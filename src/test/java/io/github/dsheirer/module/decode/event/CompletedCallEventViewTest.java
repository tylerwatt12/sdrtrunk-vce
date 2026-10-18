/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.event;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.call.*;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CompletedCallEventViewTest
{
    private static final String CHANNEL = "00000000-0000-4000-8000-000000000001";

    @Test void finalLogicalIdentitiesTimingAndEncryptionSurviveACompactAudioFreeHandoff() throws Exception
    {
        var source = source(DecoderType.P25_PHASE1, "p25:bee00:348");
        var nativeSnapshot = snapshot(source, List.of(APCO25RadioIdentifier.createFrom(67), APCO25Talkgroup.create(42)),
            1100, 2000, CallEncryptionState.CLEAR, true);
        var resolved = snapshot(source, List.of(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(67, 0xABCDE, 0x123, 1001),
            APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(42, 0xABCDE, 0x124, 2001)),
            1000, 2500, CallEncryptionState.ENCRYPTED, true);
        var summary = new CallLegSummary(nativeSnapshot.callLegId(), source, 1100, 2000, VoiceCallQuality.EMPTY,
            7200, false, false, true, null, (Identifier<?>)null, null, List.of(), 851_012_500L, 1);
        var completed = new CompletedAudioCall(new LogicalCallId(9, 12), resolved, List.of(), null,
            List.of(summary), nativeSnapshot, null);
        var view = DecodeEventViewService.completedCallView(completed, null);
        assertEquals("completed-call:9:12", view.eventId());
        assertEquals(1000, view.timeStartMs());
        assertEquals(1500, view.durationMs(), "Final logical timing does not depend on retained PCM or native winner duration");
        assertEquals("CALL_GROUP_ENCRYPTED", view.eventType());
        assertEquals("p25:bee00:348", view.servingSystemKey());
        assertEquals(new DecodeEventViewService.CanonicalRadioIdentity(0xABCDE, 0x123, 1001),
            view.fromParty().getFirst().canonicalIdentity());
        assertEquals(new DecodeEventViewService.CanonicalGroupIdentity(0xABCDE, 0x124, 2001),
            view.toParty().getFirst().canonicalGroupIdentity());
        assertEquals(67, view.fromParty().getFirst().observedWorkingId());
        assertEquals(42, view.toParty().getFirst().observedLocalId());
        assertEquals(851_012_500L, view.frequencyHz());
        assertEquals("1:2", view.alertFacts().siteId());
        assertEquals("P25", view.alertFacts().protocolFamily());
        assertEquals("winner:7", view.alertFacts().activityCallToken());
        assertNull(view.sourceBinding(), "A completed call does not borrow a mutable live processing binding");
        assertTrue(view.alertFacts().toneCodes().isEmpty(), "Call completion does not fabricate a tone result");
        assertFalse(completed.hasAudio());
        var json = new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(view);
        assertFalse(json.has("audioBuffers"));
        assertFalse(json.has("snapshot"));
    }

    @Test void finalCallFamiliesAndTypesArePreservedWithoutRecordingPolicy()
    {
        for(boolean encrypted: List.of(false, true))
        {
            var encryption = encrypted ? CallEncryptionState.ENCRYPTED : CallEncryptionState.CLEAR;
            String suffix = encrypted ? "_ENCRYPTED" : "";
            assertEquals("CALL_GROUP" + suffix, type(DecoderType.P25_PHASE1, List.of(APCO25Talkgroup.create(42)), encryption));
            assertEquals("CALL_PATCH_GROUP" + suffix, type(DecoderType.P25_PHASE1, List.of(APCO25PatchGroup.create(42)), encryption));
            assertEquals("CALL_UNIT_TO_UNIT" + suffix, type(DecoderType.P25_PHASE1, List.of(APCO25RadioIdentifier.createTo(42)), encryption));
            assertEquals("CALL" + suffix, type(DecoderType.P25_PHASE1, List.of(), encryption));
        }
        for(var decoder: List.of(DecoderType.NBFM, DecoderType.AM))
        {
            var completed = new CompletedAudioCall(snapshot(source(decoder, null), List.of(), 1000, 3000,
                CallEncryptionState.CLEAR, true), List.of());
            var view = DecodeEventViewService.completedCallView(completed, null);
            assertEquals("CALL", view.eventType());
            assertEquals(decoder.name(), view.protocol());
            assertEquals(decoder.name(), view.alertFacts().protocolFamily());
            assertFalse(completed.snapshot().recordAudio(), "Disabled recording cannot suppress completed-call matching");
        }
        var phaseTwo = new CompletedAudioCall(snapshot(source(DecoderType.P25_PHASE2, "p25:bee00:348"),
            List.of(APCO25Talkgroup.create(42)), 1000, 3000, CallEncryptionState.CLEAR, true), List.of());
        assertEquals("APCO25_PHASE2", DecodeEventViewService.completedCallView(phaseTwo, null).protocol());
        for(var decoder: List.of(DecoderType.DMR, DecoderType.NXDN))
        {
            var completed = new CompletedAudioCall(snapshot(source(decoder, null), List.of(), 1000, 3000,
                CallEncryptionState.CLEAR, true), List.of());
            var view = DecodeEventViewService.completedCallView(completed, null);
            assertEquals((decoder == DecoderType.DMR ? "dmr" : "nxdn-c") + ":channel:" + CHANNEL, view.servingSystemKey());
        }
    }

    @Test void finalLocalAliasesUseTheCapturedServingScopeAndProjectionRequiresCompletion()
    {
        var definition = new AliasListDefinition("Dispatch", AliasListFamily.P25);
        definition.setId(1);
        var aliases = new AliasList(definition);
        var alias = new Alias("Mutual Aid Dispatch"); alias.setId(81);
        alias.setMatchIdentifier(new io.github.dsheirer.alias.id.talkgroup.Talkgroup(Protocol.APCO25, 42));
        aliases.addAlias(alias);
        var model = new AliasModel()
        {
            @Override public AliasList getAliasList(IdentifierCollection ignored) { return aliases; }
        };
        var source = source(DecoderType.P25_PHASE1, "p25:bee00:348");
        var completed = new CompletedAudioCall(snapshot(source, List.of(
            APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(42, 0xABCDE, 0x124, 2001)),
            1000, 3000, CallEncryptionState.CLEAR, true), List.of());
        try(var projector = new DecodeEventViewService(null, model))
        {
            var view = projector.completedCallView(completed);
            assertEquals("Mutual Aid Dispatch", view.toAliases());
            assertEquals(List.of(81L), view.alertFacts().aliasIds());
        }
        var incomplete = new CompletedAudioCall(snapshot(source, List.of(), 1000, 3000,
            CallEncryptionState.CLEAR, false), List.of());
        assertThrows(IllegalArgumentException.class, () -> DecodeEventViewService.completedCallView(incomplete, null));
    }

    private static String type(DecoderType decoder, List<? extends Identifier<?>> identifiers, CallEncryptionState encryption)
    {
        var completed = new CompletedAudioCall(snapshot(source(decoder, "p25:bee00:348"), identifiers,
            1000, 3000, encryption, true), List.of());
        return DecodeEventViewService.completedCallView(completed, null).eventType();
    }

    private static CallLegSource source(DecoderType decoder, String system)
    {
        boolean p25 = decoder == DecoderType.P25_PHASE1 || decoder == DecoderType.P25_PHASE2;
        return new CallLegSource(decoder, CHANNEL, "North Dispatch", null, 0,
            p25 ? new P25SiteIdentity(0xBEE00, 0x348, 1, 2) : null, null,
            decoder == DecoderType.NBFM || decoder == DecoderType.AM ? ChannelKind.CONVENTIONAL : ChannelKind.TRUNKED,
            false, system);
    }

    private static AudioCallSnapshot snapshot(CallLegSource source, List<? extends Identifier<?>> identifiers,
        long start, long end, CallEncryptionState encryption, boolean complete)
    {
        return new AudioCallSnapshot(new AudioCallId(5, 7, 1), null, null,
            new IdentifierCollection(new java.util.ArrayList<Identifier>(identifiers)), Set.of(), start, end, 1, 0, start, end, false, complete,
            encryption, false, null, VoiceCallQuality.EMPTY, new CallLegId(5, 7, 1), source, null, null, "winner:7");
    }
}
