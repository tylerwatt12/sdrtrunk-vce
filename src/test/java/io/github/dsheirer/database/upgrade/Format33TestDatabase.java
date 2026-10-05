/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

/** Exact populated format-33 fixture produced only by the adjacent format-32 migration. */
public final class Format33TestDatabase
{
    private Format33TestDatabase() {}

    public static Path create(Path database) throws Exception
    {
        Format32TestDatabase.create(database);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            try(Statement statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); }
            connection.setAutoCommit(false);
            try
            {
                new Format32To33DatabaseMigration().migrate(connection);
                DatabaseFormatCatalog.stamp(connection, 33);
                populateActivity(connection);
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
            if(DatabaseFormatCatalog.inspect(connection).version() != 33 ||
                !DatabaseFormatCatalog.requireVersion(33).fingerprint().equals(
                    SqliteSchemaValidator.fingerprint(connection)))
            {
                throw new IllegalStateException("Global format 33 fixture signature mismatch");
            }
        }
        return database;
    }

    /** Includes local, working-address, target-role, null-role, equal-time, and member-link evidence. */
    private static void populateActivity(Connection connection) throws Exception
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,
                    first_seen_ms,last_seen_ms)
                VALUES(900001,'p25:abcde:123',1,0,0xABCDE,0x123,1000,3000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,
                    radio_system_id,radio_system_assigned_at_ms)
                SELECT 900001,configuration_id,1000,3000,900001,1000
                FROM configuration_channel ORDER BY id LIMIT 1
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,
                    home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms)
                VALUES(900001,900001,2,0xABCDE,0x123,9001,1000,3000),
                      (900002,900001,1,0xABCDE,0x123,100,1000,3000),
                      (900003,900001,2,-1,-1,9002,1000,3000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,
                    source_identity_summary_id,source_observed_local_id,source_observed_working_id,
                    target_identity_summary_id,target_observed_local_id,target_observed_working_id,target_kind_code)
                VALUES(900001,900001,900001,1000,1,900001,9001,NULL,900002,100,NULL,1),
                      (900002,900001,900001,2000,1,900001,9902,555,900003,9002,777,2),
                      (900003,900001,900001,2000,1,900001,9803,556,900003,9003,NULL,2),
                      (900004,900001,900001,2000,1,900001,9704,NULL,900003,9004,778,2),
                      (900005,900001,900001,3000,2,NULL,NULL,NULL,900002,100,NULL,1),
                      (900006,900001,900001,3000,2,NULL,NULL,NULL,NULL,NULL,NULL,NULL)
                """);
            statement.executeUpdate("""
                INSERT INTO activity_event_identity_member(event_id,radio_system_id,identity_summary_id,
                    identity_kind_code,observed_local_id)
                VALUES(900001,900001,900002,1,100)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_logical_call_identity_bucket(radio_system_id,bucket_start_ms,
                    identity_role_code,identity_kind_code,identity_summary_id,logical_call_count)
                VALUES(900001,1000,1,1,900002,3)
                """);
        }
    }
}
