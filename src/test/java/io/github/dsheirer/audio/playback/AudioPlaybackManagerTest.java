/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.playback;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.DecoderTypeConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.decoder.DecoderLogicalChannelNameIdentifier;
import io.github.dsheirer.identifier.decoder.TrafficChannelIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioPlaybackManagerTest
{
    @Test
    void unknownAliasWithDestinationIsReadyWithoutLosingOpeningAudio()
    {
        ManagedPlayableAudioCall call = new ManagedPlayableAudioCall(snapshot(true, false, false));
        float[] first = new float[] {0.1f, 0.2f};
        float[] second = new float[] {0.3f, 0.4f};
        call.appendAudio(first);
        call.appendAudio(second);

        assertTrue(AudioPlaybackManager.isReadyForPlayback(call));
        assertEquals(2, call.getAudioBufferCount());
        assertArrayEquals(first, call.getAudioBuffer(0));
        assertArrayEquals(second, call.getAudioBuffer(1));
    }

    @Test
    void completedTargetlessStandardCallKeepsAllAudio()
    {
        ManagedPlayableAudioCall call = new ManagedPlayableAudioCall(snapshot(false, false, false));
        float[] first = new float[] {0.1f, 0.2f};
        float[] second = new float[] {0.3f, 0.4f};
        call.appendAudio(first);
        call.appendAudio(second);

        assertFalse(AudioPlaybackManager.isReadyForPlayback(call));

        call.updateSnapshot(snapshot(false, true, false));

        assertTrue(AudioPlaybackManager.isReadyForPlayback(call));
        assertEquals(2, call.getAudioBufferCount());
        assertArrayEquals(first, call.getAudioBuffer(0));
        assertArrayEquals(second, call.getAudioBuffer(1));
    }

    @Test
    void standardDmrDirectCallUsesBoundedOpeningBuffer()
    {
        ManagedPlayableAudioCall call = new ManagedPlayableAudioCall(dmrDirectSnapshot(false));

        for(int x = 0; x < AudioPlaybackManager.PLAYBACK_POLICY_BUFFER_LIMIT - 1; x++)
        {
            call.appendAudio(new float[] {x});
        }

        assertFalse(AudioPlaybackManager.isReadyForPlayback(call));
        call.appendAudio(new float[] {AudioPlaybackManager.PLAYBACK_POLICY_BUFFER_LIMIT - 1});
        assertTrue(AudioPlaybackManager.isReadyForPlayback(call));
    }

    @Test
    void targetlessTrafficFragmentNeverBecomesReady()
    {
        ManagedPlayableAudioCall call = new ManagedPlayableAudioCall(trafficSnapshot(true));

        for(int x = 0; x < AudioPlaybackManager.PLAYBACK_POLICY_BUFFER_LIMIT; x++)
        {
            call.appendAudio(new float[] {x});
        }

        assertTrue(AudioPlaybackManager.isTrafficCall(call));
        assertFalse(AudioPlaybackManager.isReadyForPlayback(call));
    }

    @Test
    void logicalChannelNameAloneDoesNotMakeStandardP25CallTraffic()
    {
        ManagedPlayableAudioCall call = new ManagedPlayableAudioCall(logicalChannelSnapshot(true));
        call.appendAudio(new float[]{0.1f});

        assertFalse(AudioPlaybackManager.isTrafficCall(call));
        assertTrue(AudioPlaybackManager.isReadyForPlayback(call));
    }

    private static AudioCallSnapshot snapshot(boolean withDestination, boolean complete, boolean duplicate)
    {
        List<Identifier> identifiers = new ArrayList<>();
        identifiers.add(SystemConfigurationIdentifier.create("Test System"));

        if(withDestination)
        {
            identifiers.add(APCO25Talkgroup.create(1200));
        }

        return new AudioCallSnapshot(new AudioCallId(1, 1, 0), null, null,
            new IdentifierCollection(identifiers), Set.of(), 1_000L, 2_000L, 1, 1,
            1_000L, 2_000L, false, complete, false, true, 100, duplicate);
    }

    private static AudioCallSnapshot dmrDirectSnapshot(boolean complete)
    {
        List<Identifier> identifiers = List.of(SystemConfigurationIdentifier.create("Test System"),
            ChannelNameConfigurationIdentifier.create("DMR Direct"),
            DecoderTypeConfigurationIdentifier.create(DecoderType.DMR));
        return new AudioCallSnapshot(new AudioCallId(2, 1, 1), null, null,
            new IdentifierCollection(identifiers), Set.of(), 1_000L, 2_000L, 1, 1,
            1_000L, 2_000L, false, complete, false, true, 100, false);
    }

    private static AudioCallSnapshot trafficSnapshot(boolean complete)
    {
        List<Identifier> identifiers = List.of(SystemConfigurationIdentifier.create("Test System"),
            DecoderLogicalChannelNameIdentifier.create("0-737", Protocol.APCO25),
            TrafficChannelIdentifier.create());
        return new AudioCallSnapshot(new AudioCallId(3, 1, 0), null, null,
            new IdentifierCollection(identifiers), Set.of(), 1_000L, 2_000L, 1, 1,
            1_000L, 2_000L, false, complete, false, true, 100, false);
    }


    private static AudioCallSnapshot logicalChannelSnapshot(boolean complete)
    {
        List<Identifier> identifiers = List.of(SystemConfigurationIdentifier.create("Test System"),
            DecoderLogicalChannelNameIdentifier.create("0-737", Protocol.APCO25));
        return new AudioCallSnapshot(new AudioCallId(4, 1, 0), null, null,
            new IdentifierCollection(identifiers), Set.of(), 1_000L, 2_000L, 1, 1,
            1_000L, 2_000L, false, complete, false, true, 100, false);
    }
}
