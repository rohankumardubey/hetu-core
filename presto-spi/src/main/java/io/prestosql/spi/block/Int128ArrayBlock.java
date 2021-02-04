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
package io.prestosql.spi.block;

import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import io.prestosql.spi.util.BloomFilter;
import nova.hetu.omnicache.vector.LongVec;
import org.openjdk.jol.info.ClassLayout;

import javax.annotation.Nullable;

import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;

import static io.airlift.slice.SizeOf.sizeOf;
import static io.prestosql.spi.block.BlockUtil.checkArrayRange;
import static io.prestosql.spi.block.BlockUtil.checkValidRegion;
import static io.prestosql.spi.block.BlockUtil.compactArray;
import static io.prestosql.spi.block.BlockUtil.countUsedPositions;

public class Int128ArrayBlock
        implements Block<long[]>
{
    private static final int INSTANCE_SIZE = ClassLayout.parseClass(Int128ArrayBlock.class).instanceSize();
    public static final int INT128_BYTES = Long.BYTES + Long.BYTES;

    private final int positionOffset;
    private final int positionCount;
    @Nullable
    private final boolean[] valueIsNull;
    private final LongVec values;

    private final long sizeInBytes;
    private final long retainedSizeInBytes;

    public Int128ArrayBlock(int positionCount, Optional<boolean[]> valueIsNull, long[] values)
    {
        this(0, positionCount, valueIsNull.orElse(null), values);
    }

    public Int128ArrayBlock(int positionCount, Optional<boolean[]> valueIsNull, LongVec values)
    {
        this(0, positionCount, valueIsNull.orElse(null), values);
    }

    Int128ArrayBlock(int positionOffset, int positionCount, boolean[] valueIsNull, long[] values)
    {
        if (positionOffset < 0) {
            throw new IllegalArgumentException("positionOffset is negative");
        }
        this.positionOffset = positionOffset;
        if (positionCount < 0) {
            throw new IllegalArgumentException("positionCount is negative");
        }
        this.positionCount = positionCount;

        if (values.length - (positionOffset * 2) < positionCount * 2) {
            throw new IllegalArgumentException("values length is less than positionCount");
        }
        this.values = new LongVec(values.length);
        for (int i = 0; i < values.length; i++) {
            this.values.set(i, values[i]);
        }

        if (valueIsNull != null && valueIsNull.length - positionOffset < positionCount) {
            throw new IllegalArgumentException("isNull length is less than positionCount");
        }
        this.valueIsNull = valueIsNull;

        sizeInBytes = (INT128_BYTES + Byte.BYTES) * (long) positionCount;
        retainedSizeInBytes = INSTANCE_SIZE + sizeOf(valueIsNull) + this.values.capacity();
    }

    Int128ArrayBlock(int positionOffset, int positionCount, boolean[] valueIsNull, LongVec values)
    {
        if (positionOffset < 0) {
            throw new IllegalArgumentException("positionOffset is negative");
        }
        this.positionOffset = positionOffset;
        if (positionCount < 0) {
            throw new IllegalArgumentException("positionCount is negative");
        }
        this.positionCount = positionCount;

        if (values.size() - (positionOffset * 2) < positionCount * 2) {
            throw new IllegalArgumentException("values length is less than positionCount");
        }
        this.values = values;

        if (valueIsNull != null && valueIsNull.length - positionOffset < positionCount) {
            throw new IllegalArgumentException("isNull length is less than positionCount");
        }
        this.valueIsNull = valueIsNull;

        sizeInBytes = (INT128_BYTES + Byte.BYTES) * (long) positionCount;
        retainedSizeInBytes = INSTANCE_SIZE + sizeOf(valueIsNull) + this.values.capacity();
    }

    @Override
    public long getSizeInBytes()
    {
        return sizeInBytes;
    }

    @Override
    public long getRegionSizeInBytes(int position, int length)
    {
        return (INT128_BYTES + Byte.BYTES) * (long) length;
    }

    @Override
    public long getPositionsSizeInBytes(boolean[] positions)
    {
        return (INT128_BYTES + Byte.BYTES) * (long) countUsedPositions(positions);
    }

    @Override
    public long getRetainedSizeInBytes()
    {
        return retainedSizeInBytes;
    }

    @Override
    public long getEstimatedDataSizeForStats(int position)
    {
        return isNull(position) ? 0 : INT128_BYTES;
    }

    @Override
    public void retainedBytesForEachPart(BiConsumer<Object, Long> consumer)
    {
        // TODO: try to avoid copy here
        long[] valuesArray = new long[values.size()];
        for (int i = 0; i < values.size(); i++) {
            valuesArray[i] = values.get(i);
        }
        consumer.accept(valuesArray, sizeOf(valuesArray));
        if (valueIsNull != null) {
            consumer.accept(valueIsNull, sizeOf(valueIsNull));
        }
        consumer.accept(this, (long) INSTANCE_SIZE);
    }

    @Override
    public int getPositionCount()
    {
        return positionCount;
    }

    @Override
    public long getLong(int position, int offset)
    {
        checkReadablePosition(position);
        if (offset == 0) {
            return values.get((position + positionOffset) * 2);
        }
        if (offset == 8) {
            return values.get(((position + positionOffset) * 2) + 1);
        }
        throw new IllegalArgumentException("offset must be 0 or 8");
    }

    @Override
    public boolean mayHaveNull()
    {
        return valueIsNull != null;
    }

    @Override
    public boolean isNull(int position)
    {
        checkReadablePosition(position);
        return valueIsNull != null && valueIsNull[position + positionOffset];
    }

    @Override
    public void writePositionTo(int position, BlockBuilder blockBuilder)
    {
        checkReadablePosition(position);
        blockBuilder.writeLong(values.get((position + positionOffset) * 2));
        blockBuilder.writeLong(values.get(((position + positionOffset) * 2) + 1));
        blockBuilder.closeEntry();
    }

    @Override
    public Block getSingleValueBlock(int position)
    {
        checkReadablePosition(position);
        return new Int128ArrayBlock(
                0,
                1,
                isNull(position) ? new boolean[] {true} : null,
                new long[] {
                        values.get((position + positionOffset) * 2),
                        values.get(((position + positionOffset) * 2) + 1)});
    }

    @Override
    public Block copyPositions(int[] positions, int offset, int length)
    {
        checkArrayRange(positions, offset, length);

        boolean[] newValueIsNull = null;
        if (valueIsNull != null) {
            newValueIsNull = new boolean[length];
        }
        long[] newValues = new long[length * 2];
        for (int i = 0; i < length; i++) {
            int position = positions[offset + i];
            checkReadablePosition(position);
            if (valueIsNull != null) {
                newValueIsNull[i] = valueIsNull[position + positionOffset];
            }
            newValues[i * 2] = values.get((position + positionOffset) * 2);
            newValues[(i * 2) + 1] = values.get(((position + positionOffset) * 2) + 1);
        }
        return new Int128ArrayBlock(0, length, newValueIsNull, newValues);
    }

    @Override
    public Block getRegion(int positionOffset, int length)
    {
        checkValidRegion(getPositionCount(), positionOffset, length);

        return new Int128ArrayBlock(positionOffset + this.positionOffset, length, valueIsNull, values);
    }

    @Override
    public Block copyRegion(int positionOffset, int length)
    {
        checkValidRegion(getPositionCount(), positionOffset, length);
        positionOffset += this.positionOffset;

        LongVec newValues = new LongVec(length * 2);
        for (int i = 0; i < length * 2; i++) {
            newValues.set(i, this.values.get(positionOffset * 2 + i));
        }
        boolean[] newValueIsNull = valueIsNull == null ? null : compactArray(valueIsNull, positionOffset, length);

        if (newValueIsNull == valueIsNull && newValues == values) {
            return this;
        }
        return new Int128ArrayBlock(0, length, newValueIsNull, newValues);
    }

    @Override
    public String getEncodingName()
    {
        return Int128ArrayBlockEncoding.NAME;
    }

    @Override
    public String toString()
    {
        StringBuilder sb = new StringBuilder("Int128ArrayBlock{");
        sb.append("positionCount=").append(getPositionCount());
        sb.append('}');
        return sb.toString();
    }

    private void checkReadablePosition(int position)
    {
        if (position < 0 || position >= getPositionCount()) {
            throw new IllegalArgumentException("position is not valid");
        }
    }

    @Override
    public boolean[] filter(BloomFilter filter, boolean[] validPositions)
    {
        for (int i = 0; i < values.size() / 2; i++) {
            Slice value = Slices.wrappedLongArray(values.get(i * 2), values.get(i * 2 + 1));
            validPositions[i] = validPositions[i] && filter.test(value);
        }
        return validPositions;
    }

    @Override
    public int filter(int[] positions, int positionCount, int[] matchedPositions, Function<Object, Boolean> test)
    {
        int matchCount = 0;
        long[] val = new long[2];
        for (int i = 0; i < positionCount; i++) {
            if (valueIsNull != null && valueIsNull[positions[i] + positionOffset]) {
                if (test.apply(null)) {
                    matchedPositions[matchCount++] = positions[i];
                }
            }
            else {
                val[0] = values.get((positions[i] + positionOffset) * 2);
                val[1] = values.get(((positions[i] + positionOffset) * 2) + 1);
                if (test.apply(val)) {
                    matchedPositions[matchCount++] = positions[i];
                }
            }
        }

        return matchCount;
    }

    @Override
    public long[] get(int position)
    {
        long[] val = new long[2];
        if (valueIsNull != null && valueIsNull[position + positionOffset]) {
            return null;
        }
        val[0] = values.get((position + positionOffset) * 2);
        val[1] = values.get(((position + positionOffset) * 2) + 1);

        return val;
    }
}
