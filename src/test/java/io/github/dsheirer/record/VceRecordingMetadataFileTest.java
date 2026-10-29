/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mpatric.mp3agic.ID3v24Tag;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.p25.P25IdentityResolver;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.GroupAffiliationResponseExtended;
import io.github.dsheirer.module.decode.p25.reference.Response;
import io.github.dsheirer.module.decode.p25.P25RadioPresence;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.identifier.DmrTier3Radio;
import io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallFixture;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier;
import io.github.dsheirer.protocol.Protocol;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CallLegSummary;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.audio.tone.ToneSummaryPayload;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.DecoderTypeConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Nac;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.record.RecordingMode;
import io.github.dsheirer.record.wave.AudioMetadata;
import io.github.dsheirer.record.wave.AudioMetadataUtils;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the independently parsed bytes of completed MP3/WAV recordings, including compatibility fields. */
class VceRecordingMetadataFileTest
{
    private static final VoiceCallQuality QUALITY = new VoiceCallQuality(480, 8, 4, 8, 144, 72_000);
    private static final CallLegSource SOURCE = source(DecoderType.P25_PHASE2, 0xBEE00, 0x456, 1, 7);
    @TempDir Path mDirectory;

    @Test
    void decodedNxdnZeroSurvivesActualFilesAndCatalogWithNumericBasicId() throws Exception
    {
        AliasList aliases = new AliasList(new io.github.dsheirer.alias.AliasListDefinition("NXDN",
            io.github.dsheirer.alias.AliasListFamily.NXDN));
        Alias alias = new Alias("Dispatch");
        alias.setMatchIdentifier(new Talkgroup(Protocol.NXDN, 0));
        alias.setRecordable(true);
        aliases.addAlias(alias);
        CompletedAudioCall call = NXDNConventionalCallFixture.decodeGroupCall(aliases, 19, 0).audio();
        assertEquals("0", call.snapshot().recordingMetadata().destinationValue());
        assertEquals("Dispatch", call.snapshot().recordingMetadata().destinationAlias());
        assertTrue(call.snapshot().recordAudio());

        for(RecordFormat format: RecordFormat.values())
        {
            String name = "nxdn-zero-" + format;
            Recording recording = writeAndRead(call, name, format, null);
            assertExistingFieldsUnchanged(call, call.snapshot().identifierCollection(), recording);
            ID3v24Tag standard = new ID3v24Tag(recording.id3());
            assertEquals("0\"Dispatch\"", standard.getTitle(),
                "The ID is numeric while the existing quoted alias grammar remains intact");
            assertEquals("19", standard.getArtist());
            assertEquals("0", recording.vceFields().get("VCE_TALKGROUP"));
            assertEquals("NXDN", recording.vceFields().get("VCE_PROTOCOL"));
            assertFalse(recording.vceFields().keySet().stream().anyMatch(key ->
                key.contains("OTA") || key.contains("TALKER_ALIAS")), "OTA metadata remains unchanged");

            Path saved = mDirectory.resolve(name + format.getExtension());
            Path database = mDirectory.resolve(name + ".sqlite");
            try(ManagedRecordingCatalog catalog = new ManagedRecordingCatalog(database, mDirectory))
            {
                CountDownLatch indexed = new CountDownLatch(1);
                assertTrue(catalog.submit(saved, call, Files.size(saved), indexed::countDown));
                assertTrue(indexed.await(5, TimeUnit.SECONDS));
            }
            try(ManagedRecordingCatalog catalog = new ManagedRecordingCatalog(database, mDirectory))
            {
                var rows = catalog.search(ManagedRecordingCatalog.SearchFilter.builder()
                    .fromMs(0L).toMs(5_000L).protocol("NXDN").talkgroupId(0).build()).calls();
                assertEquals(1, rows.size());
                assertEquals(0, rows.getFirst().toMap().get("target_id"));
                assertEquals(19, rows.getFirst().toMap().get("source_id"));
            }
        }
    }

