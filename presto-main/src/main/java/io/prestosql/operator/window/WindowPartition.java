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
package io.prestosql.operator.window;

import com.google.common.collect.ImmutableList;
import io.prestosql.operator.PagesHashStrategy;
import io.prestosql.operator.PagesIndex;
import io.prestosql.spi.PageBuilder;
import io.prestosql.spi.function.WindowIndex;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.Restorable;
import io.prestosql.spi.snapshot.RestorableConfig;
import io.prestosql.spi.sql.expression.Types.FrameBoundType;

import java.io.Serializable;
import java.util.List;

import static com.google.common.base.Preconditions.checkState;
import static io.prestosql.spi.StandardErrorCode.INVALID_WINDOW_FRAME;
import static io.prestosql.spi.sql.expression.Types.FrameBoundType.FOLLOWING;
import static io.prestosql.spi.sql.expression.Types.FrameBoundType.PRECEDING;
import static io.prestosql.spi.sql.expression.Types.FrameBoundType.UNBOUNDED_FOLLOWING;
import static io.prestosql.spi.sql.expression.Types.FrameBoundType.UNBOUNDED_PRECEDING;
import static io.prestosql.spi.sql.expression.Types.WindowFrameType.RANGE;
import static io.prestosql.util.Failures.checkCondition;
import static java.lang.Math.toIntExact;

