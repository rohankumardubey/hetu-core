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

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import io.prestosql.matching.Captures;
import io.prestosql.matching.Pattern;
import io.prestosql.metadata.Metadata;
import io.prestosql.spi.connector.QualifiedObjectName;
import io.prestosql.spi.plan.AggregationNode;
import io.prestosql.spi.plan.AggregationNode.Aggregation;
import io.prestosql.spi.plan.Assignments;
import io.prestosql.spi.plan.ProjectNode;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.relation.CallExpression;
import io.prestosql.spi.relation.RowExpression;
import io.prestosql.spi.type.Type;
import io.prestosql.spi.type.TypeSignature;
import io.prestosql.sql.planner.FunctionCallBuilder;
import io.prestosql.sql.planner.iterative.Rule;
import io.prestosql.sql.planner.plan.AssignmentUtils;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.LongLiteral;
import io.prestosql.sql.tree.QualifiedName;

import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.prestosql.SystemSessionProperties.getHashPartitionCount;
import static io.prestosql.spi.connector.CatalogSchemaName.DEFAULT_NAMESPACE;
import static io.prestosql.spi.type.IntegerType.INTEGER;
import static io.prestosql.spi.type.TypeSignature.parseTypeSignature;
import static io.prestosql.sql.analyzer.TypeSignatureProvider.fromTypes;
import static io.prestosql.sql.planner.SymbolUtils.toSymbolReference;
import static io.prestosql.sql.planner.plan.Patterns.aggregation;
import static io.prestosql.sql.relational.OriginalExpressionUtils.castToExpression;
import static io.prestosql.sql.relational.OriginalExpressionUtils.castToRowExpression;
import static java.util.Objects.requireNonNull;

/**
 * Re-writes spatial_partitioning(geometry) aggregations into spatial_partitioning(envelope, partition_count)
 * on top of ST_Envelope(geometry) projection, e.g.
 * <pre>
 * - Aggregation: spatial_partitioning(geometry)
 *    - source
 * </pre>
 * becomes
 * <pre>
 * - Aggregation: spatial_partitioning(envelope, partition_count)
 *    - Project: envelope := ST_Envelope(geometry)
 *        - source
 * </pre>
 * , where partition_count is the value of session property hash_partition_count
 */
public class RewriteSpatialPartitioningAggregation
        implements Rule<AggregationNode>
{
    private static final TypeSignature GEOMETRY_TYPE_SIGNATURE = parseTypeSignature("Geometry");
    private static final QualifiedObjectName NAME = QualifiedObjectName.valueOf(DEFAULT_NAMESPACE, "spatial_partitioning");
    private final Pattern<AggregationNode> pattern = aggregation().matching(this::hasSpatialPartitioningAggregation);

    private final Metadata metadata;

    public RewriteSpatialPartitioningAggregation(Metadata metadata)
    {
        this.metadata = requireNonNull(metadata, "metadata is null");
    }

    private boolean hasSpatialPartitioningAggregation(AggregationNode aggregationNode)
    {
        return aggregationNode.getAggregations().values().stream()
                .anyMatch(aggregation -> metadata.getFunctionAndTypeManager().getFunctionMetadata(aggregation.getFunctionHandle()).getName().equals(NAME) && aggregation.getArguments().size() == 1);
    }

    @Override
    public Pattern<AggregationNode> getPattern()
    {
        return pattern;
    }

    @Override
    public Result apply(AggregationNode node, Captures captures, Context context)
    {
        ImmutableMap.Builder<Symbol, Aggregation> aggregations = ImmutableMap.builder();
        Symbol partitionCountSymbol = context.getSymbolAllocator().newSymbol("partition_count", INTEGER);
        ImmutableMap.Builder<Symbol, RowExpression> envelopeAssignments = ImmutableMap.builder();
        for (Map.Entry<Symbol, Aggregation> entry : node.getAggregations().entrySet()) {
            Aggregation aggregation = entry.getValue();
            QualifiedObjectName name = metadata.getFunctionAndTypeManager().getFunctionMetadata(aggregation.getFunctionHandle()).getName();
            Type geometryType = metadata.getType(GEOMETRY_TYPE_SIGNATURE);
            if (name.equals(NAME) && aggregation.getArguments().size() == 1) {
                RowExpression geometry = getOnlyElement(aggregation.getArguments().stream().collect(toImmutableList()));
                Symbol envelopeSymbol = context.getSymbolAllocator().newSymbol("envelope", metadata.getType(GEOMETRY_TYPE_SIGNATURE));
                if (isFunctionNameMatch(geometry, "ST_Envelope")) {
                    envelopeAssignments.put(envelopeSymbol, geometry);
                }
                else {
                    envelopeAssignments.put(envelopeSymbol, castToRowExpression(new FunctionCallBuilder(metadata)
                            .setName(QualifiedName.of("ST_Envelope"))
                            .addArgument(GEOMETRY_TYPE_SIGNATURE, castToExpression(geometry))
                            .build()));
                }
                aggregations.put(entry.getKey(),
                        new Aggregation(
                                new CallExpression(
                                        name.getObjectName(),
                                        metadata.getFunctionAndTypeManager().lookupFunction(NAME.getObjectName(), fromTypes(geometryType, INTEGER)),
                                        context.getSymbolAllocator().getTypes().get(entry.getKey()),
                                        ImmutableList.of(
                                                castToRowExpression(toSymbolReference(envelopeSymbol)),
                                                castToRowExpression(toSymbolReference(partitionCountSymbol))),
                                        Optional.empty()),
                                ImmutableList.of(castToRowExpression(toSymbolReference(envelopeSymbol)), castToRowExpression(toSymbolReference(partitionCountSymbol))),
                                false,
                                Optional.empty(),
                                Optional.empty(),
                                aggregation.getMask()));
            }
            else {
                aggregations.put(entry);
            }
        }

        return Result.ofPlanNode(
                new AggregationNode(
                        node.getId(),
                        new ProjectNode(
                                context.getIdAllocator().getNextId(),
                                node.getSource(),
                                Assignments.builder()
                                        .putAll(AssignmentUtils.identityAsSymbolReferences(node.getSource().getOutputSymbols()))
                                        .put(partitionCountSymbol, castToRowExpression(new LongLiteral(Integer.toString(getHashPartitionCount(context.getSession())))))
                                        .putAll(envelopeAssignments.build())
                                        .build()),
                        aggregations.build(),
                        node.getGroupingSets(),
                        node.getPreGroupedSymbols(),
                        node.getStep(),
                        node.getHashSymbol(),
                        node.getGroupIdSymbol()));
    }

    private static boolean isFunctionNameMatch(RowExpression rowExpression, String expectedName)
    {
        if (castToExpression(rowExpression) instanceof FunctionCall) {
            return ((FunctionCall) castToExpression(rowExpression)).getName().toString().equalsIgnoreCase(expectedName);
        }
        return false;
    }
}
