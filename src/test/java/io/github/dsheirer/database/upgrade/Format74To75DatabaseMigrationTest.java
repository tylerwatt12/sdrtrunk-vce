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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Format74To75DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test
    void preservesPatchRowsAndPagesWithUnknownTypeAndRollsBackForRetry() throws Exception
    {
        Path database = Format74TestDatabase.create(directory.resolve("prior.sqlite"));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity");
            var statement = connection.createStatement())
        {
            attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString());
            attach.execute();
            statement.executeUpdate("""
                INSERT OR IGNORE INTO activity.p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms,protocol)
                SELECT id,1000,1000,'APCO25' FROM activity.receiver_channel LIMIT 1
                """);
            long channel = scalar(connection, "SELECT min(channel_id) FROM activity.p25_site_snapshot");
            statement.executeUpdate("INSERT OR REPLACE INTO activity.p25_site_patch_group " +
                "(channel_id,local_patch_group_id,version,confirmed_at_ms) VALUES (" + channel + ",500,9,1000)");
            statement.executeUpdate("INSERT OR REPLACE INTO activity.p25_site_patch_group_summary " +
                "(channel_id,local_patch_group_id,version,first_seen_ms,last_seen_ms,observation_count) VALUES (" +
                channel + ",500,9,1000,2000,3)");
            Map<String,Long> trees = trees(connection);
            connection.setAutoCommit(false);
            new Format74To75DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 75);
            assertEquals(ConfigurationHistoryDatabase.HISTORY_FINGERPRINT,
                SqliteSchemaValidator.fingerprint(connection, "activity"));
            assertEquals(75, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(0, scalar(connection, "SELECT regroup_type FROM activity.p25_site_patch_group WHERE local_patch_group_id=500"));
            assertEquals(0, scalar(connection, "SELECT regroup_type FROM activity.p25_site_patch_group_summary WHERE local_patch_group_id=500"));
            assertEquals(trees, trees(connection));
            connection.rollback();
            assertEquals(74, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(ConfigurationHistoryDatabase.FORMAT_74_HISTORY_FINGERPRINT,
                SqliteSchemaValidator.fingerprint(connection, "activity"));
            new Format74To75DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 75);
            connection.commit();
            assertEquals(trees, trees(connection));
            assertEquals(3, scalar(connection, "SELECT observation_count FROM activity.p25_site_patch_group_summary WHERE local_patch_group_id=500"));
            for(String table: java.util.List.of("p25_site_patch_group", "p25_site_patch_group_summary"))
            {
                statement.executeUpdate("UPDATE activity." + table + " SET regroup_type=2 WHERE local_patch_group_id=500");
                assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE activity." + table +
                    " SET regroup_type=3 WHERE local_patch_group_id=500"));
                assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE activity." + table +
                    " SET regroup_type=1.5 WHERE local_patch_group_id=500"));
                assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE activity." + table +
                    " SET regroup_type=NULL WHERE local_patch_group_id=500"));
            }
            connection.rollback();
            try(var rows = statement.executeQuery("PRAGMA activity.quick_check"))
            {
                assertTrue(rows.next());
                assertEquals("ok", rows.getString(1));
            }
            try(var rows = statement.executeQuery("PRAGMA activity.foreign_key_check"))
            {
                assertFalse(rows.next());
            }
        }
        ConfigurationHistoryDatabase.validatePair(database);
    }

    @Test
    void fullMigratorPreservesHistoryAndRetriesUnchangedSource() throws Exception
    {
        Path database = Format74TestDatabase.create(directory.resolve("retry.sqlite"));
        var original = ActivityMigrationPreservation.original(database);
        var history = ActivityMigrationPreservation.capture(database);
        assertThrows(IllegalStateException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database, phase -> {
            if(phase.equals("Validating the updated schema and configuration")) throw new IllegalStateException("injected");
        }));
        assertEquals(original, ActivityMigrationPreservation.original(database));
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        ActivityMigrationPreservation.assertCurrent(database, history);
        byte[] current = Files.readAllBytes(database);
        byte[] currentActivity = Files.readAllBytes(ActivityDatabasePath.forConfiguration(database));
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        assertArrayEquals(current, Files.readAllBytes(database));
        assertArrayEquals(currentActivity, Files.readAllBytes(ActivityDatabasePath.forConfiguration(database)));
    }

    private static Map<String,Long> trees(Connection connection) throws SQLException
    {
        Map<String,Long> result = new TreeMap<>();
        try(var statement = connection.createStatement();
            var rows = statement.executeQuery("SELECT name,rootpage FROM activity.sqlite_schema WHERE type IN ('table','index') ORDER BY name"))
        {
            while(rows.next()) result.put(rows.getString(1), rows.getLong(2));
        }
        return result;
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }
}
