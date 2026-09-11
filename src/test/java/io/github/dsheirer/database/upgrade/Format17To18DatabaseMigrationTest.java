/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format17To18DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void preservesHistoryAndAcceptsFullSupportedSiteRange() throws Exception
    {
        Path database = Format17TestDatabase.create(mTemporaryFolder.resolve("format-17.sqlite"));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            String configurationId = text(statement,
                "SELECT configuration_id FROM configuration_channel ORDER BY id LIMIT 1");
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id, configuration_id, first_seen_ms, last_seen_ms)
                VALUES (500, '%s', 1000, 1000)
                """.formatted(configurationId));
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id, channel_id, observed_at_ms, action_code, observed_site)
                VALUES (600, 500, 1000, 4, 255)
                """);
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code, observed_site)
                VALUES (500, 1001, 4, 256)
                """));

            DatabaseMigrationChain.PreflightReport preflight = DatabaseMigrationChain.validateSource(connection,
                DatabaseFormatCatalog.inspectForMigration(connection));
            assertEquals(1, preflight.steps().size());
            assertEquals("format-17-to-18", preflight.steps().getFirst().id());
            assertEquals(DatabaseMigrationEffect.UNKNOWN_COUNT,
                preflight.steps().getFirst().effects().getFirst().affectedRows());
            assertEquals(1, new Format17To18DatabaseMigration().validateSource(connection)
                .getFirst().affectedRows());

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

            assertEquals(18, report.target().version());
            assertEquals("format-17-to-18", report.steps().getLast().id());
            assertEquals(1, number(statement,
                "SELECT count(*) FROM receiver_activity_event WHERE id=600 AND observed_site=255"));
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code, observed_site)
                VALUES (500, 1001, 4, 4095)
                """);
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO receiver_activity_event(channel_id, observed_at_ms, action_code, observed_site)
                VALUES (500, 1002, 4, 4096)
                """));
            assertEquals("ok", text(statement, "PRAGMA integrity_check"));
            assertEquals(0, number(statement, "SELECT count(*) FROM pragma_foreign_key_check"));
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
        }
    }

    private static long number(Statement statement, String sql) throws SQLException
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }

    private static String text(Statement statement, String sql) throws SQLException
    {
        try(ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getString(1) : null;
        }
    }
}
