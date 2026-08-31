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

/** Protects the bounded, administrator-only, list-focused web Alias Editor. */
class StatsWebAliasCatalogUiContractTest
{
    private static final Path APP_JAVASCRIPT = Path.of("stats-web", "assets", "app.js");
    private static final Path APP_CSS = Path.of("stats-web", "assets", "app.css");
    private static final Path INDEX_HTML = Path.of("stats-web", "index.html");

    @Test
    void requiresAListBeforeLoadingBoundedAliases() throws Exception
    {
        String source = source();
        String renderer = function(source, "async function renderAliases()");

        assertTrue(readText(INDEX_HTML).contains("data-view=\"aliases\" href=\"/?view=aliases\""));
        assertTrue(source.contains("aliases: renderAliases"));
        assertTrue(renderer.contains("apiPage('/api/v1/alias-lists')"));
        assertTrue(renderer.contains("if (!selectedList)"));
        assertTrue(renderer.indexOf("if (!selectedList)") <
            renderer.indexOf("apiPage('/api/v1/aliases'"));
        assertTrue(function(source, "function pageParameters(extra = {})").contains("limit: 100"));
        assertFalse(renderer.contains("All alias lists"));
        assertTrue(function(source, "function aliasListRail(lists, selectedList)")
            .contains("href('aliases', { list: id"));
        assertTrue(renderer.contains("view === 'configure' ? { include_activity: false }"));
    }

    @Test
    void usesTheCompleteAdministratorListCatalogAndLabelsBoundedOptionSuggestions() throws Exception
    {
        String source = source();
        String merge = function(source, "function mergedAliasLists(publicRows, adminRows = [])");
        String limits = function(source, "function aliasOptionLimit(options, name)");

        assertTrue(merge.contains("return (adminRows || []).map"));
        assertFalse(merge.contains("return (publicRows || []).map"));
        assertTrue(merge.contains("publicRow?.alias_count"));
        assertTrue(merge.contains("publicRow?.assigned_channel_count"));
        assertFalse(merge.contains("...publicRow"));
        assertTrue(limits.contains("`${name}_total`"));
        assertTrue(limits.contains("`${name}_truncated`"));
        assertTrue(source.contains("aliasOptionLimitNotice(options, 'group_names'"));
        assertTrue(source.contains("aliasOptionLimitNotice(options, 'icon_names'"));
        assertTrue(source.contains("aliasOptionLimitNotice(options, 'stream_names'"));
    }

    @Test
    void labelsTheSharedAmNbfmAliasFamilyWithoutChangingItsApiValue() throws Exception
    {
        String source = source();
        String label = function(source, "function aliasListFamilyLabel(value)");
        String create = function(source, "function openAliasListCreateModal()");

        assertTrue(source.contains("NBFM: 'Conventional Analog (AM/NBFM)'"));
        assertTrue(label.contains("ALIAS_LIST_FAMILY_LABELS[family]"));
        assertTrue(create.contains("value: value.toLowerCase(), label"));
        assertTrue(function(source, "function aliasListFamily(row)").contains("toUpperCase()"));
        assertTrue(source.contains("['P25', 'DMR', 'NXDN', 'NBFM']"));
    }

