/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.ConfigurationHistorySchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.database.settings.ReceiverNicknameStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Format63To64DatabaseMigrationTest
{
    private static final String RECEIVER = "tuner-0123456789abcdef0123456789abcdef";
    @TempDir Path directory;

    @Test void currentProfileUpgradePreservesActivityAndInitializesOnlyEmptyNames() throws Exception
    {
        Path database = Format63TestDatabase.create(directory.resolve("sdrtrunk.sqlite"));
        var expectedActivity = ActivityMigrationPreservation.capture(database);
        byte[] configuration = Files.readAllBytes(database);
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
        assertArrayEquals(configuration, Files.readAllBytes(database));
        var report = ApplicationDatabaseMigrator.migrateInPlace(database, null);
        assertTrue(report.helperOutput().contains("receiver nickname settings"));
        ConfigurationHistoryDatabase.validatePair(database);
        ActivityMigrationPreservation.assertCurrent(database, expectedActivity);
        assertEquals(java.util.Map.of(), new ReceiverNicknameStore(database).snapshot());
        assertCurrentConfiguration(database);
    }

    @Test void adjacentNicknameStepLeavesActivityBytesUntouched() throws Exception
    {
        Path database = Format63TestDatabase.create(directory.resolve("nickname-only.sqlite"));
        byte[] activity = Files.readAllBytes(ActivityDatabasePath.forConfiguration(database));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format63To64DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 64);
            connection.commit();
        }
        assertArrayEquals(activity, Files.readAllBytes(ActivityDatabasePath.forConfiguration(database)));
        ConfigurationHistoryDatabase.validatePairIdentity(database);
    }

    @Test void fullUpgradePreservationRejectsAnUnrelatedHistoryChange() throws Exception
    {
        Path database = Format63TestDatabase.create(directory.resolve("changed-history.sqlite"));
        var expected = ActivityMigrationPreservation.capture(database);
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        ActivityMigrationPreservation.assertCurrent(database, expected);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + ActivityDatabasePath.forConfiguration(database));
            var statement = connection.createStatement())
        {
            assertEquals(1, statement.executeUpdate("UPDATE receiver_activity_event SET observed_at_ms=observed_at_ms+1 " +
                "WHERE id=(SELECT id FROM receiver_activity_event ORDER BY id LIMIT 1)"));
        }
        assertThrows(AssertionError.class, () -> ActivityMigrationPreservation.assertCurrent(database, expected));
    }

    @Test void failedUpgradeRollsBackAndUnchangedSourceCanBeRetried() throws Exception
    {
        Path database = Format63TestDatabase.create(directory.resolve("retry.sqlite"));
        var expectedActivity = ActivityMigrationPreservation.capture(database);
        var originalActivity = ActivityMigrationPreservation.original(database);
        assertThrows(IllegalStateException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database, phase -> {
            if(phase.equals("Validating the updated schema and configuration"))
                throw new IllegalStateException("injected precommit failure");
        }));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(63, DatabaseFormatCatalog.inspect(connection).version());
            assertFalse(new io.github.dsheirer.database.settings.ApplicationSettingsStore(database)
                .contains("receiver.nicknames"));
        }
        assertEquals(originalActivity, ActivityMigrationPreservation.original(database));
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        assertCurrentConfiguration(database);
        ActivityMigrationPreservation.assertCurrent(database, expectedActivity);
    }

    @Test void priorMonolithTraversesBothAdjacentStepsAndMatchesFreshCurrentRoles() throws Exception
    {
        Path database = Format62TestDatabase.create(directory.resolve("older.sqlite"));
        ApplicationDatabaseMigrator.migrateInPlace(database, null);
        assertCurrentConfiguration(database);
        Path fresh = directory.resolve("fresh.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(fresh);
        for(Path path : java.util.List.of(database, fresh))
        {
            ConfigurationHistoryDatabase.validatePair(path);
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path))
            {
                assertEquals(ConfigurationHistoryDatabase.CONFIGURATION_FINGERPRINT,
                    SqliteSchemaValidator.fingerprint(connection));
            }
        }
    }

    @Test void externalPairImportPreservesSourceAndNamesAndCurrentUpgradeIsNoOp() throws Exception
    {
        Path sourceFolder = Files.createDirectories(directory.resolve("source"));
        Path source = Format63TestDatabase.create(
            io.github.dsheirer.database.SdrTrunkDatabasePath.getDatabasePath(sourceFolder));
        byte[] original = Files.readAllBytes(source);
        byte[] originalActivity = Files.readAllBytes(ActivityDatabasePath.forConfiguration(source));
        Path targetFolder = directory.resolve("imported");
        var service = ApplicationMigrationServiceTestSupport.createInProcess();
        service.importPrevious(sourceFolder, targetFolder, null);
        Path staged = io.github.dsheirer.database.SdrTrunkDatabasePath.getDatabasePath(targetFolder);
        assertCurrentConfiguration(staged);
        new ReceiverNicknameStore(staged).set(RECEIVER, "Roof antenna");
        byte[] before = Files.readAllBytes(staged);
        ApplicationDatabaseMigrator.migrateInPlace(staged, null);
        assertArrayEquals(before, Files.readAllBytes(staged));
        assertEquals("Roof antenna", new ReceiverNicknameStore(staged).get(RECEIVER));
        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals(originalActivity, Files.readAllBytes(ActivityDatabasePath.forConfiguration(source)));
        Path retriedFolder = directory.resolve("retried-import");
        service.importPrevious(sourceFolder, retriedFolder, null);
        assertCurrentConfiguration(io.github.dsheirer.database.SdrTrunkDatabasePath.getDatabasePath(retriedFolder));
        assertArrayEquals(original, Files.readAllBytes(source));
        assertArrayEquals(originalActivity, Files.readAllBytes(ActivityDatabasePath.forConfiguration(source)));
    }

    @Test void incompatibleCompanionVersionAndRoleAreRefused() throws Exception
    {
        Path database = Format67TestDatabase.create(directory.resolve("invalid.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + history))
        {
            io.github.dsheirer.database.SdrTrunkDatabaseStartup.setMetadata(connection,
                DatabaseFormatCatalog.FORMAT_VERSION_KEY, "68");
        }
        assertThrows(SQLException.class, () -> ConfigurationHistoryDatabase.validatePair(database));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + history))
        {
            io.github.dsheirer.database.SdrTrunkDatabaseStartup.setMetadata(connection,
                DatabaseFormatCatalog.FORMAT_VERSION_KEY, "63");
            io.github.dsheirer.database.SdrTrunkDatabaseStartup.setMetadata(connection,
                ConfigurationHistorySchema.ROLE_KEY, "configuration");
        }
        assertThrows(SQLException.class, () -> ConfigurationHistoryDatabase.validatePair(database));
    }

    @Test void olderConfigurationCannotBorrowANewerActivityRoleOrIgnoreAMissingCompanion() throws Exception
    {
        Path database = Format63TestDatabase.create(directory.resolve("old-configuration.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(database);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + history))
        {
            io.github.dsheirer.database.SdrTrunkDatabaseStartup.setMetadata(connection,
                DatabaseFormatCatalog.FORMAT_VERSION_KEY, "64");
        }
        assertThrows(SQLException.class, () -> ConfigurationHistoryDatabase.validatePairIdentity(database));
        assertThrows(SQLException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database, null));
        Files.delete(history);
        assertThrows(java.io.IOException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(database, null));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(63, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    private static void assertCurrentConfiguration(Path database) throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA quick_check"))
            { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_check"))
            { assertFalse(rows.next()); }
        }
    }
}
