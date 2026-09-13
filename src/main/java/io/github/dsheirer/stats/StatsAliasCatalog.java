/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static io.github.dsheirer.stats.StatsSqlRows.queryRows;

import io.github.dsheirer.alias.AliasAdministrationService;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;

/**
 * Read-only alias configuration catalog with compact statistics enrichment.  Configuration always comes from the
 * durable alias tables; statistics are projected through {@link StatsAliasResolver} so one observed identity can
 * contribute to only one winning alias.
 */
final class StatsAliasCatalog
{
    static final int MAX_ENRICH_ALIASES = 1_000;
    static final int MAX_COVERAGE_ROWS = 500;
    static final int MAX_COVERAGE_PAIRS = 10_000;
    static final int MAX_EVIDENCE_ROWS = 10_000;
    private static final int MAX_LEGACY_EXPORT_ENRICH_ALIASES =
        Math.max(1, MAX_COVERAGE_PAIRS / MAX_COVERAGE_ROWS);
    static final int MAX_BULK_ALIAS_SELECTION = 10_000;
    private static final int MIGRATION_EVIDENCE_PAGE_ROWS = 2_000;
    private static final int MIGRATION_SOURCE_PAGE_ROWS = 500;
    static final int MAX_TARGET_ALIAS_LISTS = 256;
    static final int MAX_TARGET_RANGES = 500;
    static final int MAX_SOURCE_TARGET_RANGES = 10_000;
    static final int MAX_BROADCAST_CHANNELS_PER_ALIAS = AliasAdministrationService.MAX_BROADCAST_CHANNELS;
    static final int MAX_BROADCAST_CHANNEL_ROWS = StatsSqlRows.MAXIMUM_MATERIALIZED_ROWS;
    static final int MAX_SCAN_LISTS_PER_ALIAS = AliasAdministrationService.MAX_SCAN_LISTS;
    static final int MAX_SCAN_LIST_MEMBERSHIP_ROWS = StatsSqlRows.MAXIMUM_MATERIALIZED_ROWS;
    private static final EnrichmentAdmission ENRICHMENT_ADMISSION = new EnrichmentAdmission(2);
    private static final Map<String,String> FAMILIES = Map.of(
        "p25", "P25", "dmr", "DMR", "nxdn", "NXDN", "nbfm", "NBFM");
    private static final Map<String,String> MATCHERS = Map.ofEntries(
        Map.entry("talkgroup", "TALKGROUP"), Map.entry("talkgroup_range", "TALKGROUP_RANGE"),
        Map.entry("radio", "RADIO_ID"), Map.entry("radio_range", "RADIO_ID_RANGE"),
        Map.entry("user_status", "STATUS"), Map.entry("unit_status", "UNIT_STATUS"),
        Map.entry("tone_sequence", "TONES"), Map.entry("dcs", "DCS"), Map.entry("esn", "ESN"));
    private static final Set<String> IDENTITY_TYPES = Set.of("talkgroup", "radio", "other");
    private static final Set<String> EVIDENCE_STATES = Set.of(
        "observed", "covered_no_evidence", "not_collected", "unsupported");
    private static final Set<String> USE_STATES = Set.of("used", "unused");
    private static final String IDENTIFIER_SORT_SQL = """
        CASE
            WHEN alias.matcher_type IN ('TALKGROUP_RANGE', 'RADIO_ID_RANGE')
                THEN printf('%020d–%020d', alias.min_value, alias.max_value)
            WHEN alias.value IS NOT NULL THEN printf('%020d', alias.value)
            WHEN alias.numeric_value IS NOT NULL THEN printf('%020d', alias.numeric_value)
            WHEN alias.text_value IS NOT NULL THEN lower(alias.text_value)
            WHEN alias.tone_sequence IS NOT NULL THEN lower(alias.tone_sequence)
            ELSE ''
        END
        """.strip();
    private static final Map<String,String> SORT_COLUMNS = Map.of(
        "name", "lower(coalesce(alias.name, ''))",
        "list", "lower(alias_list.name)",
        "family", "alias_list.family",
        "type", "identity_type",
        "matcher", "alias.matcher_type",
        "value", IDENTIFIER_SORT_SQL,
        "group", "lower(coalesce(alias.group_name, ''))"
    );
    private static final Map<String,String> METRIC_SORT_FIELDS = Map.ofEntries(
        Map.entry("logical_call_count", "logical_call_count"),
        Map.entry("recorded_logical_call_count", "recorded_logical_call_count"),
        Map.entry("stream_submitted_logical_call_count", "stream_submitted_logical_call_count"),
        Map.entry("encrypted_logical_call_count", "encrypted_logical_call_count"),
        Map.entry("grant_observation_count", "grant_observation_count"),
        Map.entry("join_observation_count", "join_observation_count"),
        Map.entry("emergency_observation_count", "emergency_observation_count"),
        Map.entry("register_observation_count", "register_observation_count"),
        Map.entry("logout_observation_count", "logout_observation_count"),
        Map.entry("denial_observation_count", "denial_observation_count"),
        Map.entry("data_observation_count", "data_observation_count"),
        Map.entry("other_signaling_observation_count", "other_signaling_observation_count"),
        Map.entry("signaling_observation_count", "signaling_observation_count"),
        Map.entry("first_evidence", "first_evidence_ms"),
        Map.entry("first_evidence_ms", "first_evidence_ms"),
        Map.entry("last_evidence", "last_evidence_ms"),
        Map.entry("last_evidence_ms", "last_evidence_ms")
    );
    private static final Map<String,IndexedActivitySort> INDEXED_ACTIVITY_SORTS = Map.of(
        "logical_call_count", new IndexedActivitySort("logical_call_count", "idx_alias_activity_calls", true),
        "signaling_observation_count", new IndexedActivitySort("signaling_observation_count",
            "idx_alias_activity_signaling", true),
        "last_evidence", new IndexedActivitySort("last_evidence_ms", "idx_alias_activity_last_seen", false),
        "last_evidence_ms", new IndexedActivitySort("last_evidence_ms", "idx_alias_activity_last_seen", false)
    );
    private static final List<String> METRIC_FIELDS = List.of(
        "logical_call_count", "recorded_logical_call_count", "stream_submitted_logical_call_count",
        "encrypted_logical_call_count", "grant_observation_count", "join_observation_count",
        "emergency_observation_count", "register_observation_count", "logout_observation_count",
        "denial_observation_count", "data_observation_count", "other_signaling_observation_count",
        "signaling_observation_count", "relationship_count",
        "join_relationship_count", "current_affiliation_count");
    private static final List<String> PERSISTED_METRIC_FIELDS = METRIC_FIELDS.subList(0, 13);
    private static final String SIGNALING_SQL = "summary.grant_count + summary.join_count + " +
        "summary.register_count + summary.active_count + summary.denial_count + summary.emergency_count + " +
        "summary.request_count + summary.busy_count + summary.queued_count + summary.acknowledge_count + " +
        "summary.check_count + summary.check_ack_count + summary.page_count + summary.status_count + " +
        "summary.gps_count + summary.logout_count + summary.patch_count + summary.patch_create_count + " +
        "summary.patch_cancel_count + summary.data_count";
    private static final String OTHER_SIGNALING_SQL = "summary.acknowledge_count + summary.active_count + " +
        "summary.busy_count + summary.check_count + summary.check_ack_count + " +
        "summary.gps_count + summary.page_count + summary.patch_count + summary.patch_cancel_count + " +
        "summary.patch_create_count + summary.queued_count + summary.request_count + summary.status_count + " +
        "summary.continue_count + summary.unknown_count";
    private static final String ALIAS_PROTOCOL_CODE_SQL = """
        CASE
            WHEN alias.protocol IN ('APCO25', 'APCO25_PHASE2') THEN 1
            WHEN alias.protocol = 'DMR' THEN 3
            WHEN alias.protocol = 'NXDN' THEN 4
            ELSE 0
        END
        """.strip();
    private static final String SUPPORTED_ALIAS_SQL = "(" + ALIAS_PROTOCOL_CODE_SQL +
        " > 0 AND alias.matcher_type IN ('TALKGROUP', 'TALKGROUP_RANGE', 'RADIO_ID', 'RADIO_ID_RANGE'))";

    private final StatsAliasResolver mResolver;

    StatsAliasCatalog(StatsAliasResolver resolver)
    {
        mResolver = resolver;
    }

    void invalidateActivitySnapshots()
    {
        //Alias activity is persisted transactionally.  This compatibility hook intentionally has no cache to clear.
    }

    Map<String,Object> aliasLists(Connection connection, StatsRequest request) throws SQLException
    {
        List<Map<String,Object>> rows = queryRows(connection, """
            SELECT list.id AS alias_list_id, list.name, list.family,
                count(DISTINCT alias.id) AS alias_count,
                (SELECT count(*) FROM configuration_channel channel
                 WHERE channel.alias_list_id = list.id) AS assigned_channel_count
            FROM alias_list list
            LEFT JOIN alias ON alias.alias_list_id = list.id
            GROUP BY list.id, list.name, list.family
            ORDER BY CASE list.family WHEN 'P25' THEN 1 WHEN 'DMR' THEN 2 WHEN 'NXDN' THEN 3 ELSE 4 END,
                lower(list.name), list.id
            LIMIT ? OFFSET ?
            """, request.limit() + 1, request.offset());
        boolean hasMore = rows.size() > request.limit();

        if(hasMore)
        {
            rows = new ArrayList<>(rows.subList(0, request.limit()));
        }

        List<Map<String,Object>> totals = queryRows(connection, "SELECT count(*) AS count FROM alias_list");
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("rows", rows);
        response.put("total_count", totals.isEmpty() ? 0 : number(totals.getFirst().get("count")));
        response.put("limit", request.limit());
        response.put("offset", request.offset());
        response.put("has_more", hasMore);
        response.put("next_offset", hasMore ? request.offset() + request.limit() : null);
        response.put("families", List.of("p25", "dmr", "nxdn", "nbfm"));
        response.put("matcher_types", MATCHERS.keySet().stream().sorted().toList());
        return response;
    }

    Map<String,Object> aliases(Connection connection, StatsRequest request) throws SQLException
    {
        boolean metricSort = metricSortField(request) != null;
        boolean includeActivity = request.booleanValue("include_activity", true);

        if(includeActivity || metricSort || hasMetricFilters(request))
        {
            long offset = request.longOffset();
            List<Map<String,Object>> queried = queryActivityRows(connection, request, request.limit() + 1,
                offset, null, true);
            boolean hasMore = queried.size() > request.limit();
            List<Map<String,Object>> rows = hasMore ?
                new ArrayList<>(queried.subList(0, request.limit())) : queried;
            applyConfigurationDiagnostics(connection, rows);
            Map<String,Object> response = new LinkedHashMap<>();
            response.put("rows", rows);
            response.put("limit", request.limit());
            response.put("offset", offset);
            response.put("has_more", hasMore);
            response.put("next_offset", hasMore ? offset + request.limit() : null);
            return response;
        }

        long offset = request.longOffset();
        List<Map<String,Object>> queried = queryAliasRows(connection, request, request.limit() + 1,
            offset, null, true, false);
        boolean hasMore = queried.size() > request.limit();
        List<Map<String,Object>> rows = hasMore ?
            new ArrayList<>(queried.subList(0, request.limit())) : queried;

        applyConfigurationDiagnostics(connection, rows);
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("rows", rows);
        response.put("limit", request.limit());
        response.put("offset", offset);
        response.put("has_more", hasMore);
        response.put("next_offset", hasMore ? offset + request.limit() : null);
        return response;
    }

    /**
     * Returns a bounded set of alias identifiers for interactive browser bulk actions.  Complete CSV exports use the
     * streaming iterators and are intentionally not subject to this selection safety limit.
     */
    List<Long> matchingAliasIds(Connection connection, StatsRequest request) throws SQLException
    {
        ActivityQuery query = buildActivityQuery(request, null, true, MAX_BULK_ALIAS_SELECTION + 1,
            0, true);
        List<Map<String,Object>> rows = queryRows(connection, query.sql(), query.parameters().toArray());

        if(rows.size() > MAX_BULK_ALIAS_SELECTION)
        {
            throw new StatsApiException(413, "alias_selection_too_large",
                "Matching alias selection exceeds the " + MAX_BULK_ALIAS_SELECTION + " row limit");
        }
        return rows.stream().map(row -> number(row.get("alias_id"))).toList();
    }

    Map<String,Object> alias(Connection connection, long aliasId) throws SQLException
    {
        if(aliasId <= 0)
        {
            throw new StatsApiException(400, "id is invalid");
        }

        List<Map<String,Object>> rows = queryActivityRows(connection, new StatsRequest(Map.of()), 1, 0, aliasId,
            true);

        if(rows.isEmpty())
        {
            throw new StatsApiException(404, "Alias not found");
        }

        Map<String,Object> breakdownRow = new LinkedHashMap<>(rows.getFirst());
        Map<Long,List<Map<String,Object>>> breakdown = enrich(connection, List.of(breakdownRow), true);
        copyRelationshipMetrics(breakdownRow, rows.getFirst());
        rows.getFirst().put("observed_source_count", breakdownRow.get("observed_source_count"));
        applyConfigurationDiagnostics(connection, rows);
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("alias", rows.getFirst());
        response.put("breakdown", breakdown.getOrDefault(aliasId, List.of()));
        return response;
    }

    List<Map<String,Object>> exportRows(Connection connection, StatsRequest request, int maximumRows)
        throws SQLException
    {
        List<Map<String,Object>> rows = queryActivityRows(connection, request, maximumRows + 1, 0, null, true);

        if(rows.size() <= maximumRows)
        {
            enrichLegacyExportRelationshipMetrics(connection, rows);
        }

        return rows;
    }

    /**
     * The original bounded Activity CSV includes live relationship and affiliation values that are deliberately not
     * persisted in the sortable Alias Activity summary.  Recalculate only for this legacy report, using copies so
     * the durable call/signaling values already selected by SQL remain authoritative.  The small batches preserve
     * the existing coverage-pair and evidence bounds even when one Alias List is assigned to many receiver sources.
     */
    private void enrichLegacyExportRelationshipMetrics(Connection connection, List<Map<String,Object>> rows)
        throws SQLException
    {
        for(int start = 0; start < rows.size(); start += MAX_LEGACY_EXPORT_ENRICH_ALIASES)
        {
            int end = Math.min(start + MAX_LEGACY_EXPORT_ENRICH_ALIASES, rows.size());
            List<Map<String,Object>> enriched = new ArrayList<>(end - start);

            for(int index = start; index < end; index++)
            {
                enriched.add(new LinkedHashMap<>(rows.get(index)));
            }

            enrich(connection, enriched, false);

            for(int index = 0; index < enriched.size(); index++)
            {
                copyRelationshipMetrics(enriched.get(index), rows.get(start + index));
            }
        }
    }

