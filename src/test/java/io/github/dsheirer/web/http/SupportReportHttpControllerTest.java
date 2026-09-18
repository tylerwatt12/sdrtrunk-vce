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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SupportReportHttpControllerTest
{
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path mTemporaryDirectory;

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
        server.createContext(SupportReportHttpController.PATH, controller::handle);
        server.start();
        URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        return new HttpClientHolder(server, HttpClient.newHttpClient(), origin);
    }

    private record HttpClientHolder(HttpServer server, HttpClient client, URI origin) implements AutoCloseable
    {
        private URI uri(String suffix) { return origin.resolve(SupportReportHttpController.PATH + suffix); }
        private HttpResponse<String> send(HttpRequest.Builder request) throws Exception
        {
            return client.send(request.timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        }
        @Override public void close() { server.stop(0); }
    }
}
