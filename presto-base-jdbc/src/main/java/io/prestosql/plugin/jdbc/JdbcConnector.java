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
package io.prestosql.plugin.jdbc;

import com.google.common.collect.ImmutableSet;
import io.airlift.bootstrap.LifeCycleManager;
import io.airlift.log.Logger;
import io.prestosql.plugin.jdbc.optimization.JdbcPlanOptimizer;
import io.prestosql.plugin.jdbc.optimization.JdbcPlanOptimizerProvider;
import io.prestosql.spi.ConnectorPlanOptimizer;
import io.prestosql.spi.connector.CachedConnectorMetadata;
import io.prestosql.spi.connector.Connector;
import io.prestosql.spi.connector.ConnectorAccessControl;
import io.prestosql.spi.connector.ConnectorCapabilities;
import io.prestosql.spi.connector.ConnectorMetadata;
import io.prestosql.spi.connector.ConnectorPageSinkProvider;
import io.prestosql.spi.connector.ConnectorPlanOptimizerProvider;
import io.prestosql.spi.connector.ConnectorRecordSetProvider;
import io.prestosql.spi.connector.ConnectorSplitManager;
import io.prestosql.spi.connector.ConnectorTransactionHandle;
import io.prestosql.spi.function.ExternalFunctionHub;
import io.prestosql.spi.function.FunctionMetadataManager;
import io.prestosql.spi.function.StandardFunctionResolution;
import io.prestosql.spi.procedure.Procedure;
import io.prestosql.spi.relation.RowExpressionService;
import io.prestosql.spi.transaction.IsolationLevel;

import javax.inject.Inject;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.Sets.immutableEnumSet;
import static io.prestosql.spi.connector.ConnectorCapabilities.NOT_NULL_COLUMN_CONSTRAINT;
import static io.prestosql.spi.transaction.IsolationLevel.READ_COMMITTED;
import static io.prestosql.spi.transaction.IsolationLevel.checkConnectorSupports;
import static java.util.Objects.requireNonNull;

