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

class Format35To36DatabaseMigrationTest
{
    @TempDir Path mTemporaryFolder;

    @Test
    void preservesEveryOriginalFieldAndAllocatorAcrossRollbackAndRetry() throws Exception
    {
        Path source = Format35TestDatabase.create(mTemporaryFolder.resolve("source.sqlite"));
        Map<String,TableContents> expected;
        Map<String,String> indexes;
        try(Connection connection = open(source); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO activity_event_identity_member(event_id,radio_system_id,identity_summary_id,
                    identity_kind_code,observed_local_id)
                VALUES(900002,900001,900002,1,NULL),(900003,900001,900002,1,0)
                """);
            expected = tableContents(connection);
            indexes = indexDefinitions(connection);
            assertEquals(3, expected.get("activity_event_identity_member").rows());
            assertTrue(expected.get("web_user").rows() > 0);
            assertTrue(expected.get("configuration_channel").rows() > 0);
            assertEquals(List.of(3L,2L,1L), new Format35To36DatabaseMigration().validateSource(connection).stream()
                .map(DatabaseMigrationEffect::affectedRows).toList());
            assertEquals("8eb528aead7c9b9f052dad03c8e0157ecf1e14055b2ec7fdc4f15c65d982cbce",
                SqliteSchemaValidator.fingerprint(connection), "Already admitted format 35 remains frozen");
        }
        byte[] sourceBytes = Files.readAllBytes(source);
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(source));
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));

        for(int attempt = 0; attempt < 2; attempt++)
        {
            Path candidate = Files.copy(source, mTemporaryFolder.resolve("candidate-" + attempt + ".sqlite"));
            try(Connection connection = open(candidate); Statement statement = connection.createStatement())
            {
                connection.setAutoCommit(false);
                new Format35To36DatabaseMigration().migrate(connection);
                connection.rollback();
                assertEquals(DatabaseFormatCatalog.requireVersion(35).fingerprint(),
                    SqliteSchemaValidator.fingerprint(connection));
                assertEquals(indexes, indexDefinitions(connection));
                assertEquals(expected, tableContents(connection));

                List<DatabaseMigrationEffect> effects = new Format35To36DatabaseMigration().validateSource(connection);
                new Format35To36DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection,36);
                connection.commit();
                assertEquals(List.of(3L,2L,1L), effects.stream()
                    .map(DatabaseMigrationEffect::affectedRows).toList());
                assertEquals(expected, tableContents(connection),
                    "Only the format marker and new member channel are added; original values survive exactly");
                assertEquals(DatabaseFormatCatalog.requireVersion(36).fingerprint(), SqliteSchemaValidator.fingerprint(connection));
                assertEquals(36, DatabaseFormatCatalog.inspect(connection).version());
                assertEquals("0", scalar(statement, """
                    SELECT count(*) FROM activity_event_identity_member member
                    JOIN receiver_activity_event event ON event.id=member.event_id
                    WHERE member.channel_id<>event.channel_id
                    """));
                assertIndexChanges(indexes,indexDefinitions(connection));
                assertEquals("ok", scalar(statement,"PRAGMA integrity_check"));
                assertEquals("0", scalar(statement,"SELECT count(*) FROM pragma_foreign_key_check"));
            }
            byte[] candidateBytes = Files.readAllBytes(candidate);
            assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(candidate));
            assertArrayEquals(candidateBytes,Files.readAllBytes(candidate));
            assertArrayEquals(sourceBytes,Files.readAllBytes(source));
        }
    }

    @Test
    void rejectsOlderMixedOrOrphanSourcesWithoutMutation() throws Exception
    {
        Path older = Format34TestDatabase.create(mTemporaryFolder.resolve("older.sqlite"));
        Path mixed = Format35TestDatabase.create(mTemporaryFolder.resolve("mixed.sqlite"));
        Path orphan = Format35TestDatabase.create(mTemporaryFolder.resolve("orphan.sqlite"));
        Path wrongOwner = Format35TestDatabase.create(mTemporaryFolder.resolve("wrong-owner.sqlite"));
        try(Connection connection = open(mixed); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_receiver_activity_event_id_channel");
            statement.executeUpdate("CREATE INDEX idx_receiver_activity_event_id_channel ON receiver_activity_event(channel_id,id)");
        }
        for(Path database: List.of(orphan,wrongOwner))
        {
            try(Connection connection = DriverManager.getConnection("jdbc:sqlite:"+database);
                Statement statement = connection.createStatement())
            {
                statement.execute("PRAGMA foreign_keys=OFF");
                statement.executeUpdate("INSERT INTO activity_event_identity_member VALUES(" +
                    (database.equals(orphan) ? "9900001,900001" : "900002,9900001") + ",900002,1,100)");
            }
        }
        for(Path database: List.of(older,mixed,orphan,wrongOwner))
        {
            byte[] before = Files.readAllBytes(database);
            try(Connection connection = open(database))
            {
                String fingerprint = SqliteSchemaValidator.fingerprint(connection);
                Map<String,TableContents> contents = tableContents(connection);
                assertThrows(SQLException.class, () -> new Format35To36DatabaseMigration().migrate(connection));
                assertEquals(fingerprint,SqliteSchemaValidator.fingerprint(connection));
                assertEquals(contents,tableContents(connection));
            }
            assertArrayEquals(before,Files.readAllBytes(database));
        }
    }

    @Test
    void adjacentFormat36FixtureRetainsItsExactFrozenContract() throws Exception
    {
        Path current = Format36TestDatabase.create(mTemporaryFolder.resolve("current.sqlite"));
        try(Connection connection = open(current))
        {
            assertEquals(36,DatabaseFormatCatalog.inspect(connection).version());
            assertEquals("017064542dbb5cfab978e1928679e5666fa0c22583970033c01f5b0f322a2be1",
                SqliteSchemaValidator.fingerprint(connection));
            assertFalse(indexDefinitions(connection).containsKey("idx_receiver_activity_event_source_identity_address"));
            assertFalse(indexDefinitions(connection).containsKey("idx_receiver_activity_event_target_identity_address"));
        }
    }

    @Test
    void storedChannelChecksRejectMismatchAndKeepBothOwnerRelationships() throws Exception
    {
        Path database = Format36TestDatabase.create(mTemporaryFolder.resolve("constraints.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            for(String channel: List.of("NULL","0","'wrong'","900002"))
            {
                assertThrows(SQLException.class, () -> statement.executeUpdate("""
                    INSERT INTO activity_event_identity_member(
                        event_id,radio_system_id,identity_summary_id,identity_kind_code,observed_local_id,channel_id)
                    VALUES(900002,900001,900002,1,100,%s)
                    """.formatted(channel)));
            }
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT OR IGNORE INTO activity_event_identity_member VALUES(900002,900001,900002,1,100,900002)
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO activity_event_identity_member VALUES(900002,9900001,900002,1,100,900001)
                """));
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO activity_event_identity_member VALUES(900002,900001,900003,1,100,900001)
                """));
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms)
                SELECT 900002,configuration_id,1000,3000 FROM configuration_channel
                WHERE configuration_id<>(SELECT configuration_id FROM receiver_channel WHERE id=900001)
                ORDER BY id LIMIT 1
                """);
            assertEquals("1",scalar(statement,"SELECT count(*) FROM receiver_channel WHERE id=900002"));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE receiver_activity_event SET channel_id=900002 WHERE id=900001"));
            statement.executeUpdate("DELETE FROM receiver_activity_event WHERE id=900001");
            assertEquals("0",scalar(statement,"SELECT count(*) FROM activity_event_identity_member WHERE event_id=900001"));
            assertEquals("0",scalar(statement,"SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void addressCoversPreserveOrderedActivityAndBoundMemberDistinct() throws Exception
    {
        Path database = Format36TestDatabase.create(mTemporaryFolder.resolve("plans.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            String member = "SELECT DISTINCT identity_summary_id,channel_id,observed_local_id " +
                "FROM activity_event_identity_member INDEXED BY idx_activity_event_member_identity_channel_local " +
                "WHERE identity_summary_id IN (900002,900003) AND observed_local_id>0 ORDER BY 1,2,3";
            assertCoveringOrderedPlan(statement,member,"idx_activity_event_member_identity_channel_local");
            assertCoveringOrderedPlan(statement,"SELECT event_id FROM activity_event_identity_member " +
                "INDEXED BY idx_activity_event_member_identity_event WHERE identity_summary_id=900002 " +
                "ORDER BY event_id DESC LIMIT 10","idx_activity_event_member_identity_event");
            assertCoveringOrderedPlan(statement,"SELECT channel_id FROM p25_site_call_identity_bucket " +
                "INDEXED BY idx_p25_site_call_identity_identity_address WHERE identity_summary_id=900002 " +
                "AND observed_local_id=100 AND observed_local_id>0","idx_p25_site_call_identity_identity_address");
            assertTrue(scalar(statement,"SELECT sql FROM sqlite_schema WHERE name=" +
                "'idx_p25_site_call_identity_identity_address'").contains("WHERE observed_local_id > 0"));
            assertTrue(scalar(statement,"SELECT sql FROM sqlite_schema WHERE name=" +
                "'idx_receiver_activity_event_id_channel'").startsWith("CREATE UNIQUE INDEX"));
        }
    }

    private static void assertIndexChanges(Map<String,String> before, Map<String,String> after)
    {
        Set<String> expected = new TreeSet<>(before.keySet());
        expected.add("idx_activity_event_member_identity_channel_local");
        expected.add("idx_p25_site_call_identity_identity_address");
        assertEquals(expected,after.keySet());
        Set<String> changed = new TreeSet<>();
        before.forEach((name,sql) -> { if(!sql.equals(after.get(name))) changed.add(name); });
        assertEquals(Set.of("idx_receiver_activity_event_id_channel"),changed);
    }

    private static void assertCoveringOrderedPlan(Statement statement, String sql, String index) throws Exception
    {
        List<String> plan = new ArrayList<>();
        try(ResultSet rows = statement.executeQuery("EXPLAIN QUERY PLAN "+sql))
        {
            while(rows.next()) plan.add(rows.getString("detail"));
        }
        assertTrue(plan.stream().anyMatch(row -> row.contains("SEARCH") && row.contains("COVERING INDEX "+index)),plan::toString);
        assertFalse(plan.stream().anyMatch(row -> row.contains("USE TEMP B-TREE")),plan::toString);
    }

    /** Hashes original fields, excluding only the format marker and newly added member channel. */
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
            String projection = table.equals("activity_event_identity_member") ?
                "event_id,radio_system_id,identity_summary_id,identity_kind_code,observed_local_id" : "*";
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
