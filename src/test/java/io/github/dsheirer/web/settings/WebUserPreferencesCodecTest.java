/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;

class WebUserPreferencesCodecTest
{
    private static final String DEFAULT_JSON = """
        {"version":11,"appearance":{"theme":"light","hue":null},"page_titles":{"prepend_playing_call":true},"playback":{"volume":1.0,"selected_scan_list_ids":[],"target_grouping":true,"target_burst_limit":4},"scanner":{"detail_mode":"normal"},"presentation":{"show_encryption_details":true,"show_control_decode_quality":true,"show_voice_decode_quality":true,"decode_quality_display_mode":"percentage","live_detail_row_limit":200,"show_only_active_trunked_channels":true,"retain_last_call_on_idle_rows":false,"clear_voice_quality_when_idle":false,"source_name_display":"talker_alias","live_channel_sort":"order_appeared","live_row_density":"normal"},"tuner":{"floor_db":-140,"ceiling_db":0,"waterfall_speed":1.0,"snap_frequency":true,"smooth_fft":true,"highlight_waterfall_channels":false,"show_idle_channels":false,"profile":"balanced"},"health_alerts":{"disabled_codes":[]},"tables":{}}""";

    @Test
    void defaultsHaveTheExactVersionElevenSnakeCaseWireShape() throws Exception
    {
        assertEquals(DEFAULT_JSON, WebUserPreferencesCodec.encode(WebUserPreferences.defaults()));
        assertEquals(WebUserPreferences.defaults(), WebUserPreferencesCodec.decode(DEFAULT_JSON));
    }

    @Test
    void preservesSavedPageTitleChoicesIndependentlyOfTheDefault() throws Exception
    {
        assertTrue(WebUserPreferences.defaults().pageTitles().prependPlayingCall());
        for(boolean enabled: List.of(false, true))
        {
            String saved = DEFAULT_JSON.replace("\"prepend_playing_call\":true",
                "\"prepend_playing_call\":" + enabled);
            WebUserPreferences preferences = WebUserPreferencesCodec.decode(saved);
            assertEquals(enabled, preferences.pageTitles().prependPlayingCall());
            assertEquals(saved, WebUserPreferencesCodec.encode(preferences));
        }
    }

