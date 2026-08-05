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

package io.github.dsheirer.module.decode.p25.phase2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.BinaryMessage;
import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroupManager;
import io.github.dsheirer.module.decode.p25.P25NACAuthority;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.DataUnitID;
import io.github.dsheirer.module.decode.p25.phase2.enumeration.ScrambleParameters;
import io.github.dsheirer.module.decode.p25.phase2.message.SuperFrameFragment;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessage;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacMessageFactory;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.MacOpcode;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.FrequencyBandUpdate;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.GroupVoiceChannelGrantImplicit;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.MacStructure;
import io.github.dsheirer.module.decode.p25.phase2.message.mac.structure.NetworkStatusBroadcastImplicit;
import io.github.dsheirer.module.decode.p25.reference.ServiceOptions;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.AbstractSignalingTimeslot;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.ScramblingSequence;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.Timeslot;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.Voice4Timeslot;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.source.SourceEvent;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P2ControlNACAuthorityTest
{
    private static final int NAC = 0x123;
    private static final int FOREIGN_NAC = 0x456;

    @Test
    void macFactoryPreservesCRCProtectedNACZero()
    {
        CorrectedBinaryMessage valid = new CorrectedBinaryMessage(180);

        for(int bit = 164; bit < 180; bit++)
        {
            valid.set(bit);
        }

        List<MacMessage> messages = MacMessageFactory.create(0, DataUnitID.UNSCRAMBLED_LCCH, valid, 1_000L, 152);
        assertFalse(messages.isEmpty());
        assertTrue(messages.stream().allMatch(MacMessage::isValid));
        assertTrue(messages.stream().allMatch(MacMessage::hasNAC));
        assertTrue(messages.stream().allMatch(message -> ((Number)message.getNAC().getValue()).intValue() == 0));

        CorrectedBinaryMessage damaged = new CorrectedBinaryMessage(valid);
        damaged.flip(10);
        List<MacMessage> rejected = MacMessageFactory.create(0, DataUnitID.UNSCRAMBLED_LCCH, damaged, 1_001L, 152);
        assertEquals(1, rejected.size());
        assertFalse(rejected.getFirst().isValid());
        assertFalse(rejected.getFirst().hasNAC());
    }

    @Test
    void sharedAuthorityCountsPhysicalLcchUnitsAndFreezes()
    {
        Channel parent = parent();
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);

        assertEquals(P25NACAuthority.NO_NAC, manager.observeP2ControlNAC(message(NAC, 0, 1_000L, null)));
        assertEquals(P25NACAuthority.NO_NAC, manager.observeP2ControlNAC(message(NAC, 0, 1_000L, null)),
            "multiple structures from one physical LCCH unit must count once");
        assertEquals(P25NACAuthority.NO_NAC, manager.observeP2ControlNAC(message(NAC, 1, 1_000L, null)),
            "the other simultaneous timeslot is a distinct physical unit");
        assertEquals(NAC, manager.observeP2ControlNAC(message(NAC, 0, 1_001L, null)));
        assertEquals(P25NACAuthority.NO_NAC, manager.observeP2ControlNAC(message(FOREIGN_NAC, 0, 1_002L, null)));
        assertEquals(NAC, manager.observeP2ControlNAC(message(NAC, 0, 1_003L, null)));
        assertEquals(P25NACAuthority.NO_NAC, manager.observeP2ControlNAC(message(0xF7E, 0, 1_004L, null)));
    }

    @Test
    void allThreeAuthorityObservationsMustFitWithinOneSecond()
    {
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent());

        assertEquals(P25NACAuthority.NO_NAC, manager.observeP2ControlNAC(message(NAC, 0, 0L, null)));
        assertEquals(P25NACAuthority.NO_NAC, manager.observeP2ControlNAC(message(NAC, 0, 1_000L, null)));
        assertEquals(P25NACAuthority.NO_NAC, manager.observeP2ControlNAC(message(NAC, 0, 2_000L, null)));
        assertEquals(P25NACAuthority.NO_NAC, manager.observeP2ControlNAC(message(NAC, 0, 2_001L, null)));
        assertEquals(NAC, manager.observeP2ControlNAC(message(NAC, 0, 2_002L, null)));
    }

    @Test
    void sourceFrequencyChangeRequiresFreshSharedAuthority()
    {
        Channel parent = parent();
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        manager.setCurrentControlFrequency(851_000_000L, parent);
        manager.observeP2ControlNAC(message(NAC, 0, 1_000L, null));
        manager.observeP2ControlNAC(message(NAC, 0, 1_001L, null));
        assertEquals(NAC, manager.observeP2ControlNAC(message(NAC, 0, 1_002L, null)));

        manager.setCurrentControlFrequency(852_000_000L, parent);
        assertEquals(P25NACAuthority.NO_NAC, manager.observeP2ControlNAC(message(FOREIGN_NAC, 0, 2_000L, null)));
        assertEquals(P25NACAuthority.NO_NAC, manager.observeP2ControlNAC(message(FOREIGN_NAC, 0, 2_001L, null)));
        assertEquals(FOREIGN_NAC, manager.observeP2ControlNAC(message(FOREIGN_NAC, 0, 2_002L, null)));
    }

    @Test
    void decoderStatePassesOnlyConfirmedMatchingNACIntoGrants()
    {
        assertConfirmedStateGrant(NAC);
        assertConfirmedStateGrant(0);
    }

    @Test
    void onlyAuthorizedLcchControlCanPublishFrequencyBands()
    {
        Channel control = parent();
        P25TrafficChannelManager controlManager = new P25TrafficChannelManager(control);
        P25P2DecoderState controlState =
            new P25P2DecoderState(control, 0, controlManager, new PatchGroupManager());
        controlState.receive(message(NAC, 0, 1_000L, new Grant()));
        controlState.receive(message(NAC, 0, 1_001L, new Grant()));
        controlState.receive(message(NAC, 0, 1_002L, new Grant()));
        controlState.receive(message(NAC, 0, 1_003L, new Band()));
        controlState.receive(message(NAC, 0, 1_004L, new Band()));

        APCO25Channel controlGrant = APCO25Channel.create(0, 1);
        assertTrue(controlManager.resolveControlChannel(controlGrant));
        assertEquals(851_012_500L, controlGrant.getDownlinkFrequency());

        Channel traffic = new Channel("P2 Traffic", ChannelType.TRAFFIC);
        traffic.setDecodeConfiguration(new DecodeConfigP25Phase2());
        P25TrafficChannelManager trafficManager = new P25TrafficChannelManager(traffic);
        P25P2DecoderState trafficState =
            new P25P2DecoderState(traffic, 0, trafficManager, new PatchGroupManager());
        trafficState.receive(message(NAC, 0, 2_000L, DataUnitID.UNSCRAMBLED_SACCH, new Band()));
        trafficState.receive(message(NAC, 0, 2_001L, DataUnitID.UNSCRAMBLED_SACCH, new Band()));

        assertFalse(trafficManager.resolveControlChannel(APCO25Channel.create(0, 1)));
    }

    @Test
    void facchCannotActAsControlEvenAfterLcchAuthorityExists()
    {
        Channel parent = parent();
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(parent);
        P25P2DecoderState state = new P25P2DecoderState(parent, 0, manager, new PatchGroupManager());
        state.receive(message(NAC, 0, 1_000L, new NoOp()));
        state.receive(message(NAC, 0, 1_001L, new NoOp()));
        state.receive(message(NAC, 0, 1_002L, new NoOp()));

        state.receive(message(NAC, 0, 1_003L, DataUnitID.UNSCRAMBLED_FACCH, new Grant()));
        assertEquals(0, manager.mGrantCount, "FACCH traffic signaling cannot allocate from a control decoder");

        state.receive(message(NAC, 0, 1_004L, new Grant()));
        assertEquals(1, manager.mGrantCount);
    }

    @Test
    void trafficChannelLcchCannotBecomeASecondControlDecoder()
    {
        Channel traffic = new Channel("P2 Traffic", ChannelType.TRAFFIC);
        traffic.setDecodeConfiguration(new DecodeConfigP25Phase2());
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(traffic);
        P25P2DecoderState state = new P25P2DecoderState(traffic, 0, manager, new PatchGroupManager());
        List<DecoderStateEvent> events = new ArrayList<>();
        state.setDecoderStateListener(events::add);

        state.receive(message(NAC, 0, 1_000L, new Grant()));
        state.receive(message(NAC, 0, 1_001L, new Grant()));
        state.receive(message(NAC, 0, 1_002L, new Grant()));

        assertEquals(0, manager.mGrantCount);
        assertEquals(0, events.size(), "traffic decoders must not emit CONTROL holds from LCCH artifacts");
    }

    @Test
    void ordinaryStateResetKeepsSameSourceAuthorityAndNAC()
    {
        Channel parent = parent();
        P25TrafficChannelManager manager = new P25TrafficChannelManager(parent);
        P25P2DecoderState state = new P25P2DecoderState(parent, 0, manager, new PatchGroupManager());
        state.receive(message(NAC, 0, 1_000L, new NoOp()));
        state.receive(message(NAC, 0, 1_001L, new NoOp()));
        state.receive(message(NAC, 0, 1_002L, new NoOp()));

        state.receiveDecoderStateEvent(new DecoderStateEvent(this, DecoderStateEvent.Event.REQUEST_RESET,
            State.IDLE, 0));

        assertTrue(manager.isP2ControlNACGateOpen());
        Identifier nac = state.getIdentifierCollection().getIdentifier(IdentifierClass.NETWORK,
            Form.NETWORK_ACCESS_CODE, Role.BROADCAST);
        assertNotNull(nac);
        assertEquals(NAC, nac.getValue());
    }

    @Test
    void messageProcessorKeepsSharedListenersFailClosed()
    {
        P25P2MessageProcessor processor = new P25P2MessageProcessor(true);
        List<IMessage> received = new ArrayList<>();
        processor.setMessageListener(candidate ->
        {
            if(candidate != null)
            {
                received.add(candidate);
            }
        });

        Voice4Timeslot preAuthorityVoice = voice(999L);
        processor.receive(fragment(preAuthorityVoice));
        assertFalse(received.contains(preAuthorityVoice));

        processor.receive(fragment(signaling(message(NAC, 0, 1_000L, new NoOp()))));
        processor.receive(fragment(signaling(message(NAC, 0, 1_001L, new NoOp()))));
        assertEquals(0, received.stream().filter(MacMessage.class::isInstance).count());

        MacMessage established = message(NAC, 0, 1_002L, new NoOp());
        processor.receive(fragment(signaling(established)));
        assertTrue(received.contains(established));
        assertTrue(established.isNACAuthorityValidated());

        received.clear();
        MacMessage foreign = message(FOREIGN_NAC, 0, 1_003L, new NoOp());
        processor.receive(fragment(signaling(foreign)));
        assertFalse(received.contains(foreign));

        Voice4Timeslot closedVoice = voice(1_004L);
        processor.receive(fragment(closedVoice));
        assertFalse(received.contains(closedVoice));

        Voice4Timeslot reopenedVoice = voice(1_006L);
        processor.receive(new TestSuperFrameFragment(List.of(
            signaling(message(NAC, 0, 1_005L, new NoOp())), reopenedVoice)));
        assertTrue(received.contains(reopenedVoice));
    }

    @Test
    void guardedControlRequiresLcchInEveryFragmentButAllowsOppositeSlotVoice()
    {
        P25P2MessageProcessor processor = new P25P2MessageProcessor(true);
        List<IMessage> received = new ArrayList<>();
        processor.setMessageListener(candidate ->
        {
            if(candidate != null)
            {
                received.add(candidate);
            }
        });

        processor.receive(fragment(signaling(message(NAC, 1, 1_000L, new NoOp()))));
        processor.receive(fragment(signaling(message(NAC, 1, 1_001L, new NoOp()))));
        processor.receive(fragment(signaling(message(NAC, 1, 1_002L, new NoOp()))));

        Voice4Timeslot mixedVoice = voice(2, 1_003L);
        processor.receive(new TestSuperFrameFragment(List.of(
            mixedVoice, signaling(message(NAC, 1, 1_003L, new NoOp())))));
        assertTrue(received.contains(mixedVoice),
            "a TS1 LCCH authorizes TS2 voice even when voice appears first in fragment order");

        Voice4Timeslot voiceOnly = voice(2, 1_004L);
        processor.receive(fragment(voiceOnly));
        assertFalse(received.contains(voiceOnly), "a guarded control fragment without LCCH must close");

        Voice4Timeslot reopenedVoice = voice(2, 1_005L);
        processor.receive(new TestSuperFrameFragment(List.of(
            signaling(message(NAC, 1, 1_005L, new NoOp())), reopenedVoice)));
        assertTrue(received.contains(reopenedVoice));
    }

    @Test
    void authorizedScrambleUpdateReDecodesFragmentBeforeVoiceIsPublished()
    {
        P25P2MessageProcessor processor = authorizedProcessor();
        List<IMessage> received = new ArrayList<>();
        List<ScrambleParameters> updates = new ArrayList<>();
        processor.setMessageListener(candidate ->
        {
            if(candidate != null)
            {
                received.add(candidate);
            }
        });
        processor.setScrambleParametersListener(updates::add);

        ScrambleParameters parameters = new ScrambleParameters(0xABCDE, 0x234, NAC);
        MacMessage nsb = message(NAC, 0, 1_010L, new TestNetworkStatus(parameters));
        Voice4Timeslot staleVoice = voice(1_010L);
        Voice4Timeslot refreshedVoice = voice(1_011L);
        ReDecodingSuperFrameFragment fragment = new ReDecodingSuperFrameFragment(
            List.of(signaling(nsb), staleVoice), List.of(signaling(nsb), refreshedVoice));

        processor.receive(fragment);

        assertEquals(1, fragment.mResetCount);
        assertEquals(1, updates.size());
        assertFalse(received.contains(staleVoice));
        assertTrue(received.contains(refreshedVoice));
    }

    @Test
    void inconsistentInnerScrambleNacClosesTheWholeFragment()
    {
        P25P2MessageProcessor processor = authorizedProcessor();
        List<IMessage> received = new ArrayList<>();
        List<ScrambleParameters> updates = new ArrayList<>();
        processor.setMessageListener(candidate ->
        {
            if(candidate != null)
            {
                received.add(candidate);
            }
        });
        processor.setScrambleParametersListener(updates::add);
        MacMessage inconsistent = message(NAC, 0, 1_010L,
            new TestNetworkStatus(new ScrambleParameters(0xABCDE, 0x234, FOREIGN_NAC)));
        Voice4Timeslot sameFragmentVoice = voice(1_010L);

        processor.receive(new TestSuperFrameFragment(List.of(sameFragmentVoice, signaling(inconsistent))));

        assertEquals(0, updates.size());
        assertFalse(received.contains(inconsistent));
        assertFalse(received.contains(sameFragmentVoice));

        Voice4Timeslot laterVoice = voice(1_011L);
        processor.receive(fragment(laterVoice));
        assertFalse(received.contains(laterVoice));
    }

    @Test
    void messageProcessorGuardsFragmentsAndResetsAtSourceBoundary()
    {
        P25P2MessageProcessor processor = new P25P2MessageProcessor(true);
        assertFalse(processor.isAuthorizedForProcessing(message(NAC, 0, 1_000L, new NoOp())));
        assertFalse(processor.isAuthorizedForProcessing(message(NAC, 0, 1_001L, new NoOp())));
        assertTrue(processor.isAuthorizedForProcessing(message(NAC, 0, 1_002L, new NoOp())));
        assertFalse(processor.isAuthorizedForProcessing(message(FOREIGN_NAC, 0, 1_003L, new Band())));
        assertTrue(processor.isAuthorizedForProcessing(message(NAC, 0, 1_004L, new Band())));

        processor.resetForSourceFrequencyChange();
        assertFalse(processor.isAuthorizedForProcessing(
            message(NAC, 0, 2_000L, DataUnitID.UNSCRAMBLED_FACCH, new Grant())),
            "non-LCCH fragments cannot survive or act before the new source is authorized");
    }

    @Test
    void rejectedLcchDiscardsPartialAssemblyWithoutResettingAuthority() throws Exception
    {
        P25P2MessageProcessor processor = new P25P2MessageProcessor(true);
        processor.isAuthorizedForProcessing(message(NAC, 0, 1_000L, new NoOp()));
        processor.isAuthorizedForProcessing(message(NAC, 0, 1_001L, new NoOp()));
        assertTrue(processor.isAuthorizedForProcessing(message(NAC, 0, 1_002L, new NoOp())));

        Field heldMessage = P25P2MessageProcessor.class.getDeclaredField("mMacMessageWithMultiFragment1");
        heldMessage.setAccessible(true);
        heldMessage.set(processor, message(NAC, 0, 1_003L, new NoOp()));

        assertFalse(processor.isAuthorizedForProcessing(message(FOREIGN_NAC, 0, 1_004L, new NoOp())));
        assertNull(heldMessage.get(processor));
        assertTrue(processor.isAuthorizedForProcessing(message(NAC, 0, 1_005L, new NoOp())),
            "a rejected source must not reset the frozen NAC authority");
    }

    @Test
    void guardedDecoderDoesNotCarryLearnedScrambleSequenceAcrossSources() throws Exception
    {
        DecodeConfigP25Phase2 configuration = new DecodeConfigP25Phase2();
        configuration.setAutoDetectScrambleParameters(true);
        P25P2DecoderHDQPSK decoder = new P25P2DecoderHDQPSK(configuration, 50_000, true);
        ScramblingSequence sequence = scramblingSequence(decoder);
        decoder.mMessageFramer.setScrambleParameters(new ScrambleParameters(0xABCDE, 0x234, NAC));
        assertTrue(sequence.getTimeslotSequence(0).cardinality() > 0);

        decoder.getSourceEventListener().receive(SourceEvent.frequencyChange(null, 852_000_000L));

        assertEquals(0, sequence.getTimeslotSequence(0).cardinality());
    }

    private static void assertConfirmedStateGrant(int nac)
    {
        Channel parent = parent();
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager(parent);
        P25P2DecoderState decoderState = new P25P2DecoderState(parent, 0, manager, new PatchGroupManager());

        decoderState.receive(message(nac, 0, 1_000L, new Grant()));
        decoderState.receive(message(nac, 0, 1_001L, new Grant()));
        assertEquals(0, manager.mGrantCount);
        decoderState.receive(message(nac, 0, 1_002L, new Grant()));
        assertEquals(1, manager.mGrantCount);
        assertNotNull(manager.mIdentifiers);
        Identifier identifier = manager.mIdentifiers.getIdentifier(IdentifierClass.NETWORK,
            Form.NETWORK_ACCESS_CODE, Role.BROADCAST);
        assertNotNull(identifier);
        assertEquals(nac, identifier.getValue());

        decoderState.receive(message(FOREIGN_NAC, 0, 1_003L, new Grant()));
        assertEquals(1, manager.mGrantCount);
        decoderState.receive(message(nac, 0, 1_004L, new Grant()));
        assertEquals(2, manager.mGrantCount);
    }

    private static Channel parent()
    {
        Channel parent = new Channel("P2 Control", ChannelType.STANDARD);
        parent.setDecodeConfiguration(new DecodeConfigP25Phase2());
        return parent;
    }

    private static MacMessage message(int nac, int timeslot, long timestamp, MacStructure structure)
    {
        return message(nac, timeslot, timestamp, DataUnitID.UNSCRAMBLED_LCCH, structure);
    }

    private static MacMessage message(int nac, int timeslot, long timestamp, DataUnitID dataUnitID,
                                      MacStructure structure)
    {
        MacMessage message = new MacMessage(timeslot, dataUnitID,
            new CorrectedBinaryMessage(180), timestamp, structure);
        message.setNAC(nac);
        return message;
    }

    private static SuperFrameFragment fragment(Timeslot timeslot)
    {
        return new TestSuperFrameFragment(timeslot);
    }

    private static AbstractSignalingTimeslot signaling(MacMessage message)
    {
        return new TestSignalingTimeslot(message);
    }

    private static Voice4Timeslot voice(long timestamp)
    {
        return voice(1, timestamp);
    }

    private static Voice4Timeslot voice(int timeslot, long timestamp)
    {
        return new Voice4Timeslot(new CorrectedBinaryMessage(320), new BinaryMessage(320), timeslot, timestamp);
    }

    private static P25P2MessageProcessor authorizedProcessor()
    {
        P25P2MessageProcessor processor = new P25P2MessageProcessor(true);
        processor.isAuthorizedForProcessing(message(NAC, 0, 1_000L, new NoOp()));
        processor.isAuthorizedForProcessing(message(NAC, 0, 1_001L, new NoOp()));
        assertTrue(processor.isAuthorizedForProcessing(message(NAC, 0, 1_002L, new NoOp())));
        return processor;
    }

    private static ScramblingSequence scramblingSequence(P25P2DecoderHDQPSK decoder) throws Exception
    {
        Field detectorField = P25P2MessageFramer.class.getDeclaredField("mSuperFrameDetector");
        detectorField.setAccessible(true);
        P25P2SuperFrameDetector detector = (P25P2SuperFrameDetector)detectorField.get(decoder.mMessageFramer);
        Field sequenceField = P25P2SuperFrameDetector.class.getDeclaredField("mScramblingSequence");
        sequenceField.setAccessible(true);
        return (ScramblingSequence)sequenceField.get(detector);
    }

    private static class Grant extends GroupVoiceChannelGrantImplicit
    {
        private final APCO25Channel mChannel = APCO25Channel.create(0, 1);

        private Grant()
        {
            super(new CorrectedBinaryMessage(180), 0);
        }

        @Override
        public MacOpcode getOpcode()
        {
            return MacOpcode.PHASE1_40_GROUP_VOICE_CHANNEL_GRANT_IMPLICIT;
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
            return List.of(APCO25Talkgroup.create(1_201));
        }
    }

    private static class Band extends FrequencyBandUpdate
    {
        private Band()
        {
            super(new CorrectedBinaryMessage(180), 0);
        }

        @Override
        public MacOpcode getOpcode()
        {
            return MacOpcode.PHASE1_7D_IDENTIFIER_UPDATE;
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

    private static class NoOp extends MacStructure
    {
        private NoOp()
        {
            super(new CorrectedBinaryMessage(180), 0);
        }

        @Override
        public MacOpcode getOpcode()
        {
            return MacOpcode.TDMA_00_NULL_INFORMATION_MESSAGE;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of();
        }
    }

    private static class TestNetworkStatus extends NetworkStatusBroadcastImplicit
    {
        private final ScrambleParameters mParameters;

        private TestNetworkStatus(ScrambleParameters parameters)
        {
            super(new CorrectedBinaryMessage(180), 0);
            mParameters = parameters;
        }

        @Override
        public MacOpcode getOpcode()
        {
            return MacOpcode.PHASE1_7B_NETWORK_STATUS_BROADCAST_IMPLICIT;
        }

        @Override
        public ScrambleParameters getScrambleParameters()
        {
            return mParameters;
        }
    }

    private static class TestSignalingTimeslot extends AbstractSignalingTimeslot
    {
        private final List<MacMessage> mMessages;

        private TestSignalingTimeslot(MacMessage message)
        {
            super(new CorrectedBinaryMessage(320), message.getDataUnitID(), message.getTimeslot(),
                message.getTimestamp());
            mMessages = List.of(message);
        }

        @Override
        public List<MacMessage> getMacMessages()
        {
            return mMessages;
        }
    }

    private static class TestSuperFrameFragment extends SuperFrameFragment
    {
        private final List<Timeslot> mTimeslots;

        private TestSuperFrameFragment(Timeslot timeslot)
        {
            this(List.of(timeslot));
        }

        private TestSuperFrameFragment(List<Timeslot> timeslots)
        {
            super(new CorrectedBinaryMessage(1_440), timeslots.getFirst().getTimestamp(), new ScramblingSequence());
            mTimeslots = timeslots;
        }

        @Override
        public List<Timeslot> getTimeslots()
        {
            return mTimeslots;
        }
    }

    private static class ReDecodingSuperFrameFragment extends SuperFrameFragment
    {
        private final List<Timeslot> mInitialTimeslots;
        private final List<Timeslot> mRefreshedTimeslots;
        private int mResetCount;

        private ReDecodingSuperFrameFragment(List<Timeslot> initialTimeslots, List<Timeslot> refreshedTimeslots)
        {
            super(new CorrectedBinaryMessage(1_440), initialTimeslots.getFirst().getTimestamp(),
                new ScramblingSequence());
            mInitialTimeslots = initialTimeslots;
            mRefreshedTimeslots = refreshedTimeslots;
        }

        @Override
        public void resetTimeslots()
        {
            mResetCount++;
        }

        @Override
        public List<Timeslot> getTimeslots()
        {
            return mResetCount == 0 ? mInitialTimeslots : mRefreshedTimeslots;
        }
    }

    private static class RecordingTrafficChannelManager extends P25TrafficChannelManager
    {
        private int mGrantCount;
        private IdentifierCollection mIdentifiers;

        private RecordingTrafficChannelManager(Channel parent)
        {
            super(parent);
        }

        @Override
        public void processP2ChannelGrant(APCO25Channel channel, ServiceOptions serviceOptions,
                                          IdentifierCollection identifiers, MacOpcode macOpcode, long timestamp)
        {
            mGrantCount++;
            mIdentifiers = identifiers;
        }
    }
}
