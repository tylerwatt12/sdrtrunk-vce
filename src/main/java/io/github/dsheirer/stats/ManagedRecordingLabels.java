/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.preference.UserPreferences;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Resolves current, administrator-owned names and the latest observed OTA talker alias for a bounded recording page.
 * The recording catalog keeps raw identities; changes to an Alias or channel therefore appear on old calls without
 * rewriting millions of catalog rows. A missing/pruned main-database identity simply leaves optional labels absent.
 */
final class ManagedRecordingLabels
{
    private static final int MAX_SUGGESTIONS = 20;
    private final Path mDatabasePath;

    ManagedRecordingLabels(UserPreferences preferences)
    {
        this(SdrTrunkDatabasePath.getDatabasePath(preferences));
    }

    ManagedRecordingLabels(Path databasePath)
    {
        mDatabasePath = databasePath;
    }

    List<Map<String,Object>> decorate(List<Map<String,Object>> calls)
    {
        if(calls.isEmpty())
        {
            return calls;
        }

        List<Map<String,Object>> result = new ArrayList<>(calls.size());
        for(Map<String,Object> call: calls)
        {
            result.add(new LinkedHashMap<>(call));
        }

        if(!Files.isRegularFile(mDatabasePath))
        {
            return result;
        }

        try(Connection connection = open())
        {
            Map<String,ChannelLabels> channels = new HashMap<>();
            Map<String,AliasLabels> aliases = new HashMap<>();
            Map<String,String> talkerAliases = new HashMap<>();
            for(Map<String,Object> call: result)
            {
                String channelId = string(call.get("channel_id"));
                ChannelLabels channel = channelId != null ? channels.computeIfAbsent(channelId,
                    key -> channel(connection, key)) : null;
                if(channel != null)
                {
                    put(call, "channel_name", channel.name());
                    put(call, "system_name", channel.systemName());
                    put(call, "site_name", channel.siteName());
                }

                Integer aliasListId = channel != null && channel.aliasListId() != null ? channel.aliasListId() :
                    integer(call.get("alias_list_id"));
                String protocol = string(call.get("protocol"));
                Integer targetId = integer(call.get("talkgroup_id"));
                Integer sourceId = integer(call.get("source_id"));
                Integer destinationRadioId = integer(call.get("destination_radio_id"));
                if(aliasListId != null && targetId != null && targetId > 0)
                {
                    String key = aliasListId + ":g:" + protocol + ':' + targetId;
                    AliasLabels label = aliases.computeIfAbsent(key,
                        ignored -> alias(connection, aliasListId, protocol, targetId, false));
                    if(label != null)
                    {
                        put(call, "group_alias", label.name());
                        put(call, "group_description", label.description());
                        put(call, "group_group", label.group());
                    }
                }
                if(aliasListId != null && sourceId != null && sourceId > 0)
                {
                    String key = aliasListId + ":r:" + protocol + ':' + sourceId;
                    AliasLabels label = aliases.computeIfAbsent(key,
                        ignored -> alias(connection, aliasListId, protocol, sourceId, true));
                    if(label != null)
                    {
                        put(call, "source_alias", label.name());
                        put(call, "source_description", label.description());
                        put(call, "source_group", label.group());
                    }
                }
                if(aliasListId != null && destinationRadioId != null && destinationRadioId > 0)
                {
                    String key = aliasListId + ":r:" + protocol + ':' + destinationRadioId;
                    AliasLabels label = aliases.computeIfAbsent(key,
                        ignored -> alias(connection, aliasListId, protocol, destinationRadioId, true));
                    if(label != null)
                    {
                        put(call, "destination_radio_alias", label.name());
                        put(call, "destination_radio_description", label.description());
                        put(call, "destination_radio_group", label.group());
                    }
                }
                String systemKey = string(call.get("system_key"));
                if(systemKey != null && sourceId != null && sourceId > 0)
                {
                    Integer homeWacn = integer(call.get("source_home_wacn"));
                    Integer homeSystem = integer(call.get("source_home_system_id"));
                    String key = systemKey + ':' + sourceId + ':' + homeWacn + ':' + homeSystem;
                    String ota = talkerAliases.computeIfAbsent(key,
                        ignored -> latestTalkerAlias(connection, systemKey, sourceId, homeWacn, homeSystem));
                    put(call, "source_ota_alias", ota);
                }
            }
        }
        catch(SQLException ignored)
        {
            //Current names are optional. Catalog data remains usable when the main database is busy or replaced.
        }
        return result;
    }