    @Test
    void numericIdsSurviveRealFilesInBothModesWithFrozenAliasesAndUnchangedAdditionalFacts() throws Exception
    {
        DMRTalkgroup compressed = new DMRTalkgroup(146410);
        compressed.setCompressed(true);
        List<NumericCase> cases = List.of(
            new NumericCase("zero", NXDNRadioIdentifier.createFrom(19), NXDNTalkgroupIdentifier.createTo(0),
                19, 0, TrunkedIdentityDomain.NXDN_TYPE_C),
            new NumericCase("reserved", NXDNRadioIdentifier.createFrom(0xFFF0),
                NXDNTalkgroupIdentifier.createTo(0xFFF0), 65520, 65520, TrunkedIdentityDomain.NXDN_TYPE_C),
            new NumericCase("all", NXDNRadioIdentifier.createFrom(0xFFFF),
                NXDNTalkgroupIdentifier.createTo(0xFFFF), 65535, 65535, TrunkedIdentityDomain.NXDN_TYPE_C),
            new NumericCase("type-d", NXDNRadioIdentifier.createTypeDFrom((2 << 11) | 2),
                NXDNTalkgroupIdentifier.createTypeDTo((1 << 11) | 1), 4098, 2049,
                TrunkedIdentityDomain.NXDN_TYPE_D),
            new NumericCase("type-d-other-home", NXDNRadioIdentifier.createTypeDFrom((2 << 11) | 2),
                NXDNTalkgroupIdentifier.createTypeDTo((2 << 11) | 1), 4098, 4097,
                TrunkedIdentityDomain.NXDN_TYPE_D),
            new NumericCase("type-d-radio-destination", NXDNRadioIdentifier.createTypeDFrom((2 << 11) | 2),
                NXDNRadioIdentifier.createTypeDTo((1 << 11) | 1), 4098, 2049,
                TrunkedIdentityDomain.NXDN_TYPE_D),
            new NumericCase("dmr-compressed-gateway", DmrTier3Radio.createFrom(0xFFFEC0), compressed,
                16776896, 100000, TrunkedIdentityDomain.STANDARD));
        UserPreferences preferences = new UserPreferences();
        RecordingMode previous = preferences.getRecordPreference().getRecordingMode();
        try(ManagedRecordingCatalog catalog = new ManagedRecordingCatalog(mDirectory.resolve("numeric.sqlite"),
            mDirectory))
        {
            int sequence = 100;
            for(RecordingMode mode: RecordingMode.values())
            {
                preferences.getRecordPreference().setRecordingMode(mode);
                for(NumericCase item: cases)
                {
                    Protocol protocol = item.source().getProtocol();
                    AliasList aliases = new AliasList(new io.github.dsheirer.alias.AliasListDefinition(
                        "NUMERIC", protocol == Protocol.NXDN ? io.github.dsheirer.alias.AliasListFamily.NXDN :
                            io.github.dsheirer.alias.AliasListFamily.DMR));
                    Alias sourceAlias = new Alias("LAB SOURCE 0xABCD");
                    sourceAlias.setMatchIdentifier(new Radio(protocol, item.expectedSource()));
                    Alias targetAlias = new Alias("LAB TARGET 0xBEEF");
                    targetAlias.setMatchIdentifier(item.destination().getForm() == Form.RADIO ?
                        new Radio(protocol, item.expectedTarget()) : new Talkgroup(protocol, item.expectedTarget()));
                    aliases.addAlias(sourceAlias);
                    aliases.addAlias(targetAlias);
                    IdentifierCollection identifiers = identifiers(SystemConfigurationIdentifier.create("LABSYSTEM"),
                        SiteConfigurationIdentifier.create("001-001"),
                        ChannelNameConfigurationIdentifier.create("LABCHANNEL"),
                        DecoderTypeConfigurationIdentifier.create(protocol == Protocol.NXDN ? DecoderType.NXDN : DecoderType.DMR),
                        FrequencyConfigurationIdentifier.create(461125000L), item.source(), item.destination());
                    if(protocol == Protocol.NXDN)
                    {
                        List<Identifier> captured = new ArrayList<>(identifiers.getIdentifiers());
                        captured.add(new NXDNTalkerAliasIdentifier("OTA NAME KEPT OUT OF FILE METADATA")
                            .bindTo(identifiers));
                        identifiers = new IdentifierCollection(captured);
                    }
                    CompletedAudioCall call = numericCall(identifiers, aliases, item.domain(), sequence++);
                    sourceAlias.setName("EDITED AFTER SNAPSHOT");
                    targetAlias.setName("EDITED AFTER SNAPSHOT");
                    String expectedArtist = item.expectedSource() + " LAB SOURCE 0xABCD";
                    String expectedTitle = item.expectedTarget() + "\"LAB TARGET 0xBEEF\"";
                    Map<String,String> expectedExtra = new LinkedHashMap<>();
                    if(item.destination().getForm() == Form.TALKGROUP)
                        expectedExtra.put("VCE_TALKGROUP", Integer.toString(item.expectedTarget()));
                    expectedExtra.put("VCE_FREQUENCY_HZ", "461125000");
                    expectedExtra.put("VCE_PROTOCOL", protocol.name());
                    for(RecordFormat format: RecordFormat.values())
                    {
                        Path folder = mDirectory.resolve(mode + "-" + item.name());
                        Files.createDirectories(folder);
                        Path file = BasicRecordingContract.nextPath(folder, identifiers, format, 1000L, 1);
                        assertTrue(file.getFileName().toString().matches("\\d{8}_\\d{6}_LABSYSTEM_001-001_LABCHANNEL__TO_" +
                            item.expectedTarget() + "_FROM_" + item.expectedSource() + "\\" + format.getExtension()));
                        AudioCallRecorder.write(call, file, format, preferences);
                        byte[] bytes = Files.readAllBytes(file);
                        Recording recording = readRecording(bytes, format);
                        ID3v24Tag tag = new ID3v24Tag(recording.id3());
                        assertEquals(expectedTitle, tag.getTitle(), item.name());
                        assertEquals(expectedArtist, tag.getArtist(), item.name());
                        assertEquals("LABSYSTEM", tag.getGrouping());
                        assertEquals("LABCHANNEL", tag.getAlbum());
                        assertEquals(BasicRecordingContract.composer(), tag.getComposer());
                        assertEquals(expectedExtra, recording.vceFields(), "Existing VCE facts have unchanged meanings");
                        assertEquals(Set.of("TCOM", "TCON", "TIT1", "TALB", "TIT2", "TPE1", "TDAT", "TYER", "COMM"),
                            recording.frames().stream().filter(frame -> !frame.name().equals("TXXX"))
                                .map(Frame::name).collect(java.util.stream.Collectors.toSet()),
                            "Numeric IDs add no basic ID3 fields");
                        if(format == RecordFormat.WAVE)
                        {
                            Map<String,String> info = waveInfoFields(recording.waveChunks().get("LIST"));
                            assertEquals(expectedTitle, info.get("INAM"));
                            assertEquals(expectedArtist, info.get("IART"));
                        }
                        CountDownLatch indexed = new CountDownLatch(1);
                        assertTrue(catalog.submit(file, call, bytes.length, indexed::countDown));
                        assertTrue(indexed.await(5, TimeUnit.SECONDS));
                        String relative = mDirectory.relativize(file).toString().replace('\\', '/');
                        var stored = catalog.search(ManagedRecordingCatalog.SearchFilter.builder()
                            .fromMs(0L).toMs(5000L).build()).calls().stream()
                            .filter(row -> row.relativePath().equals(relative)).findFirst().orElseThrow();
                        assertEquals(item.expectedSource(), stored.sourceId());
                        assertEquals(item.expectedTarget(), stored.targetId());
                        assertArrayEquals(bytes, Files.readAllBytes(catalog.audioPath(stored.id())),
                            "The file supplied to listening/download keeps the complete recording bytes");
                    }
                }
            }
        }
        finally
        {
            preferences.getRecordPreference().setRecordingMode(previous);
        }
    }

    private record NumericCase(String name, Identifier<?> source, Identifier<?> destination,
                               int expectedSource, int expectedTarget, TrunkedIdentityDomain domain) {}

    private static CompletedAudioCall numericCall(IdentifierCollection identifiers, AliasList aliases,
                                                 TrunkedIdentityDomain domain, int sequence)
    {
        Protocol protocol = identifiers.getFromIdentifier().getProtocol();
        CallLegSource source = new CallLegSource(protocol == Protocol.NXDN ? DecoderType.NXDN : DecoderType.DMR,
            domain == TrunkedIdentityDomain.NXDN_TYPE_D ? "00000000-0000-0000-0000-000000000099" :
                "00000000-0000-0000-0000-000000000098", "LABCHANNEL", null, 0, null, domain, null, false);
        AudioCallId id = new AudioCallId(sequence, 1, 0);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(id, null, aliases, identifiers, Set.of(),
            1000L, 2000L, 1, 1, 1000L, 2000L, false, true, CallEncryptionState.UNKNOWN, true, null,
            VoiceCallQuality.EMPTY, CallLegId.from(id), source, null);
        return new CompletedAudioCall(snapshot, call(identifiers, source, VoiceCallQuality.EMPTY,
            CallEncryptionState.UNKNOWN, 0).audioBuffers());
    }

