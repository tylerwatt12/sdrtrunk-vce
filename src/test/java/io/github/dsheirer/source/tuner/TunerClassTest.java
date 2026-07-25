/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.source.tuner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TunerClassTest
{
    @Test
    void discoversFuncubeUsbIdentifiersAsSupportedTuners()
    {
        TunerClass pro = TunerClass.lookup((short)0x04D8, (short)0xFB56);
        TunerClass proPlus = TunerClass.lookup((short)0x04D8, (short)0xFB31);

        assertEquals(TunerClass.FUNCUBE_DONGLE_PRO, pro);
        assertEquals(TunerClass.FUNCUBE_DONGLE_PRO_PLUS, proPlus);
        assertTrue(pro.isSupportedUsbTuner());
        assertTrue(proPlus.isSupportedUsbTuner());
        assertTrue(pro.isFuncubeTuner());
        assertTrue(proPlus.isFuncubeTuner());
    }

    @Test
    void keepsOtherUsbIdentifiersDiscoverable()
    {
        assertEquals(TunerClass.AIRSPY, TunerClass.lookup((short)0x1D50, (short)0x60A1));
        assertEquals(TunerClass.RTL2832, TunerClass.lookup((short)0x0BDA, (short)0x2838));
        assertTrue(TunerClass.AIRSPY.isSupportedUsbTuner());
        assertTrue(TunerClass.RTL2832.isSupportedUsbTuner());
    }
}
