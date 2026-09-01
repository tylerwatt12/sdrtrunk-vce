/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Protects the read-only Alias Catalog, its evidence semantics, and the quality CSV controls.  Interactive behavior
 * is also exercised during browser smoke testing; these source contracts prevent route and field drift.
 */
class StatsWebAliasCatalogUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path APP_CSS = Path.of("stats-web", "assets", "app.css");
    private static final Path INDEX_HTML = Path.of("stats-web", "index.html");

    @Test
    void exposesAReadOnlyCatalogWithServerBackedFiltersAndCsv() throws Exception
    {
        String source = source();
        String html = readText(INDEX_HTML);
        String renderer = function(source, "async function renderAliases()");
        String filters = function(source, "function aliasCatalogFilterToolbar(listResponse)");

        assertTrue(html.contains("data-view=\"aliases\" href=\"/?view=aliases\""));
        assertTrue(source.contains("aliases: renderAliases"));
        assertTrue(renderer.contains("api('/api/alias-lists')"));
        assertTrue(renderer.contains("api('/api/aliases', pageParameters(filters))"));
        for(String parameter: new String[]{"list", "family", "type", "matcher"})
        {
            assertTrue(renderer.contains(parameter + ": route.get('" + parameter + "')"),
                () -> "Missing Alias Catalog filter " + parameter);
        }
        assertTrue(filters.contains("input.name = 'q'"));
        assertTrue(filters.contains("form.addEventListener('submit'"));
        assertTrue(filters.contains("control.disabled = true"));
        assertTrue(renderer.contains("['list', 'family', 'type', 'matcher'].forEach"));
        assertTrue(renderer.contains("exportCsvLink('aliases', exportContext)"));
        assertTrue(function(source, "function exportCsvHref(dataset, context = {})")
            .contains("['q', 'sort', 'direction']"));
    }

    @Test
    void keepsConfigurationFixedAndPersistsOnlyOptionalEvidenceColumns() throws Exception
    {
        String source = source();
        String renderer = function(source, "async function renderAliases()");
        String core = function(source, "function aliasCatalogCoreColumns()");
        String optional = function(source, "function aliasCatalogEnrichmentColumns()");
        String reader = function(source, "function readAliasCatalogColumnSelection(definitions)");
        String widths = function(source,
            "function addColumnResizers(element, columns, columnElements, headers, tableType)");

        for(String label: new String[]{"Alias List", "Family", "Matcher", "Identifier", "Alias", "Description",
            "Group", "Behavior"})
        {
            assertTrue(core.contains("label: '" + label + "'"), () -> "Missing fixed column " + label);
        }
        assertTrue(renderer.contains("[...aliasCatalogCoreColumns(), ...optional]"));
        assertTrue(renderer.contains("serverSort: true, sortable: false"));
        assertTrue(renderer.contains("aliasColumnChooser(definitions, selected, onColumnChange)"));
        assertTrue(source.contains("sdrtrunk_alias_catalog_enrichment_columns_v1"));
        assertTrue(reader.contains("parsed.filter((id) => valid.has(id))"));
        assertTrue(reader.contains("ALIAS_CATALOG_DEFAULT_ENRICHMENT_COLUMNS"));
        assertTrue(source.contains("'emergency', 'logout'"));
        assertTrue(renderer.contains("route.set('sort', 'name')"));
        assertTrue(renderer.contains("route.set('direction', 'asc')"));
        assertTrue(widths.contains("{ ...current }"));
        assertTrue(optional.contains("sort = field"));
        assertTrue(optional.contains("sort: 'first_evidence_ms'"));
        assertTrue(optional.contains("sort: 'last_evidence_ms'"));
        assertTrue(optional.contains("'coverage_scope_count', evidence,\n      'Compatible monitored scopes where " +
            "this alias could be resolved.', null"));
        assertTrue(optional.contains("'observed_scope_count', evidence,\n      'Compatible scopes with retained " +
            "activity or relationship evidence.', null"));
        String chooser = function(source, "function aliasColumnChooser(definitions, selected, onChange)");
        assertTrue(chooser.contains("chooser.open = false"));
        assertTrue(chooser.contains("summary.focus()"));

        for(String field: new String[]{"call_count", "recorded_count", "streamed_count",
            "encrypted_evidence_count", "grant_count", "join_count", "emergency_count", "register_count",
            "logout_count", "denial_count", "data_count", "other_signaling_count", "relationship_count",
            "join_relationship_count", "current_affiliation_count", "coverage_scope_count", "observed_scope_count",
            "metrics_state", "first_evidence_ms", "last_evidence_ms"})
        {
            assertTrue(optional.contains(field), () -> "Missing optional evidence field " + field);
        }
    }

    @Test
    void distinguishesUnavailableEvidenceFromACollectedZero() throws Exception
    {
        String source = source();
        String metric = function(source, "function aliasMetricValue(row, field)");
        String behavior = function(source, "function aliasBehavior(row)");
        String optional = function(source, "function aliasCatalogEnrichmentColumns()");
        String detail = function(source, "function aliasDetailContent(alias, breakdown)");

        assertTrue(metric.contains("row[field] === null || row[field] === undefined"));
        assertTrue(metric.contains("return '—'"));
        assertTrue(metric.contains("Number.isFinite(value) ? number(value)"));
        assertTrue(behavior.contains("row.listen_enabled === null || row.listen_enabled === undefined ? Number.NaN"));
        assertTrue(behavior.contains("badge('Listen'"));
        assertTrue(behavior.contains("badge('Listen off'"));
        assertTrue(optional.contains("'Enc Obs.'"));
        assertTrue(optional.contains("Encrypted observations"));
        assertTrue(optional.contains("count('logout', 'Logout', 'logout_count'"));
        assertTrue(detail.contains("Logout means unit deregistration, not leaving a talkgroup"));
        assertTrue(detail.contains("0 means coverage was collected and the count was zero"));
        assertTrue(detail.contains("yesNoKnown(alias.ranged)"));
        assertFalse(optional.contains("'Leave'"));
    }

    @Test
    void providesAnAccessibleRouteAwareDetailDialogAndCompleteScopeBreakdown() throws Exception
    {
        String source = source();
        String open = function(source, "function openReadOnlyModal(title, body, options = {})");
        String close = function(source, "function closeReadOnlyModal(updateRoute = false)");
        String detail = function(source, "async function renderAliasDetailModal(id)");
        String breakdown = function(source, "function aliasScopeBreakdownColumns()");

        assertTrue(open.contains("setAttribute('role', 'dialog')"));
        assertTrue(open.contains("setAttribute('aria-modal', 'true')"));
        assertTrue(open.contains("event.key === 'Escape'"));
        assertTrue(open.contains("if (event.target === backdrop) dismiss()"));
        assertTrue(close.contains("route.delete('alias')"));
        assertTrue(close.contains("returnFocus.focus()"));
        assertTrue(function(source, "function currentHref(overrides = {})")
            .contains("parameters.delete('alias')"));
        assertTrue(detail.contains("api('/api/alias', { id })"));
        assertTrue(detail.contains("activeReadOnlyModal !== modal.state"));
        assertTrue(detail.contains("Number(route.get('alias')) !== id"));
        assertTrue(function(source, "async function render()").contains("closeReadOnlyModal(false)"));
        for(String field: new String[]{"call_count", "recorded_count", "streamed_count",
            "encrypted_evidence_count", "grant_count", "join_count", "emergency_count", "register_count",
            "logout_count", "denial_count", "data_count", "other_signaling_count", "relationship_count",
            "join_relationship_count", "current_affiliation_count", "first_evidence_ms", "last_evidence_ms"})
        {
            assertTrue(breakdown.contains(field), () -> "Missing scope breakdown field " + field);
        }
    }

    @Test
    void linksContextAliasListsAndExportsOnlyApplicableQualityRanges() throws Exception
    {
        String source = source();
        String siteInfo = function(source, "async function renderSiteInfo(site)");
        String conventional = function(source, "async function renderConventionalDetail()");
        String dashboardQuality = function(source, "async function signalHealthSection()");
        String siteQuality = function(source, "async function siteSignalHistorySection(site)");

        assertTrue(siteInfo.contains("aliasListLink(site.alias_list_name, site.alias_list_id)"));
        assertTrue(conventional.contains("aliasListLink(context.alias_list_name, context.alias_list_id)"));
        assertTrue(dashboardQuality.contains("exportCsvLink('signal-health')"));
        assertFalse(dashboardQuality.contains("exportCsvLink('signal-health',"));
        assertTrue(siteQuality.contains("exportCsvLink('site-quality', { guid: site.guid, range: selectedRange })"));
        assertTrue(siteQuality.contains("exportLink.href = exportCsvHref('site-quality'"));
    }

    @Test
    void keepsTheCatalogAndDialogUsableOnSmallScreensAndDarkThemes() throws Exception
    {
        String css = readText(APP_CSS);
        assertTrue(css.contains(".column-chooser-groups"));
        assertTrue(css.contains(".read-only-modal"));
        assertTrue(css.contains("@media (max-width: 900px)"));
        assertTrue(css.contains(".alias-detail,\n  .column-chooser-groups {\n    grid-template-columns: 1fr;"));
        assertTrue(css.contains("@media (max-width: 560px)"));
        assertTrue(css.contains("width: 100vw;"));
        assertTrue(css.contains("height: 100dvh;"));
        assertTrue(css.contains("color: var(--ink);"));
        assertTrue(css.contains("background: var(--bg);"));
        assertTrue(css.contains("background: var(--surface);"));
    }

    private static String source() throws Exception
    {
        assertTrue(Files.isRegularFile(APP_JAVASCRIPT), () -> "Missing " + APP_JAVASCRIPT.toAbsolutePath());
        return readText(APP_JAVASCRIPT);
    }

    private static String readText(Path path) throws Exception
    {
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String function(String source, String signature)
    {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, () -> "Missing " + signature);
        int openingBrace = source.indexOf('{', start + signature.length());
        int depth = 0;

        for(int index = openingBrace; index < source.length(); index++)
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

        throw new AssertionError("Unterminated " + signature);
    }
}
