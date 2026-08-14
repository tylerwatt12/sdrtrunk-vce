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
            Map.entry("protocol", "P25"), Map.entry("configured_system", "County, Public Safety"),
            Map.entry("scope_token", "p25:BEE00:348"), Map.entry("wacn", 0xBEE00),
            Map.entry("system_id", 0x348), Map.entry("talkgroup_id", 56132),
            Map.entry("target_kind_code", 1), Map.entry("alias_name", "  =HYPERLINK(\"bad\")"),
            Map.entry("alias_description", "Line one\nLine two"), Map.entry("alias_group", "+Formula"),
            Map.entry("call_count", 12), Map.entry("recorded_count", 4), Map.entry("streamed_count", 3),
            Map.entry("encrypted_count", 2), Map.entry("signaling_count", 7),
            Map.entry("first_seen_ms", 1_000), Map.entry("last_seen_ms", 2_000));

        StatsCsvExport export = StatsCsvExport.create("system-talkgroups", "County", List.of(row));
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
            assertEquals("56132", parsed.get("talkgroup_id"));
        }
    }

    @Test
    void writesHeadersForAnEmptyDataset() throws Exception
    {
        StatsCsvExport export = StatsCsvExport.create("conventional-radios", "empty", List.of());
        String csv = new String(export.content(), 3, export.content().length - 3, StandardCharsets.UTF_8);
        assertTrue(csv.startsWith("protocol,context,alias_list,frequency_hz"));
        assertEquals(0, export.rowCount());
    }

    @Test
    void normalizesConventionalProtocolAndMissingTimeslot() throws Exception
    {
        StatsCsvExport export = StatsCsvExport.create("conventional-channels", "all", List.of(Map.of(
            "protocol_code", 2, "context_key", "phase-two", "channel_name", "P25 Phase 2",
            "frequency_hz", 851_012_500L, "timeslot", -1, "call_count", 1)));
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
            () -> StatsCsvExport.create("system-radios", "test", tooMany));
        assertEquals(413, rows.status());

        StatsApiException bytes = assertThrows(StatsApiException.class, () -> StatsCsvExport.create(
            "system-talkgroups", "test", List.of(Map.of("alias_description", "x".repeat(2_000))), 256));
        assertEquals(413, bytes.status());
    }
}
