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

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityEligibility;
import io.github.dsheirer.protocol.Protocol;

/**
 * Protocol-aware policy for identities that are safe to project into subscriber and talkgroup directories.
 *
 * <p>Detailed activity can retain protocol broadcast, gateway and infrastructure addresses.  This policy prevents
 * those special values from becoming normal directory rows while preserving each protocol's real subscriber address
 * space.</p>
 */
final class TrunkedIdentityPolicy
{
    static final int PROTOCOL_P25 = 1;
    static final int PROTOCOL_DMR = 3;
    static final int PROTOCOL_NXDN = 4;

    static final int IDENTITY_KIND_TALKGROUP = 1;
    static final int IDENTITY_KIND_RADIO = 2;
    static final int IDENTITY_KIND_PATCH_GROUP = 3;

    private TrunkedIdentityPolicy()
    {
    }

    static int protocolFamilyCode(String protocol)
    {
        if(protocol == null)
        {
            return 0;
        }

        String normalized = protocol.strip().toUpperCase(java.util.Locale.ROOT);

        if(normalized.startsWith("APCO25") || normalized.startsWith("P25"))
        {
            return PROTOCOL_P25;
        }
        else if(normalized.startsWith("DMR"))
        {
            return PROTOCOL_DMR;
        }
        else if(normalized.startsWith("NXDN"))
        {
            return PROTOCOL_NXDN;
        }

        return 0;
    }

    static int protocolFamilyCode(int receiverProtocolCode)
    {
        return switch(receiverProtocolCode)
        {
            case 1, 2 -> PROTOCOL_P25;
            case PROTOCOL_DMR -> PROTOCOL_DMR;
            case PROTOCOL_NXDN -> PROTOCOL_NXDN;
            default -> 0;
        };
    }

    static boolean isSupportedProtocol(int protocolCode)
    {
        return protocolCode == PROTOCOL_P25 || protocolCode == PROTOCOL_DMR || protocolCode == PROTOCOL_NXDN;
    }

    static Integer identityKindCode(String targetKind)
    {
        if(Form.TALKGROUP.name().equals(targetKind))
        {
            return IDENTITY_KIND_TALKGROUP;
        }
        else if(Form.RADIO.name().equals(targetKind))
        {
            return IDENTITY_KIND_RADIO;
        }
        else if(Form.PATCH_GROUP.name().equals(targetKind))
        {
            return IDENTITY_KIND_PATCH_GROUP;
        }

        return null;
    }

    static boolean isDirectoryIdentity(int protocolCode, P25ActivityLogRecords.IdentityDomain identityDomain,
                                       int identityKindCode, Integer identifier)
    {
        return switch(identityKindCode)
        {
            case IDENTITY_KIND_TALKGROUP -> isDirectoryTalkgroup(protocolCode, identityDomain, identifier);
            case IDENTITY_KIND_RADIO -> isDirectoryRadio(protocolCode, identityDomain, identifier);
            case IDENTITY_KIND_PATCH_GROUP -> protocolCode == PROTOCOL_P25 &&
                isDirectoryTalkgroup(protocolCode, identityDomain, identifier);
            default -> false;
        };
    }

    static boolean isDirectoryTalkgroup(int protocolCode, P25ActivityLogRecords.IdentityDomain identityDomain,
                                        Integer talkgroup)
    {
        return TrunkedIdentityEligibility.isEligible(protocol(protocolCode), domain(identityDomain),
            Form.TALKGROUP, talkgroup);
    }

    static boolean isDirectoryRadio(int protocolCode, P25ActivityLogRecords.IdentityDomain identityDomain,
                                    Integer radio)
    {
        return TrunkedIdentityEligibility.isEligible(protocol(protocolCode), domain(identityDomain),
            Form.RADIO, radio);
    }

    private static Protocol protocol(int protocolCode)
    {
        return switch(protocolCode)
        {
            case PROTOCOL_P25 -> Protocol.APCO25;
            case PROTOCOL_DMR -> Protocol.DMR;
            case PROTOCOL_NXDN -> Protocol.NXDN;
            default -> Protocol.UNKNOWN;
        };
    }

    private static TrunkedIdentityDomain domain(P25ActivityLogRecords.IdentityDomain identityDomain)
    {
        return switch(identityDomain != null ? identityDomain : P25ActivityLogRecords.IdentityDomain.STANDARD)
        {
            case STANDARD -> TrunkedIdentityDomain.STANDARD;
            case NXDN_TYPE_C -> TrunkedIdentityDomain.NXDN_TYPE_C;
            case NXDN_TYPE_D -> TrunkedIdentityDomain.NXDN_TYPE_D;
        };
    }
}
