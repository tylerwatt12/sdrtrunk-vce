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
 * Protects the web presentation of authoritative receiver-channel presence. Presence is supplied by bounded API
 * rows and must never be reconstructed from calls, patch membership, or an unpaged side request.
 */
class StatsWebChannelPresenceUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path APP_CSS = Path.of("stats-web", "assets", "app.css");

    @Test
    void rendersOnlyTypedAuthoritativePresence() throws Exception
    {
        String source = source();
        String presence = function(source, "function authoritativePresence(row)");
        String identity = function(source, "function presenceChannelIdentity(channel)");
        String context = function(source, "function presenceChannelContext(channel)");
        String cell = function(source, "function channelPresenceCell(row, showConfirmation = true)");

        assertTrue(presence.contains("['registration', 'affiliation'].includes(evidence)"));
        assertTrue(presence.contains("presence?.confirmed_at_ms"));
        assertTrue(presence.contains("presence?.channel"));
        assertTrue(presence.contains("channel.protocol"));
        assertTrue(presence.contains("identifierNumber(channel.site_id)"));
        assertTrue(presence.contains("normalizedSiteText(channel.configuration_id)"));
        assertTrue(presence.contains(
            "(!identifierNumber(channel.site_id) && !normalizedSiteText(channel.configuration_id))"));
        assertFalse(presence.contains("last_seen_ms"));
        assertFalse(presence.contains("last_talkgroup"));
        assertFalse(presence.contains("patch"));
        assertFalse(presence.contains("call"));

        assertTrue(identity.contains("channel.site_id"));
        assertTrue(identity.contains("channel.configuration_id"));
        assertTrue(context.indexOf("channel?.site_name") < context.indexOf("channel?.name"));
        assertFalse(context.contains("channel?.channel_name"));
        assertTrue(cell.contains("channelLink(presence.channel, identity)"));
        assertTrue(cell.contains("presenceChannelContext(presence.channel)"));
        assertTrue(cell.contains("dateTime(presence.confirmed_at_ms)"));
    }

    @Test
    void consumesBoundedRelationshipPresenceWithoutAnAffiliationSideRequest() throws Exception
    {
        String source = source();
        String groupIdentity = function(source, "async function renderGroupIdentity()");
        String css = Files.readString(APP_CSS);

        assertTrue(groupIdentity.contains("kind === 'talkgroup'"));
        assertTrue(groupIdentity.contains("radioSystemCapability(groupIdentity, 'radio_channel_presence')"));
        assertTrue(groupIdentity.contains("route.get('affiliated') === 'true'"));
        assertTrue(groupIdentity.contains("affiliated: affiliatedOnly ? true : null"));
        assertTrue(groupIdentity.contains("row.currently_affiliated === true ? channelPresenceCell(row)"));
        assertTrue(groupIdentity.contains("label: 'Confirmed Channel'"));
        assertTrue(groupIdentity.contains("['Affiliated Channels', number(groupIdentity.affiliated_channels)]"));
        assertTrue(groupIdentity.contains("affiliatedOnly ? 'Clear Filter' : 'Show Affiliated'"));
        assertTrue(groupIdentity.contains("affiliatedOnly ? 'Affiliated Radios' : 'Radios'"));
        assertTrue(groupIdentity.contains("channelPresenceCell(row) : ''"));
        assertFalse(groupIdentity.contains("limit: 500"));
        assertFalse(groupIdentity.contains("new Set"));
        assertFalse(groupIdentity.contains("checkbox("));
        assertFalse(source.contains("function checkbox("));
        assertFalse(css.contains(".status-checkbox"));
    }

    @Test
    void combinesSystemAffiliationAndPreservesBoundedRouteFilters() throws Exception
    {
        String source = source();
        String columns = function(source, "function radioSystemRadioColumns(system)");
        String filters = function(source, "function affiliationRouteFilters()");
        String actions = function(source, "function affiliationFilterActions(exportAction = null)");
        String system = function(source, "async function renderRadioSystem()");

        assertTrue(columns.contains("label: 'Affiliation'"));
        assertTrue(columns.contains("render: affiliationTalkgroupCell"));
        assertTrue(columns.contains("label: 'Last Confirmed Channel'"));
        assertTrue(columns.contains("render: channelPresenceCell"));
        assertTrue(columns.contains("radioSystemCapability(system, 'radio_channel_presence')"));
        assertTrue(columns.contains("sort: 'channel'"));
        assertFalse(columns.contains("label: 'Affil TG'"));
        assertFalse(columns.contains("label: 'TG Alias'"));

        assertTrue(filters.contains("route.get('affiliated') === 'true' ? true : null"));
        assertTrue(filters.contains("route.get('configuration_id')"));
        assertTrue(actions.contains("affiliated: null, configuration_id: null, offset: null"));
        assertTrue(system.contains("pageParameters(filters)"));
        assertTrue(system.contains("{ ...radioSystem, ...filters }"));
        assertTrue(system.contains("affiliationFilterActions(exportAction)"));
        assertTrue(system.contains("system.affiliated_radios"));
        assertFalse(system.contains("system.affiliations"));
    }

    @Test
    void showsConfirmedPresenceAndLinksChannelAffiliationCount() throws Exception
    {
        String source = source();
        String radio = function(source, "async function renderRadio()");
        String channel = function(source, "async function renderTrunkedChannelInfo(channel, renderContext)");
        String metrics = function(source, "function metrics(values, embedded = false)");

        assertTrue(radio.contains("radioSystemCapability(radio, 'current_affiliations')"));
        assertTrue(radio.contains(
            "['Affiliation Confirmed', dateTime(radio.affiliation_confirmed_at_ms)]"));
        assertFalse(radio.contains("affiliation_updated_at_ms"));
        assertTrue(radio.contains("radioSystemCapability(radio, 'radio_channel_presence')"));
        assertTrue(radio.contains("section('Last Confirmed Channel'"));
        assertTrue(radio.contains("['Channel', channelPresenceCell(radio, false)]"));
        assertTrue(radio.contains("['Confirmed', presence ? dateTime(presence.confirmed_at_ms) : '—']"));
        assertTrue(channel.contains("channelCapability(channel, 'current_affiliations')"));
        assertTrue(channel.contains("channelCapability(channel, 'radio_channel_presence')"));
        assertTrue(channel.contains("summary.push(['Affiliated Radios', channel.affiliated_radios, linked])"));
        assertTrue(channel.contains("affiliated: true"));
        assertTrue(channel.contains("configuration_id: channel.configuration_id"));
        assertTrue(metrics.contains("displayed.append(valueNode("));
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
