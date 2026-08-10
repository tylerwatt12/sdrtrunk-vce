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

package io.github.dsheirer.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import io.github.dsheirer.audio.call.MutableAudioCallBuilder;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class UnmatchedTalkgroupPolicyTest
{
    @Test
    void validatesAndFreezesPolicyValues()
    {
        UnmatchedTalkgroupPolicy policy = new UnmatchedTalkgroupPolicy(25, true,
            List.of(" Primary ", "Archive"));

        assertEquals(25, policy.getPlaybackPriority());
        assertTrue(policy.isRecordEnabled());
        assertEquals(List.of("Primary", "Archive"), policy.getStreamDestinationNames());
        assertThrows(UnsupportedOperationException.class,
            () -> policy.getStreamDestinationNames().add("Another"));
        assertThrows(IllegalArgumentException.class,
            () -> new UnmatchedTalkgroupPolicy(0, false, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new UnmatchedTalkgroupPolicy(101, false, List.of()));
        assertThrows(IllegalArgumentException.class,
            () -> new UnmatchedTalkgroupPolicy(1, false, List.of(" ")));
        assertThrows(IllegalArgumentException.class,
            () -> new UnmatchedTalkgroupPolicy(1, false, List.of("Same", "Same")));
    }

    @Test
    void exactAndRangeAliasesTakePrecedenceWithoutTurningThePolicyIntoAnAlias()
    {
        UnmatchedTalkgroupPolicy policy = new UnmatchedTalkgroupPolicy(-1, true, List.of("Unknown"));
        AliasList aliasList = new AliasList(new AliasListDefinition("County", AliasListFamily.P25, policy));
        Alias range = alias("Range", new TalkgroupRange(Protocol.APCO25, 1, 200), 40, false, "Range Stream");
        Alias exact = alias("Exact", new Talkgroup(Protocol.APCO25, 100), 20, false, "Exact Stream");
        aliasList.addAliases(List.of(range, exact));

        assertSame(exact, aliasList.getAliases(APCO25Talkgroup.create(100)).getFirst());
        assertNull(aliasList.getUnmatchedTalkgroupPolicy(APCO25Talkgroup.create(100)));
        assertSame(range, aliasList.getAliases(APCO25Talkgroup.create(150)).getFirst());
        assertNull(aliasList.getUnmatchedTalkgroupPolicy(APCO25Talkgroup.create(150)));
        assertTrue(aliasList.getAliases(APCO25Talkgroup.create(300)).isEmpty());
        assertSame(policy, aliasList.getUnmatchedTalkgroupPolicy(APCO25Talkgroup.create(300)));

        MutableAudioCallBuilder exactCall = new MutableAudioCallBuilder(aliasList, 1);
        exactCall.addIdentifiers(List.of(APCO25Talkgroup.create(100)));
        assertEquals(20, exactCall.getMonitorPriority());
        assertFalse(exactCall.isRecordAudio());
        assertEquals(Set.of("Exact Stream"), destinations(exactCall));

        MutableAudioCallBuilder rangeCall = new MutableAudioCallBuilder(aliasList, 1);
        rangeCall.addIdentifiers(List.of(APCO25Talkgroup.create(150)));
        assertEquals(40, rangeCall.getMonitorPriority());
        assertFalse(rangeCall.isRecordAudio());
        assertEquals(Set.of("Range Stream"), destinations(rangeCall));
    }

    @Test
    void appliesUnmatchedPlaybackRecordingAndStreamingToP25DmrAndNxdn()
    {
        List<ProtocolCase> cases = List.of(
            new ProtocolCase(AliasListFamily.P25, APCO25Talkgroup.create(31001)),
            new ProtocolCase(AliasListFamily.DMR, DMRTalkgroup.create(31002)),
            new ProtocolCase(AliasListFamily.NXDN, NXDNTalkgroupIdentifier.createTo(31003)));

        for(ProtocolCase protocolCase: cases)
        {
            UnmatchedTalkgroupPolicy policy =
                new UnmatchedTalkgroupPolicy(7, true, List.of("Live", "Archive"));
            AliasList aliasList = new AliasList(new AliasListDefinition(
                protocolCase.family().name(), protocolCase.family(), policy));
            MutableAudioCallBuilder builder = new MutableAudioCallBuilder(aliasList, 1);

            builder.addIdentifiers(List.of(protocolCase.identifier()));

            assertTrue(aliasList.getAliases(protocolCase.identifier()).isEmpty());
            assertSame(policy, aliasList.getUnmatchedTalkgroupPolicy(protocolCase.identifier()));
            assertEquals(7, builder.getMonitorPriority());
            assertTrue(builder.isRecordAudio());
            assertEquals(Set.of("Live", "Archive"), destinations(builder));

            AudioCallRecordingMetadata metadata = builder.getRecordingMetadata();
            assertNull(metadata.destinationAlias());
            assertEquals(metadata.destinationIdentity(), metadata.destinationMatcherIdentity());
            assertTrue(metadata.destinationTalkgroupRecordEnabled());
        }
    }

    @Test
    void doesNotApplyPolicyAcrossProtocolFamilies()
    {
        UnmatchedTalkgroupPolicy policy = new UnmatchedTalkgroupPolicy(7, true, List.of("Wrong Protocol"));
        AliasList dmrList = new AliasList(new AliasListDefinition("DMR", AliasListFamily.DMR, policy));
        Identifier<?> p25Talkgroup = APCO25Talkgroup.create(31001);

        assertNull(dmrList.getUnmatchedTalkgroupPolicy(p25Talkgroup));
        MutableAudioCallBuilder builder = new MutableAudioCallBuilder(dmrList, 1);
        builder.addIdentifiers(List.of(p25Talkgroup));
        assertFalse(builder.isRecordAudio());
        assertEquals(100, builder.getMonitorPriority());
        assertTrue(builder.getBroadcastChannels().isEmpty());
    }

    @Test
    void unmatchedPatchUsesPolicyUntilARealTalkgroupAliasMatches()
    {
        UnmatchedTalkgroupPolicy policy = new UnmatchedTalkgroupPolicy(8, true, List.of("Unknown"));
        AliasList aliasList = new AliasList(new AliasListDefinition("Patches", AliasListFamily.P25, policy));
        Alias radioAlias = alias("Console", new Radio(Protocol.APCO25, 9001), 30, false, "Console");
        Alias member = alias("Member", new Talkgroup(Protocol.APCO25, 600), 12, false, "Member");
        aliasList.addAliases(List.of(radioAlias, member));
        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(500));
        patchGroup.addPatchedRadio(APCO25RadioIdentifier.createFrom(9001));
        APCO25PatchGroup patchIdentifier = APCO25PatchGroup.create(patchGroup);

        //A radio Alias may add its own actions, but it does not define the destination talkgroup.
        assertSame(policy, aliasList.getUnmatchedTalkgroupPolicy(patchIdentifier));
        MutableAudioCallBuilder unmatched = new MutableAudioCallBuilder(aliasList, 1);
        unmatched.addIdentifiers(List.of(patchIdentifier));
        assertTrue(unmatched.isRecordAudio());
        assertEquals(8, unmatched.getMonitorPriority());
        assertEquals(Set.of("Unknown", "Console"), destinations(unmatched));

        PatchGroup attributedPatch = new PatchGroup(APCO25Talkgroup.create(500));
        attributedPatch.addPatchedTalkgroup(APCO25Talkgroup.create(600));
        attributedPatch.addPatchedRadio(APCO25RadioIdentifier.createFrom(9001));
        APCO25PatchGroup attributedIdentifier = APCO25PatchGroup.create(attributedPatch);
        assertNull(aliasList.getUnmatchedTalkgroupPolicy(attributedIdentifier));
        unmatched.addIdentifiers(List.of(attributedIdentifier));

        assertFalse(unmatched.isRecordAudio());
        assertEquals(12, unmatched.getMonitorPriority());
        assertEquals(Set.of("Member", "Console"), destinations(unmatched));
        assertEquals("Member", unmatched.getRecordingMetadata().destinationAlias());
        assertFalse(unmatched.getRecordingMetadata().destinationTalkgroupRecordEnabled());
    }

    @Test
    void talkgroupPromotedToKnownPatchWithdrawsUnmatchedActions()
    {
        UnmatchedTalkgroupPolicy policy = new UnmatchedTalkgroupPolicy(8, true, List.of("Unknown"));
        AliasList aliasList = new AliasList(new AliasListDefinition("Patches", AliasListFamily.P25, policy));
        aliasList.addAlias(alias("Member", new Talkgroup(Protocol.APCO25, 600), 12, false, "Member"));
        MutableAudioCallBuilder builder = new MutableAudioCallBuilder(aliasList, 1);

        builder.addIdentifiers(List.of(APCO25Talkgroup.create(500)));
        assertTrue(builder.isRecordAudio());
        assertEquals(8, builder.getMonitorPriority());
        assertEquals(Set.of("Unknown"), destinations(builder));

        PatchGroup patch = new PatchGroup(APCO25Talkgroup.create(500));
        patch.addPatchedTalkgroup(APCO25Talkgroup.create(600));
        builder.addIdentifiers(List.of(APCO25PatchGroup.create(patch)));

        assertFalse(builder.isRecordAudio());
        assertEquals(12, builder.getMonitorPriority());
        assertEquals(Set.of("Member"), destinations(builder));
        assertEquals("Member", builder.getRecordingMetadata().destinationAlias());
    }

    @Test
    void aliasModelCountsPolicyStreamDestinations()
    {
        AliasListDefinition definition = new AliasListDefinition("County", AliasListFamily.P25,
            new UnmatchedTalkgroupPolicy(100, false, List.of("Old Stream", "Keep")));
        AliasModel model = new AliasModel();
        model.setAliasListDefinitions(List.of(definition));

        assertTrue(model.hasAliasesWithBroadcastChannel("Old Stream"));
        assertFalse(model.hasAliasesWithBroadcastChannel("New Stream"));
    }

    private static Alias alias(String name, io.github.dsheirer.alias.id.AliasID matcher, int priority,
                               boolean record, String destination)
    {
        Alias alias = new Alias(name);
        alias.setMatchIdentifier(matcher);
        alias.setCallPriority(priority);
        alias.setRecordable(record);
        alias.addBroadcastChannel(destination);
        return alias;
    }

    private static Set<String> destinations(MutableAudioCallBuilder builder)
    {
        return builder.getBroadcastChannels().stream().map(BroadcastChannel::getChannelName)
            .collect(Collectors.toSet());
    }

    private record ProtocolCase(AliasListFamily family, Identifier<?> identifier) {}
}
