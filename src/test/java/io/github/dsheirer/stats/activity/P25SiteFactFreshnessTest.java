/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25SiteFactFreshnessTest
{
    private static final String CONFIGURATION = "123e4567-e89b-42d3-a456-426614174000";
    private static final List<String> SUMMARIES = List.of("p25_site_channel_summary",
        "p25_site_channel_tag_summary", "p25_site_frequency_band_summary", "p25_site_neighbor_summary",
        "p25_site_patch_group_summary", "p25_site_patch_group_talkgroup_summary", "p25_site_patch_group_radio_summary");

    @Test
    void changedAndUnchangedExportsDoNotCountCachedFactsAndFreshReceptionAdvancesThem() throws Exception
    {
        try(Connection connection = database())
        {
            ReceiverActivitySchema.insertSite(connection, snapshot(1_000, 1_000, "a"));
            ReceiverActivitySchema.insertSite(connection, snapshot(31_000, 1_000, "b"));
            ReceiverActivitySchema.insertSite(connection, snapshot(61_000, 1_000, "b"));
            for(String table: SUMMARIES)
            {
                assertEquals(1, scalar(connection, "SELECT observation_count FROM " + table), table);
                assertEquals(1_000, scalar(connection, "SELECT last_seen_ms FROM " + table), table);
            }
            for(String table: List.of("p25_site_channel", "p25_site_channel_tag", "p25_site_frequency_band",
                "p25_site_neighbor", "p25_site_patch_group", "p25_site_patch_group_talkgroup", "p25_site_patch_group_radio"))
            {
                assertEquals(1_000, scalar(connection, "SELECT confirmed_at_ms FROM " + table), table);
            }

            ReceiverActivitySchema.insertSite(connection, snapshot(71_000, 70_000, "c"));
            for(String table: SUMMARIES)
            {
                assertEquals(2, scalar(connection, "SELECT observation_count FROM " + table), table);
                assertEquals(70_000, scalar(connection, "SELECT last_seen_ms FROM " + table), table);
                assertEquals(1_000, scalar(connection, "SELECT first_seen_ms FROM " + table), table);
            }
            try(var statement = connection.createStatement(); var result = statement.executeQuery("PRAGMA quick_check"))
            {
                assertTrue(result.next());
                assertEquals("ok", result.getString(1));
            }
        }
    }

    @Test
    void legacyInflatedValuesArePreservedUntilAnActuallyNewerReception() throws Exception
    {
        try(Connection connection = database())
        {
            ReceiverActivitySchema.insertSite(connection, snapshot(1_000, 1_000, "a"));
            for(String table: SUMMARIES)
            {
                connection.createStatement().executeUpdate("UPDATE " + table +
                    " SET observation_count=100,last_seen_ms=60000");
            }
            ReceiverActivitySchema.insertSite(connection, snapshot(61_000, 30_000, "b"));
            for(String table: SUMMARIES)
            {
                assertEquals(100, scalar(connection, "SELECT observation_count FROM " + table), table);
                assertEquals(60_000, scalar(connection, "SELECT last_seen_ms FROM " + table), table);
            }
            ReceiverActivitySchema.insertSite(connection, snapshot(71_000, 70_000, "c"));
            for(String table: SUMMARIES)
            {
                assertEquals(101, scalar(connection, "SELECT observation_count FROM " + table), table);
                assertEquals(70_000, scalar(connection, "SELECT last_seen_ms FROM " + table), table);
            }
        }
    }

    private static ReceiverActivityRecords.SiteSnapshot snapshot(long exported, long received, String hash)
    {
        return new ReceiverActivityRecords.SiteSnapshot(exported, CONFIGURATION,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, hash.repeat(64), "APCO25", 0xBEE00, 0x348,
            0x123, 2, 7, 0, true, false, null, 770606250L, 770606250L, 770606250L,
            List.of(new P25NetworkConfigurationSnapshot.Channel("primary_control", "1-1376", 770606250L,
                800606250L, false, 1, null, received)),
            List.of(new P25NetworkConfigurationSnapshot.NeighborSite(0x348, 0x123, 2, 1, 0, "1-1892",
                773831250L, 803831250L, "VALID", received)),
            List.of(new P25NetworkConfigurationSnapshot.FrequencyBand(1, false, 762006250L, 12500,
                6250L, 30000000L, 1, received)),
            List.of(new P25NetworkConfigurationSnapshot.PatchGroup(100, 1, List.of(200), List.of(10001), received)),
            List.of());
    }

    private static Connection database() throws Exception
    {
        Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:");
        connection.createStatement().execute("PRAGMA foreign_keys=ON");
        SdrTrunkDatabaseSchema.create(connection);
        SdrTrunkDatabaseSchema.seedDefaultAliasLists(connection);
        ReceiverActivitySchema.create(connection);
        TrunkedSiteSchema.create(connection);
        connection.createStatement().executeUpdate("""
            INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,system_name,site_name,name,
                alias_list_id,auto_start,decoder_type,primary_frequency_hz,config_json)
            VALUES('%s','TRUNKED',0,'Test','Test','Control',
                (SELECT id FROM alias_list WHERE family='P25' LIMIT 1),0,'P25_PHASE1',770606250,'{}')
            """.formatted(CONFIGURATION));
        return connection;
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(var statement = connection.createStatement(); var result = statement.executeQuery(sql))
        {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }
}
