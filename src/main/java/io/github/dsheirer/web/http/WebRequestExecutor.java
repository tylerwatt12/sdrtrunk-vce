/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.web.http;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Bounded HTTP exchanges on virtual threads. Long-lived browser connections do not occupy a platform worker or
 * prevent a short request from starting. Admission never queues work or runs it on the JDK HTTP dispatcher.
 */
public final class WebRequestExecutor extends AbstractExecutorService
{
    private final ExecutorService mExecutor = Executors.newThreadPerTaskExecutor(
        Thread.ofVirtual().name("stats web request-", 0).factory());
    private final Semaphore mCapacity;
    private final Runnable mOnRejected;

    public WebRequestExecutor(int maximumRequests, Runnable onRejected)
    {
        if(maximumRequests <= 0) throw new IllegalArgumentException("Request capacity must be positive");
        mCapacity = new Semaphore(maximumRequests);
        mOnRejected = Objects.requireNonNull(onRejected);
    }

    @Override
    public void execute(Runnable task)
    {
        Objects.requireNonNull(task);
        if(!mCapacity.tryAcquire())
        {
            mOnRejected.run();
            throw new RejectedExecutionException("Web request capacity is in use");
        }

        try
        {
            mExecutor.execute(() -> {
                try { task.run(); }
                finally { mCapacity.release(); }
            });
        }
        catch(RuntimeException | Error exception)
        {
            mCapacity.release();
            throw exception;
        }
    }

    @Override public void shutdown() { mExecutor.shutdown(); }
    @Override public List<Runnable> shutdownNow() { return mExecutor.shutdownNow(); }
    @Override public boolean isShutdown() { return mExecutor.isShutdown(); }
    @Override public boolean isTerminated() { return mExecutor.isTerminated(); }
    @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException
    { return mExecutor.awaitTermination(timeout, unit); }
}
