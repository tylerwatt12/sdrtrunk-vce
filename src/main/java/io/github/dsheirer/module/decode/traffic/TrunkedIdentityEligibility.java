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
import io.github.dsheirer.module.decode.dmr.message.type.Tier3Gateway;
import io.github.dsheirer.protocol.Protocol;

/**
 * Protocol-aware identity eligibility shared by call tracking and durable directory projections.
 */
public final class TrunkedIdentityEligibility
{
    private static final int P25_EVERYONE_TALKGROUP = 0xFFFF;
    private static final int P25_FIRST_SPECIAL_RADIO = 0xFFFFFC;
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
            case APCO25, APCO25_PHASE2 -> radio < P25_FIRST_SPECIAL_RADIO;
            case DMR -> radio <= DMR_MAX_ID && !Tier3Gateway.isGateway(radio);
            case NXDN -> radio <= NXDN_MAX_ID &&
                (identityDomain == TrunkedIdentityDomain.NXDN_TYPE_D ||
                    (radio < NXDN_TYPE_C_FIRST_SPECIAL_RADIO || radio > NXDN_TYPE_C_LAST_SPECIAL_RADIO) &&
                        radio != NXDN_MAX_ID);
            default -> false;
        };
    }
}
