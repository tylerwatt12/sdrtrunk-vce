/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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
package io.github.dsheirer.record.wave;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.sample.ConversionUtils;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import io.github.dsheirer.source.ISourceEventListener;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.util.TimeStamp;
import io.github.dsheirer.util.concurrent.BoundedMpscReferenceQueue;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;

/**
 * WAVE audio recorder module for recording complex (I&Q) samples to a wave file
 */
public class NativeBufferWaveRecorder extends Module implements Listener<INativeBuffer>, ISourceEventListener
{
    private static final Logger mLog = LoggerFactory.getLogger(NativeBufferWaveRecorder.class);
    private static final long STATUS_UPDATE_BYTE_INTERVAL = 1_048_576;
    private static final long MAX_RECORDING_SIZE = Integer.MAX_VALUE * 2l;
    private static final int MAX_PENDING_BUFFERS = 64;
    private static final String OVERFLOW_REASON = "Recording stopped because the bounded recording handoff could not keep up. " +
        "The partial WAV is marked incomplete; receiving and channels were not stopped.";
    private static final String SAMPLE_RATE_REASON = "Recording stopped because the tuner sample rate changed. " +
        "The partial WAV is marked incomplete; receiving and channels were not stopped.";
    private static final String WRITE_FAILURE_REASON = "Recording stopped because the baseband WAV could not be written. " +
        "The partial WAV is marked incomplete; receiving and channels were not stopped.";
    private static final long IDLE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final AtomicLong FILE_SEQUENCE = new AtomicLong();

    private final String mFilePrefix;
    private final IRecordingStatusListener mStatusListener;
    private volatile float mSampleRate;
    private volatile RecordingRun mRun;
    private volatile long mLastDroppedBufferCount;
    private volatile Consumer<String> mFailureHandler = ignored -> { };

    public NativeBufferWaveRecorder(float sampleRate, String filePrefix, IRecordingStatusListener statusListener)
    {
        mFilePrefix = filePrefix;
        mStatusListener = statusListener;
        setSampleRate(sampleRate);
    }

    /** Invoked off the receiver thread when the diagnostic capture must be aborted. */
    public void setFailureHandler(Consumer<String> failureHandler)
    {
        mFailureHandler = Objects.requireNonNull(failureHandler);
    }

    public boolean isRunning()
    {
        RecordingRun run = mRun;
        return run != null && run.mRunning.get();
    }

    public long getDroppedBufferCount()
    {
        RecordingRun run = mRun;
        return run != null ? run.mDroppedBufferCount.get() : mLastDroppedBufferCount;
    }

    public int getMaximumPendingBuffers()
    {
        return MAX_PENDING_BUFFERS;
    }

    public void setSampleRate(float sampleRate)
    {
        mSampleRate = sampleRate;
        RecordingRun run = mRun;
        if(run != null)
        {
            if(run.mAudioFormat.getSampleRate() != sampleRate)
            {
                requestFailure(run, SAMPLE_RATE_REASON);
            }
        }
    }

    private String getFileName()
    {
        StringBuilder sb = new StringBuilder();
        sb.append(mFilePrefix);
        sb.append("_");
        sb.append(TimeStamp.getTimeStamp("_"));
        sb.append("_");
        sb.append(FILE_SEQUENCE.incrementAndGet());
        sb.append(".wav");
        return sb.toString();
    }

    public synchronized void start()
    {
        if(mRun != null)
        {
            return;
        }

        RecordingRun run = null;
        boolean workerStarted = false;
        try
        {
            run = new RecordingRun(new AudioFormat(mSampleRate, 16, 2, true, false));
            run.mFilePath = getFileName();
            run.mWriter = new NativeBufferWaveWriter(run, run.mAudioFormat, Paths.get(run.mFilePath));
            mStatusListener.update(run.mRecordingCount, run.mFilePath, 0);
            RecordingRun startedRun = run;
            run.mWorker = new Thread(() -> runWriter(startedRun), "sdrtrunk baseband recording writer");
            run.mWorker.setDaemon(true);
            run.mWorker.setPriority(Thread.NORM_PRIORITY - 1);
            run.mFailureCheck = ThreadPool.SCHEDULED.scheduleAtFixedRate(() -> checkFailure(startedRun), 100, 100,
                TimeUnit.MILLISECONDS);
            mRun = run;
            run.mWorker.start();
            workerStarted = true;
        }
        catch(IOException | RuntimeException exception)
        {
            mLog.error("Error starting complex baseband recorder", exception);
            mRun = null;
            if(run != null)
            {
                run.mRunning.set(false);
                if(run.mFailureCheck != null)
                {
                    run.mFailureCheck.cancel(false);
                }
                if(workerStarted)
                {
                    LockSupport.unpark(run.mWorker);
                }
                else
                {
                    closeWriter(run);
                }
            }
        }
    }

