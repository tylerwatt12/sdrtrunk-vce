/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Outbound;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Settings;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.TrustedSender;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteLinkSettingsStoreTest
{
    @TempDir
    Path mTemp;

    @Test
    void freshDatabaseContainsDisabledSettingsWithTheAllInterfacesBind() throws Exception
    {
        Path database = database();
        Settings defaults = new RemoteLinkSettingsStore(database).load();

        assertEquals(Settings.defaults(), defaults);
        assertEquals("0.0.0.0", RemoteLinkAdministrationService.ListenerSnapshot.STOPPED.bindAddress());
        assertTrue(new ApplicationSettingsStore(database).contains(RemoteLinkSettingsStore.SETTINGS_KEY));
    }

    @Test
    void commitsEveryPairingAndOutboundFieldInTheApplicationDatabase() throws Exception
    {
        Path database = database();
        RemoteLinkSettingsStore store = new RemoteLinkSettingsStore(database);
        String senderId = UUID.randomUUID().toString();
        Settings settings = new Settings(19, true, "127.0.0.1", 53_801,
            List.of(new TrustedSender(senderId, "West site", "synthetic-pairing-key", true, 14L, 123L, false),
                new TrustedSender(UUID.randomUUID().toString(), "Revoked site", null, false, null, 456L, true)),
            new Outbound(true, "test-host", 53_802, senderId, "synthetic-outbound-key",
                List.of(UUID.randomUUID().toString(), UUID.randomUUID().toString())));

        store.save(settings);

        assertEquals(settings, new RemoteLinkSettingsStore(database).load());
        assertEquals(settings, new ApplicationSettingsStore(database)
            .load(RemoteLinkSettingsStore.SETTINGS_KEY, Settings.class).orElseThrow());
        assertFalse(settings.toString().contains("synthetic-pairing-key"));
        assertFalse(settings.trustedSenders().getFirst().toString().contains("synthetic-pairing-key"));
        assertFalse(settings.outbound().toString().contains("synthetic-outbound-key"));
    }

    @Test
    void missingRequiredRowRefusesLoadWithoutCreatingDefaults() throws Exception
    {
        Path database = database();
        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement("DELETE FROM application_settings WHERE key=?"))
        {
            statement.setString(1, RemoteLinkSettingsStore.SETTINGS_KEY);
            assertEquals(1, statement.executeUpdate());
        }

        IOException error = assertThrows(IOException.class, () -> new RemoteLinkSettingsStore(database).load());

        assertTrue(error.getMessage().contains("restore a valid application database"));
        assertNull(error.getCause());
        assertFalse(new ApplicationSettingsStore(database).contains(RemoteLinkSettingsStore.SETTINGS_KEY));
    }

    @Test
    void malformedCredentialNeverAppearsInALoadError() throws Exception
    {
        Path database = database();
        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement(
                "UPDATE application_settings SET settings_json=? WHERE key=?"))
        {
            statement.setString(1, "{\"revision\":2,\"listenerEnabled\":false," +
                "\"bindAddress\":\"127.0.0.1\",\"listenPort\":53800," +
                "\"trustedSenders\":[],\"outbound\":{\"enabled\":false," +
                "\"host\":\"\",\"port\":53800,\"senderId\":null," +
                "\"secret\":{\"sensitive-sentinel\":1},\"exportedChannelIds\":[]}}");
            statement.setString(2, RemoteLinkSettingsStore.SETTINGS_KEY);
            assertEquals(1, statement.executeUpdate());
        }

        IOException error = assertThrows(IOException.class, () -> new RemoteLinkSettingsStore(database).load());

        assertFalse(error.toString().contains("sensitive-sentinel"));
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
    }

    @Test
    void refusesDuplicateFieldsWithoutNormalizingThemAway() throws Exception
    {
        Path database = database();
        String original = new ApplicationSettingsStore(database).loadJson(RemoteLinkSettingsStore.SETTINGS_KEY,
            256_000).orElseThrow();

        assertInvalidRawSettings(database, "{\"bindAddress\":\"sensitive-sentinel\"," + original.substring(1));
    }

    @Test
    void refusesTrailingDocumentsWithoutExposingTheirContents() throws Exception
    {
        Path database = database();
        String original = new ApplicationSettingsStore(database).loadJson(RemoteLinkSettingsStore.SETTINGS_KEY,
            256_000).orElseThrow();

        assertInvalidRawSettings(database, original + " {\"secret\":\"sensitive-sentinel\"}");
    }

    @Test
    void refusesOversizedStoredSettings() throws Exception
    {
        assertInvalidRawSettings(database(), " ".repeat(256_001));
    }

    @Test
    void refusesIncompleteSettingsWithoutFillingMissingFields() throws Exception
    {
        assertInvalidRawSettings(database(), "{\"revision\":2,\"listenerEnabled\":false," +
            "\"bindAddress\":\"127.0.0.1\",\"listenPort\":53800,\"trustedSenders\":[]}");
    }

    @Test
    void oversizedValidSettingsAreRejectedBeforeReplacingTheCommittedRow() throws Exception
    {
        Path database = database();
        RemoteLinkSettingsStore store = new RemoteLinkSettingsStore(database);
        ApplicationSettingsStore applicationSettings = new ApplicationSettingsStore(database);
        String original = applicationSettings.loadJson(RemoteLinkSettingsStore.SETTINGS_KEY, 256_000).orElseThrow();
        Settings oversized = new Settings(2, false, RemoteLinkSettingsStore.DEFAULT_BIND_ADDRESS, 53_800,
            IntStream.range(0, 256).mapToObj(index -> new TrustedSender(UUID.randomUUID().toString(),
                "界".repeat(120), "界".repeat(256), false, null, 1, false)).toList(), Outbound.disabled());
        RemoteLinkSettingsStore.validate(oversized);

        IOException error = assertThrows(IOException.class, () -> store.save(oversized));

        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertFalse(error.toString().contains("界"));
        assertEquals(original, applicationSettings.loadJson(RemoteLinkSettingsStore.SETTINGS_KEY, 256_000).orElseThrow());
        assertEquals(Settings.defaults(), store.load());
    }

    @Test
    void failedWritePreservesCommittedCredentialsAndDoesNotExposeDatabaseDiagnostics() throws Exception
    {
        Path database = database();
        RemoteLinkSettingsStore store = new RemoteLinkSettingsStore(database);
        Settings original = store.load();
        try(Connection connection = SdrTrunkDatabase.open(database); Statement statement = connection.createStatement())
        {
            statement.execute("""
                CREATE TRIGGER refuse_remote_settings BEFORE INSERT ON application_settings
                WHEN NEW.key='remote.links'
                BEGIN SELECT RAISE(ABORT, 'sensitive-sentinel'); END
                """);
        }
        Settings changed = new Settings(2, true, original.bindAddress(), original.listenPort(),
            original.trustedSenders(), original.outbound());

        IOException error = assertThrows(IOException.class, () -> store.save(changed));

        assertFalse(error.toString().contains("sensitive-sentinel"));
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        assertEquals(original, new RemoteLinkSettingsStore(database).load());
    }

    private Path database() throws Exception
    {
        return SdrTrunkTestDatabase.create(mTemp.resolve("sdrtrunk.sqlite"));
    }

    private static void assertInvalidRawSettings(Path database, String json) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement(
                "UPDATE application_settings SET settings_json=? WHERE key=?"))
        {
            //Model corruption after startup, including bytes that ordinary SQLite writers already refuse.
            try(Statement constraints = connection.createStatement())
            {
                constraints.execute("PRAGMA ignore_check_constraints=ON");
            }
            statement.setString(1, json);
            statement.setString(2, RemoteLinkSettingsStore.SETTINGS_KEY);
            assertEquals(1, statement.executeUpdate());
        }

        IOException error = assertThrows(IOException.class, () -> new RemoteLinkSettingsStore(database).load());

        assertFalse(error.toString().contains("sensitive-sentinel"));
        assertNull(error.getCause());
        assertEquals(0, error.getSuppressed().length);
        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement statement = connection.prepareStatement(
                "SELECT settings_json FROM application_settings WHERE key=?"))
        {
            statement.setString(1, RemoteLinkSettingsStore.SETTINGS_KEY);
            try(var row = statement.executeQuery())
            {
                assertTrue(row.next());
                assertEquals(json, row.getString(1), "runtime validation must never rewrite invalid saved settings");
            }
        }
    }
}
