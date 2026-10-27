/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.ScopedData;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiverActivityLinkedHistoryDeletionTest
{
    private static final String CHANNEL = "123e4567-e89b-42d3-a456-426614174000";
    private static final String OTHER_CHANNEL = "223e4567-e89b-42d3-a456-426614174000";
    private static final String SYSTEM = "p25:abcde:123";
    private static final String FOREIGN_RADIO = "v1-r-abcde-321-9001";

    @Test
    void systemSummaryCleanupKeepsUnlinkedEventsAndRemovesIndependentIdentityMembers() throws Exception
    {
        try(Connection connection = open())
        {
            seedP25(connection, 2);
            execute(connection, "INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code," +
                "identity_id,first_seen_ms,last_seen_ms) VALUES(4,1,1,100,1000,1000)");
            execute(connection, "INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms," +
                "action_code,source_identity_summary_id,target_identity_summary_id,target_kind_code,target_observed_local_id) VALUES" +
                "(1,1,1,1000,4,1,NULL,NULL,NULL),(2,1,1,1000,4,NULL,1,2,9001),(3,1,1,1000,4,NULL,NULL,NULL,NULL)," +
                "(4,2,2,1000,4,2,NULL,NULL,NULL)");
            execute(connection, "INSERT INTO activity_event_identity_member(event_id,radio_system_id," +
                "identity_summary_id,channel_id) VALUES(3,1,4,1)");
            execute(connection, "INSERT INTO receiver_activity_tone_sequence(event_id,source_code,method_code," +
                "call_started_at_ms,completed_at_ms,tone_count) VALUES(1,1,1,900,1000,1),(3,1,1,900,1000,1)");
            execute(connection, "INSERT INTO receiver_activity_tone(event_id,ordinal,frequency_1_millihz,duration_ms) " +
                "VALUES(1,0,1000000,10),(3,0,1000000,10)");
            ScopedData target = new ScopedData("radio_system", SYSTEM, null, null, "all", null, List.of("summary"));
            assertEquals(5, ReceiverActivityMaintenance.preview(connection, target).countsByPart().get("events"));
            finish(connection, StatsDatabaseMaintenanceRequest.delete(target));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE id IN (3,4)"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM activity_event_identity_member"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_tone WHERE event_id=3"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE id=2"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void radioSummaryDeletionIncludesProvenAssociatedHistoryAutomatically() throws Exception
    {
        try(Connection connection = open())
        {
            seedP25(connection, 2);
            var preview = ReceiverActivityMaintenance.preview(connection, radio());
            assertEquals(2, preview.countsByPart().get("issi_assignment_history"));
            assertEquals(3, preview.rowsTotal());
            assertEquals(2, preview.countsByPart().get("issi_assignment_history"));
            finish(connection, StatsDatabaseMaintenanceRequest.delete(radio()));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE id=1"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM p25_wuid_assignment_observation_summary"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM p25_subscriber_identity"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM alias_p25_subscriber_identity"));
        }
    }

    @Test
    void associatedHistoryUsesProvenSubscriberAndReceivingSystemNotRepeatedRadioOrWorkingIdNumbers() throws Exception
    {
        try(Connection connection = open())
        {
            seedP25(connection, 2);
            var preview = ReceiverActivityMaintenance.preview(connection, radio());
            assertEquals(2, preview.countsByPart().get("issi_assignment_history"));
            assertEquals(2, preview.countsByPart().get("issi_assignment_history"));
            assertEquals(1, preview.countsByPart().get("summary"));
            assertEquals(3, preview.rowsTotal());
            finish(connection, StatsDatabaseMaintenanceRequest.delete(radio()));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM p25_wuid_assignment_observation_summary"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM p25_wuid_assignment_observation_summary " +
                "WHERE radio_system_id=2 AND p25_subscriber_identity_id=1"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM p25_wuid_assignment_observation_summary " +
                "WHERE radio_system_id=1 AND p25_subscriber_identity_id=2 AND working_id=9001"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM alias"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM alias_p25_subscriber_identity"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM configuration_channel"));
            assertEquals("ok", text(connection, "PRAGMA quick_check"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void localHomeRadioRouteUsesTheResolvedQualifiedOwner() throws Exception
    {
        try(Connection connection = open())
        {
            seedP25(connection, 2);
            execute(connection, "UPDATE p25_subscriber_identity SET home_system_id=291 WHERE id=1");
            execute(connection, "UPDATE radio_system_identity_summary SET home_system_id=291 WHERE id=1");
            ScopedData local = new ScopedData("radio_system", SYSTEM, null, null,
                "radios", "v1-r-x-x-9001", List.of("summary"));
            assertEquals(2, ReceiverActivityMaintenance.preview(connection, local).countsByPart().get("issi_assignment_history"));
            finish(connection, StatsDatabaseMaintenanceRequest.delete(local));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM p25_wuid_assignment_observation_summary"));
        }
    }

    @Test
    void unprovenUnknownAndNonP25RadioDeletionNeverGuessesAnAssociatedSubscriber() throws Exception
    {
        try(Connection connection = open())
        {
            seedP25(connection, 2);
            ScopedData unproven = new ScopedData("radio_system", SYSTEM, null, null,
                "radios", "v1-r-x-x-9002", List.of("summary"));
            var preview = ReceiverActivityMaintenance.preview(connection, unproven);
            assertFalse(preview.countsByPart().containsKey("issi_assignment_history"));
            assertEquals(1, preview.rowsTotal());
            finish(connection, StatsDatabaseMaintenanceRequest.delete(unproven));
            ScopedData unknown = new ScopedData("radio_system", SYSTEM, null, null,
                "radios", "v1-r-x-x-9999", List.of("summary"));
            assertFalse(ReceiverActivityMaintenance.preview(connection, unknown).countsByPart().containsKey("issi_assignment_history"));
            assertEquals(0, ReceiverActivityMaintenance.preview(connection, unknown).rowsTotal());
            execute(connection, "INSERT INTO radio_system(id,system_key,protocol_code,dmr_model_code,dmr_network_id," +
                "first_seen_ms,last_seen_ms) VALUES(3,'dmr:tier3:small:42',3,2,42,1000,1000)");
            execute(connection, "INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,identity_id," +
                "first_seen_ms,last_seen_ms) VALUES(4,3,2,9001,1000,1000)");
            ScopedData dmr = new ScopedData("radio_system", "dmr:tier3:small:42", null, null,
                "radios", "v1-r-x-x-9001", List.of("summary"));
            assertFalse(ReceiverActivityMaintenance.preview(connection, dmr).countsByPart().containsKey("issi_assignment_history"));
            finish(connection, StatsDatabaseMaintenanceRequest.delete(dmr));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE id IN (3,4)"));
            assertEquals(4, scalar(connection, "SELECT count(*) FROM p25_wuid_assignment_observation_summary"));
        }
    }

    @Test
    void deletingOnlySelectedRadioEventsKeepsItsSummaryAndAssignmentHistory() throws Exception
    {
        try(Connection connection = open())
        {
            seedP25(connection, 2);
            execute(connection, "INSERT INTO receiver_activity_event(channel_id,radio_system_id,observed_at_ms,action_code," +
                "source_identity_summary_id) VALUES(1,1,1000,4,1)");
            ScopedData events = new ScopedData("radio_system", SYSTEM, null, null,
                "radios", FOREIGN_RADIO, List.of("events"));
            var preview = ReceiverActivityMaintenance.preview(connection, events);
            assertFalse(preview.countsByPart().containsKey("issi_assignment_history"));
            finish(connection, StatsDatabaseMaintenanceRequest.delete(events));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE id=1"));
            assertEquals(4, scalar(connection, "SELECT count(*) FROM p25_wuid_assignment_observation_summary"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
        }
    }

    @Test
    void linkedHistoryPassesAreBoundedAndResumeKeepsOriginalCutoffAndFreshAssignments() throws Exception
    {
        try(Connection connection = open())
        {
            seedP25(connection, 1500);
            String plan = queryPlan(connection, "SELECT radio_system_id,working_id,p25_subscriber_identity_id " +
                "FROM p25_wuid_assignment_observation_summary INDEXED BY idx_p25_wuid_assignment_observation_subscriber " +
                "WHERE radio_system_id=1 " +
                "AND p25_subscriber_identity_id=1 AND last_observed_ms<=1000 " +
                "ORDER BY p25_subscriber_identity_id,last_observed_ms DESC LIMIT 512");
            assertTrue(plan.contains("idx_p25_wuid_assignment_observation_subscriber"), plan);
            assertFalse(plan.contains("TEMP B-TREE"), plan);
            StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.delete(radio());
            assertNull(ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0));
            assertTrue(request.progress().rowsDeleted() > 0 && request.progress().rowsDeleted() <= 512);
            long cutoff = request.progress().cutoffMs();
            long later = cutoff + 1;
            execute(connection, "UPDATE p25_wuid_assignment_observation_summary SET last_observed_ms=" + later +
                ",last_registration_ms=" + later + ",registration_count=2 WHERE radio_system_id=1 " +
                "AND p25_subscriber_identity_id=1 AND working_id=1500");
            execute(connection, "INSERT INTO p25_wuid_assignment_observation_summary " +
                "(radio_system_id,working_id,p25_subscriber_identity_id,first_observed_ms,last_observed_ms," +
                "last_registration_ms,registration_count,last_evidence_code) VALUES(1,1501,1," +
                later + "," + later + "," + later + ",1,1)");
            request.cancel();
            var cancelled = ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0);
            request.result().complete(cancelled);
            var resumed = request.resume();
            finish(connection, resumed);
            assertEquals(cutoff, resumed.progress().cutoffMs());
            assertEquals(1500, resumed.progress().rowsDeleted());
            assertEquals(1, resumed.progress().rowsRetained());
            assertEquals(4, scalar(connection, "SELECT count(*) FROM p25_wuid_assignment_observation_summary"));
            assertEquals(2, scalar(connection, "SELECT registration_count FROM p25_wuid_assignment_observation_summary " +
                "WHERE radio_system_id=1 AND p25_subscriber_identity_id=1 AND working_id=1500"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM alias_p25_subscriber_identity"));
        }
    }

    @Test
    void bulkRadioSummaryDeletionClearsOnlyHistoryWithProvenOwnersInTheReceivingSystem() throws Exception
    {
        try(Connection connection = open())
        {
            seedP25(connection, 2);
            var target = new ScopedData("radio_system", SYSTEM, null, null, "radios", null, List.of("summary"));
            var preview = ReceiverActivityMaintenance.preview(connection, target);
            assertEquals(2, preview.countsByPart().get("issi_assignment_history"));
            assertEquals(4, preview.rowsTotal());
            String plan = queryPlan(connection, "SELECT radio_system_id,working_id,p25_subscriber_identity_id " +
                "FROM p25_wuid_assignment_observation_summary INDEXED BY idx_p25_wuid_assignment_observation_subscriber " +
                "WHERE radio_system_id=1 AND p25_subscriber_identity_id IN " +
                "(SELECT p25_subscriber_identity_id FROM radio_system_identity_summary WHERE radio_system_id=1 " +
                "AND identity_kind_code=2 AND p25_subscriber_identity_id IS NOT NULL) AND last_observed_ms<=1000 " +
                "ORDER BY p25_subscriber_identity_id,last_observed_ms DESC LIMIT 512");
            assertTrue(plan.contains("idx_p25_wuid_assignment_observation_subscriber"), plan);
            assertFalse(plan.contains("TEMP B-TREE"), plan);
            finish(connection, StatsDatabaseMaintenanceRequest.delete(target));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE radio_system_id=1"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary WHERE radio_system_id=2"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM p25_wuid_assignment_observation_summary " +
                "WHERE radio_system_id=2 AND p25_subscriber_identity_id=1"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM p25_wuid_assignment_observation_summary " +
                "WHERE radio_system_id=1 AND p25_subscriber_identity_id=2"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM alias_p25_subscriber_identity"));
            assertEquals("ok", text(connection, "PRAGMA quick_check"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void conventionalCallTotalsRemoveCallOnlyDmrSummariesAndPreserveOtherData() throws Exception
    {
        try(Connection connection = open())
        {
            seedDmr(connection, 2);
            var preview = ReceiverActivityMaintenance.preview(connection, calls(List.of("summary")));
            assertEquals(5, preview.rowsTotal());
            assertTrue(preview.effects().stream().anyMatch(effect ->
                effect.contains("DMR radio and talkgroup call summaries, when present, are removed")));
            finish(connection, StatsDatabaseMaintenanceRequest.delete(calls(List.of("summary"))));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM dmr_conventional_radio_summary WHERE channel_id=1"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM dmr_conventional_talkgroup_summary WHERE channel_id=1"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM dmr_conventional_radio_summary WHERE channel_id=2"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM dmr_conventional_talkgroup_summary WHERE channel_id=2"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM conventional_activity_summary WHERE channel_id=1 " +
                "AND call_count=0 AND encrypted_count=0 AND recorded_count=0 AND streamed_count=0 AND grant_count=7"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM conventional_activity_summary WHERE channel_id=2 AND call_count=5"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM conventional_call_identity_bucket"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM alias"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM configuration_channel"));
            assertEquals("ok", text(connection, "PRAGMA quick_check"));
        }
    }

    @Test
    void conventionalDmrCallClearKeepsPostCutoffSummariesAndResumesBoundedPasses() throws Exception
    {
        try(Connection connection = open())
        {
            seedDmr(connection, 1500);
            var request = StatsDatabaseMaintenanceRequest.delete(calls(List.of("summary", "buckets", "events")));
            long changesBefore = scalar(connection, "SELECT total_changes()");
            assertNull(ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0));
            assertTrue(scalar(connection, "SELECT total_changes()") - changesBefore <= 512);
            assertTrue(request.progress().rowsDeleted() > 0 && request.progress().rowsDeleted() <= 512,
                "The pass bounds examined candidates as well as changed rows, including its reset fence");
            String boundaryQuery = "SELECT reset_at_ms FROM receiver_channel_statistics_reset " +
                "WHERE configuration_id='" + CHANNEL + "'";
            long boundary = scalar(connection, boundaryQuery);
            assertEquals(request.progress().cutoffMs() + 1, boundary);
            long later = request.progress().cutoffMs() + 1;
            execute(connection, "UPDATE dmr_conventional_radio_summary SET last_seen_ms=" + later +
                ",call_count=2 WHERE channel_id=1 AND radio_id=1500");
            execute(connection, "INSERT INTO dmr_conventional_radio_summary " +
                "(channel_id,frequency_hz,timeslot,radio_id,first_seen_ms,last_seen_ms,call_count) " +
                "VALUES(1,451000000,1,1501," + later + "," + later + ",1)");
            request.cancel();
            request.result().complete(ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0));
            var resumed = request.resume();
            finish(connection, resumed);
            assertEquals(boundary, scalar(connection, boundaryQuery));
            long changesBeforeRepeat = scalar(connection, "SELECT total_changes()");
            connection.setAutoCommit(false);
            try
            {
                assertEquals(0, ReceiverActivityScopedDeletion.Batch.start(connection, calls(List.of("summary")), boundary - 1)
                    .runPass(connection), "An unchanged boundary and cleared history create no repeat writes");
            }
            finally
            {
                connection.rollback();
                connection.setAutoCommit(true);
            }
            assertEquals(changesBeforeRepeat, scalar(connection, "SELECT total_changes()"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM dmr_conventional_radio_summary WHERE channel_id=1"));
            assertEquals(2, scalar(connection, "SELECT call_count FROM dmr_conventional_radio_summary WHERE channel_id=1 AND radio_id=1500"));
            assertEquals(1, resumed.progress().rowsRetained());
            assertEquals(0, scalar(connection, "SELECT count(*) FROM conventional_call_identity_bucket"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event WHERE action_code=12"));
            assertEquals(1, scalar(connection, "SELECT grant_count FROM conventional_activity_bucket WHERE channel_id=1"));
            assertEquals(7, scalar(connection, "SELECT grant_count FROM conventional_activity_summary WHERE channel_id=1"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM alias"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    private static ScopedData radio()
    {
        return new ScopedData("radio_system", SYSTEM, null, null, "radios", FOREIGN_RADIO,
            List.of("summary"));
    }

    private static ScopedData calls(List<String> parts)
    {
        return new ScopedData("saved_channel", CHANNEL, null, null, "call_activity", null, parts);
    }

    private static void seedP25(Connection connection, int mappings) throws Exception
    {
        for(int id=1; id<=2; id++)
        {
            String configuration=id==1 ? CHANNEL : OTHER_CHANNEL;
            execute(connection,"INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,name," +
                "alias_list_id,radioresolve_id,decoder_type,primary_frequency_hz,config_json) VALUES('" + configuration +
                "','TRUNKED'," + id + ",'Site',(SELECT id FROM alias_list WHERE family='P25' LIMIT 1)," +
                "'aaaaaaaa-aaaa-4aaa-8aaa-00000000000" + id + "','P25_PHASE1',851000000,'{}')");
        }
        execute(connection, "INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms) " +
            "VALUES(1,'" + SYSTEM + "',1,703710,291,1000,1000),(2,'p25:abcde:124',1,703710,292,1000,1000)");
        execute(connection, "INSERT INTO receiver_channel(id,configuration_id,radio_system_id,radio_system_assigned_at_ms,first_seen_ms,last_seen_ms) " +
            "VALUES(1,'" + CHANNEL + "',1,1000,1000,1000),(2,'" + OTHER_CHANNEL + "',2,1000,1000,1000)");
        execute(connection, "INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) " +
            "VALUES(1,703710,801,9001),(2,703710,802,9001)");
        execute(connection, "INSERT INTO radio_system_identity_summary" +
            "(id,radio_system_id,identity_kind_code,identity_id,home_wacn,home_system_id,p25_subscriber_identity_id,first_seen_ms,last_seen_ms) " +
            "VALUES(1,1,2,9001,703710,801,1,1000,1000),(2,2,2,9001,703710,801,1,1000,1000)," +
            "(3,1,2,9002,-1,-1,NULL,1000,1000)");
        execute(connection, "WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<" + mappings + ") " +
            "INSERT INTO p25_wuid_assignment_observation_summary " +
            "(radio_system_id,working_id,p25_subscriber_identity_id,first_observed_ms,last_observed_ms,last_registration_ms," +
            "registration_count,last_evidence_code) SELECT 1,value,1,1000,1000,1000,1,1 FROM n");
        execute(connection, "INSERT INTO p25_wuid_assignment_observation_summary " +
            "(radio_system_id,working_id,p25_subscriber_identity_id,first_observed_ms,last_observed_ms,last_registration_ms," +
            "registration_count,last_evidence_code) VALUES(2,9001,1,1000,1000,1000,1,1),(1,9001,2,1000,1000,1000,1,1)");
        execute(connection, "INSERT INTO alias(alias_list_id,name,matcher_type) " +
            "SELECT id,'Medic 7','P25_SUBSCRIBER_IDENTITY' FROM alias_list WHERE family='P25' LIMIT 1");
        execute(connection, "INSERT INTO alias_p25_subscriber_identity(alias_id,p25_subscriber_identity_id) " +
            "SELECT id,1 FROM alias WHERE name='Medic 7'");
    }

    private static void seedDmr(Connection connection, int radios) throws Exception
    {
        for(int id=1; id<=2; id++)
        {
            String configuration=id==1 ? CHANNEL : OTHER_CHANNEL;
            execute(connection, "INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,name,alias_list_id," +
                "decoder_type,primary_frequency_hz,config_json) VALUES('" + configuration + "','CONVENTIONAL'," + id +
                ",'DMR',(SELECT id FROM alias_list WHERE family='DMR' LIMIT 1),'DMR',451000000," +
                "'{\"decodeConfiguration\":{\"channelMode\":\"CONVENTIONAL\"}}')");
            execute(connection, "INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms) VALUES(" +
                id + ",'" + configuration + "',1000,1000)");
        }
        execute(connection, "INSERT INTO conventional_activity_summary(channel_id,frequency_hz,first_seen_ms,last_seen_ms," +
            "call_count,encrypted_count,recorded_count,streamed_count,grant_count) " +
            "VALUES(1,451000000,1000,1000,5,1,2,3,7),(2,451000000,1000,1000,5,1,2,3,7)");
        execute(connection, "WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<" + radios + ") " +
            "INSERT INTO dmr_conventional_radio_summary(channel_id,frequency_hz,timeslot,radio_id,first_seen_ms,last_seen_ms,call_count) " +
            "SELECT 1,451000000,1,value,1000,1000,1 FROM n");
        execute(connection, "INSERT INTO dmr_conventional_radio_summary(channel_id,frequency_hz,timeslot,radio_id," +
            "first_seen_ms,last_seen_ms,call_count) VALUES(2,451000000,1,1,1000,1000,1)");
        execute(connection, "INSERT INTO dmr_conventional_talkgroup_summary(channel_id,frequency_hz,timeslot,talkgroup_id," +
            "first_seen_ms,last_seen_ms,call_count) VALUES(1,451000000,1,45,1000,1000,1),(2,451000000,1,45,1000,1000,1)");
        execute(connection, "INSERT INTO conventional_activity_bucket(channel_id,frequency_hz,bucket_start_ms,call_count,grant_count) " +
            "VALUES(1,451000000,0,1,1)");
        execute(connection, "INSERT INTO conventional_call_identity_bucket(channel_id,bucket_start_ms,identity_role_code,identity_kind_code," +
            "identity_id,call_count) VALUES(1,0,2,2,1,1)");
        execute(connection, "INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code) VALUES(1,1000,4),(1,1000,12)");
        execute(connection, "INSERT INTO alias(alias_list_id,name,matcher_type,protocol,value) " +
            "SELECT id,'Known radio','RADIO_ID','DMR',1 FROM alias_list WHERE family='DMR' LIMIT 1");
    }

    private static Connection open() throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        execute(connection, "PRAGMA foreign_keys=ON");
        SdrTrunkDatabaseSchema.create(connection);
        SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
        ReceiverActivitySchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        ActivityTestDatabase.currentOwners(connection);
        return connection;
    }

    private static void finish(Connection connection, StatsDatabaseMaintenanceRequest request) throws Exception
    {
        ReceiverActivityMaintenance.Result result;
        do
        {
            long before = scalar(connection, "SELECT total_changes()");
            result = ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0);
            assertTrue(scalar(connection, "SELECT total_changes()") - before <= 512);
            assertTrue(connection.getAutoCommit());
        }
        while(result == null);
        request.result().complete(result);
        assertEquals(ReceiverActivityMaintenance.DeletionOutcome.DELETED, result.deletionOutcome());
    }

    private static void execute(Connection connection, String sql) throws SQLException
    {
        try(Statement statement=connection.createStatement()) { statement.execute(sql); }
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement=connection.createStatement(); ResultSet rows=statement.executeQuery(sql))
        { return rows.next() ? rows.getLong(1) : 0; }
    }

    private static String text(Connection connection, String sql) throws SQLException
    {
        try(Statement statement=connection.createStatement(); ResultSet rows=statement.executeQuery(sql))
        { return rows.next() ? rows.getString(1) : null; }
    }

    private static String queryPlan(Connection connection, String sql) throws SQLException
    {
        StringBuilder result=new StringBuilder();
        try(Statement statement=connection.createStatement(); ResultSet rows=statement.executeQuery("EXPLAIN QUERY PLAN " + sql))
        { while(rows.next()) result.append(rows.getString("detail")).append('\n'); }
        return result.toString();
    }
}
