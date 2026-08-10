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
 * prevent call outcomes, receiver health, raw decoder names, and site observations from being mixed together again.
 */
class StatsWebDashboardUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path APP_CSS = Path.of("stats-web", "assets", "app.css");

    @Test
    void separatesCallActivityFromReceiverHealth() throws Exception
    {
        String dashboard = function(Files.readString(APP_JAVASCRIPT), "async function renderDashboard()");
        assertTrue(dashboard.contains("route.get('tab') || 'health'"));
        assertTrue(dashboard.contains("requestedTab === 'calls' ? 'calls' : 'health'"));
        assertTrue(dashboard.contains("{ id: 'calls', label: 'Calls'"));
        assertTrue(dashboard.contains("{ id: 'health', label: 'Health'"));
        assertTrue(dashboard.indexOf("{ id: 'health', label: 'Health'") <
            dashboard.indexOf("{ id: 'calls', label: 'Calls'"));
        assertTrue(dashboard.contains("if (tab === 'health')"));
        assertTrue(dashboard.indexOf("if (tab === 'health')") <
            dashboard.indexOf("await signalHealthSection()"));
        assertTrue(dashboard.contains("'Monitored Coverage'"));
        assertTrue(dashboard.contains("'Recent Sites / Channels'"));
        assertTrue(dashboard.contains("dashboard.recent_receivers"));
        assertTrue(dashboard.contains("'Call Totals · Last 24 Hours'"));
        assertTrue(dashboard.contains("'Call Activity · Last 24 Hours'"));
        assertTrue(dashboard.contains("'Calls by Site / Channel · Last 24 Hours'"));
        assertTrue(dashboard.contains("dashboard.source_activity_24h"));
        assertTrue(dashboard.contains("dashboard.top_destinations"));
        assertTrue(dashboard.contains("dashboard.top_sources"));
        assertTrue(dashboard.contains("'Top Destinations · Last 24 Hours'"));
        assertTrue(dashboard.contains("'Top Sources · Last 24 Hours'"));
        assertFalse(dashboard.contains("'Busiest Call Sources"));
        assertFalse(dashboard.contains("recentTrunkedSites"));
        assertFalse(dashboard.contains("topTalkgroups"));
        assertFalse(dashboard.contains("topRadios"));
        assertFalse(dashboard.contains("P25 Trunked"));
        assertTrue(dashboard.contains("counts.trunked_systems"));
        assertTrue(dashboard.contains("counts.trunked_sites"));
        assertTrue(dashboard.contains("counts.conventional_channels"));
        assertFalse(dashboard.contains("counts.talkgroups"));
        assertFalse(dashboard.contains("counts.radios"));
        assertFalse(dashboard.contains("counts.frequencies"));
    }

    @Test
    void keepsDashboardTablesCompactAndProtocolNeutral() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String health = declaration(source, "const dashboardHealthColumns = [");
        String calls = declaration(source, "const dashboardCallSourceColumns = [");
        String identities = function(source, "function dashboardIdentityColumns(identityLabel)");
        assertTrue(health.contains("label: 'Site / Channel'"));
        assertTrue(health.contains("label: 'System'"));
        assertTrue(health.contains("label: 'Mode'"));
        assertTrue(health.contains("label: 'RFSS'"));
        assertTrue(health.contains("label: 'Site ID'"));
        assertTrue(health.contains("label: 'NAC'"));
        assertTrue(health.contains("label: 'MHz'"));
        assertTrue(health.contains("label: 'Seen'"));
        assertFalse(health.contains("label: 'Decoder'"));
        assertFalse(health.contains("label: 'Protocol'"));
        assertFalse(health.contains("label: 'Topology'"));

        assertTrue(calls.contains("label: 'Site / Channel'"));
        assertTrue(calls.contains("label: 'Mode'"));
        assertTrue(calls.contains("label: 'Calls'"));
        assertTrue(calls.contains("label: 'Recorded'"));
        assertTrue(calls.contains("label: 'Sent'"));
        assertFalse(calls.contains("label: 'Type'"));
        assertFalse(calls.contains("label: 'Enc'"));
        assertFalse(calls.contains("Latest Hour"));
        assertTrue(identities.contains("label: identityLabel"));
        assertTrue(identities.contains("...dashboardCallSourceColumns"));
        String identity = function(source, "function dashboardIdentity(row)");
        assertTrue(identity.contains("dashboard-identity-context"));
        assertTrue(identity.contains("last_talker_alias"));
        assertTrue(identity.contains("`OTA ${talkerAlias}`"));
        assertTrue(identities.contains("row.alias_name || row.last_talker_alias"));
        assertFalse(source.contains("function callSourceActivityChart(activity)"));
    }

    @Test
    void abbreviatesModeAndHumanizesLiveDecoderNames() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String modeLabel = function(source, "function dashboardModeLabel(row)");
        String mode = function(source, "function dashboardMode(row)");
        String decoder = function(source, "function decoderLabel(value, compact = false)");
        String live = function(source, "function liveSystemsSection(onSelectionChange)");
        assertTrue(modeLabel.contains("`${family}-T`"));
        assertTrue(modeLabel.contains("`${family}-C`"));
        assertTrue(modeLabel.contains("!['P25', 'DMR', 'NXDN'].includes(family)"));
        assertTrue(mode.contains("value.title = `${family} · ${topology}`"));
        assertTrue(mode.contains("value.setAttribute('aria-label'"));
        assertTrue(decoder.contains("P25_PHASE1: ['P25 P1', 'P25 Phase 1']"));
        assertTrue(decoder.contains("P25_PHASE2: ['P25 P2', 'P25 Phase 2']"));
        assertTrue(decoder.contains("P25_CONVENTIONAL: ['P25 Conv', 'P25 Conventional']"));
        assertTrue(live.contains("decoderLabel(row.decoder, true)"));
        assertTrue(live.contains("cells[10].title = decoderLabel(row.decoder)"));
        assertFalse(live.contains("cellText(cells[10], row.decoder)"));
        String identity = function(source, "function dashboardIdentity(row)");
        assertTrue(identity.contains("talkgroup: 'TG'"));
        assertTrue(identity.contains("patch_group: 'Patch'"));
    }

    @Test
    void onlyBuildsLinksWhenTheApiReportsAConcreteDetailTarget() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String receiverLink = function(source, "function callSourceLink(row)");
        String identityLink = function(source, "function dashboardIdentityLink(row, label = dashboardIdentityId(row))");
        assertTrue(receiverLink.contains("Number(row.detail_available)"));
        assertFalse(receiverLink.contains("receiver_detail_available"));
        assertTrue(receiverLink.contains("dashboardChannelKind(row) === 'TRUNKED'"));
        assertTrue(receiverLink.contains("siteNameSummary(row"));
        assertTrue(receiverLink.contains("return label"));
        assertTrue(identityLink.contains("identity_detail_available"));
        assertTrue(identityLink.contains("identity_detail_view"));
        assertTrue(identityLink.contains("return label"));
    }

    @Test
    void summarizesTrunkedNameAndSiteWithoutChangingConventionalLabels() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String parts = function(source, "function siteDisplayParts(row)");
        String summary = function(source, "function siteNameSummaryValue(primary, secondary, target = '')");
        String sourceLabel = function(source, "function callSourceLabel(row)");
        assertTrue(parts.contains("configuredNameValue(row)"));
        assertTrue(parts.contains("configuredSiteValue(row)"));
        assertTrue(parts.contains("!sameSiteText(site, primary)"));
        assertTrue(summary.contains("site-name-summary-primary"));
        assertTrue(summary.contains("site-name-summary-context"));
        assertTrue(sourceLabel.indexOf("dashboardChannelKind(row) === 'TRUNKED'") <
            sourceLabel.indexOf("row.channel_name"));
        assertTrue(sourceLabel.contains("if (row.channel_name) return row.channel_name"));
    }

    @Test
    void describesMetricAvailabilityWithoutImplyingReceiverUptime() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String coverage = function(source, "function dashboardCoverage(activity)");
        assertTrue(coverage.contains("'Metric availability'"));
        assertTrue(coverage.contains("'Full 24 hours'"));
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
        String sorter = function(source, "function sortSignalSites(sites)");
        String section = function(source, "async function signalHealthSection()");
        assertTrue(sorter.contains("siteLabel(left).localeCompare(siteLabel(right)"));
        assertTrue(sorter.contains("left.guid"));
        assertFalse(sorter.contains("decode_health_pct"));
        assertFalse(section.contains("Highest decode"));
        assertFalse(section.contains("Weakest signal"));
        String tile = function(source, "function updateSignalCurrentTile(tile, site)");
        assertTrue(tile.contains("siteNameSummary(site)"));
        assertTrue(tile.contains("dashboardReceiverSystemDetails(site)"));
        String identifiers = function(source, "function dashboardReceiverIdentifiers(row)");
        assertTrue(identifiers.contains("`RFSS ${rfss}`"));
        assertTrue(identifiers.contains("`Site ID ${site}`"));
        assertTrue(identifiers.contains("`NAC ${nac}`"));
    }

    @Test
    void stacksDashboardSplitsBeforeTabletTablesOverflow() throws Exception
    {
        String css = Files.readString(APP_CSS);
        assertTrue(css.contains(".dashboard-identity-split"));
        assertTrue(css.contains("grid-template-columns: repeat(2, minmax(0, 1fr))"));
        assertTrue(css.contains("@media (max-width: 1500px)"));
        assertTrue(css.contains("grid-template-columns: minmax(0, 1fr)"));
        assertTrue(css.contains(".dashboard-summary-section .summary-band"));
        assertTrue(css.contains(".dashboard-identity-context"));
        assertTrue(css.contains(".dashboard-mode"));
        assertTrue(css.contains(".site-name-summary"));
        assertTrue(css.contains(".site-name-summary-context"));
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
