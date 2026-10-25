/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.support.SupportBundleService;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SupportReportHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

    @Test
    void generatingReportsKeepHttpStatusAndNavigationResponsiveAndCanBeCancelled() throws Exception
    {
        CountDownLatch generating = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        try(SupportBundleService service = new SupportBundleService(mTemporaryDirectory.resolve("unused.sqlite"),
            mTemporaryDirectory.resolve("logs"), () -> {
                generating.countDown();
                try { new CountDownLatch(1).await(); }
                catch(InterruptedException exception) { interrupted.countDown(); }
                return Map.of();
            }); HttpClientHolder holder = start(service))
        {
            holder.server.createContext("/navigation", exchange -> {
                try(exchange) { ApiHttpResponse.sendData(exchange, 200, Map.of("page", "receiver")); }
            });
            String body = """
                {"title":"Receiver problem","email":"operator@example.com","category":"Other",
                 "issue":"Something else","description":"Check the current status.",
                 "steps":"Open Receiver status.","sections":["health"]}
                """;
            var created = holder.send(HttpRequest.newBuilder(holder.uri(""))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)));
            assertEquals(202, created.statusCode(), created.body());
            String id = MAPPER.readTree(created.body()).at("/data/id").textValue();
            assertTrue(generating.await(5, TimeUnit.SECONDS));
            var creates = java.util.stream.IntStream.range(0, 12).mapToObj(index -> holder.client.sendAsync(
                HttpRequest.newBuilder(holder.uri("")).header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(3)).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString())).toList();
            CompletableFuture.allOf(creates.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
            assertEquals(7, creates.stream().filter(create -> create.join().statusCode() == 202).count(),
                "Concurrent submissions retain the eight-report admission bound");
            assertEquals(5, creates.stream().filter(create -> create.join().statusCode() == 400).count());
            for(var create: creates)
            {
                if(create.join().statusCode() == 202)
                {
                    String queued = MAPPER.readTree(create.join().body()).at("/data/id").textValue();
                    var cancelled = holder.send(HttpRequest.newBuilder(holder.uri("/" + queued + "/cancel"))
                        .POST(HttpRequest.BodyPublishers.noBody()));
                    assertEquals("cancelled", MAPPER.readTree(cancelled.body()).at("/data/state").textValue());
                }
            }
            var reads = java.util.stream.IntStream.range(0, 20).mapToObj(index -> holder.client.sendAsync(
                HttpRequest.newBuilder(index % 2 == 0 ? holder.uri("/" + id) : holder.origin.resolve("/navigation"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString())).toList();
            CompletableFuture.allOf(reads.toArray(CompletableFuture[]::new)).get(3, TimeUnit.SECONDS);
            for(var read: reads) assertEquals(200, read.join().statusCode(), read.join().body());
            var stopped = holder.send(HttpRequest.newBuilder(holder.uri("/" + id + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(200, stopped.statusCode(), stopped.body());
            assertEquals("cancelled", MAPPER.readTree(stopped.body()).at("/data/state").textValue());
            assertTrue(interrupted.await(3, TimeUnit.SECONDS));
            assertEquals(409, holder.send(HttpRequest.newBuilder(holder.uri("/" + id + "/download")).GET())
                .statusCode());
            assertEquals(409, holder.send(HttpRequest.newBuilder(holder.uri("/" + id + "/submit"))
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
            var finalStatus = holder.send(HttpRequest.newBuilder(holder.uri("/" + id)).GET());
            assertEquals("cancelled", MAPPER.readTree(finalStatus.body()).at("/data/state").textValue());
        }
    }

    @Test
    void createsPollsDownloadsAndCancelsBundle() throws Exception
    {
        Path database = mTemporaryDirectory.resolve("sdrtrunk.db");
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            var statement = connection.createStatement())
        {
            statement.execute("CREATE TABLE database_metadata(key TEXT, value TEXT, updated_at_ms INTEGER)");
            statement.execute("INSERT INTO database_metadata VALUES('database_format_version','21',1)");
        }
        Path logs = Files.createDirectories(mTemporaryDirectory.resolve("logs"));
        try(SupportBundleService service = new SupportBundleService(database, logs, () -> Map.of());
            HttpClientHolder holder = start(service))
        {
            String body = """
                {
                  "title":"Receiver problem",
                  "email":"operator@example.com",
                  "category":"Other",
                  "issue":"Something else",
                  "description":"The receiver did not behave as expected.",
                  "steps":"Start the receiver and wait.",
                  "sections":["application","setup"]
                }
                """;
            HttpResponse<String> created = holder.send(HttpRequest.newBuilder(holder.uri(""))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)));
            assertEquals(202, created.statusCode(), created.body());
            String id = MAPPER.readTree(created.body()).at("/data/id").textValue();
            JsonNode ready = awaitReady(holder, id);
            assertEquals("ready", ready.path("state").textValue());
            assertTrue(ready.path("bytes").longValue() > 0);

            HttpResponse<byte[]> download = holder.client.send(HttpRequest.newBuilder(holder.uri("/" + id +
                "/download")).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, download.statusCode());
            assertTrue(download.body().length > 0);
            assertEquals("application/zip", download.headers().firstValue("Content-Type").orElseThrow());

            HttpResponse<String> cancelled = holder.send(HttpRequest.newBuilder(holder.uri("/" + id + "/cancel"))
                .POST(HttpRequest.BodyPublishers.noBody()));
            assertEquals(200, cancelled.statusCode());
            assertEquals("cancelled", MAPPER.readTree(cancelled.body()).at("/data/state").textValue());
            assertEquals(409, holder.send(HttpRequest.newBuilder(holder.uri("/" + id + "/submit"))
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
            assertEquals(404, holder.send(HttpRequest.newBuilder(holder.uri("/missing"))
                .GET()).statusCode());
            assertEquals(400, holder.send(HttpRequest.newBuilder(holder.uri("/" + id + "/cancel"))
                .POST(HttpRequest.BodyPublishers.ofString("{}"))).statusCode());
        }
    }

    private static JsonNode awaitReady(HttpClientHolder holder, String id) throws Exception
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while(System.nanoTime() < deadline)
        {
            HttpResponse<String> response = holder.send(HttpRequest.newBuilder(holder.uri("/" + id)).GET());
            assertEquals(200, response.statusCode());
            JsonNode data = MAPPER.readTree(response.body()).path("data");
            if(!data.path("state").textValue().matches("queued|generating")) return data;
            Thread.sleep(20);
        }
        throw new AssertionError("Support bundle did not finish");
    }

    private static HttpClientHolder start(SupportBundleService service) throws Exception
    {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        SupportReportHttpController controller = new SupportReportHttpController(service);
        WebRequestExecutor executor = new WebRequestExecutor(64, () -> { });
        server.setExecutor(executor);
        server.createContext(SupportReportHttpController.PATH, controller::handle);
        server.start();
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new HttpClientHolder(server, HttpClient.newHttpClient(), origin, executor);
    }

    private record HttpClientHolder(HttpServer server, HttpClient client, URI origin, WebRequestExecutor executor)
        implements AutoCloseable
    {
        private URI uri(String suffix) { return origin.resolve(SupportReportHttpController.PATH + suffix); }
        private HttpResponse<String> send(HttpRequest.Builder request) throws Exception
        {
            return client.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
