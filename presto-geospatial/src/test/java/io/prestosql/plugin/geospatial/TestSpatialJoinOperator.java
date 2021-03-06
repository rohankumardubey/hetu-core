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
package io.prestosql.plugin.geospatial;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.RowPagesBuilder;
import io.prestosql.geospatial.KdbTree;
import io.prestosql.geospatial.KdbTreeUtils;
import io.prestosql.geospatial.Rectangle;
import io.prestosql.operator.Driver;
import io.prestosql.operator.DriverContext;
import io.prestosql.operator.InternalJoinFilterFunction;
import io.prestosql.operator.Operator;
import io.prestosql.operator.OperatorFactory;
import io.prestosql.operator.PagesIndex.TestingFactory;
import io.prestosql.operator.PagesSpatialIndex;
import io.prestosql.operator.PagesSpatialIndexFactory;
import io.prestosql.operator.PipelineContext;
import io.prestosql.operator.SpatialIndexBuilderOperator.SpatialIndexBuilderOperatorFactory;
import io.prestosql.operator.SpatialIndexBuilderOperator.SpatialPredicate;
import io.prestosql.operator.SpatialJoinOperator.SpatialJoinOperatorFactory;
import io.prestosql.operator.StandardJoinFilterFunction;
import io.prestosql.operator.TaskContext;
import io.prestosql.operator.ValuesOperator;
import io.prestosql.spi.Page;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.sql.gen.JoinFilterFunctionCompiler;
import io.prestosql.sql.planner.plan.SpatialJoinNode.Type;
import io.prestosql.testing.MaterializedResult;
import io.prestosql.testing.TestingTaskContext;
import io.prestosql.testing.assertions.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;

import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.prestosql.RowPagesBuilder.rowPagesBuilder;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.geospatial.KdbTree.Node.newInternal;
import static io.prestosql.geospatial.KdbTree.Node.newLeaf;
import static io.prestosql.operator.OperatorAssertion.assertOperatorEquals;
import static io.prestosql.operator.OperatorAssertion.toMaterializedResult;
import static io.prestosql.operator.OperatorAssertion.toPages;
import static io.prestosql.plugin.geospatial.GeoFunctions.stGeometryFromText;
import static io.prestosql.plugin.geospatial.GeoFunctions.stPoint;
import static io.prestosql.plugin.geospatial.GeometryType.GEOMETRY;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.sql.planner.plan.SpatialJoinNode.Type.INNER;
import static io.prestosql.sql.planner.plan.SpatialJoinNode.Type.LEFT;
import static io.prestosql.testing.MaterializedResult.resultBuilder;
import static java.util.Collections.emptyIterator;
import static java.util.concurrent.Executors.newScheduledThreadPool;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

@Test(singleThreaded = true)
public class TestSpatialJoinOperator
{
    private static final String KDB_TREE_JSON = KdbTreeUtils.toJson(
            new KdbTree(newInternal(new Rectangle(-2, -2, 15, 15),
                    newInternal(new Rectangle(-2, -2, 6, 15),
                            newLeaf(new Rectangle(-2, -2, 6, 1), 1),
                            newLeaf(new Rectangle(-2, 1, 6, 15), 2)),
                    newLeaf(new Rectangle(6, -2, 15, 15), 0))));

    //  2 intersecting polygons: A and B
    private static final Slice POLYGON_A = stGeometryFromText(Slices.utf8Slice("POLYGON ((0 0, -0.5 2.5, 0 5, 2.5 5.5, 5 5, 5.5 2.5, 5 0, 2.5 -0.5, 0 0))"));
    private static final Slice POLYGON_B = stGeometryFromText(Slices.utf8Slice("POLYGON ((4 4, 3.5 7, 4 10, 7 10.5, 10 10, 10.5 7, 10 4, 7 3.5, 4 4))"));

    // A set of points: X in A, Y in A and B, Z in B, W outside of A and B
    private static final Slice POINT_X = stPoint(1, 1);
    private static final Slice POINT_Y = stPoint(4.5, 4.5);
    private static final Slice POINT_Z = stPoint(6, 6);
    private static final Slice POINT_W = stPoint(20, 20);
    private static final Slice POINT_R = stPoint(4.5, 4);

    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;

