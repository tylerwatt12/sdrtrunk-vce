/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

class StatsCsvExportTest
{
    @Test
    void writesExcelCompatibleRfc4180CsvAndProtectsFormulaCells() throws Exception
    {
        Map<String,Object> row = Map.ofEntries(
            Map.entry("protocol", "P25"), Map.entry("system_name", "County, Public Safety"),
            Map.entry("radio_system_key", "p25:bee00:348"), Map.entry("wacn", 0xBEE00),
            Map.entry("system_id", 0x348), Map.entry("identity_key", "v1-g-bee00-348-56132"),
            Map.entry("native_id", 56132),
            Map.entry("group_identity_kind_code", 1), Map.entry("alias_name", "  =HYPERLINK(\"bad\")"),
            Map.entry("alias_description", "Line one\nLine two"), Map.entry("alias_group", "+Formula"),
            Map.entry("logical_call_count", 12), Map.entry("recorded_logical_call_count", 4),
            Map.entry("stream_submitted_logical_call_count", 3),
            Map.entry("encrypted_logical_call_count", 2), Map.entry("signaling_observation_count", 7),
            Map.entry("first_seen_ms", 1_000), Map.entry("last_seen_ms", 2_000));

        StatsCsvExport export = StatsCsvExport.create("radio-system-group-identities", "County", List.of(row));
        byte[] content = export.content();
        assertEquals((byte)0xEF, content[0]);
        assertEquals((byte)0xBB, content[1]);
        assertEquals((byte)0xBF, content[2]);
        assertTrue(export.fileName().matches("[A-Za-z0-9.-]+"));

        String csv = new String(content, 3, content.length - 3, StandardCharsets.UTF_8);
        try(CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get()
            .parse(new StringReader(csv)))
        {
            CSVRecord parsed = parser.getRecords().getFirst();
            assertEquals("County, Public Safety", parsed.get("system_name"));
            assertEquals("'  =HYPERLINK(\"bad\")", parsed.get("alias"));
            assertEquals("Line one\nLine two", parsed.get("description"));
            assertEquals("'+Formula", parsed.get("group"));
            assertEquals("1970-01-01T00:00:01Z", parsed.get("first_seen_utc"));
            assertEquals("BEE00", parsed.get("wacn_hex"));
            assertEquals("v1-g-bee00-348-56132", parsed.get("identity_key"));
            assertEquals("56132", parsed.get("native_id"));
            assertEquals("talkgroup", parsed.get("group_identity_kind"));
            assertFalse(parser.getHeaderMap().containsKey("alias_list_id"));
            assertEquals("p25:bee00:348", parsed.get("radio_system_key"));
        }
    }

    @Test
    void writesHeadersForAnEmptyDataset() throws Exception
    {
        StatsCsvExport export = StatsCsvExport.create("channel-radios", "empty", List.of());
        String csv = new String(export.content(), 3, export.content().length - 3, StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("protocol,configuration_id,radio_system_key,alias_list,frequency_hz"));
        assertEquals(0, export.rowCount());
    }

    @Test
    void writesAuthoritativeDmrAndNxdnRadioSystemDimensions() throws Exception
    {
        StatsCsvExport groups = StatsCsvExport.create("radio-system-group-identities", "DMR", List.of(Map.ofEntries(
            Map.entry("protocol", "DMR"), Map.entry("radio_system_key", "dmr:tier3:small:42"),
            Map.entry("variant", "TIER_III"), Map.entry("dmr_model_code", 2),
            Map.entry("network_id", 42), Map.entry("identity_key", "v1-g-42-100"),
            Map.entry("native_id", 100), Map.entry("group_identity_kind_code", 1))));
        CSVRecord dmr = firstRecord(groups);
        assertEquals("tier_iii", dmr.get("variant"));
        assertEquals("small", dmr.get("model"));
        assertEquals("", dmr.get("location_category"));
        assertEquals("42", dmr.get("network_id"));

        StatsCsvExport radios = StatsCsvExport.create("radio-system-radios", "NXDN", List.of(Map.ofEntries(
            Map.entry("protocol", "NXDN"), Map.entry("radio_system_key", "nxdn-c:local:303"),
            Map.entry("variant", "TYPE_C"), Map.entry("nxdn_location_category_code", 3),
            Map.entry("system_id", 303), Map.entry("identity_key", "v1-r-303-200"),
            Map.entry("native_id", 200))));
        CSVRecord nxdn = firstRecord(radios);
        assertEquals("type_c", nxdn.get("variant"));
        assertEquals("", nxdn.get("model"));
        assertEquals("local", nxdn.get("location_category"));
        assertEquals("303", nxdn.get("system_id"));
    }

