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
 * Authoritative configured-channel read model for web entities.  Receiver contexts and activity summaries are
 * optional observations and never establish the existence of a site or conventional channel.
 */
final class WebConfiguredEntityRepository
{
    static final String CONFIGURED_CHANNEL_SELECT = """
        SELECT config.id AS configuration_row_id, config.configuration_id, config.channel_kind,
            config.system_name AS configured_system, config.site_name AS configured_site,
            config.name AS configured_name, config.name AS channel_name,
            config.alias_list_name, alias_list.id AS alias_list_id,
            config.radres_guid AS guid, config.decoder_type AS decoder,
            config.primary_frequency_hz,
            context.id AS context_id, context.context_key,
            context.first_seen_ms, context.last_seen_ms, context.nac,
            context.primary_frequency_hz AS observed_primary_frequency_hz
        FROM configuration_channel config
        LEFT JOIN alias_list ON alias_list.name = config.alias_list_name COLLATE NOCASE
        LEFT JOIN receiver_context context ON context.context_key = CASE config.channel_kind
            WHEN 'TRUNKED' THEN 'GUID:' || config.radres_guid
            WHEN 'CONVENTIONAL' THEN 'CONFIGURATION:' || config.configuration_id
        END
        """;

    ConfiguredChannel requireSite(Connection connection, String guid) throws SQLException
    {
        String key;

        try
        {
            key = WebEntityRef.site(guid).key();
        }
        catch(IllegalArgumentException exception)
        {
            throw new StatsApiException(400, "invalid_path", "guid must be a UUID", "guid");
        }
        return exactlyOne(queryRows(connection, CONFIGURED_CHANNEL_SELECT + """
            WHERE config.channel_kind = 'TRUNKED' AND config.radres_guid = ?
            """, key), "Site not found", "More than one configured site owns GUID [" + key + "]");
    }

    ConfiguredChannel requireConventional(Connection connection, String configurationId) throws SQLException
    {
        String key;

        try
        {
            key = WebEntityRef.conventional(configurationId).key();
        }
        catch(IllegalArgumentException exception)
        {
            throw new StatsApiException(400, "invalid_path", "configuration_id must be a UUID",
                "configuration_id");
        }

        return exactlyOne(queryRows(connection, CONFIGURED_CHANNEL_SELECT + """
            WHERE config.channel_kind = 'CONVENTIONAL' AND config.configuration_id = ?
            """, key), "Conventional channel not found",
            "More than one conventional channel owns configuration ID [" + key + "]");
    }

    List<ConfiguredChannel> conventionalChannels(Connection connection) throws SQLException
    {
        return queryRows(connection, CONFIGURED_CHANNEL_SELECT + """
            WHERE config.channel_kind = 'CONVENTIONAL'
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
            text(row.get("configured_system")), text(row.get("configured_site")),
            text(row.get("configured_name")), text(row.get("alias_list_name")),
            nullableLong(row.get("alias_list_id")), text(row.get("guid")), text(row.get("decoder")),
            nullableLong(row.get("primary_frequency_hz")), protocol,
            nullableLong(row.get("context_id")), text(row.get("context_key")),
            nullableLong(row.get("first_seen_ms")), nullableLong(row.get("last_seen_ms")),
            nullableLong(row.get("nac")), nullableLong(row.get("observed_primary_frequency_hz")));
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

    enum ChannelKind
    {
        TRUNKED, CONVENTIONAL
    }

    record ConfiguredChannel(long rowId, String configurationId, ChannelKind channelKind, String configuredSystem,
                             String configuredSite, String configuredName, String aliasListName, Long aliasListId,
                             String guid, String decoder, Long primaryFrequencyHz, StatsApiProtocol protocol,
                             Long contextId,
                             String contextKey, Long firstSeenMs, Long lastSeenMs, Long nac,
                             Long observedPrimaryFrequencyHz)
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
            put(row, "configured_system", configuredSystem);
            put(row, "configured_site", configuredSite);
            put(row, "configured_name", configuredName);
            put(row, "channel_name", configuredName);
            put(row, "alias_list_name", aliasListName);
            put(row, "guid", guid);
            put(row, "decoder", decoder);
            put(row, "protocol_code", protocolCode());
            put(row, "protocol", protocol.wireName());
            put(row, "nac", nac);
            put(row, "primary_frequency_hz",
                observedPrimaryFrequencyHz != null ? observedPrimaryFrequencyHz : primaryFrequencyHz);
            put(row, "first_seen_ms", firstSeenMs);
            put(row, "last_seen_ms", lastSeenMs);
            WebEntityRef.put(row, channelKind == ChannelKind.TRUNKED && guid != null ? WebEntityRef.site(guid) :
                channelKind == ChannelKind.CONVENTIONAL ? WebEntityRef.conventional(configurationId) : null);
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
