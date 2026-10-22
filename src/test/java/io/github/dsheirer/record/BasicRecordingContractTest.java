/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.record;

import io.github.dsheirer.identifier.IdentityResolutionEvidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.radio.ResolvedRadioIdentity;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.identifier.tone.P25ToneIdentifier;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.P25RadioPresence;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.P25IdentityResolver;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Shared recording contract with local numeric IDs and unchanged non-identity filename conventions. */
class BasicRecordingContractTest
{
    private static final long WRITER_TIME = LocalDateTime.of(2026, 10, 7, 12, 34, 56)
        .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();

    @TempDir Path mDirectory;

    @Test
    void nxdnZeroKeepsTheExistingFilenameAndIdentifierGrammar()
    {
        var identifiers = new IdentifierCollection(List.of(SystemConfigurationIdentifier.create("System"),
            SiteConfigurationIdentifier.create("Site"), ChannelNameConfigurationIdentifier.create("Channel"),
            NXDNTalkgroupIdentifier.createTo(0), NXDNRadioIdentifier.createFrom(19)));
        for(RecordFormat format: RecordFormat.values())
        {
            assertEquals("20261007_123456_System_Site_Channel__TO_0x0000-NULL-GROUP_FROM_19" + format.getExtension(),
                BasicRecordingContract.filename(identifiers, format, WRITER_TIME, 1, 1));
            assertEquals("20261007_123456_System_Site_Channel__TO_0x0000-NULL-GROUP_FROM_19_V2" + format.getExtension(),
                BasicRecordingContract.filename(identifiers, format, WRITER_TIME, 1, 2));
        }
        assertEquals("0x0000 NULL GROUP", BasicRecordingContract.targetIdentifier(identifiers.getToIdentifier()));
        assertEquals("19", BasicRecordingContract.sourceIdentifier(identifiers.getFromIdentifier()));
    }

    @Test
    void nxdnZeroRetainsContinuousStreamTitleAndExplicitRemapping()
    {
        var aliases = new io.github.dsheirer.alias.AliasList(new io.github.dsheirer.alias.AliasListDefinition(
            "NXDN", io.github.dsheirer.alias.AliasListFamily.NXDN));
        var alias = new io.github.dsheirer.alias.Alias("Dispatch");
        alias.setMatchIdentifier(new io.github.dsheirer.alias.id.talkgroup.Talkgroup(
            io.github.dsheirer.protocol.Protocol.NXDN, 0));
        aliases.addAlias(alias);
        var model = new io.github.dsheirer.alias.AliasModel()
        {
            @Override public io.github.dsheirer.alias.AliasList getAliasList(IdentifierCollection identifiers)
            {
                return aliases;
            }
        };
        var call = io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallFixture
            .decodeGroupCall(aliases, 19, 0).audio();
        var identifiers = call.snapshot().identifierCollection();
        assertEquals("TO:0x0000 NULL GROUP Dispatch FROM:19",
            io.github.dsheirer.audio.broadcast.icecast.IcecastMetadata.getTitle(identifiers, model));
        alias.setStreamTalkgroupAlias(new io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup(900));
        assertEquals("TO:900 FROM:19",
            io.github.dsheirer.audio.broadcast.icecast.IcecastMetadata.getTitle(identifiers, model));
        assertEquals(0, identifiers.getToIdentifier().getValue(), "Output mapping never replaces decoded identity");
    }

