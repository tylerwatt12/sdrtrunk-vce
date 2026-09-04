/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Focused contract tests for the current saved-channel and radio-system identity model.
 */
class RadioSystemIdentityModelTest
{
    private static final String P25_A = "11111111-1111-4111-8111-111111111111";
    private static final String P25_B = "22222222-2222-4222-8222-222222222222";
    private static final String P25_OTHER = "33333333-3333-4333-8333-333333333333";
    private static final String P25_PROVISIONAL = "44444444-4444-4444-8444-444444444444";
    private static final String DMR_A = "55555555-5555-4555-8555-555555555555";
    private static final String DMR_B = "66666666-6666-4666-8666-666666666666";
    private static final String NXDN_A = "77777777-7777-4777-8777-777777777777";
    private static final String NXDN_B = "88888888-8888-4888-8888-888888888888";
    private static final String CONVENTIONAL = "99999999-9999-4999-8999-999999999999";

    @Test
    void nativeP25IdentityIsSharedButTheSameTalkgroupOnAnotherSystemIsNot() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            insertChannel(connection, P25_B, "TRUNKED", "P25_PHASE1", 2, "P25 B", correlation(2));
            insertChannel(connection, P25_OTHER, "TRUNKED", "P25_PHASE1", 1, "P25 Other", correlation(3));

            //A native P25 identity can be observed through more than one saved channel.
            record(connection, trunked(P25_A, "APCO25", 0xBEE00, 0x3A9, 4400, 1_000));
            record(connection, trunked(P25_B, "APCO25", 0xBEE00, 0x3A9, 4400, 2_000));
            record(connection, trunked(P25_OTHER, "APCO25", 0xBEE00, 0x3AA, 4400, 3_000));

