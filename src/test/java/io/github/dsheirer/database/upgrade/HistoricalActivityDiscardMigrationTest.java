/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HistoricalActivityDiscardMigrationTest
{
    @TempDir Path directory;

    @Test
    void selectedOlderImportsDiscardDamagedHistoryAndPreserveConfigurationAndRecordings() throws Exception
    {
        for(int version: new int[]{24, 25, 26, 27, 28, 29, 30, 34, 35, 42})
            assertDoesNotThrow(() -> checkSelectedOlderImport(version), "Selected source format " + version);
    }

    private void checkSelectedOlderImport(int version) throws Exception
    {
        Path sourceRoot = Files.createDirectory(directory.resolve("source-" + version));
        Path source = SdrTrunkDatabasePath.getDatabasePath(sourceRoot);
        Files.createDirectories(source.getParent());
        Class.forName(getClass().getPackageName() + ".Format" + version + "TestDatabase")
            .getMethod("create", Path.class).invoke(null, source);
        Path recordingRoot = Files.createDirectories(sourceRoot.resolve("recordings-managed"));
        Path recording = recordingRoot.resolve("kept.wav");
        Path catalog = source.resolveSibling("managed-recordings.sqlite");
        byte[] recordedBytes = {1, 2, 3, 4};
        Files.write(recording, recordedBytes);
        try(var ignored = new ManagedRecordingCatalog(catalog, recordingRoot)) { }
        try(var connection = open(catalog); var statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes," +
                "protocol,call_type,voice_type) VALUES(1,1000,2000,1000,'kept.wav',4,1,1,1)");
            statement.executeUpdate("INSERT INTO recording_source(call_id,source_call_uuid) " +
                "VALUES(1,'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')");
        }
        byte[] catalogBytes = Files.readAllBytes(catalog);
        Configuration before;
        Map<Long,String> preferences = new LinkedHashMap<>();
        Map<Long,Long> preferenceRevisions = new LinkedHashMap<>();
        long discarded;
        try(var connection = open(source); var statement = connection.createStatement())
        {
            before = configuration(connection);
            try(var rows = statement.executeQuery("SELECT id,preferences_json,preferences_revision FROM web_user"))
            {
                while(rows.next())
                {
                    preferences.put(rows.getLong(1), rows.getString(2));
                    preferenceRevisions.put(rows.getLong(1), rows.getLong(3));
                }
            }
            statement.executeUpdate("INSERT INTO sqlite_sequence(name,seq) SELECT 'receiver_activity_event',-1 " +
                "WHERE NOT EXISTS(SELECT 1 FROM sqlite_sequence WHERE name='receiver_activity_event')");
            statement.executeUpdate("UPDATE sqlite_sequence SET seq=-1 WHERE name='receiver_activity_event'");
            discarded = CurrentDatabaseDerivedStateRepair.inspectHistorical(connection, version).resetRows();
            assertTrue(discarded > 0);
        }
        byte[] originalSource = Files.readAllBytes(source);
        Path targetRoot = Files.createDirectory(directory.resolve("target-" + version));
        var result = ApplicationMigrationServiceTestSupport.createInProcess().importPrevious(sourceRoot, targetRoot, null);
        assertTrue(result.helperOutput().contains("RESET " + CurrentDatabaseDerivedStateRepair.RESET_SUBJECT +
            ": " + discarded + " row(s)"), result::helperOutput);
        assertTrue(result.sourcePlan().steps().stream().flatMap(step -> step.effects().stream())
            .anyMatch(effect -> effect.kind() == DatabaseMigrationEffect.Kind.RESET &&
                effect.subject().equals(CurrentDatabaseDerivedStateRepair.RESET_SUBJECT)));
        try(var connection = open(SdrTrunkDatabasePath.getDatabasePath(targetRoot)))
        {
            assertEquals(before.rows(), readConfiguration(connection, before.columns(), before.filters()));
            long preferenceSteps = java.util.stream.IntStream.of(30, 34, 35, 42, 61).filter(sourceVersion -> sourceVersion >= version).count();
            try(var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT id,tier,primary_admin,preferences_json,preferences_revision FROM web_user"))
            {
                var json = new com.fasterxml.jackson.databind.ObjectMapper();
                while(rows.next())
                {
                    long id = rows.getLong(1);
                    assertEquals(rows.getInt(3) == 1 ? "ADMIN" : "USER", rows.getString(2));
                    var targetPreferences = json.readTree(rows.getString(4));
                    assertOriginalPreferences(json.readTree(preferences.get(id)), targetPreferences, true);
                    assertEquals(12, targetPreferences.path("version").asInt());
                    assertTrue(targetPreferences.path("guides").path("automatic_page_guides").asBoolean());
                    assertEquals(preferenceRevisions.get(id) + preferenceSteps, rows.getLong(5));
                }
            }
            assertDiscardedAndCurrent(connection);
        }
        assertArrayEquals(originalSource, Files.readAllBytes(source));
        assertArrayEquals(recordedBytes, Files.readAllBytes(recording));
        assertArrayEquals(catalogBytes, Files.readAllBytes(catalog));
    }

    @Test
    void emptyDamagedHistoryAndMissingAliasSummariesRollbackAndRetryWithoutReconstruction() throws Exception
    {
        Path source = Format25TestDatabase.create(directory.resolve("empty-history.sqlite"));
        try(var connection = open(source); var statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            List<String> present = new ArrayList<>();
            for(String table: CurrentDatabaseDerivedStateRepair.REPRODUCIBLE_TABLES)
                if(scalar(connection, "SELECT count(*) FROM sqlite_schema WHERE type='table' AND name='" + table + "'") > 0)
                    present.add(table);
            LegacyActivityReset.clear(connection, present);
            assertTrue(scalar(connection, "SELECT count(*) FROM alias") > 0);
            var before = configuration(connection);
            assertTrue(CurrentDatabaseDerivedStateRepair.inspectHistorical(connection, 25).requiresRepair());
            assertEquals(0, CurrentDatabaseDerivedStateRepair.inspectHistorical(connection, 25).resetRows());
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            assertDiscardedAndCurrent(connection);
            connection.rollback();
            assertEquals(25, DatabaseFormatCatalog.inspectForMigration(connection).version());
            assertEquals(0, scalar(connection, "SELECT count(*) FROM alias_activity_summary"));
            assertEquals(before.rows(), readConfiguration(connection, before.columns(), before.filters()));
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertDiscardedAndCurrent(connection);
            assertEquals(before.rows(), readConfiguration(connection, before.columns(), before.filters()));
        }
    }

    @Test
    void selectedImportsKeepSourceSpecificAnalogParityAndDiscardWrongDigitalProtocols() throws Exception
    {
        for(int version: new int[]{25, 61})
        {
            Path database = directory.resolve("protocol-parity-" + version + ".sqlite");
            Class.forName(getClass().getPackageName() + ".Format" + version + "TestDatabase")
                .getMethod("create", Path.class).invoke(null, database);
            try(var connection = open(database); var statement = connection.createStatement())
            {
                statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(9000,'Analog parity','NBFM')");
                statement.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) " +
                    "VALUES(9001,9000,'Analog parity','TALKGROUP','NBFM',1)");
                statement.executeUpdate("INSERT INTO alias_activity_summary(alias_id,alias_list_id,protocol_code," +
                    "metrics_state,updated_at_ms) VALUES(9001,9000," + (version < 51 ? "0,'unsupported'" : "10,'not_collected'") + ",1000)");
                //Frozen fixtures need not retain detailed events. Seed a valid observation explicitly so that
                //the discard and rollback proof always covers real history in both selected source formats.
                statement.execute("PRAGMA foreign_keys=ON");
                statement.executeUpdate("INSERT INTO receiver_channel(configuration_id,first_seen_ms,last_seen_ms) " +
                    "SELECT configuration_id,1000,2000 FROM configuration_channel " +
                    "WHERE NOT EXISTS(SELECT 1 FROM receiver_channel) ORDER BY id LIMIT 1");
                assertEquals(1, statement.executeUpdate("INSERT INTO receiver_activity_event(channel_id,observed_at_ms," +
                    "action_code,event_type_code) SELECT id,2000,12,57 FROM receiver_channel ORDER BY id LIMIT 1"));
                long retainedEventId = scalar(connection, "SELECT last_insert_rowid()");
                assertEquals(0, CurrentDatabaseDerivedStateRepair.inspectHistorical(connection, version).damagedTables(),
                    "The selected format's valid analog meaning must not discard healthy history");
                long originalEvents = scalar(connection, "SELECT count(*) FROM receiver_activity_event");
                assertTrue(originalEvents > 0);
                assertTrue(statement.executeUpdate("UPDATE alias_activity_summary SET protocol_code=3 WHERE protocol_code=1") > 0);
                var inspection = CurrentDatabaseDerivedStateRepair.inspectHistorical(connection, version);
                assertEquals(1, inspection.damagedTables(), "Wrong but locally valid digital codes must remain detectable");
                assertTrue(inspection.resetRows() > 0);
                statement.execute("PRAGMA foreign_keys=OFF");
                connection.setAutoCommit(false);
                var report = DatabaseMigrationChain.migrate(connection);
                assertEquals(inspection.resetRows(), report.steps().getFirst().effects().stream()
                    .filter(effect -> effect.subject().equals(CurrentDatabaseDerivedStateRepair.RESET_SUBJECT))
                    .findFirst().orElseThrow().affectedRows());
                assertDiscardedAndCurrent(connection);
                assertEquals(10, scalar(connection, "SELECT protocol_code FROM alias_activity_summary WHERE alias_id=9001"));
                connection.rollback();
                assertEquals(originalEvents, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
                assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE id=" + retainedEventId +
                    " AND observed_at_ms=2000 AND action_code=12 AND event_type_code=57"));
            }
        }
    }

    private static void assertDiscardedAndCurrent(Connection connection) throws Exception
    {
        assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
        assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
        assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
        assertEquals(0, scalar(connection, "SELECT count(*) FROM radio_system"));
        assertEquals(scalar(connection, "SELECT count(*) FROM alias"),
            scalar(connection, "SELECT count(*) FROM alias_activity_summary"));
        assertEquals(0, scalar(connection, "SELECT count(*) FROM alias_activity_summary WHERE logical_call_count IS NOT NULL"));
        assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA integrity_check"))
        { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
    }

    @Test
    void currentRepairCountsEmptyAliasCountersAndTheChainDoesNotRepeatTheirReset() throws Exception
    {
        Path database = CurrentFormatTestDatabase.create(directory.resolve("current-repair.sqlite"));
        try(var connection = open(database); var statement = connection.createStatement())
        {
            long aliases = scalar(connection, "SELECT count(*) FROM alias");
            assertTrue(aliases > 0);
            statement.executeUpdate("INSERT INTO sqlite_sequence(name,seq) SELECT 'receiver_activity_event',-1 " +
                "WHERE NOT EXISTS(SELECT 1 FROM sqlite_sequence WHERE name='receiver_activity_event')");
            statement.executeUpdate("UPDATE sqlite_sequence SET seq=-1 WHERE name='receiver_activity_event'");
            connection.setAutoCommit(false);
            var repaired = CurrentDatabaseDerivedStateRepair.repair(connection);
            assertEquals(aliases, CurrentDatabaseDerivedStateRepair.effects(repaired).stream()
                .filter(effect -> effect.subject().equals(CurrentDatabaseDerivedStateRepair.EMPTY_ALIAS_COUNTERS))
                .findFirst().orElseThrow().affectedRows());
            assertDiscardedAndCurrent(connection);
            long changes = scalar(connection, "SELECT total_changes()");
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
            assertEquals(changes, scalar(connection, "SELECT total_changes()"));
            connection.rollback();
        }
    }

    private static Configuration configuration(Connection connection) throws Exception
    {
        Map<String,List<String>> columns = new LinkedHashMap<>();
        Map<String,String> filters = new LinkedHashMap<>();
        for(String table: List.of("configuration_channel", "alias", "alias_list", "scan_list",
            "alias_scan_list_membership", "alias_broadcast_channel", "configuration_broadcast_stream", "web_user", "application_settings"))
        {
            List<String> fields = new ArrayList<>();
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_info(" + table + ")"))
            { while(rows.next()) fields.add(rows.getString("name")); }
            if(table.equals("web_user")) fields.removeAll(List.of("tier", "preferences_json", "preferences_revision", "updated_at_ms"));
            columns.put(table, List.copyOf(fields));
            if(table.equals("application_settings"))
            {
                List<String> keys = new ArrayList<>();
                try(var statement = connection.createStatement(); var rows = statement.executeQuery(
                    "SELECT quote(key) FROM application_settings WHERE key NOT IN ('setup_wizard','portable_java_preferences_v1')"))
                { while(rows.next()) keys.add(rows.getString(1)); }
                filters.put(table, " WHERE key IN (" + String.join(",", keys) + ")");
            }
        }
        return new Configuration(columns, filters, readConfiguration(connection, columns, filters));
    }

    private static void assertOriginalPreferences(com.fasterxml.jackson.databind.JsonNode source,
                                                   com.fasterxml.jackson.databind.JsonNode target, boolean root)
    {
        if(source.isObject())
        {
            source.properties().forEach(entry -> {
                if(!root || !entry.getKey().equals("version"))
                    assertOriginalPreferences(entry.getValue(), target.path(entry.getKey()), false);
            });
        }
        else assertEquals(source, target);
    }

    private static Map<String,List<String>> readConfiguration(Connection connection, Map<String,List<String>> columns,
                                                              Map<String,String> filters) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        for(var table: columns.entrySet())
        {
            List<String> values = new ArrayList<>();
            String fields = table.getValue().stream().map(column -> "\"" + column + "\"").collect(java.util.stream.Collectors.joining(","));
            try(var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT " + fields + " FROM " + table.getKey() + filters.getOrDefault(table.getKey(), "")))
            {
                while(rows.next())
                {
                    List<String> row = new ArrayList<>();
                    for(int index = 1; index <= table.getValue().size(); index++)
                    {
                        Object value = rows.getObject(index);
                        row.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value));
                    }
                    values.add(row.toString());
                }
            }
            values.sort(String::compareTo);
            result.put(table.getKey(), List.copyOf(values));
        }
        return result;
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
        { assertTrue(rows.next()); return rows.getLong(1); }
    }

    private static Connection open(Path database) throws Exception
    { return DriverManager.getConnection("jdbc:sqlite:" + database); }

    private record Configuration(Map<String,List<String>> columns, Map<String,String> filters, Map<String,List<String>> rows) {}
}
