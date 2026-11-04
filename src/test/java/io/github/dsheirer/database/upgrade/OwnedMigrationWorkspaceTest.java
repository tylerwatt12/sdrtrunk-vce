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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OwnedMigrationWorkspaceTest
{
    @TempDir Path temporary;

    @Test void legacyPlansKeepConservativeJournalAndIndexAllowanceWithoutChangingSource() throws Exception
    {
        Path database = Format62TestDatabase.create(temporary.resolve("legacy.sqlite"));
        byte[] before = Files.readAllBytes(database);
        long compact = ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL);
        assertTrue(compact > 0);
        assertArrayEquals(before, Files.readAllBytes(database));
        long originalBytes = Files.size(database);
        growDetailedEvents(database);
        assertTrue(Files.size(database) > originalBytes);
        assertTrue(ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL) > compact);
        assertEquals(2 * Files.size(database),
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL));
        assertEquals(2 * Files.size(database),
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.CONFIGURATION_ONLY),
            "Legacy in-place history discard still needs rollback space");
    }

    @Test void fullPairedUpgradeBudgetsBothFilesAndRetainedEventGrowth() throws Exception
    {
        Path database = Format63TestDatabase.create(temporary.resolve("paired.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(database);
        byte[] configuration = Files.readAllBytes(database);
        byte[] activity = Files.readAllBytes(history);
        long workspace = ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL);
        assertEquals(2 * (Files.size(database) + Files.size(history)), workspace);
        assertTrue(workspace > 0);
        assertArrayEquals(configuration, Files.readAllBytes(database));
        assertArrayEquals(activity, Files.readAllBytes(history));
        long originalHistorySize = Files.size(history);
        growDetailedEvents(history);
        assertTrue(Files.size(history) > originalHistorySize);
        activity = Files.readAllBytes(history);
        assertTrue(ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL) > workspace);
        assertEquals(2 * (Files.size(database) + Files.size(history)),
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL));
        assertThrows(IllegalArgumentException.class,
            () -> ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.CONFIGURATION_ONLY),
            "Paired settings-only conversion must first use the supported settings-extraction workflow");
        assertArrayEquals(configuration, Files.readAllBytes(database));
        assertArrayEquals(activity, Files.readAllBytes(history));
        Files.delete(history);
        assertThrows(java.io.IOException.class,
            () -> ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL));
        assertFalse(Files.exists(history));
    }

    @Test void admittedConfigurationStepsDoNotBudgetUntouchedActivity() throws Exception
    {
        Path database = Format63TestDatabase.create(temporary.resolve("configuration-steps.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(database);
        var full = ApplicationMigrationService.readStartupPlan(database);
        var configurationTarget = DatabaseFormatCatalog.formats().stream()
            .filter(format -> format.version() == 68).findFirst().orElseThrow();
        var configurationPlan = new DatabaseMigrationChain.PreflightReport(full.source(), configurationTarget,
            full.steps().stream().filter(step -> step.targetVersion() <= 68).toList());
        long configurationWorkspace = ApplicationMigrationService.requiredOwnedMigrationWorkspace(database,
            MigrationContent.FULL, configurationPlan);
        assertTrue(configurationWorkspace >= Files.size(database),
            "The configuration owner still requires its own transaction and settings-growth allowance");
        byte[] configuration = Files.readAllBytes(database);
        byte[] activity = Files.readAllBytes(history);
        assertEquals(configurationWorkspace, ApplicationMigrationService.requiredOwnedMigrationWorkspace(database,
            MigrationContent.FULL, configurationPlan));
        assertArrayEquals(configuration, Files.readAllBytes(database));
        assertArrayEquals(activity, Files.readAllBytes(history));
        growDetailedEvents(history);
        activity = Files.readAllBytes(history);
        assertEquals(configurationWorkspace, ApplicationMigrationService.requiredOwnedMigrationWorkspace(database,
            MigrationContent.FULL, configurationPlan), "Unchanged Activity growth cannot increase this plan's workspace");
        assertEquals(2 * (Files.size(database) + Files.size(history)),
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL),
            "The actual plan also contains format 69 and still budgets its history transaction and index work");
        var unknownStep = new DatabaseMigrationChain.StepPreflight("future-configuration-looking-step", "Future step",
            68, 69, List.of());
        var unknown = new DatabaseMigrationChain.PreflightReport(full.source(), full.target(), List.of(unknownStep));
        assertEquals(2 * (Files.size(database) + Files.size(history)),
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL, unknown),
            "An unclassified plan must retain the conservative allowance");
        assertThrows(IllegalArgumentException.class, () -> ApplicationMigrationService.requiredOwnedMigrationWorkspace(
            database, MigrationContent.CONFIGURATION_ONLY, configurationPlan));
        assertArrayEquals(configuration, Files.readAllBytes(database));
        assertArrayEquals(activity, Files.readAllBytes(history));
    }

    @Test void boundedCurrentPreferenceRepairUsesConfigurationButNoOpNeedsNoMigrationWorkspace() throws Exception
    {
        Path database = Format71TestDatabase.create(temporary.resolve("preference-repair.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(database);
        assertEquals(0, ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("INSERT OR REPLACE INTO application_settings(key,settings_json,updated_at_ms) " +
                "VALUES('portable_java_preferences_v1','{invalid',1)");
        }
        var plan = ApplicationMigrationService.readStartupPlan(database);
        assertEquals(List.of("repair-portable-preferences"), plan.steps().stream().map(
            DatabaseMigrationChain.StepPreflight::id).toList());
        byte[] configuration = Files.readAllBytes(database);
        long workspace = ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL);
        assertTrue(workspace > 0);
        growDetailedEvents(history);
        byte[] activity = Files.readAllBytes(history);
        assertEquals(workspace, ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL));
        assertArrayEquals(configuration, Files.readAllBytes(database));
        assertArrayEquals(activity, Files.readAllBytes(history));
    }

    @Test void pairedUpgradeIncludesCommittedWalWithoutChangingEitherDatabase() throws Exception
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
            assertEquals(2 * (Files.size(database) + Files.size(wal) + Files.size(history)),
                ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL));
            assertArrayEquals(configuration, Files.readAllBytes(database));
            assertArrayEquals(committedWal, Files.readAllBytes(wal));
            assertArrayEquals(activity, Files.readAllBytes(history));
        }
    }

    @Test void otherFormatsKeepConservativeFootprintAndCurrentPairsRequireTheirCompanion() throws Exception
    {
        Path legacy = Format2TestDatabase.create(temporary.resolve("old.sqlite"));
        assertEquals(2 * Files.size(legacy),
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(legacy, MigrationContent.FULL));
        Path current = temporary.resolve("current.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(current);
        Path history = ActivityDatabasePath.forConfiguration(current);
        assertEquals(0, ApplicationMigrationService.requiredOwnedMigrationWorkspace(current, MigrationContent.FULL));
        assertThrows(IllegalArgumentException.class,
            () -> ApplicationMigrationService.requiredOwnedMigrationWorkspace(current, MigrationContent.CONFIGURATION_ONLY));
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

    @Test void explicitNoActivityJournalDoesNotExtendThe68To69ExceptionToTheNewTarget() throws Exception
    {
        Path database = Format68TestDatabase.create(temporary.resolve("no-activity-journal.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(database);
        byte[] configuration = Files.readAllBytes(database), activity = Files.readAllBytes(history);
        assertEquals(2 * (Files.size(database) + Files.size(history)),
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL, false));
        assertThrows(IllegalArgumentException.class,
            () -> ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL, true));
        assertArrayEquals(configuration, Files.readAllBytes(database));
        assertArrayEquals(activity, Files.readAllBytes(history));
        assertFalse(Files.exists(database.getParent().resolve("backups")));
    }

    @Test void explicitNoActivityJournalIncludesBothCommittedWalsWithoutChangingJournalModes() throws Exception
    {
        Path database = Format68TestDatabase.create(temporary.resolve("no-activity-journal-wal.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(database);
        try(var configuration = DriverManager.getConnection("jdbc:sqlite:" + database);
            var activity = DriverManager.getConnection("jdbc:sqlite:" + history);
            var configStatement = configuration.createStatement(); var activityStatement = activity.createStatement())
        {
            for(var statement: List.of(configStatement, activityStatement))
            {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA wal_autocheckpoint=0");
            }
            configStatement.executeUpdate("UPDATE application_settings SET updated_at_ms=updated_at_ms+1");
            activityStatement.executeUpdate("UPDATE receiver_activity_event SET encrypted=1-encrypted");
            Path configWal = Path.of(database + "-wal"), activityWal = Path.of(history + "-wal");
            assertTrue(Files.size(configWal) > 0 && Files.size(activityWal) > 0);
            var files = List.of(database, history, configWal, activityWal);
            var before = new ArrayList<byte[]>();
            for(Path file: files) before.add(Files.readAllBytes(file));
            assertThrows(IllegalArgumentException.class,
                () -> ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL, true));
            for(int index = 0; index < files.size(); index++)
                assertArrayEquals(before.get(index), Files.readAllBytes(files.get(index)));
            for(var statement: List.of(configStatement, activityStatement))
                try(var mode = statement.executeQuery("PRAGMA journal_mode"))
                { assertTrue(mode.next()); assertEquals("wal", mode.getString(1)); }
        }
    }

    @Test void explicitNoActivityJournalRejectsOtherFormatsAndContentsWithoutChangingThem() throws Exception
    {
        for(int version: List.of(62, 63, 69))
        {
            Path database = temporary.resolve("unsupported-no-journal-" + version + ".sqlite");
            if(version == 62) Format62TestDatabase.create(database);
            else if(version == 63) Format63TestDatabase.create(database);
            else Format69TestDatabase.create(database);
            byte[] before = Files.readAllBytes(database);
            Path history = ActivityDatabasePath.forConfiguration(database);
            byte[] historyBefore = Files.exists(history) ? Files.readAllBytes(history) : null;
            assertThrows(IllegalArgumentException.class,
                () -> ApplicationMigrationService.requiredOwnedMigrationWorkspace(database, MigrationContent.FULL, true));
            assertArrayEquals(before, Files.readAllBytes(database));
            if(historyBefore != null) assertArrayEquals(historyBefore, Files.readAllBytes(history));
        }
        Path database = Format68TestDatabase.create(temporary.resolve("settings-only-no-journal.sqlite"));
        byte[] before = Files.readAllBytes(database);
        assertThrows(IllegalArgumentException.class, () -> ApplicationMigrationService.requiredOwnedMigrationWorkspace(
            database, MigrationContent.CONFIGURATION_ONLY, true));
        assertArrayEquals(before, Files.readAllBytes(database));
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
