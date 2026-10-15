/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.record.managed.ManagedRecordingSchema;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingTranscriptMigrationTest
{
    @TempDir Path temporary;

    @Test
    void frozenSevenBackfillsTheDiskIndexAndPreservesAllSourceFactsWithBackupAndRetry() throws Exception
    {
        Path db=fixture(temporary.resolve("seven.sqlite"));
        Map<String,List<String>> before;
        try(var c=open(db)) { before=contents(c); }
        byte[] original=Files.readAllBytes(db);
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,ManagedRecordingCatalogMigrator.inspectForStartup(db).state());
        assertThrows(SQLException.class,()->new ManagedRecordingCatalog(db,temporary));
        assertArrayEquals(original,Files.readAllBytes(db));
        var result=ManagedRecordingCatalogMigrator.migrate(db);
        assertTrue(result.migrated()); assertNotNull(result.backup());
        try(var c=open(db))
        {
            ManagedRecordingSchema.validate(c,8); assertEquals(before,contents(c));
            assertEquals(1,matches(c,"fixture AND retained"));
            assertEquals(9000,scalar(c,"SELECT seq FROM sqlite_sequence WHERE name='recording_call'"));
            integrity(c);
        }
        try(var c=open(result.backup())) { ManagedRecordingSchema.validate(c,7); assertEquals(before,contents(c)); }
        Path retry=temporary.resolve("retry.sqlite");Files.copy(result.backup(),retry);
        assertTrue(ManagedRecordingCatalogMigrator.migrate(retry,false).migrated());
        Path fresh=temporary.resolve("fresh.sqlite");
        try(var ignored=new ManagedRecordingCatalog(fresh,temporary)) { }
        try(var a=open(db);var b=open(retry);var c=open(fresh))
        {
            assertEquals(schema(c),schema(a));assertEquals(schema(a),schema(b));assertEquals(before,contents(b));
        }
        assertFalse(ManagedRecordingCatalogMigrator.migrate(db,false).migrated());
    }

    @Test
    void failedBackfillTransactionRollsBackAndRetriesFromUnchangedSeven() throws Exception
    {
        for(boolean backup:List.of(false,true))
        {
            Path db=fixture(temporary.resolve("rollback-"+backup+".sqlite"));
            byte[] before=Files.readAllBytes(db);
            assertThrows(SQLException.class,()->ManagedRecordingCatalogMigrator.migrate(db,backup,
                connection->{throw new SQLException("injected final validation failure");},ManagedRecordingTranscriptMigrationTest::open));
            assertArrayEquals(before,Files.readAllBytes(db));
            try(var c=open(db)) { ManagedRecordingSchema.validate(c,7); }
            assertTrue(ManagedRecordingCatalogMigrator.migrate(db,false).migrated());
            try(var c=open(db)) { ManagedRecordingSchema.validate(c,8);assertEquals(1,matches(c,"fixture")); }
        }
    }

    @Test
    void transcriptInsertUpdateDeleteAndCallCascadeKeepExternalContentIndexSynchronized() throws Exception
    {
        Path db=fixture(temporary.resolve("triggers.sqlite"));ManagedRecordingCatalogMigrator.migrate(db,false);
        try(var c=open(db);var s=c.createStatement())
        {
            s.executeUpdate("INSERT INTO recording_transcript VALUES(42,'fire alarm',5000)");
            assertEquals(1,matches(c,"fire"));
            s.executeUpdate("UPDATE recording_transcript SET text='medical assist',stored_at_ms=6000 WHERE call_id=42");
            assertEquals(0,matches(c,"fire"));assertEquals(1,matches(c,"medical"));
            s.execute("BEGIN");s.executeUpdate("DELETE FROM recording_transcript WHERE call_id=42");
            assertEquals(0,matches(c,"medical"));s.execute("ROLLBACK");assertEquals(1,matches(c,"medical"));
            s.executeUpdate("DELETE FROM recording_call WHERE id=42");assertEquals(0,matches(c,"medical"));
            integrity(c);
            s.execute("DROP TRIGGER recording_transcript_fts_insert");
        }
        byte[] before=Files.readAllBytes(db);
        assertThrows(SQLException.class,()->ManagedRecordingCatalogMigrator.inspectForStartup(db));
        assertThrows(SQLException.class,()->new ManagedRecordingCatalog(db,temporary));
        assertArrayEquals(before,Files.readAllBytes(db));
    }

    private Path fixture(Path db) throws Exception
    {
        byte[] sql;
        try(var input=getClass().getResourceAsStream("/database/managed-recordings-format-7.sql"))
        { assertNotNull(input);sql=input.readAllBytes(); }
        assertEquals("f0d9fbe328c5c65fb67eff98028096bda5a7c81eb2095023f6b264aa5c406bf2",
            java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(sql)));
        try(var c=open(db);var s=c.createStatement())
        {
            for(String statement:new String(sql,StandardCharsets.UTF_8).lines().toList()) s.execute(statement);
            ManagedRecordingSchema.validate(c,7);
        }
        return db;
    }
    private static Connection open(Path path) throws SQLException
    {
        Connection c=DriverManager.getConnection("jdbc:sqlite:"+path);
        try(var s=c.createStatement()) {s.execute("PRAGMA foreign_keys=ON");}return c;
    }
    private static long matches(Connection c,String text) throws SQLException
    {
        try(var s=c.prepareStatement("SELECT count(*) FROM recording_transcript_fts WHERE recording_transcript_fts MATCH ?"))
        {s.setString(1,text);try(var r=s.executeQuery()){assertTrue(r.next());return r.getLong(1);}}
    }
    private static long scalar(Connection c,String sql) throws SQLException
    {try(var s=c.createStatement();var r=s.executeQuery(sql)){assertTrue(r.next());return r.getLong(1);}}
    private static Map<String,List<String>> contents(Connection c) throws SQLException
    {
        Map<String,List<String>> all=new LinkedHashMap<>();List<String> tables=new ArrayList<>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' AND name NOT GLOB 'recording_transcript_fts*' ORDER BY name"))
        {while(r.next())tables.add(r.getString(1));}
        for(String table:tables)
        {
            List<String> rows=new ArrayList<>();
            try(var s=c.createStatement();var r=s.executeQuery("SELECT * FROM \""+table+"\""))
            {while(r.next()){List<String> values=new ArrayList<>();for(int i=1;i<=r.getMetaData().getColumnCount();i++)
                if(!table.equals("catalog_metadata")||i!=2)values.add(String.valueOf(r.getObject(i)));rows.add(values.toString());}}
            Collections.sort(rows);all.put(table,rows);
        }
        return all;
    }
    private static Map<String,String> schema(Connection c) throws SQLException
    {
        Map<String,String> all=new LinkedHashMap<>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT name,sql FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*' ORDER BY name"))
        {while(r.next())all.put(r.getString(1),r.getString(2));}return all;
    }
    private static void integrity(Connection c) throws SQLException
    {
        try(var s=c.createStatement())
        {
            s.execute("INSERT INTO recording_transcript_fts(recording_transcript_fts,rank) VALUES('integrity-check',1)");
            try(var r=s.executeQuery("PRAGMA foreign_key_check")){assertFalse(r.next());}
            try(var r=s.executeQuery("PRAGMA quick_check")){assertTrue(r.next());assertEquals("ok",r.getString(1));}
        }
    }
}
