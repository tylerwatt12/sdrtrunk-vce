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
package io.github.dsheirer.source.tuner.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.source.tuner.channel.TunerChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TunerCoveragePlannerTest
{
    private static final int RTL_USABLE_BANDWIDTH = 2_160_000;
    private static final int DC_HALF_BANDWIDTH = 5_000;

    @Test
    void coverageRequiresFullChannelInsideWindowLimitsAndOutsideCenterGap()
    {
        TunerCoveragePlanner.Window window = window(857_000_000);
        assertTrue(TunerCoveragePlanner.covers(window, channel(856_000_000)));
        assertFalse(TunerCoveragePlanner.covers(window, channel(858_079_000)),
            "The channel center fits, but its upper edge exceeds the receiving window");
        assertFalse(TunerCoveragePlanner.covers(window, channel(857_005_000)),
            "The channel center clears the gap, but part of its bandwidth does not");
        assertTrue(TunerCoveragePlanner.covers(window, channel(857_012_500)));

        TunerCoveragePlanner.Window limited = new TunerCoveragePlanner.Window(857_000_000,
            RTL_USABLE_BANDWIDTH, DC_HALF_BANDWIDTH, 856_000_000, 858_000_000);
        assertFalse(TunerCoveragePlanner.covers(limited, channel(856_002_000)));
        assertFalse(TunerCoveragePlanner.covers(limited, channel(857_998_000)));
        assertTrue(TunerCoveragePlanner.covers(limited, channel(856_012_500)));
    }

    @Test
    void coverageCountUsesUnionOfEligibleWindows()
    {
        List<TunerChannel> known = List.of(channel(852_012_500), channel(854_012_500), channel(855_012_500));
        List<TunerCoveragePlanner.Window> windows = List.of(window(853_100_000), window(855_000_000));

        assertEquals(2, TunerCoveragePlanner.coveredCount(known, windows),
            "A frequency received by multiple windows still counts once, and uncovered edges stay uncovered");
    }

    @Test
    void unusedPlacementPrioritizesKnownFrequenciesMissingFromOtherWindows()
    {
        TunerCoveragePlanner.Window unused = window(854_300_000);
        TunerCoveragePlanner.Window other = window(855_500_000);
        List<TunerChannel> missing = List.of(channel(851_812_500), channel(852_012_500), channel(853_012_500));
        List<TunerChannel> known = List.of(missing.get(0), missing.get(1), missing.get(2),
            channel(854_800_000), channel(855_000_000), channel(855_400_000));
        long chosenCenter = TunerCoveragePlanner.chooseCenter(List.of(missing.get(0)), known, List.of(other),
            unused, 25_000);
        TunerCoveragePlanner.Window chosen = atCenter(unused, chosenCenter);

        assertTrue(chosenCenter > 0);
        assertTrue(TunerCoveragePlanner.covers(chosen, missing.get(0)), "The requested call must always fit");
        assertEquals(3, covered(chosen, missing), "Use the spare window for frequencies the other tuner misses");
        assertEquals(6, globallyCovered(chosen, List.of(other), known));
    }

    @Test
    void unknownRequestedFrequencyDoesNotRequireCompleteOrSameBandKnowledge()
    {
        TunerCoveragePlanner.Window unused = new TunerCoveragePlanner.Window(771_500_000,
            RTL_USABLE_BANDWIDTH, DC_HALF_BANDWIDTH, 700_000_000, 900_000_000);
        TunerChannel previouslyUnknown = channel(857_512_500);
        List<TunerChannel> incompleteKnowledge = List.of(channel(771_512_500), channel(857_537_500));
        long chosenCenter = TunerCoveragePlanner.chooseCenter(List.of(previouslyUnknown), incompleteKnowledge,
            List.of(), unused, 25_000);

        assertTrue(chosenCenter > 0);
        assertTrue(TunerCoveragePlanner.covers(atCenter(unused, chosenCenter), previouslyUnknown));
        assertTrue(TunerCoveragePlanner.covers(atCenter(unused, chosenCenter), incompleteKnowledge.get(1)));
    }

    @Test
    void requiredChannelsAreNeverTradedForMoreKnownCoverage()
    {
        TunerCoveragePlanner.Window receiving = window(854_300_000);
        List<TunerChannel> ongoing = List.of(channel(853_500_000), channel(854_000_000), channel(854_800_000));
        List<TunerChannel> known = List.of(channel(851_500_000), channel(851_600_000), channel(851_700_000),
            ongoing.get(0), ongoing.get(1), ongoing.get(2));
        long chosenCenter = TunerCoveragePlanner.chooseCenter(ongoing, known, List.of(), receiving, 25_000);

        assertTrue(chosenCenter > 0);
        assertEquals(ongoing.size(), covered(atCenter(receiving, chosenCenter), ongoing),
            "Control, conventional and voice channels are all required receiving channels");
    }

    @Test
    void impossibleRequiredSpanReturnsNoPlacementRatherThanDroppingControl()
    {
        TunerCoveragePlanner.Window receiving = new TunerCoveragePlanner.Window(771_500_000,
            RTL_USABLE_BANDWIDTH, DC_HALF_BANDWIDTH, 700_000_000, 900_000_000);
        assertEquals(-1, TunerCoveragePlanner.chooseCenter(
            List.of(channel(771_512_500), channel(857_512_500)), List.of(channel(857_537_500)), List.of(),
            receiving, 25_000));
    }

    @Test
    void fullyUnusedWindowMovesOnlyForStrictGlobalCoverageGain()
    {
        TunerCoveragePlanner.Window unused = window(854_300_000);
        TunerCoveragePlanner.Window other = window(855_500_000);
        List<TunerChannel> known = List.of(channel(851_812_500), channel(852_212_500), channel(853_012_500),
            channel(853_412_500), channel(854_512_500), channel(855_012_500));
        int previouslyCovered = globallyCovered(unused, List.of(other), known);
        long chosenCenter = TunerCoveragePlanner.chooseCenter(List.of(), known, List.of(other), unused, 25_000);

        assertTrue(chosenCenter > 0);
        assertTrue(globallyCovered(atCenter(unused, chosenCenter), List.of(other), known) > previouslyCovered);
    }

    @Test
    void equalCoverageAndRepeatedIdleRepairKeepStableCenter()
    {
        TunerCoveragePlanner.Window unused = window(854_300_000);
        List<TunerChannel> equalAlternative = List.of(channel(851_812_500), channel(854_112_500));
        long chosenCenter = TunerCoveragePlanner.chooseCenter(List.of(), equalAlternative, List.of(), unused, 25_000);

        assertEquals(unused.centerHz(), chosenCenter, "Trading one covered frequency for another adds no benefit");
        assertEquals(chosenCenter, TunerCoveragePlanner.chooseCenter(List.of(), equalAlternative, List.of(),
            atCenter(unused, chosenCenter), 25_000));
        assertEquals(unused.centerHz(), TunerCoveragePlanner.chooseCenter(List.of(), List.of(), List.of(),
            unused, 25_000), "No learned frequencies is a stable idle state");
    }

    @Test
    void frequenciesAlreadyCoveredElsewhereDoNotCauseIdleMovement()
    {
        TunerCoveragePlanner.Window unused = window(854_300_000);
        TunerCoveragePlanner.Window other = window(852_400_000);
        List<TunerChannel> known = List.of(channel(851_812_500), channel(852_012_500), channel(853_012_500));

        assertEquals(unused.centerHz(), TunerCoveragePlanner.chooseCenter(List.of(), known, List.of(other),
            unused, 25_000));
    }

    @Test
    void candidateSearchUsesFrequencyEdgesRatherThanEveryHertz()
    {
        AtomicInteger edgeReads = new AtomicInteger();
        List<TunerChannel> known = new ArrayList<>();
        for(int index = 0; index < 64; index++)
        {
            long frequency = 851_812_500L + index * 1_500_000L;
            known.add(new TunerChannel(frequency, 12_500)
            {
                @Override public long getMinFrequency() { edgeReads.incrementAndGet(); return super.getMinFrequency(); }
                @Override public long getMaxFrequency() { edgeReads.incrementAndGet(); return super.getMaxFrequency(); }
            });
        }
        TunerCoveragePlanner.Window wideLimits = new TunerCoveragePlanner.Window(854_300_000,
            RTL_USABLE_BANDWIDTH, DC_HALF_BANDWIDTH, 850_000_000, 950_000_000);
        long center = TunerCoveragePlanner.chooseCenter(List.of(), known, List.of(), wideLimits, 25_000);

        assertTrue(center > 0);
        assertTrue(edgeReads.get() < 1_000_000,
            "A 100 MHz permitted range must not turn a frequency-edge search into a per-hertz scan");
    }

    @Test
    void invalidReceivingGeometryCannotPlaceRequestedChannel()
    {
        TunerCoveragePlanner.Window noBandwidth = new TunerCoveragePlanner.Window(857_000_000,
            0, DC_HALF_BANDWIDTH, 850_000_000, 860_000_000);
        TunerCoveragePlanner.Window reversedLimits = new TunerCoveragePlanner.Window(857_000_000,
            RTL_USABLE_BANDWIDTH, DC_HALF_BANDWIDTH, 860_000_000, 850_000_000);
        assertFalse(TunerCoveragePlanner.covers(noBandwidth, channel(857_012_500)));
        assertEquals(-1, TunerCoveragePlanner.chooseCenter(List.of(channel(857_012_500)), List.of(), List.of(),
            noBandwidth, 25_000));
        assertEquals(-1, TunerCoveragePlanner.chooseCenter(List.of(channel(857_012_500)), List.of(), List.of(),
            reversedLimits, 25_000));
    }

    @Test
    void invalidFrequencyLimitEdgesRejectRequestedChannel()
    {
        TunerCoveragePlanner.Window limited = new TunerCoveragePlanner.Window(857_000_000,
            RTL_USABLE_BANDWIDTH, DC_HALF_BANDWIDTH, 856_000_000, 858_000_000);
        assertEquals(-1, TunerCoveragePlanner.chooseCenter(List.of(channel(858_002_000)), List.of(), List.of(),
            limited, 25_000));
        assertEquals(-1, TunerCoveragePlanner.chooseCenter(List.of(channel(855_998_000)), List.of(), List.of(),
            limited, 25_000));
    }

    private static TunerChannel channel(long frequency)
    {
        return new TunerChannel(frequency, 12_500);
    }

    private static TunerCoveragePlanner.Window window(long center)
    {
        return new TunerCoveragePlanner.Window(center, RTL_USABLE_BANDWIDTH, DC_HALF_BANDWIDTH,
            850_000_000, 860_000_000);
    }

    private static TunerCoveragePlanner.Window atCenter(TunerCoveragePlanner.Window window, long center)
    {
        return new TunerCoveragePlanner.Window(center, window.usableBandwidthHz(), window.centerGapHalfHz(),
            window.minHz(), window.maxHz());
    }

    private static long covered(TunerCoveragePlanner.Window window, List<TunerChannel> channels)
    {
        return channels.stream().filter(channel -> TunerCoveragePlanner.covers(window, channel)).count();
    }

    private static int globallyCovered(TunerCoveragePlanner.Window window,
                                      List<TunerCoveragePlanner.Window> others, List<TunerChannel> channels)
    {
        return (int)channels.stream().filter(channel -> TunerCoveragePlanner.covers(window, channel) ||
            others.stream().anyMatch(other -> TunerCoveragePlanner.covers(other, channel))).count();
    }
}
