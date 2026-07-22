/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.startup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StartupCountdownTest
{
    @Test
    void expiresAtZeroAndNeverBecomesNegative()
    {
        StartupCountdown countdown = new StartupCountdown(2);
        assertEquals(2, countdown.getSecondsRemaining());
        assertFalse(countdown.tick());
        assertTrue(countdown.tick());
        assertTrue(countdown.tick());
        assertEquals(0, countdown.getSecondsRemaining());
    }

    @Test
    void resetsToTheInitialThreshold()
    {
        StartupCountdown countdown = new StartupCountdown(3);
        countdown.tick();
        countdown.tick();
        countdown.reset();
        assertEquals(3, countdown.getSecondsRemaining());
        assertFalse(countdown.isExpired());
    }

    @Test
    void treatsNegativeThresholdAsImmediatelyExpired()
    {
        StartupCountdown countdown = new StartupCountdown(-10);
        assertTrue(countdown.isExpired());
        assertEquals(0, countdown.getSecondsRemaining());
    }
}
