/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase2;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.dsp.symbol.Dibit;
import io.github.dsheirer.module.decode.p25.phase2.message.SuperFrameFragment;
import io.github.dsheirer.module.decode.p25.phase2.timeslot.ScramblingSequence;
import org.junit.jupiter.api.Test;

class P25P2SuperFrameDetectorTimestampTest
{
    @Test
    void advancesCarrierTimestampAtSixThousandDibitsPerSecond()
    {
        P25P2SuperFrameDetector detector = new P25P2SuperFrameDetector(null);
        detector.setTimestamp(1_000_000L);

        for(int x = 0; x < 600; x++)
        {
            detector.receive(Dibit.D00_PLUS_1);
        }

        assertEquals(1_000_100L, detector.getCurrentTimestamp());

        detector.setTimestamp(2_000_000L);
        assertEquals(2_000_000L, detector.getCurrentTimestamp(),
            "Each incoming sample buffer must reset the dibit offset against its own carrier timestamp");
    }

    @Test
    void assignsEachThirtyMillisecondRadioSlotItsOwnCarrierTimestamp()
    {
        SuperFrameFragment fragment = new SuperFrameFragment(new CorrectedBinaryMessage(1_440), 10_000L,
            new ScramblingSequence());

        assertEquals(9_910L, fragment.getTimeslotA().getTimestamp());
        assertEquals(9_940L, fragment.getTimeslotB().getTimestamp());
        assertEquals(9_970L, fragment.getTimeslotC().getTimestamp());
        assertEquals(10_000L, fragment.getTimeslotD().getTimestamp());
    }
}
