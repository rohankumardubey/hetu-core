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
package io.prestosql;

import io.prestosql.spi.Page;
import io.prestosql.spi.PageIndexer;
import io.prestosql.spi.PageIndexerFactory;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.type.Type;
import io.prestosql.sql.gen.JoinCompiler;

import javax.inject.Inject;

import java.util.List;

import static java.util.Objects.requireNonNull;

public class GroupByHashPageIndexerFactory
        implements PageIndexerFactory
{
    private final JoinCompiler joinCompiler;

    @Inject
    public GroupByHashPageIndexerFactory(JoinCompiler joinCompiler)
    {
        this.joinCompiler = requireNonNull(joinCompiler, "joinCompiler is null");
    }

    @Override
    public PageIndexer createPageIndexer(List<? extends Type> types)
    {
        if (types.isEmpty()) {
            return new NoHashPageIndexer();
        }
        return new GroupByHashPageIndexer(types, joinCompiler);
    }

    private static class NoHashPageIndexer
            implements PageIndexer
    {
        @Override
        public int[] indexPage(Page page)
        {
            return new int[page.getPositionCount()];
        }

        @Override
        public int getMaxIndex()
        {
            return 0;
        }

        @Override
        public Object capture(BlockEncodingSerdeProvider serdeProvider)
        {
            return null;
        }

        @Override
        public void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
        {
        }
    }
}
