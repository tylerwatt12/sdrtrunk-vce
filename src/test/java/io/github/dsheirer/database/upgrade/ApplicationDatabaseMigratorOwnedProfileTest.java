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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("platform-filesystem")
class ApplicationDatabaseMigratorOwnedProfileTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String OWNED = "--owned-complete-profile-copy";
    @TempDir Path mTemporaryFolder;

    @Test
    void relocatesCurrentAndHistoricalOwnedCopiesWithoutChangingTheirSources() throws Exception
    {
        for(int version: List.of(30, 53, 54))
        {
            Profile profile = profile("v" + version, version);
            Path external = mTemporaryFolder.resolve("external.wav");
            settings(profile.sourceDatabase(), "portable_java_preferences_v1", MAPPER.writeValueAsString(Map.of(
                "directories", Map.of("directory.recording", profile.source().resolve("recordings").toString(),
                    "directory.recording.managed", profile.source().resolve("recordings-managed").toString(),
                    "directory.application.logs", profile.source().resolve("logs").toString(),
                    "path.jmbe.library.primary", "jmbe/relative.jar",
                    "path.voice.decryption.module.primary", "modules/relative.jar"))));
            settings(profile.sourceDatabase(), "tuner.settings", MAPPER.writeValueAsString(Map.of(
                "tunerConfigurations", List.of(
                    Map.of("type", "recordingTunerConfiguration", "uniqueID", "owned", "path",
                        profile.source().resolve("recording_tuners/owned.wav").toString()),
                    Map.of("type", "recordingTunerConfiguration", "uniqueID", "external", "path", external.toString()),
                    Map.of("type", "recordingTunerConfiguration", "uniqueID", "relative", "path", "relative.wav")))));
            Files.copy(profile.sourceDatabase(), profile.stage());
            byte[] before = Files.readAllBytes(profile.sourceDatabase());
            Result result = run(profile.stage().toString(), profile.source().toString(), profile.target().toString(), OWNED);

            assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.code(), result::error);
            assertTrue(result.output().contains("bounded validation complete"), result::output);
            assertTrue(result.output().contains("physical-integrity, derived-history, and foreign-key sweeps are not run"));
            assertFalse(result.output().contains(profile.source().toString()), result::output);
            assertFalse(result.output().contains(profile.target().toString()), result::output);
            assertArrayEquals(before, Files.readAllBytes(profile.sourceDatabase()));
            assertEquals(Integer.toString(version), scalar(profile.sourceDatabase(),
                "SELECT value FROM database_metadata WHERE key='database_format_version'"));
            try(Connection connection = open(profile.stage()))
            {
                assertEquals(DatabaseFormatCatalog.current(), DatabaseFormatCatalog.requireCurrent(connection).descriptor());
            }
            JsonNode paths = MAPPER.readTree(scalar(profile.stage(),
                "SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'"))
                .path("directories");
            assertEquals(profile.target().resolve("recordings").toString(), paths.path("directory.recording").asText());
            assertEquals(profile.target().resolve("recordings-managed").toString(),
                paths.path("directory.recording.managed").asText());
            assertEquals(profile.target().resolve("logs").toString(), paths.path("directory.application.logs").asText());
            assertEquals("jmbe/relative.jar", paths.path("path.jmbe.library.primary").asText());
            assertEquals("modules/relative.jar", paths.path("path.voice.decryption.module.primary").asText());
            JsonNode tuners = MAPPER.readTree(scalar(profile.stage(),
                "SELECT settings_json FROM application_settings WHERE key='tuner.settings'"))
                .path("tunerConfigurations");
            assertEquals(profile.target().resolve("recording_tuners/owned.wav").toString(), tuners.get(0).path("path").asText());
            assertEquals(external.toString(), tuners.get(1).path("path").asText());
            assertEquals("relative.wav", tuners.get(2).path("path").asText());
            assertEquals("delete", scalar(profile.stage(), "PRAGMA journal_mode"));
            for(String suffix: List.of("-wal", "-shm", "-journal")) assertFalse(Files.exists(Path.of(profile.stage() + suffix)));
        }
    }

    @Test
    void ownedCopyOmitsDerivedIdentityWalkWhileStrictCompleteCopyRemainsStrict() throws Exception
    {
        Profile profile = profile("retained-history", 30);
        try(Connection connection = open(profile.sourceDatabase()); var statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO radio_system(id, system_key, protocol_code, p25_wacn, p25_system_id, first_seen_ms, last_seen_ms)
                VALUES(500, 'p25:abcde:123', 1, 703710, 291, 1000, 1000)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(radio_system_id, identity_kind_code,
                    home_wacn, home_system_id, identity_id, first_seen_ms, last_seen_ms)
                VALUES(500, 2, 703710, 291, 16777213, 1000, 1000)
                """);
            assertThrows(SQLException.class, () -> ApplicationDatabaseMigrator.validateCurrentDatabase(connection));
        }
        Files.copy(profile.sourceDatabase(), profile.stage());
        byte[] sourceBefore = Files.readAllBytes(profile.sourceDatabase());
        byte[] stageBefore = Files.readAllBytes(profile.stage());
        Result strict = run(profile.stage().toString(), profile.source().toString(), profile.target().toString(),
            "--complete-profile-copy");
        assertEquals(ApplicationDatabaseMigrator.EXIT_MIGRATION_FAILED, strict.code(), strict::error);
        assertArrayEquals(stageBefore, Files.readAllBytes(profile.stage()));
        assertTrue(Files.exists(ActivityDatabasePath.splitReceipt(profile.stage())),
            "A rolled-back split leaves its private extraction receipt for migrator recovery");

        Result owned = run(profile.stage().toString(), profile.source().toString(), profile.target().toString(), OWNED);
        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, owned.code(), owned::error);
        ConfigurationHistoryDatabase.validatePair(profile.stage());
        assertFalse(Files.exists(ActivityDatabasePath.splitReceipt(profile.stage())));
        assertEquals("1", scalar(ActivityDatabasePath.forConfiguration(profile.stage()),
            "SELECT count(*) FROM radio_system_identity_summary WHERE identity_id=16777213"));
        assertArrayEquals(sourceBefore, Files.readAllBytes(profile.sourceDatabase()));
    }

    @Test
    void rejectsIncompleteFlagsAndUnsafeRootOrStageRelationshipsBeforeWriting() throws Exception
    {
        Profile profile = profile("guards", 54);
        Files.copy(profile.sourceDatabase(), profile.stage());
        byte[] before = Files.readAllBytes(profile.stage());
        List<String[]> usage = List.of(new String[]{profile.stage().toString(), OWNED},
            new String[]{profile.stage().toString(), profile.source().toString(), profile.target().toString(),
                OWNED, "--complete-profile-copy"});
        for(String[] args: usage) assertEquals(ApplicationDatabaseMigrator.EXIT_USAGE, run(args).code());
        for(Path source: List.of(profile.target(), profile.target().getParent(),
            Files.createDirectory(profile.target().resolve("nested")), mTemporaryFolder.resolve("missing")))
        {
            assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT,
                run(profile.stage().toString(), source.toString(), profile.target().toString(), OWNED).code());
        }
        Path unrelated = mTemporaryFolder.resolve(".sdrtrunk.sqlite.migration-" + UUID.randomUUID());
        Files.copy(profile.stage(), unrelated);
        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT,
            run(unrelated.toString(), profile.source().toString(), profile.target().toString(), OWNED).code());
        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT,
            run(profile.sourceDatabase().toString(), profile.source().toString(), profile.target().toString(), OWNED).code());
        assertArrayEquals(before, Files.readAllBytes(profile.stage()));
        assertArrayEquals(before, Files.readAllBytes(profile.sourceDatabase()));
    }

    @Test
    void rejectsSymlinkRootsAndHardLinkedStagesBeforeWriting() throws Exception
    {
        Profile profile = profile("links", 54);
        Files.copy(profile.sourceDatabase(), profile.stage());
        byte[] before = Files.readAllBytes(profile.sourceDatabase());
        Path alias = mTemporaryFolder.resolve("linked-target");
        Path hardlink = profile.stage().resolveSibling(".sdrtrunk.sqlite.migration-" + UUID.randomUUID());
        try
        {
            Files.createSymbolicLink(alias, profile.target());
            Files.createLink(hardlink, profile.sourceDatabase());
        }
        catch(UnsupportedOperationException exception)
        {
            Assumptions.abort("Filesystem does not support the tested link types");
        }
        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, run(alias.resolve("database").resolve(profile.stage().getFileName()).toString(),
            profile.source().toString(), alias.toString(), OWNED).code());
        Result rejected = run(hardlink.toString(), profile.source().toString(), profile.target().toString(), OWNED);
        assertEquals(ApplicationDatabaseMigrator.EXIT_INPUT, rejected.code(), rejected::error);
        assertTrue(rejected.error().contains("The owned profile stage must be a separate file from the source database."),
            rejected::error);
        assertFalse(rejected.output().contains("Checking the owned complete-profile copy"), rejected::output);
        assertArrayEquals(before, Files.readAllBytes(profile.sourceDatabase()));
        assertArrayEquals(before, Files.readAllBytes(profile.stage()));
    }

    @Test
    void rejectsMixedAndNewerSchemasWithoutChangingTheSourceOrStage() throws Exception
    {
        for(boolean newer: List.of(false, true))
        {
            Profile profile = profile("rejected-" + newer, 54);
            try(Connection connection = open(profile.sourceDatabase()); var statement = connection.createStatement())
            {
                statement.executeUpdate(newer ? "UPDATE database_metadata SET value='999' WHERE key='database_format_version'" :
                    "CREATE TABLE unexpected_layout(id INTEGER PRIMARY KEY)");
            }
            Files.copy(profile.sourceDatabase(), profile.stage());
            byte[] before = Files.readAllBytes(profile.stage());
            Result result = run(profile.stage().toString(), profile.source().toString(), profile.target().toString(), OWNED);
            assertEquals(ApplicationDatabaseMigrator.EXIT_UNSUPPORTED_VERSION, result.code(), result::error);
            assertArrayEquals(before, Files.readAllBytes(profile.sourceDatabase()));
            assertArrayEquals(before, Files.readAllBytes(profile.stage()));
        }
    }

    @Test
    void launchesOwnedModeInAChildProcess() throws Exception
    {
        Profile profile = profile("child", 54);
        Files.copy(profile.sourceDatabase(), profile.stage());
        String output = ApplicationMigratorLauncher.runOwnedCompleteProfileCopy(profile.stage(), profile.source(), profile.target());
        assertTrue(output.contains("Owned complete-profile relocation and bounded validation complete"), output);
    }

    @Test
    void fullPairedNicknameUpgradeLeavesPrivateActivityAndOriginalPairUnchanged() throws Exception
    {
        Profile profile = profile("paired-nicknames", 63);
        Path sourceActivity = ActivityDatabasePath.forConfiguration(profile.sourceDatabase());
        Path stagedActivity = ActivityDatabasePath.forConfiguration(profile.stage());
        Files.copy(profile.sourceDatabase(), profile.stage());
        Files.copy(sourceActivity, stagedActivity);
        byte[] configuration = Files.readAllBytes(profile.sourceDatabase());
        byte[] activity = Files.readAllBytes(sourceActivity);
        assertEquals(Files.size(profile.stage()),
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(profile.stage(), MigrationContent.FULL));

        Result result = run(profile.stage().toString(), profile.source().toString(), profile.target().toString(), OWNED);
        assertEquals(ApplicationDatabaseMigrator.EXIT_SUCCESS, result.code(), result::error);
        ConfigurationHistoryDatabase.validatePair(profile.stage());
        assertEquals("64", scalar(profile.stage(), "SELECT value FROM database_metadata WHERE key='database_format_version'"));
        assertEquals("63", scalar(stagedActivity, "SELECT value FROM database_metadata WHERE key='database_format_version'"));
        assertArrayEquals(configuration, Files.readAllBytes(profile.sourceDatabase()));
        assertArrayEquals(activity, Files.readAllBytes(sourceActivity));
        assertArrayEquals(activity, Files.readAllBytes(stagedActivity));
    }

    private Profile profile(String name, int version) throws Exception
    {
        Path source = Files.createDirectories(mTemporaryFolder.resolve(name + "-old/data"));
        Path target = Files.createDirectories(mTemporaryFolder.resolve(name + "-new/data"));
        Path sourceDatabase = source.resolve("database/sdrtrunk.sqlite");
        Files.createDirectories(sourceDatabase.getParent());
        Class.forName("io.github.dsheirer.database.upgrade.Format" + version + "TestDatabase")
            .getMethod("create", Path.class).invoke(null, sourceDatabase);
        Files.createDirectories(target.resolve("database"));
        return new Profile(source, target, sourceDatabase,
            target.resolve("database/.sdrtrunk.sqlite.migration-" + UUID.randomUUID()));
    }

    private static void settings(Path database, String key, String json) throws Exception
    {
        try(Connection connection = open(database); var statement = connection.prepareStatement("""
            INSERT INTO application_settings(key,settings_json,updated_at_ms) VALUES(?,?,1)
            ON CONFLICT(key) DO UPDATE SET settings_json=excluded.settings_json,updated_at_ms=1
            """))
        {
            statement.setString(1, key);
            statement.setString(2, json);
            statement.executeUpdate();
        }
    }

    private static String scalar(Path database, String sql) throws SQLException
    {
        try(Connection connection = open(database); var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getString(1);
        }
    }

    private static Connection open(Path database) throws SQLException
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static Result run(String... arguments)
    {
        var output = new ByteArrayOutputStream();
        var error = new ByteArrayOutputStream();
        int code = ApplicationDatabaseMigrator.run(arguments, new PrintStream(output), new PrintStream(error));
        return new Result(code, output.toString(StandardCharsets.UTF_8), error.toString(StandardCharsets.UTF_8));
    }

    private record Profile(Path source, Path target, Path sourceDatabase, Path stage) { }
    private record Result(int code, String output, String error) { }
}
