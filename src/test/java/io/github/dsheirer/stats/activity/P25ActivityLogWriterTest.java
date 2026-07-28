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
            P25ActivityLogSchema.deleteOlderThan(connection, 50000L);

            try(Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery("SELECT action FROM p25_activity_event_resolved"))
            {
                assertTrue(resultSet.next());
                assertEquals(P25ActivityLogRecords.Action.GRANT.name(), resultSet.getString(1));
            }

            assertCount(connection, "p25_site_channel_tag", 0);

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

            //These summaries are shared by all receiver sites for the system and cannot be deleted site-by-site.
            assertCount(connection, "p25_system", 1);
            assertCount(connection, "p25_talkgroup_summary", 1);
            assertActionCount(connection, "p25_talkgroup_summary", "grant_count", 2);
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
                assertEquals("21", resultSet.getString(1));
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
    void explicitSchemaStepsCreateAndValidateForeignBandsAndQualityRetentionIndex() throws Exception
    {
        Path database = mTemporaryFolder.resolve("schema-v19-to-v21.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE p25_foreign_system_band");
            statement.executeUpdate("DROP TABLE p25_foreign_system_band_summary");
            statement.executeUpdate("DROP INDEX idx_p25_control_quality_retention");
            SdrTrunkDatabaseStartup.setMetadata(connection, "p25_activity_schema_version", "19");

            assertThrows(Exception.class, () -> P25ActivityLogSchema.validate(connection));

            statement.execute("BEGIN IMMEDIATE");
            P25ActivityLogSchema.createForeignSystemBandTables(statement);
            SdrTrunkDatabaseStartup.setMetadata(connection, "p25_activity_schema_version", "20");
            assertThrows(Exception.class, () -> P25ActivityLogSchema.validate(connection));
            P25ActivityLogSchema.createControlChannelQualityRetentionIndex(statement);
            SdrTrunkDatabaseStartup.setMetadata(connection, "p25_activity_schema_version", "21");
            P25ActivityLogSchema.validate(connection);
            statement.execute("COMMIT");

            try(ResultSet resultSet = statement.executeQuery("PRAGMA quick_check"))
            {
                assertTrue(resultSet.next());
                assertEquals("ok", resultSet.getString(1));
            }
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
            assertCount(connection, "p25_radio_summary", 0);
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
            assertCount(connection, "p25_talkgroup_summary", 0);
            assertCount(connection, "p25_radio_summary", 0);
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
    void activityCannotRekeyIdentityEstablishedBySiteSnapshot() throws Exception
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
            assertCount(connection, "p25_talkgroup_summary", 1);
            assertCount(connection, "p25_radio_summary", 1);

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
                    SELECT call_count, recorded_count, streamed_count
                    FROM p25_talkgroup_summary WHERE system_key = 1 AND talkgroup_id = 56138
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
                    SELECT call_count, recorded_count, streamed_count
                    FROM p25_talkgroup_summary WHERE system_key = 1 AND talkgroup_id = 60000
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

        writer.enqueue(trunkedSite(now - 500L, dmrGuid, TrunkedSiteSchema.PROTOCOL_DMR, "pre-reset-dmr"));
        writer.enqueue(trunkedSite(now - 500L, nxdnGuid, TrunkedSiteSchema.PROTOCOL_NXDN, "pre-reset-nxdn"));
        StatsDatabaseMaintenanceRequest request =
            StatsDatabaseMaintenanceRequest.forOperation(P25ActivityLogMaintenance.Operation.RESET_STATS);
        writer.submitMaintenance(request);
        writer.enqueue(activity(now, P25ActivityLogRecords.Action.GRANT));
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
            dmr ? "Metro DMR" : "Metro NXDN", dmr ? "Downtown" : "North", null,
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
                INSERT INTO alias_list(id, name, system_name, family)
                VALUES (1, 'Administrator', 'Administrator', 'P25')
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
        P25ActivityLogRecords.SiteSnapshot snapshot = siteSnapshot(timestamp);
        P25NetworkConfigurationSnapshot.SiteStatus status = snapshot.siteStatus();
        P25NetworkConfigurationSnapshot.SiteStatus updatedStatus = new P25NetworkConfigurationSnapshot.SiteStatus(
            broadcastClock, status.microSlots(), status.dataService(), status.dataAccess(),
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
