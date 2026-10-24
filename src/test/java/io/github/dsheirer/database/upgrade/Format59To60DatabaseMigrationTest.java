/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.alerts.TopicAlertSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.ActivityMessageCatalog;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format59To60DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void compareFreshAndMigratedDefinitions() throws Exception
    {
        Path database = Format59TestDatabase.create(directory.resolve("definitions.sqlite"));
        Path historical = Format60TestDatabase.create(directory.resolve("format-60.sqlite"));
        try(var fresh = open(historical); var migrated = open(database))
        {
            migrated.setAutoCommit(false); ReceiverActivitySchema.upgradeFrom59(migrated);
            assertEquals(SqliteSchemaValidator.fingerprint(fresh), SqliteSchemaValidator.fingerprint(migrated));
        }
    }

    @Test void freshCreationMatchesTheRegisteredSignature() throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite::memory:"))
        {
            SdrTrunkDatabaseSchema.create(connection); ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection); TrunkedSiteSchema.create(connection); TopicAlertSchema.create(connection);
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            ReceiverActivitySchema.validate(connection);
            assertEquals(113, ActivityMessageCatalog.read(connection).size());
        }
    }

    @Test void populatedMigrationPreservesEveryOldFieldAndCanRollbackAndRetry() throws Exception
    {
        Path database = Format59TestDatabase.create(directory.resolve("prior.sqlite"));
        try(var connection = open(database); var statement = connection.createStatement())
        {
            statement.execute("ANALYZE");
            Map<String,List<String>> columns = columns(connection);
            Map<String,List<String>> before = snapshot(connection, columns);
            connection.setAutoCommit(false);
            assertEquals(List.of("format-59-to-60", "format-60-to-61"), DatabaseMigrationChain.migrate(connection).steps().stream()
                .map(DatabaseMigrationChain.StepReport::id).toList());
            assertEquals(before, snapshot(connection, columns));
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            for(String table: List.of("conventional_activity_summary", "conventional_activity_bucket", "trunked_signaling_activity_bucket"))
                assertEquals(0, scalar(connection, "SELECT coalesce(sum(stun_count+revive_count+kill_count+radio_inhibit_count+" +
                    "radio_uninhibit_count+remote_monitor_count+authenticate_count+cancel_call_count+radio_detach_count+" +
                    "protect_count+vote_now_count),0) FROM " + table));
            connection.rollback();
            assertEquals(59, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, snapshot(connection, columns));
            DatabaseMigrationChain.migrate(connection); connection.commit();
            assertEquals(before, snapshot(connection, columns));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
            ReceiverActivitySchema.validate(connection);
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
            integrity(connection);
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
    }

    @Test void startupRefusesPriorWithoutChangingItAndInterruptedUpgradeLeavesTheSourceRetryable() throws Exception
    {
        Path database = Format59TestDatabase.create(directory.resolve("interrupted.sqlite"));
        byte[] before = Files.readAllBytes(database);
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
        assertArrayEquals(before, Files.readAllBytes(database));
        try(var connection = open(database))
        {
            connection.setAutoCommit(false); ReceiverActivitySchema.upgradeFrom59(connection); connection.rollback();
            assertEquals(59, DatabaseFormatCatalog.inspect(connection).version());
        }
        assertArrayEquals(before, Files.readAllBytes(database));
        try(var connection = open(database))
        {
            connection.setAutoCommit(false); DatabaseMigrationChain.migrate(connection); connection.commit();
            DatabaseFormatCatalog.requireCurrent(connection); integrity(connection);
        }
    }

    @Test void populatedEventConstraintRelaxationPreservesBtreesAndReloadsCachedStatements() throws Exception
    {
        Path database = Format59TestDatabase.create(directory.resolve("event-btrees.sqlite"));
        try(var connection = open(database); var statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            connection.setAutoCommit(false);
            statement.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<10000) " +
                "INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code,event_type_code) " +
                "SELECT (SELECT channel_id FROM receiver_activity_event LIMIT 1),100000+i,1,1 FROM n");
            // A deleted high ID proves that preserving max(id) alone would lose the allocator state.
            statement.executeUpdate("INSERT INTO receiver_activity_event(id,channel_id,observed_at_ms,action_code,event_type_code) " +
                "SELECT 9000000001,channel_id,observed_at_ms,1,1 FROM receiver_activity_event LIMIT 1");
            statement.executeUpdate("DELETE FROM receiver_activity_event WHERE id=9000000001");
            statement.execute("ANALYZE");
            connection.commit();
            var fields = columns(connection);
            var before = snapshot(connection, fields);
            var btrees = eventBtrees(connection);
            try(var cached = connection.prepareStatement("INSERT INTO receiver_activity_event(" +
                "channel_id,observed_at_ms,action_code,event_type_code) SELECT channel_id,observed_at_ms+1,?,? " +
                "FROM receiver_activity_event LIMIT 1"))
            {
                cached.setInt(1, 25); cached.setInt(2, 63);
                assertThrows(SQLException.class, cached::executeUpdate);
                DatabaseMigrationChain.migrate(connection);
                assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
                assertEquals(before, snapshot(connection, fields), "Rows, statistics and deleted-ID allocator survive.");
                assertEquals(btrees, eventBtrees(connection), "No event table or index b-tree is rebuilt.");
                assertEquals(0, scalar(connection, "PRAGMA writable_schema"));
                assertEquals(1, cached.executeUpdate(), "The previously prepared statement sees the relaxed CHECKs.");
                cached.setInt(1, 999); cached.setInt(2, 63);
                assertThrows(SQLException.class, cached::executeUpdate);
                cached.setInt(1, 25); cached.setInt(2, 999);
                assertThrows(SQLException.class, cached::executeUpdate);
                connection.rollback();
                assertEquals(59, DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(before, snapshot(connection, fields));
                assertEquals(btrees, eventBtrees(connection));
                cached.setInt(1, 25); cached.setInt(2, 63);
                assertThrows(SQLException.class, cached::executeUpdate, "Rollback restores the old cached CHECKs.");
                DatabaseMigrationChain.migrate(connection);
                connection.commit();
                assertEquals(btrees, eventBtrees(connection));
                assertEquals(1, cached.executeUpdate());
                connection.commit();
                integrity(connection);
            }
        }
        try(var connection = open(database))
        {
            DatabaseFormatCatalog.requireCurrent(connection);
            ReceiverActivitySchema.validate(connection);
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE action_code=25 AND event_type_code=63"));
            assertEquals(9000000002L, scalar(connection, "SELECT max(id) FROM receiver_activity_event"));
            integrity(connection);
        }
    }

    private static Map<String,Long> eventBtrees(Connection connection) throws SQLException
    {
        Map<String,Long> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT name,rootpage FROM sqlite_schema WHERE tbl_name='receiver_activity_event' " +
                "AND type IN ('table','index') ORDER BY name"))
        { while(rows.next()) result.put(rows.getString(1), rows.getLong(2)); }
        return result;
    }

    @Test void friendlyDatabaseTextIsAuthoritativeWhileStableCodesAndKindsAreValidated() throws Exception
    {
        Path database = Format60TestDatabase.create(directory.resolve("catalogue.sqlite"));
        try(var connection = open(database); var statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE activity_message_type SET label='Radio disabled temporarily'," +
                "meaning='A temporary disable command was observed.' WHERE definition_kind='EVENT' AND name='STUN'");
            assertEquals(60, DatabaseFormatCatalog.inspect(connection).version()); ReceiverActivitySchema.validate(connection);
            assertEquals("Radio disabled temporarily", ActivityMessageCatalog.read(connection).stream()
                .filter(row -> row.get("definition_kind").equals("EVENT") && row.get("name").equals("STUN"))
                .findFirst().orElseThrow().get("label"));
            assertEquals(25, ActivityMessageCatalog.codes(connection).get("ACTION").get("STUN"));
            assertEquals(63, ActivityMessageCatalog.codes(connection).get("EVENT").get("STUN"));
            statement.executeUpdate("INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code,event_type_code) " +
                "SELECT channel_id,observed_at_ms+1,25,63 FROM receiver_activity_event LIMIT 1");
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event_resolved WHERE action='STUN' AND event_type='STUN'"));
            statement.executeUpdate("UPDATE activity_message_type SET name='BROKEN' WHERE definition_kind='EVENT' AND code=63");
            assertThrows(SQLException.class, () -> ActivityMessageCatalog.validate(connection));
        }
    }

    private static Connection open(Path path) throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + path); }
    private static long scalar(Connection connection, String sql) throws SQLException
    { try(var statement = connection.createStatement(); var row = statement.executeQuery(sql)) { assertTrue(row.next()); return row.getLong(1); } }
    private static void integrity(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA integrity_check"))
        { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
    }
    private static Map<String,List<String>> columns(Connection connection) throws SQLException
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var tables = statement.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        {
            while(tables.next())
            {
                String table = tables.getString(1); List<String> fields = new ArrayList<>();
                try(var info = connection.createStatement(); var rows = info.executeQuery("PRAGMA table_info(\"" + table + "\")"))
                { while(rows.next()) fields.add(rows.getString("name")); }
                result.put(table, List.copyOf(fields));
            }
        }
        return result;
    }
    private static Map<String,List<String>> snapshot(Connection connection, Map<String,List<String>> tables) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        for(var table: tables.entrySet())
        {
            List<String> values = new ArrayList<>();
            String fields = table.getValue().stream().map(field -> "\"" + field + "\"").collect(java.util.stream.Collectors.joining(","));
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT " + fields + " FROM \"" + table.getKey() + "\""))
            {
                while(rows.next())
                {
                    if(table.getKey().equals("database_metadata") && DatabaseFormatCatalog.FORMAT_VERSION_KEY.equals(rows.getString(1))) continue;
                    List<String> row = new ArrayList<>();
                    for(int i=1; i<=table.getValue().size(); i++)
                    { Object value=rows.getObject(i); row.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value)); }
                    values.add(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                }
            }
            values.sort(String::compareTo); result.put(table.getKey(), values);
        }
        return result;
    }
}
