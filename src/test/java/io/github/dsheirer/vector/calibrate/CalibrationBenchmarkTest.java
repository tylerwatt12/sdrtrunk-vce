/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.vector.calibrate;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.function.LongSupplier;
import org.junit.jupiter.api.Test;

class CalibrationBenchmarkTest
{
    @Test
    void measuresCompleteBatchesWithMonotonicNanosecondClock()
    {
        SequenceClock clock = new SequenceClock(100, 110, 120, 130);
        long[] invocations = new long[1];
        long sinkBefore = CalibrationBenchmark.observableSink();
        CalibrationBenchmark.Result result = CalibrationBenchmark.measure(25, 4, () -> ++invocations[0], clock);

        assertEquals(12, result.operationCount());
        assertEquals(12, invocations[0]);
        assertEquals(30, result.elapsedNanos());
        assertEquals(400_000_000.0d, result.operationsPerSecond());
        assertNotEquals(sinkBefore, CalibrationBenchmark.observableSink());
    }

    @Test
    void rejectsInvalidMeasurements()
    {
        LongSupplier operation = () -> 1;
        LongSupplier clock = () -> 0;
        assertThrows(IllegalArgumentException.class, () -> CalibrationBenchmark.measure(0, 1, operation, clock));
        assertThrows(IllegalArgumentException.class, () -> CalibrationBenchmark.measure(1, 0, operation, clock));
        assertThrows(NullPointerException.class, () -> CalibrationBenchmark.measure(1, 1, null, clock));
        assertThrows(NullPointerException.class, () -> CalibrationBenchmark.measure(1, 1, operation, null));
    }

    @Test
    void validatesExactAndTolerantCandidateOutput() throws Exception
    {
        float[] expected = {1.0f, -2.0f, 0.0f, Float.POSITIVE_INFINITY};
        CalibrationBenchmark.requireExact("exact", expected, expected.clone());

        float[] close = {1.00001f, -2.00001f, -0.0f, Float.POSITIVE_INFINITY};
        CalibrationBenchmark.requireEquivalent("close", expected, close, 0.00002f, 0.00002f);

        CalibrationException exactMismatch = assertThrows(CalibrationException.class,
            () -> CalibrationBenchmark.requireExact("candidate", expected, close));
        assertTrue(exactMismatch.getMessage().contains("candidate differs at index 0"));

        CalibrationException toleranceMismatch = assertThrows(CalibrationException.class,
            () -> CalibrationBenchmark.requireEquivalent("candidate", expected,
                new float[]{1.1f, -2.0f, 0.0f, Float.POSITIVE_INFINITY}, 0.001f, 0.001f));
        assertTrue(toleranceMismatch.getMessage().contains("index 0"));

        CalibrationException lengthMismatch = assertThrows(CalibrationException.class,
            () -> CalibrationBenchmark.requireExact("candidate", expected, new float[2]));
        assertTrue(lengthMismatch.getMessage().contains("produced 2 values; expected 4"));
        assertThrows(IllegalArgumentException.class,
            () -> CalibrationBenchmark.requireEquivalent("candidate", expected, close, -1.0f, 0.0f));
    }

    @Test
    void namedFixturesAreRepeatableAndIndependent()
    {
        TestCalibration first = new TestCalibration();
        TestCalibration second = new TestCalibration();
        float[] firstI = first.floatFixture(32, "in-phase");

        assertArrayEquals(firstI, first.floatFixture(32, "in-phase"));
        assertArrayEquals(firstI, second.floatFixture(32, "in-phase"));
        assertFalse(Arrays.equals(firstI, first.floatFixture(32, "quadrature")));

        float[] positive = first.positiveFixture(32, "positive");
        assertTrue(Arrays.stream(toDoubleArray(positive)).allMatch(value -> value >= 0.0d && value < 1.0d));
        assertArrayEquals(first.shortFixture(32, "short"), second.shortFixture(32, "short"));
    }

    @Test
    void fingerprintsDependOnEveryArrayValue()
    {
        float[] first = {1.0f, 2.0f, 3.0f};
        float[] second = first.clone();
        assertEquals(CalibrationBenchmark.fingerprint(first), CalibrationBenchmark.fingerprint(second));
        second[2] = 4.0f;
        assertNotEquals(CalibrationBenchmark.fingerprint(first), CalibrationBenchmark.fingerprint(second));
    }

    @Test
    void objectResultsCanBePublishedToPreventArrayStoreElimination()
    {
        float[] result = {1.0f, 2.0f};
        CalibrationBenchmark.consume(result);
        assertEquals(result, CalibrationBenchmark.observableObjectSink());
        assertThrows(NullPointerException.class, () -> CalibrationBenchmark.consume((Object)null));
    }

    private static double[] toDoubleArray(float[] values)
    {
        double[] converted = new double[values.length];

        for(int x = 0; x < values.length; x++)
        {
            converted[x] = values[x];
        }

        return converted;
    }

    private static class SequenceClock implements LongSupplier
    {
        private final long[] mValues;
        private int mPointer;

        private SequenceClock(long... values)
        {
            mValues = values;
        }

        @Override public long getAsLong()
        {
            return mValues[mPointer++];
        }
    }

    private static class TestCalibration extends Calibration
    {
        private TestCalibration()
        {
            super(CalibrationType.GAIN_COMPLEX);
        }

        @Override public void calibrate()
        {
        }

        private float[] floatFixture(int size, String name)
        {
            return getFloatSamples(size, name);
        }

        private float[] positiveFixture(int size, String name)
        {
            return getPositiveFloatSamples(size, name);
        }

        private short[] shortFixture(int size, String name)
        {
            return getShortSamples(size, name);
        }
    }
}