    List<Map<String,Object>> suggestions(String query, String kind, int requestedLimit)
    {
        return suggestions(query, kind, null, requestedLimit);
    }

    List<Map<String,Object>> suggestions(String query, String kind, String systemKey, int requestedLimit)
    {
        if(query == null || query.isBlank() || !Files.isRegularFile(mDatabasePath))
        {
            return List.of();
        }
        int limit = Math.max(1, Math.min(MAX_SUGGESTIONS, requestedLimit));
        String pattern = '%' + query.toLowerCase(Locale.ROOT).replace("\\", "\\\\")
            .replace("%", "\\%").replace("_", "\\_") + '%';
        List<Map<String,Object>> result = new ArrayList<>();
        try(Connection connection = open())
        {
            if(kind == null || "system".equals(kind))
            {
                try(PreparedStatement statement = connection.prepareStatement("""
                    SELECT system.system_key,
                        coalesce(nullif(config.system_name, ''), (
                            SELECT nullif(saved.system_name, '')
                            FROM receiver_channel receiver
                            JOIN configuration_channel saved ON saved.configuration_id=receiver.configuration_id
                            WHERE receiver.radio_system_id=system.id AND saved.system_name IS NOT NULL
                            ORDER BY saved.configuration_id LIMIT 1
                        ), system.system_key) label
                    FROM radio_system system
                    LEFT JOIN configuration_channel config ON config.configuration_id = system.configuration_id
                    WHERE lower(system.system_key) LIKE ? ESCAPE '\\'
                       OR lower(coalesce(config.system_name,'')) LIKE ? ESCAPE '\\'
                       OR EXISTS(
                           SELECT 1 FROM receiver_channel receiver
                           JOIN configuration_channel saved ON saved.configuration_id=receiver.configuration_id
                           WHERE receiver.radio_system_id=system.id
                             AND lower(coalesce(saved.system_name,'')) LIKE ? ESCAPE '\\'
                       )
                    ORDER BY label LIMIT ?
                    """))
                {
                    statement.setString(1, pattern);
                    statement.setString(2, pattern);
                    statement.setString(3, pattern);
                    statement.setInt(4, limit);
                    try(ResultSet rows = statement.executeQuery())
                    {
                        while(rows.next())
                        {
                            result.add(suggestion("system", rows.getString("system_key"),
                                rows.getString("label"), null, rows.getString("system_key")));
                        }
                    }
                }
            }
            if(result.size() < limit && (kind == null || "channel".equals(kind) || "site".equals(kind)))
            {
                try(PreparedStatement statement = connection.prepareStatement("""
                    SELECT config.configuration_id, config.name, config.site_name, config.system_name,
                        config.channel_kind, p25.rfss, p25.site AS site_id,
                        system.p25_wacn AS wacn, system.p25_system_id AS sysid
                    FROM configuration_channel config
                    LEFT JOIN receiver_channel receiver ON receiver.configuration_id=config.configuration_id
                    LEFT JOIN p25_site_snapshot p25 ON p25.channel_id=receiver.id
                    LEFT JOIN radio_system system ON system.id=receiver.radio_system_id
                    WHERE lower(coalesce(config.name,'') || ' ' || coalesce(config.site_name,'') || ' ' ||
                        coalesce(config.system_name,'')) LIKE ? ESCAPE '\\'
                      AND (? IS NULL OR system.system_key=?)
                    ORDER BY lower(coalesce(config.system_name,'')), lower(coalesce(config.site_name,'')),
                        lower(coalesce(config.name,''))
                    LIMIT ?
                    """))
                {
                    statement.setString(1, pattern);
                    statement.setString(2, systemKey);
                    statement.setString(3, systemKey);
                    statement.setInt(4, limit - result.size());
                    try(ResultSet rows = statement.executeQuery())
                    {
                        while(rows.next())
                        {
                            String site = rows.getString("site_name");
                            String type = "site".equals(kind) && site != null && !site.isBlank() ? "site" : "channel";
                            if("site".equals(kind) && !"site".equals(type))
                            {
                                continue;
                            }
                            String label = "site".equals(type) ? site : rows.getString("name");
                            Map<String,Object> item = suggestion(type, rows.getString("configuration_id"), label,
                                rows.getString("system_name"), null);
                            item.put("channel_id", rows.getString("configuration_id"));
                            if("site".equals(type) && rows.getObject("rfss") != null &&
                                rows.getObject("site_id") != null)
                            {
                                item.put("rfss", rows.getInt("rfss"));
                                item.put("site_id", rows.getInt("site_id"));
                                if(rows.getObject("wacn") != null) item.put("wacn", rows.getInt("wacn"));
                                if(rows.getObject("sysid") != null) item.put("sysid", rows.getInt("sysid"));
                            }
                            result.add(item);
                        }
                    }
                }
            }
            if(result.size() < limit && (kind == null || "talkgroup".equals(kind) || "radio".equals(kind)))
            {
                try(PreparedStatement statement = connection.prepareStatement("""
                    SELECT alias.matcher_type, alias.value, alias.min_value, alias.max_value,
                        alias.name, alias.description,
                        list.id AS alias_list_id, list.name AS list_name
                    FROM alias
                    JOIN alias_list list ON list.id = alias.alias_list_id
                    WHERE alias.matcher_type = ?
                      AND (lower(alias.name) LIKE ? ESCAPE '\\' OR CAST(alias.value AS TEXT) LIKE ? ESCAPE '\\'
                          OR CAST(alias.min_value AS TEXT) LIKE ? ESCAPE '\\'
                          OR CAST(alias.max_value AS TEXT) LIKE ? ESCAPE '\\')
                      AND (? IS NULL OR alias.alias_list_id IN (
                          SELECT saved.alias_list_id
                          FROM configuration_channel saved
                          JOIN receiver_channel receiver ON receiver.configuration_id=saved.configuration_id
                          JOIN radio_system system ON system.id=receiver.radio_system_id
                          WHERE system.system_key=?
                      ))
                    ORDER BY lower(alias.name), alias.id LIMIT ?
                    """))
                {
                    for(String matcher: kind == null ?
                        List.of("TALKGROUP", "TALKGROUP_RANGE", "RADIO_ID", "RADIO_ID_RANGE") :
                        "radio".equals(kind) ? List.of("RADIO_ID", "RADIO_ID_RANGE") :
                            List.of("TALKGROUP", "TALKGROUP_RANGE"))
                    {
                        statement.setString(1, matcher);
                        statement.setString(2, pattern);
                        statement.setString(3, pattern);
                        statement.setString(4, pattern);
                        statement.setString(5, pattern);
                        statement.setString(6, systemKey);
                        statement.setString(7, systemKey);
                        statement.setInt(8, limit - result.size());
                        try(ResultSet rows = statement.executeQuery())
                        {
                            while(rows.next())
                            {
                                boolean radio = matcher.startsWith("RADIO");
                                boolean range = matcher.endsWith("_RANGE");
                                String rowKind = (radio ? "radio" : "talkgroup") + (range ? "_range" : "");
                                String id = range ? rows.getLong("min_value") + "-" + rows.getLong("max_value") :
                                    Long.toString(rows.getLong("value"));
                                Map<String,Object> item = suggestion(rowKind, id,
                                    rows.getString("name"), rows.getString("list_name"), null);
                                item.put("alias_list_id", rows.getLong("alias_list_id"));
                                if(range)
                                {
                                    item.put("min_id", rows.getInt("min_value"));
                                    item.put("max_id", rows.getInt("max_value"));
                                }
                                if(systemKey != null) item.put("system_key", systemKey);
                                result.add(item);
                            }
                        }
                        if(result.size() >= limit) break;
                    }
                }
            }
            if(result.size() < limit && systemKey != null && query.length() >= 3 &&
                (kind == null || "radio".equals(kind)))
            {
                try(PreparedStatement statement = connection.prepareStatement("""
                    SELECT recent.identity_id, recent.last_talker_alias
                    FROM (
                        SELECT summary.identity_id, summary.last_talker_alias
                        FROM radio_system_identity_summary summary
                            INDEXED BY idx_radio_system_identity_last_seen
                        JOIN radio_system system ON system.id=summary.radio_system_id
                        WHERE system.system_key=? AND summary.identity_kind_code=2
                        ORDER BY summary.last_seen_ms DESC LIMIT 5000
                    ) recent
                    WHERE recent.last_talker_alias IS NOT NULL
                      AND lower(recent.last_talker_alias) LIKE ? ESCAPE '\\'
                    LIMIT ?
                    """))
                {
                    statement.setString(1, systemKey);
                    statement.setString(2, pattern);
                    statement.setInt(3, limit - result.size());
                    try(ResultSet rows = statement.executeQuery())
                    {
                        while(rows.next())
                        {
                            result.add(suggestion("radio", Integer.toString(rows.getInt("identity_id")),
                                rows.getString("last_talker_alias"), "Latest over-the-air name",
                                systemKey));
                        }
                    }
                }
            }
        }
        catch(SQLException ignored)
        {
            return List.of();
        }
        return result;
    }

