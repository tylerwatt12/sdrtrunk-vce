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

/** Protects retained Activity filters, their page-specific scope, and URL/query separation. */
class StatsWebActivityFiltersUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");

    @Test
    void keepsBrowserFilterStateActivityPrefixedAndTranslatesOnlyAtTheApiBoundary() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String routes = function(source, "function activityFilterRouteOverrides(filters)");
        String api = function(source, "function activityApiFilterParameters(filters, now = Date.now())");
        String activity = function(source, "async function renderActivity(scopeParameters, title = 'Activity')");

        for(String key: new String[]{"activity_range", "activity_from_ms", "activity_to_ms", "activity_action",
            "activity_event_type", "activity_encryption", "activity_include_grants",
            "activity_configuration_id", "activity_radio_role", "activity_group_match",
            "activity_source_identity_key", "activity_target_identity_key", "activity_source_id",
            "activity_target_id", "activity_target_kind", "activity_frequency_hz", "activity_lcn",
            "activity_timeslot"})
        {
            assertTrue(routes.contains(key + ":"), () -> "Missing Activity route key " + key);
        }
        for(String key: new String[]{"from_ms", "to_ms", "action", "event_type", "encryption",
            "hide_grants", "configuration_id", "radio_role", "group_match", "source_identity_key",
            "target_identity_key", "source_id", "target_id", "target_kind", "frequency_hz", "lcn",
            "timeslot"})
        {
            assertTrue(api.contains(key), () -> "Missing Activity API parameter " + key);
        }
        assertTrue(activity.contains("scopeParameters = activityScopeParameters(scopeParameters)"));
        assertTrue(activity.contains("...activityApiFilterParameters(filters, Date.now())"));
        assertFalse(api.contains("activity_range"));
        assertFalse(routes.contains("\n    from_ms:"));
    }

    @Test
    void presentsOneContextAwareAccessibleToolbarOnRetainedLogsOnly() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String toolbar = function(source, "function activityFilterToolbar(context, initialFilters)");
        String chooser = function(source, "function activityIdentityChooser(context, options = {})");
        String capabilities = function(source, "function activityContextCapabilities(context)");
        String columns = function(source, "function activityColumns(context, filters)");
        String activity = function(source, "async function renderActivity(scopeParameters, title = 'Activity')");

        assertTrue(activity.contains("activityFilterToolbar(activityContext, filters)"));
        assertTrue(toolbar.contains("aria-label', 'Filter retained activity'"));
        assertTrue(toolbar.contains("'Apply filters'"));
        assertTrue(toolbar.contains("'Clear filters'"));
        assertTrue(toolbar.contains("'Advanced filters'"));
        assertTrue(toolbar.contains("'Event type'"));
        assertTrue(toolbar.contains("'Include grants'"));
        assertTrue(toolbar.contains("action === 'GRANT'"));
        assertTrue(columns.contains("label: 'Event Type'"));
        assertTrue(capabilities.contains("groupMatch: kind === 'talkgroup'"));
        assertTrue(capabilities.contains("rawIdentities: digitalConventional"));
        assertTrue(capabilities.contains("encryption: !analog"));
        assertTrue(chooser.contains("setAttribute('role', 'combobox')"));
        assertTrue(chooser.contains("setAttribute('aria-busy', 'true')"));
        assertTrue(chooser.contains("document.activeElement === input && values.length > 0"));
        assertTrue(chooser.contains("'No matching identities. Try another ID.'"));
        assertTrue(chooser.contains("new AbortController()"));
        assertTrue(chooser.contains("Choose an identity from the suggestions."));
        assertFalse(chooser.contains("return { key: '', id }"));
    }

    @Test
    void keepsPagingAndRollingRefreshInsideTheSelectedFilters() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String activity = function(source, "async function renderActivity(scopeParameters, title = 'Activity')");
        String api = function(source, "function activityApiFilterParameters(filters, now = Date.now())");
        String routes = function(source, "function activityFilterRouteOverrides(filters)");

        assertTrue(routes.contains("before_id: null"));
        assertTrue(activity.contains("filters.range !== 'custom'"));
        assertTrue(activity.contains("currentHref({ before_id: null })"));
        assertTrue(activity.contains("currentHref({ before_id: page?.next_before_id })"));
        assertTrue(activity.contains("...activityApiFilterParameters(filters, Date.now())"));
        assertTrue(api.contains("now - range.milliseconds"));
        assertTrue(api.contains("result.from_ms = filters.fromMs"));
        assertTrue(api.contains("result.to_ms = filters.toMs"));
    }

    @Test
    void adaptsActivityContextsAndTableLayoutsWithoutLeakingHiddenFilters() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String filters = function(source,
            "function activityRouteFilters(parameters, context, now = Date.now())");
        String columns = function(source, "function activityColumnsForContext(context, filters)");
        String tableType = function(source, "function activityTableType(context)");

        for(String kind: new String[]{"system", "saved-system", "talkgroup", "patch-group", "radio",
            "trunked-channel", "conventional-digital", "conventional-analog"})
        {
            assertTrue(source.contains("kind: '" + kind + "'") || source.contains("'" + kind + "'"),
                () -> "Missing Activity context " + kind);
        }
        assertTrue(filters.contains("const capabilities = activityContextCapabilities(context)"));
        assertTrue(filters.contains("capabilities.rawIdentities ?"));
        assertTrue(filters.contains("if (!allowSourceIdentity) sourceIdentityKey = ''"));
        assertTrue(filters.contains("if (!allowTargetIdentity) targetIdentityKey = ''"));
        assertTrue(columns.contains("context?.kind === 'conventional-analog'"));
        assertTrue(tableType.contains("context?.kind === 'conventional-analog'"));
        assertTrue(tableType.contains("'activity-conventional-analog-v1' : 'activity'"));
    }

    @Test
    void turnsActivityValuesIntoContextAwareFilterAndNavigationActions() throws Exception
    {
        String source = readText(APP_JAVASCRIPT);
        String mapper = function(source, "function activityCellFilterPatch(context, row, columnId)");
        String routes = function(source,
            "function activityCellFilterRouteOverrides(filters, context, row, columnId)");
        String value = function(source,
            "function activityCellValue(displayValue, row, columnId, context, filters)");
        String tooltip = function(source,
            "function showActivityCellActionTooltip(trigger, dimension, text, filterTarget, navigation, options = {})");
        String columns = function(source, "function activityColumns(context, filters)");
        String activity = function(source, "async function renderActivity(scopeParameters, title = 'Activity')");

        assertTrue(mapper.contains("sourceColumns"));
        assertTrue(mapper.contains("targetColumns"));
        assertTrue(mapper.contains("capabilities.rawIdentities"));
        assertTrue(mapper.contains("context?.radioIdentityKey"));
        assertTrue(mapper.contains("radioRole: 'source'"));
        assertTrue(mapper.contains("radioRole: 'target'"));
        assertTrue(routes.contains("activityFilterRouteOverrides({ ...filters, ...patch })"));
        assertTrue(value.contains("currentHref(filterOverrides)"));
        assertTrue(value.contains("aria-expanded"));
        assertTrue(value.contains("event.metaKey || event.ctrlKey || event.shiftKey || event.altKey"));
        assertTrue(value.contains("showActivityCellActionTooltip("));
        assertFalse(value.contains("openReadOnlyModal"));
        assertFalse(value.contains("aria-haspopup', 'dialog'"));
        assertTrue(tooltip.contains("activity-cell-action-tooltip"));
        assertTrue(tooltip.contains("ui-icon-action-tooltip"));
        assertTrue(tooltip.contains("ui-icon-button"));
        assertTrue(tooltip.contains("aria-controls"));
        assertTrue(tooltip.contains("setAttribute('popover', 'auto')"));
        assertTrue(tooltip.contains("setAttribute('role', 'group')"));
        assertTrue(tooltip.contains("Filter activity"));
        assertFalse(tooltip.contains("openReadOnlyModal"));
        assertTrue(columns.contains("activityCellValue"));
        assertTrue(activity.contains("activityColumnsForContext(activityContext, filters)"));
    }

    @Test
    void givesTheToolbarResponsiveFeatureOwnedGeometry() throws Exception
    {
        String css = StatsWebStylesheetTestSupport.readAll();
        assertTrue(css.contains(".activity-filter-toolbar {"));
        assertTrue(css.contains(".activity-filter-primary,"));
        assertTrue(css.contains(".activity-filter-grid,"));
        assertTrue(css.contains(".activity-filter-grid {\n  align-items: start;"));
        assertTrue(css.contains(".activity-cell-action-link {"));
        assertTrue(css.contains(".activity-filter-error[hidden]"));
        assertTrue(css.contains("@media (max-width: 720px)"));
    }

    private static String function(String source, String signature)
    {
        int start = source.indexOf(signature);
        if(start < 0)
        {
            throw new IllegalArgumentException("Missing " + signature);
        }
        int brace = source.indexOf('{', start + signature.length());
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
            if(!dual && !template && current == '\'')
            {
                single = !single;
                continue;
            }
            if(!single && !template && current == '"')
            {
                dual = !dual;
                continue;
            }
            if(!single && !dual && current == '`')
            {
                template = !template;
                continue;
            }
            if(single || dual || template)
            {
                continue;
            }
            if(current == '{')
            {
                depth++;
            }
            else if(current == '}' && --depth == 0)
            {
                return source.substring(start, index + 1);
            }
        }
        throw new IllegalArgumentException("Unterminated " + signature);
    }

    private static String readText(Path path) throws Exception
    {
        assertTrue(Files.isRegularFile(path), () -> "Missing " + path.toAbsolutePath());
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
    }
}
