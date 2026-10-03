/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/** Search-worker observation loop. It only shortens windows whose complete candidate set is provably empty. */
final class SpectrumWindowObservation
{
    private static final Duration POLL_TIMEOUT = Duration.ofMillis(100);

    interface FrameSource
    {
        DiagnosticStreamFrame poll(Duration timeout) throws InterruptedException;
        boolean valid();
        boolean isClosed();
        default long minimumPublicationIntervalNanos() { return 0; }
    }

    record Result(List<SpectrumPeakDetector.Peak> peaks, long elapsedNanos, long firstAcceptedFftNanos,
                  int acceptedFrames, int maximumFutureFrames, boolean cadenceKnown, boolean finishedEarly) { }

    static Result collect(SpectrumPeakDetector detector, FrameSource source, long dwellMillis, long freshAfter,
                          BooleanSupplier cancelled, LongSupplier nanoTime) throws InterruptedException
    {
        long started = nanoTime.getAsLong();
        long deadline = started + Duration.ofMillis(dwellMillis).toNanos();
        long firstAccepted = 0;
        long generation = -1;
        long sequence = -1;
        boolean certain = true;
        boolean early = false;
        int future = -1;
        while(nanoTime.getAsLong() < deadline && !cancelled.getAsBoolean())
        {
            requireValid(source);
            DiagnosticStreamFrame frame = source.poll(POLL_TIMEOUT);
            if(frame != null && frame.observedAtEpochMs() >= freshAfter)
            {
                int previous = detector.frames();
                detector.receive(frame);
                if(frame.type() == DiagnosticStreamFrame.TYPE_TUNER_FFT)
                {
                    // Keep the original collector behavior on uncertain traces; only disable the shortcut.
                    if(detector.frames() == previous || frame.sequence() <= sequence ||
                        generation >= 0 && frame.generation() != generation) certain = false;
                    generation = frame.generation();
                    sequence = frame.sequence();
                }
                if(detector.frames() > previous && firstAccepted == 0) firstAccepted = nanoTime.getAsLong();
            }
            if(source.isClosed()) throw new SpectrumSearchHardware.ObservationException(
                "The receiver stopped providing spectrum data. Check its connection and retry.");

            future = certain ? futureFrameBound(source.minimumPublicationIntervalNanos(),
                deadline - nanoTime.getAsLong()) : -1;
            if(future >= 0 && detector.cannotBecomePersistent(future) && !cancelled.getAsBoolean())
            {
                requireValid(source);
                // The final normal poll may run past the deadline; that is already included in the bound.
                early = nanoTime.getAsLong() < deadline;
                if(early) break;
            }
        }
        if(!cancelled.getAsBoolean()) SpectrumSearchHardware.requireFreshFrames(dwellMillis, detector.frames());
        return new Result(detector.peaks(), nanoTime.getAsLong() - started, firstAccepted, detector.frames(),
            future, future >= 0, early);
    }

    private static void requireValid(FrameSource source)
    {
        if(!source.valid()) throw new SpectrumSearchHardware.ObservationException(
            "The search receiver changed. Choose an idle receiver and retry.");
    }

    /**
     * The FFT queue has one latest-frame slot. Allow that queued frame, one calculation already in flight,
     * and every future fixed-delay invocation through the last 100 ms poll. Never infer a bound from a
     * requested/display frame rate: unbounded producers and arithmetic uncertainty keep the full dwell.
     */
    static int futureFrameBound(long minimumIntervalNanos, long remainingNanos)
    {
        if(minimumIntervalNanos <= 0) return -1;
        try
        {
            long horizon = Math.addExact(Math.max(0, remainingNanos), POLL_TIMEOUT.toNanos());
            long invocations = horizon / minimumIntervalNanos + (horizon % minimumIntervalNanos == 0 ? 0 : 1);
            return Math.toIntExact(Math.addExact(2, invocations));
        }
        catch(ArithmeticException exception) { return -1; }
    }
}
