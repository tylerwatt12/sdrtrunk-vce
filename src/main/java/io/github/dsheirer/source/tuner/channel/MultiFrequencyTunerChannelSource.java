/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
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

package io.github.dsheirer.source.tuner.channel;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.heartbeat.Heartbeat;
import io.github.dsheirer.source.tuner.channel.rotation.FrequencyLockChangeRequest;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.util.ThreadPool;
import java.util.List;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Multiple-frequency tuner channel source.  Provides a wrapper around a tuner channel source and listens for external
 * source events requests to change frequency.  Maintains an ordered list of frequencies and automatically tears down
 * an existing tuner channel source and obtains a new one with the next frequency from the list, on request.
 */
public class MultiFrequencyTunerChannelSource extends TunerChannelSource
{
    private static final Logger mLog = LoggerFactory.getLogger(MultiFrequencyTunerChannelSource.class);
    private TunerManager mTunerManager;
    private TunerChannelSource mTunerChannelSource;
    private List<Long> mFrequencies;
    private Set<Long> mLockedFrequencies = ConcurrentHashMap.newKeySet();
    private int mFrequencyListPointer = 0;
    private ChannelSpecification mChannelSpecification;
    private Long mMinimumFrequency;
    private Long mMaximumFrequency;
    private Listener<ComplexSamples> mComplexSamplesListener;
    private Listener<Heartbeat> mHeartbeatListener;
    private String mPreferredTuner;
    private boolean mChangingChannels;
    private long mTargetFrequency;
    private boolean mStarted;
    private ConsumerSourceEventAdapter mConsumerSourceEventAdapter = new ConsumerSourceEventAdapter();

    public MultiFrequencyTunerChannelSource(TunerManager tunerManager, TunerChannelSource tunerChannelSource,
                                            List<Long> frequencies, ChannelSpecification channelSpecification,
                                            String preferredTuner, String threadName, Long minimumFrequency,
                                            Long maximumFrequency)
    {
        super(null, tunerChannelSource.getTunerChannel(), threadName, null);
        mTunerManager = tunerManager;
        mTunerChannelSource = tunerChannelSource;
        mTunerChannelSource.setSourceEventListener(mConsumerSourceEventAdapter);
        mFrequencies = frequencies;

        int initialFrequencyIndex = mFrequencies.indexOf(tunerChannelSource.getFrequency());

        if(initialFrequencyIndex >= 0)
        {
            mFrequencyListPointer = initialFrequencyIndex;
        }

        mChannelSpecification = channelSpecification;
        mPreferredTuner = preferredTuner;
        mMinimumFrequency = minimumFrequency;
        mMaximumFrequency = maximumFrequency;
    }

    /**
     * Cycles this source to use the next frequency in the list.  If no other frequencies are available,
     * because of frequency locking, ignore the rotate request.
     */
    private synchronized void rotate()
    {
        if(!mChangingChannels)
        {
            rotateTo(getNextFrequency(), false);
        }
    }

    /**
     * Changes this source to the requested frequency.  Targeted selections ignore traffic-channel frequency locks so
     * that a decoder-requested control channel always wins.
     */
    private synchronized void rotateTo(long frequency, boolean targeted)
    {
        if(!mStarted || frequency == 0)
        {
            return;
        }

        if(mTunerChannelSource != null && frequency == getFrequency())
        {
            mFrequencyListPointer = mFrequencies.indexOf(frequency);
            if(mTargetFrequency == frequency)
            {
                mTargetFrequency = 0;
            }
            return;
        }

        if(targeted)
        {
            mTargetFrequency = frequency;
            int frequencyIndex = mFrequencies.indexOf(frequency);

            if(frequencyIndex >= 0)
            {
                mFrequencyListPointer = frequencyIndex;
            }
        }

        if(!mChangingChannels)
        {
            mChangingChannels = true;

            if(mTunerChannelSource != null)
            {
                //Shutdown the existing tuner channel source
                stopAndDispose(mTunerChannelSource);
                mTunerChannelSource = null;
            }

            if(targeted)
            {
                //The old stream is fully detached.  Reset channel/decoder/audio state before attempting the target so
                //a failed acquisition cannot leave an old-frequency call logically open.
                broadcastConsumerSourceEvent(SourceEvent.stopSampleStreamNotification(this));
            }

            //Request the next tuner channel source
            getNextSource(getTunerChannel(frequency));
        }
    }

