/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast.rdioscanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.identifier.IdentifierCollection;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RdioScannerBroadcasterTest
{
    private static final String SDRTRUNK_USER_AGENT = "sdrtrunk";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void usesCompatibleUserAgentForConnectionTestAndCallUpload() throws Exception
    {
        AtomicReference<String> connectionTestUserAgent = new AtomicReference<>();
        AtomicReference<String> callUploadUserAgent = new AtomicReference<>();
        CountDownLatch callUploadReceived = new CountDownLatch(1);
        CountDownLatch streamedAudioCountChanged = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/call-upload", exchange -> handleRequest(exchange, connectionTestUserAgent,
            callUploadUserAgent, callUploadReceived));
        server.start();

        RdioScannerConfiguration configuration = new RdioScannerConfiguration();
        configuration.setHost("http://127.0.0.1:" + server.getAddress().getPort() + "/api/call-upload");
        configuration.setApiKey("test-api-key");
        configuration.setSystemID(1);
        RdioScannerBroadcaster broadcaster = new RdioScannerBroadcaster(configuration, null, null, new AliasModel());
        broadcaster.setListener(event -> {
            if(event.getEvent() == BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE)
            {
                streamedAudioCountChanged.countDown();
            }
        });

        try
        {
            broadcaster.start();
            assertEquals(BroadcastState.CONNECTED, broadcaster.getBroadcastState());
            assertEquals(SDRTRUNK_USER_AGENT, connectionTestUserAgent.get());

            Path recordingPath = mTemporaryFolder.resolve("rdio_call_123_456.mp3");
            Files.writeString(recordingPath, "test audio", StandardCharsets.UTF_8);
            AudioRecording recording = new AudioRecording(recordingPath, List.of(), new IdentifierCollection(),
                System.currentTimeMillis(), 1_000);
            recording.addPendingReplay();
            broadcaster.receive(recording);

            assertTrue(callUploadReceived.await(5, TimeUnit.SECONDS), "Rdio Scanner call upload was not received");
            assertTrue(streamedAudioCountChanged.await(5, TimeUnit.SECONDS),
                "Rdio Scanner call upload did not complete");
            assertEquals(1, broadcaster.getStreamedAudioCount());
            assertEquals(SDRTRUNK_USER_AGENT, callUploadUserAgent.get());
        }
        finally
        {
            broadcaster.stop();
            server.stop(0);
        }
    }

    private static void handleRequest(HttpExchange exchange, AtomicReference<String> connectionTestUserAgent,
                                      AtomicReference<String> callUploadUserAgent,
                                      CountDownLatch callUploadReceived) throws IOException
    {
        String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
        String userAgent = exchange.getRequestHeaders().getFirst("User-Agent");
        String response;

        if(requestBody.contains("name=\"test\""))
        {
            connectionTestUserAgent.set(userAgent);
            response = SDRTRUNK_USER_AGENT.equals(userAgent) ? "incomplete call data: no talkgroup" :
                "incomplete call data: no audio file";
        }
        else
        {
            callUploadUserAgent.set(userAgent);
            callUploadReceived.countDown();
            response = "Call imported successfully.";
        }

        byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, responseBytes.length);
        try(OutputStream outputStream = exchange.getResponseBody())
        {
            outputStream.write(responseBytes);
        }
    }

}
