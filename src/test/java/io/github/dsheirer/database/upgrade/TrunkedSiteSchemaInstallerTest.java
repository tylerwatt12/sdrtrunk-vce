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
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
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

class TrunkedSiteSchemaInstallerTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void installsV2OnStagedCurrentDatabaseAndPreservesData() throws Exception
    {
        Path database = createWithoutTrunkedSiteSchema("staged.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias(sort_order, name) VALUES (0, 'Dispatch')");
        }

        CommandResult result = run(database);
        assertEquals(TrunkedSiteSchemaInstaller.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("RESULT: Trunked-site schema installation complete: absent -> v2"));
        assertTrue(result.error().isEmpty());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            TrunkedSiteSchema.validate(connection);
            assertEquals("2", TrunkedSiteSchema.schemaVersion(connection));
            assertEquals("20", metadata(connection, "p25_activity_schema_version"));
            assertEquals(1, count(connection, "alias"));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertFalse(hasRows(connection, "PRAGMA foreign_key_check"));
        }
    }

    @Test
    void refusesUnreleasedV1WithoutChangingData() throws Exception
    {
        Path database = mTemporaryFolder.resolve("v1.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO trunked_site_snapshot (
                    guid, snapshot_hash, protocol_code, variant_code, identity_domain_code, configured_system,
                    channel_name, first_seen_ms, last_seen_ms, observation_count
                ) VALUES ('dmr-v1', 'hash', 3, 1, 2, 'DMR System', 'DMR Site', 1000, 2000, 3)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_channel_summary (
                    guid, channel_number, inbound_channel_number, timeslot, frequency_hz, role_flags,
                    first_seen_ms, last_seen_ms, observation_count
                ) VALUES ('dmr-v1', 42, -1, 1, 451000000, 1, 1000, 2000, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_neighbor_summary (
                    guid, variant_code, identity_domain_code, network_id, system_id, site_id, channel_number,
                    frequency_hz, status_flags, first_seen_ms, last_seen_ms, observation_count
                ) VALUES ('dmr-v1', 1, 2, 10, 20, 31, 43, 452000000, 1, 1000, 2000, 2)
                """);
            statement.executeUpdate("DROP INDEX idx_trunked_site_snapshot_last_seen");
            statement.executeUpdate("DROP INDEX idx_trunked_site_channel_last_seen");
            statement.executeUpdate("DROP INDEX idx_trunked_site_neighbor_last_seen");
            SdrTrunkDatabaseStartup.setMetadata(connection, TrunkedSiteSchema.SCHEMA_VERSION_KEY, "1");
        }

        CommandResult result = run(database);
        assertEquals(TrunkedSiteSchemaInstaller.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("Expected trunked-site schema to be absent or v2; found [1]"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("1", TrunkedSiteSchema.schemaVersion(connection));
            assertEquals(1, count(connection, "trunked_site_snapshot"));
            assertEquals(1, count(connection, "trunked_site_channel_summary"));
            assertEquals(1, count(connection, "trunked_site_neighbor_summary"));
            assertEquals("DMR System", scalar(connection,
                "SELECT configured_system FROM trunked_site_snapshot WHERE guid='dmr-v1'"));
            assertFalse(indexExists(connection, "idx_trunked_site_snapshot_last_seen"));
            assertFalse(indexExists(connection, "idx_trunked_site_channel_last_seen"));
            assertFalse(indexExists(connection, "idx_trunked_site_neighbor_last_seen"));
            assertEquals("ok", scalar(connection, "PRAGMA integrity_check"));
            assertFalse(hasRows(connection, "PRAGMA foreign_key_check"));
        }
    }

    @Test
    void validatesV2WithoutChangingMetadata() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        long updatedAt = metadataUpdatedAt(database, TrunkedSiteSchema.SCHEMA_VERSION_KEY);

        CommandResult result = run(database);
        assertEquals(TrunkedSiteSchemaInstaller.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("already valid at trunked-site schema v2"));
        assertEquals(updatedAt, metadataUpdatedAt(database, TrunkedSiteSchema.SCHEMA_VERSION_KEY));
    }

    @Test
    void refusesUnsupportedVersionWithoutChangingTables() throws Exception
    {
        Path database = mTemporaryFolder.resolve("unsupported.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseStartup.setMetadata(connection, TrunkedSiteSchema.SCHEMA_VERSION_KEY, "3");
        }

        CommandResult result = run(database);
        assertEquals(TrunkedSiteSchemaInstaller.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("absent or v2; found [3]"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("3", TrunkedSiteSchema.schemaVersion(connection));
            assertTrue(tableExists(connection, "trunked_site_snapshot"));
        }
    }

    @Test
    void refusesToRepairPartialSchema() throws Exception
    {
        Path database = mTemporaryFolder.resolve("partial.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='" +
                TrunkedSiteSchema.SCHEMA_VERSION_KEY + "'");
            statement.executeUpdate("DROP TABLE trunked_site_neighbor_summary");
        }

        CommandResult result = run(database);
        assertEquals(TrunkedSiteSchemaInstaller.EXIT_PREPARATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("ambiguous partial schema"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertFalse(tableExists(connection, "trunked_site_neighbor_summary"));
            assertEquals(null, TrunkedSiteSchema.schemaVersion(connection));
        }
    }

    @Test
    void rejectsMalformedBaseSchemaBeforeInstallation() throws Exception
    {
        Path database = createWithoutTrunkedSiteSchema("malformed.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE p25_site_neighbor");
        }

        CommandResult result = run(database);
        assertEquals(TrunkedSiteSchemaInstaller.EXIT_PREPARATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("SQLite schema is missing table [p25_site_neighbor]"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertFalse(tableExists(connection, "trunked_site_snapshot"));
        }
    }

    @Test
    void reportsUsageAndMissingInputWithStableExitCodes()
    {
        CommandResult usage = runArguments();
        assertEquals(TrunkedSiteSchemaInstaller.EXIT_USAGE, usage.exitCode());
        assertTrue(usage.error().contains("staged database path"));

        CommandResult missing = run(mTemporaryFolder.resolve("missing.sqlite"));
        assertEquals(TrunkedSiteSchemaInstaller.EXIT_INPUT, missing.exitCode());
        assertTrue(missing.error().contains("Staged database not found"));

        CommandResult help = runArguments("--help");
        assertEquals(TrunkedSiteSchemaInstaller.EXIT_SUCCESS, help.exitCode());
        assertTrue(help.output().startsWith("Usage:"));
    }

    private Path createWithoutTrunkedSiteSchema(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("DROP TABLE trunked_site_channel_summary");
            statement.executeUpdate("DROP TABLE trunked_site_neighbor_summary");
            statement.executeUpdate("DROP TABLE trunked_site_snapshot");
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='" +
                TrunkedSiteSchema.SCHEMA_VERSION_KEY + "'");
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
            exitCode = TrunkedSiteSchemaInstaller.run(arguments, outputStream, errorStream);
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
