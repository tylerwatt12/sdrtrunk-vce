/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.util.concurrent;

import io.github.dsheirer.util.concurrent.ThreadQoS.QoSClass;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Creates named, low-priority daemon threads for loss-tolerant UI, web, statistics, and diagnostic observers.
 */
public final class ObserverThreadFactory implements ThreadFactory
{
    private final String mName;
    private final QoSClass mQoSClass;
    private final AtomicInteger mSequence = new AtomicInteger();

    public ObserverThreadFactory(String name)
    {
        this(name, null);
    }

    /**
     * Creates observer threads with an explicit QoS.  Keep this opt-in because some observer workers participate in
     * completed-call recording and streaming and must not be demoted without a separate product-path audit.
     */
    public ObserverThreadFactory(String name, QoSClass qosClass)
    {
        mName = Objects.requireNonNull(name, "name cannot be null");
        mQoSClass = qosClass;
    }

    @Override
    public Thread newThread(Runnable runnable)
    {
        int sequence = mSequence.incrementAndGet();
        Runnable worker = mQoSClass != null ? ThreadQoS.wrap(mQoSClass, runnable) : runnable;
        Thread thread = new Thread(worker, sequence == 1 ? mName : mName + " " + sequence);
        thread.setDaemon(true);
        thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
        return thread;
    }
}
