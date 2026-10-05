/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.stats;

import static io.github.dsheirer.stats.StatsSqlRows.queryRows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Request-side presentation enrichment. Native keys, never a name or an Alias List, select a system. */
final class StatsSystemNameResolver
{
    private static final int MAXIMUM_LOOKUPS = 256;
    private static final ObjectMapper MAPPER = new ObjectMapper()
        .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
    private static final Pattern CONFIGURATION = Pattern.compile(
        "(?:^|channel:)([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(?:$|\\))");
    private static final Pattern LEGACY_RADIO = Pattern.compile(
        "(?:(?:ISSI|ROAM) )?([0-9]+)\\.([0-9]+)\\.([0-9]+)");
    private static final Pattern LEGACY_WORKING_RADIO = Pattern.compile(
        "(?:(?:ISSI|ROAM) )?([0-9]+)\\(([0-9]+)\\.([0-9]+)\\.([0-9]+)\\)");
    private static final Pattern FORMATTED_RADIO = Pattern.compile(
        "([0-9A-Fa-f]{5})\\.([0-9A-Fa-f]{3})\\.([0-9]+)(?: \\(Working ID ([0-9]+)\\))?");
    private final Connection mConnection;
    private final Map<String,ObjectNode> mSystems = new HashMap<>();
    private final Map<String,ObjectNode> mChannels = new HashMap<>();

    StatsSystemNameResolver(Connection connection)
    {
        mConnection = connection;
    }

    /** Includes all distinct configured names in stable order instead of hiding conflicting channel labels. */
    static String configuredNameSql(String system)
    {
        return "(SELECT group_concat(system_name, ' / ') FROM (" +
            "SELECT min(trim(config.system_name)) AS system_name FROM configuration_channel config " +
            "WHERE config.configuration_id IN (SELECT channel.configuration_id FROM receiver_channel channel " +
            "WHERE channel.radio_system_id=" + system + ".id UNION SELECT " + system + ".configuration_id) " +
            "AND nullif(trim(config.system_name),'') IS NOT NULL " +
            "GROUP BY lower(trim(config.system_name)) ORDER BY lower(system_name),system_name LIMIT 16))";
    }

    JsonNode enrich(Object value) throws SQLException
    {
        JsonNode result = MAPPER.valueToTree(value);
        visit(result);
        return result;
    }

    @SuppressWarnings("unchecked")
    Map<String,Object> enrichMap(Map<String,Object> value) throws SQLException
    {
        return MAPPER.convertValue(enrich(value), Map.class);
    }

    List<Map<String,Object>> knownP25Systems() throws SQLException
    {
        List<Map<String,Object>> result = new ArrayList<>();
        for(Map<String,Object> row: queryRows(mConnection, """
            SELECT DISTINCT system.system_key
            FROM configuration_channel config
            JOIN receiver_channel channel ON channel.configuration_id=config.configuration_id
            JOIN radio_system system ON system.id=channel.radio_system_id
            WHERE system.protocol_code=1 ORDER BY system.system_key LIMIT 256
            """))
        {
            ObjectNode identity = system(String.valueOf(row.get("system_key")));
            if(identity != null && identity.hasNonNull("system_name"))
            {
                ObjectNode profile = identity.deepCopy();
                profile.set("system", profile.get("system_id"));
                result.add(MAPPER.convertValue(profile, Map.class));
            }
        }
        return result;
    }

