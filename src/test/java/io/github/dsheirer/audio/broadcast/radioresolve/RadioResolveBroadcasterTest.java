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

package io.github.dsheirer.audio.broadcast.radioresolve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadioResolveBroadcasterTest
{
    @Test
    void modesIndependentlyControlCallsAndMetadata()
    {
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();

        configuration.setMode(RadioResolveConfiguration.Mode.CALLS_AND_METADATA);
        assertTrue(configuration.isCallUploadEnabled());
        assertTrue(configuration.isSiteMetadataEnabled());

        configuration.setMode(RadioResolveConfiguration.Mode.CALLS_ONLY);
        assertTrue(configuration.isCallUploadEnabled());
        assertFalse(configuration.isSiteMetadataEnabled());

        configuration.setMode(RadioResolveConfiguration.Mode.METADATA_ONLY);
        assertFalse(configuration.isCallUploadEnabled());
        assertTrue(configuration.isSiteMetadataEnabled());
    }

    @Test
    void callsOnlyQueuesCompletedCalls(@TempDir Path temporaryDirectory)
    {
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setMode(RadioResolveConfiguration.Mode.CALLS_ONLY);
        RadioResolveBroadcaster broadcaster = new RadioResolveBroadcaster(configuration, null, null, null);
        AudioRecording audioRecording = recording(temporaryDirectory.resolve("call.mp3"));
        audioRecording.addPendingReplay();

        broadcaster.receive(audioRecording);

        assertEquals(1, broadcaster.getAudioQueueSize());
        assertTrue(audioRecording.hasPendingReplays());
        broadcaster.dispose();
        assertFalse(audioRecording.hasPendingReplays());
    }

    @Test
    void metadataOnlyRejectsCompletedCalls(@TempDir Path temporaryDirectory)
    {
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setMode(RadioResolveConfiguration.Mode.METADATA_ONLY);
        RadioResolveBroadcaster broadcaster = new RadioResolveBroadcaster(configuration, null, null, null);
        AudioRecording audioRecording = recording(temporaryDirectory.resolve("call.mp3"));
        audioRecording.addPendingReplay();

        broadcaster.receive(audioRecording);

        assertEquals(0, broadcaster.getAudioQueueSize());
        assertFalse(audioRecording.hasPendingReplays());
    }

    @Test
    void callsOnlySkipsSiteMetadataBeforeConnectionWork()
    {
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setMode(RadioResolveConfiguration.Mode.CALLS_ONLY);
        RadioResolveBroadcaster broadcaster = new RadioResolveBroadcaster(configuration, null, null, null);
        Channel channel = new Channel("Control");
        channel.setRadresGuid("site-guid");

        broadcaster.receiveSiteMetadata(new SiteMetadataEvent(channel, completeSiteSnapshot(),
            System.currentTimeMillis()));

        assertEquals(BroadcastState.READY, broadcaster.getBroadcastState());
    }

    @Test
    void callUploadRequestHasBoundedOverallTimeout(@TempDir Path temporaryDirectory) throws Exception
    {
        Path audioFile = temporaryDirectory.resolve("call.mp3");
        Files.write(audioFile, new byte[] {0x49, 0x44, 0x33});
        AudioRecording audioRecording = new AudioRecording(audioFile, List.of(), new IdentifierCollection(),
            System.currentTimeMillis(), 1000);
        RadioResolveConfiguration configuration = new RadioResolveConfiguration();
        configuration.setHost("https://calls.example.com");
        configuration.setApiKey("test-key");

        HttpRequest request = RadioResolveBroadcaster.createUploadRequest(configuration, audioRecording, null);

        assertEquals(Optional.of(Duration.ofSeconds(30)), request.timeout());
    }

    private static AudioRecording recording(Path path)
    {
        return new AudioRecording(path, List.of(), new IdentifierCollection(), System.currentTimeMillis(), 1000);
    }

    private static P25NetworkConfigurationSnapshot completeSiteSnapshot()
    {
        return new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x348, 0x348, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(0x348, 0x348, 2, 1, null, true),
            List.of(new P25NetworkConfigurationSnapshot.Channel("primary_control", "0-493", 854_087_500L,
                809_087_500L, false, 1)), List.of(),
            List.of(new P25NetworkConfigurationSnapshot.FrequencyBand(0, false, 851_006_250L, 12_500, 6_250L,
                -45_000_000L, 1)), List.of(), List.of());
    }
}
