/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Filter;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpPrincipal;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsExchange;
import com.sun.net.httpserver.HttpsServer;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.support.WebDiagnosticLogService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.prefs.Preferences;
import javax.net.ssl.SSLSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebDiagnosticHttpFilterTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir Path mDirectory;

    @Test
    void contextDecorationPreservesTheActualServerForLongLivedHandlers() throws Exception
    {
        Preferences node = isolatedNode();
        HttpServer server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        try(WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, preferences(node)))
        {
            TestExchange disabled = new TestExchange(null, "/unused", "GET");
            new WebDiagnosticHttpFilter(service).doFilter(disabled,
                new Filter.Chain(List.of(), exchange -> exchange.sendResponseHeaders(204, -1)));
            assertEquals(204, disabled.getResponseCode());
            assertFalse(Files.exists(mDirectory.resolve(WebDiagnosticLogService.FILE_NAME)));
            HttpServer registration = WebDiagnosticHttpFilter.instrumentContexts(server, service);
            HttpContext withHandler = registration.createContext("/api/v1/live", exchange -> {});
            HttpContext withoutHandler = registration.createContext("/api/v1/events");
            for(HttpContext context: List.of(withHandler, withoutHandler))
            {
                assertSame(server, context.getServer());
                assertEquals(1, context.getFilters().size());
                assertTrue(context.getFilters().getFirst() instanceof WebDiagnosticHttpFilter);
            }
            service.setEnabled(true);
            HttpContext logs = registration.createContext(WebDiagnosticLogHttpController.PATH);
            TestExchange https = new TestExchange(logs, WebDiagnosticLogHttpController.PATH, "GET");
            new WebDiagnosticHttpFilter(service).doFilter(https, new Filter.Chain(List.of(), exchange -> {
                assertSame(https, exchange);
                assertTrue(exchange instanceof HttpsExchange);
                assertSame(server, exchange.getHttpContext().getServer());
                exchange.sendResponseHeaders(200, -1);
            }));
            registration.removeContext(withoutHandler);
        }
        finally
        {
            server.stop(0);
            node.removeNode();
        }
    }

    @Test
    void recordsCompletionAndFailureWithTimingsWithoutReadingSensitiveRequestContent() throws Exception
    {
        Preferences node = isolatedNode();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        try(WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, preferences(node)))
        {
            service.setEnabled(true);
            HttpContext context = server.createContext("/api/v1/recordings");
            WebDiagnosticHttpFilter filter = new WebDiagnosticHttpFilter(service);
            TestExchange completed = new TestExchange(context,
                "https://url-user:url-secret@private.example.invalid/api/v1/recordings/private-file.wav?token=query-secret", "GET");
            filter.doFilter(completed, new Filter.Chain(List.of(), exchange -> exchange.sendResponseHeaders(204, -1)));

            TestExchange failed = new TestExchange(context, "/api/v1/recordings/12345/download?password=query-secret", "SECRET-METHOD");
            assertThrows(IOException.class, () -> filter.doFilter(failed, new Filter.Chain(List.of(), exchange -> {
                exchange.sendResponseHeaders(502, -1);
                throw new IOException("handler failed with body-secret and header-secret");
            })));

            String disk = awaitLog("\"failed\":true");
            List<JsonNode> requests = disk.lines().filter(line -> line.contains("request_started ") ||
                line.contains("request_finished ")).map(WebDiagnosticHttpFilterTest::fields).toList();
            assertEquals(4, requests.size(), disk);
            JsonNode first = requests.get(0);
            JsonNode firstFinished = requests.get(1);
            JsonNode second = requests.get(2);
            JsonNode secondFinished = requests.get(3);
            assertEquals("/api/v1/recordings/{value}", first.path("route").asText());
            assertEquals("GET", first.path("method").asText());
            assertEquals(first.path("request_id"), firstFinished.path("request_id"));
            assertEquals(204, firstFinished.path("status").asInt());
            assertFalse(firstFinished.path("failed").asBoolean());
            assertTrue(firstFinished.path("duration_ms").asDouble() > 0);
            assertEquals("/api/v1/recordings/{value}/download", second.path("route").asText());
            assertEquals("OTHER", second.path("method").asText());
            assertEquals(second.path("request_id"), secondFinished.path("request_id"));
            assertEquals(502, secondFinished.path("status").asInt());
            assertTrue(secondFinished.path("failed").asBoolean());
            assertTrue(secondFinished.path("duration_ms").asDouble() > 0);
            for(String secret: List.of("private-file", "12345", "query-secret", "url-secret", "url-user",
                "private.example.invalid", "body-secret", "header-secret", "SECRET-METHOD", "https://", "password=", "token="))
                assertFalse(disk.contains(secret), secret);
        }
        finally
        {
            server.stop(0);
            node.removeNode();
        }
    }

    @Test
    void pollingAndDownloadsDoNotCreateRecursiveLogEntriesWhileSettingsChangesAreRecorded() throws Exception
    {
        Preferences node = isolatedNode();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        try(WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, preferences(node)))
        {
            service.setEnabled(true);
            long generation = service.captureGeneration();
            WebDiagnosticHttpFilter filter = new WebDiagnosticHttpFilter(service);
            HttpContext loggingContext = null;
            for(String path: List.of(WebDiagnosticLogHttpController.PATH, ApplicationLogHttpController.PATH))
            {
                HttpContext context = server.createContext(path);
                if(WebDiagnosticLogHttpController.PATH.equals(path)) loggingContext = context;
                for(String method: List.of("GET", "HEAD"))
                    for(String suffix: List.of("", "/entries?after=private-cursor", "/download"))
                        filter.doFilter(new TestExchange(context, path + suffix, method),
                            new Filter.Chain(List.of(), exchange -> exchange.sendResponseHeaders(200, -1)));
            }
            Map<String,Object> viewer = service.viewerMetrics().snapshotAndReset(generation, 1);
            assertEquals(12L, viewer.get("started"));
            assertEquals(12L, viewer.get("completed"));
            assertEquals(0L, viewer.get("active"));
            Map<?,?> operations = (Map<?,?>)viewer.get("operations");
            assertEquals(5, operations.size());
            assertEquals(2L, ((Map<?,?>)operations.get("application_log_read")).get("started"));
            assertEquals(4L, ((Map<?,?>)operations.get("unknown_log_request")).get("started"));
            assertFalse(viewer.toString().contains("private-cursor"));
            filter.doFilter(new TestExchange(loggingContext, WebDiagnosticLogHttpController.PATH, "PUT"),
                new Filter.Chain(List.of(), exchange -> exchange.sendResponseHeaders(200, -1)));
            String disk = awaitLog("request_finished");
            assertEquals(1, disk.lines().filter(line -> line.contains("request_started ")).count(), disk);
            assertEquals(1, disk.lines().filter(line -> line.contains("request_finished ")).count(), disk);
            assertTrue(disk.contains("\"method\":\"PUT\""));
            assertFalse(disk.contains("\"method\":\"GET\""));
            assertFalse(disk.contains("\"method\":\"HEAD\""));
            assertFalse(disk.contains(ApplicationLogHttpController.PATH));
            assertFalse(disk.contains("private-cursor"));
        }
        finally
        {
            server.stop(0);
            node.removeNode();
        }
    }

    @Test
    void blockedDownloadsRemainVisibleAndFailuresAndDeclaredBytesAreAggregated() throws Exception
    {
        Preferences node = isolatedNode();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try(WebDiagnosticLogService service = new WebDiagnosticLogService(() -> mDirectory, preferences(node)))
        {
            service.setEnabled(true);
            long generation = service.captureGeneration();
            HttpContext context = server.createContext(WebDiagnosticLogHttpController.PATH);
            WebDiagnosticHttpFilter filter = new WebDiagnosticHttpFilter(service);
            List<Thread> clients = java.util.stream.IntStream.range(0, 2).mapToObj(index ->
                Thread.ofPlatform().start(() -> {
                    try
                    {
                        TestExchange exchange = new TestExchange(context,
                            WebDiagnosticLogHttpController.PATH + "/download?token=private-query", "GET");
                        filter.doFilter(exchange, new Filter.Chain(List.of(), request -> {
                            request.getResponseHeaders().set("Content-Length", "128");
                            request.sendResponseHeaders(index == 0 ? 502 : 200, 128);
                            entered.countDown();
                            try { release.await(); }
                            catch(InterruptedException exception) { throw new IOException(exception); }
                            if(index == 0) throw new IOException("private-handler-message");
                        }));
                    }
                    catch(IOException exception) { if(index != 0) failure.compareAndSet(null, exception); }
                    catch(Throwable exception) { failure.compareAndSet(null, exception); }
                })).toList();
            try
            {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                Map<String,Object> active = service.viewerMetrics().snapshotAndReset(generation, 1);
                assertEquals(2L, active.get("active"));
                assertEquals(2L, ((Map<?,?>)((Map<?,?>)active.get("operations")).get("web_log_download")).get("peak_active"));
            }
            finally
            {
                release.countDown();
                for(Thread client: clients) client.join(2000);
            }
            assertNull(failure.get());
            filter.doFilter(new TestExchange(context, WebDiagnosticLogHttpController.PATH + "/entries", "HEAD"),
                new Filter.Chain(List.of(), request -> {
                    request.getResponseHeaders().set("Content-Length", "invalid-private-value");
                    request.sendResponseHeaders(405, -1);
                }));
            Map<String,Object> completed = service.viewerMetrics().snapshotAndReset(generation, 2);
            assertEquals(3L, completed.get("completed"));
            assertEquals(2L, completed.get("errors"));
            assertEquals(0L, completed.get("active"));
            Map<?,?> operations = (Map<?,?>)completed.get("operations");
            assertEquals(256L, ((Map<?,?>)operations.get("web_log_download")).get("declared_response_bytes"));
            assertEquals(1L, ((Map<?,?>)operations.get("web_log_read")).get("unknown_response_bytes_count"));
            assertFalse(completed.toString().contains("private"));
        }
        finally
        {
            release.countDown();
            server.stop(0);
            node.removeNode();
        }
    }

    @Test
    void normalizesAssetsDynamicValuesEncodedTextAndExcessivePaths()
    {
        assertEquals("/web-assets", WebDiagnosticHttpFilter.normalizedRoute("/", "/assets/private-name.js"));
        assertEquals("/api/v1/tuners/{value}/frequency", WebDiagnosticHttpFilter.normalizedRoute(
            "/api/v1/tuners", "/api/v1/tuners/serial123/frequency"));
        assertEquals("/api/v1/channels/{value}/{value}", WebDiagnosticHttpFilter.normalizedRoute(
            "/api/v1/channels", "/api/v1/channels/123/private%20name"));
        assertEquals("/api/v1/channels", WebDiagnosticHttpFilter.normalizedRoute("/api/v1/channels", "/" + "x".repeat(2048)));
    }

    private String awaitLog(String marker) throws Exception
    {
        Path file = mDirectory.resolve(WebDiagnosticLogService.FILE_NAME);
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        String text = "";
        do
        {
            if(Files.isRegularFile(file)) text = Files.readString(file);
            if(text.contains(marker)) return text;
            Thread.sleep(10);
        }
        while(System.nanoTime() < deadline);
        assertTrue(text.contains(marker), text);
        return text;
    }

    private static JsonNode fields(String line)
    {
        try { return MAPPER.readTree(line.substring(line.indexOf('{'))); }
        catch(IOException exception) { throw new AssertionError(exception); }
    }

    private static Preferences isolatedNode()
    {
        return Preferences.userRoot().node("/sdrtrunk-vce-tests/web-diagnostic-filter-" + UUID.randomUUID());
    }

    private static ApplicationPreference preferences(Preferences node) throws ReflectiveOperationException
    {
        ApplicationPreference preference = new ApplicationPreference(ignored -> {});
        Field field = ApplicationPreference.class.getDeclaredField("mPreferences");
        field.setAccessible(true);
        field.set(preference, node);
        return preference;
    }

    /** Any read of headers, body, principal or peer information would fail this privacy contract. */
    private static final class TestExchange extends HttpsExchange
    {
        private final HttpContext mContext;
        private final URI mUri;
        private final String mMethod;
        private final Headers mResponse = new Headers();
        private int mStatus = -1;
        private TestExchange(HttpContext context, String uri, String method)
        { mContext = context; mUri = URI.create(uri); mMethod = method; }
        @Override public Headers getRequestHeaders() { throw new AssertionError("Request headers must not be read"); }
        @Override public Headers getResponseHeaders() { return mResponse; }
        @Override public URI getRequestURI() { return mUri; }
        @Override public String getRequestMethod() { return mMethod; }
        @Override public HttpContext getHttpContext() { return mContext; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { throw new AssertionError("Request body must not be read"); }
        @Override public OutputStream getResponseBody() { return new ByteArrayOutputStream(); }
        @Override public void sendResponseHeaders(int status, long length) { mStatus = status; }
        @Override public int getResponseCode() { return mStatus; }
        @Override public InetSocketAddress getRemoteAddress() { throw new AssertionError("Peer must not be read"); }
        @Override public InetSocketAddress getLocalAddress() { throw new AssertionError("Address must not be read"); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { throw new AssertionError("Principal must not be read"); }
        @Override public SSLSession getSSLSession() { return null; }
    }
}
