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

package io.github.dsheirer.alias;

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.protocol.Protocol;

/**
 * Shared presentation policy for compatibility-only alias identifiers. Retired values remain internally
 * round-trippable, but descriptor-driven web and desktop editors can use this policy to avoid exposing them.
 */
public final class AliasIdentifierPolicy
{
    private AliasIdentifierPolicy()
    {
    }

    public static boolean isUserVisible(AliasID identifier)
    {
        if(identifier == null || identifier.getType() == null || !identifier.getType().isActive())
        {
            return false;
        }

        Protocol protocol = switch(identifier)
        {
            case Talkgroup talkgroup -> talkgroup.getProtocol();
            case TalkgroupRange range -> range.getProtocol();
            case Radio radio -> radio.getProtocol();
            case RadioRange range -> range.getProtocol();
            default -> null;
        };

        return protocol == null || protocol.isActive();
    }
}
