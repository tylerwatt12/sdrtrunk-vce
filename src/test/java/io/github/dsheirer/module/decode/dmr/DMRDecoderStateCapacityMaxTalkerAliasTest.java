/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.dmr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelConfigurationChangeNotification;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.dmr.event.DMRDecodeEvent;
import io.github.dsheirer.module.decode.dmr.identifier.DMRRadio;
import io.github.dsheirer.module.decode.dmr.message.data.lc.LCMessage;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.GroupVoiceChannelUser;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.motorola.CapacityMaxTalkerAlias;
import io.github.dsheirer.module.decode.dmr.message.data.lc.full.motorola.CapacityMaxTalkerAliasContinuation;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.IDecodeEvent;
import java.nio.charset.StandardCharsets;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DMRDecoderStateCapacityMaxTalkerAliasTest
{
    @Test
    void convertedCapacityPlusChannelKeepsTrafficAndAliasReportingWithoutGrantManager() throws Exception
    {
        Channel standardChannel = channel("Capacity Plus Rest", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(standardChannel);
        TestDecoderState state = new TestDecoderState(standardChannel, manager);
        state.getIdentifierCollection().update(DMRRadio.createFrom(101));

        state.channelChanged(new ChannelConfigurationChangeNotification(
            channel("Capacity Plus Traffic", Channel.ChannelType.TRAFFIC)));
        state.emit(DMRDecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .identifiers(new IdentifierCollection())
            .timeslot(1)
            .build());
        state.receive(capacityMaxAlias("CAR 1", 1_100L));

        assertFalse(state.hasAllocationAuthorityForTest());
        assertEquals(1, manager.mTrafficEvents);
        assertEquals(List.of("CAR 1"), manager.mAliases);
    }

    @Test
    void completeShortCapacityMaxAliasIsReportedOnlyOnce()
    {
        Channel channel = channel("Capacity Max", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(channel);
        DMRDecoderState state = new DMRDecoderState(channel, 1, manager);
        state.getIdentifierCollection().update(DMRRadio.createFrom(101));

        state.receive(capacityMaxAlias("ENGINE", 1_000L));
        state.receive(capacityMaxAlias("ENGINE", 1_100L));

        assertEquals(List.of("ENGINE"), manager.mAliases);
    }

    @Test
    void completeAliasWaitsForTheSameCallsLateRadioWithoutSplittingOrRecounting() throws Exception
    {
        Channel channel = channel("Capacity Max", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(channel);
        DMRDecoderState state = new DMRDecoderState(channel, 1, manager);
        List<IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);

        processLinkControl(state, groupVoice(0, 91, 1_000L));
        state.receive(capacityMaxAlias("ENGINE", 1_100L));
        state.receive(capacityMaxAlias("ENGINE", 1_200L));
        assertEquals(List.of(), manager.mAliases);

        processLinkControl(state, groupVoice(101, 91, 1_300L));
        state.receive(capacityMaxAlias("ENGINE", 1_400L));

        assertEquals(List.of("ENGINE"), manager.mAliases);
        assertEquals(List.of(101), manager.mAliasRadios);
        assertEquals(1_000L, events.getLast().getTimeStart());
        assertEquals(101, events.getLast().getIdentifierCollection().getFromIdentifier().getValue());
    }

    @Test
    void assemblesRepeatedTextAndSpacesByLengthInsteadOfSubstringMatching() throws Exception
    {
        Channel channel = channel("Capacity Max", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(channel);
        DMRDecoderState state = new DMRDecoderState(channel, 1, manager);
        state.getIdentifierCollection().update(DMRRadio.createFrom(101));

        state.receive(capacityMaxBase("ABCDEF", 9, 1_000L));
        state.receive(capacityMaxContinuation("ABC", 1_100L));
        state.receive(capacityMaxBase("ABCDEF", 9, 1_200L));
        state.receive(capacityMaxContinuation("ABC", 1_300L));
        state.receive(capacityMaxBase("FIRE  ", 7, 1_400L));
        state.receive(capacityMaxContinuation("1", 1_500L));

        assertEquals(List.of("ABCDEFABC", "FIRE  1"), manager.mAliases);
    }

    @Test
    void continuationCanArriveFirstButPartialAndInvalidLengthAliasesStayUnpublished()
    {
        Channel channel = channel("Capacity Max", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(channel);
        DMRDecoderState state = new DMRDecoderState(channel, 1, manager);
        state.getIdentifierCollection().update(DMRRadio.createFrom(101));

        state.receive(capacityMaxContinuation("GHIJKLM", 1_000L));
        assertEquals(List.of(), manager.mAliases);
        assertNull(state.getIdentifierCollection().getIdentifier(
            io.github.dsheirer.identifier.IdentifierClass.USER,
            io.github.dsheirer.identifier.Form.TALKER_ALIAS, io.github.dsheirer.identifier.Role.FROM));
        state.receive(capacityMaxBase("ABCDEF", 0, 1_100L));
        state.receive(capacityMaxBase("ABCDEF", 15, 1_200L));
        assertEquals(List.of(), manager.mAliases);
        state.receive(capacityMaxBase("ABCDEF", 13, 1_300L));

        assertEquals(List.of("ABCDEFGHIJKLM"), manager.mAliases);
    }

    @Test
    void resetAndRadioRolloverDiscardOldFragmentsAndPendingAliases() throws Exception
    {
        Channel channel = channel("Capacity Max", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(channel);
        DMRDecoderState state = new DMRDecoderState(channel, 1, manager);

        processLinkControl(state, groupVoice(0, 91, 1_000L));
        state.receive(capacityMaxAlias("OLD", 1_100L));
        state.receive(capacityMaxContinuation("OLD", 1_200L));
        state.reset();
        processLinkControl(state, groupVoice(101, 91, 2_000L));
        state.receive(capacityMaxBase("ABCDEF", 9, 2_100L));
        assertEquals(List.of(), manager.mAliases);
        processLinkControl(state, groupVoice(202, 91, 2_200L));
        state.receive(capacityMaxContinuation("XYZ", 2_300L));
        assertEquals(List.of(), manager.mAliases);
        state.receive(capacityMaxBase("NEWNEW", 9, 2_400L));

        assertEquals(List.of("NEWNEWXYZ"), manager.mAliases);
        assertEquals(List.of(202), manager.mAliasRadios);
    }

    @Test
    void aliasFragmentsRemainIsolatedBetweenTimeslots()
    {
        Channel channel = channel("Capacity Max", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(channel);
        DMRDecoderState first = new DMRDecoderState(channel, 1, manager);
        DMRDecoderState second = new DMRDecoderState(channel, 2, manager);
        first.getIdentifierCollection().update(DMRRadio.createFrom(101));
        second.getIdentifierCollection().update(DMRRadio.createFrom(202));

        first.receive(capacityMaxBase("ABCDEF", 9, 1_000L));
        second.receive(capacityMaxContinuation("XYZ", 1_100L, 2));
        assertEquals(List.of(), manager.mAliases);
        first.receive(capacityMaxContinuation("ABC", 1_200L));

        assertEquals(List.of("ABCDEFABC"), manager.mAliases);
        assertEquals(List.of(101), manager.mAliasRadios);
    }

    @Test
    void aliasObservationTimeDoesNotExtendVoiceDuration() throws Exception
    {
        Channel channel = channel("Capacity Max", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(channel);
        DMRDecoderState state = new DMRDecoderState(channel, 1, manager);
        List<IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);
        processLinkControl(state, groupVoice(101, 91, 1_000L));
        long voiceEnd = events.getLast().getTimeEnd();

        state.receive(capacityMaxAlias("ENGINE", 1_100L));

        assertEquals(voiceEnd, events.getLast().getTimeEnd());
        assertEquals(1_100L, manager.mObservationTimes.getLast());
    }

    @Test
    void conventionalVoiceAndControlRegistrationStayOutsideTrunkedTrafficReporting() throws Exception
    {
        Channel conventional = channel("Conventional", Channel.ChannelType.STANDARD);
        ((DecodeConfigDMR)conventional.getDecodeConfiguration()).setChannelMode(DMRChannelMode.CONVENTIONAL);
        RecordingTrafficChannelManager conventionalManager = new RecordingTrafficChannelManager(conventional);
        processLinkControl(new DMRDecoderState(conventional, 1, conventionalManager), groupVoice(101, 91, 1_000L));

        Channel trunked = channel("Trunked", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager trunkedManager = new RecordingTrafficChannelManager(trunked);
        TestDecoderState state = new TestDecoderState(trunked, trunkedManager);
        state.emit(DMRDecodeEvent.builder(DecodeEventType.REGISTER, 1_000L)
            .identifiers(new IdentifierCollection()).timeslot(1).build());

        assertEquals(0, conventionalManager.mTrafficEvents);
        assertEquals(0, trunkedManager.mTrafficEvents);
    }

    @Test
    void missingRadioHeaderPreservesKnownSourceAndAliasWhileARealRadioChangeRollsOver() throws Exception
    {
        Channel channel = channel("Capacity Max", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(channel);
        DMRDecoderState state = new DMRDecoderState(channel, 1, manager);
        List<IDecodeEvent> events = new ArrayList<>();
        state.addDecodeEventListener(events::add);
        processLinkControl(state, groupVoice(101, 91, 1_000L));
        state.receive(capacityMaxAlias("ENGINE", 1_100L));
        processLinkControl(state, groupVoice(0, 91, 1_200L));

        assertEquals(101, state.getIdentifierCollection().getFromIdentifier().getValue());
        assertEquals(1_000L, events.getLast().getTimeStart());
        assertEquals(List.of("ENGINE"), manager.mAliases);
        assertNotNull(state.getIdentifierCollection().getIdentifier(
            io.github.dsheirer.identifier.IdentifierClass.USER,
            io.github.dsheirer.identifier.Form.TALKER_ALIAS, io.github.dsheirer.identifier.Role.FROM));

        processLinkControl(state, groupVoice(202, 91, 1_300L));

        assertEquals(202, state.getIdentifierCollection().getFromIdentifier().getValue());
        assertEquals(1_300L, events.getLast().getTimeStart());
        assertNull(state.getIdentifierCollection().getIdentifier(
            io.github.dsheirer.identifier.IdentifierClass.USER,
            io.github.dsheirer.identifier.Form.TALKER_ALIAS, io.github.dsheirer.identifier.Role.FROM));
    }

    @Test
    void orphanAliasCannotAttachToANewCallOrItsContinuation() throws Exception
    {
        Channel channel = channel("Capacity Max", Channel.ChannelType.STANDARD);
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(channel);
        DMRDecoderState state = new DMRDecoderState(channel, 1, manager);
        state.receive(capacityMaxAlias("ORPHAN", 1_000L));

        processLinkControl(state, groupVoice(101, 91, 2_000L));
        state.receive(capacityMaxContinuation("OLD", 2_100L));

        assertEquals(List.of(), manager.mAliases);
        assertNull(state.getIdentifierCollection().getIdentifier(
            io.github.dsheirer.identifier.IdentifierClass.USER,
            io.github.dsheirer.identifier.Form.TALKER_ALIAS, io.github.dsheirer.identifier.Role.FROM));
        state.receive(capacityMaxAlias("ENGINE", 2_200L));
        assertEquals(List.of("ENGINE"), manager.mAliases);
        assertEquals(List.of(101), manager.mAliasRadios);
    }

    private static Channel channel(String name, Channel.ChannelType type)
    {
        Channel channel = new Channel(name, type);
        DecodeConfigDMR config = new DecodeConfigDMR();
        config.setChannelMode(DMRChannelMode.TRUNKED);
        channel.setDecodeConfiguration(config);
        return channel;
    }

    private static CapacityMaxTalkerAlias capacityMaxAlias(String alias, long timestamp)
    {
        return capacityMaxBase(alias, alias.length(), timestamp);
    }

    private static CapacityMaxTalkerAlias capacityMaxBase(String alias, int length, long timestamp)
    {
        byte[] bytes = alias.getBytes(StandardCharsets.US_ASCII);
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.load(2, 6, 20);
        bits.load(8, 8, 16);
        bits.load(19, 4, length);

        for(int x = 0; x < bytes.length; x++)
        {
            bits.load(24 + (x * 8), 8, bytes[x] & 0xFF);
        }

        return new CapacityMaxTalkerAlias(bits, timestamp, 1);
    }

    private static CapacityMaxTalkerAliasContinuation capacityMaxContinuation(String text, long timestamp)
    {
        return capacityMaxContinuation(text, timestamp, 1);
    }

    private static CapacityMaxTalkerAliasContinuation capacityMaxContinuation(String text, long timestamp, int timeslot)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.load(2, 6, 21);
        bits.load(8, 8, 16);
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        for(int x = 0; x < bytes.length; x++)
        {
            bits.load(16 + x * 8, 8, bytes[x] & 0xFF);
        }
        return new CapacityMaxTalkerAliasContinuation(bits, timestamp, timeslot);
    }

    private static GroupVoiceChannelUser groupVoice(int radio, int talkgroup, long timestamp)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(72);
        bits.load(24, 24, talkgroup);
        bits.load(48, 24, radio);
        return new GroupVoiceChannelUser(bits, timestamp, 1);
    }

    private static void processLinkControl(DMRDecoderState state, LCMessage message) throws Exception
    {
        Method method = DMRDecoderState.class.getDeclaredMethod("processLinkControl", LCMessage.class, boolean.class);
        method.setAccessible(true);
        method.invoke(state, message, false);
    }

    private static class TestDecoderState extends DMRDecoderState
    {
        private TestDecoderState(Channel channel, DMRTrafficChannelManager manager)
        {
            super(channel, 1, manager);
        }

        private void emit(IDecodeEvent event)
        {
            broadcast(event);
        }
    }

    private static class RecordingTrafficChannelManager extends DMRTrafficChannelManager
    {
        private int mTrafficEvents;
        private final List<String> mAliases = new ArrayList<>();
        private final List<Integer> mAliasRadios = new ArrayList<>();
        private final List<Long> mObservationTimes = new ArrayList<>();

        private RecordingTrafficChannelManager(Channel channel)
        {
            super(channel);
        }

        @Override
        public void receiveTrafficChannelEvent(IDecodeEvent trafficChannelEvent, long observedAt)
        {
            mTrafficEvents++;
            mObservationTimes.add(observedAt);
        }

        @Override
        public void processTalkerAlias(io.github.dsheirer.channel.IChannelDescriptor channel,
                                       TalkerAliasIdentifier alias, RadioIdentifier radio,
                                       IdentifierCollection identifiers, long timestamp)
        {
            mAliases.add(alias.getValue().toString());
            mAliasRadios.add(radio.getValue());
        }
    }
}
