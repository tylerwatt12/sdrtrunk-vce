/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.alerts.TopicAlertSchema;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.ProgressHandler;

class LegacyAlertActionContextMigrationWorkTest
{
    private static final int ROWS = 20000;
    private static final String TABLE = "topic_alert_message";
    private static final List<String> ADDITIONS = List.of(
        "action_kind TEXT NOT NULL DEFAULT 'none' CHECK(action_kind IN ('none','live','channel','radio','talkgroup','patch_group','radio_system'))",
        "action_entity_ref_json TEXT CHECK(action_entity_ref_json IS NULL OR CASE WHEN json_valid(action_entity_ref_json) " +
            "THEN json_type(action_entity_ref_json)='object' AND length(action_entity_ref_json)<=2048 ELSE 0 END)");
    @TempDir Path directory;

    @Test void defaultedActionAppendHasFixedWorkAndBothFormerCheckedAddsExceedItsBudget() throws Exception
    {
        List<Path> sources = sources("append");
        List<Integer> work = new ArrayList<>();
        for(String checked: ADDITIONS)
        {
            Path old = directory.resolve("old-" + ADDITIONS.indexOf(checked) + ".sqlite");
            Files.copy(sources.getLast(), old);
            try(Connection connection = open(old))
            {
                execute(connection, "PRAGMA ignore_check_constraints=ON");
                budget(connection, 100_000);
                try
                {
                    SQLException failure = assertThrows(SQLException.class,
                        () -> execute(connection, "ALTER TABLE " + TABLE + " ADD COLUMN " + checked));
                    assertEquals(9, failure.getErrorCode(), "The old checked ADD must hit SQLITE_INTERRUPT");
                }
                finally { ProgressHandler.clearHandler(connection); }
                assertEquals(48, DatabaseFormatCatalog.inspectForMigration(connection).version());
            }
        }
        for(Path source: sources)
        {
            byte[] original = Files.readAllBytes(source);
            try(Connection connection = open(source); Connection nativeTarget = DriverManager.getConnection("jdbc:sqlite::memory:"))
            {
                List<String> oldColumns = columns(connection);
                String rows = oldMessages(connection, oldColumns), delivery = queryHash(connection, "SELECT * FROM topic_alert_delivery");
                String stats = statistics(connection), allocator = queryHash(connection, "SELECT * FROM sqlite_sequence");
                var roots = roots(connection);
                execute(nativeTarget, definition(connection));
                for(String checked: ADDITIONS) execute(nativeTarget, "ALTER TABLE " + TABLE + " ADD COLUMN " + checked);
                begin(connection);
                AtomicInteger callbacks = budget(connection, 100_000);
                try { TopicAlertSchema.addActionContext(connection); }
                finally { ProgressHandler.clearHandler(connection); }
                work.add(callbacks.get() * 100);
                SqliteSchemaValidator.validateDefinitions(connection, List.of(
                    new SqliteSchemaValidator.Definition("table", TABLE, definition(nativeTarget))));
                assertEquals(layout(nativeTarget), layout(connection));
                assertEquals(rows, oldMessages(connection, oldColumns));
                assertEquals(delivery, queryHash(connection, "SELECT * FROM topic_alert_delivery"));
                assertEquals(roots, roots(connection));
                assertEquals(stats, statistics(connection));
                assertEquals(allocator, queryHash(connection, "SELECT * FROM sqlite_sequence"));
                assertEquals(0, number(connection, "SELECT count(*) FROM " + TABLE +
                    " WHERE action_kind<>'none' OR action_kind IS NULL OR action_entity_ref_json IS NOT NULL"));
                assertEquals(0, number(connection, "PRAGMA writable_schema"));
                connection.rollback();
                assertEquals(48, DatabaseFormatCatalog.inspectForMigration(connection).version());
            }
            assertArrayEquals(original, Files.readAllBytes(source), "Append-only rollback restores the exact frozen source");
        }
        assertTrue(work.getLast() <= work.getFirst() + 2_000,
            "20,000 retained alerts must not add row-sized append work: " + work);
    }

