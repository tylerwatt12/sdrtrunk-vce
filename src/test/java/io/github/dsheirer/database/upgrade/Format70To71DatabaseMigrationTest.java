/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
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

class Format70To71DatabaseMigrationTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String PORTABLE = "portable_java_preferences_v1";
    private static final String NODE = "user/io/github/dsheirer/preference/mp3";
    private static final String KEY = "audio.mp3.normalize.before.encode";
    @TempDir Path directory;

    @Test void populatedRetirementPreservesOtherRowsTimestampsBtreesAndSupportsUnchangedSourceRetry() throws Exception
    {
        Path configuration = Format70TestDatabase.create(directory.resolve("prior.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(configuration);
        String source = JSON.writeValueAsString(Map.of(NODE, Map.of(KEY, "true", "audio.mp3.setting", "CBR_16"),
            "unrelated/node", Map.of(KEY, "preserve", "keep", "yes")));
        Map<String,List<String>> beforeConfiguration, expectedCurrentConfiguration, beforeHistory, beforeObjects;
        try(var connection = open(configuration))
        {
            save(connection, source);
            beforeConfiguration = snapshot(connection, true);
            expectedCurrentConfiguration = snapshot(connection, true, true);
        }
        try(var connection = open(history)) { beforeHistory = snapshot(connection, false); beforeObjects = objects(connection); }
        byte[] configurationBytes = Files.readAllBytes(configuration), historyBytes = Files.readAllBytes(history);
        assertThrows(Exception.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(configuration));
        assertArrayEquals(configurationBytes, Files.readAllBytes(configuration));
        assertArrayEquals(historyBytes, Files.readAllBytes(history));
        var plan = ApplicationMigrationService.readStartupPlan(configuration);
        assertEquals(CurrentFormatTestDatabase.migrationStepIdsFrom(70),
            plan.steps().stream().map(DatabaseMigrationChain.StepPreflight::id).toList());
        assertTrue(plan.steps().getFirst().effects().stream().anyMatch(effect -> effect.kind() == DatabaseMigrationEffect.Kind.DROP));
        try(var connection = open(configuration); var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
        {
            attach.setString(1, history.toString()); attach.execute(); connection.setAutoCommit(false);
            var effects = new Format70To71DatabaseMigration().migrateAndReport(connection);
            assertEquals(1, effects.stream().filter(effect -> effect.kind() == DatabaseMigrationEffect.Kind.DROP)
                .mapToLong(DatabaseMigrationEffect::affectedRows).sum());
            DatabaseFormatCatalog.stamp(connection, 71);
            assertFalse(JSON.readTree(portable(connection)).path(NODE).has(KEY));
            connection.rollback();
            assertEquals(source, portable(connection));
            assertEquals(70, DatabaseFormatCatalog.inspect(connection).version());
        }
        assertArrayEquals(configurationBytes, Files.readAllBytes(configuration));
        assertArrayEquals(historyBytes, Files.readAllBytes(history));
        CurrentFormatTestDatabase.migratePairToVersion(configuration, 71);
        try(var connection = open(configuration))
        {
            assertEquals(71, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(DatabaseFormatCatalog.requireVersion(71).fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(beforeConfiguration, snapshot(connection, true));
            var expected = JSON.readTree(source);
            ((com.fasterxml.jackson.databind.node.ObjectNode)expected.path(NODE)).remove(KEY);
            assertEquals(expected, JSON.readTree(portable(connection)));
            assertEquals("902", scalar(connection, "SELECT updated_at_ms FROM application_settings WHERE key='" + PORTABLE + "'"));
            integrity(connection);
        }
        try(var connection = open(history))
        {
            assertEquals(71, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(beforeHistory, snapshot(connection, false)); assertEquals(beforeObjects, objects(connection));
            for(String table: List.of("p25_site_channel", "p25_site_channel_summary"))
                assertEquals("0", scalar(connection, "SELECT count(*) FROM " + table + " WHERE callsign IS NOT NULL AND trim(callsign)<>''"));
            integrity(connection);
        }
        ConfigurationHistoryDatabase.validatePairIdentity(configuration);
        CurrentFormatTestDatabase.validateCurrentPair(configuration);
        try(var connection = open(configuration)) { assertEquals(expectedCurrentConfiguration, snapshot(connection, true)); }
        try(var connection = open(history)) { assertEquals(beforeHistory, snapshot(connection, false)); }
        assertNoOp(configuration);
    }

    @Test void emptyNodeIsRemovedButAbsentKeyDoesNotRewriteItsDocument() throws Exception
    {
        for(boolean retired : new boolean[]{false, true})
        {
            Path database = Format70TestDatabase.create(directory.resolve("node-" + retired + ".sqlite"));
            String source = retired ? JSON.writeValueAsString(Map.of(NODE, Map.of(KEY, "false"),
                "unrelated/node", Map.of(KEY, "preserve"))) : "{ \"unrelated/node\" : { \"keep\" : \"yes\" } }";
            try(var connection = open(database))
            {
                save(connection, source);
                assertEquals(retired ? 1 : 0, new Format70To71DatabaseMigration().inspectSource(connection).stream()
                    .filter(effect -> effect.kind() == DatabaseMigrationEffect.Kind.DROP).mapToLong(DatabaseMigrationEffect::affectedRows).sum());
            }
            ApplicationDatabaseMigrator.migrateInPlace(database, null);
            try(var connection = open(database))
            {
                if(retired) { assertFalse(JSON.readTree(portable(connection)).has(NODE));
                    assertEquals("preserve", JSON.readTree(portable(connection)).path("unrelated/node").path(KEY).asText()); }
                else assertEquals(source, portable(connection));
                assertEquals("902", scalar(connection, "SELECT updated_at_ms FROM application_settings WHERE key='" + PORTABLE + "'"));
            }
        }
    }

    @Test void freshCurrentAndLegacyMonolithReachTargetAndStartupNeverRepairsRetiredState() throws Exception
    {
        Path fresh = directory.resolve("fresh.sqlite"); SdrTrunkDatabaseStartup.createGlobalDatabase(fresh); assertNoOp(fresh);
        Path legacy = Format62TestDatabase.create(directory.resolve("legacy.sqlite"));
        try(var connection = open(legacy)) { save(connection, JSON.writeValueAsString(Map.of(NODE, Map.of(KEY, "true")))); }
        ApplicationDatabaseMigrator.migrateInPlace(legacy, null); ConfigurationHistoryDatabase.validatePair(legacy);
        try(var connection = open(legacy)) { assertFalse(JSON.readTree(portable(connection)).has(NODE)); }
        assertNoOp(legacy);
        try(var connection = open(fresh)) { save(connection, JSON.writeValueAsString(Map.of(NODE, Map.of(KEY, "false")))); }
        byte[] before = Files.readAllBytes(fresh);
        assertThrows(Exception.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(fresh));
        assertArrayEquals(before, Files.readAllBytes(fresh));
        Path prior = Format70TestDatabase.create(directory.resolve("mixed.sqlite"));
        try(var connection = open(prior)) { DatabaseFormatCatalog.stamp(connection, 71); }
        assertThrows(Exception.class, () -> ConfigurationHistoryDatabase.validatePairIdentity(prior));
        try(var connection = open(ActivityDatabasePath.forConfiguration(prior))) { assertEquals(70, DatabaseFormatCatalog.inspect(connection).version()); }
    }

    @Test void retirementWorkspaceDoesNotScaleWithRetainedActivity() throws Exception
    {
        Path configuration = Format70TestDatabase.create(directory.resolve("workspace.sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(configuration);
        var full = ApplicationMigrationService.readStartupPlan(configuration);
        var retirement = new DatabaseMigrationChain.PreflightReport(full.source(), DatabaseFormatCatalog.requireVersion(71),
            full.steps().stream().filter(step -> step.targetVersion() <= 71).toList());
        long workspace = ApplicationMigrationService.requiredOwnedMigrationWorkspace(configuration, MigrationContent.FULL, retirement);
        assertEquals(2 * Files.size(configuration) + 64L * 1024 * 1024, workspace);
        long fullWorkspace = ApplicationMigrationService.requiredOwnedMigrationWorkspace(configuration, MigrationContent.FULL);
        assertEquals(2 * (Files.size(configuration) + Files.size(history)), fullWorkspace,
            "The later BSI conversion keeps the conservative whole-pair allowance");
        try(var connection = open(history); var statement = connection.createStatement())
        {
            statement.executeUpdate("WITH RECURSIVE n(value) AS (SELECT 1 UNION ALL SELECT value+1 FROM n WHERE value<5000) " +
                "INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code,event_type_code,encryption_state) " +
                "SELECT (SELECT channel_id FROM receiver_activity_event LIMIT 1),1000000+value,2,7,0 FROM n");
        }
        assertEquals(workspace, ApplicationMigrationService.requiredOwnedMigrationWorkspace(configuration, MigrationContent.FULL, retirement));
        assertEquals(2 * (Files.size(configuration) + Files.size(history)),
            ApplicationMigrationService.requiredOwnedMigrationWorkspace(configuration, MigrationContent.FULL));
        assertTrue(ApplicationMigrationService.requiredOwnedMigrationWorkspace(configuration, MigrationContent.FULL) > fullWorkspace);
    }

    private static void assertNoOp(Path configuration) throws Exception
    {
        SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(configuration);
        Path history = ActivityDatabasePath.forConfiguration(configuration);
        byte[] before = Files.readAllBytes(configuration), historyBefore = Files.readAllBytes(history);
        assertFalse(ApplicationMigrationService.readStartupPlan(configuration).requiresMigration());
        ApplicationDatabaseMigrator.migrateInPlace(configuration, null);
        assertArrayEquals(before, Files.readAllBytes(configuration)); assertArrayEquals(historyBefore, Files.readAllBytes(history));
    }
    private static void save(Connection connection, String json) throws Exception
    {
        try(var statement = connection.prepareStatement("INSERT OR REPLACE INTO application_settings(key,settings_json,updated_at_ms) VALUES(?,?,902)"))
        { statement.setString(1, PORTABLE); statement.setString(2, json); statement.executeUpdate(); }
    }
    private static String portable(Connection connection) throws Exception
    { return scalar(connection, "SELECT settings_json FROM application_settings WHERE key='" + PORTABLE + "'"); }
    private static Connection open(Path database) throws Exception { return DriverManager.getConnection("jdbc:sqlite:" + database); }
    private static String scalar(Connection connection, String sql) throws Exception
    { try(var query = connection.createStatement(); var row = query.executeQuery(sql)) { assertTrue(row.next()); return row.getString(1); } }
    private static void integrity(Connection connection) throws Exception
    {
        assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        try(var query = connection.createStatement(); var violations = query.executeQuery("PRAGMA foreign_key_check")) { assertFalse(violations.next()); }
    }
    private static Map<String,List<String>> snapshot(Connection connection, boolean omitPortable) throws Exception
    { return snapshot(connection, omitPortable, false); }
    private static Map<String,List<String>> snapshot(Connection connection, boolean omitPortable, boolean upgradedPreferences) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>(); List<String> tables = new ArrayList<>();
        try(var query = connection.createStatement(); var values = query.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        { while(values.next()) tables.add(values.getString(1)); }
        for(String table: tables)
        {
            if(table.equals("p25_site_bsi"))
            {
                // No callsign rows existed in this frozen pair, so the later BSI owner must remain empty.
                assertEquals("0", scalar(connection, "SELECT count(*) FROM p25_site_bsi"));
                continue;
            }
            boolean excludeAddedKeyDefault = table.equals("application_settings") &&
                DatabaseFormatCatalog.inspect(connection).version() >= 76;
            if(excludeAddedKeyDefault)
            {
                try(var query = connection.createStatement(); var value = query.executeQuery(
                    "SELECT settings_json,updated_at_ms,typeof(updated_at_ms) FROM application_settings " +
                        "WHERE key='voice.encryption_keys'"))
                {
                    assertTrue(value.next());
                    assertEquals("[]", value.getString(1));
                    assertTrue(value.getLong(2) > 0);
                    assertEquals("integer", value.getString(3));
                    assertFalse(value.next());
                }
            }
            List<String> rows = new ArrayList<>();
            String where = table.equals("database_metadata") ? " WHERE key<>'database_format_version'" :
                omitPortable && table.equals("application_settings") ? " WHERE key<>'" + PORTABLE + "'" : "";
            if(excludeAddedKeyDefault) where += (where.isEmpty() ? " WHERE " : " AND ") + "key<>'voice.encryption_keys'";
            try(var query = connection.createStatement(); var values = query.executeQuery("SELECT * FROM " + table + where))
            {
                while(values.next())
                {
                    StringBuilder row = new StringBuilder();
                    for(int index = 1; index <= values.getMetaData().getColumnCount(); index++)
                    {
                        if(CurrentFormatTestDatabase.addedHistoryColumn(table, values, index)) continue;
                        Object value = values.getObject(index);
                        if(upgradedPreferences && table.equals("web_user"))
                        {
                            String column = values.getMetaData().getColumnName(index);
                            if(column.equals("preferences_json")) value = Format72WebUserPreferencesCodec.migrateFromFormat71((String)value);
                            if(column.equals("preferences_revision")) value = ((Number)value).longValue() + 1;
                        }
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
    private static Map<String,List<String>> objects(Connection connection) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        try(var query = connection.createStatement(); var values = query.executeQuery("SELECT name,type,rootpage,coalesce(sql,'') FROM sqlite_schema ORDER BY name"))
        { while(values.next()) result.put(values.getString(1), List.of(values.getString(2), values.getString(3), values.getString(4))); }
        return result;
    }
}
