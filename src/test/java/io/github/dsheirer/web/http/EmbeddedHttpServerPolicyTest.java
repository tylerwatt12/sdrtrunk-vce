/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class EmbeddedHttpServerPolicyTest
{
    @Test
    void incompleteBodiesAreSocketTimedOutAndRequestCapacityRecovers() throws Exception
    {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String testClasses = Path.of(SlowRequestProbe.class.getProtectionDomain().getCodeSource().getLocation()
            .toURI()).toString();
        String mainClasses = Path.of(EmbeddedHttpServerPolicy.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI()).toString();
        String classpath = testClasses.equals(mainClasses) ? testClasses :
            testClasses + java.io.File.pathSeparator + mainClasses;
        Process process = new ProcessBuilder(javaExecutable, "--add-modules=jdk.httpserver", "-cp",
            classpath, SlowRequestProbe.class.getName())
            .redirectErrorStream(true).start();
        boolean completed = process.waitFor(15, TimeUnit.SECONDS);

        if(!completed)
        {
            process.destroyForcibly();
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(completed, output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains("SLOW_REQUEST_PROBE_PASSED"), output);
    }

    /** Runs in a fresh JVM because the JDK caches its HTTP server policy in a static initializer. */
    public static final class SlowRequestProbe
    {
        private SlowRequestProbe()
        {
        }

        public static void main(String[] args) throws Exception
        {
            System.setProperty(EmbeddedHttpServerPolicy.MAXIMUM_REQUEST_TIME_PROPERTY, "1");
            System.setProperty("sun.net.httpserver.timerMillis", "100");
            EmbeddedHttpServerPolicy.configureBeforeServerInitialization();

            if(!"1".equals(System.getProperty(EmbeddedHttpServerPolicy.MAXIMUM_REQUEST_TIME_PROPERTY)))
            {
                throw new AssertionError("The product policy replaced an explicit operator timeout");
            }

            CountDownLatch slowHandlersEntered = new CountDownLatch(2);
            AtomicInteger slowHandlersCompleted = new AtomicInteger();
            ExecutorService executor = Executors.newFixedThreadPool(2);
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
            server.setExecutor(executor);
            server.createContext("/slow", exchange -> slowBody(exchange, slowHandlersEntered,
                slowHandlersCompleted));
            server.createContext("/health", SlowRequestProbe::health);
            server.start();
            List<Socket> stalledClients = new ArrayList<>();

            try
            {
                int port = server.getAddress().getPort();

                for(int index = 0; index < 2; index++)
                {
                    Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                    socket.setSoTimeout(4_000);
                    socket.getOutputStream().write(("POST /slow HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                        "Content-Length: 32\r\nConnection: close\r\n\r\nx")
                        .getBytes(StandardCharsets.US_ASCII));
                    socket.getOutputStream().flush();
                    stalledClients.add(socket);
                }

                if(!slowHandlersEntered.await(2, TimeUnit.SECONDS))
                {
                    throw new AssertionError("Slow request handlers did not start");
                }

                long started = System.nanoTime();
                HttpResponse<String> health = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build()
                    .send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                        .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
                long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

                if(health.statusCode() != HttpURLConnection.HTTP_OK || !"ok".equals(health.body()))
                {
                    throw new AssertionError("Ordinary request failed after slow-body shedding");
                }

                if(elapsedMillis > 4_000 || !waitForCount(slowHandlersCompleted, 2, Duration.ofSeconds(2)))
                {
                    throw new AssertionError("Slow request handlers did not release executor capacity");
                }

                for(Socket socket: stalledClients)
                {
                    assertClosedWithoutWaiting(socket);
                }

                System.out.println("SLOW_REQUEST_PROBE_PASSED");
            }
            finally
            {
                for(Socket socket: stalledClients)
                {
                    try
                    {
                        socket.close();
                    }
                    catch(IOException exception)
                    {
                        //The server timeout may already have closed the peer.
                    }
                }

                server.stop(0);
                executor.shutdownNow();
            }
        }

        private static void slowBody(HttpExchange exchange, CountDownLatch entered, AtomicInteger completed)
        {
            entered.countDown();

            try
            {
                exchange.getRequestBody().readNBytes(33);
            }
            catch(IOException exception)
            {
                //The configured server policy closes the underlying connection.
            }
            finally
            {
                exchange.close();
                completed.incrementAndGet();
            }
        }

        private static void health(HttpExchange exchange) throws IOException
        {
            byte[] body = "ok".getBytes(StandardCharsets.US_ASCII);
            exchange.sendResponseHeaders(HttpURLConnection.HTTP_OK, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private static boolean waitForCount(AtomicInteger count, int expected, Duration timeout)
            throws InterruptedException
        {
            long deadline = System.nanoTime() + timeout.toNanos();

            while(count.get() < expected && System.nanoTime() < deadline)
            {
                Thread.sleep(10);
            }

            return count.get() >= expected;
        }

        private static void assertClosedWithoutWaiting(Socket socket) throws IOException
        {
            try
            {
                if(socket.getInputStream().read() >= 0)
                {
                    throw new AssertionError("Timed-out request connection remained readable");
                }
            }
            catch(SocketTimeoutException exception)
            {
                throw new AssertionError("Timed-out request connection remained open", exception);
            }
            catch(IOException exception)
            {
                //A connection reset is also a successful forced close.
            }
        }
    }
}
