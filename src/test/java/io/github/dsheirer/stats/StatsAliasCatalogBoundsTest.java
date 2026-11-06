/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkTestDatabase;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsAliasCatalogBoundsTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void enrichmentAdmissionRejectsExcessConcurrencyAndAlwaysReleasesPermits() throws Exception
    {
        StatsAliasCatalog.EnrichmentAdmission admission = new StatsAliasCatalog.EnrichmentAdmission(2);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);

        try(var executor = Executors.newVirtualThreadPerTaskExecutor())
        {
            Future<String> first = executor.submit(() -> admission.execute(() -> awaitRelease(entered, release)));
            Future<String> second = executor.submit(() -> admission.execute(() -> awaitRelease(entered, release)));
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            StatsApiException busy = assertThrows(StatsApiException.class,
                () -> admission.execute(() -> "unexpected"));
            assertEquals(429, busy.status());
            assertEquals("alias_enrichment_busy", busy.code());

            release.countDown();
            assertEquals("complete", first.get(5, TimeUnit.SECONDS));
            assertEquals("complete", second.get(5, TimeUnit.SECONDS));
            assertEquals("reused", admission.execute(() -> "reused"));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void activityCatalogAcceptsExactlyOneHundredThousandAliasesAndMore() throws Exception
    {
        Path database = mTemporaryFolder.resolve("one-hundred-thousand-aliases.sqlite");

        SdrTrunkTestDatabase.create(database);
        try(Connection connection = StatsPairedTestDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            statement.executeUpdate("DELETE FROM alias_list_unmatched_talkgroup_scan_list_membership");
            statement.executeUpdate("DELETE FROM alias");
            statement.executeUpdate("DELETE FROM alias_list");
            statement.executeUpdate("INSERT INTO alias_list(id, name, family) VALUES (1, 'Large', 'DMR')");
            statement.executeUpdate("""
                WITH RECURSIVE sequence(value) AS (
                    VALUES(1)
                    UNION ALL
                    SELECT value + 1 FROM sequence WHERE value < 100000
                )
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                SELECT value, 1, printf('Alias %06d', value), 'TALKGROUP', 'DMR', value
                FROM sequence
                """);

            StatsRequest request = new StatsRequest(Map.of("list", "1", "sort", "logical_call_count",
                "direction", "desc", "limit", "1"));
            Map<String,Object> response = new StatsAliasCatalog(new StatsAliasResolver()).aliases(connection, request);
            List<Map<String,Object>> rows = (List<Map<String,Object>>)response.get("rows");
            assertEquals(1L, ((Number)rows.getFirst().get("alias_id")).longValue());
            assertTrue((Boolean)response.get("has_more"));
            String idPlan = queryPlan(connection,
                "SELECT id FROM alias WHERE alias_list_id=? ORDER BY id LIMIT ?", 1, 100001);
            assertTrue(idPlan.contains("idx_alias_list_id"), idPlan);
            assertTrue(!idPlan.contains("USE TEMP B-TREE"), idPlan);
            String namePlan = queryPlan(connection, """
                SELECT id FROM alias WHERE alias_list_id=?
                ORDER BY lower(coalesce(name, '')), id LIMIT ?
                """, 1, 101);
            assertTrue(namePlan.contains("idx_alias_list_name_sort"), namePlan);
            assertTrue(!namePlan.contains("USE TEMP B-TREE"), namePlan);

            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value)
                VALUES (100001, 1, 'Alias 100001', 'TALKGROUP', 'DMR', 100001)
                """);
            Map<String,Object> beyond = new StatsAliasCatalog(new StatsAliasResolver()).aliases(connection,
                new StatsRequest(Map.of("list", "1", "sort", "name", "direction", "asc",
                    "limit", "1", "offset", "100000")));
            List<Map<String,Object>> beyondRows = (List<Map<String,Object>>)beyond.get("rows");
            assertEquals(1, beyondRows.size());
            assertEquals(100001L, ((Number)beyondRows.getFirst().get("alias_id")).longValue());

            StatsApiException interactiveSelection = assertThrows(StatsApiException.class,
                () -> new StatsAliasCatalog(new StatsAliasResolver()).matchingAliasIds(connection,
                    new StatsRequest(Map.of("list", "1"))));
            assertEquals(413, interactiveSelection.status());
            assertEquals("alias_selection_too_large", interactiveSelection.code());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void configurationSortsStayBoundedWithOneHundredThousandPopulatedAliases() throws Exception
    {
        Path database = mTemporaryFolder.resolve("configuration-sorts.sqlite");
        SdrTrunkTestDatabase.create(database);
        try(Connection connection = StatsPairedTestDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM alias_list_unmatched_talkgroup_scan_list_membership");
            statement.executeUpdate("DELETE FROM alias");
            statement.executeUpdate("DELETE FROM alias_list");
            statement.executeUpdate("DELETE FROM scan_list");
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES (1,'Large','DMR')");
            statement.executeUpdate("""
                INSERT INTO scan_list(id,sort_order,name,published,is_default)
                VALUES (1,0,'Dispatch',1,0),(2,1,'Fire',1,0)
                """);
            for(int destination = 1; destination <= 3; destination++)
            {
                statement.executeUpdate("""
                    INSERT INTO configuration_broadcast_stream(configuration_id,sort_order,config_json)
                    VALUES ('00000000-0000-4000-8000-%012d',%d,'{"name":"Stream %d"}')
                    """.formatted(destination, destination, destination));
            }
            statement.executeUpdate("""
                WITH RECURSIVE sequence(value) AS (
                    VALUES(1) UNION ALL SELECT value+1 FROM sequence WHERE value<100000)
                INSERT INTO alias(id,alias_list_id,name,description,matcher_type,protocol,value,
                    record_enabled,stream_as_talkgroup)
                SELECT value,1,printf('Alias %%06d',value),
                    CASE WHEN value%%10=0 THEN ' ' WHEN value%%10=9 THEN NULL
                         ELSE printf('%%s %%06d', CASE WHEN value%%2=0 THEN 'ZONE' ELSE 'zone' END,
                             100000-value) END,
                    'TALKGROUP','DMR',value,value%%2,
                    CASE WHEN value%%5=0 THEN NULL ELSE value%%100 END
                FROM sequence
                """.replace("%%", "%"));
            statement.executeUpdate("""
                INSERT INTO alias_scan_list_membership(alias_id,scan_list_id)
                SELECT alias.id,list.id FROM alias CROSS JOIN scan_list list WHERE list.id<=alias.id%3
                """);
            statement.executeUpdate("""
                INSERT INTO alias_broadcast_channel(alias_id,broadcast_configuration_id)
                SELECT alias.id,stream.configuration_id FROM alias
                CROSS JOIN configuration_broadcast_stream stream WHERE stream.sort_order<=alias.id%4
                """);
            String membershipPlan = queryPlan(connection,
                "SELECT count(*) FROM alias_scan_list_membership WHERE alias_id=?", 1);
            String streamPlan = queryPlan(connection,
                "SELECT count(*) FROM alias_broadcast_channel WHERE alias_id=?", 1);
            assertTrue(membershipPlan.contains("SEARCH"), membershipPlan);
            assertTrue(streamPlan.contains("SEARCH"), streamPlan);
            assertTrue(!membershipPlan.contains("SCAN "), membershipPlan);
            assertTrue(!streamPlan.contains("SCAN "), streamPlan);

            List<Integer> ids = java.util.stream.IntStream.rangeClosed(1,100000).boxed().toList();
            StatsAliasCatalog catalog = new StatsAliasCatalog(new StatsAliasResolver());
            for(String sort: List.of("name", "description", "record", "scan_lists", "stream_destinations",
                "stream_as_talkgroup"))
            {
                for(boolean descending: List.of(false,true))
                {
                    java.util.Comparator<Integer> values = switch(sort)
                    {
                        case "description" -> java.util.Comparator.comparing(id ->
                            id % 10 == 0 || id % 10 == 9 ? null : 100000-id,
                            java.util.Comparator.nullsLast(descending ?
                                java.util.Comparator.<Integer>reverseOrder() : java.util.Comparator.<Integer>naturalOrder()));
                        case "record" -> java.util.Comparator.comparingInt(id -> id % 2);
                        case "scan_lists" -> java.util.Comparator.comparingInt(id -> id % 3);
                        case "stream_destinations" -> java.util.Comparator.comparingInt(id -> id % 4);
                        case "stream_as_talkgroup" -> java.util.Comparator.comparing(id ->
                            id % 5 == 0 ? null : id % 100,
                            java.util.Comparator.nullsLast(descending ?
                                java.util.Comparator.<Integer>reverseOrder() : java.util.Comparator.<Integer>naturalOrder()));
                        default -> java.util.Comparator.naturalOrder();
                    };
                    if(descending && !List.of("description","stream_as_talkgroup").contains(sort))
                    {
                        values = values.reversed();
                    }
                    List<Integer> expected = ids.stream().sorted(values.thenComparingInt(id -> id)).toList();
                    for(int offset: List.of(0,50000,99900))
                    {
                        long start = System.nanoTime();
                        Map<String,Object> page = catalog.aliases(connection, new StatsRequest(Map.of(
                            "list","1", "include_activity","false", "sort",sort,
                            "direction",descending ? "desc" : "asc", "limit","100",
                            "offset",String.valueOf(offset))));
                        double elapsedMs = (System.nanoTime()-start)/1_000_000.0;
                        System.out.printf("CONFIG_SORT sort=%s direction=%s offset=%d ms=%.2f%n",
                            sort,descending ? "desc" : "asc",offset,elapsedMs);
                        List<Map<String,Object>> rows = (List<Map<String,Object>>)page.get("rows");
                        assertEquals(expected.subList(offset,offset+100).stream().map(Integer::longValue).toList(),
                            rows.stream().map(row -> ((Number)row.get("alias_id")).longValue()).toList(),
                            sort + " descending=" + descending + " offset=" + offset);
                        assertEquals(offset<99900, page.get("has_more"));
                        assertTrue(rows.stream().noneMatch(row -> row.containsKey("logical_call_count")));
                    }
                    // Custom/Activity use the same routed sort keys and must preserve configuration ordering.
                    Map<String,Object> activity = catalog.aliases(connection, new StatsRequest(Map.of(
                        "list","1", "sort",sort, "direction",descending ? "desc" : "asc", "limit","5")));
                    List<Map<String,Object>> rows = (List<Map<String,Object>>)activity.get("rows");
                    assertEquals(expected.subList(0,5).stream().map(Integer::longValue).toList(),
                        rows.stream().map(row -> ((Number)row.get("alias_id")).longValue()).toList());
                }
            }
            Map<String,Object> filtered = catalog.aliases(connection, new StatsRequest(Map.of(
                "list","1", "include_activity","false", "sort","scan_lists", "direction","desc",
                "record","enabled", "q","Alias 000", "limit","100")));
            List<Map<String,Object>> filteredRows = (List<Map<String,Object>>)filtered.get("rows");
            assertTrue(filteredRows.stream().allMatch(row -> ((Number)row.get("alias_id")).intValue()%2==1));
            assertEquals(ids.stream().filter(id -> id<1000 && id%2==1)
                .sorted(java.util.Comparator.<Integer>comparingInt(id -> id%3).reversed()
                    .thenComparingInt(id -> id)).limit(100).map(Integer::longValue).toList(),
                filteredRows.stream().map(row -> ((Number)row.get("alias_id")).longValue()).toList());
        }
    }

    @Test
    void analyticalExportSplitsWideListSelectionsWithoutChangingUnavailableMetrics() throws Exception
    {
        Path database = mTemporaryFolder.resolve("wide-export.sqlite");
        SdrTrunkTestDatabase.create(database);
        try(Connection connection = StatsPairedTestDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                WITH RECURSIVE lists(value) AS (
                    VALUES(10000) UNION ALL SELECT value+1 FROM lists WHERE value<10599)
                INSERT INTO alias_list(id,name,family) SELECT value,'List '||value,'DMR' FROM lists
                """);
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value)
                SELECT id,id,'Alias '||id,'TALKGROUP','DMR',1 FROM alias_list WHERE id>=10000
                """);
            List<Map<String,Object>> rows = new StatsAliasCatalog(new StatsAliasResolver()).exportRows(connection,
                new StatsRequest(Map.of("q","Alias 10","sort","name","direction","asc")), 10000);
            assertEquals(600, rows.size(), "The bulk export must retain selections beyond one query's list bound");
            assertTrue(rows.stream().allMatch(row -> row.get("relationship_count") == null &&
                row.get("join_relationship_count") == null && row.get("current_affiliation_count") == null));
        }
    }

    private static String queryPlan(Connection connection, String sql, Object... parameters) throws Exception
    {
        StringBuilder plan = new StringBuilder();
        try(PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql))
        {
            for(int index = 0; index < parameters.length; index++)
            {
                statement.setObject(index + 1, parameters[index]);
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

    private static String awaitRelease(CountDownLatch entered, CountDownLatch release)
    {
        entered.countDown();

        try
        {
            if(!release.await(5, TimeUnit.SECONDS))
            {
                throw new AssertionError("Timed out waiting to release enrichment permit");
            }
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while holding enrichment permit", e);
        }

        return "complete";
    }
}
