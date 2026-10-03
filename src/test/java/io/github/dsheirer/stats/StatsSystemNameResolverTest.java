/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StatsSystemNameResolverTest
{
    private static final String GCRCN = "p25:bee00:49f";
    private static final String MARCS = "p25:bee00:348";
    private static final String CHANNEL = "00000000-0000-0000-0000-000000000001";
    private Connection mConnection;
    private StatsSystemNameResolver mNames;

    @BeforeEach
    void setUp() throws Exception
    {
        mConnection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try(Statement statement = mConnection.createStatement())
        {
            statement.execute("CREATE TABLE radio_system (id INTEGER PRIMARY KEY, system_key TEXT UNIQUE, " +
                "configuration_id TEXT, protocol_code INTEGER)");
            statement.execute("CREATE TABLE receiver_channel (configuration_id TEXT, radio_system_id INTEGER)");
            statement.execute("CREATE TABLE configuration_channel (configuration_id TEXT PRIMARY KEY, " +
                "name TEXT, site_name TEXT, system_name TEXT)");
            statement.execute("""
                INSERT INTO radio_system VALUES
                  (1,'p25:bee00:49f',NULL,1),(2,'p25:bee00:348',NULL,1),
                  (3,'dmr:tier3:small:7',NULL,3),(4,'dmr:tier3:large:7',NULL,3),
                  (5,'nxdn-c:global:7',NULL,4),(6,'nxdn-c:regional:7',NULL,4)
                """);
            statement.execute("""
                INSERT INTO configuration_channel VALUES
                  ('00000000-0000-0000-0000-000000000001','Cleveland control','Cleveland','GCRCN'),
                  ('00000000-0000-0000-0000-000000000002','MARCS control','Ohio','Ohio MARCS-IP'),
                  ('00000000-0000-0000-0000-000000000003','DMR small','','DMR Small'),
                  ('00000000-0000-0000-0000-000000000004','DMR large','','DMR Large'),
                  ('00000000-0000-0000-0000-000000000005','NXDN global','','NXDN Global'),
                  ('00000000-0000-0000-0000-000000000006','NXDN regional','','NXDN Regional')
                """);
            statement.execute("""
                INSERT INTO receiver_channel VALUES
                  ('00000000-0000-0000-0000-000000000001',1),
                  ('00000000-0000-0000-0000-000000000002',2),
                  ('00000000-0000-0000-0000-000000000003',3),
                  ('00000000-0000-0000-0000-000000000004',4),
                  ('00000000-0000-0000-0000-000000000005',5),
                  ('00000000-0000-0000-0000-000000000006',6)
                """);
        }
        mNames = new StatsSystemNameResolver(mConnection);
    }

    @AfterEach
    void tearDown() throws Exception
    {
        mConnection.close();
    }

    @Test
    void foreignAndQualifiedHomeUseTheirOwnExactSystems() throws Exception
    {
        JsonNode row = mNames.enrich(Map.of("radio_system_key", GCRCN,
            "source_value", "v1-r-bee00-348-1103", "destination_value", "56131",
            "foreign_wacn", 781824, "foreign_system_id", 840));
        assertEquals("GCRCN", row.path("system_name").asText());
        assertEquals("Ohio MARCS-IP", row.path("home_system_name").asText());
        assertEquals("Ohio MARCS-IP", row.path("foreign_system_name").asText());
        assertEquals(1103, row.path("source_radio_id").asInt());
        assertEquals(MARCS, row.path("home_system").path("key").asText());
        assertEquals(MARCS, row.path("home_system").path("entity_ref").path("key").asText());
        assertEquals(MARCS, row.path("foreign_system_entity_ref").path("key").asText());
        assertEquals(GCRCN, row.path("serving_system").path("key").asText());
        assertEquals(840, row.path("home_system").path("system_id").asInt());
    }

    @Test
    void mapRecognizesActualIssiAndRoamingRadioTextWithoutConfusingServingScope() throws Exception
    {
        for(int local: List.of(1103, 77))
        {
            String identifier = APCO25FullyQualifiedRadioIdentifier.createFrom(local, 781824, 840, 1103).toString();
            JsonNode row = mNames.enrich(Map.of("configuration_id", CHANNEL,
                "identifier", identifier, "positions", List.of()));
            assertEquals("GCRCN", row.path("system_name").asText());
            assertEquals("Ohio MARCS-IP", row.path("home_system_name").asText());
            assertEquals(MARCS, row.path("home_system").path("key").asText());
            assertEquals(GCRCN, row.path("serving_system").path("key").asText());
            assertEquals(1103, row.path("radio_id").asInt());
            assertEquals(identifier, row.path("identifier").asText());
        }
    }

    @Test
    void nativeDmrAndNxdnNamesIncludeModelAndCategoryScope() throws Exception
    {
        assertEquals("DMR Small", mNames.enrich(Map.of("protocol", "dmr", "model", "small",
            "network_id", 7)).path("system_name").asText());
        assertEquals("DMR Large", mNames.enrich(Map.of("protocol", "dmr", "model", "large",
            "network_id", 7)).path("system_name").asText());
        assertEquals("NXDN Global", mNames.enrich(Map.of("protocol", "nxdn", "location_category", "global",
            "system_id", 7)).path("system_name").asText());
        assertEquals("NXDN Regional", mNames.enrich(Map.of("protocol", "nxdn", "location_category", "regional",
            "system_id", 7)).path("system_name").asText());
        assertEquals("DMR Small", mNames.enrich(Map.of("protocol_code", 3, "dmr_model_code", 2,
            "network_id", 7)).path("system_name").asText());
        assertEquals("NXDN Regional", mNames.enrich(Map.of("protocol_code", 4,
            "nxdn_location_category_code", 2, "system_id", 7)).path("system_name").asText());
        assertFalse(mNames.enrich(Map.of("protocol", "dmr", "network_id", 7)).has("system_name"));
    }

    @Test
    void liveCallUsesEventTimeNativeKeyBeforeChannelsCurrentAssociation() throws Exception
    {
        JsonNode row = mNames.enrich(Map.of("configuration_id", CHANNEL, "protocol", "dmr",
            "playback_target", Map.of("radio_system_key", "dmr:tier3:large:7")));
        assertEquals("DMR Large", row.path("system_name").asText());
        assertEquals("DMR Large", row.path("system_identity").path("name").asText());
        assertEquals("dmr:tier3:large:7", row.path("radio_system_key").asText());
    }

    @Test
    void conflictingConfiguredNamesAreStableAndDuplicateNamesCollapse() throws Exception
    {
        try(Statement statement = mConnection.createStatement())
        {
            statement.execute("INSERT INTO configuration_channel VALUES ('extra','Extra','',' Another name ')");
            statement.execute("INSERT INTO receiver_channel VALUES ('extra',1)");
            statement.execute("INSERT INTO configuration_channel VALUES ('duplicate','Duplicate','','gcrcn')");
            statement.execute("INSERT INTO receiver_channel VALUES ('duplicate',1)");
        }
        JsonNode row = mNames.enrich(Map.of("radio_system_key", GCRCN));
        assertEquals("Another name / GCRCN", row.path("system_name").asText());
        assertEquals(2, row.path("system_names").size());
        assertEquals("Another name / GCRCN", StatsSqlRows.queryRows(mConnection,
            "SELECT " + StatsSystemNameResolver.configuredNameSql("system") +
                " AS name FROM radio_system system WHERE id=1").getFirst().get("name"));
    }

    @Test
    void unknownIdentityRetainsExactFactsAndDoesNotInventNameOrLink() throws Exception
    {
        JsonNode row = mNames.enrich(Map.of("wacn", 1, "system_id", 24));
        assertFalse(row.has("system_name"));
        assertFalse(row.has("radio_system_entity_ref"));
        assertEquals("p25:00001:018", row.path("radio_system_key").asText());
        assertEquals(1, row.path("system_identity").path("wacn").asInt());
        assertEquals(24, row.path("system_identity").path("system_id").asInt());
    }

    @Test
    void spectrumCandidatesAndAliasGroupsUseNativeNamesWithoutOverwritingCustomNames() throws Exception
    {
        Map<String,Object> identity = Map.of("wacn", 781824, "system", 1183, "rfss", 1, "site", 1);
        JsonNode generated = mNames.enrich(Map.of("identity", identity,
            "system_name", "P25 BEE00-49F", "site_name", "RFSS 1 · Site 1",
            "name", "P25 BEE00-49F · RFSS 1 · Site 1"));
        assertEquals("GCRCN", generated.path("system_name").asText());
        assertEquals("GCRCN · RFSS 1 · Site 1", generated.path("name").asText());
        JsonNode custom = mNames.enrich(Map.of("identity", identity,
            "system_name", "My system", "site_name", "Downtown", "name", "My control channel"));
        assertEquals("My system", custom.path("system_name").asText());
        assertEquals("My control channel", custom.path("name").asText());
        assertEquals("GCRCN", mNames.enrich(Map.of("wacn", 781824, "system", 1183,
            "group_id", "p25-BEE00-49F")).path("system_name").asText());
    }

    @Test
    void statisticsApiEnrichmentPreservesUnrelatedProducerFieldCasing() throws Exception
    {
        JsonNode source = StatsApiV1Payload.source(new UnrelatedRecord(123L,
            Map.of("radio_system_key", GCRCN)));
        JsonNode result = StatsApiV1Payload.present(mNames.enrich(source));
        assertEquals(123L, result.path("sampleTimeMs").asLong());
        assertFalse(result.has("sample_time_ms"));
        assertEquals("GCRCN", result.path("context").path("system_name").asText());
    }

    private record UnrelatedRecord(long sampleTimeMs, Map<String,Object> context) {}

    @Test
    void healthAndRetainedLabelsKeepExactFactsAndDeletionInputsUnchanged() throws Exception
    {
        Map<String,Object> target = Map.of("kind", "system", "radio_system_key", GCRCN,
            "include_channel_history", false);
        JsonNode row = mNames.enrich(Map.of("radio_system_key", GCRCN,
            "scope", "P25 BEE00.49F (channel:" + CHANNEL + ")", "label", "BEE00.49F · 851 MHz",
            "target", target, "foreign_wacn", 781824, "foreign_system_id", 840, "band", 1));
        assertEquals("GCRCN · Cleveland control", row.path("display_scope").asText());
        assertEquals("GCRCN · Cleveland control", row.path("display_label").asText());
        assertEquals("P25 BEE00.49F (channel:" + CHANNEL + ")", row.path("scope").asText());
        assertEquals("Foreign band 1 · Ohio MARCS-IP", row.path("label").asText());
        assertEquals(3, row.path("target").size());
        assertFalse(row.path("target").has("system_name"));
        assertTrue(mNames.knownP25Systems().stream().anyMatch(system ->
            GCRCN.equals(system.get("key")) && "GCRCN".equals(system.get("name"))));
    }
}