    private void requestFailure(RecordingRun run, String reason)
    {
        run.mFailureReason.compareAndSet(null, reason);
        LockSupport.unpark(run.mWorker);
    }

    private void checkFailure(RecordingRun run)
    {
        String reason = run.mFailureReason.get();
        if(reason != null && run.mFailureNotified.compareAndSet(false, true))
        {
            synchronized(this)
            {
                // A canceled check from an old run must never stop a newly started recording.
                if(mRun != run)
                {
                    return;
                }
                stop();
            }
            try
            {
                mFailureHandler.accept(reason);
            }
            catch(RuntimeException exception)
            {
                mLog.error("Unable to report failed baseband recording", exception);
            }
        }
    }

    public synchronized void stop()
    {
        RecordingRun run = mRun;
        if(run != null)
        {
            mRun = null;
            mLastDroppedBufferCount = run.mDroppedBufferCount.get();
            run.mRunning.set(false);
            if(run.mFailureCheck != null)
            {
                run.mFailureCheck.cancel(false);
            }
            LockSupport.unpark(run.mWorker);
        }
    }

    private void runWriter(RecordingRun run)
    {
        try
        {
            while(run.mFailureReason.get() == null)
            {
                INativeBuffer buffer = run.mPendingBuffers.poll();
                if(buffer == null)
                {
                    // Manual stop detaches the producer immediately, but accepted buffers still belong in the
                    // complete WAV. Wait for a callback already inside receive() before declaring the queue empty.
                    if(!run.mRunning.get() && run.mActiveReceivers.get() == 0)
                    {
                        break;
                    }
                    LockSupport.parkNanos(this, IDLE_PARK_NANOS);
                }
                else
                {
                    try
                    {
                        run.mWriter.receive(buffer);
                    }
                    catch(RuntimeException exception)
                    {
                        mLog.error("Error writing baseband I/Q buffer", exception);
                        requestFailure(run, WRITE_FAILURE_REASON);
                    }
                }
            }
        }
        finally
        {
            //Only this thread consumes this run's queue. A late producer offer is isolated to this retired run.
            run.mPendingBuffers.clear();
            closeWriter(run);
        }
    }

    private void closeWriter(RecordingRun run)
    {
        NativeBufferWaveWriter writer = run.mWriter;
        if(writer == null)
        {
            return;
        }

        try
        {
            writer.close();
            if(run.mFailureReason.get() != null)
            {
                Path file = Paths.get(run.mFilePath);
                Path incompleteFile = file.resolveSibling(
                    file.getFileName().toString().replaceFirst("\\.wav$", ".incomplete.wav"));
                Files.move(file, incompleteFile);
            }
        }
        catch(IOException exception)
        {
            mLog.error("Error closing baseband I/Q recorder", exception);
        }
    }

    @Override
    public void receive(INativeBuffer nativeBuffer)
    {
        RecordingRun run = mRun;
        if(run == null)
        {
            return;
        }

        run.mActiveReceivers.incrementAndGet();
        try
        {
            if(!run.mRunning.get() || run.mFailureReason.get() != null)
            {
                return;
            }

            if(run.mPendingBuffers.offer(nativeBuffer))
            {
                LockSupport.unpark(run.mWorker);
            }
            else if(run.mRunning.get())
            {
                run.mDroppedBufferCount.incrementAndGet();
                requestFailure(run, OVERFLOW_REASON);
            }
        }
        finally
        {
            if(run.mActiveReceivers.decrementAndGet() == 0 && !run.mRunning.get())
            {
                LockSupport.unpark(run.mWorker);
            }
        }
    }

