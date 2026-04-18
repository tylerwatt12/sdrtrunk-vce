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

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.identifier.IdentifierCollection;
import java.util.Set;

/**
 * Immutable snapshot of the current state of an audio call/session.
 */
public record AudioCallSnapshot(AudioCallId callId, AudioCallId linkedCallId, AliasList aliasList,
                                IdentifierCollection identifierCollection, Set<BroadcastChannel> broadcastChannels,
                                long startTimestamp, long lastActivityTimestamp, int burstCount,
                                long burstGeneration, long lastBurstStartTimestamp, long lastBurstEndTimestamp,
                                boolean burstActive, boolean complete, boolean encrypted, boolean recordAudio,
                                int monitorPriority, boolean duplicate)
{
    public int timeslot()
    {
        return callId != null ? callId.timeslot() : 0;
    }

    public boolean isDoNotMonitor()
    {
        return monitorPriority <= Priority.DO_NOT_MONITOR;
    }

    public boolean isLinked()
    {
        return linkedCallId != null;
    }

    public boolean hasBroadcastChannels()
    {
        return broadcastChannels != null && !broadcastChannels.isEmpty();
    }

    public AudioCallSnapshot withDuplicate(boolean newDuplicate)
    {
        return new AudioCallSnapshot(callId, linkedCallId, aliasList, identifierCollection, broadcastChannels,
            startTimestamp, lastActivityTimestamp, burstCount, burstGeneration, lastBurstStartTimestamp,
            lastBurstEndTimestamp, burstActive, complete, encrypted, recordAudio, monitorPriority, newDuplicate);
    }
}
