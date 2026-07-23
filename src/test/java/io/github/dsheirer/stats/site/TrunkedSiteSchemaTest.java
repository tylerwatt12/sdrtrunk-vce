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

package io.github.dsheirer.stats.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrunkedSiteSchemaTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void createsAndValidatesIndependentSchema() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            TrunkedSiteSchema.validate(connection);
            assertEquals("2", TrunkedSiteSchema.schemaVersion(connection));
        }
    }

    @Test
    void unchangedHeartbeatOnlyUpdatesSiteSummary() throws Exception
    {
        Path database = mTemporaryFolder.resolve("heartbeat.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            TrunkedSiteSchema.upsert(connection, snapshot(1_000L, "hash-1", 7));
            TrunkedSiteSchema.upsert(connection, snapshot(6_000L, "hash-1", 7));

            assertEquals(2, scalarLong(connection,
                "SELECT observation_count FROM trunked_site_snapshot WHERE guid='dmr-site'"));
            assertEquals(6_000L, scalarLong(connection,
                "SELECT last_seen_ms FROM trunked_site_snapshot WHERE guid='dmr-site'"));
            assertEquals(1, scalarLong(connection,
                "SELECT observation_count FROM trunked_site_channel_summary WHERE guid='dmr-site'"));
            assertEquals(1, scalarLong(connection,
                "SELECT observation_count FROM trunked_site_neighbor_summary WHERE guid='dmr-site'"));

            TrunkedSiteSchema.upsert(connection, snapshot(7_000L, "hash-2", 8));
            assertEquals(2, scalarLong(connection,
                "SELECT observation_count FROM trunked_site_channel_summary WHERE guid='dmr-site'"));
            assertEquals(2, scalarLong(connection,
                "SELECT observation_count FROM trunked_site_neighbor_summary WHERE guid='dmr-site'"));
            assertEquals(8, scalarLong(connection,
                "SELECT service_flags FROM trunked_site_snapshot WHERE guid='dmr-site'"));
        }
    }

    @Test
    void unchangedHeartbeatRestoresOnlySyntheticCurrentControlAfterRetention() throws Exception
    {
        Path database = mTemporaryFolder.resolve("current-control-heartbeat.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        TrunkedSiteSchema.Channel staleCurrentControl = new TrunkedSiteSchema.Channel(
            null, null, null, 451_000_000L, null, TrunkedSiteSchema.CHANNEL_ROLE_CURRENT_CONTROL, 1_000);
        TrunkedSiteSchema.Channel staleLearnedChannel = new TrunkedSiteSchema.Channel(
            42, null, 1, 452_000_000L, 457_000_000L, TrunkedSiteSchema.CHANNEL_ROLE_TRAFFIC, 1_000);
        TrunkedSiteSchema.Neighbor staleNeighbor = new TrunkedSiteSchema.Neighbor(
            1, 2, 10, 20, 31, 43, 453_000_000L, TrunkedSiteSchema.NEIGHBOR_STATUS_ACTIVE, 1_000);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            TrunkedSiteSchema.upsert(connection, new TrunkedSiteSchema.Snapshot(
                10_000, "active-site", "stable-hash", TrunkedSiteSchema.PROTOCOL_DMR, 1, 2,
                "Metro", "Downtown", null, "DMR", 10, null, 20, null, null, null, null, null,
                null, null, null, 0, null, 451_000_000L, 451_000_000L,
                List.of(staleCurrentControl, staleLearnedChannel), List.of(staleNeighbor)));

            TrunkedSiteSchema.CleanupResult cleanup = TrunkedSiteSchema.deleteOlderThan(connection, 5_000);
            assertEquals(2, cleanup.channelsDeleted());
            assertEquals(1, cleanup.neighborsDeleted());
            assertEquals(0, cleanup.sitesDeleted());

            TrunkedSiteSchema.Channel refreshedCurrentControl = new TrunkedSiteSchema.Channel(
                null, null, null, 451_000_000L, null, TrunkedSiteSchema.CHANNEL_ROLE_CURRENT_CONTROL, 11_000);
            TrunkedSiteSchema.upsert(connection, new TrunkedSiteSchema.Snapshot(
                11_000, "active-site", "stable-hash", TrunkedSiteSchema.PROTOCOL_DMR, 1, 2,
                "Metro", "Downtown", null, "DMR", 10, null, 20, null, null, null, null, null,
                null, null, null, 0, null, 451_000_000L, 451_000_000L,
                List.of(refreshedCurrentControl, staleLearnedChannel), List.of(staleNeighbor)), 5_000);

            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_channel_summary WHERE guid='active-site'"));
            assertEquals(11_000, scalarLong(connection, """
                SELECT last_seen_ms FROM trunked_site_channel_summary
                WHERE guid='active-site' AND channel_number=-1 AND frequency_hz=451000000
                """));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_neighbor_summary WHERE guid='active-site'"));
            assertEquals(11_000, scalarLong(connection,
                "SELECT last_seen_ms FROM trunked_site_snapshot WHERE guid='active-site'"));
        }
    }

    @Test
    void channelFrequencyProvenanceFlagsAccumulateWithoutSchemaChange() throws Exception
    {
        Path database = mTemporaryFolder.resolve("channel-flags.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        int mapped = TrunkedSiteSchema.CHANNEL_ROLE_OBSERVED |
            TrunkedSiteSchema.CHANNEL_ROLE_TRAFFIC |
            TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_FROM_CONFIGURED_MAP;
        int announced = TrunkedSiteSchema.CHANNEL_ROLE_OBSERVED |
            TrunkedSiteSchema.CHANNEL_ROLE_TRAFFIC |
            TrunkedSiteSchema.CHANNEL_ROLE_FREQUENCY_ANNOUNCED_OVER_THE_AIR;

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            TrunkedSiteSchema.upsert(connection, snapshot(1_000L, "hash-map", 7, mapped));
            TrunkedSiteSchema.upsert(connection, snapshot(2_000L, "hash-ota", 7, announced));

            assertEquals(mapped | announced, scalarLong(connection, """
                SELECT role_flags FROM trunked_site_channel_summary WHERE guid='dmr-site'
                """));
            assertEquals(2, scalarLong(connection, """
                SELECT observation_count FROM trunked_site_channel_summary WHERE guid='dmr-site'
                """));
            assertEquals("2", TrunkedSiteSchema.schemaVersion(connection));
        }
    }

    @Test
    void rejectsUnsupportedProtocolBeforeWriting() throws Exception
    {
        Path database = mTemporaryFolder.resolve("protocol.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            TrunkedSiteSchema.Snapshot invalid = new TrunkedSiteSchema.Snapshot(
                1_000L, "bad", "hash", 1, 0, 0, null, "Bad", null, null, null, null, null, null, null,
                null, null, null, null, null, null, 0, null, null, null, List.of(), List.of());
            assertThrows(IllegalArgumentException.class, () -> TrunkedSiteSchema.upsert(connection, invalid));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));
        }
    }

    @Test
    void clearAndResetCascadeToBoundedFacts() throws Exception
    {
        Path database = mTemporaryFolder.resolve("reset.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("INSERT INTO alias(sort_order, name) VALUES (0, 'Administrator Alias')");
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
            TrunkedSiteSchema.upsert(connection, snapshot(1_000L, "hash-1", 7));
            TrunkedSiteSchema.upsert(connection, nxdnSnapshot(1_000L, "nxdn-hash"));
            assertEquals(1, TrunkedSiteSchema.clearSiteStats(connection, "dmr-site"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_channel_summary"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_neighbor_summary"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_snapshot WHERE guid='nxdn-site'"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM alias"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM application_settings"));

            assertEquals(3, TrunkedSiteSchema.resetStats(connection));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_channel_summary"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_neighbor_summary"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM alias"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM application_settings"));
        }
    }

    @Test
    void retentionDeletesStaleChildrenBeforeExpiredSitesAndPreservesConfiguration() throws Exception
    {
        Path database = mTemporaryFolder.resolve("retention.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("INSERT INTO alias(sort_order, name) VALUES (0, 'Administrator Alias')");
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
            statement.executeUpdate("""
                INSERT INTO trunked_site_snapshot (
                    guid, snapshot_hash, protocol_code, variant_code, identity_domain_code,
                    first_seen_ms, last_seen_ms, observation_count
                ) VALUES
                    ('active-dmr', 'active', 3, 1, 2, 1000, 10000, 3),
                    ('active-nxdn', 'active', 4, 2, 4, 1000, 10000, 3),
                    ('abandoned-dmr', 'expired', 3, 1, 2, 1000, 2000, 2),
                    ('abandoned-nxdn', 'expired', 4, 2, 4, 1000, 2000, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_channel_summary (
                    guid, channel_number, inbound_channel_number, timeslot, frequency_hz, role_flags,
                    first_seen_ms, last_seen_ms, observation_count
                ) VALUES
                    ('active-dmr', 1, -1, 1, 451000000, 1, 1000, 2000, 1),
                    ('active-dmr', 2, -1, 1, 452000000, 1, 9000, 9000, 1),
                    ('active-nxdn', 120, 121, -1, 155000000, 1, 1000, 2000, 1),
                    ('active-nxdn', 122, 123, -1, 155012500, 1, 9000, 9000, 1),
                    ('abandoned-dmr', 3, -1, 1, 453000000, 1, 1000, 2000, 1),
                    ('abandoned-nxdn', 120, 121, -1, 155000000, 1, 1000, 2000, 1)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_neighbor_summary (
                    guid, variant_code, identity_domain_code, network_id, system_id, site_id, channel_number,
                    frequency_hz, status_flags, first_seen_ms, last_seen_ms, observation_count
                ) VALUES
                    ('active-dmr', 1, 2, 10, 20, 31, 41, 453000000, 1, 1000, 2000, 1),
                    ('active-dmr', 1, 2, 10, 20, 32, 42, 454000000, 1, 9000, 9000, 1),
                    ('active-nxdn', 2, 4, 7, 8, 10, 122, 155012500, 1, 1000, 2000, 1),
                    ('active-nxdn', 2, 4, 7, 8, 11, 123, 155025000, 1, 9000, 9000, 1),
                    ('abandoned-dmr', 1, 2, 10, 20, 33, 43, 455000000, 1, 1000, 2000, 1),
                    ('abandoned-nxdn', 2, 4, 7, 8, 9, 122, 155012500, 1, 1000, 2000, 1)
                """);

            TrunkedSiteSchema.CleanupResult result = TrunkedSiteSchema.deleteOlderThan(connection, 5_000);
            assertEquals(4, result.channelsDeleted());
            assertEquals(4, result.neighborsDeleted());
            assertEquals(2, result.sitesDeleted());
            assertEquals(10, result.total());

            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM alias"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM configuration_channel"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM application_settings"));
            assertEquals(2, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_snapshot WHERE guid='abandoned-dmr'"));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_snapshot WHERE guid='abandoned-nxdn'"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_channel_summary WHERE guid='active-dmr'"));
            assertEquals(2, scalarLong(connection,
                "SELECT channel_number FROM trunked_site_channel_summary WHERE guid='active-dmr'"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_neighbor_summary WHERE guid='active-dmr'"));
            assertEquals(32, scalarLong(connection,
                "SELECT site_id FROM trunked_site_neighbor_summary WHERE guid='active-dmr'"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_channel_summary WHERE guid='active-nxdn'"));
            assertEquals(122, scalarLong(connection,
                "SELECT channel_number FROM trunked_site_channel_summary WHERE guid='active-nxdn'"));
            assertEquals(1, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_neighbor_summary WHERE guid='active-nxdn'"));
            assertEquals(11, scalarLong(connection,
                "SELECT site_id FROM trunked_site_neighbor_summary WHERE guid='active-nxdn'"));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_channel_summary WHERE guid='abandoned-dmr'"));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_neighbor_summary WHERE guid='abandoned-dmr'"));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_channel_summary WHERE guid='abandoned-nxdn'"));
            assertEquals(0, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_neighbor_summary WHERE guid='abandoned-nxdn'"));
            assertEquals("ok", scalarString(connection, "PRAGMA integrity_check"));
        }
    }

    @Test
    void retentionDrainsMoreThanOneBoundedBatch() throws Exception
    {
        Path database = mTemporaryFolder.resolve("retention-batches.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("""
                INSERT INTO trunked_site_snapshot (
                    guid, snapshot_hash, protocol_code, variant_code, identity_domain_code,
                    first_seen_ms, last_seen_ms, observation_count
                ) VALUES ('active', 'hash', 3, 1, 2, 1, 10000, 1)
                """);
            statement.executeUpdate("""
                WITH RECURSIVE channels(value) AS (
                    VALUES(0) UNION ALL SELECT value + 1 FROM channels WHERE value < 1000
                )
                INSERT INTO trunked_site_channel_summary (
                    guid, channel_number, inbound_channel_number, timeslot, frequency_hz, role_flags,
                    first_seen_ms, last_seen_ms, observation_count
                )
                SELECT 'active', value, -1, -1, 450000000 + value, 0, 1, 1, 1 FROM channels
                """);

            TrunkedSiteSchema.CleanupResult result = TrunkedSiteSchema.deleteOlderThan(connection, 5_000);
            assertEquals(1_001, result.channelsDeleted());
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_channel_summary"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));
        }
    }

    @Test
    void actualChildObservationTimeControlsRetentionAndPreventsExpiredReplay() throws Exception
    {
        Path database = mTemporaryFolder.resolve("child-time.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        TrunkedSiteSchema.Channel channel = new TrunkedSiteSchema.Channel(
            42, null, 1, 451_000_000L, 456_000_000L, 1, 9_000);
        TrunkedSiteSchema.Neighbor neighbor = new TrunkedSiteSchema.Neighbor(
            1, 2, 10, 20, 31, 43, 452_000_000L, 1, 9_000);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            TrunkedSiteSchema.upsert(connection,
                snapshotWithChildren(10_000, "hash-1", channel, neighbor), 5_000);
            TrunkedSiteSchema.upsert(connection,
                snapshotWithChildren(11_000, "hash-2", channel, neighbor), 5_000);

            assertEquals(9_000, scalarLong(connection,
                "SELECT last_seen_ms FROM trunked_site_channel_summary"));
            assertEquals(1, scalarLong(connection,
                "SELECT observation_count FROM trunked_site_channel_summary"));
            assertEquals(9_000, scalarLong(connection,
                "SELECT last_seen_ms FROM trunked_site_neighbor_summary"));
            assertEquals(1, scalarLong(connection,
                "SELECT observation_count FROM trunked_site_neighbor_summary"));

            TrunkedSiteSchema.deleteOlderThan(connection, 9_500);
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_channel_summary"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_neighbor_summary"));
            assertEquals(1, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));

            TrunkedSiteSchema.upsert(connection,
                snapshotWithChildren(12_000, "hash-3", channel, neighbor), 9_500);
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_channel_summary"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_neighbor_summary"));
            assertEquals(12_000, scalarLong(connection,
                "SELECT last_seen_ms FROM trunked_site_snapshot"));
        }
    }

    @Test
    void representativeVolumeLookupsUsePrimaryKeys() throws Exception
    {
        Path database = mTemporaryFolder.resolve("query-plan.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            connection.setAutoCommit(false);
            statement.executeUpdate("""
                WITH RECURSIVE sites(value) AS (
                    VALUES(0) UNION ALL SELECT value + 1 FROM sites WHERE value < 99
                )
                INSERT INTO trunked_site_snapshot (
                    guid, snapshot_hash, protocol_code, variant_code, identity_domain_code,
                    first_seen_ms, last_seen_ms, observation_count
                )
                SELECT printf('site-%03d', value), 'hash', 3, 0, 0, 1, 1, 1 FROM sites
                """);
            statement.executeUpdate("""
                WITH RECURSIVE
                sites(value) AS (
                    VALUES(0) UNION ALL SELECT value + 1 FROM sites WHERE value < 99
                ),
                channels(value) AS (
                    VALUES(0) UNION ALL SELECT value + 1 FROM channels WHERE value < 1023
                )
                INSERT INTO trunked_site_channel_summary (
                    guid, channel_number, inbound_channel_number, timeslot, frequency_hz, role_flags,
                    first_seen_ms, last_seen_ms, observation_count
                )
                SELECT printf('site-%03d', sites.value), channels.value, -1, -1,
                    450000000 + channels.value, 0, 1, 1, 1
                FROM sites CROSS JOIN channels
                """);
            statement.executeUpdate("""
                WITH RECURSIVE
                sites(value) AS (
                    VALUES(0) UNION ALL SELECT value + 1 FROM sites WHERE value < 99
                ),
                neighbors(value) AS (
                    VALUES(0) UNION ALL SELECT value + 1 FROM neighbors WHERE value < 255
                )
                INSERT INTO trunked_site_neighbor_summary (
                    guid, variant_code, identity_domain_code, network_id, system_id, site_id, channel_number,
                    frequency_hz, status_flags, first_seen_ms, last_seen_ms, observation_count
                )
                SELECT printf('site-%03d', sites.value), 0, 0, 1, 1, neighbors.value, -1, -1, 0, 1, 1, 1
                FROM sites CROSS JOIN neighbors
                """);
            connection.commit();

            assertEquals(100, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));
            assertEquals(102_400, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_channel_summary"));
            assertEquals(25_600, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_neighbor_summary"));
            assertIndexedSearch(connection,
                "EXPLAIN QUERY PLAN SELECT * FROM trunked_site_snapshot WHERE guid='site-042'");
            assertIndexedSearch(connection, """
                EXPLAIN QUERY PLAN SELECT * FROM trunked_site_channel_summary
                WHERE guid='site-042' ORDER BY channel_number, timeslot, frequency_hz LIMIT 100
                """);
            assertIndexedSearch(connection, """
                EXPLAIN QUERY PLAN SELECT * FROM trunked_site_neighbor_summary
                WHERE guid='site-042' ORDER BY network_id, system_id, site_id, channel_number LIMIT 100
                """);
            assertIndexedSearch(connection, """
                EXPLAIN QUERY PLAN
                SELECT guid FROM trunked_site_snapshot INDEXED BY idx_trunked_site_snapshot_last_seen
                WHERE last_seen_ms < 2 ORDER BY last_seen_ms, guid LIMIT 1000
                """);
            assertIndexedSearch(connection, """
                EXPLAIN QUERY PLAN
                SELECT guid, channel_number, inbound_channel_number, timeslot, frequency_hz
                FROM trunked_site_channel_summary INDEXED BY idx_trunked_site_channel_last_seen
                WHERE last_seen_ms < 2
                ORDER BY last_seen_ms, guid, channel_number, inbound_channel_number, timeslot, frequency_hz
                LIMIT 1000
                """);
            assertIndexedSearch(connection, """
                EXPLAIN QUERY PLAN
                SELECT guid, variant_code, identity_domain_code, network_id, system_id, site_id,
                    channel_number, frequency_hz
                FROM trunked_site_neighbor_summary INDEXED BY idx_trunked_site_neighbor_last_seen
                WHERE last_seen_ms < 2
                ORDER BY last_seen_ms, guid, variant_code, identity_domain_code, network_id, system_id, site_id,
                    channel_number, frequency_hz
                LIMIT 1000
                """);
        }
    }

    @Test
    void validationDoesNotRepairMissingTables() throws Exception
    {
        Path database = mTemporaryFolder.resolve("missing.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP TABLE trunked_site_neighbor_summary");
        }

        assertThrows(Exception.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertFalse(tableExists(connection, "trunked_site_neighbor_summary"));
        }
    }

    @Test
    void validationDoesNotRepairMissingRetentionIndex() throws Exception
    {
        Path database = mTemporaryFolder.resolve("missing-index.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_trunked_site_channel_last_seen");
        }

        assertThrows(Exception.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            assertFalse(indexExists(connection, "idx_trunked_site_channel_last_seen"));
        }
    }

    @Test
    void validationRejectsRetentionIndexWithoutTimeFirstOrdering() throws Exception
    {
        Path database = mTemporaryFolder.resolve("wrong-index.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DROP INDEX idx_trunked_site_snapshot_last_seen");
            statement.executeUpdate("""
                CREATE INDEX idx_trunked_site_snapshot_last_seen
                ON trunked_site_snapshot(guid, last_seen_ms)
                """);
        }

        assertThrows(Exception.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));
    }

    @Test
    void validationRejectsIncorrectPrimaryKeyAndMissingCascadeForeignKey() throws Exception
    {
        Path database = mTemporaryFolder.resolve("invalid-key.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=OFF");
            statement.executeUpdate("ALTER TABLE trunked_site_neighbor_summary RENAME TO old_neighbor");
            statement.executeUpdate("""
                CREATE TABLE trunked_site_neighbor_summary (
                    guid TEXT NOT NULL,
                    variant_code INTEGER NOT NULL,
                    identity_domain_code INTEGER NOT NULL,
                    network_id INTEGER NOT NULL,
                    system_id INTEGER NOT NULL,
                    site_id INTEGER NOT NULL,
                    channel_number INTEGER NOT NULL,
                    frequency_hz INTEGER NOT NULL,
                    status_flags INTEGER NOT NULL DEFAULT 0,
                    first_seen_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL,
                    observation_count INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(guid, variant_code, network_id, system_id, site_id, channel_number, frequency_hz)
                ) WITHOUT ROWID
                """);
            statement.executeUpdate("DROP TABLE old_neighbor");
        }

        assertThrows(Exception.class, () -> SdrTrunkDatabaseStartup.validateGlobalDatabase(database));
    }

    @Test
    void neighborIdentityDomainIsPartOfThePrimaryKey() throws Exception
    {
        Path database = mTemporaryFolder.resolve("neighbor-domain.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            TrunkedSiteSchema.upsert(connection, snapshot(1_000L, "hash-1", 7));
            statement.executeUpdate("""
                INSERT INTO trunked_site_neighbor_summary (
                    guid, variant_code, identity_domain_code, network_id, system_id, site_id, channel_number,
                    frequency_hz, status_flags, first_seen_ms, last_seen_ms, observation_count
                ) VALUES ('dmr-site', 1, 3, 10, 20, 31, 43, 452000000, 1, 1000, 1000, 1)
                """);
            assertEquals(2, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_neighbor_summary WHERE guid='dmr-site'"));
        }
    }

    @Test
    void lifetimeFactCardinalityRemainsBoundedAcrossChangedSnapshots() throws Exception
    {
        Path database = mTemporaryFolder.resolve("bounded.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        List<TrunkedSiteSchema.Channel> firstChannels = IntStream.range(0, 1_024)
            .mapToObj(value -> new TrunkedSiteSchema.Channel(value, null, null,
                450_000_000L + value, null, 0)).toList();
        List<TrunkedSiteSchema.Neighbor> firstNeighbors = IntStream.range(0, 256)
            .mapToObj(value -> new TrunkedSiteSchema.Neighbor(1, 1, 1, 2, value,
                null, null, 0)).toList();
        List<TrunkedSiteSchema.Channel> secondChannels = IntStream.range(2_000, 3_024)
            .mapToObj(value -> new TrunkedSiteSchema.Channel(value, null, null,
                460_000_000L + value, null, 0)).toList();
        List<TrunkedSiteSchema.Neighbor> secondNeighbors = IntStream.range(2_000, 2_256)
            .mapToObj(value -> new TrunkedSiteSchema.Neighbor(1, 1, 1, 2, value,
                null, null, 0)).toList();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            TrunkedSiteSchema.upsert(connection, boundedSnapshot(1_000L, "first",
                firstChannels, firstNeighbors));
            TrunkedSiteSchema.upsert(connection, boundedSnapshot(2_000L, "second",
                secondChannels, secondNeighbors));
            assertEquals(1_024, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_channel_summary WHERE guid='bounded-site'"));
            assertEquals(256, scalarLong(connection,
                "SELECT COUNT(*) FROM trunked_site_neighbor_summary WHERE guid='bounded-site'"));

            TrunkedSiteSchema.upsert(connection, boundedSnapshot(3_000L, "third",
                List.of(firstChannels.getFirst()), List.of(firstNeighbors.getFirst())));
            assertEquals(2, scalarLong(connection, """
                SELECT observation_count FROM trunked_site_channel_summary
                WHERE guid='bounded-site' AND channel_number=0
                """));
            assertEquals(2, scalarLong(connection, """
                SELECT observation_count FROM trunked_site_neighbor_summary
                WHERE guid='bounded-site' AND site_id=0
                """));
        }
    }

    private static TrunkedSiteSchema.Snapshot snapshot(long observedAt, String hash, int serviceFlags)
    {
        return snapshot(observedAt, hash, serviceFlags, 1);
    }

    private static TrunkedSiteSchema.Snapshot snapshot(long observedAt, String hash, int serviceFlags, int roleFlags)
    {
        return new TrunkedSiteSchema.Snapshot(observedAt, "dmr-site", hash, TrunkedSiteSchema.PROTOCOL_DMR, 1,
            2, "Metro DMR", "Downtown", "Public Safety", "DMR Tier 3", 10, 20, 30, null, 2, null, null, null,
            1, 1, 42, serviceFlags, null, 451_000_000L, 451_000_000L,
            List.of(new TrunkedSiteSchema.Channel(42, null, 1, 451_000_000L, 456_000_000L, roleFlags)),
            List.of(new TrunkedSiteSchema.Neighbor(1, 2, 10, 20, 31, 43, 452_000_000L, 1)));
    }

    private static TrunkedSiteSchema.Snapshot boundedSnapshot(long observedAt, String hash,
                                                               List<TrunkedSiteSchema.Channel> channels,
                                                               List<TrunkedSiteSchema.Neighbor> neighbors)
    {
        return new TrunkedSiteSchema.Snapshot(observedAt, "bounded-site", hash,
            TrunkedSiteSchema.PROTOCOL_DMR, 1, 1, "Metro", "Downtown", null, "DMR",
            1, null, 2, null, null, null, null, null, null, null, null, 0, null,
            450_000_000L, 450_000_000L, channels, neighbors);
    }

    private static TrunkedSiteSchema.Snapshot nxdnSnapshot(long observedAt, String hash)
    {
        return new TrunkedSiteSchema.Snapshot(observedAt, "nxdn-site", hash,
            TrunkedSiteSchema.PROTOCOL_NXDN, 2, 4, "Metro NXDN", "North", null, "NXDN",
            7, 8, 9, 12, null, null, null, null, null, null, null, 0, null,
            155_000_000L, 155_000_000L,
            List.of(new TrunkedSiteSchema.Channel(120, 121, null, 155_000_000L, 160_000_000L, 1)),
            List.of(new TrunkedSiteSchema.Neighbor(2, 4, 7, 8, 10, 122, 155_012_500L, 1)));
    }

    private static TrunkedSiteSchema.Snapshot snapshotWithChildren(long observedAt, String hash,
                                                                    TrunkedSiteSchema.Channel channel,
                                                                    TrunkedSiteSchema.Neighbor neighbor)
    {
        return new TrunkedSiteSchema.Snapshot(observedAt, "timed-site", hash,
            TrunkedSiteSchema.PROTOCOL_DMR, 1, 2, "Metro", "Downtown", null, "DMR",
            10, 20, 30, null, null, null, null, null, null, null, null, 0, null,
            451_000_000L, 451_000_000L, List.of(channel), List.of(neighbor));
    }

    private static void assertIndexedSearch(Connection connection, String sql) throws Exception
    {
        StringBuilder detail = new StringBuilder();

        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            while(resultSet.next())
            {
                detail.append(resultSet.getString("detail")).append('\n');
            }
        }

        String plan = detail.toString().toUpperCase();
        assertTrue(plan.contains("SEARCH"), plan);
        assertFalse(plan.contains("SCAN "), plan);
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

    private static boolean tableExists(Connection connection, String table) throws Exception
    {
        try(var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='table' AND name=?"))
        {
            statement.setString(1, table);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static boolean indexExists(Connection connection, String index) throws Exception
    {
        try(var statement = connection.prepareStatement(
            "SELECT 1 FROM sqlite_master WHERE type='index' AND name=?"))
        {
            statement.setString(1, index);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }
}
