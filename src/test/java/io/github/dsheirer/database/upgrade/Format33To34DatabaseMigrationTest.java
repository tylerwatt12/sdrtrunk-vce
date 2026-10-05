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
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format33To34DatabaseMigrationTest
{
    @TempDir Path mTemporaryFolder;

    @Test
    void preservesPopulatedHistoryConfigurationAndCountersAcrossRollbackAndRetry() throws Exception
    {
        Path source = Format33TestDatabase.create(mTemporaryFolder.resolve("source.sqlite"));
        try(Connection connection = open(source); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("INSERT INTO statistics_status(key,value,updated_at_ms) VALUES('records_dropped','42300',1000) " +
                "ON CONFLICT(key) DO UPDATE SET value=excluded.value");
        }
        byte[] sourceBytes = Files.readAllBytes(source);
        List<String> before;
        try(Connection connection = open(source)) { before = rows(connection); }
        Path candidate = Files.copy(source, mTemporaryFolder.resolve("candidate.sqlite"));
        try(Connection connection = open(candidate); Statement statement = connection.createStatement())
        {
            assertThrows(SQLException.class, () -> DatabaseFormatCatalog.requireCurrent(connection));
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.rollback();
            assertEquals(33, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, rows(connection));
            var report = DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION - 33, report.steps().size());
            assertEquals("format-33-to-34", report.steps().getFirst().id());
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, report.target().version());
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(before, rows(connection), "Every original field and allocator survives the adjacent chain");
            try(ResultSet result = statement.executeQuery("PRAGMA quick_check"))
            {
                org.junit.jupiter.api.Assertions.assertTrue(result.next());
                assertEquals("ok", result.getString(1));
            }
            assertFalse(statement.executeQuery("PRAGMA foreign_key_check").next());
            assertEquals(0, DatabaseMigrationChain.migrate(connection).steps().size(), "Current retry is a no-op");
        }
        SdrTrunkDatabaseStartup.validateGlobalDatabase(candidate);
        assertArrayEquals(sourceBytes, Files.readAllBytes(source), "The selected source stays untouched");
    }

    @Test
    void rejectsWrongSourceWithoutChangingIt() throws Exception
    {
        Path source = Format32TestDatabase.create(mTemporaryFolder.resolve("wrong.sqlite"));
        try(Connection connection = open(source))
        {
            List<String> before = rows(connection);
            assertThrows(SQLException.class, () -> new Format33To34DatabaseMigration().migrate(connection));
            assertEquals(32, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, rows(connection));
        }
    }

    @Test
    void refusesMarkerlessSemanticAmbiguityEvenWhenOnlyCurrentRepairWouldAdmitDamagedRows() throws Exception
    {
        for(boolean damaged: List.of(false, true))
        {
            Path source = Format33TestDatabase.create(mTemporaryFolder.resolve("markerless-" + damaged + ".sqlite"));
            try(Connection connection = open(source); Statement statement = connection.createStatement())
            {
                statement.executeUpdate("DELETE FROM database_metadata WHERE key='database_format_version'");
                if(damaged)
                {
                    //A damaged bounded component cannot prove which identical-DDL semantics wrote this file.
                    statement.executeUpdate("UPDATE scan_list SET is_default=0 WHERE is_default=1");
                }
                List<String> before = rows(connection);
                SQLException strict = assertThrows(SQLException.class, () -> DatabaseFormatCatalog.inspect(connection));
                SQLException repair = assertThrows(SQLException.class,
                    () -> DatabaseFormatCatalog.inspectForMigration(connection));
                assertTrue(strict.getMessage().contains("ambiguous across formats [33, 34]"));
                assertTrue(repair.getMessage().contains("authoritative database_format_version marker is required"));
                assertEquals(before, rows(connection));
            }
        }
    }

    private static List<String> rows(Connection connection) throws SQLException
    {
        List<String> tables = new ArrayList<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        {
            while(rows.next()) tables.add(rows.getString(1));
        }
        List<String> values = new ArrayList<>();
        for(String table: tables)
        {
            String sql = "SELECT * FROM \"" + table.replace("\"", "\"\"") + "\"";
            if("activity_event_identity_member".equals(table))
            {
                // Later format 36 adds a derived channel; its migration tests validate that backfill separately.
                sql = "SELECT event_id,radio_system_id,identity_summary_id,identity_kind_code,observed_local_id " +
                    "FROM activity_event_identity_member";
            }
            if("database_metadata".equals(table)) sql += " WHERE key<>'database_format_version'";
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
            {
                while(rows.next())
                {
                    StringBuilder value = new StringBuilder(table);
                    for(int column = 1; column <= rows.getMetaData().getColumnCount(); column++)
                    {
                        Object cell = rows.getObject(column);
                        value.append('|').append(cell instanceof byte[] bytes ?
                            java.util.HexFormat.of().formatHex(bytes) : cell);
                    }
                    values.add(value.toString());
                }
            }
        }
        Collections.sort(values);
        return values;
    }

    private static Connection open(Path database) throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try(Statement statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); }
        return connection;
    }
}
