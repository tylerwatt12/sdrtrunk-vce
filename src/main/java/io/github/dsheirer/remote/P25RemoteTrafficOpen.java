/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import java.util.Objects;
import java.util.UUID;

/**
 * Validated primitive metadata for one remotely allocated P25 traffic carrier. Decoder objects and host policy never
 * cross the wire boundary.
 */
public record P25RemoteTrafficOpen(String parentConfigurationId, String streamId, String feedId, boolean control,
                                   long generation, P25RemotePhase phase, long frequency, long captureTimestamp,
                                   Integer nac, Integer wacn, Integer systemId)
{
    public P25RemoteTrafficOpen
    {
        parentConfigurationId = uuid(parentConfigurationId, "parent configuration ID");
        streamId = uuid(streamId, "stream ID");
        feedId = uuid(feedId, "feed ID");
        phase = Objects.requireNonNull(phase, "phase cannot be null");

        if(frequency <= 0L || generation < 0L || captureTimestamp < 0L)
        {
            throw new IllegalArgumentException("Invalid remote traffic stream metadata");
        }

        validateRange(nac, 0xFFF, "NAC");
        validateRange(wacn, 0xFFFFF, "WACN");
        validateRange(systemId, 0xFFF, "system ID");

        if(phase == P25RemotePhase.PHASE_1 &&
            (nac == null || nac == 0xF7E || nac == 0xF7F))
        {
            throw new IllegalArgumentException("Phase 1 remote traffic requires a concrete NAC");
        }

        if(phase == P25RemotePhase.PHASE_2 && (nac == null || wacn == null || systemId == null))
        {
            throw new IllegalArgumentException("Phase 2 remote traffic requires NAC, WACN, and system ID");
        }
    }

    private static String uuid(String value, String label)
    {
        try
        {
            return UUID.fromString(Objects.requireNonNull(value, label + " cannot be null")).toString();
        }
        catch(IllegalArgumentException exception)
        {
            throw new IllegalArgumentException("Invalid " + label, exception);
        }
    }

    private static void validateRange(Integer value, int maximum, String label)
    {
        if(value != null && (value < 0 || value > maximum))
        {
            throw new IllegalArgumentException("Invalid P25 " + label);
        }
    }
}
