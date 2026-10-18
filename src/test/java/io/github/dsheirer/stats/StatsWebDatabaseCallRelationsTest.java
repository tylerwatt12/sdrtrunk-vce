/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.audio.call.SourceCallReference;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsWebDatabaseCallRelationsTest
{
    private static final String CHANNEL = "00000000-0000-0000-0000-000000000071";
    private static final String SOURCE = SourceCallReference.fromToken("call-relations:first-call");
    private static final String OTHER_SOURCE = SourceCallReference.fromToken("call-relations:second-call");
    @TempDir Path directory;
    private Path path;
    private StatsWebDatabase database;

    @BeforeEach void setup() throws Exception
    {
        path = directory.resolve("activity.sqlite");
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
        {
            SdrTrunkDatabaseSchema.create(connection);
            SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
            ReceiverActivitySchema.create(connection); DmrActivitySchema.create(connection); TrunkedSiteSchema.create(connection);
            sql.execute("PRAGMA foreign_keys=ON");
            sql.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(71,'Related calls','P25')");
            sql.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,name,
                    alias_list_id,radioresolve_id,decoder_type,primary_frequency_hz,config_json)
                VALUES('%s','TRUNKED',71,'Related system','North','Related channel',71,
                    '10000000-0000-0000-0000-000000000071','P25_PHASE1',851012500,'{}')
                """.formatted(CHANNEL));
            sql.executeUpdate("""
                INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms)
                VALUES(71,'p25:bee00:49f',1,0,0xBEE00,0x49F,1000,4000)
                """);
            sql.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,radio_system_id,radio_system_assigned_at_ms)
                VALUES(71,'%s',1000,4000,71,1000)
                """.formatted(CHANNEL));
            sql.executeUpdate("""
                INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,home_wacn,home_system_id,
                    identity_id,first_seen_ms,last_seen_ms,logical_call_count)
                VALUES(7101,71,1,0xBEE00,0x49F,101,1000,4000,3),(7102,71,2,0xBEE00,0x49F,202,1000,4000,3)
                """);
            sql.executeUpdate("""
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value)
                VALUES(7101,71,'Dispatch','TALKGROUP','APCO25',101),(7102,71,'Unit 202','RADIO_ID','APCO25',202)
                """);
            try(var insert = connection.prepareStatement("""
                INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,event_type_code,
                    source_observed_local_id,target_observed_local_id,target_kind_code,source_identity_summary_id,
                    target_identity_summary_id,frequency_hz,source_call_uuid)
                VALUES(?,71,71,?,4,7,202,101,1,7102,7101,851012500,?)
                """))
            {
                for(int i = 1; i <= 4; i++)
                {
                    insert.setLong(1,1000 + i); insert.setLong(2,1000 + i);
                    insert.setString(3,i <= 2 ? SOURCE : i == 3 ? OTHER_SOURCE : null); insert.addBatch();
                }
                insert.executeBatch();
            }
            sql.executeUpdate("INSERT INTO receiver_activity_tone_sequence VALUES(1001,1,3,1,1000,1000,2000,1)");
            sql.executeUpdate("INSERT INTO receiver_activity_tone VALUES(1001,0,1000000,NULL,NULL,0,100,990000)");
        }
        database = new StatsWebDatabase(new UserPreferences(),path);
    }

    @Test void exactInternalLookupLeavesUnknownAndExpiredActivityUnlinked() throws Exception
    {
        assertEquals(SOURCE,database.activitySourceCallReference(1001));
        assertEquals(SOURCE,database.activitySourceCallReference(1002));
        assertEquals(OTHER_SOURCE,database.activitySourceCallReference(1003));
        assertNull(database.activitySourceCallReference(1004));
        assertNull(database.activitySourceCallReference(-1));
        assertNull(database.activitySourceCallReference(Long.MAX_VALUE));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
        {
            sql.execute("PRAGMA foreign_keys=ON"); sql.executeUpdate("DELETE FROM receiver_activity_event WHERE id=1001");
        }
        assertNull(database.activitySourceCallReference(1001));
        assertEquals(List.of(1002L),ids(database.activityBySourceCallReferences(List.of(SOURCE))));
    }

    @Test void exactRelatedRowsKeepAliasToneAndEntityEnrichmentWithoutReturningSourceIdentifiers() throws Exception
    {
        List<Map<String,Object>> rows = database.activityBySourceCallReferences(List.of(SOURCE,SOURCE));
        assertEquals(List.of(1002L,1001L),ids(rows));
        assertTrue(rows.stream().allMatch(row -> "Dispatch".equals(row.get("target_alias_name"))));
        assertTrue(rows.stream().allMatch(row -> "Unit 202".equals(row.get("source_alias_name"))));
        assertTrue(rows.stream().allMatch(row -> row.containsKey("target_entity_ref")));
        assertTrue(rows.getLast().containsKey("paging_tone_summary"));
        assertFalse(rows.getFirst().containsKey("paging_tone_summary"));
        String json = new ObjectMapper().writeValueAsString(rows);
        assertFalse(json.contains("source_call_uuid")); assertFalse(json.contains(SOURCE));
        assertEquals(List.of(1003L),ids(database.activityBySourceCallReferences(List.of(OTHER_SOURCE))));
    }

    @Test void normalActivityPagesExposeOnlyTheAvailabilityFlag() throws Exception
    {
        Map<String,Object> page = database.activity(StatsRequest.from(URI.create("/?configuration_id=" + CHANNEL +
            "&from_ms=1&to_ms=5000&hide_grants=false&limit=20")));
        @SuppressWarnings("unchecked") List<Map<String,Object>> rows = (List<Map<String,Object>>)page.get("rows");
        assertEquals(4,rows.size());
        for(Map<String,Object> row: rows)
            assertEquals(((Number)row.get("id")).longValue() != 1004,row.get("has_call_source"));
        String json = new ObjectMapper().writeValueAsString(page);
        assertFalse(json.contains("source_call_uuid")); assertFalse(json.contains(SOURCE));
    }

    @Test void recordingAvailabilityUsesTheExactStoredReferencesAndAddsNoPersistedState() throws Exception
    {
        Path catalogFile = directory.resolve("recordings.sqlite");
        try(var catalog = new ManagedRecordingCatalog(catalogFile, directory))
        {
            CallRelationsServiceTest.addRecording(catalogFile, 1, List.of(SOURCE));
            var availability = new ActivityRecordingAvailability(database, () -> catalog);
            Map<String,Object> page = database.activity(StatsRequest.from(URI.create("/?configuration_id=" + CHANNEL +
                "&from_ms=1&to_ms=5000&hide_grants=false&limit=20")));
            availability.enrich(page, true);
            @SuppressWarnings("unchecked") List<Map<String,Object>> rows = (List<Map<String,Object>>)page.get("rows");
            assertEquals(List.of(1002L,1001L), ids(rows.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("has_recording"))).toList()));
            assertFalse(new ObjectMapper().writeValueAsString(page).contains(SOURCE));
            assertEquals(SOURCE, database.activitySourceCallReference(1001));

            var noCatalog = new ActivityRecordingAvailability(database, () -> null);
            noCatalog.enrich(page, true);
            assertTrue(rows(page).stream().noneMatch(row -> Boolean.TRUE.equals(row.get("has_recording"))));
            assertTrue(catalog.delete(1));
            availability.enrich(page, true);
            assertTrue(rows(page).stream().noneMatch(row -> Boolean.TRUE.equals(row.get("has_recording"))));
            assertEquals(SOURCE, database.activitySourceCallReference(1001),
                "Availability and cleanup do not rewrite the retained Activity association");
        }
    }

    @Test void availabilitySourceLookupIsBoundedByPageIdsAndUsesTheExistingPrimaryKey() throws Exception
    {
        assertEquals(Map.of(1001L,SOURCE,1002L,SOURCE,1003L,OTHER_SOURCE),
            database.activitySourceCallReferences(List.of(1001L,1002L,1003L,1004L,1001L,9999L)));
        assertTrue(database.activitySourceCallReferences(List.of()).isEmpty());
        assertTrue(database.activitySourceCallReferences(null).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> database.activitySourceCallReferences(
            IntStream.range(0,5001).mapToObj(Long::valueOf).toList()));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
        {
            List<String> plan = new ArrayList<>();
            try(var result = sql.executeQuery("EXPLAIN QUERY PLAN SELECT id,source_call_uuid FROM " +
                "receiver_activity_event WHERE id IN (1001,1002,1003,1004) AND source_call_uuid IS NOT NULL"))
            { while(result.next()) plan.add(result.getString(4)); }
            assertTrue(plan.stream().anyMatch(detail -> detail.startsWith("SEARCH receiver_activity_event ") &&
                detail.contains("INTEGER PRIMARY KEY")), plan.toString());
            assertFalse(plan.stream().anyMatch(detail -> detail.startsWith("SCAN ")), plan.toString());
        }
    }

    @Test void activityAndRelatedRowsShareNumericDurationAndExplicitUnknown() throws Exception
    {
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var sql = connection.createStatement())
        {
            sql.executeUpdate("UPDATE receiver_activity_event SET duration_ms=12345 WHERE id=1001");
            sql.executeUpdate("UPDATE receiver_activity_event SET duration_ms=0 WHERE id=1003");
        }
        List<Map<String,Object>> related = database.activityBySourceCallReferences(List.of(SOURCE));
        assertNull(related.getFirst().get("duration_ms"));
        assertEquals(12_345L, ((Number)related.getLast().get("duration_ms")).longValue());
        Map<String,Object> page = database.activity(StatsRequest.from(URI.create("/?configuration_id=" + CHANNEL +
            "&from_ms=1&to_ms=5000&hide_grants=false&limit=20")));
        @SuppressWarnings("unchecked") List<Map<String,Object>> rows = (List<Map<String,Object>>)page.get("rows");
        assertTrue(rows.stream().allMatch(row -> row.containsKey("duration_ms")));
        assertEquals(12_345L, ((Number)rows.stream().filter(row -> ((Number)row.get("id")).longValue()==1001)
            .findFirst().orElseThrow().get("duration_ms")).longValue());
        assertEquals(0L, ((Number)rows.stream().filter(row -> ((Number)row.get("id")).longValue()==1003)
            .findFirst().orElseThrow().get("duration_ms")).longValue());
        assertNull(rows.stream().filter(row -> ((Number)row.get("id")).longValue()==1004)
            .findFirst().orElseThrow().get("duration_ms"));
    }

    @Test void relationReadsBoundSourcesAndResultsAndRejectInvalidReferences() throws Exception
    {
        assertTrue(database.activityBySourceCallReferences(null).isEmpty());
        assertTrue(database.activityBySourceCallReferences(List.of()).isEmpty());
        assertTrue(database.activityBySourceCallReferences(List.of(SourceCallReference.fromToken("unknown-call"))).isEmpty());
        assertThrows(IllegalArgumentException.class,() -> database.activityBySourceCallReferences(List.of("bad")));
        assertThrows(IllegalArgumentException.class,() -> database.activityBySourceCallReferences(List.of(SOURCE.toUpperCase())));
        List<String> missing = new ArrayList<>(); missing.add(null);
        assertThrows(IllegalArgumentException.class,() -> database.activityBySourceCallReferences(missing));
        List<String> full = IntStream.range(0,128).mapToObj(i -> SourceCallReference.fromToken("bounded:" + i)).toList();
        assertTrue(database.activityBySourceCallReferences(full).isEmpty());
        assertThrows(IllegalArgumentException.class,() -> database.activityBySourceCallReferences(
            IntStream.range(0,129).mapToObj(i -> SourceCallReference.fromToken("bounded:" + i)).toList()));
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + path); var insert = connection.prepareStatement("""
            INSERT INTO receiver_activity_event(channel_id,radio_system_id,observed_at_ms,action_code,event_type_code,source_call_uuid)
            VALUES(71,71,?,4,7,?)
            """))
        {
            for(int i = 0; i < 150; i++)
            { insert.setLong(1,2000 + i); insert.setString(2,SOURCE); insert.addBatch(); }
            insert.executeBatch();
        }
        List<Map<String,Object>> rows = database.activityBySourceCallReferences(List.of(SOURCE));
        assertEquals(100,rows.size());
        assertTrue(((Number)rows.getFirst().get("observed_at_ms")).longValue() >
            ((Number)rows.getLast().get("observed_at_ms")).longValue());
    }

    private static List<Long> ids(List<Map<String,Object>> rows)
    {
        return rows.stream().map(row -> ((Number)row.get("id")).longValue()).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> page)
    { return (List<Map<String,Object>>)page.get("rows"); }
}
