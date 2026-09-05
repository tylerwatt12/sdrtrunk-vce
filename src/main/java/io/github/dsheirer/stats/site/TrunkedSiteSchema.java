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

import io.github.dsheirer.database.SqliteSchemaValidator;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compact retention-bound summaries for DMR and NXDN trunked sites.
 *
 * <p>This is an independent schema subsystem. New databases create it from the single global startup schema owner,
 * while supported existing databases are updated only by the backed-up application migrator.</p>
 */
public final class TrunkedSiteSchema
{
    public static final int PROTOCOL_DMR = 3;
    public static final int PROTOCOL_NXDN = 4;
    public static final int MAXIMUM_CHANNEL_FACTS_PER_SNAPSHOT = 1_024;
    public static final int MAXIMUM_NEIGHBOR_FACTS_PER_SNAPSHOT = 256;
    public static final int RETENTION_DELETE_BATCH_SIZE = 1_000;
    public static final int UNKNOWN = -1;
    public static final int CHANNEL_ROLE_CURRENT_CONTROL = 1;
    public static final int CHANNEL_ROLE_ALTERNATE_CONTROL = 1 << 1;
    public static final int CHANNEL_ROLE_TRAFFIC = 1 << 2;
    public static final int CHANNEL_ROLE_OBSERVED = 1 << 3;
    public static final int CHANNEL_ROLE_FREQUENCY_FROM_CONFIGURED_MAP = 1 << 4;
    public static final int CHANNEL_ROLE_FREQUENCY_ANNOUNCED_OVER_THE_AIR = 1 << 5;
    public static final int NEIGHBOR_STATUS_ACTIVE = 1;
    public static final int NEIGHBOR_STATUS_ISOLATED = 1 << 1;
    static final String SNAPSHOT_LAST_SEEN_INDEX = "idx_trunked_site_snapshot_last_seen";
    static final String CHANNEL_LAST_SEEN_INDEX = "idx_trunked_site_channel_last_seen";
    static final String NEIGHBOR_LAST_SEEN_INDEX = "idx_trunked_site_neighbor_last_seen";

    private static final List<SqliteSchemaValidator.Table> TABLES = List.of(
        new SqliteSchemaValidator.Table("trunked_site_snapshot",
            "channel_id", "snapshot_hash", "protocol_code", "variant_code", "location_category_code",
            "network_id", "system_id",
            "site_id", "ran", "model_code", "brand_code", "mode_code", "channel_type_code", "color_code_ts1",
            "color_code_ts2", "current_repeater", "service_flags", "failure_code", "primary_frequency_hz",
            "current_control_hz", "first_seen_ms", "last_seen_ms", "observation_count"),
        new SqliteSchemaValidator.Table("trunked_site_channel_summary",
            "channel_id", "channel_number", "inbound_channel_number", "timeslot", "frequency_hz", "uplink_hz",
            "role_flags", "first_seen_ms", "last_seen_ms", "observation_count"),
        new SqliteSchemaValidator.Table("trunked_site_neighbor_summary",
            "channel_id", "variant_code", "location_category_code", "network_id", "system_id", "site_id",
            "channel_number", "frequency_hz", "status_flags", "first_seen_ms", "last_seen_ms",
            "observation_count")
    );
    private static final List<String> INDEXES = List.of(
        SNAPSHOT_LAST_SEEN_INDEX, CHANNEL_LAST_SEEN_INDEX, NEIGHBOR_LAST_SEEN_INDEX);

    private TrunkedSiteSchema()
    {
    }

