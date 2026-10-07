/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.identifier.radio;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.protocol.Protocol;
import org.junit.jupiter.api.Test;

class ResolvedRadioIdentityTest
{
    @Test
    void identityOnlyEvidenceCannotMatchAnUnresolvedSameNumberRadio()
    {
        var permanent = ResolvedRadioIdentity.from(APCO25FullyQualifiedRadioIdentifier.createFrom(
            501, 0xABCDE, 0x123, 501));
        var local = ResolvedRadioIdentity.from(APCO25RadioIdentifier.createFrom(501));
        assertNull(permanent.observedWorkingId());
        assertFalse(permanent.matchesWithinScope(local));
        assertFalse(local.matchesWithinScope(permanent));
        assertEquals(ResolvedRadioIdentity.Evidence.DIRECT, permanent.evidence());
    }

    @Test
    void homeSystemContextMatchesCanonicalAndNativeAddressInBothDirections()
    {
        var permanent = ResolvedRadioIdentity.from(APCO25FullyQualifiedRadioIdentifier.createFrom(
            10_900_077, 0xBEE00, 0x123, 10_900_077));
        var local = ResolvedRadioIdentity.from(APCO25RadioIdentifier.createFrom(10_900_077));
        assertTrue(permanent.matchesWithinScope(local, "p25:bee00:123"));
        assertTrue(local.matchesWithinScope(permanent, "p25:bee00:123"));
        assertNull(permanent.observedWorkingId(), "A home-system address is not a Working-ID assignment");
        assertFalse(permanent.matchesWithinScope(local, "p25:bee01:123"));
        assertFalse(permanent.matchesWithinScope(local, "p25:bee00:124"));
        assertFalse(permanent.matchesWithinScope(local, null));
        assertFalse(permanent.matchesWithinScope(local, "not-a-system"));
        assertFalse(permanent.matchesWithinScope(ResolvedRadioIdentity.from(
            APCO25RadioIdentifier.createFrom(10_900_078)), "p25:bee00:123"));
    }

    @Test
    void servingSystemDoesNotOverrideAnExplicitDifferentWorkingAddressOrKnownHome()
    {
        var qualified = ResolvedRadioIdentity.from(APCO25FullyQualifiedRadioIdentifier
            .createFromWithWorkingAddress(501, 0xABCDE, 0x123, 777));
        assertFalse(qualified.matchesWithinScope(ResolvedRadioIdentity.from(
            APCO25RadioIdentifier.createFrom(777)), "p25:abcde:123"));
        assertTrue(qualified.matchesWithinScope(ResolvedRadioIdentity.from(
            APCO25RadioIdentifier.createFrom(501)), "p25:abcde:123"));
        assertFalse(qualified.matchesWithinScope(ResolvedRadioIdentity.from(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xABCDE, 0x124, 777)),
            "p25:abcde:123"));
    }

    @Test
    void explicitOrConfirmedWorkingAddressCanMatchALocalObservationWithinItsScope()
    {
        var qualified = APCO25FullyQualifiedRadioIdentifier.createWithWorkingAddress(501, 0xABCDE,
            0x123, 777, Role.FROM, ResolvedRadioIdentity.Evidence.CONFIRMED_ASSIGNMENT);
        var canonical = ResolvedRadioIdentity.from(qualified);
        assertTrue(canonical.matchesWithinScope(ResolvedRadioIdentity.from(APCO25RadioIdentifier.createFrom(501))));
        assertEquals(ResolvedRadioIdentity.Evidence.CONFIRMED_ASSIGNMENT, canonical.evidence());
        assertFalse(canonical.matchesWithinScope(ResolvedRadioIdentity.from(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xABCDE, 0x123, 778))));
    }

    @Test
    void ordinaryDmrAndNxdnRetainLocalIdentityAndProtocolScope()
    {
        var dmr = ResolvedRadioIdentity.from(DMRRadio.createFrom(501));
        var nxdn = ResolvedRadioIdentity.from(NXDNRadioIdentifier.createFrom(501));
        assertNull(dmr.subscriber());
        assertNull(nxdn.subscriber());
        assertTrue(dmr.matchesWithinScope(ResolvedRadioIdentity.from(DMRRadio.createFrom(501))));
        assertFalse(dmr.matchesWithinScope(nxdn));
    }

    @Test
    void assignmentProvenanceRequiresBothIndependentIdentityFacts()
    {
        assertThrows(IllegalArgumentException.class, () -> new ResolvedRadioIdentity(Protocol.APCO25,
            null, new P25SubscriberIdentity(0xABCDE, 0x123, 777), ResolvedRadioIdentity.Evidence.CONFIRMED_ASSIGNMENT));
        assertNull(ResolvedRadioIdentity.from(APCO25RadioIdentifier.createFrom(0xFFFFFF)));
    }
}
