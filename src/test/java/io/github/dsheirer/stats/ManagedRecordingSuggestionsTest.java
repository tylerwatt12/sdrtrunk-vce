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

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Friendly names remain suggestions only while a matching call exists in the separate catalog. */
class ManagedRecordingSuggestionsTest
{
    private static final String CHANNEL_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String CHANNEL_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SYSTEM_A = "p25:00001:001";
    private static final String SYSTEM_B = "p25:00002:002";

    @TempDir Path directory;
    private Path mMainDatabase;
    private Path mCatalogDatabase;
    private ManagedRecordingCatalog mCatalog;
    private ManagedRecordingLabels mLabels;

    @BeforeEach
    void setUp() throws Exception
    {
        mMainDatabase = directory.resolve("main.sqlite");
        mCatalogDatabase = directory.resolve("recordings.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(mMainDatabase);
        mCatalog = new ManagedRecordingCatalog(mCatalogDatabase, directory.resolve("recordings-managed"));
        mLabels = new ManagedRecordingLabels(mMainDatabase);
        main("INSERT INTO alias_list(id,name,family) VALUES" +
            "(700,'Fixture North List','P25'),(701,'Fixture South List','P25')",
            "INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name," +
                "site_name,name,alias_list_id,decoder_type,primary_frequency_hz,config_json) VALUES" +
                "('" + CHANNEL_A + "','TRUNKED',1,'Fixture North System','Fixture North Site'," +
                "'Fixture North Channel',700,'P25_PHASE1',853000000,'{}')," +
                "('" + CHANNEL_B + "','TRUNKED',2,'Fixture South System','Fixture South Site'," +
                "'Fixture South Channel',701,'P25_PHASE1',854000000,'{}')",
            "INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn," +
                "p25_system_id,first_seen_ms,last_seen_ms) VALUES" +
                "(501,'" + SYSTEM_A + "',1,0,1,1,1000,2000)," +
                "(502,'" + SYSTEM_B + "',1,0,2,2,1000,2000)",
            "INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms," +
                "radio_system_id,radio_system_assigned_at_ms) VALUES" +
                "(501,'" + CHANNEL_A + "',1000,2000,501,1000)," +
                "(502,'" + CHANNEL_B + "',1000,2000,502,1000)",
            "INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms,protocol,rfss,site) " +
                "VALUES(501,1000,2000,'APCO25',4,9),(502,1000,2000,'APCO25',4,10)");
        catalog("INSERT INTO recording_system(id,system_key) VALUES" +
                "(1,'" + SYSTEM_A + "'),(2,'" + SYSTEM_B + "')",
            "INSERT INTO recording_channel(id,channel_uuid) VALUES" +
                "(1,'" + CHANNEL_A + "'),(2,'" + CHANNEL_B + "')",
            "INSERT INTO recording_site(id,wacn,system_id,rfss,site_id) " +
                "VALUES(1,1,1,4,9),(2,2,2,4,10)",
            "INSERT INTO recording_system_site(system_id,site_id) VALUES(1,1),(2,2)");
    }

    @AfterEach
    void tearDown()
    {
        if(mCatalog != null) mCatalog.close();
    }

    @Test
    void unusedAliasesSystemsChannelsAndSitesAreAbsentDespiteRetainedDimensions() throws Exception
    {
        alias(700, "TALKGROUP", "APCO25", 1001, "Fixture Recorded Dispatch");
        alias(700, "TALKGROUP", "APCO25", 1002, "Fixture Unused Dispatch");
        alias(700, "RADIO_ID", "APCO25", 101, "Fixture Recorded Radio");
        alias(700, "RADIO_ID", "APCO25", 102, "Fixture Unused Radio");
        call(1, 1, 1, 700, 1, 1, 101, 1001);
        catalog("UPDATE recording_call SET winner_site_id=1 WHERE id=1",
            "INSERT INTO recording_call_site(call_id,site_id,start_ms) VALUES(1,1,1000)");

        assertEquals(Set.of("Fixture North System"), labels("Fixture", "system", null, 20));
        assertEquals(Set.of("Fixture North Channel"), labels("Fixture", "channel", null, 20));
        assertEquals(Set.of("Fixture North Channel"), labels("Fixture", "site", null, 20));
        assertEquals(Set.of("Fixture Recorded Dispatch"), labels("Fixture", "talkgroup", null, 20));
        assertEquals(Set.of("Fixture Recorded Radio"), labels("Fixture", "radio", null, 20));
    }

    @Test
    void siteSuggestionsLeadWithChannelNameAndKeepSiteAndSystemContext() throws Exception
    {
        call(1, 1, 1, 700, 1, 1, 101, 1001);
        catalog("INSERT INTO recording_call_site(call_id,site_id,start_ms) VALUES(1,1,1000)");
        Map<String,Object> row = suggestions("North Channel", "site", SYSTEM_A, 20).getFirst();
        assertEquals("site", row.get("kind"));
        assertEquals("Fixture North Channel", row.get("label"));
        assertEquals("Fixture North Site · Fixture North System", row.get("detail"));
        assertEquals(CHANNEL_A, row.get("channel_id"));
        assertEquals(9, row.get("site_id"));
        assertEquals("Fixture North Channel", suggestions("9", "site", SYSTEM_A, 20)
            .getFirst().get("label"));
        assertEquals("Fixture North Channel", suggestions("4", "site", SYSTEM_A, 20)
            .getFirst().get("label"));
        assertEquals("Fixture North Channel", suggestions("North Site", "site", SYSTEM_A, 20)
            .getFirst().get("label"));

        main("UPDATE configuration_channel SET site_name='' WHERE configuration_id='" + CHANNEL_A + "'",
            "DELETE FROM p25_site_snapshot WHERE channel_id=501");
        row = suggestions("North Channel", "site", SYSTEM_A, 20).getFirst();
        assertEquals("Fixture North Channel", row.get("label"));
        assertEquals("Fixture North System", row.get("detail"));
        assertEquals(CHANNEL_A, row.get("channel_id"));
        assertFalse(row.containsKey("site_id"));
        assertTrue(suggestions("North Channel", "site", SYSTEM_B, 20).isEmpty());
    }

    @Test
    void eligibilityIsAppliedBeforeTheSuggestionLimit() throws Exception
    {
        for(int index = 0; index < 25; index++)
        {
            alias(700, "TALKGROUP", "APCO25", 2000 + index,
                "A" + String.format("%02d", index) + " Dispatch Unrecorded");
        }
        alias(700, "TALKGROUP", "APCO25", 3001, "Z Dispatch Recorded");
        call(1, 1, 1, 700, 1, 1, 101, 3001);

        assertEquals(Set.of("Z Dispatch Recorded"), labels("Dispatch", "talkgroup", null, 1));
    }

    @Test
    void aliasEligibilityProbesBoundedIdentityIndexesInsteadOfScanningAnAliasListsCalls() throws Exception
    {
        alias(700, "RADIO_ID", "APCO25", 101, "Fixture Engine");
        range(700, "TALKGROUP_RANGE", 1000, 1100, "Fixture Dispatch Range");
        call(1, 1, 1, 700, 1, 1, 101, 1001);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mMainDatabase))
        {
            try(PreparedStatement attach = connection.prepareStatement("ATTACH DATABASE ? AS recordings"))
            {
                attach.setString(1, mCatalogDatabase.toAbsolutePath().normalize().toUri() + "?mode=ro");
                attach.execute();
            }
            for(String scope: List.of("VALUES(NULL,NULL,NULL)", "VALUES('" + SYSTEM_A + "',1,1)"))
            {
                List<String> plan = new ArrayList<>();
                String query = "EXPLAIN QUERY PLAN WITH recording_scope(system_key,wacn,sysid) AS(" +
                    scope + ") SELECT alias.name FROM alias alias WHERE " +
                    ManagedRecordingLabels.recordedAliasPredicate();
                try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(query))
                {
                    while(rows.next()) plan.add(rows.getString("detail"));
                }
                String explanation = String.join("\n", plan);
                assertTrue(plan.stream().anyMatch(detail ->
                    detail.contains("idx_recording_call_source_time") &&
                        detail.contains("source_id>?") && detail.contains("source_id<?")), explanation);
                assertEquals(2, plan.stream().filter(detail ->
                    detail.contains("idx_recording_call_target_time") &&
                        detail.contains("target_id>?") && detail.contains("target_id<?")).count(), explanation);
                assertEquals(2, plan.stream().filter(detail ->
                    detail.contains("idx_recording_patch_member_lookup") &&
                        detail.contains("kind=?") && detail.contains("local_id>?") &&
                        detail.contains("local_id<?")).count(), explanation);
                assertFalse(plan.stream().anyMatch(detail ->
                    detail.contains("idx_recording_call_alias_list_time") || detail.startsWith("SCAN c")),
                    explanation);
            }
        }
    }

    @Test
    void recordedNamesRespectAliasListProtocolAndSelectedSystem() throws Exception
    {
        alias(700, "TALKGROUP", "APCO25", 4001, "North Dispatch");
        alias(701, "TALKGROUP", "APCO25", 4001, "South Dispatch");
        alias(700, "TALKGROUP", "DMR", 4001, "DMR Dispatch");
        alias(700, "TALKGROUP", "APCO25_PHASE2", 4002, "Phase Two Dispatch");
        call(1, 1, 1, 700, 2, 1, 101, 4001);
        call(2, 2, 2, 701, 1, 1, 102, 4001);
        call(3, 1, 1, 700, 2, 1, 103, 4002);

        assertEquals(Set.of("North Dispatch", "Phase Two Dispatch"),
            labels("Dispatch", "talkgroup", SYSTEM_A, 20));
        assertEquals(Set.of("South Dispatch"), labels("Dispatch", "talkgroup", SYSTEM_B, 20));
        assertEquals(Set.of("North Dispatch", "South Dispatch", "Phase Two Dispatch"),
            labels("Dispatch", "talkgroup", null, 20));
    }

    @Test
    void reassignedAliasListOffersOnlyCurrentNamesWhoseSubmittedScopeFindsRecordedCalls() throws Exception
    {
        alias(700, "TALKGROUP", "APCO25", 1001, "Old Dispatch");
        alias(701, "TALKGROUP", "APCO25", 1001, "Current Dispatch");
        alias(700, "RADIO_ID", "APCO25", 101, "Old Engine");
        alias(701, "RADIO_ID", "APCO25", 101, "Current Engine");
        call(1, 1, 1, 700, 1, 1, 101, 1001);
        assertEquals(Set.of("Old Dispatch"), labels("Dispatch", "talkgroup", SYSTEM_A, 20));
        assertEquals(Set.of("Old Engine"), labels("Engine", "radio", SYSTEM_A, 20));

        main("UPDATE configuration_channel SET alias_list_id=701 WHERE configuration_id='" +
            CHANNEL_A + "'");
        assertTrue(suggestions("Dispatch", "talkgroup", SYSTEM_A, 20).isEmpty());
        assertTrue(suggestions("Engine", "radio", SYSTEM_A, 20).isEmpty());

        call(2, 1, 1, 701, 1, 1, 101, 1001);
        List<Map<String,Object>> talkgroups = suggestions("Dispatch", "talkgroup", SYSTEM_A, 20);
        assertEquals(1, talkgroups.size());
        Map<String,Object> selected = talkgroups.getFirst();
        assertEquals("Current Dispatch", selected.get("label"));
        assertEquals(701L, selected.get("alias_list_id"));
        assertEquals(SYSTEM_A, selected.get("system_key"));
        assertEquals(List.of(2L), mCatalog.search(ManagedRecordingCatalog.SearchFilter.builder()
            .fromMs(0L).toMs(5000L).systemKey((String)selected.get("system_key"))
            .aliasListId(((Number)selected.get("alias_list_id")).longValue())
            .talkgroupId(Integer.parseInt((String)selected.get("id"))).build())
            .calls().stream().map(ManagedRecordingCatalog.RecordingCall::id).toList());

        List<Map<String,Object>> radios = suggestions("Engine", "radio", SYSTEM_A, 20);
        assertEquals(1, radios.size());
        Map<String,Object> selectedRadio = radios.getFirst();
        assertEquals("Current Engine", selectedRadio.get("label"));
        assertEquals(701L, selectedRadio.get("alias_list_id"));
        assertEquals(List.of(2L), mCatalog.search(ManagedRecordingCatalog.SearchFilter.builder()
            .fromMs(0L).toMs(5000L).systemKey((String)selectedRadio.get("system_key"))
            .aliasListId(((Number)selectedRadio.get("alias_list_id")).longValue())
            .sourceId(Integer.parseInt((String)selectedRadio.get("id"))).build())
            .calls().stream().map(ManagedRecordingCatalog.RecordingCall::id).toList());
    }

    @Test
    void sourcesDirectDestinationsAndPatchMembersMakeTheirOwnKindsAndRangesEligible() throws Exception
    {
        alias(700, "RADIO_ID", "APCO25", 101, "Eligible Source");
        alias(700, "RADIO_ID", "APCO25", 202, "Eligible Direct Destination");
        alias(700, "TALKGROUP", "APCO25", 202, "Eligible Wrong Kind");
        alias(700, "TALKGROUP", "APCO25", 3001, "Eligible Patch");
        alias(700, "TALKGROUP", "APCO25", 4001, "Eligible Patch Talkgroup");
        alias(700, "RADIO_ID", "APCO25", 303, "Eligible Patch Radio");
        alias(700, "RADIO_ID", "APCO25", 404, "Eligible Unused Radio");
        range(700, "TALKGROUP_RANGE", 4000, 4100, "Eligible Talkgroup Range");
        range(700, "RADIO_ID_RANGE", 300, 399, "Eligible Radio Range");
        range(700, "TALKGROUP_RANGE", 5000, 5100, "Eligible Unused Range");
        call(1, 1, 1, 700, 1, 3, 101, 202);
        call(2, 1, 1, 700, 1, 2, 101, 3001);
        catalog("INSERT INTO recording_patch_member(call_id,kind,local_id,home_wacn,home_system," +
            "home_id,start_ms) VALUES(2,1,4001,1,1,4001,1000),(2,2,303,1,1,303,1000)");

        assertEquals(Set.of("Eligible Source", "Eligible Direct Destination", "Eligible Patch Radio",
            "Eligible Radio Range"), labels("Eligible", "radio", SYSTEM_A, 20));
        assertEquals(Set.of("Eligible Patch", "Eligible Patch Talkgroup", "Eligible Talkgroup Range"),
            labels("Eligible", "talkgroup", SYSTEM_A, 20));
    }

    @Test
    void latestOtaNamesRequireRecordedCanonicalHomeIdentityAndSubmitTheLocalRadioId() throws Exception
    {
        call(1, 1, 1, 700, 1, 1, 401, 1001);
        catalog("UPDATE recording_call SET source_home_wacn=2,source_home_system=2," +
            "source_home_id=900 WHERE id=1");
        main("INSERT INTO radio_system_identity_summary(radio_system_id,identity_kind_code," +
            "home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms,last_talker_alias," +
            "last_talker_alias_seen_ms) VALUES" +
            "(501,2,2,2,900,1000,4000,'Fixture OTA Engine',4000)," +
            "(501,2,1,1,900,1000,5000,'Fixture OTA Wrong Home',5000)," +
            "(501,2,2,2,901,1000,6000,'Fixture OTA Unrecorded',6000)");

        List<Map<String,Object>> rows = suggestions("Fixture OTA", "radio", SYSTEM_A, 20);
        assertEquals(1, rows.size());
        assertEquals("Fixture OTA Engine", rows.getFirst().get("label"));
        assertEquals("401", rows.getFirst().get("id"));
        main("UPDATE radio_system_identity_summary SET last_talker_alias='Current Engine OTA'," +
            "last_talker_alias_seen_ms=7000,last_seen_ms=7000 WHERE identity_id=900 AND home_wacn=2");
        assertTrue(suggestions("Fixture OTA", "radio", SYSTEM_A, 20).isEmpty());
        assertEquals(Set.of("Current Engine OTA"), labels("Current Engine", "radio", SYSTEM_A, 20));
    }

    @Test
    void changedSiteSnapshotNeverSuggestsAnUnrecordedNumericSite() throws Exception
    {
        call(1, 1, 1, 700, 1, 1, 101, 1001);
        catalog("UPDATE recording_call SET winner_site_id=1 WHERE id=1",
            "INSERT INTO recording_call_site(call_id,site_id,start_ms) VALUES(1,1,1000)");
        main("UPDATE p25_site_snapshot SET site=10 WHERE channel_id=501");

        List<Map<String,Object>> rows = suggestions("North Site", "site", SYSTEM_A, 20);
        for(Map<String,Object> row: rows)
        {
            if(row.containsKey("site_id"))
            {
                assertEquals(4, row.get("rfss"));
                assertEquals(9, row.get("site_id"));
                assertEquals(1, row.get("wacn"));
                assertEquals(1, row.get("sysid"));
            }
            else
            {
                assertEquals(CHANNEL_A, row.get("channel_id"));
            }
        }
    }

    @Test
    void historicalP25SiteEvidenceMakesTheActualSystemEligibleInsteadOfTheCurrentChannelAssignment()
        throws Exception
    {
        call(1, null, 1, 700, 1, 1, 101, 1001);
        catalog("UPDATE recording_call SET winner_site_id=1 WHERE id=1",
            "INSERT INTO recording_call_site(call_id,site_id,start_ms) VALUES(1,1,1000)");
        main("UPDATE receiver_channel SET radio_system_id=502 WHERE id=501");

        List<Map<String,Object>> rows = suggestions("p25:", "system", null, 20);
        assertEquals(1, rows.size());
        assertEquals(SYSTEM_A, rows.getFirst().get("system_key"));
        assertTrue(suggestions("South System", "system", null, 20).isEmpty());
    }

    @Test
    void deletingTheLastCallRemovesSuggestionsEvenWhenDimensionAndCurrentNameRowsRemain() throws Exception
    {
        alias(700, "TALKGROUP", "APCO25", 1001, "Fixture Recorded Dispatch");
        call(1, 1, 1, 700, 1, 1, 101, 1001);
        catalog("UPDATE recording_call SET winner_site_id=1 WHERE id=1",
            "INSERT INTO recording_call_site(call_id,site_id,start_ms) VALUES(1,1,1000)");
        assertFalse(suggestions("Fixture", null, null, 20).isEmpty());

        catalog("DELETE FROM recording_call WHERE id=1");
        for(String kind: List.of("system", "channel", "site", "talkgroup", "radio"))
        {
            assertTrue(suggestions("Fixture", kind, null, 20).isEmpty(), kind);
        }
        assertEquals(2, count(mCatalogDatabase, "recording_system"));
        assertEquals(2, count(mCatalogDatabase, "recording_channel"));
        assertEquals(2, count(mCatalogDatabase, "recording_site"));
        assertEquals(2, count(mCatalogDatabase, "recording_system_site"));
        assertEquals(1, count(mMainDatabase, "alias"));
    }

    private List<Map<String,Object>> suggestions(String query, String kind, String systemKey, int limit)
    {
        return mLabels.suggestions(query, kind, systemKey, limit, mCatalogDatabase);
    }

    private Set<String> labels(String query, String kind, String systemKey, int limit)
    {
        return suggestions(query, kind, systemKey, limit).stream().map(row -> (String)row.get("label"))
            .collect(Collectors.toSet());
    }

    private void alias(int list, String matcher, String protocol, int id, String name) throws Exception
    {
        main("INSERT INTO alias(alias_list_id,matcher_type,protocol,value,name) VALUES(" + list +
            ",'" + matcher + "','" + protocol + "'," + id + ",'" + name + "')");
    }

    private void range(int list, String matcher, int minimum, int maximum, String name) throws Exception
    {
        main("INSERT INTO alias(alias_list_id,matcher_type,protocol,min_value,max_value,name) VALUES(" +
            list + ",'" + matcher + "','APCO25'," + minimum + "," + maximum + ",'" + name + "')");
    }

    private void call(int id, Integer system, Integer channel, int list, int protocol, int type,
                      int source, int target) throws Exception
    {
        catalog("INSERT INTO recording_call(id,start_ms,end_ms,duration_ms,relative_path,size_bytes," +
            "system_id,channel_id,alias_list_id,protocol,call_type,voice_type,source_id,target_id) VALUES(" +
            id + ",1000,2000,1000,'call-" + id + ".mp3',5," + system + "," + channel + "," + list +
            "," + protocol + "," + type + ",1," + source + "," + target + ")");
    }

    private void main(String... sql) throws Exception
    {
        execute(mMainDatabase, sql);
    }

    private void catalog(String... sql) throws Exception
    {
        execute(mCatalogDatabase, sql);
    }

    private static void execute(Path database, String... sql) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            for(String query: sql) statement.executeUpdate(query);
        }
    }

    private static int count(Path database, String table) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + table))
        {
            rows.next();
            return rows.getInt(1);
        }
    }
}