    @Test
    void combinesCallsAndSignalingInOneActivityView() throws Exception
    {
        String source = source();
        String tabs = function(source, "function aliasEditorViewTabs(selectedList)");
        String view = function(source, "function aliasEditorView(selectedList)");
        String columns = function(source, "function aliasEditorColumns(view, rows, onSelectionChange)");
        String activity = function(source, "function aliasActivityColumns()");
        String configuration = function(source, "function aliasCustomConfigurationColumns()");
        String base = function(source, "function aliasEditorBaseColumns(rows, onSelectionChange)");

        for(String label: new String[]{"Configure", "Activity", "Custom"})
        {
            assertTrue(tabs.contains("'" + label + "'"), () -> "Missing editor view " + label);
        }
        assertFalse(tabs.contains("'Call Use'"));
        assertFalse(tabs.contains("'System Evidence'"));
        assertTrue(view.contains("['calls', 'evidence']"));
        assertTrue(view.contains("'activity' : route.get('aliasTab')"));
        assertTrue(columns.contains("view === 'activity'"));
        assertFalse(columns.contains("view === 'calls'"));
        assertFalse(columns.contains("view === 'evidence'"));
        assertTrue(columns.contains("view === 'custom'"));
        assertFalse(source.contains("function aliasColumnChooser("));
        assertTrue(source.contains("defaultHiddenColumns: view === 'custom' ? definitions"));
        assertTrue(source.contains("exportCsvLink('aliases', exportContext)"));
        assertTrue(base.contains("id: 'description'"));
        for(String field: new String[]{"logical_call_count", "signaling_observation_count", "last_evidence_ms"})
        {
            assertTrue(activity.contains(field), () -> "Missing simplified activity field " + field);
        }
        for(String detail: new String[]{"grant_observation_count", "relationship_count", "metrics_state"})
        {
            assertFalse(activity.contains(detail), () -> "Detailed metric leaked into Activity table " + detail);
        }
        for(String facet: new String[]{"alias-id", "alias-list", "family", "alias", "description", "group",
            "color", "icon", "matcher", "matcher-type", "identity-type", "protocol", "protocol-variant",
            "identifier", "exact", "ranged", "value", "minimum", "maximum", "text-value",
            "numeric-value", "tone-sequence", "scan-lists", "record", "broadcast-channels",
            "stream-as-talkgroup", "behavior", "overlap"})
        {
            assertTrue(configuration.contains("'" + facet + "'"), () -> "Missing custom Alias facet " + facet);
        }
    }

    @Test
    void exposesAdminMutationsOnlyThroughTheAliasCapability() throws Exception
    {
        String source = source();
        String renderer = function(source, "async function renderAliases()");
        String editor = function(source, "async function openAliasEditorModal(mode = 'create', id = null, prefill = null)");

        assertTrue(source.contains("ADMIN_ALIASES: 'admin-aliases'"));
        assertTrue(function(source, "function aliasAdminAllowed()")
            .contains("ACCESS_CAPABILITIES.ADMIN_ALIASES"));
        assertTrue(renderer.contains("if (!aliasAdminAllowed())"));
        assertTrue(renderer.contains("requestJson('/api/v1/admin/alias-lists'"));
        assertTrue(renderer.contains("admin: true"));
        assertTrue(editor.contains("/api/v1/admin/aliases/options?alias_list_id="));
        assertTrue(editor.contains("method: 'PUT', body: { revision, alias: payload }"));
        assertTrue(editor.contains("method: 'POST', body: { revision, alias: payload }"));
        assertFalse(editor.contains("requestedScanLists"));
        assertFalse(editor.contains("its scan-list membership could not be saved"));
        assertTrue(source.contains("method: 'DELETE', body: { revision }"));
        assertTrue(source.contains("/api/v1/admin/alias-lists/${id}/delete-impact"));
        assertTrue(source.contains("code === 'stale_revision'"));
    }

