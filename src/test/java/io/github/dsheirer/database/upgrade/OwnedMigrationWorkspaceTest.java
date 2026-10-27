/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnedMigrationWorkspaceTest
{
    @TempDir Path temporary;

    @Test void fullSplitWorkspaceDoesNotGrowWithDetailedEventsAndDoesNotChangeTheSource() throws Exception
    {
        Path database = Format62TestDatabase.create(temporary.resolve("legacy.sqlite"));
        byte[] before = Files.readAllBytes(database);
        long compact = ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL);
        assertTrue(compact > 0);
        assertArrayEquals(before, Files.readAllBytes(database));
        long originalBytes = Files.size(database);
        growDetailedEvents(database);
        assertTrue(Files.size(database) > originalBytes);
        assertEquals(compact,
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL));
        assertEquals(Files.size(database),
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.CONFIGURATION_ONLY));
    }

    @Test void fullNicknameUpgradeBudgetsConfigurationWithoutGrowingWithUntouchedActivity() throws Exception
    {
        Path database = Format63TestDatabase.create(temporary.resolve("paired.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(database);
        byte[] configuration = Files.readAllBytes(database);
        byte[] activity = Files.readAllBytes(history);
        long workspace = ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL);
        assertEquals(Files.size(database), workspace);
        assertTrue(workspace > 0);
        assertArrayEquals(configuration, Files.readAllBytes(database));
        assertArrayEquals(activity, Files.readAllBytes(history));
        long originalHistorySize = Files.size(history);
        growDetailedEvents(history);
        assertTrue(Files.size(history) > originalHistorySize);
        activity = Files.readAllBytes(history);
        assertEquals(workspace,
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL));
        assertEquals(Files.size(database) + Files.size(history),
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.CONFIGURATION_ONLY),
            "Discarding Activity still needs the conservative paired-file workspace");
        assertArrayEquals(configuration, Files.readAllBytes(database));
        assertArrayEquals(activity, Files.readAllBytes(history));
        Files.delete(history);
        assertThrows(java.io.IOException.class,
            () -> ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL));
        assertFalse(Files.exists(history));
    }

    @Test void nicknameUpgradeIncludesCommittedConfigurationWalWithoutChangingEitherDatabase() throws Exception
    {
        Path database = Format63TestDatabase.create(temporary.resolve("paired-wal.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(database);
        byte[] activity = Files.readAllBytes(history);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.executeUpdate("UPDATE application_settings SET updated_at_ms=updated_at_ms+1");
            Path wal = Path.of(database + "-wal");
            assertTrue(Files.size(wal) > 0);
            byte[] configuration = Files.readAllBytes(database);
            byte[] committedWal = Files.readAllBytes(wal);
            assertEquals(Files.size(database) + Files.size(wal),
                ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL));
            assertArrayEquals(configuration, Files.readAllBytes(database));
            assertArrayEquals(committedWal, Files.readAllBytes(wal));
            assertArrayEquals(activity, Files.readAllBytes(history));
        }
    }

    @Test void otherFormatsKeepConservativeFootprintAndCurrentPairsRequireTheirCompanion() throws Exception
    {
        Path legacy = Format2TestDatabase.create(temporary.resolve("old.sqlite"));
        assertEquals(Files.size(legacy),
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(legacy, MigrationContent.FULL));
        Path current = temporary.resolve("current.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(current);
        Path history = ActivityDatabasePath.forConfiguration(current);
        assertTrue(ApplicationMigrationService.requiredOwnedMigrationWorkspace(current, MigrationContent.FULL) >=
            Files.size(current) + Files.size(history));
        Files.delete(history);
        assertThrows(java.io.IOException.class,
            () -> ApplicationMigrationService.requiredOwnedMigrationWorkspace(current, MigrationContent.FULL));
        assertFalse(Files.exists(history));
    }

    @Test void rejectsAnUnknownSourceBeforeReportingWorkspace() throws Exception
    {
        Path unknown = temporary.resolve("unknown.sqlite");
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + unknown);
            var statement = connection.createStatement())
        { statement.execute("CREATE TABLE unrelated(id INTEGER PRIMARY KEY)"); }
        byte[] before = Files.readAllBytes(unknown);
        assertThrows(SQLException.class,
            () -> ApplicationMigrationService.requiredOwnedMigrationWorkspace(unknown, MigrationContent.FULL));
        assertArrayEquals(before, Files.readAllBytes(unknown));
    }

    private static void growDetailedEvents(Path database) throws SQLException
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            var fields = new ArrayList<String>();
            try(var columns = statement.executeQuery("PRAGMA table_info(receiver_activity_event)"))
            {
                while(columns.next()) if(!"id".equals(columns.getString("name"))) fields.add(columns.getString("name"));
            }
            String names = String.join(",", fields);
            statement.executeUpdate("INSERT INTO receiver_activity_event(" + names + ") " +
                "WITH RECURSIVE copies(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM copies WHERE n<10000) " +
                "SELECT " + names + " FROM receiver_activity_event CROSS JOIN copies " +
                "WHERE receiver_activity_event.id=(SELECT min(id) FROM receiver_activity_event)");
        }
    }
}
