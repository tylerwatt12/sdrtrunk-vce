/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.ActivityMessageCatalog;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.ProgressHandler;
import static org.junit.jupiter.api.Assertions.*;

class LegacyMessageCounterMigrationWorkTest
{
    private static final List<String> PREFIX = List.of("conventional_activity_summary",
        "trunked_signaling_activity_bucket", "conventional_activity_bucket");
    private static final List<String> REORDERED = List.of("radio_system_identity_summary", "trunked_radio_group_summary");
    private static final List<String> COUNTERS = List.of("stun_count", "revive_count", "kill_count",
        "radio_inhibit_count", "radio_uninhibit_count", "remote_monitor_count", "authenticate_count",
        "cancel_call_count", "radio_detach_count", "protect_count", "vote_now_count");
    private static final int ADDED_ROWS = 20_000;
    // A direct step probe measured identical 646,400 VM work at both sizes, largely 33 native metadata ADDs.
    private static final int WHOLE_STEP_BUDGET = 750_000;
    private static final int OLD_COPY_BUDGET = 500_000;
    @TempDir Path directory;

    @Test void actual59ChainHasFixedPrefixWorkWhileKeepingBothPhysicalReorders() throws Exception
    {
        Path tiny = Format59TestDatabase.create(directory.resolve("tiny.sqlite"));
        try(var connection = open(tiny)) { seed(connection, 1); execute(connection, "ANALYZE"); }
        Path grown = directory.resolve("grown.sqlite"); Files.copy(tiny, grown);
        try(var connection = open(grown)) { seed(connection, ADDED_ROWS); }
        try(var small = open(tiny); var large = open(grown))
        {
            for(String table: PREFIX)
                assertEquals(number(small, "SELECT count(*) FROM " + table) + ADDED_ROWS,
                    number(large, "SELECT count(*) FROM " + table), table);
            for(String table: REORDERED)
                assertEquals(tableHash(small, table, columns(small, table)),
                    tableHash(large, table, columns(large, table)), "The two genuine rewrites stay fixed: " + table);
            assertEquals(statistics(small), statistics(large), "Planner metadata is identical for the growth comparison");
        }
        for(String table: PREFIX)
        {
            try(var connection = open(tiny))
            {
                installBudget(connection, OLD_COPY_BUDGET);
                try
                {
                    assertDoesNotThrow(() -> execute(connection,
                        "CREATE TEMP TABLE old_prefix_copy AS SELECT * FROM " + table),
                        "The old copy's fixed setup fits the cap on tiny data");
                }
                finally { ProgressHandler.clearHandler(connection); }
            }
            try(var connection = open(grown))
            {
                installBudget(connection, OLD_COPY_BUDGET);
                try
                {
                    // Even the minimal old copy, without target constraints or indexes, exceeds the same budget.
                    SQLException failure = assertThrows(SQLException.class,
                        () -> execute(connection, "CREATE TEMP TABLE old_prefix_copy AS SELECT * FROM " + table));
                    assertEquals(9, failure.getErrorCode(), "A retained-table copy must hit SQLITE_INTERRUPT");
                }
                finally { ProgressHandler.clearHandler(connection); }
            }
        }
        int smallWork = migrate(tiny, true);
        int largeWork = migrate(grown, false);
        assertTrue(largeWork <= smallWork + 2_000,
            () -> "Growth of the three compatible tables changed migration work: " + smallWork + " -> " + largeWork);
    }

