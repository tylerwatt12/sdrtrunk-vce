/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format20To21DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void copiesExistingDefaultsIntoBothIndependentBehaviors() throws Exception
    {
        Path database = Format20TestDatabase.create(mTemporaryFolder.resolve("format-20.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            long aliasListId = number(statement, "SELECT id FROM alias_list ORDER BY id LIMIT 1");
            long scanListId = number(statement, "SELECT id FROM scan_list ORDER BY id LIMIT 1");
            String configurationId = "21000000-0000-4000-8000-000000000001";
            statement.executeUpdate("UPDATE alias_list SET unmatched_talkgroup_record_enabled=1 WHERE id=" +
                aliasListId);
            statement.executeUpdate("""
                INSERT INTO configuration_broadcast_stream(configuration_id,sort_order,config_json)
                VALUES('%s',2100,'{}')
                """.formatted(configurationId));
            statement.executeUpdate("""
                INSERT INTO alias_list_unmatched_talkgroup_stream(alias_list_id,broadcast_configuration_id)
                VALUES(%d,'%s')
                """.formatted(aliasListId, configurationId));
            statement.executeUpdate("""
                INSERT OR IGNORE INTO alias_list_unmatched_talkgroup_scan_list_membership(alias_list_id,scan_list_id)
                VALUES(%d,%d)
                """.formatted(aliasListId, scanListId));
            long aliasCount = number(statement, "SELECT count(*) FROM alias");

            connection.setAutoCommit(false);
            try
            {
                new Format20To21DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stampForMigration(connection, 21);
                connection.commit();
            }
            catch(Exception exception)
            {
                connection.rollback();
                throw exception;
            }
            finally
            {
                connection.setAutoCommit(true);
            }

            assertEquals(1, number(statement,
                "SELECT new_alias_record_enabled FROM alias_list WHERE id=" + aliasListId));
            assertEquals(1, number(statement, """
                SELECT count(*) FROM alias_list_new_alias_stream
                WHERE alias_list_id=%d AND broadcast_configuration_id='%s'
                """.formatted(aliasListId, configurationId)));
            assertEquals(1, number(statement, """
                SELECT count(*) FROM alias_list_new_alias_scan_list_membership
                WHERE alias_list_id=%d AND scan_list_id=%d
                """.formatted(aliasListId, scanListId)));
            assertEquals(aliasCount, number(statement, "SELECT count(*) FROM alias"));
            assertEquals("ok", text(statement, "PRAGMA integrity_check"));
            assertEquals(0, number(statement, "SELECT count(*) FROM pragma_foreign_key_check"));
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
        }
    }

    private static long number(Statement statement, String sql) throws Exception
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static String text(Statement statement, String sql) throws Exception
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
