/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.activity.DmrActivitySchema;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Current DMR/NXDN site tracking behavior with saved-channel ownership and exact constraints. */
class TrunkedSiteSchemaTest
{
    private static final String DMR_CHANNEL = "11111111-1111-4111-8111-111111111111";
    private static final String NXDN_CHANNEL = "22222222-2222-4222-8222-222222222222";
    private static final String HASH_A = "a".repeat(64);
    private static final String HASH_B = "b".repeat(64);

    @TempDir
    Path mTemporaryFolder;

    @Test
    void createsAndValidatesSchemaWithoutAnIndependentVersionMarker() throws Exception
    {
        try(Connection connection = open("shape.sqlite"))
        {
            TrunkedSiteSchema.validate(connection);
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM database_metadata WHERE key='trunked_site_schema_version'
                """));
            assertEquals("channel_id|snapshot_hash|protocol_code|variant_code|location_category_code", text(connection,
                """
                SELECT group_concat(name, '|') FROM (
                    SELECT name FROM pragma_table_info('trunked_site_snapshot') WHERE cid < 5 ORDER BY cid)
                """));
            assertFalse(hasColumn(connection, "trunked_site_snapshot", "guid"));
            assertFalse(hasColumn(connection, "trunked_site_snapshot", "identity_domain_code"));
            assertFalse(hasColumn(connection, "trunked_site_snapshot", "configured_system"));
        }
    }

    @Test
    void unchangedHeartbeatRefreshesOnlyCurrentControlAndRejectsOlderState() throws Exception
    {
        try(Connection connection = open("heartbeat.sqlite"))
        {
            seedReceiver(connection, DMR_CHANNEL, "DMR");
            TrunkedSiteSchema.Channel control = new TrunkedSiteSchema.Channel(42, 42, 1,
                451_000_000L, 456_000_000L, TrunkedSiteSchema.CHANNEL_ROLE_CURRENT_CONTROL, 1_000);
            TrunkedSiteSchema.Channel traffic = new TrunkedSiteSchema.Channel(43, 43, 2,
                452_000_000L, 457_000_000L, TrunkedSiteSchema.CHANNEL_ROLE_TRAFFIC, 1_000);
            TrunkedSiteSchema.Neighbor neighbor = new TrunkedSiteSchema.Neighbor(1, 0, 0, null,
                2, 44, 453_000_000L, TrunkedSiteSchema.NEIGHBOR_STATUS_ACTIVE, 1_000);
            assertTrue(TrunkedSiteSchema.upsert(connection,
                dmr(1_000, HASH_A, List.of(control, traffic), List.of(neighbor))));
            assertTrue(TrunkedSiteSchema.upsert(connection,
                dmr(2_000, HASH_A, List.of(control, traffic), List.of(neighbor))));

            assertEquals("2|2000", text(connection, """
                SELECT observation_count || '|' || last_seen_ms FROM trunked_site_snapshot
                """));
            assertEquals("1|2000", text(connection, """
                SELECT observation_count || '|' || last_seen_ms FROM trunked_site_channel_summary
                WHERE channel_number=42
                """));
            assertEquals("1|1000", text(connection, """
                SELECT observation_count || '|' || last_seen_ms FROM trunked_site_channel_summary
                WHERE channel_number=43
                """));
            assertEquals("1|1000", text(connection, """
                SELECT observation_count || '|' || last_seen_ms FROM trunked_site_neighbor_summary
                """));

            assertFalse(TrunkedSiteSchema.upsert(connection,
                dmr(1_500, HASH_B, List.of(), List.of())));
            assertEquals(HASH_A, text(connection, "SELECT snapshot_hash FROM trunked_site_snapshot"));
        }
    }

    @Test
    void protocolOrLocationClassificationChangeClearsIncompatibleChildren() throws Exception
    {
        try(Connection connection = open("transition.sqlite"))
        {
            seedReceiver(connection, DMR_CHANNEL, "DMR");
            TrunkedSiteSchema.Channel dmrChannel = new TrunkedSiteSchema.Channel(42, 42, 1,
                451_000_000L, 456_000_000L, TrunkedSiteSchema.CHANNEL_ROLE_TRAFFIC, 1_000);
            TrunkedSiteSchema.Neighbor dmrNeighbor = new TrunkedSiteSchema.Neighbor(1, 0, 0, null,
                2, 43, 452_000_000L, 1, 1_000);
            TrunkedSiteSchema.upsert(connection,
                dmr(1_000, HASH_A, List.of(dmrChannel), List.of(dmrNeighbor)));

            TrunkedSiteSchema.Channel nxdnChannel = new TrunkedSiteSchema.Channel(120, 121, null,
                155_000_000L, 160_000_000L, TrunkedSiteSchema.CHANNEL_ROLE_CURRENT_CONTROL, 2_000);
            TrunkedSiteSchema.Neighbor nxdnNeighbor = new TrunkedSiteSchema.Neighbor(2, 4, 1, 303,
                2, 122, 156_000_000L, 1, 2_000);
            TrunkedSiteSchema.upsert(connection, nxdn(DMR_CHANNEL, 2_000, HASH_B, 4,
                List.of(nxdnChannel), List.of(nxdnNeighbor)));

            assertEquals(TrunkedSiteSchema.PROTOCOL_NXDN,
                scalar(connection, "SELECT protocol_code FROM trunked_site_snapshot"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM trunked_site_channel_summary"));
            assertEquals(120, scalar(connection, "SELECT channel_number FROM trunked_site_channel_summary"));
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM trunked_site_neighbor_summary"));
            assertEquals(4, scalar(connection,
                "SELECT location_category_code FROM trunked_site_neighbor_summary"));
        }
    }

    @Test
    void exactProtocolChecksRejectCrossProtocolAndMalformedFacts() throws Exception
    {
        try(Connection connection = open("checks.sqlite"))
        {
            seedReceiver(connection, DMR_CHANNEL, "DMR");
            int channel = channelId(connection, DMR_CHANNEL);
            assertThrows(IllegalArgumentException.class, () -> TrunkedSiteSchema.upsert(connection,
                new TrunkedSiteSchema.Snapshot(1_000, DMR_CHANNEL, "bad-hash", 3, 1, 0,
                    0, null, 1, null, 1, 1, 1, 1, 1, 1, null, 0, null,
                    451_000_000L, 451_000_000L, List.of(), List.of())));
            assertThrows(IllegalArgumentException.class, () -> TrunkedSiteSchema.upsert(connection,
                new TrunkedSiteSchema.Snapshot(1_000, DMR_CHANNEL, HASH_A, 3, 1, 2,
                    0, null, 1, null, 1, 1, 1, 1, 1, 1, null, 0, null,
                    451_000_000L, 451_000_000L, List.of(), List.of())));
            assertThrows(IllegalArgumentException.class, () -> TrunkedSiteSchema.upsert(connection,
                dmr(1_000, HASH_A, List.of(new TrunkedSiteSchema.Channel(42, 42, 0,
                    451_000_000L, null, 1)), List.of())));

            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO trunked_site_snapshot(
                    channel_id, snapshot_hash, protocol_code, variant_code, location_category_code,
                    network_id, system_id, site_id, model_code, brand_code, mode_code, channel_type_code,
                    service_flags, first_seen_ms, last_seen_ms)
                VALUES (%d, '%s', 3, 1, 0, 0, 303, 1, 1, 1, 1, 1, 0, 1000, 1000)
                """.formatted(channel, HASH_A)));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO trunked_site_snapshot(
                    channel_id, snapshot_hash, protocol_code, variant_code, location_category_code,
                    network_id, site_id, model_code, brand_code, mode_code, channel_type_code,
                    service_flags, first_seen_ms, last_seen_ms)
                VALUES (%d, '%s', 3, 1, 0, 0, 1, 1, 1, 1, 1, 0, 0, 1000)
                """.formatted(channel, HASH_A)));

            TrunkedSiteSchema.upsert(connection, dmr(1_000, HASH_A, List.of(), List.of()));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO trunked_site_channel_summary(
                    channel_id, channel_number, inbound_channel_number, timeslot, frequency_hz,
                    role_flags, first_seen_ms, last_seen_ms, observation_count)
                VALUES (%d, 1, 1, 0, 451000000, 0, 1000, 1000, 1)
                """.formatted(channel)));
            assertThrows(SQLException.class, () -> execute(connection, """
                INSERT INTO trunked_site_neighbor_summary(
                    channel_id, variant_code, location_category_code, network_id, system_id, site_id,
                    channel_number, frequency_hz, status_flags, first_seen_ms, last_seen_ms, observation_count)
                VALUES (%d, 1, 0, 0, -1, 2, 43, 452000000, 4, 1000, 1000, 1)
                """.formatted(channel)));
        }
    }

    @Test
    void retentionClearResetAndConfigurationCascadeBoundAllFacts() throws Exception
    {
        try(Connection connection = open("lifecycle.sqlite"))
        {
            seedReceiver(connection, DMR_CHANNEL, "DMR");
            seedReceiver(connection, NXDN_CHANNEL, "NXDN");
            TrunkedSiteSchema.upsert(connection, dmr(1_000, HASH_A,
                List.of(new TrunkedSiteSchema.Channel(42, 42, 1, 451_000_000L, null, 1, 1_000)),
                List.of(new TrunkedSiteSchema.Neighbor(1, 0, 0, null, 2, 43, 452_000_000L, 1, 1_000))));
            TrunkedSiteSchema.upsert(connection, nxdn(NXDN_CHANNEL, 10_000, HASH_B, 4,
                List.of(new TrunkedSiteSchema.Channel(120, 121, null, 155_000_000L, null, 1, 10_000)),
                List.of(new TrunkedSiteSchema.Neighbor(2, 4, 1, 303, 2, 122, 156_000_000L, 1, 10_000))));

            TrunkedSiteSchema.CleanupResult cleanup = TrunkedSiteSchema.deleteOlderThan(connection, 5_000);
            assertEquals(1, cleanup.channelsDeleted());
            assertEquals(1, cleanup.neighborsDeleted());
            assertEquals(1, cleanup.sitesDeleted());
            assertEquals(1, scalar(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));

            assertEquals(1, TrunkedSiteSchema.clearSiteStats(connection, NXDN_CHANNEL));
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));
            TrunkedSiteSchema.upsert(connection, nxdn(NXDN_CHANNEL, 11_000, HASH_B, 4,
                List.of(), List.of()));
            assertEquals(1, TrunkedSiteSchema.resetStats(connection));

            TrunkedSiteSchema.upsert(connection, dmr(12_000, HASH_A, List.of(), List.of()));
            execute(connection, "DELETE FROM configuration_channel WHERE configuration_id='" + DMR_CHANNEL + "'");
            assertEquals(0, scalar(connection, "SELECT COUNT(*) FROM trunked_site_snapshot"));
        }
    }

    @Test
    void admissionCapsAndIndexesKeepQueriesBounded() throws Exception
    {
        try(Connection connection = open("bounds.sqlite"))
        {
            seedReceiver(connection, DMR_CHANNEL, "DMR");
            List<TrunkedSiteSchema.Channel> channels = new ArrayList<>();
            for(int x = 0; x < TrunkedSiteSchema.MAXIMUM_CHANNEL_FACTS_PER_SNAPSHOT + 50; x++)
            {
                channels.add(new TrunkedSiteSchema.Channel(x, x, x % 2 + 1, 451_000_000L + x * 12_500L,
                    null, TrunkedSiteSchema.CHANNEL_ROLE_TRAFFIC, 1_000));
            }
            List<TrunkedSiteSchema.Neighbor> neighbors = new ArrayList<>();
            for(int x = 0; x < TrunkedSiteSchema.MAXIMUM_NEIGHBOR_FACTS_PER_SNAPSHOT + 25; x++)
            {
                neighbors.add(new TrunkedSiteSchema.Neighbor(1, 0, 0, null, x, x,
                    460_000_000L + x * 12_500L, 1, 1_000));
            }
            TrunkedSiteSchema.upsert(connection, dmr(1_000, HASH_A, channels, neighbors));
            assertEquals(TrunkedSiteSchema.MAXIMUM_CHANNEL_FACTS_PER_SNAPSHOT,
                scalar(connection, "SELECT COUNT(*) FROM trunked_site_channel_summary"));
            assertEquals(TrunkedSiteSchema.MAXIMUM_NEIGHBOR_FACTS_PER_SNAPSHOT,
                scalar(connection, "SELECT COUNT(*) FROM trunked_site_neighbor_summary"));

            assertPlanUses(connection, """
                EXPLAIN QUERY PLAN SELECT channel_id FROM trunked_site_snapshot
                WHERE last_seen_ms < 2000 ORDER BY last_seen_ms, channel_id LIMIT 1000
                """, "idx_trunked_site_snapshot_last_seen");
            assertPlanUses(connection, """
                EXPLAIN QUERY PLAN SELECT channel_number FROM trunked_site_channel_summary
                WHERE last_seen_ms < 2000 ORDER BY last_seen_ms, channel_id LIMIT 1000
                """, "idx_trunked_site_channel_last_seen");
            assertPlanUses(connection, """
                EXPLAIN QUERY PLAN SELECT site_id FROM trunked_site_neighbor_summary
                WHERE last_seen_ms < 2000 ORDER BY last_seen_ms, channel_id LIMIT 1000
                """, "idx_trunked_site_neighbor_last_seen");
        }
    }

    @Test
    void validationRejectsChangedDefinitionsWithoutRepairingThem() throws Exception
    {
        try(Connection connection = open("validation.sqlite"))
        {
            execute(connection, "DROP INDEX idx_trunked_site_snapshot_last_seen");
            assertThrows(SQLException.class, () -> TrunkedSiteSchema.validate(connection));
            assertEquals(0, scalar(connection, """
                SELECT COUNT(*) FROM sqlite_master WHERE name='idx_trunked_site_snapshot_last_seen'
                """));
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

    private static void seedReceiver(Connection connection, String configurationId, String decoder) throws Exception
    {
        execute(connection, """
            INSERT INTO configuration_channel(
                configuration_id, channel_kind, sort_order, system_name, site_name, name,
                radioresolve_id, auto_start, decoder_type, primary_frequency_hz, config_json)
            VALUES ('%s', 'TRUNKED', 0, 'System', 'Site', 'Control', '%s', 0, '%s', 451000000, '{}')
            """.formatted(configurationId, configurationId, decoder));
        execute(connection, """
            INSERT INTO receiver_channel(configuration_id, first_seen_ms, last_seen_ms)
            VALUES ('%s', 1, 1)
            """.formatted(configurationId));
    }

    private static TrunkedSiteSchema.Snapshot dmr(long observedAt, String hash,
                                                   List<TrunkedSiteSchema.Channel> channels,
                                                   List<TrunkedSiteSchema.Neighbor> neighbors)
    {
        return new TrunkedSiteSchema.Snapshot(observedAt, DMR_CHANNEL, hash,
            TrunkedSiteSchema.PROTOCOL_DMR, 1, 0, 0, null, 1, null,
            1, 1, 1, 1, 1, 1, null, 0, null, 451_000_000L, 451_000_000L, channels, neighbors);
    }

    private static TrunkedSiteSchema.Snapshot nxdn(String configurationId, long observedAt, String hash,
                                                    int locationCategory,
                                                    List<TrunkedSiteSchema.Channel> channels,
                                                    List<TrunkedSiteSchema.Neighbor> neighbors)
    {
        //Offline capture characterization observed NXDN RAN 1 / System 303 / Site 1.
        return new TrunkedSiteSchema.Snapshot(observedAt, configurationId, hash,
            TrunkedSiteSchema.PROTOCOL_NXDN, 2, locationCategory, 1, 303, 1, 1,
            null, null, 2, null, null, null, 12, 16, 60,
            155_000_000L, 155_000_000L, channels, neighbors);
    }

    private static int channelId(Connection connection, String configurationId) throws Exception
    {
        return scalar(connection,
            "SELECT id FROM receiver_channel WHERE configuration_id='" + configurationId + "'");
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

    private static int scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getInt(1) : 0;
        }
    }

    private static String text(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet resultSet = statement.executeQuery(sql))
        {
            return resultSet.next() ? resultSet.getString(1) : null;
        }
    }

    private static void execute(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement())
        {
            statement.executeUpdate(sql);
        }
    }

    private static void assertPlanUses(Connection connection, String sql, String index) throws Exception
    {
        StringBuilder plan = new StringBuilder();
        try(PreparedStatement statement = connection.prepareStatement(sql);
            ResultSet resultSet = statement.executeQuery())
        {
            while(resultSet.next())
            {
                plan.append(resultSet.getString("detail")).append('\n');
            }
        }
        assertTrue(plan.toString().contains(index), plan.toString());
    }
}
