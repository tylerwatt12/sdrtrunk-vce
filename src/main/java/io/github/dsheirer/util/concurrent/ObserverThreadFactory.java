/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.util.concurrent;

import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates named, low-priority daemon threads for loss-tolerant UI, web, statistics, and diagnostic observers.
 */
public final class ObserverThreadFactory implements ThreadFactory
{
    private final String mName;
    private final AtomicInteger mSequence = new AtomicInteger();

    public ObserverThreadFactory(String name)
    {
        mName = Objects.requireNonNull(name, "name cannot be null");
    }

    @Override
    public Thread newThread(Runnable runnable)
    {
        int sequence = mSequence.incrementAndGet();
        Thread thread = new Thread(runnable, sequence == 1 ? mName : mName + " " + sequence);
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    }
}
