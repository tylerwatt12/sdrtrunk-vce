/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SourceCallSchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.ActivityMessageSchemaMigration;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.ProgressHandler;
import static org.junit.jupiter.api.Assertions.*;

class LegacyNullableColumnMigrationWorkTest
{
    private static final int ROWS = 20_000;
    private static final String UUID = "8a220f1a-7ddc-4182-8dd0-fb60506aeb51";
    private static final long SYSTEM = 800000000001L;
    @TempDir Path directory;

    @TestFactory Stream<DynamicTest> actualAdjacentChainsPreserveHistoryAndNativeColumnContracts()
    {
        return Stream.of(31, 57, 58).map(source -> DynamicTest.dynamicTest(source + " -> " + (source + 1), () -> {
            Path tiny = directory.resolve(source + "/tiny.sqlite");
            Files.createDirectories(tiny.getParent());
            Class.forName(getClass().getPackageName() + ".Format" + source + "TestDatabase")
                .getMethod("create", Path.class).invoke(null, tiny);
            try(var connection = open(tiny)) { seed(connection, source, 1); execute(connection, "ANALYZE"); }
            Path grown = tiny.resolveSibling("grown.sqlite"); Files.copy(tiny, grown);
            try(var connection = open(grown)) { seed(connection, source, ROWS); }
            try(var small = open(tiny); var large = open(grown))
            {
                for(String table: additions(source).keySet())
                    assertEquals(number(small, "SELECT count(*) FROM " + table) + ROWS,
                        number(large, "SELECT count(*) FROM " + table), table);
                assertEquals(statistics(small, additions(source).keySet().stream().toList()),
                    statistics(large, additions(source).keySet().stream().toList()));
            }
            int budget = source == 58 ? 100_000 : 500_000;
            Path old = tiny.resolveSibling("native-checked.sqlite"); Files.copy(grown, old);
            try(var connection = open(old))
            {
                execute(connection, "PRAGMA ignore_check_constraints=ON");
                installBudget(connection, budget);
                try
                {
                    SQLException failure = assertThrows(SQLException.class, () -> {
                        for(var table: additions(source).entrySet())
                            for(String checked: table.getValue()) execute(connection,
                                "ALTER TABLE " + table.getKey() + " ADD COLUMN " + checked);
                    });
                    assertEquals(9, failure.getErrorCode(), "The former checked additions must hit the VM cap");
                }
                finally { ProgressHandler.clearHandler(connection); }
            }
            int tinyWork = migrate(tiny, source, budget, true);
            int grownWork = migrate(grown, source, budget, false);
            if(source == 58) assertTrue(grownWork <= tinyWork + 2_000, tinyWork + " -> " + grownWork);
            else assertTrue(grownWork > tinyWork,
                "The real native partial-index build remains history-dependent");
        }));
    }

    @Test void populatedDurationAndUnknownTableRefuseUnsafeBoundariesWithoutMutation() throws Exception
    {
        Path database = Format58TestDatabase.create(directory.resolve("unsafe.sqlite"));
        byte[] before = Files.readAllBytes(database);
        try(var connection = open(database))
        {
            assertThrows(SQLException.class, () -> ReceiverActivitySchema.addCallDuration(connection));
            assertEquals(58, DatabaseFormatCatalog.inspect(connection).version());
            execute(connection, "PRAGMA foreign_keys=ON"); connection.setAutoCommit(false);
            assertThrows(SQLException.class, () -> ReceiverActivitySchema.addCallDuration(connection));
            connection.rollback();
            assertThrows(SQLException.class, () -> ActivityMessageSchemaMigration.appendNullableColumns(connection,
                "unrelated", "", "", List.of("x INTEGER"), List.of("x INTEGER")));
        }
        assertArrayEquals(before, Files.readAllBytes(database));
        Path strict = Format51TestDatabase.create(directory.resolve("strict.sqlite"));
        byte[] strictBefore = Files.readAllBytes(strict);
        try(var connection = open(strict); var proof = DriverManager.getConnection("jdbc:sqlite::memory:"))
        {
            String prior = definition(connection, "receiver_activity_tone_sequence");
            String checked = "probe INTEGER CHECK(probe IS NULL OR probe>=0)";
            int constraint = prior.lastIndexOf("CHECK(");
            assertTrue(constraint > 0);
            String target = prior.substring(0, constraint) + checked + ",\n" + prior.substring(constraint);
            execute(proof, target); // The proposed append parses; refusal must be the retained STRICT cost guard.
            begin(connection);
            SQLException failure = assertThrows(SQLException.class, () ->
                ActivityMessageSchemaMigration.appendNullableColumns(connection, "receiver_activity_tone_sequence",
                    prior, target, List.of(checked), List.of("probe INTEGER")));
            assertTrue(failure.getMessage().contains("STRICT"));
            assertEquals(prior, definition(connection, "receiver_activity_tone_sequence"));
            assertEquals(0, number(connection, "PRAGMA writable_schema"));
            connection.rollback();
        }
        assertArrayEquals(strictBefore, Files.readAllBytes(strict));
    }

