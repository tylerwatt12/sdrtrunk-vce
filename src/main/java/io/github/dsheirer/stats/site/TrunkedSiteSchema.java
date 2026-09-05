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
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Compact retention-bound summaries for DMR and NXDN trunked sites.
 *
 * <p>These tables are part of the one application database format. New databases create them from the global startup
 * schema owner, while supported existing databases are updated only by the backed-up application migrator.</p>
 */
public final class TrunkedSiteSchema
{
    public static final int PROTOCOL_DMR = 3;
    public static final int PROTOCOL_NXDN = 4;
    public static final int MAXIMUM_CHANNEL_FACTS_PER_SNAPSHOT = 1_024;
    public static final int MAXIMUM_NEIGHBOR_FACTS_PER_SNAPSHOT = 256;
    public static final int UNKNOWN = -1;
    public static final int CHANNEL_ROLE_CURRENT_CONTROL = 1;
    public static final int CHANNEL_ROLE_ALTERNATE_CONTROL = 1 << 1;
    public static final int CHANNEL_ROLE_TRAFFIC = 1 << 2;
    public static final int CHANNEL_ROLE_OBSERVED = 1 << 3;
    public static final int CHANNEL_ROLE_FREQUENCY_FROM_CONFIGURED_MAP = 1 << 4;
    public static final int CHANNEL_ROLE_FREQUENCY_ANNOUNCED_OVER_THE_AIR = 1 << 5;
    public static final int NEIGHBOR_STATUS_ACTIVE = 1;
    public static final int NEIGHBOR_STATUS_ISOLATED = 1 << 1;
    private static final int DMR_VARIANT_TIER_III = 1;
    private static final int NXDN_VARIANT_TYPE_C = 1;
    static final String SNAPSHOT_LAST_SEEN_INDEX = "idx_trunked_site_snapshot_last_seen";
    static final String CHANNEL_LAST_SEEN_INDEX = "idx_trunked_site_channel_last_seen";
    static final String NEIGHBOR_LAST_SEEN_INDEX = "idx_trunked_site_neighbor_last_seen";

