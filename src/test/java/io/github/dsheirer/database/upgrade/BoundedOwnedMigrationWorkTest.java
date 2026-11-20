/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.ProgressHandler;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Bounded-plan admission must constrain executed SQLite work as well as estimated workspace.
 * VM counts do not measure native work hidden inside OP_Count, integrity checks or SQL functions;
 * metadata-only migrations still require SQL/DDL review for those implicit scans.
 */
class BoundedOwnedMigrationWorkTest
{
    private static final int ADDED_EVENTS = 20_000;
    private static final int VM_INTERVAL = 100;
    private static final int VM_BUDGET = 100_000;
    private static final int VM_GROWTH_ALLOWANCE = 2_000;
    @TempDir Path directory;

    @TestFactory
    Stream<DynamicTest> boundedAdjacentSteps()
    {
        return DatabaseMigrationChain.steps().stream().filter(step ->
            ApplicationMigrationService.changesOnlyBoundedProfileData(new DatabaseMigrationChain.StepPreflight(
                step.id(), step.description(), step.sourceVersion(), step.targetVersion(), step.declaredEffects())))
            .map(step -> DynamicTest.dynamicTest(step.sourceVersion() + " -> " + step.targetVersion() + ": " + step.id(),
                () -> adjacentOwnedChainWorkDoesNotGrowWithRetainedEvents(step.sourceVersion(), step.targetVersion(), step.id())));
    }

    private void adjacentOwnedChainWorkDoesNotGrowWithRetainedEvents(int source, int target, String id) throws Exception
    {
        Path tiny = fixture(source, directory.resolve("format" + source + "/tiny/source.sqlite"));
        Path grown = copyPair(tiny, directory.resolve("format" + source + "/grown/source.sqlite"));
        growEvents(grown);
        assertArrayEquals(Files.readAllBytes(tiny), Files.readAllBytes(grown),
            "Only retained event history changes between the compared fixtures");
        int tinyWork = migrateAndMeasure(tiny, source, target, id, false);
        int grownWork = migrateAndMeasure(grown, source, target, id, false);
        assertNearlyConstant(tinyWork, grownWork);
    }

    @Test
    void currentPreferenceRepairAndChainAdmissionHaveHistoryIndependentWork() throws Exception
    {
        Path tiny = CurrentFormatTestDatabase.create(directory.resolve("tiny/current.sqlite"));
        try(var connection = open(tiny); var statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("INSERT OR REPLACE INTO application_settings(key,settings_json,updated_at_ms) " +
                "VALUES('portable_java_preferences_v1','{invalid',1)");
        }
        Path grown = copyPair(tiny, directory.resolve("grown/current.sqlite"));
        growEvents(grown);
        int current = DatabaseFormatCatalog.CURRENT_VERSION;
        int tinyWork = migrateAndMeasure(tiny, current, current, "repair-portable-preferences", true);
        int grownWork = migrateAndMeasure(grown, current, current, "repair-portable-preferences", true);
        assertNearlyConstant(tinyWork, grownWork);
    }

