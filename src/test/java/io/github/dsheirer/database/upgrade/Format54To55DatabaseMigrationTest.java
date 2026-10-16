/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SqliteSchemaValidator;
import io.github.dsheirer.alerts.TopicAlertSchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format54To55DatabaseMigrationTest
{
    @TempDir Path directory;

    @Test void freshSchemaMatchesRegisteredSignature() throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite::memory:"))
        {
            SdrTrunkDatabaseSchema.create(connection); ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection); TrunkedSiteSchema.create(connection); TopicAlertSchema.create(connection);
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
        }
    }

    @Test void populatedPriorMigrationPreservesEveryRowWithoutGuessingHistoricalCallParentsAndRetries() throws Exception
    {
        Path path = Format54TestDatabase.create(directory.resolve("prior.sqlite"));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
        {
            assertEquals(54, DatabaseFormatCatalog.inspect(connection).version());
            sql.execute("ANALYZE");
            Map<String,List<String>> before = contents(connection);
            connection.setAutoCommit(false);
            DatabaseMigrationChain.migrate(connection);
            assertEquals(DatabaseFormatCatalog.current().fingerprint(), SqliteSchemaValidator.fingerprint(connection));
            assertEquals(before, contents(connection));
            connection.rollback();
            assertEquals(54, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(before, contents(connection));
            DatabaseMigrationChain.migrate(connection); connection.commit();
            assertEquals(55, DatabaseFormatCatalog.requireCurrent(connection).version());
            assertEquals(before, contents(connection));
            try(var rows = sql.executeQuery("PRAGMA foreign_key_check")) { assertFalse(rows.next()); }
            try(var rows = sql.executeQuery("PRAGMA integrity_check")) { assertTrue(rows.next()); assertEquals("ok", rows.getString(1)); }
            assertTrue(DatabaseMigrationChain.migrate(connection).steps().isEmpty());
        }
    }

    private static Map<String,List<String>> contents(Connection connection) throws Exception
    {
        Map<String,List<String>> result = new LinkedHashMap<>();
        List<String> tables = new ArrayList<>();
        try(var sql = connection.createStatement(); var rows = sql.executeQuery(
            "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name"))
        { while(rows.next()) tables.add(rows.getString(1)); }
        for(String table: tables)
        {
            List<String> values = new ArrayList<>();
            try(var sql = connection.createStatement(); var rows = sql.executeQuery("SELECT * FROM \"" + table + "\""))
            {
                while(rows.next())
                {
                    if(table.equals("database_metadata") && "database_format_version".equals(rows.getString(1))) continue;
                    List<String> row = new ArrayList<>();
                    for(int i = 1; i <= rows.getMetaData().getColumnCount(); i++)
                    {
                        Object value = rows.getObject(i);
                        row.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value));
                    }
                    values.add(row.toString());
                }
            }
            values.sort(String::compareTo); result.put(table, values);
        }
        return result;
    }
}
