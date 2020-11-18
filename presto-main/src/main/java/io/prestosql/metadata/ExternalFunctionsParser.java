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
package io.prestosql.metadata;

import com.google.common.collect.ImmutableMap;
import io.prestosql.spi.connector.CatalogSchemaName;
import io.prestosql.spi.connector.QualifiedObjectName;
import io.prestosql.spi.function.ExternalFunctionInfo;
import io.prestosql.spi.function.Parameter;
import io.prestosql.spi.function.RoutineCharacteristics;
import io.prestosql.spi.function.SqlInvokedFunction;
import io.prestosql.spi.type.TypeSignature;

import java.util.List;
import java.util.Optional;
import java.util.Random;

import static com.google.common.base.Preconditions.checkState;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static io.prestosql.spi.type.TypeSignature.parseTypeSignature;

public class ExternalFunctionsParser
{
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz";
    private static final String EXTERNAL_FUNCTION_BODY = "EXTERNAL";

    public static Optional<SqlInvokedFunction> parseExternalFunction(ExternalFunctionInfo externalFunctionInfo, CatalogSchemaName catalogSchemaName, RoutineCharacteristics.Language language)
    {
        Optional<String> functionName = externalFunctionInfo.getFunctionName();
        Optional<String> description = externalFunctionInfo.getDescription();
        List<String> inputArgs = externalFunctionInfo.getInputArgs();
        Optional<String> returnType = externalFunctionInfo.getReturnType();
        boolean deterministic = externalFunctionInfo.isDeterministic();
        boolean calledOnNullInput = externalFunctionInfo.isCalledOnNullInput();
        if (functionName.isPresent() && returnType.isPresent()) {
            QualifiedObjectName qualifiedObjectName = new QualifiedObjectName(catalogSchemaName.getCatalogName(), catalogSchemaName.getSchemaName(), functionName.get());
            List<Parameter> parameters = inputArgs.stream()
                    .map(str -> new Parameter(getRandomString((inputArgs.size() / ALPHABET.length() + 1), ALPHABET), parseTypeSignature(str)))
                    .collect(toImmutableList());
            TypeSignature reType = parseTypeSignature(returnType.get());
            String deter = deterministic ? "DETERMINISTIC" : "NOT_DETERMINISTIC";
            String nullCallClause = calledOnNullInput ? "CALLED_ON_NULL_INPUT" : "RETURNS_NULL_ON_NULL_INPUT";
            RoutineCharacteristics routineCharacteristics = RoutineCharacteristics.builder()
                    .setLanguage(new RoutineCharacteristics.Language(language.getLanguage()))
                    .setDeterminism(RoutineCharacteristics.Determinism.valueOf(deter))
                    .setNullCallClause(RoutineCharacteristics.NullCallClause.valueOf(nullCallClause))
                    .build();
            SqlInvokedFunction sqlInvokedFunction = new SqlInvokedFunction(
                    qualifiedObjectName,
                    parameters,
                    reType,
                    description.orElse(""),
                    routineCharacteristics,
                    EXTERNAL_FUNCTION_BODY,
                    ImmutableMap.of(),
                    Optional.empty());
            return Optional.of(sqlInvokedFunction);
        }
        return Optional.empty();
    }

    private static String getRandomString(int length, String base)
    {
        checkState(base.length() >= length, "the base should be longer than the str length needed");
        Random random = new Random();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < length; i++) {
            int number = random.nextInt(base.length());
            sb.append(base.charAt(number));
        }
        return sb.toString();
    }

    private ExternalFunctionsParser()
    {
    }
}