    @BeforeMethod
    public void setUp()
    {
        // Before/AfterMethod is chosen here because the executor needs to be shutdown
        // after every single test case to terminate outstanding threads, if any.

        // The line below is the same as newCachedThreadPool(daemonThreadsNamed(...)) except RejectionExecutionHandler.
        // RejectionExecutionHandler is set to DiscardPolicy (instead of the default AbortPolicy) here.
        // Otherwise, a large number of RejectedExecutionException will flood logging, resulting in Travis failure.
        executor = new ThreadPoolExecutor(
                0,
                Integer.MAX_VALUE,
                60L,
                SECONDS,
                new SynchronousQueue<>(),
                daemonThreadsNamed("test-executor-%s"),
                new ThreadPoolExecutor.DiscardPolicy());
        scheduledExecutor = newScheduledThreadPool(2, daemonThreadsNamed("test-scheduledExecutor-%s"));
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown()
    {
        executor.shutdownNow();
        scheduledExecutor.shutdownNow();
    }

    @Test
    public void testSpatialJoin()
    {
        TaskContext taskContext = createTaskContext();
        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POLYGON_A, "A")
                .row(null, "null")
                .pageBreak()
                .row(POLYGON_B, "B");

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POINT_X, "x")
                .row(null, "null")
                .row(POINT_Y, "y")
                .pageBreak()
                .row(POINT_Z, "z")
                .pageBreak()
                .row(POINT_W, "w");

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("x", "A")
                .row("y", "A")
                .row("y", "B")
                .row("z", "B")
                .build();

