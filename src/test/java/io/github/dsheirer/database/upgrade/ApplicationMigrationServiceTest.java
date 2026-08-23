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
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
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
    void supportsExactAlpha9AndCurrentSchemaTuples()
    {
        ApplicationMigrationService.MigrationState current =
            currentState();
        assertTrue(current.supported());
        assertFalse(current.requiresMigration());
        assertEquals("", current.requiredChanges());

        ApplicationMigrationService.MigrationState alpha9 =
            new ApplicationMigrationService.MigrationState(
                ApplicationMigrationService.ALPHA_9_ALIAS_VERSION,
                ApplicationMigrationService.ALPHA_9_P25_VERSION, 2, 1);
        assertTrue(alpha9.supported());
        assertTrue(alpha9.requiresMigration());
        assertTrue(alpha9.requiredChanges().contains("preserve current P25 affiliations"));
        assertTrue(alpha9.requiredChanges().contains("site presence empty"));

        ApplicationMigrationService.MigrationState alpha7 =
            new ApplicationMigrationService.MigrationState(3, 21, 2, null);
        assertFalse(alpha7.supported());
        assertFalse(alpha7.requiresMigration());
        assertTrue(alpha7.requiredChanges().contains("no bundled transition"));

        for(ApplicationMigrationService.MigrationState predecessor: List.of(
            new ApplicationMigrationService.MigrationState(2, 20, 2, null),
            new ApplicationMigrationService.MigrationState(3, 21, 2, 1),
            new ApplicationMigrationService.MigrationState(
                ApplicationMigrationService.CURRENT_ALIAS_VERSION - 1,
                P25ActivityLogSchema.SCHEMA_VERSION, 2, 1),
            new ApplicationMigrationService.MigrationState(
                ApplicationMigrationService.CURRENT_ALIAS_VERSION,
                P25ActivityLogSchema.SCHEMA_VERSION - 1, 2, 1),
            new ApplicationMigrationService.MigrationState(
                ApplicationMigrationService.CURRENT_ALIAS_VERSION,
                P25ActivityLogSchema.SCHEMA_VERSION, null, 1),
            new ApplicationMigrationService.MigrationState(
                ApplicationMigrationService.CURRENT_ALIAS_VERSION,
                P25ActivityLogSchema.SCHEMA_VERSION, 2, null)))
        {
            assertFalse(predecessor.supported());
            assertFalse(predecessor.requiresMigration());
            assertTrue(predecessor.requiredChanges().contains("no bundled transition"));
        }
    }

    @Test
    void migratesExactAlpha9ProfileWithSafetyBackup() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("alpha9-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Alpha9TestDatabase.create(database);
        insertAlias(database, "Retained Alpha 9 Alias");

        ApplicationMigrationService.MigrationResult result =
            new ApplicationMigrationService().migrateCurrent(dataRoot, null);

        assertFalse(result.importedPreviousProfile());
        assertTrue(result.sourceState().alpha9());
        assertTrue(result.helperOutput().contains("Alpha 8/Alpha 9 layout migration"));
        assertNotNull(result.safetyBackup());
        assertEquals("4", scalar(result.safetyBackup(), """
            SELECT value FROM database_metadata WHERE key='alias_schema_version'
            """));
        assertEquals("24", scalar(result.safetyBackup(), """
            SELECT value FROM database_metadata WHERE key='p25_activity_schema_version'
            """));
        assertEquals(1, count(result.safetyBackup(), "alias"));
        assertEquals(currentState(), ApplicationMigrationService.readMigrationState(database));
        assertEquals(1, count(database, "alias"));
        assertEquals("wal", journalMode(database));
    }

    @Test
    void importsExactAlpha9ProfileThroughMigrateExistingWithoutChangingSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("alpha9-import-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        Alpha9TestDatabase.create(sourceDatabase);
        insertAlias(sourceDatabase, "Imported Alpha 9 Alias");
        Path sourceJmbe = Files.createDirectories(sourceRoot.resolve("jmbe/nested")).resolve("jmbe-test.jar");
        Path sourceModule = Files.createDirectories(sourceRoot.resolve("modules")).resolve("module-test.jar");
        Files.write(sourceJmbe, new byte[] {1, 2, 3});
        Files.write(sourceModule, new byte[] {4, 5, 6});
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = Files.createDirectory(mTemporaryFolder.resolve("alpha9-import-target"));

        ApplicationMigrationService.MigrationResult result = new ApplicationMigrationService()
            .importPrevious(sourceRoot, targetRoot, null);

        assertTrue(result.importedPreviousProfile());
        assertTrue(result.sourceState().alpha9());
        assertTrue(result.helperOutput().contains("Alpha 8/Alpha 9 layout migration"));
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertEquals(currentState(), ApplicationMigrationService.readMigrationState(targetDatabase));
        assertEquals("Imported Alpha 9 Alias", scalar(targetDatabase,
            "SELECT name FROM alias WHERE id=1"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertEquals("4", scalar(sourceDatabase, """
            SELECT value FROM database_metadata WHERE key='alias_schema_version'
            """));
        assertEquals("24", scalar(sourceDatabase, """
            SELECT value FROM database_metadata WHERE key='p25_activity_schema_version'
            """));
        assertArrayEquals(Files.readAllBytes(sourceJmbe),
            Files.readAllBytes(targetRoot.resolve("jmbe/nested/jmbe-test.jar")));
        assertArrayEquals(Files.readAllBytes(sourceModule),
            Files.readAllBytes(targetRoot.resolve("modules/module-test.jar")));
        assertEquals("wal", journalMode(targetDatabase));
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
    void importsExactCurrentProfileWithoutLosingConfigurationOrHistory() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("source-data");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(sourceDatabase);
        insertAlias(sourceDatabase, "Keep Me");
        insertCurrentProfileSentinels(sourceDatabase);
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
        assertCurrentProfileSentinels(targetDatabase);
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
    void unreleasedPredecessorIsRefusedBeforeAnyBackupOrMutation() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("pre-release-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        removeDmrActivitySchema(database);
        byte[] before = sha256(database);

        IOException exception = assertThrows(IOException.class,
            () -> new ApplicationMigrationService().migrateCurrent(dataRoot, null));

        assertTrue(exception.getMessage().contains("exact shared v0.6.2 Alpha 8/Alpha 9 database layout"));
        assertArrayEquals(before, sha256(database));
        assertFalse(Files.exists(database.getParent().resolve("backups")));
    }

    @Test
    void rejectsMainRecordingCatalogFootprintBeforeCreatingMigrationOutput() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("current-recorded-call-source");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE recorded_call_private_payload(id INTEGER PRIMARY KEY)");
        }
        byte[] before = sha256(database);
        Path targetRoot = mTemporaryFolder.resolve("current-recorded-call-target");

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

    private static void insertAlias(Path database, String name) throws Exception
    {
        try(Connection connection = open(database); Statement listStatement = connection.createStatement())
        {
            listStatement.executeUpdate("""
                INSERT INTO alias_list(name, family)
                SELECT 'Test', 'P25'
                WHERE NOT EXISTS (
                    SELECT 1 FROM alias_list WHERE name = 'Test' COLLATE NOCASE
                )
                """);

            try(var statement = connection.prepareStatement("""
                INSERT INTO alias(alias_list_id, name, matcher_type, protocol, value)
                SELECT id, ?, 'TALKGROUP', 'APCO25', 1
                FROM alias_list
                WHERE name = 'Test' COLLATE NOCASE
                """))
            {
                statement.setString(1, name);
                statement.executeUpdate();
            }
        }
    }

    private static void insertCurrentProfileSentinels(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO configuration_channel(
                    sort_order, system_name, site_name, name, alias_list_name, decoder_type,
                    source_type, primary_frequency_hz, frequency_count, config_json
                ) VALUES (
                    1, 'Preserved System', 'Preserved Site', 'Preserved Channel', 'Test', 'DMR',
                    'TUNER', 451000000, 1, '{}'
                )
                """);
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('current_profile_sentinel', '{"value":"preserved"}', 1000)
                """);
            statement.executeUpdate("""
                INSERT INTO p25_control_channel_quality(
                    guid, frequency_hz, bucket_start_ms, observed_at_ms, signal_dbfs,
                    decode_health_pct, valid_frames, invalid_frames, corrected_bits,
                    sync_loss_bits, dropped_bits, last_valid_decode_ms
                ) VALUES (
                    'preserved-quality', 851012500, 1000, 2000, -72.5,
                    92.5, 100, 3, 4, 5, 6, 1900
                )
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_context(
                    id, context_key, guid, kind_code, protocol_code, channel_name,
                    alias_list_name, decoder, first_seen_ms, last_seen_ms, primary_frequency_hz
                ) VALUES (
                    7001, 'preserved-dmr-context', 'preserved-dmr-guid', 2, 3, 'Preserved DMR',
                    'Test', 'DMR', 1000, 2000, 451000000
                )
                """);
            statement.executeUpdate("""
                INSERT INTO dmr_conventional_talkgroup_summary(
                    context_id, frequency_hz, timeslot, talkgroup_id, first_seen_ms,
                    last_seen_ms, call_count, encrypted_count, last_source_radio_id
                ) VALUES (7001, 451000000, 1, 321, 1000, 2000, 7, 2, 654)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_snapshot(
                    guid, snapshot_hash, protocol_code, variant_code, identity_domain_code,
                    configured_system, channel_name, decoder, first_seen_ms, last_seen_ms,
                    observation_count
                ) VALUES (
                    'preserved-site-guid', 'preserved-site-hash', 3, 1, 1,
                    'Preserved System', 'Preserved Site', 'DMR', 1000, 2000, 9
                )
                """);
        }
    }

    private static void assertCurrentProfileSentinels(Path database) throws Exception
    {
        assertEquals("Preserved Channel", scalar(database, """
            SELECT name FROM configuration_channel WHERE primary_frequency_hz=451000000
            """));
        assertEquals("{\"value\":\"preserved\"}", scalar(database, """
            SELECT settings_json FROM application_settings WHERE key='current_profile_sentinel'
            """));
        assertEquals("92.5", scalar(database, """
            SELECT decode_health_pct FROM p25_control_channel_quality WHERE guid='preserved-quality'
            """));
        assertEquals("7", scalar(database, """
            SELECT call_count FROM dmr_conventional_talkgroup_summary
            WHERE context_id=7001 AND talkgroup_id=321
            """));
        assertEquals("9", scalar(database, """
            SELECT observation_count FROM trunked_site_snapshot WHERE guid='preserved-site-guid'
            """));
    }

    private static ApplicationMigrationService.MigrationState currentState()
    {
        return new ApplicationMigrationService.MigrationState(
            ApplicationMigrationService.CURRENT_ALIAS_VERSION,
            ApplicationMigrationService.CURRENT_P25_VERSION,
            TrunkedSiteSchema.SCHEMA_VERSION,
            ApplicationMigrationService.CURRENT_DMR_VERSION);
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
