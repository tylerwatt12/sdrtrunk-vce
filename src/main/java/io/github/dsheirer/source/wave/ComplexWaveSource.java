/*
 * *****************************************************************************
 * Copyright (C) 2014-2025 Dennis Sheirer
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
package io.github.dsheirer.source.wave;

import io.github.dsheirer.buffer.FloatNativeBuffer;
import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.sample.ConversionUtils;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.SampleType;
import io.github.dsheirer.source.IControllableFileSource;
import io.github.dsheirer.source.IFrameLocationListener;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.util.ThreadPool;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.apache.commons.math3.util.FastMath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;

public class ComplexWaveSource extends Source implements IControllableFileSource, AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(ComplexWaveSource.class);

    private IFrameLocationListener mFrameLocationListener;
    private int mBufferSampleCount = 65536; //Complex samples per buffer
    private int mBytesPerFrame;
    private long mFrameCounter = 0;
    private long mFrequency = 0;
    private Listener<INativeBuffer> mListener;
    private volatile AudioInputStream mInputStream;
    private File mFile;
    private boolean mAutoReplay;
    private volatile ScheduledFuture<?> mReplayController;
    private final ReentrantLock mPlaybackLifecycleLock = new ReentrantLock();
    private final AtomicLong mReplayGeneration = new AtomicLong();
    private long mPendingReplayGeneration;

    /**
     * Constructs an instance with optional auto-replay at near real time.
     * @param file containing complex I/Q sample data
     * @param autoReplay to enable continuous looping, real-time playback of sample data
     */
    public ComplexWaveSource(File file, boolean autoReplay) throws IOException
    {
        if(file == null || !file.exists() || !supports(file))
        {
            throw new IOException("Empty or null file");
        }

        if(!supports(file))
        {
            throw new IOException("Unsupported file format");
        }

        mFile = file;
        mAutoReplay = autoReplay;
    }

    public ComplexWaveSource(File file) throws IOException
    {
        this(file, false);
    }

    @Override public SampleType getSampleType()
    {
        return SampleType.COMPLEX;
    }

    @Override
    public void setSourceEventListener(Listener<SourceEvent> listener)
    {
        //Not implemented
    }

    @Override
    public void removeSourceEventListener()
    {
        //Not implemented
    }

    @Override
    public Listener<SourceEvent> getSourceEventListener()
    {
        //Not implemented
        return null;
    }

    @Override
    public void reset()
    {
        stop();
        mFrameCounter = 0;
        start();
    }

    /**
     * Number of samples per buffer
     */
    public int getBufferSampleCount()
    {
        return mBufferSampleCount;
    }

    /**
     * Audio format for the currently opened or started source file.
     */
    public AudioFormat getAudioFormat()
    {
        AudioInputStream stream = mInputStream;
        if(stream == null)
        {
            throw new IllegalStateException("Source not opened or started");
        }

        return stream.getFormat();
    }

    @Override
    public void start()
    {
        boolean opened = false;
        long generation;
        mPlaybackLifecycleLock.lock();
        try
        {
            if(mPendingReplayGeneration != 0 || mReplayController != null && !mReplayController.isDone())
            {
                return;
            }
            if(mInputStream == null)
            {
                try
                {
                    opened = openInputStream();
                }
                catch(Exception e)
                {
                    mLog.error("Error", e);
                    return;
                }
            }
            generation = mReplayGeneration.incrementAndGet();
            mPendingReplayGeneration = generation;
        }
        finally { mPlaybackLifecycleLock.unlock(); }
        try
        {
            if(opened) broadcast(0);
        }
        finally
        {
            mPlaybackLifecycleLock.lock();
            try
            {
                if(mPendingReplayGeneration == generation)
                {
                    mPendingReplayGeneration = 0;
                    if(mAutoReplay && mReplayGeneration.get() == generation && mInputStream != null)
                    {
                        double buffersPerSecond = getSampleRate() / mBufferSampleCount;
                        long intervalMilliseconds = (long)(1000.0 / buffersPerSecond);
                        Runnable r = new ReplayController(mBufferSampleCount, generation);
                        mReplayController = ThreadPool.SCHEDULED.scheduleAtFixedRate(r, 0, intervalMilliseconds,
                            TimeUnit.MILLISECONDS);
                    }
                }
            }
            finally { mPlaybackLifecycleLock.unlock(); }
        }
    }

    @Override
    public void stop()
    {
        try
        {
            close();
        }
        catch(IOException e)
        {
            mLog.error("Error stopping complex wave source");
        }
    }

    @Override
    public long getFrameCount() throws IOException
    {
        return 0;
    }

    @Override
    public double getSampleRate()
    {
        AudioInputStream stream = mInputStream;
        if(stream != null)
        {
            return stream.getFormat().getSampleRate();
        }

        return 0;
    }

    /**
     * Returns the frequency set for this file.  Normally returns zero, but
     * the value can be set with setFrequency() method.
     */
    public long getFrequency()
    {
        return mFrequency;
    }

    /**
     * Changes the value returned from getFrequency() for this source.
     */
    public void setFrequency(long frequency)
    {
        mFrequency = frequency;
    }

    /**
     * Closes the source file
     */
    public void close() throws IOException
    {
        // Invalidate an outstanding read before waiting for an operator/EOF lifecycle operation.
        mReplayGeneration.incrementAndGet();
        mPlaybackLifecycleLock.lock();
        try
        {
            // A start already holding the lock may have scheduled after the first invalidation above.
            mReplayGeneration.incrementAndGet();
            mPendingReplayGeneration = 0;
            ScheduledFuture<?> replay = mReplayController;
            mReplayController = null;
            if(replay != null) replay.cancel(true);
            closeInputStream();
        }
        finally { mPlaybackLifecycleLock.unlock(); }
    }

    private void closeInputStream() throws IOException
    {
        AudioInputStream stream = mInputStream;
        mInputStream = null;
        if(stream != null) stream.close();
    }

    /**
     * Opens the source file for reading
     */
    public void open() throws IOException, UnsupportedAudioFileException
    {
        if(openInputStream()) broadcast(0);
    }

    private boolean openInputStream() throws IOException, UnsupportedAudioFileException
    {
        if(mInputStream == null)
        {
            mInputStream = AudioSystem.getAudioInputStream(mFile);

            AudioFormat format = mInputStream.getFormat();

            mBytesPerFrame = format.getFrameSize();

            if(format.getChannels() != 2 || format.getSampleSizeInBits() != 16)
            {
                throw new IOException("Unsupported Wave Format - EXPECTED: 2 " +
                        "channels 16-bit samples FOUND: " +
                        mInputStream.getFormat().getChannels() + " channels " +
                        mInputStream.getFormat().getSampleSizeInBits() + "-bit samples");
            }

            return true;
        }
        return false;
    }

    /**
     * Reads the number of frames and sends a buffer to the listener
     */
    @Override
    public void next(int frames) throws IOException
    {
        next(frames, true);
    }

    /**
     * Reads the number of frames and optionally sends the buffer(s) to the listener
     */
    public void next(int frames, boolean broadcast) throws IOException
    {
        readNext(frames, broadcast, mReplayGeneration.get());
    }

    /** Scheduled reads retain their producer's generation, including if restart occurs before this method begins. */
    protected void readNext(int frames, boolean broadcast, long generation) throws IOException
    {
        AudioInputStream stream = mInputStream;
        if(generation != mReplayGeneration.get()) return;
        if(stream != null)
        {
            byte[] buffer = new byte[mBytesPerFrame * frames];

        	/* Fill the buffer with samples from the file */
            int samplesRead = stream.read(buffer);

            if(generation != mReplayGeneration.get() || stream != mInputStream) return;

            if(samplesRead < 0)
            {
                throw new IOException("End of file reached");
            }

            mFrameCounter += samplesRead;

            broadcast(mFrameCounter);

            if(broadcast && mListener != null && generation == mReplayGeneration.get() && stream == mInputStream)
            {
                if(samplesRead < buffer.length)
                {
                    buffer = Arrays.copyOf(buffer, samplesRead);
                }

                float[] samples = ConversionUtils.convertFromSigned16BitSamples(buffer);
                mListener.receive(new FloatNativeBuffer(samples, System.currentTimeMillis(),
                        stream.getFormat().getSampleRate() / 1000.0f));
            }
        }
    }

    /**
     * Registers the listener to receive sample buffers as they are read from
     * the wave file
     */
    public void setListener(Listener<INativeBuffer> listener)
    {
        mListener = listener;
    }

    /**
     * Unregisters the listener from receiving sample buffers
     */
    public void removeListener(Listener<INativeBuffer> listener)
    {
        mListener = null;
    }

    @Override
    public File getFile()
    {
        return mFile;
    }

    private void broadcast(long byteLocation)
    {
        if(mFrameLocationListener != null)
        {
            // WAV files near 4 GB exceed an int byte offset, though their stereo I/Q frame count still fits.
            long frameLocation = byteLocation / mBytesPerFrame;
            mFrameLocationListener.frameLocationUpdated((int)Math.min(frameLocation, Integer.MAX_VALUE));
        }
    }

    @Override
    public void setListener(IFrameLocationListener listener)
    {
        mFrameLocationListener = listener;
    }

    @Override
    public void removeListener(IFrameLocationListener listener)
    {
        mFrameLocationListener = null;
    }

    /**
     * Indicates if the file is a supported audio file type
     */
    public static boolean supports(File file)
    {
        try(AudioInputStream ais = AudioSystem.getAudioInputStream(file))
        {
            AudioFormat format = ais.getFormat();

            if(format.getChannels() == 2 && format.getSampleSizeInBits() == 16)
            {
                return true;
            }
        }
        catch(Exception e)
        {
            //Do nothing, we'll return a default of false
        }

        return false;
    }

    public class ReplayController implements Runnable
    {
        private double mFramesPerInterval;
        private int mFramesRead;
        private int mIntervals;
        private final long mGeneration;

        public ReplayController(double framesPerInterval)
        {
            this(framesPerInterval, mReplayGeneration.get());
        }

        private ReplayController(double framesPerInterval, long generation)
        {
            mFramesPerInterval = framesPerInterval;
            mGeneration = generation;
        }

        @Override
        public void run()
        {
            if(mGeneration != mReplayGeneration.get()) return;
            try
            {
                readBuffer();
            }
            catch(IOException ioe)
            {
                // Keep one scheduled producer for an EOF loop. Never wait for a UI lifecycle operation, and never
                // let an outstanding canceled read create a replacement producer after stop/close.
                if(!mPlaybackLifecycleLock.tryLock()) return;
                boolean opened = false;
                try
                {
                    if(mGeneration != mReplayGeneration.get()) return;
                    closeInputStream();
                    mFrameCounter = 0;
                    mFramesRead = 0;
                    mIntervals = 0;
                    opened = openInputStream();
                }
                catch(IOException | UnsupportedAudioFileException exception)
                {
                    ScheduledFuture<?> replay = mReplayController;
                    mReplayController = null;
                    if(replay != null) replay.cancel(false);
                }
                finally { mPlaybackLifecycleLock.unlock(); }
                if(opened && mGeneration == mReplayGeneration.get())
                {
                    broadcast(0);
                    if(mGeneration == mReplayGeneration.get())
                    {
                        // The former reset/start loop scheduled its first buffer immediately. Preserve that timing
                        // without creating another producer or holding the lifecycle lock during a sample callback.
                        try { readBuffer(); }
                        catch(IOException ignored)
                        {
                            // An empty file supplies no samples. Retry at the next ordinary tick, never recursively.
                            mFramesRead = 0;
                            mIntervals = 0;
                        }
                    }
                }
            }
        }

        private void readBuffer() throws IOException
        {
            mIntervals++;
            int framesToRead = (int) FastMath.floor((mIntervals * mFramesPerInterval) - mFramesRead);
            readNext(framesToRead, true, mGeneration);
            mFramesRead += framesToRead;
        }
    }
}
