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
 * prevent P25-only rankings, unsafe detail links, and the tablet two-column overflow from returning unnoticed.
 */
class StatsWebDashboardUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path APP_CSS = Path.of("stats-web", "assets", "app.css");

    @Test
    void rendersProtocolNeutralReceiversDestinationsAndSources() throws Exception
    {
        String dashboard = function(Files.readString(APP_JAVASCRIPT), "async function renderDashboard()");
        assertTrue(dashboard.contains("dashboard.recentReceivers"));
        assertTrue(dashboard.contains("dashboard.topDestinations"));
        assertTrue(dashboard.contains("dashboard.topSources"));
        assertTrue(dashboard.contains("'Recent Receivers'"));
        assertTrue(dashboard.contains("'Top Destinations · Last 24 Hours'"));
        assertTrue(dashboard.contains("'Top Sources · Last 24 Hours'"));
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
    void onlyBuildsLinksWhenTheApiReportsAConcreteDetailTarget() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String receiverLink = function(source, "function callSourceLink(row)");
        String identityLink = function(source, "function dashboardIdentityLink(row, label = dashboardIdentityId(row))");
        assertTrue(receiverLink.contains("detail_available ?? row.receiver_detail_available"));
        assertTrue(receiverLink.contains("return label"));
        assertTrue(identityLink.contains("identity_detail_available"));
        assertTrue(identityLink.contains("identity_detail_view"));
        assertTrue(identityLink.contains("return label"));
    }

    @Test
    void describesMetricAvailabilityWithoutImplyingReceiverUptime() throws Exception
    {
        String source = Files.readString(APP_JAVASCRIPT);
        String coverage = function(source, "function dashboardCoverage(activity)");
        assertTrue(coverage.contains("'Metric availability'"));
        assertTrue(coverage.contains("'Full range'"));
        assertTrue(coverage.contains("'Partial range'"));
        assertTrue(coverage.contains("'Unavailable'"));
        assertFalse(coverage.contains("Collection coverage"));
        String metricLabel = function(source, "function dashboardMetricLabel(activity, field, label)");
        assertTrue(metricLabel.contains("Partial coverage"));
        assertFalse(metricLabel.contains("Partial history"));
    }

    @Test
    void stacksDashboardSplitsBeforeTabletTablesOverflow() throws Exception
    {
        String css = Files.readString(APP_CSS);
        assertTrue(css.contains(".dashboard-identity-split"));
        assertTrue(css.contains("@media (max-width: 1100px)"));
        assertTrue(css.contains(".dashboard-overview-split"));
        assertTrue(css.contains("grid-template-columns: minmax(0, 1fr)"));
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
