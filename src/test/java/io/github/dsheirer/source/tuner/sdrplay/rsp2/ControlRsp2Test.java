/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.sdrplay.rsp2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.source.tuner.sdrplay.api.SDRPlayException;
import io.github.dsheirer.source.tuner.sdrplay.api.Status;
import io.github.dsheirer.source.tuner.sdrplay.api.parameter.tuner.Rsp2AntennaSelection;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ControlRsp2Test
{
    @Test
    void publishesAntennaSelectionOnlyAfterBothDeviceWritesSucceed() throws Exception
    {
        AtomicInteger writes = new AtomicInteger();
        Rsp2AntennaSelection selected = ControlRsp2.applyAntennaSelection(Rsp2AntennaSelection.ANT_B,
            ignored -> writes.incrementAndGet());
        assertEquals(Rsp2AntennaSelection.ANT_B, selected);
        assertEquals(1, writes.get());

        assertThrows(SDRPlayException.class,
            () -> ControlRsp2.applyAntennaSelection(Rsp2AntennaSelection.ANT_A,
                ignored -> { throw new SDRPlayException("synthetic failure", Status.FAIL); }));
    }
}
