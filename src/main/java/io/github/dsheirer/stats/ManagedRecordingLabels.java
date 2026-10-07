/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog.IdentityNameMatch;
import io.github.dsheirer.stats.activity.RadioSystemIdentityLookup;
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
            Map<String,SystemScope> systems = new HashMap<>();
            Map<String,String> knownIdentities = new HashMap<>();
            StatsSystemNameResolver systemNames = new StatsSystemNameResolver(connection);
            for(Map<String,Object> call: result)
            {
                String protocol = string(call.get("protocol"));
                String recordedSystem = recordedSystemKey(call, protocol);
                put(call, "system_key", recordedSystem);
                String channelId = string(call.get("channel_id"));
                ChannelLabels channel = channelId != null ? channels.computeIfAbsent(channelId,
                    key -> channel(connection, key)) : null;
                if(channel != null)
                {
                    put(call, "channel_name", channel.name());
                    String eventSystem = string(call.get("system_key"));
                    if(eventSystem == null || eventSystem.equals(channel.systemKey()))
                        put(call, "system_name", channel.systemName());
                    put(call, "site_name", channel.siteName());
                    try
                    {
                        WebEntityRef.put(call, "channel_entity_ref", WebEntityRef.channel(channelId));
                    }
                    catch(IllegalArgumentException ignored)
                    {
                        //A legacy or malformed channel ID has no stable detail-page destination.
                    }
                }

                Long aliasListId = channel != null && channel.aliasListId() != null ? channel.aliasListId() :
                    nullableNumber(call.get("alias_list_id"));
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
                if(aliasListId != null && sourceId != null && sourceId > 0 &&
                    localRadioAliasEligible(call, "source", sourceId, recordedSystem, protocol))
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
                if(aliasListId != null && destinationRadioId != null && destinationRadioId > 0 &&
                    localRadioAliasEligible(call, "target", destinationRadioId, recordedSystem, protocol))
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
                SystemScope system = systemKey != null ? systems.computeIfAbsent(systemKey,
                    key -> system(connection, key)) : null;
                if(system != null)
                {
                    WebEntityRef.put(call, "radio_system_entity_ref", system.reference());
                    if(protocolMatches(system.protocolCode(), protocol))
                    {
                        WebEntityRef.put(call, "source_entity_ref", identityReference(connection, system,
                            knownIdentities, RadioSystemIdentityKey.KIND_RADIO, sourceId,
                            integer(call.get("source_home_wacn")), integer(call.get("source_home_system_id")),
                            integer(call.get("source_home_id"))));
                        String callType = string(call.get("call_type"));
                        int targetKind = switch(callType != null ? callType : "")
                        {
                            case "GROUP" -> RadioSystemIdentityKey.KIND_TALKGROUP;
                            case "PATCH" -> RadioSystemIdentityKey.KIND_PATCH_GROUP;
                            case "DIRECT" -> RadioSystemIdentityKey.KIND_RADIO;
                            default -> 0;
                        };
                        if(targetKind != 0)
                        {
                            WebEntityRef.put(call, "target_entity_ref", identityReference(connection, system,
                                knownIdentities, targetKind, integer(call.get("target_id")),
                                integer(call.get("target_home_wacn")), integer(call.get("target_home_system_id")),
                                integer(call.get("target_home_id"))));
                        }
                        decoratePatchMembers(connection, call, system, knownIdentities, talkerAliases);
                    }
                }
                if(system != null && protocolMatches(system.protocolCode(), protocol))
                {
                    put(call, "source_ota_alias", cachedTalkerAlias(connection, system, talkerAliases, sourceId,
                        integer(call.get("source_home_wacn")), integer(call.get("source_home_system_id")),
                        integer(call.get("source_home_id"))));
                    if("DIRECT".equals(string(call.get("call_type"))))
                    {
                        put(call, "destination_radio_ota_alias", cachedTalkerAlias(connection, system,
                            talkerAliases, integer(call.get("target_id")), integer(call.get("target_home_wacn")),
                            integer(call.get("target_home_system_id")), integer(call.get("target_home_id"))));
                    }
                }
            }
            for(int index = 0; index < result.size(); index++)
            {
                result.set(index, systemNames.enrichMap(result.get(index)));
            }
        }
        catch(SQLException ignored)
        {
            //Current names are optional. Catalog data remains usable when the main database is busy or replaced.
        }
        return result;
    }

    List<Map<String,Object>> enrichSystemNames(List<Map<String,Object>> values)
    {
        if(values.isEmpty() || !Files.isRegularFile(mDatabasePath)) return values;
        try(Connection connection = open())
        {
            StatsSystemNameResolver names = new StatsSystemNameResolver(connection);
            List<Map<String,Object>> result = new ArrayList<>(values.size());
            for(Map<String,Object> value: values) result.add(names.enrichMap(value));
            return result;
        }
        catch(SQLException ignored)
        {
            return values;
        }
    }

    private static void decoratePatchMembers(Connection connection, Map<String,Object> call, SystemScope system,
                                             Map<String,String> knownIdentities, Map<String,String> talkerAliases)
    {
        if(!(call.get("patch_members") instanceof List<?> members) || members.isEmpty())
        {
            return;
        }
        List<Map<String,Object>> decorated = new ArrayList<>(members.size());
        for(Object item: members)
        {
            if(!(item instanceof Map<?,?> member))
            {
                continue;
            }
            Map<String,Object> copy = new LinkedHashMap<>();
            member.forEach((key, value) -> {
                if(key instanceof String field) copy.put(field, value);
            });
            int kind = switch(String.valueOf(copy.get("kind")))
            {
                case "talkgroup" -> RadioSystemIdentityKey.KIND_TALKGROUP;
                case "radio" -> RadioSystemIdentityKey.KIND_RADIO;
                default -> 0;
            };
            if(kind != 0)
            {
                WebEntityRef.put(copy, identityReference(connection, system, knownIdentities, kind,
                    integer(copy.get("id")), integer(copy.get("home_wacn")),
                    integer(copy.get("home_system_id")), integer(copy.get("home_identity_id"))));
                if(kind == RadioSystemIdentityKey.KIND_RADIO)
                {
                    put(copy, "ota_alias", cachedTalkerAlias(connection, system, talkerAliases,
                        integer(copy.get("id")), integer(copy.get("home_wacn")),
                        integer(copy.get("home_system_id")), integer(copy.get("home_identity_id"))));
                }
            }
            decorated.add(copy);
        }
        call.put("patch_members", decorated);
    }

    private static boolean protocolMatches(int systemProtocol, String callProtocol)
    {
        return switch(callProtocol != null ? callProtocol : "")
        {
            case "APCO25", "APCO25_PHASE2" -> systemProtocol == 1;
            case "DMR" -> systemProtocol == 3;
            case "NXDN" -> systemProtocol == 4;
            default -> false;
        };
    }

    /** A legacy recording's captured winner, or unanimous captured sites, may establish its receiving system. */
    private static String recordedSystemKey(Map<String,Object> call, String protocol)
    {
        String stored = string(call.get("system_key"));
        if(stored != null || !("APCO25".equals(protocol) || "APCO25_PHASE2".equals(protocol))) return stored;
        Integer wacn = integer(call.get("wacn"));
        Integer system = integer(call.get("system_id"));
        if(wacn != null && system != null) return RadioSystemKey.p25(wacn, system);
        String candidate = null;
        if(call.get("also_received_on") instanceof List<?> sites)
        {
            for(Object value: sites)
            {
                if(!(value instanceof Map<?,?> site) || !(site.get("wacn") instanceof Number home) ||
                    !(site.get("system_id") instanceof Number id)) return null;
                String key = RadioSystemKey.p25(home.intValue(), id.intValue());
                if(key == null || candidate != null && !candidate.equals(key)) return null;
                candidate = key;
            }
        }
        return candidate;
    }

    /** Old catalog rows cannot prove an equal-number foreign Working ID; retain only supported local fallback. */
    private static boolean localRadioAliasEligible(Map<String,Object> call, String prefix, int localId,
                                                    String receivingSystem, String protocol)
    {
        if(!RadioSystemKey.isP25Native(receivingSystem) ||
            !("APCO25".equals(protocol) || "APCO25_PHASE2".equals(protocol))) return true;
        Integer homeWacn = integer(call.get(prefix + "_home_wacn"));
        Integer homeSystem = integer(call.get(prefix + "_home_system_id"));
        Integer homeId = integer(call.get(prefix + "_home_id"));
        if(homeWacn == null || homeSystem == null || homeId == null || homeId != localId) return true;
        String home = RadioSystemKey.p25(homeWacn, homeSystem);
        return home == null || home.equals(receivingSystem);
    }

    private static SystemScope system(Connection connection, String key)
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT id,protocol_code,p25_wacn,p25_system_id FROM radio_system WHERE system_key=?
            """))
        {
            statement.setString(1, key);
            try(ResultSet row = statement.executeQuery())
            {
                if(row.next())
                {
                    return new SystemScope(row.getLong("id"), key, row.getInt("protocol_code"),
                        row.getObject("p25_wacn") != null ? row.getInt("p25_wacn") : null,
                        row.getObject("p25_system_id") != null ? row.getInt("p25_system_id") : null,
                        WebEntityRef.radioSystem(key));
                }
            }
        }
        catch(SQLException | IllegalArgumentException ignored)
        {
            //An unavailable or invalid system has no stable detail-page destination.
        }
        return null;
    }

    private static WebEntityRef identityReference(Connection connection, SystemScope system,
                                                   Map<String,String> knownIdentities, int kind, Integer localId,
                                                   Integer homeWacn, Integer homeSystemId, Integer homeId)
    {
        boolean completeHome = system.protocolCode() == 1 && homeWacn != null && homeSystemId != null &&
            homeId != null && homeId > 0;
        if((localId == null || localId <= 0) && !completeHome)
        {
            return null;
        }
        int canonicalId = localId != null ? localId : homeId;
        int canonicalWacn = RadioSystemIdentityKey.NO_HOME;
        int canonicalSystem = RadioSystemIdentityKey.NO_HOME;
        if(system.protocolCode() == 1)
        {
            if(homeWacn != null || homeSystemId != null || homeId != null)
            {
                if(homeWacn == null || homeSystemId == null || homeId == null)
                {
                    return null;
                }
                canonicalId = homeId;
                canonicalWacn = homeWacn;
                canonicalSystem = homeSystemId;
            }
            else if(system.p25Wacn() != null && system.p25SystemId() != null)
            {
                if(kind != RadioSystemIdentityKey.KIND_RADIO)
                {
                    canonicalWacn = system.p25Wacn();
                    canonicalSystem = system.p25SystemId();
                }
            }
            else
            {
                return null;
            }
        }
        else if(homeWacn != null || homeSystemId != null || homeId != null)
        {
            return null;
        }
        try
        {
            String identityKey = RadioSystemIdentityKey.format(kind, canonicalWacn, canonicalSystem,
                canonicalId);
            String cacheKey = system.key() + ':' + identityKey;
            int lookupId = canonicalId;
            int lookupWacn = canonicalWacn;
            int lookupSystem = canonicalSystem;
            String resolvedKey = knownIdentities.computeIfAbsent(cacheKey, ignored -> identityKey(connection,
                system, kind, lookupWacn, lookupSystem, lookupId));
            if(resolvedKey == null)
            {
                return null;
            }
            return switch(kind)
            {
                case RadioSystemIdentityKey.KIND_TALKGROUP -> WebEntityRef.talkgroup(system.key(), resolvedKey);
                case RadioSystemIdentityKey.KIND_PATCH_GROUP -> WebEntityRef.patchGroup(system.key(), resolvedKey);
                case RadioSystemIdentityKey.KIND_RADIO -> WebEntityRef.radio(system.key(), resolvedKey);
                default -> null;
            };
        }
        catch(IllegalArgumentException ignored)
        {
            return null;
        }
    }

    private static String identityKey(Connection connection, SystemScope system, int kind, int homeWacn,
                                      int homeSystemId, int identityId)
    {
        try
        {
            Long summaryId = RadioSystemIdentityLookup.find(connection, system.id(), kind,
                homeWacn, homeSystemId, identityId);
            if(summaryId == null) return null;
            try(PreparedStatement statement = connection.prepareStatement("""
                SELECT home_wacn,home_system_id,identity_id FROM radio_system_identity_summary
                WHERE id=? AND radio_system_id=? AND identity_kind_code=?
                """))
            {
                statement.setLong(1, summaryId);
                statement.setLong(2, system.id());
                statement.setInt(3, kind);
                try(ResultSet rows = statement.executeQuery())
                {
                    return rows.next() ? RadioSystemIdentityKey.format(kind, rows.getInt("home_wacn"),
                        rows.getInt("home_system_id"), rows.getInt("identity_id")) : null;
                }
            }
        }
        catch(SQLException | IllegalArgumentException ignored)
        {
            return null;
        }
    }

    List<Map<String,Object>> suggestions(String query, String kind, int requestedLimit)
    {
        return suggestions(query, kind, null, requestedLimit);
    }

    List<Map<String,Object>> suggestions(String query, String kind, String systemKey, int requestedLimit)
    {
        return suggestions(query, kind, systemKey, requestedLimit, null);
    }

    /** Applies catalog eligibility before each label query's limit, without changing either database. */
    List<Map<String,Object>> suggestions(String query, String kind, String systemKey, int requestedLimit,
                                         Path catalogDatabase)
    {
        boolean recordedOnly = catalogDatabase != null;
        if(query == null || query.isBlank() || !Files.isRegularFile(mDatabasePath) ||
            (recordedOnly && !Files.isRegularFile(catalogDatabase)))
        {
            return List.of();
        }
        int limit = Math.max(1, Math.min(MAX_SUGGESTIONS, requestedLimit));
        Long exactIdentity = query.matches("[0-9]{1,10}") ? Long.parseLong(query) : null;
        String pattern = '%' + query.toLowerCase(Locale.ROOT).replace("\\", "\\\\")
            .replace("%", "\\%").replace("_", "\\_") + '%';
        List<Map<String,Object>> result = new ArrayList<>();
        try(Connection connection = open())
        {
            if(recordedOnly)
            {
                attachRecordingCatalog(connection, catalogDatabase);
            }
            String scope = recordedOnly ?
                "WITH recording_scope(system_key,wacn,sysid) AS (VALUES (?,?,?)) " : "";
            if(kind == null || "system".equals(kind))
            {
                try(PreparedStatement statement = connection.prepareStatement(scope + """
                    SELECT system.system_key,
                        coalesce(%s, system.system_key) label
                    FROM radio_system system
                    LEFT JOIN configuration_channel config ON config.configuration_id = system.configuration_id
                    WHERE (lower(system.system_key) LIKE ? ESCAPE '\\'
                       OR lower(coalesce(config.system_name,'')) LIKE ? ESCAPE '\\'
                       OR EXISTS(
                           SELECT 1 FROM receiver_channel receiver
                           JOIN configuration_channel saved ON saved.configuration_id=receiver.configuration_id
                           WHERE receiver.radio_system_id=system.id
                             AND lower(coalesce(saved.system_name,'')) LIKE ? ESCAPE '\\'
                       ))
                      AND %s
                    ORDER BY label LIMIT ?
                    """.formatted(StatsSystemNameResolver.configuredNameSql("system"), recordedOnly ? "EXISTS(SELECT 1 FROM (" +
                        recordingCallCandidates("system.system_key", "system.p25_wacn", "system.p25_system_id") +
                        "))" : "1")))
                {
                    int offset = bindRecordingScope(statement, recordedOnly, systemKey);
                    statement.setString(offset + 1, pattern);
                    statement.setString(offset + 2, pattern);
                    statement.setString(offset + 3, pattern);
                    statement.setInt(offset + 4, limit);
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
                try(PreparedStatement statement = connection.prepareStatement(scope + """
                    SELECT config.configuration_id, config.name, config.site_name, config.system_name,
                        config.channel_kind, p25.rfss, p25.site AS site_id,
                        system.p25_wacn AS wacn, system.p25_system_id AS sysid
                    FROM configuration_channel config
                    LEFT JOIN receiver_channel receiver ON receiver.configuration_id=config.configuration_id
                    LEFT JOIN p25_site_snapshot p25 ON p25.channel_id=receiver.id
                    LEFT JOIN radio_system system ON system.id=receiver.radio_system_id
                    WHERE lower(coalesce(config.name,'') || ' ' || coalesce(config.site_name,'') || ' ' ||
                        coalesce(config.system_name,'')%s) LIKE ? ESCAPE '\\'
                      AND (? IS NULL OR %s)
                      AND %s
                    ORDER BY lower(coalesce(config.system_name,'')), lower(coalesce(config.site_name,'')),
                        lower(coalesce(config.name,''))
                    LIMIT ?
                    """.formatted("site".equals(kind) ?
                        " || ' ' || coalesce(CAST(p25.rfss AS TEXT),'') || ' ' || coalesce(CAST(p25.site AS TEXT),'')" : "",
                        recordedOnly ? "?=(SELECT system_key FROM recording_scope)" : "system.system_key=?",
                        recordedOnly ? recordedChannelPredicate("site".equals(kind)) : "1")))
                {
                    int offset = bindRecordingScope(statement, recordedOnly, systemKey);
                    statement.setString(offset + 1, pattern);
                    statement.setString(offset + 2, systemKey);
                    statement.setString(offset + 3, systemKey);
                    statement.setInt(offset + 4, limit - result.size());
                    try(ResultSet rows = statement.executeQuery())
                    {
                        while(rows.next())
                        {
                            String site = rows.getString("site_name");
                            String type = "site".equals(kind) ? "site" : "channel";
                            String label = rows.getString("name");
                            if(label == null || label.isBlank()) label = site;
                            List<String> context = new ArrayList<>();
                            if("site".equals(type) && site != null && !site.isBlank()) context.add(site);
                            String systemName = rows.getString("system_name");
                            if(systemName != null && !systemName.isBlank()) context.add(systemName);
                            Map<String,Object> item = suggestion(type, rows.getString("configuration_id"), label,
                                context.isEmpty() ? null : String.join(" · ", context), null);
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
                try(PreparedStatement statement = connection.prepareStatement(scope + """
                    SELECT alias.matcher_type, alias.value, alias.min_value, alias.max_value,
                        alias.name, alias.description,
                        list.id AS alias_list_id, list.name AS list_name
                    FROM alias
                    JOIN alias_list list ON list.id = alias.alias_list_id
                    WHERE alias.matcher_type = ?
                      AND (lower(alias.name) LIKE ? ESCAPE '\\' OR CAST(alias.value AS TEXT) LIKE ? ESCAPE '\\'
                          OR CAST(alias.min_value AS TEXT) LIKE ? ESCAPE '\\'
                          OR CAST(alias.max_value AS TEXT) LIKE ? ESCAPE '\\')
                      AND %s
                      AND (? IS NULL OR alias.alias_list_id IN (
                          SELECT saved.alias_list_id
                          FROM configuration_channel saved
                          JOIN receiver_channel receiver ON receiver.configuration_id=saved.configuration_id
                          JOIN radio_system system ON system.id=receiver.radio_system_id
                          WHERE system.system_key=?
                      ))
                    ORDER BY CASE WHEN alias.value=? THEN 0 ELSE 1 END, lower(alias.name), alias.id LIMIT ?
                    """.formatted(recordedOnly ? recordedAliasPredicate() : "1")))
                {
                    for(String matcher: kind == null ?
                        List.of("TALKGROUP", "TALKGROUP_RANGE", "RADIO_ID", "RADIO_ID_RANGE") :
                        "radio".equals(kind) ? List.of("RADIO_ID", "RADIO_ID_RANGE") :
                            List.of("TALKGROUP", "TALKGROUP_RANGE"))
                    {
                        int offset = bindRecordingScope(statement, recordedOnly, systemKey);
                        statement.setString(offset + 1, matcher);
                        statement.setString(offset + 2, pattern);
                        statement.setString(offset + 3, pattern);
                        statement.setString(offset + 4, pattern);
                        statement.setString(offset + 5, pattern);
                        statement.setString(offset + 6, recordedOnly ? null : systemKey);
                        statement.setString(offset + 7, systemKey);
                        statement.setObject(offset + 8, exactIdentity);
                        statement.setInt(offset + 9, limit - result.size());
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
                String recordedRadio = recordedOnly ? recordedOtaIdentityQuery() : "recent.identity_id";
                try(PreparedStatement statement = connection.prepareStatement(scope + """
                    SELECT * FROM (
                    SELECT (%s) AS identity_id, recent.last_talker_alias
                    FROM (
                        SELECT summary.identity_id, summary.last_talker_alias%s
                        FROM radio_system_identity_summary summary
                            INDEXED BY idx_radio_system_identity_last_seen
                        JOIN radio_system system ON system.id=summary.radio_system_id
                        WHERE system.system_key=? AND summary.identity_kind_code=2
                        ORDER BY summary.last_seen_ms DESC LIMIT 5000
                    ) recent
                    WHERE recent.last_talker_alias IS NOT NULL
                      AND lower(recent.last_talker_alias) LIKE ? ESCAPE '\\'
                    ) WHERE identity_id IS NOT NULL LIMIT ?
                    """.formatted(recordedRadio, recordedOnly ?
                        ", summary.home_wacn, summary.home_system_id, summary.radio_system_id, " +
                        "summary.p25_subscriber_identity_id, system.p25_wacn, system.p25_system_id" : "")))
                {
                    int offset = bindRecordingScope(statement, recordedOnly, systemKey);
                    statement.setString(offset + 1, systemKey);
                    statement.setString(offset + 2, pattern);
                    statement.setInt(offset + 3, limit - result.size());
                    try(ResultSet rows = statement.executeQuery())
                    {
                        while(rows.next())
                        {
                            String name = rows.getString("last_talker_alias");
                            Map<String,Object> item = suggestion("radio", Integer.toString(rows.getInt("identity_id")),
                                name, "Latest over-the-air name", systemKey);
                            item.put("name_query", name);
                            result.add(item);
                        }
                    }
                }
            }
        }
        catch(SQLException ignored)
        {
            // A failed optional lookup cannot add an unverified candidate. Retain earlier verified matches.
        }
        return result;
    }

    private static void attachRecordingCatalog(Connection connection, Path catalogDatabase) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("ATTACH DATABASE ? AS recordings"))
        {
            statement.setString(1, catalogDatabase.toAbsolutePath().normalize().toUri() + "?mode=ro");
            statement.execute();
        }
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=1000");
        }
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        org.sqlite.ProgressHandler.setHandler(connection, 10000, new org.sqlite.ProgressHandler()
        {
            @Override
            protected int progress()
            {
                return System.nanoTime() >= deadline ? 1 : 0;
            }
        });
    }

    private static int bindRecordingScope(PreparedStatement statement, boolean recordedOnly, String systemKey)
        throws SQLException
    {
        if(!recordedOnly) return 0;
        boolean p25 = systemKey != null && RadioSystemKey.isP25Native(systemKey);
        statement.setString(1, systemKey);
        statement.setObject(2, p25 ? Integer.parseInt(systemKey.substring(4, 9), 16) : null);
        statement.setObject(3, p25 ? Integer.parseInt(systemKey.substring(10), 16) : null);
        return 3;
    }

    /** Mirrors catalog search ownership, including older P25 calls without a stored system key. */
    private static String recordingSystemPredicate(String key, String wacn, String sysid)
    {
        return "(c.system_id=(SELECT id FROM recordings.recording_system WHERE system_key=" + key + ") OR " +
            "(c.system_id IS NULL AND c.protocol IN (1,2) AND " + wacn + " IS NOT NULL AND (" +
            "EXISTS(SELECT 1 FROM recordings.recording_site winner WHERE winner.id=c.winner_site_id " +
            "AND winner.wacn=" + wacn + " AND winner.system_id=" + sysid + ") OR " +
            "(c.winner_site_id IS NULL AND EXISTS(SELECT 1 FROM recordings.recording_call_site cs " +
            "JOIN recordings.recording_site observed ON observed.id=cs.site_id WHERE cs.call_id=c.id " +
            "AND observed.wacn=" + wacn + " AND observed.system_id=" + sysid + ") AND NOT EXISTS(" +
            "SELECT 1 FROM recordings.recording_call_site cs JOIN recordings.recording_site observed " +
            "ON observed.id=cs.site_id WHERE cs.call_id=c.id AND (observed.wacn<>" + wacn +
            " OR observed.system_id<>" + sysid + "))))))";
    }

    /** Stored ownership and legacy site evidence use separate indexed drivers. */
    private static String recordingCallCandidates(String key, String wacn, String sysid)
    {
        return "SELECT c.* FROM recordings.recording_call c WHERE c.system_id=" +
            "(SELECT id FROM recordings.recording_system WHERE system_key=" + key + ") UNION ALL " +
            "SELECT c.* FROM recordings.recording_site evidence " +
            "JOIN recordings.recording_call_site cs ON cs.site_id=evidence.id " +
            "JOIN recordings.recording_call c ON c.id=cs.call_id WHERE evidence.wacn=" + wacn +
            " AND evidence.system_id=" + sysid + " AND c.system_id IS NULL AND " +
            recordingSystemPredicate(key, wacn, sysid);
    }

    private static String selectedRecordingSystemPredicate()
    {
        return "((SELECT system_key FROM recording_scope) IS NULL OR " +
            recordingSystemPredicate("(SELECT system_key FROM recording_scope)",
                "(SELECT wacn FROM recording_scope)", "(SELECT sysid FROM recording_scope)") + ")";
    }

    private static String recordedChannelPredicate(boolean site)
    {
        String eligibility = selectedRecordingSystemPredicate();
        if(site)
        {
            // A current channel snapshot is not proof that its numeric site has recorded calls.
            eligibility += " AND (p25.rfss IS NULL OR p25.site IS NULL OR EXISTS(" +
                "SELECT 1 FROM recordings.recording_call_site cs JOIN recordings.recording_site s " +
                "ON s.id=cs.site_id WHERE cs.call_id=c.id AND s.rfss=p25.rfss AND s.site_id=p25.site " +
                "AND (system.p25_wacn IS NULL OR s.wacn=system.p25_wacn) " +
                "AND (system.p25_system_id IS NULL OR s.system_id=system.p25_system_id)))";
        }
        return "EXISTS(SELECT 1 FROM recordings.recording_call c WHERE c.channel_id=" +
            "(SELECT id FROM recordings.recording_channel WHERE channel_uuid=config.configuration_id) " +
            "AND " + eligibility + ")";
    }

    /** Exact production eligibility SQL, also used by query-plan regression tests. */
    static String recordedAliasPredicate()
    {
        String common = selectedRecordingSystemPredicate() +
            " AND c.alias_list_id=alias.alias_list_id AND alias.alias_list_id=coalesce(" +
            "(SELECT saved.alias_list_id FROM configuration_channel saved JOIN recordings.recording_channel ch " +
            "ON ch.channel_uuid=saved.configuration_id WHERE ch.id=c.channel_id),c.alias_list_id) AND " +
            "((alias.protocol IN ('APCO25','APCO25_PHASE2') AND c.protocol IN (1,2)) OR " +
            "(alias.protocol='DMR' AND c.protocol=3) OR (alias.protocol='NXDN' AND c.protocol=4) OR " +
            "(alias.protocol='NBFM' AND c.protocol=5) OR (alias.protocol='AM' AND c.protocol=6))";
        // Separate probes let the existing source, target, and patch-member indexes drive each lookup.
        return "((alias.matcher_type IN ('RADIO_ID','RADIO_ID_RANGE') AND (" +
            recordedAliasRole("c.source_id", "", common) + " OR " +
            recordedAliasRole("c.target_id", "c.call_type=3 AND ", common) + " OR " +
            recordedAliasPatchRole(2, common) + ")) OR " +
            "(alias.matcher_type IN ('TALKGROUP','TALKGROUP_RANGE') AND (" +
            recordedAliasRole("c.target_id", "c.call_type IN (1,2) AND ", common) + " OR " +
            recordedAliasPatchRole(1, common) + ")))";
    }

    private static String aliasIdentityPredicate(String identity)
    {
        // A single bounded interval keeps exact identities and ranges on the same indexed path.
        return identity + " BETWEEN CASE WHEN alias.matcher_type IN ('RADIO_ID','TALKGROUP') " +
            "THEN alias.value ELSE alias.min_value END AND CASE " +
            "WHEN alias.matcher_type IN ('RADIO_ID','TALKGROUP') THEN alias.value ELSE alias.max_value END";
    }

    private static String recordedAliasRole(String identity, String role, String common)
    {
        String index = "c.source_id".equals(identity) ? "idx_recording_call_source_time" :
            "idx_recording_call_target_time";
        return "EXISTS(SELECT 1 FROM recordings.recording_call c INDEXED BY " + index +
            " WHERE " + identity + " IS NOT NULL AND " + role +
            aliasIdentityPredicate(identity) + " AND " + common + ")";
    }

    private static String recordedAliasPatchRole(int kind, String common)
    {
        return "EXISTS(SELECT 1 FROM recordings.recording_patch_member member " +
            "INDEXED BY idx_recording_patch_member_lookup CROSS JOIN recordings.recording_call c " +
            "ON c.id=member.call_id WHERE member.kind=" + kind + " AND " +
            aliasIdentityPredicate("member.local_id") + " AND " + common + ")";
    }

    private static String recordedOtaIdentityQuery()
    {
        String scope = selectedRecordingSystemPredicate();
        String candidates = recordingCallCandidates("(SELECT system_key FROM recording_scope)",
            "(SELECT wacn FROM recording_scope)", "(SELECT sysid FROM recording_scope)");
        String homes = recordedOtaHome("member.local_id", "nullif(member.home_wacn,-1)",
            "nullif(member.home_system,-1)", "nullif(member.home_id,-1)");
        // Most OTA identities use their local ID. Probe those indexes before resolving different home IDs.
        String indexed = "SELECT local_id FROM (" +
            "SELECT coalesce(c.source_home_id,nullif(c.source_id,0)) AS local_id FROM recordings.recording_call c " +
            "WHERE c.source_id=recent.identity_id AND " + recordedOtaRole("c.source", scope) + " UNION ALL " +
            "SELECT coalesce(c.target_home_id,nullif(c.target_id,0)) AS local_id FROM recordings.recording_call c " +
            "WHERE c.target_id=recent.identity_id AND c.call_type=3 AND " +
            recordedOtaRole("c.target", scope) + " UNION ALL " +
            "SELECT coalesce(nullif(member.home_id,-1),nullif(member.local_id,0)) FROM recordings.recording_patch_member member " +
            "JOIN recordings.recording_call c ON c.id=member.call_id WHERE member.kind=2 " +
            "AND member.local_id=recent.identity_id AND " + scope + " AND " + homes + ") LIMIT 1";
        String homeMapping = "SELECT local_id FROM (" +
            "SELECT coalesce(c.source_home_id,nullif(c.source_id,0)) AS local_id FROM (" + candidates + ") c WHERE " +
            recordedOtaRole("c.source", "1") + " UNION ALL " +
            "SELECT coalesce(c.target_home_id,nullif(c.target_id,0)) AS local_id FROM (" + candidates + ") c WHERE c.call_type=3 AND " +
            recordedOtaRole("c.target", "1") + " UNION ALL " +
            "SELECT coalesce(nullif(member.home_id,-1),nullif(member.local_id,0)) FROM (" + candidates + ") c " +
            "JOIN recordings.recording_patch_member member ON member.call_id=c.id " +
            "WHERE member.kind=2 AND " + homes + ") LIMIT 1";
        return "coalesce((" + indexed + "),(" + homeMapping + "))";
    }

    private static String recordedOtaRole(String identity, String scope)
    {
        return scope + " AND " + recordedOtaHome(identity + "_id", identity + "_home_wacn",
            identity + "_home_system", identity + "_home_id");
    }

    /** Historical null-home metadata follows the same legacy-first owner rule as recording labels and routes. */
    private static String recordedOtaHome(String localId, String homeWacn, String homeSystem, String homeId)
    {
        String explicit = "(" + homeWacn + " IS NOT NULL AND " + homeSystem + " IS NOT NULL AND " +
            "coalesce(" + homeId + "," + localId + ")=recent.identity_id AND " +
            homeWacn + "=recent.home_wacn AND " + homeSystem + "=recent.home_system_id)";
        String legacy = "(recent.home_wacn=-1 AND recent.home_system_id=-1 AND " +
            "recent.p25_subscriber_identity_id IS NULL)";
        String serving = "(recent.home_wacn=recent.p25_wacn AND recent.home_system_id=recent.p25_system_id " +
            "AND NOT EXISTS(SELECT 1 FROM radio_system_identity_summary legacy " +
            "WHERE legacy.radio_system_id=recent.radio_system_id AND legacy.identity_kind_code=2 " +
            "AND legacy.home_wacn=-1 AND legacy.home_system_id=-1 AND legacy.identity_id=recent.identity_id " +
            "AND legacy.p25_subscriber_identity_id IS NULL))";
        return "(" + explicit + " OR (" + homeWacn + " IS NULL AND " + homeSystem + " IS NULL AND " +
            homeId + " IS NULL AND " + localId + "=recent.identity_id AND (" + legacy + " OR " + serving + ")))";
    }

    /** Resolves a free-text name to raw numeric identities without searching the large call catalog by label. */
    List<Integer> matchingIdentityIds(String query, int maximum)
    {
        return matchingIdentityIds(query, null, maximum);
    }

    List<Integer> matchingIdentityIds(String query, String systemKey, int maximum)
    {
        return matchingIdentityNames(query, systemKey, maximum).stream()
            .map(IdentityNameMatch::identityId).distinct().toList();
    }

    /** OTA names retain their exact owner instead of reducing a permanent identity to an ambiguous decimal ID. */
    List<IdentityNameMatch> matchingIdentityNames(String query, String systemKey, int maximum)
    {
        if(query == null || query.isBlank() || !Files.isRegularFile(mDatabasePath))
        {
            return List.of();
        }
        String pattern = '%' + query.toLowerCase(Locale.ROOT).replace("\\", "\\\\")
            .replace("%", "\\%").replace("_", "\\_") + '%';
        Set<Integer> ids = new LinkedHashSet<>();
        Set<IdentityNameMatch> matches = new LinkedHashSet<>();
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
            ids.forEach(id -> matches.add(new IdentityNameMatch(id, null, null, null, false)));
            if(matches.size() <= maximum)
            {
                String otaSql = """
                    SELECT summary.identity_id,summary.home_wacn,summary.home_system_id,
                        summary.p25_subscriber_identity_id,system.system_key,
                        NOT EXISTS(SELECT 1 FROM radio_system_identity_summary legacy
                            WHERE legacy.radio_system_id=summary.radio_system_id AND legacy.identity_kind_code=2
                              AND legacy.home_wacn=-1 AND legacy.home_system_id=-1
                              AND legacy.identity_id=summary.identity_id
                              AND legacy.p25_subscriber_identity_id IS NULL) AS no_legacy
                    FROM radio_system_identity_summary summary
                    JOIN radio_system system ON system.id=summary.radio_system_id
                    WHERE summary.identity_kind_code=2
                      AND summary.last_talker_alias IS NOT NULL
                      AND (? IS NULL OR system.system_key=?)
                      AND lower(summary.last_talker_alias) LIKE ? ESCAPE '\\'
                    LIMIT ?
                    """;
                try(PreparedStatement statement = connection.prepareStatement(otaSql))
                {
                    statement.setString(1, systemKey);
                    statement.setString(2, systemKey);
                    statement.setString(3, pattern);
                    statement.setInt(4, maximum + 1);
                    try(ResultSet rows = statement.executeQuery())
                    {
                        while(rows.next())
                        {
                            int homeWacn = rows.getInt("home_wacn");
                            int homeSystem = rows.getInt("home_system_id");
                            String owner = rows.getString("system_key");
                            boolean legacy = homeWacn == -1 && homeSystem == -1 &&
                                rows.getObject("p25_subscriber_identity_id") == null;
                            boolean serving = owner.equals(String.format(Locale.ROOT,
                                "p25:%05x:%03x", homeWacn, homeSystem)) && rows.getBoolean("no_legacy");
                            matches.add(new IdentityNameMatch(rows.getInt("identity_id"), owner,
                                homeWacn, homeSystem, legacy || serving));
                        }
                    }
                }
            }
        }
        catch(SQLException ignored)
        {
            return List.of();
        }
        if(matches.size() > maximum)
        {
            throw new StatsApiException(422, "search_too_broad",
                "Too many identities match. Add a system or use a more specific name.");
        }
        return List.copyOf(matches);
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
            SELECT config.name, config.system_name, config.site_name, config.alias_list_id,
                system.system_key
            FROM configuration_channel config
            LEFT JOIN receiver_channel channel ON channel.configuration_id=config.configuration_id
            LEFT JOIN radio_system system ON system.id=channel.radio_system_id
            WHERE config.configuration_id=?
            """))
        {
            statement.setString(1, id);
            try(ResultSet rows = statement.executeQuery())
            {
                return rows.next() ? new ChannelLabels(rows.getString(1), rows.getString(2), rows.getString(3),
                    rows.getObject(4) != null ? rows.getLong(4) : null, rows.getString(5)) : null;
            }
        }
        catch(SQLException ignored)
        {
            return null;
        }
    }

    private static AliasLabels alias(Connection connection, long listId, String callProtocol, int id, boolean radio)
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
            statement.setLong(1, listId);
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

    private static String latestTalkerAlias(Connection connection, SystemScope system, int radioId,
                                            Integer homeWacn, Integer homeSystem, Integer homeId)
    {
        boolean explicitHome = homeWacn != null || homeSystem != null || homeId != null;
        if(explicitHome && (system.protocolCode() != 1 || homeWacn == null || homeSystem == null)) return null;
        try
        {
            Long summaryId = RadioSystemIdentityLookup.find(connection, system.id(),
                RadioSystemIdentityKey.KIND_RADIO, homeWacn != null ? homeWacn : RadioSystemIdentityKey.NO_HOME,
                homeSystem != null ? homeSystem : RadioSystemIdentityKey.NO_HOME, homeId != null ? homeId : radioId);
            if(summaryId == null) return null;
            try(PreparedStatement statement = connection.prepareStatement("""
                SELECT last_talker_alias FROM radio_system_identity_summary
                WHERE id=? AND radio_system_id=? AND identity_kind_code=2
                """))
            {
                statement.setLong(1, summaryId);
                statement.setLong(2, system.id());
                try(ResultSet rows = statement.executeQuery())
                {
                    return rows.next() ? rows.getString(1) : null;
                }
            }
        }
        catch(SQLException ignored)
        {
            return null;
        }
    }

    private static String cachedTalkerAlias(Connection connection, SystemScope system, Map<String,String> cache,
                                            Integer radioId, Integer homeWacn, Integer homeSystem, Integer homeId)
    {
        Integer lookupId = radioId != null && radioId > 0 ? radioId :
            system.protocolCode() == 1 && homeWacn != null && homeSystem != null && homeId != null && homeId > 0 ?
                homeId : null;
        if(lookupId == null) return null;
        String key = system.key() + ':' + lookupId + ':' + homeWacn + ':' + homeSystem + ':' + homeId;
        return cache.computeIfAbsent(key,
            ignored -> latestTalkerAlias(connection, system, lookupId, homeWacn, homeSystem, homeId));
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

    private static Long nullableNumber(Object value)
    {
        return value instanceof Number number ? number.longValue() : null;
    }

    private record ChannelLabels(String name, String systemName, String siteName, Long aliasListId,
                                 String systemKey) {}
    private record AliasLabels(String name, String description, String group) {}
    private record SystemScope(long id, String key, int protocolCode, Integer p25Wacn, Integer p25SystemId,
                               WebEntityRef.KeyRef reference) {}
}
