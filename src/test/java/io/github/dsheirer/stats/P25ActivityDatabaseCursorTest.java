/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivityFilterCatalog;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P25ActivityDatabaseCursorTest
{
    private static final String CHANNEL = "00000000-0000-0000-0000-000000000071";
    @TempDir Path directory;
    private Path path;
    private StatsWebDatabase database;

    @BeforeEach void setup() throws Exception
    {
        path = directory.resolve("activity.sqlite");
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
        {
            SdrTrunkDatabaseSchema.create(connection);SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
            ReceiverActivitySchema.create(connection);DmrActivitySchema.create(connection);TrunkedSiteSchema.create(connection);
            sql.execute("PRAGMA foreign_keys=ON");
            sql.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(71,'Shared P25','P25')");
            sql.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,name,
                    alias_list_id,radioresolve_id,decoder_type,primary_frequency_hz,config_json)
                VALUES('%s','TRUNKED',71,'County','North','Control',71,
                    '10000000-0000-0000-0000-000000000071','P25_PHASE1',851012500,'{}')
                """.formatted(CHANNEL));
            sql.executeUpdate("""
                INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms)
                VALUES(71,'p25:bee00:49f',1,0,0xBEE00,0x49F,1,10000)
                """);
            sql.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,radio_system_id,radio_system_assigned_at_ms)
                VALUES(71,'%s',1,10000,71,1)
                """.formatted(CHANNEL));
        }
        database = new StatsWebDatabase(new UserPreferences(), path);
    }

    @Test void noteworthyHistoryIsFilteredBeforeLimitAndSurvivesMoreThanFiveHundredRoutineEvents() throws Exception
    {
        event(1,1000,"DENIAL");
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
        {
            sql.executeUpdate("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code)
                WITH RECURSIVE rows(n) AS (VALUES(2) UNION ALL SELECT n+1 FROM rows WHERE n<701)
                SELECT n,71,71,1000+n,4 FROM rows
                """);
        }
        StatsWebDatabase.ActivityQuery[] query = new StatsWebDatabase.ActivityQuery[1];
        var result = database.activity(request("/?projection=p25_visualizer&from_ms=1&to_ms=10000&actions=DENIAL&after_id=0&limit=1"),
            value -> query[0] = value);
        assertEquals(List.of(1L), ids(result));assertFalse((Boolean)result.get("has_more"));
        assertEquals(701, number(result.get("watermark_id")));assertEquals(701, number(result.get("next_after_id")));
        assertTrue(query[0].sql().indexOf("candidate.action_code IN") < query[0].sql().indexOf("LIMIT ?"));
        assertTrue(query[0].sql().indexOf("visualizer_system.protocol_code = 1") < query[0].sql().indexOf("LIMIT ?"));
        assertTrue(query[0].sql().contains("ORDER BY candidate.id ASC"));
        assertFalse(query[0].sql().contains("activity.frequency_hz"));
        assertFalse(rows(result).getFirst().containsKey("frequency_hz"));
        assertFalse(rows(result).getFirst().containsKey("has_call_source"));
    }

    @Test void appendIncludesEveryP25ActionAndOldOrOutOfOrderTimestampsWithPinnedWatermarks() throws Exception
    {
        int count = 0;
        for(String action: ReceiverActivityFilterCatalog.actions().keySet()) event(++count,1,action);
        for(int id = count + 1; id <= 130; id++) event(id,131-id,"CALL");
        assertEquals(130,database.savedActivityWatermark());
        var first = database.savedActivityAfter(0,null);
        assertEquals(LongStream.rangeClosed(1,64).boxed().toList(), ids(first));
        assertTrue((Boolean)first.get("has_more"));assertEquals(64,number(first.get("next_after_id")));
        event(131,1,"JOIN"); // Added after the first snapshot, deliberately older than every history window.
        var second = database.savedActivityAfter(64,130L);
        assertEquals(LongStream.rangeClosed(65,128).boxed().toList(),ids(second));
        var last = database.savedActivityAfter(128,130L);
        assertEquals(List.of(129L,130L),ids(last));assertFalse((Boolean)last.get("has_more"));
        assertEquals(130,number(last.get("next_after_id")));assertEquals(130,number(last.get("watermark_id")));
        var next = database.savedActivityAfter(130,null);
        assertEquals(List.of(131L),ids(next));assertEquals(131,number(next.get("watermark_id")));
    }

    @Test void nonP25AndConventionalRowsCannotConsumeTheCompactPageAndResetKeepsOriginalContract() throws Exception
    {
        event(1,100,"JOIN");event(2,200,"CALL");
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
        {
            sql.executeUpdate("""
                INSERT INTO radio_system(id,system_key,configuration_id,protocol_code,address_domain_code,first_seen_ms,last_seen_ms)
                VALUES(99,'dmr:channel:%s','%s',3,0,1,10000)
                """.formatted(CHANNEL,CHANNEL));
            sql.executeUpdate("INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code) VALUES(3,71,99,300,4)");
        }
        var page = database.savedActivityAfter(0,null);
        assertEquals(List.of(1L,2L),ids(page));assertEquals(3,number(page.get("next_after_id")));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
        { sql.executeUpdate("UPDATE configuration_channel SET channel_kind='CONVENTIONAL' WHERE configuration_id='" + CHANNEL + "'"); }
        var noTrunked = database.savedActivityAfter(0,null);
        assertTrue(rows(noTrunked).isEmpty());assertEquals(3,number(noTrunked.get("next_after_id")));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
        { sql.executeUpdate("DELETE FROM receiver_activity_event"); }
        var reset = database.savedActivityAfter(3,null);
        assertTrue((Boolean)reset.get("reset_required"));assertEquals(0,number(reset.get("watermark_id")));
        assertEquals(0,number(reset.get("next_after_id")));assertTrue(rows(reset).isEmpty());
    }

    @Test void nativeSnapshotReturnsAllCurrentAffiliationsAcrossMoreThan256Systems() throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
        {
            sql.execute("PRAGMA foreign_keys=ON");connection.setAutoCommit(false);
            for(int system = 1; system <= 257; system++)
            {
                int owner = 1000 + system;
                String configuration = "00000000-0000-0000-0000-%012d".formatted(owner);
                sql.executeUpdate("""
                    INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,name,
                        alias_list_id,radioresolve_id,decoder_type,primary_frequency_hz,config_json)
                    VALUES('%1$s','TRUNKED',%2$d,'System %2$d','Site','Control',71,'%1$s','P25_PHASE1',851012500,'{}')
                    """.formatted(configuration,owner));
                sql.executeUpdate("""
                    INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms)
                    VALUES(%1$d,'p25:bee00:%2$03x',1,0,0xBEE00,%2$d,1,10000)
                    """.formatted(owner,system));
                sql.executeUpdate("""
                    INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,radio_system_id,radio_system_assigned_at_ms)
                    VALUES(%1$d,'%2$s',1,10000,%1$d,1)
                    """.formatted(owner,configuration));
                sql.executeUpdate("""
                    INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms)
                    VALUES(%1$d,%2$d,1,0xBEE00,%3$d,101,1,10000)
                    """.formatted(1000000+owner,owner,system));
                for(int radio = 1; radio <= 40; radio++)
                {
                    int identity = 2000000 + 100 * system + radio;
                    sql.executeUpdate("""
                        INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms)
                        VALUES(%1$d,%2$d,2,0xBEE00,%3$d,%4$d,1,10000)
                        """.formatted(identity,owner,system,100000+radio));
                    sql.executeUpdate("""
                        INSERT INTO trunked_radio_affiliation(radio_system_id,radio_identity_id,talkgroup_identity_id,channel_id,
                            radio_observed_local_id,talkgroup_observed_local_id,confirmed_at_ms)
                        VALUES(%1$d,%2$d,%3$d,%1$d,%4$d,101,10000)
                        """.formatted(owner,identity,1000000+owner,100000+radio));
                }
            }
            connection.commit();
            try(var check = sql.executeQuery("PRAGMA foreign_key_check")) { assertFalse(check.next()); }
            List<String> plan = new ArrayList<>();
            try(var result = sql.executeQuery("EXPLAIN QUERY PLAN " + StatsWebDatabase.P25_AFFILIATION_CORE_SQL))
            { while(result.next()) plan.add(result.getString("detail")); }
            assertTrue(plan.stream().anyMatch(row -> row.contains("idx_trunked_radio_affiliation_retention")),plan.toString());
            assertFalse(plan.stream().anyMatch(row -> row.contains("receiver_activity_event")),plan.toString());
        }
        var snapshot = database.p25RawSnapshot();
        assertEquals(10280,snapshot.affiliations().size());
        assertEquals(257,snapshot.affiliations().stream().map(row -> row.get("radio_system_key")).distinct().count());
        assertTrue(database.savedActivityAfter(0,null).get("rows") instanceof List<?>);
        assertTrue(rows(database.savedActivityAfter(0,null)).isEmpty(),"native current state does not impersonate saved events");
    }

    private void event(int id, long timestamp, String action) throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path);
            var insert = connection.prepareStatement("INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code) VALUES(?,71,71,?,?)"))
        { insert.setInt(1,id);insert.setLong(2,timestamp);insert.setInt(3,ReceiverActivityFilterCatalog.actionCode(action));insert.executeUpdate(); }
    }

    private static StatsRequest request(String uri) { return StatsRequest.from(URI.create(uri)); }
    private static long number(Object value) { return ((Number)value).longValue(); }
    @SuppressWarnings("unchecked") private static List<Map<String,Object>> rows(Map<String,Object> page)
    { return (List<Map<String,Object>>)page.get("rows"); }
    private static List<Long> ids(Map<String,Object> page)
    { return rows(page).stream().map(row -> number(row.get("id"))).toList(); }
}
