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
package io.prestosql.plugin.hive;

import io.airlift.concurrent.BoundedExecutor;
import io.airlift.json.JsonCodec;
import io.airlift.log.Logger;
import io.airlift.units.Duration;
import io.prestosql.plugin.hive.metastore.CachingHiveMetastore;
import io.prestosql.plugin.hive.metastore.HiveMetastore;
import io.prestosql.plugin.hive.metastore.SemiTransactionalHiveMetastore;
import io.prestosql.plugin.hive.security.AccessControlMetadataFactory;
import io.prestosql.plugin.hive.statistics.MetastoreHiveStatisticsProvider;
import io.prestosql.spi.type.TypeManager;
import org.joda.time.DateTimeZone;

import javax.inject.Inject;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

public class HiveMetadataFactory
        implements Supplier<TransactionalMetadata>
{
    private static final Logger log = Logger.get(HiveMetadataFactory.class);

    private final boolean allowCorruptWritesForTesting;
    private final boolean skipDeletionForAlter;
    private final boolean skipTargetCleanupOnRollback;
    private final boolean writesToNonManagedTablesEnabled;
    private final boolean createsOfNonManagedTablesEnabled;
    private final boolean tableCreatesWithLocationAllowed;
    private final long perTransactionCacheMaximumSize;
    private final HiveMetastore metastore;
    private final HdfsEnvironment hdfsEnvironment;
    private final HivePartitionManager partitionManager;
    private final DateTimeZone timeZone;
    private final TypeManager typeManager;
    private final LocationService locationService;
    private final JsonCodec<PartitionUpdate> partitionUpdateCodec;
    private final BoundedExecutor renameExecution;
    private final ScheduledExecutorService hiveVacuumService;
    private final TypeTranslator typeTranslator;
    private final String prestoVersion;
    private final AccessControlMetadataFactory accessControlMetadataFactory;
    private final Optional<Duration> hiveTransactionHeartbeatInterval;
    private final ScheduledExecutorService heartbeatService;
    private final ScheduledExecutorService hiveMetastoreClientService;
    private final Duration vacuumCleanupRecheckInterval;
    private final int vacuumDeltaNumThreshold;
    private final double vacuumDeltaPercentThreshold;
    private final boolean autoVacuumEnabled;
    private Optional<Duration> vacuumCollectorInterval;

    @Inject
    @SuppressWarnings("deprecation")
    public HiveMetadataFactory(
            HiveConfig hiveConfig,
            HiveMetastore metastore,
            HdfsEnvironment hdfsEnvironment,
            HivePartitionManager partitionManager,
            @ForHive ExecutorService executorService,
            @ForHiveVacuum ScheduledExecutorService hiveVacuumService,
            @ForHiveMetastore ScheduledExecutorService hiveMetastoreClientService,
            @ForHiveTransactionHeartbeats ScheduledExecutorService heartbeatService,
            TypeManager typeManager,
            LocationService locationService,
            JsonCodec<PartitionUpdate> partitionUpdateCodec,
            TypeTranslator typeTranslator,
            NodeVersion nodeVersion,
            AccessControlMetadataFactory accessControlMetadataFactory)
    {
        this(
                metastore,
                hdfsEnvironment,
                partitionManager,
                hiveConfig.getDateTimeZone(),
                hiveConfig.getMaxConcurrentFileRenames(),
                hiveConfig.getAllowCorruptWritesForTesting(),
                hiveConfig.isSkipDeletionForAlter(),
                hiveConfig.isSkipTargetCleanupOnRollback(),
                hiveConfig.getWritesToNonManagedTablesEnabled(),
                hiveConfig.getCreatesOfNonManagedTablesEnabled(),
                hiveConfig.getTableCreatesWithLocationAllowed(),
                hiveConfig.getPerTransactionMetastoreCacheMaximumSize(),
                hiveConfig.getHiveTransactionHeartbeatInterval(),
                hiveConfig.getVacuumCleanupRecheckInterval(),
                typeManager,
                locationService,
                partitionUpdateCodec,
                executorService,
                hiveVacuumService,
                heartbeatService,
                hiveMetastoreClientService,
                typeTranslator,
                nodeVersion.toString(),
                accessControlMetadataFactory,
                hiveConfig.getVacuumDeltaNumThreshold(),
                hiveConfig.getVacuumDeltaPercentThreshold(),
                hiveConfig.getAutoVacuumEnabled(),
                hiveConfig.getVacuumCollectorInterval());
    }

    public HiveMetadataFactory(
            HiveMetastore metastore,
            HdfsEnvironment hdfsEnvironment,
            HivePartitionManager partitionManager,
            DateTimeZone timeZone,
            int maxConcurrentFileRenames,
            boolean allowCorruptWritesForTesting,
            boolean skipDeletionForAlter,
            boolean skipTargetCleanupOnRollback,
            boolean writesToNonManagedTablesEnabled,
            boolean createsOfNonManagedTablesEnabled,
            boolean tableCreatesWithLocationAllowed,
            long perTransactionCacheMaximumSize,
            Optional<Duration> hiveTransactionHeartbeatInterval,
            Duration vacuumCleanupRecheckInterval,
            TypeManager typeManager,
            LocationService locationService,
            JsonCodec<PartitionUpdate> partitionUpdateCodec,
            ExecutorService executorService,
            ScheduledExecutorService hiveVacuumService,
            ScheduledExecutorService heartbeatService,
            ScheduledExecutorService hiveMetastoreClientService,
            TypeTranslator typeTranslator,
            String prestoVersion,
            AccessControlMetadataFactory accessControlMetadataFactory,
            int vacuumDeltaNumThreshold,
            double vacuumDeltaPercentThreshold,
            boolean autoVacuumEnabled,
            Optional<Duration> vacuumCollectorInterval)
    {
        this.allowCorruptWritesForTesting = allowCorruptWritesForTesting;
        this.skipDeletionForAlter = skipDeletionForAlter;
        this.skipTargetCleanupOnRollback = skipTargetCleanupOnRollback;
        this.writesToNonManagedTablesEnabled = writesToNonManagedTablesEnabled;
        this.createsOfNonManagedTablesEnabled = createsOfNonManagedTablesEnabled;
        this.tableCreatesWithLocationAllowed = tableCreatesWithLocationAllowed;
        this.perTransactionCacheMaximumSize = perTransactionCacheMaximumSize;

        this.metastore = requireNonNull(metastore, "metastore is null");
        this.hdfsEnvironment = requireNonNull(hdfsEnvironment, "hdfsEnvironment is null");
        this.partitionManager = requireNonNull(partitionManager, "partitionManager is null");
        this.timeZone = requireNonNull(timeZone, "timeZone is null");
        this.typeManager = requireNonNull(typeManager, "typeManager is null");
        this.locationService = requireNonNull(locationService, "locationService is null");
        this.partitionUpdateCodec = requireNonNull(partitionUpdateCodec, "partitionUpdateCodec is null");
        this.typeTranslator = requireNonNull(typeTranslator, "typeTranslator is null");
        this.prestoVersion = requireNonNull(prestoVersion, "prestoVersion is null");
        this.accessControlMetadataFactory = requireNonNull(accessControlMetadataFactory, "accessControlMetadataFactory is null");
        this.hiveTransactionHeartbeatInterval = requireNonNull(hiveTransactionHeartbeatInterval, "hiveTransactionHeartbeatInterval is null");
        this.vacuumCleanupRecheckInterval = requireNonNull(vacuumCleanupRecheckInterval, "vacuumCleanupInterval is null");

        if (!allowCorruptWritesForTesting && !timeZone.equals(DateTimeZone.getDefault())) {
            log.warn("Hive writes are disabled. " +
                            "To write data to Hive, your JVM timezone must match the Hive storage timezone. " +
                            "Add -Duser.timezone=%s to your JVM arguments",
                    timeZone.getID());
        }

        renameExecution = new BoundedExecutor(executorService, maxConcurrentFileRenames);
        this.hiveVacuumService = requireNonNull(hiveVacuumService, "hiveVacuumService is null");
        this.heartbeatService = requireNonNull(heartbeatService, "heartbeatService is null");
        this.hiveMetastoreClientService = requireNonNull(hiveMetastoreClientService, "heartbeatService is null");
        this.vacuumDeltaNumThreshold = vacuumDeltaNumThreshold;
        this.vacuumDeltaPercentThreshold = vacuumDeltaPercentThreshold;
        this.autoVacuumEnabled = autoVacuumEnabled;
        this.vacuumCollectorInterval = vacuumCollectorInterval;
    }

    @Override
    public HiveMetadata get()
    {
        SemiTransactionalHiveMetastore metastore = new SemiTransactionalHiveMetastore(
                hdfsEnvironment,
                CachingHiveMetastore.memoizeMetastore(this.metastore, perTransactionCacheMaximumSize), // per-transaction cache
                renameExecution,
                hiveVacuumService,
                vacuumCleanupRecheckInterval,
                skipDeletionForAlter,
                skipTargetCleanupOnRollback,
                hiveTransactionHeartbeatInterval,
                heartbeatService,
                hiveMetastoreClientService);

        return new HiveMetadata(
                metastore,
                hdfsEnvironment,
                partitionManager,
                timeZone,
                allowCorruptWritesForTesting,
                writesToNonManagedTablesEnabled,
                createsOfNonManagedTablesEnabled,
                tableCreatesWithLocationAllowed,
                typeManager,
                locationService,
                partitionUpdateCodec,
                typeTranslator,
                prestoVersion,
                new MetastoreHiveStatisticsProvider(metastore),
                accessControlMetadataFactory.create(metastore),
                autoVacuumEnabled,
                vacuumDeltaNumThreshold,
                vacuumDeltaPercentThreshold,
                hiveVacuumService,
                vacuumCollectorInterval,
                hiveMetastoreClientService);
    }
}
