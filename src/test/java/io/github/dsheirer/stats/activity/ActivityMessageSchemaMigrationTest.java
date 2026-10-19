/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.upgrade.Format59TestDatabase;
import java.nio.file.Path;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.ProgressHandler;

class ActivityMessageSchemaMigrationTest
{
    private static final List<String> TABLES = List.of("conventional_activity_summary",
        "trunked_signaling_activity_bucket", "conventional_activity_bucket");
    private static final List<String> COUNTERS = List.of("stun", "revive", "kill", "radio_inhibit",
        "radio_uninhibit", "remote_monitor", "authenticate", "cancel_call", "radio_detach", "protect", "vote_now")
        .stream().map(name -> name + "_count").toList();
    private static final List<String> DEFINITIONS = COUNTERS.stream().map(column -> column +
        " INTEGER NOT NULL DEFAULT 0 CHECK(typeof(" + column + ") = 'integer' AND " + column + " >= 0)").toList();
    @TempDir Path directory;

    @Test void appendingCountersBeforeCopyPreservesTheExactSchemaAndRowsWithLessVmWork() throws Exception
    {
        Work legacy = counterWork("legacy", false);
        Work optimized = counterWork("optimized", true);
        assertEquals(legacy.fingerprint(), optimized.fingerprint(), "The optimization must retain the format-60 DDL.");
        assertEquals(legacy.rows(), optimized.rows(), "Counter values and original rowids must match the prior strategy.");
        assertTrue(optimized.callbacks() * 2 < legacy.callbacks(), () ->
            "Counter expansion must avoid per-column retained-row scans: optimized=" + optimized.callbacks() +
                ", legacy=" + legacy.callbacks());
        System.out.printf("Counter expansion VM callbacks (1000 instructions): legacy=%d, optimized=%d%n",
            legacy.callbacks(), optimized.callbacks());
    }

