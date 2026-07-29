/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.traffic;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.event.IDecodeEvent;

/**
 * One-time, protocol-neutral notification that a trunked traffic manager observed the start of a logical voice call.
 * The notification is produced from control/traffic signalling and does not depend on traffic-channel allocation,
 * audio, recording, or streaming.
 */
public record TrunkedCallStartEvent(Channel channel, IDecodeEvent event)
{
}
