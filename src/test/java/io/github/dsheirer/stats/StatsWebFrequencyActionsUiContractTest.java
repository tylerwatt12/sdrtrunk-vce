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
    private static final Path APP_CSS = Path.of("stats-web", "assets", "app.css");
    private static final Path INDEX_HTML = Path.of("stats-web", "index.html");

    @Test
    void administrationOwnsLookupSettingsWhileConfigurationKeepsThePlaceholder() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String configuration = block(source, "async function renderConfiguration()");
        String administration = block(source, "async function renderAdmin()");
        String siteSettings = block(source, "async function renderSiteSettings()");
        String settings = block(source, "async function renderAdminRadioReferenceSettings()");
        String html = Files.readString(INDEX_HTML);

        assertTrue(configuration.contains("id: 'radioreference', label: 'RadioReference'"));
        assertTrue(configuration.contains("comingSoonPanel('RadioReference'"));
        assertFalse(configuration.contains("focused migration"));
        assertTrue(configuration.contains("comingSoonPanel('RadioReference')"));
        assertFalse(configuration.contains("renderAdminRadioReferenceSettings"));
        assertTrue(administration.contains("id: 'site-settings', label: 'Site Settings'"));
        assertTrue(administration.contains("await renderSiteSettings()"));
        assertTrue(siteSettings.contains("const renderContext = captureRenderContext()"));
        assertTrue(siteSettings.contains("await renderAdminSiteBehaviorSettings()"));
        assertTrue(siteSettings.contains("if (!renderIsCurrent(renderContext)) return"));
        assertTrue(siteSettings.contains("await renderAdminRadioReferenceSettings()"));
        assertTrue(settings.contains("Choose the state used for exact-frequency searches."));
        assertTrue(html.contains("data-nav-tab=\"radioreference\" " +
            "href=\"/?view=configuration&amp;tab=radioreference\""));
    }

    @Test
    void doesNotShipTheDeferredRadioReferenceImporter() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);

        for(String endpoint: new String[]{"/browse?", "/counties?", "/systems/details?",
            "/systems/sites?", "/systems/site-preview?", "/systems/talkgroups?",
            "/systems/channels", "/systems/talkgroups/import", "/conventional/categories?",
            "/conventional/frequencies?", "/conventional/channels"})
        {
            assertFalse(source.contains("/api/v1/admin/radioreference" + endpoint),
                () -> "Deferred RadioReference import endpoint remains in the browser: " + endpoint);
        }

        for(String helper: new String[]{"renderRadioReferenceImportWorkspace",
            "openRadioReferenceTalkgroupImportConfirmation", "radioReferenceAliasSelect",
            "radioReferenceTalkgroupImportCounts", "radioReferenceItems"})
        {
            assertFalse(source.contains(helper), () -> "Deferred RadioReference import helper remains: " + helper);
        }

        assertFalse(source.contains("Import from RadioReference"));
        assertFalse(source.contains("RadioReference is not treated as authoritative"));
        assertTrue(source.contains("RADIO_REFERENCE_DIRECTORY_TIMEOUT_MILLISECONDS = 15_000"));
        assertFalse(source.contains("RADIO_REFERENCE_DETAIL_TIMEOUT_MILLISECONDS"));
        assertFalse(source.contains("RADIO_REFERENCE_MUTATION_TIMEOUT_MILLISECONDS"));
    }

    @Test
    void searchesRadioReferenceServerSideFromOneFutureProofFrequencyActionModal() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String tuner = block(source, "function tunerSpectrumPanel()");
        String actions = block(source, "function openTunerFrequencyActions(selection)");
        String results = block(source, "function radioReferenceResultView(");
        String settings = block(source, "async function renderAdminRadioReferenceSettings()");
        String pointerUp = block(tuner, "function onPlotPointerUp(event)");
        String pointerCancel = block(tuner, "function onPlotPointerCancel(event)");

        assertTrue(actions.contains("'RadioReference Lookup'"));
        assertTrue(actions.contains("'Listen'"));
        assertTrue(actions.contains("'Add System'"));
        assertTrue(actions.contains("true);"));
        assertTrue(actions.contains("openReadOnlyModal('Frequency actions'"));
        assertTrue(actions.contains("requestJson('/api/v1/admin/radioreference'"));
        assertTrue(actions.contains("/api/v1/admin/radioreference/frequencies?"));
        assertTrue(actions.contains("configuration?.account?.state !== 'VALID_PREMIUM'"));
        assertTrue(actions.contains("href('admin', { tab: 'live-activity' })"));
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
        assertTrue(results.contains("'Load details'"));
        assertTrue(results.contains("loaded.site.radio_reference_url"));
        assertTrue(results.contains("replaceFact('channel-use'"));
        assertTrue(results.contains("loadRadioReferenceDetails(row, frequencyHz"));
        assertTrue(results.contains("'radioreference-result-grid'"));
        assertTrue(results.contains("'radioreference-result-card'"));
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
        assertTrue(tuner.contains("function frequencySelectionAtPointer(event)"));
        assertTrue(source.contains("Click a frequency to choose an action."));
        assertTrue(tuner.contains("rawFrequencyHz"));
        assertTrue(tuner.contains("frequencyHz: snap?.frequencyHz ?? rawFrequencyHz"));
        assertTrue(tuner.contains("activeCarrier: carrier"));
        assertTrue(tuner.contains("canvas.addEventListener('click', onPlotClick)"));
        assertTrue(tuner.contains("flag.addEventListener('click'"));
        assertTrue(pointerUp.contains("if (moved) queueViewportUpdate();"));
        assertTrue(pointerUp.contains("else openFrequencyActionsAtPointer(event);"));
        assertFalse(pointerCancel.contains("openFrequencyActionsAtPointer"));
        assertFalse(tuner.contains("updateCursor(ratio).then"));
        assertFalse(tuner.contains("acceptTunerFrame(frame).then"));
    }

    @Test
    void stylesResponsiveRadioReferenceResultsAndDisabledFutureActions() throws Exception
    {
        String css = Files.readString(APP_CSS);
        assertTrue(css.contains(".admin-settings-form"));
        assertTrue(css.contains(".admin-settings-form-stack"));
        assertTrue(css.contains("height: 36px;"));
        assertTrue(css.contains(".read-only-modal.frequency-action-modal"));
        assertTrue(css.contains(".tuner-frequency-action-list"));
        assertTrue(css.contains(".tuner-frequency-action.disabled-action"));
        assertTrue(css.contains(":root[data-theme=\"dark\"] .tuner-frequency-action.disabled-action"));
        assertTrue(css.contains("color: var(--muted);"));
        assertTrue(css.contains(".radioreference-result-grid"));
        assertTrue(css.contains("minmax(min(100%, 360px), 1fr)"));
        assertTrue(css.contains(".radioreference-result-actions"));
        assertFalse(css.contains(".radioreference-import-workspace"));
        assertFalse(css.contains(".radioreference-browse-form"));
        assertFalse(css.contains(".radioreference-selection-row"));
        assertFalse(css.contains(".radioreference-site-import-form"));
    }

    private static String block(String source, String marker)
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

        throw new AssertionError("Unclosed block: " + marker);
    }
}
