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

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
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
 * Compact current/lifetime summaries for DMR and NXDN trunked sites.
 *
 * <p>This is an independent schema subsystem. New databases create it from the single global startup schema owner,
 * while existing databases must be upgraded with the explicit staged-copy migration helper.</p>
 */
public final class TrunkedSiteSchema
{
    public static final int SCHEMA_VERSION = 1;
    public static final String SCHEMA_VERSION_KEY = "trunked_site_schema_version";
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

    private static final List<SqliteSchemaValidator.Table> TABLES = List.of(
        new SqliteSchemaValidator.Table("trunked_site_snapshot",
            "guid", "snapshot_hash", "protocol_code", "variant_code", "identity_domain_code",
            "configured_system", "channel_name", "alias_list_name", "decoder", "network_id", "system_id",
            "site_id", "ran", "model_code", "brand_code", "mode_code", "channel_type_code", "color_code_ts1",
            "color_code_ts2", "current_repeater", "service_flags", "failure_code", "primary_frequency_hz",
            "current_control_hz", "first_seen_ms", "last_seen_ms", "observation_count"),
        new SqliteSchemaValidator.Table("trunked_site_channel_summary",
            "guid", "channel_number", "inbound_channel_number", "timeslot", "frequency_hz", "uplink_hz",
            "role_flags", "first_seen_ms", "last_seen_ms", "observation_count"),
        new SqliteSchemaValidator.Table("trunked_site_neighbor_summary",
            "guid", "variant_code", "identity_domain_code", "network_id", "system_id", "site_id",
            "channel_number", "frequency_hz", "status_flags", "first_seen_ms", "last_seen_ms",
            "observation_count")
    );

    private TrunkedSiteSchema()
    {
    }

