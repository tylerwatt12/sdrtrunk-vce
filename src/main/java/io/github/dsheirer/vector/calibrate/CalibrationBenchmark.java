/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.vector.calibrate;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Shared support for lightweight startup calibration benchmarks.  This is intentionally smaller than a full JMH
 * harness, but provides the properties needed by application startup calibration: monotonic nanosecond timing,
 * batched clock checks, and an observable checksum that prevents the measured result from becoming dead code.
 *
 * Each operation must return a fingerprint derived from its output.  The benchmark combines every returned value and
 * publishes the final checksum through a volatile sink after the measurement.  Candidate implementations should use
 * the same deterministic source fixture and should be checked for correctness before they are timed.
 */
public final class CalibrationBenchmark
{
    private static final long CHECKSUM_SEED = 0x9E3779B97F4A7C15L;
    private static volatile long sObservableSink;
    private static volatile Object sObservableObjectSink;

    private CalibrationBenchmark()
    {
    }

    /**
     * Measures an operation for at least the requested duration.  The clock is checked once per batch instead of once
     * per operation, reducing timing overhead for small DSP operations.
     *
     * @param minimumDuration minimum measurement duration
     * @param batchSize number of operations between clock checks
     * @param operation operation that returns a fingerprint derived from its output
     * @return measurement result
     */
    public static Result measure(Duration minimumDuration, int batchSize, LongSupplier operation)
    {
        Objects.requireNonNull(minimumDuration, "Minimum duration cannot be null");
        return measure(minimumDuration.toNanos(), batchSize, operation, System::nanoTime);
    }

    /**
     * Internal measurement entry point with an injectable monotonic clock for deterministic tests.
     */
    static Result measure(long minimumDurationNanos, int batchSize, LongSupplier operation, LongSupplier nanoTime)
    {
        if(minimumDurationNanos <= 0)
        {
            throw new IllegalArgumentException("Minimum duration must be greater than zero");
        }

        if(batchSize <= 0)
        {
            throw new IllegalArgumentException("Batch size must be greater than zero");
        }

        Objects.requireNonNull(operation, "Benchmark operation cannot be null");
        Objects.requireNonNull(nanoTime, "Clock cannot be null");

        long checksum = CHECKSUM_SEED;
        long operationCount = 0;
        long start = nanoTime.getAsLong();
        long now;

        do
        {
            for(int x = 0; x < batchSize; x++)
            {
                checksum = combine(checksum, operation.getAsLong());
                operationCount++;
            }

            now = nanoTime.getAsLong();
        }
        while(now - start < minimumDurationNanos);

        long elapsedNanos = Math.max(1, now - start);
        consume(checksum);
        return new Result(operationCount, elapsedNanos, checksum);
    }

    /**
     * Creates a fingerprint for a floating point value without changing its bit representation.
     */
    public static long fingerprint(float value)
    {
        return Integer.toUnsignedLong(Float.floatToRawIntBits(value));
    }

    /**
     * Creates a fingerprint for every value in an array.  This is useful for correctness tests and for benchmark
     * operations whose complete output must be made observable.  For very small operations, callers can instead
     * fingerprint rotating output elements so that checksum work does not dominate the measurement.
     */
    public static long fingerprint(float[] values)
    {
        Objects.requireNonNull(values, "Values cannot be null");
        long fingerprint = CHECKSUM_SEED;

        for(float value: values)
        {
            fingerprint = combine(fingerprint, fingerprint(value));
        }

        return fingerprint;
    }

    /**
     * Combines two fingerprints using a stable avalanche function.
     */
    public static long combine(long first, long second)
    {
        long mixed = first ^ Long.rotateLeft(second + CHECKSUM_SEED, 27);
        mixed *= 0x94D049BB133111EBL;
        return mixed ^ (mixed >>> 31);
    }

