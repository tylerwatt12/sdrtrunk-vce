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

class Format18To19DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void preservesExistingContentAndCreatesEmptyReceiverHealthHistory() throws Exception
    {
        Path database = Format18TestDatabase.create(mTemporaryFolder.resolve("format-18.sqlite"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            long aliases = number(statement, "SELECT COUNT(*) FROM alias");
            long channels = number(statement, "SELECT COUNT(*) FROM configuration_channel");
            long activity = number(statement, "SELECT COUNT(*) FROM receiver_activity_event");
            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspectForMigration(connection));
            assertEquals(4, preflight.steps().size());
            assertEquals("format-18-to-19", preflight.steps().getFirst().id());
            assertEquals(0, preflight.steps().getFirst().effects().getFirst().affectedRows());

            connection.setAutoCommit(false);
            DatabaseMigrationChain.MigrationReport report;
            try
            {
                report = DatabaseMigrationChain.migrate(connection);
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

            assertEquals(DatabaseFormatCatalog.CURRENT_VERSION, report.target().version());
            assertEquals("format-21-to-22", report.steps().getLast().id());
            assertEquals(aliases, number(statement, "SELECT COUNT(*) FROM alias"));
            assertEquals(channels, number(statement, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals(activity, number(statement, "SELECT COUNT(*) FROM receiver_activity_event"));
            assertEquals(0, number(statement, "SELECT COUNT(*) FROM receiver_health_incident"));
            assertEquals("ok", text(statement, "PRAGMA integrity_check"));
            assertEquals(0, number(statement, "SELECT COUNT(*) FROM pragma_foreign_key_check"));
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
