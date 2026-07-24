/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.sdrplay.rspDuo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ControlRspDuoTest
{
    @Test
    void appliesEitherIndependentGainChange()
    {
        assertTrue(ControlRspDuo.hasGainChanged(1, 30, 2, 30));
        assertTrue(ControlRspDuo.hasGainChanged(1, 30, 1, 31));
        assertTrue(ControlRspDuo.hasGainChanged(1, 30, 2, 31));
        assertFalse(ControlRspDuo.hasGainChanged(1, 30, 1, 30));
    }
}
