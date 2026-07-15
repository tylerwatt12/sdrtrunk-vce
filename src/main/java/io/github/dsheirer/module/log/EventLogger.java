/*
 * *****************************************************************************
 * Copyright (C) 2014-2022 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.module.log;

import com.google.common.util.concurrent.MoreExecutors;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.util.TimeStamp;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class EventLogger extends Module
{
    private static final Logger mLog = LoggerFactory.getLogger(EventLogger.class);
    private static final Executor FILE_IO_EXECUTOR = MoreExecutors.newSequentialExecutor(ThreadPool.CACHED);
    private static final int MAXIMUM_PENDING_WRITE_COUNT = 4096;
    private static final Semaphore PENDING_WRITE_PERMITS = new Semaphore(MAXIMUM_PENDING_WRITE_COUNT);
    private static final long SHUTDOWN_DRAIN_TIMEOUT_SECONDS = 5;

    private final Path mLogDirectory;
    private final String mFileNameSuffix;
    private final long mFrequency;
    private final Object mLifecycleLock = new Object();
    private final Semaphore mPendingWritePermits;
    private final int mPendingWriteLimit;
    private final AtomicLong mDroppedEntryCount = new AtomicLong();
    private volatile String mLogFileName;
    private Writer mLogFile;
    private boolean mStarted;

    protected EventLogger(Path logDirectory, String fileNameSuffix, long frequency)
    {
        this(logDirectory, fileNameSuffix, frequency, PENDING_WRITE_PERMITS, MAXIMUM_PENDING_WRITE_COUNT);
    }

    /**
     * Test constructor that permits deterministic overflow testing with a small queue limit.
     */
    EventLogger(Path logDirectory, String fileNameSuffix, long frequency, int pendingWriteLimit)
    {
        this(logDirectory, fileNameSuffix, frequency, new Semaphore(pendingWriteLimit), pendingWriteLimit);
    }

    private EventLogger(Path logDirectory, String fileNameSuffix, long frequency, Semaphore pendingWritePermits,
                        int pendingWriteLimit)
    {
        if(pendingWriteLimit <= 0)
        {
            throw new IllegalArgumentException("Pending event-log write limit must be greater than zero");
        }

        mLogDirectory = logDirectory;
        mFileNameSuffix = fileNameSuffix;
        mFrequency = frequency;
        mPendingWritePermits = pendingWritePermits;
        mPendingWriteLimit = pendingWriteLimit;
    }

    public String toString()
    {
        if(mLogFileName != null)
        {
            return mLogFileName;
        }

        return "Unknown";
    }

    public abstract String getHeader();

    @Override
    public void start()
    {
        synchronized(mLifecycleLock)
        {
            if(!mStarted)
            {
                mStarted = true;
                String logFileName = mLogDirectory + File.separator + TimeStamp.getLongTimeStamp("_") + "_" +
                    mFrequency + "_Hz_" + mFileNameSuffix;
                mLogFileName = logFileName;
                submitIoOperation(() -> open(logFileName));
            }
        }
    }

    @Override
    public void stop()
    {
        synchronized(mLifecycleLock)
        {
            if(mStarted)
            {
                mStarted = false;
                submitIoOperation(this::close);
            }
        }
    }

    protected void write(String eventLogEntry)
    {
        synchronized(mLifecycleLock)
        {
            if(!mStarted)
            {
                return;
            }

            if(mPendingWritePermits.tryAcquire())
            {
                submitWrite(eventLogEntry != null ? eventLogEntry : "");
            }
            else
            {
                long dropped = mDroppedEntryCount.incrementAndGet();

                if(dropped == 1)
                {
                    submitIoOperation(() -> mLog.warn(
                        "Event logger [{}] reached the pending-write limit [{}]; dropping new log entries",
                        mFileNameSuffix, mPendingWriteLimit));
                }
            }
        }
    }

    long getDroppedEntryCount()
    {
        return mDroppedEntryCount.get();
    }

    private void submitWrite(String entry)
    {
        try
        {
            FILE_IO_EXECUTOR.execute(() -> {
                try
                {
                    writeToFile(entry);
                }
                finally
                {
                    mPendingWritePermits.release();
                }
            });
        }
        catch(RuntimeException exception)
        {
            mPendingWritePermits.release();
            mLog.error("Unable to schedule asynchronous event-log write", exception);
        }
    }

    private static boolean submitIoOperation(Runnable operation)
    {
        try
        {
            FILE_IO_EXECUTOR.execute(operation);
            return true;
        }
        catch(RuntimeException exception)
        {
            mLog.error("Unable to schedule asynchronous event-log operation", exception);
            return false;
        }
    }

    private void open(String logFileName)
    {
        try
        {
            mLogFile = new OutputStreamWriter(new FileOutputStream(logFileName));
            writeToFile(getHeader());
        }
        catch(FileNotFoundException exception)
        {
            mLog.error("Couldn't create log file in directory:" + mLogDirectory);
        }
    }

    private void writeToFile(String eventLogEntry)
    {
        if(mLogFile != null)
        {
            try
            {
                mLogFile.write((eventLogEntry != null ? eventLogEntry : "") + "\n");
                mLogFile.flush();
            }
            catch(Exception exception)
            {
                mLog.error("Error writing entry to event log file", exception);
            }
        }
    }

    private void close()
    {
        if(mLogFile != null)
        {
            try
            {
                mLogFile.flush();
                mLogFile.close();
            }
            catch(Exception exception)
            {
                mLog.error("Couldn't close log file:" + mFileNameSuffix, exception);
            }
            finally
            {
                mLogFile = null;
            }
        }
    }

    /**
     * Drains queued event-log I/O during application shutdown after all channels have stopped.
     */
    public static void flushPendingWrites()
    {
        CountDownLatch drained = new CountDownLatch(1);

        if(!submitIoOperation(drained::countDown))
        {
            return;
        }

        try
        {
            if(!drained.await(SHUTDOWN_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS))
            {
                mLog.warn("Timed out waiting for queued event-log writes to finish");
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            mLog.warn("Interrupted while waiting for queued event-log writes to finish");
        }
    }
}