    @Test
    void supportsCompleteMatcherAndFocusedModalEditing() throws Exception
    {
        String source = source();
        String fields = function(source, "function aliasMatcherFields(host, descriptor, matcher, options)");
        String payload = function(source, "function aliasMatcherPayload(form, descriptor)");
        String editorPayload = function(source, "function aliasEditorPayload(form, options)");
        String tabs = function(source, "function aliasEditorModalTabs(panels, initial = 'basics')");

        for(String label: new String[]{"Basics", "Identifier", "Call Handling", "Activity"})
        {
            assertTrue(tabs.contains("'" + label + "'"), () -> "Missing modal tab " + label);
        }
        for(String field: new String[]{"value", "minimum", "maximum", "status", "code", "esn", "tones"})
        {
            assertTrue(fields.contains("field === '" + field + "'"), () -> "Missing matcher field " + field);
        }
        assertTrue(fields.contains("duration.min = '1'"));
        assertTrue(fields.contains("duration.max = '50'"));
        assertTrue(fields.contains("alias-tone-up"));
        assertTrue(fields.contains("alias-tone-down"));
        assertTrue(fields.contains("reorderedAliasToneRows(rows, index, direction)"));
        assertTrue(fields.contains("aria-label', 'Move tone up"));
        assertTrue(fields.contains("aria-label', 'Move tone down"));
        assertTrue(fields.contains("button.disabled ? fallback : button"));
        assertTrue(source.contains("value: aliasMatcherKey(entry), label: entry.label"));
        assertTrue(source.contains("source.matcher?.type, source.matcher?.protocol"));
        assertTrue(payload.contains("selector.dataset.originalProtocol"));
        assertTrue(editorPayload.contains("descriptor.minimum"));
        assertTrue(editorPayload.contains("descriptor.maximum"));
        assertTrue(source.contains("options.dcs_codes?.[0] || 'n023'"));
        assertTrue(source.contains("Current (outside suggestion limit)"));
        assertTrue(source.contains("aliasCloneOptionValue(source.icon_name"));
        assertTrue(source.contains("aliasStreamOptionSelected(selectedStreams.has(streamName)"));
        assertTrue(source.contains("Missing: ${streamName}"));
        assertTrue(source.contains("error.code = failure?.code"));
        assertTrue(source.contains("color: aliasEditorColorValue(form.elements.color)"));
        assertTrue(source.contains("color.dataset.originalColor"));
        assertTrue(source.contains("value: 'RESET', label: 'Reset to default'"));
        assertTrue(source.contains("Discard your unsaved changes?"));
        assertFalse(source.contains("Discard your unsaved alias changes?"));
        assertTrue(function(source, "async function render()").contains("if (!closeReadOnlyModal()) return"));
        assertTrue(source.contains("if (!closeReadOnlyModal()) {\n    window.history.pushState"));
    }

    @Test
    void keepsActivityBreakdownFocusedAndRetainsDetailsInTheAliasDialog() throws Exception
    {
        String source = source();
        String columns = function(source, "function aliasEditorScopeBreakdownColumns()");
        String activity = function(source, "function aliasActivityContent(response)");

        assertTrue(columns.contains("availableValue(row.scope_label)"));
        assertTrue(columns.contains("availableValue(row.topology)"));
        assertTrue(columns.contains("aliasMetricValue(row, 'logical_call_count')"));
        assertTrue(columns.contains("aliasMetricValue(row, 'signaling_observation_count')"));
        assertTrue(columns.contains("Number(row.last_evidence_ms || 0)"));
        assertFalse(source.contains("function aliasScopeMetricSummary("));
        for(String repeated: new String[]{"row.protocol", "row.system_name", "row.site_name", "row.metrics_state",
            "first_evidence_ms"})
        {
            assertFalse(columns.contains(repeated), () -> "Repeated scope field remains " + repeated);
        }
        assertTrue(activity.contains("aliasEditorScopeBreakdownColumns()"));
        assertTrue(activity.contains("grant_observation_count"));
        assertTrue(activity.contains("relationship_count"));
        assertTrue(activity.contains("retained call and signaling activity"));
        assertTrue(activity.contains("included in the Signaling total"));
    }

