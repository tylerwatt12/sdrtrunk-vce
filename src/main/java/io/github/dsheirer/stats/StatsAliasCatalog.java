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
    static final int MAX_METRIC_SORT_ALIASES = 1_000;
    static final int MAX_TARGET_ALIAS_LISTS = 256;
    static final int MAX_TARGET_RANGES = 500;
    static final int MAX_SCOPED_TARGET_RANGES = 10_000;
    static final int MAX_BROADCAST_CHANNELS_PER_ALIAS = AliasAdministrationService.MAX_BROADCAST_CHANNELS;
    static final int MAX_BROADCAST_CHANNEL_NAME_CHARACTERS =
        AliasAdministrationService.MAX_BROADCAST_CHANNEL_NAME_LENGTH;
    static final int MAX_BROADCAST_CHANNEL_ROWS = StatsSqlRows.MAXIMUM_MATERIALIZED_ROWS;
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
        Map.entry("calls", "call_count"), Map.entry("call_count", "call_count"),
        Map.entry("recorded", "recorded_count"), Map.entry("recorded_count", "recorded_count"),
        Map.entry("streamed", "streamed_count"), Map.entry("streamed_count", "streamed_count"),
        Map.entry("encrypted", "encrypted_evidence_count"),
        Map.entry("encrypted_evidence_count", "encrypted_evidence_count"),
        Map.entry("grants", "grant_count"), Map.entry("grant_count", "grant_count"),
        Map.entry("joins", "join_count"), Map.entry("join_count", "join_count"),
        Map.entry("emergencies", "emergency_count"), Map.entry("emergency_count", "emergency_count"),
        Map.entry("registrations", "register_count"), Map.entry("register_count", "register_count"),
        Map.entry("logouts", "logout_count"), Map.entry("logout_count", "logout_count"),
        Map.entry("denials", "denial_count"), Map.entry("denial_count", "denial_count"),
        Map.entry("data", "data_count"), Map.entry("data_count", "data_count"),
        Map.entry("other_signaling", "other_signaling_count"),
        Map.entry("other_signaling_count", "other_signaling_count"),
        Map.entry("relationships", "relationship_count"),
        Map.entry("relationship_count", "relationship_count"),
        Map.entry("join_relationships", "join_relationship_count"),
        Map.entry("join_relationship_count", "join_relationship_count"),
        Map.entry("current_affiliations", "current_affiliation_count"),
        Map.entry("current_affiliation_count", "current_affiliation_count"),
        Map.entry("first_evidence", "first_evidence_ms"),
        Map.entry("first_evidence_ms", "first_evidence_ms"),
        Map.entry("last_evidence", "last_evidence_ms"),
        Map.entry("last_evidence_ms", "last_evidence_ms")
    );
    private static final List<String> METRIC_FIELDS = List.of(
        "call_count", "recorded_count", "streamed_count", "encrypted_evidence_count",
        "grant_count", "join_count", "emergency_count", "register_count", "logout_count",
        "denial_count", "data_count", "other_signaling_count", "relationship_count",
        "join_relationship_count", "current_affiliation_count");
    private static final String OTHER_SIGNALING_SQL = "summary.acknowledge_count + summary.active_count + " +
        "summary.busy_count + summary.check_count + summary.check_ack_count + summary.continue_count + " +
        "summary.gps_count + summary.page_count + summary.patch_count + summary.patch_cancel_count + " +
        "summary.patch_create_count + summary.queued_count + summary.request_count + summary.status_count + " +
        "summary.unknown_count";

    private final StatsAliasResolver mResolver;

    StatsAliasCatalog(StatsAliasResolver resolver)
    {
        mResolver = resolver;
    }

    Map<String,Object> aliasLists(Connection connection, StatsRequest request) throws SQLException
    {
        List<Map<String,Object>> rows = queryRows(connection, """
            SELECT list.id AS alias_list_id, list.name, list.family,
                count(DISTINCT alias.id) AS alias_count,
                (SELECT count(*) FROM configuration_channel channel
                 WHERE channel.alias_list_name = list.name COLLATE NOCASE) AS assigned_channel_count
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
        response.put("count", totals.isEmpty() ? 0 : number(totals.getFirst().get("count")));
        response.put("limit", request.limit());
        response.put("offset", request.offset());
        response.put("hasMore", hasMore);
        response.put("nextOffset", hasMore ? request.offset() + request.limit() : null);
        response.put("families", List.of("p25", "dmr", "nxdn", "nbfm"));
        response.put("matcher_types", MATCHERS.keySet().stream().sorted().toList());
        return response;
    }

    Map<String,Object> aliases(Connection connection, StatsRequest request) throws SQLException
    {
        if(metricSortField(request) != null || hasMetricFilters(request))
        {
            List<Map<String,Object>> allRows = queryAliasRows(connection, request,
                MAX_METRIC_SORT_ALIASES + 1, 0, null);

            if(allRows.size() > MAX_METRIC_SORT_ALIASES)
            {
                throw new StatsApiException(413, "Metric-sorted or filtered alias query exceeds the " +
                    MAX_METRIC_SORT_ALIASES + " row limit");
            }

            enrich(connection, allRows, false);
            applyMetricFilters(allRows, request);
            sortMetricRows(allRows, request);
            int from = Math.min(request.offset(), allRows.size());
            int to = Math.min(from + request.limit(), allRows.size());
            boolean hasMore = to < allRows.size();
            List<Map<String,Object>> rows = new ArrayList<>(allRows.subList(from, to));
            applyConfigurationDiagnostics(connection, rows);
            Map<String,Object> response = new LinkedHashMap<>();
            response.put("rows", rows);
            response.put("limit", request.limit());
            response.put("offset", request.offset());
            response.put("hasMore", hasMore);
            response.put("nextOffset", hasMore ? request.offset() + request.limit() : null);
            return response;
        }

        List<Map<String,Object>> queried = queryAliasRows(connection, request, request.limit() + 1,
            request.offset(), null);
        boolean hasMore = queried.size() > request.limit();
        List<Map<String,Object>> rows = hasMore ?
            new ArrayList<>(queried.subList(0, request.limit())) : queried;
        enrich(connection, rows, false);
        applyConfigurationDiagnostics(connection, rows);
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("rows", rows);
        response.put("limit", request.limit());
        response.put("offset", request.offset());
        response.put("hasMore", hasMore);
        response.put("nextOffset", hasMore ? request.offset() + request.limit() : null);
        return response;
    }

    Map<String,Object> alias(Connection connection, long aliasId) throws SQLException
    {
        if(aliasId <= 0)
        {
            throw new StatsApiException(400, "id is invalid");
        }

        List<Map<String,Object>> rows = queryAliasRows(connection, new StatsRequest(Map.of()), 1, 0, aliasId);

        if(rows.isEmpty())
        {
            throw new StatsApiException(404, "Alias not found");
        }

        Map<Long,List<Map<String,Object>>> breakdown = enrich(connection, rows, true);
        applyConfigurationDiagnostics(connection, rows);
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("alias", rows.getFirst());
        response.put("breakdown", breakdown.getOrDefault(aliasId, List.of()));
        return response;
    }

    List<Map<String,Object>> exportRows(Connection connection, StatsRequest request, int maximumRows)
        throws SQLException
    {
        boolean metricProcessing = metricSortField(request) != null || hasMetricFilters(request);
        int queryLimit = metricProcessing ? Math.min(maximumRows, MAX_METRIC_SORT_ALIASES) + 1 : maximumRows + 1;
        List<Map<String,Object>> rows = queryAliasRows(connection, request, queryLimit, 0, null);

        if(metricProcessing && rows.size() > MAX_METRIC_SORT_ALIASES)
        {
            throw new StatsApiException(413, "Metric-filtered alias query exceeds the " +
                MAX_METRIC_SORT_ALIASES + " row limit");
        }

        if(rows.size() <= maximumRows)
        {
            enrich(connection, rows, false);
            applyMetricFilters(rows, request);

            if(metricSortField(request) != null)
            {
                sortMetricRows(rows, request);
            }
        }

        return rows;
    }

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

    private static void applyMetricFilters(List<Map<String,Object>> rows, StatsRequest request)
    {
        validateMetricFilters(request);
        String evidence = request.text("evidence");
        String use = request.text("use");
        Long after = optionalTimestamp(request, "last_activity_after");
        Long before = optionalTimestamp(request, "last_activity_before");

        rows.removeIf(row -> evidence != null && !evidence.equals(lower(text(row.get("metrics_state")))) ||
            "used".equals(use) && !(row.get("call_count") instanceof Number count && count.longValue() > 0) ||
            "unused".equals(use) && !(row.get("call_count") instanceof Number count && count.longValue() == 0) ||
            after != null && !(row.get("last_evidence_ms") instanceof Number last && last.longValue() >= after) ||
            before != null && !(row.get("last_evidence_ms") instanceof Number last && last.longValue() <= before));
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

    private static void sortMetricRows(List<Map<String,Object>> rows, StatsRequest request)
    {
        String field = metricSortField(request);

        if(field == null)
        {
            return;
        }

        boolean descending = request.descending(false);
        rows.sort((left, right) -> {
            Object leftValue = left.get(field);
            Object rightValue = right.get(field);

            if(leftValue == null && rightValue != null)
            {
                return 1;
            }
            else if(leftValue != null && rightValue == null)
            {
                return -1;
            }

            int comparison = leftValue instanceof Number leftNumber && rightValue instanceof Number rightNumber ?
                Long.compare(leftNumber.longValue(), rightNumber.longValue()) :
                String.valueOf(leftValue).compareToIgnoreCase(String.valueOf(rightValue));

            if(comparison != 0)
            {
                return descending ? -comparison : comparison;
            }

            return Long.compare(number(left.get("alias_id")), number(right.get("alias_id")));
        });
    }

    private static List<Map<String,Object>> queryAliasRows(Connection connection, StatsRequest request, int limit,
                                                            int offset, Long aliasId) throws SQLException
    {
        StringBuilder sql = new StringBuilder("""
            SELECT alias.id AS alias_id, alias.alias_list_id, alias_list.name AS alias_list_name,
                alias_list.family, alias.name, alias.description, alias.group_name AS `group`, alias.color,
                alias.icon_name, alias.stream_as_talkgroup, alias.record_enabled, alias.priority,
                alias.matcher_type,
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
        parameters.add(limit);
        parameters.add(offset);
        List<Map<String,Object>> rows = queryRows(connection, sql.toString(), parameters.toArray());
        attachBroadcastChannels(connection, rows);

        for(Map<String,Object> row: rows)
        {
            normalizeConfigurationRow(row);
        }

        return rows;
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
                SELECT alias_id, count(*) AS route_count, max(length(channel_name)) AS maximum_name_length
                FROM alias_broadcast_channel
                WHERE alias_id IN (%s)
                GROUP BY alias_id
                ORDER BY alias_id
                """.formatted(placeholders(chunk.size())), chunk.toArray());

            for(Map<String,Object> count: counts)
            {
                long routes = number(count.get("route_count"));

                if(routes > MAX_BROADCAST_CHANNELS_PER_ALIAS)
                {
                    throw new StatsApiException(413, "alias_routes_too_large",
                        "An alias has too many broadcast channels");
                }

                if(number(count.get("maximum_name_length")) > MAX_BROADCAST_CHANNEL_NAME_CHARACTERS)
                {
                    throw new StatsApiException(413, "alias_routes_too_large",
                        "An alias broadcast channel name is too long");
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
                SELECT alias_id, channel_name
                FROM alias_broadcast_channel
                WHERE alias_id IN (%s)
                ORDER BY alias_id, lower(channel_name), channel_name
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

        String listen = request.text("listen");
        if(listen != null)
        {
            switch(listen)
            {
                case "enabled" -> sql.append(" AND coalesce(alias.priority, 100) <> -1");
                case "disabled" -> sql.append(" AND alias.priority = -1");
                default -> throw new StatsApiException(400, "listen is invalid");
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

        for(int start = 0; start < aliasIds.size(); start += 500)
        {
            List<Long> chunk = aliasIds.subList(start, Math.min(start + 500, aliasIds.size()));
            List<Map<String,Object>> diagnostics = queryRows(connection, """
                SELECT current.id AS alias_id,
                    EXISTS (
                        SELECT 1
                        FROM alias other
                        WHERE other.alias_list_id = current.alias_list_id
                          AND other.id <> current.id
                          AND other.matcher_type = current.matcher_type
                          AND CASE
                            WHEN current.matcher_type IN ('TALKGROUP', 'RADIO_ID') THEN
                                (CASE WHEN other.protocol = 'APCO25_PHASE2' THEN 'APCO25' ELSE other.protocol END) =
                                (CASE WHEN current.protocol = 'APCO25_PHASE2' THEN 'APCO25' ELSE current.protocol END)
                                AND other.value = current.value
                            WHEN current.matcher_type IN ('TALKGROUP_RANGE', 'RADIO_ID_RANGE') THEN
                                (CASE WHEN other.protocol = 'APCO25_PHASE2' THEN 'APCO25' ELSE other.protocol END) =
                                (CASE WHEN current.protocol = 'APCO25_PHASE2' THEN 'APCO25' ELSE current.protocol END)
                                AND other.min_value <= current.max_value AND current.min_value <= other.max_value
                            WHEN current.matcher_type IN ('STATUS', 'UNIT_STATUS') THEN
                                other.numeric_value = current.numeric_value
                            WHEN current.matcher_type = 'DCS' THEN other.text_value = current.text_value
                            WHEN current.matcher_type = 'TONES' THEN other.tone_sequence = current.tone_sequence
                            ELSE 0
                          END
                    ) AS overlap
                FROM alias current
                WHERE current.id IN (%s)
                ORDER BY current.id
                """.formatted(placeholders(chunk.size())), chunk.toArray());

            for(Map<String,Object> diagnostic: diagnostics)
            {
                if(number(diagnostic.get("overlap")) != 0)
                {
                    overlaps.add(number(diagnostic.get("alias_id")));
                }
            }
        }

        for(Map<String,Object> alias: aliases)
        {
            boolean overlap = overlaps.contains(number(alias.get("alias_id")));
            alias.put("overlap", overlap);
            alias.put("configuration_errors", overlap ? List.of("overlap") : List.of());
        }
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
        Map<Long,Map<String,MetricAccumulator>> metrics = new LinkedHashMap<>();
        IdentityTargets targets = IdentityTargets.from(aliases);
        List<CoverageScope> scopes = loadCoverageScopes(connection, targets);
        Map<Long,Map<String,CoverageScope>> coverage = new LinkedHashMap<>();
        int coveragePairs = 0;

        for(Map<String,Object> alias: aliases)
        {
            long aliasId = number(alias.get("alias_id"));
            Map<String,CoverageScope> compatible = new LinkedHashMap<>();

            if(isSupportedIdentity(alias))
            {
                for(CoverageScope scope: scopes)
                {
                    if(isEligible(alias, scope))
                    {
                        compatible.put(scope.key, scope);
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

            for(CoverageScope scope: compatible.values())
            {
                aliasMetrics.put(scope.key, new MetricAccumulator(scope));
            }

            metrics.put(aliasId, aliasMetrics);
        }

        Set<Long> trunkedScopeIds = new LinkedHashSet<>();
        Set<Long> conventionalContextIds = new LinkedHashSet<>();

        for(Map<String,CoverageScope> aliasCoverage: coverage.values())
        {
            for(CoverageScope scope: aliasCoverage.values())
            {
                if(scope.trunked)
                {
                    trunkedScopeIds.add(scope.numericId);
                }
                else
                {
                    conventionalContextIds.add(scope.numericId);
                }
            }
        }

        Map<Long,List<CoverageScope>> scopeById = scopeProjections(scopes, true);
        Map<Long,List<CoverageScope>> contextById = scopeProjections(scopes, false);
        ScopedIdentityTargets scopedTargets = ScopedIdentityTargets.from(aliases, coverage);
        EvidenceBudget evidenceBudget = new EvidenceBudget(MAX_EVIDENCE_ROWS);
        applyTrunkedEvidence(connection, metrics, trunkedScopeIds, scopeById, scopedTargets, evidenceBudget);
        applyConventionalDmrEvidence(connection, metrics, conventionalContextIds, contextById, scopedTargets,
            evidenceBudget);
        applyConventionalDmrOutputs(connection, metrics, conventionalContextIds, contextById, scopedTargets,
            evidenceBudget);
        applyRelationships(connection, metrics, trunkedScopeIds, scopeById, scopedTargets, evidenceBudget);
        applyCurrentAffiliations(connection, metrics, trunkedScopeIds, scopeById, scopedTargets, evidenceBudget);

        Map<Long,List<Map<String,Object>>> breakdown = new LinkedHashMap<>();

        for(Map<String,Object> alias: aliases)
        {
            long aliasId = number(alias.get("alias_id"));
            Map<String,MetricAccumulator> aliasMetrics = metrics.getOrDefault(aliasId, Map.of());
            boolean supported = isSupportedIdentity(alias);
            int observedScopes = (int)aliasMetrics.values().stream().filter(MetricAccumulator::observed).count();
            alias.put("coverage_scope_count", aliasMetrics.size());
            alias.put("observed_scope_count", observedScopes);
            alias.put("metrics_state", !supported ? "unsupported" : aliasMetrics.isEmpty() ? "not_collected" :
                observedScopes > 0 ? "observed" : "covered_no_evidence");
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

    private static List<CoverageScope> loadCoverageScopes(Connection connection, IdentityTargets targets)
        throws SQLException
    {
        StringBuilder trunkedSql = new StringBuilder("""
            SELECT scope.scope_id, scope.scope_token, scope.protocol_code, scope.p25_system_key AS system_key,
                system.wacn, system.system_id, context.id AS context_id, context.context_key, context.guid,
                coalesce(context.channel_name, p25.channel_name, trunked.channel_name) AS site_name,
                coalesce((SELECT config.system_name FROM configuration_channel config
                          WHERE config.radres_guid = context.guid ORDER BY config.sort_order LIMIT 1),
                         trunked.configured_system) AS system_name,
                CASE WHEN scope.protocol_code = 1 THEN coalesce(p25.alias_list_name, context.alias_list_name)
                     ELSE coalesce(context.alias_list_name, trunked.alias_list_name) END AS alias_list_name
            FROM trunked_identity_scope scope
            JOIN trunked_identity_scope_context ownership ON ownership.scope_id = scope.scope_id
            JOIN receiver_context context ON context.id = ownership.context_id
            LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
            LEFT JOIN p25_site_snapshot p25
              ON p25.guid = context.guid AND p25.system_key = scope.p25_system_key
            LEFT JOIN trunked_site_snapshot trunked ON trunked.guid = context.guid
            WHERE
            """);
        List<Object> trunkedParameters = new ArrayList<>();
        targets.appendCoveragePredicate(trunkedSql, trunkedParameters, "scope.protocol_code",
            "CASE WHEN scope.protocol_code = 1 THEN coalesce(p25.alias_list_name, context.alias_list_name) " +
                "ELSE coalesce(context.alias_list_name, trunked.alias_list_name) END");
        trunkedSql.append("""
            ORDER BY scope.scope_id, context.id
            LIMIT ?
            """);
        trunkedParameters.add(MAX_COVERAGE_ROWS + 1);
        List<Map<String,Object>> trunked = queryRows(connection, trunkedSql.toString(),
            trunkedParameters.toArray());

        if(trunked.size() > MAX_COVERAGE_ROWS)
        {
            throw new StatsApiException(413, "Alias coverage exceeds the bounded receiver limit");
        }

        StringBuilder conventionalSql = new StringBuilder("""
            SELECT context.id AS context_id, context.context_key, context.guid, context.channel_name AS site_name,
                context.alias_list_name,
                (SELECT config.system_name FROM configuration_channel config
                 WHERE config.radres_guid = context.guid ORDER BY config.sort_order LIMIT 1) AS system_name
            FROM receiver_context context
            WHERE context.kind_code <> 1 AND context.protocol_code = 3
              AND context.alias_list_name IS NOT NULL AND trim(context.alias_list_name) <> ''
              AND
            """);
        List<Object> conventionalParameters = new ArrayList<>();
        targets.appendAliasListPredicate(conventionalSql, conventionalParameters, 3,
            "context.alias_list_name");
        conventionalSql.append("""
            ORDER BY context.id
            LIMIT ?
            """);
        conventionalParameters.add(MAX_COVERAGE_ROWS - trunked.size() + 1);
        List<Map<String,Object>> conventionalDmr = queryRows(connection, conventionalSql.toString(),
            conventionalParameters.toArray());

        if(trunked.size() + conventionalDmr.size() > MAX_COVERAGE_ROWS)
        {
            throw new StatsApiException(413, "Alias coverage exceeds the bounded receiver limit");
        }

        Map<Long,CoverageScope> p25ById = new LinkedHashMap<>();
        Map<String,CoverageScope> trunkedByProjection = new LinkedHashMap<>();

        for(Map<String,Object> row: trunked)
        {
            long scopeId = number(row.get("scope_id"));
            int protocolCode = (int)number(row.get("protocol_code"));
            String aliasList = text(row.get("alias_list_name"));
            CoverageScope scope;

            if(protocolCode == 1)
            {
                //P25 aliases are deliberately resolved across every list assigned to the decoded system.
                scope = p25ById.computeIfAbsent(scopeId, ignored -> CoverageScope.trunked(row));
            }
            else
            {
                //DMR/NXDN ownership is list-specific.  Keep an explicit projection per list so one context cannot
                //silently become the canonical resolver context for every other receiver sharing this scope.
                String projectionKey = scopeId + "\u0000" + aliasList;
                scope = trunkedByProjection.computeIfAbsent(projectionKey,
                    ignored -> CoverageScope.trunked(row));
            }

            scope.addAliasList(text(row.get("alias_list_name")));
        }

        List<CoverageScope> scopes = new ArrayList<>(p25ById.values());
        scopes.addAll(trunkedByProjection.values());

        for(Map<String,Object> row: conventionalDmr)
        {
            CoverageScope scope = CoverageScope.conventionalDmr(row);
            scope.addAliasList(text(row.get("alias_list_name")));
            scopes.add(scope);
        }

        return scopes;
    }

    private static boolean isEligible(Map<String,Object> alias, CoverageScope scope)
    {
        int protocol = protocolCode(text(alias.get("protocol")));

        if(protocol != scope.protocolCode)
        {
            return false;
        }

        return scope.aliasLists.contains(lower(text(alias.get("alias_list_name"))));
    }

    private void applyTrunkedEvidence(Connection connection, Map<Long,Map<String,MetricAccumulator>> metrics,
                                      Set<Long> scopeIds,
                                      Map<Long,List<CoverageScope>> scopes, ScopedIdentityTargets targets,
                                      EvidenceBudget budget) throws SQLException
    {
        if(scopeIds.isEmpty())
        {
            return;
        }

        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        targets.appendCte(sql, parameters, true);
        sql.append("""
            SELECT summary.scope_id, summary.identity_kind_code, summary.identity_id,
                summary.p25_identity_state_code, summary.p25_home_wacn,
                summary.p25_home_system_id, summary.p25_home_talkgroup_id,
                summary.first_seen_ms, summary.last_seen_ms, summary.call_count,
                summary.recorded_count, summary.streamed_count,
                summary.encrypted_count AS encrypted_evidence_count,
                summary.grant_count, summary.join_count, summary.emergency_count,
                summary.register_count, summary.logout_count, summary.denial_count, summary.data_count,
                %s AS other_signaling_count, scope.protocol_code, scope.p25_system_key AS system_key,
                system.wacn, system.system_id
            FROM trunked_identity_summary summary
            JOIN trunked_identity_scope scope ON scope.scope_id = summary.scope_id
            LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
            WHERE summary.scope_id IN (%s)
              AND
            """.formatted(OTHER_SIGNALING_SQL, placeholders(scopeIds.size())));
        parameters.addAll(scopeIds);
        targets.appendPredicate(sql, "summary.scope_id", "summary.identity_kind_code", "summary.identity_id");
        sql.append("""

            UNION ALL

            SELECT summary.scope_id, 1 AS identity_kind_code, 0 AS identity_id,
                2 AS p25_identity_state_code, summary.home_wacn AS p25_home_wacn,
                summary.home_system_id AS p25_home_system_id,
                summary.home_talkgroup_id AS p25_home_talkgroup_id,
                summary.first_seen_ms, summary.last_seen_ms, summary.call_count,
                summary.recorded_count, summary.streamed_count,
                summary.encrypted_count AS encrypted_evidence_count,
                summary.grant_count, summary.join_count, summary.emergency_count,
                summary.register_count, summary.logout_count, summary.denial_count, summary.data_count,
                %s AS other_signaling_count, scope.protocol_code, scope.p25_system_key AS system_key,
                system.wacn, system.system_id
            FROM p25_zero_local_fq_talkgroup_summary summary
            JOIN trunked_identity_scope scope ON scope.scope_id = summary.scope_id
            LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
            WHERE summary.scope_id IN (%s)
              AND
            """.formatted(OTHER_SIGNALING_SQL, placeholders(scopeIds.size())));
        parameters.addAll(scopeIds);
        targets.appendPredicate(sql, "summary.scope_id", "1", "0");
        sql.append("""
            ORDER BY 1, 2, 3, 5, 6, 7
            LIMIT ?
            """);
        parameters.add(budget.queryLimit());
        List<Map<String,Object>> evidence = queryRows(connection, sql.toString(), parameters.toArray());
        budget.consume(evidence.size());
        evidence = projectEvidence(evidence, scopes, "scope_id", targets, budget);

        mResolver.resolveEvidenceAliases(connection, evidence);
        applyEvidenceRows(evidence, metrics);
    }

    private void applyConventionalDmrEvidence(Connection connection,
                                               Map<Long,Map<String,MetricAccumulator>> metrics,
                                               Set<Long> contextIds, Map<Long,List<CoverageScope>> scopes,
                                               ScopedIdentityTargets targets, EvidenceBudget budget)
        throws SQLException
    {
        if(contextIds.isEmpty())
        {
            return;
        }

        String placeholders = placeholders(contextIds.size());
        StringBuilder sql = new StringBuilder();
        List<Object> parameters = new ArrayList<>();
        targets.appendCte(sql, parameters, false);
        sql.append("""
            SELECT summary.context_id, 1 AS identity_kind_code, summary.talkgroup_id AS identity_id,
                summary.first_seen_ms, summary.last_seen_ms, summary.call_count,
                NULL AS recorded_count, NULL AS streamed_count,
                summary.encrypted_count AS encrypted_evidence_count,
                NULL AS grant_count, NULL AS join_count, NULL AS emergency_count,
                NULL AS register_count, NULL AS logout_count, NULL AS denial_count,
                NULL AS data_count, NULL AS other_signaling_count, 3 AS protocol_code
            FROM dmr_conventional_talkgroup_summary summary
            WHERE summary.context_id IN (%1$s)
              AND
            """.formatted(placeholders));
        parameters.addAll(contextIds);
        targets.appendPredicate(sql, "summary.context_id", "1", "summary.talkgroup_id");
        sql.append("""

            UNION ALL

            SELECT summary.context_id, 2 AS identity_kind_code, summary.radio_id AS identity_id,
                summary.first_seen_ms, summary.last_seen_ms, summary.call_count,
                NULL AS recorded_count, NULL AS streamed_count,
                summary.encrypted_count AS encrypted_evidence_count,
                NULL AS grant_count, NULL AS join_count, NULL AS emergency_count,
                NULL AS register_count, NULL AS logout_count, NULL AS denial_count,
                NULL AS data_count, NULL AS other_signaling_count, 3 AS protocol_code
            FROM dmr_conventional_radio_summary summary
            WHERE summary.context_id IN (%1$s)
              AND
            """.formatted(placeholders));
        parameters.addAll(contextIds);
        targets.appendPredicate(sql, "summary.context_id", "2", "summary.radio_id");
        sql.append("""
            ORDER BY context_id, identity_kind_code, identity_id
            LIMIT ?
            """);
        parameters.add(budget.queryLimit());
        List<Map<String,Object>> evidence = queryRows(connection, sql.toString(), parameters.toArray());
        budget.consume(evidence.size());
        evidence = projectEvidence(evidence, scopes, "context_id", targets, budget);

        mResolver.resolveEvidenceAliases(connection, evidence);
        applyEvidenceRows(evidence, metrics);
    }

    private static void applyEvidenceRows(List<Map<String,Object>> evidence,
                                          Map<Long,Map<String,MetricAccumulator>> metrics)
    {
        for(Map<String,Object> row: evidence)
        {
            Long aliasId = nullableNumber(row.get("resolved_alias_id"));
            String scopeKey = text(row.get("scope_key"));

            if(aliasId == null || scopeKey == null)
            {
                continue;
            }

            MetricAccumulator accumulator = metrics.getOrDefault(aliasId, Map.of()).get(scopeKey);

            if(accumulator != null)
            {
                accumulator.addEvidence(row);
            }
        }
    }

    /**
     * Completed-call output counts live in the protocol-neutral hourly identity buckets.  DMR's durable conventional
     * summaries remain the sole source for call/encryption totals; this query adds only recorded/streamed output so
     * those calls are not counted twice.
     */
    private void applyConventionalDmrOutputs(Connection connection,
                                             Map<Long,Map<String,MetricAccumulator>> metrics,
                                             Set<Long> contextIds, Map<Long,List<CoverageScope>> scopes,
                                             ScopedIdentityTargets targets, EvidenceBudget budget)
        throws SQLException
    {
        if(contextIds.isEmpty())
        {
            return;
        }

        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        targets.appendCte(sql, parameters, false);
        sql.append("""
            SELECT bucket.context_id, bucket.identity_kind_code, bucket.identity_id,
                NULL AS first_seen_ms, NULL AS last_seen_ms,
                NULL AS call_count, sum(bucket.recorded_count) AS recorded_count,
                sum(bucket.streamed_count) AS streamed_count, NULL AS encrypted_evidence_count,
                NULL AS grant_count, NULL AS join_count, NULL AS emergency_count,
                NULL AS register_count, NULL AS logout_count, NULL AS denial_count,
                NULL AS data_count, NULL AS other_signaling_count, 3 AS protocol_code
            FROM call_identity_bucket bucket
            JOIN receiver_context context ON context.id = bucket.context_id
            WHERE bucket.context_id IN (%s) AND context.kind_code <> 1 AND context.protocol_code = 3
              AND bucket.identity_kind_code IN (1, 2)
              AND
            """.formatted(placeholders(contextIds.size())));
        parameters.addAll(contextIds);
        targets.appendPredicate(sql, "bucket.context_id", "bucket.identity_kind_code", "bucket.identity_id");
        sql.append("""
            GROUP BY bucket.context_id, bucket.identity_kind_code, bucket.identity_id
            ORDER BY bucket.context_id, bucket.identity_kind_code, bucket.identity_id
            LIMIT ?
            """);
        parameters.add(budget.queryLimit());
        List<Map<String,Object>> evidence = queryRows(connection, sql.toString(), parameters.toArray());
        budget.consume(evidence.size());
        evidence = projectEvidence(evidence, scopes, "context_id", targets, budget);

        mResolver.resolveEvidenceAliases(connection, evidence);
        applyEvidenceRows(evidence, metrics);
    }

    private void applyRelationships(Connection connection, Map<Long,Map<String,MetricAccumulator>> metrics,
                                    Set<Long> scopeIds,
                                    Map<Long,List<CoverageScope>> scopes, ScopedIdentityTargets targets,
                                    EvidenceBudget budget) throws SQLException
    {
        if(scopeIds.isEmpty())
        {
            return;
        }

        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        targets.appendCte(sql, parameters, true);
        sql.append("""
            SELECT relationship.scope_id, 2 AS identity_kind_code, relationship.radio_id AS identity_id,
                relationship.first_seen_ms, relationship.last_seen_ms, relationship.join_count,
                scope.protocol_code, scope.p25_system_key AS system_key, system.wacn, system.system_id,
                NULL AS p25_identity_state_code, NULL AS p25_home_wacn,
                NULL AS p25_home_system_id, NULL AS p25_home_talkgroup_id
            FROM trunked_radio_talkgroup_summary relationship
            JOIN trunked_identity_scope scope ON scope.scope_id = relationship.scope_id
            LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
            WHERE relationship.scope_id IN (%s)
              AND
            """.formatted(placeholders(scopeIds.size())));
        parameters.addAll(scopeIds);
        targets.appendPredicate(sql, "relationship.scope_id", "2", "relationship.radio_id");
        sql.append("""

            UNION ALL

            SELECT relationship.scope_id, relationship.target_kind_code AS identity_kind_code,
                relationship.talkgroup_id AS identity_id,
                relationship.first_seen_ms, relationship.last_seen_ms, relationship.join_count,
                scope.protocol_code, scope.p25_system_key AS system_key, system.wacn, system.system_id,
                target.p25_identity_state_code, target.p25_home_wacn,
                target.p25_home_system_id, target.p25_home_talkgroup_id
            FROM trunked_radio_talkgroup_summary relationship
            JOIN trunked_identity_scope scope ON scope.scope_id = relationship.scope_id
            LEFT JOIN p25_system system ON system.system_key = scope.p25_system_key
            LEFT JOIN trunked_identity_summary target
              ON target.scope_id = relationship.scope_id
             AND target.identity_kind_code = relationship.target_kind_code
             AND target.identity_id = relationship.talkgroup_id
            WHERE relationship.scope_id IN (%s)
              AND
            """.formatted(placeholders(scopeIds.size())));
        parameters.addAll(scopeIds);
        targets.appendPredicate(sql, "relationship.scope_id", "relationship.target_kind_code",
            "relationship.talkgroup_id");
        sql.append("""
            ORDER BY 1, 2, 3
            LIMIT ?
            """);
        parameters.add(budget.queryLimit());
        List<Map<String,Object>> identities = queryRows(connection, sql.toString(), parameters.toArray());
        budget.consume(identities.size());

        identities = projectEvidence(identities, scopes, "scope_id", targets, budget);

        mResolver.resolveEvidenceAliases(connection, identities);

        for(Map<String,Object> row: identities)
        {
            MetricAccumulator accumulator = accumulator(metrics, row);

            if(accumulator != null)
            {
                accumulator.relationshipCount++;
                accumulator.observe(row.get("first_seen_ms"), row.get("last_seen_ms"));

                if(number(row.get("join_count")) > 0)
                {
                    accumulator.joinRelationshipCount++;
                }
            }
        }
    }

    private void applyCurrentAffiliations(Connection connection,
                                           Map<Long,Map<String,MetricAccumulator>> metrics, Set<Long> scopeIds,
                                           Map<Long,List<CoverageScope>> scopes, ScopedIdentityTargets targets,
                                           EvidenceBudget budget) throws SQLException
    {
        Set<Long> p25Scopes = new LinkedHashSet<>();

        for(long scopeId: scopeIds)
        {
            if(scopes.getOrDefault(scopeId, List.of()).stream()
                .anyMatch(scope -> scope.protocolCode == 1))
            {
                p25Scopes.add(scopeId);
            }
        }

        if(p25Scopes.isEmpty())
        {
            return;
        }

        List<Object> parameters = new ArrayList<>();
        StringBuilder sql = new StringBuilder();
        targets.appendCte(sql, parameters, true);
        sql.append("""
            SELECT scope.scope_id, 2 AS identity_kind_code, affiliation.radio_id AS identity_id,
                affiliation.updated_at_ms, scope.protocol_code, scope.p25_system_key AS system_key,
                system.wacn, system.system_id, NULL AS p25_identity_state_code,
                NULL AS p25_home_wacn, NULL AS p25_home_system_id, NULL AS p25_home_talkgroup_id
            FROM trunked_identity_scope scope
            JOIN p25_radio_affiliation affiliation ON affiliation.system_key = scope.p25_system_key
            JOIN p25_system system ON system.system_key = scope.p25_system_key
            WHERE scope.scope_id IN (%s)
              AND
            """.formatted(placeholders(p25Scopes.size())));
        parameters.addAll(p25Scopes);
        targets.appendPredicate(sql, "scope.scope_id", "2", "affiliation.radio_id");
        sql.append("""

            UNION ALL

            SELECT scope.scope_id, 1 AS identity_kind_code, affiliation.talkgroup_id AS identity_id,
                affiliation.updated_at_ms, scope.protocol_code, scope.p25_system_key AS system_key,
                system.wacn, system.system_id, target.p25_identity_state_code,
                target.p25_home_wacn, target.p25_home_system_id, target.p25_home_talkgroup_id
            FROM trunked_identity_scope scope
            JOIN p25_radio_affiliation affiliation ON affiliation.system_key = scope.p25_system_key
            JOIN p25_system system ON system.system_key = scope.p25_system_key
            LEFT JOIN trunked_identity_summary target
              ON target.scope_id = scope.scope_id
             AND target.identity_kind_code = 1
             AND target.identity_id = affiliation.talkgroup_id
            WHERE scope.scope_id IN (%s)
              AND
            """.formatted(placeholders(p25Scopes.size())));
        parameters.addAll(p25Scopes);
        targets.appendPredicate(sql, "scope.scope_id", "1", "affiliation.talkgroup_id");
        sql.append("""
            ORDER BY 1, 2, 3
            LIMIT ?
            """);
        parameters.add(budget.queryLimit());
        List<Map<String,Object>> identities = queryRows(connection, sql.toString(), parameters.toArray());
        budget.consume(identities.size());

        identities = projectEvidence(identities, scopes, "scope_id", targets, budget);

        mResolver.resolveEvidenceAliases(connection, identities);

        for(Map<String,Object> row: identities)
        {
            MetricAccumulator accumulator = accumulator(metrics, row);

            if(accumulator != null && accumulator.currentAffiliationCount != null)
            {
                accumulator.currentAffiliationCount++;
                accumulator.observe(row.get("updated_at_ms"), row.get("updated_at_ms"));
            }
        }
    }

    private static List<Map<String,Object>> projectEvidence(List<Map<String,Object>> rows,
                                                            Map<Long,List<CoverageScope>> scopes,
                                                            String scopeIdColumn, ScopedIdentityTargets targets,
                                                            EvidenceBudget budget)
    {
        List<Map<String,Object>> projected = new ArrayList<>(rows.size());

        for(Map<String,Object> row: rows)
        {
            List<CoverageScope> projections = scopes.getOrDefault(number(row.get(scopeIdColumn)), List.of()).stream()
                .filter(scope -> targets.matches(row, scope))
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
        String scopeKey = text(row.get("scope_key"));
        return aliasId != null && scopeKey != null ?
            metrics.getOrDefault(aliasId, Map.of()).get(scopeKey) : null;
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

    private static Map<Long,List<CoverageScope>> scopeProjections(List<CoverageScope> scopes, boolean trunked)
    {
        Map<Long,List<CoverageScope>> result = new HashMap<>();

        for(CoverageScope scope: scopes)
        {
            if(scope.trunked == trunked)
            {
                result.computeIfAbsent(scope.numericId, ignored -> new ArrayList<>()).add(scope);
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

    /** One allocation budget shared by every summary source touched by a single alias enrichment. */
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
     * Compact, merged identity ranges and assigned Alias Lists derived solely from the alias rows in this response.
     * Every evidence query applies these predicates in SQLite before allocating result maps.
     */
    private static final class IdentityTargets
    {
        private final Map<Integer,Set<String>> mAliasLists;

        private IdentityTargets(Map<Integer,Set<String>> aliasLists)
        {
            mAliasLists = aliasLists;
        }

        private static IdentityTargets from(List<Map<String,Object>> aliases)
        {
            Map<TargetKey,List<IdentityRange>> ranges = new LinkedHashMap<>();
            Map<Integer,Set<String>> aliasLists = new LinkedHashMap<>();

            for(Map<String,Object> alias: aliases)
            {
                if(!isSupportedIdentity(alias))
                {
                    continue;
                }

                int protocol = protocolCode(text(alias.get("protocol")));
                String aliasList = text(alias.get("alias_list_name"));

                if(aliasList != null)
                {
                    aliasLists.computeIfAbsent(protocol, ignored -> new LinkedHashSet<>()).add(lower(aliasList));
                }

                IdentityRange range = identityRange(alias);

                if(range == null)
                {
                    continue;
                }

                addRange(ranges, new TargetKey(protocol, identityKindCode(alias)),
                    range.minimum(), range.maximum());
            }

            int listCount = aliasLists.values().stream().mapToInt(Set::size).sum();

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

            return new IdentityTargets(aliasLists);
        }

        private static void addRange(Map<TargetKey,List<IdentityRange>> ranges, TargetKey key,
                                     long minimum, long maximum)
        {
            ranges.computeIfAbsent(key, ignored -> new ArrayList<>()).add(new IdentityRange(minimum, maximum));
        }

        private void appendCoveragePredicate(StringBuilder sql, List<Object> parameters,
                                             String protocolColumn, String aliasListColumn)
        {
            if(mAliasLists.isEmpty())
            {
                sql.append("0 ");
                return;
            }

            sql.append('(');
            boolean first = true;

            for(Map.Entry<Integer,Set<String>> entry: mAliasLists.entrySet())
            {
                if(!first)
                {
                    sql.append(" OR ");
                }

                sql.append('(').append(protocolColumn).append(" = ? AND ").append(aliasListColumn)
                    .append(" COLLATE NOCASE IN (").append(placeholders(entry.getValue().size())).append("))");
                parameters.add(entry.getKey());
                parameters.addAll(entry.getValue());
                first = false;
            }

            sql.append(')');
            sql.append(' ');
        }

        private void appendAliasListPredicate(StringBuilder sql, List<Object> parameters, int protocol,
                                              String aliasListColumn)
        {
            Set<String> aliasLists = mAliasLists.getOrDefault(protocol, Set.of());

            if(aliasLists.isEmpty())
            {
                sql.append("0 ");
                return;
            }

            sql.append(aliasListColumn).append(" COLLATE NOCASE IN (")
                .append(placeholders(aliasLists.size())).append(')');
            parameters.addAll(aliasLists);
            sql.append(' ');
        }

    }

    private record ScopedTargetKey(boolean trunked, long scopeId, String projectionAliasList,
                                   int identityKindCode) {}

    private record SqlScopedTarget(long scopeId, int identityKindCode, long minimum, long maximum) {}

    /**
     * Correlates each selected alias range to only the receiver scopes where that alias is eligible.  Evidence SQL
     * consumes this bounded relation as a VALUES CTE, avoiding the protocol-wide list/range cross product that could
     * otherwise fill the response budget with identities from the wrong Alias List.
     */
    private static final class ScopedIdentityTargets
    {
        private static final String CTE_NAME = "requested_identity";
        private final Map<ScopedTargetKey,List<IdentityRange>> mRanges;

        private ScopedIdentityTargets(Map<ScopedTargetKey,List<IdentityRange>> ranges)
        {
            mRanges = ranges;
        }

        private static ScopedIdentityTargets from(List<Map<String,Object>> aliases,
                                                  Map<Long,Map<String,CoverageScope>> coverage)
        {
            Map<ScopedTargetKey,List<IdentityRange>> ranges = new LinkedHashMap<>();

            for(Map<String,Object> alias: aliases)
            {
                IdentityRange range = identityRange(alias);

                if(range == null || !isSupportedIdentity(alias))
                {
                    continue;
                }

                long aliasId = number(alias.get("alias_id"));
                int kind = identityKindCode(alias);

                for(CoverageScope scope: coverage.getOrDefault(aliasId, Map.of()).values())
                {
                    String projectionAliasList = scope.protocolCode == 1 ? null :
                        lower(text(alias.get("alias_list_name")));
                    ScopedTargetKey key = new ScopedTargetKey(scope.trunked, scope.numericId,
                        projectionAliasList, kind);
                    ranges.computeIfAbsent(key, ignored -> new ArrayList<>()).add(range);
                }
            }

            Map<ScopedTargetKey,List<IdentityRange>> merged = new LinkedHashMap<>();
            int rangeCount = 0;

            for(Map.Entry<ScopedTargetKey,List<IdentityRange>> entry: ranges.entrySet())
            {
                List<IdentityRange> result = mergeRanges(entry.getValue());
                rangeCount += result.size();

                if(rangeCount > MAX_SCOPED_TARGET_RANGES)
                {
                    throw new StatsApiException(413,
                        "Alias evidence exceeds the bounded scope and identity-range limit");
                }

                merged.put(entry.getKey(), result);
            }

            return new ScopedIdentityTargets(Map.copyOf(merged));
        }

        private void appendCte(StringBuilder sql, List<Object> parameters, boolean trunked)
        {
            Set<SqlScopedTarget> targets = new LinkedHashSet<>();

            for(Map.Entry<ScopedTargetKey,List<IdentityRange>> entry: mRanges.entrySet())
            {
                if(entry.getKey().trunked() == trunked)
                {
                    for(IdentityRange range: entry.getValue())
                    {
                        targets.add(new SqlScopedTarget(entry.getKey().scopeId(),
                            entry.getKey().identityKindCode(), range.minimum(), range.maximum()));
                    }
                }
            }

            sql.append("WITH ").append(CTE_NAME)
                .append("(scope_id, identity_kind_code, minimum, maximum) AS (");

            if(targets.isEmpty())
            {
                sql.append("SELECT NULL, NULL, NULL, NULL WHERE 0");
            }
            else
            {
                sql.append("VALUES ").append(String.join(",",
                    java.util.Collections.nCopies(targets.size(), "(?,?,?,?)")));

                for(SqlScopedTarget target: targets)
                {
                    parameters.add(target.scopeId());
                    parameters.add(target.identityKindCode());
                    parameters.add(target.minimum());
                    parameters.add(target.maximum());
                }
            }

            sql.append(") ");
        }

        private void appendPredicate(StringBuilder sql, String scopeColumn, String kindExpression,
                                     String identityExpression)
        {
            sql.append("EXISTS (SELECT 1 FROM ").append(CTE_NAME).append(" target WHERE target.scope_id = ")
                .append(scopeColumn).append(" AND (target.identity_kind_code = ").append(kindExpression)
                .append(" OR (target.identity_kind_code = 1 AND ").append(kindExpression)
                .append(" = 3)) AND ").append(identityExpression)
                .append(" BETWEEN target.minimum AND target.maximum) ");
        }

        private boolean matches(Map<String,Object> row, CoverageScope scope)
        {
            int kind = (int)number(row.get("identity_kind_code"));
            kind = kind == 3 ? 1 : kind;
            long identity = number(row.get("identity_id"));
            String projectionAliasList = scope.protocolCode == 1 ? null : lower(scope.canonicalAliasList);
            List<IdentityRange> ranges = mRanges.getOrDefault(new ScopedTargetKey(scope.trunked,
                scope.numericId, projectionAliasList, kind), List.of());
            return ranges.stream().anyMatch(range -> identity >= range.minimum() && identity <= range.maximum());
        }
    }

    private static final class CoverageScope
    {
        private final String key;
        private final long numericId;
        private final boolean trunked;
        private final int protocolCode;
        private final String protocol;
        private final String scopeToken;
        private final String contextKey;
        private final String guid;
        private final String systemName;
        private final String siteName;
        private final Long systemKey;
        private final Long wacn;
        private final Long systemId;
        private final Set<String> aliasLists = new HashSet<>();
        private String canonicalAliasList;

        private CoverageScope(String key, long numericId, boolean trunked, int protocolCode, String protocol,
                              String scopeToken, String contextKey, String guid, String systemName, String siteName,
                              Long systemKey, Long wacn, Long systemId)
        {
            this.key = key;
            this.numericId = numericId;
            this.trunked = trunked;
            this.protocolCode = protocolCode;
            this.protocol = protocol;
            this.scopeToken = scopeToken;
            this.contextKey = contextKey;
            this.guid = guid;
            this.systemName = systemName;
            this.siteName = siteName;
            this.systemKey = systemKey;
            this.wacn = wacn;
            this.systemId = systemId;
        }

        private static CoverageScope trunked(Map<String,Object> row)
        {
            long id = number(row.get("scope_id"));
            int protocolCode = (int)number(row.get("protocol_code"));
            boolean linkedP25 = protocolCode == 1;
            return new CoverageScope("scope:" + id, id, true, protocolCode,
                switch(protocolCode) { case 1 -> "P25"; case 3 -> "DMR"; case 4 -> "NXDN"; default -> "Unknown"; },
                text(row.get("scope_token")), linkedP25 ? null : text(row.get("context_key")),
                linkedP25 ? null : text(row.get("guid")), text(row.get("system_name")),
                linkedP25 ? null : text(row.get("site_name")), nullableNumber(row.get("system_key")),
                nullableNumber(row.get("wacn")), nullableNumber(row.get("system_id")));
        }

        private static CoverageScope conventionalDmr(Map<String,Object> row)
        {
            long id = number(row.get("context_id"));
            return new CoverageScope("context:" + id, id, false, 3, "DMR", null,
                text(row.get("context_key")), text(row.get("guid")), text(row.get("system_name")),
                text(row.get("site_name")), null, null, null);
        }

        private void addAliasList(String aliasList)
        {
            if(aliasList != null)
            {
                aliasLists.add(lower(aliasList));

                if(canonicalAliasList == null)
                {
                    canonicalAliasList = aliasList;
                }
            }
        }

        private void decorateEvidence(Map<String,Object> row)
        {
            row.put("scope_key", key);
            row.put("topology", trunked ? "TRUNKED" : "CONVENTIONAL");
            row.put("protocol_code", protocolCode);
            row.put("alias_list_name", canonicalAliasList);
            row.put("system_key", systemKey);
            row.put("wacn", wacn);
            row.put("system_id", systemId);
        }
    }

    private static final class MetricAccumulator
    {
        private final CoverageScope scope;
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
        private Long relationshipCount;
        private Long joinRelationshipCount;
        private Long currentAffiliationCount;
        private Long firstEvidenceMs;
        private Long lastEvidenceMs;

        private MetricAccumulator(CoverageScope scope)
        {
            this.scope = scope;
            boolean trunked = scope.trunked;
            recordedCount = 0L;
            streamedCount = 0L;
            grantCount = trunked && scope.protocolCode == 1 ? 0L : null;
            joinCount = trunked ? 0L : null;
            emergencyCount = trunked ? 0L : null;
            registerCount = trunked ? 0L : null;
            logoutCount = trunked ? 0L : null;
            denialCount = trunked ? 0L : null;
            dataCount = trunked ? 0L : null;
            otherSignalingCount = trunked ? 0L : null;
            relationshipCount = trunked ? 0L : null;
            joinRelationshipCount = trunked ? 0L : null;
            currentAffiliationCount = trunked && scope.protocolCode == 1 ? 0L : null;
        }

        private void addEvidence(Map<String,Object> row)
        {
            callCount += number(row.get("call_count"));
            encryptedEvidenceCount += number(row.get("encrypted_evidence_count"));
            recordedCount = addNullable(recordedCount, row.get("recorded_count"));
            streamedCount = addNullable(streamedCount, row.get("streamed_count"));
            grantCount = addNullable(grantCount, row.get("grant_count"));
            joinCount = addNullable(joinCount, row.get("join_count"));
            emergencyCount = addNullable(emergencyCount, row.get("emergency_count"));
            registerCount = addNullable(registerCount, row.get("register_count"));
            logoutCount = addNullable(logoutCount, row.get("logout_count"));
            denialCount = addNullable(denialCount, row.get("denial_count"));
            dataCount = addNullable(dataCount, row.get("data_count"));
            otherSignalingCount = addNullable(otherSignalingCount, row.get("other_signaling_count"));
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
            return scope.protocol + "\u0000" + scopeLabel() + "\u0000" + scope.key;
        }

        private String scopeLabel()
        {
            if(scope.systemName != null && scope.siteName != null && !scope.systemName.equals(scope.siteName))
            {
                return scope.systemName + " / " + scope.siteName;
            }

            if(scope.systemName != null)
            {
                return scope.systemName;
            }

            if(scope.siteName != null)
            {
                return scope.siteName;
            }

            return scope.scopeToken != null ? scope.scopeToken : scope.contextKey != null ? scope.contextKey :
                scope.key;
        }

        private Long metric(String field)
        {
            return switch(field)
            {
                case "call_count" -> callCount;
                case "recorded_count" -> recordedCount;
                case "streamed_count" -> streamedCount;
                case "encrypted_evidence_count" -> encryptedEvidenceCount;
                case "grant_count" -> grantCount;
                case "join_count" -> joinCount;
                case "emergency_count" -> emergencyCount;
                case "register_count" -> registerCount;
                case "logout_count" -> logoutCount;
                case "denial_count" -> denialCount;
                case "data_count" -> dataCount;
                case "other_signaling_count" -> otherSignalingCount;
                case "relationship_count" -> relationshipCount;
                case "join_relationship_count" -> joinRelationshipCount;
                case "current_affiliation_count" -> currentAffiliationCount;
                default -> null;
            };
        }

        private Map<String,Object> toMap()
        {
            Map<String,Object> row = new LinkedHashMap<>();
            row.put("scope_key", scope.key);
            row.put("scope_label", scopeLabel());
            row.put("topology", scope.trunked ? "TRUNKED" : "CONVENTIONAL");
            row.put("protocol", scope.protocol);
            row.put("system_name", scope.systemName);
            row.put("site_name", scope.siteName);
            row.put("scope_token", scope.scopeToken);
            row.put("context_key", scope.contextKey);
            row.put("guid", scope.guid);
            row.put("alias_list_name", scope.canonicalAliasList);
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
