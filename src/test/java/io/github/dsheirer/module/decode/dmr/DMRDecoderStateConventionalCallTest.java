/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.dmr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.DecoderStateEvent.Event;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.edac.CRCDMR;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.integer.IntegerIdentifier;
import io.github.dsheirer.module.decode.dmr.channel.DMRAbsoluteChannel;
import io.github.dsheirer.module.decode.dmr.channel.DMRChannel;
import io.github.dsheirer.module.decode.dmr.channel.DMRLsn;
import io.github.dsheirer.module.decode.dmr.event.DMRDecodeEvent;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.identifier.DMRTalkgroup;
import io.github.dsheirer.module.decode.dmr.message.data.lc.LCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.EncryptionParameters;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.GroupVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.terminator.Terminator;
import io.github.dsheirer.module.decode.dmr.message.voice.embedded.EmbeddedEncryptionParameters;
import io.github.dsheirer.module.decode.dmr.sync.DMRSyncPattern;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class DMRDecoderStateConventionalCallTest
{
    @Test
    void embeddedEncryptionUsesHexKeyIdInEventDetails() throws ReflectiveOperationException
    {
        DMRDecoderState state = new DMRDecoderState(
            channel(DMRChannelMode.CONVENTIONAL, ChannelType.STANDARD), 1, null);
        List<IDecodeEvent> events = new CopyOnWriteArrayList<>();
        state.addDecodeEventListener(events::add);
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(11);
        bits.load(0, 8, 0xAB);
        bits.load(8, 3, 5);
        EmbeddedEncryptionParameters parameters = new EmbeddedEncryptionParameters(bits);

        updateEncryptedCall(state, parameters, 1_000L);

        assertEquals(1, events.size());
        assertEquals("ENCRYPTION AES256 K:AB", events.getFirst().getDetails());
        assertEquals("ENCRYPTION ALGORITHM:DMRA AES256 KEY:171", parameters.toString());
    }

    @Test
    void fullEncryptionUsesHexKeyIdInEventDetails() throws ReflectiveOperationException
    {
        DMRDecoderState state = new DMRDecoderState(
            channel(DMRChannelMode.CONVENTIONAL, ChannelType.STANDARD), 1, null);
        DecodeEvent call = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L).timeslot(1).build();
        state.setCurrentCallEvent(call);
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.load(2, 6, 0x25);
        bits.load(16, 8, 0xAB);
        bits.load(80, 16, CRCDMR.calculateResidual(bits, 0, 80) ^ 0x9696);
        EncryptionParameters parameters = new EncryptionParameters(bits, 1_100L, 1);

        processLinkControl(state, parameters);

        assertEquals("ENCRYPTION AES256 K:AB IV:00000000 VENDOR:STANDARD", call.getDetails());
        assertTrue(parameters.toString().contains(" KEY:171 "));
    }

    @Test
    void publishesOneImmutableCompletionForConventionalCall()
    {
        Channel channel = channel(DMRChannelMode.CONVENTIONAL, ChannelType.STANDARD);
        channel.setAliasListName("County DMR");
        channel.setRadresGuid("123e4567-e89b-12d3-a456-426614174000");
        DMRDecoderState state = new DMRDecoderState(channel, 2, null);
        DecodeEvent call = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP_ENCRYPTED, 1_000L)
            .channel(new DMRAbsoluteChannel(2, 2, 461_125_000L, 0))
            .identifiers(new IdentifierCollection(List.of(
                DMRRadio.createFrom(1_234_567), DMRTalkgroup.create(91))))
            .timeslot(2)
            .build();
        ConventionalCallSubscriber subscriber = new ConventionalCallSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            state.setCurrentCallEvent(call);
            state.reset();
            state.reset();
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(1, subscriber.count.get());
        DMRConventionalCallEvent event = subscriber.event.get();
        assertNotNull(event);
        assertEquals(1_000L, event.startTimestamp());
        assertTrue(event.endTimestamp() >= event.startTimestamp());
        assertEquals(461_125_000L, event.frequencyHertz());
        assertEquals(2, event.timeslot());
        assertEquals(DMRConventionalCallEvent.TargetKind.GROUP, event.targetKind());
        assertEquals(91, event.talkgroupId());
        assertEquals(1_234_567, event.sourceRadioId());
        assertNull(event.targetRadioId());
        assertTrue(event.encrypted());
        assertEquals("County DMR", event.aliasListName());
    }

    @Test
    void terminatorContributesLateTalkgroupBeforeCompletion() throws ReflectiveOperationException
    {
        Channel channel = channel(DMRChannelMode.CONVENTIONAL, ChannelType.STANDARD);
        DecodeConfigDMR configuration = (DecodeConfigDMR)channel.getDecodeConfiguration();
        configuration.setIgnoreCRCChecksums(true);
        DMRDecoderState state = new DMRDecoderState(channel, 1, null);
        state.getIdentifierCollection().update(DMRRadio.createFrom(101));
        DecodeEvent call = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .channel(new DMRAbsoluteChannel(1, 1, 461_125_000L, 0))
            .identifiers(new IdentifierCollection(List.of(DMRRadio.createFrom(101))))
            .timeslot(1)
            .build();
        state.setCurrentCallEvent(call);

        CorrectedBinaryMessage linkControlBits = new CorrectedBinaryMessage(72);
        linkControlBits.load(24, 24, 91);
        linkControlBits.load(48, 24, 101);
        GroupVoiceChannelUser linkControl = new GroupVoiceChannelUser(linkControlBits, 2_000L, 1);
        Terminator terminator = new Terminator(DMRSyncPattern.BASE_STATION_DATA,
            new CorrectedBinaryMessage(288), null, null, 2_000L, 1, linkControl);
        ConventionalCallSubscriber subscriber = new ConventionalCallSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            processTerminator(state, terminator);
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(1, subscriber.count.get());
        DMRConventionalCallEvent event = subscriber.event.get();
        assertNotNull(event);
        assertEquals(91, event.talkgroupId());
        assertEquals(101, event.sourceRadioId());
        assertEquals(2_000L, event.endTimestamp());
    }

    @Test
    void identityChangeSplitsConventionalCallsWithoutLeakingEncryption() throws ReflectiveOperationException
    {
        DMRDecoderState state = new DMRDecoderState(
            channel(DMRChannelMode.CONVENTIONAL, ChannelType.STANDARD), 1, null);
        state.receiveDecoderStateEvent(frequencyNotification(461_125_000L));
        state.getIdentifierCollection().update(DMRRadio.createFrom(101));
        state.getIdentifierCollection().update(DMRTalkgroup.create(91));
        ConventionalCallSubscriber subscriber = new ConventionalCallSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            updateCurrentCall(state, DecodeEventType.CALL_GROUP_ENCRYPTED, 1_000L);
            state.getIdentifierCollection().update(DMRRadio.createFrom(202));
            updateCurrentCall(state, DecodeEventType.CALL_GROUP, 2_000L);
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
    void conventionalFrequencyChangeClosesOldCallAndResetsChannelDescriptor()
        throws ReflectiveOperationException
    {
        DMRDecoderState state = new DMRDecoderState(
            channel(DMRChannelMode.CONVENTIONAL, ChannelType.STANDARD), 1, null);
        state.receiveDecoderStateEvent(frequencyNotification(461_125_000L));
        state.getIdentifierCollection().update(DMRRadio.createFrom(101));
        state.getIdentifierCollection().update(DMRTalkgroup.create(91));
        ConventionalCallSubscriber subscriber = new ConventionalCallSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            updateCurrentCall(state, DecodeEventType.CALL_GROUP, 1_000L);
            state.receiveDecoderStateEvent(frequencyNotification(462_125_000L));
            updateCurrentCall(state, DecodeEventType.CALL_GROUP, 2_000L);
            state.reset();
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        assertEquals(2, subscriber.events.size());
        assertEquals(461_125_000L, subscriber.events.get(0).frequencyHertz());
        assertEquals(462_125_000L, subscriber.events.get(1).frequencyHertz());
    }

    @Test
    void capturesConventionalModeWhenDecoderStateStarts()
    {
        Channel channel = channel(DMRChannelMode.CONVENTIONAL, ChannelType.STANDARD);
        DMRDecoderState state = new DMRDecoderState(channel, 1, null);
        ((DecodeConfigDMR)channel.getDecodeConfiguration()).setChannelMode(DMRChannelMode.TRUNKED);

        assertNotNull(completedCall(state));

        Channel trunkedChannel = channel(DMRChannelMode.TRUNKED, ChannelType.STANDARD);
        DMRDecoderState trunkedState = new DMRDecoderState(trunkedChannel, 1, null);
        ((DecodeConfigDMR)trunkedChannel.getDecodeConfiguration()).setChannelMode(DMRChannelMode.CONVENTIONAL);

        assertNull(completedCall(trunkedState));
    }

    @Test
    void capacityPlusActiveTalkgroupRecoveryOnlyRunsForTrunkedMode()
    {
        Map<Integer, IntegerIdentifier> activeTalkgroups = Map.of(3, DMRTalkgroup.create(91));
        DMRLsn activeChannel = new DMRLsn(3);
        Map<Integer, DMRLsn> activeChannels = Map.of(3, activeChannel);
        DecodeEvent call = DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .identifiers(new IdentifierCollection(List.of(DMRTalkgroup.create(91))))
            .timeslot(1)
            .build();
        DMRDecoderState conventional = new DMRDecoderState(
            channel(DMRChannelMode.CONVENTIONAL, ChannelType.STANDARD), 1, null);
        conventional.setCurrentCallEvent(call);
        DMRDecoderState trunked = new DMRDecoderState(
            channel(DMRChannelMode.TRUNKED, ChannelType.STANDARD), 1, null);
        trunked.setCurrentCallEvent(call);

        assertNull(conventional.processActiveTalkgroups(activeTalkgroups, activeChannels));
        DMRChannel recovered = trunked.processActiveTalkgroups(activeTalkgroups, activeChannels);
        assertSame(activeChannel, recovered);
    }

    @Test
    void doesNotPublishForTrunkedOrTrafficChannel()
    {
        assertNull(completedCall(channel(DMRChannelMode.TRUNKED, ChannelType.STANDARD)));
        assertNull(completedCall(channel(DMRChannelMode.CONVENTIONAL, ChannelType.TRAFFIC)));
    }

    private static DMRConventionalCallEvent completedCall(Channel channel)
    {
        return completedCall(new DMRDecoderState(channel, 1, null));
    }

    private static DMRConventionalCallEvent completedCall(DMRDecoderState state)
    {
        DecodeEvent call = DMRDecodeEvent.builder(DecodeEventType.CALL_UNIT_TO_UNIT, 1_000L)
            .channel(new DMRAbsoluteChannel(1, 1, 461_125_000L, 0))
            .identifiers(new IdentifierCollection(List.of(
                DMRRadio.createFrom(101), DMRRadio.createTo(202))))
            .timeslot(1)
            .build();
        ConventionalCallSubscriber subscriber = new ConventionalCallSubscriber();
        MyEventBus.getGlobalEventBus().register(subscriber);

        try
        {
            state.setCurrentCallEvent(call);
            state.reset();
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(subscriber);
        }

        return subscriber.event.get();
    }

    private static DecoderStateEvent frequencyNotification(long frequency)
    {
        return new DecoderStateEvent(DMRDecoderStateConventionalCallTest.class,
            Event.NOTIFICATION_SOURCE_FREQUENCY, State.IDLE, 1, frequency);
    }

    private static void updateCurrentCall(DMRDecoderState state, DecodeEventType type, long timestamp)
        throws ReflectiveOperationException
    {
        Method method = DMRDecoderState.class.getDeclaredMethod("updateCurrentCall",
            DecodeEventType.class, String.class, long.class);
        method.setAccessible(true);
        method.invoke(state, type, null, timestamp);
    }

    private static void processTerminator(DMRDecoderState state, Terminator terminator)
        throws ReflectiveOperationException
    {
        Method method = DMRDecoderState.class.getDeclaredMethod("processTerminator", Terminator.class);
        method.setAccessible(true);
        method.invoke(state, terminator);
    }

    private static void updateEncryptedCall(DMRDecoderState state, EmbeddedEncryptionParameters parameters,
                                            long timestamp) throws ReflectiveOperationException
    {
        Method method = DMRDecoderState.class.getDeclaredMethod("updateEncryptedCall",
            EmbeddedEncryptionParameters.class, boolean.class, long.class);
        method.setAccessible(true);
        method.invoke(state, parameters, true, timestamp);
    }

    private static void processLinkControl(DMRDecoderState state, LCMessage message)
        throws ReflectiveOperationException
    {
        Method method = DMRDecoderState.class.getDeclaredMethod("processLinkControl", LCMessage.class,
            boolean.class);
        method.setAccessible(true);
        method.invoke(state, message, false);
    }

    private static Channel channel(DMRChannelMode mode, ChannelType type)
    {
        Channel channel = new Channel("DMR Test", type);
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setChannelMode(mode);
        channel.setDecodeConfiguration(configuration);
        return channel;
    }

    private static class ConventionalCallSubscriber
    {
        private final AtomicInteger count = new AtomicInteger();
        private final AtomicReference<DMRConventionalCallEvent> event = new AtomicReference<>();
        private final List<DMRConventionalCallEvent> events = new CopyOnWriteArrayList<>();

        @Subscribe
        public void receive(DMRConventionalCallEvent call)
        {
            count.incrementAndGet();
            event.set(call);
            events.add(call);
        }
    }
}
