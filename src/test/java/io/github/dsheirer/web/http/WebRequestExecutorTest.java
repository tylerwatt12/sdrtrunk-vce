/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WebRequestExecutorTest
{
    @Test
    void twoHundredOpenHttpExchangesLeaveShortRequestsResponsive() throws Exception
    {
        CountDownLatch connected = new CountDownLatch(200);
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean allVirtual = new AtomicBoolean(true);
        WebRequestExecutor executor = new WebRequestExecutor(256, () -> fail("Unexpected request rejection"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 256);
        server.setExecutor(executor);
        server.createContext("/live", exchange -> {
            if(!Thread.currentThread().isVirtual()) allVirtual.set(false);
            try
            {
                exchange.sendResponseHeaders(200, 0);
                exchange.getResponseBody().write(1);
                exchange.getResponseBody().flush();
                connected.countDown();
                release.await();
            }
            catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.createContext("/status", exchange -> {
            if(!Thread.currentThread().isVirtual()) allVirtual.set(false);
            exchange.sendResponseHeaders(200, 1);
            exchange.getResponseBody().write(7);
            exchange.close();
        });
        server.start();
        URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        List<CompletableFuture<HttpResponse<InputStream>>> connections = new ArrayList<>();

        try(var clientExecutor = Executors.newVirtualThreadPerTaskExecutor();
            HttpClient client = HttpClient.newBuilder().executor(clientExecutor).build())
        {
            try
            {
                for(int index = 0; index < 200; index++)
                    connections.add(client.sendAsync(HttpRequest.newBuilder(base.resolve("/live")).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream()));
                assertTrue(connected.await(10, TimeUnit.SECONDS), "All 200 live exchanges must start");
                HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(base.resolve("/status"))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, response.statusCode());
                assertArrayEquals(new byte[]{7}, response.body());
                assertTrue(allVirtual.get());
            }
            finally
            {
                release.countDown();
                for(var connection: connections)
                    connection.get(5, TimeUnit.SECONDS).body().close();
            }
        }
        finally { assertTrue(EmbeddedHttpServerShutdown.stop(server, executor)); }
    }

    @Test
    void saturationRejectsImmediatelyAndCompletionReleasesCapacity() throws Exception
    {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger rejections = new AtomicInteger();
        try(WebRequestExecutor executor = new WebRequestExecutor(1, rejections::incrementAndGet))
        {
            var task = executor.submit(() -> {
                started.countDown();
                release.await();
                return Thread.currentThread().isVirtual();
            });
            try
            {
                assertTrue(started.await(1, TimeUnit.SECONDS));
                assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> fail("Caller ran task")));
                assertEquals(1, rejections.get());
            }
            finally { release.countDown(); }
            assertTrue(task.get(1, TimeUnit.SECONDS));
            // A submitted Future can become complete immediately before its outer admission wrapper exits.
            CountDownLatch nextStarted = new CountDownLatch(1);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while(true)
            {
                try { executor.execute(nextStarted::countDown); break; }
                catch(RejectedExecutionException exception)
                {
                    if(System.nanoTime() >= deadline) throw exception;
                    Thread.yield();
                }
            }
            assertTrue(nextStarted.await(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void shutdownInterruptsLongRequestsAndRefusesNewWork() throws Exception
    {
        WebRequestExecutor executor = new WebRequestExecutor(2, () -> {});
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        executor.execute(() -> {
            started.countDown();
            try { new CountDownLatch(1).await(); }
            catch(InterruptedException exception) { interrupted.countDown(); }
        });
        assertTrue(started.await(1, TimeUnit.SECONDS));
        executor.shutdownNow();
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        assertThrows(RejectedExecutionException.class, () -> executor.execute(() -> fail("Task after shutdown")));
    }
}
