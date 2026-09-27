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
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.source.tuner.recording.RecordingTunerFileCatalog;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecordingTunerHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void rescansThenCreatesFromAnOpaqueIdWithoutExposingAPath() throws Exception
    {
        Path folder = mTemporaryDirectory.resolve("recording_tuners");
        Files.createDirectory(folder);
        Path recording = folder.resolve("recording.wav");
        createWave(recording);
        AtomicReference<Path> registered = new AtomicReference<>();
        AtomicReference<Long> frequency = new AtomicReference<>();
        RecordingTunerHttpController controller = new RecordingTunerHttpController(
            new RecordingTunerFileCatalog(folder), (path, hz) -> {
                registered.set(path);
                frequency.set(hz);
            });
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(RecordingTunerHttpController.PATH, controller::handle);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> initial = send(client, origin, RecordingTunerHttpController.PATH,
                "GET", null);
            assertEquals(200, initial.statusCode());
            assertEquals(1, MAPPER.readTree(initial.body()).at("/data/entries").size());
            assertFalse(initial.body().contains(mTemporaryDirectory.toString()));

            HttpResponse<String> scanned = send(client, origin, RecordingTunerHttpController.RESCAN_PATH,
                "POST", null);
            assertEquals(200, scanned.statusCode());
            assertFalse(scanned.body().contains(mTemporaryDirectory.toString()));
            JsonNode entry = MAPPER.readTree(scanned.body()).at("/data/entries/0");
            assertEquals("recording.wav", entry.path("name").textValue());
            assertEquals(2_400_000, entry.path("sample_rate_hz").intValue());
            String id = entry.path("id").textValue();
            assertEquals(id, MAPPER.readTree(initial.body()).at("/data/entries/0/id").textValue());

            HttpResponse<String> added = send(client, origin, RecordingTunerHttpController.PATH,
                "POST", "{\"file_id\":\"" + id + "\",\"center_frequency_hz\":851012500}");
            assertEquals(201, added.statusCode(), added.body());
            assertFalse(added.body().contains(mTemporaryDirectory.toString()));
            assertEquals(recording, registered.get());
            assertEquals(851_012_500L, frequency.get());

            HttpResponse<String> arbitraryPath = send(client, origin, RecordingTunerHttpController.PATH,
                "POST", "{\"file_id\":\"../../outside.wav\",\"center_frequency_hz\":851012500}");
            assertEquals(404, arbitraryPath.statusCode());
            assertEquals(422, send(client, origin, RecordingTunerHttpController.PATH,
                "POST", "{\"file_id\":\"" + id + "\",\"center_frequency_hz\":0}").statusCode());

            Files.delete(recording);
            assertEquals(409, send(client, origin, RecordingTunerHttpController.PATH,
                "POST", "{\"file_id\":\"" + id + "\",\"center_frequency_hz\":851012500}").statusCode());
        }
        finally
        {
            server.stop(0);
        }
    }

    private static HttpResponse<String> send(HttpClient client, URI origin, String path, String method, String body)
        throws Exception
    {
        HttpRequest.Builder request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(10));
        if(body != null)
        {
            request.header("Content-Type", "application/json");
        }
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
            HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void createWave(Path path) throws Exception
    {
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(40);
        header.put("WAVE".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.put("fmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(16);
        header.putShort((short)1);
        header.putShort((short)2);
        header.putInt(2_400_000);
        header.putInt(9_600_000);
        header.putShort((short)4);
        header.putShort((short)16);
        header.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        header.putInt(4);
        header.flip();
        try(FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE))
        {
            while(header.hasRemaining())
            {
                channel.write(header);
            }
            channel.write(ByteBuffer.allocate(4));
        }
    }
}
