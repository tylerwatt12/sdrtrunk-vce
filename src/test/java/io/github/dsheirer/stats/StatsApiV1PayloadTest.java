/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StatsApiV1PayloadTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Set<String> INTERNAL_FIELDS = Set.of(
        "radio_system_id", "channel_id", "identity_summary_id", "radio_identity_summary_id",
        "group_identity_summary_id", "source_identity_summary_id", "target_identity_summary_id",
        "representative_channel_id", "fallback_channel_id", "identity_id", "system_key",
        "protocol_code", "variant_code", "site_variant_code",
        "address_domain_code", "location_category_code", "site_location_category_code",
        "dmr_model_code", "nxdn_location_category_code", "site_model_code",
        "identity_kind_code", "source_identity_kind_code", "target_identity_kind_code", "target_kind_code",
        "group_identity_kind_code", "group_identity_kind_label", "last_group_identity_kind_code",
        "home_wacn", "home_system_id", "channel_kind_code",
        "identity_role_code", "model_code", "brand_code", "mode_code", "channel_type_code",
        "service_flags", "failure_code", "role_flags", "status_flags", "last_event_type_code");

    @Test
    void keepsDecoderProfileSeparateFromNormalizedProtocol()
    {
        JsonNode diagnostic = StatsApiV1Payload.present(Map.of(
            "protocol", "P25 Phase 1", "decoder_profile", "P25 Phase 1 LSM"));
        assertEquals("p25", diagnostic.path("protocol").textValue());
        assertEquals("P25 Phase 1 LSM", diagnostic.path("decoder_profile").textValue());
    }

    @Test
    void exposesCurrentConventionalProtocols()
    {
        JsonNode am = StatsApiV1Payload.present(Map.of("protocol_code", 11, "decoder", "AM"));
        JsonNode nbfm = StatsApiV1Payload.present(Map.of("protocol_code", 10, "decoder", "NBFM"));
        assertEquals("am", am.path("protocol").textValue());
        assertEquals("nbfm", nbfm.path("protocol").textValue());
        assertNoInternalFields(am);
        assertNoInternalFields(nbfm);
    }

    @Test
    void expectsAlreadySnakeCaseProducerKeys()
    {
        JsonNode dashboard = StatsApiV1Payload.present(Map.of(
            "source_activity_24h", Map.of("rows", List.of(), "total_count", 0)));
        assertTrue(dashboard.has("source_activity_24h"));
        assertTrue(dashboard.at("/source_activity_24h").has("total_count"));
        assertFalse(dashboard.has("sourceActivity24h"));
    }

    @Test
    void removesLegacyCounterNamesAtThePublicBoundary()
    {
        JsonNode payload = StatsApiV1Payload.present(Map.ofEntries(
            Map.entry("call_count", 9), Map.entry("source_call_count", 4),
            Map.entry("target_call_count", 5), Map.entry("recorded_count", 3),
            Map.entry("streamed_count", 2), Map.entry("encrypted_count", 1),
            Map.entry("grant_count", 8), Map.entry("event_count", 12),
            Map.entry("signaling_observation_count", 9), Map.entry("logical_call_count", 7),
            Map.entry("channel_observation_count", 11),
            Map.entry("stream_submitted_logical_call_count", 2)));
        assertEquals(7, payload.path("logical_call_count").intValue());
        assertEquals(11, payload.path("channel_observation_count").intValue());
        for(String field: List.of("call_count", "source_call_count", "target_call_count", "recorded_count",
            "streamed_count", "encrypted_count", "grant_count", "event_count"))
        {
            assertFalse(payload.has(field), field);
        }
    }

    @Test
    void presentsRadioSystemsAndNestedIdentitiesWithoutDatabaseCodes()
    {
        Map<String,Object> identity = Map.ofEntries(
            Map.entry("radio_system_id", 77), Map.entry("radio_system_key", "p25:bee00:348"),
            Map.entry("protocol_code", 1), Map.entry("address_domain_code", 0),
            Map.entry("identity_summary_id", 99), Map.entry("native_id", 205),
            Map.entry("identity_key", "v1-p-bee00-348-205"),
            Map.entry("group_identity_kind_code", 3), Map.entry("home_wacn", 0xBEE00),
            Map.entry("home_system_id", 0x348));
        JsonNode payload = StatsApiV1Payload.present(Map.of(
            "radio_system_id", 77, "radio_system_key", "p25:bee00:348", "protocol_code", 1,
            "address_domain_code", 0, "capabilities", Map.of("activity", true),
            "group_identities", List.of(identity)));

        assertEquals("p25", payload.path("protocol").textValue());
        assertEquals("standard", payload.path("address_domain").textValue());
        assertEquals("p25:bee00:348", payload.path("radio_system_key").textValue());
        assertEquals(205, payload.at("/group_identities/0/native_id").intValue());
        assertEquals("v1-p-bee00-348-205", payload.at("/group_identities/0/identity_key").textValue());
        assertEquals("patch_group", payload.at("/group_identities/0/group_identity_kind").textValue());
        assertFalse(payload.at("/group_identities/0").has("talkgroup_id"));
        assertFalse(payload.at("/group_identities/0").has("qualification"));
        assertNoInternalFields(payload);
    }

    @Test
    void removesRelationshipJoinIdsWhileKeepingCanonicalIdentityFields()
    {
        JsonNode relationship = StatsApiV1Payload.present(Map.ofEntries(
            Map.entry("identity_key", "v1-r-bee00-348-205"),
            Map.entry("native_id", 205),
            Map.entry("radio_identity_summary_id", 11),
            Map.entry("group_identity_summary_id", 12),
            Map.entry("source_identity_summary_id", 13),
            Map.entry("target_identity_summary_id", 14),
            Map.entry("representative_channel_id", 15),
            Map.entry("fallback_channel_id", 16)));

        assertEquals("v1-r-bee00-348-205", relationship.path("identity_key").textValue());
        assertEquals(205, relationship.path("native_id").intValue());
        assertNoInternalFields(relationship);
    }

    @Test
    void activityKeepsSemanticIdentityReferencesWithoutNumericDiscriminators()
    {
        JsonNode activity = StatsApiV1Payload.present(Map.ofEntries(
            Map.entry("protocol", "APCO25"),
            Map.entry("source_identity_key", "v1-r-bee00-348-205"),
            Map.entry("target_identity_key", "v1-g-bee00-348-101"),
            Map.entry("source_identity_kind_code", 2),
            Map.entry("target_identity_kind_code", 1),
            Map.entry("target_kind_code", 1)));

        assertEquals("v1-r-bee00-348-205", activity.path("source_identity_key").textValue());
        assertEquals("v1-g-bee00-348-101", activity.path("target_identity_key").textValue());
        assertEquals("talkgroup", activity.path("target_kind").textValue());
        assertNoInternalFields(activity);
    }

    @Test
    void presentsDmrChannelLocationSemantics()
    {
        JsonNode channel = StatsApiV1Payload.present(Map.ofEntries(
            Map.entry("channel_id", 1001), Map.entry("configuration_id", "channel-1"),
            Map.entry("protocol_code", 3), Map.entry("site_id", 7), Map.entry("variant_code", 3),
            Map.entry("location_category_code", 0), Map.entry("brand_code", 2),
            Map.entry("model_code", 4), Map.entry("mode_code", 1), Map.entry("channel_type_code", 2)));
        assertEquals("dmr", channel.path("protocol").textValue());
        assertEquals("capacity_max", channel.path("variant").textValue());
        assertEquals("huge", channel.path("model").textValue());
        assertEquals("motorola_connect_plus", channel.path("brand").textValue());
        assertEquals("open_system", channel.path("mode").textValue());
        assertEquals("traffic", channel.path("channel_type").textValue());
        assertNoInternalFields(channel);
    }

    @Test
    void presentsNxdnAddressDomainLocationAndServicesSeparately()
    {
        JsonNode channel = StatsApiV1Payload.present(Map.ofEntries(
            Map.entry("channel_id", 2002), Map.entry("configuration_id", "channel-2"),
            Map.entry("protocol_code", 4), Map.entry("site_id", 12), Map.entry("variant_code", 2),
            Map.entry("address_domain_code", 2), Map.entry("location_category_code", 3),
            Map.entry("mode_code", 3), Map.entry("service_flags", 0x8200), Map.entry("failure_code", 45)));
        assertEquals("nxdn", channel.path("protocol").textValue());
        assertEquals("type_d", channel.path("variant").textValue());
        assertEquals("nxdn_type_d", channel.path("address_domain").textValue());
        assertEquals("local", channel.path("location_category").textValue());
        assertEquals("halted_cwid", channel.path("repeater_state").textValue());
        assertEquals(List.of("multi_site", "voice_call"),
            OBJECT_MAPPER.convertValue(channel.get("services"), List.class));
        assertEquals(45, channel.path("failure_call_timer_seconds").intValue());
        assertNoInternalFields(channel);
    }

    @Test
    void presentsAuthoritativeNativeSystemDimensionsWithoutDatabaseCodes()
    {
        JsonNode dmr = StatsApiV1Payload.present(Map.of(
            "protocol_code", 3, "radio_system_key", "dmr:tier3:small:42",
            "variant", "TIER_III", "dmr_model_code", 2, "network_id", 42));
        assertEquals("dmr", dmr.path("protocol").textValue());
        assertEquals("tier_iii", dmr.path("variant").textValue());
        assertEquals("small", dmr.path("model").textValue());
        assertEquals(42, dmr.path("network_id").intValue());
        assertNoInternalFields(dmr);

        JsonNode nxdn = StatsApiV1Payload.present(Map.of(
            "protocol_code", 4, "radio_system_key", "nxdn-c:local:303",
            "address_domain_code", 1, "variant", "TYPE_C",
            "nxdn_location_category_code", 3, "system_id", 303));
        assertEquals("nxdn", nxdn.path("protocol").textValue());
        assertEquals("type_c", nxdn.path("variant").textValue());
        assertEquals("local", nxdn.path("location_category").textValue());
        assertEquals(303, nxdn.path("system_id").intValue());
        assertNoInternalFields(nxdn);
    }

    @Test
    void presentsDmrAndNxdnNeighborClassificationsInSeparateFields()
    {
        JsonNode dmr = StatsApiV1Payload.present(Map.of(
            "protocol_code", 3, "variant_code", 1, "dmr_model_code", 2,
            "network_id", 42, "site_id", 7));
        assertEquals("dmr", dmr.path("protocol").textValue());
        assertEquals("tier_iii", dmr.path("variant").textValue());
        assertEquals("small", dmr.path("model").textValue());
        assertFalse(dmr.has("location_category"));
        assertNoInternalFields(dmr);

        JsonNode nxdn = StatsApiV1Payload.present(Map.of(
            "protocol_code", 4, "variant_code", 1, "nxdn_location_category_code", 3,
            "system_id", 303, "site_id", 9));
        assertEquals("nxdn", nxdn.path("protocol").textValue());
        assertEquals("type_c", nxdn.path("variant").textValue());
        assertEquals("local", nxdn.path("location_category").textValue());
        assertFalse(nxdn.has("model"));
        assertNoInternalFields(nxdn);
    }

    @Test
    void keepsRadioSystemIdentitySeparateFromObservedSiteFacts()
    {
        JsonNode dmr = StatsApiV1Payload.present(Map.ofEntries(
            Map.entry("protocol_code", 3), Map.entry("radio_system_key", "dmr:tier3:small:42"),
            Map.entry("variant", "TIER_III"), Map.entry("dmr_model_code", 2), Map.entry("network_id", 42),
            Map.entry("site_variant_code", 1), Map.entry("site_model_code", 3),
            Map.entry("site_network_id", 7), Map.entry("site_id", 9)));

        assertEquals("tier_iii", dmr.path("variant").textValue());
        assertEquals("small", dmr.path("model").textValue());
        assertEquals(42, dmr.path("network_id").intValue());
        assertEquals("tier_iii", dmr.path("site_variant").textValue());
        assertEquals("large", dmr.path("site_model").textValue());
        assertEquals(7, dmr.path("site_network_id").intValue());
        assertEquals(9, dmr.path("site_id").intValue());
        assertNoInternalFields(dmr);

        JsonNode nxdn = StatsApiV1Payload.present(Map.ofEntries(
            Map.entry("protocol_code", 4), Map.entry("radio_system_key", "nxdn-c:local:303"),
            Map.entry("variant", "TYPE_C"), Map.entry("nxdn_location_category_code", 3),
            Map.entry("system_id", 303), Map.entry("site_variant_code", 1),
            Map.entry("site_location_category_code", 2), Map.entry("site_system_id", 12)));

        assertEquals("type_c", nxdn.path("variant").textValue());
        assertEquals("local", nxdn.path("location_category").textValue());
        assertEquals(303, nxdn.path("system_id").intValue());
        assertEquals("type_c", nxdn.path("site_variant").textValue());
        assertEquals("regional", nxdn.path("site_location_category").textValue());
        assertEquals(12, nxdn.path("site_system_id").intValue());
        assertNoInternalFields(nxdn);
    }

    @Test
    void normalizesDecoderNamesAndP25ScalarSiteId()
    {
        JsonNode p25 = StatsApiV1Payload.present(Map.of("protocol", "APCO25_PHASE2", "name", "Phase 2"));
        assertEquals("p25", p25.path("protocol").textValue());

        JsonNode channel = StatsApiV1Payload.present(Map.of(
            "protocol_code", 1, "configuration_id", "channel-3", "rfss", 2, "site_id", 7,
            "active_rfss_network_connection", 1));
        assertEquals(7, channel.path("site_id").intValue());
        assertTrue(channel.path("active_rfss_network_connection").booleanValue());
        assertFalse(channel.has("site"));
        assertNoInternalFields(channel);
    }

    @Test
    void preservesNestedChannelPresenceAndBooleanAffiliationState()
    {
        JsonNode radio = StatsApiV1Payload.present(Map.of(
            "protocol_code", 1, "radio_id", 1234, "currently_affiliated", 1,
            "presence", Map.of("evidence", "affiliation", "confirmed_at_ms", 5000,
                "channel", Map.of("protocol_code", 1, "configuration_id", "channel-4",
                    "rfss", 2, "site_id", 7))));
        assertTrue(radio.path("currently_affiliated").booleanValue());
        assertTrue(radio.at("/presence/channel").isObject());
        assertEquals("p25", radio.at("/presence/channel/protocol").textValue());
        assertEquals("channel-4", radio.at("/presence/channel/configuration_id").textValue());
        assertFalse(radio.at("/presence").has("site"));
        assertNoInternalFields(radio);
    }

    @Test
    void presentsBoundedChannelPreviewsWithoutDatabaseIds()
    {
        JsonNode system = StatsApiV1Payload.present(Map.ofEntries(
            Map.entry("radio_system_id", 1), Map.entry("radio_system_key", "p25:bee00:348"),
            Map.entry("protocol_code", 1), Map.entry("channels", 26),
            Map.entry("channel_preview_truncated", true),
            Map.entry("channel_preview", List.of(Map.of("channel_id", 1, "protocol_code", 1,
                "configuration_id", "channel-5", "rfss", 2, "site_id", 7)))));
        assertEquals("p25", system.path("protocol").textValue());
        assertTrue(system.path("channel_preview").isArray());
        assertEquals("channel-5", system.at("/channel_preview/0/configuration_id").textValue());
        assertTrue(system.path("channel_preview_truncated").booleanValue());
        assertNoInternalFields(system);
    }

    @Test
    void presentsAliasEnumsFlagsAndNeighborTypes()
    {
        JsonNode alias = StatsApiV1Payload.present(Map.of(
            "alias_id", 1, "alias_list_id", 2, "family", "P25",
            "matcher_type", "RADIO_ID_RANGE", "protocol", "APCO25_PHASE2"));
        assertEquals("p25", alias.path("family").textValue());
        assertEquals("radio_range", alias.path("matcher_type").textValue());
        assertEquals("phase_2", alias.path("protocol_variant").textValue());

        JsonNode frequency = StatsApiV1Payload.present(Map.of("protocol_code", 1,
            "channel_type_code", 5, "role_flags", 1 | 4 | 8 | 32, "tdma", 1));
        assertEquals(List.of("current_control", "traffic"),
            OBJECT_MAPPER.convertValue(frequency.get("roles"), List.class));
        assertEquals(List.of("observed", "over_air_frequency"),
            OBJECT_MAPPER.convertValue(frequency.get("sources"), List.class));
        assertEquals("tdma_h_d8psk", frequency.path("access_mode").textValue());

        JsonNode neighbor = StatsApiV1Payload.present(Map.of("status_flags", 3, "entry_type", "ISSI"));
        assertEquals(List.of("linked", "isolated"),
            OBJECT_MAPPER.convertValue(neighbor.get("statuses"), List.class));
        assertEquals("issi", neighbor.path("entry_type").textValue());
        assertNoInternalFields(alias);
        assertNoInternalFields(frequency);
        assertNoInternalFields(neighbor);
    }

    private static void assertNoInternalFields(JsonNode node)
    {
        if(node.isObject())
        {
            for(String field: INTERNAL_FIELDS)
            {
                assertFalse(node.has(field), () -> "Internal field escaped into v1 payload: " + field);
            }
        }
        node.forEach(StatsApiV1PayloadTest::assertNoInternalFields);
    }
}
