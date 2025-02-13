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
package io.prestosql.sql.planner.optimizations;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import io.prestosql.Session;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.connector.ConstantProperty;
import io.prestosql.spi.connector.GroupingProperty;
import io.prestosql.spi.connector.LocalProperty;
import io.prestosql.spi.connector.SortingProperty;
import io.prestosql.spi.plan.AggregationNode;
import io.prestosql.spi.plan.CTEScanNode;
import io.prestosql.spi.plan.JoinNode;
import io.prestosql.spi.plan.LimitNode;
import io.prestosql.spi.plan.MarkDistinctNode;
import io.prestosql.spi.plan.PlanNode;
import io.prestosql.spi.plan.PlanNodeIdAllocator;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.plan.TopNNode;
import io.prestosql.spi.plan.UnionNode;
import io.prestosql.spi.plan.WindowNode;
import io.prestosql.sql.planner.Partitioning;
import io.prestosql.sql.planner.PartitioningScheme;
import io.prestosql.sql.planner.PlanSymbolAllocator;
import io.prestosql.sql.planner.TypeAnalyzer;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.planner.optimizations.StreamPropertyDerivations.StreamProperties;
import io.prestosql.sql.planner.plan.ApplyNode;
import io.prestosql.sql.planner.plan.CubeFinishNode;
import io.prestosql.sql.planner.plan.DistinctLimitNode;
import io.prestosql.sql.planner.plan.EnforceSingleRowNode;
import io.prestosql.sql.planner.plan.ExchangeNode;
import io.prestosql.sql.planner.plan.ExplainAnalyzeNode;
import io.prestosql.sql.planner.plan.IndexJoinNode;
import io.prestosql.sql.planner.plan.InternalPlanVisitor;
import io.prestosql.sql.planner.plan.LateralJoinNode;
import io.prestosql.sql.planner.plan.OutputNode;
import io.prestosql.sql.planner.plan.RowNumberNode;
import io.prestosql.sql.planner.plan.SemiJoinNode;
import io.prestosql.sql.planner.plan.SortNode;
import io.prestosql.sql.planner.plan.SpatialJoinNode;
import io.prestosql.sql.planner.plan.StatisticsWriterNode;
import io.prestosql.sql.planner.plan.TableDeleteNode;
import io.prestosql.sql.planner.plan.TableFinishNode;
import io.prestosql.sql.planner.plan.TableWriterNode;
import io.prestosql.sql.planner.plan.TableWriterNode.DeleteAsInsertReference;
import io.prestosql.sql.planner.plan.TableWriterNode.UpdateReference;
import io.prestosql.sql.planner.plan.TopNRankingNumberNode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static io.prestosql.SystemSessionProperties.getTaskConcurrency;
import static io.prestosql.SystemSessionProperties.getTaskWriterCount;
import static io.prestosql.SystemSessionProperties.isDistributedSortEnabled;
import static io.prestosql.SystemSessionProperties.isSpillEnabled;
import static io.prestosql.operator.aggregation.AggregationUtils.hasSingleNodeExecutionPreference;
import static io.prestosql.operator.aggregation.AggregationUtils.isDecomposable;
import static io.prestosql.sql.planner.SystemPartitioningHandle.FIXED_ARBITRARY_DISTRIBUTION;
import static io.prestosql.sql.planner.SystemPartitioningHandle.FIXED_HASH_DISTRIBUTION;
import static io.prestosql.sql.planner.SystemPartitioningHandle.SINGLE_DISTRIBUTION;
import static io.prestosql.sql.planner.optimizations.StreamPreferredProperties.any;
import static io.prestosql.sql.planner.optimizations.StreamPreferredProperties.defaultParallelism;
import static io.prestosql.sql.planner.optimizations.StreamPreferredProperties.exactlyPartitionedOn;
import static io.prestosql.sql.planner.optimizations.StreamPreferredProperties.fixedParallelism;
import static io.prestosql.sql.planner.optimizations.StreamPreferredProperties.singleStream;
import static io.prestosql.sql.planner.optimizations.StreamPropertyDerivations.StreamProperties.StreamDistribution.FIXED;
import static io.prestosql.sql.planner.optimizations.StreamPropertyDerivations.StreamProperties.StreamDistribution.SINGLE;
import static io.prestosql.sql.planner.optimizations.StreamPropertyDerivations.derivePropertiesRecursively;
import static io.prestosql.sql.planner.plan.ChildReplacer.replaceChildren;
import static io.prestosql.sql.planner.plan.ExchangeNode.Scope.LOCAL;
import static io.prestosql.sql.planner.plan.ExchangeNode.Type.GATHER;
import static io.prestosql.sql.planner.plan.ExchangeNode.Type.REPARTITION;
import static io.prestosql.sql.planner.plan.ExchangeNode.gatheringExchange;
import static io.prestosql.sql.planner.plan.ExchangeNode.mergingExchange;
import static io.prestosql.sql.planner.plan.ExchangeNode.partitionedExchange;
import static java.util.Objects.requireNonNull;
import static java.util.function.Predicate.isEqual;
import static java.util.stream.Collectors.toList;

