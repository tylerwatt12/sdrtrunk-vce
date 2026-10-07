/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;

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

class Format45To46DatabaseMigrationTest
{
    @TempDir Path mTemporaryFolder;

    @Test
    void preservesEveryStoredValueWithoutInventingDiscoveryIdentityAndCurrentIsNoOp() throws Exception
    {
        Path database = Format45TestDatabase.create(mTemporaryFolder.resolve("source45.sqlite"));
        try(Connection connection = open(database))
        {
            Map<String,List<List<String>>> before = contents(connection);
            assertFalse(before.get("configuration_channel").isEmpty(), "Use a populated prior-format fixture");
            String fingerprint = SqliteSchemaValidator.fingerprint(connection);
            connection.setAutoCommit(false);
            var report = DatabaseMigrationChain.migrate(connection);
            connection.commit();
            assertEquals("format-45-to-46", report.steps().getFirst().id());
            assertEquals(1, report.steps().size());
            assertEquals(before, contents(connection));
            assertEquals(fingerprint, DatabaseFormatCatalog.current().fingerprint());
            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
            assertEquals(before, contents(connection));
        }
    }

    @Test
    void uncommittedSemanticStampRollsBackAndTheExactSourceCanBeRetried() throws Exception
    {
        Path database = Format45TestDatabase.create(mTemporaryFolder.resolve("rollback45.sqlite"));
        byte[] before = Files.readAllBytes(database);
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.rollback();
            assertEquals(45, DatabaseFormatCatalog.inspect(connection).version());
        }
        assertArrayEquals(before, Files.readAllBytes(database));
        try(Connection connection = open(database))
        {
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            connection.commit();
            DatabaseFormatCatalog.requireCurrent(connection);
        }
    }

    @Test
    void sameSchemaRequiresItsMarkerAndMixedSchemaCannotBeStamped() throws Exception
    {
        Path database = Format45TestDatabase.create(mTemporaryFolder.resolve("ambiguous.sqlite"));
        try(Connection connection = open(database); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM database_metadata WHERE key='database_format_version'");
            assertThrows(SQLException.class, () -> DatabaseFormatCatalog.inspect(connection));
            assertThrows(SQLException.class, () -> DatabaseMigrationChain.migrate(connection));
            statement.executeUpdate("INSERT INTO database_metadata(key,value,updated_at_ms) VALUES('database_format_version','45',1)");
            statement.execute("CREATE TABLE unexpected(id INTEGER)");
            assertThrows(SQLException.class, () -> new Format45To46DatabaseMigration().migrate(connection));
            assertThrows(SQLException.class, () -> DatabaseFormatCatalog.stamp(connection, 46));
        }
    }

    private static Map<String,List<List<String>>> contents(Connection connection) throws SQLException
    {
        Map<String,List<List<String>>> result = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
            "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name"))
        {
            while(rows.next()) tables.add(rows.getString(1));
        }
        for(String table: tables)
        {
            List<List<String>> values = new ArrayList<>();
            try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(
                "SELECT * FROM \"" + table.replace("\"", "\"\"") + "\"" +
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
            result.put(table, values);
        }
        return result;
    }

    private static Connection open(Path database) throws SQLException
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }
}
