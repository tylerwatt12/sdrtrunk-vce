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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.ProgressHandler;

class ManagedRecordingToneConstraintMigrationTest
{
    @TempDir Path directory;

    @Test
    void bothFrozenToneWideningsHaveFixedWorkAndKeepEveryRetainedTreeRowAndStatistic() throws Exception
    {
        for(int source: List.of(4, 8))
        {
            List<Integer> work = new ArrayList<>();
            List<Map<String,List<String>>> plannerMetadata = new ArrayList<>();
            record Sample(int retained, boolean analyzed) {}
            for(Sample sample: List.of(new Sample(1, false), new Sample(20000, false), new Sample(20000, true)))
            {
                int retained = sample.retained();
                Path catalog = fixture(directory.resolve(source + "-" + retained + "-" + sample.analyzed() + ".sqlite"),
                    source, retained, sample.analyzed());
                Map<String,Long> trees;
                Map<String,List<String>> facts;
                try(Connection connection = open(catalog))
                {
                    trees = trees(connection);
                    facts = facts(connection);
                    assertTrue(facts.containsKey("sqlite_stat1"));
                    assertTrue(facts.containsKey("sqlite_stat4"));
                }
                if(retained == 20000 && !sample.analyzed()) rejectOldRebuild(catalog, source);
                if(!sample.analyzed()) plannerMetadata.add(Map.of("sqlite_stat1", facts.get("sqlite_stat1"),
                    "sqlite_stat4", facts.get("sqlite_stat4")));
                AtomicInteger calls = new AtomicInteger();
                if(source == 4)
                {
                    // Later 5->6 lookup-index construction and 7->8 FTS backfill have legitimate row work.
                    // Exercise precisely the adjacent production helper, stamp, and exact target admission here.
                    try(Connection connection = open(catalog); var statement = connection.createStatement())
                    {
                        budget(connection, calls);
                        try
                        {
                            statement.execute("BEGIN IMMEDIATE");
                            ManagedRecordingCatalogMigrator.widenToneMethodConstraint(connection, source);
                            stamp(connection, source + 1);
                            ManagedRecordingSchema.validate(connection, source + 1);
                            statement.execute("COMMIT");
                        }
                        finally { ProgressHandler.clearHandler(connection); }
                    }
                }
                else
                {
                    // The entire 8->current route, including quality additions, must remain bounded.
                    assertTrue(ManagedRecordingCatalogMigrator.migrate(catalog, false, ignored -> {}, path -> {
                        Connection connection = open(path);
                        budget(connection, calls);
                        return connection;
                    }).migrated());
                }
                if(!sample.analyzed()) work.add(calls.get());
                try(Connection connection = open(catalog))
                {
                    ManagedRecordingSchema.validate(connection, source == 4 ? 5 : ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
                    Map<String,Long> afterTrees = trees(connection);
                    trees.forEach((name, root) -> assertEquals(root, afterTrees.get(name), name));
                    var afterFacts = facts(connection);
                    facts.forEach((table, rows) -> assertEquals(rows, afterFacts.get(table), table));
                    assertEquals(retained + 2, scalar(connection, "SELECT count(*) FROM recording_tone_sequence"));
                    assertEquals(retained * 2 + 3, scalar(connection, "SELECT count(*) FROM recording_tone"));
                    assertEquals(0, scalar(connection, "PRAGMA writable_schema"));
                    integrity(connection);
                }
            }
            assertEquals(plannerMetadata.getFirst(), plannerMetadata.getLast(),
                "Compare retained-row growth with identical compact planner metadata; separately budget fresh ANALYZE above");
            assertTrue(Math.abs(work.getLast() - work.getFirst()) <= 2,
                "20,000 parents and 40,000 children must add at most 2,000 VM operations: " + work);
        }
    }

    @Test
    void reopenedTargetsMatchFrozenNativeDefinitionsAndOnlyAdmitTheIntendedNewPairs() throws Exception
    {
        for(int source: List.of(4, 8))
        {
            Path catalog = fixture(directory.resolve("constraints-" + source + ".sqlite"), source, 1);
            try(Connection connection = open(catalog); var statement = connection.createStatement())
            {
                statement.execute("BEGIN IMMEDIATE");
                ManagedRecordingCatalogMigrator.widenToneMethodConstraint(connection, source);
                stamp(connection, source + 1);
                ManagedRecordingSchema.validate(connection, source + 1);
                statement.execute("COMMIT");
            }
            Path nativeTarget = directory.resolve("native-" + source + ".sqlite");
            try(Connection connection = open(nativeTarget)) { loadFrozen(connection, source + 1); }
            try(Connection connection = open(catalog); Connection nativeConnection = open(nativeTarget);
                var statement = connection.createStatement())
            {
                ManagedRecordingSchema.validate(connection, source + 1);
                for(String table: List.of("recording_tone_sequence", "recording_tone"))
                {
                    assertEquals(tableDefinition(nativeConnection, table), tableDefinition(connection, table));
                    assertEquals(query(nativeConnection, "PRAGMA table_xinfo(" + table + ")"),
                        query(connection, "PRAGMA table_xinfo(" + table + ")"));
                    assertEquals(query(nativeConnection, "PRAGMA foreign_key_list(" + table + ")"),
                        query(connection, "PRAGMA foreign_key_list(" + table + ")"));
                    assertEquals(1, scalar(connection, "SELECT strict FROM pragma_table_list WHERE name='" + table + "'"));
                }
                int newMethod = source == 4 ? 3 : 4;
                statement.executeUpdate("UPDATE recording_tone_sequence SET method_code=" + newMethod + " WHERE call_id=41");
                for(String assignments: List.of("source_code=2", "method_code=5", "phase=3", "tone_count=0",
                    "call_started_at_ms=0", "completed_at_ms=1", "method_code='text'"))
                    assertThrows(SQLException.class, () -> statement.executeUpdate(
                        "UPDATE recording_tone_sequence SET " + assignments + " WHERE call_id=41"), assignments);
                if(source == 4) assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE recording_tone_sequence SET method_code=4 WHERE call_id=41"));
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO recording_tone_sequence VALUES(999999,1," + newMethod + ",NULL,1000,NULL,2000,1)"));
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE recording_tone SET duration_ms=0 WHERE call_id=41"));
                statement.execute("SAVEPOINT cascade_test");
                statement.executeUpdate("DELETE FROM recording_tone_sequence WHERE call_id=41");
                assertEquals(0, scalar(connection, "SELECT count(*) FROM recording_tone WHERE call_id=41"));
                statement.execute("ROLLBACK TO cascade_test");
                statement.execute("RELEASE cascade_test");
                assertEquals(1, scalar(connection, "SELECT count(*) FROM recording_tone WHERE call_id=41"));
                integrity(connection);
            }
        }
    }

    @Test
    void bothWideningsRollBackExactlyAndAllowRetry() throws Exception
    {
        for(int source: List.of(4, 8))
        {
            Path catalog = fixture(directory.resolve("rollback-" + source + ".sqlite"), source, 1);
            byte[] original = Files.readAllBytes(catalog);
            assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, false,
                connection -> {
                    ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
                    throw new SQLException("Injected final failure after tone CHECK widening");
                }, ManagedRecordingToneConstraintMigrationTest::open));
            assertArrayEquals(original, Files.readAllBytes(catalog));
            try(Connection connection = open(catalog))
            {
                ManagedRecordingSchema.validate(connection, source);
                integrity(connection);
            }
            assertTrue(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
            try(Connection connection = open(catalog))
            {
                ManagedRecordingSchema.validate(connection, ManagedRecordingSchema.CURRENT_FORMAT_VERSION);
                integrity(connection);
            }
            assertFalse(ManagedRecordingCatalogMigrator.migrate(catalog, false).migrated());
        }
    }