// Many of these fields are captured by WindowOperator
@RestorableConfig(uncapturedFields = {"peerGroupHashStrategy", "outputChannels", "pagesIndex", "windowFunctions"})
public final class WindowPartition
        implements Restorable
{
    private final PagesIndex pagesIndex;
    private final int partitionStart;
    private final int partitionEnd;

    private final int[] outputChannels;
    private final List<FramedWindowFunction> windowFunctions;
    private final PagesHashStrategy peerGroupHashStrategy;

    private int peerGroupStart;
    private int peerGroupEnd;

    private int currentPosition;

    public WindowPartition(PagesIndex pagesIndex,
            int partitionStart,
            int partitionEnd,
            int[] outputChannels,
            List<FramedWindowFunction> windowFunctions,
            PagesHashStrategy peerGroupHashStrategy)
    {
        this.pagesIndex = pagesIndex;
        this.partitionStart = partitionStart;
        this.partitionEnd = partitionEnd;
        this.outputChannels = outputChannels;
        this.windowFunctions = ImmutableList.copyOf(windowFunctions);
        this.peerGroupHashStrategy = peerGroupHashStrategy;

        // reset functions for new partition
        WindowIndex windowIndex = new PagesWindowIndex(pagesIndex, partitionStart, partitionEnd);
        for (FramedWindowFunction framedWindowFunction : windowFunctions) {
            framedWindowFunction.getFunction().reset(windowIndex);
        }

        currentPosition = partitionStart;
        updatePeerGroup();
    }

    public int getPartitionStart()
    {
        return partitionStart;
    }

    public int getPartitionEnd()
    {
        return partitionEnd;
    }

    public boolean hasNext()
    {
        return currentPosition < partitionEnd;
    }

    public void processNextRow(PageBuilder pageBuilder)
    {
        checkState(hasNext(), "No more rows in partition");

        // copy output channels
        pageBuilder.declarePosition();
        int channel = 0;
        while (channel < outputChannels.length) {
            pagesIndex.appendTo(outputChannels[channel], currentPosition, pageBuilder.getBlockBuilder(channel));
            channel++;
        }

        // check for new peer group
        if (currentPosition == peerGroupEnd) {
            updatePeerGroup();
        }

        for (FramedWindowFunction framedFunction : windowFunctions) {
            Range range = getFrameRange(framedFunction.getFrame());
            framedFunction.getFunction().processRow(
                    pageBuilder.getBlockBuilder(channel),
                    peerGroupStart - partitionStart,
                    peerGroupEnd - partitionStart - 1,
                    range.getStart(),
                    range.getEnd());
            channel++;
        }

        currentPosition++;
    }

    private static class Range
    {
        private final int start;
        private final int end;

        Range(int start, int end)
        {
            this.start = start;
            this.end = end;
        }

        public int getStart()
        {
            return start;
        }

        public int getEnd()
        {
            return end;
        }
    }

    private void updatePeerGroup()
    {
        peerGroupStart = currentPosition;
        // find end of peer group
        peerGroupEnd = peerGroupStart + 1;
        while ((peerGroupEnd < partitionEnd) && pagesIndex.positionEqualsPosition(peerGroupHashStrategy, peerGroupStart, peerGroupEnd)) {
            peerGroupEnd++;
        }
    }

    private Range getFrameRange(FrameInfo frameInfo)
    {
        int rowPosition = currentPosition - partitionStart;
        int endPosition = partitionEnd - partitionStart - 1;

        // handle empty frame
        if (emptyFrame(frameInfo, rowPosition, endPosition)) {
            return new Range(-1, -1);
        }

        int frameStart;
        int frameEnd;

        // frame start
        if (frameInfo.getStartType() == UNBOUNDED_PRECEDING) {
            frameStart = 0;
        }
        else if (frameInfo.getStartType() == PRECEDING) {
            frameStart = preceding(rowPosition, getStartValue(frameInfo));
        }
        else if (frameInfo.getStartType() == FOLLOWING) {
            frameStart = following(rowPosition, endPosition, getStartValue(frameInfo));
        }
        else if (frameInfo.getType() == RANGE) {
            frameStart = peerGroupStart - partitionStart;
        }
        else {
            frameStart = rowPosition;
        }

        // frame end
        if (frameInfo.getEndType() == UNBOUNDED_FOLLOWING) {
            frameEnd = endPosition;
        }
        else if (frameInfo.getEndType() == PRECEDING) {
            frameEnd = preceding(rowPosition, getEndValue(frameInfo));
        }
        else if (frameInfo.getEndType() == FOLLOWING) {
            frameEnd = following(rowPosition, endPosition, getEndValue(frameInfo));
        }
        else if (frameInfo.getType() == RANGE) {
            frameEnd = peerGroupEnd - partitionStart - 1;
        }
        else {
            frameEnd = rowPosition;
        }

        return new Range(frameStart, frameEnd);
    }

    private boolean emptyFrame(FrameInfo frameInfo, int rowPosition, int endPosition)
    {
        FrameBoundType startType = frameInfo.getStartType();
        FrameBoundType endType = frameInfo.getEndType();

        int positions = endPosition - rowPosition;

        if ((startType == UNBOUNDED_PRECEDING) && (endType == PRECEDING)) {
            return getEndValue(frameInfo) > rowPosition;
        }

        if ((startType == FOLLOWING) && (endType == UNBOUNDED_FOLLOWING)) {
            return getStartValue(frameInfo) > positions;
        }

        if (startType != endType) {
            return false;
        }

        FrameBoundType type = frameInfo.getStartType();
        if ((type != PRECEDING) && (type != FOLLOWING)) {
            return false;
        }

        long start = getStartValue(frameInfo);
        long end = getEndValue(frameInfo);

        if (type == PRECEDING) {
            return (start < end) || ((start > rowPosition) && (end > rowPosition));
        }

        return (start > end) || (start > positions);
    }

    private static int preceding(int rowPosition, long value)
    {
        if (value > rowPosition) {
            return 0;
        }
        return toIntExact(rowPosition - value);
    }

    private static int following(int rowPosition, int endPosition, long value)
    {
        if (value > (endPosition - rowPosition)) {
            return endPosition;
        }
        return toIntExact(rowPosition + value);
    }

    private long getStartValue(FrameInfo frameInfo)
    {
        return getFrameValue(frameInfo.getStartChannel(), "starting");
    }

    private long getEndValue(FrameInfo frameInfo)
    {
        return getFrameValue(frameInfo.getEndChannel(), "ending");
    }

    private long getFrameValue(int channel, String type)
    {
        checkCondition(!pagesIndex.isNull(channel, currentPosition), INVALID_WINDOW_FRAME, "Window frame %s offset must not be null", type);
        long value = pagesIndex.getLong(channel, currentPosition);
        checkCondition(value >= 0, INVALID_WINDOW_FRAME, "Window frame %s offset must not be negative", value);
        return value;
    }

    @Override
    public Object capture(BlockEncodingSerdeProvider serdeProvider)
    {
        // Although partitionStart/End fields are final, they need to be captured,
        // to be used to construct new WindowPartition objects during restore
        WindowPartitionState myState = new WindowPartitionState();
        myState.partitionStart = partitionStart;
        myState.partitionEnd = partitionEnd;
        myState.peerGroupStart = peerGroupStart;
        myState.peerGroupEnd = peerGroupEnd;
        myState.currentPosition = currentPosition;
        return myState;
    }

    public static WindowPartition restoreWindowPartition(PagesIndex pagesIndex,
            int[] outputChannels,
            List<FramedWindowFunction> windowFunctions,
            PagesHashStrategy peerGroupHashStrategy,
            Object state)
    {
        WindowPartitionState myState = (WindowPartitionState) state;
        WindowPartition windowPartition = new WindowPartition(pagesIndex, myState.partitionStart, myState.partitionEnd, outputChannels, windowFunctions, peerGroupHashStrategy);
        windowPartition.peerGroupStart = myState.peerGroupStart;
        windowPartition.peerGroupEnd = myState.peerGroupEnd;
        windowPartition.currentPosition = myState.currentPosition;
        return windowPartition;
    }

    private static class WindowPartitionState
            implements Serializable
    {
        private int partitionStart;
        private int partitionEnd;
        private int peerGroupStart;
        private int peerGroupEnd;
        private int currentPosition;
    }
}
