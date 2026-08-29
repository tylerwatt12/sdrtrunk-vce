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

import java.io.IOException;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class Format6WebUserPreferencesCodecTest
{
    private static final String VERSION_ONE = """
        {"version":1,"appearance":{"theme":"dark"},"page_titles":{"prepend_playing_call":true},"playback":{"volume":0.4,"selected_scan_list_ids":[9,3]},"scanner":{"detail_mode":"advanced"},"presentation":{"show_encryption_details":false,"show_control_decode_quality":true,"show_voice_decode_quality":false,"decode_quality_display_mode":"detailed","live_detail_row_limit":125},"tuner":{"floor_db":-150,"ceiling_db":-10,"waterfall_speed":2.0,"snap_frequency":false,"smooth_fft":true,"highlight_waterfall_channels":true,"profile":"high-detail"},"tables":{"scanner.calls":{"schema":["alias","talkgroup"],"column_order":["talkgroup","alias"],"column_widths":{"alias":240},"hidden_columns":["talkgroup"]}}}""";

    @Test
    void validatesTheExactFrozenVersionOneDocument() throws Exception
    {
        Format6WebUserPreferencesCodec.validate(VERSION_ONE);
    }

    @Test
    void createsExactVersionOneDefaultsForTheEarlierMigrationStep() throws Exception
    {
        String defaults = Format6WebUserPreferencesCodec.defaults(true, false, true, "percentage", 200);
        assertTrue(defaults.contains("\"version\":1"));
        assertFalse(defaults.contains("conversation_grouping"));
        assertFalse(defaults.contains("conversation_burst_limit"));
        Format6WebUserPreferencesCodec.validate(defaults);
    }

    @Test
    void validatesTheHistoricalSelectionBound() throws Exception
    {
        String historicalMaximum = withSelectedScanLists(128);

        Format6WebUserPreferencesCodec.validate(historicalMaximum);
        assertThrows(IOException.class, () -> Format6WebUserPreferencesCodec.validate(withSelectedScanLists(129)));
    }

    @Test
    void rejectsUnknownMissingAndWrongVersionFields()
    {
        assertThrows(IOException.class, () -> Format6WebUserPreferencesCodec.validate(
            VERSION_ONE.replace("\"version\":1", "\"version\":1,\"unknown\":true")));
        assertThrows(IOException.class, () -> Format6WebUserPreferencesCodec.validate(
            VERSION_ONE.replace(",\"smooth_fft\":true", "")));
        assertThrows(IOException.class, () -> Format6WebUserPreferencesCodec.validate(
            VERSION_ONE.replace("\"version\":1", "\"version\":2")));
    }

    private static String withSelectedScanLists(int count)
    {
        String ids = LongStream.rangeClosed(1, count).mapToObj(Long::toString).collect(Collectors.joining(","));
        return VERSION_ONE.replace("\"selected_scan_list_ids\":[9,3]",
            "\"selected_scan_list_ids\":[" + ids + "]");
    }
}
