/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.alerts.TopicAlertSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
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

class Format58To59DatabaseMigrationTest
{
    private static final String CALL = "8a220f1a-7ddc-4182-8dd0-fb60506aeb51";
    @TempDir Path directory;

    @Test void freshSchemaMatchesRegisteredSignatureAndActivityDefinition() throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite::memory:"))
        {
            SdrTrunkDatabaseSchema.create(connection); ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection); TrunkedSiteSchema.create(connection); TopicAlertSchema.create(connection);
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test void populatedMigrationPreservesRowsReferencesAndAllocatorsWithUnknownHistoricalDuration() throws Exception
    {
        Path database = Format58TestDatabase.create(directory.resolve("prior.sqlite"));
        try(var connection = open(database); var sql = connection.createStatement())
        {
            sql.execute("ANALYZE");
            Map<String,List<String>> before = contents(connection);
            assertTrue(count(connection, "receiver_activity_event") > 0);
            assertTrue(count(connection, "topic_alert_message") > 0);
            connection.setAutoCommit(false);
            var report = DatabaseMigrationChain.migrate(connection);
            assertEquals(List.of("format-58-to-59"), report.steps().stream().map(DatabaseMigrationChain.StepReport::id).toList());
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(before, contents(connection));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE duration_ms IS NOT NULL"));
            assertTrue(scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE source_call_uuid IS NOT NULL") > 0);
            assertTrue(scalar(connection, "SELECT count(*) FROM topic_alert_message WHERE source_call_uuid IS NOT NULL") > 0);
            connection.rollback();
            assertEquals(58, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, contents(connection));
            DatabaseMigrationChain.migrate(connection); connection.commit();
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals(before, contents(connection));
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
            integrity(connection);
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
    }

    @Test void interruptedAdjacentStepRollsBackAndCanRetryFromTheUnchangedSource() throws Exception
    {
        Path database = Format58TestDatabase.create(directory.resolve("interrupted.sqlite"));
        byte[] before = Files.readAllBytes(database);
        try(var connection = open(database))
        {
            connection.setAutoCommit(false);
            ReceiverActivitySchema.addCallDuration(connection);
            connection.rollback();
            assertEquals(58, DatabaseFormatCatalog.inspect(connection).version());
        }
        assertArrayEquals(before, Files.readAllBytes(database));
        try(var connection = open(database))
        {
            connection.setAutoCommit(false); DatabaseMigrationChain.migrate(connection); connection.commit();
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version()); integrity(connection);
        }
    }

    @Test void durationIsNullableNonnegativeIntegerCallOnlyAndExactReferenceUpdateRemainsIndexed() throws Exception
    {
        Path database = Format59TestDatabase.create(directory.resolve("duration.sqlite"));
        try(var connection = open(database); var sql = connection.createStatement())
        {
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE duration_ms IS NOT NULL"));
            assertTrue(sql.executeUpdate("UPDATE receiver_activity_event SET duration_ms=0 WHERE action_code=4") > 0);
            assertTrue(sql.executeUpdate("UPDATE receiver_activity_event SET duration_ms=1234 WHERE action_code=4") > 0);
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE receiver_activity_event SET duration_ms=-1 WHERE action_code=4"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE receiver_activity_event SET duration_ms=1.5 WHERE action_code=4"));
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE receiver_activity_event SET duration_ms='unknown' WHERE action_code=4"));
            sql.executeUpdate("INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code,event_type_code) " +
                "SELECT channel_id,observed_at_ms+1,1,event_type_code FROM receiver_activity_event LIMIT 1");
            assertThrows(SQLException.class, () -> sql.executeUpdate("UPDATE receiver_activity_event SET duration_ms=1 WHERE action_code=1"));
            sql.executeUpdate("WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<5000) " +
                "INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code,event_type_code,source_call_uuid) " +
                "SELECT (SELECT channel_id FROM receiver_activity_event LIMIT 1),2000000000000+value,4,7," +
                "printf('%08x-7ddc-4182-8dd0-fb60506aeb51',value) FROM n");
            sql.execute("ANALYZE");
            //Production exact-call enrichment uses the existing sparse UUID index, not a retained-history scan.
            try(var plan = sql.executeQuery("EXPLAIN QUERY PLAN UPDATE receiver_activity_event SET duration_ms=1234 " +
                "WHERE source_call_uuid='" + CALL + "' AND action_code=4 AND channel_id=" +
                "(SELECT id FROM receiver_channel WHERE configuration_id='00000000-0000-0000-0000-000000000001')"))
            {
                List<String> details = new ArrayList<>();
                while(plan.next()) details.add(plan.getString("detail"));
                assertTrue(details.stream().anyMatch(detail -> detail.contains("idx_receiver_activity_event_source_call")), details.toString());
                assertTrue(details.stream().noneMatch(detail -> detail.contains("SCAN receiver_activity_event")), details.toString());
                assertTrue(details.stream().anyMatch(detail -> detail.contains("configuration_id")), details.toString());
            }
            integrity(connection);
        }
    }

    @Test void startupRefusesPriorFormatWithoutChangingItsBytes() throws Exception
    {
        Path database = Format58TestDatabase.create(directory.resolve("startup.sqlite"));
        byte[] before = Files.readAllBytes(database);
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(database));
        assertArrayEquals(before, Files.readAllBytes(database));
    }

    private static Connection open(Path path) throws SQLException
    {
        return DriverManager.getConnection("jdbc:sqlite:" + path);
    }

    private static long count(Connection connection, String table) throws SQLException
    {
        return scalar(connection, "SELECT count(*) FROM " + table);
    }

    private static long scalar(Connection connection, String query) throws SQLException
    {
        try(var statement = connection.createStatement(); var row = statement.executeQuery(query))
        { assertTrue(row.next()); return row.getLong(1); }
    }

    private static void integrity(Connection connection) throws SQLException
    {
        try(var sql = connection.createStatement(); var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
        try(var sql = connection.createStatement(); var rows = sql.executeQuery("PRAGMA integrity_check"))
        { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
    }

    /** Compare every old column, including statistics and sqlite_sequence, ignoring only the marker and new fields. */
    private static Map<String,List<String>> contents(Connection connection) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        try(var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        { while(rows.next()) tables.add(rows.getString(1)); }
        for(String table: tables)
        {
            List<String> values = new ArrayList<>();
            try(var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT * FROM \"" + table + "\""))
            {
                while(rows.next())
                {
                    if(table.equals("database_metadata") && DatabaseFormatCatalog.FORMAT_VERSION_KEY.equals(rows.getString(1))) continue;
                    List<String> row = new ArrayList<>();
                    for(int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                    {
                        if(table.equals("receiver_activity_event") && "duration_ms".equals(rows.getMetaData().getColumnName(i))) continue;
                        Object value = rows.getObject(i);
                        row.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value));
                    }
                    values.add(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8))));
                }
            }
            values.sort(String::compareTo); result.put(table, values);
        }
        return result;
    }
}
