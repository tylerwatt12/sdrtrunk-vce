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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.ToDoubleFunction;

/**
 * Selection helpers for calibrations whose vector implementation can be slower than HotSpot's optimized scalar loop.
 */
public final class CalibrationSelector
{
    /** A small apparent win is not enough to survive normal startup load and measurement variation. */
    public static final double MINIMUM_VECTOR_SPEEDUP = 1.05d;
    /** Four trials give each candidate two measurements in each forward/reverse ordering position. */
    public static final int BENCHMARK_TRIAL_COUNT = 4;

    private CalibrationSelector()
    {
    }

    /**
     * Calculates the median of repeated benchmark scores.
     *
     * @param scores positive, finite throughput scores
     * @return median score
     */
    public static double median(double[] scores)
    {
        if(scores == null || scores.length == 0)
        {
            throw new IllegalArgumentException("At least one calibration score is required");
        }

        double[] sorted = scores.clone();

        for(double score: sorted)
        {
            if(!Double.isFinite(score) || score <= 0.0d)
            {
                throw new IllegalArgumentException("Calibration scores must be finite and greater than zero");
            }
        }

        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return (sorted.length & 1) == 0 ? (sorted[middle - 1] + sorted[middle]) / 2.0d : sorted[middle];
    }

    /**
     * Indicates whether a vector score is large enough to be considered a repeatable win over scalar.
     */
    public static boolean isReliableVectorWin(double scalarScore, double vectorScore)
    {
        return Double.isFinite(scalarScore) && scalarScore > 0.0d && Double.isFinite(vectorScore) &&
            vectorScore >= scalarScore * MINIMUM_VECTOR_SPEEDUP;
    }

    /**
     * Measures each candidate four times, reversing candidate order on alternate trials, and returns median scores in
     * the same order as the supplied candidates.  The caller owns warmup and constructs a fresh stateful operation
     * for each measurement when required.
     */
    public static <T> double[] alternatingMedians(List<T> candidates, ToDoubleFunction<T> measurement)
    {
        Objects.requireNonNull(candidates, "Candidates cannot be null");
        Objects.requireNonNull(measurement, "Measurement cannot be null");

        if(candidates.isEmpty())
        {
            throw new IllegalArgumentException("At least one calibration candidate is required");
        }

        double[][] scores = new double[candidates.size()][BENCHMARK_TRIAL_COUNT];

        for(int trial = 0; trial < BENCHMARK_TRIAL_COUNT; trial++)
        {
            for(int position = 0; position < candidates.size(); position++)
            {
                int candidateIndex = (trial & 1) == 0 ? position : candidates.size() - 1 - position;
                scores[candidateIndex][trial] = measurement.applyAsDouble(candidates.get(candidateIndex));
            }
        }

        double[] medians = new double[candidates.size()];

        for(int x = 0; x < scores.length; x++)
        {
            medians[x] = median(scores[x]);
        }

        return medians;
    }

    /**
     * Selects the fastest vector candidate only when it clears the reliability margin over the scalar candidate at
     * index zero.  Returns zero when scalar remains the reliable choice.
     */
    public static int selectFastestReliableCandidate(double[] medianScores)
    {
        if(medianScores == null || medianScores.length == 0)
        {
            throw new IllegalArgumentException("At least one median calibration score is required");
        }

        double scalarScore = median(new double[]{medianScores[0]});
        int bestIndex = 0;
        double bestScore = scalarScore;

        for(int x = 1; x < medianScores.length; x++)
        {
            double score = median(new double[]{medianScores[x]});

            if(score > bestScore)
            {
                bestIndex = x;
                bestScore = score;
            }
        }

        return bestIndex > 0 && isReliableVectorWin(scalarScore, bestScore) ? bestIndex : 0;
    }
}
