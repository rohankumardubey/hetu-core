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
package io.prestosql.cost;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import io.prestosql.Session;
import io.prestosql.matching.Pattern;
import io.prestosql.matching.pattern.TypeOfPattern;
import io.prestosql.spi.plan.PlanNode;
import io.prestosql.sql.planner.TypeProvider;
import io.prestosql.sql.planner.iterative.Lookup;

import java.lang.reflect.Modifier;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.Multimaps.toMultimap;

public class ComposableStatsCalculator
        implements StatsCalculator
{
    private final ListMultimap<Class<?>, Rule<?>> rulesByRootType;

    public ComposableStatsCalculator(List<Rule<?>> rules)
    {
        this.rulesByRootType = rules.stream()
                .peek(rule -> {
                    checkArgument(rule.getPattern() instanceof TypeOfPattern, "Rule pattern must be TypeOfPattern");
                    Class<?> expectedClass = ((TypeOfPattern<?>) rule.getPattern()).expectedClass();
                    checkArgument(!expectedClass.isInterface() && !Modifier.isAbstract(expectedClass.getModifiers()), "Rule must be registered on a concrete class");
                })
                .collect(toMultimap(
                        rule -> ((TypeOfPattern<?>) rule.getPattern()).expectedClass(),
                        rule -> rule,
                        ArrayListMultimap::create));
    }

    private Stream<Rule<?>> getCandidates(PlanNode node)
    {
        for (Class<?> superclass = node.getClass().getSuperclass(); superclass != null; superclass = superclass.getSuperclass()) {
            // This is important because rule ordering, given in the constructor, is significant.
            // We can't check this fully in the constructor, since abstract class may lack `abstract` modifier.
            checkState(rulesByRootType.get(superclass).isEmpty(), "Cannot maintain rule order because there is rule registered for %s", superclass);
        }
        return rulesByRootType.get(node.getClass()).stream();
    }

    @Override
    public PlanNodeStatsEstimate calculateStats(PlanNode node, StatsProvider sourceStats, Lookup lookup, Session session, TypeProvider types)
    {
        Iterator<Rule<?>> ruleIterator = getCandidates(node).iterator();
        while (ruleIterator.hasNext()) {
            Rule<?> rule = ruleIterator.next();
            Optional<PlanNodeStatsEstimate> calculatedStats = calculateStats(rule, node, sourceStats, lookup, session, types);
            if (calculatedStats.isPresent()) {
                return calculatedStats.get();
            }
        }
        return PlanNodeStatsEstimate.unknown();
    }

    private static <T extends PlanNode> Optional<PlanNodeStatsEstimate> calculateStats(Rule<T> rule, PlanNode node, StatsProvider sourceStats, Lookup lookup, Session session, TypeProvider types)
    {
        return rule.calculate((T) node, sourceStats, lookup, session, types);
    }

    public interface Rule<T extends PlanNode>
    {
        Pattern<T> getPattern();

        Optional<PlanNodeStatsEstimate> calculate(T node, StatsProvider sourceStats, Lookup lookup, Session session, TypeProvider types);
    }
}
