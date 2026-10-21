/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TopicAlertStoreTest
{
    @TempDir Path mDirectory;
    private static final long NOW = 1_797_000_000_000L;

    @Test void exactSourcePersistsWithAlertButNeverEntersPublicHistoryOrDelivery() throws Exception
    {
        Path path = mDirectory.resolve("source.sqlite"); TopicAlertStore store = store(path);
        var phone = device("phone", "remote-phone", "a"); store.saveDevice(phone);
        String source = "00000000-0000-4000-8000-000000000001";
        var event = new AlertEvent("call", NOW, "call", Map.of(), AlertAction.Context.EMPTY, source);
        assertTrue(store.publish(topic(), "rule", "Rule", event, "Title", "Body", "details", List.of(phone), NOW));
        var delivery = store.pendingDeliveries(1, NOW).getFirst();
        TopicAlertStore restored = new TopicAlertStore(path);
        assertEquals(source, restored.sourceCallUuid(delivery.alertId()).orElseThrow());
        assertEquals(List.of(delivery.alertId()), restored.historyBySourceCallReferences(List.of(source, source)).stream().map(item -> item.get("id")).toList());
        assertTrue(restored.historyBySourceCallReferences(List.of("00000000-0000-4000-8000-000000000002")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> restored.historyBySourceCallReferences(java.util.Collections.nCopies(129, source)));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for(Object publicValue: List.of(restored.history(null, 100, 0), restored.historyItem(delivery.alertId()).orElseThrow(), delivery, event))
        {
            String json = mapper.writeValueAsString(publicValue);
            assertFalse(json.contains(source)); assertFalse(json.contains("source_call_uuid")); assertFalse(json.contains("sourceCallUuid"));
        }
        var config = restored.configuration();
        restored.updateConfiguration(new TopicAlertConfiguration(config.revision(), config.push(),
            List.of(AlertTopic.administratorAlerts(), new AlertTopic("fire", "Fire", "", false, true, false)), List.of()), config.revision());
        assertTrue(restored.sourceCallUuid(delivery.alertId()).isEmpty(), "Hidden topics cannot resolve source content");
        assertTrue(restored.historyBySourceCallReferences(List.of(source)).isEmpty());
    }

    @Test void relayRecipientSnapshotsAndTerminalStatesSurviveDeviceChangesAndBatchRetry() throws Exception
    {
        Path path = mDirectory.resolve("alerts.sqlite"); TopicAlertStore store = store(path);
        var first = device("one", "remote-one", "a"); var second = device("two", "remote-two", "b");
        store.saveDevice(first); store.saveDevice(second);
        assertTrue(store.publish(topic(), "rule", "Rule name", new AlertEvent("event", NOW, "call", Map.of()), "Title", "Body", "details", List.of(first, second), NOW));
        var batch = store.pendingDeliveries(100, NOW); String alertId = batch.getFirst().alertId();
        assertTrue(store.queuedRelayAlerts(100).isEmpty(), "Untouched pending alerts have no remote work to reconcile");
        store.saveDevice(device("one", "remote-renewed", "a")); store.unsubscribe("two");
        assertEquals(Set.of("remote-one", "remote-two"), store.deliveriesForAlert(alertId).stream().map(AlertDelivery::remoteDeviceId).collect(java.util.stream.Collectors.toSet()));
        assertEquals("remote-one", store.deliveryForRemote(alertId, "remote-one").orElseThrow().remoteDeviceId());
        assertTrue(store.deliveryForRemote(alertId, "remote-renewed").isEmpty());
        store.retryRelayAlert(alertId, "Temporary relay failure", NOW + 1000);
        assertEquals(alertId, store.queuedRelayAlerts(100).getFirst().alertId(), "Attempted retries can suppress an unknown remote POST outcome");
        assertEquals("retry", store.deliveryForRemote(alertId, "remote-one").orElseThrow().state());
        assertEquals("cancelled", store.deliveryForRemote(alertId, "remote-two").orElseThrow().state());
        store.queueRelayAlert(alertId, "relay-alert");
        var accepted = store.deliveryForRemote(alertId, "remote-one").orElseThrow();
        assertEquals("queued", accepted.state()); assertTrue(store.pendingDeliveries(100, NOW + 1000).isEmpty());
        store.completeDelivery(accepted.id(), "opened", "relay-alert", "", 0); store.queueRelayAlert(alertId, "relay-alert");
        assertEquals("opened", store.delivery(accepted.id()).orElseThrow().state());
    }

    @Test void pairingSessionAndOwnershipCompleteAtomicallyAndRetainUnavailableExistingChoices() throws Exception
    {
        Path path = mDirectory.resolve("pairing.sqlite"); TopicAlertStore store = store(path);
        var session = new AlertEnrollment("session", "remote-session", "c".repeat(64), List.of("fire"), NOW, NOW + 100_000, "pending", "", "https://example.test/pair", "code");
        store.saveEnrollment(session);
        var hidden = new AlertTopic("fire", "Fire", "", false, false, false); var config = store.configuration();
        store.updateConfiguration(new TopicAlertConfiguration(config.revision(), config.push(), List.of(AlertTopic.administratorAlerts(), hidden), List.of()), config.revision());
        var completed = new AlertEnrollment("session", "remote-session", "c".repeat(64), List.of("fire"), NOW, NOW + 100_000, "completed", "phone", session.enrollmentUrl(), session.code());
        var phone = device("phone", "remote-phone", "c"); store.completeEnrollment(completed, phone);
        TopicAlertStore restored = new TopicAlertStore(path);
        assertEquals("phone", restored.findEnrollmentByTokenHash("c".repeat(64)).orElseThrow().deviceId());
        assertEquals("https://example.test/pair", restored.byRemoteEnrollmentId("remote-session").orElseThrow().enrollmentUrl());
        assertEquals(List.of("fire"), restored.findDeviceByTokenHash("c".repeat(64)).orElseThrow().topicIds());
        assertThrows(IllegalArgumentException.class, () -> restored.saveDevice(device("new", "remote-new", "d")));
    }

    @Test void acceptsTheWholeMaximumFanoutAndRejectsCapacityWithoutPartialMessages() throws Exception
    {
        Path path = mDirectory.resolve("fanout.sqlite"); TopicAlertStore store = store(path);
        try(Connection connection = SdrTrunkDatabase.open(path); Statement sql = connection.createStatement())
        {
            sql.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<1000) INSERT INTO topic_alert_device SELECT 'd'||i,'Phone','proxy','remote-'||i,'','','',printf('%064x',i),0 FROM n");
            sql.executeUpdate("INSERT INTO topic_alert_membership SELECT id,'fire' FROM topic_alert_device");
        }
        var all = store.devices(); assertEquals(1000, all.size());
        assertTrue(assertThrows(IllegalArgumentException.class, () -> store.saveDevice(device("overflow", "remote-overflow", "f"))).getMessage().contains("1000 notification devices"));
        var event = new AlertEvent("a", NOW, "call", Map.of());
        assertTrue(store.publish(topic(), "rule", "Rule", event, "Title", "Body", "details", all, NOW));
        String firstId = store.pendingDeliveries(1, NOW).getFirst().alertId();
        assertEquals(1000, store.deliveriesForAlert(firstId).size(), "One immutable batch retains every recipient");
        store.queueRelayAlert(firstId, "relay-one");
        assertTrue(store.publish(topic(), "rule", "Rule", event, "Title", "Body", "details", all, NOW + 1));
        String secondId = store.pendingDeliveries(1, NOW + 1).getFirst().alertId(); store.queueRelayAlert(secondId, "relay-two");
        assertFalse(store.publish(topic(), "rule", "Rule", event, "Title", "Body", "details", List.of(all.getFirst()), NOW + 2));
        assertEquals(2, store.history(null, 100, 0).get("total"));
        var queued = store.queuedRelayAlerts(100); assertEquals(2, queued.size());
        assertTrue(queued.getFirst().id() < queued.getLast().id());
    }

    @Test void actualProviderAcceptanceSupersedesLocalCancellationExpiryAndFailureButNeverAnOpen() throws Exception
    {
        Path path = mDirectory.resolve("outcomes.sqlite"); TopicAlertStore store = store(path); var phone = device("phone", "remote-phone", "e"); store.saveDevice(phone);
        for(String local: List.of("cancelled", "expired", "failed"))
        {
            store.publish(topic(), "rule", "Rule", new AlertEvent(local, NOW, "call", Map.of()), "Title", "Body", "details", List.of(phone), NOW);
            long id = store.pendingDeliveries(1, NOW).getFirst().id();
            store.completeDelivery(id, local, "", "Local outcome", 0); store.completeDelivery(id, "push_accepted", "remote-alert", "", 0);
            assertEquals("push_accepted", store.delivery(id).orElseThrow().state());
            store.completeDelivery(id, "opened", "remote-alert", "", 0); store.completeDelivery(id, "push_accepted", "remote-alert", "", 0);
            store.completeDelivery(id, "failed", "remote-alert", "", 0); assertEquals("opened", store.delivery(id).orElseThrow().state());
        }
    }

    @Test void expiredSubscriptionsRetainGuestTopicsAndFreshMaterialReactivatesNewAlerts() throws Exception
    {
        for(String method: List.of("direct", "proxy"))
        {
            Path path = mDirectory.resolve("renew-" + method + ".sqlite"); TopicAlertStore store = store(path);
            var phone = new AlertDevice("phone", "Saved phone", method, "proxy".equals(method) ? "remote-phone" : "",
                "direct".equals(method) ? "https://example.test/push/old" : "", "direct".equals(method) ? "public-key" : "",
                "direct".equals(method) ? "auth" : "", "a".repeat(64), List.of("fire"), false);
            store.saveDevice(phone);
            store.publish(topic(), "rule", "Rule", new AlertEvent("before", NOW, "call", Map.of()), "Title", "Body", "details", List.of(phone), NOW);
            long oldDelivery = store.pendingDeliveries(1, NOW).getFirst().id(); store.markDeviceExpired(phone.id(), true);
            var restored = new TopicAlertStore(path).findDeviceByTokenHash(phone.manageTokenHash()).orElseThrow();
            assertTrue(restored.subscriptionExpired()); assertEquals(phone.topicIds(), restored.topicIds());
            assertEquals("Saved phone", restored.name()); assertTrue(store.devicesForTopic("fire", method).isEmpty());
            assertTrue(store.pendingDeliveries(100, NOW).isEmpty()); assertEquals("expired", store.delivery(oldDelivery).orElseThrow().state());
            var fresh = new AlertDevice(restored.id(), restored.name(), method, restored.remoteDeviceId(),
                "direct".equals(method) ? "https://example.test/push/new" : "", restored.p256dh(), restored.auth(), restored.manageTokenHash(),
                restored.topicIds(), false);
            store.saveDevice(fresh); assertFalse(store.findDeviceByTokenHash(phone.manageTokenHash()).orElseThrow().subscriptionExpired());
            if("direct".equals(method))
            {
                assertFalse(store.markDeviceSubscriptionExpired(phone.id(), phone.endpoint(), phone.p256dh(), phone.auth()), "A late failure from old material cannot expire the refreshed destination");
                assertFalse(store.device(phone.id()).orElseThrow().subscriptionExpired());
            }
            assertEquals(1, store.devicesForTopic("fire", method).size());
            store.publish(topic(), "rule", "Rule", new AlertEvent("after", NOW + 1, "call", Map.of()), "Title", "Body", "details", List.of(fresh), NOW + 1);
            assertEquals(1, store.pendingDeliveries(100, NOW + 1).size()); assertEquals("expired", store.delivery(oldDelivery).orElseThrow().state());
            if("direct".equals(method))
            {
                assertTrue(store.markDeviceSubscriptionExpired(fresh.id(), fresh.endpoint(), fresh.p256dh(), fresh.auth()));
                assertTrue(store.device(phone.id()).orElseThrow().subscriptionExpired()); assertTrue(store.pendingDeliveries(100, NOW + 1).isEmpty());
            }
        }
    }

    @Test void representativeTopicMembershipHistoryAndDeliveryLookupsUseTheirIndexes() throws Exception
    {
        Path path = mDirectory.resolve("plans.sqlite"); store(path);
        try(Connection connection = SdrTrunkDatabase.open(path); Statement sql = connection.createStatement())
        {
            long before = allocated(connection); connection.setAutoCommit(false);
            sql.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<1000) INSERT INTO topic_alert_device SELECT 'd'||i,'Phone','proxy','remote-'||i,'','','',printf('%064x',i),0 FROM n");
            sql.executeUpdate("INSERT INTO topic_alert_membership SELECT id,'fire' FROM topic_alert_device");
            sql.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<1000) INSERT INTO topic_alert_message(id,rule_id,topic_id,topic_name,alert_name,title,body,click_action,event_type,created_at_ms,expires_at_ms) SELECT 'm'||i,'rule','fire','Fire','Rule name','Title',printf('%02048d',i),'details','call'," + NOW + "+i," + (NOW + 3_600_000) + " FROM n");
            sql.executeUpdate("INSERT INTO topic_alert_delivery(alert_id,device_id,remote_device_id,method,state,attempts,next_at_ms,remote_id,error,updated_at_ms) SELECT 'm'||substr(id,2),id,remote_device_id,'proxy','pending',0," + NOW + ",'',''," + NOW + " FROM topic_alert_device");
            connection.commit();
            assertTrue(plan(connection, "SELECT v.id FROM topic_alert_membership membership JOIN topic_alert_device v ON v.id=membership.device_id WHERE membership.topic_id='fire' AND v.method='proxy' ORDER BY membership.device_id LIMIT 100").contains("idx_topic_alert_membership_topic"));
            String historyPlan = plan(connection, "SELECT id FROM topic_alert_message WHERE topic_id='fire' ORDER BY created_at_ms DESC,id LIMIT 100");
            assertTrue(historyPlan.contains("idx_topic_alert_message_topic_time")); assertFalse(historyPlan.contains("TEMP B-TREE"));
            assertTrue(plan(connection, "SELECT m.id FROM topic_alert_message m JOIN topic_alert_topic t ON t.id=m.topic_id WHERE m.id='m500' AND t.visible=1").contains("SEARCH m USING INDEX sqlite_autoindex_topic_alert_message_1"));
            assertTrue(plan(connection, "SELECT id FROM topic_alert_delivery WHERE alert_id='m500' AND remote_device_id='remote-500' AND remote_device_id<>''").contains("idx_topic_alert_delivery_remote"));
            assertTrue(plan(connection, "SELECT id FROM topic_alert_delivery WHERE state IN ('pending','retry') AND next_at_ms<=" + NOW + " ORDER BY next_at_ms,id LIMIT 100").contains("idx_topic_alert_delivery_pending"));
            System.out.println("Alert storage sample: " + (allocated(connection) - before) + " allocated bytes for 1000 messages, devices, memberships and pending deliveries");
            sql.executeUpdate("UPDATE topic_alert_delivery SET state='queued',remote_id='relay-'||alert_id");
            sql.executeUpdate("WITH RECURSIVE shift(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM shift WHERE n<19) INSERT INTO topic_alert_delivery(alert_id,device_id,remote_device_id,method,state,attempts,next_at_ms,remote_id,error,updated_at_ms) SELECT 'm'||i,'d'||((i+n-1)%1000+1),'remote-'||((i+n-1)%1000+1),'proxy','failed',1," + NOW + ",'',''," + NOW + " FROM (SELECT CAST(substr(id,2) AS INTEGER) i FROM topic_alert_device) CROSS JOIN shift");
            connection.commit();
            long queryStarted = System.nanoTime(); var queued = new TopicAlertStore(path);
            var first = queued.queuedRelayAlerts(100); var second = queued.queuedRelayAlerts(100);
            assertEquals(100, first.size()); assertEquals(100, second.size()); assertTrue(second.getFirst().id() > first.getLast().id());
            Set<String> visited = new java.util.HashSet<>(); first.forEach(item -> visited.add(item.alertId())); second.forEach(item -> visited.add(item.alertId()));
            for(int i = 0; i < 8; i++) queued.queuedRelayAlerts(100).forEach(item -> visited.add(item.alertId()));
            assertEquals(1000, visited.size()); assertEquals(first.getFirst().id(), queued.queuedRelayAlerts(100).getFirst().id(), "Queued reconciliation wraps after visiting later alerts");
            System.out.println("Queued relay lookup sample: " + ((System.nanoTime() - queryStarted) / 1_000_000.0) + " ms for11 bounded batches over20000 delivery rows");
            assertFalse(queued.publish(topic(), "rule", "Rule", new AlertEvent("overflow", NOW, "call", Map.of()), "Title", "Body", "details", List.of(queued.device("d1").orElseThrow()), NOW), "A protected recipient snapshot set cannot grow beyond the retained-delivery budget");
            sql.executeUpdate("WITH RECURSIVE n(i) AS (VALUES(1) UNION ALL SELECT i+1 FROM n WHERE i<2000) INSERT INTO topic_alert_message(id,rule_id,topic_id,topic_name,alert_name,title,body,click_action,event_type,created_at_ms,expires_at_ms) SELECT 'later'||i,'rule','fire','Fire','Rule name','Title','Body','details','call'," + NOW + "+10000+i," + (NOW + 3_600_000) + " FROM n");
            sql.executeUpdate("INSERT INTO topic_alert_delivery(alert_id,device_id,remote_device_id,method,state,attempts,next_at_ms,remote_id,error,updated_at_ms) SELECT id,'d1','remote-1','proxy','failed',1," + NOW + ",'',''," + NOW + " FROM topic_alert_message WHERE id LIKE 'later%'");
            connection.commit(); queued.prune(NOW);
            assertEquals(20, queued.deliveriesForAlert("m1").size(), "Queued batch retains completed recipient siblings beyond ordinary history pruning");
            assertTrue(queued.historyItem("m1").isPresent());
        }
    }

    private TopicAlertStore store(Path path) throws Exception
    {
        SdrTrunkDatabaseStartup.createGlobalDatabase(path); var store = new TopicAlertStore(path); var config = store.configuration();
        store.updateConfiguration(new TopicAlertConfiguration(config.revision(), config.push(), List.of(AlertTopic.administratorAlerts(), topic()), List.of()), config.revision()); return store;
    }
    private static AlertTopic topic() { return new AlertTopic("fire", "Fire", "", true, true, false); }
    private static AlertDevice device(String id, String remote, String token) { return new AlertDevice(id, "Phone", "proxy", remote, "", "", "", token.repeat(64), List.of("fire"), false); }
    private static String plan(Connection connection, String query) throws Exception
    {
        StringBuilder result = new StringBuilder(); try(Statement sql = connection.createStatement(); var rows = sql.executeQuery("EXPLAIN QUERY PLAN " + query)) { while(rows.next()) result.append(rows.getString(4)).append('\n'); } return result.toString();
    }
    private static long allocated(Connection connection) throws Exception
    {
        try(Statement sql = connection.createStatement(); var rows = sql.executeQuery("SELECT page_count*page_size FROM pragma_page_count,pragma_page_size")) { rows.next(); return rows.getLong(1); }
    }
}
