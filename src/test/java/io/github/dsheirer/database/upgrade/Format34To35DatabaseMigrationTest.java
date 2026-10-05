/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class Format34To35DatabaseMigrationTest
{
    private static final Set<String> REBUILT_INDEXES = Set.of(
        "idx_receiver_activity_event_source_time", "idx_receiver_activity_event_target_time",
        "idx_receiver_activity_event_channel_action_time", "idx_activity_event_member_identity_event");
    @TempDir Path mTemporaryFolder;

    @Test
    void preservesEveryRowAndAllocatorAcrossRollbackAndRetryToExactHistoricalFormat35() throws Exception
    {
        Path source = Format34TestDatabase.create(mTemporaryFolder.resolve("source.sqlite"));
        byte[] sourceBytes = Files.readAllBytes(source);
        Map<String,TableContents> expectedRows;
        Map<String,String> sourceIndexes;
        try(Connection connection = open(source))
        {
            expectedRows = tableContents(connection);
            sourceIndexes = indexDefinitions(connection);
            assertTrue(expectedRows.get("receiver_activity_event").rows() > 0);
            assertTrue(expectedRows.get("activity_event_identity_member").rows() > 0);
            assertTrue(expectedRows.get("configuration_channel").rows() > 0);
            assertTrue(expectedRows.get("web_user").rows() > 0);
            DatabaseMigrationChain.PreflightReport plan = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspect(connection));
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 34, plan.steps().size());
            assertEquals("format-34-to-35", plan.steps().getFirst().id());
            assertEquals(List.of(4L, 1L), plan.steps().getFirst().effects().stream()
                .map(DatabaseMigrationEffect::affectedRows).toList());
        }

        //Normal receiver startup must refuse the prior format without changing it.
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(source));
        assertArrayEquals(sourceBytes, Files.readAllBytes(source));

        for(int attempt = 0; attempt < 2; attempt++)
        {
            Path candidate = Files.copy(source, mTemporaryFolder.resolve("candidate-" + attempt + ".sqlite"));
            try(Connection connection = open(candidate); Statement statement = connection.createStatement())
            {
                connection.setAutoCommit(false);
                new Format34To35DatabaseMigration().migrate(connection);
                connection.rollback();
                assertEquals(DatabaseFormatCatalog.requireVersion(34).fingerprint(),
                    SqliteSchemaValidator.fingerprint(connection));
                assertEquals(sourceIndexes, indexDefinitions(connection));
                assertEquals(expectedRows, tableContents(connection));

                List<DatabaseMigrationEffect> effects =
                    new Format34To35DatabaseMigration().migrateAndReport(connection, false);
                DatabaseFormatCatalog.stamp(connection, 35);
                connection.commit();
                assertEquals(List.of(DatabaseMigrationEffect.Kind.TRANSFORM, DatabaseMigrationEffect.Kind.TRANSFORM),
                    effects.stream().map(DatabaseMigrationEffect::kind).toList());
                assertEquals(List.of(4L, 1L), effects.stream()
                    .map(DatabaseMigrationEffect::affectedRows).toList());
                assertEquals(expectedRows, tableContents(connection),
                    "Only the global format marker changes; every other row and allocator survives");
                assertEquals(DatabaseFormatCatalog.requireVersion(35).fingerprint(),
                    SqliteSchemaValidator.fingerprint(connection));
                assertEquals(35, DatabaseFormatCatalog.inspect(connection).version());
                assertFourRebuiltIndexesAndOneAdded(sourceIndexes, indexDefinitions(connection));
                assertCoveringPlansAndStableTieOrdering(statement);
                assertCoveringMemberEvidencePlan(statement);
                assertEquals("ok", scalar(statement, "PRAGMA integrity_check"));
                try(ResultSet violations = statement.executeQuery("PRAGMA foreign_key_check"))
                {
                    assertFalse(violations.next());
                }
            }
            assertThrows(SQLException.class, () ->
                SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(candidate));
            assertArrayEquals(sourceBytes, Files.readAllBytes(source), "The selected source remains unchanged");
        }
    }

    @Test
    void refusesUnrelatedAndMixedSourcesWithoutMutation() throws Exception
    {
        Path older = Format32TestDatabase.create(mTemporaryFolder.resolve("format32.sqlite"));
        Path prior = Format33TestDatabase.create(mTemporaryFolder.resolve("format33.sqlite"));
        Path mixed = Format34TestDatabase.create(mTemporaryFolder.resolve("mixed.sqlite"));
        try(Connection connection = open(mixed); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_receiver_activity_event_source_time");
            statement.executeUpdate("CREATE INDEX idx_receiver_activity_event_source_time " +
                "ON receiver_activity_event(source_identity_summary_id,channel_id)");
        }
        for(Path database: List.of(older, prior, mixed))
        {
            byte[] before = Files.readAllBytes(database);
            try(Connection connection = open(database))
            {
                String signature = SqliteSchemaValidator.fingerprint(connection);
                assertThrows(SQLException.class, () -> new Format34To35DatabaseMigration().migrate(connection));
                assertEquals(signature, SqliteSchemaValidator.fingerprint(connection));
            }
            assertArrayEquals(before, Files.readAllBytes(database));
        }
    }

    @Test
    void format35FixtureRemainsFrozenAndCannotRunThePreviousAdjacentStep() throws Exception
    {
        Path current = Format35TestDatabase.create(mTemporaryFolder.resolve("current.sqlite"));
        byte[] beforeBytes = Files.readAllBytes(current);
        try(Connection connection = open(current))
        {
            Map<String,TableContents> before = tableContents(connection);
            assertThrows(SQLException.class, () -> new Format34To35DatabaseMigration().migrate(connection));
            assertEquals(before, tableContents(connection));
            assertEquals(DatabaseFormatCatalog.requireVersion(35).fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals(35, DatabaseFormatCatalog.inspect(connection).version());
        }
        assertArrayEquals(beforeBytes, Files.readAllBytes(current));
    }

    private static void assertFourRebuiltIndexesAndOneAdded(Map<String,String> before, Map<String,String> after)
    {
        Set<String> expected = new TreeSet<>(before.keySet());
        expected.add("idx_receiver_activity_event_id_channel");
        assertEquals(expected, after.keySet(), "Only the narrow event/channel projection is added");
        Set<String> changed = new TreeSet<>();
        before.forEach((name, sql) -> { if(!sql.equals(after.get(name))) changed.add(name); });
        assertEquals(REBUILT_INDEXES, changed);
    }

    private static void assertCoveringMemberEvidencePlan(Statement statement) throws Exception
    {
        String query = "SELECT member.observed_local_id,event.channel_id FROM activity_event_identity_member member " +
            "INDEXED BY idx_activity_event_member_identity_event JOIN receiver_activity_event event " +
            "INDEXED BY idx_receiver_activity_event_id_channel ON event.id=member.event_id " +
            "WHERE member.identity_summary_id=900002 AND member.observed_local_id>0";
        List<String> plan = new ArrayList<>();
        try(ResultSet rows = statement.executeQuery("EXPLAIN QUERY PLAN " + query))
        {
            while(rows.next()) plan.add(rows.getString("detail"));
        }
        assertTrue(plan.stream().anyMatch(row -> row.contains("SEARCH member USING COVERING INDEX " +
            "idx_activity_event_member_identity_event") && row.contains("identity_summary_id=?")), plan::toString);
        assertTrue(plan.stream().anyMatch(row -> row.contains("SEARCH event USING COVERING INDEX " +
            "idx_receiver_activity_event_id_channel") && row.contains("id=?")), plan::toString);
        assertFalse(plan.stream().anyMatch(row -> row.contains("SCAN member") || row.contains("SCAN event")),
            plan::toString);
        try(ResultSet rows = statement.executeQuery(query))
        {
            assertTrue(rows.next());
            assertEquals(100, rows.getInt("observed_local_id"));
            assertEquals(900001, rows.getInt("channel_id"));
            assertFalse(rows.next());
        }
    }

    private static void assertCoveringPlansAndStableTieOrdering(Statement statement) throws Exception
    {
        String source = "SELECT id,channel_id,source_observed_local_id,source_observed_working_id " +
            "FROM receiver_activity_event INDEXED BY idx_receiver_activity_event_source_time " +
            "WHERE source_identity_summary_id=900001 AND observed_at_ms BETWEEN 1000 AND 3000 " +
            "ORDER BY observed_at_ms DESC,id DESC LIMIT 2";
        String target = "SELECT id,channel_id,CASE WHEN target_kind_code IN (1,3) THEN target_observed_local_id " +
            "ELSE COALESCE(target_observed_working_id,target_observed_local_id) END AS observed_id " +
            "FROM receiver_activity_event INDEXED BY idx_receiver_activity_event_target_time " +
            "WHERE target_identity_summary_id=900003 AND observed_at_ms BETWEEN 1000 AND 3000 " +
            "ORDER BY observed_at_ms DESC,id DESC LIMIT 2";
        String action = "SELECT id,radio_system_id,source_identity_summary_id,source_observed_local_id " +
            "FROM receiver_activity_event INDEXED BY idx_receiver_activity_event_channel_action_time " +
            "WHERE channel_id=900001 AND action_code=1 AND observed_at_ms BETWEEN 1000 AND 3000 " +
            "ORDER BY observed_at_ms DESC,id DESC LIMIT 2";
        assertCoveringOrderedPlan(statement, source, "idx_receiver_activity_event_source_time");
        assertCoveringOrderedPlan(statement, target, "idx_receiver_activity_event_target_time");
        assertCoveringOrderedPlan(statement, action, "idx_receiver_activity_event_channel_action_time");
        assertEquals(List.of(900004L, 900003L), eventIds(statement, source));
        assertEquals(List.of(900004L, 900003L), eventIds(statement, target));
        assertEquals(List.of(900004L, 900003L), eventIds(statement, action));
        assertEquals(List.of(900002L, 900001L), eventIds(statement, source.replace(
            "ORDER BY observed_at_ms DESC,id DESC LIMIT 2",
            "AND (observed_at_ms<2000 OR observed_at_ms=2000 AND id<900003) " +
                "ORDER BY observed_at_ms DESC,id DESC LIMIT 2")));
        assertTrue(scalar(statement, "SELECT sql FROM sqlite_schema WHERE name=" +
            "'idx_receiver_activity_event_source_time'").contains("WHERE source_identity_summary_id IS NOT NULL"));
        assertTrue(scalar(statement, "SELECT sql FROM sqlite_schema WHERE name=" +
            "'idx_receiver_activity_event_target_time'").contains("WHERE target_identity_summary_id IS NOT NULL"));
    }

    private static void assertCoveringOrderedPlan(Statement statement, String sql, String index) throws Exception
    {
        List<String> plan = new ArrayList<>();
        try(ResultSet rows = statement.executeQuery("EXPLAIN QUERY PLAN " + sql))
        {
            while(rows.next()) plan.add(rows.getString("detail"));
        }
        assertTrue(plan.stream().anyMatch(row -> row.contains("SEARCH") && row.contains("COVERING INDEX " + index)),
            plan::toString);
        assertFalse(plan.stream().anyMatch(row -> row.contains("SCAN receiver_activity_event") ||
            row.contains("USE TEMP B-TREE")), plan::toString);
    }

    private static List<Long> eventIds(Statement statement, String sql) throws Exception
    {
        List<Long> ids = new ArrayList<>();
        try(ResultSet rows = statement.executeQuery(sql)) { while(rows.next()) ids.add(rows.getLong("id")); }
        return ids;
    }

    /** Hashes fixture values so a preservation assertion never prints credentials or preference contents. */
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
            String escaped = table.replace("\"", "\"\"");
            int columns;
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT * FROM \"" + escaped + "\" LIMIT 0"))
            {
                columns = rows.getMetaData().getColumnCount();
            }
            String ordering = java.util.stream.IntStream.rangeClosed(1, columns).mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(","));
            String filter = table.equals("database_metadata") ? " WHERE key<>'database_format_version'" : "";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0;
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT * FROM \"" + escaped + "\"" + filter + " ORDER BY " + ordering))
            {
                while(rows.next())
                {
                    count++;
                    for(int column = 1; column <= columns; column++)
                    {
                        byte[] value = rows.getBytes(column);
                        digest.update((byte)(value == null ? 0 : 1));
                        if(value != null)
                        {
                            digest.update(Integer.toString(value.length).getBytes(StandardCharsets.UTF_8));
                            digest.update((byte)0);
                            digest.update(value);
                        }
                    }
                }
            }
            contents.put(table, new TableContents(count, HexFormat.of().formatHex(digest.digest())));
        }
        return contents;
    }

    private static Map<String,String> indexDefinitions(Connection connection) throws Exception
    {
        Map<String,String> indexes = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT name,sql FROM sqlite_schema WHERE type='index' AND sql IS NOT NULL ORDER BY name"))
        {
            while(rows.next()) indexes.put(rows.getString(1), rows.getString(2).replaceAll("\\s+", " ").trim());
        }
        return indexes;
    }

    private static Connection open(Path database) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try(Statement statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); }
        return connection;
    }

    private static String scalar(Statement statement, String sql) throws SQLException
    {
        try(ResultSet rows = statement.executeQuery(sql)) { return rows.next() ? rows.getString(1) : null; }
    }

    private record TableContents(long rows, String digest) {}
}
