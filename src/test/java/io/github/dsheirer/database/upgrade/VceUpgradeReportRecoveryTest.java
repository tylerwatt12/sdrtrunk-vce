/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.portable.PortableDataRootLock;
import java.nio.file.Files;
import java.nio.file.Path;
import io.github.dsheirer.portable.PortableFileIdentity;
import java.sql.DriverManager;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class VceUpgradeReportRecoveryTest
{
    @TempDir Path temporary;

    @Test
    void committedFullRetryKeepsTheOriginalSourcePlanAndDisclosesCompletedHistoryResets() throws Exception
    {
        Path source = Files.createDirectories(temporary.resolve("alpha/data"));
        Path target = Files.createDirectories(temporary.resolve("next")).resolve("data");
        Format2TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(source));
        var originalPlan = VceUpgradeService.inspect(source).withContent(MigrationContent.FULL);
        assertTrue(originalPlan.steps().stream().flatMap(step -> step.effects().stream())
            .anyMatch(effect -> effect.kind() == DatabaseMigrationEffect.Kind.RESET));
        try(AutoCloseable observer = VceUpgradeService.observePhases(phase -> {
            if(phase.equals("COMMITTED")) throw new SimulatedCrash();
        }))
        {
            assertThrows(SimulatedCrash.class, () -> VceUpgradeService.upgrade(source, target,
                MigrationContent.FULL, true, null, null));
        }
        assertTrue(VceUpgradeService.isPending(target));

        var result = VceUpgradeService.resume(target, null, null);

        try(PortableDataRootLock ignored = result.lock())
        {
            assertEquals(originalPlan, result.migration().sourcePlan());
            assertTrue(result.migration().helperOutput().contains("Migration plan: format 2 ["), result.migration()::helperOutput);
            assertTrue(result.migration().helperOutput().contains("COMPLETED STEP: 2 -> 3"), result.migration()::helperOutput);
            assertTrue(result.migration().helperOutput().contains("RESET"), result.migration()::helperOutput);
            assertTrue(result.migration().completedWithRepairsOrSkippedItems());
            assertFalse(VceUpgradeService.isPending(target));
        }
    }

    @Test
    void completionReportSurvivesWorkspaceCleanupBeforeTheIntentIsRemoved() throws Exception
    {
        Path source = Files.createDirectories(temporary.resolve("cleanup-old/data"));
        Path target = Files.createDirectories(temporary.resolve("cleanup-new")).resolve("data");
        Format62TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(source));
        var originalPlan = VceUpgradeService.inspect(source);
        interruptAfterCommit(source, target);
        Properties intent = intent(target);
        try(var entries = Files.walk(Path.of(intent.getProperty("work"))))
        {
            for(Path file: entries.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
        }

        var result = VceUpgradeService.resume(target, null, null);

        try(PortableDataRootLock ignored = result.lock())
        {
            assertEquals(originalPlan, result.migration().sourcePlan());
            assertTrue(result.migration().helperOutput().contains("COMPLETED STEP: 62 -> 63"), result.migration()::helperOutput);
            assertFalse(VceUpgradeService.isPending(target));
        }
    }

    @Test
    void committedDatabaseRecoversEvenWhenOptionalPlanReportingDataIsMalformed() throws Exception
    {
        Path source = Files.createDirectories(temporary.resolve("report-old/data"));
        Path target = Files.createDirectories(temporary.resolve("report-new")).resolve("data");
        Format2TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(source));
        interruptAfterCommit(source, target);
        Properties intent = intent(target);
        intent.setProperty("sourcePlan", "not JSON");
        try(var output = Files.newOutputStream(intentPath(target))) { intent.store(output, "corrupted optional report fixture"); }

        var result = VceUpgradeService.resume(target, null, null);

        try(PortableDataRootLock ignored = result.lock())
        {
            assertEquals(2, result.migration().sourcePlan().source().version());
            assertTrue(result.migration().helperOutput().contains("WARNING: The original detailed update plan is unavailable"));
            assertTrue(result.migration().helperOutput().contains("COMPLETED STEP: 2 -> 3"));
            assertFalse(VceUpgradeService.isPending(target));
        }
    }

    @Test
    void lostPreCommitReportKeepsOriginalResetDisclosuresWithoutInventingCompletionCounts() throws Exception
    {
        Path source = Files.createDirectories(temporary.resolve("interrupted-alpha/data"));
        Path target = Files.createDirectories(temporary.resolve("interrupted-next")).resolve("data");
        Format2TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(source));
        var originalPlan = VceUpgradeService.inspect(source);
        try(AutoCloseable observer = Format62To63DatabaseMigration.observeActivation(phase -> {
            if(phase.equals("after-configuration-rename")) throw new SimulatedCrash();
        }))
        {
            assertThrows(SimulatedCrash.class, () -> VceUpgradeService.upgrade(source, target,
                MigrationContent.FULL, true, null, null));
        }

        var result = VceUpgradeService.resume(target, null, null);

        try(PortableDataRootLock ignored = result.lock())
        {
            assertEquals(originalPlan, result.migration().sourcePlan());
            assertTrue(result.migration().helperOutput().contains("Its exact completed-step counts are unavailable"));
            assertTrue(result.migration().helperOutput().contains("Original migration plan: format 2 ["));
            assertTrue(result.migration().helperOutput().contains("Planned RESET:"));
            assertFalse(VceUpgradeService.isPending(target));
        }
    }

    @Test
    void committedDatabaseCompletesWithWarningWhenOptionalReportFieldsAreMissing() throws Exception
    {
        Path source = Files.createDirectories(temporary.resolve("missing-report-old/data"));
        Path target = Files.createDirectories(temporary.resolve("missing-report-new")).resolve("data");
        Format62TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(source));
        interruptAfterCommit(source, target);
        Properties intent = intent(target);
        for(String key: java.util.List.of("sourcePlan", "sourceVersion", "sourceMarkerPresent", "helperReport", "fullAttemptStarted"))
            intent.remove(key);
        try(var output = Files.newOutputStream(intentPath(target))) { intent.store(output, "missing optional report fixture"); }

        var result = VceUpgradeService.resume(target, null, null);

        try(PortableDataRootLock ignored = result.lock())
        {
            assertTrue(result.migration().helperOutput().contains("WARNING: The original source plan is unavailable"));
            assertTrue(result.migration().helperOutput().contains("describes the current profile"));
            assertTrue(result.migration().helperOutput().contains("Exact completion counts from the earlier attempt are unavailable"));
            assertFalse(result.migration().helperOutput().contains("Original migration plan: format 64"));
            assertFalse(VceUpgradeService.isPending(target));
        }
    }

    @Test
    void installedSettingsPairFinishesWhenItsOptionalTemporaryReportIsMissing() throws Exception
    {
        Path source = Files.createDirectories(temporary.resolve("settings-report-old/data"));
        Path target = Files.createDirectories(temporary.resolve("settings-report-new")).resolve("data");
        Format64TestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(source));
        try(AutoCloseable observer = VceUpgradeService.observePhases(phase -> {
            if(phase.equals("VALIDATING")) throw new SimulatedCrash();
        }))
        {
            assertThrows(SimulatedCrash.class, () -> VceUpgradeService.upgrade(source, target,
                MigrationContent.CONFIGURATION_ONLY, true, null, null));
        }
        Path configuration = SdrTrunkDatabasePath.getDatabasePath(target);
        Path activity = ActivityDatabasePath.forConfiguration(configuration);
        String configurationIdentity = PortableFileIdentity.read(configuration);
        assertNotNull(configurationIdentity);
        String activityIdentity = PortableFileIdentity.read(activity);
        assertNotNull(activityIdentity);
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + configuration); var query = connection.createStatement())
        {
            assertEquals(1, query.executeUpdate("UPDATE alias SET name='Saved after installation' WHERE id=(SELECT min(id) FROM alias)"));
        }
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + activity); var query = connection.createStatement())
        {
            query.executeUpdate("INSERT INTO statistics_status(key,value,updated_at_ms) VALUES('after-install-proof','retained',1)");
        }
        Files.delete(Path.of(intent(target).getProperty("work")).resolve("report.txt"));

        var result = VceUpgradeService.resume(target, null, null);

        try(PortableDataRootLock ignored = result.lock())
        {
            assertEquals(configurationIdentity, PortableFileIdentity.read(configuration));
            assertEquals(activityIdentity, PortableFileIdentity.read(activity));
            SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(configuration);
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + configuration); var query = connection.createStatement();
                var rows = query.executeQuery("SELECT name FROM alias WHERE id=(SELECT min(id) FROM alias)"))
            { assertTrue(rows.next()); assertEquals("Saved after installation", rows.getString(1)); }
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + activity); var query = connection.createStatement())
            {
                try(var rows = query.executeQuery("SELECT count(*) FROM receiver_activity_event"))
                { assertTrue(rows.next()); assertEquals(0, rows.getLong(1)); }
                try(var rows = query.executeQuery("SELECT value FROM statistics_status WHERE key='after-install-proof'"))
                { assertTrue(rows.next()); assertEquals("retained", rows.getString(1)); }
            }
            assertTrue(result.migration().helperOutput().contains("WARNING: The earlier detailed settings update report is unavailable"));
            assertTrue(result.migration().helperOutput().contains("Exact completion counts are unavailable"));
            assertFalse(VceUpgradeService.isPending(target));
        }
    }

    private static void interruptAfterCommit(Path source, Path target) throws Exception
    {
        try(AutoCloseable observer = VceUpgradeService.observePhases(phase -> {
            if(phase.equals("COMMITTED")) throw new SimulatedCrash();
        }))
        {
            assertThrows(SimulatedCrash.class, () -> VceUpgradeService.upgrade(source, target,
                MigrationContent.FULL, true, null, null));
        }
    }

    private static Path intentPath(Path target)
    { return target.resolveSibling("." + target.getFileName() + ".vce-upgrade").resolve("intent.properties"); }

    private static Properties intent(Path target) throws Exception
    {
        Properties intent = new Properties();
        try(var input = Files.newInputStream(intentPath(target))) { intent.load(input); }
        return intent;
    }

    private static final class SimulatedCrash extends Error {}
}
