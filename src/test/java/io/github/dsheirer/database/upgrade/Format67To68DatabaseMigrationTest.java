/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.database.settings.ReceiverSettingsSchema;
import io.github.dsheirer.remote.RemoteLinkSettingsStore;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Outbound;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Settings;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.TrustedSender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format67To68DatabaseMigrationTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SENDER = "11111111-1111-4111-8111-111111111111";
    private static final String FEED = "22222222-2222-4222-8222-222222222222";
    @TempDir Path directory;

    @Test void preservesEnabledAndDisabledConnectionsCredentialsFeedsAndActivityThroughRollbackAndRetry() throws Exception
    {
        for(boolean enabled: List.of(false, true))
        {
            Path database = Format67TestDatabase.create(directory.resolve("prior-" + enabled + ".sqlite"));
            Settings expected = new Settings(19, true, "127.0.0.1", 53802,
                List.of(new TrustedSender(SENDER, "Synthetic sender", "synthetic-inbound-key", false, null, 123, false)),
                new Outbound(enabled, "synthetic.invalid", 53801, SENDER, "synthetic-outbound-key", List.of(FEED)));
            String original = Format67TestDatabase.remoteSettingsJson(expected);
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var update = connection.prepareStatement(
                    "UPDATE application_settings SET settings_json=?,updated_at_ms=123 WHERE key='remote.links'"))
            {
                update.setString(1, original);
                assertEquals(1, update.executeUpdate());
                assertEquals(67, DatabaseFormatCatalog.inspect(connection).version());
                connection.setAutoCommit(false);
                var effects = new Format67To68DatabaseMigration().migrateAndReport(connection);
                DatabaseFormatCatalog.stamp(connection, 68);
                assertEquals(1, effects.getFirst().affectedRows());
                assertEquals(68, DatabaseFormatCatalog.requireCurrent(connection).version());
                assertEquals(ConfigurationHistoryDatabase.CONFIGURATION_FINGERPRINT,
                    SqliteSchemaValidator.fingerprint(connection));
                assertEquals(expected, settings(connection));
                assertEquals(RemoteLinkSettingsStore.LEGACY_DESTINATION_ID, settings(connection).destinations().getFirst().id());
                assertEquals(123, timestamp(connection));
                connection.rollback();
                assertEquals(67, DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(original, json(connection));
            }
            byte[] activity = Files.readAllBytes(ActivityDatabasePath.forConfiguration(database));
            byte[] before = Files.readAllBytes(database);
            assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
            assertArrayEquals(before, Files.readAllBytes(database));
            assertThrows(IllegalStateException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database, phase -> {
                if(phase.equals("Validating the updated schema and configuration")) throw new IllegalStateException("injected");
            }));
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
            {
                assertEquals(67, DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(original, json(connection));
            }
            ApplicationDatabaseMigrator.migrateInPlace(database, null);
            assertEquals(expected, new RemoteLinkSettingsStore(database).load());
            ConfigurationHistoryDatabase.validatePair(database);
            assertArrayEquals(activity, Files.readAllBytes(ActivityDatabasePath.forConfiguration(database)));
            byte[] current = Files.readAllBytes(database);
            ApplicationDatabaseMigrator.migrateInPlace(database, null);
            assertArrayEquals(current, Files.readAllBytes(database));
        }
    }

    @Test void untouchedDefaultBecomesEmptyAndCurrentStartupRefusesLegacyShape() throws Exception
    {
        Path database = Format67TestDatabase.create(directory.resolve("defaults.sqlite"));
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        assertTrue(new RemoteLinkSettingsStore(database).load().destinations().isEmpty());
        ReceiverSettingsSchema.validateJson(ReceiverSettingsSchema.REMOTE_LINKS_KEY,
            JSON.writeValueAsString(Settings.defaults()));
        assertThrows(SQLException.class, () -> ReceiverSettingsSchema.validateJson(
            ReceiverSettingsSchema.REMOTE_LINKS_KEY, Format67ReceiverSettingsSchema.defaultRemoteJson()));
        assertThrows(SQLException.class, () -> Format67ReceiverSettingsSchema.validateJson(
            ReceiverSettingsSchema.REMOTE_LINKS_KEY, JSON.writeValueAsString(Settings.defaults())));
    }

    private static Settings settings(java.sql.Connection connection) throws Exception
    {
        return JSON.readValue(json(connection), Settings.class);
    }
    private static String json(java.sql.Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement(); var row = statement.executeQuery(
            "SELECT settings_json FROM application_settings WHERE key='remote.links'"))
        {
            assertTrue(row.next());
            return row.getString(1);
        }
    }
    private static long timestamp(java.sql.Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement(); var row = statement.executeQuery(
            "SELECT updated_at_ms FROM application_settings WHERE key='remote.links'"))
        {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }
}
