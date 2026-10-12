package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.*;
import io.github.dsheirer.web.tuner.TunerAllocationMonitorService;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TunerAllocationHttpControllerTest
{
    @Test void onlyExactBodylessGetProjectsHistory() throws Exception
    {
        AtomicInteger reads = new AtomicInteger();
        TunerAllocationHttpController controller = new TunerAllocationHttpController(() -> {
            reads.incrementAndGet();
            return new TunerAllocationMonitorService.Snapshot(true, "session", 3, 1_000, List.of(), List.of(), List.of());
        });
        TestExchange valid = new TestExchange(TunerAllocationHttpController.PATH, "GET");
        controller.handle(valid);
        assertEquals(200, valid.getResponseCode());
        assertEquals("no-store", valid.getResponseHeaders().getFirst("Cache-Control"));
        assertTrue(valid.body().contains("\"generated_at_ms\":1000"));
        for(String method: List.of("POST", "PUT", "PATCH", "DELETE"))
        {
            TestExchange mutation = new TestExchange(TunerAllocationHttpController.PATH, method);
            controller.handle(mutation);
            assertEquals(405, mutation.getResponseCode());
        }
        TestExchange query = new TestExchange(TunerAllocationHttpController.PATH + "?capture=true", "GET");
        controller.handle(query);
        assertEquals(400, query.getResponseCode());
        TestExchange body = new TestExchange(TunerAllocationHttpController.PATH, "GET");
        body.getRequestHeaders().set("Content-Length", "10");
        controller.handle(body);
        assertEquals(400, body.getResponseCode());
        TestExchange nested = new TestExchange(TunerAllocationHttpController.PATH + "/capture", "GET");
        controller.handle(nested);
        assertEquals(404, nested.getResponseCode());
        assertEquals(1, reads.get());
    }

    @Test void unavailableResponseHasActionableCopy() throws Exception
    {
        TestExchange exchange = new TestExchange(TunerAllocationHttpController.PATH, "GET");
        new TunerAllocationHttpController(() -> null).handle(exchange);
        assertEquals(503, exchange.getResponseCode());
        assertTrue(exchange.body().contains("Try again shortly"));
    }

    private static final class TestExchange extends HttpExchange
    {
        private final Headers request = new Headers();
        private final Headers response = new Headers();
        private final Map<String,Object> attributes = new HashMap<>();
        private final URI uri;
        private final String method;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int code;
        private TestExchange(String path, String method) { uri = URI.create(path); this.method = method; }
        private String body() { return output.toString(StandardCharsets.UTF_8); }
        @Override public Headers getRequestHeaders() { return request; }
        @Override public Headers getResponseHeaders() { return response; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(new byte[0]); }
        @Override public OutputStream getResponseBody() { return output; }
        @Override public void sendResponseHeaders(int code, long length) { this.code = code; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 1234); }
        @Override public int getResponseCode() { return code; }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress(InetAddress.getLoopbackAddress(), 8080); }
        @Override public String getProtocol() { return "HTTP/1.1"; }
        @Override public Object getAttribute(String name) { return attributes.get(name); }
        @Override public void setAttribute(String name, Object value) { attributes.put(name, value); }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
    }
}
