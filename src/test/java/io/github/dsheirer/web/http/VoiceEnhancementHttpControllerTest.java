package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.audio.enhance.ModelBundleFixtures;
import io.github.dsheirer.audio.enhance.VoiceEnhancementService;
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

class VoiceEnhancementHttpControllerTest
{
    @TempDir Path temporary;

    @Test
    void validatesRevisionBodyAndModelBeforePublishingNewSelection() throws Exception
    {
        var service = new VoiceEnhancementService(temporary);
        var controller = new VoiceEnhancementHttpController(service);
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(VoiceEnhancementHttpController.PATH, controller::handle);
        server.start();
        try
        {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + VoiceEnhancementHttpController.PATH);
            var client = HttpClient.newHttpClient();
            var json = new ObjectMapper();
            var initial = send(client, request(base).GET());
            assertEquals(200, initial.statusCode());
            String etag = initial.headers().firstValue("ETag").orElseThrow();
            assertFalse(json.readTree(initial.body()).path("enabled").asBoolean());
            assertEquals(428, send(client, request(URI.create(base + "/enabled"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("{\"value\":true}"))).statusCode());
            assertEquals(422, send(client, request(URI.create(base + "/enabled"))
                .header("If-Match", etag).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":true}"))).statusCode());
            assertEquals(415, send(client, request(URI.create(base + "/model"))
                .header("If-Match", etag).POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1}))).statusCode());
            assertEquals(422, send(client, request(URI.create(base + "/model"))
                .header("If-Match", etag).header("Content-Type", "application/zip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{1}))).statusCode());
            var installed = send(client, request(URI.create(base + "/model"))
                .header("If-Match", etag).header("Content-Type", "application/zip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(ModelBundleFixtures.bundle("http-fixture"))));
            assertEquals(200, installed.statusCode());
            assertEquals("http-fixture", json.readTree(installed.body()).path("model").path("id").textValue());
            String installedEtag = installed.headers().firstValue("ETag").orElseThrow();
            assertEquals(409, send(client, request(URI.create(base + "/enabled"))
                .header("If-Match", etag).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":true}"))).statusCode());
            assertEquals(400, send(client, request(URI.create(base + "/enabled"))
                .header("If-Match", installedEtag).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":true,\"unexpected\":1}"))).statusCode());
            assertEquals(200, send(client, request(URI.create(base + "/enabled"))
                .header("If-Match", installedEtag).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":true}"))).statusCode());
            assertTrue(service.snapshot().enabled());
            assertEquals(405, send(client, request(URI.create(base + "/enabled")).GET()).statusCode());
            assertEquals(404, send(client, request(URI.create(base + "/other")).GET()).statusCode());
            assertEquals(400, send(client, request(URI.create(base + "?extra=true")).GET()).statusCode());
        }
        finally { server.stop(0); }
    }

    private static HttpRequest.Builder request(URI uri)
    { return HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(5)); }
    private static HttpResponse<String> send(HttpClient client, HttpRequest.Builder request) throws Exception
    { return client.send(request.build(), HttpResponse.BodyHandlers.ofString()); }
}
