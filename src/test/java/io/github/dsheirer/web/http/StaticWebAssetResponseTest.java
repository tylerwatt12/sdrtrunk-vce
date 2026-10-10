/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticWebAssetResponseTest
{
    @TempDir Path mRoot;

    @Test
    void publicOriginUsesOnlyOneValidatedAuthorityAndTheActualTransport()
    {
        assertEquals("http://receiver.example", StaticWebAssetResponse.publicOrigin(false,
            List.of("Receiver.Example:80")));
        assertEquals("https://receiver.example", StaticWebAssetResponse.publicOrigin(true,
            List.of("Receiver.Example:443")));
        assertEquals("https://receiver.example:8443", StaticWebAssetResponse.publicOrigin(true,
            List.of("receiver.example:8443")));
        assertEquals("http://[2001:db8::1]:8090", StaticWebAssetResponse.publicOrigin(false,
            List.of("[2001:DB8::1]:8090")));
        assertEquals(null, StaticWebAssetResponse.publicOrigin(false, null));
        assertEquals(null, StaticWebAssetResponse.publicOrigin(false, List.of()));
        assertEquals(null, StaticWebAssetResponse.publicOrigin(false, List.of("first.example", "second.example")));
        for(String authority: List.of("", " receiver.example", "receiver.example ", "user@receiver.example",
            "receiver.example/path", "receiver.example?query", "receiver.example#fragment",
            "receiver.example\"", "receiver.example&other", "receiver.example:0", "receiver.example:65536",
            "receiver.example:", "receiver.example:-1", "receiver.example:100000", "[::1%25scope]",
            "receiver.example\\other", "receiver_example", "é.example", "a".repeat(321)))
            assertEquals(null, StaticWebAssetResponse.publicOrigin(false, List.of(authority)), authority);
    }

    @Test
    void rendersPublicIndexBeforeLoginWithRootUrlsAndRepresentationSpecificValidators() throws Exception
    {
        Files.writeString(mRoot.resolve("index.html"), indexTemplate("public card"));
        Files.write(mRoot.resolve("card.png"), new byte[] { 1, 2, 3 });
        StaticWebAssetResponse responder = new StaticWebAssetResponse();
        try(Fixture fixture = new Fixture(responder))
        {
            Response identity = fixture.rawRequest("/?view=recordings&call=private-detail", "GET",
                List.of("Receiver.Example:8090"), "identity", null);
            assertEquals(200, identity.status());
            String html = new String(identity.body(), StandardCharsets.UTF_8);
            assertTrue(html.contains("href=\"http://receiver.example:8090/\""));
            assertTrue(html.contains("content=\"http://receiver.example:8090/\""));
            assertTrue(html.contains("http://receiver.example:8090/assets/vce-social-card.png?v=1"));
            assertFalse(html.contains("__VCE_PUBLIC_ORIGIN__"));
            assertFalse(html.contains("private-detail"));
            assertFalse(html.contains("forwarded.example"), "forwarding headers do not choose the public origin");
            assertEquals("no-cache", identity.headers().get("cache-control"));
            Response compressed = fixture.rawRequest("/index.html?view=admin", "GET",
                List.of("receiver.example:8090"), "gzip", null);
            assertEquals("gzip", compressed.headers().get("content-encoding"));
            try(GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed.body())))
            {
                assertArrayEquals(identity.body(), input.readAllBytes());
            }
            assertNotEquals(identity.headers().get("etag"), compressed.headers().get("etag"));
            Response head = fixture.rawRequest("/index.html", "HEAD", List.of("receiver.example:8090"),
                "gzip", null);
            assertEquals(0, head.body().length);
            assertEquals(compressed.headers().get("content-length"), head.headers().get("content-length"));
            assertEquals(compressed.headers().get("etag"), head.headers().get("etag"));
            Response unchanged = fixture.rawRequest("/index.html?view=about", "GET",
                List.of("receiver.example:8090"), "gzip", compressed.headers().get("etag"));
            assertEquals(304, unchanged.status());
            assertEquals(0, unchanged.body().length);
            Response otherOrigin = fixture.rawRequest("/index.html", "GET", List.of("other.example:8090"),
                "identity", identity.headers().get("etag"));
            assertEquals(200, otherOrigin.status(), "a validator from another origin cannot hide changed URLs");
            assertNotEquals(identity.headers().get("etag"), otherOrigin.headers().get("etag"));
            assertFalse(new String(otherOrigin.body(), StandardCharsets.UTF_8).contains("receiver.example"));
            assertArrayEquals(new byte[] { 1, 2, 3 }, fixture.request("card.png", "GET", null, null).body());
            for(List<String> authorities: List.of(List.of("user@receiver.example"),
                List.of("first.example", "second.example")))
                assertEquals(400, fixture.rawRequest("/index.html", "GET", authorities, null, null).status());
        }
    }

    @Test
    void boundsOriginVariantsAndInvalidatesEveryVariantAfterDocumentEditsOrRemoval() throws Exception
    {
        Path index = mRoot.resolve("index.html");
        Files.writeString(index, indexTemplate("before"));
        StaticWebAssetResponse responder = new StaticWebAssetResponse(16_000, 2, 8_000);
        try(Fixture fixture = new Fixture(responder))
        {
            for(String authority: List.of("first.example", "second.example", "third.example"))
            {
                assertEquals(200, fixture.rawRequest("/index.html", "GET", List.of(authority), "gzip", null).status());
                assertTrue(responder.cachedEntries() <= 2);
                assertTrue(responder.cachedBytes() <= 16_000);
            }
            Response before = fixture.rawRequest("/index.html", "GET", List.of("third.example"), "identity", null);
            Files.writeString(index, indexTemplate("after!"));
            Files.setLastModifiedTime(index, FileTime.from(Instant.now().plusSeconds(10)));
            Response after = fixture.rawRequest("/index.html", "GET", List.of("third.example"), "identity",
                before.headers().get("etag"));
            assertEquals(200, after.status());
            assertTrue(new String(after.body(), StandardCharsets.UTF_8).contains("after!"));
            assertNotEquals(before.headers().get("etag"), after.headers().get("etag"));
            assertEquals(1, responder.cachedEntries(), "an edit releases all other origins for the old file stamp");
            fixture.rawRequest("/index.html", "GET", List.of("first.example"), "identity", null);
            responder.invalidate(index);
            assertEquals(0, responder.cachedEntries());
            assertEquals(0, responder.cachedBytes());
        }
    }

    private static String indexTemplate(String label)
    {
        return """
            <!doctype html><html><head><title>SDRTrunk VCE</title>
            <link rel="canonical" href="__VCE_PUBLIC_ORIGIN__/">
            <meta property="og:url" content="__VCE_PUBLIC_ORIGIN__/">
            <meta property="og:image" content="__VCE_PUBLIC_ORIGIN__/assets/vce-social-card.png?v=1">
            <meta name="twitter:image" content="__VCE_PUBLIC_ORIGIN__/assets/vce-social-card.png?v=1">
            </head><body><section id="access-landing">Sign in</section>
            """ + label.repeat(100) + "</body></html>";
    }

    @Test
    void compressesVendorSizedScriptsAndRevalidatesTheSelectedRepresentation() throws Exception
    {
        byte[] original = "export const renderer = 'saved P25 activity';\n".repeat(30_000)
            .getBytes(StandardCharsets.UTF_8);
        Files.write(mRoot.resolve("vendor.js"), original);
        StaticWebAssetResponse responder = new StaticWebAssetResponse();
        try(Fixture fixture = new Fixture(responder))
        {
            HttpResponse<byte[]> compressed = fixture.request("vendor.js?v=3", "GET", "gzip", null);
            assertEquals(200, compressed.statusCode());
            assertEquals("gzip", compressed.headers().firstValue("Content-Encoding").orElseThrow());
            assertEquals("Accept-Encoding", compressed.headers().firstValue("Vary").orElseThrow());
            assertEquals("no-cache", compressed.headers().firstValue("Cache-Control").orElseThrow());
            assertEquals("fixture-revision", compressed.headers().firstValue("X-Sdrtrunk-Web-Revision").orElseThrow());
            assertEquals(compressed.body().length,
                compressed.headers().firstValueAsLong("Content-Length").orElseThrow());
            assertTrue(compressed.body().length < original.length / 2);
            try(GZIPInputStream input = new GZIPInputStream(new ByteArrayInputStream(compressed.body())))
            {
                assertArrayEquals(original, input.readAllBytes());
            }
            String gzipEtag = compressed.headers().firstValue("ETag").orElseThrow();
            HttpResponse<byte[]> unchanged = fixture.request("vendor.js?v=3", "GET", "gzip", "W/" + gzipEtag);
            assertEquals(304, unchanged.statusCode());
            assertEquals(0, unchanged.body().length);
            assertEquals(gzipEtag, unchanged.headers().firstValue("ETag").orElseThrow());
            HttpResponse<byte[]> identity = fixture.request("vendor.js?v=3", "GET", "identity", gzipEtag);
            assertEquals(200, identity.statusCode());
            assertArrayEquals(original, identity.body());
            assertNotEquals(gzipEtag, identity.headers().firstValue("ETag").orElseThrow());
            assertEquals(1, responder.cachedEntries(), "encoding variants share one bounded file snapshot");
        }
    }

    @Test
    void honorsEncodingQualityExclusionsAndLeavesBinaryFilesUncompressed() throws Exception
    {
        byte[] bytes = "saved P25 activity\n".repeat(100).getBytes(StandardCharsets.UTF_8);
        Files.write(mRoot.resolve("feature.js"), bytes);
        Files.write(mRoot.resolve("icon.png"), bytes);
        try(Fixture fixture = new Fixture(new StaticWebAssetResponse()))
        {
            for(String encoding: Arrays.asList(null, "gzip;q=0", "gzip;q=0, *;q=1",
                "gzip;q=0.2, identity;q=0.9", "gzip;q=NaN"))
            {
                HttpResponse<byte[]> response = fixture.request("feature.js", "GET", encoding, null);
                assertEquals(200, response.statusCode());
                assertFalse(response.headers().firstValue("Content-Encoding").isPresent());
                assertArrayEquals(bytes, response.body());
            }
            assertEquals("gzip", fixture.request("feature.js", "GET", "*;q=1, identity;q=0", null)
                .headers().firstValue("Content-Encoding").orElseThrow());
            assertEquals(406, fixture.request("feature.js", "GET", "gzip;q=0, identity;q=0", null).statusCode());
            assertEquals(406, fixture.request("feature.js", "GET", "*;q=0", null).statusCode());
            HttpResponse<byte[]> binary = fixture.request("icon.png", "GET", "gzip", null);
            assertFalse(binary.headers().firstValue("Content-Encoding").isPresent());
            assertArrayEquals(bytes, binary.body());
        }
    }

    @Test
    void headResponsesDescribeTheChosenEncodingWithoutSendingABody() throws Exception
    {
        Files.writeString(mRoot.resolve("feature.js"), "saved P25 activity\n".repeat(100));
        Files.write(mRoot.resolve("empty.js"), new byte[0]);
        try(Fixture fixture = new Fixture(new StaticWebAssetResponse()))
        {
            for(String encoding: Arrays.asList(null, "gzip"))
            {
                HttpResponse<byte[]> get = fixture.request("feature.js", "GET", encoding, null);
                HttpResponse<byte[]> head = fixture.request("feature.js", "HEAD", encoding, null);
                assertEquals(200, head.statusCode());
                assertEquals(0, head.body().length);
                assertEquals(get.headers().firstValue("Content-Length"), head.headers().firstValue("Content-Length"));
                assertEquals(get.headers().firstValue("ETag"), head.headers().firstValue("ETag"));
                assertEquals(get.headers().firstValue("Content-Encoding"), head.headers().firstValue("Content-Encoding"));
                assertEquals(304, fixture.request("feature.js", "HEAD", encoding,
                    get.headers().firstValue("ETag").orElseThrow()).statusCode());
            }
            HttpResponse<byte[]> empty = fixture.request("empty.js", "GET", null, null);
            assertEquals(0, empty.body().length);
            assertEquals(0, empty.headers().firstValueAsLong("Content-Length").orElseThrow());
        }
    }

    @Test
    void previewEditsAndFileReplacementInvalidateCachedScriptsEvenWithTheSameVersionQuery() throws Exception
    {
        Path file = mRoot.resolve("feature.js");
        Files.writeString(file, "const state = 'before';\n".repeat(100));
        StaticWebAssetResponse responder = new StaticWebAssetResponse();
        try(Fixture fixture = new Fixture(responder))
        {
            HttpResponse<byte[]> first = fixture.request("feature.js?v=3", "GET", "gzip", null);
            String firstEtag = first.headers().firstValue("ETag").orElseThrow();
            Files.writeString(file, "const state = 'after!';\n".repeat(100));
            Files.setLastModifiedTime(file, FileTime.from(Instant.now().plusSeconds(10)));
            HttpResponse<byte[]> changed = fixture.request("feature.js?v=3", "GET", "gzip", firstEtag);
            assertEquals(200, changed.statusCode());
            String changedEtag = changed.headers().firstValue("ETag").orElseThrow();
            assertNotEquals(firstEtag, changedEtag);
            FileTime modified = Files.getLastModifiedTime(file);
            Path replacement = mRoot.resolve("replacement.js");
            Files.writeString(replacement, "const state = 'other!';\n".repeat(100));
            Files.setLastModifiedTime(replacement, modified);
            Files.move(replacement, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            HttpResponse<byte[]> replaced = fixture.request("feature.js?v=3", "GET", "gzip", changedEtag);
            assertEquals(200, replaced.statusCode());
            assertNotEquals(changedEtag, replaced.headers().firstValue("ETag").orElseThrow());
            assertEquals(1, responder.cachedEntries());
            assertEquals(304, fixture.request("feature.js?v=3", "GET", "gzip",
                "\"other-validator\", " + replaced.headers().firstValue("ETag").orElseThrow()).statusCode());
        }
    }

    @Test
    void boundsRetainedRepresentationsStreamsOversizedFilesAndReleasesRemovedEntries() throws Exception
    {
        StaticWebAssetResponse responder = new StaticWebAssetResponse(160, 2, 100);
        for(String name: List.of("first.js", "second.js", "third.js"))
            Files.writeString(mRoot.resolve(name), name.repeat(8));
        byte[] large = new byte[101];
        Files.write(mRoot.resolve("large.png"), large);
        try(Fixture fixture = new Fixture(responder))
        {
            for(String name: List.of("first.js", "second.js", "third.js"))
            {
                assertEquals(200, fixture.request(name, "GET", "gzip", null).statusCode());
                assertTrue(responder.cachedBytes() <= 160);
                assertTrue(responder.cachedEntries() <= 2);
            }
            long cachedBytes = responder.cachedBytes();
            int cachedEntries = responder.cachedEntries();
            HttpResponse<byte[]> streamed = fixture.request("large.png", "GET", null, null);
            assertArrayEquals(large, streamed.body());
            assertEquals(cachedBytes, responder.cachedBytes());
            assertEquals(cachedEntries, responder.cachedEntries());
            assertEquals(304, fixture.request("large.png", "GET", null,
                streamed.headers().firstValue("ETag").orElseThrow()).statusCode());
            Path latest = mRoot.resolve("third.js");
            Files.delete(latest);
            assertThrows(java.io.IOException.class, () -> responder.send(null, latest, "text/javascript", null));
            assertTrue(responder.cachedEntries() < cachedEntries);
        }
    }

    private final class Fixture implements AutoCloseable
    {
        private final HttpServer mServer;
        private final HttpClient mClient = HttpClient.newHttpClient();

        private Fixture(StaticWebAssetResponse responder) throws Exception
        {
            mServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            mServer.createContext("/", exchange -> {
                String requestPath = exchange.getRequestURI().getPath();
                Path file = mRoot.resolve(requestPath.equals("/") ? "index.html" : requestPath.substring(1));
                String type = file.toString().endsWith(".png") ? "image/png" :
                    file.toString().endsWith(".html") ? "text/html; charset=utf-8" : "text/javascript; charset=utf-8";
                if(file.getFileName().toString().equals("index.html"))
                    responder.sendIndex(exchange, file, type, "fixture-revision");
                else responder.send(exchange, file, type, "fixture-revision");
            });
            mServer.start();
        }

        private HttpResponse<byte[]> request(String path, String method, String encoding, String etag) throws Exception
        {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" +
                mServer.getAddress().getPort() + "/" + path)).timeout(Duration.ofSeconds(3))
                .method(method, HttpRequest.BodyPublishers.noBody());
            if(encoding != null) request.header("Accept-Encoding", encoding);
            if(etag != null) request.header("If-None-Match", etag);
            return mClient.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        }

        private Response rawRequest(String path, String method, List<String> authorities, String encoding,
                                    String etag) throws Exception
        {
            try(Socket socket = new Socket("127.0.0.1", mServer.getAddress().getPort()))
            {
                socket.setSoTimeout(3_000);
                StringBuilder request = new StringBuilder(method).append(' ').append(path)
                    .append(" HTTP/1.1\r\nConnection: close\r\n");
                authorities.forEach(authority -> request.append("Host: ").append(authority).append("\r\n"));
                request.append("Forwarded: host=forwarded.example;proto=https\r\n")
                    .append("X-Forwarded-Host: forwarded.example\r\nX-Forwarded-Proto: https\r\n");
                if(encoding != null) request.append("Accept-Encoding: ").append(encoding).append("\r\n");
                if(etag != null) request.append("If-None-Match: ").append(etag).append("\r\n");
                request.append("\r\n");
                socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.ISO_8859_1));
                socket.getOutputStream().flush();
                byte[] response = socket.getInputStream().readAllBytes();
                int boundary = new String(response, StandardCharsets.ISO_8859_1).indexOf("\r\n\r\n");
                assertTrue(boundary >= 0, "the local fixture sends complete HTTP headers");
                String[] lines = new String(response, 0, boundary, StandardCharsets.ISO_8859_1).split("\r\n");
                Map<String,String> headers = new LinkedHashMap<>();
                for(int index = 1; index < lines.length; index++)
                {
                    int separator = lines[index].indexOf(':');
                    headers.put(lines[index].substring(0, separator).toLowerCase(java.util.Locale.ROOT),
                        lines[index].substring(separator + 1).trim());
                }
                return new Response(Integer.parseInt(lines[0].split(" ")[1]), headers,
                    Arrays.copyOfRange(response, boundary + 4, response.length));
            }
        }

        @Override public void close()
        {
            mClient.close();
            mServer.stop(0);
        }
    }

    private record Response(int status, Map<String,String> headers, byte[] body) { }

}
