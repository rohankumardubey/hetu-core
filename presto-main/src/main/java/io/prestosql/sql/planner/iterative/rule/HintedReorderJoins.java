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

package io.prestosql.sql.planner.iterative.rule;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Ordering;
import io.airlift.log.Logger;
import io.prestosql.Session;
import io.prestosql.SystemSessionProperties;
import io.prestosql.cost.CostCalculator;
import io.prestosql.cost.CostComparator;
import io.prestosql.cost.CostProvider;
import io.prestosql.cost.PlanCostEstimate;
import io.prestosql.cost.StatsCalculator;
import io.prestosql.execution.warnings.WarningCollector;
import io.prestosql.matching.Captures;
import io.prestosql.matching.Pattern;
import io.prestosql.spi.plan.FilterNode;
import io.prestosql.spi.plan.JoinNode;
import io.prestosql.spi.plan.JoinNode.DistributionType;
import io.prestosql.spi.plan.JoinNode.EquiJoinClause;
import io.prestosql.spi.plan.PlanNode;
import io.prestosql.spi.plan.PlanNodeIdAllocator;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.plan.TableScanNode;
import io.prestosql.sql.analyzer.FeaturesConfig;
import io.prestosql.sql.analyzer.FeaturesConfig.JoinDistributionType;
import io.prestosql.sql.planner.EqualityInference;
import io.prestosql.sql.planner.PlanSymbolAllocator;
import io.prestosql.sql.planner.RuleStatsRecorder;
import io.prestosql.sql.planner.SymbolUtils;
import io.prestosql.sql.planner.SymbolsExtractor;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.planner.iterative.IterativeOptimizer;
import io.prestosql.sql.planner.iterative.Lookup;
import io.prestosql.sql.planner.iterative.Rule;
import io.prestosql.sql.planner.optimizations.JoinNodeUtils;
import io.prestosql.sql.planner.optimizations.PlanOptimizer;
import io.prestosql.sql.planner.plan.InternalPlanVisitor;
import io.prestosql.sql.planner.plan.SimplePlanRewriter;
import io.prestosql.sql.relational.OriginalExpressionUtils;
import io.prestosql.sql.tree.ComparisonExpression;
import io.prestosql.sql.tree.Expression;
import io.prestosql.sql.tree.SymbolReference;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Predicates.in;
import static com.google.common.base.Verify.verify;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Sets.powerSet;
import static com.google.common.collect.Streams.stream;
import static io.prestosql.SystemSessionProperties.getJoinDistributionType;
import static io.prestosql.SystemSessionProperties.getJoinReorderingStrategy;
import static io.prestosql.SystemSessionProperties.getMaxReorderedJoins;
import static io.prestosql.spi.plan.JoinNode.DistributionType.PARTITIONED;
import static io.prestosql.spi.plan.JoinNode.DistributionType.REPLICATED;
import static io.prestosql.spi.plan.JoinNode.Type.INNER;
import static io.prestosql.sql.ExpressionUtils.and;
import static io.prestosql.sql.ExpressionUtils.combineConjuncts;
import static io.prestosql.sql.ExpressionUtils.extractConjuncts;
import static io.prestosql.sql.planner.EqualityInference.createEqualityInference;
import static io.prestosql.sql.planner.EqualityInference.nonInferrableConjuncts;
import static io.prestosql.sql.planner.ExpressionDeterminismEvaluator.isDeterministic;
import static io.prestosql.sql.planner.iterative.rule.DetermineJoinDistributionType.canReplicate;
import static io.prestosql.sql.planner.iterative.rule.HintedReorderJoins.HintedReorderJoinsRule.JoinEnumerationResult.INFINITE_COST_RESULT;
import static io.prestosql.sql.planner.iterative.rule.HintedReorderJoins.HintedReorderJoinsRule.JoinEnumerationResult.UNKNOWN_COST_RESULT;
import static io.prestosql.sql.planner.iterative.rule.HintedReorderJoins.HintedReorderJoinsRule.MultiJoinNode.toMultiJoinNode;
import static io.prestosql.sql.planner.optimizations.QueryCardinalityUtil.isAtMostScalar;
import static io.prestosql.sql.planner.plan.ChildReplacer.replaceChildren;
import static io.prestosql.sql.planner.plan.Patterns.join;
import static io.prestosql.sql.relational.OriginalExpressionUtils.castToRowExpression;
import static io.prestosql.sql.tree.BooleanLiteral.TRUE_LITERAL;
import static io.prestosql.sql.tree.ComparisonExpression.Operator.EQUAL;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toCollection;

