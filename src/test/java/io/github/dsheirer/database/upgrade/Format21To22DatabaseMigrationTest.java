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

class Format21To22DatabaseMigrationTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void preservesIdentityCountersAndAllowsNullGroupStatistics() throws Exception
    {
        Path database = Format21TestDatabase.create(mTemporaryFolder.resolve("format-21.sqlite"));
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            long channelId = number(statement, "SELECT id FROM receiver_channel ORDER BY id LIMIT 1");
            if(channelId == 0)
            {
                String configurationId = text(statement,
                    "SELECT configuration_id FROM configuration_channel ORDER BY id LIMIT 1");
                statement.executeUpdate("""
                    INSERT INTO receiver_channel(configuration_id,first_seen_ms,last_seen_ms)
                    VALUES('%s',1000,1000)
                    """.formatted(configurationId));
                channelId = number(statement, "SELECT id FROM receiver_channel ORDER BY id DESC LIMIT 1");
            }
            statement.executeUpdate("""
                INSERT INTO conventional_call_identity_bucket(
                    channel_id,bucket_start_ms,identity_role_code,identity_kind_code,identity_id,
                    call_count,encrypted_count,recorded_count,streamed_count)
                VALUES(%d,3600000,1,1,91,7,2,3,4)
                """.formatted(channelId));
            long finalChannelId = channelId;
            assertThrows(SQLException.class, () -> statement.executeUpdate("""
                INSERT INTO conventional_call_identity_bucket(
                    channel_id,bucket_start_ms,identity_role_code,identity_kind_code,identity_id,
                    call_count,encrypted_count,recorded_count,streamed_count)
                VALUES(%d,3600000,1,1,0,1,0,0,0)
                """.formatted(finalChannelId)));

            assertEquals(1, new Format21To22DatabaseMigration().validateSource(connection)
                .getFirst().affectedRows());
            connection.setAutoCommit(false);
            try
            {
                new Format21To22DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stampForMigration(connection, 22);
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

            assertEquals(7, number(statement, """
                SELECT call_count FROM conventional_call_identity_bucket
                WHERE channel_id=%d AND identity_kind_code=1 AND identity_id=91
                """.formatted(channelId)));
            statement.executeUpdate("""
                INSERT INTO conventional_call_identity_bucket(
                    channel_id,bucket_start_ms,identity_role_code,identity_kind_code,identity_id,
                    call_count,encrypted_count,recorded_count,streamed_count)
                VALUES(%d,3600000,1,1,0,1,0,1,1)
                """.formatted(channelId));
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