    @Test
    void normalizesConventionalProtocolAndMissingTimeslot() throws Exception
    {
        StatsCsvExport export = StatsCsvExport.create("channels", "all", List.of(Map.of(
            "protocol_code", 2, "configuration_id", "00000000-0000-0000-0000-000000000001",
            "channel_name", "P25 Phase 2",
            "frequency_hz", 851_012_500L, "timeslot", -1, "logical_call_count", 1)));
        String csv = new String(export.content(), 3, export.content().length - 3, StandardCharsets.UTF_8);

        try(CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get()
            .parse(new StringReader(csv)))
        {
            CSVRecord row = parser.getRecords().getFirst();
            assertEquals("P25", row.get("protocol"));
            assertEquals("", row.get("timeslot"));
        }
    }

    @Test
    void aliasExportIncludesScanListMembershipAndOmitsRetiredFields() throws Exception
    {
        StatsCsvExport export = StatsCsvExport.create("aliases", "County", List.of(Map.ofEntries(
            Map.entry("alias_id", 1), Map.entry("alias_list_id", 2), Map.entry("alias_list_name", "County"),
            Map.entry("family", "P25"), Map.entry("name", "Dispatch"),
            Map.entry("matcher_type", "TALKGROUP"), Map.entry("protocol", "APCO25_PHASE2"),
            Map.entry("value", 100), Map.entry("scan_list_ids", List.of(1L, 2L)),
            Map.entry("scan_lists", List.of("Default", "Cleveland")))));
        String csv = new String(export.content(), 3, export.content().length - 3, StandardCharsets.UTF_8);

        try(CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get()
            .parse(new StringReader(csv)))
        {
            assertFalse(parser.getHeaderMap().containsKey("wacn"));
            assertFalse(parser.getHeaderMap().containsKey("wacn_hex"));
            assertFalse(parser.getHeaderMap().containsKey("p25_system_id"));
            assertFalse(parser.getHeaderMap().containsKey("p25_system_id_hex"));
            assertFalse(parser.getHeaderMap().containsKey("fully_qualified"));
            assertFalse(parser.getHeaderMap().containsKey("priority"));
            assertTrue(parser.getHeaderMap().containsKey("scan_list_ids"));
            assertTrue(parser.getHeaderMap().containsKey("scan_lists"));
            assertTrue(parser.getHeaderMap().containsKey("signaling_observations"));
            CSVRecord row = parser.getRecords().getFirst();
            assertEquals("100", row.get("value"));
            assertEquals("p25", row.get("family"));
            assertEquals("talkgroup", row.get("matcher_type"));
            assertEquals("p25", row.get("protocol"));
            assertEquals("phase_2", row.get("protocol_variant"));
            assertEquals("1; 2", row.get("scan_list_ids"));
            assertEquals("Default; Cleveland", row.get("scan_lists"));
        }
    }

    @Test
    void rejectsUnsupportedDatasetsAndHardLimitsWithoutTruncating()
    {
        StatsApiException unsupported = assertThrows(StatsApiException.class,
            () -> StatsCsvExport.create("unknown", "test", List.of()));
        assertEquals(400, unsupported.status());

        List<Map<String,Object>> tooMany = Collections.nCopies(StatsCsvExport.MAX_ROWS + 1, Map.of());
        StatsApiException rows = assertThrows(StatsApiException.class,
            () -> StatsCsvExport.create("radio-system-radios", "test", tooMany));
        assertEquals(413, rows.status());

        StatsApiException bytes = assertThrows(StatsApiException.class, () -> StatsCsvExport.create(
            "radio-system-group-identities", "test",
            List.of(Map.of("alias_description", "x".repeat(2_000))), 256));
        assertEquals(413, bytes.status());
    }

    private static CSVRecord firstRecord(StatsCsvExport export) throws Exception
    {
        String csv = new String(export.content(), 3, export.content().length - 3, StandardCharsets.UTF_8);
        try(CSVParser parser = CSVFormat.RFC4180.builder().setHeader().setSkipHeaderRecord(true).get()
            .parse(new StringReader(csv)))
        {
            return parser.getRecords().getFirst();
        }
    }
}
