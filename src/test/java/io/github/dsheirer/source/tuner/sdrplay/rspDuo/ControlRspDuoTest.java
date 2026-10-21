/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.sdrplay.rspDuo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ControlRspDuoTest
{
    @Test
    void masterStopsTheBridgedSlaveBeforeItsOwnDeviceReleaseGate() throws Exception
    {
        List<String> order = new ArrayList<>();
        IControlRspDuoTuner2 slaveControl = (IControlRspDuoTuner2)Proxy.newProxyInstance(
            IControlRspDuoTuner2.class.getClassLoader(), new Class<?>[]{IControlRspDuoTuner2.class},
            (proxy, method, arguments) -> null);
        MasterSlaveBridge bridge = new MasterSlaveBridge();
        bridge.setSlave(new RspDuoTuner2Controller(slaveControl, null)
        {
            @Override public void stop() { order.add("slave-stop"); }
        });
        ControlRspDuoTuner1Master master = new ControlRspDuoTuner1Master(null, bridge)
        {
            @Override public boolean hasDevice() { order.add("master-release-gate"); return false; }
        };
        master.stop();
        assertEquals(List.of("slave-stop", "master-release-gate"), order);
        master.stop();
        assertEquals(List.of("slave-stop", "master-release-gate", "master-release-gate"), order,
            "the stopped slave is retired from the bridge before another stop");
    }

    @Test
    void appliesEitherIndependentGainChange()
    {
        assertTrue(ControlRspDuo.hasGainChanged(1, 30, 2, 30));
        assertTrue(ControlRspDuo.hasGainChanged(1, 30, 1, 31));
        assertTrue(ControlRspDuo.hasGainChanged(1, 30, 2, 31));
        assertFalse(ControlRspDuo.hasGainChanged(1, 30, 1, 30));
    }
}
