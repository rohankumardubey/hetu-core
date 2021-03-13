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

import io.airlift.slice.Slices;
import io.prestosql.spi.connector.QualifiedObjectName;
import io.prestosql.spi.function.Signature;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.VarcharType;

import java.util.concurrent.ThreadLocalRandom;

import static io.prestosql.spi.function.FunctionKind.AGGREGATE;
import static io.prestosql.spi.type.BigintType.BIGINT;
import static io.prestosql.spi.type.DoubleType.DOUBLE;
import static io.prestosql.spi.type.TypeSignature.parseTypeSignature;

public class TestApproximateCountDistinctVarBinary
        extends AbstractTestApproximateCountDistinct
{
    @Override
    protected InternalAggregationFunction getAggregationFunction()
    {
        return metadata.getFunctionAndTypeManager().getAggregateFunctionImplementation(
                new Signature(QualifiedObjectName.valueOfDefaultFunction("approx_distinct"), AGGREGATE, BIGINT.getTypeSignature(), parseTypeSignature("varchar"), DOUBLE.getTypeSignature()));
    }

    @Override
    protected Type getValueType()
    {
        return VarcharType.VARCHAR;
    }

    @Override
    protected Object randomValue()
    {
        int length = ThreadLocalRandom.current().nextInt(100);
        byte[] bytes = new byte[length];
        ThreadLocalRandom.current().nextBytes(bytes);

        return Slices.wrappedBuffer(bytes);
    }
}
