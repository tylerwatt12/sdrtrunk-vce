/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.vector.calibrate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CalibrationTypeTest
{
    @Test
    void implementationWhitelistMatchesMeasuredFactoryCandidates()
    {
        EnumMap<CalibrationType, EnumSet<Implementation>> expectedVectors = new EnumMap<>(CalibrationType.class);

        register(expectedVectors, vectors(Implementation.VECTOR_SIMD_PREFERRED),
            CalibrationType.OSCILLATOR_COMPLEX, CalibrationType.GAIN_COMPLEX, CalibrationType.MIXER_COMPLEX,
            CalibrationType.OSCILLATOR_REAL, CalibrationType.SAMPLE_UNPACKED_BYTE_CONVERTER,
            CalibrationType.WINDOW);
        register(expectedVectors, vectors(Implementation.VECTOR_SIMD_PREFERRED, Implementation.VECTOR_SIMD_64,
                Implementation.VECTOR_SIMD_128, Implementation.VECTOR_SIMD_256),
            CalibrationType.AM_DEMODULATOR, CalibrationType.RSP_SAMPLE_CONVERTER,
            CalibrationType.FILTER_FIR_PULSE_SHAPING);
        register(expectedVectors, vectors(Implementation.VECTOR_SIMD_64, Implementation.VECTOR_SIMD_128,
                Implementation.VECTOR_SIMD_256, Implementation.VECTOR_SIMD_512),
            CalibrationType.POLYPHASE_CHANNELIZER_FILTER, CalibrationType.DMR_SOFT_SYNC_DETECTOR,
            CalibrationType.DIFFERENTIAL_DEMODULATOR, CalibrationType.FILTER_HALF_BAND_REAL_11_TAP,
            CalibrationType.FILTER_HALF_BAND_REAL_15_TAP, CalibrationType.FILTER_HALF_BAND_REAL_23_TAP,
            CalibrationType.FILTER_HALF_BAND_REAL_63_TAP, CalibrationType.FM_DEMODULATOR,
            CalibrationType.NXDN_SOFT_SYNC_DETECTOR, CalibrationType.P25P1_SOFT_SYNC_DETECTOR,
            CalibrationType.SAMPLE_UNPACKED_INTERLEAVED_ITERATOR, CalibrationType.SAMPLE_UNPACKED_ITERATOR);
        register(expectedVectors, vectors(Implementation.VECTOR_SIMD_PREFERRED, Implementation.VECTOR_SIMD_64,
                Implementation.VECTOR_SIMD_128, Implementation.VECTOR_SIMD_256, Implementation.VECTOR_SIMD_512),
            CalibrationType.FILTER_FIR);
        register(expectedVectors, vectors(Implementation.VECTOR_SIMD_PREFERRED, Implementation.VECTOR_SIMD_64,
                Implementation.VECTOR_SIMD_128, Implementation.VECTOR_SIMD_256, Implementation.VECTOR_SIMD_512),
            CalibrationType.FILTER_HALF_BAND_REAL_DEFAULT);
        register(expectedVectors, vectors(Implementation.VECTOR_SIMD_64, Implementation.VECTOR_SIMD_128,
                Implementation.VECTOR_SIMD_256), CalibrationType.INTERPOLATOR);

        assertEquals(EnumSet.allOf(CalibrationType.class), expectedVectors.keySet(),
            "Every calibration type needs an explicit measured/factory implementation whitelist");

        for(Map.Entry<CalibrationType, EnumSet<Implementation>> entry: expectedVectors.entrySet())
        {
            for(Implementation implementation: Implementation.values())
            {
                boolean expected = implementation == Implementation.SCALAR ||
                    implementation == Implementation.UNCALIBRATED || entry.getValue().contains(implementation);
                assertEquals(expected, entry.getKey().supportsImplementation(implementation),
                    entry.getKey() + " unexpected support for " + implementation);
            }
        }
    }

    private static EnumSet<Implementation> vectors(Implementation first, Implementation... remaining)
    {
        return EnumSet.of(first, remaining);
    }

    private static void register(EnumMap<CalibrationType, EnumSet<Implementation>> whitelists,
                                 EnumSet<Implementation> implementations, CalibrationType... types)
    {
        for(CalibrationType type: types)
        {
            whitelists.put(type, implementations.clone());
        }
    }
}
