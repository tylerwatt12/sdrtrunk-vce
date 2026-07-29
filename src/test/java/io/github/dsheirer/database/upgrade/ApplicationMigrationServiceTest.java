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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationMigrationServiceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void onlyTheCurrentDevelopmentSchemaIsSupported()
    {
        ApplicationMigrationService.MigrationState current =
            new ApplicationMigrationService.MigrationState(4, 22, 2, 1);
        assertTrue(current.supported());
        assertFalse(current.requiresMigration());
        assertEquals("", current.requiredChanges());

        for(ApplicationMigrationService.MigrationState predecessor: List.of(
            new ApplicationMigrationService.MigrationState(3, 22, 2, 1),
            new ApplicationMigrationService.MigrationState(4, 21, 2, 1),
            new ApplicationMigrationService.MigrationState(4, 22, null, 1),
            new ApplicationMigrationService.MigrationState(4, 22, 2, null)))
        {
            assertFalse(predecessor.supported());
            assertTrue(predecessor.requiresMigration());
            assertTrue(predecessor.requiredChanges().contains("no bundled transition"));
        }
    }

    @Test
    void readsTheExactCurrentSchemaState() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("current-state");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        ApplicationMigrationService.MigrationState state =
            ApplicationMigrationService.readMigrationState(database);

        assertEquals(new ApplicationMigrationService.MigrationState(4, 22, 2, 1), state);
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

        ApplicationMigrationService.MigrationResult result = new ApplicationMigrationService()
            .importPrevious(sourceRoot, targetRoot, null);

        assertTrue(result.importedPreviousProfile());
        assertEquals(new ApplicationMigrationService.MigrationState(4, 22, 2, 1), result.sourceState());
        assertTrue(result.helperOutput().contains("Application database migration and validation complete"));
        Path targetDatabase = SdrTrunkDatabasePath.getDatabasePath(targetRoot);
        assertEquals(1, count(targetDatabase, "alias"));
        assertEquals(new ApplicationMigrationService.MigrationState(4, 22, 2, 1),
            ApplicationMigrationService.readMigrationState(targetDatabase));
        assertArrayEquals(sourceHash, sha256(sourceDatabase));
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
        assertEquals(new ApplicationMigrationService.MigrationState(4, 22, 2, 1),
            ApplicationMigrationService.readMigrationState(result.safetyBackup()));
        assertEquals(1, count(database, "alias"));
        assertEquals(new ApplicationMigrationService.MigrationState(4, 22, 2, 1),
            ApplicationMigrationService.readMigrationState(database));
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

        assertTrue(exception.getMessage().contains("accepts only its current schemas"));
        assertArrayEquals(before, sha256(database));
        assertFalse(Files.exists(database.getParent().resolve("backups")));
    }

    @Test
    void helperFailureLeavesTheCurrentDatabaseUntouched() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("failure-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        insertAlias(database, "Do Not Lose");
        byte[] before = sha256(database);
        ApplicationMigrationService service = new ApplicationMigrationService(
            (source, destination) -> Files.copy(source, destination),
            (staged, source, target) ->
            {
                throw new IOException("forced helper failure");
            });

        IOException exception = assertThrows(IOException.class, () -> service.migrateCurrent(dataRoot, null));

        assertTrue(exception.getMessage().contains("forced helper failure"));
        assertArrayEquals(before, sha256(database));
        assertEquals(1, count(database, "alias"));
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
                INSERT INTO alias_list(id, name, system_name, family)
                VALUES (1, 'Test', 'Test', 'P25')
                """);
            statement.setString(1, name);
            statement.executeUpdate();
        }
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

    private static byte[] sha256(Path path) throws Exception
    {
        return MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
    }
}
