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
}
