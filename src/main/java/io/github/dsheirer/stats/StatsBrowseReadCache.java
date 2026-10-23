/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Shares prepared browse responses while preserving exact filters and a small, fixed database read budget. */
final class StatsBrowseReadCache
{
    static final int MAXIMUM_COMPLETED_READS = 64;
    static final int MAXIMUM_PENDING_READS = 256;
    static final int MAXIMUM_RESPONSE_BYTES = 8 * 1024 * 1024;
    private static final long LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long FAILURE_LIFETIME_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long WAIT_SECONDS = 2;
    private final Semaphore mReaders;
    private final LongSupplier mClock;
    private final Map<Key,Entry> mReads = new LinkedHashMap<>(64, 0.75f, true);
    private int mPendingReads;

    StatsBrowseReadCache(int maximumReaders)
    {
        this(maximumReaders, System::nanoTime);
    }

    StatsBrowseReadCache(int maximumReaders, LongSupplier clock)
    {
        if(maximumReaders < 1) throw new IllegalArgumentException("Read capacity must be positive");
        mReaders = new Semaphore(maximumReaders, true);
        mClock = Objects.requireNonNull(clock);
    }

    byte[] get(Object source, URI uri, Read read) throws IOException, SQLException
    {
        Key key = new Key(source, fingerprint(uri));
        Entry entry;
        boolean owner = false;
        synchronized(mReads)
        {
            trim();
            entry = mReads.get(key);
            if(entry == null)
            {
                if(mPendingReads >= MAXIMUM_PENDING_READS)
                    throw busy();
                entry = new Entry();
                mReads.put(key, entry);
                mPendingReads++;
                owner = true;
            }
        }
        if(!owner) return await(entry);

        boolean admitted = false;
        try
        {
            admitted = mReaders.tryAcquire(WAIT_SECONDS, TimeUnit.SECONDS);
            if(!admitted) throw busy();
            byte[] response = Objects.requireNonNull(read.get());
            complete(entry, response, null);
            return response;
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            RuntimeException failure = busy();
            complete(entry, null, failure);
            throw failure;
        }
        catch(IOException | SQLException | RuntimeException | Error exception)
        {
            complete(entry, null, exception);
            throw exception;
        }
        finally
        {
            if(admitted) mReaders.release();
        }
    }

    private void complete(Entry entry, byte[] response, Throwable failure)
    {
        synchronized(mReads)
        {
            mPendingReads--;
            entry.finishedAt = mClock.getAsLong();
            entry.bytes = response != null ? response.length : 0;
            entry.failed = failure != null;
            if(failure == null) entry.result.complete(response);
            else entry.result.completeExceptionally(failure);
            trim();
        }
    }

    private byte[] await(Entry entry) throws IOException, SQLException
    {
        try { return entry.result.get(WAIT_SECONDS, TimeUnit.SECONDS); }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw busy();
        }
        catch(TimeoutException exception) { throw busy(); }
        catch(ExecutionException exception)
        {
            Throwable cause = exception.getCause();
            if(cause instanceof IOException io) throw io;
            if(cause instanceof SQLException sql) throw sql;
            if(cause instanceof RuntimeException runtime) throw runtime;
            if(cause instanceof Error error) throw error;
            throw new IllegalStateException("Unexpected browse read failure", cause);
        }
    }

    void clear()
    {
        synchronized(mReads) { mReads.clear(); }
    }

    private void trim()
    {
        long now = mClock.getAsLong();
        mReads.values().removeIf(entry -> entry.result.isDone() && now - entry.finishedAt >=
            (entry.failed ? FAILURE_LIFETIME_NANOS : LIFETIME_NANOS));
        int completed = 0;
        long bytes = 0;
        for(Entry entry: mReads.values())
        {
            if(entry.result.isDone()) { completed++; bytes += entry.bytes; }
        }
        Iterator<Entry> entries = mReads.values().iterator();
        while((completed > MAXIMUM_COMPLETED_READS || bytes > MAXIMUM_RESPONSE_BYTES) && entries.hasNext())
        {
            Entry entry = entries.next();
            if(entry.result.isDone()) { completed--; bytes -= entry.bytes; entries.remove(); }
        }
    }

    private static String fingerprint(URI uri)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(uri.toASCIIString().getBytes(StandardCharsets.UTF_8)));
        }
        catch(NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static StatsApiException busy()
    {
        return new StatsApiException(503, "browse_busy", "This page is busy. Try again shortly.");
    }

    @FunctionalInterface
    interface Read { byte[] get() throws IOException, SQLException; }
    private record Key(Object source, String request) {}
    private static final class Entry
    {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private long finishedAt;
        private int bytes;
        private boolean failed;
    }
}
