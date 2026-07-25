/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.source.tuner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.gui.preference.tuner.RspDuoSelectionMode;
import io.github.dsheirer.source.tuner.manager.ChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.PolyphaseChannelSourceManager;
import io.github.dsheirer.source.tuner.sdrplay.RspTuner;
import io.github.dsheirer.source.tuner.sdrplay.RspTunerController;
import io.github.dsheirer.source.tuner.sdrplay.api.device.DeviceInfo;
import io.github.dsheirer.source.tuner.sdrplay.rspDuo.MasterSlaveBridge;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class PolyphaseTunerConstructionTest
{
    @Test
    void usbFactoryAlwaysConstructsPolyphaseTuners() throws Exception
    {
        ITunerErrorListener errorListener = new LoggingTunerErrorListener();

        for(TunerClass tunerClass: TunerClass.SUPPORTED_USB_TUNERS)
        {
            Tuner tuner = TunerFactory.getUsbTuner(tunerClass, "1", 1, errorListener);

            try
            {
                assertInstanceOf(PolyphaseChannelSourceManager.class, tuner.getChannelSourceManager(),
                    tunerClass + " must use the polyphase channelizer");
            }
            finally
            {
                tuner.getChannelSourceManager().dispose();
            }
        }
    }

    @Test
    void tunerFactoryAndConstructorsHaveNoChannelizerSelectionParameter() throws Exception
    {
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("io.github.dsheirer.preference.source.ChannelizerType"));
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("io.github.dsheirer.source.tuner.manager.HeterodyneChannelSourceManager"));

        assertMethodParameterCount("getUsbTuner", 4);
        assertMethodParameterCount("getRspTuners", 2);
        assertMethodParameterCount("getRspTuner", 2);
        assertMethodParameterCount("getRspDuoTuner", 3);

        Constructor<RspTuner> rspConstructor =
            RspTuner.class.getConstructor(RspTunerController.class, ITunerErrorListener.class);
        assertEquals(2, rspConstructor.getParameterCount());

        boolean hasManagerConstructor = Arrays.stream(Tuner.class.getDeclaredConstructors())
            .map(Constructor::getParameterTypes)
            .anyMatch(parameters -> parameters.length == 3 &&
                parameters[2] == ChannelSourceManager.class);
        assertTrue(hasManagerConstructor,
            "The tuner base constructor must receive a completed manager instead of selecting one");

        TunerFactory.class.getMethod("getRspTuners", DeviceInfo.class, RspDuoSelectionMode.class);
        TunerFactory.class.getMethod("getRspTuner", DeviceInfo.class, ITunerErrorListener.class);
        TunerFactory.class.getMethod("getRspDuoTuner", DeviceInfo.class, ITunerErrorListener.class,
            MasterSlaveBridge.class);
    }

    private static void assertMethodParameterCount(String methodName, int expectedCount)
    {
        Method[] matchingMethods = Arrays.stream(TunerFactory.class.getDeclaredMethods())
            .filter(method -> method.getName().equals(methodName))
            .toArray(Method[]::new);

        assertEquals(1, matchingMethods.length, methodName + " must have one unambiguous construction path");
        assertEquals(expectedCount, matchingMethods[0].getParameterCount(),
            methodName + " must not accept a channelizer selection");
    }
}
