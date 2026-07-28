/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.record.wave.AudioMetadata;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AudioCallRecorderTest
{
    @Test
    void appendsVoiceQualityToRecordingComments()
    {
        AudioCallSnapshot snapshot = new AudioCallSnapshot(new AudioCallId(1, 1, 0), null, null,
            new IdentifierCollection(), Set.of(), 1_000L, 2_000L, 1, 1, 1_000L, 2_000L,
            false, true, false, true, 50, false, null,
            new VoiceCallQuality(49, 1, 0, 0, 4, 6_850));
        CompletedAudioCall call = new CompletedAudioCall(snapshot, List.of(new float[160]));
        Map<AudioMetadata,String> metadata = new EnumMap<>(AudioMetadata.class);
        metadata.put(AudioMetadata.COMMENTS, "System:Test;");

        AudioCallRecorder.addVoiceQualityMetadata(metadata, call);

        assertEquals("System:Test;VC Quality:98.0%;VC Frames:49/1/0/0;VC FEC:4/6850;",
            metadata.get(AudioMetadata.COMMENTS));
    }
}