public class JdbcConnector
        implements Connector
{
    private static final Logger log = Logger.get(JdbcConnector.class);

    private final LifeCycleManager lifeCycleManager;
    private final JdbcMetadataFactory jdbcMetadataFactory;
    private final JdbcSplitManager jdbcSplitManager;
    private final JdbcRecordSetProvider jdbcRecordSetProvider;
    private final JdbcPageSinkProvider jdbcPageSinkProvider;
    private final Optional<ConnectorAccessControl> accessControl;
    private final Set<Procedure> procedures;
    private final JdbcMetadataConfig config;
    private final ConnectorPlanOptimizer planOptimizer;
    private final FunctionMetadataManager functionManager;
    private final StandardFunctionResolution functionResolution;
    private final RowExpressionService rowExpressionService;

    private final ConcurrentMap<ConnectorTransactionHandle, JdbcMetadata> transactions = new ConcurrentHashMap<>();
    private final JdbcClient jdbcClient;

    @Inject
    public JdbcConnector(
            LifeCycleManager lifeCycleManager,
            JdbcMetadataFactory jdbcMetadataFactory,
            JdbcSplitManager jdbcSplitManager,
            JdbcRecordSetProvider jdbcRecordSetProvider,
            JdbcPageSinkProvider jdbcPageSinkProvider,
            Optional<ConnectorAccessControl> accessControl,
            Set<Procedure> procedures,
            FunctionMetadataManager functionManager,
            StandardFunctionResolution functionResolution,
            RowExpressionService rowExpressionService,
            JdbcMetadataConfig config,
            JdbcPlanOptimizer planOptimizer,
            JdbcClient jdbcClient)
    {
        this.lifeCycleManager = requireNonNull(lifeCycleManager, "lifeCycleManager is null");
        this.jdbcMetadataFactory = requireNonNull(jdbcMetadataFactory, "jdbcMetadataFactory is null");
        this.jdbcSplitManager = requireNonNull(jdbcSplitManager, "jdbcSplitManager is null");
        this.jdbcRecordSetProvider = requireNonNull(jdbcRecordSetProvider, "jdbcRecordSetProvider is null");
        this.jdbcPageSinkProvider = requireNonNull(jdbcPageSinkProvider, "jdbcPageSinkProvider is null");
        this.accessControl = requireNonNull(accessControl, "accessControl is null");
        this.procedures = ImmutableSet.copyOf(requireNonNull(procedures, "procedures is null"));
        this.config = config;
        this.planOptimizer = planOptimizer;
        this.functionManager = requireNonNull(functionManager, "functionManager is null");
        this.functionResolution = requireNonNull(functionResolution, "functionResolution is null");
        this.rowExpressionService = requireNonNull(rowExpressionService, "rowExpressionService is null");
        this.jdbcClient = requireNonNull(jdbcClient, "jdbcClient is null");
    }

    @Override
    public Optional<ExternalFunctionHub> getExternalFunctionHub()
    {
        return this.jdbcClient.getExternalFunctionHub();
    }

    @Override
    public ConnectorPlanOptimizerProvider getConnectorPlanOptimizerProvider()
    {
        return new JdbcPlanOptimizerProvider(planOptimizer);
    }

    @Override
    public boolean isSingleStatementWritesOnly()
    {
        return true;
    }

    @Override
    public ConnectorTransactionHandle beginTransaction(IsolationLevel isolationLevel, boolean readOnly)
    {
        checkConnectorSupports(READ_COMMITTED, isolationLevel);
        JdbcTransactionHandle transaction = new JdbcTransactionHandle();
        transactions.put(transaction, jdbcMetadataFactory.create());
        return transaction;
    }

    @Override
    public ConnectorMetadata getMetadata(ConnectorTransactionHandle transaction)
    {
        JdbcMetadata metadata = transactions.get(transaction);
        checkArgument(metadata != null, "no such transaction: %s", transaction);
        if (config.isMetadataCacheEnabled()) {
            return new CachedConnectorMetadata(metadata, config.getMetadataCacheTtl(), config.getMetadataCacheMaximumSize());
        }
        else {
            return metadata;
        }
    }

    @Override
    public void commit(ConnectorTransactionHandle transaction)
    {
        checkArgument(transactions.remove(transaction) != null, "no such transaction: %s", transaction);
    }

    @Override
    public void rollback(ConnectorTransactionHandle transaction)
    {
        JdbcMetadata metadata = transactions.remove(transaction);
        checkArgument(metadata != null, "no such transaction: %s", transaction);
        metadata.rollback();
    }

    @Override
    public ConnectorSplitManager getSplitManager()
    {
        return jdbcSplitManager;
    }

    @Override
    public ConnectorRecordSetProvider getRecordSetProvider()
    {
        return jdbcRecordSetProvider;
    }

    @Override
    public ConnectorPageSinkProvider getPageSinkProvider()
    {
        return jdbcPageSinkProvider;
    }

    @Override
    public ConnectorAccessControl getAccessControl()
    {
        return accessControl.orElseThrow(UnsupportedOperationException::new);
    }

    @Override
    public Set<Procedure> getProcedures()
    {
        return procedures;
    }

    @Override
    public final void shutdown()
    {
        try {
            lifeCycleManager.stop();
        }
        catch (Exception e) {
            log.error(e, "Error shutting down connector");
        }
    }

    @Override
    public Set<ConnectorCapabilities> getCapabilities()
    {
        return immutableEnumSet(NOT_NULL_COLUMN_CONSTRAINT);
    }

    /**
     * Hetu DC Connector returns the list of catalogs from the data center. The list should not include catalogs from any remote data centers connected to this data center.
     * For example, even if the dc1 is connected to dc2, calling this method on dc1 should return only hive, mysql, etc (not dc2.hive or dc2.mysql)
     *
     * @param user the username to access the data center
     * @param extraCredentials the content of data center properties file (Eg: dc.properties)
     * @return the list of first class catalogs of the data center
     */
    @Override
    public Collection<String> getCatalogs(String user, Map<String, String> extraCredentials)
    {
        return this.jdbcMetadataFactory.getJdbcClient().getCatalogNames(new JdbcIdentity(user, extraCredentials));
    }
}
