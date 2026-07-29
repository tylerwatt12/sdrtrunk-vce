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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationDatabaseMigratorTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void validatesCurrentStagedDatabaseWithoutChangingSchema() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("already current and valid"));
        assertTrue(result.error().isEmpty());

        try(Connection connection = open(database))
        {
            assertEquals("4", metadata(connection, "alias_schema_version"));
            assertEquals("22", metadata(connection, "p25_activity_schema_version"));
            assertEquals("2", metadata(connection, "trunked_site_schema_version"));
            assertEquals("1", metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
    }

    @Test
    void rebasesPortableDirectoriesWithoutChangingSchema() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Path source = mTemporaryFolder.resolve("source-data").toAbsolutePath();
        Path target = mTemporaryFolder.resolve("target-data").toAbsolutePath();

        try(Connection connection = open(database); var statement = connection.prepareStatement("""
            INSERT INTO application_settings(key, settings_json, updated_at_ms)
            VALUES ('portable_java_preferences_v1', ?, 1)
            """))
        {
            statement.setString(1, OBJECT_MAPPER.writeValueAsString(java.util.Map.of("directories",
                java.util.Map.of("directory.recording", source.resolve("recordings").toString(),
                    "directory.application.logs", source.resolve("logs").toString()))));
            statement.executeUpdate();
        }

        CommandResult result = run(database, source, target);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode());
        assertTrue(result.output().contains("Portable directory preferences updated: 2"));

        try(Connection connection = open(database))
        {
            JsonNode settings = OBJECT_MAPPER.readTree(scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals(target.resolve("recordings").toString(),
                settings.path("directories").path("directory.recording").asText());
            assertEquals(target.resolve("logs").toString(),
                settings.path("directories").path("directory.application.logs").asText());
            assertEquals("1", metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
        }
    }

    @Test
    void refusesUnreleasedPredecessorSchemaWithoutRepairingIt() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        removeDmrActivitySchema(database);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("Expected current DMR activity schema v1"));

        try(Connection connection = open(database))
        {
            assertFalse(tableExists(connection, DmrActivitySchema.TALKGROUP_TABLE));
            assertFalse(tableExists(connection, DmrActivitySchema.RADIO_TABLE));
            assertEquals(null, metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
        }
    }

    @Test
    void refusesOlderPublishedSchemaOutsideReleasePreparation() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database))
        {
            SdrTrunkDatabaseStartup.setMetadata(connection, "p25_activity_schema_version", "20");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("Expected current P25 activity schema v22"));

        try(Connection connection = open(database))
        {
            assertEquals("20", metadata(connection, "p25_activity_schema_version"));
        }
    }

    @Test
    void malformedRelocationSettingsRollBackWithoutSchemaChanges() throws Exception
    {
        Path database = newStagedDatabase();
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        Path source = mTemporaryFolder.resolve("source-data").toAbsolutePath();
        Path target = mTemporaryFolder.resolve("target-data").toAbsolutePath();

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1', '{invalid', 1)
                """);
        }

        CommandResult result = run(database, source, target);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertFalse(result.error().isBlank());

        try(Connection connection = open(database))
        {
            assertEquals("{invalid", scalar(connection, """
                SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
                """));
            assertEquals("1", metadata(connection, DmrActivitySchema.SCHEMA_VERSION_KEY));
        }
    }

    @Test
    void refusesAValidLiveDatabasePathWithoutOpeningItForMigration() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("live-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        byte[] before = Files.readAllBytes(database);

        CommandResult result = runArguments(database.toString());

        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, result.exitCode());
        assertTrue(result.error().contains("accepts only an application-created staged database"));
        assertTrue(java.util.Arrays.equals(before, Files.readAllBytes(database)));
    }

    @Test
    void reportsUsageAndMissingInputWithStableExitCodes()
    {
        CommandResult help = runArguments("--help");
        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, help.exitCode());
        assertTrue(help.output().contains("Usage:"));

        CommandResult missing = runArguments();
        assertEquals(ApplicationDatabaseMigrator.EXIT_USAGE, missing.exitCode());
        assertTrue(missing.error().contains("staged database path"));
    }

    private Path newStagedDatabase() throws Exception
    {
        Path database = mTemporaryFolder.resolve(".sdrtrunk.sqlite.migration-" + UUID.randomUUID());
        Files.createDirectories(database.getParent());
        return database;
    }

    private CommandResult run(Path database)
    {
        return runArguments(database.toString());
    }

    private CommandResult run(Path database, Path source, Path target)
    {
        return runArguments(database.toString(), source.toString(), target.toString());
    }

    private CommandResult runArguments(String... arguments)
    {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int exitCode = ApplicationDatabaseMigrator.run(arguments, new PrintStream(output), new PrintStream(error));
        return new CommandResult(exitCode, output.toString(StandardCharsets.UTF_8),
            error.toString(StandardCharsets.UTF_8));
    }

    private static void removeDmrActivitySchema(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.TALKGROUP_RETENTION_INDEX);
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.RADIO_RETENTION_INDEX);
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.TALKGROUP_CONTEXT_INDEX);
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.RADIO_CONTEXT_INDEX);
            statement.executeUpdate("DROP TABLE " + DmrActivitySchema.TALKGROUP_TABLE);
            statement.executeUpdate("DROP TABLE " + DmrActivitySchema.RADIO_TABLE);
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='" +
                DmrActivitySchema.SCHEMA_VERSION_KEY + "'");
        }
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
                return resultSet.next() ? resultSet.getString(1) : null;
            }
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

    private record CommandResult(int exitCode, String output, String error)
    {
    }
}
