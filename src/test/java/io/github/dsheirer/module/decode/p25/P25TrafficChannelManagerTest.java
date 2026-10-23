/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25;

import com.google.common.eventbus.EventBus;
import io.github.dsheirer.audio.call.AudioActivityCallEvent;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.alias.P25TalkerAliasIdentifier;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.message.TimeslotMessage;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Nac;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.module.decode.p25.identifier.encryption.APCO25EncryptionKey;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacOpcode;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.PushToTalk;
import io.github.dsheirer.module.decode.p25.bandplan.P25BandplanOverrideRegistry;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.module.decode.traffic.TrunkedTalkerAliasEvent;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigRemote;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class P25TrafficChannelManagerTest
{
    @Test
    void remoteControlGrantsIgnoreLocalTrafficSkipPreferences() throws Exception
    {
        Channel parent = new Channel("Remote control", Channel.ChannelType.STANDARD);
        SourceConfigRemote source = new SourceConfigRemote();
        source.setSenderId("54ad3a5c-c1cb-446f-bef7-c358fb14b7a8");
        source.setFeedId("14dc0718-2970-4147-9185-7f4119810dca");
        source.setFrequency(851_000_000L);
        parent.setSourceConfiguration(source);
        DecodeConfigP25Phase1 config = new DecodeConfigP25Phase1();
        config.setTrafficChannelPoolSize(2);
        config.setIgnoreDataCalls(true);
        config.setIgnoreEncryptedCalls(true);
        parent.setDecodeConfiguration(config);
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        manager.processFrequencyBand(band(0, 851_006_250L, 6_250L, 1));
        APCO25Channel encrypted = APCO25Channel.create(0, 1);
        APCO25Channel data = APCO25Channel.create(0, 2);

        manager.processP1ControlDirectedChannelGrant(encrypted, VoiceServiceOptions.createEncrypted(),
            identifiers(1201, null), Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_000L);
        manager.processP1ControlDirectedChannelGrant(data, null, identifiers(1202, null),
            Opcode.OSP_SNDCP_DATA_CHANNEL_GRANT, 1_100L);

        assertFalse(trafficTrackers(manager).get(encrypted.getDownlinkFrequency()).getEvent().getDetails()
            .contains("IGNORED"));
        assertFalse(trafficTrackers(manager).get(data.getDownlinkFrequency()).getEvent().getDetails()
            .contains("IGNORED"));
        assertTrue(allocatedTrafficChannels(manager).isEmpty(), "remote OPENs remain the allocation authority");
        assertTrue(config.getIgnoreDataCalls(), "saved preferences must not be mutated");
        assertTrue(config.getIgnoreEncryptedCalls());
    }

    @Test
    void encryptedPhaseOneGrantSkipsAllocationButClearGrantStillStarts()
    {
        Channel parent = new Channel("Control", Channel.ChannelType.STANDARD);
        DecodeConfigP25Phase1 config = new DecodeConfigP25Phase1();
        config.setTrafficChannelPoolSize(1);
        config.setIgnoreEncryptedCalls(true);
        parent.setDecodeConfiguration(config);
        AtomicInteger moduleStateReads = new AtomicInteger();
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent,
            P25BandplanOverrideRegistry.empty(), () ->
            {
                moduleStateReads.incrementAndGet();
                return false;
            });
        P25FrequencyBand band = new P25FrequencyBand(0, 851_006_250L, -45_000_000L, 6_250L, 12_500, 1);
        manager.processFrequencyBand(band);
        manager.processFrequencyBand(band);
        StartRequestSubscriber subscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);
        manager.setInterModuleEventBus(eventBus);
        APCO25Channel channel = APCO25Channel.create(0, 1);
        MutableIdentifierCollection identifiers = identifiers(1201, null);
        identifiers.update(APCO25Nac.create(0x491));

        manager.processP1ControlDirectedChannelGrant(channel, VoiceServiceOptions.createEncrypted(), identifiers,
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_000L);
        assertTrue(subscriber.requests.isEmpty());
        assertEquals(1, moduleStateReads.get());

        manager.processP1ControlDirectedChannelGrant(channel, VoiceServiceOptions.createUnencrypted(), identifiers,
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_100L);

        assertEquals(1, subscriber.requests.size());
        assertEquals(1, moduleStateReads.get(), "clear grants must not read the module state");
    }

    @Test
    void encryptedPhaseTwoGrantSkipsOnlyNewSharedCarrierAllocation()
    {
        long frequency = 851_012_500L;
        Channel parent = new Channel("Control", Channel.ChannelType.STANDARD);
        DecodeConfigP25Phase2 config = new DecodeConfigP25Phase2();
        config.setTrafficChannelPoolSize(1);
        config.setIgnoreEncryptedCalls(true);
        parent.setDecodeConfiguration(config);
        AtomicInteger moduleStateReads = new AtomicInteger();
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent,
            P25BandplanOverrideRegistry.empty(), () ->
            {
                moduleStateReads.incrementAndGet();
                return false;
            });
        P25FrequencyBand band = new P25FrequencyBand(0, frequency, -45_000_000L, 12_500L, 12_500, 2);
        manager.processFrequencyBand(band);
        manager.processFrequencyBand(band);
        StartRequestSubscriber subscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);
        manager.setInterModuleEventBus(eventBus);
        APCO25Channel encryptedTimeslot = APCO25Channel.create(0, 0);
        APCO25Channel clearTimeslot = APCO25Channel.create(0, 1);

        manager.processP2ChannelGrant(encryptedTimeslot, VoiceServiceOptions.createEncrypted(),
            identifiers(1201, null), MacOpcode.TDMA_05_GROUP_VOICE_CHANNEL_GRANT_UPDATE_MULTIPLE_IMPLICIT, 1_000L);

        assertTrue(subscriber.requests.isEmpty());
        assertEquals(1, moduleStateReads.get());

        manager.processP2ChannelGrant(clearTimeslot, VoiceServiceOptions.createUnencrypted(),
            identifiers(1202, null), MacOpcode.TDMA_05_GROUP_VOICE_CHANNEL_GRANT_UPDATE_MULTIPLE_IMPLICIT, 1_100L);
        manager.processP2ChannelGrant(encryptedTimeslot, VoiceServiceOptions.createEncrypted(),
            identifiers(1201, null), MacOpcode.TDMA_05_GROUP_VOICE_CHANNEL_GRANT_UPDATE_MULTIPLE_IMPLICIT, 1_200L);

        assertEquals(1, subscriber.requests.size(),
            "a clear sister slot should start the carrier and later encrypted grants must not replace it");
        assertEquals(1, moduleStateReads.get(),
            "clear and already-allocated grants must not read the module state");
    }

    @Test
    void loadedVoiceDecryptionModuleOverridesEncryptedPhaseTwoGrantSkip()
    {
        long frequency = 851_012_500L;
        Channel parent = new Channel("Control", Channel.ChannelType.STANDARD);
        DecodeConfigP25Phase2 config = new DecodeConfigP25Phase2();
        config.setTrafficChannelPoolSize(1);
        config.setIgnoreEncryptedCalls(true);
        parent.setDecodeConfiguration(config);
        AtomicBoolean moduleLoaded = new AtomicBoolean();
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent,
            P25BandplanOverrideRegistry.empty(), moduleLoaded::get);
        P25FrequencyBand band = new P25FrequencyBand(0, frequency, -45_000_000L, 12_500L, 12_500, 2);
        manager.processFrequencyBand(band);
        manager.processFrequencyBand(band);
        StartRequestSubscriber subscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);
        manager.setInterModuleEventBus(eventBus);
        APCO25Channel channel = APCO25Channel.create(0, 0);

        manager.processP2ChannelGrant(channel, VoiceServiceOptions.createEncrypted(), identifiers(1201, null),
            MacOpcode.TDMA_05_GROUP_VOICE_CHANNEL_GRANT_UPDATE_MULTIPLE_IMPLICIT, 1_000L);
        assertTrue(subscriber.requests.isEmpty());

        moduleLoaded.set(true);
        manager.processP2ChannelGrant(channel, VoiceServiceOptions.createEncrypted(), identifiers(1201, null),
            MacOpcode.TDMA_05_GROUP_VOICE_CHANNEL_GRANT_UPDATE_MULTIPLE_IMPLICIT, 1_100L);

        assertEquals(1, subscriber.requests.size());
    }

    @Test
    void rejectedPhaseOneGrantsPreserveExactlyOneTrafficPoolSlot() throws Exception
    {
        assertRejectedEnvelopeGrantsPreserveOneSlot(false);
    }

    @Test
    void rejectedPhaseTwoGrantsPreserveExactlyOneTrafficPoolSlot() throws Exception
    {
        assertRejectedEnvelopeGrantsPreserveOneSlot(true);
    }

    private static void assertRejectedEnvelopeGrantsPreserveOneSlot(boolean phaseTwo) throws Exception
    {
        long base = 851_000_000L;
        Channel parent = new Channel("Control", Channel.ChannelType.STANDARD);
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        source.addFrequency(base + 12_500L);
        source.setMinimumFrequency(base + 12_500L);
        source.setMaximumFrequency(base + 25_000L);
        parent.setSourceConfiguration(source);
        if(phaseTwo)
        {
            DecodeConfigP25Phase2 config = new DecodeConfigP25Phase2();
            config.setTrafficChannelPoolSize(1);
            parent.setDecodeConfiguration(config);
        }
        else
        {
            DecodeConfigP25Phase1 config = new DecodeConfigP25Phase1();
            config.setTrafficChannelPoolSize(1);
            parent.setDecodeConfiguration(config);
        }
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        P25FrequencyBand band = new P25FrequencyBand(0, base, -45_000_000L, 12_500L, 12_500, phaseTwo ? 2 : 1);
        manager.processFrequencyBand(band);
        manager.processFrequencyBand(band);
        StartRequestSubscriber subscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);
        manager.setInterModuleEventBus(eventBus);
        int step = phaseTwo ? 2 : 1;
        // Reject both sides and repeat the first grant, then request the two inclusive envelope edges.
        int[] channelNumbers = {0, 3 * step, 0, step, 2 * step};
        for(int index = 0; index < channelNumbers.length; index++)
        {
            APCO25Channel channel = APCO25Channel.create(0, channelNumbers[index]);
            MutableIdentifierCollection identifiers = identifiers(1201 + channelNumbers[index], null);
            identifiers.update(APCO25Nac.create(0x491));
            long timestamp = 1_000L + index * 100L;
            if(phaseTwo)
                manager.processP2ChannelGrant(channel, null, identifiers,
                    MacOpcode.TDMA_05_GROUP_VOICE_CHANNEL_GRANT_UPDATE_MULTIPLE_IMPLICIT, timestamp);
            else
                manager.processP1ControlDirectedChannelGrant(channel, null, identifiers,
                    Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, timestamp);
            if(index < 3)
            {
                assertTrue(subscriber.requests.isEmpty(), "Out-of-envelope grants must not request startup");
                assertTrue(allocatedTrafficChannels(manager).isEmpty(), "Rejected grants must not reserve a carrier");
            }
        }
        assertEquals(1, subscriber.requests.size(), "Rejections must preserve one slot without returning duplicates");
        assertEquals(1, allocatedTrafficChannels(manager).size());
        assertTrue(allocatedTrafficChannels(manager).containsKey(source.getMinimumFrequency()),
            "The first valid edge must still be able to allocate after repeated rejections");
        assertFalse(allocatedTrafficChannels(manager).containsKey(source.getMaximumFrequency()),
            "The one-slot pool must not permit a second simultaneous carrier");
    }

    @Test
    void copiesLateLearnedSiteIdentityToTrafficChannel() throws Exception
    {
        Channel parent = new Channel("Control");
        Method activate = Channel.class.getDeclaredMethod("activateProcessingIncarnation", long.class);
        activate.setAccessible(true);
        activate.invoke(parent, 17L);
        parent.advanceSiteEvidenceTuningGeneration();
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        P25SiteIdentity learnedIdentity = new P25SiteIdentity(0xBEE00, 0x348, 2, 19);
        parent.setP25SiteIdentity(learnedIdentity);
        Channel traffic = new Channel("Traffic", Channel.ChannelType.TRAFFIC);
        Method sync = P25TrafficChannelManager.class.getDeclaredMethod("syncTrafficChannelIdentity", Channel.class);
        sync.setAccessible(true);

        sync.invoke(manager, traffic);

        assertEquals(learnedIdentity, traffic.getP25SiteIdentity());
        assertEquals(17L, traffic.getSiteEvidenceProcessingIncarnation());
        assertEquals(2L, traffic.getSiteEvidenceTuningGeneration(),
            "an allocation must capture the parent control tuning epoch before its call source is created");
    }

    @Test
    void displaysEncryptionKeyIdAsHexadecimalInEventDetails() throws Exception
    {
        long frequency = 851_012_500L;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP_ENCRYPTED, 1_000L,
                VoiceServiceOptions.createEncrypted())
            .details("PHASE 1 CALL")
            .identifiers(identifiers(1201, null))
            .build();
        trafficTrackers(manager).put(frequency, new P25TrafficChannelEventTracker(event));
        EncryptionKeyIdentifier encryptionKey = EncryptionKeyIdentifier.create(Protocol.APCO25,
            APCO25EncryptionKey.create(0x84, 0x65));

        manager.processP1TrafficCurrentUser(frequency, encryptionKey, 1_100L);

        assertEquals("PHASE 1 CALL AES256 K:65", event.getDetails());
    }

    @Test
    void phaseOneReturnsItsSelectedTokenAndRefusesToBindOldTrafficToANewerTracker() throws Exception
    {
        long frequency = 851_012_500L;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        MutableIdentifierCollection identifiers = identifiers(1201, APCO25RadioIdentifier.createFrom(700_001));
        P25TrafficChannelEventTracker first = new P25TrafficChannelEventTracker(
            P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, VoiceServiceOptions.createUnencrypted())
                .identifiers(identifiers).build());
        trafficTrackers(manager).put(frequency, first);
        AudioActivityCallEvent selected = manager.processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
            DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), identifiers, 1_100L, null);
        assertEquals(first.getActivityCallToken(), selected.token());
        assertEquals(selected.token(), first.getEvent().getActivityCallToken(),
            "The decode event consumed by alerts keeps the same binding selected for P25 audio and Activity");

        P25TrafficChannelEventTracker replacement = new P25TrafficChannelEventTracker(
            P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 2_000L, VoiceServiceOptions.createUnencrypted())
                .identifiers(identifiers(1301, APCO25RadioIdentifier.createFrom(700_002))).build());
        trafficTrackers(manager).put(frequency, replacement);
        assertEquals(first.getActivityCallToken(), selected.token(),
            "The returned selection stays attached to its physical observation after a grant replacement");
        assertFalse(replacement.getActivityCallToken().equals(selected.token()));
        assertNull(manager.processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
            DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), identifiers, 1_200L, null));
        assertNull(manager.processP1TrafficVoice(frequency, 1_200L));
        assertNull(manager.processP1TrafficCallStart(frequency, APCO25Talkgroup.create(1201), null,
            EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x80, 0)),
            VoiceServiceOptions.createUnencrypted(), new StandardChannel(frequency), 1_200L));
        manager.processP1TrafficCurrentUser(frequency, APCO25RadioIdentifier.createFrom(700_001), 1_200L);
        assertNull(manager.processP1TrafficCallStart(frequency, APCO25Talkgroup.create(1201), null,
            EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x80, 0)),
            VoiceServiceOptions.createUnencrypted(), new StandardChannel(frequency), 2_100L));
        assertEquals(replacement.getActivityCallToken(), manager.processP1TrafficVoice(frequency, 2_100L).token(),
            "Voice activity selects the accepted call without replaying old cached identities");
        assertSame(replacement, trafficTrackers(manager).get(frequency));
        assertEquals(APCO25Talkgroup.create(1301), replacement.getEvent().getIdentifierCollection().getToIdentifier());
        assertEquals(APCO25RadioIdentifier.createFrom(700_002),
            replacement.getEvent().getIdentifierCollection().getFromIdentifier());
    }

    @Test
    void phaseOneVoiceActivityCannotCreateReviveOrKeepAStaleCall() throws Exception
    {
        long frequency = 851_012_500L;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        assertNull(manager.processP1TrafficVoice(frequency, 1_000L));
        MutableIdentifierCollection identifiers = identifiers(1201, APCO25RadioIdentifier.createFrom(700_001));
        AudioActivityCallEvent selected = manager.processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
            DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), identifiers, 1_100L, null);
        assertEquals(selected.token(), manager.processP1TrafficVoice(frequency, 1_200L).token());
        assertTrue(manager.processP1TrafficCallEnd(frequency, 1_300L));
        assertNull(manager.processP1TrafficVoice(frequency, 1_400L));

        selected = manager.processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
            DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), identifiers, 1_500L, null);
        assertNull(manager.processP1TrafficVoice(frequency, 5_000L));
        assertTrue(selected.attribution().isComplete());
        assertFalse(trafficTrackers(manager).containsKey(frequency));
    }

    @Test
    void publishesOneCallStartForEachConventionalTrafficCall()
    {
        long frequency = 154_875_000L;
        Channel parentChannel = new Channel("Conventional");
        parentChannel.setConfigurationId("00000000-0000-0000-0000-000000000401");
        parentChannel.setDecodeConfiguration(new DecodeConfigP25Conventional());
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parentChannel);
        MutableIdentifierCollection identifiers = identifiers(1201,
            APCO25RadioIdentifier.createFrom(1_234_567));
        CallStartSubscriber subscriber = new CallStartSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            manager.processP1TrafficCurrentUser(frequency, null,
                DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), identifiers, 1_000L, null);
            manager.processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
                DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), identifiers, 1_100L, null);
            assertTrue(manager.processP1TrafficCallEnd(frequency, 1_200L));
            manager.processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
                DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), identifiers, 1_300L, null);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(2, subscriber.events.size());
        assertEquals(parentChannel.getPersistedConfigurationId(), subscriber.events.get(0).configurationId());
        assertEquals(DecoderType.P25_CONVENTIONAL, subscriber.events.get(0).decoderType());
        assertEquals(1_000L, subscriber.events.get(0).startedAtEpochMilliseconds());
        assertEquals(frequency, subscriber.events.get(0).frequencyHertz());
        assertEquals(1_300L, subscriber.events.get(1).startedAtEpochMilliseconds());
    }

    @Test
    void conventionalLateSourceAndCompletionKeepTheSameImmutableCallTokenAndOriginalOwnership()
    {
        long frequency = 154_875_000L;
        Channel parent = new Channel("Conventional");
        parent.setConfigurationId("00000000-0000-0000-0000-000000000402");
        parent.setDecodeConfiguration(new DecodeConfigP25Conventional());
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        CallStartSubscriber subscriber = new CallStartSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);
        try
        {
            manager.processP1TrafficCallStart(frequency, APCO25Talkgroup.create(1201), null,
                EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(0x80, 0)),
                VoiceServiceOptions.createUnencrypted(), new StandardChannel(frequency), 1_000L);
            parent.setConfigurationId("00000000-0000-0000-0000-000000000403");
            manager.processP1TrafficCurrentUser(frequency, APCO25RadioIdentifier.createFrom(700_001), 1_100L);
            manager.processP1TrafficCurrentUser(frequency, APCO25RadioIdentifier.createFrom(700_001), 1_150L);
            assertTrue(manager.processP1TrafficCallEnd(frequency, 1_200L));
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }
        assertEquals(1, subscriber.events.size());
        assertEquals(2, subscriber.updates.size());
        P25CallStartEvent initial = subscriber.events.getFirst();
        assertNotNull(initial.callToken());
        assertNull(initial.identifierCollection().getFromIdentifier());
        for(P25CallUpdateEvent update: subscriber.updates)
        {
            assertEquals(initial.callToken(), update.call().callToken());
            assertEquals(initial.configurationId(), update.call().configurationId());
            assertEquals(initial.startedAtEpochMilliseconds(), update.call().startedAtEpochMilliseconds());
            assertEquals(frequency, update.call().frequencyHertz());
            assertEquals(APCO25RadioIdentifier.createFrom(700_001),
                update.call().identifierCollection().getFromIdentifier());
        }
        assertFalse(subscriber.updates.getFirst().complete());
        assertNull(subscriber.updates.getFirst().durationMilliseconds());
        assertTrue(subscriber.updates.getLast().complete());
        assertEquals(200L, subscriber.updates.getLast().durationMilliseconds());
    }

    @Test
    void clearAndEncryptedMetadataOnlyCallsDistinguishObservedEndFromReset()
    {
        for(boolean encrypted: List.of(false, true))
        {
            for(boolean observedEnd: List.of(false, true))
            {
                long frequency = 154_875_000L;
                Channel parent = new Channel("Conventional");
                parent.setDecodeConfiguration(new DecodeConfigP25Conventional());
                P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
                CallStartSubscriber subscriber = new CallStartSubscriber();
                MyEventBus.getGlobalEventBus().register(subscriber);
                try
                {
                    manager.processP1TrafficCallStart(frequency, APCO25Talkgroup.create(1201), null,
                        EncryptionKeyIdentifier.create(APCO25EncryptionKey.create(encrypted ? 0x84 : 0x80, 0)),
                        encrypted ? VoiceServiceOptions.createEncrypted() : VoiceServiceOptions.createUnencrypted(),
                        new StandardChannel(frequency), 1_000L);
                    assertTrue(manager.processTrafficCallEnd(frequency, TimeslotMessage.TIMESLOT_1,
                        observedEnd ? 1_450L : 1_700_000_000_000L, observedEnd));
                }
                finally
                {
                    MyEventBus.getGlobalEventBus().unregister(subscriber);
                }
                assertEquals(1, subscriber.events.size());
                assertEquals(1, subscriber.updates.size());
                assertTrue(subscriber.updates.getFirst().complete());
                assertEquals(observedEnd ? 450L : null, subscriber.updates.getFirst().durationMilliseconds(),
                    "Only the accepted RF interval is known; the manager has no audio module or codec");
            }
        }
    }

    @Test
    void nextPhaseTwoPttKeepsCallBoundariesWithoutInventingThePriorEndTime()
    {
        long frequency = 851_000_000L;
        int timeslot = TimeslotMessage.TIMESLOT_1;
        Channel parent = new Channel("Phase 2");
        parent.setDecodeConfiguration(new DecodeConfigP25Phase2());
        var manager = new P25TrafficChannelManager(parent);
        var subscriber = new CallStartSubscriber();
        var bits = new CorrectedBinaryMessage(144);
        bits.setInt(0x80, IntField.length8(80));
        bits.setInt(700_001, IntField.length24(104));
        bits.setInt(1201, IntField.length16(128));
        var ptt = new PushToTalk(bits);
        var identifiers = new IdentifierCollection(ptt.getIdentifiers());
        MyEventBus.getGlobalEventBus().register(subscriber);
        try
        {
            manager.processP2TrafficPushToTalk(frequency, timeslot, new StandardChannel(frequency), identifiers,
                ptt, 1_000L);
            manager.processP2TrafficVoice(frequency, timeslot, 1_100L);
            manager.processP2TrafficPushToTalk(frequency, timeslot, new StandardChannel(frequency), identifiers,
                ptt, 2_000L);
            assertTrue(manager.processP2TrafficEndPushToTalk(frequency, timeslot, 2_500L));
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }
        assertEquals(2, subscriber.events.size());
        assertNotEquals(subscriber.events.getFirst().callToken(), subscriber.events.getLast().callToken());
        var completed = subscriber.updates.stream().filter(P25CallUpdateEvent::complete).toList();
        assertEquals(2, completed.size());
        assertNull(completed.getFirst().durationMilliseconds(), "No previous EndPTT was received");
        assertEquals(500L, completed.getLast().durationMilliseconds());
    }

    @Test
    void aDifferentConventionalTalkerGetsANewTokenInsteadOfEnrichingThePreviousCall()
    {
        Channel parent = new Channel("Conventional");
        parent.setConfigurationId("00000000-0000-0000-0000-000000000404");
        parent.setDecodeConfiguration(new DecodeConfigP25Conventional());
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        CallStartSubscriber subscriber = new CallStartSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);
        try
        {
            manager.processP1TrafficCurrentUser(154_875_000L, null, DecodeEventType.CALL_GROUP,
                VoiceServiceOptions.createUnencrypted(), identifiers(1201,
                    APCO25RadioIdentifier.createFrom(700_001)), 1_000L, null);
            manager.processP1TrafficCurrentUser(154_875_000L, null, DecodeEventType.CALL_GROUP,
                VoiceServiceOptions.createUnencrypted(), identifiers(1201,
                    APCO25RadioIdentifier.createFrom(700_002)), 1_100L, null);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }
        assertEquals(2, subscriber.events.size());
        assertFalse(subscriber.events.getFirst().callToken().equals(subscriber.events.getLast().callToken()));
    }

    @Test
    void aChannelIdentifierDoesNotMakeUnchangedConventionalBroadcastsPublishUpdates() throws Exception
    {
        Channel parent = new Channel("Conventional");
        parent.setConfigurationId("00000000-0000-0000-0000-000000000405");
        parent.setDecodeConfiguration(new DecodeConfigP25Conventional());
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        MutableIdentifierCollection identifiers = identifiers(1201, APCO25RadioIdentifier.createFrom(700_001));
        APCO25Channel channelIdentifier = APCO25Channel.create(0, 1);
        identifiers.update(channelIdentifier);
        CallStartSubscriber subscriber = new CallStartSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);
        try
        {
            manager.processP1TrafficCurrentUser(154_875_000L, null, DecodeEventType.CALL_GROUP,
                VoiceServiceOptions.createUnencrypted(), identifiers, 1_000L, null);
            P25TrafficChannelEventTracker tracker = trafficTrackers(manager).get(154_875_000L);
            assertTrue(tracker.getEvent().getIdentifierCollection().hasIdentifier(channelIdentifier));
            manager.broadcast(tracker);
            manager.broadcast(tracker);
            assertEquals(1, subscriber.events.size());
            assertEquals(0, subscriber.updates.size());
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }
    }

    @Test
    void clearsFrequencyBandsWhenControlFrequencyChanges() throws Exception
    {
        Channel parentChannel = new Channel("Control");
        TestP25TrafficChannelManager manager = new TestP25TrafficChannelManager(parentChannel);
        manager.processFrequencyBand(band(0, 851_006_250L, 6250L, 1));

        assertEquals(1, frequencyBands(manager).size());

        manager.changeControlFrequency(851_012_500L, 852_012_500L, parentChannel);

        assertTrue(frequencyBands(manager).isEmpty());
        assertEquals(1L, parentChannel.getSiteEvidenceTuningGeneration(),
            "placement authority advances before facts from the new control source can escape");
    }

    @Test
    void controlFrequencyChangeDisablesTrafficChannelsAfterReleasingManagerLock() throws Exception
    {
        Channel parentChannel = new Channel("Control");
        Channel trafficChannel = new Channel("Traffic");
        TestP25TrafficChannelManager manager = new TestP25TrafficChannelManager(parentChannel);
        allocatedTrafficChannels(manager).put(851_012_500L, trafficChannel);
        ReentrantLock managerLock = managerLock(manager);
        AtomicReference<Channel> disabledChannel = new AtomicReference<>();
        AtomicReference<Boolean> callbackHeldManagerLock = new AtomicReference<>();
        manager.setChannelEventListener(event -> {
            disabledChannel.set(event.getChannel());
            callbackHeldManagerLock.set(managerLock.isHeldByCurrentThread());
        });

        manager.changeControlFrequency(851_000_000L, 852_000_000L, parentChannel);

        assertSame(trafficChannel, disabledChannel.get());
        assertEquals(Boolean.FALSE, callbackHeldManagerLock.get());
    }

    @Test
    void keepsLocalTalkerAliasButDoesNotPersistItWithoutAnActiveCall()
    {
        Channel parentChannel = new Channel("Control");
        parentChannel.setP25SiteIdentity(new P25SiteIdentity(0xBEE00, 0x348, 1, 1));
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parentChannel);
        RadioIdentifier radio = APCO25RadioIdentifier.createFrom(1811524);
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("CAR 201");
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Wacn.create(0xBEE00));
        identifiers.update(APCO25System.create(0x348));
        TalkerAliasSubscriber subscriber = new TalkerAliasSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            manager.processP1MotorolaTalkerAlias(851_012_500L, radio, APCO25Talkgroup.create(56138), alias,
                identifiers, 2000L);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertTrue(manager.getTalkerAliasManager().hasAlias(radio));
        assertNull(subscriber.event.get());
    }

    @Test
    void talkerAliasKeepsTheSystemCapturedWhenItsCallStarted()
    {
        long frequency = 851_012_500L;
        int talkgroup = 56_138;
        Channel parentChannel = new Channel("Control");
        parentChannel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        parentChannel.setP25SiteIdentity(new P25SiteIdentity(0xBEE00, 0x348, 1, 1));
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parentChannel);
        RadioIdentifier radio = APCO25RadioIdentifier.createFrom(1_811_524);
        MutableIdentifierCollection identifiers = identifiers(talkgroup, radio);
        manager.processP1TrafficCurrentUser(frequency, new StandardChannel(frequency),
            DecodeEventType.CALL_GROUP, VoiceServiceOptions.createUnencrypted(), identifiers, 1_000L, null);
        parentChannel.setP25SiteIdentity(new P25SiteIdentity(0xAAAAA, 0x123, 2, 2));
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("CAR 201");
        TalkerAliasSubscriber subscriber = new TalkerAliasSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            manager.processP1MotorolaTalkerAlias(frequency, radio, APCO25Talkgroup.create(talkgroup), alias,
                identifiers, 1_100L);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertNotNull(subscriber.event.get());
        assertEquals("p25:bee00:348", subscriber.event.get().radioSystemKey());
    }

    @Test
    void waitsForKnownRadioBeforeAcceptingHarrisTalkerAlias() throws Exception
    {
        long frequency = 851_012_500L;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(1201));
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
            .identifiers(identifiers)
            .build();
        P25TrafficChannelEventTracker tracker = new P25TrafficChannelEventTracker(event);
        trafficTrackers(manager).put(frequency, tracker);
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("DISPATCH");

        manager.processP1HarrisTalkerAlias(frequency, null, alias, identifiers, 1_100L);

        assertNull(talkerAlias(event.getIdentifierCollection()));
        RadioIdentifier radio = APCO25RadioIdentifier.createFrom(101);
        identifiers.update(radio);
        manager.processP1HarrisTalkerAlias(frequency, radio, alias, identifiers, 1_200L);
        assertOwnedAlias(alias, event.getIdentifierCollection());
    }

    @Test
    void harrisTextCannotSupplyANumericSourceFromTheDecoderCache() throws Exception
    {
        for(int timeslot: List.of(TimeslotMessage.TIMESLOT_1, TimeslotMessage.TIMESLOT_2))
        for(boolean known: List.of(false, true))
        for(RadioIdentifier radio: List.of(APCO25RadioIdentifier.createFrom(101),
            APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(0, 0xBEE00, 0x123, 7001)))
        {
            long frequency = 851_012_500L;
            P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
            P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
                .identifiers(identifiers(1201, known ? radio : null)).build();
            P25TrafficChannelEventTracker tracker = new P25TrafficChannelEventTracker(event, "p25:bee00:123");
            tracker.updateDurationTraffic(1_050L);
            trafficTrackers(manager, timeslot).put(frequency, tracker);
            P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("UNIT");

            if(timeslot == TimeslotMessage.TIMESLOT_1)
                manager.processP1HarrisTalkerAlias(frequency, radio, alias, identifiers(1201, radio), 1_100L);
            else
                assertEquals(known, manager.processP2HarrisTalkerAlias(frequency, timeslot, radio, alias,
                    identifiers(1201, radio), 1_100L));

            if(known)
            {
                assertSame(radio, event.getIdentifierCollection().getFromIdentifier());
                assertOwnedAlias(alias, event.getIdentifierCollection());
            }
            else
            {
                assertNull(event.getIdentifierCollection().getFromIdentifier());
                assertNull(talkerAlias(event.getIdentifierCollection()));
                assertFalse(manager.getTalkerAliasManager().hasAlias(radio, "p25:bee00:123"));
            }
        }
    }

    @Test
    void rejectsHarrisAliasWhenTrafficAndGrantRadiosDisagree() throws Exception
    {
        long frequency = 851_012_500L;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        RadioIdentifier subscriber = APCO25RadioIdentifier.createFrom(1_880_997);
        RadioIdentifier console = APCO25RadioIdentifier.createFrom(1_104);
        MutableIdentifierCollection grantIdentifiers = identifiers(56_132, subscriber);
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
            .identifiers(grantIdentifiers)
            .build();
        trafficTrackers(manager).put(frequency, new P25TrafficChannelEventTracker(event));
        MutableIdentifierCollection trafficIdentifiers = identifiers(56_132, console);
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("CDP #0997");
        TalkerAliasSubscriber aliasSubscriber = new TalkerAliasSubscriber();
        MyEventBus.getGlobalEventBus().register(aliasSubscriber);

        try
        {
            manager.processP1HarrisTalkerAlias(frequency, console, alias, trafficIdentifiers, 1_100L);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(aliasSubscriber);
        }

        assertNull(aliasSubscriber.event.get());
        assertFalse(manager.getTalkerAliasManager().hasAlias(subscriber));
        assertFalse(manager.getTalkerAliasManager().hasAlias(console));
        assertEquals(subscriber, event.getIdentifierCollection().getFromIdentifier());
        assertNull(talkerAlias(event.getIdentifierCollection()));
    }

    @Test
    void rejectsHarrisAliasForADifferentTrackedCall() throws Exception
    {
        long frequency = 851_012_500L;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        RadioIdentifier trackedRadio = APCO25RadioIdentifier.createFrom(1_880_997);
        RadioIdentifier observedRadio = APCO25RadioIdentifier.createFrom(1_104);
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
            .identifiers(identifiers(56_132, trackedRadio))
            .build();
        trafficTrackers(manager).put(frequency, new P25TrafficChannelEventTracker(event));
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("DISPATCH");

        manager.processP1HarrisTalkerAlias(frequency, observedRadio, alias,
            identifiers(56_106, observedRadio), 1_100L);

        assertFalse(manager.getTalkerAliasManager().hasAlias(observedRadio));
        assertFalse(manager.getTalkerAliasManager().hasAlias(trackedRadio));
        assertNull(talkerAlias(event.getIdentifierCollection()));
    }

    @Test
    void doesNotAttachLateMotorolaPhase1AliasToNextRadio() throws Exception
    {
        long frequency = 851_012_500L;
        int talkgroup = 56_132;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        RadioIdentifier firstRadio = APCO25FullyQualifiedRadioIdentifier.createFrom(1_880_997, 0xBEE00, 0x49F,
            1_880_997);
        RadioIdentifier nextRadio = APCO25RadioIdentifier.createFrom(1_880_998);
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
            .identifiers(identifiers(talkgroup, nextRadio))
            .build();
        P25TrafficChannelEventTracker tracker = new P25TrafficChannelEventTracker(event);
        tracker.updateDurationTraffic(1_050L);
        trafficTrackers(manager).put(frequency, tracker);
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("CDP #0997");

        manager.processP1MotorolaTalkerAlias(frequency, firstRadio, APCO25Talkgroup.create(talkgroup), alias,
            identifiers(talkgroup, firstRadio), 1_100L);

        assertTrue(manager.getTalkerAliasManager().hasAlias(firstRadio));
        assertNull(talkerAlias(event.getIdentifierCollection()));
    }

    @Test
    void attachesMotorolaPhase1AliasWhenRadioAndTalkgroupStillMatch() throws Exception
    {
        long frequency = 851_012_500L;
        int talkgroup = 56_132;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        RadioIdentifier localRadio = APCO25RadioIdentifier.createFrom(1_880_997);
        RadioIdentifier fullyQualifiedRadio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
            1_880_997, 0xBEE00, 0x49F, 1_880_997);
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
            .identifiers(identifiers(talkgroup, localRadio))
            .build();
        P25TrafficChannelEventTracker tracker = new P25TrafficChannelEventTracker(event);
        tracker.updateDurationTraffic(1_050L);
        trafficTrackers(manager).put(frequency, tracker);
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("CDP #0997");

        manager.processP1MotorolaTalkerAlias(frequency, fullyQualifiedRadio, APCO25Talkgroup.create(talkgroup), alias,
            identifiers(talkgroup, fullyQualifiedRadio), 1_100L);

        assertOwnedAlias(alias, event.getIdentifierCollection());
    }

    @Test
    void doesNotInferAWorkingAddressFromAnEqualCanonicalSubscriberNumber() throws Exception
    {
        long frequency = 851_012_500L;
        int talkgroup = 56_132;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        RadioIdentifier identityOnlyRadio = APCO25FullyQualifiedRadioIdentifier.createFrom(1_880_997, 0xBEE00,
            0x49F, 1_880_997);
        RadioIdentifier trackedLocalRadio = APCO25RadioIdentifier.createFrom(1_880_997);
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
            .identifiers(identifiers(talkgroup, trackedLocalRadio))
            .build();
        P25TrafficChannelEventTracker tracker = new P25TrafficChannelEventTracker(event);
        tracker.updateDurationTraffic(1_050L);
        trafficTrackers(manager).put(frequency, tracker);
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("CDP #0997");

        manager.processP1MotorolaTalkerAlias(frequency, identityOnlyRadio, APCO25Talkgroup.create(talkgroup), alias,
            identifiers(talkgroup, identityOnlyRadio), 1_100L);

        assertNull(talkerAlias(event.getIdentifierCollection()));
    }

    @Test
    void acceptsCanonicalHomeAliasForOrdinaryPhaseOneAndTwoCalls() throws Exception
    {
        for(int timeslot: List.of(TimeslotMessage.TIMESLOT_1, TimeslotMessage.TIMESLOT_2))
        {
            long frequency = 851_012_500L;
            int talkgroup = 10_003;
            Channel parent = new Channel("Control");
            parent.setDecodeConfiguration(new DecodeConfigP25Phase1());
            //Current receiver metadata may have changed; only the immutable call scope is authoritative.
            parent.setP25SiteIdentity(new P25SiteIdentity(0xABCDE, 0x124, 1, 2));
            P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
            RadioIdentifier local = APCO25RadioIdentifier.createFrom(10_900_077);
            APCO25FullyQualifiedRadioIdentifier canonical = APCO25FullyQualifiedRadioIdentifier.createFrom(
                10_900_077, 0xBEE00, 0x123, 10_900_077);
            P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
                .identifiers(identifiers(talkgroup, local)).build();
            P25TrafficChannelEventTracker tracker = new P25TrafficChannelEventTracker(event, "p25:bee00:123");
            tracker.updateDurationTraffic(1_050L);
            trafficTrackers(manager, timeslot).put(frequency, tracker);
            P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("UNIT 77");
            TalkerAliasSubscriber subscriber = new TalkerAliasSubscriber();
            MyEventBus.getGlobalEventBus().register(subscriber);
            try
            {
                if(timeslot == TimeslotMessage.TIMESLOT_1)
                {
                    manager.processP1MotorolaTalkerAlias(frequency, canonical, APCO25Talkgroup.create(talkgroup),
                        alias, identifiers(talkgroup, canonical), 1_100L);
                }
                else
                {
                    manager.processP2MotorolaTalkerAlias(frequency, timeslot, canonical,
                        APCO25Talkgroup.create(talkgroup), alias, identifiers(talkgroup, canonical), 1_100L);
                }
            }
            finally
            {
                MyEventBus.getGlobalEventBus().unregister(subscriber);
            }
            assertOwnedAlias(alias, event.getIdentifierCollection());
            assertSame(canonical, event.getIdentifierCollection().getFromIdentifier());
            assertNull(canonical.getWorkingAddress());
            assertNotNull(subscriber.event.get());
            assertEquals("p25:bee00:123", subscriber.event.get().radioSystemKey());
            assertEquals(1_000L, subscriber.event.get().callStartEpochMilliseconds());
            tracker.addIdentifierIfMissing(local);
            assertSame(canonical, event.getIdentifierCollection().getFromIdentifier());
            assertEquals(1_000L, event.getTimeStart());
        }
    }

    @Test
    void homeAliasStillRejectsOtherSystemTalkgroupAndCallGeneration() throws Exception
    {
        for(String rejection: List.of("other-home", "other-talkgroup", "early", "stale", "complete"))
        {
            long frequency = 851_012_500L;
            int talkgroup = 10_003;
            Channel parent = new Channel("Control");
            parent.setDecodeConfiguration(new DecodeConfigP25Phase1());
            P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
            RadioIdentifier local = APCO25RadioIdentifier.createFrom(10_900_077);
            RadioIdentifier canonical = APCO25FullyQualifiedRadioIdentifier.createFrom(10_900_077,
                0xBEE00, rejection.equals("other-home") ? 0x124 : 0x123, 10_900_077);
            P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
                .identifiers(identifiers(talkgroup, local)).build();
            P25TrafficChannelEventTracker tracker = new P25TrafficChannelEventTracker(event, "p25:bee00:123");
            tracker.updateDurationTraffic(1_050L);
            if(rejection.equals("complete")) tracker.completeTraffic(1_075L);
            trafficTrackers(manager).put(frequency, tracker);
            P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("UNIT 77");
            TalkerAliasSubscriber subscriber = new TalkerAliasSubscriber();
            MyEventBus.getGlobalEventBus().register(subscriber);
            try
            {
                manager.processP1MotorolaTalkerAlias(frequency, canonical,
                    APCO25Talkgroup.create(rejection.equals("other-talkgroup") ? talkgroup + 1 : talkgroup),
                    alias, identifiers(talkgroup, canonical), rejection.equals("early") ? 999L :
                        rejection.equals("stale") ? 4_000L : 1_100L);
            }
            finally
            {
                MyEventBus.getGlobalEventBus().unregister(subscriber);
            }
            assertNull(talkerAlias(event.getIdentifierCollection()), rejection);
            assertSame(local, event.getIdentifierCollection().getFromIdentifier(), rejection);
            assertNull(subscriber.event.get(), rejection);
        }
    }

    @Test
    void doesNotAttachAliasWhenCanonicalSubscribersShareAWorkingAddress() throws Exception
    {
        long frequency = 851_012_500L;
        int talkgroup = 56_132;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        RadioIdentifier observedRadio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501,
            0xBEE00, 0x49F, 1_880_997);
        RadioIdentifier trackedRadio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501,
            0xBEE00, 0x49F, 1_880_998);
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
            .identifiers(identifiers(talkgroup, trackedRadio))
            .build();
        P25TrafficChannelEventTracker tracker = new P25TrafficChannelEventTracker(event);
        tracker.updateDurationTraffic(1_050L);
        trafficTrackers(manager).put(frequency, tracker);
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("CDP #0997");

        manager.processP1MotorolaTalkerAlias(frequency, observedRadio, APCO25Talkgroup.create(talkgroup), alias,
            identifiers(talkgroup, observedRadio), 1_100L);

        assertNull(talkerAlias(event.getIdentifierCollection()));
    }

    @Test
    void doesNotAttachAliasWhenHomeSystemsShareASubscriberNumber() throws Exception
    {
        long frequency = 851_012_500L;
        int talkgroup = 56_132;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        RadioIdentifier observedRadio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501,
            0xBEE00, 0x49F, 1_880_997);
        RadioIdentifier trackedRadio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(777,
            0xABCDE, 0x123, 1_880_997);
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
            .identifiers(identifiers(talkgroup, trackedRadio))
            .build();
        P25TrafficChannelEventTracker tracker = new P25TrafficChannelEventTracker(event);
        tracker.updateDurationTraffic(1_050L);
        trafficTrackers(manager).put(frequency, tracker);
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("CDP #0997");

        manager.processP1MotorolaTalkerAlias(frequency, observedRadio, APCO25Talkgroup.create(talkgroup), alias,
            identifiers(talkgroup, observedRadio), 1_100L);

        assertNull(talkerAlias(event.getIdentifierCollection()));
    }

    @Test
    void doesNotAttachLateMotorolaPhase2AliasToNextRadio() throws Exception
    {
        long frequency = 851_012_500L;
        int talkgroup = 56_132;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        RadioIdentifier firstRadio = APCO25FullyQualifiedRadioIdentifier.createFrom(1_880_997, 0xBEE00, 0x49F,
            1_880_997);
        RadioIdentifier nextRadio = APCO25RadioIdentifier.createFrom(1_880_998);
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
            .identifiers(identifiers(talkgroup, nextRadio))
            .build();
        P25TrafficChannelEventTracker tracker = new P25TrafficChannelEventTracker(event);
        tracker.updateDurationTraffic(1_050L);
        trafficTrackers(manager, TimeslotMessage.TIMESLOT_2).put(frequency, tracker);
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("CDP #0997");

        manager.processP2MotorolaTalkerAlias(frequency, TimeslotMessage.TIMESLOT_2, firstRadio,
            APCO25Talkgroup.create(talkgroup), alias, identifiers(talkgroup, firstRadio), 1_100L);

        assertTrue(manager.getTalkerAliasManager().hasAlias(firstRadio));
        assertNull(talkerAlias(event.getIdentifierCollection()));
    }

    @Test
    void rejectsPhase2HarrisAliasWhenTrafficAndGrantRadiosDisagree() throws Exception
    {
        long frequency = 851_012_500L;
        RadioIdentifier trackedRadio = APCO25RadioIdentifier.createFrom(1_880_997);
        RadioIdentifier observedRadio = APCO25RadioIdentifier.createFrom(1_104);
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
            .identifiers(identifiers(56_132, trackedRadio))
            .build();
        trafficTrackers(manager, TimeslotMessage.TIMESLOT_2).put(frequency,
            new P25TrafficChannelEventTracker(event));
        P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("CDP #0997");

        assertFalse(manager.processP2HarrisTalkerAlias(frequency, TimeslotMessage.TIMESLOT_2, observedRadio, alias,
            identifiers(56_132, observedRadio), 1_100L));

        assertFalse(manager.getTalkerAliasManager().hasAlias(trackedRadio));
        assertFalse(manager.getTalkerAliasManager().hasAlias(observedRadio));
        assertNull(talkerAlias(event.getIdentifierCollection()));
    }

    @Test
    void rejectsAlreadyOwnedHarrisAliasForAnotherRadioWithoutATracker()
    {
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        RadioIdentifier currentRadio = APCO25RadioIdentifier.createFrom(101);
        RadioIdentifier otherRadio = APCO25RadioIdentifier.createFrom(202);
        TalkerAliasIdentifier wrongOwner = P25TalkerAliasIdentifier.create("UNIT 202").bindTo(otherRadio);

        assertFalse(manager.processP2HarrisTalkerAlias(851_012_500L, TimeslotMessage.TIMESLOT_2,
            currentRadio, wrongOwner, identifiers(1201, currentRadio), 1_100L));

        assertFalse(manager.getTalkerAliasManager().hasAlias(currentRadio));
        assertFalse(manager.getTalkerAliasManager().hasAlias(otherRadio));
    }

    @Test
    void phaseTwoHarrisAliasRequiresTheMatchingOpenCall() throws Exception
    {
        for(String state: List.of("absent", "complete", "stale", "early", "other-talkgroup", "active"))
        {
            long frequency = 851_012_500L;
            P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
            RadioIdentifier radio = APCO25RadioIdentifier.createFrom(101);
            P25ChannelGrantEvent event = P25ChannelGrantEvent.builder(DecodeEventType.CALL_GROUP, 1_000L, null)
                .identifiers(identifiers(1201, radio)).build();
            P25TrafficChannelEventTracker tracker = new P25TrafficChannelEventTracker(event, "p25:bee00:123");
            tracker.updateDurationTraffic(1_050);
            if(state.equals("complete")) tracker.completeTraffic(1_075);
            if(!state.equals("absent")) trafficTrackers(manager, TimeslotMessage.TIMESLOT_2).put(frequency, tracker);
            P25TalkerAliasIdentifier alias = P25TalkerAliasIdentifier.create("UNIT 101");

            boolean accepted = manager.processP2HarrisTalkerAlias(frequency, TimeslotMessage.TIMESLOT_2, radio,
                alias, identifiers(state.equals("other-talkgroup") ? 1202 : 1201, radio),
                state.equals("early") ? 999 : state.equals("stale") ? 5_000 : 1_100);

            assertEquals(state.equals("active"), accepted, state);
            if(accepted) assertOwnedAlias(alias, event.getIdentifierCollection());
            else assertNull(talkerAlias(event.getIdentifierCollection()), state);
            assertEquals(accepted, manager.getTalkerAliasManager().hasAlias(radio, "p25:bee00:123"), state);
        }
    }

    @Test
    void sourceLessGrantUpdatesReuseIncompleteCallAndPreserveRadio() throws Exception
    {
        long frequency = 853_875_000L;
        P25TrafficChannelManager manager = new P25TrafficChannelManager(new Channel("Control"));
        APCO25Channel channel = APCO25Channel.create(0, 459);
        P25FrequencyBand frequencyBand =
            new P25FrequencyBand(0, 851_006_250L, -45_000_000L, 6_250L, 12_500, 1);
        manager.processFrequencyBand(frequencyBand);
        manager.processFrequencyBand(frequencyBand);
        channel.setFrequencyBand(frequencyBand);
        assertEquals(frequency, channel.getDownlinkFrequency());
        RadioIdentifier source = APCO25RadioIdentifier.createFrom(1234567);
        MutableIdentifierCollection grantIdentifiers = identifiers(1201, source);

        manager.processP1ControlDirectedChannelGrant(channel, null, grantIdentifiers,
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_000L);
        P25TrafficChannelEventTracker initial = trafficTrackers(manager).get(frequency);
        assertNotNull(initial);

        MutableIdentifierCollection updateIdentifiers = identifiers(1201, null);
        manager.processP1ControlAnnouncedTrafficUpdate(channel, null, updateIdentifiers,
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE, 4_000L);
        manager.processP1ControlAnnouncedTrafficUpdate(channel, null, updateIdentifiers,
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE, 7_000L);

        P25TrafficChannelEventTracker continued = trafficTrackers(manager).get(frequency);
        assertSame(initial, continued);
        assertEquals(source, continued.getEvent().getIdentifierCollection().getFromIdentifier());

        assertTrue(initial.completeTraffic(7_100L));
        manager.processP1ControlAnnouncedTrafficUpdate(channel, null, updateIdentifiers,
            Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE, 7_200L);
        assertNotSame(initial, trafficTrackers(manager).get(frequency));
    }

    @Test
    void phaseOneGrantTrafficEnrichmentAndRepeatedGrantPublishOnlyOneCallStart() throws Exception
    {
        assertGrantTrafficEnrichmentKeepsOneCallStart(false);
    }

    @Test
    void phaseTwoGrantTrafficEnrichmentAndRepeatedGrantPublishOnlyOneCallStart() throws Exception
    {
        assertGrantTrafficEnrichmentKeepsOneCallStart(true);
    }

    @Test
    void acceptedAttributionSurvivesDecoderCleanupAndLateControlUpdates() throws Exception
    {
        RadioIdentifier source = APCO25RadioIdentifier.createFrom(700_001);
        for(int timeslot: List.of(TimeslotMessage.TIMESLOT_1, TimeslotMessage.TIMESLOT_2))
        {
            for(boolean phaseOneControl: List.of(false, true))
            {
                PhaseTwoAudioCall call = phaseTwoAudioCall(timeslot, null);
                AudioActivityCallEvent bound = call.manager().processP2TrafficVoice(call.frequency(), timeslot, 1_100L);
                assertNull(bound.attribution().source());
                grantPhaseTwoCall(call.manager(), call.channel(), phaseOneControl, source, 1_300L);
                assertEquals(source, bound.attribution().source());
                assertTrue(call.manager().processP2TrafficEndPushToTalk(call.frequency(), timeslot, 1_500L));
                assertTrue(bound.attribution().isComplete());
                assertEquals(source, bound.attribution().source());
                assertNull(call.audioIdentifiers().getFromIdentifier(), "Attribution never seeds decoder metadata");
            }
        }
    }

    @Test
    void attributionIsBoundToOneTrackerAndRejectsOldTraffic() throws Exception
    {
        PhaseTwoAudioCall call = phaseTwoAudioCall(TimeslotMessage.TIMESLOT_1, null);
        AudioActivityCallEvent first = call.manager().processP2TrafficVoice(call.frequency(), call.timeslot(), 1_100L);
        RadioIdentifier firstSource = APCO25RadioIdentifier.createFrom(700_001);
        RadioIdentifier secondSource = APCO25RadioIdentifier.createFrom(700_002);
        grantPhaseTwoCall(call.manager(), call.channel(), false, firstSource, 1_200L);
        grantPhaseTwoCall(call.manager(), call.channel(), false, secondSource, 1_300L);
        AudioActivityCallEvent next = call.manager().processP2TrafficVoice(call.frequency(), call.timeslot(), 1_400L);
        assertNotSame(first.attribution(), next.attribution());
        assertTrue(first.attribution().isComplete());
        assertFalse(first.token().equals(next.token()));
        assertEquals(firstSource, first.attribution().source());
        assertEquals(secondSource, next.attribution().source());
        assertNull(call.manager().processP2TrafficVoice(call.frequency(), call.timeslot(), 1_200L));
        assertNull(call.manager().processP2TrafficVoice(call.frequency() + 12_500L, call.timeslot(), 1_400L));
        assertNull(call.manager().processP2TrafficVoice(call.frequency(), 3, 1_400L));
    }

    @Test
    void controlSourceResetExpiresPendingCallAttribution() throws Exception
    {
        PhaseTwoAudioCall call = phaseTwoAudioCall(TimeslotMessage.TIMESLOT_1, null);
        AudioActivityCallEvent event = call.manager().processP2TrafficVoice(call.frequency(), call.timeslot(), 1_100L);
        call.manager().resetControlSourceState();
        assertTrue(event.attribution().isComplete());
        assertNull(call.manager().processP2TrafficVoice(call.frequency(), call.timeslot(), 1_200L));
    }

    @Test
    void acceptedAttributionRejectsReservedRadioSources() throws Exception
    {
        for(int source: List.of(0, 0xFFFFFD, 0xFFFFFE, 0xFFFFFF))
        {
            PhaseTwoAudioCall call = phaseTwoAudioCall(TimeslotMessage.TIMESLOT_1,
                APCO25RadioIdentifier.createFrom(source));
            assertNull(call.manager().processP2TrafficVoice(call.frequency(), call.timeslot(), 1_100L).attribution().source());
        }
    }

    @Test
    void audioReadsAcceptedAttributionWithoutWaitingForManagerLock() throws Exception
    {
        PhaseTwoAudioCall call = phaseTwoAudioCall(TimeslotMessage.TIMESLOT_1,
            APCO25RadioIdentifier.createFrom(700_001));
        AudioActivityCallEvent event = call.manager().processP2TrafficVoice(call.frequency(), call.timeslot(), 1_100L);
        ReentrantLock lock = managerLock(call.manager());
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            lock.lock();
            try
            {
                acquired.countDown();
                release.await();
            }
            catch(InterruptedException interrupted)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                lock.unlock();
            }
        });
        holder.start();
        try
        {
            assertTrue(acquired.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(250), () ->
                assertEquals(APCO25RadioIdentifier.createFrom(700_001), event.attribution().source()));
        }
        finally
        {
            release.countDown();
            holder.join(2_000L);
        }
        assertFalse(holder.isAlive());
    }

    private static PhaseTwoAudioCall phaseTwoAudioCall(int timeslot, RadioIdentifier source) throws Exception
    {
        Channel parent = new Channel("Control", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigP25Phase2());
        parent.setSourceConfiguration(new SourceConfigRemote());
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        P25FrequencyBand band = new P25FrequencyBand(0, 851_012_500L, -45_000_000L, 12_500L, 12_500, 2);
        manager.processFrequencyBand(band);
        manager.processFrequencyBand(band);
        APCO25Channel channel = APCO25Channel.create(0, timeslot - 1);
        channel.setFrequencyBand(band);
        long frequency = channel.getDownlinkFrequency();
        grantPhaseTwoCall(manager, channel, false, source, 1_000L);
        manager.processP2TrafficVoice(frequency, timeslot, 1_000L);
        manager.processP2TrafficCurrentUser(frequency, timeslot, APCO25Nac.create(0x123), 1_000L);
        MutableIdentifierCollection audioIdentifiers = identifiers(1201, null);
        audioIdentifiers.update(APCO25Nac.create(0x123));
        return new PhaseTwoAudioCall(manager, channel, timeslot, frequency, audioIdentifiers,
            trafficTrackers(manager, timeslot).get(frequency));
    }

    private record PhaseTwoAudioCall(P25TrafficChannelManager manager, APCO25Channel channel, int timeslot,
                                    long frequency, MutableIdentifierCollection audioIdentifiers,
                                    P25TrafficChannelEventTracker tracker)
    {
    }

    @Test
    void phaseTwoMacGrantRecoversLateRadioAndKeepsTalkersSeparate() throws Exception
    {
        assertPhaseTwoGrantRecoversLateRadio(false);
    }

    @Test
    void phaseTwoTsbkGrantRecoversLateRadioAndKeepsTalkersSeparate() throws Exception
    {
        assertPhaseTwoGrantRecoversLateRadio(true);
    }

    private static void assertPhaseTwoGrantRecoversLateRadio(boolean phaseOneControl) throws Exception
    {
        for(int timeslot: List.of(TimeslotMessage.TIMESLOT_1, TimeslotMessage.TIMESLOT_2))
        {
            Channel parent = new Channel("Control", Channel.ChannelType.STANDARD);
            parent.setDecodeConfiguration(phaseOneControl ? new DecodeConfigP25Phase1() : new DecodeConfigP25Phase2());
            parent.setSourceConfiguration(new SourceConfigRemote());
            P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
            P25FrequencyBand band = new P25FrequencyBand(0, 851_012_500L, -45_000_000L, 12_500L, 12_500, 2);
            manager.processFrequencyBand(band);
            manager.processFrequencyBand(band);
            APCO25Channel channel = APCO25Channel.create(0, timeslot - 1);
            channel.setFrequencyBand(band);
            assertEquals(timeslot, channel.getTimeslot());
            long frequency = channel.getDownlinkFrequency();
            RadioIdentifier firstSource = APCO25RadioIdentifier.createFrom(700_001);
            RadioIdentifier nextSource = APCO25RadioIdentifier.createFrom(700_002);

            grantPhaseTwoCall(manager, channel, phaseOneControl, null, 1_000L);
            P25TrafficChannelEventTracker initial = trafficTrackers(manager, timeslot).get(frequency);
            assertNotNull(initial);
            assertNull(initial.getEvent().getIdentifierCollection().getFromIdentifier());

            grantPhaseTwoCall(manager, channel, phaseOneControl, firstSource, 1_300L);
            assertSame(initial, trafficTrackers(manager, timeslot).get(frequency));
            assertEquals(firstSource, initial.getEvent().getIdentifierCollection().getFromIdentifier());

            if(phaseOneControl)
            {
                manager.processP1ControlAnnouncedTrafficUpdate(channel, null, identifiers(1201, null),
                    Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE, 1_400L);
            }
            else
            {
                manager.processP2ChannelUpdate(channel, null, identifiers(1201, null),
                    MacOpcode.PHASE1_42_GROUP_VOICE_CHANNEL_GRANT_UPDATE_IMPLICIT, 1_400L);
            }

            assertSame(initial, trafficTrackers(manager, timeslot).get(frequency));
            assertEquals(firstSource, initial.getEvent().getIdentifierCollection().getFromIdentifier());

            grantPhaseTwoCall(manager, channel, phaseOneControl, nextSource, 1_500L);
            P25TrafficChannelEventTracker next = trafficTrackers(manager, timeslot).get(frequency);
            assertNotSame(initial, next);
            assertEquals(nextSource, next.getEvent().getIdentifierCollection().getFromIdentifier());
            assertEquals(1_500L, next.getEvent().getTimeStart());
            assertEquals(firstSource, initial.getEvent().getIdentifierCollection().getFromIdentifier());
        }
    }

    private static void grantPhaseTwoCall(P25TrafficChannelManager manager, APCO25Channel channel,
                                          boolean phaseOneControl, RadioIdentifier source, long timestamp)
    {
        if(phaseOneControl)
        {
            manager.processP1ControlDirectedChannelGrant(channel, VoiceServiceOptions.createUnencrypted(),
                identifiers(1201, source), Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, timestamp);
        }
        else
        {
            manager.processP2ChannelGrant(channel, VoiceServiceOptions.createUnencrypted(), identifiers(1201, source),
                MacOpcode.PHASE1_40_GROUP_VOICE_CHANNEL_GRANT_IMPLICIT, timestamp);
        }
    }

    private static void assertGrantTrafficEnrichmentKeepsOneCallStart(boolean phaseTwo) throws Exception
    {
        Channel parent = new Channel("Control", Channel.ChannelType.STANDARD);
        parent.setConfigurationId("00000000-0000-0000-0000-000000000402");
        parent.setDecodeConfiguration(phaseTwo ? new DecodeConfigP25Phase2() : new DecodeConfigP25Phase1());
        parent.setSourceConfiguration(new SourceConfigRemote());
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        P25FrequencyBand band = new P25FrequencyBand(0, 851_012_500L, -45_000_000L, 12_500L, 12_500,
            phaseTwo ? 2 : 1);
        manager.processFrequencyBand(band);
        manager.processFrequencyBand(band);
        APCO25Channel channel = APCO25Channel.create(0, phaseTwo ? 1 : 0);
        channel.setFrequencyBand(band);
        long frequency = channel.getDownlinkFrequency();
        int timeslot = phaseTwo ? TimeslotMessage.TIMESLOT_2 : TimeslotMessage.TIMESLOT_1;
        MutableIdentifierCollection abbreviated = identifiers(1201, APCO25RadioIdentifier.createFrom(1234567));
        RadioIdentifier qualifiedRadio = APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
            1234567, 0xABCDE, 0x123, 7654321);
        Identifier<?> qualifiedGroup = APCO25FullyQualifiedTalkgroupIdentifier.createToWithLocalAddress(
            1201, 0xABCDE, 0x123, 2201);
        MutableIdentifierCollection enriched = new MutableIdentifierCollection();
        enriched.update(qualifiedGroup);
        enriched.update(qualifiedRadio);
        CallStartSubscriber subscriber = new CallStartSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            if(phaseTwo)
            {
                manager.processP2ChannelGrant(channel, VoiceServiceOptions.createUnencrypted(), abbreviated,
                    MacOpcode.PHASE1_40_GROUP_VOICE_CHANNEL_GRANT_IMPLICIT, 1_000L);
            }
            else
            {
                manager.processP1ControlDirectedChannelGrant(channel, VoiceServiceOptions.createUnencrypted(),
                    abbreviated, Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_000L);
            }

            P25TrafficChannelEventTracker initial = trafficTrackers(manager, timeslot).get(frequency);
            assertNotNull(initial);
            assertEquals(1, subscriber.events.size());

            if(phaseTwo)
            {
                manager.processP2TrafficCurrentUser(frequency, timeslot, channel,
                    VoiceServiceOptions.createUnencrypted(), MacOpcode.TDMA_21_GROUP_VOICE_CHANNEL_USER_EXTENDED,
                    enriched, 1_037L, null);
                manager.processP2ChannelGrant(channel, VoiceServiceOptions.createUnencrypted(), abbreviated,
                    MacOpcode.PHASE1_40_GROUP_VOICE_CHANNEL_GRANT_IMPLICIT, 1_075L);
            }
            else
            {
                manager.processP1TrafficCurrentUser(frequency, channel, DecodeEventType.CALL_GROUP,
                    VoiceServiceOptions.createUnencrypted(), enriched, 1_037L, null);
                manager.processP1ControlDirectedChannelGrant(channel, VoiceServiceOptions.createUnencrypted(),
                    abbreviated, Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_075L);
            }

            assertSame(initial, trafficTrackers(manager, timeslot).get(frequency));
            assertEquals(1, subscriber.events.size(), "Enrichment and the repeated grant must not announce another call");
            assertSame(qualifiedRadio, initial.getEvent().getIdentifierCollection().getFromIdentifier());
            assertSame(qualifiedGroup, initial.getEvent().getIdentifierCollection().getToIdentifier());

            MutableIdentifierCollection conflicting = new MutableIdentifierCollection();
            conflicting.update(qualifiedGroup);
            conflicting.update(APCO25FullyQualifiedRadioIdentifier.createFrom(
                1234567, 0xABCDE, 0x123, 7654322));

            if(phaseTwo)
            {
                manager.processP2ChannelGrant(channel, VoiceServiceOptions.createUnencrypted(), conflicting,
                    MacOpcode.PHASE1_40_GROUP_VOICE_CHANNEL_GRANT_IMPLICIT, 1_150L);
            }
            else
            {
                manager.processP1ControlDirectedChannelGrant(channel, VoiceServiceOptions.createUnencrypted(),
                    conflicting, Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT, 1_150L);
            }

            assertNotSame(initial, trafficTrackers(manager, timeslot).get(frequency));
            assertEquals(2, subscriber.events.size(), "A conflicting known home radio remains a different call");
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }
    }

    @Test
    void phaseTwoDataPublishesBothSlotsAndAllocatesTheSharedCarrierOnce()
    {
        Channel parent = new Channel("Control", Channel.ChannelType.STANDARD);
        DecodeConfigP25Phase2 config = new DecodeConfigP25Phase2();
        config.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(config);
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        StartRequestSubscriber subscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);
        manager.setInterModuleEventBus(eventBus);
        APCO25Channel channel = dataChannel(manager);
        List<Integer> slots = new java.util.ArrayList<>();
        manager.addDecodeEventListener(event -> {
            slots.add(event.getTimeslot());
            assertEquals(event.getTimeslot(), ((APCO25Channel)event.getChannelDescriptor()).getTimeslot());
        });

        manager.processP2DataChannel(channel, 1_000L);
        assertEquals(List.of(1, 2), slots);
        assertEquals(1, subscriber.requests.size());
        manager.processP2DataChannel(channel, 1_100L);
        assertEquals(List.of(1, 2, 1, 2), slots);
        assertEquals(1, subscriber.requests.size(), "Two data slots share one carrier allocation");
    }

    @Test
    void ignoredPhaseTwoDataStillPublishesBothSlotsWithoutAllocation()
    {
        Channel parent = new Channel("Control", Channel.ChannelType.STANDARD);
        DecodeConfigP25Phase2 config = new DecodeConfigP25Phase2();
        config.setTrafficChannelPoolSize(1);
        config.setIgnoreDataCalls(true);
        parent.setDecodeConfiguration(config);
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        StartRequestSubscriber subscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);
        manager.setInterModuleEventBus(eventBus);
        List<Integer> slots = new java.util.ArrayList<>();
        manager.addDecodeEventListener(event -> slots.add(event.getTimeslot()));

        manager.processP2DataChannel(dataChannel(manager), 1_000L);
        assertEquals(List.of(1, 2), slots);
        assertTrue(subscriber.requests.isEmpty());
    }

    @Test
    void phaseTwoDataRollsBothLongLivedTrackersOverTogether() throws Exception
    {
        Channel parent = new Channel("Control", Channel.ChannelType.STANDARD);
        DecodeConfigP25Phase2 config = new DecodeConfigP25Phase2();
        config.setTrafficChannelPoolSize(0);
        parent.setDecodeConfiguration(config);
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        manager.setInterModuleEventBus(new EventBus());
        APCO25Channel channel = dataChannel(manager);
        List<Integer> slots = new java.util.ArrayList<>();
        manager.addDecodeEventListener(event -> slots.add(event.getTimeslot()));
        manager.processP2DataChannel(channel, 1_000L);
        P25TrafficChannelEventTracker first = trafficTrackers(manager, 1).get(channel.getDownlinkFrequency());
        P25TrafficChannelEventTracker second = trafficTrackers(manager, 2).get(channel.getDownlinkFrequency());

        for(long timestamp = 2_000L; timestamp <= 18_000L; timestamp += 1_000L)
        {
            slots.clear();
            manager.processP2DataChannel(channel, timestamp);
            assertEquals(List.of(1, 2), slots);
        }

        assertNotSame(first, trafficTrackers(manager, 1).get(channel.getDownlinkFrequency()));
        assertNotSame(second, trafficTrackers(manager, 2).get(channel.getDownlinkFrequency()));
        assertEquals(18_000L, trafficTrackers(manager, 1).get(channel.getDownlinkFrequency()).getEvent().getTimeStart());
        assertEquals(18_000L, trafficTrackers(manager, 2).get(channel.getDownlinkFrequency()).getEvent().getTimeStart());
    }

    private static APCO25Channel dataChannel(P25TrafficChannelManager manager)
    {
        IFrequencyBand band = band(0, 851_000_000L, 12_500L, 2);
        manager.processFrequencyBand(band);
        manager.processFrequencyBand(band);
        APCO25Channel channel = APCO25Channel.create(0, 0);
        channel.setFrequencyBand(band);
        return channel;
    }

    @SuppressWarnings("unchecked")
    private static Map<Integer,IFrequencyBand> frequencyBands(P25TrafficChannelManager manager) throws Exception
    {
        Field field = P25TrafficChannelManager.class.getDeclaredField("mFrequencyBandMap");
        field.setAccessible(true);
        return (Map<Integer,IFrequencyBand>)field.get(manager);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long,P25TrafficChannelEventTracker> trafficTrackers(P25TrafficChannelManager manager)
        throws Exception
    {
        return trafficTrackers(manager, TimeslotMessage.TIMESLOT_1);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long,P25TrafficChannelEventTracker> trafficTrackers(P25TrafficChannelManager manager,
                                                                           int timeslot) throws Exception
    {
        Field field = P25TrafficChannelManager.class.getDeclaredField(timeslot == TimeslotMessage.TIMESLOT_2 ?
            "mTS2ChannelGrantEventMap" : "mTS1ChannelGrantEventMap");
        field.setAccessible(true);
        return (Map<Long,P25TrafficChannelEventTracker>)field.get(manager);
    }

    @SuppressWarnings("unchecked")
    private static Map<Long,Channel> allocatedTrafficChannels(P25TrafficChannelManager manager) throws Exception
    {
        Field field = P25TrafficChannelManager.class.getDeclaredField("mAllocatedTrafficChannelMap");
        field.setAccessible(true);
        return (Map<Long,Channel>)field.get(manager);
    }

    private static ReentrantLock managerLock(P25TrafficChannelManager manager) throws Exception
    {
        Field field = P25TrafficChannelManager.class.getDeclaredField("mLock");
        field.setAccessible(true);
        return (ReentrantLock)field.get(manager);
    }

    private static IFrequencyBand band(int identifier, long base, long spacing, int timeslots)
    {
        return new P25FrequencyBand(identifier, base, -45_000_000L, spacing, 12_500, timeslots);
    }

    private static MutableIdentifierCollection identifiers(int talkgroup, RadioIdentifier source)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(talkgroup));

        if(source != null)
        {
            identifiers.update(source);
        }

        return identifiers;
    }

    private static TalkerAliasIdentifier talkerAlias(IdentifierCollection identifiers)
    {
        return (TalkerAliasIdentifier)identifiers.getIdentifier(IdentifierClass.USER, Form.TALKER_ALIAS, Role.FROM);
    }

    private static void assertOwnedAlias(TalkerAliasIdentifier expected, IdentifierCollection identifiers)
    {
        TalkerAliasIdentifier observed = talkerAlias(identifiers);
        assertNotNull(observed);
        assertEquals(expected.getValue(), observed.getValue());
        assertTrue(observed.isBound());
        assertTrue(observed.matchesContext(identifiers));
    }

    private static class TestP25TrafficChannelManager extends P25TrafficChannelManager
    {
        private TestP25TrafficChannelManager(Channel parentChannel)
        {
            super(parentChannel);
        }

        private void changeControlFrequency(long previous, long current, Channel parentChannel)
        {
            processControlFrequencyUpdate(previous, current, parentChannel);
        }
    }

    private static class TalkerAliasSubscriber
    {
        private final AtomicReference<TrunkedTalkerAliasEvent> event = new AtomicReference<>();

        @Subscribe
        public void receive(TrunkedTalkerAliasEvent talkerAliasEvent)
        {
            event.set(talkerAliasEvent);
        }
    }

    private static class StartRequestSubscriber
    {
        private final List<ChannelStartProcessingRequest> requests = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(ChannelStartProcessingRequest request)
        {
            requests.add(request);
        }
    }

    private static class CallStartSubscriber
    {
        private final List<P25CallStartEvent> events = new CopyOnWriteArrayList<>();
        private final List<P25CallUpdateEvent> updates = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(P25CallStartEvent callStartEvent)
        {
            events.add(callStartEvent);
        }

        @Subscribe
        public void receive(P25CallUpdateEvent update)
        {
            updates.add(update);
        }
    }
}
