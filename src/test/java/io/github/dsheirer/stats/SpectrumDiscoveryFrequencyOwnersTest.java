/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.stats.activity.ReceiverActivitySchema;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** A historical serving frequency belongs to its saved channel, independently of current receiver activity. */
class SpectrumDiscoveryFrequencyOwnersTest
{
    private static final String P25 = "00000000-0000-0000-0000-000000000081";
    private static final String DMR = "00000000-0000-0000-0000-000000000082";
    @TempDir Path temporary;
    private Path path;
    private StatsWebDatabase database;

    @BeforeEach
    void createCurrentDatabase() throws Exception
    {
        path = temporary.resolve("sdrtrunk.sqlite");
        try(Connection connection = connection(); Statement statement = connection.createStatement())
        {
            SdrTrunkDatabaseSchema.create(connection);
            ReceiverActivitySchema.create(connection);
            TrunkedSiteSchema.create(connection);
            statement.executeUpdate("INSERT INTO alias_list(id,name,family) VALUES " +
                "(81,'Regional P25','P25'),(82,'Regional DMR','DMR')");
            statement.executeUpdate("""
                INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,
                    name,alias_list_id,decoder_type,config_json) VALUES
                ('%s','TRUNKED',1,'Regional P25','North','North control',81,'P25_PHASE1','{}'),
                ('%s','TRUNKED',2,'Regional DMR','South','South control',82,'DMR',
                    '{"decodeConfiguration":{"channelMode":"TRUNKED"}}')
                """.formatted(P25, DMR));
            statement.executeUpdate("""
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms)
                VALUES(81,'%s',1000,4000),(82,'%s',1000,4000)
                """.formatted(P25, DMR));
            statement.executeUpdate("""
                INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms) VALUES(81,1000,4000)
                """);
            statement.executeUpdate("""
                INSERT INTO trunked_site_snapshot(channel_id,snapshot_hash,protocol_code,first_seen_ms,last_seen_ms)
                VALUES(82,'%s',3,1000,4000)
                """.formatted("a".repeat(64)));
        }
        database = new StatsWebDatabase(new UserPreferences(), path);
    }

    @Test
    void reservesHistoricalServingControlAndVoiceWithNamesAndNoDuplicateOwners() throws Exception
    {
        p25Frequency("control", 770_500_000, "CONTROL", "ALTERNATE_CONTROL");
        p25Frequency("voice", 770_600_000, "VOICE");
        List<Map<String,Object>> owners = database.discoveryFrequencyOwners(770_500_000);
        assertEquals(1, owners.size());
        assertEquals(P25, owners.getFirst().get("configuration_id"));
        assertEquals("North control", owners.getFirst().get("name"));
        assertEquals("Regional P25", owners.getFirst().get("system"));
        assertEquals("North", owners.getFirst().get("site"));
        assertEquals("known", owners.getFirst().get("kind"));
        assertEquals(1, database.discoveryFrequencyOwners(770_600_000).size());
        assertEquals(1, database.discoveryFrequencyOwners(770_506_250).size());
        assertTrue(database.discoveryFrequencyOwners(770_506_251).isEmpty());
        assertTrue(database.discoveryFrequencyOwners(725_500_000).isEmpty(), "Uplink is not a serving downlink");
    }

    @Test
    void excludesNeighborOnlyAndUnclassifiedFrequencies() throws Exception
    {
        p25Frequency("neighbor", 770_500_000, "NEIGHBOR_CONTROL");
        p25Frequency("unclassified", 770_600_000);
        p25Frequency("serving", 770_700_000, "CURRENT_CONTROL");
        assertTrue(database.discoveryFrequencyOwners(770_500_000).isEmpty());
        assertTrue(database.discoveryFrequencyOwners(770_600_000).isEmpty());
        assertEquals(1, database.discoveryFrequencyOwners(770_700_000).size());
    }

    @Test
    void reservesOtherTrunkedServingChannelsOnlyWhenTheyHaveARole() throws Exception
    {
        try(Connection connection = connection(); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO trunked_site_channel_summary(channel_id,channel_number,inbound_channel_number,timeslot,
                    frequency_hz,role_flags,first_seen_ms,last_seen_ms) VALUES
                (82,1,-1,-1,451500000,1,1000,4000),
                (82,2,-1,-1,451600000,0,1000,4000),
                (82,3,-1,1,451500000,4,1000,4000)
                """);
        }
        assertEquals(1, database.discoveryFrequencyOwners(451_500_000).size());
        assertEquals(DMR, database.discoveryFrequencyOwners(451_500_000).getFirst().get("configuration_id"));
        assertTrue(database.discoveryFrequencyOwners(451_600_000).isEmpty());
    }

    @Test
    void removingSavedOwnerMakesItsHistoricalFrequencyEligibleAgain() throws Exception
    {
        p25Frequency("control", 770_500_000, "CONTROL");
        assertEquals(1, database.discoveryFrequencyOwners(770_500_000).size());
        try(Connection connection = connection(); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("DELETE FROM configuration_channel WHERE configuration_id='" + P25 + "'");
        }
        assertTrue(database.discoveryFrequencyOwners(770_500_000).isEmpty());
    }

    private void p25Frequency(String key, long frequency, String... tags) throws Exception
    {
        try(Connection connection = connection(); Statement statement = connection.createStatement())
        {
            statement.executeUpdate("""
                INSERT INTO p25_site_channel_summary(channel_id,channel_key,downlink_hz,uplink_hz,first_seen_ms,last_seen_ms)
                VALUES(81,'%s',%d,%d,1000,4000)
                """.formatted(key, frequency, frequency - 45_000_000));
            for(String tag: tags)
                statement.executeUpdate("""
                    INSERT INTO p25_site_channel_tag_summary(channel_id,channel_key,tag,first_seen_ms,last_seen_ms)
                    VALUES(81,'%s','%s',1000,4000)
                    """.formatted(key, tag));
        }
    }

    private Connection connection() throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + path);
        try(Statement statement = connection.createStatement()) { statement.execute("PRAGMA foreign_keys=ON"); }
        return connection;
    }
}
