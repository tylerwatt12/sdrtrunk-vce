package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alerts.TopicAlertConfiguration;
import io.github.dsheirer.alerts.TopicAlertService;
import io.github.dsheirer.alerts.delivery.PushDeliveryService;
import io.github.dsheirer.alerts.delivery.PublicAccessVerifier;
import io.github.dsheirer.alerts.delivery.WebPushCodec;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.web.auth.WebAccessService;
import io.github.dsheirer.web.auth.WebAuthenticationService;
import io.github.dsheirer.web.auth.WebCapability;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class TopicAlertsHttpControllerTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path mDirectory;

    @Test void guestTopicsBypassGlobalLoginButAdminStillNeedsSessionAndDirectProof() throws Exception
    {
        try(Fixture f = new Fixture(mDirectory.resolve("alerts.sqlite")))
        {
            assertEquals(200, f.send("GET", "/api/v1/alerts/topics", null, null, false).statusCode());
            assertEquals(0, f.body(f.send("GET", "/api/v1/alerts/topics", null, null, false)).path("topics").size());
            assertEquals(401, f.send("GET", "/api/v1/admin/alerts/topics", null, null, false).statusCode());
            f.login();
            var bad = f.send("PUT", "/api/v1/admin/alerts/config", Map.of("revision", 1, "enabled", true,
                "mode", "direct", "site_url", "https://vce.example", "test_token", "claimed-passed"), null, true);
            assertEquals(422, bad.statusCode());
            assertEquals("public_test_required", f.body(bad).at("/error/code").asText());
            assertEquals("proxy", f.service.configuration().push().mode());
            var saved = f.send("POST", "/api/v1/admin/alerts/topics", Map.of("revision", 1, "topic", Map.of(
                "id", "fire", "name", "Fire activity", "description", "", "visible", true, "enabled", true, "built_in", false)), null, true);
            assertEquals(200, saved.statusCode(), saved.body());
            assertEquals(2, f.body(saved).path("revision").asLong());
            assertEquals(1, f.body(f.send("GET", "/api/v1/alerts/topics", null, null, false)).path("topics").size());
            var stale = f.send("POST", "/api/v1/admin/alerts/topics", Map.of("revision", 1, "topic", Map.of(
                "id", "other", "name", "Other", "description", "", "visible", true, "enabled", true, "built_in", false)), null, true);
            assertEquals(409, stale.statusCode());
        }
    }

    @Test void anonymousDeviceMutationsRequireSameOriginAndUnforgeableManagementToken() throws Exception
    {
        try(Fixture f = new Fixture(mDirectory.resolve("subscriptions.sqlite")))
        {
            var current = f.service.configuration();
            var topic = new io.github.dsheirer.alerts.AlertTopic("fire", "Fire", "", true, true, false);
            f.service.saveTopic(topic, current.revision()); current = f.service.configuration();
            f.service.updateConfiguration(new TopicAlertConfiguration(current.revision(),
                new TopicAlertConfiguration.PushSettings(true, "direct", "https://vce.example", "VCE"), current.topics(), current.rules()), current.revision());
            String publicKey = WebPushCodec.generateKeys().publicKey();
            Map<String,Object> request = Map.of("topic_ids", java.util.List.of("fire"), "prefs", Map.of("paused", false),
                "subscription", Map.of("endpoint", "https://fcm.googleapis.com/send/public-test-fixture",
                    "keys", Map.of("p256dh", publicKey, "auth", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]))));
            assertEquals(403, f.send("POST", "/api/v1/alerts/enrollments", request, null, false).statusCode());
            var enrolled = f.send("POST", "/api/v1/alerts/enrollments", request, null, true);
            assertEquals(200, enrolled.statusCode(), enrolled.body());
            String token = f.body(enrolled).path("manage_token").asText();
            var device = f.send("GET", "/api/v1/alerts/subscriptions/me", null, token, false);
            assertEquals(200, device.statusCode());
            for(String secret: java.util.List.of("endpoint", "p256dh", "auth", "manage_token_hash", "remote_device_id"))
                assertFalse(f.body(device).has(secret), secret);
            assertEquals(403, f.send("GET", "/api/v1/alerts/subscriptions/me", null, PublicAccessVerifier.token(), false).statusCode());
            assertEquals(422, f.send("PUT", "/api/v1/alerts/subscriptions/me", Map.of("topic_ids", java.util.List.of("administrator-alerts"), "prefs", Map.of()), token, true).statusCode());
            assertEquals(200, f.send("DELETE", "/api/v1/alerts/subscriptions/me", null, token, true).statusCode());
            assertEquals(403, f.send("GET", "/api/v1/alerts/subscriptions/me", null, token, false).statusCode());
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final TopicAlertService service; final PushDeliveryService push; final HttpServer server;
        final HttpClient client = HttpClient.newHttpClient(); final WebRequestSecurity security;
        final URI origin; String cookie = "", csrf = "";
        Fixture(Path database) throws Exception
        {
            SdrTrunkDatabaseStartup.createGlobalDatabase(database);
            try(var connection = SdrTrunkDatabase.open(database); var insert = connection.createStatement())
            { insert.executeUpdate("INSERT INTO web_access_policy VALUES ('web-access','ADMIN',1)"); }
            WebAccessService access = new WebAccessService(database);
            access.provisionOrResetPrimaryAdmin("fixture-admin-password".toCharArray());
            var authentication = new WebAuthenticationService(access);
            security = new WebRequestSecurity(access, authentication);
            service = new TopicAlertService(database); push = new PushDeliveryService(database, service);
            var controller = new TopicAlertsHttpController(service, push, Map::of);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            new WebSessionHttpController(access, authentication, security).register(server);
            server.createContext(TopicAlertsHttpController.ADMIN_PATH, security.protectApi(WebCapability.ADMIN_SETTINGS, controller::handleAdmin));
            server.createContext(TopicAlertsHttpController.PUBLIC_PATH, controller::handlePublic);
            server.start(); origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }
        void login() throws Exception
        {
            var response = send("POST", WebSessionHttpController.LOGIN_PATH, Map.of("username", "admin", "password", "fixture-admin-password"), null, true);
            assertEquals(200, response.statusCode(), response.body());
            cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            csrf = body(response).path("csrf_token").asText();
        }
        HttpResponse<String> send(String method, String path, Object body, String token, boolean mutation) throws Exception
        {
            var request = HttpRequest.newBuilder(origin.resolve(path)).timeout(Duration.ofSeconds(5));
            if(mutation) request.header("Origin", origin.toString());
            if(!cookie.isEmpty()) request.header("Cookie", cookie).header("X-CSRF-Token", csrf);
            if(token != null) request.header("Authorization", "Bearer " + token);
            if(body != null) request.header("Content-Type", "application/json");
            return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() :
                HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
        }
        JsonNode body(HttpResponse<String> response) throws Exception
        {
            JsonNode parsed = JSON.readTree(response.body());
            return parsed.has("data") ? parsed.path("data") : parsed;
        }
        public void close() { server.stop(0); security.close(); push.close(); service.close(); }
    }
}
