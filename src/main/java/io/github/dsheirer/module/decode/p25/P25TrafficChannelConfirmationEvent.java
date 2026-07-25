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

/**
 * Independent evidence that a traffic decoder received payload on a granted frequency/timeslot.
 */
public record P25TrafficChannelConfirmationEvent(Channel channel, long frequencyHertz, int timeslot, long timestamp)
{
}
