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

class StatsWebFrequencyActionsUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path RADIO_REFERENCE_IMPORT_JAVASCRIPT =
        Path.of("stats-web", "assets", "features", "radioreference-import.js");
    private static final Path INDEX_HTML = Path.of("stats-web", "index.html");

    @Test
    void manageOwnsRadioReferenceLookupSettings() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String settings = block(source, "async function renderAdminRadioReferenceSettings()");
        String html = Files.readString(INDEX_HTML);

        assertTrue(source.contains("radioreference: renderAdminRadioReferenceSettings"));
        assertFalse(source.contains("function renderConfiguration()"));
        assertFalse(source.contains("focused migration"));
        assertFalse(source.contains("function comingSoonPanel"));
        assertTrue(settings.contains("Choose the state used for exact-frequency searches."));
        assertTrue(settings.contains("Log out and clear saved credentials"));
        assertTrue(html.contains("data-view=\"radioreference\" href=\"/?view=radioreference\""));
    }

    @Test
    void shipsTheRadioReferenceImporterAsAnApiBackedWorkspace() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String importer = Files.readString(RADIO_REFERENCE_IMPORT_JAVASCRIPT);

        for(String endpoint: new String[]{"/browse", "/counties", "/systems/details",
            "/systems/sites", "/systems/talkgroups", "/conventional/categories",
            "/conventional/frequencies", "/imports/site/preview", "/imports/conventional/preview",
            "/imports/talkgroups/preview"})
        {
            assertTrue(importer.contains("${API_ROOT}" + endpoint),
                () -> "RadioReference import endpoint is missing from the browser: " + endpoint);
        }

        assertTrue(source.contains("createRadioReferenceImportWorkspace"));
        assertTrue(source.contains("Browse systems and agencies, compare changes, and import"));
        assertTrue(importer.contains("selectedTalkgroups: new Set()"));
        assertTrue(importer.contains("Selections persist across filters and pages"));
        assertTrue(importer.contains("Clear selection"));
        assertTrue(importer.contains("Import selected"));
        assertTrue(importer.contains("Import all system talkgroups"));
        assertTrue(importer.contains("Create one combined channel"));
        assertTrue(importer.contains("Import one channel at a time"));
        assertFalse(importer.contains("For Each Frequency"));
        assertFalse(importer.contains("Create Selected Channels"));
        assertTrue(source.contains("RADIO_REFERENCE_DIRECTORY_TIMEOUT_MILLISECONDS = 15_000"));
    }

    @Test
    void opensCompactSpectrumActionsAndSeparateRadioReferenceResults() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String tuner = block(source, "function tunerSpectrumPanel(snapPresetDocument, panelOptions = {})");
        String actions = block(source, "function openTunerFrequencyActions(selection)");
        String lookup = block(source, "function openTunerRadioReferenceLookup(selectedHz)");
        String results = block(source, "function radioReferenceResultView(");
        String settings = block(source, "async function renderAdminRadioReferenceSettings()");
        String spectrumPage = block(source, "async function renderTunerSpectrum()");
        String pointerUp = block(tuner, "function onPlotPointerUp(event)");
        String pointerCancel = block(tuner, "function onPlotPointerCancel(event)");

        assertTrue(actions.contains("'RadioReference lookup'"));
        assertTrue(actions.contains("'Listen to NBFM'"));
        assertTrue(actions.contains("'Add channel / system'"));
        assertTrue(actions.contains("/api/v1/admin/spectrum-discovery/eligibility?"));
        assertTrue(actions.contains("eligibility.eligible !== true"));
        assertTrue(actions.contains("openSpectrumDiscoveryWizard(selection)"));
        assertTrue(actions.contains("bandwidth_hz: Number(bandwidth.value)"));
        assertTrue(actions.contains("binaryFrameConnection('frequency_audio'"));
        assertTrue(actions.contains("true);"));
        assertTrue(actions.contains("selection?.actionHost?.nodeType === Node.ELEMENT_NODE"));
        assertTrue(actions.contains("const inline = Boolean(actionHost)"));
        assertTrue(actions.contains("const panel = inline ? actionHost"));
        assertTrue(actions.contains("panel.replaceChildren(body)"));
        assertTrue(actions.contains("panel.setAttribute('popover', 'auto')"));
        assertTrue(actions.contains("panel.hidePopover();"));
        assertTrue(actions.contains("openTunerRadioReferenceLookup(selectedHz)"));
        assertTrue(actions.contains("stopListening();"));
        assertFalse(actions.contains("openReadOnlyModal("));
        assertTrue(lookup.contains("openReadOnlyModal('RadioReference lookup'"));
        assertTrue(lookup.contains("requestJson('/api/v1/admin/radioreference'"));
        assertTrue(lookup.contains("/api/v1/admin/radioreference/frequencies?"));
        assertTrue(lookup.contains("configuration?.account?.state !== 'VALID_PREMIUM'"));
        assertTrue(lookup.contains("href('admin', { tab: 'live-activity' })"));
        assertFalse(results.contains("'Freq Out'"));
        assertFalse(results.contains("'Freq In'"));
        assertTrue(results.contains("row.description"));
        assertTrue(results.contains("'System'"));
        assertTrue(results.contains("'Site'"));
        assertTrue(results.contains("'Channel use'"));
        assertTrue(results.contains("'Conventional'"));
        assertTrue(results.contains("'Trunked systems and sites'"));
        assertTrue(results.contains("row.mode_name"));
        assertTrue(results.contains("row.radio_reference_url"));
        assertTrue(results.contains("'Open RadioReference'"));
        assertTrue(results.contains("open.classList.add('ui-button', 'ui-button-secondary')"));
        assertTrue(results.contains("'Load details'"));
        assertTrue(results.contains("loaded.site.radio_reference_url"));
        assertTrue(results.contains("replaceFact('channel-use'"));
        assertTrue(results.contains("loadRadioReferenceDetails(row, frequencyHz"));
        assertTrue(results.contains("'radioreference-result-grid'"));
        assertTrue(source.contains("'radioreference-result-card ui-surface'"));
        assertFalse(results.contains("table(items"));
        assertTrue(source.contains("/api/v1/admin/radioreference/frequencies/details?"));
        assertTrue(source.contains("site_number: String(Number(row.site_number || 0))"));
        assertTrue(source.contains("timeoutMs: 65_000"));
        assertTrue(source.contains("radioReferenceDetailCache.clear()"));
        assertFalse(results.contains("Number(row.output_mhz)"));
        assertTrue(settings.contains("'/api/v1/admin/radioreference/session'"));
        assertTrue(settings.contains("'/api/v1/admin/radioreference/countries'"));
        assertTrue(settings.contains("'/api/v1/admin/radioreference/location'"));
        assertFalse(source.contains("RADIO_REFERENCE_FREQUENCY_QUERY_URL"));
        assertFalse(source.contains("openRadioReferenceFrequencyQuery"));
        assertFalse(source.contains("function radioReferenceDetailContent"));
        assertFalse(source.contains("radioreference-frequency-detail-header"));
        assertTrue(spectrumPage.contains("'spectrum-browse-control-rail ui-surface'"));
        assertTrue(spectrumPage.contains("'Select a signal'"));
        assertTrue(spectrumPage.contains("main.append(spectrum.element, frequencyRail)"));
        assertTrue(spectrumPage.contains("onFrequencySelection: (selection) =>"));
        assertTrue(spectrumPage.contains("actionHost: frequencyRail"));
        assertFalse(spectrumPage.contains("openSpectrumSearchWizard"));
        assertFalse(spectrumPage.contains("findChannels"));
        assertFalse(spectrumPage.contains("Keep tuner here"));
        assertTrue(tuner.contains("function frequencySelectionAtPointer(event)"));
        assertTrue(tuner.contains("typeof panelOptions.onFrequencySelection === 'function'"));
        assertTrue(tuner.contains("if (frequencySelectionHandler) frequencySelectionHandler(selection)"));
        assertTrue(source.contains("Click a frequency to choose an action."));
        assertTrue(tuner.contains("rawFrequencyHz"));
        assertTrue(tuner.contains("frequencyHz: selectedFrequencyHz"));
        assertTrue(tuner.contains("anchorRect: { left: event.clientX"));
        assertTrue(tuner.contains("activeCarrier: carrier"));
        assertTrue(tuner.contains("canvas.addEventListener('click', onPlotClick)"));
        assertTrue(tuner.contains("flag.addEventListener('click'"));
        assertTrue(tuner.contains("const frequencyActions = !basicOperator &&"));
        assertTrue(pointerUp.contains("if (moved) queueViewportUpdate();"));
        assertTrue(pointerUp.contains("else if (frequencyActions) openFrequencyActionsAtPointer(event);"));
        assertFalse(pointerCancel.contains("openFrequencyActionsAtPointer"));
        assertFalse(tuner.contains("updateCursor(ratio).then"));
        assertFalse(tuner.contains("acceptTunerFrame(frame).then"));
    }

    @Test
    void channelSetupOwnsTrunkedSearchWithItsOwnReceiverLease() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String channels = block(source, "async function renderModernChannelCatalog(renderContext)");
        String search = Files.readString(Path.of("stats-web", "assets", "features", "spectrum-search.js"));

        assertTrue(channels.contains("capabilityAllowed(ACCESS_CAPABILITIES.ADMIN_TUNERS)"));
        assertTrue(channels.contains("uiActionButton('Find Trunked Systems'"));
        assertTrue(channels.contains("openSpectrumSearchWizard"));
        assertTrue(channels.contains("prepareReceiver: async (tuner)"));
        assertTrue(channels.contains("'Stop channels for this search?'"));
        assertTrue(search.contains("const browsePath = (id) => `/api/v1/admin/tuners/${encodeURIComponent(id)}/browse`"));
        assertTrue(search.contains("browse_lease_id: lease.lease_id"));
        assertTrue(search.contains("radioreference_state_id: directory.stateId()"));
        assertTrue(search.contains("if (jobReleased) await releaseLease({ bestEffort: true })"));
        assertFalse(channels.contains("searchOwner"));
        assertFalse(channels.contains("searchActive"));
    }

    @Test
    void stylesResponsiveRadioReferenceResultsAndDisabledFutureActions() throws Exception
    {
        String css = StatsWebStylesheetTestSupport.readAll();
        assertTrue(css.contains(".admin-settings-form"));
        assertTrue(css.contains("height: 36px;"));
        assertTrue(css.contains(".read-only-modal.frequency-action-modal"));
        assertTrue(css.contains(".tuner-frequency-popover"));
        assertTrue(css.contains(".read-only-modal.tuner-frequency-lookup-modal"));
        assertTrue(css.contains(".tuner-frequency-action-list"));
        assertTrue(css.contains(".spectrum-browse-control-rail"));
        assertTrue(css.contains(".spectrum-browse-main"));
        assertTrue(css.contains(".ui-button[aria-disabled=\"true\"]"));
        assertTrue(css.contains("color: var(--muted);"));
        assertTrue(css.contains(".radioreference-result-grid"));
        assertTrue(css.contains(".radioreference-result-actions .ui-button"));
        assertTrue(css.contains("minmax(min(100%, 360px), 1fr)"));
        assertTrue(css.contains(".radioreference-result-actions"));
        assertTrue(css.contains(".radioreference-import-workspace"));
        assertTrue(css.contains(".radioreference-browse-form"));
        assertTrue(css.contains(".radioreference-selection-badge"));
        assertTrue(css.contains(".radioreference-site-form"));
    }

    private static String block(String source, String marker)
    {
        int start = source.indexOf(marker);
        assertTrue(start >= 0, marker);
        int opening = source.indexOf('{', start + marker.length());
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

        throw new AssertionError("Unclosed block: " + marker);
    }
}
