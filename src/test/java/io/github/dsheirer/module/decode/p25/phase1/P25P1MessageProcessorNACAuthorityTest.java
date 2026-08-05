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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.IDecoderStateEventProvider;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.phase1.message.hdu.HDUMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.FrequencyBandUpdate;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.GroupVoiceChannelGrant;
import io.github.dsheirer.module.decode.p25.reference.Direction;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P1MessageProcessorNACAuthorityTest
{
    private static final int NAC = 0x123;
    private static final int FOREIGN_NAC = 0x456;
    private static final long BASE_FREQUENCY = 851_006_250L;

    @Test
    void candidatesStayOffSharedStreamAndEstablishingThirdMessageIsProcessedExactlyOnce()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor(true);
        List<IMessage> received = new ArrayList<>();
        List<DecoderStateEvent> events = new ArrayList<>();
        processor.setMessageListener(received::add);
        processor.setDecoderStateListener(events::add);

        processor.receive(new Observation(NAC, 1_000L));
        processor.receive(new Observation(NAC, 1_001L));

        assertEquals(0, received.size(), "candidate payloads must stay fail-closed");
        assertEquals(2, events.size(), "candidate activity must hold a rotating control channel");
        assertEquals(DecoderStateEvent.Event.DECODE, events.get(0).getEvent());
        assertEquals(DecoderStateEvent.Event.DECODE, events.get(1).getEvent());
        assertEquals(State.CONTROL, events.get(0).getState());
        assertEquals(State.CONTROL, events.get(1).getState());

        Band establishingBand = new Band(NAC, 1_002L, BASE_FREQUENCY);
        processor.receive(establishingBand);

        assertEquals(1, received.size(), "the establishing unit must be published once");
        assertSame(establishingBand, received.getFirst());
        assertEquals(2, events.size(), "the establishing payload supplies normal downstream control activity");

        processor.receive(new Band(NAC, 1_003L, BASE_FREQUENCY));
        processor.receive(new Band(FOREIGN_NAC, 1_005L, 450_000_000L));
        processor.receive(new Band(FOREIGN_NAC, 1_006L, 450_000_000L));

        APCO25Channel channel = APCO25Channel.create(0, 1);
        processor.receive(new Grant(channel, NAC, 1_007L));

        assertEquals(3, received.size(), "foreign band messages must not reach shared listeners");
        assertEquals(2, events.size(), "foreign NAC must not keep the control channel alive");
        assertEquals(BASE_FREQUENCY + 6_250L, channel.getDownlinkFrequency());
    }

    @Test
    void voiceAndDataStayClosedUntilAuthorityAndRejectForeignNACAfterward()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor(true);
        List<IMessage> received = new ArrayList<>();
        List<DecoderStateEvent> events = new ArrayList<>();
        processor.setMessageListener(received::add);
        processor.setDecoderStateListener(events::add);

        processor.receive(new HDUMessage(new CorrectedBinaryMessage(648), FOREIGN_NAC, 999L));
        assertEquals(0, received.size());

        processor.receive(new Observation(NAC, 1_000L));
        processor.receive(new Observation(NAC, 1_001L));
        assertEquals(0, received.size());
        assertEquals(2, events.size());

        Observation establishing = new Observation(NAC, 1_002L);
        processor.receive(establishing);
        assertEquals(1, received.size());
        assertSame(establishing, received.getFirst());
        received.clear();

        processor.receive(new HDUMessage(new CorrectedBinaryMessage(648), FOREIGN_NAC, 1_003L));
        assertEquals(0, received.size());

        processor.receive(new HDUMessage(new CorrectedBinaryMessage(648), NAC, 1_004L));
        assertEquals(1, received.size());
    }

    @Test
    void sourceFrequencyChangeRequiresFreshAuthority()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor(true);
        List<IMessage> received = new ArrayList<>();
        List<DecoderStateEvent> events = new ArrayList<>();
        processor.setMessageListener(received::add);
        processor.setDecoderStateListener(events::add);

        processor.receive(new Observation(NAC, 1_000L));
        processor.receive(new Observation(NAC, 1_001L));
        processor.receive(new Observation(NAC, 1_002L));
        received.clear();
        events.clear();

        processor.resetForSourceFrequencyChange();
        processor.receive(new HDUMessage(new CorrectedBinaryMessage(648), NAC, 2_000L));
        processor.receive(new Observation(NAC, 2_001L));
        processor.receive(new Observation(NAC, 2_002L));

        assertEquals(0, received.size(), "the old NAC must not authorize a new RF source");
        assertEquals(2, events.size());

        Observation establishing = new Observation(NAC, 2_003L);
        processor.receive(establishing);
        assertEquals(1, received.size());
        assertSame(establishing, received.getFirst());

        received.clear();
        events.clear();
        processor.resetForSourceFrequencyChange();
        processor.receive(new Observation(NAC, 3_000L));
        processor.receive(new Observation(NAC, 3_001L));
        assertEquals(2, events.size());

        processor.resetForSourceFrequencyChange();
        processor.receive(new Observation(NAC, 3_002L));
        assertEquals(0, received.size(), "pending observations from the old source must be discarded");
        assertEquals(3, events.size(), "one observation after reset must remain a candidate");
    }

    @Test
    void modulationDecodersDelegateControlActivityListener() throws Exception
    {
        assertDecoderStateListenerDelegation(new P25P1DecoderC4FM(25_000, true));
        assertDecoderStateListenerDelegation(new P25P1DecoderLSM(25_000, true));
    }

    @Test
    void rejectedSourceClearsPartialReassemblyWithoutResettingAuthority() throws Exception
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor(true);
        processor.setMessageListener(message -> {});
        processor.receive(new Observation(NAC, 1_000L));
        processor.receive(new Observation(NAC, 1_001L));
        processor.receive(new Observation(NAC, 1_002L));

        Field heldHDU = P25P1MessageProcessor.class.getDeclaredField("mHeldHDUMessage");
        heldHDU.setAccessible(true);
        heldHDU.set(processor, new HDUMessage(new CorrectedBinaryMessage(648), NAC, 1_003L));

        processor.receive(new HDUMessage(new CorrectedBinaryMessage(648), FOREIGN_NAC, 1_004L));
        assertNull(heldHDU.get(processor));

        List<IMessage> received = new ArrayList<>();
        processor.setMessageListener(received::add);
        processor.receive(new HDUMessage(new CorrectedBinaryMessage(648), NAC, 1_005L));
        assertEquals(1, received.size(), "a rejected source must not reset the frozen NAC authority");
    }

    private static void assertDecoderStateListenerDelegation(IDecoderStateEventProvider decoder) throws Exception
    {
        List<DecoderStateEvent> events = new ArrayList<>();
        decoder.setDecoderStateListener(events::add);

        Field field = decoder.getClass().getDeclaredField("mMessageProcessor");
        field.setAccessible(true);
        P25P1MessageProcessor processor = (P25P1MessageProcessor)field.get(decoder);
        processor.receive(new Observation(NAC, 1_000L));

        assertEquals(1, events.size(), decoder.getClass().getSimpleName());
        assertEquals(State.CONTROL, events.getFirst().getState());

        decoder.setDecoderStateListener(null);
        processor.receive(new Observation(NAC, 1_001L));
        assertEquals(1, events.size(), "null listener must unregister candidate activity");

        processor.resetForSourceFrequencyChange();
        decoder.setDecoderStateListener(events::add);
        processor.receive(new Observation(NAC, 2_000L));
        assertEquals(2, events.size());

        decoder.removeDecoderStateListener();
        processor.receive(new Observation(NAC, 2_001L));
        assertEquals(2, events.size(), "removed listener must not receive candidate activity");
    }

    private static class Observation extends TSBKMessage
    {
        private Observation(int nac, long timestamp)
        {
            super(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, new CorrectedBinaryMessage(96), nac, timestamp);
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

    private static class Band extends FrequencyBandUpdate
    {
        private final long mBaseFrequency;

        private Band(int nac, long timestamp, long baseFrequency)
        {
            super(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, new CorrectedBinaryMessage(96), nac, timestamp);
            mBaseFrequency = baseFrequency;
        }

        @Override
        public Direction getDirection()
        {
            return Direction.OUTBOUND;
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
            return mBaseFrequency;
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

    private static class Grant extends GroupVoiceChannelGrant
    {
        private final APCO25Channel mChannel;

        private Grant(APCO25Channel channel, int nac, long timestamp)
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
            return List.of();
        }
    }
}
