/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.broadcast.openmhz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.github.dsheirer.audio.broadcast.TalkerAliasOwnershipFixtures.recording;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.audio.broadcast.TalkerAliasOwnershipFixtures;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallFixture;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenMHzBroadcasterTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void uploadsDecodedNxdnZeroThroughTheExistingMultipartWireContract() throws Exception
    {
        verifyDecodedNxdnZeroUpload(null);
        verifyDecodedNxdnZeroUpload(700);
    }

    private void verifyDecodedNxdnZeroUpload(Integer streamAs) throws Exception
    {
        AliasListDefinition definition = new AliasListDefinition("NXDN wire fixture", AliasListFamily.NXDN);
        definition.setId(7);
        Alias group = new Alias("Security");
        group.setId(1);
        group.setAliasListDefinition(definition);
        group.setMatchIdentifier(new Talkgroup(Protocol.NXDN, 0));
        if(streamAs != null) group.setStreamTalkgroupAlias(new StreamAsTalkgroup(streamAs));
        AliasModel aliases = new AliasModel();
        aliases.replaceCommittedConfiguration(List.of(definition), List.of(group));
        var call = NXDNConventionalCallFixture.decodeGroupCall(aliases.getAliasList(definition.getName()), 19, 0)
            .audio();
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection(
            call.snapshot().identifierCollection().getIdentifiers());
        identifiers.silentUpdate(AliasListConfigurationIdentifier.create(definition.getName()));
        identifiers.silentUpdate(FrequencyConfigurationIdentifier.create(461_125_000L));
        AtomicReference<Map<String,String>> uploaded = new AtomicReference<>();
        AtomicReference<String> uploadMethod = new AtomicReference<>();
        AtomicReference<String> authorizationBody = new AtomicReference<>();
        CountDownLatch streamed = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixture/authorize", exchange -> {
            authorizationBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, "ok");
        });
        server.createContext("/fixture/upload", exchange -> {
            uploadMethod.set(exchange.getRequestMethod());
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            uploaded.set(multipartFields(body, exchange.getRequestHeaders().getFirst("Content-Type")));
            respond(exchange, "ok");
        });
        server.start();
        OpenMHzConfiguration configuration = new OpenMHzConfiguration();
        configuration.setHost("http://127.0.0.1:" + server.getAddress().getPort());
        configuration.setSystemName("fixture");
        configuration.setApiKey("synthetic-upload-key");
        OpenMHzBroadcaster broadcaster = new OpenMHzBroadcaster(configuration, null, null, aliases);
        broadcaster.setListener(event -> {
            if(event.getEvent() == BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE) streamed.countDown();
        });
        try
        {
            broadcaster.start();
            assertEquals(BroadcastState.CONNECTED, broadcaster.getBroadcastState());
            Path path = mTemporaryFolder.resolve("nxdn_" + (streamAs != null ? streamAs : 0) + ".mp3");
            Files.writeString(path, "synthetic MP3 transport payload", StandardCharsets.US_ASCII);
            long startedAt = System.currentTimeMillis();
            AudioRecording recording = new AudioRecording(path, List.of(), identifiers.copyOf(), startedAt,
                call.getDuration());
            recording.addPendingReplay();
            broadcaster.receive(recording);
            assertTrue(streamed.await(5, TimeUnit.SECONDS), "The OpenMHz multipart upload must complete");
            Map<String,String> fields = uploaded.get();
            assertEquals(Set.of("freq", "start_time", "stop_time", "call_length", "talkgroup_num", "emergency",
                "api_key", "patch_list", "talker_alias", "source_list", "call"), fields.keySet(),
                "Existing provider field names stay unchanged without adding configured-name fields");
            assertEquals(streamAs != null ? streamAs.toString() : "0", fields.get("talkgroup_num"));
            assertEquals("[{ \"pos\": 0.00, \"src\": 19}]", fields.get("source_list"));
            assertEquals("", fields.get("talker_alias"), "A call with no decoded OTA name keeps the existing empty field");
            assertEquals("synthetic-upload-key", fields.get("api_key"));
            assertEquals("461125000", fields.get("freq"));
            assertEquals(Long.toString(startedAt / 1_000), fields.get("start_time"));
            assertEquals("[]", fields.get("patch_list"));
            assertEquals("synthetic MP3 transport payload", fields.get("call"));
            assertFalse(fields.toString().contains("NULL GROUP"));
            assertEquals("api_key=synthetic-upload-key", authorizationBody.get());
            assertEquals("POST", uploadMethod.get());
            assertEquals(0, call.snapshot().identifierCollection().getToIdentifier().getValue(),
                "Stream As changes only the provider target and retains decoded talkgroup zero");
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
            assertTrue(matcher.find());
            String value = part.substring(separator + 4);
            assertTrue(value.endsWith("\r\n"));
            assertNull(fields.putIfAbsent(matcher.group(1), value.substring(0, value.length() - 2)));
        }
        return fields;
    }

    private static void respond(HttpExchange exchange, String response) throws IOException
    {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try(OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
    }

    @Test
    void canonicalRadioNameCannotBeAttachedToAnUnknownNumericSource()
    {
        var sample = TalkerAliasOwnershipFixtures.canonicalWithoutNumericSource();
        assertEquals("", OpenMHzBroadcaster.getTalkerAlias(recording(sample.source(), sample.owned())));
    }

    @Test
    void uploadAliasRequiresTheCurrentRadioOwnerAcrossProtocols()
    {
        for(var sample: TalkerAliasOwnershipFixtures.samples())
        {
            assertEquals(sample.raw().getValue(),
                OpenMHzBroadcaster.getTalkerAlias(recording(sample.source(), sample.owned())));
            assertEquals("", OpenMHzBroadcaster.getTalkerAlias(recording(sample.other(), sample.owned())));
            assertEquals("", OpenMHzBroadcaster.getTalkerAlias(recording(sample.source(), sample.raw())));
            assertEquals("", OpenMHzBroadcaster.getTalkerAlias(recording(sample.owned())));
        }
    }
}
