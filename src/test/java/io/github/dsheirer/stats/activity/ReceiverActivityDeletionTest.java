/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.Channel;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.ConventionalIdentity;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.Frequency;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.Identity;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.IdentityKind;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.LearnedSite;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.SavedSite;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.System;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.Test;

class ReceiverActivityDeletionTest
{
    private static final String CHANNEL_A = "123e4567-e89b-42d3-a456-426614174000";
    private static final String CHANNEL_B = "223e4567-e89b-42d3-a456-426614174000";

    @Test
    void p25FrequencyDeletesAllLogicalKeysOnlyAtTheSelectedSite() throws Exception
    {
        try(Connection connection = open())
        {
            p25Channel(connection, CHANNEL_A, 1, 1, 2);
            p25Channel(connection, CHANNEL_B, 2, 1, 3);
            execute(connection, """
                INSERT INTO p25_site_channel_summary
                    (channel_id, channel_key, downlink_hz, first_seen_ms, last_seen_ms)
                VALUES (1, '1-10', 851012500, 1000, 1000),
                       (1, '1-11', 851012500, 1000, 1000),
                       (1, '1-12', 851025000, 1000, 1000),
                       (2, '1-10', 851012500, 1000, 1000)
                """);
            execute(connection, """
                INSERT INTO p25_site_channel(channel_id, channel_key, downlink_hz, confirmed_at_ms)
                VALUES (1, '1-10', 851012500, 1000), (1, '1-11', 851012500, 1000),
                       (2, '1-10', 851012500, 1000)
                """);

            assertEquals(ReceiverActivityMaintenance.DeletionOutcome.STALE_SITE,
                ReceiverActivityDeletion.delete(connection,
                    new Frequency(CHANNEL_A, RetainedSiteKey.p25(1, 3, 1, null, 1000),
                        851012500)).outcome());
            assertEquals(4, count(connection, "p25_site_channel_summary"));

            ReceiverActivityDeletion.Result result = ReceiverActivityDeletion.delete(connection,
                new Frequency(CHANNEL_A, RetainedSiteKey.p25(1, 2, 1, null, 1000), 851012500));
            assertTrue(result.found());
            assertEquals(1, count(connection, "p25_site_channel_summary WHERE channel_id=1"));
            assertEquals(0, count(connection, "p25_site_channel WHERE channel_id=1"));
            assertEquals(1, count(connection, "p25_site_channel_summary WHERE channel_id=2"));
            assertEquals(1, count(connection, "p25_site_channel WHERE channel_id=2"));
            assertEquals(2, count(connection, "configuration_channel"));
        }
    }

    @Test
    void siteKeyRejectsReassignmentAndRecreatedSnapshotWithSameSiteNumbers() throws Exception
    {
        try(Connection connection = open())
        {
            p25Channel(connection, CHANNEL_A, 1, 1, 2);
            execute(connection, """
                INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms)
                VALUES (1,'p25:abcde:123',1,703710,291,1000,1000),
                       (2,'p25:abcdf:123',1,703711,291,1000,1000)
                """);
            execute(connection, """
                UPDATE receiver_channel SET radio_system_id=1,radio_system_assigned_at_ms=1000 WHERE id=1
                """);
            execute(connection, """
                INSERT INTO p25_site_channel_summary
                    (channel_id,channel_key,downlink_hz,first_seen_ms,last_seen_ms)
                VALUES (1,'1-10',851012500,1000,1000)
                """);

            String selectedKey = RetainedSiteKey.p25(1, 2, 1, 1L, 1000);
            execute(connection, """
                UPDATE receiver_channel SET radio_system_id=2,radio_system_assigned_at_ms=2000,
                    last_seen_ms=2000 WHERE id=1
                """);
            assertEquals(ReceiverActivityMaintenance.DeletionOutcome.STALE_SITE,
                ReceiverActivityDeletion.delete(connection,
                    new Frequency(CHANNEL_A, selectedKey, 851012500)).outcome());
            assertEquals(1, count(connection, "p25_site_channel_summary"));

            execute(connection, """
                UPDATE receiver_channel SET radio_system_id=1,radio_system_assigned_at_ms=2000 WHERE id=1
                """);
            execute(connection, "DELETE FROM p25_site_snapshot WHERE channel_id=1");
            execute(connection, """
                INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms,rfss,site)
                VALUES (1,2000,2000,1,2)
                """);
            execute(connection, """
                INSERT INTO p25_site_channel_summary
                    (channel_id,channel_key,downlink_hz,first_seen_ms,last_seen_ms)
                VALUES (1,'1-10',851012500,2000,2000)
                """);
            assertEquals(ReceiverActivityMaintenance.DeletionOutcome.STALE_SITE,
                ReceiverActivityDeletion.delete(connection,
                    new SavedSite(CHANNEL_A, selectedKey, false)).outcome());
            assertEquals(1, count(connection, "p25_site_channel_summary"));
        }
    }

