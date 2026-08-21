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

package io.github.dsheirer.channel.state;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.decoder.ChannelStateIdentifier;
import io.github.dsheirer.sample.Listener;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * State machine for tracking a channel state.
 */
public class StateMachine
{
    protected volatile State mState = State.IDLE;
    protected volatile long mFadeTimeout;
    protected volatile long mFadeTimeoutBufferMilliseconds = 0;
    protected volatile long mEndTimeout;
    protected volatile long mEndTimeoutBufferMilliseconds = 0;
    private int mTimeslot;
    private Set<State> mActiveStates;
    private volatile Channel.ChannelType mChannelType = Channel.ChannelType.STANDARD;
    private List<IStateMachineListener> mStateMachineListeners = new ArrayList<>();
    private Listener<IdentifierUpdateNotification> mIdentifierUpdateListener;

    /**
     * Constructs an instance
     *
     * @param timeslot for this state machine
     * @param activeStates set of states considered active for updating the fade timeout
     */
    public StateMachine(int timeslot, Set<State> activeStates)
    {
        mTimeslot = timeslot;
        mActiveStates = activeStates;
    }

    /**
     * Adds a state change listener
     * @param listener to receive state change events
     */
    public void addListener(IStateMachineListener listener)
    {
        mStateMachineListeners.add(listener);
    }

    /**
     * Removes the state change listener
     */
    public void removeListener(IStateMachineListener listener)
    {
        mStateMachineListeners.remove(listener);
    }

    /**
     * Sets the listener to receive identifier update notifications (ie state identifier updates)
     */
    public void setIdentifierUpdateListener(Listener<IdentifierUpdateNotification> listener)
    {
        mIdentifierUpdateListener = listener;
    }

    /**
     * Sets the channel type for this state machine
     */
    public void setChannelType(Channel.ChannelType channelType)
    {
        mChannelType = channelType;
    }

    /**
     * Checks the state and transitions to FADE or TEARDOWN if timers have expired
     */
    public void checkState()
    {
        State state = mState;

        if(mActiveStates.contains(state) && mFadeTimeout <= System.currentTimeMillis())
        {
            setState(State.FADE);
        }
        else if(state == State.FADE && mEndTimeout <= System.currentTimeMillis())
        {
            setState(State.TEARDOWN);
        }
    }

    /**
     * Current state of the state machine
     */
    public State getState()
    {
        return mState;
    }

    /**
     * Sets the state
     */
    public void setState(State state)
    {
        State current = mState;

        if(state == current)
        {
            if(mActiveStates.contains(state))
            {
                updateFadeTimeout();
            }

            return;
        }

        if(!current.canChangeTo(state))
        {
            return;
        }

        if(mActiveStates.contains(state))
        {
            updateFadeTimeout();
        }

        //Use the channel type at the action point.  A lifecycle conversion can publish TRAFFIC concurrently with this
        //callback and a traffic chain must never re-enter CONTROL after that publication.  Keep the existing activity
        //timeout refresh above, while this method remains a bounded single pass with no lock or retry loop.
        if(state == State.CONTROL && mChannelType != Channel.ChannelType.STANDARD)
        {
            return;
        }

        mState = state;
        broadcast(ChannelStateIdentifier.get(state));

        //Preserve the existing last-writer check when decoder and heartbeat callbacks overlap.  Handoff-specific
        //teardown ordering is reconciled by MultiChannelState's explicit configuration transition marker.
        if(mState == state)
        {
            for(IStateMachineListener listener: mStateMachineListeners)
            {
                listener.stateChanged(state, mTimeslot);
            }
        }
    }

    /**
     * Broadcasts a channel state identifier update
     */
    private void broadcast(ChannelStateIdentifier channelStateIdentifier)
    {
        if(mIdentifierUpdateListener != null)
        {
            mIdentifierUpdateListener.receive(new IdentifierUpdateNotification(channelStateIdentifier,
                IdentifierUpdateNotification.Operation.ADD, mTimeslot));
        }
    }

    /**
     * Updates the fade timeout to current time plus the fade timeout buffer value.
     */
    private void updateFadeTimeout()
    {
        mFadeTimeout = System.currentTimeMillis() + mFadeTimeoutBufferMilliseconds;
    }

    /**
     * Manually sets the fade timeout
     */
    public void setFadeTimeout(long timeout)
    {
        mFadeTimeout = timeout;
    }

    /**
     * Fade timeout value
     */
    public long getFadeTimeout()
    {
        return mFadeTimeout;
    }

    /**
     * Sets the fade timeout buffer value.  This value determines the maximum length of time the state machine
     * can stay in any state (without an update) before automatically transitioning to FADE state.
     * @param buffer in milliseconds
     */
    public void setFadeTimeoutBufferMilliseconds(long buffer)
    {
        mFadeTimeoutBufferMilliseconds = buffer;
        updateFadeTimeout();
    }

    /**
     * Updates the end timeout value to current system time plus the end timeout buffer value.
     */
    private void updateEndTimeout()
    {
        mEndTimeout = System.currentTimeMillis() + mEndTimeoutBufferMilliseconds;
    }

    /**
     * Sets the end timeout buffer value.  This value is the maximum length of time that the channel can
     * stay in the FADE state (without an update) before transitioning to a TEARDOWN state.
     */
    public void setEndTimeoutBufferMilliseconds(long buffer)
    {
        mEndTimeoutBufferMilliseconds = buffer;
        updateEndTimeout();
    }

    /**
     * Sets the end timeout value
     */
    public void setEndTimeout(long endTimeout)
    {
        mEndTimeout = endTimeout;
    }

    /**
     * Current end timeout value
     */
    public long getEndTimeout()
    {
        return mEndTimeout;
    }
}
