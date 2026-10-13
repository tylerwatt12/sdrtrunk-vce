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
            Map<String,Object> request = Map.of("topic_ids", java.util.List.of("fire"), "prefs", Map.of("paused", true, "quiet_enabled", true, "time_zone", "retired-invalid-zone"),
                "subscription", Map.of("endpoint", "https://fcm.googleapis.com/send/public-test-fixture",
                    "keys", Map.of("p256dh", publicKey, "auth", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]))));
            assertEquals(403, f.send("POST", "/api/v1/alerts/enrollments", request, null, false).statusCode());
            var enrolled = f.send("POST", "/api/v1/alerts/enrollments", request, null, true);
            assertEquals(200, enrolled.statusCode(), enrolled.body());
            String token = f.body(enrolled).path("manage_token").asText();
            var device = f.send("GET", "/api/v1/alerts/subscriptions/me", null, token, false);
            assertEquals(200, device.statusCode());
            for(String secret: java.util.List.of("prefs", "paused", "quiet_start", "quiet_end", "time_zone", "endpoint", "p256dh", "auth", "manage_token_hash", "remote_device_id"))
                assertFalse(f.body(device).has(secret), secret);
            assertEquals(403, f.send("GET", "/api/v1/alerts/subscriptions/me", null, PublicAccessVerifier.token(), false).statusCode());
            assertEquals(422, f.send("PUT", "/api/v1/alerts/subscriptions/me", Map.of("topic_ids", java.util.List.of("administrator-alerts"), "prefs", Map.of()), token, true).statusCode());
            assertEquals(200, f.send("DELETE", "/api/v1/alerts/subscriptions/me", null, token, true).statusCode());
            assertEquals(403, f.send("GET", "/api/v1/alerts/subscriptions/me", null, token, false).statusCode());
        }
    }

    @Test void checkerUnavailableAndUnapprovedRegistrationHaveDistinctActionableErrors() throws Exception
    {
        HttpServer gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requestedName = new java.util.concurrent.atomic.AtomicReference<String>();
        gateway.createContext("/public-test", exchange -> { exchange.sendResponseHeaders(404, -1); exchange.close(); });
        gateway.createContext("/senders", exchange -> {
            requestedName.set(JSON.readTree(exchange.getRequestBody()).path("sender_name").asText());
            exchange.sendResponseHeaders(403, -1); exchange.close();
        }); gateway.start();
        try(Fixture f = new Fixture(mDirectory.resolve("gateway-errors.sqlite"), URI.create("http://127.0.0.1:" + gateway.getAddress().getPort() + "/")))
        {
            f.login();
            f.optionsUnavailable = true;
            var sourceFailure = f.send("GET", "/api/v1/admin/alerts/topics", null, null, false);
            assertEquals(503, sourceFailure.statusCode());
            assertEquals("Notifications are temporarily unavailable. Try again later.", f.body(sourceFailure).at("/error/message").asText());
            f.optionsUnavailable = false;
            var unavailable = f.send("POST", "/api/v1/admin/alerts/public-test", Map.of("site_url", "https://vce.example"), null, true);
            assertEquals(200, unavailable.statusCode());
            assertEquals("unavailable", f.body(unavailable).at("/public_access/status").asText());
            assertTrue(f.body(unavailable).at("/public_access/message").asText().contains("has not checked"));
            assertEquals(422, f.send("POST", "/api/v1/admin/alerts/public-test", Map.of("site_url", "http://vce.example"), null, true).statusCode());
            var rejected = f.send("POST", "/api/v1/admin/alerts/registration", Map.of("sender_label", "Test receiver"), null, true);
            assertEquals(403, rejected.statusCode());
            assertEquals("gateway_not_approved", f.body(rejected).at("/error/code").asText());
            assertEquals("Test receiver", requestedName.get());
            assertEquals("Test receiver", f.service.configuration().push().senderLabel());
        }
        finally { gateway.stop(0); }
    }

    @Test void historyActionsUseCurrentAnonymousPolicyEvenForAnAdministratorSession() throws Exception
    {
        try(Fixture f = new Fixture(mDirectory.resolve("public-actions.sqlite")))
        {
            f.login();
            var topic = new io.github.dsheirer.alerts.AlertTopic("fire", "Fire", "", true, true, false);
            f.service.saveTopic(topic, f.service.configuration().revision());
            var store = new io.github.dsheirer.alerts.TopicAlertStore(f.database);
            long now = System.currentTimeMillis();
            store.publish(topic, "rule", "Rule", new io.github.dsheirer.alerts.AlertEvent("channel-event", 1000, "event", Map.of()), "Channel", "Body", "listen",
                new io.github.dsheirer.alerts.AlertAction("channel", io.github.dsheirer.stats.WebEntityReferences.channel("00000000-0000-4000-8000-000000000001")), java.util.List.of(), now);
            store.publish(topic, "rule", "Rule", new io.github.dsheirer.alerts.AlertEvent("health-event", 1000, "health", Map.of()), "Health", "Body", "details",
                io.github.dsheirer.alerts.AlertAction.LIVE, java.util.List.of(), now + 1);
            var gated = f.body(f.send("GET", "/api/v1/alerts/history?topic=fire", null, null, false)).path("items");
            for(var item: gated) assertTrue(item.path("action").isNull(), "The administrator session must not grant guest CTA eligibility");
            f.access.setCapabilityTier("web-access", io.github.dsheirer.web.auth.AccessTier.PUBLIC);
            f.access.setCapabilityTier("radio", io.github.dsheirer.web.auth.AccessTier.PUBLIC);
            f.access.setCapabilityTier("live", io.github.dsheirer.web.auth.AccessTier.PUBLIC);
            var publicItems = f.body(f.send("GET", "/api/v1/alerts/history?topic=fire", null, null, false)).path("items");
            assertEquals(2, publicItems.size());
            for(var item: publicItems)
            {
                assertFalse(item.path("action").isNull());
                var detail = f.body(f.send("GET", "/api/v1/alerts/history/" + item.path("id").asText(), null, null, false));
                assertEquals(item.path("action"), detail.path("action"));
                assertFalse(detail.path("action").has("url"));
            }
            f.access.setCapabilityTier("radio", io.github.dsheirer.web.auth.AccessTier.USER);
            f.access.setCapabilityTier("live", io.github.dsheirer.web.auth.AccessTier.ADMIN);
            for(var item: f.body(f.send("GET", "/api/v1/alerts/history?topic=fire", null, null, false)).path("items"))
                assertTrue(item.path("action").isNull());
        }
    }

    @Test void builtinActionSettingOnlyAcceptsTheBuiltinTopicAndSurvivesPushSettingsSave() throws Exception
    {
        try(Fixture f = new Fixture(mDirectory.resolve("builtin-action-edit.sqlite")))
        {
            f.login();
            var other = Map.of("id", "fire", "name", "Fire", "description", "", "visible", true, "enabled", true, "built_in", false);
            assertEquals(422, f.send("PUT", "/api/v1/admin/alerts/topics", Map.of("revision", f.service.configuration().revision(), "topic", other,
                "builtin_action_button_enabled", true), null, true).statusCode());
            var builtin = Map.of("id", "administrator-alerts", "name", "Administrator alerts", "description", "", "visible", true, "enabled", true, "built_in", true);
            assertEquals(422, f.send("PUT", "/api/v1/admin/alerts/topics", Map.of("revision", f.service.configuration().revision(), "topic", builtin,
                "builtin_action_button_enabled", "true"), null, true).statusCode());
            var saved = f.send("PUT", "/api/v1/admin/alerts/topics", Map.of("revision", f.service.configuration().revision(), "topic", builtin,
                "builtin_action_button_enabled", true), null, true);
            assertEquals(200, saved.statusCode(), saved.body());
            assertTrue(f.body(saved).path("builtin_action_button_enabled").asBoolean());
            var pushSaved = f.send("PUT", "/api/v1/admin/alerts/config", Map.of("revision", f.service.configuration().revision(), "enabled", false,
                "mode", "proxy", "sender_label", "VCE"), null, true);
            assertEquals(200, pushSaved.statusCode(), pushSaved.body());
            assertTrue(f.service.configuration().builtinActionButtonEnabled());
            assertTrue(new io.github.dsheirer.alerts.TopicAlertStore(f.database).configuration().builtinActionButtonEnabled());
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final TopicAlertService service; final PushDeliveryService push; final HttpServer server;
        final WebAccessService access; final Path database;
        final HttpClient client = HttpClient.newHttpClient(); final WebRequestSecurity security;
        boolean optionsUnavailable;
        final URI origin; String cookie = "", csrf = "";
        Fixture(Path database) throws Exception { this(database, null); }
        Fixture(Path database, URI gateway) throws Exception
        {
            this.database = database;
            SdrTrunkDatabaseStartup.createGlobalDatabase(database);
            try(var connection = SdrTrunkDatabase.open(database); var insert = connection.createStatement())
            { insert.executeUpdate("INSERT INTO web_access_policy VALUES ('web-access','ADMIN',1)"); }
            access = new WebAccessService(database);
            access.provisionOrResetPrimaryAdmin("fixture-admin-password".toCharArray());
            var authentication = new WebAuthenticationService(access);
            security = new WebRequestSecurity(access, authentication);
            service = new TopicAlertService(database);
            if(gateway == null) push = new PushDeliveryService(database, service);
            else
            {
                var clientConstructor = io.github.dsheirer.alerts.delivery.RadioResolvePushClient.class.getDeclaredConstructor(URI.class, HttpClient.class);
                clientConstructor.setAccessible(true);
                var deliveryConstructor = PushDeliveryService.class.getDeclaredConstructor(Path.class, TopicAlertService.class, io.github.dsheirer.alerts.delivery.RadioResolvePushClient.class);
                deliveryConstructor.setAccessible(true);
                push = deliveryConstructor.newInstance(database, service, clientConstructor.newInstance(gateway, HttpClient.newHttpClient()));
            }
            var controller = new TopicAlertsHttpController(service, push, () -> { if(optionsUnavailable) throw new IllegalStateException("Alert source choices are temporarily unavailable"); return Map.of(); }, () -> { }, access);
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
