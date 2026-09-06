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

/** Protects the one channel directory and channel-owned detail resources. */
class StatsWebChannelsUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");

    @Test
    void listsTrunkedAndConventionalChannelsTogether() throws Exception
    {
        String source = source();
        String render = function(source, "async function renderChannels()");
        String columns = function(source, "function channelDirectoryColumns()");

        assertTrue(render.contains("pageHeader('Channels', 'Every configured trunked and conventional receiver channel')"));
        assertTrue(render.contains("apiPage('/api/v1/channels', pageParameters())"));
        assertTrue(render.contains("exportCsvLink('channels')"));
        assertTrue(columns.contains("label: 'Type'"));
        assertTrue(columns.contains("? 'Trunked' : 'Conventional'"));
        assertTrue(columns.contains("row.primary_frequency_hz"));
        assertFalse(columns.contains("row.frequency_hz"));
        assertFalse(source.contains("/api/v1/conventional-channels"));
        assertFalse(source.contains("/api/v1/conventional-contexts"));
    }

    @Test
    void usesConfigurationIdForEveryChannelResource() throws Exception
    {
        String source = source();
        String render = function(source, "async function renderChannel()");
        String groups = function(source, "async function renderChannelGroupIdentities(configurationId)");
        String radios = function(source, "async function renderChannelRadios(configurationId)");

        assertTrue(render.contains("route.get('configuration_id')"));
        assertTrue(render.contains("api(channelApiPath(configurationId))"));
        assertTrue(groups.contains("channelApiPath(configurationId, 'group-identities')"));
        assertTrue(groups.contains("exportCsvLink('channel-group-identities'"));
        assertTrue(radios.contains("channelApiPath(configurationId, 'radios')"));
        assertTrue(radios.contains("exportCsvLink('channel-radios'"));
        assertFalse(source.contains("site.guid"));
        assertFalse(source.contains("context_key"));
    }

    @Test
    void hidesSyntheticAnalogGroupTablesButKeepsDigitalGroupsAndDmrSlots() throws Exception
    {
        String source = source();
        String tabs = function(source, "function channelTabItems(channel)");
        String groupColumns = function(source, "function channelGroupIdentityColumns()");

        assertTrue(tabs.contains("['AM', 'NBFM']"));
        assertTrue(tabs.contains("if (!analog && channelCapability(channel, 'group_identities'))"));
        assertTrue(groupColumns.contains("groupIdentityLabel(row)"));
        assertTrue(groupColumns.contains("render: (row) => groupIdentityLink(row)"));
        assertTrue(groupColumns.contains("render: (row) => groupIdentityAliasLink(row)"));
        assertTrue(groupColumns.contains("key: 'timeslot'"));
        assertTrue(groupColumns.contains("label: 'Slot'"));
    }

    @Test
    void linksChannelIdentityTablesToTheirDrillDownPages() throws Exception
    {
        String source = source();
        String groupColumns = function(source, "function channelGroupIdentityColumns()");
        String radioColumns = function(source, "function channelRadioColumns()");

        assertTrue(groupColumns.contains("radioLink(row, row.last_source_radio_id)"));
        assertTrue(groupColumns.contains("radioLink(row, row.last_source_radio_id, row.last_source_alias_name)"));
        assertTrue(radioColumns.contains("render: (row) => radioLink(row)"));
        assertTrue(radioColumns.contains("radioLink(row, undefined, aliasLabel(row))"));
        assertTrue(radioColumns.contains("groupIdentityLink(row, row.last_talkgroup_id)"));
        assertTrue(radioColumns.contains("groupIdentityLink(row, row.last_talkgroup_id, row.last_talkgroup_alias_name)"));
        assertTrue(radioColumns.contains("radioLink(row, row.last_peer_radio_id)"));
        assertTrue(radioColumns.contains("radioLink(row, row.last_peer_radio_id, row.last_peer_alias_name)"));
    }

    private static String source() throws Exception
    {
        assertTrue(Files.isRegularFile(APP_JAVASCRIPT), () -> "Missing " + APP_JAVASCRIPT.toAbsolutePath());
        return Files.readString(APP_JAVASCRIPT);
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
