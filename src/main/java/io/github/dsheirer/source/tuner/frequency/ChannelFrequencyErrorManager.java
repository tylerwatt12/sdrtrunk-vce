/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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
package io.github.dsheirer.source.tuner.frequency;

import io.github.dsheirer.buffer.FloatAveragingBuffer;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;

/**
 * Applies decoder requested frequency correction to one channel source and reports that channel's correction state to
 * the tuner-level correction manager.
 */
public class ChannelFrequencyErrorManager implements Listener<SourceEvent>
{
    private static final long MAXIMUM_CHANNEL_ERROR_CORRECTION_PER_INTERVAL = 10;
    private final FloatAveragingBuffer mRequestedCorrectionAveragingBuffer = new FloatAveragingBuffer(6, 2);
    private final TunerChannelSource mTunerChannelSource;
    private final TunerFrequencyErrorManager mParent;
    private volatile boolean mRunning;
    private long mAppliedFrequencyCorrection;
    private long mAverageRequestedCorrection;
    private boolean mCurrentForTunerProcessing;
    private boolean mCurrentForChannelProcessing;
    private long mRequestTunerGeneration;
    private long mLatestRequestTimestamp;
    private volatile long mGeneration;
    private long mLastRequestedCorrection;
    private long mLastMeasurementTimestamp;
    private boolean mHasMeasurement;
    private volatile CorrectionSnapshot mCorrectionSnapshot;

    /** One worker-produced latest value; no observer callback or per-client work occurs on decoder threads. */
    public record CorrectionSnapshot(long timestamp, Long measurementTimestamp, Long requestedAdjustmentHz,
                                     long channelCorrectionHz, long tunerCenterFrequencyHz, double tunerCorrectionPPM,
                                     long tunerCorrectionHz, boolean autoPPM, long tunerGeneration, long generation)
    {
    }

    public CorrectionSnapshot getCorrectionSnapshot()
    {
        CorrectionSnapshot snapshot = mCorrectionSnapshot;
        return mRunning && snapshot != null && snapshot.generation() == mGeneration &&
            snapshot.tunerGeneration() == mParent.getTunerGeneration() ?
            snapshot : null;
    }

    public ChannelFrequencyErrorManager(TunerChannelSource tunerChannelSource, TunerFrequencyErrorManager parent)
    {
        mTunerChannelSource = tunerChannelSource;
        mParent = parent;
    }

    public synchronized long getFrequencyError()
    {
        return mAppliedFrequencyCorrection;
    }

    @Override
    public synchronized void receive(SourceEvent sourceEvent)
    {
        if(mRunning && sourceEvent.getEvent() == SourceEvent.Event.REQUEST_FREQUENCY_CORRECTION)
        {
            long requestedCorrection = sourceEvent.getValue().longValue();
            mAverageRequestedCorrection = (long)mRequestedCorrectionAveragingBuffer.get(requestedCorrection);
            mCurrentForChannelProcessing = true;
            mRequestTunerGeneration = mParent.getTunerGeneration();
            mLatestRequestTimestamp = System.currentTimeMillis();
        }
    }

    public synchronized boolean isCurrentForTunerProcessing()
    {
        return mCurrentForTunerProcessing;
    }

    public synchronized void clearCurrentFlag()
    {
        mCurrentForTunerProcessing = false;
    }

