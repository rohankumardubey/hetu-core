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
package io.prestosql.sql.planner.plan;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.metadata.Metadata;
import io.prestosql.operator.aggregation.InternalAggregationFunction;
import io.prestosql.spi.function.FunctionHandle;
import io.prestosql.spi.plan.AggregationNode.Aggregation;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.relation.CallExpression;
import io.prestosql.sql.planner.PlanSymbolAllocator;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static io.prestosql.sql.planner.SymbolUtils.toSymbolReference;
import static io.prestosql.sql.relational.OriginalExpressionUtils.castToRowExpression;
import static java.util.Objects.requireNonNull;

public class StatisticAggregations
{
    private final Map<Symbol, Aggregation> aggregations;
    private final List<Symbol> groupingSymbols;

    @JsonCreator
    public StatisticAggregations(
            @JsonProperty("aggregations") Map<Symbol, Aggregation> aggregations,
            @JsonProperty("groupingSymbols") List<Symbol> groupingSymbols)
    {
        this.aggregations = ImmutableMap.copyOf(requireNonNull(aggregations, "aggregations is null"));
        this.groupingSymbols = ImmutableList.copyOf(requireNonNull(groupingSymbols, "groupingSymbols is null"));
    }

    @JsonProperty
    public Map<Symbol, Aggregation> getAggregations()
    {
        return aggregations;
    }

    @JsonProperty
    public List<Symbol> getGroupingSymbols()
    {
        return groupingSymbols;
    }

    public Parts createPartialAggregations(PlanSymbolAllocator planSymbolAllocator, Metadata metadata)
    {
        ImmutableMap.Builder<Symbol, Aggregation> partialAggregation = ImmutableMap.builder();
        ImmutableMap.Builder<Symbol, Aggregation> finalAggregation = ImmutableMap.builder();
        ImmutableMap.Builder<Symbol, Symbol> mappings = ImmutableMap.builder();
        for (Map.Entry<Symbol, Aggregation> entry : aggregations.entrySet()) {
            Aggregation originalAggregation = entry.getValue();
            FunctionHandle functionHandle = originalAggregation.getFunctionHandle();
            InternalAggregationFunction function = metadata.getFunctionAndTypeManager().getAggregateFunctionImplementation(functionHandle);
            Symbol partialSymbol = planSymbolAllocator.newSymbol(originalAggregation.getFunctionCall().getDisplayName(), function.getIntermediateType());
            mappings.put(entry.getKey(), partialSymbol);
            partialAggregation.put(partialSymbol, new Aggregation(
                    new CallExpression(
                            originalAggregation.getFunctionCall().getDisplayName(),
                            functionHandle,
                            function.getIntermediateType(),
                            originalAggregation.getArguments(),
                            Optional.empty()),
                    originalAggregation.getArguments(),
                    originalAggregation.isDistinct(),
                    originalAggregation.getFilter(),
                    originalAggregation.getOrderingScheme(),
                    originalAggregation.getMask()));
            finalAggregation.put(entry.getKey(),
                    new Aggregation(
                            new CallExpression(
                                    originalAggregation.getFunctionCall().getDisplayName(),
                                    functionHandle,
                                    function.getFinalType(),
                                    ImmutableList.of(castToRowExpression(toSymbolReference(partialSymbol))),
                                    Optional.empty()),
                            ImmutableList.of(castToRowExpression(toSymbolReference(partialSymbol))),
                            false,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
        }
        groupingSymbols.forEach(symbol -> mappings.put(symbol, symbol));
        return new Parts(
                new StatisticAggregations(partialAggregation.build(), groupingSymbols),
                new StatisticAggregations(finalAggregation.build(), groupingSymbols),
                mappings.build());
    }

    public static class Parts
    {
        private final StatisticAggregations partialAggregation;
        private final StatisticAggregations finalAggregation;
        private final Map<Symbol, Symbol> mappings;

        public Parts(StatisticAggregations partialAggregation, StatisticAggregations finalAggregation, Map<Symbol, Symbol> mappings)
        {
            this.partialAggregation = requireNonNull(partialAggregation, "partialAggregation is null");
            this.finalAggregation = requireNonNull(finalAggregation, "finalAggregation is null");
            this.mappings = ImmutableMap.copyOf(requireNonNull(mappings, "mappings is null"));
        }

        public StatisticAggregations getPartialAggregation()
        {
            return partialAggregation;
        }

        public StatisticAggregations getFinalAggregation()
        {
            return finalAggregation;
        }

        public Map<Symbol, Symbol> getMappings()
        {
            return mappings;
        }
    }
}
