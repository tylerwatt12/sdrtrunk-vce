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
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.stats.activity.ReceiverActivityFilterCatalog;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * Stable API presentation boundary for database maps and decoder records.
 */
final class StatsApiV1Payload
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> RAW_ACTION_COUNT_FIELDS = ReceiverActivityFilterCatalog.actions().keySet()
        .stream().map(name -> name.toLowerCase(java.util.Locale.ROOT) + "_count")
        .collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Map<Integer,String> EVENT_TYPE_NAMES = ReceiverActivityFilterCatalog.eventTypes().entrySet()
        .stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));
    private static final Set<String> P25_VISUALIZER_FIELDS = Set.of(
        "id", "configuration_id", "observed_at_ms", "channel_kind", "protocol", "action",
        "source_radio_id", "target_id", "source_identity_key", "target_identity_key",
        "source_native_id", "target_native_id", "source_canonical_identity", "target_canonical_identity",
        "source_canonical_group_identity", "target_canonical_group_identity",
        "source_observed_local_id", "target_observed_local_id",
        "source_observed_working_id", "target_observed_working_id", "target_kind", "serving_system_key",
        "source_alias_name", "target_alias_name", "source_talker_alias", "target_talker_alias",
        "source_home_system_name", "target_home_system_name", "system_name", "system_names",
        "radio_system_key", "radio_system_entity_ref", "wacn", "system_id");
    private static final Set<String> INTERNAL_FIELDS = Set.of(
        "radio_system_id", "channel_id", "identity_summary_id", "radio_identity_summary_id",
        "group_identity_summary_id", "affiliated_talkgroup_identity_summary_id",
        "p25_subscriber_identity_id", "source_p25_subscriber_identity_id", "target_p25_subscriber_identity_id",
        "canonical_wacn", "canonical_system_id", "canonical_subscriber_id",
        "source_canonical_wacn", "source_canonical_system_id", "source_canonical_subscriber_id",
        "target_canonical_wacn", "target_canonical_system_id", "target_canonical_subscriber_id",
        "canonical_talkgroup_id", "source_canonical_talkgroup_id", "target_canonical_talkgroup_id",
        "radio_canonical_wacn", "radio_canonical_system_id", "radio_canonical_subscriber_id",
        "source_identity_summary_id", "target_identity_summary_id",
        "representative_channel_id", "fallback_channel_id", "identity_id", "system_key", "site_type",
        "protocol_code", "variant_code", "site_variant_code",
        "address_domain_code", "location_category_code", "site_location_category_code",
        "dmr_model_code", "nxdn_location_category_code", "site_model_code",
        "identity_kind_code", "source_identity_kind_code", "target_identity_kind_code", "target_kind_code",
        "group_identity_kind_code", "group_identity_kind_label",
        "home_wacn", "home_system_id", "channel_kind_code", "identity_role_code", "model_code",
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
        "active_rfss_network_connection",
        "ranged", "exact", "overlap", "has_fdma", "has_tdma", "has_unknown",
        "channel_names_truncated", "talkgroups_truncated", "radios_truncated",
        "members_truncated", "channel_key_truncated", "descriptor_truncated", "callsign_truncated",
        "logical_channels_truncated", "currently_affiliated");
    private static final Set<String> ENUM_FIELDS = Set.of(
        "type", "category", "event_type", "source_form", "target_form", "state", "metrics_state",
        "identity_role", "channel_kind", "action", "topology", "entry_type");

    private StatsApiV1Payload()
    {
    }

    static JsonNode present(Object value)
    {
        return transform(source(value), StatsApiProtocol.UNKNOWN);
    }

    /** Trim only after shared name/canonical/Alias presentation so the compact graph preserves its labels. */
    static JsonNode p25Visualizer(Object value)
    {
        JsonNode rows = source(value);
        for(JsonNode row: rows)
        {
            if(row instanceof ObjectNode object) object.retain(P25_VISUALIZER_FIELDS);
        }
        return rows;
    }

    /** Identical compact public rows for saved seeds, committed deltas and cursor recovery. */
    static java.util.List<Map<String,Object>> p25VisualizerRows(Object value)
    {
        return OBJECT_MAPPER.convertValue(p25Visualizer(present(value)),
            new com.fasterxml.jackson.core.type.TypeReference<java.util.List<Map<String,Object>>>() { });
    }

    /** Preserves producer field casing before optional read-side enrichment. */
    static JsonNode source(Object value)
    {
        return OBJECT_MAPPER.valueToTree(value);
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
        boolean identityRecord = source.has("identity_kind_code") || source.has("target_kind_code") ||
            source.has("group_identity_kind_code") || source.has("identity_key") || source.has("identity_id");
        boolean channelObservationRecord = !identityRecord && source.has("variant_code") &&
            (source.has("site_id") || source.has("ran") || source.has("rfss"));
        boolean entityReference = entityReference(source);
        boolean protocolRecord = !entityReference && (source.has("protocol_code") || source.has("protocol") ||
            identityRecord || channelObservationRecord || source.has("radio_system_key") ||
            source.has("configuration_id"));
        ObjectNode presented = OBJECT_MAPPER.createObjectNode();
        Iterator<Map.Entry<String,JsonNode>> fields = source.fields();

        while(fields.hasNext())
        {
            Map.Entry<String,JsonNode> field = fields.next();
            String name = field.getKey();

            if(INTERNAL_FIELDS.contains(name) || LEGACY_METRIC_FIELDS.contains(name) || RAW_ACTION_COUNT_FIELDS.contains(name))
            {
                continue;
            }
            else if("protocol".equals(name) && field.getValue().isTextual())
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
            else if(Set.of("identity_kind", "target_kind", "group_identity_kind")
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

        if(protocol != StatsApiProtocol.UNKNOWN && protocolRecord)
        {
            presented.put("protocol", protocol.wireName());
        }

        if(aliasRecord && protocol == StatsApiProtocol.P25)
        {
            presented.put("protocol_variant", "APCO25_PHASE2".equals(rawProtocol) ? "phase_2" : "phase_1");
        }

        if((protocol == StatsApiProtocol.DMR || protocol == StatsApiProtocol.NXDN) &&
            source.get("variant_code") instanceof JsonNode variant && variant.isNumber())
        {
            presented.put("variant", protocol.variant(variant.longValue()));
        }

        if((protocol == StatsApiProtocol.DMR || protocol == StatsApiProtocol.NXDN) &&
            source.get("site_variant_code") instanceof JsonNode variant && variant.isNumber())
        {
            presented.put("site_variant", protocol.variant(variant.longValue()));
        }

        putIdentityKind(source, presented, "identity_kind_code", "identity_kind");
        putIdentityKind(source, presented, "target_kind_code", "target_kind");
        putIdentityKind(source, presented, "group_identity_kind_code", "group_identity_kind");
        addP25CanonicalIdentities(source, presented, protocol);

        JsonNode channelKind = source.get("channel_kind_code");

        if(channelKind != null && channelKind.isNumber() && !presented.has("channel_kind"))
        {
            presented.put("channel_kind", channelKind.intValue() == 1 ? "trunked" : "conventional");
        }

        if(source.get("address_domain_code") instanceof JsonNode domain && domain.isNumber())
        {
            String addressDomain = protocol.addressDomain(domain.longValue());
            presented.put("address_domain", addressDomain);
            addNxdnDisplays(presented, addressDomain);
        }

        if(protocol == StatsApiProtocol.NXDN &&
            source.get("location_category_code") instanceof JsonNode category && category.isNumber())
        {
            presented.put("location_category", protocol.siteClassification(category.longValue()));
        }

        if(protocol == StatsApiProtocol.DMR &&
            source.get("dmr_model_code") instanceof JsonNode model && model.isNumber())
        {
            presented.put("model", protocol.siteClassification(model.longValue()));
        }

        if(protocol == StatsApiProtocol.NXDN &&
            source.get("nxdn_location_category_code") instanceof JsonNode category && category.isNumber())
        {
            presented.put("location_category", protocol.siteClassification(category.longValue()));
        }

        if(protocol == StatsApiProtocol.DMR &&
            source.get("site_model_code") instanceof JsonNode model && model.isNumber())
        {
            presented.put("site_model", protocol.siteClassification(model.longValue()));
        }

        if(protocol == StatsApiProtocol.NXDN &&
            source.get("site_location_category_code") instanceof JsonNode category && category.isNumber())
        {
            presented.put("site_location_category", protocol.siteClassification(category.longValue()));
        }

        addProtocolChannelFields(source, presented, protocol);
        addChannelFields(source, presented);
        addNeighborFields(source, presented);
        addLastEventType(source, presented);

        return presented;
    }

    /**
     * Exposes protocol-native P25 subscriber identities as structured numbers. Formatting belongs to the browser so
     * every surface uses the same WACN/System/subscriber convention, and a working ID is never mistaken for the
     * permanent subscriber ID.
     */
    private static void addP25CanonicalIdentities(ObjectNode source, ObjectNode presented,
                                                   StatsApiProtocol protocol)
    {
        if(protocol != StatsApiProtocol.P25)
        {
            return;
        }
        //Navigation references are opaque keys, not identity observations.
        if(entityReference(source)) return;

        boolean directRadio = code(source, "identity_kind_code") == 2 ||
            code(source, "group_identity_kind_code") == 2 ||
            source.path("identity_key").asText("").startsWith("v1-r-") ||
            source.has("canonical_subscriber_id");
        if(directRadio)
        {
            putCanonicalIdentity(source, presented, "canonical_identity",
                firstIntegral(source, "canonical_wacn"),
                firstIntegral(source, "canonical_system_id"),
                firstIntegral(source, "canonical_subscriber_id"));
        }

        putCanonicalIdentity(source, presented, "source_canonical_identity",
            firstIntegral(source, "source_canonical_wacn"),
            firstIntegral(source, "source_canonical_system_id"),
            firstIntegral(source, "source_canonical_subscriber_id"));

        putCanonicalIdentity(source, presented, "radio_canonical_identity",
            firstIntegral(source, "radio_canonical_wacn"),
            firstIntegral(source, "radio_canonical_system_id"),
            firstIntegral(source, "radio_canonical_subscriber_id"));

        int targetKind = code(source, "target_kind_code");
        if(targetKind == 2 || source.has("target_canonical_subscriber_id"))
        {
            putCanonicalIdentity(source, presented, "target_canonical_identity",
                firstIntegral(source, "target_canonical_wacn"),
                firstIntegral(source, "target_canonical_system_id"),
                firstIntegral(source, "target_canonical_subscriber_id"));
        }

        putGroupIdentity(source, presented, "canonical_group_identity", "identity_key",
            firstIntegral(source, "canonical_wacn"), firstIntegral(source, "canonical_system_id"),
            firstIntegral(source, "canonical_talkgroup_id"));
        putGroupIdentity(source, presented, "source_canonical_group_identity", "source_identity_key",
            firstIntegral(source, "source_canonical_wacn"), firstIntegral(source, "source_canonical_system_id"),
            firstIntegral(source, "source_canonical_talkgroup_id"));
        putGroupIdentity(source, presented, "target_canonical_group_identity", "target_identity_key",
            firstIntegral(source, "target_canonical_wacn", "target_home_wacn"),
            firstIntegral(source, "target_canonical_system_id", "target_home_system_id"),
            firstIntegral(source, "target_canonical_talkgroup_id", "target_home_id"));
        putGroupIdentity(source, presented, "group_canonical_group_identity", "group_identity_key",
            null, null, null);
        putGroupIdentity(source, presented, "affiliated_talkgroup_canonical_group_identity",
            "affiliated_talkgroup_identity_key", null, null, null);

        if(presented.has("source_canonical_group_identity") && !source.has("source_canonical_group_identity"))
        {
            presented.putNull("source_observed_local_id");
        }
        else putObservedLocal(source, presented, "source_observed_local_id", "source_observed_local_id",
            "source_radio_id", "source_id");
        if(presented.has("target_canonical_group_identity") && !source.has("target_canonical_group_identity"))
        {
            presented.putNull("target_observed_local_id");
        }
        else putObservedLocal(source, presented, "target_observed_local_id", "target_observed_local_id", "target_id");
        if(presented.has("canonical_group_identity") && !source.has("canonical_group_identity") &&
            !source.has("explicitly_observed_local_address"))
        {
            presented.putNull("observed_local_id");
        }
    }

    private static boolean entityReference(ObjectNode source)
    {
        String kind = source.path("kind").asText("");
        return source.size() == 2 && source.has("key") && Set.of("channel", "radio_system").contains(kind) ||
            source.size() == 3 && source.has("radio_system_key") && source.has("identity_key") &&
                Set.of("radio", "talkgroup", "patch_group").contains(kind);
    }

    private static void putGroupIdentity(ObjectNode source, ObjectNode presented, String field, String keyField,
                                         Long wacn, Long systemId, Long groupId)
    {
        String key = source.path(keyField).asText("");
        if(!key.isEmpty())
        {
            try
            {
                RadioSystemIdentityKey.Identity identity = RadioSystemIdentityKey.parse(key);
                if(identity.kindCode() == RadioSystemIdentityKey.KIND_RADIO) return;
                if(identity.hasHome() && identity.kindCode() != RadioSystemIdentityKey.KIND_RADIO)
                {
                    wacn = (long)identity.homeWacn();
                    systemId = (long)identity.homeSystemId();
                    groupId = (long)identity.identityId();
                }
            }
            catch(IllegalArgumentException ignored)
            {
                return;
            }
        }
        else if("target_identity_key".equals(keyField) && code(source, "target_kind_code") != 1 &&
            code(source, "target_kind_code") != 3 &&
            !Set.of("GROUP", "PATCH", "talkgroup", "patch_group").contains(source.path("call_type").asText("")) &&
            !source.has("target_canonical_talkgroup_id"))
        {
            return;
        }
        if(wacn == null || systemId == null || groupId == null ||
            wacn < 0 || wacn > 0xFFFFF || systemId < 0 || systemId > 0xFFF ||
            groupId < 1 || groupId > RadioSystemIdentityKey.MAX_P25_GROUP_ID) return;
        ObjectNode identity = OBJECT_MAPPER.createObjectNode();
        identity.put("wacn", wacn);
        identity.put("system_id", systemId);
        identity.put("talkgroup_id", groupId);
        presented.set(field, identity);
    }

    private static void putObservedLocal(ObjectNode source, ObjectNode presented, String field, String... candidates)
    {
        if(candidates.length > 0 && source.has(candidates[0]) && source.get(candidates[0]).isNull())
        {
            presented.putNull(field);
            return;
        }
        Long value = firstIntegral(source, candidates);
        if(value != null && value >= 0 && value <= RadioSystemIdentityKey.MAX_P25_RADIO_ID)
        {
            presented.put(field, value);
        }
    }

    private static void putCanonicalIdentity(ObjectNode source, ObjectNode presented, String field,
                                               Long wacn, Long systemId, Long subscriberId)
    {
        if(wacn == null || systemId == null || subscriberId == null ||
            wacn < 0 || wacn > 0xFFFFF || systemId < 0 || systemId > 0xFFF ||
            subscriberId < 1 || subscriberId > RadioSystemIdentityKey.MAX_P25_RADIO_ID)
        {
            return;
        }

        ObjectNode identity = OBJECT_MAPPER.createObjectNode();
        identity.put("wacn", wacn);
        identity.put("system_id", systemId);
        identity.put("subscriber_id", subscriberId);
        presented.set(field, identity);
    }

    private static Long firstIntegral(ObjectNode source, String... fields)
    {
        for(String field: fields)
        {
            JsonNode value = source.get(field);
            if(value != null && value.isIntegralNumber())
            {
                return value.longValue();
            }
        }
        return null;
    }

    private static int code(ObjectNode source, String field)
    {
        JsonNode value = source.get(field);
        return value != null && value.isIntegralNumber() ? value.intValue() : -1;
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
            case "P25_SUBSCRIBER_IDENTITY" -> "p25_subscriber_identity";
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

    private static void addProtocolChannelFields(ObjectNode source, ObjectNode presented, StatsApiProtocol protocol)
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

        presented.put("last_event_type", enumName(EVENT_TYPE_NAMES.getOrDefault(code.intValue(), "UNKNOWN")));
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

        for(String field: Set.of("native_id", "observed_local_id", "talkgroup_id", "radio_id", "source_id", "target_id",
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
