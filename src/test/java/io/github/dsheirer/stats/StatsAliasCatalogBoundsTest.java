/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
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
    void activitySnapshotAcceptsExactlyOneHundredThousandAliasesAndRejectsMore() throws Exception
    {
        Path database = mTemporaryFolder.resolve("one-hundred-thousand-aliases.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
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
                VALUES (100001, 1, 'One too many', 'TALKGROUP', 'DMR', 100001)
                """);
            StatsApiException tooLarge = assertThrows(StatsApiException.class,
                () -> new StatsAliasCatalog(new StatsAliasResolver()).aliases(connection, request));
            assertEquals(413, tooLarge.status());
            assertEquals("alias_activity_too_large", tooLarge.code());
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
