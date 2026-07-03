/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.audio.broadcast.radioresolve;

import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;

/**
 * Shared RadioResolve RF metadata publish-readiness rule.
 */
public record RadioResolveMetadataReadiness(boolean ready, String message)
{
    public static RadioResolveMetadataReadiness evaluate(String guid, P25NetworkConfigurationSnapshot snapshot)
    {
        StringBuilder missing = new StringBuilder();

        if(guid == null || guid.isBlank())
        {
            appendMissing(missing, "GUID");
        }

        if(snapshot == null || snapshot.decoder() == null || snapshot.decoder().isBlank())
        {
            appendMissing(missing, "Decoder");
        }

        P25NetworkConfigurationSnapshot.Network network = snapshot != null ? snapshot.network() : null;

        if(network == null || network.wacn() == null)
        {
            appendMissing(missing, "WACN");
        }

        if(network == null || network.system() == null)
        {
            appendMissing(missing, "System");
        }

        P25NetworkConfigurationSnapshot.CurrentSite currentSite = snapshot != null ? snapshot.currentSite() : null;

        if(currentSite == null || currentSite.rfss() == null)
        {
            appendMissing(missing, "RFSS");
        }

        if(currentSite == null || currentSite.site() == null)
        {
            appendMissing(missing, "Site");
        }

        if(!hasFrequencyBand(snapshot))
        {
            appendMissing(missing, "Frequency Band");
        }

        if(!hasResolvedPrimaryControl(snapshot))
        {
            appendMissing(missing, "Current Control");
        }

        if(missing.length() == 0)
        {
            return new RadioResolveMetadataReadiness(true, "Ready");
        }

        return new RadioResolveMetadataReadiness(false, "Not uploaded: missing " + missing);
    }

    private static boolean hasFrequencyBand(P25NetworkConfigurationSnapshot snapshot)
    {
        return snapshot != null && snapshot.frequencyBands() != null && !snapshot.frequencyBands().isEmpty();
    }

    private static boolean hasResolvedPrimaryControl(P25NetworkConfigurationSnapshot snapshot)
    {
        if(snapshot == null || snapshot.channels() == null)
        {
            return false;
        }

        for(P25NetworkConfigurationSnapshot.Channel channel: snapshot.channels())
        {
            if(channel != null && isCurrentControl(channel.role()) && channel.downlink() != null &&
                channel.downlink() > 0)
            {
                return true;
            }
        }

        return false;
    }

    private static boolean isCurrentControl(String role)
    {
        return "primary_control".equals(role) || "current_control".equals(role);
    }

    private static void appendMissing(StringBuilder missing, String value)
    {
        if(missing.length() > 0)
        {
            missing.append(", ");
        }

        missing.append(value);
    }
}