public class AddLocalExchanges
        implements PlanOptimizer
{
    private final Metadata metadata;
    private final TypeAnalyzer typeAnalyzer;

    public AddLocalExchanges(Metadata metadata, TypeAnalyzer typeAnalyzer)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.typeAnalyzer = requireNonNull(typeAnalyzer, "typeAnalyzer is null");
    }

    @Override
    public PlanNode optimize(PlanNode plan, Session session, TypeProvider types, PlanSymbolAllocator planSymbolAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        PlanWithProperties result = plan.accept(new Rewriter(planSymbolAllocator, idAllocator, session), any());
        return result.getNode();
    }

    private class Rewriter
            extends InternalPlanVisitor<PlanWithProperties, StreamPreferredProperties>
    {
        private final PlanNodeIdAllocator idAllocator;
        private final Session session;
        private final TypeProvider types;

        public Rewriter(PlanSymbolAllocator planSymbolAllocator, PlanNodeIdAllocator idAllocator, Session session)
        {
            this.types = planSymbolAllocator.getTypes();
            this.idAllocator = idAllocator;
            this.session = session;
        }

        @Override
        public PlanWithProperties visitPlan(PlanNode node, StreamPreferredProperties parentPreferences)
        {
            return planAndEnforceChildren(
                    node,
                    parentPreferences.withoutPreference().withDefaultParallelism(session),
                    parentPreferences.withDefaultParallelism(session));
        }

        @Override
        public PlanWithProperties visitApply(ApplyNode node, StreamPreferredProperties parentPreferences)
        {
            throw new IllegalStateException("Unexpected node: " + node.getClass().getName());
        }

        @Override
        public PlanWithProperties visitLateralJoin(LateralJoinNode node, StreamPreferredProperties parentPreferences)
        {
            throw new IllegalStateException("Unexpected node: " + node.getClass().getName());
        }

        @Override
        public PlanWithProperties visitOutput(OutputNode node, StreamPreferredProperties parentPreferences)
        {
            return planAndEnforceChildren(
                    node,
                    any().withOrderSensitivity(),
                    any().withOrderSensitivity());
        }

        @Override
        public PlanWithProperties visitExplainAnalyze(ExplainAnalyzeNode node, StreamPreferredProperties parentPreferences)
        {
            // Although explain analyze discards all output, we want to maintain the behavior
            // of a normal output node, so declare the node to be order sensitive
            return planAndEnforceChildren(
                    node,
                    singleStream().withOrderSensitivity(),
                    singleStream().withOrderSensitivity());
        }

        //
        // Nodes that always require a single stream
        //

        @Override
        public PlanWithProperties visitSort(SortNode node, StreamPreferredProperties parentPreferences)
        {
            if (isDistributedSortEnabled(session)) {
                PlanWithProperties sortPlan = planAndEnforceChildren(node, fixedParallelism(), fixedParallelism());

                if (!sortPlan.getProperties().isSingleStream()) {
                    return deriveProperties(
                            mergingExchange(
                                    idAllocator.getNextId(),
                                    LOCAL,
                                    sortPlan.getNode(),
                                    node.getOrderingScheme()),
                            sortPlan.getProperties());
                }

                return sortPlan;
            }
            // sort requires that all data be in one stream
            // this node changes the input organization completely, so we do not pass through parent preferences
            return planAndEnforceChildren(node, singleStream(), defaultParallelism(session));
        }

        @Override
        public PlanWithProperties visitStatisticsWriterNode(StatisticsWriterNode node, StreamPreferredProperties context)
        {
            // analyze finish requires that all data be in one stream
            // this node changes the input organization completely, so we do not pass through parent preferences
            return planAndEnforceChildren(node, singleStream(), defaultParallelism(session));
        }

        @Override
        public PlanWithProperties visitTableDelete(TableDeleteNode node, StreamPreferredProperties context)
        {
            if (node.getSource() != null) {
                // table delete requires that all data be in one stream
                // this node changes the input organization completely, so we do not pass through parent preferences
                return planAndEnforceChildren(node, singleStream(), defaultParallelism(session));
            }
            return super.visitTableDelete(node, context);
        }

        @Override
        public PlanWithProperties visitTableFinish(TableFinishNode node, StreamPreferredProperties parentPreferences)
        {
            // table commit requires that all data be in one stream
            // this node changes the input organization completely, so we do not pass through parent preferences
            return planAndEnforceChildren(node, singleStream(), defaultParallelism(session));
        }

        @Override
        public PlanWithProperties visitCubeFinish(CubeFinishNode node, StreamPreferredProperties parentPreferences)
        {
            // table commit requires that all data be in one stream
            // this node changes the input organization completely, so we do not pass through parent preferences
            return planAndEnforceChildren(node, singleStream(), defaultParallelism(session));
        }

        @Override
        public PlanWithProperties visitTopN(TopNNode node, StreamPreferredProperties parentPreferences)
        {
            if (node.getStep().equals(TopNNode.Step.PARTIAL)) {
                return planAndEnforceChildren(
                        node,
                        parentPreferences.withoutPreference().withDefaultParallelism(session),
                        parentPreferences.withDefaultParallelism(session));
            }

            // final topN requires that all data be in one stream
            // also, a final changes the input organization completely, so we do not pass through parent preferences
            return planAndEnforceChildren(
                    node,
                    singleStream(),
                    defaultParallelism(session));
        }

        @Override
        public PlanWithProperties visitLimit(LimitNode node, StreamPreferredProperties parentPreferences)
        {
            if (node.isWithTies()) {
                throw new IllegalStateException("Unexpected node: LimitNode with ties");
            }

            if (node.isPartial()) {
                return planAndEnforceChildren(
                        node,
                        parentPreferences.withoutPreference().withDefaultParallelism(session),
                        parentPreferences.withDefaultParallelism(session));
            }

            // final limit requires that all data be in one stream
            // also, a final changes the input organization completely, so we do not pass through parent preferences
            return planAndEnforceChildren(
                    node,
                    singleStream(),
                    defaultParallelism(session));
        }

        @Override
        public PlanWithProperties visitDistinctLimit(DistinctLimitNode node, StreamPreferredProperties parentPreferences)
        {
            // final limit requires that all data be in one stream
            StreamPreferredProperties requiredProperties;
            StreamPreferredProperties preferredProperties;
            if (node.isPartial()) {
                requiredProperties = parentPreferences.withoutPreference().withDefaultParallelism(session);
                preferredProperties = parentPreferences.withDefaultParallelism(session);
            }
            else {
                // a final changes the input organization completely, so we do not pass through parent preferences
                requiredProperties = singleStream();
                preferredProperties = defaultParallelism(session);
            }

            return planAndEnforceChildren(node, requiredProperties, preferredProperties);
        }

        @Override
        public PlanWithProperties visitEnforceSingleRow(EnforceSingleRowNode node, StreamPreferredProperties parentPreferences)
        {
            return planAndEnforceChildren(node, singleStream(), defaultParallelism(session));
        }

        //
        // Nodes that require parallel streams to be partitioned
        //

        @Override
        public PlanWithProperties visitAggregation(AggregationNode node, StreamPreferredProperties parentPreferences)
        {
            checkState(node.getStep() == AggregationNode.Step.SINGLE, "step of aggregation is expected to be SINGLE, but it is %s", node.getStep());

            if (hasSingleNodeExecutionPreference(node, metadata)) {
                return planAndEnforceChildren(node, singleStream(), defaultParallelism(session));
            }

            List<Symbol> groupingKeys = node.getGroupingKeys();
            if (node.hasDefaultOutput()) {
                checkState(isDecomposable(node, metadata));

                // Put fixed local exchange directly below final aggregation to ensure that final and partial aggregations are separated by exchange (in a local runner mode)
                // This is required so that default outputs from multiple instances of partial aggregations are passed to a single final aggregation.
                PlanWithProperties child = planAndEnforce(node.getSource(), any(), defaultParallelism(session));
                PlanWithProperties exchange = deriveProperties(
                        partitionedExchange(
                                idAllocator.getNextId(),
                                LOCAL,
                                child.getNode(),
                                groupingKeys,
                                Optional.empty()),
                        child.getProperties());
                return rebaseAndDeriveProperties(node, ImmutableList.of(exchange));
            }

            StreamPreferredProperties childRequirements = parentPreferences
                    .constrainTo(node.getSource().getOutputSymbols())
                    .withDefaultParallelism(session)
                    .withPartitioning(groupingKeys);

            PlanWithProperties child = planAndEnforce(node.getSource(), childRequirements, childRequirements, node.getAggregationType());

            List<Symbol> preGroupedSymbols = ImmutableList.of();
            if (!LocalProperties.match(child.getProperties().getLocalProperties(), LocalProperties.grouped(groupingKeys)).get(0).isPresent()) {
                // !isPresent() indicates the property was satisfied completely
                preGroupedSymbols = groupingKeys;
            }

            AggregationNode result = new AggregationNode(
                    node.getId(),
                    child.getNode(),
                    node.getAggregations(),
                    node.getGroupingSets(),
                    preGroupedSymbols,
                    node.getStep(),
                    node.getHashSymbol(),
                    node.getGroupIdSymbol(),
                    node.getAggregationType(),
                    node.getFinalizeSymbol());

            return deriveProperties(result, child.getProperties());
        }

        @Override
        public PlanWithProperties visitWindow(WindowNode node, StreamPreferredProperties parentPreferences)
        {
            StreamPreferredProperties childRequirements = parentPreferences
                    .constrainTo(node.getSource().getOutputSymbols())
                    .withDefaultParallelism(session)
                    .withPartitioning(node.getPartitionBy());

            PlanWithProperties child = planAndEnforce(node.getSource(), childRequirements, childRequirements);

            List<LocalProperty<Symbol>> desiredProperties = new ArrayList<>();
            if (!node.getPartitionBy().isEmpty()) {
                desiredProperties.add(new GroupingProperty<>(node.getPartitionBy()));
            }
            node.getOrderingScheme().ifPresent(orderingScheme ->
                    orderingScheme.getOrderBy().stream()
                            .map(symbol -> new SortingProperty<>(symbol, orderingScheme.getOrdering(symbol)))
                            .forEach(desiredProperties::add));
            Iterator<Optional<LocalProperty<Symbol>>> matchIterator = LocalProperties.match(child.getProperties().getLocalProperties(), desiredProperties).iterator();

            Set<Symbol> prePartitionedInputs = ImmutableSet.of();
            if (!node.getPartitionBy().isEmpty()) {
                Optional<LocalProperty<Symbol>> groupingRequirement = matchIterator.next();
                Set<Symbol> unPartitionedInputs = groupingRequirement.map(LocalProperty::getColumns).orElse(ImmutableSet.of());
                prePartitionedInputs = node.getPartitionBy().stream()
                        .filter(symbol -> !unPartitionedInputs.contains(symbol))
                        .collect(toImmutableSet());
            }

            int preSortedOrderPrefix = 0;
            if (prePartitionedInputs.equals(ImmutableSet.copyOf(node.getPartitionBy()))) {
                while (matchIterator.hasNext() && !matchIterator.next().isPresent()) {
                    preSortedOrderPrefix++;
                }
            }

            WindowNode result = new WindowNode(
                    node.getId(),
                    child.getNode(),
                    node.getSpecification(),
                    node.getWindowFunctions(),
                    node.getHashSymbol(),
                    prePartitionedInputs,
                    preSortedOrderPrefix);

            return deriveProperties(result, child.getProperties());
        }

        @Override
        public PlanWithProperties visitMarkDistinct(MarkDistinctNode node, StreamPreferredProperties parentPreferences)
        {
            // mark distinct requires that all data partitioned
            StreamPreferredProperties childRequirements = parentPreferences
                    .constrainTo(node.getSource().getOutputSymbols())
                    .withDefaultParallelism(session)
                    .withPartitioning(node.getDistinctSymbols());

            PlanWithProperties child = planAndEnforce(node.getSource(), childRequirements, childRequirements);

            MarkDistinctNode result = new MarkDistinctNode(
                    node.getId(),
                    child.getNode(),
                    node.getMarkerSymbol(),
                    pruneMarkDistinctSymbols(node, child.getProperties().getLocalProperties()),
                    node.getHashSymbol());

            return deriveProperties(result, child.getProperties());
        }

        /**
         * Prune redundant distinct symbols to reduce CPU cost of hashing corresponding values and amount of memory
         * needed to store all the distinct values.
         * <p>
         * Consider the following plan,
         * <pre>
         *  - MarkDistinctNode (unique, c1, c2)
         *      - Join
         *          - AssignUniqueId (unique)
         *              - probe (c1, c2)
         *          - build
         * </pre>
         * In this case MarkDistinctNode (unique, c1, c2) is equivalent to MarkDistinctNode (unique),
         * because if two rows match on `unique`, they must match on `c1` and `c2` as well.
         * <p>
         * More generally, any distinct symbol that is functionally dependent on a subset of
         * other distinct symbols can be dropped.
         * <p>
         * Ideally, this logic would be encapsulated in a separate rule, but currently no rule other
         * than AddLocalExchanges can reason about local properties.
         */
        private List<Symbol> pruneMarkDistinctSymbols(MarkDistinctNode node, List<LocalProperty<Symbol>> localProperties)
        {
            if (localProperties.isEmpty()) {
                return node.getDistinctSymbols();
            }

            // Identify functional dependencies between distinct symbols: in the list of local properties any constant
            // symbol is functionally dependent on the set of symbols that appears earlier.
            ImmutableSet.Builder<Symbol> redundantSymbolsBuilder = ImmutableSet.builder();
            for (LocalProperty<Symbol> property : localProperties) {
                if (property instanceof ConstantProperty) {
                    redundantSymbolsBuilder.add(((ConstantProperty<Symbol>) property).getColumn());
                }
                else if (!node.getDistinctSymbols().containsAll(property.getColumns())) {
                    // Ran into a non-distinct symbol. There will be no more symbols that are functionally dependent on distinct symbols exclusively.
                    break;
                }
            }

            Set<Symbol> redundantSymbols = redundantSymbolsBuilder.build();
            List<Symbol> remainingSymbols = node.getDistinctSymbols().stream()
                    .filter(symbol -> !redundantSymbols.contains(symbol))
                    .collect(toImmutableList());
            if (remainingSymbols.isEmpty()) {
                // This happens when all distinct symbols are constants.
                // In that case, keep the first symbol (don't drop them all).
                return ImmutableList.of(node.getDistinctSymbols().get(0));
            }
            return remainingSymbols;
        }

        @Override
        public PlanWithProperties visitRowNumber(RowNumberNode node, StreamPreferredProperties parentPreferences)
        {
            // row number requires that all data be partitioned
            StreamPreferredProperties requiredProperties = parentPreferences.withDefaultParallelism(session).withPartitioning(node.getPartitionBy());
            return planAndEnforceChildren(node, requiredProperties, requiredProperties);
        }

        @Override
        public PlanWithProperties visitTopNRankingNumber(TopNRankingNumberNode node, StreamPreferredProperties parentPreferences)
        {
            StreamPreferredProperties requiredProperties = parentPreferences.withDefaultParallelism(session);

            // final topN row number requires that all data be partitioned
            if (!node.isPartial()) {
                requiredProperties = requiredProperties.withPartitioning(node.getPartitionBy());
            }

            return planAndEnforceChildren(node, requiredProperties, requiredProperties);
        }

        //
        // Table Writer
        //

        @Override
        public PlanWithProperties visitTableWriter(TableWriterNode node, StreamPreferredProperties parentPreferences)
        {
            StreamPreferredProperties requiredProperties;
            StreamPreferredProperties preferredProperties;
            if (getTaskWriterCount(session) > 1) {
                boolean hasFixedHashDistribution = node.getPartitioningScheme()
                        .map(scheme -> scheme.getPartitioning().getHandle())
                        .filter(isEqual(FIXED_HASH_DISTRIBUTION))
                        .isPresent();
                if (!node.getPartitioningScheme().isPresent()) {
                    requiredProperties = fixedParallelism();
                    preferredProperties = fixedParallelism();
                }
                else if (hasFixedHashDistribution) {
                    requiredProperties = exactlyPartitionedOn(node.getPartitioningScheme().get().getPartitioning().getColumns());
                    preferredProperties = requiredProperties;
                }
                else {
                    requiredProperties = singleStream();
                    preferredProperties = defaultParallelism(session);
                }
            }
            else {
                requiredProperties = singleStream();
                preferredProperties = defaultParallelism(session);
            }
            if (node.getTarget() instanceof UpdateReference
                    || node.getTarget() instanceof DeleteAsInsertReference) {
                //Update and DeleteAsInsert requires fixed number parallelism
                // avoid change in order of data.
                requiredProperties = fixedParallelism();
                preferredProperties = fixedParallelism();
            }
            return planAndEnforceChildren(node, requiredProperties, preferredProperties);
        }

        //
        // Exchanges
        //

        @Override
        public PlanWithProperties visitExchange(ExchangeNode node, StreamPreferredProperties parentPreferences)
        {
            checkArgument(node.getScope() != LOCAL, "AddLocalExchanges can not process a plan containing a local exchange");
            // this node changes the input organization completely, so we do not pass through parent preferences
            if (node.getOrderingScheme().isPresent()) {
                return planAndEnforceChildren(
                        node,
                        any().withOrderSensitivity(),
                        any().withOrderSensitivity());
            }
            return planAndEnforceChildren(node, any(), defaultParallelism(session));
        }

        @Override
        public PlanWithProperties visitUnion(UnionNode node, StreamPreferredProperties preferredProperties)
        {
            // Union is replaced with an exchange which does not retain streaming properties from the children
            List<PlanWithProperties> sourcesWithProperties = node.getSources().stream()
                    .map(source -> source.accept(this, defaultParallelism(session)))
                    .collect(toImmutableList());

            List<PlanNode> sources = sourcesWithProperties.stream()
                    .map(PlanWithProperties::getNode)
                    .collect(toImmutableList());

            List<StreamProperties> inputProperties = sourcesWithProperties.stream()
                    .map(PlanWithProperties::getProperties)
                    .collect(toImmutableList());

            List<List<Symbol>> inputLayouts = new ArrayList<>(sources.size());
            for (int i = 0; i < sources.size(); i++) {
                inputLayouts.add(node.sourceOutputLayout(i));
            }

            if (preferredProperties.isSingleStreamPreferred()) {
                ExchangeNode exchangeNode = new ExchangeNode(
                        idAllocator.getNextId(),
                        GATHER,
                        LOCAL,
                        new PartitioningScheme(Partitioning.create(SINGLE_DISTRIBUTION, ImmutableList.of()), node.getOutputSymbols()),
                        sources,
                        inputLayouts,
                        Optional.empty(),
                        AggregationNode.AggregationType.HASH);
                return deriveProperties(exchangeNode, inputProperties);
            }

            Optional<List<Symbol>> preferredPartitionColumns = preferredProperties.getPartitioningColumns();
            if (preferredPartitionColumns.isPresent()) {
                ExchangeNode exchangeNode = new ExchangeNode(
                        idAllocator.getNextId(),
                        REPARTITION,
                        LOCAL,
                        new PartitioningScheme(
                                Partitioning.create(FIXED_HASH_DISTRIBUTION, preferredPartitionColumns.get()),
                                node.getOutputSymbols(),
                                Optional.empty()),
                        sources,
                        inputLayouts,
                        Optional.empty(),
                        AggregationNode.AggregationType.HASH);
                return deriveProperties(exchangeNode, inputProperties);
            }

            // multiple streams preferred
            ExchangeNode result = new ExchangeNode(
                    idAllocator.getNextId(),
                    REPARTITION,
                    LOCAL,
                    new PartitioningScheme(Partitioning.create(FIXED_ARBITRARY_DISTRIBUTION, ImmutableList.of()), node.getOutputSymbols()),
                    sources,
                    inputLayouts,
                    Optional.empty(),
                    AggregationNode.AggregationType.HASH);
            ExchangeNode exchangeNode = result;

            return deriveProperties(exchangeNode, inputProperties);
        }

        //
        // Joins
        //

        @Override
        public PlanWithProperties visitJoin(JoinNode node, StreamPreferredProperties parentPreferences)
        {
            PlanWithProperties probe = planAndEnforce(
                    node.getLeft(),
                    defaultParallelism(session),
                    parentPreferences.constrainTo(node.getLeft().getOutputSymbols()).withDefaultParallelism(session));

            if (isSpillEnabled(session)) {
                if (probe.getProperties().getDistribution() != FIXED) {
                    // Disable spill for joins over non-fixed streams as otherwise we would need to insert local exchange.
                    // Such local exchanges can hurt performance when spill is not triggered.
                    // When spill is not triggered it should not induce performance penalty.
                    node = node.withSpillable(false);
                }
                else {
                    node = node.withSpillable(true);
                }
            }

            // this build consumes the input completely, so we do not pass through parent preferences
            List<Symbol> buildHashSymbols = Lists.transform(node.getCriteria(), JoinNode.EquiJoinClause::getRight);
            StreamPreferredProperties buildPreference;
            if (getTaskConcurrency(session) > 1) {
                buildPreference = exactlyPartitionedOn(buildHashSymbols);
            }
            else {
                buildPreference = singleStream();
            }
            PlanWithProperties build = planAndEnforce(node.getRight(), buildPreference, buildPreference);

            return rebaseAndDeriveProperties(node, ImmutableList.of(probe, build));
        }

        @Override
        public PlanWithProperties visitSemiJoin(SemiJoinNode node, StreamPreferredProperties parentPreferences)
        {
            PlanWithProperties source = planAndEnforce(
                    node.getSource(),
                    defaultParallelism(session),
                    parentPreferences.constrainTo(node.getSource().getOutputSymbols()).withDefaultParallelism(session));

            // this filter source consumes the input completely, so we do not pass through parent preferences
            PlanWithProperties filteringSource = planAndEnforce(node.getFilteringSource(), singleStream(), singleStream());

            return rebaseAndDeriveProperties(node, ImmutableList.of(source, filteringSource));
        }

        @Override
        public PlanWithProperties visitSpatialJoin(SpatialJoinNode node, StreamPreferredProperties parentPreferences)
        {
            PlanWithProperties probe = planAndEnforce(
                    node.getLeft(),
                    defaultParallelism(session),
                    parentPreferences.constrainTo(node.getLeft().getOutputSymbols())
                            .withDefaultParallelism(session));

            PlanWithProperties build = planAndEnforce(node.getRight(), singleStream(), singleStream());

            return rebaseAndDeriveProperties(node, ImmutableList.of(probe, build));
        }

        @Override
        public PlanWithProperties visitIndexJoin(IndexJoinNode node, StreamPreferredProperties parentPreferences)
        {
            PlanWithProperties probe = planAndEnforce(
                    node.getProbeSource(),
                    defaultParallelism(session),
                    parentPreferences.constrainTo(node.getProbeSource().getOutputSymbols()).withDefaultParallelism(session));

            // index source does not support local parallel and must produce a single stream
            StreamProperties indexStreamProperties = derivePropertiesRecursively(node.getIndexSource(), metadata, session, types, typeAnalyzer);
            checkArgument(indexStreamProperties.getDistribution() == SINGLE, "index source must be single stream");
            PlanWithProperties index = new PlanWithProperties(node.getIndexSource(), indexStreamProperties);

            return rebaseAndDeriveProperties(node, ImmutableList.of(probe, index));
        }

        @Override
        public PlanWithProperties visitCTEScan(CTEScanNode node, StreamPreferredProperties parentPreferences)
        {
            PlanWithProperties planWithProperties = visitPlan(node, parentPreferences);
            return planWithProperties;
        }

        //
        // Helpers
        //

        private PlanWithProperties planAndEnforceChildren(PlanNode node, StreamPreferredProperties requiredProperties, StreamPreferredProperties preferredProperties)
        {
            // plan and enforce each child, but strip any requirement not in terms of symbols produced from the child
            // Note: this assumes the child uses the same symbols as the parent
            List<PlanWithProperties> children = node.getSources().stream()
                    .map(source -> planAndEnforce(
                            source,
                            requiredProperties.constrainTo(source.getOutputSymbols()),
                            preferredProperties.constrainTo(source.getOutputSymbols())))
                    .collect(toImmutableList());

            return rebaseAndDeriveProperties(node, children);
        }

        private PlanWithProperties planAndEnforce(PlanNode node, StreamPreferredProperties requiredProperties, StreamPreferredProperties preferredProperties)
        {
            return planAndEnforce(node, requiredProperties, preferredProperties, AggregationNode.AggregationType.HASH);
        }

        private PlanWithProperties planAndEnforce(PlanNode node, StreamPreferredProperties requiredProperties, StreamPreferredProperties preferredProperties, AggregationNode.AggregationType aggregationType)
        {
            // verify properties are in terms of symbols produced by the node
            List<Symbol> outputSymbols = node.getOutputSymbols();
            checkArgument(requiredProperties.getPartitioningColumns().map(outputSymbols::containsAll).orElse(true));
            checkArgument(preferredProperties.getPartitioningColumns().map(outputSymbols::containsAll).orElse(true));

            // plan the node using the preferred properties
            PlanWithProperties result = node.accept(this, preferredProperties);

            // enforce the required properties
            result = enforce(result, requiredProperties, aggregationType);

            checkState(requiredProperties.isSatisfiedBy(result.getProperties()), "required properties not enforced");
            return result;
        }

        private PlanWithProperties enforce(PlanWithProperties planWithProperties, StreamPreferredProperties requiredProperties, AggregationNode.AggregationType aggregationType)
        {
            if (requiredProperties.isSatisfiedBy(planWithProperties.getProperties())) {
                return planWithProperties;
            }

            if (requiredProperties.isSingleStreamPreferred()) {
                ExchangeNode exchangeNode = gatheringExchange(idAllocator.getNextId(), LOCAL, planWithProperties.getNode());
                return deriveProperties(exchangeNode, planWithProperties.getProperties());
            }

            Optional<List<Symbol>> requiredPartitionColumns = requiredProperties.getPartitioningColumns();
            if (!requiredPartitionColumns.isPresent()) {
                // unpartitioned parallel streams required
                ExchangeNode exchangeNode = partitionedExchange(
                        idAllocator.getNextId(),
                        LOCAL,
                        planWithProperties.getNode(),
                        new PartitioningScheme(Partitioning.create(FIXED_ARBITRARY_DISTRIBUTION, ImmutableList.of()), planWithProperties.getNode().getOutputSymbols()));

                return deriveProperties(exchangeNode, planWithProperties.getProperties());
            }

            if (requiredProperties.isParallelPreferred()) {
                // partitioned parallel streams required
                ExchangeNode exchangeNode = partitionedExchange(
                        idAllocator.getNextId(),
                        LOCAL,
                        planWithProperties.getNode(),
                        requiredPartitionColumns.get(),
                        Optional.empty(),
                        false,
                        aggregationType);
                return deriveProperties(exchangeNode, planWithProperties.getProperties());
            }

            // no explicit parallel requirement, so gather to a single stream
            ExchangeNode exchangeNode = gatheringExchange(
                    idAllocator.getNextId(),
                    LOCAL,
                    planWithProperties.getNode());
            return deriveProperties(exchangeNode, planWithProperties.getProperties());
        }

        private PlanWithProperties rebaseAndDeriveProperties(PlanNode node, List<PlanWithProperties> children)
        {
            PlanNode result = replaceChildren(
                    node,
                    children.stream()
                            .map(PlanWithProperties::getNode)
                            .collect(toList()));

            List<StreamProperties> inputProperties = children.stream()
                    .map(PlanWithProperties::getProperties)
                    .collect(toImmutableList());

            return deriveProperties(result, inputProperties);
        }

        private PlanWithProperties deriveProperties(PlanNode result, StreamProperties inputProperties)
        {
            return new PlanWithProperties(result, StreamPropertyDerivations.deriveProperties(result, inputProperties, metadata, session, types, typeAnalyzer));
        }

        private PlanWithProperties deriveProperties(PlanNode result, List<StreamProperties> inputProperties)
        {
            return new PlanWithProperties(result, StreamPropertyDerivations.deriveProperties(result, inputProperties, metadata, session, types, typeAnalyzer));
        }
    }

    private static class PlanWithProperties
    {
        private final PlanNode node;
        private final StreamProperties properties;

        public PlanWithProperties(PlanNode node, StreamProperties properties)
        {
            this.node = requireNonNull(node, "node is null");
            this.properties = requireNonNull(properties, "StreamProperties is null");
        }

        public PlanNode getNode()
        {
            return node;
        }

        public StreamProperties getProperties()
        {
            return properties;
        }
    }
}
