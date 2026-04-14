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

import io.github.dsheirer.audio.AudioSegment;

/**
 * Playback-side view of one talk burst on an {@link AudioSegment}.  The segment remains the call/session object;
 * this wrapper owns the mutable playback state for the currently observed burst.
 */
public class AudioPlaybackBurst
{
    private final AudioSegment mAudioSegment;
    private long mBurstGeneration;
    private int mCurrentBufferIndex = -1;
    private boolean mAudioDelivered;

    public AudioPlaybackBurst(AudioSegment audioSegment)
    {
        mAudioSegment = audioSegment;
        mBurstGeneration = audioSegment != null ? audioSegment.getBurstGeneration() : 0;
    }

    public AudioSegment getAudioSegment()
    {
        return mAudioSegment;
    }

    public boolean hasSegment()
    {
        return mAudioSegment != null;
    }

    public long getBurstGeneration()
    {
        return mBurstGeneration;
    }

    public boolean isBurstGenerationCurrent()
    {
        return mAudioSegment != null && mAudioSegment.getBurstGeneration() == mBurstGeneration;
    }

    /**
     * Updates this playback view to the segment's current burst generation.
     *
     * @return true if this represents a new burst after audio was already delivered for a prior burst
     */
    public boolean advanceToCurrentBurst()
    {
        if(mAudioSegment == null || isBurstGenerationCurrent())
        {
            return false;
        }

        boolean newBurstAfterPlayback = mAudioDelivered;
        mBurstGeneration = mAudioSegment.getBurstGeneration();

        if(mAudioSegment.getAudioBufferCount() > mCurrentBufferIndex)
        {
            mCurrentBufferIndex = Math.max(0, mCurrentBufferIndex);
        }

        return newBurstAfterPlayback;
    }

    public boolean hasPendingBuffers()
    {
        return mAudioSegment != null && mCurrentBufferIndex < mAudioSegment.getAudioBufferCount();
    }

    public boolean isStartOfBurst()
    {
        return mCurrentBufferIndex < 0;
    }

    public float[] nextBuffer()
    {
        if(mAudioSegment == null)
        {
            return null;
        }

        float[] audio;

        if(mCurrentBufferIndex < 0)
        {
            audio = mAudioSegment.getAudioBuffer(0);
            mCurrentBufferIndex = 1;
        }
        else
        {
            audio = mAudioSegment.getAudioBuffer(mCurrentBufferIndex++);
        }

        mAudioDelivered = true;
        return audio;
    }

    public int getCurrentBufferIndex()
    {
        return mCurrentBufferIndex;
    }

    public boolean isAudioDelivered()
    {
        return mAudioDelivered;
    }
}
