/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.broadcast;

import static io.github.dsheirer.test.BroadcastRouteTestSupport.route;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mpatric.mp3agic.ID3v24Tag;
import com.mpatric.mp3agic.Mp3File;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallBroadcaster;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.audio.broadcast.openmhz.OpenMHzBroadcaster;
import io.github.dsheirer.audio.broadcast.openmhz.OpenMHzConfiguration;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerBroadcaster;
import io.github.dsheirer.audio.broadcast.rdioscanner.RdioScannerConfiguration;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.wave.AudioMetadataUtils;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
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

/** Checks real provider requests locally; the receiving service is never contacted. */
class NumericIdUploadContractTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void broadcastifyKeepsNumericIdsMappingsAndItsPatchListContract() throws Exception
    {
        for(Probe probe: probes()) verifyUpload(Provider.BROADCASTIFY, probe);
    }

    @Test
    void rdioKeepsNumericIdsMappingsAndItsSeparatePatchContract() throws Exception
    {
        for(Probe probe: probes()) verifyUpload(Provider.RDIO, probe);
    }

    @Test
    void openMhzKeepsNumericIdsMappingsAndItsSeparatePatchContract() throws Exception
    {
        for(Probe probe: probes()) verifyUpload(Provider.OPENMHZ, probe);
    }

    @Test
    void realStreamingMp3NamesUseConfiguredThenOtaWithoutChangingProviderIdsOrOtaFields() throws Exception
    {
        for(Provider provider: Provider.values())
        {
            verifyUpload(provider, probes().get(0), null, "OTA NXDN Unit");
            verifyUpload(provider, probes().get(0), "Configured Unit", "OTA NXDN Unit");
            verifyUpload(provider, probes().get(4), null, "OTA Type-D Unit");
            verifyUpload(provider, probes().get(6), null, "OTA P25 Unit");
        }
    }

    private static List<Probe> probes()
    {
        PatchGroup patch = new PatchGroup(APCO25Talkgroup.create(100));
        patch.addPatchedTalkgroup(APCO25Talkgroup.create(200));
        patch.addPatchedRadio(APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(
            502, 0xBEE00, 0x687, 70002));
        return List.of(
            new Probe("nxdn-zero", NXDNRadioIdentifier.createFrom(19), NXDNTalkgroupIdentifier.createTo(0),
                "19", "0", "0", "[]", null),
            new Probe("nxdn-special", NXDNRadioIdentifier.createFrom(0xFFF0), NXDNTalkgroupIdentifier.createTo(0xFFFF),
                "65520", "65535", "65535", "[]", null),
            new Probe("type-d-first-repeater", NXDNRadioIdentifier.createTypeDFrom((1 << 11) | 7),
                NXDNTalkgroupIdentifier.createTypeDTo((3 << 11) | 25), "2055", "6169", "6169", "[]", null),
            new Probe("type-d-other-repeater", NXDNRadioIdentifier.createTypeDFrom((2 << 11) | 7),
                NXDNTalkgroupIdentifier.createTypeDTo((4 << 11) | 25), "4103", "8217", "8217", "[]", null),
            new Probe("type-d-stream-as", NXDNRadioIdentifier.createTypeDFrom((1 << 11) | 7),
                NXDNTalkgroupIdentifier.createTypeDTo((3 << 11) | 25), "2055", "6169", "6169", "[]", 700),
            new Probe("nxdn-radio-destination", NXDNRadioIdentifier.createFrom(12), NXDNRadioIdentifier.createTo(0xFFFF),
                "12", "65535", "65535", "[]", null),
            new Probe("p25-qualified-patch", APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                501, 0xBEE00, 0x687, 70001), APCO25PatchGroup.create(patch),
                "501", "P:100 [200] [502]", "100,200,502", "[100,200,502]", null));
    }

    private void verifyUpload(Provider provider, Probe probe) throws Exception
    {
        verifyUpload(provider, probe, null, null);
    }

    private void verifyUpload(Provider provider, Probe probe, String configuredSource, String otaName) throws Exception
    {
        AliasListDefinition definition = new AliasListDefinition("Numeric export fixture",
            probe.source().getProtocol() == Protocol.NXDN ? AliasListFamily.NXDN : AliasListFamily.P25);
        definition.setId(7);
        AliasModel aliases = new AliasModel();
        List<Alias> configured = new ArrayList<>();
        if(probe.streamAs() != null)
        {
            Alias mapped = new Alias("0xABCD dispatch");
            mapped.setId(1);
            mapped.setAliasListDefinition(definition);
            mapped.setMatchIdentifier(new Talkgroup(Protocol.NXDN, ((Number)probe.target().getValue()).intValue()));
            mapped.setStreamTalkgroupAlias(new StreamAsTalkgroup(probe.streamAs()));
            configured.add(mapped);
        }
        if(configuredSource != null)
        {
            Alias source = new Alias(configuredSource);
            source.setId(2);
            source.setAliasListDefinition(definition);
            source.setMatchIdentifier(new Radio(probe.source().getProtocol(),
                ((Number)probe.source().getValue()).intValue()));
            configured.add(source);
        }
        aliases.replaceCommittedConfiguration(List.of(definition), configured);
        List<Identifier> captured = new ArrayList<>(List.of(probe.source(), probe.target(),
            AliasListConfigurationIdentifier.create(definition.getId(), definition.getName()),
            FrequencyConfigurationIdentifier.create(461_125_000L)));
        if(otaName != null)
        {
            TalkerAliasIdentifier raw = probe.source().getProtocol() == Protocol.NXDN ?
                new NXDNTalkerAliasIdentifier(otaName) : P25TalkerAliasIdentifier.create(otaName);
            TalkerAliasIdentifier bound = raw.bindTo((RadioIdentifier)probe.source());
            assertNotNull(bound);
            captured.add(bound);
        }
        IdentifierCollection identifiers = new IdentifierCollection(captured);
        byte[] tagBytes = AudioMetadataUtils.getMP3ID3(AudioMetadataUtils.getMetadataMap(identifiers,
            aliases.getAliasList(definition.getName())));
        ID3v24Tag tags = new ID3v24Tag(tagBytes);
        String expectedTitle = probe.basicTitle() + (probe.streamAs() != null ? "\"0xABCD dispatch\"" : "");
        assertEquals(expectedTitle, tags.getTitle(), probe.name());
        String expectedArtist = probe.sourceText() + (configuredSource != null ? " " + configuredSource :
            otaName != null ? " " + otaName : "");
        assertEquals(expectedArtist, tags.getArtist(), probe.name());
        ByteArrayOutputStream transport = new ByteArrayOutputStream();
        transport.write(tagBytes);
        transport.write("synthetic transport audio".getBytes(StandardCharsets.US_ASCII));
        byte[] audioBytes = otaName != null ? actualStreamingMp3(probe, aliases, definition, identifiers,
            configuredSource) : transport.toByteArray();
        //Rdio derives its uploaded basename from the final two underscore-separated spool tokens.
        Path path = mTemporaryFolder.resolve("temporary_streaming_file_" + provider + "_" + probe.name() + ".mp3");
        Files.write(path, audioBytes);
        AtomicReference<Map<String,byte[]>> requestFields = new AtomicReference<>();
        AtomicReference<byte[]> uploadedAudio = new AtomicReference<>();
        CountDownLatch streamed = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            if(exchange.getRequestMethod().equals("PUT"))
            {
                uploadedAudio.set(exchange.getRequestBody().readAllBytes());
                respond(exchange, "ok");
                return;
            }
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.ISO_8859_1);
            if(exchange.getRequestURI().getPath().endsWith("/authorize")) respond(exchange, "ok");
            else if(body.contains("name=\"test\""))
                respond(exchange, provider == Provider.RDIO ? "incomplete call data: no talkgroup" : "ok");
            else
            {
                Map<String,byte[]> fields = multipartFields(body, exchange.getRequestHeaders().getFirst("Content-Type"));
                requestFields.set(fields);
                if(provider == Provider.BROADCASTIFY)
                    respond(exchange, "0 http://127.0.0.1:" + server.getAddress().getPort() + "/file");
                else
                {
                    uploadedAudio.set(fields.get(provider == Provider.RDIO ? "audio" : "call"));
                    respond(exchange, provider == Provider.RDIO ? "Call imported successfully." : "ok");
                }
            }
        });
        server.start();
        String host = "http://127.0.0.1:" + server.getAddress().getPort();
        AbstractAudioBroadcaster<?> broadcaster = broadcaster(provider, host, aliases);
        broadcaster.setListener(event -> {
            if(event.getEvent() == BroadcastEvent.Event.BROADCASTER_STREAMED_COUNT_CHANGE) streamed.countDown();
        });
        try
        {
            broadcaster.start();
            assertEquals(BroadcastState.CONNECTED, broadcaster.getBroadcastState());
            AudioRecording recording = new AudioRecording(path, List.of(), identifiers, System.currentTimeMillis(), 1_000);
            recording.addPendingReplay();
            broadcaster.receive(recording);
            assertTrue(streamed.await(5, TimeUnit.SECONDS), provider + " " + probe.name());
            Map<String,byte[]> fields = requestFields.get();
            Set<String> expectedFields = new HashSet<>(provider.fields);
            if(provider == Provider.BROADCASTIFY && otaName != null) expectedFields.add("srcId_alias");
            assertEquals(expectedFields, fields.keySet(), "The provider's existing field schema must remain intact");
            String target = probe.streamAs() != null ? probe.streamAs().toString() :
                provider == Provider.BROADCASTIFY ? probe.apiTarget() : probe.apiTarget().split(",")[0];
            assertEquals(target, text(fields, provider.targetField), probe.name());
            assertEquals(provider == Provider.OPENMHZ ? "[{ \"pos\": 0.00, \"src\": " + probe.sourceText() + "}]" :
                probe.sourceText(), text(fields, provider.sourceField), probe.name());
            if(provider != Provider.BROADCASTIFY)
            {
                assertEquals(probe.patches(), text(fields, provider == Provider.RDIO ? "patches" : "patch_list"));
                assertEquals(otaName != null ? otaName : "",
                    text(fields, provider == Provider.RDIO ? "talkerAlias" : "talker_alias"),
                    "The separate OTA upload field keeps the decoded name even when a configured Artist wins");
            }
            else if(otaName != null) assertEquals(otaName, text(fields, "srcId_alias"));
            assertArrayEquals(audioBytes, uploadedAudio.get(), "Providers must transport the existing MP3 tags unchanged");
            assertEquals(expectedTitle, new ID3v24Tag(uploadedAudio.get()).getTitle());
            assertEquals(expectedArtist, new ID3v24Tag(uploadedAudio.get()).getArtist());
            assertEquals(probe.target(), identifiers.getToIdentifier(), "Provider remapping must not replace decoded identity");
            assertEquals(1, broadcaster.getStreamedAudioCount());
        }
        finally
        {
            broadcaster.stop();
            server.stop(0);
        }
    }

    /** Runs the actual background streaming encoder, with fresh synthetic audio and isolated preferences. */
    private byte[] actualStreamingMp3(Probe probe, AliasModel aliases, AliasListDefinition definition,
                                      IdentifierCollection identifiers, String configuredSource) throws Exception
    {
        assertTrue(Preferences.userRoot().getClass().getName().contains("SqlitePreferencesFactory"),
            "Streaming preference changes require the isolated SQLite test preference factory");
        UserPreferences preferences = new UserPreferences();
        Path previousStreaming = preferences.getDirectoryPreference().getDirectoryStreaming();
        Path previousJmbe = preferences.getJmbeLibraryPreference().getPathJmbeLibrary();
        PatchGroupStreamingOption previousPatch = preferences.getCallManagementPreference().getPatchGroupStreamingOption();
        Path output = Files.createTempDirectory(mTemporaryFolder, "encoded-stream-");
        long ended = System.currentTimeMillis();
        AudioCallId id = new AudioCallId(71, 71, 0);
        var destination = route("Numeric ID and name upload fixture");
        AudioCallSnapshot snapshot = new AudioCallSnapshot(id, null, aliases.getAliasList(definition.getName()),
            identifiers, Set.of(destination), ended - 1_000, ended, 1, 1, ended - 1_000, ended,
            false, true, CallEncryptionState.CLEAR, false, null, VoiceCallQuality.EMPTY,
            CallLegId.from(id), CallLegSource.UNKNOWN, null);
        CompletedAudioCall call = new CompletedAudioCall(snapshot, List.of(new float[8_000]));
        assertEquals(configuredSource, snapshot.recordingMetadata().sourceAlias(),
            "A learned OTA name must not be relabeled as a configured source alias");
        AtomicReference<AudioRecording> encoded = new AtomicReference<>();
        CountDownLatch complete = new CountDownLatch(1);
        AudioStreamingManager streaming = new AudioStreamingManager(recording -> {
            encoded.set(recording);
            complete.countDown();
        }, BroadcastFormat.MP3, preferences);
        try
        {
            preferences.getDirectoryPreference().setDirectoryStreaming(output);
            preferences.getJmbeLibraryPreference().resetPathJmbeLibrary();
            preferences.getCallManagementPreference().setPatchGroupStreamingOption(PatchGroupStreamingOption.PATCH_GROUP);
            streaming.start();
            streaming.receive(call);
            assertTrue(complete.await(10, TimeUnit.SECONDS), "Real streaming encoding for " + probe.name());
            AudioRecording recording = encoded.get();
            Mp3File mp3 = new Mp3File(recording.getPath().toString());
            assertTrue(mp3.getFrameCount() > 0, "The upload must contain genuinely encoded MP3 frames");
            assertEquals(Set.of(destination), Set.copyOf(recording.getBroadcastChannels()));
            assertEquals(probe.source(), recording.getIdentifierCollection().getFromIdentifier());
            assertEquals(probe.target(), recording.getIdentifierCollection().getToIdentifier());
            assertEquals(configuredSource, snapshot.recordingMetadata().sourceAlias());
            return Files.readAllBytes(recording.getPath());
        }
        finally
        {
            streaming.stop();
            preferences.getDirectoryPreference().setDirectoryStreaming(previousStreaming);
            if(previousJmbe != null) preferences.getJmbeLibraryPreference().setPathJmbeLibrary(previousJmbe);
            else preferences.getJmbeLibraryPreference().resetPathJmbeLibrary();
            preferences.getCallManagementPreference().setPatchGroupStreamingOption(previousPatch);
        }
    }

    private static AbstractAudioBroadcaster<?> broadcaster(Provider provider, String host, AliasModel aliases)
    {
        return switch(provider)
        {
            case BROADCASTIFY -> {
                BroadcastifyCallConfiguration config = new BroadcastifyCallConfiguration();
                config.setHost(host + "/calls"); config.setApiKey("synthetic-key"); config.setSystemID(1);
                yield new BroadcastifyCallBroadcaster(config, null, null, aliases);
            }
            case RDIO -> {
                RdioScannerConfiguration config = new RdioScannerConfiguration();
                config.setHost(host + "/call-upload"); config.setApiKey("synthetic-key"); config.setSystemID(1);
                yield new RdioScannerBroadcaster(config, null, null, aliases);
            }
            case OPENMHZ -> {
                OpenMHzConfiguration config = new OpenMHzConfiguration();
                config.setHost(host); config.setApiKey("synthetic-key"); config.setSystemName("fixture");
                yield new OpenMHzBroadcaster(config, null, null, aliases);
            }
        };
    }

    private static Map<String,byte[]> multipartFields(String body, String contentType)
    {
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

    private static String text(Map<String,byte[]> fields, String name)
    {
        return new String(fields.get(name), StandardCharsets.UTF_8);
    }

    private static void respond(HttpExchange exchange, String response) throws IOException
    {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try(var output = exchange.getResponseBody()) { output.write(bytes); }
    }

    private record Probe(String name, Identifier<?> source, Identifier<?> target, String sourceText,
                         String basicTitle, String apiTarget, String patches, Integer streamAs) { }

    private enum Provider
    {
        BROADCASTIFY("tg", "src", Set.of("apiKey", "systemId", "callDuration", "ts", "tg", "src", "freq", "enc")),
        RDIO("talkgroup", "source", Set.of("key", "system", "dateTime", "talkgroup", "source", "frequency",
            "talkerAlias", "talkgroupLabel", "talkgroupGroup", "systemLabel", "patches", "audio")),
        OPENMHZ("talkgroup_num", "source_list", Set.of("freq", "start_time", "stop_time", "call_length", "talkgroup_num",
            "emergency", "api_key", "patch_list", "talker_alias", "source_list", "call"));

        private final String targetField;
        private final String sourceField;
        private final Set<String> fields;

        Provider(String targetField, String sourceField, Set<String> fields)
        {
            this.targetField = targetField;
            this.sourceField = sourceField;
            this.fields = fields;
        }
    }
}