    @Test
    void keepsBulkSelectionExplicitBoundedAndTriState() throws Exception
    {
        String source = source();
        String bulk = function(source, "function openAliasBulkModal(kind)");
        String columns = function(source, "function aliasEditorBaseColumns(rows, onSelectionChange)");
        String selectedIds = function(source,
            "function validatedAliasSelectionIds(selection, maximum = ALIAS_BULK_SELECTION_LIMIT)");

        assertTrue(bulk.contains("aliasMutationSelectionIds()"));
        assertTrue(bulk.contains("selected aliases"));
        assertFalse(bulk.contains("on this page"));
        assertTrue(selectedIds.contains("ids.length > maximum"));
        assertTrue(selectedIds.contains("Select no more than ${maximum} aliases"));
        assertFalse(source.contains("slice(0, 500)"));
        for(String operation: new String[]{"group_operation", "recordable",
            "stream_operation", "broadcast_channels", "alias_list_id", "icon_name", "delete",
            "/api/v1/admin/scan-lists/", "alias_ids"})
        {
            assertTrue(bulk.contains(operation), () -> "Missing bulk contract " + operation);
        }
        assertTrue(source.contains("['move', 'Move'], ['group', 'Group'], ['scan-lists', 'Scan Lists']"));
        assertTrue(source.contains("['stream', 'Stream'], ['appearance', 'Appearance'], ['delete', 'Delete']"));
        String binary = function(source,
            "function aliasBulkBinaryOperation(ariaLabel, positiveDescription, negativeDescription)");
        assertTrue(binary.contains("let selected = 'add'"));
        assertTrue(binary.contains("['add', '+', positiveDescription]"));
        assertTrue(binary.contains("['remove', '−', negativeDescription]"));
        assertTrue(bulk.contains("aliasBulkBinaryOperation('Membership change', 'Add selected aliases'"));
        assertTrue(bulk.contains("aliasBulkBinaryOperation('Recording change', 'Enable recording', 'Disable recording')"));
        assertTrue(bulk.contains("aliasBulkBinaryOperation('Group change', 'Assign group', 'Clear group')"));
        assertFalse(bulk.contains("aliasSelect('membershipOperation'"));
        assertFalse(bulk.contains("aliasSelect('recordOperation'"));
        assertFalse(bulk.contains("aliasSelect('groupOperation'"));
        assertTrue(columns.contains("event.shiftKey"));
        assertTrue(source.contains("event.metaKey || event.ctrlKey"));
    }

    @Test
    void selectsEverySupportedMatchAcrossPagesWithoutChangingFilterMeaning() throws Exception
    {
        String source = source();
        String renderer = function(source, "async function renderAliases()");
        String members = function(source,
            "async function renderScanListMembers(main, listResponse, scanListCatalog, scanList, renderContext)");
        String selectAll = function(source,
            "async function selectAllMatchingAliases(filters, scope, button, onSelectionChange)");
        String complete = function(source,
            "function completeAliasSelection(response, maximum = ALIAS_BULK_SELECTION_LIMIT)");
        String extend = function(source,
            "function extendedAliasSelection(selection, additions, maximum = ALIAS_BULK_SELECTION_LIMIT)");
        String scope = function(source, "function aliasSelectionScopeKey(kind, filters = {})");
        String applicationRender = function(source, "async function render()");
        String inactive = function(source, "function clearInactiveAliasSelection(activeTable)");
        String leave = function(source, "function clearAliasSelectionOutsideEditor(view)");

        assertTrue(source.contains("const ALIAS_BULK_SELECTION_LIMIT = 10_000"));
        assertTrue(selectAll.contains("api('/api/v1/aliases/ids', filters"));
        assertFalse(selectAll.contains("offset"));
        assertFalse(selectAll.contains("sort"));
        assertTrue(selectAll.contains("request !== aliasEditorSelectionRequest"));
        assertTrue(complete.contains("response.alias_ids"));
        assertTrue(complete.contains("Number(response.count) !== response.alias_ids.length"));
        assertTrue(complete.contains("new Set(ids).size !== ids.length"));
        assertTrue(extend.contains("const next = new Set(selection)"));
        assertTrue(extend.contains("The previous selection was kept"));
        assertTrue(scope.contains("'list', 'type', 'matcher', 'group', 'scan_list_id'"));
        assertTrue(scope.contains("'record', 'stream', 'q', 'evidence', 'use'"));
        assertFalse(scope.contains("offset"));
        assertFalse(scope.contains("sort"));
        assertTrue(renderer.contains("aliasSelectionScopeKey('alias-list', selectionFilters)"));
        assertTrue(members.contains("aliasSelectionScopeKey('scan-list-members', selectionFilters)"));
        assertTrue(renderer.contains("synchronizeAliasEditorSelectionScope(selectionScope)"));
        assertTrue(members.contains("synchronizeAliasEditorSelectionScope(selectionScope)"));
        assertTrue(renderer.contains("aliasEditorSelection = extendedAliasSelection(aliasEditorSelection"));
        assertTrue(members.contains("aliasEditorSelection = extendedAliasSelection(aliasEditorSelection"));
        assertFalse(renderer.contains("visibleIds"));
        assertFalse(members.contains("visibleIds"));
        assertTrue(applicationRender.contains("clearAliasSelectionOutsideEditor(effectiveView)"));
        assertTrue(applicationRender.contains("clearInactiveAliasSelection(false)"));
        assertTrue(renderer.contains("clearInactiveAliasSelection(aliasAdminAllowed() && requestedTable)"));
        assertTrue(renderer.contains("if (requestedScanListId) {\n    clearInactiveAliasSelection(false);"));
        assertTrue(renderer.contains("if (!selectedList) {\n    clearInactiveAliasSelection(false);"));
        assertTrue(inactive.contains("resetAliasEditorSelection()"));
        assertTrue(leave.contains("clearInactiveAliasSelection(view === 'aliases')"));
        int firstButton = source.indexOf("'Select All Matching'");
        assertTrue(firstButton >= 0 && source.indexOf("'Select All Matching'", firstButton + 1) > firstButton);
    }

