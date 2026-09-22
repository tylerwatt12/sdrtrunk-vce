/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.analog.DecodeConfigAnalog.Bandwidth;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import org.junit.jupiter.api.Test;

class FrequencyListenServiceTest
{
    @Test
    void selectsOnlySupportedNbfmBandwidths()
    {
        assertEquals(Bandwidth.BW_6_25, FrequencyListenService.bandwidthFor(6250));
        assertEquals(Bandwidth.BW_12_5, FrequencyListenService.bandwidthFor(12500));
        assertEquals(Bandwidth.BW_20_0, FrequencyListenService.bandwidthFor(20000));
        assertEquals(Bandwidth.BW_25_0, FrequencyListenService.bandwidthFor(25000));
        assertThrows(IllegalArgumentException.class, () -> FrequencyListenService.bandwidthFor(7500));

        for(int bandwidth: new int[]{6250, 12500, 20000, 25000})
        {
            DecodeConfigNBFM config = new DecodeConfigNBFM();
            config.setBandwidth(FrequencyListenService.bandwidthFor(bandwidth));
            assertEquals(bandwidth, config.getChannelSpecification().getBandwidth());
        }
    }

    @Test
    void rejectsOutsideWindowAndDcExclusionWithoutMovingCenter()
    {
        long center = 770_000_000;
        assertTrue(FrequencyListenService.withinCurrentWindow(center + 100_000, center, 12500,
            500_000, 10_000));
        assertFalse(FrequencyListenService.withinCurrentWindow(center + 498_000, center, 12500,
            500_000, 10_000));
        assertFalse(FrequencyListenService.withinCurrentWindow(center + 12_000, center, 12500,
            500_000, 10_000));
        assertFalse(FrequencyListenService.withinCurrentWindow(0, center, 12500, 500_000, 0));
    }
}
