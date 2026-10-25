package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alerts.AlertDevice;
import io.github.dsheirer.alerts.AlertRule;
import io.github.dsheirer.alerts.AlertTopic;
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
import java.io.IOException;
import java.sql.SQLException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
import static org.junit.jupiter.api.Assertions.*;

class TopicAlertsHttpControllerTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    @TempDir Path mDirectory;

    @Test void heldAlertSaveLeavesActualHttpCatalogSetupAndHistoryResponsive() throws Exception
    {
        try(Fixture f = new Fixture(mDirectory.resolve("held-alert-save.sqlite")))
        {
            f.login();
            var topic = new AlertTopic("fire", "Fire", "", true, true, false);
            f.service.saveTopic(topic, f.service.configuration().revision());
            var current = f.service.configuration();
            f.service.updateConfiguration(new TopicAlertConfiguration(current.revision(),
                new TopicAlertConfiguration.PushSettings(true, "direct", "https://vce.example", "VCE"),
                current.topics(), current.rules()), current.revision());
            f.push.publicKey();
            new io.github.dsheirer.alerts.TopicAlertStore(f.database).publish(topic, "rule", "Rule",
                new io.github.dsheirer.alerts.AlertEvent("call", 1000, "call", Map.of()), "Call", "Body", "details",
                List.of(), System.currentTimeMillis());
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread writer = null;
            try(var held = SdrTrunkDatabase.open(f.database); var sql = held.createStatement())
            {
                sql.execute("BEGIN IMMEDIATE");
                writer = Thread.ofPlatform().start(() -> {
                    try { f.service.saveTopic(new AlertTopic("fire", "Changed", "", true, true, false), f.service.configuration().revision()); }
                    catch(Throwable exception) { failure.set(exception); }
                });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                while(java.util.Arrays.stream(writer.getStackTrace()).noneMatch(frame ->
                    frame.getClassName().equals("org.sqlite.core.NativeDB") && frame.getMethodName().contains("step")))
                {
                    if(System.nanoTime() >= deadline) throw new AssertionError("The fixture save did not reach SQLite");
                    Thread.sleep(5);
                }
                try
                {
                    CompletableFuture.runAsync(() -> {
                        try
                        {
                            for(int index = 0; index < 20; index++)
                                for(String path: List.of("/api/v1/admin/alerts/topics", "/api/v1/admin/alerts/config",
                                    "/api/v1/alerts/topics", "/api/v1/alerts/history"))
                                {
                                    var response = f.send("GET", path, null, null, false);
                                    assertEquals(200, response.statusCode(), response.body());
                                    assertFalse(response.body().contains("Changed"));
                                }
                        }
                        catch(Exception exception) { throw new RuntimeException(exception); }
                    }).get(3, TimeUnit.SECONDS);
                }
                finally { sql.execute("ROLLBACK"); }
            }
            finally { if(writer != null) writer.join(2000); }
            assertNull(failure.get());
            assertTrue(f.send("GET", "/api/v1/admin/alerts/topics", null, null, false).body().contains("Changed"));
        }
    }

    @Test void relatedCallsUseCurrentSessionCapabilitiesWithoutGivingNotificationTokensAudioAccess() throws Exception
    {
        try(Fixture f = new Fixture(mDirectory.resolve("related-calls.sqlite")))
        {
            var topic = new AlertTopic("fire", "Fire", "", true, true, false);
            f.service.saveTopic(topic, f.service.configuration().revision());
            var store = new io.github.dsheirer.alerts.TopicAlertStore(f.database);
            String source = "00000000-0000-4000-8000-000000000001";
            store.publish(topic, "rule", "Rule", new io.github.dsheirer.alerts.AlertEvent("call", 1000, "call", Map.of(),
                io.github.dsheirer.alerts.AlertAction.Context.EMPTY, source), "Call", "Body", "details", List.of(), System.currentTimeMillis());
            @SuppressWarnings("unchecked") var items = (List<Map<String,Object>>)f.service.history(null, 100, 0).get("items");
            String detail = "/api/v1/alerts/history/" + items.getFirst().get("id");
            assertFalse(f.send("GET", detail, null, null, false).body().contains(source));
            assertFalse(f.send("GET", "/api/v1/alerts/history", null, null, false).body().contains("source_call_uuid"));
            assertEquals(401, f.send("GET", detail + "/related", null, "a".repeat(43), false).statusCode());
            assertTrue(f.resolvedSources.isEmpty(), "A subscription token never substitutes for receiver page access");

            f.access.setCapabilityTier("web-access", io.github.dsheirer.web.auth.AccessTier.PUBLIC);
            f.access.setCapabilityTier("radio", io.github.dsheirer.web.auth.AccessTier.ADMIN);
            f.access.setCapabilityTier("recordings", io.github.dsheirer.web.auth.AccessTier.PUBLIC);
            var recordingsOnly = f.body(f.send("GET", detail + "/related", null, null, false));
            assertEquals(0, recordingsOnly.path("activity_rows").size());
            assertEquals(1, recordingsOnly.path("recordings").size());
            assertEquals(List.of(source), f.resolvedSources);
            assertFalse(recordingsOnly.toString().contains(source));

            f.access.setCapabilityTier("radio", io.github.dsheirer.web.auth.AccessTier.PUBLIC);
            f.access.setCapabilityTier("recordings", io.github.dsheirer.web.auth.AccessTier.ADMIN);
            var activityOnly = f.body(f.send("GET", detail + "/related", null, null, false));
            assertEquals(1, activityOnly.path("activity_rows").size());
            assertEquals(0, activityOnly.path("recordings").size());
            f.login();
            var signedIn = f.body(f.send("GET", detail + "/related", null, null, false));
            assertEquals(1, signedIn.path("activity_rows").size());
            assertEquals(1, signedIn.path("recordings").size());
            assertEquals(400, f.send("GET", detail + "/related?source_call_uuid=" + source, null, null, false).statusCode());
            assertEquals(404, f.send("GET", "/api/v1/alerts/history/missing/related", null, null, false).statusCode());
            f.service.saveTopic(new AlertTopic("fire", "Fire", "", false, true, false), f.service.configuration().revision());
            assertEquals(404, f.send("GET", detail + "/related", null, null, false).statusCode());
        }
    }

    @Test void ruleTestsRequireAnAdministratorPostAndQueueTheSavedTemplatesForSubscribers() throws Exception
    {
        try(Fixture f = new Fixture(mDirectory.resolve("rule-tests.sqlite")))
        {
            String path = "/api/v1/admin/alerts/rules/fire%20rule%2Ftest/test";
            f.service.saveTopic(new AlertTopic("fire", "Fire", "", true, true, false), f.service.configuration().revision());
            f.service.saveRule(new AlertRule("fire rule/test", "Fire activity", "fire", false, "call", "", "", "", "", "",
                AlertRule.Condition.all(), AlertRule.Timing.immediate(), AlertRule.Schedule.always(), 60, "rule", false,
                "Fire at {vce}", "Activity detected", "details"), f.service.configuration().revision());
            f.service.saveDevice(new AlertDevice("subscriber", "Phone", "direct", "", "https://push.example/device",
                "key", "auth", "a".repeat(64), List.of("fire"), false));
            Map<String,Object> request = Map.of("revision", f.service.configuration().revision());
            assertEquals(401, f.send("POST", path, request, null, true).statusCode());
            f.login();
            var unready = f.send("POST", path, request, null, true);
            assertEquals(422, unready.statusCode());
            assertEquals("Finish push setup and enable notifications before sending a test alert.",
                f.body(unready).at("/error/message").asText());
            var current = f.service.configuration();
            f.service.updateConfiguration(new TopicAlertConfiguration(current.revision(),
                new TopicAlertConfiguration.PushSettings(true, "direct", "https://vce.example", "East"),
                current.topics(), current.rules()), current.revision());
            long revision = f.service.configuration().revision();
            request = Map.of("revision", revision);

            var noCsrf = HttpRequest.newBuilder(f.origin.resolve(path)).header("Cookie", f.cookie)
                .header("Origin", f.origin.toString()).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request))).build();
            assertEquals(403, f.client.send(noCsrf, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(403, f.send("POST", path, request, null, false).statusCode());
            var get = f.send("GET", path, null, null, false);
            assertEquals(405, get.statusCode());
            assertEquals("POST", get.headers().firstValue("Allow").orElseThrow());
            assertEquals(400, f.send("POST", path + "?test=true", request, null, true).statusCode());
            assertEquals(400, f.send("POST", path, Map.of("revision", revision, "title", "Override"), null, true).statusCode());
            assertEquals(409, f.send("POST", path, Map.of("revision", revision - 1), null, true).statusCode());
            assertEquals(422, f.send("POST", "/api/v1/admin/alerts/rules/missing/test", request, null, true).statusCode());
            assertEquals(0, f.service.history(null, 100, 0).get("total"));

            var sent = f.send("POST", path, request, null, true);
            assertEquals(200, sent.statusCode(), sent.body());
            assertTrue(f.body(sent).path("queued").asBoolean());
            var deliveries = f.service.pendingDeliveries(100);
            assertEquals(1, deliveries.size());
            assertEquals("subscriber", deliveries.getFirst().deviceId());
            assertEquals("TEST: Fire at East", deliveries.getFirst().title());
            assertEquals("TEST: Activity detected", deliveries.getFirst().body());
            assertEquals("Fire activity", deliveries.getFirst().alertName());
            assertEquals(revision, f.service.configuration().revision());
            assertFalse(f.service.rules().getFirst().enabled(), "Testing must preserve the paused rule");
            assertEquals(200, f.send("POST", path, request, null, true).statusCode());
            assertEquals(2, f.service.pendingDeliveries(100).size(), "Each confirmed test is a new alert");
            assertEquals(200, f.send("DELETE", "/api/v1/admin/alerts/rules/fire%20rule%2Ftest", request, null, true).statusCode(),
                "A rule ID ending in /test must retain its delete action");
            assertTrue(f.service.rules().isEmpty());
        }
    }

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

    @Test void topicResponsesDistinguishDisabledNotificationsFromEnabledDeliveryAwaitingApproval() throws Exception
    {
        try(Fixture f = new Fixture(mDirectory.resolve("enabled-metadata.sqlite")))
        {
            var publicTopics = f.body(f.send("GET", "/api/v1/alerts/topics", null, null, false));
            assertTrue(publicTopics.path("enabled").isBoolean());
            assertFalse(publicTopics.path("enabled").asBoolean());
            assertFalse(publicTopics.path("push_enabled").asBoolean());
            f.login();
            var catalog = f.body(f.send("GET", "/api/v1/admin/alerts/topics", null, null, false));
            assertTrue(catalog.path("enabled").isBoolean());
            assertFalse(catalog.path("enabled").asBoolean());

            var enabled = f.send("PUT", "/api/v1/admin/alerts/config", Map.of("revision", f.service.configuration().revision(),
                "enabled", true, "mode", "proxy", "sender_label", "County alerts"), null, true);
            assertEquals(200, enabled.statusCode(), enabled.body());
            assertEquals("unregistered", f.body(enabled).at("/registration/status").asText());
            publicTopics = f.body(f.send("GET", "/api/v1/alerts/topics", null, null, false));
            assertTrue(publicTopics.path("enabled").asBoolean(), "The master switch is independent of approval");
            assertFalse(publicTopics.path("push_enabled").asBoolean(), "Unapproved Cloud delivery is still unavailable");
            assertTrue(f.body(f.send("GET", "/api/v1/admin/alerts/topics", null, null, false)).path("enabled").asBoolean());
        }
    }

    @Test void directDeliveryCanBeDisabledWithoutFreshProofButCannotBeReenabledWithoutIt() throws Exception
    {
        try(Fixture f = new Fixture(mDirectory.resolve("direct-disable.sqlite")))
        {
            var current = f.service.configuration();
            var settings = new TopicAlertConfiguration.PushSettings(true, "direct", "https://vce.example:8443", "County alerts");
            f.service.updateConfiguration(new TopicAlertConfiguration(current.revision(), settings, current.topics(), current.rules()),
                current.revision());
            f.login();
            long revision = f.service.configuration().revision();
            var disable = Map.of("revision", revision, "enabled", false, "mode", "direct", "site_url", settings.siteUrl(),
                "sender_label", settings.senderLabel());
            var disabled = f.send("PUT", "/api/v1/admin/alerts/config", disable, null, true);
            assertEquals(200, disabled.statusCode(), disabled.body());
            assertEquals(new TopicAlertConfiguration.PushSettings(false, settings.mode(), settings.siteUrl(), settings.senderLabel()),
                f.service.configuration().push(), "Disabling preserves the configured delivery details");
            assertFalse(f.body(disabled).path("enabled").asBoolean());
            assertEquals(revision + 1, f.body(disabled).path("revision").asLong());
            assertEquals(409, f.send("PUT", "/api/v1/admin/alerts/config", disable, null, true).statusCode(),
                "Disabled saves retain revision protection");

            var reenable = f.send("PUT", "/api/v1/admin/alerts/config", Map.of("revision", f.service.configuration().revision(),
                "enabled", true, "mode", "direct", "site_url", settings.siteUrl(), "sender_label", settings.senderLabel()), null, true);
            assertEquals(422, reenable.statusCode());
            assertEquals("public_test_required", f.body(reenable).at("/error/code").asText());
            assertFalse(f.service.configuration().push().enabled());
            assertEquals(revision + 1, f.service.configuration().revision());
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
            Map<String,Object> retiredPreferences = Map.of("paused", true, "quiet_enabled", true,
                "quiet_start", "00:00", "quiet_end", "00:00", "time_zone", "retired-invalid-zone");
            Map<String,Object> request = Map.of("topic_ids", java.util.List.of("fire"), "prefs", retiredPreferences,
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
            var updated = f.send("PUT", "/api/v1/alerts/subscriptions/me", Map.of("topic_ids", List.of("fire"),
                "prefs", retiredPreferences), token, true);
            assertEquals(200, updated.statusCode(), updated.body());
            f.service.saveRule(new AlertRule("dispatch", "Dispatch activity", "fire", true, "call", "", "", "", "", null, null, null,
                0, "rule", false, "Dispatch", "Activity", "details"), f.service.configuration().revision());
            f.service.testRule("dispatch", f.service.configuration().revision());
            assertEquals(1, f.service.pendingDeliveries(10).size(), "Retired personal preferences cannot suppress delivery");
            assertEquals(f.body(enrolled).path("device_id").asText(), f.service.pendingDeliveries(10).getFirst().deviceId());
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

    @Test void malformedCloudPairingResponseLogsItsOperationAndParserTypeWithoutSubscriptionSecrets() throws Exception
    {
        String secretEndpoint = "https://private.example/push?token=synthetic-endpoint-token";
        var credential = new java.util.concurrent.atomic.AtomicReference<String>();
        HttpServer gateway = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        gateway.createContext("/senders", exchange -> {
            credential.set(JSON.readTree(exchange.getRequestBody()).path("credential").asText());
            byte[] body = JSON.writeValueAsBytes(Map.of("sender_id", "synthetic-sender", "status", "approved"));
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        gateway.createContext("/enrollments", exchange -> {
            byte[] body = ("invalid response " + secretEndpoint + " " + credential.get()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        gateway.start();
        Logger logger = (Logger)LoggerFactory.getLogger(TopicAlertsHttpController.class);
        ListAppender<ILoggingEvent> messages = new ListAppender<>(); messages.start(); logger.addAppender(messages);
        try(Fixture f = new Fixture(mDirectory.resolve("pairing-failure-diagnostics.sqlite"),
            URI.create("http://127.0.0.1:" + gateway.getAddress().getPort() + "/")))
        {
            f.service.saveTopic(new io.github.dsheirer.alerts.AlertTopic("fire", "Fire", "", true, true, false), f.service.configuration().revision());
            var current = f.service.configuration();
            f.service.updateConfiguration(new TopicAlertConfiguration(current.revision(),
                new TopicAlertConfiguration.PushSettings(true, "proxy", "", "VCE"), current.topics(), current.rules()), current.revision());
            f.push.register();
            var failed = f.send("POST", "/api/v1/alerts/enrollments", Map.of("topic_ids", java.util.List.of("fire")), null, true);
            assertEquals(503, failed.statusCode());
            assertEquals("Notifications are temporarily unavailable. Try again later.", f.body(failed).at("/error/message").asText());
            ILoggingEvent event = messages.list.getLast();
            assertTrue(event.getFormattedMessage().contains("operation=PUBLIC_PAIRING"));
            assertTrue(event.getFormattedMessage().contains("exception=IOException, cause=JsonParseException"));
            assertFalse(event.getFormattedMessage().contains(secretEndpoint));
            assertFalse(event.getFormattedMessage().contains(credential.get()));
            assertNull(event.getThrowableProxy(), "No exception message or stack trace may be attached to the log event");
        }
        finally { logger.detachAppender(messages); messages.stop(); gateway.stop(0); }
    }

    @Test void diagnosticsKeepDatabaseAndNetworkFailureTypesButNeverDynamicPathsOrMessages() throws Exception
    {
        String secret = "https://private.example/push?code=synthetic-secret";
        Logger logger = (Logger)LoggerFactory.getLogger(TopicAlertsHttpController.class);
        ListAppender<ILoggingEvent> messages = new ListAppender<>(); messages.start(); logger.addAppender(messages);
        try(Fixture f = new Fixture(mDirectory.resolve("database-failure-diagnostics.sqlite")))
        {
            f.login();
            f.optionsFailure = new RuntimeException(secret, new SQLException(secret, "HY000", 5));
            assertEquals(503, f.send("GET", "/api/v1/admin/alerts/topics", null, null, false).statusCode());
            assertTrue(messages.list.getLast().getFormattedMessage().contains("operation=ADMIN_TOPICS, exception=RuntimeException, cause=SQLException, sql_state=HY000, sql_code=5"));
            var operation = TopicAlertsHttpController.failureOperation(false, "GET", "/enrollments/" + secret);
            TopicAlertsHttpController.logFailure(operation, new IOException(secret, new java.net.http.HttpTimeoutException(secret)));
            assertTrue(messages.list.getLast().getFormattedMessage().contains("operation=PUBLIC_PAIRING_STATUS, exception=IOException, cause=HttpTimeoutException"));
            TopicAlertsHttpController.logFailure(operation, new SQLException(secret, secret, 19));
            assertTrue(messages.list.getLast().getFormattedMessage().contains("sql_state=none, sql_code=19"));
            for(var event: messages.list)
            {
                assertFalse(event.getFormattedMessage().contains(secret));
                assertNull(event.getThrowableProxy());
            }
        }
        finally { logger.detachAppender(messages); messages.stop(); }
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
        final WebRequestExecutor requestExecutor = new WebRequestExecutor(32, () -> { });
        final HttpClient client = HttpClient.newHttpClient(); final WebRequestSecurity security;
        final List<String> resolvedSources = new java.util.ArrayList<>();
        boolean optionsUnavailable;
        RuntimeException optionsFailure;
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
            var controller = new TopicAlertsHttpController(service, push, () -> {
                if(optionsFailure != null) throw optionsFailure;
                if(optionsUnavailable) throw new IllegalStateException("Alert source choices are temporarily unavailable");
                return Map.of();
            }, () -> { }, access, security, (source, activityAllowed, recordingsAllowed) -> {
                resolvedSources.add(source);
                return Map.of("activity_rows", activityAllowed ? List.of(Map.of("id", 12)) : List.of(),
                    "recordings", recordingsAllowed ? List.of(Map.of("id", 34)) : List.of());
            });
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(requestExecutor);
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
        public void close() { server.stop(0); requestExecutor.shutdownNow(); security.close(); push.close(); service.close(); }
    }
}
