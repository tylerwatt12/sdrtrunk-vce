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
import io.github.dsheirer.audio.AudioFormats;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.record.RecordingMode;
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
    void completeMp3AndWavUseUpstreamTagsInBothRecordingModes() throws Exception
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

                    assertEquals("501(781824.1671.70001)", tag.getArtist());
                    assertEquals("ROAM 777(703710.1110.9001)", tag.getTitle());
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
                        assertTrue(info.contains("501(781824.1671.70001)"));
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
        assertEquals("501(781824.1671.70001)", tag.getArtist());
        assertFalse(tag.getComment().contains("VC "));
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
