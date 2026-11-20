/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.ProgressHandler;
import static org.junit.jupiter.api.Assertions.*;

/** Owned constraint changes preserve their actual affected history instead of copying or counting it. */
class LegacyConstraintMigrationWorkTest
{
    private static final List<Integer> SOURCES = List.of(17, 21, 25, 49, 51, 54);
    private static final int ADDED_ROWS = 20_000;
    private static final int VM_INTERVAL = 100;
    private static final int VM_BUDGET = 100_000;
    private static final int VM_GROWTH_ALLOWANCE = 2_000;
    @TempDir Path directory;

    @TestFactory
    Stream<DynamicTest> ownedAdjacentWorkAndPhysicalPreservation()
    {
        return SOURCES.stream().map(source -> DynamicTest.dynamicTest(source + " -> " + (source + 1), () -> {
            Path tiny = fixture(source, "tiny");
            Path grown = directory.resolve(source + "/grown.sqlite");
            Files.copy(tiny, grown);
            // Compare history growth with identical compact planner metadata. ANALYZE itself can add more
            // bounded sqlite_stat4 samples; the separate analyzed case qualifies that metadata independently.
            try(var connection = open(grown)) { addHistory(connection, source, ADDED_ROWS); reserveAllocator(connection); }
            try(var small = open(tiny); var large = open(grown))
            {
                for(String table: affectedTables(source))
                    assertEquals(number(small, "SELECT count(*) FROM " + table) + ADDED_ROWS,
                        number(large, "SELECT count(*) FROM " + table), table + " must actually grow");
                for(String table: List.of("sqlite_stat1", "sqlite_stat4"))
                    if(number(small, "SELECT count(*) FROM sqlite_schema WHERE name='" + table + "'") > 0)
                        assertEquals(contentsOf(small, List.of(table)), contentsOf(large, List.of(table)),
                            "The history-growth comparison must retain identical planner metadata");
            }
            int tinyWork = migrateAndMeasure(tiny, source);
            int grownWork = migrateAndMeasure(grown, source);
            assertTrue(grownWork <= tinyWork + VM_GROWTH_ALLOWANCE,
                () -> "Affected-history growth increased owned migration VM work: " + tinyWork + " -> " + grownWork);
        }));
    }

    @TestFactory
    Stream<DynamicTest> fullyAnalyzedGrownHistoryStaysWithinAbsoluteWorkBudget()
    {
        return SOURCES.stream().map(source -> DynamicTest.dynamicTest(source + " -> " + (source + 1), () -> {
            Path database = fixture(source, "analyzed");
            try(var connection = open(database))
            { addHistory(connection, source, ADDED_ROWS); prepareStatistics(connection); }
            migrateAndMeasure(database, source);
        }));
    }

    @TestFactory
    Stream<DynamicTest> rollbackRetryReopenAndNewConstraintDomains()
    {
        return SOURCES.stream().map(source -> DynamicTest.dynamicTest(source + " -> " + (source + 1), () -> {
            Path database = fixture(source, "retry");
            byte[] original = Files.readAllBytes(database);
            try(var connection = open(database))
            {
                assertProspectiveDomain(connection, source, false);
                Map<String,String> rows = contents(connection);
                Map<String,String> trees = originalTrees(connection);
                beginOwned(connection);
                migrateAdjacent(connection, source);
                connection.rollback();
                assertEquals(source, DatabaseFormatCatalog.inspectForMigration(connection).version());
                assertEquals(rows, contents(connection));
                assertEquals(trees, originalTrees(connection));
            }
            assertArrayEquals(original, Files.readAllBytes(database), "Rollback must restore the original file");
            try(var connection = open(database))
            {
                beginOwned(connection);
                migrateAdjacent(connection, source);
                connection.commit();
            }
            try(var connection = open(database))
            {
                assertEquals(source + 1, DatabaseFormatCatalog.inspectForMigration(connection).version());
                assertEquals(DatabaseFormatCatalog.requireVersion(source + 1).fingerprint(),
                    SqliteSchemaValidator.fingerprint(connection));
                assertProspectiveDomain(connection, source, true);
                integrity(connection);
            }
        }));
    }

