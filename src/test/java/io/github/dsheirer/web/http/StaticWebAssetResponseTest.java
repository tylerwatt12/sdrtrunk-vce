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
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StaticWebAssetResponseTest
{
    @TempDir Path mRoot;

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
                Path file = mRoot.resolve(exchange.getRequestURI().getPath().substring(1));
                String type = file.toString().endsWith(".png") ? "image/png" : "text/javascript; charset=utf-8";
                responder.send(exchange, file, type, "fixture-revision");
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

        @Override public void close()
        {
            mClient.close();
            mServer.stop(0);
        }
    }

}
