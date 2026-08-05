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

import io.github.dsheirer.bits.CorrectedBinaryMessage;
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
    void foreignNACCannotPoisonUpstreamFrequencyBandEnrichment()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor(true);
        processor.setMessageListener(message -> {});
        processor.receive(new Observation(NAC, 1_000L));
        processor.receive(new Observation(NAC, 1_001L));
        processor.receive(new Observation(NAC, 1_002L));

        processor.receive(new Band(NAC, 1_003L, BASE_FREQUENCY));
        processor.receive(new Band(NAC, 1_004L, BASE_FREQUENCY));
        processor.receive(new Band(FOREIGN_NAC, 1_005L, 450_000_000L));
        processor.receive(new Band(FOREIGN_NAC, 1_006L, 450_000_000L));

        APCO25Channel channel = APCO25Channel.create(0, 1);
        processor.receive(new Grant(channel, NAC, 1_007L));

        assertEquals(BASE_FREQUENCY + 6_250L, channel.getDownlinkFrequency());
    }

    @Test
    void voiceAndDataStayClosedUntilAuthorityAndRejectForeignNACAfterward()
    {
        P25P1MessageProcessor processor = new P25P1MessageProcessor(true);
        List<IMessage> received = new ArrayList<>();
        processor.setMessageListener(received::add);

        processor.receive(new HDUMessage(new CorrectedBinaryMessage(648), FOREIGN_NAC, 999L));
        assertEquals(0, received.size());

        processor.receive(new Observation(NAC, 1_000L));
        processor.receive(new Observation(NAC, 1_001L));
        processor.receive(new Observation(NAC, 1_002L));
        received.clear();

        processor.receive(new HDUMessage(new CorrectedBinaryMessage(648), FOREIGN_NAC, 1_003L));
        assertEquals(0, received.size());

        processor.receive(new HDUMessage(new CorrectedBinaryMessage(648), NAC, 1_004L));
        assertEquals(1, received.size());
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
