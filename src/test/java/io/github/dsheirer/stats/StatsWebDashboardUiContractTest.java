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
        String dashboard = function(Files.readString(APP_JAVASCRIPT), "async function renderDashboard()");
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
        assertTrue(dashboard.contains("renderNestedRadioDirectory(renderContext, snapshot.directory)"));
        assertTrue(dashboard.contains("if (tab === 'activity')"));
        assertTrue(dashboard.contains("await renderDashboardActivity(renderContext)"));
        assertTrue(dashboard.contains("const snapshot = await loadMainDashboard()"));
        assertTrue(dashboard.contains("signalHealthSection(snapshot.quality.current)"));
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
    void rendersAccessibleActivityTotalsWithoutDetailedHistoryRanking() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String actionRows = function(source, "function dashboardActivityActionRows(response)");
        String mix = function(source, "function dashboardActivityMix(response)");
        String activity = function(source, "async function renderDashboardActivity(renderContext)");
        String failure = function(source, "function asyncSectionFailure(error, fallbackMessage, retry)");
        assertTrue(source.contains("['6h', '6h'], ['24h', '24h'], ['7d', '7d']"));
        assertTrue(actionRows.contains("row.action !== 'CONTINUE'"));
        assertTrue(mix.contains("class: 'dashboard-activity-donut'"));
        assertTrue(mix.contains("role: 'button', tabindex: 0"));
        assertTrue(mix.contains("setAttribute('aria-pressed'"));
        assertTrue(mix.contains("event.key !== 'Enter' && event.key !== ' '"));
        assertTrue(mix.contains("selected?.label || 'Events'"));
        assertTrue(mix.contains("updateSelection(row.action)"));
        assertFalse(mix.contains("api("));
        assertTrue(activity.contains("api('/api/v1/activity/actions'"));
        assertTrue(activity.contains("signal: renderContext.signal"));
        assertTrue(activity.contains("renderIsCurrent(renderContext)"));
        assertTrue(activity.contains("error?.name === 'AbortError'"));
        assertTrue(activity.contains("error?.status === 401 || error?.status === 403"));
        assertTrue(activity.contains("await refreshAccessSession(false)"));
        assertTrue(activity.contains("setAttribute('aria-busy'"));
        assertTrue(activity.contains("asyncSectionFailure(error"));
        assertTrue(failure.contains("setAttribute('role', 'alert')"));
        assertTrue(activity.contains("activityMetricGuide()"));
        assertFalse(activity.contains("apiPage("));
        assertFalse(activity.contains("source radios"));
        assertFalse(activity.contains("detailedHistoryAvailable()"));
        assertFalse(activity.contains("pageInterval"));
        assertFalse(source.contains("function dashboardActivityRadio("));
        assertFalse(source.contains("const dashboardActivityRadioColumns = ["));
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
        String live = function(source, "function liveChannelsSection(onSelectionChange)");
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
    void keepsSignalHealthInStableNameOrder() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String sorter = function(source, "function sortSignalChannels(channels)");
        String section = function(source, "function signalHealthSection(currentChannels)");
        assertTrue(sorter.contains("channelLabel(left).localeCompare(channelLabel(right)"));
        assertTrue(sorter.contains("left.configuration_id"));
        assertFalse(sorter.contains("decode_health_pct"));
        assertFalse(section.contains("Highest decode"));
        assertFalse(section.contains("Weakest signal"));
        assertTrue(section.contains("canonicalConfigurationId(channel?.configuration_id)"));
        assertTrue(section.contains("const key = canonicalConfigurationId(channel.configuration_id)"));
        assertFalse(section.contains("channel.configuration_id || channelLabel(channel)"));
        String tile = function(source, "function updateSignalCurrentTile(tile, channel)");
        assertTrue(tile.contains("channel.name || 'Unnamed channel'"));
        assertTrue(tile.contains("channel.site_name || ''"));
        assertTrue(tile.contains("channel.system_name || channel.system || ''"));
        assertTrue(tile.contains("capabilityAllowed(ACCESS_CAPABILITIES.RADIO)"));
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
        assertTrue(dashboardCss.contains("grid-template-columns: minmax(0, 1fr)"));
        assertFalse(dashboardCss.contains("@media (max-width: 1500px)"));
        assertTrue(css.contains(".dashboard-summary-section .summary-band"));
        assertTrue(css.contains(".dashboard-identity-context"));
        assertTrue(css.contains(".dashboard-activity-layout"));
        assertTrue(css.contains(".dashboard-activity-donut"));
        assertTrue(css.contains(".dashboard-activity-segment:focus-visible"));
        assertFalse(css.contains(".dashboard-activity-radio-status"));
        assertFalse(css.contains(".dashboard-activity-radio-result > .ui-section-note"));
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