    void processChannel()
    {
        long appliedFrequencyCorrection = 0;
        boolean applyFrequencyCorrection = false;
        long generation;
        long tunerGeneration;
        long requestedCorrection;
        long measurementTimestamp;
        long channelCorrection;
        boolean hasMeasurement;

        synchronized(this)
        {
            if(!mRunning)
            {
                return;
            }

            generation = mGeneration;

            if(mCurrentForChannelProcessing)
            {
                long partialCorrection = clamp(mAverageRequestedCorrection, -MAXIMUM_CHANNEL_ERROR_CORRECTION_PER_INTERVAL,
                    MAXIMUM_CHANNEL_ERROR_CORRECTION_PER_INTERVAL);

                if(partialCorrection != 0)
                {
                    mAppliedFrequencyCorrection += partialCorrection;
                    mRequestedCorrectionAveragingBuffer.reset();
                    appliedFrequencyCorrection = mAppliedFrequencyCorrection;
                    applyFrequencyCorrection = true;
                }

                if(mRequestTunerGeneration == mParent.getTunerGeneration())
                {
                    mLastRequestedCorrection = mAverageRequestedCorrection;
                    mLastMeasurementTimestamp = mLatestRequestTimestamp;
                    mHasMeasurement = true;
                }
                mAverageRequestedCorrection = 0;
                mCurrentForTunerProcessing = true;
                mCurrentForChannelProcessing = false;
            }

            tunerGeneration = mParent.getTunerGeneration();
            if(mRequestTunerGeneration != tunerGeneration)
            {
                mHasMeasurement = false;
            }
            requestedCorrection = mLastRequestedCorrection;
            measurementTimestamp = mLastMeasurementTimestamp;
            hasMeasurement = mHasMeasurement;
            channelCorrection = mAppliedFrequencyCorrection;
        }

        if(applyFrequencyCorrection)
        {
            mTunerChannelSource.setFrequencyCorrection(appliedFrequencyCorrection);
        }

        updateSnapshot(generation, tunerGeneration, requestedCorrection, measurementTimestamp, hasMeasurement,
            channelCorrection);
    }

    private void updateSnapshot(long expectedGeneration, long tunerGeneration, long requestedCorrection,
                                long measurementTimestamp, boolean hasMeasurement, long channelCorrection)
    {
        if(!mRunning || expectedGeneration != mGeneration)
        {
            return;
        }

        //Primitive capture above shares only the existing correction lock. Allocation and all observation metadata
        //work stay outside that lock, so a decoder request cannot wait for diagnostic projection or tuner settings.
        long center = mParent.getTunerCenterFrequency();
        double ppm = mParent.getTunerPPM();
        CorrectionSnapshot snapshot = new CorrectionSnapshot(System.currentTimeMillis(),
            hasMeasurement ? measurementTimestamp : null, hasMeasurement ? requestedCorrection : null,
            channelCorrection, center, ppm, Math.round(center * ppm / 1_000_000.0), mParent.isEnabled(),
            tunerGeneration, expectedGeneration);
        if(mRunning && mGeneration == expectedGeneration && mParent.getTunerGeneration() == tunerGeneration)
        {
            mCorrectionSnapshot = snapshot;
        }
    }

    public void start()
    {
        if(mParent != null)
        {
            synchronized(this)
            {
                mAverageRequestedCorrection = 0;
                mCurrentForTunerProcessing = false;
                mCurrentForChannelProcessing = false;
                mRequestedCorrectionAveragingBuffer.reset();
                mLastRequestedCorrection = 0;
                mLastMeasurementTimestamp = 0;
                mHasMeasurement = false;
                mCorrectionSnapshot = null;
                mGeneration++;
                mRunning = true;
            }

            mParent.add(this);
        }
    }

    public void stop()
    {
        boolean clearFrequencyCorrection;

        synchronized(this)
        {
            mRunning = false;
            clearFrequencyCorrection = mAppliedFrequencyCorrection != 0;
            mAppliedFrequencyCorrection = 0;
            mAverageRequestedCorrection = 0;
            mCurrentForTunerProcessing = false;
            mCurrentForChannelProcessing = false;
            mRequestedCorrectionAveragingBuffer.reset();
            mLastRequestedCorrection = 0;
            mLastMeasurementTimestamp = 0;
            mHasMeasurement = false;
            mCorrectionSnapshot = null;
            mGeneration++;
        }

        if(clearFrequencyCorrection)
        {
            mTunerChannelSource.setFrequencyCorrection(0);
        }

        if(mParent != null)
        {
            mParent.remove(this);
        }
    }

    private long clamp(long value, long minimum, long maximum)
    {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
