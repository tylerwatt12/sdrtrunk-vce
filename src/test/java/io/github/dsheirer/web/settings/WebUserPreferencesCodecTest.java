/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class WebUserPreferencesCodecTest
{
    private static final String DEFAULT_JSON = """
        {"version":3,"appearance":{"theme":"light"},"page_titles":{"prepend_playing_call":false},"playback":{"volume":1.0,"selected_scan_list_ids":[],"conversation_grouping":true,"conversation_burst_limit":4},"scanner":{"detail_mode":"normal"},"presentation":{"show_encryption_details":true,"show_control_decode_quality":true,"show_voice_decode_quality":true,"decode_quality_display_mode":"percentage","live_detail_row_limit":200},"tuner":{"floor_db":-140,"ceiling_db":0,"waterfall_speed":1.0,"snap_frequency":true,"smooth_fft":true,"highlight_waterfall_channels":false,"profile":"balanced"},"health_alerts":{"disabled_codes":[]},"tables":{}}""";

    @Test
    void defaultsHaveTheExactVersionThreeSnakeCaseWireShape() throws Exception
    {
        assertEquals(DEFAULT_JSON, WebUserPreferencesCodec.encode(WebUserPreferences.defaults()));
        assertEquals(WebUserPreferences.defaults(), WebUserPreferencesCodec.decode(DEFAULT_JSON));
    }

    @Test
    void enforcesSharedTableAndTunerBounds() throws Exception
    {
        WebUserPreferences defaults = WebUserPreferences.defaults();
        WebUserPreferences.TableLayout valid = new WebUserPreferences.TableLayout(
            List.of("alias", "talkgroup"), List.of("talkgroup", "alias"), Map.of("alias", 240),
            List.of("talkgroup"));
        WebUserPreferences withTable = new WebUserPreferences(defaults.version(), defaults.appearance(),
            defaults.pageTitles(), defaults.playback(), defaults.scanner(), defaults.presentation(),
            defaults.tuner(), defaults.healthAlerts(), Map.of("scanner.calls", valid));
        assertEquals(withTable, WebUserPreferencesCodec.decode(WebUserPreferencesCodec.encode(withTable)));

        assertThrows(IllegalArgumentException.class, () -> new WebUserPreferences.TableLayout(
            List.of("alias"), List.of("alias"), Map.of(), List.of("alias")));
        assertThrows(IllegalArgumentException.class, () -> new WebUserPreferences.TableLayout(
            List.of("alias"), List.of("alias"), Map.of("alias", 47), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WebUserPreferences.Tuner(
            -140, 0, 4.01, true, true, false, "balanced"));
        assertThrows(IllegalArgumentException.class, () -> new WebUserPreferences.Playback(
            1.0, List.of(), true, 0));
        assertThrows(IllegalArgumentException.class, () -> new WebUserPreferences.Playback(
            1.0, List.of(), true, 21));
        assertThrows(IllegalArgumentException.class, () -> new WebUserPreferences(defaults.version(),
            defaults.appearance(), defaults.pageTitles(), defaults.playback(), defaults.scanner(),
            defaults.presentation(), defaults.tuner(), defaults.healthAlerts(), Map.of("Invalid Table", valid)));
    }

    @Test
    void canonicalizesAndBoundsDisabledHealthAlertCodes()
    {
        WebUserPreferences.HealthAlerts alerts = new WebUserPreferences.HealthAlerts(
            List.of("receiver-iq-drop", "disk-space"));
        assertEquals(List.of("disk-space", "receiver-iq-drop"), alerts.disabledCodes());

        assertThrows(IllegalArgumentException.class,
            () -> new WebUserPreferences.HealthAlerts(List.of("receiver-iq-drop", "receiver-iq-drop")));
        assertThrows(IllegalArgumentException.class,
            () -> new WebUserPreferences.HealthAlerts(List.of("Receiver IQ Drop")));

        List<String> tooMany = java.util.stream.IntStream
            .rangeClosed(0, WebUserPreferences.MAXIMUM_DISABLED_HEALTH_ALERT_CODES)
            .mapToObj(index -> "alert-" + index)
            .toList();
        assertThrows(IllegalArgumentException.class, () -> new WebUserPreferences.HealthAlerts(tooMany));
    }

    @Test
    void enforcesTheSixteenScanListSelectionBound()
    {
        List<Long> maximum = LongStream.rangeClosed(1, WebUserPreferences.MAXIMUM_SELECTED_SCAN_LISTS)
            .boxed().toList();
        WebUserPreferences.Playback accepted = new WebUserPreferences.Playback(1.0, maximum, true, 4);
        assertEquals(maximum, accepted.selectedScanListIds());

        List<Long> tooMany = LongStream.rangeClosed(1, WebUserPreferences.MAXIMUM_SELECTED_SCAN_LISTS + 1L)
            .boxed().toList();
        IllegalArgumentException rejection = assertThrows(IllegalArgumentException.class,
            () -> new WebUserPreferences.Playback(1.0, tooMany, true, 4));
        assertTrue(rejection.getMessage().contains("more than 16 scan lists"), rejection.getMessage());
    }

    @Test
    void rejectsUnknownDuplicateAndNonIntegerFields()
    {
        assertThrows(java.io.IOException.class,
            () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace("\"version\":3",
                "\"version\":3,\"unknown\":true")));
        assertThrows(java.io.IOException.class,
            () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace("\"theme\":\"light\"",
                "\"theme\":\"light\",\"theme\":\"dark\"")));
        assertThrows(java.io.IOException.class,
            () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace("\"live_detail_row_limit\":200",
                "\"live_detail_row_limit\":200.5")));
        assertThrows(java.io.IOException.class,
            () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace("\"version\":3", "\"version\":2")));
        assertThrows(java.io.IOException.class,
            () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace(
                ",\"conversation_grouping\":true", "")));
        assertThrows(java.io.IOException.class,
            () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace(
                ",\"health_alerts\":{\"disabled_codes\":[]}", "")));
    }
}
