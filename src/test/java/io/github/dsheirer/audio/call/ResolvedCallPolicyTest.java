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
package io.github.dsheirer.audio.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNFullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ResolvedCallPolicyTest
{
    @Test
    void nxdnIdentityKeepsItsSystemWithoutInventingANetwork()
    {
        NXDNFullyQualifiedTalkgroupIdentifier systemOne =
            NXDNFullyQualifiedTalkgroupIdentifier.createTo(11, 1200);
        ResolvedCallPolicy.DestinationIdentity first =
            ResolvedCallPolicy.DestinationIdentity.from(systemOne);
        ResolvedCallPolicy.DestinationIdentity same =
            ResolvedCallPolicy.DestinationIdentity.from(
                NXDNFullyQualifiedTalkgroupIdentifier.createTo(11, 1200));
        ResolvedCallPolicy.DestinationIdentity differentSystem =
            ResolvedCallPolicy.DestinationIdentity.from(
                NXDNFullyQualifiedTalkgroupIdentifier.createTo(12, 1200));

        assertEquals(Protocol.NXDN, first.protocol());
        assertEquals(1200, first.talkgroup());
        assertTrue(first.fullyQualified());
        assertNull(first.qualifier().networkId());
        assertEquals(11, first.qualifier().systemId());
        assertEquals(first, same);
        assertNotEquals(first, differentSystem);
        assertTrue(first.matches(same));
        assertFalse(first.matches(differentSystem));
        assertEquals(systemOne, NXDNFullyQualifiedTalkgroupIdentifier.createTo(11, 1200));
        assertNotEquals(systemOne, NXDNFullyQualifiedTalkgroupIdentifier.createTo(12, 1200));

        AudioCallRecordingMetadata metadata = AudioCallRecordingMetadata.captureAtSnapshot(null,
            new IdentifierCollection(List.of(systemOne)));
        assertEquals("1200", metadata.destinationValue());
        assertEquals("NXDN:fq:11:1200", metadata.destinationIdentity());
        assertEquals("NXDN:fq:11:1200", metadata.destinationMatcherIdentity());
    }

    @Test
    void p25IdentityKeepsNetworkAndSystemInTheSameCanonicalQualifierShape()
    {
        ResolvedCallPolicy.DestinationIdentity identity =
            ResolvedCallPolicy.DestinationIdentity.from(
                APCO25FullyQualifiedTalkgroupIdentifier.createTo(99, 0xABCDE, 0x321, 1200));

        assertEquals(Protocol.APCO25, identity.protocol());
        assertEquals(1200, identity.talkgroup());
        assertEquals(0xABCDE, identity.qualifier().networkId().intValue());
        assertEquals(0x321, identity.qualifier().systemId());
        assertTrue(identity.qualifier().hasNetwork());
    }

    @Test
    void resolvedPolicyCapturesQualifiedPatchMembers()
    {
        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(500));
        TalkgroupIdentifier qualifiedMember =
            APCO25FullyQualifiedTalkgroupIdentifier.createTo(501, 0xABCDE, 0x321, 700);
        patchGroup.addPatchedTalkgroup(qualifiedMember);
        ResolvedCallPolicy policy = ResolvedCallPolicy.capture(snapshot(APCO25PatchGroup.create(patchGroup)));
        Set<ResolvedCallPolicy.DestinationIdentity> destinations = policy.matchContexts().stream()
            .flatMap(context -> context.destinationIdentities().stream())
            .collect(Collectors.toSet());

        assertTrue(destinations.contains(new ResolvedCallPolicy.DestinationIdentity(
            Protocol.APCO25, 500, null)));
        assertTrue(destinations.contains(new ResolvedCallPolicy.DestinationIdentity(
            Protocol.APCO25, 700,
            ResolvedCallPolicy.DestinationQualifier.networkAndSystem(0xABCDE, 0x321))));
    }

    private static AudioCallSnapshot snapshot(Identifier<?> destination)
    {
        long now = System.currentTimeMillis();
        return new AudioCallSnapshot(new AudioCallId(1, 1, 0), null, null,
            new IdentifierCollection(List.of(destination)), Set.of(), now, now, 1, 1, now, now,
            false, false, false, false, 50, false);
    }
}