    public Listener<INativeBuffer> getReusableComplexBufferListener()
    {
        return this;
    }

    @Override
    public void reset() { /* no action required */ }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        return sourceEvent ->
        {
            if(sourceEvent.getEvent() == SourceEvent.Event.NOTIFICATION_SAMPLE_RATE_CHANGE)
            {
                setSampleRate(sourceEvent.getValue().floatValue());
            }
        };
    }

    private final class RecordingRun
    {
        private final AudioFormat mAudioFormat;
        private final BoundedMpscReferenceQueue<INativeBuffer> mPendingBuffers =
            new BoundedMpscReferenceQueue<>(MAX_PENDING_BUFFERS);
        private final AtomicBoolean mRunning = new AtomicBoolean(true);
        private final AtomicInteger mActiveReceivers = new AtomicInteger();
        private final AtomicLong mDroppedBufferCount = new AtomicLong();
        private final AtomicReference<String> mFailureReason = new AtomicReference<>();
        private final AtomicBoolean mFailureNotified = new AtomicBoolean();
        private Thread mWorker;
        private ScheduledFuture<?> mFailureCheck;
        private NativeBufferWaveWriter mWriter;
        private String mFilePath;
        private long mCurrentSize;
        private long mLastReportedSize;
        private int mRecordingCount = 1;

        private RecordingRun(AudioFormat audioFormat)
        {
            mAudioFormat = Objects.requireNonNull(audioFormat);
        }
    }

    /** Wave writer owned by exactly one recording run and called only by its writer thread. */
    private class NativeBufferWaveWriter extends WaveWriter implements Listener<INativeBuffer>
    {
        private final RecordingRun mRun;

        private NativeBufferWaveWriter(RecordingRun run, AudioFormat format, Path file) throws IOException
        {
            super(format, file);
            mRun = run;
        }

        @Override
        public void receive(INativeBuffer nativeBuffer)
        {
            Iterator<InterleavedComplexSamples> iterator = nativeBuffer.iteratorInterleaved();

            while(iterator.hasNext() && mRun.mFailureReason.get() == null)
            {
                try
                {
                    ByteBuffer data = ConversionUtils.convertToSigned16BitSamples(iterator.next());

                    if((mRun.mCurrentSize + data.array().length) > MAX_RECORDING_SIZE)
                    {
                        rollRecording();
                    }

                    if(mRun.mWriter == null || mRun.mFailureReason.get() != null)
                    {
                        return;
                    }

                    mRun.mWriter.writeData(data);

                    mRun.mCurrentSize += data.array().length;
                    if(mRun.mCurrentSize > (mRun.mLastReportedSize + STATUS_UPDATE_BYTE_INTERVAL) &&
                        mRun.mRunning.get() && mRun.mFailureReason.get() == null)
                    {
                        mStatusListener.update(mRun.mRecordingCount, mRun.mFilePath, mRun.mCurrentSize);
                        mRun.mLastReportedSize = mRun.mCurrentSize;
                    }
                }
                catch(IOException exception)
                {
                    mLog.error("I/O exception while writing I/Q buffers to wave recorder", exception);
                    requestFailure(mRun, WRITE_FAILURE_REASON);
                }
            }
        }

        /**
         * Rollover the recording once the current recording file size is full
         */
        private void rollRecording() throws IOException
        {
            if(mRun.mWriter != null)
            {
                NativeBufferWaveWriter previous = mRun.mWriter;
                mRun.mWriter = null;
                previous.close();
                mRun.mCurrentSize = 0;
                mRun.mLastReportedSize = 0;
                if(mRun.mFailureReason.get() != null)
                {
                    return;
                }
                mRun.mFilePath = getFileName();
                mRun.mWriter = new NativeBufferWaveWriter(mRun, mRun.mAudioFormat, Paths.get(mRun.mFilePath));
                if(mRun.mRunning.get() && mRun.mFailureReason.get() == null)
                {
                    mStatusListener.update(++mRun.mRecordingCount, mRun.mFilePath, 0);
                }
            }
        }
    }
}
