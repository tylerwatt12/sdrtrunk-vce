/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.vector.calibrate.window;

import io.github.dsheirer.dsp.window.ScalarWindow;
import io.github.dsheirer.dsp.window.VectorWindow;
import io.github.dsheirer.dsp.window.Window;
import io.github.dsheirer.dsp.window.WindowFactory;
import io.github.dsheirer.vector.calibrate.Calibration;
import io.github.dsheirer.vector.calibrate.CalibrationBenchmark;
import io.github.dsheirer.vector.calibrate.CalibrationException;
import io.github.dsheirer.vector.calibrate.CalibrationType;
import io.github.dsheirer.vector.calibrate.Implementation;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Calculates the optimal scalar or preferred-width vector implementation for repeatedly applying an FFT window. */
public class WindowCalibration extends Calibration
{
    private static final int WINDOW_SIZE = 8192;
    private static final Duration WARMUP_DURATION = Duration.ofMillis(250);
    private static final Duration TEST_DURATION = Duration.ofMillis(750);
    private static final int BENCHMARK_BATCH_SIZE = 16;

    public WindowCalibration()
    {
        super(CalibrationType.WINDOW);
    }

    @Override public void calibrate() throws CalibrationException
    {
        float[] samples = getFloatSamples(WINDOW_SIZE, "samples");
        verifyImplementations(samples);

        //A real window repeatedly applied to the same array quickly attenuates it to zero.  Unit-magnitude signs keep
        //the data live across iterations while exercising the identical multiplication, loads, stores and tail path.
        float[] benchmarkCoefficients = getFloatSamples(WINDOW_SIZE, "benchmark-coefficients");

        for(int x = 0; x < benchmarkCoefficients.length; x++)
        {
            benchmarkCoefficients[x] = benchmarkCoefficients[x] < 0.0f ? -1.0f : 1.0f;
        }

        Window scalar = new ScalarWindow(benchmarkCoefficients);
        Window vector = new VectorWindow(benchmarkCoefficients);
        measure(scalar, samples, WARMUP_DURATION);
        measure(vector, samples, WARMUP_DURATION);
        double scalarScore = measure(scalar, samples, TEST_DURATION);
        double vectorScore = measure(vector, samples, TEST_DURATION);

        mLog.info("WINDOW - SCALAR: {} buffers/second", DECIMAL_FORMAT.format(scalarScore));
        mLog.info("WINDOW - VECTOR: {} buffers/second", DECIMAL_FORMAT.format(vectorScore));

        if(scalarScore > vectorScore)
        {
            setImplementation(Implementation.SCALAR);
        }
        else
        {
            setImplementation(Implementation.VECTOR_SIMD_PREFERRED);
        }

        mLog.info("WINDOW - OPTIMAL IMPLEMENTATION SET TO: " + getImplementation());
    }

    private void verifyImplementations(float[] samples) throws CalibrationException
    {
        float[] coefficients = WindowFactory.getBlackman(WINDOW_SIZE);
        float[] expected = samples.clone();
        float[] actual = samples.clone();
        new ScalarWindow(coefficients).apply(expected);
        new VectorWindow(coefficients).apply(actual);
        CalibrationBenchmark.requireExact("Vector window", expected, actual);
    }

    private double measure(Window window, float[] samples, Duration duration)
    {
        return CalibrationBenchmark.measure(duration, BENCHMARK_BATCH_SIZE,
            new WindowOperation(window, samples)).operationsPerSecond();
    }

    private static class WindowOperation implements LongSupplier
    {
        private final Window mWindow;
        private final float[] mSamples;
        private int mObservationIndex;

        private WindowOperation(Window window, float[] samples)
        {
            mWindow = window;
            mSamples = samples.clone();
        }

        @Override public long getAsLong()
        {
            mWindow.apply(mSamples);
            CalibrationBenchmark.consume(mSamples);
            int index = mObservationIndex++;

            if(mObservationIndex >= mSamples.length)
            {
                mObservationIndex = 0;
            }

            return CalibrationBenchmark.fingerprint(mSamples[index]);
        }
    }
}
