/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StatsBrowseReadCacheTest
{
    @Test
    void twoHundredIdenticalVisitorsShareOneReadAndPreparedResponse() throws Exception
    {
        StatsBrowseReadCache cache = new StatsBrowseReadCache(4);
        Object catalog = new Object();
        AtomicInteger reads = new AtomicInteger();
        byte[] prepared = "prepared response".getBytes(StandardCharsets.UTF_8);
        try(var visitors = Executors.newVirtualThreadPerTaskExecutor())
        {
            var responses = java.util.stream.IntStream.range(0, 200).mapToObj(index -> visitors.submit(() ->
                cache.get(catalog, URI.create("/calls?limit=25"), () -> {
                    reads.incrementAndGet();
                    return prepared;
                }))).toList();
            for(var response: responses) assertSame(prepared, response.get(2, TimeUnit.SECONDS));
        }
        assertEquals(1, reads.get());
    }

    @Test
    void twoHundredDistinctRollingPeriodsWaitForOnlyFourDatabaseReaders() throws Exception
    {
        StatsBrowseReadCache cache = new StatsBrowseReadCache(4);
        Object catalog = new Object();
        AtomicInteger reads = new AtomicInteger(), active = new AtomicInteger(), maximum = new AtomicInteger();
        try(var visitors = Executors.newVirtualThreadPerTaskExecutor())
        {
            var responses = java.util.stream.IntStream.range(0, 200).mapToObj(index -> visitors.submit(() ->
                cache.get(catalog, URI.create("/calls?from_ms=" + index + "&to_ms=" + (index + 86_400_000)), () -> {
                    reads.incrementAndGet();
                    maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
                    try { Thread.sleep(5); }
                    catch(InterruptedException exception) { throw new AssertionError(exception); }
                    finally { active.decrementAndGet(); }
                    return Integer.toString(index).getBytes(StandardCharsets.UTF_8);
                }))).toList();
            for(int index = 0; index < responses.size(); index++)
                assertEquals(Integer.toString(index), new String(responses.get(index).get(2, TimeUnit.SECONDS),
                    StandardCharsets.UTF_8));
        }
        assertEquals(200, reads.get(), "Exact caller time bounds must never be rounded into another query");
        assertTrue(maximum.get() <= 4, "Visitors must not raise database query concurrency");
        assertTrue(maximum.get() > 1);
    }

    @Test
    void queriesAndCatalogInstancesHaveSeparateResponses() throws Exception
    {
        StatsBrowseReadCache cache = new StatsBrowseReadCache(1);
        Object first = new Object(), replacement = new Object();
        URI one = URI.create("/calls?q=one&limit=25"), two = URI.create("/calls?q=two&limit=25");
        byte[] firstResponse = new byte[]{1}, secondResponse = new byte[]{2}, replacedResponse = new byte[]{3};
        assertSame(firstResponse, cache.get(first, one, () -> firstResponse));
        assertSame(secondResponse, cache.get(first, two, () -> secondResponse));
        assertSame(replacedResponse, cache.get(replacement, one, () -> replacedResponse));
        assertSame(firstResponse, cache.get(first, one, () -> fail("The first catalog/query must retain its response")));
    }

    @Test
    void completedEntryAndByteLimitsEvictWithoutRejectingNewVisitors() throws Exception
    {
        StatsBrowseReadCache cache = new StatsBrowseReadCache(1);
        Object catalog = new Object();
        AtomicInteger reads = new AtomicInteger();
        for(int index = 0; index <= StatsBrowseReadCache.MAXIMUM_COMPLETED_READS; index++)
            cache.get(catalog, URI.create("/calls?offset=" + index), () -> { reads.incrementAndGet(); return new byte[1]; });
        cache.get(catalog, URI.create("/calls?offset=0"), () -> { reads.incrementAndGet(); return new byte[1]; });
        assertEquals(StatsBrowseReadCache.MAXIMUM_COMPLETED_READS + 2, reads.get());
        cache.clear();
        byte[] large = new byte[StatsBrowseReadCache.MAXIMUM_RESPONSE_BYTES / 4];
        for(int index = 0; index < 5; index++)
            cache.get(catalog, URI.create("/large?offset=" + index), () -> large);
        byte[] refreshed = new byte[]{7};
        assertSame(refreshed, cache.get(catalog, URI.create("/large?offset=0"), () -> refreshed),
            "The byte budget must evict the oldest prepared response");
    }

    @Test
    void failuresShareOneCooldownAndSuccessfulValuesExpire() throws Exception
    {
        AtomicLong clock = new AtomicLong();
        AtomicInteger reads = new AtomicInteger();
        StatsBrowseReadCache cache = new StatsBrowseReadCache(1, clock::get);
        Object catalog = new Object();
        URI key = URI.create("/calls?limit=25");
        StatsBrowseReadCache.Read read = () -> {
            if(reads.incrementAndGet() == 1) throw new SQLException("Unavailable");
            return new byte[]{2};
        };
        for(int visitor = 0; visitor < 200; visitor++) assertThrows(SQLException.class, () -> cache.get(catalog, key, read));
        assertEquals(1, reads.get());
        clock.set(TimeUnit.SECONDS.toNanos(1));
        assertArrayEquals(new byte[]{2}, cache.get(catalog, key, read));
        clock.set(TimeUnit.SECONDS.toNanos(3));
        assertArrayEquals(new byte[]{2}, cache.get(catalog, key, read));
        assertEquals(3, reads.get());
    }

    @Test
    void invalidationDuringAnOldReadCannotOverwriteFreshMetadata() throws Exception
    {
        StatsBrowseReadCache cache = new StatsBrowseReadCache(2);
        Object catalog = new Object();
        URI key = URI.create("/calls?limit=25");
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        byte[] old = new byte[]{1}, fresh = new byte[]{2};
        try(var visitors = Executors.newVirtualThreadPerTaskExecutor())
        {
            var stale = visitors.submit(() -> cache.get(catalog, key, () -> {
                reading.countDown();
                try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                catch(InterruptedException exception) { throw new AssertionError(exception); }
                return old;
            }));
            assertTrue(reading.await(1, TimeUnit.SECONDS));
            try
            {
                cache.clear();
                assertSame(fresh, cache.get(catalog, key, () -> fresh));
            }
            finally { release.countDown(); }
            assertSame(old, stale.get(1, TimeUnit.SECONDS));
            assertSame(fresh, cache.get(catalog, key, () -> fail("Old metadata must not reappear after mutation")));
        }
    }

    @Test
    void blockedReadsHaveBoundedAdmissionWaitAndDoNotStartAnotherQuery() throws Exception
    {
        StatsBrowseReadCache cache = new StatsBrowseReadCache(1);
        CountDownLatch reading = new CountDownLatch(1), release = new CountDownLatch(1);
        Object catalog = new Object();
        try(var visitors = Executors.newVirtualThreadPerTaskExecutor())
        {
            var blocked = visitors.submit(() -> cache.get(catalog, URI.create("/calls?q=first"), () -> {
                reading.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch(InterruptedException exception) { throw new AssertionError(exception); }
                return new byte[]{1};
            }));
            assertTrue(reading.await(1, TimeUnit.SECONDS));
            try
            {
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                    StatsApiException error = assertThrows(StatsApiException.class, () ->
                        cache.get(catalog, URI.create("/calls?q=second"), () -> fail("Capacity is still occupied")));
                    assertEquals(503, error.status());
                    assertEquals("browse_busy", error.code());
                });
            }
            finally { release.countDown(); }
            assertArrayEquals(new byte[]{1}, blocked.get(1, TimeUnit.SECONDS));
        }
    }
}