@SuppressWarnings("Duplicates")
public class HintedReorderJoins
        implements PlanOptimizer
{
    private final RuleStatsRecorder stats;
    private final StatsCalculator statsCalculator;
    private final CostCalculator costCalculator;
    private final CostComparator costComparator;

    public HintedReorderJoins(RuleStatsRecorder stats, StatsCalculator statsCalculator, CostCalculator costCalculator, CostComparator costComparator)
    {
        this.stats = stats;
        this.statsCalculator = statsCalculator;
        this.costCalculator = costCalculator;
        this.costComparator = costComparator;
    }

    @Override
    public PlanNode optimize(PlanNode plan, Session session, TypeProvider types, PlanSymbolAllocator planSymbolAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
    {
        return SimplePlanRewriter.rewriteWith(new PreRuleOptimizer(session, types, planSymbolAllocator, idAllocator, warningCollector),
                plan);
    }

    private class PreRuleOptimizer
            extends SimplePlanRewriter<Void>
    {
        private final Session session;
        private final TypeProvider types;
        private final PlanSymbolAllocator planSymbolAllocator;
        private final PlanNodeIdAllocator idAllocator;
        private final WarningCollector warningCollector;
        private Set<PlanNode> optimizableSources;

        private PreRuleOptimizer(Session session, TypeProvider types, PlanSymbolAllocator planSymbolAllocator, PlanNodeIdAllocator idAllocator, WarningCollector warningCollector)
        {
            this.session = session;
            this.types = types;
            this.planSymbolAllocator = planSymbolAllocator;
            this.idAllocator = idAllocator;
            this.warningCollector = warningCollector;
        }

        @Override
        public PlanNode visitPlan(PlanNode node, RewriteContext<Void> context)
        {
            if (optimizableSources != null && optimizableSources.contains(node)) {
                // this node is already considered as part of the highest level join
                return rewrite(node);
            }
            return super.visitPlan(node, context);
        }

        @Override
        public PlanNode visitJoin(JoinNode node, RewriteContext<Void> context)
        {
            if (optimizableSources == null) {
                // The highest level join
                ReorderJoins.MultiJoinNode multiJoinNode = JoinNodeCandidatesExtractor.toMultiJoinNode(node, getMaxReorderedJoins(session));
                optimizableSources = multiJoinNode.getSources();
                PlanNode left = context.rewrite(node.getLeft(), context.get());
                PlanNode right = context.rewrite(node.getRight(), context.get());
                PlanNode planNode = replaceChildren(node, ImmutableList.of(left, right));
                planNode = rewrite(planNode);
                return planNode;
            }
            return visitPlan(node, context);
        }

        private PlanNode rewrite(PlanNode planNode)
        {
            return new IterativeOptimizer(stats,
                    statsCalculator,
                    costCalculator,
                    ImmutableSet.of(new HintedReorderJoinsRule(costComparator))).optimize(planNode, session, types, planSymbolAllocator, idAllocator, warningCollector);
        }
    }

    private static class JoinNodeCandidatesExtractor
    {
        private final LinkedHashSet<PlanNode> sources = new LinkedHashSet<>();
        private final List<Expression> filters = new ArrayList<>();
        private final List<Symbol> outputSymbols;

        JoinNodeCandidatesExtractor(JoinNode node, int sourceLimit)
        {
            requireNonNull(node, "node is null");
            checkState(node.getType() == INNER, "join type must be INNER");
            this.outputSymbols = node.getOutputSymbols();
            flattenNode(node, sourceLimit);
        }

        static ReorderJoins.MultiJoinNode toMultiJoinNode(JoinNode joinNode, int joinLimit)
        {
            // the number of sources is the number of joins + 1
            return new JoinNodeCandidatesExtractor(joinNode, joinLimit + 1).toMultiJoinNode();
        }

        private void flattenNode(PlanNode node, int limit)
        {
            // (limit - 2) because you need to account for adding left and right side
            if (!(node instanceof JoinNode) || (sources.size() > (limit - 2))) {
                sources.add(node);
                return;
            }

            JoinNode joinNode = (JoinNode) node;
            if (joinNode.getType() != INNER
                    || !isDeterministic(joinNode.getFilter().map(OriginalExpressionUtils::castToExpression).orElse(TRUE_LITERAL))
                    || joinNode.getDistributionType().isPresent()) {
                sources.add(node);
                return;
            }

            // we set the left limit to limit - 1 to account for the node on the right
            flattenNode(joinNode.getLeft(), limit - 1);
            flattenNode(joinNode.getRight(), limit);
            joinNode.getCriteria().stream()
                    .map(JoinNodeUtils::toExpression)
                    .forEach(filters::add);
        }

        ReorderJoins.MultiJoinNode toMultiJoinNode()
        {
            return new ReorderJoins.MultiJoinNode(sources, and(filters), outputSymbols);
        }
    }

    static class HintedReorderJoinsRule
            implements Rule<JoinNode>
    {
        private static final Logger log = Logger.get(HintedReorderJoins.class);

        // We check that join distribution type is absent because we only want
        // to do this transformation once (reordered joins will have distribution type already set).
        private static final Pattern<JoinNode> PATTERN = join().matching(
                joinNode -> !joinNode.getDistributionType().isPresent()
                        && joinNode.getType() == INNER
                        && isDeterministic(joinNode.getFilter().map(OriginalExpressionUtils::castToExpression).orElse(TRUE_LITERAL)));

        private final CostComparator costComparator;

        public HintedReorderJoinsRule(CostComparator costComparator)
        {
            this.costComparator = requireNonNull(costComparator, "costComparator is null");
        }

        @Override
        public Pattern<JoinNode> getPattern()
        {
            return PATTERN;
        }

        @Override
        public boolean isEnabled(Session session)
        {
            if (getJoinReorderingStrategy(session) != FeaturesConfig.JoinReorderingStrategy.NONE) {
                return false;
            }
            String joinOrder = SystemSessionProperties.getJoinOrder(session);
            return joinOrder != null && !"".equals(joinOrder);
        }

        @Override
        public Result apply(JoinNode joinNode, Captures captures, Context context)
        {
            MultiJoinNode multiJoinNode = toMultiJoinNode(joinNode, context.getLookup(), getMaxReorderedJoins(context.getSession()));
            JoinEnumerator joinEnumerator = new JoinEnumerator(
                    costComparator,
                    multiJoinNode.getFilter(),
                    context);
            String tablePriority = "";
            String joinOrder = SystemSessionProperties.getJoinOrder(context.getSession());
            if (joinOrder != null && !"".equals(joinOrder.trim())) {
                tablePriority = joinOrder.trim();
            }
            Lookup lookup = context.getLookup();
            for (PlanNode planNode : multiJoinNode.getSources()) {
                StringBuilder builder = new StringBuilder();
                TableNameExtractor extractor = new TableNameExtractor(context.getLookup());
                lookup.resolve(planNode).accept(extractor, builder);
            }
            JoinEnumerationResult result = joinEnumerator.chooseJoinOrder(multiJoinNode.getSources(), multiJoinNode.getOutputSymbols(), tablePriority);
            if (!result.getPlanNode().isPresent()) {
                return Result.empty();
            }
            return Result.ofPlanNode(result.getPlanNode().get());
        }

        @VisibleForTesting
        static class JoinEnumerator
        {
            private final Session session;
            private final CostProvider costProvider;
            // Using Ordering to facilitate rule determinism
            private final Ordering<JoinEnumerationResult> resultComparator;
            private final PlanNodeIdAllocator idAllocator;
            private final Expression allFilter;
            private final EqualityInference allFilterInference;
            private final Lookup lookup;
            private final Context context;

            private final Map<Set<PlanNode>, JoinEnumerationResult> memo = new HashMap<>();

            @VisibleForTesting
            JoinEnumerator(CostComparator costComparator, Expression filter, Context context)
            {
                this.context = requireNonNull(context);
                this.session = requireNonNull(context.getSession(), "session is null");
                this.costProvider = requireNonNull(context.getCostProvider(), "costProvider is null");
                this.resultComparator = costComparator.forSession(session).onResultOf(result -> result.cost);
                this.idAllocator = requireNonNull(context.getIdAllocator(), "idAllocator is null");
                this.allFilter = requireNonNull(filter, "filter is null");
                this.allFilterInference = createEqualityInference(filter);
                this.lookup = requireNonNull(context.getLookup(), "lookup is null");
            }

            private JoinEnumerationResult chooseJoinOrder(LinkedHashSet<PlanNode> sources, List<Symbol> outputSymbols, String tablePriority)
            {
                context.checkTimeoutNotExhausted();

                Set<PlanNode> multiJoinKey = ImmutableSet.copyOf(sources);
                JoinEnumerationResult bestResult = memo.get(multiJoinKey);
                if (bestResult == null) {
                    checkState(sources.size() > 1, "sources size is less than or equal to one");
                    ImmutableList.Builder<JoinEnumerationResult> resultBuilder = ImmutableList.builder();
                    Set<Set<Integer>> partitions = generatePartitions(sources.size());
                    for (Set<Integer> partition : partitions) {
                        JoinEnumerationResult result = createJoinAccordingToPartitioning(sources, outputSymbols, partition, tablePriority);
                        if (result.planNode.isPresent() && tablePriority != null && !"".equals(tablePriority)) {
                            StringBuilder builder = new StringBuilder();
                            TableNameExtractor extractor = new TableNameExtractor(lookup);
                            result.planNode.get().accept(extractor, builder);
                            if (TableNameExtractor.startsWith(builder.toString(), tablePriority)) {
                                memo.put(multiJoinKey, result);
                                return result;
                            }
                        }
                        if (result.equals(UNKNOWN_COST_RESULT)) {
                            memo.put(multiJoinKey, result);
                            return result;
                        }
                        if (!result.equals(INFINITE_COST_RESULT)) {
                            resultBuilder.add(result);
                        }
                    }

                    List<JoinEnumerationResult> results = resultBuilder.build();
                    if (results.isEmpty()) {
                        memo.put(multiJoinKey, INFINITE_COST_RESULT);
                        return INFINITE_COST_RESULT;
                    }

                    if (results.size() > 1) {
                        for (JoinEnumerationResult result : results) {
                            StringBuilder builder = new StringBuilder();
                            TableNameExtractor extractor = new TableNameExtractor(lookup);
                            result.planNode.get().accept(extractor, builder);
                            if (TableNameExtractor.startsWith(builder.toString(), tablePriority)) {
                                memo.put(multiJoinKey, result);
                                return result;
                            }
                        }
                    }
                    bestResult = resultComparator.min(results);
                    memo.put(multiJoinKey, bestResult);
                }

                bestResult.planNode.ifPresent((planNode) -> log.debug("Least cost join was: %s", planNode));
                return bestResult;
            }

            /**
             * This method generates all the ways of dividing totalNodes into two sets
             * each containing at least one node. It will generate one set for each
             * possible partitioning. The other partition is implied in the absent values.
             * In order not to generate the inverse of any set, we always include the 0th
             * node in our sets.
             *
             * @return A set of sets each of which defines a partitioning of totalNodes
             */
            @VisibleForTesting
            static Set<Set<Integer>> generatePartitions(int totalNodes)
            {
                checkArgument(totalNodes > 1, "totalNodes must be greater than 1");
                Set<Integer> numbers = IntStream.range(0, totalNodes)
                        .boxed()
                        .collect(toImmutableSet());
                return powerSet(numbers).stream()
                        .filter(subSet -> subSet.contains(0))
                        .filter(subSet -> subSet.size() < numbers.size())
                        .collect(toImmutableSet());
            }

            @VisibleForTesting
            JoinEnumerationResult createJoinAccordingToPartitioning(LinkedHashSet<PlanNode> sources, List<Symbol> outputSymbols, Set<Integer> partitioning)
            {
                return this.createJoinAccordingToPartitioning(sources, outputSymbols, partitioning, "");
            }

            @VisibleForTesting
            JoinEnumerationResult createJoinAccordingToPartitioning(LinkedHashSet<PlanNode> sources, List<Symbol> outputSymbols, Set<Integer> partitioning, String expectedPattern)
            {
                List<PlanNode> sourceList = ImmutableList.copyOf(sources);
                LinkedHashSet<PlanNode> leftSources = partitioning.stream()
                        .map(sourceList::get)
                        .collect(toCollection(LinkedHashSet::new));
                LinkedHashSet<PlanNode> rightSources = sources.stream()
                        .filter(source -> !leftSources.contains(source))
                        .collect(toCollection(LinkedHashSet::new));
                return createJoin(leftSources, rightSources, outputSymbols, expectedPattern);
            }

            private JoinEnumerationResult createJoin(LinkedHashSet<PlanNode> leftSources, LinkedHashSet<PlanNode> rightSources, List<Symbol> outputSymbols, String expectedPattern)
            {
                Set<Symbol> leftSymbols = leftSources.stream()
                        .flatMap(node -> node.getOutputSymbols().stream())
                        .collect(toImmutableSet());
                Set<Symbol> rightSymbols = rightSources.stream()
                        .flatMap(node -> node.getOutputSymbols().stream())
                        .collect(toImmutableSet());

                List<Expression> joinPredicates = getJoinPredicates(leftSymbols, rightSymbols);
                List<EquiJoinClause> joinConditions = joinPredicates.stream()
                        .filter(JoinEnumerator::isJoinEqualityCondition)
                        .map(predicate -> toEquiJoinClause((ComparisonExpression) predicate, leftSymbols))
                        .collect(toImmutableList());
                if (joinConditions.isEmpty()) {
                    return INFINITE_COST_RESULT;
                }
                List<Expression> joinFilters = joinPredicates.stream()
                        .filter(predicate -> !isJoinEqualityCondition(predicate))
                        .collect(toImmutableList());

                Set<Symbol> requiredJoinSymbols = ImmutableSet.<Symbol>builder()
                        .addAll(outputSymbols)
                        .addAll(SymbolsExtractor.extractUnique(joinPredicates))
                        .build();

                JoinEnumerationResult leftResult = getJoinSource(
                        leftSources,
                        requiredJoinSymbols.stream()
                                .filter(leftSymbols::contains)
                                .collect(toImmutableList()),
                        expectedPattern);
                if (leftResult.equals(UNKNOWN_COST_RESULT)) {
                    return UNKNOWN_COST_RESULT;
                }
                if (leftResult.equals(INFINITE_COST_RESULT)) {
                    return INFINITE_COST_RESULT;
                }

                PlanNode left = leftResult.planNode.orElseThrow(() -> new VerifyException("Plan node is not present"));

                JoinEnumerationResult rightResult = getJoinSource(
                        rightSources,
                        requiredJoinSymbols.stream()
                                .filter(rightSymbols::contains)
                                .collect(toImmutableList()),
                        expectedPattern);
                if (rightResult.equals(UNKNOWN_COST_RESULT)) {
                    return UNKNOWN_COST_RESULT;
                }
                if (rightResult.equals(INFINITE_COST_RESULT)) {
                    return INFINITE_COST_RESULT;
                }

                PlanNode right = rightResult.planNode.orElseThrow(() -> new VerifyException("Plan node is not present"));

                // sort output symbols so that the left input symbols are first
                List<Symbol> sortedOutputSymbols = Stream.concat(left.getOutputSymbols().stream(), right.getOutputSymbols().stream())
                        .filter(outputSymbols::contains)
                        .collect(toImmutableList());

                return setJoinNodeProperties(new JoinNode(
                                idAllocator.getNextId(),
                                INNER,
                                left,
                                right,
                                joinConditions,
                                sortedOutputSymbols,
                                joinFilters.isEmpty() ? Optional.empty() : Optional.of(and(joinFilters)).map(OriginalExpressionUtils::castToRowExpression),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                Optional.empty(),
                                ImmutableMap.of()),
                        expectedPattern);
            }

            private List<Expression> getJoinPredicates(Set<Symbol> leftSymbols, Set<Symbol> rightSymbols)
            {
                ImmutableList.Builder<Expression> joinPredicatesBuilder = ImmutableList.builder();

                // This takes all conjuncts that were part of allFilters that
                // could not be used for equality inference.
                // If they use both the left and right symbols, we add them to the list of joinPredicates
                stream(nonInferrableConjuncts(allFilter))
                        .map(conjunct -> allFilterInference.rewriteExpression(conjunct, symbol -> leftSymbols.contains(symbol) || rightSymbols.contains(symbol)))
                        .filter(Objects::nonNull)
                        // filter expressions that contain only left or right symbols
                        .filter(conjunct -> allFilterInference.rewriteExpression(conjunct, leftSymbols::contains) == null)
                        .filter(conjunct -> allFilterInference.rewriteExpression(conjunct, rightSymbols::contains) == null)
                        .forEach(joinPredicatesBuilder::add);

                // create equality inference on available symbols
                // TODO: make generateEqualitiesPartitionedBy take left and right scope
                List<Expression> joinEqualities = allFilterInference.generateEqualitiesPartitionedBy(symbol -> leftSymbols.contains(symbol) || rightSymbols.contains(symbol)).getScopeEqualities();
                EqualityInference joinInference = createEqualityInference(joinEqualities.toArray(new Expression[0]));
                joinPredicatesBuilder.addAll(joinInference.generateEqualitiesPartitionedBy(in(leftSymbols)).getScopeStraddlingEqualities());

                return joinPredicatesBuilder.build();
            }

            private JoinEnumerationResult getJoinSource(LinkedHashSet<PlanNode> nodes, List<Symbol> outputSymbols, String expectedPattern)
            {
                if (nodes.size() == 1) {
                    PlanNode planNode = getOnlyElement(nodes);
                    ImmutableList.Builder<Expression> predicates = ImmutableList.builder();
                    predicates.addAll(allFilterInference.generateEqualitiesPartitionedBy(outputSymbols::contains).getScopeEqualities());
                    stream(nonInferrableConjuncts(allFilter))
                            .map(conjunct -> allFilterInference.rewriteExpression(conjunct, outputSymbols::contains))
                            .filter(Objects::nonNull)
                            .forEach(predicates::add);
                    Expression filter = combineConjuncts(predicates.build());
                    if (!TRUE_LITERAL.equals(filter)) {
                        planNode = new FilterNode(idAllocator.getNextId(), planNode, castToRowExpression(filter));
                    }
                    return createJoinEnumerationResult(planNode);
                }
                return chooseJoinOrder(nodes, outputSymbols, expectedPattern);
            }

            private static boolean isJoinEqualityCondition(Expression expression)
            {
                return expression instanceof ComparisonExpression
                        && ((ComparisonExpression) expression).getOperator() == EQUAL
                        && ((ComparisonExpression) expression).getLeft() instanceof SymbolReference
                        && ((ComparisonExpression) expression).getRight() instanceof SymbolReference;
            }

            private static EquiJoinClause toEquiJoinClause(ComparisonExpression equality, Set<Symbol> leftSymbols)
            {
                Symbol leftSymbol = SymbolUtils.from(equality.getLeft());
                Symbol rightSymbol = SymbolUtils.from(equality.getRight());
                EquiJoinClause equiJoinClause = new EquiJoinClause(leftSymbol, rightSymbol);
                return leftSymbols.contains(leftSymbol) ? equiJoinClause : equiJoinClause.flip();
            }

            private JoinEnumerationResult setJoinNodeProperties(JoinNode joinNode, String expectedPattern)
            {
                if (isAtMostScalar(joinNode.getRight(), lookup)) {
                    return createJoinEnumerationResult(joinNode.withDistributionType(REPLICATED));
                }
                if (isAtMostScalar(joinNode.getLeft(), lookup)) {
                    return createJoinEnumerationResult(joinNode.flipChildren().withDistributionType(REPLICATED));
                }
                List<JoinEnumerationResult> possibleJoinNodes = getPossibleJoinNodes(joinNode, getJoinDistributionType(session));
                verify(!possibleJoinNodes.isEmpty(), "possibleJoinNodes is empty");
                if (possibleJoinNodes.stream().anyMatch(UNKNOWN_COST_RESULT::equals)) {
                    return UNKNOWN_COST_RESULT;
                }
                for (JoinEnumerationResult result : possibleJoinNodes) {
                    StringBuilder builder = new StringBuilder();
                    TableNameExtractor extractor = new TableNameExtractor(lookup);
                    result.planNode.get().accept(extractor, builder);
                    if (TableNameExtractor.startsWith(builder.toString(), expectedPattern)) {
                        return result;
                    }
                }
                return resultComparator.min(possibleJoinNodes);
            }

            private List<JoinEnumerationResult> getPossibleJoinNodes(JoinNode joinNode, JoinDistributionType distributionType)
            {
                checkArgument(joinNode.getType() == INNER, "unexpected join node type: %s", joinNode.getType());

                if (joinNode.isCrossJoin()) {
                    return getPossibleJoinNodes(joinNode, REPLICATED);
                }

                switch (distributionType) {
                    case PARTITIONED:
                        return getPossibleJoinNodes(joinNode, PARTITIONED);
                    case BROADCAST:
                        return getPossibleJoinNodes(joinNode, REPLICATED);
                    case AUTOMATIC:
                        ImmutableList.Builder<JoinEnumerationResult> result = ImmutableList.builder();
                        result.addAll(getPossibleJoinNodes(joinNode, PARTITIONED));
                        if (canReplicate(joinNode, context)) {
                            result.addAll(getPossibleJoinNodes(joinNode, REPLICATED));
                        }
                        return result.build();
                    default:
                        throw new IllegalArgumentException("unexpected join distribution type: " + distributionType);
                }
            }

            private List<JoinEnumerationResult> getPossibleJoinNodes(JoinNode joinNode, DistributionType distributionType)
            {
                return ImmutableList.of(
                        createJoinEnumerationResult(joinNode.withDistributionType(distributionType)),
                        createJoinEnumerationResult(joinNode.flipChildren().withDistributionType(distributionType)));
            }

            private JoinEnumerationResult createJoinEnumerationResult(PlanNode planNode)
            {
                return JoinEnumerationResult.createJoinEnumerationResult(Optional.of(planNode), costProvider.getCost(planNode));
            }
        }

        /**
         * This class represents a set of inner joins that can be executed in any order.
         */
        @VisibleForTesting
        static class MultiJoinNode
        {
            // Use a linked hash set to ensure optimizer is deterministic
            private final LinkedHashSet<PlanNode> sources;
            private final Expression filter;
            private final List<Symbol> outputSymbols;

            public MultiJoinNode(LinkedHashSet<PlanNode> sources, Expression filter, List<Symbol> outputSymbols)
            {
                requireNonNull(sources, "sources is null");
                checkArgument(sources.size() > 1, "sources size is <= 1");
                requireNonNull(filter, "filter is null");
                requireNonNull(outputSymbols, "outputSymbols is null");

                this.sources = sources;
                this.filter = filter;
                this.outputSymbols = ImmutableList.copyOf(outputSymbols);

                List<Symbol> inputSymbols = sources.stream().flatMap(source -> source.getOutputSymbols().stream()).collect(toImmutableList());
                checkArgument(inputSymbols.containsAll(outputSymbols), "inputs do not contain all output symbols");
            }

            public Expression getFilter()
            {
                return filter;
            }

            public LinkedHashSet<PlanNode> getSources()
            {
                return sources;
            }

            public List<Symbol> getOutputSymbols()
            {
                return outputSymbols;
            }

            public static Builder builder()
            {
                return new Builder();
            }

            @Override
            public int hashCode()
            {
                return Objects.hash(sources, ImmutableSet.copyOf(extractConjuncts(filter)), outputSymbols);
            }

            @Override
            public boolean equals(Object obj)
            {
                if (!(obj instanceof MultiJoinNode)) {
                    return false;
                }

                MultiJoinNode other = (MultiJoinNode) obj;
                return this.sources.equals(other.sources)
                        && ImmutableSet.copyOf(extractConjuncts(this.filter)).equals(ImmutableSet.copyOf(extractConjuncts(other.filter)))
                        && this.outputSymbols.equals(other.outputSymbols);
            }

            static MultiJoinNode toMultiJoinNode(JoinNode joinNode, Lookup lookup, int joinLimit)
            {
                // the number of sources is the number of joins + 1
                return new JoinNodeFlattener(joinNode, lookup, joinLimit + 1).toMultiJoinNode();
            }

            private static class JoinNodeFlattener
            {
                private final LinkedHashSet<PlanNode> sources = new LinkedHashSet<>();
                private final List<Expression> filters = new ArrayList<>();
                private final List<Symbol> outputSymbols;
                private final Lookup lookup;

                JoinNodeFlattener(JoinNode node, Lookup lookup, int sourceLimit)
                {
                    requireNonNull(node, "node is null");
                    checkState(node.getType() == INNER, "join type must be INNER");
                    this.outputSymbols = node.getOutputSymbols();
                    this.lookup = requireNonNull(lookup, "lookup is null");
                    flattenNode(node, sourceLimit);
                }

                private void flattenNode(PlanNode node, int limit)
                {
                    PlanNode resolved = lookup.resolve(node);

                    // (limit - 2) because you need to account for adding left and right side
                    if (!(resolved instanceof JoinNode) || (sources.size() > (limit - 2))) {
                        sources.add(node);
                        return;
                    }

                    JoinNode joinNode = (JoinNode) resolved;
                    if (joinNode.getType() != INNER
                            || !isDeterministic(joinNode.getFilter().map(OriginalExpressionUtils::castToExpression).orElse(TRUE_LITERAL))
                            || joinNode.getDistributionType().isPresent()) {
                        sources.add(node);
                        return;
                    }

                    // we set the left limit to limit - 1 to account for the node on the right
                    flattenNode(joinNode.getLeft(), limit - 1);
                    flattenNode(joinNode.getRight(), limit);
                    joinNode.getCriteria().stream()
                            .map(JoinNodeUtils::toExpression)
                            .forEach(filters::add);
                    joinNode.getFilter().map(OriginalExpressionUtils::castToExpression).ifPresent(filters::add);
                }

                MultiJoinNode toMultiJoinNode()
                {
                    return new MultiJoinNode(sources, and(filters), outputSymbols);
                }
            }

            static class Builder
            {
                private List<PlanNode> sources;
                private Expression filter;
                private List<Symbol> outputSymbols;

                public Builder setSources(PlanNode... sources)
                {
                    this.sources = ImmutableList.copyOf(sources);
                    return this;
                }

                public Builder setFilter(Expression filter)
                {
                    this.filter = filter;
                    return this;
                }

                public Builder setOutputSymbols(Symbol... outputSymbols)
                {
                    this.outputSymbols = ImmutableList.copyOf(outputSymbols);
                    return this;
                }

                public MultiJoinNode build()
                {
                    return new MultiJoinNode(new LinkedHashSet<>(sources), filter, outputSymbols);
                }
            }
        }

        @VisibleForTesting
        static class JoinEnumerationResult
        {
            public static final JoinEnumerationResult UNKNOWN_COST_RESULT = new JoinEnumerationResult(Optional.empty(), PlanCostEstimate.unknown());
            public static final JoinEnumerationResult INFINITE_COST_RESULT = new JoinEnumerationResult(Optional.empty(), PlanCostEstimate.infinite());

            private final Optional<PlanNode> planNode;
            private final PlanCostEstimate cost;

            private JoinEnumerationResult(Optional<PlanNode> planNode, PlanCostEstimate cost)
            {
                this.planNode = requireNonNull(planNode, "planNode is null");
                this.cost = requireNonNull(cost, "cost is null");
                checkArgument((cost.hasUnknownComponents() || cost.equals(PlanCostEstimate.infinite())) && !planNode.isPresent()
                                || (!cost.hasUnknownComponents() || !cost.equals(PlanCostEstimate.infinite())) && planNode.isPresent(),
                        "planNode should be present if and only if cost is known");
            }

            public Optional<PlanNode> getPlanNode()
            {
                return planNode;
            }

            public PlanCostEstimate getCost()
            {
                return cost;
            }

            static JoinEnumerationResult createJoinEnumerationResult(Optional<PlanNode> planNode, PlanCostEstimate cost)
            {
                if (cost.hasUnknownComponents()) {
                    return UNKNOWN_COST_RESULT;
                }
                if (cost.equals(PlanCostEstimate.infinite())) {
                    return INFINITE_COST_RESULT;
                }
                return new JoinEnumerationResult(planNode, cost);
            }
        }

        private static class TableNameExtractor
                extends InternalPlanVisitor<Void, StringBuilder>
        {
            private final Lookup lookup;
            private String pattern = "";

            private TableNameExtractor(Lookup lookup)
            {
                this.lookup = lookup;
            }

            @Override
            public Void visitTableScan(TableScanNode node, StringBuilder context)
            {
                context.append(node.getTable().getConnectorHandle().getTableName().toString());
                return null;
            }

            @Override
            public Void visitPlan(PlanNode node, StringBuilder context)
            {
                node = lookup.resolve(node);
                for (PlanNode source : node.getSources()) {
                    lookup.resolve(source).accept(this, context);
                }
                return null;
            }

            @Override
            public Void visitJoin(JoinNode node, StringBuilder context)
            {
                PlanNode left = lookup.resolve(node.getLeft());
                PlanNode right = lookup.resolve(node.getRight());
                context.append('(');
                left.accept(this, context);
                context.append(',');
                right.accept(this, context);
                context.append(')');
                return null;
            }

            public static boolean startsWith(String actualPattern, String expectedPattern)
            {
                return expectedPattern.contains(actualPattern);
            }
        }
    }
}
