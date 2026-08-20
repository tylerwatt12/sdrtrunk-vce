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

/** Protects the bounded, list-focused web Alias Editor and its read-only catalog fallback. */
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
        assertTrue(renderer.contains("api('/api/v1/alias-lists')"));
        assertTrue(renderer.contains("if (!selectedList)"));
        assertTrue(renderer.indexOf("if (!selectedList)") <
            renderer.indexOf("api('/api/v1/aliases', pageParameters(filters))"));
        assertTrue(function(source, "function pageParameters(extra = {})").contains("limit: 100"));
        assertFalse(renderer.contains("All alias lists"));
        assertTrue(function(source, "function aliasListRail(lists, selectedList, admin)")
            .contains("href('aliases', { list: id"));
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
    void separatesConfigurationCallsEvidenceAndCustomColumns() throws Exception
    {
        String source = source();
        String tabs = function(source, "function aliasEditorViewTabs(selectedList)");
        String columns = function(source, "function aliasEditorColumns(view, admin, rows, onSelectionChange, selectedCustom)");
        String optional = function(source, "function aliasCatalogEnrichmentColumns()");
        String configuration = function(source, "function aliasCustomConfigurationColumns()");
        String base = function(source, "function aliasEditorBaseColumns(admin, rows, onSelectionChange)");

        for(String label: new String[]{"Configure", "Call Use", "System Evidence", "Custom"})
        {
            assertTrue(tabs.contains("'" + label + "'"), () -> "Missing editor view " + label);
        }
        assertTrue(columns.contains("view === 'calls'"));
        assertTrue(columns.contains("view === 'evidence'"));
        assertTrue(columns.contains("view === 'custom'"));
        assertTrue(source.contains("aliasColumnChooser(definitions, selectedCustom"));
        assertTrue(source.contains("exportCsvLink('aliases', exportContext)"));
        assertTrue(base.contains("id: 'description'"));
        for(String facet: new String[]{"alias-id", "alias-list", "family", "alias", "description", "group",
            "color", "icon", "matcher", "matcher-type", "identity-type", "protocol", "protocol-variant",
            "identifier", "exact", "ranged", "value", "minimum", "maximum", "text-value",
            "numeric-value", "tone-sequence", "scan-lists", "record", "broadcast-channels",
            "stream-as-talkgroup", "behavior", "overlap"})
        {
            assertTrue(configuration.contains("'" + facet + "'"), () -> "Missing custom Alias facet " + facet);
        }
        for(String field: new String[]{"call_count", "recorded_count", "streamed_count",
            "encrypted_evidence_count", "grant_count", "join_count", "emergency_count", "register_count",
            "logout_count", "relationship_count", "current_affiliation_count", "metrics_state",
            "first_evidence_ms", "last_evidence_ms"})
        {
            assertTrue(optional.contains(field), () -> "Missing evidence field " + field);
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
        assertTrue(renderer.contains("const admin = aliasAdminAllowed()"));
        assertTrue(renderer.contains("admin ? requestJson('/api/v1/admin/alias-lists'"));
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

        for(String label: new String[]{"Basics", "Identifier", "Call Handling", "Usage & Evidence"})
        {
            assertTrue(tabs.contains("'" + label + "'"), () -> "Missing modal tab " + label);
        }
        for(String field: new String[]{"value", "minimum", "maximum", "status", "code", "esn", "tones"})
        {
            assertTrue(fields.contains("field === '" + field + "'"), () -> "Missing matcher field " + field);
        }
        assertTrue(fields.contains("duration.min = '1'"));
        assertTrue(fields.contains("duration.max = '50'"));
        assertTrue(source.contains("value: aliasMatcherKey(entry), label: entry.label"));
        assertTrue(source.contains("source.matcher?.type, source.matcher?.protocol"));
        assertTrue(payload.contains("selector.dataset.originalProtocol"));
        assertTrue(editorPayload.contains("descriptor.minimum"));
        assertTrue(editorPayload.contains("descriptor.maximum"));
        assertTrue(source.contains("options.dcs_codes?.[0] || 'n023'"));
        assertTrue(source.contains("Missing: ${source.icon_name}"));
        assertTrue(source.contains("Missing: ${streamName}"));
        assertTrue(source.contains("error.code = failure?.code"));
        assertTrue(source.contains("color: aliasEditorColorValue(form.elements.color)"));
        assertTrue(source.contains("color.dataset.originalColor"));
        assertTrue(source.contains("value: 'RESET', label: 'Reset to default'"));
        assertTrue(source.contains("Discard your unsaved alias changes?"));
        assertTrue(function(source, "async function render()").contains("if (!closeReadOnlyModal(false)) return"));
        assertTrue(source.contains("if (!closeReadOnlyModal(false)) return;\n  window.history.pushState"));
    }

    @Test
    void keepsUsageScopeBreakdownFocusedAndRemovesRepeatedColumns() throws Exception
    {
        String source = source();
        String columns = function(source, "function aliasEditorScopeBreakdownColumns()");
        String usage = function(source, "function aliasUsageContent(response)");

        assertTrue(columns.contains("availableValue(row.scope_label)"));
        assertTrue(columns.contains("availableValue(row.topology)"));
        assertTrue(columns.contains("Number(row.last_evidence_ms || 0)"));
        assertTrue(columns.contains("aliasScopeMetricSummary(row, callUse, 'No calls observed')"));
        assertTrue(columns.contains("aliasScopeMetricSummary(row, systemEvidence, 'No signaling observed')"));
        for(String repeated: new String[]{"row.protocol", "row.system_name", "row.site_name", "row.metrics_state",
            "first_evidence_ms"})
        {
            assertFalse(columns.contains(repeated), () -> "Repeated scope field remains " + repeated);
        }
        assertTrue(usage.contains("aliasEditorScopeBreakdownColumns()"));
        assertTrue(usage.contains("complete totals remain"));
    }

    @Test
    void keepsBulkSelectionExplicitBoundedAndTriState() throws Exception
    {
        String source = source();
        String bulk = function(source, "function openAliasBulkModal(kind)");
        String columns = function(source, "function aliasEditorBaseColumns(admin, rows, onSelectionChange)");

        assertTrue(bulk.contains("slice(0, 500)"));
        assertTrue(bulk.contains("explicitly selected aliases"));
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
    void opensAllScanListMembersAndRemovesOnlySelectedMemberships() throws Exception
    {
        String source = source();
        String actions = function(source, "function adminScanListActions(scanList, revision)");
        String count = function(source, "function adminScanListMemberCount(scanList)");
        String renderer = function(source, "async function renderAliases()");
        String members = function(source,
            "async function renderScanListMembers(main, listResponse, scanListCatalog, scanList)");
        String columns = function(source, "function scanListMemberColumns(rows, onSelectionChange)");
        String bulk = function(source, "function scanListMemberBulkBar(scanList, onClear)");
        String remove = function(source, "function openScanListMemberRemoveModal(scanList)");

        assertTrue(actions.contains("'View Aliases'"));
        assertTrue(actions.contains("scanListId: scanList.id"));
        assertTrue(count.contains("scanListId: scanList.id"));
        assertTrue(renderer.contains("requestJson('/api/v1/admin/scan-lists'"));
        assertTrue(renderer.contains("await renderScanListMembers"));
        assertTrue(members.contains("api('/api/v1/aliases'"));
        assertTrue(members.contains("scan_list_id: scanList.id"));
        assertTrue(members.contains("scanListMemberColumns(rows, updateSelection)"));
        assertTrue(members.contains("'No aliases belong to this scan list'"));
        assertTrue(members.contains("scanListMemberBulkBar(scanList"));
        assertTrue(members.contains("scan_list_id: scanList.id"));
        assertTrue(columns.contains("id: 'alias-list'"));
        assertTrue(columns.contains("id: 'family'"));
        assertTrue(bulk.contains("`Remove from ${scanList.name}`"));
        assertTrue(remove.contains("slice(0, 500)"));
        assertTrue(remove.contains("/api/v1/admin/scan-lists/${scanList.id}/members"));
        assertTrue(remove.contains("operation: 'remove'"));
        assertTrue(remove.contains("alias_ids: ids"));
        assertTrue(remove.contains("aliases and their other scan-list memberships will be preserved"));
    }

    @Test
    void retainsExactServerFiltersAndEvidenceSemantics() throws Exception
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
        assertTrue(filters.contains("'Call use'"));
        assertTrue(filters.contains("'No calls observed'"));
        assertTrue(filters.contains("hidden.value = String(new Date(control.value).getTime())"));
        assertTrue(filters.contains("'lastActivityAfter', 'lastActivityBefore'"));
        assertTrue(source.contains("An em dash means unavailable or not collected"));
        assertTrue(source.contains("Logout means unit deregistration, not leaving a talkgroup"));
    }

    @Test
    void exposesUnmatchedPolicyAndBoundedObservedTalkgroupDiscovery() throws Exception
    {
        String source = source();
        String tabs = function(source, "function aliasEditorViewTabs(selectedList)");
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
        assertTrue(renderer.contains("observedTalkgroupDiscoverySupported(selectedList)"));
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
        assertTrue(policy.contains("'Unknown talkgroup scan-list delivery'"));
        assertTrue(policy.contains("'Save Global Settings'"));
        assertTrue(prefill.contains("scan_list_ids: [...(policy.scan_list_ids || [])]"));
        assertTrue(renderer.contains("'Global Settings'"));
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
        assertTrue(create.contains("!observedTalkgroupPromotionSupported(row)"));
        assertTrue(create.contains("'Review only'"));
        assertTrue(create.contains("observedTalkgroupPromotionReason(row)"));
        assertTrue(detail.contains("observedTalkgroupPromotionSupported(row)"));
        assertTrue(prefill.contains("stream_as_talkgroup: null"));
        assertTrue(prefill.contains("copy_actions_from_alias_id"));
        assertTrue(editor.contains("rangeActionsPromise"));
        assertTrue(editor.contains("/api/v1/admin/aliases/${Number(prefill.copy_actions_from_alias_id)}"));
        assertTrue(editor.contains("['recordable', 'broadcast_channels', 'scan_list_ids']"));
        assertTrue(editor.contains("selectedStreams.has(streamName) && (editing || configuredStreams.has(streamName))"));
    }

    @Test
    void providesResponsiveThemeAwareRailTableBulkBarAndModal() throws Exception
    {
        String css = readText(APP_CSS);
        for(String selector: new String[]{".alias-editor-workspace", ".alias-list-rail", ".alias-list-mobile",
            ".alias-list-summary", ".alias-editor-table-host", ".alias-bulk-bar", ".alias-editor-modal",
            ".alias-modal-tabs", ".alias-editor-grid", ".alias-stream-options", ".alias-tone-row",
            ".alias-scan-list-option", ".observed-talkgroup-table-host", ".observed-talkgroup-detail",
            ".alias-policy-modal"})
        {
            assertTrue(css.contains(selector), () -> "Missing Alias Editor style " + selector);
        }
        assertTrue(css.contains(":root[data-theme=\"dark\"] .alias-editor-table-host"));
        assertTrue(css.contains(":not(.auth-action):not(.table-sort-control)"));
        assertTrue(function(source(), "function aliasEditorModalTabs(panels, initial = 'basics')")
            .contains("node('button', 'secondary', label)"));
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
