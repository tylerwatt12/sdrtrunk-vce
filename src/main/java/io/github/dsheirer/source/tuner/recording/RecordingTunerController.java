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
package io.github.dsheirer.source.tuner.recording;

import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.TunerType;
import io.github.dsheirer.source.wave.ComplexWaveSource;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Tuner controller for playback of baseband complex recording files.
 */
public class RecordingTunerController extends TunerController
{
    private static final Logger mLog = LoggerFactory.getLogger(RecordingTunerController.class);

    public static final int DC_NOISE_BANDWIDTH = 0;
    public static final double USABLE_BANDWIDTH_PERCENTAGE = 1.00;
    private volatile ComplexWaveSource mComplexWaveSource;
    private volatile boolean mPlaybackRunning;
    private volatile long mLastSampleAtEpochMs;
    private volatile long mLastSampleAtNanos;
    private final AtomicLong mSampleBufferCount = new AtomicLong();
    private String mPath;
    private long mCenterFrequency;

    /**
     * Constructs an instance
     * @param tunerErrorListener to receive errors from this controller
      */
    public RecordingTunerController(ITunerErrorListener tunerErrorListener, String path, long centerFrequency)
    {
        super(tunerErrorListener);
        mPath = path;
        mCenterFrequency = centerFrequency;
        if(mCenterFrequency == 0)
        {
            mCenterFrequency = 100000000;
        }

        setMinimumFrequency(1000000l);
        setMaximumFrequency(3000000000l);
        setMiddleUnusableHalfBandwidth(DC_NOISE_BANDWIDTH);
        setUsableBandwidthPercentage(USABLE_BANDWIDTH_PERCENTAGE);
    }

    @Override
    public void start() throws SourceException
    {
        if(mComplexWaveSource == null)
        {
            try
            {
                RecordingTunerFileCatalog.requireManagedPlaybackFile(mPath);
                mComplexWaveSource = new ComplexWaveSource(new File(mPath), true);
            }
            catch(IOException ioe)
            {
                mLog.error("Error opening recording file [" + mPath + "]", ioe);
                setErrorMessage(ioe.getMessage() + " File:" + mPath);
                return;
            }

            ComplexWaveSource source = mComplexWaveSource;
            source.setListener(buffer ->
            {
                // A stopped or replaced WAV source must not publish a late buffer into its former tuner.
                if(mPlaybackRunning && mComplexWaveSource == source)
                {
                    mLastSampleAtNanos = System.nanoTime();
                    mLastSampleAtEpochMs = System.currentTimeMillis();
                    mSampleBufferCount.incrementAndGet();
                    broadcast(buffer);
                }
            });

            try
            {
                source.open();
                // Publish valid tuner metadata before the first scheduled WAV buffer can reach any consumer.
                mFrequencyController.setFrequency(mCenterFrequency);
                mFrequencyController.setSampleRate((int)source.getSampleRate());
                mFrequencyController.broadcast(SourceEvent.recordingFileLoaded());
                mLastSampleAtEpochMs = 0;
                mLastSampleAtNanos = 0;
                mSampleBufferCount.set(0);
                mPlaybackRunning = true;
                source.start();
                mLog.info("Tuner Recording Loaded: " + mPath);
            }
            catch(IOException | UnsupportedAudioFileException | SourceException e)
            {
                stop();
                mLog.error("Error starting recording file [" + mPath + "]", e);
                setErrorMessage(e.getMessage() + " File:" + mPath);
            }
        }
    }

    @Override
    public void stop()
    {
        mPlaybackRunning = false;
        ComplexWaveSource source = mComplexWaveSource;
        mComplexWaveSource = null;
        if(source != null)
        {
            try
            {
                source.stop();
                source.close();
            }
            catch(IOException ioe)
            {
                mLog.error("Ignoring - error stopping baseband recording playback - " + ioe.getLocalizedMessage());
            }
        }
    }

    /** True while ordinary WAV playback is enabled, including the brief automatic EOF/replay transition. */
    public boolean isPlaybackRunning()
    {
        return mPlaybackRunning && mComplexWaveSource != null;
    }

    /** Read-only source health. No sample listener or diagnostic worker is created by this query. */
    public PlaybackStatus getPlaybackStatus()
    {
        long staleAfterMilliseconds = Math.max(1_500L, 3L * Math.max(1L, getBufferDuration()));
        long lastSampleNanos = mLastSampleAtNanos;
        String state = !isPlaybackRunning() ? "stopped" : lastSampleNanos != 0 &&
            System.nanoTime() - lastSampleNanos <= staleAfterMilliseconds * 1_000_000L ? "playing" : "waiting";
        return new PlaybackStatus(state, mLastSampleAtEpochMs, mSampleBufferCount.get(), staleAfterMilliseconds);
    }

    public record PlaybackStatus(String state, long lastSampleAtEpochMs, long sampleBufferCount,
                                 long staleAfterMilliseconds) { }

    @Override
    public TunerType getTunerType()
    {
        return TunerType.RECORDING;
    }

    @Override
    public int getBufferSampleCount()
    {
        ComplexWaveSource source = mComplexWaveSource;
        if(source != null)
        {
            return source.getBufferSampleCount();
        }

        return 0;
    }

    @Override
    public void setFrequency(long frequency) throws SourceException
    {
        /* no action required */
    }

    /**
     * Current center frequency for this tuner
     * @throws SourceException
     */
    @Override
    public long getTunedFrequency() throws SourceException
    {
        return mCenterFrequency;
    }

    /**
     * Sets the center frequency for this tuner
     * @param frequency in hertz
     * @throws SourceException
     */
    @Override
    public void setTunedFrequency(long frequency) throws SourceException
    {
        mCenterFrequency = frequency;
    }

    /**
     * Current sample rate for this tuner controller
     */
    @Override
    public double getCurrentSampleRate()
    {
        // EOF looping briefly closes the file stream; the recording's validated rate remains unchanged.
        return isPlaybackRunning() ? getSampleRate() : 0d;
    }
}
