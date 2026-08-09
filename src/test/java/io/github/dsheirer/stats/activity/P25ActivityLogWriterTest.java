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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.channel.metadata.activity.ChannelTag;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
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
    void closeDrainsACollectingPartialBatch() throws Exception
    {
        Path database = mTemporaryFolder.resolve("close-drains.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, true, 10);
        writer.start();
        writer.enqueue(activity(1_000L, P25ActivityLogRecords.Action.GRANT));
        writer.close();

        assertEquals(1, writer.getWrittenRecords());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertCount(connection, "p25_activity_event", 1);
        }
    }

    @Test
    void writesEachCompletedDmrConventionalCallExactlyOnce() throws Exception
    {
        Path database = mTemporaryFolder.resolve("dmr-conventional.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, false, 10);
        P25ActivityLogRecords.DmrConventionalCall call = new P25ActivityLogRecords.DmrConventionalCall(
            1_000L, 2_000L, "GUID:dmr-writer", "dmr-writer", "DMR Repeater",
            "County DMR", 461_125_000L, 1, P25ActivityLogRecords.DmrTargetKind.GROUP, 91, 101, null, false);
        writer.start();
        writer.enqueue(call);
        writer.enqueue(call);

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(writer.getWrittenRecords() < 2 && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        assertEquals(2, writer.getWrittenRecords());
        writer.close();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            try(ResultSet resultSet = statement.executeQuery(
                "SELECT call_count FROM dmr_conventional_talkgroup_summary"))
            {
                assertTrue(resultSet.next());
                assertEquals(2, resultSet.getInt(1));
            }

            try(ResultSet resultSet = statement.executeQuery(
                "SELECT call_count FROM conventional_activity_summary"))
            {
                assertTrue(resultSet.next());
                assertEquals(2, resultSet.getInt(1));
            }

            assertCount(connection, "p25_activity_event", 0);
        }
    }

    @Test
    void reportsDetailedDmrConventionalActivityOnlyAfterCommit() throws Exception
    {
        Path database = mTemporaryFolder.resolve("dmr-conventional-detailed.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        AtomicReference<List<Long>> committed = new AtomicReference<>();
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, true, committed::set);
        writer.start();
        writer.enqueue(new P25ActivityLogRecords.DmrConventionalCall(
            1_000L, 2_000L, "GUID:dmr-detailed", "dmr-detailed", "DMR Repeater",
            "County DMR", 461_125_000L, 2, P25ActivityLogRecords.DmrTargetKind.GROUP, 91, 101, null, false));

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(committed.get() == null && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        writer.close();
        assertEquals(List.of(1L), committed.get());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertCount(connection, "p25_activity_event", 1);
            assertEquals(3L, scalarLong(connection, """
                SELECT kind_code FROM receiver_context WHERE context_key='GUID:dmr-detailed'
                """));
            assertEquals(3L, scalarLong(connection, """
                SELECT protocol_code FROM receiver_context WHERE context_key='GUID:dmr-detailed'
                """));
            assertEquals("DMR Repeater", scalarString(connection, """
                SELECT channel_name FROM receiver_context WHERE context_key='GUID:dmr-detailed'
                """));
            assertEquals("County DMR", scalarString(connection, """
                SELECT alias_list_name FROM receiver_context WHERE context_key='GUID:dmr-detailed'
                """));
            assertEquals("DMR", scalarString(connection, """
                SELECT decoder FROM receiver_context WHERE context_key='GUID:dmr-detailed'
                """));
            assertEquals(461_125_000L, scalarLong(connection, """
                SELECT primary_frequency_hz FROM receiver_context WHERE context_key='GUID:dmr-detailed'
                """));
            assertEquals(1L, scalarLong(connection, """
                SELECT COUNT(*) FROM receiver_context
                WHERE context_key='GUID:dmr-detailed' AND current_control_hz IS NULL
                  AND system_key IS NULL AND nac IS NULL AND rfss IS NULL AND site IS NULL
                """));
            assertEquals(1L, scalarLong(connection,
                "SELECT call_count FROM conventional_activity_summary"));
            assertEquals(1L, scalarLong(connection,
                "SELECT call_count FROM dmr_conventional_talkgroup_summary"));
            assertEquals("CONVENTIONAL_DMR", scalarString(connection,
                "SELECT channel_kind FROM p25_activity_event_resolved"));
            assertEquals("CALL", scalarString(connection,
                "SELECT action FROM p25_activity_event_resolved"));
        }
    }

    @Test
    void reportsDetailedNxdnConventionalActivityOnlyAfterCommit() throws Exception
    {
        Path database = mTemporaryFolder.resolve("nxdn-conventional-detailed.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        AtomicReference<List<Long>> committed = new AtomicReference<>();
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, true, committed::set);
        writer.start();
        writer.enqueue(new P25ActivityLogRecords.NxdnConventionalCall(
            1_000L, 2_000L, "GUID:nxdn-detailed", "nxdn-detailed", "NXDN Repeater",
            "County NXDN", 461_125_000L, P25ActivityLogRecords.NxdnTargetKind.GROUP, 91, 101, null, true));
        writer.enqueue(new P25ActivityLogRecords.CompletedCallOutput(
            1_000L, "GUID:nxdn-detailed", "nxdn-detailed", 461_125_000L, null, 91, "TALKGROUP",
            List.of(), 101, P25ActivityLogRecords.CallOutput.RECORDED));

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(committed.get() == null && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        writer.close();
        assertEquals(List.of(1L), committed.get());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertCount(connection, "p25_activity_event", 1);
            assertEquals(4L, scalarLong(connection, """
                SELECT kind_code FROM receiver_context WHERE context_key='GUID:nxdn-detailed'
                """));
            assertEquals(4L, scalarLong(connection, """
                SELECT protocol_code FROM receiver_context WHERE context_key='GUID:nxdn-detailed'
                """));
            assertEquals("NXDN Repeater", scalarString(connection, """
                SELECT channel_name FROM receiver_context WHERE context_key='GUID:nxdn-detailed'
                """));
            assertEquals("County NXDN", scalarString(connection, """
                SELECT alias_list_name FROM receiver_context WHERE context_key='GUID:nxdn-detailed'
                """));
            assertEquals("NXDN", scalarString(connection, """
                SELECT decoder FROM receiver_context WHERE context_key='GUID:nxdn-detailed'
                """));
            assertEquals(461_125_000L, scalarLong(connection, """
                SELECT primary_frequency_hz FROM receiver_context WHERE context_key='GUID:nxdn-detailed'
                """));
            assertEquals(1L, scalarLong(connection, """
                SELECT COUNT(*) FROM receiver_context
                WHERE context_key='GUID:nxdn-detailed' AND current_control_hz IS NULL
                  AND system_key IS NULL AND nac IS NULL AND rfss IS NULL AND site IS NULL
                """));
            assertEquals(1L, scalarLong(connection,
                "SELECT call_count FROM conventional_activity_summary"));
            assertEquals(1L, scalarLong(connection,
                "SELECT recorded_count FROM conventional_activity_summary"));
            assertEquals(2L, scalarLong(connection,
                "SELECT COUNT(*) FROM call_identity_bucket"));
            assertEquals(2L, scalarLong(connection,
                "SELECT SUM(recorded_count) FROM call_identity_bucket"));
            assertEquals("CONVENTIONAL_NXDN", scalarString(connection,
                "SELECT channel_kind FROM p25_activity_event_resolved"));
            assertEquals("NXDN", scalarString(connection,
                "SELECT protocol FROM p25_activity_event_resolved"));
            assertEquals("CALL", scalarString(connection,
                "SELECT action FROM p25_activity_event_resolved"));
        }
    }

    @Test
    void bucketsDmrPrivateCallAndOutputsByCallStartForBothIdentities() throws Exception
    {
        Path database = mTemporaryFolder.resolve("dmr-private-identity.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String guid = "dmr-private";
        String contextKey = "GUID:" + guid;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogRecords.DmrConventionalCall call = new P25ActivityLogRecords.DmrConventionalCall(
                3_599_900L, 3_600_100L, contextKey, guid, "DMR Private", "County DMR",
                461_125_000L, 2, P25ActivityLogRecords.DmrTargetKind.PRIVATE, null, 101, 202, true);
            P25ActivityLogSchema.recordDmrConventionalCall(connection, call);
            assertTrue(P25ActivityLogSchema.applyCompletedCallOutput(connection,
                new P25ActivityLogRecords.CompletedCallOutput(3_599_900L, contextKey, guid,
                    461_125_000L, 2, 202, "RADIO", List.of(), 101,
                    P25ActivityLogRecords.CallOutput.RECORDED)));
            assertTrue(P25ActivityLogSchema.applyCompletedCallOutput(connection,
                new P25ActivityLogRecords.CompletedCallOutput(3_599_900L, contextKey, guid,
                    461_125_000L, 2, 202, "RADIO", List.of(), 101,
                    P25ActivityLogRecords.CallOutput.STREAMED)));

            assertEquals(2, count(connection, "call_identity_bucket"));
            assertIdentityBucket(connection, 0L, P25ActivityLogSchema.IDENTITY_ROLE_DESTINATION,
                P25ActivityLogSchema.IDENTITY_KIND_RADIO, 202, 1, 1, 1, 1);
            assertIdentityBucket(connection, 0L, P25ActivityLogSchema.IDENTITY_ROLE_SOURCE,
                P25ActivityLogSchema.IDENTITY_KIND_RADIO, 101, 1, 1, 1, 1);
            assertEquals(1L, scalarLong(connection,
                "SELECT call_count FROM conventional_activity_bucket WHERE bucket_start_ms=0"));
            assertEquals(0L, scalarLong(connection,
                "SELECT COUNT(*) FROM conventional_activity_bucket WHERE bucket_start_ms=3600000"));
        }
    }

    @Test
    void failsAndRollsBackBatchContainingInvalidDmrIdentity() throws Exception
    {
        Path database = mTemporaryFolder.resolve("invalid-dmr.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, false, 10);
        writer.start();
        writer.enqueue(new P25ActivityLogRecords.DmrConventionalCall(
            1_000L, 2_000L, "GUID:valid-dmr", "valid-dmr", "Valid DMR", null, 461_125_000L, 1,
            P25ActivityLogRecords.DmrTargetKind.GROUP, 91, 101, null, false));
        writer.enqueue(new P25ActivityLogRecords.DmrConventionalCall(
            3_000L, 4_000L, "GUID:invalid-dmr", "invalid-dmr", "Invalid DMR", null, 461_125_000L, 1,
            P25ActivityLogRecords.DmrTargetKind.GROUP, DmrActivitySchema.MAXIMUM_DMR_ID + 1, 102, null, false));

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(writer.getStatus().state() != P25ActivityLogStatus.State.FAILED &&
            System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        assertEquals(P25ActivityLogStatus.State.FAILED, writer.getStatus().state());
        assertEquals(0, writer.getWrittenRecords());
        writer.close();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertCount(connection, "receiver_context", 0);
            assertCount(connection, "dmr_conventional_talkgroup_summary", 0);
            assertCount(connection, "conventional_activity_summary", 0);
        }
    }

    @Test
    void failsAndRollsBackBatchContainingInvalidNxdnIdentity() throws Exception
    {
        Path database = mTemporaryFolder.resolve("invalid-nxdn.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, false, 10);
        writer.start();
        writer.enqueue(new P25ActivityLogRecords.NxdnConventionalCall(
            1_000L, 2_000L, "GUID:valid-nxdn", "valid-nxdn", "Valid NXDN", null, 461_125_000L,
            P25ActivityLogRecords.NxdnTargetKind.GROUP, 91, 101, null, false));
        writer.enqueue(new P25ActivityLogRecords.NxdnConventionalCall(
            3_000L, 4_000L, "GUID:invalid-nxdn", "invalid-nxdn", "Invalid NXDN", null, 461_125_000L,
            P25ActivityLogRecords.NxdnTargetKind.GROUP, 0x1_0000, 102, null, false));

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(writer.getStatus().state() != P25ActivityLogStatus.State.FAILED &&
            System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        assertEquals(P25ActivityLogStatus.State.FAILED, writer.getStatus().state());
        assertEquals(0, writer.getWrittenRecords());
        writer.close();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertCount(connection, "receiver_context", 0);
            assertCount(connection, "conventional_activity_summary", 0);
        }
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
            recordConfirmedActivity(connection, activity(1000L, P25ActivityLogRecords.Action.GRANT), true);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT channel.channel_key, channel.downlink_hz, tag.tag, tag.observation_count
                    FROM p25_site_channel_summary channel
                    JOIN p25_site_channel_tag_summary tag
                      ON tag.guid = channel.guid AND tag.channel_key = channel.channel_key
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals("0-509", resultSet.getString("channel_key"));
                assertEquals(854187500L, resultSet.getLong("downlink_hz"));
                assertEquals("VOICE", resultSet.getString("tag"));
                assertEquals(1, resultSet.getInt("observation_count"));
            }
        }
    }

    @Test
    void accumulatesVoiceAndDataEvidenceWithoutChangingEncryptedDataStatus() throws Exception
    {
        Path database = mTemporaryFolder.resolve("mixed-service-channel.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            recordConfirmedActivity(connection,
                serviceActivity(1000L, "CALL_GROUP", false, 854_187_500L, "00-0509"), true);
            recordConfirmedActivity(connection,
                serviceActivity(2000L, "DATA_CALL", false, 854_187_500L, "0-509"), true);
            recordConfirmedActivity(connection,
                serviceActivity(3000L, "DATA_CALL_ENCRYPTED", true, 854_187_500L, "0-509"), true);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT tag, observation_count
                    FROM p25_site_channel_tag_summary
                    ORDER BY tag
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals("DATA", resultSet.getString("tag"));
                assertEquals(2, resultSet.getInt("observation_count"));
                assertTrue(resultSet.next());
                assertEquals("VOICE", resultSet.getString("tag"));
                assertEquals(1, resultSet.getInt("observation_count"));
                assertFalse(resultSet.next());
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT event_type, encrypted
                    FROM p25_activity_event_resolved
                    WHERE observed_at_ms = 3000
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals("DATA_CALL_ENCRYPTED", resultSet.getString("event_type"));
                assertEquals(1, resultSet.getInt("encrypted"));
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
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(1000L));
            P25ActivityLogSchema.recordActivity(connection, activity(1000L, P25ActivityLogRecords.Action.CALL), true);
            recordConfirmedActivity(connection, activity(100000L, P25ActivityLogRecords.Action.GRANT), true);
            assertCount(connection, "call_identity_bucket", 2);
            P25ActivityLogSchema.deleteOlderThan(connection, 50000L);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT action FROM p25_activity_event_resolved"))
            {
                assertTrue(resultSet.next());
                assertEquals(P25ActivityLogRecords.Action.GRANT.name(), resultSet.getString(1));
            }

            assertCount(connection, "p25_site_channel_tag", 0);
            assertCount(connection, "call_identity_bucket", 0);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT tag FROM p25_site_channel_tag_summary"))
            {
                assertTrue(resultSet.next());
                assertEquals("VOICE", resultSet.getString("tag"));
                assertFalse(resultSet.next());
            }
        }
    }

    @Test
    void storesBucketsAndDeletesExpiredControlChannelQuality() throws Exception
    {
        Path database = mTemporaryFolder.resolve("quality-retention.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertControlChannelQuality(connection, quality(1_000L, -25.0));
            P25ActivityLogSchema.insertControlChannelQuality(connection, quality(2_000L, -20.0));
            P25ActivityLogSchema.insertControlChannelQuality(connection, quality(100_000L, -15.0));

            assertEquals(2, count(connection, "p25_control_channel_quality"));

            try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery("""
                SELECT observed_at_ms, signal_dbfs FROM p25_control_channel_quality
                WHERE bucket_start_ms = 0
                """))
            {
                assertTrue(resultSet.next());
                assertEquals(2_000L, resultSet.getLong("observed_at_ms"));
                assertEquals(-20.0, resultSet.getDouble("signal_dbfs"));
            }

            P25ActivityLogSchema.deleteOlderThan(connection, 50_000L);
            assertEquals(1, count(connection, "p25_control_channel_quality"));
        }
    }

    @Test
    void qualityRetentionDrainsMoreThanOneBoundedBatch() throws Exception
    {
        Path database = mTemporaryFolder.resolve("quality-retention-batches.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE buckets(value) AS (
                    VALUES(0) UNION ALL SELECT value + 1 FROM buckets WHERE value < 1000
                )
                INSERT INTO p25_control_channel_quality (
                    guid, frequency_hz, bucket_start_ms, observed_at_ms
                )
                SELECT 'dmr-site', 451000000, value * 10000, value * 10000 FROM buckets
                """);
            statement.executeUpdate("""
                INSERT INTO p25_control_channel_quality (
                    guid, frequency_hz, bucket_start_ms, observed_at_ms
                ) VALUES ('dmr-site', 451000000, 20000000, 20000000)
                """);

            assertEquals(1_002, count(connection, "p25_control_channel_quality"));
            assertEquals(1_001, P25ActivityLogSchema.deleteOlderThan(connection, 11_000_000L));
            assertEquals(1, count(connection, "p25_control_channel_quality"));
            assertEquals(20_000_000L, scalarLong(connection,
                "SELECT observed_at_ms FROM p25_control_channel_quality"));
        }
    }

    @Test
    void representativeVolumeQualityRetentionSelectionUsesCoveringTimeIndex() throws Exception
    {
        Path database = mTemporaryFolder.resolve("quality-retention-query-plan.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE
                    sites(value) AS (
                        VALUES(0) UNION ALL SELECT value + 1 FROM sites WHERE value < 99
                    ),
                    buckets(value) AS (
                        VALUES(0) UNION ALL SELECT value + 1 FROM buckets WHERE value < 1023
                    )
                INSERT INTO p25_control_channel_quality (
                    guid, frequency_hz, bucket_start_ms, observed_at_ms
                )
                SELECT printf('site-%03d', sites.value), 450000000 + sites.value,
                       buckets.value * 10000, buckets.value * 10000
                FROM sites CROSS JOIN buckets
                """);

            assertEquals(102_400, count(connection, "p25_control_channel_quality"));

            StringBuilder plan = new StringBuilder();

            try(ResultSet resultSet = statement.executeQuery("""
                EXPLAIN QUERY PLAN
                SELECT guid, frequency_hz, bucket_start_ms
                FROM p25_control_channel_quality INDEXED BY idx_p25_control_quality_retention
                WHERE observed_at_ms < 5120000
                ORDER BY observed_at_ms, guid, frequency_hz, bucket_start_ms
                LIMIT 1000
                """))
            {
                while(resultSet.next())
                {
                    plan.append(resultSet.getString("detail")).append('\n');
                }
            }

            assertTrue(plan.toString().contains(
                "USING COVERING INDEX idx_p25_control_quality_retention (observed_at_ms<?)"), plan.toString());
            assertFalse(plan.toString().contains("SCAN p25_control_channel_quality"), plan.toString());
        }
    }

    @Test
    void representativeVolumeIdentityRankingUsesTimeLeadingIndex() throws Exception
    {
        Path database = mTemporaryFolder.resolve("identity-ranking-query-plan.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE contexts(value) AS (
                    VALUES(0) UNION ALL SELECT value + 1 FROM contexts WHERE value < 31
                )
                INSERT INTO receiver_context (
                    context_key, kind_code, protocol_code, first_seen_ms, last_seen_ms
                )
                SELECT printf('identity-context-%02d', value), 1, 1, 0, 0 FROM contexts
                """);
            statement.executeUpdate("""
                WITH RECURSIVE buckets(value) AS (
                    VALUES(0) UNION ALL SELECT value + 1 FROM buckets WHERE value < 511
                )
                INSERT INTO call_identity_bucket (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id, call_count
                )
                SELECT context.id, buckets.value * 3600000, 1, 1, 1000 + context.id, 1
                FROM receiver_context context CROSS JOIN buckets
                """);

            assertEquals(16_384, count(connection, "call_identity_bucket"));
            StringBuilder plan = new StringBuilder();

            try(ResultSet resultSet = statement.executeQuery("""
                EXPLAIN QUERY PLAN
                SELECT identity_id, SUM(call_count)
                FROM call_identity_bucket INDEXED BY idx_call_identity_bucket_dashboard_time
                WHERE bucket_start_ms >= 1756800000
                  AND identity_role_code = 1
                  AND identity_kind_code = 1
                GROUP BY identity_id
                ORDER BY SUM(call_count) DESC
                LIMIT 20
                """))
            {
                while(resultSet.next())
                {
                    plan.append(resultSet.getString("detail")).append('\n');
                }
            }

            assertTrue(plan.toString().contains(
                "USING INDEX idx_call_identity_bucket_dashboard_time (bucket_start_ms>?)"), plan.toString());
            assertFalse(plan.toString().contains("SCAN call_identity_bucket"), plan.toString());
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
            P25ActivityLogSchema.insertControlChannelQuality(connection,
                quality(now - TimeUnit.DAYS.toMillis(2), -25.0));
            P25ActivityLogSchema.insertControlChannelQuality(connection, quality(now, -20.0));

            try(var statement = connection.prepareStatement("""
                INSERT INTO trunked_site_snapshot (
                    guid, snapshot_hash, protocol_code, variant_code, identity_domain_code,
                    first_seen_ms, last_seen_ms, observation_count
                ) VALUES (?, ?, ?, 1, 1, ?, ?, 1)
                """))
            {
                insertTrunkedSite(statement, "expired-dmr", TrunkedSiteSchema.PROTOCOL_DMR,
                    now - TimeUnit.DAYS.toMillis(2));
                insertTrunkedSite(statement, "current-dmr", TrunkedSiteSchema.PROTOCOL_DMR, now);
                insertTrunkedSite(statement, "expired-nxdn", TrunkedSiteSchema.PROTOCOL_NXDN,
                    now - TimeUnit.DAYS.toMillis(2));
                insertTrunkedSite(statement, "current-nxdn", TrunkedSiteSchema.PROTOCOL_NXDN, now);
            }
        }

        P25ActivityLogMaintenance.Result result =
            P25ActivityLogMaintenance.run(database, 1, P25ActivityLogMaintenance.Operation.MAINTAIN);

        assertEquals(P25ActivityLogMaintenance.Operation.MAINTAIN, result.operation());
        assertTrue(result.rowsDeleted() > 0);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertCount(connection, "p25_activity_event", 1);
            assertCount(connection, "p25_control_channel_quality", 1);
            assertCount(connection, "call_identity_bucket", 0);
            assertCount(connection, "trunked_site_snapshot", 2);
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_snapshot WHERE protocol_code=3"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_snapshot WHERE protocol_code=4"));
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
    void clearsOnlySelectedSiteStatistics() throws Exception
    {
        Path database = mTemporaryFolder.resolve("clear-site.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String clearedGuid = "123e4567-e89b-12d3-a456-426614174000";
        String retainedGuid = "223e4567-e89b-12d3-a456-426614174000";

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(1_000L, clearedGuid));
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(1_100L, retainedGuid));
            recordConfirmedActivity(connection,
                activity(2_000L, P25ActivityLogRecords.Action.GRANT, clearedGuid), true);
            recordConfirmedActivity(connection,
                activity(3_000L, P25ActivityLogRecords.Action.GRANT, retainedGuid), true);
            P25ActivityLogSchema.recordActivity(connection,
                activity(2_100L, P25ActivityLogRecords.Action.CALL, clearedGuid), false);
            P25ActivityLogSchema.recordActivity(connection,
                activity(3_100L, P25ActivityLogRecords.Action.CALL, retainedGuid), false);
            P25ActivityLogSchema.insertControlChannelQuality(connection, quality(4_000L, -20.0, clearedGuid));
            P25ActivityLogSchema.insertControlChannelQuality(connection, quality(5_000L, -21.0, retainedGuid));
        }

        P25ActivityLogMaintenance.Result result =
            P25ActivityLogMaintenance.clearSiteStats(database, clearedGuid);

        assertEquals(P25ActivityLogMaintenance.Operation.CLEAR_SITE_STATS, result.operation());
        assertTrue(result.rowsDeleted() > 0);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertGuidCount(connection, "receiver_context", clearedGuid, 0);
            assertGuidCount(connection, "receiver_context", retainedGuid, 1);
            assertGuidCount(connection, "p25_site_snapshot", clearedGuid, 0);
            assertGuidCount(connection, "p25_site_channel_summary", clearedGuid, 0);
            assertGuidCount(connection, "p25_site_channel_tag_summary", clearedGuid, 0);
            assertGuidCount(connection, "p25_control_channel_quality", clearedGuid, 0);
            assertGuidCount(connection, "p25_site_channel_summary", retainedGuid, 2);
            assertGuidCount(connection, "p25_site_channel_tag_summary", retainedGuid, 2);
            assertGuidCount(connection, "p25_control_channel_quality", retainedGuid, 1);
            assertCount(connection, "p25_activity_event", 1);
            assertCount(connection, "p25_site_frequency_summary", 1);
            assertCount(connection, "p25_site_talkgroup_bucket", 1);
            assertCount(connection, "p25_site_activity_bucket", 1);
            assertEquals(2L, scalarLong(connection, """
                SELECT COUNT(*)
                FROM call_identity_bucket identity
                JOIN receiver_context context ON context.id=identity.context_id
                WHERE context.guid='223e4567-e89b-12d3-a456-426614174000'
                """));

            //These summaries are shared by all receiver sites for the system and cannot be deleted site-by-site.
            assertCount(connection, "p25_system", 1);
            assertGroupIdentityCount(connection, 1);
            assertEquals(2L, scalarLong(connection, """
                SELECT grant_count FROM trunked_identity_summary
                WHERE identity_kind_code IN (1,3)
                """));
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
                assertEquals(Integer.toString(P25ActivityLogSchema.SCHEMA_VERSION), resultSet.getString(1));
            }

            try(ResultSet resultSet = statement.executeQuery("""
                SELECT CAST(value AS INTEGER) FROM database_metadata
                WHERE key='p25_call_output_metrics_started_at_ms'
                """))
            {
                assertTrue(resultSet.next());
                assertTrue(resultSet.getLong(1) > 0);
            }

            try(ResultSet resultSet = statement.executeQuery("PRAGMA user_version"))
            {
                assertTrue(resultSet.next());
                assertEquals(0, resultSet.getInt(1));
            }

            assertColumnAbsent(connection, "trunked_identity_summary", "last_frequency_hz");
            assertColumnAbsent(connection, "trunked_identity_summary", "last_lcn");
            assertColumnAbsent(connection, "p25_activity_event", "service");
            assertColumnAbsent(connection, "p25_activity_event", "details");
            assertColumnAbsent(connection, "trunked_identity_summary", "hits");
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
    void callIdentitySchemaEnforcesIdentityAndOwnershipContract() throws Exception
    {
        Path database = mTemporaryFolder.resolve("call-identity-schema.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            P25ActivityLogSchema.recordActivity(connection,
                conventionalActivity(1_000L, P25ActivityLogRecords.Action.CALL), false);

            List<String> primaryKey = new ArrayList<>();

            try(ResultSet resultSet = statement.executeQuery("PRAGMA table_info(call_identity_bucket)"))
            {
                while(resultSet.next())
                {
                    if(resultSet.getInt("pk") > 0)
                    {
                        primaryKey.add(resultSet.getInt("pk") + ":" + resultSet.getString("name"));
                    }
                }
            }

            assertEquals(List.of("1:context_id", "2:bucket_start_ms", "3:identity_role_code",
                "4:identity_kind_code", "5:identity_id"), primaryKey);

            try(ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_list(call_identity_bucket)"))
            {
                assertTrue(resultSet.next());
                assertEquals("receiver_context", resultSet.getString("table"));
                assertEquals("context_id", resultSet.getString("from"));
                assertEquals("id", resultSet.getString("to"));
                assertEquals("CASCADE", resultSet.getString("on_delete"));
                assertFalse(resultSet.next());
            }

            List<String> indexColumns = new ArrayList<>();

            try(ResultSet resultSet =
                    statement.executeQuery("PRAGMA index_info(idx_call_identity_bucket_dashboard_time)"))
            {
                while(resultSet.next())
                {
                    indexColumns.add(resultSet.getString("name"));
                }
            }

            assertEquals(List.of("bucket_start_ms", "identity_role_code", "identity_kind_code", "context_id",
                "identity_id"), indexColumns);

            long contextId = scalarLong(connection, "SELECT id FROM receiver_context");
            assertThrows(Exception.class, () -> statement.executeUpdate("""
                INSERT INTO call_identity_bucket (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id
                ) VALUES (%d, 3600000, 9, 1, 1)
                """.formatted(contextId)));
            assertThrows(Exception.class, () -> statement.executeUpdate("""
                INSERT INTO call_identity_bucket (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id
                ) VALUES (%d, 3600000, 2, 0, 0)
                """.formatted(contextId)));
            assertThrows(Exception.class, () -> statement.executeUpdate("""
                INSERT INTO call_identity_bucket (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id, call_count
                ) VALUES (%d, 3600000, 1, 1, 1, -1)
                """.formatted(contextId)));
            assertThrows(Exception.class, () -> statement.executeUpdate("""
                INSERT INTO call_identity_bucket (
                    context_id, bucket_start_ms, identity_role_code, identity_kind_code, identity_id
                ) VALUES (999999, 3600000, 1, 1, 1)
                """));

            statement.executeUpdate("DELETE FROM receiver_context WHERE id=" + contextId);
            assertCount(connection, "call_identity_bucket", 0);
        }
    }

    @Test
    void rejectsAStaleResolvedActivityView() throws Exception
    {
        Path database = mTemporaryFolder.resolve("stale-resolved-view.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP VIEW p25_activity_event_resolved");
            statement.executeUpdate("CREATE VIEW p25_activity_event_resolved AS SELECT 1 AS id");
            assertThrows(Exception.class, () -> P25ActivityLogSchema.validate(connection));
        }
    }

    @Test
    void storesReplacesAndClearsCurrentRadioAffiliation() throws Exception
    {
        Path database = mTemporaryFolder.resolve("affiliation.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(500L));
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
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(500L));
            P25ActivityLogSchema.recordActivity(connection, affiliation(2000L, 1811524, 56538), false);
            P25ActivityLogSchema.recordActivity(connection, affiliation(1000L, 1811524, 56133), false);
            assertAffiliation(connection, 1811524, 56538, 2000L);
        }
    }

    @Test
    void keepsReservedP25IdentitiesInActivityButOutOfDirectoryProjections() throws Exception
    {
        Path database = mTemporaryFolder.resolve("reserved-identities.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        int validRadio = 1_811_524;
        int validTalkgroup = 56_138;
        int[] invalidTalkgroups = {0, 0xFFFF, 0x10000};
        int[] invalidRadios = {0, 0xFFFFFC, 0xFFFFFD, 0xFFFFFE, 0xFFFFFF, 0x1000000};
        long timestamp = 1_000L;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(500L));

            for(int talkgroup: invalidTalkgroups)
            {
                P25ActivityLogSchema.recordActivity(connection,
                    identityActivity(timestamp++, validRadio, talkgroup, null), true);
            }

            for(int radio: invalidRadios)
            {
                P25ActivityLogSchema.recordActivity(connection,
                    identityActivity(timestamp++, radio, validTalkgroup, null), true);
            }

            P25ActivityLogSchema.recordActivity(connection,
                affiliation(timestamp++, 0xFFFFFC, validTalkgroup), true);
            P25ActivityLogSchema.recordActivity(connection,
                affiliation(timestamp, validRadio, 0xFFFF), true);
            P25ActivityLogSchema.recordActivity(connection,
                countedIdentityActivity(++timestamp, 0xFFFFFC, 0), true);

            assertEquals(invalidTalkgroups.length + invalidRadios.length + 3,
                count(connection, "p25_activity_event"));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id > 0 AND identity_id < 65535
                """));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND (identity_id <= 0 OR identity_id >= 65535)
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_kind_code=2 AND identity_id > 0 AND identity_id < 16777212
                """));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_kind_code=2 AND (identity_id <= 0 OR identity_id >= 16777212)
                """));
            assertEquals(0, count(connection, "trunked_radio_talkgroup_summary"));
            assertEquals(0, count(connection, "p25_radio_affiliation"));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM p25_site_talkgroup_bucket
                WHERE talkgroup_id <= 0 OR talkgroup_id >= 65535
                """));
            assertEquals(1, count(connection, "call_identity_bucket"));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM call_identity_bucket
                WHERE identity_role_code = 1 AND identity_kind_code = 0 AND identity_id = 0
                """));
        }
    }

    @Test
    void updatesAggregateSummaries() throws Exception
    {
        Path database = mTemporaryFolder.resolve("summaries.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(500L));
            P25ActivityLogSchema.recordActivity(connection, activity(1000L, P25ActivityLogRecords.Action.GRANT), true);
            P25ActivityLogSchema.recordActivity(connection, activity(2000L, P25ActivityLogRecords.Action.CONTINUE), true);
            P25ActivityLogSchema.recordActivity(connection, activity(3000L, P25ActivityLogRecords.Action.CALL), true);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                    """
                    SELECT call_count, grant_count, continue_count, encrypted_count
                    FROM trunked_identity_summary WHERE identity_kind_code=1
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("call_count"));
                assertEquals(1, resultSet.getInt("grant_count"));
                assertEquals(1, resultSet.getInt("continue_count"));
                assertEquals(1, resultSet.getInt("encrypted_count"));
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                    """
                    SELECT call_count, grant_count, encrypted_count
                    FROM trunked_identity_summary WHERE identity_kind_code=2
                    """))
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

            assertCount(connection, "trunked_radio_talkgroup_summary", 1);
            assertActionCount(connection, "trunked_radio_talkgroup_summary", "call_count", 1);
            assertActionCount(connection, "p25_site_talkgroup_bucket", "call_count", 1);
            assertActionCount(connection, "p25_site_activity_bucket", "call_count", 1);
            assertCount(connection, "p25_activity_event", 2);
        }
    }

    @Test
    void lateTalkerAliasUpdateDoesNotInflateActivityCounters() throws Exception
    {
        Path database = mTemporaryFolder.resolve("talker-alias.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(500L));
            assertIdentityCount(connection, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, 0);
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
                    FROM trunked_identity_summary WHERE identity_kind_code=2
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
    void onlyExplicitTalkerAliasUpdatesDurableRadioAlias() throws Exception
    {
        Path database = mTemporaryFolder.resolve("explicit-talker-alias.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(500L));
            P25ActivityLogSchema.recordActivity(connection,
                activityWithTalkerAlias(1_000L, "WRONG FIRST"), true);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                    "SELECT last_talker_alias FROM trunked_identity_summary WHERE identity_kind_code=2"))
            {
                assertTrue(resultSet.next());
                assertNull(resultSet.getString("last_talker_alias"));
            }

            P25ActivityLogSchema.updateTalkerAlias(connection, new P25ActivityLogRecords.TalkerAliasUpdate(
                2_000L, "GUID:123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174000", 0xBEE00, 0x348, 1811524, "CAR 201"));
            P25ActivityLogSchema.recordActivity(connection,
                activityWithTalkerAlias(3_000L, "WRONG LATE"), true);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT last_talker_alias, last_talker_alias_seen_ms
                    FROM trunked_identity_summary WHERE identity_kind_code=2
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals("CAR 201", resultSet.getString("last_talker_alias"));
                assertEquals(2_000L, resultSet.getLong("last_talker_alias_seen_ms"));
            }
        }
    }

    @Test
    void activityAndTalkerAliasCannotEstablishSystemIdentity() throws Exception
    {
        Path database = mTemporaryFolder.resolve("untrusted-activity-identity.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(1_000L, P25ActivityLogRecords.Action.CALL), true);
            P25ActivityLogSchema.updateTalkerAlias(connection, new P25ActivityLogRecords.TalkerAliasUpdate(
                2_000L, "GUID:123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174000", 0xBEE00, 0x348, 1811524, "CAR 201"));

            assertCount(connection, "p25_system", 0);
            assertGroupIdentityCount(connection, 0);
            assertIdentityCount(connection, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, 0);
            assertCount(connection, "p25_site_activity_bucket", 1);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                    "SELECT system_key, nac, rfss, site FROM receiver_context"))
            {
                assertTrue(resultSet.next());
                assertEquals(null, resultSet.getObject("system_key"));
                assertEquals(null, resultSet.getObject("nac"));
                assertEquals(null, resultSet.getObject("rfss"));
                assertEquals(null, resultSet.getObject("site"));
            }
        }
    }

    @Test
    void activityWithMismatchedSystemIdentityIsRejectedAfterSiteSnapshot() throws Exception
    {
        Path database = mTemporaryFolder.resolve("stable-site-identity.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(1_000L));
            P25ActivityLogSchema.recordActivity(connection,
                activityWithNetworkIdentity(2_000L, 0xAAAAA, 0x111, 9, 9), false);
            P25ActivityLogSchema.updateTalkerAlias(connection, new P25ActivityLogRecords.TalkerAliasUpdate(
                3_000L, "GUID:123e4567-e89b-12d3-a456-426614174000",
                "123e4567-e89b-12d3-a456-426614174000", 0xAAAAA, 0x111, 1811524, "CAR 201"));

            assertCount(connection, "p25_system", 1);
            assertGroupIdentityCount(connection, 0);
            assertIdentityCount(connection, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, 1);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT system.wacn, system.system_id, context.nac, context.rfss, context.site
                    FROM receiver_context context
                    JOIN p25_system system ON system.system_key = context.system_key
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(0xBEE00, resultSet.getInt("wacn"));
                assertEquals(0x348, resultSet.getInt("system_id"));
                assertEquals(0x348, resultSet.getInt("nac"));
                assertEquals(2, resultSet.getInt("rfss"));
                assertEquals(1, resultSet.getInt("site"));
            }
        }
    }

    @Test
    void conventionalCallCountersAndOptionalAnalogHistoryCountEachCall() throws Exception
    {
        Path database = mTemporaryFolder.resolve("conventional-hits.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                conventionalActivity(1000L, P25ActivityLogRecords.Action.GRANT), false);
            P25ActivityLogSchema.recordActivity(connection,
                conventionalActivity(2000L, P25ActivityLogRecords.Action.CALL), false);
            P25ActivityLogSchema.recordActivity(connection,
                conventionalActivity(3000L, P25ActivityLogRecords.Action.CALL), true);

            assertActionCount(connection, "conventional_activity_summary", "call_count", 2);
            assertActionCount(connection, "conventional_activity_bucket", "call_count", 2);
            assertActionCount(connection, "conventional_activity_summary", "grant_count", 1);
            assertCount(connection, "p25_activity_event", 1);
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
            assertCount(connection, "p25_foreign_system_band", 3);
            assertCount(connection, "p25_foreign_system_band_summary", 3);
            assertCount(connection, "p25_site_neighbor", 1);
            assertCount(connection, "p25_site_patch_group", 1);
            assertCount(connection, "p25_site_patch_group_talkgroup", 1);
            assertCount(connection, "p25_site_patch_group_radio", 1);
            assertCount(connection, "p25_system", 1);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT observation_count, lra, mfid, broadcast_clock_ms, micro_slots, data_service,
                        data_access, wuid_lease_minutes, registration_service, tdma, voice_service
                    FROM p25_site_snapshot
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(2, resultSet.getInt("observation_count"));
                assertEquals(0, resultSet.getInt("lra"));
                assertEquals(0x90, resultSet.getInt("mfid"));
                assertEquals(1_784_000_000_000L, resultSet.getLong("broadcast_clock_ms"));
                assertEquals(110, resultSet.getInt("micro_slots"));
                assertEquals(1, resultSet.getInt("data_service"));
                assertEquals("Autonomous and by Request", resultSet.getString("data_access"));
                assertEquals(240, resultSet.getInt("wuid_lease_minutes"));
                assertEquals(1, resultSet.getInt("registration_service"));
                assertEquals(1, resultSet.getInt("tdma"));
                assertEquals(1, resultSet.getInt("voice_service"));
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT callsign FROM p25_site_channel"))
            {
                assertTrue(resultSet.next());
                assertEquals("WPFF205", resultSet.getString("callsign"));
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                    "SELECT observation_count FROM p25_site_neighbor_summary"))
            {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("observation_count"));
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT foreign_wacn, foreign_system_id, band, channel_type, base_hz, spacing_hz,
                        transmit_offset_hz
                    FROM p25_foreign_system_band
                    WHERE foreign_system_id = 0x9EF AND band = 5
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(0xBEE00, resultSet.getInt("foreign_wacn"));
                assertEquals(3, resultSet.getInt("channel_type"));
                assertEquals(935_012_500L, resultSet.getLong("base_hz"));
                assertEquals(12_500L, resultSet.getLong("spacing_hz"));
                assertEquals(-39_000_000L, resultSet.getLong("transmit_offset_hz"));
            }
        }
    }

    @Test
    void createsNewSystemAndSiteWithCurrentAndAlternateControlChannels() throws Exception
    {
        Path database = mTemporaryFolder.resolve("new-system-site-controls.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String guid = "323e4567-e89b-12d3-a456-426614174000";
        List<P25NetworkConfigurationSnapshot.Channel> channels = List.of(
            new P25NetworkConfigurationSnapshot.Channel("primary_control", "2-1328", 770_306_250L,
                null, false, 1),
            new P25NetworkConfigurationSnapshot.Channel("secondary_control", "2-1668", 772_431_250L,
                null, false, 1),
            new P25NetworkConfigurationSnapshot.Channel("secondary_control", "2-1724", 772_781_250L,
                null, false, 1));
        P25ActivityLogRecords.SiteSnapshot snapshot = new P25ActivityLogRecords.SiteSnapshot(1_000L, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "new-system-site", "APCO25", "New Site",
            "New System", "P25-1", 0x00001, 0x047, 0x123, 50, 50, null, false, null,
            770_306_250L, 770_306_250L, channels, List.of(), List.of(), List.of(), List.of());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, snapshot);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT system.wacn, system.system_id, site.rfss, site.site, site.current_control_hz
                    FROM p25_site_snapshot site
                    JOIN p25_system system ON system.system_key = site.system_key
                    WHERE site.guid = '323e4567-e89b-12d3-a456-426614174000'
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(0x00001, resultSet.getInt("wacn"));
                assertEquals(0x047, resultSet.getInt("system_id"));
                assertEquals(50, resultSet.getInt("rfss"));
                assertEquals(50, resultSet.getInt("site"));
                assertEquals(770_306_250L, resultSet.getLong("current_control_hz"));
                assertFalse(resultSet.next());
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT channel.downlink_hz, tag.tag
                    FROM p25_site_channel channel
                    JOIN p25_site_channel_tag tag
                        ON tag.guid = channel.guid AND tag.channel_key = channel.channel_key
                    WHERE channel.guid = '323e4567-e89b-12d3-a456-426614174000'
                    ORDER BY channel.downlink_hz
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(770_306_250L, resultSet.getLong("downlink_hz"));
                assertEquals("CURRENT_CONTROL", resultSet.getString("tag"));
                assertTrue(resultSet.next());
                assertEquals(772_431_250L, resultSet.getLong("downlink_hz"));
                assertEquals("ALTERNATE_CONTROL", resultSet.getString("tag"));
                assertTrue(resultSet.next());
                assertEquals(772_781_250L, resultSet.getLong("downlink_hz"));
                assertEquals("ALTERNATE_CONTROL", resultSet.getString("tag"));
                assertFalse(resultSet.next());
            }
        }
    }

    @Test
    void mergesDuplicateLogicalSiteChannelsWithoutStoppingWriter() throws Exception
    {
        Path database = mTemporaryFolder.resolve("duplicate-site-channels.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, true, 10);
        writer.start();
        writer.enqueue(siteSnapshotWithDuplicateChannels(1000L));

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(writer.getWrittenRecords() < 1 && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        P25ActivityLogWriter.WriterStatus status = writer.getStatus();
        assertEquals(P25ActivityLogStatus.State.RUNNING, status.state());
        assertEquals(1, status.recordsWritten());
        assertEquals(null, status.lastError());
        writer.close();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT downlink_hz, uplink_hz, timeslots
                FROM p25_site_channel
                """))
        {
            assertTrue(resultSet.next());
            assertEquals(856137500L, resultSet.getLong("downlink_hz"));
            assertEquals(811137500L, resultSet.getLong("uplink_hz"));
            assertEquals(1, resultSet.getInt("timeslots"));
            assertFalse(resultSet.next());
        }

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT tag FROM p25_site_channel_tag ORDER BY tag"))
        {
            assertTrue(resultSet.next());
            assertEquals("ALTERNATE_CONTROL", resultSet.getString("tag"));
            assertTrue(resultSet.next());
            assertEquals("CURRENT_CONTROL", resultSet.getString("tag"));
            assertTrue(resultSet.next());
            assertEquals("DATA_ANNOUNCED", resultSet.getString("tag"));
            assertFalse(resultSet.next());
        }

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("""
                SELECT observation_count FROM p25_site_channel_summary
                """))
        {
            assertTrue(resultSet.next());
            assertEquals(1, resultSet.getInt("observation_count"));
            assertFalse(resultSet.next());
        }

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            recordConfirmedActivity(connection,
                serviceActivity(2000L, "CALL_GROUP", false, 856_137_500L, "0-821"), false);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT tag FROM p25_site_channel_tag_summary ORDER BY tag
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals("ALTERNATE_CONTROL", resultSet.getString("tag"));
                assertTrue(resultSet.next());
                assertEquals("CONTROL", resultSet.getString("tag"));
                assertTrue(resultSet.next());
                assertEquals("DATA_ANNOUNCED", resultSet.getString("tag"));
                assertTrue(resultSet.next());
                assertEquals("VOICE", resultSet.getString("tag"));
                assertFalse(resultSet.next());
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
                856137500L, null, List.of(), List.of(), List.of(), List.of());
            P25ActivityLogSchema.insertSite(connection, empty);

            assertCount(connection, "p25_site_channel", 0);
            assertCount(connection, "p25_site_neighbor", 0);
            assertCount(connection, "p25_foreign_system_band", 0);
            assertCount(connection, "p25_site_patch_group", 0);
            assertCount(connection, "p25_site_channel_summary", 1);
            assertCount(connection, "p25_site_neighbor_summary", 1);
            assertCount(connection, "p25_foreign_system_band_summary", 3);
            assertCount(connection, "p25_site_patch_group_summary", 1);
        }
    }

    @Test
    void timingOnlyHeartbeatRefreshesCurrentFactsWithoutRecountingSummaries() throws Exception
    {
        Path database = mTemporaryFolder.resolve("site-timing-heartbeat.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshotWithTiming(1_000L, 1_784_000_000_000L));
            P25ActivityLogSchema.insertSite(connection, siteSnapshotWithTiming(2_000L, 1_784_000_001_000L));

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT broadcast_clock_ms, observation_count
                    FROM p25_site_snapshot
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(1_784_000_001_000L, resultSet.getLong("broadcast_clock_ms"));
                assertEquals(2, resultSet.getInt("observation_count"));
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT observation_count, last_seen_ms
                    FROM p25_site_channel_summary
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("observation_count"));
                assertEquals(1_000L, resultSet.getLong("last_seen_ms"));
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT confirmed_at_ms
                    FROM p25_site_channel
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(2_000L, resultSet.getLong("confirmed_at_ms"));
            }
        }
    }

    @Test
    void invalidSynchronizationDateClearsPersistedClockAndKeepsMicroslots() throws Exception
    {
        Path database = mTemporaryFolder.resolve("site-invalid-sync-date.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshotWithTiming(1_000L, 1_784_000_000_000L));
            P25ActivityLogSchema.insertSite(connection, siteSnapshotWithTiming(2_000L, null, 222));

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT broadcast_clock_ms, micro_slots
                    FROM p25_site_snapshot
                    """))
            {
                assertTrue(resultSet.next());
                assertNull(resultSet.getObject("broadcast_clock_ms"));
                assertEquals(222, resultSet.getInt("micro_slots"));
            }
        }
    }

    @Test
    void olderSiteSnapshotIsRejectedBeforeItCanRegressCurrentOrRetainedFacts() throws Exception
    {
        Path database = mTemporaryFolder.resolve("site-out-of-order.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(2_000L));
            P25ActivityLogSchema.insertSite(connection,
                withSnapshotHash(siteSnapshot(1_000L), "older-structural-state"));

            assertEquals("hash", scalarString(connection,
                "SELECT snapshot_hash FROM p25_site_snapshot"));
            assertEquals(2_000L, scalarLong(connection,
                "SELECT last_seen_ms FROM p25_site_snapshot"));
            assertEquals(1L, scalarLong(connection,
                "SELECT observation_count FROM p25_site_snapshot"));
            assertEquals(2_000L, scalarLong(connection,
                "SELECT last_seen_ms FROM receiver_context WHERE guid IS NOT NULL"));

            for(String table: List.of("p25_site_channel", "p25_site_channel_tag",
                "p25_site_frequency_band", "p25_foreign_system_band", "p25_site_neighbor",
                "p25_site_patch_group", "p25_site_patch_group_talkgroup", "p25_site_patch_group_radio"))
            {
                assertEquals(2_000L, scalarLong(connection,
                    "SELECT MIN(confirmed_at_ms) FROM " + table));
            }

            for(String table: List.of("p25_site_channel_summary", "p25_site_channel_tag_summary",
                "p25_site_frequency_band_summary", "p25_foreign_system_band_summary",
                "p25_site_neighbor_summary", "p25_site_patch_group_summary",
                "p25_site_patch_group_talkgroup_summary", "p25_site_patch_group_radio_summary"))
            {
                assertEquals(2_000L, scalarLong(connection,
                    "SELECT MIN(last_seen_ms) FROM " + table));
                assertEquals(1L, scalarLong(connection,
                    "SELECT MAX(observation_count) FROM " + table));
            }
        }
    }

    @Test
    void mergesSystemEntitiesAcrossSiteGuidsButKeepsSiteBucketsSeparate() throws Exception
    {
        Path database = mTemporaryFolder.resolve("multi-site.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection,
                siteSnapshot(500L, "123e4567-e89b-12d3-a456-426614174000"));
            P25ActivityLogSchema.insertSite(connection,
                siteSnapshot(600L, "223e4567-e89b-12d3-a456-426614174000"));
            P25ActivityLogSchema.recordActivity(connection,
                activity(1000L, P25ActivityLogRecords.Action.GRANT,
                    "123e4567-e89b-12d3-a456-426614174000"), false);
            P25ActivityLogSchema.recordActivity(connection,
                activity(2000L, P25ActivityLogRecords.Action.GRANT,
                    "223e4567-e89b-12d3-a456-426614174000"), false);

            assertCount(connection, "p25_system", 1);
            assertCount(connection, "receiver_context", 2);
            assertGroupIdentityCount(connection, 1);
            assertIdentityCount(connection, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, 1);
            assertCount(connection, "trunked_radio_talkgroup_summary", 1);
            assertCount(connection, "p25_site_talkgroup_bucket", 2);
            assertCount(connection, "p25_site_activity_bucket", 2);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT grant_count FROM trunked_identity_summary
                    WHERE identity_kind_code=1
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
            assertGroupIdentityCount(connection, 1);
            assertIdentityCount(connection, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, 1);
            assertCount(connection, "trunked_radio_talkgroup_summary", 1);
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
        writer.enqueue(new P25ActivityLogRecords.CompletedCallOutput(1000L,
            "123e4567-e89b-12d3-a456-426614174000", 56138, P25ActivityLogRecords.CallOutput.RECORDED));

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(writer.getWrittenRecords() < 3 && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        writer.close();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertCount(connection, "p25_activity_event", 1);
            assertCount(connection, "p25_site_snapshot", 1);
            assertActionCount(connection, "p25_site_talkgroup_bucket", "recorded_count", 1);
            assertActionCount(connection, "p25_site_activity_bucket", "recorded_count", 1);
            assertTrue(count(connection, "logger_status") > 0);
            assertTrue(Long.parseLong(status(connection, "last_successful_write_ms")) > 0);
        }
    }

    @Test
    void fansOutEncryptedPatchCallAndOutputsWithoutInflatingPhysicalTotals() throws Exception
    {
        Path database = mTemporaryFolder.resolve("patch-call.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, true, 10);
        String guid = "123e4567-e89b-12d3-a456-426614174000";
        writer.start();
        writer.enqueue(siteSnapshot(500L));
        writer.enqueue(patchActivity(1_000L));
        writer.enqueue(new P25ActivityLogRecords.CompletedCallOutput(1_000L, "GUID:" + guid, guid,
            854_187_500L, 1, 56182, "PATCH_GROUP", List.of(56181, 56180, 56180, 56182), 1811524,
            P25ActivityLogRecords.CallOutput.RECORDED));
        writer.enqueue(new P25ActivityLogRecords.CompletedCallOutput(1_000L, "GUID:" + guid, guid,
            854_187_500L, 1, 56182, "PATCH_GROUP", List.of(56180, 56181), 1811524,
            P25ActivityLogRecords.CallOutput.STREAMED));

        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while(writer.getWrittenRecords() < 4 && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        writer.close();
        assertEquals(4, writer.getWrittenRecords());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            try(Statement statement = connection.createStatement())
            {
                statement.execute("PRAGMA foreign_keys=ON");
            }

            assertCount(connection, "p25_activity_event", 1);
            assertEquals(56182L, scalarLong(connection,
                "SELECT target_id FROM p25_activity_event"));
            assertEquals(3L, scalarLong(connection,
                "SELECT target_kind_code FROM p25_activity_event"));
            assertEquals(1L, scalarLong(connection,
                "SELECT encrypted FROM p25_activity_event"));
            assertCount(connection, "activity_event_talkgroup_member", 2);
            assertEquals(112361L, scalarLong(connection,
                "SELECT SUM(talkgroup_id) FROM activity_event_talkgroup_member"));

            assertGroupIdentityCount(connection, 3);
            assertCount(connection, "trunked_radio_talkgroup_summary", 3);
            assertCount(connection, "p25_site_talkgroup_bucket", 3);

            assertEquals(3L, scalarLong(connection, """
                SELECT SUM(call_count) FROM trunked_identity_summary
                WHERE identity_kind_code IN (1,3)
                """));
            assertEquals(3L, scalarLong(connection, """
                SELECT SUM(encrypted_count) FROM trunked_identity_summary
                WHERE identity_kind_code IN (1,3)
                """));
            assertEquals(1L, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_id=56182 AND identity_kind_code=3
                """));
            assertEquals(2L, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_identity_summary
                WHERE identity_id IN (56180,56181) AND identity_kind_code=1
                """));
            assertEquals(3L, scalarLong(connection,
                "SELECT SUM(call_count) FROM trunked_radio_talkgroup_summary"));
            assertEquals(3L, scalarLong(connection,
                "SELECT SUM(encrypted_count) FROM trunked_radio_talkgroup_summary"));
            assertEquals(1L, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_radio_talkgroup_summary
                WHERE talkgroup_id=56182 AND target_kind_code=3
                """));
            assertEquals(2L, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_radio_talkgroup_summary
                WHERE talkgroup_id IN (56180,56181) AND target_kind_code=1
                """));

            assertEquals(3L, scalarLong(connection,
                "SELECT SUM(call_count) FROM p25_site_talkgroup_bucket"));
            assertEquals(3L, scalarLong(connection,
                "SELECT SUM(encrypted_count) FROM p25_site_talkgroup_bucket"));
            assertEquals(3L, scalarLong(connection,
                """
                SELECT SUM(recorded_count) FROM trunked_identity_summary
                WHERE identity_kind_code IN (1,3)
                """));
            assertEquals(3L, scalarLong(connection,
                """
                SELECT SUM(streamed_count) FROM trunked_identity_summary
                WHERE identity_kind_code IN (1,3)
                """));
            assertEquals(3L, scalarLong(connection,
                "SELECT SUM(recorded_count) FROM p25_site_talkgroup_bucket"));
            assertEquals(3L, scalarLong(connection,
                "SELECT SUM(streamed_count) FROM p25_site_talkgroup_bucket"));

            assertIdentityCount(connection, TrunkedIdentityPolicy.IDENTITY_KIND_RADIO, 1);
            assertEquals(1L, scalarLong(connection,
                "SELECT call_count FROM trunked_identity_summary WHERE identity_kind_code=2"));
            assertEquals(1L, scalarLong(connection,
                "SELECT encrypted_count FROM trunked_identity_summary WHERE identity_kind_code=2"));
            assertCount(connection, "p25_site_frequency_summary", 1);
            assertEquals(1L, scalarLong(connection,
                "SELECT call_count FROM p25_site_frequency_summary"));
            assertCount(connection, "p25_site_activity_bucket", 1);
            assertEquals(1L, scalarLong(connection,
                "SELECT call_count FROM p25_site_activity_bucket"));
            assertEquals(1L, scalarLong(connection,
                "SELECT encrypted_count FROM p25_site_activity_bucket"));
            assertEquals(1L, scalarLong(connection,
                "SELECT recorded_count FROM p25_site_activity_bucket"));
            assertEquals(1L, scalarLong(connection,
                "SELECT streamed_count FROM p25_site_activity_bucket"));

            assertCount(connection, "call_identity_bucket", 4);
            assertIdentityBucket(connection, 0L, P25ActivityLogSchema.IDENTITY_ROLE_DESTINATION,
                P25ActivityLogSchema.IDENTITY_KIND_PATCH_GROUP, 56182, 1, 1, 1, 1);
            assertIdentityBucket(connection, 0L, P25ActivityLogSchema.IDENTITY_ROLE_DESTINATION,
                P25ActivityLogSchema.IDENTITY_KIND_TALKGROUP, 56180, 1, 1, 1, 1);
            assertIdentityBucket(connection, 0L, P25ActivityLogSchema.IDENTITY_ROLE_DESTINATION,
                P25ActivityLogSchema.IDENTITY_KIND_TALKGROUP, 56181, 1, 1, 1, 1);
            assertIdentityBucket(connection, 0L, P25ActivityLogSchema.IDENTITY_ROLE_SOURCE,
                P25ActivityLogSchema.IDENTITY_KIND_RADIO, 1811524, 1, 1, 1, 1);

            P25ActivityLogSchema.deleteOlderThan(connection, 2_000L);
            assertCount(connection, "p25_activity_event", 0);
            assertCount(connection, "activity_event_talkgroup_member", 0);
        }
    }

    @Test
    void attributesLateP25IdentityAndEncryptionAcrossLegacySummariesWithoutAnotherPhysicalCall() throws Exception
    {
        Path database = mTemporaryFolder.resolve("p25-late-attribution.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String guid = "123e4567-e89b-12d3-a456-426614174000";
        P25ActivityLogRecords.ActivityEvent unidentified = new P25ActivityLogRecords.ActivityEvent(
            1_000L, "GUID:" + guid, guid, P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25",
            P25ActivityLogRecords.Action.CALL, "CALL_GROUP", null, null, null, List.of(),
            854_187_500L, "00-0509", 1, false, null, null, 0xBEE00, 0x348, 0x348, 2, 1,
            "Example Site", "P25_PHASE1", null, true, null, null);
        P25ActivityLogRecords.TrunkedCallAttribution attribution =
            new P25ActivityLogRecords.TrunkedCallAttribution(1_000L, "GUID:" + guid, guid,
                854_187_500L, 1, 56138, "TALKGROUP", List.of(), 1811524,
                true, true, true, false);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(500L));
            P25ActivityLogSchema.recordActivity(connection, unidentified, true);
            assertTrue(P25ActivityLogSchema.applyTrunkedCallAttribution(connection, attribution));

            assertCount(connection, "p25_activity_event", 1);
            assertEquals(1L, P25ActivityLogSchema.findDetailedTrunkedCallId(connection, attribution));
            assertEquals(1811524L, scalarLong(connection,
                "SELECT source_radio_id FROM p25_activity_event"));
            assertEquals(56138L, scalarLong(connection,
                "SELECT target_id FROM p25_activity_event"));
            assertEquals(1L, scalarLong(connection,
                "SELECT target_kind_code FROM p25_activity_event"));
            assertEquals(1L, scalarLong(connection,
                "SELECT encrypted FROM p25_activity_event"));
            assertEquals(1L, scalarLong(connection,
                "SELECT call_count FROM p25_site_activity_bucket"));
            assertEquals(1L, scalarLong(connection,
                "SELECT encrypted_count FROM p25_site_activity_bucket"));
            assertEquals(1L, scalarLong(connection,
                "SELECT call_count FROM p25_site_frequency_summary"));
            assertEquals(1L, scalarLong(connection,
                "SELECT encrypted_count FROM p25_site_frequency_summary"));
            assertEquals(1L, scalarLong(connection,
                "SELECT call_count FROM p25_site_talkgroup_bucket WHERE talkgroup_id=56138"));
            assertEquals(1L, scalarLong(connection,
                "SELECT encrypted_count FROM p25_site_talkgroup_bucket WHERE talkgroup_id=56138"));
            assertEquals(1L, scalarLong(connection,
                """
                SELECT call_count FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=56138
                """));
            assertEquals(1L, scalarLong(connection,
                """
                SELECT encrypted_count FROM trunked_identity_summary
                WHERE identity_kind_code=1 AND identity_id=56138
                """));
            assertEquals(1L, scalarLong(connection,
                """
                SELECT call_count FROM trunked_identity_summary
                WHERE identity_kind_code=2 AND identity_id=1811524
                """));
            assertEquals(1L, scalarLong(connection,
                """
                SELECT encrypted_count FROM trunked_identity_summary
                WHERE identity_kind_code=2 AND identity_id=1811524
                """));
            assertEquals(56138L, scalarLong(connection,
                """
                SELECT last_counterpart_id FROM trunked_identity_summary
                WHERE identity_kind_code=2 AND identity_id=1811524
                """));
            assertEquals(1L, scalarLong(connection,
                "SELECT call_count FROM trunked_radio_talkgroup_summary"));
            assertEquals(1L, scalarLong(connection,
                "SELECT encrypted_count FROM trunked_radio_talkgroup_summary"));
            assertCount(connection, "call_identity_bucket", 2);
            assertIdentityBucket(connection, 0L, P25ActivityLogSchema.IDENTITY_ROLE_DESTINATION,
                P25ActivityLogSchema.IDENTITY_KIND_TALKGROUP, 56138, 1, 1, 0, 0);
            assertIdentityBucket(connection, 0L, P25ActivityLogSchema.IDENTITY_ROLE_SOURCE,
                P25ActivityLogSchema.IDENTITY_KIND_RADIO, 1811524, 1, 1, 0, 0);
        }
    }

    @Test
    void fillsLateEncryptionDetailsWithoutIncreasingCallOrEncryptionCounts() throws Exception
    {
        Path database = mTemporaryFolder.resolve("p25-late-encryption-details.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String guid = "123e4567-e89b-12d3-a456-426614174000";
        P25ActivityLogRecords.ActivityEvent encryptedCall = new P25ActivityLogRecords.ActivityEvent(
            1_000L, "GUID:" + guid, guid, P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25",
            P25ActivityLogRecords.Action.CALL, "CALL_GROUP_ENCRYPTED", "1811524", "56138", "TALKGROUP",
            List.of(), 854_187_500L, "00-0509", 1, true, null, null, 0xBEE00, 0x348, 0x348, 2, 1,
            "Example Site", "P25_PHASE1", null, true, null, null);
        P25ActivityLogRecords.TrunkedCallAttribution details =
            new P25ActivityLogRecords.TrunkedCallAttribution(1_000L, "GUID:" + guid, guid,
                854_187_500L, 1, 56138, "TALKGROUP", List.of(), 1811524, 0x84, 101,
                false, false, false, true, P25ActivityLogRecords.IdentityDomain.STANDARD);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(500L));
            P25ActivityLogSchema.recordActivity(connection, encryptedCall, true);
            assertTrue(P25ActivityLogSchema.applyTrunkedCallAttribution(connection, details));

            assertEquals(0x84L, scalarLong(connection,
                "SELECT encryption_algorithm_id FROM p25_activity_event"));
            assertEquals(101L, scalarLong(connection,
                "SELECT encryption_key_id FROM p25_activity_event"));
            assertEquals(0x84L, scalarLong(connection,
                "SELECT last_encryption_algorithm_id FROM p25_site_frequency_summary"));
            assertEquals(101L, scalarLong(connection,
                "SELECT last_encryption_key_id FROM p25_site_frequency_summary"));
            assertEquals(2L, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_identity_summary WHERE last_encryption_algorithm_id = 132"));
            assertEquals(2L, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_identity_summary WHERE last_encryption_key_id = 101"));
            assertEquals(0x84L, scalarLong(connection,
                "SELECT last_encryption_algorithm_id FROM trunked_radio_talkgroup_summary"));
            assertEquals(101L, scalarLong(connection,
                "SELECT last_encryption_key_id FROM trunked_radio_talkgroup_summary"));

            assertEquals(1L, scalarLong(connection,
                "SELECT call_count FROM p25_site_activity_bucket"));
            assertEquals(1L, scalarLong(connection,
                "SELECT encrypted_count FROM p25_site_activity_bucket"));
            assertEquals(1L, scalarLong(connection,
                "SELECT call_count FROM p25_site_frequency_summary"));
            assertEquals(1L, scalarLong(connection,
                "SELECT encrypted_count FROM p25_site_frequency_summary"));
            assertEquals(2L, scalarLong(connection,
                "SELECT SUM(call_count) FROM trunked_identity_summary"));
            assertEquals(2L, scalarLong(connection,
                "SELECT SUM(encrypted_count) FROM trunked_identity_summary"));
            assertEquals(1L, scalarLong(connection,
                "SELECT call_count FROM trunked_radio_talkgroup_summary"));
            assertEquals(1L, scalarLong(connection,
                "SELECT encrypted_count FROM trunked_radio_talkgroup_summary"));
        }
    }

    @Test
    void lateP25PatchAttributionLinksRetainedActivityToEachValidMemberTalkgroup() throws Exception
    {
        Path database = mTemporaryFolder.resolve("p25-late-patch-attribution.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String guid = "123e4567-e89b-12d3-a456-426614174000";
        P25ActivityLogRecords.ActivityEvent unidentified = new P25ActivityLogRecords.ActivityEvent(
            1_000L, "GUID:" + guid, guid, P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25",
            P25ActivityLogRecords.Action.CALL, "CALL_GROUP", null, null, null, List.of(),
            854_187_500L, "00-0509", 1, false, null, null, 0xBEE00, 0x348, 0x348, 2, 1,
            "Example Site", "P25_PHASE1", null, true, null, null);
        P25ActivityLogRecords.TrunkedCallAttribution attribution =
            new P25ActivityLogRecords.TrunkedCallAttribution(1_000L, "GUID:" + guid, guid,
                854_187_500L, 1, 56182, "PATCH_GROUP",
                List.of(56180, 56181, 0xFFFF, 56182, 56180), null,
                true, false, false, false);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(500L));
            P25ActivityLogSchema.recordActivity(connection, unidentified, true);
            assertTrue(P25ActivityLogSchema.applyTrunkedCallAttribution(connection, attribution));

            assertCount(connection, "p25_activity_event", 1);
            assertEquals(56182L, scalarLong(connection,
                "SELECT target_id FROM p25_activity_event"));
            assertEquals(P25ActivityLogSchema.IDENTITY_KIND_PATCH_GROUP, scalarLong(connection,
                "SELECT target_kind_code FROM p25_activity_event"));
            assertCount(connection, "activity_event_talkgroup_member", 2);
            assertEquals(112361L, scalarLong(connection,
                "SELECT SUM(talkgroup_id) FROM activity_event_talkgroup_member"));
            assertEquals(1L, retainedActivityCountForMember(connection, 56180));
            assertEquals(1L, retainedActivityCountForMember(connection, 56181));
            assertEquals(0L, retainedActivityCountForMember(connection, 0xFFFF));
        }
    }

    @Test
    void lateAttributionUpdatesOnlyTheMatchingDmrTimeslot() throws Exception
    {
        Path database = mTemporaryFolder.resolve("dmr-late-attribution-slot.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String guid = "123e4567-e89b-12d3-a456-426614174001";

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            for(int timeslot: List.of(1, 2))
            {
                P25ActivityLogSchema.recordActivity(connection, new P25ActivityLogRecords.ActivityEvent(
                    1_000L, "GUID:" + guid, guid, P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "DMR",
                    P25ActivityLogRecords.Action.CALL, "CALL_GROUP", null, null, null, List.of(),
                    461_125_000L, "12", timeslot, false, null, null, null, 7, null, null, 1,
                    "Example DMR", "DMR", null, true, null, null), true);
            }

            P25ActivityLogRecords.TrunkedCallAttribution attribution =
                new P25ActivityLogRecords.TrunkedCallAttribution(1_000L, "GUID:" + guid, guid,
                    461_125_000L, 1, 91, "TALKGROUP", List.of(), 101,
                    true, true, false, false);
            assertTrue(P25ActivityLogSchema.applyTrunkedCallAttribution(connection, attribution));

            assertEquals(91L, scalarLong(connection,
                "SELECT target_id FROM p25_activity_event WHERE timeslot = 1"));
            assertEquals(-1L, scalarLong(connection,
                "SELECT coalesce(target_id, -1) FROM p25_activity_event WHERE timeslot = 2"));
            assertEquals(2L, scalarLong(connection,
                "SELECT call_count FROM p25_site_activity_bucket"));
        }
    }

    @Test
    void aggregatesSuccessfulCallOutputsWithoutChangingTrackedCalls() throws Exception
    {
        Path database = mTemporaryFolder.resolve("call-outputs.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(500L));
            P25ActivityLogSchema.recordActivity(connection,
                activity(1_000L, P25ActivityLogRecords.Action.CALL), false);
            assertTrue(P25ActivityLogSchema.applyCompletedCallOutput(connection,
                new P25ActivityLogRecords.CompletedCallOutput(1_000L,
                    "123e4567-e89b-12d3-a456-426614174000", 56138,
                    P25ActivityLogRecords.CallOutput.RECORDED)));
            assertTrue(P25ActivityLogSchema.applyCompletedCallOutput(connection,
                new P25ActivityLogRecords.CompletedCallOutput(1_000L,
                    "123e4567-e89b-12d3-a456-426614174000", 56138,
                    P25ActivityLogRecords.CallOutput.STREAMED)));

            for(String table: List.of("p25_site_talkgroup_bucket", "p25_site_activity_bucket"))
            {
                try(Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                        "SELECT call_count, recorded_count, streamed_count FROM " + table))
                {
                    assertTrue(resultSet.next());
                    assertEquals(1, resultSet.getInt("call_count"));
                    assertEquals(1, resultSet.getInt("recorded_count"));
                    assertEquals(1, resultSet.getInt("streamed_count"));
                }
            }

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT summary.call_count, summary.recorded_count, summary.streamed_count
                    FROM trunked_identity_summary summary
                    JOIN trunked_identity_scope scope ON scope.scope_id=summary.scope_id
                    WHERE scope.p25_system_key=1
                      AND summary.identity_kind_code=1 AND summary.identity_id=56138
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(1, resultSet.getInt("call_count"));
                assertEquals(1, resultSet.getInt("recorded_count"));
                assertEquals(1, resultSet.getInt("streamed_count"));
            }

            assertFalse(P25ActivityLogSchema.applyCompletedCallOutput(connection,
                new P25ActivityLogRecords.CompletedCallOutput(1_000L, "missing-guid", 56138,
                    P25ActivityLogRecords.CallOutput.RECORDED)));
        }
    }

    @Test
    void aggregatesConventionalOutputsIntoTheTrackedCallHour() throws Exception
    {
        Path database = mTemporaryFolder.resolve("conventional-call-outputs.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                conventionalActivity(3_600_123L, P25ActivityLogRecords.Action.CALL), false);
            String contextKey = "CONVENTIONAL_ANALOG:NBFM:154310000";
            assertTrue(P25ActivityLogSchema.applyCompletedCallOutput(connection,
                new P25ActivityLogRecords.CompletedCallOutput(3_600_123L, contextKey, null,
                    154_310_000L, null, 0, null, List.of(),
                    P25ActivityLogRecords.CallOutput.RECORDED)));
            assertTrue(P25ActivityLogSchema.applyCompletedCallOutput(connection,
                new P25ActivityLogRecords.CompletedCallOutput(3_600_123L, contextKey, null,
                    154_310_000L, null, 0, null, List.of(),
                    P25ActivityLogRecords.CallOutput.STREAMED)));

            for(String table: List.of("conventional_activity_summary", "conventional_activity_bucket"))
            {
                try(Statement statement = connection.createStatement();
                    ResultSet resultSet = statement.executeQuery(
                        "SELECT call_count, recorded_count, streamed_count FROM " + table))
                {
                    assertTrue(resultSet.next());
                    assertEquals(1, resultSet.getInt("call_count"));
                    assertEquals(1, resultSet.getInt("recorded_count"));
                    assertEquals(1, resultSet.getInt("streamed_count"));
                }
            }

            assertCount(connection, "call_identity_bucket", 1);
            assertIdentityBucket(connection, 3_600_000L, P25ActivityLogSchema.IDENTITY_ROLE_DESTINATION,
                P25ActivityLogSchema.IDENTITY_KIND_CHANNEL_OR_UNKNOWN, 0, 1, 0, 1, 1);
        }
    }

    @Test
    void createsCompactSummaryForOutputOnlyTalkgroup() throws Exception
    {
        Path database = mTemporaryFolder.resolve("output-only-talkgroup.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.insertSite(connection, siteSnapshot(1_000L));
            assertTrue(P25ActivityLogSchema.applyCompletedCallOutput(connection,
                new P25ActivityLogRecords.CompletedCallOutput(2_000L,
                    "123e4567-e89b-12d3-a456-426614174000", 60000,
                    P25ActivityLogRecords.CallOutput.RECORDED)));

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("""
                    SELECT summary.call_count, summary.recorded_count, summary.streamed_count
                    FROM trunked_identity_summary summary
                    JOIN trunked_identity_scope scope ON scope.scope_id=summary.scope_id
                    WHERE scope.p25_system_key=1
                      AND summary.identity_kind_code=1 AND summary.identity_id=60000
                    """))
            {
                assertTrue(resultSet.next());
                assertEquals(0, resultSet.getInt("call_count"));
                assertEquals(1, resultSet.getInt("recorded_count"));
                assertEquals(0, resultSet.getInt("streamed_count"));
            }
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

    @Test
    void siteSnapshotsAuthoritativelyRemoveAliasListsAndP25ContextFields() throws Exception
    {
        Path database = mTemporaryFolder.resolve("site-alias-removal.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String guid = "123e4567-e89b-12d3-a456-426614174099";

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogRecords.SiteSnapshot p25 = siteSnapshot(1_000L, guid);
            P25ActivityLogSchema.insertSite(connection, p25);
            assertEquals("Example System", scalarString(connection, """
                SELECT alias_list_name FROM receiver_context WHERE guid='%s'
                """.formatted(guid)));

            //An older-style activity record has no configured-metadata observation and must not erase the alias.
            P25ActivityLogSchema.recordActivity(connection,
                activity(1_500L, P25ActivityLogRecords.Action.GRANT, guid), false);
            assertEquals("Example System", scalarString(connection, """
                SELECT alias_list_name FROM receiver_context WHERE guid='%s'
                """.formatted(guid)));

            P25ActivityLogSchema.insertSite(connection, withAliasList(p25, 2_000L, "without-alias", null));
            assertNull(scalarString(connection, """
                SELECT alias_list_name FROM receiver_context WHERE guid='%s'
                """.formatted(guid)));
            assertNull(scalarString(connection, """
                SELECT alias_list_name FROM p25_site_snapshot WHERE guid='%s'
                """.formatted(guid)));

            TrunkedSiteSchema.Snapshot dmr =
                trunkedSite(3_000L, guid, TrunkedSiteSchema.PROTOCOL_DMR, "dmr-transition").snapshot();
            TrunkedSiteSchema.upsert(connection, dmr);
            P25ActivityLogSchema.ensureTrunkedSiteIdentityScope(connection, dmr);

            assertEquals(TrunkedSiteSchema.PROTOCOL_DMR, scalarLong(connection, """
                SELECT protocol_code FROM receiver_context WHERE guid='%s'
                """.formatted(guid)));
            assertEquals(0, scalarLong(connection, """
                SELECT COUNT(*) FROM receiver_context
                WHERE guid='%s' AND (system_key IS NOT NULL OR nac IS NOT NULL OR rfss IS NOT NULL OR site IS NOT NULL)
                """.formatted(guid)));
            assertNull(scalarString(connection, """
                SELECT alias_list_name FROM receiver_context WHERE guid='%s'
                """.formatted(guid)));
        }
    }

    @Test
    void persistsConfiguredMetadataForP25AndNbfmConventionalContexts() throws Exception
    {
        Path database = mTemporaryFolder.resolve("configured-conventional-metadata.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            String p25Guid = "123e4567-e89b-12d3-a456-426614174090";
            P25ActivityLogSchema.recordActivity(connection, configuredConventionalActivity(1_000L, p25Guid,
                P25ActivityLogRecords.ContextKind.CONVENTIONAL_P25, "APCO25", "ELYRIA PDISP", "Elyria PD",
                "P25-1", 155_730_000L, 0x348), false);

            assertEquals(2L, scalarLong(connection, """
                SELECT kind_code FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(p25Guid)));
            assertEquals(1L, scalarLong(connection, """
                SELECT protocol_code FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(p25Guid)));
            assertEquals("ELYRIA PDISP", scalarString(connection, """
                SELECT channel_name FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(p25Guid)));
            assertEquals("Elyria PD", scalarString(connection, """
                SELECT alias_list_name FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(p25Guid)));
            assertEquals("P25-1", scalarString(connection, """
                SELECT decoder FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(p25Guid)));
            assertEquals(155_730_000L, scalarLong(connection, """
                SELECT primary_frequency_hz FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(p25Guid)));
            assertEquals(0x348L, scalarLong(connection, """
                SELECT nac FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(p25Guid)));

            P25ActivityLogSchema.recordActivity(connection, configuredConventionalActivity(2_000L, p25Guid,
                P25ActivityLogRecords.ContextKind.CONVENTIONAL_P25, "APCO25", "ELYRIA PDISP", null,
                "P25-1", 155_730_000L, 0x348), false);
            assertNull(scalarString(connection, """
                SELECT alias_list_name FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(p25Guid)));

            String nbfmGuid = "123e4567-e89b-12d3-a456-426614174091";
            P25ActivityLogSchema.recordActivity(connection, configuredConventionalActivity(3_000L, nbfmGuid,
                P25ActivityLogRecords.ContextKind.CONVENTIONAL_ANALOG, "NBFM", "County Fire",
                "Conventional Lorain Cnty", "NBFM", 154_310_000L, null), false);

            assertEquals(10L, scalarLong(connection, """
                SELECT kind_code FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(nbfmGuid)));
            assertEquals(10L, scalarLong(connection, """
                SELECT protocol_code FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(nbfmGuid)));
            assertEquals("County Fire", scalarString(connection, """
                SELECT channel_name FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(nbfmGuid)));
            assertEquals("Conventional Lorain Cnty", scalarString(connection, """
                SELECT alias_list_name FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(nbfmGuid)));
            assertEquals(154_310_000L, scalarLong(connection, """
                SELECT primary_frequency_hz FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(nbfmGuid)));
            assertEquals(1L, scalarLong(connection, """
                SELECT COUNT(*) FROM receiver_context WHERE context_key='GUID:%s'
                  AND system_key IS NULL AND nac IS NULL AND rfss IS NULL AND site IS NULL
                  AND current_control_hz IS NULL
                """.formatted(nbfmGuid)));
        }
    }

    @Test
    void persistsConfiguredMetadataForDmrAndNxdnTrunkedSites() throws Exception
    {
        Path database = mTemporaryFolder.resolve("configured-trunked-metadata.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String dmrGuid = "123e4567-e89b-12d3-a456-426614174092";
        String nxdnGuid = "123e4567-e89b-12d3-a456-426614174093";

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            TrunkedSiteSchema.Snapshot dmr = trunkedSite(1_000L, dmrGuid, TrunkedSiteSchema.PROTOCOL_DMR,
                "dmr-metadata", "Metro DMR Aliases").snapshot();
            TrunkedSiteSchema.upsert(connection, dmr);
            P25ActivityLogSchema.ensureTrunkedSiteIdentityScope(connection, dmr);

            TrunkedSiteSchema.Snapshot nxdn = trunkedSite(2_000L, nxdnGuid, TrunkedSiteSchema.PROTOCOL_NXDN,
                "nxdn-metadata", "Metro NXDN Aliases").snapshot();
            TrunkedSiteSchema.upsert(connection, nxdn);
            P25ActivityLogSchema.ensureTrunkedSiteIdentityScope(connection, nxdn);

            assertEquals("Downtown", scalarString(connection, """
                SELECT channel_name FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(dmrGuid)));
            assertEquals("Metro DMR Aliases", scalarString(connection, """
                SELECT alias_list_name FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(dmrGuid)));
            assertEquals("DMR", scalarString(connection, """
                SELECT decoder FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(dmrGuid)));
            assertEquals(451_000_000L, scalarLong(connection, """
                SELECT primary_frequency_hz FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(dmrGuid)));
            assertEquals(451_000_000L, scalarLong(connection, """
                SELECT current_control_hz FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(dmrGuid)));

            assertEquals("North", scalarString(connection, """
                SELECT channel_name FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(nxdnGuid)));
            assertEquals("Metro NXDN Aliases", scalarString(connection, """
                SELECT alias_list_name FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(nxdnGuid)));
            assertEquals("NXDN", scalarString(connection, """
                SELECT decoder FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(nxdnGuid)));
            assertEquals(155_000_000L, scalarLong(connection, """
                SELECT primary_frequency_hz FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(nxdnGuid)));
            assertEquals(155_000_000L, scalarLong(connection, """
                SELECT current_control_hz FROM receiver_context WHERE context_key='GUID:%s'
                """.formatted(nxdnGuid)));
            assertEquals(2L, scalarLong(connection, """
                SELECT COUNT(*) FROM receiver_context
                WHERE kind_code=1 AND protocol_code IN (3,4) AND system_key IS NULL
                  AND nac IS NULL AND rfss IS NULL AND site IS NULL
                """));
        }
    }

    @Test
    void clearSiteWaitsForEarlierObservationsAndPrecedesLaterObservations() throws Exception
    {
        Path database = mTemporaryFolder.resolve("writer-clear-order.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        String guid = "123e4567-e89b-12d3-a456-426614174000";
        String retainedGuid = "223e4567-e89b-12d3-a456-426614174000";
        long now = System.currentTimeMillis();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            insertAdministratorData(connection);
        }

        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, true, 1024);
        writer.start();

        //Fill a startup backlog so the clear request has earlier observations to cross as a queue barrier.
        for(int x = 0; x < 400; x++)
        {
            writer.enqueue(activity(now - 1_000L + x, P25ActivityLogRecords.Action.GRANT, guid));
        }

        writer.enqueue(trunkedSite(now - 500L, guid, TrunkedSiteSchema.PROTOCOL_DMR, "pre-clear"));
        writer.enqueue(trunkedSite(now - 500L, retainedGuid, TrunkedSiteSchema.PROTOCOL_NXDN, "retained"));
        StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.clearSite(guid);
        writer.submitMaintenance(request);
        writer.enqueue(activity(now, P25ActivityLogRecords.Action.GRANT, guid));
        writer.enqueue(trunkedSite(now, guid, TrunkedSiteSchema.PROTOCOL_DMR, "post-clear"));

        P25ActivityLogMaintenance.Result result = request.result().get(10, TimeUnit.SECONDS);
        assertEquals(P25ActivityLogMaintenance.Operation.CLEAR_SITE_STATS, result.operation());
        writer.close();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            //The clear removed every pre-request row and the post-request observation was written afterward.
            assertCount(connection, "p25_activity_event", 1);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT observed_at_ms FROM p25_activity_event"))
            {
                assertTrue(resultSet.next());
                assertEquals(now, resultSet.getLong(1));
                assertFalse(resultSet.next());
            }

            assertGuidCount(connection, "trunked_site_snapshot", guid, 1);
            assertGuidCount(connection, "trunked_site_snapshot", retainedGuid, 1);
            assertEquals(now, scalarLong(connection,
                "SELECT last_seen_ms FROM trunked_site_snapshot WHERE guid='" + guid + "'"));
            assertEquals(TrunkedSiteSchema.PROTOCOL_DMR, scalarLong(connection,
                "SELECT protocol_code FROM trunked_site_snapshot WHERE guid='" + guid + "'"));
            assertEquals(TrunkedSiteSchema.PROTOCOL_NXDN, scalarLong(connection,
                "SELECT protocol_code FROM trunked_site_snapshot WHERE guid='" + retainedGuid + "'"));
            assertAdministratorData(connection);
        }
    }

    @Test
    void resetStatsWaitsForEarlierObservationsAndPrecedesLaterObservations() throws Exception
    {
        Path database = mTemporaryFolder.resolve("writer-reset-order.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        long now = System.currentTimeMillis();
        String dmrGuid = "123e4567-e89b-12d3-a456-426614174000";
        String nxdnGuid = "223e4567-e89b-12d3-a456-426614174000";

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            insertAdministratorData(connection);
        }

        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, true, 1024);
        writer.start();

        for(int x = 0; x < 400; x++)
        {
            writer.enqueue(activity(now - 1_000L + x, P25ActivityLogRecords.Action.GRANT));
        }

        writer.enqueue(activity(now - 2_000L, P25ActivityLogRecords.Action.CALL));
        writer.enqueue(trunkedSite(now - 500L, dmrGuid, TrunkedSiteSchema.PROTOCOL_DMR, "pre-reset-dmr"));
        writer.enqueue(trunkedSite(now - 500L, nxdnGuid, TrunkedSiteSchema.PROTOCOL_NXDN, "pre-reset-nxdn"));
        StatsDatabaseMaintenanceRequest request =
            StatsDatabaseMaintenanceRequest.forOperation(P25ActivityLogMaintenance.Operation.RESET_STATS);
        writer.submitMaintenance(request);
        writer.enqueue(activity(now, P25ActivityLogRecords.Action.CALL));
        writer.enqueue(trunkedSite(now, dmrGuid, TrunkedSiteSchema.PROTOCOL_DMR, "post-reset-dmr"));
        writer.enqueue(trunkedSite(now, nxdnGuid, TrunkedSiteSchema.PROTOCOL_NXDN, "post-reset-nxdn"));

        P25ActivityLogMaintenance.Result result = request.result().get(10, TimeUnit.SECONDS);
        assertEquals(P25ActivityLogMaintenance.Operation.RESET_STATS, result.operation());
        writer.close();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertCount(connection, "p25_activity_event", 1);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT observed_at_ms FROM p25_activity_event"))
            {
                assertTrue(resultSet.next());
                assertEquals(now, resultSet.getLong(1));
                assertFalse(resultSet.next());
            }

            assertCount(connection, "trunked_site_snapshot", 2);
            assertCount(connection, "call_identity_bucket", 2);
            assertEquals(2L, scalarLong(connection,
                "SELECT SUM(call_count) FROM call_identity_bucket"));
            assertCount(connection, "trunked_site_channel_summary", 2);
            assertCount(connection, "trunked_site_neighbor_summary", 2);
            assertEquals(2, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_snapshot WHERE last_seen_ms=" + now));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_site_snapshot
                WHERE protocol_code=3
                """));
            assertEquals(1, scalarLong(connection, """
                SELECT COUNT(*) FROM trunked_site_snapshot
                WHERE protocol_code=4
                """));
            assertAdministratorData(connection);
        }
    }

    @Test
    void loweringRetentionRequestsPromptCleanupOnWriter() throws Exception
    {
        Path database = mTemporaryFolder.resolve("writer-retention-reduction.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        long now = System.currentTimeMillis();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            P25ActivityLogSchema.recordActivity(connection,
                activity(now - TimeUnit.DAYS.toMillis(2), P25ActivityLogRecords.Action.GRANT), true);
        }

        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, true, 10);
        writer.start();
        waitForState(writer, P25ActivityLogStatus.State.RUNNING);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertCount(connection, "p25_activity_event", 1);
        }

        writer.setRetentionDays(1);
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        int remaining = 1;

        while(remaining != 0 && System.currentTimeMillis() < deadline)
        {
            try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
            {
                remaining = count(connection, "p25_activity_event");
            }

            if(remaining != 0)
            {
                Thread.sleep(25);
            }
        }

        writer.close();
        assertEquals(0, remaining);
    }

    @Test
    void rejectsMaintenanceWhenWriterIsNotRunning()
    {
        P25ActivityLogWriter writer = new P25ActivityLogWriter(mTemporaryFolder.resolve("not-running.sqlite"),
            30, false, 1);
        StatsDatabaseMaintenanceRequest request =
            StatsDatabaseMaintenanceRequest.forOperation(P25ActivityLogMaintenance.Operation.RESET_STATS);

        writer.submitMaintenance(request);

        assertTrue(request.result().isCompletedExceptionally());
    }

    private static void waitForState(P25ActivityLogWriter writer, P25ActivityLogStatus.State expected)
        throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);

        while(writer.getStatus().state() != expected && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
        }

        assertEquals(expected, writer.getStatus().state());
    }

    private static void insertTrunkedSite(java.sql.PreparedStatement statement, String guid, int protocol,
                                          long observedAt) throws Exception
    {
        statement.setString(1, guid);
        statement.setString(2, "hash-" + guid);
        statement.setInt(3, protocol);
        statement.setLong(4, observedAt);
        statement.setLong(5, observedAt);
        statement.executeUpdate();
    }

    private static P25ActivityLogRecords.TrunkedSiteSnapshot trunkedSite(long observedAt, String guid,
                                                                         int protocol, String hash)
    {
        return trunkedSite(observedAt, guid, protocol, hash, null);
    }

    private static P25ActivityLogRecords.TrunkedSiteSnapshot trunkedSite(long observedAt, String guid,
                                                                         int protocol, String hash,
                                                                         String aliasListName)
    {
        boolean dmr = protocol == TrunkedSiteSchema.PROTOCOL_DMR;
        TrunkedSiteSchema.Channel channel = dmr ?
            new TrunkedSiteSchema.Channel(42, null, 1, 451_000_000L, 456_000_000L,
                TrunkedSiteSchema.CHANNEL_ROLE_TRAFFIC, observedAt) :
            new TrunkedSiteSchema.Channel(120, 121, null, 155_000_000L, 160_000_000L,
                TrunkedSiteSchema.CHANNEL_ROLE_CURRENT_CONTROL, observedAt);
        TrunkedSiteSchema.Neighbor neighbor = dmr ?
            new TrunkedSiteSchema.Neighbor(1, 2, 10, 20, 31, 43, 452_000_000L,
                TrunkedSiteSchema.NEIGHBOR_STATUS_ACTIVE, observedAt) :
            new TrunkedSiteSchema.Neighbor(2, 4, 7, 8, 10, 122, 155_012_500L,
                TrunkedSiteSchema.NEIGHBOR_STATUS_ISOLATED, observedAt);
        TrunkedSiteSchema.Snapshot snapshot = new TrunkedSiteSchema.Snapshot(
            observedAt, guid, hash, protocol, dmr ? 1 : 2, dmr ? 2 : 4,
            dmr ? "Metro DMR" : "Metro NXDN", dmr ? "Downtown" : "North", aliasListName,
            dmr ? "DMR" : "NXDN", dmr ? 10 : 7, dmr ? null : 8, dmr ? 20 : 9,
            dmr ? null : 12, null, null, null, null, null, null, null, 0, null,
            channel.frequencyHertz(), channel.frequencyHertz(), List.of(channel), List.of(neighbor));
        return new P25ActivityLogRecords.TrunkedSiteSnapshot(observedAt, snapshot);
    }

    private static void insertAdministratorData(Connection connection) throws Exception
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias_list(id, name, family)
                VALUES (1, 'Administrator', 'P25')
                """);
            statement.executeUpdate("""
                INSERT INTO alias(alias_list_id, name, matcher_type, protocol, value)
                VALUES (1, 'Administrator Alias', 'TALKGROUP', 'APCO25', 1)
                """);
            statement.executeUpdate("""
                INSERT INTO configuration_channel (
                    sort_order, name, auto_start, frequency_count, recording_enabled, event_logging_enabled,
                    config_json
                ) VALUES (0, 'Administrator Channel', 0, 1, 0, 0, '{}')
                """);
            statement.executeUpdate("""
                INSERT INTO application_settings (key, settings_json, updated_at_ms)
                VALUES ('administrator-setting', '{}', 1)
                """);
        }
    }

    private static void assertAdministratorData(Connection connection) throws Exception
    {
        assertCount(connection, "alias", 1);
        assertCount(connection, "configuration_channel", 1);
        assertCount(connection, "application_settings", 1);
    }

    private static void assertCount(Connection connection, String table, int expected) throws Exception
    {
        assertEquals(expected, count(connection, table));
    }

    private static void assertIdentityCount(Connection connection, int identityKind, int expected) throws Exception
    {
        assertEquals(expected, scalarLong(connection, """
            SELECT COUNT(*) FROM trunked_identity_summary WHERE identity_kind_code=%d
            """.formatted(identityKind)));
    }

    private static void assertGroupIdentityCount(Connection connection, int expected) throws Exception
    {
        assertEquals(expected, scalarLong(connection, """
            SELECT COUNT(*) FROM trunked_identity_summary WHERE identity_kind_code IN (1,3)
            """));
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

    private static long scalarLong(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static long retainedActivityCountForMember(Connection connection, int talkgroupId) throws Exception
    {
        try(java.sql.PreparedStatement statement = connection.prepareStatement("""
            SELECT COUNT(DISTINCT event.id)
            FROM p25_activity_event event
            JOIN activity_event_talkgroup_member member ON member.event_id=event.id
            WHERE member.talkgroup_id=?
            """))
        {
            statement.setInt(1, talkgroupId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                return resultSet.getLong(1);
            }
        }
    }

    private static String scalarString(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static void assertGuidCount(Connection connection, String table, String guid, int expected)
        throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "SELECT COUNT(*) FROM " + table + " WHERE guid='" + guid + "'"))
        {
            assertTrue(resultSet.next());
            assertEquals(expected, resultSet.getInt(1));
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

    private static void assertIdentityBucket(Connection connection, long bucketStart, int roleCode, int kindCode,
                                             int identityId, int calls, int encrypted, int recorded, int streamed)
        throws Exception
    {
        try(java.sql.PreparedStatement statement = connection.prepareStatement("""
            SELECT call_count, encrypted_count, recorded_count, streamed_count
            FROM call_identity_bucket
            WHERE bucket_start_ms = ? AND identity_role_code = ? AND identity_kind_code = ? AND identity_id = ?
            """))
        {
            statement.setLong(1, bucketStart);
            statement.setInt(2, roleCode);
            statement.setInt(3, kindCode);
            statement.setInt(4, identityId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                assertEquals(calls, resultSet.getInt("call_count"));
                assertEquals(encrypted, resultSet.getInt("encrypted_count"));
                assertEquals(recorded, resultSet.getInt("recorded_count"));
                assertEquals(streamed, resultSet.getInt("streamed_count"));
                assertFalse(resultSet.next());
            }
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

    private static void recordConfirmedActivity(Connection connection,
                                                P25ActivityLogRecords.ActivityEvent activity,
                                                boolean detailedHistory) throws Exception
    {
        P25ActivityLogSchema.recordActivity(connection, activity, detailedHistory);
        ChannelTag tag = activity.eventType() != null && activity.eventType().contains("DATA") ?
            ChannelTag.DATA : ChannelTag.VOICE;
        boolean tdma = "APCO25_PHASE2".equals(activity.protocol()) ||
            (activity.decoder() != null && activity.decoder().contains("PHASE2")) ||
            (activity.lcn() != null && activity.lcn().contains("TS"));
        P25ActivityLogSchema.upsertGrantedChannelSummary(connection,
            new P25ActivityLogRecords.ChannelFact(activity.observedAtEpochMilliseconds(), activity.guid(),
                activity.lcn(), activity.frequencyHertz(), tag, tdma, tdma ? 2 : 1));
    }

    private static P25ActivityLogRecords.ControlChannelQuality quality(long timestamp, double signalDbfs)
    {
        return quality(timestamp, signalDbfs, "123e4567-e89b-12d3-a456-426614174000");
    }

    private static P25ActivityLogRecords.ControlChannelQuality quality(long timestamp, double signalDbfs, String guid)
    {
        return new P25ActivityLogRecords.ControlChannelQuality(timestamp, guid, 856_137_500L, signalDbfs, signalDbfs,
            signalDbfs - 1.0, signalDbfs + 1.0, 98.5, 10, 1, 3, 0, 0, timestamp);
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

    private static P25ActivityLogRecords.ActivityEvent identityActivity(long timestamp, int sourceRadio,
                                                                         int talkgroup,
                                                                         P25ActivityLogRecords.RadioAffiliationUpdate
                                                                             affiliationUpdate)
    {
        String guid = "123e4567-e89b-12d3-a456-426614174000";
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:" + guid, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25", P25ActivityLogRecords.Action.JOIN,
            "AFFILIATE", Integer.toString(sourceRadio), Integer.toString(talkgroup), "TALKGROUP",
            null, null, null, false, null, null, 0xBEE00, 0x348, 0x348, 2, 1,
            "Example Site", null, null, false, null, affiliationUpdate);
    }

    private static P25ActivityLogRecords.ActivityEvent countedIdentityActivity(long timestamp, int sourceRadio,
                                                                                int talkgroup)
    {
        String guid = "123e4567-e89b-12d3-a456-426614174000";
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:" + guid, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25", P25ActivityLogRecords.Action.CALL,
            "CALL_GROUP", Integer.toString(sourceRadio), Integer.toString(talkgroup), "TALKGROUP",
            null, null, null, false, null, null, 0xBEE00, 0x348, 0x348, 2, 1,
            "Example Site", null, null, true, null, null);
    }

    private static P25ActivityLogRecords.ActivityEvent patchActivity(long timestamp)
    {
        String guid = "123e4567-e89b-12d3-a456-426614174000";
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:" + guid, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25", P25ActivityLogRecords.Action.CALL,
            "CALL_PATCH_GROUP_ENCRYPTED", "1811524", "56182", "PATCH_GROUP",
            List.of(56181, 56180, 56180, 56182, -1), 854187500L, "00-0509", 1, true, 0x84, 101,
            0xBEE00, 0x348, 0x348, 2, 1, "Example Site", "P25_PHASE1", null, true, null, null);
    }

    private static P25ActivityLogRecords.ActivityEvent activityWithTalkerAlias(long timestamp, String talkerAlias)
    {
        String guid = "123e4567-e89b-12d3-a456-426614174000";
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:" + guid, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25", P25ActivityLogRecords.Action.CALL,
            "CALL_GROUP", "1811524", "56138", "TALKGROUP", List.of(), 854187500L, "00-0509", 1, false,
            null, null, 0xBEE00, 0x348, 0x348, 2, 1, "Example Site", null, talkerAlias, true, null, null);
    }

    private static P25ActivityLogRecords.ActivityEvent serviceActivity(long timestamp, String eventType,
                                                                        boolean encrypted, long frequency,
                                                                        String lcn)
    {
        String guid = "123e4567-e89b-12d3-a456-426614174000";
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:" + guid, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25", P25ActivityLogRecords.Action.GRANT,
            eventType, "1811524", "56138", "TALKGROUP", frequency, lcn, 1, encrypted,
            encrypted ? 0x84 : null, encrypted ? 101 : null, 0xBEE00, 0x348, 0x348, 2, 1,
            "Example Site", "P25-1", null, false, null, null);
    }

    private static P25ActivityLogRecords.ActivityEvent activityWithChannelName(long timestamp, String channelName)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:123e4567-e89b-12d3-a456-426614174000",
            "123e4567-e89b-12d3-a456-426614174000", P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25",
            P25ActivityLogRecords.Action.GRANT, "CALL_GROUP", "1811524", "56138", "TALKGROUP", 854187500L,
            "00-0509", 1, true, 0x84, 101, 0xBEE00, 0x348, 0x348, 2, 1, channelName, null, null,
            false, null, null);
    }

    private static P25ActivityLogRecords.ActivityEvent activityWithNetworkIdentity(long timestamp, int wacn,
                                                                                    int system, int rfss, int site)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp,
            "GUID:123e4567-e89b-12d3-a456-426614174000", "123e4567-e89b-12d3-a456-426614174000",
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "APCO25", P25ActivityLogRecords.Action.CALL,
            "CALL_GROUP", "1811524", "56138", "TALKGROUP", 854187500L, "00-0509", 1, false,
            null, null, wacn, system, 0x999, rfss, site, "Wrong Site", null, null, true, null, null);
    }

    private static P25ActivityLogRecords.ActivityEvent conventionalActivity(long timestamp,
                                                                             P25ActivityLogRecords.Action action)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "CONVENTIONAL_ANALOG:NBFM:154310000", null,
            P25ActivityLogRecords.ContextKind.CONVENTIONAL_ANALOG, "NBFM", action, "CALL", null, null, null,
            154310000L, null, null, false, null, null, null, null, null, null, null, "County Fire", "NBFM",
            null, action == P25ActivityLogRecords.Action.CALL, null, null);
    }

    private static P25ActivityLogRecords.ActivityEvent configuredConventionalActivity(long timestamp, String guid,
        P25ActivityLogRecords.ContextKind contextKind, String protocol, String channelName, String aliasListName,
        String decoder, long frequencyHertz, Integer nac)
    {
        return new P25ActivityLogRecords.ActivityEvent(timestamp, "GUID:" + guid, guid, contextKind, protocol,
            P25ActivityLogRecords.Action.CALL, "CALL", null, null, null, List.of(), frequencyHertz, null, null,
            false, null, null, null, null, nac, null, null, channelName, decoder, null, true, null, null,
            P25ActivityLogRecords.IdentityDomain.STANDARD, P25ActivityLogRecords.P25TargetIdentity.UNKNOWN,
            List.of(), aliasListName, true);
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
        return siteSnapshot(timestamp, "123e4567-e89b-12d3-a456-426614174000");
    }

    private static P25ActivityLogRecords.SiteSnapshot siteSnapshot(long timestamp, String guid)
    {
        List<P25NetworkConfigurationSnapshot.Channel> channels = List.of(
            new P25NetworkConfigurationSnapshot.Channel("primary_control", "00-0821", 856137500L, null, false, 1,
                "WPFF205"));
        List<P25NetworkConfigurationSnapshot.NeighborSite> neighbors = List.of(
            new P25NetworkConfigurationSnapshot.NeighborSite(0x348, 0x348, 2, 2, null, "00-0661", 855137500L,
                null, "ACTIVE"));
        List<P25NetworkConfigurationSnapshot.FrequencyBand> bands = List.of(
            new P25NetworkConfigurationSnapshot.FrequencyBand(0, false, 851006250L, 12500, 6250L,
                -45000000L, 1));
        List<P25NetworkConfigurationSnapshot.PatchGroup> patches = List.of(
            new P25NetworkConfigurationSnapshot.PatchGroup(56182, 1, List.of(56180), List.of(1811524)));
        List<P25NetworkConfigurationSnapshot.ForeignSystemBand> foreignBands = List.of(
            new P25NetworkConfigurationSnapshot.ForeignSystemBand(0xBEE00, 0x9EF, 4, 1,
                935_012_500L, 12_500L, -39_000_000L),
            new P25NetworkConfigurationSnapshot.ForeignSystemBand(0xBEE00, 0x9EF, 5, 3,
                935_012_500L, 12_500L, -39_000_000L),
            new P25NetworkConfigurationSnapshot.ForeignSystemBand(0xBEE00, 0x954, 0, 1,
                851_006_250L, 6_250L, -45_000_000L));

        return new P25ActivityLogRecords.SiteSnapshot(timestamp, guid,
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, "hash", "APCO25", "Example Site", "Example System", "P25-1",
            0xBEE00, 0x348, 0x348, 2, 1, 0, true,
            new P25NetworkConfigurationSnapshot.SiteStatus(1_784_000_000_000L, 110, true,
                "Autonomous and by Request", 240, true, 0x90, true),
            856137500L, 856137500L, channels, neighbors, bands, patches, foreignBands);
    }

    private static P25ActivityLogRecords.SiteSnapshot siteSnapshotWithTiming(long timestamp, long broadcastClock)
    {
        return siteSnapshotWithTiming(timestamp, broadcastClock, 110);
    }

    private static P25ActivityLogRecords.SiteSnapshot siteSnapshotWithTiming(long timestamp, Long broadcastClock,
                                                                              int microSlots)
    {
        P25ActivityLogRecords.SiteSnapshot snapshot = siteSnapshot(timestamp);
        P25NetworkConfigurationSnapshot.SiteStatus status = snapshot.siteStatus();
        P25NetworkConfigurationSnapshot.SiteStatus updatedStatus = new P25NetworkConfigurationSnapshot.SiteStatus(
            broadcastClock, microSlots, status.dataService(), status.dataAccess(),
            status.wuidLeaseMinutes(), status.registrationService(), status.mfid(), status.voiceService());

        return new P25ActivityLogRecords.SiteSnapshot(timestamp, snapshot.guid(), snapshot.contextKind(),
            snapshot.snapshotHash(), snapshot.protocol(), snapshot.channelName(), snapshot.aliasListName(),
            snapshot.decoder(), snapshot.wacn(), snapshot.systemId(), snapshot.nac(), snapshot.rfss(), snapshot.site(),
            snapshot.lra(), snapshot.tdma(), updatedStatus, snapshot.primaryFrequencyHertz(),
            snapshot.currentControlHertz(), snapshot.channels(), snapshot.neighborSites(), snapshot.frequencyBands(),
            snapshot.patchGroups(), snapshot.foreignSystemBands());
    }

    private static P25ActivityLogRecords.SiteSnapshot withSnapshotHash(
        P25ActivityLogRecords.SiteSnapshot snapshot, String snapshotHash)
    {
        return new P25ActivityLogRecords.SiteSnapshot(snapshot.observedAtEpochMilliseconds(), snapshot.guid(),
            snapshot.contextKind(), snapshotHash, snapshot.protocol(), snapshot.channelName(),
            snapshot.aliasListName(), snapshot.decoder(), snapshot.wacn(), snapshot.systemId(), snapshot.nac(),
            snapshot.rfss(), snapshot.site(), snapshot.lra(), snapshot.tdma(), snapshot.siteStatus(),
            snapshot.primaryFrequencyHertz(), snapshot.currentControlHertz(), snapshot.channels(),
            snapshot.neighborSites(), snapshot.frequencyBands(), snapshot.patchGroups(),
            snapshot.foreignSystemBands());
    }

    private static P25ActivityLogRecords.SiteSnapshot withAliasList(
        P25ActivityLogRecords.SiteSnapshot snapshot, long timestamp, String snapshotHash, String aliasListName)
    {
        return new P25ActivityLogRecords.SiteSnapshot(timestamp, snapshot.guid(), snapshot.contextKind(),
            snapshotHash, snapshot.protocol(), snapshot.channelName(), aliasListName, snapshot.decoder(),
            snapshot.wacn(), snapshot.systemId(), snapshot.nac(), snapshot.rfss(), snapshot.site(), snapshot.lra(),
            snapshot.tdma(), snapshot.siteStatus(), snapshot.primaryFrequencyHertz(), snapshot.currentControlHertz(),
            snapshot.channels(), snapshot.neighborSites(), snapshot.frequencyBands(), snapshot.patchGroups(),
            snapshot.foreignSystemBands());
    }

    private static P25ActivityLogRecords.SiteSnapshot siteSnapshotWithDuplicateChannels(long timestamp)
    {
        P25ActivityLogRecords.SiteSnapshot snapshot = siteSnapshot(timestamp);
        List<P25NetworkConfigurationSnapshot.Channel> channels = List.of(
            new P25NetworkConfigurationSnapshot.Channel("secondary_control", "0-821", 856137500L,
                811137500L, false, 1),
            new P25NetworkConfigurationSnapshot.Channel("fdma_data", "00-0821", 856137500L,
                null, false, 1),
            new P25NetworkConfigurationSnapshot.Channel("primary_control", "00-0821", 856137500L,
                null, false, null));

        return new P25ActivityLogRecords.SiteSnapshot(snapshot.observedAtEpochMilliseconds(), snapshot.guid(),
            snapshot.contextKind(), "duplicate-channel-hash", snapshot.protocol(), snapshot.channelName(),
            snapshot.aliasListName(), snapshot.decoder(), snapshot.wacn(), snapshot.systemId(), snapshot.nac(),
            snapshot.rfss(), snapshot.site(), snapshot.primaryFrequencyHertz(), snapshot.currentControlHertz(),
            channels, snapshot.neighborSites(), snapshot.frequencyBands(), snapshot.patchGroups());
    }
}
