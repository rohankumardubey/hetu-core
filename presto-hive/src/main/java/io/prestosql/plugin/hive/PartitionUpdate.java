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
package io.prestosql.plugin.hive;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Multimaps;
import io.prestosql.spi.PrestoException;
import org.apache.hadoop.fs.Path;

import java.util.Collection;
import java.util.List;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class PartitionUpdate
{
    private final String name;
    private final UpdateMode updateMode;
    private final Path writePath;
    private final Path targetPath;
    private final List<String> fileNames;
    private final long rowCount;
    private final long inMemoryDataSizeInBytes;
    private final long onDiskDataSizeInBytes;
    private final List<String> miscData;

    @JsonCreator
    public PartitionUpdate(
            @JsonProperty("name") String name,
            @JsonProperty("updateMode") UpdateMode updateMode,
            @JsonProperty("writePath") String writePath,
            @JsonProperty("targetPath") String targetPath,
            @JsonProperty("fileNames") List<String> fileNames,
            @JsonProperty("rowCount") long rowCount,
            @JsonProperty("inMemoryDataSizeInBytes") long inMemoryDataSizeInBytes,
            @JsonProperty("onDiskDataSizeInBytes") long onDiskDataSizeInBytes,
            @JsonProperty("miscData") List<String> miscData)
    {
        this(
                name,
                updateMode,
                new Path(requireNonNull(writePath, "writePath is null")),
                new Path(requireNonNull(targetPath, "targetPath is null")),
                fileNames,
                rowCount,
                inMemoryDataSizeInBytes,
                onDiskDataSizeInBytes,
                miscData);
    }

    public PartitionUpdate(
            String name,
            UpdateMode updateMode,
            Path writePath,
            Path targetPath,
            List<String> fileNames,
            long rowCount,
            long inMemoryDataSizeInBytes,
            long onDiskDataSizeInBytes,
            List<String> miscData)
    {
        this.name = requireNonNull(name, "name is null");
        this.updateMode = requireNonNull(updateMode, "updateMode is null");
        this.writePath = requireNonNull(writePath, "writePath is null");
        this.targetPath = requireNonNull(targetPath, "targetPath is null");
        this.fileNames = ImmutableList.copyOf(requireNonNull(fileNames, "fileNames is null"));
        checkArgument(rowCount >= 0, "rowCount is negative: %s", rowCount);
        this.rowCount = rowCount;
        checkArgument(inMemoryDataSizeInBytes >= 0, "inMemoryDataSizeInBytes is negative: %s", inMemoryDataSizeInBytes);
        this.inMemoryDataSizeInBytes = inMemoryDataSizeInBytes;
        checkArgument(onDiskDataSizeInBytes >= 0, "onDiskDataSizeInBytes is negative: %s", onDiskDataSizeInBytes);
        this.onDiskDataSizeInBytes = onDiskDataSizeInBytes;
        this.miscData = requireNonNull(miscData, "miscData is null");
    }

    @JsonProperty
    public String getName()
    {
        return name;
    }

    @JsonProperty
    public UpdateMode getUpdateMode()
    {
        return updateMode;
    }

    public Path getWritePath()
    {
        return writePath;
    }

    public Path getTargetPath()
    {
        return targetPath;
    }

    @JsonProperty
    public List<String> getFileNames()
    {
        return fileNames;
    }

    @JsonProperty("targetPath")
    public String getJsonSerializableTargetPath()
    {
        return targetPath.toString();
    }

    @JsonProperty("writePath")
    public String getJsonSerializableWritePath()
    {
        return writePath.toString();
    }

    @JsonProperty
    public long getRowCount()
    {
        return rowCount;
    }

    @JsonProperty
    public long getInMemoryDataSizeInBytes()
    {
        return inMemoryDataSizeInBytes;
    }

    @JsonProperty
    public long getOnDiskDataSizeInBytes()
    {
        return onDiskDataSizeInBytes;
    }

    @JsonProperty
    public List<String> getMiscData()
    {
        return miscData;
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("name", name)
                .add("updateMode", updateMode)
                .add("writePath", writePath)
                .add("targetPath", targetPath)
                .add("fileNames", fileNames)
                .add("rowCount", rowCount)
                .add("inMemoryDataSizeInBytes", inMemoryDataSizeInBytes)
                .add("onDiskDataSizeInBytes", onDiskDataSizeInBytes)
                .add("miscData", miscData)
                .toString();
    }

    public HiveBasicStatistics getStatistics()
    {
        return new HiveBasicStatistics(fileNames.size(), rowCount, inMemoryDataSizeInBytes, onDiskDataSizeInBytes);
    }

    public static List<PartitionUpdate> mergePartitionUpdates(Iterable<PartitionUpdate> unMergedUpdates)
    {
        ImmutableList.Builder<PartitionUpdate> partitionUpdates = ImmutableList.builder();
        for (Collection<PartitionUpdate> partitionGroup : Multimaps.index(unMergedUpdates, PartitionUpdate::getName).asMap().values()) {
            PartitionUpdate firstPartition = partitionGroup.iterator().next();

            ImmutableList.Builder<String> allFileNames = ImmutableList.builder();
            ImmutableList.Builder<String> allMiscData = ImmutableList.builder();
            long totalRowCount = 0;
            long totalInMemoryDataSizeInBytes = 0;
            long totalOnDiskDataSizeInBytes = 0;
            for (PartitionUpdate partition : partitionGroup) {
                // verify partitions have the same new flag, write path and target path
                // this shouldn't happen but could if another user added a partition during the write
                if (!partition.getWritePath().equals(firstPartition.getWritePath()) ||
                        !partition.getTargetPath().equals(firstPartition.getTargetPath())) {
                    throw new PrestoException(HiveErrorCode.HIVE_CONCURRENT_MODIFICATION_DETECTED, format("Partition %s was added or modified during INSERT", firstPartition.getName()));
                }
                allFileNames.addAll(partition.getFileNames());
                allMiscData.addAll(partition.getMiscData());
                totalRowCount += partition.getRowCount();
                totalInMemoryDataSizeInBytes += partition.getInMemoryDataSizeInBytes();
                totalOnDiskDataSizeInBytes += partition.getOnDiskDataSizeInBytes();
            }

            partitionUpdates.add(new PartitionUpdate(firstPartition.getName(),
                    firstPartition.getUpdateMode(),
                    firstPartition.getWritePath(),
                    firstPartition.getTargetPath(),
                    allFileNames.build(),
                    totalRowCount,
                    totalInMemoryDataSizeInBytes,
                    totalOnDiskDataSizeInBytes,
                    allMiscData.build()));
        }
        return partitionUpdates.build();
    }

    public enum UpdateMode
    {
        NEW,
        APPEND,
        OVERWRITE,
    }
}