            long shared = radioSystemId(connection, P25_A);
            assertEquals(shared, radioSystemId(connection, P25_B));
            assertNotEquals(shared, radioSystemId(connection, P25_OTHER));
            assertEquals("p25:bee00:3a9", systemKey(connection, shared));
            assertEquals(2, scalar(connection, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE identity_kind_code=1 AND identity_id=4400
                """));

            long channelId = channelId(connection, P25_A);
            execute(connection, """
                UPDATE configuration_channel
                SET system_name='Renamed', site_name='Moved', name='New display name', alias_list_id=2,
                    radioresolve_id='%s'
                WHERE configuration_id='%s'
                """.formatted(correlation(9), P25_A));
            record(connection, trunked(P25_A, "APCO25", 0xBEE00, 0x3A9, 4400, 4_000));
            assertEquals(channelId, channelId(connection, P25_A));
            assertEquals(shared, radioSystemId(connection, P25_A));
        }
    }

    @Test
    void dmrAndNxdnRemainScopedToEachSavedChannel() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, DMR_A, "TRUNKED", "DMR", 1, "DMR A", correlation(4));
            insertChannel(connection, DMR_B, "TRUNKED", "DMR", 2, "DMR B", correlation(5));
            insertChannel(connection, NXDN_A, "TRUNKED", "NXDN", 1, "NXDN A", correlation(6));
            insertChannel(connection, NXDN_B, "TRUNKED", "NXDN", 2, "NXDN B", correlation(7));

            //Until native grouping rules are defined, DMR and NXDN intentionally remain saved-channel scoped.
            record(connection, trunked(DMR_A, "DMR", null, null, 91, 1_000));
            record(connection, trunked(DMR_B, "DMR", null, null, 91, 2_000));
            record(connection, trunked(NXDN_A, "NXDN", null, null, 91, 3_000));
            record(connection, trunked(NXDN_B, "NXDN", null, null, 91, 4_000));

            Set<Long> systems = Set.of(radioSystemId(connection, DMR_A), radioSystemId(connection, DMR_B),
                radioSystemId(connection, NXDN_A), radioSystemId(connection, NXDN_B));
            assertEquals(4, systems.size());
            assertEquals("dmr:channel:" + DMR_A, systemKey(connection, radioSystemId(connection, DMR_A)));
            assertEquals("dmr:channel:" + DMR_B, systemKey(connection, radioSystemId(connection, DMR_B)));
            assertEquals("nxdn:channel:" + NXDN_A, systemKey(connection, radioSystemId(connection, NXDN_A)));
            assertEquals("nxdn:channel:" + NXDN_B, systemKey(connection, radioSystemId(connection, NXDN_B)));
        }
    }

    @Test
    void conventionalActivityUsesOnlyTheSavedConfigurationIdentity() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, CONVENTIONAL, "CONVENTIONAL", "NBFM", 1, "Fire Dispatch", null);
            record(connection, conventional(CONVENTIONAL, 155_730_000L, 1_000));
            long channelId = channelId(connection, CONVENTIONAL);

            execute(connection, """
                UPDATE configuration_channel
                SET system_name='Renamed', site_name='New site', name='New channel label', alias_list_id=2,
                    radioresolve_id='%s'
                WHERE configuration_id='%s'
                """.formatted(correlation(8), CONVENTIONAL));
            record(connection, conventional(CONVENTIONAL, 155_730_000L, 2_000));

            assertEquals(channelId, channelId(connection, CONVENTIONAL));
            assertEquals(CONVENTIONAL, text(connection,
                "SELECT configuration_id FROM receiver_channel WHERE id=" + channelId));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM receiver_channel WHERE id=" + channelId + " AND radio_system_id IS NOT NULL"));
            assertEquals(2, scalar(connection, """
                SELECT call_count FROM conventional_activity_summary
                WHERE channel_id=%d AND frequency_hz=155730000
                """.formatted(channelId)));
        }
    }

    @Test
    void reassignmentClearsChannelOwnedSiteAndQualityFactsAndDeletesTheProvisionalSystem() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_PROVISIONAL, "TRUNKED", "P25_PHASE1", 1, "P25", correlation(4));
            record(connection, trunked(P25_PROVISIONAL, "APCO25", null, null, 1201, 1_000));
            long channelId = channelId(connection, P25_PROVISIONAL);
            long provisionalSystem = radioSystemId(connection, P25_PROVISIONAL);
            execute(connection, """
                INSERT INTO p25_site_snapshot(channel_id, snapshot_hash, first_seen_ms, last_seen_ms, protocol)
                VALUES (%d, '%s', 1000, 1000, 'APCO25')
                """.formatted(channelId, "a".repeat(64)));
            execute(connection, """
                INSERT INTO p25_site_channel(channel_id, channel_key, downlink_hz, confirmed_at_ms)
                VALUES (%d, '0-1', 851012500, 1000)
                """.formatted(channelId));
            ReceiverActivitySchema.insertControlChannelQuality(connection,
                new ReceiverActivityRecords.ControlChannelQuality(1_500, P25_PROVISIONAL, 851_012_500,
                    -70.0, -71.0, -75.0, -68.0, 95.0, 100, 2, 3, 4, 5, 1_400));

            record(connection, trunked(P25_PROVISIONAL, "APCO25", 0xBEE00, 0x321, 1201, 2_000));

            assertNotEquals(provisionalSystem, radioSystemId(connection, P25_PROVISIONAL));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM radio_system WHERE id=" + provisionalSystem));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM p25_site_snapshot WHERE channel_id=" + channelId));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM p25_site_channel WHERE channel_id=" + channelId));
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM trunked_control_channel_quality WHERE channel_id=" + channelId));
        }
    }

    @Test
    void foreignKeysPreventCrossSystemPairsAndCascadesHaveBoringOwnership() throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            insertChannel(connection, P25_B, "TRUNKED", "P25_PHASE1", 2, "P25 B", correlation(2));
            insertChannel(connection, DMR_A, "TRUNKED", "DMR", 1, "DMR A", correlation(3));
            record(connection, trunked(P25_A, "APCO25", 0xBEE00, 0x3A9, 4400, 1_000));
            record(connection, trunked(P25_B, "APCO25", 0xBEE00, 0x3AA, 4400, 2_000));
            record(connection, trunked(DMR_A, "DMR", null, null, 91, 3_000));

            long systemA = radioSystemId(connection, P25_A);
            long systemB = radioSystemId(connection, P25_B);
            long channelB = channelId(connection, P25_B);
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO trunked_radio_site_presence(
                    radio_system_id, radio_id, channel_id, evidence_code, confirmed_at_ms
                ) VALUES (%d, 1001, %d, 1, 4000)
                """.formatted(systemA, channelB)));

