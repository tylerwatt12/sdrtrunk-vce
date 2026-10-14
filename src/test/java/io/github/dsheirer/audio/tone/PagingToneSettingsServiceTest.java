package io.github.dsheirer.audio.tone;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.database.settings.ReceiverSettingsSchema;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PagingToneSettingsServiceTest
{
    @TempDir Path directory;

    @Test
    void defaultsOnAndSavedOffSurvivesRestartWithoutStaleWrites() throws Exception
    {
        ApplicationSettingsStore store = store();
        AtomicBoolean enabled = new AtomicBoolean();
        var service = new PagingToneSettingsService(store, enabled::set);
        assertTrue(enabled.get());
        var initial = service.snapshot();
        assertEquals("ready", initial.status());
        assertTrue(service.setEnabled(initial.revision(), false).updated());
        assertFalse(enabled.get());
        assertFalse(service.setEnabled(initial.revision(), true).updated());
        var restarted = new PagingToneSettingsService(store, enabled::set);
        assertFalse(enabled.get());
        assertEquals(service.snapshot(), restarted.snapshot());
        assertFalse(store.load(ReceiverSettingsSchema.PAGING_TONES_KEY,
            PagingToneSettingsService.Settings.class).orElseThrow().enabled());
        assertTrue(restarted.setEnabled(restarted.snapshot().revision(), true).updated());
        assertTrue(enabled.get());
        assertEquals(restarted.snapshot(), new PagingToneSettingsService(store, enabled::set).snapshot());
    }

    @Test
    void unavailablePreferenceSafelyDisablesAndCanBeRepairedEvenBySavingOff() throws Exception
    {
        ApplicationSettingsStore store = store();
        for(Object invalid : new Object[]{"invalid", Map.of("enabled", "true"), Map.of(),
            Map.of("enabled", true, "extra", 0)})
        {
            store.save(ReceiverSettingsSchema.PAGING_TONES_KEY, invalid);
            JsonNode original = store.load(ReceiverSettingsSchema.PAGING_TONES_KEY, JsonNode.class).orElseThrow();
            AtomicBoolean enabled = new AtomicBoolean(true);
            var service = new PagingToneSettingsService(store, enabled::set);
            assertFalse(enabled.get());
            assertEquals("unavailable", service.snapshot().status());
            assertEquals(original, store.load(ReceiverSettingsSchema.PAGING_TONES_KEY, JsonNode.class).orElseThrow(),
                "Construction must not repair persisted settings");
            assertTrue(service.setEnabled(service.snapshot().revision(), false).updated());
            assertEquals("ready", service.snapshot().status());
            assertEquals(service.snapshot(), new PagingToneSettingsService(store, enabled::set).snapshot());
        }
    }

    @Test
    void missingRequiredPreferenceStaysUnavailableWithoutCreatingARow() throws Exception
    {
        ApplicationSettingsStore store = store();
        try(var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + store.getDatabasePath());
            var delete = connection.prepareStatement("DELETE FROM application_settings WHERE key = ?"))
        {
            delete.setString(1, ReceiverSettingsSchema.PAGING_TONES_KEY);
            delete.executeUpdate();
        }
        AtomicBoolean enabled = new AtomicBoolean(true);

        var service = new PagingToneSettingsService(store, enabled::set);

        assertFalse(enabled.get());
        assertEquals("unavailable", service.snapshot().status());
        assertFalse(store.contains(ReceiverSettingsSchema.PAGING_TONES_KEY));
    }

    @Test
    void duplicateTrailingAndOversizedStoredValuesRemainUnavailableAndUnchanged() throws Exception
    {
        ApplicationSettingsStore store = store();
        for(String raw: new String[]{"{\"enabled\":false,\"enabled\":true}", "{\"enabled\":true} {}",
            " ".repeat(257) + "{\"enabled\":true}"})
        {
            try(var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + store.getDatabasePath());
                var statement = connection.createStatement();
                var update = connection.prepareStatement(
                    "UPDATE application_settings SET settings_json = ? WHERE key = ?"))
            {
                //Model an already damaged row without changing the fixture schema.
                statement.execute("PRAGMA ignore_check_constraints=ON");
                update.setString(1, raw);
                update.setString(2, ReceiverSettingsSchema.PAGING_TONES_KEY);
                assertEquals(1, update.executeUpdate());
            }
            AtomicBoolean enabled = new AtomicBoolean(true);

            var service = new PagingToneSettingsService(store, enabled::set);

            assertFalse(enabled.get());
            assertEquals("unavailable", service.snapshot().status());
            try(var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + store.getDatabasePath());
                var query = connection.prepareStatement("SELECT settings_json FROM application_settings WHERE key = ?"))
            {
                query.setString(1, ReceiverSettingsSchema.PAGING_TONES_KEY);
                try(var row = query.executeQuery())
                {
                    assertTrue(row.next());
                    assertEquals(raw, row.getString(1));
                }
            }
        }
    }

    @Test
    void failedSavePreservesRuntimeAndPriorSelection() throws Exception
    {
        ApplicationSettingsStore store = store();
        store.save(ReceiverSettingsSchema.PAGING_TONES_KEY, new PagingToneSettingsService.Settings(false));
        ApplicationSettingsStore failing = new ApplicationSettingsStore(store.getDatabasePath())
        {
            @Override
            public void save(String key, Object value) throws IOException, SQLException
            {
                throw new SQLException("Synthetic write failure");
            }
        };
        AtomicBoolean enabled = new AtomicBoolean();
        var service = new PagingToneSettingsService(failing, enabled::set);
        var before = service.snapshot();

        IOException failure = assertThrows(IOException.class, () -> service.setEnabled(before.revision(), true));

        assertNull(failure.getCause());
        assertFalse(enabled.get());
        assertEquals(before, service.snapshot());
        assertFalse(store.load(ReceiverSettingsSchema.PAGING_TONES_KEY,
            PagingToneSettingsService.Settings.class).orElseThrow().enabled());
    }

    private ApplicationSettingsStore store() throws Exception
    {
        Path database = directory.resolve("settings.sqlite");
        SdrTrunkTestDatabase.create(database);
        return new ApplicationSettingsStore(database);
    }
}
