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

package io.github.dsheirer.stats;

import static io.github.dsheirer.stats.StatsSqlRows.queryRows;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Authoritative configured-channel read model for web entities. Receiver observations never establish channel
 * identity: every web channel is keyed by the saved configuration UUID.
 */
final class WebConfiguredEntityRepository
{
    static final String CONFIGURED_CHANNEL_SELECT = """
        SELECT config.id AS configuration_row_id, config.configuration_id, config.channel_kind,
            config.system_name AS system_name, config.site_name AS site_name, config.name AS name,
            alias_list.name AS alias_list_name, config.alias_list_id,
            config.decoder_type AS decoder, config.primary_frequency_hz,
            channel.id AS channel_id, channel.first_seen_ms, channel.last_seen_ms,
            radio_system.id AS radio_system_id, radio_system.system_key AS radio_system_key,
            radio_system.protocol_code AS radio_system_protocol_code,
            config.address_domain_code, radio_system.p25_wacn, radio_system.p25_system_id
        FROM configuration_channel config
        LEFT JOIN alias_list ON alias_list.id = config.alias_list_id
        LEFT JOIN receiver_channel channel ON channel.configuration_id = config.configuration_id
        LEFT JOIN radio_system ON radio_system.id = channel.radio_system_id
        """;

    ConfiguredChannel requireChannel(Connection connection, String configurationId) throws SQLException
    {
        String key;

        try
        {
            key = WebEntityRef.channel(configurationId).key();
        }
        catch(IllegalArgumentException exception)
        {
            throw new StatsApiException(400, "invalid_path", "configuration_id must be a UUID",
                "configuration_id");
        }
        return exactlyOne(queryRows(connection, CONFIGURED_CHANNEL_SELECT + """
            WHERE config.configuration_id = ?
            """, key), "Channel not found", "More than one channel owns configuration ID [" + key + "]");
    }

    List<ConfiguredChannel> channels(Connection connection) throws SQLException
    {
        return queryRows(connection, CONFIGURED_CHANNEL_SELECT + """
            ORDER BY config.sort_order, config.id
            """).stream().map(WebConfiguredEntityRepository::configuredChannel).toList();
    }

    private static ConfiguredChannel exactlyOne(List<Map<String,Object>> rows, String missing, String duplicate)
    {
        if(rows.isEmpty())
        {
            throw new StatsApiException(404, missing);
        }
        if(rows.size() != 1)
        {
            throw new StatsApiException(409, "configuration_identity_conflict", duplicate);
        }

        return configuredChannel(rows.getFirst());
    }

    static ConfiguredChannel configuredChannel(Map<String,Object> row)
    {
        StatsApiProtocol protocol = StatsApiProtocol.fromDecoder(text(row.get("decoder")));

        if(protocol == StatsApiProtocol.UNKNOWN)
        {
            throw new StatsApiException(500, "configuration_protocol_invalid",
                "Configured channel has an unsupported primary decoder");
        }

        return new ConfiguredChannel(
            longValue(row.get("configuration_row_id")), text(row.get("configuration_id")),
            ChannelKind.valueOf(text(row.get("channel_kind")).toUpperCase(Locale.ROOT)),
            text(row.get("system_name")), text(row.get("site_name")),
            text(row.get("name")), text(row.get("alias_list_name")),
            nullableLong(row.get("alias_list_id")), text(row.get("decoder")),
            nullableLong(row.get("primary_frequency_hz")), protocol, nullableLong(row.get("channel_id")),
            nullableLong(row.get("radio_system_id")), text(row.get("radio_system_key")),
            nullableInt(row.get("radio_system_protocol_code")), nullableInt(row.get("address_domain_code")),
            nullableInt(row.get("p25_wacn")), nullableInt(row.get("p25_system_id")),
            nullableLong(row.get("first_seen_ms")), nullableLong(row.get("last_seen_ms")));
    }

    private static String text(Object value)
    {
        return value != null && !String.valueOf(value).isBlank() ? String.valueOf(value).strip() : null;
    }

    private static long longValue(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static Long nullableLong(Object value)
    {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static Integer nullableInt(Object value)
    {
        return value instanceof Number number ? number.intValue() : null;
    }

    enum ChannelKind
    {
        TRUNKED, CONVENTIONAL
    }

    record ConfiguredChannel(long rowId, String configurationId, ChannelKind channelKind, String systemName,
                             String siteName, String name, String aliasListName, Long aliasListId,
                             String decoder, Long primaryFrequencyHz, StatsApiProtocol protocol, Long channelId,
                             Long radioSystemId, String radioSystemKey, Integer radioSystemProtocolCode,
                             Integer addressDomainCode, Integer p25Wacn, Integer p25SystemId,
                             Long firstSeenMs, Long lastSeenMs)
    {
        ConfiguredChannel
        {
            if(rowId <= 0 || configurationId == null || channelKind == null || protocol == null)
            {
                throw new StatsApiException(500, "configuration_identity_invalid",
                    "Configured channel identity is incomplete");
            }
        }

        int protocolCode()
        {
            return protocol.databaseCode();
        }

        Map<String,Object> toApiMap()
        {
            Map<String,Object> row = new LinkedHashMap<>();
            put(row, "configuration_id", configurationId);
            put(row, "channel_kind", channelKind.name());
            put(row, "system_name", systemName);
            put(row, "site_name", siteName);
            put(row, "name", name);
            put(row, "alias_list_name", aliasListName);
            put(row, "alias_list_id", aliasListId);
            put(row, "decoder", decoder);
            put(row, "protocol_code", protocolCode());
            put(row, "protocol", protocol.wireName());
            put(row, "primary_frequency_hz", primaryFrequencyHz);
            put(row, "radio_system_key", radioSystemKey);
            put(row, "first_seen_ms", firstSeenMs);
            put(row, "last_seen_ms", lastSeenMs);
            WebEntityRef.put(row, WebEntityRef.channel(configurationId));
            return row;
        }

        private static void put(Map<String,Object> target, String key, Object value)
        {
            if(value != null)
            {
                target.put(key, value);
            }
        }
    }
}
