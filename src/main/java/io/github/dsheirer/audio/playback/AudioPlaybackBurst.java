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

package io.github.dsheirer.audio.playback;

/**
 * Playback-side view of one talk burst on a {@link PlayableAudioCall}.
 * this wrapper owns the mutable playback state for the currently observed burst.
 */
public class AudioPlaybackBurst
{
    private enum BurstPlaybackState
    {
        NOT_STARTED,
        PENDING_START_TONE,
        PLAYING
    }

    private final PlayableAudioCall mAudioCall;
    private long mBurstGeneration;
    private int mCurrentBufferIndex = -1;
    private BurstPlaybackState mPlaybackState = BurstPlaybackState.NOT_STARTED;

    public AudioPlaybackBurst(PlayableAudioCall audioCall)
    {
        mAudioCall = audioCall;
        mBurstGeneration = audioCall != null ? audioCall.getBurstGeneration() : 0;
    }

    public PlayableAudioCall getAudioCall()
    {
        return mAudioCall;
    }

    public boolean hasSegment()
    {
        return mAudioCall != null;
    }

    public long getBurstGeneration()
    {
        return mBurstGeneration;
    }

    public boolean isBurstGenerationCurrent()
    {
        return mAudioCall != null && mAudioCall.getBurstGeneration() == mBurstGeneration;
    }

    /**
     * Updates this playback view to the call's current burst generation.
     *
     * @return true if this represents a new burst after audio was already delivered for a prior burst
     */
    public boolean advanceToCurrentBurst()
    {
        if(mAudioCall == null || isBurstGenerationCurrent())
        {
            return false;
        }

        boolean newBurstAfterPlayback = mPlaybackState == BurstPlaybackState.PLAYING;
        mBurstGeneration = mAudioCall.getBurstGeneration();
        mPlaybackState = newBurstAfterPlayback ? BurstPlaybackState.PENDING_START_TONE :
            BurstPlaybackState.NOT_STARTED;

        if(mAudioCall.getAudioBufferCount() > mCurrentBufferIndex)
        {
            mCurrentBufferIndex = Math.max(0, mCurrentBufferIndex);
        }

        return newBurstAfterPlayback;
    }

    public boolean hasPendingBuffers()
    {
        return mAudioCall != null && mCurrentBufferIndex < mAudioCall.getAudioBufferCount();
    }

    public boolean isStartOfBurst()
    {
        return mCurrentBufferIndex < 0 || mPlaybackState == BurstPlaybackState.PENDING_START_TONE;
    }

    /**
     * Indicates that a new burst began on an already-loaded call and the next delivered audio buffer should be
     * treated as the audible start of that burst.
     */
    public void beginBurstPlayback()
    {
        mPlaybackState = BurstPlaybackState.PLAYING;
    }

    public float[] nextBuffer()
    {
        if(mAudioCall == null)
        {
            return null;
        }

        float[] audio;

        if(mCurrentBufferIndex < 0)
        {
            audio = mAudioCall.getAudioBuffer(0);
            mCurrentBufferIndex = 1;
        }
        else
        {
            audio = mAudioCall.getAudioBuffer(mCurrentBufferIndex++);
        }

        beginBurstPlayback();
        return audio;
    }

    public int getCurrentBufferIndex()
    {
        return mCurrentBufferIndex;
    }

    public boolean isAudioDelivered()
    {
        return mPlaybackState == BurstPlaybackState.PLAYING;
    }
}