    private static final List<SqliteSchemaValidator.Table> TABLES = List.of(
        new SqliteSchemaValidator.Table("trunked_site_snapshot",
            "channel_id", "snapshot_hash", "protocol_code", "variant_code", "observed_location_category_code",
            "observed_network_id", "observed_system_id",
            "observed_site_id", "observed_ran", "observed_model_code", "brand_code", "mode_code",
            "channel_type_code", "color_code_ts1",
            "color_code_ts2", "current_repeater", "service_flags", "failure_code", "primary_frequency_hz",
            "current_control_hz", "first_seen_ms", "last_seen_ms", "observation_count"),
        new SqliteSchemaValidator.Table("trunked_site_channel_summary",
            "channel_id", "channel_number", "inbound_channel_number", "timeslot", "frequency_hz", "uplink_hz",
            "role_flags", "first_seen_ms", "last_seen_ms", "observation_count"),
        new SqliteSchemaValidator.Table("trunked_site_neighbor_summary",
            "channel_id", "protocol_code", "variant_code", "dmr_model_code",
            "nxdn_location_category_code", "network_id", "system_id", "site_id", "channel_number",
            "frequency_hz", "status_flags", "first_seen_ms", "last_seen_ms", "observation_count")
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
                    last_seen_ms, channel_id, protocol_code, variant_code, dmr_model_code,
                    nxdn_location_category_code, network_id, system_id, site_id, channel_number, frequency_hz)
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
                observed_location_category_code INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(observed_location_category_code) = 'integer'),
                observed_network_id INTEGER CHECK(observed_network_id IS NULL OR
                    (typeof(observed_network_id) = 'integer' AND observed_network_id >= 0)),
                observed_system_id INTEGER CHECK(observed_system_id IS NULL OR
                    (typeof(observed_system_id) = 'integer' AND observed_system_id >= 0)),
                observed_site_id INTEGER CHECK(observed_site_id IS NULL OR
                    (typeof(observed_site_id) = 'integer' AND observed_site_id >= 0)),
                observed_ran INTEGER CHECK(observed_ran IS NULL OR
                    (typeof(observed_ran) = 'integer' AND observed_ran BETWEEN 0 AND 63)),
                observed_model_code INTEGER CHECK(observed_model_code IS NULL OR
                    typeof(observed_model_code) = 'integer'),
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
                UNIQUE(channel_id, protocol_code),
                CHECK(
                    (protocol_code = 3
                        AND variant_code BETWEEN 0 AND 5
                        AND observed_location_category_code = 0
                        AND observed_system_id IS NULL AND observed_ran IS NULL
                        AND (observed_model_code IS NULL OR observed_model_code BETWEEN 1 AND 4)
                        AND (variant_code != 1 OR observed_model_code IS NULL OR observed_network_id IS NULL OR
                            observed_network_id <= CASE observed_model_code
                                WHEN 1 THEN 511 WHEN 2 THEN 127 WHEN 3 THEN 15 WHEN 4 THEN 3 END)
                        AND (brand_code IS NULL OR brand_code BETWEEN 1 AND 5)
                        AND (mode_code IS NULL OR mode_code BETWEEN 1 AND 2)
                        AND (channel_type_code IS NULL OR channel_type_code BETWEEN 1 AND 2)
                        AND current_repeater IS NULL AND service_flags = 0 AND failure_code IS NULL)
                    OR
                    (protocol_code = 4
                        AND variant_code BETWEEN 0 AND 2
                        AND observed_location_category_code BETWEEN 0 AND 5
                        AND observed_model_code IS NULL AND brand_code IS NULL AND channel_type_code IS NULL
                        AND color_code_ts1 IS NULL AND color_code_ts2 IS NULL
                        AND (mode_code IS NULL OR mode_code BETWEEN 1 AND 3)
                        AND service_flags BETWEEN 0 AND 65520 AND (service_flags & 15) = 0
                        AND (variant_code != 1 OR observed_location_category_code = 0 OR
                            observed_system_id IS NULL OR
                            (observed_location_category_code = 1 AND observed_system_id BETWEEN 1 AND 1022) OR
                            (observed_location_category_code = 2 AND observed_system_id BETWEEN 1 AND 16382) OR
                            (observed_location_category_code = 3 AND observed_system_id BETWEEN 1 AND 131070)))
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
                protocol_code INTEGER NOT NULL
                    CHECK(typeof(protocol_code) = 'integer' AND protocol_code IN (3, 4)),
                variant_code INTEGER NOT NULL
                    CHECK(typeof(variant_code) = 'integer' AND variant_code BETWEEN 0 AND 5),
                dmr_model_code INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(dmr_model_code) = 'integer' AND dmr_model_code BETWEEN 0 AND 4),
                nxdn_location_category_code INTEGER NOT NULL DEFAULT 0
                    CHECK(typeof(nxdn_location_category_code) = 'integer'
                        AND nxdn_location_category_code BETWEEN 0 AND 5),
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
                PRIMARY KEY(channel_id, protocol_code, variant_code, dmr_model_code,
                    nxdn_location_category_code, network_id, system_id, site_id, channel_number, frequency_hz),
                FOREIGN KEY(channel_id, protocol_code)
                    REFERENCES trunked_site_snapshot(channel_id, protocol_code) ON DELETE CASCADE,
                CHECK(
                    (protocol_code = 3 AND variant_code BETWEEN 0 AND 5
                        AND nxdn_location_category_code = 0 AND system_id = -1
                        AND (variant_code = 1 OR dmr_model_code = 0)
                        AND (variant_code != 1 OR dmr_model_code = 0 OR network_id = -1 OR
                            network_id <= CASE dmr_model_code
                                WHEN 1 THEN 511 WHEN 2 THEN 127 WHEN 3 THEN 15 WHEN 4 THEN 3 END))
                    OR
                    (protocol_code = 4 AND variant_code BETWEEN 0 AND 2
                        AND dmr_model_code = 0
                        AND (variant_code != 1 OR nxdn_location_category_code = 0 OR system_id = -1 OR
                            (nxdn_location_category_code = 1 AND system_id BETWEEN 1 AND 1022) OR
                            (nxdn_location_category_code = 2 AND system_id BETWEEN 1 AND 16382) OR
                            (nxdn_location_category_code = 3 AND system_id BETWEEN 1 AND 131070)))
                )
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
            List.of("last_seen_ms", "channel_id", "protocol_code", "variant_code", "dmr_model_code",
                "nxdn_location_category_code", "network_id", "system_id", "site_id", "channel_number",
                "frequency_hz"));
    }

    private static void validateKeysAndForeignKeys(Connection connection) throws SQLException
    {
        validatePrimaryKey(connection, "trunked_site_snapshot", List.of("channel_id"));
        validatePrimaryKey(connection, "trunked_site_channel_summary",
            List.of("channel_id", "channel_number", "inbound_channel_number", "timeslot", "frequency_hz"));
        validatePrimaryKey(connection, "trunked_site_neighbor_summary",
            List.of("channel_id", "protocol_code", "variant_code", "dmr_model_code",
                "nxdn_location_category_code", "network_id", "system_id", "site_id", "channel_number",
                "frequency_hz"));
        validateChannelForeignKey(connection, "trunked_site_snapshot", "receiver_channel");
        validateChannelForeignKey(connection, "trunked_site_channel_summary", "trunked_site_snapshot");
        validateNeighborForeignKey(connection);
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

        if(previous != null && (snapshot.observedAtEpochMilliseconds() < previous.lastSeenEpochMilliseconds() ||
            snapshot.observedAtEpochMilliseconds() == previous.lastSeenEpochMilliseconds() &&
                generationChanged(previous, snapshot)))
        {
            return false;
        }

        boolean classificationChanged = generationChanged(previous, snapshot);
        if(classificationChanged)
        {
            deleteSnapshot(connection, receiverChannelId);
        }
        upsertSite(connection, receiverChannelId, snapshot);

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
                    upsertNeighbor(connection, receiverChannelId, snapshot.protocolCode(), childObservationTime,
                        neighbor);
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
    private static void deleteSnapshot(Connection connection, int channelId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM trunked_site_snapshot WHERE channel_id = ?"))
        {
            statement.setInt(1, channelId);
            statement.executeUpdate();
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

    private static void validateNeighborForeignKey(Connection connection) throws SQLException
    {
        Map<Integer,Set<String>> cascadeColumns = new java.util.HashMap<>();

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(
                "PRAGMA foreign_key_list(trunked_site_neighbor_summary)"))
        {
            while(resultSet.next())
            {
                if("trunked_site_snapshot".equals(resultSet.getString("table")) &&
                    "CASCADE".equalsIgnoreCase(resultSet.getString("on_delete")))
                {
                    cascadeColumns.computeIfAbsent(resultSet.getInt("id"), ignored -> new HashSet<>())
                        .add(resultSet.getString("from") + ":" + resultSet.getString("to"));
                }
            }
        }

        if(cascadeColumns.values().stream().noneMatch(columns -> columns.equals(
            Set.of("channel_id:channel_id", "protocol_code:protocol_code"))))
        {
            throw new SQLException("SQLite schema is missing the protocol-safe neighbor cascade foreign key");
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
                snapshot.currentRepeater() != null || snapshot.serviceFlags() != 0 || snapshot.failureCode() != null ||
                snapshot.variantCode() == DMR_VARIANT_TIER_III && snapshot.modelCode() != null &&
                    snapshot.networkId() != null && snapshot.networkId() > dmrNetworkMaximum(snapshot.modelCode()))
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
            (snapshot.serviceFlags() & 0xF) != 0 || negative(snapshot.failureCode()) ||
            snapshot.variantCode() == NXDN_VARIANT_TYPE_C && snapshot.locationCategoryCode() != 0 &&
                snapshot.systemId() != null &&
                snapshot.systemId() > nxdnSystemMaximum(snapshot.locationCategoryCode()))
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

            boolean wrongClassification;
            if(snapshot.protocolCode() == PROTOCOL_DMR)
            {
                wrongClassification = neighbor.variantCode() < 0 || neighbor.variantCode() > 5 ||
                    neighbor.dmrModelCode() < 0 || neighbor.dmrModelCode() > 4 ||
                    neighbor.nxdnLocationCategoryCode() != 0 || neighbor.systemId() != null ||
                    neighbor.variantCode() != DMR_VARIANT_TIER_III && neighbor.dmrModelCode() != 0 ||
                    neighbor.variantCode() == DMR_VARIANT_TIER_III && neighbor.dmrModelCode() != 0 &&
                        neighbor.networkId() != null &&
                        neighbor.networkId() > dmrNetworkMaximum(neighbor.dmrModelCode());
            }
            else
            {
                wrongClassification = neighbor.variantCode() < 0 || neighbor.variantCode() > 2 ||
                    neighbor.dmrModelCode() != 0 || neighbor.nxdnLocationCategoryCode() < 0 ||
                    neighbor.nxdnLocationCategoryCode() > 5 ||
                    neighbor.variantCode() == NXDN_VARIANT_TYPE_C &&
                        neighbor.nxdnLocationCategoryCode() != 0 && neighbor.systemId() != null &&
                        neighbor.systemId() > nxdnSystemMaximum(neighbor.nxdnLocationCategoryCode());
            }
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
            SELECT snapshot_hash, last_seen_ms, protocol_code, variant_code, observed_location_category_code,
                observed_network_id, observed_system_id, observed_site_id, observed_ran, observed_model_code
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
                        nullableInteger(resultSet, 9), nullableInteger(resultSet, 10)) : null;
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
                knownLocationCategoryChanged(previous.locationCategoryCode(), snapshot.locationCategoryCode()) ||
                locationChanged(previous.modelCode(), snapshot.modelCode()) ||
                locationChanged(previous.networkId(), snapshot.networkId()) ||
                locationChanged(previous.systemId(), snapshot.systemId()) ||
                locationChanged(previous.siteId(), snapshot.siteId()) ||
                locationChanged(previous.ran(), snapshot.ran()));
    }

    private static void upsertSite(Connection connection, int channelId, Snapshot snapshot) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_site_snapshot (
                channel_id, snapshot_hash, protocol_code, variant_code, observed_location_category_code,
                observed_network_id, observed_system_id, observed_site_id, observed_ran, observed_model_code,
                brand_code, mode_code, channel_type_code, color_code_ts1, color_code_ts2, current_repeater,
                service_flags, failure_code, primary_frequency_hz, current_control_hz, first_seen_ms, last_seen_ms,
                observation_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT(channel_id) DO UPDATE SET
                snapshot_hash = excluded.snapshot_hash,
                protocol_code = excluded.protocol_code,
                variant_code = excluded.variant_code,
                observed_location_category_code = CASE
                    WHEN excluded.protocol_code=4 AND excluded.variant_code=1
                    THEN coalesce(nullif(excluded.observed_location_category_code, 0),
                        trunked_site_snapshot.observed_location_category_code)
                    ELSE excluded.observed_location_category_code END,
                observed_network_id = coalesce(excluded.observed_network_id,
                    trunked_site_snapshot.observed_network_id),
                observed_system_id = coalesce(excluded.observed_system_id,
                    trunked_site_snapshot.observed_system_id),
                observed_site_id = coalesce(excluded.observed_site_id,
                    trunked_site_snapshot.observed_site_id),
                observed_ran = coalesce(excluded.observed_ran, trunked_site_snapshot.observed_ran),
                observed_model_code = coalesce(excluded.observed_model_code,
                    trunked_site_snapshot.observed_model_code),
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
                      OR (trunked_site_snapshot.observed_location_category_code != 0
                          AND excluded.observed_location_category_code != 0
                          AND trunked_site_snapshot.observed_location_category_code !=
                              excluded.observed_location_category_code)
                      OR (trunked_site_snapshot.observed_model_code IS NOT NULL
                          AND excluded.observed_model_code IS NOT NULL
                          AND trunked_site_snapshot.observed_model_code != excluded.observed_model_code)
                      OR (trunked_site_snapshot.observed_network_id IS NOT NULL
                          AND excluded.observed_network_id IS NOT NULL
                          AND trunked_site_snapshot.observed_network_id != excluded.observed_network_id)
                      OR (trunked_site_snapshot.observed_system_id IS NOT NULL
                          AND excluded.observed_system_id IS NOT NULL
                          AND trunked_site_snapshot.observed_system_id != excluded.observed_system_id)
                      OR (trunked_site_snapshot.observed_site_id IS NOT NULL
                          AND excluded.observed_site_id IS NOT NULL
                          AND trunked_site_snapshot.observed_site_id != excluded.observed_site_id)
                      OR (trunked_site_snapshot.observed_ran IS NOT NULL AND excluded.observed_ran IS NOT NULL
                          AND trunked_site_snapshot.observed_ran != excluded.observed_ran)
                    THEN excluded.first_seen_ms
                    ELSE min(trunked_site_snapshot.first_seen_ms, excluded.first_seen_ms)
                END,
                last_seen_ms = max(trunked_site_snapshot.last_seen_ms, excluded.last_seen_ms),
                observation_count = CASE
                    WHEN trunked_site_snapshot.protocol_code != excluded.protocol_code
                      OR trunked_site_snapshot.variant_code != excluded.variant_code
                      OR (trunked_site_snapshot.observed_location_category_code != 0
                          AND excluded.observed_location_category_code != 0
                          AND trunked_site_snapshot.observed_location_category_code !=
                              excluded.observed_location_category_code)
                      OR (trunked_site_snapshot.observed_model_code IS NOT NULL
                          AND excluded.observed_model_code IS NOT NULL
                          AND trunked_site_snapshot.observed_model_code != excluded.observed_model_code)
                      OR (trunked_site_snapshot.observed_network_id IS NOT NULL
                          AND excluded.observed_network_id IS NOT NULL
                          AND trunked_site_snapshot.observed_network_id != excluded.observed_network_id)
                      OR (trunked_site_snapshot.observed_system_id IS NOT NULL
                          AND excluded.observed_system_id IS NOT NULL
                          AND trunked_site_snapshot.observed_system_id != excluded.observed_system_id)
                      OR (trunked_site_snapshot.observed_site_id IS NOT NULL
                          AND excluded.observed_site_id IS NOT NULL
                          AND trunked_site_snapshot.observed_site_id != excluded.observed_site_id)
                      OR (trunked_site_snapshot.observed_ran IS NOT NULL AND excluded.observed_ran IS NOT NULL
                          AND trunked_site_snapshot.observed_ran != excluded.observed_ran)
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

    private static void upsertNeighbor(Connection connection, int channelId, int protocolCode, long observedAt,
                                       Neighbor neighbor) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_site_neighbor_summary (
                channel_id, protocol_code, variant_code, dmr_model_code, nxdn_location_category_code,
                network_id, system_id, site_id, channel_number, frequency_hz, status_flags,
                first_seen_ms, last_seen_ms, observation_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT(channel_id, protocol_code, variant_code, dmr_model_code, nxdn_location_category_code,
                network_id, system_id, site_id, channel_number, frequency_hz)
            DO UPDATE SET
                status_flags = trunked_site_neighbor_summary.status_flags | excluded.status_flags,
                first_seen_ms = min(trunked_site_neighbor_summary.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(trunked_site_neighbor_summary.last_seen_ms, excluded.last_seen_ms),
                observation_count = trunked_site_neighbor_summary.observation_count +
                    CASE WHEN excluded.last_seen_ms > trunked_site_neighbor_summary.last_seen_ms THEN 1 ELSE 0 END
            """))
        {
            statement.setInt(1, channelId);
            statement.setInt(2, protocolCode);
            statement.setInt(3, neighbor.variantCode());
            statement.setInt(4, neighbor.dmrModelCode());
            statement.setInt(5, neighbor.nxdnLocationCategoryCode());
            statement.setInt(6, known(neighbor.networkId()));
            statement.setInt(7, known(neighbor.systemId()));
            statement.setInt(8, known(neighbor.siteId()));
            statement.setInt(9, known(neighbor.channelNumber()));
            statement.setLong(10, known(neighbor.frequencyHertz()));
            statement.setInt(11, neighbor.statusFlags());
            statement.setLong(12, observedAt);
            statement.setLong(13, observedAt);
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
            SELECT variant_code, dmr_model_code, nxdn_location_category_code, network_id, system_id, site_id,
                channel_number, frequency_hz
            FROM trunked_site_neighbor_summary WHERE channel_id = ?
            """))
        {
            statement.setInt(1, channelId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    keys.add(new NeighborKey(resultSet.getInt(1), resultSet.getInt(2), resultSet.getInt(3),
                        resultSet.getInt(4), resultSet.getInt(5), resultSet.getInt(6), resultSet.getInt(7),
                        resultSet.getLong(8)));
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

    public record Neighbor(int variantCode, int dmrModelCode, int nxdnLocationCategoryCode,
                           Integer networkId, Integer systemId, Integer siteId, Integer channelNumber,
                           Long frequencyHertz, int statusFlags,
                           long observedAtEpochMilliseconds)
    {
        public Neighbor(int variantCode, int dmrModelCode, int nxdnLocationCategoryCode,
                        Integer networkId, Integer systemId, Integer siteId, Integer channelNumber,
                        Long frequencyHertz, int statusFlags)
        {
            this(variantCode, dmrModelCode, nxdnLocationCategoryCode, networkId, systemId, siteId, channelNumber,
                frequencyHertz, statusFlags, 0);
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

    private static boolean knownLocationCategoryChanged(int previous, int current)
    {
        return previous > 0 && current > 0 && previous != current;
    }

    private static int dmrNetworkMaximum(int modelCode)
    {
        return switch(modelCode)
        {
            case 1 -> 511;
            case 2 -> 127;
            case 3 -> 15;
            case 4 -> 3;
            default -> -1;
        };
    }

    private static int nxdnSystemMaximum(int locationCategoryCode)
    {
        return switch(locationCategoryCode)
        {
            case 1 -> 1022;
            case 2 -> 16382;
            case 3 -> 131070;
            default -> -1;
        };
    }

    private record SiteState(String snapshotHash, long lastSeenEpochMilliseconds, int protocolCode,
                             int variantCode, int locationCategoryCode, Integer networkId, Integer systemId,
                             Integer siteId, Integer ran, Integer modelCode)
    {
    }

    private record NeighborKey(int variantCode, int dmrModelCode, int nxdnLocationCategoryCode, int networkId,
                               int systemId, int siteId, int channelNumber, long frequencyHertz)
    {
        private static NeighborKey from(Neighbor neighbor)
        {
            return new NeighborKey(neighbor.variantCode(), neighbor.dmrModelCode(),
                neighbor.nxdnLocationCategoryCode(), known(neighbor.networkId()), known(neighbor.systemId()),
                known(neighbor.siteId()), known(neighbor.channelNumber()), known(neighbor.frequencyHertz()));
        }
    }
}
