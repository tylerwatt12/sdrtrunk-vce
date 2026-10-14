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
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.database.settings.ReceiverSettingsSchema;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceEnhancementServiceTest
{
    @TempDir Path temporary;
    private ApplicationSettingsStore mSettingsStore;

    @BeforeEach
    void createDatabase() throws Exception
    {
        Path database = temporary.resolve("settings.sqlite");
        SdrTrunkTestDatabase.create(database);
        mSettingsStore = new ApplicationSettingsStore(database);
    }

    @Test
    void defaultsOffAndDoesNotLoadJmbeUntilAnAdministrationRequestOrWorkerNeedsIt() throws Exception
    {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger databaseReads = new AtomicInteger();
        ApplicationSettingsStore guarded = new ApplicationSettingsStore(mSettingsStore.getDatabasePath())
        {
            @Override
            public java.util.Optional<String> loadJson(String key, int maximumBytes) throws IOException, SQLException
            {
                assertEquals(1, databaseReads.incrementAndGet(), "Live audio must use the cached selection");
                return super.loadJson(key, maximumBytes);
            }
        };
        VoiceEnhancementService service = new VoiceEnhancementService(guarded, () -> {
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
        assertEquals(1, databaseReads.get());
        assertFalse(mSettingsStore.load(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY,
            VoiceEnhancementService.Settings.class).orElseThrow().enabled());
    }

    @Test
    void savesOnlyTheEnabledFlagAndUsesTheCurrentlySelectedJmbeModel() throws Exception
    {
        AtomicReference<JmbeVocoderAudio> selected = new AtomicReference<>(VocoderAudioFixtures.provider("first").bridge());
        VoiceEnhancementService service = new VoiceEnhancementService(mSettingsStore, selected::get);
        var before = service.snapshot();
        assertEquals("ready", before.status());
        assertEquals("first", before.model().get("id"));
        assertTrue(service.setEnabled(before.revision(), true).updated());
        String enabledRevision = service.snapshot().revision();
        assertEquals(service.snapshot(), new VoiceEnhancementService(mSettingsStore, selected::get).snapshot());
        var saved = mSettingsStore.load(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY,
            com.fasterxml.jackson.databind.JsonNode.class).orElseThrow();
        assertEquals(1, saved.size());
        assertTrue(saved.path("enabled").booleanValue());
        assertFalse(saved.has("sha256"));
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
    void startupReadsCommittedChoiceWithoutLoadingTheProviderOrRewritingSettings() throws Exception
    {
        mSettingsStore.save(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY,
            new VoiceEnhancementService.Settings(true));
        var original = mSettingsStore.load(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY,
            com.fasterxml.jackson.databind.JsonNode.class).orElseThrow();
        AtomicInteger reads = new AtomicInteger();

        VoiceEnhancementService service = new VoiceEnhancementService(mSettingsStore, () -> {
            reads.incrementAndGet();
            return null;
        });

        assertTrue(service.isEnabled());
        assertEquals(0, reads.get());
        assertEquals(original, mSettingsStore.load(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY,
            com.fasterxml.jackson.databind.JsonNode.class).orElseThrow());
    }

    @Test
    void failedSettingsWriteLeavesEnabledStateUnchanged() throws Exception
    {
        var provider = VocoderAudioFixtures.provider("embedded").bridge();
        ApplicationSettingsStore failing = new ApplicationSettingsStore(mSettingsStore.getDatabasePath())
        {
            @Override
            public void save(String key, Object value) throws IOException, SQLException
            {
                throw new SQLException("Synthetic write failure");
            }
        };
        VoiceEnhancementService service = new VoiceEnhancementService(failing, () -> provider);
        var before = service.snapshot();

        IOException failure = assertThrows(IOException.class, () -> service.setEnabled(before.revision(), true));

        assertNull(failure.getCause());
        assertEquals(before, service.snapshot());
        assertFalse(mSettingsStore.load(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY,
            VoiceEnhancementService.Settings.class).orElseThrow().enabled());
    }

    @Test
    void duplicateTrailingAndOversizedStoredValuesDoNotEnableAudioOrChangeSavedSettings() throws Exception
    {
        for(String raw: new String[]{"{\"enabled\":false,\"enabled\":true}", "{\"enabled\":true} {}",
            " ".repeat(257) + "{\"enabled\":true}"})
        {
            try(var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + mSettingsStore.getDatabasePath());
                var statement = connection.createStatement();
                var update = connection.prepareStatement(
                    "UPDATE application_settings SET settings_json = ? WHERE key = ?"))
            {
                //Model an already damaged row without changing the fixture schema.
                statement.execute("PRAGMA ignore_check_constraints=ON");
                update.setString(1, raw);
                update.setString(2, ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY);
                assertEquals(1, update.executeUpdate());
            }

            var service = new VoiceEnhancementService(mSettingsStore, () -> null);

            assertFalse(service.isEnabled());
            try(var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + mSettingsStore.getDatabasePath());
                var query = connection.prepareStatement("SELECT settings_json FROM application_settings WHERE key = ?"))
            {
                query.setString(1, ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY);
                try(var row = query.executeQuery())
                {
                    assertTrue(row.next());
                    assertEquals(raw, row.getString(1));
                }
            }
        }
    }

    @Test
    void delegatesToJmbeWithoutChangingNativeAudioAndKeepsAgcWhenModelWorkIsSkipped() throws Exception
    {
        var provider = VocoderAudioFixtures.provider("embedded");
        provider.mutateInput = true;
        provider.failModelInfo = true;
        VoiceEnhancementService service = new VoiceEnhancementService(mSettingsStore, provider::bridge);
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
        VoiceEnhancementService service = new VoiceEnhancementService(mSettingsStore, provider::bridge);
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
        VoiceEnhancementService service = new VoiceEnhancementService(mSettingsStore, provider::bridge);
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
        VoiceEnhancementService service = new VoiceEnhancementService(mSettingsStore, provider::bridge);
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
