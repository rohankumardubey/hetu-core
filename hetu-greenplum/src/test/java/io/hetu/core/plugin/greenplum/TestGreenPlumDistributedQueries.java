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
package io.hetu.core.plugin.greenplum;

import com.google.common.collect.ImmutableMap;
import io.airlift.testing.postgresql.TestingPostgreSqlServer;
import io.airlift.tpch.TpchTable;
import io.prestosql.tests.AbstractTestDistributedQueries;
import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.io.IOException;

import static io.hetu.core.plugin.greenplum.GreenPlumQueryRunner.createGreenPlumQueryRunner;

@Test
public class TestGreenPlumDistributedQueries
        extends AbstractTestDistributedQueries
{
    private final TestingPostgreSqlServer postgreSqlServer;

    public TestGreenPlumDistributedQueries()
            throws Exception
    {
        this(new TestingPostgreSqlServer("testuser", "tpch"));
    }

    public TestGreenPlumDistributedQueries(TestingPostgreSqlServer postgreSqlServer)
    {
        super(() -> createGreenPlumQueryRunner(postgreSqlServer, ImmutableMap.of(), TpchTable.getTables()));
        this.postgreSqlServer = postgreSqlServer;
    }

    @AfterClass(alwaysRun = true)
    public final void destroy()
            throws IOException
    {
        postgreSqlServer.close();
    }

    @Override
    protected boolean supportsViews()
    {
        return false;
    }

    @Override
    protected boolean supportsArrays()
    {
        // Arrays are supported conditionally. Check the defaults.
        return new GreenPlumSqlConfig().getArrayMapping() != GreenPlumSqlConfig.ArrayMapping.DISABLED;
    }

    @Override
    public void testCommentTable()
    {
        // PostgreSQL connector currently does not support comment on table
        assertQueryFails("COMMENT ON TABLE orders IS 'hello'", "This connector does not support setting table comments");
    }

    @Override
    public void testDelete()
    {
        // delete is not supported
    }

    // PostgreSQL specific tests should normally go in TestGreenPlumIntegrationSmokeTest
}
