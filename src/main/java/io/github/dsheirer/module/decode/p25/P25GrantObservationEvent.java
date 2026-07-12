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
package io.github.dsheirer.module.decode.p25;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;

/**
 * One control-channel grant or grant-update observation.
 */
public record P25GrantObservationEvent(Channel channel, APCO25Channel channelDescriptor,
                                       IdentifierCollection identifiers, DecodeEventType eventType, long timestamp,
                                       boolean continuation)
{
}