    @Test
    void localRadioRouteResolvesHomeOwnerAndRetainsAmbiguousLegacyOwnership() throws Exception
    {
        try(Connection connection = open())
        {
            execute(connection,"INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id," +
                "first_seen_ms,last_seen_ms) VALUES(1,'p25:abcde:123',1,0xABCDE,0x123,1000,1000)");
            execute(connection,"INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id) " +
                "VALUES(1,0xABCDE,0x123,321),(2,0xABCDE,0x124,321)");
            execute(connection,"INSERT INTO radio_system_identity_summary(id,radio_system_id,identity_kind_code," +
                "home_wacn,home_system_id,identity_id,first_seen_ms,last_seen_ms,p25_subscriber_identity_id) " +
                "VALUES(1,1,2,0xABCDE,0x123,321,1000,1000,1),(2,1,2,0xABCDE,0x124,321,1000,1000,2)," +
                "(3,1,2,-1,-1,321,1000,1000,NULL)");
            assertEquals(3L,RadioSystemIdentityLookup.find(connection,1,2,-1,-1,321));
            assertTrue(ReceiverActivityDeletion.delete(connection,
                new Identity("p25:abcde:123","v1-r-x-x-321",IdentityKind.RADIO)).found());
            assertEquals(1L,RadioSystemIdentityLookup.find(connection,1,2,-1,-1,321));
            assertEquals(1L,RadioSystemIdentityLookup.find(connection,1,2,0xABCDE,0x123,321));
            assertEquals(2L,RadioSystemIdentityLookup.find(connection,1,2,0xABCDE,0x124,321));
            assertTrue(ReceiverActivityDeletion.delete(connection,
                new Identity("p25:abcde:123","v1-r-x-x-321",IdentityKind.RADIO)).found());
            assertEquals(1,count(connection,"radio_system_identity_summary"));
            assertEquals(null,RadioSystemIdentityLookup.find(connection,1,2,-1,-1,321));
        }
    }

    @Test
    void radioIdentityDeletionCannotCrossSystemsAndCascadesItsDetail() throws Exception
    {
        try(Connection connection = open())
        {
            p25Channel(connection, CHANNEL_A, 1, 1, 2);
            p25Channel(connection, CHANNEL_B, 2, 1, 3);
            execute(connection, """
                INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms)
                VALUES (1,'p25:abcde:123',1,703710,291,1000,1000),
                       (2,'p25:abcde:124',1,703710,292,1000,1000)
                """);
            execute(connection, """
                INSERT INTO radio_system_identity_summary
                    (id,radio_system_id,identity_kind_code,identity_id,first_seen_ms,last_seen_ms)
                VALUES (1,1,2,321,1000,1000), (2,2,2,321,1000,1000)
                """);
            execute(connection, """
                INSERT INTO receiver_activity_event
                    (channel_id,radio_system_id,observed_at_ms,action_code,source_identity_summary_id)
                VALUES (1,1,1000,12,1), (2,2,1000,12,2)
                """);

            assertFalse(ReceiverActivityDeletion.delete(connection,
                new Identity("p25:abcde:123", "v1-g-x-x-321", IdentityKind.TALKGROUP)).found());
            assertTrue(ReceiverActivityDeletion.delete(connection,
                new Identity("p25:abcde:123", "v1-r-x-x-321", IdentityKind.RADIO)).found());
            assertEquals(0, count(connection, "radio_system_identity_summary WHERE radio_system_id=1"));
            assertEquals(0, count(connection, "receiver_activity_event WHERE radio_system_id=1"));
            assertEquals(1, count(connection, "radio_system_identity_summary WHERE radio_system_id=2"));
            assertEquals(1, count(connection, "receiver_activity_event WHERE radio_system_id=2"));
        }
    }

