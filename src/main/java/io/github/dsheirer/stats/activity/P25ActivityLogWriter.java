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

import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
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
    private static final int BATCH_SIZE = 250;
    private static final long BATCH_COLLECTION_MILLISECONDS = 100;
    private static final long POLL_TIMEOUT_MILLISECONDS = 1000;
    private static final long DATABASE_BUSY_RETRY_MILLISECONDS = 500;
    private static final long RETENTION_CLEANUP_INTERVAL_MILLISECONDS = TimeUnit.HOURS.toMillis(1);
    private static final long MAINTENANCE_INTERVAL_MILLISECONDS = TimeUnit.DAYS.toMillis(1);

    private final Path mDatabasePath;
    private final ArrayBlockingQueue<QueuedRecord> mQueue;
    private final ConcurrentLinkedQueue<MaintenanceCommand> mMaintenanceQueue = new ConcurrentLinkedQueue<>();
    private final Object mQueueOrderingLock = new Object();
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final AtomicBoolean mRetentionCleanupRequested = new AtomicBoolean();
    private final AtomicLong mEnqueueSequence = new AtomicLong();
    private final AtomicLong mDroppedRecords = new AtomicLong();
    private final AtomicLong mWrittenRecords = new AtomicLong();
    private final AtomicLong mLastSuccessfulWriteMs = new AtomicLong();
    private final P25ActivityCommitListener mCommitListener;
    private ExecutorService mExecutorService;
    private volatile int mRetentionDays;
    private volatile boolean mDetailedEventHistoryEnabled;
    private volatile long mLastRetentionCleanup;
    private volatile long mLastMaintenance;
    private volatile P25ActivityLogStatus.State mState = P25ActivityLogStatus.State.STOPPED;
    private volatile String mLastError;

    P25ActivityLogWriter(Path databasePath, int retentionDays, boolean detailedEventHistoryEnabled)
    {
        this(databasePath, retentionDays, detailedEventHistoryEnabled, DEFAULT_QUEUE_CAPACITY, null);
    }

    P25ActivityLogWriter(Path databasePath, int retentionDays, boolean detailedEventHistoryEnabled,
                         P25ActivityCommitListener commitListener)
    {
        this(databasePath, retentionDays, detailedEventHistoryEnabled, DEFAULT_QUEUE_CAPACITY, commitListener);
    }

    P25ActivityLogWriter(Path databasePath, int retentionDays, boolean detailedEventHistoryEnabled, int queueCapacity)
    {
        this(databasePath, retentionDays, detailedEventHistoryEnabled, queueCapacity, null);
    }

    private P25ActivityLogWriter(Path databasePath, int retentionDays, boolean detailedEventHistoryEnabled,
                                 int queueCapacity, P25ActivityCommitListener commitListener)
    {
        mDatabasePath = databasePath;
        mQueue = new ArrayBlockingQueue<>(Math.max(1, queueCapacity));
        mCommitListener = commitListener;
        setRetentionDays(retentionDays);
        setDetailedEventHistoryEnabled(detailedEventHistoryEnabled);
    }

    void start()
    {
        if(mRunning.compareAndSet(false, true))
        {
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

    WriterStatus getStatus()
    {
        return new WriterStatus(mState, mDetailedEventHistoryEnabled, mLastSuccessfulWriteMs.get(),
            mWrittenRecords.get(), mDroppedRecords.get(), mLastError);
    }

    @Override
    public void close()
    {
        synchronized(mQueueOrderingLock)
        {
            mRunning.set(false);
        }

        if(mExecutorService != null)
        {
            mExecutorService.shutdown();

            try
            {
                if(!mExecutorService.awaitTermination(5, TimeUnit.SECONDS))
                {
                    mExecutorService.shutdownNow();
                }
            }
            catch(InterruptedException e)
            {
                Thread.currentThread().interrupt();
                mExecutorService.shutdownNow();
            }
            finally
            {
                mExecutorService = null;
            }
        }

        if(mState != P25ActivityLogStatus.State.FAILED)
        {
            mState = P25ActivityLogStatus.State.STOPPED;
        }
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

            List<P25ActivityLogRecord> batch = new ArrayList<>(BATCH_SIZE);
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
                        TimeUnit.MILLISECONDS.toNanos(BATCH_COLLECTION_MILLISECONDS);

                    while(batch.size() < BATCH_SIZE)
                    {
                        long remainingNanoseconds = batchDeadline - System.nanoTime();

                        if(remainingNanoseconds <= 0)
                        {
                            break;
                        }

                        QueuedRecord next = mQueue.poll(remainingNanoseconds, TimeUnit.NANOSECONDS);

                        if(next == null)
                        {
                            break;
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

                return result;
            }
            catch(SQLException e)
            {
                if(!isDatabaseBusy(e) || !mRunning.get())
                {
                    throw e;
                }

                Thread.sleep(DATABASE_BUSY_RETRY_MILLISECONDS);
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
            try
            {
                return SdrTrunkDatabase.open(mDatabasePath);
            }
            catch(SQLException e)
            {
                if(!isDatabaseBusy(e) || !mRunning.get())
                {
                    throw e;
                }

                Thread.sleep(DATABASE_BUSY_RETRY_MILLISECONDS);
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
                if(!isDatabaseBusy(e) || !mRunning.get())
                {
                    throw e;
                }

                Thread.sleep(DATABASE_BUSY_RETRY_MILLISECONDS);
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
                if(!isDatabaseBusy(e) || !mRunning.get())
                {
                    throw e;
                }

                Thread.sleep(DATABASE_BUSY_RETRY_MILLISECONDS);
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
                if(!isDatabaseBusy(e) || !mRunning.get())
                {
                    throw e;
                }

                Thread.sleep(DATABASE_BUSY_RETRY_MILLISECONDS);
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
                if(!isDatabaseBusy(e) || !mRunning.get())
                {
                    throw e;
                }

                Thread.sleep(DATABASE_BUSY_RETRY_MILLISECONDS);
            }
        }
    }

    private void writeBatch(Connection connection, List<P25ActivityLogRecord> batch) throws SQLException
    {
        if(batch.isEmpty())
        {
            return;
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        List<Long> committedActivityIds = new ArrayList<>();
        boolean committed = false;

        try
        {
            int writtenRecords = 0;

            for(P25ActivityLogRecord record: batch)
            {
                if(record instanceof P25ActivityLogRecords.ActivityEvent activityEvent)
                {
                    Long activityId = P25ActivityLogSchema.recordActivity(connection, activityEvent,
                        mDetailedEventHistoryEnabled);

                    if(activityId != null)
                    {
                        committedActivityIds.add(activityId);
                    }

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
                else if(record instanceof P25ActivityLogRecords.CompletedCallOutput callOutput)
                {
                    if(P25ActivityLogSchema.applyCompletedCallOutput(connection, callOutput))
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
                    Long activityId = P25ActivityLogSchema.recordDmrConventionalCall(connection, dmrCall,
                        mDetailedEventHistoryEnabled);

                    if(activityId != null)
                    {
                        committedActivityIds.add(activityId);
                    }

                    writtenRecords++;
                }
                else if(record instanceof P25ActivityLogRecords.TrunkedSiteSnapshot trunkedSiteSnapshot)
                {
                    long childRetentionCutoff = System.currentTimeMillis() -
                        TimeUnit.DAYS.toMillis(Math.max(1, mRetentionDays));
                    TrunkedSiteSchema.upsert(connection, trunkedSiteSnapshot.snapshot(), childRetentionCutoff);
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
            committed = true;
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

        if(committed && mCommitListener != null && !committedActivityIds.isEmpty())
        {
            try
            {
                mCommitListener.activityCommitted(List.copyOf(committedActivityIds));
            }
            catch(RuntimeException e)
            {
                mLog.warn("Committed activity listener failed", e);
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
}
