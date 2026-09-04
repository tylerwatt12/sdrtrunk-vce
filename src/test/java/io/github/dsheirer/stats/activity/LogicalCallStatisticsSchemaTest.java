/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compact logical-call accounting tests using only current saved-channel and radio-system identity. */
class LogicalCallStatisticsSchemaTest
{
    private static final long CALL_START = 1_700_000_000_000L;
    private static final String CHANNEL_A = "11111111-1111-4111-8111-111111111111";
    private static final String CHANNEL_B = "22222222-2222-4222-8222-222222222222";

    @TempDir
    Path mTemporaryFolder;

    @Test
    void countsOneSystemCallItsOutputsAndEachDistinctLearnedSite() throws Exception
    {
        try(Connection connection = open("logical-call.sqlite"))
        {
            insertChannel(connection, CHANNEL_A);
            ReceiverActivityRecords.ResolvedLogicalCall call = call(CHANNEL_A, 1, 0x924, 0x649,
                List.of(site(1, 1), site(2, 2), site(1, 1)));

            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection, call));
            assertEquals(1, scalar(connection,
                "SELECT logical_call_count FROM trunked_logical_call_bucket"));
            assertEquals(1, scalar(connection,
                "SELECT encrypted_logical_call_count FROM trunked_logical_call_bucket"));
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM p25_site_call_bucket"));
            assertEquals(2, scalar(connection,
                "SELECT SUM(observed_call_count) FROM p25_site_call_bucket"));
            assertEquals(1, scalar(connection, """
                SELECT logical_call_count FROM trunked_logical_call_identity_bucket
                WHERE identity_role_code=1 AND identity_kind_code=1 AND identity_id=1201
                """));

