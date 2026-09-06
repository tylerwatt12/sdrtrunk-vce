/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                SELECT bucket.logical_call_count
                FROM trunked_logical_call_identity_bucket bucket
                JOIN radio_system_identity_summary identity
                  ON identity.id=bucket.identity_summary_id
                 AND identity.radio_system_id=bucket.radio_system_id
                WHERE bucket.identity_role_code=1 AND identity.identity_kind_code=1
                  AND identity.identity_id=1201
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
    void keepsAHighP25WorkingAddressBesideThePermanentRadioIdentity() throws Exception
    {
        try(Connection connection = open("roaming-radio.sqlite"))
        {
            insertChannel(connection, CHANNEL_A);
            ReceiverActivityRecords.P25Identity radio =
                ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xBEE00, 0x954, 831_102);
            P25SiteIdentity servingSite = new P25SiteIdentity(0xBEE00, 0x3A9, 1, 1);
            ReceiverActivityRecords.ResolvedLogicalCall call = new ReceiverActivityRecords.ResolvedLogicalCall(
                new LogicalCallId(9, 99), CALL_START, CHANNEL_A, Protocol.APCO25.name(),
                TrunkedIdentityDomain.STANDARD, 0xBEE00, 0x3A9, 1201, Form.TALKGROUP.name(),
                List.of(), 0xFFFD26, false, null, null, ReceiverActivityRecords.P25Identity.ORDINARY,
                radio, List.of(), List.of(new ReceiverActivityRecords.P25SiteCallObservation(CHANNEL_A,
                    servingSite, 0xFFFD26, 1201, Form.TALKGROUP.name(),
                    ReceiverActivityRecords.P25Identity.ORDINARY, radio, List.of(), List.of())), null);

            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection, call));
            assertEquals(1, scalar(connection, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE identity_kind_code=2 AND home_wacn=0xBEE00 AND home_system_id=0x954
                  AND identity_id=831102
                """));
            assertEquals(0xFFFD26, scalar(connection, """
                SELECT observed_local_id FROM p25_site_call_identity_bucket
                WHERE identity_role_code=2
                """));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void nativeP25CallsShareASystemWhileUnresolvedCallsCreateNoSyntheticSystem() throws Exception
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

            //Remove the shared derived rows, then prove incomplete P25 identity creates no fake radio system.
            execute(connection, "DELETE FROM receiver_channel");
            execute(connection, "DELETE FROM radio_system");
            assertFalse(ReceiverActivitySchema.recordResolvedLogicalCall(connection,
                call(CHANNEL_A, 3, null, null, List.of())));
            assertFalse(ReceiverActivitySchema.recordResolvedLogicalCall(connection,
                call(CHANNEL_B, 4, null, null, List.of())));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM radio_system"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_site_call_bucket"));
        }
    }

    @Test
    void p25TrunkedOwnershipAcceptsEitherTrafficPhaseWithoutCrossingProtocolFamilies() throws Exception
    {
        try(Connection connection = open("p25-traffic-phase-ownership.sqlite"))
        {
            insertChannel(connection, CHANNEL_A, "P25_PHASE1");
            ReceiverActivityRecords.ResolvedLogicalCall phaseTwo = call(CHANNEL_A, 1, Protocol.APCO25_PHASE2,
                0xBEE00, 0x3A9, List.of());

            assertEquals(Protocol.APCO25_PHASE2.name(), phaseTwo.protocol(),
                "the completed call retains its observed traffic phase");
            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection, phaseTwo),
                "a Phase 1 control-channel configuration owns its Phase 2 traffic calls");
            ReceiverActivityRecords.ActivityEvent phaseTwoActivity =
                p25Activity(CHANNEL_A, Protocol.APCO25_PHASE2, CALL_START + 2);
            assertEquals(Protocol.APCO25_PHASE2.name(), phaseTwoActivity.protocol());
            assertNotNull(ReceiverActivitySchema.recordActivity(connection, phaseTwoActivity, true),
                "Phase 2 activity is owned by the Phase 1 control-channel configuration");
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM receiver_activity_event"));

            insertChannel(connection, CHANNEL_B, "P25_PHASE2");
            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection,
                call(CHANNEL_B, 3, Protocol.APCO25, 0xBEE00, 0x3A9, List.of())),
                "a retained legacy Phase 2 configuration still has P25-family ownership");
            assertEquals(2, scalar(connection,
                "SELECT SUM(logical_call_count) FROM trunked_logical_call_bucket"));

            execute(connection, "UPDATE configuration_channel SET decoder_type='DMR', " +
                "config_json='{\"decodeConfiguration\":{\"channelMode\":\"TRUNKED\"}}' " +
                "WHERE configuration_id='" + CHANNEL_B + "'");
            assertFalse(ReceiverActivitySchema.recordResolvedLogicalCall(connection,
                call(CHANNEL_B, 4, Protocol.APCO25, 0xBEE00, 0x3A9, List.of())),
                "another trunked protocol family cannot adopt a P25 call");
            execute(connection, "UPDATE configuration_channel SET decoder_type='NXDN', address_domain_code=1, " +
                "config_json='{\"decodeConfiguration\":{\"channelMode\":\"TRUNKED\"}}' " +
                "WHERE configuration_id='" + CHANNEL_B + "'");
            assertFalse(ReceiverActivitySchema.recordResolvedLogicalCall(connection,
                call(CHANNEL_B, 5, Protocol.APCO25_PHASE2, 0xBEE00, 0x3A9, List.of())));

            String conventional = "33333333-3333-4333-8333-333333333333";
            insertConventionalP25Channel(connection, conventional);
            assertNull(ReceiverActivitySchema.recordActivity(connection,
                new ReceiverActivityRecords.ActivityEvent(CALL_START + 6, conventional,
                    ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_P25, Protocol.APCO25_PHASE2.name(),
                    ReceiverActivityRecords.Action.CALL, "CALL_GROUP", "700001", "1201",
                    Form.TALKGROUP.name(), List.of(), 851_012_500L, null, null, false, null, null,
                    null, null, null, null, null, null, true, null, null, TrunkedIdentityDomain.STANDARD,
                    ReceiverActivityRecords.P25Identity.ORDINARY, ReceiverActivityRecords.P25Identity.ORDINARY,
                    List.of(), null), true),
                "phase-family ownership does not broaden conventional channel validation");
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

            assertTrue(ReceiverActivitySchema.runRetentionPass(connection, CALL_START + 3_600_001L) > 0);
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_logical_call_bucket"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_site_call_bucket"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM p25_learned_site"));
            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void p25SiteIdentityLookupsStayIndexedAtRepresentativeVolume() throws Exception
    {
        try(Connection connection = open("site-identity-plans.sqlite"))
        {
            insertChannel(connection, CHANNEL_A);
            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection,
                call(CHANNEL_A, 1, 0x924, 0x649, List.of(site(1, 1)))));
            long radioSystemId = scalar(connection, "SELECT id FROM radio_system");
            long receiverChannelId = scalar(connection, "SELECT id FROM receiver_channel");
            long learnedSiteId = scalar(connection, "SELECT learned_site_id FROM p25_learned_site");
            long firstBucket = Math.floorDiv(CALL_START, 3_600_000L) * 3_600_000L;

            execute(connection, """
                WITH RECURSIVE identity_number(value) AS (
                    SELECT 1 UNION ALL SELECT value + 1 FROM identity_number WHERE value < 1000
                )
                INSERT INTO radio_system_identity_summary(
                    radio_system_id, identity_kind_code, home_wacn, home_system_id, identity_id,
                    first_seen_ms, last_seen_ms)
                SELECT %d, 1, 0x924, 0x649, 100000 + value, %d, %d
                FROM identity_number
                """.formatted(radioSystemId, firstBucket, firstBucket));
            execute(connection, """
                WITH RECURSIVE hour_number(value) AS (
                    SELECT 0 UNION ALL SELECT value + 1 FROM hour_number WHERE value < 99
                )
                INSERT INTO p25_site_call_identity_bucket(
                    radio_system_id, learned_site_id, channel_id, bucket_start_ms,
                    identity_role_code, identity_kind_code, identity_summary_id, observed_local_id,
                    last_observed_at_ms, observed_call_count)
                SELECT %d, %d, %d, %d + hour_number.value * 3600000,
                    1, 1, identity.id, identity.identity_id,
                    %d + hour_number.value * 3600000 + 1000, 1
                FROM radio_system_identity_summary identity CROSS JOIN hour_number
                WHERE identity.radio_system_id=%d AND identity.identity_id BETWEEN 100001 AND 101000
                """.formatted(radioSystemId, learnedSiteId, receiverChannelId, firstBucket, firstBucket,
                radioSystemId));
            execute(connection, "ANALYZE");
            long identitySummaryId = scalar(connection, """
                SELECT id FROM radio_system_identity_summary WHERE identity_id=100500
                """);

            String identityPlan = queryPlan(connection, """
                SELECT channel_id, observed_local_id
                FROM p25_site_call_identity_bucket
                WHERE identity_summary_id=?
                """, identitySummaryId);
            assertTrue(identityPlan.contains("idx_p25_site_call_identity_identity"), identityPlan);
            assertFalse(identityPlan.contains("SCAN p25_site_call_identity_bucket"), identityPlan);

            String channelPlan = queryPlan(connection, """
                SELECT identity_summary_id, bucket_start_ms
                FROM p25_site_call_identity_bucket
                WHERE channel_id=? AND bucket_start_ms>=? AND bucket_start_ms<?
                ORDER BY bucket_start_ms, radio_system_id, identity_role_code, identity_summary_id
                LIMIT 200
                """, receiverChannelId, firstBucket, firstBucket + 100L * 3_600_000L);
            assertTrue(channelPlan.contains("idx_p25_site_call_identity_channel_time"), channelPlan);
            assertFalse(channelPlan.contains("SCAN p25_site_call_identity_bucket"), channelPlan);
            assertFalse(channelPlan.contains("USE TEMP B-TREE"), channelPlan);
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
            for(String table: List.of("radio_system_identity_summary", "trunked_radio_group_summary",
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
        insertChannel(connection, configurationId, "P25_PHASE1");
    }

    private static void insertChannel(Connection connection, String configurationId, String decoderType)
        throws Exception
    {
        execute(connection, """
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name,
                radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json)
            VALUES ('%s', 'TRUNKED', 0, 'P25', 'Site', 'Control',
                '%s', 0, '%s', 851012500, '{}')
            """.formatted(configurationId, configurationId, decoderType));
    }

    private static ReceiverActivityRecords.ResolvedLogicalCall call(String configurationId, long sequence,
                                                                     Integer wacn, Integer systemId,
                                                                     List<P25SiteIdentity> sites)
    {
        return call(configurationId, sequence, Protocol.APCO25, wacn, systemId, sites);
    }

    private static ReceiverActivityRecords.ResolvedLogicalCall call(String configurationId, long sequence,
                                                                     Protocol protocol, Integer wacn,
                                                                     Integer systemId, List<P25SiteIdentity> sites)
    {
        return new ReceiverActivityRecords.ResolvedLogicalCall(new LogicalCallId(9, sequence),
            CALL_START + sequence, configurationId, protocol.name(),
            TrunkedIdentityDomain.STANDARD, wacn, systemId, 1201, Form.TALKGROUP.name(),
            List.of(), 700001, true, 0x84, 1, ReceiverActivityRecords.P25Identity.ORDINARY,
            ReceiverActivityRecords.P25Identity.ORDINARY, List.of(), sites.stream()
                .map(site -> new ReceiverActivityRecords.P25SiteCallObservation(configurationId, site,
                    700001, 1201, Form.TALKGROUP.name(), ReceiverActivityRecords.P25Identity.ORDINARY,
                    ReceiverActivityRecords.P25Identity.ORDINARY, List.of(), List.of()))
                .toList(), null);
    }

    private static ReceiverActivityRecords.ActivityEvent p25Activity(String configurationId, Protocol protocol,
                                                                      long timestamp)
    {
        return new ReceiverActivityRecords.ActivityEvent(timestamp, configurationId,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, protocol.name(),
            ReceiverActivityRecords.Action.GRANT, "CALL_GROUP", "700001", "1201",
            Form.TALKGROUP.name(), List.of(), 851_012_500L, "0-1", 1, false, null, null,
            0xBEE00, 0x3A9, null, null, null, null, false, null, null, TrunkedIdentityDomain.STANDARD,
            ReceiverActivityRecords.P25Identity.ORDINARY, ReceiverActivityRecords.P25Identity.ORDINARY,
            List.of(), null);
    }

    private static void insertConventionalP25Channel(Connection connection, String configurationId) throws Exception
    {
        execute(connection, """
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name,
                auto_start, decoder_type, primary_frequency_hz, config_json)
            VALUES ('%s', 'CONVENTIONAL', 0, 'P25', '', 'Conventional',
                0, 'P25_CONVENTIONAL', 851012500, '{}')
            """.formatted(configurationId));
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

    private static String queryPlan(Connection connection, String sql, Object... parameters) throws Exception
    {
        StringBuilder plan = new StringBuilder();
        try(PreparedStatement statement = connection.prepareStatement("EXPLAIN QUERY PLAN " + sql))
        {
            for(int index = 0; index < parameters.length; index++)
            {
                statement.setObject(index + 1, parameters[index]);
            }
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