    /**
     * Iterates every configuration row in one Alias List without building the Activity projection.  Complete-list
     * transfer exports do not contain Activity counters, so joining the summary and calculating receiver coverage
     * would add work without changing the CSV.  The owning read transaction and durable identifier order give the
     * importer one stable selection while each configuration collection is attached to only a bounded batch.
     */
    void forEachAliasConfigurationBatch(Connection connection, long aliasListId, int batchSize,
                                        AliasBatchConsumer consumer) throws SQLException
    {
        if(batchSize < 1 || batchSize > 1_000)
        {
            throw new IllegalArgumentException("Alias export batch size must be between 1 and 1000");
        }

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT alias.id AS alias_id, alias.alias_list_id, alias_list.name AS alias_list_name,
                alias_list.family, alias.name, alias.description, alias.group_name AS `group`, alias.color,
                alias.icon_name, alias.stream_as_talkgroup, alias.record_enabled, alias.matcher_type,
                alias.protocol, alias.value, alias.min_value, alias.max_value, alias.text_value,
                alias.numeric_value, alias.tone_sequence
            FROM alias
            JOIN alias_list ON alias_list.id = alias.alias_list_id
            WHERE alias_list.id = ?
            ORDER BY alias.id ASC
            """))
        {
            statement.setLong(1, aliasListId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int columnCount = metadata.getColumnCount();
                List<Map<String,Object>> batch = new ArrayList<>(batchSize);

                while(resultSet.next())
                {
                    Map<String,Object> row = new LinkedHashMap<>();

                    for(int column = 1; column <= columnCount; column++)
                    {
                        row.put(metadata.getColumnLabel(column), resultSet.getObject(column));
                    }

                    batch.add(row);

                    if(batch.size() == batchSize)
                    {
                        prepareExportRows(connection, batch);
                        consumer.accept(List.copyOf(batch));
                        batch.clear();
                    }
                }

                if(!batch.isEmpty())
                {
                    prepareExportRows(connection, batch);
                    consumer.accept(List.copyOf(batch));
                }
            }
        }
    }

    /**
     * Iterates one stable filtered/sorted selection in bounded pages.  The caller owns the read transaction, which
     * keeps export ordering stable without a server-side cache or a whole-result Java collection.
     */
    void forEachFilteredAliasBatch(Connection connection, StatsRequest request, int batchSize,
                                   AliasBatchConsumer consumer) throws SQLException
    {
        if(batchSize < 1 || batchSize > 1_000)
        {
            throw new IllegalArgumentException("Alias export batch size must be between 1 and 1000");
        }

        //CSV exports intentionally use one canonical order.  Still build and validate the complete request so an
        //invalid UI sort cannot be silently accepted, but do not make export ordering depend on presentation state.
        ActivityQuery query = buildActivityQuery(request, null, false, 0, 0, true);
        try(PreparedStatement statement = connection.prepareStatement(query.sql()))
        {
            for(int index = 0; index < query.parameters().size(); index++)
            {
                statement.setObject(index + 1, query.parameters().get(index));
            }
            try(ResultSet resultSet = statement.executeQuery())
            {
                ResultSetMetaData metadata = resultSet.getMetaData();
                int columnCount = metadata.getColumnCount();
                List<Map<String,Object>> batch = new ArrayList<>(batchSize);
                while(resultSet.next())
                {
                    Map<String,Object> row = new LinkedHashMap<>();
                    for(int column = 1; column <= columnCount; column++)
                    {
                        row.put(metadata.getColumnLabel(column), resultSet.getObject(column));
                    }
                    batch.add(row);

                    if(batch.size() == batchSize)
                    {
                        prepareExportRows(connection, batch);
                        consumer.accept(List.copyOf(batch));
                        batch.clear();
                    }
                }

                if(!batch.isEmpty())
                {
                    prepareExportRows(connection, batch);
                    consumer.accept(List.copyOf(batch));
                }
            }
        }
    }

    @FunctionalInterface
    interface AliasBatchConsumer
    {
        void accept(List<Map<String,Object>> rows) throws SQLException;
    }

    record ActivityQuery(String sql, List<Object> parameters, IndexedActivitySort indexedSort) {}

    private record IndexedActivitySort(String column, String index, boolean requiresPositiveValue) {}

    private static String metricSortField(StatsRequest request)
    {
        return METRIC_SORT_FIELDS.get(request.sort("name"));
    }

    private static boolean hasMetricFilters(StatsRequest request)
    {
        validateMetricFilters(request);
        return request.text("evidence") != null || request.text("use") != null ||
            request.text("last_activity_after") != null || request.text("last_activity_before") != null;
    }

    private static void validateMetricFilters(StatsRequest request)
    {
        String evidence = request.text("evidence");
        if(evidence != null && !EVIDENCE_STATES.contains(evidence))
        {
            throw new StatsApiException(400, "evidence is invalid");
        }

        String use = request.text("use");
        if(use != null && !USE_STATES.contains(use))
        {
            throw new StatsApiException(400, "use is invalid");
        }

        optionalTimestamp(request, "last_activity_after");
        optionalTimestamp(request, "last_activity_before");
    }

    private static Long optionalTimestamp(StatsRequest request, String name)
    {
        String value = request.text(name);
        if(value == null)
        {
            return null;
        }

        try
        {
            long timestamp = Long.parseLong(value);
            if(timestamp < 0)
            {
                throw new NumberFormatException();
            }
            return timestamp;
        }
        catch(NumberFormatException exception)
        {
            throw new StatsApiException(400, name + " is invalid");
        }
    }

    private static List<Map<String,Object>> queryAliasRows(Connection connection, StatsRequest request, int limit,
                                                            long offset, Long aliasId,
                                                            boolean includeConfigurationCollections,
                                                            boolean stableIdentifierOrder)
        throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
            SELECT alias.id AS alias_id, alias.alias_list_id, alias_list.name AS alias_list_name,
                alias_list.family, alias.name, alias.description, alias.group_name AS `group`, alias.color,
                alias.icon_name, alias.stream_as_talkgroup, alias.record_enabled, alias.matcher_type,
                CASE
                    WHEN alias.matcher_type IN ('TALKGROUP', 'TALKGROUP_RANGE') THEN 'talkgroup'
                    WHEN alias.matcher_type IN ('RADIO_ID', 'RADIO_ID_RANGE') THEN 'radio'
                    ELSE 'other'
                END AS identity_type,
                alias.protocol, alias.value, alias.min_value, alias.max_value,
                alias.text_value, alias.numeric_value, alias.tone_sequence,
                CASE WHEN alias.matcher_type IN ('TALKGROUP_RANGE', 'RADIO_ID_RANGE') THEN 1 ELSE 0 END AS ranged,
                CASE WHEN alias.matcher_type NOT IN ('TALKGROUP_RANGE', 'RADIO_ID_RANGE') THEN 1 ELSE 0 END AS exact
            FROM alias
            JOIN alias_list ON alias_list.id = alias.alias_list_id
            WHERE 1=1
            """);
        List<Object> parameters = new ArrayList<>();

        if(aliasId != null)
        {
            sql.append(" AND alias.id = ?");
            parameters.add(aliasId);
        }
        else
        {
            addFilters(sql, parameters, request);
        }

