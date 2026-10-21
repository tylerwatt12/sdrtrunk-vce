package io.github.dsheirer.alerts.delivery;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.dsheirer.alerts.*;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PushDeliveryServiceTest
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String REMOTE_A = "a".repeat(32), REMOTE_B = "b".repeat(32);
    @TempDir Path directory;

    @Test void oneEventQueuesOneImmutableBatchAndUnsubscribeSuppressesOnlyThatPhone() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("batch.sqlite")))
        {
            AlertDelivery first = f.publish();
            f.push.deliverRelayBatch(first);
            assertEquals(1, f.requests.size());
            JsonNode original = f.requests.getFirst();
            assertEquals(first.alertId(), original.path("client_alert_id").asText());
            assertEquals("Dispatch activity", original.path("alert_name").asText());
            assertEquals(List.of(REMOTE_A, REMOTE_B), JSON.convertValue(original.path("device_ids"), List.class));
            assertEquals(java.util.Set.of("client_alert_id", "alert_name", "topic_id", "topic_name", "title", "body",
                "device_ids", "expires_at_ms", "suppressed_device_ids"),
                JSON.convertValue(original, Map.class).keySet());
            assertFalse(original.toString().contains("00000000-0000-4000-8000-000000000001"));
            assertTrue(f.service.deliveriesForAlert(first.alertId()).stream().allMatch(d -> d.state().equals("queued")));
            f.push.updateDevice(f.tokenB, List.of());
            f.push.reconcileQueuedRelay();
            JsonNode changed = f.requests.getLast();
            assertEquals(original.path("device_ids"), changed.path("device_ids"));
            assertEquals(List.of(REMOTE_B), JSON.convertValue(changed.path("suppressed_device_ids"), List.class));
            assertEquals("queued", f.service.deliveryForRemote(first.alertId(), REMOTE_A).orElseThrow().state());
            assertEquals("cancelled", f.service.deliveryForRemote(first.alertId(), REMOTE_B).orElseThrow().state());
            f.updates = JSON.valueToTree(Map.of("cursor", "2", "updates", List.of(
                Map.of("type", "alert_delivery", "client_alert_id", first.alertId(), "device_id", REMOTE_A, "status", "push_accepted", "alert_id", "remote-alert"),
                Map.of("type", "alert_delivery", "client_alert_id", first.alertId(), "device_id", REMOTE_B, "status", "expired", "alert_id", "remote-alert"))));
            f.push.relayTick();
            assertEquals("push_accepted", f.service.deliveryForRemote(first.alertId(), REMOTE_A).orElseThrow().state());
            assertNotEquals("push_accepted", f.service.deliveryForRemote(first.alertId(), REMOTE_B).orElseThrow().state());
        }
    }

    @Test void unknownPostOutcomeAndHiddenTopicReplayStableIdsWithSuppression() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("unknown.sqlite")))
        {
            AlertDelivery first = f.publish(); f.alertStatus.set(500);
            f.push.deliverRelayBatch(first);
            assertEquals("retry", f.service.deliveryForRemote(first.alertId(), REMOTE_A).orElseThrow().state());
            f.service.saveTopic(new AlertTopic("fire", "Fire activity", "", false, true, false), f.service.configuration().revision());
            f.alertStatus.set(202); f.push.reconcileQueuedRelay();
            assertEquals(2, f.requests.size());
            assertEquals(f.requests.getFirst().path("device_ids"), f.requests.getLast().path("device_ids"));
            assertEquals(List.of(REMOTE_A, REMOTE_B), JSON.convertValue(f.requests.getLast().path("suppressed_device_ids"), List.class));
            assertTrue(f.service.deliveriesForAlert(first.alertId()).stream().allMatch(d -> d.state().equals("cancelled")));
        }
    }

    @Test void directOutboxTickDoesNotContactPreviouslyRegisteredGateway() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("direct.sqlite")))
        {
            var current = f.service.configuration();
            f.service.updateConfiguration(new TopicAlertConfiguration(current.revision(),
                new TopicAlertConfiguration.PushSettings(true, "direct", "https://vce.example", "VCE"), current.topics(), current.rules()), current.revision());
            f.alertStatus.set(503); f.push.tick();
            assertEquals(0, f.httpCalls.get(), "Direct sending must not await RadioResolve status requests");
        }
    }

    @Test void failedSuppressionKeepsUnknownRemoteOutcomeEligibleForReconciliation() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("suppression-retry.sqlite")))
        {
            AlertDelivery first = f.publish(); f.alertStatus.set(500);
            f.push.deliverRelayBatch(first);
            f.service.saveTopic(new AlertTopic("fire", "Fire activity", "", false, true, false), f.service.configuration().revision());
            f.push.deliverRelayBatch(f.service.delivery(first.id()).orElseThrow());
            assertTrue(f.service.deliveriesForAlert(first.alertId()).stream().allMatch(d -> d.state().equals("retry")),
                "Failed suppression must remain durable because the previous request may already be queued remotely");
            f.alertStatus.set(202); f.push.reconcileQueuedRelay();
            assertEquals(3, f.requests.size());
            assertEquals(f.requests.getFirst().path("device_ids"), f.requests.getLast().path("device_ids"));
            assertTrue(f.service.deliveriesForAlert(first.alertId()).stream().allMatch(d -> d.state().equals("cancelled")));
        }
    }

    @Test void relayExpiryAndRenewalKeepGuestTopics() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("renew-relay.sqlite")))
        {
            f.push.updateDevice(f.tokenA, List.of("fire"));
            f.updates = JSON.valueToTree(Map.of("cursor", "1", "updates", List.of(
                Map.of("type", "device_expired", "device_id", REMOTE_A))));
            f.push.relayTick();
            assertTrue(f.push.managedDevice(f.tokenA).subscriptionExpired());
            f.push.updateDevice(f.tokenA, List.of("fire"));
            assertTrue(f.push.managedDevice(f.tokenA).subscriptionExpired(), "Editing topics must not reactivate an expired endpoint");
            f.updates = JSON.valueToTree(Map.of("cursor", "2", "updates", List.of(
                Map.of("type", "device_refreshed", "device_id", REMOTE_A))));
            f.push.relayTick();
            AlertDevice renewed = f.push.managedDevice(f.tokenA);
            assertFalse(renewed.subscriptionExpired());
            assertEquals(List.of("fire"), renewed.topicIds());
            assertFalse(JSON.valueToTree(renewed).has("paused"));
            assertFalse(JSON.valueToTree(renewed).has("quiet_start"));
            assertFalse(JSON.valueToTree(renewed).has("time_zone"));
        }
    }

    @Test void pairingSameRemotePhoneReusesOneLocalMembershipAndReplacesOldManagementToken() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("pair-again.sqlite")))
        {
            String newToken = PublicAccessVerifier.token(); long now = System.currentTimeMillis();
            f.service.saveEnrollment(new AlertEnrollment("second-pairing", "c".repeat(32), PushDeliveryService.hashToken(newToken),
                List.of("fire"), now, now + 900_000, "pending", "", "", ""));
            f.updates = JSON.valueToTree(Map.of("status", "completed", "device_id", REMOTE_A));
            f.push.enrollment("second-pairing", newToken);
            assertEquals(2, f.service.devices().size());
            assertEquals("phone-a", f.push.managedDevice(newToken).id());
            assertEquals(List.of("fire"), f.push.managedDevice(newToken).topicIds());
            assertThrows(SecurityException.class, () -> f.push.managedDevice(f.tokenA));
        }
    }

    @Test void directRefreshMustChangeExpiredMaterialAndRetainsDeviceIdentity() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("renew-direct.sqlite")))
        {
            var keys = WebPushCodec.generateKeys();
            String auth = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
            String endpoint = "https://fcm.googleapis.com/send/expired";
            f.service.unsubscribe("phone-a");
            f.service.saveDevice(new AlertDevice("direct-phone", "Phone", "direct", "", endpoint, keys.publicKey(), auth,
                PushDeliveryService.hashToken(f.tokenA), List.of("fire"), true));
            f.push.refreshSubscription(f.tokenA, JSON.valueToTree(Map.of("endpoint", endpoint,
                "keys", Map.of("p256dh", keys.publicKey(), "auth", auth))));
            assertTrue(f.push.managedDevice(f.tokenA).subscriptionExpired());
            f.push.refreshSubscription(f.tokenA, JSON.valueToTree(Map.of("endpoint", "https://fcm.googleapis.com/send/renewed",
                "keys", Map.of("p256dh", keys.publicKey(), "auth", auth))));
            AlertDevice renewed = f.push.managedDevice(f.tokenA);
            assertFalse(renewed.subscriptionExpired());
            assertEquals("direct-phone", renewed.id());
            assertEquals(List.of("fire"), renewed.topicIds());
        }
    }

    @Test void unsubscribedCloudPhoneCanStartANewPairingWithoutRevivingItsOldToken() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("cloud-unsubscribe-pair-again.sqlite")))
        {
            long now = System.currentTimeMillis();
            f.service.saveEnrollment(new AlertEnrollment("old-pairing", "e".repeat(32), PushDeliveryService.hashToken(f.tokenA),
                List.of("fire"), now, now + 900_000, "completed", "phone-a", "https://radioresolve.com/vce-push/enroll.php", "old-code"));
            f.push.disconnect(f.tokenA);
            assertEquals("cancelled", f.service.enrollment("old-pairing").orElseThrow().status());
            assertThrows(SecurityException.class, () -> f.push.managedDevice(f.tokenA));

            var pairing = f.push.enroll(List.of("fire"), JSON.createObjectNode());
            assertEquals("pending", pairing.get("status"));
            assertEquals(List.of("fire"), f.service.enrollment((String)pairing.get("id")).orElseThrow().topicIds());
            f.updates = JSON.valueToTree(Map.of("status", "completed", "device_id", REMOTE_A));
            var completed = f.push.enrollment((String)pairing.get("id"), (String)pairing.get("manage_token"));
            assertEquals("completed", completed.get("status"));
            assertEquals(2, f.service.devices().size());
            assertEquals(List.of("fire"), f.push.managedDevice((String)pairing.get("manage_token")).topicIds());
            assertThrows(SecurityException.class, () -> f.push.managedDevice(f.tokenA));
        }
    }

    @Test void directEnrollmentReusesExactBrowserMaterialWithoutRestoringExpiry() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("direct-pair-again.sqlite")))
        {
            var current = f.service.configuration();
            f.service.updateConfiguration(new TopicAlertConfiguration(current.revision(),
                new TopicAlertConfiguration.PushSettings(true, "direct", "https://vce.example", "VCE"), current.topics(), current.rules()), current.revision());
            var keys = WebPushCodec.generateKeys();
            String auth = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[16]);
            JsonNode subscription = JSON.valueToTree(Map.of("endpoint", "https://fcm.googleapis.com/send/this-browser",
                "keys", Map.of("p256dh", keys.publicKey(), "auth", auth)));
            var first = f.push.enroll(List.of("fire"), subscription);
            f.service.markDeviceExpired((String)first.get("device_id"), true);
            var second = f.push.enroll(List.of("fire"), subscription);
            assertEquals(first.get("device_id"), second.get("device_id"));
            assertEquals(3, f.service.devices().size());
            assertEquals("reconnect_required", second.get("status"));
            assertTrue(f.push.managedDevice((String)second.get("manage_token")).subscriptionExpired());
            assertThrows(SecurityException.class, () -> f.push.managedDevice((String)first.get("manage_token")));
            JsonNode collision = JSON.valueToTree(Map.of("endpoint", subscription.path("endpoint").asText(),
                "keys", Map.of("p256dh", WebPushCodec.generateKeys().publicKey(), "auth", auth)));
            assertThrows(IllegalArgumentException.class, () -> f.push.enroll(List.of("fire"), collision));
        }
    }

    @Test void deregistrationCancelsOutstandingPhonePairingBeforeSenderCanBeReplaced() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("deregister-pending.sqlite")))
        {
            String token = PublicAccessVerifier.token(); long now = System.currentTimeMillis();
            f.service.saveEnrollment(new AlertEnrollment("pending-phone", "d".repeat(32), PushDeliveryService.hashToken(token),
                List.of("fire"), now, now + 900_000, "pending", "", "", ""));
            f.push.deregister();
            assertEquals("cancelled", f.push.enrollment("pending-phone", token).get("status"));
            assertTrue(f.service.devices().isEmpty());
            int previous = f.httpCalls.get(); f.push.relayTick();
            assertEquals(previous, f.httpCalls.get());
        }
    }

    @Test void registrationSavesTheRequestedNameWithoutUnnecessaryRevisionChanges() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("register-name.sqlite")))
        {
            long revision = f.service.configuration().revision();
            f.push.register("New installation name");
            assertEquals("New installation name", f.service.configuration().push().senderLabel());
            assertEquals("New installation name", f.registrationRequest.path("sender_name").asText());
            assertEquals(revision + 1, f.service.configuration().revision());
            f.push.register("New installation name");
            assertEquals(revision + 1, f.service.configuration().revision());
            assertThrows(IllegalArgumentException.class, () -> f.push.register(""));
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final TopicAlertService service; final PushDeliveryService push; final HttpServer server;
        final List<JsonNode> requests = new ArrayList<>(); final AtomicInteger alertStatus = new AtomicInteger(202), httpCalls = new AtomicInteger();
        final String tokenA = PublicAccessVerifier.token(), tokenB = PublicAccessVerifier.token();
        JsonNode registrationRequest;
        JsonNode updates = JSON.valueToTree(Map.of("cursor", "0", "updates", List.of()));
        Fixture(Path database) throws Exception
        {
            SdrTrunkDatabaseStartup.createGlobalDatabase(database); service = new TopicAlertService(database);
            service.saveTopic(new AlertTopic("fire", "Fire activity", "", true, true, false), service.configuration().revision());
            var rule = new AlertRule("dispatch", "Dispatch activity", "fire", true, "call", "", "", "", "", null, null, null,
                0, "rule", false, "Radio {radio}", "Transmission on {talkgroup}", "details");
            service.saveRule(rule, service.configuration().revision());
            var current = service.configuration(); service.updateConfiguration(new TopicAlertConfiguration(current.revision(),
                new TopicAlertConfiguration.PushSettings(true, "proxy", "", "VCE"), current.topics(), current.rules()), current.revision());
            service.saveDevice(new AlertDevice("phone-a", "Phone A", "proxy", REMOTE_A, "", "", "", PushDeliveryService.hashToken(tokenA), List.of("fire"), false));
            service.saveDevice(new AlertDevice("phone-b", "Phone B", "proxy", REMOTE_B, "", "", "", PushDeliveryService.hashToken(tokenB), List.of("fire"), false));
            var credentials = new PushCredentialStore(database); credentials.prepareRegistration(); credentials.registration("sender-fixture", "approved");
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                httpCalls.incrementAndGet(); Object response; int status = 200;
                if(exchange.getRequestURI().getPath().equals("/alerts"))
                {
                    requests.add(JSON.readTree(exchange.getRequestBody())); status = alertStatus.get();
                    response = Map.of("alert_id", "remote-alert", "status", "queued");
                }
                else if(exchange.getRequestURI().getPath().equals("/senders")) { registrationRequest = JSON.readTree(exchange.getRequestBody()); response = Map.of("sender_id", "sender-fixture", "status", "approved"); }
                else if(exchange.getRequestURI().getPath().equals("/senders/me")) response = Map.of("sender_id", "sender-fixture", "status", "approved");
                else if(exchange.getRequestURI().getPath().equals("/enrollments")) response = Map.of(
                    "id", "f".repeat(32), "expires_at_ms", System.currentTimeMillis() + 900_000,
                    "enrollment_url", "https://radioresolve.com/vce-push/enroll.php?code=synthetic-session.synthetic-code",
                    "code", "synthetic-code");
                else response = updates;
                byte[] body = JSON.writeValueAsBytes(response); exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body); exchange.close();
            }); server.start();
            var gateway = new RadioResolvePushClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"),
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build());
            push = new PushDeliveryService(database, service, gateway);
        }
        AlertDelivery publish() throws Exception
        {
            service.observe(new AlertEvent("call-fixture", System.currentTimeMillis(), "call", Map.of("radio", List.of("100"), "talkgroup", List.of("200")),
                AlertAction.Context.EMPTY, "00000000-0000-4000-8000-000000000001"));
            service.receiveRecordingReady(List.of("00000000-0000-4000-8000-000000000001"));
            long until = System.nanoTime() + 2_000_000_000L;
            while(service.pendingDeliveries(10).isEmpty() && System.nanoTime() < until) Thread.sleep(10);
            assertEquals(2, service.pendingDeliveries(10).size()); return service.pendingDeliveries(10).getFirst();
        }
        public void close() { push.close(); service.close(); server.stop(0); }
    }
}
