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

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SqliteSchemaValidator;
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
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Child-process boundary and rollback tests for the format-2 Application Migrator.
 *
 * <p>These retain the safety cases from the canonical global migrator while deliberately omitting the later
 * format-2-to-3 and web-user semantics that are not present in the Alpha 11 target.</p>
 */
class ApplicationDatabaseMigratorTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void validatesExactCurrentStagedDatabaseWithoutChangingSchema() throws Exception
    {
        Path database = createCurrentDatabase(newStagedDatabase());
        byte[] before = Files.readAllBytes(database);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("already current and valid"));
        assertTrue(result.error().isEmpty());
        assertEquals(DatabaseFormatCatalog.current().fingerprint(), fingerprint(database));
        assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION),
            metadata(database, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
        assertEquals("ok", scalar(database, "PRAGMA quick_check"));
        assertFalse(Arrays.equals(new byte[before.length], Files.readAllBytes(database)));
    }

    @Test
    void adoptsMarkerlessCurrentLayoutWithoutRunningSchemaSteps() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());
        String beforeFingerprint = fingerprint(database);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("adopt-global-format-marker"));
        assertFalse(result.output().contains("format-1-to-2"));
        assertEquals(beforeFingerprint, fingerprint(database));
        assertEquals(Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION),
            metadata(database, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
    }

    @Test
    void migratesExactFormat1ThroughTheOnlyAdjacentStep() throws Exception
    {
        Path database = Format1TestDatabase.create(newStagedDatabase());
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM p25_radio_affiliation"));
        assertEquals("{\"configurationId\":\"11111111-2222-4333-8444-555555555556\",\"fixture\":true}",
            scalar(database,
            "SELECT config_json FROM configuration_channel WHERE name='Fixture Channel'"));

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.exitCode(), result.error());
        assertTrue(result.output().contains("PLAN STEP: 1 -> 2 [format-1-to-2]"));
        assertTrue(result.output().contains("COMPLETED STEP: 1 -> 2 [format-1-to-2]"));
        assertEquals(DatabaseFormatCatalog.current().fingerprint(), fingerprint(database));
        assertEquals("0", scalar(database, """
            SELECT COUNT(*) FROM sqlite_schema
            WHERE type='table' AND name='p25_radio_affiliation'
            """));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM trunked_identity_summary"));
        assertEquals("{\"configurationId\":\"11111111-2222-4333-8444-555555555556\",\"fixture\":true}",
            scalar(database,
            "SELECT config_json FROM configuration_channel WHERE name='Fixture Channel'"));
    }

    @Test
    void refusesANewerKnownFormatMarkerExplicitly() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());
        setMarker(database, 3);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("newer than this build supports"));
    }

    @Test
    void refusesANewerMarkerBeforeLookingUpAnUnknownFingerprint() throws Exception
    {
        Path database = Format2TestDatabase.create(newStagedDatabase());
        setMarker(database, 3);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE future_format_object(id INTEGER PRIMARY KEY)");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("newer than this build supports"));
    }

    @Test
    void refusesUnrecognizedCurrentLikeMetadataWithoutRepairingIt() throws Exception
    {
        Path database = createCurrentDatabase(newStagedDatabase());
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='" +
                DmrActivitySchema.SCHEMA_VERSION_KEY + "'");
        }

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.exitCode());
        assertTrue(result.error().contains("mixed or partially migrated"));
        assertEquals(null, metadata(database, DmrActivitySchema.SCHEMA_VERSION_KEY));
    }

    @Test
    void malformedRelocationSettingsRollBackFormat1AndItsData() throws Exception
    {
        Path database = Format1TestDatabase.create(newStagedDatabase());
        String beforeFingerprint = fingerprint(database);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1', '{invalid', 1)
                """);
        }
        Path source = mTemporaryFolder.resolve("source-data").toAbsolutePath();
        Path target = mTemporaryFolder.resolve("target-data").toAbsolutePath();

        CommandResult result = run(database, source, target);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("invalid JSON"));
        assertEquals(beforeFingerprint, fingerprint(database));
        assertEquals(null, metadata(database, DatabaseFormatCatalog.FORMAT_VERSION_KEY));
        assertEquals("24", metadata(database, "p25_activity_schema_version"));
        assertEquals("1", scalar(database, "SELECT COUNT(*) FROM p25_radio_affiliation"));
        assertEquals("{invalid", scalar(database, """
            SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
            """));
        assertEquals("{\"configurationId\":\"11111111-2222-4333-8444-555555555556\",\"fixture\":true}",
            scalar(database,
            "SELECT config_json FROM configuration_channel WHERE name='Fixture Channel'"));
    }

    @Test
    void refusesAValidLiveDatabasePathWithoutOpeningItForMigration() throws Exception
    {
        Path database = createCurrentDatabase(
            SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder.resolve("live-data")));
        byte[] before = Files.readAllBytes(database);

        CommandResult result = runArguments(database.toString());

        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, result.exitCode());
        assertTrue(result.error().contains("accepts only an application-created staged database"));
        assertTrue(Arrays.equals(before, Files.readAllBytes(database)));
    }

    @Test
    void refusesAStagedLookingSymbolicLinkToALiveDatabase() throws Exception
    {
        Path liveDatabase = createCurrentDatabase(
            SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder.resolve("live-symlink-data")));
        byte[] before = Files.readAllBytes(liveDatabase);
        Path stagedLink = newStagedDatabase();

        try
        {
            Files.createSymbolicLink(stagedLink, liveDatabase);
        }
        catch(UnsupportedOperationException | IllegalArgumentException | java.io.IOException | SecurityException e)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }

        CommandResult result = run(stagedLink);

        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, result.exitCode());
        assertTrue(result.error().contains("must not be a symbolic link"));
        assertTrue(Arrays.equals(before, Files.readAllBytes(liveDatabase)));
    }

    @Test
    void refusesAStagedLookingAncestorSymlinkToALiveDataRoot() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("live-ancestor-data");
        Path liveDatabase = createCurrentDatabase(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
        byte[] before = Files.readAllBytes(liveDatabase);
        Path deceptiveStageRoot = mTemporaryFolder.resolve(".live-ancestor-data.migration-" + UUID.randomUUID());

        try
        {
            Files.createSymbolicLink(deceptiveStageRoot, dataRoot);
        }
        catch(UnsupportedOperationException | java.io.IOException | SecurityException e)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }

        Path disguisedLiveDatabase = deceptiveStageRoot.resolve("database")
            .resolve(SdrTrunkDatabasePath.DATABASE_FILENAME);
        CommandResult result = run(disguisedLiveDatabase);

        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, result.exitCode());
        assertTrue(result.error().contains("resolves outside an application stage"));
        assertTrue(Arrays.equals(before, Files.readAllBytes(liveDatabase)));
    }

    @Test
    void refusesAStagedLookingHardLinkToALiveDatabaseWhenLinkCountsAreAvailable() throws Exception
    {
        Path liveDatabase = createCurrentDatabase(
            SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder.resolve("live-hard-link-data")));
        byte[] before = Files.readAllBytes(liveDatabase);
        Path stagedLink = newStagedDatabase();

        try
        {
            Files.createLink(stagedLink, liveDatabase);
            Object links = Files.getAttribute(stagedLink, "unix:nlink",
                java.nio.file.LinkOption.NOFOLLOW_LINKS);
            Assumptions.assumeTrue(links instanceof Number && ((Number)links).longValue() > 1,
                "Filesystem link counts are unavailable");
        }
        catch(UnsupportedOperationException | IllegalArgumentException | java.io.IOException | SecurityException e)
        {
            Assumptions.assumeTrue(false, "Hard links are unavailable: " + e.getMessage());
        }

        CommandResult result = run(stagedLink);

        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, result.exitCode());
        assertTrue(result.error().contains("multiple filesystem links"));
        assertTrue(Arrays.equals(before, Files.readAllBytes(liveDatabase)));
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

    @Test
    void reportsCorruptSqliteAsMigrationFailureInsteadOfUnsupportedFormat() throws Exception
    {
        Path database = newStagedDatabase();
        byte[] corrupt = "not a sqlite database".getBytes(StandardCharsets.UTF_8);
        Files.write(database, corrupt);

        CommandResult result = run(database);

        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, result.exitCode());
        assertTrue(result.error().contains("Database migration failed"));
        assertTrue(Arrays.equals(corrupt, Files.readAllBytes(database)));
    }

    private Path newStagedDatabase() throws Exception
    {
        Path database = mTemporaryFolder.resolve(".sdrtrunk.sqlite.migration-" + UUID.randomUUID());
        Files.createDirectories(database.getParent());
        return database;
    }

    private static Path createCurrentDatabase(Path database) throws Exception
    {
        Format2TestDatabase.create(database);
        try(Connection connection = open(database))
        {
            DatabaseFormatCatalog.stamp(connection, DatabaseFormatCatalog.CURRENT_VERSION);
        }
        return database;
    }

    private static void setMarker(Path database, int version) throws Exception
    {
        try(Connection connection = open(database); var statement = connection.prepareStatement("""
            INSERT INTO database_metadata(key, value, updated_at_ms)
            VALUES ('database_format_version', ?, 1)
            ON CONFLICT(key) DO UPDATE SET value=excluded.value
            """))
        {
            statement.setString(1, Integer.toString(version));
            statement.executeUpdate();
        }
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

    private static Connection open(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static String metadata(Path database, String key) throws Exception
    {
        try(Connection connection = open(database); var statement = connection.prepareStatement(
            "SELECT value FROM database_metadata WHERE key=?"))
        {
            statement.setString(1, key);
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static String scalar(Path database, String sql) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static String fingerprint(Path database) throws Exception
    {
        try(Connection connection = open(database))
        {
            return SqliteSchemaValidator.fingerprint(connection);
        }
    }

    private record CommandResult(int exitCode, String output, String error)
    {
    }
}
