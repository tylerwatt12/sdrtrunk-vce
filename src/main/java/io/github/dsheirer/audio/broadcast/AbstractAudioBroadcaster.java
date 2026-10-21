/*
 * *****************************************************************************
 *  Copyright (C) 2014-2020 Dennis Sheirer
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

package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.sample.Listener;

/**
 * Base Audio Broadcaster class
 */
public abstract class AbstractAudioBroadcaster<T extends BroadcastConfiguration> implements Listener<AudioRecording>
{
    private Listener<BroadcastEvent> mBroadcastEventListener;
    private T mBroadcastConfiguration;
    public record StateSnapshot(BroadcastState state, BroadcastState lastBadState) {}
    private volatile StateSnapshot mState = new StateSnapshot(BroadcastState.READY, null);
    private final java.util.List<Runnable> mStateListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    protected volatile int mStreamedAudioCount = 0;
    protected volatile int mErrorAudioCount = 0;
    protected volatile int mAgedOffAudioCount = 0;

    /**
     * Constructs an instance
     * @param broadcastConfiguration to use for this broadcaster
     */
    protected AbstractAudioBroadcaster(T broadcastConfiguration)
    {
        mBroadcastConfiguration = broadcastConfiguration;
    }

    public void addStateChangeListener(Runnable listener) { mStateListeners.add(listener); }
    public void removeStateChangeListener(Runnable listener) { mStateListeners.remove(listener); }
    public StateSnapshot getStateSnapshot() { return mState; }

    /**
     * Current state of the broadcastAudio connection
     */
    public BroadcastState getBroadcastState()
    {
        return mState.state();
    }

    /**
     * Sets or changes the broadcast state
     */
    public void setBroadcastState(BroadcastState broadcastState)
    {
        StateSnapshot previous = mState;
        BroadcastState lastBad = previous.lastBadState();
        if(broadcastState == BroadcastState.CONNECTED) lastBad = null;
        else if(broadcastState.isErrorState() || broadcastState.isWarningState()) lastBad = broadcastState;
        StateSnapshot current = new StateSnapshot(broadcastState, lastBad);
        mState = current;
        if(!current.equals(previous)) mStateListeners.forEach(Runnable::run);
        broadcast(new BroadcastEvent(this, BroadcastEvent.Event.BROADCASTER_STATE_CHANGE));
    }

    /**
     * Last bad state of the broadcastAudio connection
     */
    public BroadcastState getLastBadBroadcastState()
    {
        return mState.lastBadState();
    }

    /**
     * Starts the broadcaster
     */
    public abstract void start();

    /**
     * Stops the broadcaster
     */
    public abstract void stop();

    /**
     * Perform any shutdown related tasks.
     */
    public abstract void dispose();

    /**
     * Indicates if this broadcaster accepts the recording for delivery.  Providers that do not apply additional
     * routing restrictions retain the legacy accept-all behavior.
     *
     * @param audioRecording candidate recording
     * @return true to enqueue the recording for this broadcaster
     */
    public boolean accepts(AudioRecording audioRecording)
    {
        return true;
    }

    /**
     * Access the configuration for the broadcaster
     */
    public T getBroadcastConfiguration()
    {
        return mBroadcastConfiguration;
    }

    /**
     * Registers the listener to receive broadcast events/state changes
     */
    public void setListener(Listener<BroadcastEvent> listener)
    {
        mBroadcastEventListener = listener;
    }

    /**
     * Removes the listener from receiving broadcast events/state changes
     */
   public void removeListener()
   {
       mBroadcastEventListener = null;
   }

    /**
     * Broadcasts the event to any registered listener
     */
    protected void broadcast(BroadcastEvent event)
    {
        if(mBroadcastEventListener != null)
        {
            mBroadcastEventListener.receive(event);
        }
    }

    /**
     * Number of audio recordings streamed to remote server
     */
    public int getStreamedAudioCount()
    {
        return mStreamedAudioCount;
    }

    /**
     * Increments the streamed or uploaded audio count by one
     */
    public void incrementStreamedAudioCount()
    {
        mStreamedAudioCount++;
    }

    /**
     * Number of audio recordings that were removed for exceeding age limit
     */
    public int getAgedOffAudioCount()
    {
        return mAgedOffAudioCount;
    }

    /**
     * Increments the aged-off audio count by one
     */
    public void incrementAgedOffAudioCount()
    {
        mAgedOffAudioCount++;
    }

    /**
     * Number of audio recordings awaiting streaming or upload
     */
    public abstract int getAudioQueueSize();

    /** Observer read; durable senders override this to avoid storage work or locks. */
    public int getAudioQueueSizeSnapshot()
    {
        return getAudioQueueSize();
    }

    /**
     * Total audio upload/stream error count
     */
    public int getAudioErrorCount()
    {
        return mErrorAudioCount;
    }

    /**
     * Increments the error audio count by one
     */
    public void incrementErrorAudioCount()
    {
        mErrorAudioCount++;
    }
}
