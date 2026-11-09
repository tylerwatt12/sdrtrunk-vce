/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format68To69StorageEfficiencyTest
{
    private static final Set<String> ENCRYPTED_INDEXES = Set.of("idx_receiver_activity_event_encryption",
        "idx_receiver_activity_event_system_encrypted_time", "idx_receiver_activity_event_channel_encrypted_time");
    @TempDir Path directory;

    @Test void retainsEventAndUnaffectedIndexRootsAndStatisticsWhileUpdatingOnlyChangedStates() throws Exception
    {
        Path history = populatedHistory("roots", 5000);
        try(Connection connection = open(history); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE receiver_activity_event SET encrypted=3 WHERE observed_at_ms=100001");
            statement.execute("PRAGMA ignore_check_constraints=OFF");
            injectNonIntegerZero(connection);
            statement.execute("ANALYZE receiver_activity_event");
            Map<String,Long> roots = unaffectedRoots(connection, "main");
            Map<String,String> statistics = unaffectedStatistics(connection);
            long changes = scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE encrypted IS NOT 0 " +
                "OR typeof(encrypted)<>'integer' " +
                "OR event_type_code IN (6,8,10,17,20,26)");
            long sequence = scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='receiver_activity_event'");
            statement.execute("PRAGMA analysis_limit=17");
            statement.execute("PRAGMA foreign_keys=OFF");
            connection.setAutoCommit(false);
            List<String> operations = new ArrayList<>();
            assertEquals(changes, ReceiverActivitySchema.migrateFormat68EncryptionState(
                observe(connection, operations::add), "main"));
            assertEquals(17, scalar(connection, "PRAGMA analysis_limit"));
            assertEquals(0, scalar(connection, "PRAGMA writable_schema"));
            connection.commit(); connection.setAutoCommit(true);
            assertEquals(ConfigurationHistoryDatabase.HISTORY_FINGERPRINT, SqliteSchemaValidator.fingerprint(connection));
            assertEquals(roots, unaffectedRoots(connection, "main"));
            assertEquals(statistics, unaffectedStatistics(connection));
            assertEquals(sequence, scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='receiver_activity_event'"));
            assertEquals(0, scalar(connection, "SELECT encryption_state FROM receiver_activity_event WHERE observed_at_ms=100001"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE observed_at_ms=100002 " +
                "AND encryption_state=0 AND typeof(encryption_state)='integer'"));
            assertEquals(3, operations.stream().filter(sql -> sql.startsWith("CREATE INDEX main.")).count());
            assertEquals(3, operations.stream().filter(sql -> sql.startsWith("ANALYZE main.idx_")).count());
            assertFalse(operations.stream().anyMatch(sql -> sql.startsWith("DROP TABLE") ||
                sql.startsWith("CREATE TABLE") || sql.startsWith("ANALYZE main.receiver_activity_event")));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE receiver_activity_event SET encryption_state=3 WHERE id=(SELECT min(id) FROM receiver_activity_event)"));
            for(int state: List.of(0, 1, 2)) statement.executeUpdate("UPDATE receiver_activity_event SET encryption_state=" +
                state + " WHERE id=(SELECT min(id) FROM receiver_activity_event)");
            assertIntegrity(connection);
        }
    }
    @Test void everyMutationBoundaryRollsBackAttachedHistoryAndCanRetry() throws Exception
    {
        for(String phase: List.of("rename", "schema-update", "schema", "schema-off", "update", "index", "analysis"))
            rollbackAndRetry(phase);
    }

    private void rollbackAndRetry(String phase) throws Exception
    {
        Path configuration = Format68TestDatabase.create(directory.resolve("rollback-" + phase + ".sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(configuration);
        try(Connection connection = open(configuration); Statement statement = connection.createStatement())
        {
            try(var attach = connection.prepareStatement("ATTACH DATABASE ? AS activity"))
            { attach.setString(1, history.toString()); attach.execute(); }
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.execute("PRAGMA activity.journal_mode=DELETE");
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.execute("PRAGMA analysis_limit=37");
            String configurationSchema = SqliteSchemaValidator.fingerprint(connection);
            String activitySchema = SqliteSchemaValidator.fingerprint(connection, "activity");
            Map<String,Long> roots = unaffectedRoots(connection, "activity");
            long oldPositive = scalar(connection, "SELECT count(*) FROM activity.receiver_activity_event WHERE encrypted=1");
            connection.setAutoCommit(false);
            statement.executeUpdate("UPDATE database_metadata SET value='69' WHERE key='database_format_version'");
            AtomicBoolean failed = new AtomicBoolean();
            Connection interrupted = observe(connection, sql -> {
                if(matchesPhase(phase, sql) && failed.compareAndSet(false, true)) throw new InjectedFailure();
            }, phase.equals("schema-off"));
            assertThrows(InjectedFailure.class,
                () -> ReceiverActivitySchema.migrateFormat68EncryptionState(interrupted, "activity"));
            assertTrue(failed.get());
            assertEquals(0, scalar(connection, "PRAGMA writable_schema"));
            assertEquals(37, scalar(connection, "PRAGMA analysis_limit"));
            connection.rollback(); connection.setAutoCommit(true);
            assertEquals(configurationSchema, SqliteSchemaValidator.fingerprint(connection));
            assertEquals(activitySchema, SqliteSchemaValidator.fingerprint(connection, "activity"));
            assertEquals(roots, unaffectedRoots(connection, "activity"));
            assertEquals(oldPositive, scalar(connection,
                "SELECT count(*) FROM activity.receiver_activity_event WHERE encrypted=1"));
            assertEquals(68, scalar(connection,
                "SELECT CAST(value AS INTEGER) FROM database_metadata WHERE key='database_format_version'"));
            connection.setAutoCommit(false);
            ReceiverActivitySchema.migrateFormat68EncryptionState(connection, "activity");
            connection.commit(); connection.setAutoCommit(true);
            assertEquals(ConfigurationHistoryDatabase.HISTORY_FINGERPRINT,
                SqliteSchemaValidator.fingerprint(connection, "activity"));
            assertEquals(roots, unaffectedRoots(connection, "activity"));
        }
    }

    private Path populatedHistory(String name, int events) throws Exception
    {
        Path configuration = Format68TestDatabase.create(directory.resolve(name + ".sqlite"));
        Path history = ActivityDatabasePath.forConfiguration(configuration);
        try(Connection connection = open(history); Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=DELETE");
            statement.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<" + events + ") " +
                "INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code,event_type_code,encrypted) " +
                "SELECT (SELECT channel_id FROM receiver_activity_event LIMIT 1),100000+i,4," +
                "CASE WHEN i%97=0 THEN 8 ELSE 7 END,CASE WHEN i%100=0 THEN 1 ELSE 0 END FROM n");
        }
        return history;
    }

    private static Map<String,Long> unaffectedRoots(Connection connection, String schema) throws Exception
    {
        Map<String,Long> roots = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement(); var rows = statement.executeQuery("SELECT name,rootpage " +
            "FROM " + schema + ".sqlite_schema WHERE tbl_name='receiver_activity_event' AND type IN ('table','index') ORDER BY name"))
        { while(rows.next()) if(!ENCRYPTED_INDEXES.contains(rows.getString(1))) roots.put(rows.getString(1), rows.getLong(2)); }
        return roots;
    }

    /** Build admitted CHECK damage whose numeric value compares equal to the target integer zero. */
    private static void injectNonIntegerZero(Connection connection) throws Exception
    {
        String definition;
        try(Statement statement = connection.createStatement(); var row = statement.executeQuery(
            "SELECT sql FROM sqlite_schema WHERE name='receiver_activity_event' AND type='table'"))
        { assertTrue(row.next()); definition = row.getString(1); }
        String noAffinity = definition.replace("encrypted INTEGER", "encrypted BLOB");
        assertNotEquals(definition, noAffinity);
        replaceFixtureDefinition(connection, noAffinity);
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA ignore_check_constraints=ON");
            statement.executeUpdate("UPDATE receiver_activity_event SET encrypted=CAST(0 AS REAL) WHERE observed_at_ms=100002");
            statement.execute("PRAGMA ignore_check_constraints=OFF");
        }
        finally { replaceFixtureDefinition(connection, definition); }
        assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE observed_at_ms=100002 " +
            "AND typeof(encrypted)='real'"));
    }

    private static void replaceFixtureDefinition(Connection connection, String definition) throws Exception
    {
        long version = scalar(connection, "PRAGMA schema_version");
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA writable_schema=ON");
            try
            {
                try(var update = connection.prepareStatement(
                    "UPDATE sqlite_schema SET sql=? WHERE name='receiver_activity_event' AND type='table'"))
                { update.setString(1, definition); assertEquals(1, update.executeUpdate()); }
                statement.execute("PRAGMA schema_version=" + (version + 1));
            }
            finally { statement.execute("PRAGMA writable_schema=RESET"); }
        }
    }

    private static Map<String,String> unaffectedStatistics(Connection connection) throws Exception
    {
        Map<String,String> statistics = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT idx,stat FROM sqlite_stat1 WHERE tbl='receiver_activity_event' ORDER BY idx"))
        { while(rows.next()) if(!ENCRYPTED_INDEXES.contains(rows.getString(1))) statistics.put(rows.getString(1), rows.getString(2)); }
        return statistics;
    }

    private static Connection observe(Connection connection, Consumer<String> observer)
    { return observe(connection, observer, false); }

    private static Connection observe(Connection connection, Consumer<String> observer, boolean before)
    {
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
            (proxy, method, arguments) -> {
                Object result;
                try { result = method.invoke(connection, arguments); }
                catch(InvocationTargetException error) { throw error.getCause(); }
                if(!(result instanceof Statement statement)) return result;
                String prepared = method.getName().equals("prepareStatement") ? (String)arguments[0] : null;
                return Proxy.newProxyInstance(Statement.class.getClassLoader(),
                    new Class<?>[]{prepared != null ? PreparedStatement.class : Statement.class},
                    (statementProxy, operation, parameters) -> {
                        String sql = operation.getName().startsWith("execute") ?
                            parameters != null && parameters.length > 0 && parameters[0] instanceof String text ? text : prepared : null;
                        if(sql != null && before) observer.accept(sql);
                        Object value;
                        try { value = operation.invoke(statement, parameters); }
                        catch(InvocationTargetException error) { throw error.getCause(); }
                        if(sql != null && !before) observer.accept(sql);
                        return value;
                    });
            });
    }

    private static void assertIntegrity(Connection connection) throws Exception
    {
        try(Statement statement=connection.createStatement();var row=statement.executeQuery("PRAGMA integrity_check"))
        { assertTrue(row.next()); assertEquals("ok", row.getString(1)); }
    }

    private static Connection open(Path path) throws Exception
    { return DriverManager.getConnection("jdbc:sqlite:" + path); }
    private static long scalar(Connection connection, String sql) throws Exception
    { try(Statement statement=connection.createStatement();var row=statement.executeQuery(sql))
      { assertTrue(row.next()); return row.getLong(1); } }
    private static final class InjectedFailure extends RuntimeException {}

    private static boolean matchesPhase(String phase, String sql)
    {
        return switch(phase)
        {
            case "rename" -> sql.startsWith("ALTER TABLE activity.receiver_activity_event RENAME COLUMN");
            case "schema-update" -> sql.startsWith("UPDATE activity.sqlite_schema SET sql=");
            case "schema" -> sql.startsWith("PRAGMA activity.schema_version=");
            case "schema-off" -> sql.equals("PRAGMA writable_schema=OFF");
            case "update" -> sql.startsWith("UPDATE activity.receiver_activity_event SET encryption_state=");
            case "index" -> sql.startsWith("CREATE INDEX activity.");
            case "analysis" -> sql.startsWith("ANALYZE activity.");
            default -> false;
        };
    }
}
