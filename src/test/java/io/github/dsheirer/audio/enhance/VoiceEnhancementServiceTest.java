package io.github.dsheirer.audio.enhance;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.codec.mbe.JmbeVocoderAudio;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceEnhancementServiceTest
{
    @TempDir Path temporary;

    @Test
    void defaultsOffAndDoesNotLoadJmbeUntilAnAdministrationRequestOrWorkerNeedsIt() throws Exception
    {
        AtomicInteger reads = new AtomicInteger();
        VoiceEnhancementService service = new VoiceEnhancementService(temporary.resolve("models"), () -> {
            reads.incrementAndGet();
            return null;
        });
        assertFalse(service.isEnabled());
        assertNull(service.render(call(DecoderType.P25_PHASE1), false));
        assertEquals(0, reads.get());
        var before = service.snapshot();
        assertFalse(before.enabled());
        assertEquals("unavailable", before.status());
        assertNull(before.model());
        assertThrows(IllegalArgumentException.class, () -> service.setEnabled(before.revision(), true));
        assertNull(service.render(call(DecoderType.P25_PHASE1), true));
        assertFalse(Files.exists(temporary.resolve("models")));
    }

    @Test
    void savesOnlyTheEnabledFlagAndUsesTheCurrentlySelectedJmbeModel() throws Exception
    {
        Path directory = temporary.resolve("models");
        AtomicReference<JmbeVocoderAudio> selected = new AtomicReference<>(VocoderAudioFixtures.provider("first").bridge());
        VoiceEnhancementService service = new VoiceEnhancementService(directory, selected::get);
        var before = service.snapshot();
        assertEquals("ready", before.status());
        assertEquals("first", before.model().get("id"));
        assertTrue(service.setEnabled(before.revision(), true).updated());
        String enabledRevision = service.snapshot().revision();
        assertEquals(service.snapshot(), new VoiceEnhancementService(directory, selected::get).snapshot());
        String saved = Files.readString(directory.resolve("selection.json"));
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(saved);
        assertEquals(2, json.size());
        assertEquals(2, json.path("version").intValue());
        assertTrue(json.path("enabled").booleanValue());
        assertFalse(saved.contains("sha256"));
        assertFalse(service.setEnabled(before.revision(), false).updated());

        selected.set(VocoderAudioFixtures.provider("replacement").bridge());
        assertTrue(service.snapshot().enabled());
        assertEquals("replacement", service.snapshot().model().get("id"));
        assertNotEquals(enabledRevision, service.snapshot().revision());
        assertFalse(service.setEnabled(enabledRevision, false).updated());
        selected.set(null);
        assertEquals("unavailable", service.snapshot().status());
        assertNull(service.render(call(DecoderType.P25_PHASE1), true));
        assertTrue(service.setEnabled(service.snapshot().revision(), false).updated());
    }

    @Test
    void legacySelectionPreservesItsEnabledChoiceWithoutLoadingOrDeletingOldArchives() throws Exception
    {
        Path directory = temporary.resolve("models");
        Files.createDirectories(directory);
        byte[] oldArchive = {1, 2, 3};
        Path archive = directory.resolve("a".repeat(64) + ".zip");
        Files.write(archive, oldArchive);
        Path selection = directory.resolve("selection.json");
        String legacy = "{\"version\":1,\"enabled\":true,\"bundle_sha256\":\"" + "a".repeat(64) + "\"}";
        Files.writeString(selection, legacy);
        var provider = VocoderAudioFixtures.provider("embedded").bridge();
        VoiceEnhancementService service = new VoiceEnhancementService(directory, () -> provider);
        assertTrue(service.isEnabled());
        assertEquals("embedded", service.snapshot().model().get("id"));
        assertEquals(legacy, Files.readString(selection), "Compatibility reads do not rewrite saved data");
        assertTrue(service.setEnabled(service.snapshot().revision(), false).updated());
        assertEquals(2, new com.fasterxml.jackson.databind.ObjectMapper().readTree(Files.readString(selection))
            .path("version").intValue());
        assertArrayEquals(oldArchive, Files.readAllBytes(archive));
    }

    @Test
    void failedSettingsWriteLeavesEnabledStateUnchanged() throws Exception
    {
        Path directory = temporary.resolve("models");
        var provider = VocoderAudioFixtures.provider("embedded").bridge();
        VoiceEnhancementService service = new VoiceEnhancementService(directory, () -> provider);
        var before = service.snapshot();
        Files.writeString(directory, "a file cannot be a model directory");
        assertThrows(java.io.IOException.class, () -> service.setEnabled(before.revision(), true));
        assertEquals(before, service.snapshot());
    }

    @Test
    void delegatesToJmbeWithoutChangingNativeAudioAndKeepsAgcWhenModelWorkIsSkipped() throws Exception
    {
        var provider = VocoderAudioFixtures.provider("embedded");
        provider.mutateInput = true;
        provider.failModelInfo = true;
        VoiceEnhancementService service = new VoiceEnhancementService(temporary, provider::bridge);
        CompletedAudioCall call = call(DecoderType.P25_PHASE1);
        float[] before = call.audioBuffers().getFirst().clone();
        var agc = service.render(call, true);
        assertEquals(0, provider.modelInfoCount, "Automatic gain must not load or inspect the model");
        assertEquals(8000, agc.sampleRate());
        assertNull(agc.modelId());
        assertEquals("IMBE", provider.lastCodec);
        assertTrue(provider.lastAgc);
        assertFalse(provider.lastEnhance);
        assertArrayEquals(before, call.audioBuffers().getFirst());
        provider.failModelInfo = false;
        service.setEnabled(service.snapshot().revision(), true);
        var enhanced = service.render(call, true);
        assertEquals(16000, enhanced.sampleRate());
        assertEquals("embedded", enhanced.modelId());
        assertEquals(before.length * 2, enhanced.samples().length);
        assertTrue(provider.lastEnhance);
        var backlog = service.render(call, true, false);
        assertEquals(8000, backlog.sampleRate());
        assertNull(backlog.modelId());
        assertTrue(provider.lastAgc);
        assertFalse(provider.lastEnhance);
        assertNull(service.render(call, false, false));
        assertArrayEquals(before, call.audioBuffers().getFirst());
        provider.fail = true;
        assertNull(service.render(call, true));
        assertTrue(service.failures() > 0);
    }

    @Test
    void passesBoundedToneProtectionToJmbeAndKeepsEnhancementScopedToValidatedP25() throws Exception
    {
        var provider = VocoderAudioFixtures.provider("embedded");
        VoiceEnhancementService service = new VoiceEnhancementService(temporary, provider::bridge);
        CompletedAudioCall call = call(DecoderType.P25_PHASE2);
        CompletedToneSequence tones = new CompletedToneSequence(call.nativeAudioSnapshot(), 1000,
            CompletedToneSequence.Source.ENCODED, CompletedToneSequence.Method.AMBE_TONE_V1,
            List.of(new CompletedToneSequence.Element(List.of(600_000), "TONE_600", 20L, 40L, null)));
        assertNotNull(service.render(call.withCompletedTones(tones), true));
        assertArrayEquals(new long[]{20}, provider.lastToneOffsets);
        assertArrayEquals(new long[]{40}, provider.lastToneDurations);
        var unknownOffset = new CompletedToneSequence(call.nativeAudioSnapshot(), 1000,
            CompletedToneSequence.Source.ENCODED, CompletedToneSequence.Method.AMBE_TONE_V1,
            List.of(new CompletedToneSequence.Element(List.of(600_000), "TONE_600", null, 40L, null)));
        assertNotNull(service.render(call.withCompletedTones(unknownOffset), true));
        assertArrayEquals(new long[]{0}, provider.lastToneOffsets);
        assertArrayEquals(new long[]{100}, provider.lastToneDurations);
        service.setEnabled(service.snapshot().revision(), true);
        assertEquals(8000, service.render(call(DecoderType.NBFM), true).sampleRate());
        assertEquals("NONE", provider.lastCodec);
        assertNull(service.render(call(DecoderType.DMR), false));
        assertEquals(8000, service.render(call(DecoderType.DMR), true).sampleRate());
        assertEquals("AMBE_PLUS_2", provider.lastCodec);
        assertFalse(provider.lastEnhance);
    }

    @Test
    void automaticGainAcceptsLongerCallsWithoutLoadingTheEnhancementModel() throws Exception
    {
        var provider = VocoderAudioFixtures.provider("embedded");
        provider.failModelInfo = true;
        VoiceEnhancementService service = new VoiceEnhancementService(temporary, provider::bridge);
        CompletedAudioCall template = call(DecoderType.AM);
        float[] samples = new float[VoiceEnhancementService.MAXIMUM_INPUT_SAMPLES + 1];
        samples[0] = .1f;
        CompletedAudioCall longer = new CompletedAudioCall(template.snapshot(), List.of(samples));
        var result = service.render(longer, true);
        assertNotNull(result);
        assertEquals(samples.length, result.samples().length);
        assertEquals(8000, result.sampleRate());
        assertEquals("NONE", provider.lastCodec);
        assertEquals(0, provider.modelInfoCount);
        assertEquals(.1f, samples[0]);
        provider.failModelInfo = false;
        service.setEnabled(service.snapshot().revision(), true);
        int infoReads = provider.modelInfoCount;
        provider.failModelInfo = true;
        var bothRequested = service.render(longer, true);
        assertNotNull(bothRequested);
        assertEquals(8000, bothRequested.sampleRate());
        assertEquals(infoReads, provider.modelInfoCount, "An over-cap model is skipped without loading it");
        assertNull(bothRequested.modelId());
    }

    @Test
    void modelMetadataOrInferenceFailureRetainsIndependentGainAndReCopiesNativeAudio() throws Exception
    {
        var provider = VocoderAudioFixtures.provider("embedded");
        VoiceEnhancementService service = new VoiceEnhancementService(temporary, provider::bridge);
        service.setEnabled(service.snapshot().revision(), true);
        CompletedAudioCall call = call(DecoderType.P25_PHASE1);
        float[] before = call.audioBuffers().getFirst().clone();
        provider.failModelInfo = true;
        var missingModel = service.render(call, true);
        assertNotNull(missingModel);
        assertEquals(8000, missingModel.sampleRate());
        assertNull(missingModel.modelId());
        assertFalse(provider.lastEnhance);
        assertTrue(provider.lastAgc);

        provider.failModelInfo = false;
        provider.failEnhancement = true;
        provider.mutateInput = true;
        var failedInference = service.render(call, true);
        assertNotNull(failedInference);
        assertEquals(8000, failedInference.sampleRate());
        assertEquals(.2f, failedInference.samples()[0]);
        assertArrayEquals(before, call.audioBuffers().getFirst());
        assertNull(service.render(call, false));
    }

    private static CompletedAudioCall call(DecoderType decoder)
    {
        AudioCallId id = new AudioCallId(1, 1, 0);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(id, null, null, new IdentifierCollection(List.of()),
            Set.of(), 1000, 1100, 1, 1, 1000, 1100, false, true, CallEncryptionState.CLEAR,
            false, null, null, CallLegId.from(id), new CallLegSource(decoder,
                "00000000-0000-0000-0000-000000000001", "Test channel", null, 0, null,
                null, null, false), null);
        float[] samples = new float[800];
        java.util.Arrays.fill(samples, .1f);
        return new CompletedAudioCall(snapshot, List.of(samples));
    }
}
