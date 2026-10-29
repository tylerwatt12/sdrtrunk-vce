/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.broadcast;

import static io.github.dsheirer.test.BroadcastRouteTestSupport.route;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mpatric.mp3agic.Mp3File;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerBroadcaster;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerConfiguration;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.nxdn.NXDNConventionalCallFixture;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.record.AudioCallRecorder;
import io.github.dsheirer.record.RecordFormat;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Uses the actual streaming encoder and Rdio request builder with synthetic audio and a loopback receiver. */
class RdioStreamAsWorkaroundTest
{
    private static final int MAPPED_TALKGROUP = 900001;
    /** Optional local evidence capture. The normal regression test writes only to its disposable directory. */
    private static final String EVIDENCE_PROPERTY = "sdrtrunk.test.streamAsEvidence";
    private static final Set<String> FIELD_NAMES = Set.of("key", "system", "dateTime", "talkgroup", "source",
        "frequency", "talkerAlias", "talkgroupLabel", "talkgroupGroup", "systemLabel", "patches", "audio");

    @TempDir
    Path mTemporaryFolder;

    @Test
    void configuredStreamAsMapsConventionalZeroWithoutChangingItsDecodedIdOrRecordingTags() throws Exception
    {
        verify(false, false);
        verify(false, true);
    }

    @Test
    void theSameConfiguredMappingAlsoAppliesToTrunkedTypeD() throws Exception
    {
        verify(true, false);
        verify(true, true);
    }

