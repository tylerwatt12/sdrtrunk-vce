/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.settings.EncryptionKeySchema;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.preference.encryption.VoiceEncryptionKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Format75To76DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test
    void initializesKeysWithoutHistoryScansOrBtreeChangesAsHistoryGrowsAndRollsBackForRetry() throws Exception
    {
        for(int historySize: new int[]{0, 20_000})
        {
            Path database = Format75TestDatabase.create(directory.resolve("prior-" + historySize + ".sqlite"));
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity");
                var statement = connection.createStatement())
            {
                attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString());
                attach.execute();
                long channel = scalar(connection, "SELECT min(id) FROM activity.receiver_channel");
                if(historySize > 0)
                {
                    statement.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<" +
                        historySize + ") INSERT INTO activity.receiver_activity_event(channel_id,observed_at_ms,action_code) " +
                        "SELECT " + channel + ",100000+i,1 FROM n");
                }
                Map<String,Long> trees = trees(connection);
                long events = scalar(connection, "SELECT count(*) FROM activity.receiver_activity_event");
                connection.setAutoCommit(false);
                AtomicInteger instructions = new AtomicInteger();
                org.sqlite.ProgressHandler.setHandler(connection, 1000, new org.sqlite.ProgressHandler()
                {
                    @Override protected int progress() { return instructions.incrementAndGet() > 100 ? 1 : 0; }
                });
                try { new Format75To76DatabaseMigration().migrate(connection); }
                finally { org.sqlite.ProgressHandler.clearHandler(connection); }
                DatabaseFormatCatalog.stamp(connection, 76);
                assertEquals(76, DatabaseFormatCatalog.inspect(connection).version());
                EncryptionKeySchema.validate(connection);
                assertEquals(trees, trees(connection));
                assertEquals(events, scalar(connection, "SELECT count(*) FROM activity.receiver_activity_event"));
                connection.rollback();
                assertEquals(75, DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(0, scalar(connection, "SELECT count(*) FROM application_settings WHERE key='voice.encryption_keys'"));
                new Format75To76DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 76);
                connection.commit();
                assertEquals(trees, trees(connection));
            }
            ConfigurationHistoryDatabase.validatePair(database);
        }
    }

    @Test
    void fullMigratorRetriesUnchangedSourceAndCurrentProfileIsByteIdenticalNoop() throws Exception
    {
        Path database = Format75TestDatabase.create(directory.resolve("retry.sqlite"));
        var original = ActivityMigrationPreservation.original(database);
        var history = ActivityMigrationPreservation.capture(database);
        assertThrows(IllegalStateException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database, phase -> {
            if(phase.equals("Validating the updated schema and configuration")) throw new IllegalStateException("injected");
        }));
        assertEquals(original, ActivityMigrationPreservation.original(database));
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        ActivityMigrationPreservation.assertCurrent(database, history);
        var configuredKey = new VoiceEncryptionKey();
        configuredKey.setAlgorithmId(0xAA);
        configuredKey.setKeyId(42);
        configuredKey.setKeyHex("0102030405");
        var settings = new ApplicationSettingsStore(database);
        settings.save(EncryptionKeySchema.KEY, EncryptionKeySchema.encode(java.util.List.of(configuredKey)));
        byte[] current = Files.readAllBytes(database);
        byte[] activity = Files.readAllBytes(ActivityDatabasePath.forConfiguration(database));
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        assertArrayEquals(current, Files.readAllBytes(database));
        assertArrayEquals(activity, Files.readAllBytes(ActivityDatabasePath.forConfiguration(database)));
        assertEquals("0102030405", EncryptionKeySchema.decode(settings.loadJson(EncryptionKeySchema.KEY,
            EncryptionKeySchema.MAXIMUM_BYTES).orElseThrow()).getFirst().getKeyHex());
    }

    private static Map<String,Long> trees(Connection connection) throws SQLException
    {
        Map<String,Long> result = new TreeMap<>();
        try(var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT name,rootpage FROM activity.sqlite_schema WHERE type IN ('table','index') ORDER BY name"))
        { while(rows.next()) result.put(rows.getString(1), rows.getLong(2)); }
        return result;
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
        { assertTrue(rows.next()); return rows.getLong(1); }
    }
}
