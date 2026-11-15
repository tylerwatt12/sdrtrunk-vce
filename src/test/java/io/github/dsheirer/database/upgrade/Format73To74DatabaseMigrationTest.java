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
            assertEquals(ConfigurationHistoryDatabase.HISTORY_FINGERPRINT, SqliteSchemaValidator.fingerprint(c,"activity"));
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
        }
        ConfigurationHistoryDatabase.validatePair(database);
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
