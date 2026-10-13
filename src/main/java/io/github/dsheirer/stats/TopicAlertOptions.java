package io.github.dsheirer.stats;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reuses the configured web channel and system-name projections for rule source choices. */
final class TopicAlertOptions
{
    static Map<String,Object> read(Path database)
    {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("aliases", List.of()); // Alias/radio/talkgroup selectors use complete server-side name search.
        result.put("protocols", Arrays.stream(Protocol.values()).filter(value -> value.isActive() && value != Protocol.UNKNOWN)
            .map(value -> option(value.name(), value.toString(), "")).toList());
        result.put("message_types", Arrays.stream(DecodeEventType.values()).filter(value -> value != DecodeEventType.UNKNOWN)
            .map(value -> option(value.name(), value.getLabel(), "")).toList());
        result.put("tone_types", Arrays.stream(AmbeTone.values()).map(value -> option(value.name(), value.toString(), "")).toList());
        result.put("dcs_codes", Arrays.stream(DCSCode.values()).filter(value -> value != DCSCode.UNKNOWN)
            .map(value -> option(value.name(), value.toString(), "")).toList());
        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            List<Map<String,Object>> systems = StatsSqlRows.queryRows(connection, "SELECT system.system_key," +
                StatsSystemNameResolver.configuredNameSql("system") + " AS system_name FROM radio_system system ORDER BY system_name,system.system_key");
            result.put("systems", systems.stream().map(row -> option(text(row, "system_key"),
                named(row, "system_name", "system_key"), text(row, "system_key"))).toList());
            List<Map<String,Object>> channels = StatsSqlRows.queryRows(connection,
                WebConfiguredEntityRepository.CONFIGURED_CHANNEL_SELECT + " ORDER BY config.sort_order,config.id");
            result.put("channels", channels.stream().map(row -> option(text(row, "configuration_id"),
                channelLabel(row), text(row, "radio_system_key"))).toList());
            List<Map<String,Object>> sites = StatsSqlRows.queryRows(connection, """
                SELECT DISTINCT system.system_key, CAST(site.rfss AS TEXT)||':'||CAST(site.site AS TEXT) AS site_id,
                    config.site_name
                FROM p25_site_snapshot site JOIN receiver_channel channel ON channel.id=site.channel_id
                JOIN radio_system system ON system.id=channel.radio_system_id
                JOIN configuration_channel config ON config.configuration_id=channel.configuration_id
                WHERE site.rfss IS NOT NULL AND site.site IS NOT NULL
                UNION
                SELECT DISTINCT system.system_key, CAST(site.observed_site_id AS TEXT) AS site_id, config.site_name
                FROM trunked_site_snapshot site JOIN receiver_channel channel ON channel.id=site.channel_id
                JOIN radio_system system ON system.id=channel.radio_system_id
                JOIN configuration_channel config ON config.configuration_id=channel.configuration_id
                WHERE site.observed_site_id IS NOT NULL
                ORDER BY site_name,site_id
                """);
            result.put("sites", sites.stream().map(row -> option(text(row, "site_id"),
                named(row, "site_name", "site_id"), text(row, "system_key"))).toList());
        }
        catch(Exception exception)
        {
            throw new IllegalStateException("Alert source choices are temporarily unavailable");
        }
        return result;
    }

    private static Map<String,Object> option(String value, String label, String system)
    {
        return Map.of("value", value, "label", label, "system_key", system);
    }
    private static String named(Map<String,Object> row, String name, String fallback)
    {
        String label = text(row, name); return label.isEmpty() ? text(row, fallback) : label;
    }
    private static String channelLabel(Map<String,Object> row)
    {
        List<String> parts = new ArrayList<>();
        for(String key: List.of("name", "system_name", "site_name")) if(!text(row, key).isEmpty()) parts.add(text(row, key));
        return parts.isEmpty() ? text(row, "configuration_id") : String.join(" · ", parts);
    }
    private static String text(Map<String,Object> row, String key) { return row.get(key) == null ? "" : String.valueOf(row.get(key)); }
}
