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
package io.prestosql.tests;

import com.google.common.collect.ImmutableList;
import io.prestosql.metadata.BoundVariables;
import io.prestosql.metadata.FunctionAndTypeManager;
import io.prestosql.metadata.SqlScalarFunction;
import io.prestosql.spi.connector.QualifiedObjectName;
import io.prestosql.spi.function.BuiltInScalarFunctionImplementation;
import io.prestosql.spi.function.FunctionKind;
import io.prestosql.spi.function.Signature;

import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.spi.function.BuiltInScalarFunctionImplementation.ArgumentProperty.valueTypeArgumentProperty;
import static io.prestosql.spi.function.BuiltInScalarFunctionImplementation.NullConvention.RETURN_NULL_ON_NULL;
import static io.prestosql.spi.function.Signature.typeVariable;
import static io.prestosql.spi.type.TypeSignature.parseTypeSignature;
import static io.prestosql.spi.util.Reflection.constructorMethodHandle;
import static io.prestosql.spi.util.Reflection.methodHandle;
import static java.util.Collections.nCopies;

public class StatefulSleepingSum
        extends SqlScalarFunction
{
    public static final StatefulSleepingSum STATEFUL_SLEEPING_SUM = new StatefulSleepingSum();

    private StatefulSleepingSum()
    {
        super(new Signature(
                QualifiedObjectName.valueOfDefaultFunction("stateful_sleeping_sum"),
                FunctionKind.SCALAR,
                ImmutableList.of(typeVariable("bigint")),
                ImmutableList.of(),
                parseTypeSignature("bigint"),
                ImmutableList.of(parseTypeSignature("double"), parseTypeSignature("bigint"), parseTypeSignature("bigint"), parseTypeSignature("bigint")),
                false));
    }

    @Override
    public boolean isHidden()
    {
        return false;
    }

    @Override
    public boolean isDeterministic()
    {
        return true;
    }

    @Override
    public String getDescription()
    {
        return "testing not thread safe function";
    }

    @Override
    public BuiltInScalarFunctionImplementation specialize(BoundVariables boundVariables, int arity, FunctionAndTypeManager functionAndTypeManager)
    {
        int args = 4;
        return new BuiltInScalarFunctionImplementation(
                false,
                nCopies(args, valueTypeArgumentProperty(RETURN_NULL_ON_NULL)),
                methodHandle(StatefulSleepingSum.class, "statefulSleepingSum", State.class, double.class, long.class, long.class, long.class),
                Optional.of(constructorMethodHandle(State.class)));
    }

    public static long statefulSleepingSum(State state, double sleepProbability, long sleepDurationMillis, long a, long b)
    {
        int currentThreads = state.currentThreads.incrementAndGet();
        try {
            checkState(currentThreads == 1, "%s threads concurrently executing a stateful function", currentThreads);
            if (ThreadLocalRandom.current().nextDouble() < sleepProbability) {
                Thread.sleep(sleepDurationMillis);
            }
            return a + b;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted", e);
        }
        finally {
            state.currentThreads.decrementAndGet();
        }
    }

    public static class State
    {
        private final AtomicInteger currentThreads = new AtomicInteger();
    }
}
