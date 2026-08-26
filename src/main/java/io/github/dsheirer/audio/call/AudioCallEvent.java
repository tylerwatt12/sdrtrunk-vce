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

package io.github.dsheirer.audio.call;

import java.util.Arrays;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Immutable audio call event.
 *
 * @param continuationExpected true when a completed audio segment is immediately followed by a linked segment for
 * the same continuing call
 * @param voiceFrameTimestamp carrier timestamp for the vocoder frame fingerprint, or zero when the event does not
 * carry voice-frame evidence
 */
public record AudioCallEvent(AudioCallEventType eventType, AudioCallSnapshot snapshot,
                             float @Nullable [] audioFrame, boolean continuationExpected,
                             long voiceFrameFingerprint, long voiceFrameTimestamp)
{
    public AudioCallEvent
    {
        Objects.requireNonNull(eventType, "Audio call event type is required");
        Objects.requireNonNull(snapshot, "Audio call snapshot is required");

        if(eventType == AudioCallEventType.AUDIO_FRAME)
        {
            if(audioFrame == null)
            {
                throw new IllegalArgumentException("An audio-frame event requires decoded audio");
            }

            if(voiceFrameTimestamp <= 0L)
            {
                throw new IllegalArgumentException("An audio-frame event requires its carrier timestamp");
            }
        }
        else if(audioFrame != null || voiceFrameFingerprint != 0L || voiceFrameTimestamp != 0L)
        {
            throw new IllegalArgumentException("Only an audio-frame event can carry audio or voice-frame evidence");
        }

        if(continuationExpected && eventType != AudioCallEventType.CALL_COMPLETED)
        {
            throw new IllegalArgumentException("Only a completed call can expect a continuation");
        }

        audioFrame = audioFrame != null ? audioFrame.clone() : null;
    }

    public AudioCallId callId()
    {
        return snapshot.callId();
    }

    @Override
    public float @Nullable [] audioFrame()
    {
        return audioFrame != null ? audioFrame.clone() : null;
    }

    /**
     * Coordinator-only read view of the frame owned by this immutable event.  Keeping this package-private lets the
     * asynchronous handoff transfer the producer's single defensive copy without cloning every frame a second time.
     * Consumers must treat the returned array as read-only.
     */
    float @Nullable [] audioFrameView()
    {
        return audioFrame;
    }

    @Override
    public boolean equals(Object o)
    {
        if(this == o)
        {
            return true;
        }

        if(!(o instanceof AudioCallEvent that))
        {
            return false;
        }

        return continuationExpected == that.continuationExpected &&
            voiceFrameFingerprint == that.voiceFrameFingerprint &&
            voiceFrameTimestamp == that.voiceFrameTimestamp &&
            eventType == that.eventType &&
            Objects.equals(snapshot, that.snapshot) &&
            Arrays.equals(audioFrame, that.audioFrame);
    }

    @Override
    public int hashCode()
    {
        int result = Objects.hash(eventType, snapshot, continuationExpected, voiceFrameFingerprint,
            voiceFrameTimestamp);
        result = 31 * result + Arrays.hashCode(audioFrame);
        return result;
    }

    @Override
    public String toString()
    {
        return "AudioCallEvent[" +
            "eventType=" + eventType +
            ", snapshot=" + snapshot +
            ", audioFrame=" + Arrays.toString(audioFrame) +
            ", continuationExpected=" + continuationExpected +
            ", voiceFrameFingerprint=" + voiceFrameFingerprint +
            ", voiceFrameTimestamp=" + voiceFrameTimestamp +
            "]";
    }
}
