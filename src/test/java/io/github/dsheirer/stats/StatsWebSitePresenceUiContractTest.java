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
 * Protects the web presentation of authoritative radio site presence.  Presence is supplied by the bounded API
 * rows and must never be reconstructed from calls, patch membership, or an unpaged side request.
 */
class StatsWebSitePresenceUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path APP_CSS = Path.of("stats-web", "assets", "app.css");

    @Test
    void rendersOnlyTypedAuthoritativePresence() throws Exception
    {
        String source = source();
        String presence = function(source, "function authoritativePresence(row)");
        String identity = function(source, "function presenceSiteIdentity(site)");
        String context = function(source, "function presenceSiteContext(site)");
        String cell = function(source, "function sitePresenceCell(row, showConfirmation = true)");

        assertTrue(presence.contains("['registration', 'affiliation'].includes(evidence)"));
        assertTrue(presence.contains("presence?.confirmed_at_ms"));
        assertTrue(presence.contains("presence?.site"));
        assertTrue(presence.contains("site.protocol"));
        assertTrue(presence.contains("identifierNumber(site.site_id)"));
        assertTrue(presence.contains("normalizedSiteText(site.guid)"));
        assertTrue(presence.contains(
            "(!identifierNumber(site.site_id) && !normalizedSiteText(site.guid))"));
        assertFalse(presence.contains("last_seen_ms"));
        assertFalse(presence.contains("last_talkgroup"));
        assertFalse(presence.contains("patch"));
        assertFalse(presence.contains("call"));

        assertTrue(identity.contains("site.site_id"));
        assertFalse(identity.contains("hex(site.site,"));
        assertTrue(context.indexOf("site?.configured_site") < context.indexOf("site?.configured_name"));
        assertTrue(context.indexOf("site?.configured_name") < context.indexOf("site?.channel_name"));
        assertTrue(cell.contains("siteLink(presence.site, identity)"));
        assertTrue(cell.contains("presenceSiteContext(presence.site)"));
        assertTrue(cell.contains("dateTime(presence.confirmed_at_ms)"));
    }

    @Test
    void consumesBoundedRelationshipPresenceWithoutAnAffiliationSideRequest() throws Exception
    {
        String source = source();
        String talkgroup = function(source, "async function renderTalkgroup()");
        String css = Files.readString(APP_CSS);

        assertTrue(talkgroup.contains("kind === 'talkgroup'"));
        assertTrue(talkgroup.contains("systemCapability(talkgroup, 'radio_site_presence')"));
        assertTrue(talkgroup.contains("route.get('affiliated') === 'true'"));
        assertTrue(talkgroup.contains("affiliated: affiliatedOnly ? true : null"));
        assertTrue(talkgroup.contains("row.currently_affiliated === true ? sitePresenceCell(row)"));
        assertTrue(talkgroup.contains("label: 'Affiliated Site'"));
        assertTrue(talkgroup.contains("['Affiliated Sites', number(talkgroup.affiliated_sites)]"));
        assertTrue(talkgroup.contains("affiliatedOnly ? 'Clear Filter' : 'Show Affiliated'"));
        assertTrue(talkgroup.contains("affiliatedOnly ? 'Affiliated Radios' : 'Radios'"));
        assertTrue(talkgroup.contains("sitePresenceCell(row) : ''"));
        assertFalse(talkgroup.contains("systemApiPath(systemScope.scope, 'affiliations')"));
        assertFalse(talkgroup.contains("limit: 500"));
        assertFalse(talkgroup.contains("new Set"));
        assertFalse(talkgroup.contains("checkbox("));
        assertFalse(source.contains("function checkbox("));
        assertFalse(css.contains(".status-checkbox"));
    }

    @Test
    void combinesSystemAffiliationAndPreservesBoundedRouteFilters() throws Exception
    {
        String source = source();
        String columns = function(source, "function systemRadioColumns(system)");
        String filters = function(source, "function affiliationRouteFilters()");
        String actions = function(source, "function affiliationFilterActions(exportAction = null)");
        String system = function(source, "async function renderSystem()");

        assertTrue(columns.contains("label: 'Affiliation'"));
        assertTrue(columns.contains("render: affiliationTalkgroupCell"));
        assertTrue(columns.contains("label: 'Last Confirmed Site'"));
        assertTrue(columns.contains("render: sitePresenceCell"));
        assertTrue(columns.contains("systemCapability(system, 'radio_site_presence')"));
        assertTrue(columns.contains("sort: 'site'"));
        assertFalse(columns.contains("label: 'Affil TG'"));
        assertFalse(columns.contains("label: 'TG Alias'"));

        assertTrue(filters.contains("route.get('affiliated') === 'true' ? true : null"));
        assertTrue(filters.contains("route.get('site_guid')"));
        assertTrue(actions.contains("affiliated: null, site_guid: null, offset: null"));
        assertTrue(system.contains("pageParameters(filters)"));
        assertTrue(system.contains("{ ...systemScope, ...filters }"));
        assertTrue(system.contains("affiliationFilterActions(exportAction)"));
        assertTrue(system.contains("system.affiliated_radios"));
        assertFalse(system.contains("system.affiliations"));
    }

    @Test
    void showsConfirmedPresenceAndLinksSiteAffiliationCount() throws Exception
    {
        String source = source();
        String radio = function(source, "async function renderRadio()");
        String site = function(source, "async function renderSiteInfo(site, renderContext)");
        String metrics = function(source, "function metrics(values, embedded = false)");

        assertTrue(radio.contains("systemCapability(radio, 'current_affiliations')"));
        assertTrue(radio.contains(
            "['Affiliation Confirmed', dateTime(radio.affiliation_confirmed_at_ms)]"));
        assertFalse(radio.contains("affiliation_updated_at_ms"));
        assertTrue(radio.contains("systemCapability(radio, 'radio_site_presence')"));
        assertTrue(radio.contains("section('Last Confirmed Site'"));
        assertTrue(radio.contains("['Site', sitePresenceCell(radio, false)]"));
        assertTrue(radio.contains("['Confirmed', presence ? dateTime(presence.confirmed_at_ms) : '—']"));
        assertTrue(site.contains("siteCapability(site, 'current_affiliations')"));
        assertTrue(site.contains("siteCapability(site, 'radio_site_presence')"));
        assertTrue(site.contains("['Affiliated Radios', site.affiliated_radios, linked]"));
        assertTrue(site.contains("affiliated: true, site_guid: site.guid"));
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
