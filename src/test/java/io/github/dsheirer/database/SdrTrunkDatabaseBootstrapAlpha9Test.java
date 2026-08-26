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

import io.github.dsheirer.database.upgrade.Alpha9TestDatabase;
import io.github.dsheirer.database.upgrade.ApplicationMigrationService;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultPath;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
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

class SdrTrunkDatabaseBootstrapAlpha9Test
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void headlessAlpha9WithoutUpgradeFlagRefusesWithoutMutation() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("no-upgrade-flag");
        Path database = createAlpha9Database(dataRoot, "Retained Without Flag");
        byte[] before = sha256(database);

        IOException exception = assertThrows(IOException.class,
            () -> SdrTrunkDatabaseBootstrap.run(new String[0], dataRoot, true));

        assertTrue(exception.getMessage().contains("--upgrade-current"));
        assertArrayEquals(before, sha256(database));
        assertEquals(alpha9State(), ApplicationMigrationService.readMigrationState(database));
        assertFalse(Files.exists(database.getParent().resolve("backups")));
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void headlessUpgradeCurrentMigratesAlpha9AndRetainsItsSafetyBackup() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("upgrade-current");
        Path database = createAlpha9Database(dataRoot, "Migrated In Place");

        SdrTrunkDatabaseBootstrap.BootstrapResult result =
            SdrTrunkDatabaseBootstrap.run(new String[]{"--upgrade-current"}, dataRoot, true);

        assertTrue(result.startApplication());
        assertFalse(result.initializeNewPreferences());
        assertCurrentDatabase(database);
        assertEquals("Migrated In Place", scalar(database, "SELECT name FROM alias WHERE id=1"));
        List<Path> backups = regularFiles(database.getParent().resolve("backups"));
        assertEquals(1, backups.size());
        assertEquals(alpha9State(), ApplicationMigrationService.readMigrationState(backups.getFirst()));
        assertEquals("Migrated In Place", scalar(backups.getFirst(), "SELECT name FROM alias WHERE id=1"));
        assertTrue(Files.isRegularFile(EncryptionKeyVaultPath.getVaultPath(dataRoot)));
        assertNoSqliteSidecars(backups.getFirst());
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void upgradeDataImportsAndMigratesAlpha9WithoutChangingItsSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("import-source");
        Path sourceDatabase = createAlpha9Database(sourceRoot, "Imported Alpha 9");
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("import-target");
        Path passwordFile = passwordFile("import-target-password.txt");

        SdrTrunkDatabaseBootstrap.BootstrapResult result = SdrTrunkDatabaseBootstrap.run(
            new String[]{"--upgrade-data", sourceRoot.toString(), "--admin-password-file", passwordFile.toString()},
            targetRoot, true);

        assertTrue(result.startApplication());
        assertFalse(result.initializeNewPreferences());
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertEquals(alpha9State(), ApplicationMigrationService.readMigrationState(sourceDatabase));
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertCurrentDatabase(targetDatabase);
        assertEquals("Imported Alpha 9", scalar(targetDatabase, "SELECT name FROM alias WHERE id=1"));
        assertFalse(Files.exists(targetDatabase.getParent().resolve("backups")));
        assertTrue(Files.isRegularFile(EncryptionKeyVaultPath.getVaultPath(targetRoot)));
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void exactCurrentDatabaseStartsWithoutMigration() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("current");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
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
    void unsupportedExistingAlpha9LayoutLeavesNoBackupOrStagedDatabase() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("unsupported-current");
        Path database = createAlpha9Database(dataRoot, "Unsupported Existing");
        addUnexpectedSchemaObject(database);
        byte[] before = sha256(database);

        SQLException exception = assertThrows(SQLException.class,
            () -> SdrTrunkDatabaseBootstrap.run(new String[]{"--upgrade-current"}, dataRoot, true));

        assertTrue(exception.getMessage().contains(
            "exact shared v0.6.2 Alpha 8/Alpha 9/Alpha 10 schema layout"));
        assertArrayEquals(before, sha256(database));
        assertFalse(Files.exists(database.getParent().resolve("backups")));
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void unsupportedImportedAlpha9LayoutLeavesSourceAndTargetUntouched() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("unsupported-import-source");
        Path sourceDatabase = createAlpha9Database(sourceRoot, "Unsupported Import");
        addUnexpectedSchemaObject(sourceDatabase);
        byte[] before = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("unsupported-import-target");

        SQLException exception = assertThrows(SQLException.class, () -> SdrTrunkDatabaseBootstrap.run(
            new String[]{"--upgrade-data", sourceRoot.toString()}, targetRoot, true));

        assertTrue(exception.getMessage().contains(
            "exact shared v0.6.2 Alpha 8/Alpha 9/Alpha 10 schema layout"));
        assertArrayEquals(before, sha256(sourceDatabase));
        assertFalse(Files.exists(targetRoot));
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    private static Path createAlpha9Database(Path dataRoot, String aliasName) throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Alpha9TestDatabase.create(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO alias_list(id, name, family) VALUES (1, 'Bootstrap', 'P25')");
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
            statement.executeUpdate("CREATE TABLE unexpected_alpha9_bootstrap_object(id INTEGER PRIMARY KEY)");
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
        assertEquals(currentState(), ApplicationMigrationService.readMigrationState(database));
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
        assertEquals("wal", scalar(database, "PRAGMA journal_mode"));
    }

    private static ApplicationMigrationService.MigrationState alpha9State()
    {
        return new ApplicationMigrationService.MigrationState(
            ApplicationMigrationService.ALPHA_9_ALIAS_VERSION,
            ApplicationMigrationService.ALPHA_9_P25_VERSION,
            TrunkedSiteSchema.SCHEMA_VERSION,
            ApplicationMigrationService.CURRENT_DMR_VERSION);
    }

    private static ApplicationMigrationService.MigrationState currentState()
    {
        return new ApplicationMigrationService.MigrationState(
            ApplicationMigrationService.CURRENT_ALIAS_VERSION,
            ApplicationMigrationService.CURRENT_P25_VERSION,
            TrunkedSiteSchema.SCHEMA_VERSION,
            ApplicationMigrationService.CURRENT_DMR_VERSION);
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
        return DriverManager.getConnection("jdbc:sqlite:" + database);
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