            execute(connection, """
                INSERT INTO p25_learned_site(
                    learned_site_id, radio_system_id, rfss, site, first_seen_ms, last_seen_ms
                ) VALUES (901, %d, 1, 1, 1000, 1000)
                """.formatted(systemA));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO p25_site_call_bucket(
                    radio_system_id, learned_site_id, bucket_start_ms, observed_call_count
                ) VALUES (%d, 901, 0, 1)
                """.formatted(systemB)));

            long dmrSystem = radioSystemId(connection, DMR_A);
            execute(connection, "DELETE FROM configuration_channel WHERE configuration_id='" + DMR_A + "'");
            assertEquals(0, scalar(connection,
                "SELECT COUNT(*) FROM receiver_channel WHERE configuration_id='" + DMR_A + "'"));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM radio_system WHERE id=" + dmrSystem));

            ReceiverActivitySchema.validate(connection);
        }
    }

    @Test
    void sharedNativeSystemSurvivesOneChannelDeletionAndUnownedNativeSystemsPruneAfterFactsExpire()
        throws Exception
    {
        try(Connection connection = open())
        {
            insertChannel(connection, P25_A, "TRUNKED", "P25_PHASE1", 1, "P25 A", correlation(1));
            insertChannel(connection, P25_B, "TRUNKED", "P25_PHASE1", 2, "P25 B", correlation(2));
            record(connection, trunked(P25_A, "APCO25", 0xBEE00, 0x3A9, 4400, 1_000));
            record(connection, trunked(P25_B, "APCO25", 0xBEE00, 0x3A9, 4400, 2_000));
            long system = radioSystemId(connection, P25_A);

            execute(connection, "DELETE FROM configuration_channel WHERE configuration_id='" + P25_A + "'");
            assertEquals(system, radioSystemId(connection, P25_B));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM radio_system WHERE id=" + system));

            execute(connection, "DELETE FROM configuration_channel WHERE configuration_id='" + P25_B + "'");
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM radio_system WHERE id=" + system));
            execute(connection, "DELETE FROM radio_system_identity_summary WHERE radio_system_id=" + system);
            execute(connection, "DELETE FROM trunked_radio_group_summary WHERE radio_system_id=" + system);
            assertEquals(1, ReceiverActivitySchema.pruneUnusedRadioSystems(connection));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM radio_system WHERE id=" + system));
        }
    }

    @Test
    void schemaHasOnlyCanonicalChannelAndSystemIdentityAndRejectsMalformedKeys() throws Exception
    {
        try(Connection connection = open())
        {
            assertEquals(Set.of("id", "configuration_id", "first_seen_ms", "last_seen_ms", "radio_system_id"),
                columns(connection, "receiver_channel"));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM sqlite_master
                WHERE name IN ('receiver_context', 'trunked_identity_scope', 'trunked_identity_scope_context',
                    'radio_system_context', 'p25_system', 'trunked_identity_summary',
                    'trunked_radio_talkgroup_summary')
                """));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM database_metadata
                WHERE key IN ('p25_activity_schema_version', 'dmr_activity_schema_version',
                    'trunked_site_schema_version', 'trunked_identity_metrics_started_at_ms')
                """));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO radio_system(
                    system_key, protocol_code, address_domain_code, p25_wacn, p25_system_id,
                    first_seen_ms, last_seen_ms
                ) VALUES ('p25:bee00:124', 1, 0, 0xBEE00, 0x123, 1000, 1000)
                """));
            ReceiverActivitySchema.validate(connection);
        }
    }

    private static Connection open() throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        try(Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA foreign_keys=ON");
        }
        SdrTrunkDatabaseSchema.create(connection);
        ReceiverActivitySchema.create(connection);
        DmrActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        execute(connection, "INSERT INTO alias_list(id, name, family) VALUES (1, 'First', 'P25'), (2, 'Second', 'P25')");
        ReceiverActivitySchema.validate(connection);
        return connection;
    }

    private static void insertChannel(Connection connection, String configurationId, String kind, String decoder,
                                      long aliasListId, String name, String radioResolveId) throws SQLException
    {
        try(PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name, alias_list_id,
                radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json
            ) VALUES (?, ?, 0, 'Configured system', 'Configured site', ?, ?, ?, 0, ?, 851012500, '{}')
            """))
        {
            statement.setString(1, configurationId);
            statement.setString(2, kind);
            statement.setString(3, name);
            statement.setLong(4, aliasListId);
            statement.setString(5, radioResolveId);
            statement.setString(6, decoder);
            statement.executeUpdate();
        }
    }

    private static ReceiverActivityRecords.ActivityEvent trunked(String configurationId, String protocol,
                                                                  Integer wacn, Integer systemId, int talkgroup,
                                                                  long timestamp)
    {
        return new ReceiverActivityRecords.ActivityEvent(timestamp, configurationId,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, protocol, ReceiverActivityRecords.Action.GRANT,
            "CALL_GROUP", "1001", Integer.toString(talkgroup), "TALKGROUP", List.of(), 851_012_500L,
            "0-1", 1, false, null, null, wacn, systemId, null, null, null, null, false, null, null,
            ReceiverActivityRecords.IdentityDomain.STANDARD, ReceiverActivityRecords.P25TargetIdentity.ORDINARY,
            List.of());
    }

    private static ReceiverActivityRecords.ActivityEvent conventional(String configurationId, long frequency,
                                                                       long timestamp)
    {
        return new ReceiverActivityRecords.ActivityEvent(timestamp, configurationId,
            ReceiverActivityRecords.ReceiverKind.CONVENTIONAL_ANALOG, "NBFM", ReceiverActivityRecords.Action.CALL,
            "CALL", null, null, null, List.of(), frequency, null, null, false, null, null, null, null, null,
            null, null, null, true, null, null, ReceiverActivityRecords.IdentityDomain.STANDARD,
            ReceiverActivityRecords.P25TargetIdentity.UNKNOWN, List.of());
    }

    private static void record(Connection connection, ReceiverActivityRecords.ActivityEvent event) throws SQLException
    {
        ReceiverActivitySchema.recordActivity(connection, event, true);
    }

    private static long channelId(Connection connection, String configurationId) throws SQLException
    {
        return scalar(connection,
            "SELECT id FROM receiver_channel WHERE configuration_id='" + configurationId + "'");
    }

    private static long radioSystemId(Connection connection, String configurationId) throws SQLException
    {
        return scalar(connection,
            "SELECT radio_system_id FROM receiver_channel WHERE configuration_id='" + configurationId + "'");
    }

    private static String systemKey(Connection connection, long radioSystemId) throws SQLException
    {
        return text(connection, "SELECT system_key FROM radio_system WHERE id=" + radioSystemId);
    }

    private static String correlation(int suffix)
    {
        return "aaaaaaaa-aaaa-4aaa-8aaa-%012d".formatted(suffix);
    }

    private static Set<String> columns(Connection connection, String table) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("PRAGMA table_info(" + table + ")"))
        {
            java.util.ArrayList<String> columns = new java.util.ArrayList<>();
            while(resultSet.next())
            {
                columns.add(resultSet.getString("name"));
            }
            return columns.stream().collect(Collectors.toUnmodifiableSet());
        }
    }

    private static long scalar(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next(), sql);
            return resultSet.getLong(1);
        }
    }

    private static String text(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next(), sql);
            return resultSet.getString(1);
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }
}