    @Test
    void sharedNativeToneEvidenceSurvivesActualFilesAndSavedCatalogWithoutChangingAudioOrLegacyTags() throws Exception
    {
        for(boolean encoded: List.of(false, true))
        {
            CompletedAudioCall nativeCall = call(groupIdentifiers(), encoded ? SOURCE :
                source(DecoderType.P25_PHASE1, 0xBEE00, 0x456, 1, 7), QUALITY, CallEncryptionState.CLEAR, 1);
            var sequence = new CompletedToneSequence(nativeCall.nativeAudioSnapshot(), encoded ? 0 : 1_000,
                encoded ? CompletedToneSequence.Source.ENCODED : CompletedToneSequence.Source.DECODED_AUDIO,
                encoded ? CompletedToneSequence.Method.AMBE_TONE_V1 : CompletedToneSequence.Method.P25_PCM_TONE_V1,
                encoded ? List.of(new CompletedToneSequence.Element(List.of(697_000,1_209_000), "DTMF_1", 0L, 200, null),
                    new CompletedToneSequence.Element(List.of(), "INVALID", 200L, 60, null)) :
                    List.of(new CompletedToneSequence.Element(List.of(1_000_125), null, 0L, 250, .9123456)));
            CompletedAudioCall tagged = nativeCall.withCompletedTones(sequence);
            for(RecordFormat format: RecordFormat.values())
            {
                String name = "tones-" + encoded + "-" + format;
                Recording original = writeAndRead(nativeCall, name + "-plain", format, null);
                Recording recording = writeAndRead(tagged, name, format, null);
                assertExistingFieldsUnchanged(nativeCall, nativeCall.snapshot().identifierCollection(), recording);
                assertEquals("1", recording.vceFields().get("VCE_PAGING_TONE_SCHEMA"));
                assertEquals(encoded ? "encoded" : "decoded_audio", recording.vceFields().get("VCE_PAGING_TONE_SOURCE"));
                assertEquals(encoded ? "2" : "1", recording.vceFields().get("VCE_PAGING_TONE_COUNT"));
                assertEquals(encoded ? "697" : "1000.125", recording.vceFields().get("VCE_PAGING_TONE_1_FREQUENCY_1_HZ"));
                assertEquals(encoded ? "200" : "250", recording.vceFields().get("VCE_PAGING_TONE_1_DURATION_MS"));
                if(encoded)
                {
                    assertEquals("1209", recording.vceFields().get("VCE_PAGING_TONE_1_FREQUENCY_2_HZ"));
                    assertEquals("INVALID", recording.vceFields().get("VCE_PAGING_TONE_2_CODE"));
                    assertFalse(recording.vceFields().containsKey("VCE_PAGING_TONE_FIRST_SAMPLE_MS"));
                    assertFalse(recording.vceFields().containsKey("VCE_PAGING_TONE_1_CONFIDENCE_PPM"));
                }
                else assertEquals("912346", recording.vceFields().get("VCE_PAGING_TONE_1_CONFIDENCE_PPM"));
                if(format == RecordFormat.WAVE)
                    assertArrayEquals(original.waveChunks().get("data"), recording.waveChunks().get("data"));
                Path saved = mDirectory.resolve(name + format.getExtension());
                Path database = mDirectory.resolve(name + ".sqlite");
                try(ManagedRecordingCatalog catalog = new ManagedRecordingCatalog(database,mDirectory))
                {
                    CountDownLatch indexed = new CountDownLatch(1);
                    assertTrue(catalog.submit(saved, tagged, Files.size(saved), indexed::countDown));
                    assertTrue(indexed.await(5, TimeUnit.SECONDS));
                    var stored = catalog.search(ManagedRecordingCatalog.SearchFilter.builder().fromMs(0L).toMs(5_000L).build()).calls().getFirst();
                    assertEquals(ToneSummaryPayload.summary(sequence), stored.toMap().get("paging_tone_summary"));
                    assertFalse(stored.toMap().containsKey("tone"), "Paging sequences are not DPL access codes");
                }
                try(ManagedRecordingCatalog reopened = new ManagedRecordingCatalog(database,mDirectory))
                {
                    assertEquals(ToneSummaryPayload.summary(sequence), reopened.search(
                        ManagedRecordingCatalog.SearchFilter.builder().fromMs(0L).toMs(5_000L).build()).calls().getFirst().toMap().get("paging_tone_summary"));
                }
            }
        }
    }

    @Test
    void nominalPcmFrequencyIsTheOnlyFrequencyWrittenWithoutChangingAudio() throws Exception
    {
        CompletedAudioCall plain = call(groupIdentifiers(),
            source(DecoderType.P25_PHASE1, 0xBEE00, 0x456, 1, 7), QUALITY, CallEncryptionState.CLEAR, 1);
        var detected = new io.github.dsheirer.audio.tone.P25PagingToneDetector.DetectedTone(
            566.379, 514, 900, .99, "decoded_audio", "p25_pcm_ambe_tone_v2");
        assertEquals(562.5, detected.frequencyHz());
        var sequence = new CompletedToneSequence(plain.nativeAudioSnapshot(), 1_000,
            CompletedToneSequence.Source.DECODED_AUDIO, CompletedToneSequence.Method.P25_PCM_AMBE_TONE_V2,
            List.of(new CompletedToneSequence.Element(List.of((int)Math.round(detected.frequencyHz() * 1000)),
                null, detected.offsetMilliseconds(), detected.durationMilliseconds(), detected.confidence())));
        for(RecordFormat format: RecordFormat.values())
        {
            Recording before = writeAndRead(plain, "nominal-plain", format, null);
            Recording after = writeAndRead(plain.withCompletedTones(sequence), "nominal", format, null);
            assertEquals("562.5", after.vceFields().get("VCE_PAGING_TONE_1_FREQUENCY_1_HZ"));
            assertEquals("p25_pcm_ambe_tone_v2", after.vceFields().get("VCE_PAGING_TONE_METHOD"));
            assertEquals("514", after.vceFields().get("VCE_PAGING_TONE_1_OFFSET_MS"));
            assertEquals("900", after.vceFields().get("VCE_PAGING_TONE_1_DURATION_MS"));
            assertFalse(after.vceFields().keySet().stream().anyMatch(key -> key.contains("MATCHING") || key.contains("MEASURED")));
            assertFalse(after.vceFields().containsKey("VCE_PAGING_TONE_1_FREQUENCY_2_HZ"));
            if(format == RecordFormat.WAVE)
                assertArrayEquals(before.waveChunks().get("data"), after.waveChunks().get("data"));
            else
                assertArrayEquals(Arrays.copyOfRange(before.id3(), 10 + synchsafe(before.id3(), 6), before.id3().length),
                    Arrays.copyOfRange(after.id3(), 10 + synchsafe(after.id3(), 6), after.id3().length));
        }
    }

    @Test
    void everyAvailableFieldSurvivesInActualMp3AndWaveWithoutChangingStandardFields() throws Exception
    {
        CompletedAudioCall call = call(groupIdentifiers(), SOURCE, QUALITY, CallEncryptionState.CLEAR, 1);
        Map<String, String> expected = fullGroupFields();

        for(RecordFormat format: RecordFormat.values())
        {
            Recording recording = writeAndRead(call, "complete", format, null);
            assertEquals(expected, recording.vceFields());
            assertExistingFieldsUnchanged(call, call.snapshot().identifierCollection(), recording);
            ID3v24Tag standard = new ID3v24Tag(recording.id3());
            assertEquals("120045", standard.getArtist());
            assertEquals("3101", standard.getTitle());
            assertEquals("Synthetic System", standard.getGrouping());
            assertEquals("Synthetic Voice", standard.getAlbum());
            assertTrue(standard.getComment().endsWith("System:Synthetic System;Site:Synthetic Site;" +
                "Name:Synthetic Voice;Decoder:P25 Phase 2;Frequency:851012500;"));
        }
    }

