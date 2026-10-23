/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class StatsWebSnapshotTest
{
    @Test
    void twoHundredVisitorsShareOneRead() throws Exception
    {
        AtomicInteger reads = new AtomicInteger();
        StatsWebSnapshot<Integer> snapshot = new StatsWebSnapshot<>(Duration.ofSeconds(2), () -> {
            reads.incrementAndGet();
            return 42;
        });
        try(var clients = Executors.newVirtualThreadPerTaskExecutor())
        {
            var results = java.util.stream.IntStream.range(0, 200)
                .mapToObj(index -> clients.submit(snapshot::get)).toList();
            for(var result: results) assertEquals(42, result.get(2, TimeUnit.SECONDS));
        }
        assertEquals(1, reads.get());
    }

    @Test
    void visitorsUseConfirmedDataWhileOneWebWorkerRefreshes() throws Exception
    {
        AtomicLong clock = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch refreshing = new CountDownLatch(1), release = new CountDownLatch(1);
        StatsWebSnapshot<Integer> snapshot = new StatsWebSnapshot<>(Duration.ofNanos(10), () -> {
            if(reads.incrementAndGet() == 2)
            {
                refreshing.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                catch(InterruptedException error) { throw new AssertionError(error); }
            }
            return reads.get();
        }, clock::get);
        assertEquals(1, snapshot.get());
        clock.set(11);
        try(var worker = Executors.newVirtualThreadPerTaskExecutor())
        {
            var result = worker.submit(snapshot::get);
            assertTrue(refreshing.await(2, TimeUnit.SECONDS));
            try
            {
                assertTimeoutPreemptively(Duration.ofMillis(200), () -> assertEquals(1, snapshot.get()));
            }
            finally { release.countDown(); }
            assertEquals(2, result.get(2, TimeUnit.SECONDS));
        }
        assertEquals(2, snapshot.get());
        assertEquals(2, reads.get());
    }

    @Test
    void twoHundredColdFailuresShareOneReadAndRetryAfterTheCooldown() throws Exception
    {
        AtomicLong clock = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        StatsWebSnapshot<Integer> snapshot = new StatsWebSnapshot<>(Duration.ofSeconds(1), () -> {
            if(reads.incrementAndGet() == 1) throw new IllegalStateException("Unavailable");
            return 2;
        }, clock::get);
        try(var clients = Executors.newVirtualThreadPerTaskExecutor())
        {
            var results = java.util.stream.IntStream.range(0, 200)
                .mapToObj(index -> clients.submit(() -> assertThrows(StatsApiException.class, snapshot::get))).toList();
            for(var result: results)
            {
                StatsApiException error = result.get(2, TimeUnit.SECONDS);
                assertEquals(503, error.status());
                assertEquals("snapshot_unavailable", error.code());
            }
        }
        assertEquals(1, reads.get(), "Failed cold reads must not turn into a serialized retry train");
        clock.set(TimeUnit.SECONDS.toNanos(1));
        assertEquals(2, snapshot.get());
        assertEquals(2, reads.get());
    }

    @Test
    void failedRefreshKeepsConfirmedDataOnlyWithinTheStaleGraceAndRecovers()
    {
        AtomicLong clock = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        AtomicBoolean unavailable = new AtomicBoolean();
        StatsWebSnapshot<Integer> snapshot = new StatsWebSnapshot<>(Duration.ofSeconds(2), () -> {
            int value = reads.incrementAndGet();
            if(unavailable.get()) throw new IllegalStateException("Unavailable");
            return value;
        }, clock::get);
        assertEquals(1, snapshot.get());
        unavailable.set(true);
        clock.set(TimeUnit.SECONDS.toNanos(3));
        assertEquals(1, snapshot.get(), "A failed refresh may use recent confirmed data");
        for(int visitor = 0; visitor < 200; visitor++) assertEquals(1, snapshot.get());
        assertEquals(2, reads.get());
        clock.set(TimeUnit.SECONDS.toNanos(13));
        assertEquals(503, assertThrows(StatsApiException.class, snapshot::get).status());
        assertEquals(3, reads.get());
        unavailable.set(false);
        clock.set(TimeUnit.SECONDS.toNanos(14));
        assertEquals(4, snapshot.get());
        assertEquals(4, snapshot.get());
        assertEquals(4, reads.get());
    }

    @Test
    void twoHundredExpiredVisitorsWaitForTheSharedRefresh() throws Exception
    {
        AtomicLong clock = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch refreshing = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch visitorsStarted = new CountDownLatch(200);
        StatsWebSnapshot<Integer> snapshot = new StatsWebSnapshot<>(Duration.ofSeconds(2), () -> {
            int value = reads.incrementAndGet();
            if(value == 2)
            {
                refreshing.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch(InterruptedException exception) { throw new AssertionError(exception); }
            }
            return value;
        }, clock::get);
        assertEquals(1, snapshot.get());
        clock.set(TimeUnit.SECONDS.toNanos(13));
        try(var clients = Executors.newVirtualThreadPerTaskExecutor())
        {
            var refresh = clients.submit(snapshot::get);
            assertTrue(refreshing.await(1, TimeUnit.SECONDS));
            var results = java.util.stream.IntStream.range(0, 200).mapToObj(index -> clients.submit(() -> {
                visitorsStarted.countDown();
                return snapshot.get();
            })).toList();
            try
            {
                assertTrue(visitorsStarted.await(1, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> results.getFirst().get(50, TimeUnit.MILLISECONDS),
                    "Expired visitors should wait for the shared read instead of returning an immediate error");
            }
            finally { release.countDown(); }
            assertEquals(2, refresh.get(1, TimeUnit.SECONDS));
            for(var result: results) assertEquals(2, result.get(1, TimeUnit.SECONDS));
        }
        assertEquals(2, reads.get(), "Visitors must reuse the completed refresh without another read");
    }

    @Test
    void expiredWaitersShareRefreshFailureAndRespectTheCooldown() throws Exception
    {
        AtomicLong clock = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch refreshing = new CountDownLatch(1), release = new CountDownLatch(1);
        CountDownLatch visitorStarted = new CountDownLatch(1);
        StatsWebSnapshot<Integer> snapshot = new StatsWebSnapshot<>(Duration.ofSeconds(2), () -> {
            int value = reads.incrementAndGet();
            if(value == 2)
            {
                refreshing.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch(InterruptedException exception) { throw new AssertionError(exception); }
                throw new IllegalStateException("Unavailable");
            }
            return value;
        }, clock::get);
        assertEquals(1, snapshot.get());
        clock.set(TimeUnit.SECONDS.toNanos(13));
        try(var clients = Executors.newVirtualThreadPerTaskExecutor())
        {
            var refresh = clients.submit(() -> assertThrows(StatsApiException.class, snapshot::get));
            assertTrue(refreshing.await(1, TimeUnit.SECONDS));
            var waiter = clients.submit(() -> {
                visitorStarted.countDown();
                return assertThrows(StatsApiException.class, snapshot::get);
            });
            try
            {
                assertTrue(visitorStarted.await(1, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> waiter.get(50, TimeUnit.MILLISECONDS));
            }
            finally { release.countDown(); }
            assertEquals(503, refresh.get(1, TimeUnit.SECONDS).status());
            assertEquals(503, waiter.get(1, TimeUnit.SECONDS).status());
        }
        for(int visitor = 0; visitor < 200; visitor++)
            assertEquals(503, assertThrows(StatsApiException.class, snapshot::get).status());
        assertEquals(2, reads.get(), "A failed shared refresh must not create a retry train");
        clock.set(TimeUnit.SECONDS.toNanos(14));
        assertEquals(3, snapshot.get());
        assertEquals(3, reads.get());
    }

    @Test
    void expiredWaitersHaveABoundedWaitWithoutExposingOldData() throws Exception
    {
        AtomicLong clock = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch refreshing = new CountDownLatch(1), release = new CountDownLatch(1);
        StatsWebSnapshot<Integer> snapshot = new StatsWebSnapshot<>(Duration.ofSeconds(2), () -> {
            if(reads.incrementAndGet() == 2)
            {
                refreshing.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch(InterruptedException exception) { throw new AssertionError(exception); }
            }
            return reads.get();
        }, clock::get);
        assertEquals(1, snapshot.get());
        clock.set(TimeUnit.SECONDS.toNanos(3));
        try(var worker = Executors.newVirtualThreadPerTaskExecutor())
        {
            var refresh = worker.submit(snapshot::get);
            assertTrue(refreshing.await(1, TimeUnit.SECONDS));
            try
            {
                assertEquals(1, snapshot.get());
                clock.set(TimeUnit.SECONDS.toNanos(13));
                assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertEquals(503, assertThrows(StatsApiException.class, snapshot::get).status()));
                assertEquals(2, reads.get(), "A timed-out visitor must never create another reader");
            }
            finally { release.countDown(); }
            assertEquals(2, refresh.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void coldWaitersHaveABoundedWaitWhileTheSingleReaderIsBlocked() throws Exception
    {
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        StatsWebSnapshot<Integer> snapshot = new StatsWebSnapshot<>(Duration.ofSeconds(2), () -> {
            reads.incrementAndGet();
            reading.countDown();
            try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
            catch(InterruptedException exception) { throw new AssertionError(exception); }
            return 1;
        });
        try(var worker = Executors.newVirtualThreadPerTaskExecutor())
        {
            var refresh = worker.submit(snapshot::get);
            assertTrue(reading.await(1, TimeUnit.SECONDS));
            try
            {
                assertTimeoutPreemptively(Duration.ofSeconds(3), () ->
                    assertEquals(503, assertThrows(StatsApiException.class, snapshot::get).status()));
                assertEquals(1, reads.get(), "A timed-out visitor must never create another reader");
            }
            finally { release.countDown(); }
            assertEquals(1, refresh.get(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void coldWaitersRespectShutdownInterruptionWhileTheSingleReadContinues() throws Exception
    {
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        StatsWebSnapshot<Integer> snapshot = new StatsWebSnapshot<>(Duration.ofSeconds(2), () -> {
            reading.countDown();
            try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
            catch(InterruptedException exception) { throw new AssertionError(exception); }
            return 1;
        });
        try(var worker = Executors.newVirtualThreadPerTaskExecutor())
        {
            var refresh = worker.submit(snapshot::get);
            assertTrue(reading.await(1, TimeUnit.SECONDS));
            try
            {
                Thread.currentThread().interrupt();
                assertEquals(503, assertThrows(StatsApiException.class, snapshot::get).status());
                assertTrue(Thread.currentThread().isInterrupted());
            }
            finally
            {
                Thread.interrupted();
                release.countDown();
            }
            assertEquals(1, refresh.get(1, TimeUnit.SECONDS));
        }
    }
}
