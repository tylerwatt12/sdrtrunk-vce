/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.buffer.INativeBuffer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** Adversarial cases for the producer capability and the proof's bounded track table. */
class SpectrumWindowObservationProofTest
{
    private static final long CENTER = 851_000_000;
    private static final long RATE = 4_000_000;
    private static final long INTERVAL = Duration.ofMillis(50).toNanos();

    @Test
    void ownedProducerReportsItsFixedDelayAndClosedOrInjectedProducerHasNoCapability()
    {
        try(DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
            TunerDiagnosticService.TunerFftProcessor processor = new TunerDiagnosticService.TunerFftProcessor(
                scheduler, CENTER, RATE, ignored -> {}))
        {
            assertEquals(INTERVAL, processor.minimumPublicationIntervalNanos());
            assertEquals(1, scheduler.activeTaskCount());
            processor.close();
            assertEquals(0, processor.minimumPublicationIntervalNanos());
            assertEquals(0, scheduler.activeTaskCount());
        }
        try(TunerDiagnosticService.FrameProcessor injected = new TunerDiagnosticService.FrameProcessor()
        {
            public void receive(INativeBuffer buffer, long observedAtEpochMs, long ingressConfiguration) {}
            public long configuration() { return 1; }
            public void updateMetadata(long centerFrequencyHz, long sampleRateHz) {}
            public void close() {}
        })
        {
            assertEquals(0, injected.minimumPublicationIntervalNanos());
        }
    }

    @Test
    void oneQueuedFrameAndOneImmediateInFlightCompletionCannotHideALateQualifyingCarrier() throws Exception
    {
        Source original = queuedAndInFlight(true);
        var full = collect(original, false);
        var optimized = collect(queuedAndInFlight(true), true);
        assertEquals(1, optimized.peaks().size());
        assertFalse(optimized.finishedEarly());
        assertEquals(full.peaks(), optimized.peaks());
        assertEquals(full.acceptedFrames(), optimized.acceptedFrames());
        assertEquals(full.elapsedNanos(), optimized.elapsedNanos());
    }

    @Test
    void queuedAndInFlightQuietFramesStillPermitOnlyAnIdenticallyEmptyShortcut() throws Exception
    {
        var full = collect(queuedAndInFlight(false), false);
        var optimized = collect(queuedAndInFlight(false), true);
        assertEquals(full.peaks(), optimized.peaks());
        assertTrue(optimized.peaks().isEmpty());
        assertTrue(optimized.finishedEarly());
        assertTrue(optimized.acceptedFrames() >= 6);
        assertTrue(optimized.elapsedNanos() < full.elapsedNanos());
    }

    @Test
    void aFullTrackTableDoesNotLetAnUntrackedLateCarrierChangeTheQualifiedSet() throws Exception
    {
        Source source = regularSource();
        float[] crowded = crowdedSpectrum();
        for(int index = 0; index < source.events.size(); index++)
        {
            float[] bins = index == 0 ? crowded : noise();
            if(index > 0) signal(bins, CENTER - 300_000, -25);
            source.events.set(index, new Event(source.events.get(index).at, frame(index + 1, bins)));
        }
        var full = collect(source.copy(), false);
        var optimized = collect(source, true);
        // The 128 first-frame tracks retain their slots; the later frequency cannot allocate a 129th track.
        assertTrue(full.peaks().isEmpty());
        assertEquals(full.peaks(), optimized.peaks());
        assertTrue(optimized.finishedEarly());
        assertTrue(optimized.elapsedNanos() < full.elapsedNanos());
    }

    @Test
    void aQualifiableExistingTrackBlocksShortcutEvenWhenTheTrackTableIsFull() throws Exception
    {
        Source source = regularSource();
        for(int index = 0; index < source.events.size(); index++)
        {
            float[] bins = index == 0 ? crowdedSpectrum() : noise();
            signal(bins, CENTER + 30_000, -25);
            source.events.set(index, new Event(source.events.get(index).at, frame(index + 1, bins)));
        }
        var full = collect(source.copy(), false);
        var optimized = collect(source, true);
        assertEquals(1, optimized.peaks().size());
        assertEquals(full.peaks(), optimized.peaks());
        assertEquals(full.acceptedFrames(), optimized.acceptedFrames());
        assertFalse(optimized.finishedEarly());
    }

