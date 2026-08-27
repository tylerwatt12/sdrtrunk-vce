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
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class StatsApiV1PayloadTest
{
    @Test
    void keepsTheDecoderProfileSeparateFromTheNormalizedProtocol()
    {
        JsonNode diagnostic = StatsApiV1Payload.present(Map.of(
            "protocol", "P25 Phase 1",
            "decoder_profile", "P25 Phase 1 LSM"));

        assertEquals("p25", diagnostic.path("protocol").textValue());
        assertEquals("P25 Phase 1 LSM", diagnostic.path("decoder_profile").textValue());
    }

    @Test
    void exposesAmAsAFirstClassProtocol()
    {
        JsonNode payload = StatsApiV1Payload.present(Map.of(
            "protocol_code", 11,
            "kind_code", 10,
            "decoder", "AM"));

        assertEquals("am", payload.path("protocol").textValue());
        assertFalse(payload.has("protocol_code"));
        assertFalse(payload.has("kind_code"));
    }

    @Test
    void presentsDashboardDaySourceActivityWithDelimitedWireUnits()
    {
        JsonNode dashboard = StatsApiV1Payload.present(Map.of(
            "sourceActivity24h", Map.of("rows", List.of(), "limit", 100)));

        assertTrue(dashboard.has("source_activity_24h"));
        assertFalse(dashboard.has("sourceActivity24h"));
    }

    @Test
    void rejectsLegacyTrunkedMetricNamesAtThePublicBoundary()
    {
        JsonNode payload = StatsApiV1Payload.present(Map.ofEntries(
            Map.entry("call_count", 9),
            Map.entry("source_call_count", 4),
            Map.entry("target_call_count", 5),
            Map.entry("recorded_count", 3),
            Map.entry("streamed_count", 2),
            Map.entry("encrypted_count", 1),
            Map.entry("grant_count", 8),
            Map.entry("join_count", 6),
            Map.entry("event_count", 12),
            Map.entry("signaling_count", 8),
            Map.entry("logical_call_count", 7),
            Map.entry("site_observation_count", 11),
            Map.entry("stream_submitted_logical_call_count", 2)));

        assertEquals(7, payload.path("logical_call_count").intValue());
        assertEquals(11, payload.path("site_observation_count").intValue());
        assertEquals(2, payload.path("stream_submitted_logical_call_count").intValue());
        for(String field: new String[]{"call_count", "source_call_count", "target_call_count",
            "recorded_count", "streamed_count", "encrypted_count", "grant_count", "join_count",
            "event_count", "signaling_count"})
        {
            assertFalse(payload.has(field), () -> "Legacy metric escaped into v1 payload: " + field);
        }
    }

    private static final Set<String> INTERNAL_FIELDS = Set.of(
        "protocol_code", "scope_kind_code", "variant_code", "identity_domain_code", "scope_id", "context_id",
        "system_key", "p25_system_key", "resolved_system_key", "site_type",
        "identity_kind_code", "target_kind_code", "last_talkgroup_kind_code", "last_counterpart_kind_code",
        "p25_identity_state_code", "p25_home_wacn", "p25_home_system_id", "p25_home_talkgroup_id",
        "channel_kind_code", "kind_code", "identity_role_code", "model_code", "brand_code", "mode_code",
        "channel_type_code", "service_flags", "failure_code", "role_flags", "status_flags",
        "last_event_type_code");

    @Test
    void presentsP25ScopesAndNestedIdentitiesWithoutInternalCodes()
    {
        Map<String,Object> identity = Map.ofEntries(
            Map.entry("context_id", 77),
            Map.entry("system_key", 91),
            Map.entry("resolved_system_key", 92),
            Map.entry("identity_domain_code", 0),
            Map.entry("talkgroup_id", 205),
            Map.entry("target_kind_code", 3),
            Map.entry("p25_identity_state_code", 2),
            Map.entry("p25_home_wacn", 0xBEE00),
            Map.entry("p25_home_system_id", 0x348),
            Map.entry("p25_home_talkgroup_id", 205),
            Map.entry("display_name", "Dispatch"));
        Map<String,Object> scope = Map.of(
            "protocol_code", 2,
            "scope_id", 10,
            "p25_system_key", 90,
            "scope_token", "opaque-token",
            "scope_kind_code", 1,
            "identity_domain_code", 0,
            "capabilities", Map.of("activity", true),
            "rows", List.of(identity));

        JsonNode payload = StatsApiV1Payload.present(scope);

        assertEquals("p25", payload.get("protocol").textValue());
        assertEquals("linked_system", payload.get("scope_kind").textValue());
        assertEquals("standard", payload.get("address_domain").textValue());
        assertEquals("opaque-token", payload.get("scope_token").textValue());
        assertEquals("p25", payload.at("/rows/0/protocol").textValue());
        assertEquals("standard", payload.at("/rows/0/address_domain").textValue());
        assertEquals(205, payload.at("/rows/0/talkgroup_id").intValue());
        assertEquals("patch_group", payload.at("/rows/0/target_kind").textValue());
        assertEquals("stable_fully_qualified", payload.at("/rows/0/qualification/state").textValue());
        assertEquals(0xBEE00, payload.at("/rows/0/qualification/home/wacn").intValue());
        assertFalse(payload.at("/capabilities").has("protocol"));
        assertNoInternalFields(payload);
    }

    @Test
    void presentsDmrVariantAndSiteModelSemantically()
    {
        JsonNode payload = StatsApiV1Payload.present(Map.of(
            "protocol_code", 3,
            "guid", 1001,
            "site_id", 7,
            "variant_code", 3,
            "identity_domain_code", 4,
            "brand_code", 2,
            "model_code", 4,
            "mode_code", 1,
            "channel_type_code", 2));

        assertEquals("dmr", payload.get("protocol").textValue());
        assertEquals("capacity_max", payload.get("variant").textValue());
        assertEquals("huge", payload.get("model").textValue());
        assertEquals("motorola_connect_plus", payload.get("brand").textValue());
        assertEquals("open_system", payload.get("mode").textValue());
        assertEquals("traffic", payload.get("channel_type").textValue());
        assertFalse(payload.has("address_domain"));
        assertNoInternalFields(payload);
    }

    @Test
    void presentsNxdnVariantSiteCategoryAndTypeDIdentifiersSemantically()
    {
        JsonNode site = StatsApiV1Payload.present(Map.of(
            "protocol_code", 4,
            "guid", 2002,
            "site_id", 12,
            "variant_code", 2,
            "identity_domain_code", 3,
            "mode_code", 3,
            "service_flags", 0x8200,
            "failure_code", 45));

        assertEquals("nxdn", site.get("protocol").textValue());
        assertEquals("type_d", site.get("variant").textValue());
        assertEquals("local", site.get("location_category").textValue());
        assertEquals("halted_cwid", site.get("repeater_state").textValue());
        assertEquals(List.of("multi_site", "voice_call"),
            new com.fasterxml.jackson.databind.ObjectMapper().convertValue(site.get("services"), List.class));
        assertEquals(45, site.get("failure_call_timer_seconds").intValue());
        assertFalse(site.has("address_domain"));
        assertNoInternalFields(site);

        JsonNode identity = StatsApiV1Payload.present(Map.ofEntries(
            Map.entry("protocol_code", 4),
            Map.entry("scope_kind_code", 2),
            Map.entry("identity_domain_code", 2),
            Map.entry("talkgroup_id", 0x1234),
            Map.entry("radio_id", 0xFFFF),
            Map.entry("source_id", -1),
            Map.entry("target_id", 0x1_0000)));

        assertEquals("nxdn", identity.get("protocol").textValue());
        assertEquals("receiver_context", identity.get("scope_kind").textValue());
        assertEquals("nxdn_type_d", identity.get("address_domain").textValue());
        assertEquals("02-0564", identity.get("talkgroup_id_display").textValue());
        assertEquals("31-2047", identity.get("radio_id_display").textValue());
        assertFalse(identity.has("source_id_display"));
        assertFalse(identity.has("target_id_display"));
        assertNoInternalFields(identity);
    }

    @Test
    void normalizesKnownDecoderProtocolNames()
    {
        JsonNode p25 = StatsApiV1Payload.present(Map.of("protocol", "APCO25_PHASE2", "name", "Phase 2"));
        assertEquals("p25", p25.get("protocol").textValue());

        JsonNode site = StatsApiV1Payload.present(Map.of(
            "protocol_code", 1, "guid", "site-guid", "rfss", 2, "site", 7,
            "active_rfss_network_connection", 1));
        assertEquals(7, site.get("site_id").intValue());
        assertTrue(site.get("active_rfss_network_connection").booleanValue());
        assertFalse(site.has("site"));
    }

    @Test
    void preservesNestedPresenceSiteAndNormalizesItsScalarP25SiteIdAndAffiliationState()
    {
        JsonNode radio = StatsApiV1Payload.present(Map.of(
            "protocol_code", 1,
            "radio_id", 1234,
            "currently_affiliated", 1,
            "presence", Map.of(
                "evidence", "affiliation",
                "confirmed_at_ms", 5000,
                "site", Map.of(
                    "protocol_code", 1,
                    "guid", "site-guid",
                    "rfss", 2,
                    "site", 7))));

        assertTrue(radio.get("currently_affiliated").isBoolean());
        assertTrue(radio.get("currently_affiliated").booleanValue());
        assertTrue(radio.at("/presence/site").isObject());
        assertEquals("p25", radio.at("/presence/site/protocol").textValue());
        assertEquals(7, radio.at("/presence/site/site_id").intValue());
        assertFalse(radio.at("/presence/site").has("site"));
        assertFalse(radio.at("/presence").has("site_id"));
        assertNoInternalFields(radio);
    }

    @Test
    void presentsNestedSystemSitePreviewsWithoutDatabaseIdentityFields()
    {
        JsonNode system = StatsApiV1Payload.present(Map.ofEntries(
            Map.entry("scope_id", 1),
            Map.entry("scope_token", "p25:BEE00:348"),
            Map.entry("protocol_code", 1),
            Map.entry("sites", 26),
            Map.entry("site_preview_truncated", true),
            Map.entry("site_preview", List.of(Map.of(
                "scope_id", 1,
                "protocol_code", 1,
                "guid", "site-guid",
                "rfss", 2,
                "site_id", 7)))));

        assertEquals("p25", system.path("protocol").textValue());
        assertTrue(system.path("site_preview").isArray());
        assertEquals("p25", system.at("/site_preview/0/protocol").textValue());
        assertEquals(7, system.at("/site_preview/0/site_id").intValue());
        assertTrue(system.path("site_preview_truncated").booleanValue());
        assertNoInternalFields(system);
    }

    @Test
    void presentsAliasCatalogEnumsWithTheStableWireVocabulary()
    {
        JsonNode phaseTwo = StatsApiV1Payload.present(Map.of(
            "alias_id", 1,
            "alias_list_id", 2,
            "family", "P25",
            "matcher_type", "RADIO_ID_RANGE",
            "protocol", "APCO25_PHASE2"));

        assertEquals("p25", phaseTwo.get("family").textValue());
        assertEquals("radio_range", phaseTwo.get("matcher_type").textValue());
        assertEquals("p25", phaseTwo.get("protocol").textValue());
        assertEquals("phase_2", phaseTwo.get("protocol_variant").textValue());

        JsonNode status = StatsApiV1Payload.present(Map.of(
            "alias_id", 3,
            "alias_list_id", 2,
            "family", "NBFM",
            "matcher_type", "STATUS"));
        assertEquals("nbfm", status.get("family").textValue());
        assertEquals("user_status", status.get("matcher_type").textValue());
    }

    @Test
    void presentsFlagsBandPlansEventTypesAndBooleansSemantically()
    {
        JsonNode channel = StatsApiV1Payload.present(Map.of(
            "role_flags", 1 | 4 | 8 | 32,
            "tdma", 1,
            "encrypted", 0));
        assertEquals(List.of("current_control", "traffic"),
            new com.fasterxml.jackson.databind.ObjectMapper().convertValue(channel.get("roles"), List.class));
        assertEquals(List.of("observed", "over_air_frequency"),
            new com.fasterxml.jackson.databind.ObjectMapper().convertValue(channel.get("sources"), List.class));
        assertEquals(true, channel.get("tdma").booleanValue());
        assertEquals(false, channel.get("encrypted").booleanValue());

        JsonNode neighbor = StatsApiV1Payload.present(Map.of("status_flags", 3));
        assertEquals(List.of("linked", "isolated"),
            new com.fasterxml.jackson.databind.ObjectMapper().convertValue(neighbor.get("statuses"), List.class));

        JsonNode band = StatsApiV1Payload.present(Map.of("protocol_code", 1, "channel_type_code", 5));
        assertEquals("tdma_h_d8psk", band.get("access_mode").textValue());
        assertEquals(12_500, band.get("bandwidth_hz").intValue());
        assertEquals(2, band.get("timeslots").intValue());
        assertEquals("half", band.get("voice_rate").textValue());

        JsonNode conventional = StatsApiV1Payload.present(Map.of("protocol_code", 10,
            "last_event_type_code", 5));
        assertEquals("call", conventional.get("last_event_type").textValue());
        assertNoInternalFields(channel);
        assertNoInternalFields(neighbor);
        assertNoInternalFields(band);
        assertNoInternalFields(conventional);
    }

    @Test
    void presentsObservedAliasTopologyAndNeighborEntryTypeAsWireEnums()
    {
        JsonNode observedAlias = StatsApiV1Payload.present(Map.of(
            "observed_key", "p25:test:205",
            "topology", "CONVENTIONAL"));
        JsonNode neighbor = StatsApiV1Payload.present(Map.of(
            "entry_type", "ISSI",
            "system_id", 0x348));

        assertEquals("conventional", observedAlias.get("topology").textValue());
        assertEquals("issi", neighbor.get("entry_type").textValue());
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
