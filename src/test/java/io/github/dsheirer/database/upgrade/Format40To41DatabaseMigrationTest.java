/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format40To41DatabaseMigrationTest
{
    private static final Map<String,String> ADDED = Map.of(
        "idx_receiver_activity_event_target_event_type_time", """
            CREATE INDEX idx_receiver_activity_event_target_event_type_time
            ON receiver_activity_event(target_identity_summary_id,event_type_code,observed_at_ms DESC,id DESC,
                radio_system_id,action_code) WHERE target_identity_summary_id IS NOT NULL
            """,
        "idx_receiver_activity_event_channel_frequency_time", """
            CREATE INDEX idx_receiver_activity_event_channel_frequency_time
            ON receiver_activity_event(channel_id,frequency_hz,observed_at_ms DESC,id DESC,
                lcn_band,lcn_number,timeslot,radio_system_id,action_code) WHERE frequency_hz IS NOT NULL
            """);
    @TempDir Path mTemporaryFolder;

    @Test
    void preservesEveryRowAndAllocatorAcrossRollbackAndRetry() throws Exception
    {
        Path source = Format40TestDatabase.create(mTemporaryFolder.resolve("source.sqlite"));
        Map<String,TableContents> expected;
        Map<String,String> indexes;
        try(Connection connection = open(source); Statement statement = connection.createStatement())
        {
            seedLookupRows(statement);
            expected = tableContents(connection);
            indexes = schemaDefinitions(connection);
            assertTrue(expected.get("activity_event_identity_member").rows()>0);
            assertTrue(expected.get("web_user").rows()>0);
            assertTrue(expected.get("configuration_channel").rows()>0);
            assertEquals("c318b74d848a9beccb82600922e79fd6570c60e2d25762857194aa004a9c0f3c",
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals(List.of(2L),new Format40To41DatabaseMigration().validateSource(connection).stream()
                .map(DatabaseMigrationEffect::affectedRows).toList());
        }
        byte[] bytes = Files.readAllBytes(source);
        assertThrows(SQLException.class,() -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(source));
        assertArrayEquals(bytes,Files.readAllBytes(source));
        for(int attempt=0;attempt<2;attempt++)
        {
            Path candidate = Files.copy(source,mTemporaryFolder.resolve("candidate-"+attempt+".sqlite"));
            try(Connection connection = open(candidate); Statement statement = connection.createStatement())
            {
                connection.setAutoCommit(false);
                new Format40To41DatabaseMigration().migrate(connection);connection.rollback();
                assertEquals(DatabaseFormatCatalog.requireVersion(40).fingerprint(),SqliteSchemaValidator.fingerprint(connection));
                assertEquals(indexes,schemaDefinitions(connection));assertEquals(expected,tableContents(connection));
                DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);connection.commit();
                assertEquals(1,report.steps().size());
                assertEquals("format-40-to-41",report.steps().getFirst().id());
                assertEquals(List.of(2L),report.steps().getFirst().effects().stream()
                    .map(DatabaseMigrationEffect::affectedRows).toList());
                assertEquals(expected,tableContents(connection),"Only the format marker changes");
                assertEquals(41,DatabaseFormatCatalog.requireCurrent(connection).version());
                assertEquals(DatabaseFormatCatalog.current().fingerprint(),SqliteSchemaValidator.fingerprint(connection));
                Map<String,String> actual = schemaDefinitions(connection);
                Set<String> names = new TreeSet<>(indexes.keySet());
                ADDED.keySet().forEach(name -> names.add("index:"+name));
                assertEquals(names,actual.keySet());indexes.forEach((name,sql) -> assertEquals(sql,actual.get(name)));
                ADDED.forEach((name,sql) -> assertEquals(normalizeSql(sql),actual.get("index:"+name)));
                assertLookupPlansAndResults(statement);
                assertEquals("ok",scalar(statement,"PRAGMA integrity_check"));
                assertEquals("0",scalar(statement,"SELECT count(*) FROM pragma_foreign_key_check"));
            }
            SdrTrunkDatabaseStartup.validateGlobalDatabase(candidate);
            assertArrayEquals(bytes,Files.readAllBytes(source));
        }
    }

    @Test
    void refusesOlderAndMixedSourcesWithoutMutation() throws Exception
    {
        Path older = Format39TestDatabase.create(mTemporaryFolder.resolve("older.sqlite"));
        Path mixed = Format40TestDatabase.create(mTemporaryFolder.resolve("mixed.sqlite"));
        try(Connection connection = open(mixed); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_receiver_activity_event_source_identity_address");
            statement.executeUpdate("CREATE INDEX idx_receiver_activity_event_source_identity_address " +
                "ON receiver_activity_event(source_identity_summary_id,source_observed_local_id,channel_id)");
        }
        for(Path source: List.of(older,mixed))
        {
            byte[] bytes = Files.readAllBytes(source);
            try(Connection connection = open(source))
            {
                String fingerprint = SqliteSchemaValidator.fingerprint(connection);
                Map<String,TableContents> contents = tableContents(connection);
                assertThrows(SQLException.class,() -> new Format40To41DatabaseMigration().migrate(connection));
                assertEquals(fingerprint,SqliteSchemaValidator.fingerprint(connection));assertEquals(contents,tableContents(connection));
            }
            assertArrayEquals(bytes,Files.readAllBytes(source));
        }
    }

    @Test
    void freshAndMigratedFormat41HaveTheSameSchemaAndCurrentMigrationIsANoOp() throws Exception
    {
        Path current = Format41TestDatabase.create(mTemporaryFolder.resolve("current.sqlite"));
        Path fresh = mTemporaryFolder.resolve("fresh.sqlite");SdrTrunkDatabaseStartup.createGlobalDatabase(fresh);
        try(Connection connection = open(current); Connection freshConnection = open(fresh))
        {
            Map<String,TableContents> before = tableContents(connection);
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());assertEquals(before,tableContents(connection));
            assertEquals(SqliteSchemaValidator.fingerprint(freshConnection),SqliteSchemaValidator.fingerprint(connection));
            // Historical table rebuilds retain harmless identifier quotes; the fingerprint checks their definitions.
            assertEquals(schemaDefinitions(freshConnection).keySet(),schemaDefinitions(connection).keySet());
        }
    }

    @Test
    void adjacentFormat40FixtureRetainsItsExactFrozenContract() throws Exception
    {
        Path historical = Format40TestDatabase.create(mTemporaryFolder.resolve("historical.sqlite"));
        try(Connection connection = open(historical))
        {
            assertEquals(40,DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("c318b74d848a9beccb82600922e79fd6570c60e2d25762857194aa004a9c0f3c",
                SqliteSchemaValidator.fingerprint(connection));
            Map<String,String> definitions = schemaDefinitions(connection);
            ADDED.keySet().forEach(name -> assertFalse(definitions.containsKey("index:"+name)));
        }
    }

    private static void seedLookupRows(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,
                event_type_code,target_identity_summary_id,target_observed_local_id,target_kind_code,
                frequency_hz,lcn_band,lcn_number,timeslot)
            VALUES(900010,900001,900001,4000,1,1,900003,0,2,851000000,0,0,1),
                  (900011,900001,900001,4000,1,1,900003,9002,2,851000000,1,2,2),
                  (900012,900001,900001,4000,12,1,900003,9002,2,851000000,1,2,2),
                  (900013,900001,900001,4000,1,2,900003,9002,2,851000000,1,2,2),
                  (900014,900001,900001,4000,1,1,900002,100,1,851000000,1,2,2),
                  (900015,900001,900001,4000,1,1,NULL,NULL,NULL,NULL,NULL,NULL,NULL),
                  (900016,900001,900001,5000,1,NULL,900003,9002,2,851000000,NULL,NULL,NULL),
                  (900017,900001,900001,4000,1,1,900003,9002,2,852000000,1,2,2)
            """);
        statement.executeUpdate("""
            INSERT INTO trunked_logical_call_identity_bucket(radio_system_id,bucket_start_ms,identity_role_code,
                identity_kind_code,identity_summary_id,logical_call_count,encrypted_logical_call_count,
                recorded_output_count,streamed_output_count)
            VALUES(900001,2000,1,1,900002,5,2,3,4),
                  (900001,0,1,1,900002,7,1,2,3),
                  (900001,4000,2,2,900003,11,2,3,4)
            """);
    }

    private static void assertLookupPlansAndResults(Statement statement) throws Exception
    {
        String target = "SELECT id FROM receiver_activity_event INDEXED BY %s " +
            "WHERE radio_system_id=900001 AND target_identity_summary_id=900003 AND event_type_code=1 " +
            "AND action_code<>12 ORDER BY observed_at_ms DESC,id DESC";
        String targetCover = target.formatted("idx_receiver_activity_event_target_event_type_time");
        assertEquals(List.of("900017","900011","900010"),results(statement,targetCover));
        assertEquals(results(statement,target.formatted("idx_receiver_activity_event_target_time")),
            results(statement,targetCover));
        assertCoveringPlan(statement,targetCover,"idx_receiver_activity_event_target_event_type_time",
            List.of("target_identity_summary_id=?","event_type_code=?"));
        String targetCursor = targetCover.replace("ORDER BY", "AND (observed_at_ms<4000 OR " +
            "(observed_at_ms=4000 AND id<900011)) ORDER BY");
        assertEquals(List.of("900010"),results(statement,targetCursor));
        assertEquals("4",scalar(statement,"SELECT count(*) FROM receiver_activity_event " +
            "INDEXED BY idx_receiver_activity_event_target_event_type_time " +
            "WHERE target_identity_summary_id=900003 AND event_type_code IS NULL"),
            "The new target index must retain null-subtype observations");

        String frequency = "SELECT id FROM receiver_activity_event INDEXED BY %s " +
            "WHERE channel_id=900001 AND frequency_hz=851000000 AND lcn_band=1 AND lcn_number=2 " +
            "AND timeslot=2 AND radio_system_id=900001 AND action_code<>12 " +
            "ORDER BY observed_at_ms DESC,id DESC";
        String frequencyCover = frequency.formatted("idx_receiver_activity_event_channel_frequency_time");
        assertEquals(List.of("900014","900013","900011"),results(statement,frequencyCover));
        assertEquals(results(statement,frequency.formatted("idx_receiver_activity_event_channel_time")),
            results(statement,frequencyCover));
        assertCoveringPlan(statement,frequencyCover,"idx_receiver_activity_event_channel_frequency_time",
            List.of("channel_id=?","frequency_hz=?"));
        assertTrue(results(statement,frequencyCover.replace("timeslot=2","timeslot=1")).isEmpty());
        assertEquals("7",scalar(statement,"SELECT count(*) FROM receiver_activity_event " +
            "INDEXED BY idx_receiver_activity_event_channel_frequency_time " +
            "WHERE frequency_hz IS NOT NULL"),"Null-frequency observations are outside the sparse index");

        for(String role: List.of("source","target"))
        {
            String ordered = "SELECT id FROM receiver_activity_event INDEXED BY idx_receiver_activity_event_"+
                role+"_time WHERE "+role+"_identity_summary_id="+(role.equals("source") ? "900001" : "900003")+" " +
                "ORDER BY observed_at_ms DESC,id DESC LIMIT 2";
            List<String> plan = queryPlan(statement,ordered);
            assertFalse(plan.stream().anyMatch(row -> row.contains("USE TEMP B-TREE")),plan::toString);
        }
    }

    private static void assertCoveringPlan(Statement statement, String sql, String index,
                                           List<String> predicates) throws Exception
    {
        List<String> plan = queryPlan(statement,sql);
        assertTrue(plan.stream().anyMatch(row -> row.contains("SEARCH") && row.contains("COVERING INDEX "+index)
            && predicates.stream().allMatch(row::contains)),plan::toString);
        assertFalse(plan.stream().anyMatch(row -> row.contains("USE TEMP B-TREE")),plan::toString);
    }

    private static List<String> queryPlan(Statement statement, String sql) throws SQLException
    {
        List<String> plan = new ArrayList<>();
        try(ResultSet rows = statement.executeQuery("EXPLAIN QUERY PLAN "+sql))
        {
            while(rows.next()) plan.add(rows.getString("detail"));
        }
        return plan;
    }

    private static List<String> results(Statement statement, String sql) throws SQLException
    {
        List<String> results = new ArrayList<>();
        try(ResultSet rows = statement.executeQuery(sql))
        {
            int columns = rows.getMetaData().getColumnCount();
            while(rows.next())
            {
                List<String> values = new ArrayList<>();
                for(int column=1;column<=columns;column++) values.add(rows.getString(column));
                results.add(String.join("|",values));
            }
        }
        return results;
    }

    /** Hashes every fixture field without exposing credentials or preference contents. */
    private static Map<String,TableContents> tableContents(Connection connection) throws Exception
    {
        List<String> tables = new ArrayList<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        {
            while(rows.next()) tables.add(rows.getString(1));
        }
        Map<String,TableContents> contents = new LinkedHashMap<>();
        for(String table: tables)
        {
            String escaped = table.replace("\"","\"\"");
            String projection = "*";
            int columns;
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT "+projection+" FROM \""+escaped+"\" LIMIT 0"))
            {
                columns = rows.getMetaData().getColumnCount();
            }
            String ordering = java.util.stream.IntStream.rangeClosed(1,columns).mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(","));
            String filter = table.equals("database_metadata") ? " WHERE key<>'database_format_version'" : "";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT "+projection+" FROM \""+escaped+"\""+filter+" ORDER BY "+ordering))
            {
                while(rows.next())
                {
                    count++;
                    for(int column=1;column<=columns;column++)
                    {
                        byte[] value = rows.getBytes(column);
                        digest.update((byte)(value==null ? 0 : 1));
                        if(value!=null)
                        {
                            digest.update(Integer.toString(value.length).getBytes(StandardCharsets.UTF_8));
                            digest.update((byte)0);digest.update(value);
                        }
                    }
                }
            }
            contents.put(table,new TableContents(count,HexFormat.of().formatHex(digest.digest())));
        }
        return contents;
    }

    private static Map<String,String> schemaDefinitions(Connection connection) throws Exception
    {
        Map<String,String> indexes = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT type,name,sql FROM sqlite_schema WHERE sql IS NOT NULL ORDER BY type,name"))
        {
            while(rows.next()) indexes.put(rows.getString(1)+":"+rows.getString(2),normalizeSql(rows.getString(3)));
        }
        return indexes;
    }

    private static String normalizeSql(String sql)
    {
        return sql.replaceAll("\\s+"," ").trim().replace("IF NOT EXISTS ","").replaceAll("\\s*,\\s*",",");
    }

    private static Connection open(Path database) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:"+database);
        try(Statement statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); }
        return connection;
    }

    private static String scalar(Statement statement, String sql) throws SQLException
    {
        try(ResultSet rows = statement.executeQuery(sql)) { return rows.next() ? rows.getString(1) : null; }
    }

    private record TableContents(long rows, String digest) {}
}