    @Test
    void roundTripsTheOriginalPaletteAndBoundedCustomHueWithoutRelaxingTheDocument() throws Exception
    {
        assertEquals(null, WebUserPreferencesCodec.decode(DEFAULT_JSON).appearance().hue());
        for(int hue: List.of(0, 215, 359))
        {
            String custom = DEFAULT_JSON.replace("\"hue\":null", "\"hue\":" + hue);
            WebUserPreferences preferences = WebUserPreferencesCodec.decode(custom);
            assertEquals(hue, preferences.appearance().hue());
            assertEquals(preferences, WebUserPreferencesCodec.decode(custom.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals(custom, WebUserPreferencesCodec.encode(preferences));
        }
        for(String invalid: List.of("-1", "360", "215.5", "\"215\"", "true", "[]", "{}", "2147483648"))
        {
            assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(
                DEFAULT_JSON.replace("\"hue\":null", "\"hue\":" + invalid)));
        }
        assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(
            DEFAULT_JSON.replace(",\"hue\":null", "")));
        assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(
            DEFAULT_JSON.replace("\"hue\":null", "\"hue\":null,\"hue\":215")));
        assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(
            DEFAULT_JSON.replace("\"hue\":null", "\"hue\":null,\"unknown\":true")));
        assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(
            DEFAULT_JSON.replace("\"theme\":\"light\"", "\"theme\":null")));
        assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(
            DEFAULT_JSON.replace("\"appearance\":{\"theme\":\"light\",\"hue\":null}", "\"appearance\":null")));
        assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(
            DEFAULT_JSON.replace("\"version\":11", "\"version\":7")));
    }

    @Test
    void savedInactiveRowsChoiceRemainsOff() throws Exception
    {
        String savedOff = DEFAULT_JSON.replace("\"show_only_active_trunked_channels\":true",
            "\"show_only_active_trunked_channels\":false");
        assertFalse(WebUserPreferencesCodec.decode(savedOff).presentation().showOnlyActiveTrunkedChannels());
    }

    @Test
    void defaultsToTalkerAliasAndRoundTripsOnlySupportedSourceNameChoices() throws Exception
    {
        assertEquals("talker_alias", WebUserPreferences.defaults().presentation().sourceNameDisplay());
        for(String mode: List.of("talker_alias", "source_alias", "both"))
        {
            String document = DEFAULT_JSON.replace("\"source_name_display\":\"talker_alias\"",
                "\"source_name_display\":\"" + mode + "\"");
            assertEquals(mode, WebUserPreferencesCodec.decode(document).presentation().sourceNameDisplay());
            assertEquals(document, WebUserPreferencesCodec.encode(WebUserPreferencesCodec.decode(document)));
        }
        for(String invalid: List.of("\"unknown\"", "null", "true", "[]", "{}", "1"))
        {
            assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace(
                "\"source_name_display\":\"talker_alias\"", "\"source_name_display\":" + invalid)));
        }
        assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace(
            ",\"source_name_display\":\"talker_alias\"", "")));
    }

    @Test
    void savesEachSortMethodIndependentlyOfTheActiveOnlyChoice() throws Exception
    {
        for(boolean activeOnly: List.of(false, true))
        {
            for(String sort: List.of("lcn", "order_appeared", "frequency"))
            {
                String json = DEFAULT_JSON.replace("order_appeared", sort).replace(
                    "\"show_only_active_trunked_channels\":true",
                    "\"show_only_active_trunked_channels\":" + activeOnly);
                WebUserPreferences preferences = WebUserPreferencesCodec.decode(json);
                assertEquals(sort, preferences.presentation().liveChannelSort());
                assertEquals(activeOnly, preferences.presentation().showOnlyActiveTrunkedChannels());
                assertEquals(json, WebUserPreferencesCodec.encode(preferences));
            }
        }
        for(String invalid: List.of("\"unknown\"", "null", "true", "5", "[]", "{}"))
        {
            assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(
                DEFAULT_JSON.replace("\"order_appeared\"", invalid)));
        }
        assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(
            DEFAULT_JSON.replace(",\"live_channel_sort\":\"order_appeared\"", "")));
    }

    @Test
    void defaultsToNormalAndRoundTripsOnlySupportedLiveRowDensityChoices() throws Exception
    {
        assertEquals("normal", WebUserPreferences.defaults().presentation().liveRowDensity());
        for(String density: List.of("normal", "dense"))
        {
            String document = DEFAULT_JSON.replace("\"live_row_density\":\"normal\"",
                "\"live_row_density\":\"" + density + "\"");
            WebUserPreferences preferences = WebUserPreferencesCodec.decode(document);
            assertEquals(density, preferences.presentation().liveRowDensity());
            assertEquals(document, WebUserPreferencesCodec.encode(preferences));
            assertEquals(preferences, WebUserPreferencesCodec.decode(
                document.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        for(String invalid: List.of("\"unknown\"", "null", "true", "[]", "{}", "1"))
        {
            assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace(
                "\"live_row_density\":\"normal\"", "\"live_row_density\":" + invalid)));
        }
        assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace(
            ",\"live_row_density\":\"normal\"", "")));
        assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace(
            "\"live_row_density\":\"normal\"", "\"live_row_density\":\"normal\",\"live_row_density\":\"dense\"")));
        assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace(
            "\"version\":11", "\"version\":10")));
    }

    @Test
    void enforcesSharedTableAndTunerBounds() throws Exception
    {
        WebUserPreferences defaults = WebUserPreferences.defaults();
        WebUserPreferences.TableLayout valid = new WebUserPreferences.TableLayout(
            List.of("alias", "talkgroup"), List.of("talkgroup", "alias"), Map.of("alias", 240),
            List.of("talkgroup"), List.of("p25-phase1"));
        WebUserPreferences withTable = new WebUserPreferences(defaults.version(), defaults.appearance(),
            defaults.pageTitles(), defaults.playback(), defaults.scanner(), defaults.presentation(),
            defaults.tuner(), defaults.healthAlerts(), Map.of("scanner.calls", valid));
        assertEquals(withTable, WebUserPreferencesCodec.decode(WebUserPreferencesCodec.encode(withTable)));
        String encodedTable = WebUserPreferencesCodec.encode(withTable);
        assertThrows(java.io.IOException.class, () -> WebUserPreferencesCodec.decode(
            encodedTable.replace(",\"collapsed_groups\":[\"p25-phase1\"]", "")));

        assertThrows(IllegalArgumentException.class, () -> new WebUserPreferences.TableLayout(
            List.of("alias"), List.of("alias"), Map.of(), List.of("alias"), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WebUserPreferences.TableLayout(
            List.of("alias"), List.of("alias"), Map.of("alias", 47), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new WebUserPreferences.TableLayout(
            List.of("alias"), List.of("alias"), Map.of(), List.of(), List.of("P25 Phase 1")));
        assertThrows(IllegalArgumentException.class, () -> new WebUserPreferences.TableLayout(
            List.of("alias"), List.of("alias"), Map.of(), List.of(),
            List.of("p25-phase1", "p25-phase1")));
        List<String> tooManyGroups = java.util.stream.IntStream
            .rangeClosed(0, WebUserPreferences.MAXIMUM_COLLAPSED_GROUPS_PER_TABLE)
            .mapToObj(index -> "group-" + index)
            .toList();
        assertThrows(IllegalArgumentException.class, () -> new WebUserPreferences.TableLayout(
            List.of("alias"), List.of("alias"), Map.of(), List.of(), tooManyGroups));
        assertThrows(IllegalArgumentException.class, () -> new WebUserPreferences.Tuner(
            -140, 0, 4.01, true, true, false, false, "balanced"));
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
            () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace("\"version\":11",
                "\"version\":11,\"unknown\":true")));
        assertThrows(java.io.IOException.class,
            () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace("\"theme\":\"light\"",
                "\"theme\":\"light\",\"theme\":\"dark\"")));
        assertThrows(java.io.IOException.class,
            () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace("\"live_detail_row_limit\":200",
                "\"live_detail_row_limit\":200.5")));
        assertThrows(java.io.IOException.class,
            () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace("\"version\":11", "\"version\":3")));
        assertThrows(java.io.IOException.class,
            () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace(
                ",\"target_grouping\":true", "")));
        assertThrows(java.io.IOException.class,
            () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace(
                ",\"health_alerts\":{\"disabled_codes\":[]}", "")));
        assertThrows(java.io.IOException.class,
            () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace(
                ",\"show_only_active_trunked_channels\":true", "")));
        assertThrows(java.io.IOException.class,
            () -> WebUserPreferencesCodec.decode(DEFAULT_JSON.replace(
                ",\"show_idle_channels\":false", "")));
    }
}