    @Test void eventConstraintRelaxationHasBoundedWorkAndFailsClosedBeforeChangingMetadata() throws Exception
    {
        Path database = Format59TestDatabase.create(directory.resolve("event-guards.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var target = DriverManager.getConnection("jdbc:sqlite::memory:");
            var statement = connection.createStatement())
        {
            SdrTrunkDatabaseSchema.create(target); ReceiverActivitySchema.create(target);
            String previous = eventDefinition(connection), current = eventDefinition(target);
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            // A populated update must refuse unmanaged autocommit and an enabled FK boundary.
            assertThrows(SQLException.class, () -> ActivityMessageSchemaMigration.relaxEventConstraints(connection, previous, current));
            assertEquals(fingerprint, SqliteSchemaValidator.fingerprint(connection));
            statement.execute("PRAGMA foreign_keys=ON");
            connection.setAutoCommit(false);
            assertThrows(SQLException.class, () -> ActivityMessageSchemaMigration.relaxEventConstraints(connection, previous, current));
            connection.rollback(); connection.setAutoCommit(true);
            statement.execute("PRAGMA foreign_keys=OFF");
            connection.setAutoCommit(false);
            assertThrows(SQLException.class, () -> ActivityMessageSchemaMigration.relaxEventConstraints(connection,
                previous.replace("receiver_activity_event", "different_table"), current));
            assertThrows(SQLException.class, () -> ActivityMessageSchemaMigration.relaxEventConstraints(connection,
                previous, "CREATE TABLE receiver_activity_event (broken"));
            assertThrows(SQLException.class, () -> ActivityMessageSchemaMigration.relaxEventConstraints(connection,
                previous, current.substring(0, current.lastIndexOf(')')) + ", extra INTEGER)"));
            assertEquals(fingerprint, SqliteSchemaValidator.fingerprint(connection));
            assertEquals(0, scalar(connection, "PRAGMA writable_schema"));
            // Fail after the bounded metadata UPDATE: cleanup must disable schema writing, and the
            // migrator's rollback must restore the previous definition without touching event b-trees.
            Connection interrupted = (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) ->
                {
                    try
                    {
                        Object result = method.invoke(connection, arguments);
                        if(method.getName().equals("createStatement"))
                            return Proxy.newProxyInstance(Statement.class.getClassLoader(), new Class<?>[]{Statement.class},
                                (statementProxy, operation, parameters) ->
                                {
                                    if(operation.getName().equals("execute") && parameters[0] instanceof String sql &&
                                        sql.startsWith("PRAGMA schema_version="))
                                        throw new SQLException("Injected schema-version failure");
                                    try { return operation.invoke(result, parameters); }
                                    catch(InvocationTargetException error) { throw error.getCause(); }
                                });
                        return result;
                    }
                    catch(InvocationTargetException error) { throw error.getCause(); }
                });
            SQLException failure = assertThrows(SQLException.class,
                () -> ActivityMessageSchemaMigration.relaxEventConstraints(interrupted, previous, current));
            assertEquals("Injected schema-version failure", failure.getMessage());
            assertEquals(current, eventDefinition(connection), "The injected failure occurs after the metadata UPDATE.");
            assertEquals(0, scalar(connection, "PRAGMA writable_schema"));
            connection.rollback();
            assertEquals(fingerprint, SqliteSchemaValidator.fingerprint(connection));
            statement.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<10000) " +
                "INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code,event_type_code) " +
                "SELECT (SELECT channel_id FROM receiver_activity_event LIMIT 1),100000+i,1,1 FROM n");
            AtomicInteger callbacks = new AtomicInteger();
            ProgressHandler.setHandler(connection, 1000, new ProgressHandler()
            {
                @Override protected int progress() { return callbacks.incrementAndGet() > 100 ? 1 : 0; }
            });
            try { ActivityMessageSchemaMigration.relaxEventConstraints(connection, previous, current); }
            finally { ProgressHandler.clearHandler(connection); }
            assertTrue(callbacks.get() < 100, "Event relaxation must not scan retained events or rebuild their indexes.");
            assertEquals(0, scalar(connection, "PRAGMA writable_schema"));
            System.out.printf("Event CHECK relaxation VM callbacks (1000 instructions): %d%n", callbacks.get());
            connection.rollback();
            assertEquals(fingerprint, SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test void populatedSummariesRefuseAnUnmanagedUpgradeBeforeAnySchemaMutation() throws Exception
    {
        Path database = Format59TestDatabase.create(directory.resolve("summary-boundary.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.executeUpdate("DELETE FROM receiver_activity_event");
            assertTrue(scalar(connection, "SELECT count(*) FROM radio_system_identity_summary") > 0);
            String before = SqliteSchemaValidator.fingerprint(connection);
            assertThrows(SQLException.class, () -> ReceiverActivitySchema.upgradeFrom59(connection));
            assertEquals(before, SqliteSchemaValidator.fingerprint(connection));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM sqlite_schema WHERE name='activity_message_type'"));
        }
    }

    private static String eventDefinition(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement(); var row = statement.executeQuery(
            "SELECT sql FROM sqlite_schema WHERE type='table' AND name='receiver_activity_event'"))
        { assertTrue(row.next()); return row.getString(1); }
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(var statement = connection.createStatement(); var row = statement.executeQuery(sql))
        { assertTrue(row.next()); return row.getLong(1); }
    }

    private Work counterWork(String name, boolean optimized) throws Exception
    {
        Path database = Format59TestDatabase.create(directory.resolve(name + ".sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            connection.setAutoCommit(false);
            for(String table: TABLES) statement.executeUpdate("DELETE FROM " + table);
            // Distinct explicit rowids with gaps prove this is more than a value-only copy.
            statement.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<4096) " +
                "INSERT INTO conventional_activity_summary(rowid,channel_id,frequency_hz,first_seen_ms,last_seen_ms,call_count) " +
                "SELECT 1000+3*i,1,100000000+i,1,2,i FROM n");
            statement.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<4096) " +
                "INSERT INTO conventional_activity_bucket(rowid,channel_id,frequency_hz,bucket_start_ms,call_count) " +
                "SELECT 2000+5*i,1,100000000+i,3600000*i,i FROM n");
            statement.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<4096) " +
                "INSERT INTO trunked_signaling_activity_bucket(channel_id,radio_system_id,bucket_start_ms,join_count) " +
                "SELECT 1,1,3600000*i,i FROM n");
            statement.execute("ANALYZE");
            connection.commit();
            Map<String,List<String>> fields = originalFields(connection);
            Map<String,List<String>> before = snapshot(connection, fields);
            Map<String,String> definitions = new LinkedHashMap<>();
            for(String table: TABLES) try(var query = connection.prepareStatement(
                "SELECT sql FROM sqlite_schema WHERE type='table' AND name=?"))
            {
                query.setString(1, table);
                try(var row = query.executeQuery())
                { assertTrue(row.next()); definitions.put(table, row.getString(1).replace("\"" + table + "\"", table)); }
            }
            // Match the owned migrator's constraint policy while measuring SQLite work rather than disk timing.
            statement.execute("PRAGMA ignore_check_constraints=ON");
            AtomicInteger callbacks = new AtomicInteger();
            ProgressHandler.setHandler(connection, 1000, new ProgressHandler()
            {
                @Override protected int progress() { return callbacks.incrementAndGet() > 500_000 ? 1 : 0; }
            });
            try
            {
                if(optimized)
                {
                    for(String table: TABLES)
                        ActivityMessageSchemaMigration.rebuild(connection, table, definitions.get(table), DEFINITIONS);
                }
                else
                {
                    // The previous step already rebuilt this summary before the 33 populated ADD COLUMNs.
                    ActivityMessageSchemaMigration.rebuild(connection, TABLES.getFirst(), definitions.get(TABLES.getFirst()));
                    for(String table: TABLES) for(String definition: DEFINITIONS)
                        statement.executeUpdate("ALTER TABLE " + table + " ADD COLUMN " + definition);
                }
            }
            finally { ProgressHandler.clearHandler(connection); }
            statement.execute("PRAGMA ignore_check_constraints=OFF");
            assertEquals(before, snapshot(connection, fields), "Old fields, rowids, statistics and allocators survive.");
            for(String table: TABLES)
            {
                try(var row = statement.executeQuery("SELECT sum(" + String.join("+", COUNTERS) + ") FROM " + table))
                { assertTrue(row.next()); assertEquals(0, row.getLong(1)); }
                assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE " + table + " SET stun_count=-1"),
                    "New counters retain their CHECK constraints.");
            }
            Map<String,List<String>> rows = snapshot(connection, originalFields(connection));
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            connection.rollback();
            assertEquals(before, snapshot(connection, fields), "A failed migration remains safely retryable.");
            return new Work(callbacks.get(), fingerprint, rows);
        }
    }

    private static Map<String,List<String>> originalFields(Connection connection) throws SQLException
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        for(String table: TABLES)
        {
            List<String> fields = new ArrayList<>();
            if(!table.equals("trunked_signaling_activity_bucket")) fields.add("rowid");
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA table_info(" + table + ")"))
            { while(rows.next()) fields.add(rows.getString("name")); }
            result.put(table, fields);
        }
        for(String table: List.of("sqlite_sequence", "sqlite_stat1", "sqlite_stat4"))
            try(var query = connection.prepareStatement("SELECT 1 FROM sqlite_schema WHERE name=?"))
            {
                query.setString(1, table);
                try(var row = query.executeQuery()) { if(row.next()) result.put(table, List.of("*")); }
            }
        return result;
    }

    private static Map<String,List<String>> snapshot(Connection connection, Map<String,List<String>> fields)
        throws SQLException
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        for(var table: fields.entrySet())
        {
            List<String> records = new ArrayList<>();
            try(var statement = connection.createStatement(); var rows = statement.executeQuery(
                "SELECT " + String.join(",", table.getValue()) + " FROM " + table.getKey()))
            {
                while(rows.next())
                {
                    List<String> values = new ArrayList<>();
                    for(int i=1; i<=rows.getMetaData().getColumnCount(); i++)
                    {
                        Object value = rows.getObject(i);
                        values.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value));
                    }
                    records.add(values.toString());
                }
            }
            records.sort(String::compareTo);
            result.put(table.getKey(), records);
        }
        return result;
    }

    private record Work(int callbacks, String fingerprint, Map<String,List<String>> rows) {}
}
