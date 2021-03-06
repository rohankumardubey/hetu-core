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
package io.prestosql.sql.gen;

import io.airlift.bytecode.BytecodeNode;
import io.prestosql.spi.function.FunctionHandle;
import io.prestosql.spi.relation.RowExpression;
import io.prestosql.spi.type.Type;

import java.util.List;

public interface BytecodeGenerator
{
    BytecodeNode generateExpression(FunctionHandle functionHandle, BytecodeGeneratorContext context, Type returnType, List<RowExpression> arguments);
}
