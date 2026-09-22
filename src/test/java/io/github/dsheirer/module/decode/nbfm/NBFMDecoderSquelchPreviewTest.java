/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.nbfm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.dsp.squelch.NoiseSquelchState;
import org.junit.jupiter.api.Test;

class NBFMDecoderSquelchPreviewTest
{
    @Test
    void previewChangesOnlyRuntimeSquelchState()
    {
        DecodeConfigNBFM configuration = new DecodeConfigNBFM();
        float savedOpen = configuration.getSquelchNoiseOpenThreshold();
        float savedClose = configuration.getSquelchNoiseCloseThreshold();
        int savedOpenTiming = configuration.getSquelchHysteresisOpenThreshold();
        int savedCloseTiming = configuration.getSquelchHysteresisCloseThreshold();
        NBFMDecoder decoder = new NBFMDecoder(configuration);

        decoder.previewSquelch(0.14f, 0.24f, 3, 8);
        NoiseSquelchState preview = decoder.getNoiseSquelchState();

        assertEquals(0.14f, preview.noiseOpenThreshold());
        assertEquals(0.24f, preview.noiseCloseThreshold());
        assertEquals(3, preview.hysteresisOpenThreshold());
        assertEquals(8, preview.hysteresisCloseThreshold());
        assertEquals(savedOpen, configuration.getSquelchNoiseOpenThreshold());
        assertEquals(savedClose, configuration.getSquelchNoiseCloseThreshold());
        assertEquals(savedOpenTiming, configuration.getSquelchHysteresisOpenThreshold());
        assertEquals(savedCloseTiming, configuration.getSquelchHysteresisCloseThreshold());
    }
}
