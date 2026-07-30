/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.audio.playback;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import org.junit.jupiter.api.Test;

class AudioOutputTest
{
    @Test
    void unsupportedMasterGainIsOmittedWithoutRequestingControl()
    {
        AtomicBoolean controlRequested = new AtomicBoolean();
        SourceDataLine sourceDataLine = sourceDataLine(false, null, controlRequested);

        assertNull(AudioOutput.getMasterGainControl(sourceDataLine));
        assertFalse(controlRequested.get());
    }

    @Test
    void supportedMasterGainIsReturned()
    {
        AtomicBoolean controlRequested = new AtomicBoolean();
        FloatControl gainControl = new TestGainControl();
        SourceDataLine sourceDataLine = sourceDataLine(true, gainControl, controlRequested);

        assertSame(gainControl, AudioOutput.getMasterGainControl(sourceDataLine));
        assertTrue(controlRequested.get());
    }

    private static SourceDataLine sourceDataLine(boolean gainSupported, FloatControl gainControl,
                                                 AtomicBoolean controlRequested)
    {
        return (SourceDataLine)Proxy.newProxyInstance(AudioOutputTest.class.getClassLoader(),
            new Class<?>[]{SourceDataLine.class}, (proxy, method, args) ->
            {
                return switch(method.getName())
                {
                    case "isOpen" -> true;
                    case "isControlSupported" -> gainSupported;
                    case "getControl" ->
                    {
                        controlRequested.set(true);
                        yield gainControl;
                    }
                    default -> throw new UnsupportedOperationException("Unexpected method: " + method.getName());
                };
            });
    }

    private static class TestGainControl extends FloatControl
    {
        private TestGainControl()
        {
            super(Type.MASTER_GAIN, -80.0f, 6.0f, 0.1f, 1, 0.0f, "dB");
        }
    }
}
