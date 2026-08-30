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
import io.github.dsheirer.web.settings.WebUserPreferences;
import io.github.dsheirer.web.settings.WebUserPreferencesCodec;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class Format9WebUserPreferencesCodecTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String VERSION_THREE = """
        {"version":3,"appearance":{"theme":"dark"},"page_titles":{"prepend_playing_call":true},"playback":{"volume":0.4,"selected_scan_list_ids":[9,3],"conversation_grouping":false,"conversation_burst_limit":7},"scanner":{"detail_mode":"advanced"},"presentation":{"show_encryption_details":false,"show_control_decode_quality":true,"show_voice_decode_quality":false,"decode_quality_display_mode":"detailed","live_detail_row_limit":125},"tuner":{"floor_db":-150,"ceiling_db":-10,"waterfall_speed":2.0,"snap_frequency":false,"smooth_fft":true,"highlight_waterfall_channels":true,"profile":"high-detail"},"health_alerts":{"disabled_codes":["disk-space"]},"tables":{"scanner.calls":{"schema":["alias","talkgroup"],"column_order":["talkgroup","alias"],"column_widths":{"alias":240},"hidden_columns":["talkgroup"]}}}""";

    @Test
    void migratesEveryFrozenVersionThreeFieldAndSeedsPresentationChoices() throws Exception
    {
        String migrated = Format9WebUserPreferencesCodec.migrateFromFormat8(VERSION_THREE, true, false);
        Format9WebUserPreferencesCodec.validate(migrated);
        WebUserPreferences runtime = WebUserPreferencesCodec.decode(migrated);
        JsonNode document = MAPPER.readTree(migrated);

        assertEquals(4, runtime.version());
        assertFalse(runtime.presentation().showOnlyActiveTrunkedChannels());
        assertTrue(runtime.presentation().retainLastCallOnIdleRows());
        assertFalse(runtime.presentation().clearVoiceQualityWhenIdle());
        assertEquals("dark", document.path("appearance").path("theme").asText());
        assertEquals(0.4, document.path("playback").path("volume").asDouble());
        assertEquals(7, document.path("playback").path("conversation_burst_limit").asInt());
        assertEquals("advanced", document.path("scanner").path("detail_mode").asText());
        assertEquals(125, document.path("presentation").path("live_detail_row_limit").asInt());
        assertEquals("disk-space", document.path("health_alerts").path("disabled_codes").get(0).asText());
        assertEquals("talkgroup",
            document.path("tables").path("scanner.calls").path("column_order").get(0).asText());
    }

    @Test
    void validatesOnlyTheExactFrozenVersionFourShape() throws Exception
    {
        String versionFour = Format9WebUserPreferencesCodec.migrateFromFormat8(VERSION_THREE, false, true);
        Format9WebUserPreferencesCodec.validate(versionFour);

        assertThrows(IOException.class, () -> Format9WebUserPreferencesCodec.validate(VERSION_THREE));
        assertThrows(IOException.class, () -> Format9WebUserPreferencesCodec.validate(
            versionFour.replace("\"version\":4", "\"version\":4,\"unknown\":true")));
        assertThrows(IOException.class, () -> Format9WebUserPreferencesCodec.validate(
            versionFour.replace(",\"show_only_active_trunked_channels\":false", "")));
        assertThrows(IOException.class, () -> Format9WebUserPreferencesCodec.validate(
            versionFour.replace("\"retain_last_call_on_idle_rows\":false",
                "\"retain_last_call_on_idle_rows\":null")));
        assertThrows(IOException.class, () -> Format9WebUserPreferencesCodec.validate(
            versionFour.replace("\"version\":4", "\"version\":5")));
    }
}
