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
package io.prestosql.spi.connector;

import io.airlift.slice.Slice;
import io.prestosql.spi.Page;
import io.prestosql.spi.snapshot.BlockEncodingSerdeProvider;
import io.prestosql.spi.snapshot.Restorable;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ConnectorPageSink
        extends Restorable
{
    CompletableFuture<?> NOT_BLOCKED = CompletableFuture.completedFuture(null);

    /**
     * Gets the number of output bytes written by this page source so far.
     * If size is not available, this method should return zero.
     */
    default long getCompletedBytes()
    {
        return 0;
    }

    /**
     * Gets the number of output rows written by this page sink so far.
     * If size is not available, this method should return zero.
     */
    default long getRowsWritten()
    {
        return 0;
    }

    /**
     * Get the total memory that needs to be reserved in the general memory pool.
     * This memory should include any buffers, etc. that are used for reading data.
     *
     * @return the memory used so far in table read
     */
    default long getSystemMemoryUsage()
    {
        return 0;
    }

    /**
     * ConnectorPageSink can provide optional validation to check
     * the data is correctly consumed by connector (e.g. output table has the correct data).
     * <p>
     * This method returns the CPU spent on validation, if any.
     */
    default long getValidationCpuNanos()
    {
        return 0;
    }

    /**
     * Returns a future that will be completed when the page sink can accept
     * more pages.  If the page sink can accept more pages immediately,
     * this method should return {@code NOT_BLOCKED}.
     */
    CompletableFuture<?> appendPage(Page page);

    /**
     * TODO-cp-I2BZ0A: may replace these with more generic solutions.
     * Create a savepoint.
     * The {@link #restore} function can be called later to bring the connector back to the savepoint state.
     * That is, any change (add/delete/update) made after the savepoint is reverted.
     * If {@link #appendPage} was called, then this method should be called after the returned future is complete.
     * The method should not be called after {@link #finish} or {@link #abort}. Its behavior is undefined.
     */
    @Override
    default Object capture(BlockEncodingSerdeProvider serdeProvider)
    {
        throw new UnsupportedOperationException("This connector is not restorable: " + getClass().getName());
    }

    /**
     * Restore to a previous savepoint.
     * If {@link #appendPage} was called, then this method should be called after the returned future is complete.
     * The method should not be called after {@link #finish} or {@link #abort}. Its behavior is undefined.
     */
    @Override
    default void restore(Object state, BlockEncodingSerdeProvider serdeProvider)
    {
        throw new UnsupportedOperationException("This connector is not restorable: " + getClass().getName());
    }

    /**
     * Notifies the connector that no more pages will be appended and returns
     * connector-specific information that will be sent to the coordinator to
     * complete the write operation. This method may be called immediately
     * after the previous call to {@link #appendPage} (even if the returned
     * future is not complete).
     */
    CompletableFuture<Collection<Slice>> finish();

    void abort();

    /**
     * When page sinks need to be cancelled so they can be rescheduled to resume query execution,
     * then call this function, to differentiate from "abort".
     * Default implementation invokes abort(). Connectors that support resuming,
     * e.g. HivePageSink, should override this function.
     */
    default void cancelToResume()
    {
        abort();
    }

    /**
     * Returns a future that will be completed when the page sink completes the vacuum operation.
     *
     * @returns The current appended page for Vacuum, if any
     */
    default VacuumResult vacuum(ConnectorPageSourceProvider pageSourceProvider,
            ConnectorTransactionHandle transactionHandle,
            ConnectorTableHandle connectorTableHandle,
            List<ConnectorSplit> splits)
    {
        throw new UnsupportedOperationException("Vacuum is not supported by connector page sink : " + getClass().getSimpleName());
    }

    /**
     * VacuumResult class contains the previous page processed during Vacuum.
     * This page will be helpful for statistics.
     */
    class VacuumResult
    {
        private final Page page;
        private final boolean finished;

        public VacuumResult(Page page, boolean finished)
        {
            this.page = page;
            this.finished = finished;
        }

        public Page getPage()
        {
            return page;
        }

        public boolean isFinished()
        {
            return finished;
        }
    }
}
