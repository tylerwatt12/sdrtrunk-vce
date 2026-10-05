/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.ScopedData;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiverActivityMemberChannelDeletionTest
{
    private static final String CHANNEL = "123e4567-e89b-42d3-a456-426614174000";
    private static final String SYSTEM = "p25:abcde:123";

    @Test
    void twoParentForeignKeysCountAndDeleteOneMemberOnlyOnce() throws Exception
    {
        try(Connection connection = open())
        {
            seed(connection,1);
            assertEquals(2,ReceiverActivityMaintenance.preview(connection,target()).rowsTotal());
            StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.delete(target());
            ReceiverActivityMaintenance.Result result = finish(connection,request);
            assertEquals(2,result.rowsDeleted());
            assertEquals(2,request.progress().rowsTotal());
            assertEquals(0,scalar(connection,"SELECT count(*) FROM receiver_activity_event"));
            assertEquals(0,scalar(connection,"SELECT count(*) FROM activity_event_identity_member"));
            assertEquals(1,scalar(connection,"SELECT count(*) FROM receiver_channel"));
            assertEquals(0,scalar(connection,"SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void duplicateCascadePathsStayBoundedAndPreserveMembersAddedAfterTheCutoff() throws Exception
    {
        try(Connection connection = open())
        {
            seed(connection,1200);
            StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.delete(target());
            long before = scalar(connection,"SELECT total_changes()");
            assertNull(ReceiverActivityMaintenance.deleteRetainedStatsPass(connection,null,request,0));
            assertTrue(scalar(connection,"SELECT total_changes()")-before<=512);
            long later = request.progress().cutoffMs()+1;
            execute(connection,"INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code) " +
                "VALUES(3000,1,1,"+later+",4)");
            execute(connection,"INSERT INTO activity_event_identity_member VALUES(3000,1,1,1,45,1)");
            ReceiverActivityMaintenance.Result result = finish(connection,request);
            assertEquals(2400,result.rowsDeleted());
            assertEquals(1,scalar(connection,"SELECT count(*) FROM receiver_activity_event"));
            assertEquals(later,scalar(connection,"SELECT observed_at_ms FROM receiver_activity_event"));
            assertEquals(1,scalar(connection,"SELECT count(*) FROM activity_event_identity_member WHERE event_id=3000"));
            assertEquals(0,scalar(connection,"SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    private static ReceiverActivityMaintenance.Result finish(Connection connection,
        StatsDatabaseMaintenanceRequest request) throws Exception
    {
        ReceiverActivityMaintenance.Result result;
        int passes = 0;
        do
        {
            long before = scalar(connection,"SELECT total_changes()");
            result = ReceiverActivityMaintenance.deleteRetainedStatsPass(connection,null,request,0);
            assertTrue(scalar(connection,"SELECT total_changes()")-before<=512);
            assertTrue(++passes<20);
        }
        while(result==null);
        assertEquals(ReceiverActivityMaintenance.DeletionOutcome.DELETED,result.deletionOutcome());
        return result;
    }

    private static ScopedData target()
    {
        return new ScopedData("radio_system",SYSTEM,null,null,"detailed_events",null,List.of("events"));
    }

    private static Connection open() throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        execute(connection,"PRAGMA foreign_keys=ON");
        SdrTrunkDatabaseSchema.create(connection);
        SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
        ReceiverActivitySchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        execute(connection,"INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,name," +
            "alias_list_id,radioresolve_id,decoder_type,primary_frequency_hz,config_json) VALUES('"+CHANNEL+
            "','TRUNKED',0,'Site',(SELECT id FROM alias_list WHERE family='P25' LIMIT 1)," +
            "'aaaaaaaa-aaaa-4aaa-8aaa-000000000001','P25_PHASE1',851000000,'{}')");
        execute(connection,"INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms) " +
            "VALUES(1,'"+SYSTEM+"',1,703710,291,1000,1000)");
        execute(connection,"INSERT INTO receiver_channel(id,configuration_id,radio_system_id,radio_system_assigned_at_ms," +
            "first_seen_ms,last_seen_ms) VALUES(1,'"+CHANNEL+"',1,1000,1000,1000)");
        execute(connection,"INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code,identity_id," +
            "first_seen_ms,last_seen_ms) VALUES(1,1,1,45,1000,1000)");
        return connection;
    }

    private static void seed(Connection connection, int count) throws Exception
    {
        execute(connection,"WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<"+count+
            ") INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code) " +
            "SELECT value,1,1,1000+value,4 FROM n");
        execute(connection,"INSERT INTO activity_event_identity_member SELECT id,1,1,1,45,channel_id FROM receiver_activity_event");
    }

    private static void execute(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            return rows.next() ? rows.getLong(1) : 0;
        }
    }
}
