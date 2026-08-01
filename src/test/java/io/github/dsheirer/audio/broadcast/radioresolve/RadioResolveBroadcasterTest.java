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

import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.identifier.IdentifierCollection;
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
}
