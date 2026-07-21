/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    }

    private static AMBTCFrequencyBandUpdateTDMA message(int wacn, int system, int band, int channelType,
                                                         long base, long spacing, long offset) throws Exception
    {
        CorrectedBinaryMessage headerBits = new CorrectedBinaryMessage(96);
        headerBits.set(2); //Outbound
        headerBits.setInt(23, IntField.range(3, 7)); //Alternate MBTC
        headerBits.setInt(band, IntField.length4(24));
        headerBits.setInt(channelType, IntField.length4(28));
        headerBits.setInt(wacn, WACN);
        headerBits.setInt(1, IntField.range(49, 55)); //One block follows
        headerBits.setInt(51, IntField.length6(58)); //IDEN_UPDATE_TDMA
        headerBits.setInt(system, IntField.length12(68));

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

        PDUSequence sequence = new PDUSequence(new AMBTCHeader(headerBits, true), 1_000L, 0x928);
        sequence.addDataBlock(block);
        return new AMBTCFrequencyBandUpdateTDMA(sequence, 0x928, 1_000L);
    }
}