    @Test
    void sourceLessAuxiliaryFactsStayUnknownInFilenamesTagsAndStreamTitles()
    {
        var aliasModel = new io.github.dsheirer.alias.AliasModel()
        {
            @Override public io.github.dsheirer.alias.AliasList getAliasList(IdentifierCollection identifiers)
            {
                return io.github.dsheirer.alias.AliasList.empty("test");
            }
        };
        for(var auxiliary: List.of(P25ToneIdentifier.create(new ToneSequence(List.of(new Tone(AmbeTone.DTMF_1, 5)))),
            io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier.create("Name"),
            APCO25RadioIdentifier.createFrom(0),
            io.github.dsheirer.module.decode.p25.identifier.telephone.APCO25TelephoneNumber.createTo("123"),
            io.github.dsheirer.module.decode.p25.identifier.telephone.APCO25TelephoneNumber.createAny("123")))
        {
            var identifiers = new IdentifierCollection(List.of(auxiliary));
            assertNull(io.github.dsheirer.audio.call.AudioCallRecordingMetadata.captureAtSnapshot(null, identifiers)
                .sourceValue());
            assertFalse(BasicRecordingContract.filename(identifiers, RecordFormat.MP3, WRITER_TIME, 1, 1)
                .contains("_FROM_"));
            assertNull(io.github.dsheirer.record.wave.AudioMetadataUtils.getCapturedMetadataMap(identifiers,
                io.github.dsheirer.audio.call.AudioCallRecordingMetadata.captureAtSnapshot(null, identifiers))
                .get(io.github.dsheirer.record.wave.AudioMetadata.ARTIST_NAME));
            assertTrue(io.github.dsheirer.audio.broadcast.icecast.IcecastMetadata.getTitle(identifiers, aliasModel)
                .endsWith("FROM:UNKNOWN"));
        }
    }

    @Test
    void sourceTelephoneMatchesCapturedMetadataFilenameAndTags()
    {
        var telephone = io.github.dsheirer.module.decode.p25.identifier.telephone.APCO25TelephoneNumber
            .createFrom("123");
        var identifiers = new IdentifierCollection(List.of(APCO25RadioIdentifier.createFrom(0), telephone));
        var captured = io.github.dsheirer.audio.call.AudioCallRecordingMetadata.captureAtSnapshot(null, identifiers);
        assertEquals("123", captured.sourceValue());
        assertTrue(BasicRecordingContract.filename(identifiers, RecordFormat.MP3, WRITER_TIME, 1, 1)
            .contains("_FROM_123"));
        assertEquals("123", io.github.dsheirer.record.wave.AudioMetadataUtils
            .getCapturedMetadataMap(identifiers, captured)
            .get(io.github.dsheirer.record.wave.AudioMetadata.ARTIST_NAME));
    }

    @Test
    void plainProtocolsAndNxdnSpecialAddressesRetainTheirUpstreamRepresentation()
    {
        assertEquals("70001", BasicRecordingContract.sourceIdentifier(APCO25RadioIdentifier.createFrom(70001)));
        assertEquals("70001", BasicRecordingContract.sourceIdentifier(DMRRadio.createFrom(70001)));
        assertEquals("7001", BasicRecordingContract.sourceIdentifier(NXDNRadioIdentifier.createFrom(7001)));
        assertEquals("03-0025", BasicRecordingContract.sourceIdentifier(NXDNRadioIdentifier.createTypeDFrom((3 << 11) | 25)));
        assertEquals("0xFFF0 TRUNKING CONTROLLER", BasicRecordingContract.sourceIdentifier(NXDNRadioIdentifier.createFrom(0xFFF0)));
    }

