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

package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelConfigurationKey;

/**
 * Immutable independent evidence that a traffic decoder received payload on a granted frequency/timeslot.
 */
public record P25TrafficChannelConfirmationEvent(String configurationId, long frequencyHertz, int timeslot,
                                                 long timestamp, long processingIncarnation,
                                                 long siteEvidenceTuningGeneration)
{
    public P25TrafficChannelConfirmationEvent
    {
        configurationId = ChannelConfigurationKey.canonical(configurationId);
        processingIncarnation = Math.max(0L, processingIncarnation);
        siteEvidenceTuningGeneration = Math.max(0L, siteEvidenceTuningGeneration);
    }

    /** Compatibility constructor for callers that do not capture a receiver generation. */
    public P25TrafficChannelConfirmationEvent(String configurationId, long frequencyHertz, int timeslot,
                                              long timestamp)
    {
        this(configurationId, frequencyHertz, timeslot, timestamp, 0L, 0L);
    }

    /** Captures the saved channel identity and receiver generation before asynchronous observers see it. */
    public P25TrafficChannelConfirmationEvent(Channel channel, long frequencyHertz, int timeslot, long timestamp)
    {
        this(ChannelConfigurationKey.configured(channel), frequencyHertz, timeslot, timestamp,
            channel != null ? channel.getSiteEvidenceProcessingIncarnation() : 0L,
            channel != null ? channel.getSiteEvidenceTuningGeneration() : 0L);
    }
}
