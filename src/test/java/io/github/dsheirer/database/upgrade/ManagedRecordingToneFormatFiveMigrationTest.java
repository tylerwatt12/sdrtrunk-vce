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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingToneFormatFiveMigrationTest
{
    @TempDir Path directory;

    @Test
    void frozenPopulatedFourPreservesAllFactsRelationshipsAndAllocatorsThroughCurrent() throws Exception
    {
        Path catalog = fixture(directory.resolve("prior.sqlite"));
        Map<String,List<String>> before;
        try(Connection connection = open(catalog))
        {
            ManagedRecordingSchema.validate(connection, 4);
            before = contents(connection);
            try(var statement = connection.createStatement())
            {
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO recording_tone_sequence VALUES(43,1,3,1,4000,4000,5000,1)"));
            }
        }
        byte[] original = Files.readAllBytes(catalog);
        assertEquals(ManagedRecordingCatalogMigrator.State.UPGRADE_REQUIRED,
            ManagedRecordingCatalogMigrator.inspectForStartup(catalog).state());
        assertArrayEquals(original, Files.readAllBytes(catalog), "Inspection leaves the frozen format4 file unchanged");
        var result = ManagedRecordingCatalogMigrator.migrate(catalog);
        assertTrue(result.migrated());
        assertNotNull(result.backup());
        Map<String,String> migratedSignature;
        try(Connection connection = open(catalog))
        {
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            assertEquals(before, contents(connection), "Only the catalog format marker changes");
            assertEquals(2, scalar(connection, "SELECT count(*) FROM recording_tone_sequence"));
            assertEquals(3, scalar(connection, "SELECT count(*) FROM recording_tone"));
            assertEquals(566379, scalar(connection, "SELECT frequency_1_millihz FROM recording_tone WHERE call_id=41"));
            assertEquals(9000, scalar(connection, "SELECT seq FROM sqlite_sequence WHERE name='recording_call'"));
            migratedSignature = schema(connection);
            integrity(connection);
        }
        try(Connection backup = open(result.backup()))
        {
            ManagedRecordingSchema.validate(backup, 4);
            assertEquals(before, contents(backup));
        }
        Path retry = directory.resolve("retry-copy.sqlite");
        Files.copy(result.backup(), retry);
        assertTrue(ManagedRecordingCatalogMigrator.migrate(retry, false).migrated());
        try(Connection connection = open(retry))
        {
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            assertEquals(before, contents(connection));
            assertEquals(migratedSignature, schema(connection));
        }
        Path fresh = directory.resolve("fresh.sqlite");
        try(Connection connection = open(fresh); var statement = connection.createStatement())
        {
            for(String ddl: ManagedRecordingSchema.creationStatements()) statement.execute(ddl);
            statement.executeUpdate("INSERT INTO catalog_metadata VALUES(1," + ManagedRecordingSchema.CURRENT_FORMAT_VERSION + ",0,0)");
            statement.execute("PRAGMA application_id=" + ManagedRecordingSchema.APPLICATION_ID);
            statement.execute("PRAGMA user_version=" + ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            assertEquals(migratedSignature, schema(connection), "Fresh and migrated catalogs have one exact schema");
        }
        assertFalse(ManagedRecordingCatalogMigrator.migrate(catalog).migrated(), "A current catalog is a no-op");
    }

    @Test
    void finalFailureRollsBackPopulatedFourAndAllowsRetryWithEitherBackupChoice() throws Exception
    {
        for(boolean backup: List.of(false, true))
        {
            Path folder = Files.createDirectory(directory.resolve("fault-" + backup));
            Path catalog = fixture(folder.resolve("catalog.sqlite"));
            Map<String,List<String>> before;
            try(Connection connection = open(catalog)) { before = contents(connection); }
            assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, backup,
                connection -> { throw new SQLException("Injected format5 final boundary"); },
                ManagedRecordingToneFormatFiveMigrationTest::open));
            try(Connection connection = open(catalog))
            {
                ManagedRecordingSchema.validate(connection, 4);
                assertEquals(before, contents(connection));
                integrity(connection);
            }
            if(backup)
            {
                try(var paths = Files.list(folder.resolve("backups")))
                {
                    List<Path> retained = paths.toList();
                    assertEquals(1, retained.size());
                    try(Connection connection = open(retained.getFirst()))
                    {
                        ManagedRecordingSchema.validate(connection, 4);
                        assertEquals(before, contents(connection));
                    }
                }
            }
            assertTrue(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
            try(Connection connection = open(catalog))
            {
                ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
                assertEquals(before, contents(connection));
                integrity(connection);
            }
        }
    }

    @Test
    void sourceMethodPairsAreExplicitAndNewNominalFactsReadThroughTheCatalog() throws Exception
    {
        Path catalog = fixture(directory.resolve("methods.sqlite"));
        ManagedRecordingCatalogMigrator.migrate(catalog, false);
        try(Connection connection = open(catalog); var statement = connection.createStatement())
        {
            for(int[] pair: new int[][]{{1,1}, {1,3}, {1,4}, {2,2}})
            {
                statement.executeUpdate("INSERT INTO recording_tone_sequence VALUES(43," + pair[0] + "," + pair[1] +
                    ",1,4000,4000,5000,1)");
                statement.executeUpdate("DELETE FROM recording_tone_sequence WHERE call_id=43");
            }
            for(int[] pair: new int[][]{{1,2}, {2,1}, {2,3}, {2,4}, {1,0}, {1,5}})
            {
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO recording_tone_sequence VALUES(43," + pair[0] + "," + pair[1] + ",1,4000,4000,5000,1)"));
            }
            statement.executeUpdate("INSERT INTO recording_tone_sequence VALUES(43,1,3,1,4000,4000,5000,1)");
            statement.executeUpdate("INSERT INTO recording_tone VALUES(43,0,562500,NULL,NULL,100,200,997907)");
            integrity(connection);
        }
        try(ManagedRecordingCatalog opened = new ManagedRecordingCatalog(catalog, directory))
        {
            var calls = opened.search(ManagedRecordingCatalog.SearchFilter.builder().fromMs(0L).toMs(6000L).build()).calls();
            Map<?,?> normalized = (Map<?,?>)calls.stream().filter(call -> call.id() == 43).findFirst().orElseThrow()
                .toMap().get("paging_tone_summary");
            assertEquals("decoded_audio", normalized.get("source"));
            assertEquals("p25_pcm_ambe_tone_v2", normalized.get("method"));
            Map<?,?> tone = (Map<?,?>)((List<?>)normalized.get("tones")).getFirst();
            assertEquals(List.of(562.5), tone.get("frequencies_hz"));
            assertEquals(200L, tone.get("duration_milliseconds"));
            assertFalse(normalized.containsKey("frequency_mapping"));
            assertFalse(tone.containsKey("matching_frequency_hz"));
            Map<?,?> legacy = (Map<?,?>)calls.stream().filter(call -> call.id() == 41).findFirst().orElseThrow()
                .toMap().get("paging_tone_summary");
            assertEquals("p25_pcm_tone_v1", legacy.get("method"));
            assertEquals(List.of(566.379), ((Map<?,?>)((List<?>)legacy.get("tones")).getFirst()).get("frequencies_hz"));
        }
    }

    @Test
    void mislabeledOrNewerFourIsRefusedWithoutMutation() throws Exception
    {
        for(int marker: List.of(5, 6))
        {
            Path catalog = fixture(directory.resolve("refuse-" + marker + ".sqlite"));
            try(Connection connection = open(catalog); var statement = connection.createStatement())
            { statement.execute("PRAGMA user_version=" + marker); }
            byte[] before = Files.readAllBytes(catalog);
            assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.inspect(catalog));
            assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, false));
            assertArrayEquals(before, Files.readAllBytes(catalog));
        }
    }

    private static Path fixture(Path path) throws Exception
    {
        byte[] frozen;
        try(var input = ManagedRecordingToneFormatFiveMigrationTest.class.getResourceAsStream(
            "/database/managed-recordings-format-4.sql"))
        { assertNotNull(input); frozen = input.readAllBytes(); }
        assertEquals("53f7ba7927c95adb66c12959c08f5b82a26594ece611e0f0ce417902cf41a1fa",
            HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(frozen)));
        try(Connection connection = open(path); var statement = connection.createStatement())
        {
            for(String ddl: new String(frozen, StandardCharsets.UTF_8).lines().toList()) statement.execute(ddl);
            statement.executeUpdate("INSERT INTO catalog_metadata VALUES(1,4,3,192)");
            statement.executeUpdate("INSERT INTO recording_system VALUES(11,'p25:bee00:123')");
            statement.executeUpdate("INSERT INTO recording_channel VALUES(22,'123e4567-e89b-42d3-a456-426614174000')");
            statement.executeUpdate("INSERT INTO recording_site VALUES(33,781824,291,1,2)");
            statement.executeUpdate("INSERT INTO recording_system_site VALUES(11,33)");
            statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes," +
                "system_id,channel_id,protocol,call_type,voice_type,tone_kind,tone,winner_site_id,transcription_status) VALUES" +
                "(41,1000,2000,1000,'fixture/one.mp3',64,11,22,1,1,1,2,'023',33,'complete')," +
                "(42,2000,3000,1000,'fixture/two.wav',64,11,22,1,1,1,NULL,NULL,33,'failed')," +
                "(43,4000,5000,1000,'fixture/three.mp3',64,11,22,1,1,1,NULL,NULL,33,'pending')");
            statement.executeUpdate("INSERT INTO recording_call_site VALUES(41,33,1000)");
            statement.executeUpdate("INSERT INTO recording_patch_member VALUES(41,1,501,781824,291,501,1000)");
            statement.executeUpdate("INSERT INTO recording_transcript VALUES(41,'fixture transcript retained verbatim',4000)");
            statement.executeUpdate("INSERT INTO recording_tone_sequence VALUES(41,1,1,1,1000,1000,2000,1),(42,2,2,2,2000,NULL,3000,2)");
            statement.executeUpdate("INSERT INTO recording_tone VALUES(41,0,566379,NULL,NULL,100,200,997907)," +
                "(42,0,697000,1209000,'DTMF_1',0,200,NULL),(42,1,NULL,NULL,'INVALID',200,60,NULL)");
            statement.executeUpdate("UPDATE sqlite_sequence SET seq=9000 WHERE name='recording_call'");
            statement.execute("PRAGMA application_id=" + ManagedRecordingSchema.APPLICATION_ID);
            statement.execute("PRAGMA user_version=4");
            ManagedRecordingSchema.validate(connection, 4);
            integrity(connection);
        }
        return path;
    }

    private static Map<String,List<String>> contents(Connection connection) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' AND name NOT GLOB 'recording_transcript_fts*' ORDER BY name"))
        { while(rows.next()) tables.add(rows.getString(1)); }
        for(String table: tables)
        {
            List<String> values = new ArrayList<>();
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM \"" + table + "\""))
            {
                while(rows.next())
                {
                    List<String> row = new ArrayList<>();
                    for(int index = 1; index <= rows.getMetaData().getColumnCount(); index++)
                        if(!table.equals("catalog_metadata") || index != 2) row.add(String.valueOf(rows.getObject(index)));
                    values.add(row.toString());
                }
            }
            Collections.sort(values);
            result.put(table, values);
        }
        result.putIfAbsent("recording_identifier", List.of());
        return result;
    }

    private static Map<String,String> schema(Connection connection) throws SQLException
    {
        Map<String,String> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT name,sql FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*' ORDER BY name"))
        { while(rows.next()) result.put(rows.getString(1), rows.getString(2)); }
        return result;
    }

    private static long scalar(Connection connection, String query) throws SQLException
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(query))
        { assertTrue(rows.next()); return rows.getLong(1); }
    }

    private static void integrity(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA foreign_key_check"))
        { assertFalse(rows.next()); }
        try(var statement = connection.createStatement(); var rows = statement.executeQuery("PRAGMA quick_check"))
        { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
    }

    private static Connection open(Path path) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try(var statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); }
        return connection;
    }
}
