/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.module.decode.p25.phase1;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.DroppedSamplesMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.P25P1Message;
import io.github.dsheirer.protocol.Protocol;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class P25P1AutoSelectorTest
{
    @Test
    void testsBothDecodersBeforeSelectingThePreferredDecoder()
    {
        P25P1AutoSelector selector = new P25P1AutoSelector(1_000, Modulation.C4FM);

        assertFalse(selector.receiveFrame(Modulation.C4FM, true));
        assertFalse(selector.receiveFrame(Modulation.C4FM, true));
        assertEquals(Modulation.CQPSK, selector.receiveSamples(500));
        assertFalse(selector.isLocked(), "valid preferred evidence must not skip the LSM trial");
        assertFalse(selector.receiveFrame(Modulation.CQPSK, true));
        assertEquals(Modulation.C4FM, selector.receiveSamples(500));
        assertTrue(selector.isLocked());
        assertEquals(Modulation.C4FM, selector.getActive());
    }

    @Test
    void subTrialFrequencyDwellsCannotCompleteAcquisition()
    {
        P25P1AutoSelector selector = new P25P1AutoSelector(1_000, Modulation.C4FM);

        for(int frequency = 0; frequency < 4; frequency++)
        {
            selector.receiveFrame(Modulation.C4FM, true);
            assertNull(selector.receiveSamples(400));
            selector.reset(Modulation.C4FM);
        }

        assertFalse(selector.isLocked());
        selector.receiveFrame(Modulation.C4FM, true);
        assertEquals(Modulation.CQPSK, selector.receiveSamples(500));
        selector.receiveFrame(Modulation.CQPSK, true);
        selector.receiveSamples(500);
        assertTrue(selector.isLocked());
    }

    @Test
    void selectsTheBetterAlternateEvenWhenThePreferredDecoderIsValid()
    {
        P25P1AutoSelector selector = new P25P1AutoSelector(1_000, Modulation.C4FM);

        selector.receiveFrame(Modulation.C4FM, true);
        selector.receiveFrame(Modulation.C4FM, true);
        for(int count = 0; count < 4; count++)
        {
            selector.receiveFrame(Modulation.C4FM, false);
        }

        assertEquals(Modulation.CQPSK, selector.receiveSamples(500));
        for(int count = 0; count < 4; count++)
        {
            selector.receiveFrame(Modulation.CQPSK, true);
        }

        assertNull(selector.receiveSamples(500));
        assertTrue(selector.isLocked());
        assertEquals(Modulation.CQPSK, selector.getActive());
    }

    @Test
    void syncLossContributesToTheDeterministicQualityComparison()
    {
        P25P1AutoSelector selector = new P25P1AutoSelector(1_000, Modulation.C4FM);

        for(int count = 0; count < 4; count++) selector.receiveFrame(Modulation.C4FM, true);
        selector.receiveSyncLoss(Modulation.C4FM, 1_960);
        assertEquals(Modulation.CQPSK, selector.receiveSamples(500));
        for(int count = 0; count < 3; count++) selector.receiveFrame(Modulation.CQPSK, true);

        assertNull(selector.receiveSamples(500));
        assertTrue(selector.isLocked());
        assertEquals(Modulation.CQPSK, selector.getActive());
    }

    @Test
    void lockedSelectionSurvivesSustainedSyncLoss()
    {
        P25P1AutoSelector selector = lockedC4fmSelector();

        selector.receiveSyncLoss(Modulation.C4FM, 96_000);
        assertNull(selector.receiveSamples(60_000));
        assertTrue(selector.isLocked());
        assertEquals(Modulation.C4FM, selector.getActive());
    }

    @Test
    void resetDoesNotReopenAcquisitionAfterSelection()
    {
        P25P1AutoSelector selector = lockedC4fmSelector();

        selector.reset(Modulation.CQPSK);
        assertNull(selector.receiveSamples(60_000));
        assertTrue(selector.isLocked());
        assertEquals(Modulation.C4FM, selector.getActive());
    }

    @Test
    void noSignalRepeatsTheSamePreferredThenAlternateOrder()
    {
        P25P1AutoSelector selector = new P25P1AutoSelector(1_000, Modulation.CQPSK);

        assertEquals(Modulation.C4FM, selector.receiveSamples(500));
        assertEquals(Modulation.CQPSK, selector.receiveSamples(500));
        assertEquals(Modulation.C4FM, selector.receiveSamples(500));
        assertFalse(selector.isLocked());
    }

    private static P25P1AutoSelector lockedC4fmSelector()
    {
        P25P1AutoSelector selector = new P25P1AutoSelector(1_000, Modulation.C4FM);
        for(int count = 0; count < 4; count++) selector.receiveFrame(Modulation.C4FM, true);
        selector.receiveSamples(500);
        selector.receiveFrame(Modulation.CQPSK, true);
        selector.receiveSamples(500);
        assertTrue(selector.isLocked());
        assertEquals(Modulation.C4FM, selector.getActive());
        return selector;
    }

    @Test
    void onlyValidDecodedP25FramesAreSelectionEvidence()
    {
        assertFalse(P25P1DecoderAuto.isSelectionEvidence(new SyncLossMessage(1, 9_600, Protocol.APCO25)));
        assertFalse(P25P1DecoderAuto.isSelectionEvidence(new DroppedSamplesMessage(1, 100, Protocol.APCO25)));

        P25P1Message decoded = new P25P1Message(0x123, 1)
        {
            @Override
            public P25P1DataUnitID getDUID()
            {
                return P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1;
            }

            @Override
            public List<Identifier> getIdentifiers()
            {
                return Collections.emptyList();
            }
        };

        assertTrue(P25P1DecoderAuto.isSelectionEvidence(decoded));
        decoded.setValid(false);
        assertFalse(P25P1DecoderAuto.isSelectionEvidence(decoded));
    }
}
