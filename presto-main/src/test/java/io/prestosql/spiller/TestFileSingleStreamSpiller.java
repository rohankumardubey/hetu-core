/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.spiller;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;
import com.google.common.util.concurrent.ListeningExecutorService;
import io.airlift.slice.InputStreamSliceInput;
import io.hetu.core.transport.execution.buffer.PageCodecMarker;
import io.hetu.core.transport.execution.buffer.PagesSerdeUtil;
import io.hetu.core.transport.execution.buffer.SerializedPage;
import io.prestosql.memory.context.LocalMemoryContext;
import io.prestosql.operator.PageAssertions;
import io.prestosql.spi.Page;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.type.Type;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import java.util.List;

import static com.google.common.io.MoreFiles.deleteRecursively;
import static com.google.common.io.MoreFiles.listFiles;
import static com.google.common.io.RecursiveDeleteOption.ALLOW_INSECURE;
import static com.google.common.util.concurrent.MoreExecutors.listeningDecorator;
import static io.prestosql.memory.context.AggregatedMemoryContext.newSimpleAggregatedMemoryContext;
import static io.prestosql.metadata.MetadataManager.createTestMetadataManager;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.VarbinaryType.VARBINARY;
import static java.lang.Double.doubleToLongBits;
import static java.nio.file.Files.createTempDirectory;
import static java.nio.file.Files.newInputStream;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestFileSingleStreamSpiller
{
    private static final List<Type> TYPES = ImmutableList.of(BIGINT, DOUBLE, VARBINARY);

    private final ListeningExecutorService executor = listeningDecorator(newCachedThreadPool());
    private final File spillPath = createTempDirectory(getClass().getName()).toFile();

    public TestFileSingleStreamSpiller()
            throws IOException
    {}

    @AfterClass(alwaysRun = true)
    public void tearDown()
            throws Exception
    {
        executor.shutdown();
        deleteRecursively(spillPath.toPath(), ALLOW_INSECURE);
    }

    @Test
    public void testSpill()
            throws Exception
    {
        assertSpill(false, false);
    }

    @Test
    public void testSpillCompression()
            throws Exception
    {
        assertSpill(true, false);
    }

    @Test
    public void testSpillEncryption()
            throws Exception
    {
        assertSpill(false, true);
    }

    @Test
    public void testSpillEncryptionWithCompression()
            throws Exception
    {
        assertSpill(true, true);
    }

    private void assertSpill(boolean compression, boolean encryption)
            throws Exception
    {
        FileSingleStreamSpillerFactory spillerFactory = new FileSingleStreamSpillerFactory(
                executor, // executor won't be closed, because we don't call destroy() on the spiller factory
                createTestMetadataManager().getFunctionAndTypeManager().getBlockEncodingSerde(),
                new SpillerStats(),
                ImmutableList.of(spillPath.toPath()),
                1.0,
                compression,
                encryption);
        LocalMemoryContext memoryContext = newSimpleAggregatedMemoryContext().newLocalMemoryContext("test");
        SingleStreamSpiller singleStreamSpiller = spillerFactory.create(TYPES, bytes -> {}, memoryContext);
        assertTrue(singleStreamSpiller instanceof FileSingleStreamSpiller);
        FileSingleStreamSpiller spiller = (FileSingleStreamSpiller) singleStreamSpiller;

        Page page = buildPage();

        // The spillers will reserve memory in their constructors
        assertEquals(memoryContext.getBytes(), 4096);
        spiller.spill(page).get();
        spiller.spill(Iterators.forArray(page, page, page)).get();
        assertEquals(listFiles(spillPath.toPath()).size(), 1);

        // Assert the spill codec flags match the expected configuration
        try (InputStream is = newInputStream(listFiles(spillPath.toPath()).get(0))) {
            Iterator<SerializedPage> serializedPages = PagesSerdeUtil.readSerializedPages(new InputStreamSliceInput(is));
            assertTrue(serializedPages.hasNext(), "at least one page should be successfully read back");
            byte markers = serializedPages.next().getPageCodecMarkers();
            assertEquals(PageCodecMarker.COMPRESSED.isSet(markers), compression);
            assertEquals(PageCodecMarker.ENCRYPTED.isSet(markers), encryption);
        }

        // The spillers release their memory reservations when they are closed, therefore at this point
        // they will have non-zero memory reservation.

        Iterator<Page> spilledPagesIterator = spiller.getSpilledPages();
        assertEquals(memoryContext.getBytes(), FileSingleStreamSpiller.BUFFER_SIZE);
        ImmutableList<Page> spilledPages = ImmutableList.copyOf(spilledPagesIterator);
        // The spillers release their memory reservations when they are closed, therefore at this point
        // they will have non-zero memory reservation.

        assertEquals(4, spilledPages.size());
        for (int i = 0; i < 4; ++i) {
            PageAssertions.assertPageEquals(TYPES, page, spilledPages.get(i));
        }

        spiller.close();
        assertEquals(listFiles(spillPath.toPath()).size(), 0);
        assertEquals(memoryContext.getBytes(), 0);
    }

    private Page buildPage()
    {
        BlockBuilder col1 = BIGINT.createBlockBuilder(null, 1);
        BlockBuilder col2 = DOUBLE.createBlockBuilder(null, 1);
        BlockBuilder col3 = VARBINARY.createBlockBuilder(null, 1);

        col1.writeLong(42).writeLong(45).writeLong(45).writeLong(45).writeLong(45).writeLong(45).closeEntry();
        col2.writeLong(doubleToLongBits(43.0)).writeLong(doubleToLongBits(43.0)).writeLong(doubleToLongBits(43.0)).writeLong(doubleToLongBits(43.0)).writeLong(doubleToLongBits(43.0)).writeLong(doubleToLongBits(43.0)).closeEntry();
        col3.writeLong(doubleToLongBits(43.0)).writeLong(doubleToLongBits(43.0)).writeLong(doubleToLongBits(43.0)).writeLong(doubleToLongBits(43.0)).writeLong(doubleToLongBits(43.0)).writeLong(doubleToLongBits(43.0)).writeLong(1).closeEntry();

        return new Page(col1.build(), col2.build(), col3.build());
    }
}
