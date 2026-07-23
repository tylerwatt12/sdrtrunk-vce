/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.nxdn.layer1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.module.decode.nxdn.layer2.LICH;
import io.github.dsheirer.module.decode.nxdn.layer2.SACCHFragment;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrameQualityTest
{
    @Test
    void assignsOneAggregateCarrierForMultiMessageTypeDFrame()
    {
        SACCHFragment scch = message(1_000L, LICH.RTCH_2_OUTBOUND_SINGLE_FACCH1_FACCH1, false, 2);
        SACCHFragment facch = message(1_000L, LICH.RTCH_2_OUTBOUND_SINGLE_FACCH1_FACCH1, true, 3);

        Frame.assignRfFrameQuality(List.of(scch, facch));

        assertTrue(scch.isRfFrameQualityCarrier());
        assertFalse(facch.isRfFrameQualityCarrier());
        assertTrue(scch.isRfFrameValid());
        assertEquals(5, scch.getRfFrameCorrectedBitCount());
    }

    @Test
    void assignsSingleRcchMessageAsFrameCarrier()
    {
        SACCHFragment rcch = message(2_000L, LICH.RCCH_OUTBOUND_SINGLE_CAC_NORMAL, false, -1);

        Frame.assignRfFrameQuality(List.of(rcch));

        assertTrue(rcch.isRfFrameQualityCarrier());
        assertFalse(rcch.isRfFrameValid());
        assertEquals(0, rcch.getRfFrameCorrectedBitCount());
    }

    private static SACCHFragment message(long timestamp, LICH lich, boolean valid, int correctedBits)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(26);
        bits.setCorrectedBitCount(correctedBits);
        SACCHFragment message = new SACCHFragment(bits, timestamp, lich);
        message.setValid(valid);
        return message;
    }
}
