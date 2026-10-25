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
        assertTrue(Files.size(database) > originalBytes);
        assertEquals(compact,
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL));
        assertEquals(Files.size(database),
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.CONFIGURATION_ONLY));
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
}
