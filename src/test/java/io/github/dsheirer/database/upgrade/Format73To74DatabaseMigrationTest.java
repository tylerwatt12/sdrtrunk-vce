/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Format73To74DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void addsNullableEvidenceWithoutRewritingEventsOrIndexesAndRollsBackForRetry() throws Exception
    {
        Path database = Format73TestDatabase.create(directory.resolve("prior.sqlite"));
        try(var c = DriverManager.getConnection("jdbc:sqlite:" + database);
            var attach = c.prepareStatement("ATTACH DATABASE ? AS activity"); var s = c.createStatement())
        {
            attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString()); attach.execute();
            var trees = eventTrees(c);
            long count = scalar(c,"SELECT count(*) FROM activity.receiver_activity_event");
            assertTrue(count > 0, "Prior fixture must contain retained events");
            c.setAutoCommit(false);
            new Format73To74DatabaseMigration().migrate(c); DatabaseFormatCatalog.stamp(c,74);
            assertEquals(74, DatabaseFormatCatalog.inspect(c).version());
            assertEquals(ConfigurationHistoryDatabase.FORMAT_74_HISTORY_FINGERPRINT, SqliteSchemaValidator.fingerprint(c,"activity"));
            assertEquals(trees, eventTrees(c));
            assertEquals(0,scalar(c,"SELECT count(*) FROM activity.receiver_activity_event WHERE denial_reason_code IS NOT NULL OR denial_reason_vendor_specific IS NOT NULL"));
            c.rollback();
            assertEquals(73, DatabaseFormatCatalog.inspect(c).version());
            assertEquals(ConfigurationHistoryDatabase.FORMAT_73_HISTORY_FINGERPRINT, SqliteSchemaValidator.fingerprint(c,"activity"));
            new Format73To74DatabaseMigration().migrate(c); DatabaseFormatCatalog.stamp(c,74); c.commit();
            assertEquals(trees,eventTrees(c));
            assertEquals(count,scalar(c,"SELECT count(*) FROM activity.receiver_activity_event"));
            List<String> plan=new ArrayList<>();
            try(var rows=s.executeQuery("EXPLAIN QUERY PLAN SELECT id,denial_reason_code,denial_reason_vendor_specific " +
                "FROM activity.receiver_activity_event WHERE channel_id=1 AND observed_at_ms>=1000 " +
                "ORDER BY observed_at_ms DESC,id DESC LIMIT 50"))
            { while(rows.next()) plan.add(rows.getString(4)); }
            assertTrue(plan.stream().anyMatch(detail->detail.contains("SEARCH") && detail.contains("INDEX")),plan.toString());
            assertFalse(plan.stream().anyMatch(detail->detail.contains("TEMP B-TREE")),plan.toString());
            long id=scalar(c,"SELECT min(id) FROM activity.receiver_activity_event");
            s.executeUpdate("UPDATE activity.receiver_activity_event SET action_code=9,denial_reason_code=0,denial_reason_vendor_specific=1 WHERE id="+id);
            try(var r=s.executeQuery("SELECT denial_reason_code,denial_reason_vendor_specific FROM activity.receiver_activity_event_resolved WHERE id="+id))
            { assertTrue(r.next()); assertEquals(0,r.getInt(1)); assertEquals(1,r.getInt(2)); }
            for(String assignments: List.of("denial_reason_code=256", "denial_reason_code=-1", "denial_reason_code=1.5",
                "denial_reason_vendor_specific=2", "denial_reason_vendor_specific=NULL", "denial_reason_code=NULL", "action_code=4"))
                assertThrows(SQLException.class,()->s.executeUpdate("UPDATE activity.receiver_activity_event SET "+assignments+" WHERE id="+id),assignments);
            c.rollback();
            try(var r=s.executeQuery("PRAGMA activity.quick_check")) { assertTrue(r.next()); assertEquals("ok",r.getString(1)); }
            try(var r=s.executeQuery("PRAGMA activity.foreign_key_check")) { assertFalse(r.next()); }
            assertEquals(74, DatabaseFormatCatalog.inspect(c).version());
            ConfigurationHistoryDatabase.validateAdmittedRole(c, ConfigurationHistorySchema.HISTORY_ROLE,
                SqliteSchemaValidator.fingerprint(c, "activity"), "activity");
        }
        ConfigurationHistoryDatabase.validatePairIdentity(database);
    }

    @Test void fullMigratorPreservesHistoryAndRetriesUnchangedSource() throws Exception
    {
        Path database=Format73TestDatabase.create(directory.resolve("retry.sqlite"));
        byte[] original=Files.readAllBytes(database);
        var unchanged=ActivityMigrationPreservation.original(database);
        var history=ActivityMigrationPreservation.capture(database);
        assertThrows(IllegalStateException.class,()->ApplicationDatabaseMigrator.migrateInPlace(database,phase->{
            if(phase.equals("Validating the updated schema and configuration")) throw new IllegalStateException("injected");
        }));
        assertArrayEquals(original,Files.readAllBytes(database));
        assertEquals(unchanged,ActivityMigrationPreservation.original(database));
        ApplicationDatabaseMigrator.migrateInPlace(database,null);
        ActivityMigrationPreservation.assertCurrent(database,history);
        byte[] current=Files.readAllBytes(database);
        byte[] currentActivity=Files.readAllBytes(ActivityDatabasePath.forConfiguration(database));
        ApplicationDatabaseMigrator.migrateInPlace(database,null);
        assertArrayEquals(current,Files.readAllBytes(database));
        assertArrayEquals(currentActivity,Files.readAllBytes(ActivityDatabasePath.forConfiguration(database)));
    }

    @Test void matchesNativeCheckedColumnsAndReopensWithHistoricalNullsAndNewDenials() throws Exception
    {
        Path database = Format73TestDatabase.create(directory.resolve("native-equivalent.sqlite"));
        long originalCount;
        try(var c = DriverManager.getConnection("jdbc:sqlite:" + database);
            var nativeSchema = DriverManager.getConnection("jdbc:sqlite::memory:");
            var attach = c.prepareStatement("ATTACH DATABASE ? AS activity");
            var statement = c.createStatement(); var nativeStatement = nativeSchema.createStatement())
        {
            attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString()); attach.execute();
            originalCount = scalar(c, "SELECT count(*) FROM activity.receiver_activity_event");
            nativeStatement.executeUpdate(eventDefinition(c, "activity"));
            nativeStatement.executeUpdate("ALTER TABLE receiver_activity_event ADD COLUMN denial_reason_code INTEGER " +
                "CHECK(denial_reason_code IS NULL OR (typeof(denial_reason_code) = 'integer' AND " +
                "denial_reason_code BETWEEN 0 AND 255 AND action_code = 9))");
            nativeStatement.executeUpdate("ALTER TABLE receiver_activity_event ADD COLUMN denial_reason_vendor_specific INTEGER " +
                "CHECK((denial_reason_code IS NULL AND denial_reason_vendor_specific IS NULL) OR " +
                "(denial_reason_code IS NOT NULL AND denial_reason_vendor_specific IS NOT NULL AND " +
                "typeof(denial_reason_vendor_specific) = 'integer' AND denial_reason_vendor_specific IN (0, 1)))");
            long channel = scalar(c, "SELECT channel_id FROM activity.receiver_activity_event LIMIT 1");
            try(var cached = c.prepareStatement("INSERT INTO activity.receiver_activity_event(" +
                "channel_id,observed_at_ms,action_code) VALUES(?,?,9)"))
            {
                cached.setLong(1, channel); cached.setLong(2, 1_700_000_000_000L);
                c.setAutoCommit(false);
                new Format73To74DatabaseMigration().migrate(c); DatabaseFormatCatalog.stamp(c, 74);
                SqliteSchemaValidator.validateDefinitions(c, List.of(new SqliteSchemaValidator.Definition(
                    "table", "receiver_activity_event", eventDefinition(nativeSchema, "main"))), "activity");
                assertEquals(columnLayout(nativeSchema, "main"), columnLayout(c, "activity"));
                assertEquals(0, scalar(c, "PRAGMA writable_schema"));
                assertEquals(1, cached.executeUpdate(), "An older prepared insert must see the appended NULL fields");
                statement.executeUpdate("INSERT INTO activity.receiver_activity_event(channel_id,observed_at_ms," +
                    "action_code,denial_reason_code,denial_reason_vendor_specific) VALUES(" + channel +
                    ",1700000000001,9,255,0)");
                c.commit();
            }
        }
        try(var reopened = DriverManager.getConnection("jdbc:sqlite:" + ActivityDatabasePath.forConfiguration(database));
            var statement = reopened.createStatement())
        {
            assertEquals(originalCount + 2, scalar(reopened, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(originalCount + 1, scalar(reopened, "SELECT count(*) FROM receiver_activity_event " +
                "WHERE denial_reason_code IS NULL AND denial_reason_vendor_specific IS NULL"));
            assertEquals(1, scalar(reopened, "SELECT count(*) FROM receiver_activity_event " +
                "WHERE action_code=9 AND denial_reason_code=255 AND denial_reason_vendor_specific=0"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO receiver_activity_event(" +
                "channel_id,observed_at_ms,action_code,denial_reason_code,denial_reason_vendor_specific) " +
                "SELECT channel_id,1700000000002,4,1,0 FROM receiver_activity_event LIMIT 1"));
            try(var rows = statement.executeQuery("PRAGMA quick_check"))
            { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
        }
        ConfigurationHistoryDatabase.validatePairIdentity(database);
    }

    @Test void nullableColumnMigrationDoesNotExecuteAHistorySizedConstraintScan() throws Exception
    {
        Path database = Format73TestDatabase.create(directory.resolve("bounded.sqlite"));
        try(var c = DriverManager.getConnection("jdbc:sqlite:" + database);
            var attach = c.prepareStatement("ATTACH DATABASE ? AS activity"); var statement = c.createStatement())
        {
            attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString()); attach.execute();
            c.setAutoCommit(false);
            statement.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<20000) " +
                "INSERT INTO activity.receiver_activity_event(channel_id,observed_at_ms,action_code) " +
                "SELECT (SELECT channel_id FROM activity.receiver_activity_event LIMIT 1),1000000+i,9 FROM n");
            c.commit();
            var step = new Format73To74DatabaseMigration();
            step.validateSource(c);
            var instructions = new java.util.concurrent.atomic.AtomicInteger();
            org.sqlite.ProgressHandler.setHandler(c, 1000, new org.sqlite.ProgressHandler()
            {
                @Override protected int progress() { return instructions.incrementAndGet() > 500 ? 1 : 0; }
            });
            try
            {
                // A native checked ADD scans every old event, even when CHECK enforcement is disabled.
                // Schema-only additions and validation fit a fixed VM budget independently of retained rows.
                assertDoesNotThrow(() -> step.migrateSource(c), "Nullable additions must not scan retained Activity");
            }
            finally { org.sqlite.ProgressHandler.clearHandler(c); }
            DatabaseFormatCatalog.stamp(c, 74); c.commit();
            assertEquals(ConfigurationHistoryDatabase.FORMAT_74_HISTORY_FINGERPRINT,
                SqliteSchemaValidator.fingerprint(c, "activity"));
            assertEquals(0, scalar(c, "SELECT count(*) FROM activity.receiver_activity_event " +
                "WHERE denial_reason_code IS NOT NULL OR denial_reason_vendor_specific IS NOT NULL"));
        }
    }

    private static String eventDefinition(Connection connection, String schema) throws SQLException
    {
        try(var statement = connection.createStatement(); var row = statement.executeQuery(
            "SELECT sql FROM " + schema + ".sqlite_schema WHERE type='table' AND name='receiver_activity_event'"))
        { assertTrue(row.next()); return row.getString(1); }
    }

    private static List<String> columnLayout(Connection connection, String schema) throws SQLException
    {
        List<String> result = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "PRAGMA " + schema + ".table_xinfo(receiver_activity_event)"))
        {
            while(rows.next()) result.add(rows.getInt("cid") + "|" + rows.getString("name") + "|" +
                rows.getString("type") + "|" + rows.getInt("notnull") + "|" + rows.getString("dflt_value") +
                "|" + rows.getInt("pk") + "|" + rows.getInt("hidden"));
        }
        return result;
    }

    private static Map<String,Long> eventTrees(Connection c) throws SQLException
    {
        Map<String,Long> result=new TreeMap<>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT name,rootpage FROM activity.sqlite_schema WHERE tbl_name='receiver_activity_event' AND type IN ('table','index') ORDER BY name"))
        { while(r.next()) result.put(r.getString(1),r.getLong(2)); }
        return result;
    }

    private static long scalar(Connection c,String sql) throws SQLException
    { try(var s=c.createStatement();var r=s.executeQuery(sql)) { assertTrue(r.next());return r.getLong(1); } }
}
