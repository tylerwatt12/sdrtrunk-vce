package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Configuration removal and scheduled history expiry have independent, directly exercised lifecycles. */
class ConfigurationHistoryRetentionTest
{
    private static final String ACTIVE = "123e4567-e89b-42d3-a456-426614174001";
    private static final String DETACHED = "123e4567-e89b-42d3-a456-426614174002";

    @Test
    void oldMatcherGenerationExpiresWhileMatchingActiveLifetimeSummaryRemains(@TempDir Path temporary)
        throws Exception
    {
        Path configuration = temporary.resolve("sdrtrunk.sqlite");
        io.github.dsheirer.database.SdrTrunkTestDatabase.create(configuration);
        try(Connection config = SdrTrunkDatabase.open(configuration);
            Connection history = SdrTrunkDatabase.openHistory(configuration))
        {
            execute(config,"INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) " +
                "SELECT 100,id,'Old matcher','TALKGROUP','APCO25',1 FROM alias_list WHERE family='P25' LIMIT 1");
            execute(config,"INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) " +
                "SELECT 101,id,'Active matcher','TALKGROUP','APCO25',2 FROM alias_list WHERE family='P25' LIMIT 1");
            io.github.dsheirer.stats.AliasActivitySummaryMaintenance.ensureCurrent(history,100,1,1);
            io.github.dsheirer.stats.AliasActivitySummaryMaintenance.ensureCurrent(history,101,1,1);
            execute(history,"UPDATE alias_activity_summary SET logical_call_count=55,last_evidence_ms=10,first_evidence_ms=10");
            execute(config,"UPDATE alias SET activity_identity='123e4567-e89b-42d3-a456-426614174999',value=3 WHERE id=100");
            for(int pass=0;pass<5;pass++) ReceiverActivityRetention.runPass(history,20);
            assertEquals(0,scalar(history,"SELECT count(*) FROM alias_activity_summary WHERE alias_id=100"));
            assertEquals(55,scalar(history,"SELECT logical_call_count FROM alias_activity_summary WHERE alias_id=101"));
            assertEquals(1,scalar(config,"SELECT count(*) FROM alias WHERE id=100"));
        }
    }

    @Test
    void aliasCleanupSeedsMissingFencesInBoundedBatchesAndDeletesDetachedSummaries(@TempDir Path temporary)
        throws Exception
    {
        Path configuration = temporary.resolve("sdrtrunk.sqlite");
        io.github.dsheirer.database.SdrTrunkTestDatabase.create(configuration);
        try(Connection config = SdrTrunkDatabase.open(configuration);
            Connection history = SdrTrunkDatabase.openHistory(configuration))
        {
            execute(config, """
                WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<1200)
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value,activity_reset_ms)
                SELECT 1000+value,(SELECT id FROM alias_list WHERE family='DMR' LIMIT 1),
                    'Alias '||value,'TALKGROUP','DMR',value,1 FROM n
                """);
            execute(config, """
                INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,name,alias_list_id,
                    decoder_type,config_json)
                VALUES('%s','TRUNKED',0,'Control',(SELECT id FROM alias_list WHERE family='DMR' LIMIT 1),'DMR','{"decodeConfiguration":{"channelMode":"TRUNKED"}}')
                """.formatted(ACTIVE));
            io.github.dsheirer.stats.AliasActivitySummaryMaintenance.ensureCurrent(history,1001,3,10);
            io.github.dsheirer.stats.AliasActivitySummaryMaintenance.ensureCurrent(history,1002,3,10);
            execute(config, "UPDATE alias SET activity_identity='123e4567-e89b-42d3-a456-426614174999'," +
                "activity_reset_ms=20 WHERE id=1001");
            execute(config, "DELETE FROM alias WHERE id=1002");
            var target = new StatsDatabaseMaintenanceRequest.ScopedData("alias_activity",null,null,null,
                "alias_activity",null,java.util.List.of("summary"));
            var request = StatsDatabaseMaintenanceRequest.delete(target);
            ReceiverActivityMaintenance.Result result = null;
            int passes = 0;
            while(result == null && passes++ < 20)
            {
                long before = request.progress().rowsDeleted();
                result = ReceiverActivityMaintenance.deleteRetainedStatsPass(history,configuration,request,0);
                assertTrue(request.progress().rowsDeleted()-before <= 512);
            }
            assertTrue(result != null);
            assertTrue(passes > 1);
            assertEquals(1199,scalar(history,"SELECT count(*) FROM alias_activity_summary"));
            assertEquals(0,scalar(history,"SELECT count(*) FROM alias_activity_summary WHERE alias_id=1002"));
            long boundary = request.progress().cutoffMs()+1;
            assertEquals(1199,scalar(history,"SELECT count(*) FROM alias_activity_summary WHERE last_reset_ms="+boundary));
            assertEquals(1199,scalar(history,"SELECT count(*) FROM alias_activity_summary summary JOIN alias configured " +
                "ON configured.id=summary.alias_id AND configured.activity_identity=summary.activity_identity"));
            for(int nativeId: new int[]{1,3})
            {
                AliasActivityProjection.recordActivity(history,signal(boundary-1,nativeId));
                assertEquals(0,scalar(history,"SELECT coalesce(signaling_observation_count,0) " +
                    "FROM alias_activity_summary WHERE alias_id="+(1000+nativeId)));
                AliasActivityProjection.recordActivity(history,signal(boundary+1,nativeId));
                assertEquals(1,scalar(history,"SELECT signaling_observation_count FROM alias_activity_summary " +
                    "WHERE alias_id="+(1000+nativeId)));
            }
        }
    }

