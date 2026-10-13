/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TopicAlertServiceTest
{
    @TempDir Path mDirectory;
    private static final String CHANNEL = "00000000-0000-4000-8000-000000000001";
    private static final long START = Instant.parse("2026-10-13T12:00:00Z").toEpochMilli();

    @Test void scopedMatchingRejectsSameIdsOnAnotherReceiverAndDeduplicatesUpdates() throws Exception
    {
        AtomicLong clock = new AtomicLong(START);
        try(TopicAlertService service = configured(clock, rule("immediate", 0, 0, 0)))
        {
            service.observe(event("other-source", START, "other", "100", "200")); service.drain();
            assertEquals(0, service.history(null, 100, 0).get("total"));
            service.observe(event("call-a", START, CHANNEL, "100", "200")); service.drain();
            service.observe(event("call-a", START, CHANNEL, "100", "200")); service.drain();
            assertEquals(1, service.history(null, 100, 0).get("total"));
            assertEquals(1, service.pendingDeliveries(100).size());
            assertEquals("Radio 100", service.pendingDeliveries(100).getFirst().title());
            assertEquals("Radio activity", service.pendingDeliveries(100).getFirst().alertName());
            assertEquals("200 is active at East", service.pendingDeliveries(100).getFirst().body());
        }
    }

    @Test void countAndRepeatLimitsCountDistinctEventsAndKeepRadiosSeparate() throws Exception
    {
        AtomicLong clock = new AtomicLong(START);
        try(TopicAlertService service = configured(clock, rule("count", 0, 2, 5)))
        {
            service.observe(event("a", START, CHANNEL, "100", "200")); service.drain();
            service.observe(event("a", START, CHANNEL, "100", "200")); service.drain();
            assertEquals(0, service.history(null, 100, 0).get("total"));
            service.observe(event("b", START, CHANNEL, "100", "200")); service.drain();
            assertEquals(1, service.history(null, 100, 0).get("total"));
            service.observe(event("c", START, CHANNEL, "100", "200")); service.observe(event("d", START, CHANNEL, "100", "200")); service.drain();
            assertEquals(1, service.history(null, 100, 0).get("total"));
            clock.addAndGet(60_000);
            service.observe(event("e", START, CHANNEL, "100", "200")); service.observe(event("f", START, CHANNEL, "100", "200")); service.drain();
            assertEquals(2, service.history(null, 100, 0).get("total"));
        }
    }

    @Test void missingEncryptionDoesNotSatisfyAnExclusionAndOvernightSchedulesUsePreviousDay()
    {
        var none = new AlertRule.Condition("none", List.of(new AlertRule.Condition("", null, "encrypted", "is", "true")), "", "", "");
        assertEquals(TopicAlertService.Match.UNKNOWN, TopicAlertService.match(none, new AlertEvent("a", START, "call", Map.of())));
        assertEquals(TopicAlertService.Match.YES, TopicAlertService.match(none, new AlertEvent("b", START, "call", Map.of("encrypted", List.of("false")))));
        var mondayNight = new AlertRule.Schedule("custom", List.of(0), "22:00", "06:00", "UTC");
        assertTrue(TopicAlertService.scheduleMatches(mondayNight, Instant.parse("2026-10-13T03:00:00Z").toEpochMilli()));
        assertFalse(TopicAlertService.scheduleMatches(mondayNight, Instant.parse("2026-10-14T03:00:00Z").toEpochMilli()));
    }

    @Test void fullIngressRejectsImmediatelyEvenWhenObserverDoesNotDrain() throws Exception
    {
        AtomicLong clock = new AtomicLong(START);
        try(TopicAlertService service = configured(clock, rule("immediate", 0, 0, 0)))
        {
            for(int i = 0; i < TopicAlertService.INGRESS_CAPACITY; i++) assertTrue(service.observe(event("a" + i, START, CHANNEL, "100", "200")));
            assertFalse(service.observe(event("overflow", START, CHANNEL, "100", "200")));
            assertEquals(1, service.droppedObservations());
        }
    }

    @Test void topicDeletionCannotEraseActiveRelayCancellationMetadata() throws Exception
    {
        AtomicLong clock = new AtomicLong(START);
        try(TopicAlertService service = configured(clock, rule("immediate", 0, 0, 0)))
        {
            service.observe(event("a", START, CHANNEL, "100", "200")); service.drain();
            var delivery = service.pendingDeliveries(1).getFirst(); service.queueRelayAlert(delivery.alertId(), "relay-alert");
            service.deleteRule("r", service.configuration().revision()); long revision = service.configuration().revision();
            assertEquals("Pause or hide this topic and wait for queued notifications to finish before deleting it.",
                assertThrows(IllegalArgumentException.class, () -> service.deleteTopic("fire", revision)).getMessage());
            assertEquals(revision, service.configuration().revision(), "Rejected deletion rolls back the revision");
            service.saveTopic(new AlertTopic("fire", "Fire", "", false, false, false), revision);
            assertEquals(delivery.alertId(), service.queuedRelayAlerts(100).getFirst().alertId(), "Hidden queued work remains available for suppression reconciliation");
            assertEquals("remote-phone", service.deliveriesForAlert(delivery.alertId()).getFirst().remoteDeviceId());
            service.completeDelivery(delivery.id(), "push_accepted", "relay-alert", "", 0);
            service.deleteTopic("fire", service.configuration().revision()); assertTrue(service.topics().stream().noneMatch(topic -> "fire".equals(topic.id())));
        }
    }

    @Test void sustainedHealthAndRecoveryTrackOnlyNotifiedIncidentsAndBuiltInStartsHidden() throws Exception
    {
        AtomicLong clock = new AtomicLong(START);
        var healthRule = new AlertRule("r", "USB health", "fire", true, "health", "", "", "", "",
            new AlertRule.Condition("all", List.of(new AlertRule.Condition("", null, "severity", "is", "warning")), "", "", ""),
            new AlertRule.Timing("sustained", 2, 0, 0), AlertRule.Schedule.always(), 0, "rule", true, "{issue}", "{state}", "details");
        try(TopicAlertService service = configured(clock, healthRule))
        {
            var issue = Map.<String,Object>of("occurrence_id", 1, "code", "usb_stall", "severity", "warning", "title", "USB transfers stalled", "scope", "Tuner", "opened_at_ms", START);
            service.observeHealth(health(List.of(issue), List.of())); service.drain();
            assertEquals(0, service.history(null, 100, 0).get("total"));
            clock.addAndGet(120_000);
            service.observeHealth(health(List.of(issue), List.of())); service.drain();
            service.observeHealth(health(List.of(issue), List.of())); service.drain();
            assertEquals(1, service.history(null, 100, 0).get("total"));
            var recovered = Map.<String,Object>of("occurrence_id", 1, "code", "usb_stall", "severity", "info", "title", "USB transfers recovered", "scope", "Tuner", "opened_at_ms", START);
            service.observeHealth(health(List.of(), List.of(recovered))); service.drain();
            service.observeHealth(health(List.of(), List.of(recovered))); service.drain();
            assertEquals(2, service.history(null, 100, 0).get("total"), "One recovery follows the emitted sustained incident");
            var shortIssue = Map.<String,Object>of("occurrence_id", 2, "code", "usb_stall", "severity", "warning", "title", "USB transfers stalled", "scope", "Tuner", "opened_at_ms", clock.get());
            service.observeHealth(health(List.of(shortIssue), List.of())); service.drain();
            service.observeHealth(health(List.of(), List.of(shortIssue))); service.drain();
            assertEquals(2, service.history(null, 100, 0).get("total"), "Unnotified short incidents do not emit a standalone recovery");
            service.saveTopic(new AlertTopic(AlertTopic.ADMINISTRATOR_TOPIC_ID, "Administrator alerts", "", true, true, true), service.configuration().revision());
            service.observeHealth(health(List.of(shortIssue), List.of())); service.drain();
            assertEquals(3, service.history(null, 100, 0).get("total"));
            @SuppressWarnings("unchecked") var items = (List<Map<String,Object>>)service.history(null, 100, 0).get("items");
            assertTrue(items.stream().anyMatch(item -> item.get("alert_name").equals("Administrator alerts")));
        }
    }

    private static Map<String,Object> health(List<Map<String,Object>> active, List<Map<String,Object>> resolved)
    {
        return Map.of("started_at_ms", START, "active", active, "resolved", resolved);
    }

    @Test void sustainedHealthDoesNotRepeatWhenUnrelatedCallsEvictTheSharedEventDedupMarker() throws Exception
    {
        AtomicLong clock = new AtomicLong(START);
        var healthRule = new AlertRule("health", "Health", "fire", true, "health", "", "", "", "", AlertRule.Condition.all(),
            new AlertRule.Timing("sustained", 1, 0, 0), AlertRule.Schedule.always(), 0, "rule", false, "{issue}", "{state}", "details");
        try(TopicAlertService service = configured(clock, healthRule))
        {
            service.saveRule(rule("immediate", 0, 0, 0), service.configuration().revision());
            var issue = Map.<String,Object>of("occurrence_id", 1, "code", "usb_stall", "severity", "warning", "title", "USB stalled", "scope", "Tuner", "opened_at_ms", START);
            service.observeHealth(health(List.of(issue), List.of())); service.drain(); clock.addAndGet(60_000);
            service.observeHealth(health(List.of(issue), List.of())); service.drain(); assertEquals(1, service.history(null, 100, 0).get("total"));
            for(int i = 0; i < 5000; i++)
            {
                service.observe(event("traffic-" + i, clock.get(), CHANNEL, "100", "200")); if(i % 128 == 127) service.drain();
            }
            service.drain(); assertEquals(2, service.history(null, 100, 0).get("total"));
            service.observeHealth(health(List.of(issue), List.of())); service.drain();
            assertEquals(2, service.history(null, 100, 0).get("total"), "A still-open sustained incident does not repeat after unrelated event pressure");
        }
    }

    @Test void persistsConfigGuestManagementAndDurableDeliveryAndHonorsHiddenTopics() throws Exception
    {
        AtomicLong clock = new AtomicLong(START);
        Path database = mDirectory.resolve("persistent.sqlite"); SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        var store = new TopicAlertStore(database);
        try(TopicAlertService service = new TopicAlertService(store, clock::get))
        {
            configure(service, rule("immediate", 0, 0, 0));
            service.observe(event("call", START, CHANNEL, "100", "200")); service.drain();
            AlertDelivery delivery = service.pendingDeliveries(100).getFirst();
            long id = delivery.id();
            assertEquals("Radio activity", service.historyItem(delivery.alertId()).orElseThrow().get("alert_name"));
            assertTrue(service.historyItem("unknown").isEmpty());
            service.saveTopic(new AlertTopic("fire", "Renamed fire", "", true, false, false), service.configuration().revision());
            assertTrue(service.historyItem(delivery.alertId()).isPresent(), "Disabled visible topics retain readable history");
            service.deleteRule("r", service.configuration().revision());
            assertEquals("Radio activity", service.historyItem(delivery.alertId()).orElseThrow().get("alert_name"), "Historical name survives deleting its rule");
            service.completeDelivery(id, "opened", "remote", "", 0);
            service.completeDelivery(id, "queued", "remote", "", 0);
            assertEquals("opened", service.delivery(id).orElseThrow().state());
            service.saveTopic(new AlertTopic("fire", "Renamed fire", "", false, true, false), service.configuration().revision());
            assertTrue(service.historyItem(delivery.alertId()).isEmpty());
            assertEquals(0, service.history(List.of("fire"), 100, 0).get("total"));
            assertEquals(1, service.history(null, 100, 0).get("total"));
            assertThrows(ConcurrentModificationException.class, () -> service.saveTopic(new AlertTopic("other", "Other", "", true, true, false), 1));
        }
        assertEquals("Renamed fire", store.configuration().topics().stream().filter(t -> t.id().equals("fire")).findFirst().orElseThrow().name());
        assertTrue(store.findDeviceByTokenHash("a".repeat(64)).isPresent());
        assertEquals("opened", store.delivery(1).orElseThrow().state());
    }

    private TopicAlertService configured(AtomicLong clock, AlertRule rule) throws Exception
    {
        Path database = mDirectory.resolve("alerts-" + System.nanoTime() + ".sqlite"); SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        var service = new TopicAlertService(new TopicAlertStore(database), clock::get); configure(service, rule); return service;
    }
    private static void configure(TopicAlertService service, AlertRule rule) throws Exception
    {
        service.updateConfiguration(new TopicAlertConfiguration(1, new TopicAlertConfiguration.PushSettings(true, "proxy", "", "VCE test"),
            List.of(AlertTopic.administratorAlerts(), new AlertTopic("fire", "Fire", "", true, true, false)), List.of(rule)), 1);
        service.saveDevice(new AlertDevice("phone", "Phone", "proxy", "remote-phone", "", "", "", "a".repeat(64), List.of("fire"), false, "", "", "UTC"));
    }
    private static AlertRule rule(String mode, int minutes, int count, int window)
    {
        return new AlertRule("r", "Radio activity", "fire", true, "call", "", "", CHANNEL, "APCO25",
            new AlertRule.Condition("all", List.of(new AlertRule.Condition("", null, "talkgroup", "is", "200")), "", "", ""),
            new AlertRule.Timing(mode, minutes, count, window), AlertRule.Schedule.always(), 1, "radio", false, "Radio {radio}", "{talkgroup} is active at {site}", "details");
    }
    private static AlertEvent event(String id, long time, String channel, String radio, String group)
    {
        return new AlertEvent(id, time, "call", Map.of("channel_id", List.of(channel), "radio", List.of(radio), "talkgroup", List.of(group), "protocol", List.of("APCO25"), "site_id", List.of("1:2"), "site_name", List.of("East")));
    }
}