        if(stableIdentifierOrder)
        {
            sql.append(" ORDER BY alias.id ASC LIMIT ? OFFSET ?");
        }
        else
        {
            String requestedSort = request.sort("name");
            String sort = SORT_COLUMNS.get(requestedSort);

            if(sort == null)
            {
                if(metricSortField(request) != null)
                {
                    sort = SORT_COLUMNS.get("name");
                }
                else
                {
                    throw new StatsApiException(400, "invalid_parameter", "sort is not supported", "sort");
                }
            }

            String direction = request.descending(false) ? " DESC" : " ASC";
            sql.append(" ORDER BY ").append(sort).append(direction).append(", alias.id ASC LIMIT ? OFFSET ?");
        }
        parameters.add(limit);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());

        if(includeConfigurationCollections)
        {
            attachConfigurationCollections(connection, rows);
        }

        for(Map<String,Object> row: rows)
        {
            normalizeConfigurationRow(row);
        }

        return rows;
    }

    /**
     * Queries the durable Alias Activity read model.  Both configuration and activity predicates are applied inside
     * SQLite before the deterministic sort and bounded page, so the JVM never materializes the complete Alias List.
     */
    private static List<Map<String,Object>> queryActivityRows(Connection connection, StatsRequest request, int limit,
                                                               long offset, Long aliasId,
                                                               boolean includeConfigurationCollections)
        throws SQLException
    {
        ActivityQuery query = buildActivityQuery(request, aliasId, true, limit, offset, false);
        List<Map<String,Object>> rows = queryRows(connection, query.sql(), query.parameters().toArray());

        //The list-first summary indexes give deep metric pages their bounded response time.  A positive metric page
        //is identical to the projected sort because every positive value is observed.  At the zero/NULL boundary,
        //current coverage can project a stored NULL to zero and an incomplete pre-migration fixture can lack a
        //summary row, so use the general expression sort for that bounded edge page.
        if(query.indexedSort() != null && !isSafeIndexedPage(rows, limit, query.indexedSort()))
        {
            query = buildActivityQuery(request, aliasId, true, limit, offset, false, false);
            rows = queryRows(connection, query.sql(), query.parameters().toArray());
        }

        prepareActivityRows(connection, rows, includeConfigurationCollections);
        return rows;
    }

    private static boolean isSafeIndexedPage(List<Map<String,Object>> rows, int requestedRows,
                                             IndexedActivitySort indexedSort)
    {
        if(rows.size() < requestedRows)
        {
            return false;
        }

        for(Map<String,Object> row: rows)
        {
            Long value = nullableNumber(row.get("_activity_index_sort"));
            if(value == null || indexedSort.requiresPositiveValue() && value <= 0)
            {
                return false;
            }
        }

        return true;
    }

    static ActivityQuery buildActivityQuery(StatsRequest request, Long aliasId, boolean paged, int limit,
                                            long offset, boolean aliasIdOrder)
    {
        return buildActivityQuery(request, aliasId, paged, limit, offset, aliasIdOrder, true);
    }

    private static ActivityQuery buildActivityQuery(StatsRequest request, Long aliasId, boolean paged, int limit,
                                                     long offset, boolean aliasIdOrder,
                                                     boolean allowIndexedMetricSort)
    {
        validateMetricFilters(request);
        String requestedSort = request.sort("name");
        boolean descending = request.descending(false);
        IndexedActivitySort indexedSort = indexedActivitySort(request, requestedSort, descending, aliasId,
            paged, aliasIdOrder, allowIndexedMetricSort);
        String summaryFrom = indexedSort != null ?
            "alias_activity_summary summary INDEXED BY " + indexedSort.index() +
                " JOIN alias ON alias.id = summary.alias_id" :
            "alias LEFT JOIN alias_activity_summary summary ON summary.alias_id = alias.id";
        String indexedSelect = indexedSort != null ?
            ", summary." + indexedSort.column() + " AS _activity_index_sort, " +
                "summary.alias_id AS _activity_index_alias_id" :
            ", NULL AS _activity_index_sort, NULL AS _activity_index_alias_id";
        StringBuilder sql = new StringBuilder("""
            WITH current_coverage AS (
                SELECT configuration.alias_list_id,
                    CASE WHEN configuration.decoder_type LIKE 'P25%%' THEN 1
                         WHEN configuration.decoder_type = 'DMR' THEN 3
                         WHEN configuration.decoder_type = 'NXDN' THEN 4 END AS protocol_code,
                    count(DISTINCT CASE
                        WHEN configuration.channel_kind = 'TRUNKED' AND channel.radio_system_id IS NOT NULL
                            THEN 'T:' || channel.radio_system_id
                        WHEN configuration.channel_kind = 'CONVENTIONAL' THEN 'C:' || channel.id
                    END) AS coverage_source_count,
                    count(DISTINCT CASE
                        WHEN configuration.channel_kind = 'TRUNKED' AND channel.radio_system_id IS NOT NULL
                            THEN 'T:' || channel.radio_system_id END) AS trunked_source_count,
                    count(DISTINCT CASE
                        WHEN configuration.channel_kind = 'TRUNKED'
                         AND configuration.decoder_type LIKE 'P25%%'
                         AND channel.radio_system_id IS NOT NULL THEN 'T:' || channel.radio_system_id END)
                        AS p25_trunked_source_count
                FROM configuration_channel configuration
                JOIN receiver_channel channel
                  ON channel.configuration_id = configuration.configuration_id
                WHERE configuration.alias_list_id IS NOT NULL
                  AND (configuration.decoder_type LIKE 'P25%%'
                    OR configuration.decoder_type IN ('DMR', 'NXDN'))
                GROUP BY configuration.alias_list_id, protocol_code
            ), activity_alias AS NOT MATERIALIZED (
                SELECT alias.id AS alias_id, alias.alias_list_id, alias_list.name AS alias_list_name,
                    alias_list.family, alias.name, alias.description, alias.group_name AS `group`, alias.color,
                    alias.icon_name, alias.stream_as_talkgroup, alias.record_enabled, alias.matcher_type,
                    CASE
                        WHEN alias.matcher_type IN ('TALKGROUP', 'TALKGROUP_RANGE') THEN 'talkgroup'
                        WHEN alias.matcher_type IN ('RADIO_ID', 'RADIO_ID_RANGE') THEN 'radio'
                        ELSE 'other'
                    END AS identity_type,
                    alias.protocol, alias.value, alias.min_value, alias.max_value,
                    alias.text_value, alias.numeric_value, alias.tone_sequence,
                    CASE WHEN alias.matcher_type IN ('TALKGROUP_RANGE', 'RADIO_ID_RANGE') THEN 1 ELSE 0 END AS ranged,
                    CASE WHEN alias.matcher_type NOT IN ('TALKGROUP_RANGE', 'RADIO_ID_RANGE') THEN 1 ELSE 0 END AS exact,
                    %s AS _identifier_sort,
                    %s AS _protocol_code,
                    coalesce(coverage.coverage_source_count, 0) AS coverage_source_count,
                    NULL AS observed_source_count,
                    CASE
                        WHEN NOT %s THEN 'unsupported'
                        WHEN summary.metrics_state = 'observed' THEN 'observed'
                        WHEN coalesce(coverage.coverage_source_count, 0) > 0 THEN 'covered_no_evidence'
                        ELSE 'not_collected'
                    END AS metrics_state,
                    CASE WHEN %s AND (summary.metrics_state = 'observed'
                            OR coalesce(coverage.coverage_source_count, 0) > 0)
                        THEN coalesce(summary.logical_call_count, 0) END AS logical_call_count,
                    CASE WHEN %s AND (summary.metrics_state = 'observed'
                            OR coalesce(coverage.coverage_source_count, 0) > 0)
                        THEN coalesce(summary.recorded_logical_call_count, 0) END AS recorded_logical_call_count,
                    CASE WHEN %s AND (summary.metrics_state = 'observed'
                            OR coalesce(coverage.coverage_source_count, 0) > 0)
                        THEN coalesce(summary.stream_submitted_logical_call_count, 0)
                        END AS stream_submitted_logical_call_count,
                    CASE WHEN %s AND (summary.metrics_state = 'observed'
                            OR coalesce(coverage.coverage_source_count, 0) > 0)
                        THEN coalesce(summary.encrypted_logical_call_count, 0)
                        END AS encrypted_logical_call_count,
                    CASE WHEN %s AND (summary.grant_observation_count IS NOT NULL
                            OR coalesce(coverage.p25_trunked_source_count, 0) > 0)
                        THEN coalesce(summary.grant_observation_count, 0) END AS grant_observation_count,
                    CASE WHEN %s AND (summary.join_observation_count IS NOT NULL
                            OR coalesce(coverage.trunked_source_count, 0) > 0)
                        THEN coalesce(summary.join_observation_count, 0) END AS join_observation_count,
                    CASE WHEN %s AND (summary.emergency_observation_count IS NOT NULL
                            OR coalesce(coverage.trunked_source_count, 0) > 0)
                        THEN coalesce(summary.emergency_observation_count, 0) END AS emergency_observation_count,
                    CASE WHEN %s AND (summary.register_observation_count IS NOT NULL
                            OR coalesce(coverage.trunked_source_count, 0) > 0)
                        THEN coalesce(summary.register_observation_count, 0) END AS register_observation_count,
                    CASE WHEN %s AND (summary.logout_observation_count IS NOT NULL
                            OR coalesce(coverage.trunked_source_count, 0) > 0)
                        THEN coalesce(summary.logout_observation_count, 0) END AS logout_observation_count,
                    CASE WHEN %s AND (summary.denial_observation_count IS NOT NULL
                            OR coalesce(coverage.trunked_source_count, 0) > 0)
                        THEN coalesce(summary.denial_observation_count, 0) END AS denial_observation_count,
                    CASE WHEN %s AND (summary.data_observation_count IS NOT NULL
                            OR coalesce(coverage.trunked_source_count, 0) > 0)
                        THEN coalesce(summary.data_observation_count, 0) END AS data_observation_count,
                    CASE WHEN %s AND (summary.other_signaling_observation_count IS NOT NULL
                            OR coalesce(coverage.trunked_source_count, 0) > 0)
                        THEN coalesce(summary.other_signaling_observation_count, 0)
                        END AS other_signaling_observation_count,
                    CASE WHEN %s AND (summary.signaling_observation_count IS NOT NULL
                            OR coalesce(coverage.trunked_source_count, 0) > 0)
                        THEN coalesce(summary.signaling_observation_count, 0) END AS signaling_observation_count,
                    NULL AS relationship_count, NULL AS join_relationship_count,
                    NULL AS current_affiliation_count,
                    summary.first_evidence_ms, summary.last_evidence_ms%s
                FROM %s
                JOIN alias_list ON alias_list.id = alias.alias_list_id
                LEFT JOIN current_coverage coverage
                  ON coverage.alias_list_id = alias.alias_list_id
                 AND coverage.protocol_code = %s
                WHERE 1=1
            """.formatted(IDENTIFIER_SORT_SQL, ALIAS_PROTOCOL_CODE_SQL, SUPPORTED_ALIAS_SQL,
            SUPPORTED_ALIAS_SQL, SUPPORTED_ALIAS_SQL, SUPPORTED_ALIAS_SQL, SUPPORTED_ALIAS_SQL,
            SUPPORTED_ALIAS_SQL, SUPPORTED_ALIAS_SQL, SUPPORTED_ALIAS_SQL, SUPPORTED_ALIAS_SQL,
            SUPPORTED_ALIAS_SQL, SUPPORTED_ALIAS_SQL, SUPPORTED_ALIAS_SQL, SUPPORTED_ALIAS_SQL,
            SUPPORTED_ALIAS_SQL, indexedSelect, summaryFrom, ALIAS_PROTOCOL_CODE_SQL));
        List<Object> parameters = new ArrayList<>();

        if(indexedSort != null)
        {
            sql.append(" AND summary.alias_list_id = ?");
            parameters.add(indexedAliasListId(request));
        }

        if(aliasId != null)
        {
            sql.append(" AND alias.id = ?");
            parameters.add(aliasId);
        }
        else
        {
            addFilters(sql, parameters, request);
        }

        String sort = METRIC_SORT_FIELDS.get(requestedSort);
        boolean metricSort = sort != null;

        if(sort == null)
        {
            sort = switch(requestedSort)
            {
                case "name" -> "lower(coalesce(name, ''))";
                case "list" -> "lower(alias_list_name)";
                case "family" -> "family";
                case "type" -> "identity_type";
                case "matcher" -> "matcher_type";
                case "value" -> "_identifier_sort";
                case "group" -> "lower(coalesce(`group`, ''))";
                default -> throw new StatsApiException(400, "invalid_parameter", "sort is not supported", "sort");
            };
        }

        String direction = descending ? " DESC" : " ASC";
        boolean narrowPagedSelection = indexedSort == null && aliasId == null && paged && !aliasIdOrder;
        if(narrowPagedSelection)
        {
            //Sort only the identifier and requested key across the complete matching set.  The second reference to
            //the non-materialized Activity projection is then constrained to the bounded page.  This keeps correct
            //coverage-aware NULL/zero ordering while avoiding a temporary sort record containing every wide Alias
            //configuration and activity column.
            sql.append(" ), page_alias AS MATERIALIZED (SELECT alias_id, ").append(sort)
                .append(" AS _page_sort FROM activity_alias WHERE 1=1");
            addMetricFilters(sql, parameters, request);
            sql.append(" ORDER BY ");
            if(metricSort && !descending)
            {
                sql.append(sort).append(" IS NULL ASC, ");
            }
            sql.append(sort).append(direction).append(", alias_id ASC LIMIT ? OFFSET ?)");
            parameters.add(limit);
            parameters.add(offset);
            sql.append(" SELECT activity_alias.* FROM page_alias JOIN activity_alias USING(alias_id) ORDER BY ");
            if(metricSort && !descending)
            {
                sql.append("page_alias._page_sort IS NULL ASC, ");
            }
            sql.append("page_alias._page_sort").append(direction).append(", page_alias.alias_id ASC");
        }
        else
        {
            sql.append(" ) SELECT * FROM activity_alias WHERE 1=1");
            addMetricFilters(sql, parameters, request);
            sql.append(" ORDER BY ");
            if(aliasIdOrder)
            {
                sql.append("alias_id ASC");
            }
            else
            {
                //SQLite naturally places NULL after every numeric value for descending order.  The explicit
                //discriminator is only needed for ascending order, where product semantics require NULL last.
                if(metricSort && !descending)
                {
                    sql.append(sort).append(" IS NULL ASC, ");
                }
                if(indexedSort != null)
                {
                    sql.append("_activity_index_sort DESC, _activity_index_alias_id ASC");
                }
                else
                {
                    sql.append(sort).append(direction).append(", alias_id ASC");
                }
            }
            if(paged)
            {
                sql.append(" LIMIT ? OFFSET ?");
                parameters.add(limit);
                parameters.add(offset);
            }
        }
        return new ActivityQuery(sql.toString(), List.copyOf(parameters), indexedSort);
    }

    private static IndexedActivitySort indexedActivitySort(StatsRequest request, String requestedSort,
                                                            boolean descending, Long aliasId, boolean paged,
                                                            boolean aliasIdOrder, boolean allowed)
    {
        if(!allowed || aliasId != null || !paged || aliasIdOrder || !descending)
        {
            return null;
        }

        IndexedActivitySort indexedSort = INDEXED_ACTIVITY_SORTS.get(requestedSort);
        return indexedSort != null && indexedAliasListId(request) != null ? indexedSort : null;
    }

    private static Long indexedAliasListId(StatsRequest request)
    {
        String list = request.text("list");
        if(list == null || !list.matches("[1-9][0-9]*"))
        {
            return null;
        }

        try
        {
            return Long.parseLong(list);
        }
        catch(NumberFormatException exception)
        {
            throw new StatsApiException(400, "list is invalid");
        }
    }

    private static void prepareActivityRows(Connection connection, List<Map<String,Object>> rows,
                                             boolean includeConfigurationCollections) throws SQLException
    {
        for(Map<String,Object> row: rows)
        {
            row.remove("_identifier_sort");
            row.remove("_protocol_code");
            row.remove("_activity_index_sort");
            row.remove("_activity_index_alias_id");
            normalizeConfigurationRow(row);
        }
        if(includeConfigurationCollections)
        {
            attachConfigurationCollections(connection, rows);
        }
    }

    /**
     * Attaches configuration collections for one bounded export batch directly from JDBC cursors.  A valid Alias can
     * belong to every configured scan list, so a 1,000-Alias batch can legitimately exceed the generic 20,000-row API
     * materialization guard.  The cursor keeps that valid dense case bounded by the fixed batch and per-Alias limits.
     */
    private static void prepareExportRows(Connection connection, List<Map<String,Object>> rows) throws SQLException
    {
        for(Map<String,Object> row: rows)
        {
            normalizeConfigurationRow(row);
        }

        attachBroadcastChannelsForExport(connection, rows);
        attachScanListsForExport(connection, rows);
    }

    private static void attachBroadcastChannelsForExport(Connection connection, List<Map<String,Object>> aliases)
        throws SQLException
    {
        List<Long> aliasIds = aliases.stream().map(row -> nullableNumber(row.get("alias_id")))
            .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long,List<String>> channels = new HashMap<>();

        if(!aliasIds.isEmpty())
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                SELECT route.alias_id,
                    coalesce(nullif(trim(json_extract(stream.config_json, '$.name')), ''),
                        route.broadcast_configuration_id) AS channel_name
                FROM alias_broadcast_channel route
                JOIN configuration_broadcast_stream stream
                  ON stream.configuration_id = route.broadcast_configuration_id
                WHERE route.alias_id IN (%s)
                ORDER BY route.alias_id, lower(channel_name), channel_name
                """.formatted(placeholders(aliasIds.size()))))
            {
                bind(statement, aliasIds);

                try(ResultSet resultSet = statement.executeQuery())
                {
                    while(resultSet.next())
                    {
                        long aliasId = resultSet.getLong("alias_id");
                        String channel = resultSet.getString("channel_name");
                        List<String> values = channels.computeIfAbsent(aliasId, ignored -> new ArrayList<>());

                        if(channel == null || channel.isBlank() || values.size() >= MAX_BROADCAST_CHANNELS_PER_ALIAS)
                        {
                            throw new StatsApiException(413, "alias_routes_too_large",
                                "An alias has too many or invalid broadcast channels");
                        }
                        values.add(channel);
                    }
                }
            }
        }

        for(Map<String,Object> alias: aliases)
        {
            Long aliasId = nullableNumber(alias.get("alias_id"));
            alias.put("broadcast_channels", List.copyOf(channels.getOrDefault(aliasId, List.of())));
        }
    }

    private static void attachScanListsForExport(Connection connection, List<Map<String,Object>> aliases)
        throws SQLException
    {
        List<Long> aliasIds = aliases.stream().map(row -> nullableNumber(row.get("alias_id")))
            .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long,List<String>> scanLists = new HashMap<>();

        if(!aliasIds.isEmpty())
        {
            try(PreparedStatement statement = connection.prepareStatement("""
                SELECT membership.alias_id, list.name AS scan_list_name
                FROM alias_scan_list_membership membership
                JOIN scan_list list ON list.id = membership.scan_list_id
                WHERE membership.alias_id IN (%s)
                ORDER BY membership.alias_id, list.sort_order, lower(list.name), list.id
                """.formatted(placeholders(aliasIds.size()))))
            {
                bind(statement, aliasIds);

                try(ResultSet resultSet = statement.executeQuery())
                {
                    while(resultSet.next())
                    {
                        long aliasId = resultSet.getLong("alias_id");
                        String scanList = resultSet.getString("scan_list_name");
                        List<String> values = scanLists.computeIfAbsent(aliasId, ignored -> new ArrayList<>());

                        if(scanList == null || scanList.isBlank() || values.size() >= MAX_SCAN_LISTS_PER_ALIAS)
                        {
                            throw new StatsApiException(413, "alias_scan_lists_too_large",
                                "An alias has too many or invalid scan-list memberships");
                        }
                        values.add(scanList);
                    }
                }
            }
        }

        for(Map<String,Object> alias: aliases)
        {
            Long aliasId = nullableNumber(alias.get("alias_id"));
            alias.put("scan_lists", List.copyOf(scanLists.getOrDefault(aliasId, List.of())));
        }
    }

    private static void bind(PreparedStatement statement, List<Long> values) throws SQLException
    {
        for(int index = 0; index < values.size(); index++)
        {
            statement.setLong(index + 1, values.get(index));
        }
    }

    private static void addMetricFilters(StringBuilder sql, List<Object> parameters, StatsRequest request)
    {
        String evidence = request.text("evidence");
        if(evidence != null)
        {
            sql.append(" AND metrics_state = ?");
            parameters.add(evidence);
        }

        String use = request.text("use");
        if("used".equals(use))
        {
            sql.append(" AND logical_call_count > 0");
        }
        else if("unused".equals(use))
        {
            sql.append(" AND logical_call_count = 0");
        }

        Long after = optionalTimestamp(request, "last_activity_after");
        if(after != null)
        {
            sql.append(" AND last_evidence_ms >= ?");
            parameters.add(after);
        }

        Long before = optionalTimestamp(request, "last_activity_before");
        if(before != null)
        {
            sql.append(" AND last_evidence_ms <= ?");
            parameters.add(before);
        }
    }

    private static void attachConfigurationCollections(Connection connection, List<Map<String,Object>> aliases)
        throws SQLException
    {
        attachBroadcastChannels(connection, aliases);
        attachScanLists(connection, aliases);
    }

    private static void attachBroadcastChannels(Connection connection, List<Map<String,Object>> aliases)
        throws SQLException
    {
        List<Long> aliasIds = aliases.stream().map(row -> nullableNumber(row.get("alias_id")))
            .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long,List<String>> channels = new HashMap<>();
        long routeTotal = 0;

        for(int start = 0; start < aliasIds.size(); start += 500)
        {
            List<Long> chunk = aliasIds.subList(start, Math.min(start + 500, aliasIds.size()));
            List<Map<String,Object>> counts = queryRows(connection, """
                SELECT route.alias_id, count(*) AS route_count
                FROM alias_broadcast_channel route
                JOIN configuration_broadcast_stream stream
                  ON stream.configuration_id = route.broadcast_configuration_id
                WHERE route.alias_id IN (%s)
                GROUP BY route.alias_id
                ORDER BY route.alias_id
                """.formatted(placeholders(chunk.size())), chunk.toArray());

            for(Map<String,Object> count: counts)
            {
                long routes = number(count.get("route_count"));

                if(routes > MAX_BROADCAST_CHANNELS_PER_ALIAS)
                {
                    throw new StatsApiException(413, "alias_routes_too_large",
                        "An alias has too many broadcast channels");
                }

                routeTotal += routes;

                if(routeTotal > MAX_BROADCAST_CHANNEL_ROWS)
                {
                    throw new StatsApiException(413, "alias_routes_too_large",
                        "Alias broadcast channels exceed the response safety limit");
                }
            }
        }

        for(int start = 0; start < aliasIds.size(); start += 500)
        {
            List<Long> chunk = aliasIds.subList(start, Math.min(start + 500, aliasIds.size()));
            List<Map<String,Object>> routes = queryRows(connection, """
                SELECT route.alias_id,
                    coalesce(nullif(trim(json_extract(stream.config_json, '$.name')), ''),
                        route.broadcast_configuration_id) AS channel_name
                FROM alias_broadcast_channel route
                JOIN configuration_broadcast_stream stream
                  ON stream.configuration_id = route.broadcast_configuration_id
                WHERE route.alias_id IN (%s)
                ORDER BY route.alias_id, lower(channel_name), channel_name
                """.formatted(placeholders(chunk.size())), chunk.toArray());

            for(Map<String,Object> route: routes)
            {
                Long aliasId = nullableNumber(route.get("alias_id"));
                String channel = text(route.get("channel_name"));

                if(aliasId != null && channel != null)
                {
                    channels.computeIfAbsent(aliasId, ignored -> new ArrayList<>()).add(channel);
                }
            }
        }

        for(Map<String,Object> alias: aliases)
        {
            Long aliasId = nullableNumber(alias.get("alias_id"));
            alias.put("broadcast_channels", List.copyOf(channels.getOrDefault(aliasId, List.of())));
        }
    }

    private static void attachScanLists(Connection connection, List<Map<String,Object>> aliases)
        throws SQLException
    {
        List<Long> aliasIds = aliases.stream().map(row -> nullableNumber(row.get("alias_id")))
            .filter(java.util.Objects::nonNull).distinct().toList();
        Map<Long,List<Long>> scanListIds = new HashMap<>();
        Map<Long,List<String>> scanListNames = new HashMap<>();
        long membershipTotal = 0;

        for(int start = 0; start < aliasIds.size(); start += 500)
        {
            List<Long> chunk = aliasIds.subList(start, Math.min(start + 500, aliasIds.size()));
            List<Map<String,Object>> counts = queryRows(connection, """
                SELECT alias_id, count(*) AS membership_count
                FROM alias_scan_list_membership
                WHERE alias_id IN (%s)
                GROUP BY alias_id
                ORDER BY alias_id
                """.formatted(placeholders(chunk.size())), chunk.toArray());

            for(Map<String,Object> count: counts)
            {
                long memberships = number(count.get("membership_count"));

                if(memberships > MAX_SCAN_LISTS_PER_ALIAS)
                {
                    throw new StatsApiException(413, "alias_scan_lists_too_large",
                        "An alias has too many scan-list memberships");
                }

                membershipTotal += memberships;

                if(membershipTotal > MAX_SCAN_LIST_MEMBERSHIP_ROWS)
                {
                    throw new StatsApiException(413, "alias_scan_lists_too_large",
                        "Alias scan-list memberships exceed the response safety limit");
                }
            }
        }

        for(int start = 0; start < aliasIds.size(); start += 500)
        {
            List<Long> chunk = aliasIds.subList(start, Math.min(start + 500, aliasIds.size()));
            List<Map<String,Object>> memberships = queryRows(connection, """
                SELECT membership.alias_id, list.id AS scan_list_id, list.name AS scan_list_name
                FROM alias_scan_list_membership membership
                JOIN scan_list list ON list.id = membership.scan_list_id
                WHERE membership.alias_id IN (%s)
                ORDER BY membership.alias_id, list.sort_order, lower(list.name), list.id
                """.formatted(placeholders(chunk.size())), chunk.toArray());

            for(Map<String,Object> membership: memberships)
            {
                Long aliasId = nullableNumber(membership.get("alias_id"));
                Long scanListId = nullableNumber(membership.get("scan_list_id"));
                String scanListName = text(membership.get("scan_list_name"));

                if(aliasId != null && scanListId != null && scanListName != null)
                {
                    scanListIds.computeIfAbsent(aliasId, ignored -> new ArrayList<>()).add(scanListId);
                    scanListNames.computeIfAbsent(aliasId, ignored -> new ArrayList<>()).add(scanListName);
                }
            }
        }

        for(Map<String,Object> alias: aliases)
        {
            Long aliasId = nullableNumber(alias.get("alias_id"));
            alias.put("scan_list_ids", List.copyOf(scanListIds.getOrDefault(aliasId, List.of())));
            alias.put("scan_lists", List.copyOf(scanListNames.getOrDefault(aliasId, List.of())));
        }
    }

    private static void addFilters(StringBuilder sql, List<Object> parameters, StatsRequest request)
    {
        String family = request.text("family");

        if(family != null)
        {
            String databaseFamily = FAMILIES.get(family);

            if(databaseFamily == null)
            {
                throw new StatsApiException(400, "family is invalid");
            }

            sql.append(" AND alias_list.family = ?");
            parameters.add(databaseFamily);
        }

        String identityType = request.text("type");

        if(identityType != null)
        {
            if(!IDENTITY_TYPES.contains(identityType))
            {
                throw new StatsApiException(400, "type is invalid");
            }

            sql.append(switch(identityType)
            {
                case "talkgroup" -> " AND alias.matcher_type IN ('TALKGROUP', 'TALKGROUP_RANGE')";
                case "radio" -> " AND alias.matcher_type IN ('RADIO_ID', 'RADIO_ID_RANGE')";
                default -> " AND alias.matcher_type NOT IN ('TALKGROUP', 'TALKGROUP_RANGE', " +
                    "'RADIO_ID', 'RADIO_ID_RANGE')";
            });
        }

        String matcher = request.text("matcher");

        if(matcher != null)
        {
            String databaseMatcher = MATCHERS.get(matcher);

            if(databaseMatcher == null)
            {
                throw new StatsApiException(400, "matcher is invalid");
            }

            sql.append(" AND alias.matcher_type = ?");
            parameters.add(databaseMatcher);
        }

        String list = request.text("list");

        if(list != null)
        {
            if(list.matches("[1-9][0-9]*"))
            {
                try
                {
                    sql.append(" AND alias_list.id = ?");
                    parameters.add(Long.parseLong(list));
                }
                catch(NumberFormatException e)
                {
                    throw new StatsApiException(400, "list is invalid");
                }
            }
            else
            {
                sql.append(" AND alias_list.name = ? COLLATE NOCASE");
                parameters.add(list);
            }
        }

        String group = request.text("group");
        if(group != null)
        {
            sql.append(" AND alias.group_name = ? COLLATE NOCASE");
            parameters.add(group);
        }

        String scanListId = request.text("scan_list_id");
        if(scanListId != null)
        {
            try
            {
                if(!scanListId.matches("[1-9][0-9]*"))
                {
                    throw new NumberFormatException();
                }

                sql.append(" AND EXISTS (SELECT 1 FROM alias_scan_list_membership membership " +
                    "WHERE membership.alias_id = alias.id AND membership.scan_list_id = ?)");
                parameters.add(Long.parseLong(scanListId));
            }
            catch(NumberFormatException e)
            {
                throw new StatsApiException(400, "invalid_parameter",
                    "scan_list_id must be a positive decimal integer", "scan_list_id");
            }
        }

        String record = request.text("record");
        if(record != null)
        {
            switch(record)
            {
                case "enabled" -> sql.append(" AND alias.record_enabled = 1");
                case "disabled" -> sql.append(" AND alias.record_enabled = 0");
                default -> throw new StatsApiException(400, "record is invalid");
            }
        }

        String stream = request.text("stream");
        if(stream != null)
        {
            switch(stream)
            {
                case "present" -> sql.append(" AND EXISTS (SELECT 1 FROM alias_broadcast_channel route " +
                    "WHERE route.alias_id = alias.id)");
                case "none" -> sql.append(" AND NOT EXISTS (SELECT 1 FROM alias_broadcast_channel route " +
                    "WHERE route.alias_id = alias.id)");
                default -> throw new StatsApiException(400, "stream is invalid");
            }
        }

        String search = request.search();

        if(search != null)
        {
            sql.append("""
                 AND (lower(coalesce(alias.name, '')) LIKE ?
                   OR lower(coalesce(alias.description, '')) LIKE ?
                   OR lower(coalesce(alias.group_name, '')) LIKE ?
                   OR lower(alias_list.name) LIKE ?
                   OR lower(alias.matcher_type) LIKE ?
                   OR lower(coalesce(alias.protocol, '')) LIKE ?
                   OR CAST(coalesce(alias.value, alias.min_value, alias.numeric_value) AS TEXT) LIKE ?
                   OR CAST(alias.max_value AS TEXT) LIKE ?
                   OR lower(coalesce(alias.text_value, '')) LIKE ?
                   OR lower(coalesce(alias.tone_sequence, '')) LIKE ?)
                """);
            String like = "%" + search.toLowerCase(Locale.ROOT) + "%";
            for(int x = 0; x < 10; x++)
            {
                parameters.add(like);
            }
        }
    }

    private static void normalizeConfigurationRow(Map<String,Object> row)
    {
        String matcher = text(row.get("matcher_type"));
        row.put("matcher_label", matcherLabel(matcher));
        row.put("identifier_display", identifierDisplay(row));
    }

    /**
     * Adds the same identifier-overlap warning shown by the desktop editor to only the bounded rows being returned.
     * The correlated comparison still checks every sibling in the owning alias list, so paging and filters cannot
     * hide a collision.
     */
    private static void applyConfigurationDiagnostics(Connection connection, List<Map<String,Object>> aliases)
        throws SQLException
    {
        if(aliases.isEmpty())
        {
            return;
        }

        List<Long> aliasIds = aliases.stream().map(row -> nullableNumber(row.get("alias_id")))
            .filter(java.util.Objects::nonNull).distinct().toList();

        if(aliasIds.isEmpty())
        {
            return;
        }

        Set<Long> overlaps = new HashSet<>();
        for(Map<String,Object> alias: aliases)
        {
            if(hasConfigurationOverlap(connection, alias))
            {
                overlaps.add(number(alias.get("alias_id")));
            }
        }

        for(Map<String,Object> alias: aliases)
        {
            boolean overlap = overlaps.contains(number(alias.get("alias_id")));
            alias.put("overlap", overlap);
            alias.put("configuration_errors", overlap ? List.of("overlap") : List.of());
        }
    }

    private static boolean hasConfigurationOverlap(Connection connection, Map<String,Object> alias)
        throws SQLException
    {
        long aliasId = number(alias.get("alias_id"));
        long aliasListId = number(alias.get("alias_list_id"));
        String matcher = text(alias.get("matcher_type"));
        String protocol = text(alias.get("protocol"));
        String sql;
        List<Object> parameters = new ArrayList<>();

        if(("TALKGROUP".equals(matcher) || "RADIO_ID".equals(matcher)) && alias.get("value") != null)
        {
            String index = "TALKGROUP".equals(matcher) ? "idx_alias_talkgroup_value" :
                "idx_alias_radio_value";
            String protocolPredicate;
            if("APCO25".equals(protocol) || "APCO25_PHASE2".equals(protocol))
            {
                protocolPredicate = "protocol IN ('APCO25','APCO25_PHASE2')";
            }
            else
            {
                protocolPredicate = "protocol = ?";
                parameters.add(protocol);
            }
            sql = "SELECT 1 FROM alias INDEXED BY " + index +
                " WHERE matcher_type='" + matcher + "' AND " + protocolPredicate +
                " AND value=? AND alias_list_id=? AND id<>? LIMIT 1";
            parameters.add(alias.get("value"));
        }
        else if(("TALKGROUP_RANGE".equals(matcher) || "RADIO_ID_RANGE".equals(matcher)) &&
            alias.get("min_value") != null && alias.get("max_value") != null)
        {
            String index = "TALKGROUP_RANGE".equals(matcher) ? "idx_alias_activity_talkgroup_range" :
                "idx_alias_activity_radio_range";
            String protocolPredicate;
            if("APCO25".equals(protocol) || "APCO25_PHASE2".equals(protocol))
            {
                protocolPredicate = "protocol IN ('APCO25','APCO25_PHASE2')";
            }
            else
            {
                protocolPredicate = "protocol = ?";
                parameters.add(protocol);
            }
            sql = "SELECT 1 FROM alias INDEXED BY " + index +
                " WHERE alias_list_id=? AND matcher_type='" + matcher + "' AND " + protocolPredicate +
                " AND min_value<=? AND max_value>=? AND id<>? LIMIT 1";
            parameters.add(aliasListId);
            parameters.add(alias.get("max_value"));
            parameters.add(alias.get("min_value"));
            parameters.add(aliasId);
            return exists(connection, sql, parameters);
        }
        else if(("STATUS".equals(matcher) || "UNIT_STATUS".equals(matcher)) &&
            alias.get("numeric_value") != null)
        {
            sql = "SELECT 1 FROM alias WHERE alias_list_id=? AND matcher_type=? " +
                "AND numeric_value=? AND id<>? LIMIT 1";
            parameters.add(aliasListId);
            parameters.add(matcher);
            parameters.add(alias.get("numeric_value"));
            parameters.add(aliasId);
            return exists(connection, sql, parameters);
        }
        else if("DCS".equals(matcher) && alias.get("text_value") != null)
        {
            sql = "SELECT 1 FROM alias WHERE alias_list_id=? AND matcher_type='DCS' " +
                "AND text_value=? AND id<>? LIMIT 1";
            parameters.add(aliasListId);
            parameters.add(alias.get("text_value"));
            parameters.add(aliasId);
            return exists(connection, sql, parameters);
        }
        else if("TONES".equals(matcher) && alias.get("tone_sequence") != null)
        {
            sql = "SELECT 1 FROM alias WHERE alias_list_id=? AND matcher_type='TONES' " +
                "AND tone_sequence=? AND id<>? LIMIT 1";
            parameters.add(aliasListId);
            parameters.add(alias.get("tone_sequence"));
            parameters.add(aliasId);
            return exists(connection, sql, parameters);
        }
        else
        {
            return false;
        }

        parameters.add(aliasListId);
        parameters.add(aliasId);
        return exists(connection, sql, parameters);
    }

    private static boolean exists(Connection connection, String sql, List<Object> parameters) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement(sql))
        {
            for(int index = 0; index < parameters.size(); index++)
            {
                statement.setObject(index + 1, parameters.get(index));
            }
            try(ResultSet resultSet = statement.executeQuery())
            {
                return resultSet.next();
            }
        }
    }

    private static void copyRelationshipMetrics(Map<String,Object> source, Map<String,Object> target)
    {
        target.put("relationship_count", source.get("relationship_count"));
        target.put("join_relationship_count", source.get("join_relationship_count"));
        target.put("current_affiliation_count", source.get("current_affiliation_count"));
    }

    private Map<Long,List<Map<String,Object>>> enrich(Connection connection, List<Map<String,Object>> aliases,
                                                       boolean includeBreakdown) throws SQLException
    {
        if(aliases.isEmpty())
        {
            return Map.of();
        }

        if(aliases.size() > MAX_ENRICH_ALIASES)
        {
            throw new StatsApiException(413, "Alias enrichment exceeds the bounded alias limit");
        }

        return ENRICHMENT_ADMISSION.execute(() -> enrichAdmitted(connection, aliases, includeBreakdown));
    }

    private Map<Long,List<Map<String,Object>>> enrichAdmitted(Connection connection,
                                                               List<Map<String,Object>> aliases,
                                                               boolean includeBreakdown) throws SQLException
    {
        return enrichAdmitted(connection, aliases, includeBreakdown, null);
    }

    private Map<Long,List<Map<String,Object>>> enrichAdmitted(Connection connection,
                                                               List<Map<String,Object>> aliases,
                                                               boolean includeBreakdown,
                                                               List<CoverageSource> preloadedSources) throws SQLException
    {
        Map<Long,Map<String,MetricAccumulator>> metrics = new LinkedHashMap<>();
        List<CoverageSource> sources = preloadedSources;

        if(sources == null)
        {
            IdentityTargets targets = IdentityTargets.from(aliases);

            if(targets.isEmpty())
            {
                return applyNoCoverageMetrics(aliases, includeBreakdown);
            }

            sources = loadCoverageSources(connection, targets);
        }

        if(sources.isEmpty())
        {
            return applyNoCoverageMetrics(aliases, includeBreakdown);
        }

        Map<Long,Map<String,CoverageSource>> coverage = new LinkedHashMap<>();
        int coveragePairs = 0;

        for(Map<String,Object> alias: aliases)
        {
            long aliasId = number(alias.get("alias_id"));
            Map<String,CoverageSource> compatible = new LinkedHashMap<>();

            if(isSupportedIdentity(alias))
            {
                for(CoverageSource source: sources)
                {
                    if(isEligible(alias, source))
                    {
                        compatible.put(source.key, source);
                    }
                }
            }

            coveragePairs += compatible.size();

            if(coveragePairs > MAX_COVERAGE_PAIRS)
            {
                throw new StatsApiException(413, "Alias coverage exceeds the bounded query limit");
            }

            coverage.put(aliasId, compatible);
            Map<String,MetricAccumulator> aliasMetrics = new LinkedHashMap<>();

            for(CoverageSource source: compatible.values())
            {
                aliasMetrics.put(source.key, new MetricAccumulator(source));
            }

            metrics.put(aliasId, aliasMetrics);
        }

        Set<Long> radioSystemIds = new LinkedHashSet<>();
        Set<Long> conventionalChannelIds = new LinkedHashSet<>();

        for(Map<String,CoverageSource> aliasCoverage: coverage.values())
        {
            for(CoverageSource source: aliasCoverage.values())
            {
                if(source.trunked)
                {
                    radioSystemIds.add(source.numericId);
                }
                else
                {
                    conventionalChannelIds.add(source.numericId);
                }
            }
        }

        Map<Long,List<CoverageSource>> systemSourcesById = sourceProjections(sources, true);
        Map<Long,List<CoverageSource>> channelSourcesById = sourceProjections(sources, false);
        SourceIdentityTargets sourceTargets = SourceIdentityTargets.from(aliases, coverage);
        applyTrunkedEvidence(connection, metrics, radioSystemIds, systemSourcesById, sourceTargets,
            new EvidenceBudget(MAX_EVIDENCE_ROWS));
        applyConventionalEvidence(connection, metrics, conventionalChannelIds, channelSourcesById, sourceTargets,
            new EvidenceBudget(MAX_EVIDENCE_ROWS));
        applyRelationships(connection, metrics, radioSystemIds, systemSourcesById, sourceTargets,
            new EvidenceBudget(MAX_EVIDENCE_ROWS));
        applyCurrentAffiliations(connection, metrics, radioSystemIds, systemSourcesById, sourceTargets,
            new EvidenceBudget(MAX_EVIDENCE_ROWS));

        Map<Long,List<Map<String,Object>>> breakdown = new LinkedHashMap<>();

        for(Map<String,Object> alias: aliases)
        {
            long aliasId = number(alias.get("alias_id"));
            Map<String,MetricAccumulator> aliasMetrics = metrics.getOrDefault(aliasId, Map.of());
            boolean supported = isSupportedIdentity(alias);
            int observedSources = (int)aliasMetrics.values().stream().filter(MetricAccumulator::observed).count();
            alias.put("coverage_source_count", aliasMetrics.size());
            alias.put("observed_source_count", observedSources);
            alias.put("metrics_state", !supported ? "unsupported" : aliasMetrics.isEmpty() ? "not_collected" :
                observedSources > 0 ? "observed" : "covered_no_evidence");
            aggregate(alias, aliasMetrics.values());
            List<Map<String,Object>> detailRows = aliasMetrics.values().stream()
                .sorted(Comparator.comparing(MetricAccumulator::sortKey))
                .map(MetricAccumulator::toMap)
                .toList();

            for(Map<String,Object> detail: detailRows)
            {
                detail.put("alias_list_id", alias.get("alias_list_id"));
                detail.put("alias_list_name", alias.get("alias_list_name"));
            }

            if(includeBreakdown)
            {
                breakdown.put(aliasId, detailRows);
            }
        }

        return breakdown;
    }

    private static Map<Long,List<Map<String,Object>>> applyNoCoverageMetrics(List<Map<String,Object>> aliases,
                                                                              boolean includeBreakdown)
    {
        Map<Long,List<Map<String,Object>>> breakdown = new LinkedHashMap<>();

        for(Map<String,Object> alias: aliases)
        {
            long aliasId = number(alias.get("alias_id"));
            alias.put("coverage_source_count", 0);
            alias.put("observed_source_count", 0);
            alias.put("metrics_state", isSupportedIdentity(alias) ? "not_collected" : "unsupported");
            aggregate(alias, List.of());

            if(includeBreakdown)
            {
                breakdown.put(aliasId, List.of());
            }
        }

        return breakdown;
    }

    private static List<CoverageSource> loadCoverageSources(Connection connection, IdentityTargets targets)
        throws SQLException
    {
        return loadCoverageSources(connection, targets, true);
    }

    private static List<CoverageSource> loadCoverageSources(Connection connection, IdentityTargets targets,
                                                             boolean boundedRuntimeRequest)
        throws SQLException
    {
        StringBuilder trunkedSql = new StringBuilder("""
            WITH coverage AS (
                SELECT source.id AS radio_system_id, source.system_key AS radio_system_key,
                    source.protocol_code, source.p25_wacn AS wacn, source.p25_system_id AS system_id,
                    channel.id AS channel_id, channel.configuration_id,
                    coalesce(nullif(trim(config.site_name), ''), nullif(trim(config.name), '')) AS site_name,
                    nullif(trim(config.system_name), '') AS system_name,
                    config.alias_list_id, list.name AS alias_list_name
                FROM radio_system source
                JOIN receiver_channel channel ON channel.radio_system_id = source.id
                JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
                JOIN alias_list list ON list.id = config.alias_list_id
                WHERE config.channel_kind = 'TRUNKED'
                UNION
                SELECT source.id AS radio_system_id, source.system_key AS radio_system_key,
                    source.protocol_code, source.p25_wacn AS wacn, source.p25_system_id AS system_id,
                    channel.id AS channel_id, channel.configuration_id,
                    coalesce(nullif(trim(config.site_name), ''), nullif(trim(config.name), '')) AS site_name,
                    nullif(trim(config.system_name), '') AS system_name,
                    config.alias_list_id, list.name AS alias_list_name
                FROM radio_system source
                JOIN configuration_channel config ON config.configuration_id = source.configuration_id
                JOIN receiver_channel channel ON channel.configuration_id = config.configuration_id
                JOIN alias_list list ON list.id = config.alias_list_id
                WHERE config.channel_kind = 'TRUNKED'
            )
            SELECT * FROM coverage
            WHERE
            """);
        List<Object> trunkedParameters = new ArrayList<>();
        targets.appendCoveragePredicate(trunkedSql, trunkedParameters, "coverage.protocol_code",
            "coverage.alias_list_id");
        trunkedSql.append(" ORDER BY radio_system_id, channel_id");
        List<Map<String,Object>> trunked = queryCoverageRows(connection, trunkedSql.toString(),
            trunkedParameters, boundedRuntimeRequest ? MAX_COVERAGE_ROWS : null);

        if(boundedRuntimeRequest && trunked.size() > MAX_COVERAGE_ROWS)
        {
            throw new StatsApiException(413, "Alias coverage exceeds the bounded receiver limit");
        }

        StringBuilder conventionalSql = new StringBuilder("""
            SELECT channel.id AS channel_id, channel.configuration_id,
                coalesce(nullif(trim(config.site_name), ''), nullif(trim(config.name), '')) AS site_name,
                config.alias_list_id, list.name AS alias_list_name,
                nullif(trim(config.system_name), '') AS system_name,
                CASE WHEN config.decoder_type LIKE 'P25%' THEN 1
                     WHEN config.decoder_type = 'DMR' THEN 3
                     WHEN config.decoder_type = 'NXDN' THEN 4 END AS protocol_code
            FROM receiver_channel channel
            JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
            JOIN alias_list list ON list.id = config.alias_list_id
            WHERE config.channel_kind = 'CONVENTIONAL'
              AND (config.decoder_type LIKE 'P25%' OR config.decoder_type IN ('DMR', 'NXDN')) AND
            """);
        List<Object> conventionalParameters = new ArrayList<>();
        targets.appendCoveragePredicate(conventionalSql, conventionalParameters,
            "CASE WHEN config.decoder_type LIKE 'P25%' THEN 1 " +
                "WHEN config.decoder_type = 'DMR' THEN 3 WHEN config.decoder_type = 'NXDN' THEN 4 END",
            "config.alias_list_id");
        conventionalSql.append(" ORDER BY channel.id");
        Integer conventionalMaximum = boundedRuntimeRequest ? MAX_COVERAGE_ROWS - trunked.size() : null;
        List<Map<String,Object>> conventional = queryCoverageRows(connection, conventionalSql.toString(),
            conventionalParameters, conventionalMaximum);

        if(boundedRuntimeRequest && trunked.size() + conventional.size() > MAX_COVERAGE_ROWS)
        {
            throw new StatsApiException(413, "Alias coverage exceeds the bounded receiver limit");
        }

        Map<Long,CoverageSource> p25ById = new LinkedHashMap<>();
        Map<String,CoverageSource> trunkedByProjection = new LinkedHashMap<>();

        for(Map<String,Object> row: trunked)
        {
            long radioSystemId = number(row.get("radio_system_id"));
            int protocolCode = (int)number(row.get("protocol_code"));
            long aliasListId = number(row.get("alias_list_id"));
            CoverageSource source;

            if(protocolCode == 1)
            {
                //P25 aliases are deliberately resolved across every list assigned to the decoded system.
                source = p25ById.computeIfAbsent(radioSystemId, ignored -> CoverageSource.trunked(row));
            }
            else
            {
                //DMR/NXDN ownership is list-specific. Keep an explicit projection per list so one saved channel
                //cannot silently become the canonical alias source for every other channel on this radio system.
                String projectionKey = radioSystemId + "\u0000" + aliasListId;
                source = trunkedByProjection.computeIfAbsent(projectionKey,
                    ignored -> CoverageSource.trunked(row));
            }

            source.addAliasList(aliasListId, text(row.get("alias_list_name")));
        }

        List<CoverageSource> sources = new ArrayList<>(p25ById.values());
        sources.addAll(trunkedByProjection.values());

        for(Map<String,Object> row: conventional)
        {
            CoverageSource source = CoverageSource.conventional(row);
            source.addAliasList(number(row.get("alias_list_id")), text(row.get("alias_list_name")));
            sources.add(source);
        }

        return sources;
    }

    private static List<Map<String,Object>> queryCoverageRows(Connection connection, String sql,
                                                               List<Object> parameters, Integer maximum)
        throws SQLException
    {
        if(maximum != null)
        {
            List<Object> bounded = new ArrayList<>(parameters);
            bounded.add(maximum + 1);
            return queryRows(connection, sql + " LIMIT ?", bounded.toArray());
        }

        List<Map<String,Object>> rows = new ArrayList<>();
        int offset = 0;
        while(true)
        {
            List<Object> pageParameters = new ArrayList<>(parameters);
            pageParameters.add(MIGRATION_EVIDENCE_PAGE_ROWS);
            pageParameters.add(offset);
            List<Map<String,Object>> page = queryRows(connection, sql + " LIMIT ? OFFSET ?",
                pageParameters.toArray());
            rows.addAll(page);
            if(page.size() < MIGRATION_EVIDENCE_PAGE_ROWS)
            {
                return rows;
            }
            offset += page.size();
        }
    }

    private static boolean isEligible(Map<String,Object> alias, CoverageSource source)
    {
        int protocol = protocolCode(text(alias.get("protocol")));

        if(protocol != source.protocolCode)
        {
            return false;
        }

        long aliasListId = number(alias.get("alias_list_id"));
        return aliasListId > 0 && source.aliasListIds.contains(aliasListId);
    }

    /**
     * Rebuilds the persisted read model from retained compact evidence.  This is used by the adjacent database
     * migration only; normal reads and page changes never invoke it.
     */
    void rebuildAllActivitySummaries(Connection connection) throws SQLException
    {
        try(PreparedStatement delete = connection.prepareStatement("DELETE FROM alias_activity_summary"))
        {
            delete.executeUpdate();
        }

        List<Long> aliasListIds = new ArrayList<>();
        try(PreparedStatement statement = connection.prepareStatement("SELECT id FROM alias_list ORDER BY id");
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                aliasListIds.add(resultSet.getLong(1));
            }
        }

        long updatedAt = Math.max(1L, System.currentTimeMillis());
        String insertSql = """
            INSERT INTO alias_activity_summary(
                alias_id, alias_list_id, protocol_code, metrics_state,
                logical_call_count, recorded_logical_call_count, stream_submitted_logical_call_count,
                encrypted_logical_call_count, grant_observation_count, join_observation_count,
                emergency_observation_count, register_observation_count, logout_observation_count,
                denial_observation_count, data_observation_count, other_signaling_observation_count,
                signaling_observation_count, first_evidence_ms, last_evidence_ms, updated_at_ms
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

        try(PreparedStatement insert = connection.prepareStatement(insertSql))
        {
            for(long aliasListId: aliasListIds)
            {
                AliasActivitySnapshot snapshot = buildActivitySnapshot(connection, aliasListId);
                for(Map.Entry<Long,AliasActivityMetric> entry: snapshot.metrics.entrySet())
                {
                    AliasActivityMetric metric = entry.getValue();
                    int parameter = 1;
                    insert.setLong(parameter++, entry.getKey());
                    insert.setLong(parameter++, aliasListId);
                    insert.setInt(parameter++, metric.protocol);
                    insert.setString(parameter++, metric.state());
                    for(String field: PERSISTED_METRIC_FIELDS)
                    {
                        setNullableLong(insert, parameter++, metric.metric(field));
                    }
                    setNullableLong(insert, parameter++, metric.firstEvidenceMs);
                    setNullableLong(insert, parameter++, metric.lastEvidenceMs);
                    insert.setLong(parameter, updatedAt);
                    insert.addBatch();
                }
                insert.executeBatch();
            }
        }
    }

    private static void setNullableLong(PreparedStatement statement, int parameter, Long value) throws SQLException
    {
        if(value != null)
        {
            statement.setLong(parameter, value);
        }
        else
        {
            statement.setNull(parameter, java.sql.Types.INTEGER);
        }
    }

    private AliasActivitySnapshot buildActivitySnapshot(Connection connection, long aliasListId) throws SQLException
    {
        Map<Long,Integer> aliasProtocols = new LinkedHashMap<>();
        Set<Integer> protocols = new LinkedHashSet<>();

        try(PreparedStatement statement = connection.prepareStatement("""
            SELECT alias.id, alias.matcher_type, alias.protocol
            FROM alias
            WHERE alias.alias_list_id = ?
            ORDER BY alias.id
            """))
        {
            statement.setLong(1, aliasListId);

            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    String matcher = resultSet.getString("matcher_type");
                    int protocol = protocolCode(resultSet.getString("protocol"));
                    boolean supported = protocol > 0 && ("TALKGROUP".equals(matcher) ||
                        "TALKGROUP_RANGE".equals(matcher) || "RADIO_ID".equals(matcher) ||
                        "RADIO_ID_RANGE".equals(matcher));
                    aliasProtocols.put(resultSet.getLong("id"), supported ? protocol : 0);
                    if(supported)
                    {
                        protocols.add(protocol);
                    }
                }
            }
        }

        IdentityTargets coverageTarget = IdentityTargets.forAliasList(aliasListId, protocols);
        List<CoverageSource> sources = coverageTarget.isEmpty() ? List.of() :
            loadCoverageSources(connection, coverageTarget, false);
        Map<Integer,SourceAvailability> availability = sourceAvailability(sources);
        Map<Long,AliasActivityMetric> metrics = new LinkedHashMap<>(Math.max(16, aliasProtocols.size() * 4 / 3));

        for(Map.Entry<Long,Integer> entry: aliasProtocols.entrySet())
        {
            metrics.put(entry.getKey(), new AliasActivityMetric(entry.getValue(),
                availability.get(entry.getValue())));
        }

        if(!sources.isEmpty())
        {
            Map<Long,List<CoverageSource>> systemSources = sourceProjections(sources, true);
            Map<Long,List<CoverageSource>> channelSources = sourceProjections(sources, false);
            int[] evidenceRows = {0};
            applySnapshotTrunkedEvidence(connection, metrics, systemSources, evidenceRows);
            applySnapshotConventionalEvidence(connection, metrics, channelSources, evidenceRows);
        }

        return new AliasActivitySnapshot(aliasListId, System.currentTimeMillis(), Map.copyOf(metrics));
    }

    private static Map<Integer,SourceAvailability> sourceAvailability(List<CoverageSource> sources)
    {
        Map<Integer,SourceAvailability> availability = new HashMap<>();

        for(CoverageSource source: sources)
        {
            SourceAvailability current = availability.computeIfAbsent(source.protocolCode,
                ignored -> new SourceAvailability());
            current.coverageSourceCount++;
            current.trunked |= source.trunked;
            current.p25Trunked |= source.trunked && source.protocolCode == 1;
        }

        return availability;
    }

    private void applySnapshotTrunkedEvidence(Connection connection, Map<Long,AliasActivityMetric> metrics,
                                               Map<Long,List<CoverageSource>> sources, int[] evidenceRows)
        throws SQLException
    {
        if(sources.isEmpty())
        {
            return;
        }

        List<Map.Entry<Long,List<CoverageSource>>> entries = new ArrayList<>(sources.entrySet());
        for(int offset = 0; offset < entries.size(); offset += MIGRATION_SOURCE_PAGE_ROWS)
        {
            Map<Long,List<CoverageSource>> page = new LinkedHashMap<>();
            for(Map.Entry<Long,List<CoverageSource>> entry: entries.subList(offset,
                Math.min(entries.size(), offset + MIGRATION_SOURCE_PAGE_ROWS)))
            {
                page.put(entry.getKey(), entry.getValue());
            }
            applySnapshotTrunkedEvidencePage(connection, metrics, page, evidenceRows);
        }
    }

    private void applySnapshotTrunkedEvidencePage(Connection connection, Map<Long,AliasActivityMetric> metrics,
                                                   Map<Long,List<CoverageSource>> sources, int[] evidenceRows)
        throws SQLException
    {

        String sql = """
            SELECT summary.id AS identity_summary_id, summary.radio_system_id, summary.identity_kind_code,
                summary.identity_id, summary.identity_id AS canonical_identity_id,
                summary.home_wacn, summary.home_system_id,
                summary.first_seen_ms, summary.last_seen_ms, summary.logical_call_count,
                summary.recorded_output_count AS recorded_logical_call_count,
                summary.streamed_output_count AS stream_submitted_logical_call_count,
                summary.encrypted_logical_call_count,
                summary.grant_count AS grant_observation_count,
                summary.join_count AS join_observation_count,
                summary.emergency_count AS emergency_observation_count,
                summary.register_count AS register_observation_count,
                summary.logout_count AS logout_observation_count,
                summary.denial_count AS denial_observation_count,
                summary.data_count AS data_observation_count,
                %s AS other_signaling_observation_count,
                %s AS signaling_observation_count,
                source.protocol_code, source.system_key AS radio_system_key,
                source.p25_wacn AS wacn, source.p25_system_id AS system_id
            FROM radio_system_identity_summary summary
            JOIN radio_system source ON source.id = summary.radio_system_id
            WHERE summary.radio_system_id IN (%s)
              AND summary.identity_kind_code IN (1, 2, 3)
            ORDER BY summary.radio_system_id, summary.identity_kind_code, summary.identity_id,
                summary.home_wacn, summary.home_system_id
            """.formatted(OTHER_SIGNALING_SQL, SIGNALING_SQL, placeholders(sources.size()));
        processSnapshotEvidence(connection, sql, new ArrayList<>(sources.keySet()), sources, "radio_system_id",
            metrics, evidenceRows);
    }

    private void applySnapshotConventionalEvidence(Connection connection, Map<Long,AliasActivityMetric> metrics,
                                                    Map<Long,List<CoverageSource>> sources, int[] evidenceRows)
        throws SQLException
    {
        if(sources.isEmpty())
        {
            return;
        }

        List<Map.Entry<Long,List<CoverageSource>>> entries = new ArrayList<>(sources.entrySet());
        for(int offset = 0; offset < entries.size(); offset += MIGRATION_SOURCE_PAGE_ROWS)
        {
            Map<Long,List<CoverageSource>> page = new LinkedHashMap<>();
            for(Map.Entry<Long,List<CoverageSource>> entry: entries.subList(offset,
                Math.min(entries.size(), offset + MIGRATION_SOURCE_PAGE_ROWS)))
            {
                page.put(entry.getKey(), entry.getValue());
            }
            applySnapshotConventionalEvidencePage(connection, metrics, page, evidenceRows);
        }
    }

    private void applySnapshotConventionalEvidencePage(Connection connection,
                                                        Map<Long,AliasActivityMetric> metrics,
                                                        Map<Long,List<CoverageSource>> sources, int[] evidenceRows)
        throws SQLException
    {

        String sql = """
            SELECT bucket.channel_id, bucket.identity_kind_code, bucket.identity_id,
                min(bucket.bucket_start_ms) AS first_seen_ms,
                max(bucket.bucket_start_ms) AS last_seen_ms,
                sum(bucket.call_count) AS logical_call_count,
                sum(bucket.recorded_count) AS recorded_logical_call_count,
                sum(bucket.streamed_count) AS stream_submitted_logical_call_count,
                sum(bucket.encrypted_count) AS encrypted_logical_call_count,
                NULL AS grant_observation_count, NULL AS join_observation_count,
                NULL AS emergency_observation_count, NULL AS register_observation_count,
                NULL AS logout_observation_count, NULL AS denial_observation_count,
                NULL AS data_observation_count, NULL AS other_signaling_observation_count,
                NULL AS signaling_observation_count,
                CASE WHEN config.decoder_type LIKE 'P25%%' THEN 1
                     WHEN config.decoder_type = 'DMR' THEN 3
                     WHEN config.decoder_type = 'NXDN' THEN 4 END AS protocol_code
            FROM conventional_call_identity_bucket bucket
            JOIN receiver_channel channel ON channel.id = bucket.channel_id
            JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
            WHERE bucket.channel_id IN (%s) AND config.channel_kind = 'CONVENTIONAL'
              AND (config.decoder_type LIKE 'P25%%' OR config.decoder_type IN ('DMR', 'NXDN'))
              AND bucket.identity_kind_code IN (1, 2, 3)
            GROUP BY bucket.channel_id, bucket.identity_kind_code, bucket.identity_id, config.decoder_type
            ORDER BY bucket.channel_id, bucket.identity_kind_code, bucket.identity_id
            """.formatted(placeholders(sources.size()));
        processSnapshotEvidence(connection, sql, new ArrayList<>(sources.keySet()), sources, "channel_id",
            metrics, evidenceRows);
    }

    private void processSnapshotEvidence(Connection connection, String sql, List<Object> parameters,
                                         Map<Long,List<CoverageSource>> sources, String ownerColumn,
                                         Map<Long,AliasActivityMetric> metrics, int[] evidenceRows) throws SQLException
    {
        int offset = 0;

        while(true)
        {
            List<Object> pageParameters = new ArrayList<>(parameters);
            pageParameters.add(MIGRATION_EVIDENCE_PAGE_ROWS);
            pageParameters.add(offset);
            List<Map<String,Object>> rows = queryRows(connection, sql + " LIMIT ? OFFSET ?",
                pageParameters.toArray());
            evidenceRows[0] += rows.size();

            List<Map<String,Object>> projected = projectSnapshotEvidence(rows, sources, ownerColumn,
                evidenceRows);
            mResolver.resolveEvidenceAliasesForMigration(connection, projected);

            for(Map<String,Object> row: projected)
            {
                Long aliasId = nullableNumber(row.get("resolved_alias_id"));
                AliasActivityMetric metric = aliasId != null ? metrics.get(aliasId) : null;
                if(metric != null)
                {
                    metric.add(row);
                }
            }

            if(rows.size() < MIGRATION_EVIDENCE_PAGE_ROWS)
            {
                return;
            }

            offset += rows.size();
        }
    }

    private static List<Map<String,Object>> projectSnapshotEvidence(List<Map<String,Object>> rows,
                                                                    Map<Long,List<CoverageSource>> sources,
                                                                    String ownerColumn, int[] evidenceRows)
    {
        List<Map<String,Object>> projected = new ArrayList<>(rows.size());

        for(Map<String,Object> row: rows)
        {
            List<CoverageSource> projections = sources.getOrDefault(number(row.get(ownerColumn)), List.of());

            for(int index = 0; index < projections.size(); index++)
            {
                Map<String,Object> projection = index == 0 ? row : new LinkedHashMap<>(row);
                if(index > 0)
                {
                    evidenceRows[0]++;
                }
                projections.get(index).decorateEvidence(projection);
                projected.add(projection);
            }
        }

        return projected;
    }

    private void applyTrunkedEvidence(Connection connection, Map<Long,Map<String,MetricAccumulator>> metrics,
                                      Set<Long> radioSystemIds,
                                      Map<Long,List<CoverageSource>> sources, SourceIdentityTargets targets,
                                      EvidenceBudget budget) throws SQLException
    {
        if(radioSystemIds.isEmpty())
        {
            return;
        }

        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        targets.appendCte(sql, parameters, true);
        sql.append("""
            SELECT summary.radio_system_id, summary.identity_kind_code,
                summary.identity_id AS identity_id,
                summary.identity_id AS canonical_identity_id,
                summary.home_wacn, summary.home_system_id,
                summary.first_seen_ms, summary.last_seen_ms, summary.logical_call_count,
                summary.recorded_output_count AS recorded_logical_call_count,
                summary.streamed_output_count AS stream_submitted_logical_call_count,
                summary.encrypted_logical_call_count,
                summary.grant_count AS grant_observation_count,
                summary.join_count AS join_observation_count,
                summary.emergency_count AS emergency_observation_count,
                summary.register_count AS register_observation_count,
                summary.logout_count AS logout_observation_count,
                summary.denial_count AS denial_observation_count,
                summary.data_count AS data_observation_count,
                %s AS other_signaling_observation_count,
                %s AS signaling_observation_count,
                source.protocol_code, source.system_key AS radio_system_key,
                source.p25_wacn AS wacn, source.p25_system_id AS system_id
            FROM radio_system_identity_summary summary
            JOIN radio_system source ON source.id = summary.radio_system_id
            WHERE summary.radio_system_id IN (%s)
              AND
            """.formatted(OTHER_SIGNALING_SQL, SIGNALING_SQL, placeholders(radioSystemIds.size())));
        parameters.addAll(radioSystemIds);
        targets.appendPredicate(sql, "summary.radio_system_id", "summary.identity_kind_code",
            "summary.identity_id");
        sql.append("""
            ORDER BY 1, 2, 3, 5, 6
            LIMIT ?
            """);
        parameters.add(budget.queryLimit());
        List<Map<String,Object>> evidence = queryRows(connection, sql.toString(), parameters.toArray());
        budget.consume(evidence.size());
        evidence = projectEvidence(evidence, sources, "radio_system_id", targets, budget);

        mResolver.resolveEvidenceAliases(connection, evidence);
        applyEvidenceRows(evidence, metrics);
    }

    private static void applyEvidenceRows(List<Map<String,Object>> evidence,
                                          Map<Long,Map<String,MetricAccumulator>> metrics)
    {
        for(Map<String,Object> row: evidence)
        {
            Long aliasId = nullableNumber(row.get("resolved_alias_id"));
            String sourceKey = text(row.get("coverage_key"));

            if(aliasId == null || sourceKey == null)
            {
                continue;
            }

            MetricAccumulator accumulator = metrics.getOrDefault(aliasId, Map.of()).get(sourceKey);

            if(accumulator != null)
            {
                accumulator.addEvidence(row);
            }
        }
    }

    /**
     * Conventional call facts are owned by the exact saved channel. The shared hourly identity buckets cover P25,
     * DMR, and NXDN without borrowing an Alias List or identity from another channel.
     */
    private void applyConventionalEvidence(Connection connection,
                                           Map<Long,Map<String,MetricAccumulator>> metrics,
                                           Set<Long> channelIds, Map<Long,List<CoverageSource>> sources,
                                           SourceIdentityTargets targets, EvidenceBudget budget)
        throws SQLException
    {
        if(channelIds.isEmpty())
        {
            return;
        }

        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        targets.appendCte(sql, parameters, false);
        sql.append("""
            SELECT bucket.channel_id, bucket.identity_kind_code, bucket.identity_id,
                min(bucket.bucket_start_ms) AS first_seen_ms,
                max(bucket.bucket_start_ms) AS last_seen_ms,
                sum(bucket.call_count) AS logical_call_count,
                sum(bucket.recorded_count) AS recorded_logical_call_count,
                sum(bucket.streamed_count) AS stream_submitted_logical_call_count,
                sum(bucket.encrypted_count) AS encrypted_logical_call_count,
                NULL AS grant_observation_count, NULL AS join_observation_count,
                NULL AS emergency_observation_count, NULL AS register_observation_count,
                NULL AS logout_observation_count, NULL AS denial_observation_count,
                NULL AS data_observation_count, NULL AS other_signaling_observation_count,
                NULL AS signaling_observation_count,
                CASE WHEN config.decoder_type LIKE 'P25%%' THEN 1
                     WHEN config.decoder_type = 'DMR' THEN 3
                     WHEN config.decoder_type = 'NXDN' THEN 4 END AS protocol_code
            FROM conventional_call_identity_bucket bucket
            JOIN receiver_channel channel ON channel.id = bucket.channel_id
            JOIN configuration_channel config ON config.configuration_id = channel.configuration_id
            WHERE bucket.channel_id IN (%s) AND config.channel_kind = 'CONVENTIONAL'
              AND (config.decoder_type LIKE 'P25%%' OR config.decoder_type IN ('DMR', 'NXDN'))
              AND bucket.identity_kind_code IN (1, 2, 3)
              AND
            """.formatted(placeholders(channelIds.size())));
        parameters.addAll(channelIds);
        targets.appendPredicate(sql, "bucket.channel_id", "bucket.identity_kind_code", "bucket.identity_id");
        sql.append("""
            GROUP BY bucket.channel_id, bucket.identity_kind_code, bucket.identity_id, config.decoder_type
            ORDER BY bucket.channel_id, bucket.identity_kind_code, bucket.identity_id
            LIMIT ?
            """);
        parameters.add(budget.queryLimit());
        List<Map<String,Object>> evidence = queryRows(connection, sql.toString(), parameters.toArray());
        budget.consume(evidence.size());
        evidence = projectEvidence(evidence, sources, "channel_id", targets, budget);

        mResolver.resolveEvidenceAliases(connection, evidence);
        applyEvidenceRows(evidence, metrics);
    }

    private void applyRelationships(Connection connection, Map<Long,Map<String,MetricAccumulator>> metrics,
                                    Set<Long> radioSystemIds,
                                    Map<Long,List<CoverageSource>> sources, SourceIdentityTargets targets,
                                    EvidenceBudget budget) throws SQLException
    {
        if(radioSystemIds.isEmpty())
        {
            return;
        }

        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        targets.appendCte(sql, parameters, true);
        sql.append("""
            SELECT relationship.radio_system_id, 2 AS identity_kind_code,
                identity.identity_id AS identity_id,
                min(relationship.first_seen_ms) AS first_seen_ms,
                max(relationship.last_seen_ms) AS last_seen_ms,
                count(*) AS relationship_count,
                sum(CASE WHEN relationship.join_count > 0 THEN 1 ELSE 0 END) AS join_relationship_count,
                source.protocol_code, source.system_key AS radio_system_key,
                source.p25_wacn AS wacn, source.p25_system_id AS system_id,
                identity.identity_id AS canonical_identity_id, identity.home_wacn, identity.home_system_id
            FROM trunked_radio_group_summary relationship
            JOIN radio_system source ON source.id = relationship.radio_system_id
            JOIN radio_system_identity_summary identity
              ON identity.radio_system_id = relationship.radio_system_id
             AND identity.id = relationship.radio_identity_id
            WHERE relationship.radio_system_id IN (%s)
              AND
            """.formatted(placeholders(radioSystemIds.size())));
        parameters.addAll(radioSystemIds);
        targets.appendPredicate(sql, "relationship.radio_system_id", "2",
            "identity.identity_id");
        sql.append("""
            GROUP BY relationship.radio_system_id, identity.id, identity.identity_id,
                identity.home_wacn, identity.home_system_id,
                source.protocol_code, source.system_key, source.p25_wacn, source.p25_system_id

            UNION ALL

            SELECT relationship.radio_system_id, relationship.group_kind_code AS identity_kind_code,
                target.identity_id AS identity_id,
                min(relationship.first_seen_ms) AS first_seen_ms,
                max(relationship.last_seen_ms) AS last_seen_ms,
                count(*) AS relationship_count,
                sum(CASE WHEN relationship.join_count > 0 THEN 1 ELSE 0 END) AS join_relationship_count,
                source.protocol_code, source.system_key AS radio_system_key,
                source.p25_wacn AS wacn, source.p25_system_id AS system_id,
                target.identity_id AS canonical_identity_id, target.home_wacn, target.home_system_id
            FROM trunked_radio_group_summary relationship
            JOIN radio_system source ON source.id = relationship.radio_system_id
            JOIN radio_system_identity_summary target
             ON target.radio_system_id = relationship.radio_system_id
             AND target.id = relationship.group_identity_id
            WHERE relationship.radio_system_id IN (%s)
              AND
            """.formatted(placeholders(radioSystemIds.size())));
        parameters.addAll(radioSystemIds);
        targets.appendPredicate(sql, "relationship.radio_system_id", "relationship.group_kind_code",
            "target.identity_id");
        sql.append("""
            GROUP BY relationship.radio_system_id, relationship.group_kind_code, target.id, target.identity_id,
                target.home_wacn, target.home_system_id,
                source.protocol_code, source.system_key, source.p25_wacn, source.p25_system_id
            ORDER BY 1, 2, 3
            LIMIT ?
            """);
        parameters.add(budget.queryLimit());
        List<Map<String,Object>> identities = queryRows(connection, sql.toString(), parameters.toArray());
        budget.consume(identities.size());

        identities = projectEvidence(identities, sources, "radio_system_id", targets, budget);

        mResolver.resolveEvidenceAliases(connection, identities);

        for(Map<String,Object> row: identities)
        {
            MetricAccumulator accumulator = accumulator(metrics, row);

            if(accumulator != null)
            {
                accumulator.relationshipCount += number(row.get("relationship_count"));
                accumulator.joinRelationshipCount += number(row.get("join_relationship_count"));
                accumulator.observe(row.get("first_seen_ms"), row.get("last_seen_ms"));
            }
        }
    }

    private void applyCurrentAffiliations(Connection connection,
                                           Map<Long,Map<String,MetricAccumulator>> metrics, Set<Long> radioSystemIds,
                                           Map<Long,List<CoverageSource>> sources, SourceIdentityTargets targets,
                                           EvidenceBudget budget) throws SQLException
    {
        Set<Long> p25Systems = new LinkedHashSet<>();

        for(long radioSystemId: radioSystemIds)
        {
            if(sources.getOrDefault(radioSystemId, List.of()).stream()
                .anyMatch(source -> source.protocolCode == 1))
            {
                p25Systems.add(radioSystemId);
            }
        }

        if(p25Systems.isEmpty())
        {
            return;
        }

        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        targets.appendCte(sql, parameters, true);
        sql.append("""
            SELECT source.id AS radio_system_id, 2 AS identity_kind_code,
                coalesce(affiliation.radio_observed_local_id, identity.identity_id) AS identity_id,
                max(affiliation.confirmed_at_ms) AS updated_at_ms,
                count(*) AS current_affiliation_count, source.protocol_code,
                source.system_key AS radio_system_key,
                source.p25_wacn AS wacn, source.p25_system_id AS system_id,
                identity.identity_id AS canonical_identity_id, identity.home_wacn, identity.home_system_id
            FROM radio_system source
            JOIN trunked_radio_affiliation affiliation ON affiliation.radio_system_id = source.id
            JOIN radio_system_identity_summary identity
              ON identity.radio_system_id = affiliation.radio_system_id
             AND identity.id = affiliation.radio_identity_id
            WHERE source.id IN (%s)
              AND
            """.formatted(placeholders(p25Systems.size())));
        parameters.addAll(p25Systems);
        targets.appendPredicate(sql, "source.id", "2",
            "coalesce(affiliation.radio_observed_local_id, identity.identity_id)");
        sql.append("""
            GROUP BY source.id, identity.id, identity.identity_id,
                identity.home_wacn, identity.home_system_id, affiliation.radio_observed_local_id,
                source.protocol_code, source.system_key, source.p25_wacn, source.p25_system_id

            UNION ALL

            SELECT source.id AS radio_system_id, 1 AS identity_kind_code,
                coalesce(affiliation.talkgroup_observed_local_id, target.identity_id) AS identity_id,
                max(affiliation.confirmed_at_ms) AS updated_at_ms,
                count(*) AS current_affiliation_count, source.protocol_code,
                source.system_key AS radio_system_key,
                source.p25_wacn AS wacn, source.p25_system_id AS system_id,
                target.identity_id AS canonical_identity_id, target.home_wacn, target.home_system_id
            FROM radio_system source
            JOIN trunked_radio_affiliation affiliation ON affiliation.radio_system_id = source.id
            LEFT JOIN radio_system_identity_summary target
             ON target.radio_system_id = source.id
             AND target.id = affiliation.talkgroup_identity_id
            WHERE source.id IN (%s)
              AND
            """.formatted(placeholders(p25Systems.size())));
        parameters.addAll(p25Systems);
        targets.appendPredicate(sql, "source.id", "1",
            "coalesce(affiliation.talkgroup_observed_local_id, target.identity_id)");
        sql.append("""
            GROUP BY source.id, target.id, target.identity_id,
                target.home_wacn, target.home_system_id, affiliation.talkgroup_observed_local_id,
                source.protocol_code, source.system_key, source.p25_wacn, source.p25_system_id
            ORDER BY 1, 2, 3
            LIMIT ?
            """);
        parameters.add(budget.queryLimit());
        List<Map<String,Object>> identities = queryRows(connection, sql.toString(), parameters.toArray());
        budget.consume(identities.size());

        identities = projectEvidence(identities, sources, "radio_system_id", targets, budget);

        mResolver.resolveEvidenceAliases(connection, identities);

        for(Map<String,Object> row: identities)
        {
            MetricAccumulator accumulator = accumulator(metrics, row);

            if(accumulator != null && accumulator.currentAffiliationCount != null)
            {
                accumulator.currentAffiliationCount += number(row.get("current_affiliation_count"));
                accumulator.observe(row.get("updated_at_ms"), row.get("updated_at_ms"));
            }
        }
    }

    private static List<Map<String,Object>> projectEvidence(List<Map<String,Object>> rows,
                                                            Map<Long,List<CoverageSource>> sources,
                                                            String radioSystemIdColumn, SourceIdentityTargets targets,
                                                            EvidenceBudget budget)
    {
        List<Map<String,Object>> projected = new ArrayList<>(rows.size());

        for(Map<String,Object> row: rows)
        {
            List<CoverageSource> projections = sources.getOrDefault(number(row.get(radioSystemIdColumn)), List.of()).stream()
                .filter(source -> targets.matches(row, source))
                .toList();

            for(int index = 0; index < projections.size(); index++)
            {
                Map<String,Object> projection;

                if(index == 0)
                {
                    projection = row;
                }
                else
                {
                    budget.consume(1);
                    projection = new LinkedHashMap<>(row);
                }

                projections.get(index).decorateEvidence(projection);
                projected.add(projection);
            }
        }

        return projected;
    }

    private static MetricAccumulator accumulator(Map<Long,Map<String,MetricAccumulator>> metrics,
                                                  Map<String,Object> row)
    {
        Long aliasId = nullableNumber(row.get("resolved_alias_id"));
        String sourceKey = text(row.get("coverage_key"));
        return aliasId != null && sourceKey != null ?
            metrics.getOrDefault(aliasId, Map.of()).get(sourceKey) : null;
    }

    private static void aggregate(Map<String,Object> alias, java.util.Collection<MetricAccumulator> rows)
    {
        for(String field: METRIC_FIELDS)
        {
            boolean available = false;
            long total = 0;

            for(MetricAccumulator row: rows)
            {
                Long value = row.metric(field);

                if(value != null)
                {
                    available = true;
                    total += value;
                }
            }

            alias.put(field, available ? total : null);
        }

        Long first = rows.stream().map(row -> row.firstEvidenceMs).filter(java.util.Objects::nonNull)
            .min(Long::compareTo).orElse(null);
        Long last = rows.stream().map(row -> row.lastEvidenceMs).filter(java.util.Objects::nonNull)
            .max(Long::compareTo).orElse(null);
        alias.put("first_evidence_ms", first);
        alias.put("last_evidence_ms", last);
    }

    private static boolean isSupportedIdentity(Map<String,Object> alias)
    {
        String type = text(alias.get("identity_type"));
        return ("talkgroup".equals(type) || "radio".equals(type)) && protocolCode(text(alias.get("protocol"))) > 0;
    }

    private static int protocolCode(String protocol)
    {
        if("APCO25".equals(protocol) || "APCO25_PHASE2".equals(protocol))
        {
            return 1;
        }
        else if("DMR".equals(protocol))
        {
            return 3;
        }
        else if("NXDN".equals(protocol))
        {
            return 4;
        }

        return 0;
    }

    private static int identityKindCode(Map<String,Object> alias)
    {
        return "radio".equals(text(alias.get("identity_type"))) ? 2 : 1;
    }

    private static IdentityRange identityRange(Map<String,Object> alias)
    {
        Long minimum;
        Long maximum;
        String matcher = text(alias.get("matcher_type"));

        if("TALKGROUP_RANGE".equals(matcher) || "RADIO_ID_RANGE".equals(matcher))
        {
            minimum = nullableNumber(alias.get("min_value"));
            maximum = nullableNumber(alias.get("max_value"));
        }
        else
        {
            minimum = nullableNumber(alias.get("value"));
            maximum = minimum;
        }

        return minimum != null && maximum != null && minimum >= 0 && maximum >= minimum ?
            new IdentityRange(minimum, maximum) : null;
    }

    private static List<IdentityRange> mergeRanges(List<IdentityRange> ranges)
    {
        List<IdentityRange> sorted = ranges.stream()
            .sorted(Comparator.comparingLong(IdentityRange::minimum)
                .thenComparingLong(IdentityRange::maximum))
            .toList();
        List<IdentityRange> merged = new ArrayList<>();

        for(IdentityRange range: sorted)
        {
            if(merged.isEmpty())
            {
                merged.add(range);
                continue;
            }

            IdentityRange previous = merged.getLast();

            if(range.minimum() <= previous.maximum() ||
                previous.maximum() != Long.MAX_VALUE && range.minimum() == previous.maximum() + 1)
            {
                merged.set(merged.size() - 1,
                    new IdentityRange(previous.minimum(), Math.max(previous.maximum(), range.maximum())));
            }
            else
            {
                merged.add(range);
            }
        }

        return List.copyOf(merged);
    }

    private static Map<Long,List<CoverageSource>> sourceProjections(List<CoverageSource> sources, boolean trunked)
    {
        Map<Long,List<CoverageSource>> result = new HashMap<>();

        for(CoverageSource source: sources)
        {
            if(source.trunked == trunked)
            {
                result.computeIfAbsent(source.numericId, ignored -> new ArrayList<>()).add(source);
            }
        }

        return result;
    }

    private static String matcherLabel(String matcher)
    {
        if(matcher == null)
        {
            return "Unknown";
        }

        return switch(matcher)
        {
            case "TALKGROUP" -> "Talkgroup";
            case "TALKGROUP_RANGE" -> "Talkgroup range";
            case "RADIO_ID" -> "Radio ID";
            case "RADIO_ID_RANGE" -> "Radio ID range";
            case "UNIT_STATUS" -> "Unit status";
            case "TONES" -> "Tones";
            default -> matcher.replace('_', ' ');
        };
    }

    private static String identifierDisplay(Map<String,Object> row)
    {
        String matcher = text(row.get("matcher_type"));

        if("TALKGROUP_RANGE".equals(matcher) || "RADIO_ID_RANGE".equals(matcher))
        {
            return displayNumber(row.get("min_value")) + "–" + displayNumber(row.get("max_value"));
        }

        Object value = row.get("value") != null ? row.get("value") :
            row.get("numeric_value") != null ? row.get("numeric_value") :
                row.get("text_value") != null ? row.get("text_value") : row.get("tone_sequence");
        return value != null ? String.valueOf(value) : "";
    }

    private static String displayNumber(Object value)
    {
        return value instanceof Number number ? Long.toString(number.longValue()) : "";
    }

    private static String placeholders(int count)
    {
        return String.join(",", java.util.Collections.nCopies(count, "?"));
    }

    private static String text(Object value)
    {
        return value instanceof String string && !string.isBlank() ? string : null;
    }

    private static String lower(String value)
    {
        return value != null ? value.toLowerCase(Locale.ROOT) : null;
    }

    private static long number(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static Long nullableNumber(Object value)
    {
        return value instanceof Number number ? number.longValue() : null;
    }

    private record AliasActivitySnapshot(long aliasListId, long createdAtMillis,
                                         Map<Long,AliasActivityMetric> metrics) {}

    private static final class SourceAvailability
    {
        private int coverageSourceCount;
        private boolean trunked;
        private boolean p25Trunked;
    }

    /** One-time migration accumulator for retained evidence belonging to an alias. */
    private static final class AliasActivityMetric
    {
        private final int protocol;
        private final boolean supported;
        private final int coverageSourceCount;
        private final boolean trunked;
        private final boolean p25Trunked;
        private Set<String> observedSources;
        private long callCount;
        private long recordedCount;
        private long streamedCount;
        private long encryptedCount;
        private long grantCount;
        private long joinCount;
        private long emergencyCount;
        private long registerCount;
        private long logoutCount;
        private long denialCount;
        private long dataCount;
        private long otherSignalingCount;
        private long signalingCount;
        private long relationshipCount;
        private long joinRelationshipCount;
        private long currentAffiliationCount;
        private Long firstEvidenceMs;
        private Long lastEvidenceMs;

        private AliasActivityMetric(int protocol, SourceAvailability availability)
        {
            this.protocol = protocol;
            supported = protocol > 0;
            coverageSourceCount = availability != null ? availability.coverageSourceCount : 0;
            trunked = availability != null && availability.trunked;
            p25Trunked = availability != null && availability.p25Trunked;
        }

        private void add(Map<String,Object> row)
        {
            callCount += number(row.get("logical_call_count"));
            recordedCount += number(row.get("recorded_logical_call_count"));
            streamedCount += number(row.get("stream_submitted_logical_call_count"));
            encryptedCount += number(row.get("encrypted_logical_call_count"));
            grantCount += number(row.get("grant_observation_count"));
            joinCount += number(row.get("join_observation_count"));
            emergencyCount += number(row.get("emergency_observation_count"));
            registerCount += number(row.get("register_observation_count"));
            logoutCount += number(row.get("logout_observation_count"));
            denialCount += number(row.get("denial_observation_count"));
            dataCount += number(row.get("data_observation_count"));
            otherSignalingCount += number(row.get("other_signaling_observation_count"));
            signalingCount += number(row.get("signaling_observation_count"));
            relationshipCount += number(row.get("relationship_count"));
            joinRelationshipCount += number(row.get("join_relationship_count"));
            currentAffiliationCount += number(row.get("current_affiliation_count"));
            Long first = nullableNumber(row.get("first_seen_ms"));
            Long last = nullableNumber(row.get("last_seen_ms"));
            Long updated = nullableNumber(row.get("updated_at_ms"));
            first = first != null ? first : updated;
            last = last != null ? last : updated;
            firstEvidenceMs = first != null && (firstEvidenceMs == null || first < firstEvidenceMs) ? first :
                firstEvidenceMs;
            lastEvidenceMs = last != null && (lastEvidenceMs == null || last > lastEvidenceMs) ? last :
                lastEvidenceMs;

            if(first != null || last != null || number(row.get("relationship_count")) > 0 ||
                number(row.get("current_affiliation_count")) > 0)
            {
                if(observedSources == null)
                {
                    observedSources = new HashSet<>();
                }
                String source = text(row.get("coverage_key"));
                if(source != null)
                {
                    observedSources.add(source);
                }
            }
        }

        private String state()
        {
            return !supported ? "unsupported" :
                observedSources != null && !observedSources.isEmpty() ? "observed" : "not_collected";
        }

        private Object value(String field)
        {
            return switch(field)
            {
                case "first_evidence_ms" -> firstEvidenceMs;
                case "last_evidence_ms" -> lastEvidenceMs;
                default -> metric(field);
            };
        }

        private Long metric(String field)
        {
            if(!supported || coverageSourceCount == 0)
            {
                return null;
            }

            return switch(field)
            {
                case "logical_call_count" -> callCount;
                case "recorded_logical_call_count" -> recordedCount;
                case "stream_submitted_logical_call_count" -> streamedCount;
                case "encrypted_logical_call_count" -> encryptedCount;
                case "grant_observation_count" -> p25Trunked ? grantCount : null;
                case "join_observation_count" -> trunked ? joinCount : null;
                case "emergency_observation_count" -> trunked ? emergencyCount : null;
                case "register_observation_count" -> trunked ? registerCount : null;
                case "logout_observation_count" -> trunked ? logoutCount : null;
                case "denial_observation_count" -> trunked ? denialCount : null;
                case "data_observation_count" -> trunked ? dataCount : null;
                case "other_signaling_observation_count" -> trunked ? otherSignalingCount : null;
                case "signaling_observation_count" -> trunked ? signalingCount : null;
                case "relationship_count" -> trunked ? relationshipCount : null;
                case "join_relationship_count" -> trunked ? joinRelationshipCount : null;
                case "current_affiliation_count" -> p25Trunked ? currentAffiliationCount : null;
                default -> null;
            };
        }
    }

    @FunctionalInterface
    interface EnrichmentOperation<T>
    {
        T execute() throws SQLException;
    }

    /**
     * Alias metrics touch several compact summary tables.  A small process-wide, fail-fast gate prevents otherwise
     * bounded requests from multiplying their working sets under concurrent browser refreshes or exports.
     */
    static final class EnrichmentAdmission
    {
        private final Semaphore mPermits;

        EnrichmentAdmission(int permits)
        {
            if(permits <= 0)
            {
                throw new IllegalArgumentException("permits must be positive");
            }

            mPermits = new Semaphore(permits, true);
        }

        <T> T execute(EnrichmentOperation<T> operation) throws SQLException
        {
            if(!mPermits.tryAcquire())
            {
                throw new StatsApiException(429, "alias_enrichment_busy",
                    "Alias metrics are busy; retry the request");
            }

            try
            {
                return operation.execute();
            }
            finally
            {
                mPermits.release();
            }
        }
    }

    /**
     * Allocation budget for one sequential summary-source query and its Alias List projections. Each source releases
     * its materialized rows before the next source is queried, so sharing one budget across all sources rejects safe
     * multi-system requests without reducing peak memory.
     */
    private static final class EvidenceBudget
    {
        private int mRemaining;

        private EvidenceBudget(int maximumRows)
        {
            mRemaining = maximumRows;
        }

        private int queryLimit()
        {
            return mRemaining + 1;
        }

        private void consume(int rows)
        {
            if(rows > mRemaining)
            {
                throw new StatsApiException(413, "Alias evidence exceeds the bounded query limit");
            }

            mRemaining -= rows;
        }
    }

    private record TargetKey(int protocolCode, int identityKindCode) {}

    private record IdentityRange(long minimum, long maximum) {}

    /**
     * Compact, merged identity ranges and assigned Alias List IDs derived solely from the alias rows in this response.
     * Every evidence query applies these predicates in SQLite before allocating result maps.
     */
    private static final class IdentityTargets
    {
        private final Map<Integer,Set<Long>> mAliasListIds;

        private IdentityTargets(Map<Integer,Set<Long>> aliasListIds)
        {
            mAliasListIds = aliasListIds;
        }

        private static IdentityTargets from(List<Map<String,Object>> aliases)
        {
            Map<TargetKey,List<IdentityRange>> ranges = new LinkedHashMap<>();
            Map<Integer,Set<Long>> aliasListIds = new LinkedHashMap<>();

            for(Map<String,Object> alias: aliases)
            {
                if(!isSupportedIdentity(alias))
                {
                    continue;
                }

                int protocol = protocolCode(text(alias.get("protocol")));
                long aliasListId = number(alias.get("alias_list_id"));

                if(aliasListId > 0)
                {
                    aliasListIds.computeIfAbsent(protocol, ignored -> new LinkedHashSet<>()).add(aliasListId);
                }

                IdentityRange range = identityRange(alias);

                if(range == null)
                {
                    continue;
                }

                addRange(ranges, new TargetKey(protocol, identityKindCode(alias)),
                    range.minimum(), range.maximum());
            }

            int listCount = aliasListIds.values().stream().mapToInt(Set::size).sum();

            if(listCount > MAX_TARGET_ALIAS_LISTS)
            {
                throw new StatsApiException(413, "Alias enrichment exceeds the bounded Alias List limit");
            }

            int rangeCount = 0;

            for(Map.Entry<TargetKey,List<IdentityRange>> entry: ranges.entrySet())
            {
                List<IdentityRange> result = mergeRanges(entry.getValue());
                rangeCount += result.size();
            }

            if(rangeCount > MAX_TARGET_RANGES)
            {
                throw new StatsApiException(413, "Alias enrichment exceeds the bounded identity-range limit");
            }

            return new IdentityTargets(aliasListIds);
        }

        /**
         * A catalog request for one Alias List normally spans many bounded metric batches.  When every supported
         * identity has the same protocol and Alias List, its receiver coverage is identical for every batch and can
         * be loaded once without changing the coverage row bound.  Mixed selections retain the existing per-batch
         * behavior.
         */
        private static IdentityTargets singleCoverageTarget(List<Map<String,Object>> aliases)
        {
            Map<Integer,Set<Long>> aliasListIds = new LinkedHashMap<>();
            int targetCount = 0;

            for(Map<String,Object> alias: aliases)
            {
                if(!isSupportedIdentity(alias))
                {
                    continue;
                }

                long aliasListId = number(alias.get("alias_list_id"));

                if(aliasListId <= 0)
                {
                    continue;
                }

                int protocol = protocolCode(text(alias.get("protocol")));
                Set<Long> protocolLists = aliasListIds.computeIfAbsent(protocol,
                    ignored -> new LinkedHashSet<>());

                if(protocolLists.add(aliasListId) && ++targetCount > 1)
                {
                    return null;
                }
            }

            return new IdentityTargets(aliasListIds);
        }

        private static IdentityTargets forAliasList(long aliasListId, Set<Integer> protocols)
        {
            Map<Integer,Set<Long>> aliasListIds = new LinkedHashMap<>();
            for(Integer protocol: protocols)
            {
                if(protocol != null && protocol > 0)
                {
                    aliasListIds.put(protocol, Set.of(aliasListId));
                }
            }
            return new IdentityTargets(aliasListIds);
        }

        private boolean isEmpty()
        {
            return mAliasListIds.isEmpty();
        }

        private static void addRange(Map<TargetKey,List<IdentityRange>> ranges, TargetKey key,
                                     long minimum, long maximum)
        {
            ranges.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new IdentityRange(minimum, maximum));
        }

        private void appendCoveragePredicate(StringBuilder sql, List<Object> parameters,
                                             String protocolColumn, String aliasListColumn)
        {
            if(mAliasListIds.isEmpty())
            {
                sql.append("0 ");
                return;
            }

            sql.append('(');
            boolean first = true;

            for(Map.Entry<Integer,Set<Long>> entry: mAliasListIds.entrySet())
            {
                if(!first)
                {
                    sql.append(" OR ");
                }

                sql.append('(').append(protocolColumn).append(" = ? AND ").append(aliasListColumn)
                    .append(" IN (").append(placeholders(entry.getValue().size())).append("))");
                parameters.add(entry.getKey());
                parameters.addAll(entry.getValue());
                first = false;
            }

            sql.append(')');
            sql.append(' ');
        }

    }

    private record SourceTargetKey(boolean trunked, long radioSystemId, Long projectionAliasListId,
                                   int identityKindCode) {}

    private record SqlSourceTarget(long radioSystemId, int identityKindCode, long minimum, long maximum) {}

    /**
     * Correlates each selected alias range to only the receiver sources where that alias is eligible.  Evidence SQL
     * consumes this bounded relation as a VALUES CTE, avoiding the protocol-wide list/range cross product that could
     * otherwise fill the response budget with identities from the wrong Alias List.
     */
    private static final class SourceIdentityTargets
    {
        private static final String CTE_NAME = "requested_identity";
        private final Map<SourceTargetKey,List<IdentityRange>> mRanges;

        private SourceIdentityTargets(Map<SourceTargetKey,List<IdentityRange>> ranges)
        {
            mRanges = ranges;
        }

        private static SourceIdentityTargets from(List<Map<String,Object>> aliases,
                                                  Map<Long,Map<String,CoverageSource>> coverage)
        {
            Map<SourceTargetKey,List<IdentityRange>> ranges = new LinkedHashMap<>();

            for(Map<String,Object> alias: aliases)
            {
                IdentityRange range = identityRange(alias);

                if(range == null || !isSupportedIdentity(alias))
                {
                    continue;
                }

                long aliasId = number(alias.get("alias_id"));
                int kind = identityKindCode(alias);

                for(CoverageSource source: coverage.getOrDefault(aliasId, Map.of()).values())
                {
                    Long projectionAliasListId = source.trunked && source.protocolCode == 1 ? null :
                        nullableNumber(alias.get("alias_list_id"));
                    SourceTargetKey key = new SourceTargetKey(source.trunked, source.numericId,
                        projectionAliasListId, kind);
                    ranges.computeIfAbsent(key, ignored -> new ArrayList<>()).add(range);
                }
            }

            Map<SourceTargetKey,List<IdentityRange>> merged = new LinkedHashMap<>();
            int rangeCount = 0;

            for(Map.Entry<SourceTargetKey,List<IdentityRange>> entry: ranges.entrySet())
            {
                List<IdentityRange> result = mergeRanges(entry.getValue());
                rangeCount += result.size();

                if(rangeCount > MAX_SOURCE_TARGET_RANGES)
                {
                    throw new StatsApiException(413,
                        "Alias evidence exceeds the bounded source and identity-range limit");
                }

                merged.put(entry.getKey(), result);
            }

            return new SourceIdentityTargets(Map.copyOf(merged));
        }

        private void appendCte(StringBuilder sql, List<Object> parameters, boolean trunked)
        {
            Set<SqlSourceTarget> targets = new LinkedHashSet<>();

            for(Map.Entry<SourceTargetKey,List<IdentityRange>> entry: mRanges.entrySet())
            {
                if(entry.getKey().trunked() == trunked)
                {
                    for(IdentityRange range: entry.getValue())
                    {
                        targets.add(new SqlSourceTarget(entry.getKey().radioSystemId(),
                            entry.getKey().identityKindCode(), range.minimum(), range.maximum()));
                    }
                }
            }

            sql.append("WITH ").append(CTE_NAME)
                .append("(radio_system_id, identity_kind_code, minimum, maximum) AS (");

            if(targets.isEmpty())
            {
                sql.append("SELECT NULL, NULL, NULL, NULL WHERE 0");
            }
            else
            {
                sql.append("VALUES ").append(String.join(",",
                    java.util.Collections.nCopies(targets.size(), "(?,?,?,?)")));

                for(SqlSourceTarget target: targets)
                {
                    parameters.add(target.radioSystemId());
                    parameters.add(target.identityKindCode());
                    parameters.add(target.minimum());
                    parameters.add(target.maximum());
                }
            }

            sql.append(") ");
        }

        private void appendPredicate(StringBuilder sql, String ownerColumn, String kindExpression,
                                     String identityExpression)
        {
            sql.append("EXISTS (SELECT 1 FROM ").append(CTE_NAME).append(" target WHERE target.radio_system_id = ")
                .append(ownerColumn).append(" AND (target.identity_kind_code = ").append(kindExpression)
                .append(" OR (target.identity_kind_code = 1 AND ").append(kindExpression)
                .append(" = 3)) AND ").append(identityExpression)
                .append(" BETWEEN target.minimum AND target.maximum) ");
        }

        private boolean matches(Map<String,Object> row, CoverageSource source)
        {
            int kind = (int)number(row.get("identity_kind_code"));
            kind = kind == 3 ? 1 : kind;
            long identity = number(row.get("identity_id"));
            Long projectionAliasListId = source.trunked && source.protocolCode == 1 ? null :
                source.canonicalAliasListId;
            List<IdentityRange> ranges = mRanges.getOrDefault(new SourceTargetKey(source.trunked,
                source.numericId, projectionAliasListId, kind), List.of());
            return ranges.stream().anyMatch(range -> identity >= range.minimum() && identity <= range.maximum());
        }
    }

    private static final class CoverageSource
    {
        private final String key;
        private final long numericId;
        private final Long radioSystemId;
        private final Long channelId;
        private final boolean trunked;
        private final int protocolCode;
        private final String protocol;
        private final String systemKey;
        private final String configurationId;
        private final String systemName;
        private final String siteName;
        private final Long wacn;
        private final Long systemId;
        private final Set<Long> aliasListIds = new HashSet<>();
        private Long canonicalAliasListId;
        private String canonicalAliasList;

        private CoverageSource(String key, long numericId, Long radioSystemId, Long channelId, boolean trunked,
                              int protocolCode, String protocol,
                              String systemKey, String configurationId, String systemName, String siteName,
                              Long wacn, Long systemId)
        {
            this.key = key;
            this.numericId = numericId;
            this.radioSystemId = radioSystemId;
            this.channelId = channelId;
            this.trunked = trunked;
            this.protocolCode = protocolCode;
            this.protocol = protocol;
            this.systemKey = systemKey;
            this.configurationId = configurationId;
            this.systemName = systemName;
            this.siteName = siteName;
            this.wacn = wacn;
            this.systemId = systemId;
        }

        private static CoverageSource trunked(Map<String,Object> row)
        {
            long id = number(row.get("radio_system_id"));
            int protocolCode = (int)number(row.get("protocol_code"));
            boolean linkedP25 = protocolCode == 1;
            return new CoverageSource("radio-system:" + id, id, id,
                linkedP25 ? null : nullableNumber(row.get("channel_id")), true, protocolCode,
                switch(protocolCode) { case 1 -> "P25"; case 3 -> "DMR"; case 4 -> "NXDN"; default -> "Unknown"; },
                text(row.get("radio_system_key")), linkedP25 ? null : text(row.get("configuration_id")),
                text(row.get("system_name")), linkedP25 ? null : text(row.get("site_name")), nullableNumber(row.get("wacn")),
                nullableNumber(row.get("system_id")));
        }

        private static CoverageSource conventional(Map<String,Object> row)
        {
            long id = number(row.get("channel_id"));
            int protocolCode = (int)number(row.get("protocol_code"));
            String protocol = switch(protocolCode)
            {
                case 1 -> "P25";
                case 3 -> "DMR";
                case 4 -> "NXDN";
                default -> "Unknown";
            };
            return new CoverageSource("channel:" + id, id, null, id, false, protocolCode, protocol, null,
                text(row.get("configuration_id")), text(row.get("system_name")),
                text(row.get("site_name")), null, null);
        }

        private void addAliasList(long aliasListId, String aliasList)
        {
            if(aliasListId > 0 && aliasListIds.add(aliasListId))
            {
                if(aliasListIds.size() == 1)
                {
                    canonicalAliasListId = aliasListId;
                    canonicalAliasList = aliasList;
                }
                else
                {
                    //A shared P25 system owns no Alias List. Preserve every applicable list for matching, but do not
                    //present whichever channel happened to be read first as the system's canonical list.
                    canonicalAliasListId = null;
                    canonicalAliasList = null;
                }
            }
        }

        private void decorateEvidence(Map<String,Object> row)
        {
            row.put("coverage_key", key);
            row.put("topology", trunked ? "TRUNKED" : "CONVENTIONAL");
            row.put("protocol_code", protocolCode);
            row.put("alias_list_id", canonicalAliasListId);
            row.put("alias_list_name", canonicalAliasList);
            row.put("radio_system_key", systemKey);
            row.put("wacn", wacn);
            row.put("system_id", systemId);
        }
    }

    private static final class MetricAccumulator
    {
        private final CoverageSource source;
        private long callCount;
        private Long recordedCount;
        private Long streamedCount;
        private long encryptedEvidenceCount;
        private Long grantCount;
        private Long joinCount;
        private Long emergencyCount;
        private Long registerCount;
        private Long logoutCount;
        private Long denialCount;
        private Long dataCount;
        private Long otherSignalingCount;
        private Long signalingCount;
        private Long relationshipCount;
        private Long joinRelationshipCount;
        private Long currentAffiliationCount;
        private Long firstEvidenceMs;
        private Long lastEvidenceMs;

        private MetricAccumulator(CoverageSource source)
        {
            this.source = source;
            boolean trunked = source.trunked;
            recordedCount = 0L;
            streamedCount = 0L;
            grantCount = trunked && source.protocolCode == 1 ? 0L : null;
            joinCount = trunked ? 0L : null;
            emergencyCount = trunked ? 0L : null;
            registerCount = trunked ? 0L : null;
            logoutCount = trunked ? 0L : null;
            denialCount = trunked ? 0L : null;
            dataCount = trunked ? 0L : null;
            otherSignalingCount = trunked ? 0L : null;
            signalingCount = trunked ? 0L : null;
            relationshipCount = trunked ? 0L : null;
            joinRelationshipCount = trunked ? 0L : null;
            currentAffiliationCount = trunked && source.protocolCode == 1 ? 0L : null;
        }

        private void addEvidence(Map<String,Object> row)
        {
            callCount += number(row.get("logical_call_count"));
            encryptedEvidenceCount += number(row.get("encrypted_logical_call_count"));
            recordedCount = addNullable(recordedCount, row.get("recorded_logical_call_count"));
            streamedCount = addNullable(streamedCount, row.get("stream_submitted_logical_call_count"));
            grantCount = addNullable(grantCount, row.get("grant_observation_count"));
            joinCount = addNullable(joinCount, row.get("join_observation_count"));
            emergencyCount = addNullable(emergencyCount, row.get("emergency_observation_count"));
            registerCount = addNullable(registerCount, row.get("register_observation_count"));
            logoutCount = addNullable(logoutCount, row.get("logout_observation_count"));
            denialCount = addNullable(denialCount, row.get("denial_observation_count"));
            dataCount = addNullable(dataCount, row.get("data_observation_count"));
            otherSignalingCount = addNullable(otherSignalingCount,
                row.get("other_signaling_observation_count"));
            signalingCount = addNullable(signalingCount, row.get("signaling_observation_count"));
            observe(row.get("first_seen_ms"), row.get("last_seen_ms"));
        }

        private void observe(Object firstValue, Object lastValue)
        {
            Long first = nullableNumber(firstValue);
            Long last = nullableNumber(lastValue);
            firstEvidenceMs = first != null && (firstEvidenceMs == null || first < firstEvidenceMs) ? first :
                firstEvidenceMs;
            lastEvidenceMs = last != null && (lastEvidenceMs == null || last > lastEvidenceMs) ? last :
                lastEvidenceMs;
        }

        private boolean observed()
        {
            return firstEvidenceMs != null || lastEvidenceMs != null || relationshipCount != null &&
                relationshipCount > 0 || currentAffiliationCount != null && currentAffiliationCount > 0;
        }

        private String sortKey()
        {
            return source.protocol + "\u0000" + sourceLabel() + "\u0000" + source.key;
        }

        private String sourceLabel()
        {
            if(source.systemName != null && source.siteName != null && !source.systemName.equals(source.siteName))
            {
                return source.systemName + " / " + source.siteName;
            }

            if(source.systemName != null)
            {
                return source.systemName;
            }

            if(source.siteName != null)
            {
                return source.siteName;
            }

            return source.systemKey != null ? source.systemKey : source.configurationId != null ? source.configurationId :
                source.key;
        }

        private Long metric(String field)
        {
            return switch(field)
            {
                case "logical_call_count" -> callCount;
                case "recorded_logical_call_count" -> recordedCount;
                case "stream_submitted_logical_call_count" -> streamedCount;
                case "encrypted_logical_call_count" -> encryptedEvidenceCount;
                case "grant_observation_count" -> grantCount;
                case "join_observation_count" -> joinCount;
                case "emergency_observation_count" -> emergencyCount;
                case "register_observation_count" -> registerCount;
                case "logout_observation_count" -> logoutCount;
                case "denial_observation_count" -> denialCount;
                case "data_observation_count" -> dataCount;
                case "other_signaling_observation_count" -> otherSignalingCount;
                case "signaling_observation_count" -> signalingCount;
                case "relationship_count" -> relationshipCount;
                case "join_relationship_count" -> joinRelationshipCount;
                case "current_affiliation_count" -> currentAffiliationCount;
                default -> null;
            };
        }

        private Map<String,Object> toMap()
        {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("source_label", sourceLabel());
            row.put("topology", source.trunked ? "TRUNKED" : "CONVENTIONAL");
            row.put("protocol", source.protocol);
            row.put("radio_system_id", source.radioSystemId);
            row.put("channel_id", source.channelId);
            row.put("system_name", source.systemName);
            row.put("site_name", source.siteName);
            row.put("radio_system_key", source.systemKey);
            row.put("configuration_id", source.configurationId);
            row.put("alias_list_id", source.canonicalAliasListId);
            row.put("alias_list_name", source.canonicalAliasList);
            row.put("metrics_state", observed() ? "observed" : "covered_no_evidence");

            for(String field: METRIC_FIELDS)
            {
                row.put(field, metric(field));
            }

            row.put("first_evidence_ms", firstEvidenceMs);
            row.put("last_evidence_ms", lastEvidenceMs);
            return row;
        }

        private static Long addNullable(Long current, Object increment)
        {
            return current != null && increment instanceof Number number ? current + number.longValue() : current;
        }
    }
}
