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

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
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

    @Test
    void capturesTalkgroupStatusAndAliasIdsFromOneLookupPublication()
    {
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25);
        AliasList aliasList = new AliasList(definition);
        Alias alias = new Alias("Dispatch");
        alias.setId(101);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 1200));
        aliasList.addAlias(alias);
        Identifier<?> destination = APCO25Talkgroup.create(1200);
        IdentifierCollection identifiers = new IdentifierCollection(List.of(destination))
        {
            private int mReads;

            @Override
            public List<Identifier> getIdentifiers()
            {
                if(++mReads == 2)
                {
                    aliasList.removeAlias(alias);
                }

                return super.getIdentifiers();
            }
        };

        ResolvedCallPolicy policy = ResolvedCallPolicy.capture(snapshot(aliasList, identifiers));
        ResolvedCallPolicy.MatchContext context = policy.matchContexts().getFirst();

        assertTrue(aliasList.getAliases(destination).isEmpty(), "The test must publish a replacement lookup index");
        assertEquals(AliasList.TalkgroupMatchStatus.MATCHED, context.talkgroupMatchStatus());
        assertEquals(Set.of(101L), context.matchedAliasIds());
    }

    private static AudioCallSnapshot snapshot(Identifier<?> destination)
    {
        return snapshot(null, new IdentifierCollection(List.of(destination)));
    }

    private static AudioCallSnapshot snapshot(AliasList aliasList, IdentifierCollection identifiers)
    {
        long now = System.currentTimeMillis();
        AudioCallId callId = new AudioCallId(1, 1, 0);
        return new AudioCallSnapshot(callId, null, aliasList,
            identifiers, Set.of(), now, now, 1, 1, now, now,
            false, false, CallEncryptionState.CLEAR, false, null, VoiceCallQuality.EMPTY,
            CallLegId.from(callId), null, null);
    }
}
