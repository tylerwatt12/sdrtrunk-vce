/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast.broadcastify;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.AudioRecording;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastState;
import io.github.dsheirer.audio.broadcast.TalkerAliasOwnershipFixtures;
import static io.github.dsheirer.audio.broadcast.TalkerAliasOwnershipFixtures.recording;
import io.github.dsheirer.audio.call.MutableAudioCallBuilder;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.alias.DmrTalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallFixture;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
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

class BroadcastifyCallBroadcasterTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void uploadsDecodedNxdnZeroThroughTheExistingTwoStageWireContract() throws Exception
    {
        verifyDecodedNxdnZeroUpload(null);
        verifyDecodedNxdnZeroUpload(700);
    }

    private void verifyDecodedNxdnZeroUpload(Integer streamAs) throws Exception
    {
        verifyDecodedNxdnZeroUpload(streamAs, false);
    }

    @Test
    void uploadsCapturedAliasIdentityAfterRenameAndNameReuse() throws Exception
    {
        verifyDecodedNxdnZeroUpload(700, true);
    }

    private void verifyDecodedNxdnZeroUpload(Integer streamAs, boolean renameList) throws Exception
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
        identifiers.silentUpdate(AliasListConfigurationIdentifier.create(definition.getId(), definition.getName()));
        identifiers.silentUpdate(FrequencyConfigurationIdentifier.create(461_125_000L));
        AtomicReference<Map<String,String>> submitted = new AtomicReference<>();
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        AtomicReference<String> uploadMethod = new AtomicReference<>();
        AtomicReference<String> uploadContentType = new AtomicReference<>();
        CountDownLatch streamed = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/calls", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            if(body.contains("name=\"test\"")) respond(exchange, "ok");
            else
            {
                submitted.set(multipartFields(body, exchange.getRequestHeaders().getFirst("Content-Type")));
                respond(exchange, "0 http://127.0.0.1:" + server.getAddress().getPort() + "/file");
            }
        });
        server.createContext("/file", exchange -> {
            uploadMethod.set(exchange.getRequestMethod());
            uploadContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            uploaded.set(exchange.getRequestBody().readAllBytes());
            respond(exchange, "ok");
        });
        server.start();
        BroadcastifyCallConfiguration configuration = new BroadcastifyCallConfiguration();
        configuration.setHost("http://127.0.0.1:" + server.getAddress().getPort() + "/calls");
        configuration.setApiKey("synthetic-upload-key");
        configuration.setSystemID(1);
        BroadcastifyCallBroadcaster broadcaster = new BroadcastifyCallBroadcaster(configuration, null, null, aliases);
        broadcaster.setListener(event -> {
            if(event.getEvent() == BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE) streamed.countDown();
        });
        try
        {
            broadcaster.start();
            assertEquals(BroadcastState.CONNECTED, broadcaster.getBroadcastState());
            byte[] audioBytes = "synthetic MP3 transport payload".getBytes(StandardCharsets.US_ASCII);
            Path path = mTemporaryFolder.resolve("nxdn_" + (streamAs != null ? streamAs : 0) + ".mp3");
            Files.write(path, audioBytes);
            long startedAt = System.currentTimeMillis();
            AudioRecording recording = new AudioRecording(path, List.of(), identifiers.copyOf(), startedAt,
                call.getDuration());
            if(renameList)
            {
                AliasListDefinition renamed = new AliasListDefinition("Renamed NXDN", AliasListFamily.NXDN);
                renamed.setId(definition.getId());
                AliasListDefinition reusedName = new AliasListDefinition(definition.getName(), AliasListFamily.NXDN);
                reusedName.setId(8);
                Alias wrongList = new Alias("Other list");
                wrongList.setId(2);
                wrongList.setAliasListDefinition(reusedName);
                wrongList.setMatchIdentifier(new Talkgroup(Protocol.NXDN, 0));
                wrongList.setStreamTalkgroupAlias(new StreamAsTalkgroup(999));
                aliases.publishCommittedConfiguration(List.of(renamed, reusedName), List.of(group, wrongList),
                    Set.of(wrongList.getId()), true);
            }
            recording.addPendingReplay();
            broadcaster.receive(recording);
            assertTrue(streamed.await(5, TimeUnit.SECONDS), "Both Broadcastify upload stages must complete");
            Map<String,String> fields = submitted.get();
            assertEquals(Set.of("apiKey", "systemId", "callDuration", "ts", "tg", "src", "freq", "enc"),
                fields.keySet(), "Existing provider field names stay unchanged and no alias field is invented");
            assertEquals(streamAs != null ? streamAs.toString() : "0", fields.get("tg"));
            assertEquals("19", fields.get("src"));
            assertEquals("synthetic-upload-key", fields.get("apiKey"));
            assertEquals("1", fields.get("systemId"));
            assertEquals("mp3", fields.get("enc"));
            assertEquals(Long.toString(startedAt / 1_000), fields.get("ts"));
            assertFalse(fields.toString().contains("NULL GROUP"));
            assertNull(BroadcastifyCallBroadcaster.getFromAlias(recording));
            assertEquals(0, call.snapshot().identifierCollection().getToIdentifier().getValue(),
                "Stream As changes the provider target, never the decoded talkgroup");
            assertEquals("PUT", uploadMethod.get());
            assertEquals("audio/mpeg", uploadContentType.get());
            assertArrayEquals(audioBytes, uploaded.get());
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
        assertNull(BroadcastifyCallBroadcaster.getFromAlias(recording(sample.source(), sample.owned())));
    }

    @Test
    void returnsOverTheAirTalkerAlias()
    {
        for(var sample: TalkerAliasOwnershipFixtures.samples())
        {
            assertEquals(sample.raw().getValue(),
                BroadcastifyCallBroadcaster.getFromAlias(recording(sample.source(), sample.owned())));
            assertNull(BroadcastifyCallBroadcaster.getFromAlias(recording(sample.other(), sample.owned())));
            assertNull(BroadcastifyCallBroadcaster.getFromAlias(recording(sample.source(), sample.raw())));
            assertNull(BroadcastifyCallBroadcaster.getFromAlias(recording(sample.owned())));
        }
        assertEquals("srcId_alias", FormField.RADIO_ID_ALIAS.getHeader());
    }

    @Test
    void omitsMissingOrInvalidTalkerAlias()
    {
        AudioRecording missing = new AudioRecording(null, List.of(), new IdentifierCollection(), 0, 0);
        AudioRecording invalid = new AudioRecording(null, List.of(),
            new IdentifierCollection(List.of(DmrTalkerAliasIdentifier.create(""))), 0, 0);

        assertNull(BroadcastifyCallBroadcaster.getFromAlias(missing));
        assertNull(BroadcastifyCallBroadcaster.getFromAlias(invalid));
    }

    @Test
    void sourceChangeOmitsPreviousRadioAliasFromUploadMetadata()
    {
        MutableAudioCallBuilder builder = new MutableAudioCallBuilder(AliasList.empty("test"), 1);
        builder.addIdentifier(APCO25RadioIdentifier.createFrom(120001));
        builder.addIdentifier(P25TalkerAliasIdentifier.create("UNIT ONE")
            .bindTo(APCO25RadioIdentifier.createFrom(120001)));
        builder.addIdentifier(APCO25RadioIdentifier.createFrom(120002));
        AudioRecording recording = new AudioRecording(null, List.of(),
            builder.getIdentifierCollection(), 0, 0);

        assertEquals(120002, recording.getIdentifierCollection().getFromIdentifier().getValue());
        assertNull(BroadcastifyCallBroadcaster.getFromAlias(recording));
    }
}
