/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import java.sql.Connection;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StatsAnalogAliasCatalogTest
{
    @TempDir Path mDirectory;
    @Test void catalogUsesProspectiveStoredAnalogCountsAndKeepsProtocolAndRangeScope() throws Exception
    {
        try(Connection connection = fixture(); var sql=connection.createStatement())
        {
            long list;
            try(var rows=sql.executeQuery("SELECT id FROM alias_list WHERE family='NBFM'")) { assertTrue(rows.next()); list=rows.getLong(1); }
            sql.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value,min_value,max_value) VALUES " +
                "(901," + list + ",'Analog FM range','TALKGROUP_RANGE','NBFM',NULL,1,100)," +
                "(902," + list + ",'Analog AM exact','TALKGROUP','AM',7,NULL,NULL)," +
                "(903," + list + ",'Analog FM exact','TALKGROUP','NBFM',7,NULL,NULL)");
            sql.executeUpdate("INSERT INTO alias_activity_summary(alias_id,alias_list_id,protocol_code,metrics_state,logical_call_count,recorded_logical_call_count,stream_submitted_logical_call_count,first_evidence_ms,last_evidence_ms,updated_at_ms) VALUES " +
                "(901," + list + ",10,'observed',8,2,3,1000,2000,2000)," +
                "(902," + list + ",11,'observed',5,1,0,1000,1900,2000)," +
                "(903," + list + ",10,'not_collected',NULL,NULL,NULL,NULL,NULL,2000)");
            sql.executeUpdate("UPDATE alias_activity_summary SET encrypted_logical_call_count=6," +
                "signaling_observation_count=7,grant_observation_count=8 WHERE alias_id=901");
            StatsPairedTestDatabase.alignAliasSummaryIdentities(connection);
            StatsAliasCatalog catalog = new StatsAliasCatalog(new StatsAliasResolver());
            Map<String,Object> response = catalog.aliases(connection,
                new StatsRequest(Map.of("list",Long.toString(list),"sort","logical_call_count","direction","desc")));
            List<?> rows = (List<?>)response.get("rows");
            assertEquals(3, rows.size());
            Map<?,?> first=(Map<?,?>)rows.getFirst();
            assertEquals("Analog FM range",first.get("name"));
            assertEquals(8L,((Number)first.get("logical_call_count")).longValue());
            assertEquals("observed", first.get("metrics_state"));
            assertNull(first.get("signaling_observation_count"));
            assertNull(first.get("encrypted_logical_call_count"));
            assertNull(first.get("grant_observation_count"));
            assertNull(first.get("current_affiliation_count"));
            assertNull(first.get("relationship_count"));
            Map<String,Object> detail=catalog.alias(connection,901);
            assertTrue(((List<?>)detail.get("breakdown")).isEmpty(),
                "Historical per-channel alias counts cannot be inferred from current matchers");
            assertEquals(8L,((Number)((Map<?,?>)detail.get("alias")).get("logical_call_count")).longValue());
            List<Map<String,Object>> observed = new ArrayList<>(List.of(
                observed(list,10,7),observed(list,10,8),observed(list,11,7),observed(list,11,8)));
            new StatsAliasResolver().resolveObservedGroupIdentities(connection,observed);
            assertEquals(List.of("exact","range","exact","none"),observed.stream().map(row->row.get("match_kind")).toList());
            assertEquals(903L,((Number)observed.get(0).get("matched_alias_id")).longValue());
            assertEquals(902L,((Number)observed.get(2).get("matched_alias_id")).longValue());
        }
    }

    @Test void currentAnalogCoverageFollowsAssignedNumberAndExactBeforeRangePrecedence() throws Exception
    {
        try(Connection connection = fixture(); var sql=connection.createStatement())
        {
            long list;
            try(var rows=sql.executeQuery("SELECT id FROM alias_list WHERE family='NBFM'"))
            { assertTrue(rows.next()); list=rows.getLong(1); }
            sql.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value,min_value,max_value) VALUES " +
                "(901," + list + ",'Exact FM','TALKGROUP','NBFM',7,NULL,NULL)," +
                "(902," + list + ",'Range FM','TALKGROUP_RANGE','NBFM',NULL,1,100)," +
                "(903," + list + ",'Other FM','TALKGROUP','NBFM',8,NULL,NULL)," +
                "(904," + list + ",'Exact AM','TALKGROUP','AM',7,NULL,NULL)");
            sql.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,name,alias_list_id,
                    decoder_type,primary_frequency_hz,config_json)
                VALUES('10000000-0000-4000-8000-000000000101','CONVENTIONAL',0,'Analog channel',%d,
                    'NBFM',155100000,'{"decodeConfiguration":{"talkgroup":7}}')
                """.formatted(list));
            sql.executeUpdate("""
                INSERT INTO receiver_channel(configuration_id,first_seen_ms,last_seen_ms)
                VALUES('10000000-0000-4000-8000-000000000101',1000,1000)
                """);
            StatsAliasCatalog catalog=new StatsAliasCatalog(new StatsAliasResolver());
            StatsRequest request=new StatsRequest(Map.of("list",Long.toString(list)));
            Map<String,Object> page=catalog.aliases(connection,request);
            assertEquals("covered_no_evidence",aliasRow(page,901).get("metrics_state"));
            assertEquals(0L,((Number)aliasRow(page,901).get("logical_call_count")).longValue());
            for(long id:List.of(902L,903L,904L))
            {
                assertEquals("not_collected",aliasRow(page,id).get("metrics_state"));
                assertNull(aliasRow(page,id).get("logical_call_count"));
            }
            sql.executeUpdate("""
                UPDATE configuration_channel SET config_json='{"decodeConfiguration":{"talkgroup":9}}'
                WHERE configuration_id='10000000-0000-4000-8000-000000000101'
                """);
            page=catalog.aliases(connection,request);
            assertEquals("not_collected",aliasRow(page,901).get("metrics_state"));
            assertEquals("covered_no_evidence",aliasRow(page,902).get("metrics_state"));
            sql.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,min_value,max_value) VALUES " +
                "(905," + list + ",'Winning equal range','TALKGROUP_RANGE','NBFM',1,100)");
            page=catalog.aliases(connection,request);
            assertEquals("not_collected",aliasRow(page,902).get("metrics_state"));
            assertEquals("covered_no_evidence",aliasRow(page,905).get("metrics_state"));
        }
    }

    private static Map<?,?> aliasRow(Map<String,Object> page,long aliasId)
    {
        return ((List<?>)page.get("rows")).stream().map(row->(Map<?,?>)row)
            .filter(row->((Number)row.get("alias_id")).longValue()==aliasId).findFirst().orElseThrow();
    }

    @Test void malformedDiscoveryRowsNeverOfferAliasCreation() throws Exception
    {
        try(Connection connection = fixture())
        {
            List<Map<String,Object>> rows = new ArrayList<>(List.of(
                observed(1,99,7), observed(1,10,65536), observed(1,11,0), observed(0,10,7)));
            Map<String,Object> missingNumber = observed(1,10,7);
            missingNumber.remove("group_identity_id");
            rows.add(missingNumber);
            new StatsAliasResolver().resolveObservedGroupIdentities(connection,rows);
            for(Map<String,Object> row: rows)
            {
                assertEquals(false,row.get("promotion_supported"));
                assertEquals("none",row.get("match_kind"));
            }
        }
    }

    private Connection fixture() throws Exception
    {
        Path configuration = mDirectory.resolve("analog.sqlite");
        SdrTrunkTestDatabase.create(configuration);
        return StatsPairedTestDatabase.open(configuration);
    }

    private static Map<String,Object> observed(long list,int protocol,int number)
    {
        Map<String,Object> row=new LinkedHashMap<>();
        row.put("alias_list_id",list); row.put("protocol_code",protocol); row.put("group_identity_id",number);
        row.put("topology","CONVENTIONAL"); return row;
    }
}
