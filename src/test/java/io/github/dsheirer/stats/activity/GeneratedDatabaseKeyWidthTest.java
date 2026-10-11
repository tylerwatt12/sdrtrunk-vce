/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.activity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.LogicalCallId;
import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.traffic.P25SubscriberIdentity;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.ScopedData;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Generated SQLite keys are 64-bit; air-interface addresses keep their separate bounded integer domains. */
class GeneratedDatabaseKeyWidthTest
{
    private static final String CHANNEL = "123e4567-e89b-42d3-a456-426614174000";
    private static final String SYSTEM = "p25:bee00:3a9";
    private static final long HIGH_KEY = (long)Integer.MAX_VALUE + 101;
    private static final int WORKING_ID = 0xFFFD26;
    private static final int SUBSCRIBER_ID = 831_102;
    private static final P25SubscriberIdentity SUBSCRIBER = new P25SubscriberIdentity(0xBEE00, 0x954,
        SUBSCRIBER_ID);
    private static final ReceiverActivityRecords.P25Identity RADIO =
        ReceiverActivityRecords.P25Identity.fullyQualifiedRadio(0xBEE00, 0x954, SUBSCRIBER_ID);

    @Test
    void highGeneratedKeysSurviveBatchedActivityCanonicalSubscriberAndPresenceJoins() throws Exception
    {
        try(Connection connection = open())
        {
            ReceiverActivitySchema.recordActivityBatch(connection, List.of(registration(1_000),
                registration(2_000)), true);

            RadioSystemSchema.RadioSystem system = RadioSystemSchema.ensureP25RadioSystem(connection,
                0xBEE00, 0x3A9, 2_000);
            assertTrue(system.radioSystemId() > Integer.MAX_VALUE);
            RadioSystemSchema.IdentityReference identity = RadioSystemSchema.identityReference(connection,
                system, ReceiverActivitySchema.IDENTITY_KIND_RADIO, WORKING_ID, RADIO, 2_000);
            assertNotNull(identity);
            assertTrue(identity.summaryId() > Integer.MAX_VALUE);
            assertEquals(WORKING_ID, identity.observedLocalId().intValue());

            assertEquals(2, scalar(connection, """
                SELECT count(*) FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                JOIN radio_system system ON system.id=event.radio_system_id
                JOIN radio_system_identity_summary identity ON identity.id=event.source_identity_summary_id
                    AND identity.radio_system_id=system.id
                JOIN p25_subscriber_identity subscriber ON subscriber.id=identity.p25_subscriber_identity_id
                WHERE event.id>2147483647 AND channel.id>2147483647 AND system.id>2147483647
                    AND identity.id>2147483647 AND subscriber.id>2147483647
                    AND identity.identity_id=831102 AND event.source_observed_working_id=16776486
                """));
            assertEquals(2, scalar(connection, """
                SELECT observation.registration_count FROM p25_wuid_assignment_observation_summary observation
                JOIN p25_subscriber_identity subscriber ON subscriber.id=observation.p25_subscriber_identity_id
                JOIN receiver_channel channel ON channel.id=observation.last_channel_id
                WHERE subscriber.id>2147483647 AND channel.id>2147483647
                """));
            assertEquals(identity.summaryId(), scalar(connection,
                "SELECT radio_identity_id FROM trunked_radio_channel_presence"));
            assertEquals(2, scalar(connection,
                "SELECT register_count FROM radio_system_identity_summary WHERE identity_kind_code=2"));
            assertHealthy(connection);
        }
    }