    /**
     * Creates the current schema. This method is only called by the global new-database routine or the bundled
     * Application Migrator while it owns a staged copy.
     */
    public static void create(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(snapshotTableSql());
            statement.executeUpdate(channelSummaryTableSql());
            statement.executeUpdate(neighborSummaryTableSql());
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_trunked_site_snapshot_last_seen
                ON trunked_site_snapshot(last_seen_ms, channel_id)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_trunked_site_channel_last_seen
                ON trunked_site_channel_summary(
                    last_seen_ms, channel_id, channel_number, inbound_channel_number, timeslot, frequency_hz)
                """);
            statement.executeUpdate("""
                CREATE INDEX IF NOT EXISTS idx_trunked_site_neighbor_last_seen
                ON trunked_site_neighbor_summary(
                    last_seen_ms, channel_id, variant_code, location_category_code, network_id, system_id, site_id,
                    channel_number, frequency_hz)
                """);
        }

    }

    public static void validate(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validate(connection, TABLES, INDEXES, List.of(), List.of());
        SqliteSchemaValidator.validateDefinitions(connection, List.of(
            new SqliteSchemaValidator.Definition("table", "trunked_site_snapshot", snapshotTableSql()),
            new SqliteSchemaValidator.Definition("table", "trunked_site_channel_summary", channelSummaryTableSql()),
            new SqliteSchemaValidator.Definition("table", "trunked_site_neighbor_summary",
                neighborSummaryTableSql())));
        validateKeysAndIndexes(connection);
    }

    private static String snapshotTableSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_site_snapshot (
                channel_id INTEGER PRIMARY KEY REFERENCES receiver_channel(id) ON DELETE CASCADE
                    CHECK(typeof(channel_id) = 'integer' AND channel_id > 0),
                snapshot_hash TEXT NOT NULL CHECK(
                    typeof(snapshot_hash) = 'text'
                    AND length(snapshot_hash) = 64 AND snapshot_hash = lower(snapshot_hash)
                    AND snapshot_hash NOT GLOB '*[^0-9a-f]*'
                ),
                protocol_code INTEGER NOT NULL CHECK(typeof(protocol_code) = 'integer' AND protocol_code IN (3, 4)),
                variant_code INTEGER NOT NULL DEFAULT 0 CHECK(typeof(variant_code) = 'integer'),
                location_category_code INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(location_category_code) = 'integer'),
                network_id INTEGER CHECK(network_id IS NULL OR
                    (typeof(network_id) = 'integer' AND network_id >= 0)),
                system_id INTEGER CHECK(system_id IS NULL OR
                    (typeof(system_id) = 'integer' AND system_id >= 0)),
                site_id INTEGER CHECK(site_id IS NULL OR
                    (typeof(site_id) = 'integer' AND site_id >= 0)),
                ran INTEGER CHECK(ran IS NULL OR (typeof(ran) = 'integer' AND ran BETWEEN 0 AND 63)),
                model_code INTEGER CHECK(model_code IS NULL OR typeof(model_code) = 'integer'),
                brand_code INTEGER CHECK(brand_code IS NULL OR typeof(brand_code) = 'integer'),
                mode_code INTEGER CHECK(mode_code IS NULL OR typeof(mode_code) = 'integer'),
                channel_type_code INTEGER CHECK(channel_type_code IS NULL OR typeof(channel_type_code) = 'integer'),
                color_code_ts1 INTEGER CHECK(color_code_ts1 IS NULL OR
                    (typeof(color_code_ts1) = 'integer' AND color_code_ts1 BETWEEN 0 AND 15)),
                color_code_ts2 INTEGER CHECK(color_code_ts2 IS NULL OR
                    (typeof(color_code_ts2) = 'integer' AND color_code_ts2 BETWEEN 0 AND 15)),
                current_repeater INTEGER CHECK(current_repeater IS NULL OR
                    (typeof(current_repeater) = 'integer' AND current_repeater >= 0)),
                service_flags INTEGER NOT NULL DEFAULT 0 CHECK(typeof(service_flags) = 'integer'),
                failure_code INTEGER CHECK(failure_code IS NULL OR
                    (typeof(failure_code) = 'integer' AND failure_code >= 0)),
                primary_frequency_hz INTEGER CHECK(primary_frequency_hz IS NULL OR
                    (typeof(primary_frequency_hz) = 'integer' AND primary_frequency_hz > 0)),
                current_control_hz INTEGER CHECK(current_control_hz IS NULL OR
                    (typeof(current_control_hz) = 'integer' AND current_control_hz > 0)),
                first_seen_ms INTEGER NOT NULL CHECK(typeof(first_seen_ms) = 'integer' AND first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(typeof(last_seen_ms) = 'integer' AND last_seen_ms >= first_seen_ms),
                observation_count INTEGER NOT NULL DEFAULT 1
                    CHECK(typeof(observation_count) = 'integer' AND observation_count > 0),
                CHECK(
                    (protocol_code = 3
                        AND variant_code BETWEEN 0 AND 5
                        AND location_category_code = 0
                        AND system_id IS NULL AND ran IS NULL
                        AND (model_code IS NULL OR model_code BETWEEN 1 AND 4)
                        AND (brand_code IS NULL OR brand_code BETWEEN 1 AND 5)
                        AND (mode_code IS NULL OR mode_code BETWEEN 1 AND 2)
                        AND (channel_type_code IS NULL OR channel_type_code BETWEEN 1 AND 2)
                        AND current_repeater IS NULL AND service_flags = 0 AND failure_code IS NULL)
                    OR
                    (protocol_code = 4
                        AND variant_code BETWEEN 0 AND 2
                        AND location_category_code BETWEEN 0 AND 5
                        AND model_code IS NULL AND brand_code IS NULL AND channel_type_code IS NULL
                        AND color_code_ts1 IS NULL AND color_code_ts2 IS NULL
                        AND (mode_code IS NULL OR mode_code BETWEEN 1 AND 3)
                        AND service_flags BETWEEN 0 AND 65520 AND (service_flags & 15) = 0)
                )
            )
            """;
    }