    @Test
    void radioDestinationAndMeasuredZerosRemainDistinctFromGroupAndUnknownValues() throws Exception
    {
        IdentifierCollection identifiers = identifiers(APCO25RadioIdentifier.createFrom(120045),
            APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(220078, 0xBCDEF, 0x321, 987654));
        CompletedAudioCall call = call(identifiers, CallLegSource.UNKNOWN,
            new VoiceCallQuality(50, 0, 0, 0, 0, 6_850), CallEncryptionState.ENCRYPTED, 0);
        Map<String, String> expected = Map.ofEntries(
            Map.entry("VCE_P25_WUID", "120045"),
            Map.entry("VCE_P25_DEST_WUID", "220078"),
            Map.entry("VCE_P25_DEST_SUID", "BCDEF.321.987654"),
            Map.entry("VCE_PROTOCOL", "APCO25"),
            Map.entry("VCE_ENCRYPTION", "ENCRYPTED"),
            Map.entry("VCE_VOICE_DECODED_FRAMES", "50"),
            Map.entry("VCE_VOICE_REPEATED_FRAMES", "0"),
            Map.entry("VCE_VOICE_CONCEALED_FRAMES", "0"),
            Map.entry("VCE_VOICE_MISSING_FRAMES", "0"),
            Map.entry("VCE_VOICE_FEC_ERRORS", "0"),
            Map.entry("VCE_VOICE_FEC_PROTECTED_BITS", "6850"),
            Map.entry("VCE_VOICE_FEC_CORRECTION_PERCENT", "0.000000"));

        for(RecordFormat format: RecordFormat.values())
        {
            Recording recording = writeAndRead(call, "private", format, null);
            assertEquals(expected, recording.vceFields());
            assertExistingFieldsUnchanged(call, identifiers, recording);
        }
    }

    @Test
    void patchDestinationIsNotExportedAsAnOrdinaryTalkgroupOrRadio() throws Exception
    {
        IdentifierCollection identifiers = identifiers(APCO25RadioIdentifier.createFrom(501),
            APCO25PatchGroup.create(45001));
        CompletedAudioCall call = call(identifiers, CallLegSource.UNKNOWN, VoiceCallQuality.EMPTY,
            CallEncryptionState.UNKNOWN, 0);
        Map<String, String> expected = Map.of("VCE_P25_WUID", "501", "VCE_PATCH_GROUP", "45001",
            "VCE_PROTOCOL", "APCO25");
        for(RecordFormat format: RecordFormat.values())
        {
            Recording recording = writeAndRead(call, "patch", format, null);
            assertEquals(expected, recording.vceFields());
            assertExistingFieldsUnchanged(call, identifiers, recording);
            assertEquals("P:45001", new ID3v24Tag(recording.id3()).getTitle());
        }
    }

    @Test
    void identityOnlyObservationDoesNotInventAWorkingAddressAndUnknownFactsAreOmitted() throws Exception
    {
        IdentifierCollection identifiers = identifiers(
            APCO25FullyQualifiedRadioIdentifier.createFrom(7654321, 0xABCDE, 0x123, 7654321));
        CompletedAudioCall call = call(identifiers, CallLegSource.UNKNOWN, VoiceCallQuality.EMPTY,
            CallEncryptionState.UNKNOWN, 0);
        for(RecordFormat format: RecordFormat.values())
        {
            Recording recording = writeAndRead(call, "identity-only", format, null);
            assertEquals(Map.of("VCE_P25_SUID", "ABCDE.123.7654321", "VCE_PROTOCOL", "APCO25"),
                recording.vceFields());
            assertExistingFieldsUnchanged(call, identifiers, recording);
        }

        CompletedAudioCall unknown = call(new IdentifierCollection(), CallLegSource.UNKNOWN,
            VoiceCallQuality.EMPTY, CallEncryptionState.UNKNOWN, 0);
        for(RecordFormat format: RecordFormat.values())
        {
            Recording recording = writeAndRead(unknown, "unknown", format, null);
            assertTrue(recording.vceFields().isEmpty(), "No facts must produce no placeholder TXXX frames");
            assertExistingFieldsUnchanged(unknown, unknown.snapshot().identifierCollection(), recording);
        }
    }

    @Test
    void recordingUsesResolvedAttributionAndSelectedReceiverQuality() throws Exception
    {
        IdentifierCollection observed = identifiers(APCO25RadioIdentifier.createFrom(120045),
            APCO25Talkgroup.create(3101), APCO25Nac.create(0x3A7));
        IdentifierCollection promoted = groupIdentifiers();
        CompletedAudioCall original = call(promoted, SOURCE,
            new VoiceCallQuality(999, 99, 99, 0, 999, 999), CallEncryptionState.CLEAR, 1);
        CallLegSummary winner = new CallLegSummary(original.snapshot().callLegId(), SOURCE, 1_000, 2_000,
            QUALITY, 8_000, false, false, true, null, observed.getFromIdentifier(), observed.getToIdentifier(),
            List.of(), 851012500L, 1);
        CallLegSummary loser = new CallLegSummary(new CallLegId(2, 1, 2),
            source(DecoderType.P25_PHASE1, 0x11111, 0x222, 2, 9), 1_000, 2_000,
            new VoiceCallQuality(900, 90, 90, 90, 900, 900), 0, false, false, false, null,
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(333, 0x11111, 0x222, 444),
            APCO25Talkgroup.create(555), List.of(), 852000000L, 2);
        CompletedAudioCall call = new CompletedAudioCall(original.logicalCallId(), original.snapshot(),
            original.audioBuffers(), original.resolvedPolicy(), List.of(winner, loser));
        Map<String, String> expected = fullGroupFields();
        for(RecordFormat format: RecordFormat.values())
        {
            Recording recording = writeAndRead(call, "winner", format, null);
            assertEquals(expected, recording.vceFields());
            assertExistingFieldsUnchanged(call, promoted, recording);
            assertEquals("120045", new ID3v24Tag(recording.id3()).getArtist());
        }
    }

    @Test
    void explicitStreamingTagIdentifiersDoNotReplaceCapturedVceFacts() throws Exception
    {
        CompletedAudioCall call = call(groupIdentifiers(), SOURCE, QUALITY, CallEncryptionState.CLEAR, 1);
        IdentifierCollection remapped = identifiers(APCO25RadioIdentifier.createFrom(99),
            APCO25Talkgroup.create(88), FrequencyConfigurationIdentifier.create(852000000L),
            DecoderTypeConfigurationIdentifier.create(DecoderType.P25_PHASE1));
        for(RecordFormat format: RecordFormat.values())
        {
            Recording recording = writeAndRead(call, "streaming", format, remapped);
            assertEquals(fullGroupFields(), recording.vceFields());
            assertExistingFieldsUnchanged(call, remapped, recording);
            assertEquals("99", new ID3v24Tag(recording.id3()).getArtist());
            assertEquals("88", new ID3v24Tag(recording.id3()).getTitle());
        }
    }

