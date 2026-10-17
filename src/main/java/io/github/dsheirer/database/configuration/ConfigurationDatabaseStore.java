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

package io.github.dsheirer.database.configuration;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * SQLite persistence for active channel and stream configuration.
 */
public class ConfigurationDatabaseStore
{
    /**
     * Channel fields whose sole persisted authority is the relational row. Retired spellings remain on the rejection
     * list so a nonstandard current writer cannot bypass the same one-fact/one-owner rule.
     */
    private static final List<String> CHANNEL_ROW_OWNED_JSON_FIELDS = List.of(
        "configurationId", "system", "site", "name", "aliasListId", "aliasListName", "radioResolveId",
        "radresGuid", "radres_guid", "autoStart", "enabled", "autoStartOrder", "order", "channelType");
    private final Path mDatabasePath;
    private final ObjectMapper mObjectMapper = new ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        .enable(SerializationFeature.INDENT_OUTPUT);

    public ConfigurationDatabaseStore(Path databasePath)
    {
        mDatabasePath = databasePath;
    }

    public Path getDatabasePath()
    {
        return mDatabasePath;
    }

    public ChannelAndBroadcastConfiguration load() throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            return load(connection);
        }
    }

    /** Loads the channel and stream portion of a snapshot from one caller-owned database view. */
    public ChannelAndBroadcastConfiguration load(Connection connection) throws IOException, SQLException
    {
        if(connection == null)
        {
            throw new IllegalArgumentException("Connection cannot be null");
        }
        return new ChannelAndBroadcastConfiguration(loadChannels(connection),
            loadBroadcastConfigurations(connection));
    }

    /**
     * Saves channels and streams using the caller-owned repository transaction so every reference and its Alias List
     * definition becomes visible in one commit.
     *
     * @param connection open connection with auto-commit disabled
     */
    public void replace(Connection connection, ChannelAndBroadcastConfiguration configuration)
        throws IOException, SQLException
    {
        if(connection == null || connection.getAutoCommit())
        {
            throw new IllegalArgumentException("Configuration snapshot writes require a caller-owned transaction");
        }

        if(configuration == null)
        {
            throw new IllegalArgumentException("Channel and broadcast configuration cannot be null");
        }

        synchronizeChannels(connection, configuration.channels());
        synchronizeBroadcastConfigurations(connection, configuration.broadcastConfigurations());
    }

    /** Loads the channel portion of a caller-owned consistent database view. */
    public List<Channel> loadChannels(Connection connection) throws SQLException, IOException
    {
        List<Channel> channels = new ArrayList<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT channel.configuration_id, channel.channel_kind, channel.system_name, channel.site_name,
                   channel.name, channel.alias_list_id, list.name AS alias_list_name, channel.radioresolve_id,
                   channel.auto_start, channel.auto_start_order, channel.decoder_type,
                   channel.address_domain_code, channel.primary_frequency_hz, channel.config_json,
                   current_system.system_key AS current_system_key,
                   current_system.protocol_code AS current_protocol_code,
                   current_system.address_domain_code AS current_address_domain_code,
                   current_system.configuration_id AS current_system_configuration_id,
                   current_system.p25_wacn AS current_p25_wacn,
                   current_system.p25_system_id AS current_p25_system_id,
                   current_p25_site.protocol AS current_p25_protocol,
                   current_p25_site.rfss AS current_p25_rfss,
                   current_p25_site.site AS current_p25_site
            FROM configuration_channel channel
            LEFT JOIN alias_list list ON list.id = channel.alias_list_id
            LEFT JOIN receiver_channel receiver
              ON receiver.configuration_id = channel.configuration_id
            LEFT JOIN radio_system current_system
              ON current_system.id = receiver.radio_system_id
            LEFT JOIN p25_site_snapshot current_p25_site
              ON current_p25_site.channel_id = receiver.id
            ORDER BY channel.sort_order, channel.id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                String json = resultSet.getString("config_json");
                requireAbsent(json, CHANNEL_ROW_OWNED_JSON_FIELDS, "configuration_channel.config_json");
                String configurationId = requireCanonicalConfigurationId(resultSet.getString("configuration_id"));
                Channel channel = mObjectMapper.readValue(json, Channel.class);
                channel.setConfigurationId(configurationId);

                if(!ChannelConfigurationPolicy.requireChannelKind(channel).name()
                    .equals(resultSet.getString("channel_kind")))
                {
                    throw new IOException("Channel kind scalar does not match config_json");
                }

                ConfigurationChannelProjection.from(channel).requireMatches(
                    ConfigurationChannelProjection.read(resultSet), "Channel " + configurationId);
                restoreInterruptedP25Binding(channel, resultSet);
                channel.setSystem(resultSet.getString("system_name"));
                channel.setSite(resultSet.getString("site_name"));
                channel.setName(resultSet.getString("name"));
                channel.setAliasListName(resultSet.getString("alias_list_name"));
                Long aliasListId = readNullableLong(resultSet, "alias_list_id");
                channel.setAliasListId(aliasListId != null ? aliasListId : AliasListDefinition.UNASSIGNED_ID);
                channel.setRadioResolveId(resultSet.getString("radioresolve_id"));
                channel.setAutoStart(ConfigurationChannelProjection.readBooleanFlag(resultSet, "auto_start"));
                channel.setAutoStartOrder(ConfigurationChannelProjection.readNullableInt(resultSet,
                    "auto_start_order"));
                channels.add(channel);
            }
        }

        return channels;
    }

    /**
     * Restores the first verified P25 site into the runtime channel when an interrupted delayed save left the JSON
     * binding empty. The receiver row is joined by this channel's exact configuration UUID, and only a complete,
     * canonical native P25 system/site projection is eligible. A saved JSON binding always remains authoritative.
     */
    private static void restoreInterruptedP25Binding(Channel channel, ResultSet resultSet) throws SQLException
    {
        Integer currentProtocol = readCurrentInteger(resultSet, "current_protocol_code");
        Integer currentAddressDomain = readCurrentInteger(resultSet, "current_address_domain_code");
        if(channel.getP25SiteIdentity() != null || !"TRUNKED".equals(resultSet.getString("channel_kind")) ||
            !("P25_PHASE1".equals(resultSet.getString("decoder_type")) ||
                "P25_PHASE2".equals(resultSet.getString("decoder_type"))) ||
            currentProtocol == null || currentProtocol != 1 ||
            currentAddressDomain == null || currentAddressDomain != 0 ||
            resultSet.getString("current_system_configuration_id") != null ||
            !"APCO25".equals(resultSet.getString("current_p25_protocol")))
        {
            return;
        }

        Integer wacn = readCurrentInteger(resultSet, "current_p25_wacn");
        Integer system = readCurrentInteger(resultSet, "current_p25_system_id");
        Integer rfss = readCurrentInteger(resultSet, "current_p25_rfss");
        Integer site = readCurrentInteger(resultSet, "current_p25_site");

        if(wacn == null || system == null || rfss == null || site == null)
        {
            return;
        }

        try
        {
            P25SiteIdentity identity = new P25SiteIdentity(wacn, system, rfss, site);
            if(RadioSystemKey.p25(identity).equals(resultSet.getString("current_system_key")))
            {
                channel.setP25SiteIdentity(identity);
            }
        }
        catch(IllegalArgumentException ignored)
        {
            //Startup schema validation reports malformed current state. Never infer a binding from it here.
        }
    }

    /** Returns an exact SQLite integer for optional derived state, or null for absent/malformed input. */
    private static Integer readCurrentInteger(ResultSet resultSet, String column) throws SQLException
    {
        Object value = resultSet.getObject(column);
        if(value instanceof Byte || value instanceof Short || value instanceof Integer)
        {
            return ((Number)value).intValue();
        }
        if(value instanceof Long number && number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE)
        {
            return number.intValue();
        }
        return null;
    }

    private List<BroadcastConfiguration> loadBroadcastConfigurations(Connection connection)
        throws SQLException, IOException
    {
        List<BroadcastConfiguration> configurations = new ArrayList<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT configuration_id, config_json
            FROM configuration_broadcast_stream
            ORDER BY sort_order, id
            """);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                String json = resultSet.getString("config_json");
                requireAbsent(json, "configurationId", "configuration_broadcast_stream.config_json");
                BroadcastConfiguration configuration =
                    mObjectMapper.readValue(json, BroadcastConfiguration.class);
                configuration.setConfigurationId(
                    requireCanonicalConfigurationId(resultSet.getString("configuration_id")));
                configurations.add(configuration);
            }
        }

        return configurations;
    }

    /**
     * Persists an authoritative channel snapshot while writing only new or changed rows. Missing channels are deleted;
     * unchanged channel identities, history and rows remain untouched. The caller owns the transaction.
     */
    public void synchronizeChannels(Connection connection, List<Channel> channels) throws SQLException, IOException
    {
        requireTransaction(connection);
        Map<String,ChannelRow> stored = storedChannelRows(connection);
        Map<String,ChannelRow> desired = new LinkedHashMap<>();
        for(int sortOrder = 0; sortOrder < channels.size(); sortOrder++)
        {
            Channel channel = channels.get(sortOrder);
            if(ChannelConfigurationPolicy.isRetired(channel))
            {
                throw new IOException("Retired channel configuration cannot be stored in the active database");
            }
            String id = requireCanonicalConfigurationId(channel.getConfigurationId());
            ChannelRow row = new ChannelRow(ChannelConfigurationPolicy.requireChannelKind(channel).name(), sortOrder,
                channel.getSystem(), channel.getSite(), channel.getName(), channel.getAliasListId(),
                channel.getRadioResolveId(), channel.getAutoStart(), channel.getAutoStartOrder(),
                ConfigurationChannelProjection.from(channel), channelPayload(channel));
            if(desired.put(id, row) != null)
            {
                throw new IOException("Duplicate channel configuration identity: " + id);
            }
        }

        deleteMissing(connection, "configuration_channel", desired.keySet());

        //Only topology changes discard the receiver's now-incompatible derived history. Renames, frequency changes
        //and ordering edits retain the stable parent and all of its history.
        try(PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM configuration_channel WHERE configuration_id=?");
            PreparedStatement clear = connection.prepareStatement(
                "UPDATE configuration_channel SET radioresolve_id=NULL WHERE configuration_id=?"))
        {
            for(Map.Entry<String,ChannelRow> entry: desired.entrySet())
            {
                ChannelRow previous = stored.get(entry.getKey());
                ChannelRow next = entry.getValue();
                if(previous != null && !previous.sameClassification(next))
                {
                    delete.setString(1, entry.getKey());
                    delete.addBatch();
                }
                else if(previous != null && previous.radioResolveId() != null &&
                    !Objects.equals(previous.radioResolveId(), next.radioResolveId()))
                {
                    //Release only IDs whose owners actually change. This supports atomic swaps without touching
                    //unrelated rows or briefly clearing a stable channel's correlation ID.
                    clear.setString(1, entry.getKey());
                    clear.addBatch();
                }
            }
            delete.executeBatch();
            clear.executeBatch();
        }

        try(PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO configuration_channel (
                    channel_kind, sort_order, system_name, site_name, name, alias_list_id,
                    radioresolve_id, auto_start, auto_start_order, decoder_type, address_domain_code,
                    primary_frequency_hz, config_json, configuration_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """);
            PreparedStatement update = connection.prepareStatement("""
                UPDATE configuration_channel SET channel_kind=?, sort_order=?, system_name=?, site_name=?, name=?,
                    alias_list_id=?, radioresolve_id=?, auto_start=?, auto_start_order=?, decoder_type=?,
                    address_domain_code=?, primary_frequency_hz=?, config_json=? WHERE configuration_id=?
                """))
        {
            for(Map.Entry<String,ChannelRow> entry: desired.entrySet())
            {
                ChannelRow row = entry.getValue();
                ChannelRow previous = stored.get(entry.getKey());
                if(row.equals(previous))
                {
                    continue;
                }
                PreparedStatement statement = previous != null && previous.sameClassification(row) ? update : insert;
                statement.setString(1, row.channelKind());
                statement.setInt(2, row.sortOrder());
                statement.setString(3, row.system());
                statement.setString(4, row.site());
                statement.setString(5, row.name());
                setLong(statement, 6, row.aliasListId());
                statement.setString(7, row.radioResolveId());
                statement.setInt(8, row.autoStart() ? 1 : 0);
                setInteger(statement, 9, row.autoStartOrder());
                row.projection().bind(statement, 10);
                statement.setString(13, mObjectMapper.writeValueAsString(row.payload()));
                statement.setString(14, entry.getKey());
                statement.addBatch();
            }
            insert.executeBatch();
            update.executeBatch();
        }
    }

    /** Persists an authoritative stream snapshot without rewriting unchanged destinations. */
    public void synchronizeBroadcastConfigurations(Connection connection, List<BroadcastConfiguration> configurations)
        throws SQLException, IOException
    {
        requireTransaction(connection);
        Map<String,BroadcastRow> stored = new HashMap<>();
        try(PreparedStatement query = connection.prepareStatement(
                "SELECT configuration_id, sort_order, config_json FROM configuration_broadcast_stream");
            ResultSet rows = query.executeQuery())
        {
            while(rows.next())
            {
                stored.put(rows.getString("configuration_id"), new BroadcastRow(rows.getInt("sort_order"),
                    mObjectMapper.readTree(rows.getString("config_json"))));
            }
        }
        Map<String,BroadcastRow> desired = new LinkedHashMap<>();
        for(int sortOrder = 0; sortOrder < configurations.size(); sortOrder++)
        {
            BroadcastConfiguration configuration = configurations.get(sortOrder);
            String id = requireCanonicalConfigurationId(configuration.getConfigurationId());
            BroadcastRow row = new BroadcastRow(sortOrder, broadcastPayload(configuration));
            if(desired.put(id, row) != null)
            {
                throw new IOException("Duplicate broadcast configuration identity: " + id);
            }
        }

        deleteMissing(connection, "configuration_broadcast_stream", desired.keySet());
        try(PreparedStatement insert = connection.prepareStatement("""
                INSERT INTO configuration_broadcast_stream (sort_order, config_json, configuration_id) VALUES (?, ?, ?)
                """);
            PreparedStatement update = connection.prepareStatement("""
                UPDATE configuration_broadcast_stream SET sort_order=?, config_json=? WHERE configuration_id=?
                """))
        {
            for(Map.Entry<String,BroadcastRow> entry: desired.entrySet())
            {
                BroadcastRow row = entry.getValue();
                BroadcastRow previous = stored.get(entry.getKey());
                if(row.equals(previous))
                {
                    continue;
                }
                PreparedStatement statement = previous != null ? update : insert;
                statement.setInt(1, row.sortOrder());
                statement.setString(2, mObjectMapper.writeValueAsString(row.payload()));
                statement.setString(3, entry.getKey());
                statement.addBatch();
            }
            insert.executeBatch();
            update.executeBatch();
        }
    }

    private Map<String,ChannelRow> storedChannelRows(Connection connection) throws SQLException, IOException
    {
        Map<String,ChannelRow> stored = new HashMap<>();
        try(PreparedStatement statement = connection.prepareStatement("SELECT * FROM configuration_channel");
            ResultSet rows = statement.executeQuery())
        {
            while(rows.next())
            {
                stored.put(rows.getString("configuration_id"), new ChannelRow(rows.getString("channel_kind"),
                    rows.getInt("sort_order"), rows.getString("system_name"), rows.getString("site_name"),
                    rows.getString("name"), readNullableLong(rows, "alias_list_id"),
                    rows.getString("radioresolve_id"), ConfigurationChannelProjection.readBooleanFlag(rows, "auto_start"),
                    ConfigurationChannelProjection.readNullableInt(rows, "auto_start_order"),
                    ConfigurationChannelProjection.read(rows), mObjectMapper.readTree(rows.getString("config_json"))));
            }
        }
        return stored;
    }

    private static void requireTransaction(Connection connection) throws SQLException
    {
        if(connection == null || connection.getAutoCommit())
        {
            throw new IllegalArgumentException("Configuration snapshot writes require a caller-owned transaction");
        }
    }

    private static void deleteMissing(Connection connection, String table, Set<String> retainedIds)
        throws SQLException
    {
        List<String> removed = new ArrayList<>();
        try(PreparedStatement query = connection.prepareStatement("SELECT configuration_id FROM " + table);
            ResultSet rows = query.executeQuery())
        {
            while(rows.next())
            {
                String configurationId = rows.getString(1);
                if(!retainedIds.contains(configurationId))
                {
                    removed.add(configurationId);
                }
            }
        }

        try(PreparedStatement delete = connection.prepareStatement(
            "DELETE FROM " + table + " WHERE configuration_id=?"))
        {
            for(String configurationId: removed)
            {
                delete.setString(1, configurationId);
                delete.addBatch();
            }
            delete.executeBatch();
        }
    }

    private static void setInteger(PreparedStatement statement, int index, Integer value) throws SQLException
    {
        if(value != null)
        {
            statement.setInt(index, value);
        }
        else
        {
            statement.setNull(index, Types.INTEGER);
        }
    }

    private static void setLong(PreparedStatement statement, int index, Long value) throws SQLException
    {
        if(value != null)
        {
            statement.setLong(index, value);
        }
        else
        {
            statement.setNull(index, Types.INTEGER);
        }
    }

    private JsonNode channelPayload(Channel channel) throws IOException
    {
        //Read the serialized document so integer/float Java types use the same node representations as stored JSON.
        ObjectNode payload = (ObjectNode)mObjectMapper.readTree(mObjectMapper.writeValueAsBytes(channel));
        payload.remove(CHANNEL_ROW_OWNED_JSON_FIELDS);
        return payload;
    }

    private JsonNode broadcastPayload(BroadcastConfiguration configuration) throws IOException
    {
        ObjectNode payload = (ObjectNode)mObjectMapper.readTree(
            mObjectMapper.writerFor(BroadcastConfiguration.class).writeValueAsBytes(configuration));
        payload.remove("configurationId");
        return payload;
    }

    private void requireAbsent(String json, String property, String source) throws IOException
    {
        requireAbsent(json, List.of(property), source);
    }

    private void requireAbsent(String json, List<String> properties, String source) throws IOException
    {
        JsonNode payload = mObjectMapper.readTree(json);
        for(String property: properties)
        {
            if(payload.has(property))
            {
                throw new IOException(source + " must not duplicate relational field [" + property + "]");
            }
        }
    }

    private static Long readNullableLong(ResultSet resultSet, String column) throws SQLException, IOException
    {
        Object value = resultSet.getObject(column);
        if(value == null)
        {
            return null;
        }
        if(value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long)
        {
            return ((Number)value).longValue();
        }
        throw new IOException("configuration_channel " + column + " is not stored as an integer");
    }

    private static String requireCanonicalConfigurationId(String value) throws IOException
    {
        try
        {
            String canonical = UUID.fromString(value).toString();

            if(!canonical.equals(value))
            {
                throw new IllegalArgumentException("not canonical");
            }

            return canonical;
        }
        catch(IllegalArgumentException | NullPointerException exception)
        {
            throw new IOException("Channel configuration identity must be a canonical lowercase UUID", exception);
        }
    }

    private record ChannelRow(String channelKind, int sortOrder, String system, String site, String name,
                              Long aliasListId, String radioResolveId, boolean autoStart, Integer autoStartOrder,
                              ConfigurationChannelProjection projection, JsonNode payload)
    {
        private boolean sameClassification(ChannelRow other)
        {
            return channelKind.equals(other.channelKind) &&
                Objects.equals(projection.decoderType(), other.projection.decoderType()) &&
                projection.addressDomainCode() == other.projection.addressDomainCode();
        }
    }

    private record BroadcastRow(int sortOrder, JsonNode payload)
    {
    }

}
