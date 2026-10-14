/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mpatric.mp3agic.ID3v24Tag;
import com.mpatric.mp3agic.Mp3File;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.radio.P25Subscriber;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.audio.AudioFormats;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.audio.convert.InputAudioFormat;
import io.github.dsheirer.audio.convert.MP3Setting;
import io.github.dsheirer.audio.enhance.VocoderAudioFixtures;
import io.github.dsheirer.dsp.gain.AudioOutputGain;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.record.RecordingMode;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.record.wave.WaveWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioCallRecorderTest
{
    @TempDir Path mDirectory;

    @Test
    void longAgcCallRecordsResampledMp3WithoutChangingNativePcmOrBufferBoundaries() throws Exception
    {
        List<float[]> buffers = new ArrayList<>(), original = new ArrayList<>();
        for(int frame = 0; frame < 600; frame++)
        {
            float[] samples = new float[160];
            for(int index = 0; index < samples.length; index++)
                samples[index] = (float)(.1 * Math.sin((frame * 160 + index) * Math.PI / 10));
            buffers.add(samples);
            original.add(samples.clone());
        }
        CompletedAudioCall source = new CompletedAudioCall(callWithQuality().snapshot(), buffers);
        var provider = VocoderAudioFixtures.provider("recording-buffer-test");
        provider.mutateInput = true;
        var prepared = AudioOutputGain.prepare(source, provider.bridge());
        assertTrue(prepared.agcApplied());
        assertEquals(1, provider.processCount, "JMBE still processes the entire call continuously once");
        assertEquals(12_000, prepared.call().getDuration());

        UserPreferences preferences = new UserPreferences();
        InputAudioFormat originalRate = preferences.getMP3Preference().getAudioSampleRate();
        MP3Setting originalSetting = preferences.getMP3Preference().getMP3Setting();
        try
        {
            for(InputAudioFormat rate: List.of(InputAudioFormat.getDefault(), InputAudioFormat.SR_44100))
            {
                preferences.getMP3Preference().setMP3Setting(rate == InputAudioFormat.SR_44100 ?
                    MP3Setting.VBR_5 : MP3Setting.getDefault());
                preferences.getMP3Preference().setAudioSampleRate(rate);
                Path file = mDirectory.resolve("long-agc-" + rate.name() + ".mp3");
                AudioCallRecorder.write(prepared.call(), file, RecordFormat.MP3, preferences, false);
                Mp3File recorded = new Mp3File(file.toString());
                assertEquals((int)rate.getSampleRate(), recorded.getSampleRate());
                assertTrue(recorded.hasId3v2Tag());
                assertTrue(recorded.getFrameCount() > 100, "Recording contains audio frames beyond its ID3 tag");
                assertTrue(recorded.getLengthInMilliseconds() >= 11_900 &&
                    recorded.getLengthInMilliseconds() <= 12_300, "All twelve seconds were encoded");
            }
        }
        finally
        {
            preferences.getMP3Preference().setMP3Setting(originalSetting);
            preferences.getMP3Preference().setAudioSampleRate(originalRate);
        }
        assertEquals(buffers.size(), prepared.call().audioBuffers().size());
        for(int frame = 0; frame < buffers.size(); frame++)
        {
            assertArrayEquals(original.get(frame), buffers.get(frame), "Native PCM remains immutable");
            float[] output = prepared.call().audioBuffers().get(frame);
            assertEquals(buffers.get(frame).length, output.length);
            for(int index = 0; index < output.length; index++)
                assertEquals(original.get(frame)[index] * 2, output[index], "Processed order and gain are preserved");
        }
    }

    @Test
    void completeMp3AndWavUseLocalIdTagsInBothRecordingModes() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();
        CompletedAudioCall call = callWithQuality();
        try
        {
            for(RecordingMode mode: RecordingMode.values())
            {
                preferences.getRecordPreference().setRecordingMode(mode);
                for(RecordFormat format: RecordFormat.values())
                {
                    Path path = mDirectory.resolve(mode.name() + format.getExtension());
                    AudioCallRecorder.write(call, path, format, preferences);
                    byte[] bytes = Files.readAllBytes(path);
                    byte[] id3 = format == RecordFormat.MP3 ? bytes : waveChunk(bytes, "id3 ");
                    ID3v24Tag tag = new ID3v24Tag(id3);

                    assertEquals("501", tag.getArtist());
                    assertEquals("777", tag.getTitle());
                    assertEquals("SyntheticSystem", tag.getGrouping());
                    assertEquals(BasicRecordingContract.composer(), tag.getComposer());
                    assertTrue(tag.getComposer().startsWith("sdrtrunk"));
                    assertTrue(tag.getComment().matches("Date:\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3};System:SyntheticSystem;"));
                    assertFalse(tag.getComment().contains("VC "));
                    assertFalse(tag.getArtist().contains("Working ID"));
                    if(format == RecordFormat.WAVE)
                    {
                        assertEquals("RIFF", new String(bytes, 0, 4, StandardCharsets.US_ASCII));
                        assertEquals(bytes.length - 8, ByteBuffer.wrap(bytes, 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
                        assertTrue(waveChunk(bytes, "data").length > 0);
                        String info = new String(waveChunk(bytes, "LIST"), StandardCharsets.UTF_8);
                        assertTrue(info.contains("501"));
                        assertFalse(info.contains("781824.1671.70001"));
                        assertFalse(info.contains("VC Quality"));
                    }
                    else
                    {
                        int tagSize = 10 + ((bytes[6] & 0x7F) << 21) + ((bytes[7] & 0x7F) << 14) +
                            ((bytes[8] & 0x7F) << 7) + (bytes[9] & 0x7F);
                        assertTrue(bytes.length > tagSize, "MP3 must include encoded audio after complete metadata");
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
    void standaloneWaveConversionUsesTheSameTagContract() throws Exception
    {
        CompletedAudioCall call = callWithQuality();
        Path path = mDirectory.resolve("converted.wav");
        AudioCallRecorder.recordWAVE(call, path, call.snapshot().identifierCollection());
        ID3v24Tag tag = new ID3v24Tag(waveChunk(Files.readAllBytes(path), "id3 "));
        assertEquals("501", tag.getArtist());
        assertFalse(tag.getComment().contains("VC "));
    }

    @Test
    void finalMp3AndWaveTagsUseOriginalWinnerIdentifiersAfterCanonicalCallResolution() throws Exception
    {
        CompletedAudioCall resolved = callWithQuality();
        IdentifierCollection observed = new IdentifierCollection(List.of(
            SystemConfigurationIdentifier.create("SyntheticSystem"),
            APCO25RadioIdentifier.createFrom(501), APCO25Talkgroup.create(48530)));
        CompletedAudioCall call = new CompletedAudioCall(resolved.logicalCallId(), resolved.snapshot(),
            resolved.audioBuffers(), resolved.resolvedPolicy(), resolved.callLegSummaries(), observed);

        for(RecordFormat format: RecordFormat.values())
        {
            Path path = mDirectory.resolve("winner" + format.getExtension());
            AudioCallRecorder.write(call, path, format, new UserPreferences());
            byte[] bytes = Files.readAllBytes(path);
            ID3v24Tag tag = new ID3v24Tag(format == RecordFormat.MP3 ? bytes : waveChunk(bytes, "id3 "));
            assertEquals("501", tag.getArtist());
            assertEquals("48530", tag.getTitle());
            if(format == RecordFormat.WAVE)
            {
                String info = new String(waveChunk(bytes, "LIST"), StandardCharsets.UTF_8);
                assertFalse(info.contains("781824.1671"));
                assertFalse(info.contains("703710.1110"));
            }
        }
        assertTrue(call.snapshot().identifierCollection().getFromIdentifier() instanceof
            APCO25FullyQualifiedRadioIdentifier, "Call matching retains the resolved canonical identity");
    }

    @Test
    void finalTagsUseWinnerReceivingSystemAndKeepQualifiedAliasesInBothRecordingModes() throws Exception
    {
        AliasList aliases = new AliasList(new AliasListDefinition("P25", AliasListFamily.P25));
        Alias canonical = new Alias("Canonical Unit");
        canonical.setMatchIdentifier(new P25Subscriber(0xBEE00, 0x687, 70001));
        aliases.addAlias(canonical);
        Alias numeric = new Alias("Numeric Unit");
        numeric.setMatchIdentifier(new Radio(Protocol.APCO25, 70001));
        aliases.addAlias(numeric);
        Alias working = new Alias("Working Unit");
        working.setMatchIdentifier(new Radio(Protocol.APCO25, 501));
        aliases.addAlias(working);
        IdentifierCollection observed = new IdentifierCollection(List.of(
            SystemConfigurationIdentifier.create("SyntheticSystem"),
            APCO25Wacn.create(0xABCDE), APCO25System.create(0x456),
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(70001, 0xBEE00, 0x687, 70001),
            APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(501, 0xBEE00, 0x687, 70001)));
        CallLegSource winner = new CallLegSource(DecoderType.P25_PHASE1, null, null, null, 0,
            new P25SiteIdentity(0xABCDE, 0x456, 1, 1), TrunkedIdentityDomain.STANDARD, null, false,
            "p25:bee00:687");
        CompletedAudioCall call = callWithContext(winner, observed, observed, aliases);
        UserPreferences preferences = new UserPreferences();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();
        try
        {
            for(RecordingMode mode: RecordingMode.values())
            {
                preferences.getRecordPreference().setRecordingMode(mode);
                for(RecordFormat format: RecordFormat.values())
                {
                    Path path = mDirectory.resolve("same-system-" + mode + format.getExtension());
                    AudioCallRecorder.write(call, path, format, preferences);
                    byte[] bytes = Files.readAllBytes(path);
                    ID3v24Tag tag = new ID3v24Tag(format == RecordFormat.MP3 ? bytes : waveChunk(bytes, "id3 "));
                    assertEquals("70001 Canonical Unit", tag.getArtist());
                    assertEquals("501\"Canonical Unit\"", tag.getTitle());
                    assertEquals("SyntheticSystem", tag.getGrouping());
                    assertFalse(tag.getArtist().contains("Numeric Unit"));
                    assertFalse(tag.getTitle().contains("Working Unit"));
                    assertFalse(tag.getTitle().contains("70001"),
                        "A different local address must not emit a second permanent unit number");
                    assertFalse(tag.getArtist().contains("Working ID"));
                    assertFalse(tag.getTitle().contains("Working ID"));
                    if(format == RecordFormat.WAVE)
                    {
                        String info = new String(waveChunk(bytes, "LIST"), StandardCharsets.UTF_8);
                        assertTrue(info.contains("70001 Canonical Unit"));
                        assertTrue(info.contains("501\"Canonical Unit\""));
                        assertFalse(info.contains("781824.1671"));
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
    void finalTagsUseLocalIdsWhenWinnerSystemIsUnknown() throws Exception
    {
        IdentifierCollection observed = new IdentifierCollection(List.of(
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(70001, 0xBEE00, 0x687, 70001)));
        IdentifierCollection resolved = new IdentifierCollection(List.of(
            APCO25Wacn.create(0xBEE00), APCO25System.create(0x687), APCO25RadioIdentifier.createFrom(70001)));
        CompletedAudioCall call = callWithContext(CallLegSource.UNKNOWN, observed, resolved, null);
        for(RecordFormat format: RecordFormat.values())
        {
            Path path = mDirectory.resolve("unknown-winner" + format.getExtension());
            AudioCallRecorder.write(call, path, format, new UserPreferences());
            byte[] bytes = Files.readAllBytes(path);
            ID3v24Tag tag = new ID3v24Tag(format == RecordFormat.MP3 ? bytes : waveChunk(bytes, "id3 "));
            assertEquals("70001", tag.getArtist());
        }
    }

    @Test
    void existingFinalWavReportsCollisionWithoutChangingBytes() throws Exception
    {
        Path path = mDirectory.resolve("existing.wav");
        byte[] existing = {7, 8, 9};
        Files.write(path, existing);
        assertThrows(FileAlreadyExistsException.class,
            () -> new WaveWriter(AudioFormats.PCM_SIGNED_8000_HZ_16_BIT_MONO, path));
        assertArrayEquals(existing, Files.readAllBytes(path));
    }

    @Test
    void temporaryWaveCollisionStillUsesTemporarySeries() throws Exception
    {
        Path path = mDirectory.resolve("capture.tmp");
        Files.write(path, new byte[]{7});
        try(WaveWriter ignored = new WaveWriter(AudioFormats.PCM_SIGNED_8000_HZ_16_BIT_MONO, path))
        {
            assertTrue(Files.isRegularFile(mDirectory.resolve("capture_2.tmp")));
        }
        assertArrayEquals(new byte[]{7}, Files.readAllBytes(path));
    }

    private static CompletedAudioCall callWithQuality()
    {
        AudioCallId callId = new AudioCallId(1, 1, 0);
        IdentifierCollection identifiers = new IdentifierCollection(List.of(
            SystemConfigurationIdentifier.create("SyntheticSystem"),
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501, 0xBEE00, 0x687, 70001),
            APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(777, 0xABCDE, 0x456, 9001)));
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            identifiers, Set.of(), 1_000L, 2_000L, 1, 1, 1_000L, 2_000L,
            false, true, CallEncryptionState.CLEAR, true, null,
            new VoiceCallQuality(49, 1, 0, 0, 4, 6_850), CallLegId.from(callId), null, null);
        List<float[]> audio = new ArrayList<>();
        for(int frame = 0; frame < 50; frame++)
        {
            float[] samples = new float[160];
            for(int index = 0; index < samples.length; index++) samples[index] =
                (float)(0.2 * Math.sin((frame * 160 + index) * Math.PI / 10));
            audio.add(samples);
        }
        return new CompletedAudioCall(snapshot, audio);
    }

    private static CompletedAudioCall callWithContext(CallLegSource source, IdentifierCollection observed,
                                                       IdentifierCollection resolved, AliasList aliases)
    {
        CompletedAudioCall base = callWithQuality();
        AudioCallSnapshot old = base.snapshot();
        AudioCallSnapshot snapshot = new AudioCallSnapshot(old.callId(), old.linkedCallId(), aliases, resolved,
            old.broadcastChannels(), old.startTimestamp(), old.lastActivityTimestamp(), old.burstCount(),
            old.burstGeneration(), old.lastBurstStartTimestamp(), old.lastBurstEndTimestamp(), old.burstActive(),
            old.complete(), old.encryptionState(), old.recordAudio(), null, old.voiceCallQuality(),
            old.callLegId(), source, old.callEncryptionEvidence());
        CompletedAudioCall oneLeg = new CompletedAudioCall(snapshot, base.audioBuffers());
        return new CompletedAudioCall(oneLeg.logicalCallId(), snapshot, oneLeg.audioBuffers(), oneLeg.resolvedPolicy(),
            oneLeg.callLegSummaries(), observed);
    }

    private static byte[] waveChunk(byte[] bytes, String name)
    {
        for(int offset = 12; offset + 8 <= bytes.length; )
        {
            String id = new String(bytes, offset, 4, StandardCharsets.US_ASCII);
            int size = ByteBuffer.wrap(bytes, offset + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if(name.equals(id)) return Arrays.copyOfRange(bytes, offset + 8, offset + 8 + size);
            offset += 8 + size + (size & 1);
        }
        throw new AssertionError("Missing WAV chunk " + name);
    }
}