    private void visit(JsonNode value) throws SQLException
    {
        if(value == null || value.isValueNode()) return;
        if(value.isArray())
        {
            for(JsonNode child: value) visit(child);
            return;
        }
        ObjectNode row = (ObjectNode)value;
        //Deletion identities are deliberately exact input documents, not presentation records.
        if("scoped_data".equals(text(row, "kind"))) return;
        // Visit only original children; the identity facts added below do not need recursive expansion.
        List<JsonNode> children = new ArrayList<>();
        row.fields().forEachRemaining(field -> {
            if(!"target".equals(field.getKey())) children.add(field.getValue());
        });
        for(JsonNode child: children) visit(child);
        if(row.has("kind") && (row.has("key") || row.has("identity_key"))) return;

        String key = text(row, "radio_system_key", "system_key");
        if(key == null) key = nestedKey(row, "playback_target");
        if(key == null) key = nestedKey(row, "radio_system_entity_ref");
        if(key == null) key = nestedKey(row, "identity");
        if(key == null && "radio_system".equals(text(row, "source_kind"))) key = text(row, "source_key");
        if(key == null) key = nativeKey(row);
        String configuration = text(row, "configuration_id", "channel_id");
        if(configuration == null)
        {
            Matcher match = CONFIGURATION.matcher(textOrEmpty(row, "scope"));
            if(match.find()) configuration = match.group(1);
        }
        ObjectNode channel = channel(configuration);
        if(channel != null)
        {
            String channelKey = text(channel, "radio_system_key");
            if(key == null) key = channelKey;
            copyAbsent(row, channel, "channel_name", "site_name", "configuration_id");
            if(key == null || key.equals(channelKey)) copyAbsent(row, channel, "system_name");
        }
        ObjectNode identity = system(key);
        if(identity != null)
        {
            String fallbackSystem = RadioSystemKey.isP25Native(key) ?
                "P25 " + key.substring(4).replace(':', '-').toUpperCase(Locale.ROOT) : null;
            if(fallbackSystem != null && fallbackSystem.equals(text(row, "system_name")) &&
                identity.hasNonNull("system_name"))
            {
                if((fallbackSystem + " · " + text(row, "site_name")).equals(text(row, "name")))
                    row.put("name", identity.path("system_name").asText() + " · " + text(row, "site_name"));
                copyName(row, identity, "system_name");
            }
            copyAbsent(row, identity, "system_name", "system_names", "radio_system_key",
                "radio_system_entity_ref", "protocol", "wacn", "system_id", "network_id", "model",
                "location_category");
            row.set("system_identity", identity.deepCopy());
            if(row.has("source_value") && row.has("destination_value"))
                row.set("serving_system", identity.deepCopy());
            if(row.has("radio_system_name") || row.has("source_kind"))
                copyName(row, identity, "radio_system_name");
            if("radio_system".equals(text(row, "source_kind"))) copyName(row, identity, "label");
            if("system".equals(text(row, "kind")) && row.has("id")) copyName(row, identity, "label");
        }
        String configuredSystem = text(row, "system");
        if(!row.hasNonNull("system_name") && configuredSystem != null &&
            !RadioSystemKey.isCanonical(configuredSystem) && !configuredSystem.matches("[0-9]+"))
            row.put("system_name", configuredSystem);

        addSystem(row, "home", number(row, "home_wacn", "canonical_wacn"),
            number(row, "home_system_id", "home_system", "canonical_system_id"));
        addSystem(row, "foreign", number(row, "foreign_wacn"), number(row, "foreign_system_id"));
        addSystem(row, "source_home", number(row, "source_home_wacn"),
            number(row, "source_home_system_id", "source_home_system"));
        addSystem(row, "target_home", number(row, "target_home_wacn"),
            number(row, "target_home_system_id", "target_home_system"));
        qualified(row, "source_value", "source_home", "source_radio_id");
        qualified(row, "source_id", "source_home", "source_radio_id");
        qualified(row, "destination_value", "target_home", "target_native_id");
        qualified(row, "target_id", "target_home", "target_native_id");
        if(row.has("source_value") && row.has("source_home_system"))
        {
            row.set("home_system", row.get("source_home_system"));
            if(row.has("source_home_system_name")) row.set("home_system_name", row.get("source_home_system_name"));
        }
        if(row.has("positions") && row.has("identifier"))
        {
            qualified(row, "identifier", "home", "radio_id");
            if(identity != null) row.set("serving_system", identity.deepCopy());
        }
        if(row.has("scope") && row.hasNonNull("system_name"))
        {
            String name = text(row, "system_name");
            String channelName = text(row, "channel_name");
            String display = channelName != null && !channelName.equalsIgnoreCase(name) ?
                name + " · " + channelName : name;
            row.put("display_scope", display);
            row.put("display_label", display);
        }
        if(row.hasNonNull("foreign_system_name") && row.has("band") && row.has("label"))
            row.put("label", "Foreign band " + row.path("band").asText() + " · " +
                row.path("foreign_system_name").asText());
        if(row.hasNonNull("home_system_name") && row.has("detail") && row.has("native_id"))
        {
            String detail = row.path("detail").asText();
            int rawHome = detail.indexOf(" · Home WACN ");
            if(rawHome >= 0) row.put("detail", detail.substring(0, rawHome) + " · Home: " +
                row.path("home_system_name").asText());
        }
    }