    private static String channelSummaryTableSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_site_channel_summary (
                channel_id INTEGER NOT NULL CHECK(typeof(channel_id) = 'integer' AND channel_id > 0),
                channel_number INTEGER NOT NULL CHECK(typeof(channel_number) = 'integer' AND channel_number >= -1),
                inbound_channel_number INTEGER NOT NULL
                    CHECK(typeof(inbound_channel_number) = 'integer' AND inbound_channel_number >= -1),
                timeslot INTEGER NOT NULL CHECK(typeof(timeslot) = 'integer' AND timeslot IN (-1, 1, 2)),
                frequency_hz INTEGER NOT NULL
                    CHECK(typeof(frequency_hz) = 'integer' AND (frequency_hz = -1 OR frequency_hz > 0)),
                uplink_hz INTEGER CHECK(uplink_hz IS NULL OR
                    (typeof(uplink_hz) = 'integer' AND uplink_hz > 0)),
                role_flags INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(role_flags) = 'integer' AND role_flags BETWEEN 0 AND 63),
                first_seen_ms INTEGER NOT NULL CHECK(typeof(first_seen_ms) = 'integer' AND first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(typeof(last_seen_ms) = 'integer' AND last_seen_ms >= first_seen_ms),
                observation_count INTEGER NOT NULL DEFAULT 1
                    CHECK(typeof(observation_count) = 'integer' AND observation_count > 0),
                PRIMARY KEY(channel_id, channel_number, inbound_channel_number, timeslot, frequency_hz),
                FOREIGN KEY(channel_id) REFERENCES trunked_site_snapshot(channel_id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """;
    }

    private static String neighborSummaryTableSql()
    {
        return """
            CREATE TABLE IF NOT EXISTS trunked_site_neighbor_summary (
                channel_id INTEGER NOT NULL CHECK(typeof(channel_id) = 'integer' AND channel_id > 0),
                variant_code INTEGER NOT NULL
                    CHECK(typeof(variant_code) = 'integer' AND variant_code BETWEEN 0 AND 5),
                location_category_code INTEGER NOT NULL
                    CHECK(typeof(location_category_code) = 'integer' AND location_category_code BETWEEN 0 AND 5),
                network_id INTEGER NOT NULL CHECK(typeof(network_id) = 'integer' AND network_id >= -1),
                system_id INTEGER NOT NULL CHECK(typeof(system_id) = 'integer' AND system_id >= -1),
                site_id INTEGER NOT NULL CHECK(typeof(site_id) = 'integer' AND site_id >= -1),
                channel_number INTEGER NOT NULL CHECK(typeof(channel_number) = 'integer' AND channel_number >= -1),
                frequency_hz INTEGER NOT NULL
                    CHECK(typeof(frequency_hz) = 'integer' AND (frequency_hz = -1 OR frequency_hz > 0)),
                status_flags INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(status_flags) = 'integer' AND status_flags BETWEEN 0 AND 3),
                first_seen_ms INTEGER NOT NULL CHECK(typeof(first_seen_ms) = 'integer' AND first_seen_ms > 0),
                last_seen_ms INTEGER NOT NULL CHECK(typeof(last_seen_ms) = 'integer' AND last_seen_ms >= first_seen_ms),
                observation_count INTEGER NOT NULL DEFAULT 1
                    CHECK(typeof(observation_count) = 'integer' AND observation_count > 0),
                PRIMARY KEY(channel_id, variant_code, location_category_code, network_id, system_id, site_id,
                    channel_number, frequency_hz),
                FOREIGN KEY(channel_id) REFERENCES trunked_site_snapshot(channel_id) ON DELETE CASCADE
            ) WITHOUT ROWID
            """;
    }

    private static void validateKeysAndIndexes(Connection connection) throws SQLException
    {
        validateKeysAndForeignKeys(connection);
        validateIndex(connection, SNAPSHOT_LAST_SEEN_INDEX, List.of("last_seen_ms", "channel_id"));
        validateIndex(connection, CHANNEL_LAST_SEEN_INDEX,
            List.of("last_seen_ms", "channel_id", "channel_number", "inbound_channel_number", "timeslot",
                "frequency_hz"));
        validateIndex(connection, NEIGHBOR_LAST_SEEN_INDEX,
            List.of("last_seen_ms", "channel_id", "variant_code", "location_category_code", "network_id", "system_id",
                "site_id", "channel_number", "frequency_hz"));
    }

    private static void validateKeysAndForeignKeys(Connection connection) throws SQLException
    {
        validatePrimaryKey(connection, "trunked_site_snapshot", List.of("channel_id"));
        validatePrimaryKey(connection, "trunked_site_channel_summary",
            List.of("channel_id", "channel_number", "inbound_channel_number", "timeslot", "frequency_hz"));
        validatePrimaryKey(connection, "trunked_site_neighbor_summary",
            List.of("channel_id", "variant_code", "location_category_code", "network_id", "system_id", "site_id",
                "channel_number", "frequency_hz"));
        validateChannelForeignKey(connection, "trunked_site_snapshot", "receiver_channel");
        validateChannelForeignKey(connection, "trunked_site_channel_summary", "trunked_site_snapshot");
        validateChannelForeignKey(connection, "trunked_site_neighbor_summary", "trunked_site_snapshot");
    }

    /**
     * Updates one compact site summary. Learned child facts are only touched when the publisher's stable snapshot
     * hash changes. Each liveness heartbeat refreshes only channels identified as current controls so active receiver
     * state cannot expire while learned traffic, alternate-control, and neighbor evidence ages normally.
     */
    public static boolean upsert(Connection connection, Snapshot snapshot) throws SQLException
    {
        return upsert(connection, snapshot, Long.MIN_VALUE);
    }

    /**
     * Updates one compact site summary while refusing to replay cumulative child facts that had already expired.
     *
     * @param childRetentionCutoffEpochMilliseconds child facts observed before this time are not inserted or updated
     * @return true when the snapshot was accepted, or false when an older snapshot was ignored
     */
    public static boolean upsert(Connection connection, Snapshot snapshot,
                                 long childRetentionCutoffEpochMilliseconds)
        throws SQLException
    {
        requireValid(snapshot);
        int receiverChannelId = receiverChannelId(connection, snapshot.configurationId());
        SiteState previous = siteState(connection, receiverChannelId);

        if(previous != null && snapshot.observedAtEpochMilliseconds() < previous.lastSeenEpochMilliseconds())
        {
            return false;
        }

        boolean classificationChanged = generationChanged(previous, snapshot);
        upsertSite(connection, receiverChannelId, snapshot);

        if(classificationChanged)
        {
            clearProtocolSpecificChildren(connection, receiverChannelId);
        }

        if(previous != null && !classificationChanged &&
            Objects.equals(previous.snapshotHash(), snapshot.snapshotHash()))
        {
            confirmCurrentControls(connection, receiverChannelId, snapshot, childRetentionCutoffEpochMilliseconds);
            reconcileProvisionalChannels(connection, receiverChannelId, snapshot);
            return true;
        }

        clearMutableChannelRoles(connection, receiverChannelId);
        Set<ChannelKey> channelKeys = channelKeys(connection, receiverChannelId);
        int channelLimit = Math.min(snapshot.channels().size(), MAXIMUM_CHANNEL_FACTS_PER_SNAPSHOT);

        for(int x = 0; x < channelLimit; x++)
        {
            Channel channel = snapshot.channels().get(x);

            if(channel != null)
            {
                ChannelKey key = ChannelKey.from(channel);
                long childObservationTime = observationTime(channel.observedAtEpochMilliseconds(),
                    snapshot.observedAtEpochMilliseconds());

                if(childObservationTime >= childRetentionCutoffEpochMilliseconds &&
                    (channelKeys.contains(key) || channelKeys.size() < MAXIMUM_CHANNEL_FACTS_PER_SNAPSHOT))
                {
                    upsertChannel(connection, receiverChannelId, childObservationTime, channel);
                    channelKeys.add(key);
                }
            }
        }

        confirmCurrentControls(connection, receiverChannelId, snapshot, childRetentionCutoffEpochMilliseconds);
        reconcileProvisionalChannels(connection, receiverChannelId, snapshot);
        Set<NeighborKey> neighborKeys = neighborKeys(connection, receiverChannelId);
        int neighborLimit = Math.min(snapshot.neighbors().size(), MAXIMUM_NEIGHBOR_FACTS_PER_SNAPSHOT);

        for(int x = 0; x < neighborLimit; x++)
        {
            Neighbor neighbor = snapshot.neighbors().get(x);

            if(neighbor != null)
            {
                NeighborKey key = NeighborKey.from(neighbor);
                long childObservationTime = observationTime(neighbor.observedAtEpochMilliseconds(),
                    snapshot.observedAtEpochMilliseconds());

                if(childObservationTime >= childRetentionCutoffEpochMilliseconds &&
                    (neighborKeys.contains(key) || neighborKeys.size() < MAXIMUM_NEIGHBOR_FACTS_PER_SNAPSHOT))
                {
                    upsertNeighbor(connection, receiverChannelId, childObservationTime, neighbor);
                    neighborKeys.add(key);
                }
            }
        }

        return true;
    }

    /**
     * Channel and neighbor identities are meaningful only within their protocol variant and location category. A
     * saved channel can be reconfigured, so retained facts from an incompatible classification must not be merged
     * into the new evidence.
     */
    private static void clearProtocolSpecificChildren(Connection connection, int channelId) throws SQLException
    {
        for(String table: List.of("trunked_site_channel_summary", "trunked_site_neighbor_summary"))
        {
            try(PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM " + table + " WHERE channel_id = ?"))
            {
                statement.setInt(1, channelId);
                statement.executeUpdate();
            }
        }
    }

    /**
     * Refreshes only channels that the latest stable snapshot identifies as current controls. A liveness
     * confirmation advances freshness without inflating the independent-observation counter. Learned traffic,
     * alternate-control, and neighbor facts remain tied to their own observation timestamps.
     */
    private static void confirmCurrentControls(Connection connection, int channelId, Snapshot snapshot,
                                               long childRetentionCutoffEpochMilliseconds)
        throws SQLException
    {
        if(snapshot.observedAtEpochMilliseconds() < childRetentionCutoffEpochMilliseconds)
        {
            return;
        }

        int channelLimit = Math.min(snapshot.channels().size(), MAXIMUM_CHANNEL_FACTS_PER_SNAPSHOT);
        Set<ChannelKey> channelKeys = channelKeys(connection, channelId);

        for(int x = 0; x < channelLimit; x++)
        {
            Channel channel = snapshot.channels().get(x);

            if(channel != null && (channel.roleFlags() & CHANNEL_ROLE_CURRENT_CONTROL) != 0)
            {
                ChannelKey key = ChannelKey.from(channel);

                if(channelKeys.contains(key) || channelKeys.size() < MAXIMUM_CHANNEL_FACTS_PER_SNAPSHOT)
                {
                    confirmChannel(connection, channelId, snapshot.observedAtEpochMilliseconds(), channel);
                    channelKeys.add(key);
                }
            }
        }
    }

    private static void clearMutableChannelRoles(Connection connection, int channelId) throws SQLException
    {
        int mutableRoleFlags = CHANNEL_ROLE_CURRENT_CONTROL | CHANNEL_ROLE_ALTERNATE_CONTROL;

        try(PreparedStatement statement = connection.prepareStatement("""
            UPDATE trunked_site_channel_summary
            SET role_flags = role_flags & ?
            WHERE channel_id = ? AND (role_flags & ?) != 0
            """))
        {
            statement.setInt(1, ~mutableRoleFlags);
            statement.setInt(2, channelId);
            statement.setInt(3, mutableRoleFlags);
            statement.executeUpdate();
        }
    }

    /**
     * A configured control frequency is initially stored without a logical channel or slot. Once the current
     * protocol snapshot resolves that exact frequency, remove only that provisional row. The resolved row has already
     * been inserted or confirmed above, so its current-control role and freshness are preserved. Unresolved
     * frequencies and placeholders for other frequencies remain intact.
     */
    private static void reconcileProvisionalChannels(Connection connection, int channelId, Snapshot snapshot)
        throws SQLException
    {
        Set<Long> resolvedFrequencies = new HashSet<>();
        int channelLimit = Math.min(snapshot.channels().size(), MAXIMUM_CHANNEL_FACTS_PER_SNAPSHOT);

        for(int x = 0; x < channelLimit; x++)
        {
            Channel channel = snapshot.channels().get(x);

            if(channel != null && channel.frequencyHertz() != null && channel.frequencyHertz() > 0 &&
                (channel.channelNumber() != null || channel.inboundChannelNumber() != null ||
                    channel.timeslot() != null))
            {
                resolvedFrequencies.add(channel.frequencyHertz());
            }
        }

        if(resolvedFrequencies.isEmpty())
        {
            return;
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            DELETE FROM trunked_site_channel_summary AS provisional
            WHERE provisional.channel_id = ?
              AND provisional.channel_number = ?
              AND provisional.inbound_channel_number = ?
              AND provisional.timeslot = ?
              AND provisional.frequency_hz = ?
              AND EXISTS (
                  SELECT 1
                  FROM trunked_site_channel_summary AS resolved
                  WHERE resolved.channel_id = provisional.channel_id
                    AND resolved.frequency_hz = provisional.frequency_hz
                    AND (resolved.channel_number != ?
                         OR resolved.inbound_channel_number != ?
                         OR resolved.timeslot != ?)
              )
            """))
        {
            for(Long frequency: resolvedFrequencies)
            {
                statement.setInt(1, channelId);
                statement.setInt(2, UNKNOWN);
                statement.setInt(3, UNKNOWN);
                statement.setInt(4, UNKNOWN);
                statement.setLong(5, frequency);
                statement.setInt(6, UNKNOWN);
                statement.setInt(7, UNKNOWN);
                statement.setInt(8, UNKNOWN);
                statement.addBatch();
            }

            statement.executeBatch();
        }
    }

    public static int resetStats(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            return statement.executeUpdate("DELETE FROM trunked_site_snapshot");
        }
    }

    public static int clearChannelStats(Connection connection, String configurationId) throws SQLException
    {
        if(configurationId == null || configurationId.isBlank())
        {
            return 0;
        }

        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM trunked_site_snapshot WHERE channel_id = " +
                "(SELECT id FROM receiver_channel WHERE configuration_id = ?)"))
        {
            statement.setString(1, configurationId);
            return statement.executeUpdate();
        }
    }

    /**
     * Deletes expired learned facts in bounded, time-indexed batches. Child rows are removed independently before
     * expired site parents, so an active site cannot keep an obsolete channel or neighbor alive indefinitely.
     *
     * <p>The caller owns the transaction and must use a connection with foreign keys enabled so deleting an expired
     * site also removes any remaining descendants.</p>
     */
    public static CleanupResult deleteOlderThan(Connection connection, long cutoffEpochMilliseconds)
        throws SQLException
    {
        int channels = deleteAllBatches(connection, """
            DELETE FROM trunked_site_channel_summary
            WHERE (channel_id, channel_number, inbound_channel_number, timeslot, frequency_hz) IN (
                SELECT channel_id, channel_number, inbound_channel_number, timeslot, frequency_hz
                FROM trunked_site_channel_summary INDEXED BY idx_trunked_site_channel_last_seen
                WHERE last_seen_ms < ?
                ORDER BY last_seen_ms, channel_id, channel_number, inbound_channel_number, timeslot, frequency_hz
                LIMIT ?
            )
            """, cutoffEpochMilliseconds);
        int neighbors = deleteAllBatches(connection, """
            DELETE FROM trunked_site_neighbor_summary
            WHERE (channel_id, variant_code, location_category_code, network_id, system_id, site_id,
                   channel_number, frequency_hz) IN (
                SELECT channel_id, variant_code, location_category_code, network_id, system_id, site_id,
                       channel_number, frequency_hz
                FROM trunked_site_neighbor_summary INDEXED BY idx_trunked_site_neighbor_last_seen
                WHERE last_seen_ms < ?
                ORDER BY last_seen_ms, channel_id, variant_code, location_category_code, network_id, system_id, site_id,
                         channel_number, frequency_hz
                LIMIT ?
            )
            """, cutoffEpochMilliseconds);
        int sites = deleteAllBatches(connection, """
            DELETE FROM trunked_site_snapshot
            WHERE channel_id IN (
                SELECT channel_id
                FROM trunked_site_snapshot INDEXED BY idx_trunked_site_snapshot_last_seen
                WHERE last_seen_ms < ?
                ORDER BY last_seen_ms, channel_id
                LIMIT ?
            )
            """, cutoffEpochMilliseconds);
        return new CleanupResult(channels, neighbors, sites);
    }

    private static void validatePrimaryKey(Connection connection, String table, List<String> expected)
        throws SQLException
    {
        TreeMap<Integer,String> ordered = new TreeMap<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(resultSet.next())
            {
                int position = resultSet.getInt("pk");

                if(position > 0)
                {
                    ordered.put(position, resultSet.getString("name"));
                }
            }
        }

        if(!new ArrayList<>(ordered.values()).equals(expected))
        {
            throw new SQLException("SQLite schema has incorrect primary key for [" + table + "]: " +
                ordered.values());
        }
    }

    private static void validateIndex(Connection connection, String index, List<String> expected) throws SQLException
    {
        List<String> actual = new ArrayList<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA index_info(" + index + ")"))
        {
            while(resultSet.next())
            {
                actual.add(resultSet.getString("name"));
            }
        }

        if(!actual.equals(expected))
        {
            throw new SQLException("SQLite schema has incorrect columns for index [" + index + "]: " + actual);
        }
    }

    private static int deleteAllBatches(Connection connection, String sql, long cutoffEpochMilliseconds)
        throws SQLException
    {
        int total = 0;

        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            int deleted;

            do
            {
                statement.setLong(1, cutoffEpochMilliseconds);
                statement.setInt(2, RETENTION_DELETE_BATCH_SIZE);
                deleted = statement.executeUpdate();
                total = Math.addExact(total, deleted);
            }
            while(deleted > 0);
        }

        return total;
    }

    private static long observationTime(long childObservationTime, long snapshotObservationTime)
    {
        return childObservationTime > 0 ? childObservationTime : snapshotObservationTime;
    }

    private static void validateChannelForeignKey(Connection connection, String table, String parent)
        throws SQLException
    {
        boolean valid = false;

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_list(" + table + ")"))
        {
            while(resultSet.next())
            {
                if(parent.equals(resultSet.getString("table")) &&
                    "channel_id".equals(resultSet.getString("from")) &&
                    ("receiver_channel".equals(parent) ? "id" : "channel_id").equals(resultSet.getString("to")) &&
                    "CASCADE".equalsIgnoreCase(resultSet.getString("on_delete")))
                {
                    valid = true;
                    break;
                }
            }
        }

        if(!valid)
        {
            throw new SQLException("SQLite schema is missing channel cascade foreign key for [" + table + "]");
        }
    }

    private static void requireValid(Snapshot snapshot)
    {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");

        if(!canonicalUuid(snapshot.configurationId()))
        {
            throw new IllegalArgumentException("Trunked site snapshot requires a canonical channel configuration ID");
        }

        if(snapshot.snapshotHash() == null || !snapshot.snapshotHash().matches("[0-9a-f]{64}"))
        {
            throw new IllegalArgumentException("Trunked site snapshot requires a canonical SHA-256 hash");
        }

        if(snapshot.protocolCode() != PROTOCOL_DMR && snapshot.protocolCode() != PROTOCOL_NXDN)
        {
            throw new IllegalArgumentException("Trunked site protocol must be DMR or NXDN");
        }

        if(snapshot.observedAtEpochMilliseconds() <= 0)
        {
            throw new IllegalArgumentException("Trunked site observation time is required");
        }

        if(positiveRequired(snapshot.primaryFrequencyHertz()) || positiveRequired(snapshot.currentControlHertz()) ||
            negative(snapshot.networkId()) || negative(snapshot.systemId()) || negative(snapshot.siteId()) ||
            snapshot.ran() != null && (snapshot.ran() < 0 || snapshot.ran() > 63))
        {
            throw new IllegalArgumentException("Trunked site snapshot contains an invalid identity or frequency");
        }

        if(snapshot.protocolCode() == PROTOCOL_DMR)
        {
            if(snapshot.variantCode() < 0 || snapshot.variantCode() > 5 || snapshot.locationCategoryCode() != 0 ||
                snapshot.systemId() != null || snapshot.ran() != null ||
                outside(snapshot.modelCode(), 1, 4) || outside(snapshot.brandCode(), 1, 5) ||
                outside(snapshot.modeCode(), 1, 2) || outside(snapshot.channelTypeCode(), 1, 2) ||
                outside(snapshot.colorCodeTimeslot1(), 0, 15) || outside(snapshot.colorCodeTimeslot2(), 0, 15) ||
                snapshot.currentRepeater() != null || snapshot.serviceFlags() != 0 || snapshot.failureCode() != null)
            {
                throw new IllegalArgumentException("DMR site snapshot contains fields from another protocol");
            }
        }
        else if(snapshot.variantCode() < 0 || snapshot.variantCode() > 2 ||
            snapshot.locationCategoryCode() < 0 || snapshot.locationCategoryCode() > 5 ||
            snapshot.modelCode() != null || snapshot.brandCode() != null || snapshot.channelTypeCode() != null ||
            snapshot.colorCodeTimeslot1() != null || snapshot.colorCodeTimeslot2() != null ||
            outside(snapshot.modeCode(), 1, 3) || negative(snapshot.currentRepeater()) ||
            snapshot.serviceFlags() < 0 || snapshot.serviceFlags() > 65_520 ||
            (snapshot.serviceFlags() & 0xF) != 0 || negative(snapshot.failureCode()))
        {
            throw new IllegalArgumentException("NXDN site snapshot contains fields from another protocol");
        }

        for(Channel channel: snapshot.channels())
        {
            if(channel == null)
            {
                continue;
            }

            if(negative(channel.channelNumber()) || negative(channel.inboundChannelNumber()) ||
                positiveRequired(channel.frequencyHertz()) || positiveRequired(channel.uplinkHertz()) ||
                channel.roleFlags() < 0 || channel.roleFlags() > 63 ||
                channel.observedAtEpochMilliseconds() < 0 ||
                snapshot.protocolCode() == PROTOCOL_DMR && channel.timeslot() != null &&
                    channel.timeslot() != 1 && channel.timeslot() != 2 ||
                snapshot.protocolCode() == PROTOCOL_NXDN && channel.timeslot() != null)
            {
                throw new IllegalArgumentException("Trunked site channel contains an invalid protocol fact");
            }
        }

        for(Neighbor neighbor: snapshot.neighbors())
        {
            if(neighbor == null)
            {
                continue;
            }

            boolean wrongClassification = snapshot.protocolCode() == PROTOCOL_DMR ?
                neighbor.variantCode() < 0 || neighbor.variantCode() > 5 || neighbor.locationCategoryCode() != 0 :
                neighbor.variantCode() < 0 || neighbor.variantCode() > 2 ||
                    neighbor.locationCategoryCode() < 0 || neighbor.locationCategoryCode() > 5;
            if(wrongClassification || negative(neighbor.networkId()) || negative(neighbor.systemId()) ||
                negative(neighbor.siteId()) || negative(neighbor.channelNumber()) ||
                positiveRequired(neighbor.frequencyHertz()) || neighbor.statusFlags() < 0 ||
                neighbor.statusFlags() > 3 || neighbor.observedAtEpochMilliseconds() < 0)
            {
                throw new IllegalArgumentException("Trunked site neighbor contains an invalid protocol fact");
            }
        }
    }

    private static boolean canonicalUuid(String value)
    {
        return value != null && value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    private static boolean negative(Integer value)
    {
        return value != null && value < 0;
    }

    private static boolean positiveRequired(Long value)
    {
        return value != null && value <= 0;
    }

    private static boolean outside(Integer value, int minimum, int maximum)
    {
        return value != null && (value < minimum || value > maximum);
    }

    private static int receiverChannelId(Connection connection, String configurationId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT id FROM receiver_channel WHERE configuration_id = ?"))
        {
            statement.setString(1, configurationId);
            try(ResultSet resultSet = statement.executeQuery())
            {
                if(resultSet.next())
                {
                    return resultSet.getInt(1);
                }
            }
        }
        throw new SQLException("No receiver channel exists for configuration [" + configurationId + "]");
    }

    private static SiteState siteState(Connection connection, int channelId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            """
            SELECT snapshot_hash, last_seen_ms, protocol_code, variant_code, location_category_code,
                network_id, system_id, site_id, ran
            FROM trunked_site_snapshot
            WHERE channel_id = ?
            """))
        {
            statement.setInt(1, channelId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ?
                    new SiteState(resultSet.getString(1), resultSet.getLong(2), resultSet.getInt(3),
                        resultSet.getInt(4), resultSet.getInt(5), nullableInteger(resultSet, 6),
                        nullableInteger(resultSet, 7), nullableInteger(resultSet, 8),
                        nullableInteger(resultSet, 9)) : null;
            }
        }
    }

    /** True when a newer snapshot would replace the saved channel's decoded site/location generation. */
    public static boolean isGenerationChange(Connection connection, Snapshot snapshot) throws SQLException
    {
        if(snapshot == null || snapshot.configurationId() == null || snapshot.configurationId().isBlank())
        {
            return false;
        }

        int channelId = receiverChannelId(connection, snapshot.configurationId());
        return generationChanged(siteState(connection, channelId), snapshot);
    }

    private static boolean generationChanged(SiteState previous, Snapshot snapshot)
    {
        return previous != null && snapshot != null &&
            (previous.protocolCode() != snapshot.protocolCode() ||
                previous.variantCode() != snapshot.variantCode() ||
                previous.locationCategoryCode() != snapshot.locationCategoryCode() ||
                locationChanged(previous.networkId(), snapshot.networkId()) ||
                locationChanged(previous.systemId(), snapshot.systemId()) ||
                locationChanged(previous.siteId(), snapshot.siteId()) ||
                locationChanged(previous.ran(), snapshot.ran()));
    }

    private static void upsertSite(Connection connection, int channelId, Snapshot snapshot) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_site_snapshot (
                channel_id, snapshot_hash, protocol_code, variant_code, location_category_code,
                network_id, system_id, site_id, ran, model_code,
                brand_code, mode_code, channel_type_code, color_code_ts1, color_code_ts2, current_repeater,
                service_flags, failure_code, primary_frequency_hz, current_control_hz, first_seen_ms, last_seen_ms,
                observation_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT(channel_id) DO UPDATE SET
                snapshot_hash = excluded.snapshot_hash,
                protocol_code = excluded.protocol_code,
                variant_code = excluded.variant_code,
                location_category_code = excluded.location_category_code,
                network_id = excluded.network_id,
                system_id = excluded.system_id,
                site_id = excluded.site_id,
                ran = excluded.ran,
                model_code = excluded.model_code,
                brand_code = excluded.brand_code,
                mode_code = excluded.mode_code,
                channel_type_code = excluded.channel_type_code,
                color_code_ts1 = excluded.color_code_ts1,
                color_code_ts2 = excluded.color_code_ts2,
                current_repeater = excluded.current_repeater,
                service_flags = excluded.service_flags,
                failure_code = excluded.failure_code,
                primary_frequency_hz = excluded.primary_frequency_hz,
                current_control_hz = excluded.current_control_hz,
                first_seen_ms = CASE
                    WHEN trunked_site_snapshot.protocol_code != excluded.protocol_code
                      OR trunked_site_snapshot.variant_code != excluded.variant_code
                      OR trunked_site_snapshot.location_category_code != excluded.location_category_code
                      OR (trunked_site_snapshot.network_id IS NOT NULL AND excluded.network_id IS NOT NULL
                          AND trunked_site_snapshot.network_id != excluded.network_id)
                      OR (trunked_site_snapshot.system_id IS NOT NULL AND excluded.system_id IS NOT NULL
                          AND trunked_site_snapshot.system_id != excluded.system_id)
                      OR (trunked_site_snapshot.site_id IS NOT NULL AND excluded.site_id IS NOT NULL
                          AND trunked_site_snapshot.site_id != excluded.site_id)
                      OR (trunked_site_snapshot.ran IS NOT NULL AND excluded.ran IS NOT NULL
                          AND trunked_site_snapshot.ran != excluded.ran)
                    THEN excluded.first_seen_ms
                    ELSE min(trunked_site_snapshot.first_seen_ms, excluded.first_seen_ms)
                END,
                last_seen_ms = max(trunked_site_snapshot.last_seen_ms, excluded.last_seen_ms),
                observation_count = CASE
                    WHEN trunked_site_snapshot.protocol_code != excluded.protocol_code
                      OR trunked_site_snapshot.variant_code != excluded.variant_code
                      OR trunked_site_snapshot.location_category_code != excluded.location_category_code
                      OR (trunked_site_snapshot.network_id IS NOT NULL AND excluded.network_id IS NOT NULL
                          AND trunked_site_snapshot.network_id != excluded.network_id)
                      OR (trunked_site_snapshot.system_id IS NOT NULL AND excluded.system_id IS NOT NULL
                          AND trunked_site_snapshot.system_id != excluded.system_id)
                      OR (trunked_site_snapshot.site_id IS NOT NULL AND excluded.site_id IS NOT NULL
                          AND trunked_site_snapshot.site_id != excluded.site_id)
                      OR (trunked_site_snapshot.ran IS NOT NULL AND excluded.ran IS NOT NULL
                          AND trunked_site_snapshot.ran != excluded.ran)
                    THEN 1
                    ELSE trunked_site_snapshot.observation_count + 1
                END
            """))
        {
            int parameter = 1;
            statement.setInt(parameter++, channelId);
            statement.setString(parameter++, snapshot.snapshotHash());
            statement.setInt(parameter++, snapshot.protocolCode());
            statement.setInt(parameter++, snapshot.variantCode());
            statement.setInt(parameter++, snapshot.locationCategoryCode());
            setInteger(statement, parameter++, snapshot.networkId());
            setInteger(statement, parameter++, snapshot.systemId());
            setInteger(statement, parameter++, snapshot.siteId());
            setInteger(statement, parameter++, snapshot.ran());
            setInteger(statement, parameter++, snapshot.modelCode());
            setInteger(statement, parameter++, snapshot.brandCode());
            setInteger(statement, parameter++, snapshot.modeCode());
            setInteger(statement, parameter++, snapshot.channelTypeCode());
            setInteger(statement, parameter++, snapshot.colorCodeTimeslot1());
            setInteger(statement, parameter++, snapshot.colorCodeTimeslot2());
            setInteger(statement, parameter++, snapshot.currentRepeater());
            statement.setInt(parameter++, snapshot.serviceFlags());
            setInteger(statement, parameter++, snapshot.failureCode());
            setLong(statement, parameter++, snapshot.primaryFrequencyHertz());
            setLong(statement, parameter++, snapshot.currentControlHertz());
            statement.setLong(parameter++, snapshot.observedAtEpochMilliseconds());
            statement.setLong(parameter, snapshot.observedAtEpochMilliseconds());
            statement.executeUpdate();
        }
    }

    private static void upsertChannel(Connection connection, int channelId, long observedAt, Channel channel)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_site_channel_summary (
                channel_id, channel_number, inbound_channel_number, timeslot, frequency_hz, uplink_hz, role_flags,
                first_seen_ms, last_seen_ms, observation_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT(channel_id, channel_number, inbound_channel_number, timeslot, frequency_hz) DO UPDATE SET
                uplink_hz = CASE
                    WHEN excluded.last_seen_ms > trunked_site_channel_summary.last_seen_ms
                    THEN coalesce(excluded.uplink_hz, trunked_site_channel_summary.uplink_hz)
                    ELSE trunked_site_channel_summary.uplink_hz
                END,
                role_flags = trunked_site_channel_summary.role_flags | excluded.role_flags,
                first_seen_ms = min(trunked_site_channel_summary.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(trunked_site_channel_summary.last_seen_ms, excluded.last_seen_ms),
                observation_count = trunked_site_channel_summary.observation_count +
                    CASE WHEN excluded.last_seen_ms > trunked_site_channel_summary.last_seen_ms THEN 1 ELSE 0 END
            """))
        {
            statement.setInt(1, channelId);
            statement.setInt(2, known(channel.channelNumber()));
            statement.setInt(3, known(channel.inboundChannelNumber()));
            statement.setInt(4, known(channel.timeslot()));
            statement.setLong(5, known(channel.frequencyHertz()));
            setLong(statement, 6, channel.uplinkHertz());
            statement.setInt(7, channel.roleFlags());
            statement.setLong(8, observedAt);
            statement.setLong(9, observedAt);
            statement.executeUpdate();
        }
    }

    private static void confirmChannel(Connection connection, int channelId, long confirmedAt, Channel channel)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_site_channel_summary (
                channel_id, channel_number, inbound_channel_number, timeslot, frequency_hz, uplink_hz, role_flags,
                first_seen_ms, last_seen_ms, observation_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT(channel_id, channel_number, inbound_channel_number, timeslot, frequency_hz) DO UPDATE SET
                uplink_hz = coalesce(excluded.uplink_hz, trunked_site_channel_summary.uplink_hz),
                role_flags = trunked_site_channel_summary.role_flags | excluded.role_flags,
                last_seen_ms = max(trunked_site_channel_summary.last_seen_ms, excluded.last_seen_ms)
            """))
        {
            statement.setInt(1, channelId);
            statement.setInt(2, known(channel.channelNumber()));
            statement.setInt(3, known(channel.inboundChannelNumber()));
            statement.setInt(4, known(channel.timeslot()));
            statement.setLong(5, known(channel.frequencyHertz()));
            setLong(statement, 6, channel.uplinkHertz());
            statement.setInt(7, channel.roleFlags());
            statement.setLong(8, confirmedAt);
            statement.setLong(9, confirmedAt);
            statement.executeUpdate();
        }
    }

    private static void upsertNeighbor(Connection connection, int channelId, long observedAt, Neighbor neighbor)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_site_neighbor_summary (
                channel_id, variant_code, location_category_code, network_id, system_id, site_id, channel_number,
                frequency_hz, status_flags, first_seen_ms, last_seen_ms, observation_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT(channel_id, variant_code, location_category_code, network_id, system_id, site_id, channel_number,
                frequency_hz)
            DO UPDATE SET
                status_flags = trunked_site_neighbor_summary.status_flags | excluded.status_flags,
                first_seen_ms = min(trunked_site_neighbor_summary.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(trunked_site_neighbor_summary.last_seen_ms, excluded.last_seen_ms),
                observation_count = trunked_site_neighbor_summary.observation_count +
                    CASE WHEN excluded.last_seen_ms > trunked_site_neighbor_summary.last_seen_ms THEN 1 ELSE 0 END
            """))
        {
            statement.setInt(1, channelId);
            statement.setInt(2, neighbor.variantCode());
            statement.setInt(3, neighbor.locationCategoryCode());
            statement.setInt(4, known(neighbor.networkId()));
            statement.setInt(5, known(neighbor.systemId()));
            statement.setInt(6, known(neighbor.siteId()));
            statement.setInt(7, known(neighbor.channelNumber()));
            statement.setLong(8, known(neighbor.frequencyHertz()));
            statement.setInt(9, neighbor.statusFlags());
            statement.setLong(10, observedAt);
            statement.setLong(11, observedAt);
            statement.executeUpdate();
        }
    }

    private static Set<ChannelKey> channelKeys(Connection connection, int channelId) throws SQLException
    {
        Set<ChannelKey> keys = new HashSet<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT channel_number, inbound_channel_number, timeslot, frequency_hz
            FROM trunked_site_channel_summary WHERE channel_id = ?
            """))
        {
            statement.setInt(1, channelId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    keys.add(new ChannelKey(resultSet.getInt(1), resultSet.getInt(2), resultSet.getInt(3),
                        resultSet.getLong(4)));
                }
            }
        }

        return keys;
    }

    private static Set<NeighborKey> neighborKeys(Connection connection, int channelId) throws SQLException
    {
        Set<NeighborKey> keys = new HashSet<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT variant_code, location_category_code, network_id, system_id, site_id, channel_number,
                frequency_hz
            FROM trunked_site_neighbor_summary WHERE channel_id = ?
            """))
        {
            statement.setInt(1, channelId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    keys.add(new NeighborKey(resultSet.getInt(1), resultSet.getInt(2), resultSet.getInt(3),
                        resultSet.getInt(4), resultSet.getInt(5), resultSet.getInt(6), resultSet.getLong(7)));
                }
            }
        }

        return keys;
    }

    private static int known(Integer value)
    {
        return value != null ? value : UNKNOWN;
    }

    private static long known(Long value)
    {
        return value != null ? value : UNKNOWN;
    }

    private static Integer nullableInteger(ResultSet resultSet, int column) throws SQLException
    {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static void setInteger(PreparedStatement statement, int parameter, Integer value) throws SQLException
    {
        if(value != null)
        {
            statement.setInt(parameter, value);
        }
        else
        {
            statement.setNull(parameter, java.sql.Types.INTEGER);
        }
    }

    private static void setLong(PreparedStatement statement, int parameter, Long value) throws SQLException
    {
        if(value != null)
        {
            statement.setLong(parameter, value);
        }
        else
        {
            statement.setNull(parameter, java.sql.Types.INTEGER);
        }
    }

    private static void setString(PreparedStatement statement, int parameter, String value) throws SQLException
    {
        if(value != null && !value.isBlank())
        {
            statement.setString(parameter, value);
        }
        else
        {
            statement.setNull(parameter, java.sql.Types.VARCHAR);
        }
    }

    /**
     * Immutable writer record. Integer status fields intentionally keep the database compact and protocol-neutral.
     */
    public record Snapshot(long observedAtEpochMilliseconds, String configurationId, String snapshotHash,
                           int protocolCode, int variantCode, int locationCategoryCode,
                           Integer networkId, Integer systemId, Integer siteId,
                           Integer ran, Integer modelCode, Integer brandCode, Integer modeCode,
                           Integer channelTypeCode, Integer colorCodeTimeslot1, Integer colorCodeTimeslot2,
                           Integer currentRepeater, int serviceFlags, Integer failureCode, Long primaryFrequencyHertz,
                           Long currentControlHertz, List<Channel> channels, List<Neighbor> neighbors)
    {
        public Snapshot
        {
            channels = channels != null ? List.copyOf(channels) : List.of();
            neighbors = neighbors != null ? List.copyOf(neighbors) : List.of();
        }
    }

    public record Channel(Integer channelNumber, Integer inboundChannelNumber, Integer timeslot, Long frequencyHertz,
                          Long uplinkHertz, int roleFlags, long observedAtEpochMilliseconds)
    {
        public Channel(Integer channelNumber, Integer inboundChannelNumber, Integer timeslot, Long frequencyHertz,
                       Long uplinkHertz, int roleFlags)
        {
            this(channelNumber, inboundChannelNumber, timeslot, frequencyHertz, uplinkHertz, roleFlags, 0);
        }
    }

    public record Neighbor(int variantCode, int locationCategoryCode, Integer networkId, Integer systemId,
                           Integer siteId, Integer channelNumber, Long frequencyHertz, int statusFlags,
                           long observedAtEpochMilliseconds)
    {
        public Neighbor(int variantCode, int locationCategoryCode, Integer networkId, Integer systemId,
                        Integer siteId, Integer channelNumber, Long frequencyHertz, int statusFlags)
        {
            this(variantCode, locationCategoryCode, networkId, systemId, siteId, channelNumber, frequencyHertz,
                statusFlags, 0);
        }
    }

    public record CleanupResult(int channelsDeleted, int neighborsDeleted, int sitesDeleted)
    {
        public int total()
        {
            return Math.addExact(Math.addExact(channelsDeleted, neighborsDeleted), sitesDeleted);
        }
    }

    private record ChannelKey(int channelNumber, int inboundChannelNumber, int timeslot, long frequencyHertz)
    {
        private static ChannelKey from(Channel channel)
        {
            return new ChannelKey(known(channel.channelNumber()), known(channel.inboundChannelNumber()),
                known(channel.timeslot()), known(channel.frequencyHertz()));
        }
    }

    private static boolean locationChanged(Integer previous, Integer current)
    {
        return previous != null && current != null && !previous.equals(current);
    }

    private record SiteState(String snapshotHash, long lastSeenEpochMilliseconds, int protocolCode,
                             int variantCode, int locationCategoryCode, Integer networkId, Integer systemId,
                             Integer siteId, Integer ran)
    {
    }

    private record NeighborKey(int variantCode, int locationCategoryCode, int networkId, int systemId, int siteId,
                               int channelNumber, long frequencyHertz)
    {
        private static NeighborKey from(Neighbor neighbor)
        {
            return new NeighborKey(neighbor.variantCode(), neighbor.locationCategoryCode(), known(neighbor.networkId()),
                known(neighbor.systemId()), known(neighbor.siteId()), known(neighbor.channelNumber()),
                known(neighbor.frequencyHertz()));
        }
    }
}
