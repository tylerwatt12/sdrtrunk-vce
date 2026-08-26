/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single background SQLite writer for statistics observations and maintenance.
 */
class P25ActivityLogWriter implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(P25ActivityLogWriter.class);
    private static final int DEFAULT_QUEUE_CAPACITY = 10000;
    /* Queue entries are observations that may fan out to multiple SQL statements, not changed-row counts. */
    private static final int BATCH_SIZE = 1250;
    /* Normal 40-100 observation/second traffic reaches the deadline before this bounded safety cap. */
    private static final long BATCH_COLLECTION_MILLISECONDS = 10000;
    private static final long BATCH_COLLECTION_POLL_MILLISECONDS = 100;
    private static final long POLL_TIMEOUT_MILLISECONDS = 1000;
    private static final int DATABASE_BUSY_TIMEOUT_MILLISECONDS = 250;
    private static final long DATABASE_BUSY_RETRY_MILLISECONDS = 100;
    private static final long GRACEFUL_DRAIN_MILLISECONDS = 5000;
    private static final long FORCED_SHUTDOWN_WAIT_MILLISECONDS = 1000;
    private static final long RETENTION_CLEANUP_INTERVAL_MILLISECONDS = TimeUnit.HOURS.toMillis(1);
    private static final long MAINTENANCE_INTERVAL_MILLISECONDS = TimeUnit.DAYS.toMillis(1);
    private static final long RESOLVED_CALL_RETENTION_MILLISECONDS = TimeUnit.HOURS.toMillis(24);
    private static final int MAXIMUM_RESOLVED_CALLS = 65_536;

    private final Path mDatabasePath;
    private final ArrayBlockingQueue<QueuedRecord> mQueue;
    private final int mBatchSize;
    private final long mBatchCollectionMilliseconds;
    private final int mDatabaseBusyTimeoutMilliseconds;
    private final long mGracefulDrainMilliseconds;
    private final ConcurrentLinkedQueue<MaintenanceCommand> mMaintenanceQueue = new ConcurrentLinkedQueue<>();
    private final Object mQueueOrderingLock = new Object();
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final AtomicBoolean mRetentionCleanupRequested = new AtomicBoolean();
    private final AtomicLong mEnqueueSequence = new AtomicLong();
    private final AtomicLong mDroppedRecords = new AtomicLong();
    private final AtomicLong mWrittenRecords = new AtomicLong();
    private final AtomicLong mLastSuccessfulWriteMs = new AtomicLong();
    /* Writer-thread-owned evidence that the matching logical-call counters committed successfully. */
    private final Map<LogicalCallId,Long> mResolvedLogicalCalls = new LinkedHashMap<>(1024, 0.75f, true);
    private volatile ExecutorService mExecutorService;
    private volatile long mShutdownDrainDeadlineNanos = Long.MIN_VALUE;
    private volatile int mRetentionDays;
    private volatile boolean mDetailedEventHistoryEnabled;
    private volatile long mLastRetentionCleanup;
    private volatile long mLastMaintenance;
    private volatile P25ActivityLogStatus.State mState = P25ActivityLogStatus.State.STOPPED;
    private volatile String mLastError;

    P25ActivityLogWriter(Path databasePath, int retentionDays, boolean detailedEventHistoryEnabled)
    {
        this(databasePath, retentionDays, detailedEventHistoryEnabled, DEFAULT_QUEUE_CAPACITY);
    }

    P25ActivityLogWriter(Path databasePath, int retentionDays, boolean detailedEventHistoryEnabled, int queueCapacity)
    {
        this(databasePath, retentionDays, detailedEventHistoryEnabled, queueCapacity, BATCH_SIZE,
            BATCH_COLLECTION_MILLISECONDS);
    }

    P25ActivityLogWriter(Path databasePath, int retentionDays, boolean detailedEventHistoryEnabled, int queueCapacity,
                         int batchSize, long batchCollectionMilliseconds)
    {
        this(databasePath, retentionDays, detailedEventHistoryEnabled, queueCapacity, batchSize,
            batchCollectionMilliseconds, DATABASE_BUSY_TIMEOUT_MILLISECONDS, GRACEFUL_DRAIN_MILLISECONDS);
    }

    P25ActivityLogWriter(Path databasePath, int retentionDays, boolean detailedEventHistoryEnabled, int queueCapacity,
                         int batchSize, long batchCollectionMilliseconds, int databaseBusyTimeoutMilliseconds,
                         long gracefulDrainMilliseconds)
    {
        mDatabasePath = databasePath;
        mQueue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        mBatchSize = Math.max(1, batchSize);
        mBatchCollectionMilliseconds = Math.max(0, batchCollectionMilliseconds);
        mDatabaseBusyTimeoutMilliseconds = Math.max(1, databaseBusyTimeoutMilliseconds);
        mGracefulDrainMilliseconds = Math.max(0, gracefulDrainMilliseconds);
        setRetentionDays(retentionDays);
        setDetailedEventHistoryEnabled(detailedEventHistoryEnabled);
    }

    void start()
    {
        if(mRunning.compareAndSet(false, true))
        {
            mShutdownDrainDeadlineNanos = Long.MIN_VALUE;
            mLastError = null;
            mState = P25ActivityLogStatus.State.STARTING;
            mExecutorService = Executors.newSingleThreadExecutor(new NamingThreadFactory("statistics database writer"));
            mExecutorService.execute(this::run);
        }
    }

    void setRetentionDays(int retentionDays)
    {
        int updatedRetentionDays = Math.max(1, retentionDays);
        int previousRetentionDays = mRetentionDays;
        mRetentionDays = updatedRetentionDays;

        if(previousRetentionDays > 0 && updatedRetentionDays < previousRetentionDays)
        {
            mRetentionCleanupRequested.set(true);
        }
    }

    void setDetailedEventHistoryEnabled(boolean detailedEventHistoryEnabled)
    {
        mDetailedEventHistoryEnabled = detailedEventHistoryEnabled;
    }

    void enqueue(P25ActivityLogRecord record)
    {
        if(record == null)
        {
            return;
        }

        synchronized(mQueueOrderingLock)
        {
            if(!mRunning.get())
            {
                return;
            }

            QueuedRecord queuedRecord = new QueuedRecord(mEnqueueSequence.incrementAndGet(), record);

            if(!mQueue.offer(queuedRecord))
            {
                mQueue.poll();
                mDroppedRecords.incrementAndGet();

                if(!mQueue.offer(queuedRecord))
                {
                    mDroppedRecords.incrementAndGet();
                }
            }
        }
    }

    /**
     * Serializes a non-droppable runtime maintenance request behind any active write transaction.
     */
    void submitMaintenance(StatsDatabaseMaintenanceRequest request)
    {
        if(request == null)
        {
            return;
        }

        synchronized(mQueueOrderingLock)
        {
            if(!mRunning.get())
            {
                request.result().completeExceptionally(
                    new IllegalStateException("Statistics database writer is not running"));
                return;
            }

            mMaintenanceQueue.offer(new MaintenanceCommand(request, mEnqueueSequence.get()));
        }
    }

    long getDroppedRecords()
    {
        return mDroppedRecords.get();
    }

    long getWrittenRecords()
    {
        return mWrittenRecords.get();
    }

    Path getDatabasePath()
    {
        return mDatabasePath;
    }

    boolean isWorkerTerminatedForTest()
    {
        ExecutorService executorService = mExecutorService;
        return executorService == null || executorService.isTerminated();
    }

    int getQueuedRecordCountForTest()
    {
        return mQueue.size();
    }

    WriterStatus getStatus()
    {
        return new WriterStatus(mState, mDetailedEventHistoryEnabled, mLastSuccessfulWriteMs.get(),
            mWrittenRecords.get(), mDroppedRecords.get(), mLastError);
    }

    @Override
    public void close()
    {
        long drainDeadlineNanos = System.nanoTime() +
            TimeUnit.MILLISECONDS.toNanos(mGracefulDrainMilliseconds);

        synchronized(mQueueOrderingLock)
        {
            mShutdownDrainDeadlineNanos = drainDeadlineNanos;
            mRunning.set(false);
        }

        ExecutorService executorService = mExecutorService;

        if(executorService != null)
        {
            executorService.shutdown();
            TerminationWait gracefulWait = awaitTerminationUntil(executorService, drainDeadlineNanos);
            boolean interrupted = gracefulWait.interrupted();
            boolean terminated = gracefulWait.terminated();

            if(!terminated)
            {
                executorService.shutdownNow();
                long forcedDeadlineNanos = System.nanoTime() +
                    TimeUnit.MILLISECONDS.toNanos(FORCED_SHUTDOWN_WAIT_MILLISECONDS);
                TerminationWait forcedWait = awaitTerminationUntil(executorService, forcedDeadlineNanos);
                terminated = forcedWait.terminated();
                interrupted |= forcedWait.interrupted();

                fail(new IllegalStateException("Statistics database writer exceeded its graceful drain deadline"));
            }

            if(terminated)
            {
                if(mExecutorService == executorService)
                {
                    mExecutorService = null;
                }
            }
            else
            {
                fail(new IllegalStateException("Statistics database writer did not terminate after forced shutdown"));
            }

            if(interrupted)
            {
                Thread.currentThread().interrupt();
            }

            if(!terminated)
            {
                throw new IllegalStateException("Statistics database writer is still running after forced shutdown");
            }
        }
    }

    private static TerminationWait awaitTerminationUntil(ExecutorService executorService, long deadlineNanos)
    {
        boolean interrupted = false;
        boolean terminated = executorService.isTerminated();

        while(!terminated)
        {
            long remainingNanos = deadlineNanos - System.nanoTime();

            if(remainingNanos <= 0)
            {
                break;
            }

            try
            {
                terminated = executorService.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS);
            }
            catch(InterruptedException e)
            {
                interrupted = true;
            }
        }

        return new TerminationWait(terminated, interrupted);
    }

    private void run()
    {
        Exception terminalFailure = null;

        try(Connection connection = openConnection())
        {
            restoreStatus(connection);
            runMaintenanceWithRetry(connection);

            updateStatusWithRetry(connection, "database_path", mDatabasePath.toString());

            if(mRunning.get())
            {
                mState = P25ActivityLogStatus.State.RUNNING;
            }

            List<P25ActivityLogRecord> batch = new ArrayList<>(mBatchSize);
            QueuedRecord pendingRecord = null;

            while(mRunning.get() || pendingRecord != null || !mQueue.isEmpty() || !mMaintenanceQueue.isEmpty())
            {
                MaintenanceCommand command = mMaintenanceQueue.peek();
                QueuedRecord queuedHead = mQueue.peek();
                long nextSequence = pendingRecord != null ? pendingRecord.sequence() :
                    (queuedHead != null ? queuedHead.sequence() : Long.MAX_VALUE);

                if(command != null && nextSequence > command.observationBarrierSequence())
                {
                    processNextMaintenanceCommand(connection);
                    runScheduledMaintenance(connection);
                    continue;
                }

                if(pendingRecord == null)
                {
                    pendingRecord = mQueue.poll(POLL_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
                }

                command = mMaintenanceQueue.peek();

                if(pendingRecord != null &&
                    (command == null || pendingRecord.sequence() <= command.observationBarrierSequence()))
                {
                    batch.add(pendingRecord.record());
                    pendingRecord = null;

                    long batchDeadline = System.nanoTime() +
                        TimeUnit.MILLISECONDS.toNanos(mBatchCollectionMilliseconds);

                    while(batch.size() < mBatchSize)
                    {
                        if(mRetentionCleanupRequested.get())
                        {
                            break;
                        }

                        command = mMaintenanceQueue.peek();
                        queuedHead = mQueue.peek();

                        if(command != null && (queuedHead == null ||
                            queuedHead.sequence() > command.observationBarrierSequence()))
                        {
                            break;
                        }

                        if(!mRunning.get())
                        {
                            QueuedRecord next = mQueue.poll();

                            if(next == null)
                            {
                                break;
                            }

                            batch.add(next.record());
                            continue;
                        }

                        long remainingNanoseconds = batchDeadline - System.nanoTime();

                        if(remainingNanoseconds <= 0)
                        {
                            break;
                        }

                        long pollNanoseconds = Math.min(remainingNanoseconds,
                            TimeUnit.MILLISECONDS.toNanos(BATCH_COLLECTION_POLL_MILLISECONDS));
                        QueuedRecord next = mQueue.poll(pollNanoseconds, TimeUnit.NANOSECONDS);

                        if(next == null)
                        {
                            continue;
                        }

                        command = mMaintenanceQueue.peek();

                        if(command != null && next.sequence() > command.observationBarrierSequence())
                        {
                            pendingRecord = next;
                            break;
                        }

                        batch.add(next.record());
                    }

                    writeBatchWithRetry(connection, batch);
                    batch.clear();
                }

                runScheduledMaintenance(connection);
            }

            if(!batch.isEmpty())
            {
                writeBatchWithRetry(connection, batch);
            }
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            terminalFailure = e;
        }
        catch(IOException e)
        {
            terminalFailure = e;
            fail(e);
            mLog.warn("Statistics SQLite writer stopped after database path error", e);
        }
        catch(SQLException e)
        {
            terminalFailure = e;
            fail(e);
            mLog.warn("Statistics SQLite writer stopped after database error", e);
        }
        finally
        {
            mRunning.set(false);
            failPendingMaintenance(terminalFailure != null ? terminalFailure :
                new IllegalStateException("Statistics database writer stopped"));

            if(mState != P25ActivityLogStatus.State.FAILED)
            {
                mState = P25ActivityLogStatus.State.STOPPED;
            }
        }
    }

    private void processNextMaintenanceCommand(Connection connection)
    {
        MaintenanceCommand command = mMaintenanceQueue.poll();

        if(command != null)
        {
            try
            {
                P25ActivityLogMaintenance.Result result = executeMaintenanceWithRetry(connection, command);
                command.request().result().complete(result);
            }
            catch(Exception e)
            {
                command.request().result().completeExceptionally(e);
                mLog.warn("Statistics database maintenance request failed [{}]",
                    command.request().operation(), e);
            }
        }
    }

    private void runScheduledMaintenance(Connection connection) throws SQLException, InterruptedException
    {
        if(mRetentionCleanupRequested.getAndSet(false) ||
            System.currentTimeMillis() - mLastRetentionCleanup >= RETENTION_CLEANUP_INTERVAL_MILLISECONDS)
        {
            cleanupRetentionWithRetry(connection);
        }

        if(System.currentTimeMillis() - mLastMaintenance >= MAINTENANCE_INTERVAL_MILLISECONDS)
        {
            runMaintenanceWithRetry(connection);
        }
    }

    private P25ActivityLogMaintenance.Result executeMaintenanceWithRetry(Connection connection,
                                                                         MaintenanceCommand command)
        throws IOException, SQLException, InterruptedException
    {
        while(true)
        {
            try
            {
                StatsDatabaseMaintenanceRequest request = command.request();
                P25ActivityLogMaintenance.Result result;

                if(request.operation() == P25ActivityLogMaintenance.Operation.CLEAR_SITE_STATS)
                {
                    result = P25ActivityLogMaintenance.clearSiteStats(connection, mDatabasePath, request.siteGuid());
                }
                else
                {
                    result = P25ActivityLogMaintenance.run(connection, mDatabasePath, mRetentionDays,
                        request.operation());
                }

                if(request.operation() == P25ActivityLogMaintenance.Operation.MAINTAIN ||
                    request.operation() == P25ActivityLogMaintenance.Operation.SHRINK)
                {
                    mLastRetentionCleanup = System.currentTimeMillis();
                    mLastMaintenance = System.currentTimeMillis();
                }
                else if(request.operation() == P25ActivityLogMaintenance.Operation.RESET_STATS)
                {
                    mResolvedLogicalCalls.clear();
                }

                return result;
            }
            catch(SQLException e)
            {
                if(!isDatabaseBusy(e) || !pauseBeforeDatabaseBusyRetry())
                {
                    throw e;
                }
            }
        }
    }

    private void failPendingMaintenance(Exception failure)
    {
        MaintenanceCommand command;

        while((command = mMaintenanceQueue.poll()) != null)
        {
            command.request().result().completeExceptionally(failure);
        }
    }

    private Connection openConnection() throws IOException, SQLException, InterruptedException
    {
        while(true)
        {
            Connection connection = null;

            try
            {
                connection = SdrTrunkDatabase.open(mDatabasePath);

                try(Statement statement = connection.createStatement())
                {
                    statement.execute("PRAGMA busy_timeout=" + mDatabaseBusyTimeoutMilliseconds);
                }

                return connection;
            }
            catch(SQLException e)
            {
                if(connection != null)
                {
                    try
                    {
                        connection.close();
                    }
                    catch(SQLException closeException)
                    {
                        e.addSuppressed(closeException);
                    }
                }

                if(!isDatabaseBusy(e) || !pauseBeforeDatabaseBusyRetry())
                {
                    throw e;
                }
            }
        }
    }

    private void restoreStatus(Connection connection) throws SQLException
    {
        mWrittenRecords.set(P25ActivityLogSchema.readStatusLong(connection, "records_written"));
        mDroppedRecords.addAndGet(P25ActivityLogSchema.readStatusLong(connection, "records_dropped"));
        long lastSuccessfulWriteMs = P25ActivityLogSchema.readStatusLong(connection, "last_successful_write_ms");
        mLastSuccessfulWriteMs.updateAndGet(current -> Math.max(current, lastSuccessfulWriteMs));
    }

    private void writeBatchWithRetry(Connection connection, List<P25ActivityLogRecord> batch)
        throws SQLException, InterruptedException
    {
        while(true)
        {
            try
            {
                writeBatch(connection, batch);
                return;
            }
            catch(SQLException e)
            {
                if(!isDatabaseBusy(e) || !pauseBeforeDatabaseBusyRetry())
                {
                    throw e;
                }
            }
        }
    }

    private void cleanupRetentionWithRetry(Connection connection) throws SQLException, InterruptedException
    {
        while(true)
        {
            try
            {
                cleanupRetention(connection);
                return;
            }
            catch(SQLException e)
            {
                if(!isDatabaseBusy(e) || !pauseBeforeDatabaseBusyRetry())
                {
                    throw e;
                }
            }
        }
    }

    private void updateStatusWithRetry(Connection connection, String key, String value)
        throws SQLException, InterruptedException
    {
        while(true)
        {
            try
            {
                P25ActivityLogSchema.updateStatus(connection, key, value);
                return;
            }
            catch(SQLException e)
            {
                if(!isDatabaseBusy(e) || !pauseBeforeDatabaseBusyRetry())
                {
                    throw e;
                }
            }
        }
    }

    private void runMaintenanceWithRetry(Connection connection) throws SQLException, InterruptedException
    {
        while(true)
        {
            try
            {
                P25ActivityLogMaintenance.runLightMaintenance(connection, mRetentionDays);
                mLastRetentionCleanup = System.currentTimeMillis();
                mLastMaintenance = System.currentTimeMillis();
                return;
            }
            catch(SQLException e)
            {
                if(!isDatabaseBusy(e) || !pauseBeforeDatabaseBusyRetry())
                {
                    throw e;
                }
            }
        }
    }

    private boolean pauseBeforeDatabaseBusyRetry() throws InterruptedException
    {
        long retryNanos = TimeUnit.MILLISECONDS.toNanos(DATABASE_BUSY_RETRY_MILLISECONDS);

        if(!mRunning.get())
        {
            long remainingNanos = remainingShutdownDrainNanos();

            if(remainingNanos <= 0)
            {
                return false;
            }

            retryNanos = Math.min(retryNanos, remainingNanos);
        }

        TimeUnit.NANOSECONDS.sleep(retryNanos);
        return mRunning.get() || remainingShutdownDrainNanos() > 0;
    }

    private long remainingShutdownDrainNanos()
    {
        long deadlineNanos = mShutdownDrainDeadlineNanos;
        return deadlineNanos == Long.MIN_VALUE ? 0 : deadlineNanos - System.nanoTime();
    }

    private void writeBatch(Connection connection, List<P25ActivityLogRecord> batch) throws SQLException
    {
        if(batch.isEmpty())
        {
            return;
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);

        try
        {
            int writtenRecords = 0;
            Set<LogicalCallId> acceptedLogicalCalls = new LinkedHashSet<>();
            pruneResolvedLogicalCalls(System.currentTimeMillis());

            for(P25ActivityLogRecord record: batch)
            {
                if(record instanceof P25ActivityLogRecords.ActivityEvent activityEvent)
                {
                    P25ActivityLogSchema.recordActivity(connection, activityEvent, mDetailedEventHistoryEnabled);
                    writtenRecords++;
                }
                else if(record instanceof P25ActivityLogRecords.SiteSnapshot siteSnapshot)
                {
                    P25ActivityLogSchema.insertSite(connection, siteSnapshot);
                    writtenRecords++;
                }
                else if(record instanceof P25ActivityLogRecords.ChannelFact channelFact)
                {
                    P25ActivityLogSchema.upsertGrantedChannelSummary(connection, channelFact);
                    writtenRecords++;
                }
                else if(record instanceof P25ActivityLogRecords.TalkerAliasUpdate talkerAliasUpdate)
                {
                    P25ActivityLogSchema.updateTalkerAlias(connection, talkerAliasUpdate);
                    writtenRecords++;
                }
                else if(record instanceof P25ActivityLogRecords.ControlChannelQuality quality)
                {
                    P25ActivityLogSchema.insertControlChannelQuality(connection, quality);
                    writtenRecords++;
                }
                else if(record instanceof P25ActivityLogRecords.ConventionalCallOutput callOutput)
                {
                    if(P25ActivityLogSchema.applyConventionalCallOutput(connection, callOutput))
                    {
                        writtenRecords++;
                    }
                }
                else if(record instanceof P25ActivityLogRecords.ResolvedLogicalCall logicalCall)
                {
                    LogicalCallId logicalCallId = logicalCall.logicalCallId();
                    if(!hasResolvedLogicalCall(logicalCallId, acceptedLogicalCalls) &&
                        P25ActivityLogSchema.recordResolvedLogicalCall(connection, logicalCall))
                    {
                        acceptedLogicalCalls.add(logicalCallId);
                        writtenRecords++;
                    }
                }
                else if(record instanceof P25ActivityLogRecords.LogicalCallOutput logicalOutput)
                {
                    if(hasResolvedLogicalCall(logicalOutput.call().logicalCallId(), acceptedLogicalCalls) &&
                        P25ActivityLogSchema.applyLogicalCallOutput(connection, logicalOutput))
                    {
                        writtenRecords++;
                    }
                }
                else if(record instanceof P25ActivityLogRecords.TrunkedCallAttribution attribution)
                {
                    if(P25ActivityLogSchema.applyTrunkedCallAttribution(connection, attribution))
                    {
                        writtenRecords++;
                    }
                }
                else if(record instanceof P25ActivityLogRecords.DmrConventionalCall dmrCall)
                {
                    P25ActivityLogSchema.recordDmrConventionalCall(connection, dmrCall,
                        mDetailedEventHistoryEnabled);
                    writtenRecords++;
                }
                else if(record instanceof P25ActivityLogRecords.NxdnConventionalCall nxdnCall)
                {
                    P25ActivityLogSchema.recordNxdnConventionalCall(connection, nxdnCall,
                        mDetailedEventHistoryEnabled);
                    writtenRecords++;
                }
                else if(record instanceof P25ActivityLogRecords.TrunkedSiteSnapshot trunkedSiteSnapshot)
                {
                    long childRetentionCutoff = System.currentTimeMillis() -
                        TimeUnit.DAYS.toMillis(Math.max(1, mRetentionDays));
                    if(P25ActivityLogSchema.isAuthoritativeTrunkedSiteSnapshot(
                        connection, trunkedSiteSnapshot.snapshot()) &&
                        TrunkedSiteSchema.upsert(connection, trunkedSiteSnapshot.snapshot(), childRetentionCutoff))
                    {
                        P25ActivityLogSchema.ensureTrunkedSiteIdentityScope(connection,
                            trunkedSiteSnapshot.snapshot());
                    }
                    writtenRecords++;
                }
            }

            long writtenTotal = mWrittenRecords.get() + writtenRecords;
            long successfulWrite = System.currentTimeMillis();
            P25ActivityLogSchema.updateStatus(connection, "records_written", Long.toString(writtenTotal));
            P25ActivityLogSchema.updateStatus(connection, "records_dropped", Long.toString(mDroppedRecords.get()));
            P25ActivityLogSchema.updateStatus(connection, "last_successful_write_ms",
                Long.toString(successfulWrite));

            connection.commit();
            rememberResolvedLogicalCalls(acceptedLogicalCalls, successfulWrite);
            mWrittenRecords.addAndGet(writtenRecords);
            mLastSuccessfulWriteMs.set(successfulWrite);
        }
        catch(SQLException e)
        {
            try
            {
                connection.rollback();
            }
            catch(SQLException rollbackException)
            {
                e.addSuppressed(rollbackException);
            }

            if(!isDatabaseBusy(e))
            {
                try
                {
                    P25ActivityLogSchema.updateStatus(connection, "last_write_error", e.getMessage());
                }
                catch(SQLException statusException)
                {
                    e.addSuppressed(statusException);
                }
            }

            throw e;
        }
        finally
        {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private boolean hasResolvedLogicalCall(LogicalCallId logicalCallId, Set<LogicalCallId> acceptedLogicalCalls)
    {
        return logicalCallId != null && (acceptedLogicalCalls.contains(logicalCallId) ||
            mResolvedLogicalCalls.get(logicalCallId) != null);
    }

    private void rememberResolvedLogicalCalls(Set<LogicalCallId> logicalCallIds, long observedAt)
    {
        for(LogicalCallId logicalCallId: logicalCallIds)
        {
            mResolvedLogicalCalls.put(logicalCallId, observedAt);
        }

        while(mResolvedLogicalCalls.size() > MAXIMUM_RESOLVED_CALLS)
        {
            var iterator = mResolvedLogicalCalls.keySet().iterator();
            if(iterator.hasNext())
            {
                iterator.next();
                iterator.remove();
            }
        }
    }

    private void pruneResolvedLogicalCalls(long now)
    {
        var iterator = mResolvedLogicalCalls.entrySet().iterator();
        while(iterator.hasNext())
        {
            Map.Entry<LogicalCallId,Long> entry = iterator.next();
            if(now >= entry.getValue() && now - entry.getValue() > RESOLVED_CALL_RETENTION_MILLISECONDS)
            {
                iterator.remove();
            }
        }
    }

    private void cleanupRetention(Connection connection) throws SQLException
    {
        P25ActivityLogMaintenance.cleanupRetention(connection, mRetentionDays);
        mLastRetentionCleanup = System.currentTimeMillis();
    }

    private static boolean isDatabaseBusy(SQLException exception)
    {
        Throwable throwable = exception;

        while(throwable != null)
        {
            String message = throwable.getMessage();

            if(message != null && (message.contains("SQLITE_BUSY") || message.contains("database is locked")))
            {
                return true;
            }

            throwable = throwable.getCause();
        }

        return false;
    }

    private void fail(Exception exception)
    {
        String message = exception.getMessage();
        String error = exception.getClass().getSimpleName() +
            (message == null || message.isBlank() ? "" : ": " + message);
        mLastError = error.substring(0, Math.min(500, error.length()));
        mState = P25ActivityLogStatus.State.FAILED;
    }

    record WriterStatus(P25ActivityLogStatus.State state, boolean detailedHistoryEnabled,
                        long lastSuccessfulWriteMs, long recordsWritten, long recordsDropped, String lastError)
    {
    }

    private record QueuedRecord(long sequence, P25ActivityLogRecord record)
    {
    }

    private record MaintenanceCommand(StatsDatabaseMaintenanceRequest request, long observationBarrierSequence)
    {
    }

    private record TerminationWait(boolean terminated, boolean interrupted)
    {
    }
}
