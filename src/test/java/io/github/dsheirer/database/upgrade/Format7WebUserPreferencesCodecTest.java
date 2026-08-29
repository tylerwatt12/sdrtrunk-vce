/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class Format7WebUserPreferencesCodecTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VERSION_ONE = """
        {"version":1,"appearance":{"theme":"dark"},"page_titles":{"prepend_playing_call":true},"playback":{"volume":0.4,"selected_scan_list_ids":[9,3]},"scanner":{"detail_mode":"advanced"},"presentation":{"show_encryption_details":false,"show_control_decode_quality":true,"show_voice_decode_quality":false,"decode_quality_display_mode":"detailed","live_detail_row_limit":125},"tuner":{"floor_db":-150,"ceiling_db":-10,"waterfall_speed":2.0,"snap_frequency":false,"smooth_fft":true,"highlight_waterfall_channels":true,"profile":"high-detail"},"tables":{"scanner.calls":{"schema":["alias","talkgroup"],"column_order":["talkgroup","alias"],"column_widths":{"alias":240},"hidden_columns":["talkgroup"]}}}""";

    @Test
    void migratesEveryFrozenVersionOneFieldToExactVersionTwo() throws Exception
    {
        String migrated = Format7WebUserPreferencesCodec.migrateFromFormat6(VERSION_ONE);
        Format7WebUserPreferencesCodec.validate(migrated);
        JsonNode document = MAPPER.readTree(migrated);

        assertEquals(2, document.path("version").asInt());
        assertEquals("dark", document.path("appearance").path("theme").asText());
        assertTrue(document.path("page_titles").path("prepend_playing_call").asBoolean());
        assertEquals(0.4, document.path("playback").path("volume").asDouble());
        assertEquals(9, document.path("playback").path("selected_scan_list_ids").get(0).asLong());
        assertEquals(3, document.path("playback").path("selected_scan_list_ids").get(1).asLong());
        assertTrue(document.path("playback").path("conversation_grouping").asBoolean());
        assertEquals(4, document.path("playback").path("conversation_burst_limit").asInt());
        assertEquals("advanced", document.path("scanner").path("detail_mode").asText());
        assertEquals("detailed",
            document.path("presentation").path("decode_quality_display_mode").asText());
        assertEquals(125, document.path("presentation").path("live_detail_row_limit").asInt());
        assertEquals("high-detail", document.path("tuner").path("profile").asText());
        assertEquals("talkgroup",
            document.path("tables").path("scanner.calls").path("column_order").get(0).asText());
        assertEquals(240,
            document.path("tables").path("scanner.calls").path("column_widths").path("alias").asInt());
    }

    @Test
    void refusesAFormatSixSelectionThatFormatSevenCannotRepresent() throws Exception
    {
        String historicalMaximum = withSelectedScanLists(128);
        Format6WebUserPreferencesCodec.validate(historicalMaximum);

        Format7WebUserPreferencesCodec.SelectedScanListLimitException rejection = assertThrows(
            Format7WebUserPreferencesCodec.SelectedScanListLimitException.class,
            () -> Format7WebUserPreferencesCodec.migrateFromFormat6(historicalMaximum));
        assertEquals(128, rejection.selected());
        assertEquals(16, rejection.maximum());
    }

    @Test
    void validatesOnlyTheExactFrozenVersionTwoShape() throws Exception
    {
        String versionTwo = Format7WebUserPreferencesCodec.migrateFromFormat6(VERSION_ONE);
        Format7WebUserPreferencesCodec.validate(versionTwo);

        assertThrows(IOException.class, () -> Format7WebUserPreferencesCodec.validate(VERSION_ONE));
        assertThrows(IOException.class, () -> Format7WebUserPreferencesCodec.validate(
            versionTwo.replace("\"version\":2", "\"version\":2,\"unknown\":true")));
        assertThrows(IOException.class, () -> Format7WebUserPreferencesCodec.validate(
            versionTwo.replace(",\"conversation_grouping\":true", "")));
        assertThrows(IOException.class, () -> Format7WebUserPreferencesCodec.validate(
            versionTwo.replace("\"conversation_burst_limit\":4", "\"conversation_burst_limit\":21")));
        assertThrows(IOException.class, () -> Format7WebUserPreferencesCodec.validate(
            versionTwo.replace("\"version\":2", "\"version\":3")));
        assertFalse(versionTwo.contains("\"version\":1"));
    }

    private static String withSelectedScanLists(int count)
    {
        String ids = LongStream.rangeClosed(1, count).mapToObj(Long::toString).collect(Collectors.joining(","));
        return VERSION_ONE.replace("\"selected_scan_list_ids\":[9,3]",
            "\"selected_scan_list_ids\":[" + ids + "]");
    }
}