    /**
     * Creates the current schema. This method is only called by the global new-database routine or the explicit
     * staged-copy migration helper.
     */
    public static void create(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS trunked_site_snapshot (
                    guid TEXT PRIMARY KEY,
                    snapshot_hash TEXT NOT NULL,
                    protocol_code INTEGER NOT NULL,
                    variant_code INTEGER NOT NULL DEFAULT 0,
                    identity_domain_code INTEGER NOT NULL DEFAULT 0,
                    configured_system TEXT,
                    channel_name TEXT,
                    alias_list_name TEXT,
                    decoder TEXT,
                    network_id INTEGER,
                    system_id INTEGER,
                    site_id INTEGER,
                    ran INTEGER,
                    model_code INTEGER,
                    brand_code INTEGER,
                    mode_code INTEGER,
                    channel_type_code INTEGER,
                    color_code_ts1 INTEGER,
                    color_code_ts2 INTEGER,
                    current_repeater INTEGER,
                    service_flags INTEGER,
                    failure_code INTEGER,
                    primary_frequency_hz INTEGER,
                    current_control_hz INTEGER,
                    first_seen_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL,
                    observation_count INTEGER NOT NULL DEFAULT 1,
                    CHECK(protocol_code IN (3, 4)),
                    CHECK(last_seen_ms >= first_seen_ms),
                    CHECK(observation_count > 0)
                )
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS trunked_site_channel_summary (
                    guid TEXT NOT NULL,
                    channel_number INTEGER NOT NULL,
                    inbound_channel_number INTEGER NOT NULL,
                    timeslot INTEGER NOT NULL,
                    frequency_hz INTEGER NOT NULL,
                    uplink_hz INTEGER,
                    role_flags INTEGER NOT NULL DEFAULT 0,
                    first_seen_ms INTEGER NOT NULL,
                    last_seen_ms INTEGER NOT NULL,
                    observation_count INTEGER NOT NULL DEFAULT 1,
                    PRIMARY KEY(guid, channel_number, inbound_channel_number, timeslot, frequency_hz),
                    FOREIGN KEY(guid) REFERENCES trunked_site_snapshot(guid) ON DELETE CASCADE,
                    CHECK(last_seen_ms >= first_seen_ms),
                    CHECK(observation_count > 0)
                ) WITHOUT ROWID
                """);
            statement.executeUpdate("""
                CREATE TABLE IF NOT EXISTS trunked_site_neighbor_summary (
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
                    PRIMARY KEY(guid, variant_code, identity_domain_code, network_id, system_id, site_id,
                        channel_number, frequency_hz),
                    FOREIGN KEY(guid) REFERENCES trunked_site_snapshot(guid) ON DELETE CASCADE,
                    CHECK(last_seen_ms >= first_seen_ms),
                    CHECK(observation_count > 0)
                ) WITHOUT ROWID
                """);
        }

        SdrTrunkDatabaseStartup.setMetadata(connection, SCHEMA_VERSION_KEY, Integer.toString(SCHEMA_VERSION));
    }

    public static void validate(Connection connection) throws SQLException
    {
        SqliteSchemaValidator.validate(connection, TABLES, List.of(), List.of(),
            List.of(new SqliteSchemaValidator.Metadata(SCHEMA_VERSION_KEY, Integer.toString(SCHEMA_VERSION))));
        validatePrimaryKey(connection, "trunked_site_snapshot", List.of("guid"));
        validatePrimaryKey(connection, "trunked_site_channel_summary",
            List.of("guid", "channel_number", "inbound_channel_number", "timeslot", "frequency_hz"));
        validatePrimaryKey(connection, "trunked_site_neighbor_summary",
            List.of("guid", "variant_code", "identity_domain_code", "network_id", "system_id", "site_id",
                "channel_number", "frequency_hz"));
        validateGuidForeignKey(connection, "trunked_site_channel_summary");
        validateGuidForeignKey(connection, "trunked_site_neighbor_summary");
    }

    /**
     * Updates one compact site summary. Child facts are only touched when the publisher's stable snapshot hash
     * changes; a liveness heartbeat therefore performs one bounded row update.
     */
    public static void upsert(Connection connection, Snapshot snapshot) throws SQLException
    {
        requireValid(snapshot);
        String previousHash = snapshotHash(connection, snapshot.guid());
        upsertSite(connection, snapshot);

        if(Objects.equals(previousHash, snapshot.snapshotHash()))
        {
            return;
        }

        Set<ChannelKey> channelKeys = channelKeys(connection, snapshot.guid());
        int channelLimit = Math.min(snapshot.channels().size(), MAXIMUM_CHANNEL_FACTS_PER_SNAPSHOT);

        for(int x = 0; x < channelLimit; x++)
        {
            Channel channel = snapshot.channels().get(x);

            if(channel != null)
            {
                ChannelKey key = ChannelKey.from(channel);

                if(channelKeys.contains(key) || channelKeys.size() < MAXIMUM_CHANNEL_FACTS_PER_SNAPSHOT)
                {
                    upsertChannel(connection, snapshot.guid(), snapshot.observedAtEpochMilliseconds(), channel);
                    channelKeys.add(key);
                }
            }
        }

        Set<NeighborKey> neighborKeys = neighborKeys(connection, snapshot.guid());
        int neighborLimit = Math.min(snapshot.neighbors().size(), MAXIMUM_NEIGHBOR_FACTS_PER_SNAPSHOT);

        for(int x = 0; x < neighborLimit; x++)
        {
            Neighbor neighbor = snapshot.neighbors().get(x);

            if(neighbor != null)
            {
                NeighborKey key = NeighborKey.from(neighbor);

                if(neighborKeys.contains(key) || neighborKeys.size() < MAXIMUM_NEIGHBOR_FACTS_PER_SNAPSHOT)
                {
                    upsertNeighbor(connection, snapshot.guid(), snapshot.observedAtEpochMilliseconds(), neighbor);
                    neighborKeys.add(key);
                }
            }
        }
    }

    public static int resetStats(Connection connection) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            return statement.executeUpdate("DELETE FROM trunked_site_snapshot");
        }
    }

    public static int clearSiteStats(Connection connection, String guid) throws SQLException
    {
        if(guid == null || guid.isBlank())
        {
            return 0;
        }

        try(PreparedStatement statement = connection.prepareStatement(
            "DELETE FROM trunked_site_snapshot WHERE guid = ?"))
        {
            statement.setString(1, guid);
            return statement.executeUpdate();
        }
    }

    public static String schemaVersion(Connection connection) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT value FROM database_metadata WHERE key = ?"))
        {
            statement.setString(1, SCHEMA_VERSION_KEY);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
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

    private static void validateGuidForeignKey(Connection connection, String table) throws SQLException
    {
        boolean valid = false;

        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA foreign_key_list(" + table + ")"))
        {
            while(resultSet.next())
            {
                if("trunked_site_snapshot".equals(resultSet.getString("table")) &&
                    "guid".equals(resultSet.getString("from")) && "guid".equals(resultSet.getString("to")) &&
                    "CASCADE".equalsIgnoreCase(resultSet.getString("on_delete")))
                {
                    valid = true;
                    break;
                }
            }
        }

        if(!valid)
        {
            throw new SQLException("SQLite schema is missing GUID cascade foreign key for [" + table + "]");
        }
    }

    private static void requireValid(Snapshot snapshot)
    {
        Objects.requireNonNull(snapshot, "snapshot cannot be null");

        if(snapshot.guid() == null || snapshot.guid().isBlank())
        {
            throw new IllegalArgumentException("Trunked site snapshot GUID is required");
        }

        if(snapshot.snapshotHash() == null || snapshot.snapshotHash().isBlank())
        {
            throw new IllegalArgumentException("Trunked site snapshot hash is required");
        }

        if(snapshot.protocolCode() != PROTOCOL_DMR && snapshot.protocolCode() != PROTOCOL_NXDN)
        {
            throw new IllegalArgumentException("Trunked site protocol must be DMR or NXDN");
        }

        if(snapshot.observedAtEpochMilliseconds() <= 0)
        {
            throw new IllegalArgumentException("Trunked site observation time is required");
        }
    }

    private static String snapshotHash(Connection connection, String guid) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT snapshot_hash FROM trunked_site_snapshot WHERE guid = ?"))
        {
            statement.setString(1, guid);

            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next() ? resultSet.getString(1) : null;
            }
        }
    }

    private static void upsertSite(Connection connection, Snapshot snapshot) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_site_snapshot (
                guid, snapshot_hash, protocol_code, variant_code, identity_domain_code, configured_system,
                channel_name, alias_list_name, decoder, network_id, system_id, site_id, ran, model_code,
                brand_code, mode_code, channel_type_code, color_code_ts1, color_code_ts2, current_repeater,
                service_flags, failure_code, primary_frequency_hz, current_control_hz, first_seen_ms, last_seen_ms,
                observation_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT(guid) DO UPDATE SET
                snapshot_hash = excluded.snapshot_hash,
                protocol_code = excluded.protocol_code,
                variant_code = excluded.variant_code,
                identity_domain_code = excluded.identity_domain_code,
                configured_system = excluded.configured_system,
                channel_name = excluded.channel_name,
                alias_list_name = excluded.alias_list_name,
                decoder = excluded.decoder,
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
                first_seen_ms = min(trunked_site_snapshot.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(trunked_site_snapshot.last_seen_ms, excluded.last_seen_ms),
                observation_count = trunked_site_snapshot.observation_count + 1
            """))
        {
            int parameter = 1;
            statement.setString(parameter++, snapshot.guid());
            statement.setString(parameter++, snapshot.snapshotHash());
            statement.setInt(parameter++, snapshot.protocolCode());
            statement.setInt(parameter++, snapshot.variantCode());
            statement.setInt(parameter++, snapshot.identityDomainCode());
            setString(statement, parameter++, snapshot.configuredSystem());
            setString(statement, parameter++, snapshot.channelName());
            setString(statement, parameter++, snapshot.aliasListName());
            setString(statement, parameter++, snapshot.decoder());
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

    private static void upsertChannel(Connection connection, String guid, long observedAt, Channel channel)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_site_channel_summary (
                guid, channel_number, inbound_channel_number, timeslot, frequency_hz, uplink_hz, role_flags,
                first_seen_ms, last_seen_ms, observation_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT(guid, channel_number, inbound_channel_number, timeslot, frequency_hz) DO UPDATE SET
                uplink_hz = coalesce(excluded.uplink_hz, trunked_site_channel_summary.uplink_hz),
                role_flags = trunked_site_channel_summary.role_flags | excluded.role_flags,
                first_seen_ms = min(trunked_site_channel_summary.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(trunked_site_channel_summary.last_seen_ms, excluded.last_seen_ms),
                observation_count = trunked_site_channel_summary.observation_count + 1
            """))
        {
            statement.setString(1, guid);
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

    private static void upsertNeighbor(Connection connection, String guid, long observedAt, Neighbor neighbor)
        throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO trunked_site_neighbor_summary (
                guid, variant_code, identity_domain_code, network_id, system_id, site_id, channel_number,
                frequency_hz, status_flags, first_seen_ms, last_seen_ms, observation_count
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1)
            ON CONFLICT(guid, variant_code, identity_domain_code, network_id, system_id, site_id, channel_number,
                frequency_hz)
            DO UPDATE SET
                status_flags = trunked_site_neighbor_summary.status_flags | excluded.status_flags,
                first_seen_ms = min(trunked_site_neighbor_summary.first_seen_ms, excluded.first_seen_ms),
                last_seen_ms = max(trunked_site_neighbor_summary.last_seen_ms, excluded.last_seen_ms),
                observation_count = trunked_site_neighbor_summary.observation_count + 1
            """))
        {
            statement.setString(1, guid);
            statement.setInt(2, neighbor.variantCode());
            statement.setInt(3, neighbor.identityDomainCode());
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

    private static Set<ChannelKey> channelKeys(Connection connection, String guid) throws SQLException
    {
        Set<ChannelKey> keys = new HashSet<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT channel_number, inbound_channel_number, timeslot, frequency_hz
            FROM trunked_site_channel_summary WHERE guid = ?
            """))
        {
            statement.setString(1, guid);

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

    private static Set<NeighborKey> neighborKeys(Connection connection, String guid) throws SQLException
    {
        Set<NeighborKey> keys = new HashSet<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT variant_code, identity_domain_code, network_id, system_id, site_id, channel_number,
                frequency_hz
            FROM trunked_site_neighbor_summary WHERE guid = ?
            """))
        {
            statement.setString(1, guid);

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
    public record Snapshot(long observedAtEpochMilliseconds, String guid, String snapshotHash, int protocolCode,
                           int variantCode, int identityDomainCode, String configuredSystem, String channelName,
                           String aliasListName, String decoder, Integer networkId, Integer systemId, Integer siteId,
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
                          Long uplinkHertz, int roleFlags)
    {
    }

    public record Neighbor(int variantCode, int identityDomainCode, Integer networkId, Integer systemId,
                           Integer siteId, Integer channelNumber, Long frequencyHertz, int statusFlags)
    {
    }

    private record ChannelKey(int channelNumber, int inboundChannelNumber, int timeslot, long frequencyHertz)
    {
        private static ChannelKey from(Channel channel)
        {
            return new ChannelKey(known(channel.channelNumber()), known(channel.inboundChannelNumber()),
                known(channel.timeslot()), known(channel.frequencyHertz()));
        }
    }

    private record NeighborKey(int variantCode, int identityDomainCode, int networkId, int systemId, int siteId,
                               int channelNumber, long frequencyHertz)
    {
        private static NeighborKey from(Neighbor neighbor)
        {
            return new NeighborKey(neighbor.variantCode(), neighbor.identityDomainCode(), known(neighbor.networkId()),
                known(neighbor.systemId()), known(neighbor.siteId()), known(neighbor.channelNumber()),
                known(neighbor.frequencyHertz()));
        }
    }
}
