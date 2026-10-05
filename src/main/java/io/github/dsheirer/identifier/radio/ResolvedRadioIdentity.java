/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.identifier.radio;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.protocol.Protocol;

/**
 * Immutable radio identity facts captured at the receiver boundary. A permanent P25 identity and an observed
 * working address are independent fields. Equality of their numbers never establishes a relationship.
 * Local identities remain scoped externally by their serving system or conventional channel.
 */
public record ResolvedRadioIdentity(Protocol protocol, Integer observedWorkingId,
                                    P25SubscriberIdentity subscriber, Evidence evidence)
{
    public enum Evidence { DIRECT, CONFIRMED_ASSIGNMENT, UNRESOLVED, UNKNOWN }

    public ResolvedRadioIdentity
    {
        boolean p25 = protocol == Protocol.APCO25 || protocol == Protocol.APCO25_PHASE2;
        if(protocol == null || evidence == null || observedWorkingId == null && subscriber == null ||
            observedWorkingId != null && (observedWorkingId < 1 || p25 && observedWorkingId > 0xFFFFFC) ||
            subscriber != null && !p25 ||
            evidence == Evidence.CONFIRMED_ASSIGNMENT && (subscriber == null || observedWorkingId == null) ||
            evidence == Evidence.UNRESOLVED && subscriber != null)
        {
            throw new IllegalArgumentException("Radio identity requires explicit usable evidence");
        }
    }

    public static ResolvedRadioIdentity from(Identifier<?> identifier)
    {
        if(!(identifier instanceof RadioIdentifier radio) || radio.getProtocol() == null)
        {
            return null;
        }
        if(radio instanceof FullyQualifiedRadioIdentifier qualified)
        {
            P25SubscriberIdentity subscriber = P25SubscriberIdentity.from(qualified);
            return subscriber != null ? new ResolvedRadioIdentity(radio.getProtocol(),
                qualified.getWorkingAddress(), subscriber, qualified.getResolutionEvidence()) : null;
        }
        int local = radio.getValue();
        boolean p25 = radio.getProtocol() == Protocol.APCO25 || radio.getProtocol() == Protocol.APCO25_PHASE2;
        if(local < 1 || p25 && local > 0xFFFFFC)
        {
            return null;
        }
        return new ResolvedRadioIdentity(radio.getProtocol(), local, null, Evidence.UNRESOLVED);
    }

    /**
     * Identity compatibility only after the caller has established the same serving-system/channel scope.
     * This is an input to call matching, not proof that two receptions contain the same transmission.
     */
    public boolean matchesWithinScope(ResolvedRadioIdentity other)
    {
        if(other == null || protocol != other.protocol)
        {
            return false;
        }
        if(subscriber != null && other.subscriber != null)
        {
            return subscriber.equals(other.subscriber);
        }
        return observedWorkingId != null && observedWorkingId.equals(other.observedWorkingId);
    }
}
