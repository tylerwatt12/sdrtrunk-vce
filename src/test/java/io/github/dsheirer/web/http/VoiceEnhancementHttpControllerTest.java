package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.audio.codec.mbe.JmbeVocoderAudio;
import io.github.dsheirer.audio.enhance.VocoderAudioFixtures;
import io.github.dsheirer.audio.enhance.VoiceEnhancementService;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VoiceEnhancementHttpControllerTest
{
    @TempDir Path temporary;

    @Test
    void selectedJmbeOwnsTheModelAndOnlyTheEnabledSettingCanBeWritten() throws Exception
    {
        AtomicReference<JmbeVocoderAudio> selected = new AtomicReference<>();
        Path database = temporary.resolve("settings.sqlite");
        SdrTrunkTestDatabase.create(database);
        var service = new VoiceEnhancementService(new ApplicationSettingsStore(database), selected::get);
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
            String initialEtag = initial.headers().firstValue("ETag").orElseThrow();
            assertFalse(json.readTree(initial.body()).path("enabled").asBoolean());
            assertEquals("unavailable", json.readTree(initial.body()).path("status").textValue());
            assertEquals(428, send(client, request(URI.create(base + "/enabled"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString("{\"value\":true}"))).statusCode());
            var unavailable = send(client, request(URI.create(base + "/enabled"))
                .header("If-Match", initialEtag).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":true}")));
            assertEquals(422, unavailable.statusCode());
            assertTrue(unavailable.body().contains("Install a current JMBE library"));
            assertEquals(404, send(client, request(URI.create(base + "/model"))
                .header("If-Match", initialEtag).header("Content-Type", "application/zip")
                .POST(HttpRequest.BodyPublishers.ofByteArray(new byte[]{80, 75, 3, 4}))).statusCode());
            assertEquals(404, send(client, request(URI.create(base + "/model")).GET()).statusCode());

            selected.set(VocoderAudioFixtures.provider("embedded-http-model").bridge());
            var ready = send(client, request(base).GET());
            assertEquals("embedded-http-model", json.readTree(ready.body()).path("model").path("id").textValue());
            assertEquals("ready", json.readTree(ready.body()).path("status").textValue());
            String readyEtag = ready.headers().firstValue("ETag").orElseThrow();
            assertNotEquals(initialEtag, readyEtag);
            assertEquals(409, send(client, request(URI.create(base + "/enabled"))
                .header("If-Match", initialEtag).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":true}"))).statusCode());
            assertEquals(400, send(client, request(URI.create(base + "/enabled"))
                .header("If-Match", readyEtag).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":true,\"unexpected\":1}"))).statusCode());
            assertEquals(422, send(client, request(URI.create(base + "/enabled"))
                .header("If-Match", readyEtag).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":\"true\"}"))).statusCode());
            assertEquals(200, send(client, request(URI.create(base + "/enabled"))
                .header("If-Match", readyEtag).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":true}"))).statusCode());
            assertTrue(service.snapshot().enabled());
            selected.set(null);
            var removed = send(client, request(base).GET());
            String removedEtag = removed.headers().firstValue("ETag").orElseThrow();
            assertEquals(200, send(client, request(URI.create(base + "/enabled"))
                .header("If-Match", removedEtag).header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"value\":false}"))).statusCode());
            assertFalse(service.snapshot().enabled());
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
