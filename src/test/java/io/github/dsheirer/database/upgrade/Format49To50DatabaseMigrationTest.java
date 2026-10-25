/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.ToneActivitySchema;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format49To50DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void freshAndPopulatedMigrationHaveExactSignaturePreserveAllRowsIndexesStatisticsAndAllocators() throws Exception
    {
        Path prior = Format49TestDatabase.create(directory.resolve("prior.sqlite"));
        try(Connection connection = open(prior); var sql = connection.createStatement())
        {
            assertTrue(count(connection, "configuration_channel") > 0);
            assertTrue(count(connection, "receiver_activity_event") > 0, "Prior fixture must exercise retained Activity");
            sql.executeUpdate("UPDATE sqlite_sequence SET seq=seq+5000 WHERE name='receiver_activity_event'");
            sql.execute("ANALYZE receiver_activity_event");
            Map<String,List<String>> before = contents(connection);
            List<String> indexes = indexDefinitions(connection);
            List<String> creationOrder = indexCreationOrder(connection);
            long highWater = scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='receiver_activity_event'");
            connection.setAutoCommit(false);
            new Format49To50DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 50); connection.commit();
            assertEquals(before, contents(connection));
            assertEquals(indexes, indexDefinitions(connection));
            assertEquals(creationOrder,indexCreationOrder(connection));
            assertEquals(highWater, scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='receiver_activity_event'"));
            assertEquals(0, count(connection, "receiver_activity_tone_sequence"));
            assertEquals(0, count(connection, "receiver_activity_tone"));
            assertEquals(50, DatabaseFormatCatalog.inspect(connection).version());
            integrity(connection);
            assertEquals(DatabaseFormatCatalog.requireVersion(50).fingerprint(), SqliteSchemaValidator.fingerprint(connection));
        }
        assertThrows(java.io.IOException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(prior));
        Path fresh = directory.resolve("fresh.sqlite"); SdrTrunkDatabaseStartup.createGlobalDatabase(fresh);
        try(Connection connection = open(fresh))
        { assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection)); integrity(connection); }
    }

    @Test void rollbackPreservesExactPriorFileAndAllowsRetry() throws Exception
    {
        Path prior = Format49TestDatabase.create(directory.resolve("rollback.sqlite"));
        byte[] original = Files.readAllBytes(prior);
        try(Connection connection = open(prior))
        {
            connection.setAutoCommit(false); new Format49To50DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 50); connection.rollback();
            assertEquals(49, DatabaseFormatCatalog.inspect(connection).version());
        }
        assertArrayEquals(original, Files.readAllBytes(prior));
        try(Connection connection = open(prior))
        {
            connection.setAutoCommit(false); new Format49To50DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 50); connection.commit();
            assertEquals(50, DatabaseFormatCatalog.inspect(connection).version()); integrity(connection);
        }
    }

    @Test void emptiedEventTablePreservesItsRetiredIdHighWater() throws Exception
    {
        Path prior = Format49TestDatabase.create(directory.resolve("empty-history.sqlite"));
        try(Connection connection = open(prior); var sql = connection.createStatement())
        {
            sql.execute("PRAGMA foreign_keys=ON"); sql.executeUpdate("DELETE FROM receiver_activity_event");
            sql.executeUpdate("UPDATE sqlite_sequence SET seq=6000000000 WHERE name='receiver_activity_event'");
            sql.execute("PRAGMA foreign_keys=OFF");
            connection.setAutoCommit(false); new Format49To50DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 50); connection.commit();
            assertEquals(6000000000L, scalar(connection,"SELECT seq FROM sqlite_sequence WHERE name='receiver_activity_event'"));
            assertEquals(0,count(connection,"receiver_activity_event")); integrity(connection);
        }
    }

    @Test void runtimeRefusesOldMixedAndPopulatedForeignKeyRebuildWithoutMutation() throws Exception
    {
        Path prior = Format49TestDatabase.create(directory.resolve("refused.sqlite"));
        byte[] original = Files.readAllBytes(prior);
        assertThrows(java.io.IOException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(prior));
        assertArrayEquals(original, Files.readAllBytes(prior));
        try(Connection connection = open(prior); var sql = connection.createStatement())
        {
            sql.execute("PRAGMA foreign_keys=ON");
            assertThrows(SQLException.class, () -> ToneActivitySchema.upgradeFrom49(connection));
            assertEquals(49, DatabaseFormatCatalog.inspect(connection).version());
            sql.execute("PRAGMA foreign_keys=OFF");
            sql.execute("CREATE TABLE receiver_activity_tone_sequence(event_id INTEGER PRIMARY KEY)");
            var before = contents(connection);
            assertThrows(SQLException.class, () -> CurrentFormatTestDatabase.migrateToVersion(connection, 62));
            assertEquals(before, contents(connection));
        }
    }

    private static Map<String,List<String>> contents(Connection connection) throws Exception
    {
        List<String> tables = new ArrayList<>(); Map<String,List<String>> result = new LinkedHashMap<>();
        try(var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' AND name NOT IN ('receiver_activity_tone','receiver_activity_tone_sequence') ORDER BY name"))
        { while(rows.next()) tables.add(rows.getString(1)); }
        for(String table: tables)
        {
            List<String> values = new ArrayList<>();
            try(var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT * FROM \"" + table.replace("\"", "\"\"") + "\"" +
                (table.equals("database_metadata") ? " WHERE key<>'database_format_version'" : "")))
            {
                while(rows.next())
                {
                    var row = new ArrayList<String>();
                    for(int column=1; column<=rows.getMetaData().getColumnCount(); column++)
                    {
                        Object value = rows.getObject(column);
                        row.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value));
                    }
                    values.add(row.toString());
                }
            }
            Collections.sort(values); result.put(table, values);
        }
        return result;
    }
    private static List<String> indexDefinitions(Connection connection) throws Exception
    {
        List<String> result = new ArrayList<>();
        try(var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT name||':'||sql FROM sqlite_schema WHERE type='index' AND tbl_name='receiver_activity_event' AND sql IS NOT NULL ORDER BY name"))
        { while(rows.next()) result.add(rows.getString(1)); }
        return result;
    }
    private static List<String> indexCreationOrder(Connection connection) throws Exception
    {
        var result = new ArrayList<String>();
        try(var sql=connection.createStatement();var rows=sql.executeQuery("SELECT name FROM sqlite_schema WHERE type='index' AND tbl_name='receiver_activity_event' AND sql IS NOT NULL ORDER BY rowid"))
        { while(rows.next()) result.add(rows.getString(1)); }
        return result;
    }
    private static long count(Connection connection, String table) throws Exception
    { return scalar(connection,"SELECT count(*) FROM " + table); }
    private static long scalar(Connection connection, String query) throws Exception
    { try(var sql = connection.createStatement(); var row = sql.executeQuery(query)) { assertTrue(row.next()); return row.getLong(1); } }
    private static void integrity(Connection connection) throws Exception
    {
        try(var sql = connection.createStatement(); var row = sql.executeQuery("PRAGMA foreign_key_check")) { assertFalse(row.next()); }
        try(var sql = connection.createStatement(); var row = sql.executeQuery("PRAGMA quick_check")) { assertTrue(row.next()); assertEquals("ok",row.getString(1)); }
    }
    private static Connection open(Path path) throws Exception { return DriverManager.getConnection("jdbc:sqlite:" + path); }
}