    @Test
    void opensAllScanListMembersAndRemovesOnlySelectedMemberships() throws Exception
    {
        String source = source();
        String actions = function(source, "function adminScanListActions(scanList, revision)");
        String count = function(source, "function adminScanListMemberCount(scanList)");
        String renderer = function(source, "async function renderAliases()");
        String members = function(source,
            "async function renderScanListMembers(main, listResponse, scanListCatalog, scanList, renderContext)");
        String columns = function(source, "function scanListMemberColumns(rows, onSelectionChange)");
        String bulk = function(source, "function scanListMemberBulkBar(scanList, onClear)");
        String remove = function(source, "function openScanListMemberRemoveModal(scanList)");

        assertTrue(actions.contains("'View Aliases'"));
        assertTrue(actions.contains("scanListId: scanList.id"));
        assertTrue(count.contains("scanListId: scanList.id"));
        assertTrue(renderer.contains("requestJson('/api/v1/admin/scan-lists'"));
        assertTrue(renderer.contains("await renderScanListMembers"));
        assertTrue(members.contains("apiPage('/api/v1/aliases'"));
        assertTrue(members.contains("!renderIsCurrent(renderContext) || !main.isConnected"));
        assertTrue(members.contains("scan_list_id: scanList.id"));
        assertTrue(members.contains("scanListMemberColumns(rows, updateSelection)"));
        assertTrue(members.contains("'No aliases belong to this scan list'"));
        assertTrue(members.contains("scanListMemberBulkBar(scanList"));
        assertTrue(members.contains("scan_list_id: scanList.id"));
        assertTrue(columns.contains("id: 'alias-list'"));
        assertTrue(columns.contains("id: 'family'"));
        assertTrue(bulk.contains("`Remove from ${scanList.name}`"));
        assertTrue(remove.contains("aliasMutationSelectionIds()"));
        assertTrue(remove.contains("/api/v1/admin/scan-lists/${scanList.id}/members"));
        assertTrue(remove.contains("operation: 'remove'"));
        assertTrue(remove.contains("alias_ids: ids"));
        assertTrue(remove.contains("aliases and their other scan-list memberships will be preserved"));
        String fullSet = function(source,
            "function openFullScanListMembershipModal(scanList, operation)");
        String fullSetRequest = function(source,
            "function fullScanListMembershipRequest(revision, operation, aliasListId = null)");
        assertTrue(members.contains("'Add All from Alias List'"));
        assertTrue(members.contains("'Remove All Members'"));
        assertTrue(fullSet.contains("fullScanListMembershipRequest"));
        assertTrue(fullSet.contains("Add All Aliases"));
        assertTrue(fullSetRequest.contains("alias_scope"));
        assertTrue(fullSetRequest.contains("alias_list_id: id"));
        assertFalse(fullSetRequest.contains("alias_ids"));
    }

