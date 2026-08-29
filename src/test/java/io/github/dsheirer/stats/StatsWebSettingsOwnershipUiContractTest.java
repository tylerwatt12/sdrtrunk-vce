/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class StatsWebSettingsOwnershipUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");

    @Test
    void separatesThreeSharedSiteSettingsFromPersonalPreferences() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String site = function(source, "async function renderAdminSiteBehaviorSettings()");
        String request = function(source, "async function requestSiteSettings(method = 'GET', settings = null, revision = null)");
        String personal = function(source, "async function renderSettings()");
        String livePresentation = function(source, "function openLivePresentationSettings(returnFocusSelector = null)");
        String scannerPlayback = function(source, "function openScannerPlaybackSettings(returnFocusSelector = null)");
        String admin = function(source, "async function renderAdmin()");

        assertTrue(admin.contains("id: 'site-settings', label: 'Site Settings'"));
        assertTrue(request.contains("'/api/v1/admin/site-settings'"));
        assertTrue(request.contains("headers['If-Match'] = `\"${revision}\"`"));
        assertTrue(site.contains("confirmed?.revision"));
        assertTrue(site.contains("error?.code === 'site_settings_conflict'"));
        assertTrue(site.contains("Current server values were reloaded"));
        assertTrue(site.contains("retain_idle_call_details"));
        assertTrue(site.contains("clear_voice_decode_quality_on_call_end"));
        assertTrue(site.contains("traffic_grant_age_out_milliseconds"));
        assertFalse(site.contains("show_encryption_details"));
        assertFalse(site.contains("show_control_decode_quality"));
        assertFalse(site.contains("live_detail_row_limit"));

        assertTrue(personal.contains("userPreferenceController.snapshot()"));
        assertTrue(personal.contains("prepend_playing_call"));
        assertTrue(personal.contains("settingsCard('Page titles'"));
        assertFalse(personal.contains("Live presentation"));
        assertFalse(personal.contains("preferences.presentation"));
        assertFalse(personal.contains("show_encryption_details"));
        assertFalse(personal.contains("show_control_decode_quality"));
        assertFalse(personal.contains("show_voice_decode_quality"));
        assertFalse(personal.contains("live_detail_row_limit"));
        assertFalse(personal.contains("appearance.theme"));
        assertFalse(personal.contains("playback.volume"));
        assertFalse(personal.contains("selected_scan_list_ids"));
        assertFalse(personal.contains("conversation_grouping"));
        assertFalse(personal.contains("conversation_burst_limit"));
        assertFalse(personal.contains("scanner.detail_mode"));
        assertFalse(personal.contains("preferences.tuner"));
        assertFalse(personal.contains("preferences.tables"));
        assertFalse(personal.contains("retain_idle_call_details"));
        assertFalse(personal.contains("traffic_grant_age_out_milliseconds"));

        assertTrue(livePresentation.contains("openReadOnlyModal('Live presentation'"));
        assertTrue(livePresentation.contains("show_encryption_details"));
        assertTrue(livePresentation.contains("show_control_decode_quality"));
        assertTrue(livePresentation.contains("show_voice_decode_quality"));
        assertTrue(livePresentation.contains("decode_quality_display_mode"));
        assertTrue(livePresentation.contains("live_detail_row_limit"));
        assertTrue(livePresentation.contains("preferences.presentation ="));
        assertFalse(livePresentation.contains("conversation_grouping"));
        assertFalse(livePresentation.contains("conversation_burst_limit"));
        assertFalse(livePresentation.contains("preferences.playback"));
        assertFalse(livePresentation.contains("preferences.page_titles"));
        assertTrue(livePresentation.contains("modal.setDirty(true)"));
        assertTrue(livePresentation.contains("void render()"));

        assertTrue(scannerPlayback.contains("openReadOnlyModal('Scanner playback'"));
        assertTrue(scannerPlayback.contains("conversation_grouping"));
        assertTrue(scannerPlayback.contains("conversation_burst_limit"));
        assertTrue(scannerPlayback.contains("preferences.playback.conversation_grouping ="));
        assertTrue(scannerPlayback.contains("preferences.playback.conversation_burst_limit ="));
        assertFalse(scannerPlayback.contains("preferences.presentation"));
        assertTrue(source.contains("id = 'scanner-playback-settings'"));
        assertTrue(source.contains("openScannerPlaybackSettings('#scanner-playback-settings')"));

        assertFalse(source.contains("/api/v1/admin/web-display"));
        assertFalse(source.contains("/api/v1/live/settings"));
        assertFalse(source.contains("renderAdminWebDisplaySettings"));
    }

    @Test
    void serverRegistersOnlyTheNewSettingsOwners() throws Exception
    {
        String service = Files.readString(Path.of("src", "main", "java", "io", "github", "dsheirer",
            "stats", "StatsWebServerService.java"));
        assertTrue(service.contains("WebSiteSettingsHttpController.PATH"));
        assertTrue(service.contains("WebUserPreferencesHttpController.PATH"));
        assertTrue(service.contains("WebCapability.USER_SETTINGS"));
        assertFalse(service.contains("WebDisplaySettings"));
        assertFalse(service.contains("/api/v1/live/settings"));
    }

    private static String function(String source, String marker)
    {
        int start = source.indexOf(marker);
        assertTrue(start >= 0, marker);
        int opening = source.indexOf('{', start);
        int depth = 0;
        for(int index = opening; index < source.length(); index++)
        {
            char character = source.charAt(index);
            if(character == '{')
            {
                depth++;
            }
            else if(character == '}' && --depth == 0)
            {
                return source.substring(start, index + 1);
            }
        }
        throw new AssertionError("Unclosed function: " + marker);
    }
}
