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
        String tabBuilder = function(source, "function conventionalTabItems(context)");

        for(String capability: new String[]{"info", "talkgroups", "radios", "activity"})
        {
            assertTrue(tabBuilder.contains("conventionalCapability(context, '" + capability + "')"),
                () -> "Missing conventional capability check for " + capability);
        }

        assertFalse(tabBuilder.contains("protocol_code"));
        assertFalse(tabBuilder.contains("isDmr"));
    }

    @Test
    void usesBoundedDmrSummaryEndpointsAndDoesNotAddDmrPerCallActivity() throws Exception
    {
        String source = source();
        assertTrue(source.contains("const CONVENTIONAL_IDENTITY_PAGE_LIMIT = 100;"));
        assertTrue(source.contains("api('/api/conventional/talkgroups', pageParameters({"));
        assertTrue(source.contains("api('/api/conventional/radios', pageParameters({"));
        assertTrue(source.contains("limit: CONVENTIONAL_IDENTITY_PAGE_LIMIT"));
        assertFalse(source.contains("/api/conventional/activity"));
    }

    @Test
    void rendersDmrIdentitiesCountersAliasesAndRfContext() throws Exception
    {
        String source = source();
        String talkgroups = function(source, "function conventionalTalkgroupColumns()");
        String radios = function(source, "function conventionalRadioColumns()");

        for(String field: new String[]{"talkgroup_id", "alias_name", "frequency_hz", "timeslot", "call_count",
            "encrypted_count", "last_source_radio_id", "last_source_alias_name", "first_seen_ms", "last_seen_ms"})
        {
            assertTrue(talkgroups.contains(field), () -> "Missing DMR talkgroup field " + field);
        }

        for(String field: new String[]{"radio_id", "alias_name", "frequency_hz", "timeslot", "call_count",
            "source_call_count", "target_call_count", "group_call_count", "private_call_count", "encrypted_count",
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
        String detail = function(source, "async function renderConventionalDetail()");
        String radios = function(source, "function conventionalRadioColumns()");
        assertTrue(list.contains("decoderDisplay(row.decoder)"));
        assertTrue(detail.contains("decoderDisplay(context.decoder)"));
        assertTrue(detail.contains("label: 'Calls'"));
        assertTrue(detail.contains("label: 'Rec'"));
        assertTrue(detail.contains("label: 'Sent'"));
        assertTrue(detail.contains("label: 'Enc'"));
        assertTrue(radios.indexOf("id: 'encrypted'") > radios.indexOf("id: 'calls'"));
        assertTrue(radios.indexOf("id: 'encrypted'") < radios.indexOf("id: 'source-calls'"));
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
