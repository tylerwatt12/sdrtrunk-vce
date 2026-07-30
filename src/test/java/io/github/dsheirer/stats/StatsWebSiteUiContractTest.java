/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Protects the intentionally shared P25/DMR/NXDN site-page architecture.  Browser behavior is exercised during web
 * smoke testing; these source-contract checks prevent a second protocol-specific renderer from silently returning.
 */
class StatsWebSiteUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");

    @Test
    void usesOneSiteRendererAndOneTabBuilder() throws Exception
    {
        String source = source();
        assertEquals(1, occurrences(source, "async function renderSite()"));
        assertEquals(1, occurrences(source, "function siteTabs(site, active)"));
        assertFalse(source.contains("renderTrunkedSite"));
        assertFalse(source.contains("trunkedSiteTabs"));
        assertFalse(source.contains("site.site_kind === 'trunked'"));
    }

    @Test
    void drivesSharedAndOptionalFeaturesFromCapabilities() throws Exception
    {
        String source = source();

        for(String capability: new String[]{"channels", "quality", "quality-live", "quality-history",
            "neighbors", "band-plan", "patches", "activity", "top-talkgroups"})
        {
            assertTrue(source.contains("siteCapability(site, '" + capability + "')"),
                () -> "Missing site capability check for " + capability);
        }
    }

    @Test
    void keepsProtocolDifferencesInDetailAndTableAdapters() throws Exception
    {
        String source = source();

        for(String adapter: new String[]{"p25SiteDetailRows", "dmrSiteDetailRows", "nxdnSiteDetailRows",
            "p25SiteChannelColumns", "trunkedSiteChannelColumns", "p25SiteNeighborColumns",
            "trunkedSiteNeighborColumns"})
        {
            assertTrue(source.contains("function " + adapter), () -> "Missing site adapter " + adapter);
        }
    }

    @Test
    void usesManufacturerNameAndOmitsMetricImplementationNotes() throws Exception
    {
        String source = source();
        assertTrue(source.contains("['Manufacturer', site.mfid_display]"));
        assertFalse(source.contains("['MFID'"));
        assertFalse(source.contains("Last Active identifies"));
        assertFalse(source.contains("counters begin"));
        assertFalse(source.contains("outputMetricStartNote"));
    }

    @Test
    void keepsSiteMetadataAndCallOutcomesDistinct() throws Exception
    {
        String source = source();
        String siteInfo = function(source, "async function renderSiteInfo(site)");
        String talkgroups = function(source, "async function siteTopTalkgroupsSection(site)");
        String channels = function(source, "function trunkedSiteChannelColumns()");
        assertTrue(siteInfo.contains("['Metadata Updates', site.observation_count]"));
        assertTrue(siteInfo.contains("['Decoder', decoderDisplay(site.decoder)]"));
        assertTrue(talkgroups.contains("section('Talkgroup Call Activity'"));
        assertTrue(talkgroups.contains("label: 'Calls'"));
        assertTrue(talkgroups.contains("label: 'Rec'"));
        assertTrue(talkgroups.contains("label: 'Sent'"));
        assertTrue(talkgroups.contains("label: 'Enc'"));
        assertFalse(talkgroups.contains("Last Active"));
        assertTrue(channels.contains("label: 'Seen'"));
        assertTrue(channels.contains("fullLabel: 'Last Seen'"));
        assertFalse(channels.contains("Last Recorded"));
    }

    private static String source() throws Exception
    {
        assertTrue(Files.isRegularFile(APP_JAVASCRIPT), () -> "Missing " + APP_JAVASCRIPT.toAbsolutePath());
        return Files.readString(APP_JAVASCRIPT);
    }

    private static int occurrences(String source, String value)
    {
        int count = 0;
        int offset = 0;

        while((offset = source.indexOf(value, offset)) >= 0)
        {
            count++;
            offset += value.length();
        }

        return count;
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
