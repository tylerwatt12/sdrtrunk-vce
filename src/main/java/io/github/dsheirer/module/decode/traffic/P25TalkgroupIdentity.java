/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.module.decode.traffic;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.protocol.Protocol;

/** Permanent P25 group identity; independent of the receiving system's local Group Address. */
public record P25TalkgroupIdentity(int homeWacn, int homeSystemId, int talkgroupId)
{
    public P25TalkgroupIdentity
    {
        if(homeWacn < 0 || homeWacn > 0xFFFFF || homeSystemId < 0 || homeSystemId > 0xFFF ||
            talkgroupId < 1 || talkgroupId >= 0xFFFF)
        {
            throw new IllegalArgumentException("Invalid canonical P25 talkgroup identity");
        }
    }

    public static P25TalkgroupIdentity from(Identifier<?> identifier)
    {
        if(identifier instanceof FullyQualifiedTalkgroupIdentifier group && group.getProtocol() == Protocol.APCO25)
        {
            try
            {
                return new P25TalkgroupIdentity(group.getWacn(), group.getSystem(), group.getTalkgroup());
            }
            catch(IllegalArgumentException ignored)
            {
                //Zero and everyone/reserved addresses are not individual group identities.
            }
        }
        return null;
    }
}