    @Test
    void homeSystemAndStandaloneWaveUseLocalMetadataWithFullAdditionalIdentity() throws Exception
    {
        IdentifierCollection identifiers = identifiers(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(120045, 0xBEE00, 0x456, 120045),
            APCO25Talkgroup.create(3101));
        CompletedAudioCall call = call(identifiers, SOURCE, new VoiceCallQuality(50, 0, 0, 0, 0, 0),
            CallEncryptionState.CLEAR, 1);
        for(RecordFormat format: RecordFormat.values())
        {
            Recording recording = writeAndRead(call, "home", format, null);
            assertEquals("120045", new ID3v24Tag(recording.id3()).getArtist());
            assertEquals("120045", recording.vceFields().get("VCE_P25_WUID"));
            assertEquals("BEE00.456.120045", recording.vceFields().get("VCE_P25_SUID"));
            assertFalse(recording.vceFields().containsKey("VCE_VOICE_FEC_ERRORS"));
            assertFalse(recording.vceFields().containsKey("VCE_VOICE_FEC_CORRECTION_PERCENT"));
            assertExistingFieldsUnchanged(call, identifiers, recording);
        }
        Path standalone = mDirectory.resolve("standalone.wav");
        AudioCallRecorder.recordWAVE(call, standalone, identifiers);
        Recording recording = readRecording(Files.readAllBytes(standalone), RecordFormat.WAVE);
        assertEquals("BEE00.456.120045", recording.vceFields().get("VCE_P25_SUID"));
        assertExistingFieldsUnchanged(call, identifiers, recording);
        assertTrue(BasicRecordingContract.filename(identifiers, RecordFormat.MP3, 1_000, 1, 1)
            .endsWith("_TO_3101_FROM_120045.mp3"));
    }

    @Test
    void zeroHomeForeignUnknownAndConflictedCallsUseLocalTagsAndRetainAdditionalIdentities() throws Exception
    {
        List<Integer> locals = List.of(0, 7654321, 120045, 120045, 120045);
        CallLegSource home = source(DecoderType.P25_PHASE2, 0xABCDE, 0x123, 1, 7);
        List<CallLegSource> receivers = List.of(home, home, SOURCE, CallLegSource.UNKNOWN, SOURCE);
        UserPreferences preferences = new UserPreferences();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();

        try
        {
            for(RecordingMode mode: RecordingMode.values())
            {
                preferences.getRecordPreference().setRecordingMode(mode);
                for(int index = 0; index < locals.size(); index++)
                {
                    int local = locals.get(index);
                    IdentifierCollection identifiers = identifiers(
                        APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(local,
                            0xABCDE, 0x123, 7654321),
                        APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(local,
                            0xBCDEF, 0x321, 987654));
                    if(index == 4)
                    {
                        identifiers = identifiers(identifiers.getFromIdentifier(), identifiers.getToIdentifier(),
                            APCO25Wacn.create(0xABCDE), APCO25System.create(0x123));
                    }
                    CompletedAudioCall call = call(identifiers, receivers.get(index), VoiceCallQuality.EMPTY,
                        CallEncryptionState.UNKNOWN, 0);

                    for(RecordFormat format: RecordFormat.values())
                    {
                        Path path = mDirectory.resolve("local-" + mode + "-" + index + format.getExtension());
                        AudioCallRecorder.write(call, path, format, preferences);
                        Recording recording = readRecording(Files.readAllBytes(path), format);
                        ID3v24Tag tag = new ID3v24Tag(recording.id3());
                        assertEquals(Integer.toString(local), tag.getArtist());
                        assertEquals(Integer.toString(local), tag.getTitle());
                        assertEquals("ABCDE.123.7654321", recording.vceFields().get("VCE_P25_SUID"));
                        assertEquals("BCDEF.321.987654", recording.vceFields().get("VCE_P25_DEST_SUID"));
                        if(local == 0)
                        {
                            assertFalse(recording.vceFields().containsKey("VCE_P25_WUID"));
                            assertFalse(recording.vceFields().containsKey("VCE_P25_DEST_WUID"));
                        }
                        else
                        {
                            assertEquals(Integer.toString(local), recording.vceFields().get("VCE_P25_WUID"));
                            assertEquals(Integer.toString(local), recording.vceFields().get("VCE_P25_DEST_WUID"));
                        }
                        if(index >= 3)
                        {
                            assertFalse(recording.vceFields().containsKey("VCE_P25_RX_WACN"));
                            assertFalse(recording.vceFields().containsKey("VCE_P25_RX_SYSID"));
                            assertFalse(recording.vceFields().containsKey("VCE_P25_RX_RFSS"));
                            assertFalse(recording.vceFields().containsKey("VCE_P25_RX_SITE"));
                        }
                        else
                        {
                            assertEquals(index == 2 ? "BEE00" : "ABCDE",
                                recording.vceFields().get("VCE_P25_RX_WACN"));
                            assertEquals(index == 2 ? "456" : "123",
                                recording.vceFields().get("VCE_P25_RX_SYSID"));
                        }
                        if(format == RecordFormat.WAVE)
                        {
                            Map<String, String> info = waveInfoFields(recording.waveChunks().get("LIST"));
                            assertEquals(Integer.toString(local), info.get("IART"));
                            assertEquals(Integer.toString(local), info.get("INAM"));
                        }
                        assertTrue(BasicRecordingContract.filename(identifiers, format, 1_000, 1, 1)
                            .endsWith("_TO_" + local + "_FROM_" + local + format.getExtension()));
                    }
                }
            }
        }
        finally
        {
            preferences.getRecordPreference().setRecordingMode(originalMode);
        }
    }

