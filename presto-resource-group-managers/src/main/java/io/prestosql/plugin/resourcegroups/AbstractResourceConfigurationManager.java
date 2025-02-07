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

import com.google.common.collect.ImmutableList;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.prestosql.spi.PrestoException;
import io.prestosql.spi.memory.ClusterMemoryPoolManager;
import io.prestosql.spi.memory.MemoryPoolId;
import io.prestosql.spi.resourcegroups.KillPolicy;
import io.prestosql.spi.resourcegroups.QueryType;
import io.prestosql.spi.resourcegroups.ResourceGroup;
import io.prestosql.spi.resourcegroups.ResourceGroupConfigurationManager;
import io.prestosql.spi.resourcegroups.ResourceGroupId;
import io.prestosql.spi.resourcegroups.SelectionContext;

import javax.annotation.concurrent.GuardedBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static io.airlift.units.DataSize.Unit.BYTE;
import static io.prestosql.spi.StandardErrorCode.INVALID_RESOURCE_GROUP;
import static java.lang.String.format;
import static java.util.function.Predicate.isEqual;

public abstract class AbstractResourceConfigurationManager
        implements ResourceGroupConfigurationManager<ResourceGroupIdTemplate>
{
    @GuardedBy("generalPoolMemoryFraction")
    private final Map<ResourceGroup, Double> generalPoolMemoryFraction = new HashMap<>();
    @GuardedBy("generalPoolMemoryFraction")
    private long generalPoolBytes;

    protected abstract Optional<Duration> getCpuQuotaPeriod();

    protected abstract List<ResourceGroupSpec> getRootGroups();

    protected void validateRootGroups(ManagerSpec managerSpec)
    {
        Queue<ResourceGroupSpec> groups = new LinkedList<>(managerSpec.getRootGroups());
        while (!groups.isEmpty()) {
            ResourceGroupSpec group = groups.poll();
            List<ResourceGroupSpec> subGroups = group.getSubGroups();
            groups.addAll(subGroups);
            if (group.getSoftCpuLimit().isPresent() || group.getHardCpuLimit().isPresent()) {
                checkArgument(managerSpec.getCpuQuotaPeriod().isPresent(), "cpuQuotaPeriod must be specified to use cpu limits on group: %s", group.getName());
            }
            if (group.getSoftCpuLimit().isPresent()) {
                checkArgument(group.getHardCpuLimit().isPresent(), "Must specify hard CPU limit in addition to soft limit");
                checkArgument(group.getSoftCpuLimit().get().compareTo(group.getHardCpuLimit().get()) <= 0, "Soft CPU limit cannot be greater than hard CPU limit");
            }
            if (group.getSchedulingPolicy().isPresent()) {
                switch (group.getSchedulingPolicy().get()) {
                    case WEIGHTED:
                    case WEIGHTED_FAIR:
                        checkArgument(
                                subGroups.stream().allMatch(t -> t.getSchedulingWeight().isPresent()) || subGroups.stream().noneMatch(t -> t.getSchedulingWeight().isPresent()),
                                format("Must specify scheduling weight for all sub-groups of '%s' or none of them", group.getName()));
                        break;
                    case QUERY_PRIORITY:
                    case FAIR:
                        for (ResourceGroupSpec subGroup : subGroups) {
                            checkArgument(!subGroup.getSchedulingWeight().isPresent(), "Must use 'weighted' or 'weighted_fair' scheduling policy if specifying scheduling weight for '%s'", group.getName());
                        }
                        break;
                    default:
                        throw new UnsupportedOperationException();
                }
            }
        }
    }

    protected List<ResourceGroupSelector> buildSelectors(ManagerSpec managerSpec)
    {
        ImmutableList.Builder<ResourceGroupSelector> selectors = ImmutableList.builder();
        for (SelectorSpec spec : managerSpec.getSelectors()) {
            validateSelectors(managerSpec.getRootGroups(), spec);
            selectors.add(new StaticSelector(
                    spec.getUserRegex(),
                    spec.getSourceRegex(),
                    spec.getClientTags(),
                    spec.getResourceEstimate(),
                    spec.getQueryType(),
                    spec.getGroup()));
        }
        return selectors.build();
    }

    private void validateSelectors(List<ResourceGroupSpec> groups, SelectorSpec spec)
    {
        spec.getQueryType().ifPresent(this::validateQueryType);
        StringBuilder fullyQualifiedGroupName = new StringBuilder();
        for (ResourceGroupNameTemplate groupName : spec.getGroup().getSegments()) {
            fullyQualifiedGroupName.append(groupName);
            Optional<ResourceGroupSpec> match = groups
                    .stream()
                    .filter(groupSpec -> groupSpec.getName().equals(groupName))
                    .findFirst();
            if (!match.isPresent()) {
                throw new IllegalArgumentException(format("Selector refers to nonexistent group: %s", fullyQualifiedGroupName.toString()));
            }
            fullyQualifiedGroupName.append(".");
            groups = match.get().getSubGroups();
        }
    }

    private void validateQueryType(String queryType)
    {
        try {
            QueryType.valueOf(queryType.toUpperCase());
        }
        catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(format("Selector specifies an invalid query type: %s", queryType));
        }
    }

    protected AbstractResourceConfigurationManager(ClusterMemoryPoolManager memoryPoolManager)
    {
        memoryPoolManager.addChangeListener(new MemoryPoolId("general"), poolInfo -> {
            Map<ResourceGroup, DataSize> memoryLimits = new HashMap<>();
            synchronized (generalPoolMemoryFraction) {
                for (Map.Entry<ResourceGroup, Double> entry : generalPoolMemoryFraction.entrySet()) {
                    double bytes = poolInfo.getMaxBytes() * entry.getValue();
                    // setSoftMemoryLimit() acquires a lock on the root group of its tree, which could cause a deadlock if done while holding the "generalPoolMemoryFraction" lock
                    memoryLimits.put(entry.getKey(), new DataSize(bytes, BYTE));
                }
                generalPoolBytes = poolInfo.getMaxBytes();
            }
            for (Map.Entry<ResourceGroup, DataSize> entry : memoryLimits.entrySet()) {
                entry.getKey().setSoftMemoryLimit(entry.getValue());
            }
        });
    }

    @Override
    public SelectionContext<ResourceGroupIdTemplate> parentGroupContext(SelectionContext<ResourceGroupIdTemplate> context)
    {
        ResourceGroupId parentGroupId = context.getResourceGroupId().getParent().orElseThrow(() -> new IllegalArgumentException("Group has no parent group: " + context.getResourceGroupId()));
        List<ResourceGroupNameTemplate> parentGroupIdTemplate = new ArrayList<>(context.getContext().getSegments());
        parentGroupIdTemplate.remove(parentGroupIdTemplate.size() - 1);
        return new SelectionContext<>(parentGroupId, ResourceGroupIdTemplate.fromSegments(parentGroupIdTemplate));
    }

    protected ResourceGroupSpec getMatchingSpec(ResourceGroup group, SelectionContext<ResourceGroupIdTemplate> context)
    {
        List<ResourceGroupSpec> candidates = getRootGroups();
        ResourceGroupIdTemplate groupIdTemplate = context.getContext();
        ResourceGroupSpec match = null;

        for (ResourceGroupNameTemplate segment : groupIdTemplate.getSegments()) {
            match = null;
            for (ResourceGroupSpec candidate : candidates) {
                if (candidate.getName().equals(segment)) {
                    if (match != null) {
                        throw new PrestoException(INVALID_RESOURCE_GROUP, format(
                                "Ambiguous configuration for [%s] using [%s]. Matches [%s] and [%s]",
                                group.getId(),
                                groupIdTemplate,
                                match.getName(),
                                candidate.getName()));
                    }
                    match = candidate;
                }
            }

            checkState(match != null, "No matching configuration found for [%s] using [%s]", group.getId(), groupIdTemplate);
            candidates = match.getSubGroups();
        }

        verify(match != null, "match is null");
        return match;
    }

    protected void configureGroup(ResourceGroup group, ResourceGroupSpec match)
    {
        if (match.getSoftMemoryLimit().isPresent()) {
            group.setSoftMemoryLimit(match.getSoftMemoryLimit().get());
        }
        else {
            synchronized (generalPoolMemoryFraction) {
                double fraction = match.getSoftMemoryLimitFraction().get();
                generalPoolMemoryFraction.put(group, fraction);
                group.setSoftMemoryLimit(new DataSize(generalPoolBytes * fraction, BYTE));
            }
        }
        // Hetu: set SoftReservedMemory
        if (match.getSoftReservedMemory().isPresent()) {
            group.setSoftReservedMemory(match.getSoftReservedMemory().get());
        }
        else if (match.getSoftReservedFraction().isPresent()) {
            synchronized (generalPoolMemoryFraction) {
                double fraction = match.getSoftReservedFraction().get();
                generalPoolMemoryFraction.put(group, fraction);
                group.setSoftReservedMemory(new DataSize(generalPoolBytes * fraction, BYTE));
            }
        }
        group.setMaxQueuedQueries(match.getMaxQueued());
        group.setSoftConcurrencyLimit(match.getSoftConcurrencyLimit().orElse(match.getHardConcurrencyLimit()));
        group.setHardConcurrencyLimit(match.getHardConcurrencyLimit());
        match.getHardReservedConcurrency().ifPresent(group::setHardReservedConcurrency); // Hetu: set hardReservedConcurrency
        match.getSchedulingPolicy().ifPresent(group::setSchedulingPolicy);
        match.getSchedulingWeight().ifPresent(group::setSchedulingWeight);
        match.getJmxExport().filter(isEqual(group.getJmxExport()).negate()).ifPresent(group::setJmxExport);
        match.getSoftCpuLimit().ifPresent(group::setSoftCpuLimit);
        match.getHardCpuLimit().ifPresent(group::setHardCpuLimit);
        if (match.getSoftCpuLimit().isPresent() || match.getHardCpuLimit().isPresent()) {
            // This will never throw an exception if the validateRootGroups method succeeds
            checkState(getCpuQuotaPeriod().isPresent(), "Must specify hard CPU limit in addition to soft limit");
            Duration limit;
            if (match.getHardCpuLimit().isPresent()) {
                limit = match.getHardCpuLimit().get();
            }
            else {
                limit = match.getSoftCpuLimit().get();
            }
            long rate = (long) Math.min(1000.0 * limit.toMillis() / (double) getCpuQuotaPeriod().get().toMillis(), Long.MAX_VALUE);
            rate = Math.max(1, rate);
            group.setCpuQuotaGenerationMillisPerSecond(rate);
        }

        if (match.getKillPolicy().isPresent()) {
            group.setKillPolicy(match.getKillPolicy().get());
        }
        else {
            group.setKillPolicy(KillPolicy.NO_KILL);
        }
    }
}
