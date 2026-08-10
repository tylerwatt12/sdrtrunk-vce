/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.sdrplay.rsp1;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ControlRsp1Test
{
    @Test
    void limitsLnaToFourHardwareStates()
    {
        assertEquals(3, new ControlRsp1(null).getMaximumLNASetting());
    }
}
