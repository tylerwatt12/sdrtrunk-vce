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
 * Sanitized RadioResolve RF metadata status for optional UI diagnostics.
 */
public record RadioResolveMetadataStatusEvent(Stage stage, long timestamp, String guid, String channelName,
                                              String aliasListName, String nodeName, String timezone, String host,
                                              P25NetworkConfigurationSnapshot snapshot, String summaryHash,
                                              boolean readyToSend, String readinessMessage, Integer httpStatus,
                                              Integer payloadBytes, String resultMessage)
{
    public enum Stage
    {
        KNOWN,
        ATTEMPT,
        SUCCESS,
        REJECTED,
        FAILED
    }
}