    @Test
    void drillsIntoIdentifierConflictsWithoutLoadingEveryAlias() throws Exception
    {
        String source = source();
        String button = function(source,
            "function aliasConflictButton(row, label = 'Conflict', detailsHost = null)");
        String modal = function(source, "async function openAliasConflictModal(aliasId, aliasName = '')");
        String detail = function(source, "function aliasConflictDetail(response, aliasId, aliasName = '')");
        String columns = function(source, "function aliasEditorColumns(view, rows, onSelectionChange)");
        String editor = function(source,
            "async function openAliasEditorModal(mode = 'create', id = null, prefill = null)");

        assertTrue(button.contains("openAliasConflictModal(id, row.name)"));
        assertTrue(button.contains("detailsHost.replaceChildren(aliasConflictDetail("));
        assertTrue(button.contains("event.stopPropagation()"));
        assertTrue(modal.contains("`/api/v1/admin/aliases/${id}/conflicts`"));
        assertTrue(modal.contains("aliasConflictDetail(response, id, aliasName)"));
        assertTrue(detail.contains("response?.conflicts_total"));
        assertTrue(detail.contains("response?.conflicts_truncated"));
        assertTrue(detail.contains("conflicts.map(aliasConflictSummary)"));
        assertTrue(columns.contains("render: aliasConflictButton"));
        assertTrue(editor.contains("const conflictDetails = node('div', 'alias-conflict-inline')"));
        assertTrue(editor.contains("'Show conflicts', conflictDetails"));
        String summary = function(source, "function aliasConflictSummary(row)");
        assertTrue(summary.contains("aliasTab: 'configure', alias: id"));
        assertTrue(summary.contains(
            "openAliasEditorModal('edit', id, { alias_list_id: Number(row?.alias_list_id) })"));
    }

    @Test
    void retainsExactServerFiltersAndUsesSimpleActivitySemantics() throws Exception
    {
        String source = source();
        String renderer = function(source, "async function renderAliases()");
        String filters = function(source, "function aliasEditorFilterToolbar(listResponse, options = null)");

        for(String parameter: new String[]{"group", "record", "stream", "evidence", "use"})
        {
            assertTrue(renderer.contains(parameter + ": route.get('" + parameter + "')"),
                () -> "Missing server filter " + parameter);
        }
        assertTrue(renderer.contains("scan_list_id: route.get('scanListId')"));
        assertTrue(filters.contains("selectFilter('Scan list', 'scanListId'"));
        assertTrue(renderer.contains("last_activity_before: route.get('lastActivityBefore')"));
        assertTrue(renderer.contains("last_activity_after: route.get('lastActivityAfter')"));
        assertTrue(filters.contains("selectFilter('Calls', 'use'"));
        assertTrue(filters.contains("'No calls observed'"));
        assertFalse(filters.contains("selectFilter('Evidence'"));
        assertFalse(filters.contains("'Covered · no evidence'"));
        assertTrue(filters.contains("hidden.value = String(new Date(control.value).getTime())"));
        assertTrue(filters.contains("'lastActivityAfter', 'lastActivityBefore'"));
        assertTrue(source.contains("A call can also have signaling"));
        assertTrue(source.contains("An em dash means unavailable; 0 means monitored with none observed"));
    }