    /**
     * Persistently attempts to get the next tuner channel source using the next frequency in the list.  This method
     * should only be invoked by the nextFrequency() method that protects access via the mChangingChannels flag.
     *
     * Note: if this channel source is shutdown by the external consumer, the mStarted flag will be set to false and
     * the persistent attempts will stop.
     */
    private synchronized void getNextSource(TunerChannel nextChannel)
    {
        if(mStarted)
        {
            Source source = mTunerManager.getSource(nextChannel, mChannelSpecification, mPreferredTuner, mThreadName,
                getAllFrequencyChannels());

            if(source instanceof TunerChannelSource candidate)
            {
                try
                {
                    candidate.setSourceEventListener(mConsumerSourceEventAdapter);
                    candidate.setListener(mComplexSamplesListener);
                    candidate.addHeartbeatListener(mHeartbeatListener);
                    candidate.start();
                    mTunerChannelSource = candidate;
                    mTunerChannel = nextChannel;

                    if(mTargetFrequency == nextChannel.getFrequency())
                    {
                        mTargetFrequency = 0;
                    }

                    mChangingChannels = false;

                    getSourceEventListener().receive(
                        SourceEvent.frequencyRotationSuccessNotification(this, nextChannel.getFrequency()));
                }
                catch(RuntimeException exception)
                {
                    mLog.warn("Unable to start replacement tuner channel source for frequency [{}]",
                        nextChannel.getFrequency(), exception);
                    stopAndDispose(candidate);
                }
            }

            //If we don't get a channel source because a tuner is not available or none of the available tuners can
            //support the frequency, then persistently attempt to get a source by iterating the frequency list
            if(mTunerChannelSource == null)
            {
                getSourceEventListener().receive(SourceEvent.frequencyRotationFailureNotification(this, nextChannel.getFrequency()));
                ThreadPool.SCHEDULED.schedule(this::retryNextSource, 500, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Stops and detaches a one-use tuner channel source.
     */
    private void stopAndDispose(TunerChannelSource source)
    {
        try
        {
            source.stop();
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Error stopping tuner channel source during frequency rotation", exception);
        }

        source.setListener(null);
        source.removeSourceEventListener();
        source.removeHeartbeatListener(mHeartbeatListener);
        source.dispose();
    }

    /**
     * Retries the selected control frequency, or continues ordinary sequential rotation when no target is active.
     */
    synchronized void retryNextSource()
    {
        if(mStarted && mChangingChannels)
        {
            long targetFrequency = mTargetFrequency;
            long retryFrequency = targetFrequency > 0 ? targetFrequency : getNextFrequency();

            if(retryFrequency > 0)
            {
                getNextSource(getTunerChannel(retryFrequency));
            }
            else
            {
                mChangingChannels = false;
            }
        }
    }

    @Override
    public synchronized void start()
    {
        //The initial source should not be null
        if(mTunerChannelSource != null)
        {
            mStarted = true;

            try
            {
                mTunerChannelSource.start();
            }
            catch(RuntimeException exception)
            {
                mStarted = false;
                throw exception;
            }
        }
    }

    @Override
    public synchronized void stop()
    {
        mStarted = false;
        mTargetFrequency = 0;
        mChangingChannels = false;

        if(mTunerChannelSource != null)
        {
            mTunerChannelSource.stop();
            mTunerChannelSource.removeSourceEventListener();
            mTunerChannelSource = null;
        }
    }

    /**
     * Processes requests to lock or unlock frequencies for this source that are received over the processing chain
     * event bus.  A locked frequency will not be used in the frequency rotation list until it is unlocked.
     *
     * @param request to lock or unlock a frequency
     */
    @Subscribe
    public void process(FrequencyLockChangeRequest request)
    {
        if(request.isLockRequest())
        {
            mLockedFrequencies.add(request.getFrequency());
        }
        else if(request.isUnlockRequest())
        {
            mLockedFrequencies.remove(request.getFrequency());
        }
    }

    /**
     * Identifies the next frequency in the list to use, while respecting the locked frequencies list.
     *
     * @return next frequency or 0 if there currently are no other frequencies than the frequency currently in use.
     */
    private long getNextFrequency()
    {
        if(mFrequencies.size() <= 1)
        {
            return 0;
        }

        int attempts = 0;

        mFrequencyListPointer = ++mFrequencyListPointer % mFrequencies.size();

        long frequency = mFrequencies.get(mFrequencyListPointer);

        while((mLockedFrequencies.contains(frequency) || frequency == getFrequency()) && attempts < mFrequencies.size())
        {
            //If we loop through the entire list back to the current frequency, return 0;
            if(frequency == getFrequency())
            {
                return 0;
            }

            mFrequencyListPointer = ++mFrequencyListPointer % mFrequencies.size();

            frequency = mFrequencies.get(mFrequencyListPointer);

            attempts++;
        }

        return frequency;
    }

    /**
     * Provides the next tuner channel from the frequency list.
     */
    private TunerChannel getTunerChannel(long frequency)
    {
        return new TunerChannel(frequency, mChannelSpecification.getBandwidth());
    }

    /**
     * Returns all configured frequencies as a sorted set of tuner channels, for use in polyphase
     * center-frequency selection so the tuner can be positioned for the full frequency envelope.
     */
    private SortedSet<TunerChannel> getAllFrequencyChannels()
    {
        SortedSet<TunerChannel> channels = new TreeSet<>();

        if(hasFrequencyEnvelope())
        {
            long minimumFrequency = mMinimumFrequency;
            long maximumFrequency = mMaximumFrequency;
            long centerFrequency = minimumFrequency + ((maximumFrequency - minimumFrequency) / 2);
            int bandwidth = (int)((maximumFrequency - minimumFrequency) + mChannelSpecification.getBandwidth());
            channels.add(new TunerChannel(centerFrequency, bandwidth));
            return channels;
        }

        for(long frequency: mFrequencies)
        {
            channels.add(new TunerChannel(frequency, mChannelSpecification.getBandwidth()));
        }

        return channels;
    }

    /**
     * Indicates if this source has a usable frequency envelope for tuner centering.
     */
    private boolean hasFrequencyEnvelope()
    {
        return mMinimumFrequency != null && mMaximumFrequency != null &&
            mMinimumFrequency > 0 && mMaximumFrequency >= mMinimumFrequency;
    }

    /**
     * Adds the heartbeat listener to receive heartbeats from the enclosed tuner channel source.
     */
    @Override
    public void addHeartbeatListener(Listener<Heartbeat> listener)
    {
        mHeartbeatListener = listener;

        if(mTunerChannelSource != null)
        {
            mTunerChannelSource.addHeartbeatListener(listener);
        }
    }

    /**
     * Removes the heartbeat listener from receiving heartbeats from the enclosed tuner channel source
     */
    @Override
    public void removeHeartbeatListener(Listener<Heartbeat> listener)
    {
        mHeartbeatListener = null;

        if(mTunerChannelSource != null)
        {
            mTunerChannelSource.removeHeartbeatListener(listener);
        }
    }

    @Override
    public void setFrequency(long frequency)
    {
        if(mTunerChannelSource != null)
        {
            mTunerChannelSource.setFrequency(frequency);
        }
    }

    @Override
    public void setFrequencyCorrection(long correction)
    {
        if(mTunerChannelSource != null)
        {
            mTunerChannelSource.setFrequencyCorrection(correction);
        }
    }

    @Override
    protected void setSampleRate(double sampleRate)
    {
        if(mTunerChannelSource != null)
        {
            mTunerChannelSource.setSampleRate(sampleRate);
        }
    }

    @Override
    public void setListener(Listener<ComplexSamples> complexSamplesListener)
    {
        mComplexSamplesListener = complexSamplesListener;

        if(mTunerChannelSource != null)
        {
            mTunerChannelSource.setListener(complexSamplesListener);
        }
    }

    @Override
    public double getSampleRate()
    {
        if(mTunerChannelSource != null)
        {
            return mTunerChannelSource.getSampleRate();
        }

        return 0;
    }

    /**
     * Overrides the parent method so that we can intercept external requests to change to the next frequency in the
     * frequency list.
     *
     * @param sourceEvent to process
     * @throws SourceException
     */
    @Override
    public void process(SourceEvent sourceEvent) throws SourceException
    {
        if(sourceEvent.getEvent() == SourceEvent.Event.REQUEST_FREQUENCY_ROTATION)
        {
            rotate();
        }
        else if(sourceEvent.getEvent() == SourceEvent.Event.REQUEST_FREQUENCY_SELECTION && sourceEvent.hasValue())
        {
            long frequency = sourceEvent.getValue().longValue();

            if(mFrequencies.contains(frequency))
            {
                rotateTo(frequency, true);
            }
        }
        else if(mTunerChannelSource != null)
        {
            mTunerChannelSource.process(sourceEvent);
        }
    }

    /**
     * Listener for source events produced by the enclosed tuner channel source.  This adapter receives events from
     * the wrapped tuner channel source and rebroadcasts them as consumer source events from this enclosing multi-frequency
     * tuner channel source and to the registered external consumer source event listener.
     */
    public class ConsumerSourceEventAdapter implements Listener<SourceEvent>
    {
        @Override
        public void receive(SourceEvent sourceEvent)
        {
            broadcastConsumerSourceEvent(sourceEvent);
        }
    }
}
