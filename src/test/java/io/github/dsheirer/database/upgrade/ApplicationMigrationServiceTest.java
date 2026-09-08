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
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultPath;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultSchema;
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
        assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, format1Plan.steps().size());
        assertEquals(1, format1Plan.steps().getFirst().sourceVersion());
        assertEquals(2, format1Plan.steps().getFirst().targetVersion());
        assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 1,
            format1Plan.steps().get(format1Plan.steps().size() - 2).sourceVersion());
        assertEquals("repair-portable-preferences", format1Plan.steps().getLast().id());
        assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, format1Plan.steps().getLast().sourceVersion());
        assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, format1Plan.steps().getLast().targetVersion());
        assertTrue(format1Plan.steps().get(1).effects().stream()
            .anyMatch(effect -> "unassigned channel Alias Lists".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(2).effects().stream()
            .anyMatch(effect -> "receiver-derived activity and counters".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(3).effects().stream()
            .anyMatch(effect -> "web accounts".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(4).effects().stream()
            .anyMatch(effect -> "receiver-derived activity and counters".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(5).effects().stream()
            .anyMatch(effect -> "per-user browser preference documents".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(6).effects().stream()
            .anyMatch(effect -> "per-user receiver-health alert settings".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(7).effects().stream()
            .anyMatch(effect -> "per-user Live presentation settings".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(8).effects().stream()
            .anyMatch(effect -> "P25 bandplan overrides".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(9).effects().stream()
            .anyMatch(effect -> "missing factory Alias Lists".equals(effect.subject())));
        assertTrue(format1Plan.steps().get(10).effects().stream()
            .anyMatch(effect -> "per-user idle FFT channel markers".equals(effect.subject())));

        Path currentDatabase = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder.resolve("current-plan"));
        SdrTrunkTestDatabase.create(currentDatabase);
        DatabaseMigrationChain.PreflightReport currentPlan =
            ApplicationMigrationService.readMigrationPlan(currentDatabase);
        assertFormat(currentPlan.source(), DatabaseFormatCatalog.CURRENT_VERSION,
            DatabaseFormatCatalog.current().id(), true);
        assertEquals(DatabaseFormatCatalog.current(), currentPlan.target());
        assertTrue(currentPlan.steps().isEmpty());
    }

    @Test
    void migrationPlanUsesDeclaredEffectsWithoutExecutingLaterSteps() throws Exception
    {
        Path database = Format4TestDatabase.create(mTemporaryFolder.resolve("later-step-preflight.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('setup_wizard', '{"format_version":1,"complete":false}', 1)
                """);
        }

        DatabaseMigrationChain.PreflightReport plan = ApplicationMigrationService.readMigrationPlan(database);

        assertFormat(plan.source(), 4, "logical-call-site-observation-v28", true);
        assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 4 + 1, plan.steps().size());
        assertTrue(plan.steps().stream().flatMap(step -> step.effects().stream())
            .anyMatch(effect -> effect.affectedRows() == DatabaseMigrationEffect.UNKNOWN_COUNT));
        assertEquals("4", scalar(database, "SELECT value FROM database_metadata " +
            "WHERE key='database_format_version'"));
        assertEquals("1", scalar(database,
            "SELECT COUNT(*) FROM application_settings WHERE key='setup_wizard'"));
    }

    @Test
    void migratesExactFormat1ProfileWithSafetyBackup() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("format-1-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Format1TestDatabase.create(database);
        insertAlias(database, "Retained Format 1 Alias");

        ApplicationMigrationService.MigrationResult result =
            inProcessMigrationService().migrateCurrent(dataRoot, null);

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
        Path sourceVault = createValidVault(sourceRoot);
        byte[] sourceHash = sha256(sourceDatabase);
        byte[] sourceVaultHash = sha256(sourceVault);
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
        Path targetVault = EncryptionKeyVaultPath.getVaultPath(targetRoot);
        assertEquals("1", scalar(targetVault,
            "SELECT value FROM vault_metadata WHERE key='schema_version'"));
        assertArrayEquals(sourceVaultHash, sha256(sourceVault));
        assertTrue(result.helperOutput().contains("- JMBE library files: copied."));
        assertTrue(result.helperOutput().contains("- optional decoder module files: copied."));
        assertTrue(result.helperOutput().contains("- encryption-key vault: copied."));
        assertEquals("wal", journalMode(targetDatabase));
    }

    @Test
    void invalidOptionalVaultIsSkippedWithoutLosingTheMigratedDatabaseOrReportingDetails() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("invalid-optional-vault-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkTestDatabase.create(sourceDatabase);
        Path sourceVault = EncryptionKeyVaultPath.getVaultPath(sourceRoot);
        Files.createDirectories(sourceVault.getParent());
        String secret = "DO-NOT-REPORT-vault-secret";
        Files.writeString(sourceVault, secret);
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("invalid-optional-vault-target");
        List<String> progress = new ArrayList<>();
        ApplicationMigrationService service = new ApplicationMigrationService(SqliteDatabaseSnapshot::create,
            (staged, source, target) -> "mandatory database complete");

        ApplicationMigrationService.MigrationResult result =
            service.importPrevious(sourceRoot, targetRoot, progress::add);

        assertCurrentFormat(SdrTrunkDatabasePath.getDatabasePath(targetRoot));
        assertFalse(Files.exists(targetRoot.resolve(EncryptionKeyVaultPath.VAULT_DIRECTORY)));
        assertTrue(result.helperOutput().contains(
            "- encryption-key vault: WARNING - skipped because it could not be imported safely."));
        assertFalse(result.helperOutput().contains(secret));
        assertFalse(result.helperOutput().contains(sourceVault.toString()));
        assertFalse(result.helperOutput().contains("not a database"));
        assertTrue(progress.stream().noneMatch(status -> status.contains(secret) ||
            status.contains(sourceVault.toString()) || status.contains("not a database")));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertEquals(secret, Files.readString(sourceVault));
    }

    @Test
    void unsupportedOptionalVaultVersionIsSkippedWithoutBlockingTheDatabase() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("unsupported-vault-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkTestDatabase.create(sourceDatabase);
        Path sourceVault = createValidVault(sourceRoot);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + sourceVault);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE vault_metadata SET value='99' WHERE key='schema_version'");
        }
        Path targetRoot = mTemporaryFolder.resolve("unsupported-vault-target");
        ApplicationMigrationService service = new ApplicationMigrationService(SqliteDatabaseSnapshot::create,
            (staged, source, target) -> "mandatory database complete");

        ApplicationMigrationService.MigrationResult result =
            service.importPrevious(sourceRoot, targetRoot, ignored -> { });

        assertCurrentFormat(SdrTrunkDatabasePath.getDatabasePath(targetRoot));
        assertFalse(Files.exists(targetRoot.resolve(EncryptionKeyVaultPath.VAULT_DIRECTORY)));
        assertTrue(result.helperOutput().contains(
            "- encryption-key vault: WARNING - skipped because it could not be imported safely."));
        assertEquals("99", scalar(sourceVault,
            "SELECT value FROM vault_metadata WHERE key='schema_version'"));
    }

    @Test
    void symbolicLinkInOptionalJmbeIsSkippedWhileModulesStillCopy() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("optional-symlink-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkTestDatabase.create(sourceDatabase);
        Path privateFile = mTemporaryFolder.resolve("private-api-key-material.jar");
        String secret = "DO-NOT-REPORT-symlink-secret";
        Files.writeString(privateFile, secret);
        Path jmbeDirectory = Files.createDirectories(sourceRoot.resolve("jmbe"));
        Path link = jmbeDirectory.resolve("private-library-link.jar");
        try
        {
            Files.createSymbolicLink(link, privateFile);
        }
        catch(UnsupportedOperationException | IOException | SecurityException exception)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }
        Path sourceModule = Files.createDirectories(sourceRoot.resolve("modules")).resolve("module.jar");
        Files.writeString(sourceModule, "module-content");
        Path targetRoot = mTemporaryFolder.resolve("optional-symlink-target");
        ApplicationMigrationService service = new ApplicationMigrationService(SqliteDatabaseSnapshot::create,
            (staged, source, target) -> "mandatory database complete");

        ApplicationMigrationService.MigrationResult result =
            service.importPrevious(sourceRoot, targetRoot, null);

        assertCurrentFormat(SdrTrunkDatabasePath.getDatabasePath(targetRoot));
        assertFalse(Files.exists(targetRoot.resolve("jmbe")));
        assertEquals("module-content", Files.readString(targetRoot.resolve("modules/module.jar")));
        assertTrue(result.helperOutput().contains(
            "- JMBE library files: WARNING - skipped because it could not be imported safely."));
        assertTrue(result.helperOutput().contains("- optional decoder module files: copied."));
        assertFalse(result.helperOutput().contains(secret));
        assertFalse(result.helperOutput().contains(privateFile.toString()));
        assertFalse(result.helperOutput().contains(link.getFileName().toString()));
        assertTrue(Files.isSymbolicLink(link));
        assertEquals(secret, Files.readString(privateFile));
    }

    @Test
    void unreadableOptionalModuleIsSkippedWhileJmbeStillCopies() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("optional-unreadable-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkTestDatabase.create(sourceDatabase);
        Path sourceJmbe = Files.createDirectories(sourceRoot.resolve("jmbe")).resolve("jmbe.jar");
        Files.writeString(sourceJmbe, "jmbe-content");
        Path unreadable = Files.createDirectories(sourceRoot.resolve("modules")).resolve("private-module.jar");
        Files.writeString(unreadable, "DO-NOT-REPORT-unreadable-secret");
        PosixFileAttributeView view = Files.getFileAttributeView(unreadable, PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "POSIX file permissions are unavailable");
        PosixFileAttributes original = view.readAttributes();

        try
        {
            view.setPermissions(PosixFilePermissions.fromString("---------"));
            Assumptions.assumeFalse(Files.isReadable(unreadable),
                "This environment can still read a mode-000 file");
            Path targetRoot = mTemporaryFolder.resolve("optional-unreadable-target");
            ApplicationMigrationService service = new ApplicationMigrationService(SqliteDatabaseSnapshot::create,
                (staged, source, target) -> "mandatory database complete");

            ApplicationMigrationService.MigrationResult result =
                service.importPrevious(sourceRoot, targetRoot, null);

            assertCurrentFormat(SdrTrunkDatabasePath.getDatabasePath(targetRoot));
            assertEquals("jmbe-content", Files.readString(targetRoot.resolve("jmbe/jmbe.jar")));
            assertFalse(Files.exists(targetRoot.resolve("modules")));
            assertTrue(result.helperOutput().contains("- JMBE library files: copied."));
            assertTrue(result.helperOutput().contains(
                "- optional decoder module files: WARNING - skipped because it could not be imported safely."));
            assertFalse(result.helperOutput().contains("DO-NOT-REPORT-unreadable-secret"));
            assertFalse(result.helperOutput().contains(unreadable.toString()));
        }
        finally
        {
            view.setPermissions(original.permissions());
        }
    }

    @Test
    void mandatoryDatabaseSnapshotFailureStillAbortsPortableImport() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("mandatory-snapshot-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkTestDatabase.create(sourceDatabase);
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("mandatory-snapshot-target");
        AtomicBoolean helperRan = new AtomicBoolean();
        ApplicationMigrationService service = new ApplicationMigrationService((source, destination) ->
        {
            Files.createDirectories(destination.getParent());
            Files.writeString(destination, "incomplete mandatory snapshot");
            throw new IOException("forced mandatory database snapshot failure");
        }, (staged, source, target) ->
        {
            helperRan.set(true);
            return "must not run";
        });

        IOException failure = assertThrows(IOException.class,
            () -> service.importPrevious(sourceRoot, targetRoot, null));

        assertTrue(failure.getMessage().contains("forced mandatory database snapshot failure"));
        assertFalse(helperRan.get());
        assertFalse(Files.exists(targetRoot));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        try(var paths = Files.list(mTemporaryFolder))
        {
            assertTrue(paths.noneMatch(path -> path.getFileName().toString()
                .startsWith(".mandatory-snapshot-target.migration-")));
        }
    }

    @Test
    void directDatabaseImportDoesNotCopyOrRebaseNeighboringProfileData() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("database-only-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkTestDatabase.create(sourceDatabase);
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
        SdrTrunkTestDatabase.create(activeDatabase);
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
        ApplicationMigrationService.ApprovedMigrationPlan approval =
            ApplicationMigrationService.readMigrationApproval(sourceDatabase);
        List<String> progress = new ArrayList<>();

        ApplicationMigrationService.MigrationResult result = new ApplicationMigrationService()
            .replaceCurrentDatabase(sourceDatabase, activeRoot, approval, progress::add);

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
    void replacingFromACompletedCurrentProfileStillRequiresDestinationReview() throws Exception
    {
        Path activeRoot = mTemporaryFolder.resolve("review-target");
        Path activeDatabase = SdrTrunkDatabasePath.getDatabasePath(activeRoot);
        Path sourceDatabase = mTemporaryFolder.resolve("review-source.sqlite");
        SdrTrunkTestDatabase.create(activeDatabase);
        SdrTrunkTestDatabase.create(sourceDatabase);
        new io.github.dsheirer.gui.setup.SetupProgress(true, false).save(sourceDatabase);
        insertAlias(activeDatabase, "Old destination");
        insertAlias(sourceDatabase, "Selected source");
        byte[] sourceHash = sha256(sourceDatabase);
        var result = inProcessMigrationService().replaceCurrentDatabase(sourceDatabase, activeRoot, null);
        assertCurrentFormat(activeDatabase);
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        var review = io.github.dsheirer.gui.setup.SetupProgress.read(activeDatabase);
        assertFalse(review.isComplete());
        assertTrue(review.isImported());
        assertEquals(io.github.dsheirer.gui.setup.SetupProgress.State.PENDING,
            review.get(io.github.dsheirer.gui.setup.SetupStep.REVIEW));
        assertTrue(io.github.dsheirer.gui.setup.SetupProgress.read(sourceDatabase).isComplete());
        assertEquals("Old destination", scalar(result.safetyBackup(), "SELECT name FROM alias WHERE id=1"));
        assertEquals("Selected source", scalar(activeDatabase, "SELECT name FROM alias WHERE id=1"));
    }

    @Test
    void selectedDatabaseReplacementBindsTheApprovedPlanBeforeBackup() throws Exception
    {
        Path activeRoot = mTemporaryFolder.resolve("active-plan-binding-data");
        Path activeDatabase = SdrTrunkDatabasePath.getDatabasePath(activeRoot);
        SdrTrunkTestDatabase.create(activeDatabase);
        insertAlias(activeDatabase, "Keep Active");
        byte[] activeHash = sha256(activeDatabase);

        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(
            mTemporaryFolder.resolve("selected-plan-binding-source"));
        SdrTrunkTestDatabase.create(sourceDatabase);
        ApplicationMigrationService.ApprovedMigrationPlan approved =
            ApplicationMigrationService.readMigrationApproval(sourceDatabase);
        Files.delete(sourceDatabase);
        Format14TestDatabase.create(sourceDatabase);

        IOException exception = assertThrows(IOException.class,
            () -> inProcessMigrationService().replaceCurrentDatabase(sourceDatabase, activeRoot, approved,
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
        SdrTrunkTestDatabase.create(activeDatabase);

        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(
            mTemporaryFolder.resolve("selected-admin-source"));
        Format1TestDatabase.create(sourceDatabase);
        char[] password = "retained alpha administrator".toCharArray();
        LegacyWebAccessTestData.storePrimaryAdmin(sourceDatabase, password);

        inProcessMigrationService().replaceCurrentDatabase(sourceDatabase, activeRoot, null);

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
        SdrTrunkTestDatabase.create(activeDatabase);
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
        SdrTrunkTestDatabase.create(activeDatabase);

        IOException exception = assertThrows(IOException.class,
            () -> inProcessMigrationService().replaceCurrentDatabase(activeDatabase, activeRoot, null));

        assertTrue(exception.getMessage().contains("paths are the same"));
        assertFalse(Files.exists(activeDatabase.getParent().resolve("backups")));
    }

    @Test
    void activeDatabaseHardLinkCannotBeSelectedAsItsReplacement() throws Exception
    {
        Path activeRoot = mTemporaryFolder.resolve("hard-link-replacement-data");
        Path activeDatabase = SdrTrunkDatabasePath.getDatabasePath(activeRoot);
        SdrTrunkTestDatabase.create(activeDatabase);
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
            () -> inProcessMigrationService().replaceCurrentDatabase(hardLink, activeRoot, null));

        assertTrue(exception.getMessage().contains("same physical file"));
        assertFalse(Files.exists(activeDatabase.getParent().resolve("backups")));
    }

    @Test
    void refusesOverlappingImportRootsBeforeCreatingMigrationFiles() throws Exception
    {
        Path outerSource = mTemporaryFolder.resolve("outer-source");
        Path outerSourceDatabase = SdrTrunkDatabasePath.getDatabasePath(outerSource);
        SdrTrunkTestDatabase.create(outerSourceDatabase);
        byte[] outerSourceHash = sha256(outerSourceDatabase);
        Path nestedTarget = outerSource.resolve("jmbe/imported-data");

        IOException nestedTargetException = assertThrows(IOException.class,
            () -> inProcessMigrationService().importPrevious(outerSource, nestedTarget, null));

        assertTrue(nestedTargetException.getMessage().contains("overlap"));
        assertArrayEquals(outerSourceHash, sha256(outerSourceDatabase));
        assertFalse(Files.exists(nestedTarget));

        Path outerTarget = mTemporaryFolder.resolve("outer-target");
        Path nestedSource = outerTarget.resolve("previous-data");
        Path nestedSourceDatabase = SdrTrunkDatabasePath.getDatabasePath(nestedSource);
        SdrTrunkTestDatabase.create(nestedSourceDatabase);
        byte[] nestedSourceHash = sha256(nestedSourceDatabase);

        IOException nestedSourceException = assertThrows(IOException.class,
            () -> inProcessMigrationService().importPrevious(nestedSource, outerTarget, null));

        assertTrue(nestedSourceException.getMessage().contains("overlap"));
        assertArrayEquals(nestedSourceHash, sha256(nestedSourceDatabase));
        assertFalse(Files.exists(outerTarget.resolve("database/backups")));
    }

    @Test
    void refusesAStagedSnapshotWhoseMigrationPlanDiffersFromTheApprovedSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("plan-change-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkTestDatabase.create(sourceDatabase);
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("plan-change-target");
        AtomicBoolean helperRan = new AtomicBoolean();
        ApplicationMigrationService service = new ApplicationMigrationService(
            (source, destination) ->
            {
                Files.createDirectories(destination.getParent());
                try
                {
                    Format14TestDatabase.create(destination);
                }
                catch(Exception e)
                {
                    throw new IOException("Could not create the staged format-14 fixture", e);
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
        SdrTrunkTestDatabase.create(sourceDatabase);
        ApplicationMigrationService.ApprovedMigrationPlan approvedPlan =
            ApplicationMigrationService.readMigrationApproval(sourceDatabase);

        Files.delete(sourceDatabase);
        Format14TestDatabase.create(sourceDatabase);

        Path targetRoot = mTemporaryFolder.resolve("approved-plan-target");
        PreviousBuildLocator.Selection selection = new PreviousBuildLocator.Selection(sourceRoot,
            PreviousBuildLocator.InputScope.PORTABLE_PROFILE);

        IOException exception = assertThrows(IOException.class,
            () -> inProcessMigrationService().importPrevious(selection, targetRoot, approvedPlan, null));

        assertTrue(exception.getMessage().contains("source database selected after confirmation"));
        assertFalse(Files.exists(targetRoot));
    }

    @Test
    void refusesChangedSourceContentEvenWhenTheReviewedPlanIsUnchanged() throws Exception
    {
        Path sourceDatabase = mTemporaryFolder.resolve("same-plan-changed-source.sqlite");
        SdrTrunkTestDatabase.create(sourceDatabase);
        insertAlias(sourceDatabase, "Approved Alias Name");
        ApplicationMigrationService.ApprovedMigrationPlan approved =
            ApplicationMigrationService.readMigrationApproval(sourceDatabase);

        try(Connection connection = open(sourceDatabase); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE alias SET name='Changed After Approval' " +
                "WHERE name='Approved Alias Name'");
        }

        assertEquals(approved.plan(), ApplicationMigrationService.readMigrationPlan(sourceDatabase),
            "changing row content must not need to alter the migration plan");
        Path targetRoot = mTemporaryFolder.resolve("same-plan-changed-target");
        PreviousBuildLocator.Selection selection = new PreviousBuildLocator.Selection(sourceDatabase,
            PreviousBuildLocator.InputScope.DATABASE_FILE);

        IOException exception = assertThrows(IOException.class,
            () -> inProcessMigrationService().importPrevious(selection, targetRoot, approved, null));

        assertTrue(exception.getMessage().contains("database content may have changed"));
        assertFalse(Files.exists(targetRoot));
    }

    @Test
    void approvalPreflightIncludesCommittedWalContent() throws Exception
    {
        Path sourceDatabase = mTemporaryFolder.resolve("approval-wal-source.sqlite");
        SdrTrunkTestDatabase.create(sourceDatabase);
        insertAlias(sourceDatabase, "Alias With WAL Route");

        try(Connection writer = open(sourceDatabase); Statement statement = writer.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel(alias_id, broadcast_configuration_id)
                VALUES ((SELECT id FROM alias WHERE name='Alias With WAL Route'),
                        '00000000-0000-0000-0000-000000000164')
                """);
            Path wal = Path.of(sourceDatabase + "-wal");
            assertTrue(Files.isRegularFile(wal));
            assertTrue(Files.size(wal) > 0);

            ApplicationMigrationService.ApprovedMigrationPlan approval =
                ApplicationMigrationService.readMigrationApproval(sourceDatabase);

            assertTrue(approval.plan().steps().stream()
                .anyMatch(step -> CurrentDatabaseBestEffortRepair.STEP_ID.equals(step.id())),
                "approval must inspect the recovered WAL state, not the stale main database file");
        }
    }

    @Test
    void repairsCurrentAliasForeignKeyViolationsWithoutChangingTheSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("foreign-key-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkTestDatabase.create(sourceDatabase);

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

        ApplicationMigrationService.MigrationResult result = inProcessMigrationService()
            .importPrevious(sourceRoot, targetRoot, null);

        assertTrue(result.importedPreviousProfile());
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertCurrentFormat(targetDatabase);
        assertEquals("0", scalar(targetDatabase,
            "SELECT COUNT(*) FROM alias WHERE name='Orphan Alias'"));
        assertEquals("0", scalar(targetDatabase, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertEquals("1", scalar(sourceDatabase,
            "SELECT COUNT(*) FROM alias WHERE name='Orphan Alias'"));
    }

    @Test
    void markerlessCurrentLayoutPlansCurrentAliasForeignKeyRepair() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(
            mTemporaryFolder.resolve("markerless-current-foreign-key-source"));
        SdrTrunkTestDatabase.create(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='database_format_version'");
            statement.executeUpdate("""
                INSERT INTO alias(alias_list_id, name, matcher_type, protocol, value)
                VALUES (999999, 'Markerless Current Orphan', 'TALKGROUP', 'APCO25', 1)
                """);
        }

        DatabaseMigrationChain.PreflightReport plan = ApplicationMigrationService.readMigrationPlan(database);

        assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, plan.source().version());
        assertFalse(plan.source().markerPresent());
        assertTrue(plan.steps().stream().anyMatch(step ->
            CurrentDatabaseBestEffortRepair.STEP_ID.equals(step.id())));
        assertTrue(plan.steps().stream().anyMatch(step -> "adopt-global-format-marker".equals(step.id())));
        assertEquals("1", scalar(database,
            "SELECT COUNT(*) FROM alias WHERE name='Markerless Current Orphan'"));
    }

    @Test
    void repairsLegacySourceForeignKeyViolationsInsteadOfRejectingTheMigration() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("legacy-foreign-key-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        Format2TestDatabase.create(sourceDatabase);

        try(Connection connection = open(sourceDatabase); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.executeUpdate("""
                INSERT INTO alias(alias_list_id, name, matcher_type, protocol, value)
                VALUES (999999, 'Legacy Orphan Alias', 'TALKGROUP', 'APCO25', 1)
                """);
        }

        byte[] sourceHash = sha256(sourceDatabase);
        DatabaseMigrationChain.PreflightReport plan = ApplicationMigrationService.readMigrationPlan(sourceDatabase);
        assertEquals(2, plan.source().version());
        Path targetRoot = Files.createDirectory(mTemporaryFolder.resolve("legacy-foreign-key-target"));

        ApplicationMigrationService.MigrationResult result = inProcessMigrationService()
            .importPrevious(sourceRoot, targetRoot, null);

        assertTrue(result.importedPreviousProfile());
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertCurrentFormat(targetDatabase);
        assertEquals("0", scalar(targetDatabase, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
        assertEquals("0", scalar(targetDatabase,
            "SELECT COUNT(*) FROM alias WHERE name='Legacy Orphan Alias'"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
    }

    @Test
    void repairsLegacyRowCheckViolationsAfterPhysicalIntegrityPreflight() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("legacy-check-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        Format6TestDatabase.create(sourceDatabase);

        try(Connection connection = open(sourceDatabase); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE web_user SET preferences_revision=0 WHERE id=1");
            statement.execute("PRAGMA ignore_check_constraints=OFF");
        }

        byte[] sourceHash = sha256(sourceDatabase);
        DatabaseMigrationChain.PreflightReport plan = ApplicationMigrationService.readMigrationPlan(sourceDatabase);
        assertEquals(6, plan.source().version());
        Path targetRoot = Files.createDirectory(mTemporaryFolder.resolve("legacy-check-target"));
        ApplicationMigrationService.ApprovedMigrationPlan approval =
            ApplicationMigrationService.readMigrationApproval(sourceDatabase);
        assertEquals(plan, approval.plan());

        PreviousBuildLocator.Selection selection = new PreviousBuildLocator.Selection(sourceRoot,
            PreviousBuildLocator.InputScope.PORTABLE_PROFILE);
        inProcessMigrationService().importPrevious(selection, targetRoot, approval, null);

        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertCurrentFormat(targetDatabase);
        assertEquals("1", scalar(targetDatabase,
            "SELECT CASE WHEN preferences_revision > 0 THEN 1 ELSE 0 END FROM web_user WHERE id=1"));
        assertEquals("ok", scalar(targetDatabase, "PRAGMA quick_check"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
    }

    @Test
    void importsFormat14WithAnExhaustedPreferenceRevisionWithoutChangingTheSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("format14-exhausted-preference-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        Format14TestDatabase.create(sourceDatabase);
        try(Connection connection = open(sourceDatabase); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE web_user SET preferences_revision=" + (Long.MAX_VALUE - 1) +
                " WHERE primary_admin=1");
        }

        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("format14-exhausted-preference-target");

        ApplicationMigrationService.MigrationResult result = inProcessMigrationService()
            .importPrevious(sourceRoot, targetRoot, null);

        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertTrue(result.importedPreviousProfile());
        assertTrue(result.completedWithRepairsOrSkippedItems());
        assertCurrentFormat(targetDatabase);
        assertEquals("1", scalar(targetDatabase,
            "SELECT preferences_revision FROM web_user WHERE primary_admin=1"));
        assertEquals("1", scalar(targetDatabase, "SELECT COUNT(*) FROM web_user WHERE primary_admin=1"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertEquals(Long.toString(Long.MAX_VALUE - 1), scalar(sourceDatabase,
            "SELECT preferences_revision FROM web_user WHERE primary_admin=1"));
    }

    @Test
    void readsTheExactCurrentFormatPlan() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("current-state");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);

        DatabaseMigrationChain.PreflightReport plan = ApplicationMigrationService.readMigrationPlan(database);

        assertFormat(plan.source(), DatabaseFormatCatalog.CURRENT_VERSION,
            DatabaseFormatCatalog.current().id(), true);
        assertEquals(DatabaseFormatCatalog.current(), plan.target());
        assertTrue(plan.steps().isEmpty());
    }

    @Test
    void approvalUsesAndCleansTheCallerSelectedScratchVolume() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder.resolve("approval-source"));
        SdrTrunkTestDatabase.create(database);
        Path scratch = mTemporaryFolder.resolve("destination-adjacent-scratch");

        ApplicationMigrationService.ApprovedMigrationPlan approval =
            ApplicationMigrationService.readMigrationApproval(database, scratch);

        assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, approval.plan().source().version());
        assertTrue(Files.isDirectory(scratch));
        try(var children = Files.list(scratch))
        {
            assertTrue(children.findAny().isEmpty(), "approval scratch artifacts must always be removed");
        }
    }

    @Test
    void preflightsAndImportsRepairableCurrentPreferencesWithoutChangingSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("repairable-current-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkTestDatabase.create(sourceDatabase);
        try(Connection connection = open(sourceDatabase); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('portable_java_preferences_v1', '{invalid', 1)
                """);
            statement.execute("PRAGMA ignore_check_constraints=OFF");
        }
        byte[] sourceHash = sha256(sourceDatabase);

        DatabaseMigrationChain.PreflightReport plan =
            ApplicationMigrationService.readMigrationPlan(sourceDatabase);

        assertFalse(plan.source().requiresMigration());
        assertTrue(plan.requiresMigration());
        assertEquals(1, plan.steps().size());
        assertEquals("repair-portable-preferences", plan.steps().getFirst().id());
        assertTrue(ApplicationMigrationService.describePlan(plan).contains("unusable portable preference components"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertFalse(Files.exists(Path.of(sourceDatabase + "-wal")));
        assertFalse(Files.exists(Path.of(sourceDatabase + "-shm")));

        Path targetRoot = Files.createDirectory(mTemporaryFolder.resolve("repairable-current-target"));
        ApplicationMigrationService.ApprovedMigrationPlan approval =
            ApplicationMigrationService.readMigrationApproval(sourceDatabase);
        assertEquals(plan, approval.plan());
        ApplicationMigrationService.MigrationResult result = inProcessMigrationService().importPrevious(
            new PreviousBuildLocator.Selection(sourceDatabase, PreviousBuildLocator.InputScope.DATABASE_FILE),
            targetRoot, approval, null);

        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertTrue(result.helperOutput().contains(
            "RESET unusable portable preference components: 1 preference component(s)"));
        assertEquals("{}", scalar(targetDatabase, """
            SELECT settings_json FROM application_settings WHERE key='portable_java_preferences_v1'
            """));
        assertCurrentFormat(targetDatabase);
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
    }

    @Test
    void importsExactCurrentProfileWithoutLosingConfigurationOrHistory() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("source-data");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkTestDatabase.create(sourceDatabase);
        insertAlias(sourceDatabase, "Keep Me");
        insertCurrentProfileSentinels(sourceDatabase);
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("target-data");
        Files.createDirectory(targetRoot);

        ApplicationMigrationService.MigrationResult result = inProcessMigrationService()
            .importPrevious(sourceRoot, targetRoot, null);

        assertTrue(result.importedPreviousProfile());
        assertFormat(result.sourceFormat(), DatabaseFormatCatalog.CURRENT_VERSION,
            DatabaseFormatCatalog.current().id(), true);
        assertEquals(result.sourceFormat(), result.sourcePlan().source());
        assertTrue(result.helperOutput().contains("Application database migration and validation complete"));
        assertTrue(result.helperOutput().contains(
            "- JMBE library files: not present in the selected profile."));
        assertTrue(result.helperOutput().contains(
            "- optional decoder module files: not present in the selected profile."));
        assertTrue(result.helperOutput().contains(
            "- encryption-key vault: not present in the selected profile."));
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
        SdrTrunkTestDatabase.create(database);
        insertAlias(database, "Retained");

        ApplicationMigrationService.MigrationResult result =
            inProcessMigrationService().migrateCurrent(dataRoot, null);

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
        SdrTrunkTestDatabase.create(database);
        PosixFileAttributeView view = Files.getFileAttributeView(database, PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "POSIX file attributes are unavailable");
        view.setPermissions(PosixFilePermissions.fromString("rw-------"));
        PosixFileAttributes before = view.readAttributes();

        ApplicationMigrationService.MigrationResult result =
            inProcessMigrationService().migrateCurrent(dataRoot, null);

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
        SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(sourceRoot));
        Path targetRoot = Files.createDirectory(mTemporaryFolder.resolve("posix-import-target"));
        PosixFileAttributeView view = Files.getFileAttributeView(targetRoot, PosixFileAttributeView.class);
        Assumptions.assumeTrue(view != null, "POSIX file attributes are unavailable");
        view.setPermissions(PosixFilePermissions.fromString("rwx------"));
        PosixFileAttributes before = view.readAttributes();

        inProcessMigrationService().importPrevious(sourceRoot, targetRoot, null);

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
        SdrTrunkTestDatabase.create(database);
        removeDmrActivitySchema(database);
        byte[] before = sha256(database);

        SQLException exception = assertThrows(SQLException.class,
            () -> inProcessMigrationService().migrateCurrent(dataRoot, null));

        assertTrue(exception.getMessage().contains("Unrecognized SQLite database schema fingerprint"));
        assertArrayEquals(before, sha256(database));
        assertFalse(Files.exists(database.getParent().resolve("backups")));
    }

    @Test
    void rejectsMainRecordingCatalogFootprintBeforeCreatingMigrationOutput() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("current-recorded-call-source");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE recorded_call_private_payload(id INTEGER PRIMARY KEY)");
        }
        byte[] before = sha256(database);
        Path targetRoot = mTemporaryFolder.resolve("current-recorded-call-target");

        SQLException importFailure = assertThrows(SQLException.class,
            () -> inProcessMigrationService().importPrevious(dataRoot, targetRoot, null));
        assertTrue(importFailure.getMessage().contains("webfirst managed-recording"));
        assertFalse(Files.exists(targetRoot));
        assertArrayEquals(before, sha256(database));

        SQLException currentFailure = assertThrows(SQLException.class,
            () -> inProcessMigrationService().migrateCurrent(dataRoot, null));
        assertTrue(currentFailure.getMessage().contains("webfirst managed-recording"));
        assertFalse(Files.exists(database.getParent().resolve("backups")));
        assertArrayEquals(before, sha256(database));
    }

    @Test
    void helperFailureLeavesTheCurrentDatabaseUntouched() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("failure-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
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
            inProcessMigrationService().migrateCurrent(dataRoot, null);
        assertNotNull(retry.safetyBackup());
        assertCurrentFormat(database);
        assertEquals(1, count(database, "alias"));
    }

    @Test
    void failedPostPromotionValidationAndRestoreReportsUncertainLiveDatabase() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("uncertain-live-database");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        insertAlias(database, "Retained Safety Backup Alias");
        Path sidecar = Path.of(database + "-wal");
        ApplicationMigrationService service = new ApplicationMigrationService(
            SqliteDatabaseSnapshot::create,
            (staged, source, target) -> "migration helper complete",
            promoted ->
            {
                Files.writeString(Path.of(promoted + "-wal"), "forced active sidecar");
                throw new SQLException("forced post-promotion validation failure");
            });

        ApplicationMigrationService.LiveDatabaseRecoveryException failure;
        try
        {
            failure = assertThrows(ApplicationMigrationService.LiveDatabaseRecoveryException.class,
                () -> service.migrateCurrent(dataRoot, null));
        }
        finally
        {
            Files.deleteIfExists(sidecar);
        }

        assertEquals(database, failure.database());
        assertTrue(Files.isRegularFile(failure.safetyBackup()));
        assertTrue(failure.getMessage().contains("could not be restored automatically"));
        assertTrue(failure.getMessage().contains(failure.safetyBackup().toString()));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(failure.getSuppressed()[0].getMessage().contains("cannot be restored automatically"));
    }

    @Test
    void failedPostPromotionValidationRestoresAndValidatesExactOlderFormatBackup() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("older-format-post-promotion-failure");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Format14TestDatabase.create(database);
        insertAlias(database, "Restore Older Format Alias");
        ApplicationMigrationService.ApprovedMigrationPlan approval =
            ApplicationMigrationService.readMigrationApproval(database);
        ApplicationMigrationService service = new ApplicationMigrationService(
            SqliteDatabaseSnapshot::create,
            ApplicationMigrationServiceTestSupport::runMigratorInProcess,
            promoted ->
            {
                throw new SQLException("forced post-promotion validation failure");
            });

        SQLException failure = assertThrows(SQLException.class,
            () -> service.migrateCurrent(dataRoot, approval, null));

        assertTrue(failure.getMessage().contains("forced post-promotion validation failure"));
        assertEquals(approval.plan(), ApplicationMigrationService.readMigrationPlan(database));
        assertEquals("Restore Older Format Alias", scalar(database,
            "SELECT name FROM alias WHERE name='Restore Older Format Alias'"));
        assertEquals("ok", scalar(database, "PRAGMA integrity_check"));
        try(var paths = Files.list(database.getParent().resolve("backups")))
        {
            List<Path> backups = paths.toList();
            assertEquals(1, backups.size());
            assertEquals(-1L, Files.mismatch(backups.getFirst(), database));
        }
    }

    @Test
    void snapshotFailureRemovesEveryIncompleteBackupArtifact() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("snapshot-failure-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
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
        SdrTrunkTestDatabase.create(sourceDatabase);
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
        SdrTrunkTestDatabase.create(sourceDatabase);
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

        assertTrue(exception.getMessage().contains("Channel kind scalar does not match config_json"),
            exception::getMessage);
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertFalse(Files.exists(targetRoot));
    }

    @Test
    void targetPopulationAtPromotionTimePreventsImportWithoutLosingEitherSide() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("promotion-race-source");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkTestDatabase.create(sourceDatabase);
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = Files.createDirectory(mTemporaryFolder.resolve("promotion-race-target"));
        Path competingFile = targetRoot.resolve("created-by-another-first-run.txt");

        IOException exception = assertThrows(IOException.class,
            () -> inProcessMigrationService().importPrevious(sourceRoot, targetRoot, progress ->
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
        SdrTrunkTestDatabase.create(sourceDatabase);
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = Files.createDirectory(mTemporaryFolder.resolve("atomic-promotion-target"));
        ApplicationMigrationService service = new ApplicationMigrationService(SqliteDatabaseSnapshot::create,
            ApplicationMigrationServiceTestSupport::runMigratorInProcess,
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
        SdrTrunkTestDatabase.create(sourceDatabase);
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
            () -> inProcessMigrationService().importPrevious(sourceRoot, apparentlySeparateTarget, null));

        assertTrue(exception.getMessage().contains("overlap"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertFalse(Files.exists(apparentlySeparateTarget));
    }

    @Test
    void refusesExistingTargetSymlinkWhoseInstallParentIsInsideSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("source-with-target-link");
        Path sourceDatabase = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        SdrTrunkTestDatabase.create(sourceDatabase);
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
            () -> inProcessMigrationService().importPrevious(sourceRoot, targetLink, null));

        assertTrue(exception.getMessage().contains("overlap"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertTrue(Files.isSymbolicLink(targetLink));
    }

    private static ApplicationMigrationService inProcessMigrationService()
    {
        return ApplicationMigrationServiceTestSupport.createInProcess();
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

    private static Path createValidVault(Path dataRoot) throws Exception
    {
        Path vault = EncryptionKeyVaultPath.getVaultPath(dataRoot);
        Files.createDirectories(vault.getParent());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + vault))
        {
            EncryptionKeyVaultSchema.create(connection);
        }
        return vault;
    }

    private static void insertCurrentProfileSentinels(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            String configJson = channelJson("Preserved Channel", "Preserved System", "Preserved Site", "Test",
                DecoderType.P25_PHASE1, 451000000);
            ObjectNode currentChannel = (ObjectNode)OBJECT_MAPPER.readTree(configJson);
            String configurationId = currentChannel.path("configurationId").asText();
            currentChannel.remove(List.of("configurationId", "system", "site", "name", "aliasListId",
                "aliasListName", "radioResolveId", "radresGuid", "radres_guid", "autoStart", "enabled",
                "autoStartOrder", "order", "channelType"));
            try(var insert = connection.prepareStatement("""
                INSERT INTO configuration_channel(
                    configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_id,
                    radioresolve_id, auto_start, decoder_type, address_domain_code, primary_frequency_hz, config_json
                ) VALUES (
                    ?, 'TRUNKED', 1, 'Preserved System', 'Preserved Site', 'Preserved Channel',
                    (SELECT id FROM alias_list WHERE name='Test' COLLATE NOCASE),
                    '00000000-0000-4000-8000-000000007001', 0, 'P25_PHASE1', 0, 451000000, ?
                )
                """))
            {
                insert.setString(1, configurationId);
                insert.setString(2, OBJECT_MAPPER.writeValueAsString(currentChannel));
                insert.executeUpdate();
            }
            statement.executeUpdate("""
                INSERT INTO application_settings(key, settings_json, updated_at_ms)
                VALUES ('current_profile_sentinel', '{"value":"preserved"}', 1000)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system(
                    id, system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                    first_seen_ms, last_seen_ms
                ) VALUES (7000, 'p25:bee00:123', 1, 0, 781824, 291, 1000, 2000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel(
                    id, configuration_id, first_seen_ms, last_seen_ms, radio_system_id,
                    radio_system_assigned_at_ms
                ) VALUES (7001, '%s', 1000, 2000, 7000, 1000)
                """.formatted(configurationId));
            statement.executeUpdate("""
                INSERT INTO trunked_control_channel_quality(
                    channel_id, frequency_hz, bucket_start_ms, observed_at_ms, signal_dbfs,
                    decode_health_pct, valid_frames, invalid_frames, corrected_bits,
                    sync_loss_bits, dropped_bits, last_valid_decode_ms
                ) VALUES (
                    7001, 851012500, 1000, 2000, -72.5,
                    92.5, 100, 3, 4, 5, 6, 1900
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
            SELECT decode_health_pct FROM trunked_control_channel_quality WHERE channel_id=7001
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
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.TALKGROUP_RECEIVER_INDEX);
            statement.executeUpdate("DROP INDEX " + DmrActivitySchema.RADIO_RECEIVER_INDEX);
            statement.executeUpdate("DROP TABLE " + DmrActivitySchema.TALKGROUP_TABLE);
            statement.executeUpdate("DROP TABLE " + DmrActivitySchema.RADIO_TABLE);
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='" +
                "dmr_activity_schema_version'");
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