    @Test
    void exposesUnmatchedPolicyAndBoundedObservedTalkgroupDiscovery() throws Exception
    {
        String source = source();
        String tabs = function(source, "function aliasEditorViewTabs(selectedList)");
        String view = function(source, "function aliasEditorView(selectedList)");
        String renderer = function(source, "async function renderAliases()");
        String discoverySupport = function(source, "function observedTalkgroupDiscoverySupported(selectedList)");
        String unmatchedSupport = function(source, "function unmatchedTalkgroupsSupported(selectedList)");
        String policy = function(source, "function openUnmatchedTalkgroupPolicyModal(selectedList)");
        String checkbox = function(source, "function aliasCheckOption(labelText, control)");
        String observed = function(source, "function renderObservedTalkgroups(main, page, selectedList)");
        String prefill = function(source, "function observedTalkgroupPrefill(row, selectedList)");
        String identity = function(source, "function observedTalkgroupIdentity(row)");
        String key = function(source, "function observedTalkgroupKey(row)");
        String focusKey = function(source, "function observedTalkgroupFocusKey(row)");
        String homeIdentity = function(source, "function observedP25HomeIdentity(row)");
        String time = function(source, "function observedTalkgroupTime(row, value)");
        String create = function(source, "function observedTalkgroupCreateButton(row, selectedList)");
        String detail = function(source, "function observedTalkgroupDetail(row, selectedList)");
        String editor = function(source,
            "async function openAliasEditorModal(mode = 'create', id = null, prefill = null)");

        assertFalse(source.contains("alias_match_kind"));
        assertTrue(tabs.contains("'Discover'"));
        assertTrue(renderer.contains("`/api/v1/alias-lists/${aliasListId(selectedList)}/observed-talkgroups`"));
        assertTrue(renderer.contains("include_exact: false"));
        assertTrue(renderer.contains("options?.alias_list && options?.revision !== undefined"));
        assertTrue(renderer.contains("aliasEditorContext.selectedList = selectedList"));
        assertTrue(source.contains("aliasTab: 'discover'"));
        assertTrue(tabs.contains("observedTalkgroupDiscoverySupported(selectedList)"));
        assertTrue(view.contains("observedTalkgroupDiscoverySupported(selectedList)"));
        assertTrue(renderer.contains("aliasEditorView(selectedList)"));
        assertTrue(discoverySupport.contains("['P25', 'DMR', 'NXDN']"));
        assertFalse(discoverySupport.contains("'NBFM'"));
        assertTrue(unmatchedSupport.contains("['P25', 'DMR', 'NXDN', 'NBFM']"));
        assertTrue(checkbox.contains("'alias-check-option'"));
        assertTrue(policy.contains("aliasCheckOption('Record calls', record)"));
        assertTrue(editor.contains("aliasCheckOption('Record calls', record)"));
        assertFalse(policy.contains("aliasFormField('Record calls', record)"));
        assertTrue(observed.contains("observedTalkgroupMatchKind(row) !== 'exact'"));
        assertTrue(observed.contains("defaultSort: 'last_seen'"));
        assertTrue(observed.contains("pager({ ...page, rows })"));

        assertFalse(source.contains("function unmatchedTalkgroupPolicy"));
        assertTrue(policy.contains("selectedList?.unmatched_talkgroup_policy"));
        assertTrue(prefill.contains("selectedList?.unmatched_talkgroup_policy"));
        assertTrue(policy.contains("/unmatched-talkgroups"));
        for(String field: new String[]{"recordable", "broadcast_channels", "scan_list_ids"})
        {
            assertTrue(policy.contains(field), () -> "Missing unmatched policy field " + field);
        }
        assertTrue(policy.contains("aliasScanListChoices(options, policy.scan_list_ids || [])"));
        assertTrue(policy.contains("selectedAliasScanListIds(form)"));
        assertTrue(policy.contains("'Scan List'"));
        assertTrue(policy.contains("'Save Alias List Defaults'"));
        assertTrue(policy.contains("'Recording'"));
        assertTrue(policy.contains("'Streaming'"));
        assertTrue(policy.contains("including sensitive traffic"));
        assertTrue(prefill.contains("scan_list_ids: [...(policy.scan_list_ids || [])]"));
        assertTrue(renderer.contains("'Alias List Defaults'"));
        assertFalse(policy.contains("listen_enabled"));
        assertFalse(policy.contains("priority"));

        assertTrue(prefill.contains("name: ''"));
        assertTrue(prefill.contains("type: 'talkgroup'"));
        assertFalse(source.contains("P25_FULLY_QUALIFIED_TALKGROUP"));
        assertTrue(prefill.contains("!observedTalkgroupPromotionSupported(row)"));
        assertTrue(prefill.contains("['ordinary', 'stable_fully_qualified'].includes(observedP25IdentityState(row))"));
        assertTrue(prefill.contains("topology || '').toUpperCase() === 'CONVENTIONAL'"));
        for(String field: new String[]{"wacn", "system_id", "talkgroup_id"})
        {
            assertTrue(source.contains("qualification?.home?." + field),
                () -> "Missing qualifier-safe discovery field " + field);
            assertTrue(key.contains("home." + field), () -> "Observed row key omits qualifier " + field);
        }
        assertTrue(key.contains("row?.topology"));
        assertTrue(key.contains("observedTalkgroupProtocol(row)"));
        assertTrue(key.contains("scope-key:"));
        assertTrue(key.contains("context-key:"));
        assertTrue(homeIdentity.contains("talkgroup > 0"));
        assertTrue(homeIdentity.contains("talkgroup < 0xFFFF"));
        assertTrue(time.contains("wrapper.append(rendered, ' (hour beginning)')"));
        assertFalse(time.contains("`${rendered}"));
        assertTrue(focusKey.contains("encodeURIComponent(observedTalkgroupKey(row))"));
        assertTrue(prefill.contains("observedTalkgroupFocusKey(row)"));
        assertTrue(create.contains("dataset.observedKey = observedTalkgroupFocusKey(row)"));
        assertTrue(identity.contains("identityNumber(row, row.talkgroup_id)"));
        assertTrue(detail.contains("'Decoded Home'"));
        assertTrue(detail.contains("'Local Talkgroup'"));
        assertTrue(detail.contains("other recognized signaling actions"));
        assertTrue(create.contains("!observedTalkgroupPromotionSupported(row)"));
        assertTrue(create.contains("'Review only'"));
        assertTrue(create.contains("observedTalkgroupPromotionReason(row)"));
        assertTrue(detail.contains("observedTalkgroupPromotionSupported(row)"));
        assertTrue(prefill.contains("stream_as_talkgroup: null"));
        assertFalse(prefill.contains("copy_actions_from_alias_id"));
        assertFalse(editor.contains("rangeActionsPromise"));
        assertTrue(editor.contains("aliasStreamOptionSelected(selectedStreams.has(streamName)"));
    }

