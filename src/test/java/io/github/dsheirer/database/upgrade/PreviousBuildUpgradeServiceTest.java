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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.preference.encryption.vault.EncryptionKeyVaultPath;
import io.github.dsheirer.stats.activity.P25ActivityLogSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PreviousBuildUpgradeServiceTest
{
    private static final String VERSION_KEY = "p25_activity_schema_version";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void importsV19ProfileWithRealChildHelperAndLeavesSourceUnchanged() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("sdrtrunk-vce-alpha5-data");
        Path sourceDatabase = createV19Database(sourceRoot);
        insertAlias(sourceDatabase, "Dispatch");
        Path sourceVault = createVault(sourceRoot);
        Path jmbe = sourceRoot.resolve("jmbe").resolve("jmbe.jar");
        Path module = sourceRoot.resolve("modules").resolve("optional.jar");
        Files.createDirectories(jmbe.getParent());
        Files.createDirectories(module.getParent());
        Files.write(jmbe, new byte[] {1, 3, 5, 7});
        Files.write(module, new byte[] {2, 4, 6, 8});
        Path sourceRecordings = Files.createDirectories(sourceRoot.resolve("recordings"));
        Path sourceLogs = Files.createDirectories(sourceRoot.resolve("logs"));
        Path externalEventLogs = Files.createDirectories(mTemporaryFolder.resolve("shared-event-logs"));
        storePortableDirectoryPreferences(sourceDatabase, Map.of(
            "directory.recording", sourceRecordings.toString(),
            "directory.application.logs", sourceLogs.toString(),
            "directory.jmbe", sourceRoot.resolve("jmbe").toString(),
            "directory.event.logs", externalEventLogs.toString()
        ));

        byte[] sourceDatabaseHash = sha256(sourceDatabase);
        byte[] sourceVaultHash = sha256(sourceVault);
        byte[] jmbeContents = Files.readAllBytes(jmbe);
        byte[] moduleContents = Files.readAllBytes(module);
        Path targetRoot = mTemporaryFolder.resolve("sdrtrunk-vce-alpha6-data");
        List<String> progress = new ArrayList<>();

        PreviousBuildUpgradeService.UpgradeResult result = new PreviousBuildUpgradeService()
            .importPrevious(sourceRoot, targetRoot, progress::add);

        assertTrue(result.importedPreviousProfile());
        assertEquals(19, result.sourceVersion());
        assertNull(result.safetyBackup());
        assertTrue(result.helperOutput().contains("v19 -> v20"));
        assertTrue(result.helperOutput().contains("absent -> v1"));
        assertEquals(List.of("Checking previous data", "Copying setup", "Creating safety backup",
            "Updating database", "Checking updated data", "Finishing"), progress);

        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertEquals(20, PreviousBuildUpgradeService.readP25ActivitySchemaVersion(targetDatabase));
        assertEquals(1, count(targetDatabase, "alias"));
        assertTrue(tableExists(targetDatabase, "p25_foreign_system_band"));
        assertTrue(tableExists(targetDatabase, "p25_foreign_system_band_summary"));
        validateTrunkedSiteSchema(targetDatabase);
        assertEquals(1, count(EncryptionKeyVaultPath.getVaultPath(targetRoot), "vault_payload"));
        assertArrayEquals(jmbeContents, Files.readAllBytes(targetRoot.resolve("jmbe/jmbe.jar")));
        assertArrayEquals(moduleContents, Files.readAllBytes(targetRoot.resolve("modules/optional.jar")));
        Map<String,String> importedDirectories = portableDirectoryPreferences(targetDatabase);
        assertEquals(targetRoot.resolve("recordings").toString(), importedDirectories.get("directory.recording"));
        assertEquals(targetRoot.resolve("logs").toString(), importedDirectories.get("directory.application.logs"));
        assertEquals(targetRoot.resolve("jmbe").toString(), importedDirectories.get("directory.jmbe"));
        assertEquals(externalEventLogs.toString(), importedDirectories.get("directory.event.logs"));

        assertArrayEquals(sourceDatabaseHash, sha256(sourceDatabase));
        assertArrayEquals(sourceVaultHash, sha256(sourceVault));
        assertArrayEquals(jmbeContents, Files.readAllBytes(jmbe));
        assertArrayEquals(moduleContents, Files.readAllBytes(module));
        assertEquals(19, PreviousBuildUpgradeService.readP25ActivitySchemaVersion(sourceDatabase));
        assertFalse(tableExists(sourceDatabase, "p25_foreign_system_band"));
        assertFalse(tableExists(sourceDatabase, "trunked_site_snapshot"));
    }

    @Test
    void importsRealOldV20ProfileAndInstallsTrunkedSiteSchemaWithoutChangingSource() throws Exception
    {
        Path sourceRoot = mTemporaryFolder.resolve("sdrtrunk-vce-v20-data");
        Path sourceDatabase = createV20DatabaseWithoutTrunkedSiteSchema(sourceRoot);
        insertAlias(sourceDatabase, "Existing V20");
        byte[] sourceDatabaseHash = sha256(sourceDatabase);
        Path targetRoot = mTemporaryFolder.resolve("sdrtrunk-vce-current-data");

        PreviousBuildUpgradeService.UpgradeResult result = new PreviousBuildUpgradeService()
            .importPrevious(sourceRoot, targetRoot, null);

        assertTrue(result.importedPreviousProfile());
        assertEquals(20, result.sourceVersion());
        assertTrue(result.helperOutput().contains("already valid at P25 activity schema v20"));
        assertTrue(result.helperOutput().contains("absent -> v1"));

        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertEquals(20, PreviousBuildUpgradeService.readP25ActivitySchemaVersion(targetDatabase));
        assertEquals(1, count(targetDatabase, "alias"));
        validateTrunkedSiteSchema(targetDatabase);

        assertArrayEquals(sourceDatabaseHash, sha256(sourceDatabase));
        assertEquals(20, PreviousBuildUpgradeService.readP25ActivitySchemaVersion(sourceDatabase));
        assertFalse(tableExists(sourceDatabase, "trunked_site_snapshot"));
    }

    @Test
    void upgradesCurrentV19DatabaseAndRetainsV19SafetyBackup() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("current-data");
        Path database = createV19Database(dataRoot);
        insertAlias(database, "Fireground");

        PreviousBuildUpgradeService.UpgradeResult result = new PreviousBuildUpgradeService()
            .upgradeCurrent(dataRoot, null);

        assertFalse(result.importedPreviousProfile());
        assertEquals(19, result.sourceVersion());
        assertTrue(result.helperOutput().contains("v19 -> v20"));
        assertTrue(result.helperOutput().contains("absent -> v1"));
        assertNotNull(result.safetyBackup());
        assertTrue(Files.isRegularFile(result.safetyBackup()));
        assertTrue(result.safetyBackup().startsWith(database.getParent().resolve("backups")));

        assertEquals(20, PreviousBuildUpgradeService.readP25ActivitySchemaVersion(database));
        assertEquals(1, count(database, "alias"));
        try(Connection connection = open(database))
        {
            P25ActivityLogSchema.validate(connection);
            TrunkedSiteSchema.validate(connection);
        }

        assertEquals(19, PreviousBuildUpgradeService.readP25ActivitySchemaVersion(result.safetyBackup()));
        assertEquals(1, count(result.safetyBackup(), "alias"));
        assertFalse(tableExists(result.safetyBackup(), "p25_foreign_system_band"));
        assertFalse(tableExists(result.safetyBackup(), "trunked_site_snapshot"));
        assertEquals("ok", scalar(result.safetyBackup(), "PRAGMA quick_check"));
    }

    @Test
    void realHelperFailureLeavesCurrentDatabaseUnchanged() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("current-data");
        Path database = createV19Database(dataRoot);
        insertAlias(database, "Do Not Lose");

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE p25_site_neighbor");
        }

        byte[] before = sha256(database);
        IOException exception = assertThrows(IOException.class,
            () -> new PreviousBuildUpgradeService().upgradeCurrent(dataRoot, null));

        assertTrue(exception.getMessage().contains("database upgrade helper failed"));
        assertArrayEquals(before, sha256(database));
        assertEquals(19, PreviousBuildUpgradeService.readP25ActivitySchemaVersion(database));
        assertEquals(1, count(database, "alias"));
        assertFalse(tableExists(database, "p25_foreign_system_band"));

        Path backupDirectory = database.getParent().resolve("backups");

        try(var backups = Files.list(backupDirectory))
        {
            List<Path> paths = backups.toList();
            assertEquals(1, paths.size());
            assertEquals(19, PreviousBuildUpgradeService.readP25ActivitySchemaVersion(paths.getFirst()));
            assertEquals(1, count(paths.getFirst(), "alias"));
        }
    }

    @Test
    void partialTrunkedSiteSchemaFailureLeavesCurrentDatabaseUnchanged() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("current-data");
        Path database = createV19Database(dataRoot);
        insertAlias(database, "Keep Partial Source");

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE trunked_site_snapshot (guid TEXT PRIMARY KEY)");
        }

        byte[] before = sha256(database);
        IOException exception = assertThrows(IOException.class,
            () -> new PreviousBuildUpgradeService().upgradeCurrent(dataRoot, null));

        assertTrue(exception.getMessage().contains("database upgrade helper failed"));
        assertTrue(exception.getMessage().contains("ambiguous partial schema"));
        assertArrayEquals(before, sha256(database));
        assertEquals(19, PreviousBuildUpgradeService.readP25ActivitySchemaVersion(database));
        assertEquals(1, count(database, "alias"));
        assertFalse(tableExists(database, "p25_foreign_system_band"));
        assertTrue(tableExists(database, "trunked_site_snapshot"));
        assertFalse(tableExists(database, "trunked_site_channel_summary"));
        assertFalse(tableExists(database, "trunked_site_neighbor_summary"));
    }

    @Test
    void refusesToReplaceCurrentDatabaseWhileAnotherConnectionIsOpen() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("current-data");
        Path database = createV19Database(dataRoot);
        insertAlias(database, "Keep This");

        try(Connection otherProcess = open(database); Statement statement = otherProcess.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.executeQuery("SELECT COUNT(*) FROM alias").close();

            IOException exception = assertThrows(IOException.class,
                () -> new PreviousBuildUpgradeService().upgradeCurrent(dataRoot, null));
            assertTrue(exception.getMessage().contains("still active"));
            assertEquals(19, PreviousBuildUpgradeService.readP25ActivitySchemaVersion(database));
            assertEquals(1, count(database, "alias"));
        }
    }

    private Path createV19Database(Path dataRoot) throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE p25_foreign_system_band");
            statement.executeUpdate("DROP TABLE p25_foreign_system_band_summary");
            SdrTrunkDatabaseStartup.setMetadata(connection, VERSION_KEY, "19");
        }

        removeTrunkedSiteSchema(database);
        return database;
    }

    private Path createV20DatabaseWithoutTrunkedSiteSchema(Path dataRoot) throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        removeTrunkedSiteSchema(database);
        return database;
    }

    private static void removeTrunkedSiteSchema(Path database) throws Exception
    {
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE trunked_site_channel_summary");
            statement.executeUpdate("DROP TABLE trunked_site_neighbor_summary");
            statement.executeUpdate("DROP TABLE trunked_site_snapshot");
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='" +
                TrunkedSiteSchema.SCHEMA_VERSION_KEY + "'");
        }
    }

    private static void validateTrunkedSiteSchema(Path database) throws Exception
    {
        try(Connection connection = open(database))
        {
            TrunkedSiteSchema.validate(connection);
        }
    }

    private static Path createVault(Path dataRoot) throws Exception
    {
        Path vault = EncryptionKeyVaultPath.getVaultPath(dataRoot);
        SdrTrunkDatabaseStartup.createVaultDatabase(vault);

        try(Connection connection = open(vault); var statement = connection.prepareStatement("""
            INSERT INTO vault_payload(id, nonce, ciphertext, updated_at_ms)
            VALUES (1, ?, ?, ?)
            """))
        {
            statement.setBytes(1, new byte[] {11, 12, 13});
            statement.setBytes(2, new byte[] {21, 22, 23, 24});
            statement.setLong(3, 123456789L);
            statement.executeUpdate();
        }

        return vault;
    }

    private static void insertAlias(Path database, String name) throws Exception
    {
        try(Connection connection = open(database); var statement = connection.prepareStatement(
            "INSERT INTO alias(sort_order, name) VALUES (0, ?)"))
        {
            statement.setString(1, name);
            statement.executeUpdate();
        }
    }

    private static void storePortableDirectoryPreferences(Path database, Map<String,String> directories)
        throws Exception
    {
        String json = new ObjectMapper().writeValueAsString(Map.of(
            "user/io/github/dsheirer/preference/directory", directories));

        try(Connection connection = open(database); var statement = connection.prepareStatement("""
            INSERT INTO application_settings(key, settings_json, updated_at_ms) VALUES (?, ?, ?)
            """))
        {
            statement.setString(1, "portable_java_preferences_v1");
            statement.setString(2, json);
            statement.setLong(3, 1L);
            statement.executeUpdate();
        }
    }

    private static Map<String,String> portableDirectoryPreferences(Path database) throws Exception
    {
        try(Connection connection = open(database); var statement = connection.prepareStatement(
            "SELECT settings_json FROM application_settings WHERE key=?"))
        {
            statement.setString(1, "portable_java_preferences_v1");

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                Map<String,Map<String,String>> values = new ObjectMapper().readValue(resultSet.getString(1),
                    new TypeReference<>() {});
                return values.get("user/io/github/dsheirer/preference/directory");
            }
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

    private static boolean tableExists(Path database, String table) throws Exception
    {
        try(Connection connection = open(database); var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?"))
        {
            statement.setString(1, table);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static String scalar(Path database, String sql) throws Exception
    {
        try(Connection connection = open(database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static byte[] sha256(Path file) throws Exception
    {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
    }
}
