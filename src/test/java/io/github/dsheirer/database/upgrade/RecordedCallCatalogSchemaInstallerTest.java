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
import io.github.dsheirer.record.RecordedCallCatalogSchema;
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

class RecordedCallCatalogSchemaInstallerTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void installsCatalogOnStagedCurrentDatabaseAndPreservesData() throws Exception
    {
        Path database = createWithoutCatalog("staged.sqlite");

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias(sort_order, name) VALUES (0, 'Dispatch')");
        }

        CommandResult result = run(database);
        assertEquals(RecordedCallCatalogSchemaInstaller.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains(
            "RESULT: Recorded-call catalog schema installation complete: absent -> v1"));
        assertTrue(result.error().isEmpty());

        try(Connection connection = open(database))
        {
            RecordedCallCatalogSchema.validate(connection);
            assertEquals("1", RecordedCallCatalogSchema.schemaVersion(connection));
            assertEquals("21", metadata(connection, "p25_activity_schema_version"));
            assertEquals(1, count(connection, "alias"));
            assertEquals(2, catalogTableCount(connection));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertFalse(hasRows(connection, "PRAGMA foreign_key_check"));
        }
    }

    @Test
    void validatesCurrentCatalogWithoutChangingMetadata() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        long updatedAt = metadataUpdatedAt(database, RecordedCallCatalogSchema.SCHEMA_VERSION_KEY);

        CommandResult result = run(database);
        assertEquals(RecordedCallCatalogSchemaInstaller.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("already valid at recorded-call catalog schema v1"));
        assertEquals(updatedAt, metadataUpdatedAt(database, RecordedCallCatalogSchema.SCHEMA_VERSION_KEY));
    }

    @Test
    void refusesUnsupportedVersionWithoutChangingCatalog() throws Exception
    {
        Path database = mTemporaryFolder.resolve("unsupported.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database))
        {
            SdrTrunkDatabaseStartup.setMetadata(connection, RecordedCallCatalogSchema.SCHEMA_VERSION_KEY, "2");
        }

        CommandResult result = run(database);
        assertEquals(RecordedCallCatalogSchemaInstaller.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("absent or v1; found [2]"));

        try(Connection connection = open(database))
        {
            assertEquals("2", RecordedCallCatalogSchema.schemaVersion(connection));
            assertEquals(2, catalogTableCount(connection));
        }
    }

    @Test
    void refusesToRepairPartialCatalog() throws Exception
    {
        Path database = mTemporaryFolder.resolve("partial.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='" +
                RecordedCallCatalogSchema.SCHEMA_VERSION_KEY + "'");
            statement.executeUpdate("DROP TABLE recorded_call");
        }

        CommandResult result = run(database);
        assertEquals(RecordedCallCatalogSchemaInstaller.EXIT_PREPARATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("ambiguous partial schema"));

        try(Connection connection = open(database))
        {
            assertFalse(tableExists(connection, "recorded_call"));
            assertTrue(tableExists(connection, "recorded_call_bucket"));
            assertEquals(null, RecordedCallCatalogSchema.schemaVersion(connection));
        }
    }

    @Test
    void refusesUnknownObjectInVersionedCatalog() throws Exception
    {
        Path database = mTemporaryFolder.resolve("unknown-object.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE VIEW recorded_call_extra AS SELECT 1 AS id");
        }

        CommandResult result = run(database);
        assertEquals(RecordedCallCatalogSchemaInstaller.EXIT_PREPARATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("incorrect recorded-call catalog objects"));
        assertTrue(viewExists(database, "recorded_call_extra"));
    }

    @Test
    void reportsUsageAndMissingInputWithStableExitCodes()
    {
        CommandResult usage = runArguments();
        assertEquals(RecordedCallCatalogSchemaInstaller.EXIT_USAGE, usage.exitCode());
        assertTrue(usage.error().contains("staged database path"));

        CommandResult missing = run(mTemporaryFolder.resolve("missing.sqlite"));
        assertEquals(RecordedCallCatalogSchemaInstaller.EXIT_INPUT, missing.exitCode());
        assertTrue(missing.error().contains("Staged database not found"));

        CommandResult help = runArguments("--help");
        assertEquals(RecordedCallCatalogSchemaInstaller.EXIT_SUCCESS, help.exitCode());
        assertTrue(help.output().startsWith("Usage:"));
    }

    private Path createWithoutCatalog(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE recorded_call");
            statement.executeUpdate("DROP TABLE recorded_call_bucket");
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='" +
                RecordedCallCatalogSchema.SCHEMA_VERSION_KEY + "'");
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
            exitCode = RecordedCallCatalogSchemaInstaller.run(arguments, outputStream, errorStream);
        }

        return new CommandResult(exitCode, output.toString(StandardCharsets.UTF_8),
            error.toString(StandardCharsets.UTF_8));
    }

    private static Connection open(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
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
        try(Connection connection = open(database);
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

    private static boolean viewExists(Path database, String view) throws Exception
    {
        try(Connection connection = open(database); var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='view' AND name=?"))
        {
            statement.setString(1, view);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static int catalogTableCount(Connection connection) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type='table' AND name GLOB 'recorded_call*'
                """))
        {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
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