        assertSpatialJoin(taskContext, INNER, buildPages, probePages, expected);
    }

    @Test
    public void testSpatialJoinSnapshot()
    {
        TaskContext taskContext = createTaskContext();
        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POLYGON_A, "A")
                .row(null, "null")
                .pageBreak()
                .row(POLYGON_B, "B");

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POINT_X, "x")
                .row(null, "null")
                .row(POINT_Y, "y")
                .pageBreak()
                .row(POINT_Z, "z")
                .pageBreak()
                .row(POINT_W, "w")
                .pageBreak()
                .row(POINT_R, "r");

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("x", "A")
                .row("y", "A")
                .row("y", "B")
                .row("z", "B")
                .row("r", "A")
                .row("r", "B")
                .build();

        assertSpatialJoinSnapshot(taskContext, INNER, buildPages, probePages, expected);
    }

    @Test
    public void testSpatialLeftJoin()
    {
        TaskContext taskContext = createTaskContext();
        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POLYGON_A, "A")
                .row(null, "null")
                .pageBreak()
                .row(POLYGON_B, "B");

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POINT_X, "x")
                .row(null, "null")
                .row(POINT_Y, "y")
                .pageBreak()
                .row(POINT_Z, "z")
                .pageBreak()
                .row(POINT_W, "w");
        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("x", "A")
                .row("null", null)
                .row("y", "A")
                .row("y", "B")
                .row("z", "B")
                .row("w", null)
                .build();

        assertSpatialJoin(taskContext, LEFT, buildPages, probePages, expected);
    }

    private void assertSpatialJoin(TaskContext taskContext, Type joinType, RowPagesBuilder buildPages, RowPagesBuilder probePages, MaterializedResult expected)
    {
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true, false).addDriverContext();
        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(driverContext, (build, probe, r) -> build.contains(probe), Optional.empty(), Optional.empty(), buildPages);
        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), joinType, probePages.getTypes(), Ints.asList(1), 0, Optional.empty(), pagesSpatialIndexFactory);
        assertOperatorEquals(joinOperatorFactory, driverContext, probePages.build(), expected);
    }

    private void assertSpatialJoinSnapshot(TaskContext taskContext, Type joinType, RowPagesBuilder buildPages, RowPagesBuilder probePages, MaterializedResult expected)
    {
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true, false).addDriverContext();
        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndexSnapshot(driverContext, (build, probe, r) -> build.contains(probe), Optional.empty(), Optional.empty(), buildPages);
        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), joinType, probePages.getTypes(), Ints.asList(1), 0, Optional.empty(), pagesSpatialIndexFactory);
        assetResultEqualsSnapshot(joinOperatorFactory, driverContext, probePages.build(), expected);
    }

    private void assetResultEqualsSnapshot(OperatorFactory operatorFactory, DriverContext driverContext, List<Page> input, MaterializedResult expected)
    {
        Operator operator = operatorFactory.createOperator(driverContext);

        ImmutableList.Builder<Page> outputPages = ImmutableList.builder();
        int inputIdx = 0;
        Object snapshot = null;
        boolean restored = false;

        for (int loopsSinceLastPage = 0; loopsSinceLastPage < 1_000; loopsSinceLastPage++) {
            if (inputIdx < input.size() && operator.needsInput()) {
                if (inputIdx == input.size() / 2) {
                    snapshot = operator.capture(operator.getOperatorContext().getDriverContext().getSerde());
                }
                else if (inputIdx == input.size() - 1 && snapshot != null && !restored) {
                    operator.restore(snapshot, operator.getOperatorContext().getDriverContext().getSerde());
                    inputIdx = input.size() / 2;
                    restored = true;
                }
                operator.addInput(input.get(inputIdx));
                inputIdx++;
                loopsSinceLastPage = 0;
                //Operator doesn't produce output in the process, but needs getOutput call to clear for next input.
                operator.getOutput();
            }
        }

        for (int loopsSinceLastPage = 0; !operator.isFinished() && loopsSinceLastPage < 1_000; loopsSinceLastPage++) {
            operator.finish();
            Page outputPage = operator.getOutput();
            if (outputPage != null && outputPage.getPositionCount() != 0) {
                outputPages.add(outputPage);
                loopsSinceLastPage = 0;
            }
        }

        assertEquals(operator.isFinished(), true, "Operator did not finish");
        assertEquals(operator.needsInput(), false, "Operator still wants input");
        assertEquals(operator.isBlocked().isDone(), true, "Operator is blocked");

        List<Page> output = outputPages.build();
        MaterializedResult actual = toMaterializedResult(driverContext.getSession(), expected.getTypes(), output);
        Assert.assertEquals(actual, expected);
    }

    @Test
    public void testEmptyBuild()
    {
        TaskContext taskContext = createTaskContext();
        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR));

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POINT_X, "x")
                .row(null, "null")
                .row(POINT_Y, "y")
                .pageBreak()
                .row(POINT_Z, "z")
                .pageBreak()
                .row(POINT_W, "w");

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR)).build();

        assertSpatialJoin(taskContext, INNER, buildPages, probePages, expected);
    }

    @Test
    public void testEmptyBuildLeftJoin()
    {
        TaskContext taskContext = createTaskContext();
        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR));

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POINT_X, "x")
                .row(null, "null")
                .row(POINT_Y, "y")
                .pageBreak()
                .row(POINT_Z, "z")
                .pageBreak()
                .row(POINT_W, "w");

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("x", null)
                .row("null", null)
                .row("y", null)
                .row("z", null)
                .row("w", null)
                .build();

        assertSpatialJoin(taskContext, LEFT, buildPages, probePages, expected);
    }

    @Test
    public void testEmptyProbe()
    {
        TaskContext taskContext = createTaskContext();
        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POLYGON_A, "A")
                .row(null, "null")
                .pageBreak()
                .row(POLYGON_B, "B");

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR));

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR)).build();

        assertSpatialJoin(taskContext, INNER, buildPages, probePages, expected);
    }

    @Test
    public void testYield()
    {
        // create a filter function that yields for every probe match
        // verify we will yield #match times totally

        TaskContext taskContext = createTaskContext();
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true, false).addDriverContext();

        // force a yield for every match
        AtomicInteger filterFunctionCalls = new AtomicInteger();
        InternalJoinFilterFunction filterFunction = new TestInternalJoinFilterFunction((
                (leftPosition, leftPage, rightPosition, rightPage) -> {
                    filterFunctionCalls.incrementAndGet();
                    driverContext.getYieldSignal().forceYieldForTesting();
                    return true;
                }));

        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(POLYGON_A, "A")
                .pageBreak()
                .row(POLYGON_B, "B");
        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(driverContext, (build, probe, r) -> build.contains(probe), Optional.empty(), Optional.of(filterFunction), buildPages);

        // 10 points in polygon A (x0...x9)
        // 10 points in polygons A and B (y0...y9)
        // 10 points in polygon B (z0...z9)
        // 40 total matches
        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR));
        for (int i = 0; i < 10; i++) {
            probePages.row(stPoint(1 + 0.1 * i, 1 + 0.1 * i), "x" + i);
        }
        for (int i = 0; i < 10; i++) {
            probePages.row(stPoint(4.5 + 0.01 * i, 4.5 + 0.01 * i), "y" + i);
        }
        for (int i = 0; i < 10; i++) {
            probePages.row(stPoint(6 + 0.1 * i, 6 + 0.1 * i), "z" + i);
        }
        List<Page> probeInput = probePages.build();

        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), INNER, probePages.getTypes(), Ints.asList(1), 0, Optional.empty(), pagesSpatialIndexFactory);

        Operator operator = joinOperatorFactory.createOperator(driverContext);
        assertTrue(operator.needsInput());
        operator.addInput(probeInput.get(0));
        operator.finish();

        // we will yield 40 times due to filterFunction
        for (int i = 0; i < 40; i++) {
            driverContext.getYieldSignal().setWithDelay(5 * SECONDS.toNanos(1), driverContext.getYieldExecutor());
            assertNull(operator.getOutput());
            assertEquals(filterFunctionCalls.get(), i + 1, "Expected join to stop processing (yield) after calling filter function once");
            driverContext.getYieldSignal().reset();
        }
        // delayed yield is not going to prevent operator from producing a page now (yield won't be forced because filter function won't be called anymore)
        driverContext.getYieldSignal().setWithDelay(5 * SECONDS.toNanos(1), driverContext.getYieldExecutor());
        Page output = operator.getOutput();
        assertNotNull(output);

        // make sure we have 40 matches
        assertEquals(output.getPositionCount(), 40);
    }

    @Test(dataProvider = "testDuplicateProbeFactoryDataProvider")
    public void testDuplicateProbeFactory(boolean createSecondaryOperators)
            throws Exception
    {
        TaskContext taskContext = createTaskContext();
        PipelineContext pipelineContext = taskContext.addPipelineContext(0, true, true, false);
        DriverContext probeDriver = pipelineContext.addDriverContext();
        DriverContext buildDriver = pipelineContext.addDriverContext();

        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR, DOUBLE))
                .row(stPoint(0, 0), "0_0", 1.5);
        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(buildDriver, (build, probe, r) -> build.distance(probe) <= r.getAsDouble(), Optional.of(2), Optional.empty(), buildPages);

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(stPoint(0, 1), "0_1");
        OperatorFactory firstFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), INNER, probePages.getTypes(), Ints.asList(1), 0, Optional.empty(), pagesSpatialIndexFactory);

        for (int i = 0; i < 3; i++) {
            DriverContext secondDriver = pipelineContext.addDriverContext();
            OperatorFactory secondFactory = firstFactory.duplicate();
            if (createSecondaryOperators) {
                try (Operator secondOperator = secondFactory.createOperator(secondDriver)) {
                    assertEquals(toPages(secondOperator, emptyIterator()), ImmutableList.of());
                }
            }
            secondFactory.noMoreOperators();
        }

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("0_1", "0_0")
                .build();
        assertOperatorEquals(firstFactory, probeDriver, probePages.build(), expected);
    }

    @DataProvider
    public Object[][] testDuplicateProbeFactoryDataProvider()
    {
        return new Object[][] {
                {true},
                {false},
        };
    }

    @Test
    public void testDistanceQuery()
    {
        TaskContext taskContext = createTaskContext();
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true, false).addDriverContext();

        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR, DOUBLE))
                .row(stPoint(0, 0), "0_0", 1.5)
                .row(null, "null", 1.5)
                .row(stPoint(1, 0), "1_0", 1.5)
                .pageBreak()
                .row(stPoint(3, 0), "3_0", 1.5)
                .pageBreak()
                .row(stPoint(10, 0), "10_0", 1.5);
        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(driverContext, (build, probe, r) -> build.distance(probe) <= r.getAsDouble(), Optional.of(2), Optional.empty(), buildPages);

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR))
                .row(stPoint(0, 1), "0_1")
                .row(null, "null")
                .row(stPoint(1, 1), "1_1")
                .pageBreak()
                .row(stPoint(3, 1), "3_1")
                .pageBreak()
                .row(stPoint(10, 1), "10_1");
        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), INNER, probePages.getTypes(), Ints.asList(1), 0, Optional.empty(), pagesSpatialIndexFactory);

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("0_1", "0_0")
                .row("0_1", "1_0")
                .row("1_1", "0_0")
                .row("1_1", "1_0")
                .row("3_1", "3_0")
                .row("10_1", "10_0")
                .build();

        assertOperatorEquals(joinOperatorFactory, driverContext, probePages.build(), expected);
    }

    @Test
    public void testDistributedSpatialJoin()
    {
        TaskContext taskContext = createTaskContext();
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true, true).addDriverContext();

        RowPagesBuilder buildPages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR, INTEGER))
                .row(POLYGON_A, "A", 1)
                .row(POLYGON_A, "A", 2)
                .row(null, "null", null)
                .pageBreak()
                .row(POLYGON_B, "B", 0)
                .row(POLYGON_B, "B", 2);

        RowPagesBuilder probePages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR, INTEGER))
                .row(POINT_X, "x", 2)
                .row(null, "null", null)
                .row(POINT_Y, "y", 2)
                .pageBreak()
                .row(POINT_Z, "z", 0);

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("x", "A")
                .row("y", "A")
                .row("y", "B")
                .row("z", "B")
                .build();

        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(driverContext, (build, probe, r) -> build.contains(probe), Optional.empty(), Optional.of(2), Optional.of(KDB_TREE_JSON), Optional.empty(), buildPages);
        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), INNER, probePages.getTypes(), Ints.asList(1), 0, Optional.of(2), pagesSpatialIndexFactory);
        assertOperatorEquals(joinOperatorFactory, driverContext, probePages.build(), expected);
    }

    @Test
    public void testDistributedSpatialSelfJoin()
    {
        TaskContext taskContext = createTaskContext();
        DriverContext driverContext = taskContext.addPipelineContext(0, true, true, true).addDriverContext();

        RowPagesBuilder pages = rowPagesBuilder(ImmutableList.of(GEOMETRY, VARCHAR, INTEGER))
                .row(POLYGON_A, "A", 1)
                .row(POLYGON_A, "A", 2)
                .row(null, "null", null)
                .pageBreak()
                .row(POLYGON_B, "B", 0)
                .row(POLYGON_B, "B", 2);

        MaterializedResult expected = resultBuilder(taskContext.getSession(), ImmutableList.of(VARCHAR, VARCHAR))
                .row("A", "A")
                .row("A", "B")
                .row("B", "A")
                .row("B", "B")
                .build();

        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildIndex(driverContext, (build, probe, r) -> build.intersects(probe), Optional.empty(), Optional.of(2), Optional.of(KDB_TREE_JSON), Optional.empty(), pages);
        OperatorFactory joinOperatorFactory = new SpatialJoinOperatorFactory(2, new PlanNodeId("test"), INNER, pages.getTypes(), Ints.asList(1), 0, Optional.of(2), pagesSpatialIndexFactory);
        assertOperatorEquals(joinOperatorFactory, driverContext, pages.build(), expected);
    }

    private PagesSpatialIndexFactory buildIndexSnapshot(DriverContext driverContext, SpatialPredicate spatialRelationshipTest, Optional<Integer> radiusChannel, Optional<InternalJoinFilterFunction> filterFunction, RowPagesBuilder buildPages)
    {
        return buildIndexSnapshot(driverContext, spatialRelationshipTest, radiusChannel, Optional.empty(), Optional.empty(), filterFunction, buildPages);
    }

    private PagesSpatialIndexFactory buildIndexSnapshot(DriverContext driverContext, SpatialPredicate spatialRelationshipTest, Optional<Integer> radiusChannel, Optional<Integer> partitionChannel, Optional<String> kdbTreeJson, Optional<InternalJoinFilterFunction> filterFunction, RowPagesBuilder buildPages)
    {
        Optional<JoinFilterFunctionCompiler.JoinFilterFunctionFactory> filterFunctionFactory = filterFunction
                .map(function -> (session, addresses, pages) -> new StandardJoinFilterFunction(function, addresses, pages));

        SpatialIndexBuilderOperatorFactory buildOperatorFactory = new SpatialIndexBuilderOperatorFactory(
                1,
                new PlanNodeId("test"),
                buildPages.getTypes(),
                Ints.asList(1),
                0,
                radiusChannel,
                partitionChannel,
                spatialRelationshipTest,
                kdbTreeJson,
                filterFunctionFactory,
                10_000,
                new TestingFactory(false));

        Operator operator = buildOperatorFactory.createOperator(driverContext);
        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildOperatorFactory.getPagesSpatialIndexFactory();
        ListenableFuture<PagesSpatialIndex> pagesSpatialIndex = pagesSpatialIndexFactory.createPagesSpatialIndex();

        List<Page> buildSideInputs = buildPages.build();
        int inputIndex = 0;
        boolean restored = false;
        Object snapshot = null;

        //When build side is not done, keep looping
        while (!pagesSpatialIndex.isDone()) {
            if (operator.needsInput() && inputIndex < buildSideInputs.size()) {
                operator.addInput(buildSideInputs.get(inputIndex));
                inputIndex++;
            }
            //Take snapshot in the middle
            if (inputIndex == buildSideInputs.size() / 2 && !restored) {
                snapshot = operator.capture(operator.getOperatorContext().getDriverContext().getSerde());
            }
            //When input pages are used up, restore operator to snapshot and move inputIndex back to when snapshot was taken
            if (inputIndex == buildSideInputs.size() && !restored) {
                assertTrue(snapshot != null);
                operator.restore(snapshot, operator.getOperatorContext().getDriverContext().getSerde());
                restored = true;
                inputIndex = buildSideInputs.size() / 2;
            }
            //Used up all provided build side inputs and have done rollback process, finish build operator, this will cause future to be done
            if (inputIndex >= buildSideInputs.size() && restored) {
                operator.finish();
            }
        }

        return pagesSpatialIndexFactory;
    }

    private PagesSpatialIndexFactory buildIndex(DriverContext driverContext, SpatialPredicate spatialRelationshipTest, Optional<Integer> radiusChannel, Optional<InternalJoinFilterFunction> filterFunction, RowPagesBuilder buildPages)
    {
        return buildIndex(driverContext, spatialRelationshipTest, radiusChannel, Optional.empty(), Optional.empty(), filterFunction, buildPages);
    }

    private PagesSpatialIndexFactory buildIndex(DriverContext driverContext, SpatialPredicate spatialRelationshipTest, Optional<Integer> radiusChannel, Optional<Integer> partitionChannel, Optional<String> kdbTreeJson, Optional<InternalJoinFilterFunction> filterFunction, RowPagesBuilder buildPages)
    {
        Optional<JoinFilterFunctionCompiler.JoinFilterFunctionFactory> filterFunctionFactory = filterFunction
                .map(function -> (session, addresses, pages) -> new StandardJoinFilterFunction(function, addresses, pages));

        ValuesOperator.ValuesOperatorFactory valuesOperatorFactory = new ValuesOperator.ValuesOperatorFactory(0, new PlanNodeId("test"), buildPages.build());
        SpatialIndexBuilderOperatorFactory buildOperatorFactory = new SpatialIndexBuilderOperatorFactory(
                1,
                new PlanNodeId("test"),
                buildPages.getTypes(),
                Ints.asList(1),
                0,
                radiusChannel,
                partitionChannel,
                spatialRelationshipTest,
                kdbTreeJson,
                filterFunctionFactory,
                10_000,
                new TestingFactory(false));

        Driver driver = Driver.createDriver(driverContext,
                valuesOperatorFactory.createOperator(driverContext),
                buildOperatorFactory.createOperator(driverContext));

        PagesSpatialIndexFactory pagesSpatialIndexFactory = buildOperatorFactory.getPagesSpatialIndexFactory();
        ListenableFuture<PagesSpatialIndex> pagesSpatialIndex = pagesSpatialIndexFactory.createPagesSpatialIndex();

        while (!pagesSpatialIndex.isDone()) {
            driver.process();
        }

        runDriverInThread(executor, driver);
        return pagesSpatialIndexFactory;
    }

    /**
     * Runs Driver in another thread until it is finished
     */
    private static void runDriverInThread(ExecutorService executor, Driver driver)
    {
        executor.execute(() -> {
            if (!driver.isFinished()) {
                try {
                    driver.process();
                }
                catch (PrestoException e) {
                    driver.getDriverContext().failed(e);
                    throw e;
                }
                runDriverInThread(executor, driver);
            }
        });
    }

    private TaskContext createTaskContext()
    {
        return TestingTaskContext.createTaskContext(executor, scheduledExecutor, TEST_SESSION);
    }

    private static class TestInternalJoinFilterFunction
            implements InternalJoinFilterFunction
    {
        public interface Lambda
        {
            boolean filter(int leftPosition, Page leftPage, int rightPosition, Page rightPage);
        }

        private final TestInternalJoinFilterFunction.Lambda lambda;

        private TestInternalJoinFilterFunction(TestInternalJoinFilterFunction.Lambda lambda)
        {
            this.lambda = lambda;
        }

        @Override
        public boolean filter(int leftPosition, Page leftPage, int rightPosition, Page rightPage)
        {
            return lambda.filter(leftPosition, leftPage, rightPosition, rightPage);
        }
    }
}
