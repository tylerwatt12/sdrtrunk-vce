/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.record.managed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingTranscriptionServiceTest
{
    @TempDir
    Path temporary;

    @Test
    void backfillsEligibleCallsAndLeavesShortCallsAlone() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/v1/audio/transcriptions", exchange ->
        {
            byte[] request = exchange.getRequestBody().readAllBytes();
            assertTrue(new String(request, StandardCharsets.UTF_8).contains("test-model"));
            requests.incrementAndGet();
            byte[] response = "{\"text\":\"A clear radio call\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try(var body = exchange.getResponseBody())
            {
                body.write(response);
            }
        });
        server.start();

        Path root = temporary.resolve("audio");
        Path database = temporary.resolve("database/managed-recordings.sqlite");
        try(ManagedRecordingCatalog catalog = new ManagedRecordingCatalog(database, root))
        {
            insertCall(database, root, 1L, 400L);
            insertCall(database, root, 2L, 800L);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/audio/transcriptions";
            var settings = new ManagedRecordingTranscriptionService.Settings(true, url, "test-model", "", 500L);
            try(ManagedRecordingTranscriptionService service = new ManagedRecordingTranscriptionService(catalog,
                () -> settings, HttpClient.newHttpClient(), Duration.ofMillis(30), Duration.ofMillis(50),
                () -> false))
            {
                service.start();
                awaitStatus(catalog, 2L, "complete");
                assertEquals("A clear radio call", catalog.transcription(2L).text());
                assertEquals("pending", catalog.transcription(1L).status());
                assertEquals(1, requests.get());
            }
        }
        finally
        {
            server.stop(0);
        }
    }

    @Test
    void slowServerDoesNotHoldCatalogWriteLockAndEmptySpeechCompletes() throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/v1/audio/transcriptions", exchange ->
        {
            exchange.getRequestBody().readAllBytes();
            started.countDown();
            try
            {
                release.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            byte[] response = "{\"text\":\"\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            try(var body = exchange.getResponseBody())
            {
                body.write(response);
            }
        });
        server.start();

        Path root = temporary.resolve("audio");
        Path database = temporary.resolve("database/managed-recordings.sqlite");
        try(ManagedRecordingCatalog catalog = new ManagedRecordingCatalog(database, root))
        {
            insertCall(database, root, 1L, 800L);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/audio/transcriptions";
            var settings = new ManagedRecordingTranscriptionService.Settings(true, url, "test-model", "", 500L);
            try(ManagedRecordingTranscriptionService service = new ManagedRecordingTranscriptionService(catalog,
                () -> settings, HttpClient.newHttpClient(), Duration.ofMillis(30), Duration.ofMillis(50),
                () -> false))
            {
                service.start();
                assertTrue(started.await(5, TimeUnit.SECONDS));
                // This write must remain possible while the ASR server is holding its response.
                insertCall(database, root, 2L, 300L);
                assertEquals("pending", catalog.transcription(2L).status());
                release.countDown();
                awaitStatus(catalog, 1L, "complete");
                assertEquals("", catalog.transcription(1L).text());
            }
        }
        finally
        {
            release.countDown();
            server.stop(0);
        }
    }

    private static void insertCall(Path database, Path root, long id, long durationMs) throws Exception
    {
        Files.createDirectories(root);
        String name = id + ".mp3";
        byte[] audio = new byte[]{1, 2, 3, 4};
        Files.write(root.resolve(name), audio);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.prepareStatement(
                "INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes," +
                    "protocol,call_type,voice_type) VALUES(?,?,?,?,?,?,?,?,?)"))
        {
            statement.setLong(1, id);
            statement.setLong(2, id * 1_000L);
            statement.setLong(3, id * 1_000L + durationMs);
            statement.setLong(4, durationMs);
            statement.setString(5, name);
            statement.setLong(6, audio.length);
            statement.setInt(7, 0);
            statement.setInt(8, 0);
            statement.setInt(9, 1);
            statement.executeUpdate();
        }
    }

    private static void awaitStatus(ManagedRecordingCatalog catalog, long id, String status) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while(System.nanoTime() < deadline)
        {
            ManagedRecordingCatalog.Transcript transcript = catalog.transcription(id);
            if(transcript != null && status.equals(transcript.status()))
            {
                return;
            }
            Thread.sleep(20L);
        }
        assertEquals(status, catalog.transcription(id).status());
    }
}