    @Test
    void systemDeletionPreservesOrClearsAssociatedChannelHistoryByScope() throws Exception
    {
        try(Connection connection = open())
        {
            p25Channel(connection, CHANNEL_A, 1, 1, 2);
            execute(connection, """
                INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms)
                VALUES (1,'p25:abcde:123',1,703710,291,1000,1000)
                """);
            execute(connection, """
                UPDATE receiver_channel SET radio_system_id=1,radio_system_assigned_at_ms=1000 WHERE id=1
                """);

            assertTrue(ReceiverActivityDeletion.delete(connection,
                new System("p25:abcde:123", false)).found());
            assertEquals(1, count(connection, "receiver_channel"));
            assertEquals(1, count(connection, "p25_site_snapshot"));
            assertEquals(0, count(connection,
                "receiver_channel WHERE radio_system_id IS NOT NULL OR radio_system_assigned_at_ms IS NOT NULL"));
            assertEquals(0, count(connection, "radio_system"));

            execute(connection, """
                INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms)
                VALUES (2,'p25:abcde:123',1,703710,291,1000,1000)
                """);
            execute(connection, """
                UPDATE receiver_channel SET radio_system_id=2,radio_system_assigned_at_ms=1000 WHERE id=1
                """);
            assertTrue(ReceiverActivityDeletion.delete(connection,
                new System("p25:abcde:123", true)).found());
            assertEquals(0, count(connection, "receiver_channel"));
            assertEquals(0, count(connection, "p25_site_snapshot"));
            assertEquals(1, count(connection, "configuration_channel"));
        }
    }

    @Test
    void conventionalFrequencyRemovesItsOwnSummariesWithoutChangingConfiguration() throws Exception
    {
        try(Connection connection = open())
        {
            execute(connection, """
                INSERT INTO configuration_channel
                    (configuration_id,channel_kind,sort_order,name,alias_list_id,decoder_type,
                     primary_frequency_hz,config_json)
                VALUES ('%s','CONVENTIONAL',0,'Conventional',
                    (SELECT id FROM alias_list WHERE family='DMR' LIMIT 1),'DMR',451000000,
                    '{"decodeConfiguration":{"channelMode":"CONVENTIONAL"}}')
                """.formatted(CHANNEL_A));
            execute(connection, """
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms)
                VALUES (1,'%s',1000,1000)
                """.formatted(CHANNEL_A));
            execute(connection, """
                INSERT INTO conventional_activity_summary
                    (channel_id,frequency_hz,timeslot,first_seen_ms,last_seen_ms)
                VALUES (1,451000000,1,1000,1000), (1,452000000,1,1000,1000)
                """);
            execute(connection, """
                INSERT INTO conventional_activity_bucket(channel_id,frequency_hz,timeslot,bucket_start_ms)
                VALUES (1,451000000,1,0), (1,452000000,1,0)
                """);

            assertTrue(ReceiverActivityDeletion.delete(connection,
                new Frequency(CHANNEL_A, RetainedSiteKey.conventional(1, 1000), 451000000)).found());
            assertEquals(1, count(connection, "conventional_activity_summary"));
            assertEquals(1, count(connection, "conventional_activity_bucket"));
            assertEquals(1, count(connection, "configuration_channel"));
        }
    }

