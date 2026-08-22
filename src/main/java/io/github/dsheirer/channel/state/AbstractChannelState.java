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

package io.github.dsheirer.channel.state;

import io.github.dsheirer.audio.squelch.ISquelchStateProvider;
import io.github.dsheirer.channel.metadata.ChannelMetadata;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.controller.channel.IChannelEventProvider;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.IdentifierUpdateProvider;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.event.IDecodeEventProvider;
import io.github.dsheirer.sample.IOverflowListener;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.ISourceEventProvider;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.heartbeat.Heartbeat;
import io.github.dsheirer.source.heartbeat.IHeartbeatListener;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public abstract class AbstractChannelState extends Module implements IChannelEventProvider, IDecodeEventProvider,
    IDecoderStateEventProvider, ISourceEventProvider, IHeartbeatListener, ISquelchStateProvider,
    IdentifierUpdateProvider, IOverflowListener
{
    protected Listener<ChannelEvent> mChannelEventListener;
    protected Listener<IDecodeEvent> mDecodeEventListener;
    protected Listener<DecoderStateEvent> mDecoderStateListener;
    protected Listener<SourceEvent> mExternalSourceEventListener;
    /*
     * Decoder and heartbeat callbacks read the channel while lifecycle work can replace the configuration.  A volatile
     * owner gives those callbacks one visible channel incarnation without making the real-time path acquire a lock.
     */
    private volatile Channel mChannel;
    private final AtomicReference<ChannelConfigurationTransition> mChannelConfigurationTransition =
        new AtomicReference<>();
    protected boolean mSourceOverflow = false;
    private HeartbeatReceiver mHeartbeatReceiver = new HeartbeatReceiver();
    protected volatile boolean mTeardownSequenceStarted = false;
    protected volatile boolean mTeardownSequenceCompleted = false;

    //TODO: remove the IOverflowListener code from this class

    /**
     * Constructs an instance
     * @param channel configuration
     */
    protected AbstractChannelState(Channel channel)
    {
        mChannel = channel;
    }

    /**
     * Indicates if the teardown sequence was started.
     */
    public boolean isTeardownSequenceCompleted()
    {
        return mTeardownSequenceCompleted;
    }

    /**
     * Indicates if the teardown sequence was completed, meaning that the request disable channel event was dispatched.
     */
    public boolean isTeardownSequenceStarted()
    {
        return mTeardownSequenceStarted;
    }

    /**
     * Updates/replaces the current channel configuration with the argument.
     */
    protected void updateChannelConfiguration(Channel channel)
    {
        mChannel = Objects.requireNonNull(channel, "channel cannot be null");
    }

    /**
     * Channel configuration for this channel state
     */
    protected Channel getChannel()
    {
        return mChannel;
    }

    /**
     * Current functional channel identity for this running state machine.  The lifecycle owner publishes a converted
     * traffic-channel identity through the same volatile field before observer callbacks can rely on it, so callers
     * can read this value without consulting the channel-processing map or acquiring its lifecycle lock.
     */
    public final Channel getCurrentChannel()
    {
        return mChannel;
    }

    /**
     * Publishes the intent to replace this running channel before the lifecycle owner changes its channel-map key.
     * Decoder callbacks only inspect this preallocated marker; they never wait for lifecycle work.
     */
    public final ChannelConfigurationTransition beginChannelConfigurationTransition(Channel channel)
    {
        ChannelConfigurationTransition transition = new ChannelConfigurationTransition(mChannel,
            Objects.requireNonNull(channel, "channel cannot be null"));

        if(!mChannelConfigurationTransition.compareAndSet(null, transition))
        {
            throw new IllegalStateException("A channel configuration transition is already active");
        }

        return transition;
    }

    /**
     * Makes the target channel visible after the lifecycle owner has installed its new map entry.  Presentation and
     * configuration-identifier projection can then run on the lifecycle thread before completion reconciles state.
     */
    public final void publishChannelConfigurationTransition(ChannelConfigurationTransition transition)
    {
        requireCurrentTransition(transition);
        updateChannelConfiguration(transition.getTargetChannel());
        transition.markPublished();
        channelConfigurationTransitionPublished(transition);
    }

    /**
     * Completes a published transition and reconciles any decoder teardown that overlapped the lifecycle conversion.
     */
    public final void completeChannelConfigurationTransition(ChannelConfigurationTransition transition)
    {
        requireCurrentTransition(transition);

        if(!transition.isPublished())
        {
            throw new IllegalStateException("Channel configuration transition has not been published");
        }

        /*
         * Clear before reconciling.  A decoder callback that captured the marker already published its volatile state,
         * which the hook sees.  A later callback sees no marker and follows normal committed-channel handling.  This
         * closes the hook-read/marker-clear gap without making either callback wait or retry.
         */
        if(!mChannelConfigurationTransition.compareAndSet(transition, null))
        {
            throw new IllegalStateException("Channel configuration transition is no longer active");
        }

        channelConfigurationTransitionCompleted(transition);
    }

    /**
     * Cancels a transition before publication and restores normal handling for any state change held by the marker.
     */
    public final void rollbackChannelConfigurationTransition(ChannelConfigurationTransition transition)
    {
        if(transition != null && mChannelConfigurationTransition.get() == transition)
        {
            transition.markRollingBack();

            if(mChannelConfigurationTransition.compareAndSet(transition, null))
            {
                if(transition.isPublished())
                {
                    updateChannelConfiguration(transition.getPreviousChannel());
                }

                channelConfigurationTransitionRolledBack(transition);
            }
        }
    }

    /** Current transition marker for nonblocking decoder-side reconciliation. */
    protected final ChannelConfigurationTransition getChannelConfigurationTransition()
    {
        return mChannelConfigurationTransition.get();
    }

    protected void channelConfigurationTransitionPublished(ChannelConfigurationTransition transition)
    {
        // Optional subclass hook.
    }

    protected void channelConfigurationTransitionCompleted(ChannelConfigurationTransition transition)
    {
        // Optional subclass hook.
    }

    protected void channelConfigurationTransitionRolledBack(ChannelConfigurationTransition transition)
    {
        // Optional subclass hook.
    }

    private void requireCurrentTransition(ChannelConfigurationTransition transition)
    {
        if(transition == null || mChannelConfigurationTransition.get() != transition)
        {
            throw new IllegalStateException("Channel configuration transition is no longer active");
        }
    }

    /**
     * Identity-scoped marker for one running processing-chain configuration transition.
     */
    public static final class ChannelConfigurationTransition
    {
        private final Channel mPreviousChannel;
        private final Channel mTargetChannel;
        private final AtomicBoolean mPublished = new AtomicBoolean();
        private final AtomicBoolean mRollingBack = new AtomicBoolean();
        private final AtomicBoolean mTeardownObserved = new AtomicBoolean();

        private ChannelConfigurationTransition(Channel previousChannel, Channel targetChannel)
        {
            mPreviousChannel = previousChannel;
            mTargetChannel = targetChannel;
        }

        public Channel getPreviousChannel()
        {
            return mPreviousChannel;
        }

        public Channel getTargetChannel()
        {
            return mTargetChannel;
        }

        public boolean isPublished()
        {
            return mPublished.get();
        }

        private void markPublished()
        {
            if(!mPublished.compareAndSet(false, true))
            {
                throw new IllegalStateException("Channel configuration transition was already published");
            }
        }

        private void markRollingBack()
        {
            mRollingBack.set(true);
        }

        public boolean isRollingBack()
        {
            return mRollingBack.get();
        }

        public void markTeardownObserved()
        {
            mTeardownObserved.set(true);
        }

        public boolean wasTeardownObserved()
        {
            return mTeardownObserved.get();
        }
    }

    /**
     * Invoked each time that a heartbeat is received so that sub-class implementations can check current timers and
     * adjust channel state as necessary.  The heartbeat arrives on a periodic basis independent of any decoded
     * messages so that channel state is not entirely dependent on a continuous decoded message stream.
     */
    protected abstract void checkState();

    /**
     * Indicates if any timeslot is currently in a TEARDOWN state.
     */
    public abstract boolean isTeardownState();

    public abstract List<ChannelMetadata> getChannelMetadata();

    public abstract void updateChannelStateIdentifiers(IdentifierUpdateNotification notification);

    /**
     * Receiver inner class that implements the IHeartbeatListener interface to receive heartbeat messages.
     */
    @Override
    public Listener<Heartbeat> getHeartbeatListener()
    {
        return mHeartbeatReceiver;
    }

    /**
     * This method is invoked if the source buffer provider goes into overflow state.  Since this is an external state,
     * we use the mSourceOverflow variable to override the internal state reported to external listeners.
     *
     * @param overflow true to indicate an overflow state
     */
    @Override
    public void sourceOverflow(boolean overflow)
    {
        mSourceOverflow = overflow;
    }

    /**
     * Indicates if this channel's sample buffer is in overflow state, meaning that the inbound sample
     * stream is not being processed fast enough and samples are being thrown away until the processing can
     * catch up.
     *
     * @return true if the channel is in overflow state.
     */
    public boolean isOverflow()
    {
        return mSourceOverflow;
    }

    @Override
    public void setChannelEventListener(Listener<ChannelEvent> listener)
    {
        mChannelEventListener = listener;
    }

    @Override
    public void removeChannelEventListener()
    {
        mChannelEventListener = null;
    }

    @Override
    public void addDecodeEventListener(Listener<IDecodeEvent> listener)
    {
        mDecodeEventListener = listener;
    }

    @Override
    public void removeDecodeEventListener(Listener<IDecodeEvent> listener)
    {
        mDecodeEventListener = null;
    }

    /**
     * Adds a decoder state event listener
     */
    @Override
    public void setDecoderStateListener(Listener<DecoderStateEvent> listener)
    {
        mDecoderStateListener = listener;
    }

    /**
     * Removes the decoder state event listener
     */
    @Override
    public void removeDecoderStateListener()
    {
        mDecoderStateListener = null;
    }

    /**
     * Registers the listener to receive source events from the channel state
     */
    @Override
    public void setSourceEventListener(Listener<SourceEvent> listener)
    {
        mExternalSourceEventListener = listener;
    }

    /**
     * De-Registers a listener from receiving source events from the channel state
     */
    @Override
    public void removeSourceEventListener()
    {
        mExternalSourceEventListener = null;
    }

    /**
     * Processes periodic heartbeats received from the processing chain to perform state monitoring and cleanup
     * functions.
     *
     * Monitors decoder state events to automatically transition the channel state to IDLE (standard channel) or to
     * TEARDOWN (traffic channel) when decoding stops or the monitored channel returns to a no signal state.
     *
     * Provides a FADE transition state to allow for momentary decoding dropouts and to allow the user access to call
     * details for a fade period upon call end.
     */
    public class HeartbeatReceiver implements Listener<Heartbeat>
    {
        @Override
        public void receive(Heartbeat heartbeat)
        {
            checkState();
        }
    }
}
