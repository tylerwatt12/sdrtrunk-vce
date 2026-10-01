/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.ScopedData;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ReceiverActivityBatchedDeletionTest
{
    private static final String CHANNEL = "123e4567-e89b-42d3-a456-426614174000";
    private static final String OTHER = "223e4567-e89b-42d3-a456-426614174000";
    private static final String SYSTEM = "p25:abcde:123";
    private static final String SITE = RetainedSiteKey.p25(1, 2, 1, 1, 1000);

    @Test
    void moreThan100000QualityRowsDeleteInSmallCommittedPassesAndKeepOtherSitesAndActiveBucket() throws Exception
    {
        try(Connection connection = open())
        {
            seedQuality(connection, 100_002);
            long current = System.currentTimeMillis() / 10_000 * 10_000;
            execute(connection, "INSERT INTO trunked_control_channel_quality(channel_id,frequency_hz,bucket_start_ms,observed_at_ms) " +
                "VALUES(2,851000000,0,1000),(1,851000000," + current + "," + (current + 1) + ")");
            StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.delete(quality());
            ReceiverActivityMaintenance.Result result = null;
            int passes = 0;
            long deleted = 0;
            do
            {
                long before = scalar(connection, "SELECT total_changes()");
                result = ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0);
                long changed = scalar(connection, "SELECT total_changes()") - before;
                assertTrue(changed <= ReceiverActivityScopedDeletion.Batch.MAXIMUM_ROWS_PER_PASS);
                deleted += changed;
                passes++;
                assertTrue(connection.getAutoCommit());
            }
            while(result == null);
            assertTrue(passes > 100);
            assertEquals(100_002, deleted);
            assertEquals(100_002, result.rowsDeleted());
            assertEquals(100_002, request.progress().rowsTotal());
            assertEquals(0, request.progress().rowsRetained());
            assertEquals(2, scalar(connection, "SELECT count(*) FROM trunked_control_channel_quality"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM configuration_channel"));
            assertEquals("ok", text(connection, "PRAGMA quick_check"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void legacyAtomicTargetsKeepTheirSafetyCap() throws Exception
    {
        try(Connection connection = open())
        {
            seedQuality(connection, 100_002);
            ReceiverActivityMaintenance.Result rejected = ReceiverActivityMaintenance.deleteRetainedStats(connection, null,
                new StatsDatabaseMaintenanceRequest.Channel(CHANNEL));
            assertEquals(ReceiverActivityMaintenance.DeletionOutcome.TOO_LARGE, rejected.deletionOutcome());
            assertEquals(100_002, scalar(connection, "SELECT count(*) FROM trunked_control_channel_quality"));
            assertTrue(connection.getAutoCommit());
        }
    }

    @Test
    void largeIdentityCascadeNeverEscapesBatchBoundAndNeverDeletesNewDescendants() throws Exception
    {
        try(Connection connection = open())
        {
            execute(connection, "INSERT INTO radio_system_identity_summary" +
                "(id,radio_system_id,identity_kind_code,identity_id,first_seen_ms,last_seen_ms) VALUES(1,1,2,321,1000,1000)");
            execute(connection, "WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<100001) " +
                "INSERT INTO receiver_activity_event(channel_id,radio_system_id,observed_at_ms,action_code,source_identity_summary_id) " +
                "SELECT 1,1,value,4,1 FROM n");
            execute(connection, "INSERT INTO radio_system_identity_summary" +
                "(id,radio_system_id,identity_kind_code,identity_id,first_seen_ms,last_seen_ms) VALUES(2,1,1,45,1000,1000)");
            execute(connection, "INSERT INTO activity_event_identity_member(event_id,radio_system_id,identity_summary_id," +
                "identity_kind_code) VALUES(1,1,2,1)");
            ScopedData target = new ScopedData("radio_system", SYSTEM, null, null, "radios", "v1-r-x-x-321", List.of("summary"));
            StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.delete(target);
            assertNull(ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0));
            long later = request.progress().cutoffMs() + 1;
            execute(connection, "INSERT INTO receiver_activity_event(channel_id,radio_system_id,observed_at_ms,action_code,source_identity_summary_id) " +
                "VALUES(1,1," + later + ",4,1)");
            ReceiverActivityMaintenance.Result result;
            do
            {
                long before = scalar(connection, "SELECT total_changes()");
                result = ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0);
                assertTrue(scalar(connection, "SELECT total_changes()") - before <= 512,
                    "FK cascades must not turn one small pass into an unbounded write");
            }
            while(result == null);
            assertEquals(100_002, request.progress().rowsDeleted());
            assertEquals(1, request.progress().rowsRetained());
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(later, scalar(connection, "SELECT observed_at_ms FROM receiver_activity_event"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM activity_event_identity_member"));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        }
    }

    @Test
    void cancelAndResumeKeepCommittedProgressAndOriginalCutoff() throws Exception
    {
        try(Connection connection = open())
        {
            seedQuality(connection, 1500);
            StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.delete(quality());
            assertNull(ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 5));
            assertEquals(512, request.progress().rowsDeleted());
            long cutoff = request.progress().cutoffMs();
            request.cancel();
            ReceiverActivityMaintenance.Result interrupted = ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 5);
            request.result().complete(interrupted);
            assertEquals(ReceiverActivityMaintenance.DeletionOutcome.INTERRUPTED, interrupted.deletionOutcome());
            assertEquals(988, scalar(connection, "SELECT count(*) FROM trunked_control_channel_quality"));
            StatsDatabaseMaintenanceRequest rejected = request.resume();
            assertThrows(IllegalStateException.class, request::resume);
            rejected.result().completeExceptionally(new IllegalStateException("Dispatcher unavailable"));
            StatsDatabaseMaintenanceRequest continuation = rejected.resume();
            finish(connection, continuation);
            assertEquals(cutoff, continuation.progress().cutoffMs());
            assertEquals(1500, continuation.progress().rowsDeleted());
            assertFalse(continuation.progress().resumable());
            assertEquals(0, scalar(connection, "SELECT count(*) FROM trunked_control_channel_quality"));
        }
    }

    @Test
    void commitFailureRollsBackRowsAndCursorSoContinuationDoesNotSkipWork() throws Exception
    {
        try(Connection real = open())
        {
            seedQuality(real, 1500);
            AtomicBoolean failCommit = new AtomicBoolean(true);
            Connection connection = (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                    if(method.getName().equals("commit") && failCommit.getAndSet(false))
                        throw new SQLException("Simulated commit failure");
                    try { return method.invoke(real, arguments); }
                    catch(InvocationTargetException failure) { throw failure.getCause(); }
                });
            StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.delete(quality());
            SQLException failure = assertThrows(SQLException.class,
                () -> ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0));
            request.result().completeExceptionally(failure);
            assertEquals(0, request.progress().rowsDeleted());
            assertEquals(1500, scalar(real, "SELECT count(*) FROM trunked_control_channel_quality"));
            assertTrue(real.getAutoCommit());
            long cutoff = request.progress().cutoffMs();
            StatsDatabaseMaintenanceRequest continuation = request.resume();
            finish(real, continuation);
            assertEquals(1500, continuation.progress().rowsDeleted());
            assertEquals(cutoff, continuation.progress().cutoffMs());
        }
    }

    @Test
    void changingSiteOwnershipStopsRemainingPasses() throws Exception
    {
        try(Connection connection = open())
        {
            seedQuality(connection, 1500);
            StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.delete(quality());
            assertNull(ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0));
            execute(connection, "UPDATE p25_site_snapshot SET site=3 WHERE channel_id=1");
            ReceiverActivityMaintenance.Result stopped = ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0);
            assertEquals(ReceiverActivityMaintenance.DeletionOutcome.STALE_SITE, stopped.deletionOutcome());
            assertEquals(512, stopped.rowsDeleted());
            assertEquals(988, scalar(connection, "SELECT count(*) FROM trunked_control_channel_quality"));
        }
    }

    @Test
    void selfDeletedSnapshotDoesNotPermitContinuationAfterChannelSystemReassignment() throws Exception
    {
        try(Connection connection = open())
        {
            execute(connection, "WITH RECURSIVE n(value) AS (VALUES(1) UNION ALL SELECT value+1 FROM n WHERE value<511) " +
                "INSERT INTO p25_site_channel(channel_id,channel_key,downlink_hz,confirmed_at_ms) " +
                "SELECT 1,'1-'||value,851000000,1000 FROM n");
            StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.delete(new ScopedData(
                "radio_system", SYSTEM, CHANNEL, SITE, "site_state", null, List.of("current")));
            assertNull(ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0));
            assertEquals(0, scalar(connection, "SELECT count(*) FROM p25_site_snapshot WHERE channel_id=1"));
            execute(connection, "INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms) " +
                "VALUES(2,'p25:abcde:124',1,703710,292,1000,1000)");
            execute(connection, "UPDATE receiver_channel SET radio_system_id=2 WHERE id=1");
            ReceiverActivityMaintenance.Result stopped = ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0);
            assertEquals(ReceiverActivityMaintenance.DeletionOutcome.NOT_FOUND, stopped.deletionOutcome());
            assertEquals(512, stopped.rowsDeleted());
            assertEquals(2, scalar(connection, "SELECT radio_system_id FROM receiver_channel WHERE id=1"));
        }
    }

    @Test
    void historyFiltersAreInclusiveAtStartAndExclusiveAtEndAndKeepLinkedMembersInSync() throws Exception
    {
        try(Connection connection = open())
        {
            seedQuality(connection, 5);
            ScopedData filtered = new ScopedData("radio_system", SYSTEM, CHANNEL, SITE,
                "control_quality", null, List.of("buckets"), 10_100L, 30_100L, 851000000L);
            assertEquals(2, ReceiverActivityMaintenance.preview(connection, filtered).rowsTotal());
            finish(connection, StatsDatabaseMaintenanceRequest.delete(filtered));
            assertEquals(3, scalar(connection, "SELECT count(*) FROM trunked_control_channel_quality"));
            execute(connection, "INSERT INTO radio_system_identity_summary" +
                "(id,radio_system_id,identity_kind_code,identity_id,first_seen_ms,last_seen_ms) VALUES(1,1,2,321,1000,1000)");
            execute(connection, "INSERT INTO receiver_activity_event(id,channel_id,radio_system_id,observed_at_ms,action_code,frequency_hz) " +
                "VALUES(1,1,1,1000,4,851000000),(2,1,1,2000,4,852000000)");
            execute(connection, "INSERT INTO radio_system_identity_summary" +
                "(id,radio_system_id,identity_kind_code,identity_id,first_seen_ms,last_seen_ms) VALUES(2,1,1,45,1000,1000)");
            execute(connection, "INSERT INTO activity_event_identity_member(event_id,radio_system_id,identity_summary_id,identity_kind_code) " +
                "VALUES(1,1,2,1),(2,1,2,1)");
            ScopedData events = new ScopedData("radio_system", SYSTEM, CHANNEL, SITE,
                "detailed_events", null, List.of("events"), 1000L, 2000L, 851000000L);
            assertEquals(2, ReceiverActivityMaintenance.preview(connection, events).rowsTotal());
            finish(connection, StatsDatabaseMaintenanceRequest.delete(events));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM activity_event_identity_member WHERE event_id=2"));
        }
    }

    @Test
    void liveUpdatedSummariesAreReportedAsKeptAndAllCleanupPreservesStableOwners() throws Exception
    {
        try(Connection connection = open())
        {
            seedQuality(connection, 600);
            execute(connection, "INSERT INTO p25_site_channel_summary(channel_id,channel_key,downlink_hz,first_seen_ms,last_seen_ms) " +
                "VALUES(1,'1-10',851000000,1000,1000)");
            StatsDatabaseMaintenanceRequest request = StatsDatabaseMaintenanceRequest.delete(new ScopedData(
                "radio_system", SYSTEM, CHANNEL, SITE, "all", null, List.of("current", "summary", "buckets", "events")));
            assertNull(ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0));
            long later = request.progress().cutoffMs() + 1;
            execute(connection, "UPDATE p25_site_channel_summary SET last_seen_ms=" + later + " WHERE channel_id=1");
            finish(connection, request);
            assertEquals(2, request.progress().rowsRetained(), "Updated summary and its required parent snapshot remain");
            assertEquals(1, scalar(connection, "SELECT count(*) FROM p25_site_channel_summary"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM receiver_channel"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM radio_system"));
            assertEquals(2, scalar(connection, "SELECT count(*) FROM configuration_channel"));
        }
    }

    private static ScopedData quality()
    {
        return new ScopedData("radio_system", SYSTEM, CHANNEL, SITE, "control_quality", null, List.of("buckets"));
    }

    private static void finish(Connection connection, StatsDatabaseMaintenanceRequest request) throws Exception
    {
        ReceiverActivityMaintenance.Result result;
        do { result = ReceiverActivityMaintenance.deleteRetainedStatsPass(connection, null, request, 0); }
        while(result == null);
        request.result().complete(result);
        assertEquals(ReceiverActivityMaintenance.DeletionOutcome.DELETED, result.deletionOutcome());
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
        for(int id = 1; id <= 2; id++)
        {
            String configuration = id == 1 ? CHANNEL : OTHER;
            execute(connection, "INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,name,alias_list_id,radioresolve_id," +
                "decoder_type,primary_frequency_hz,config_json) VALUES('" + configuration + "','TRUNKED'," + (id-1) + ",'Site'," +
                "(SELECT id FROM alias_list WHERE family='P25' LIMIT 1),'aaaaaaaa-aaaa-4aaa-8aaa-00000000000" + id + "','P25_PHASE1',851000000,'{}')");
        }
        execute(connection, "INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms) " +
            "VALUES(1,'" + SYSTEM + "',1,703710,291,1000,1000)");
        execute(connection, "INSERT INTO receiver_channel(id,configuration_id,radio_system_id,radio_system_assigned_at_ms,first_seen_ms,last_seen_ms) " +
            "VALUES(1,'" + CHANNEL + "',1,1000,1000,1000),(2,'" + OTHER + "',1,1000,1000,1000)");
        execute(connection, "INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms,rfss,site) VALUES(1,1000,1000,1,2),(2,1000,1000,1,3)");
        return connection;
    }

    private static void seedQuality(Connection connection, int count) throws Exception
    {
        execute(connection, "WITH RECURSIVE n(value) AS (VALUES(0) UNION ALL SELECT value+1 FROM n WHERE value<" + (count-1) + ") " +
            "INSERT INTO trunked_control_channel_quality(channel_id,frequency_hz,bucket_start_ms,observed_at_ms) " +
            "SELECT 1,851000000,value*10000,value*10000+100 FROM n");
    }

    private static void execute(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        { return rows.next() ? rows.getLong(1) : 0; }
    }

    private static String text(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        { return rows.next() ? rows.getString(1) : null; }
    }
}
