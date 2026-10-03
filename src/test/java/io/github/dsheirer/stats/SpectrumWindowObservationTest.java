/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

class SpectrumWindowObservationTest
{
    private static final long CENTER = 851_000_000;
    private static final long RATE = 2_000_000;
    private static final long INTERVAL = Duration.ofMillis(50).toNanos();

    @Test void boundIncludesQueuedInFlightAndFinalDeadlinePollAndRejectsUnknownOrOverflow()
    {
        assertEquals(4, SpectrumWindowObservation.futureFrameBound(INTERVAL, 0));
        assertEquals(5, SpectrumWindowObservation.futureFrameBound(INTERVAL, 1));
        assertEquals(34, SpectrumWindowObservation.futureFrameBound(INTERVAL, Duration.ofMillis(1500).toNanos()));
        assertEquals(-1, SpectrumWindowObservation.futureFrameBound(0, 1));
        assertEquals(-1, SpectrumWindowObservation.futureFrameBound(-1, 1));
        assertEquals(-1, SpectrumWindowObservation.futureFrameBound(1, Long.MAX_VALUE));
    }

    @Test void quietWindowReturnsIdenticalEmptySetEarlierButUnknownProducerRetainsDwell() throws Exception
    {
        Trace known = trace(frame -> false);
        var early = collect(known, true);
        var full = collect(trace(frame -> false), false);
        assertTrue(early.finishedEarly());
        assertTrue(early.acceptedFrames() >= 6);
        assertTrue(early.elapsedNanos() < full.elapsedNanos());
        assertEquals(full.peaks(), early.peaks());
        assertTrue(early.cadenceKnown());
        assertFalse(full.cadenceKnown());
        assertFalse(full.finishedEarly());
        assertTrue(full.elapsedNanos() >= Duration.ofMillis(1500).toNanos());
    }

    @Test void weakLateAndIntermittentTracksRetainFullDwellAndExactRanking() throws Exception
    {
        for(IntPredicate present: List.<IntPredicate>of(index -> true, index -> index >= 7,
            index -> index % 4 != 0, index -> index < 22))
        {
            var optimized = collect(trace(present), true);
            var original = collect(trace(present), false);
            assertFalse(optimized.finishedEarly());
            assertEquals(original.elapsedNanos(), optimized.elapsedNanos());
            assertEquals(original.peaks(), optimized.peaks());
            assertEquals(original.acceptedFrames(), optimized.acceptedFrames());
        }
    }

    @Test void lateRejectedTrackAndJitterCannotChangeAnEmptyQualifiedSet() throws Exception
    {
        Trace jitter = trace(index -> index >= 23);
        for(int index = 0; index < jitter.events.size(); index++)
        {
            Event value = jitter.events.get(index);
            jitter.events.set(index, new Event(value.at + index * Duration.ofMillis(3).toNanos(), value.frame));
        }
        Trace full = jitter.copy();
        assertEquals(collect(full, false).peaks(), collect(jitter, true).peaks());
        assertTrue(jitter.now.get() < full.now.get());
    }

    @Test void duplicateOutOfOrderGenerationAndMalformedFramesDisableOnlyShortcut() throws Exception
    {
        for(int kind = 0; kind < 4; kind++)
        {
            Trace values = trace(index -> false);
            Event third = values.events.get(2);
            DiagnosticStreamFrame frame = third.frame;
            DiagnosticStreamFrame replacement = switch(kind)
            {
                case 0 -> frame(1, 2, noise());
                case 1 -> frame(1, 0, noise());
                case 2 -> frame(2, 3, noise());
                default -> DiagnosticStreamFrame.tunerFft(1, 3, 1000, CENTER + RATE, RATE, 4096, 8, noise());
            };
            values.events.set(2, new Event(third.at, replacement));
            var original = collect(values.copy(), false);
            var optimized = collect(values, true);
            assertFalse(optimized.finishedEarly());
            assertFalse(optimized.cadenceKnown());
            assertEquals(original.peaks(), optimized.peaks());
            assertEquals(original.acceptedFrames(), optimized.acceptedFrames());
            assertEquals(original.elapsedNanos(), optimized.elapsedNanos());
        }
    }

