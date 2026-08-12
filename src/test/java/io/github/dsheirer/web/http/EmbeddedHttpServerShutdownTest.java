/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EmbeddedHttpServerShutdownTest
{
    @Test
    void interruptsExecutorFirstAndBoundsAStuckServerStop() throws Exception
    {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);
        BlockingHttpServer server = new BlockingHttpServer(executor);

        executor.execute(() -> {
            taskStarted.countDown();

            try
            {
                new CountDownLatch(1).await();
            }
            catch(InterruptedException exception)
            {
                taskInterrupted.countDown();
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(taskStarted.await(1, TimeUnit.SECONDS));

        try
        {
            long started = System.nanoTime();
            boolean completed = EmbeddedHttpServerShutdown.stop(server, executor, Duration.ofMillis(100));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertFalse(completed);
            assertTrue(elapsedMillis < 1_000, "Shutdown caller waited " + elapsedMillis + " ms");
            assertTrue(server.mStopEntered.await(1, TimeUnit.SECONDS));
            assertTrue(executor.isShutdown());
            assertTrue(server.mExecutorWasShutdownAtStop.get());
            assertTrue(taskInterrupted.await(1, TimeUnit.SECONDS));
            assertEquals(EmbeddedHttpServerShutdown.STOPPER_THREAD_NAME, server.mStopThreadName.get());
            assertTrue(server.mStopThreadWasDaemon.get());
        }
        finally
        {
            server.mReleaseStop.countDown();
            assertTrue(server.mStopReturned.await(1, TimeUnit.SECONDS));
            executor.shutdownNow();
        }
    }

    @Test
    void ordinaryStopReleasesTheSamePortForAnImmediateRebind() throws Exception
    {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 0);
        ExecutorService firstExecutor = Executors.newSingleThreadExecutor();
        HttpServer first = HttpServer.create(address, 0);
        first.setExecutor(firstExecutor);
        first.createContext("/", exchange -> exchange.close());
        first.start();
        int port = first.getAddress().getPort();
        assertTrue(EmbeddedHttpServerShutdown.stop(first, firstExecutor));

        ExecutorService replacementExecutor = Executors.newSingleThreadExecutor();
        HttpServer replacement = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        replacement.setExecutor(replacementExecutor);
        replacement.createContext("/", exchange -> exchange.close());

        try
        {
            replacement.start();
            assertEquals(port, replacement.getAddress().getPort());
        }
        finally
        {
            assertTrue(EmbeddedHttpServerShutdown.stop(replacement, replacementExecutor));
        }
    }

    private static final class BlockingHttpServer extends HttpServer
    {
        private final ExecutorService mExecutor;
        private final CountDownLatch mStopEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseStop = new CountDownLatch(1);
        private final CountDownLatch mStopReturned = new CountDownLatch(1);
        private final AtomicBoolean mExecutorWasShutdownAtStop = new AtomicBoolean();
        private final AtomicBoolean mStopThreadWasDaemon = new AtomicBoolean();
        private final AtomicReference<String> mStopThreadName = new AtomicReference<>();

        private BlockingHttpServer(ExecutorService executor)
        {
            mExecutor = executor;
        }

        @Override
        public void bind(InetSocketAddress address, int backlog)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void start()
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setExecutor(Executor executor)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public Executor getExecutor()
        {
            return mExecutor;
        }

        @Override
        public void stop(int delay)
        {
            mExecutorWasShutdownAtStop.set(mExecutor.isShutdown());
            mStopThreadName.set(Thread.currentThread().getName());
            mStopThreadWasDaemon.set(Thread.currentThread().isDaemon());
            mStopEntered.countDown();

            try
            {
                mReleaseStop.await();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            finally
            {
                mStopReturned.countDown();
            }
        }

        @Override
        public HttpContext createContext(String path, HttpHandler handler)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public HttpContext createContext(String path)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeContext(String path)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeContext(HttpContext context)
        {
            throw new UnsupportedOperationException();
        }

        @Override
        public InetSocketAddress getAddress()
        {
            return null;
        }
    }
}
