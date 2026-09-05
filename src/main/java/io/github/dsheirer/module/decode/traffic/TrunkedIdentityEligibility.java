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

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.dmr.message.type.Tier3Gateway;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.protocol.Protocol;

/**
 * Protocol-aware identity eligibility shared by call tracking and durable directory projections.
 */
public final class TrunkedIdentityEligibility
{
    private static final int P25_EVERYONE_TALKGROUP = 0xFFFF;
    private static final int DMR_MAX_ID = 0xFFFFFF;
    private static final int NXDN_MAX_ID = 0xFFFF;
    private static final int NXDN_TYPE_C_RESERVED_GROUP = 0xFFF0;
    private static final int NXDN_TYPE_C_FIRST_SPECIAL_RADIO = 0xFFF0;
    private static final int NXDN_TYPE_C_LAST_SPECIAL_RADIO = 0xFFF5;

    private TrunkedIdentityEligibility()
    {
    }

    public static boolean isEligible(Protocol protocol, TrunkedIdentityDomain identityDomain, Form form,
                                     Integer identifier)
    {
        if(protocol == null || form == null || identifier == null || identifier <= 0)
        {
            return false;
        }

        return switch(form)
        {
            case TALKGROUP -> isTalkgroup(protocol, identityDomain, identifier);
            case PATCH_GROUP -> (protocol == Protocol.APCO25 || protocol == Protocol.APCO25_PHASE2) &&
                isTalkgroup(protocol, identityDomain, identifier);
            case RADIO -> isRadio(protocol, identityDomain, identifier);
            default -> false;
        };
    }

    public static boolean isTalkgroup(Protocol protocol, TrunkedIdentityDomain identityDomain, int talkgroup)
    {
        if(talkgroup <= 0)
        {
            return false;
        }

        return switch(protocol)
        {
            case APCO25, APCO25_PHASE2 -> talkgroup < P25_EVERYONE_TALKGROUP;
            case DMR -> talkgroup <= DMR_MAX_ID && !Tier3Gateway.isGateway(talkgroup);
            case NXDN -> talkgroup <= NXDN_MAX_ID &&
                (identityDomain == TrunkedIdentityDomain.NXDN_TYPE_D ||
                    talkgroup != NXDN_TYPE_C_RESERVED_GROUP && talkgroup != NXDN_MAX_ID);
            default -> false;
        };
    }

    public static boolean isRadio(Protocol protocol, TrunkedIdentityDomain identityDomain, int radio)
    {
        if(radio <= 0)
        {
            return false;
        }

        return switch(protocol)
        {
            case APCO25, APCO25_PHASE2 -> radio <= RadioSystemIdentityKey.MAX_P25_RADIO_ID;
            case DMR -> radio <= DMR_MAX_ID && !Tier3Gateway.isGateway(radio);
            case NXDN -> radio <= NXDN_MAX_ID &&
                (identityDomain == TrunkedIdentityDomain.NXDN_TYPE_D ||
                    (radio < NXDN_TYPE_C_FIRST_SPECIAL_RADIO || radio > NXDN_TYPE_C_LAST_SPECIAL_RADIO) &&
                        radio != NXDN_MAX_ID);
            default -> false;
        };
    }

    /**
     * Verifies that decoded NXDN identifiers use the saved channel's address domain.  The saved decoder mode is the
     * authority; decoded identifiers are evidence inside that mode and can never reclassify the channel.
     */
    public static boolean nxdnIdentifiersMatchDomain(IdentifierCollection identifiers,
                                                      TrunkedIdentityDomain identityDomain)
    {
        if(identityDomain != TrunkedIdentityDomain.NXDN_TYPE_C &&
            identityDomain != TrunkedIdentityDomain.NXDN_TYPE_D)
        {
            return false;
        }

        boolean typeD = identityDomain == TrunkedIdentityDomain.NXDN_TYPE_D;
        if(identifiers != null)
        {
            for(Identifier<?> identifier: identifiers.getIdentifiers())
            {
                if(!nxdnIdentifierMatchesDomain(identifier, identityDomain))
                {
                    return false;
                }
            }
        }

        return true;
    }

    /** Checks one decoded NXDN identity against the saved channel's authoritative address domain. */
    public static boolean nxdnIdentifierMatchesDomain(Identifier<?> identifier,
                                                       TrunkedIdentityDomain identityDomain)
    {
        if(identityDomain != TrunkedIdentityDomain.NXDN_TYPE_C &&
            identityDomain != TrunkedIdentityDomain.NXDN_TYPE_D)
        {
            return false;
        }

        boolean typeD = identityDomain == TrunkedIdentityDomain.NXDN_TYPE_D;
        if(identifier instanceof NXDNTalkgroupIdentifier talkgroup)
        {
            return talkgroup.isTypeD() == typeD;
        }
        if(identifier instanceof NXDNRadioIdentifier radio)
        {
            return radio.isTypeD() == typeD;
        }
        return true;
    }
}
