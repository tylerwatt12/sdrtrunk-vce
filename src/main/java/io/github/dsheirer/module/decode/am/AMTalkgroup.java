/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.am;

import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.protocol.Protocol;

/**
 * Logical channel identifier assigned to AM audio calls.
 */
public class AMTalkgroup extends TalkgroupIdentifier
{
    public AMTalkgroup(int value)
    {
        super(value, Role.TO);
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.AM;
    }
}
