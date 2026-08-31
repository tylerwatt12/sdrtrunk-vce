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

        for(String capability: new String[]{"channels", "quality", "neighbors", "frequency_bands",
            "patch_groups", "activity", "group_identities", "current_affiliations",
            "radio_site_presence"})
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
    void rendersNeighborSiteFromTheCanonicalIdentifier() throws Exception
    {
        String source = source();
        String identifier = function(source, "function neighborSiteId(row)");
        String p25Columns = function(source, "function p25SiteNeighborColumns()");
        String trunkedColumns = function(source, "function trunkedSiteNeighborColumns(site)");

        assertTrue(identifier.contains("return row?.site_id"));
        assertFalse(identifier.contains("?? row?.site"));
        assertTrue(p25Columns.contains("hex(neighborSiteId(row), 2)"));
        assertTrue(trunkedColumns.contains("identifierNumber(neighborSiteId(row))"));
    }

    @Test
    void usesManufacturerNameAndOmitsMetricImplementationNotes() throws Exception
    {
        String source = source();
        assertTrue(source.contains("['Manufacturer', site.mfid_display]"));
        assertTrue(source.contains("['Configured Decoder Mode', p25DecoderMode(site.p25_decoder_mode)]"));
        assertFalse(source.contains("Auto Starting Preference"));
        assertTrue(function(source, "function p25DecoderMode(value)")
            .contains("CQPSK: 'Simulcast (LSM / CQPSK)'"));
        assertFalse(source.contains("['MFID'"));
        assertFalse(source.contains("Last Active identifies"));
        assertFalse(source.contains("counters begin"));
        assertFalse(source.contains("outputMetricStartNote"));
    }

    @Test
    void rendersDecodedP25ConnectionStatus() throws Exception
    {
        String source = source();
        String siteDetails = function(source, "function p25SiteDetailRows(site)");

        assertTrue(siteDetails.contains("site.active_rfss_network_connection"));
    }

    @Test
    void keepsSiteMetadataAndPhysicalCallObservationsDistinct() throws Exception
    {
        String source = source();
        String siteInfo = function(source, "async function renderSiteInfo(site, renderContext)");
        String configuredSite = function(source, "function configuredSiteValue(row)");
        String siteInfoSite = function(source, "function siteInfoSiteValue(row)");
        String talkgroups = function(source, "async function siteTopTalkgroupsSection(site)");
        String channels = function(source, "function trunkedSiteChannelColumns()");
        assertTrue(siteInfo.contains("['Metadata Updates', site.observation_count]"));
        assertTrue(siteInfo.contains("['Decoder', decoderDisplay(site.decoder)]"));
        assertTrue(siteInfo.contains("['Site', siteInfoSiteValue(site)]"));
        assertTrue(siteInfo.contains("['Name', configuredNameValue(site)]"));
        assertTrue(siteInfo.contains("summary.push(['Affiliated Radios', site.affiliated_radios, linked])"));
        assertTrue(siteInfo.contains("tab: 'radios', affiliated: true, site_guid: site.guid"));
        assertFalse(siteInfo.contains("['Name', site.channel_name]"));
        assertFalse(configuredSite.contains("channel_name"));
        assertTrue(siteInfoSite.contains("configuredNameValue(row) ? ''"));
        assertTrue(talkgroups.contains("section('Talkgroup Site Observations'"));
        assertTrue(talkgroups.contains("label: 'Site Observations'"));
        assertTrue(talkgroups.contains("fullLabel: 'Encrypted Site Observations'"));
        assertFalse(talkgroups.contains("recorded_logical_call_count"));
        assertFalse(talkgroups.contains("stream_submitted_logical_call_count"));
        assertFalse(talkgroups.contains("Last Active"));
        assertTrue(channels.contains("label: 'Seen'"));
        assertTrue(channels.contains("fullLabel: 'Last Seen'"));
        assertFalse(channels.contains("Last Recorded"));
    }

    @Test
    void usesNameAsTheSiteTitleAndSiteAsSeparateContext() throws Exception
    {
        String source = source();
        String renderer = function(source, "async function renderSite()");
        String display = function(source, "function siteDisplayParts(row)");
        assertTrue(display.contains("const primary = name || site"));
        assertTrue(display.contains("secondary: site && !sameSiteText(site, primary)"));
        assertTrue(renderer.contains("const display = siteDisplayParts(site)"));
        assertTrue(renderer.contains("[display.secondary, protocolFamily(site)"));
        assertTrue(renderer.contains("pageHeader(siteValue(site), subtitle)"));
    }

    @Test
    void keepsSiteViewsInsideTheSystemsAccessBoundary() throws Exception
    {
        String source = source();
        String tabItems = function(source, "function siteTabItems(site)");
        String siteInfo = function(source, "async function renderSiteInfo(site, renderContext)");
        String site = function(source, "async function renderSite()");

        assertFalse(source.contains("function liveSiteReceiverSection(site)"));
        assertFalse(source.contains("liveConnection('/live/sites')"));
        assertFalse(source.contains("liveConnection('/api/v1/live/sites')"));
        assertFalse(source.contains("section('Live Receiver'"));
        assertFalse(source.contains("siteCapability(site, 'quality-live')"));
        assertTrue(tabItems.contains("siteCapability(site, 'quality')"));
        assertTrue(siteInfo.contains("siteCapability(site, 'group_identities')"));
        assertTrue(siteInfo.contains("siteTopTalkgroupsSection(site)"));
        assertTrue(site.contains("const signalHistory = await siteSignalHistorySection(site)"));
        assertTrue(site.contains("if (!renderIsCurrent(renderContext)) return"));
    }

    @Test
    void labelsTheOneEffectiveP25BandplanWithoutInventingOverrideObservations() throws Exception
    {
        String site = function(source(), "async function renderSite()");

        assertTrue(site.contains("data.band_source === 'P25_OVERRIDE'"));
        assertTrue(site.contains("overrideActive ? 'P25 Override' : 'OTA Bandplan'"));
        assertTrue(site.contains("if (!overrideActive) homeBandColumns.push("));
        assertTrue(site.contains("label: 'Obs'"));
        assertTrue(site.contains("label: 'Seen'"));
        assertFalse(site.contains("label: 'Source'"));
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