    private static ReceiverActivityRecords.ActivityEvent signal(long observedAt, int target)
    {
        return new ReceiverActivityRecords.ActivityEvent(observedAt,ACTIVE,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE,"DMR",ReceiverActivityRecords.Action.JOIN,
            "JOIN",null,Integer.toString(target),"TALKGROUP",java.util.List.of(),451_000_000L,null,1,
            false,null,null,null,42,null,null,1,null,false,null,null,
            io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain.STANDARD,
            ReceiverActivityRecords.P25Identity.UNKNOWN,ReceiverActivityRecords.P25Identity.UNKNOWN,
            java.util.List.of(),null);
    }

    @Test
    void explicitCleanupCanStillResolveAChannelAfterConfigurationRemoval(@TempDir Path temporary) throws Exception
    {
        Path configuration=temporary.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(configuration);
        try(Connection config=io.github.dsheirer.database.SdrTrunkDatabase.open(configuration);
            Connection history=io.github.dsheirer.database.SdrTrunkDatabase.openHistory(configuration))
        {
            execute(config,"""
                INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,name,alias_list_id,
                    decoder_type,config_json)
                VALUES('%s','CONVENTIONAL',0,'Removed channel',
                    (SELECT id FROM alias_list WHERE family='NBFM' LIMIT 1),'NBFM','{}')
                """.formatted(DETACHED));
            execute(history,"""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,name,channel_kind,decoder_type)
                VALUES(1,'%s',1000,1000,'Removed channel','CONVENTIONAL','NBFM')
                """.formatted(DETACHED));
            execute(history,"INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code) VALUES(1,1000,4)");
            execute(config,"DELETE FROM configuration_channel WHERE configuration_id='"+DETACHED+"'");
            StatsDatabaseMaintenanceRequest request=StatsDatabaseMaintenanceRequest.delete(
                new StatsDatabaseMaintenanceRequest.ScopedData("saved_channel",DETACHED,null,null,"all",null,
                    java.util.List.of("summary","buckets","events")));
            ReceiverActivityMaintenance.Result result=null;
            for(int pass=0;pass<20 && result==null;pass++)
                result=ReceiverActivityMaintenance.deleteRetainedStatsPass(history,configuration,request,0);
            assertTrue(result!=null);
            assertEquals(ReceiverActivityMaintenance.DeletionOutcome.DELETED,result.deletionOutcome());
            assertEquals(0,scalar(history,"SELECT count(*) FROM receiver_activity_event"));
            assertEquals(0,scalar(config,"SELECT count(*) FROM configuration_channel"));
        }
    }

