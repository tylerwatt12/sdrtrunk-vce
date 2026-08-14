/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.phase2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.sample.complex.ComplexSamples;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class P25P2DecoderHDQPSKTest
{
    @Test
    void publishesFiniteSoftSymbolsAfterSampleRateReconfiguration()
    {
        P25P2DecoderHDQPSK decoder = new P25P2DecoderHDQPSK(new DecodeConfigP25Phase2(), 50_000);
        AtomicInteger softSymbolCount = new AtomicInteger();
        AtomicInteger hardSymbolCount = new AtomicInteger();
        AtomicBoolean invalidSoftSymbol = new AtomicBoolean();
        decoder.setSymbolListener(symbol ->
        {
            softSymbolCount.incrementAndGet();

            if(!Float.isFinite(symbol) || symbol < -Math.PI || symbol > Math.PI)
            {
                invalidSoftSymbol.set(true);
            }
        });
        decoder.getDibitBroadcaster().addListener(symbol -> hardSymbolCount.incrementAndGet());
        decoder.setSampleRate(48_000);

        float[] inphase = new float[4_096];
        float[] quadrature = new float[inphase.length];
        Arrays.fill(inphase, 1.0f);

        decoder.start();

        try
        {
            decoder.receive(new ComplexSamples(inphase, quadrature, 1_000L));
        }
        finally
        {
            decoder.stop();
        }

        assertTrue(softSymbolCount.get() > 0);
        assertFalse(invalidSoftSymbol.get());
        assertEquals(hardSymbolCount.get(), softSymbolCount.get());
    }
}
