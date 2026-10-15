/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WebDiagnosticRequestMetricsTest
{
    @Test
    void summarizesKnownAndUnknownBytesErrorsAndDurationsThenDrainsCompletedCounters()
    {
        WebDiagnosticRequestMetrics metrics = new WebDiagnosticRequestMetrics();
        assertNull(metrics.begin("web_log_read", 0));
        assertTrue(metrics.snapshotAndReset(0, 10).isEmpty());
        metrics.finish(null, 0, 200, false, 0);
        WebDiagnosticRequestMetrics.Ticket first = metrics.begin("web_log_read", 2);
        WebDiagnosticRequestMetrics.Ticket second = metrics.begin("web_log_read", 2);
        metrics.finish(first, 2_000_000, 200, false, 4096);
        metrics.finish(first, 100_000_000, 500, true, 1000); // A ticket completes once.
        metrics.finish(second, 3_000_000, 503, true, -1);

        Map<String,Object> snapshot = metrics.snapshotAndReset(2, 123);
        assertEquals(123L, snapshot.get("observed_at"));
        assertEquals(2L, snapshot.get("started"));
        assertEquals(2L, snapshot.get("completed"));
        assertEquals(1L, snapshot.get("errors"));
        Map<?,?> read = operation(snapshot, "web_log_read");
        assertEquals(0L, read.get("active"));
        assertEquals(2L, read.get("peak_active"));
        assertEquals(5.0, read.get("duration_ms"));
        assertEquals(3.0, read.get("max_duration_ms"));
        assertEquals(4096L, read.get("declared_response_bytes"));
        assertEquals(1L, read.get("unknown_response_bytes_count"));
        assertEquals(1L, read.get("http_errors"));
        assertEquals(1L, read.get("failed"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.put("secret", "value"));
        assertThrows(UnsupportedOperationException.class, read::clear);
        assertTrue(metrics.snapshotAndReset(2, 124).isEmpty());
    }

    @Test
    void ongoingRequestsRemainVisibleButOldCaptureCompletionsCannotContaminateNewCapture()
    {
        WebDiagnosticRequestMetrics metrics = new WebDiagnosticRequestMetrics();
        WebDiagnosticRequestMetrics.Ticket old = metrics.begin("web_log_download", 2);
        assertEquals(1L, metrics.snapshotAndReset(2, 10).get("active"));
        Map<String,Object> ongoing = metrics.snapshotAndReset(2, 15);
        assertEquals(1L, ongoing.get("active"));
        assertEquals(0L, ongoing.get("started"));
        WebDiagnosticRequestMetrics.Ticket current = metrics.begin("web_log_status", 4);
        assertNull(metrics.begin("web_log_status", 2));
        assertTrue(metrics.snapshotAndReset(2, 20).isEmpty());
        metrics.finish(old, 5_000_000_000L, 500, true, 10000);
        metrics.finish(current, 1_000_000, 200, false, 32);
        Map<String,Object> snapshot = metrics.snapshotAndReset(4, 25);
        assertEquals(1L, snapshot.get("started"));
        assertEquals(1L, snapshot.get("completed"));
        assertEquals(0L, snapshot.get("errors"));
        assertEquals(0L, snapshot.get("active"));
        assertEquals(1, ((Map<?,?>)snapshot.get("operations")).size());
    }

    @Test
    void arbitraryOperationNamesCannotGrowTheFixedBucketsOrRetainPrivateText()
    {
        WebDiagnosticRequestMetrics metrics = new WebDiagnosticRequestMetrics();
        for(int index = 0; index < 1000; index++)
            metrics.finish(metrics.begin("private-query-secret-" + index, 2), -1, -1, false, -1);
        Map<String,Object> snapshot = metrics.snapshotAndReset(2, 1);
        assertEquals(1000L, snapshot.get("completed"));
        assertEquals(1000L, snapshot.get("errors"));
        assertEquals(1, ((Map<?,?>)snapshot.get("operations")).size());
        assertEquals(0.0, operation(snapshot, "unknown_log_request").get("duration_ms"));
        assertFalse(snapshot.toString().contains("private-query-secret"));
    }

    @Test
    void concurrentBlockedClientsUseIndependentTicketsWithoutLosingActiveCounts() throws Exception
    {
        WebDiagnosticRequestMetrics metrics = new WebDiagnosticRequestMetrics();
        CountDownLatch entered = new CountDownLatch(8);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for(int index = 0; index < 8; index++)
        {
            Thread thread = Thread.ofPlatform().start(() -> {
                try
                {
                    WebDiagnosticRequestMetrics.Ticket ticket = metrics.begin("web_log_download", 2);
                    entered.countDown();
                    release.await();
                    metrics.finish(ticket, 1_000_000, 200, false, 100);
                }
                catch(Throwable exception) { failure.compareAndSet(null, exception); }
            });
            threads.add(thread);
        }
        try
        {
            assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));
            Map<String,Object> snapshot = metrics.snapshotAndReset(2, 1);
            assertEquals(8L, snapshot.get("started"));
            assertEquals(8L, snapshot.get("active"));
            assertEquals(8L, operation(snapshot, "web_log_download").get("peak_active"));
        }
        finally
        {
            release.countDown();
            for(Thread thread: threads) thread.join(2000);
        }
        assertNull(failure.get());
        Map<String,Object> completed = metrics.snapshotAndReset(2, 2);
        assertEquals(8L, completed.get("completed"));
        assertEquals(0L, completed.get("active"));
        assertEquals(800L, operation(completed, "web_log_download").get("declared_response_bytes"));
    }

    private static Map<?,?> operation(Map<String,Object> snapshot, String name)
    {
        return (Map<?,?>)((Map<?,?>)snapshot.get("operations")).get(name);
    }
}
