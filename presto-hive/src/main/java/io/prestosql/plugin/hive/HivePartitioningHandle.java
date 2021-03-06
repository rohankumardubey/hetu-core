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
import io.prestosql.plugin.hive.HiveBucketing.BucketingVersion;
import io.prestosql.spi.connector.ConnectorPartitioningHandle;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

public class HivePartitioningHandle
        implements ConnectorPartitioningHandle
{
    private final BucketingVersion bucketingVersion;
    private final int bucketCount;
    private final List<HiveType> hiveTypes;
    private final OptionalInt maxCompatibleBucketCount;
    private final boolean forUpdateOrDelete;

    public HivePartitioningHandle(
            BucketingVersion bucketingVersion,
            int bucketCount,
            List<HiveType> hiveTypes,
            OptionalInt maxCompatibleBucketCount)
    {
        this(bucketingVersion, bucketCount, hiveTypes, maxCompatibleBucketCount, false);
    }

    @JsonCreator
    public HivePartitioningHandle(
            @JsonProperty("bucketingVersion") BucketingVersion bucketingVersion,
            @JsonProperty("bucketCount") int bucketCount,
            @JsonProperty("hiveTypes") List<HiveType> hiveTypes,
            @JsonProperty("maxCompatibleBucketCount") OptionalInt maxCompatibleBucketCount,
            @JsonProperty("forUpdate") boolean forUpdateOrDelete)
    {
        this.bucketingVersion = requireNonNull(bucketingVersion, "bucketingVersion is null");
        this.bucketCount = bucketCount;
        this.hiveTypes = requireNonNull(hiveTypes, "hiveTypes is null");
        this.maxCompatibleBucketCount = maxCompatibleBucketCount;
        this.forUpdateOrDelete = forUpdateOrDelete;
    }

    @JsonProperty
    public BucketingVersion getBucketingVersion()
    {
        return bucketingVersion;
    }

    @JsonProperty
    public int getBucketCount()
    {
        return bucketCount;
    }

    @JsonProperty
    public List<HiveType> getHiveTypes()
    {
        return hiveTypes;
    }

    @JsonProperty
    public OptionalInt getMaxCompatibleBucketCount()
    {
        return maxCompatibleBucketCount;
    }

    @JsonProperty
    public boolean isForUpdateOrDelete()
    {
        return forUpdateOrDelete;
    }

    @Override
    public String toString()
    {
        return format("buckets=%s, hiveTypes=%s", bucketCount, hiveTypes);
    }

    @Override
    public boolean equals(Object o)
    {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        HivePartitioningHandle that = (HivePartitioningHandle) o;
        return bucketCount == that.bucketCount &&
                Objects.equals(hiveTypes, that.hiveTypes);
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(bucketCount, hiveTypes);
    }
}
