/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format36To37DatabaseMigrationTest
{
    private static final String INDEX = "idx_trunked_logical_identity_identity";
    @TempDir Path mTemporaryFolder;

    @Test
    void preservesEveryPopulatedTableAcrossRollbackAndRetryToExactFormat37() throws Exception
    {
        Path source = Format36TestDatabase.create(mTemporaryFolder.resolve("source.sqlite"));
        Map<String, TableSnapshot> before;
        try(Connection connection = open(source); Statement statement = connection.createStatement())
        {
            populateLogicalIdentityBuckets(statement);
            assertEquals(36, DatabaseFormatCatalog.inspect(connection).version());
            before = snapshots(connection);
            assertTrue(before.get("configuration_channel").rows() > 0);
            assertTrue(before.get("application_settings").rows() > 0);
            assertTrue(before.get("web_user").rows() > 0);
        }
        byte[] sourceBytes = Files.readAllBytes(source);
        Path candidate = Files.copy(source, mTemporaryFolder.resolve("candidate.sqlite"));
        try(Connection connection = open(candidate); Statement statement = connection.createStatement())
        {
            assertThrows(SQLException.class, () -> DatabaseFormatCatalog.requireCurrent(connection));
            connection.setAutoCommit(false);
            new Format36To37DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stampForMigration(connection, 37);
            connection.rollback();
            assertEquals(36, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(DatabaseFormatCatalog.requireVersion(36).fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
            assertEquals(before, snapshots(connection), "A failed attempt leaves every source row unchanged");

            var effects = new Format36To37DatabaseMigration().migrateAndReport(connection);
            DatabaseFormatCatalog.stampForMigration(connection, 37);
            connection.commit();
            assertEquals(1, effects.size());
            assertEquals(DatabaseMigrationEffect.Kind.TRANSFORM, effects.getFirst().kind());
            assertEquals(1, effects.getFirst().affectedRows());
            assertEquals(37, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(DatabaseFormatCatalog.requireVersion(37).fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(before, snapshots(connection),
                "The index migration preserves all history, settings, credentials, and personal preferences");
            assertEquals("ok", scalar(statement, "PRAGMA integrity_check"));
            assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());
            assertIndexedRetentionAndForeignKeyCascade(connection, statement);
            assertEquals(before, snapshots(connection), "The cascade check rolls back every temporary delete");
            assertThrows(SQLException.class, () -> new Format36To37DatabaseMigration().migrate(connection));
            assertEquals(before, snapshots(connection), "The frozen target refuses the previous step unchanged");
        }
        assertThrows(SQLException.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabaseForStartup(candidate));
        assertArrayEquals(sourceBytes, Files.readAllBytes(source), "The selected source remains untouched");
    }

    @Test
    void refusesMismatchedSourceWithoutMutatingRowsOrSchema() throws Exception
    {
        Path source = Format35TestDatabase.create(mTemporaryFolder.resolve("wrong-source.sqlite"));
        try(Connection connection = open(source))
        {
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            Map<String, TableSnapshot> before = snapshots(connection);
            assertThrows(SQLException.class, () -> new Format36To37DatabaseMigration().migrate(connection));
            assertEquals(35, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(fingerprint, SqliteSchemaValidator.fingerprint(connection));
            assertEquals(before, snapshots(connection));
        }
    }

    @Test
    void refusesFormat36WithConflictingIndexDefinitionWithoutMutation() throws Exception
    {
        Path source = Format36TestDatabase.create(mTemporaryFolder.resolve("mixed-source.sqlite"));
        try(Connection connection = open(source); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE INDEX " + INDEX +
                " ON trunked_logical_call_identity_bucket(radio_system_id,identity_summary_id)");
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            Map<String, TableSnapshot> before = snapshots(connection);
            assertThrows(SQLException.class, () -> new Format36To37DatabaseMigration().migrate(connection));
            assertEquals("36", scalar(statement,
                "SELECT value FROM database_metadata WHERE key='database_format_version'"));
            assertEquals(fingerprint, SqliteSchemaValidator.fingerprint(connection));
            assertEquals(before, snapshots(connection));
        }
    }

    private static void assertIndexedRetentionAndForeignKeyCascade(Connection connection, Statement statement)
        throws SQLException
    {
        List<String> columns = new ArrayList<>();
        try(ResultSet rows = statement.executeQuery("PRAGMA index_info('" + INDEX + "')"))
        {
            while(rows.next()) columns.add(rows.getString("name"));
        }
        assertEquals(List.of("identity_summary_id", "radio_system_id", "identity_kind_code"), columns);

        List<String> plan = new ArrayList<>();
        try(ResultSet rows = statement.executeQuery("""
            EXPLAIN QUERY PLAN
            SELECT identity.id FROM radio_system_identity_summary identity
            WHERE identity.id=800001
              AND NOT EXISTS (
                SELECT 1 FROM trunked_logical_call_identity_bucket child
                WHERE child.radio_system_id=identity.radio_system_id AND child.identity_summary_id=identity.id
              )
            """))
        {
            while(rows.next()) plan.add(rows.getString("detail"));
        }
        assertTrue(plan.stream().anyMatch(row -> row.contains("SEARCH child USING COVERING INDEX " + INDEX) &&
            row.contains("identity_summary_id=? AND radio_system_id=?")), plan::toString);
        assertFalse(plan.stream().anyMatch(row -> row.contains("SCAN child")), plan::toString);

        long rootPage = Long.parseLong(scalar(statement,
            "SELECT rootpage FROM sqlite_schema WHERE name='" + INDEX + "'"));
        boolean foreignKeyOpensIdentityIndex = false;
        try(ResultSet rows = statement.executeQuery(
            "EXPLAIN DELETE FROM radio_system_identity_summary WHERE id=800001"))
        {
            while(rows.next())
            {
                if("OpenRead".equals(rows.getString("opcode")) && rows.getLong("p2") == rootPage)
                {
                    foreignKeyOpensIdentityIndex = true;
                }
            }
        }
        assertTrue(foreignKeyOpensIdentityIndex,
            "SQLite's implicit foreign-key child lookup must use the identity-leading index");

        Savepoint savepoint = connection.setSavepoint();
        try
        {
            statement.executeUpdate("DELETE FROM radio_system_identity_summary WHERE id=800001");
            assertEquals("0", scalar(statement,
                "SELECT count(*) FROM radio_system_identity_summary WHERE id=800001"));
            assertEquals("0", scalar(statement,
                "SELECT count(*) FROM trunked_logical_call_identity_bucket WHERE identity_summary_id=800001"));
            assertEquals("3999", scalar(statement,
                "SELECT count(*) FROM trunked_logical_call_identity_bucket WHERE identity_summary_id=800002"));
            assertEquals("100", scalar(statement,
                "SELECT count(*) FROM trunked_logical_call_identity_bucket WHERE radio_system_id=800001 " +
                    "AND identity_summary_id BETWEEN 800003 AND 800102"));
        }
        finally
        {
            connection.rollback(savepoint);
            connection.releaseSavepoint(savepoint);
        }
    }

    private static void populateLogicalIdentityBuckets(Statement statement) throws SQLException
    {
        statement.executeUpdate("""
            INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,
                first_seen_ms,last_seen_ms)
            VALUES(800001,'p25:bcdef:234',1,0,0xBCDEF,0x234,1000,3000)
            """);
        statement.executeUpdate("""
            INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,
                home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms)
            VALUES(800001,800001,2,0xBCDEF,0x234,9001,1000,3000),
                  (800002,800001,2,0xBCDEF,0x234,9002,1000,3000)
            """);
        statement.executeUpdate("""
            WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value + 1 FROM n WHERE value < 4000)
            INSERT INTO trunked_logical_call_identity_bucket(
                radio_system_id,bucket_start_ms,identity_role_code,identity_kind_code,identity_summary_id,
                logical_call_count)
            SELECT 800001,value,1,2,CASE WHEN value=4000 THEN 800001 ELSE 800002 END,1 FROM n
            """);
        //Keep a realistic identity-selective distribution even when the shared format-33 fixture supplies
        //another system. Implicit foreign-key checks choose their own path from the analyzed distribution.
        statement.executeUpdate("""
            WITH RECURSIVE n(value) AS (VALUES(3) UNION ALL SELECT value + 1 FROM n WHERE value < 102)
            INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,
                home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms)
            SELECT 800000+value,800001,2,0xBCDEF,0x234,9000+value,1000,3000 FROM n
            """);
        statement.executeUpdate("""
            WITH RECURSIVE n(value) AS (VALUES(3) UNION ALL SELECT value + 1 FROM n WHERE value < 102)
            INSERT INTO trunked_logical_call_identity_bucket(
                radio_system_id,bucket_start_ms,identity_role_code,identity_kind_code,identity_summary_id,
                logical_call_count)
            SELECT 800001,5000+value,1,2,800000+value,1 FROM n
            """);
    }

    /** Compare opaque durable row digests while allowing reproducible SQLite planner statistics to be rebuilt. */
    private static Map<String, TableSnapshot> snapshots(Connection connection) throws Exception
    {
        List<String> tables = new ArrayList<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_stat%' ORDER BY name"))
        {
            while(rows.next()) tables.add(rows.getString(1));
        }
        Map<String, TableSnapshot> snapshots = new TreeMap<>();
        for(String table: tables)
        {
            String query = "SELECT * FROM \"" + table.replace("\"", "\"\"") + "\"";
            if("database_metadata".equals(table)) query += " WHERE key<>'database_format_version'";
            List<String> rowDigests = new ArrayList<>();
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query))
            {
                while(rows.next())
                {
                    MessageDigest rowDigest = MessageDigest.getInstance("SHA-256");
                    for(int column = 1; column <= rows.getMetaData().getColumnCount(); column++)
                    {
                        Object cell = rows.getObject(column);
                        byte[] value = cell instanceof byte[] bytes ? bytes :
                            String.valueOf(cell).getBytes(StandardCharsets.UTF_8);
                        rowDigest.update((byte)(cell == null ? 0 : cell instanceof byte[] ? 1 :
                            cell instanceof Number ? 2 : 3));
                        rowDigest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value.length).array());
                        rowDigest.update(value);
                    }
                    rowDigests.add(HexFormat.of().formatHex(rowDigest.digest()));
                }
            }
            Collections.sort(rowDigests);
            MessageDigest tableDigest = MessageDigest.getInstance("SHA-256");
            for(String rowDigest: rowDigests) tableDigest.update(rowDigest.getBytes(StandardCharsets.US_ASCII));
            snapshots.put(table, new TableSnapshot(rowDigests.size(), HexFormat.of().formatHex(tableDigest.digest())));
        }
        return snapshots;
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

    private record TableSnapshot(long rows, String digest) {}
}
