/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.support;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Fixed-size aggregate counters for the log viewer's own traffic. Requests retain only a capture-scoped ticket,
 * never an exchange, URL, query, header or body. Old requests can complete without entering a later capture.
 */
public final class WebDiagnosticRequestMetrics
{
    private static final String[] OPERATIONS = {"application_log_read", "web_log_status", "web_log_read",
        "web_log_download", "unknown_log_request"};
    private final AtomicReference<Capture> mCapture = new AtomicReference<>();

    /** Admission requires no lock or retry loop. A race with a newer capture safely omits the old request. */
    public Ticket begin(String operation, long generation)
    {
        if(generation <= 0) return null;
        Capture capture = capture(generation);
        if(capture == null) return null;
        Counters counters = capture.operations[index(operation)];
        counters.started.incrementAndGet();
        long active = counters.active.incrementAndGet();
        counters.peakActive.accumulateAndGet(active, Math::max);
        return new Ticket(counters);
    }

    /** Content-Length describes the advertised response size; disconnected transfers may send fewer bytes. */
    public void finish(Ticket ticket, long durationNanos, int responseCode, boolean failed,
                       long declaredResponseBytes)
    {
        if(ticket == null || !ticket.finished.compareAndSet(false, true)) return;
        Counters counters = ticket.counters;
        long duration = Math.max(0, durationNanos);
        counters.completed.incrementAndGet();
        if(failed || responseCode >= 400 || responseCode < 100) counters.errors.incrementAndGet();
        if(failed) counters.failed.incrementAndGet();
        if(responseCode >= 400) counters.httpErrors.incrementAndGet();
        counters.durationNanos.addAndGet(duration);
        counters.maximumDurationNanos.accumulateAndGet(duration, Math::max);
        if(declaredResponseBytes >= 0) counters.declaredResponseBytes.addAndGet(declaredResponseBytes);
        else counters.unknownResponseBytes.incrementAndGet();
        counters.active.decrementAndGet();
    }

    /**
     * The writer drains completed counters periodically; active requests remain visible until completion.
     * Concurrent boundary updates can land in adjacent summaries, so each record is an aggregate observation,
     * not a transactionally consistent request ledger. Counter storage is always five fixed buckets.
     */
    public Map<String,Object> snapshotAndReset(long generation, long observedAtMs)
    {
        if(generation <= 0) return Map.of();
        Capture capture = capture(generation);
        if(capture == null) return Map.of();
        Map<String,Object> operations = new LinkedHashMap<>();
        long totalStarted = 0, totalCompleted = 0, totalErrors = 0, totalActive = 0;
        for(int index = 0; index < OPERATIONS.length; index++)
        {
            Counters counters = capture.operations[index];
            long started = counters.started.getAndSet(0);
            long completed = counters.completed.getAndSet(0);
            long errors = counters.errors.getAndSet(0);
            long failed = counters.failed.getAndSet(0);
            long httpErrors = counters.httpErrors.getAndSet(0);
            long duration = counters.durationNanos.getAndSet(0);
            long maximumDuration = counters.maximumDurationNanos.getAndSet(0);
            long bytes = counters.declaredResponseBytes.getAndSet(0);
            long unknownBytes = counters.unknownResponseBytes.getAndSet(0);
            long active = counters.active.get();
            long peakActive = Math.max(active, counters.peakActive.getAndSet(active));
            if(started == 0 && completed == 0 && errors == 0 && failed == 0 && httpErrors == 0 && active == 0 &&
                peakActive == 0 && duration == 0 && maximumDuration == 0 && bytes == 0 && unknownBytes == 0) continue;
            Map<String,Object> values = new LinkedHashMap<>();
            values.put("started", started);
            values.put("completed", completed);
            values.put("errors", errors);
            values.put("failed", failed);
            values.put("http_errors", httpErrors);
            values.put("active", active);
            values.put("peak_active", peakActive);
            values.put("duration_ms", duration / 1_000_000.0);
            values.put("max_duration_ms", maximumDuration / 1_000_000.0);
            values.put("declared_response_bytes", bytes);
            values.put("unknown_response_bytes_count", unknownBytes);
            operations.put(OPERATIONS[index], Map.copyOf(values));
            totalStarted += started;
            totalCompleted += completed;
            totalErrors += errors;
            totalActive += active;
        }
        if(operations.isEmpty()) return Map.of();
        return Map.of("observed_at", observedAtMs, "capture_generation", generation, "started", totalStarted,
            "completed", totalCompleted, "errors", totalErrors, "active", totalActive,
            "operations", Map.copyOf(operations));
    }

    private Capture capture(long generation)
    {
        Capture current = mCapture.get();
        if(current != null && current.generation >= generation)
            return current.generation == generation ? current : null;
        Capture replacement = new Capture(generation);
        if(mCapture.compareAndSet(current, replacement)) return replacement;
        current = mCapture.get();
        return current != null && current.generation == generation ? current : null;
    }

    private static int index(String operation)
    {
        for(int index = 0; index < OPERATIONS.length - 1; index++)
            if(OPERATIONS[index].equals(operation)) return index;
        return OPERATIONS.length - 1;
    }

    public static final class Ticket
    {
        private final Counters counters;
        private final AtomicBoolean finished = new AtomicBoolean();
        private Ticket(Counters counters) { this.counters = counters; }
    }

    private static final class Capture
    {
        private final long generation;
        private final Counters[] operations = new Counters[OPERATIONS.length];
        private Capture(long generation)
        {
            this.generation = generation;
            for(int index = 0; index < operations.length; index++) operations[index] = new Counters();
        }
    }

    private static final class Counters
    {
        private final AtomicLong started = new AtomicLong();
        private final AtomicLong completed = new AtomicLong();
        private final AtomicLong errors = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();
        private final AtomicLong httpErrors = new AtomicLong();
        private final AtomicLong active = new AtomicLong();
        private final AtomicLong peakActive = new AtomicLong();
        private final AtomicLong durationNanos = new AtomicLong();
        private final AtomicLong maximumDurationNanos = new AtomicLong();
        private final AtomicLong declaredResponseBytes = new AtomicLong();
        private final AtomicLong unknownResponseBytes = new AtomicLong();
    }
}
