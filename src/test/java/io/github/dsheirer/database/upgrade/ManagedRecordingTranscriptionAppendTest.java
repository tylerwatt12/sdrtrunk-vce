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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.ProgressHandler;

class ManagedRecordingTranscriptionAppendTest
{
    @TempDir Path directory;

    @Test
    void appendWorkIsIndependentOfRetainedCallsAndTheOldStrictAddExceedsItsBudget() throws Exception
    {
        List<Integer> work = new ArrayList<>();
        List<Map<String,List<List<String>>>> plannerMetadata = new ArrayList<>();
        for(int retained: List.of(1, 20000))
        {
            Path catalog = fixture(directory.resolve("bounded-" + retained + ".sqlite"), retained);
            Map<String,Long> trees;
            Map<String,List<List<String>>> facts;
            try(Connection connection = open(catalog))
            {
                trees = trees(connection);
                facts = facts(connection);
            }
            plannerMetadata.add(Map.of("sqlite_stat1", facts.get("sqlite_stat1"), "sqlite_stat4", facts.get("sqlite_stat4")));
            if(retained == 20000)
            {
                Path nativeScan = directory.resolve("native-scan.sqlite");
                Files.copy(catalog, nativeScan);
                try(Connection connection = open(nativeScan); var statement = connection.createStatement())
                {
                    budget(connection, new AtomicInteger());
                    try
                    {
                        SQLException failure = assertThrows(SQLException.class, () -> statement.execute(
                            "ALTER TABLE recording_call ADD COLUMN " + ManagedRecordingSchema.TRANSCRIPTION_STATUS_COLUMN));
                        assertEquals(9, failure.getErrorCode(), "The old STRICT ADD must hit SQLITE_INTERRUPT");
                    }
                    finally { ProgressHandler.clearHandler(connection); }
                    ManagedRecordingSchema.validate(connection, 2);
                }
            }
            AtomicInteger calls = new AtomicInteger();
            try(Connection connection = open(catalog); var statement = connection.createStatement())
            {
                statement.execute("BEGIN IMMEDIATE");
                budget(connection, calls);
                try { ManagedRecordingCatalogMigrator.appendTranscriptionStatusColumn(connection); }
                finally { ProgressHandler.clearHandler(connection); }
                work.add(calls.get());
                assertEquals(trees, trees(connection));
                assertEquals(facts, facts(connection), "Every retained field, transcript, statistic and allocator stays unchanged");
                assertEquals(retained + 2, scalar(connection, "SELECT count(*) FROM recording_call WHERE transcription_status='pending'"));
                assertEquals(0, scalar(connection, "PRAGMA writable_schema"));
                // This is an append-only phase gate. Real transcript backfill and pending-index construction
                // intentionally have retained-row work and are tested through the complete route separately.
                finishFormatThree(connection);
                statement.execute("COMMIT");
            }
            try(Connection connection = open(catalog))
            {
                ManagedRecordingSchema.validate(connection, 3);
                assertEquals(retained + 2, scalar(connection, "SELECT count(*) FROM recording_call WHERE transcription_status='pending'"));
                integrity(connection);
            }
        }
        assertEquals(plannerMetadata.getFirst(), plannerMetadata.getLast(), "Compare growth with identical compact planner metadata");
        assertTrue(Math.abs(work.getLast() - work.getFirst()) <= 2,
            "20,000 retained calls add at most 2,000 VM operations to the append phase: " + work);
    }

    @Test
    void reopenedOldAndNewRowsUseTheExactNativeDefaultStrictLayoutAndStatusDomain() throws Exception
    {
        Path catalog = fixture(directory.resolve("status.sqlite"), 1);
        try(Connection connection = open(catalog); var statement = connection.createStatement())
        {
            statement.execute("BEGIN IMMEDIATE");
            ManagedRecordingCatalogMigrator.appendTranscriptionStatusColumn(connection);
            finishFormatThree(connection);
            statement.execute("COMMIT");
        }
        try(Connection connection = open(catalog); Connection nativeConnection = DriverManager.getConnection("jdbc:sqlite::memory:");
            var nativeStatement = nativeConnection.createStatement(); var statement = connection.createStatement())
        {
            for(String sql: frozen(3).lines().toList()) nativeStatement.execute(sql);
            assertEquals(query(nativeConnection, "SELECT sql FROM sqlite_schema WHERE name='recording_call'"),
                query(connection, "SELECT sql FROM sqlite_schema WHERE name='recording_call'"));
            assertEquals(query(nativeConnection, "PRAGMA table_xinfo(recording_call)"), query(connection, "PRAGMA table_xinfo(recording_call)"));
            assertEquals(1, scalar(connection, "SELECT strict FROM pragma_table_list WHERE name='recording_call'"));
            assertEquals(List.of(List.of("pending", "text")), query(connection,
                "SELECT transcription_status,typeof(transcription_status) FROM recording_call WHERE id=41"));
            statement.executeUpdate("INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path,size_bytes," +
                "protocol,call_type,voice_type) VALUES(1000,2000,1000,'new.mp3',64,1,1,1)");
            assertEquals(1, scalar(connection, "SELECT count(*) FROM recording_call WHERE relative_path='new.mp3' AND transcription_status='pending'"));
            for(String status: List.of("pending", "complete", "failed"))
            {
                statement.executeUpdate("UPDATE recording_call SET transcription_status='" + status + "' WHERE id=41");
                assertEquals(status, query(connection, "SELECT transcription_status FROM recording_call WHERE id=41").getFirst().getFirst());
            }
            for(String value: List.of("NULL", "'queued'", "''", "123"))
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE recording_call SET transcription_status=" + value + " WHERE id=41"));
            assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE recording_call SET duration_ms=-1 WHERE id=41"));
            integrity(connection);
        }
    }

