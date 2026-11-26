/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SqliteSchemaValidator;
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

class Format76To77DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test
    void defaultsOnlyChannelPreferencePreservesHistoryBtreesAndRollsBackForRetryAsHistoryGrows() throws Exception
    {
        for(int historySize: new int[]{0, 20_000})
        {
            Path database = Format76TestDatabase.create(directory.resolve("prior-" + historySize + ".sqlite"));
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity");
                var statement = connection.createStatement())
            {
                attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString());
                attach.execute();
                long channel = scalar(connection, "SELECT min(id) FROM activity.receiver_channel");
                if(historySize > 0)
                    statement.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<" +
                        historySize + ") INSERT INTO activity.receiver_activity_event(channel_id,observed_at_ms,action_code) " +
                        "SELECT " + channel + ",100000+i,1 FROM n");
                assertEquals(2 * Files.size(database) + 64L * 1024 * 1024,
                    ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL),
                    "This bounded configuration step must not budget a retained-history copy");
                Map<String,Long> beforeTrees = trees(connection);
                String rows = channelRows(connection);
                long events = scalar(connection, "SELECT count(*) FROM activity.receiver_activity_event");
                connection.setAutoCommit(false);
                AtomicInteger instructions = new AtomicInteger();
                org.sqlite.ProgressHandler.setHandler(connection, 1000, new org.sqlite.ProgressHandler()
                {
                    @Override protected int progress() { return instructions.incrementAndGet() > 100 ? 1 : 0; }
                });
                try { new Format76To77DatabaseMigration().migrate(connection); }
                finally { org.sqlite.ProgressHandler.clearHandler(connection); }
                DatabaseFormatCatalog.stampForMigration(connection, 77);
                assertEquals(77, DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(DatabaseFormatCatalog.requireVersion(77).fingerprint(),
                    SqliteSchemaValidator.fingerprint(connection));
                assertEquals(0, scalar(connection,
                    "SELECT count(*) FROM configuration_channel WHERE use_auto_start_order_for_audio<>0"));
                assertEquals(rows, channelRows(connection));
                assertEquals(beforeTrees, trees(connection));
                assertEquals(events, scalar(connection, "SELECT count(*) FROM activity.receiver_activity_event"));
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE configuration_channel SET use_auto_start_order_for_audio=2"));
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE configuration_channel SET config_json=json_set(config_json,'$.useAutoStartOrderForAudio',json('true'))"));
                connection.rollback();
                assertEquals(76, DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(rows, channelRows(connection));
                assertThrows(SQLException.class, () -> scalar(connection,
                    "SELECT use_auto_start_order_for_audio FROM configuration_channel"));
                new Format76To77DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stampForMigration(connection, 77);
                assertEquals(77, DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(DatabaseFormatCatalog.requireVersion(77).fingerprint(),
                    SqliteSchemaValidator.fingerprint(connection));
                connection.commit();
                assertEquals(beforeTrees, trees(connection));
            }
            ConfigurationHistoryDatabase.validatePairIdentity(database);
            for(Path role: java.util.List.of(database, ActivityDatabasePath.forConfiguration(database)))
                try(var connection = DriverManager.getConnection("jdbc:sqlite:" + role);
                    var statement = connection.createStatement())
                {
                    assertEquals(77, DatabaseFormatCatalog.inspect(connection).version());
                    try(var rows = statement.executeQuery("PRAGMA quick_check"))
                    { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); assertFalse(rows.next()); }
                    try(var rows = statement.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
                }
        }
    }

    @Test
    void fullMigratorRetriesUnchangedPairAndCurrentOptInSurvivesByteIdenticalNoop() throws Exception
    {
        Path database = Format76TestDatabase.create(directory.resolve("retry.sqlite"));
        var original = ActivityMigrationPreservation.original(database);
        var history = ActivityMigrationPreservation.capture(database);
        assertThrows(IllegalStateException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database, phase -> {
            if(phase.equals("Validating the updated schema and configuration")) throw new IllegalStateException("injected");
        }));
        assertEquals(original, ActivityMigrationPreservation.original(database));
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        ActivityMigrationPreservation.assertCurrent(database, history);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE configuration_channel SET use_auto_start_order_for_audio=1");
        }
        byte[] configuration = Files.readAllBytes(database);
        byte[] activity = Files.readAllBytes(ActivityDatabasePath.forConfiguration(database));
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        assertArrayEquals(configuration, Files.readAllBytes(database));
        assertArrayEquals(activity, Files.readAllBytes(ActivityDatabasePath.forConfiguration(database)));
    }

    @Test
    void currentChannelRepairPreservesEnabledAudioPreference() throws Exception
    {
        Path database = CurrentFormatTestDatabase.create(directory.resolve("repair.sqlite"));
        long channels;
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            channels = scalar(connection, "SELECT count(*) FROM configuration_channel");
            assertTrue(channels > 0);
            statement.executeUpdate("UPDATE configuration_channel SET use_auto_start_order_for_audio=1");
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE configuration_channel SET sort_order=-1 " +
                "WHERE id=(SELECT min(id) FROM configuration_channel)");
        }
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(channels, scalar(connection,
                "SELECT count(*) FROM configuration_channel WHERE use_auto_start_order_for_audio=1"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM configuration_channel WHERE sort_order<0"));
        }
        ConfigurationHistoryDatabase.validatePair(database);
    }

    private static String channelRows(Connection connection) throws SQLException
    {
        StringBuilder value = new StringBuilder();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT id,configuration_id,auto_start,auto_start_order,config_json FROM configuration_channel ORDER BY id"))
        {
            while(rows.next()) for(int column=1; column<=5; column++) value.append(rows.getObject(column)).append('\n');
        }
        return value.toString();
    }

    private static Map<String,Long> trees(Connection connection) throws SQLException
    {
        Map<String,Long> result = new TreeMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT 'main.'||name,rootpage FROM main.sqlite_schema WHERE type IN ('table','index') UNION ALL " +
                "SELECT 'activity.'||name,rootpage FROM activity.sqlite_schema WHERE type IN ('table','index') ORDER BY 1"))
        { while(rows.next()) result.put(rows.getString(1), rows.getLong(2)); }
        return result;
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
        { assertTrue(rows.next()); return rows.getLong(1); }
    }
}
