/*
 * Copyright (C) 2018-2020. Huawei Technologies Co., Ltd. All rights reserved.
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
package io.hetu.core.plugin.greenplum.optimization;

import io.prestosql.plugin.jdbc.optimization.BaseJdbcQueryGenerator;
import io.prestosql.plugin.jdbc.optimization.JdbcPushDownParameter;
import io.prestosql.spi.function.FunctionMetadataManager;
import io.prestosql.spi.function.StandardFunctionResolution;
import io.prestosql.spi.relation.DeterminismEvaluator;
import io.prestosql.spi.relation.RowExpressionService;

public class GreenPlumQueryGenerator
        extends BaseJdbcQueryGenerator
{
    public GreenPlumQueryGenerator(DeterminismEvaluator determinismEvaluator,
            RowExpressionService rowExpressionService,
            FunctionMetadataManager functionManager,
            StandardFunctionResolution functionResolution,
            JdbcPushDownParameter pushDownParameter)
    {
        super(
                pushDownParameter,
                new GreenPlumRowExpressionConverter(determinismEvaluator, rowExpressionService, functionManager, functionResolution),
                new GreenPlumSqlStatementWriter(pushDownParameter));
    }
}