    @Test
    void highLearnedSiteAndDirectoryKeysRemainLinkedToLogicalCallBuckets() throws Exception
    {
        try(Connection connection = open())
        {
            ReceiverActivitySchema.recordActivity(connection, registration(1_000), true);
            long systemId = scalar(connection, "SELECT id FROM radio_system WHERE system_key='" + SYSTEM + "'");
            execute(connection, """
                INSERT INTO p25_learned_site(learned_site_id,radio_system_id,rfss,site,first_seen_ms,last_seen_ms)
                VALUES (%d,%d,0,0,1000,1000)
                """.formatted(HIGH_KEY, systemId));
            P25SiteIdentity site = new P25SiteIdentity(0xBEE00, 0x3A9, 1, 2);
            ReceiverActivityRecords.ResolvedLogicalCall call = new ReceiverActivityRecords.ResolvedLogicalCall(
                new LogicalCallId(9, 1), 2_000, CHANNEL, "APCO25", TrunkedIdentityDomain.STANDARD,
                0xBEE00, 0x3A9, 91, Form.TALKGROUP.name(), List.of(), WORKING_ID, false, null, null,
                ReceiverActivityRecords.P25Identity.ORDINARY, RADIO, List.of(),
                List.of(new ReceiverActivityRecords.P25SiteCallObservation(CHANNEL, site, WORKING_ID, 91,
                    Form.TALKGROUP.name(), ReceiverActivityRecords.P25Identity.ORDINARY, RADIO,
                    List.of(), List.of())), null);

            assertTrue(ReceiverActivitySchema.recordResolvedLogicalCall(connection, call));
            assertEquals(2, scalar(connection, """
                SELECT count(*) FROM p25_site_call_identity_bucket bucket
                JOIN p25_learned_site site ON site.learned_site_id=bucket.learned_site_id
                    AND site.radio_system_id=bucket.radio_system_id
                JOIN receiver_channel channel ON channel.id=bucket.channel_id
                JOIN radio_system_identity_summary identity ON identity.id=bucket.identity_summary_id
                    AND identity.radio_system_id=bucket.radio_system_id
                WHERE site.learned_site_id>2147483647 AND site.rfss=1 AND site.site=2
                    AND channel.id>2147483647 AND identity.id>2147483647
                    AND bucket.observed_call_count=1
                """));
            assertEquals(1, scalar(connection, """
                SELECT bucket.logical_call_count FROM trunked_logical_call_identity_bucket bucket
                JOIN radio_system_identity_summary identity ON identity.id=bucket.identity_summary_id
                    AND identity.radio_system_id=bucket.radio_system_id
                WHERE identity.identity_kind_code=1 AND identity.identity_id=91
                """));
            assertHealthy(connection);
        }
    }

    @Test
    void highIdentityAndEventKeysKeepDeletionScopedAndPreserveEventsAddedAfterPlanning() throws Exception
    {
        try(Connection connection = open())
        {
            ReceiverActivitySchema.recordActivity(connection, registration(1_000), true);
            ReceiverActivityDeletion.SavedChannel channel = ReceiverActivityDeletion.savedChannel(connection, CHANNEL);
            assertNotNull(channel);
            assertTrue(channel.id() > Integer.MAX_VALUE);
            long systemId = ReceiverActivityDeletion.systemId(connection, SYSTEM);
            assertTrue(systemId > Integer.MAX_VALUE);
            assertEquals(RetainedSiteKey.snapshotless(channel.id(), systemId, 1_000), channel.siteKey());

            ScopedData target = new ScopedData("radio_system", SYSTEM, null, null, "radios",
                "v1-r-bee00-954-831102", List.of("events"));
            ReceiverActivityScopedDeletion.Batch batch = ReceiverActivityScopedDeletion.Batch.start(connection,
                target, 10_000);
            assertEquals(1, batch.rowsTotal());
            //A new event can have an earlier observation time. The generated event-ID boundary still protects it.
            ReceiverActivitySchema.recordActivity(connection, registration(2_000), true);
            connection.setAutoCommit(false);
            assertEquals(1, batch.runPass(connection));
            connection.commit();
            connection.setAutoCommit(true);
            assertEquals(1, scalar(connection, "SELECT count(*) FROM receiver_activity_event"));
            assertEquals(2_000, scalar(connection, "SELECT observed_at_ms FROM receiver_activity_event"));
            assertEquals(1, scalar(connection, "SELECT count(*) FROM radio_system_identity_summary"));
            assertHealthy(connection);
        }
    }

