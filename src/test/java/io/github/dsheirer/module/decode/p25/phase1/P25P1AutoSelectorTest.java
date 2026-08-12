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
    void locksThePreferredDecoderAfterDeterministicEvidence()
    {
        P25P1AutoSelector selector = new P25P1AutoSelector(1_000, Modulation.C4FM);

        assertFalse(selector.receiveMessage(Modulation.C4FM, true));
        assertTrue(selector.receiveMessage(Modulation.C4FM, true));
        assertTrue(selector.isLocked());
        assertEquals(Modulation.C4FM, selector.getActive());
        assertNull(selector.receiveSamples(9_999));
    }

    @Test
    void selectsTheAlternateDecoderWhenThePreferredDecoderHasNoValidMessages()
    {
        P25P1AutoSelector selector = new P25P1AutoSelector(1_000, Modulation.C4FM);

        assertEquals(Modulation.CQPSK, selector.receiveSamples(750));
        assertFalse(selector.receiveMessage(Modulation.CQPSK, true));
        assertTrue(selector.receiveMessage(Modulation.CQPSK, true));
        assertTrue(selector.isLocked());
        assertEquals(Modulation.CQPSK, selector.getActive());
    }

    @Test
    void hysteresisRequiresHoldAndLossBeforeTryingTheAlternateDecoder()
    {
        P25P1AutoSelector selector = new P25P1AutoSelector(1_000, Modulation.C4FM);
        selector.receiveMessage(Modulation.C4FM, true);
        selector.receiveMessage(Modulation.C4FM, true);

        assertNull(selector.receiveSamples(9_999));
        assertEquals(Modulation.CQPSK, selector.receiveSamples(1));
        assertFalse(selector.isLocked());

        assertEquals(Modulation.C4FM, selector.receiveSamples(750));
        assertTrue(selector.isLocked());
        assertEquals(Modulation.C4FM, selector.getActive());
    }

    @Test
    void noSignalRepeatsTheSamePreferredThenAlternateOrder()
    {
        P25P1AutoSelector selector = new P25P1AutoSelector(1_000, Modulation.CQPSK);

        assertEquals(Modulation.C4FM, selector.receiveSamples(750));
        assertEquals(Modulation.CQPSK, selector.receiveSamples(750));
        assertEquals(Modulation.C4FM, selector.receiveSamples(750));
        assertFalse(selector.isLocked());
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
