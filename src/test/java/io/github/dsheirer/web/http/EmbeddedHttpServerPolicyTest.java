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
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
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
        runProbe(SlowRequestProbe.class, "SLOW_REQUEST_PROBE_PASSED");
    }

    @Test
    void browserConnectionsRemainReusableAboveTheJdkIdleDefault() throws Exception
    {
        runProbe(KeepAliveProbe.class, "KEEP_ALIVE_PROBE_PASSED");
    }

    @Test
    void explicitIdleConnectionBudgetIsPreserved() throws Exception
    {
        runProbe(KeepAliveProbe.class, "KEEP_ALIVE_PROBE_PASSED", "333");
    }

    private static void runProbe(Class<?> probe, String success, String... arguments) throws Exception
    {
        String javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String testClasses = Path.of(probe.getProtectionDomain().getCodeSource().getLocation()
            .toURI()).toString();
        String mainClasses = Path.of(EmbeddedHttpServerPolicy.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI()).toString();
        String classpath = testClasses.equals(mainClasses) ? testClasses :
            testClasses + java.io.File.pathSeparator + mainClasses;
        List<String> command = new ArrayList<>(List.of(javaExecutable, "--add-modules=jdk.httpserver", "-cp",
            classpath, probe.getName()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean completed = process.waitFor(15, TimeUnit.SECONDS);

        if(!completed)
        {
            process.destroyForcibly();
        }

        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(completed, output);
        assertEquals(0, process.exitValue(), output);
        assertTrue(output.contains(success), output);
    }

    /** Real HTTP sockets exercise the JDK's static policy and completed-response idle transition. */
    public static final class KeepAliveProbe
    {
        public static void main(String[] args) throws Exception
        {
            String expected = args.length == 0 ? "1536" : args[0];
            System.clearProperty(EmbeddedHttpServerPolicy.MAXIMUM_IDLE_CONNECTIONS_PROPERTY);
            if(args.length > 0) System.setProperty(EmbeddedHttpServerPolicy.MAXIMUM_IDLE_CONNECTIONS_PROPERTY, args[0]);
            EmbeddedHttpServerPolicy.configureBeforeServerInitialization();
            if(!expected.equals(System.getProperty(EmbeddedHttpServerPolicy.MAXIMUM_IDLE_CONNECTIONS_PROPERTY)))
                throw new AssertionError("The idle connection policy did not preserve the requested budget");

            ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
            HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 8);
            server.setExecutor(executor);
            AtomicInteger requests = new AtomicInteger();
            server.createContext("/", exchange -> {
                requests.incrementAndGet();
                SlowRequestProbe.health(exchange);
            });
            server.start();
            List<Socket> clients = new ArrayList<>();
            try
            {
                int port = server.getAddress().getPort();
                // The former JDK limit closed the 201st completed connection before it could be reused.
                for(int index = 0; index < 256; index++)
                {
                    Socket socket = new Socket(InetAddress.getLoopbackAddress(), port);
                    socket.setSoTimeout(2_000);
                    clients.add(socket);
                    request(socket);
                }
                // A later request forces the dispatcher past all previous completion/idle transitions.
                try(Socket barrier = new Socket(InetAddress.getLoopbackAddress(), port))
                {
                    barrier.setSoTimeout(2_000);
                    request(barrier);
                }
                for(Socket socket: clients) request(socket);
                if(requests.get() != 513) throw new AssertionError("Not every original socket was reused");
                System.out.println("KEEP_ALIVE_PROBE_PASSED");
            }
            finally
            {
                for(Socket socket: clients) socket.close();
                server.stop(0);
                executor.shutdownNow();
            }
        }

        private static void request(Socket socket) throws IOException
        {
            socket.getOutputStream().write(("GET / HTTP/1.1\r\nHost: localhost\r\n" +
                "Connection: keep-alive\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            ByteArrayOutputStream headers = new ByteArrayOutputStream();
            int lastFour = 0;
            while(lastFour != 0x0d0a0d0a)
            {
                int value = socket.getInputStream().read();
                if(value < 0) throw new EOFException("A completed keep-alive connection was closed before reuse");
                headers.write(value);
                lastFour = (lastFour << 8) | value;
                if(headers.size() > 8_192) throw new IOException("Unexpected response headers");
            }
            String response = headers.toString(StandardCharsets.US_ASCII).toLowerCase(java.util.Locale.ROOT);
            if(!response.startsWith("http/1.1 200 ") || !response.contains("\r\ncontent-length: 2\r\n") ||
                !java.util.Arrays.equals("ok".getBytes(StandardCharsets.US_ASCII), socket.getInputStream().readNBytes(2)))
                throw new AssertionError("Unexpected keep-alive response");
        }
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