            assertTrue(ReceiverActivitySchema.applyLogicalCallOutput(connection,
                new ReceiverActivityRecords.LogicalCallOutput(call, ReceiverActivityRecords.CallOutput.RECORDED)));
            assertTrue(ReceiverActivitySchema.applyLogicalCallOutput(connection,
                new ReceiverActivityRecords.LogicalCallOutput(call, ReceiverActivityRecords.CallOutput.STREAMED)));
            assertEquals("1|1|1", text(connection, """
                SELECT logical_call_count || '|' || recorded_output_count || '|' || streamed_output_count
                FROM trunked_logical_call_bucket
                """));
            assertEquals("1|1|1", text(connection, """
                SELECT logical_call_count || '|' || recorded_output_count || '|' || streamed_output_count
                FROM radio_system_identity_summary
                WHERE identity_kind_code=1 AND identity_id=1201
                """));
        }
    }

    @Test
    void nativeP25CallsShareASystemWhileUnresolvedCallsStayChannelScoped() throws Exception
    {
        try(Connection connection = open("identity.sqlite"))
        {
            insertChannel(connection, CHANNEL_A);
            insertChannel(connection, CHANNEL_B);
            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection,
                call(CHANNEL_A, 1, 0xBEE00, 0x3A9, List.of())));
            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection,
                call(CHANNEL_B, 2, 0xBEE00, 0x3A9, List.of())));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM radio_system"));
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM receiver_channel"));

            //Remove the shared derived rows so each channel can be characterized independently as provisional.
            execute(connection, "DELETE FROM receiver_channel");
            execute(connection, "DELETE FROM radio_system");
            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection,
                call(CHANNEL_A, 3, null, null, List.of())));
            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection,
                call(CHANNEL_B, 4, null, null, List.of())));
            assertEquals(2, scalar(connection, "SELECT COUNT(*) FROM radio_system"));
            assertEquals("p25:channel:" + CHANNEL_A + "|p25:channel:" + CHANNEL_B,
                text(connection, """
                    SELECT group_concat(system_key, '|') FROM (SELECT system_key FROM radio_system ORDER BY system_key)
                    """));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_site_call_bucket"));
        }
    }

    @Test
    void prunesHourlyFactsAndUsesTimeLeadingIndexes() throws Exception
    {
        try(Connection connection = open("retention.sqlite"))
        {
            insertChannel(connection, CHANNEL_A);
            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection,
                call(CHANNEL_A, 1, 0x924, 0x649, List.of(site(1, 1), site(2, 2)))));

            assertTrue(queryPlan(connection, """
                SELECT radio_system_id, logical_call_count
                FROM trunked_logical_call_bucket
                WHERE bucket_start_ms BETWEEN ? AND ? ORDER BY bucket_start_ms
                """, CALL_START - 1, CALL_START + 3_600_000L)
                .contains("idx_trunked_logical_call_bucket_time"));
            assertTrue(queryPlan(connection, """
                SELECT learned_site_id, observed_call_count
                FROM p25_site_call_bucket
                WHERE bucket_start_ms BETWEEN ? AND ? ORDER BY bucket_start_ms
                """, CALL_START - 1, CALL_START + 3_600_000L)
                .contains("idx_p25_site_call_bucket_time"));

            assertTrue(ReceiverActivitySchema.deleteOlderThan(connection, CALL_START + 3_600_001L) > 0);
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_logical_call_bucket"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_site_call_bucket"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_learned_site"));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void cleanSchemaExposesNoRetiredCallIdentityObjects() throws Exception
    {
        try(Connection connection = open("clean-shape.sqlite"))
        {
            for(String removed: List.of("receiver_context", "trunked_identity_scope",
                "trunked_identity_scope_context", "trunked_identity_summary", "call_identity_bucket"))
            {
                assertEquals(0, scalar(connection,
                    "SELECT COUNT(*) FROM sqlite_master WHERE name='" + removed + "'"));
            }
            for(String table: List.of("radio_system_identity_summary", "trunked_radio_talkgroup_summary",
                "trunked_logical_call_bucket", "p25_site_call_bucket"))
            {
                for(String removedColumn: List.of("call_count", "encrypted_count", "recorded_count",
                    "streamed_count", "scope_id", "context_id"))
                {
                    assertFalse(hasColumn(connection, table, removedColumn), table + "." + removedColumn);
                }
            }
            assertTrue(hasColumn(connection, "trunked_logical_call_bucket", "logical_call_count"));
            assertTrue(hasColumn(connection, "p25_site_call_bucket", "observed_call_count"));
        }
    }

    private Connection open(String name) throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + mTemporaryFolder.resolve(name));
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        SdrTrunkDatabaseSchema.create(connection);
        ReceiverActivitySchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        return connection;
    }

    private static void insertChannel(Connection connection, String configurationId) throws Exception
    {
        execute(connection, """
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name,
                radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json)
            VALUES ('%s', 'TRUNKED', 0, 'P25', 'Site', 'Control',
                '%s', 0, 'P25_PHASE1', 851012500, '{}')
            """.formatted(configurationId, configurationId));
    }

    private static ReceiverActivityRecords.ResolvedLogicalCall call(String configurationId, long sequence,
                                                                     Integer wacn, Integer systemId,
                                                                     List<P25SiteIdentity> sites)
    {
        return new ReceiverActivityRecords.ResolvedLogicalCall(new LogicalCallId(9, sequence),
            CALL_START + sequence, configurationId, Protocol.APCO25.name(),
            ReceiverActivityRecords.IdentityDomain.STANDARD, wacn, systemId, 1201, Form.TALKGROUP.name(),
            List.of(), 700001, true, 0x84, 1, ReceiverActivityRecords.P25TargetIdentity.ORDINARY,
            List.of(), sites);
    }

    private static P25SiteIdentity site(int rfss, int site)
    {
        return new P25SiteIdentity(0x924, 0x649, rfss, site);
    }

    private static void execute(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static String text(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static boolean hasColumn(Connection connection, String table, String column) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            while(resultSet.next())
            {
                if(column.equals(resultSet.getString("name")))
                {
                    return true;
                }
            }
            return false;
        }
    }

    private static String queryPlan(Connection connection, String sql, long start, long end) throws Exception
    {
        StringBuilder plan = new StringBuilder();
        try(PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql))
        {
            statement.setLong(1, start);
            statement.setLong(2, end);
            try(ResultSet resultSet = statement.executeQuery())
            {
                while(resultSet.next())
                {
                    plan.append(resultSet.getString("detail"));
                }
            }
        }
        return plan.toString();
    }
}
