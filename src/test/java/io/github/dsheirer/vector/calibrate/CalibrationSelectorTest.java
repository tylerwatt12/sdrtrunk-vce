/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.vector.calibrate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalibrationSelectorTest
{
    @Test
    void medianRejectsOneFastOutlier()
    {
        assertEquals(101.0d, CalibrationSelector.median(new double[]{100.0d, 500.0d, 101.0d}));
        assertEquals(102.0d, CalibrationSelector.median(new double[]{100.0d, 104.0d}));
    }

    @Test
    void vectorMustBeatScalarByAtLeastFivePercent()
    {
        assertFalse(CalibrationSelector.isReliableVectorWin(100.0d, 104.999d));
        assertTrue(CalibrationSelector.isReliableVectorWin(100.0d, 105.0d));
        assertTrue(CalibrationSelector.isReliableVectorWin(100.0d, 160.0d));
    }

    @Test
    void invalidScoresCannotSelectVector()
    {
        assertFalse(CalibrationSelector.isReliableVectorWin(0.0d, 100.0d));
        assertFalse(CalibrationSelector.isReliableVectorWin(100.0d, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> CalibrationSelector.median(new double[0]));
        assertThrows(IllegalArgumentException.class,
            () -> CalibrationSelector.median(new double[]{100.0d, Double.POSITIVE_INFINITY}));
    }

    @Test
    void candidateMeasurementsAlternateForwardAndReverseBeforeTakingMedians()
    {
        List<String> order = new ArrayList<>();
        List<String> candidates = List.of("scalar", "vector-64", "vector-128");
        double[] medians = CalibrationSelector.alternatingMedians(candidates, candidate ->
        {
            order.add(candidate);
            return switch(candidate)
            {
                case "scalar" -> 100.0d;
                case "vector-64" -> 110.0d;
                case "vector-128" -> 120.0d;
                default -> throw new IllegalArgumentException(candidate);
            };
        });

        assertEquals(List.of("scalar", "vector-64", "vector-128", "vector-128", "vector-64", "scalar",
            "scalar", "vector-64", "vector-128", "vector-128", "vector-64", "scalar"), order);
        assertEquals(100.0d, medians[0]);
        assertEquals(110.0d, medians[1]);
        assertEquals(120.0d, medians[2]);
    }

    @Test
    void selectsOnlyTheFastestVectorThatClearsTheReliabilityMargin()
    {
        assertEquals(0, CalibrationSelector.selectFastestReliableCandidate(new double[]{100.0d, 104.999d, 80.0d}));
        assertEquals(2, CalibrationSelector.selectFastestReliableCandidate(new double[]{100.0d, 106.0d, 120.0d}));
        assertThrows(IllegalArgumentException.class,
            () -> CalibrationSelector.selectFastestReliableCandidate(new double[]{100.0d, Double.NaN}));
    }
}
