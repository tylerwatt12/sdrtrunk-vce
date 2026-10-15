/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.database.settings.ReceiverSettingsSchema;
import io.github.dsheirer.remote.RemoteLinkSettingsStore;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Outbound;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Settings;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.TrustedSender;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format52To53DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void freshAndMigratedDatabasesHaveTheSameRequiredDefaultsAndExactSchema() throws Exception
    {
        Path database = Format52TestDatabase.create(directory.resolve("prior.sqlite"));
        Map<String,List<String>> before;
        try(Connection connection = open(database); var sql = connection.createStatement())
        {
            assertEquals(52, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("4b8bb01a7b346866f0af8dadc087f680a30bfe310aeeaef2c28d8f282ba00d41",
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals(3, ReceiverSettingsSchema.countMissing(connection));
            sql.executeUpdate("UPDATE sqlite_sequence SET seq=seq+5000 WHERE name='receiver_activity_event'");
            sql.execute("ANALYZE receiver_activity_tone_sequence");
            before = retainedContents(connection);
            connection.setAutoCommit(false);
            var report = DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals(List.of("format-52-to-53", "format-53-to-54"),
                report.steps().stream().map(DatabaseMigrationChain.StepReport::id).toList());
            assertEquals(3, report.steps().getFirst().effects().getLast().affectedRows());
            assertEquals(before, retainedContents(connection));
            assertDefaults(connection);
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
            integrity(connection);
        }
        Path fresh = directory.resolve("fresh.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(fresh);
        try(Connection connection = open(fresh))
        {
            assertDefaults(connection);
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            integrity(connection);
        }
    }

    @Test void validPrematureDatabaseChoicesAndCredentialsArePreservedExactly() throws Exception
    {
        Path database = Format52TestDatabase.create(directory.resolve("existing.sqlite"));
        var store = new ApplicationSettingsStore(database);
        String sender = UUID.randomUUID().toString();
        Settings remote = new Settings(7, true, "127.0.0.1", 53_800,
            List.of(new TrustedSender(sender, "Synthetic receiver", "synthetic-credential", true, null, 123, false)),
            new Outbound(true, "127.0.0.1", 53_801, sender, "synthetic-credential", List.of(UUID.randomUUID().toString())));
        store.save(ReceiverSettingsSchema.REMOTE_LINKS_KEY, remote);
        store.save(ReceiverSettingsSchema.PAGING_TONES_KEY, Map.of("enabled", false));
        store.save(ReceiverSettingsSchema.VOICE_ENHANCEMENT_KEY, Map.of("enabled", true));
        try(Connection connection = open(database))
        {
            Map<String,List<String>> before = allContents(connection);
            connection.setAutoCommit(false);
            var report = DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals(0, report.steps().getFirst().effects().getLast().affectedRows());
            assertEquals(before, allContents(connection));
            ReceiverSettingsSchema.validate(connection);
        }
        assertEquals(remote, store.load(ReceiverSettingsSchema.REMOTE_LINKS_KEY, Settings.class).orElseThrow());
    }

    @Test void invalidExistingChoicesRefuseWithoutCredentialDisclosureOrChanges() throws Exception
    {
        Path database = Format52TestDatabase.create(directory.resolve("invalid.sqlite"));
        String secret = "synthetic-do-not-report-credential";
        try(Connection connection = open(database); var insert = connection.prepareStatement(
            "INSERT INTO application_settings VALUES(?,?,1)"))
        {
            insert.setString(1, ReceiverSettingsSchema.REMOTE_LINKS_KEY);
            insert.setString(2, "{\"secret\":\"" + secret + "\"}");
            insert.executeUpdate();
            Map<String,List<String>> before = allContents(connection);
            connection.setAutoCommit(false);
            SQLException failure = assertThrows(SQLException.class, () -> DatabaseMigrationChain.migrate(connection));
            assertFalse(failure.toString().contains(secret));
            assertNull(failure.getCause());
            assertEquals(before, allContents(connection));
            connection.rollback();
            assertEquals(52, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    @Test void preCommitFailureRollsBackAllDefaultsAndRetrySucceedsWithEitherBackupChoice() throws Exception
    {
        for(boolean backup: List.of(false, true))
        {
            Path root = directory.resolve("rollback-" + backup);
            Path database = Format52TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(root));
            Map<String,List<String>> before;
            try(Connection connection = open(database)) { before = allContents(connection); }
            assertThrows(CancellationException.class, () -> new ApplicationMigrationService().migrateCurrent(root,
                backup, message -> {
                    if(message.equals("Committing the database update")) throw new CancellationException("Synthetic cancellation");
                }));
            try(Connection connection = open(database))
            {
                assertEquals(52, DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(before, allContents(connection));
                assertEquals(3, ReceiverSettingsSchema.countMissing(connection));
                integrity(connection);
            }
            new ApplicationMigrationService().migrateCurrent(root, false, null);
            try(Connection connection = open(database))
            {
                assertDefaults(connection);
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
                integrity(connection);
            }
        }
    }

    @Test void directRollbackLeavesFrozenSourceBytesUnchanged() throws Exception
    {
        Path database = Format52TestDatabase.create(directory.resolve("retry.sqlite"));
        byte[] original = Files.readAllBytes(database);
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            new Format52To53DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 53);
            connection.rollback();
            assertEquals(52, DatabaseFormatCatalog.inspect(connection).version());
        }
        assertArrayEquals(original, Files.readAllBytes(database));
    }

    @Test void startupAndCurrentMigratorRefuseMissingRowsWithoutRepairingThem() throws Exception
    {
        for(String key: ReceiverSettingsSchema.KEYS)
        {
            Path root = directory.resolve("missing-" + key);
            Path database = Format53TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(root));
            try(Connection connection = open(database); var delete = connection.prepareStatement("DELETE FROM application_settings WHERE key=?"))
            {
                delete.setString(1, key);
                delete.executeUpdate();
            }
            byte[] original = Files.readAllBytes(database);
            assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
            assertThrows(SQLException.class, () -> new ApplicationMigrationService().migrateCurrent(root, false, null));
            assertArrayEquals(original, Files.readAllBytes(database));
        }
    }

    @Test void strictValuesRejectCoercionDuplicatesTrailingDataAndMissingRemoteFields() throws Exception
    {
        for(String json: List.of("{}", "{\"enabled\":\"false\"}", "{\"enabled\":false,\"enabled\":true}",
            "{\"enabled\":false} {}", "{\"enabled\":false,\"unknown\":true}"))
        {
            assertThrows(SQLException.class, () -> ReceiverSettingsSchema.validateJson(ReceiverSettingsSchema.PAGING_TONES_KEY, json));
        }
        String defaults = ReceiverSettingsSchema.defaultJson(ReceiverSettingsSchema.REMOTE_LINKS_KEY);
        for(String json: List.of(defaults.replace("\"trustedSenders\":[]", "\"trustedSenders\":null"),
            defaults.replace("\"listenerEnabled\":false,", ""), defaults + " {}"))
        {
            assertThrows(SQLException.class, () -> ReceiverSettingsSchema.validateJson(ReceiverSettingsSchema.REMOTE_LINKS_KEY, json));
        }
    }

    @Test void markerlessSharedSemanticFamilyCannotBeGuessed() throws Exception
    {
        for(boolean current: List.of(false, true))
        {
            Path database = directory.resolve("markerless-" + current + ".sqlite");
            if(current) Format53TestDatabase.create(database); else Format52TestDatabase.create(database);
            try(Connection connection = open(database); var sql = connection.createStatement())
            {
                sql.executeUpdate("DELETE FROM database_metadata WHERE key='database_format_version'");
                assertThrows(SQLException.class, () -> DatabaseFormatCatalog.inspect(connection));
                assertThrows(SQLException.class, () -> DatabaseFormatCatalog.inspectForMigration(connection));
            }
        }
    }

    private static void assertDefaults(Connection connection) throws SQLException
    {
        ReceiverSettingsSchema.validate(connection);
        try(var query = connection.prepareStatement("SELECT settings_json FROM application_settings WHERE key=?"))
        {
            for(String key: ReceiverSettingsSchema.KEYS)
            {
                query.setString(1, key);
                try(var row = query.executeQuery())
                {
                    assertTrue(row.next());
                    assertEquals(ReceiverSettingsSchema.defaultJson(key), row.getString(1));
                }
            }
        }
    }

    private static Map<String,List<String>> retainedContents(Connection connection) throws Exception
    {
        return contents(connection, true);
    }
    private static Map<String,List<String>> allContents(Connection connection) throws Exception
    {
        return contents(connection, false);
    }
    private static Map<String,List<String>> contents(Connection connection, boolean omitNewSettings) throws Exception
    {
        List<String> tables = new ArrayList<>();
        Map<String,List<String>> result = new LinkedHashMap<>();
        try(var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        { while(rows.next()) tables.add(rows.getString(1)); }
        for(String table: tables)
        {
            List<String> values = new ArrayList<>();
            String where = table.equals("database_metadata") ? " WHERE key<>'database_format_version'" :
                omitNewSettings && table.equals("application_settings") ?
                    " WHERE key NOT IN ('remote.links','audio.paging_tones','audio.voice_enhancement')" : "";
            try(var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT * FROM \"" + table.replace("\"", "\"\"") + "\"" + where))
            {
                while(rows.next())
                {
                    List<String> row = new ArrayList<>();
                    for(int column = 1; column <= rows.getMetaData().getColumnCount(); column++)
                    {
                        Object value = rows.getObject(column);
                        row.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value));
                    }
                    values.add(row.toString());
                }
            }
            Collections.sort(values);
            result.put(table, values);
        }
        return result;
    }
    private static void integrity(Connection connection) throws SQLException
    {
        try(var sql = connection.createStatement(); var row = sql.executeQuery("PRAGMA foreign_key_check")) { assertFalse(row.next()); }
        try(var sql = connection.createStatement(); var row = sql.executeQuery("PRAGMA quick_check")) { assertTrue(row.next()); assertEquals("ok", row.getString(1)); }
    }
    private static Connection open(Path path) throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + path); }
}
