/*
 * *****************************************************************************
 * Copyright (C) 2026
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25Channel;
import io.github.dsheirer.module.decode.p25.identifier.channel.APCO25ExplicitChannel;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.SymbolMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.block.UnconfirmedDataBlock;
import java.util.Arrays;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;

/**
 * Constructed decoded blocks, not captured/FEC/CRC test vectors. TIA-102.AABC-E (April 2019) section 2.3.9 and
 * Figures 4.2.5-2, 4.2.8-2 and 5.2.1-2 place Channel(T) in block 0 and Channel(R) in block 1.
 */
class AMBTCExplicitGrantChannelTest
{
    @TestFactory
    Stream<DynamicTest> sourceNetworkBitsCannotDiscardADifferentReceiveChannel()
    {
        return forEachGrant(type ->
        {
            for(int sourceBits : new int[]{100, 101})
            {
                Decoded grant = type.decode(sequence(2, 100, 3, 200, sourceBits, 2));
                assertExplicit(grant, 3, 200);
                assertFrequencies(grant.channel(), 801_250_000L, 902_500_000L);
                assertArrayEquals(new int[]{2, 3}, grant.channel().getFrequencyBandIdentifiers());
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> equalNumbersInDifferentBandsRetainTheReceiveBand()
    {
        return forEachGrant(type ->
        {
            Decoded grant = type.decode(sequence(2, 100, 3, 100, 100, 2));
            assertExplicit(grant, 3, 100);
            assertFrequencies(grant.channel(), 801_250_000L, 901_250_000L);
        });
    }

    @TestFactory
    Stream<DynamicTest> identicalChannelFieldsRemainExplicitDespiteANonzeroBandOffset()
    {
        return forEachGrant(type ->
        {
            for(int sourceBits : new int[]{100, 101})
            {
                Decoded grant = type.decode(sequence(2, 100, 2, 100, sourceBits, 2));
                assertExplicit(grant, 2, 100);
                assertFrequencies(grant.channel(), 801_250_000L, 801_250_000L);
                assertArrayEquals(new int[]{2}, grant.channel().getFrequencyBandIdentifiers());
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> allOnesReceiveFieldRequestsImplicitSignaling()
    {
        return forEachGrant(type ->
        {
            for(int transmitNumber : new int[]{100, 4095})
            {
                Decoded grant = type.decode(sequence(2, transmitNumber, 15, 4095, 101, 2));
                assertFalse(grant.extended());
                assertFalse(grant.channel() instanceof APCO25ExplicitChannel);
                long downlink = 800_000_000L + transmitNumber * 12_500L;
                assertFrequencies(grant.channel(), downlink, downlink - 45_000_000L);
                assertArrayEquals(new int[]{2}, grant.channel().getFrequencyBandIdentifiers());
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> channelZeroIsAnExplicitReceiveChannel()
    {
        return forEachGrant(type ->
        {
            Decoded grant = type.decode(sequence(2, 100, 0, 0, 100, 2));
            assertExplicit(grant, 0, 0);
            grant.channel().setFrequencyBand(new P25FrequencyBand(0, 700_000_000L, -30_000_000L,
                12_500L, 12_500, 1));
            assertFrequencies(grant.channel(), 801_250_000L, 700_000_000L);
        });
    }

    @TestFactory
    Stream<DynamicTest> validFieldsAdjacentToTheAllOnesSentinelRemainExplicit()
    {
        return forEachGrant(type ->
        {
            for(int[] receive : new int[][]{{14, 4095}, {15, 4094}})
            {
                Decoded grant = type.decode(sequence(2, 4095, receive[0], receive[1], 4095, 2));
                assertExplicit(grant, receive[0], receive[1]);
                grant.channel().setFrequencyBand(new P25FrequencyBand(receive[0], 700_000_000L,
                    -30_000_000L, 12_500L, 12_500, 1));
                assertFrequencies(grant.channel(), 851_187_500L, 700_000_000L + receive[1] * 12_500L);
            }
        });
    }

    @TestFactory
    Stream<DynamicTest> unresolvedReceiveBandDoesNotSubstituteTheImplicitFrequency()
    {
        return forEachGrant(type ->
        {
            Decoded grant = type.decode(sequence(2, 100, 3, 200, 100, 2));
            assertExplicit(grant, 3, 200);
            grant.channel().setFrequencyBand(downlinkBand());
            assertEquals(801_250_000L, grant.channel().getDownlinkFrequency());
            assertEquals(0L, grant.channel().getUplinkFrequency());
        });
    }

    @TestFactory
    Stream<DynamicTest> missingSecondBlockKeepsTheExistingImplicitFallback()
    {
        return forEachGrant(type ->
        {
            Decoded grant = type.decode(sequence(2, 100, 3, 200, 101, 1));
            assertFalse(grant.extended());
            assertFalse(grant.channel() instanceof APCO25ExplicitChannel);
            assertFrequencies(grant.channel(), 801_250_000L, 756_250_000L);
        });
    }

    @TestFactory
    Stream<DynamicTest> missingDataBlocksKeepTheExistingUnknownChannel()
    {
        return forEachGrant(type ->
        {
            Decoded grant = type.decode(sequence(2, 100, 3, 200, 101, 0));
            assertFalse(grant.extended());
            assertEquals(-1, grant.channel().getValue().getDownlinkBandIdentifier());
            assertEquals(0, grant.channel().getDownlinkFrequency());
            assertEquals(0, grant.channel().getUplinkFrequency());
        });
    }

    private static Stream<DynamicTest> forEachGrant(Consumer<GrantType> check)
    {
        return Arrays.stream(GrantType.values()).map(type -> DynamicTest.dynamicTest(type.name(), () -> check.accept(type)));
    }

    private static void assertExplicit(Decoded grant, int band, int channel)
    {
        assertTrue(grant.extended());
        assertInstanceOf(APCO25ExplicitChannel.class, grant.channel());
        assertEquals(band, grant.channel().getValue().getUplinkBandIdentifier());
        assertEquals(channel, grant.channel().getValue().getUplinkChannelNumber());
    }

    private static P25FrequencyBand downlinkBand()
    {
        return new P25FrequencyBand(2, 800_000_000L, -45_000_000L, 12_500L, 12_500, 1);
    }

    private static void assertFrequencies(APCO25Channel channel, long downlink, long uplink)
    {
        channel.setFrequencyBand(downlinkBand());
        channel.setFrequencyBand(new P25FrequencyBand(3, 900_000_000L, -45_000_000L, 12_500L, 12_500, 1));
        assertEquals(downlink, channel.getDownlinkFrequency());
        assertEquals(uplink, channel.getUplinkFrequency());
    }

    private static PDUSequence sequence(int transmitBand, int transmitChannel, int receiveBand, int receiveChannel,
                                        int sourceBits, int blockCount)
    {
        CorrectedBinaryMessage header = new CorrectedBinaryMessage(96);
        header.setInt(2, IntField.length7(49));
        PDUSequence sequence = new PDUSequence(new AMBTCHeader(header, true), 1_000L, 1);
        CorrectedBinaryMessage block0 = new CorrectedBinaryMessage(96);
        //This slice belongs to the source WACN, not Channel(R).
        block0.setInt(sourceBits, IntField.length12(4));
        block0.setInt(transmitBand, IntField.length4(80));
        block0.setInt(transmitChannel, IntField.length12(84));
        CorrectedBinaryMessage block1 = new CorrectedBinaryMessage(96);
        block1.setInt(receiveBand, IntField.length4(0));
        block1.setInt(receiveChannel, IntField.length12(4));
        if(blockCount > 0)
        {
            sequence.addDataBlock(new TestBlock(block0));
        }
        if(blockCount > 1)
        {
            sequence.addDataBlock(new TestBlock(block1));
        }
        return sequence;
    }

    private enum GrantType
    {
        VOICE, VOICE_UPDATE, INDIVIDUAL_DATA;

        private Decoded decode(PDUSequence sequence)
        {
            return switch(this)
            {
                case VOICE ->
                {
                    var grant = new AMBTCUnitToUnitVoiceServiceChannelGrant(sequence, 1, 1_000L);
                    yield new Decoded(grant.isExtendedChannel(), grant.getChannel());
                }
                case VOICE_UPDATE ->
                {
                    var grant = new AMBTCUnitToUnitVoiceServiceChannelGrantUpdate(sequence, 1, 1_000L);
                    yield new Decoded(grant.isExtendedChannel(), grant.getChannel());
                }
                case INDIVIDUAL_DATA ->
                {
                    var grant = new AMBTCIndividualDataChannelGrant(sequence, 1, 1_000L);
                    yield new Decoded(grant.isExtendedChannel(), grant.getChannel());
                }
            };
        }
    }

    private record Decoded(boolean extended, APCO25Channel channel) {}

    private static class TestBlock extends UnconfirmedDataBlock
    {
        private final CorrectedBinaryMessage mMessage;

        private TestBlock(CorrectedBinaryMessage message)
        {
            super(new SymbolMessage(98));
            mMessage = message;
        }

        @Override
        public CorrectedBinaryMessage getMessage()
        {
            return mMessage;
        }
    }
}
