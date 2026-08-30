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
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class Format8WebUserPreferencesCodecTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VERSION_TWO = """
        {"version":2,"appearance":{"theme":"dark"},"page_titles":{"prepend_playing_call":true},"playback":{"volume":0.4,"selected_scan_list_ids":[9,3],"conversation_grouping":false,"conversation_burst_limit":7},"scanner":{"detail_mode":"advanced"},"presentation":{"show_encryption_details":false,"show_control_decode_quality":true,"show_voice_decode_quality":false,"decode_quality_display_mode":"detailed","live_detail_row_limit":125},"tuner":{"floor_db":-150,"ceiling_db":-10,"waterfall_speed":2.0,"snap_frequency":false,"smooth_fft":true,"highlight_waterfall_channels":true,"profile":"high-detail"},"tables":{"scanner.calls":{"schema":["alias","talkgroup"],"column_order":["talkgroup","alias"],"column_widths":{"alias":240},"hidden_columns":["talkgroup"]}}}""";

    @Test
    void migratesEveryFrozenVersionTwoFieldToExactVersionThree() throws Exception
    {
        String migrated = Format8WebUserPreferencesCodec.migrateFromFormat7(VERSION_TWO);
        Format8WebUserPreferencesCodec.validate(migrated);
        JsonNode document = MAPPER.readTree(migrated);

        assertEquals(3, document.path("version").asInt());
        assertEquals("dark", document.path("appearance").path("theme").asText());
        assertTrue(document.path("page_titles").path("prepend_playing_call").asBoolean());
        assertEquals(0.4, document.path("playback").path("volume").asDouble());
        assertEquals(9, document.path("playback").path("selected_scan_list_ids").get(0).asLong());
        assertEquals(3, document.path("playback").path("selected_scan_list_ids").get(1).asLong());
        assertFalse(document.path("playback").path("conversation_grouping").asBoolean());
        assertEquals(7, document.path("playback").path("conversation_burst_limit").asInt());
        assertEquals("advanced", document.path("scanner").path("detail_mode").asText());
        assertEquals("detailed", document.path("presentation").path("decode_quality_display_mode").asText());
        assertEquals(125, document.path("presentation").path("live_detail_row_limit").asInt());
        assertEquals("high-detail", document.path("tuner").path("profile").asText());
        assertEquals(0, document.path("health_alerts").path("disabled_codes").size());
        assertEquals("talkgroup",
            document.path("tables").path("scanner.calls").path("column_order").get(0).asText());
        assertEquals(240,
            document.path("tables").path("scanner.calls").path("column_widths").path("alias").asInt());
    }

    @Test
    void validatesCanonicalBoundedStableDisabledCodes() throws Exception
    {
        String versionThree = Format8WebUserPreferencesCodec.migrateFromFormat7(VERSION_TWO);
        String withDisabled = versionThree.replace("\"disabled_codes\":[]",
            "\"disabled_codes\":[\"receiver-iq-drop\",\"disk-space\"]");
        Format8WebUserPreferencesCodec.validate(withDisabled);

        assertThrows(IOException.class, () -> Format8WebUserPreferencesCodec.validate(
            withDisabled.replace("\"disk-space\"]", "\"receiver-iq-drop\"]")));
        assertThrows(IOException.class, () -> Format8WebUserPreferencesCodec.validate(
            withDisabled.replace("\"disk-space\"", "\"Receiver IQ Drop\"")));

        String codes = IntStream.rangeClosed(0, 128)
            .mapToObj(index -> "\"alert-" + index + "\"")
            .collect(Collectors.joining(","));
        assertThrows(IOException.class, () -> Format8WebUserPreferencesCodec.validate(
            versionThree.replace("\"disabled_codes\":[]", "\"disabled_codes\":[" + codes + "]")));
    }

    @Test
    void validatesOnlyTheExactFrozenVersionThreeShape() throws Exception
    {
        String versionThree = Format8WebUserPreferencesCodec.migrateFromFormat7(VERSION_TWO);
        Format8WebUserPreferencesCodec.validate(versionThree);

        assertThrows(IOException.class, () -> Format8WebUserPreferencesCodec.validate(VERSION_TWO));
        assertThrows(IOException.class, () -> Format8WebUserPreferencesCodec.validate(
            versionThree.replace("\"version\":3", "\"version\":3,\"unknown\":true")));
        assertThrows(IOException.class, () -> Format8WebUserPreferencesCodec.validate(
            versionThree.replace(",\"health_alerts\":{\"disabled_codes\":[]}", "")));
        assertThrows(IOException.class, () -> Format8WebUserPreferencesCodec.validate(
            versionThree.replace("\"disabled_codes\":[]", "\"disabled_codes\":null")));
        assertThrows(IOException.class, () -> Format8WebUserPreferencesCodec.validate(
            versionThree.replace("\"version\":3", "\"version\":4")));
        assertFalse(versionThree.contains("\"version\":2"));
    }
}
