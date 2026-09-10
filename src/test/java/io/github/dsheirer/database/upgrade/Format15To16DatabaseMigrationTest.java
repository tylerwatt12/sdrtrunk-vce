/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class Format15To16DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void defaultsOnlyMissingAssignmentAndEnforcesRequiredReference() throws Exception
    {
        Path database = Format15TestDatabase.create(mTemporaryFolder.resolve("missing-assignment.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            long channelId = scalar(connection, """
                SELECT id FROM configuration_channel
                WHERE decoder_type IN ('P25_CONVENTIONAL', 'P25_PHASE1', 'P25_PHASE2')
                ORDER BY id LIMIT 1
                """);
            assertTrue(channelId > 0);
            long channelCount = scalar(connection, "SELECT COUNT(*) FROM configuration_channel");
            statement.executeUpdate("UPDATE configuration_channel SET alias_list_id=NULL WHERE id=" + channelId);

            Format15To16DatabaseMigration migration = new Format15To16DatabaseMigration();
            assertEquals(1, migration.validateSource(connection).get(0).affectedRows());
            assertEquals(0, migration.validateSource(connection).get(1).affectedRows());
            assertEquals(channelCount, migration.validateSource(connection).get(2).affectedRows());

            connection.setAutoCommit(false);
            migration.migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 16);
            connection.commit();
            connection.setAutoCommit(true);

            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM configuration_channel WHERE alias_list_id IS NULL"));
            assertEquals(DatabaseFormatCatalog.current().fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                "UPDATE configuration_channel SET alias_list_id=NULL WHERE id=" + channelId));

            long assignedListId = scalar(connection,
                "SELECT alias_list_id FROM configuration_channel WHERE id=" + channelId);
            statement.execute("PRAGMA foreign_keys=ON");
            assertThrows(SQLException.class,
                () -> statement.executeUpdate("DELETE FROM alias_list WHERE id=" + assignedListId));
        }
    }

    @Test
    void preservesCompatibleCustomAssignment() throws Exception
    {
        Path database = Format15TestDatabase.create(mTemporaryFolder.resolve("custom-assignment.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list(name, family, unmatched_talkgroup_record_enabled)
                VALUES ('County P25', 'P25', 0)
                """);
            long customListId = scalar(connection,
                "SELECT id FROM alias_list WHERE name='County P25'");
            long channelId = scalar(connection, """
                SELECT id FROM configuration_channel
                WHERE decoder_type IN ('P25_CONVENTIONAL', 'P25_PHASE1', 'P25_PHASE2')
                ORDER BY id LIMIT 1
                """);
            statement.executeUpdate("UPDATE configuration_channel SET alias_list_id=" + customListId +
                " WHERE id=" + channelId);

            connection.setAutoCommit(false);
            new Format15To16DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 16);
            connection.commit();

            assertEquals(customListId, scalar(connection,
                "SELECT alias_list_id FROM configuration_channel WHERE id=" + channelId));
        }
    }

    @Test
    void createsAndRoutesACompatibleListWhenTheChannelFamilyHasNone() throws Exception
    {
        Path database = Format15TestDatabase.create(mTemporaryFolder.resolve("missing-family-list.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM alias_list WHERE family='P25'");

            Format15To16DatabaseMigration migration = new Format15To16DatabaseMigration();
            assertTrue(migration.validateSource(connection).get(0).affectedRows() > 0);
            assertEquals(1, migration.validateSource(connection).get(1).affectedRows());

            connection.setAutoCommit(false);
            migration.migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 16);
            connection.commit();

            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM alias_list WHERE family='P25'"));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*)
                FROM configuration_channel AS channel
                JOIN alias_list AS list ON list.id=channel.alias_list_id
                WHERE channel.decoder_type IN ('P25_CONVENTIONAL', 'P25_PHASE1', 'P25_PHASE2')
                  AND list.family<>'P25'
                """));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*)
                FROM alias_list_unmatched_talkgroup_scan_list_membership AS membership
                JOIN alias_list AS list ON list.id=membership.alias_list_id
                JOIN scan_list AS scan ON scan.id=membership.scan_list_id
                WHERE list.family='P25' AND scan.is_default=1
                """));
        }
    }

    @Test
    void rollbackRestoresTheExactFormat15Table() throws Exception
    {
        Path database = Format15TestDatabase.create(mTemporaryFolder.resolve("rollback.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            connection.setAutoCommit(false);
            new Format15To16DatabaseMigration().migrate(connection);
            DatabaseFormatCatalog.stamp(connection, 16);
            connection.rollback();

            assertEquals(15, DatabaseFormatCatalog.inspect(connection).version());
            assertEquals(DatabaseFormatCatalog.requireVersion(15).fingerprint(),
                SqliteSchemaValidator.fingerprint(connection));
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(var statement = connection.createStatement(); var rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }
}