    private void qualified(ObjectNode row, String field, String prefix, String idField) throws SQLException
    {
        String value = text(row, field);
        if(value == null) return;
        Matcher dotted = LEGACY_RADIO.matcher(value);
        Matcher legacyWorking = LEGACY_WORKING_RADIO.matcher(value);
        Matcher formatted = FORMATTED_RADIO.matcher(value);
        try
        {
            int wacn;
            int system;
            int subscriber;
            if(value.startsWith("v1-"))
            {
                RadioSystemIdentityKey.Identity canonical = RadioSystemIdentityKey.parse(value);
                if(!canonical.hasHome()) return;
                wacn = canonical.homeWacn();
                system = canonical.homeSystemId();
                subscriber = canonical.identityId();
            }
            else if(formatted.matches())
            {
                wacn = Integer.parseInt(formatted.group(1), 16);
                system = Integer.parseInt(formatted.group(2), 16);
                subscriber = Integer.parseInt(formatted.group(3));
                if(formatted.group(4) != null && !validWorkingId(formatted.group(4))) return;
                if(dotted.matches())
                {
                    int decimalWacn = Integer.parseInt(dotted.group(1));
                    int decimalSystem = Integer.parseInt(dotted.group(2));
                    if(wacn != decimalWacn || system != decimalSystem)
                    {
                        //A bare all-digit fixed-width tuple can be current hexadecimal or legacy decimal.
                        //Only numeric origin facts can resolve its base; inventory membership cannot prove it.
                        Integer homeWacn = number(row, prefix + "_wacn");
                        Integer homeSystem = number(row, prefix + "_system_id");
                        if(homeWacn == null || homeSystem == null) return;
                        if(homeWacn == decimalWacn && homeSystem == decimalSystem)
                        {
                            wacn = decimalWacn;
                            system = decimalSystem;
                        }
                        else if(homeWacn != wacn || homeSystem != system) return;
                    }
                }
                new P25SubscriberIdentity(wacn, system, subscriber);
            }
            else if(dotted.matches())
            {
                wacn = Integer.parseInt(dotted.group(1));
                system = Integer.parseInt(dotted.group(2));
                subscriber = Integer.parseInt(dotted.group(3));
                new P25SubscriberIdentity(wacn, system, subscriber);
            }
            else if(legacyWorking.matches())
            {
                if(!validWorkingId(legacyWorking.group(1))) return;
                wacn = Integer.parseInt(legacyWorking.group(2));
                system = Integer.parseInt(legacyWorking.group(3));
                subscriber = Integer.parseInt(legacyWorking.group(4));
                new P25SubscriberIdentity(wacn, system, subscriber);
            }
            else return;

            Integer homeWacn = number(row, prefix + "_wacn");
            Integer homeSystem = number(row, prefix + "_system_id");
            if(homeWacn != null && homeWacn != wacn || homeSystem != null && homeSystem != system) return;
            if(!row.hasNonNull(idField)) row.put(idField, subscriber);
            row.put(prefix + "_wacn", wacn);
            row.put(prefix + "_system_id", system);
            addSystem(row, prefix, wacn, system);
        }
        catch(IllegalArgumentException ignored) { /* Malformed retained text has no trustworthy native identity. */ }
    }

    private static boolean validWorkingId(String value)
    {
        int workingId = Integer.parseInt(value);
        return workingId > 0 && workingId <= RadioSystemIdentityKey.MAX_P25_WORKING_UNIT_ID;
    }

    private void addSystem(ObjectNode row, String prefix, Integer wacn, Integer systemId) throws SQLException
    {
        if(wacn == null || systemId == null) return;
        ObjectNode identity = system(RadioSystemKey.p25(wacn, systemId));
        if(identity == null) return;
        row.put(prefix + "_system_id", systemId);
        row.set(prefix + "_system", identity.deepCopy());
        copyName(row, identity, prefix + "_system_name");
        row.set(prefix + "_radio_system_key", identity.get("radio_system_key"));
        row.set(prefix + "_system_key", identity.get("radio_system_key"));
        if(identity.has("radio_system_entity_ref"))
        {
            row.set(prefix + "_radio_system_entity_ref", identity.get("radio_system_entity_ref"));
            row.set(prefix + "_system_entity_ref", identity.get("radio_system_entity_ref"));
        }
    }

    private ObjectNode system(String key) throws SQLException
    {
        if(key == null) return null;
        try { key = RadioSystemKey.parse(key); }
        catch(IllegalArgumentException ignored) { return null; }
        if(mSystems.containsKey(key)) return mSystems.get(key);
        if(mSystems.size() >= MAXIMUM_LOOKUPS) return null;
        ObjectNode identity = MAPPER.createObjectNode();
        identity.put("radio_system_key", key);
        identity.put("key", key);
        String[] parts = key.split(":");
        if(RadioSystemKey.isP25Native(key))
        {
            identity.put("protocol", "p25");
            identity.put("wacn", Integer.parseInt(parts[1], 16));
            identity.put("system_id", Integer.parseInt(parts[2], 16));
        }
        else if(key.startsWith("dmr:tier3:"))
        {
            identity.put("protocol", "dmr");
            identity.put("model", parts[2]);
            identity.put("network_id", Integer.parseInt(parts[3]));
        }
        else if(key.startsWith("nxdn-c:") && !RadioSystemKey.isChannelScoped(key))
        {
            identity.put("protocol", "nxdn");
            identity.put("location_category", parts[1]);
            identity.put("system_id", Integer.parseInt(parts[2]));
        }
        else identity.put("protocol", key.startsWith("dmr:") ? "dmr" : "nxdn");
        List<Map<String,Object>> systems = queryRows(mConnection,
            "SELECT id,configuration_id FROM radio_system WHERE system_key=?", key);
        if(!systems.isEmpty())
        {
            Map<String,Object> system = systems.getFirst();
            List<Map<String,Object>> rows = queryRows(mConnection, """
                SELECT min(trim(config.system_name)) AS system_name
                FROM configuration_channel config
                WHERE config.configuration_id IN (
                    SELECT channel.configuration_id FROM receiver_channel channel WHERE channel.radio_system_id=?
                    UNION SELECT ?)
                  AND nullif(trim(config.system_name),'') IS NOT NULL
                GROUP BY lower(trim(config.system_name)) ORDER BY lower(system_name),system_name LIMIT 16
                """, system.get("id"), system.get("configuration_id"));
            List<String> names = rows.stream().map(row -> String.valueOf(row.get("system_name"))).toList();
            if(!names.isEmpty())
            {
                identity.put("system_name", String.join(" / ", names));
                identity.put("name", String.join(" / ", names));
                identity.set("system_names", MAPPER.valueToTree(names));
            }
            identity.set("radio_system_entity_ref", MAPPER.valueToTree(WebEntityRef.radioSystem(key).toMap()));
            identity.set("entity_ref", identity.get("radio_system_entity_ref"));
        }
        mSystems.put(key, identity);
        return identity;
    }

