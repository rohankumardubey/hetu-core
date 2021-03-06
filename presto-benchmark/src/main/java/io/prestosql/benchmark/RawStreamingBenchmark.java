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
package io.prestosql.benchmark;

import com.google.common.collect.ImmutableList;
import io.prestosql.operator.OperatorFactory;
import io.prestosql.spi.plan.PlanNodeId;
import io.prestosql.testing.LocalQueryRunner;

import java.util.List;

import static io.prestosql.benchmark.BenchmarkQueryRunner.createLocalQueryRunner;

public class RawStreamingBenchmark
        extends AbstractSimpleOperatorBenchmark
{
    public RawStreamingBenchmark(LocalQueryRunner localQueryRunner)
    {
        super(localQueryRunner, "raw_stream", 10, 100);
    }

    @Override
    protected List<? extends OperatorFactory> createOperatorFactories()
    {
        OperatorFactory tableScanOperator = createTableScanOperator(0, new PlanNodeId("test"), "orders", "totalprice");

        return ImmutableList.of(tableScanOperator);
    }

    public static void main(String[] args)
    {
        new RawStreamingBenchmark(createLocalQueryRunner()).runBenchmark(new SimpleLineBenchmarkResultWriter(System.out));
    }
}
