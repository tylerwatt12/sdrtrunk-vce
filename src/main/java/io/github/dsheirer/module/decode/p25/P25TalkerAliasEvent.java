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
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;

/**
 * Completed over-the-air P25 talker alias observation.  This event is independent of the lifetime of the call
 * tracker so that aliases decoded at call teardown can still be persisted.
 */
public record P25TalkerAliasEvent(Channel channel, RadioIdentifier radio, TalkerAliasIdentifier alias,
                                  IdentifierCollection identifiers, long timestamp)
{
}
