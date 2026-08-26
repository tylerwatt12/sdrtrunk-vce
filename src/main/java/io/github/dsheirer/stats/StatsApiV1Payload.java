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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Stable API presentation boundary for database maps and decoder records.
 */
final class StatsApiV1Payload
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> INTERNAL_FIELDS = Set.of(
        "scope_id", "context_id", "system_key", "p25_system_key", "resolved_system_key", "site_type",
        "protocol_code",
        "scope_kind_code", "variant_code",
        "identity_domain_code", "identity_kind_code", "target_kind_code", "last_talkgroup_kind_code",
        "last_counterpart_kind_code", "p25_identity_state_code", "p25_home_wacn", "p25_home_system_id",
        "p25_home_talkgroup_id", "channel_kind_code", "kind_code", "identity_role_code", "model_code",
        "brand_code", "mode_code", "channel_type_code", "service_flags", "failure_code", "role_flags",
        "status_flags", "last_event_type_code");
    private static final Set<String> LEGACY_METRIC_FIELDS = Set.of(
        "call_count", "source_call_count", "target_call_count", "group_call_count", "private_call_count",
        "recorded_count", "streamed_count", "encrypted_count", "grant_count", "join_count",
        "register_count", "active_count", "continue_count", "denial_count", "acknowledge_count",
        "emergency_count", "request_count", "queued_count", "busy_count", "check_count",
        "check_ack_count", "page_count", "status_count", "gps_count", "logout_count", "patch_count",
        "patch_create_count", "patch_cancel_count", "data_count", "unknown_count", "signaling_count",
        "other_signaling_count", "encrypted_evidence_count", "event_count", "total_event_count",
        "activity_retained_calls", "activity_calls", "activity_recorded", "activity_streamed",
        "activity_encrypted");
    private static final Set<String> BOOLEAN_FIELDS = Set.of(
        "data_service", "registration_service", "tdma", "voice_service", "encrypted", "record_enabled",
        "ranged", "exact", "overlap", "has_fdma", "has_tdma", "has_unknown", "detail_available",
        "identity_detail_available", "site_names_truncated", "talkgroups_truncated", "radios_truncated",
        "members_truncated", "channel_key_truncated", "descriptor_truncated", "callsign_truncated",
        "logical_channels_truncated", "currently_affiliated");
    private static final Set<String> ENUM_FIELDS = Set.of(
        "type", "category", "event_type", "source_form", "target_form", "state", "metrics_state",
        "identity_role", "channel_kind", "site_kind", "action", "topology", "entry_type");

    private StatsApiV1Payload()
    {
    }

    static JsonNode present(Object value)
    {
        return transform(OBJECT_MAPPER.valueToTree(value), StatsApiProtocol.UNKNOWN);
    }

    private static JsonNode transform(JsonNode value, StatsApiProtocol inheritedProtocol)
    {
        if(value == null || value.isNull() || value.isValueNode())
        {
            return value;
        }
        else if(value.isArray())
        {
            ArrayNode array = OBJECT_MAPPER.createArrayNode();
            value.forEach(item -> array.add(transform(item, inheritedProtocol)));
            return array;
        }

        ObjectNode source = (ObjectNode)value;
        StatsApiProtocol protocol = protocol(source, inheritedProtocol);
        String rawProtocol = source.path("protocol").isTextual() ? source.path("protocol").textValue() : null;
        boolean aliasRecord = source.has("alias_list_id") && source.has("matcher_type");
        boolean identityRecord = source.has("scope_kind_code") || source.has("identity_kind_code") ||
            source.has("target_kind_code") || source.has("p25_identity_state_code") ||
            source.has("talkgroup_id") || source.has("radio_id") || source.has("identity_id");
        boolean siteRecord = !identityRecord && (source.has("variant_code") || source.has("guid")) &&
            (source.has("site_id") || source.has("ran") || source.has("rfss") || source.has("site"));
        boolean protocolRecord = source.has("protocol_code") || source.has("protocol") || identityRecord ||
            siteRecord || source.has("scope_token") || source.has("context_key");
        ObjectNode presented = OBJECT_MAPPER.createObjectNode();
        Iterator<Map.Entry<String,JsonNode>> fields = source.fields();

        while(fields.hasNext())
        {
            Map.Entry<String,JsonNode> field = fields.next();
            String name = field.getKey();

            if(INTERNAL_FIELDS.contains(name) || LEGACY_METRIC_FIELDS.contains(name))
            {
                continue;
            }

            if("protocol".equals(name) && field.getValue().isTextual())
            {
                presented.put(name, protocol.wireName());
            }
            else if("family".equals(name) && field.getValue().isTextual())
            {
                presented.put(name, aliasFamily(field.getValue().textValue()));
            }
            else if("matcher_type".equals(name) && field.getValue().isTextual())
            {
                presented.put(name, aliasMatcherType(field.getValue().textValue()));
            }
            else if(Set.of("identity_kind", "target_kind", "last_talkgroup_kind", "last_counterpart_kind")
                .contains(name) && field.getValue().isTextual())
            {
                presented.put(name, identityKind(field.getValue().textValue()));
            }
            else if("channel_kind".equals(name) && field.getValue().isTextual())
            {
                presented.put(name, enumName(field.getValue().textValue()));
            }
            else if("variant".equals(name) && field.getValue().isTextual())
            {
                presented.put(name, protocol.variant(field.getValue().textValue()));
            }
            else if(ENUM_FIELDS.contains(name) && field.getValue().isTextual())
            {
                presented.put(name, enumName(field.getValue().textValue()));
            }
            else if(BOOLEAN_FIELDS.contains(name) && field.getValue().isNumber())
            {
                presented.put(name, field.getValue().longValue() != 0);
            }
            else
            {
                presented.set(name, transform(field.getValue(), protocol));
            }
        }

        if(protocol == StatsApiProtocol.P25 && source.has("site") && source.path("site").isValueNode() &&
            !source.path("site").isNull())
        {
            presented.set("site_id", transform(source.get("site"), protocol));
            presented.remove("site");
        }

        if(protocol != StatsApiProtocol.UNKNOWN && protocolRecord)
        {
            presented.put("protocol", protocol.wireName());
        }

        if(aliasRecord && protocol == StatsApiProtocol.P25)
        {
            presented.put("protocol_variant", "APCO25_PHASE2".equals(rawProtocol) ? "phase_2" : "phase_1");
        }

        if(source.get("scope_kind_code") instanceof JsonNode scopeKind && scopeKind.isNumber())
        {
            presented.put("scope_kind", protocol.scopeKind(scopeKind.longValue()));
        }

        if((protocol == StatsApiProtocol.DMR || protocol == StatsApiProtocol.NXDN) &&
            source.get("variant_code") instanceof JsonNode variant && variant.isNumber())
        {
            presented.put("variant", protocol.variant(variant.longValue()));
        }

        putIdentityKind(source, presented, "identity_kind_code", "identity_kind");
        putIdentityKind(source, presented, "target_kind_code", "target_kind");
        putIdentityKind(source, presented, "last_talkgroup_kind_code", "last_talkgroup_kind");
        putIdentityKind(source, presented, "last_counterpart_kind_code", "last_counterpart_kind");

        JsonNode channelKind = source.get("channel_kind_code");

        if(channelKind != null && channelKind.isNumber() && !presented.has("channel_kind"))
        {
            presented.put("channel_kind", channelKind.intValue() == 1 ? "trunked" : "conventional");
        }

        JsonNode contextKind = source.get("kind_code");

        if(contextKind != null && contextKind.isNumber())
        {
            presented.put("context_kind", contextKind.intValue() == 1 ? "trunked" : "conventional");
        }

        if(source.get("identity_domain_code") instanceof JsonNode domain && domain.isNumber())
        {
            if(identityRecord || !siteRecord && source.has("scope_token"))
            {
                String addressDomain = protocol.addressDomain(domain.longValue());
                presented.put("address_domain", addressDomain);
                addNxdnDisplays(presented, addressDomain);
            }
            else if(protocol == StatsApiProtocol.DMR)
            {
                presented.put("model", protocol.siteClassification(domain.longValue()));
            }
            else if(protocol == StatsApiProtocol.NXDN)
            {
                presented.put("location_category", protocol.siteClassification(domain.longValue()));
            }
        }

        addProtocolSiteFields(source, presented, protocol);
        addChannelFields(source, presented);
        addNeighborFields(source, presented);
        addLastEventType(source, presented);

        addP25Qualification(source, presented, protocol);

        if(presented.has("sourceActivity24h"))
        {
            presented.set("source_activity_24h", presented.remove("sourceActivity24h"));
        }

        return presented;
    }

    private static void putIdentityKind(ObjectNode source, ObjectNode presented, String codeField, String field)
    {
        JsonNode code = source.get(codeField);

        if(code != null && code.isNumber())
        {
            presented.put(field, identityKind(code.intValue()));
        }
    }

    private static String identityKind(int code)
    {
        return switch(code)
        {
            case 1 -> "talkgroup";
            case 2 -> "radio";
            case 3 -> "patch_group";
            default -> "unknown";
        };
    }

    private static String identityKind(String value)
    {
        String normalized = enumName(value);
        return switch(normalized)
        {
            case "talkgroup", "radio", "patch_group" -> normalized;
            case "channel_unknown" -> "unknown";
            default -> normalized.isBlank() ? "unknown" : normalized;
        };
    }

    private static String enumName(String value)
    {
        return value != null ? value.strip().toLowerCase().replace('-', '_').replace(' ', '_') : "";
    }

    static String aliasFamily(String value)
    {
        return switch(value != null ? value : "")
        {
            case "P25" -> "p25";
            case "DMR" -> "dmr";
            case "NXDN" -> "nxdn";
            case "NBFM" -> "nbfm";
            case "AM" -> "am";
            default -> "unknown";
        };
    }

    static String aliasMatcherType(String value)
    {
        return switch(value != null ? value : "")
        {
            case "TALKGROUP" -> "talkgroup";
            case "TALKGROUP_RANGE" -> "talkgroup_range";
            case "RADIO_ID" -> "radio";
            case "RADIO_ID_RANGE" -> "radio_range";
            case "STATUS" -> "user_status";
            case "UNIT_STATUS" -> "unit_status";
            case "TONES" -> "tone_sequence";
            case "DCS" -> "dcs";
            case "ESN" -> "esn";
            default -> "unknown";
        };
    }

    private static void addP25Qualification(ObjectNode source, ObjectNode presented, StatsApiProtocol protocol)
    {
        JsonNode stateCode = source.get("p25_identity_state_code");

        if(protocol != StatsApiProtocol.P25 || stateCode == null || !stateCode.isNumber())
        {
            return;
        }

        int code = stateCode.intValue();
        ObjectNode qualification = OBJECT_MAPPER.createObjectNode();
        qualification.put("state", switch(code)
        {
            case 1 -> "ordinary";
            case 2 -> "stable_fully_qualified";
            case 3 -> "ambiguous";
            default -> "unknown";
        });

        if(code == 2 && source.get("p25_home_wacn") instanceof JsonNode wacn && wacn.isIntegralNumber() &&
            source.get("p25_home_system_id") instanceof JsonNode system && system.isIntegralNumber() &&
            source.get("p25_home_talkgroup_id") instanceof JsonNode talkgroup && talkgroup.isIntegralNumber())
        {
            ObjectNode home = OBJECT_MAPPER.createObjectNode();
            home.set("wacn", wacn);
            home.set("system_id", system);
            home.set("talkgroup_id", talkgroup);
            qualification.set("home", home);
        }

        presented.set("qualification", qualification);
    }

    private static void addProtocolSiteFields(ObjectNode source, ObjectNode presented, StatsApiProtocol protocol)
    {
        JsonNode brand = source.get("brand_code");
        if(protocol == StatsApiProtocol.DMR && brand != null && brand.isNumber())
        {
            presented.put("brand", protocol.brand(brand.longValue()));
        }

        JsonNode model = source.get("model_code");
        if(protocol == StatsApiProtocol.DMR && model != null && model.isNumber())
        {
            presented.put("model", protocol.siteClassification(model.longValue()));
        }

        JsonNode mode = source.get("mode_code");
        if(mode != null && mode.isNumber())
        {
            if(protocol == StatsApiProtocol.DMR)
            {
                presented.put("mode", protocol.operatingMode(mode.longValue()));
            }
            else if(protocol == StatsApiProtocol.NXDN)
            {
                presented.put("repeater_state", protocol.operatingMode(mode.longValue()));
            }
        }

        JsonNode channelType = source.get("channel_type_code");
        if(channelType != null && channelType.isNumber())
        {
            if(protocol == StatsApiProtocol.DMR)
            {
                presented.put("channel_type", protocol.channelType(channelType.longValue()));
            }
            else if(protocol == StatsApiProtocol.P25)
            {
                addP25ChannelPlan(presented, channelType.intValue());
            }
        }

        JsonNode services = source.get("service_flags");
        if(protocol == StatsApiProtocol.NXDN && services != null && services.isNumber())
        {
            presented.set("services", OBJECT_MAPPER.valueToTree(protocol.services(services.longValue())));
        }

        JsonNode failure = source.get("failure_code");
        if(protocol == StatsApiProtocol.NXDN && failure != null && failure.isNumber())
        {
            if(failure.longValue() > 0)
            {
                presented.put("failure_call_timer_seconds", failure.longValue());
            }
            else
            {
                presented.putNull("failure_call_timer_seconds");
            }
        }
    }

    private static void addP25ChannelPlan(ObjectNode presented, int code)
    {
        String accessMode;
        int bandwidth;
        int timeslots;
        String voiceRate;

        switch(code)
        {
            case 0 -> { accessMode = "fdma"; bandwidth = 12_500; timeslots = 1; voiceRate = "half"; }
            case 1 -> { accessMode = "fdma"; bandwidth = 12_500; timeslots = 1; voiceRate = "full"; }
            case 2 -> { accessMode = "fdma"; bandwidth = 6_250; timeslots = 1; voiceRate = "half"; }
            case 3 -> { accessMode = "tdma"; bandwidth = 12_500; timeslots = 2; voiceRate = "half"; }
            case 4 -> { accessMode = "tdma"; bandwidth = 25_000; timeslots = 4; voiceRate = "half"; }
            case 5 -> { accessMode = "tdma_h_d8psk"; bandwidth = 12_500; timeslots = 2; voiceRate = "half"; }
            default -> { accessMode = "unknown"; bandwidth = 0; timeslots = 0; voiceRate = "unknown"; }
        }

        presented.put("access_mode", accessMode);
        if(bandwidth > 0)
        {
            presented.put("bandwidth_hz", bandwidth);
            presented.put("timeslots", timeslots);
        }
        presented.put("voice_rate", voiceRate);
    }

    private static void addChannelFields(ObjectNode source, ObjectNode presented)
    {
        JsonNode flags = source.get("role_flags");
        if(flags == null || !flags.isNumber())
        {
            return;
        }

        long value = flags.longValue();
        presented.set("roles", strings(value,
            Map.entry(1L, "current_control"), Map.entry(2L, "alternate_control"),
            Map.entry(4L, "traffic")));
        presented.set("sources", strings(value,
            Map.entry(8L, "observed"), Map.entry(16L, "configured_map_frequency"),
            Map.entry(32L, "over_air_frequency")));
    }

    private static void addNeighborFields(ObjectNode source, ObjectNode presented)
    {
        JsonNode flags = source.get("status_flags");
        if(flags != null && flags.isNumber())
        {
            presented.set("statuses", strings(flags.longValue(), Map.entry(1L, "linked"),
                Map.entry(2L, "isolated")));
        }
    }

    @SafeVarargs
    private static ArrayNode strings(long flags, Map.Entry<Long,String>... values)
    {
        ArrayNode result = OBJECT_MAPPER.createArrayNode();
        for(Map.Entry<Long,String> value: values)
        {
            if((flags & value.getKey()) != 0)
            {
                result.add(value.getValue());
            }
        }
        return result;
    }

    private static void addLastEventType(ObjectNode source, ObjectNode presented)
    {
        JsonNode code = source.get("last_event_type_code");
        if(code == null || !code.isIntegralNumber())
        {
            return;
        }

        io.github.dsheirer.module.decode.event.DecodeEventType[] values =
            io.github.dsheirer.module.decode.event.DecodeEventType.values();
        int ordinal = code.intValue() - 1;
        presented.put("last_event_type", ordinal >= 0 && ordinal < values.length ?
            enumName(values[ordinal].name()) : "unknown");
    }

    private static StatsApiProtocol protocol(ObjectNode value, StatsApiProtocol inherited)
    {
        JsonNode code = value.get("protocol_code");

        if(code != null && code.isNumber())
        {
            StatsApiProtocol protocol = StatsApiProtocol.fromCode(code.longValue());

            if(protocol != StatsApiProtocol.UNKNOWN)
            {
                return protocol;
            }
        }

        JsonNode name = value.get("protocol");

        if(name != null && name.isTextual())
        {
            StatsApiProtocol protocol = StatsApiProtocol.fromName(name.textValue());

            if(protocol != StatsApiProtocol.UNKNOWN)
            {
                return protocol;
            }
        }

        return inherited;
    }

    private static void addNxdnDisplays(ObjectNode value, String addressDomain)
    {
        if(!"nxdn_type_d".equals(addressDomain))
        {
            return;
        }

        for(String field: Set.of("identity_id", "talkgroup_id", "radio_id", "source_id", "target_id",
            "last_talkgroup_id", "last_peer_radio_id"))
        {
            JsonNode identifier = value.get(field);

            if(identifier != null && identifier.isIntegralNumber())
            {
                long numeric = identifier.longValue();

                if(numeric >= 0 && numeric <= 0xFFFF)
                {
                    value.put(field + "_display", "%02d-%04d".formatted((numeric >> 11) & 0x1F,
                        numeric & 0x7FF));
                }
            }
        }
    }
}
