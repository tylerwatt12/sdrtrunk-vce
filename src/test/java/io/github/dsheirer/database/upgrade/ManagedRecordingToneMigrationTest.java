/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
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

class ManagedRecordingToneMigrationTest
{
    @TempDir Path directory;

    @Test void frozenPopulatedThreePreservesEveryValueAndAllocatorThroughCurrent() throws Exception
    {
        Path prior = createPrior(directory.resolve("prior.sqlite"));
        Map<String,List<String>> before;
        try(Connection connection = open(prior)) { before = contents(connection); ManagedRecordingSchema.validate(connection,3); }
        byte[] original = Files.readAllBytes(prior);
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED, ManagedRecordingCatalogMigrator.inspectForStartup(prior).state());
        assertArrayEquals(original, Files.readAllBytes(prior), "Startup inspection cannot migrate the catalog");
        try(Connection connection = open(prior))
        { assertThrows(SQLException.class, () -> ManagedRecordingSchema.validate(connection,4)); }
        var migrated = ManagedRecordingCatalogMigrator.migrate(prior);
        assertTrue(migrated.migrated()); assertNotNull(migrated.backup());
        try(Connection connection = open(prior))
        {
            ManagedRecordingSchema.validate(connection,ManagedRecordingSchema.CURRENT_FORMAT_VERSION); assertEquals(before, contents(connection));
            assertEquals(0, scalar(connection,"SELECT count(*) FROM recording_tone_sequence"));
            assertEquals(0, scalar(connection,"SELECT count(*) FROM recording_tone")); integrity(connection);
        }
        try(Connection backup = open(migrated.backup()))
        { ManagedRecordingSchema.validate(backup,3); assertEquals(before,contents(backup)); }
        assertFalse(ManagedRecordingCatalogMigrator.migrate(prior).migrated());
    }

    @Test void finalFaultRollsBackBothToneTablesAndAllowsExactRetry() throws Exception
    {
        Path prior = createPrior(directory.resolve("retry.sqlite")); Map<String,List<String>> before;
        try(Connection connection = open(prior)) { before = contents(connection); }
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(prior,false,
            connection -> { throw new SQLException("Injected current-format final boundary"); }, ManagedRecordingToneMigrationTest::open));
        try(Connection connection = open(prior))
        {
            ManagedRecordingSchema.validate(connection,3); assertEquals(before,contents(connection));
            assertEquals(0, scalar(connection,"SELECT count(*) FROM sqlite_schema WHERE name LIKE 'recording_tone%'"));
        }
        assertTrue(ManagedRecordingCatalogMigrator.migrate(prior,false).migrated());
        try(Connection connection = open(prior))
        { ManagedRecordingSchema.validate(connection,ManagedRecordingSchema.CURRENT_FORMAT_VERSION); assertEquals(before,contents(connection)); integrity(connection); }
    }

    @Test void mixedOrNewerCatalogIsRefusedBeforeMutation() throws Exception
    {
        Path prior = createPrior(directory.resolve("mixed.sqlite"));
        try(Connection connection = open(prior); var sql = connection.createStatement())
        { sql.execute("CREATE TABLE recording_tone_sequence(call_id INTEGER PRIMARY KEY)"); }
        byte[] original = Files.readAllBytes(prior);
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.inspectForStartup(prior));
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(prior,false));
        assertArrayEquals(original, Files.readAllBytes(prior));
        Path newer = createPrior(directory.resolve("newer.sqlite"));
        try(Connection connection = open(newer); var sql = connection.createStatement())
        { sql.execute("PRAGMA user_version=" + (ManagedRecordingSchema.CURRENT_FORMAT_VERSION + 1)); }
        byte[] future = Files.readAllBytes(newer);
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(newer,false));
        assertArrayEquals(future, Files.readAllBytes(newer));
    }

    private static Path createPrior(Path path) throws Exception
    {
        byte[] frozen;
        try(var input = ManagedRecordingToneMigrationTest.class.getResourceAsStream("/database/managed-recordings-format-3.sql"))
        { assertNotNull(input); frozen = input.readAllBytes(); }
        assertEquals("f50affd681c9a5006940cd4dde721f3070e7e25a64a351e2dcc39a35ab91d611",
            java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(frozen)), "Independent frozen prior signature");
        try(Connection connection = open(path); var sql = connection.createStatement())
        {
            sql.execute("PRAGMA foreign_keys=ON");
            for(String definition: new String(frozen,StandardCharsets.UTF_8).lines().toList()) sql.execute(definition);
            sql.execute("PRAGMA application_id=" + ManagedRecordingSchema.APPLICATION_ID); sql.execute("PRAGMA user_version=3");
            sql.executeUpdate("INSERT INTO catalog_metadata VALUES(1,3,2,128)");
            sql.executeUpdate("INSERT INTO recording_system VALUES(11,'p25:bee00:123')");
            sql.executeUpdate("INSERT INTO recording_channel VALUES(22,'123e4567-e89b-42d3-a456-426614174000')");
            sql.executeUpdate("INSERT INTO recording_site VALUES(33,781824,291,1,2)");
            sql.executeUpdate("INSERT INTO recording_system_site VALUES(11,33)");
            sql.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes,system_id,channel_id,protocol,call_type,voice_type,tone_kind,tone,winner_site_id,transcription_status) VALUES(41,1000,2000,1000,'fixture/one.mp3',64,11,22,1,1,1,2,'023',33,'complete'),(42,2000,3000,1000,'fixture/two.wav',64,11,22,1,1,1,NULL,NULL,33,'failed')");
            sql.executeUpdate("INSERT INTO recording_call_site VALUES(41,33,1000)");
            sql.executeUpdate("INSERT INTO recording_patch_member VALUES(41,1,501,781824,291,501,1000)");
            sql.executeUpdate("INSERT INTO recording_transcript VALUES(41,'fixture transcript retained verbatim',4000)");
            sql.executeUpdate("UPDATE sqlite_sequence SET seq=9000 WHERE name='recording_call'");
            ManagedRecordingSchema.validate(connection,3); integrity(connection);
        }
        return path;
    }
    private static Map<String,List<String>> contents(Connection connection) throws Exception
    {
        var result = new LinkedHashMap<String,List<String>>(); var tables = new ArrayList<String>();
        try(var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT name FROM sqlite_schema WHERE type='table' AND name NOT IN ('recording_tone','recording_tone_sequence') ORDER BY name"))
        { while(rows.next()) tables.add(rows.getString(1)); }
        for(String table: tables)
        {
            var values = new ArrayList<String>();
            try(var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT * FROM \"" + table + "\""))
            {
                while(rows.next())
                {
                    var row = new ArrayList<String>();
                    for(int index=1; index<=rows.getMetaData().getColumnCount(); index++)
                        if(!table.equals("catalog_metadata") || index != 2) row.add(String.valueOf(rows.getObject(index)));
                    values.add(row.toString());
                }
            }
            Collections.sort(values); result.put(table,values);
        }
        result.putIfAbsent("recording_identifier", List.of());
        return result;
    }
    private static long scalar(Connection c,String query) throws Exception
    { try(var sql=c.createStatement(); var rows=sql.executeQuery(query)) { assertTrue(rows.next()); return rows.getLong(1); } }
    private static void integrity(Connection c) throws Exception
    {
        try(var sql=c.createStatement();var rows=sql.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
        try(var sql=c.createStatement();var rows=sql.executeQuery("PRAGMA quick_check")) { assertTrue(rows.next());assertEquals("ok",rows.getString(1)); }
    }
    private static Connection open(Path path) throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + path); }
}
