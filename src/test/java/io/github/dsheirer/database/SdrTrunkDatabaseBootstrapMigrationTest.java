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
package io.github.dsheirer.database;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.upgrade.ApplicationMigrationService;
import io.github.dsheirer.database.upgrade.ApplicationMigrationServiceTestSupport;
import io.github.dsheirer.database.upgrade.DatabaseFormatCatalog;
import io.github.dsheirer.database.upgrade.Format2TestDatabase;
import io.github.dsheirer.database.upgrade.Format3TestDatabase;
import io.github.dsheirer.database.upgrade.Format30TestDatabase;
import io.github.dsheirer.database.upgrade.ManagedRecordingCatalogMigrator;
import io.github.dsheirer.record.managed.ManagedRecordingSchema;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultPath;
import io.github.dsheirer.web.auth.WebAccessService;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SdrTrunkDatabaseBootstrapMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void headlessStartupCannotBypassAnInterruptedGraphicalUpgrade() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("pending-profile/data");
        Path database = Format30TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
        byte[] before = Files.readAllBytes(database);
        Path receipt = dataRoot.resolveSibling(".data.vce-upgrade").resolve("intent.properties");
        Files.createDirectories(receipt.getParent());
        Files.writeString(receipt, "pending installation upgrade");
        for(String[] arguments: List.of(new String[0], new String[]{"--upgrade-current"}, new String[]{"--fresh"}))
        {
            IOException failure = assertThrows(IOException.class,
                () -> SdrTrunkDatabaseBootstrap.run(arguments, dataRoot, true));
            assertTrue(failure.getMessage().contains("Setup Wizard"));
            assertArrayEquals(before, Files.readAllBytes(database));
            assertTrue(Files.isRegularFile(receipt));
        }
    }

    @Test
    void explicitCurrentUpgradeRepairsConfigurationEvenWhenCatalogAdmissionIsCurrent() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("current-configuration-repair");
        Path database = io.github.dsheirer.database.upgrade.CurrentFormatTestDatabase.create(
            SdrTrunkDatabasePath.getDatabasePath(dataRoot));
        String aliasesBefore = scalar(database, "SELECT COUNT(*) FROM alias");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE configuration_channel SET config_json='{invalid' WHERE rowid=" +
                "(SELECT MIN(rowid) FROM configuration_channel)");
        }
        assertFalse(ApplicationMigrationService.readStartupPlan(database).requiresMigration());
        SdrTrunkDatabaseBootstrap.run(new String[]{"--upgrade-current"}, dataRoot, true);
        assertEquals("0", scalar(database,
            "SELECT COUNT(*) FROM configuration_channel WHERE config_json='{invalid'"));
        assertEquals(aliasesBefore, scalar(database, "SELECT COUNT(*) FROM alias"));
        List<Path> backups = regularFiles(database.getParent().resolve("backups"));
        assertEquals(2, backups.size());
        ConfigurationHistoryDatabase.validatePair(backups.stream()
            .filter(path -> !path.getFileName().toString().endsWith(".activity.sqlite")).findFirst().orElseThrow());
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
    }

    @Test
    void headlessFormat2WithoutUpgradeFlagRefusesWithoutMutation() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("no-upgrade-flag");
        Path database = createFormat2Database(dataRoot, "Retained Without Flag");
        byte[] before = sha256(database);

        IOException exception = assertThrows(IOException.class,
            () -> SdrTrunkDatabaseBootstrap.run(new String[0], dataRoot, true));

        assertTrue(exception.getMessage().contains("--upgrade-current"));
        assertArrayEquals(before, sha256(database));
        assertFormat(database, 2, "scan-lists-p25-v26", false);
        assertFalse(Files.exists(database.getParent().resolve("backups")));
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void headlessUpgradeCurrentMigratesFormat2AndRetainsItsSafetyBackup() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("upgrade-current");
        Path database = createFormat2Database(dataRoot, "Migrated In Place");
        Path passwordFile = passwordFile("upgrade-current-password.txt");

        SdrTrunkDatabaseBootstrap.BootstrapResult result =
            SdrTrunkDatabaseBootstrap.run(new String[]{"--upgrade-current", "--admin-password-file",
                passwordFile.toString()}, dataRoot, true);

        assertTrue(result.startApplication());
        assertFalse(result.initializeNewPreferences());
        assertCurrentDatabase(database);
        assertEquals("Migrated In Place", scalar(database, "SELECT name FROM alias WHERE id=1"));
        List<Path> backups = regularFiles(database.getParent().resolve("backups"));
        assertEquals(1, backups.size());
        assertFormat(backups.getFirst(), 2, "scan-lists-p25-v26", false);
        assertEquals("Migrated In Place", scalar(backups.getFirst(), "SELECT name FROM alias WHERE id=1"));
        assertTrue(Files.isRegularFile(EncryptionKeyVaultPath.getVaultPath(dataRoot)));
        assertNoSqliteSidecars(backups.getFirst());
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void headlessUpgradeCurrentMigratesExactFormat3ThroughThePinnedStep() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("upgrade-format-3");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Format3TestDatabase.create(database);
        Path passwordFile = passwordFile("upgrade-format-3-password.txt");

        SdrTrunkDatabaseBootstrap.BootstrapResult result =
            SdrTrunkDatabaseBootstrap.run(new String[]{"--upgrade-current", "--admin-password-file",
                passwordFile.toString()}, dataRoot, true,
                ApplicationMigrationServiceTestSupport.createInProcess());

        assertTrue(result.startApplication());
        assertFalse(result.initializeNewPreferences());
        assertCurrentDatabase(database);
        assertEquals("{\"preserved\":true}", scalar(database, """
            SELECT settings_json FROM application_settings WHERE key='format-3-preserve-sentinel'
            """));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM conventional_call_identity_bucket"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM trunked_signaling_activity_bucket"));
        assertEquals("0", scalar(database, "SELECT COUNT(*) FROM radio_system"));
        List<Path> backups = regularFiles(database.getParent().resolve("backups"));
        assertEquals(1, backups.size());
        assertFormat(backups.getFirst(), 3, "p25-site-projection-v27", true);
        assertTrue(Files.isRegularFile(EncryptionKeyVaultPath.getVaultPath(dataRoot)));
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void upgradeDataImportsAndMigratesFormat2WithoutChangingItsSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("import-source");
        Path sourceDatabase = createFormat2Database(sourceRoot, "Imported Format 2");
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("import-target");
        Path passwordFile = passwordFile("import-target-password.txt");

        SdrTrunkDatabaseBootstrap.BootstrapResult result = SdrTrunkDatabaseBootstrap.run(
            new String[]{"--upgrade-data", sourceRoot.toString(), "--admin-password-file", passwordFile.toString()},
            targetRoot, true, ApplicationMigrationServiceTestSupport.createInProcess());

        assertTrue(result.startApplication());
        assertFalse(result.initializeNewPreferences());
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertFormat(sourceDatabase, 2, "scan-lists-p25-v26", false);
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertCurrentDatabase(targetDatabase);
        assertEquals("Imported Format 2", scalar(targetDatabase, "SELECT name FROM alias WHERE id=1"));
        assertFalse(Files.exists(targetDatabase.getParent().resolve("backups")));
        assertTrue(Files.isRegularFile(EncryptionKeyVaultPath.getVaultPath(targetRoot)));
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void upgradeDataRejectsStandaloneDatabaseWithoutChangingSourceOrCreatingDatabase() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("database-file-import-source");
        Path sourceDatabase = createFormat2Database(sourceRoot, "Imported Database File");
        byte[] sourceHash = sha256(sourceDatabase);
        Files.createDirectories(sourceRoot.resolve("jmbe"));
        Files.writeString(sourceRoot.resolve("jmbe/jmbe-test.jar"), "must not be copied");
        Files.createDirectories(sourceRoot.resolve("modules"));
        Files.writeString(sourceRoot.resolve("modules/module-test.jar"), "must not be copied");
        Files.createDirectories(sourceRoot.resolve("vault"));
        Files.writeString(EncryptionKeyVaultPath.getVaultPath(sourceRoot), "invalid unselected vault");
        Path targetRoot = mTemporaryFolder.resolve("database-file-import-target");
        Path passwordFile = passwordFile("database-file-import-password.txt");

        IOException refusal = assertThrows(IOException.class, () -> SdrTrunkDatabaseBootstrap.run(
            new String[]{"--upgrade-data", sourceDatabase.toString(), "--admin-password-file",
                passwordFile.toString()}, targetRoot, true,
                ApplicationMigrationServiceTestSupport.createInProcess()));

        assertTrue(refusal.getMessage().contains("Choose a previous VCE installation folder"));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertFormat(sourceDatabase, 2, "scan-lists-p25-v26", false);
        assertFalse(Files.exists(SdrTrunkDatabasePath.getDatabasePath(targetRoot)));
        assertFalse(Files.exists(targetRoot.resolve("jmbe")));
        assertFalse(Files.exists(targetRoot.resolve("modules")));
        assertFalse(Files.exists(EncryptionKeyVaultPath.getVaultPath(targetRoot)));
        assertEquals("must not be copied", Files.readString(sourceRoot.resolve("jmbe/jmbe-test.jar")));
        assertEquals("must not be copied", Files.readString(sourceRoot.resolve("modules/module-test.jar")));
        assertEquals("invalid unselected vault", Files.readString(EncryptionKeyVaultPath.getVaultPath(sourceRoot)));
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void exactCurrentDatabaseStartsWithoutMigration() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("current");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        removeInitialSetupMarker(database);

        SdrTrunkDatabaseBootstrap.BootstrapResult result =
            SdrTrunkDatabaseBootstrap.run(new String[0], dataRoot, true);

        assertTrue(result.startApplication());
        assertFalse(result.initializeNewPreferences());
        assertCurrentDatabase(database);
        assertFalse(Files.exists(database.getParent().resolve("backups")));
        assertTrue(Files.isRegularFile(EncryptionKeyVaultPath.getVaultPath(dataRoot)));
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void explicitUpgradeResumesActivationWhenConfigurationFilenameIsMissing() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("interrupted-pair-activation");
        Path database = SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
        Path history = ActivityDatabasePath.forConfiguration(database);
        String pairId = scalar(database, "SELECT value FROM database_metadata " +
            "WHERE key='configuration_history_pair_id'");
        java.util.Properties receipt = new java.util.Properties();
        receipt.setProperty("pair_id", pairId);
        receipt.setProperty("configuration", database.getFileName().toString());
        Object fileKey = Files.readAttributes(history, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        if(fileKey != null)
        {
            receipt.setProperty("source_file_key", fileKey.toString());
        }
        Files.move(database, ActivityDatabasePath.configurationStage(database));
        try(var output = Files.newOutputStream(ActivityDatabasePath.splitReceipt(database)))
        {
            receipt.store(output, "interrupted activation fixture");
        }
        assertFalse(Files.exists(database));
        IOException refusal = assertThrows(IOException.class,
            () -> SdrTrunkDatabaseBootstrap.run(new String[0], dataRoot, true));
        assertTrue(refusal.getMessage().contains("--upgrade-current"));

        Path password = passwordFile("resume-activation-password.txt");
        assertTrue(SdrTrunkDatabaseBootstrap.run(new String[]{"--upgrade-current", "--no-upgrade-backup",
            "--admin-password-file", password.toString()}, dataRoot, true).startApplication());

        ConfigurationHistoryDatabase.validatePair(database);
        assertFalse(Files.exists(ActivityDatabasePath.splitReceipt(database)));
        assertFalse(Files.exists(ActivityDatabasePath.configurationStage(database)));
        assertFalse(Files.exists(database.getParent().resolve("backups")));
    }

    @Test
    void headlessUpgradeKeepsMigratorPasswordResetForPresentAndAbsentMarkers() throws Exception
    {
        for(boolean markerPresent: new boolean[]{true, false})
        {
            Path dataRoot = mTemporaryFolder.resolve(markerPresent ?
                "reset-invalid-admin-with-marker" : "reset-invalid-admin-without-marker");
            Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
            SdrTrunkTestDatabase.create(database);
            new WebAccessService(database).provisionOrResetPrimaryAdmin("old unusable password".toCharArray());
            try(Connection connection = open(database); Statement statement = connection.createStatement())
            {
                statement.execute("PRAGMA ignore_check_constraints=ON");
                statement.executeUpdate("UPDATE web_user SET password_hash=x'01' WHERE primary_admin=1");
                if(!markerPresent)
                {
                    statement.executeUpdate("DELETE FROM database_metadata WHERE key='initial_admin_setup'");
                }
                statement.execute("PRAGMA ignore_check_constraints=OFF");
            }
            Path passwordFile = passwordFile((markerPresent ? "present" : "absent") + "-new-password.txt");

            SdrTrunkDatabaseBootstrap.BootstrapResult result = SdrTrunkDatabaseBootstrap.run(
                new String[]{"--upgrade-current", "--admin-password-file", passwordFile.toString()},
                dataRoot, true, ApplicationMigrationServiceTestSupport.createInProcess());

            assertTrue(result.startApplication());
            assertEquals("complete", scalar(database, """
                SELECT value FROM database_metadata WHERE key='initial_admin_setup'
                """));
            assertTrue(new WebAccessService(database)
                .authenticate("admin", "migration admin password".toCharArray()).isPresent());
            assertEquals("1", scalar(database,
                "SELECT COUNT(*) FROM web_user WHERE primary_admin=1"));
        }
    }

    @Test
    void headlessCatalogUpgradeRequiresExplicitFlagWithoutChangingRecordings() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("catalog-needs-upgrade");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        Path catalog = createFormat1Catalog(database.resolveSibling("managed-recordings.sqlite"));
        byte[] before = sha256(catalog);

        IOException exception = assertThrows(IOException.class,
            () -> SdrTrunkDatabaseBootstrap.run(new String[0], dataRoot, true));

        assertTrue(exception.getMessage().contains("--upgrade-current"));
        assertArrayEquals(before, sha256(catalog));
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspect(catalog).state());
        assertTrue(catalogBackups(catalog).isEmpty());
    }

    @Test
    void headlessCurrentUpgradeUsesOneFlowWithAnOptionalRecoveryBackup() throws Exception
    {
        for(boolean backup: new boolean[]{true, false})
        {
            Path dataRoot = mTemporaryFolder.resolve(backup ? "upgrade-with-backup" : "upgrade-without-backup");
            Path database = Format30TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
            String aliases = scalar(database, "SELECT COUNT(*) FROM alias");
            String channels = scalar(database, "SELECT COUNT(*) FROM configuration_channel");
            String passwordVerifier = scalar(database, "SELECT hex(password_hash) FROM web_user WHERE primary_admin=1");
            String[] args = backup ? new String[]{"--upgrade-current"} :
                new String[]{"--upgrade-current", "--no-upgrade-backup"};

            assertTrue(SdrTrunkDatabaseBootstrap.run(args, dataRoot, true).startApplication());

            assertCurrentDatabase(database);
            assertEquals(aliases, scalar(database, "SELECT COUNT(*) FROM alias"));
            assertEquals(channels, scalar(database, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals(passwordVerifier, scalar(database, "SELECT hex(password_hash) FROM web_user WHERE primary_admin=1"));
            Path backups = database.getParent().resolve("backups");
            if(backup)
            {
                try(var files = Files.list(backups))
                {
                    assertEquals(1, files.filter(Files::isRegularFile).count());
                }
            }
            else assertFalse(Files.exists(backups));
        }
    }

    @Test
    void failedOptionalCatalogUpgradeDoesNotBlockReceivingOrReplaceItsSource() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("catalog-backup-failure");
        Path database = SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
        Path catalog = createFormat1Catalog(database.resolveSibling("managed-recordings.sqlite"));
        byte[] before = sha256(catalog);
        Path backupBlocker = database.getParent().resolve("backups");
        Files.writeString(backupBlocker, "existing unrelated file");

        Path password = passwordFile("catalog-failure-admin-password.txt");
        assertTrue(SdrTrunkDatabaseBootstrap.run(new String[]{"--upgrade-managed-recordings",
            "--admin-password-file", password.toString()}, dataRoot, true)
            .startApplication());

        assertArrayEquals(before, sha256(catalog));
        assertEquals("existing unrelated file", Files.readString(backupBlocker));
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspectForStartup(catalog).state());
        assertCurrentDatabase(database);
    }

    @Test
    void freshProfileMigratesOlderCatalogAfterCreatingMainDatabase() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("catalog-without-main-database");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Path catalog = createFormat1Catalog(database.resolveSibling("managed-recordings.sqlite"));
        Path passwordFile = passwordFile("fresh-with-catalog-password.txt");
        byte[] catalogBefore = sha256(catalog);

        IOException missingApproval = assertThrows(IOException.class,
            () -> SdrTrunkDatabaseBootstrap.run(
                new String[]{"--fresh", "--admin-password-file", passwordFile.toString()}, dataRoot, true));
        assertTrue(missingApproval.getMessage().contains("--upgrade-managed-recordings"));
        assertFalse(Files.exists(database));
        assertArrayEquals(catalogBefore, sha256(catalog));

        SdrTrunkDatabaseBootstrap.BootstrapResult result = SdrTrunkDatabaseBootstrap.run(
            new String[]{"--fresh", "--upgrade-managed-recordings", "--admin-password-file",
                passwordFile.toString()}, dataRoot, true);

        assertTrue(result.startApplication());
        assertTrue(Files.isRegularFile(database));
        assertEquals(ManagedRecordingCatalogMigrator.State.CURRENT,
            ManagedRecordingCatalogMigrator.inspect(catalog).state());
        assertEquals("1", scalar(catalog, "SELECT COUNT(*) FROM recording_call"));
        assertEquals("0", scalar(catalog, "SELECT COUNT(*) FROM recording_transcript"));
        List<Path> backups = catalogBackups(catalog);
        assertEquals(1, backups.size());
        assertEquals("1", scalar(backups.getFirst(), "PRAGMA user_version"));
    }

    @Test
    void headlessCatalogUpgradeRetainsCallsAndRecoveryCopy() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("catalog-upgrade");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        removeInitialSetupMarker(database);
        Path catalog = createFormat1Catalog(database.resolveSibling("managed-recordings.sqlite"));

        SdrTrunkDatabaseBootstrap.BootstrapResult result = SdrTrunkDatabaseBootstrap.run(
            new String[]{"--upgrade-current"}, dataRoot, true);

        assertTrue(result.startApplication());
        assertEquals(ManagedRecordingCatalogMigrator.State.CURRENT,
            ManagedRecordingCatalogMigrator.inspect(catalog).state());
        assertEquals(Integer.toString(ManagedRecordingSchema.CURRENT_FORMAT_VERSION),
            scalar(catalog, "PRAGMA user_version"));
        assertEquals(Integer.toString(ManagedRecordingSchema.CURRENT_FORMAT_VERSION),
            scalar(catalog, "SELECT format_version FROM catalog_metadata WHERE id=1"));
        assertEquals("1", scalar(catalog, "SELECT COUNT(*) FROM recording_call"));
        assertEquals("5", scalar(catalog, "SELECT total_bytes FROM catalog_metadata WHERE id=1"));
        assertEquals("0", scalar(catalog, "SELECT COUNT(*) FROM recording_transcript"));
        List<Path> backups = catalogBackups(catalog);
        assertEquals(1, backups.size());
        assertEquals("1", scalar(backups.getFirst(), "PRAGMA user_version"));
        assertEquals("1", scalar(backups.getFirst(), "SELECT COUNT(*) FROM recording_call"));
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void unusableOptionalCatalogKeepsHeadlessStartupFallback() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("unusable-catalog");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        removeInitialSetupMarker(database);
        Path catalog = database.resolveSibling("managed-recordings.sqlite");
        Files.write(catalog, new byte[0]);

        SdrTrunkDatabaseBootstrap.BootstrapResult result =
            SdrTrunkDatabaseBootstrap.run(new String[0], dataRoot, true);

        assertTrue(result.startApplication());
        assertEquals(0L, Files.size(catalog));
    }

    @Test
    void unsupportedExistingFormat2LayoutLeavesNoBackupOrStagedDatabase() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("unsupported-current");
        Path database = createFormat2Database(dataRoot, "Unsupported Existing");
        addUnexpectedSchemaObject(database);
        byte[] before = sha256(database);

        SQLException exception = assertThrows(SQLException.class,
            () -> SdrTrunkDatabaseBootstrap.run(new String[]{"--upgrade-current"}, dataRoot, true));

        assertTrue(exception.getMessage().contains("Unrecognized SQLite database schema fingerprint"));
        assertArrayEquals(before, sha256(database));
        assertFalse(Files.exists(database.getParent().resolve("backups")));
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void unsupportedImportedFormat2LayoutLeavesSourceAndTargetUntouched() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("unsupported-import-source");
        Path sourceDatabase = createFormat2Database(sourceRoot, "Unsupported Import");
        addUnexpectedSchemaObject(sourceDatabase);
        byte[] before = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("unsupported-import-target");

        SQLException exception = assertThrows(SQLException.class, () -> SdrTrunkDatabaseBootstrap.run(
            new String[]{"--upgrade-data", sourceRoot.toString()}, targetRoot, true));

        assertTrue(exception.getMessage().contains("Unrecognized SQLite database schema fingerprint"));
        assertArrayEquals(before, sha256(sourceDatabase));
        assertFalse(Files.exists(targetRoot));
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    private static List<Path> catalogBackups(Path catalog) throws Exception
    {
        Path backupDirectory = catalog.getParent().resolve("backups");
        if(!Files.isDirectory(backupDirectory))
        {
            return List.of();
        }
        try(var paths = Files.list(backupDirectory))
        {
            return paths.filter(path -> Files.isRegularFile(path) &&
                path.getFileName().toString().startsWith("managed-recordings-before-upgrade-")).toList();
        }
    }

    private static Path createFormat1Catalog(Path catalog) throws Exception
    {
        Files.createDirectories(catalog.getParent());
        try(Connection connection = open(catalog); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            for(String ddl: ManagedRecordingSchema.ddlForFormat(1).values())
            {
                statement.execute(ddl);
            }
            statement.execute("INSERT INTO catalog_metadata(id,format_version,call_count,total_bytes) " +
                "VALUES(1,1,1,5)");
            statement.execute("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path," +
                "size_bytes,protocol,call_type,voice_type) VALUES(1,1000,2000,1000,'test.mp3',5,1,1,1)");
            statement.execute("PRAGMA application_id=" + ManagedRecordingSchema.APPLICATION_ID);
            statement.execute("PRAGMA user_version=1");
        }
        return catalog;
    }

    private static Path createFormat2Database(Path dataRoot, String aliasName) throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Format2TestDatabase.create(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id, name, family) SELECT 1, 'Bootstrap', 'P25' " +
                "WHERE NOT EXISTS (SELECT 1 FROM alias_list WHERE id=1)");
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (1, 1, '%s', 'TALKGROUP', 'APCO25', 101)
                """.formatted(aliasName.replace("'", "''")));
        }

        return database;
    }

    private static void addUnexpectedSchemaObject(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE unexpected_format_1_bootstrap_object(id INTEGER PRIMARY KEY)");
        }
    }

    private Path passwordFile(String name) throws IOException
    {
        Path path = mTemporaryFolder.resolve(name);
        Files.writeString(path, "migration admin password\n");
        return path;
    }

    private static void removeInitialSetupMarker(Path database) throws Exception
    {
        try(Connection connection = open(database);
            java.sql.PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM database_metadata WHERE key = ?"))
        {
            statement.setString(1, InitialAdminSetup.METADATA_KEY);
            statement.executeUpdate();
        }
    }

    private static void assertCurrentDatabase(Path database) throws Exception
    {
        assertFormat(database, DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.current().id(), true);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
        assertEquals("wal", scalar(database, "PRAGMA journal_mode"));
    }

    private static void assertFormat(Path database, int version, String id, boolean markerPresent) throws Exception
    {
        DatabaseFormatCatalog.DetectedFormat source =
            ApplicationMigrationService.readMigrationPlan(database).source();
        assertEquals(version, source.version());
        assertEquals(id, source.id());
        assertEquals(markerPresent, source.markerPresent());
    }

    private static List<Path> regularFiles(Path directory) throws Exception
    {
        if(!Files.isDirectory(directory))
        {
            return List.of();
        }

        try(var paths = Files.list(directory))
        {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    private static void assertNoPrivateMigrationArtifacts(Path root) throws Exception
    {
        try(var paths = Files.walk(root))
        {
            List<Path> artifacts = paths.filter(path ->
            {
                Path fileName = path.getFileName();
                if(fileName == null)
                {
                    return false;
                }
                String name = fileName.toString();
                return name.contains(".migration-") || name.contains(".incomplete-") ||
                    name.contains(".restore-");
            }).toList();
            assertTrue(artifacts.isEmpty(), "Private migration artifacts remain: " + artifacts);
        }
    }

    private static void assertNoSqliteSidecars(Path database)
    {
        assertFalse(Files.exists(Path.of(database + "-journal")));
        assertFalse(Files.exists(Path.of(database + "-wal")));
        assertFalse(Files.exists(Path.of(database + "-shm")));
    }

    private static Connection open(Path database) throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        Path history = ActivityDatabasePath.forConfiguration(database);
        if(Files.isRegularFile(history))
        {
            try(Statement statement = connection.createStatement())
            {
                statement.execute("ATTACH DATABASE '" + history.toString().replace("'", "''") + "' AS activity");
            }
            catch(Exception failure)
            {
                connection.close();
                throw failure;
            }
        }
        return connection;
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
}
