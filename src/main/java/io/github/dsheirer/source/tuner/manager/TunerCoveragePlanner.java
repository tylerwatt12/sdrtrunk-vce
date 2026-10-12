/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.tuner.channel.TunerChannel;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Pure, bounded placement arithmetic. Knowledge is a hint; required channels always take precedence. */
public final class TunerCoveragePlanner
{
    private TunerCoveragePlanner() {}

    public record Window(long centerHz, int usableBandwidthHz, int centerGapHalfHz, long minHz, long maxHz)
    {
        public Window at(long center)
        {
            return new Window(center, usableBandwidthHz, centerGapHalfHz, minHz, maxHz);
        }
    }

    public static boolean covers(Window window, TunerChannel channel)
    {
        if(window == null || channel == null || channel.getFrequency() <= 0 || channel.getBandwidth() <= 0 ||
            window.usableBandwidthHz() <= 0 || channel.getMinFrequency() < window.minHz() ||
            channel.getMaxFrequency() > window.maxHz())
        {
            return false;
        }
        long half = window.usableBandwidthHz() / 2L;
        return channel.getMinFrequency() >= window.centerHz() - half &&
            channel.getMaxFrequency() <= window.centerHz() + half &&
            (window.centerGapHalfHz() <= 0 || !channel.overlaps(
                window.centerHz() - window.centerGapHalfHz(), window.centerHz() + window.centerGapHalfHz()));
    }

    public static int coveredCount(Collection<TunerChannel> channels, Collection<Window> windows)
    {
        int covered = 0;
        for(TunerChannel channel: channels)
        {
            if(windows.stream().anyMatch(window -> covers(window, channel))) covered++;
        }
        return covered;
    }

    /**
     * Chooses a useful legal center. Empty required channels permits fully-idle gap repair. Equal coverage keeps
     * the current center; all other ties favor the nearest center and then the lower frequency. Candidate centers
     * come from passband and DC edges rather than an unbounded one-hertz search.
     */
    public static long chooseCenter(Collection<TunerChannel> required, Collection<TunerChannel> known,
                                     Collection<Window> otherWindows, Window window, long gridHz)
    {
        if(window.usableBandwidthHz() <= 0 || window.minHz() > window.maxHz()) return -1;
        long half = window.usableBandwidthHz() / 2L;
        long low = Math.max(1, window.minHz()), high = window.maxHz();
        for(TunerChannel channel: required)
        {
            if(channel.getMinFrequency() < window.minHz() || channel.getMaxFrequency() > window.maxHz()) return -1;
            low = Math.max(low, channel.getMaxFrequency() - half);
            high = Math.min(high, channel.getMinFrequency() + half);
        }
        if(low > high) return -1;

        // A site list is bounded by the receiver's frequency catalog. Already-covered channels do not pull an
        // unused tuner back into another tuner's window.
        List<TunerChannel> useful = known.stream().filter(channel ->
            channel.getMinFrequency() >= window.minHz() && channel.getMaxFrequency() <= window.maxHz() &&
                otherWindows.stream().noneMatch(other -> covers(other, channel))).toList();
        long anchor = !required.isEmpty() ? required.iterator().next().getFrequency() :
            !useful.isEmpty() ? useful.getFirst().getFrequency() : window.centerHz();
        Set<Long> candidates = new LinkedHashSet<>();
        candidates.add(window.centerHz());
        add(candidates, low, low, high, anchor, gridHz);
        add(candidates, high, low, high, anchor, gridHz);
        add(candidates, low + (high - low) / 2, low, high, anchor, gridHz);
        for(TunerChannel channel: useful)
        {
            add(candidates, channel.getMaxFrequency() - half, low, high, anchor, gridHz);
            add(candidates, channel.getMinFrequency() + half, low, high, anchor, gridHz);
            add(candidates, channel.getMaxFrequency() + window.centerGapHalfHz() + 1, low, high, anchor, gridHz);
            add(candidates, channel.getMinFrequency() - window.centerGapHalfHz() - 1, low, high, anchor, gridHz);
        }
        // Required channels can constrain the legal side of the center exclusion even when they are not known.
        for(TunerChannel channel: required)
        {
            add(candidates, channel.getMaxFrequency() + window.centerGapHalfHz() + 1, low, high, anchor, gridHz);
            add(candidates, channel.getMinFrequency() - window.centerGapHalfHz() - 1, low, high, anchor, gridHz);
        }

        long best = -1, distance = Long.MAX_VALUE;
        int score = -1;
        for(long center: candidates)
        {
            if(center < low || center > high) continue;
            Window candidate = window.at(center);
            if(required.stream().anyMatch(channel -> !covers(candidate, channel))) continue;
            int count = (int)useful.stream().filter(channel -> covers(candidate, channel)).count();
            long move = Math.abs(center - window.centerHz());
            if(count > score || count == score && (move < distance || move == distance && center < best))
            {
                score = count;
                distance = move;
                best = center;
            }
        }
        return best;
    }

    private static void add(Set<Long> candidates, long center, long low, long high, long anchor, long grid)
    {
        long bounded = Math.max(low, Math.min(center, high));
        candidates.add(bounded);
        if(grid > 0)
        {
            long below = bounded - Math.floorMod(bounded - anchor, grid);
            if(below >= low && below <= high) candidates.add(below);
            if(below <= Long.MAX_VALUE - grid && below + grid >= low && below + grid <= high)
                candidates.add(below + grid);
        }
    }
}