    /**
     * Publishes a result through a volatile field so that the JVM cannot treat output-dependent benchmark work as
     * unobservable dead code.
     */
    public static void consume(long value)
    {
        sObservableSink = combine(sObservableSink, value);
    }

    /**
     * Publishes an allocated result so escape analysis cannot discard unobserved array elements or their stores.
     * Callers should still return a lightweight fingerprint from the benchmark operation.
     */
    public static void consume(Object value)
    {
        sObservableObjectSink = Objects.requireNonNull(value, "Consumed value cannot be null");
    }

    /**
     * Requires bit-for-bit identical floating point arrays.
     *
     * @throws CalibrationException when the arrays differ
     */
    public static void requireExact(String candidate, float[] expected, float[] actual) throws CalibrationException
    {
        requireSameLength(candidate, expected, actual);

        for(int x = 0; x < expected.length; x++)
        {
            if(Float.floatToRawIntBits(expected[x]) != Float.floatToRawIntBits(actual[x]))
            {
                throw mismatch(candidate, x, expected[x], actual[x], 0.0f);
            }
        }
    }

    /**
     * Requires equivalent floating point arrays using the larger of the absolute tolerance and the relative
     * tolerance scaled by the compared values.  Exactly matching infinities and NaN bit patterns are accepted;
     * otherwise non-finite values fail the comparison.
     *
     * @throws CalibrationException when the arrays differ
     */
    public static void requireEquivalent(String candidate, float[] expected, float[] actual, float absoluteTolerance,
                                         float relativeTolerance) throws CalibrationException
    {
        validateTolerance(absoluteTolerance, "Absolute");
        validateTolerance(relativeTolerance, "Relative");
        requireSameLength(candidate, expected, actual);

        for(int x = 0; x < expected.length; x++)
        {
            float expectedValue = expected[x];
            float actualValue = actual[x];

            if(Float.floatToRawIntBits(expectedValue) == Float.floatToRawIntBits(actualValue))
            {
                continue;
            }

            float difference = Math.abs(expectedValue - actualValue);
            float relativeLimit = relativeTolerance * Math.max(Math.abs(expectedValue), Math.abs(actualValue));
            float allowedDifference = Math.max(absoluteTolerance, relativeLimit);

            if(!Float.isFinite(difference) || difference > allowedDifference)
            {
                throw mismatch(candidate, x, expectedValue, actualValue, allowedDifference);
            }
        }
    }

    private static void requireSameLength(String candidate, float[] expected, float[] actual)
        throws CalibrationException
    {
        Objects.requireNonNull(candidate, "Candidate name cannot be null");
        Objects.requireNonNull(expected, "Expected values cannot be null");
        Objects.requireNonNull(actual, "Actual values cannot be null");

        if(expected.length != actual.length)
        {
            throw new CalibrationException(candidate + " produced " + actual.length + " values; expected " +
                expected.length);
        }
    }

    private static void validateTolerance(float tolerance, String name)
    {
        if(!Float.isFinite(tolerance) || tolerance < 0.0f)
        {
            throw new IllegalArgumentException(name + " tolerance must be finite and non-negative");
        }
    }

    private static CalibrationException mismatch(String candidate, int index, float expected, float actual,
                                                  float allowedDifference)
    {
        return new CalibrationException(candidate + " differs at index " + index + ": expected " + expected +
            ", actual " + actual + ", allowed difference " + allowedDifference);
    }

    static long observableSink()
    {
        return sObservableSink;
    }

    static Object observableObjectSink()
    {
        return sObservableObjectSink;
    }

    /**
     * Result from one timed measurement.
     *
     * @param operationCount number of completed operations
     * @param elapsedNanos measured elapsed time
     * @param checksum fingerprint accumulated from every operation result
     */
    public record Result(long operationCount, long elapsedNanos, long checksum)
    {
        /**
         * Normalized operations completed per second.
         */
        public double operationsPerSecond()
        {
            return operationCount * 1_000_000_000.0d / elapsedNanos;
        }
    }
}
