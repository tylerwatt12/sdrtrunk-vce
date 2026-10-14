package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.audio.tone.PagingToneSettingsService;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PagingToneHttpControllerTest
{
    @TempDir Path directory;

    @Test
    void validatesRequestsAndPublishesAtomicSavedToggle() throws Exception
    {
        Path database = directory.resolve("settings.sqlite");
        SdrTrunkTestDatabase.create(database);
        var service = new PagingToneSettingsService(new ApplicationSettingsStore(database), ignored -> {});
        var controller = new PagingToneHttpController(service);
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(PagingToneHttpController.PATH, controller::handle);
        server.start();
        try
        {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + PagingToneHttpController.PATH);
            URI setting = URI.create(base + "/enabled");
            var client = HttpClient.newHttpClient();
            var initial = send(client, request(base).GET());
            assertEquals(200, initial.statusCode());
            assertTrue(new ObjectMapper().readTree(initial.body()).path("enabled").asBoolean());
            String revision = initial.headers().firstValue("ETag").orElseThrow();
            assertEquals(428, send(client, request(setting).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":false}"))).statusCode());
            assertEquals(400, send(client, request(setting).header("If-Match", "*")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":false}"))).statusCode());
            assertEquals(422, update(client, setting, revision, "{\"value\":1}").statusCode());
            assertEquals(400, update(client, setting, revision, "{\"value\":false,\"extra\":0}").statusCode());
            assertEquals(400, update(client, setting, revision, "{\"value\":false,\"value\":true}").statusCode());
            assertEquals(200, update(client, setting, revision, "{\"value\":false}").statusCode());
            assertFalse(service.snapshot().enabled());
            assertEquals(409, update(client, setting, revision, "{\"value\":true}").statusCode());
            assertFalse(service.snapshot().enabled());
            assertEquals(405, send(client, request(setting).GET()).statusCode());
            assertEquals(404, send(client, request(URI.create(base + "/extra")).GET()).statusCode());
            assertEquals(400, send(client, request(URI.create(base + "?extra=true")).GET()).statusCode());
        }
        finally { server.stop(0); }
    }

    private static HttpRequest.Builder request(URI uri)
    { return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)); }
    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception
    { return client.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
    private static HttpResponse<String> update(HttpClient client, URI uri, String revision, String body) throws Exception
    { return send(client, request(uri).header("If-Match", revision).header("Content-Type", "application/json")
        .PUT(HttpRequest.BodyPublishers.ofString(body))); }
}