    @Test
    void incompatibleSourceOrUnregisteredStepIsRefusedWithoutMutation() throws Exception
    {
        Path catalog = fixture(directory.resolve("refused.sqlite"), 4, 1);
        try(Connection connection = open(catalog); var statement = connection.createStatement())
        {
            assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.widenToneMethodConstraint(connection, 5));
            assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.widenToneMethodConstraint(connection, 8));
            statement.execute("ALTER TABLE recording_tone_sequence ADD COLUMN unexpected INTEGER");
        }
        byte[] before = Files.readAllBytes(catalog);
        assertThrows(SQLException.class, () -> ManagedRecordingCatalogMigrator.migrate(catalog, false));
        assertArrayEquals(before, Files.readAllBytes(catalog));
    }

    private void rejectOldRebuild(Path catalog, int source) throws Exception
    {
        Path old = directory.resolve("old-rebuild-" + source + ".sqlite");
        Files.copy(catalog, old);
        try(Connection connection = open(old); var statement = connection.createStatement())
        {
            budget(connection, new AtomicInteger());
            try
            {
                // First retained-history copy from both former adjacent steps. The unchanged old program fails
                // this same budget before any destructive DDL, while the complete new path above succeeds.
                SQLException failure = assertThrows(SQLException.class, () -> statement.execute(
                    "CREATE TEMP TABLE recording_tone_before_format" + (source + 1) + " AS SELECT * FROM recording_tone"));
                assertEquals(9, failure.getErrorCode());
            }
            finally { ProgressHandler.clearHandler(connection); }
            ManagedRecordingSchema.validate(connection, source);
        }
    }

    private static void budget(Connection connection, AtomicInteger calls) throws SQLException
    {
        // This bounds VM work, not native SQL-function internals. Exact DDL review and b-tree preservation
        // separately guard against native implicit quick_check/table rebuild work hidden behind one opcode.
        ProgressHandler.setHandler(connection, 1000, new ProgressHandler()
        {
            @Override protected int progress() { return calls.incrementAndGet() > 100 ? 1 : 0; }
        });
    }

    private static Path fixture(Path path, int format, int retained) throws Exception
    {
        return fixture(path, format, retained, false);
    }

    private static Path fixture(Path path, int format, int retained, boolean analyzeRetained) throws Exception
    {
        try(Connection connection = open(path); var statement = connection.createStatement())
        {
            loadFrozen(connection, format);
            if(format == 4)
            {
                statement.executeUpdate("INSERT INTO catalog_metadata VALUES(1,4,3,192)");
                statement.executeUpdate("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path," +
                    "size_bytes,protocol,call_type,voice_type) VALUES" +
                    "(41,1000,2000,1000,'one.mp3',64,1,1,1),(42,2000,3000,1000,'two.mp3',64,1,1,1)," +
                    "(43,4000,5000,1000,'three.mp3',64,1,1,1)");
                statement.executeUpdate("INSERT INTO recording_tone_sequence VALUES" +
                    "(41,1,1,1,1000,1000,2000,1),(42,2,2,2,2000,NULL,3000,2)");
                statement.executeUpdate("INSERT INTO recording_tone VALUES(41,0,566379,NULL,NULL,100,200,997907)," +
                    "(42,0,697000,1209000,'DTMF_1',0,200,NULL),(42,1,NULL,NULL,'INVALID',200,60,NULL)");
            }
            // Planner samples legitimately grow after ANALYZE. Keep them identical for the growth comparison;
            // a separate analyzed-large case above proves populated stat1/stat4 preservation under the same cap.
            statement.execute("ANALYZE");
            statement.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<" + retained + ") " +
                "INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes,protocol,call_type,voice_type) " +
                "SELECT 100000+i,1000,2000,1000,'retained-'||i||'.mp3',64,1,1,1 FROM n");
            statement.executeUpdate("INSERT INTO recording_tone_sequence SELECT id,1,1,NULL,1000,NULL,2000,2 " +
                "FROM recording_call WHERE id>100000");
            statement.executeUpdate("INSERT INTO recording_tone SELECT id,0,562500,NULL,NULL,0,100,990000 " +
                "FROM recording_call WHERE id>100000");
            statement.executeUpdate("INSERT INTO recording_tone SELECT id,1,NULL,NULL,'INVALID',100,200,NULL " +
                "FROM recording_call WHERE id>100000");
            statement.executeUpdate("UPDATE catalog_metadata SET call_count=call_count+" + retained +
                ",total_bytes=total_bytes+" + (retained * 64) + " WHERE id=1");
            statement.executeUpdate("UPDATE sqlite_sequence SET seq=200000 WHERE name='recording_call'");
            statement.execute("PRAGMA application_id=" + ManagedRecordingSchema.APPLICATION_ID);
            statement.execute("PRAGMA user_version=" + format);
            if(analyzeRetained) statement.execute("ANALYZE");
            ManagedRecordingSchema.validate(connection, format);
            integrity(connection);
        }
        return path;
    }

    private static void loadFrozen(Connection connection, int format) throws Exception
    {
        String expected = switch(format)
        {
            case 4 -> "53f7ba7927c95adb66c12959c08f5b82a26594ece611e0f0ce417902cf41a1fa";
            case 5 -> "4a52aebf2a1b1e2fac36563618c89cea76238602e350c02deb2abdcc246e704a";
            case 8 -> "21fa8fb9d92302efe2e4b9c2cd0601b7a4f3ec8f0f37abc190f9a1ebfde3d70a";
            case 9 -> "215c0bbb492485bbd3676994b6a342d2a43a02a08c815cafabd23d49ce21c20d";
            default -> throw new IllegalArgumentException();
        };
        byte[] frozen;
        try(var input = ManagedRecordingToneConstraintMigrationTest.class.getResourceAsStream(
            "/database/managed-recordings-format-" + format + ".sql"))
        { assertNotNull(input); frozen = input.readAllBytes(); }
        assertEquals(expected, HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(frozen)));
        try(var statement = connection.createStatement())
        { for(String sql: new String(frozen, StandardCharsets.UTF_8).lines().toList()) statement.execute(sql); }
    }

    private static void stamp(Connection connection, int target) throws SQLException
    {
        try(var statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE catalog_metadata SET format_version=" + target + " WHERE id=1");
            statement.execute("PRAGMA user_version=" + target);
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

    private static Map<String,List<String>> facts(Connection connection) throws SQLException
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        for(List<String> table: query(connection, "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        {
            String name = table.getFirst();
            List<String> values = new ArrayList<>();
            try(var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT * FROM \"" + name + "\""))
            {
                while(rows.next())
                {
                    List<String> value = new ArrayList<>();
                    for(int col = 1; col <= rows.getMetaData().getColumnCount(); col++)
                    {
                        if(ManagedRecordingMigrationFacts.isUnmeasuredQualityColumn(name, rows, col) ||
                            (name.equals("catalog_metadata") && col == 2)) continue;
                        Object field = rows.getObject(col);
                        value.add(field instanceof byte[] bytes ? HexFormat.of().formatHex(bytes) : String.valueOf(field));
                    }
                    values.add(value.toString());
                }
            }
            Collections.sort(values);
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
                for(int col = 1; col <= rows.getMetaData().getColumnCount(); col++) value.add(String.valueOf(rows.getObject(col)));
                result.add(value);
            }
        }
        return result;
    }

    private static String tableDefinition(Connection connection, String table) throws SQLException
    { return query(connection, "SELECT sql FROM sqlite_schema WHERE name='" + table + "'").getFirst().getFirst(); }

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
