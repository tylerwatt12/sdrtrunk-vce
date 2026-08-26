/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P25ActivityLogWriterTest
{
    private static final long START = 1_700_000_000_000L;

    @TempDir
    Path mTemporaryFolder;

    @Test
    void writesDetailedSignalingWithoutCreatingLogicalCallCounters() throws Exception
    {
        Path database = database("signaling.sqlite");
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, true, 10, 1, 0);
        writer.start();
        writer.enqueue(signaling(P25ActivityLogRecords.Action.GRANT));
        writer.close();

        assertEquals(P25ActivityLogStatus.State.STOPPED, writer.getStatus().state());
        assertEquals(1, writer.getWrittenRecords());
        try(Connection connection = open(database))
        {
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM p25_activity_event"));
            assertEquals(1, scalar(connection,
                "SELECT grant_count FROM trunked_signaling_activity_bucket"));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM trunked_logical_call_bucket"));
        }
    }

    @Test
    void writesResolvedLogicalCallThenItsOutputsExactlyOnce() throws Exception
    {
        Path database = database("resolved-output.sqlite");
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, false, 10, 3, 0);
        P25ActivityLogRecords.ResolvedLogicalCall call = logicalCall(1);
        writer.start();
        writer.enqueue(call);
        writer.enqueue(new P25ActivityLogRecords.LogicalCallOutput(call,
            P25ActivityLogRecords.CallOutput.RECORDED));
        writer.enqueue(new P25ActivityLogRecords.LogicalCallOutput(call,
            P25ActivityLogRecords.CallOutput.STREAMED));
        writer.close();

        try(Connection connection = open(database))
        {
            assertEquals(1, scalar(connection,
                "SELECT logical_call_count FROM trunked_logical_call_bucket"));
            assertEquals(1, scalar(connection,
                "SELECT recorded_output_count FROM trunked_logical_call_bucket"));
            assertEquals(1, scalar(connection,
                "SELECT streamed_output_count FROM trunked_logical_call_bucket"));
            assertEquals(2, scalar(connection,
                "SELECT COUNT(*) FROM p25_site_call_bucket"));
        }
    }

    @Test
    void rejectsOutputThatArrivesBeforeItsResolvedCall() throws Exception
    {
        Path database = database("output-order.sqlite");
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, false, 10, 2, 0);
        P25ActivityLogRecords.ResolvedLogicalCall call = logicalCall(2);
        writer.start();
        writer.enqueue(new P25ActivityLogRecords.LogicalCallOutput(call,
            P25ActivityLogRecords.CallOutput.RECORDED));
        writer.enqueue(call);
        writer.close();

        try(Connection connection = open(database))
        {
            assertEquals(1, scalar(connection,
                "SELECT logical_call_count FROM trunked_logical_call_bucket"));
            assertEquals(0, scalar(connection,
                "SELECT recorded_output_count FROM trunked_logical_call_bucket"));
        }
    }

    @Test
    void duplicateResolvedNotificationDoesNotIncrementAgainWithinWriterLifetime() throws Exception
    {
        Path database = database("resolved-idempotency.sqlite");
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, false, 10, 2, 0);
        P25ActivityLogRecords.ResolvedLogicalCall call = logicalCall(3);
        writer.start();
        writer.enqueue(call);
        writer.enqueue(call);
        writer.close();

        try(Connection connection = open(database))
        {
            assertEquals(1, scalar(connection,
                "SELECT logical_call_count FROM trunked_logical_call_bucket"));
            assertEquals(2, scalar(connection,
                "SELECT SUM(observed_call_count) FROM p25_site_call_bucket"));
        }
    }

    @Test
    void closeDrainsAcceptedPartialBatch() throws Exception
    {
        Path database = database("close-drain.sqlite");
        P25ActivityLogWriter writer = new P25ActivityLogWriter(database, 30, true, 10, 100,
            TimeUnit.SECONDS.toMillis(10));
        writer.start();
        writer.enqueue(signaling(P25ActivityLogRecords.Action.DENIAL));
        writer.close();

        try(Connection connection = open(database))
        {
            assertEquals(1, scalar(connection,
                "SELECT denial_count FROM trunked_signaling_activity_bucket"));
        }
    }

    private Path database(String name) throws Exception
    {
        Path database = mTemporaryFolder.resolve(name);
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        return database;
    }

    private static Connection open(Path database) throws Exception
    {
        return DriverManager.getConnection("jdbc:sqlite:" + database);
    }

    private static P25ActivityLogRecords.ResolvedLogicalCall logicalCall(long sequence)
    {
        return new P25ActivityLogRecords.ResolvedLogicalCall(new LogicalCallId(77, sequence), START + sequence,
            "GUID:winner", "winner", Protocol.APCO25.name(), P25ActivityLogRecords.IdentityDomain.STANDARD,
            0x924, 0x649, 17, 1201, Form.TALKGROUP.name(), List.of(), 700001, true,
            0x84, 1, P25ActivityLogRecords.P25TargetIdentity.ORDINARY, List.of(),
            List.of(new P25SiteIdentity(0x924, 0x649, 1, 1),
                new P25SiteIdentity(0x924, 0x649, 2, 2)));
    }

    private static P25ActivityLogRecords.ActivityEvent signaling(P25ActivityLogRecords.Action action)
    {
        return new P25ActivityLogRecords.ActivityEvent(START, "GUID:signaling", "signaling",
            P25ActivityLogRecords.ContextKind.TRUNKED_SITE, Protocol.DMR.name(), action,
            DecodeEventName.forAction(action), "101", "91", Form.TALKGROUP.name(), List.of(),
            451_012_500L, "12", 1, false, null, null, null, null, null, null, null,
            "DMR Site", "DMR", null, false, null, null, P25ActivityLogRecords.IdentityDomain.STANDARD,
            P25ActivityLogRecords.P25TargetIdentity.UNKNOWN, List.of(), "DMR", true);
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static final class DecodeEventName
    {
        private DecodeEventName()
        {
        }

        private static String forAction(P25ActivityLogRecords.Action action)
        {
            return action == P25ActivityLogRecords.Action.DENIAL ? "DENIAL" : "CALL_GROUP";
        }
    }
}
