/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.github.dsheirer.support.ApplicationLogService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ApplicationLogHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();
    @TempDir
    Path mDirectory;

    @Test
    void usesSharedNoStoreEnvelopeAndReturnsFullSnapshotsWithCursor() throws Exception
    {
        Files.writeString(mDirectory.resolve("sdrtrunk_app.log"),
            "20261002 105431.012 [main] INFO  example.Service - Started\n");
        try(ApplicationLogService service = new ApplicationLogService(mDirectory))
        {
            ApplicationLogHttpController controller = new ApplicationLogHttpController(service);
            TestExchange first = new TestExchange(ApplicationLogHttpController.PATH, "GET");
            controller.handle(first);
            assertEquals(200, first.getResponseCode());
            assertEquals("no-store", first.getResponseHeaders().getFirst("Cache-Control"));
            assertEquals("Cookie", first.getResponseHeaders().getFirst("Vary"));
            JsonNode snapshot = MAPPER.readTree(first.body()).path("data");
            assertEquals("sdrtrunk_app.log", snapshot.path("file_name").textValue());
            assertEquals(500, snapshot.path("max_entries").intValue());
            assertEquals("Started", snapshot.at("/entries/0/message").textValue());
            assertTrue(snapshot.path("updated_at").longValue() > 0);
            assertFalse(snapshot.has("fileName"));
            assertTrue(snapshot.at("/entries/0").has("text"));
            TestExchange compact = new TestExchange(ApplicationLogHttpController.PATH + "?compact=true", "GET");
            controller.handle(compact);
            JsonNode wire = MAPPER.readTree(compact.body()).path("data");
            assertFalse(wire.at("/entries/0").has("text"));
            assertEquals("20261002 105431.012 [main] INFO  example.Service - ",
                wire.at("/entries/0/header_prefix").textValue());
            TestExchange cursor = new TestExchange(ApplicationLogHttpController.PATH + "?log=current&after=" +
                snapshot.path("latest_id").textValue(), "GET");
            controller.handle(cursor);
            JsonNode same = MAPPER.readTree(cursor.body()).path("data");
            assertEquals(1, same.path("entries").size());
            assertFalse(same.path("gap").booleanValue());
            TestExchange incremental = new TestExchange(ApplicationLogHttpController.PATH + "?log=current&after=" +
                snapshot.path("latest_id").textValue() + "&compact=true&revision=" + snapshot.path("revision").textValue(), "GET");
            controller.handle(incremental);
            JsonNode delta = MAPPER.readTree(incremental.body()).path("data");
            assertTrue(delta.path("incremental").booleanValue());
            assertEquals(0, delta.path("entries").size());

            TestExchange missing = new TestExchange(ApplicationLogHttpController.PATH + "?log=previous", "GET");
            controller.handle(missing);
            assertEquals(200, missing.getResponseCode());
            assertFalse(MAPPER.readTree(missing.body()).at("/data/available").booleanValue());
        }
    }

    @Test
    void compactEntriesReconstructEmptyDetailLinesUnstructuredAndTruncatedMessages() throws Exception
    {
        String header = "20261002 105431.012 [main] INFO  example.Service - ";
        for(String original: java.util.List.of(header + "Started\n\n", "Unstructured diagnostic\n\n",
            header + "x".repeat(70_000) + "\n"))
        {
            Files.writeString(mDirectory.resolve("sdrtrunk_app.log"), original);
            try(ApplicationLogService service = new ApplicationLogService(mDirectory))
            {
                ApplicationLogHttpController controller = new ApplicationLogHttpController(service);
                TestExchange legacy = new TestExchange(ApplicationLogHttpController.PATH, "GET");
                controller.handle(legacy);
                TestExchange compact = new TestExchange(ApplicationLogHttpController.PATH + "?compact=true", "GET");
                controller.handle(compact);
                JsonNode entry = MAPPER.readTree(compact.body()).at("/data/entries/0");
                assertTrue(entry.path("multiline").booleanValue());
                String reconstructed = entry.path("header_prefix").textValue() + entry.path("message").textValue() +
                    (entry.path("multiline").booleanValue() ? "\n" + entry.path("details").textValue() : "");
                assertEquals(MAPPER.readTree(legacy.body()).at("/data/entries/0/text").textValue(), reconstructed);
                assertFalse(entry.has("text"));
            }
        }
    }

    @Test
    void refusesUnsupportedMethodsBodiesPathsQueriesAndClosedReader() throws Exception
    {
        try(ApplicationLogService service = new ApplicationLogService(mDirectory))
        {
            ApplicationLogHttpController controller = new ApplicationLogHttpController(service);
            for(String query: java.util.List.of("log=current&log=previous", "file=private.txt", "log=../private",
                "compact=false", "revision=abcd:1", "after=/private", "log=", "after=%FF", "", "log=current&", "extra=" + "x".repeat(300)))
            {
                TestExchange invalid = new TestExchange(ApplicationLogHttpController.PATH + "?" + query, "GET");
                controller.handle(invalid);
                assertEquals(400, invalid.getResponseCode(), query);
                assertEquals("no-store", invalid.getResponseHeaders().getFirst("Cache-Control"));
            }
            TestExchange method = new TestExchange(ApplicationLogHttpController.PATH, "POST");
            controller.handle(method);
            assertEquals(405, method.getResponseCode());
            assertEquals("GET", method.getResponseHeaders().getFirst("Allow"));
            TestExchange body = new TestExchange(ApplicationLogHttpController.PATH, "GET");
            body.getRequestHeaders().set("Content-Length", "1");
            controller.handle(body);
            assertEquals(400, body.getResponseCode());
            TestExchange suffix = new TestExchange(ApplicationLogHttpController.PATH + "/private", "GET");
            controller.handle(suffix);
            assertEquals(404, suffix.getResponseCode());
            service.close();
            TestExchange closed = new TestExchange(ApplicationLogHttpController.PATH, "GET");
            controller.handle(closed);
            assertEquals(503, closed.getResponseCode());
            assertEquals("2", closed.getResponseHeaders().getFirst("Retry-After"));
            assertEquals("application_log_busy", MAPPER.readTree(closed.body()).at("/error/code").textValue());
        }
    }

    @Test
    void blockedResponseDoesNotRetainReaderAdmission() throws Exception
    {
        Files.writeString(mDirectory.resolve("sdrtrunk_app.log"), "Current message\n");
        Files.writeString(mDirectory.resolve("20261001_sdrtrunk_app.log"), "Previous message\n");
        BlockingOutputStream output = new BlockingOutputStream();
        try(ApplicationLogService service = new ApplicationLogService(mDirectory);
            var executor = Executors.newSingleThreadExecutor())
        {
            ApplicationLogHttpController controller = new ApplicationLogHttpController(service);
            TestExchange exchange = new TestExchange(ApplicationLogHttpController.PATH, "GET", output);
            var response = executor.submit(() -> {
                controller.handle(exchange);
                return null;
            });
            assertTrue(output.mWriting.await(2, TimeUnit.SECONDS));
            assertTrue(service.snapshot("previous", null).available());
            assertFalse(response.isDone());
            output.mRelease.countDown();
            response.get(2, TimeUnit.SECONDS);
        }
        finally
        {
            output.mRelease.countDown();
        }
    }

    private static final class TestExchange extends HttpExchange
    {
        private final Headers mRequest = new Headers();
        private final Headers mResponse = new Headers();
        private final Map<String,Object> mAttributes = new HashMap<>();
        private final URI mUri;
        private final String mMethod;
        private final OutputStream mOutput;
        private int mStatus;

        private TestExchange(String path, String method)
        {
            this(path, method, new ByteArrayOutputStream());
        }

        private TestExchange(String path, String method, OutputStream output)
        {
            mUri = URI.create(path);
            mMethod = method;
            mOutput = output;
        }

        private String body()
        {
            return ((ByteArrayOutputStream)mOutput).toString(StandardCharsets.UTF_8);
        }

        @Override public Headers getRequestHeaders() { return mRequest; }
        @Override public Headers getResponseHeaders() { return mResponse; }
        @Override public URI getRequestURI() { return mUri; }
        @Override public String getRequestMethod() { return mMethod; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return mOutput; }
        @Override public void sendResponseHeaders(int status, long length) { mStatus = status; }
        @Override public int getResponseCode() { return mStatus; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 12345); }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return mAttributes.get(name); }
        @Override public void setAttribute(String name, Object value) { mAttributes.put(name, value); }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }

    private static final class BlockingOutputStream extends OutputStream
    {
        private final CountDownLatch mWriting = new CountDownLatch(1);
        private final CountDownLatch mRelease = new CountDownLatch(1);

        @Override public void write(int value) throws IOException { block(); }
        @Override public void write(byte[] values, int offset, int length) throws IOException { block(); }

        private void block() throws IOException
        {
            mWriting.countDown();
            try
            {
                if(!mRelease.await(5, TimeUnit.SECONDS)) throw new IOException("Response was not released");
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                throw new IOException("Response interrupted", exception);
            }
        }
    }
}