    private ObjectNode channel(String configuration) throws SQLException
    {
        if(configuration == null || !CONFIGURATION.matcher(configuration).matches()) return null;
        if(mChannels.containsKey(configuration)) return mChannels.get(configuration);
        if(mChannels.size() >= MAXIMUM_LOOKUPS) return null;
        List<Map<String,Object>> rows = queryRows(mConnection, """
            SELECT config.configuration_id,config.name AS channel_name,config.site_name,config.system_name,
                system.system_key AS radio_system_key
            FROM configuration_channel config
            LEFT JOIN receiver_channel channel ON channel.configuration_id=config.configuration_id
            LEFT JOIN radio_system system ON system.id=channel.radio_system_id
            WHERE config.configuration_id=?
            """, configuration);
        ObjectNode result = rows.isEmpty() ? null : MAPPER.valueToTree(rows.getFirst());
        mChannels.put(configuration, result);
        return result;
    }

    private static String nativeKey(ObjectNode row)
    {
        Integer wacn = number(row, "wacn");
        Integer system = number(row, "system_id", "system");
        if(wacn != null && system != null) return RadioSystemKey.p25(wacn, system);
        String protocol = textOrEmpty(row, "protocol").toLowerCase(Locale.ROOT);
        if(protocol.isBlank())
        {
            Integer code = number(row, "protocol_code");
            if(code != null) protocol = StatsApiProtocol.fromCode(code).wireName();
        }
        if(protocol.equals("dmr"))
        {
            String model = text(row, "model");
            Integer code = number(row, "dmr_model_code", "model_code");
            if(model == null && code != null) model = StatsApiProtocol.DMR.siteClassification(code);
            return RadioSystemKey.dmrTier3(model, number(row, "network_id"));
        }
        if(protocol.equals("nxdn"))
        {
            String category = text(row, "location_category");
            Integer code = number(row, "nxdn_location_category_code", "location_category_code");
            if(category == null && code != null) category = StatsApiProtocol.NXDN.siteClassification(code);
            return RadioSystemKey.nxdnTypeC(category, system);
        }
        return null;
    }

    private static String nestedKey(ObjectNode row, String field)
    {
        return row.get(field) instanceof ObjectNode nested ? text(nested, "radio_system_key", "key") : null;
    }

    private static void copyAbsent(ObjectNode target, ObjectNode source, String... fields)
    {
        for(String field: fields)
            if((!target.hasNonNull(field) || target.path(field).isTextual() && target.path(field).asText().isBlank()) &&
                source.hasNonNull(field)) target.set(field, source.get(field));
    }

    private static void copyName(ObjectNode target, ObjectNode source, String field)
    {
        if(source.hasNonNull("system_name")) target.set(field, source.get("system_name"));
    }

    private static Integer number(ObjectNode row, String... fields)
    {
        for(String field: fields)
        {
            JsonNode value = row.get(field);
            if(value == null || value.isNull()) continue;
            try
            {
                int result = Integer.parseInt(value.asText());
                if(result >= 0) return result;
            }
            catch(NumberFormatException ignored) { }
        }
        return null;
    }

    private static String text(ObjectNode row, String... fields)
    {
        for(String field: fields)
        {
            JsonNode value = row.get(field);
            if(value != null && value.isTextual() && !value.asText().isBlank()) return value.asText().strip();
        }
        return null;
    }

    private static String textOrEmpty(ObjectNode row, String field)
    {
        String value = text(row, field);
        return value != null ? value : "";
    }
}
