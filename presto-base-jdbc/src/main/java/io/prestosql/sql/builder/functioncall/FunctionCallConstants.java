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
package io.prestosql.sql.builder.functioncall;

public class FunctionCallConstants
{
    private FunctionCallConstants()
    {
    }

    /**
     * Remote catalog schema config splitter
     */
    public static final String REMOTE_CATALOGSCHEMAS_CONFIG_SPLITTER = "\\|";

    /**
     * Dot splitter
     */
    public static final String DOT_SPLITTER = "\\.";

    /**
     * Remote function catalog and schemas
     */
    public static final String REMOTE_FUNCTION_CATALOG_SCHEMA = "jdbc.pushdown.remotenamespace";

    /**
     * Catalog and schemas length
     */
    public static final int CATALOG_SCHEMA_LENGTH_COUNT = 2;

    /**
     * function catalog, schema and functio name length
     */
    public static final int CATALOG_SCHEMA_NAME_LENGTH = 3;
}
