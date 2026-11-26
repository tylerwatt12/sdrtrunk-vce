/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.SQLiteConnection;

class Format77To78DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test
    void changesOnlyMatchingRoleMarkersAndPreservesChoicesAcrossRollbackAndRetry() throws Exception
    {
        Path database = populated77("adjacent.sqlite");
        assertEquals(2 * Files.size(database) + 64L * 1024 * 1024,
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL));
        try(var connection = openPair(database))
        {
            var source = DatabaseFormatCatalog.inspectForMigration(connection);
            assertEquals(77, source.version());
            var plan = DatabaseMigrationChain.planInPlace(connection, source);
            assertEquals(List.of("broadcastify-health-reporting-v1"), plan.steps().stream().map(
                DatabaseMigrationChain.StepPreflight::id).toList());
            assertTrue(plan.steps().stream().allMatch(ApplicationMigrationService::changesOnlyBoundedProfileData));
            Map<String,List<String>> before = retainedRows(connection);
            Map<String,Long> originalTrees = trees(connection);
            assertChoices(connection, database);
            var writes = new ArrayList<String>();
            ((SQLiteConnection)connection).addUpdateListener((type, schema, table, row) -> writes.add(schema + "." + table));
            connection.setAutoCommit(false);
            migrate(connection);
            assertEquals(List.of("activity.database_metadata", "main.database_metadata"), writes,
                "The semantic step may write only the two format metadata rows");
            assertEquals(before, retainedRows(connection));
            assertEquals(originalTrees, trees(connection));
            assertChoices(connection, database);
            connection.rollback();
            assertEquals(77, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("77", ConfigurationHistorySchema.metadata(connection, "activity",
                DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals(before, retainedRows(connection));
            writes.clear();
            migrate(connection);
            connection.commit();
            assertEquals(List.of("activity.database_metadata", "main.database_metadata"), writes);
            assertEquals(before, retainedRows(connection));
            assertEquals(originalTrees, trees(connection));
        }
        ConfigurationHistoryDatabase.validatePair(database);
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database);
        try(var connection = openPair(database))
        {
            assertEquals(DatabaseFormatCatalog.requireVersion(77).fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(DatabaseFormatCatalog.requireVersion(78).fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(ConfigurationHistoryDatabase.historyFingerprint(77), SqliteSchemaValidator.fingerprint(connection, "activity"));
            assertEquals(ConfigurationHistoryDatabase.historyFingerprint(78), SqliteSchemaValidator.fingerprint(connection, "activity"));
            assertIntegrity(connection);
        }
    }

    @Test
    void ownedUpgradeRetriesUnchangedSourceAndCurrentNoopPreservesBothFiles() throws Exception
    {
        Path database = populated77("owned.sqlite");
        Map<String,List<String>> before;
        try(var connection = openPair(database)) { before = retainedRows(connection); }
        assertThrows(IllegalStateException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database, phase -> {
            if(phase.equals("Validating the updated schema and configuration")) throw new IllegalStateException("injected");
        }));
        try(var connection = openPair(database))
        {
            assertEquals(77, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("77", ConfigurationHistorySchema.metadata(connection, "activity",
                DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            assertEquals(before, retainedRows(connection));
        }
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        try(var connection = openPair(database))
        {
            assertEquals(78, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals(before, retainedRows(connection));
            assertChoices(connection, database);
            assertIntegrity(connection);
        }
        byte[] configuration = Files.readAllBytes(database);
        byte[] activity = Files.readAllBytes(ActivityDatabasePath.forConfiguration(database));
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        assertArrayEquals(configuration, Files.readAllBytes(database));
        assertArrayEquals(activity, Files.readAllBytes(ActivityDatabasePath.forConfiguration(database)));
    }

    @Test
    void refusesMissingMismatchedOrAdvancedCompanionsBeforeAnyMarkerWrite() throws Exception
    {
        for(String defect: List.of("missing", "identity", "advanced"))
        {
            Path database = populated77("refuse-" + defect + ".sqlite");
            Path history = ActivityDatabasePath.forConfiguration(database);
            if(!defect.equals("missing"))
            {
                try(var connection = open(history); var update = connection.prepareStatement(
                    "UPDATE database_metadata SET value=? WHERE key=?"))
                {
                    update.setString(1, defect.equals("identity") ? "00000000-0000-0000-0000-000000000078" : "78");
                    update.setString(2, defect.equals("identity") ? ConfigurationHistorySchema.PAIR_ID_KEY :
                        DatabaseFormatCatalog.FORMAT_VERSION_KEY);
                    assertEquals(1, update.executeUpdate());
                }
            }
            byte[] originalConfiguration = Files.readAllBytes(database), originalHistory = Files.readAllBytes(history);
            try(var connection = defect.equals("missing") ? open(database) : openPair(database))
            {
                connection.setAutoCommit(false);
                assertThrows(SQLException.class, () -> new Format77To78DatabaseMigration().migrate(connection));
                connection.rollback();
            }
            assertArrayEquals(originalConfiguration, Files.readAllBytes(database));
            assertArrayEquals(originalHistory, Files.readAllBytes(history));
        }
    }

    @Test
    void frozen77NeedsMigrationAndFresh78AndNewerMarkersFollowStartupAdmission() throws Exception
    {
        Path prior = Format77TestDatabase.create(directory.resolve("frozen77.sqlite"));
        byte[] priorConfiguration = Files.readAllBytes(prior);
        byte[] priorHistory = Files.readAllBytes(ActivityDatabasePath.forConfiguration(prior));
        try(var connection = openPair(prior))
        {
            assertEquals(77, DatabaseFormatCatalog.inspect(connection).version());
            assertThrows(SQLException.class, () -> DatabaseFormatCatalog.requireCurrent(connection));
            assertThrows(SQLException.class, () -> new Format77To78DatabaseMigration().migrate(connection),
                "Even a semantic step requires the migrator-owned transaction");
        }
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(prior));
        assertArrayEquals(priorConfiguration, Files.readAllBytes(prior));
        assertArrayEquals(priorHistory, Files.readAllBytes(ActivityDatabasePath.forConfiguration(prior)));

        Path current = Format78TestDatabase.create(directory.resolve("current78.sqlite"));
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(current);
        try(var connection = open(current); var statement = connection.createStatement())
        { statement.executeUpdate("UPDATE database_metadata SET value='79' WHERE key='database_format_version'"); }
        byte[] future = Files.readAllBytes(current);
        try(var connection = open(current))
        {
            var rejected = assertThrows(SQLException.class, () -> DatabaseFormatCatalog.inspect(connection));
            assertTrue(rejected.getMessage().contains("newer"));
        }
        assertArrayEquals(future, Files.readAllBytes(current));
    }

    private Path populated77(String filename) throws Exception
    {
        Path database = Format77TestDatabase.create(directory.resolve(filename));
        try(var connection = open(database); var insert = connection.prepareStatement(
            "INSERT INTO configuration_broadcast_stream(configuration_id,sort_order,config_json) VALUES(?,?,?)"))
        {
            int sequence = 100;
            for(String type: List.of("broadcastifyCallConfiguration", "broadcastifyCallSiteConfiguration"))
            {
                for(String choice: List.of("absent", "false", "true"))
                {
                    sequence++;
                    insert.setString(1, "00000000-0000-0000-0000-" + String.format("%012d", sequence));
                    insert.setInt(2, sequence);
                    insert.setString(3, " { \"type\":\"" + type + "\", \"name\":\"choice-" + type + "-" + choice + "\"" +
                        (choice.equals("absent") ? "" : ", \"healthReports\": " + choice) + " } \n");
                    insert.executeUpdate();
                }
            }
            insert.setString(1, "00000000-0000-0000-0000-000000000200");
            insert.setInt(2, 200);
            insert.setString(3, " {\"type\":\"OpenMHzConfiguration\", \"name\":\"Unrelated provider\"," +
                " \"host\":\"https://example.invalid\"} \n");
            insert.executeUpdate();
        }
        return database;
    }

    private static void assertChoices(Connection connection, Path database) throws Exception
    {
        var configurations = new ConfigurationDatabaseStore(database).load(connection).broadcastConfigurations();
        int choices = 0;
        for(var provider: configurations)
        {
            if(provider.getName().startsWith("choice-"))
            {
                choices++;
                var calls = assertInstanceOf(BroadcastifyCallConfiguration.class, provider);
                assertEquals(!provider.getName().endsWith("-false"), calls.isHealthReports());
            }
        }
        assertEquals(6, choices);
    }

    private static void migrate(Connection connection) throws SQLException
    {
        var report = DatabaseMigrationChain.migrate(connection, DatabaseFormatCatalog.inspectForMigration(connection),
            false, ignored -> {}, ignored -> {}, 78);
        assertEquals(List.of("broadcastify-health-reporting-v1"), report.steps().stream().map(
            DatabaseMigrationChain.StepReport::id).toList());
        assertEquals(78, DatabaseFormatCatalog.requireCurrent(connection).version());
        assertEquals("78", ConfigurationHistorySchema.metadata(connection, "activity", DatabaseFormatCatalog.FORMAT_VERSION_KEY));
    }

    private static Map<String,List<String>> retainedRows(Connection connection) throws SQLException
    {
        var result = new TreeMap<String,List<String>>();
        for(String schema: List.of("main", "activity"))
        {
            var tables = new ArrayList<String>();
            try(var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT name FROM " + schema + ".sqlite_schema WHERE type='table' ORDER BY name"))
            { while(rows.next()) tables.add(rows.getString(1)); }
            for(String table: tables)
            {
                var values = new ArrayList<String>();
                try(var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT * FROM " + schema + ".\"" + table.replace("\"", "\"\"") + "\"" +
                        (table.equals("database_metadata") ? " WHERE key<>'database_format_version'" : "")))
                {
                    while(rows.next())
                    {
                        var row = new StringBuilder();
                        for(int column = 1; column <= rows.getMetaData().getColumnCount(); column++)
                        {
                            Object value = rows.getObject(column);
                            String text = value == null ? "null" : value instanceof byte[] bytes ?
                                HexFormat.of().formatHex(bytes) : value.getClass().getSimpleName() + ":" + value;
                            row.append(text.length()).append(':').append(text);
                        }
                        values.add(row.toString());
                    }
                }
                Collections.sort(values);
                result.put(schema + "." + table, values);
            }
        }
        return result;
    }

    private static Map<String,Long> trees(Connection connection) throws SQLException
    {
        var result = new TreeMap<String,Long>();
        for(String schema: List.of("main", "activity"))
            try(var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT name,rootpage FROM " + schema + ".sqlite_schema WHERE type IN ('table','index')"))
            { while(rows.next()) result.put(schema + "." + rows.getString(1), rows.getLong(2)); }
        return result;
    }

    private static void assertIntegrity(Connection connection) throws SQLException
    {
        for(String schema: List.of("main", "activity"))
        {
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA " + schema + ".quick_check"))
            { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); assertFalse(rows.next()); }
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA " + schema + ".foreign_key_check"))
            { assertFalse(rows.next()); }
        }
    }

    private static Connection open(Path database) throws SQLException
    { return DriverManager.getConnection("jdbc:sqlite:" + database); }

    private static Connection openPair(Path database) throws SQLException
    {
        Connection connection = open(database);
        try(var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
        {
            attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString());
            attach.execute();
        }
        catch(SQLException failure) { connection.close(); throw failure; }
        return connection;
    }
}
