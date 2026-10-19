package io.github.dsheirer.stats;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.alerts.AlertProtocolFamily;
import io.github.dsheirer.identifier.tone.AmbeTone;
import io.github.dsheirer.audio.tone.PagingToneFrequencyMapping;
import io.github.dsheirer.module.decode.dcs.DCSCode;
import io.github.dsheirer.stats.activity.ActivityMessageCatalog;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.service.radioreference.RadioSystemNameCatalog;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Reuses the configured web channel and system-name projections for rule source choices. */
final class TopicAlertOptions
{
    static Map<String,Object> read(Path database)
    {
        return read(database, RadioSystemNameCatalog.bundled());
    }

    static Map<String,Object> read(Path database, RadioSystemNameCatalog systemNames)
    {
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("aliases", List.of()); // Alias/radio/talkgroup selectors use complete server-side name search.
        result.put("protocol_families", List.of("P25", "DMR", "NXDN", "AM", "NBFM").stream().map(family -> option(family, family, "")).toList());
        result.put("protocols", Arrays.stream(Protocol.values()).filter(value -> value.isActive() && value != Protocol.UNKNOWN)
            .map(TopicAlertOptions::protocolOption).toList());
        List<Map<String,Object>> tones = new ArrayList<>(Arrays.stream(AmbeTone.values())
            .map(value -> option(value.name(), value.toString(), "")).toList());
        var toneCodes = new LinkedHashSet<>(Arrays.stream(AmbeTone.values()).map(Enum::name).toList());
        for(var nominal: PagingToneFrequencyMapping.nominals())
        {
            if(toneCodes.add(nominal.code()))
            {
                Map<String,Object> tone = new LinkedHashMap<>(option(nominal.code(),
                    String.format(java.util.Locale.ROOT, "TONE %.2f", nominal.frequencyHz()), ""));
                tone.put("analog_only", true);
                tones.add(tone);
            }
        }
        result.put("tone_types", List.copyOf(tones));
        result.put("dcs_codes", Arrays.stream(DCSCode.values()).filter(value -> value != DCSCode.UNKNOWN)
            .map(value -> option(value.name(), value.toString(), "")).toList());
        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            result.put("message_types", ActivityMessageCatalog.read(connection).stream()
                .filter(row -> "EVENT".equals(row.get("definition_kind")) && !"UNKNOWN".equals(row.get("name")))
                .map(row -> {
                    Map<String,Object> message = new LinkedHashMap<>(option(text(row, "name"), text(row, "label"), ""));
                    message.put("meaning", row.get("meaning"));
                    message.put("p25", row.get("p25"));
                    message.put("dmr", row.get("dmr"));
                    message.put("nxdn", row.get("nxdn"));
                    return message;
                }).toList());
            StatsSystemNameResolver.installCatalog(connection, systemNames);
            List<Map<String,Object>> systems = StatsSqlRows.queryRows(connection, "SELECT system.system_key,system.protocol_code," +
                StatsSystemNameResolver.displayNameSql("system") + " AS system_name FROM radio_system system ORDER BY system_name,system.system_key");
            result.put("systems", systems.stream().map(row -> sourceOption(text(row, "system_key"),
                named(row, "system_name", "system_key"), text(row, "system_key"), StatsApiProtocol.fromCode(((Number)row.get("protocol_code")).longValue()).name())).toList());
            List<Map<String,Object>> channels = StatsSqlRows.queryRows(connection,
                WebConfiguredEntityRepository.CONFIGURED_CHANNEL_SELECT + " ORDER BY config.sort_order,config.id");
            StatsSystemNameResolver names = new StatsSystemNameResolver(connection, systemNames);
            for(int index = 0; index < channels.size(); index++) channels.set(index, names.enrichMap(channels.get(index)));
            result.put("channels", channels.stream().map(row -> {
                Map<String,Object> option = sourceOption(text(row, "configuration_id"), channelLabel(row),
                    text(row, "radio_system_key"), AlertProtocolFamily.fromDecoder(text(row, "decoder")));
                option.put("channel_kind", text(row, "channel_kind")); option.put("decoder_type", text(row, "decoder"));
                option.put("system_name", text(row, "system_name")); option.put("site_name", text(row, "site_name"));
                return option;
            }).toList());
            List<Map<String,Object>> sites = StatsSqlRows.queryRows(connection, """
                SELECT DISTINCT system.system_key,system.protocol_code,config.configuration_id, CAST(site.rfss AS TEXT)||':'||CAST(site.site AS TEXT) AS site_id,
                    config.site_name
                FROM p25_site_snapshot site JOIN receiver_channel channel ON channel.id=site.channel_id
                JOIN radio_system system ON system.id=channel.radio_system_id
                JOIN configuration_channel config ON config.configuration_id=channel.configuration_id
                WHERE site.rfss IS NOT NULL AND site.site IS NOT NULL
                UNION
                SELECT DISTINCT system.system_key,system.protocol_code,config.configuration_id, CAST(site.observed_site_id AS TEXT) AS site_id, config.site_name
                FROM trunked_site_snapshot site JOIN receiver_channel channel ON channel.id=site.channel_id
                JOIN radio_system system ON system.id=channel.radio_system_id
                JOIN configuration_channel config ON config.configuration_id=channel.configuration_id
                WHERE site.observed_site_id IS NOT NULL
                ORDER BY site_name,site_id
                """);
            result.put("sites", sites.stream().map(row -> {
                Map<String,Object> option = sourceOption(text(row, "site_id"), named(row, "site_name", "site_id"),
                    text(row, "system_key"), StatsApiProtocol.fromCode(((Number)row.get("protocol_code")).longValue()).name());
                option.put("channel_id", text(row, "configuration_id")); return option;
            }).toList());
        }
        catch(Exception exception)
        {
            throw new IllegalStateException("Alert source choices are temporarily unavailable");
        }
        return result;
    }

    private static Map<String,Object> sourceOption(String value, String label, String system, String family)
    {
        Map<String,Object> option = new LinkedHashMap<>(option(value, label, system));
        option.put("protocol_family", AlertProtocolFamily.FAMILIES.contains(family) ? family : ""); return option;
    }
    private static Map<String,Object> protocolOption(Protocol protocol)
    {
        Map<String,Object> option = new LinkedHashMap<>(option(protocol.name(), protocol.toString(), ""));
        List<String> families = switch(protocol)
        {
            case APCO25, APCO25_PHASE2 -> List.of("P25");
            case DMR, ARS, LRRP, IPV4, UDP -> List.of("DMR");
            case NXDN -> List.of("NXDN");
            case AM -> List.of("AM");
            case NBFM -> List.of("NBFM");
            case DCS, FLEETSYNC, LOJACK, MDC1200, TAIT1200 -> List.of("AM", "NBFM");
            default -> List.of();
        };
        option.put("protocol_families", families); return option;
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
