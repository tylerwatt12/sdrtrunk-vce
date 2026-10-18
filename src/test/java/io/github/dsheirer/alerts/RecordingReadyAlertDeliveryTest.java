/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.alerts;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.audio.call.*;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingReadyAlertDeliveryTest
{
    @TempDir Path directory;
    static final String CHANNEL = "00000000-0000-4000-8000-000000000001";
    static final long START = 1_790_000_000_000L;

    @Test void exactReadinessReleasesBothArrivalOrdersForCallAndToneInEitherPushMode() throws Exception
    {
        for(String mode: List.of("proxy", "direct"))
        for(String trigger: List.of("call", "tone"))
        for(boolean readyFirst: List.of(false, true))
        {
            AtomicLong clock = new AtomicLong(START);
            try(var service = configured(clock, mode, trigger))
            {
                String source = SourceCallReference.fromToken("ready:1");
                if(readyFirst) { assertTrue(service.receiveRecordingReady(List.of(source))); service.drain(); }
                observe(service, trigger, 1, "ready:1"); service.drain();
                assertEquals(1, service.history(null, 100, 0).get("total"), "Matching and history are immediate");
                assertEquals(readyFirst ? 1 : 0, service.pendingDeliveries(100).size());
                if(!readyFirst)
                {
                    assertTrue(service.receiveRecordingReady(List.of(SourceCallReference.fromToken("other:1")))); service.drain();
                    assertTrue(service.pendingDeliveries(100).isEmpty(), "A different call cannot release this notification");
                    clock.incrementAndGet();
                    assertTrue(service.receiveRecordingReady(List.of(source))); service.drain();
                }
                AlertDelivery delivery = service.pendingDeliveries(100).getFirst();
                assertEquals(mode, delivery.method());
                assertEquals(START, delivery.createdAtMs(), "Readiness does not move matching time");
                assertTrue(service.receiveRecordingReady(List.of(source, source))); service.drain();
                observe(service, trigger, 1, "ready:1"); service.drain();
                assertEquals(1, service.pendingDeliveries(100).size(), "Completion/readiness duplicates do not duplicate fanout");
                assertEquals(1, service.history(null, 100, 0).get("total"));
            }
        }
    }

    @Test void emptyUnrecordedAndEncryptedCompletedCallsFallBackOnlyAfterTwoSeconds() throws Exception
    {
        for(var encryption: List.of(CallEncryptionState.CLEAR, CallEncryptionState.ENCRYPTED, CallEncryptionState.UNKNOWN))
        {
            AtomicLong clock = new AtomicLong(START);
            try(var service = configured(clock, "proxy", "call"))
            {
                var completed = call(1, "no-recording", encryption);
                assertFalse(completed.hasAudio()); assertFalse(completed.snapshot().recordAudio());
                service.receiveCompletedCall(completed); service.drain();
                assertEquals(1, service.history(null, 100, 0).get("total"));
                assertTrue(service.pendingDeliveries(100).isEmpty());
                clock.addAndGet(1999); assertTrue(service.pendingDeliveries(100).isEmpty());
                clock.incrementAndGet(); assertEquals(1, service.pendingDeliveries(100).size());
            }
        }
    }

    @Test void completedProjectionHasNoPcmAndUnknownEncryptionDoesNotBecomeClear() throws Exception
    {
        AtomicLong clock = new AtomicLong(START);
        try(var service = configured(clock, "proxy", "call"))
        {
            var rule = service.rules().getFirst();
            service.saveRule(new AlertRule(rule.id(), rule.name(), rule.topicId(), true, "call", "", "", "", "",
                new AlertRule.Condition("", null, "encrypted", "is", "false"), null, null, 0, "rule", false,
                "Call", "Call", "details"), service.configuration().revision());
            service.setCallProjector(completed -> {
                assertFalse(completed.hasAudio()); assertTrue(completed.audioBuffers().isEmpty());
                return DecodeEventViewService.completedCallView(completed, null);
            });
            var original = call(1, "unknown", CallEncryptionState.UNKNOWN);
            service.receiveCompletedCall(new CompletedAudioCall(original.snapshot(), List.of(new float[160]))); service.drain();
            assertEquals(0, service.history(null, 100, 0).get("total"));
            service.receiveCompletedCall(call(2, "clear", CallEncryptionState.CLEAR)); service.drain();
            assertEquals(1, service.history(null, 100, 0).get("total"));
        }
    }

    @Test void livePagingAndControlStatusAreNotSuppressedAsVoiceCalls() throws Exception
    {
        for(String type: List.of("CALL_ALERT", "CALL_NO_TUNER"))
        {
            AtomicLong clock = new AtomicLong(START);
            String trigger = type.equals("CALL_ALERT") ? "paging" : "event";
            try(var service = configured(clock, "proxy", trigger))
            {
                service.receiveEventView(view("live:" + type, type, List.of())); service.drain();
                assertEquals(1, service.pendingDeliveries(100).size(), type + " stays immediate without audio completion");
                assertEquals(trigger, service.pendingDeliveries(100).getFirst().alertName());
            }
        }
    }

    @Test void liveDcsRemainsImmediateAndCompletedCallDoesNotRepeatItsDcsObservation() throws Exception
    {
        AtomicLong clock = new AtomicLong(START);
        try(var service = configured(clock, "proxy", "call"))
        {
            service.saveRule(rule("dcs", "dcs"), service.configuration().revision());
            service.receiveEventView(view("live:voice", "CALL_GROUP", List.of("023"))); service.drain();
            assertEquals(1, service.pendingDeliveries(100).size());
            assertEquals("dcs", service.pendingDeliveries(100).getFirst().alertName());
            assertEquals(1, service.history(null, 100, 0).get("total"), "The live main call is suppressed");
            service.setCallProjector(call -> view("completed:voice", "CALL_GROUP", List.of("023")));
            service.receiveCompletedCall(call(1, "voice:1", CallEncryptionState.CLEAR)); service.drain();
            assertEquals(2, service.history(null, 100, 0).get("total"), "Only one completed CALL is added");
            assertEquals(1, service.pendingDeliveries(100).size(), "Completed DCS facts do not generate another DCS alert");
            clock.addAndGet(2000); assertEquals(2, service.pendingDeliveries(100).size());
        }
    }

    @Test void cooldownStartsAtCompletedCallMatchingAndDoesNotMoveWhenAudioBecomesReady() throws Exception
    {
        AtomicLong clock = new AtomicLong(START);
        try(var service = configured(clock, "proxy", "call"))
        {
            service.saveRule(new AlertRule("call", "call", "fire", true, "call", "", "", "", "",
                AlertRule.Condition.all(), null, null, 1, "rule", false, "Call", "Call", "details"), service.configuration().revision());
            observe(service, "call", 1, "cooldown:1"); service.drain();
            assertTrue(service.pendingDeliveries(100).isEmpty());
            clock.incrementAndGet(); service.receiveRecordingReady(List.of(SourceCallReference.fromToken("cooldown:1"))); service.drain();
            observe(service, "call", 2, "cooldown:2"); service.drain();
            assertEquals(1, service.history(null, 100, 0).get("total"));
            clock.set(START + 60_000); observe(service, "call", 3, "cooldown:3"); service.drain();
            assertEquals(2, service.history(null, 100, 0).get("total"));
            assertEquals(1, service.pendingDeliveries(100).size(), "The new matched call waits independently for its own recording");
        }
    }

    @Test void retriesAndOtherAlertTypesRemainIndependentOfRecordingReadiness() throws Exception
    {
        AtomicLong clock = new AtomicLong(START);
        try(var service = configured(clock, "proxy", "call"))
        {
            service.saveRule(rule("event", "paging"), service.configuration().revision());
            observe(service, "call", 1, "retry:1"); service.drain();
            service.observe(new AlertEvent("page", START, "paging", Map.of())); service.drain();
            assertEquals(1, service.pendingDeliveries(1).size(), "Waiting calls cannot block an unrelated event page");
            assertEquals("event", service.pendingDeliveries(1).getFirst().alertName());
            service.receiveRecordingReady(List.of(SourceCallReference.fromToken("retry:1"))); service.drain();
            var callDelivery = service.pendingDeliveries(100).stream().filter(delivery -> delivery.alertName().equals("call")).findFirst().orElseThrow();
            service.completeDelivery(callDelivery.id(), "retry", "", "temporary", START + 10_000);
            service.receiveRecordingReady(List.of(SourceCallReference.fromToken("retry:1"))); service.drain();
            assertEquals(1, service.pendingDeliveries(100).size(), "Readiness must not shorten delivery retry backoff");
            clock.addAndGet(10_000); assertEquals(2, service.pendingDeliveries(100).size());
            service.completeDelivery(callDelivery.id(), "push_accepted", "accepted", "", 0);
            service.receiveRecordingReady(List.of(SourceCallReference.fromToken("retry:1"))); service.drain();
            assertEquals("push_accepted", service.delivery(callDelivery.id()).orElseThrow().state());
            assertEquals(1, service.pendingDeliveries(100).size(), "Readiness cannot reopen an accepted delivery");
        }
    }

    @Test void deadlineSurvivesRestartAndConfigurationStillControlsEligibility() throws Exception
    {
        AtomicLong clock = new AtomicLong(START);
        Path database = directory.resolve("restart.sqlite"); SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        var store = new TopicAlertStore(database);
        try(var service = new TopicAlertService(store, clock::get))
        {
            configure(service, "proxy", "call");
            observe(service, "call", 1, "restart:1"); observe(service, "call", 2, "restart:2"); service.drain();
            assertTrue(service.pendingDeliveries(100).isEmpty());
        }
        try(var service = new TopicAlertService(new TopicAlertStore(database), clock::get))
        {
            assertTrue(service.pendingDeliveries(100).isEmpty());
            service.saveTopic(new AlertTopic("fire", "Fire", "", true, false, false), service.configuration().revision());
            assertTrue(service.receiveRecordingReady(List.of(SourceCallReference.fromToken("restart:1")))); service.drain();
            assertTrue(service.pendingDeliveries(100).isEmpty(), "Readiness cannot enable a disabled topic");
            service.saveTopic(new AlertTopic("fire", "Fire", "", true, true, false), service.configuration().revision());
            assertEquals(1, service.pendingDeliveries(100).size());
            clock.addAndGet(1999); assertEquals(1, service.pendingDeliveries(100).size());
            clock.incrementAndGet(); assertEquals(2, service.pendingDeliveries(100).size(), "The second call's original fallback deadline survives restart");
        }
        assertEquals(2, store.pendingDeliveries(100, START + 2000).size());
    }

    @Test void readinessCallbacksRemainBoundedUnderSlowStoreQueuePressureAndShutdown() throws Exception
    {
        AtomicLong clock = new AtomicLong(START);
        Path database = directory.resolve("pressure.sqlite"); SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        var store = new TopicAlertStore(database);
        var held = new CountDownLatch(1); var release = new CountDownLatch(1);
        try(var service = new TopicAlertService(store, clock::get))
        {
            configure(service, "proxy", "call");
            Thread blocker = Thread.ofPlatform().start(() -> {
                synchronized(store)
                {
                    held.countDown();
                    try { release.await(5, TimeUnit.SECONDS); } catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                }
            });
            assertTrue(held.await(1, TimeUnit.SECONDS));
            try
            {
                assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
                    assertTrue(service.receiveRecordingReady(List.of(SourceCallReference.fromToken("slow:1"))));
                    service.receiveCompletedCall(call(1, "slow:1", CallEncryptionState.CLEAR));
                });
            }
            finally { release.countDown(); blocker.join(1000); }
            service.drain(); assertEquals(1, service.pendingDeliveries(100).size());
            for(int index = 0; index < TopicAlertService.INGRESS_CAPACITY; index++)
                assertTrue(service.observe(new AlertEvent("unused:" + index, START, "paging", Map.of())));
            assertFalse(service.receiveRecordingReady(List.of(SourceCallReference.fromToken("full:1"))));
            assertEquals(1, service.droppedObservations());
            assertFalse(service.receiveRecordingReady(java.util.Collections.nCopies(129, "x")));
            service.close();
            assertFalse(service.receiveRecordingReady(List.of(SourceCallReference.fromToken("closed:1"))));
        }
    }

    private static DecodeEventViewService.EventView view(String id, String type, List<String> dcs)
    {
        var facts = new DecodeEventViewService.AlertFacts(List.of(), List.of(), dcs, null, null, null, "P25", "voice:1");
        return new DecodeEventViewService.EventView(id, CHANNEL, START - 20_000, 20_000, type,
            "Call", "calls", "", "", "", "", "", null, null, "", "APCO25", List.of(), List.of(),
            0, "Dispatch", null, null, null, facts);
    }

    static CompletedAudioCall call(int sequence, String token, CallEncryptionState encryption)
    {
        var source = new CallLegSource(DecoderType.P25_PHASE1, CHANNEL, "Dispatch", null, 0, null, null,
            ChannelKind.CONVENTIONAL, false, null);
        var snapshot = new AudioCallSnapshot(new AudioCallId(5, sequence, 1), null, null,
            new IdentifierCollection(), Set.of(), START - 20_000, START, 1, 0, START - 20_000, START, false, true,
            encryption, false, null, VoiceCallQuality.EMPTY, new CallLegId(5, sequence, 1), source, null, null, token);
        return new CompletedAudioCall(snapshot, List.of());
    }

    private static void observe(TopicAlertService service, String trigger, int sequence, String token)
    {
        var completed = call(sequence, token, CallEncryptionState.CLEAR);
        if(trigger.equals("call")) service.receiveCompletedCall(completed);
        else service.receiveCompletedToneSequence(new CompletedToneSequence(completed.snapshot(), START,
            CompletedToneSequence.Source.DECODED_AUDIO, CompletedToneSequence.Method.P25_PCM_AMBE_TONE_V2,
            List.of(new CompletedToneSequence.Element(List.of(437500), null, 0L, 500, .99))));
    }
    private TopicAlertService configured(AtomicLong clock, String mode, String trigger) throws Exception
    {
        Path database = directory.resolve("alerts-" + System.nanoTime() + ".sqlite"); SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        var service = new TopicAlertService(new TopicAlertStore(database), clock::get); configure(service, mode, trigger); return service;
    }
    private static void configure(TopicAlertService service, String mode, String trigger) throws Exception
    {
        service.updateConfiguration(new TopicAlertConfiguration(1, new TopicAlertConfiguration.PushSettings(true, mode, "", "VCE test"),
            List.of(AlertTopic.administratorAlerts(), new AlertTopic("fire", "Fire", "", true, true, false)), List.of(rule(trigger, trigger))), 1);
        service.saveDevice(new AlertDevice("phone", "Phone", mode, mode.equals("proxy") ? "remote-phone" : "",
            mode.equals("direct") ? "https://push.example.test/device" : "", "key", "auth", "a".repeat(64), List.of("fire"), false));
    }
    private static AlertRule rule(String id, String trigger)
    {
        return new AlertRule(id, id, "fire", true, trigger, "", "", "", "", AlertRule.Condition.all(), null, null,
            0, "rule", false, "Alert", "Alert", "details");
    }
}
