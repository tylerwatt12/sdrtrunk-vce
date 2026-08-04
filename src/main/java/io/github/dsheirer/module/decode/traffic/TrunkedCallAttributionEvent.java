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

import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.protocol.Protocol;

/**
 * Immutable late identity/encryption attribution for an already-counted logical trunked call.
 */
public record TrunkedCallAttributionEvent(Channel channel, Protocol protocol,
                                          IChannelDescriptor channelDescriptor, Integer timeslot,
                                          long callStartEpochMilliseconds,
                                          IdentifierCollection identifiers,
                                          boolean destinationBecameKnown,
                                          boolean sourceBecameKnown,
                                          boolean encryptionBecameKnown,
                                          Integer encryptionAlgorithmId,
                                          Integer encryptionKeyId,
                                          boolean encryptedBeforeObservation)
{
    public TrunkedCallAttributionEvent
    {
        identifiers = identifiers != null ?
            new IdentifierCollection(identifiers.getIdentifiers()) : new IdentifierCollection();

        if(timeslot != null && timeslot >= 0)
        {
            identifiers.setTimeslot(timeslot);
        }
    }

    public TrunkedCallAttributionEvent(Channel channel, Protocol protocol,
                                       IChannelDescriptor channelDescriptor, Integer timeslot,
                                       long callStartEpochMilliseconds, IdentifierCollection identifiers,
                                       boolean destinationBecameKnown, boolean sourceBecameKnown,
                                       boolean encryptionBecameKnown, boolean encryptedBeforeObservation)
    {
        this(channel, protocol, channelDescriptor, timeslot, callStartEpochMilliseconds, identifiers,
            destinationBecameKnown, sourceBecameKnown, encryptionBecameKnown, null, null,
            encryptedBeforeObservation);
    }
}