    @Test
    void conventionalFrequencyCanRemoveDmrIdentityRowsWithoutAnActivitySummary() throws Exception
    {
        try(Connection connection = open())
        {
            execute(connection, """
                INSERT INTO configuration_channel
                    (configuration_id,channel_kind,sort_order,name,alias_list_id,decoder_type,
                     primary_frequency_hz,config_json)
                VALUES ('%s','CONVENTIONAL',0,'Conventional',
                    (SELECT id FROM alias_list WHERE family='DMR' LIMIT 1),'DMR',451000000,
                    '{"decodeConfiguration":{"channelMode":"CONVENTIONAL"}}')
                """.formatted(CHANNEL_A));
            execute(connection, """
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms)
                VALUES (1,'%s',1000,1000)
                """.formatted(CHANNEL_A));
            execute(connection, """
                INSERT INTO dmr_conventional_radio_summary
                    (channel_id,frequency_hz,timeslot,radio_id,first_seen_ms,last_seen_ms,call_count)
                VALUES (1,451000000,1,321,1000,1000,1),
                       (1,452000000,1,322,1000,1000,1)
                """);

            ReceiverActivityDeletion.Result result = ReceiverActivityDeletion.delete(connection,
                new Frequency(CHANNEL_A, RetainedSiteKey.conventional(1, 1000), 451000000));
            assertTrue(result.found());
            assertEquals(1, count(connection, "dmr_conventional_radio_summary"));
            assertEquals(0, count(connection, "dmr_conventional_radio_summary WHERE frequency_hz=451000000"));
        }
    }

    @Test
    void learnedSiteScopePreservesSavedSnapshotUnlessChannelHistoryIsSelected() throws Exception
    {
        try(Connection connection = open())
        {
            p25Channel(connection, CHANNEL_A, 1, 1, 2);
            execute(connection, """
                INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms)
                VALUES (1,'p25:abcde:123',1,703710,291,1000,1000)
                """);
            execute(connection, """
                UPDATE receiver_channel SET radio_system_id=1,radio_system_assigned_at_ms=1000 WHERE id=1
                """);
            execute(connection, """
                INSERT INTO p25_learned_site(learned_site_id,radio_system_id,rfss,site,first_seen_ms,last_seen_ms)
                VALUES (1,1,1,2,1000,1000)
                """);
            execute(connection, """
                INSERT INTO p25_site_channel_summary
                    (channel_id,channel_key,downlink_hz,first_seen_ms,last_seen_ms)
                VALUES (1,'1-10',851012500,1000,1000)
                """);

            assertTrue(ReceiverActivityDeletion.delete(connection,
                new LearnedSite("p25:abcde:123", 1, 2, false)).found());
            assertEquals(0, count(connection, "p25_learned_site"));
            assertEquals(1, count(connection, "p25_site_snapshot"));
            assertEquals(1, count(connection, "p25_site_channel_summary"));
            assertEquals(1, count(connection, "receiver_channel"));

            execute(connection, """
                INSERT INTO p25_learned_site(learned_site_id,radio_system_id,rfss,site,first_seen_ms,last_seen_ms)
                VALUES (2,1,1,2,1000,1000)
                """);
            assertTrue(ReceiverActivityDeletion.delete(connection,
                new LearnedSite("p25:abcde:123", 1, 2, true)).found());
            assertEquals(0, count(connection, "receiver_channel"));
            assertEquals(0, count(connection, "p25_site_snapshot"));
            assertEquals(0, count(connection, "p25_learned_site"));
            assertEquals(1, count(connection, "configuration_channel"));
        }
    }

