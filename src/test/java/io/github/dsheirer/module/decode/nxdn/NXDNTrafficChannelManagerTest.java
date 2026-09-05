/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.nxdn;

import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.AbstractAudioModule;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.controller.channel.event.PostChannelModuleEventRequest;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
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
import io.github.dsheirer.module.decode.traffic.RadioSystemKeyEvent;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
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
    void trafficStartPreloadsNativeSystemBeforeChannelInformation()
    {
        Channel parent = new Channel("NXDN Site", Channel.ChannelType.STANDARD);
        DecodeConfigNXDN config = new DecodeConfigNXDN();
        config.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(config);
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        manager.updateNativeRadioSystemKey("nxdn-c:local:303");
        StartRequestSubscriber subscriber = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(subscriber);
        manager.setInterModuleEventBus(eventBus);
        EncryptionKeyIdentifier clear = EncryptionKeyIdentifier.create(Protocol.NXDN,
            NXDNEncryptionKey.create(0, 0));

        manager.processVoiceCall(identifiers(101, 91, clear), channel(452_012_500L),
            CallType.GROUP_BROADCAST, clear, 1_000L, new VoiceCallOption(0), CallTimer.UNSPECIFIED);

        assertEquals(1, subscriber.requests.size());
        List<?> preloads = subscriber.requests.getFirst().getPreloadDataContents();
        assertTrue(preloads.getFirst() instanceof RadioSystemKeyEvent);
        assertEquals("nxdn-c:local:303", ((RadioSystemKeyEvent)preloads.getFirst()).radioSystemKey());
        assertTrue(preloads.get(1) instanceof NXDNChannelInfoPreloadData);
    }

    @Test
    void reusedTrafficChildCorrelatesAnInFlightCallKeyWithoutRelabellingAnOlderCall()
    {
        Channel parent = new Channel("NXDN Site", Channel.ChannelType.STANDARD);
        parent.setConfigurationId("00000000-0000-0000-0000-000000000063");
        DecodeConfigNXDN config = new DecodeConfigNXDN();
        config.setTrafficChannelPoolSize(1);
        parent.setDecodeConfiguration(config);
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        manager.updateNativeRadioSystemKey("nxdn-c:local:303");
        StartRequestSubscriber starts = new StartRequestSubscriber();
        EventBus eventBus = new EventBus();
        eventBus.register(starts);
        manager.setInterModuleEventBus(eventBus);
        EncryptionKeyIdentifier clear = EncryptionKeyIdentifier.create(Protocol.NXDN,
            NXDNEncryptionKey.create(0, 0));
        NXDNChannel channel = channel(452_012_500L);
        CallStartSubscriber callStarts = new CallStartSubscriber();
        MyEventBus.getGlobalEventBus().register(callStarts);

        try
        {
            manager.processVoiceCall(identifiers(101, 91, clear), channel,
                CallType.GROUP_BROADCAST, clear, 1_000L, new VoiceCallOption(0), CallTimer.UNSPECIFIED);

            assertEquals(1, starts.requests.size());
            RadioSystemKeyEvent initial = (RadioSystemKeyEvent)starts.requests.getFirst()
                .getPreloadDataContents().getFirst();
            assertEquals("nxdn-c:local:303", initial.radioSystemKey());
            InterleavedAudioModule racingCall = new InterleavedAudioModule(parent.getConfigurationId(),
                initial.radioSystemKey());
            InterleavedAudioModule olderCall = new InterleavedAudioModule(parent.getConfigurationId(),
                initial.radioSystemKey());
            olderCall.beginAt(900L);
            PostRequestSubscriber posts = new PostRequestSubscriber(racingCall, olderCall);
            eventBus.register(posts);

            manager.updateNativeRadioSystemKey(null);
            manager.processVoiceCall(identifiers(102, 92, clear), channel,
                CallType.GROUP_BROADCAST, clear, 1_100L, new VoiceCallOption(0), CallTimer.UNSPECIFIED);

            assertEquals(1, posts.requests.size());
            PostChannelModuleEventRequest post = posts.requests.getFirst();
            assertEquals(List.of(starts.requests.getFirst().getChannel()), post.getChannels());
            RadioSystemKeyEvent activation = (RadioSystemKeyEvent)post.getEvent();
            String expected = RadioSystemKey.channelScoped(Protocol.NXDN, TrunkedIdentityDomain.NXDN_TYPE_C,
                parent.getConfigurationId());
            assertEquals(expected, activation.radioSystemKey());
            assertEquals(1_100L, activation.callStartEpochMilliseconds());
            assertNull(activation.timeslot());

            racingCall.closeAt(1_200L);
            olderCall.closeAt(1_200L);
            assertEquals(expected, racingCall.lastCompletedSystemKey(),
                "audio beginning after the frozen activation but before event delivery must receive that call's key");
            assertEquals("nxdn-c:local:303", olderCall.lastCompletedSystemKey(),
                "a call from an older activation must retain its original key");
            olderCall.beginAt(1_300L);
            olderCall.closeAt(1_400L);
            assertEquals(expected, olderCall.lastCompletedSystemKey(),
                "the same update must become the template for the next call");
            assertEquals(expected, callStarts.events.getLast().radioSystemKey());
            assertEquals(racingCall.lastCompletedSystemKey(), callStarts.events.getLast().radioSystemKey());
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(callStarts);
        }
    }

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
        assertEquals(91, subscriber.events.get(0).targetId());
        assertEquals(92, subscriber.events.get(1).targetId());
        assertEquals(92, subscriber.events.get(2).targetId());
        assertEquals(1_000L, subscriber.events.get(0).callStartEpochMilliseconds());
        assertEquals(1_200L, subscriber.events.get(1).callStartEpochMilliseconds());
        assertEquals(1_400L, subscriber.events.get(2).callStartEpochMilliseconds());
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
        assertNull(subscriber.events.get(0).frequencyHertz());
        assertEquals(12, subscriber.events.get(0).channelDescriptorSnapshot().primaryChannelNumber());
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
        parent.setConfigurationId("00000000-0000-0000-0000-000000000064");
        DecodeConfigNXDN config = new DecodeConfigNXDN();
        config.setTransmissionMode(TransmissionMode.TYPE_D);
        config.setTrafficChannelPoolSize(0);
        parent.setDecodeConfiguration(config);
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        NXDNChannel callChannel = channel(452_012_500L);
        NXDNRadioIdentifier radio = NXDNRadioIdentifier.createTypeDFrom(0x1234);
        EncryptionKeyIdentifier clear = EncryptionKeyIdentifier.create(Protocol.NXDN,
            NXDNEncryptionKey.create(0, 0));
        List<Identifier> identifiers = List.of(radio, NXDNTalkgroupIdentifier.createTypeDTo(0x1001), clear);
        manager.processVoiceCall(identifiers, callChannel, CallType.GROUP_BROADCAST, clear,
            1_000L, new VoiceCallOption(0), CallTimer.UNSPECIFIED);
        TalkerAliasSubscriber subscriber = new TalkerAliasSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            manager.processTalkerAlias(callChannel, new NXDNTalkerAliasIdentifier("UNIT 12"), radio, 2_000L);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(1, subscriber.events.size());
        assertEquals(TrunkedIdentityDomain.NXDN_TYPE_D,
            subscriber.events.getFirst().identityDomain());
        assertEquals("UNIT 12", subscriber.events.getFirst().talkerAlias());
        assertEquals(1_000L, subscriber.events.getFirst().callStartEpochMilliseconds());
        assertEquals("nxdn-d:channel:" +
                io.github.dsheirer.controller.channel.ChannelConfigurationKey.configured(parent),
            subscriber.events.getFirst().radioSystemKey());
    }

    @Test
    void talkerAliasKeepsTheTypeCSystemCapturedWhenItsCallStarted()
    {
        Channel parent = new Channel("NXDN Type-C", Channel.ChannelType.STANDARD);
        parent.setConfigurationId("00000000-0000-0000-0000-000000000065");
        DecodeConfigNXDN config = new DecodeConfigNXDN();
        config.setTrafficChannelPoolSize(0);
        parent.setDecodeConfiguration(config);
        NXDNTrafficChannelManager manager = new NXDNTrafficChannelManager(parent);
        manager.updateNativeRadioSystemKey("nxdn-c:local:303");
        NXDNChannel callChannel = channel(452_012_500L);
        EncryptionKeyIdentifier clear = EncryptionKeyIdentifier.create(Protocol.NXDN,
            NXDNEncryptionKey.create(0, 0));
        List<Identifier> identifiers = identifiers(101, 91, clear);
        manager.processVoiceCall(identifiers, callChannel, CallType.GROUP_BROADCAST, clear,
            1_000L, new VoiceCallOption(0), CallTimer.UNSPECIFIED);
        manager.updateNativeRadioSystemKey("nxdn-c:local:304");
        TalkerAliasSubscriber subscriber = new TalkerAliasSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            manager.processTalkerAlias(callChannel, new NXDNTalkerAliasIdentifier("UNIT 101"),
                NXDNRadioIdentifier.createFrom(101), 2_000L);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(1, subscriber.events.size());
        assertEquals(1_000L, subscriber.events.getFirst().callStartEpochMilliseconds());
        assertEquals("nxdn-c:local:303", subscriber.events.getFirst().radioSystemKey());
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

    private static class StartRequestSubscriber
    {
        private final List<ChannelStartProcessingRequest> requests = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(ChannelStartProcessingRequest request)
        {
            requests.add(request);
        }
    }

    private static class PostRequestSubscriber
    {
        private final List<PostChannelModuleEventRequest> requests = new CopyOnWriteArrayList<>();
        private final InterleavedAudioModule mRacingCall;
        private final InterleavedAudioModule mOlderCall;

        private PostRequestSubscriber(InterleavedAudioModule racingCall, InterleavedAudioModule olderCall)
        {
            mRacingCall = racingCall;
            mOlderCall = olderCall;
        }

        @Subscribe
        public void receive(PostChannelModuleEventRequest request)
        {
            requests.add(request);
            RadioSystemKeyEvent event = (RadioSystemKeyEvent)request.getEvent();
            //This is the production race: traffic audio starts after the manager froze the activation, but before the
            //processing-chain event reaches the audio module.
            mRacingCall.beginAt(event.callStartEpochMilliseconds() + 1L);
            mRacingCall.radioSystemKeyChanged(event);
            mOlderCall.radioSystemKeyChanged(event);
        }
    }

    private static class InterleavedAudioModule extends AbstractAudioModule
    {
        private final List<AudioCallEvent> mEvents = new CopyOnWriteArrayList<>();

        private InterleavedAudioModule(String configurationId, String radioSystemKey)
        {
            super(AliasList.empty("Test"), 0, 60_000L,
                new CallLegSource(DecoderType.NXDN, configurationId, "Traffic", null, 0L, null,
                    TrunkedIdentityDomain.NXDN_TYPE_C, ChannelConfigurationPolicy.ChannelKind.TRUNKED, true,
                    radioSystemKey));
            setAudioCallEventListener(mEvents::add);
        }

        private void beginAt(long timestamp)
        {
            beginCurrentAudioSegment(timestamp);
        }

        private void closeAt(long timestamp)
        {
            closeAudioSegment(timestamp);
        }

        private String lastCompletedSystemKey()
        {
            return mEvents.stream().filter(event -> event.eventType() == AudioCallEventType.CALL_COMPLETED)
                .toList().getLast().snapshot().callLegSource().radioSystemKey();
        }

        @Override
        public void reset()
        {
        }

        @Override
        public void start()
        {
        }
    }
}
