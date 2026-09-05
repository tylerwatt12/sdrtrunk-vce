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
                                                 long timestamp)
{
    public P25TrafficChannelConfirmationEvent
    {
        configurationId = ChannelConfigurationKey.canonical(configurationId);
    }

    /** Captures the saved channel identity before this confirmation crosses the statistics queue. */
    public P25TrafficChannelConfirmationEvent(Channel channel, long frequencyHertz, int timeslot, long timestamp)
    {
        this(ChannelConfigurationKey.configured(channel), frequencyHertz, timeslot, timestamp);
    }
}
