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

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
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
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, true, 10);
        writer.start();
        writer.enqueue(activity(1000L, P25ActivityLogRecords.Action.GRANT));

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(writer.getWrittenRecords() < 1 && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        P25ActivityLogWriter.WriterStatus runningStatus = writer.getStatus();
        assertEquals(P25ActivityLogStatus.State.RUNNING, runningStatus.state());
        assertTrue(runningStatus.detailedHistoryEnabled());
        assertTrue(runningStatus.lastSuccessfulWriteMs() > 0);
        assertEquals(1, runningStatus.recordsWritten());
        writer.close();
        assertEquals(P25ActivityLogStatus.State.STOPPED, writer.getStatus().state());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM p25_activity_event"))
        {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }

        P25ActivityLogWriter restarted = new P25ActivityLogWriter(database, 30, false, 10);
        restarted.start();
        deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(restarted.getStatus().state() != P25ActivityLogStatus.State.RUNNING &&
            System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        P25ActivityLogWriter.WriterStatus restoredStatus = restarted.getStatus();
        assertEquals(P25ActivityLogStatus.State.RUNNING, restoredStatus.state());
        assertEquals(1, restoredStatus.recordsWritten());
        assertTrue(restoredStatus.lastSuccessfulWriteMs() > 0);
        restarted.close();
    }

    @Test
    void reportsWriterFailure() throws Exception
    {
        Path missingDatabase = mTemporaryFolder.resolve("missing.sqlite");
        P25ActivityLogWriter writer = new P25ActivityLogWriter(missingDatabase, 30, false, 10);
        writer.start();

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(writer.getStatus().state() != P25ActivityLogStatus.State.FAILED &&
            System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        P25ActivityLogWriter.WriterStatus status = writer.getStatus();
        assertEquals(P25ActivityLogStatus.State.FAILED, status.state());
        assertFalse(status.detailedHistoryEnabled());
        assertEquals(0, status.lastSuccessfulWriteMs());
        assertTrue(status.lastError().contains("IOException"));
        writer.close();
    }

    @Test
    void learnsVoiceChannelsFromControlChannelGrants() throws Exception
    {
        Path database = mTemporaryFolder.resolve("voice-channel.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(1000L, P25ActivityLogRecords.Action.GRANT), true);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT channel_key, role, downlink_hz, traffic_observations
                    FROM p25_site_channel_summary
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals("0-509", resultSet.getString("channel_key"));
                assertEquals("traffic", resultSet.getString("role"));
                assertEquals(854187500L, resultSet.getLong("downlink_hz"));
                assertEquals(1, resultSet.getInt("traffic_observations"));
            }
        }
    }

    @Test
    void reportsDetailedActivityOnlyAfterCommit() throws Exception
    {
        Path database = mTemporaryFolder.resolve("committed.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        AtomicReference<List<Long>> committed = new AtomicReference<>();
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, true, committed::set);
        writer.start();
        writer.enqueue(activity(1000L, P25ActivityLogRecords.Action.GRANT));

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(committed.get() == null && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        writer.close();
        assertEquals(List.of(1L), committed.get());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM p25_activity_event WHERE id = 1"))
        {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt(1));
        }
    }

    @Test
    void appliesRetentionCleanup() throws Exception
    {
        Path database = mTemporaryFolder.resolve("retention.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection, activity(1000L, P25ActivityLogRecords.Action.CALL), true);
            P25ActivityLogSchema.recordActivity(connection, activity(100000L, P25ActivityLogRecords.Action.GRANT), true);
            P25ActivityLogSchema.deleteOlderThan(connection, 50000L);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT action FROM p25_activity_event_resolved"))
            {
                assertTrue(resultSet.next());
                assertEquals(P25ActivityLogRecords.Action.GRANT.name(), resultSet.getString(1));
            }
        }
    }

    @Test
    void maintenanceDeletesExpiredRowsAndUpdatesStatus() throws Exception
    {
        Path database = mTemporaryFolder.resolve("maintenance.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        long now = System.currentTimeMillis();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection, activity(now - TimeUnit.DAYS.toMillis(2),
                P25ActivityLogRecords.Action.CALL), true);
            P25ActivityLogSchema.recordActivity(connection, activity(now, P25ActivityLogRecords.Action.GRANT), true);
        }

        P25ActivityLogMaintenance.Result result =
            P25ActivityLogMaintenance.run(database, 1, P25ActivityLogMaintenance.Operation.MAINTAIN);

        assertEquals(P25ActivityLogMaintenance.Operation.MAINTAIN, result.operation());
        assertTrue(result.rowsDeleted() > 0);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertCount(connection, "p25_activity_event", 1);
            assertEquals("1", status(connection, "retention_days"));
            assertEquals(Long.toString(result.rowsDeleted()), status(connection, "last_maintenance_deleted_rows"));
        }
    }

    @Test
    void maintenanceCheckReportsOk() throws Exception
    {
        Path database = mTemporaryFolder.resolve("check.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        P25ActivityLogMaintenance.Result result =
            P25ActivityLogMaintenance.run(database, 30, P25ActivityLogMaintenance.Operation.CHECK);

        assertTrue(result.checkOk());
        assertEquals("ok", result.checkResult());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertEquals("ok", status(connection, "last_integrity_check_result"));
        }
    }

    @Test
    void storesSchemaVersionInDatabaseMetadata() throws Exception
    {
        Path database = mTemporaryFolder.resolve("schema-metadata.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            try(ResultSet resultSet = statement.executeQuery(
                "SELECT value FROM database_metadata WHERE key='p25_activity_schema_version'"))
            {
                assertTrue(resultSet.next());
                assertEquals("14", resultSet.getString(1));
            }

            try(ResultSet resultSet = statement.executeQuery("PRAGMA user_version"))
            {
                assertTrue(resultSet.next());
                assertEquals(0, resultSet.getInt(1));
            }

            assertColumnAbsent(connection, "p25_talkgroup_summary", "last_frequency_hz");
            assertColumnAbsent(connection, "p25_talkgroup_summary", "last_lcn");
            assertColumnAbsent(connection, "p25_radio_summary", "last_frequency_hz");
            assertColumnAbsent(connection, "p25_radio_summary", "last_lcn");
            assertColumnAbsent(connection, "p25_activity_event", "service");
            assertColumnAbsent(connection, "p25_activity_event", "details");
            assertColumnAbsent(connection, "p25_talkgroup_summary", "hits");
            assertColumnAbsent(connection, "p25_activity_event", "wacn");
            assertColumnAbsent(connection, "p25_activity_event", "system_id");
            assertColumnAbsent(connection, "p25_activity_event", "nac");
            assertColumnAbsent(connection, "p25_activity_event", "rfss");
            assertColumnAbsent(connection, "p25_activity_event", "site");
            assertColumnAbsent(connection, "p25_activity_event", "channel_name");
            assertColumnAbsent(connection, "p25_activity_event", "decoder");
            assertColumnAbsent(connection, "radio_context", "last_snapshot_hash");
            assertColumnAbsent(connection, "p25_site_neighbor", "nac");
            assertColumnAbsent(connection, "p25_site_channel", "observation_count");
            assertColumnAbsent(connection, "p25_site_neighbor", "observation_count");
            assertColumnAbsent(connection, "receiver_context", "wacn");
            assertColumnAbsent(connection, "receiver_context", "system_id");
            assertEquals(0, count(connection, "p25_radio_affiliation"));
        }
    }

    @Test
    void storesReplacesAndClearsCurrentRadioAffiliation() throws Exception
    {
        Path database = mTemporaryFolder.resolve("affiliation.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection, affiliation(1000L, 1811524, 56133), true);
            assertAffiliation(connection, 1811524, 56133, 1000L);

            P25ActivityLogSchema.recordActivity(connection, affiliation(2000L, 1811524, 56538), true);
            assertEquals(1, count(connection, "p25_radio_affiliation"));
            assertAffiliation(connection, 1811524, 56538, 2000L);

            P25ActivityLogSchema.recordActivity(connection, affiliation(3000L, 1811524, null), true);
            assertEquals(0, count(connection, "p25_radio_affiliation"));
        }
    }

    @Test
    void olderAffiliationCannotReplaceNewerState() throws Exception
    {
        Path database = mTemporaryFolder.resolve("affiliation-order.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection, affiliation(2000L, 1811524, 56538), false);
            P25ActivityLogSchema.recordActivity(connection, affiliation(1000L, 1811524, 56133), false);
            assertAffiliation(connection, 1811524, 56538, 2000L);
        }
    }

    @Test
    void updatesAggregateSummaries() throws Exception
    {
        Path database = mTemporaryFolder.resolve("summaries.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection, activity(1000L, P25ActivityLogRecords.Action.GRANT), true);
            P25ActivityLogSchema.recordActivity(connection, activity(2000L, P25ActivityLogRecords.Action.CONTINUE), true);
            P25ActivityLogSchema.recordActivity(connection, activity(3000L, P25ActivityLogRecords.Action.CALL), true);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                    "SELECT call_count, grant_count, continue_count, encrypted_count FROM p25_talkgroup_summary"))
            {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("call_count"));
                assertEquals(1, resultSet.getInt("grant_count"));
                assertEquals(1, resultSet.getInt("continue_count"));
                assertEquals(1, resultSet.getInt("encrypted_count"));
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                    "SELECT call_count, grant_count, encrypted_count FROM p25_radio_summary"))
            {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("call_count"));
                assertEquals(1, resultSet.getInt("grant_count"));
                assertEquals(1, resultSet.getInt("encrypted_count"));
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                    "SELECT call_count, grant_count, continue_count FROM p25_site_frequency_summary"))
            {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("call_count"));
                assertEquals(1, resultSet.getInt("grant_count"));
                assertEquals(1, resultSet.getInt("continue_count"));
            }

            assertCount(connection, "p25_radio_talkgroup_summary", 1);
            assertActionCount(connection, "p25_radio_talkgroup_summary", "call_count", 1);
            assertActionCount(connection, "p25_site_talkgroup_bucket", "call_count", 1);
            assertActionCount(connection, "p25_site_activity_bucket", "call_count", 1);
        }
    }

    @Test
    void lateTalkerAliasUpdateDoesNotInflateActivityCounters() throws Exception
    {
        Path database = mTemporaryFolder.resolve("talker-alias.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(1000L, P25ActivityLogRecords.Action.CALL), true);
            P25ActivityLogSchema.updateTalkerAlias(connection, new P25ActivityLogRecords.TalkerAliasUpdate(
                2000L, "GUID:123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174000", 0xBEE00, 0x348, 1811524, "CAR 201"));
            P25ActivityLogSchema.updateTalkerAlias(connection, new P25ActivityLogRecords.TalkerAliasUpdate(
                1500L, "GUID:123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174000", 0xBEE00, 0x348, 1811524, "OLDER"));

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT call_count, grant_count, encrypted_count, last_talker_alias,
                        last_talker_alias_seen_ms, last_seen_ms
                    FROM p25_radio_summary
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("call_count"));
                assertEquals(0, resultSet.getInt("grant_count"));
                assertEquals(0, resultSet.getInt("encrypted_count"));
                assertEquals("CAR 201", resultSet.getString("last_talker_alias"));
                assertEquals(2000L, resultSet.getLong("last_talker_alias_seen_ms"));
                assertEquals(2000L, resultSet.getLong("last_seen_ms"));
            }
        }
    }

    @Test
    void conventionalCallCountersCountCalls() throws Exception
    {
        Path database = mTemporaryFolder.resolve("conventional-hits.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                conventionalActivity(1000L, P25ActivityLogRecords.Action.GRANT), false);
            P25ActivityLogSchema.recordActivity(connection,
                conventionalActivity(2000L, P25ActivityLogRecords.Action.CALL), false);

            assertActionCount(connection, "conventional_activity_summary", "call_count", 1);
            assertActionCount(connection, "conventional_activity_bucket", "call_count", 1);
            assertActionCount(connection, "conventional_activity_summary", "grant_count", 1);
        }
    }

    @Test
    void trunkedActivityDoesNotStoreLogicalChannelAsContextName() throws Exception
    {
        Path database = mTemporaryFolder.resolve("context-name.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection, activityWithChannelName(1000L, "0-825"), true);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT channel_name FROM receiver_context"))
            {
                assertTrue(resultSet.next());
                assertEquals(null, resultSet.getString("channel_name"));
            }

            P25ActivityLogSchema.insertSite(connection, siteSnapshot(2000L));

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT channel_name FROM receiver_context"))
            {
                assertTrue(resultSet.next());
                assertEquals("Example Site", resultSet.getString("channel_name"));
            }
        }
    }

    @Test
    void upsertsStableSiteEntities() throws Exception
    {
        Path database = mTemporaryFolder.resolve("site.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(1000L));
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(2000L));

            assertCount(connection, "p25_site_snapshot", 1);
            assertCount(connection, "p25_site_channel", 1);
            assertCount(connection, "p25_site_frequency_band", 1);
            assertCount(connection, "p25_site_neighbor", 1);
            assertCount(connection, "p25_site_patch_group", 1);
            assertCount(connection, "p25_site_patch_group_talkgroup", 1);
            assertCount(connection, "p25_site_patch_group_radio", 1);
            assertCount(connection, "p25_system", 1);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT observation_count FROM p25_site_snapshot"))
            {
                assertTrue(resultSet.next());
                assertEquals(2, resultSet.getInt("observation_count"));
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                    "SELECT observation_count FROM p25_site_neighbor_summary"))
            {
                assertTrue(resultSet.next());
                assertEquals(2, resultSet.getInt("observation_count"));
            }
        }
    }

    @Test
    void replacesCurrentSiteFactsButKeepsHistoricalObservations() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-site.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(1000L));
            P25ActivityLogRecords.SiteSnapshot empty = new P25ActivityLogRecords.SiteSnapshot(2000L,
                "123e4567-e89b-12d3-a456-426614174000", P25ActivityLogRecords.ContextKind.TRUNKED_SITE,
                "changed", "APCO25", "Example Site", "Example System", "P25-1", 0xBEE00, 0x348, 0x348, 2, 1,
                856137500L, null, List.of(), List.of(), List.of(), List.of(), List.of());
            P25ActivityLogSchema.insertSite(connection, empty);

            assertCount(connection, "p25_site_channel", 0);
            assertCount(connection, "p25_site_neighbor", 0);
            assertCount(connection, "p25_site_patch_group", 0);
            assertCount(connection, "p25_site_channel_summary", 1);
            assertCount(connection, "p25_site_neighbor_summary", 1);
            assertCount(connection, "p25_site_patch_group_summary", 1);
        }
    }

    @Test
    void mergesSystemEntitiesAcrossSiteGuidsButKeepsSiteBucketsSeparate() throws Exception
    {
        Path database = mTemporaryFolder.resolve("multi-site.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(1000L, P25ActivityLogRecords.Action.GRANT,
                    "123e4567-e89b-12d3-a456-426614174000"), false);
            P25ActivityLogSchema.recordActivity(connection,
                activity(2000L, P25ActivityLogRecords.Action.GRANT,
                    "223e4567-e89b-12d3-a456-426614174000"), false);

            assertCount(connection, "p25_system", 1);
            assertCount(connection, "receiver_context", 2);
            assertCount(connection, "p25_talkgroup_summary", 1);
            assertCount(connection, "p25_radio_summary", 1);
            assertCount(connection, "p25_radio_talkgroup_summary", 1);
            assertCount(connection, "p25_site_talkgroup_bucket", 2);
            assertCount(connection, "p25_site_activity_bucket", 2);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT grant_count FROM p25_talkgroup_summary
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(2, resultSet.getInt("grant_count"));
            }
        }
    }

    @Test
    void usesEstablishedGuidSystemWhenTrafficEventOmitsSystemIdentity() throws Exception
    {
        Path database = mTemporaryFolder.resolve("established-system.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(1000L));
            P25ActivityLogSchema.recordActivity(connection, activityWithoutSystemIdentity(2000L), false);

            assertCount(connection, "p25_system", 1);
            assertCount(connection, "p25_talkgroup_summary", 1);
            assertCount(connection, "p25_radio_summary", 1);
            assertCount(connection, "p25_radio_talkgroup_summary", 1);
        }
    }

    @Test
    void writesAllStatsRecordTypes() throws Exception
    {
        Path database = mTemporaryFolder.resolve("all-stats.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, true, 10);
        writer.start();
        writer.enqueue(activity(1000L, P25ActivityLogRecords.Action.GRANT));
        writer.enqueue(siteSnapshot(2000L));

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(writer.getWrittenRecords() < 2 && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        writer.close();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertCount(connection, "p25_activity_event", 1);
            assertCount(connection, "p25_site_snapshot", 1);
            assertTrue(count(connection, "logger_status") > 0);
            assertTrue(Long.parseLong(status(connection, "last_successful_write_ms")) > 0);
        }
    }

    @Test
    void dropsOldestWhenQueueIsFull() throws Exception
    {
        Path database = mTemporaryFolder.resolve("overflow.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, true, 1);
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
        assertEquals(expected, count(connection, table));
    }

    private static int count(Connection connection, String table) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static String status(Connection connection, String key) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "SELECT value FROM logger_status WHERE key='" + key + "'"))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static void assertActionCount(Connection connection, String table, String column, int expected)
        throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT " + column + " FROM " + table))
        {
            assertTrue(resultSet.next());
            assertEquals(expected, resultSet.getInt(column));
        }
    }

    private static void assertAffiliation(Connection connection, int radioId, int talkgroupId, long updatedAt)
        throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT system.wacn, system.system_id, affiliation.radio_id, affiliation.talkgroup_id,
                    affiliation.updated_at_ms
                FROM p25_radio_affiliation affiliation
                JOIN p25_system system ON system.system_key = affiliation.system_key
                """))
        {
            assertTrue(resultSet.next());
            assertEquals(0xBEE00, resultSet.getInt("wacn"));
            assertEquals(0x348, resultSet.getInt("system_id"));
            assertEquals(radioId, resultSet.getInt("radio_id"));
            assertEquals(talkgroupId, resultSet.getInt("talkgroup_id"));
            assertEquals(updatedAt, resultSet.getLong("updated_at_ms"));
            assertFalse(resultSet.next());
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
        return activity(timestamp, action, "123e4567-e89b-12d3-a456-426614174000");
    }

    private static P25ActivityLogRecords.ActivityEvent activity(long timestamp, P25ActivityLogRecords.Action action,
                                                                String guid)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:" + guid,
            guid, P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25",
            action, "CALL_GROUP", "1811524", "56138", "TALKGROUP", 854187500L, "00-0509", 1,
            action == P25ActivityLogRecords.Action.GRANT,
            action == P25ActivityLogRecords.Action.GRANT ? 0x84 : null,
            action == P25ActivityLogRecords.Action.GRANT ? 101 : null, 0xBEE00, 0x348, 0x348, 2, 1,
            "Example Site", null, null, action == P25ActivityLogRecords.Action.CALL, null, null);
    }

    private static P25ActivityLogRecords.ActivityEvent activityWithChannelName(long timestamp, String channelName)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-12d3-a456-426614174000", P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25",
            P25ActivityLogRecords.Action.GRANT, "CALL_GROUP", "1811524", "56138", "TALKGROUP", 854187500L,
            "00-0509", 1, true, 0x84, 101, 0xBEE00, 0x348, 0x348, 2, 1, channelName, null, null,
            false, null, null);
    }

    private static P25ActivityLogRecords.ActivityEvent conventionalActivity(long timestamp,
                                                                             P25ActivityLogRecords.Action action)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "CONVENTIONAL_ANALOG:NBFM:154310000", null,
            P25ActivityLogRecords.ContextKind.CONVENTIONAL_ANALOG, "NBFM", action, "CALL", null, null, null,
            154310000L, null, null, false, null, null, null, null, null, null, null, "County Fire", "NBFM",
            null, action == P25ActivityLogRecords.Action.CALL, null, null);
    }

    private static P25ActivityLogRecords.ActivityEvent affiliation(long timestamp, int radioId, Integer talkgroupId)
    {
        P25ActivityLogRecords.Action action = talkgroupId != null ? P25ActivityLogRecords.Action.JOIN :
            P25ActivityLogRecords.Action.LOGOUT;
        return new P25ActivityLogRecords.ActivityEvent(timestamp,
            "GUID:123e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-12d3-a456-426614174000",
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25", action,
            talkgroupId != null ? "AFFILIATE" : "DEREGISTER", Integer.toString(radioId),
            talkgroupId != null ? talkgroupId.toString() : null, talkgroupId != null ? "TALKGROUP" : null,
            null, null, null, false, null, null, 0xBEE00, 0x348, 0x348, 2, 1, "Example Site", null, null,
            false, null, new P25ActivityLogRecords.RadioAffiliationUpdate(radioId, talkgroupId));
    }

    private static P25ActivityLogRecords.ActivityEvent activityWithoutSystemIdentity(long timestamp)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp,
            "GUID:123e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-12d3-a456-426614174000",
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25", P25ActivityLogRecords.Action.GRANT,
            "CALL_GROUP", "1811524", "56138", "TALKGROUP", 854187500L, "00-0509", 1, false,
            null, null, null, null, null, null, null, "Example Site", null, null, false, null, null);
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
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "hash", "APCO25", "Example Site", "Example System", "P25-1",
            0xBEE00, 0x348, 0x348, 2, 1, 856137500L, 856137500L, channels, neighbors, bands, patches, aliases);
    }
}
