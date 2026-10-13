package io.github.dsheirer.audio.enhance;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceEnhancementServiceTest
{
    @TempDir Path temporary;

    @Test
    void defaultsOffAndRequiresAValidModelBeforeEnabling() throws Exception
    {
        VoiceEnhancementService service = new VoiceEnhancementService(temporary.resolve("models"));
        var before = service.snapshot();
        assertFalse(before.enabled());
        assertEquals("no_model", before.status());
        assertNull(before.model());
        assertThrows(IllegalArgumentException.class, () -> service.setEnabled(before.revision(), true));
        assertThrows(IllegalArgumentException.class, () -> service.install(before.revision(), new byte[]{1, 2}));
        assertEquals(before, service.snapshot());
        assertFalse(Files.exists(temporary.resolve("models")));
    }

    @Test
    void atomicSelectionSurvivesReloadAndRejectsStaleAndCorruptUpdates() throws Exception
    {
        Path directory = temporary.resolve("models");
        VoiceEnhancementService service = new VoiceEnhancementService(directory);
        String initial = service.snapshot().revision();
        var installed = service.install(initial, ModelBundleFixtures.bundle("first"));
        assertTrue(installed.updated());
        assertFalse(installed.snapshot().enabled());
        assertEquals("ready", installed.snapshot().status());
        assertEquals("first", installed.snapshot().model().get("id"));
        assertEquals(installed.snapshot(), new VoiceEnhancementService(directory).snapshot());
        assertFalse(service.setEnabled(initial, true).updated());
        assertFalse(service.install(initial, ModelBundleFixtures.bundle("stale")).updated());
        assertTrue(service.setEnabled(installed.snapshot().revision(), true).updated());
        var enabled = service.snapshot();
        byte[] selection = Files.readAllBytes(directory.resolve("selection.json"));
        assertThrows(IllegalArgumentException.class, () -> service.install(enabled.revision(), new byte[]{0}));
        assertArrayEquals(selection, Files.readAllBytes(directory.resolve("selection.json")));
        assertEquals(enabled, service.snapshot());
        assertEquals(enabled, new VoiceEnhancementService(directory).snapshot());

        var replacement = service.install(enabled.revision(), ModelBundleFixtures.bundle("second"));
        assertTrue(replacement.updated());
        assertTrue(replacement.snapshot().enabled());
        assertEquals("second", replacement.snapshot().model().get("id"));
        assertTrue(Files.exists(directory.resolve(installed.snapshot().model().get("sha256") + ".zip")),
            "Replacing a model retains its previous immutable archive");
    }

    @Test
    void unavailableSavedModelFallsBackAndCanBeReplaced() throws Exception
    {
        Path directory = temporary.resolve("models");
        VoiceEnhancementService service = new VoiceEnhancementService(directory);
        service.install(service.snapshot().revision(), ModelBundleFixtures.bundle("original"));
        service.setEnabled(service.snapshot().revision(), true);
        Files.write(directory.resolve(service.snapshot().model().get("sha256") + ".zip"), new byte[]{1});
        VoiceEnhancementService restarted = new VoiceEnhancementService(directory);
        assertTrue(restarted.snapshot().enabled());
        assertEquals("unavailable", restarted.snapshot().status());
        assertNull(restarted.snapshot().model());
        assertTrue(restarted.install(restarted.snapshot().revision(), ModelBundleFixtures.bundle("recovered")).updated());
        assertEquals("ready", restarted.snapshot().status());
    }

    @Test
    void failedSettingsWriteLeavesActiveModelAndEnabledStateUnchanged() throws Exception
    {
        Path directory = temporary.resolve("models");
        VoiceEnhancementService service = new VoiceEnhancementService(directory);
        service.install(service.snapshot().revision(), ModelBundleFixtures.bundle("original"));
        var before = service.snapshot();
        Files.move(directory, temporary.resolve("saved-models"));
        Files.writeString(directory, "a file cannot be a model directory");
        assertThrows(java.io.IOException.class, () -> service.setEnabled(before.revision(), true));
        assertThrows(java.io.IOException.class, () -> service.install(before.revision(), ModelBundleFixtures.bundle("replacement")));
        assertEquals(before, service.snapshot());
    }
}