    @Test
    void qualifiedRadiosUseObservedLocalIdsWithoutHomeIdentitySubstitution()
    {
        var differing = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xBEE00, 0x687, 70001);
        assertEquals("501", BasicRecordingContract.sourceIdentifier(differing));
        assertEquals("501", BasicRecordingContract.targetIdentifier(differing));
        assertEquals("501", BasicRecordingContract.filenameIdentifier(differing));
        assertEquals("70001", BasicRecordingContract.sourceIdentifier(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(70001, 0xBEE00, 0x687, 70001)));
        assertEquals("0", BasicRecordingContract.sourceIdentifier(
            APCO25FullyQualifiedRadioIdentifier.createFrom(0, 0xBEE00, 0x687, 70001)));
        assertEquals("501", BasicRecordingContract.sourceIdentifier(
            APCO25FullyQualifiedRadioIdentifier.createFrom(501, 0xBEE00, 0x687, 70001)));
    }

    @Test
    void qualifiedRadioScalarGrammarRetainsZeroAndNeverSubstitutesTheHomeUnit()
    {
        for(int local: List.of(0, 70001, 501))
        {
            var radio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(local,
                0xBEE00, 0x687, 70001);
            assertEquals(Integer.toString(local),
                BasicRecordingContract.sourceIdentifier(radio));
            assertEquals(Integer.toString(local),
                BasicRecordingContract.targetIdentifier(radio));
            assertEquals(Integer.toString(local),
                BasicRecordingContract.filenameIdentifier(radio));
            assertTrue(BasicRecordingContract.filenameIdentifier(radio).matches("[0-9]+"),
                "Qualified radio filename identifiers retain the ordinary scalar grammar");
        }
        assertEquals("501", BasicRecordingContract.sourceIdentifier(
            APCO25FullyQualifiedRadioIdentifier.createFrom(501, 0xBEE00, 0x687, 70001)));
    }

    @Test
    void networkBroadcastPairIsRequiredAndRadioHomeNeverSuppliesServingSystem()
    {
        var radio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(70001,
            0xBEE00, 0x687, 70001);
        for(IdentifierCollection incomplete: List.of(new IdentifierCollection(List.of(radio)),
            new IdentifierCollection(List.of(radio, APCO25Wacn.create(0xBEE00))),
            new IdentifierCollection(List.of(radio, APCO25System.create(0x687)))))
        {
            assertNull(BasicRecordingContract.servingSystemKey(incomplete));
            assertEquals("20261007_123456__FROM_70001.mp3",
                BasicRecordingContract.filename(incomplete, RecordFormat.MP3, WRITER_TIME, 1, 1));
        }

        IdentifierCollection configuredName = new IdentifierCollection(List.of(radio,
            SystemConfigurationIdentifier.create("BEE00.687")));
        assertNull(BasicRecordingContract.servingSystemKey(configuredName));
        assertEquals("20261007_123456_BEE00.687__FROM_70001.mp3",
            BasicRecordingContract.filename(configuredName, RecordFormat.MP3, WRITER_TIME, 1, 1));

        IdentifierCollection complete = new IdentifierCollection(List.of(radio,
            APCO25Wacn.create(0xBEE00), APCO25System.create(0x687)));
        assertEquals("p25:bee00:687", BasicRecordingContract.servingSystemKey(complete));
        assertEquals("20261007_123456__FROM_70001.mp3",
            BasicRecordingContract.filename(complete, RecordFormat.MP3, WRITER_TIME, 1, 1));
        assertNull(BasicRecordingContract.servingSystemKey(new IdentifierCollection(List.of(
            APCO25Wacn.create(0x100000), APCO25System.create(0x687)))));
        assertNull(BasicRecordingContract.servingSystemKey(new IdentifierCollection(List.of(
            APCO25Wacn.create(0xBEE00), APCO25System.create(-1)))));
    }

    @Test
    void winnerServingSystemRefusesConflictingEvidenceAndIgnoresPromotedIdentifiers()
    {
        IdentifierCollection observed = new IdentifierCollection(List.of(APCO25Wacn.create(0xABCDE),
            APCO25System.create(0x456)));
        CallLegSource nativeSource = new CallLegSource(DecoderType.P25_PHASE1, null, null, null, 0,
            new P25SiteIdentity(0x12345, 0x123, 1, 1), TrunkedIdentityDomain.STANDARD, null, false,
            "p25:bee00:687");
        assertNull(BasicRecordingContract.servingSystemKey(call(nativeSource, observed)));

        CallLegSource learnedSource = new CallLegSource(DecoderType.P25_PHASE1, null, null, null, 0,
            new P25SiteIdentity(0xBEE00, 0x687, 1, 1), TrunkedIdentityDomain.STANDARD, null, false);
        assertNull(BasicRecordingContract.servingSystemKey(call(learnedSource, observed)));
        IdentifierCollection matching = new IdentifierCollection(List.of(APCO25Wacn.create(0xBEE00),
            APCO25System.create(0x687)));
        assertEquals("p25:bee00:687", BasicRecordingContract.servingSystemKey(call(learnedSource, matching)));
        assertEquals("p25:abcde:456", BasicRecordingContract.servingSystemKey(call(CallLegSource.UNKNOWN, observed)));
        assertNull(BasicRecordingContract.servingSystemKey(call(CallLegSource.UNKNOWN, null)),
            "Promoted logical identifiers must not establish the winner's receiving system");
    }

    @Test
    void localIdProjectionRecursesThroughQualifiedPatchTalkgroupsAndRadios()
    {
        var primary = APCO25FullyQualifiedTalkgroupIdentifier.createTo(77, 0xBEE00, 0x687, 1201);
        var patch = new PatchGroup(primary);
        patch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(501,
            0xBEE00, 0x687, 70001));
        patch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(502,
            0xABCDE, 0x456, 70002));
        var identifier = APCO25PatchGroup.create(patch);
        assertEquals("P:77 [501, 502]",
            BasicRecordingContract.targetIdentifier(identifier));
        assertEquals("P77 [501, 502]",
            BasicRecordingContract.filenameIdentifier(identifier));
    }

    @Test
    void qualifiedFilenameUsesObservedWorkingScalarAndCollisionSuffix() throws Exception
    {
        IdentifierCollection identifiers = new IdentifierCollection(List.of(APCO25Talkgroup.create(48530),
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xBEE00, 0x687, 70001)));
        Path first = BasicRecordingContract.nextPath(mDirectory, identifiers, RecordFormat.MP3,
            WRITER_TIME, 1);
        assertEquals("20261007_123456__TO_48530_FROM_501.mp3", first.getFileName().toString());
        Files.writeString(first, "first recording");
        Path second = BasicRecordingContract.nextPath(mDirectory, identifiers, RecordFormat.MP3,
            WRITER_TIME, 1);
        assertEquals("20261007_123456__TO_48530_FROM_501_V2.mp3",
            second.getFileName().toString());
        for(Path path: List.of(first, second))
        {
            assertTrue(path.getFileName().toString().matches(
                "[0-9]{8}_[0-9]{6}__TO_[0-9]+_FROM_[0-9]+(?:_V[0-9]+)?\\.mp3"));
            assertFalse(path.getFileName().toString().contains("70001"));
            assertFalse(path.getFileName().toString().contains("Working_ID"));
        }
        assertEquals("first recording", Files.readString(first));
    }

    private static CompletedAudioCall call(CallLegSource source, IdentifierCollection observed)
    {
        AudioCallId id = new AudioCallId(1, 1, 0);
        IdentifierCollection resolved = new IdentifierCollection(List.of(APCO25Wacn.create(0xBEE00),
            APCO25System.create(0x687)));
        AudioCallSnapshot snapshot = new AudioCallSnapshot(id, null, null, resolved, Set.of(),
            1_000L, 2_000L, 1, 1, 1_000L, 2_000L, false, true, CallEncryptionState.CLEAR, true,
            null, null, CallLegId.from(id), source, null);
        CompletedAudioCall oneLeg = new CompletedAudioCall(snapshot, List.of());
        return new CompletedAudioCall(oneLeg.logicalCallId(), snapshot, List.of(), oneLeg.resolvedPolicy(),
            oneLeg.callLegSummaries(), new AudioCallSnapshot(id, null, null, observed, Set.of(),
                1_000L, 2_000L, 1, 1, 1_000L, 2_000L, false, true, CallEncryptionState.CLEAR, true,
                null, null, CallLegId.from(id), source, null), null);
    }

    @Test
    void formattingCannotRestoreACompetingIdentifierFromDecoderProvenance()
    {
        var source = APCO25FullyQualifiedRadioIdentifier.withRecordingIdentifier(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xBEE00, 0x687, 777),
            APCO25RadioIdentifier.createFrom(999));
        assertEquals("501", BasicRecordingContract.sourceIdentifier(source));
        assertEquals("501", BasicRecordingContract.filenameIdentifier(source));
    }

    @Test
    void assignmentEnrichmentCannotChangeObservedRadioFilenameOrIdentifierText()
    {
        //Mainline abbreviated voice messages supply ordinary radios. VCE's assignment cache adds facts only.
        for(int observed: List.of(85610, 501))
        {
            for(var role: List.of(Role.FROM, Role.TO))
            {
                var enriched = APCO25FullyQualifiedRadioIdentifier.createWithWorkingAddress(observed,
                    0xBEE00, 0x687, 85610, role, IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT);
                assertEquals(Integer.toString(observed), BasicRecordingContract.sourceIdentifier(enriched));
                assertEquals(Integer.toString(observed), BasicRecordingContract.targetIdentifier(enriched));
                assertEquals(Integer.toString(observed), BasicRecordingContract.filenameIdentifier(enriched));
                assertEquals(role, enriched.getRole());
                assertEquals(85610, enriched.getRadio(), "Canonical identity remains available to the receiver");
            }
            IdentifierCollection identifiers = new IdentifierCollection(List.of(APCO25Talkgroup.create(48530),
                APCO25FullyQualifiedRadioIdentifier.createWithWorkingAddress(observed, 0xBEE00, 0x687, 85610,
                    Role.FROM, IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT)));
            assertEquals("20261007_123456__TO_48530_FROM_" + observed + ".mp3",
                BasicRecordingContract.filename(identifiers, RecordFormat.MP3, WRITER_TIME, 1, 1));

        }
    }

    @Test
    void realAssignmentCacheEnrichmentKeepsMainlineAbbreviatedVoiceFilename()
    {
        var site = new P25SiteIdentity(0xBEE00, 0x687, 1, 1);
        for(int observed: List.of(85610, 501))
        {
            var registry = new P25IdentityResolver();
            registry.register(site, new P25RadioPresence(new P25SubscriberIdentity(0xBEE00, 0x687, 85610),
                observed), 1_000L, null);
            var identifiers = new MutableIdentifierCollection(List.of(APCO25Talkgroup.create(48530),
                APCO25RadioIdentifier.createFrom(observed)));
            registry.enrich(site, identifiers, 2_000L);

            assertTrue(identifiers.getFromIdentifier() instanceof APCO25FullyQualifiedRadioIdentifier,
                "The test must exercise an actual assignment-cache promotion");
            assertEquals("20261007_123456__TO_48530_FROM_" + observed + ".mp3",
                BasicRecordingContract.filename(identifiers, RecordFormat.MP3, WRITER_TIME, 1, 1));

            //A later identity-only observation cannot replace the accepted local working address.
            identifiers.update(APCO25FullyQualifiedRadioIdentifier.createFrom(85610, 0xBEE00, 0x687, 85610));
            assertEquals("20261007_123456__TO_48530_FROM_" + observed + ".mp3",
                BasicRecordingContract.filename(identifiers, RecordFormat.MP3, WRITER_TIME, 1, 1));
            assertEquals(Integer.toString(observed), BasicRecordingContract.sourceIdentifier(identifiers.getFromIdentifier()));
        }
    }

    @Test
    void qualifiedTalkgroupAndPatchMembersUseTheSameLocalIdProjection()
    {
        var primary = APCO25FullyQualifiedTalkgroupIdentifier.createTo(77, 0xBEE00, 0x687, 1201);
        var patch = new PatchGroup(primary);
        patch.addPatchedTalkgroup(APCO25Talkgroup.create(1202));
        patch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xBEE00, 0x687, 70001));
        var identifier = APCO25PatchGroup.create(patch);

        assertEquals("77", BasicRecordingContract.targetIdentifier(primary));
        assertEquals("P:77 [1202] [501]",
            BasicRecordingContract.targetIdentifier(identifier));
        assertEquals("P77 [1202] [501]",
            BasicRecordingContract.filenameIdentifier(identifier));
    }

    @Test
    void qualifiedTalkgroupAndPatchMembersRetainZeroLocalIds()
    {
        var primary = APCO25FullyQualifiedTalkgroupIdentifier.createTo(0, 0xBEE00, 0x687, 1201);
        var patch = new PatchGroup(primary);
        patch.addPatchedTalkgroup(APCO25FullyQualifiedTalkgroupIdentifier.createTo(0, 0xABCDE, 0x456, 1202));
        patch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createTo(0, 0xBEE00, 0x687, 70001));
        var identifier = APCO25PatchGroup.create(patch);

        assertEquals("0", BasicRecordingContract.targetIdentifier(primary));
        assertEquals("P:0 [0] [0]", BasicRecordingContract.targetIdentifier(identifier));
        assertEquals("P0 [0] [0]", BasicRecordingContract.filenameIdentifier(identifier));
    }

    @Test
    void basicFilenameTimestampFieldsAndDuplicateSuffixesStayFrozen() throws Exception
    {
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            SystemConfigurationIdentifier.create("SyntheticSystem"), SiteConfigurationIdentifier.create("Site1"),
            ChannelNameConfigurationIdentifier.create("Channel1"), APCO25Talkgroup.create(1201),
            APCO25RadioIdentifier.createFrom(70001)));
        assertEquals("20261007_123456_SyntheticSystem_Site1_Channel1__TO_1201_FROM_70001.mp3",
            BasicRecordingContract.filename(identifiers, RecordFormat.MP3, WRITER_TIME, 1, 1));
        Path first = BasicRecordingContract.nextPath(mDirectory, identifiers, RecordFormat.MP3, WRITER_TIME, 1);
        Files.writeString(first, "existing first recording");
        Path second = BasicRecordingContract.nextPath(mDirectory, identifiers, RecordFormat.MP3, WRITER_TIME, 1);
        assertEquals("20261007_123456_SyntheticSystem_Site1_Channel1__TO_1201_FROM_70001_V2.mp3", second.getFileName().toString());
        Files.writeString(second, "existing second recording");
        assertEquals("20261007_123456_SyntheticSystem_Site1_Channel1__TO_1201_FROM_70001_V3.mp3",
            BasicRecordingContract.nextPath(mDirectory, identifiers, RecordFormat.MP3, WRITER_TIME, 1).getFileName().toString());
        assertEquals("existing first recording", Files.readString(first));
        assertEquals("existing second recording", Files.readString(second));
    }

    @Test
    void filenameSelectionRetainsTalkgroupBeforePatchAndRadioBeforeTone()
    {
        var tone = P25ToneIdentifier.create(new ToneSequence(List.of(new Tone(AmbeTone.DTMF_1))));
        IdentifierCollection identifiers = new IdentifierCollection(List.of(APCO25PatchGroup.create(1203),
            APCO25RadioIdentifier.createTo(70002), tone, APCO25Talkgroup.create(1201),
            APCO25RadioIdentifier.createFrom(70001)));
        assertEquals("20261007_123456__TO_1201_FROM_70001_TONES_DTMF_1.wav",
            BasicRecordingContract.filename(identifiers, RecordFormat.WAVE, WRITER_TIME, 1, 1));
        assertEquals("20261007_123456__TO_P1203.wav", BasicRecordingContract.filename(
            new IdentifierCollection(List.of(APCO25PatchGroup.create(1203))), RecordFormat.WAVE, WRITER_TIME, 1, 1));
    }

    @Test
    void unknownSanitizedAndLongFilenamesRemainImportable()
    {
        assertEquals("20261007_123456_audio_recording_no_metadata_9.wav",
            BasicRecordingContract.filename(null, RecordFormat.WAVE, WRITER_TIME, 9, 1));
        assertEquals("20261007_123456_Sys-Name__TO_1201.mp3", BasicRecordingContract.filename(
            new IdentifierCollection(List.of(SystemConfigurationIdentifier.create("Sys/Name"), APCO25Talkgroup.create(1201))),
            RecordFormat.MP3, WRITER_TIME, 1, 1));
        IdentifierCollection longName = new IdentifierCollection(List.of(SystemConfigurationIdentifier.create("S".repeat(300))));
        String first = BasicRecordingContract.filename(longName, RecordFormat.MP3, WRITER_TIME, 1, 1);
        String tenth = BasicRecordingContract.filename(longName, RecordFormat.MP3, WRITER_TIME, 1, 10);
        assertEquals(252, first.length());
        assertEquals(255, tenth.length());
        assertTrue(tenth.endsWith("_V10.mp3"));
        assertFalse(first.contains("Working_ID"));
    }

    @Test
    void composerUsesUpstreamNameAndManifestVersionRules()
    {
        assertEquals("sdrtrunk", BasicRecordingContract.composer(null, null));
        assertEquals("sdrtrunk v", BasicRecordingContract.composer("", null));
        assertEquals("sdrtrunk v0.6.1", BasicRecordingContract.composer("0.6.1", null));
        assertEquals("sdrtrunk vnightly", BasicRecordingContract.composer("nightly", null));
        assertEquals("sdrtrunk nightly - 2026-10-07T12:34:56.000+0000",
            BasicRecordingContract.composer("nightly", "2026-10-07T12:34:56.000+0000"));
    }
}
