/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format46To47DatabaseMigrationTest
{
    private static final String PAIR_COLUMNS = "radio_system_id,working_id,p25_subscriber_identity_id," +
        "first_observed_ms,last_observed_ms,last_registration_ms,last_affiliation_ms," +
        "registration_count,affiliation_count,last_evidence_code,last_channel_id";
    @TempDir Path mTemporaryFolder;

    @Test
    void preservesEveryOldValueAndAllocatorWithoutInventingHistoricalTiming() throws Exception
    {
        Path database = Format46TestDatabase.createWithLeaseSiteTopology(mTemporaryFolder.resolve("source46.sqlite"));
        try(Connection connection = open(database))
        {
            Map<String,List<List<String>>> before = contents(connection);
            assertFalse(before.get("p25_wuid_assignment_observation_summary").isEmpty());
            Map<String,String> beforeSchema = schema(connection);
            connection.setAutoCommit(false);
            var report = DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals(List.of("format-46-to-47"), report.steps().stream().map(step -> step.id()).toList());
            assertEquals(before, contents(connection), "Preserve every original field, relationship and allocator");
            assertEquals("0", scalar(connection, """
                SELECT count(*) FROM p25_wuid_assignment_observation_summary
                WHERE last_advertised_lease_minutes IS NOT NULL OR last_expires_at_ms IS NOT NULL
                """));
            Map<String,String> afterSchema = schema(connection);
            assertEquals(beforeSchema.keySet(), afterSchema.keySet(), "Add no table, index, view or trigger");
            beforeSchema.remove("table:p25_wuid_assignment_observation_summary");
            afterSchema.remove("table:p25_wuid_assignment_observation_summary");
            beforeSchema.remove("table:p25_site_snapshot");
            afterSchema.remove("table:p25_site_snapshot");
            assertEquals(beforeSchema, afterSchema, "Only replace the two existing lifetime-bearing definitions");
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(47, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
            assertEquals(before, contents(connection));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
            assertEquals("0", scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void normalizesOnlyLegacyNoExpiryAndPreservesAllSiteChildrenWithRestoredPragmas() throws Exception
    {
        Path database = Format46TestDatabase.createWithLeaseSiteTopology(mTemporaryFolder.resolve("site46.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("UPDATE p25_site_snapshot SET wuid_lease_minutes=7890 WHERE channel_id=5000000000");
            Map<String,List<List<String>>> expected = contents(connection);
            for(List<String> row: expected.get("p25_site_snapshot"))
            {
                if(row.getFirst().equals("5000000000"))
                {
                    row.set(1, null);
                    row.set(15, "0");
                }
            }
            statement.execute("PRAGMA legacy_alter_table=ON");
            connection.setAutoCommit(false);
            var report = DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals(expected, contents(connection));
            assertEquals("1", scalar(connection, "PRAGMA legacy_alter_table"));
            assertEquals(1, report.steps().getFirst().effects().stream()
                .filter(effect -> effect.subject().equals("legacy P25 no-expiry observations"))
                .findFirst().orElseThrow().affectedRows());
            assertEquals("0", scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
            for(String value: List.of("NULL", "0", "240", "7860"))
                statement.executeUpdate("UPDATE p25_site_snapshot SET wuid_lease_minutes=" + value);
            for(String value: List.of("239", "241", "7890", "-1"))
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE p25_site_snapshot SET wuid_lease_minutes=" + value));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
    }

    @Test
    void freshCreationMatchesTheExactMigratedSignature() throws Exception
    {
        Path database = mTemporaryFolder.resolve("fresh47.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        try(Connection connection = open(database))
        {
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(47, DatabaseFormatCatalog.requireCurrent(connection).version());
        }
    }

    @Test
    void interruptionRollsBackToTheExactPriorFileAndRetrySucceeds() throws Exception
    {
        Path database = Format46TestDatabase.create(mTemporaryFolder.resolve("retry46.sqlite"));
        byte[] before = Files.readAllBytes(database);
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            new Format46To47DatabaseMigration().migrate(connection);
            assertThrows(SQLException.class, () -> connection.createStatement().execute(
                "INSERT INTO missing_table VALUES(1)"));
            connection.rollback();
            assertEquals(46, DatabaseFormatCatalog.inspect(connection).version());
        }
        assertArrayEquals(before, Files.readAllBytes(database));
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals(47, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
    }

    @Test
    void startupRefusesOlderFormatWithoutMutationAndNewColumnsRejectMalformedValues() throws Exception
    {
        Path database = Format46TestDatabase.create(mTemporaryFolder.resolve("validation46.sqlite"));
        byte[] source = Files.readAllBytes(database);
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));
        assertArrayEquals(source, Files.readAllBytes(database));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
            for(String value: List.of("NULL", "0", "240", "270", "7860"))
                statement.executeUpdate("UPDATE p25_wuid_assignment_observation_summary " +
                    "SET last_advertised_lease_minutes=" + value);
            for(String value: List.of("-1", "1", "239", "241", "7890", "'unknown'"))
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE p25_wuid_assignment_observation_summary SET last_advertised_lease_minutes=" + value));
            for(String value: List.of("NULL", "1", "9223372036854775807"))
                statement.executeUpdate("UPDATE p25_wuid_assignment_observation_summary SET last_expires_at_ms=" + value);
            for(String value: List.of("0", "-1", "'unknown'"))
                assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "UPDATE p25_wuid_assignment_observation_summary SET last_expires_at_ms=" + value));
            assertEquals("ok", scalar(connection, "PRAGMA quick_check"));
        }
    }

    private static Map<String,List<List<String>>> contents(Connection connection) throws SQLException
    {
        Map<String,List<List<String>>> result = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        {
            while(rows.next()) tables.add(rows.getString(1));
        }
        for(String table: tables)
        {
            List<List<String>> values = new ArrayList<>();
            String columns = table.equals("p25_wuid_assignment_observation_summary") ? PAIR_COLUMNS : "*";
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT " + columns + " FROM \"" + table.replace("\"", "\"\"") + "\"" +
                    (table.equals("database_metadata") ? " WHERE key<>'database_format_version'" : "")))
            {
                while(rows.next())
                {
                    List<String> row = new ArrayList<>();
                    for(int column = 1; column <= rows.getMetaData().getColumnCount(); column++)
                        row.add(rows.getString(column));
                    values.add(row);
                }
            }
            values.sort(java.util.Comparator.comparing(Object::toString));
            result.put(table, values);
        }
        return result;
    }

    private static Map<String,String> schema(Connection connection) throws SQLException
    {
        Map<String,String> result = new LinkedHashMap<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT type,name,sql FROM sqlite_schema WHERE name NOT GLOB 'sqlite_*' ORDER BY type,name"))
        {
            while(rows.next()) result.put(rows.getString(1) + ":" + rows.getString(2), rows.getString(3));
        }
        return result;
    }

    private static String scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getString(1) : null;
        }
    }

    private static Connection open(Path database) throws SQLException
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }
}
