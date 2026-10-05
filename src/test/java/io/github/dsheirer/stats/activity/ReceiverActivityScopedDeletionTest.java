/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.ScopedData;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReceiverActivityScopedDeletionTest
{
    private static final String CHANNEL = "123e4567-e89b-42d3-a456-426614174000";
    private static final String OTHER_CHANNEL = "223e4567-e89b-42d3-a456-426614174000";
    private static final String SYSTEM = "p25:abcde:123";

    @Test
    void p25BandPlanCurrentAndSummaryCanBeRemovedSeparately() throws Exception
    {
        try(Connection connection = open())
        {
            p25Site(connection);
            execute(connection, """
                INSERT INTO p25_site_frequency_band(channel_id,band,base_hz,confirmed_at_ms)
                VALUES (1,0,851000000,1000),(1,1,762000000,1000)
                """);
            execute(connection, """
                INSERT INTO p25_site_frequency_band_summary
                    (channel_id,band,base_hz,first_seen_ms,last_seen_ms)
                VALUES (1,0,851000000,1000,1000),(1,1,762000000,1000,1000)
                """);
            String siteKey = RetainedSiteKey.p25(1, 2, 1, 1L, 1000);
            ScopedData current = new ScopedData("radio_system", SYSTEM, CHANNEL, siteKey,
                "band_plans", "0", List.of("current"));
            ReceiverActivityMaintenance.Preview preview = ReceiverActivityMaintenance.preview(connection, current);
            assertEquals(1, preview.rowsTotal());
            assertEquals(2, count(connection, "p25_site_frequency_band"));
            assertEquals(1, ReceiverActivityDeletion.delete(connection, current).rowsDeleted());
            assertEquals(1, count(connection, "p25_site_frequency_band"));
            assertEquals(2, count(connection, "p25_site_frequency_band_summary"));

            ScopedData summary = new ScopedData("radio_system", SYSTEM, CHANNEL, siteKey,
                "band_plans", "0", List.of("summary"));
            assertEquals(1, ReceiverActivityDeletion.delete(connection, summary).rowsDeleted());
            assertEquals(1, count(connection, "p25_site_frequency_band_summary"));
            assertEquals(1, count(connection, "configuration_channel"));
        }
    }

    @Test
    void hourlyHistoryDeletionPreservesSystemSummariesAndDetailedEvents() throws Exception
    {
        try(Connection connection = open())
        {
            p25Site(connection);
            execute(connection, """
                INSERT INTO radio_system_identity_summary
                    (id,radio_system_id,identity_kind_code,identity_id,first_seen_ms,last_seen_ms)
                VALUES (1,1,2,321,1000,1000)
                """);
            execute(connection, """
                INSERT INTO trunked_logical_call_bucket(radio_system_id,bucket_start_ms,logical_call_count)
                VALUES (1,0,1)
                """);
            execute(connection, """
                INSERT INTO receiver_activity_event
                    (channel_id,radio_system_id,observed_at_ms,action_code,source_identity_summary_id)
                VALUES (1,1,1000,4,1)
                """);
            ScopedData target = new ScopedData("radio_system", SYSTEM, null, null,
                "hourly_history", null, List.of("buckets"));
            assertEquals(1, ReceiverActivityMaintenance.preview(connection, target).rowsTotal());
            assertEquals(1, ReceiverActivityDeletion.delete(connection, target).rowsDeleted());
            assertEquals(0, count(connection, "trunked_logical_call_bucket"));
            assertEquals(1, count(connection, "radio_system_identity_summary"));
            assertEquals(1, count(connection, "receiver_activity_event"));
        }
    }

    @Test
    void aliasActivityResetPreservesSavedAliasAndOtherAliasCounters() throws Exception
    {
        try(Connection connection = open())
        {
            execute(connection, """
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value)
                VALUES (101,(SELECT id FROM alias_list WHERE family='P25' LIMIT 1),
                        'Alpha','RADIO_ID','APCO25',321),
                       (102,(SELECT id FROM alias_list WHERE family='P25' LIMIT 1),
                        'Bravo','RADIO_ID','APCO25',322)
                """);
            execute(connection, """
                INSERT INTO alias_activity_summary
                    (alias_id,alias_list_id,protocol_code,metrics_state,logical_call_count,
                     first_evidence_ms,last_evidence_ms,updated_at_ms)
                SELECT id,alias_list_id,1,'observed',5,1000,2000,2000 FROM alias
                """);
            ScopedData target = new ScopedData("alias_activity", null, null, null,
                "alias_activity", "101", List.of("summary"));
            assertEquals(1, ReceiverActivityMaintenance.preview(connection, target).rowsTotal());
            assertEquals(1, ReceiverActivityDeletion.delete(connection, target).rowsDeleted());
            assertEquals(2, count(connection, "alias"));
            assertEquals(2, count(connection, "alias_activity_summary"));
            assertEquals(1, count(connection,
                "alias_activity_summary WHERE alias_id=101 AND metrics_state='not_collected' " +
                    "AND logical_call_count IS NULL AND last_evidence_ms IS NULL"));
            assertEquals(1, count(connection,
                "alias_activity_summary WHERE alias_id=102 AND logical_call_count=5"));
        }
    }

    @Test
    void siteSelectionRequiresCurrentSiteKeyAndSystemOwnership() throws Exception
    {
        try(Connection connection = open())
        {
            p25Site(connection);
            p25OtherSystemSite(connection);
            execute(connection, """
                INSERT INTO p25_site_frequency_band(channel_id,band,base_hz,confirmed_at_ms)
                VALUES (1,0,851000000,1000),(2,0,762000000,1000)
                """);
            ScopedData stale = new ScopedData("radio_system", SYSTEM, CHANNEL,
                RetainedSiteKey.p25(1, 3, 1, 1L, 1000), "band_plans", "0", List.of("current"));
            assertEquals(ReceiverActivityMaintenance.DeletionOutcome.STALE_SITE,
                ReceiverActivityMaintenance.preview(connection, stale).outcome());
            ScopedData wrongSystem = new ScopedData("radio_system", SYSTEM, OTHER_CHANNEL,
                RetainedSiteKey.p25(1, 3, 2, 2L, 1000), "band_plans", "0", List.of("current"));
            assertEquals(ReceiverActivityMaintenance.DeletionOutcome.NOT_FOUND,
                ReceiverActivityMaintenance.preview(connection, wrongSystem).outcome());
            assertEquals(2, count(connection, "p25_site_frequency_band"));
        }
    }

    @Test
    void snapshotlessSavedSiteCanStillClearItsDetailedHistory() throws Exception
    {
        try(Connection connection = open())
        {
            p25Site(connection);
            execute(connection, """
                INSERT INTO receiver_activity_event
                    (channel_id,radio_system_id,observed_at_ms,action_code)
                VALUES (1,1,1000,12)
                """);
            String original = RetainedSiteKey.p25(1, 2, 1, 1L, 1000);
            ScopedData state = new ScopedData("radio_system", SYSTEM, CHANNEL, original,
                "site_state", null, List.of("current"));
            assertEquals(1, ReceiverActivityDeletion.delete(connection, state).rowsDeleted());
            assertEquals(1, count(connection, "receiver_activity_event"));
            String fallback = RetainedSiteKey.snapshotless(1, 1L, 1000);
            assertEquals(fallback, ReceiverActivityDeletion.savedChannel(connection, CHANNEL).siteKey());
            assertEquals(ReceiverActivityMaintenance.DeletionOutcome.STALE_SITE,
                ReceiverActivityMaintenance.preview(connection,
                    new ScopedData("radio_system", SYSTEM, CHANNEL, original,
                        "detailed_events", null, List.of("events"))).outcome());
            ScopedData history = new ScopedData("radio_system", SYSTEM, CHANNEL, fallback,
                "detailed_events", null, List.of("events"));
            assertEquals(1, ReceiverActivityDeletion.delete(connection, history).rowsDeleted());
            assertEquals(0, count(connection, "receiver_activity_event"));
        }
    }

    @Test
    void siteScopeKeepsHistoryFromAnotherSystemAssignmentOnSameChannel() throws Exception
    {
        try(Connection connection = open())
        {
            p25Site(connection);
            execute(connection, """
                INSERT INTO radio_system
                    (id,system_key,protocol_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms)
                VALUES (2,'p25:abcde:124',1,703710,292,1000,1000)
                """);
            execute(connection, """
                INSERT INTO receiver_activity_event(channel_id,radio_system_id,observed_at_ms,action_code)
                VALUES (1,1,1000,12),(1,2,1001,12)
                """);
            execute(connection, """
                INSERT INTO trunked_signaling_activity_bucket
                    (channel_id,radio_system_id,bucket_start_ms,grant_count)
                VALUES (1,1,0,1),(1,2,0,1)
                """);
            String siteKey = RetainedSiteKey.p25(1, 2, 1, 1L, 1000);
            ScopedData history = new ScopedData("radio_system", SYSTEM, CHANNEL, siteKey,
                "hourly_history", null, List.of("buckets"));
            assertEquals(1, ReceiverActivityDeletion.delete(connection, history).rowsDeleted());
            assertEquals(1, count(connection,
                "trunked_signaling_activity_bucket WHERE radio_system_id=2"));
            ScopedData events = new ScopedData("radio_system", SYSTEM, CHANNEL, siteKey,
                "detailed_events", null, List.of("events"));
            assertEquals(1, ReceiverActivityDeletion.delete(connection, events).rowsDeleted());
            assertEquals(1, count(connection, "receiver_activity_event WHERE radio_system_id=2"));
        }
    }

    @Test
    void identitySummaryPreviewDisclosesCascadeToHistory() throws Exception
    {
        try(Connection connection = open())
        {
            p25Site(connection);
            execute(connection, """
                INSERT INTO radio_system_identity_summary
                    (id,radio_system_id,identity_kind_code,identity_id,first_seen_ms,last_seen_ms)
                VALUES (1,1,2,321,1000,1000)
                """);
            execute(connection, """
                INSERT INTO trunked_logical_call_identity_bucket
                    (radio_system_id,bucket_start_ms,identity_role_code,identity_kind_code,
                     identity_summary_id,logical_call_count)
                VALUES (1,0,2,2,1,1)
                """);
            execute(connection, """
                INSERT INTO receiver_activity_event
                    (channel_id,radio_system_id,observed_at_ms,action_code,source_identity_summary_id)
                VALUES (1,1,1000,4,1)
                """);
            ScopedData target = new ScopedData("radio_system", SYSTEM, null, null,
                "radios", "v1-r-x-x-321", List.of("summary"));
            ReceiverActivityMaintenance.Preview preview = ReceiverActivityMaintenance.preview(connection, target);
            assertEquals(3, preview.rowsTotal());
            assertTrue(preview.effects().stream().anyMatch(effect -> effect.contains("foreign keys")));
            assertEquals(1, ReceiverActivityDeletion.delete(connection, target).rowsDeleted());
            assertEquals(0, count(connection, "radio_system_identity_summary"));
            assertEquals(0, count(connection, "trunked_logical_call_identity_bucket"));
            assertEquals(0, count(connection, "receiver_activity_event"));
        }
    }

    @Test
    void largeIdentityCascadeIsAvailableForBatchedCleanup() throws Exception
    {
        try(Connection connection = open())
        {
            p25Site(connection);
            execute(connection, """
                INSERT INTO radio_system_identity_summary
                    (id,radio_system_id,identity_kind_code,identity_id,first_seen_ms,last_seen_ms)
                VALUES (1,1,2,321,1000,1000)
                """);
            execute(connection, """
                WITH RECURSIVE sequence(n) AS
                    (SELECT 0 UNION ALL SELECT n+1 FROM sequence WHERE n<100000)
                INSERT INTO receiver_activity_event
                    (channel_id,radio_system_id,observed_at_ms,action_code,source_identity_summary_id)
                SELECT 1,1,n+1,4,1 FROM sequence
                """);
            ScopedData target = new ScopedData("radio_system", SYSTEM, null, null,
                "radios", "v1-r-x-x-321", List.of("summary"));
            ReceiverActivityMaintenance.Preview preview = ReceiverActivityMaintenance.preview(connection, target);
            assertEquals(100002, preview.rowsTotal());
            assertEquals(ReceiverActivityMaintenance.DeletionOutcome.DELETED, preview.outcome());
            assertEquals(1, count(connection, "radio_system_identity_summary"));
            assertEquals(100001, count(connection, "receiver_activity_event"));
        }
    }

    @Test
    void frequencyEventSelectionLeavesEventsWithoutThatFrequency() throws Exception
    {
        try(Connection connection = open())
        {
            p25Site(connection);
            execute(connection, """
                INSERT INTO receiver_activity_event
                    (channel_id,radio_system_id,observed_at_ms,action_code,frequency_hz)
                VALUES (1,1,1000,4,851000000),(1,1,1001,4,852000000),(1,1,1002,12,NULL)
                """);
            ScopedData target = new ScopedData("radio_system", SYSTEM, CHANNEL,
                RetainedSiteKey.p25(1, 2, 1, 1L, 1000), "frequencies", "851000000", List.of("events"));
            assertEquals(1, ReceiverActivityMaintenance.preview(connection, target).rowsTotal());
            assertEquals(1, ReceiverActivityDeletion.delete(connection, target).rowsDeleted());
            assertEquals(2, count(connection, "receiver_activity_event"));
        }
    }

    @Test
    void conventionalBulkIdentityEventsLeaveUnrelatedHistory() throws Exception
    {
        try(Connection connection = open())
        {
            conventionalChannel(connection, CHANNEL, 1, 451000000);
            execute(connection, """
                INSERT INTO receiver_activity_event
                    (channel_id,observed_at_ms,action_code,frequency_hz,timeslot,
                     source_observed_local_id,target_observed_local_id,target_kind_code)
                VALUES (1,1000,4,451000000,1,321,45,1),
                       (1,1001,4,451000000,1,NULL,46,1),
                       (1,1002,12,451000000,1,NULL,NULL,NULL)
                """);
            ScopedData radios = new ScopedData("saved_channel", CHANNEL, null, null,
                "radios", null, List.of("events"));
            assertEquals(1, ReceiverActivityDeletion.delete(connection, radios).rowsDeleted());
            assertEquals(2, count(connection, "receiver_activity_event"));
            ScopedData talkgroups = new ScopedData("saved_channel", CHANNEL, null, null,
                "talkgroups", null, List.of("events"));
            assertEquals(1, ReceiverActivityDeletion.delete(connection, talkgroups).rowsDeleted());
            assertEquals(1, count(connection, "receiver_activity_event WHERE action_code=12"));
        }
    }

    @Test
    void conventionalRadioRowRemovalClearsRelatedLastRadioReferences() throws Exception
    {
        try(Connection connection = open())
        {
            conventionalChannel(connection, CHANNEL, 1, 451000000);
            execute(connection, """
                INSERT INTO dmr_conventional_radio_summary
                    (channel_id,frequency_hz,timeslot,radio_id,first_seen_ms,last_seen_ms,call_count,
                     last_peer_radio_id)
                VALUES (1,451000000,1,321,1000,1000,1,NULL),
                       (1,451000000,1,322,1000,1000,1,321)
                """);
            execute(connection, """
                INSERT INTO dmr_conventional_talkgroup_summary
                    (channel_id,frequency_hz,timeslot,talkgroup_id,first_seen_ms,last_seen_ms,
                     call_count,last_source_radio_id)
                VALUES (1,451000000,1,45,1000,1000,1,321)
                """);
            ScopedData target = new ScopedData("saved_channel", CHANNEL, null, null,
                "radios", "451000000:1:321", List.of("summary"));
            assertEquals(1, ReceiverActivityDeletion.delete(connection, target).rowsDeleted());
            assertEquals(1, count(connection,
                "dmr_conventional_radio_summary WHERE radio_id=322 AND last_peer_radio_id IS NULL"));
            assertEquals(1, count(connection,
                "dmr_conventional_talkgroup_summary WHERE talkgroup_id=45 " +
                    "AND last_source_radio_id IS NULL"));
        }
    }

    @Test
    void p25SummaryFrequencyUsesDisplayedCurrentFrequencyForLogicalKey() throws Exception
    {
        try(Connection connection = open())
        {
            p25Site(connection);
            execute(connection, """
                INSERT INTO p25_site_channel_summary
                    (channel_id,channel_key,downlink_hz,first_seen_ms,last_seen_ms)
                VALUES (1,'1-10',852000000,1000,1000)
                """);
            execute(connection, """
                INSERT INTO p25_site_channel(channel_id,channel_key,downlink_hz,confirmed_at_ms)
                VALUES (1,'1-10',851000000,1000)
                """);
            execute(connection, """
                INSERT INTO p25_site_channel_tag(channel_id,channel_key,tag,confirmed_at_ms)
                VALUES (1,'1-10','Current tag',1000)
                """);
            execute(connection, """
                INSERT INTO p25_site_channel_tag_summary
                    (channel_id,channel_key,tag,first_seen_ms,last_seen_ms)
                VALUES (1,'1-10','Summary tag',1000,1000)
                """);
            ScopedData target = new ScopedData("radio_system", SYSTEM, CHANNEL,
                RetainedSiteKey.p25(1, 2, 1, 1L, 1000), "frequencies", "851000000", List.of("summary"));
            assertEquals(2, ReceiverActivityMaintenance.preview(connection, target).rowsTotal());
            assertEquals(2, ReceiverActivityDeletion.delete(connection, target).rowsDeleted());
            assertEquals(0, count(connection, "p25_site_channel_summary"));
            assertEquals(0, count(connection, "p25_site_channel_tag_summary"));
            assertEquals(1, count(connection, "p25_site_channel"));
            assertEquals(1, count(connection, "p25_site_channel_tag"));
            ScopedData current = new ScopedData("radio_system", SYSTEM, CHANNEL,
                RetainedSiteKey.p25(1, 2, 1, 1L, 1000), "frequencies", "851000000", List.of("current"));
            assertEquals(2, ReceiverActivityDeletion.delete(connection, current).rowsDeleted());
            assertEquals(0, count(connection, "p25_site_channel_tag"));
        }
    }

    @Test
    void conventionalAllClearsOneChannelWithoutChangingConfigurationOrAliases() throws Exception
    {
        try(Connection connection = open())
        {
            conventionalChannel(connection, CHANNEL, 1, 451000000);
            conventionalChannel(connection, OTHER_CHANNEL, 2, 452000000);
            execute(connection, """
                INSERT INTO conventional_activity_summary
                    (channel_id,frequency_hz,first_seen_ms,last_seen_ms,call_count)
                VALUES (1,451000000,1000,1000,3),(2,452000000,1000,1000,4)
                """);
            execute(connection, """
                INSERT INTO alias(id,alias_list_id,name,matcher_type,protocol,value)
                VALUES (101,(SELECT id FROM alias_list WHERE family='DMR' LIMIT 1),
                    'Known radio','RADIO_ID','DMR',321)
                """);
            ScopedData target = new ScopedData("saved_channel", CHANNEL, null, null,
                "all", null, List.of("summary", "buckets", "events"));
            assertEquals(1, ReceiverActivityMaintenance.preview(connection, target).rowsTotal());
            assertTrue(ReceiverActivityDeletion.delete(connection, target).found());
            assertEquals(2, count(connection, "receiver_channel"));
            assertEquals(1, count(connection, "conventional_activity_summary WHERE channel_id=2"));
            assertEquals(2, count(connection, "configuration_channel"));
            assertEquals(1, count(connection, "alias"));
        }
    }

    @Test
    void callAndSignalingSummaryResetsPreserveOppositeCounters() throws Exception
    {
        try(Connection connection = open())
        {
            conventionalChannel(connection, CHANNEL, 1, 451000000);
            execute(connection, """
                INSERT INTO conventional_activity_summary
                    (channel_id,frequency_hz,first_seen_ms,last_seen_ms,call_count,grant_count)
                VALUES (1,451000000,1000,1000,5,7)
                """);
            ScopedData calls = new ScopedData("saved_channel", CHANNEL, null, null,
                "call_activity", null, List.of("summary"));
            assertEquals(1, ReceiverActivityDeletion.delete(connection, calls).rowsDeleted());
            assertEquals(1, count(connection,
                "conventional_activity_summary WHERE call_count=0 AND grant_count=7"));
            ScopedData signaling = new ScopedData("saved_channel", CHANNEL, null, null,
                "signaling_activity", null, List.of("summary"));
            assertEquals(1, ReceiverActivityDeletion.delete(connection, signaling).rowsDeleted());
            assertEquals(1, count(connection,
                "conventional_activity_summary WHERE call_count=0 AND grant_count=0"));
        }
    }

    @Test
    void unsupportedLayerIsRejectedBeforeAnyDeletion() throws Exception
    {
        try(Connection connection = open())
        {
            p25Site(connection);
            ScopedData target = new ScopedData("radio_system", SYSTEM, CHANNEL,
                RetainedSiteKey.p25(1, 2, 1, 1L, 1000), "band_plans", null, List.of("events"));
            assertThrows(IllegalArgumentException.class,
                () -> ReceiverActivityMaintenance.preview(connection, target));
            assertEquals(1, count(connection, "p25_site_snapshot"));
        }
    }

    @Test
    void entireSiteThenSystemRemoveOnlyRetainedStatsAndKeepSavedChannels() throws Exception
    {
        try(Connection connection = open())
        {
            p25Site(connection);
            p25SecondSiteSameSystem(connection);
            execute(connection, """
                INSERT INTO p25_site_frequency_band_summary
                    (channel_id,band,base_hz,first_seen_ms,last_seen_ms)
                VALUES (1,0,851000000,1000,1000),(2,1,762000000,1000,1000)
                """);
            ScopedData site = new ScopedData("radio_system", SYSTEM, CHANNEL,
                RetainedSiteKey.p25(1, 2, 1, 1L, 1000), "all", null,
                List.of("current", "summary", "buckets", "events"));
            assertTrue(ReceiverActivityDeletion.delete(connection, site).found());
            assertEquals(1, count(connection, "receiver_channel WHERE id=2"));
            assertEquals(1, count(connection, "p25_site_frequency_band_summary WHERE channel_id=2"));
            assertEquals(1, count(connection, "radio_system"));

            ScopedData system = new ScopedData("radio_system", SYSTEM, null, null, "all", null,
                List.of("current", "summary", "buckets", "events"));
            assertEquals(2, ReceiverActivityMaintenance.preview(connection, system).rowsTotal());
            assertTrue(ReceiverActivityDeletion.delete(connection, system).found());
            assertEquals(2, count(connection, "receiver_channel"));
            assertEquals(1, count(connection, "radio_system"));
            assertEquals(2, count(connection, "configuration_channel"));
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

    private static void p25Site(Connection connection) throws SQLException
    {
        execute(connection, """
            INSERT INTO configuration_channel
                (configuration_id,channel_kind,sort_order,name,alias_list_id,radioresolve_id,
                 decoder_type,primary_frequency_hz,config_json)
            VALUES ('%s','TRUNKED',0,'Site',
                (SELECT id FROM alias_list WHERE family='P25' LIMIT 1),
                'aaaaaaaa-aaaa-4aaa-8aaa-000000000001','P25_PHASE1',851000000,'{}')
            """.formatted(CHANNEL));
        execute(connection, """
            INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms)
            VALUES (1,'%s',1,703710,291,1000,1000)
            """.formatted(SYSTEM));
        execute(connection, """
            INSERT INTO receiver_channel
                (id,configuration_id,radio_system_id,radio_system_assigned_at_ms,first_seen_ms,last_seen_ms)
            VALUES (1,'%s',1,1000,1000,1000)
            """.formatted(CHANNEL));
        execute(connection, """
            INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms,rfss,site)
            VALUES (1,1000,1000,1,2)
            """);
    }

    private static void p25OtherSystemSite(Connection connection) throws SQLException
    {
        execute(connection, """
            INSERT INTO configuration_channel
                (configuration_id,channel_kind,sort_order,name,alias_list_id,radioresolve_id,
                 decoder_type,primary_frequency_hz,config_json)
            VALUES ('%s','TRUNKED',1,'Other Site',
                (SELECT id FROM alias_list WHERE family='P25' LIMIT 1),
                'aaaaaaaa-aaaa-4aaa-8aaa-000000000002','P25_PHASE1',762000000,'{}')
            """.formatted(OTHER_CHANNEL));
        execute(connection, """
            INSERT INTO radio_system(id,system_key,protocol_code,p25_wacn,p25_system_id,first_seen_ms,last_seen_ms)
            VALUES (2,'p25:abcde:124',1,703710,292,1000,1000)
            """);
        execute(connection, """
            INSERT INTO receiver_channel
                (id,configuration_id,radio_system_id,radio_system_assigned_at_ms,first_seen_ms,last_seen_ms)
            VALUES (2,'%s',2,1000,1000,1000)
            """.formatted(OTHER_CHANNEL));
        execute(connection, """
            INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms,rfss,site)
            VALUES (2,1000,1000,1,3)
            """);
    }

    private static void p25SecondSiteSameSystem(Connection connection) throws SQLException
    {
        execute(connection, """
            INSERT INTO configuration_channel
                (configuration_id,channel_kind,sort_order,name,alias_list_id,radioresolve_id,
                 decoder_type,primary_frequency_hz,config_json)
            VALUES ('%s','TRUNKED',1,'Second Site',
                (SELECT id FROM alias_list WHERE family='P25' LIMIT 1),
                'aaaaaaaa-aaaa-4aaa-8aaa-000000000002','P25_PHASE1',762000000,'{}')
            """.formatted(OTHER_CHANNEL));
        execute(connection, """
            INSERT INTO receiver_channel
                (id,configuration_id,radio_system_id,radio_system_assigned_at_ms,first_seen_ms,last_seen_ms)
            VALUES (2,'%s',1,1000,1000,1000)
            """.formatted(OTHER_CHANNEL));
        execute(connection, """
            INSERT INTO p25_site_snapshot(channel_id,first_seen_ms,last_seen_ms,rfss,site)
            VALUES (2,1000,1000,1,3)
            """);
    }

    private static void conventionalChannel(Connection connection, String configurationId, int channelId,
                                            long frequencyHz) throws SQLException
    {
        execute(connection, """
            INSERT INTO configuration_channel
                (configuration_id,channel_kind,sort_order,name,alias_list_id,decoder_type,
                 primary_frequency_hz,config_json)
            VALUES ('%s','CONVENTIONAL',%d,'Conventional',
                (SELECT id FROM alias_list WHERE family='DMR' LIMIT 1),'DMR',%d,
                '{"decodeConfiguration":{"channelMode":"CONVENTIONAL"}}')
            """.formatted(configurationId, channelId, frequencyHz));
        execute(connection, """
            INSERT INTO receiver_channel(id,configuration_id,first_seen_ms,last_seen_ms)
            VALUES (%d,'%s',1000,1000)
            """.formatted(channelId, configurationId));
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
