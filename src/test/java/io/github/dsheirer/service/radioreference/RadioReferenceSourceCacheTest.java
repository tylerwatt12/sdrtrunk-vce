/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.service.radioreference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.service.radioreference.RadioReferenceGatewayException.Kind;
import io.github.dsheirer.service.radioreference.RadioReferenceSourceCache.Source;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RadioReferenceSourceCacheTest
{
    @Test
    void retainsImmutableValuesAndSeparatesTypedSourcesAndIds() throws Exception
    {
        AtomicLong time = new AtomicLong();
        try(var cache = cache(10, 100, time))
        {
            List<String> rows = List.of("one", "two");
            assertSame(rows, cache.get(Source.STATE, 1, false, () -> rows));
            assertSame(rows, cache.get(Source.STATE, 1, false, () -> { throw new AssertionError("cache miss"); }));
            assertEquals("county", cache.get(Source.COUNTY, 1, false, () -> "county"));
            assertEquals("another state", cache.get(Source.STATE, 2, false, () -> "another state"));
        }
    }

    @Test
    void lifetimeStartsAtSuccessfulPublicationAndRefreshRenewsIt() throws Exception
    {
        AtomicLong time = new AtomicLong();
        long lifetime = Duration.ofMinutes(5).toNanos();
        try(var cache = cache(10, 100, time))
        {
            assertEquals("first", cache.get(Source.STATE, 1, false, () -> {
                time.set(lifetime);
                return "first";
            }));
            time.addAndGet(lifetime - 1);
            assertEquals("first", cache.get(Source.STATE, 1, false, () -> "unexpected"));
            time.incrementAndGet();
            assertEquals("second", cache.get(Source.STATE, 1, false, () -> "second"));
            time.addAndGet(lifetime - 1);
            assertEquals("refreshed", cache.get(Source.STATE, 1, true, () -> "refreshed"));
            time.addAndGet(lifetime - 1);
            assertEquals("refreshed", cache.get(Source.STATE, 1, false, () -> "unexpected"));
        }
    }

    @Test
    void evictsLeastRecentlyUsedValuesAtEntryBound() throws Exception
    {
        AtomicLong time = new AtomicLong();
        AtomicInteger secondLoads = new AtomicInteger();
        try(var cache = cache(2, 100, time))
        {
            cache.get(Source.STATE, 1, false, () -> "first");
            cache.get(Source.STATE, 2, false, () -> "second-" + secondLoads.incrementAndGet());
            assertEquals("first", cache.get(Source.STATE, 1, false, () -> "unexpected"));
            cache.get(Source.STATE, 3, false, () -> "third");
            assertEquals("first", cache.get(Source.STATE, 1, false, () -> "unexpected"));
            assertEquals("second-2", cache.get(Source.STATE, 2, false,
                () -> "second-" + secondLoads.incrementAndGet()));
        }
    }

    @Test
    void boundsAggregateWeightAndNeverRetainsOversizedResults() throws Exception
    {
        AtomicLong time = new AtomicLong();
        AtomicInteger firstLoads = new AtomicInteger();
        AtomicInteger largeLoads = new AtomicInteger();
        try(var cache = cache(10, 5, time))
        {
            cache.get(Source.STATE, 1, false, () -> { firstLoads.incrementAndGet(); return "aaa"; });
            cache.get(Source.STATE, 2, false, () -> "bb");
            cache.get(Source.STATE, 3, false, () -> "ccc");
            assertEquals("bb", cache.get(Source.STATE, 2, false, () -> "unexpected"));
            cache.get(Source.STATE, 1, false, () -> { firstLoads.incrementAndGet(); return "aaa"; });
            assertEquals(2, firstLoads.get());
            for(int index = 0; index < 2; index++)
                assertEquals("oversized", cache.get(Source.STATE, 4, false,
                    () -> { largeLoads.incrementAndGet(); return "oversized"; }));
            assertEquals(2, largeLoads.get());
            assertEquals("aaa", cache.get(Source.STATE, 1, false, () -> "unexpected"));
        }
    }

    @Test
    void failedRefreshRetainsPreviousValueAndOriginalExpiry() throws Exception
    {
        AtomicLong time = new AtomicLong();
        long lifetime = Duration.ofMinutes(5).toNanos();
        try(var cache = cache(10, 100, time))
        {
            cache.get(Source.STATE, 1, false, () -> "previous");
            time.set(lifetime - 1);
            assertEquals(Kind.HTTP_ERROR, assertThrows(RadioReferenceGatewayException.class,
                () -> cache.get(Source.STATE, 1, true, () -> { throw new RadioReferenceGatewayException(Kind.HTTP_ERROR); })).kind());
            assertEquals("previous", cache.get(Source.STATE, 1, false, () -> "unexpected"));
            time.set(lifetime);
            assertEquals("new", cache.get(Source.STATE, 1, false, () -> "new"));
        }
    }

    @Test
    void coalescesInlineOwnerAndLetsOrdinaryReadersUsePriorValueDuringRefresh() throws Exception
    {
        AtomicLong time = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger loads = new AtomicInteger();
        try(var cache = cache(10, 100, time))
        {
            cache.get(Source.STATE, 1, false, () -> "previous");
            Task<String> owner = start(() -> cache.get(Source.STATE, 1, true, () -> {
                loads.incrementAndGet();
                entered.countDown();
                awaitSource(release);
                return "refreshed";
            }));
            try
            {
                await(entered);
                assertEquals("previous", cache.get(Source.STATE, 1, false, () -> "unexpected"));
                Task<String> waiter = start(() -> cache.get(Source.STATE, 1, true,
                    () -> { throw new AssertionError("second owner"); }));
                awaitWaiting(waiter.thread());
                release.countDown();
                assertEquals("refreshed", owner.result().get(2, TimeUnit.SECONDS));
                assertEquals("refreshed", waiter.result().get(2, TimeUnit.SECONDS));
                assertEquals(1, loads.get());
                Thread caller = Thread.currentThread();
                cache.get(Source.STATE, 2, false, () -> { assertSame(caller, Thread.currentThread()); return "inline"; });
            }
            finally { release.countDown(); }
        }
    }

    @Test
    void interruptedWaiterLeavesOwnerAndOtherReadersIntact() throws Exception
    {
        AtomicLong time = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        try(var cache = cache(10, 100, time))
        {
            Task<String> owner = start(() -> cache.get(Source.STATE, 1, false, () -> {
                entered.countDown();
                awaitSource(release);
                return "loaded";
            }));
            try
            {
                await(entered);
                Task<String> waiter = start(() -> {
                    try { return cache.get(Source.STATE, 1, false, () -> "unexpected"); }
                    finally { interruptPreserved.set(Thread.currentThread().isInterrupted()); }
                });
                awaitWaiting(waiter.thread());
                waiter.thread().interrupt();
                assertEquals(Kind.INTERRUPTED, failure(waiter).kind());
                assertTrue(interruptPreserved.get());
                assertFalse(owner.thread().isInterrupted());
                release.countDown();
                assertEquals("loaded", owner.result().get(2, TimeUnit.SECONDS));
                assertEquals("loaded", cache.get(Source.STATE, 1, false, () -> "unexpected"));
            }
            finally { release.countDown(); }
        }
    }

    @Test
    void ownerFailureReleasesWaitersAndAllowsFreshRetry() throws Exception
    {
        AtomicLong time = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try(var cache = cache(10, 100, time))
        {
            Task<String> owner = start(() -> cache.get(Source.STATE, 1, false, () -> {
                entered.countDown();
                awaitSource(release);
                throw new RadioReferenceGatewayException(Kind.INVALID_RESPONSE);
            }));
            try
            {
                await(entered);
                Task<String> waiter = start(() -> cache.get(Source.STATE, 1, false, () -> "unexpected"));
                awaitWaiting(waiter.thread());
                release.countDown();
                assertEquals(Kind.INVALID_RESPONSE, failure(owner).kind());
                assertEquals(Kind.INVALID_RESPONSE, failure(waiter).kind());
                assertEquals("retry", cache.get(Source.STATE, 1, false, () -> "retry"));
            }
            finally { release.countDown(); }
        }
    }

    @Test
    void interruptedOwnerCannotPublishItsResult() throws Exception
    {
        AtomicLong time = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try(var cache = cache(10, 100, time))
        {
            Task<String> owner = start(() -> cache.get(Source.STATE, 1, false, () -> {
                entered.countDown();
                try { release.await(2, TimeUnit.SECONDS); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                return "interrupted result";
            }));
            try
            {
                await(entered);
                owner.thread().interrupt();
                assertEquals(Kind.INTERRUPTED, failure(owner).kind());
                assertEquals("retry", cache.get(Source.STATE, 1, false, () -> "retry"));
            }
            finally { release.countDown(); }
        }
    }

    @Test
    void activeLongerWaiterRetriesInlineAfterShorterOwnerIsInterrupted() throws Exception
    {
        AtomicLong time = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger retryLoads = new AtomicInteger();
        AtomicReference<Thread> retryThread = new AtomicReference<>();
        try(var cache = cache(10, 100, time))
        {
            Task<String> owner = start(() -> cache.get(Source.STATE, 1, false, () -> {
                entered.countDown();
                try { release.await(2, TimeUnit.SECONDS); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                return "cancelled owner result";
            }));
            try
            {
                await(entered);
                Task<String> waiter = start(() -> cache.get(Source.STATE, 1, false, () -> {
                    retryLoads.incrementAndGet();
                    retryThread.set(Thread.currentThread());
                    return "longer caller result";
                }));
                awaitWaiting(waiter.thread());
                owner.thread().interrupt();
                assertEquals(Kind.INTERRUPTED, failure(owner).kind());
                assertEquals("longer caller result", waiter.result().get(2, TimeUnit.SECONDS));
                assertSame(waiter.thread(), retryThread.get());
                assertEquals(1, retryLoads.get());
                assertEquals("longer caller result", cache.get(Source.STATE, 1, false,
                    () -> { throw new AssertionError("active caller result was not retained"); }));
            }
            finally { release.countDown(); }
        }
    }

    @Test
    void closeReleasesWaitersInterruptsOwnersAndRejectsLateResults() throws Exception
    {
        AtomicLong time = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        var cache = cache(10, 100, time);
        Task<String> owner = start(() -> cache.get(Source.STATE, 1, false, () -> {
            entered.countDown();
            boolean done = false;
            while(!done)
            {
                try { done = release.await(2, TimeUnit.SECONDS); }
                catch(InterruptedException exception) { interrupted.countDown(); }
            }
            return "late result";
        }));
        try
        {
            await(entered);
            Task<String> waiter = start(() -> cache.get(Source.STATE, 1, false, () -> "unexpected"));
            awaitWaiting(waiter.thread());
            cache.close();
            assertEquals(Kind.UNAVAILABLE, failure(waiter).kind());
            await(interrupted);
            assertFalse(owner.result().isDone());
            assertEquals(Kind.UNAVAILABLE, assertThrows(RadioReferenceGatewayException.class,
                () -> cache.get(Source.STATE, 1, false, () -> "after close")).kind());
            release.countDown();
            assertEquals(Kind.UNAVAILABLE, failure(owner).kind());
            cache.close();
        }
        finally
        {
            release.countDown();
            cache.close();
        }
    }

    @Test
    void boundsPendingKeysWithoutRefusingWaitersForAnAdmittedKey() throws Exception
    {
        AtomicLong time = new AtomicLong();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try(var cache = cache(1, 100, time))
        {
            Task<String> owner = start(() -> cache.get(Source.STATE, 1, false, () -> {
                entered.countDown();
                awaitSource(release);
                return "first";
            }));
            try
            {
                await(entered);
                assertEquals(Kind.UNAVAILABLE, assertThrows(RadioReferenceGatewayException.class,
                    () -> cache.get(Source.STATE, 2, false, () -> "second")).kind());
                Task<String> waiter = start(() -> cache.get(Source.STATE, 1, false, () -> "unexpected"));
                awaitWaiting(waiter.thread());
                release.countDown();
                assertEquals("first", owner.result().get(2, TimeUnit.SECONDS));
                assertEquals("first", waiter.result().get(2, TimeUnit.SECONDS));
            }
            finally { release.countDown(); }
        }
    }

    @Test
    void sanitizesUnexpectedLoaderFailureWithoutRetainingIt() throws Exception
    {
        AtomicLong time = new AtomicLong();
        try(var cache = cache(10, 100, time))
        {
            RadioReferenceGatewayException exception = assertThrows(RadioReferenceGatewayException.class,
                () -> cache.get(Source.STATE, 1, false, () -> { throw new IllegalStateException("private request details"); }));
            assertEquals(Kind.UNAVAILABLE, exception.kind());
            assertNull(exception.getCause());
            assertFalse(exception.getMessage().contains("private"));
            assertEquals("retry", cache.get(Source.STATE, 1, false, () -> "retry"));
        }
    }

    private static RadioReferenceSourceCache cache(int entries, long weight, AtomicLong time)
    {
        return new RadioReferenceSourceCache(entries, weight, Duration.ofMinutes(5),
            value -> value instanceof String text ? text.length() : ((List<?>)value).size(), time::get);
    }

    private static void await(CountDownLatch latch) throws InterruptedException
    {
        assertTrue(latch.await(2, TimeUnit.SECONDS), "source did not reach the expected state");
    }

    private static void awaitSource(CountDownLatch latch) throws RadioReferenceGatewayException
    {
        try
        {
            if(!latch.await(2, TimeUnit.SECONDS)) throw new RadioReferenceGatewayException(Kind.TIMEOUT);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new RadioReferenceGatewayException(Kind.INTERRUPTED);
        }
    }

    private static void awaitWaiting(Thread thread) throws InterruptedException
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while(thread.getState() != Thread.State.WAITING && thread.getState() != Thread.State.TIMED_WAITING &&
            thread.isAlive() && System.nanoTime() - deadline < 0) Thread.sleep(1);
        assertTrue(thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING,
            "reader did not wait for the admitted source load");
    }

    private static <T> Task<T> start(Action<T> action)
    {
        CompletableFuture<T> result = new CompletableFuture<>();
        Thread thread = Thread.ofPlatform().daemon().start(() -> {
            try { result.complete(action.run()); }
            catch(Throwable exception) { result.completeExceptionally(exception); }
        });
        return new Task<>(thread, result);
    }

    private static RadioReferenceGatewayException failure(Task<?> task) throws Exception
    {
        ExecutionException exception = assertThrows(ExecutionException.class,
            () -> task.result().get(2, TimeUnit.SECONDS));
        assertTrue(exception.getCause() instanceof RadioReferenceGatewayException);
        return (RadioReferenceGatewayException)exception.getCause();
    }

    @FunctionalInterface private interface Action<T> { T run() throws Exception; }
    private record Task<T>(Thread thread, CompletableFuture<T> result) {}
}