    @Test
    void appendAndFullRouteFailuresRollBackExactlyAndAllowTheRealBackfillOnRetry() throws Exception
    {
        Path catalog = fixture(directory.resolve("rollback.sqlite"), 1);
        byte[] original = Files.readAllBytes(catalog);
        try(Connection connection = open(catalog); var statement = connection.createStatement())
        {
            statement.execute("BEGIN IMMEDIATE");
            ManagedRecordingCatalogMigrator.appendTranscriptionStatusColumn(connection);
            statement.execute("ROLLBACK");
        }
        assertArrayEquals(original, Files.readAllBytes(catalog));
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, false, connection -> {
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            throw new SQLException("Injected failure after the status append, backfill, index and remaining chain");
        }, ManagedRecordingTranscriptionAppendTest::open));
        assertArrayEquals(original, Files.readAllBytes(catalog));
        try(Connection connection = open(catalog)) { ManagedRecordingSchema.validate(connection, 2); integrity(connection); }
        // No constant-work budget here: the real backfill and index builds are intentional work.
        assertTrue(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
        try(Connection connection = open(catalog))
        {
            ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
            assertEquals("complete", query(connection, "SELECT transcription_status FROM recording_call WHERE id=41").getFirst().getFirst());
            assertEquals(2, scalar(connection, "SELECT count(*) FROM recording_call WHERE transcription_status='pending'"));
            assertEquals("fixture transcript retained verbatim", query(connection, "SELECT text FROM recording_transcript WHERE call_id=41").getFirst().getFirst());
            integrity(connection);
        }
        assertFalse(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
    }

    private static void budget(Connection connection, AtomicInteger calls) throws SQLException
    {
        // VM counts do not cover native work hidden in individual SQL functions/opcodes; exact DDL review
        // and retained b-tree/row preservation complement this old-STRICT-ADD negative control.
        ProgressHandler.setHandler(connection, 1000, new ProgressHandler()
        {
            @Override protected int progress() { return calls.incrementAndGet() > 100 ? 1 : 0; }
        });
    }

    private static Path fixture(Path path, int retained) throws Exception
    {
        try(Connection connection = open(path); var statement = connection.createStatement())
        {
            for(String sql: frozen(2).lines().toList()) statement.execute(sql);
            // Preserve identical planner metadata while growing facts; stale samples are normal between analyses.
            statement.execute("ANALYZE");
            statement.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<" + retained + ") " +
                "INSERT INTO recording_call(start_ms,end_ms,duration_ms,relative_path,size_bytes,protocol,call_type,voice_type) " +
                "SELECT 1000,2000,1000,'retained-'||i||'.mp3',64,1,1,1 FROM n");
            statement.executeUpdate("UPDATE catalog_metadata SET call_count=call_count+" + retained +
                ",total_bytes=total_bytes+" + retained * 64 + " WHERE id=1");
            ManagedRecordingSchema.validate(connection, 2);
            integrity(connection);
        }
        return path;
    }

    private static String frozen(int format) throws Exception
    {
        byte[] bytes;
        try(var input = ManagedRecordingTranscriptionAppendTest.class.getResourceAsStream(
            "/database/managed-recordings-format-" + format + ".sql"))
        { assertNotNull(input); bytes = input.readAllBytes(); }
        assertEquals(format == 2 ? "cc8d46560d6607678fc892b285229de7d136b35d5cd47c8bbba7e1f8e9023a86" :
            "f50affd681c9a5006940cd4dde721f3070e7e25a64a351e2dcc39a35ab91d611",
            HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void finishFormatThree(Connection connection) throws SQLException
    {
        try(var statement = connection.createStatement())
        {
            statement.execute(ManagedRecordingSchema.ddlForFormat(3).get("idx_recording_call_transcription_pending"));
            statement.executeUpdate("UPDATE catalog_metadata SET format_version=3 WHERE id=1");
            statement.execute("PRAGMA user_version=3");
            ManagedRecordingSchema.validate(connection, 3);
        }
    }

    private static Map<String,Long> trees(Connection connection) throws SQLException
    {
        Map<String,Long> result = new LinkedHashMap<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(
            "SELECT name,rootpage FROM sqlite_schema WHERE rootpage>0 ORDER BY name"))
        { while(rows.next()) result.put(rows.getString(1), rows.getLong(2)); }
        return result;
    }

    private static Map<String,List<List<String>>> facts(Connection connection) throws SQLException
    {
        Map<String,List<List<String>>> result = new LinkedHashMap<>();
        for(List<String> table: query(connection, "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        {
            String name = table.getFirst();
            String columns = name.equals("recording_call") ? "id,start_ms,end_ms,duration_ms,relative_path,size_bytes," +
                "system_id,channel_id,alias_list_id,protocol,call_type,voice_type,source_id,source_home_wacn," +
                "source_home_system,source_home_id,target_id,target_home_wacn,target_home_system,target_home_id," +
                "frequency_hz,timeslot,nac,tone_kind,tone,winner_site_id" : "*";
            var values = query(connection, "SELECT " + columns + " FROM \"" + name + "\"");
            values.sort(java.util.Comparator.comparing(Object::toString));
            result.put(name, values);
        }
        return result;
    }

    private static List<List<String>> query(Connection connection, String sql) throws SQLException
    {
        List<List<String>> result = new ArrayList<>();
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
        {
            while(rows.next())
            {
                List<String> value = new ArrayList<>();
                for(int col = 1; col <= rows.getMetaData().getColumnCount(); col++)
                {
                    Object field = rows.getObject(col);
                    value.add(field instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : String.valueOf(field));
                }
                result.add(value);
            }
        }
        return result;
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    { try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
      { assertTrue(rows.next()); return rows.getLong(1); } }

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
