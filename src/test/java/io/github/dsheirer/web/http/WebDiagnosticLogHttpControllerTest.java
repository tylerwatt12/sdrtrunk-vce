/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpContext;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpPrincipal;
import io.github.dsheirer.preference.application.ApplicationPreference;
import io.github.dsheirer.support.WebDiagnosticLogService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.prefs.Preferences;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WebDiagnosticLogHttpControllerTest
{
    @TempDir Path mDirectory;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PATH = WebDiagnosticLogHttpController.PATH;

    @Test
    void statusToggleCompactEntriesAndDownloadUseSharedSafeWireContract() throws Exception
    {
        Files.writeString(mDirectory.resolve(WebDiagnosticLogService.FILE_NAME),
            "20261014 105431.012 [writer] WARN web.diagnostic - Example password=private-secret\n");
        try(Fixture fixture = new Fixture())
        {
            Exchange status = new Exchange(PATH, "GET", "");
            fixture.controller.handle(status);
            assertEquals(200, status.code);
            assertEquals("no-store", status.response.getFirst("Cache-Control"));
            var data = MAPPER.readTree(status.bytes()).path("data");
            assertFalse(data.path("enabled").booleanValue());
            assertEquals(WebDiagnosticLogService.FILE_NAME, data.path("file_name").textValue());
            assertEquals(7, data.path("retention_days").intValue());
            assertEquals(100L * 1024 * 1024, data.path("maximum_bytes").longValue());
            assertFalse(data.has("maximumBytes"));
            Exchange enable = new Exchange(PATH, "PUT", "{\"enabled\":true}");
            fixture.controller.handle(enable);
            assertEquals(200, enable.code);
            assertTrue(MAPPER.readTree(enable.bytes()).at("/data/enabled").booleanValue());
            Exchange entries = new Exchange(PATH + "/entries?compact=true", "GET", "");
            fixture.controller.handle(entries);
            assertEquals(200, entries.code);
            assertFalse(MAPPER.readTree(entries.bytes()).at("/data/entries/0").has("text"));
            assertFalse(new String(entries.bytes(), StandardCharsets.UTF_8).contains("private-secret"));
            Exchange download = new Exchange(PATH + "/download", "GET", "");
            fixture.controller.handle(download);
            assertEquals(200, download.code);
            assertEquals("application/zip", download.response.getFirst("Content-Type"));
            assertEquals("no-store", download.response.getFirst("Cache-Control"));
            assertEquals("Cookie", download.response.getFirst("Vary"));
            try(ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(download.bytes())))
            {
                assertNotNull(zip.getNextEntry());
                assertFalse(new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains("private-secret"));
            }
        }
    }

    @Test
    void rejectsUnknownPathsMethodsBodiesQueriesAndInvalidSettings() throws Exception
    {
        try(Fixture fixture = new Fixture())
        {
            for(String body: List.of("{}", "{\"enabled\":1}", "{\"enabled\":true,\"extra\":1}",
                "{\"enabled\":true,\"enabled\":false}", "[]", ""))
            {
                Exchange exchange = new Exchange(PATH, "PUT", body);
                fixture.controller.handle(exchange);
                assertEquals(400, exchange.code, body);
            }
            for(String query: List.of("", "file=private.log", "log=../private", "compact=false", "log=current&log=previous", "after=%FF"))
            {
                Exchange exchange = new Exchange(PATH + "/entries?" + query, "GET", "");
                fixture.controller.handle(exchange);
                assertEquals(400, exchange.code, query);
            }
            Exchange unsupported = new Exchange(PATH + "/download", "PUT", "{\"enabled\":true}");
            fixture.controller.handle(unsupported);
            assertEquals(405, unsupported.code);
            assertEquals("GET", unsupported.response.getFirst("Allow"));
            Exchange unknown = new Exchange(PATH + "/private", "GET", "");
            fixture.controller.handle(unknown);
            assertEquals(404, unknown.code);
            Exchange getBody = new Exchange(PATH, "GET", "x");
            fixture.controller.handle(getBody);
            assertEquals(400, getBody.code);
            Exchange downloadQuery = new Exchange(PATH + "/download?file=private", "GET", "");
            fixture.controller.handle(downloadQuery);
            assertEquals(400, downloadQuery.code);
            Exchange emptyDownload = new Exchange(PATH + "/download", "GET", "");
            fixture.controller.handle(emptyDownload);
            assertEquals(503, emptyDownload.code);
            assertFalse(new String(emptyDownload.bytes(), StandardCharsets.UTF_8).contains(mDirectory.toString()));
            fixture.service.close();
            Exchange closed = new Exchange(PATH + "/entries", "GET", "");
            fixture.controller.handle(closed);
            assertEquals(503, closed.code);
            assertEquals("2", closed.response.getFirst("Retry-After"));
        }
    }

    private final class Fixture implements AutoCloseable
    {
        private final Preferences node = Preferences.userRoot().node("/sdrtrunk-vce-tests/web-diagnostic-http-" + UUID.randomUUID());
        private final WebDiagnosticLogService service;
        private final WebDiagnosticLogHttpController controller;
        private Fixture() throws Exception
        {
            ApplicationPreference preferences = new ApplicationPreference(ignored -> {});
            Field field = ApplicationPreference.class.getDeclaredField("mPreferences");
            field.setAccessible(true);
            field.set(preferences, node);
            service = new WebDiagnosticLogService(() -> mDirectory, preferences);
            controller = new WebDiagnosticLogHttpController(service);
        }
        @Override public void close() throws Exception
        {
            service.close();
            Preferences parent = node.parent(); node.removeNode(); parent.flush();
        }
    }

    private static final class Exchange extends HttpExchange
    {
        private final Headers request = new Headers();
        private final Headers response = new Headers();
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final URI uri;
        private final String method;
        private final byte[] input;
        private int code;
        private Exchange(String path, String method, String body)
        {
            uri = URI.create(path); this.method = method; input = body.getBytes(StandardCharsets.UTF_8);
            request.set("Content-Type", "application/json");
            request.set("Content-Length", Integer.toString(input.length));
        }
        private byte[] bytes() { return output.toByteArray(); }
        @Override public Headers getRequestHeaders() { return request; }
        @Override public Headers getResponseHeaders() { return response; }
        @Override public URI getRequestURI() { return uri; }
        @Override public String getRequestMethod() { return method; }
        @Override public HttpContext getHttpContext() { return null; }
        @Override public void close() { }
        @Override public InputStream getRequestBody() { return new ByteArrayInputStream(input); }
        @Override public OutputStream getResponseBody() { return output; }
        @Override public void sendResponseHeaders(int status, long length) { code = status; }
        @Override public int getResponseCode() { return code; }
        @Override public InetSocketAddress getRemoteAddress() { return new InetSocketAddress("127.0.0.1", 12345); }
        @Override public InetSocketAddress getLocalAddress() { return new InetSocketAddress("127.0.0.1", 8090); }
        @Override public Object getAttribute(String name) { return null; }
        @Override public void setAttribute(String name, Object value) { }
        @Override public void setStreams(InputStream input, OutputStream output) { }
        @Override public HttpPrincipal getPrincipal() { return null; }
        @Override public String getProtocol() { return "HTTP/1.1"; }
    }
}