    @Test
    void qualifiedTalkgroupsAndPatchMembersUseLocalIdsInActualMp3AndWave() throws Exception
    {
        var primary = APCO25FullyQualifiedTalkgroupIdentifier.createTo(0, 0xBEE00, 0x456, 45001);
        var patch = new PatchGroup(primary);
        patch.addPatchedTalkgroup(APCO25FullyQualifiedTalkgroupIdentifier.createTo(77, 0xABCDE, 0x123, 45002));
        patch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createTo(0, 0xBEE00, 0x456, 900002));
        Identifier<?> source = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501,
            0xABCDE, 0x123, 7654321);
        List<Identifier<?>> destinations = List.of(primary, APCO25PatchGroup.create(patch));
        List<String> titles = List.of("0", "P:0 [77] [0]");

        for(int index = 0; index < destinations.size(); index++)
        {
            IdentifierCollection identifiers = identifiers(source, destinations.get(index));
            CompletedAudioCall call = call(identifiers, SOURCE, VoiceCallQuality.EMPTY,
                CallEncryptionState.UNKNOWN, 0);
            for(RecordFormat format: RecordFormat.values())
            {
                Recording recording = writeAndRead(call, "qualified-destination-" + index, format, null);
                ID3v24Tag tag = new ID3v24Tag(recording.id3());
                assertEquals("501", tag.getArtist());
                assertEquals(titles.get(index), tag.getTitle());
                assertEquals("ABCDE.123.7654321", recording.vceFields().get("VCE_P25_SUID"));
                assertEquals("501", recording.vceFields().get("VCE_P25_WUID"));
                if(format == RecordFormat.WAVE)
                {
                    Map<String, String> info = waveInfoFields(recording.waveChunks().get("LIST"));
                    assertEquals("501", info.get("IART"));
                    assertEquals(titles.get(index), info.get("INAM"));
                }
            }
        }
    }

    private static Map<String, String> waveInfoFields(byte[] chunk)
    {
        ByteBuffer buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
        byte[] four = new byte[4];
        buffer.get(four);
        assertEquals("INFO", new String(four, StandardCharsets.US_ASCII));
        Map<String, String> fields = new LinkedHashMap<>();
        while(buffer.remaining() >= 8)
        {
            buffer.get(four);
            String name = new String(four, StandardCharsets.US_ASCII);
            int length = buffer.getInt();
            byte[] value = new byte[length];
            buffer.get(value);
            int textLength = 0;
            while(textLength < length && value[textLength] != 0) textLength++;
            fields.put(name, new String(value, 0, textLength, StandardCharsets.UTF_8));
        }
        return fields;
    }

    @Test
    void acceptedExtendedGroupEvidenceFlowsThroughResolverRealFilesAndCatalog() throws Exception
    {
        P25SiteIdentity site = SOURCE.p25SiteIdentity();
        P25IdentityResolver resolver = new P25IdentityResolver();
        resolver.register(site, new P25RadioPresence(new P25SubscriberIdentity(0xABCDE, 0x123, 7654321), 120045),
            1_000L, null);
        for(int[] pair: List.of(new int[]{3101, 501}, new int[]{91, 701}))
        {
            CorrectedBinaryMessage bits = new CorrectedBinaryMessage(128);
            bits.load(0, 8, 0xE8);
            bits.load(40, 16, pair[0]);
            bits.load(56, 20, 0xBCDEF);
            bits.load(76, 12, 0x321);
            bits.load(88, 16, pair[1]);
            bits.load(104, 24, 120045);
            GroupAffiliationResponseExtended decoded = new GroupAffiliationResponseExtended(bits, 0);
            assertEquals(Response.ACCEPTED, decoded.getResponse());
            assertTrue(resolver.observeGroup(site, decoded.getSourceGID(),
                decoded.getResponse() == Response.ACCEPTED, 1_000L, null));
        }
        PatchGroup rawPatch = new PatchGroup(APCO25Talkgroup.create(3101));
        rawPatch.addPatchedTalkgroup(APCO25Talkgroup.create(91));
        rawPatch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(
            220078, 0xCDEF0, 0x234, 987654));
        IdentifierCollection enriched = resolver.enrich(site,
            identifiers(APCO25RadioIdentifier.createFrom(120045), APCO25PatchGroup.create(rawPatch)), 1_001L);
        CompletedAudioCall call = call(enriched, SOURCE, VoiceCallQuality.EMPTY, CallEncryptionState.CLEAR, 1);
        for(RecordFormat format: RecordFormat.values())
        {
            String name = "resolved-group-" + format.name();
            Recording recording = writeAndRead(call, name, format, null);
            ID3v24Tag standard = new ID3v24Tag(recording.id3());
            assertEquals("120045", standard.getArtist());
            assertEquals("P:3101 [91] [220078]", standard.getTitle());
            assertEquals("BCDEF.321.501", recording.vceFields().get("VCE_P25_GID"));
            assertEquals("3101", recording.vceFields().get("VCE_P25_GROUP_ADDRESS"));
            assertEquals("BCDEF.321.701", recording.vceFields().get("VCE_PATCH_TALKGROUP_1_GID"));
            assertEquals("91", recording.vceFields().get("VCE_PATCH_TALKGROUP_1_GROUP_ADDRESS"));
            assertEquals("CDEF0.234.987654", recording.vceFields().get("VCE_PATCH_RADIO_1_SUID"));
            assertEquals("220078", recording.vceFields().get("VCE_PATCH_RADIO_1_WUID"));
            assertEquals("ABCDE.123.7654321", recording.vceFields().get("VCE_P25_SUID"));
            assertExistingFieldsUnchanged(call, enriched, recording);
            Path saved = mDirectory.resolve(name + format.getExtension());
            try(ManagedRecordingCatalog catalog = new ManagedRecordingCatalog(
                mDirectory.resolve("catalog-" + format.name() + ".sqlite"), mDirectory))
            {
                CountDownLatch indexed = new CountDownLatch(1);
                assertTrue(catalog.submit(saved, call, Files.size(saved), indexed::countDown));
                assertTrue(indexed.await(5, TimeUnit.SECONDS), "real recording must be indexed");
                var row = catalog.search(ManagedRecordingCatalog.SearchFilter.builder()
                    .fromMs(0L).toMs(5_000L).build()).calls().getFirst();
                assertEquals(3101, row.targetId());
                assertEquals(501, row.targetHomeId());
                assertEquals(0xBCDEF, row.targetHomeWacn());
                assertEquals(Map.of("wacn", 0xBCDEF, "system_id", 0x321, "talkgroup_id", 501),
                    row.toMap().get("target_canonical_group_identity"));
                assertEquals(3101, row.toMap().get("target_observed_local_id"));
                assertTrue(row.patchMembers().stream().anyMatch(member -> "talkgroup".equals(member.kind()) &&
                    member.id() == 91 && Integer.valueOf(701).equals(member.homeIdentityId())));
            }
        }
    }

    @Test
    void homeOnlyAndZeroGroupAddressesKeepFullGidWithoutInventingLocalAddress() throws Exception
    {
        for(int local: new int[]{0, 501})
        {
            CompletedAudioCall call = call(identifiers(APCO25RadioIdentifier.createFrom(120045),
                APCO25FullyQualifiedTalkgroupIdentifier.createTo(local, 0xBCDEF, 0x321, 501)),
                CallLegSource.UNKNOWN, VoiceCallQuality.EMPTY, CallEncryptionState.UNKNOWN, 0);
            for(RecordFormat format: RecordFormat.values())
            {
                Recording recording = writeAndRead(call, "home-only-group-" + local, format, null);
                assertEquals(Integer.toString(local), new ID3v24Tag(recording.id3()).getTitle());
                assertEquals("BCDEF.321.501", recording.vceFields().get("VCE_P25_GID"));
                assertFalse(recording.vceFields().containsKey("VCE_P25_GROUP_ADDRESS"));
                assertFalse(recording.vceFields().containsKey("VCE_P25_RX_WACN"));
            }
        }
    }

    @Test
    void actualFromGroupsPreserveSourceGidAndProvenLocalAddressInMp3AndWaveBytes() throws Exception
    {
        for(int local : new int[]{91, 0, 701})
        {
            Identifier<?> source = local == 701 ?
                APCO25FullyQualifiedTalkgroupIdentifier.createFrom(local, 0xABCDF, 0x124, 701) :
                APCO25FullyQualifiedTalkgroupIdentifier.createFromWithLocalAddress(local, 0xABCDF, 0x124, 701);
            IdentifierCollection identifiers = identifiers(source,
                APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(3101, 0xABCDE, 0x123, 501));
            CompletedAudioCall call = call(identifiers, CallLegSource.UNKNOWN, VoiceCallQuality.EMPTY,
                CallEncryptionState.UNKNOWN, 0);
            for(RecordFormat format : RecordFormat.values())
            {
                Recording recording = writeAndRead(call, "source-group-" + local, format, null);
                ID3v24Tag standard = new ID3v24Tag(recording.id3());
                assertEquals(Integer.toString(local), standard.getArtist());
                assertEquals("3101", standard.getTitle());
                assertEquals("ABCDF.124.701", recording.vceFields().get("VCE_P25_SOURCE_GID"));
                if(local == 91) assertEquals("91", recording.vceFields().get("VCE_P25_SOURCE_GROUP_ADDRESS"));
                else assertFalse(recording.vceFields().containsKey("VCE_P25_SOURCE_GROUP_ADDRESS"));
                assertEquals("ABCDE.123.501", recording.vceFields().get("VCE_P25_GID"));
                assertEquals("3101", recording.vceFields().get("VCE_P25_GROUP_ADDRESS"));
                assertFalse(recording.vceFields().containsKey("VCE_P25_SUID"));
                assertFalse(recording.vceFields().containsKey("VCE_P25_WUID"));
                assertFalse(recording.vceFields().containsKey("VCE_P25_RX_WACN"));
                assertExistingFieldsUnchanged(call, identifiers, recording);
                if(format == RecordFormat.WAVE)
                {
                    Map<String,String> info = waveInfoFields(recording.waveChunks().get("LIST"));
                    assertEquals(Integer.toString(local), info.get("IART"));
                    assertEquals("3101", info.get("INAM"));
                }
            }
        }
    }

    private Recording writeAndRead(CompletedAudioCall call, String name, RecordFormat format,
                                    IdentifierCollection standardIdentifiers) throws Exception
    {
        Path path = mDirectory.resolve(name + format.getExtension());
        if(standardIdentifiers == null)
        {
            AudioCallRecorder.write(call, path, format, new UserPreferences());
        }
        else
        {
            AudioCallRecorder.write(call, path, format, new UserPreferences(), standardIdentifiers);
        }
        return readRecording(Files.readAllBytes(path), format);
    }

    private static Recording readRecording(byte[] bytes, RecordFormat format)
    {
        Map<String, byte[]> chunks = new LinkedHashMap<>();
        byte[] id3 = bytes;
        if(format == RecordFormat.WAVE)
        {
            assertEquals("RIFF", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
            assertEquals("WAVE", new String(bytes, 8, 4, StandardCharsets.US_ASCII));
            assertEquals(bytes.length - 8, littleEndianInt(bytes, 4));
            int offset = 12;
            while(offset < bytes.length)
            {
                assertTrue(offset + 8 <= bytes.length, "Complete RIFF chunk header");
                String name = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
                int size = littleEndianInt(bytes, offset + 4);
                assertTrue(size >= 0 && offset + 8L + size <= bytes.length, "Valid RIFF chunk size");
                assertFalse(chunks.containsKey(name), "Each RIFF chunk must be unique");
                chunks.put(name, Arrays.copyOfRange(bytes, offset + 8, offset + 8 + size));
                offset += 8 + size + (size & 1);
            }
            assertEquals(bytes.length, offset, "RIFF chunks and alignment consume the complete file");
            assertNotNull(chunks.get("fmt "));
            assertEquals(16_000, chunks.get("data").length, "One second of 8 kHz 16-bit mono PCM survives");
            id3 = chunks.get("id3 ");
            assertNotNull(id3);
            assertEquals(0, id3.length % 4, "Embedded ID3 keeps the existing WAV padding contract");
        }

        List<Frame> frames = readFrames(id3);
        int id3End = 10 + synchsafe(id3, 6);
        if(format == RecordFormat.MP3)
        {
            assertTrue(bytes.length > id3End + 4, "Encoded audio follows the complete ID3 block");
            assertEquals(0xFF, bytes[id3End] & 0xFF, "First MPEG audio frame sync");
            assertEquals(0xE0, bytes[id3End + 1] & 0xE0, "First MPEG audio frame sync");
        }
        else
        {
            for(int offset = id3End; offset < id3.length; offset++)
            {
                assertEquals(0, id3[offset], "ID3 chunk alignment contains zero padding");
            }
        }

        Map<String, String> fields = new LinkedHashMap<>();
        for(Frame frame: frames)
        {
            if(!"TXXX".equals(frame.name())) continue;
            byte[] payload = frame.payload();
            assertTrue(payload.length > 2);
            assertEquals(3, payload[0], "Every VCE description and value is ID3v2.4 UTF-8");
            int separator = 1;
            while(separator < payload.length && payload[separator] != 0) separator++;
            assertTrue(separator < payload.length, "TXXX description has a NUL terminator");
            String name = new String(payload, 1, separator - 1, StandardCharsets.UTF_8);
            String value = new String(payload, separator + 1, payload.length - separator - 1,
                StandardCharsets.UTF_8);
            assertTrue(name.startsWith("VCE_"));
            assertFalse(value.isEmpty(), "Unknown facts must be omitted instead of encoded as empty values");
            assertFalse(value.contains("\u0000"));
            assertFalse(fields.containsKey(name), "Named TXXX fields occur exactly once: " + name);
            fields.put(name, value);
        }
        assertFalse(fields.keySet().stream().anyMatch(key -> key.contains("RSSI") || key.contains("SNR") ||
            key.contains("BER")), "Unavailable signal measurements must never be manufactured");
        return new Recording(id3, frames, chunks, fields);
    }

    private static void assertExistingFieldsUnchanged(CompletedAudioCall call, IdentifierCollection identifiers,
                                                      Recording recording) throws Exception
    {
        ID3v24Tag actual = new ID3v24Tag(recording.id3());
        String date = actual.getDate();
        assertTrue(date.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}"));
        Map<AudioMetadata, String> original = AudioMetadataUtils.getMetadataMap(identifiers,
            call.snapshot().aliasList());
        // The legacy writer stamps the current clock. Normalize only that value before comparing exact bytes.
        original.put(AudioMetadata.DATE_CREATED, date);
        original.put(AudioMetadata.YEAR, date.substring(0, 4));
        original.put(AudioMetadata.COMMENTS,
            original.get(AudioMetadata.COMMENTS).replaceFirst("Date:[^;]+;", "Date:" + date + ";"));
        List<Frame> baseline = readFrames(AudioMetadataUtils.getMP3ID3(original));
        List<Frame> standard = recording.frames().stream().filter(frame -> !"TXXX".equals(frame.name())).toList();
        assertEquals(baseline.size(), standard.size(), "Standard ID3 frame count is unchanged");
        for(Frame expected: baseline)
        {
            List<Frame> matches = standard.stream().filter(frame -> expected.name().equals(frame.name())).toList();
            assertEquals(1, matches.size(), "Standard frame remains present once: " + expected.name());
            assertArrayEquals(expected.bytes(), matches.getFirst().bytes(),
                "Existing ID3 frame bytes remain unchanged: " + expected.name());
        }
        if(recording.waveChunks().containsKey("LIST"))
        {
            byte[] list = AudioMetadataUtils.getLISTChunk(original).array();
            assertArrayEquals(Arrays.copyOfRange(list, 8, list.length), recording.waveChunks().get("LIST"),
                "Existing LIST/INFO chunk bytes remain unchanged");
        }
    }

    /** Parses the ID3v2.4 framing independently of the production ID3 library. */
    private static List<Frame> readFrames(byte[] id3)
    {
        assertTrue(id3.length >= 10);
        assertEquals("ID3", new String(id3, 0, 3, StandardCharsets.US_ASCII));
        assertEquals(4, id3[3]);
        assertEquals(0, id3[4]);
        assertEquals(0, id3[5]);
        int end = 10 + synchsafe(id3, 6);
        assertTrue(end <= id3.length, "ID3 header size fits the actual file");
        List<Frame> frames = new ArrayList<>();
        int offset = 10;
        while(offset < end && id3[offset] != 0)
        {
            assertTrue(offset + 10 <= end);
            String name = new String(id3, offset, 4, StandardCharsets.US_ASCII);
            assertTrue(name.matches("[A-Z0-9]{4}"));
            int size = synchsafe(id3, offset + 4);
            assertTrue(size > 0 && offset + 10L + size <= end, "Complete frame payload: " + name);
            assertEquals(0, id3[offset + 8]);
            assertEquals(0, id3[offset + 9]);
            frames.add(new Frame(name, Arrays.copyOfRange(id3, offset, offset + 10 + size),
                Arrays.copyOfRange(id3, offset + 10, offset + 10 + size)));
            offset += 10 + size;
        }
        for(; offset < end; offset++) assertEquals(0, id3[offset], "ID3 tag padding is zero");
        return frames;
    }

    private static int synchsafe(byte[] bytes, int offset)
    {
        int value = 0;
        for(int index = offset; index < offset + 4; index++)
        {
            assertEquals(0, bytes[index] & 0x80, "ID3v2.4 sizes use synchsafe bytes");
            value = (value << 7) | (bytes[index] & 0x7F);
        }
        return value;
    }

    private static int littleEndianInt(byte[] bytes, int offset)
    {
        return ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    private static Map<String, String> fullGroupFields()
    {
        return new LinkedHashMap<>(Map.ofEntries(
            Map.entry("VCE_P25_WUID", "120045"),
            Map.entry("VCE_P25_SUID", "ABCDE.123.7654321"),
            Map.entry("VCE_P25_RX_WACN", "BEE00"),
            Map.entry("VCE_P25_RX_SYSID", "456"),
            Map.entry("VCE_P25_RX_RFSS", "1"),
            Map.entry("VCE_P25_RX_SITE", "7"),
            Map.entry("VCE_P25_NAC", "3A7"),
            Map.entry("VCE_TALKGROUP", "3101"),
            Map.entry("VCE_FREQUENCY_HZ", "851012500"),
            Map.entry("VCE_PROTOCOL", "APCO25"),
            Map.entry("VCE_P25_PHASE", "2"),
            Map.entry("VCE_TIMESLOT", "1"),
            Map.entry("VCE_ENCRYPTION", "CLEAR"),
            Map.entry("VCE_VOICE_DECODED_FRAMES", "480"),
            Map.entry("VCE_VOICE_REPEATED_FRAMES", "8"),
            Map.entry("VCE_VOICE_CONCEALED_FRAMES", "4"),
            Map.entry("VCE_VOICE_MISSING_FRAMES", "8"),
            Map.entry("VCE_VOICE_FEC_ERRORS", "144"),
            Map.entry("VCE_VOICE_FEC_PROTECTED_BITS", "72000"),
            Map.entry("VCE_VOICE_FEC_CORRECTION_PERCENT", "0.200000")));
    }

    private static IdentifierCollection groupIdentifiers()
    {
        return identifiers(SystemConfigurationIdentifier.create("Synthetic System"),
            SiteConfigurationIdentifier.create("Synthetic Site"),
            ChannelNameConfigurationIdentifier.create("Synthetic Voice"),
            DecoderTypeConfigurationIdentifier.create(DecoderType.P25_PHASE2),
            FrequencyConfigurationIdentifier.create(851012500L), APCO25Nac.create(0x3A7),
            APCO25Wacn.create(0xBEE00), APCO25System.create(0x456),
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(120045, 0xABCDE, 0x123, 7654321),
            APCO25Talkgroup.create(3101));
    }

    private static IdentifierCollection identifiers(Identifier<?>... identifiers)
    {
        return new IdentifierCollection(new ArrayList<Identifier>(Arrays.asList(identifiers)));
    }

    private static CallLegSource source(DecoderType decoder, int wacn, int sysid, int rfss, int site)
    {
        return new CallLegSource(decoder, null, null, null, 0,
            new P25SiteIdentity(wacn, sysid, rfss, site), TrunkedIdentityDomain.STANDARD, null, false);
    }

    private static CompletedAudioCall call(IdentifierCollection identifiers, CallLegSource source,
                                           VoiceCallQuality quality, CallEncryptionState encryption, int timeslot)
    {
        AudioCallId callId = new AudioCallId(1, 1, timeslot);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null, identifiers, Set.of(),
            1_000, 2_000, 1, 1, 1_000, 2_000, false, true, encryption, true, null, quality,
            CallLegId.from(callId), source, null);
        List<float[]> audio = new ArrayList<>();
        for(int frame = 0; frame < 50; frame++)
        {
            float[] samples = new float[160];
            for(int index = 0; index < samples.length; index++)
            {
                samples[index] = (float)(0.2 * Math.sin((frame * 160 + index) * Math.PI / 10));
            }
            audio.add(samples);
        }
        return new CompletedAudioCall(snapshot, audio);
    }

    private record Frame(String name, byte[] bytes, byte[] payload) {}
    private record Recording(byte[] id3, List<Frame> frames, Map<String, byte[]> waveChunks,
                             Map<String, String> vceFields) {}
}
