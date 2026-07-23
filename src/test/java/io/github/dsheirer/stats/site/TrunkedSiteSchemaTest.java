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
            assertEquals("1", TrunkedSiteSchema.schemaVersion(connection));
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
            TrunkedSiteSchema.upsert(connection, snapshot(1_000L, "hash-1", 7));
            assertEquals(1, TrunkedSiteSchema.clearSiteStats(connection, "dmr-site"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_channel_summary"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_neighbor_summary"));

            TrunkedSiteSchema.upsert(connection, snapshot(2_000L, "hash-2", 7));
            assertEquals(3, TrunkedSiteSchema.resetStats(connection));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_channel_summary"));
            assertEquals(0, scalarLong(connection, "SELECT COUNT(*) FROM trunked_site_neighbor_summary"));
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
        return new TrunkedSiteSchema.Snapshot(observedAt, "dmr-site", hash, TrunkedSiteSchema.PROTOCOL_DMR, 1,
            2, "Metro DMR", "Downtown", "Public Safety", "DMR Tier 3", 10, 20, 30, null, 2, null, null, null,
            1, 1, 42, serviceFlags, null, 451_000_000L, 451_000_000L,
            List.of(new TrunkedSiteSchema.Channel(42, null, 1, 451_000_000L, 456_000_000L, 1)),
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
}
