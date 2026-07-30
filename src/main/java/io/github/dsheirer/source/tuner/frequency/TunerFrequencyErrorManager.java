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

import io.github.dsheirer.source.ISourceEventProcessor;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.util.ThreadPool;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Aggregates child channel corrections and, when Auto-PPM is enabled, nudges the physical tuner PPM slowly toward the
 * average residual channel error.
 */
public class TunerFrequencyErrorManager implements ISourceEventProcessor
{
    private static final Logger LOGGER = LoggerFactory.getLogger(TunerFrequencyErrorManager.class);
    private static final double PPM_DIVISOR = 1.0 / 1_000_000.0;
    private static final long MAXIMUM_TUNER_ERROR_CORRECTION_PER_INTERVAL_HZ = 10;
    private static final long MINIMUM_CORRECTION_THRESHOLD_HZ = 50;
    private static final double MAXIMUM_AUTO_PPM_EXCURSION = 3.0;
    private static final long CHANNEL_PROCESSING_INTERVAL_MILLISECONDS = 500;
    private static final int TUNER_PROCESSING_INTERVAL_TICKS = 10;
    private final List<ChannelFrequencyErrorManager> mChannelManagers = new ArrayList<>();
    private final TunerController mTunerController;
    private ScheduledFuture<?> mScheduledFuture;
    private volatile boolean mEnabled = true;
    private boolean mShutdown;
    private double mTunerPPM;
    private long mTunerCorrection;
    private double mAutoCorrectionBaselinePPM;
    private boolean mApplyingAutomaticCorrection;
    private int mTunerProcessingTickCounter;

    public TunerFrequencyErrorManager(TunerController tunerController)
    {
        mTunerController = tunerController;
        mTunerController.addListener(this);
        mTunerPPM = mTunerController.getFrequencyCorrection();
        mTunerCorrection = toHertz(mTunerPPM);
        mAutoCorrectionBaselinePPM = mTunerPPM;
    }

    public void dispose()
    {
        mShutdown = true;
        stop();
    }

    @Override
    public void process(SourceEvent event) throws SourceException
    {
        if(event.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CHANGE ||
            event.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CORRECTION_CHANGE)
        {
            double ppm = mTunerController.getFrequencyCorrection();

            if(ppm != mTunerPPM)
            {
                mTunerPPM = ppm;
                mTunerCorrection = toHertz(mTunerPPM);
            }

            if(event.getEvent() == SourceEvent.Event.NOTIFICATION_FREQUENCY_CORRECTION_CHANGE &&
                !mApplyingAutomaticCorrection)
            {
                mAutoCorrectionBaselinePPM = ppm;
            }
        }
    }

    public void setEnabled(boolean enabled)
    {
        mTunerController.getLock().lock();

        try
        {
            if(enabled && !mEnabled)
            {
                mAutoCorrectionBaselinePPM = mTunerController.getFrequencyCorrection();
            }

            mEnabled = enabled;
        }
        finally
        {
            mTunerController.getLock().unlock();
        }
    }

    public boolean isEnabled()
    {
        return mEnabled;
    }

    private void process()
    {
        try
        {
            processTick();
        }
        catch(Throwable t)
        {
            LOGGER.error("Error while processing tuner/channel frequency correction", t);
        }
    }

    private void processTick()
    {
        List<ChannelFrequencyErrorManager> channelManagers = getChannelManagersSnapshot();

        for(ChannelFrequencyErrorManager channelManager: channelManagers)
        {
            try
            {
                channelManager.processChannel();
            }
            catch(Throwable t)
            {
                LOGGER.error("Error while processing channel frequency correction", t);
            }
        }

        mTunerProcessingTickCounter++;

        if(mTunerProcessingTickCounter >= TUNER_PROCESSING_INTERVAL_TICKS)
        {
            mTunerProcessingTickCounter = 0;
            processTunerCorrection(channelManagers);
        }
    }

    private List<ChannelFrequencyErrorManager> getChannelManagersSnapshot()
    {
        mTunerController.getLock().lock();

        try
        {
            return new ArrayList<>(mChannelManagers);
        }
        finally
        {
            mTunerController.getLock().unlock();
        }
    }