    @Test
    void providesResponsiveThemeAwareRailTableBulkBarAndModal() throws Exception
    {
        String css = readText(APP_CSS);
        for(String selector: new String[]{".alias-editor-workspace", ".alias-list-rail", ".alias-list-mobile",
            ".alias-list-summary", ".alias-editor-table-host", ".alias-bulk-bar", ".alias-editor-modal",
            ".alias-modal-tabs", ".alias-editor-grid", ".alias-stream-options", ".alias-tone-row",
            ".alias-tone-actions", ".alias-conflict-list",
            ".alias-scan-list-option", ".observed-talkgroup-table-host", ".observed-talkgroup-detail",
            ".alias-policy-modal"})
        {
            assertTrue(css.contains(selector), () -> "Missing Alias Editor style " + selector);
        }
        assertTrue(css.contains(":root[data-theme=\"dark\"] .alias-editor-table-host"));
        assertTrue(css.contains(":not(.auth-action):not(.auth-session-button):not(.table-sort-control)"));
        assertTrue(function(source(), "function aliasEditorModalTabs(panels, initial = 'basics')")
            .contains("node('button', 'secondary', label)"));
        assertTrue(function(source(), "function aliasBulkBinaryOperation")
            .contains("node('button', 'secondary', label)"));
        assertTrue(css.contains(":root[data-theme=\"dark\"] .alias-membership-operation button[aria-pressed=\"true\"]"));
        assertTrue(css.contains("@media (max-width: 900px)"));
        assertTrue(source().contains("alias-list-mobile-create"));
        assertTrue(css.contains("@media (max-width: 560px)"));
        assertTrue(css.contains("grid-template-columns: 1fr;"));
        assertTrue(css.contains("width: 100vw;"));
        assertTrue(css.contains("height: 100dvh;"));
    }

    private static String source() throws Exception
    {
        assertTrue(Files.isRegularFile(APP_JAVASCRIPT), () -> "Missing " + APP_JAVASCRIPT.toAbsolutePath());
        return readText(APP_JAVASCRIPT);
    }

    private static String readText(Path path) throws Exception
    {
        return Files.readString(path).replace("\r\n", "\n").replace('\r', '\n');
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
