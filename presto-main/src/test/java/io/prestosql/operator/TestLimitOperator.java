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
package io.prestosql.operator;

import com.google.common.collect.ImmutableList;
import io.prestosql.operator.LimitOperator.LimitOperatorFactory;
import io.prestosql.spi.Page;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.testing.MaterializedResult;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;

import static io.airlift.concurrent.Threads.daemonThreadsNamed;
import static io.prestosql.RowPagesBuilder.rowPagesBuilder;
import static io.prestosql.SequencePageBuilder.createSequencePage;
import static io.prestosql.SessionTestUtils.TEST_SESSION;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.testing.MaterializedResult.resultBuilder;
import static io.prestosql.testing.TestingTaskContext.createTaskContext;
import static java.util.concurrent.Executors.newCachedThreadPool;
import static java.util.concurrent.Executors.newScheduledThreadPool;

@Test(singleThreaded = true)
public class TestLimitOperator
{
    private ExecutorService executor;
    private ScheduledExecutorService scheduledExecutor;
    private DriverContext driverContext;

    @BeforeMethod
    public void setUp()
    {
        executor = newCachedThreadPool(daemonThreadsNamed("test-executor-%s"));
        scheduledExecutor = newScheduledThreadPool(2, daemonThreadsNamed("test-scheduledExecutor-%s"));
        driverContext = createTaskContext(executor, scheduledExecutor, TEST_SESSION)
                .addPipelineContext(0, true, true, false)
                .addDriverContext();
    }

    @AfterMethod(alwaysRun = true)
    public void tearDown()
    {
        executor.shutdownNow();
        scheduledExecutor.shutdownNow();
    }

    @Test
    public void testLimitWithPageAlignment()
    {
        List<Page> input = rowPagesBuilder(BIGINT)
                .addSequencePage(3, 1)
                .addSequencePage(2, 4)
                .addSequencePage(2, 6)
                .build();

        OperatorFactory operatorFactory = new LimitOperatorFactory(0, new PlanNodeId("test"), 5);

        MaterializedResult expected = resultBuilder(driverContext.getSession(), BIGINT)
                .page(createSequencePage(ImmutableList.of(BIGINT), 3, 1))
                .page(createSequencePage(ImmutableList.of(BIGINT), 2, 4))
                .build();

        OperatorAssertion.assertOperatorEquals(operatorFactory, driverContext, input, expected);
    }

    @Test
    public void testLimitSnapshotSimple()
    {
        List<Page> input = rowPagesBuilder(BIGINT)
                .addSequencePage(3, 1)
                .addSequencePage(1, 4)
                .addSequencePage(1, 5)
                .addSequencePage(1, 6)
                .addSequencePage(3, 7)
                .build();

        OperatorFactory operatorFactory = new LimitOperatorFactory(0, new PlanNodeId("test"), 7);

        MaterializedResult expected = resultBuilder(driverContext.getSession(), BIGINT)
                .page(createSequencePage(ImmutableList.of(BIGINT), 3, 1))
                .page(createSequencePage(ImmutableList.of(BIGINT), 1, 4))
                .page(createSequencePage(ImmutableList.of(BIGINT), 1, 5))
                .page(createSequencePage(ImmutableList.of(BIGINT), 1, 6))
                .page(createSequencePage(ImmutableList.of(BIGINT), 1, 7))
                .build();

        OperatorAssertion.assertOperatorEqualsWithSimpleStateComparison(operatorFactory, driverContext, input, expected, createExpectedMappingSimple());
    }

    private Map<String, Object> createExpectedMappingSimple()
    {
        Map<String, Object> expectedMapping = new HashMap<>();
        expectedMapping.put("operatorContext", 0);
        expectedMapping.put("remainingLimit", 2L);
        return expectedMapping;
    }

    @Test
    public void testLimitSnapshotWithRestore()
    {
        List<Page> input = rowPagesBuilder(BIGINT)
                .addSequencePage(3, 1)
                .addSequencePage(1, 4)
                .addSequencePage(1, 5)
                .addSequencePage(1, 6)
                .addSequencePage(3, 7)
                .build();

        OperatorFactory operatorFactory = new LimitOperatorFactory(0, new PlanNodeId("test"), 7);

        MaterializedResult expected = resultBuilder(driverContext.getSession(), BIGINT)
                .page(createSequencePage(ImmutableList.of(BIGINT), 3, 1))
                .page(createSequencePage(ImmutableList.of(BIGINT), 1, 4))
                .page(createSequencePage(ImmutableList.of(BIGINT), 1, 5))
                .page(createSequencePage(ImmutableList.of(BIGINT), 1, 6))
                .page(createSequencePage(ImmutableList.of(BIGINT), 1, 7))
                .build();

        OperatorAssertion.assertOperatorEqualsWithStateComparison(operatorFactory, driverContext, input, expected, createExpectedMappingRestore());
    }

    private Map<String, Object> createExpectedMappingRestore()
    {
        Map<String, Object> expectedMapping = new HashMap<>();
        expectedMapping.put("operatorContext", 0);
        expectedMapping.put("remainingLimit", 3L);
        return expectedMapping;
    }

    @Test
    public void testLimitWithBlockView()
    {
        List<Page> input = rowPagesBuilder(BIGINT)
                .addSequencePage(3, 1)
                .addSequencePage(2, 4)
                .addSequencePage(2, 6)
                .build();

        OperatorFactory operatorFactory = new LimitOperatorFactory(0, new PlanNodeId("test"), 6);

        List<Page> expected = rowPagesBuilder(BIGINT)
                .addSequencePage(3, 1)
                .addSequencePage(2, 4)
                .addSequencePage(1, 6)
                .build();

        OperatorAssertion.assertOperatorEquals(operatorFactory, ImmutableList.of(BIGINT), driverContext, input, expected);
    }
}
