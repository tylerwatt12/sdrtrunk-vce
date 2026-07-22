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
import io.github.dsheirer.controller.channel.event.ChannelStopProcessingRequest;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.Source;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.heartbeat.Heartbeat;
import io.github.dsheirer.source.tuner.channel.rotation.FrequencyLockChangeRequest;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.util.ThreadPool;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Multiple-frequency tuner channel source.  Provides a wrapper around a tuner channel source and listens for external
 * source events requests to change frequency.  Maintains an ordered list of frequencies and automatically tears down
 * an existing tuner channel source and obtains a new one with the next frequency from the list, on request.
 */
public class MultiFrequencyTunerChannelSource extends TunerChannelSource
{

    private TunerManager mTunerManager;
    private volatile TunerChannelSource mTunerChannelSource;
    private List<Long> mFrequencies;
    private List<Long> mLockedFrequencies = new ArrayList<>();
    private int mFrequencyListPointer = 0;
    private ChannelSpecification mChannelSpecification;
    private Long mMinimumFrequency;
    private Long mMaximumFrequency;
    private Listener<ComplexSamples> mComplexSamplesListener;
    private Listener<Heartbeat> mHeartbeatListener;
    private String mPreferredTuner;
    private AtomicBoolean mChangingChannels = new AtomicBoolean();
    private volatile boolean mStarted;
    private final Object mSourceLock = new Object();
    private DiscoveredTuner mSourceLifecycleOwner;
    private DiscoveredTuner.LifecycleQuiesceRegistration mGapLifecycleRegistration;
    private long mGapLifecycleGeneration;
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
        mChannelSpecification = channelSpecification;
        mPreferredTuner = preferredTuner;
        mMinimumFrequency = minimumFrequency;
        mMaximumFrequency = maximumFrequency;
        mSourceLifecycleOwner = mTunerManager.getSourceAllocationOwner(tunerChannelSource);
    }

    /**
     * Cycles this source to use the next frequency in the list.  If no other frequencies are available,
     * because of frequency locking, ignore the rotate request.
     */
    private void rotate()
    {
        synchronized(mSourceLock)
        {
            if(mChangingChannels.compareAndSet(false, true))
            {
                long frequency = getNextFrequency();

                if(frequency == 0)
                {
                    mChangingChannels.set(false);
                    return;
                }

                if(mTunerChannelSource != null)
                {
                    //Once the concrete source is removed, the normal channel manager cannot enumerate this wrapper.
                    //Register a tiny one-shot owner hook before creating that gap.
                    if(!registerGapLifecycleListener())
                    {
                        sourceReceiverQuiescing();
                        return;
                    }

                    //Shutdown the existing tuner channel source
                    mTunerChannelSource.stop();
                    mTunerChannelSource.setListener(null);
                    mTunerChannelSource.removeSourceEventListener();
                    mTunerChannelSource.removeHeartbeatListener(mHeartbeatListener);
                    mTunerChannelSource.dispose();
                    mTunerChannelSource = null;
                }

                //Request the next tuner channel source
                getNextSourceLocked(getTunerChannel(frequency));
            }
        }
    }

    /**
     * Persistently attempts to get the next tuner channel source using the next frequency in the list.  This method
     * should only be invoked by the nextFrequency() method that protects access via the mChangingChannels flag.
     *
     * Note: if this channel source is shutdown by the external consumer, the mStarted flag will be set to false and
     * the persistent attempts will stop.
     */
    private void getNextSource(TunerChannel nextChannel)
    {
        synchronized(mSourceLock)
        {
            getNextSourceLocked(nextChannel);
        }
    }

    private void getNextSourceLocked(TunerChannel nextChannel)
    {
        if(mStarted)
        {
            Source source = mTunerManager.getSource(nextChannel, mChannelSpecification, mPreferredTuner, mThreadName,
                getAllFrequencyChannels());
            DiscoveredTuner nextLifecycleOwner = mTunerManager.getSourceAllocationOwner(source);

            try
            {
                if(source instanceof TunerChannelSource tunerChannelSource && mStarted)
                {
                    if(nextLifecycleOwner == null)
                    {
                        throw new IllegalStateException("Receiver began stopping during frequency rotation");
                    }

                    mTunerChannelSource = tunerChannelSource;
                    mTunerChannelSource.setSourceEventListener(mConsumerSourceEventAdapter);
                    mTunerChannelSource.setListener(mComplexSamplesListener);
                    mTunerChannelSource.addHeartbeatListener(mHeartbeatListener);
                    mTunerChannelSource.start();
                    mTunerChannel = nextChannel;
                    mSourceLifecycleOwner = nextLifecycleOwner;
                    closeGapLifecycleRegistration();
                    mChangingChannels.set(false);

                    getSourceEventListener().receive(SourceEvent.frequencyRotationSuccessNotification(this,
                        nextChannel.getFrequency()));
                }
                else if(source instanceof TunerChannelSource tunerChannelSource)
                {
                    tunerChannelSource.stop();
                    tunerChannelSource.dispose();
                }
            }
            catch(RuntimeException exception)
            {
                TunerChannelSource failedSource = mTunerChannelSource != null ? mTunerChannelSource :
                    source instanceof TunerChannelSource tunerChannelSource ? tunerChannelSource : null;

                if(failedSource != null)
                {
                    try
                    {
                        failedSource.stop();
                        failedSource.dispose();
                    }
                    catch(RuntimeException cleanupException)
                    {
                        exception.addSuppressed(cleanupException);
                    }

                    mTunerChannelSource = null;
                }

                if(mStarted && mGapLifecycleRegistration == null && !registerGapLifecycleListener())
                {
                    sourceReceiverQuiescing();
                }
            }
            finally
            {
                //The wrapper now exposes the underlying source to stop requests, so native shutdown may proceed.
                mTunerManager.completeSourceAllocation(source);
            }

            //If we don't get a channel source because a tuner is not available or none of the available tuners can
            //support the frequency, then persistently attempt to get a source by iterating the frequency list
            if(mTunerChannelSource == null && mStarted)
            {
                getSourceEventListener().receive(SourceEvent.frequencyRotationFailureNotification(this, nextChannel.getFrequency()));
                ThreadPool.SCHEDULED.schedule(() -> getNextSource(getTunerChannel(getNextFrequency())), 500, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * Indicates whether this wrapper currently owns the supplied underlying tuner source.
     */
    public boolean hasSource(Source source)
    {
        return source == this || mTunerChannelSource == source;
    }

    private void sourceReceiverQuiescing()
    {
        boolean notify;

        synchronized(mSourceLock)
        {
            notify = sourceReceiverQuiescingLocked();
        }

        if(notify)
        {
            MyEventBus.getGlobalEventBus().post(new ChannelStopProcessingRequest(this));
        }
    }

    private void sourceReceiverQuiescing(long generation)
    {
        boolean notify = false;

        synchronized(mSourceLock)
        {
            //An old owner's quiesce callback may already have been copied for invocation while this wrapper finishes
            //assigning a source from a new receiver. Ignore that stale callback once the guarded gap has closed.
            if(generation == mGapLifecycleGeneration && mGapLifecycleRegistration != null)
            {
                notify = sourceReceiverQuiescingLocked();
            }
        }

        if(notify)
        {
            MyEventBus.getGlobalEventBus().post(new ChannelStopProcessingRequest(this));
        }
    }

    private boolean sourceReceiverQuiescingLocked()
    {
        if(!mStarted && mGapLifecycleRegistration == null)
        {
            return false;
        }

        //This callback is especially important during the source-less interval between rotated frequencies, when a
        //tuner channel manager has no concrete source it can enumerate for its normal stop request.
        mStarted = false;
        mChangingChannels.set(false);
        closeGapLifecycleRegistration();
        return true;
    }

    private boolean registerGapLifecycleListener()
    {
        closeGapLifecycleRegistration();
        long generation = ++mGapLifecycleGeneration;
        mGapLifecycleRegistration = mSourceLifecycleOwner != null ?
            mSourceLifecycleOwner.tryRegisterLifecycleQuiesceListener(() -> sourceReceiverQuiescing(generation)) : null;
        return mGapLifecycleRegistration != null;
    }

    private void closeGapLifecycleRegistration()
    {
        mGapLifecycleGeneration++;
        DiscoveredTuner.LifecycleQuiesceRegistration registration = mGapLifecycleRegistration;
        mGapLifecycleRegistration = null;

        if(registration != null)
        {
            registration.close();
        }
    }

    @Override
    public void start()
    {
        synchronized(mSourceLock)
        {
            //The initial source should not be null
            if(mTunerChannelSource != null)
            {
                mTunerChannelSource.start();
                mStarted = true;
            }
        }
    }

    @Override
    public void stop()
    {
        synchronized(mSourceLock)
        {
            mStarted = false;
            mChangingChannels.set(false);
            closeGapLifecycleRegistration();
            mSourceLifecycleOwner = null;

            if(mTunerChannelSource != null)
            {
                mTunerChannelSource.stop();
                mTunerChannelSource.removeSourceEventListener();
                mTunerChannelSource = null;
            }
        }
    }

    @Override
    protected void performDisposal()
    {
        synchronized(mSourceLock)
        {
            closeGapLifecycleRegistration();
            mSourceLifecycleOwner = null;
        }

        super.performDisposal();
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
        if(request.isLockRequest() && !mLockedFrequencies.contains(request.getFrequency()))
        {
            mLockedFrequencies.add(request.getFrequency());
        }
        else if(request.isUnlockRequest() && mLockedFrequencies.contains(request.getFrequency()))
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
