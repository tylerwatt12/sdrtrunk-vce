package io.github.dsheirer.alerts.delivery;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;
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

    @Test void slowEnrollmentDoesNotBlockLocalChangesAndCannotOutliveDeregistration() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("slow-enroll.sqlite"));
            ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor())
        {
            String key = f.push.publicKey(); f.hold("POST", "/enrollments");
            var enrollment = requests.submit(() -> f.push.enroll(List.of("fire"), JSON.createObjectNode()));
            assertTrue(f.entered.await(2, TimeUnit.SECONDS));
            try
            {
                requests.submit(() -> {
                    f.push.updateDevice(f.tokenB, List.of());
                    assertEquals(key, f.push.publicKey());
                    assertEquals(2, f.service.devices().size());
                    return null;
                }).get(1, TimeUnit.SECONDS);
                requests.submit(() -> { f.push.deregister(); return null; }).get(2, TimeUnit.SECONDS);
            }
            finally { f.release.countDown(); }
            assertInstanceOf(IllegalStateException.class,
                assertThrows(ExecutionException.class, () -> enrollment.get(2, TimeUnit.SECONDS)).getCause());
            assertTrue(f.service.pendingEnrollments(100).isEmpty());
            assertTrue(f.service.devices().isEmpty());
            assertEquals("deregistered", f.push.registration().get("status"));
        }
    }

    @Test void slowDisconnectCannotRevokeAReconnectedDeviceOrBlockOtherDeviceSettings() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("slow-disconnect.sqlite"));
            ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor())
        {
            String key = f.push.publicKey(); f.hold("DELETE", "/devices/" + REMOTE_A);
            var disconnect = requests.submit(() -> { f.push.disconnect(f.tokenA); return null; });
            assertTrue(f.entered.await(2, TimeUnit.SECONDS));
            String replacementToken = PublicAccessVerifier.token();
            try
            {
                requests.submit(() -> {
                    f.push.updateDevice(f.tokenB, List.of());
                    assertEquals(key, f.push.publicKey());
                    var old = f.push.managedDevice(f.tokenA);
                    f.service.saveDevice(new AlertDevice(old.id(), old.name(), old.method(), old.remoteDeviceId(),
                        old.endpoint(), old.p256dh(), old.auth(), PushDeliveryService.hashToken(replacementToken), old.topicIds(), false));
                    return null;
                }).get(1, TimeUnit.SECONDS);
            }
            finally { f.release.countDown(); }
            disconnect.get(2, TimeUnit.SECONDS);
            assertEquals("phone-a", f.push.managedDevice(replacementToken).id());
            assertThrows(SecurityException.class, () -> f.push.managedDevice(f.tokenA));
            assertEquals(List.of(), f.push.managedDevice(f.tokenB).topicIds());
        }
    }

    @Test void successfulDeviceDisconnectSurvivesAConcurrentFailedSenderDisconnect() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("disconnect-after-sender-failure.sqlite"));
            ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor())
        {
            f.hold("DELETE", "/devices/" + REMOTE_A);
            var disconnect = requests.submit(() -> { f.push.disconnect(f.tokenA); return null; });
            assertTrue(f.entered.await(2, TimeUnit.SECONDS));
            try
            {
                f.registrationStatusCode.set(503);
                assertThrows(RadioResolvePushClient.GatewayException.class, f.push::deregister);
                assertEquals("approved", f.push.registration().get("status"));
                assertEquals("phone-a", f.push.managedDevice(f.tokenA).id());
            }
            finally { f.release.countDown(); }
            disconnect.get(2, TimeUnit.SECONDS);
            assertThrows(SecurityException.class, () -> f.push.managedDevice(f.tokenA));
            assertEquals("phone-b", f.push.managedDevice(f.tokenB).id());
            assertEquals("approved", f.push.registration().get("status"));
        }
    }

    @Test void slowDeregistrationLeavesReadsAndLocalDeviceSettingsAvailable() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("slow-deregister.sqlite"));
            ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor())
        {
            String key = f.push.publicKey(); f.hold("DELETE", "/senders/me");
            var disconnect = requests.submit(() -> { f.push.deregister(); return null; });
            assertTrue(f.entered.await(2, TimeUnit.SECONDS));
            try
            {
                requests.submit(() -> {
                    assertEquals(key, f.push.publicKey());
                    assertEquals("approved", f.push.registration().get("status"));
                    f.push.updateDevice(f.tokenB, List.of());
                    assertFalse(f.push.ready());
                    assertThrows(IllegalStateException.class, f.push::register);
                    return null;
                }).get(1, TimeUnit.SECONDS);
            }
            finally { f.release.countDown(); }
            disconnect.get(2, TimeUnit.SECONDS);
            assertEquals("deregistered", f.push.registration().get("status"));
            assertTrue(f.service.devices().isEmpty());
        }
    }

    @Test void lateStatusResponseCannotRestoreDisconnectedCredentialsAndFailureReleasesAdmission() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("late-status.sqlite"));
            ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor())
        {
            f.hold("GET", "/senders/me");
            var status = requests.submit(f.push::refreshRegistration);
            assertTrue(f.entered.await(2, TimeUnit.SECONDS));
            try { requests.submit(() -> { f.push.deregister(); return null; }).get(2, TimeUnit.SECONDS); }
            finally { f.release.countDown(); }
            status.get(2, TimeUnit.SECONDS);
            assertEquals("deregistered", f.push.registration().get("status"));
            f.push.register();
            f.registrationStatusCode.set(503);
            assertThrows(RadioResolvePushClient.GatewayException.class, f.push::deregister);
            assertTrue(f.push.ready());
            f.registrationStatusCode.set(200);
            f.push.deregister();
            assertEquals("deregistered", f.push.registration().get("status"));
        }
    }

    @Test void lateUpdatePollCannotRestorePairingOrAdvanceDisconnectedCredentials() throws Exception
    {
        try(Fixture f = new Fixture(directory.resolve("late-updates.sqlite"));
            ExecutorService requests = Executors.newVirtualThreadPerTaskExecutor())
        {
            String token = PublicAccessVerifier.token(); long now = System.currentTimeMillis();
            f.service.saveEnrollment(new AlertEnrollment("pending-phone", "c".repeat(32), PushDeliveryService.hashToken(token),
                List.of("fire"), now, now + 900_000, "pending", "", "", ""));
            f.updates = JSON.valueToTree(Map.of("cursor", "9", "updates", List.of(Map.of(
                "type", "enrollment_completed", "enrollment_id", "c".repeat(32), "device_id", REMOTE_A))));
            f.hold("GET", "/updates");
            var poll = requests.submit(() -> { f.push.relayTick(); return null; });
            assertTrue(f.entered.await(2, TimeUnit.SECONDS));
            try { requests.submit(() -> { f.push.deregister(); return null; }).get(2, TimeUnit.SECONDS); }
            finally { f.release.countDown(); }
            poll.get(2, TimeUnit.SECONDS);
            assertTrue(f.service.devices().isEmpty());
            assertEquals("cancelled", f.service.enrollment("pending-phone").orElseThrow().status());
            assertEquals("0", new PushCredentialStore(directory.resolve("late-updates.sqlite")).get().updateCursor());
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

    @Test void relayRecoveryRequiresTheWholeStatusUpdatesAndPreferencesCheckToSucceed() throws Exception
    {
        Logger logger = (Logger)LoggerFactory.getLogger(PushDeliveryService.class);
        Level previousLevel = logger.getLevel();
        ListAppender<ILoggingEvent> messages = new ListAppender<>();
        messages.start(); logger.addAppender(messages); logger.setLevel(Level.DEBUG);
        try(Fixture f = new Fixture(directory.resolve("relay-recovery.sqlite")))
        {
            f.push.deliverRelayBatch(f.publish());
            f.push.updateDevice(f.tokenB, List.of());
            f.registrationStatusCode.set(503);
            f.push.relayTick();
            assertEquals(1, messages.list.size());
            assertEquals("RadioResolve status and queued preferences will be checked again (failures: GatewayException=1)", messages.list.getLast().getFormattedMessage());

            f.registrationStatusCode.set(200); f.updatesStatusCode.set(503);
            f.push.relayTick();
            assertEquals(1, messages.list.size(), "A successful status request must not announce recovery before updates succeed");
            f.updatesStatusCode.set(200); f.alertStatus.set(503); f.push.relayTick();
            assertEquals(1, messages.list.size(), "Successful status and updates requests must not announce recovery before queued preferences succeed");
            f.alertStatus.set(202); f.push.relayTick();
            assertEquals(2, messages.list.size());
            assertEquals("RadioResolve status and queued preferences recovered (failures: GatewayException=3)", messages.list.getLast().getFormattedMessage());
            f.push.relayTick();
            assertEquals(2, messages.list.size());
            assertTrue(messages.list.stream().allMatch(event -> event.getLevel() == Level.DEBUG && event.getThrowableProxy() == null));
        }
        finally
        {
            logger.detachAppender(messages); logger.setLevel(previousLevel); messages.stop();
        }
    }

    private static final class Fixture implements AutoCloseable
    {
        final TopicAlertService service; final PushDeliveryService push; final HttpServer server;
        final List<JsonNode> requests = new java.util.concurrent.CopyOnWriteArrayList<>(); final AtomicInteger alertStatus = new AtomicInteger(202), httpCalls = new AtomicInteger();
        final AtomicInteger registrationStatusCode = new AtomicInteger(200), updatesStatusCode = new AtomicInteger(200);
        final String tokenA = PublicAccessVerifier.token(), tokenB = PublicAccessVerifier.token();
        final ExecutorService serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        volatile String heldMethod = "", heldPath = "";
        void hold(String method, String path) { heldPath = path; heldMethod = method; }
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
            server.setExecutor(serverExecutor);
            server.createContext("/", exchange -> {
                httpCalls.incrementAndGet(); Object response; int status = 200;
                if(heldMethod.equals(exchange.getRequestMethod()) && heldPath.equals(exchange.getRequestURI().getPath()))
                {
                    entered.countDown();
                    try { if(!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Fixture response was not released"); }
                    catch(InterruptedException exception) { Thread.currentThread().interrupt(); throw new java.io.IOException(exception); }
                }
                if(exchange.getRequestURI().getPath().equals("/alerts"))
                {
                    requests.add(JSON.readTree(exchange.getRequestBody())); status = alertStatus.get();
                    response = Map.of("alert_id", "remote-alert", "status", "queued");
                }
                else if(exchange.getRequestURI().getPath().equals("/senders")) { registrationRequest = JSON.readTree(exchange.getRequestBody()); response = Map.of("sender_id", "sender-fixture", "status", "approved"); }
                else if(exchange.getRequestURI().getPath().equals("/senders/me")) { status = registrationStatusCode.get(); response = Map.of("sender_id", "sender-fixture", "status", "approved"); }
                else if(exchange.getRequestURI().getPath().equals("/enrollments")) response = Map.of(
                    "id", "f".repeat(32), "expires_at_ms", System.currentTimeMillis() + 900_000,
                    "enrollment_url", "https://radioresolve.com/vce-push/enroll.php?code=synthetic-session.synthetic-code",
                    "code", "synthetic-code");
                else { status = updatesStatusCode.get(); response = updates; }
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
        public void close() { release.countDown(); push.close(); service.close(); server.stop(0); serverExecutor.close(); }
    }
}
