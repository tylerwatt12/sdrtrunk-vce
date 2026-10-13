package io.github.dsheirer.audio.tone;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PagingToneSettingsServiceTest
{
    @TempDir Path directory;

    @Test
    void defaultsOnAndSavedOffSurvivesRestartWithoutStaleWrites() throws Exception
    {
        AtomicBoolean enabled = new AtomicBoolean();
        Path file = directory.resolve("analysis/paging-tones.json");
        var service = new PagingToneSettingsService(file, enabled::set);
        assertTrue(enabled.get());
        var initial = service.snapshot();
        assertEquals("ready", initial.status());
        assertFalse(Files.exists(file));
        assertTrue(service.setEnabled(initial.revision(), false).updated());
        assertFalse(enabled.get());
        assertFalse(service.setEnabled(initial.revision(), true).updated());
        var restarted = new PagingToneSettingsService(file, enabled::set);
        assertFalse(enabled.get());
        assertEquals(service.snapshot(), restarted.snapshot());
        assertTrue(restarted.setEnabled(restarted.snapshot().revision(), true).updated());
        assertTrue(enabled.get());
        assertEquals(restarted.snapshot(), new PagingToneSettingsService(file, enabled::set).snapshot());
    }

    @Test
    void unreadablePreferenceSafelyDisablesAndCanBeRepairedEvenBySavingOff() throws Exception
    {
        Path file = directory.resolve("paging-tones.json");
        for(String invalid : new String[]{"{", "{\"version\":2,\"enabled\":true}",
            "{\"version\":1,\"enabled\":true,\"enabled\":false}",
            "{\"version\":1,\"enabled\":true} {}", "{\"version\":1,\"enabled\":true,\"extra\":0}"})
        {
            Files.writeString(file, invalid);
            AtomicBoolean enabled = new AtomicBoolean(true);
            var service = new PagingToneSettingsService(file, enabled::set);
            assertFalse(enabled.get());
            assertEquals("unavailable", service.snapshot().status());
            assertTrue(service.setEnabled(service.snapshot().revision(), false).updated());
            assertEquals("ready", service.snapshot().status());
            assertEquals(service.snapshot(), new PagingToneSettingsService(file, enabled::set).snapshot());
        }
    }

    @Test
    void failedSavePreservesRuntimeAndPriorSelection() throws Exception
    {
        Path parent = directory.resolve("analysis");
        Path file = parent.resolve("paging-tones.json");
        AtomicBoolean enabled = new AtomicBoolean();
        var service = new PagingToneSettingsService(file, enabled::set);
        service.setEnabled(service.snapshot().revision(), false);
        var before = service.snapshot();
        Files.move(parent, directory.resolve("preserved"));
        Files.writeString(parent, "not a settings directory");
        assertThrows(java.io.IOException.class, () -> service.setEnabled(before.revision(), true));
        assertFalse(enabled.get());
        assertEquals(before, service.snapshot());
    }
}