    @Test
    void receiverLossOrCancellationAtTheQuietDecisionCannotReturnEarlySuccess() throws Exception
    {
        Source invalid = regularSource();
        // Nine quiet frames make even an unseen track impossible at the bounded 1500 ms dwell.
        // The validity check before the ninth poll succeeds; the check before the shortcut must fail.
        invalid.invalidAt = 9 * INTERVAL;
        assertThrows(SpectrumSearchHardware.ObservationException.class, () -> collect(invalid, true));

        Source cancelled = regularSource();
        cancelled.bounded = true;
        var result = SpectrumWindowObservation.collect(detector(), cancelled, 1500, 0,
            () -> cancelled.now.get() >= 9 * INTERVAL, cancelled.now::get);
        assertFalse(result.finishedEarly());
        assertTrue(result.peaks().isEmpty());
        assertTrue(result.elapsedNanos() < Duration.ofMillis(1500).toNanos());
    }

    private static SpectrumWindowObservation.Result collect(Source source, boolean bounded) throws Exception
    {
        source.bounded = bounded;
        return SpectrumWindowObservation.collect(detector(), source, 1500, 0, () -> false, source.now::get);
    }

    private static SpectrumPeakDetector detector()
    { return new SpectrumPeakDetector(CENTER, RATE, 3_600_000, 0); }

    private static float[] noise()
    {
        float[] bins = new float[4096];
        Arrays.fill(bins, -100);
        return bins;
    }

    private static float[] crowdedSpectrum()
    {
        float[] bins = noise();
        for(int track = 0; track < 128; track++) signal(bins, CENTER + 30_000 + track * 12_500L, -35);
        return bins;
    }

    private static void signal(float[] bins, long frequency, float power)
    {
        double width = (double)RATE / bins.length;
        for(int bin = 0; bin < bins.length; bin++)
            if(Math.abs(CENTER - RATE / 2.0 + (bin + 0.5) * width - frequency) <= 1500) bins[bin] = power;
    }

    private static DiagnosticStreamFrame frame(long sequence, float[] bins)
    { return DiagnosticStreamFrame.tunerFft(1, sequence, 1000, CENTER, RATE, bins.length, 8, bins); }

    private static Source regularSource()
    {
        Source source = new Source();
        for(int index = 0; index < 30; index++)
            source.events.add(new Event((index + 1) * INTERVAL, frame(index + 1, noise())));
        return source;
    }

    private static Source queuedAndInFlight(boolean lateCarrier)
    {
        Source source = new Source();
        // This queued frame was published at least 50 ms before observation began. The already in-flight
        // calculation finishes at t=2 ns; all later publications are separated by the owned 50 ms delay.
        source.queued = frame(1, noise());
        for(int invocation = 0; invocation <= 30; invocation++)
        {
            float[] bins = noise();
            if(lateCarrier && invocation >= 5) signal(bins, CENTER + 300_000, -35);
            source.events.add(new Event(2 + invocation * INTERVAL, frame(invocation + 2, bins)));
        }
        return source;
    }

    private record Event(long at, DiagnosticStreamFrame frame) {}

    private static final class Source implements SpectrumWindowObservation.FrameSource
    {
        final AtomicLong now = new AtomicLong(1);
        final List<Event> events = new ArrayList<>();
        DiagnosticStreamFrame queued;
        int next;
        boolean bounded;
        long invalidAt = Long.MAX_VALUE;
        public DiagnosticStreamFrame poll(Duration timeout)
        {
            if(queued != null)
            {
                DiagnosticStreamFrame result = queued;
                queued = null;
                return result;
            }
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
        public boolean isClosed() { return false; }
        public long minimumPublicationIntervalNanos() { return bounded ? INTERVAL : 0; }
        Source copy()
        {
            Source copy = new Source();
            copy.events.addAll(events);
            copy.queued = queued;
            copy.invalidAt = invalidAt;
            return copy;
        }
    }
}
