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
import io.prestosql.spi.plan.Symbol;
import io.prestosql.spi.type.BigintType;
import io.prestosql.spi.type.DateType;
import io.prestosql.sql.planner.FunctionCallBuilder;
import io.prestosql.sql.planner.assertions.PlanMatchPattern;
import io.prestosql.sql.planner.iterative.rule.test.BaseRuleTest;
import io.prestosql.sql.planner.iterative.rule.test.PlanBuilder;
import io.prestosql.sql.relational.OriginalExpressionUtils;
import io.prestosql.sql.tree.FunctionCall;
import io.prestosql.sql.tree.LongLiteral;
import io.prestosql.sql.tree.QualifiedName;
import io.prestosql.sql.tree.SymbolReference;
import org.testng.annotations.Test;

import static io.prestosql.spi.type.VarcharType.VARCHAR;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.expression;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.filter;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.project;
import static io.prestosql.sql.planner.assertions.PlanMatchPattern.values;
import static io.prestosql.sql.planner.iterative.rule.test.PlanBuilder.assignment;

public class TestExpressionRewriteRuleSet
        extends BaseRuleTest
{
    private ExpressionRewriteRuleSet zeroRewriter = new ExpressionRewriteRuleSet(
            (expression, context) -> new LongLiteral("0"));

    @Test
    public void testProjectionExpressionRewrite()
    {
        tester().assertThat(zeroRewriter.projectExpressionRewrite())
                .on(p -> p.project(
                        assignment(p.symbol("y"), PlanBuilder.expression("x IS NOT NULL")),
                        p.values(p.symbol("x"))))
                .matches(
                        project(ImmutableMap.of("y", expression("0")), values("x")));
    }

    @Test
    public void testProjectionExpressionNotRewritten()
    {
        tester().assertThat(zeroRewriter.projectExpressionRewrite())
                .on(p -> p.project(
                        assignment(p.symbol("y"), PlanBuilder.expression("0")),
                        p.values(p.symbol("x"))))
                .doesNotFire();
    }

    @Test
    public void testAggregationExpressionRewrite()
    {
        ExpressionRewriteRuleSet functionCallRewriter = new ExpressionRewriteRuleSet((expression, context) -> new FunctionCallBuilder(tester().getMetadata())
                .setName(QualifiedName.of("count"))
                .addArgument(VARCHAR, new SymbolReference("y"))
                .build());
        tester().assertThat(functionCallRewriter.aggregationExpressionRewrite())
                .on(p -> p.aggregation(a -> a
                        .globalGrouping()
                        .addAggregation(
                                p.symbol("count_1", BigintType.BIGINT),
                                new FunctionCallBuilder(tester().getMetadata())
                                        .setName(QualifiedName.of("count"))
                                        .addArgument(VARCHAR, new SymbolReference("x"))
                                        .build(),
                                ImmutableList.of(BigintType.BIGINT))
                        .source(
                                p.values(p.symbol("x"), p.symbol("y")))))
                .matches(
                        PlanMatchPattern.aggregation(
                                ImmutableMap.of("count_1", aliases -> new FunctionCallBuilder(tester().getMetadata())
                                        .setName(QualifiedName.of("count"))
                                        .addArgument(VARCHAR, new SymbolReference("y"))
                                        .build()),
                                values("x", "y")));
    }

    @Test
    public void testAggregationExpressionNotRewritten()
    {
        FunctionCall nowCall = new FunctionCallBuilder(tester().getMetadata())
                .setName(QualifiedName.of("now"))
                .build();
        ExpressionRewriteRuleSet functionCallRewriter = new ExpressionRewriteRuleSet((expression, context) -> nowCall);

        tester().assertThat(functionCallRewriter.aggregationExpressionRewrite())
                .on(p -> p.aggregation(a -> a
                        .globalGrouping()
                        .addAggregation(
                                p.symbol("count_1", DateType.DATE),
                                nowCall,
                                ImmutableList.of())
                        .source(
                                p.values())))
                .doesNotFire();
    }

    @Test
    public void testFilterExpressionRewrite()
    {
        tester().assertThat(zeroRewriter.filterExpressionRewrite())
                .on(p -> p.filter(new LongLiteral("1"), p.values()))
                .matches(
                        filter("0", values()));
    }

    @Test
    public void testFilterExpressionNotRewritten()
    {
        tester().assertThat(zeroRewriter.filterExpressionRewrite())
                .on(p -> p.filter(new LongLiteral("0"), p.values()))
                .doesNotFire();
    }

    @Test
    public void testValueExpressionRewrite()
    {
        tester().assertThat(zeroRewriter.valuesExpressionRewrite())
                .on(p -> p.values(
                        ImmutableList.<Symbol>of(p.symbol("a")),
                        ImmutableList.of((ImmutableList.of(OriginalExpressionUtils.castToRowExpression(PlanBuilder.expression("1")))))))
                .matches(
                        values(ImmutableList.of("a"), ImmutableList.of(ImmutableList.of(new LongLiteral("0")))));
    }

    @Test
    public void testValueExpressionNotRewritten()
    {
        tester().assertThat(zeroRewriter.valuesExpressionRewrite())
                .on(p -> p.values(
                        ImmutableList.<Symbol>of(p.symbol("a")),
                        ImmutableList.of((ImmutableList.of(OriginalExpressionUtils.castToRowExpression(PlanBuilder.expression("0")))))))
                .doesNotFire();
    }
}
