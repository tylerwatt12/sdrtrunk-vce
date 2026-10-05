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
package io.github.dsheirer.module.decode.traffic;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.P25SubscriberIdentityFormatter;
import io.github.dsheirer.protocol.Protocol;

/**
 * Permanent P25 subscriber identity, independent of any temporary working unit address assigned by a serving
 * system.
 */
public record P25SubscriberIdentity(int homeWacn, int homeSystemId, int subscriberId)
{
    public P25SubscriberIdentity
    {
        if(homeWacn < 0 || homeWacn > 0xFFFFF || homeSystemId < 0 || homeSystemId > 0xFFF || subscriberId < 1 ||
            subscriberId > RadioSystemIdentityKey.MAX_P25_RADIO_ID)
        {
            throw new IllegalArgumentException("Invalid canonical P25 subscriber identity");
        }
    }

    /**
     * Extracts only an explicit, complete P25 subscriber identity. Ordinary local radio IDs are never promoted.
     */
    public static P25SubscriberIdentity from(Identifier<?> identifier)
    {
        if(identifier instanceof FullyQualifiedRadioIdentifier radio && radio.getProtocol() == Protocol.APCO25)
        {
            try
            {
                return new P25SubscriberIdentity(radio.getWacn(), radio.getSystem(), radio.getRadio());
            }
            catch(IllegalArgumentException ignored)
            {
                //Some decoded infrastructure and reserved addresses are intentionally not subscriber identities.
            }
        }

        return null;
    }

    public String display()
    {
        return P25SubscriberIdentityFormatter.format(homeWacn, homeSystemId, subscriberId);
    }

    @Override
    public String toString()
    {
        return display();
    }
}
