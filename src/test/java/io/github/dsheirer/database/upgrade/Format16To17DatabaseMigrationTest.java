/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format16To17DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void addsOnlyAliasEditorIndexesAndPreservesPopulatedAliases() throws Exception
    {
        Path database = Format16TestDatabase.create(mTemporaryFolder.resolve("format-16.sqlite"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            long aliasesBefore = scalar(statement, "SELECT count(*) FROM alias");
            DatabaseMigrationChain.MigrationReport report = DatabaseMigrationChain.migrate(connection);
            assertEquals(17, report.target().version());
            assertEquals("format-16-to-17", report.steps().getLast().id());
            assertEquals(aliasesBefore, scalar(statement, "SELECT count(*) FROM alias"));
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
            assertTrue(indexExists(statement, "idx_alias_list_id"));
            assertTrue(indexExists(statement, "idx_alias_list_name_sort"));
            assertEquals("ok", statement.executeQuery("PRAGMA integrity_check").getString(1));
        }
    }

    private static long scalar(Statement statement, String sql) throws Exception
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static boolean indexExists(Statement statement, String name) throws Exception
    {
        try(ResultSet rows = statement.executeQuery(
            "SELECT 1 FROM sqlite_schema WHERE type='index' AND name='" + name + "'"))
        {
            return rows.next();
        }
    }
}
