package io.github.dsheirer.web.http;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.web.settings.SpectrumSnapSettings;
import io.github.dsheirer.web.settings.SpectrumSnapSettingsService;
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

class SpectrumSnapPresetHttpControllerTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void readsCatalogAndRevisionControlsAdministratorCountryReplacement() throws Exception
    {
        Path database = temporary.resolve("profile.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        SpectrumSnapPresetHttpController controller = new SpectrumSnapPresetHttpController(
            new SpectrumSnapSettingsService(database));
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(SpectrumSnapPresetHttpController.PATH, controller::handleRead);
        server.createContext(SpectrumSnapPresetHttpController.ADMIN_PATH, controller::handleAdmin);
        server.start();

        try
        {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
            HttpResponse<String> publicResponse = client.send(request(origin, SpectrumSnapPresetHttpController.PATH)
                .GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode initial = success(publicResponse);
            assertEquals("\"1\"", publicResponse.headers().firstValue("ETag").orElseThrow());
            assertEquals("US", initial.path("country_code").textValue());
            assertEquals("United States", initial.path("country_label").textValue());
            assertEquals(1, initial.path("countries").size());
            assertEquals(47, initial.path("scopes").size());
            assertTrue(initial.path("scopes").findValuesAsText("id").contains("ism-900"));

            HttpResponse<String> publicPut = client.send(request(origin, SpectrumSnapPresetHttpController.PATH)
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(
                    "{\"country_code\":\"US\"}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(405, publicPut.statusCode());

            HttpResponse<String> updatedResponse = client.send(request(origin, SpectrumSnapPresetHttpController.ADMIN_PATH)
                .header("Content-Type", "application/json").header("If-Match", "\"1\"")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"country_code\":\"US\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
            JsonNode updated = success(updatedResponse);
            assertEquals(2, updated.path("revision").longValue());
            assertEquals(2, SpectrumSnapSettings.read(database).revision());

            HttpResponse<String> stale = client.send(request(origin, SpectrumSnapPresetHttpController.ADMIN_PATH)
                .header("Content-Type", "application/json").header("If-Match", "\"1\"")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"country_code\":\"US\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(409, stale.statusCode());
            assertEquals(2, JSON.readTree(stale.body()).path("revision").longValue());

            HttpResponse<String> unsupported = client.send(request(origin, SpectrumSnapPresetHttpController.ADMIN_PATH)
                .header("Content-Type", "application/json").header("If-Match", "\"2\"")
                .PUT(HttpRequest.BodyPublishers.ofString("{\"country_code\":\"CA\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
            assertEquals(422, unsupported.statusCode());
            assertEquals(2, SpectrumSnapSettings.read(database).revision());
        }
        finally
        {
            server.stop(0);
        }
    }

    private static HttpRequest.Builder request(URI origin, String path)
    {
        return HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(10));
    }

    private static JsonNode success(HttpResponse<String> response) throws Exception
    {
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300, response.body());
        return JSON.readTree(response.body());
    }
}
