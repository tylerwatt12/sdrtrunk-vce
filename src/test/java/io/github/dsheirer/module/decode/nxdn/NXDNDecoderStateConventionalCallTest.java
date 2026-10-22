/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.nxdn;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.eventbus.Subscribe;
import com.google.common.eventbus.EventBus;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioActivityCallEvent;
import io.github.dsheirer.audio.call.CallPlaybackTarget;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.DecoderStateEvent.Event;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer3.NXDNMessageType;
import io.github.dsheirer.module.decode.nxdn.layer3.call.TransmissionRelease;
import io.github.dsheirer.module.decode.nxdn.layer3.call.VoiceCall;
import io.github.dsheirer.module.decode.nxdn.layer3.type.CallType;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;

class NXDNDecoderStateConventionalCallTest
{
    @Test
    void retainsUnaddressedNormalAndAllGroupDestinationsThroughAudioAndCompletion()
    {
        for(int group: new int[]{0, 91, 0xFFFF})
        {
            var decoded = NXDNConventionalCallFixture.decodeGroupCall(AliasList.empty("NXDN fixture"), 101, group);
            var audio = decoded.audio();
            assertEquals(group, audio.snapshot().identifierCollection().getToIdentifier().getValue());
            assertEquals(101, audio.snapshot().identifierCollection().getFromIdentifier().getValue());
            assertTrue(audio.hasAudio());
            assertEquals(1_000L, audio.getDuration());
            assertEquals(NXDNConventionalCallEvent.TargetKind.GROUP, decoded.completion().targetKind());
            assertEquals(group, decoded.completion().talkgroupId());
            assertEquals(101, decoded.completion().sourceRadioId());
            assertEquals(decoded.completion().callToken(), audio.snapshot().activityCallToken());
            assertEquals(CallPlaybackTarget.Kind.CHANNEL,
                CallPlaybackTarget.from(audio.snapshot(), audio.snapshot().identifierCollection().getToIdentifier()).kind());
        }
    }

    @Test
    void retainsNullGroupWithoutInventingASourceRadioForZero()
    {
        var decoded = NXDNConventionalCallFixture.decodeGroupCall(AliasList.empty("NXDN fixture"), 0, 0);
        assertEquals(0, decoded.audio().snapshot().identifierCollection().getToIdentifier().getValue());
        assertNull(decoded.audio().snapshot().identifierCollection().getFromIdentifier());
        assertEquals(0, decoded.completion().talkgroupId());
        assertNull(decoded.completion().sourceRadioId());
        assertNull(decoded.completion().targetRadioId());
    }

    @Test
    void publishesOneImmutableCompletionWithoutTrafficManager()
    {
        Channel channel = channel(NXDNChannelMode.CONVENTIONAL, ChannelType.STANDARD);
        channel.setAliasListName("County NXDN");
        channel.setRadioResolveId("123e4567-e89b-12d3-a456-426614174000");
        NXDNDecoderState state = new NXDNDecoderState(channel, null);
        state.receiveDecoderStateEvent(frequencyNotification(461_125_000L));
        List<String> decodeTokens = new CopyOnWriteArrayList<>();
        state.addDecodeEventListener(event -> {
            if(event.getEventType().isVoiceCallEvent()) decodeTokens.add(event.getActivityCallToken());
        });
        ConventionalCallSubscriber subscriber = new ConventionalCallSubscriber();
        EventBus localBus = new EventBus();
        ActivityBindingSubscriber binding = new ActivityBindingSubscriber();
        localBus.register(binding);
        state.setInterModuleEventBus(localBus);
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            state.receive(voiceCall(1_000L, 101, 91, CallType.GROUP_BROADCAST, true));
            state.receive(release(2_000L, 101, 91, CallType.GROUP_BROADCAST));
            state.receive(release(2_100L, 101, 91, CallType.GROUP_BROADCAST));
            state.reset();
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(1, subscriber.events.size());
        NXDNConventionalCallEvent event = subscriber.events.getFirst();
        assertNotNull(event.callToken());
        assertFalse(decodeTokens.isEmpty());
        assertTrue(decodeTokens.stream().allMatch(event.callToken()::equals),
            "Initial, updated and completed alert events share the exact Activity/audio call binding");
        assertEquals(event.callToken(), binding.tokens.stream().filter(java.util.Objects::nonNull).findFirst().orElseThrow());
        assertEquals(1_000L, event.startTimestamp());
        assertEquals(2_000L, event.endTimestamp());
        assertEquals(461_125_000L, event.frequencyHertz());
        assertEquals(NXDNConventionalCallEvent.TargetKind.GROUP, event.targetKind());
        assertEquals(91, event.talkgroupId());
        assertEquals(101, event.sourceRadioId());
        assertNull(event.targetRadioId());
        assertTrue(event.encrypted());
        assertEquals(channel.getConfigurationId(), event.channelConfigurationId());
    }

