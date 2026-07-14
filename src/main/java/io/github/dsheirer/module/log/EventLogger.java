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
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class EventLogger extends Module
{
    private static final Logger mLog = LoggerFactory.getLogger(EventLogger.class);
    private static final Executor FILE_IO_EXECUTOR = MoreExecutors.newSequentialExecutor(ThreadPool.CACHED);
    private static final long SHUTDOWN_DRAIN_TIMEOUT_SECONDS = 5;

    private Path mLogDirectory;
    private String mFileNameSuffix;
    private volatile String mLogFileName;
    private long mFrequency;
    private Writer mLogFile;
    private final Object mLifecycleLock = new Object();
    private boolean mStarted;

    protected EventLogger(Path logDirectory, String fileNameSuffix, long frequency)
    {
        mLogDirectory = logDirectory;
        mFileNameSuffix = fileNameSuffix;
        mFrequency = frequency;
    }

    public String toString()
    {
        if(mLogFileName != null)
        {
            return mLogFileName;
        }
        else
        {
            return "Unknown";
        }
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
                FILE_IO_EXECUTOR.execute(() -> open(logFileName));
            }
        }
    }

    public void stop()
    {
        synchronized(mLifecycleLock)
        {
            if(mStarted)
            {
                mStarted = false;
                FILE_IO_EXECUTOR.execute(this::close);
            }
        }
    }

    protected void write(String eventLogEntry)
    {
        String entry = eventLogEntry != null ? eventLogEntry : "";

        synchronized(mLifecycleLock)
        {
            if(mStarted)
            {
                FILE_IO_EXECUTOR.execute(() -> writeToFile(entry));
            }
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
                mLogFile.write(eventLogEntry + "\n");
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
        FILE_IO_EXECUTOR.execute(drained::countDown);

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
