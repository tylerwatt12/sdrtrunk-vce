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

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.web.auth.WebAccessService;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationMigrationServiceTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void readsExactFormat1AndCurrentCatalogPlans() throws Exception
    {
        Path format1Database = Format1TestDatabase.create(
            SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder.resolve("format-1-plan")));
        DatabaseMigrationChain.PreflightReport format1Plan =
            ApplicationMigrationService.readMigrationPlan(format1Database);
        assertFormat(format1Plan.source(), 1, "alpha8-shared", false);
        assertEquals(DatabaseFormatCatalog.current(), format1Plan.target());
        assertEquals(9, format1Plan.steps().size());
        assertEquals(1, format1Plan.steps().getFirst().sourceVersion());
        assertEquals(2, format1Plan.steps().getFirst().targetVersion());
        assertEquals(9, format1Plan.steps().getLast().sourceVersion());
        assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, format1Plan.steps().getLast().targetVersion());
        assertTrue(format1Plan.steps().get(1).effects().stream()
            .anyMatch(effect -> "unassigned channel Alias Lists".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(2).effects().stream()
            .anyMatch(effect -> "physical receiver-leg call projections".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(3).effects().stream()
            .anyMatch(effect -> "web accounts".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(format1Plan.steps().size() - 5).effects().stream()
            .anyMatch(effect -> "configured conventional receiver-context identities".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(format1Plan.steps().size() - 4).effects().stream()
            .anyMatch(effect -> "per-user browser preference documents".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(format1Plan.steps().size() - 3).effects().stream()
            .anyMatch(effect -> "per-user receiver-health alert settings".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(format1Plan.steps().size() - 2).effects().stream()
            .anyMatch(effect -> "per-user Live presentation settings".equals(effect.subject())));
        assertTrue(format1Plan.steps().getLast().effects().stream()
            .anyMatch(effect -> "P25 bandplan overrides".equals(effect.subject())));

        Path currentDatabase = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder.resolve("current-plan"));
        SdrTrunkDatabaseStartup.createGlobalDatabase(currentDatabase);
        DatabaseMigrationChain.PreflightReport currentPlan =
            ApplicationMigrationService.readMigrationPlan(currentDatabase);
        assertFormat(currentPlan.source(), DatabaseFormatCatalog.CURRENT_VERSION,
            DatabaseFormatCatalog.current().id(), true);
        assertEquals(DatabaseFormatCatalog.current(), currentPlan.target());
        assertTrue(currentPlan.steps().isEmpty());
    }

    @Test
    void migratesExactFormat1ProfileWithSafetyBackup() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("format-1-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Format1TestDatabase.create(database);
        insertAlias(database, "Retained Format 1 Alias");

        ApplicationMigrationService.MigrationResult result =
            new ApplicationMigrationService().migrateCurrent(dataRoot, null);

        assertFalse(result.importedPreviousProfile());
        assertFormat(result.sourceFormat(), 1, "alpha8-shared", false);
        assertEquals(result.sourceFormat(), result.sourcePlan().source());
        assertTrue(result.helperOutput().contains("Migrated database format 1 [alpha8-shared]"));
        assertNotNull(result.safetyBackup());
        assertEquals("4", scalar(result.safetyBackup(), """
            SELECT value FROM database_metadata WHERE key='alias_schema_version'
            """));
        assertEquals("24", scalar(result.safetyBackup(), """
            SELECT value FROM database_metadata WHERE key='p25_activity_schema_version'
            """));
        assertEquals(1, count(result.safetyBackup(), "alias"));
        assertCurrentFormat(database);
        assertEquals(1, count(database, "alias"));
        assertEquals("wal", journalMode(database));
    }

    @Test
    void importsExactFormat1ProfileThroughMigrateExistingWithoutChangingSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("format-1-import-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        Format1TestDatabase.create(sourceDatabase);
        insertAlias(sourceDatabase, "Imported Format 1 Alias");
        Path sourceJmbe = Files.createDirectories(sourceRoot.resolve("jmbe/nested")).resolve("jmbe-test.jar");
        Path sourceModule = Files.createDirectories(sourceRoot.resolve("modules")).resolve("module-test.jar");
        Files.write(sourceJmbe, new byte[] {1, 2, 3});
        Files.write(sourceModule, new byte[] {4, 5, 6});
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = Files.createDirectory(mTemporaryFolder.resolve("format-1-import-target"));

        ApplicationMigrationService.MigrationResult result = new ApplicationMigrationService()
            .importPrevious(sourceRoot, targetRoot, null);

        assertTrue(result.importedPreviousProfile());
        assertEquals(PreviousBuildLocator.InputScope.PORTABLE_PROFILE, result.inputScope());
        assertFormat(result.sourceFormat(), 1, "alpha8-shared", false);
        assertEquals(result.sourceFormat(), result.sourcePlan().source());
        assertTrue(result.helperOutput().contains("Migrated database format 1 [alpha8-shared]"));
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertCurrentFormat(targetDatabase);
        assertEquals("Imported Format 1 Alias", scalar(targetDatabase,
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
    void directDatabaseImportDoesNotCopyOrRebaseNeighboringProfileData() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("database-only-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(sourceDatabase);
        insertAlias(sourceDatabase, "Database Only Alias");
        byte[] sourceHash = sha256(sourceDatabase);
        Files.createDirectories(sourceRoot.resolve("jmbe"));
        Files.writeString(sourceRoot.resolve("jmbe/jmbe-test.jar"), "must not be copied");
        Files.createDirectories(sourceRoot.resolve("modules"));
        Files.writeString(sourceRoot.resolve("modules/module-test.jar"), "must not be copied");
        Files.createDirectories(sourceRoot.resolve("vault"));
        Files.writeString(sourceRoot.resolve("vault/encryption-key-vault.sqlite"), "invalid and not selected");
        PreviousBuildLocator.Selection selection = PreviousBuildLocator.resolveSelection(sourceDatabase)
            .orElseThrow();
        Path targetRoot = mTemporaryFolder.resolve("database-only-target");
        AtomicReference<Path> receivedSourceRoot = new AtomicReference<>();
        AtomicReference<Path> receivedTargetRoot = new AtomicReference<>();
        ApplicationMigrationService service = new ApplicationMigrationService(SqliteDatabaseSnapshot::create,
            (staged, source, target) ->
            {
                receivedSourceRoot.set(source);
                receivedTargetRoot.set(target);
                return "database-only validation";
            });
        List<String> progress = new ArrayList<>();

        ApplicationMigrationService.MigrationResult result =
            service.importPrevious(selection, targetRoot, progress::add);

        assertFalse(result.importedPreviousProfile());
        assertEquals(PreviousBuildLocator.InputScope.DATABASE_FILE, result.inputScope());
        assertFormat(result.sourceFormat(), DatabaseFormatCatalog.CURRENT_VERSION,
            DatabaseFormatCatalog.current().id(), true);
        assertEquals(null, receivedSourceRoot.get());
        assertEquals(null, receivedTargetRoot.get());
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertEquals("Database Only Alias", scalar(targetDatabase, "SELECT name FROM alias WHERE id=1"));
        assertFalse(Files.exists(targetRoot.resolve("jmbe")));
        assertFalse(Files.exists(targetRoot.resolve("modules")));
        assertFalse(Files.exists(targetRoot.resolve("vault")));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        int scopeIndex = java.util.stream.IntStream.range(0, progress.size())
            .filter(index -> progress.get(index).startsWith("Migration scope:"))
            .findFirst().orElseThrow();
        int copyIndex = progress.indexOf("Copying selected database");
        assertTrue(scopeIndex >= 0 && scopeIndex < copyIndex);
        assertTrue(progress.get(scopeIndex).contains("SQLite database only"));
    }

    @Test
    void selectedDatabaseReplacesActiveProfileAfterBackupAndStagedMigration() throws Exception
    {
        Path activeRoot = mTemporaryFolder.resolve("active-replacement-data");
        Path activeDatabase = SdrTrunkDatabasePath.getDatabasePath(activeRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(activeDatabase);
        insertAlias(activeDatabase, "Previous Active Alias");
        Path retainedJmbe = Files.createDirectories(activeRoot.resolve("jmbe")).resolve("retained.jar");
        Files.writeString(retainedJmbe, "keep current external file");

        Path sourceRoot = mTemporaryFolder.resolve("selected-old-database");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        Format1TestDatabase.create(sourceDatabase);
        insertAlias(sourceDatabase, "Imported Old Alias");
        Files.createDirectories(sourceRoot.resolve("jmbe"));
        Files.writeString(sourceRoot.resolve("jmbe/not-imported.jar"), "source neighbor");
        byte[] sourceHash = sha256(sourceDatabase);
        DatabaseMigrationChain.PreflightReport plan = ApplicationMigrationService.readMigrationPlan(sourceDatabase);
        List<String> progress = new ArrayList<>();

        ApplicationMigrationService.MigrationResult result = new ApplicationMigrationService()
            .replaceCurrentDatabase(sourceDatabase, activeRoot, plan, progress::add);

        assertFalse(result.importedPreviousProfile());
        assertEquals(PreviousBuildLocator.InputScope.DATABASE_FILE, result.inputScope());
        assertFormat(result.sourceFormat(), 1, "alpha8-shared", false);
        assertTrue(result.helperOutput().contains("Migrated database format 1 [alpha8-shared]"));
        assertNotNull(result.safetyBackup());
        assertEquals("Previous Active Alias", scalar(result.safetyBackup(),
            "SELECT name FROM alias WHERE id=1"));
        assertCurrentFormat(activeDatabase);
        assertEquals("Imported Old Alias", scalar(activeDatabase, "SELECT name FROM alias WHERE id=1"));
        assertEquals("required", scalar(activeDatabase, """
            SELECT value FROM database_metadata WHERE key='initial_admin_setup'
            """));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertEquals("keep current external file", Files.readString(retainedJmbe));
        assertFalse(Files.exists(activeRoot.resolve("jmbe/not-imported.jar")));
        assertTrue(progress.stream().anyMatch(step -> step.contains("SQLite database only")));
        assertTrue(progress.indexOf("Creating current database safety backup") <
            progress.indexOf("Replacing active database"));
        assertEquals("wal", journalMode(activeDatabase));
    }

    @Test
    void selectedDatabaseReplacementBindsTheApprovedPlanBeforeBackup() throws Exception
    {
        Path activeRoot = mTemporaryFolder.resolve("active-plan-binding-data");
        Path activeDatabase = SdrTrunkDatabasePath.getDatabasePath(activeRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(activeDatabase);
        insertAlias(activeDatabase, "Keep Active");
        byte[] activeHash = sha256(activeDatabase);

        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(
            mTemporaryFolder.resolve("selected-plan-binding-source"));
        SdrTrunkDatabaseStartup.createGlobalDatabase(sourceDatabase);
        DatabaseMigrationChain.PreflightReport approved =
            ApplicationMigrationService.readMigrationPlan(sourceDatabase);
        try(Connection connection = open(sourceDatabase); var statement = connection.prepareStatement(
            "UPDATE database_metadata SET value=? WHERE key=?"))
        {
            statement.setString(1, Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION - 1));
            statement.setString(2, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            assertEquals(1, statement.executeUpdate());
        }

        IOException exception = assertThrows(IOException.class,
            () -> new ApplicationMigrationService().replaceCurrentDatabase(sourceDatabase, activeRoot, approved,
                null));

        assertTrue(exception.getMessage().contains("SQLite database selected after confirmation"));
        assertArrayEquals(activeHash, sha256(activeDatabase));
        assertEquals("Keep Active", scalar(activeDatabase, "SELECT name FROM alias WHERE id=1"));
        assertFalse(Files.exists(activeDatabase.getParent().resolve("backups")));
    }

    @Test
    void selectedMarkerlessDatabaseWithAdministratorPreservesCredentialsAndCompletesSetup() throws Exception
    {
        Path activeRoot = mTemporaryFolder.resolve("active-admin-replacement-data");
        Path activeDatabase = SdrTrunkDatabasePath.getDatabasePath(activeRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(activeDatabase);

        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(
            mTemporaryFolder.resolve("selected-admin-source"));
        Format1TestDatabase.create(sourceDatabase);
        char[] password = "retained alpha administrator".toCharArray();
        LegacyWebAccessTestData.storePrimaryAdmin(sourceDatabase, password);

        new ApplicationMigrationService().replaceCurrentDatabase(sourceDatabase, activeRoot, null);

        assertEquals("complete", scalar(activeDatabase, """
            SELECT value FROM database_metadata WHERE key='initial_admin_setup'
            """));
        assertTrue(new WebAccessService(activeDatabase).authenticate("admin", password).isPresent());
    }

    @Test
    void selectedDatabaseHelperFailureLeavesActiveAndSourceUntouchedAndCleansStage() throws Exception
    {
        Path activeRoot = mTemporaryFolder.resolve("active-helper-failure-data");
        Path activeDatabase = SdrTrunkDatabasePath.getDatabasePath(activeRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(activeDatabase);
        insertAlias(activeDatabase, "Still Active");
        byte[] activeHash = sha256(activeDatabase);

        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(
            mTemporaryFolder.resolve("selected-helper-failure-source"));
        Format1TestDatabase.create(sourceDatabase);
        insertAlias(sourceDatabase, "Never Installed");
        byte[] sourceHash = sha256(sourceDatabase);
        AtomicReference<Path> stagedDatabase = new AtomicReference<>();
        ApplicationMigrationService service = new ApplicationMigrationService(SqliteDatabaseSnapshot::create,
            (staged, source, target) ->
            {
                stagedDatabase.set(staged);
                Files.writeString(Path.of(staged + "-journal"), "forced journal");
                Files.writeString(Path.of(staged + "-wal"), "forced wal");
                Files.writeString(Path.of(staged + "-shm"), "forced shared memory");
                throw new IOException("forced replacement helper failure");
            });

        IOException exception = assertThrows(IOException.class,
            () -> service.replaceCurrentDatabase(sourceDatabase, activeRoot, null));

        assertTrue(exception.getMessage().contains("forced replacement helper failure"));
        assertArrayEquals(activeHash, sha256(activeDatabase));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertEquals("Still Active", scalar(activeDatabase, "SELECT name FROM alias WHERE id=1"));
        assertNotNull(stagedDatabase.get());
        for(String suffix: List.of("", "-journal", "-wal", "-shm"))
        {
            assertFalse(Files.exists(suffix.isEmpty() ? stagedDatabase.get() :
                Path.of(stagedDatabase.get() + suffix)));
        }
        try(var backups = Files.list(activeDatabase.getParent().resolve("backups")))
        {
            List<Path> retained = backups.toList();
            assertEquals(1, retained.size());
            assertEquals("Still Active", scalar(retained.getFirst(), "SELECT name FROM alias WHERE id=1"));
        }
    }

    @Test
    void activeDatabaseCannotBeSelectedAsItsOwnReplacement() throws Exception
    {
        Path activeRoot = mTemporaryFolder.resolve("same-replacement-data");
        Path activeDatabase = SdrTrunkDatabasePath.getDatabasePath(activeRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(activeDatabase);

        IOException exception = assertThrows(IOException.class,
            () -> new ApplicationMigrationService().replaceCurrentDatabase(activeDatabase, activeRoot, null));

        assertTrue(exception.getMessage().contains("paths are the same"));
        assertFalse(Files.exists(activeDatabase.getParent().resolve("backups")));
    }

    @Test
    void activeDatabaseHardLinkCannotBeSelectedAsItsReplacement() throws Exception
    {
        Path activeRoot = mTemporaryFolder.resolve("hard-link-replacement-data");
        Path activeDatabase = SdrTrunkDatabasePath.getDatabasePath(activeRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(activeDatabase);
        Path hardLink = mTemporaryFolder.resolve("active-database-hard-link.sqlite");
        try
        {
            Files.createLink(hardLink, activeDatabase);
        }
        catch(IOException | UnsupportedOperationException e)
        {
            Assumptions.assumeTrue(false, "Hard links are unavailable: " + e.getMessage());
        }

        IOException exception = assertThrows(IOException.class,
            () -> new ApplicationMigrationService().replaceCurrentDatabase(hardLink, activeRoot, null));

        assertTrue(exception.getMessage().contains("same physical file"));
        assertFalse(Files.exists(activeDatabase.getParent().resolve("backups")));
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
    void refusesAStagedSnapshotWhoseMigrationPlanDiffersFromTheApprovedSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("plan-change-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(sourceDatabase);
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("plan-change-target");
        AtomicBoolean helperRan = new AtomicBoolean();
        ApplicationMigrationService service = new ApplicationMigrationService(
            (source, destination) ->
            {
                Files.createDirectories(destination.getParent());
                Files.copy(source, destination);
                try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + destination);
                    var statement = connection.prepareStatement(
                        "UPDATE database_metadata SET value=? WHERE key=?"))
                {
                    statement.setString(1, Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION - 1));
                    statement.setString(2, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
                    assertEquals(1, statement.executeUpdate());
                }
            },
            (staged, source, target) ->
            {
                helperRan.set(true);
                return "must not run";
            });

        IOException exception = assertThrows(IOException.class,
            () -> service.importPrevious(sourceRoot, targetRoot, null));

        assertTrue(exception.getMessage().contains("does not match the migration plan"));
        assertFalse(helperRan.get());
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertFalse(Files.exists(targetRoot));
    }

    @Test
    void refusesAChangedSourcePlanAfterOperatorApproval() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("approved-plan-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(sourceDatabase);
        DatabaseMigrationChain.PreflightReport approvedPlan =
            ApplicationMigrationService.readMigrationPlan(sourceDatabase);

        try(Connection connection = open(sourceDatabase); var statement = connection.prepareStatement(
            "UPDATE database_metadata SET value=? WHERE key=?"))
        {
            statement.setString(1, Integer.toString(DatabaseFormatCatalog.CURRENT_VERSION - 1));
            statement.setString(2, DatabaseFormatCatalog.FORMAT_VERSION_KEY);
            assertEquals(1, statement.executeUpdate());
        }

        Path targetRoot = mTemporaryFolder.resolve("approved-plan-target");
        PreviousBuildLocator.Selection selection = new PreviousBuildLocator.Selection(sourceRoot,
            PreviousBuildLocator.InputScope.PORTABLE_PROFILE);

        IOException exception = assertThrows(IOException.class,
            () -> new ApplicationMigrationService().importPrevious(selection, targetRoot, approvedPlan, null));

        assertTrue(exception.getMessage().contains("source database selected after confirmation"));
        assertFalse(Files.exists(targetRoot));
    }

    @Test
    void refusesSourceForeignKeyViolationsBeforeCreatingMigrationOutput() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("foreign-key-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(sourceDatabase);

        try(Connection connection = open(sourceDatabase); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.executeUpdate("""
                INSERT INTO alias(alias_list_id, name, matcher_type, protocol, value)
                VALUES (999999, 'Orphan Alias', 'TALKGROUP', 'APCO25', 1)
                """);
        }

        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("foreign-key-target");

        SQLException exception = assertThrows(SQLException.class,
            () -> new ApplicationMigrationService().importPrevious(sourceRoot, targetRoot, null));

        assertTrue(exception.getMessage().contains("foreign-key check failed"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertFalse(Files.exists(targetRoot));
    }

    @Test
    void readsTheExactCurrentFormatPlan() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("current-state");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        DatabaseMigrationChain.PreflightReport plan = ApplicationMigrationService.readMigrationPlan(database);

        assertFormat(plan.source(), DatabaseFormatCatalog.CURRENT_VERSION,
            DatabaseFormatCatalog.current().id(), true);
        assertEquals(DatabaseFormatCatalog.current(), plan.target());
        assertTrue(plan.steps().isEmpty());
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
        assertFormat(result.sourceFormat(), DatabaseFormatCatalog.CURRENT_VERSION,
            DatabaseFormatCatalog.current().id(), true);
        assertEquals(result.sourceFormat(), result.sourcePlan().source());
        assertTrue(result.helperOutput().contains("Application database migration and validation complete"));
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertEquals(1, count(targetDatabase, "alias"));
        assertCurrentProfileSentinels(targetDatabase);
        assertCurrentFormat(targetDatabase);
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
        assertCurrentFormat(result.safetyBackup());
        assertEquals(1, count(database, "alias"));
        assertCurrentFormat(database);
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

        SQLException exception = assertThrows(SQLException.class,
            () -> new ApplicationMigrationService().migrateCurrent(dataRoot, null));

        assertTrue(exception.getMessage().contains("Unrecognized SQLite database schema fingerprint"));
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
            assertCurrentFormat(backups.getFirst());
        }

        ApplicationMigrationService.MigrationResult retry =
            new ApplicationMigrationService().migrateCurrent(dataRoot, null);
        assertNotNull(retry.safetyBackup());
        assertCurrentFormat(database);
        assertEquals(1, count(database, "alias"));
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
    void invalidFinalConfigurationIsNotPromoted() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("invalid-config-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(sourceDatabase);
        insertCurrentProfileSentinels(sourceDatabase);
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("invalid-config-target");
        ApplicationMigrationService service = new ApplicationMigrationService(SqliteDatabaseSnapshot::create,
            (staged, source, target) ->
            {
                try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + staged);
                    Statement statement = connection.createStatement())
                {
                    statement.executeUpdate("""
                        UPDATE configuration_channel SET config_json='{}'
                        WHERE name='Preserved Channel'
                        """);
                }
                catch(SQLException e)
                {
                    throw new IOException("Unable to inject invalid configuration", e);
                }
                return "injected invalid configuration";
            });

        IOException exception = assertThrows(IOException.class,
            () -> service.importPrevious(sourceRoot, targetRoot, null));

        assertTrue(exception.getMessage().contains("canonical lowercase UUID"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertFalse(Files.exists(targetRoot));
    }

    @Test
    void targetPopulationAtPromotionTimePreventsImportWithoutLosingEitherSide() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("promotion-race-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(sourceDatabase);
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = Files.createDirectory(mTemporaryFolder.resolve("promotion-race-target"));
        Path competingFile = targetRoot.resolve("created-by-another-first-run.txt");

        IOException exception = assertThrows(IOException.class,
            () -> new ApplicationMigrationService().importPrevious(sourceRoot, targetRoot, progress ->
            {
                if("Finishing".equals(progress))
                {
                    try
                    {
                        Files.writeString(competingFile, "keep me");
                    }
                    catch(IOException e)
                    {
                        throw new java.io.UncheckedIOException(e);
                    }
                }
            }));

        assertTrue(exception.getMessage().contains("already contains data"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertEquals("keep me", Files.readString(competingFile));
        try(var paths = Files.list(mTemporaryFolder))
        {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString()
                .startsWith(".promotion-race-target.migration-")));
        }
    }

    @Test
    void atomicImportPromotionFailureRestoresThePreexistingEmptyTarget() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("atomic-promotion-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(sourceDatabase);
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = Files.createDirectory(mTemporaryFolder.resolve("atomic-promotion-target"));
        ApplicationMigrationService service = new ApplicationMigrationService(SqliteDatabaseSnapshot::create,
            ApplicationMigratorLauncher::run,
            (staged, target) ->
            {
                assertFalse(Files.exists(target));
                throw new IOException("forced atomic promotion failure");
            });

        IOException exception = assertThrows(IOException.class,
            () -> service.importPrevious(sourceRoot, targetRoot, null));

        assertTrue(exception.getMessage().contains("forced atomic promotion failure"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertTrue(Files.isDirectory(targetRoot));
        try(var paths = Files.list(targetRoot))
        {
            assertTrue(paths.findAny().isEmpty());
        }
        try(var paths = Files.list(mTemporaryFolder))
        {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString()
                .startsWith(".atomic-promotion-target.migration-")));
        }
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
            String configJson = channelJson("Preserved Channel", "Preserved System", "Preserved Site", "Test",
                DecoderType.P25_PHASE1, 451000000);
            String configurationId = OBJECT_MAPPER.readTree(configJson).path("configurationId").asText();
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_channel(
                    configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_name,
                    radres_guid, decoder_type, source_type, primary_frequency_hz, frequency_count, config_json
                ) VALUES (
                    ?, 'TRUNKED', 1, 'Preserved System', 'Preserved Site', 'Preserved Channel', 'Test',
                    '00000000-0000-4000-8000-000000007001', 'P25_PHASE1', 'TUNER', 451000000, 1, ?
                )
                """))
            {
                insert.setString(1, configurationId);
                insert.setString(2, configJson);
                insert.executeUpdate();
            }
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

    private static String channelJson(String name, String system, String site, String aliasListName,
                                      DecoderType decoderType, long frequency) throws Exception
    {
        Channel channel = new Channel(name);
        channel.setSystem(system);
        channel.setSite(site);
        channel.setAliasListName(aliasListName);
        channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(decoderType));
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(frequency);
        channel.setSourceConfiguration(source);
        return OBJECT_MAPPER.writeValueAsString(channel);
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

    private static void assertCurrentFormat(Path database) throws Exception
    {
        assertFormat(ApplicationMigrationService.readMigrationPlan(database).source(),
            DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.current().id(), true);
    }

    private static void assertFormat(DatabaseFormatCatalog.DetectedFormat format, int expectedVersion,
                                     String expectedId, boolean expectedMarkerPresent)
    {
        assertEquals(expectedVersion, format.version());
        assertEquals(expectedId, format.id());
        assertEquals(expectedMarkerPresent, format.markerPresent());
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