    /** Resolves a free-text name to raw numeric identities without searching the large call catalog by label. */
    List<Integer> matchingIdentityIds(String query, int maximum)
    {
        return matchingIdentityIds(query, null, maximum);
    }

    List<Integer> matchingIdentityIds(String query, String systemKey, int maximum)
    {
        if(query == null || query.isBlank() || !Files.isRegularFile(mDatabasePath))
        {
            return List.of();
        }
        String pattern = '%' + query.toLowerCase(Locale.ROOT).replace("\\", "\\\\")
            .replace("%", "\\%").replace("_", "\\_") + '%';
        Set<Integer> ids = new LinkedHashSet<>();
        try(Connection connection = open())
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                SELECT matcher_type,value,min_value,max_value FROM alias
                WHERE matcher_type IN ('TALKGROUP','RADIO_ID','TALKGROUP_RANGE','RADIO_ID_RANGE') AND
                    lower(name || ' ' || coalesce(description,'') || ' ' || coalesce(group_name,''))
                        LIKE ? ESCAPE '\\'
                  AND (? IS NULL OR alias_list_id IN (
                      SELECT saved.alias_list_id
                      FROM configuration_channel saved
                      JOIN receiver_channel receiver ON receiver.configuration_id=saved.configuration_id
                      JOIN radio_system system ON system.id=receiver.radio_system_id
                      WHERE system.system_key=?
                  ))
                LIMIT ?
                """))
            {
                statement.setString(1, pattern);
                statement.setString(2, systemKey);
                statement.setString(3, systemKey);
                statement.setInt(4, maximum + 1);
                try(ResultSet rows = statement.executeQuery())
                {
                    while(rows.next())
                    {
                        String matcher = rows.getString("matcher_type");
                        if(matcher.endsWith("_RANGE"))
                        {
                            int minimum = rows.getInt("min_value");
                            int maximumId = rows.getInt("max_value");
                            if(maximumId - minimum + 1 > maximum)
                            {
                                throw new StatsApiException(422, "search_too_broad",
                                    "Select the matching range from suggestions to search it");
                            }
                            for(int id = minimum; id <= maximumId; id++)
                            {
                                ids.add(id);
                            }
                        }
                        else
                        {
                            ids.add(rows.getInt("value"));
                        }
                        if(ids.size() > maximum) break;
                    }
                }
            }
            if(ids.size() <= maximum)
            {
                String otaSql = systemKey != null ? """
                    SELECT summary.identity_id FROM radio_system_identity_summary summary
                    JOIN radio_system system ON system.id=summary.radio_system_id
                    WHERE system.system_key=? AND summary.identity_kind_code=2
                      AND summary.last_talker_alias IS NOT NULL
                      AND lower(summary.last_talker_alias) LIKE ? ESCAPE '\\'
                    LIMIT ?
                    """ : """
                    SELECT identity_id FROM radio_system_identity_summary
                    WHERE identity_kind_code=2 AND last_talker_alias IS NOT NULL
                      AND lower(last_talker_alias) LIKE ? ESCAPE '\\'
                    LIMIT ?
                    """;
                try(PreparedStatement statement = connection.prepareStatement(otaSql))
                {
                    int parameter = 1;
                    if(systemKey != null)
                    {
                        statement.setString(parameter++, systemKey);
                    }
                    statement.setString(parameter++, pattern);
                    statement.setInt(parameter, maximum + 1);
                    try(ResultSet rows = statement.executeQuery())
                    {
                        while(rows.next())
                        {
                            ids.add(rows.getInt(1));
                        }
                    }
                }
            }
        }
        catch(SQLException ignored)
        {
            return List.of();
        }
        if(ids.size() > maximum)
        {
            throw new StatsApiException(422, "search_too_broad",
                "Too many identities match. Add a system or use a more specific name.");
        }
        return List.copyOf(ids);
    }

    private static Map<String,Object> suggestion(String kind, String id, String label, String detail,
                                                 String systemKey)
    {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("kind", kind);
        row.put("id", id);
        row.put("label", label != null && !label.isBlank() ? label : id);
        put(row, "detail", detail);
        put(row, "system_key", systemKey);
        return row;
    }

    private Connection open() throws SQLException
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=" + SdrTrunkDatabase.BUSY_TIMEOUT_MILLISECONDS);
            statement.execute("PRAGMA query_only=ON");
        }
        return connection;
    }

    private static ChannelLabels channel(Connection connection, String id)
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT name, system_name, site_name, alias_list_id
            FROM configuration_channel WHERE configuration_id=?
            """))
        {
            statement.setString(1, id);
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? new ChannelLabels(rows.getString(1), rows.getString(2), rows.getString(3),
                    rows.getInt(4)) : null;
            }
        }
        catch(SQLException ignored)
        {
            return null;
        }
    }

    private static AliasLabels alias(Connection connection, int listId, String callProtocol, int id, boolean radio)
    {
        String protocol = switch(callProtocol != null ? callProtocol.toUpperCase(Locale.ROOT) : "")
        {
            case "P25", "APCO25", "APCO25_PHASE2" -> "APCO25";
            case "DMR" -> "DMR";
            case "NXDN" -> "NXDN";
            case "AM" -> "AM";
            case "NBFM" -> "NBFM";
            default -> null;
        };
        if(protocol == null)
        {
            return null;
        }
        String exact = radio ? "RADIO_ID" : "TALKGROUP";
        String range = radio ? "RADIO_ID_RANGE" : "TALKGROUP_RANGE";
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT name, description, group_name
            FROM alias
            WHERE alias_list_id=? AND protocol IN (?,?)
              AND ((matcher_type=? AND value=?) OR
                   (matcher_type=? AND min_value<=? AND max_value>=?))
            ORDER BY CASE WHEN matcher_type=? THEN 0 ELSE 1 END,
                coalesce(max_value-min_value,0), id
            LIMIT 1
            """))
        {
            statement.setInt(1, listId);
            statement.setString(2, protocol);
            statement.setString(3, "APCO25".equals(protocol) ? "APCO25_PHASE2" : protocol);
            statement.setString(4, exact);
            statement.setInt(5, id);
            statement.setString(6, range);
            statement.setInt(7, id);
            statement.setInt(8, id);
            statement.setString(9, exact);
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? new AliasLabels(rows.getString(1), rows.getString(2), rows.getString(3)) : null;
            }
        }
        catch(SQLException ignored)
        {
            return null;
        }
    }

    private static String latestTalkerAlias(Connection connection, String systemKey, int radioId,
                                            Integer homeWacn, Integer homeSystem)
    {
        String sql = """
            SELECT summary.last_talker_alias
            FROM radio_system_identity_summary summary
            JOIN radio_system system ON system.id=summary.radio_system_id
            WHERE system.system_key=? AND summary.identity_kind_code=2 AND summary.identity_id=?
              AND summary.last_talker_alias IS NOT NULL
            """ + " AND summary.home_wacn=? AND summary.home_system_id=? " + """
            ORDER BY summary.last_talker_alias_seen_ms DESC LIMIT 1
            """;
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            statement.setString(1, systemKey);
            statement.setInt(2, radioId);
            statement.setInt(3, homeWacn != null ? homeWacn : -1);
            statement.setInt(4, homeSystem != null ? homeSystem : -1);
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? rows.getString(1) : null;
            }
        }
        catch(SQLException ignored)
        {
            return null;
        }
    }

    private static void put(Map<String,Object> row, String key, String value)
    {
        if(value != null && !value.isBlank())
        {
            row.put(key, value);
        }
    }

    private static String string(Object value)
    {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private static Integer integer(Object value)
    {
        return value instanceof Number number ? number.intValue() : null;
    }

    private record ChannelLabels(String name, String systemName, String siteName, Integer aliasListId) {}
    private record AliasLabels(String name, String description, String group) {}
}