    private int migrate(Path database, int source, int budget, boolean exerciseFutureWrites) throws Exception
    {
        int work;
        var additions = additions(source);
        try(var connection = open(database); var nativeSchema = DriverManager.getConnection("jdbc:sqlite::memory:"))
        {
            Map<String,List<String>> oldColumns = new LinkedHashMap<>();
            Map<String,String> oldRows = new LinkedHashMap<>();
            for(var table: additions.entrySet())
            {
                oldColumns.put(table.getKey(), columnNames(connection, table.getKey()));
                oldRows.put(table.getKey(), tableHash(connection, table.getKey(), oldColumns.get(table.getKey())));
                execute(nativeSchema, definition(connection, table.getKey()));
                for(String checked: table.getValue()) execute(nativeSchema,
                    "ALTER TABLE " + table.getKey() + " ADD COLUMN " + checked);
            }
            var tables = oldColumns.keySet().stream().toList();
            Map<String,String> trees = originalRoots(connection, tables, null, oldColumns);
            String statistics = statistics(connection, tables);
            String allocator = queryHash(connection, "SELECT * FROM sqlite_sequence WHERE name IN(" + names(tables) + ")");
            byte[] original = Files.readAllBytes(database);
            begin(connection); adjacent(connection, source); connection.rollback();
            assertEquals(source, DatabaseFormatCatalog.inspectForMigration(connection).version());
            for(String table: tables) assertEquals(oldRows.get(table), tableHash(connection, table, oldColumns.get(table)));
            assertEquals(trees, originalRoots(connection, tables, null, oldColumns));
            assertArrayEquals(original, Files.readAllBytes(database));
            var callbacks = installBudget(connection, budget);
            try
            {
                var detected = DatabaseFormatCatalog.inspectForMigration(connection);
                assertEquals(source, detected.version());
                assertTrue(DatabaseMigrationChain.planInPlace(connection, detected).steps().stream()
                    .anyMatch(step -> step.sourceVersion() == source));
                adjacent(connection, source);
                assertEquals(source + 1, DatabaseFormatCatalog.inspectForMigration(connection).version());
            }
            finally { ProgressHandler.clearHandler(connection); }
            work = callbacks.get() * 100; connection.commit();
            assertEquals(DatabaseFormatCatalog.requireVersion(source + 1).fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(trees, originalRoots(connection, tables, trees.keySet().stream().toList(), oldColumns));
            assertEquals(statistics, statistics(connection, tables));
            assertEquals(allocator, queryHash(connection, "SELECT * FROM sqlite_sequence WHERE name IN(" + names(tables) + ")"));
            for(String table: tables)
            {
                assertEquals(oldRows.get(table), tableHash(connection, table, oldColumns.get(table)));
                SqliteSchemaValidator.validateDefinitions(connection, List.of(new SqliteSchemaValidator.Definition(
                    "table", table, definition(nativeSchema, table))));
                assertEquals(layout(nativeSchema, table), layout(connection, table));
                for(String checked: additions.get(table))
                    assertEquals(0, number(connection, "SELECT count(*) FROM " + table + " WHERE " +
                        checked.substring(0, checked.indexOf(' ')) + " IS NOT NULL"));
            }
        }
        try(var connection = open(database))
        {
            execute(connection, "PRAGMA foreign_keys=ON");
            if(exerciseFutureWrites) futureWrites(connection, source);
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA quick_check"))
            { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); assertFalse(rows.next()); }
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_check"))
            { assertFalse(rows.next()); }
        }
        return work;
    }

