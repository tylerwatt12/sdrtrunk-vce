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
 * Protects the protocol-neutral dashboard contract. Browser smoke testing exercises behavior; these source checks
 * prevent call outcomes, receiver health, raw decoder names, and channel observations from being mixed together again.
 */
class StatsWebDashboardUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");

    @Test
    void separatesCallActivityFromReceiverHealth() throws Exception
    {
        String dashboard = function(Files.readString(APP_JAVASCRIPT), "async function renderDashboard(options = {})");
        assertTrue(dashboard.contains("route.get('tab') || 'health'"));
        assertTrue(dashboard.contains("['health', 'calls', 'activity', 'inventory'].includes(requestedTab)"));
        assertTrue(dashboard.contains("{ id: 'calls', label: 'Call Statistics'"));
        assertTrue(dashboard.contains("{ id: 'health', label: 'Overview'"));
        assertTrue(dashboard.contains("{ id: 'activity', label: 'Radio Activity'"));
        assertTrue(dashboard.indexOf("{ id: 'health', label: 'Overview'") <
            dashboard.indexOf("{ id: 'calls', label: 'Call Statistics'"));
        assertTrue(dashboard.indexOf("{ id: 'calls', label: 'Call Statistics'") <
            dashboard.indexOf("{ id: 'activity', label: 'Radio Activity'"));
        assertTrue(dashboard.contains("if (tab === 'health')"));
        assertTrue(dashboard.contains("renderNestedRadioDirectory(renderContext, visibleDirectory, snapshot.quality.current, options)"));
        assertTrue(dashboard.contains("if (tab === 'activity')"));
        assertTrue(dashboard.contains("await renderDashboardActivity(renderContext, options)"));
        assertTrue(dashboard.contains("const snapshot = await loadMainDashboard(requestApi)"));
        assertFalse(dashboard.contains("signalHealthSection("));
        assertFalse(dashboard.contains("'Monitored Coverage'"));
        assertFalse(dashboard.contains("'Recent Channels'"));
        assertFalse(dashboard.contains("dashboard.recent_channels"));
        assertTrue(dashboard.contains("'Calls across all systems and channels · 24h'"));
        assertTrue(dashboard.contains("'Call history · 24h'"));
        assertTrue(dashboard.contains("'Calls by Conventional Channel · Last 24h'"));
        assertTrue(dashboard.contains("dashboard.source_activity_24h"));
        assertTrue(dashboard.contains("dashboard.top_destinations"));
        assertTrue(dashboard.contains("dashboard.top_sources"));
        assertTrue(dashboard.contains("'Top Destinations · Last 24h'"));
        assertTrue(dashboard.contains("'Top Sources · Last 24h'"));
        assertFalse(dashboard.contains("'Busiest Call Sources"));
        assertFalse(dashboard.contains("recentTrunkedSites"));
        assertFalse(dashboard.contains("topTalkgroups"));
        assertFalse(dashboard.contains("topRadios"));
        assertFalse(dashboard.contains("P25 Trunked"));
        assertFalse(dashboard.contains("dashboard.counts"));
    }

    @Test
    void rendersAccessibleActivityTotalsAndSourceRadioDrilldown() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String actionRows = function(source, "function dashboardActivityActionRows(response, catalog = activityMessageTypes)");
        String mix = function(source, "function dashboardActivityMix(response, selectedAction, onSelect, catalog = activityMessageTypes)");
        String activity = function(source, "async function renderDashboardActivity(renderContext, options = {})");
        String radio = function(source, "function dashboardActivityRadio(row)");
        String pager = function(source, "function dashboardActivityRadioPager(page, onOffset)");
        String note = function(source, "function dashboardActivityRadioNote(page)");
        String failure = function(source, "function asyncSectionFailure(error, fallbackMessage, retry)");
        assertTrue(source.contains("['6h', '6h'], ['24h', '24h'], ['7d', '7d']"));
        assertTrue(actionRows.contains("row.action !== 'CONTINUE'"));
        assertTrue(mix.contains("class: 'dashboard-activity-donut'"));
        assertTrue(mix.contains("role: 'button', tabindex: 0"));
        assertTrue(mix.contains("setAttribute('aria-pressed'"));
        assertTrue(mix.contains("event.key !== 'Enter' && event.key !== ' '"));
        assertTrue(mix.contains("selected?.label || 'Events'"));
        assertTrue(mix.contains("updateSelection(row.action)"));
        assertTrue(mix.contains("if (notify && selected) onSelect(selected)"));
        assertFalse(mix.contains("api("));
        assertTrue(activity.contains("requestApi('/api/v1/activity/actions'"));
        assertTrue(activity.contains("signal: request.controller.signal"));
        assertTrue(activity.contains("renderContext.signal.addEventListener('abort'"));
        assertTrue(activity.contains("previous?.controller.abort()"));
        assertTrue(activity.contains("renderIsCurrent(renderContext)"));
        assertTrue(activity.contains("error?.name === 'AbortError'"));
        assertTrue(activity.contains("error?.status === 401 || error?.status === 403"));
        assertTrue(activity.contains("await refreshAccessSession(false)"));
        assertTrue(activity.contains("setAttribute('aria-busy'"));
        assertTrue(activity.contains("asyncSectionFailure(error"));
        assertTrue(failure.contains("setAttribute('role', 'alert')"));
        assertTrue(activity.contains("activityMetricGuide(false, catalog)"));
        assertTrue(activity.contains("requestPage('/api/v1/activity/radios'"));
        assertTrue(activity.contains("range: selectedRange, action, limit: 100, offset: selectedOffset"));
        assertTrue(activity.contains("'Source radios'"));
        assertTrue(activity.contains("detailedHistoryAvailable(options.historyState)"));
        assertTrue(activity.contains("capabilityAllowed(ACCESS_CAPABILITIES.RADIO)"));
        assertTrue(activity.contains("sequence !== radioSequence || action !== selectedAction"));
        assertTrue(activity.contains("cleanupTableLayoutMenu(radioTableController)"));
        assertTrue(activity.contains("node('p', 'ui-muted dashboard-activity-radio-status')"));
        assertTrue(radio.contains("radioIdentityValue(row, { reference: row.radio_entity_ref"));
        assertTrue(radio.contains("row.alias_description"));
        assertTrue(pager.contains("browsingWorkflows.createBrowsingPager"));
        assertTrue(pager.contains("page.has_more"));
        assertTrue(pager.contains("onOffset(page.next_offset)"));
        assertTrue(note.contains("page.action_observation_count"));
        assertTrue(note.contains("page.retained_observation_count"));
        assertTrue(note.contains("page.identified_observation_count"));
        assertTrue(note.contains("page.unknown_source_observation_count"));
        assertFalse(activity.contains("pageInterval"));
        assertTrue(source.contains("const dashboardActivityRadioColumns = ["));
        assertTrue(declaration(source, "const dashboardActivityRadioColumns = [")
            .contains("render: dashboardIdentitySystem"));
    }

    @Test
    void keepsCallTablesCompactAndProtocolNeutral() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String calls = declaration(source, "const dashboardCallSourceColumns = [");
        String identities = function(source, "function dashboardIdentityColumns(identityLabel)");
        String context = function(source, "function dashboardChannelContext(row)");
        assertFalse(source.contains("const dashboardHealthColumns = ["));
        assertTrue(context.contains("`RFSS ${hex(row.rfss, 2)}`"));
        assertTrue(context.contains("`RAN ${identifierNumber(row.ran)}`"));
        assertTrue(context.contains("`NAC ${hex(row.nac, 3)}`"));
        assertTrue(context.contains("values.push(radioSystemDisplayName(row))"));
        assertTrue(context.contains("protocolFamily(row) === 'NXDN' && row.ran != null"));

        assertTrue(calls.contains("label: 'Conventional Channel'"));
        assertTrue(calls.contains("label: 'Mode'"));
        assertTrue(calls.contains("label: 'Calls', fullLabel: 'Calls'"));
        assertTrue(calls.contains("label: 'Recorded'"));
        assertTrue(calls.contains("label: 'Sent to Streamer'"));
        assertFalse(calls.contains("label: 'Type'"));
        assertFalse(calls.contains("label: 'Enc'"));
        assertFalse(calls.contains("Latest Hour"));
        assertTrue(identities.contains("label: identityLabel"));
        assertTrue(identities.contains("label: 'System / Channel'"));
        assertTrue(identities.contains("label: 'Calls', fullLabel: 'Calls'"));
        assertFalse(identities.contains("...dashboardCallSourceColumns"));
        String identity = function(source, "function dashboardIdentity(row)");
        assertTrue(identity.contains("tableIdentityValue(row, identityKind(row.identity_kind) === 'radio' ? 'radio' : 'group'"));
        assertTrue(identity.contains("dashboardIdentityOptions(row)"));
        assertTrue(identities.contains("sortValue: dashboardIdentitySortValue"));
        assertFalse(source.contains("function callSourceActivityChart(activity)"));
    }

    @Test
    void humanizesLiveDecoderNames() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String decoder = function(source, "function decoderLabel(value, compact = false)");
        String live = function(source, "function liveChannelsSection(onSelectionChange, options = {})");
        assertTrue(decoder.contains("P25_PHASE1: ['P25 P1', 'P25 Phase 1']"));
        assertTrue(decoder.contains("P25_PHASE2: ['P25 P2', 'P25 Phase 2']"));
        assertTrue(decoder.contains("P25_CONVENTIONAL: ['P25 Conv', 'P25 Conventional']"));
        assertTrue(live.contains("decoderLabel(row.decoder, true)"));
        assertTrue(live.contains("title: (row) => decoderLabel(row.decoder)"));
        assertFalse(live.contains("cells[10]"));
        String identity = function(source, "function dashboardIdentityOptions(row)");
        assertTrue(identity.contains("talkgroup: 'TG'"));
        assertTrue(identity.contains("patch_group: 'Patch'"));
    }

    @Test
    void onlyBuildsLinksFromCanonicalEntityReferences() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String receiverLink = function(source, "function callSourceLink(row)");
        String identityLink = function(source, "function dashboardIdentityLink(row, label = dashboardIdentityId(row))");
        assertFalse(receiverLink.contains("detail_available"));
        assertTrue(receiverLink.contains("dashboardChannelKind(row) === 'TRUNKED'"));
        assertTrue(receiverLink.contains("channelNameSummary(row"));
        assertTrue(receiverLink.contains("entityReferenceAllowed(row.entity_ref)"));
        assertTrue(receiverLink.contains("if (target) return anchor(label, target)"));
        assertFalse(identityLink.contains("identity_detail_available"));
        assertFalse(identityLink.contains("identity_detail_view"));
        assertTrue(identityLink.contains("row.entity_ref"));
        assertTrue(identityLink.contains("row.entity_tab"));
        assertTrue(identityLink.contains("return target ? anchor(label, target) : label"));
    }

    @Test
    void summarizesTrunkedNameAndSiteWithoutChangingConventionalLabels() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String parts = function(source, "function channelDisplayParts(row)");
        String summary = function(source, "function identitySummaryValue(primary, secondary, target = '')");
        String sourceLabel = function(source, "function callSourceLabel(row)");
        assertTrue(parts.contains("nameValue(row)"));
        assertTrue(parts.contains("siteNameValue(row)"));
        assertTrue(parts.contains("!sameSiteText(site, primary)"));
        assertTrue(summary.contains("identity-summary-primary"));
        assertTrue(summary.contains("identity-summary-context"));
        assertTrue(sourceLabel.indexOf("dashboardChannelKind(row) === 'TRUNKED'") <
            sourceLabel.indexOf("row.name"));
        assertTrue(sourceLabel.contains("if (row.name) return row.name"));
        assertFalse(sourceLabel.contains("row.channel_name"));
    }

    @Test
    void describesMetricAvailabilityWithoutImplyingReceiverUptime() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String coverage = function(source, "function dashboardCoverage(activity)");
        assertTrue(coverage.contains("'Metric availability'"));
        assertTrue(coverage.contains("'Full 24h'"));
        assertTrue(coverage.contains("'Partial history'"));
        assertTrue(coverage.contains("'Not collected'"));
        assertFalse(coverage.contains("Collection coverage"));
        String metricLabel = function(source, "function dashboardMetricLabel(activity, field, label)");
        assertFalse(metricLabel.contains("Partial coverage"));
        assertFalse(metricLabel.contains("Partial history"));
    }

    @Test
    void keepsGroupedQualityInStableNameOrder() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String sorter = function(source, "function radioDirectoryCompare(left, right)");
        String directory = function(source,
            "function renderNestedRadioDirectory(renderContext, directory, currentQuality = [], options = {})");
        assertTrue(sorter.contains("left.name || left.site || left.site_name"));
        assertTrue(sorter.contains("left.configuration_id"));
        assertFalse(sorter.contains("decode_health_pct"));
        assertTrue(directory.contains("canonicalConfigurationId(row.configuration_id)"));
        assertTrue(directory.contains("radioDirectoryQualityOnly(currentQuality)"));
        assertTrue(directory.contains("radioDirectoryReconcile(directoryHost, ...sections)"));
        assertFalse(source.contains("function signalHealthSection("));
        assertFalse(source.contains("function updateSignalCurrentTile("));
        String row = function(source,
            "function radioDirectorySiteRow(row, conventional = false, quality = null)");
        assertTrue(row.contains("row.name || 'Unnamed channel'"));
        assertTrue(row.contains("row.site || row.site_name"));
        assertTrue(row.contains("capabilityAllowed(ACCESS_CAPABILITIES.RADIO)"));
        assertTrue(row.contains("running && row.display_frequency_source === 'active-control'"));
        assertFalse(row.contains("quality_frequency_hz"));
        assertTrue(row.contains("SIGNAL_OFFLINE_MILLISECONDS"));
        assertTrue(row.contains("fresh && !remote"));
        assertTrue(row.contains("average_signal_dbfs"));
        assertTrue(row.contains("node('meter', 'ui-quality-meter')"));
        String context = function(source, "function dashboardChannelContext(row)");
        assertTrue(context.contains("dashboardChannelKind(row)"));
        assertTrue(context.contains("`RFSS ${hex(row.rfss, 2)}`"));
        assertTrue(context.contains("row.site_id"));
        assertTrue(context.contains("`Site ${isP25(row) ? hex(row.site_id, 2) : identifierNumber(row.site_id)}`"));
        assertFalse(context.contains("row.site_system_id"));
        assertTrue(context.contains("values.push(radioSystemDisplayName(row))"));
        assertTrue(context.contains("`NAC ${hex(row.nac, 3)}`"));
    }

    @Test
    void stacksDashboardIdentityTablesAtEveryWidth() throws Exception
    {
        String css = StatsWebStylesheetTestSupport.readAll();
        assertTrue(css.contains(".dashboard-identity-split"));
        String dashboardCss = Files.readString(Path.of("stats-web", "assets", "styles", "features", "dashboard.css"));
        assertTrue(function(dashboardCss, ".dashboard-identity-split")
            .contains("grid-template-columns: minmax(0, 1fr)"));
        assertTrue(css.contains(".dashboard-summary-section .summary-band"));
        assertTrue(css.contains(".dashboard-identity-context"));
        assertTrue(css.contains(".dashboard-activity-layout"));
        assertTrue(css.contains(".dashboard-activity-donut"));
        assertTrue(css.contains(".dashboard-activity-segment:focus-visible"));
        String radioStatus = function(css, ".dashboard-activity-radio-status");
        assertTrue(radioStatus.contains("padding: var(--space-2) var(--space-3)"));
        assertFalse(radioStatus.contains("color:"));
        assertFalse(radioStatus.contains("border-"));
        assertFalse(css.contains(".dashboard-activity-radio-result > .ui-section-note"));
        assertTrue(function(css, "\n.ui-section-note").contains("border-top: 1px solid var(--line)"));
        assertTrue(css.contains("--chart-grant: #2d6f9f"));
        assertTrue(css.contains("--chart-grant: #78b7e5"));
        assertFalse(css.contains(".dashboard-activity-breakdown-toolbar"));
        assertFalse(css.contains(".dashboard-activity-summary .summary-band"));
        assertFalse(css.contains(".site-activity-pie"));
    }

    private static String declaration(String source, String signature)
    {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, () -> "Missing " + signature);
        int end = source.indexOf("\n];", start);
        assertTrue(end >= 0, () -> "Unterminated " + signature);
        return source.substring(start, end + 3);
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
