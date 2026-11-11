/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.*;
import java.nio.file.Path;
import java.nio.file.Files;
import java.sql.DriverManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class Format72To73DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void preservesBsiWithExactOrUnknownReceptionTimeAndRollsBackForRetry() throws Exception
    {
        Path database = Format72TestDatabase.create(directory.resolve("prior.sqlite"));
        try(var c = DriverManager.getConnection("jdbc:sqlite:" + database);
            var attach = c.prepareStatement("ATTACH DATABASE ? AS activity"); var s = c.createStatement())
        {
            attach.setString(1, ActivityDatabasePath.forConfiguration(database).toString()); attach.execute();
            long id;
            try(var rows = s.executeQuery("SELECT min(id) FROM activity.receiver_channel"))
            { assertTrue(rows.next()); id = rows.getLong(1); assertTrue(id > 0); }
            s.executeUpdate("INSERT OR IGNORE INTO activity.p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms) VALUES("+id+",1000,1000)");
            s.executeUpdate("INSERT OR REPLACE INTO activity.p25_site_channel_summary(channel_id,channel_key,downlink_hz,callsign,first_seen_ms,last_seen_ms) VALUES("+id+",'bsi-test',851012500,'WKSD533',1000,9000)");
            s.executeUpdate("INSERT OR REPLACE INTO activity.p25_site_channel_tag_summary(channel_id,channel_key,tag,first_seen_ms,last_seen_ms,observation_count) VALUES("+id+",'bsi-test','CWID',1000,2000,3)");
            s.executeUpdate("INSERT OR REPLACE INTO activity.p25_site_channel_summary(channel_id,channel_key,downlink_hz,callsign,first_seen_ms,last_seen_ms) VALUES("+id+",'bsi-unknown',851025000,'WTEST77',1000,9000)");
            s.executeUpdate("UPDATE alias SET icon_name='CWID' WHERE id=(SELECT min(id) FROM alias)");
            c.setAutoCommit(false);
            new Format72To73DatabaseMigration().migrate(c);
            DatabaseFormatCatalog.stamp(c,73);
            assertEquals(73,DatabaseFormatCatalog.inspect(c).version());
            c.rollback();
            assertEquals(72,DatabaseFormatCatalog.inspect(c).version());
            try(var rows=s.executeQuery("SELECT callsign FROM activity.p25_site_channel_summary WHERE channel_key='bsi-test'"))
            { assertTrue(rows.next()); assertEquals("WKSD533",rows.getString(1)); }
            new Format72To73DatabaseMigration().migrate(c); DatabaseFormatCatalog.stamp(c,73); c.commit();
            try(var rows=s.executeQuery("SELECT callsign,last_heard_ms,source FROM activity.p25_site_bsi WHERE frequency_hz=851012500"))
            { assertTrue(rows.next()); assertEquals("WKSD533",rows.getString(1)); assertEquals(2000,rows.getLong(2)); assertEquals(0,rows.getInt(3)); }
            try(var rows=s.executeQuery("SELECT last_heard_ms FROM activity.p25_site_bsi WHERE frequency_hz=851025000"))
            { assertTrue(rows.next()); assertNull(rows.getObject(1)); }
            try(var rows=s.executeQuery("SELECT observation_count FROM activity.p25_site_channel_tag_summary WHERE channel_key='bsi-test' AND tag='BSI'"))
            { assertTrue(rows.next()); assertEquals(3,rows.getInt(1)); }
            assertEquals(ConfigurationHistoryDatabase.HISTORY_FINGERPRINT,SqliteSchemaValidator.fingerprint(c,"activity"));
            try(var rows=s.executeQuery("PRAGMA activity.quick_check")) { assertTrue(rows.next()); assertEquals("ok",rows.getString(1)); }
            try(var rows=s.executeQuery("PRAGMA activity.foreign_key_check")) { assertFalse(rows.next()); }
        }
        ConfigurationHistoryDatabase.validatePair(database);
    }

    @Test void fullMigratorPreservesUnaffectedActivityAndRetriesTheUnchangedSource() throws Exception
    {
        Path database=Format72TestDatabase.create(directory.resolve("retry.sqlite"));
        var original=Files.readAllBytes(database);
        var history=ActivityMigrationPreservation.capture(database);
        assertThrows(IllegalStateException.class,()->ApplicationDatabaseMigrator.migrateInPlace(database, phase->{
            if(phase.equals("Validating the updated schema and configuration")) throw new IllegalStateException("injected");
        }));
        assertArrayEquals(original,Files.readAllBytes(database));
        assertEquals(history,ActivityMigrationPreservation.capture(database));
        ApplicationDatabaseMigrator.migrateInPlace(database,null);
        ActivityMigrationPreservation.assertCurrent(database,history);
        byte[] current=Files.readAllBytes(database);
        ApplicationDatabaseMigrator.migrateInPlace(database,null);
        assertArrayEquals(current,Files.readAllBytes(database));
    }
}
