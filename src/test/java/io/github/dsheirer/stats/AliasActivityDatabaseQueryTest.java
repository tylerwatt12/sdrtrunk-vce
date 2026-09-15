/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasActivityDatabaseQueryTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    @SuppressWarnings("unchecked")
    void completeListFilteringSortingAndPagingStayDatabaseDrivenAndBounded() throws Exception
    {
        AliasActivityRepresentativeTestDatabase.Fixture fixture =
            AliasActivityRepresentativeTestDatabase.createMoreThan(mTemporaryFolder.resolve("query.sqlite"));
        StatsAliasCatalog catalog = new StatsAliasCatalog(new StatsAliasResolver());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + fixture.database()))
        {
            StatsRequest calls = request(fixture, "logical_call_count", Map.of());
            long started = System.nanoTime();
            Map<String,Object> first = catalog.aliases(connection, calls);
            long coldMillis = elapsedMillis(started);
            List<Map<String,Object>> firstRows = (List<Map<String,Object>>)first.get("rows");
            assertEquals(100, firstRows.size());
            assertDescending(firstRows, "logical_call_count");

            long signalingStarted = System.nanoTime();
            List<Map<String,Object>> signaling = rows(catalog.aliases(connection,
                request(fixture, "signaling_observation_count", Map.of())));
            long signalingMillis = elapsedMillis(signalingStarted);
            long lastSeenStarted = System.nanoTime();
            List<Map<String,Object>> lastSeen = rows(catalog.aliases(connection,
                request(fixture, "last_evidence_ms", Map.of())));
            long lastSeenMillis = elapsedMillis(lastSeenStarted);
            long pageStarted = System.nanoTime();
            List<Map<String,Object>> secondPage = rows(catalog.aliases(connection,
                request(fixture, "logical_call_count", Map.of("offset", "100"))));
            long pageMillis = elapsedMillis(pageStarted);
            long deepPageStarted = System.nanoTime();
            List<Map<String,Object>> deepPage = rows(catalog.aliases(connection,
                request(fixture, "logical_call_count", Map.of("offset", "50000"))));
            long deepPageMillis = elapsedMillis(deepPageStarted);
            long namePageStarted = System.nanoTime();
            List<Map<String,Object>> namePage = rows(catalog.aliases(connection,
                request(fixture, "name", Map.of("direction", "asc", "offset", "50000"))));
            long namePageMillis = elapsedMillis(namePageStarted);
            long ascendingCallsStarted = System.nanoTime();
            List<Map<String,Object>> ascendingCalls = rows(catalog.aliases(connection,
                request(fixture, "logical_call_count", Map.of("direction", "asc", "offset", "50000"))));
            long ascendingCallsMillis = elapsedMillis(ascendingCallsStarted);
            assertDescending(signaling, "signaling_observation_count");
            assertDescending(lastSeen, "last_evidence_ms");
            Set<Long> firstIds = ids(firstRows);
            assertEquals(100, firstIds.size());
            assertEquals(100, ids(secondPage).size());
            assertEquals(100, ids(deepPage).size());
            assertEquals(100, ids(namePage).size());
            assertEquals(100, ids(ascendingCalls).size());
            assertConfigurationOrder(namePage, "name", false);
            assertMetricOrder(ascendingCalls, "logical_call_count", false);
            assertTrue(firstIds.stream().noneMatch(ids(secondPage)::contains));

            Map<String,List<Map<String,Object>>> firstMetricPages = Map.of(
                "logical_call_count", firstRows,
                "signaling_observation_count", signaling,
                "last_evidence_ms", lastSeen);
            Map<String,List<Map<String,Object>>> deepMetricPages = new LinkedHashMap<>();
            deepMetricPages.put("logical_call_count", deepPage);
            deepMetricPages.put("signaling_observation_count", rows(catalog.aliases(connection,
                request(fixture, "signaling_observation_count", Map.of("offset", "50000")))));
            deepMetricPages.put("last_evidence_ms", rows(catalog.aliases(connection,
                request(fixture, "last_evidence_ms", Map.of("offset", "50000")))));
            for(String metric: firstMetricPages.keySet())
            {
                assertEquals(expectedMetricIds(connection, fixture.aliasListId(), metric, true, 0, 100),
                    idsInOrder(firstMetricPages.get(metric)), metric + " first page is not the full-list result");
                assertEquals(expectedMetricIds(connection, fixture.aliasListId(), metric, true, 50_000, 100),
                    idsInOrder(deepMetricPages.get(metric)), metric + " deep page is not the full-list result");
            }

            List<Map<String,Object>> precedingDeepPage = rows(catalog.aliases(connection,
                request(fixture, "logical_call_count", Map.of("offset", "49900"))));
            List<Long> adjacentDeepIds = new ArrayList<>(idsInOrder(precedingDeepPage));
            adjacentDeepIds.addAll(idsInOrder(deepPage));
            assertEquals(200, new HashSet<>(adjacentDeepIds).size(),
                "Adjacent deep pages must not duplicate aliases");
            assertEquals(expectedMetricIds(connection, fixture.aliasListId(), "logical_call_count", true,
                49_900, 200), adjacentDeepIds, "Adjacent deep pages must not omit aliases");

            List<Map<String,Object>> used = rows(catalog.aliases(connection,
                request(fixture, "logical_call_count", Map.of("use", "used"))));
            assertTrue(used.stream().allMatch(row -> number(row.get("logical_call_count")) > 0));
            List<Map<String,Object>> unused = rows(catalog.aliases(connection,
                request(fixture, "logical_call_count", Map.of("use", "unused"))));
            assertTrue(unused.stream().allMatch(row -> number(row.get("logical_call_count")) == 0));
            long activityFilterStarted = System.nanoTime();
            List<Map<String,Object>> observed = rows(catalog.aliases(connection,
                request(fixture, "last_evidence_ms", Map.of("evidence", "observed",
                    "use", "used", "last_activity_after", "1700050000000",
                    "last_activity_before", "1700090000000"))));
            long activityFilterMillis = elapsedMillis(activityFilterStarted);
            assertTrue(observed.stream().allMatch(row -> "observed".equals(row.get("metrics_state")) &&
                number(row.get("logical_call_count")) > 0 &&
                number(row.get("last_evidence_ms")) >= 1_700_050_000_000L &&
                number(row.get("last_evidence_ms")) <= 1_700_090_000_000L));
            assertTrue(activityFilterMillis < 1_000,
                "observed/date/use filter took " + activityFilterMillis + " ms");
            long configurationFilterStarted = System.nanoTime();
            List<Map<String,Object>> configured = rows(catalog.aliases(connection,
                request(fixture, "name", Map.of("q", "alias 039", "group", "fire",
                    "record", "enabled", "matcher", "talkgroup"))));
            long configurationFilterMillis = elapsedMillis(configurationFilterStarted);
            assertFalse(configured.isEmpty());
            assertTrue(configured.stream().allMatch(row -> "Fire".equals(row.get("group")) &&
                number(row.get("record_enabled")) == 1 && "TALKGROUP".equals(row.get("matcher_type"))));
            assertTrue(configurationFilterMillis < 1_000,
                "compound text/configuration filter took " + configurationFilterMillis + " ms");

            String ascendingCallsPlan = plan(connection, StatsAliasCatalog.buildActivityQuery(
                request(fixture, "logical_call_count", Map.of("direction", "asc", "offset", "50000")),
                null, true, 100, 50_000, false));
            String descendingTypePlan = plan(connection, StatsAliasCatalog.buildActivityQuery(
                request(fixture, "type", Map.of("direction", "desc", "offset", "50000")),
                null, true, 100, 50_000, false));
            System.out.println("ALIAS_ACTIVITY_DIAGNOSTIC calls_asc_plan=" +
                ascendingCallsPlan.replace('\n', '|') + " type_desc_plan=" +
                descendingTypePlan.replace('\n', '|'));

            Map<String,Long> sortSweep = new LinkedHashMap<>();
            for(String field: List.of("name", "list", "family", "type", "matcher", "value", "group"))
            {
                for(String direction: List.of("asc", "desc"))
                {
                    long sortStarted = System.nanoTime();
                    List<Map<String,Object>> sorted = rows(catalog.aliases(connection, request(fixture, field,
                        Map.of("direction", direction, "offset", "50000"))));
                    long elapsed = elapsedMillis(sortStarted);
                    assertEquals(100, sorted.size());
                    assertConfigurationOrder(sorted, field, "desc".equals(direction));
                    assertTrue(elapsed < 1_000, field + " " + direction + " sort took " + elapsed + " ms");
                    sortSweep.put(field + '_' + direction, elapsed);
                }
            }
            for(String field: List.of("logical_call_count", "signaling_observation_count", "last_evidence_ms"))
            {
                for(String direction: List.of("asc", "desc"))
                {
                    long sortStarted = System.nanoTime();
                    List<Map<String,Object>> sorted = rows(catalog.aliases(connection, request(fixture, field,
                        Map.of("direction", direction, "offset", "50000"))));
                    long elapsed = elapsedMillis(sortStarted);
                    assertEquals(100, sorted.size());
                    assertMetricOrder(sorted, field, "desc".equals(direction));
                    assertTrue(elapsed < 1_000, field + " " + direction + " sort took " + elapsed + " ms");
                    sortSweep.put(field + '_' + direction, elapsed);
                }
            }

            StatsAliasCatalog.ActivityQuery query = StatsAliasCatalog.buildActivityQuery(calls, null,
                true, 101, 0, false);
            String plan = plan(connection, query);
            System.out.println("ALIAS_ACTIVITY_QUERY cold_ms=" + coldMillis + " signaling_ms=" +
                signalingMillis + " last_seen_ms=" + lastSeenMillis + " page_ms=" + pageMillis +
                " deep_page_ms=" + deepPageMillis + " name_deep_page_ms=" + namePageMillis +
                " calls_ascending_deep_page_ms=" + ascendingCallsMillis +
                " activity_filter_ms=" + activityFilterMillis +
                " configuration_filter_ms=" + configurationFilterMillis +
                " sort_sweep_ms=" + sortSweep +
                " plan=" + plan.replace('\n', '|'));
            assertTrue(plan.contains("SEARCH summary USING INDEX idx_alias_activity_calls"), plan);
            assertFalse(plan.contains("USE TEMP B-TREE FOR ORDER BY"), plan);
            assertFalse(plan.contains("radio_system_identity_summary"), plan);
            assertTrue(coldMillis < 2_000, "cold Activity page took " + coldMillis + " ms");
            assertTrue(signalingMillis < 1_000, "signaling sort took " + signalingMillis + " ms");
            assertTrue(lastSeenMillis < 1_000, "last-seen sort took " + lastSeenMillis + " ms");
            assertTrue(pageMillis < 1_000, "second page took " + pageMillis + " ms");
            assertTrue(deepPageMillis < 1_000, "deep page took " + deepPageMillis + " ms");
            assertTrue(namePageMillis < 1_000, "deep name page took " + namePageMillis + " ms");
            assertTrue(ascendingCallsMillis < 1_000,
                "deep ascending calls page took " + ascendingCallsMillis + " ms");
        }
    }

    private static StatsRequest request(AliasActivityRepresentativeTestDatabase.Fixture fixture, String sort,
                                        Map<String,String> additions)
    {
        java.util.Map<String,String> parameters = new java.util.LinkedHashMap<>();
        parameters.put("list", Long.toString(fixture.aliasListId()));
        parameters.put("sort", sort);
        parameters.put("direction", "desc");
        parameters.put("limit", "100");
        parameters.putAll(additions);
        return new StatsRequest(parameters);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> response)
    {
        return (List<Map<String,Object>>)response.get("rows");
    }

    private static void assertDescending(List<Map<String,Object>> rows, String field)
    {
        for(int index = 1; index < rows.size(); index++)
        {
            long previous = number(rows.get(index - 1).get(field));
            long current = number(rows.get(index).get(field));
            assertTrue(previous >= current, field + " is not descending at row " + index);
            if(previous == current)
            {
                assertTrue(number(rows.get(index - 1).get("alias_id")) <
                    number(rows.get(index).get("alias_id")), "alias.id tie-breaker is not ascending");
            }
        }
    }

    private static void assertMetricOrder(List<Map<String,Object>> rows, String field, boolean descending)
    {
        for(int index = 1; index < rows.size(); index++)
        {
            Long previous = nullableNumber(rows.get(index - 1).get(field));
            Long current = nullableNumber(rows.get(index).get(field));
            if(previous == null)
            {
                assertTrue(current == null, field + " does not keep NULL values last");
            }
            else if(current != null)
            {
                assertTrue(descending ? previous >= current : previous <= current,
                    field + " is not in " + (descending ? "descending" : "ascending") + " order");
                if(previous.equals(current))
                {
                    assertTrue(number(rows.get(index - 1).get("alias_id")) <
                        number(rows.get(index).get("alias_id")), "alias.id tie-breaker is not ascending");
                }
            }
        }
    }

    private static void assertConfigurationOrder(List<Map<String,Object>> rows, String field, boolean descending)
    {
        for(int index = 1; index < rows.size(); index++)
        {
            String previous = configurationSortKey(rows.get(index - 1), field);
            String current = configurationSortKey(rows.get(index), field);
            int comparison = previous.compareTo(current);
            assertTrue(descending ? comparison >= 0 : comparison <= 0,
                field + " is not in " + (descending ? "descending" : "ascending") + " order");
            if(comparison == 0)
            {
                assertTrue(number(rows.get(index - 1).get("alias_id")) <
                    number(rows.get(index).get("alias_id")), "alias.id tie-breaker is not ascending");
            }
        }
    }

    private static String configurationSortKey(Map<String,Object> row, String field)
    {
        return switch(field)
        {
            case "name" -> string(row.get("name")).toLowerCase(Locale.ROOT);
            case "list" -> string(row.get("alias_list_name")).toLowerCase(Locale.ROOT);
            case "family" -> string(row.get("family"));
            case "type" -> string(row.get("identity_type"));
            case "matcher" -> string(row.get("matcher_type"));
            case "group" -> string(row.get("group")).toLowerCase(Locale.ROOT);
            case "value" -> identifierSortKey(row);
            default -> throw new IllegalArgumentException("Unsupported test sort " + field);
        };
    }

    private static String identifierSortKey(Map<String,Object> row)
    {
        String matcher = string(row.get("matcher_type"));
        if("TALKGROUP_RANGE".equals(matcher) || "RADIO_ID_RANGE".equals(matcher))
        {
            return "%020d–%020d".formatted(number(row.get("min_value")), number(row.get("max_value")));
        }
        if(row.get("value") != null)
        {
            return "%020d".formatted(number(row.get("value")));
        }
        if(row.get("numeric_value") != null)
        {
            return "%020d".formatted(number(row.get("numeric_value")));
        }
        Object text = row.get("text_value") != null ? row.get("text_value") : row.get("tone_sequence");
        return string(text).toLowerCase(Locale.ROOT);
    }

    private static String string(Object value)
    {
        return value != null ? String.valueOf(value) : "";
    }

    private static Set<Long> ids(List<Map<String,Object>> rows)
    {
        Set<Long> ids = new HashSet<>();
        rows.forEach(row -> ids.add(number(row.get("alias_id"))));
        return ids;
    }

    private static List<Long> idsInOrder(List<Map<String,Object>> rows)
    {
        return rows.stream().map(row -> number(row.get("alias_id"))).toList();
    }

    private static List<Long> expectedMetricIds(Connection connection, long aliasListId, String requestedColumn,
                                                 boolean descending, int offset, int limit) throws Exception
    {
        String column = switch(requestedColumn)
        {
            case "logical_call_count" -> "logical_call_count";
            case "signaling_observation_count" -> "signaling_observation_count";
            case "last_evidence_ms" -> "last_evidence_ms";
            default -> throw new IllegalArgumentException("Unsupported metric " + requestedColumn);
        };
        String nullOrder = descending ? "" : column + " IS NULL ASC, ";
        String direction = descending ? " DESC" : " ASC";
        List<Long> expected = new ArrayList<>(limit);
        try(PreparedStatement statement = connection.prepareStatement(
            "SELECT alias_id FROM alias_activity_summary WHERE alias_list_id=? ORDER BY " + nullOrder +
                column + direction + ",alias_id ASC LIMIT ? OFFSET ?"))
        {
            statement.setLong(1, aliasListId);
            statement.setInt(2, limit);
            statement.setInt(3, offset);
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next())
                {
                    expected.add(rows.getLong(1));
                }
            }
        }
        return List.copyOf(expected);
    }

    private static String plan(Connection connection, StatsAliasCatalog.ActivityQuery query) throws Exception
    {
        StringBuilder plan = new StringBuilder();
        try(PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + query.sql()))
        {
            for(int index = 0; index < query.parameters().size(); index++)
            {
                statement.setObject(index + 1, query.parameters().get(index));
            }
            try(ResultSet rows = statement.executeQuery())
            {
                while(rows.next())
                {
                    if(!plan.isEmpty())
                    {
                        plan.append('\n');
                    }
                    plan.append(rows.getString("detail"));
                }
            }
        }
        return plan.toString();
    }

    private static long number(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static Long nullableNumber(Object value)
    {
        return value instanceof Number number ? number.longValue() : null;
    }

    private static long elapsedMillis(long start)
    {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