    @Test void freshFrameMinimumSourceFailureAndCancellationStayEffective() throws Exception
    {
        Trace tooFew = trace(index -> false);
        tooFew.events.subList(5, tooFew.events.size()).clear();
        assertThrows(SpectrumSearchHardware.ObservationException.class, () -> collect(tooFew, true));
        assertTrue(tooFew.now.get() >= Duration.ofMillis(1500).toNanos());

        Trace stopped = trace(index -> false);
        stopped.closeAt = Duration.ofMillis(300).toNanos();
        assertThrows(SpectrumSearchHardware.ObservationException.class, () -> collect(stopped, true));

        Trace invalid = trace(index -> false);
        invalid.invalidAt = Duration.ofMillis(300).toNanos();
        assertThrows(SpectrumSearchHardware.ObservationException.class, () -> collect(invalid, true));

        Trace cancelled = trace(index -> false);
        AtomicBoolean cancel = new AtomicBoolean();
        var result = SpectrumWindowObservation.collect(detector(), cancelled, 1500, 0,
            () -> { if(cancelled.now.get() >= Duration.ofMillis(250).toNanos()) cancel.set(true); return cancel.get(); },
            cancelled.now::get);
        assertTrue(cancel.get());
        assertFalse(result.finishedEarly());
        assertTrue(result.elapsedNanos() < Duration.ofMillis(1500).toNanos());
    }

    @Test void usableEdgesCenterPolicyAndMultiplePeaksKeepSameCentroidsAndPower() throws Exception
    {
        Trace values = trace(index -> true);
        for(int index = 0; index < values.events.size(); index++)
        {
            float[] bins = noise();
            signal(bins, CENTER + 300_000 + index % 2 * 1000, -91); // weak, just above the existing gate
            signal(bins, CENTER - 850_000, -40);
            signal(bins, CENTER + 899_000, -20); // rejected usable edge
            signal(bins, CENTER, -10); // rejected hardware center
            values.events.set(index, new Event(values.events.get(index).at, frame(1, index + 1, bins)));
        }
        var original = collect(values.copy(), false);
        var optimized = collect(values, true);
        assertFalse(optimized.finishedEarly());
        assertEquals(2, optimized.peaks().size());
        assertEquals(original.peaks(), optimized.peaks());
    }

    private static SpectrumWindowObservation.Result collect(Trace trace, boolean bounded) throws Exception
    {
        trace.bounded = bounded;
        return SpectrumWindowObservation.collect(detector(), trace, 1500, 0, () -> false, trace.now::get);
    }

    private static SpectrumPeakDetector detector() { return new SpectrumPeakDetector(CENTER, RATE, 1_800_000, 0); }
    private static float[] noise() { float[] bins = new float[4096]; Arrays.fill(bins, -100); return bins; }
    private static void signal(float[] bins, long frequency, float power)
    {
        double width = (double)RATE / bins.length;
        for(int bin = 0; bin < bins.length; bin++)
            if(Math.abs(CENTER - RATE / 2.0 + (bin + 0.5) * width - frequency) <= 5000) bins[bin] = power;
    }
    private static DiagnosticStreamFrame frame(long generation, long sequence, float[] bins)
    { return DiagnosticStreamFrame.tunerFft(generation, sequence, 1000, CENTER, RATE, bins.length, 8, bins); }
    private static Trace trace(IntPredicate present)
    {
        Trace trace = new Trace();
        for(int index = 0; index < 30; index++)
        {
            float[] bins = noise();
            if(present.test(index)) signal(bins, CENTER + 300_000, -35);
            trace.events.add(new Event((index + 1) * INTERVAL, frame(1, index + 1, bins)));
        }
        return trace;
    }
    private record Event(long at, DiagnosticStreamFrame frame) { }
    private static final class Trace implements SpectrumWindowObservation.FrameSource
    {
        final AtomicLong now = new AtomicLong(1);
        final List<Event> events = new ArrayList<>();
        int next;
        boolean bounded;
        long closeAt = Long.MAX_VALUE;
        long invalidAt = Long.MAX_VALUE;
        public DiagnosticStreamFrame poll(Duration timeout)
        {
            long until = now.get() + timeout.toNanos();
            if(next < events.size() && events.get(next).at <= until)
            {
                Event event = events.get(next++);
                now.set(Math.max(now.get(), event.at));
                return event.frame;
            }
            now.set(until);
            return null;
        }
        public boolean valid() { return now.get() < invalidAt; }
        public boolean isClosed() { return now.get() >= closeAt; }
        public long minimumPublicationIntervalNanos() { return bounded ? INTERVAL : 0; }
        Trace copy()
        { Trace copy = new Trace(); copy.events.addAll(events); copy.closeAt = closeAt; copy.invalidAt = invalidAt; return copy; }
    }
}