    @Test
    void savedSiteRequiresCurrentSiteKeyAndCanPreserveChannelHistory() throws Exception
    {
        try(Connection connection = open())
        {
            p25Channel(connection, CHANNEL_A, 1, 1, 2);
            execute(connection, """
                INSERT INTO p25_site_channel_summary
                    (channel_id,channel_key,downlink_hz,first_seen_ms,last_seen_ms)
                VALUES (1,'1-10',851012500,1000,1000)
                """);
            execute(connection, """
                INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code)
                VALUES (1,1000,12)
                """);
            assertEquals(ReceiverActivityMaintenance.DeletionOutcome.STALE_SITE,
                ReceiverActivityDeletion.delete(connection,
                    new SavedSite(CHANNEL_A, RetainedSiteKey.p25(1, 3, 1, null, 1000), false)).outcome());
            assertEquals(1, count(connection, "p25_site_snapshot"));

            assertTrue(ReceiverActivityDeletion.delete(connection,
                new SavedSite(CHANNEL_A, RetainedSiteKey.p25(1, 2, 1, null, 1000), false)).found());
            assertEquals(0, count(connection, "p25_site_snapshot"));
            assertEquals(0, count(connection, "p25_site_channel_summary"));
            assertEquals(1, count(connection, "receiver_channel"));
            assertEquals(1, count(connection, "receiver_activity_event"));

            execute(connection, """
                INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms,rfss,site)
                VALUES (1,1000,1000,1,2)
                """);
            assertTrue(ReceiverActivityDeletion.delete(connection,
                new SavedSite(CHANNEL_A, RetainedSiteKey.p25(1, 2, 1, null, 1000), true)).found());
            assertEquals(0, count(connection, "receiver_channel"));
            assertEquals(1, count(connection, "configuration_channel"));
        }
    }

    @Test
    void dmrFrequencyRequiresCurrentSiteAndLeavesOtherFrequencies() throws Exception
    {
        try(Connection connection = open())
        {
            execute(connection, """
                INSERT INTO configuration_channel
                    (configuration_id,channel_kind,sort_order,name,alias_list_id,decoder_type,
                     primary_frequency_hz,config_json)
                VALUES ('%s','TRUNKED',0,'DMR Site',
                    (SELECT id FROM alias_list WHERE family='DMR' LIMIT 1),'DMR',451000000,
                    '{"decodeConfiguration":{"channelMode":"TRUNKED"}}')
                """.formatted(CHANNEL_A));
            execute(connection, """
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms)
                VALUES (1,'%s',1000,1000)
                """.formatted(CHANNEL_A));
            execute(connection, """
                INSERT INTO trunked_site_snapshot
                    (channel_id,snapshot_hash,protocol_code,variant_code,observed_location_category_code,
                     observed_network_id,observed_site_id,first_seen_ms,last_seen_ms)
                VALUES (1,'%s',3,1,0,42,2,1000,1000)
                """.formatted("a".repeat(64)));
            execute(connection, """
                INSERT INTO trunked_site_channel_summary
                    (channel_id,channel_number,inbound_channel_number,timeslot,frequency_hz,
                     first_seen_ms,last_seen_ms)
                VALUES (1,10,-1,1,451012500,1000,1000),
                       (1,11,-1,1,452012500,1000,1000)
                """);
            String siteKey = RetainedSiteKey.trunked(3, 1, 0, 42, null, 2, null, null, 1, null, 1000);
            assertFalse(ReceiverActivityDeletion.delete(connection,
                new Frequency(CHANNEL_A, RetainedSiteKey.trunked(3, 1, 0, 42, null, 3, null, null,
                    1, null, 1000),
                    451012500)).found());
            assertTrue(ReceiverActivityDeletion.delete(connection,
                new Frequency(CHANNEL_A, siteKey, 451012500)).found());
            assertEquals(1, count(connection, "trunked_site_channel_summary"));
            assertEquals(1, count(connection, "receiver_channel"));
        }
    }

