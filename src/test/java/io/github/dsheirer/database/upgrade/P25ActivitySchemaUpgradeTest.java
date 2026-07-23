/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P25ActivitySchemaUpgradeTest
{
    private static final String VERSION_KEY = "p25_activity_schema_version";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void migratesV19StagedCopyAndPreservesExistingData() throws Exception
    {
        Path database = createV19Database("migrate.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias(sort_order, name) VALUES (0, 'Dispatch')");
        }

        CommandResult result = run(database);

        assertEquals(P25ActivitySchemaUpgrade.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("RESULT: P25 activity schema upgrade complete: v19 -> v21"));
        assertTrue(result.error().isEmpty());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("21", metadata(connection, VERSION_KEY));
            assertTrue(tableExists(connection, "p25_foreign_system_band"));
            assertTrue(tableExists(connection, "p25_foreign_system_band_summary"));
            assertTrue(indexExists(connection, "idx_p25_control_quality_retention"));
            assertEquals(1, count(connection, "alias"));
            P25ActivityLogSchema.validate(connection);
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertFalse(hasRows(connection, "PRAGMA foreign_key_check"));
        }
    }

    @Test
    void migratesV20StagedCopyAndPreservesQualityData() throws Exception
    {
        Path database = createV20Database("v20.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_control_channel_quality (
                    guid, frequency_hz, bucket_start_ms, observed_at_ms
                ) VALUES ('dmr-site', 451000000, 10000, 12000)
                """);
        }

        CommandResult result = run(database);

        assertEquals(P25ActivitySchemaUpgrade.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("v20 -> v21 indexed quality retention"));
        assertTrue(result.error().isEmpty());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("21", metadata(connection, VERSION_KEY));
            assertTrue(indexExists(connection, "idx_p25_control_quality_retention"));
            assertEquals(1, count(connection, "p25_control_channel_quality"));
            P25ActivityLogSchema.validate(connection);
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
    }

    @Test
    void validatesV21WithoutChangingItsMetadata() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        long updatedAt = metadataUpdatedAt(database, VERSION_KEY);

        CommandResult result = run(database);

        assertEquals(P25ActivitySchemaUpgrade.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("already valid at P25 activity schema v21"));
        assertTrue(result.output().contains("without schema changes"));
        assertTrue(result.error().isEmpty());
        assertEquals(updatedAt, metadataUpdatedAt(database, VERSION_KEY));
    }

    @Test
    void refusesUnsupportedVersionWithoutChangingDatabase() throws Exception
    {
        Path database = createV19Database("unsupported.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseStartup.setMetadata(connection, VERSION_KEY, "18");
        }

        CommandResult result = run(database);

        assertEquals(P25ActivitySchemaUpgrade.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("Expected P25 activity schema v19, v20, or v21, found [18]"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("18", metadata(connection, VERSION_KEY));
            assertFalse(tableExists(connection, "p25_foreign_system_band"));
            assertFalse(tableExists(connection, "p25_foreign_system_band_summary"));
        }
    }

    @Test
    void rejectsForeignKeyViolationBeforeMigration() throws Exception
    {
        Path database = createV19Database("foreign-key.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel(alias_id, sort_order, channel_name)
                VALUES (999999, 0, 'missing-parent')
                """);
        }

        CommandResult result = run(database);

        assertEquals(P25ActivitySchemaUpgrade.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("Foreign-key check failed"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("19", metadata(connection, VERSION_KEY));
            assertFalse(tableExists(connection, "p25_foreign_system_band"));
        }
    }

    @Test
    void rollsBackTablesAndVersionWhenCurrentSchemaValidationFails() throws Exception
    {
        Path database = createV19Database("rollback.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE p25_site_neighbor");
        }

        CommandResult result = run(database);

        assertEquals(P25ActivitySchemaUpgrade.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("SQLite schema is missing table [p25_site_neighbor]"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("19", metadata(connection, VERSION_KEY));
            assertFalse(tableExists(connection, "p25_foreign_system_band"));
            assertFalse(tableExists(connection, "p25_foreign_system_band_summary"));
        }
    }

    @Test
    void refusesIncorrectPreexistingRetentionIndexWithoutRepairingIt() throws Exception
    {
        Path database = createV20Database("incorrect-index.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE INDEX idx_p25_control_quality_retention
                ON p25_control_channel_quality(guid)
                """);
        }

        CommandResult result = run(database);

        assertEquals(P25ActivitySchemaUpgrade.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains(
            "incorrect columns for index [idx_p25_control_quality_retention]: [guid]"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "PRAGMA index_info(idx_p25_control_quality_retention)"))
        {
            assertEquals("20", metadata(connection, VERSION_KEY));
            assertTrue(resultSet.next());
            assertEquals("guid", resultSet.getString("name"));
            assertFalse(resultSet.next());
        }
    }

    @Test
    void reportsUsageAndMissingInputWithStableExitCodes()
    {
        CommandResult usage = runArguments();
        assertEquals(P25ActivitySchemaUpgrade.EXIT_USAGE, usage.exitCode());
        assertTrue(usage.error().contains("A staged database path"));

        CommandResult missing = run(mTemporaryFolder.resolve("missing.sqlite"));
        assertEquals(P25ActivitySchemaUpgrade.EXIT_INPUT, missing.exitCode());
        assertTrue(missing.error().contains("Staged database not found"));

        CommandResult help = runArguments("--help");
        assertEquals(P25ActivitySchemaUpgrade.EXIT_SUCCESS, help.exitCode());
        assertTrue(help.output().startsWith("Usage:"));
    }

    private Path createV19Database(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE p25_foreign_system_band");
            statement.executeUpdate("DROP TABLE p25_foreign_system_band_summary");
            statement.executeUpdate("DROP INDEX idx_p25_control_quality_retention");
            SdrTrunkDatabaseStartup.setMetadata(connection, VERSION_KEY, "19");
        }

        return database;
    }

    private Path createV20Database(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_p25_control_quality_retention");
            SdrTrunkDatabaseStartup.setMetadata(connection, VERSION_KEY, "20");
        }

        return database;
    }

    private CommandResult run(Path database)
    {
        return runArguments(database.toString());
    }

    private CommandResult runArguments(String... arguments)
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exitCode;

        try(PrintStream outputStream = new PrintStream(output, true, StandardCharsets.UTF_8);
            PrintStream errorStream = new PrintStream(error, true, StandardCharsets.UTF_8))
        {
            exitCode = P25ActivitySchemaUpgrade.run(arguments, outputStream, errorStream);
        }

        return new CommandResult(exitCode, output.toString(StandardCharsets.UTF_8),
            error.toString(StandardCharsets.UTF_8));
    }

    private static String metadata(Connection connection, String key) throws Exception
    {
        try(var statement = connection.prepareStatement("SELECT value FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                return resultSet.getString(1);
            }
        }
    }

    private static long metadataUpdatedAt(Path database, String key) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.prepareStatement(
                "SELECT updated_at_ms FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, key);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private static boolean tableExists(Connection connection, String table) throws Exception
    {
        try(var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?"))
        {
            statement.setString(1, table);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static boolean indexExists(Connection connection, String index) throws Exception
    {
        try(var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?"))
        {
            statement.setString(1, index);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static int count(Connection connection, String table) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static String scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static boolean hasRows(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next();
        }
    }

    private record CommandResult(int exitCode, String output, String error)
    {
    }
}
