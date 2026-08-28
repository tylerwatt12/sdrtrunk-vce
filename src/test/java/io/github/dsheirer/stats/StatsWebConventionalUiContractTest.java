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
 * Protects the capability-driven conventional detail UI and its bounded DMR identity tables.  Browser behavior is
 * exercised during web smoke testing; these source-contract checks keep DMR away from the per-call activity endpoint
 * and prevent an unbounded identity request from reaching the browser.
 */
class StatsWebConventionalUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");

    @Test
    void buildsConventionalTabsFromServerCapabilities() throws Exception
    {
        String source = source();
        String tabBuilder = function(source, "function conventionalTabItems(channel)");

        for(String capability: new String[]{"group_identities", "radios", "activity"})
        {
            assertTrue(tabBuilder.contains("conventionalCapability(channel, '" + capability + "')"),
                () -> "Missing conventional capability check for " + capability);
        }

        assertTrue(tabBuilder.contains(
            "conventionalCapability(channel, 'activity') && capabilityAllowed(ACCESS_CAPABILITIES.SYSTEMS)"));
        assertFalse(tabBuilder.contains("protocol_code"));
        assertFalse(tabBuilder.contains("isDmr"));
    }

    @Test
    void usesBoundedDmrSummaryEndpointsAndDoesNotAddDmrPerCallActivity() throws Exception
    {
        String source = source();
        assertTrue(source.contains("const CONVENTIONAL_IDENTITY_PAGE_LIMIT = 100;"));
        assertTrue(source.contains("apiPage(conventionalApiPath(configurationId, 'talkgroups'), pageParameters({"));
        assertTrue(source.contains("apiPage(conventionalApiPath(configurationId, 'radios'), pageParameters({"));
        assertTrue(source.contains("limit: CONVENTIONAL_IDENTITY_PAGE_LIMIT"));
        assertTrue(source.contains("apiPage('/api/v1/conventional-channels', pageParameters())"));
        assertFalse(source.contains("/api/v1/conventional-contexts"));
        assertFalse(function(source, "async function renderConventionalDetail()").contains("context_key"));
    }

    @Test
    void rendersDmrIdentitiesCountersAliasesAndRfContext() throws Exception
    {
        String source = source();
        String talkgroups = function(source, "function conventionalTalkgroupColumns()");
        String radios = function(source, "function conventionalRadioColumns()");

        for(String field: new String[]{"talkgroup_id", "alias_name", "frequency_hz", "timeslot",
            "logical_call_count", "encrypted_logical_call_count", "last_source_radio_id",
            "last_source_alias_name", "first_seen_ms", "last_seen_ms"})
        {
            assertTrue(talkgroups.contains(field), () -> "Missing DMR talkgroup field " + field);
        }

        for(String field: new String[]{"radio_id", "alias_name", "frequency_hz", "timeslot", "logical_call_count",
            "source_logical_call_count", "target_logical_call_count", "group_logical_call_count",
            "private_logical_call_count", "encrypted_logical_call_count",
            "last_talkgroup_id", "last_talkgroup_alias_name", "last_peer_radio_id", "last_peer_alias_name",
            "first_seen_ms", "last_seen_ms"})
        {
            assertTrue(radios.contains(field), () -> "Missing DMR radio field " + field);
        }
    }

    @Test
    void humanizesDecodersAndGroupsConventionalCallOutcomes() throws Exception
    {
        String source = source();
        String list = function(source, "async function renderConventional()");
        String columns = function(source, "function conventionalColumns()");
        String mode = function(source, "function conventionalMode(row)");
        String details = function(source, "function conventionalDetails(row)");
        String detail = function(source, "async function renderConventionalDetail()");
        String radios = function(source, "function conventionalRadioColumns()");
        assertTrue(columns.contains("label: 'Mode'"));
        assertTrue(columns.contains("label: 'Details'"));
        assertFalse(columns.contains("label: 'Slot'"));
        assertFalse(columns.contains("label: 'NAC'"));
        assertTrue(mode.contains("decoderLabel(row.decoder, true)"));
        assertFalse(mode.contains("decoderDisplay"));
        assertTrue(details.contains("`NAC ${hex(row.nac, 3)}`"));
        assertTrue(list.contains("createAsyncSection('Conventional Channels'"));
        assertTrue(list.contains("apiPage('/api/v1/conventional-channels', pageParameters())"));
        assertTrue(list.indexOf("beginPage(renderContext") < list.indexOf("await directory.load("));
        assertTrue(detail.contains("const channel = data?.channel"));
        assertTrue(detail.contains("decoderDisplay(channel.decoder)"));
        assertTrue(detail.contains("label: 'Logical Calls'"));
        assertTrue(detail.contains("label: 'Rec'"));
        assertTrue(detail.contains("label: 'Submitted'"));
        assertTrue(detail.contains("label: 'Enc'"));
        assertTrue(detail.contains("timeslotLabel(row.timeslot)"));
        assertTrue(radios.indexOf("id: 'encrypted-logical-calls'") >
            radios.indexOf("id: 'logical-calls'"));
        assertTrue(radios.indexOf("id: 'encrypted-logical-calls'") <
            radios.indexOf("id: 'source-logical-calls'"));
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