    @Test void ownedAdjacentRouteKeepsRealListenBackfillAndAccurateCountsWithoutCopyingHistory() throws Exception
    {
        List<Integer> work = new ArrayList<>();
        for(Path source: sources("owned"))
        {
            byte[] original = Files.readAllBytes(source);
            try(Connection connection = open(source))
            {
                List<String> oldColumns = columns(connection);
                String rows = oldMessages(connection, oldColumns), delivery = queryHash(connection, "SELECT * FROM topic_alert_delivery");
                String stats = statistics(connection), allocator = queryHash(connection, "SELECT * FROM sqlite_sequence");
                var roots = roots(connection);
                long listen = number(connection, "SELECT count(*) FROM " + TABLE + " WHERE click_action='listen'");
                begin(connection);
                adjacent(connection); connection.rollback();
                assertEquals(48, DatabaseFormatCatalog.inspectForMigration(connection).version());
                assertEquals(rows, oldMessages(connection, oldColumns));
                assertArrayEquals(original, Files.readAllBytes(source));
                AtomicInteger callbacks = budget(connection, 2_000_000);
                DatabaseMigrationChain.MigrationReport report;
                try
                {
                    var detected = DatabaseFormatCatalog.inspectForMigration(connection);
                    assertTrue(DatabaseMigrationChain.planInPlace(connection, detected).steps().stream()
                        .anyMatch(step -> step.sourceVersion() == 48));
                    report = adjacent(connection);
                    assertEquals(49, DatabaseFormatCatalog.inspectForMigration(connection).version());
                }
                finally { ProgressHandler.clearHandler(connection); }
                work.add(callbacks.get() * 100);
                connection.commit();
                assertEffect(report, "prior Listen popup actions", listen);
                assertEffect(report, "alert rule popup settings", 2);
                assertEffect(report, "built-in alert popup setting", 1);
                assertEquals(DatabaseFormatCatalog.requireVersion(49).fingerprint(), SqliteSchemaValidator.fingerprint(connection));
                assertEquals(rows, oldMessages(connection, oldColumns));
                assertEquals(delivery, queryHash(connection, "SELECT * FROM topic_alert_delivery"));
                assertEquals(roots, roots(connection));
                assertEquals(stats, statistics(connection));
                assertEquals(allocator, queryHash(connection, "SELECT * FROM sqlite_sequence"));
                assertEquals(listen, number(connection, "SELECT count(*) FROM " + TABLE + " WHERE action_kind='live'"));
                assertEquals(0, number(connection, "SELECT count(*) FROM " + TABLE + " WHERE " +
                    "(click_action='listen' AND action_kind<>'live') OR (click_action='details' AND action_kind<>'none') " +
                    "OR action_entity_ref_json IS NOT NULL"));
            }
            try(Connection connection = open(source))
            {
                assertEquals(49, DatabaseFormatCatalog.inspect(connection).version());
                integrity(connection);
            }
        }
        assertTrue(work.getLast() > work.getFirst(), "The real Listen UPDATE remains history-dependent");
    }

