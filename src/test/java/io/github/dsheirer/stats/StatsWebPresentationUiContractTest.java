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

/** Protects the compact, per-user Live and table-presentation controls. */
class StatsWebPresentationUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path APP_CSS = Path.of("stats-web", "assets", "app.css");

    @Test
    void keepsOnlyReceiverTimingInSiteSettingsAndMovesRowPresentationToLive() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String site = function(source, "async function renderAdminSiteBehaviorSettings()");
        String live = function(source, "function openLivePresentationSettings(returnFocusSelector = null)");

        assertTrue(site.contains("'Traffic grant timing'"));
        assertTrue(site.contains("traffic_grant_age_out_milliseconds"));
        assertFalse(site.contains("retain_idle_call_details"));
        assertFalse(site.contains("clear_voice_decode_quality_on_call_end"));
        for(String setting: new String[]{"show_only_active_trunked_channels", "retain_last_call_on_idle_rows",
            "clear_voice_quality_when_idle"})
        {
            assertTrue(live.contains(setting), () -> "Missing Live preference " + setting);
        }
        assertTrue(live.contains("These choices affect only this signed-in user."));
        assertTrue(live.contains("Conventional channels are always shown."));
    }

    @Test
    void usesOneAccessibleColumnsIconAndPlacesHighTrafficControlsInSectionHeaders() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String activity = function(source, "async function renderActivity(scopeParameters, title = 'Activity')");
        String discover = function(source, "function renderObservedTalkgroups(main, page, selectedList)");
        String scanList = function(source,
            "async function renderScanListMembers(main, listResponse, scanListCatalog, scanList, renderContext)");
        String aliases = function(source, "async function renderAliases()");

        assertTrue(source.contains("trigger.append(iconGlyph('icon-columns'))"));
        assertTrue(source.contains("trigger.setAttribute('aria-label', 'Choose table columns')"));
        assertTrue(source.contains("trigger.title = 'Choose table columns'"));
        assertFalse(source.contains("inline ? '' : 'Columns'"));
        assertTrue(activity.contains("layoutMenuHost: titleActions"));
        assertTrue(activity.contains("section(title, activityTable, titleActions)"));
        assertTrue(activity.contains("titleActions.prepend(refreshControls)"));
        assertTrue(discover.contains("layoutMenuHost: actions"));
        assertTrue(discover.contains("section('Observed Talkgroups', host, actions)"));
        assertTrue(scanList.contains("layoutMenuHost: actions"));
        assertTrue(aliases.contains("layoutMenuHost: actions"));
    }

    @Test
    void keepsDiscoverColumnsAtomicAndDateInputsInsideTheirFilters() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String css = readText(APP_CSS);
        String discover = function(source, "function renderObservedTalkgroups(main, page, selectedList)");
        String identity = function(source, "function observedTalkgroupIdentity(row)");
        String filters = function(source, "function aliasEditorFilterToolbar(listResponse, options = null)");
        String detail = function(source, "function observedTalkgroupDetail(row, selectedList)");

        for(String label: new String[]{"'Identity'", "'System / Sites'", "'Calls'", "'Signaling'",
            "'Last Seen'"})
        {
            assertTrue(discover.contains(label), () -> "Missing Discover column " + label);
        }
        assertFalse(discover.contains("'Alias Match'"));
        assertFalse(source.contains("function observedTalkgroupCounts("));
        assertTrue(discover.contains("aliasMetricValue(row, 'logical_call_count')"));
        assertTrue(discover.contains("aliasMetricValue(row, 'signaling_observation_count')"));
        assertTrue(identity.contains("Covered by range"));
        assertTrue(detail.contains("recorded_logical_call_count"));
        assertTrue(detail.contains("stream_submitted_logical_call_count"));
        assertTrue(detail.contains("encrypted_logical_call_count"));
        assertTrue(filters.contains("'alias-filter alias-date-filter'"));
        assertTrue(css.contains(".alias-editor-filter-toolbar .alias-date-filter"));
        assertTrue(css.contains("width: 100%;\n  min-width: 0;\n  box-sizing: border-box;"));
    }

    private static String function(String source, String signature)
    {
        int start = source.indexOf(signature);
        if(start < 0)
        {
            throw new IllegalArgumentException("Missing " + signature);
        }
        int brace = source.indexOf('{', start);
        int depth = 0;
        boolean single = false;
        boolean dual = false;
        boolean template = false;
        boolean escaped = false;
        for(int index = brace; index < source.length(); index++)
        {
            char current = source.charAt(index);
            if(escaped)
            {
                escaped = false;
                continue;
            }
            if((single || dual || template) && current == '\\')
            {
                escaped = true;
                continue;
            }
            if(!dual && !template && current == '\'') single = !single;
            else if(!single && !template && current == '"') dual = !dual;
            else if(!single && !dual && current == '`') template = !template;
            else if(!single && !dual && !template)
            {
                if(current == '{') depth++;
                else if(current == '}' && --depth == 0) return source.substring(start, index + 1);
            }
        }
        throw new IllegalArgumentException("Unclosed " + signature);
    }

    private static String readText(Path path) throws Exception
    {
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }
}
