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
package io.prestosql.plugin.resourcegroups;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.prestosql.spi.resourcegroups.KillPolicy;
import io.prestosql.spi.resourcegroups.SchedulingPolicy;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.MoreObjects.toStringHelper;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public class ResourceGroupSpec
{
    private static final Pattern PERCENT_PATTERN = Pattern.compile("(\\d{1,3}(:?\\.\\d+)?)%");

    private final ResourceGroupNameTemplate name;
    private final Optional<DataSize> softMemoryLimit;
    private final Optional<Double> softMemoryLimitFraction;
    private final Optional<DataSize> softReservedMemory;
    private final Optional<Double> softReservedFraction;
    private final int maxQueued;
    private final Optional<Integer> softConcurrencyLimit;
    private final Optional<Integer> hardReservedConcurrency;
    private final int hardConcurrencyLimit;
    private final Optional<SchedulingPolicy> schedulingPolicy;
    private final Optional<Integer> schedulingWeight;
    private final List<ResourceGroupSpec> subGroups;
    private final Optional<Boolean> jmxExport;
    private final Optional<Duration> softCpuLimit;
    private final Optional<Duration> hardCpuLimit;

    private final Optional<KillPolicy> killPolicy;

    // Hetu: add new parameters: softReservedMemory and hardReservedConcurrency
    @JsonCreator
    public ResourceGroupSpec(
            @JsonProperty("name") ResourceGroupNameTemplate name,
            @JsonProperty("softMemoryLimit") String softMemoryLimit,
            @JsonProperty("softReservedMemory") Optional<String> softReservedMemory,
            @JsonProperty("maxQueued") int maxQueued,
            @JsonProperty("softConcurrencyLimit") Optional<Integer> softConcurrencyLimit,
            @JsonProperty("hardConcurrencyLimit") Optional<Integer> hardConcurrencyLimit,
            @JsonProperty("hardReservedConcurrency") Optional<Integer> hardReservedConcurrency,
            @JsonProperty("maxRunning") Optional<Integer> maxRunning,
            @JsonProperty("schedulingPolicy") Optional<String> schedulingPolicy,
            @JsonProperty("schedulingWeight") Optional<Integer> schedulingWeight,
            @JsonProperty("subGroups") Optional<List<ResourceGroupSpec>> subGroups,
            @JsonProperty("jmxExport") Optional<Boolean> jmxExport,
            @JsonProperty("softCpuLimit") Optional<Duration> softCpuLimit,
            @JsonProperty("hardCpuLimit") Optional<Duration> hardCpuLimit,
            @JsonProperty("killPolicy") Optional<String> killPolicy)
    {
        this.softCpuLimit = requireNonNull(softCpuLimit, "softCpuLimit is null");
        this.hardCpuLimit = requireNonNull(hardCpuLimit, "hardCpuLimit is null");
        this.jmxExport = requireNonNull(jmxExport, "jmxExport is null");
        this.name = requireNonNull(name, "name is null");
        checkArgument(maxQueued >= 0, "maxQueued is negative");
        this.maxQueued = maxQueued;
        this.softConcurrencyLimit = softConcurrencyLimit;
        this.hardReservedConcurrency = hardReservedConcurrency;

        checkArgument(hardConcurrencyLimit.isPresent() || maxRunning.isPresent(), "Missing required property: hardConcurrencyLimit");
        this.hardConcurrencyLimit = hardConcurrencyLimit.orElseGet(maxRunning::get);
        checkArgument(this.hardConcurrencyLimit >= 0, "hardConcurrencyLimit is negative");

        softConcurrencyLimit.ifPresent(soft -> checkArgument(soft >= 0, "softConcurrencyLimit is negative"));
        softConcurrencyLimit.ifPresent(soft -> checkArgument(this.hardConcurrencyLimit >= soft, "hardConcurrencyLimit must be greater than or equal to softConcurrencyLimit"));
        // Hetu: validate hardReservedConcurrency
        hardReservedConcurrency.ifPresent(hardReserve -> checkArgument(hardReserve >= 0, "hardReservedConcurrency is negative"));
        hardReservedConcurrency.ifPresent(hardReserve -> checkArgument(this.hardConcurrencyLimit >= hardReserve, "hardConcurrencyLimit must be greater than or equal to hardReservedConcurrency"));
        this.schedulingPolicy = requireNonNull(schedulingPolicy, "schedulingPolicy is null").map(value -> SchedulingPolicy.valueOf(value.toUpperCase()));
        this.schedulingWeight = requireNonNull(schedulingWeight, "schedulingWeight is null");

        requireNonNull(softMemoryLimit, "softMemoryLimit is null");
        Optional<DataSize> absoluteSize;
        Optional<Double> fraction;
        Matcher matcher = PERCENT_PATTERN.matcher(softMemoryLimit);
        if (matcher.matches()) {
            absoluteSize = Optional.empty();
            fraction = Optional.of(Double.parseDouble(matcher.group(1)) / 100.0);
        }
        else {
            absoluteSize = Optional.of(DataSize.valueOf(softMemoryLimit));
            fraction = Optional.empty();
        }
        this.softMemoryLimit = absoluteSize;
        this.softMemoryLimitFraction = fraction;

        // Hetu: set value for softReservedMemory and softReservedFraction
        if (softReservedMemory.isPresent()) {
            matcher = PERCENT_PATTERN.matcher(softReservedMemory.get());
            if (matcher.matches()) {
                absoluteSize = Optional.empty();
                fraction = Optional.of(Double.parseDouble(matcher.group(1)) / 100.0);
            }
            else {
                absoluteSize = Optional.of(DataSize.valueOf(softReservedMemory.get()));
                fraction = Optional.empty();
            }
        }
        else {
            fraction = Optional.empty();
            absoluteSize = Optional.empty();
        }
        this.softReservedMemory = absoluteSize;
        this.softReservedFraction = fraction;

        this.subGroups = ImmutableList.copyOf(requireNonNull(subGroups, "subGroups is null").orElse(ImmutableList.of()));
        Set<ResourceGroupNameTemplate> names = new HashSet<>();
        for (ResourceGroupSpec subGroup : this.subGroups) {
            checkArgument(!names.contains(subGroup.getName()), "Duplicated sub group: %s", subGroup.getName());
            names.add(subGroup.getName());
        }

        this.killPolicy = requireNonNull(killPolicy, "killPolicy is null").map(value -> KillPolicy.valueOf(value.toUpperCase()));
    }

    public Optional<DataSize> getSoftMemoryLimit()
    {
        return softMemoryLimit;
    }

    public Optional<Double> getSoftMemoryLimitFraction()
    {
        return softMemoryLimitFraction;
    }

    public Optional<DataSize> getSoftReservedMemory()
    {
        return softReservedMemory;
    }

    public Optional<Double> getSoftReservedFraction()
    {
        return softReservedFraction;
    }

    public int getMaxQueued()
    {
        return maxQueued;
    }

    public Optional<Integer> getSoftConcurrencyLimit()
    {
        return softConcurrencyLimit;
    }

    public Optional<Integer> getHardReservedConcurrency()
    {
        return hardReservedConcurrency;
    }

    public int getHardConcurrencyLimit()
    {
        return hardConcurrencyLimit;
    }

    public Optional<SchedulingPolicy> getSchedulingPolicy()
    {
        return schedulingPolicy;
    }

    public Optional<Integer> getSchedulingWeight()
    {
        return schedulingWeight;
    }

    public ResourceGroupNameTemplate getName()
    {
        return name;
    }

    public List<ResourceGroupSpec> getSubGroups()
    {
        return subGroups;
    }

    public Optional<Boolean> getJmxExport()
    {
        return jmxExport;
    }

    public Optional<Duration> getSoftCpuLimit()
    {
        return softCpuLimit;
    }

    public Optional<Duration> getHardCpuLimit()
    {
        return hardCpuLimit;
    }

    public Optional<KillPolicy> getKillPolicy()
    {
        return killPolicy;
    }

    @Override
    public boolean equals(Object other)
    {
        if (other == this) {
            return true;
        }
        if (!(other instanceof ResourceGroupSpec)) {
            return false;
        }
        ResourceGroupSpec that = (ResourceGroupSpec) other;
        return (name.equals(that.name) &&
                softMemoryLimit.equals(that.softMemoryLimit) &&
                softReservedMemory.equals(that.softReservedMemory) && // Hetu: new parameter softReservedMemory
                maxQueued == that.maxQueued &&
                softConcurrencyLimit.equals(that.softConcurrencyLimit) &&
                hardConcurrencyLimit == that.hardConcurrencyLimit &&
                hardReservedConcurrency.equals(that.hardReservedConcurrency) && // Hetu: new parameter hardReservedConcurrency
                schedulingPolicy.equals(that.schedulingPolicy) &&
                schedulingWeight.equals(that.schedulingWeight) &&
                subGroups.equals(that.subGroups) &&
                jmxExport.equals(that.jmxExport) &&
                softCpuLimit.equals(that.softCpuLimit) &&
                hardCpuLimit.equals(that.hardCpuLimit) &&
                killPolicy.equals(that.killPolicy));
    }

    // Subgroups not included, used to determine whether a group needs to be reconfigured
    public boolean sameConfig(ResourceGroupSpec other)
    {
        if (other == null) {
            return false;
        }
        return (name.equals(other.name) &&
                softMemoryLimit.equals(other.softMemoryLimit) &&
                maxQueued == other.maxQueued &&
                softConcurrencyLimit.equals(other.softConcurrencyLimit) &&
                hardConcurrencyLimit == other.hardConcurrencyLimit &&
                hardReservedConcurrency == other.hardReservedConcurrency && // Hetu: new parameter hardReservedConcurrency
                softReservedMemory == other.softReservedMemory && // Hetu: new parameter softReservedMemory
                schedulingPolicy.equals(other.schedulingPolicy) &&
                schedulingWeight.equals(other.schedulingWeight) &&
                jmxExport.equals(other.jmxExport) &&
                softCpuLimit.equals(other.softCpuLimit) &&
                hardCpuLimit.equals(other.hardCpuLimit) &&
                killPolicy.equals(other.killPolicy));
    }

    @Override
    public int hashCode()
    {
        return Objects.hash(
                name,
                softMemoryLimit,
                softReservedMemory, // Hetu: add parameter softReservedMemory
                maxQueued,
                softConcurrencyLimit,
                hardConcurrencyLimit,
                hardReservedConcurrency, // Hetu: add parameter hardReservedConcurrency
                schedulingPolicy,
                schedulingWeight,
                subGroups,
                jmxExport,
                softCpuLimit,
                hardCpuLimit,
                killPolicy);
    }

    @Override
    public String toString()
    {
        return toStringHelper(this)
                .add("name", name)
                .add("softMemoryLimit", softMemoryLimit)
                .add("softReservedMemory", softReservedMemory) // Hetu: add parameter softReservedMemory
                .add("maxQueued", maxQueued)
                .add("softConcurrencyLimit", softConcurrencyLimit)
                .add("hardConcurrencyLimit", hardConcurrencyLimit)
                .add("hardReservedConcurrency", hardReservedConcurrency) // Hetu: add parameter hardReservedConcurrency
                .add("schedulingPolicy", schedulingPolicy)
                .add("schedulingWeight", schedulingWeight)
                .add("jmxExport", jmxExport)
                .add("softCpuLimit", softCpuLimit)
                .add("hardCpuLimit", hardCpuLimit)
                .add("killPolicy", killPolicy)
                .toString();
    }
}
