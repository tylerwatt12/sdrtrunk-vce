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
        String audioDock = Files.readString(Path.of("stats-web", "assets", "core", "audio-dock.js"));
        String scannerControls = function(source,
            "function arrangeScannerPlayback(bar, player, signal, options = {})");
        String playerSettings = function(audioDock, "function renderSettings()");
        String receiver = function(source, "async function renderAdminReceiverBehaviorSettings(target = content, renderContext = captureRenderContext())");
        String request = function(source, "async function requestReceiverSettings(method = 'GET', settings = null, revision = null,\n" +
            "    fetchDocument = jsonDocumentFetch)");
        String bandplanRequest = function(source,
            "async function requestP25BandplanOverrides(method = 'GET', profiles = null, fetchDocument = jsonDocumentFetch)");
        String bandplanPage = function(source, "async function renderAdminP25BandplanOverrides(renderContext = captureRenderContext())");
        String personal = function(source, "async function renderSettings()");
        String personalWorkspace = Files.readString(Path.of("stats-web", "assets", "features", "my-settings.js"));
        String preferenceRegistry = Files.readString(Path.of("stats-web", "assets", "core", "preference-registry.js"));
        String personalReset = function(source, "function openResetUserPreferences(returnFocusSelector = null)");
        String livePresentation = function(source, "function openLivePresentationSettings(returnFocusSelector = null)");
        String preferenceSave = function(source, "async function savePreferenceForm(workflow, modal, mutate, options)");
        String admin = function(source, "async function renderAdmin(options = {})");
        String adminGroups = function(source, "function adminSettingsGroups()");

        assertTrue(adminGroups.contains("id: 'display', label: 'Display settings'"));
        assertTrue(admin.contains("renderAdminReceiverBehaviorSettings(display, renderContext)"));
        assertTrue(adminGroups.contains("id: 'protocol-p25', label: 'P25 band plans'"));
        assertFalse(adminGroups.contains("id: 'activity', label: 'Activity history'"));
        assertFalse(adminGroups.contains("id: 'protocol-dmr'"));
        assertTrue(admin.contains("await renderAdminP25BandplanOverrides(renderContext)"));
        assertTrue(request.contains("'/api/v1/admin/receiver-settings'"));
        assertTrue(request.contains("headers['If-Match'] = `\"${revision}\"`"));
        assertTrue(bandplanRequest.contains("fetchDocument('/api/v1/admin/p25-bandplan-overrides'"));
        assertFalse(bandplanRequest.contains("requestJson("));
        assertTrue(bandplanPage.contains("list.replaceChildren(...profiles.map((profile, index) =>"));
        assertTrue(bandplanPage.contains("p25OverrideProfileCard(existing, knownSystems)"));
        assertTrue(bandplanPage.contains("radioSystemLink(system.radio_system_entity_ref, displayName(profile))"));
        assertTrue(bandplanPage.contains("const add = node('button', 'ui-button ui-button-primary', " +
            "'Add P25 override')"));
        assertTrue(bandplanPage.contains("modal = openReadOnlyModal(existing ? `Edit P25 override"));
        assertTrue(bandplanPage.contains("form.addEventListener('input', () => modal.setDirty(true))"));
        assertTrue(bandplanPage.contains("Site overrides take priority over system overrides."));
        assertFalse(source.contains("createP25Override"));
        assertFalse(source.contains("p25OverrideCreateRoute"));
        assertFalse(source.contains("clearP25OverrideCreateRoute"));
        assertTrue(receiver.contains("const panel = section('Live timing', body)"));
        assertTrue(receiver.contains("panel.classList.add('ui-settings-panel')"));
        assertTrue(receiver.contains("target.append(panel)"));
        assertTrue(receiver.contains("confirmed?.revision"));
        assertTrue(receiver.contains("error?.code === 'receiver_settings_conflict'"));
        assertTrue(receiver.contains("Live timing changed elsewhere. The latest saved value was loaded."));
        assertFalse(receiver.contains("retain_idle_call_details"));
        assertFalse(receiver.contains("clear_voice_decode_quality_on_call_end"));
        assertTrue(receiver.contains("traffic_grant_age_out_milliseconds"));
        assertFalse(receiver.contains("Traffic-row idle delay"));
        assertTrue(receiver.contains("Mark a traffic row idle after (milliseconds)"));
        assertTrue(receiver.contains("Applies to traffic rows on the Live page."));
        assertFalse(receiver.contains("does not keep ' +"));
        assertFalse(receiver.contains("show_encryption_details"));
        assertFalse(receiver.contains("show_control_decode_quality"));
        assertFalse(receiver.contains("live_detail_row_limit"));

        assertTrue(personal.contains("userPreferenceController.snapshot()"));
        assertTrue(personal.contains("Customize your appearance and personal listening preferences."));
        assertTrue(personal.contains("createMySettingsWorkspace("));
        assertTrue(personal.contains("openResetUserPreferences"));
        assertFalse(personal.contains("userPreferenceSummaryCards"));
        assertFalse(personal.contains("preferenceCheckbox("));
        assertFalse(personal.contains("updateUserPreferences("));
        assertTrue(personalWorkspace.contains("export function createMySettingsWorkspace"));
        assertTrue(personalWorkspace.contains("preferenceGroups.filter(group => editorGroups.has(group.id)"));
        assertTrue(preferenceRegistry.contains("id: 'appearance', label: 'Appearance'"));
        assertTrue(preferenceRegistry.contains("id: 'names', label: 'Source names'"));
        assertTrue(preferenceRegistry.contains("id: 'status', label: 'Status icon'"));
        assertTrue(personalWorkspace.contains("Page preferences"));
        assertTrue(personalWorkspace.contains("source_name_display"));
        assertTrue(personalWorkspace.contains("disabled_codes"));
        assertFalse(personalWorkspace.contains("preferences.tables"));
        assertFalse(personalWorkspace.contains("selected_scan_list_ids"));
        assertTrue(personalReset.contains("await userPreferenceController.resetPreferences()"));
        assertTrue(personalReset.contains("const ownsProfile = userPreferenceController.profileGuard()"));
        assertTrue(personalReset.contains("if (!ownsProfile())"));
        assertTrue(personalReset.contains("createActionWorkflow({ surface: body, feedback: message, modal })"));
        assertTrue(personalReset.contains("await workflow.run(async () =>"));
        assertTrue(personalReset.contains("pending: 'Resetting personal preferences…'"));
        assertTrue(personalReset.contains("Reset the guest preferences saved in this browser?"));
        assertTrue(personalReset.contains("Reset every personal preference for this account?"));
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
        assertFalse(livePresentation.contains("target_grouping"));
        assertFalse(livePresentation.contains("target_burst_limit"));
        assertFalse(livePresentation.contains("preferences.playback"));
        assertFalse(livePresentation.contains("preferences.page_titles"));
        assertTrue(livePresentation.contains("createFormWorkflow({ form, submit: save, feedback: message, modal })"));
        assertTrue(livePresentation.contains("savePreferenceForm(workflow, modal"));
        assertTrue(livePresentation.contains("identity: snapshot.identity"));
        assertTrue(livePresentation.contains("renderAfterSave: !densityOnly"));
        assertTrue(preferenceSave.contains("await workflow.save(async () =>"));
        assertTrue(preferenceSave.contains("userPreferenceController.snapshot().identity !== identity"));
        assertTrue(preferenceSave.contains("await updateUserPreferences(mutate, false)"));
        assertTrue(preferenceSave.contains("modal.close() && renderAfterSave"));
        assertTrue(preferenceSave.contains("void render()"));

        assertFalse(source.contains("function activateScannerSettings("));
        assertFalse(source.contains("function openScannerSettings("));
        assertTrue(personalWorkspace.contains("selectedSection === 'listening'"));
        assertTrue(personalWorkspace.contains("preferences.page_titles.prepend_playing_call = prepend_playing_call"));
        String scanner = function(source, "function renderScanner(options = {})");
        assertTrue(scanner.contains("typeof options.channelMetadata !== 'function'"));
        assertTrue(scanner.contains("typeof options.mount !== 'function'"));
        assertFalse(scanner.contains("options.readSnapshot"));
        assertFalse(scanner.contains("options.savePreferenceForm"));
        assertFalse(scanner.contains("options.openReadOnlyModal"));
        assertTrue(scanner.contains("if (!guideMode) audioDock.openPlayerMenu(invoker)"));
        assertTrue(scanner.contains("audioDock.prepareGuide({ sampleLiveState: player.viewState(), guidePanel: panels[section] })"));
        assertTrue(scannerControls.contains("(options.openPlayerMenu || (invoker => audioDock.openPlayerMenu(invoker)))(menu)"));
        assertTrue(scannerControls.contains("menu.id = 'scanner-player-menu'"));
        assertTrue(audioDock.contains("['Listening settings', 'playback-controls', 'settings']"));
        assertTrue(playerSettings.contains("player.applyPreferences({ target_grouping: grouping.checked }); player.writePreferences({ grouping: true })"));
        assertTrue(playerSettings.contains("player.applyPreferences({ target_burst_limit: Number(burst.value) }); player.writePreferences({ burst: true })"));
        assertTrue(playerSettings.contains("setTitlePreference(prepend.checked)"));
        assertFalse(playerSettings.contains("preferences.presentation"));

        assertFalse(source.contains("/api/v1/admin/web-display"));
        assertFalse(source.contains("/api/v1/live/settings"));
        assertFalse(source.contains("renderAdminWebDisplaySettings"));
    }

    @Test
    void serverRegistersOnlyTheNewSettingsOwners() throws Exception
    {
        String service = Files.readString(Path.of("src", "main", "java", "io", "github", "dsheirer",
            "stats", "StatsWebServerService.java"));
        assertTrue(service.contains("WebReceiverSettingsHttpController.PATH"));
        assertTrue(service.contains("P25BandplanOverrideHttpController.PATH"));
        assertTrue(service.contains("WebUserPreferencesHttpController.PATH"));
        assertTrue(service.contains("WebCapability.USER_SETTINGS"));
        assertFalse(service.contains("WebDisplaySettings"));
        assertFalse(service.contains("/api/v1/live/settings"));
    }

    @Test
    void keepsGuestSettingsAvailableWhileAccountPreferencesRequireAuthentication() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String routes = Files.readString(Path.of("stats-web", "assets", "core", "routes.js"));
        String access = function(source, "function routeDefinitionAllowed(definition)");
        String controller = Files.readString(Path.of("src", "main", "java", "io", "github", "dsheirer",
            "web", "http", "WebUserPreferencesHttpController.java"));

        assertTrue(routes.contains("id: 'settings', label: 'My Settings', title: 'My Settings', parent: null, " +
            "capability: 'web-access'"));
        assertFalse(routes.contains("access: 'authenticated'"));
        assertFalse(access.contains("definition.access === 'authenticated'"));
        assertTrue(controller.contains("mSecurity.authenticatedAccount(exchange).orElse(null)"));
        assertTrue(controller.contains("if(account == null)"));
        assertTrue(controller.contains("ApiHttpResponse.sendError(exchange, 401, \"authentication_required\""));
        assertTrue(controller.contains("mPreferences.get(account)"));
        assertTrue(controller.contains("mPreferences.update(account, expectedRevision, preferences)"));
    }

    private static String function(String source, String marker)
    {
        return StatsWebSourceTestSupport.settingsFunction(source, marker);
    }
}