    @Test
    void deletionPreservesHistoryAndRetentionExpiresDetachedOwnersWithoutClearingActiveLifetimeTotals(
        @TempDir Path temporary) throws Exception
    {
        Path configuration = temporary.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(configuration);
        try(Connection config = SdrTrunkDatabase.open(configuration);
            Connection history = SdrTrunkDatabase.openHistory(configuration))
        {
            assertIndexed(history,"SELECT configuration_id FROM receiver_channel_statistics_reset WHERE reset_at_ms<20000 " +
                "ORDER BY reset_at_ms,configuration_id LIMIT 256", "idx_receiver_channel_statistics_reset_retention");
            assertIndexed(history,"SELECT id FROM receiver_channel WHERE last_seen_ms<20000 ORDER BY last_seen_ms,id LIMIT 256",
                "idx_receiver_channel_retention");
            assertIndexed(history,"SELECT alias_id FROM alias_activity_summary WHERE coalesce(last_evidence_ms,updated_at_ms)<20000 " +
                "ORDER BY coalesce(last_evidence_ms,updated_at_ms),alias_id LIMIT 256", "idx_alias_activity_retention");
            assertIndexed(history,"SELECT channel_id FROM conventional_activity_summary WHERE last_seen_ms<20000 " +
                "ORDER BY last_seen_ms,channel_id,frequency_hz,timeslot LIMIT 256", "idx_conventional_summary_retention");
            assertIndexed(history,"SELECT alias_list_id FROM alias_list_analog_activity_summary WHERE last_seen_ms<20000 " +
                "ORDER BY last_seen_ms,alias_list_id,channel_configuration_id,protocol_code,assigned_talkgroup LIMIT 256",
                "idx_alias_analog_retention");
            assertIndexed(history,"SELECT 1 FROM trunked_radio_affiliation WHERE channel_id=2",
                "idx_trunked_radio_affiliation_channel");
            assertIndexed(history,"SELECT 1 FROM trunked_radio_channel_presence_clear WHERE channel_id=2",
                "idx_trunked_radio_presence_clear_channel");
            assertIndexed(history,"SELECT 1 FROM p25_wuid_assignment_observation_summary WHERE last_channel_id=2",
                "idx_p25_wuid_assignment_observation_channel");
            execute(config, """
                INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,
                    site_name,name,alias_list_id,decoder_type,address_domain_code,primary_frequency_hz,config_json)
                VALUES('%s','CONVENTIONAL',0,'System','Site','Active',(SELECT id FROM alias_list WHERE family='NBFM' LIMIT 1),'NBFM',0,450000000,'{}'),
                    ('%s','CONVENTIONAL',1,'System','Site','Removed channel',(SELECT id FROM alias_list WHERE family='NBFM' LIMIT 1),'NBFM',0,450000000,'{}')
                """.formatted(ACTIVE, DETACHED));
            execute(config, """
                INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value,activity_reset_ms)
                SELECT id,'Active Alias','TALKGROUP','NBFM',1,1 FROM alias_list WHERE family='NBFM' LIMIT 1
                """);
            execute(config, """
                INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value,activity_reset_ms)
                SELECT id,'Removed Alias','TALKGROUP','NBFM',2,1 FROM alias_list WHERE family='NBFM' LIMIT 1
                """);
            execute(history, """
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms,name,system_name,
                    site_name,channel_kind,decoder_type,address_domain_code,primary_frequency_hz)
                VALUES(1,'%s',1,1,'Active','System','Site','CONVENTIONAL','NBFM',0,450000000),
                    (2,'%s',1,10000,'Removed channel','System','Site','CONVENTIONAL','NBFM',0,450000000)
                """.formatted(ACTIVE, DETACHED));
            execute(history,"INSERT INTO receiver_channel_statistics_reset VALUES('"+ACTIVE+"',1),('"+DETACHED+"',10000)");
            execute(history, """
                WITH RECURSIVE event(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM event WHERE n<2000)
                INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code)
                SELECT 2,10000,4 FROM event
                """);
            execute(history, """
                INSERT INTO conventional_activity_summary(channel_id,frequency_hz,first_seen_ms,last_seen_ms,call_count)
                VALUES(1,450000000,1,1,101),(2,450000000,1,10000,202)
                """);
            execute(history, """
                INSERT INTO alias_activity_summary(alias_id,alias_list_id,protocol_code,metrics_state,
                    logical_call_count,first_evidence_ms,last_evidence_ms,updated_at_ms,activity_identity,
                    alias_name,alias_list_name)
                SELECT configured.id,configured.alias_list_id,10,'observed',303,1,10000,10000,
                    configured.activity_identity,configured.name,list.name
                FROM alias configured JOIN alias_list list ON list.id=configured.alias_list_id
                """);

            execute(history,"""
                INSERT INTO radio_system(id,system_key,protocol_code,address_domain_code,p25_wacn,p25_system_id,
                    first_seen_ms,last_seen_ms) VALUES(7,'p25:abcde:321',1,0,0xABCDE,0x321,10000,10000)
                """);
            execute(history,"INSERT INTO p25_subscriber_identity VALUES(7,0xABCDE,0x321,9001)");
            execute(history,"""
                INSERT INTO p25_wuid_assignment_observation_summary(radio_system_id,working_id,
                    p25_subscriber_identity_id,first_observed_ms,last_observed_ms,last_registration_ms,
                    registration_count,affiliation_count,last_evidence_code)
                VALUES(7,123,7,10000,10000,10000,1,0,1)
                """);
            ReceiverActivityRetention.runPass(history,0);
            assertEquals(1,scalar(history,"SELECT count(*) FROM p25_subscriber_identity"),
                "an assignment alone retains its subscriber without a directory identity or configured Alias");
            assertEquals(1,scalar(history,"SELECT count(*) FROM p25_wuid_assignment_observation_summary"));

            execute(config, "DELETE FROM configuration_channel WHERE configuration_id='" + DETACHED + "'");
            execute(config, "DELETE FROM alias WHERE name='Removed Alias'");
            assertEquals(2000, scalar(history, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(2, scalar(history, "SELECT count(*) FROM alias_activity_summary"));

            ReceiverActivityRetention.runPass(history, 5000);
            assertEquals(2000, scalar(history, "SELECT count(*) FROM receiver_activity_event"),
                "retention uses observation time, not configuration deletion time");
            assertEquals(202, scalar(history,
                "SELECT call_count FROM conventional_activity_summary WHERE channel_id=2"));
            try(Connection reader = SdrTrunkDatabase.openHistoryRead(configuration);
                Statement statement = reader.createStatement();
                ResultSet row = statement.executeQuery("SELECT resolved_channel_name FROM receiver_activity_event_resolved LIMIT 1"))
            {
                assertTrue(row.next());
                assertEquals("Removed channel", row.getString(1));
            }

            boolean finished = false;
            for(int pass = 0; pass < 20; pass++)
            {
                ReceiverActivityRetention.Pass result = ReceiverActivityRetention.runPass(history, 20000);
                assertTrue(result.deletedRows() <= ReceiverActivityRetention.MAXIMUM_ROWS_PER_PASS);
                if(!result.moreWorkLikely()) { finished = true; break; }
            }
            assertTrue(finished, "scheduled follow-up passes drain the detached data");
            assertEquals(0, scalar(history, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(0,scalar(history,"SELECT count(*) FROM p25_wuid_assignment_observation_summary"));
            assertEquals(0,scalar(history,"SELECT count(*) FROM p25_subscriber_identity"));
            assertEquals(1, scalar(history, "SELECT count(*) FROM receiver_channel"));
            assertEquals(1,scalar(history,"SELECT count(*) FROM receiver_channel_statistics_reset"));
            assertEquals(101, scalar(history, "SELECT call_count FROM conventional_activity_summary"));
            assertEquals(1, scalar(history, "SELECT count(*) FROM alias_activity_summary"));
            assertEquals(303, scalar(history, "SELECT logical_call_count FROM alias_activity_summary"));
        }
    }

    private static void execute(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet row = statement.executeQuery(sql))
        {
            assertTrue(row.next());
            return row.getLong(1);
        }
    }

    private static void assertIndexed(Connection connection,String sql,String index) throws Exception
    {
        StringBuilder plan=new StringBuilder();
        try(Statement statement=connection.createStatement();ResultSet rows=statement.executeQuery("EXPLAIN QUERY PLAN "+sql))
        { while(rows.next()) plan.append(rows.getString("detail")).append('\n'); }
        assertTrue(plan.toString().contains("SEARCH") && plan.toString().contains(index),plan.toString());
    }
}
