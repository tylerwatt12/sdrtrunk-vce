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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationMigrationServiceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void supportsOnlyPublishedAlpha7AndCurrentSchemaTuples()
    {
        ApplicationMigrationService.MigrationState current =
            currentState();
        assertTrue(current.supported());
        assertFalse(current.requiresMigration());
        assertEquals("", current.requiredChanges());

        ApplicationMigrationService.MigrationState alpha7 =
            new ApplicationMigrationService.MigrationState(3, 21, 2, null);
        assertTrue(alpha7.supported());
        assertTrue(alpha7.requiresMigration());
        assertTrue(alpha7.requiredChanges().contains("Alpha 7"));

        for(ApplicationMigrationService.MigrationState predecessor: List.of(
            new ApplicationMigrationService.MigrationState(2, 20, 2, null),
            new ApplicationMigrationService.MigrationState(3, 21, 2, 1),
            new ApplicationMigrationService.MigrationState(3, P25ActivityLogSchema.SCHEMA_VERSION, 2, 1),
            new ApplicationMigrationService.MigrationState(4, P25ActivityLogSchema.SCHEMA_VERSION - 1, 2, 1),
            new ApplicationMigrationService.MigrationState(4, P25ActivityLogSchema.SCHEMA_VERSION, null, 1),
            new ApplicationMigrationService.MigrationState(4, P25ActivityLogSchema.SCHEMA_VERSION, 2, null)))
        {
            assertFalse(predecessor.supported());
            assertFalse(predecessor.requiresMigration());
            assertTrue(predecessor.requiredChanges().contains("no bundled transition"));
        }
    }

    @Test
    void refusesOverlappingImportRootsBeforeCreatingMigrationFiles() throws Exception
    {
        Path outerSource = mTemporaryFolder.resolve("outer-source");
        Path outerSourceDatabase = SdrTrunkDatabasePath.getDatabasePath(outerSource);
        SdrTrunkDatabaseStartup.createGlobalDatabase(outerSourceDatabase);
        byte[] outerSourceHash = sha256(outerSourceDatabase);
        Path nestedTarget = outerSource.resolve("jmbe/imported-data");

        IOException nestedTargetException = assertThrows(IOException.class,
            () -> new ApplicationMigrationService().importPrevious(outerSource, nestedTarget, null));

        assertTrue(nestedTargetException.getMessage().contains("overlap"));
        assertArrayEquals(outerSourceHash, sha256(outerSourceDatabase));
        assertFalse(Files.exists(nestedTarget));

        Path outerTarget = mTemporaryFolder.resolve("outer-target");
        Path nestedSource = outerTarget.resolve("previous-data");
        Path nestedSourceDatabase = SdrTrunkDatabasePath.getDatabasePath(nestedSource);
        SdrTrunkDatabaseStartup.createGlobalDatabase(nestedSourceDatabase);
        byte[] nestedSourceHash = sha256(nestedSourceDatabase);

        IOException nestedSourceException = assertThrows(IOException.class,
            () -> new ApplicationMigrationService().importPrevious(nestedSource, outerTarget, null));

        assertTrue(nestedSourceException.getMessage().contains("overlap"));
        assertArrayEquals(nestedSourceHash, sha256(nestedSourceDatabase));
        assertFalse(Files.exists(outerTarget.resolve("database/backups")));
    }

    @Test
    void readsTheExactCurrentSchemaState() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("current-state");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        ApplicationMigrationService.MigrationState state =
            ApplicationMigrationService.readMigrationState(database);

        assertEquals(currentState(), state);
        assertTrue(state.supported());
    }

    @Test
    void importsAnAlreadyCurrentProfileAndRebasesPortablePaths() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("source-data");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(sourceDatabase);
        insertAlias(sourceDatabase, "Keep Me");
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("target-data");
        Files.createDirectory(targetRoot);

        ApplicationMigrationService.MigrationResult result = new ApplicationMigrationService()
            .importPrevious(sourceRoot, targetRoot, null);

        assertTrue(result.importedPreviousProfile());
        assertEquals(currentState(), result.sourceState());
        assertTrue(result.helperOutput().contains("Application database migration and validation complete"));
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertEquals(1, count(targetDatabase, "alias"));
        assertEquals(currentState(),
            ApplicationMigrationService.readMigrationState(targetDatabase));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertEquals("wal", journalMode(targetDatabase));
    }

    @Test
    void currentProfileRefreshRetainsSafetyBackup() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("current-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        insertAlias(database, "Retained");

        ApplicationMigrationService.MigrationResult result =
            new ApplicationMigrationService().migrateCurrent(dataRoot, null);

        assertFalse(result.importedPreviousProfile());
        assertNotNull(result.safetyBackup());
        assertTrue(Files.isRegularFile(result.safetyBackup()));
        assertEquals(1, count(result.safetyBackup(), "alias"));
        assertEquals(currentState(),
            ApplicationMigrationService.readMigrationState(result.safetyBackup()));
        assertEquals(1, count(database, "alias"));
        assertEquals(currentState(),
            ApplicationMigrationService.readMigrationState(database));
        assertEquals("wal", journalMode(database));
    }

    @Test
    void currentProfileMigrationPreservesPosixDatabaseAccess() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("posix-current-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        PosixFileAttributeView view = Files.getFileAttributeView(database, PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "POSIX file attributes are unavailable");
        view.setPermissions(PosixFilePermissions.fromString("rw-------"));
        PosixFileAttributes before = view.readAttributes();

        ApplicationMigrationService.MigrationResult result =
            new ApplicationMigrationService().migrateCurrent(dataRoot, null);

        PosixFileAttributes after = Files.readAttributes(database, PosixFileAttributes.class);
        assertEquals(before.permissions(), after.permissions());
        assertEquals(before.owner(), after.owner());
        assertEquals(before.group(), after.group());
        PosixFileAttributes backup = Files.readAttributes(result.safetyBackup(), PosixFileAttributes.class);
        assertEquals(before.permissions(), backup.permissions());
        assertEquals(before.owner(), backup.owner());
        assertEquals(before.group(), backup.group());
    }

    @Test
    void profileImportPreservesAnExistingEmptyTargetRootsPosixAccess() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("posix-import-source");
        SdrTrunkDatabaseStartup.createGlobalDatabase(SdrTrunkDatabasePath.getDatabasePath(sourceRoot));
        Path targetRoot = Files.createDirectory(mTemporaryFolder.resolve("posix-import-target"));
        PosixFileAttributeView view = Files.getFileAttributeView(targetRoot, PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "POSIX file attributes are unavailable");
        view.setPermissions(PosixFilePermissions.fromString("rwx------"));
        PosixFileAttributes before = view.readAttributes();

        new ApplicationMigrationService().importPrevious(sourceRoot, targetRoot, null);

        PosixFileAttributes after = Files.readAttributes(targetRoot, PosixFileAttributes.class);
        assertEquals(before.permissions(), after.permissions());
        assertEquals(before.owner(), after.owner());
        assertEquals(before.group(), after.group());
    }

    @Test
    void migratesExactAlpha7CurrentProfileAndKeepsAnUnchangedAlpha7Backup() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("alpha7-current-data");
        Path database = Alpha7TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
        insertAlpha7Sentinel(database);

        ApplicationMigrationService.MigrationResult result =
            new ApplicationMigrationService().migrateCurrent(dataRoot, null);

        assertFalse(result.importedPreviousProfile());
        assertEquals(new ApplicationMigrationService.MigrationState(3, 21, 2, null), result.sourceState());
        assertNotNull(result.safetyBackup());
        assertEquals(new ApplicationMigrationService.MigrationState(3, 21, 2, null),
            ApplicationMigrationService.readMigrationState(result.safetyBackup()));
        assertAlpha7Sentinel(result.safetyBackup());
        assertEquals(currentState(), ApplicationMigrationService.readMigrationState(database));
        assertCurrentSentinel(database);
        assertEquals("wal", journalMode(database));
    }

    @Test
    void importsExactAlpha7ProfileWithoutChangingItsSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("alpha7-import-source");
        Path sourceDatabase = Alpha7TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(sourceRoot));
        insertAlpha7Sentinel(sourceDatabase);
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("alpha7-import-target");

        ApplicationMigrationService.MigrationResult result =
            new ApplicationMigrationService().importPrevious(sourceRoot, targetRoot, null);

        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertTrue(result.importedPreviousProfile());
        assertEquals(new ApplicationMigrationService.MigrationState(3, 21, 2, null), result.sourceState());
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertAlpha7Sentinel(sourceDatabase);
        assertEquals(currentState(), ApplicationMigrationService.readMigrationState(targetDatabase));
        assertCurrentSentinel(targetDatabase);
        assertEquals("wal", journalMode(targetDatabase));
    }

    @Test
    void discardsBrokenAlpha7SiteHistoryWithoutBlockingConfigurationImport() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("alpha7-orphaned-site-history-source");
        Path sourceDatabase = Alpha7TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(sourceRoot));
        try(Connection connection = open(sourceDatabase); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO trunked_site_channel_summary(
                    guid, channel_number, inbound_channel_number, timeslot, frequency_hz,
                    first_seen_ms, last_seen_ms, observation_count
                ) VALUES ('missing-site', 1, 1, 0, 851000000, 1, 1, 1)
                """);
        }
        assertEquals("1", scalar(sourceDatabase, "SELECT count(*) FROM pragma_foreign_key_check"));
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("alpha7-orphaned-site-history-target");

        new ApplicationMigrationService().importPrevious(sourceRoot, targetRoot, null);

        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertEquals("1", scalar(sourceDatabase,
            "SELECT count(*) FROM trunked_site_channel_summary WHERE guid='missing-site'"));
        assertEquals(currentState(), ApplicationMigrationService.readMigrationState(targetDatabase));
        assertEquals("0", scalar(targetDatabase, "SELECT count(*) FROM trunked_site_channel_summary"));
        assertEquals("0", scalar(targetDatabase, "SELECT count(*) FROM pragma_foreign_key_check"));
    }

    @Test
    void unreleasedPredecessorIsRefusedBeforeAnyBackupOrMutation() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("pre-release-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        removeDmrActivitySchema(database);
        byte[] before = sha256(database);

        IOException exception = assertThrows(IOException.class,
            () -> new ApplicationMigrationService().migrateCurrent(dataRoot, null));

        assertTrue(exception.getMessage().contains("complete Alpha 7 database"));
        assertArrayEquals(before, sha256(database));
        assertFalse(Files.exists(database.getParent().resolve("backups")));
    }

    @Test
    void rejectsMainAndAlpha7RecordingCatalogFootprintsBeforeCreatingMigrationOutput() throws Exception
    {
        for(boolean alpha7: List.of(false, true))
        {
            String label = alpha7 ? "alpha7" : "current";
            Path dataRoot = mTemporaryFolder.resolve(label + "-recorded-call-source");
            Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
            if(alpha7)
            {
                Alpha7TestDatabase.create(database);
            }
            else
            {
                SdrTrunkDatabaseStartup.createGlobalDatabase(database);
            }
            try(Connection connection = open(database); Statement statement = connection.createStatement())
            {
                statement.executeUpdate("CREATE TABLE recorded_call_private_payload(id INTEGER PRIMARY KEY)");
            }
            byte[] before = sha256(database);
            Path targetRoot = mTemporaryFolder.resolve(label + "-recorded-call-target");

            SQLException importFailure = assertThrows(SQLException.class,
                () -> new ApplicationMigrationService().importPrevious(dataRoot, targetRoot, null));
            assertTrue(importFailure.getMessage().contains("webfirst managed-recording"));
            assertFalse(Files.exists(targetRoot));
            assertArrayEquals(before, sha256(database));

            SQLException currentFailure = assertThrows(SQLException.class,
                () -> new ApplicationMigrationService().migrateCurrent(dataRoot, null));
            assertTrue(currentFailure.getMessage().contains("webfirst managed-recording"));
            assertFalse(Files.exists(database.getParent().resolve("backups")));
            assertArrayEquals(before, sha256(database));
        }
    }

    @Test
    void helperFailureLeavesTheCurrentDatabaseUntouched() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("failure-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        insertAlias(database, "Do Not Lose");
        byte[] before = sha256(database);
        AtomicReference<Path> stagedDatabase = new AtomicReference<>();
        ApplicationMigrationService service = new ApplicationMigrationService(
            (source, destination) -> Files.copy(source, destination),
            (staged, source, target) ->
            {
                stagedDatabase.set(staged);
                Files.writeString(Path.of(staged + "-journal"), "private rollback content");
                Files.writeString(Path.of(staged + "-wal"), "private WAL content");
                Files.writeString(Path.of(staged + "-shm"), "private shared-memory content");
                throw new IOException("forced helper failure");
            });

        IOException exception = assertThrows(IOException.class, () -> service.migrateCurrent(dataRoot, null));

        assertTrue(exception.getMessage().contains("forced helper failure"));
        assertArrayEquals(before, sha256(database));
        assertEquals(1, count(database, "alias"));
        assertNotNull(stagedDatabase.get());
        for(String suffix: List.of("", "-journal", "-wal", "-shm"))
        {
            assertFalse(Files.exists(suffix.isEmpty() ? stagedDatabase.get() :
                Path.of(stagedDatabase.get() + suffix)));
        }
        Path backupDirectory = database.getParent().resolve("backups");
        try(var paths = Files.list(backupDirectory))
        {
            List<Path> backups = paths.toList();
            assertEquals(1, backups.size());
            assertEquals(currentState(), ApplicationMigrationService.readMigrationState(backups.getFirst()));
        }
    }

    @Test
    void snapshotFailureRemovesEveryIncompleteBackupArtifact() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("snapshot-failure-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        byte[] before = sha256(database);
        ApplicationMigrationService service = new ApplicationMigrationService(
            (source, destination) ->
            {
                Files.writeString(destination, "partial backup");
                Files.writeString(Path.of(destination + "-journal"), "partial journal");
                Files.writeString(Path.of(destination + "-wal"), "partial wal");
                Files.writeString(Path.of(destination + "-shm"), "partial shm");
                throw new IOException("forced snapshot failure");
            },
            (staged, source, target) -> "must not run");

        IOException exception = assertThrows(IOException.class, () -> service.migrateCurrent(dataRoot, null));

        assertTrue(exception.getMessage().contains("forced snapshot failure"));
        assertArrayEquals(before, sha256(database));
        Path backupDirectory = database.getParent().resolve("backups");
        try(var paths = Files.list(backupDirectory))
        {
            assertTrue(paths.findAny().isEmpty());
        }
    }

    @Test
    void helperCannotPromoteAWebfirstRecordingCatalog() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("source-main-data");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(sourceDatabase);
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("target-main-data");
        ApplicationMigrationService service = new ApplicationMigrationService(
            (source, destination) ->
            {
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
            },
            (staged, source, target) ->
            {
                try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + staged);
                    Statement statement = connection.createStatement())
                {
                    statement.executeUpdate("CREATE TABLE recorded_call(id INTEGER PRIMARY KEY)");
                }
                catch(java.sql.SQLException e)
                {
                    throw new IOException("Unable to inject incompatible test schema", e);
                }
                return "injected incompatible schema";
            });

        java.sql.SQLException exception = assertThrows(java.sql.SQLException.class,
            () -> service.importPrevious(sourceRoot, targetRoot, null));

        assertTrue(exception.getMessage().contains("webfirst managed-recording"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertFalse(Files.exists(targetRoot));
    }

    @Test
    void refusesImportThroughSymlinkThatPhysicallyTargetsTheSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("physical-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(sourceDatabase);
        byte[] sourceHash = sha256(sourceDatabase);
        Path sourceLink = mTemporaryFolder.resolve("source-link");
        try
        {
            Files.createSymbolicLink(sourceLink, sourceRoot);
        }
        catch(UnsupportedOperationException | IOException | SecurityException e)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }
        Path apparentlySeparateTarget = sourceLink.resolve("jmbe/imported-data");

        IOException exception = assertThrows(IOException.class,
            () -> new ApplicationMigrationService().importPrevious(sourceRoot, apparentlySeparateTarget, null));

        assertTrue(exception.getMessage().contains("overlap"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertFalse(Files.exists(apparentlySeparateTarget));
    }

    @Test
    void refusesExistingTargetSymlinkWhoseInstallParentIsInsideSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("source-with-target-link");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(sourceDatabase);
        byte[] sourceHash = sha256(sourceDatabase);
        Path installParent = Files.createDirectories(sourceRoot.resolve("jmbe"));
        Path externalEmptyDirectory = Files.createDirectories(mTemporaryFolder.resolve("external-empty"));
        Path targetLink = installParent.resolve("imported-data");
        try
        {
            Files.createSymbolicLink(targetLink, externalEmptyDirectory);
        }
        catch(UnsupportedOperationException | IOException | SecurityException e)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }

        IOException exception = assertThrows(IOException.class,
            () -> new ApplicationMigrationService().importPrevious(sourceRoot, targetLink, null));

        assertTrue(exception.getMessage().contains("overlap"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertTrue(Files.isSymbolicLink(targetLink));
    }

    @Test
    void alpha7AliasExpansionEstimateAccountsForDuplicatedLargeDescriptions() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("alpha7-expansion-source");
        Path database = Alpha7TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(sourceRoot));
        try(Connection connection = open(database);
            PreparedStatement alias = connection.prepareStatement("""
                INSERT INTO alias(id, sort_order, name, description, alias_list_name)
                VALUES (1, 1, 'Large Alias', ?, 'Large List')
                """);
            PreparedStatement matcher = connection.prepareStatement("""
                INSERT INTO alias_talkgroup(
                    alias_id, sort_order, protocol, value, fully_qualified, ranged
                ) VALUES (1, ?, 'APCO25', ?, 0, 0)
                """);
            PreparedStatement route = connection.prepareStatement("""
                INSERT INTO alias_broadcast_channel(alias_id, sort_order, channel_name)
                VALUES (1, 1, ?)
                """))
        {
            alias.setString(1, "x".repeat(128 * 1024));
            alias.executeUpdate();
            for(int index = 1; index <= 32; index++)
            {
                matcher.setInt(1, index);
                matcher.setInt(2, index);
                matcher.executeUpdate();
            }
            route.setString(1, "r".repeat(64 * 1024));
            route.executeUpdate();
        }

        long fixedThreeCopies = Files.size(database) * 3L;
        long dataDrivenExpansion = ApplicationMigrationService.alpha7AliasExpansionEstimate(database);
        long reservedWorkingSpace = ApplicationMigrationService.alpha7AliasWorkingSpace(database);

        assertTrue(dataDrivenExpansion > fixedThreeCopies,
            "Duplicated Alias-v4 payload must add a bound beyond the old fixed database multiplier");
        assertTrue(dataDrivenExpansion >= 32L * 8L * 64L * 1024L * 3L,
            "Each possible cloned route reserves its table, unique-index, and name-index payload");
        assertEquals(dataDrivenExpansion * 2L, reservedWorkingSpace,
            "Database and WAL can contain the expanded alias pages at the same time");
    }

    private static void insertAlias(Path database, String name) throws Exception
    {
        try(Connection connection = open(database);
            Statement listStatement = connection.createStatement();
            var statement = connection.prepareStatement("""
                INSERT INTO alias(alias_list_id, name, matcher_type, protocol, value)
                VALUES (1, ?, 'TALKGROUP', 'APCO25', 1)
                """))
        {
            listStatement.executeUpdate("""
                INSERT INTO alias_list(id, name, family)
                VALUES (1, 'Test', 'P25')
                """);
            statement.setString(1, name);
            statement.executeUpdate();
        }
    }

    private static void insertAlpha7Sentinel(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias(id, sort_order, name, alias_list_name)
                VALUES (9001, 9001, 'Alpha 7 Sentinel', 'Alpha 7 Sentinel List')
                """);
            statement.executeUpdate("""
                INSERT INTO alias_talkgroup(alias_id, sort_order, protocol, value)
                VALUES (9001, 1, 'APCO25', 321)
                """);
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES
                    ('alpha7_release_sentinel', '{"value":"preserved"}', 1),
                    ('tuner.settings',
                     '{"disabledTuners":[],"tunerConfigurations":[{"type":"recordingTunerConfiguration","uniqueID":"Release Test Recording","path":"/previous/profile/baseband/test.bits"}]}',
                     1)
                """);
            statement.executeUpdate("""
                INSERT INTO application_icons(key, icons_json, updated_at_ms)
                VALUES ('icons', '{"path":"/previous/profile/icons/custom.png"}', 1)
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel_map(id, sort_order, name, config_json)
                VALUES (9001, 9001, 'Alpha 7 Map', '{"mapping":"preserved"}')
                """);
        }
    }

    private static void assertAlpha7Sentinel(Path database) throws Exception
    {
        assertEquals("1", scalar(database, """
            SELECT count(*) FROM alias a JOIN alias_talkgroup t ON t.alias_id=a.id
            WHERE a.name='Alpha 7 Sentinel' AND a.alias_list_name='Alpha 7 Sentinel List'
              AND t.protocol='APCO25' AND t.value=321
            """));
        assertEquals("{\"value\":\"preserved\"}", scalar(database, """
            SELECT settings_json FROM application_settings WHERE key='alpha7_release_sentinel'
            """));
        assertEquals("/previous/profile/baseband/test.bits", scalar(database, """
            SELECT json_extract(settings_json, '$.tunerConfigurations[0].path')
            FROM application_settings WHERE key='tuner.settings'
            """));
        assertEquals("/previous/profile/icons/custom.png", scalar(database, """
            SELECT json_extract(icons_json, '$.path') FROM application_icons WHERE key='icons'
            """));
        assertEquals("{\"mapping\":\"preserved\"}", scalar(database, """
            SELECT config_json FROM configuration_channel_map WHERE name='Alpha 7 Map'
            """));
    }

    private static void assertCurrentSentinel(Path database) throws Exception
    {
        assertEquals("1", scalar(database, """
            SELECT count(*) FROM alias a JOIN alias_list l ON l.id=a.alias_list_id
            WHERE a.name='Alpha 7 Sentinel' AND l.name='Alpha 7 Sentinel List' AND l.family='P25'
              AND a.matcher_type='TALKGROUP' AND a.protocol='APCO25' AND a.value=321
            """));
        assertEquals("{\"value\":\"preserved\"}", scalar(database, """
            SELECT settings_json FROM application_settings WHERE key='alpha7_release_sentinel'
            """));
        assertEquals("/previous/profile/baseband/test.bits", scalar(database, """
            SELECT json_extract(settings_json, '$.tunerConfigurations[0].path')
            FROM application_settings WHERE key='tuner.settings'
            """));
        assertEquals("/previous/profile/icons/custom.png", scalar(database, """
            SELECT json_extract(icons_json, '$.path') FROM application_icons WHERE key='icons'
            """));
        assertEquals("{\"mapping\":\"preserved\"}", scalar(database, """
            SELECT config_json FROM configuration_channel_map WHERE name='Alpha 7 Map'
            """));
    }

    private static ApplicationMigrationService.MigrationState currentState()
    {
        return new ApplicationMigrationService.MigrationState(4, P25ActivityLogSchema.SCHEMA_VERSION, 2, 1);
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

    private static int count(Path database, String table) throws Exception
    {
        try(Connection connection = open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static String scalar(Path database, String sql) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static byte[] sha256(Path path) throws Exception
    {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }

    private static String journalMode(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA journal_mode"))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
