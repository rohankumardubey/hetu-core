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
package io.prestosql.sql.relational;

import io.prestosql.metadata.FunctionAndTypeManager;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.function.FunctionHandle;
import io.prestosql.spi.relation.CallExpression;
import io.prestosql.spi.relation.ConstantExpression;
import io.prestosql.spi.relation.DeterminismEvaluator;
import io.prestosql.spi.relation.InputReferenceExpression;
import io.prestosql.spi.relation.LambdaDefinitionExpression;
import io.prestosql.spi.relation.RowExpression;
import io.prestosql.spi.relation.RowExpressionVisitor;
import io.prestosql.spi.relation.SpecialForm;
import io.prestosql.spi.relation.VariableReferenceExpression;

import javax.inject.Inject;

import static io.prestosql.spi.StandardErrorCode.FUNCTION_IMPLEMENTATION_MISSING;
import static java.util.Objects.requireNonNull;

public class RowExpressionDeterminismEvaluator
        implements DeterminismEvaluator
{
    private final Metadata metadata;

    @Inject
    public RowExpressionDeterminismEvaluator(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    public boolean isDeterministic(RowExpression expression)
    {
        return expression.accept(new Visitor(metadata.getFunctionAndTypeManager()), null);
    }

    private static class Visitor
            implements RowExpressionVisitor<Boolean, Void>
    {
        private final FunctionAndTypeManager functionAndTypeManager;

        public Visitor(FunctionAndTypeManager functionAndTypeManager)
        {
            this.functionAndTypeManager = functionAndTypeManager;
        }

        @Override
        public Boolean visitInputReference(InputReferenceExpression reference, Void context)
        {
            return true;
        }

        @Override
        public Boolean visitConstant(ConstantExpression literal, Void context)
        {
            return true;
        }

        @Override
        public Boolean visitCall(CallExpression call, Void context)
        {
            FunctionHandle functionHandle = call.getFunctionHandle();
            try {
                if (!functionAndTypeManager.getFunctionMetadata(functionHandle).isDeterministic()) {
                    return false;
                }
            }
            catch (PrestoException e) {
                if (e.getErrorCode().getCode() != FUNCTION_IMPLEMENTATION_MISSING.toErrorCode().getCode()) {
                    throw e;
                }
            }

            return call.getArguments().stream()
                    .allMatch(expression -> expression.accept(this, context));
        }

        @Override
        public Boolean visitSpecialForm(SpecialForm specialForm, Void context)
        {
            return specialForm.getArguments().stream()
                    .allMatch(expression -> expression.accept(this, context));
        }

        @Override
        public Boolean visitLambda(LambdaDefinitionExpression lambda, Void context)
        {
            return lambda.getBody().accept(this, context);
        }

        @Override
        public Boolean visitVariableReference(VariableReferenceExpression reference, Void context)
        {
            return true;
        }
    }
}
