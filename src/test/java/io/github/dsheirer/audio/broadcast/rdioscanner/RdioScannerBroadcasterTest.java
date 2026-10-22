/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast.rdioscanner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.dsheirer.audio.broadcast.TalkerAliasOwnershipFixtures.recording;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.audio.broadcast.TalkerAliasOwnershipFixtures;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.protocol.Protocol;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
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
    void canonicalRadioNameCannotBeAttachedToAnUnknownNumericSource()
    {
        var sample = TalkerAliasOwnershipFixtures.canonicalWithoutNumericSource();
        assertEquals("", RdioScannerBroadcaster.getTalkerAlias(recording(sample.source(), sample.owned())));
    }

    @Test
    void uploadAliasRequiresTheCurrentRadioOwnerAcrossProtocols()
    {
        for(var sample: TalkerAliasOwnershipFixtures.samples())
        {
            assertEquals(sample.raw().getValue(),
                RdioScannerBroadcaster.getTalkerAlias(recording(sample.source(), sample.owned())));
            assertEquals("", RdioScannerBroadcaster.getTalkerAlias(recording(sample.other(), sample.owned())));
            assertEquals("", RdioScannerBroadcaster.getTalkerAlias(recording(sample.source(), sample.raw())));
            assertEquals("", RdioScannerBroadcaster.getTalkerAlias(recording(sample.owned())));
        }
    }

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

    @Test
    void uploadsNxdnZeroWithConfiguredLabelAndOptionalStreamAsOverride() throws Exception
    {
        verifyNxdnZeroUpload(null);
        verifyNxdnZeroUpload(700);
    }

    private void verifyNxdnZeroUpload(Integer streamAs) throws Exception
    {
        AliasListDefinition definition = new AliasListDefinition("NXDN Upload", AliasListFamily.NXDN);
        definition.setId(7);
        Alias group = new Alias("Conventional Dispatch");
        group.setId(1);
        group.setAliasListDefinition(definition);
        group.setGroup("Operations");
        group.setMatchIdentifier(new Talkgroup(Protocol.NXDN, 0));
        if(streamAs != null) group.setStreamTalkgroupAlias(new StreamAsTalkgroup(streamAs));
        Alias radio = new Alias("Configured Radio");
        radio.setId(2);
        radio.setAliasListDefinition(definition);
        radio.setMatchIdentifier(new Radio(Protocol.NXDN, 123));
        AliasModel model = new AliasModel();
        model.replaceCommittedConfiguration(List.of(definition), List.of(group, radio));
        var source = NXDNRadioIdentifier.createFrom(123);
        var destination = NXDNTalkgroupIdentifier.createTo(0);
        IdentifierCollection identifiers = new IdentifierCollection(List.of(source, destination,
            new NXDNTalkerAliasIdentifier("Portable 123").bindTo(source),
            AliasListConfigurationIdentifier.create(definition.getName()),
            SystemConfigurationIdentifier.create("NXDN Conventional"),
            FrequencyConfigurationIdentifier.create(451_000_000L)));
        AtomicReference<Map<String,String>> uploaded = new AtomicReference<>();
        CountDownLatch uploadReceived = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/call-upload", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            String response = "incomplete call data: no talkgroup";
            if(!body.contains("name=\"test\""))
            {
                uploaded.set(multipartFields(body, exchange.getRequestHeaders().getFirst("Content-Type")));
                uploadReceived.countDown();
                response = "Call imported successfully.";
            }
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try(OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
        });
        server.start();
        RdioScannerConfiguration configuration = new RdioScannerConfiguration();
        configuration.setHost("http://127.0.0.1:" + server.getAddress().getPort() + "/api/call-upload");
        configuration.setApiKey("synthetic-upload-key");
        configuration.setSystemID(1);
        RdioScannerBroadcaster broadcaster = new RdioScannerBroadcaster(configuration, null, null, model);
        broadcaster.setListener(event -> {
            if(event.getEvent() == BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE) completed.countDown();
        });
        try
        {
            broadcaster.start();
            Path path = mTemporaryFolder.resolve("nxdn_call_123_" + (streamAs != null ? streamAs : 0) + ".mp3");
            Files.writeString(path, "synthetic audio", StandardCharsets.US_ASCII);
            long startTime = System.currentTimeMillis();
            AudioRecording recording = new AudioRecording(path, List.of(), identifiers, startTime, 1_000);
            recording.addPendingReplay();
            broadcaster.receive(recording);
            assertTrue(uploadReceived.await(5, TimeUnit.SECONDS));
            assertTrue(completed.await(5, TimeUnit.SECONDS));
            Map<String,String> fields = uploaded.get();
            assertEquals(Set.of("key", "system", "dateTime", "talkgroup", "source", "frequency", "talkerAlias",
                "talkgroupLabel", "talkgroupGroup", "systemLabel", "patches", "audio"), fields.keySet());
            assertEquals("synthetic-upload-key", fields.get("key"));
            assertEquals("1", fields.get("system"));
            assertEquals(Long.toString(startTime / 1_000), fields.get("dateTime"));
            assertEquals(streamAs != null ? streamAs.toString() : "0", fields.get("talkgroup"));
            assertEquals("123", fields.get("source"));
            assertEquals("451000000", fields.get("frequency"));
            assertEquals("Portable 123", fields.get("talkerAlias"));
            assertEquals("Conventional Dispatch", fields.get("talkgroupLabel"));
            assertEquals("Operations", fields.get("talkgroupGroup"));
            assertEquals("NXDN Conventional", fields.get("systemLabel"));
            assertEquals("[]", fields.get("patches"));
            assertEquals("synthetic audio", fields.get("audio"));
            assertEquals(0, destination.getValue());
            assertEquals(1, broadcaster.getStreamedAudioCount());
        }
        finally
        {
            broadcaster.stop();
            server.stop(0);
        }
    }

    private static Map<String,String> multipartFields(String body, String contentType)
    {
        String boundary = contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
        Map<String,String> fields = new LinkedHashMap<>();
        Pattern name = Pattern.compile("(?:^|; )name=\"([^\"]+)\"");
        for(String part: body.split(Pattern.quote("--" + boundary)))
        {
            int separator = part.indexOf("\r\n\r\n");
            if(separator < 0) continue;
            var matcher = name.matcher(part.substring(0, separator));
            assertTrue(matcher.find(), "Every uploaded part retains its existing field name");
            String value = part.substring(separator + 4);
            assertTrue(value.endsWith("\r\n"));
            assertTrue(fields.putIfAbsent(matcher.group(1), value.substring(0, value.length() - 2)) == null,
                "Each metadata field must occur once");
        }
        return fields;
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