    @Test
    void conventionalDmrIdentityDeletesOneExactRowAndClearsLastSeenReferences() throws Exception
    {
        try(Connection connection = open())
        {
            execute(connection, """
                INSERT INTO configuration_channel
                    (configuration_id,channel_kind,sort_order,name,alias_list_id,decoder_type,
                     primary_frequency_hz,config_json)
                VALUES ('%s','CONVENTIONAL',0,'Conventional',
                    (SELECT id FROM alias_list WHERE family='DMR' LIMIT 1),'DMR',451000000,
                    '{"decodeConfiguration":{"channelMode":"CONVENTIONAL"}}')
                """.formatted(CHANNEL_A));
            execute(connection, """
                INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms)
                VALUES (1,'%s',1000,1000)
                """.formatted(CHANNEL_A));
            execute(connection, """
                INSERT INTO dmr_conventional_radio_summary
                    (channel_id,frequency_hz,timeslot,radio_id,first_seen_ms,last_seen_ms,call_count,
                     last_talkgroup_id,last_peer_radio_id)
                VALUES (1,451000000,1,321,1000,1000,1,45,NULL),
                       (1,451000000,1,322,1000,1000,1,45,321),
                       (1,451000000,2,321,1000,1000,1,45,NULL)
                """);
            execute(connection, """
                INSERT INTO dmr_conventional_talkgroup_summary
                    (channel_id,frequency_hz,timeslot,talkgroup_id,first_seen_ms,last_seen_ms,
                     call_count,last_source_radio_id)
                VALUES (1,451000000,1,45,1000,1000,1,321)
                """);

            assertTrue(ReceiverActivityDeletion.delete(connection,
                new ConventionalIdentity(CHANNEL_A,451000000,1,321,IdentityKind.RADIO)).found());
            assertEquals(0, count(connection,
                "dmr_conventional_radio_summary WHERE timeslot=1 AND radio_id=321"));
            assertEquals(1, count(connection,
                "dmr_conventional_radio_summary WHERE timeslot=2 AND radio_id=321"));
            assertEquals(0, count(connection,
                "dmr_conventional_radio_summary WHERE timeslot=1 AND last_peer_radio_id=321"));
            assertEquals(0, count(connection,
                "dmr_conventional_talkgroup_summary WHERE last_source_radio_id=321"));

            assertTrue(ReceiverActivityDeletion.delete(connection,
                new ConventionalIdentity(CHANNEL_A,451000000,1,45,IdentityKind.TALKGROUP)).found());
            assertEquals(0, count(connection, "dmr_conventional_talkgroup_summary"));
            assertEquals(0, count(connection,
                "dmr_conventional_radio_summary WHERE timeslot=1 AND last_talkgroup_id=45"));
        }
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
        return connection;
    }

    private static void p25Channel(Connection connection, String configurationId, int channelId,
                                   int rfss, int site) throws SQLException
    {
        execute(connection, """
            INSERT INTO configuration_channel
                (configuration_id,channel_kind,sort_order,name,alias_list_id,radioresolve_id,
                 decoder_type,primary_frequency_hz,config_json)
            VALUES ('%s','TRUNKED',%d,'Site',
                (SELECT id FROM alias_list WHERE family='P25' LIMIT 1),
                'aaaaaaaa-aaaa-4aaa-8aaa-%012d','P25_PHASE1',851000000,'{}')
            """.formatted(configurationId, channelId, channelId));
        execute(connection, """
            INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms)
            VALUES (%d,'%s',1000,1000)
            """.formatted(channelId, configurationId));
        execute(connection, """
            INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms,rfss,site)
            VALUES (%d,1000,1000,%d,%d)
            """.formatted(channelId, rfss, site));
    }

    private static int count(Connection connection, String tableAndWhere) throws SQLException
    {
        try(Statement statement = connection.createStatement();
            ResultSet rows = statement.executeQuery("SELECT count(*) FROM " + tableAndWhere))
        {
            return rows.next() ? rows.getInt(1) : 0;
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute(sql);
        }
    }
}
