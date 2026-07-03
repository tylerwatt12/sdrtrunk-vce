/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.radioresolve.activitylog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P25ActivityLogWriterTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void writesActivityEvent() throws Exception
    {
        Path database = mTemporaryFolder.resolve("activity.sqlite");
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, 10);
        writer.start();
        writer.enqueue(activity(1000L, P25ActivityLogRecords.Action.GRANT));

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(writer.getWrittenRecords() < 1 && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        writer.close();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM activity_event"))
        {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }

    @Test
    void appliesRetentionCleanup() throws Exception
    {
        Path database = mTemporaryFolder.resolve("retention.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.initialize(connection);
            P25ActivityLogSchema.insertActivity(connection, activity(1000L, P25ActivityLogRecords.Action.CALL));
            P25ActivityLogSchema.insertActivity(connection, activity(100000L, P25ActivityLogRecords.Action.GRANT));
            P25ActivityLogSchema.deleteOlderThan(connection, 50000L);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT action FROM activity_event"))
            {
                assertTrue(resultSet.next());
                assertEquals(P25ActivityLogRecords.Action.GRANT.name(), resultSet.getString(1));
            }
        }
    }

    @Test
    void storesSchemaVersionInDatabaseMetadata() throws Exception
    {
        Path database = mTemporaryFolder.resolve("schema-metadata.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            P25ActivityLogSchema.initialize(connection);

            try(ResultSet resultSet = statement.executeQuery(
                "SELECT value FROM database_metadata WHERE key='p25_activity_schema_version'"))
            {
                assertTrue(resultSet.next());
                assertEquals("5", resultSet.getString(1));
            }

            try(ResultSet resultSet = statement.executeQuery("PRAGMA user_version"))
            {
                assertTrue(resultSet.next());
                assertEquals(0, resultSet.getInt(1));
            }

            assertColumnAbsent(connection, "talkgroup_summary", "last_frequency_hz");
            assertColumnAbsent(connection, "talkgroup_summary", "last_lcn");
            assertColumnAbsent(connection, "radio_user_summary", "last_frequency_hz");
            assertColumnAbsent(connection, "radio_user_summary", "last_lcn");
        }
    }

    @Test
    void updatesAggregateSummaries() throws Exception
    {
        Path database = mTemporaryFolder.resolve("summaries.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.initialize(connection);
            P25ActivityLogSchema.insertActivity(connection, activity(1000L, P25ActivityLogRecords.Action.GRANT));
            P25ActivityLogSchema.insertActivity(connection, activity(2000L, P25ActivityLogRecords.Action.CONTINUE));

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                    "SELECT hits, grant_count, continue_count, encrypted_count FROM talkgroup_summary"))
            {
                assertTrue(resultSet.next());
                assertEquals(2, resultSet.getInt("hits"));
                assertEquals(1, resultSet.getInt("grant_count"));
                assertEquals(1, resultSet.getInt("continue_count"));
                assertEquals(1, resultSet.getInt("encrypted_count"));
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                    "SELECT hits, grant_count, encrypted_count FROM radio_user_summary"))
            {
                assertTrue(resultSet.next());
                assertEquals(2, resultSet.getInt("hits"));
                assertEquals(1, resultSet.getInt("grant_count"));
                assertEquals(1, resultSet.getInt("encrypted_count"));
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                    "SELECT hits, grant_count, continue_count FROM frequency_summary"))
            {
                assertTrue(resultSet.next());
                assertEquals(2, resultSet.getInt("hits"));
                assertEquals(1, resultSet.getInt("grant_count"));
                assertEquals(1, resultSet.getInt("continue_count"));
            }
        }
    }

    @Test
    void upsertsStableSiteEntities() throws Exception
    {
        Path database = mTemporaryFolder.resolve("site.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.initialize(connection);
            P25ActivityLogRecords.SiteSnapshot snapshot = siteSnapshot(1000L);
            P25ActivityLogSchema.insertSite(connection, snapshot);
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(2000L));

            assertCount(connection, "site_snapshot", 1);
            assertCount(connection, "site_channel", 1);
            assertCount(connection, "site_frequency_band", 1);
            assertCount(connection, "site_neighbor", 1);
            assertCount(connection, "site_patch_group", 1);
            assertCount(connection, "site_patch_group_talkgroup", 1);
            assertCount(connection, "site_patch_group_radio", 1);
            assertCount(connection, "site_talker_alias", 1);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT seen_count FROM site_snapshot"))
            {
                assertTrue(resultSet.next());
                assertEquals(2, resultSet.getInt("seen_count"));
            }
        }
    }

    @Test
    void honorsRecordTypeLoggingOptions() throws Exception
    {
        Path database = mTemporaryFolder.resolve("filtered.sqlite");
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, false, true, false, 10);
        writer.start();
        writer.enqueue(activity(1000L, P25ActivityLogRecords.Action.GRANT));
        writer.enqueue(siteSnapshot(2000L));

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(writer.getWrittenRecords() < 1 && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        writer.close();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertCount(connection, "activity_event", 0);
            assertCount(connection, "site_snapshot", 1);
            assertCount(connection, "logger_status", 0);
        }
    }

    @Test
    void dropsOldestWhenQueueIsFull() throws Exception
    {
        Path database = mTemporaryFolder.resolve("overflow.sqlite");
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, 1);
        writer.start();

        for(int x = 0; x < 1000; x++)
        {
            writer.enqueue(activity(1000L + x, P25ActivityLogRecords.Action.GRANT));
        }

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(writer.getWrittenRecords() == 0 && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        writer.close();
        assertTrue(writer.getDroppedRecords() > 0);
    }

    private static void assertCount(Connection connection, String table, int expected) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            assertTrue(resultSet.next());
            assertEquals(expected, resultSet.getInt(1));
        }
    }

    private static void assertColumnAbsent(Connection connection, String table, String column) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(resultSet.next())
            {
                assertFalse(column.equals(resultSet.getString("name")));
            }
        }
    }

    private static P25ActivityLogRecords.ActivityEvent activity(long timestamp, P25ActivityLogRecords.Action action)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "123e4567-e89b-12d3-a456-426614174000",
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25", action, "CALL_GROUP", "1811524", "56138",
            "TALKGROUP", 854187500L, "00-0509", "00-0509", 1, "ENCRYPTED", "PHASE 1 CHANNEL GRANT",
            action == P25ActivityLogRecords.Action.GRANT, action == P25ActivityLogRecords.Action.GRANT ? 0x84 : null,
            action == P25ActivityLogRecords.Action.GRANT ? 101 : null, 0xBEE00, 0x348, 0x348, 2, 1, "Example Site",
            "P25-1", null, null, null);
    }

    private static P25ActivityLogRecords.SiteSnapshot siteSnapshot(long timestamp)
    {
        List<P25NetworkConfigurationSnapshot.Channel> channels = List.of(
            new P25NetworkConfigurationSnapshot.Channel("primary_control", "00-0821", 856137500L, null, false, 1));
        List<P25NetworkConfigurationSnapshot.NeighborSite> neighbors = List.of(
            new P25NetworkConfigurationSnapshot.NeighborSite(0x348, 0x348, 2, 2, null, "00-0661", 855137500L,
                null, "ACTIVE"));
        List<P25NetworkConfigurationSnapshot.FrequencyBand> bands = List.of(
            new P25NetworkConfigurationSnapshot.FrequencyBand(0, false, 851006250L, 12500, 6250L,
                -45000000L, 1));
        List<P25NetworkConfigurationSnapshot.PatchGroup> patches = List.of(
            new P25NetworkConfigurationSnapshot.PatchGroup(56182, 1, List.of(56180), List.of(1811524)));
        List<P25NetworkConfigurationSnapshot.TalkerAlias> aliases = List.of(
            new P25NetworkConfigurationSnapshot.TalkerAlias(1811524, "WPFF205"));

        return new P25ActivityLogRecords.SiteSnapshot(timestamp, "123e4567-e89b-12d3-a456-426614174000",
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "hash", "APCO25", "Example Site", "Example System A", "P25-1",
            0xBEE00, 0x348, 0x348, 2, 1, 856137500L, 856137500L, channels, neighbors, bands, patches, aliases);
    }
}
