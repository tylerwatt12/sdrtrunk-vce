/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.identifier.talkgroup;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentityResolutionEvidence;
import io.github.dsheirer.module.decode.traffic.P25TalkgroupIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.protocol.Protocol;

/** Group identity and explicitly observed local address. Numeric coincidence is not assignment evidence. */
public record ResolvedTalkgroupIdentity(Protocol protocol, Integer observedLocalId,
                                       P25TalkgroupIdentity talkgroup, IdentityResolutionEvidence evidence)
{
    public ResolvedTalkgroupIdentity
    {
        boolean p25 = protocol == Protocol.APCO25 || protocol == Protocol.APCO25_PHASE2;
        if(protocol == null || evidence == null || observedLocalId == null && talkgroup == null ||
            observedLocalId != null && (observedLocalId < 1 || p25 && observedLocalId >= 0xFFFF) ||
            talkgroup != null && !p25 || evidence == IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT && (talkgroup == null || observedLocalId == null) ||
            evidence == IdentityResolutionEvidence.UNRESOLVED && talkgroup != null)
            throw new IllegalArgumentException("Talkgroup identity requires explicit usable evidence");
    }

    public static ResolvedTalkgroupIdentity from(Identifier<?> identifier)
    {
        if(!(identifier instanceof TalkgroupIdentifier group) || group.getProtocol() == null) return null;
        if(group instanceof FullyQualifiedTalkgroupIdentifier qualified)
        {
            P25TalkgroupIdentity home = P25TalkgroupIdentity.from(qualified);
            return home != null ? new ResolvedTalkgroupIdentity(group.getProtocol(), qualified.getLocalAddress(),
                home, qualified.getResolutionEvidence()) : null;
        }
        int local = group.getValue();
        boolean p25 = group.getProtocol() == Protocol.APCO25 || group.getProtocol() == Protocol.APCO25_PHASE2;
        return local > 0 && (!p25 || local < 0xFFFF) ?
            new ResolvedTalkgroupIdentity(group.getProtocol(), local, null, IdentityResolutionEvidence.UNRESOLVED) : null;
    }

    /** Caller must first establish the receiving-system scope. This alone does not prove call equality. */
    public boolean matchesWithinScope(ResolvedTalkgroupIdentity other, String servingSystemKey)
    {
        if(other == null || protocol != other.protocol) return false;
        if(talkgroup != null && other.talkgroup != null) return talkgroup.equals(other.talkgroup);
        if(observedLocalId != null && observedLocalId.equals(other.observedLocalId)) return true;
        if(!RadioSystemKey.isP25Native(servingSystemKey) || (talkgroup != null) == (other.talkgroup != null)) return false;
        ResolvedTalkgroupIdentity qualified = talkgroup != null ? this : other;
        ResolvedTalkgroupIdentity local = talkgroup == null ? this : other;
        return qualified.observedLocalId == null && local.observedLocalId != null &&
            qualified.talkgroup.talkgroupId() == local.observedLocalId &&
            qualified.talkgroup.homeWacn() == Integer.parseInt(servingSystemKey, 4, 9, 16) &&
            qualified.talkgroup.homeSystemId() == Integer.parseInt(servingSystemKey, 10, 13, 16);
    }
}