    private static int migrate(Path database, boolean checkFutureWrites) throws Exception
    {
        int work;
        List<String> all = new ArrayList<>(PREFIX); all.addAll(REORDERED); all.add("receiver_activity_event");
        try(var connection = open(database); var nativeSchema = DriverManager.getConnection("jdbc:sqlite::memory:"))
        {
            Map<String,List<String>> oldColumns = new LinkedHashMap<>();
            Map<String,String> oldRows = new LinkedHashMap<>();
            Map<String,String> oldDefinitions = new LinkedHashMap<>();
            Map<String,List<String>> oldLayouts = new LinkedHashMap<>();
            Map<String,String> oldFlags = new LinkedHashMap<>();
            for(String table: all)
            {
                oldColumns.put(table, columns(connection, table));
                oldRows.put(table, tableHash(connection, table, oldColumns.get(table)));
                oldDefinitions.put(table, definition(connection, table));
                oldLayouts.put(table, layout(connection, table));
                oldFlags.put(table, flags(connection, table));
            }
            Map<String,String> trees = trees(connection, null, oldColumns);
            String statistics = statistics(connection);
            String allocator = queryHash(connection, "SELECT * FROM sqlite_sequence");
            byte[] original = Files.readAllBytes(database);
            begin(connection); adjacent(connection); connection.rollback();
            assertEquals(59, DatabaseFormatCatalog.inspectForMigration(connection).version());
            assertEquals(trees, trees(connection, null, oldColumns));
            for(String table: all) assertEquals(oldRows.get(table), tableHash(connection, table, oldColumns.get(table)));
            assertArrayEquals(original, Files.readAllBytes(database));
            AtomicInteger callbacks = installBudget(connection, WHOLE_STEP_BUDGET);
            try
            {
                var source = DatabaseFormatCatalog.inspectForMigration(connection);
                assertEquals(59, source.version());
                assertTrue(DatabaseMigrationChain.planInPlace(connection, source).steps().stream()
                    .anyMatch(step -> step.sourceVersion() == 59));
                adjacent(connection);
                assertEquals(60, DatabaseFormatCatalog.inspectForMigration(connection).version());
                assertEquals(DatabaseFormatCatalog.requireVersion(60).fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            }
            finally { ProgressHandler.clearHandler(connection); }
            work = callbacks.get() * 100; connection.commit();
            assertEquals(trees, trees(connection, trees.keySet().stream().toList(), oldColumns));
            assertEquals(statistics, statistics(connection));
            assertEquals(allocator, queryHash(connection, "SELECT * FROM sqlite_sequence"));
            for(String table: all)
            {
                assertEquals(oldRows.get(table), tableHash(connection, table, oldColumns.get(table)), table);
                assertEquals(oldFlags.get(table), flags(connection, table), table);
                List<String> target = layout(connection, table);
                if(REORDERED.contains(table))
                    assertNotEquals(oldLayouts.get(table), target.subList(0, oldLayouts.get(table).size()),
                        "The frozen target genuinely inserts fields before old tail columns: " + table);
                else assertEquals(oldLayouts.get(table), target.subList(0, oldLayouts.get(table).size()), table);
                for(String counter: COUNTERS)
                    if(!table.equals("receiver_activity_event"))
                        assertEquals(0, number(connection, "SELECT count(*) FROM " + table + " WHERE " + counter + " IS NOT 0"));
            }
            String eventCodes = ActivityMessageCatalog.codes(connection).get("EVENT").values().stream().sorted()
                .map(String::valueOf).collect(java.util.stream.Collectors.joining(", "));
            for(String table: PREFIX)
            {
                String previous = oldDefinitions.get(table);
                if(table.equals("conventional_activity_summary"))
                {
                    String token = "last_event_type_code IN (";
                    int start = previous.indexOf(token) + token.length();
                    assertTrue(start >= token.length());
                    int end = previous.indexOf(')', start);
                    previous = previous.substring(0, start) + eventCodes + previous.substring(end);
                }
                execute(nativeSchema, previous);
                for(String counter: COUNTERS) execute(nativeSchema, "ALTER TABLE " + table + " ADD COLUMN " + counter +
                    " INTEGER NOT NULL DEFAULT 0 CHECK(typeof(" + counter + ") = 'integer' AND " + counter + " >= 0)");
                SqliteSchemaValidator.validateDefinitions(connection, List.of(new SqliteSchemaValidator.Definition(
                    "table", table, definition(nativeSchema, table))));
                assertEquals(layout(nativeSchema, table), layout(connection, table));
            }
        }
        try(var connection = open(database))
        {
            execute(connection, "PRAGMA foreign_keys=ON");
            if(checkFutureWrites)
            {
                for(String table: PREFIX)
                {
                    execute(connection, "UPDATE " + table + " SET stun_count=7,vote_now_count=9");
                    for(String invalid: List.of("-1", "1.5", "'unknown'", "NULL")) assertThrows(SQLException.class,
                        () -> execute(connection, "UPDATE " + table + " SET stun_count=" + invalid));
                }
                execute(connection, "UPDATE conventional_activity_summary SET last_event_type_code=63");
                assertThrows(SQLException.class, () -> execute(connection,
                    "UPDATE conventional_activity_summary SET last_event_type_code=2147483647"));
            }
            try(var statement=connection.createStatement(); var rows=statement.executeQuery("PRAGMA quick_check"))
            { assertTrue(rows.next()); assertEquals("ok",rows.getString(1)); assertFalse(rows.next()); }
            try(var statement=connection.createStatement(); var rows=statement.executeQuery("PRAGMA foreign_key_check"))
            { assertFalse(rows.next()); }
        }
        return work;
    }

    private static void adjacent(Connection connection) throws SQLException
    {
        var report = DatabaseMigrationChain.migrate(connection, DatabaseFormatCatalog.inspectForMigration(connection),
            false, ignored -> {}, ignored -> {}, 60);
        assertEquals(List.of("format-59-to-60"), report.steps().stream().map(DatabaseMigrationChain.StepReport::id).toList());
        assertEquals(60, report.target().version());
        assertTrue(report.steps().getFirst().effects().stream().allMatch(effect -> effect.affectedRows() >= 0));
    }

    private static void seed(Connection connection, int count) throws SQLException
    {
        long channel = number(connection, "SELECT id FROM receiver_channel LIMIT 1");
        long radio = number(connection, "SELECT id FROM radio_system LIMIT 1");
        String n = "WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<" + count + ") ";
        long frequency = number(connection, "SELECT coalesce(max(frequency_hz),100000000) FROM conventional_activity_summary");
        execute(connection, n + "INSERT INTO conventional_activity_summary(channel_id,frequency_hz,timeslot,first_seen_ms,last_seen_ms,call_count,last_event_type_code) " +
            "SELECT " + channel + "," + frequency + "+i,-1,1000,2000,i,7 FROM n");
        long bucket = number(connection, "SELECT coalesce(max(bucket_start_ms),0) FROM trunked_signaling_activity_bucket");
        execute(connection, n + "INSERT INTO trunked_signaling_activity_bucket(channel_id,radio_system_id,bucket_start_ms,grant_count) " +
            "SELECT " + channel + "," + radio + "," + bucket + "+i*3600000,i FROM n");
        frequency = number(connection, "SELECT coalesce(max(frequency_hz),100000000) FROM conventional_activity_bucket");
        execute(connection, n + "INSERT INTO conventional_activity_bucket(channel_id,frequency_hz,timeslot,bucket_start_ms,call_count) " +
            "SELECT " + channel + "," + frequency + "+i,-1,0,i FROM n");
    }

    private static AtomicInteger installBudget(Connection connection, int budget) throws SQLException
    {
        AtomicInteger result = new AtomicInteger();
        ProgressHandler.setHandler(connection,100,new ProgressHandler()
        { @Override protected int progress() { return result.incrementAndGet()*100>budget?1:0; } });
        return result;
    }
    private static void begin(Connection connection) throws SQLException
    { execute(connection,"PRAGMA foreign_keys=OFF"); execute(connection,"PRAGMA ignore_check_constraints=ON"); connection.setAutoCommit(false); }
    private static List<String> columns(Connection connection,String table) throws SQLException
    {
        List<String> result=new ArrayList<>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("PRAGMA table_xinfo("+table+")"))
        { while(rows.next())result.add(rows.getString("name")); }
        return result;
    }
    private static List<String> layout(Connection connection,String table) throws SQLException
    {
        List<String> result=new ArrayList<>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery("PRAGMA table_xinfo("+table+")"))
        { while(rows.next())result.add(rows.getInt("cid")+"|"+rows.getString("name")+"|"+rows.getString("type")+"|"+
            rows.getInt("notnull")+"|"+rows.getString("dflt_value")+"|"+rows.getInt("pk")+"|"+rows.getInt("hidden")); }
        return result;
    }
    private static String flags(Connection connection,String table) throws SQLException
    { return queryHash(connection,"SELECT wr,strict FROM pragma_table_list WHERE name='"+table+"'")+":"+
        queryHash(connection,"SELECT * FROM pragma_foreign_key_list('"+table+"')"); }
    private static String definition(Connection connection,String table) throws SQLException
    {
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT sql FROM sqlite_schema WHERE type='table' AND name='"+table+"'"))
        { assertTrue(rows.next());return rows.getString(1); }
    }
    private static Map<String,String> trees(Connection connection,List<String> original,Map<String,List<String>> columns) throws SQLException
    {
        List<String> retained=new ArrayList<>(PREFIX);retained.add("receiver_activity_event");
        Map<String,String> result=new TreeMap<>();
        String names=retained.stream().map(table->"'"+table+"'").collect(java.util.stream.Collectors.joining(","));
        try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT name,type,rootpage,rowid,sql,tbl_name FROM sqlite_schema WHERE type IN('table','index') AND tbl_name IN("+names+") ORDER BY name"))
        {
            while(rows.next())if(original==null||original.contains(rows.getString(1)))
                result.put(rows.getString(1),rows.getString(2)+":"+rows.getLong(3)+":"+rows.getLong(4)+
                    (rows.getString(2).equals("index")?":"+rows.getString(5)+":"+
                    queryHash(connection,"SELECT * FROM pragma_index_xinfo('"+rows.getString(1)+"') WHERE cid<"+
                        columns.get(rows.getString(6)).size()):""));
        }
        return result;
    }
    private static String statistics(Connection connection) throws SQLException
    {
        String result="";
        for(String table:List.of("sqlite_stat1","sqlite_stat4"))
            if(number(connection,"SELECT count(*) FROM sqlite_schema WHERE name='"+table+"'")>0)result+=queryHash(connection,"SELECT * FROM "+table);
        return result;
    }
    private static String tableHash(Connection connection,String table,List<String> columns) throws SQLException
    { return queryHash(connection,"SELECT "+String.join(",",columns)+" FROM "+table); }
    private static String queryHash(Connection connection,String query) throws SQLException
    {
        List<String> result=new ArrayList<>();
        try(var statement=connection.createStatement();var rows=statement.executeQuery(query))
        {
            while(rows.next())
            {
                StringBuilder value=new StringBuilder();
                for(int i=1;i<=rows.getMetaData().getColumnCount();i++)
                {
                    Object item=rows.getObject(i);
                    String text=item instanceof byte[] bytes?HexFormat.of().formatHex(bytes):item==null?"NULL":item.getClass().getName()+":"+item;
                    value.append(text.length()).append(':').append(text).append(';');
                }
                result.add(hash(value.toString()));
            }
        }
        result.sort(String::compareTo);return hash(String.join("\n",result));
    }
    private static String hash(String value)
    {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException impossible){throw new AssertionError(impossible);}
    }
    private static long number(Connection connection,String sql) throws SQLException
    { try(var statement=connection.createStatement();var row=statement.executeQuery(sql)){assertTrue(row.next());return row.getLong(1);} }
    private static void execute(Connection connection,String sql) throws SQLException
    { try(var statement=connection.createStatement()){statement.execute(sql);} }
    private static Connection open(Path database) throws SQLException
    { return DriverManager.getConnection("jdbc:sqlite:"+database); }
}
