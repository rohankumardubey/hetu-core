package io.prestosql.operator.scalar;
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

import com.google.common.collect.ImmutableList;
import io.prestosql.metadata.BoundVariables;
import io.prestosql.metadata.FunctionAndTypeManager;
import io.prestosql.spi.block.Block;
import io.prestosql.spi.function.BuiltInScalarFunctionImplementation;
import io.prestosql.spi.type.RowType;
import io.prestosql.spi.type.Type;

import java.lang.invoke.MethodHandle;
import java.util.List;

import static io.prestosql.spi.function.BuiltInScalarFunctionImplementation.ArgumentProperty.valueTypeArgumentProperty;
import static io.prestosql.spi.function.BuiltInScalarFunctionImplementation.NullConvention.RETURN_NULL_ON_NULL;
import static io.prestosql.spi.function.OperatorType.LESS_THAN;
import static io.prestosql.spi.function.OperatorType.LESS_THAN_OR_EQUAL;
import static io.prestosql.spi.util.Reflection.methodHandle;

public final class RowLessThanOrEqualOperator
        extends RowComparisonOperator
{
    public static final RowLessThanOrEqualOperator ROW_LESS_THAN_OR_EQUAL = new RowLessThanOrEqualOperator();
    private static final MethodHandle METHOD_HANDLE = methodHandle(RowLessThanOrEqualOperator.class, "lessOrEqual", RowType.class, List.class, Block.class, Block.class);

    private RowLessThanOrEqualOperator()
    {
        super(LESS_THAN_OR_EQUAL);
    }

    @Override
    public BuiltInScalarFunctionImplementation specialize(BoundVariables boundVariables, int arity, FunctionAndTypeManager functionAndTypeManager)
    {
        Type type = boundVariables.getTypeVariable("T");
        return new BuiltInScalarFunctionImplementation(
                false,
                ImmutableList.of(
                        valueTypeArgumentProperty(RETURN_NULL_ON_NULL),
                        valueTypeArgumentProperty(RETURN_NULL_ON_NULL)),
                METHOD_HANDLE.bindTo(type).bindTo(getMethodHandles((RowType) type, functionAndTypeManager, LESS_THAN)));
    }

    public static boolean lessOrEqual(
            RowType rowType,
            List<MethodHandle> lessThanFunctions,
            Block leftRow, Block rightRow)
    {
        int compareResult = compare(rowType, lessThanFunctions, leftRow, rightRow);
        return compareResult >= 0;
    }
}
