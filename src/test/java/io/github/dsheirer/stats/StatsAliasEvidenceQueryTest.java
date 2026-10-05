/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.sqlite.ProgressHandler;

class StatsAliasEvidenceQueryTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void requestedIdentitiesSeekExistingOwnerKeysInsteadOfGlobalCanonicalRows() throws Exception
    {
        try(Connection connection = fixture())
        {
            List<Long> requested = List.of(1001L, 1002L, 1003L, 1004L, 1005L, 1006L, 2001L, 2003L, 2004L);
            String sql = StatsAliasResolver.p25LocalEvidenceSql(requested.size());
            String plan = plan(connection, sql, requested);

            assertTrue(plan.contains("SEARCH presence USING PRIMARY KEY (radio_system_id=? AND radio_identity_id=?)"),
                plan);
            assertTrue(plan.contains("SEARCH affiliation USING PRIMARY KEY (radio_system_id=? AND radio_identity_id=?)"),
                plan);
            assertTrue(plan.contains("idx_trunked_radio_affiliation_talkgroup (radio_system_id=? AND talkgroup_identity_id=?)"),
                plan);
            assertTrue(plan.contains("idx_receiver_activity_event_source_working_evidence (source_identity_summary_id=?)"),
                plan);
            assertTrue(plan.contains("SEARCH member USING COVERING INDEX idx_activity_event_member_identity_channel_local " +
                "(identity_summary_id=?)"), plan);
            assertFalse(plan.contains("idx_receiver_activity_event_id_channel"), plan);
            assertFalse(plan.contains("idx_radio_system_identity_p25_subscriber"), plan);
            assertFalse(plan.contains("SCAN presence"), plan);
            assertFalse(plan.contains("SCAN affiliation"), plan);
            assertFalse(plan.contains("SCAN event"), plan);

            AtomicInteger progressCalls = new AtomicInteger();
            ProgressHandler.setHandler(connection, 1_000, new ProgressHandler()
            {
                @Override
                protected int progress()
                {
                    progressCalls.incrementAndGet();
                    return 0;
                }
            });
            try
            {
                assertEquals(List.of(
                    List.of(1001L, 1L, 501L), List.of(1002L, 1L, 601L), List.of(1003L, 1L, 601L),
                    List.of(1004L, 1L, 301L), List.of(1006L, 1L, 501L), List.of(2001L, 2L, 502L),
                    List.of(2003L, 2L, 602L), List.of(2004L, 2L, 302L)), evidence(connection, sql, requested));
            }
            finally
            {
                ProgressHandler.clearHandler(connection);
            }
            assertTrue(progressCalls.get() < 100,
                "A small request revisited global current-state or canonical identities: " + progressCalls);
        }
    }

    @Test
    void canonicalFallbackKeepsPrimaryKeySeekWithMisleadingSubscriberIndexStatistics() throws Exception
    {
        try(Connection connection = fixture(); Statement statement = connection.createStatement())
        {
            // Simulate statistics gathered when the partial canonical index was almost empty. Retained summary
            // growth can otherwise make SQLite visit every unrelated canonical row for each requested identity.
            assertEquals(1, statement.executeUpdate("""
                UPDATE sqlite_stat1 SET stat='1 1' WHERE idx='idx_radio_system_identity_p25_subscriber'
                """));
            boolean hasSamples;
            try(ResultSet tables = statement.executeQuery(
                "SELECT 1 FROM sqlite_schema WHERE name='sqlite_stat4'"))
            {
                hasSamples = tables.next();
            }
            if(hasSamples)
            {
                statement.executeUpdate("""
                    DELETE FROM sqlite_stat4 WHERE idx='idx_radio_system_identity_p25_subscriber'
                    """);
            }
            statement.execute("ANALYZE sqlite_schema");

            List<Long> requested = List.of(1002L, 1005L);
            String sql = StatsAliasResolver.p25LocalEvidenceSql(requested.size());
            String unhintedSql = sql.replace("summary NOT INDEXED", "summary");
            String unhintedPlan = plan(connection, unhintedSql, requested);
            assertTrue(unhintedPlan.contains("idx_radio_system_identity_p25_subscriber " +
                "(p25_subscriber_identity_id>?)"), unhintedPlan);
            String boundedPlan = plan(connection, sql, requested);
            assertFalse(boundedPlan.contains("idx_radio_system_identity_p25_subscriber"), boundedPlan);
            assertTrue(boundedPlan.contains("SEARCH summary USING INTEGER PRIMARY KEY (rowid=?)"), boundedPlan);

            AtomicInteger progressCalls = new AtomicInteger();
            ProgressHandler.setHandler(connection, 1_000, new ProgressHandler()
            {
                @Override
                protected int progress()
                {
                    progressCalls.incrementAndGet();
                    return 0;
                }
            });
            List<List<Long>> boundedEvidence;
            try
            {
                boundedEvidence = evidence(connection, sql, requested);
            }
            finally
            {
                ProgressHandler.clearHandler(connection);
            }
            assertEquals(List.of(List.of(1002L, 1L, 601L)), boundedEvidence);
            assertEquals(evidence(connection, unhintedSql, requested), boundedEvidence);
            assertTrue(progressCalls.get() < 100,
                "Misleading partial-index statistics revisited unrelated canonical rows: " + progressCalls);
        }
    }

    @Test
    void preservesCompactPrecedenceWorkingAddressesAndAliasListOwnership() throws Exception
    {
        try(Connection connection = fixture())
        {
            List<Map<String,Object>> radios = new ArrayList<>(List.of(
                identity(1001, 1, 2, 900, null), identity(1002, 1, 2, 9100, 91L),
                identity(1003, 1, 2, 9101, 92L), identity(1005, 1, 2, 9102, 93L),
                identity(1006, 1, 2, 901, null), identity(2001, 2, 2, 900, null),
                identity(2003, 2, 2, 9101, 92L)));
            StatsAliasResolver resolver = new StatsAliasResolver();
            resolver.enrichCanonicalSystemRadios(connection, radios, "identity_summary_id", "identity_id", "alias_");
            assertEquals("Local Unit", radios.get(0).get("alias_name"));
            assertEquals("Working Unit", radios.get(1).get("alias_name"));
            assertEquals("Working Unit", radios.get(2).get("alias_name"),
                "Compact Working-ID evidence must suppress conflicting detailed-event fallback");
            assertNull(radios.get(3).get("alias_name"),
                "A canonical subscriber without Working-ID evidence cannot use its retained local address");
            assertEquals("Local Unit", radios.get(4).get("alias_name"),
                "An ordinary identity uses its local address even when the event also carries a Working ID");
            assertEquals("Other Local Unit", radios.get(5).get("alias_name"));
            assertEquals("Other Working Unit", radios.get(6).get("alias_name"));

            List<Map<String,Object>> groups = new ArrayList<>(List.of(
                identity(1004, 1, 1, 300, null), identity(2004, 2, 1, 300, null)));
            resolver.enrichCanonicalSystemTalkgroups(connection, groups,
                "identity_summary_id", "identity_id", "alias_");
            assertEquals("Dispatch", groups.get(0).get("alias_name"));
            assertEquals("Other Dispatch", groups.get(1).get("alias_name"));
        }
    }

    @Test
    void historicalMembersRetainPositiveAddressEvidenceWithoutCurrentAffiliations() throws Exception
    {
        try(Connection connection = fixture(); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM trunked_radio_affiliation WHERE talkgroup_identity_id=1004");
            assertEquals(List.of(List.of(1004L, 1L, 301L)),
                evidence(connection, StatsAliasResolver.p25LocalEvidenceSql(1), List.of(1004L)));
            List<Map<String,Object>> groups = new ArrayList<>(List.of(identity(1004, 1, 1, 300, null)));
            new StatsAliasResolver().enrichCanonicalSystemTalkgroups(connection, groups,
                "identity_summary_id", "identity_id", "alias_");
            assertEquals("Dispatch", groups.getFirst().get("alias_name"));
        }
    }

    @Test
    void memberChannelCoverSkipsRepeatedHistoricalEvidence() throws Exception
    {
        try(Connection connection = fixture(); Statement statement = connection.createStatement())
        {
            List<Long> requested = List.of(1004L);
            String sql = StatsAliasResolver.p25LocalEvidenceSql(1);
            int before = evidenceWork(connection, sql, requested);
            statement.executeUpdate("""
                WITH digits(value) AS (VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9)),
                     observations(value) AS (
                         SELECT a.value+10*b.value+100*c.value+1000*d.value+10000*e.value
                         FROM digits a CROSS JOIN digits b CROSS JOIN digits c
                         CROSS JOIN digits d CROSS JOIN digits e
                     )
                INSERT INTO receiver_activity_event(channel_id,radio_system_id,observed_at_ms,action_code)
                SELECT 1,1,1000+value,12 FROM observations WHERE value<50000
                """);
            statement.executeUpdate("""
                INSERT INTO activity_event_identity_member(event_id,radio_system_id,identity_summary_id,
                    observed_local_id,channel_id)
                SELECT id,1,1004,301,channel_id FROM receiver_activity_event WHERE observed_at_ms>=1000
                """);
            statement.execute("ANALYZE");
            int after = evidenceWork(connection, sql, requested);
            assertTrue(after <= before * 2 + 50,
                "Repeated member evidence caused parent-event lookups or a full duplicate scan: " + before + " -> " + after);
        }
    }

    private static int evidenceWork(Connection connection, String sql, List<Long> requested) throws Exception
    {
        AtomicInteger callbacks = new AtomicInteger();
        ProgressHandler.setHandler(connection, 1_000, new ProgressHandler()
        {
            @Override protected int progress()
            {
                callbacks.incrementAndGet();
                return 0;
            }
        });
        try
        {
            assertEquals(List.of(List.of(1004L,1L,301L)), evidence(connection,sql,requested));
            return callbacks.get();
        }
        finally
        {
            ProgressHandler.clearHandler(connection);
        }
    }

    @Test
    void memberEvidenceUsesEachSavedChannelsAssignedAliasList() throws Exception
    {
        try(Connection connection = fixture(); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,
                    site_name,name,alias_list_id,auto_start,decoder_type,primary_frequency_hz,config_json)
                VALUES ('10000000-0000-4000-8000-000000000003','TRUNKED',0,'System','Site','Other',2,0,
                    'P25_PHASE1',852000000,'{}')
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,
                    radio_system_id,radio_system_assigned_at_ms)
                VALUES (3,'10000000-0000-4000-8000-000000000003',1,2,1,1)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(channel_id,radio_system_id,observed_at_ms,action_code)
                VALUES (3,1,8,12)
                """);
            statement.executeUpdate("""
                INSERT INTO activity_event_identity_member(event_id,radio_system_id,identity_summary_id,
                    observed_local_id,channel_id) VALUES (6,1,1004,302,3)
                """);
            assertEquals(List.of(List.of(1004L,1L,301L), List.of(1004L,2L,302L)),
                evidence(connection,StatsAliasResolver.p25LocalEvidenceSql(1),List.of(1004L)));
            List<Map<String,Object>> groups = new ArrayList<>(List.of(identity(1004,1,1,300,null)));
            new StatsAliasResolver().enrichCanonicalSystemTalkgroups(connection,groups,
                "identity_summary_id","identity_id","alias_");
            assertNull(groups.getFirst().get("alias_name"),
                "Different labels from historical members on assigned Alias Lists cannot claim consensus");
        }
    }

    private Connection fixture() throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" +
            mTemporaryFolder.resolve("alias-evidence.sqlite"));
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
            statement.executeUpdate("DELETE FROM alias_list_unmatched_talkgroup_scan_list_membership");
            statement.executeUpdate("DELETE FROM alias_list");
            statement.executeUpdate("INSERT INTO alias_list(id, name, family) VALUES (1, 'Selected', 'P25'), (2, 'Other', 'P25')");
            statement.executeUpdate("""
                INSERT INTO alias(id, alias_list_id, name, matcher_type, protocol, value) VALUES
                    (1, 1, 'Local Unit', 'RADIO_ID', 'APCO25', 501),
                    (2, 1, 'Working Unit', 'RADIO_ID', 'APCO25', 601),
                    (3, 1, 'Conflicting Detail', 'RADIO_ID', 'APCO25', 603),
                    (4, 1, 'Dispatch', 'TALKGROUP', 'APCO25', 301),
                    (5, 2, 'Other Local Unit', 'RADIO_ID', 'APCO25', 502),
                    (6, 2, 'Other Working Unit', 'RADIO_ID', 'APCO25', 602),
                    (7, 2, 'Other Dispatch', 'TALKGROUP', 'APCO25', 302)
                """);
            for(int system = 1; system <= 2; system++)
            {
                String configuration = "10000000-0000-4000-8000-00000000000" + system;
                statement.executeUpdate("""
                    INSERT INTO configuration_channel(configuration_id, channel_kind, sort_order, system_name,
                        site_name, name, alias_list_id, auto_start, decoder_type, primary_frequency_hz, config_json)
                    VALUES ('%s', 'TRUNKED', 0, 'System', 'Site', 'Control', %d, 0, 'P25_PHASE1', 851000000, '{}')
                    """.formatted(configuration, system));
                statement.executeUpdate("""
                    INSERT INTO radio_system(id, system_key, protocol_code, address_domain_code, p25_wacn,
                        p25_system_id, first_seen_ms, last_seen_ms)
                    VALUES (%d, 'p25:bee00:00%d', 1, 0, 0xBEE00, %d, 1, 2)
                    """.formatted(system, system, system));
                statement.executeUpdate("""
                    INSERT INTO receiver_channel(id, configuration_id, first_seen_ms, last_seen_ms,
                        radio_system_id, radio_system_assigned_at_ms)
                    VALUES (%d, '%s', 1, 2, %d, 1)
                    """.formatted(system, configuration, system));
            }
            statement.executeUpdate("""
                INSERT INTO p25_subscriber_identity(id, home_wacn, home_system_id, subscriber_id)
                VALUES (91, 0xABCDE, 0x123, 9100), (92, 0xABCDE, 0x123, 9101), (93, 0xABCDE, 0x123, 9102)
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id, radio_system_id, identity_kind_code,
                    identity_id, home_wacn, home_system_id, p25_subscriber_identity_id, first_seen_ms, last_seen_ms)
                VALUES (1001, 1, 2, 900, -1, -1, NULL, 1, 2),
                       (1002, 1, 2, 9100, 0xABCDE, 0x123, 91, 1, 2),
                       (1003, 1, 2, 9101, 0xABCDE, 0x123, 92, 1, 2),
                       (1004, 1, 1, 300, 0xBEE00, 1, NULL, 1, 2),
                       (1005, 1, 2, 9102, 0xABCDE, 0x123, 93, 1, 2),
                       (1006, 1, 2, 901, -1, -1, NULL, 1, 2),
                       (2001, 2, 2, 900, -1, -1, NULL, 1, 2),
                       (2003, 2, 2, 9101, 0xABCDE, 0x123, 92, 1, 2),
                       (2004, 2, 1, 300, 0xBEE00, 2, NULL, 1, 2),
                       (2900, 2, 1, 400, 0xBEE00, 2, NULL, 1, 2)
                """);
            statement.executeUpdate("""
                WITH RECURSIVE values_to_insert(value) AS (
                    SELECT 1 UNION ALL SELECT value + 1 FROM values_to_insert WHERE value < 4000)
                INSERT INTO p25_subscriber_identity(id, home_wacn, home_system_id, subscriber_id)
                SELECT 10000 + value, 0xABCDE, 0x123, 100000 + value FROM values_to_insert
                """);
            statement.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id, radio_system_id, identity_kind_code, identity_id,
                    home_wacn, home_system_id, p25_subscriber_identity_id, first_seen_ms, last_seen_ms)
                SELECT id, 2, 2, subscriber_id, home_wacn, home_system_id, id, 1, 2
                FROM p25_subscriber_identity WHERE id > 10000
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence(radio_system_id, radio_identity_id, channel_id,
                    observed_local_id, observed_working_id, evidence_code, confirmed_at_ms)
                VALUES (1, 1001, 1, 501, NULL, 1, 2), (1, 1003, 1, 501, 601, 1, 2),
                       (2, 2001, 2, 502, NULL, 1, 2), (2, 2003, 2, 502, 602, 1, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_channel_presence(radio_system_id, radio_identity_id, channel_id,
                    observed_local_id, observed_working_id, evidence_code, confirmed_at_ms)
                SELECT 2, id, 2, identity_id, identity_id, 1, 2 FROM radio_system_identity_summary WHERE id > 10000
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_affiliation(radio_system_id, radio_identity_id, talkgroup_identity_id,
                    channel_id, radio_observed_local_id, radio_observed_working_id,
                    talkgroup_observed_local_id, confirmed_at_ms)
                VALUES (1, 1001, 1004, 1, 501, NULL, 301, 2), (1, 1003, 1004, 1, 501, 601, 301, 2),
                       (2, 2001, 2004, 2, 502, NULL, 302, 2), (2, 2003, 2004, 2, 502, 602, 302, 2)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_radio_affiliation(radio_system_id, radio_identity_id, talkgroup_identity_id,
                    channel_id, radio_observed_local_id, radio_observed_working_id,
                    talkgroup_observed_local_id, confirmed_at_ms)
                SELECT 2, id, 2900, 2, identity_id, identity_id, 302, 2
                FROM radio_system_identity_summary WHERE id > 10000
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_activity_event(channel_id, radio_system_id, observed_at_ms, action_code,
                    source_identity_summary_id, source_observed_local_id, source_observed_working_id)
                VALUES (1, 1, 3, 12, 1002, 501, 601), (1, 1, 4, 12, 1002, 0, 601),
                       (1, 1, 5, 12, 1003, 501, 603), (1, 1, 6, 12, 1005, 601, NULL),
                       (1, 1, 7, 12, 1006, 501, 601)
                """);
            statement.executeUpdate("""
                INSERT INTO activity_event_identity_member(event_id, radio_system_id, identity_summary_id,
                    observed_local_id, channel_id)
                VALUES (1, 1, 1004, 301, 1), (2, 1, 1004, 301, 1),
                       (3, 1, 1004, 0, 1), (4, 1, 1004, NULL, 1)
                """);
            statement.execute("ANALYZE");
        }
        return connection;
    }

    private static Map<String,Object> identity(long summary, int system, int kind, int identifier, Long subscriber)
    {
        Map<String,Object> row = new LinkedHashMap<>();
        row.put("radio_system_key", "p25:bee00:00" + system);
        row.put("protocol_code", 1);
        row.put("topology", "TRUNKED");
        row.put("identity_summary_id", summary);
        row.put("identity_kind_code", kind);
        row.put("identity_id", identifier);
        row.put("p25_subscriber_identity_id", subscriber);
        return row;
    }

    private static String plan(Connection connection, String sql, List<Long> requested) throws Exception
    {
        StringBuilder plan = new StringBuilder();
        try(PreparedStatement query = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql))
        {
            bind(query, requested);
            try(ResultSet result = query.executeQuery())
            {
                while(result.next())
                {
                    plan.append(result.getString("detail")).append('\n');
                }
            }
        }
        return plan.toString();
    }

    private static List<List<Long>> evidence(Connection connection, String sql, List<Long> requested) throws Exception
    {
        List<List<Long>> evidence = new ArrayList<>();
        try(PreparedStatement query = connection.prepareStatement(sql))
        {
            bind(query, requested);
            try(ResultSet result = query.executeQuery())
            {
                while(result.next())
                {
                    evidence.add(List.of(result.getLong("identity_summary_id"), result.getLong("alias_list_id"),
                        result.getLong("observed_local_id")));
                }
            }
        }
        return evidence;
    }

    private static void bind(PreparedStatement query, List<Long> requested) throws Exception
    {
        for(int index = 0; index < requested.size(); index++)
        {
            query.setLong(index + 1, requested.get(index));
        }
        query.setInt(requested.size() + 1, StatsAliasResolver.MAX_RULE_LOOKUP_PAIRS + 1);
    }
}