    private void processTunerCorrection(List<ChannelFrequencyErrorManager> channelManagers)
    {
        long requestedChangeHz = 0;
        int count = 0;

        for(ChannelFrequencyErrorManager manager: channelManagers)
        {
            if(manager.isCurrentForTunerProcessing())
            {
                requestedChangeHz += manager.getFrequencyError();
                count++;
                manager.clearCurrentFlag();
            }
        }

        if(count == 0)
        {
            mTunerController.setMeasuredFrequencyError(0);
            return;
        }

        requestedChangeHz /= count;
        mTunerController.setMeasuredFrequencyError((int)requestedChangeHz);

        if(Math.abs(requestedChangeHz) > MINIMUM_CORRECTION_THRESHOLD_HZ)
        {
            applyAutomaticCorrection(requestedChangeHz);
        }
    }

    /**
     * Applies one bounded automatic PPM adjustment. Package visibility supports deterministic safety-limit tests
     * without starting the scheduled channel-processing loop.
     */
    void applyAutomaticCorrection(long requestedChangeHz)
    {
        requestedChangeHz = clamp(requestedChangeHz, -MAXIMUM_TUNER_ERROR_CORRECTION_PER_INTERVAL_HZ,
            MAXIMUM_TUNER_ERROR_CORRECTION_PER_INTERVAL_HZ);

        mTunerController.getLock().lock();

        try
        {
            long frequency = mTunerController.getFrequency();

            if(mEnabled && frequency > 0)
            {
                double adjustment = requestedChangeHz / (frequency * PPM_DIVISOR);
                double currentPPM = mTunerController.getFrequencyCorrection();
                double minimumPPM = mAutoCorrectionBaselinePPM - MAXIMUM_AUTO_PPM_EXCURSION;
                double maximumPPM = mAutoCorrectionBaselinePPM + MAXIMUM_AUTO_PPM_EXCURSION;
                double adjustedPPM = clamp(currentPPM + adjustment, minimumPPM, maximumPPM);

                if(Double.compare(currentPPM, adjustedPPM) != 0)
                {
                    mApplyingAutomaticCorrection = true;

                    try
                    {
                        mTunerController.setFrequencyCorrection(adjustedPPM);
                    }
                    catch(SourceException e)
                    {
                        LOGGER.error("Error while adjusting tuner PPM value", e);
                    }
                    finally
                    {
                        mApplyingAutomaticCorrection = false;
                    }
                }
            }
        }
        finally
        {
            mTunerController.getLock().unlock();
        }
    }

    public double getTunerPPM()
    {
        return mTunerController.getFrequencyCorrection();
    }

    public long getTunerFrequencyCorrection()
    {
        return mTunerCorrection;
    }

    public void add(ChannelFrequencyErrorManager channelFrequencyErrorManager)
    {
        boolean startRequired = false;
        mTunerController.getLock().lock();

        try
        {
            if(!mChannelManagers.contains(channelFrequencyErrorManager))
            {
                mChannelManagers.add(channelFrequencyErrorManager);
                startRequired = true;
            }
        }
        finally
        {
            mTunerController.getLock().unlock();
        }

        if(startRequired)
        {
            start();
        }
    }

    public void remove(ChannelFrequencyErrorManager channelFrequencyErrorManager)
    {
        boolean stopRequired;
        mTunerController.getLock().lock();

        try
        {
            mChannelManagers.remove(channelFrequencyErrorManager);
            stopRequired = mChannelManagers.isEmpty();
        }
        finally
        {
            mTunerController.getLock().unlock();
        }

        if(stopRequired)
        {
            mTunerController.setMeasuredFrequencyError(0);
            stop();
        }
    }

    public synchronized void start()
    {
        if(!mShutdown && mScheduledFuture == null)
        {
            mScheduledFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(this::process,
                CHANNEL_PROCESSING_INTERVAL_MILLISECONDS, CHANNEL_PROCESSING_INTERVAL_MILLISECONDS,
                TimeUnit.MILLISECONDS);
        }
    }

    public synchronized void stop()
    {
        if(mScheduledFuture != null)
        {
            mScheduledFuture.cancel(true);
        }

        mScheduledFuture = null;
        mTunerProcessingTickCounter = 0;
    }

    private long toHertz(double ppm)
    {
        return (long)(mTunerController.getFrequency() * ppm * PPM_DIVISOR);
    }

    private long clamp(long value, long minimum, long maximum)
    {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private double clamp(double value, double minimum, double maximum)
    {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