    @Test void reopenedRowsKeepDefaultsDomainJsonAndForeignKeyConstraints() throws Exception
    {
        Path source = Format48TestDatabase.create(directory.resolve("constraints.sqlite"));
        try(Connection connection = open(source))
        {
            begin(connection); adjacent(connection); connection.commit();
        }
        try(Connection connection = open(source))
        {
            execute(connection, "PRAGMA foreign_keys=ON");
            assertEquals(49, DatabaseFormatCatalog.inspect(connection).version());
            execute(connection, "INSERT INTO topic_alert_message(id,rule_id,topic_id,topic_name,alert_name,title,body," +
                "click_action,event_type,created_at_ms,expires_at_ms) SELECT 'new-default',rule_id,topic_id,topic_name," +
                "alert_name,title,body,click_action,event_type,created_at_ms,expires_at_ms FROM " + TABLE +
                " WHERE id='message-details'");
            assertEquals(1, number(connection, "SELECT count(*) FROM " + TABLE +
                " WHERE id='new-default' AND action_kind='none' AND action_entity_ref_json IS NULL"));
            for(String kind: List.of("none", "live", "channel", "radio", "talkgroup", "patch_group", "radio_system"))
                execute(connection, "UPDATE " + TABLE + " SET action_kind='" + kind + "' WHERE id='new-default'");
            for(String kind: List.of("NULL", "'other'", "123")) assertThrows(SQLException.class,
                () -> execute(connection, "UPDATE " + TABLE + " SET action_kind=" + kind + " WHERE id='new-default'"));
            for(String json: List.of("[]", "{", "1", "\"text\"", "{\"padding\":\"" + "x".repeat(2048) + "\"}"))
                try(var update = connection.prepareStatement("UPDATE " + TABLE + " SET action_entity_ref_json=? WHERE id='new-default'"))
                { update.setString(1, json); assertThrows(SQLException.class, update::executeUpdate); }
            execute(connection, "UPDATE " + TABLE + " SET action_entity_ref_json='{}' WHERE id='new-default'");
            execute(connection, "UPDATE " + TABLE + " SET action_entity_ref_json=NULL WHERE id='new-default'");
            assertThrows(SQLException.class, () -> execute(connection,
                "UPDATE " + TABLE + " SET topic_id='missing-topic' WHERE id='new-default'"));
            assertThrows(SQLException.class, () -> execute(connection,
                "UPDATE " + TABLE + " SET expires_at_ms=created_at_ms WHERE id='new-default'"));
            integrity(connection);
        }
    }

    @Test void populatedActionAppendRefusesAnUnownedOrForeignKeyEnabledBoundary() throws Exception
    {
        Path source = Format48TestDatabase.create(directory.resolve("unsafe.sqlite"));
        byte[] original = Files.readAllBytes(source);
        try(Connection connection = open(source))
        {
            assertThrows(SQLException.class, () -> TopicAlertSchema.addActionContext(connection));
            execute(connection, "PRAGMA foreign_keys=ON"); connection.setAutoCommit(false);
            assertThrows(SQLException.class, () -> TopicAlertSchema.addActionContext(connection));
            connection.rollback();
            assertEquals(48, DatabaseFormatCatalog.inspectForMigration(connection).version());
        }
        assertArrayEquals(original, Files.readAllBytes(source));
    }

    private List<Path> sources(String prefix) throws Exception
    {
        Path tiny = Format48TestDatabase.create(directory.resolve(prefix + "-tiny.sqlite"));
        try(Connection connection = open(tiny)) { seed(connection, 1); execute(connection, "ANALYZE"); }
        Path grown = directory.resolve(prefix + "-grown.sqlite"); Files.copy(tiny, grown);
        try(Connection connection = open(grown)) { seed(connection, ROWS); }
        try(Connection small = open(tiny); Connection large = open(grown))
        {
            assertEquals(number(small, "SELECT count(*) FROM " + TABLE) + ROWS, number(large, "SELECT count(*) FROM " + TABLE));
            assertEquals(statistics(small), statistics(large), "Keep compact planner metadata identical while growing alerts");
        }
        return List.of(tiny, grown);
    }

    private static void seed(Connection connection, int count) throws SQLException
    {
        long start = number(connection, "SELECT max(rowid) FROM " + TABLE);
        execute(connection, "WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<" + count + ") " +
            "INSERT INTO " + TABLE + " SELECT 'work-'||(" + start + "+i),rule_id,topic_id,topic_name,alert_name,title," +
            "body,click_action,event_type,created_at_ms,expires_at_ms FROM " + TABLE + " CROSS JOIN n " +
            "WHERE id=CASE WHEN i%2=0 THEN 'message-details' ELSE 'message-listen' END");
    }

    private static DatabaseMigrationChain.MigrationReport adjacent(Connection connection) throws SQLException
    {
        var report = DatabaseMigrationChain.migrate(connection, DatabaseFormatCatalog.inspectForMigration(connection),
            false, ignored -> {}, ignored -> {}, 49);
        assertEquals(1, report.steps().size());
        assertEquals(48, report.steps().getFirst().sourceVersion());
        return report;
    }

