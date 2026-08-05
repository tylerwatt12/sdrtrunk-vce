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

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.Channel.ChannelType;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
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
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.motorola.osp.MotorolaExplicitTDMADataChannelAnnouncement;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.FrequencyBandUpdate;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.GroupVoiceChannelGrant;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.GroupVoiceChannelGrantUpdate;
import io.github.dsheirer.module.decode.p25.reference.ServiceOptions;
import io.github.dsheirer.module.decode.p25.reference.Vendor;
import io.github.dsheirer.module.decode.p25.reference.VoiceServiceOptions;
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

    private static RecordingTrafficChannelManager exercise(ChannelType channelType)
    {
        Channel channel = new Channel("Test", channelType);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        RecordingTrafficChannelManager manager = new RecordingTrafficChannelManager();
        P25P1DecoderState decoderState = new P25P1DecoderState(channel, manager);
        APCO25Channel trafficChannel = resolvedChannel();

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
            super(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, new CorrectedBinaryMessage(96), NAC, TIMESTAMP);
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
    }
}
