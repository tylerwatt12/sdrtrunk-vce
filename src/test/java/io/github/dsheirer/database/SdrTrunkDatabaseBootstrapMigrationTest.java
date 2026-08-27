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
import io.github.dsheirer.database.upgrade.DatabaseFormatCatalog;
import io.github.dsheirer.database.upgrade.Format1TestDatabase;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultPath;
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
    void headlessFormat1WithoutUpgradeFlagRefusesWithoutMutation() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("no-upgrade-flag");
        Path database = createFormat1Database(dataRoot, "Retained Without Flag");
        byte[] before = sha256(database);

        IOException exception = assertThrows(IOException.class,
            () -> SdrTrunkDatabaseBootstrap.run(new String[0], dataRoot, true));

        assertTrue(exception.getMessage().contains("--upgrade-current"));
        assertArrayEquals(before, sha256(database));
        assertFormat(database, 1, "alpha8-shared", false);
        assertFalse(Files.exists(database.getParent().resolve("backups")));
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void headlessUpgradeCurrentMigratesFormat1AndRetainsItsSafetyBackup() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("upgrade-current");
        Path database = createFormat1Database(dataRoot, "Migrated In Place");

        SdrTrunkDatabaseBootstrap.BootstrapResult result =
            SdrTrunkDatabaseBootstrap.run(new String[]{"--upgrade-current"}, dataRoot, true);

        assertTrue(result.startApplication());
        assertFalse(result.initializeNewPreferences());
        assertCurrentDatabase(database);
        assertEquals("Migrated In Place", scalar(database, "SELECT name FROM alias WHERE id=1"));
        List<Path> backups = regularFiles(database.getParent().resolve("backups"));
        assertEquals(1, backups.size());
        assertFormat(backups.getFirst(), 1, "alpha8-shared", false);
        assertEquals("Migrated In Place", scalar(backups.getFirst(), "SELECT name FROM alias WHERE id=1"));
        assertTrue(Files.isRegularFile(EncryptionKeyVaultPath.getVaultPath(dataRoot)));
        assertNoSqliteSidecars(backups.getFirst());
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void upgradeDataImportsAndMigratesFormat1WithoutChangingItsSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("import-source");
        Path sourceDatabase = createFormat1Database(sourceRoot, "Imported Format 1");
        byte[] sourceHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("import-target");
        Path passwordFile = passwordFile("import-target-password.txt");

        SdrTrunkDatabaseBootstrap.BootstrapResult result = SdrTrunkDatabaseBootstrap.run(
            new String[]{"--upgrade-data", sourceRoot.toString(), "--admin-password-file", passwordFile.toString()},
            targetRoot, true);

        assertTrue(result.startApplication());
        assertFalse(result.initializeNewPreferences());
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        assertFormat(sourceDatabase, 1, "alpha8-shared", false);
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertCurrentDatabase(targetDatabase);
        assertEquals("Imported Format 1", scalar(targetDatabase, "SELECT name FROM alias WHERE id=1"));
        assertFalse(Files.exists(targetDatabase.getParent().resolve("backups")));
        assertTrue(Files.isRegularFile(EncryptionKeyVaultPath.getVaultPath(targetRoot)));
        assertNoPrivateMigrationArtifacts(mTemporaryFolder);
    }

    @Test
    void upgradeDataWithDirectDatabaseSelectionImportsOnlyThatFile() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("database-file-import-source");
        Path sourceDatabase = createFormat1Database(sourceRoot, "Imported Database File");
        byte[] sourceHash = sha256(sourceDatabase);
        Files.createDirectories(sourceRoot.resolve("jmbe"));
        Files.writeString(sourceRoot.resolve("jmbe/jmbe-test.jar"), "must not be copied");
        Files.createDirectories(sourceRoot.resolve("modules"));
        Files.writeString(sourceRoot.resolve("modules/module-test.jar"), "must not be copied");
        Files.createDirectories(sourceRoot.resolve("vault"));
        Files.writeString(EncryptionKeyVaultPath.getVaultPath(sourceRoot), "invalid unselected vault");
        Path targetRoot = mTemporaryFolder.resolve("database-file-import-target");
        Path passwordFile = passwordFile("database-file-import-password.txt");

        SdrTrunkDatabaseBootstrap.BootstrapResult result = SdrTrunkDatabaseBootstrap.run(
            new String[]{"--upgrade-data", sourceDatabase.toString(), "--admin-password-file",
                passwordFile.toString()}, targetRoot, true);

        assertTrue(result.startApplication());
        assertFalse(result.initializeNewPreferences());
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertCurrentDatabase(targetDatabase);
        assertEquals("Imported Database File", scalar(targetDatabase, "SELECT name FROM alias WHERE id=1"));
        assertFalse(Files.exists(targetRoot.resolve("jmbe")));
        assertFalse(Files.exists(targetRoot.resolve("modules")));
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
    void unsupportedExistingFormat1LayoutLeavesNoBackupOrStagedDatabase() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("unsupported-current");
        Path database = createFormat1Database(dataRoot, "Unsupported Existing");
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
    void unsupportedImportedFormat1LayoutLeavesSourceAndTargetUntouched() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("unsupported-import-source");
        Path sourceDatabase = createFormat1Database(sourceRoot, "Unsupported Import");
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

    private static Path createFormat1Database(Path dataRoot, String aliasName) throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Format1TestDatabase.create(database);

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
