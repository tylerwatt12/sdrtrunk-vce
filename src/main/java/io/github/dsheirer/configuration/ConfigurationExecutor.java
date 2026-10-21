/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.configuration;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** One configuration command owner, independent of windows and never used by sample/decoder callbacks. */
public final class ConfigurationExecutor implements AutoCloseable
{
    private static final long WAIT_SECONDS = 15;
    private final Object mCallAdmission = new Object();
    private boolean mAcceptingCalls = true;
    private CompletableFuture<?> mLastCall = CompletableFuture.completedFuture(null);
    private volatile Thread mOwner;
    private final ThreadPoolExecutor mExecutor = new ThreadPoolExecutor(1, 1, 10, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(256), task ->
        {
            Thread thread = new Thread(task, "receiver-configuration");
            thread.setDaemon(true);
            mOwner = thread;
            return thread;
        });

    public ConfigurationExecutor()
    {
        mExecutor.allowCoreThreadTimeOut(true);
    }

    public <T> T call(Supplier<T> operation)
    {
        Objects.requireNonNull(operation);
        if(Thread.currentThread() == mOwner) return operation.get();
        Command<T> command;
        synchronized(mCallAdmission)
        {
            if(!mAcceptingCalls) throw new BusyException();
            command = submitCommand(operation);
            mLastCall = command.result;
        }
        try
        {
            return command.result.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }
        catch(TimeoutException exception)
        {
            if(command.cancelBeforeStart()) throw new BusyException();
            return join(command.result);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            if(command.cancelBeforeStart()) throw new BusyException();
            return join(command.result);
        }
        catch(ExecutionException exception)
        {
            throw propagate(exception.getCause());
        }
    }

    /** Nonblocking admission for learned configuration, already offloaded from decoder callbacks. */
    public CompletableFuture<Void> submit(Runnable operation)
    {
        Objects.requireNonNull(operation);
        try { return this.<Void>submitCommand(() -> { operation.run(); return null; }).result; }
        catch(BusyException failure) { return CompletableFuture.failedFuture(failure); }
    }

    private <T> Command<T> submitCommand(Supplier<T> operation)
    {
        Command<T> command = new Command<>(Objects.requireNonNull(operation));
        try { mExecutor.execute(command); }
        catch(RejectedExecutionException exception) { throw new BusyException(); }
        return command;
    }

    private static <T> T join(CompletableFuture<T> result)
    {
        try { return result.join(); }
        catch(CompletionException exception) { throw propagate(exception.getCause()); }
    }

    private static RuntimeException propagate(Throwable failure)
    {
        if(failure instanceof Error error) throw error;
        return failure instanceof RuntimeException runtime ? runtime : new CompletionException(failure);
    }

    /** Stop external administration and finish admitted commands; internal observer submissions may still drain. */
    public void quiesceCalls()
    {
        if(Thread.currentThread() == mOwner) throw new IllegalStateException("An active command cannot quiesce itself");
        CompletableFuture<?> lastCall;
        synchronized(mCallAdmission)
        {
            mAcceptingCalls = false;
            lastCall = mLastCall;
        }
        //The last admitted call is also an ordered barrier, even when the bounded queue is full.
        lastCall.handle((_, _) -> null).join();
    }

    @Override
    public void close()
    {
        synchronized(mCallAdmission)
        {
            mAcceptingCalls = false;
            mExecutor.shutdown();
        }
        try
        {
            if(!mExecutor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS))
                throw new IllegalStateException("Configuration commands did not finish before shutdown");
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Configuration shutdown was interrupted", exception);
        }
    }

    public static final class BusyException extends IllegalStateException
    {
        public BusyException() { super("Configuration is busy; try again"); }
    }

    private enum State { QUEUED, RUNNING, CANCELLED }

    private static final class Command<T> implements Runnable
    {
        private final Supplier<T> operation;
        private final CompletableFuture<T> result = new CompletableFuture<>();
        private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);

        private Command(Supplier<T> operation) { this.operation = operation; }
        private boolean cancelBeforeStart() { return state.compareAndSet(State.QUEUED, State.CANCELLED); }

        @Override public void run()
        {
            if(!state.compareAndSet(State.QUEUED, State.RUNNING))
            {
                result.completeExceptionally(new BusyException());
                return;
            }
            try { result.complete(operation.get()); }
            catch(Throwable failure) { result.completeExceptionally(failure); }
        }
    }
}
