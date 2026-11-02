/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format69To70DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void preservesLegacyDuplicatesChildrenCountersAndBtreesAndRollsBackBothMarkersBeforeRetry() throws Exception
    {
        Path configuration = Format69TestDatabase.create(directory.resolve("prior.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(configuration);
        Map<String,List<String>> beforeHistory, beforeConfiguration, btrees;
        try(var connection = open(history); var statement = connection.createStatement())
        {
            // Identical-looking historical observations still have no exact parent-call association.
            for(int action: List.of(4, 2, 12))
                statement.executeUpdate("INSERT INTO receiver_activity_event(id,channel_id,observed_at_ms," +
                    "action_code,event_type_code,encryption_state,duration_ms,source_call_uuid) SELECT " +
                    (900000000000L + action) + ",channel_id,1000000," + action + ",7,1," +
                    (action == 4 ? "1234,'9a220f1a-7ddc-4182-8dd0-fb60506aeb51'" : "NULL,NULL") +
                    " FROM receiver_activity_event LIMIT 1");
            statement.executeUpdate("INSERT INTO receiver_activity_event(id,channel_id,observed_at_ms," +
                "action_code,event_type_code,encryption_state) SELECT 900000000025,channel_id,1000000,2,25,0 " +
                "FROM receiver_activity_event LIMIT 1");
            statement.executeUpdate("UPDATE sqlite_sequence SET seq=900000000100 WHERE name='receiver_activity_event'");
            beforeHistory = snapshot(connection); btrees = schemaObjects(connection);
        }
        try(var connection = open(configuration)) { beforeConfiguration = snapshot(connection); }
        byte[] configurationBytes = Files.readAllBytes(configuration), historyBytes = Files.readAllBytes(history);
        assertThrows(Exception.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(configuration));
        assertArrayEquals(configurationBytes, Files.readAllBytes(configuration));
        assertArrayEquals(historyBytes, Files.readAllBytes(history));
        var plan = ApplicationMigrationService.readStartupPlan(configuration);
        assertEquals(List.of("call-owned-voice-activity-v1"), plan.steps().stream().map(
            DatabaseMigrationChain.StepPreflight::id).toList());
        assertTrue(plan.steps().getFirst().effects().stream().allMatch(effect -> effect.affectedRows() >= 0),
            "Preflight declares a prospective policy without counting retained history");
        assertThrows(IllegalStateException.class, () -> ApplicationDatabaseMigrator.migrateInPlace(configuration, phase -> {
            if(phase.equals("Validating the updated schema and configuration")) throw new IllegalStateException("injected");
        }));
        assertPreserved(configuration, beforeConfiguration, 69); assertPreserved(history, beforeHistory, 69);
        ApplicationDatabaseMigrator.migrateInPlace(configuration, null);
        assertPreserved(configuration, beforeConfiguration, 70); assertPreserved(history, beforeHistory, 70);
        try(var connection = open(history))
        {
            assertEquals(btrees, schemaObjects(connection));
            assertEquals(ConfigurationHistoryDatabase.HISTORY_FINGERPRINT, SqliteSchemaValidator.fingerprint(connection));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            try(var query = connection.createStatement(); var violations = query.executeQuery("PRAGMA foreign_key_check"))
            { assertFalse(violations.next()); }
        }
        ConfigurationHistoryDatabase.validatePair(configuration);
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(configuration);
        byte[] currentConfiguration = Files.readAllBytes(configuration), currentHistory = Files.readAllBytes(history);
        assertFalse(ApplicationMigrationService.readStartupPlan(configuration).requiresMigration());
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(configuration);
        assertArrayEquals(currentConfiguration, Files.readAllBytes(configuration));
        assertArrayEquals(currentHistory, Files.readAllBytes(history));
    }

    @Test void rejectsMixedSemanticVersionsAndPreservesTheFrozen69Fixture() throws Exception
    {
        Path configuration = Format69TestDatabase.create(directory.resolve("mixed.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(configuration);
        try(var connection = open(configuration)) { DatabaseFormatCatalog.stamp(connection, 70); }
        assertThrows(Exception.class, () -> ConfigurationHistoryDatabase.validatePairIdentity(configuration));
        try(var connection = open(history))
        {
            assertEquals(69, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(ConfigurationHistoryDatabase.historyFingerprint(69), ConfigurationHistoryDatabase.historyFingerprint(70));
        }
        Path frozen = Format69TestDatabase.create(directory.resolve("frozen.sqlite"));
        ConfigurationHistoryDatabase.validatePairIdentity(frozen);
        try(var connection = open(frozen))
        {
            assertEquals(69, DatabaseFormatCatalog.inspect(connection).version());
            assertThrows(Exception.class, () -> DatabaseFormatCatalog.requireCurrent(connection));
        }
    }

    @Test void semanticWorkspaceDoesNotScaleWithRetainedActivity() throws Exception
    {
        Path configuration = Format69TestDatabase.create(directory.resolve("workspace.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(configuration);
        long workspace = ApplicationMigrationService.requiredOwnedMigrationWorkspace(configuration, MigrationContent.FULL);
        assertEquals(2 * Files.size(configuration) + 64L * 1024 * 1024, workspace);
        try(var connection = open(history); var statement = connection.createStatement())
        {
            statement.executeUpdate("WITH RECURSIVE n(value) AS (SELECT 1 UNION ALL SELECT value+1 FROM n WHERE value<5000) " +
                "INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code,event_type_code,encryption_state) " +
                "SELECT (SELECT channel_id FROM receiver_activity_event LIMIT 1),1000000+value,2,7,0 FROM n");
        }
        assertEquals(workspace, ApplicationMigrationService.requiredOwnedMigrationWorkspace(configuration, MigrationContent.FULL));
    }

    @Test void legacyMonolithAdvancesItsAttachedConfigurationAndHistoryTogether() throws Exception
    {
        Path configuration = Format62TestDatabase.create(directory.resolve("legacy.sqlite"));
        ApplicationDatabaseMigrator.migrateInPlace(configuration, null);
        ConfigurationHistoryDatabase.validatePair(configuration);
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(configuration);
        for(Path database: List.of(configuration, ActivityDatabasePath.forConfiguration(configuration)))
            try(var connection = open(database))
            {
                assertEquals(70, DatabaseFormatCatalog.requireCurrent(connection).version());
                assertThrows(java.sql.SQLException.class, () -> SqliteSchemaValidator.fingerprint(connection,
                    "unexpected_companion"));
                assertThrows(java.sql.SQLException.class, () ->
                    io.github.dsheirer.database.ConfigurationHistorySchema.metadata(connection,
                        "unexpected_companion", DatabaseFormatCatalog.FORMAT_VERSION_KEY));
            }
    }

    private static void assertPreserved(Path database, Map<String,List<String>> expected, int version) throws Exception
    {
        try(var connection = open(database))
        { assertEquals(expected, snapshot(connection)); assertEquals(version, DatabaseFormatCatalog.inspect(connection).version()); }
    }

    /** Complete fixture contents, including encryption, children, planner statistics and allocators. */
    private static Map<String,List<String>> snapshot(Connection connection) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>(); List<String> tables = new ArrayList<>();
        try(var query = connection.createStatement(); var values = query.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        { while(values.next()) tables.add(values.getString(1)); }
        for(String table: tables)
        {
            List<String> rows = new ArrayList<>();
            String where = table.equals("database_metadata") ? " WHERE key<>'database_format_version'" : "";
            try(var query = connection.createStatement(); var values = query.executeQuery("SELECT * FROM " + table + where))
            {
                while(values.next())
                {
                    StringBuilder row = new StringBuilder();
                    for(int index = 1; index <= values.getMetaData().getColumnCount(); index++)
                    {
                        Object value = values.getObject(index);
                        String text = value instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : String.valueOf(value);
                        row.append(text.length()).append(':').append(text).append('|');
                    }
                    rows.add(row.toString());
                }
            }
            rows.sort(String::compareTo); result.put(table, rows);
        }
        return result;
    }
    private static Map<String,List<String>> schemaObjects(Connection connection) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        try(var query = connection.createStatement(); var values = query.executeQuery(
            "SELECT name,type,rootpage,coalesce(sql,'') FROM sqlite_schema ORDER BY name"))
        {
            while(values.next()) result.put(values.getString(1), List.of(values.getString(2), values.getString(3), values.getString(4)));
        }
        return result;
    }
    private static Connection open(Path database) throws Exception
    { return DriverManager.getConnection("jdbc:sqlite:" + database); }
    private static String scalar(Connection connection, String sql) throws Exception
    {
        try(var query = connection.createStatement(); var row = query.executeQuery(sql))
        { assertTrue(row.next()); return row.getString(1); }
    }
}