    private int migrateAndMeasure(Path database, int source) throws Exception
    {
        int work;
        try(var connection = open(database))
        {
            Map<String,String> rows = contents(connection);
            Map<String,String> trees = originalTrees(connection);
            beginOwned(connection);
            AtomicInteger callbacks = new AtomicInteger();
            ProgressHandler.setHandler(connection, VM_INTERVAL, new ProgressHandler()
            {
                @Override protected int progress()
                { return callbacks.incrementAndGet() * VM_INTERVAL > VM_BUDGET ? 1 : 0; }
            });
            try
            {
                var detected = DatabaseFormatCatalog.inspectForMigration(connection);
                assertEquals(source, detected.version());
                var plan = DatabaseMigrationChain.planInPlace(connection, detected);
                assertTrue(plan.steps().stream().anyMatch(step -> step.sourceVersion() == source));
                var report = migrateAdjacent(connection, source);
                // OP_Count can hide native history work from a VM budget. Owned reports describe preservation,
                // without discovering or claiming an exact count of retained rows. Source review guards the SQL.
                var preserved = report.steps().getFirst().effects().stream()
                    .filter(effect -> effect.kind() == DatabaseMigrationEffect.Kind.PRESERVE)
                    .toList();
                assertFalse(preserved.isEmpty());
                assertTrue(preserved.stream().allMatch(effect -> effect.affectedRows() == 0));
                assertEquals(source + 1, DatabaseFormatCatalog.inspectForMigration(connection).version());
                assertEquals(DatabaseFormatCatalog.requireVersion(source + 1).fingerprint(),
                    SqliteSchemaValidator.fingerprint(connection));
            }
            finally { ProgressHandler.clearHandler(connection); }
            work = callbacks.get() * VM_INTERVAL;
            assertTrue(work <= VM_BUDGET);
            connection.commit();
            assertEquals(rows, contentsOf(connection, rows.keySet().stream().toList()),
                "Every existing row, counter, statistic sample and allocator is preserved");
            assertEquals(trees, originalTreesOf(connection, trees.keySet().stream().toList()),
                "Original table/index root pages, index SQL and schema order are preserved");
        }
        try(var connection = open(database))
        {
            assertEquals(DatabaseFormatCatalog.requireVersion(source + 1).fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
            integrity(connection); // Deliberate retained-history qualification is outside the measured work.
        }
        return work;
    }

    private static DatabaseMigrationChain.MigrationReport migrateAdjacent(Connection connection, int source)
        throws Exception
    {
        var report = DatabaseMigrationChain.migrate(connection, DatabaseFormatCatalog.inspectForMigration(connection),
            false, ignored -> {}, ignored -> {}, source + 1);
        assertEquals(1, report.steps().size());
        assertEquals(source, report.steps().getFirst().sourceVersion());
        assertEquals(source + 1, report.target().version());
        return report;
    }

    private Path fixture(int source, String name) throws Exception
    {
        Path database = directory.resolve(source + "/" + name + ".sqlite");
        Files.createDirectories(database.getParent());
        Class.forName(getClass().getPackageName() + ".Format" + source + "TestDatabase")
            .getMethod("create", Path.class).invoke(null, database);
        try(var connection = open(database))
        {
            try(var statement = connection.createStatement())
            {
                if(number(connection, "SELECT count(*) FROM receiver_channel") == 0)
                    statement.executeUpdate("INSERT INTO receiver_channel(configuration_id,first_seen_ms,last_seen_ms) " +
                        "SELECT configuration_id,1000,1000 FROM configuration_channel ORDER BY id LIMIT 1");
            }
            addHistory(connection, source, 1);
            prepareStatistics(connection);
        }
        return database;
    }

    private static void addHistory(Connection connection, int source, int count) throws SQLException
    {
        try(var statement = connection.createStatement())
        {
            long channel = number(connection, "SELECT id FROM receiver_channel ORDER BY id LIMIT 1");
            long firstEvent = number(connection, "SELECT coalesce(max(id),0) FROM receiver_activity_event");
            String numbers = "WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<" + count + ") ";
            if(source != 21)
                statement.executeUpdate(numbers + "INSERT INTO receiver_activity_event(id,channel_id,observed_at_ms," +
                    "action_code,event_type_code) SELECT " + firstEvent + "+i," + channel + ",1000000+i," +
                    (source >= 51 ? "24,62" : "4,NULL") + " FROM n");
            if(source == 21)
            {
                long bucket = number(connection, "SELECT coalesce(max(bucket_start_ms),0) FROM conventional_call_identity_bucket");
                statement.executeUpdate(numbers + "INSERT INTO conventional_call_identity_bucket(" +
                    "channel_id,bucket_start_ms,identity_role_code,identity_kind_code,identity_id,call_count," +
                    "encrypted_count,recorded_count,streamed_count) SELECT " + channel + "," + bucket +
                    "+i*3600000,1,1,91,i,0,0,0 FROM n");
            }
            if(source == 25)
            {
                long frequency = number(connection, "SELECT coalesce(max(frequency_hz),100000000) FROM conventional_activity_summary");
                statement.executeUpdate(numbers + "INSERT INTO conventional_activity_summary(" +
                    "channel_id,frequency_hz,timeslot,first_seen_ms,last_seen_ms,call_count,last_event_type_code) " +
                    "SELECT " + channel + "," + frequency + "+i,-1,1000,2000,i,57 FROM n");
                if(count == 1)
                {
                    statement.executeUpdate("INSERT OR IGNORE INTO radio_system(system_key,protocol_code,address_domain_code," +
                        "p25_wacn,p25_system_id,first_seen_ms,last_seen_ms) VALUES('p25:abcde:321',1,0,703710,801,1000,2000)");
                    long radio = number(connection, "SELECT id FROM radio_system WHERE system_key='p25:abcde:321'");
                    long identity = number(connection, "SELECT coalesce(max(id),0)+1 FROM radio_system_identity_summary");
                    statement.executeUpdate("INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code," +
                        "home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms) VALUES(" + identity + "," + radio +
                        ",1,703710,801,1002,1000,2000)");
                    statement.executeUpdate("UPDATE receiver_activity_event SET radio_system_id=" + radio +
                        " WHERE id=" + (firstEvent + 1));
                    statement.executeUpdate("INSERT INTO activity_event_identity_member(event_id,radio_system_id," +
                        "identity_summary_id,identity_kind_code,observed_local_id) VALUES(" + (firstEvent + 1) + "," +
                        radio + "," + identity + ",1,1002)");
                }
            }
            if(source >= 51)
            {
                statement.executeUpdate(numbers + "INSERT INTO receiver_activity_tone_sequence(" +
                    "event_id,source_code,method_code,phase,call_started_at_ms,first_voice_sample_at_ms,completed_at_ms,tone_count) " +
                    "SELECT " + firstEvent + "+i,1,1,1,1000,1100,2000,1 FROM n");
                statement.executeUpdate(numbers + "INSERT INTO receiver_activity_tone(" +
                    "event_id,ordinal,frequency_1_millihz,frequency_2_millihz,codec_symbol,offset_ms,duration_ms,confidence_ppm) " +
                    "SELECT " + firstEvent + "+i,0,343300,NULL,NULL,125,800,900000 FROM n");
            }
        }
    }

    private static void prepareStatistics(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement())
        { statement.execute("ANALYZE"); }
        reserveAllocator(connection);
    }

