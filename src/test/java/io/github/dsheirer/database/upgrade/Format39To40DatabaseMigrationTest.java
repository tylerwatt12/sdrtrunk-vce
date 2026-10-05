/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
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

class Format39To40DatabaseMigrationTest
{
    private static final Set<String> ADDED = Set.of("idx_receiver_activity_event_source_identity_address",
        "idx_receiver_activity_event_target_identity_address");
    @TempDir Path mTemporaryFolder;

    @Test
    void preservesEveryRowAndAllocatorAcrossRollbackAndRetry() throws Exception
    {
        Path source = Format39TestDatabase.create(mTemporaryFolder.resolve("source.sqlite"));
        Map<String,TableContents> expected;
        Map<String,String> indexes;
        try(Connection connection = open(source); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,
                    source_identity_summary_id,source_observed_local_id,target_identity_summary_id,
                    target_observed_local_id,target_kind_code)
                VALUES(900007,900001,900001,4000,1,900001,0,900002,0,1),
                      (900008,900001,900001,4000,1,NULL,999,NULL,888,1)
                """);
            expected = tableContents(connection);indexes = indexDefinitions(connection);
            assertTrue(expected.get("activity_event_identity_member").rows()>0);
            assertTrue(expected.get("web_user").rows()>0);
            assertTrue(expected.get("configuration_channel").rows()>0);
            assertEquals("017064542dbb5cfab978e1928679e5666fa0c22583970033c01f5b0f322a2be1",
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals(List.of(2L),new Format39To40DatabaseMigration().validateSource(connection).stream()
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
                new Format39To40DatabaseMigration().migrate(connection);connection.rollback();
                assertEquals(DatabaseFormatCatalog.requireVersion(36).fingerprint(),SqliteSchemaValidator.fingerprint(connection));
                assertEquals(indexes,indexDefinitions(connection));assertEquals(expected,tableContents(connection));
                List<DatabaseMigrationEffect> effects =
                    new Format39To40DatabaseMigration().migrateAndReport(connection, false);
                DatabaseFormatCatalog.stampForMigration(connection,37);connection.commit();
                assertEquals(List.of(2L),effects.stream()
                    .map(DatabaseMigrationEffect::affectedRows).toList());
                assertEquals(expected,tableContents(connection),"Only the format marker changes");
                assertEquals(37,DatabaseFormatCatalog.inspect(connection).version());
                assertEquals(DatabaseFormatCatalog.requireVersion(37).fingerprint(),SqliteSchemaValidator.fingerprint(connection));
                Map<String,String> actual = indexDefinitions(connection);
                Set<String> names = new TreeSet<>(indexes.keySet());names.addAll(ADDED);
                assertEquals(names,actual.keySet());indexes.forEach((name,sql) -> assertEquals(sql,actual.get(name)));
                assertAddressPlans(statement);
                assertEquals("ok",scalar(statement,"PRAGMA integrity_check"));
                assertEquals("0",scalar(statement,"SELECT count(*) FROM pragma_foreign_key_check"));
            }
            assertThrows(SQLException.class,() -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(candidate));
            assertArrayEquals(bytes,Files.readAllBytes(source));
        }
    }

    @Test
    void refusesOlderAndMixedSourcesWithoutMutation() throws Exception
    {
        Path older = Format38TestDatabase.create(mTemporaryFolder.resolve("older.sqlite"));
        Path mixed = Format39TestDatabase.create(mTemporaryFolder.resolve("mixed.sqlite"));
        try(Connection connection = open(mixed); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_receiver_activity_event_id_channel");
            statement.executeUpdate("CREATE INDEX idx_receiver_activity_event_id_channel ON receiver_activity_event(id,channel_id)");
        }
        for(Path source: List.of(older,mixed))
        {
            byte[] bytes = Files.readAllBytes(source);
            try(Connection connection = open(source))
            {
                String fingerprint = SqliteSchemaValidator.fingerprint(connection);
                Map<String,TableContents> contents = tableContents(connection);
                assertThrows(SQLException.class,() -> new Format39To40DatabaseMigration().migrate(connection));
                assertEquals(fingerprint,SqliteSchemaValidator.fingerprint(connection));assertEquals(contents,tableContents(connection));
            }
            assertArrayEquals(bytes,Files.readAllBytes(source));
        }
    }

    @Test
    void frozenFormat37MatchesItsHistoricalCreator() throws Exception
    {
        Path current = Format40TestDatabase.create(mTemporaryFolder.resolve("historical.sqlite"));
        Path fresh = mTemporaryFolder.resolve("fresh-historical.sqlite");
        try(Connection connection = open(current); Connection freshConnection = open(fresh))
        {
            SdrTrunkDatabaseSchema.create(freshConnection);
            ReceiverActivitySchema.createFormat37(freshConnection);
            DmrActivitySchema.create(freshConnection);
            TrunkedSiteSchema.create(freshConnection);
            assertEquals(DatabaseFormatCatalog.requireVersion(37).fingerprint(),SqliteSchemaValidator.fingerprint(connection));
            assertEquals(SqliteSchemaValidator.fingerprint(freshConnection),SqliteSchemaValidator.fingerprint(connection));
            assertEquals(indexDefinitions(freshConnection),indexDefinitions(connection));
        }
        byte[] bytes = Files.readAllBytes(current);
        assertThrows(SQLException.class,() -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(current));
        assertArrayEquals(bytes,Files.readAllBytes(current));
    }

    private static void assertAddressPlans(Statement statement) throws Exception
    {
        for(String role: List.of("source","target"))
        {
            String index = "idx_receiver_activity_event_"+role+"_identity_address";
            String summary = role.equals("source") ? "900001" : "900003";
            String base = "SELECT channel_id,"+role+"_observed_local_id FROM receiver_activity_event INDEXED BY "+index+
                " WHERE "+role+"_identity_summary_id="+summary+" AND "+role+"_observed_local_id>0 AND ";
            for(String predicate: List.of(role+"_observed_local_id=9002",role+"_observed_local_id BETWEEN 9001 AND 9902"))
            {
                List<String> plan = new ArrayList<>();
                try(ResultSet rows = statement.executeQuery("EXPLAIN QUERY PLAN "+base+predicate))
                {
                    while(rows.next()) plan.add(rows.getString("detail"));
                }
                assertTrue(plan.stream().anyMatch(row -> row.contains("SEARCH") && row.contains("COVERING INDEX "+index) &&
                    row.contains(role+"_identity_summary_id=?") && row.contains(role+"_observed_local_id")),plan::toString);
                assertFalse(plan.stream().anyMatch(row -> row.contains("SCAN receiver_activity_event")),plan::toString);
            }
            String definition = scalar(statement,"SELECT sql FROM sqlite_schema WHERE name='"+index+"'");
            assertTrue(definition.contains("WHERE "+role+"_identity_summary_id IS NOT NULL AND "+role+"_observed_local_id > 0"));
            String count = scalar(statement,"SELECT count(*) FROM receiver_activity_event INDEXED BY "+index+
                " WHERE "+role+"_identity_summary_id IS NOT NULL AND "+role+"_observed_local_id>0");
            assertEquals(role.equals("source") ? "4" : "5",count,"Null identity and nonpositive address rows are excluded");
        }
        List<String> plan = new ArrayList<>();
        try(ResultSet rows = statement.executeQuery("""
            EXPLAIN QUERY PLAN SELECT id FROM receiver_activity_event
            INDEXED BY idx_receiver_activity_event_source_time WHERE source_identity_summary_id=900001
            ORDER BY observed_at_ms DESC,id DESC LIMIT 2
            """)) { while(rows.next()) plan.add(rows.getString("detail")); }
        assertFalse(plan.stream().anyMatch(row -> row.contains("USE TEMP B-TREE")),plan::toString);
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

    private static Map<String,String> indexDefinitions(Connection connection) throws Exception
    {
        Map<String,String> indexes = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT name,sql FROM sqlite_schema WHERE type='index' AND sql IS NOT NULL ORDER BY name"))
        {
            while(rows.next()) indexes.put(rows.getString(1),rows.getString(2).replaceAll("\\s+"," ").trim());
        }
        return indexes;
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
