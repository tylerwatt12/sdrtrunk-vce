/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.module.decode.p25.phase1.message.SymbolMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.PDUSequence;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.AMBTCHeader;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.ambtc.osp.AMBTCFrequencyBandUpdateTDMA;
import io.github.dsheirer.module.decode.p25.phase1.message.pdu.block.UnconfirmedDataBlock;
import io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class P25P1ForeignSystemBandTest
{
    private static final int[] WACN = {32, 33, 34, 35, 36, 37, 38, 39, 40, 41, 42, 43, 44, 45, 46, 47,
        64, 65, 66, 67};

    @Test
    void decodesAndIsolatesReportedForeignSystemBand() throws Exception
    {
        AMBTCFrequencyBandUpdateTDMA message = message(0xBEE00, 0x9EF, 5, 3, 935_012_500L,
            12_500L, -39_000_000L);

        assertEquals(0xBEE00, message.getWacn().getValue());
        assertEquals(0x9EF, message.getSystem().getValue());
        assertEquals(5, message.getIdentifier());
        assertEquals(3, message.getChannelTypeValue());
        assertTrue(message.isTDMA());
        assertEquals(2, message.getTimeslotCount());
        assertEquals(935_012_500L, message.getBaseFrequency());
        assertEquals(12_500L, message.getChannelSpacing());
        assertEquals(-39_000_000L, message.getTransmitOffset());

        P25P1NetworkConfigurationMonitor monitor = new P25P1NetworkConfigurationMonitor(Modulation.C4FM);
        P25NetworkConfigurationSnapshot observation = monitor.process(message);

        assertTrue(observation.frequencyBands().isEmpty());
        assertEquals(1, observation.foreignSystemBands().size());
        P25NetworkConfigurationSnapshot.ForeignSystemBand band = observation.foreignSystemBands().getFirst();
        assertEquals(0xBEE00, band.wacn());
        assertEquals(0x9EF, band.system());
        assertEquals(5, band.band());
        assertEquals(3, band.channelType());
        assertEquals(935_012_500L, band.base());
        assertEquals(12_500L, band.spacing());
        assertEquals(-39_000_000L, band.transmitOffset());
    }

    @Test
    void rejectsHeaderCrcFailureWithCompletePayload() throws Exception
    {
        assertRejected(message(0xBEE00, 0x9EF, 5, 3, 935_012_500L,
            12_500L, -39_000_000L, false, 1, true));
    }

    @Test
    void rejectsCompleteSequenceWithoutRequiredBlockZero() throws Exception
    {
        assertRejected(message(0xBEE00, 0x9EF, 5, 3, 935_012_500L,
            12_500L, -39_000_000L, true, 0, false));
    }

    @Test
    void rejectedMessageDoesNotReplaceCachedBand() throws Exception
    {
        P25P1NetworkConfigurationMonitor monitor = new P25P1NetworkConfigurationMonitor(Modulation.C4FM);
        AMBTCFrequencyBandUpdateTDMA accepted = message(0xBEE00, 0x9EF, 5, 3, 935_012_500L,
            12_500L, -39_000_000L);
        AMBTCFrequencyBandUpdateTDMA rejected = message(0xBEE00, 0x9EF, 5, 3, 621_971_535L,
            12_500L, -39_000_000L, false, 1, true);

        monitor.process(accepted);
        assertNull(monitor.process(rejected));

        P25NetworkConfigurationSnapshot.ForeignSystemBand cached =
            monitor.getSnapshot().foreignSystemBands().getFirst();
        assertEquals(935_012_500L, cached.base());
    }

    private static void assertRejected(AMBTCFrequencyBandUpdateTDMA message)
    {
        P25P1NetworkConfigurationMonitor monitor = new P25P1NetworkConfigurationMonitor(Modulation.C4FM);

        assertNull(monitor.process(message));
        assertTrue(monitor.getSnapshot().foreignSystemBands().isEmpty());
    }

    private static AMBTCFrequencyBandUpdateTDMA message(int wacn, int system, int band, int channelType,
                                                         long base, long spacing, long offset) throws Exception
    {
        return message(wacn, system, band, channelType, base, spacing, offset, true, 1, true);
    }

    private static AMBTCFrequencyBandUpdateTDMA message(int wacn, int system, int band, int channelType,
                                                         long base, long spacing, long offset, boolean headerValid,
                                                         int blocksToFollow, boolean includeBlockZero) throws Exception
    {
        CorrectedBinaryMessage headerBits = header(wacn, system, band, channelType, blocksToFollow);
        PDUSequence sequence = new PDUSequence(new AMBTCHeader(headerBits, headerValid), 1_000L, 0x928);

        if(includeBlockZero)
        {
            CorrectedBinaryMessage blockBits = new CorrectedBinaryMessage(96);
            blockBits.setInt((int)(base / 5L), IntField.length32(0));
            if(offset >= 0)
            {
                blockBits.set(32);
            }
            blockBits.setInt((int)(Math.abs(offset) / spacing), IntField.range(33, 45));
            blockBits.setInt((int)(spacing / 125L), IntField.range(46, 55));

            UnconfirmedDataBlock block = new UnconfirmedDataBlock(new SymbolMessage(98));
            Field decodedMessage = UnconfirmedDataBlock.class.getDeclaredField("mDecodedMessage");
            decodedMessage.setAccessible(true);
            decodedMessage.set(block, blockBits);
            sequence.addDataBlock(block);
        }

        return new AMBTCFrequencyBandUpdateTDMA(sequence, 0x928, 1_000L);
    }

    private static CorrectedBinaryMessage header(int wacn, int system, int band, int channelType, int blocksToFollow)
    {
        CorrectedBinaryMessage headerBits = new CorrectedBinaryMessage(96);
        headerBits.set(2); //Outbound
        headerBits.setInt(23, IntField.range(3, 7)); //Alternate MBTC
        headerBits.setInt(band, IntField.length4(24));
        headerBits.setInt(channelType, IntField.length4(28));
        headerBits.setInt(wacn, WACN);
        headerBits.setInt(blocksToFollow, IntField.range(49, 55));
        headerBits.setInt(51, IntField.length6(58)); //IDEN_UPDATE_TDMA
        headerBits.setInt(system, IntField.length12(68));
        return headerBits;
    }
}