    private void verify(boolean typeD, boolean mapped) throws Exception
    {
        assertTrue(Preferences.userRoot().getClass().getName().contains("SqlitePreferencesFactory"),
            "The launcher must install isolated SQLite test preferences before this test changes output choices");
        String caseName = (typeD ? "trunked-type-d" : "conventional-zero") + (mapped ? "-mapped" : "-unmapped");
        int decodedTarget = typeD ? 2049 : 0;
        String aliasName = typeD ? "LAB Type-D" : "LAB 0";
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        CountDownLatch requestReceived = new CountDownLatch(1);
        CountDownLatch uploadCompleted = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/call-upload", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            if(new String(body, StandardCharsets.ISO_8859_1).contains("name=\"test\""))
                respond(exchange, "incomplete call data: no talkgroup");
            else
            {
                Map<String,String> headers = Map.of("Content-Type", contentType,
                    "User-Agent", exchange.getRequestHeaders().getFirst("User-Agent"));
                captured.set(new CapturedRequest(exchange.getRequestMethod(), headers, body,
                    multipartFields(body, contentType)));
                requestReceived.countDown();
                // This local response confirms the producer's completion handling, not an external server's rules.
                respond(exchange, "Call imported successfully.");
            }
        });
        server.start();
        RdioScannerConfiguration configuration = new RdioScannerConfiguration();
        configuration.setName("Synthetic Rdio destination");
        configuration.setHost("http://127.0.0.1:" + server.getAddress().getPort() + "/api/call-upload");
        configuration.setApiKey("synthetic-workaround-key");
        configuration.setSystemID(1);
        AliasListDefinition definition = new AliasListDefinition("Synthetic NXDN", AliasListFamily.NXDN);
        definition.setId(41);
        Alias alias = new Alias(aliasName);
        alias.setId(1);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.NXDN, decodedTarget));
        alias.addBroadcastChannel(route(configuration));
        if(mapped) alias.setStreamTalkgroupAlias(new StreamAsTalkgroup(MAPPED_TALKGROUP));
        AliasModel aliases = new AliasModel();
        aliases.replaceCommittedConfiguration(List.of(definition), List.of(alias));
        AliasList aliasList = aliases.getAliasList(definition.getName());
        CompletedAudioCall call = typeD ? typeDCall(aliasList, route(configuration)) : conventionalCall(aliasList);
        assertEquals(typeD ? ChannelConfigurationPolicy.ChannelKind.TRUNKED :
            ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL, call.snapshot().callLegSource().channelKind());
        RdioScannerBroadcaster broadcaster = new RdioScannerBroadcaster(configuration, null, null, aliases);
        broadcaster.setListener(event -> {
            if(event.getEvent() == BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE) uploadCompleted.countDown();
        });
        UserPreferences preferences = new UserPreferences();
        Path originalStreamingDirectory = preferences.getDirectoryPreference().getDirectoryStreaming();
        Path originalJmbe = preferences.getJmbeLibraryPreference().getPathJmbeLibrary();
        Path streamingDirectory = mTemporaryFolder.resolve(caseName);
        Files.createDirectory(streamingDirectory);
        AtomicReference<AudioRecording> encoded = new AtomicReference<>();
        AtomicReference<byte[]> encodedBytes = new AtomicReference<>();
        AudioStreamingManager streaming = new AudioStreamingManager(recording -> {
            encoded.set(recording);
            try { encodedBytes.set(Files.readAllBytes(recording.getPath())); }
            catch(IOException failure) { throw new UncheckedIOException(failure); }
            recording.addPendingReplay();
            broadcaster.receive(recording);
        }, BroadcastFormat.MP3, preferences);
        try
        {
            preferences.getDirectoryPreference().setDirectoryStreaming(streamingDirectory);
            preferences.getJmbeLibraryPreference().resetPathJmbeLibrary();
            //A chosen test path isolates local recording tags; Basic filename generation has its own contract tests.
            Path localPath = streamingDirectory.resolve("local-recording.mp3");
            AudioCallRecorder.write(call, localPath, RecordFormat.MP3, preferences);
            byte[] localBytes = Files.readAllBytes(localPath);
            Mp3File localMp3 = new Mp3File(localPath.toString());
            assertTrue(localMp3.getFrameCount() > 0);
            assertEquals(decodedTarget + "\"" + aliasName + "\"", localMp3.getId3v2Tag().getTitle());
            assertEquals("19", localMp3.getId3v2Tag().getArtist());
            assertEquals(decodedTarget, call.snapshot().identifierCollection().getToIdentifier().getValue());
            assertEquals(19, call.snapshot().identifierCollection().getFromIdentifier().getValue());
            broadcaster.start();
            assertEquals(BroadcastState.CONNECTED, broadcaster.getBroadcastState());
            streaming.start();
            streaming.receive(call);
            assertTrue(requestReceived.await(10, TimeUnit.SECONDS), "Actual encoded upload request: " + caseName);
            assertTrue(uploadCompleted.await(5, TimeUnit.SECONDS), "Rdio upload completion: " + caseName);
            CapturedRequest request = captured.get();
            assertEquals("POST", request.method());
            assertEquals("sdrtrunk", request.headers().get("User-Agent"));
            assertEquals(FIELD_NAMES, request.fields().keySet());
            assertEquals(mapped ? "900001" : Integer.toString(decodedTarget), request.text("talkgroup"));
            assertEquals("19", request.text("source"));
            assertEquals(aliasName, request.text("talkgroupLabel"));
            assertEquals("", request.text("talkerAlias"), "No OTA name was decoded or invented");
            assertEquals("[]", request.text("patches"));
            assertEquals(decodedTarget, call.snapshot().identifierCollection().getToIdentifier().getValue());
            assertEquals(decodedTarget, encoded.get().getIdentifierCollection().getToIdentifier().getValue());
            assertEquals(19, encoded.get().getIdentifierCollection().getFromIdentifier().getValue());
            assertEquals(Set.of(route(configuration)), Set.copyOf(encoded.get().getBroadcastChannels()));
            assertArrayEquals(encodedBytes.get(), request.fields().get("audio"));
            Mp3File mp3 = new Mp3File(encoded.get().getPath().toString());
            assertTrue(mp3.getFrameCount() > 0, "The request carries actually encoded MP3 audio");
            assertEquals(decodedTarget + "\"" + aliasName + "\"", mp3.getId3v2Tag().getTitle());
            assertEquals("19", mp3.getId3v2Tag().getArtist());
            assertEquals(1_000, encoded.get().getRecordingLength());
            assertEquals(1, broadcaster.getStreamedAudioCount());
            assertFalse(request.text("talkgroup").contains("NULL"));
            captureEvidence(caseName, call, mapped, request, encodedBytes.get(), mp3, localBytes, localMp3);
        }
        finally
        {
            streaming.stop();
            broadcaster.stop();
            server.stop(0);
            preferences.getDirectoryPreference().setDirectoryStreaming(originalStreamingDirectory);
            if(originalJmbe != null) preferences.getJmbeLibraryPreference().setPathJmbeLibrary(originalJmbe);
            else preferences.getJmbeLibraryPreference().resetPathJmbeLibrary();
        }
    }

    private static CompletedAudioCall conventionalCall(AliasList aliases)
    {
        CompletedAudioCall decoded = NXDNConventionalCallFixture.decodeGroupCall(aliases, 19, 0).audio();
        AudioCallSnapshot original = decoded.snapshot();
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection(original.identifierCollection().getIdentifiers());
        identifiers.silentUpdate(AliasListConfigurationIdentifier.create(aliases.getId(), aliases.getName()));
        long ended = System.currentTimeMillis();
        //The header fixture uses a synthetic clock. Retiming permits the normal upload age checks to run unchanged.
        AudioCallSnapshot snapshot = new AudioCallSnapshot(original.callId(), original.linkedCallId(), aliases,
            identifiers.copyOf(), original.broadcastChannels(), ended - 1_000, ended, 1, 1,
            ended - 1_000, ended, false, true, original.encryptionState(), original.recordAudio(), null,
            original.voiceCallQuality(), original.callLegId(), original.callLegSource(), original.callEncryptionEvidence());
        return new CompletedAudioCall(snapshot, decoded.audioBuffers());
    }

    private static CompletedAudioCall typeDCall(AliasList aliases, BroadcastChannel destination)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(NXDNRadioIdentifier.createTypeDFrom(19));
        identifiers.update(NXDNTalkgroupIdentifier.createTypeDTo(2049));
        identifiers.update(AliasListConfigurationIdentifier.create(aliases.getId(), aliases.getName()));
        identifiers.update(FrequencyConfigurationIdentifier.create(461_125_000L));
        long ended = System.currentTimeMillis();
        AudioCallId callId = new AudioCallId(91, 91, 0);
        CallLegSource source = new CallLegSource(DecoderType.NXDN, "synthetic-type-d", "Synthetic Type-D", null,
            aliases.getId(), null, TrunkedIdentityDomain.NXDN_TYPE_D,
            ChannelConfigurationPolicy.ChannelKind.TRUNKED, true);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, aliases, identifiers.copyOf(), Set.of(destination),
            ended - 1_000, ended, 1, 1, ended - 1_000, ended, false, true, CallEncryptionState.CLEAR,
            false, null, VoiceCallQuality.EMPTY, CallLegId.from(callId), source, null);
        return new CompletedAudioCall(snapshot, List.of(new float[8_000]));
    }

    private static void captureEvidence(String caseName, CompletedAudioCall call, boolean mapped,
                                        CapturedRequest request, byte[] audio, Mp3File mp3,
                                        byte[] localAudio, Mp3File localMp3) throws Exception
    {
        String configuredDirectory = System.getProperty(EVIDENCE_PROPERTY);
        if(configuredDirectory == null || configuredDirectory.isBlank()) return;
        Path root = Path.of(configuredDirectory);
        if(!root.isAbsolute()) throw new IllegalArgumentException("Evidence capture requires an explicit absolute directory");
        Files.createDirectories(root);
        Path directory = root.resolve(caseName);
        Files.createDirectory(directory); //Never overwrite a previous investigation capture.
        Files.write(directory.resolve("request-body.bin"), request.body(), StandardOpenOption.CREATE_NEW);
        Files.write(directory.resolve("audio.mp3"), audio, StandardOpenOption.CREATE_NEW);
        Files.write(directory.resolve("local-recording.mp3"), localAudio, StandardOpenOption.CREATE_NEW);
        ObjectMapper mapper = new ObjectMapper();
        Files.write(directory.resolve("request-headers.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
            Map.of("method", request.method(), "headers", request.headers())), StandardOpenOption.CREATE_NEW);
        Map<String,Object> manifest = new LinkedHashMap<>();
        manifest.put("captureSchemaVersion", 1);
        manifest.put("case", caseName);
        manifest.put("producer", "AudioStreamingManager -> AudioCallRecorder -> RdioScannerBroadcaster");
        manifest.put("input", call.snapshot().callLegSource().channelKind() == ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL ?
            "Decoded conventional NXDN header, synthetic PCM and fresh upload clock" :
            "Synthetic trunked NXDN Type-D completed-call snapshot and PCM");
        manifest.put("channelKind", call.snapshot().callLegSource().channelKind().name());
        manifest.put("identityDomain", call.snapshot().callLegSource().identityDomain().name());
        manifest.put("decodedTalkgroup", call.snapshot().identifierCollection().getToIdentifier().getValue());
        manifest.put("source", 19);
        manifest.put("streamAs", mapped ? MAPPED_TALKGROUP : null);
        manifest.put("wireTalkgroup", request.text("talkgroup"));
        manifest.put("wireSource", request.text("source"));
        manifest.put("wireLabel", request.text("talkgroupLabel"));
        manifest.put("mp3Title", mp3.getId3v2Tag().getTitle());
        manifest.put("mp3Artist", mp3.getId3v2Tag().getArtist());
        manifest.put("localMp3Title", localMp3.getId3v2Tag().getTitle());
        manifest.put("localMp3Artist", localMp3.getId3v2Tag().getArtist());
        manifest.put("localFilename", "Chosen isolated test path; Basic filename generator not exercised by this test");
        manifest.put("requestBodySha256", sha256(request.body()));
        manifest.put("audioSha256", sha256(audio));
        manifest.put("localAudioSha256", sha256(localAudio));
        manifest.put("externalServiceContacted", false);
        Files.write(directory.resolve("manifest.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest),
            StandardOpenOption.CREATE_NEW);
    }

    private static String sha256(byte[] bytes) throws Exception
    {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static Map<String,byte[]> multipartFields(byte[] bytes, String contentType)
    {
        String body = new String(bytes, StandardCharsets.ISO_8859_1);
        String boundary = contentType.substring(contentType.indexOf("boundary=") + "boundary=".length());
        Pattern name = Pattern.compile("(?:^|; )name=\"([^\"]+)\"");
        Map<String,byte[]> fields = new LinkedHashMap<>();
        for(String part: body.split(Pattern.quote("--" + boundary)))
        {
            int separator = part.indexOf("\r\n\r\n");
            if(separator < 0) continue;
            var matcher = name.matcher(part.substring(0, separator));
            assertTrue(matcher.find());
            String value = part.substring(separator + 4);
            assertTrue(value.endsWith("\r\n"));
            assertNull(fields.putIfAbsent(matcher.group(1),
                value.substring(0, value.length() - 2).getBytes(StandardCharsets.ISO_8859_1)));
        }
        return fields;
    }

    private static void respond(HttpExchange exchange, String response) throws IOException
    {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try(var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private record CapturedRequest(String method, Map<String,String> headers, byte[] body, Map<String,byte[]> fields)
    {
        String text(String field) { return new String(fields.get(field), StandardCharsets.UTF_8); }
    }
}