    private static void assertEffect(DatabaseMigrationChain.MigrationReport report, String subject, long count)
    { assertEquals(count, report.steps().getFirst().effects().stream().filter(effect -> subject.equals(effect.subject()))
        .findFirst().orElseThrow().affectedRows()); }
    private static void begin(Connection connection) throws SQLException
    { execute(connection, "PRAGMA foreign_keys=OFF"); execute(connection, "PRAGMA ignore_check_constraints=ON"); connection.setAutoCommit(false); }
    private static AtomicInteger budget(Connection connection, int maximum) throws SQLException
    {
        // Instruction caps complement frozen/native DDL review; single opcodes can hide native work.
        var callbacks = new AtomicInteger();
        ProgressHandler.setHandler(connection, 100, new ProgressHandler()
        { @Override protected int progress() { return callbacks.incrementAndGet() * 100 > maximum ? 1 : 0; } });
        return callbacks;
    }
    private static List<String> columns(Connection connection) throws SQLException
    {
        List<String> result = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_xinfo(" + TABLE + ")"))
        { while(rows.next()) result.add(rows.getString("name")); }
        return result;
    }
    private static String definition(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement(); var row = statement.executeQuery("SELECT sql FROM sqlite_schema WHERE name='" + TABLE + "'"))
        { assertTrue(row.next()); return row.getString(1); }
    }
    private static String layout(Connection connection) throws SQLException
    { return queryHash(connection, "SELECT * FROM pragma_table_xinfo('" + TABLE + "')") + ":" +
        queryHash(connection, "SELECT * FROM pragma_foreign_key_list('" + TABLE + "')") + ":" +
        queryHash(connection, "SELECT ncol,wr,strict FROM pragma_table_list WHERE name='" + TABLE + "'"); }
    private static Map<String,String> roots(Connection connection) throws SQLException
    {
        Map<String,String> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT name,type,rootpage,rowid,sql FROM sqlite_schema WHERE tbl_name IN('topic_alert_message','topic_alert_delivery') " +
                "AND type IN('table','index') ORDER BY name"))
        { while(rows.next()) result.put(rows.getString(1), rows.getString(2) + ":" + rows.getLong(3) + ":" + rows.getLong(4) +
            (rows.getString(2).equals("index") ? ":" + rows.getString(5) : "")); }
        return result;
    }
    private static String statistics(Connection connection) throws SQLException
    {
        String result = "";
        for(String table: List.of("sqlite_stat1", "sqlite_stat4"))
            if(number(connection, "SELECT count(*) FROM sqlite_schema WHERE name='" + table + "'") > 0)
                result += queryHash(connection, "SELECT * FROM " + table + " WHERE tbl IN('topic_alert_message','topic_alert_delivery')");
        return result;
    }
    private static String oldMessages(Connection connection, List<String> columns) throws SQLException
    { return queryHash(connection, "SELECT rowid," + String.join(",", columns) + " FROM " + TABLE); }
    private static String queryHash(Connection connection, String query) throws SQLException
    {
        List<String> values = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(query))
        {
            while(rows.next())
            {
                StringBuilder value = new StringBuilder();
                for(int i=1; i<=rows.getMetaData().getColumnCount(); i++)
                {
                    Object item = rows.getObject(i);
                    String field = item instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) :
                        item == null ? "NULL" : item.getClass().getName() + ":" + item;
                    value.append(field.length()).append(':').append(field).append(';');
                }
                values.add(value.toString());
            }
        }
        values.sort(String::compareTo); return hash(String.join("\n", values));
    }
    private static String hash(String value)
    {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }
    private static long number(Connection connection, String query) throws SQLException
    { try(var statement = connection.createStatement(); var row = statement.executeQuery(query))
      { assertTrue(row.next()); return row.getLong(1); } }
    private static void execute(Connection connection, String sql) throws SQLException
    { try(var statement = connection.createStatement()) { statement.execute(sql); } }
    private static Connection open(Path path) throws SQLException
    { return DriverManager.getConnection("jdbc:sqlite:" + path); }
    private static void integrity(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement(); var row = statement.executeQuery("PRAGMA quick_check"))
        { assertTrue(row.next()); assertEquals("ok", row.getString(1)); assertFalse(row.next()); }
        try(var statement = connection.createStatement(); var row = statement.executeQuery("PRAGMA foreign_key_check"))
        { assertFalse(row.next()); }
    }
}
