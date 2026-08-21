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

package io.github.dsheirer.source.tuner.channel.rotation;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.IDecoderStateEventListener;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.ISourceEventProvider;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.util.ThreadPool;
import java.util.ArrayList;
import java.util.Collection;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Monitors channel state to detect when a channel is not in an identified active state and issues a request to rotate
 * to the next channel frequency in the list.  This class depends on the ChannelState providing a continuous
 * stream of channel state notification events in the form of DecoderStateEvents.
 */
public class ChannelRotationMonitor extends Module implements ISourceEventProvider, IDecoderStateEventListener,
        Listener<DecoderStateEvent>
{
    public static final int CHANNEL_ROTATION_DELAY_MINIMUM = 200;
    public static final int CHANNEL_ROTATION_DELAY_DEFAULT = 2000;
    public static final int CHANNEL_ROTATION_DELAY_MAXIMUM = 10000;
    public static final int ACTIVE_STATE_LOSS_DELAY_DEFAULT = 2000;

    private static final Logger mLog = LoggerFactory.getLogger(ChannelRotationMonitor.class);
    private Collection<State> mActiveStates;
    private ScheduledFuture<?> mScheduledFuture;
    private Listener<SourceEvent> mSourceEventListener;
    private long mRotationDelay;
    private final long mActiveStateLossDelay;
    private volatile long mLastActiveTimestamp = System.currentTimeMillis();
    private volatile long mInitialRotationTimestamp;
    private volatile boolean mActiveStateObserved;
    private final AtomicLong mRequestedFrequency = new AtomicLong();

    /**
     * Constructs a channel rotation monitor that uses the specified rotation delay.
     * @param activeStates to monitor
     * @param rotationDelay specifies how long to remain on each frequency before rotating (in milliseconds).
     */
    public ChannelRotationMonitor(Collection<State> activeStates, long rotationDelay)
    {
        this(activeStates, rotationDelay, 0);
    }

    /**
     * Constructs a channel rotation monitor with separate delays for seeking and losing a previously active channel.
     * @param activeStates to monitor
     * @param rotationDelay how long to seek on each frequency before rotating, in milliseconds
     * @param activeStateLossDelay how long to tolerate silence after an active state was observed, in milliseconds
     */
    public ChannelRotationMonitor(Collection<State> activeStates, long rotationDelay, long activeStateLossDelay)
    {
        mActiveStates = new ArrayList<>(activeStates);
        mRotationDelay = rotationDelay;
        mActiveStateLossDelay = activeStateLossDelay;

        if(mRotationDelay < CHANNEL_ROTATION_DELAY_MINIMUM)
        {
            mRotationDelay = CHANNEL_ROTATION_DELAY_MINIMUM;
        }
        else if(mRotationDelay > CHANNEL_ROTATION_DELAY_MAXIMUM)
        {
            mRotationDelay = CHANNEL_ROTATION_DELAY_MAXIMUM;
        }

        mInitialRotationTimestamp = System.currentTimeMillis() + (mRotationDelay * 2);
    }

    /**
     * Registers the external listener to receive frequency rotation requests from this module
     */
    @Override
    public void setSourceEventListener(Listener<SourceEvent> listener)
    {
        mSourceEventListener = listener;
    }

    /**
     * Unregisters the external listener from receiving frequency rotation requests.
     */
    @Override
    public void removeSourceEventListener()
    {
        mSourceEventListener = null;
    }

    @Override
    public Listener<DecoderStateEvent> getDecoderStateListener()
    {
        return this;
    }

    @Override
    public void receive(DecoderStateEvent event)
    {
        if(event.getEvent() == DecoderStateEvent.Event.NOTIFICATION_CHANNEL_STATE &&
            mActiveStates.contains(event.getState()))
        {
            mLastActiveTimestamp = System.currentTimeMillis();
            mActiveStateObserved = true;
        }
    }

    /**
     * Processes a request to add an active state to the list of monitored active states.
     * @param request to add
     */
    @Subscribe
    public void addActiveState(AddChannelRotationActiveStateRequest request)
    {
        if(!mActiveStates.contains(request.getState()))
        {
            mActiveStates.add(request.getState());
        }
    }

    /**
     * Stores only the latest decoder-requested target.  EventBus dispatch is synchronous on the decoder callback, so
     * tuner selection is deferred to this monitor's existing scheduled worker.
     */
    @Subscribe
    public void selectFrequency(ChannelRotationFrequencySelectionRequest request)
    {
        mRequestedFrequency.set(request.frequency());
    }

    /**
     * Checks the current active state and when inactive for longer than the specified delay, issues a
     * channel frequency rotation request
     */
    private void checkState()
    {
        checkState(System.currentTimeMillis());
    }

    /**
     * Checks the current active state at the supplied time. Package visibility supports deterministic tests.
     */
    void checkState(long currentTimeMillis)
    {
        Listener<SourceEvent> sourceEventListener = mSourceEventListener;

        if(sourceEventListener != null)
        {
            long requestedFrequency = mRequestedFrequency.getAndSet(0);

            if(requestedFrequency > 0)
            {
                sourceEventListener.receive(SourceEvent.frequencySelectionRequest(requestedFrequency));
                mLastActiveTimestamp = currentTimeMillis;
                mInitialRotationTimestamp = 0;
                mActiveStateObserved = false;
                return;
            }
        }

        long delay = mActiveStateObserved && mActiveStateLossDelay > 0 ?
            Math.max(mRotationDelay, mActiveStateLossDelay) : mRotationDelay;

        if(sourceEventListener != null && currentTimeMillis >= mInitialRotationTimestamp &&
            ((mLastActiveTimestamp + delay) < currentTimeMillis))
        {
            sourceEventListener.receive(SourceEvent.frequencyRotationRequest());
            mLastActiveTimestamp = currentTimeMillis;
            mActiveStateObserved = false;
        }
    }

    @Override
    public void reset()
    {
        /* no action required */
    }

    @Override
    public void start()
    {
        if(mScheduledFuture == null)
        {
            mInitialRotationTimestamp = System.currentTimeMillis() + (mRotationDelay * 2);

            Runnable runnable = () -> {
                try
                {
                    checkState();
                }
                catch(Exception e)
                {
                    mLog.warn("Error while checking state", e);
                }
            };

            mScheduledFuture = ThreadPool.SCHEDULED.scheduleAtFixedRate(runnable, 0,
                mRotationDelay / 2, TimeUnit.MILLISECONDS);
        }
    }

    @Override
    public void stop()
    {
        mRequestedFrequency.set(0);

        if(mScheduledFuture != null)
        {
            mScheduledFuture.cancel(true);
            mScheduledFuture = null;
        }
    }
}