    private static void adjacent(Connection connection, int source) throws SQLException
    {
        var report = DatabaseMigrationChain.migrate(connection, DatabaseFormatCatalog.inspectForMigration(connection),
            false, ignored -> {}, ignored -> {}, source + 1);
        assertEquals(1, report.steps().size());
        assertEquals(source, report.steps().getFirst().sourceVersion());
        assertEquals(source + 1, report.target().version());
        assertTrue(report.steps().getFirst().effects().stream().allMatch(effect -> effect.affectedRows() >= 0));
    }

    private static Map<String,List<String>> additions(int source)
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        if(source == 31)
        {
            result.put("receiver_activity_event", List.of(working("source_observed_working_id"), working("target_observed_working_id")));
            result.put("p25_site_call_identity_bucket", List.of(working("observed_working_id")));
            result.put("trunked_radio_affiliation", List.of(working("radio_observed_working_id")));
            result.put("trunked_radio_channel_presence", List.of(working("observed_working_id")));
            result.put("trunked_radio_channel_presence_clear", List.of(working("observed_working_id")));
            result.put("radio_system_identity_summary", List.of("p25_subscriber_identity_id INTEGER " +
                "REFERENCES p25_subscriber_identity(id) ON DELETE RESTRICT CHECK(p25_subscriber_identity_id IS NULL OR " +
                "(typeof(p25_subscriber_identity_id) = 'integer' AND p25_subscriber_identity_id > 0 " +
                "AND identity_kind_code = 2 AND home_wacn >= 0 AND home_system_id >= 0))"));
        }
        else if(source == 57)
        {
            result.put("receiver_activity_event", List.of(SourceCallSchema.COLUMN_DEFINITION));
            result.put("topic_alert_message", List.of(SourceCallSchema.COLUMN_DEFINITION));
        }
        else result.put("receiver_activity_event", List.of("duration_ms INTEGER CHECK(duration_ms IS NULL OR " +
            "(typeof(duration_ms) = 'integer' AND duration_ms >= 0 AND action_code = 4))"));
        return result;
    }

    private static String working(String name)
    { return name + " INTEGER CHECK(" + name + " IS NULL OR (typeof(" + name + ") = 'integer' AND " + name + " BETWEEN 1 AND 16777212))"; }

    private static void seed(Connection connection, int source, int count) throws SQLException
    {
        String n = "WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<" + count + ") ";
        long firstEvent = number(connection, "SELECT coalesce(max(id),0) FROM receiver_activity_event");
        if(source == 31)
        {
            execute(connection, "INSERT OR IGNORE INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms) " +
                "VALUES(" + SYSTEM + ",'p25:f1234:abc',1,0,987700,2748,1000,2000)");
            execute(connection, "INSERT OR IGNORE INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,radio_system_id,radio_system_assigned_at_ms) " +
                "VALUES(" + SYSTEM + ",'11111111-2222-4333-8444-555555555555',1000,2000," + SYSTEM + ",1000)");
            execute(connection, "INSERT OR IGNORE INTO p25_learned_site(learned_site_id,radio_system_id,rfss,site,first_seen_ms,last_seen_ms) " +
                "VALUES(" + SYSTEM + "," + SYSTEM + ",1,1,1000,2000)");
            long firstRadio = number(connection, "SELECT coalesce(max(id),0) FROM radio_system_identity_summary");
            long natural = number(connection, "SELECT coalesce(max(identity_id),0) FROM radio_system_identity_summary WHERE radio_system_id=" + SYSTEM + " AND identity_kind_code=2");
            execute(connection, n + "INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms) " +
                "SELECT " + firstRadio + "+i," + SYSTEM + ",2,987700,2748," + natural + "+i,1000,2000 FROM n");
            execute(connection, "INSERT OR IGNORE INTO radio_system_identity_summary(radio_system_id,identity_kind_code,home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms) " +
                "VALUES(" + SYSTEM + ",1,987700,2748,65534,1000,2000)");
            long talkgroup = number(connection, "SELECT id FROM radio_system_identity_summary WHERE radio_system_id=" + SYSTEM + " AND identity_kind_code=1");
            execute(connection, n + "INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,source_identity_summary_id) " +
                "SELECT " + firstEvent + "+i," + SYSTEM + "," + SYSTEM + ",1000000+i,4," + firstRadio + "+i FROM n");
            execute(connection, n + "INSERT INTO p25_site_call_identity_bucket(radio_system_id,learned_site_id,channel_id,bucket_start_ms,identity_role_code,identity_kind_code,identity_summary_id,observed_local_id,last_observed_at_ms,observed_call_count) " +
                "SELECT " + SYSTEM + "," + SYSTEM + "," + SYSTEM + ",0,2,2," + firstRadio + "+i," + natural + "+i,2000,1 FROM n");
            execute(connection, n + "INSERT INTO trunked_radio_affiliation(radio_system_id,radio_identity_id,talkgroup_identity_id,channel_id,radio_observed_local_id,talkgroup_observed_local_id,confirmed_at_ms) " +
                "SELECT " + SYSTEM + "," + firstRadio + "+i," + talkgroup + "," + SYSTEM + "," + natural + "+i,65534,2000 FROM n");
            execute(connection, n + "INSERT INTO trunked_radio_channel_presence(radio_system_id,radio_identity_id,channel_id,observed_local_id,evidence_code,confirmed_at_ms) " +
                "SELECT " + SYSTEM + "," + firstRadio + "+i," + SYSTEM + "," + natural + "+i,1,2000 FROM n");
            execute(connection, n + "INSERT INTO trunked_radio_channel_presence_clear(radio_system_id,radio_identity_id,channel_id,observed_local_id,cleared_at_ms) " +
                "SELECT " + SYSTEM + "," + firstRadio + "+i," + SYSTEM + "," + natural + "+i,2100 FROM n");
        }
        else
        {
            long channel = number(connection, "SELECT id FROM receiver_channel LIMIT 1");
            execute(connection, n + "INSERT INTO receiver_activity_event(id,channel_id,observed_at_ms,action_code) " +
                "SELECT " + firstEvent + "+i," + channel + ",1000000+i,4 FROM n");
            if(source == 57)
            {
                List<String> fields = columnNames(connection, "topic_alert_message").stream().filter(name -> !name.equals("id")).toList();
                long start = number(connection, "SELECT coalesce(max(rowid),0) FROM topic_alert_message");
                execute(connection, n + "INSERT INTO topic_alert_message(id," + String.join(",", fields) + ") " +
                    "SELECT 'nullable-'||(" + start + "+i)," + fields.stream().map(field -> "t." + field)
                        .collect(java.util.stream.Collectors.joining(",")) +
                    " FROM topic_alert_message t CROSS JOIN n WHERE t.rowid=(SELECT min(rowid) FROM topic_alert_message)");
            }
        }
    }

    private static void futureWrites(Connection connection, int source) throws SQLException
    {
        if(source == 31)
        {
            for(var table: additions(source).entrySet())
                for(String checked: table.getValue())
                {
                    String column = checked.substring(0, checked.indexOf(' '));
                    if(column.equals("p25_subscriber_identity_id")) continue;
                    execute(connection, "UPDATE " + table.getKey() + " SET " + column + "=16777212");
                    for(String invalid: List.of("0", "16777213", "1.5")) assertThrows(SQLException.class,
                        () -> execute(connection, "UPDATE " + table.getKey() + " SET " + column + "=" + invalid));
                    execute(connection, "UPDATE " + table.getKey() + " SET " + column + "=NULL");
                }
            execute(connection, "INSERT INTO p25_subscriber_identity(home_wacn,home_system_id,subscriber_id) VALUES(987700,2748,1)");
            long id = number(connection, "SELECT id FROM radio_system_identity_summary WHERE radio_system_id=" + SYSTEM + " AND identity_kind_code=2 LIMIT 1");
            long subscriber = number(connection, "SELECT id FROM p25_subscriber_identity WHERE home_wacn=987700 AND home_system_id=2748 AND subscriber_id=1");
            execute(connection, "UPDATE radio_system_identity_summary SET p25_subscriber_identity_id=" + subscriber + " WHERE id=" + id);
            assertEquals(1, number(connection, "SELECT count(*) FROM radio_system_identity_summary INDEXED BY idx_radio_system_identity_p25_subscriber WHERE p25_subscriber_identity_id=" + subscriber));
            assertThrows(SQLException.class, () -> execute(connection, "DELETE FROM p25_subscriber_identity WHERE id=" + subscriber));
            assertThrows(SQLException.class, () -> execute(connection, "UPDATE radio_system_identity_summary SET p25_subscriber_identity_id=999999999999 WHERE id=" + id));
            execute(connection, "UPDATE radio_system_identity_summary SET p25_subscriber_identity_id=NULL WHERE id=" + id);
            assertEquals(0, number(connection, "SELECT count(*) FROM radio_system_identity_summary INDEXED BY idx_radio_system_identity_p25_subscriber WHERE p25_subscriber_identity_id IS NOT NULL"));
        }
        else if(source == 57)
            for(String table: additions(source).keySet())
            {
                execute(connection, "UPDATE " + table + " SET source_call_uuid='" + UUID + "'");
                assertEquals(number(connection, "SELECT count(*) FROM " + table), number(connection,
                    "SELECT count(*) FROM " + table + " INDEXED BY idx_" + table + "_source_call WHERE source_call_uuid='" + UUID + "'"));
                for(String invalid: List.of("unknown", UUID.toUpperCase())) assertThrows(SQLException.class,
                    () -> execute(connection, "UPDATE " + table + " SET source_call_uuid='" + invalid + "'"));
                execute(connection, "UPDATE " + table + " SET source_call_uuid=NULL");
                assertEquals(0, number(connection, "SELECT count(*) FROM " + table + " INDEXED BY idx_" + table + "_source_call WHERE source_call_uuid IS NOT NULL"));
            }
        else
        {
            execute(connection, "UPDATE receiver_activity_event SET duration_ms=0 WHERE action_code=4");
            execute(connection, "UPDATE receiver_activity_event SET duration_ms=1234 WHERE action_code=4");
            for(String invalid: List.of("-1", "1.5", "'unknown'")) assertThrows(SQLException.class,
                () -> execute(connection, "UPDATE receiver_activity_event SET duration_ms=" + invalid + " WHERE action_code=4"));
            assertThrows(SQLException.class, () -> execute(connection, "INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code,duration_ms) " +
                "SELECT channel_id,3000000,9,1 FROM receiver_activity_event LIMIT 1"));
        }
    }

    private static AtomicInteger installBudget(Connection connection, int budget) throws SQLException
    {
        var callbacks = new AtomicInteger();
        ProgressHandler.setHandler(connection, 100, new ProgressHandler()
        { @Override protected int progress() { return callbacks.incrementAndGet() * 100 > budget ? 1 : 0; } });
        return callbacks;
    }
    private static void begin(Connection connection) throws SQLException
    { execute(connection, "PRAGMA foreign_keys=OFF"); execute(connection, "PRAGMA ignore_check_constraints=ON"); connection.setAutoCommit(false); }
    private static List<String> columnNames(Connection connection, String table) throws SQLException
    {
        List<String> result = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_xinfo(" + table + ")"))
        { while(rows.next()) result.add(rows.getString("name")); }
        return result;
    }
    private static String layout(Connection connection, String table) throws SQLException
    { return queryHash(connection, "SELECT * FROM pragma_table_xinfo('" + table + "')") + ":" +
        queryHash(connection, "SELECT * FROM pragma_foreign_key_list('" + table + "')") + ":" +
        queryHash(connection, "SELECT ncol,wr,strict FROM pragma_table_list WHERE name='" + table + "'"); }
    private static String definition(Connection connection, String table) throws SQLException
    {
        try(var statement = connection.createStatement(); var row = statement.executeQuery("SELECT sql FROM sqlite_schema WHERE type='table' AND name='" + table + "'"))
        { assertTrue(row.next()); return row.getString(1); }
    }
    private static Map<String,String> originalRoots(Connection connection, List<String> tables, List<String> originalNames,
        Map<String,List<String>> oldColumns) throws SQLException
    {
        Map<String,String> result = new TreeMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT name,type,rootpage,rowid,sql,tbl_name FROM sqlite_schema WHERE type IN('table','index') AND tbl_name IN(" + names(tables) + ") ORDER BY name"))
        {
            while(rows.next()) if(originalNames == null || originalNames.contains(rows.getString(1)))
                result.put(rows.getString(1), rows.getString(2) + ":" + rows.getLong(3) + ":" + rows.getLong(4) +
                    (rows.getString(2).equals("index") ? ":" + rows.getString(5) + ":" +
                        // Native ADD can expose appended NULL payload columns on a WITHOUT ROWID primary index.
                        // Preserve every old key/covering field; the separate native-layout proof covers additions.
                        queryHash(connection, "SELECT * FROM pragma_index_xinfo('" + rows.getString(1) +
                            "') WHERE cid<" + oldColumns.get(rows.getString(6)).size()) : ""));
        }
        return result;
    }
    private static String statistics(Connection connection, List<String> tables) throws SQLException
    {
        String result = "";
        for(String table: List.of("sqlite_stat1", "sqlite_stat4"))
            if(number(connection, "SELECT count(*) FROM sqlite_schema WHERE name='" + table + "'") > 0)
                result += queryHash(connection, "SELECT * FROM " + table + " WHERE tbl IN(" + names(tables) + ")");
        return result;
    }
    private static String tableHash(Connection connection, String table, List<String> fields) throws SQLException
    { return queryHash(connection, "SELECT " + String.join(",", fields) + " FROM " + table); }
    private static String queryHash(Connection connection, String query) throws SQLException
    {
        List<String> hashes = new ArrayList<>();
        try(var statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query))
        {
            while(rows.next())
            {
                StringBuilder value = new StringBuilder();
                for(int i=1; i<=rows.getMetaData().getColumnCount(); i++)
                {
                    Object item=rows.getObject(i);
                    String text=item instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : item==null ? "NULL" : item.getClass().getName()+":"+item;
                    value.append(text.length()).append(':').append(text).append(';');
                }
                hashes.add(hash(value.toString()));
            }
        }
        hashes.sort(String::compareTo); return hash(String.join("\n", hashes));
    }
    private static String hash(String value)
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static String names(List<String> names)
    { return names.stream().map(name -> "'"+name+"'").collect(java.util.stream.Collectors.joining(",")); }
    private static long number(Connection connection, String query) throws SQLException
    { try(var statement=connection.createStatement(); var rows=statement.executeQuery(query)) { assertTrue(rows.next()); return rows.getLong(1); } }
    private static void execute(Connection connection, String sql) throws SQLException
    { try(var statement=connection.createStatement()) { statement.execute(sql); } }
    private static Connection open(Path database) throws SQLException
    { return DriverManager.getConnection("jdbc:sqlite:"+database); }
}
