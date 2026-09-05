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
import io.github.dsheirer.identifier.IncompleteIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
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

    /**
     * Tests the durable identity carried by a decoded identifier.  A fully-qualified P25 identifier is keyed by its
     * home identity, while its temporary local address is validated separately as observation evidence.
     */
    public static boolean isEligibleDecodedIdentifier(Protocol protocol, TrunkedIdentityDomain identityDomain,
                                                       Identifier<?> identifier)
    {
        if(identifier == null || identifier instanceof IncompleteIdentifier)
        {
            return false;
        }

        Form form = identifier.getForm();
        Identifier<?> primary = identifier;
        Integer observedLocalId = number(identifier);

        if(identifier instanceof PatchGroupIdentifier patch && patch.getValue() != null &&
            patch.getValue().getPatchGroup() != null)
        {
            primary = patch.getValue().getPatchGroup();
            observedLocalId = patch.getValue().getPatchGroup().getValue();
        }

        if((protocol == Protocol.APCO25 || protocol == Protocol.APCO25_PHASE2) &&
            primary instanceof FullyQualifiedRadioIdentifier radio && radio.getProtocol() == Protocol.APCO25)
        {
            return validP25Home(radio.getWacn(), radio.getSystem()) &&
                isEligible(protocol, identityDomain, form, radio.getRadio()) &&
                isObservedLocalEligible(protocol, identityDomain, form, observedLocalId, true);
        }

        if((protocol == Protocol.APCO25 || protocol == Protocol.APCO25_PHASE2) &&
            primary instanceof FullyQualifiedTalkgroupIdentifier talkgroup &&
            talkgroup.getProtocol() == Protocol.APCO25)
        {
            return validP25Home(talkgroup.getWacn(), talkgroup.getSystem()) &&
                isEligible(protocol, identityDomain, form, talkgroup.getTalkgroup()) &&
                isObservedLocalEligible(protocol, identityDomain, form, observedLocalId, true);
        }

        return isEligible(protocol, identityDomain, form, observedLocalId);
    }

    /**
     * Validates an over-the-air local address without confusing it with a durable home identity.  P25 fully-qualified
     * radios may use any assignable 24-bit working address, including values above the permanent Unit ID range.
     */
    public static boolean isObservedLocalEligible(Protocol protocol, TrunkedIdentityDomain identityDomain,
                                                   Form form, Integer identifier, boolean fullyQualified)
    {
        if(!fullyQualified || protocol != Protocol.APCO25 && protocol != Protocol.APCO25_PHASE2)
        {
            return isEligible(protocol, identityDomain, form, identifier);
        }

        if(form == null || identifier == null || identifier < 0)
        {
            return false;
        }

        return switch(form)
        {
            case TALKGROUP, PATCH_GROUP -> identifier <= RadioSystemIdentityKey.MAX_P25_GROUP_ID;
            case RADIO -> identifier <= RadioSystemIdentityKey.MAX_P25_WORKING_UNIT_ID;
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

    private static boolean validP25Home(int wacn, int system)
    {
        return wacn >= 0 && wacn <= 0xFFFFF && system >= 0 && system <= 0xFFF;
    }

    private static Integer number(Identifier<?> identifier)
    {
        return identifier != null && identifier.getValue() instanceof Number number ? number.intValue() : null;
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
