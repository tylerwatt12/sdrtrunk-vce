/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StartupSequenceTest
{
    @Test
    void ordersEveryEligibleStep()
    {
        StartupSequence sequence = new StartupSequence(true, true, true, true);

        assertEquals(List.of(StartupStep.WHATS_NEW, StartupStep.CPU_CALIBRATION,
            StartupStep.ENCRYPTION_VAULT, StartupStep.AUTO_START_CHANNELS), sequence.getSteps());
        assertEquals(StartupStep.WHATS_NEW, sequence.start().orElseThrow());
        assertEquals(StartupStep.CPU_CALIBRATION, sequence.advance().orElseThrow());
        assertEquals(StartupStep.ENCRYPTION_VAULT, sequence.advance().orElseThrow());
        assertEquals(StartupStep.AUTO_START_CHANNELS, sequence.advance().orElseThrow());
        assertTrue(sequence.advance().isEmpty());
        assertTrue(sequence.isComplete());
    }

    @Test
    void skipsIneligibleStepsWithoutChangingOrder()
    {
        StartupSequence sequence = new StartupSequence(false, true, false, true);

        assertEquals(List.of(StartupStep.CPU_CALIBRATION, StartupStep.AUTO_START_CHANNELS), sequence.getSteps());
        assertEquals(StartupStep.CPU_CALIBRATION, sequence.start().orElseThrow());
        assertEquals(1, sequence.getCurrentNumber());
        assertEquals(StartupStep.AUTO_START_CHANNELS, sequence.advance().orElseThrow());
        assertEquals(2, sequence.getCurrentNumber());
    }

    @Test
    void emptySequenceDoesNotStart()
    {
        StartupSequence sequence = new StartupSequence(false, false, false, false);

        assertTrue(sequence.start().isEmpty());
        assertFalse(sequence.isComplete());
        assertEquals(0, sequence.size());
    }

    @Test
    void everyEligibilityCombinationPreservesCanonicalOrder()
    {
        StartupStep[] canonical = StartupStep.values();

        for(int mask = 0; mask < 16; mask++)
        {
            StartupSequence sequence = new StartupSequence((mask & 1) != 0, (mask & 2) != 0,
                (mask & 4) != 0, (mask & 8) != 0);
            List<StartupStep> expected = new ArrayList<>();

            for(int bit = 0; bit < canonical.length; bit++)
            {
                if((mask & (1 << bit)) != 0)
                {
                    expected.add(canonical[bit]);
                }
            }

            assertEquals(expected, sequence.getSteps());
        }
    }
}
