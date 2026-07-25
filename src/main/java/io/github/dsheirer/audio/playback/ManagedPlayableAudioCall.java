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

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.identifier.IdentifierCollection;
import java.util.ArrayList;
import java.util.List;

/**
 * Mutable, thread-safe playback call owned exclusively by {@link io.github.dsheirer.audio.call.AudioCallCoordinator}.
 *
 * The coordinator is the only writer for snapshots and audio buffers. Playback code may read from this object on
 * other threads, but must not mutate it or treat it as a shared call-lifecycle authority.
 */
public class ManagedPlayableAudioCall implements PlayableAudioCall
{
    private final AudioCallId mCallId;
    private final List<float[]> mAudioBuffers = new ArrayList<>();
    private volatile AudioCallSnapshot mSnapshot;

    public ManagedPlayableAudioCall(AudioCallSnapshot snapshot)
    {
        mCallId = snapshot.callId();
        mSnapshot = snapshot;
    }

    public synchronized void updateSnapshot(AudioCallSnapshot snapshot)
    {
        mSnapshot = snapshot;
    }

    public synchronized void appendAudio(float[] audioFrame)
    {
        if(audioFrame != null)
        {
            mAudioBuffers.add(audioFrame);
        }
    }

    @Override
    public AudioCallSnapshot snapshot()
    {
        return mSnapshot;
    }

    @Override
    public AudioCallId callId()
    {
        return mCallId;
    }

    @Override
    public AudioCallId linkedCallId()
    {
        return mSnapshot != null ? mSnapshot.linkedCallId() : null;
    }

    @Override
    public IdentifierCollection getIdentifierCollection()
    {
        return mSnapshot != null ? mSnapshot.identifierCollection() : null;
    }

    @Override
    public long getStartTimestamp()
    {
        return mSnapshot != null ? mSnapshot.startTimestamp() : 0;
    }

    @Override
    public long getLastActivityTimestamp()
    {
        return mSnapshot != null ? mSnapshot.lastActivityTimestamp() : 0;
    }

    @Override
    public long getLastBurstStartTimestamp()
    {
        return mSnapshot != null ? mSnapshot.lastBurstStartTimestamp() : 0;
    }

    @Override
    public long getLastBurstEndTimestamp()
    {
        return mSnapshot != null ? mSnapshot.lastBurstEndTimestamp() : 0;
    }

    @Override
    public int getBurstCount()
    {
        return mSnapshot != null ? mSnapshot.burstCount() : 0;
    }

    @Override
    public long getBurstGeneration()
    {
        return mSnapshot != null ? mSnapshot.burstGeneration() : 0;
    }

    @Override
    public boolean isBurstActive()
    {
        return mSnapshot != null && mSnapshot.burstActive();
    }

    @Override
    public boolean isComplete()
    {
        return mSnapshot != null && mSnapshot.complete();
    }

    @Override
    public boolean isEncrypted()
    {
        return mSnapshot != null && mSnapshot.encrypted();
    }

    @Override
    public boolean isDoNotMonitor()
    {
        return mSnapshot != null && mSnapshot.isDoNotMonitor();
    }

    @Override
    public boolean isDuplicate()
    {
        return mSnapshot != null && mSnapshot.duplicate();
    }

    @Override
    public int getMonitorPriority()
    {
        return mSnapshot != null ? mSnapshot.monitorPriority() : Integer.MAX_VALUE;
    }

    @Override
    public boolean isLinked()
    {
        return mSnapshot != null && mSnapshot.isLinked();
    }

    @Override
    public boolean isLinkedTo(PlayableAudioCall other)
    {
        return other != null && linkedCallId() != null && linkedCallId().equals(other.callId());
    }

    @Override
    public synchronized boolean hasAudio()
    {
        return !mAudioBuffers.isEmpty();
    }

    @Override
    public synchronized int getAudioBufferCount()
    {
        return mAudioBuffers.size();
    }

    @Override
    public synchronized float[] getAudioBuffer(int index)
    {
        return mAudioBuffers.get(index);
    }
}
