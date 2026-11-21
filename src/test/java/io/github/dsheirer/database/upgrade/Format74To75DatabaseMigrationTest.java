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
import java.util.List;
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
            assertEquals(ConfigurationHistoryDatabase.historyFingerprint(75),
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
        assertExactFormat75Pair(database);
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

    @Test
    void populatedRegroupAdditionHasFixedWorkAndMatchesNativeConstraintsAfterReopen() throws Exception
    {
        Path database = Format74TestDatabase.create(directory.resolve("bounded-patches.sqlite"));
        List<String> tables = List.of("p25_site_patch_group", "p25_site_patch_group_summary");
        String column = "regroup_type INTEGER NOT NULL DEFAULT 0 " +
            "CHECK(typeof(regroup_type) = 'integer' AND regroup_type IN (0, 1, 2))";
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var nativeSchema = DriverManager.getConnection("jdbc:sqlite::memory:");
            var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity");
            var statement = connection.createStatement(); var nativeStatement = nativeSchema.createStatement())
        {
            attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString()); attach.execute();
            statement.executeUpdate("INSERT OR IGNORE INTO activity.p25_site_snapshot" +
                "(channel_id,first_seen_ms,last_seen_ms,protocol) " +
                "SELECT id,1000,1000,'APCO25' FROM activity.receiver_channel LIMIT 1");
            long channel = scalar(connection, "SELECT min(channel_id) FROM activity.p25_site_snapshot");
            connection.setAutoCommit(false);
            statement.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<20000) " +
                "INSERT OR REPLACE INTO activity.p25_site_patch_group " +
                "(channel_id,local_patch_group_id,version,confirmed_at_ms) " +
                "SELECT " + channel + ",i,9,1000 FROM n");
            statement.executeUpdate("INSERT OR REPLACE INTO activity.p25_site_patch_group_summary " +
                "(channel_id,local_patch_group_id,version,first_seen_ms,last_seen_ms,observation_count) " +
                "SELECT channel_id,local_patch_group_id,version,1000,2000,3 FROM activity.p25_site_patch_group");
            connection.commit(); connection.setAutoCommit(true);
            Map<String,Long> originalTrees = trees(connection);
            // Match the owned migrator's temporary legacy-repair CHECK policy: it does not prevent the scan.
            statement.execute("PRAGMA ignore_check_constraints=ON");
            for(String table: tables)
            {
                assertTrue(scalar(connection, "SELECT count(*) FROM activity." + table) >= 20000);
                nativeStatement.executeUpdate(tableDefinition(connection, "activity", table));
                nativeStatement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + column);
                // Negative control: the former checked ADD exceeds the same fixed budget on either table.
                installWorkBudget(connection);
                try
                {
                    SQLException failure = assertThrows(SQLException.class,
                        () -> statement.executeUpdate("ALTER TABLE activity." + table + " ADD COLUMN " + column));
                    assertEquals(9, failure.getErrorCode(), "Native CHECK addition must hit SQLITE_INTERRUPT");
                }
                finally { org.sqlite.ProgressHandler.clearHandler(connection); }
            }
            connection.setAutoCommit(false);
            var step = new Format74To75DatabaseMigration();
            step.validateSource(connection);
            installWorkBudget(connection);
            try
            {
                assertDoesNotThrow(() -> step.migrateSource(connection),
                    "Regroup additions must not scan retained patch history");
            }
            finally { org.sqlite.ProgressHandler.clearHandler(connection); }
            DatabaseFormatCatalog.stamp(connection, 75); connection.commit();
            assertEquals(originalTrees, trees(connection));
            assertEquals(ConfigurationHistoryDatabase.historyFingerprint(75),
                SqliteSchemaValidator.fingerprint(connection, "activity"));
            assertEquals(0, scalar(connection, "PRAGMA writable_schema"));
            for(String table: tables)
            {
                SqliteSchemaValidator.validateDefinitions(connection, List.of(new SqliteSchemaValidator.Definition(
                    "table", table, tableDefinition(nativeSchema, "main", table))), "activity");
                assertEquals(columnLayout(nativeSchema, "main", table), columnLayout(connection, "activity", table));
            }
        }
        try(var reopened = DriverManager.getConnection("jdbc:sqlite:" + ActivityDatabasePath.forConfiguration(database));
            var statement = reopened.createStatement())
        {
            for(String table: tables)
            {
                assertEquals(0, scalar(reopened, "SELECT count(*) FROM " + table + " WHERE regroup_type IS NOT 0"));
                statement.executeUpdate("UPDATE " + table + " SET regroup_type=2 WHERE local_patch_group_id=1");
                for(String invalid: List.of("3", "1.5", "NULL"))
                    assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE " + table +
                        " SET regroup_type=" + invalid + " WHERE local_patch_group_id=1"));
            }
            try(var rows = statement.executeQuery("PRAGMA quick_check"))
            { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); assertFalse(rows.next()); }
            try(var rows = statement.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
        }
        assertExactFormat75Pair(database);
    }

    private static void assertExactFormat75Pair(Path database) throws Exception
    {
        // This adjacent-step test stops at frozen format 75, before current-only settings are installed.
        ConfigurationHistoryDatabase.validatePairIdentity(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(75, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(DatabaseFormatCatalog.requireVersion(75).fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals(0, scalar(connection,
                "SELECT count(*) FROM application_settings WHERE key='voice.encryption_keys'"));
        }
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + ActivityDatabasePath.forConfiguration(database)))
        {
            assertEquals(75, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(ConfigurationHistoryDatabase.historyFingerprint(75),
                SqliteSchemaValidator.fingerprint(connection));
        }
    }

    private static void installWorkBudget(Connection connection) throws SQLException
    {
        var instructions = new java.util.concurrent.atomic.AtomicInteger();
        org.sqlite.ProgressHandler.setHandler(connection, 1000, new org.sqlite.ProgressHandler()
        {
            @Override protected int progress() { return instructions.incrementAndGet() > 100 ? 1 : 0; }
        });
    }

    private static String tableDefinition(Connection connection, String schema, String table) throws SQLException
    {
        try(var statement = connection.createStatement(); var row = statement.executeQuery(
            "SELECT sql FROM " + schema + ".sqlite_schema WHERE type='table' AND name='" + table + "'"))
        { assertTrue(row.next()); return row.getString(1); }
    }

    private static List<String> columnLayout(Connection connection, String schema, String table) throws SQLException
    {
        List<String> result = new java.util.ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "PRAGMA " + schema + ".table_xinfo(" + table + ")"))
        {
            while(rows.next()) result.add(rows.getInt("cid") + "|" + rows.getString("name") + "|" +
                rows.getString("type") + "|" + rows.getInt("notnull") + "|" + rows.getString("dflt_value") +
                "|" + rows.getInt("pk") + "|" + rows.getInt("hidden"));
        }
        return result;
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