    @Test
    void highSavedChannelKeysReachDmrCallsAndTrunkedSiteProjections() throws Exception
    {
        try(Connection connection = open())
        {
            String conventional = "223e4567-e89b-42d3-a456-426614174000";
            String trunked = "323e4567-e89b-42d3-a456-426614174000";
            for(String configurationId: List.of(conventional, trunked))
            {
                String mode = configurationId.equals(conventional) ? "CONVENTIONAL" : "TRUNKED";
                execute(connection, """
                    INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,name,
                        alias_list_id,auto_start,decoder_type,primary_frequency_hz,config_json)
                    VALUES ('%s','%s',0,'DMR',
                        (SELECT id FROM alias_list WHERE family='DMR' LIMIT 1),0,'DMR',451000000,
                        '{"decodeConfiguration":{"channelMode":"%s"}}')
                    """.formatted(configurationId, mode, mode));
            }
            Long eventId = ReceiverActivitySchema.recordDmrConventionalCall(connection,
                new ReceiverActivityRecords.DmrConventionalCall(1_000, 2_000, conventional, 451_000_000L, 1,
                    ReceiverActivityRecords.DmrTargetKind.GROUP, 91, 1001, null, false), true);
            assertNotNull(eventId);
            assertTrue(eventId > Integer.MAX_VALUE);
            assertEquals(1, scalar(connection, """
                SELECT radio.call_count FROM dmr_conventional_radio_summary radio
                JOIN receiver_channel channel ON channel.id=radio.channel_id
                JOIN receiver_activity_event event ON event.channel_id=channel.id
                WHERE channel.id>2147483647 AND radio.radio_id=1001 AND event.id>2147483647
                """));

            TrunkedSiteSchema.Snapshot snapshot = new TrunkedSiteSchema.Snapshot(3_000, trunked,
                "%064x".formatted(3_000), TrunkedSiteSchema.PROTOCOL_DMR, 1, 0, 42, null, 2, null, 2,
                null, null, null, null, null, null, 0, null, 451_000_000L, 451_000_000L,
                List.of(), List.of());
            assertTrue(ReceiverActivitySchema.ensureTrunkedSiteRadioSystem(connection, snapshot));
            assertTrue(TrunkedSiteSchema.upsert(connection, snapshot, 0));
            assertEquals(1, scalar(connection, """
                SELECT count(*) FROM trunked_site_snapshot site
                JOIN receiver_channel channel ON channel.id=site.channel_id
                JOIN radio_system system ON system.id=channel.radio_system_id
                WHERE channel.id>2147483647 AND system.id>2147483647
                    AND site.observed_network_id=42 AND site.observed_site_id=2
                """));
            assertHealthy(connection);
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
        execute(connection, """
            INSERT INTO configuration_channel(configuration_id,channel_kind,sort_order,name,
                alias_list_id,auto_start,decoder_type,primary_frequency_hz,config_json)
            VALUES ('%s','TRUNKED',0,'Control',
                (SELECT id FROM alias_list WHERE family='P25' LIMIT 1),0,'P25_PHASE1',851012500,'{}')
            """.formatted(CHANNEL));
        for(String table: List.of("receiver_channel", "radio_system", "radio_system_identity_summary",
            "receiver_activity_event"))
        {
            execute(connection, "INSERT INTO sqlite_sequence(name,seq) VALUES ('" + table + "'," + HIGH_KEY + ")");
        }
        execute(connection, """
            INSERT INTO p25_subscriber_identity(id,home_wacn,home_system_id,subscriber_id)
            VALUES (%d,0xBEE00,0x954,831102)
            """.formatted(HIGH_KEY));
        return connection;
    }

    private static ReceiverActivityRecords.ActivityEvent registration(long observedAt)
    {
        ReceiverActivityRecords.RadioPresenceUpdate presence = ReceiverActivityRecords.RadioPresenceUpdate.confirmed(
            WORKING_ID, null, ReceiverActivityRecords.RadioPresenceEvidence.REGISTRATION, RADIO,
            ReceiverActivityRecords.P25Identity.UNKNOWN);
        return new ReceiverActivityRecords.ActivityEvent(observedAt, CHANNEL,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "APCO25", ReceiverActivityRecords.Action.REGISTER,
            "REGISTER", Integer.toString(WORKING_ID), null, null, List.of(), 851_012_500L, "0-1", 1,
            false, null, null, 0xBEE00, 0x3A9, null, null, null, null, false, null, presence,
            TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.UNKNOWN, RADIO, List.of(), null,
            WORKING_ID, null, new ReceiverActivityRecords.P25WuidObservation(0xBEE00, 0x3A9, WORKING_ID,
                SUBSCRIBER, observedAt + 60_000, ReceiverActivityRecords.RadioPresenceEvidence.REGISTRATION));
    }

    private static long scalar(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql))
        {
            assertTrue(rows.next());
            return rows.getLong(1);
        }
    }

    private static void execute(Connection connection, String sql) throws Exception
    {
        try(Statement statement = connection.createStatement())
        {
            statement.execute(sql);
        }
    }

    private static void assertHealthy(Connection connection) throws Exception
    {
        ReceiverActivitySchema.validate(connection);
        assertEquals(0, scalar(connection, "SELECT count(*) FROM pragma_foreign_key_check"));
        try(Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery("PRAGMA quick_check"))
        {
            assertTrue(rows.next());
            assertEquals("ok", rows.getString(1));
        }
    }
}
