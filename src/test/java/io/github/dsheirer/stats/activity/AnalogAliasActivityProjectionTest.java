package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.alias.AnalogCallAttribution;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AnalogAliasActivityProjectionTest
{
    private static final String CHANNEL = "223e4567-e89b-42d3-a456-426614174000";
    @TempDir Path temporary;

    @Test
    void queuedCallAndOutputsKeepTheirOriginalListAfterChannelReassignment() throws Exception
    {
        try(Connection connection = open(); Statement statement = connection.createStatement())
        {
            AnalogCallAttribution captured = attribution(1, Protocol.NBFM, 7,
                new AnalogCallAttribution.Match(10, "TALKGROUP", 7, 7));
            statement.executeUpdate("UPDATE configuration_channel SET alias_list_id=2 WHERE configuration_id='" +
                CHANNEL + "'");
            AnalogAliasActivityProjection.recordCall(connection, captured);
            AnalogAliasActivityProjection.recordOutput(connection, captured, ReceiverActivityRecords.CallOutput.RECORDED);
            AnalogAliasActivityProjection.recordOutput(connection, captured, ReceiverActivityRecords.CallOutput.STREAMED);
            assertEquals(1, metric(connection, "alias_activity_summary", "logical_call_count", "alias_id=10"));
            assertEquals(1, metric(connection, "alias_activity_summary", "recorded_logical_call_count", "alias_id=10"));
            assertEquals(1, metric(connection, "alias_activity_summary", "stream_submitted_logical_call_count", "alias_id=10"));
            assertEquals(1, metric(connection, "alias_list_analog_activity_summary", "call_count", "alias_list_id=1"));
            assertEquals(0, metric(connection, "alias_list_analog_activity_summary", "unmatched_call_count", "alias_list_id=1"));
            assertEquals(0, metric(connection, "alias_list_analog_activity_summary", "call_count", "alias_list_id=2"));
        }
    }

    @Test
    void countsUnmatchedAndAmSeparatelyWithoutInventingAliasActivity() throws Exception
    {
        try(Connection connection = open())
        {
            AnalogAliasActivityProjection.recordCall(connection, attribution(1, Protocol.NBFM, 8));
            AnalogAliasActivityProjection.recordCall(connection, attribution(1, Protocol.AM, 8));
            assertEquals(1, metric(connection, "alias_list_analog_activity_summary", "call_count", "protocol_code=10"));
            assertEquals(1, metric(connection, "alias_list_analog_activity_summary", "call_count", "protocol_code=11"));
            assertEquals(2, metric(connection, "alias_list_analog_activity_summary", "unmatched_call_count", "1=1"));
            assertEquals(0, metric(connection, "alias_activity_summary", "logical_call_count", "1=1"));
        }
    }

    @Test
    void matcherChangesAndStatisticsResetsRejectDelayedAliasCredits() throws Exception
    {
        try(Connection connection = open(); Statement statement = connection.createStatement())
        {
            var captured = attribution(1, Protocol.NBFM, 7,
                new AnalogCallAttribution.Match(10, "TALKGROUP", 7, 7));
            AnalogAliasActivityProjection.recordCall(connection, captured);
            statement.executeUpdate("UPDATE alias SET value=9 WHERE id=10");
            AnalogAliasActivityProjection.recordOutput(connection, captured, ReceiverActivityRecords.CallOutput.RECORDED);
            assertEquals(0, metric(connection, "alias_activity_summary", "recorded_logical_call_count", "alias_id=10"));
            statement.executeUpdate("UPDATE alias SET value=7 WHERE id=10");
            statement.executeUpdate("UPDATE alias_activity_summary SET last_reset_ms=2000 WHERE alias_id=10");
            AnalogAliasActivityProjection.recordOutput(connection, captured, ReceiverActivityRecords.CallOutput.STREAMED);
            assertEquals(0, metric(connection, "alias_activity_summary", "stream_submitted_logical_call_count", "alias_id=10"));
            SdrTrunkDatabaseStartup.setMetadata(connection,
                ReceiverActivitySchema.CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY, "2000");
            long recorded = metric(connection, "alias_list_analog_activity_summary", "recorded_count", "1=1");
            AnalogAliasActivityProjection.recordOutput(connection, captured, ReceiverActivityRecords.CallOutput.RECORDED);
            assertEquals(recorded, metric(connection, "alias_list_analog_activity_summary", "recorded_count", "1=1"));
        }
    }

    @Test
    void channelResetAndInvalidSavedIdsCannotRecreateActivity() throws Exception
    {
        try(Connection connection = open(); Statement statement = connection.createStatement())
        {
            var captured = attribution(1, Protocol.NBFM, 7);
            statement.executeUpdate("INSERT INTO receiver_channel_statistics_reset(configuration_id,reset_at_ms) VALUES('" + CHANNEL + "',2000)");
            AnalogAliasActivityProjection.recordOutput(connection, captured, ReceiverActivityRecords.CallOutput.RECORDED);
            AnalogAliasActivityProjection.recordCall(connection, attribution(0, Protocol.NBFM, 7));
            assertEquals(0, metric(connection, "alias_list_analog_activity_summary", "call_count", "1=1"));
            assertEquals(0, metric(connection, "alias_list_analog_activity_summary", "recorded_count", "1=1"));
        }
    }

    @Test
    void outputBeforeRawCallKeepsIndependentEvidenceWithoutAddingACall() throws Exception
    {
        try(Connection connection = open(); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM receiver_channel");
            var captured = attribution(1, Protocol.NBFM, 7,
                new AnalogCallAttribution.Match(10, "TALKGROUP", 7, 7));
            var output = new ReceiverActivityRecords.ConventionalCallOutput(captured.startedAt(), CHANNEL,
                ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_ANALOG, "NBFM", 461125000L, null, 7,
                "TALKGROUP", List.of(), null, ReceiverActivityRecords.CallOutput.RECORDED,
                io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain.STANDARD,
                ReceiverActivityRecords.P25Identity.UNKNOWN, ReceiverActivityRecords.P25Identity.UNKNOWN,
                List.of(), null, null, captured);
            assertTrue(ReceiverActivitySchema.applyConventionalCallOutput(connection, output));
            assertEquals(0, metric(connection, "alias_list_analog_activity_summary", "call_count", "1=1"));
            assertEquals(1, metric(connection, "alias_list_analog_activity_summary", "recorded_count", "1=1"));
            assertEquals(0, metric(connection, "alias_activity_summary", "logical_call_count", "alias_id=10"));
            ReceiverActivitySchema.recordActivity(connection, mappedCall(captured), false);
            assertEquals(1, metric(connection, "alias_list_analog_activity_summary", "call_count", "1=1"));
            assertEquals(1, metric(connection, "alias_list_analog_activity_summary", "recorded_count", "1=1"));
            assertEquals(1, metric(connection, "alias_activity_summary", "logical_call_count", "alias_id=10"));
            assertEquals(1, metric(connection, "alias_activity_summary", "recorded_logical_call_count", "alias_id=10"));
        }
    }

    @Test
    void realChannelClearRejectsOldRawCallsAndOutputsBeforeAndAfterANewCall() throws Exception
    {
        try(Connection connection = open(); Statement statement = connection.createStatement())
        {
            var old = attribution(1, Protocol.NBFM, 7,
                new AnalogCallAttribution.Match(10, "TALKGROUP", 7, 7));
            ReceiverActivitySchema.recordActivity(connection, mappedCall(old), false);
            ReceiverActivityMaintenance.clearChannelStats(connection, null, CHANNEL);
            ReceiverActivitySchema.recordActivity(connection, mappedCall(old), false);
            AnalogAliasActivityProjection.recordOutput(connection, old, ReceiverActivityRecords.CallOutput.RECORDED);
            assertEquals(0, metric(connection, "alias_list_analog_activity_summary", "call_count", "1=1"));
            assertEquals(0, metric(connection, "receiver_channel", "id", "1=1"));

            long reset = metric(connection, "receiver_channel_statistics_reset", "reset_at_ms", "1=1");
            var fresh = new AnalogCallAttribution(CHANNEL, 1, Protocol.NBFM, 7, reset + 1, old.matches());
            ReceiverActivitySchema.recordActivity(connection, mappedCall(fresh), false);
            ReceiverActivitySchema.recordActivity(connection, mappedCall(old), false);
            AnalogAliasActivityProjection.recordOutput(connection, old, ReceiverActivityRecords.CallOutput.RECORDED);
            assertEquals(1, metric(connection, "alias_list_analog_activity_summary", "call_count", "1=1"));
            assertEquals(0, metric(connection, "alias_list_analog_activity_summary", "recorded_count", "1=1"));
            assertEquals(reset + 1, metric(connection, "receiver_channel", "first_seen_ms", "1=1"));
            assertEquals(2, metric(connection, "alias_activity_summary", "logical_call_count", "alias_id=10"),
                "Channel clears preserve prior Alias totals; only the new call adds a credit");
        }
    }

    @Test
    void globalResetRejectsOldRawCallsAndOutputs() throws Exception
    {
        try(Connection connection = open())
        {
            var old = attribution(1, Protocol.NBFM, 7);
            ReceiverActivitySchema.recordActivity(connection, mappedCall(old), false);
            ReceiverActivityMaintenance.run(connection, null, 30, ReceiverActivityMaintenance.Operation.RESET_STATS);
            ReceiverActivitySchema.recordActivity(connection, mappedCall(old), false);
            AnalogAliasActivityProjection.recordOutput(connection, old, ReceiverActivityRecords.CallOutput.RECORDED);
            assertEquals(0, metric(connection, "alias_list_analog_activity_summary", "call_count", "1=1"));
            assertEquals(0, metric(connection, "receiver_channel", "id", "1=1"));
        }
    }

    @Test
    void changingDecoderBeforeChannelClearCannotLetAnOldOutputRecreateAnalogActivity() throws Exception
    {
        try(Connection connection = open(); Statement statement = connection.createStatement())
        {
            var old = attribution(1, Protocol.NBFM, 7,
                new AnalogCallAttribution.Match(10, "TALKGROUP", 7, 7));
            ReceiverActivitySchema.recordActivity(connection, mappedCall(old), false);
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(3,'P25','P25')");
            statement.executeUpdate("UPDATE configuration_channel SET decoder_type='P25_CONVENTIONAL',alias_list_id=3");
            ReceiverActivityMaintenance.clearChannelStats(connection, null, CHANNEL);
            var output = new ReceiverActivityRecords.ConventionalCallOutput(old.startedAt(), CHANNEL,
                ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_ANALOG, "NBFM", 461125000L, null, 7,
                "TALKGROUP", List.of(), null, ReceiverActivityRecords.CallOutput.RECORDED,
                io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain.STANDARD,
                ReceiverActivityRecords.P25Identity.UNKNOWN, ReceiverActivityRecords.P25Identity.UNKNOWN,
                List.of(), null, null, old);
            assertFalse(ReceiverActivitySchema.applyConventionalCallOutput(connection, output));
            assertEquals(0, metric(connection, "alias_list_analog_activity_summary", "recorded_count", "1=1"));
            assertEquals(0, metric(connection, "alias_list_analog_activity_summary", "call_count", "1=1"));
            assertTrue(metric(connection, "receiver_channel_statistics_reset", "reset_at_ms", "1=1") > old.startedAt());
        }
    }

    private static ReceiverActivityRecords.ActivityEvent mappedCall(AnalogCallAttribution attribution)
    {
        var channel = new io.github.dsheirer.controller.channel.Channel("Repeater");
        channel.setConfigurationId(CHANNEL);
        channel.setDecodeConfiguration(new io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM());
        var event = new io.github.dsheirer.module.decode.analog.AnalogCallEvent(attribution.startedAt(), attribution);
        event.setProtocol(attribution.protocol());
        event.setChannelDescriptor(new io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel(461125000));
        event.setIdentifierCollection(new io.github.dsheirer.identifier.IdentifierCollection(List.of(
            new io.github.dsheirer.module.decode.nbfm.NBFMTalkgroup(attribution.assignedTalkgroup()))));
        return new ReceiverActivityMapper().map(channel, event);
    }

    private Connection open() throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + temporary.resolve("activity.sqlite"));
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseSchema.create(connection);
            ReceiverActivitySchema.create(connection);
            statement.execute("PRAGMA foreign_keys=OFF");
            io.github.dsheirer.database.ConfigurationHistorySchema.separateHistoryOwners(connection);
            statement.execute("PRAGMA foreign_keys=ON");
            SdrTrunkDatabaseStartup.setMetadata(connection,
                ReceiverActivitySchema.CONVENTIONAL_CALL_OUTPUT_METRICS_STARTED_AT_KEY, "1");
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES(1,'Analog One','NBFM'),(2,'Analog Two','NBFM')");
            statement.executeUpdate("INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value) VALUES(10,1,'Repeater','TALKGROUP','NBFM',7)");
            statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,name,alias_list_id,
                    decoder_type,address_domain_code,primary_frequency_hz,config_json)
                VALUES('%s','CONVENTIONAL',0,'Repeater',1,'NBFM',0,461125000,'{}')
                """.formatted(CHANNEL));
            statement.executeUpdate("INSERT INTO receiver_channel(configuration_id,first_seen_ms,last_seen_ms) VALUES('" +
                CHANNEL + "',1000,1000)");
        }
        return connection;
    }

    private static AnalogCallAttribution attribution(long list, Protocol protocol, int number,
                                                     AnalogCallAttribution.Match... matches)
    {
        return new AnalogCallAttribution(CHANNEL, list, protocol, number, 1000, List.of(matches));
    }

    private static long metric(Connection connection, String table, String column, String where) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
            "SELECT coalesce(sum(" + column + "),0) FROM " + table + " WHERE " + where))
        {
            return result.next() ? result.getLong(1) : 0;
        }
    }
}
