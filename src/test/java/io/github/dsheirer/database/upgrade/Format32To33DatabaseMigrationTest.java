/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format32To33DatabaseMigrationTest
{
    private static final String INDEX = "idx_receiver_activity_event_source_working_evidence";
    @TempDir Path mTemporaryFolder;

    @Test
    void preservesSourceAndEventsAcrossRollbackAndRetryToExactCurrentSchema() throws Exception
    {
        Path source = Format32TestDatabase.create(mTemporaryFolder.resolve("source.sqlite"));
        try(Connection connection = open(source); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,
                    first_seen_ms,last_seen_ms)
                VALUES(900001,'p25:abcde:123',1,0,0xABCDE,0x123,1000,3000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,
                    radio_system_id,radio_system_assigned_at_ms)
                SELECT 900001,configuration_id,1000,3000,900001,1000
                FROM configuration_channel ORDER BY id LIMIT 1
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,
                    home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms)
                VALUES(900001,900001,2,0xABCDE,0x123,9001,1000,3000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,
                    source_identity_summary_id,source_observed_local_id,source_observed_working_id)
                VALUES(900001,900001,900001,1000,1,900001,9001,NULL),
                      (900002,900001,900001,2000,1,900001,9001,555)
                """);
        }
        byte[] sourceBytes = Files.readAllBytes(source);
        List<String> expectedEvents;
        try(Connection connection = open(source)) { expectedEvents = eventRows(connection); }
        for(int attempt = 0; attempt < 2; attempt++)
        {
            Path candidate = Files.copy(source, mTemporaryFolder.resolve("candidate-" + attempt + ".sqlite"));
            try(Connection connection = open(candidate); Statement statement = connection.createStatement())
            {
                connection.setAutoCommit(false);
                new Format32To33DatabaseMigration().migrate(connection);
                connection.rollback();
                assertEquals(DatabaseFormatCatalog.requireVersion(32).fingerprint(),
                    SqliteSchemaValidator.fingerprint(connection));
                assertEquals(expectedEvents, eventRows(connection));

                DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
                connection.commit();
                assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 32, report.steps().size());
                assertEquals("format-32-to-33", report.steps().getFirst().id());
                assertEquals(1, report.steps().getFirst().effects().getFirst().affectedRows());
                assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
                assertEquals(expectedEvents, eventRows(connection));
                assertEquals("ok", scalar(statement, "PRAGMA integrity_check"));
                assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());
                assertSparseCoveringPlan(statement);
            }
            SdrTrunkDatabaseStartup.validateGlobalDatabase(candidate);
            assertArrayEquals(sourceBytes, Files.readAllBytes(source), "Migration leaves the selected source unchanged");
        }
    }

    @Test
    void refusesAnUnrelatedHistoricalSourceWithoutSchemaMutation() throws Exception
    {
        Path database = Format31TestDatabase.create(mTemporaryFolder.resolve("format31.sqlite"));
        try(Connection connection = open(database))
        {
            String before = SqliteSchemaValidator.fingerprint(connection);
            assertThrows(SQLException.class, () -> new Format32To33DatabaseMigration().migrate(connection));
            assertEquals(before, SqliteSchemaValidator.fingerprint(connection));
            assertEquals(31, DatabaseFormatCatalog.inspect(connection).version());
        }
    }

    private static void assertSparseCoveringPlan(Statement statement) throws Exception
    {
        assertEquals("CREATE INDEX " + INDEX + " ON receiver_activity_event(source_identity_summary_id, " +
            "channel_id, source_observed_working_id) WHERE source_identity_summary_id IS NOT NULL " +
            "AND source_observed_working_id > 0", scalar(statement,
                "SELECT sql FROM sqlite_schema WHERE name='" + INDEX + "'").replaceAll("\\s+", " ").trim());
        String query = "SELECT channel_id,source_observed_working_id FROM receiver_activity_event " +
            "WHERE source_identity_summary_id=900001 AND source_observed_working_id>0";
        List<String> plan = new ArrayList<>();
        try(ResultSet rows = statement.executeQuery("EXPLAIN QUERY PLAN " + query))
        {
            while(rows.next()) plan.add(rows.getString("detail"));
        }
        //Format 38 also covers this projection with the general source/time index. SQLite may prefer either
        //identity equality seek on this small fixture; the sparse index still has its own exact DDL contract.
        assertTrue(plan.stream().anyMatch(row -> row.contains("SEARCH receiver_activity_event") &&
            row.contains("COVERING INDEX ") && row.contains("source_identity_summary_id=?") &&
            (row.contains("COVERING INDEX " + INDEX + " ") ||
                row.contains("COVERING INDEX idx_receiver_activity_event_source_time "))), plan::toString);
        assertFalse(plan.stream().anyMatch(row -> row.contains("SCAN receiver_activity_event")), plan::toString);
        List<String> sparsePlan = new ArrayList<>();
        try(ResultSet rows = statement.executeQuery("EXPLAIN QUERY PLAN " + query.replace(
            "FROM receiver_activity_event ", "FROM receiver_activity_event INDEXED BY " + INDEX + " ")))
        {
            while(rows.next()) sparsePlan.add(rows.getString("detail"));
        }
        assertTrue(sparsePlan.stream().anyMatch(row -> row.contains("SEARCH receiver_activity_event") &&
            row.contains("COVERING INDEX " + INDEX + " ") && row.contains("source_identity_summary_id=?")),
            sparsePlan::toString);
        try(ResultSet rows = statement.executeQuery(query))
        {
            assertTrue(rows.next());
            assertEquals(555, rows.getInt("source_observed_working_id"));
            assertFalse(rows.next(), "Null historical Working IDs stay outside the sparse evidence lookup");
        }
    }

    private static List<String> eventRows(Connection connection) throws Exception
    {
        List<String> events = new ArrayList<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("""
            SELECT id,channel_id,source_identity_summary_id,source_observed_local_id,source_observed_working_id
            FROM receiver_activity_event ORDER BY id
            """))
        {
            while(rows.next()) events.add(rows.getLong(1) + ":" + rows.getLong(2) + ":" + rows.getLong(3) + ":" +
                rows.getObject(4) + ":" + rows.getObject(5));
        }
        return events;
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
}