    private static void reserveAllocator(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement())
        { statement.executeUpdate("UPDATE sqlite_sequence SET seq=seq+5000 WHERE name='receiver_activity_event'"); }
    }

    private static List<String> affectedTables(int source)
    {
        return switch(source)
        {
            case 17, 49 -> List.of("receiver_activity_event");
            case 21 -> List.of("conventional_call_identity_bucket");
            case 25 -> List.of("receiver_activity_event", "conventional_activity_summary");
            case 51, 54 -> List.of("receiver_activity_event", "receiver_activity_tone_sequence", "receiver_activity_tone");
            default -> throw new AssertionError(source);
        };
    }

    private static void assertProspectiveDomain(Connection connection, int source, boolean migrated) throws SQLException
    {
        String valid;
        String invalid;
        switch(source)
        {
            case 17 -> {
                valid = "UPDATE receiver_activity_event SET observed_site=4095 WHERE id=(SELECT min(id) FROM receiver_activity_event)";
                invalid = valid.replace("4095", "4096");
            }
            case 21 -> {
                valid = "UPDATE conventional_call_identity_bucket SET identity_id=0 WHERE bucket_start_ms=" +
                    "(SELECT max(bucket_start_ms) FROM conventional_call_identity_bucket) AND identity_role_code=1 AND identity_kind_code=1";
                invalid = valid.replace("identity_id=0", "identity_id=0,identity_kind_code=2");
            }
            case 25 -> {
                valid = "UPDATE receiver_activity_event SET action_code=21,event_type_code=61 WHERE id=(SELECT min(id) FROM receiver_activity_event)";
                invalid = valid.replace("61", "2147483647");
            }
            case 49 -> {
                valid = "UPDATE receiver_activity_event SET action_code=24,event_type_code=62 WHERE id=(SELECT min(id) FROM receiver_activity_event)";
                invalid = valid.replace("event_type_code=62", "event_type_code=2147483647");
            }
            case 51, 54 -> {
                valid = "UPDATE receiver_activity_tone_sequence SET method_code=" + (source == 51 ? 3 : 4) +
                    " WHERE source_code=1";
                invalid = "UPDATE receiver_activity_tone_sequence SET method_code=5 WHERE source_code=1";
            }
            default -> throw new AssertionError(source);
        }
        try(var statement = connection.createStatement())
        {
            if(migrated) assertTrue(statement.executeUpdate(valid) > 0);
            else assertThrows(SQLException.class, () -> statement.executeUpdate(valid));
            assertThrows(SQLException.class, () -> statement.executeUpdate(invalid));
            if(source == 25)
            {
                String summary = "UPDATE conventional_activity_summary SET last_event_type_code=61";
                if(migrated) assertTrue(statement.executeUpdate(summary) > 0);
                else assertThrows(SQLException.class, () -> statement.executeUpdate(summary));
                assertThrows(SQLException.class, () -> statement.executeUpdate(summary.replace("61", "2147483647")));
            }
            if(migrated && source >= 49)
                try(var rows = statement.executeQuery("PRAGMA table_list"))
                {
                    int strict = 0;
                    while(rows.next())
                        if(List.of("receiver_activity_tone_sequence", "receiver_activity_tone").contains(rows.getString("name")))
                        { assertEquals(1, rows.getInt("strict")); strict++; }
                    assertEquals(2, strict);
                }
        }
    }

    private static void beginOwned(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement())
        { statement.execute("PRAGMA foreign_keys=OFF"); statement.execute("PRAGMA ignore_check_constraints=ON"); }
        connection.setAutoCommit(false);
    }

    private static Map<String,String> contents(Connection connection) throws Exception
    {
        List<String> tables = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        { while(rows.next()) tables.add(rows.getString(1)); }
        return contentsOf(connection, tables);
    }

    private static Map<String,String> contentsOf(Connection connection, List<String> tables) throws Exception
    {
        Map<String,String> result = new LinkedHashMap<>();
        for(String table: tables)
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            int columns;
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM \"" + table + "\" LIMIT 0"))
            { columns = rows.getMetaData().getColumnCount(); }
            String order = java.util.stream.IntStream.rangeClosed(1, columns).mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(","));
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM \"" + table + "\" ORDER BY " + order))
            {
                while(rows.next())
                {
                    if(table.equals("database_metadata") && "database_format_version".equals(rows.getString(1))) continue;
                    for(int column = 1; column <= columns; column++)
                    {
                        Object value = rows.getObject(column);
                        byte[] bytes = value instanceof byte[] blob ? blob :
                            (value == null ? "NULL" : value.getClass().getName() + ":" + value).getBytes(StandardCharsets.UTF_8);
                        digest.update(Integer.toString(bytes.length).getBytes(StandardCharsets.US_ASCII));
                        digest.update((byte)':'); digest.update(bytes); digest.update((byte)';');
                    }
                    digest.update((byte)'\n');
                }
            }
            result.put(table, HexFormat.of().formatHex(digest.digest()));
        }
        return result;
    }

    private static Map<String,String> originalTrees(Connection connection) throws SQLException
    {
        return originalTreesOf(connection, null);
    }

    private static Map<String,String> originalTreesOf(Connection connection, List<String> names) throws SQLException
    {
        Map<String,String> result = new TreeMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT name,type,rootpage,rowid,sql FROM sqlite_schema WHERE type IN('table','index') ORDER BY name"))
        {
            while(rows.next())
            {
                String name = rows.getString(1);
                if(names == null || names.contains(name)) result.put(name,
                    rows.getString(2) + ":" + rows.getLong(3) + ":" + rows.getLong(4) +
                    (rows.getString(2).equals("index") ? ":" + rows.getString(5) + ":" +
                        physicalLayout(connection, "PRAGMA index_xinfo(\"" + name + "\")") :
                        ":" + physicalLayout(connection, "SELECT wr,strict FROM pragma_table_list WHERE schema='main' AND name='" + name + "'") +
                        ":" + physicalLayout(connection, "PRAGMA table_xinfo(\"" + name + "\")")));
            }
        }
        return result;
    }

    private static List<List<String>> physicalLayout(Connection connection, String query) throws SQLException
    {
        List<List<String>> result = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(query))
        {
            while(rows.next())
            {
                List<String> row = new ArrayList<>();
                for(int column = 1; column <= rows.getMetaData().getColumnCount(); column++)
                    row.add(String.valueOf(rows.getObject(column)));
                result.add(row);
            }
        }
        return result;
    }

    private static void integrity(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=OFF");
            try(var rows = statement.executeQuery("PRAGMA quick_check"))
            { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); assertFalse(rows.next()); }
            try(var rows = statement.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
        }
    }

    private static long number(Connection connection, String query) throws SQLException
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(query))
        { return rows.next() ? rows.getLong(1) : 0; }
    }

    private static Connection open(Path database) throws SQLException
    { return DriverManager.getConnection("jdbc:sqlite:" + database); }
}
