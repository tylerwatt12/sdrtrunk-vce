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
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single background SQLite writer for P25 activity logging.
 */
class P25ActivityLogWriter implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(P25ActivityLogWriter.class);
    private static final int DEFAULT_QUEUE_CAPACITY = 10000;
    private static final int BATCH_SIZE = 250;
    private static final long POLL_TIMEOUT_MILLISECONDS = 1000;
    private static final long DATABASE_BUSY_RETRY_MILLISECONDS = 500;
    private static final long RETENTION_CLEANUP_INTERVAL_MILLISECONDS = TimeUnit.HOURS.toMillis(1);
    private static final long MAINTENANCE_INTERVAL_MILLISECONDS = TimeUnit.DAYS.toMillis(1);

    private final Path mDatabasePath;
    private final ArrayBlockingQueue<P25ActivityLogRecord> mQueue;
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final AtomicLong mDroppedRecords = new AtomicLong();
    private final AtomicLong mWrittenRecords = new AtomicLong();
    private final P25ActivityCommitListener mCommitListener;
    private ExecutorService mExecutorService;
    private volatile int mRetentionDays;
    private volatile boolean mDetailedEventHistoryEnabled;
    private volatile long mLastRetentionCleanup;
    private volatile long mLastMaintenance;

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
            mExecutorService = Executors.newSingleThreadExecutor(new NamingThreadFactory("p25 activity log writer"));
            mExecutorService.execute(this::run);
        }
    }

    void setRetentionDays(int retentionDays)
    {
        mRetentionDays = Math.max(1, retentionDays);
    }

    void setDetailedEventHistoryEnabled(boolean detailedEventHistoryEnabled)
    {
        mDetailedEventHistoryEnabled = detailedEventHistoryEnabled;
    }

    void enqueue(P25ActivityLogRecord record)
    {
        if(record == null || !mRunning.get())
        {
            return;
        }

        if(!mQueue.offer(record))
        {
            mQueue.poll();
            mDroppedRecords.incrementAndGet();

            if(!mQueue.offer(record))
            {
                mDroppedRecords.incrementAndGet();
            }
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

    @Override
    public void close()
    {
        mRunning.set(false);

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
    }

    private void run()
    {
        try(Connection connection = openConnection())
        {
            runMaintenanceWithRetry(connection);

            updateStatusWithRetry(connection, "database_path", mDatabasePath.toString());

            List<P25ActivityLogRecord> batch = new ArrayList<>(BATCH_SIZE);

            while(mRunning.get() || !mQueue.isEmpty())
            {
                P25ActivityLogRecord first = mQueue.poll(POLL_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);

                if(first != null)
                {
                    batch.add(first);
                    mQueue.drainTo(batch, BATCH_SIZE - 1);
                    writeBatchWithRetry(connection, batch);
                    batch.clear();
                }

                if(System.currentTimeMillis() - mLastRetentionCleanup >= RETENTION_CLEANUP_INTERVAL_MILLISECONDS)
                {
                    cleanupRetentionWithRetry(connection);
                }

                if(System.currentTimeMillis() - mLastMaintenance >= MAINTENANCE_INTERVAL_MILLISECONDS)
                {
                    runMaintenanceWithRetry(connection);
                }
            }

            if(!batch.isEmpty())
            {
                writeBatchWithRetry(connection, batch);
            }
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
        catch(IOException e)
        {
            mLog.warn("P25 activity SQLite writer stopped after database path error", e);
        }
        catch(SQLException e)
        {
            mLog.warn("P25 activity SQLite writer stopped after database error", e);
        }
        finally
        {
            mRunning.set(false);
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
            }

            mWrittenRecords.addAndGet(writtenRecords);

            P25ActivityLogSchema.updateStatus(connection, "records_written", Long.toString(mWrittenRecords.get()));
            P25ActivityLogSchema.updateStatus(connection, "records_dropped", Long.toString(mDroppedRecords.get()));

            connection.commit();
            committed = true;
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
}
