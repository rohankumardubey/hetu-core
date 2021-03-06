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
import io.prestosql.spi.plan.JoinNode;
import io.prestosql.spi.plan.Symbol;
import io.prestosql.sql.planner.iterative.Rule;
import io.prestosql.sql.planner.plan.LateralJoinNode;

import java.util.Optional;

import static io.prestosql.matching.Pattern.empty;
import static io.prestosql.sql.planner.plan.Patterns.LateralJoin.correlation;
import static io.prestosql.sql.planner.plan.Patterns.lateralJoin;

public class TransformUncorrelatedLateralToJoin
        implements Rule<LateralJoinNode>
{
    private static final Pattern<LateralJoinNode> PATTERN = lateralJoin()
            .with(empty(correlation()));

    @Override
    public Pattern<LateralJoinNode> getPattern()
    {
        return PATTERN;
    }

    @Override
    public Result apply(LateralJoinNode lateralJoinNode, Captures captures, Context context)
    {
        return Result.ofPlanNode(new JoinNode(
                context.getIdAllocator().getNextId(),
                JoinNode.Type.INNER,
                lateralJoinNode.getInput(),
                lateralJoinNode.getSubquery(),
                ImmutableList.of(),
                ImmutableList.<Symbol>builder()
                        .addAll(lateralJoinNode.getInput().getOutputSymbols())
                        .addAll(lateralJoinNode.getSubquery().getOutputSymbols())
                        .build(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ImmutableMap.of()));
    }
}
