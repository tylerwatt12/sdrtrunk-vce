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

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.message.TimeslotMessage;
import io.github.dsheirer.module.decode.p25.P25FrequencyBandValidator;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp.AMBTCGroupVoiceChannelGrant;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp.AMBTCUnitToUnitVoiceServiceChannelGrantUpdate;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.motorola.osp.MotorolaExplicitTDMADataChannelAnnouncement;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.FrequencyBandUpdate;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.GroupVoiceChannelGrant;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.GroupVoiceChannelGrantUpdate;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.NetworkStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.lc.motorola.MotorolaTalkerAliasComplete;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.ScrambleParameters;
import io.github.dsheirer.module.decode.p25.reference.ServiceOptions;
import io.github.dsheirer.module.decode.p25.reference.Direction;
import io.github.dsheirer.module.decode.p25.reference.Vendor;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.protocol.Protocol;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P1DecoderStateControlAuthorityTest
{
    private static final int NAC = 0x123;
    private static final long TIMESTAMP = 1_000L;
    private static final Identifier TALKGROUP = APCO25Talkgroup.create(1_201);

    @Test
    void standardChannelDispatchesControlActions()
    {
        RecordingTrafficChannelManager manager = exercise(ChannelType.STANDARD);

        assertEquals(2, manager.mDirectedGrantCount, "TSBK and AMBTC grants");
        assertEquals(2, manager.mAnnouncedUpdateCount, "TSBK and AMBTC grant updates");
        assertEquals(1, manager.mPhase2DataCount, "Motorola TDMA data announcement");
        assertEquals(1, manager.mFrequencyBandCount, "frequency-band preload");
    }

    @Test
    void trafficChannelDoesNotDispatchControlActions()
    {
        RecordingTrafficChannelManager manager = exercise(ChannelType.TRAFFIC);

        assertEquals(0, manager.mDirectedGrantCount, "TSBK and AMBTC grants");
        assertEquals(0, manager.mAnnouncedUpdateCount, "TSBK and AMBTC grant updates");
        assertEquals(0, manager.mPhase2DataCount, "Motorola TDMA data announcement");
        assertEquals(0, manager.mFrequencyBandCount, "frequency-band preload");
    }

    @Test
    void trafficChannelDoesNotStayAliveAsASecondControlDecoder()
    {
        Channel channel = new Channel("Traffic", ChannelType.TRAFFIC);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25P1DecoderState decoderState =
            new P25P1DecoderState(channel, new RecordingTrafficChannelManager());
        List<DecoderStateEvent> events = new ArrayList<>();
        decoderState.setDecoderStateListener(events::add);

        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP));

        assertEquals(0, events.size(), "traffic decoders must not emit CONTROL holds from TSBKs");
        assertNull(controlNAC(decoderState), "traffic decoders must not learn NAC from control-family artifacts");
    }

    @Test
    void ordinaryStateResetKeepsProcessorAndStateAuthorityAligned()
    {
        Channel channel = new Channel("Test", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager();
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, manager);
        P25P1MessageProcessor processor = new P25P1MessageProcessor(true);
        processor.setMessageListener(decoderState::receive);
        processor.receive(new TestNACObservation(NAC, TIMESTAMP));
        processor.receive(new TestNACObservation(NAC, TIMESTAMP + 1));
        processor.receive(new TestNACObservation(NAC, TIMESTAMP + 2));
        assertEquals(NAC, controlNAC(decoderState));

        decoderState.receiveDecoderStateEvent(new DecoderStateEvent(this,
            DecoderStateEvent.Event.REQUEST_RESET, State.IDLE));
        assertEquals(NAC, controlNAC(decoderState),
            "an ordinary state timeout does not change the RF source authority");

        processor.receive(new TestTSBKGrant(resolvedChannel(), NAC, TIMESTAMP + 3));
        assertEquals(1, manager.mDirectedGrantCount);
    }

    @Test
    void phase2ScrambleParametersCanOnlyComeFromAuthorizedControlNAC() throws Exception
    {
        Channel channel = new Channel("Test", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25TrafficChannelManager manager = new P25TrafficChannelManager(channel);
        P25P1DecoderState state = new P25P1DecoderState(channel, manager);
        TestNetworkStatusBroadcast foreign = new TestNetworkStatusBroadcast(0x456, TIMESTAMP);

        manager.getMessageListener().receive(foreign);
        assertNull(scrambleParameters(manager), "the direct module listener cannot bypass NAC authority");

        state.receive(new TestNACObservation(NAC, TIMESTAMP));
        state.receive(new TestNACObservation(NAC, TIMESTAMP + 1));
        state.receive(new TestNACObservation(NAC, TIMESTAMP + 2));
        state.receive(new TestNetworkStatusBroadcast(NAC, 0x456, TIMESTAMP + 3));
        assertNull(scrambleParameters(manager), "an inconsistent inner NAC cannot repoint traffic descrambling");
        state.receive(new TestNetworkStatusBroadcast(NAC, TIMESTAMP + 3));
        assertEquals(NAC, scrambleParameters(manager).getNAC());

        state.receive(foreign);
        assertEquals(NAC, scrambleParameters(manager).getNAC(), "a foreign NSB cannot repoint traffic descrambling");

        state.reset();
        assertNull(scrambleParameters(manager), "scramble parameters share the control-source lifetime");
    }

    @Test
    void standardChannelFreezesConfirmedNACAuthorityUntilReset()
    {
        Channel channel = new Channel("Test", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager();
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, manager);

        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 1));
        assertNull(controlNAC(decoderState), "two observations must not establish authority");

        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 2));
        assertEquals(NAC, controlNAC(decoderState));

        for(int x = 0; x < 5; x++)
        {
            decoderState.receive(new TestTSBKGrant(resolvedChannel(), 0x456));
        }

        assertEquals(0, manager.mDirectedGrantCount, "foreign NAC must not dispatch a control grant");

        decoderState.receive(new TestTSBKGrant(resolvedChannel(), NAC));
        assertEquals(1, manager.mDirectedGrantCount);
        assertNotNull(manager.mLastIdentifiers);
        Identifier grantNAC = manager.mLastIdentifiers.getIdentifier(IdentifierClass.NETWORK,
            Form.NETWORK_ACCESS_CODE, Role.BROADCAST);
        assertNotNull(grantNAC);
        assertEquals(NAC, grantNAC.getValue());

        decoderState.reset();
        assertNull(controlNAC(decoderState), "reset must clear the old control authority");
        decoderState.receive(new TestTSBKGrant(resolvedChannel(), NAC));
        assertEquals(1, manager.mDirectedGrantCount, "a reset source must establish authority again");

        decoderState.receive(new TestNACObservation(0x456, TIMESTAMP + 1));
        decoderState.receive(new TestNACObservation(0x456, TIMESTAMP + 2));
        assertNull(controlNAC(decoderState));
        decoderState.receive(new TestNACObservation(0x456, TIMESTAMP + 3));
        assertEquals(0x456, controlNAC(decoderState));
        decoderState.receive(new TestTSBKGrant(resolvedChannel(), 0x456));
        assertEquals(2, manager.mDirectedGrantCount, "the reset source can establish a new authority");
    }

    @Test
    void standardChannelCanEstablishZeroNACAuthority()
    {
        Channel channel = new Channel("Test", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager();
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, manager);

        decoderState.receive(new TestNACObservation(0, TIMESTAMP));
        decoderState.receive(new TestNACObservation(0, TIMESTAMP + 1));
        decoderState.receive(new TestNACObservation(0, TIMESTAMP + 2));
        decoderState.receive(new TestTSBKGrant(resolvedChannel(), 0, TIMESTAMP + 3));

        assertEquals(0, controlNAC(decoderState));
        assertEquals(1, manager.mDirectedGrantCount);
        Identifier grantNAC = manager.mLastIdentifiers.getIdentifier(IdentifierClass.NETWORK,
            Form.NETWORK_ACCESS_CODE, Role.BROADCAST);
        assertNotNull(grantNAC);
        assertEquals(0, grantNAC.getValue());
    }

    @Test
    void oneNIDAndNonControlUnitsCannotEstablishAuthority()
    {
        Channel channel = new Channel("Test", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, new RecordingTrafficChannelManager());

        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP,
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 1,
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_2));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 2,
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_3));
        assertNull(controlNAC(decoderState), "TSBK continuation blocks must not count as additional NIDs");

        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 3));
        assertNull(controlNAC(decoderState));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 4));
        assertEquals(NAC, controlNAC(decoderState));

        decoderState.reset();

        decoderState.receive(new TestNonControlObservation(NAC, TIMESTAMP + 5));
        decoderState.receive(new TestNonControlObservation(NAC, TIMESTAMP + 6));
        decoderState.receive(new TestNonControlObservation(NAC, TIMESTAMP + 7));
        assertNull(controlNAC(decoderState), "voice-family units cannot establish control authority");
    }

    @Test
    void sourceFrequencyChangeRequiresFreshAuthority()
    {
        Channel channel = new Channel("Test", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, new RecordingTrafficChannelManager());

        decoderState.receiveDecoderStateEvent(new DecoderStateEvent(this,
            DecoderStateEvent.Event.NOTIFICATION_SOURCE_FREQUENCY, State.IDLE, 851_000_000L));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 1));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 2));
        assertEquals(NAC, controlNAC(decoderState));

        decoderState.reset();
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 3));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 4));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 5));
        assertEquals(NAC, controlNAC(decoderState));

        decoderState.receiveDecoderStateEvent(new DecoderStateEvent(this,
            DecoderStateEvent.Event.NOTIFICATION_SOURCE_FREQUENCY, State.IDLE, 852_000_000L));
        assertNull(controlNAC(decoderState));

        decoderState.receive(new TestNACObservation(0x456, TIMESTAMP + 6));
        decoderState.receive(new TestNACObservation(0x456, TIMESTAMP + 7));
        assertNull(controlNAC(decoderState));
        decoderState.receive(new TestNACObservation(0x456, TIMESTAMP + 8));
        assertEquals(0x456, controlNAC(decoderState));
    }

    @Test
    void authorityRequiresConsecutiveObservationsWithinOneSecond()
    {
        Channel channel = new Channel("Test", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, new RecordingTrafficChannelManager());

        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 1));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 2_000));
        assertNull(controlNAC(decoderState), "old observations must expire");
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 2_001));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 2_002));
        assertEquals(NAC, controlNAC(decoderState));

        decoderState.reset();
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 3_000));
        decoderState.receive(new TestNACObservation(0x456, TIMESTAMP + 3_001));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 3_002));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 3_003));
        assertNull(controlNAC(decoderState), "an interleaved NAC must restart confirmation");
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 3_004));
        assertEquals(NAC, controlNAC(decoderState));
    }

    @Test
    void pendingAuthorityStillHoldsControlChannelWithoutDispatchingPayload()
    {
        Channel channel = new Channel("Test", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager();
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, manager);
        List<DecoderStateEvent> events = new ArrayList<>();
        decoderState.setDecoderStateListener(events::add);

        decoderState.receive(new TestTSBKGrant(resolvedChannel(), NAC, TIMESTAMP));

        assertEquals(1, events.size());
        assertEquals(State.CONTROL, events.getFirst().getState());
        assertEquals(0, manager.mDirectedGrantCount);
        assertNull(controlNAC(decoderState));
    }

    @Test
    void conventionalChannelKeepsImmediateNACDiscoveryButHasNoControlAuthority()
    {
        Channel channel = new Channel("Conventional", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Conventional());
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager();
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, manager);

        decoderState.receive(new TestTSBKGrant(resolvedChannel(), NAC));

        assertEquals(NAC, controlNAC(decoderState));
        assertEquals(0, manager.mDirectedGrantCount, "conventional messages cannot allocate trunked traffic channels");
    }

    @Test
    void matchingInvalidControlFrameStillHoldsTheConfirmedControlChannel()
    {
        Channel channel = new Channel("Test", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, new RecordingTrafficChannelManager());
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 1));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 2));

        List<DecoderStateEvent> events = new ArrayList<>();
        decoderState.setDecoderStateListener(events::add);
        TestNACObservation matching = new TestNACObservation(NAC, TIMESTAMP + 3);
        matching.setValid(false);
        decoderState.receive(matching);

        assertEquals(1, events.size());
        assertEquals(State.CONTROL, events.getFirst().getState());

        TestNACObservation foreign = new TestNACObservation(0x456, TIMESTAMP + 4);
        foreign.setValid(false);
        decoderState.receive(foreign);
        assertEquals(1, events.size(), "foreign invalid control frame must not hold the channel");
    }

    @Test
    void derivedTrafficMessagesCannotBypassControlNACAuthority()
    {
        Channel channel = new Channel("Test", ChannelType.STANDARD);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager();
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, manager);
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 1));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 2));

        MotorolaTalkerAliasComplete alias = new MotorolaTalkerAliasComplete(new CorrectedBinaryMessage(80),
            APCO25Talkgroup.create(1_201), 1, TimeslotMessage.TIMESLOT_0, TIMESTAMP + 3, Protocol.APCO25);
        alias.setValid(true);
        decoderState.receive(alias);

        assertEquals(0, manager.mTalkerAliasCount);
    }

    private static RecordingTrafficChannelManager exercise(ChannelType channelType)
    {
        Channel channel = new Channel("Test", channelType);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager();
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, manager);
        APCO25Channel trafficChannel = resolvedChannel();

        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 1));
        decoderState.receive(new TestNACObservation(NAC, TIMESTAMP + 2));

        List<P25P1Message> messages = List.of(
            new TestTSBKGrant(trafficChannel),
            new TestTSBKGrantUpdate(trafficChannel),
            new TestAMBTCGrant(trafficChannel),
            new TestAMBTCGrantUpdate(trafficChannel),
            new TestMotorolaTDMADataChannel(trafficChannel),
            new TestFrequencyBandUpdate());

        messages.forEach(decoderState::receive);
        return manager;
    }

    private static Integer controlNAC(P25P1DecoderState decoderState)
    {
        Identifier identifier = decoderState.getIdentifierCollection().getIdentifier(IdentifierClass.NETWORK,
            Form.NETWORK_ACCESS_CODE, Role.BROADCAST);
        return identifier != null && identifier.getValue() instanceof Number number ? number.intValue() : null;
    }

    private static ScrambleParameters scrambleParameters(P25TrafficChannelManager manager) throws Exception
    {
        Field field = P25TrafficChannelManager.class.getDeclaredField("mPhase2ScrambleParameters");
        field.setAccessible(true);
        return (ScrambleParameters)field.get(manager);
    }

    private static APCO25Channel resolvedChannel()
    {
        APCO25Channel channel = APCO25Channel.create(0, 1);
        channel.setFrequencyBand(new P25FrequencyBand(0, 851_006_250L, -45_000_000L, 6_250L, 12_500, 1));
        return channel;
    }

    private static AMBTCHeader header(Opcode opcode)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.set(2); //Outbound
        bits.setInt(opcode.getCode(), IntField.length6(58));
        return new AMBTCHeader(bits, true);
    }

    private static class TestTSBKGrant extends GroupVoiceChannelGrant
    {
        private final APCO25Channel mChannel;

        private TestTSBKGrant(APCO25Channel channel)
        {
            this(channel, NAC);
        }

        private TestTSBKGrant(APCO25Channel channel, int nac)
        {
            this(channel, nac, TIMESTAMP);
        }

        private TestTSBKGrant(APCO25Channel channel, int nac, long timestamp)
        {
            super(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, new CorrectedBinaryMessage(96), nac, timestamp);
            mChannel = channel;
        }

        @Override
        public Opcode getOpcode()
        {
            return Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT;
        }

        @Override
        public APCO25Channel getChannel()
        {
            return mChannel;
        }

        @Override
        public VoiceServiceOptions getServiceOptions()
        {
            return VoiceServiceOptions.createUnencrypted();
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of(TALKGROUP);
        }
    }

    private static class TestNACObservation extends TSBKMessage
    {
        private TestNACObservation(int nac, long timestamp)
        {
            this(nac, timestamp, P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1);
        }

        private TestNACObservation(int nac, long timestamp, P25P1DataUnitID dataUnitID)
        {
            super(dataUnitID, new CorrectedBinaryMessage(96), nac, timestamp);
        }

        @Override
        public Direction getDirection()
        {
            return Direction.OUTBOUND;
        }

        @Override
        public Opcode getOpcode()
        {
            return Opcode.OSP_UNKNOWN;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of();
        }
    }

    private static class TestNetworkStatusBroadcast extends NetworkStatusBroadcast
    {
        private final ScrambleParameters mScrambleParameters;

        private TestNetworkStatusBroadcast(int nac, long timestamp)
        {
            this(nac, nac, timestamp);
        }

        private TestNetworkStatusBroadcast(int nac, int scrambleNac, long timestamp)
        {
            super(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, new CorrectedBinaryMessage(96), nac, timestamp);
            mScrambleParameters = new ScrambleParameters(0xABCDE, 0x123, scrambleNac);
        }

        @Override
        public ScrambleParameters getScrambleParameters()
        {
            return mScrambleParameters;
        }
    }

    private static class TestNonControlObservation extends P25P1Message
    {
        private TestNonControlObservation(int nac, long timestamp)
        {
            super(new CorrectedBinaryMessage(0), nac, timestamp);
        }

        @Override
        public P25P1DataUnitID getDUID()
        {
            return P25P1DataUnitID.LOGICAL_LINK_DATA_UNIT_1;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of();
        }
    }

    private static class TestTSBKGrantUpdate extends GroupVoiceChannelGrantUpdate
    {
        private final APCO25Channel mChannel;

        private TestTSBKGrantUpdate(APCO25Channel channel)
        {
            super(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, new CorrectedBinaryMessage(96), NAC, TIMESTAMP);
            mChannel = channel;
        }

        @Override
        public Opcode getOpcode()
        {
            return Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT_UPDATE;
        }

        @Override
        public APCO25Channel getChannelA()
        {
            return mChannel;
        }

        @Override
        public Identifier getGroupAddressA()
        {
            return TALKGROUP;
        }

        @Override
        public boolean hasGroupB()
        {
            return false;
        }
    }

    private static class TestAMBTCGrant extends AMBTCGroupVoiceChannelGrant
    {
        private final APCO25Channel mChannel;

        private TestAMBTCGrant(APCO25Channel channel)
        {
            super(new PDUSequence(header(Opcode.OSP_GROUP_VOICE_CHANNEL_GRANT), TIMESTAMP, NAC), NAC, TIMESTAMP);
            mChannel = channel;
        }

        @Override
        public APCO25Channel getChannel()
        {
            return mChannel;
        }

        @Override
        public VoiceServiceOptions getServiceOptions()
        {
            return VoiceServiceOptions.createUnencrypted();
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of(TALKGROUP);
        }
    }

    private static class TestAMBTCGrantUpdate extends AMBTCUnitToUnitVoiceServiceChannelGrantUpdate
    {
        private final APCO25Channel mChannel;

        private TestAMBTCGrantUpdate(APCO25Channel channel)
        {
            super(new PDUSequence(header(Opcode.OSP_UNIT_TO_UNIT_VOICE_CHANNEL_GRANT_UPDATE), TIMESTAMP, NAC),
                NAC, TIMESTAMP);
            mChannel = channel;
        }

        @Override
        public APCO25Channel getChannel()
        {
            return mChannel;
        }

        @Override
        public VoiceServiceOptions getServiceOptions()
        {
            return VoiceServiceOptions.createUnencrypted();
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of(TALKGROUP);
        }
    }

    private static class TestMotorolaTDMADataChannel extends MotorolaExplicitTDMADataChannelAnnouncement
    {
        private final APCO25Channel mChannel;

        private TestMotorolaTDMADataChannel(APCO25Channel channel)
        {
            super(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, new CorrectedBinaryMessage(96), NAC, TIMESTAMP);
            mChannel = channel;
        }

        @Override
        public Opcode getOpcode()
        {
            return Opcode.MOTOROLA_OSP_TDMA_DATA_CHANNEL;
        }

        @Override
        public Vendor getVendor()
        {
            return Vendor.MOTOROLA;
        }

        @Override
        public boolean hasChannel()
        {
            return true;
        }

        @Override
        public APCO25Channel getChannel()
        {
            return mChannel;
        }
    }

    private static class TestFrequencyBandUpdate extends FrequencyBandUpdate
    {
        private TestFrequencyBandUpdate()
        {
            super(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, new CorrectedBinaryMessage(96), NAC, TIMESTAMP);
        }

        @Override
        public Opcode getOpcode()
        {
            return Opcode.OSP_IDENTIFIER_UPDATE;
        }

        @Override
        public int getIdentifier()
        {
            return 0;
        }

        @Override
        public long getChannelSpacing()
        {
            return 6_250L;
        }

        @Override
        public long getBaseFrequency()
        {
            return 851_006_250L;
        }

        @Override
        public int getBandwidth()
        {
            return 12_500;
        }

        @Override
        public long getTransmitOffset()
        {
            return -45_000_000L;
        }

        @Override
        public boolean hasTransmitOffset()
        {
            return true;
        }
    }

    private static class RecordingTrafficChannelManager extends P25TrafficChannelManager
    {
        private int mDirectedGrantCount;
        private int mAnnouncedUpdateCount;
        private int mPhase2DataCount;
        private int mFrequencyBandCount;
        private int mTalkerAliasCount;
        private IdentifierCollection mLastIdentifiers;

        private RecordingTrafficChannelManager()
        {
            super(new Channel("Parent"));
        }

        @Override
        public void processP1ControlDirectedChannelGrant(APCO25Channel channel, ServiceOptions serviceOptions,
                                                         IdentifierCollection identifiers, Opcode opcode,
                                                         long timestamp)
        {
            mDirectedGrantCount++;
            mLastIdentifiers = identifiers;
        }

        @Override
        public boolean resolveControlChannel(APCO25Channel channel)
        {
            return P25FrequencyBandValidator.isResolvedChannel(channel);
        }

        @Override
        public void processP1ControlAnnouncedTrafficUpdate(APCO25Channel channel, ServiceOptions serviceOptions,
                                                           IdentifierCollection identifiers, Opcode opcode,
                                                           long timestamp)
        {
            mAnnouncedUpdateCount++;
        }

        @Override
        public void processP2DataChannel(APCO25Channel channel, long timestamp)
        {
            mPhase2DataCount++;
        }

        @Override
        public void processFrequencyBand(IFrequencyBand frequencyBand)
        {
            mFrequencyBandCount++;
        }

        @Override
        public void processP1MotorolaTalkerAlias(long frequency, RadioIdentifier radio, Identifier talkgroup,
                                                 TalkerAliasIdentifier alias, IdentifierCollection identifiers,
                                                 long timestamp)
        {
            mTalkerAliasCount++;
        }
    }
}
