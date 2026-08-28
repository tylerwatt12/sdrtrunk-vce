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
        assertTrue(personal.contains("show_encryption_details"));
        assertTrue(personal.contains("show_control_decode_quality"));
        assertTrue(personal.contains("show_voice_decode_quality"));
        assertTrue(personal.contains("live_detail_row_limit"));
        assertTrue(personal.contains("prepend_playing_call"));
        assertTrue(personal.contains("settingsCard('Page titles'"));
        assertTrue(personal.contains("settingsCard('Live presentation'"));
        assertFalse(personal.contains("appearance.theme"));
        assertFalse(personal.contains("playback.volume"));
        assertFalse(personal.contains("selected_scan_list_ids"));
        assertFalse(personal.contains("scanner.detail_mode"));
        assertFalse(personal.contains("preferences.tuner"));
        assertFalse(personal.contains("preferences.tables"));
        assertFalse(personal.contains("retain_idle_call_details"));
        assertFalse(personal.contains("traffic_grant_age_out_milliseconds"));

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
