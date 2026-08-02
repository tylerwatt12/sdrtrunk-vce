/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.identifier.decoder;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.protocol.Protocol;

/**
 * Identifies audio produced by a temporary trunked traffic-channel processing chain.
 */
public class TrafficChannelIdentifier extends Identifier<Boolean>
{
    private static final TrafficChannelIdentifier INSTANCE = new TrafficChannelIdentifier();

    private TrafficChannelIdentifier()
    {
        super(true, IdentifierClass.DECODER, Form.TRAFFIC_CHANNEL, Role.ANY);
    }

    public static TrafficChannelIdentifier create()
    {
        return INSTANCE;
    }

    @Override
    public Protocol getProtocol()
    {
        return Protocol.UNKNOWN;
    }

    @Override
    public String toString()
    {
        return "Traffic Channel";
    }
}
