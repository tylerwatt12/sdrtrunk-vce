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
    void separatesSharedTrafficTimingFromPersonalLivePresentation() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String site = function(source, "async function renderAdminSiteBehaviorSettings()");
        String request = function(source, "async function requestSiteSettings(method = 'GET', settings = null, revision = null)");
        String bandplanRequest = function(source,
            "async function requestP25BandplanOverrides(method = 'GET', profiles = null)");
        String bandplanPage = function(source, "async function renderAdminP25BandplanOverrides()");
        String bandplanPrefill = function(source, "function p25OverrideCreateRouteProfile(parameters)");
        String clearBandplanPrefill = function(source, "function clearP25OverrideCreateRoute()");
        String personal = function(source, "async function renderSettings()");
        String personalSummary = function(source, "function userPreferenceSummaryCards(preferences)");
        String personalReset = function(source, "function openResetUserPreferences(returnFocusSelector = null)");
        String livePresentation = function(source, "function openLivePresentationSettings(returnFocusSelector = null)");
        String scannerPlayback = function(source, "function openScannerSettings(returnFocusSelector = null)");
        String admin = function(source, "async function renderAdmin()");

        assertTrue(admin.contains("id: 'site-settings', label: 'Site Settings'"));
        assertTrue(admin.contains("id: 'p25-bandplans', label: 'P25 Bandplan Overrides'"));
        assertTrue(admin.contains("await renderAdminP25BandplanOverrides()"));
        assertTrue(request.contains("'/api/v1/admin/site-settings'"));
        assertTrue(request.contains("headers['If-Match'] = `\"${revision}\"`"));
        assertTrue(bandplanRequest.contains("jsonDocumentFetch('/api/v1/admin/p25-bandplan-overrides'"));
        assertFalse(bandplanRequest.contains("requestJson("));
        assertTrue(bandplanPrefill.contains("parameters.get('createP25Override') !== '1'"));
        assertTrue(bandplanPrefill.contains("wacn: hexValue('wacn', 5, 0xFFFFF)"));
        assertTrue(bandplanPrefill.contains("rfss: hexValue('rfss', 2, 0xFF)"));
        assertTrue(bandplanPrefill.contains("site: hexValue('site', 2, 0xFF)"));
        assertTrue(bandplanPage.contains("p25OverrideSameScope(profile, requestedProfile)"));
        assertTrue(bandplanPage.contains("list.prepend(requestedCard)"));
        assertTrue(bandplanPage.contains("Enter its replacement bands, then save."));
        assertTrue(bandplanPage.contains("clearP25OverrideCreateRoute()"));
        assertTrue(clearBandplanPrefill.contains("window.history.replaceState({}, '', currentHref())"));
        assertTrue(site.contains("confirmed?.revision"));
        assertTrue(site.contains("error?.code === 'site_settings_conflict'"));
        assertTrue(site.contains("Current server values were reloaded"));
        assertFalse(site.contains("retain_idle_call_details"));
        assertFalse(site.contains("clear_voice_decode_quality_on_call_end"));
        assertTrue(site.contains("traffic_grant_age_out_milliseconds"));
        assertFalse(site.contains("show_encryption_details"));
        assertFalse(site.contains("show_control_decode_quality"));
        assertFalse(site.contains("live_detail_row_limit"));

        assertTrue(personal.contains("userPreferenceController.snapshot()"));
        assertTrue(personal.contains("A read-only overview of every personal preference"));
        assertTrue(personal.contains("userPreferenceSummaryCards(current)"));
        assertTrue(personal.contains("Reset All Personal Preferences"));
        assertTrue(personal.contains("openResetUserPreferences"));
        assertFalse(personal.contains("preferenceCheckbox("));
        assertFalse(personal.contains("updateUserPreferences("));
        assertTrue(personalSummary.contains("appearance.theme"));
        assertTrue(personalSummary.contains("page_titles.prepend_playing_call"));
        assertTrue(personalSummary.contains("playback.volume"));
        assertTrue(personalSummary.contains("selected_scan_list_ids"));
        assertTrue(personalSummary.contains("conversation_grouping"));
        assertTrue(personalSummary.contains("conversation_burst_limit"));
        assertTrue(personalSummary.contains("scanner.detail_mode"));
        assertTrue(personalSummary.contains("presentation.show_encryption_details"));
        assertTrue(personalSummary.contains("presentation.show_control_decode_quality"));
        assertTrue(personalSummary.contains("presentation.show_voice_decode_quality"));
        assertTrue(personalSummary.contains("presentation.decode_quality_display_mode"));
        assertTrue(personalSummary.contains("presentation.live_detail_row_limit"));
        assertTrue(personalSummary.contains("presentation.show_only_active_trunked_channels"));
        assertTrue(personalSummary.contains("presentation.retain_last_call_on_idle_rows"));
        assertTrue(personalSummary.contains("presentation.clear_voice_quality_when_idle"));
        assertTrue(personalSummary.contains("preferences.tuner.floor_db"));
        assertTrue(personalSummary.contains("preferences.tuner.ceiling_db"));
        assertTrue(personalSummary.contains("preferences.tuner.waterfall_speed"));
        assertTrue(personalSummary.contains("preferences.tuner.snap_frequency"));
        assertTrue(personalSummary.contains("preferences.tuner.smooth_fft"));
        assertTrue(personalSummary.contains("preferences.tuner.highlight_waterfall_channels"));
        assertTrue(personalSummary.contains("preferences.tuner.profile"));
        assertTrue(personalSummary.contains("preferences.health_alerts.disabled_codes"));
        assertTrue(personalSummary.contains("preferences.tables"));
        assertTrue(personalReset.contains("updateUserPreferences(() => preferenceSchema.defaults, false)"));
        assertTrue(personalReset.contains("does not change the username, password"));
        assertTrue(personalReset.contains("error?.code === 'preference_conflict'"));
        assertTrue(personalReset.contains("if (modal.close()) void render()"));
        assertFalse(personalReset.contains("reset.focus()"));
        assertFalse(personal.contains("retain_idle_call_details"));
        assertFalse(personal.contains("traffic_grant_age_out_milliseconds"));

        assertTrue(livePresentation.contains("openReadOnlyModal('Live presentation'"));
        assertTrue(livePresentation.contains("show_encryption_details"));
        assertTrue(livePresentation.contains("show_control_decode_quality"));
        assertTrue(livePresentation.contains("show_voice_decode_quality"));
        assertTrue(livePresentation.contains("decode_quality_display_mode"));
        assertTrue(livePresentation.contains("live_detail_row_limit"));
        assertTrue(livePresentation.contains("show_only_active_trunked_channels"));
        assertTrue(livePresentation.contains("retain_last_call_on_idle_rows"));
        assertTrue(livePresentation.contains("clear_voice_quality_when_idle"));
        assertTrue(livePresentation.contains("preferences.presentation ="));
        assertFalse(livePresentation.contains("conversation_grouping"));
        assertFalse(livePresentation.contains("conversation_burst_limit"));
        assertFalse(livePresentation.contains("preferences.playback"));
        assertFalse(livePresentation.contains("preferences.page_titles"));
        assertTrue(livePresentation.contains("modal.setDirty(true)"));
        assertTrue(livePresentation.contains("void render()"));

        assertTrue(scannerPlayback.contains("openReadOnlyModal('Scanner settings'"));
        assertTrue(scannerPlayback.contains("conversation_grouping"));
        assertTrue(scannerPlayback.contains("conversation_burst_limit"));
        assertTrue(scannerPlayback.contains("page_titles.prepend_playing_call"));
        assertTrue(scannerPlayback.contains("preferences.playback.conversation_grouping ="));
        assertTrue(scannerPlayback.contains("preferences.playback.conversation_burst_limit ="));
        assertTrue(scannerPlayback.contains("preferences.page_titles.prepend_playing_call ="));
        assertFalse(scannerPlayback.contains("preferences.presentation"));
        assertTrue(source.contains("id = 'scanner-settings'"));
        assertTrue(source.contains("openScannerSettings('#scanner-settings')"));

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
        assertTrue(service.contains("P25BandplanOverrideHttpController.PATH"));
        assertTrue(service.contains("WebUserPreferencesHttpController.PATH"));
        assertTrue(service.contains("WebCapability.USER_SETTINGS"));
        assertFalse(service.contains("WebDisplaySettings"));
        assertFalse(service.contains("/api/v1/live/settings"));
    }

    @Test
    void usesThePersonalSettingsCapabilityForThePageAndApi() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String routes = Files.readString(Path.of("stats-web", "assets", "core", "routes.js"));
        String access = function(source, "function routeDefinitionAllowed(definition)");

        assertTrue(routes.contains("id: 'settings', label: 'My Settings', title: 'My Settings', parent: null, " +
            "capability: 'user-settings'"));
        assertFalse(routes.contains("access: 'authenticated'"));
        assertFalse(access.contains("definition.access === 'authenticated'"));
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