    private int migrateAndMeasure(Path database, int sourceVersion, int targetVersion, String id,
                                  boolean repairPreferences) throws Exception
    {
        int work;
        try(var connection = open(database); var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity");
            var statement = connection.createStatement())
        {
            attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString()); attach.execute();
            Map<String,Long> originalTrees = eventTrees(connection);
            long count = scalar(connection, "SELECT count(*) FROM activity.receiver_activity_event");
            statement.execute("PRAGMA foreign_keys=OFF");
            if(!repairPreferences) statement.execute("PRAGMA ignore_check_constraints=ON");
            connection.setAutoCommit(false);
            var callbacks = new AtomicInteger();
            ProgressHandler.setHandler(connection, VM_INTERVAL, new ProgressHandler()
            {
                @Override protected int progress()
                { return callbacks.incrementAndGet() * VM_INTERVAL > VM_BUDGET ? 1 : 0; }
            });
            try
            {
                // Include actual source admission, owned planning, adjacent execution and target admission.
                var source = DatabaseFormatCatalog.inspectForMigration(connection);
                assertEquals(sourceVersion, source.version());
                var plan = DatabaseMigrationChain.planInPlace(connection, source);
                assertTrue(plan.steps().stream().anyMatch(step -> step.id().equals(id)));
                if(repairPreferences)
                {
                    assertEquals(List.of(id), plan.steps().stream().map(DatabaseMigrationChain.StepPreflight::id).toList());
                    assertTrue(plan.steps().stream().allMatch(ApplicationMigrationService::changesOnlyBoundedProfileData));
                }
                var report = DatabaseMigrationChain.migrate(connection, source, false, ignored -> {}, ignored -> {}, targetVersion);
                assertEquals(repairPreferences ? List.of() : List.of(id), report.steps().stream().map(
                    DatabaseMigrationChain.StepReport::id).toList());
                // The owned wrapper repairs current preferences after its chain, before final exact admission.
                if(repairPreferences) assertTrue(ApplicationDatabaseMigrator.repairCurrentPortablePreferences(connection) > 0);
                assertEquals(targetVersion, DatabaseFormatCatalog.inspectForMigration(connection).version());
                assertEquals(DatabaseFormatCatalog.requireVersion(targetVersion).fingerprint(),
                    SqliteSchemaValidator.fingerprint(connection));
                assertEquals(ConfigurationHistoryDatabase.historyFingerprint(targetVersion),
                    SqliteSchemaValidator.fingerprint(connection, "activity"));
                if(repairPreferences) DatabaseFormatCatalog.requireCurrent(connection);
            }
            finally { ProgressHandler.clearHandler(connection); }
            work = callbacks.get() * VM_INTERVAL;
            statement.execute("PRAGMA ignore_check_constraints=OFF");
            connection.commit();
            assertEquals(originalTrees, eventTrees(connection));
            assertEquals(count, scalar(connection, "SELECT count(*) FROM activity.receiver_activity_event"));
        }
        // Deliberate whole-history qualification stays outside the migration's measured work.
        ConfigurationHistoryDatabase.validatePairIdentity(database);
        try(var connection = open(ActivityDatabasePath.forConfiguration(database)); var statement = connection.createStatement())
        {
            try(var rows = statement.executeQuery("PRAGMA quick_check"))
            { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); assertFalse(rows.next()); }
            try(var rows = statement.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
        }
        return work;
    }

    private static void assertNearlyConstant(int tinyWork, int grownWork)
    {
        assertTrue(tinyWork <= VM_BUDGET && grownWork <= VM_BUDGET);
        assertTrue(grownWork <= tinyWork + VM_GROWTH_ALLOWANCE,
            () -> "Retained-event growth increased bounded migration VM work: " + tinyWork + " -> " + grownWork);
    }

    private static Path fixture(int version, Path database) throws Exception
    {
        Files.createDirectories(database.getParent());
        String factory = Path.of(DatabaseFormatCatalog.requireVersion(version).fixtureResource()).getFileName()
            .toString().replace(".java", "");
        return (Path)Class.forName(CurrentFormatTestDatabase.class.getPackageName() + "." + factory)
            .getMethod("create", Path.class).invoke(null, database);
    }

    private static Path copyPair(Path source, Path target) throws Exception
    {
        Files.createDirectories(target.getParent());
        Files.copy(source, target);
        Files.copy(ActivityDatabasePath.forConfiguration(source), ActivityDatabasePath.forConfiguration(target));
        return target;
    }

    private static void growEvents(Path database) throws Exception
    {
        try(var connection = open(ActivityDatabasePath.forConfiguration(database)); var statement = connection.createStatement())
        {
            statement.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<" + ADDED_EVENTS + ") " +
                "INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code) " +
                "SELECT (SELECT channel_id FROM receiver_activity_event LIMIT 1),1000000+i,9 FROM n");
        }
    }

    private static Map<String,Long> eventTrees(Connection connection) throws SQLException
    {
        Map<String,Long> trees = new TreeMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT name,rootpage FROM activity.sqlite_schema WHERE tbl_name='receiver_activity_event' " +
                "AND type IN('table','index') ORDER BY name"))
        { while(rows.next()) trees.put(rows.getString(1), rows.getLong(2)); }
        return trees;
    }

    private static long scalar(Connection connection, String query) throws SQLException
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(query))
        { assertTrue(rows.next()); return rows.getLong(1); }
    }

    private static Connection open(Path database) throws SQLException
    { return DriverManager.getConnection("jdbc:sqlite:" + database); }
}
