/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.bits;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.dsp.symbol.ISyncDetectListener;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageFramerTest
{
    @Test
    void deliversSyncPrefixedMessageAndNotifiesSyncListener()
    {
        MessageFramer framer = new MessageFramer(new boolean[]{true, false, true}, 5);
        List<CorrectedBinaryMessage> messages = new ArrayList<>();
        List<Integer> syncErrors = new ArrayList<>();
        List<Integer> lostSyncCounts = new ArrayList<>();
        framer.addMessageListener(messages::add);
        framer.setSyncDetectListener(new ISyncDetectListener()
        {
            @Override public void syncDetected(int bitErrors) { syncErrors.add(bitErrors); }
            @Override public void syncLost(int bitsProcessed) { lostSyncCounts.add(bitsProcessed); }
        });

        try
        {
            for(boolean bit: new boolean[]{true, false, true, true, false, false})
            {
                framer.process(bit);
            }

            assertEquals(1, messages.size());
            assertArrayEquals(new boolean[]{true, false, true, true, false}, messages.getFirst().getBits(0, 4));
            assertEquals(List.of(0), syncErrors);
            assertTrue(lostSyncCounts.isEmpty());
        }
        finally
        {
            framer.dispose();
        }
    }
}
