/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsChannelAliasSelectionTest
{
    private static final String CHANNEL = "00000000-0000-4000-8000-000000000071";
    @TempDir Path mTemporaryFolder;
    private Path mDatabasePath;
    private StatsWebDatabase mDatabase;

    @BeforeEach
    void setUp() throws Exception
    {
        mDatabasePath = mTemporaryFolder.resolve("channel-aliases.sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
            ReceiverActivitySchema.create(connection);
            DmrActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES " +
                "(71,'Selected','P25'),(72,'Other','P25')");
            statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,
                    name,alias_list_id,radioresolve_id,decoder_type,primary_frequency_hz,config_json)
                VALUES('%s','TRUNKED',71,'P25','Site','Control',71,
                    '10000000-0000-4000-8000-000000000071','P25_PHASE1',851012500,'{}')
                """.formatted(CHANNEL));
            statement.executeUpdate("""
                INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,
                    first_seen_ms,last_seen_ms) VALUES(71,'p25:bee00:49f',1,0,0xBEE00,0x49F,1000,2000)
                """);
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,
                    radio_system_id,radio_system_assigned_at_ms) VALUES(71,'%s',1000,2000,71,1000)
                """.formatted(CHANNEL));
            statement.executeUpdate("""
                INSERT INTO p25_learned_site(learned_site_id,radio_system_id,rfss,site,first_seen_ms,last_seen_ms)
                VALUES(71,71,1,1,1000,2000)
                """);
        }
        mDatabase = new StatsWebDatabase(new UserPreferences(), mDatabasePath);
    }

    @Test
    void missingObservedAddressesKeepNativeAliasesInDisplaySearchAndListOwnership() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            addIdentity(statement, 101, 1, null, 0xBEE00, 0x49F, 101, null, null);
            addIdentity(statement, 202, 2, null, 0xBEE00, 0x49F, 202, null, null);
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) VALUES
                    (101,71,'Native Group','TALKGROUP','APCO25',101),
                    (202,71,'Native Radio','RADIO_ID','APCO25',202),
                    (999,72,'Wrong List','RADIO_ID','APCO25',202)
                """);
        }
        Map<String,Object> group = rows(mDatabase.channelGroupIdentities(CHANNEL,
            request("/?q=native%20group&sort=alias&range=24h"))).getFirst();
        Map<String,Object> radio = rows(mDatabase.channelRadios(CHANNEL,
            request("/?q=native%20radio&sort=alias"))).getFirst();
        assertEquals("Native Group", group.get("alias_name"));
        assertEquals("Native Radio", radio.get("alias_name"));
        for(Map<String,Object> row: List.of(group, radio))
        {
            assertEquals(71L, ((Number)row.get("alias_list_id")).longValue());
            assertEquals("Selected", row.get("alias_list_name"));
            assertTrue(row.containsKey("alias_description"));
            assertTrue(row.containsKey("alias_group"));
            assertTrue(row.keySet().stream().noneMatch(key -> key.startsWith("matched_alias_")));
        }
        assertTrue(rows(mDatabase.channelRadios(CHANNEL, request("/?q=wrong%20list"))).isEmpty());
    }

    @Test
    void canonicalHomeAndForeignRadiosUseOnlyTheSupportedWorkingAddressFallback() throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) VALUES
                    (11,0xBEE00,0x49F,101),(12,0xABCDE,0x123,102),
                    (13,0xABCDE,0x123,103),(14,0xBEE00,0x49F,104)
                """);
            addIdentity(statement, 11, 2, 11, 0xBEE00, 0x49F, 101, 101, null);
            addIdentity(statement, 12, 2, 12, 0xABCDE, 0x123, 102, 102, null);
            addIdentity(statement, 13, 2, 13, 0xABCDE, 0x123, 103, 50, 50);
            addIdentity(statement, 14, 2, 14, 0xBEE00, 0x49F, 104, 104, 51);
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) VALUES
                    (101,71,'Home Local','RADIO_ID','APCO25',101),
                    (102,71,'Unproven Foreign Local','RADIO_ID','APCO25',102),
                    (103,71,'Foreign Working','RADIO_ID','APCO25',50),
                    (104,71,'Home Native','RADIO_ID','APCO25',104),
                    (105,71,'Home Working','RADIO_ID','APCO25',51)
                """);
        }
        Map<Long,Map<String,Object>> radios = byIdentity(rows(mDatabase.channelRadios(CHANNEL, request("/"))));
        assertEquals("Home Local", radios.get(11L).get("alias_name"));
        assertFalse(radios.get(12L).containsKey("alias_name"), "A foreign subscriber number proves no local address");
        assertEquals("Foreign Working", radios.get(13L).get("alias_name"));
        assertEquals("Home Working", radios.get(14L).get("alias_name"),
            "An explicit working address takes precedence even when observed local equals the home subscriber");
        assertTrue(rows(mDatabase.channelRadios(CHANNEL, request("/?q=unproven"))).isEmpty());
        assertTrue(rows(mDatabase.channelRadios(CHANNEL, request("/?q=home%20native"))).isEmpty());

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,description,group_name,color) VALUES
                    (201,71,'Canonical Foreign','P25_SUBSCRIBER_IDENTITY','',NULL,123),
                    (202,72,'Other List Canonical','P25_SUBSCRIBER_IDENTITY','Other','Other',456)
                """);
            statement.executeUpdate("INSERT INTO alias_p25_subscriber_identity(alias_id,p25_subscriber_identity_id) " +
                "VALUES(201,13),(202,13)");
        }
        Map<String,Object> canonical = rows(mDatabase.channelRadios(CHANNEL,
            request("/?q=canonical%20foreign"))).getFirst();
        assertEquals("Canonical Foreign", canonical.get("alias_name"));
        assertEquals("", canonical.get("alias_description"));
        assertTrue(canonical.containsKey("alias_group"));
        assertEquals(null, canonical.get("alias_group"));
        assertEquals(123, ((Number)canonical.get("alias_color")).intValue());
        assertTrue(rows(mDatabase.channelRadios(CHANNEL, request("/?q=foreign%20working"))).isEmpty());
        assertTrue(rows(mDatabase.channelRadios(CHANNEL, request("/?q=other%20list"))).isEmpty());
    }

    @Test
    void selectedAliasUsesExactAndRangeIndexesAndKeepsDeterministicTieOrder() throws Exception
    {
        Method join = StatsWebDatabase.class.getDeclaredMethod("channelAliasJoin",
            WebConfiguredEntityRepository.ConfiguredChannel.class, String.class, String.class);
        join.setAccessible(true);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mDatabasePath);
            Statement statement = connection.createStatement())
        {
            var configured = new WebConfiguredEntityRepository().requireChannel(connection, CHANNEL);
            for(String kind: List.of("radio", "talkgroup"))
            {
                String matcher = "radio".equals(kind) ? "RADIO_ID" : "TALKGROUP";
                statement.executeUpdate("DELETE FROM alias");
                statement.executeUpdate("""
                    INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value,min_value,max_value) VALUES
                        (1,71,'Broad','%1$s_RANGE','APCO25',NULL,1,1000),
                        (2,71,'Narrow','%1$s_RANGE','APCO25',NULL,100,200),
                        (3,71,'Tie First','%1$s_RANGE','APCO25',NULL,100,300),
                        (4,71,'Tie Last','%1$s_RANGE','APCO25_PHASE2',NULL,100,300),
                        (5,71,'Exact First','%1$s','APCO25',150,NULL,NULL),
                        (6,71,'Exact Last','%1$s','APCO25_PHASE2',150,NULL,NULL),
                        (7,72,'Wrong List','%1$s','APCO25',175,NULL,NULL)
                    """.formatted(matcher));
                String sql = "WITH grouped(native_id) AS (VALUES(150),(175),(500),(2000)) " +
                    "SELECT grouped.native_id,matched.id FROM grouped " +
                    join.invoke(null, configured, "alias_" + kind, "grouped.native_id") +
                    " ORDER BY grouped.native_id";
                List<Map<String,Object>> selected = StatsSqlRows.queryRows(connection, sql);
                assertEquals(List.of(6, 4, 1), selected.subList(0, 3).stream().map(row -> row.get("id")).toList());
                assertEquals(null, selected.getLast().get("id"));
                List<String> plan = StatsSqlRows.queryRows(connection, "EXPLAIN QUERY PLAN " + sql).stream()
                    .map(row -> String.valueOf(row.get("detail"))).toList();
                assertTrue(plan.stream().anyMatch(detail -> detail.contains("idx_alias_" + kind + "_value") &&
                    detail.contains("value=?")), plan.toString());
                assertTrue(plan.stream().anyMatch(detail -> detail.contains("idx_alias_activity_" + kind + "_range") &&
                    detail.contains("min_value<?")), plan.toString());
                assertTrue(plan.stream().noneMatch(detail -> detail.startsWith("SCAN alias")), plan.toString());
            }
        }
    }

    private static void addIdentity(Statement statement, int id, int kind, Integer canonical,
                                    int wacn, int system, int nativeId, Integer local, Integer working) throws Exception
    {
        statement.executeUpdate("""
            INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,
                home_system_id,identity_id,first_seen_ms,last_seen_ms,p25_subscriber_identity_id)
            VALUES(%d,71,%d,%d,%d,%d,1000,2000,%s)
            """.formatted(id, kind, wacn, system, nativeId, canonical));
        long bucket = Math.floorDiv(System.currentTimeMillis(), 3_600_000L) * 3_600_000L;
        statement.executeUpdate("""
            INSERT INTO p25_site_call_identity_bucket(radio_system_id,learned_site_id,channel_id,bucket_start_ms,
                identity_role_code,identity_kind_code,identity_summary_id,observed_local_id,
                observed_working_id,last_observed_at_ms,observed_call_count)
            VALUES(71,71,71,%d,%d,%d,%d,%s,%s,2000,1)
            """.formatted(bucket, kind, kind, id, local, working));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> response)
    {
        return (List<Map<String,Object>>)response.get("rows");
    }

    private static Map<Long,Map<String,Object>> byIdentity(List<Map<String,Object>> rows)
    {
        return rows.stream().collect(java.util.stream.Collectors.toMap(
            row -> ((Number)row.get("identity_summary_id")).longValue(), row -> row));
    }

    private static StatsRequest request(String uri)
    {
        return StatsRequest.from(URI.create(uri));
    }
}
