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

/**
 * Playback-facing view of a live call owned by the audio coordinator.
 */
public interface PlayableAudioCall
{
    AudioCallSnapshot snapshot();
    AudioCallId callId();
    AudioCallId linkedCallId();
    IdentifierCollection getIdentifierCollection();
    long getStartTimestamp();
    long getLastActivityTimestamp();
    long getLastBurstStartTimestamp();
    long getLastBurstEndTimestamp();
    int getBurstCount();
    long getBurstGeneration();
    boolean isBurstActive();
    boolean isComplete();
    boolean isEncrypted();
    boolean isDoNotMonitor();
    boolean isDuplicate();
    int getMonitorPriority();
    boolean isLinked();
    boolean isLinkedTo(PlayableAudioCall other);
    boolean hasAudio();
    int getAudioBufferCount();
    float[] getAudioBuffer(int index);
}
