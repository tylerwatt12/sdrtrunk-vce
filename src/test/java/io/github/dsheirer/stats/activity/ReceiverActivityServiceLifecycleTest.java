/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.configuration.ChannelAndBroadcastConfiguration;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.ChannelConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.DecoderTypeConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.FrequencyConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.RadioResolveConfigurationIdentifier;
import io.github.dsheirer.metadata.site.ProtocolSiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.channel.DMRTier3Channel;
import io.github.dsheirer.module.decode.dmr.channel.TimeslotFrequency;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.telemetry.DMRNetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNChannelMode;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelLookup;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.P25CallStartEvent;
import io.github.dsheirer.module.decode.p25.P25ChannelGrantEvent;
import io.github.dsheirer.module.decode.p25.P25GrantObservationEvent;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelConfirmationEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Nac;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Rfss;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Site;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.channel.P25Channel;
import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.module.decode.p25.identifier.patch.APCO25PatchGroup;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartTracker;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import io.github.dsheirer.stats.site.TrunkedSiteSchema;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReceiverActivityServiceLifecycleTest
{
    private static final String ACTIVITY_CONFIGURATION_ID = "123e4567-e89b-12d3-a456-426614174000";
    private static final String LEARNED_P25_CONFIGURATION_ID = "00000000-0000-0000-0000-000000000902";
    private static final ReceiverActivityService.WriterFactory FAST_WRITER_FACTORY =
        (databasePath, retentionDays, detailedHistory) ->
            new ReceiverActivityWriter(databasePath, retentionDays, detailedHistory, 10_000, 1_250, 25);

    @TempDir
    Path mTemporaryFolder;

    @Test
    void shutdownBarrierPersistsImmediatelyAcceptedLogicalCallAndOutputsOnce() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        persistChannels(database, p25TrunkedChannel(LEARNED_P25_CONFIGURATION_ID));
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = new ReceiverActivityService(userPreferences);
        CompletedAudioCall call = learnedP25TrafficCall(8_001L, System.currentTimeMillis());

        try
        {
            service.receiveResolvedCall(call);
            service.receiveResolvedCall(call);
            service.receiveRecordedCall(call);
            service.receiveStreamedCall(call);
            service.receiveRecordedCall(call);
            service.receiveStreamedCall(call);

            assertTrue(service.awaitObservationDrain(5, TimeUnit.SECONDS),
                "the shutdown barrier must follow all accepted logical-call notifications");
            disposeAndAwait(service);

            assertEquals(1L, scalar(database,
                "SELECT COALESCE(SUM(logical_call_count), 0) FROM trunked_logical_call_bucket"));
            assertEquals(1L, scalar(database,
                "SELECT COALESCE(SUM(recorded_output_count), 0) FROM trunked_logical_call_bucket"));
            assertEquals(1L, scalar(database,
                "SELECT COALESCE(SUM(streamed_output_count), 0) FROM trunked_logical_call_bucket"));
            assertEquals(1L, scalar(database,
                "SELECT COALESCE(SUM(observed_call_count), 0) FROM p25_site_call_bucket"));
        }
        finally
        {
            disposeAndAwait(service);
        }
    }

    @Test
    void preferenceNotificationAfterDisposeCannotRestartTheWriter() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = fastWriterService(userPreferences);

        disposeAndAwait(service);
        service.preferenceUpdated(PreferenceType.APPLICATION);

        assertEquals(ReceiverActivityStatus.State.STOPPED, service.getStatus().state());
        assertFalse(service.getStatus().summaryActive());
    }

    @Test
    void blockedStatisticsProjectionNeverBlocksTheDecoderCallback() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = fastWriterService(userPreferences);
        Channel channel = new Channel("Observer isolation", Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicReference<Thread> projectionThread = new AtomicReference<>();
        DecodeEvent blocked = new DecodeEvent(DecodeEventType.CALL, System.currentTimeMillis())
        {
            @Override
            public DecodeEventType getEventType()
            {
                projectionThread.compareAndSet(null, Thread.currentThread());
                projectionEntered.countDown();

                try
                {
                    releaseProjection.await(3, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return super.getEventType();
            }
        };
        DecodeEvent ordinary = DecodeEvent.builder(DecodeEventType.CALL, System.currentTimeMillis()).build();
        Thread decoderThread = Thread.currentThread();

        try
        {
            service.getDecodeEventListener().accept(channel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();

            for(int x = 0; x < ReceiverActivityService.OBSERVATION_QUEUE_SIZE + 16; x++)
            {
                service.getDecodeEventListener().accept(channel, ordinary);
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 500, "bounded offers took " + elapsedMs + " ms");
            assertTrue(service.getObservationDropCount() > 0);
            assertFalse(decoderThread == projectionThread.get());
        }
        finally
        {
            releaseProjection.countDown();
            disposeAndAwait(service);
        }
    }

    @Test
    void queuedP25CallStartKeepsItsOriginalFactsWhenTheLiveTrackerChanges() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        Channel blockerChannel = nbfmChannel("00000000-0000-0000-0000-000000000311");
        Channel p25Channel = p25TrunkedChannel("00000000-0000-0000-0000-000000000312");
        p25Channel.setP25SiteIdentity(new P25SiteIdentity(0xBEE00, 0x3A9, 1, 1));
        persistChannels(database, blockerChannel, p25Channel);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = fastWriterService(userPreferences);
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        long start = System.currentTimeMillis();
        DecodeEvent blocked = blockingDecodeEvent(start - 100L, projectionEntered, releaseProjection);
        MutableIdentifierCollection originalIdentifiers = new MutableIdentifierCollection();
        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(1_201));
        patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(1_202));
        originalIdentifiers.update(APCO25PatchGroup.create(patchGroup));
        originalIdentifiers.update(APCO25RadioIdentifier.createFrom(1_234_567));
        APCO25Channel callDescriptor = descriptorThatRejectsProducerStringProjection(0, 1);
        callDescriptor.setFrequencyBand(new P25FrequencyBand(0, 851_000_000L, -45_000_000L, 12_500L,
            12_500, 1));
        originalIdentifiers.update(callDescriptor);
        P25ChannelGrantEvent liveEvent = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, start,
                VoiceServiceOptions.createUnencrypted())
            .channelDescriptor(callDescriptor)
            .identifiers(originalIdentifiers)
            .build();
        P25CallStartEvent callStart = new P25CallStartEvent(p25Channel, liveEvent);
        APCO25PatchGroup exposedListPatch = (APCO25PatchGroup)callStart.identifiers().stream()
            .filter(APCO25PatchGroup.class::isInstance)
            .findFirst()
            .orElseThrow();
        exposedListPatch.getValue().addPatchedTalkgroup(APCO25Talkgroup.create(1_204));
        APCO25PatchGroup exposedCollectionPatch = (APCO25PatchGroup)callStart.identifierCollection().getIdentifiers()
            .stream()
            .filter(APCO25PatchGroup.class::isInstance)
            .findFirst()
            .orElseThrow();
        exposedCollectionPatch.getValue().addPatchedTalkgroup(APCO25Talkgroup.create(1_205));

        try
        {
            service.getDecodeEventListener().accept(blockerChannel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            service.receiveCallStart(callStart);

            patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(1_203));
            callDescriptor.clearFrequencyBands();
            MutableIdentifierCollection laterIdentifiers = new MutableIdentifierCollection();
            laterIdentifiers.update(APCO25Talkgroup.create(9_999));
            laterIdentifiers.update(APCO25RadioIdentifier.createFrom(7_654_321));
            liveEvent.setIdentifierCollection(laterIdentifiers);
            liveEvent.setChannelDescriptor(new StandardChannel(859_987_500L));
            liveEvent.end(start + 5_000L);

            releaseProjection.countDown();
            assertTrue(service.awaitObservationDrain(5, TimeUnit.SECONDS));
            awaitScalar(database, """
                SELECT COUNT(*) FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000312'
                """, 1);
            assertEquals(start, scalar(database, """
                SELECT event.observed_at_ms FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000312'
                """));
            assertEquals(1_201, scalar(database, """
                SELECT event.target_observed_local_id FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000312'
                """));
            assertEquals(1_234_567, scalar(database, """
                SELECT event.source_observed_local_id FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000312'
                """));
            assertEquals(851_012_500L, scalar(database, """
                SELECT event.frequency_hz FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000312'
                """));
            assertEquals(1, scalar(database, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE identity_kind_code=1 AND identity_id=1202
                """));
            assertEquals(0, scalar(database, """
                SELECT COUNT(*) FROM radio_system_identity_summary
                WHERE identity_kind_code=1 AND identity_id IN (1203, 1204, 1205)
                """));
        }
        finally
        {
            releaseProjection.countDown();
            disposeAndAwait(service);
        }
    }

    @Test
    void queuedSiteMetadataCannotReassignAReceiverAfterItsSavedSourceChanges() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        String p25ConfigurationId = "00000000-0000-0000-0000-000000000321";
        String dmrConfigurationId = "00000000-0000-0000-0000-000000000322";
        Channel blockerChannel = nbfmChannel("00000000-0000-0000-0000-000000000323");
        Channel p25Channel = p25TrunkedChannel(p25ConfigurationId);
        Channel dmrChannel = dmrTrunkedChannel(dmrConfigurationId);
        SourceConfigTuner p25Source = tuner(851_012_500L);
        SourceConfigTuner dmrSource = tuner(451_012_500L);
        p25Channel.setSourceConfiguration(p25Source);
        dmrChannel.setSourceConfiguration(dmrSource);
        persistChannels(database, blockerChannel, p25Channel, dmrChannel);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = fastWriterService(userPreferences);
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        long now = System.currentTimeMillis();
        DecodeEvent blocked = blockingDecodeEvent(now - 100L, projectionEntered, releaseProjection);
        P25NetworkConfigurationSnapshot oldP25 = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(0xBEE00, 0x3A9, 0x293, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(0x3A9, 0x293, 1, 1, null, true),
            List.of(new P25NetworkConfigurationSnapshot.Channel("primary_control", null, 851_012_500L,
                null, false, 1)), List.of(), List.of(), List.of(), List.of());
        SiteMetadataEvent staleP25 = new SiteMetadataEvent(p25Channel, oldP25, now, 851_012_500L);
        ProtocolSiteMetadataEvent staleDmr = new ProtocolSiteMetadataEvent(dmrChannel,
            new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 10, 20, "Tier III Trunking",
                "SMALL", null, "Control", 1, 2, List.of(), List.of()), now, 451_012_500L);

        try
        {
            service.getDecodeEventListener().accept(blockerChannel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            service.receiveSiteMetadata(staleP25);
            service.receiveProtocolSiteMetadata(staleDmr);

            p25Source.setFrequency(852_012_500L);
            dmrSource.setFrequency(452_012_500L);
            persistChannels(database, blockerChannel, p25Channel, dmrChannel);
            releaseProjection.countDown();
            assertTrue(service.awaitObservationDrain(5, TimeUnit.SECONDS));
            StatsDatabaseMaintenanceRequest staleBarrier =
                StatsDatabaseMaintenanceRequest.forOperation(ReceiverActivityMaintenance.Operation.CHECK);
            service.receiveMaintenanceRequest(staleBarrier);
            assertTrue(staleBarrier.result().get(5, TimeUnit.SECONDS).checkOk());

            assertEquals(0, count(database, "p25_site_snapshot"));
            assertEquals(0, count(database, "trunked_site_snapshot"));
            assertEquals(0, scalar(database, """
                SELECT COUNT(*) FROM receiver_channel
                WHERE configuration_id IN (
                    '00000000-0000-0000-0000-000000000321',
                    '00000000-0000-0000-0000-000000000322')
                  AND radio_system_id IS NOT NULL
                """));

            P25NetworkConfigurationSnapshot currentP25 = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
                new P25NetworkConfigurationSnapshot.Network(0xABCDE, 0x123, 0x124, null),
                new P25NetworkConfigurationSnapshot.CurrentSite(0x123, 0x124, 2, 3, null, true),
                List.of(new P25NetworkConfigurationSnapshot.Channel("primary_control", null, 852_012_500L,
                    null, false, 1)), List.of(), List.of(), List.of(), List.of());
            service.receiveSiteMetadata(new SiteMetadataEvent(p25Channel, currentP25, now + 1_000L,
                852_012_500L));
            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(dmrChannel,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 30, 40, "Tier III Trunking",
                    "SMALL", null, "Control", 1, 2, List.of(), List.of()), now + 1_000L,
                452_012_500L));
            assertTrue(service.awaitObservationDrain(5, TimeUnit.SECONDS));
            StatsDatabaseMaintenanceRequest currentBarrier =
                StatsDatabaseMaintenanceRequest.forOperation(ReceiverActivityMaintenance.Operation.CHECK);
            service.receiveMaintenanceRequest(currentBarrier);
            assertTrue(currentBarrier.result().get(5, TimeUnit.SECONDS).checkOk());

            assertEquals(1, count(database, "p25_site_snapshot"));
            assertEquals(1, count(database, "trunked_site_snapshot"));
            assertEquals("p25:abcde:123", scalarText(database, """
                SELECT system.system_key
                FROM receiver_channel channel
                JOIN radio_system system ON system.id=channel.radio_system_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000321'
                """));
            assertEquals(30, scalar(database, """
                SELECT site.observed_network_id
                FROM trunked_site_snapshot site
                JOIN receiver_channel channel ON channel.id=site.channel_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000322'
                """));
            assertEquals(40, scalar(database, """
                SELECT site.observed_site_id
                FROM trunked_site_snapshot site
                JOIN receiver_channel channel ON channel.id=site.channel_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000322'
                """));
        }
        finally
        {
            releaseProjection.countDown();
            disposeAndAwait(service);
        }
    }

    @Test
    void queuedP25GrantAndTrafficConfirmationKeepProducerTimeFactsWhenEverySourceChanges() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        String p25ConfigurationId = "00000000-0000-0000-0000-000000000318";
        Channel blockerChannel = nbfmChannel("00000000-0000-0000-0000-000000000319");
        Channel p25Channel = p25TrunkedChannel(p25ConfigurationId);
        P25SiteIdentity originalSite = new P25SiteIdentity(0xBEE00, 0x3A9, 1, 1);
        p25Channel.setP25SiteIdentity(originalSite);
        persistChannels(database, blockerChannel, p25Channel);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = fastWriterService(userPreferences);
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        long start = System.currentTimeMillis();
        long controlFrequency = 851_006_250L;
        DecodeEvent blocked = blockingDecodeEvent(start - 100L, projectionEntered, releaseProjection);
        P25NetworkConfigurationSnapshot siteSnapshot = new P25NetworkConfigurationSnapshot("P25_PHASE_1",
            new P25NetworkConfigurationSnapshot.Network(originalSite.wacn(), originalSite.system(), 0x293, null),
            new P25NetworkConfigurationSnapshot.CurrentSite(originalSite.system(), 0x293, originalSite.rfss(),
                originalSite.site(), null, true),
            List.of(new P25NetworkConfigurationSnapshot.Channel("primary_control", null, controlFrequency, null,
                false, 1)), List.of(), List.of(), List.of(), List.of());

        APCO25Channel descriptor = descriptorThatRejectsProducerStringProjection(0, 509);
        descriptor.setFrequencyBand(new P25FrequencyBand(0, controlFrequency, -45_000_000L, 12_500L, 12_500, 2));
        long grantFrequency = descriptor.getDownlinkFrequency();
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        PatchGroup patchGroup = new PatchGroup(APCO25Talkgroup.create(56_138));
        patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(56_139));
        APCO25PatchGroup patchIdentifier = APCO25PatchGroup.create(patchGroup);
        identifiers.update(patchIdentifier);
        identifiers.update(APCO25RadioIdentifier.createFrom(1_811_524));
        identifiers.update(APCO25Wacn.create(originalSite.wacn()));
        identifiers.update(APCO25System.create(originalSite.system()));
        identifiers.update(APCO25Nac.create(0x293));
        identifiers.update(APCO25Rfss.create(originalSite.rfss()));
        identifiers.update(APCO25Site.create(originalSite.site()));
        identifiers.update(descriptor);
        identifiers.setTimeslot(2);
        P25GrantObservationEvent grant = new P25GrantObservationEvent(p25Channel, descriptor, identifiers,
            DecodeEventType.CALL_GROUP, start, false, true);
        P25TrafficChannelConfirmationEvent confirmation = new P25TrafficChannelConfirmationEvent(p25Channel,
            grantFrequency, 2, start + 1L);

        try
        {
            service.receiveSiteMetadata(new SiteMetadataEvent(p25Channel, siteSnapshot, start - 200L,
                controlFrequency));
            assertTrue(service.awaitObservationDrain(5, TimeUnit.SECONDS));
            awaitScalar(database, "SELECT COUNT(*) FROM p25_site_snapshot", 1);

            service.getDecodeEventListener().accept(blockerChannel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            service.receiveGrantObservation(grant);
            service.receiveTrafficChannelConfirmation(confirmation);

            p25Channel.setConfigurationId("00000000-0000-0000-0000-000000000320");
            p25Channel.setDecodeConfiguration(new DecodeConfigNBFM());
            p25Channel.setP25SiteIdentity(new P25SiteIdentity(0xABCDE, 0x123, 9, 9));
            descriptor.clearFrequencyBands();
            patchGroup.addPatchedTalkgroup(APCO25Talkgroup.create(56_140));
            identifiers.remove(patchIdentifier);
            identifiers.update(APCO25Talkgroup.create(9_999));
            identifiers.update(APCO25RadioIdentifier.createFrom(7_654_321));
            identifiers.update(APCO25Wacn.create(0xABCDE));
            identifiers.update(APCO25System.create(0x123));
            identifiers.update(APCO25Nac.create(0x123));
            identifiers.update(APCO25Rfss.create(9));
            identifiers.update(APCO25Site.create(9));
            identifiers.setTimeslot(2);

            releaseProjection.countDown();
            assertTrue(service.awaitObservationDrain(5, TimeUnit.SECONDS));
            awaitScalar(database, """
                SELECT COUNT(*) FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000318'
                """, 1);
            awaitScalar(database, "SELECT COUNT(*) FROM p25_site_channel_summary WHERE channel_key='0-508'", 1);

            String grantRow = " FROM receiver_activity_event event JOIN receiver_channel channel " +
                "ON channel.id=event.channel_id WHERE " +
                "channel.configuration_id='00000000-0000-0000-0000-000000000318'";
            assertEquals(start, scalar(database, "SELECT event.observed_at_ms" + grantRow));
            assertEquals(56_138, scalar(database, "SELECT event.target_observed_local_id" + grantRow));
            assertEquals(1_811_524, scalar(database, "SELECT event.source_observed_local_id" + grantRow));
            assertEquals(grantFrequency, scalar(database, "SELECT event.frequency_hz" + grantRow));
            assertEquals(0, scalar(database, "SELECT event.lcn_band" + grantRow));
            assertEquals(508, scalar(database, "SELECT event.lcn_number" + grantRow));
            assertEquals(2, scalar(database, "SELECT event.timeslot" + grantRow));
            assertEquals("p25:bee00:3a9", scalarText(database, "SELECT system_key FROM radio_system"));
            assertEquals(grantFrequency, scalar(database, "SELECT downlink_hz FROM p25_site_channel_summary " +
                "WHERE channel_key='0-508'"));
            assertEquals(1, scalar(database, "SELECT COUNT(*) FROM radio_system_identity_summary " +
                "WHERE identity_kind_code=1 AND identity_id=56139"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM radio_system_identity_summary " +
                "WHERE identity_kind_code=1 AND identity_id=56140"));
            assertEquals(0, scalar(database, "SELECT COUNT(*) FROM receiver_channel channel " +
                "WHERE channel.configuration_id='00000000-0000-0000-0000-000000000320'"));
        }
        finally
        {
            releaseProjection.countDown();
            disposeAndAwait(service);
        }
    }

    @Test
    void queuedDmrAndNxdnCallFactsDoNotFollowMutableDecoderObjects() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        String dmrConfigurationId = "00000000-0000-0000-0000-000000000313";
        String nxdnConfigurationId = "00000000-0000-0000-0000-000000000314";
        Channel blockerChannel = nbfmChannel("00000000-0000-0000-0000-000000000315");
        Channel dmrChannel = dmrTrunkedChannel(dmrConfigurationId);
        Channel nxdnChannel = nxdnTrunkedChannel(nxdnConfigurationId);
        persistChannels(database, blockerChannel, dmrChannel, nxdnChannel);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = fastWriterService(userPreferences);
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        long start = System.currentTimeMillis();
        DecodeEvent blocked = blockingDecodeEvent(start - 100L, projectionEntered, releaseProjection);

        DMRTier3Channel dmrResource = new DMRTier3Channel(12, 2);
        TimeslotFrequency dmrMapping = new TimeslotFrequency();
        dmrMapping.setNumber(12);
        dmrMapping.setDownlinkFrequency(451_012_500L);
        dmrResource.setTimeslotFrequency(dmrMapping);
        MutableIdentifierCollection dmrIdentifiers = new MutableIdentifierCollection();
        dmrIdentifiers.update(DMRRadio.createFrom(101));
        dmrIdentifiers.update(DMRTalkgroup.create(91));
        TrunkedCallStartTracker dmrTracker = new TrunkedCallStartTracker(5_000L);
        var dmrStart = dmrTracker.observe(dmrChannel, Protocol.DMR, dmrResource, 2, dmrIdentifiers,
            DecodeEventType.CALL_GROUP, start);

        NXDNChannelLookup nxdnResource = new NXDNChannelLookup(34);
        nxdnResource.receive(null, Map.of(34, new ChannelFrequency(34, 452_012_500L, 0)));
        TrunkedCallStartTracker nxdnTracker = new TrunkedCallStartTracker(5_000L);
        var nxdnStart = nxdnTracker.observeWithAttribution(nxdnChannel, Protocol.NXDN, nxdnResource, null,
            new MutableIdentifierCollection(), DecodeEventType.CALL_GROUP, start + 10L);
        MutableIdentifierCollection nxdnIdentifiers = new MutableIdentifierCollection();
        NXDNRadioIdentifier nxdnRadio = NXDNRadioIdentifier.createFrom(201);
        NXDNTalkgroupIdentifier nxdnTalkgroup = NXDNTalkgroupIdentifier.createTo(191);
        nxdnIdentifiers.update(nxdnRadio);
        nxdnIdentifiers.update(nxdnTalkgroup);
        var nxdnEnrichment = nxdnTracker.observeWithAttribution(nxdnChannel, Protocol.NXDN, nxdnResource, null,
            nxdnIdentifiers, DecodeEventType.CALL_GROUP, start + 20L);

        try
        {
            service.getDecodeEventListener().accept(blockerChannel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            service.receiveTrunkedCallStart(dmrStart);
            service.receiveTrunkedCallStart(nxdnStart.callStart());
            service.receiveTrunkedCallAttribution(nxdnEnrichment.attribution());

            dmrIdentifiers.update(DMRRadio.createFrom(999));
            dmrIdentifiers.update(DMRTalkgroup.create(998));
            TimeslotFrequency changedDmrMapping = new TimeslotFrequency();
            changedDmrMapping.setNumber(12);
            changedDmrMapping.setDownlinkFrequency(459_987_500L);
            dmrResource.setTimeslotFrequency(changedDmrMapping);
            nxdnRadio.setTypeD(true);
            nxdnTalkgroup.setTypeD(true);
            nxdnResource.receive(null, Map.of(34, new ChannelFrequency(34, 460_987_500L, 0)));
            dmrChannel.setConfigurationId("00000000-0000-0000-0000-000000000316");
            dmrChannel.setDecodeConfiguration(new DecodeConfigNBFM());
            nxdnChannel.setConfigurationId("00000000-0000-0000-0000-000000000317");
            nxdnChannel.setDecodeConfiguration(new DecodeConfigNBFM());

            releaseProjection.countDown();
            assertTrue(service.awaitObservationDrain(5, TimeUnit.SECONDS));
            awaitScalar(database, """
                SELECT COUNT(*) FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                WHERE channel.configuration_id IN (
                    '00000000-0000-0000-0000-000000000313',
                    '00000000-0000-0000-0000-000000000314')
                """, 2);
            assertEquals(101, scalar(database, """
                SELECT event.source_observed_local_id FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000313'
                """));
            assertEquals(91, scalar(database, """
                SELECT event.target_observed_local_id FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000313'
                """));
            assertEquals(451_012_500L, scalar(database, """
                SELECT event.frequency_hz FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000313'
                """));
            assertEquals(201, scalar(database, """
                SELECT event.source_observed_local_id FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000314'
                """));
            assertEquals(191, scalar(database, """
                SELECT event.target_observed_local_id FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000314'
                """));
            assertEquals(452_012_500L, scalar(database, """
                SELECT event.frequency_hz FROM receiver_activity_event event
                JOIN receiver_channel channel ON channel.id=event.channel_id
                WHERE channel.configuration_id='00000000-0000-0000-0000-000000000314'
                """));
        }
        finally
        {
            releaseProjection.countDown();
            disposeAndAwait(service);
        }
    }

    @Test
    void observationDrainBarrierHonorsTotalTimeoutWhenHandoffIsFull() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = fastWriterService(userPreferences);
        Channel channel = new Channel("Drain barrier saturation", Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        DecodeEvent blocked = blockingDecodeEvent(System.currentTimeMillis(), projectionEntered, releaseProjection);
        DecodeEvent filler = DecodeEvent.builder(DecodeEventType.CALL, System.currentTimeMillis() + 1).build();

        try
        {
            service.getDecodeEventListener().accept(channel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));

            for(int index = 0; index < ReceiverActivityService.OBSERVATION_QUEUE_SIZE; index++)
            {
                service.getDecodeEventListener().accept(channel, filler);
            }

            assertEquals(ReceiverActivityService.OBSERVATION_QUEUE_SIZE, service.getPendingObservationCount());
            long startedNanos = System.nanoTime();
            assertFalse(service.awaitObservationDrain(25, TimeUnit.MILLISECONDS),
                "a full handoff must time out instead of accepting an unsequenced barrier");
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
            assertTrue(elapsedMillis < 1_000,
                "the 25 ms drain timeout took " + elapsedMillis + " ms while the handoff stayed full");
        }
        finally
        {
            releaseProjection.countDown();
            disposeAndAwait(service);
        }
    }

    @Test
    void interruptedObservationDrainPreservesCallerInterruptFlag() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = fastWriterService(userPreferences);
        Channel channel = new Channel("Interrupted drain barrier", Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        DecodeEvent blocked = blockingDecodeEvent(System.currentTimeMillis(), projectionEntered, releaseProjection);
        AtomicReference<Boolean> drainResult = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        CountDownLatch drainReturned = new CountDownLatch(1);
        Thread drainCaller = new Thread(() -> {
            drainResult.set(service.awaitObservationDrain(5, TimeUnit.SECONDS));
            interruptPreserved.set(Thread.currentThread().isInterrupted());
            drainReturned.countDown();
        }, "interrupted statistics drain caller");

        try
        {
            service.getDecodeEventListener().accept(channel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            drainCaller.start();
            awaitPendingObservationCount(service, 1);

            drainCaller.interrupt();
            assertTrue(drainReturned.await(1, TimeUnit.SECONDS),
                "the interrupted drain caller did not return promptly");
            assertEquals(Boolean.FALSE, drainResult.get());
            assertTrue(interruptPreserved.get(), "awaitObservationDrain must restore the interrupt flag");
        }
        finally
        {
            drainCaller.interrupt();
            releaseProjection.countDown();
            drainCaller.join(TimeUnit.SECONDS.toMillis(2));
            disposeAndAwait(service);
        }
    }

    @Test
    void observationEpochChangeCannotFalselyCompleteRetiredDrainBarrier() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = fastWriterService(userPreferences);
        Channel channel = new Channel("Retired drain epoch", Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        DecodeEvent blocked = blockingDecodeEvent(System.currentTimeMillis(), projectionEntered, releaseProjection);
        AtomicReference<Boolean> drainResult = new AtomicReference<>();
        CountDownLatch drainReturned = new CountDownLatch(1);
        Thread drainCaller = new Thread(() -> {
            drainResult.set(service.awaitObservationDrain(500, TimeUnit.MILLISECONDS));
            drainReturned.countDown();
        }, "retired statistics drain caller");

        try
        {
            service.getDecodeEventListener().accept(channel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            var retiredIngress = service.getObservationIngressForTest();
            drainCaller.start();
            awaitPendingObservationCount(service, 1);

            applicationPreference.setCollectionEnabled(false);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            applicationPreference.setCollectionEnabled(true);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            assertFalse(retiredIngress == service.getObservationIngressForTest(),
                "disable/re-enable must publish a distinct observation epoch");
            releaseProjection.countDown();

            assertTrue(drainReturned.await(2, TimeUnit.SECONDS), "the retired drain barrier did not time out");
            assertEquals(Boolean.FALSE, drainResult.get(),
                "a barrier from a retired collection epoch must never report a successful drain");
        }
        finally
        {
            drainCaller.interrupt();
            releaseProjection.countDown();
            drainCaller.join(TimeUnit.SECONDS.toMillis(2));
            disposeAndAwait(service);
        }
    }

    @Test
    void disposalTimeoutDoesNotWaitForServiceMonitorHeldDuringWriterClose() throws Exception
    {
        Path firstDatabase = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        Path secondRoot = mTemporaryFolder.resolve("monitor-held-writer-close");
        Path secondDatabase = SdrTrunkDatabasePath.getDatabasePath(secondRoot);
        SdrTrunkTestDatabase.create(firstDatabase);
        SdrTrunkTestDatabase.create(secondDatabase);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestDirectoryPreference directoryPreference = new TestDirectoryPreference(mTemporaryFolder);
        TestUserPreferences userPreferences = new TestUserPreferences(applicationPreference, directoryPreference);
        MonitorHeldCloseWriter initialWriter = new MonitorHeldCloseWriter(firstDatabase, 30, true);
        AtomicInteger writerCount = new AtomicInteger();
        ReceiverActivityService.WriterFactory writerFactory = (databasePath, retentionDays, detailedHistory) ->
            writerCount.getAndIncrement() == 0 ? initialWriter :
                new ReceiverActivityWriter(databasePath, retentionDays, detailedHistory);
        ReceiverActivityService service = new ReceiverActivityService(userPreferences, 2, TimeUnit.SECONDS,
            null, null, writerFactory);
        AtomicReference<Boolean> disposeResult = new AtomicReference<>();
        AtomicLong disposeElapsedMillis = new AtomicLong(Long.MAX_VALUE);
        CountDownLatch disposeStarted = new CountDownLatch(1);
        CountDownLatch disposeReturned = new CountDownLatch(1);
        Thread disposeCaller = new Thread(() -> {
            disposeStarted.countDown();
            long startedNanos = System.nanoTime();
            disposeResult.set(service.disposeAndAwait(25, TimeUnit.MILLISECONDS));
            disposeElapsedMillis.set(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
            disposeReturned.countDown();
        }, "monitor-independent statistics disposer");

        try
        {
            directoryPreference.setRoot(secondRoot);
            service.preferenceUpdated(PreferenceType.DIRECTORY);
            assertTrue(initialWriter.awaitCloseEntered(2, TimeUnit.SECONDS),
                "writer transition did not enter the monitor-held close");

            disposeCaller.start();
            assertTrue(disposeStarted.await(1, TimeUnit.SECONDS), "disposal caller did not start");
            boolean returnedWithinBound = disposeReturned.await(750, TimeUnit.MILLISECONDS);
            initialWriter.releaseClose();
            disposeCaller.join(TimeUnit.SECONDS.toMillis(2));

            assertTrue(returnedWithinBound,
                "disposeAndAwait blocked on the service monitor instead of honoring its total timeout");
            assertFalse(disposeCaller.isAlive());
            assertEquals(Boolean.FALSE, disposeResult.get());
            assertTrue(disposeElapsedMillis.get() < 750,
                "the 25 ms disposal timeout took " + disposeElapsedMillis.get() + " ms");
            assertTrue(service.disposeAndAwait(2, TimeUnit.SECONDS),
                "disposal must finish after the monitor-held writer close is released");
        }
        finally
        {
            initialWriter.releaseClose();
            disposeCaller.join(TimeUnit.SECONDS.toMillis(2));
            disposeAndAwait(service);
        }
    }

    @Test
    void outputAfterDroppedResolvedNotificationCannotCreateOrphanTotals() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = fastWriterService(userPreferences);
        Channel blockerChannel = new Channel("Logical output saturation", Channel.ChannelType.STANDARD);
        blockerChannel.setDecodeConfiguration(new DecodeConfigNBFM());
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        DecodeEvent blocked = new DecodeEvent(DecodeEventType.CALL, System.currentTimeMillis())
        {
            @Override
            public DecodeEventType getEventType()
            {
                projectionEntered.countDown();
                try
                {
                    releaseProjection.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
                return super.getEventType();
            }
        };
        DecodeEvent filler = DecodeEvent.builder(DecodeEventType.CALL, System.currentTimeMillis()).build();
        CompletedAudioCall call = uncertainP25TrafficCall(9001, System.currentTimeMillis());

        try
        {
            service.getDecodeEventListener().accept(blockerChannel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            for(int index = 0; index < ReceiverActivityService.OBSERVATION_QUEUE_SIZE; index++)
            {
                service.getDecodeEventListener().accept(blockerChannel, filler);
            }
            long dropsBefore = service.getObservationDropCount();
            service.receiveResolvedCall(call);
            assertTrue(service.getObservationDropCount() > dropsBefore,
                "the full observer queue must drop the resolved notification");

            releaseProjection.countDown();
            awaitObservationQueueEmpty(service);
            service.receiveRecordedCall(call);
            awaitObservationQueueEmpty(service);
            disposeAndAwait(service);

            assertEquals(0, scalar(database,
                "SELECT COUNT(*) FROM trunked_logical_call_bucket"));
            assertEquals(0, scalar(database,
                "SELECT COALESCE(SUM(recorded_output_count), 0) FROM trunked_logical_call_bucket"));
        }
        finally
        {
            releaseProjection.countDown();
            disposeAndAwait(service);
        }
    }

    @Test
    void unresolvedP25CallWithoutNetworkIdentityIsIgnoredCleanly()
    {
        assertNull(new ReceiverActivityMapper().mapResolvedLogicalCall(
            uncertainP25TrafficCall(9_002L, System.currentTimeMillis())));
    }

    @Test
    void blockedProjectionDisposeLeavesQueuedCleanupToWorker() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = new ReceiverActivityService(userPreferences, 25, TimeUnit.MILLISECONDS);
        Channel channel = new Channel("Observer shutdown", Channel.ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        DecodeEvent blocked = new DecodeEvent(DecodeEventType.CALL, System.currentTimeMillis())
        {
            @Override
            public DecodeEventType getEventType()
            {
                projectionEntered.countDown();

                try
                {
                    releaseProjection.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return super.getEventType();
            }
        };
        DecodeEvent queued = DecodeEvent.builder(DecodeEventType.CALL, System.currentTimeMillis() + 1).build();
        try
        {
            service.getDecodeEventListener().accept(channel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            service.getDecodeEventListener().accept(channel, queued);
            assertEquals(1, service.getPendingObservationCount());
            var retiredEpoch = service.getObservationIngressForTest();

            service.dispose();
            assertFalse(service.isObservationWorkerTerminated());
            assertEquals(0, service.getPendingObservationCount());
            assertEquals(1, retiredEpoch.size(),
                "dispose must abandon the old epoch instead of consuming it on the caller thread");

            releaseProjection.countDown();
            assertTrue(service.disposeAndAwait(8, TimeUnit.SECONDS),
                "a repeated bounded disposal wait must include the owned SQLite writer");
            assertEquals(0, service.getPendingObservationCount());
            service.getDecodeEventListener().accept(channel, queued);
            assertEquals(0, service.getPendingObservationCount());
        }
        finally
        {
            releaseProjection.countDown();
            disposeAndAwait(service);
        }
    }

    @Test
    void callbackPausedAcrossDisableAndReenableCannotEnterTheNewObservationEpoch() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        Channel channel = nbfmChannel("00000000-0000-0000-0000-000000000305");
        persistChannels(database, channel);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        CountDownLatch activeEpochCaptured = new CountDownLatch(1);
        CountDownLatch disabledEpochCaptured = new CountDownLatch(1);
        CountDownLatch releaseActiveProducer = new CountDownLatch(1);
        CountDownLatch releaseDisabledProducer = new CountDownLatch(1);
        AtomicInteger producerSnapshot = new AtomicInteger();
        Runnable pauseAfterSnapshot = () -> {
            int snapshot = producerSnapshot.incrementAndGet();
            CountDownLatch captured = snapshot == 1 ? activeEpochCaptured :
                snapshot == 2 ? disabledEpochCaptured : null;
            CountDownLatch release = snapshot == 1 ? releaseActiveProducer :
                snapshot == 2 ? releaseDisabledProducer : null;

            if(captured != null)
            {
                captured.countDown();

                try
                {
                    release.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ReceiverActivityService service = new ReceiverActivityService(userPreferences, 2, TimeUnit.SECONDS,
            pauseAfterSnapshot, null, FAST_WRITER_FACTORY);
        long oldTimestamp = System.currentTimeMillis();
        long disabledTimestamp = oldTimestamp + 5_000L;
        long newTimestamp = oldTimestamp + 10_000L;
        DecodeEvent staleActive = DecodeEvent.builder(DecodeEventType.CALL, oldTimestamp)
            .channel(new StandardChannel(154_310_000L))
            .identifiers(new IdentifierCollection())
            .build();
        DecodeEvent staleDisabled = DecodeEvent.builder(DecodeEventType.CALL, disabledTimestamp)
            .channel(new StandardChannel(154_310_000L))
            .identifiers(new IdentifierCollection())
            .build();
        DecodeEvent current = DecodeEvent.builder(DecodeEventType.CALL, newTimestamp)
            .channel(new StandardChannel(154_310_000L))
            .identifiers(new IdentifierCollection())
            .build();
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread staleActiveProducer = new Thread(() -> {
            try
            {
                service.getDecodeEventListener().accept(channel, staleActive);
            }
            catch(Throwable throwable)
            {
                producerFailure.set(throwable);
            }
        }, "stale active-epoch producer");
        Thread staleDisabledProducer = new Thread(() -> {
            try
            {
                service.getDecodeEventListener().accept(channel, staleDisabled);
            }
            catch(Throwable throwable)
            {
                producerFailure.compareAndSet(null, throwable);
            }
        }, "stale disabled-epoch producer");

        try
        {
            staleActiveProducer.start();
            assertTrue(activeEpochCaptured.await(2, TimeUnit.SECONDS));

            applicationPreference.setCollectionEnabled(false);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            staleDisabledProducer.start();
            assertTrue(disabledEpochCaptured.await(2, TimeUnit.SECONDS));
            applicationPreference.setCollectionEnabled(true);
            service.preferenceUpdated(PreferenceType.APPLICATION);

            releaseActiveProducer.countDown();
            releaseDisabledProducer.countDown();
            staleActiveProducer.join(TimeUnit.SECONDS.toMillis(2));
            staleDisabledProducer.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(staleActiveProducer.isAlive());
            assertFalse(staleDisabledProducer.isAlive());
            assertEquals(null, producerFailure.get());

            service.getDecodeEventListener().accept(channel, current);
            awaitCount(database, "receiver_activity_event", 1);
            assertEquals(newTimestamp, scalar(database,
                "SELECT observed_at_ms FROM receiver_activity_event"));
        }
        finally
        {
            releaseActiveProducer.countDown();
            releaseDisabledProducer.countDown();
            staleActiveProducer.join(TimeUnit.SECONDS.toMillis(2));
            staleDisabledProducer.join(TimeUnit.SECONDS.toMillis(2));
            disposeAndAwait(service);
        }
    }

    @Test
    void completedCallPausedAcrossDisableAndReenableCannotWriteIntoTheNewEpoch() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        Channel channel = nbfmChannel("00000000-0000-0000-0000-000000000304");
        persistChannels(database, channel);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        CountDownLatch oldEpochCaptured = new CountDownLatch(1);
        CountDownLatch releaseOldOutput = new CountDownLatch(1);
        AtomicBoolean pauseNextOutput = new AtomicBoolean();
        Runnable pauseAfterSnapshot = () -> {
            if(pauseNextOutput.compareAndSet(true, false))
            {
                oldEpochCaptured.countDown();

                try
                {
                    releaseOldOutput.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        };
        ReceiverActivityService service = new ReceiverActivityService(userPreferences, 2, TimeUnit.SECONDS,
            pauseAfterSnapshot, null, FAST_WRITER_FACTORY);
        long frequency = 154_310_000L;
        long start = System.currentTimeMillis();
        DecodeEvent context = DecodeEvent.builder(DecodeEventType.CALL, start)
            .channel(new StandardChannel(frequency))
            .identifiers(new IdentifierCollection())
            .build();
        CompletedAudioCall stale = conventionalCompletedCall(1, channel.getConfigurationId(),
            channel.getRadioResolveId(), frequency, start + 1_000L);
        CompletedAudioCall current = conventionalCompletedCall(2, channel.getConfigurationId(),
            channel.getRadioResolveId(), frequency, start + 2_000L);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread staleProducer = new Thread(() -> {
            try
            {
                service.receiveRecordedCall(stale);
            }
            catch(Throwable throwable)
            {
                producerFailure.set(throwable);
            }
        }, "stale completed-call producer");

        try
        {
            service.getDecodeEventListener().accept(channel, context);
            awaitCount(database, "receiver_activity_event", 1);
            assertEquals(new ReceiverActivityMapper().mapConventionalCallOutput(current.snapshot(),
                    ReceiverActivityRecords.CallOutput.RECORDED).configurationId(),
                scalarText(database, "SELECT configuration_id FROM receiver_channel LIMIT 1"));

            var retiredIngress = service.getObservationIngressForTest();
            pauseNextOutput.set(true);
            staleProducer.start();
            assertTrue(oldEpochCaptured.await(2, TimeUnit.SECONDS));

            applicationPreference.setCollectionEnabled(false);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            applicationPreference.setCollectionEnabled(true);
            service.preferenceUpdated(PreferenceType.APPLICATION);

            releaseOldOutput.countDown();
            staleProducer.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(staleProducer.isAlive());
            assertEquals(null, producerFailure.get());
            var retiredOutput = retiredIngress.poll();
            assertTrue(retiredOutput != null && retiredOutput.first() instanceof CompletedAudioCall,
                "the bounded statistics queue must retain the resolved logical-call metadata");
            assertFalse(((CompletedAudioCall)retiredOutput.first()).hasAudio(),
                "completed-call audio buffers must never be retained by statistics ingress");

            service.receiveRecordedCall(current);
            awaitScalar(database,
                "SELECT COALESCE(SUM(recorded_count), 0) FROM conventional_activity_summary", 1);
            assertEquals(1, scalar(database,
                "SELECT COALESCE(SUM(recorded_count), 0) FROM conventional_activity_summary"));
        }
        finally
        {
            releaseOldOutput.countDown();
            staleProducer.join(TimeUnit.SECONDS.toMillis(2));
            disposeAndAwait(service);
        }
    }

    @Test
    void writerReplacementUsesDistinctInactiveAndActiveEpochs() throws Exception
    {
        Path firstDatabase = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        Path secondRoot = mTemporaryFolder.resolve("replacement");
        Path secondDatabase = SdrTrunkDatabasePath.getDatabasePath(secondRoot);
        SdrTrunkTestDatabase.create(firstDatabase);
        SdrTrunkTestDatabase.create(secondDatabase);
        Channel channel = nbfmChannel("00000000-0000-0000-0000-000000000306");
        persistChannels(firstDatabase, channel);
        persistChannels(secondDatabase, channel);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestDirectoryPreference directoryPreference = new TestDirectoryPreference(mTemporaryFolder);
        TestUserPreferences userPreferences = new TestUserPreferences(applicationPreference, directoryPreference);
        CountDownLatch oldActiveCaptured = new CountDownLatch(1);
        CountDownLatch inactiveCaptured = new CountDownLatch(1);
        CountDownLatch releaseOldActive = new CountDownLatch(1);
        CountDownLatch releaseInactive = new CountDownLatch(1);
        CountDownLatch writerReadyToActivate = new CountDownLatch(1);
        CountDownLatch allowWriterActivation = new CountDownLatch(1);
        AtomicInteger producerSnapshot = new AtomicInteger();
        Runnable pauseAfterSnapshot = () -> {
            int snapshot = producerSnapshot.incrementAndGet();
            CountDownLatch captured = snapshot == 1 ? oldActiveCaptured : snapshot == 2 ? inactiveCaptured : null;
            CountDownLatch release = snapshot == 1 ? releaseOldActive : snapshot == 2 ? releaseInactive : null;

            if(captured != null)
            {
                captured.countDown();

                try
                {
                    release.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        };
        Runnable pauseBeforeActivation = () -> {
            writerReadyToActivate.countDown();

            try
            {
                allowWriterActivation.await();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        };
        ReceiverActivityService service = new ReceiverActivityService(userPreferences, 2, TimeUnit.SECONDS,
            pauseAfterSnapshot, pauseBeforeActivation, FAST_WRITER_FACTORY);
        long start = System.currentTimeMillis();
        DecodeEvent oldActive = conventionalEvent(start);
        DecodeEvent inactive = conventionalEvent(start + 5_000L);
        DecodeEvent current = conventionalEvent(start + 10_000L);
        AtomicReference<Throwable> producerFailure = new AtomicReference<>();
        Thread oldActiveProducer = observationProducer(service, channel, oldActive, producerFailure,
            "old writer epoch producer");
        Thread inactiveProducer = observationProducer(service, channel, inactive, producerFailure,
            "inactive writer epoch producer");

        try
        {
            oldActiveProducer.start();
            assertTrue(oldActiveCaptured.await(2, TimeUnit.SECONDS));
            directoryPreference.setRoot(secondRoot);
            service.preferenceUpdated(PreferenceType.DIRECTORY);
            assertTrue(writerReadyToActivate.await(8, TimeUnit.SECONDS));

            inactiveProducer.start();
            assertTrue(inactiveCaptured.await(2, TimeUnit.SECONDS));
            allowWriterActivation.countDown();
            awaitWriterTransition(service, secondDatabase);

            releaseOldActive.countDown();
            releaseInactive.countDown();
            oldActiveProducer.join(TimeUnit.SECONDS.toMillis(2));
            inactiveProducer.join(TimeUnit.SECONDS.toMillis(2));
            assertFalse(oldActiveProducer.isAlive());
            assertFalse(inactiveProducer.isAlive());
            assertEquals(null, producerFailure.get());

            service.getDecodeEventListener().accept(channel, current);
            awaitCount(secondDatabase, "receiver_activity_event", 1);
            assertEquals(start + 10_000L, scalar(secondDatabase,
                "SELECT observed_at_ms FROM receiver_activity_event"));
            assertEquals(0, count(firstDatabase, "receiver_activity_event"));
        }
        finally
        {
            releaseOldActive.countDown();
            releaseInactive.countDown();
            allowWriterActivation.countDown();
            oldActiveProducer.join(TimeUnit.SECONDS.toMillis(2));
            inactiveProducer.join(TimeUnit.SECONDS.toMillis(2));
            disposeAndAwait(service);
        }
    }

    @Test
    void disposalTracksUninstalledReplacementWriterAfterItsInitialCloseFails() throws Exception
    {
        Path firstDatabase = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        Path secondRoot = mTemporaryFolder.resolve("replacement-close-failure");
        Path secondDatabase = SdrTrunkDatabasePath.getDatabasePath(secondRoot);
        SdrTrunkTestDatabase.create(firstDatabase);
        SdrTrunkTestDatabase.create(secondDatabase);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestDirectoryPreference directoryPreference = new TestDirectoryPreference(mTemporaryFolder);
        TestUserPreferences userPreferences = new TestUserPreferences(applicationPreference, directoryPreference);
        DelayedTerminationWriter replacementWriter = new DelayedTerminationWriter(secondDatabase, 30, true);
        AtomicInteger writerCount = new AtomicInteger();
        ReceiverActivityService.WriterFactory writerFactory = (databasePath, retentionDays, detailedHistory) -> {
            int index = writerCount.getAndIncrement();

            if(index == 0)
            {
                return new ReceiverActivityWriter(databasePath, retentionDays, detailedHistory);
            }

            if(index == 1)
            {
                return replacementWriter;
            }

            throw new AssertionError("unexpected statistics writer creation " + index);
        };
        CountDownLatch allowActivationCheck = new CountDownLatch(1);
        Runnable pauseBeforeActivation = () -> {
            try
            {
                allowActivationCheck.await();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        };
        ReceiverActivityService service = new ReceiverActivityService(userPreferences, 2, TimeUnit.SECONDS,
            null, pauseBeforeActivation, writerFactory);

        try
        {
            directoryPreference.setRoot(secondRoot);
            service.preferenceUpdated(PreferenceType.DIRECTORY);
            assertTrue(replacementWriter.awaitStarted(8, TimeUnit.SECONDS),
                "replacement candidate did not start");
            assertEquals(1, service.getStartedWriterCountForTest(),
                "the retired initial writer should be gone while the replacement candidate remains tracked");

            assertFalse(service.disposeAndAwait(25, TimeUnit.MILLISECONDS),
                "disposal cannot finish while the candidate is paused before installation");
            allowActivationCheck.countDown();
            assertTrue(replacementWriter.awaitCloseAttempt(2, TimeUnit.SECONDS),
                "the losing candidate was not closed");
            awaitObservationWorkerTermination(service);

            assertFalse(service.disposeAndAwait(25, TimeUnit.MILLISECONDS),
                "observer termination must not hide the candidate whose close attempt failed");
            assertEquals(1, service.getStartedWriterCountForTest());

            replacementWriter.finishTermination();
            assertTrue(service.disposeAndAwait(2, TimeUnit.SECONDS),
                "disposal must finish after the losing candidate actually terminates");
            assertEquals(0, service.getStartedWriterCountForTest());
        }
        finally
        {
            allowActivationCheck.countDown();
            replacementWriter.finishTermination();
            disposeAndAwait(service);
        }
    }

    private static DecodeEvent conventionalEvent(long timestamp)
    {
        return DecodeEvent.builder(DecodeEventType.CALL, timestamp)
            .channel(new StandardChannel(154_310_000L))
            .identifiers(new IdentifierCollection())
            .build();
    }

    private static DecodeEvent blockingDecodeEvent(long timestamp, CountDownLatch entered, CountDownLatch release)
    {
        return new DecodeEvent(DecodeEventType.CALL, timestamp)
        {
            @Override
            public DecodeEventType getEventType()
            {
                entered.countDown();

                try
                {
                    release.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return super.getEventType();
            }
        };
    }

    private static CompletedAudioCall conventionalCompletedCall(long sequence, String configurationId, String guid,
                                                                 long frequency, long timestamp)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(ChannelConfigurationIdentifier.create(configurationId));
        if(guid != null && !guid.isBlank())
        {
            identifiers.update(RadioResolveConfigurationIdentifier.create(guid));
        }
        identifiers.update(FrequencyConfigurationIdentifier.create(frequency));
        identifiers.update(DecoderTypeConfigurationIdentifier.create(DecoderType.NBFM));
        AudioCallId callId = new AudioCallId(sequence, sequence + 1, 0);
        CallLegSource source = new CallLegSource(DecoderType.NBFM, configurationId, "Conventional NBFM", guid,
            0, null, TrunkedIdentityDomain.STANDARD,
            io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL, false);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null,
            identifiers, Set.of(), timestamp, timestamp + 100L, 1, 1, timestamp, timestamp + 100L,
            false, true, CallEncryptionState.CLEAR, true, null, VoiceCallQuality.EMPTY,
            CallLegId.from(callId), source, null);
        return new CompletedAudioCall(snapshot, List.of(new float[800]));
    }

    private static CompletedAudioCall uncertainP25TrafficCall(long sequence, long timestamp)
    {
        AudioCallId callId = new AudioCallId(99, sequence, 1);
        CallLegId callLegId = CallLegId.from(callId);
        IdentifierCollection identifiers = new IdentifierCollection();
        CallLegSource source = new CallLegSource(DecoderType.P25_PHASE1,
            "00000000-0000-0000-0000-000000000901", "Uncertain Site", null, 0, null,
            io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain.STANDARD,
            io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind.TRUNKED, true);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null, identifiers, Set.of(), timestamp,
            timestamp + 100L, 1, 1, timestamp, timestamp + 100L, false, true,
            CallEncryptionState.UNKNOWN, true,
            AudioCallRecordingMetadata.captureAtSnapshot(null, identifiers), VoiceCallQuality.EMPTY,
            callLegId, source, null);
        return new CompletedAudioCall(snapshot, List.of(new float[800]));
    }

    private static CompletedAudioCall learnedP25TrafficCall(long sequence, long timestamp)
    {
        AudioCallId callId = new AudioCallId(100, sequence, 1);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(9_001));
        identifiers.update(APCO25RadioIdentifier.createFrom(1_234_567));
        P25SiteIdentity learnedSite = new P25SiteIdentity(0xBEE00, 0x348, 2, 1);
        CallLegSource source = new CallLegSource(DecoderType.P25_PHASE1,
            "00000000-0000-0000-0000-000000000902", "Learned Site", null, 77L, learnedSite,
            io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain.STANDARD,
            io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind.TRUNKED, true);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, null, identifiers, Set.of(), timestamp,
            timestamp + 100L, 1, 1, timestamp, timestamp + 100L, false, true,
            CallEncryptionState.CLEAR, true,
            AudioCallRecordingMetadata.captureAtSnapshot(null, identifiers), VoiceCallQuality.EMPTY,
            CallLegId.from(callId), source, null);
        return new CompletedAudioCall(snapshot, List.of(new float[800]));
    }

    private static void awaitObservationQueueEmpty(ReceiverActivityService service) throws Exception
    {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
        while(service.getPendingObservationCount() > 0 && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(10);
        }
        assertEquals(0, service.getPendingObservationCount());
    }

    private static void awaitPendingObservationCount(ReceiverActivityService service, int expected)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while(service.getPendingObservationCount() != expected && System.nanoTime() < deadline)
        {
            Thread.sleep(1);
        }

        assertEquals(expected, service.getPendingObservationCount(),
            "statistics observation queue did not reach the expected size");
    }

    private static Thread observationProducer(ReceiverActivityService service, Channel channel, DecodeEvent event,
                                              AtomicReference<Throwable> failure, String name)
    {
        return new Thread(() -> {
            try
            {
                service.getDecodeEventListener().accept(channel, event);
            }
            catch(Throwable throwable)
            {
                failure.compareAndSet(null, throwable);
            }
        }, name);
    }

    private static void awaitWriterTransition(ReceiverActivityService service, Path expectedPath)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);

        while((service.isWriterTransitionActiveForTest() ||
            !expectedPath.equals(service.getCurrentDatabasePathForTest())) && System.nanoTime() < deadline)
        {
            Thread.sleep(5);
        }

        assertFalse(service.isWriterTransitionActiveForTest(), "writer transition did not finish");
        assertEquals(expectedPath, service.getCurrentDatabasePathForTest());
    }

    private static void awaitObservationWorkerTermination(ReceiverActivityService service) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

        while(!service.isObservationWorkerTerminated() && System.nanoTime() < deadline)
        {
            Thread.sleep(5);
        }

        assertTrue(service.isObservationWorkerTerminated(), "statistics observer did not terminate");
    }

    private static void disposeAndAwait(ReceiverActivityService service) throws InterruptedException
    {
        assertTrue(service.disposeAndAwait(8, TimeUnit.SECONDS),
            "statistics observer and owned SQLite writer did not terminate");
        assertTrue(service.isObservationWorkerTerminated(), "statistics observer did not terminate");
    }

    private static final class DelayedTerminationWriter extends ReceiverActivityWriter
    {
        private final CountDownLatch mStarted = new CountDownLatch(1);
        private final CountDownLatch mCloseAttempted = new CountDownLatch(1);
        private final CountDownLatch mTerminated = new CountDownLatch(1);
        private final AtomicBoolean mFirstClose = new AtomicBoolean(true);

        private DelayedTerminationWriter(Path databasePath, int retentionDays, boolean detailedHistory)
        {
            super(databasePath, retentionDays, detailedHistory);
        }

        @Override
        void start()
        {
            mStarted.countDown();
        }

        @Override
        public void close()
        {
            mCloseAttempted.countDown();

            if(mFirstClose.compareAndSet(true, false))
            {
                throw new IllegalStateException("simulated writer close timeout");
            }
        }

        @Override
        boolean isWorkerTerminated()
        {
            return mTerminated.getCount() == 0;
        }

        @Override
        boolean awaitWorkerTermination(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mTerminated.await(timeout, unit);
        }

        private boolean awaitStarted(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mStarted.await(timeout, unit);
        }

        private boolean awaitCloseAttempt(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mCloseAttempted.await(timeout, unit);
        }

        private void finishTermination()
        {
            mTerminated.countDown();
        }
    }

    private static final class MonitorHeldCloseWriter extends ReceiverActivityWriter
    {
        private final CountDownLatch mCloseEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseClose = new CountDownLatch(1);
        private final CountDownLatch mTerminated = new CountDownLatch(1);

        private MonitorHeldCloseWriter(Path databasePath, int retentionDays, boolean detailedHistory)
        {
            super(databasePath, retentionDays, detailedHistory);
        }

        @Override
        void start()
        {
            //No worker is needed for this deterministic lifecycle seam.
        }

        @Override
        public void close()
        {
            mCloseEntered.countDown();

            try
            {
                mReleaseClose.await();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                mTerminated.countDown();
            }
        }

        @Override
        boolean isWorkerTerminated()
        {
            return mTerminated.getCount() == 0;
        }

        @Override
        boolean awaitWorkerTermination(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mTerminated.await(timeout, unit);
        }

        private boolean awaitCloseEntered(long timeout, TimeUnit unit) throws InterruptedException
        {
            return mCloseEntered.await(timeout, unit);
        }

        private void releaseClose()
        {
            mReleaseClose.countDown();
        }
    }

    @Test
    void countsOneConventionalP25StartNotItsMutableTrackerUpdates() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        Channel channel = p25ConventionalChannel("00000000-0000-0000-0000-000000000302");
        persistChannels(database, channel);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = fastWriterService(userPreferences);
        P25TrafficChannelManager manager = new P25TrafficChannelManager(channel);
        manager.addDecodeEventListener(event -> service.getDecodeEventListener().accept(channel, event));
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(1_201));
        identifiers.update(APCO25RadioIdentifier.createFrom(1_234_567));
        long frequency = 154_875_000L;
        long start = System.currentTimeMillis();

        try
        {
            manager.processP1TrafficCurrentUser(frequency, null, DecodeEventType.CALL_GROUP,
                VoiceServiceOptions.createUnencrypted(), identifiers, start, null);
            manager.processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
                DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), identifiers, start + 100L, null);
            manager.processP1TrafficCallEnd(frequency, start + 200L);

            awaitCount(database, "receiver_activity_event", 1);
            assertEquals(1, scalar(database,
                "SELECT call_count FROM conventional_activity_summary"));
            assertEquals(1, scalar(database,
                "SELECT call_count FROM conventional_activity_bucket"));
            assertEquals(0, scalar(database,
                "SELECT active_count FROM conventional_activity_summary"));
        }
        finally
        {
            disposeAndAwait(service);
        }
    }

    @Test
    void countsBackToBackNbfmCallsAndStoresOptionalHistory() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        Channel channel = nbfmChannel("00000000-0000-0000-0000-000000000301");
        persistChannels(database, channel);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30, true);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = fastWriterService(userPreferences);
        long frequency = 154_310_000L;
        long start = System.currentTimeMillis();

        try
        {
            DecodeEvent first = DecodeEvent.builder(DecodeEventType.CALL, start)
                .channel(new StandardChannel(frequency))
                .identifiers(new IdentifierCollection())
                .build();
            service.getDecodeEventListener().accept(channel, first);
            first.update(start + 100L);
            service.getDecodeEventListener().accept(channel, first);
            first.end(start + 200L);
            service.getDecodeEventListener().accept(channel, first);

            DecodeEvent second = DecodeEvent.builder(DecodeEventType.CALL, start + 500L)
                .channel(new StandardChannel(frequency))
                .identifiers(new IdentifierCollection())
                .build();
            service.getDecodeEventListener().accept(channel, second);

            awaitCount(database, "receiver_activity_event", 2);
            assertEquals(2, scalar(database,
                "SELECT call_count FROM conventional_activity_summary"));
            assertEquals(2, scalar(database,
                "SELECT call_count FROM conventional_activity_bucket"));
        }
        finally
        {
            disposeAndAwait(service);
        }
    }

    @Test
    void lowersRetentionAndRunsMaintenanceWhileCollectionIsDisabled() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        String expiredDmr = "00000000-0000-0000-0000-000000000111";
        String currentDmr = "00000000-0000-0000-0000-000000000112";
        String expiredNxdn = "00000000-0000-0000-0000-000000000113";
        String currentNxdn = "00000000-0000-0000-0000-000000000114";
        persistChannels(database,
            p25TrunkedChannel(ACTIVITY_CONFIGURATION_ID),
            dmrChannel(expiredDmr, DMRChannelMode.TRUNKED),
            dmrChannel(currentDmr, DMRChannelMode.TRUNKED),
            nxdnTrunkedChannel(expiredNxdn),
            nxdnTrunkedChannel(currentNxdn));
        long now = System.currentTimeMillis();

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            ReceiverActivitySchema.recordActivity(connection,
                activity(now - TimeUnit.DAYS.toMillis(40)), true);
            ReceiverActivitySchema.recordActivity(connection,
                activity(now - TimeUnit.DAYS.toMillis(2)), true);

            insertTrunkedSite(connection, expiredDmr, "a".repeat(64), TrunkedSiteSchema.PROTOCOL_DMR,
                now - TimeUnit.DAYS.toMillis(40));
            insertTrunkedSite(connection, currentDmr, "b".repeat(64), TrunkedSiteSchema.PROTOCOL_DMR,
                now - TimeUnit.DAYS.toMillis(2));
            insertTrunkedSite(connection, expiredNxdn, "c".repeat(64), TrunkedSiteSchema.PROTOCOL_NXDN,
                now - TimeUnit.DAYS.toMillis(40));
            insertTrunkedSite(connection, currentNxdn, "d".repeat(64), TrunkedSiteSchema.PROTOCOL_NXDN,
                now - TimeUnit.DAYS.toMillis(2));
        }

        TestApplicationPreference applicationPreference = new TestApplicationPreference(false, 30);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = fastWriterService(userPreferences);

        try
        {
            StatsDatabaseMaintenanceRequest initialCheck =
                StatsDatabaseMaintenanceRequest.forOperation(ReceiverActivityMaintenance.Operation.CHECK);
            service.receiveMaintenanceRequest(initialCheck);
            assertTrue(initialCheck.result().get(5, TimeUnit.SECONDS).checkOk());
            assertEquals(ReceiverActivityStatus.State.DISABLED, service.getStatus().state());
            //Startup maintenance used the 30-day setting even though collection was disabled.
            assertEquals(1, count(database, "receiver_activity_event"));
            assertEquals(2, count(database, "trunked_site_snapshot"));
            assertEquals(1, countProtocol(database, TrunkedSiteSchema.PROTOCOL_DMR));
            assertEquals(1, countProtocol(database, TrunkedSiteSchema.PROTOCOL_NXDN));

            applicationPreference.setRetentionDays(1);
            service.preferenceUpdated(PreferenceType.APPLICATION);

            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
            int remainingReceiverActivity = 1;
            int remainingTrunked = 2;

            while((remainingReceiverActivity != 0 || remainingTrunked != 0) &&
                System.currentTimeMillis() < deadline)
            {
                remainingReceiverActivity = count(database, "receiver_activity_event");
                remainingTrunked = count(database, "trunked_site_snapshot");

                if(remainingReceiverActivity != 0 || remainingTrunked != 0)
                {
                    Thread.sleep(25);
                }
            }

            assertEquals(0, remainingReceiverActivity);
            assertEquals(0, remainingTrunked);

            StatsDatabaseMaintenanceRequest finalCheck =
                StatsDatabaseMaintenanceRequest.forOperation(ReceiverActivityMaintenance.Operation.CHECK);
            service.receiveMaintenanceRequest(finalCheck);
            assertTrue(finalCheck.result().get(5, TimeUnit.SECONDS).checkOk());
            assertEquals(ReceiverActivityStatus.State.DISABLED, service.getStatus().state());

            Channel channel = new Channel("Disabled collection", Channel.ChannelType.STANDARD);
            channel.setConfigurationId("00000000-0000-0000-0000-000000000102");
            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(channel,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 1, 2, null, null, null, null,
                    null, null, List.of(), List.of()),
                System.currentTimeMillis()));
            Thread.sleep(1_100);
            assertEquals(0, count(database, "trunked_site_snapshot"));
        }
        finally
        {
            disposeAndAwait(service);
        }
    }

    @Test
    void exposesMaintenanceWriterFailureWhileCollectionIsDisabled() throws Exception
    {
        TestApplicationPreference applicationPreference = new TestApplicationPreference(false, 30);
        TestUserPreferences userPreferences = new TestUserPreferences(applicationPreference,
            new TestDirectoryPreference(mTemporaryFolder.resolve("missing-portable-data")));
        ReceiverActivityService service = fastWriterService(userPreferences);

        try
        {
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);

            while(service.getStatus().state() != ReceiverActivityStatus.State.FAILED &&
                System.currentTimeMillis() < deadline)
            {
                Thread.sleep(25);
            }

            assertEquals(ReceiverActivityStatus.State.FAILED, service.getStatus().state());
            assertTrue(service.getStatus().lastError() != null && !service.getStatus().lastError().isBlank());
        }
        finally
        {
            disposeAndAwait(service);
        }
    }

    @Test
    void persistsExplicitTrunkedDmrQualityWithoutPromotingConventionalDmr() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(mTemporaryFolder);
        SdrTrunkTestDatabase.create(database);
        Channel trunked = dmrChannel("00000000-0000-0000-0000-000000000201", DMRChannelMode.TRUNKED);
        Channel conventional = dmrChannel("00000000-0000-0000-0000-000000000202");
        persistChannels(database, trunked, conventional);
        TestApplicationPreference applicationPreference = new TestApplicationPreference(true, 30);
        TestUserPreferences userPreferences =
            new TestUserPreferences(applicationPreference, new TestDirectoryPreference(mTemporaryFolder));
        ReceiverActivityService service = fastWriterService(userPreferences);

        try
        {
            long now = System.currentTimeMillis();
            service.getControlChannelQualityListener().receive(quality(trunked, now));
            awaitCount(database, "trunked_control_channel_quality", 1);

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", null, 10, 20, null, null, null, null,
                    1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 10_000L));
            awaitCount(database, "trunked_control_channel_quality", 2);
            assertEquals(0, count(database, "trunked_site_snapshot"));

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 10, 20, "Tier III Trunking",
                    "SMALL", null, "Control", 1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 20_000L));
            awaitCount(database, "trunked_control_channel_quality", 3);
            assertEquals(1, count(database, "trunked_site_snapshot"));

            applicationPreference.setCollectionEnabled(false);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            applicationPreference.setCollectionEnabled(true);
            service.preferenceUpdated(PreferenceType.APPLICATION);
            service.getControlChannelQualityListener().receive(quality(trunked, now + 40_000L));

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 10, 20, "Tier III Trunking",
                    "SMALL", null, "Control", 1, 2, List.of(), List.of()), System.currentTimeMillis()));
            Channel reusedConfiguration = dmrChannel(trunked.getPersistedConfigurationId(), DMRChannelMode.TRUNKED);
            service.getControlChannelQualityListener().receive(quality(reusedConfiguration, now + 60_000L));

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 10, 20, "Tier III Trunking",
                    "SMALL", null, "Control", 1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 600_000L));
            awaitCount(database, "trunked_control_channel_quality", 6);

            service.getControlChannelQualityListener().receive(quality(trunked, now + 610_000L, false));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 620_000L));

            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", "TIER_III", 10, 20, "Tier III Trunking",
                    "SMALL", null, "Control", 1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.receiveProtocolSiteMetadata(new ProtocolSiteMetadataEvent(trunked,
                new DMRNetworkConfigurationSnapshot("DMR", null, 10, 20, null, null, null, null,
                    1, 2, List.of(), List.of()), System.currentTimeMillis()));
            service.getControlChannelQualityListener().receive(quality(trunked, now + 640_000L));

            service.getControlChannelQualityListener().receive(quality(conventional, now + 660_000L));
            awaitCount(database, "trunked_control_channel_quality", 8);
        }
        finally
        {
            disposeAndAwait(service);
        }
    }

    private static int count(Path database, String table) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            assertTrue(resultSet.next());
            return resultSet.getInt(1);
        }
    }

    private static int countProtocol(Path database, int protocol) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.prepareStatement(
                "SELECT COUNT(*) FROM trunked_site_snapshot WHERE protocol_code = ?"))
        {
            statement.setInt(1, protocol);

            try(ResultSet resultSet = statement.executeQuery())
            {
                assertTrue(resultSet.next());
                return resultSet.getInt(1);
            }
        }
    }

    private static long scalar(Path database, String sql) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getLong(1);
        }
    }

    private static String scalarText(Path database, String sql) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static void awaitCount(Path database, String table, int expected) throws Exception
    {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
        int actual = count(database, table);

        while(actual != expected && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
            actual = count(database, table);
        }

        assertEquals(expected, actual);
    }

    private static void awaitScalar(Path database, String sql, long expected) throws Exception
    {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
        long actual = scalar(database, sql);

        while(actual != expected && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(25);
            actual = scalar(database, sql);
        }

        assertEquals(expected, actual);
    }

    private static Channel dmrChannel(String configurationId)
    {
        return dmrChannel(configurationId, DMRChannelMode.CONVENTIONAL);
    }

    private static Channel dmrChannel(String configurationId, DMRChannelMode mode)
    {
        Channel channel = new Channel("DMR", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(configurationId);
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setChannelMode(mode);
        channel.setDecodeConfiguration(configuration);
        return channel;
    }

    private static ControlChannelQualitySnapshot quality(Channel channel, long observedAt)
    {
        return quality(channel, observedAt, true);
    }

    private static ControlChannelQualitySnapshot quality(Channel channel, long observedAt, boolean active)
    {
        return new ControlChannelQualitySnapshot(channel, channel.getPersistedConfigurationId(), 451_012_500L, observedAt,
            active, -20.0, -21.0, -25.0, -18.0, 95.0, 100, 2, 1, 0, 0, observedAt);
    }

    private static void insertTrunkedSite(Connection connection, String configurationId, String snapshotHash,
                                          int protocol, long observedAt) throws Exception
    {
        try(var channel = connection.prepareStatement("""
                INSERT INTO receiver_channel(configuration_id, first_seen_ms, last_seen_ms)
                VALUES (?, ?, ?)
                """))
        {
            channel.setString(1, configurationId);
            channel.setLong(2, observedAt);
            channel.setLong(3, observedAt);
            channel.executeUpdate();
        }

        try(var snapshot = connection.prepareStatement("""
                INSERT INTO trunked_site_snapshot (
                    channel_id, snapshot_hash, protocol_code, variant_code, observed_location_category_code,
                    first_seen_ms, last_seen_ms, observation_count
                ) VALUES ((SELECT id FROM receiver_channel WHERE configuration_id = ?), ?, ?, 1, ?, ?, ?, 1)
                """))
        {
            snapshot.setString(1, configurationId);
            snapshot.setString(2, snapshotHash);
            snapshot.setInt(3, protocol);
            snapshot.setInt(4, protocol == TrunkedSiteSchema.PROTOCOL_NXDN ? 1 : 0);
            snapshot.setLong(5, observedAt);
            snapshot.setLong(6, observedAt);
            snapshot.executeUpdate();
        }
    }

    private static ReceiverActivityRecords.ActivityEvent activity(long timestamp)
    {
        return new ReceiverActivityRecords.ActivityEvent(timestamp, ACTIVITY_CONFIGURATION_ID,
            ReceiverActivityRecords.ReceiverKind.TRUNKED_SITE, "APCO25", ReceiverActivityRecords.Action.GRANT,
            "CALL_GROUP", "1811524", "56138", "TALKGROUP", List.of(), 854_187_500L, "00-0509", 1,
            false, null, null, 0xBEE00, 0x348, 0x348, 2, 1, "Example Site", false, null, null,
            TrunkedIdentityDomain.STANDARD, ReceiverActivityRecords.P25Identity.ORDINARY,
            ReceiverActivityRecords.P25Identity.ORDINARY, List.of(), null);
    }

    private static Channel nbfmChannel(String configurationId)
    {
        Channel channel = new Channel("Conventional NBFM", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(configurationId);
        channel.setDecodeConfiguration(new DecodeConfigNBFM());
        return channel;
    }

    private static Channel p25ConventionalChannel(String configurationId)
    {
        Channel channel = new Channel("Conventional P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(configurationId);
        channel.setDecodeConfiguration(new DecodeConfigP25Conventional());
        return channel;
    }

    private static Channel p25TrunkedChannel(String configurationId)
    {
        Channel channel = new Channel("Trunked P25", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(configurationId);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        return channel;
    }

    private static SourceConfigTuner tuner(long frequency)
    {
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(frequency);
        return source;
    }

    private static APCO25Channel descriptorThatRejectsProducerStringProjection(int band, int channel)
    {
        return new APCO25Channel(new P25Channel(band, channel))
        {
            @Override
            public String toString()
            {
                throw new AssertionError("P25 descriptor text must be formatted on the statistics worker");
            }
        };
    }

    private static Channel nxdnTrunkedChannel(String configurationId)
    {
        Channel channel = new Channel("Trunked NXDN", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(configurationId);
        DecodeConfigNXDN configuration = new DecodeConfigNXDN();
        configuration.setChannelMode(NXDNChannelMode.TRUNKED);
        channel.setDecodeConfiguration(configuration);
        return channel;
    }

    private static Channel dmrTrunkedChannel(String configurationId)
    {
        Channel channel = new Channel("Trunked DMR", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(configurationId);
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setChannelMode(DMRChannelMode.TRUNKED);
        channel.setDecodeConfiguration(configuration);
        return channel;
    }

    private static void persistChannels(Path database, Channel... channels) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.openWriteTransaction(database))
        {
            new ConfigurationDatabaseStore(database).replace(connection,
                new ChannelAndBroadcastConfiguration(List.of(channels), List.of()));
            SdrTrunkDatabase.commitWriteTransaction(connection);
        }
    }

    private static ReceiverActivityService fastWriterService(UserPreferences userPreferences)
    {
        return new ReceiverActivityService(userPreferences, 2, TimeUnit.SECONDS, null, null,
            FAST_WRITER_FACTORY);
    }

    private static class TestUserPreferences extends UserPreferences
    {
        private final ApplicationPreference mApplicationPreference;
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(ApplicationPreference applicationPreference,
                                    DirectoryPreference directoryPreference)
        {
            mApplicationPreference = applicationPreference;
            mDirectoryPreference = directoryPreference;
        }

        @Override
        public ApplicationPreference getApplicationPreference()
        {
            return mApplicationPreference;
        }

        @Override
        public DirectoryPreference getDirectoryPreference()
        {
            return mDirectoryPreference;
        }
    }

    private static class TestApplicationPreference extends ApplicationPreference
    {
        private boolean mCollectionEnabled;
        private int mRetentionDays;
        private final boolean mDetailedHistoryEnabled;

        private TestApplicationPreference(boolean collectionEnabled, int retentionDays)
        {
            this(collectionEnabled, retentionDays, false);
        }

        private TestApplicationPreference(boolean collectionEnabled, int retentionDays,
                                          boolean detailedHistoryEnabled)
        {
            super(preferenceType -> {});
            mCollectionEnabled = collectionEnabled;
            mRetentionDays = retentionDays;
            mDetailedHistoryEnabled = detailedHistoryEnabled;
        }

        @Override
        public boolean isStatsLoggingEnabled()
        {
            return mCollectionEnabled;
        }

        private void setCollectionEnabled(boolean collectionEnabled)
        {
            mCollectionEnabled = collectionEnabled;
        }

        @Override
        public boolean isStatsDetailedHistoryEnabled()
        {
            return mDetailedHistoryEnabled;
        }

        @Override
        public int getStatsLoggingRetentionDays()
        {
            return mRetentionDays;
        }

        private void setRetentionDays(int retentionDays)
        {
            mRetentionDays = retentionDays;
        }
    }

    private static class TestDirectoryPreference extends DirectoryPreference
    {
        private Path mRoot;

        private TestDirectoryPreference(Path root)
        {
            super(preferenceType -> {});
            mRoot = root;
        }

        @Override
        public Path getDirectoryApplicationRoot()
        {
            return mRoot;
        }

        private void setRoot(Path root)
        {
            mRoot = root;
        }
    }
}
