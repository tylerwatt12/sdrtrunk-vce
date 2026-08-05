/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.nxdn;

import com.google.common.eventbus.Subscribe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import io.github.dsheirer.module.decode.nxdn.channel.ChannelFrequency;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannel;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelFake;
import io.github.dsheirer.module.decode.nxdn.channel.NXDNChannelLookup;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNEncryptionKey;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNRadioIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkerAliasIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNTalkgroupIdentifier;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCallAssignment;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallTimer;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallType;
import io.github.dsheirer.module.decode.nxdn.layer3.type.ChannelAccessInformation;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.module.decode.nxdn.layer3.type.VoiceCallOption;
import io.github.dsheirer.module.decode.traffic.TrunkedCallStartEvent;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedTalkerAliasEvent;
import io.github.dsheirer.preference.nowplaying.NowPlayingPreference;
import io.github.dsheirer.protocol.Protocol;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class NXDNTrafficChannelManagerTest
{
    @Test
    void displaysEncryptionKeyIdAsHexadecimalInEventDetails()
    {
        Channel parent = new Channel("NXDN Site", Channel.ChannelType.STANDARD);
        DecodeConfigNXDN config = new DecodeConfigNXDN();
        config.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(config);
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        List<IDecodeEvent> events = new CopyOnWriteArrayList<>();
        manager.addDecodeEventListener(events::add);
        EncryptionKeyIdentifier encryption = EncryptionKeyIdentifier.create(Protocol.NXDN,
            NXDNEncryptionKey.create(0x03, 0x2A));

        manager.processVoiceCall(identifiers(101, 91, encryption), channel(452_012_500L),
            CallType.GROUP_BROADCAST, encryption, 1_000L, new VoiceCallOption(0), CallTimer.UNSPECIFIED);

        assertEquals(1, events.size());
        assertEquals("AES256 K:2A TIMER:UNSPECIFIED AMBE+ HALF-RATE 4800", events.getFirst().getDetails());
        assertEquals("AES KEY:42", encryption.toString());
    }

    @Test
    void conventionalModeDoesNotCreateOrUseTrafficPool() throws Exception
    {
        Channel parent = new Channel("NXDN Conventional", Channel.ChannelType.STANDARD);
        DecodeConfigNXDN config = new DecodeConfigNXDN();
        config.setChannelMode(NXDNChannelMode.CONVENTIONAL);
        config.setTrafficChannelPoolSize(2);
        parent.setDecodeConfiguration(config);
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        EncryptionKeyIdentifier clear = EncryptionKeyIdentifier.create(Protocol.NXDN,
            NXDNEncryptionKey.create(0, 0));
        CallStartSubscriber subscriber = new CallStartSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            manager.processVoiceCall(identifiers(101, 91, clear), channel(452_012_500L),
                CallType.GROUP_BROADCAST, clear, 1_000L, new VoiceCallOption(0), CallTimer.UNSPECIFIED);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        Field field = NXDNTrafficChannelManager.class.getDeclaredField("mManagedTrafficChannels");
        field.setAccessible(true);
        assertTrue(((List<?>)field.get(manager)).isEmpty());
        assertTrue(subscriber.events.isEmpty());
    }

    @Test
    void publishesOneCallStartPerTargetWithoutTrafficTuner()
    {
        Channel parent = new Channel("NXDN Site", Channel.ChannelType.STANDARD);
        DecodeConfigNXDN config = new DecodeConfigNXDN();
        config.setTrafficChannelPoolSize(0);
        parent.setDecodeConfiguration(config);
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        NXDNChannel channel = channel(452_012_500L);
        EncryptionKeyIdentifier clear = EncryptionKeyIdentifier.create(Protocol.NXDN,
            NXDNEncryptionKey.create(0, 0));
        CallStartSubscriber subscriber = new CallStartSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            manager.processVoiceCall(identifiers(101, 91, clear), channel, CallType.GROUP_BROADCAST, clear,
                1_000L, new VoiceCallOption(0), CallTimer.UNSPECIFIED);
            manager.processVoiceCall(identifiers(102, 91, clear), channel, CallType.GROUP_BROADCAST, clear,
                1_100L, new VoiceCallOption(0), CallTimer.UNSPECIFIED);
            manager.processVoiceCall(identifiers(102, 92, clear), channel, CallType.GROUP_BROADCAST, clear,
                1_200L, new VoiceCallOption(0), CallTimer.UNSPECIFIED);
            manager.processEndCall(channel, 1_300L);
            manager.processVoiceCall(identifiers(103, 92, clear), channel, CallType.GROUP_BROADCAST, clear,
                1_400L, new VoiceCallOption(0), CallTimer.UNSPECIFIED);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(3, subscriber.events.size());
        assertEquals(91, subscriber.events.get(0).event().getIdentifierCollection().getToIdentifier().getValue());
        assertEquals(92, subscriber.events.get(1).event().getIdentifierCollection().getToIdentifier().getValue());
        assertEquals(92, subscriber.events.get(2).event().getIdentifierCollection().getToIdentifier().getValue());
        assertEquals(1_000L, subscriber.events.get(0).event().getTimeStart());
        assertEquals(1_200L, subscriber.events.get(1).event().getTimeStart());
        assertEquals(1_400L, subscriber.events.get(2).event().getTimeStart());
    }

    @Test
    void excludesUnknownPlaceholderChannelsFromCallStarts()
    {
        Channel parent = new Channel("NXDN Site", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigNXDN());
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        EncryptionKeyIdentifier clear = EncryptionKeyIdentifier.create(Protocol.NXDN,
            NXDNEncryptionKey.create(0, 0));
        CallStartSubscriber subscriber = new CallStartSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            manager.processVoiceCall(identifiers(101, 301, clear), new NXDNChannelFake(301),
                CallType.GROUP_BROADCAST, clear, 1_000L, new VoiceCallOption(0), CallTimer.UNSPECIFIED);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertTrue(subscriber.events.isEmpty());
    }

    @Test
    void publishesAnUnresolvedLogicalChannelBeforeFrequencyAllocationIsPossible()
    {
        Channel parent = new Channel("NXDN Site", Channel.ChannelType.STANDARD);
        DecodeConfigNXDN config = new DecodeConfigNXDN();
        config.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(config);
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        CallStartSubscriber subscriber = new CallStartSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            manager.processVoiceCallAssignment(unresolvedAssignment(1_000L, 12, 101, 91));
            manager.processVoiceCallAssignment(unresolvedAssignment(1_100L, 12, 101, 91));
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(1, subscriber.events.size());
        assertEquals(0L, subscriber.events.get(0).event().getChannelDescriptor().getDownlinkFrequency());
        assertEquals(12, ((NXDNChannelLookup)subscriber.events.get(0).event().getChannelDescriptor())
            .getChannelNumber());
    }

    @Test
    void audioProgressPreventsARepeatedAssignmentFromStartingAnotherCall()
    {
        Channel parent = new Channel("NXDN Site", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigNXDN());
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        NXDNChannel channel = channel(452_012_500L);
        EncryptionKeyIdentifier clear = EncryptionKeyIdentifier.create(Protocol.NXDN,
            NXDNEncryptionKey.create(0, 0));
        CallStartSubscriber subscriber = new CallStartSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            List<Identifier> identifiers = identifiers(101, 91, clear);
            manager.processVoiceCall(identifiers, channel, CallType.GROUP_BROADCAST, clear,
                1_000L, new VoiceCallOption(0), CallTimer.UNSPECIFIED);
            //Traffic progress has to arrive before the existing three-second event tracker ages out.
            manager.processCallProgressUpdate(channel, 3_500L);
            manager.processVoiceCall(identifiers, channel, CallType.GROUP_BROADCAST, clear,
                5_000L, new VoiceCallOption(0), CallTimer.UNSPECIFIED);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(1, subscriber.events.size());
    }

    @Test
    void publishesTypeDTalkerAliasInTheTypeDIdentityDomain()
    {
        Channel parent = new Channel("NXDN Type-D", Channel.ChannelType.STANDARD);
        DecodeConfigNXDN config = new DecodeConfigNXDN();
        config.setTransmissionMode(TransmissionMode.TYPE_D);
        parent.setDecodeConfiguration(config);
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        TalkerAliasSubscriber subscriber = new TalkerAliasSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            manager.processTalkerAlias(channel(452_012_500L),
                new NXDNTalkerAliasIdentifier("UNIT 12"),
                NXDNRadioIdentifier.createTypeDFrom(0x1234), 2_000L);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(1, subscriber.events.size());
        assertEquals(TrunkedIdentityDomain.NXDN_TYPE_D,
            subscriber.events.getFirst().identityDomain());
        assertEquals("UNIT 12", subscriber.events.getFirst().alias().getValue());
    }

    @Test
    void rateLimitsProgressAtTheDefaultCadence()
    {
        Channel parent = new Channel("NXDN", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigNXDN());
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        long frequency = 452_012_500L;

        assertTrue(manager.shouldPublishActivityProgress(frequency, 1_000L));
        assertFalse(manager.shouldPublishActivityProgress(frequency, 1_200L));
        assertFalse(manager.shouldPublishActivityProgress(frequency, 1_499L));
        assertTrue(manager.shouldPublishActivityProgress(frequency, 1_500L));
        assertTrue(manager.shouldPublishActivityProgress(frequency, 1_400L));
    }

    @Test
    void derivesProgressCadenceFromShortTrafficGrantAgeOut()
    {
        Channel parent = new Channel("NXDN", Channel.ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigNXDN());
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        NowPlayingPreference preference = new NowPlayingPreference(type -> {})
        {
            @Override
            public int getTrafficGrantAgeOutMilliseconds()
            {
                return NowPlayingPreference.MIN_TRAFFIC_GRANT_AGE_OUT_MILLISECONDS;
            }
        };
        manager.setChannelActivityModel(new ChannelActivityModel(new AliasModel(), preference));
        long frequency = 452_012_500L;

        assertEquals(50L, manager.getActivityProgressIntervalMilliseconds());
        assertTrue(manager.shouldPublishActivityProgress(frequency, 1_000L));
        assertFalse(manager.shouldPublishActivityProgress(frequency, 1_049L));
        assertTrue(manager.shouldPublishActivityProgress(frequency, 1_050L));
    }

    private static List<Identifier> identifiers(int radio, int talkgroup,
                                                EncryptionKeyIdentifier encryption)
    {
        return List.of(NXDNRadioIdentifier.createFrom(radio), NXDNTalkgroupIdentifier.createTo(talkgroup),
            encryption);
    }

    private static NXDNChannel channel(long frequency)
    {
        NXDNChannelLookup channel = new NXDNChannelLookup(12);
        channel.receive(null, Map.of(12, new ChannelFrequency(12, frequency, 0)));
        return channel;
    }

    private static VoiceCallAssignment unresolvedAssignment(long timestamp, int channelNumber,
                                                            int radio, int talkgroup)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(176);
        message.load(16, 3, CallType.GROUP_BROADCAST.getValue());
        message.load(24, 16, radio);
        message.load(40, 16, talkgroup);
        message.load(62, 10, channelNumber);
        VoiceCallAssignment assignment = new VoiceCallAssignment(message, timestamp,
            NXDNMessageType.CONTROL_OUT_04_CC_VOICE_CALL_ASSIGNMENT, 0,
            LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL);
        assignment.receive(new ChannelAccessInformation(new CorrectedBinaryMessage(6), 0), Map.of());
        return assignment;
    }

    private static class CallStartSubscriber
    {
        private final List<TrunkedCallStartEvent> events = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(TrunkedCallStartEvent event)
        {
            events.add(event);
        }
    }

    private static class TalkerAliasSubscriber
    {
        private final List<TrunkedTalkerAliasEvent> events = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(TrunkedTalkerAliasEvent event)
        {
            events.add(event);
        }
    }
}
