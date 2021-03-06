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
package io.prestosql.spi.function;

import io.prestosql.spi.block.Block;
import io.prestosql.spi.block.BlockBuilder;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.Restorable;
import io.prestosql.spi.type.Type;

public interface AccumulatorStateSerializer<T>
{
    Type getSerializedType();

    void serialize(T state, BlockBuilder out);

    /**
     * Deserialize {@code index}-th position in {@code block} into {@code state}.
     * <p>
     * This method may be invoked with a reused dirty {@code state}. Therefore,
     * implementations must not make assumptions about the initial value of
     * {@code state}.
     * <p>
     * Null positions in {@code block} are skipped and ignored. In other words,
     * {@code block.isNull(index)} is guaranteed to return false.
     */
    void deserialize(Block block, int index, T state);

    default Object serializeCapture(Object state, BlockEncodingSerdeProvider serdeProvider)
    {
        if (state instanceof Restorable) {
            return ((Restorable) state).capture(serdeProvider);
        }
        throw new UnsupportedOperationException();
    }

    default void deserializeRestore(Object snapshot, Object state, BlockEncodingSerdeProvider serdeProvider)
    {
        if (state instanceof Restorable) {
            ((Restorable) state).restore(snapshot, serdeProvider);
        }
        else {
            throw new UnsupportedOperationException();
        }
    }
}
