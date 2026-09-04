/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end coverage for the writer boundary using the current saved-channel identity model. */
class ReceiverActivityWriterTest
{
    private static final String CONFIGURATION_ID = "123e4567-e89b-42d3-a456-426614174000";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void writesThroughConfigurationChannelToTheNativeP25RadioSystem() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("writer.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, true, 16, 1, 0);
        writer.start();
        writer.enqueue(activity(ReceiverActivityRecords.Action.GRANT, 1_700_000_000_000L));
        writer.close();

        assertEquals(ReceiverActivityStatus.State.STOPPED, writer.getStatus().state());
        assertEquals(1, writer.getWrittenRecords());
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(CONFIGURATION_ID, text(connection, """
                SELECT channel.configuration_id
                FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                """));
            assertEquals("p25:bee00:3a9", text(connection, """
                SELECT system.system_key
                FROM receiver_channel channel
                JOIN radio_system system ON system.id=channel.radio_system_id
                """));
            assertEquals(1, scalar(connection, "SELECT grant_count FROM trunked_signaling_activity_bucket"));
        }
    }

    @Test
    void closeDrainsAnAcceptedPartialBatch() throws Exception
    {
        Path database = createDatabase(mTemporaryFolder.resolve("drain.sqlite"));
        insertConfiguredChannel(database);
        ReceiverActivityWriter writer = new ReceiverActivityWriter(database, 30, false, 8, 100, 60_000);
        writer.start();
        writer.enqueue(activity(ReceiverActivityRecords.Action.DENIAL, 1_700_000_000_100L));
        writer.close();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals(1, scalar(connection, "SELECT denial_count FROM trunked_signaling_activity_bucket"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));
        }
    }

    private static ReceiverActivityRecords.ActivityEvent activity(ReceiverActivityRecords.Action action,
                                                                   long timestamp)
    {
        return new ReceiverActivityRecords.ActivityEvent(timestamp, CONFIGURATION_ID,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "APCO25", action, "CALL_GROUP", "1811524",
            "56138", "TALKGROUP", List.of(), 854_187_500L, "00-0509", 1, false, null, null,
            0xBEE00, 0x3A9, 0x293, 2, 1, null, action == ReceiverActivityRecords.Action.CALL, null, null,
            ReceiverActivityRecords.IdentityDomain.STANDARD, ReceiverActivityRecords.P25TargetIdentity.ORDINARY,
            List.of());
    }

    private static void insertConfiguredChannel(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO configuration_channel(
                    configuration_id, channel_kind, sort_order, system_name, site_name, name,
                    radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json
                ) VALUES (
                    '%s', 'TRUNKED', 0, 'Capture system', 'Capture site', 'Control',
                    'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa', 0, 'P25_PHASE1', 854187500, '{}'
                )
                """.formatted(CONFIGURATION_ID));
        }
    }

    private static Path createDatabase(Path database) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
        }
        return database;
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static String text(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }
}