    @Test
    void identityChangeSplitsCallsWithoutLeakingEncryption()
    {
        NXDNDecoderState state = new NXDNDecoderState(
            channel(NXDNChannelMode.CONVENTIONAL, ChannelType.STANDARD), null);
        state.receiveDecoderStateEvent(frequencyNotification(461_125_000L));
        ConventionalCallSubscriber subscriber = new ConventionalCallSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            state.receive(voiceCall(1_000L, 101, 91, CallType.GROUP_BROADCAST, true));
            state.receive(voiceCall(2_000L, 202, 91, CallType.GROUP_BROADCAST, false));
            state.reset();
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(2, subscriber.events.size());
        assertEquals(101, subscriber.events.get(0).sourceRadioId());
        assertTrue(subscriber.events.get(0).encrypted());
        assertEquals(202, subscriber.events.get(1).sourceRadioId());
        assertFalse(subscriber.events.get(1).encrypted());
        assertEquals(91, subscriber.events.get(0).talkgroupId());
        assertEquals(91, subscriber.events.get(1).talkgroupId());
    }

    @Test
    void doesNotPublishForTrunkedOrTrafficChannels()
    {
        assertNull(completedCall(channel(NXDNChannelMode.TRUNKED, ChannelType.STANDARD)));
        assertNull(completedCall(channel(NXDNChannelMode.CONVENTIONAL, ChannelType.TRAFFIC)));
    }

    private static NXDNConventionalCallEvent completedCall(Channel channel)
    {
        NXDNDecoderState state = new NXDNDecoderState(channel, null);
        state.receiveDecoderStateEvent(frequencyNotification(461_125_000L));
        ConventionalCallSubscriber subscriber = new ConventionalCallSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            state.receive(voiceCall(1_000L, 101, 202, CallType.INDIVIDUAL, false));
            state.reset();
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        return subscriber.events.isEmpty() ? null : subscriber.events.getFirst();
    }

    private static VoiceCall voiceCall(long timestamp, int source, int target, CallType callType,
                                       boolean encrypted)
    {
        CorrectedBinaryMessage message = callBits(source, target, callType);
        message.load(56, 2, encrypted ? 1 : 0);
        message.load(58, 6, encrypted ? 7 : 0);
        return new VoiceCall(message, timestamp, NXDNMessageType.TRAFFIC_OUT_01_CC_VOICE_CALL, 3,
            LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1);
    }

    private static TransmissionRelease release(long timestamp, int source, int target, CallType callType)
    {
        return new TransmissionRelease(callBits(source, target, callType), timestamp,
            NXDNMessageType.TRAFFIC_OUT_08_CC_TRANSMISSION_RELEASE, 3,
            LICH.RDCH_OUTBOUND_SUPER_FACCH1_FACCH1);
    }

    private static CorrectedBinaryMessage callBits(int source, int target, CallType callType)
    {
        CorrectedBinaryMessage message = new CorrectedBinaryMessage(176);
        message.load(16, 3, callType.getValue());
        message.load(24, 16, source);
        message.load(40, 16, target);
        return message;
    }

    private static DecoderStateEvent frequencyNotification(long frequency)
    {
        return new DecoderStateEvent(NXDNDecoderStateConventionalCallTest.class,
            Event.NOTIFICATION_SOURCE_FREQUENCY, State.IDLE, frequency);
    }

    private static Channel channel(NXDNChannelMode mode, ChannelType type)
    {
        Channel channel = new Channel("NXDN Test", type);
        DecodeConfigNXDN configuration = new DecodeConfigNXDN();
        configuration.setChannelMode(mode);
        channel.setDecodeConfiguration(configuration);
        return channel;
    }

    private static class ActivityBindingSubscriber
    {
        private final List<String> tokens = new java.util.ArrayList<>();
        @Subscribe public void receive(AudioActivityCallEvent event) { tokens.add(event.token()); }
    }

    private static class ConventionalCallSubscriber
    {
        private final List<NXDNConventionalCallEvent> events = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(NXDNConventionalCallEvent call)
        {
            assertNotNull(call);
            events.add(call);
        }
    }
}
