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
package io.prestosql.split;

import io.prestosql.Session;
import io.prestosql.connector.CatalogName;
import io.prestosql.metadata.Split;
import io.prestosql.metadata.TableHandle;
import io.prestosql.spi.connector.ColumnHandle;
import io.prestosql.spi.connector.ConnectorPageSource;
import io.prestosql.spi.connector.ConnectorPageSourceProvider;
import io.prestosql.spi.dynamicfilter.DynamicFilterSupplier;

import java.util.List;
import java.util.Optional;

public interface PageSourceProvider
{
    ConnectorPageSource createPageSource(Session session,
                                         Split split,
                                         TableHandle table,
                                         List<ColumnHandle> columns,
                                         Optional<DynamicFilterSupplier> dynamicFilter);

    default ConnectorPageSourceProvider getPageSourceProvider(CatalogName catalogName)
    {
        throw new UnsupportedOperationException("Not implemented");
    }
}
