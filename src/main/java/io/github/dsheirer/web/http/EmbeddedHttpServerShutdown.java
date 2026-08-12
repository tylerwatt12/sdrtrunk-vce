/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.web.http;

import com.sun.net.httpserver.HttpServer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded shutdown for the JDK embedded HTTP server.
 *
 * <p>{@link HttpServer#stop(int)} normally closes the listening socket before it waits for active exchanges, but a
 * TLS exchange whose peer is not reading can leave that stop call waiting in JDK socket cleanup.  Listener shutdown
 * must not hold the application lifecycle lock indefinitely.  The request executor is interrupted first, and the
 * potentially blocking JDK stop is then owned by one daemon thread while the lifecycle caller waits only for the
 * fixed shutdown budget.</p>
 */
public final class EmbeddedHttpServerShutdown
{
    private static final Logger mLog = LoggerFactory.getLogger(EmbeddedHttpServerShutdown.class);
    private static final Duration STOP_WAIT = Duration.ofSeconds(2);
    static final String STOPPER_THREAD_NAME = "embedded HTTP server stopper";

    private EmbeddedHttpServerShutdown()
    {
    }

    /**
     * Stops accepting new requests and gives the JDK server a bounded opportunity to release active exchanges.
     *
     * @return {@code true} when {@code HttpServer.stop(0)} completed successfully inside the shutdown budget;
     * otherwise {@code false}.  On timeout, the named daemon continues the best-effort stop without holding the
     * application lifecycle caller.
     */
    public static boolean stop(HttpServer server, ExecutorService executor)
    {
        return stop(server, executor, STOP_WAIT);
    }

    static boolean stop(HttpServer server, ExecutorService executor, Duration wait)
    {
        Objects.requireNonNull(server, "HTTP server cannot be null");
        Objects.requireNonNull(executor, "HTTP server executor cannot be null");
        Objects.requireNonNull(wait, "HTTP server stop wait cannot be null");

        if(wait.isNegative() || wait.isZero())
        {
            throw new IllegalArgumentException("HTTP server stop wait must be positive");
        }

        try
        {
            executor.shutdownNow();
        }
        catch(RuntimeException exception)
        {
            //Stopping the listener is still the more important lifecycle operation.
            mLog.warn("Unable to interrupt the embedded HTTP server request executor", exception);
        }

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread stopper = new Thread(() -> {
            try
            {
                server.stop(0);
            }
            catch(Throwable throwable)
            {
                failure.set(throwable);
            }
        }, STOPPER_THREAD_NAME);
        stopper.setDaemon(true);
        stopper.start();

        try
        {
            long waitMillis = Math.max(1, wait.toMillis());
            stopper.join(waitMillis);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            mLog.warn("Interrupted while waiting for the embedded HTTP server to stop");
            return false;
        }

        if(stopper.isAlive())
        {
            mLog.warn("Embedded HTTP server stop exceeded [{}] ms; shutdown continues on the daemon stopper",
                wait.toMillis());
            return false;
        }

        Throwable throwable = failure.get();

        if(throwable != null)
        {
            mLog.warn("Embedded HTTP server stop failed", throwable);
            return false;
        }

        return true;
    }
}
