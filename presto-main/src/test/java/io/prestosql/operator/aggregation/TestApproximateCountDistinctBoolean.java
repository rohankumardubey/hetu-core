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
package io.prestosql.operator.aggregation;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.primitives.Booleans;
import io.prestosql.spi.connector.QualifiedObjectName;
import io.prestosql.spi.function.Signature;
import io.prestosql.spi.type.Type;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static io.prestosql.spi.function.FunctionKind.AGGREGATE;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.BooleanType.BOOLEAN;
import static io.prestosql.spi.type.DoubleType.DOUBLE;

public class TestApproximateCountDistinctBoolean
        extends AbstractTestApproximateCountDistinct
{
    @Override
    protected InternalAggregationFunction getAggregationFunction()
    {
        return metadata.getFunctionAndTypeManager().getAggregateFunctionImplementation(
                new Signature(QualifiedObjectName.valueOfDefaultFunction("approx_distinct"), AGGREGATE, BIGINT.getTypeSignature(), BOOLEAN.getTypeSignature(), DOUBLE.getTypeSignature()));
    }

    @Override
    protected Type getValueType()
    {
        return BOOLEAN;
    }

    @Override
    protected Object randomValue()
    {
        return ThreadLocalRandom.current().nextBoolean();
    }

    @DataProvider(name = "inputSequences")
    public Object[][] inputSequences()
    {
        return new Object[][] {
                {true},
                {false},
                {true, false},
                {true, true, true},
                {false, false, false},
                {true, false, true, false},
                };
    }

    @Test(dataProvider = "inputSequences")
    public void testNonEmptyInputs(boolean... inputSequence)
    {
        List<Boolean> values = Booleans.asList(inputSequence);
        assertCount(values, 0, distinctCount(values));
    }

    @Test
    public void testNoInput()
    {
        assertCount(ImmutableList.of(), 0, 0);
    }

    private long distinctCount(List<Boolean> inputSequence)
    {
        return ImmutableSet.copyOf(inputSequence).size();
    }

    @Override
    protected int getUniqueValuesCount()
    {
        return 2;
    }
}
