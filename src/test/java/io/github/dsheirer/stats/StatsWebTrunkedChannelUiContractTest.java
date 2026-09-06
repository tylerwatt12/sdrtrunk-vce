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
 * Protects the shared P25/DMR/NXDN trunked-channel page. Browser behavior is exercised during web smoke testing;
 * these checks keep protocol differences inside small adapters instead of separate information architectures.
 */
class StatsWebTrunkedChannelUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");

    @Test
    void usesOneChannelRendererAndOneTrunkedTabBuilder() throws Exception
    {
        String source = source();
        assertEquals(1, occurrences(source, "async function renderChannel()"));
        assertEquals(1, occurrences(source, "function trunkedChannelTabs(channel, active)"));
        assertTrue(function(source, "async function renderChannel()")
            .contains("String(channel.channel_kind || '').toUpperCase() === 'TRUNKED'"));
        assertFalse(source.contains("async function renderSite()"));
        assertFalse(source.contains("view=site"));
    }

    @Test
    void drivesSharedAndOptionalFeaturesFromCapabilities() throws Exception
    {
        String source = source();

        for(String capability: new String[]{"channels", "quality", "neighbors", "frequency_bands",
            "patch_groups", "activity", "group_identities", "current_affiliations",
            "radio_channel_presence"})
        {
            assertTrue(source.contains("channelCapability(channel, '" + capability + "')"),
                () -> "Missing channel capability check for " + capability);
        }
    }

    @Test
    void keepsProtocolDifferencesInDetailAndTableAdapters() throws Exception
    {
        String source = source();

        for(String adapter: new String[]{"p25ChannelDetailRows", "dmrChannelDetailRows", "nxdnChannelDetailRows",
            "p25ChannelFrequencyColumns", "trunkedChannelFrequencyColumns", "p25ChannelNeighborColumns",
            "trunkedChannelNeighborColumns"})
        {
            assertTrue(source.contains("function " + adapter), () -> "Missing channel adapter " + adapter);
        }
    }

    @Test
    void separatesRadioSystemIdentityFromObservedDmrAndNxdnSiteFacts() throws Exception
    {
        String source = source();
        String directoryIdentity = function(source, "function channelDirectoryRfIdentity(row)");
        String locationIdentity = function(source, "function channelLocationIdentity(channel)");
        String dmr = function(source, "function dmrChannelDetailRows(channel)");
        String nxdn = function(source, "function nxdnChannelDetailRows(channel)");

        assertTrue(directoryIdentity.contains("family === 'DMR'"));
        assertTrue(directoryIdentity.contains("row.site_id"));
        assertFalse(directoryIdentity.contains("row.site_system_id"));
        assertTrue(locationIdentity.contains("channel.site_id"));
        assertTrue(locationIdentity.contains("channel.site_network_id"));

        assertTrue(dmr.contains("['Radio System Network', identifierNumber(channel.network_id)]"));
        assertTrue(dmr.contains("['Radio System Model', semanticLabel(channel.model)]"));
        assertTrue(dmr.contains("['Observed Site', identifierNumber(channel.site_id)]"));
        assertFalse(dmr.contains("channel.system_id"));
        assertFalse(dmr.contains("channel.site_system_id"));
        assertFalse(dmr.contains("channel.site_network_id"));
        assertFalse(dmr.contains("channel.ran"));

        assertTrue(nxdn.contains("['Radio System Category', semanticLabel(channel.location_category)]"));
        assertTrue(nxdn.contains("['Radio System ID', identifierNumber(channel.system_id)]"));
        assertTrue(nxdn.contains("['Observed Site', identifierNumber(channel.site_id)]"));
        assertTrue(nxdn.contains("identifierNumber(channel.site_network_id)"));
        assertTrue(nxdn.contains("['Observed Integrator', integrator]"));
        assertFalse(nxdn.contains("channel.network_id"));
        assertFalse(nxdn.contains("channel.site_system_id"));
    }

    @Test
    void rendersNeighborSiteFromTheCanonicalIdentifier() throws Exception
    {
        String source = source();
        String identifier = function(source, "function neighborSiteId(row)");
        String p25Columns = function(source, "function p25ChannelNeighborColumns()");
        String trunkedColumns = function(source, "function trunkedChannelNeighborColumns(channel)");

        assertTrue(identifier.contains("return row?.site_id"));
        assertFalse(identifier.contains("?? row?.site"));
        assertTrue(p25Columns.contains("hex(neighborSiteId(row), 2)"));
        assertTrue(trunkedColumns.contains("identifierNumber(neighborSiteId(row))"));
    }

    @Test
    void presentsPatchTelemetryWithCanonicalFieldsAndIdentityLinks() throws Exception
    {
        String source = source();
        int patches = source.indexOf("tab === 'patches'");
        int activity = source.indexOf("tab === 'activity'", patches);
        assertTrue(patches >= 0);
        assertTrue(activity > patches);
        String patchPresentation = source.substring(patches, activity);

        assertTrue(patchPresentation.contains("label: 'Local Patch'"));
        assertTrue(patchPresentation.contains("label: 'Local TGIDs'"));
        assertTrue(patchPresentation.contains("label: 'Local Radios'"));
        assertTrue(patchPresentation.contains("patchMembersByLocalGroup(data.talkgroups)"));
        assertTrue(patchPresentation.contains("patchMembersByLocalGroup(data.radios)"));
        assertTrue(patchPresentation.contains("groupIdentityLink(row, row.local_patch_group_id)"));
        assertTrue(patchPresentation.contains("groupIdentityLink(member, member.local_talkgroup_id)"));
        assertTrue(patchPresentation.contains("radioLink(member, member.local_radio_id)"));
        assertFalse(patchPresentation.contains("row.patch_group"));
        assertFalse(patchPresentation.contains("member.talkgroup_id"));
        assertFalse(patchPresentation.contains("member.radio_id"));
    }

    @Test
    void usesManufacturerNameAndOmitsMetricImplementationNotes() throws Exception
    {
        String source = source();
        assertTrue(source.contains("['Manufacturer', channel.mfid_display]"));
        assertTrue(source.contains("['Configured Decoder Mode', p25DecoderMode(channel.p25_decoder_mode)]"));
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
        String siteDetails = function(source, "function p25ChannelDetailRows(channel)");

        assertTrue(siteDetails.contains("channel.active_rfss_network_connection"));
    }

    @Test
    void keepsSiteMetadataAndPhysicalCallObservationsDistinct() throws Exception
    {
        String source = source();
        String channelInfo = function(source, "async function renderTrunkedChannelInfo(channel, renderContext)");
        String siteName = function(source, "function siteNameValue(row)");
        String groups = function(source, "async function channelTopGroupsSection(channel)");
        String frequencies = function(source, "function trunkedChannelFrequencyColumns()");
        assertTrue(channelInfo.contains("['Metadata Updates', channel.observation_count]"));
        assertTrue(channelInfo.contains("['Decoder', decoderDisplay(channel.decoder)]"));
        assertTrue(channelInfo.contains("['Site', siteNameValue(channel)]"));
        assertTrue(channelInfo.contains("['Name', nameValue(channel)]"));
        assertTrue(channelInfo.contains("summary.push(['Affiliated Radios', channel.affiliated_radios, linked])"));
        assertTrue(channelInfo.contains("configuration_id: channel.configuration_id"));
        assertFalse(channelInfo.contains("['Name', channel.channel_name]"));
        assertFalse(siteName.contains("channel_name"));
        assertTrue(groups.contains("section('Group Activity on This Channel'"));
        assertTrue(groups.contains("label: 'Logical Calls'"));
        assertTrue(groups.contains("row.logical_call_count"));
        assertTrue(groups.contains("fullLabel: 'Encrypted Logical Calls'"));
        assertTrue(groups.contains("row.encrypted_logical_call_count"));
        assertFalse(groups.contains("channel_observation_count"));
        assertFalse(groups.contains("recorded_logical_call_count"));
        assertFalse(groups.contains("stream_submitted_logical_call_count"));
        assertFalse(groups.contains("Last Active"));
        assertTrue(frequencies.contains("label: 'Seen'"));
        assertTrue(frequencies.contains("fullLabel: 'Last Seen'"));
        assertFalse(frequencies.contains("Last Recorded"));
    }

    @Test
    void usesConfiguredNameAsChannelTitleAndSiteAsSeparateContext() throws Exception
    {
        String source = source();
        String renderer = function(source, "async function renderTrunkedChannel(channel, configurationId, renderContext)");
        String display = function(source, "function channelDisplayParts(row)");
        assertTrue(display.contains("const primary = name || site"));
        assertTrue(display.contains("secondary: site && !sameSiteText(site, primary)"));
        assertTrue(renderer.contains("const display = channelDisplayParts(channel)"));
        assertTrue(renderer.contains("[display.secondary, protocolFamily(channel)"));
        assertTrue(renderer.contains("pageHeader(channelValue(channel), subtitle)"));
    }

    @Test
    void keepsChannelViewsInsideTheRadioAccessBoundary() throws Exception
    {
        String source = source();
        String tabItems = function(source, "function trunkedChannelTabItems(channel)");
        String channelInfo = function(source, "async function renderTrunkedChannelInfo(channel, renderContext)");
        String channel = function(source, "async function renderTrunkedChannel(channel, configurationId, renderContext)");

        assertFalse(source.contains("function liveSiteReceiverSection(site)"));
        assertFalse(source.contains("liveConnection('/live/sites')"));
        assertFalse(source.contains("liveConnection('/api/v1/live/sites')"));
        assertFalse(source.contains("section('Live Receiver'"));
        assertFalse(source.contains("channelCapability(channel, 'quality-live')"));
        assertTrue(tabItems.contains("channelCapability(channel, 'quality')"));
        assertTrue(channelInfo.contains("channelCapability(channel, 'group_identities')"));
        assertTrue(channelInfo.contains("channelTopGroupsSection(channel)"));
        assertTrue(channel.contains("const signalHistory = await channelSignalHistorySection(channel)"));
        assertTrue(channel.contains("if (!renderIsCurrent(renderContext)) return"));
    }

    @Test
    void labelsTheOneEffectiveP25BandplanWithoutInventingOverrideObservations() throws Exception
    {
        String channel = function(source(), "async function renderTrunkedChannel(channel, configurationId, renderContext)");

        assertTrue(channel.contains("data.band_source === 'P25_OVERRIDE'"));
        assertTrue(channel.contains("overrideActive ? 'P25 Override' : 'OTA Bandplan'"));
        assertTrue(channel.contains("if (!overrideActive) homeBandColumns.push("));
        assertTrue(channel.contains("label: 'Observations'"));
        assertFalse(channel.contains("label: 'Obs'"));
        assertTrue(channel.contains("label: 'Seen'"));
        assertFalse(channel.contains("label: 'Source'"));
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
