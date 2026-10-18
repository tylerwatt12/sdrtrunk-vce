/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.alerts.*;
import io.github.dsheirer.audio.call.*;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AlertRecordingReadinessIntegrationTest
{
    @TempDir Path directory;
    private static final String CHANNEL = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";

    @Test void firstEligibleProxyAndDirectCallAndToneDeliveriesAlreadyHaveExactPlayableRelatedRecordingWithoutActivity() throws Exception
    {
        for(String mode: List.of("proxy", "direct"))
        {
            for(String trigger: List.of("call", "tone"))
            {
                Path root = Files.createDirectory(directory.resolve(mode + "-" + trigger));
                Path global = root.resolve("application.sqlite");
                SdrTrunkDatabaseStartup.createGlobalDatabase(global);
                try(var catalog = new ManagedRecordingCatalog(root.resolve("recordings.sqlite"), root);
                    var alerts = new TopicAlertService(global);
                    var subscription = catalog.addRecordingReadyListener(ready ->
                        alerts.receiveRecordingReady(ready.sourceCallReferences())))
                {
                    configure(alerts, mode, trigger);
                    CountDownLatch projected = projectionSignal(alerts, trigger);
                    var call = call(mode + ":" + trigger);
                    alerts.receiveCompletedCall(call);
                    if(trigger.equals("tone")) alerts.receiveCompletedToneSequence(tones(call));
                    assertTrue(projected.await(2, TimeUnit.SECONDS));
                    Thread.sleep(50);
                    assertTrue(alerts.pendingDeliveries(10).isEmpty(), "The notification cannot overtake its recording");

                    Path audio = root.resolve("dispatch.mp3");
                    Files.write(audio, new byte[]{'I', 'D', '3', 0});
                    assertTrue(catalog.submit(audio, call, Files.size(audio), null));
                    await(() -> eligible(alerts), 2000);
                    AlertDelivery delivery = alerts.pendingDeliveries(10).getFirst();
                    assertEquals(mode, delivery.method());
                    assertEquals(trigger, alerts.historyItem(delivery.alertId()).orElseThrow().get("event_type"));
                    String source = alerts.sourceCallUuid(delivery.alertId()).orElseThrow();
                    assertEquals(SourceCallReference.fromToken(mode + ":" + trigger), source);
                    var relations = relations(catalog, alerts);
                    var result = relations.resolve(source, false, true);
                    assertTrue(rows(result, "activity_rows").isEmpty(), "Activity collection is independent");
                    assertEquals(1, rows(result, "recordings").size(), "The first notification sees the committed exact association");
                    var recording = rows(result, "recordings").getFirst();
                    long id = ((Number)recording.get("id")).longValue();
                    assertEquals("/api/v1/recordings/calls/" + id + "/audio", recording.get("audio_url"));
                    assertEquals(audio, catalog.audioPath(id));
                    assertTrue(Files.isRegularFile(catalog.audioPath(id)));
                    assertEquals(1, rows(result, "alerts").size());
                }
            }
        }
    }

    @Test void unrelatedRecordingDoesNotReleaseCallButOtherTriggerIsImmediateAndTimeoutStillSendsWithoutAudio() throws Exception
    {
        Path global = directory.resolve("timeout-application.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(global);
        try(var catalog = new ManagedRecordingCatalog(directory.resolve("timeout-recordings.sqlite"), directory);
            var alerts = new TopicAlertService(global);
            var subscription = catalog.addRecordingReadyListener(ready ->
                alerts.receiveRecordingReady(ready.sourceCallReferences())))
        {
            configure(alerts, "proxy", "call");
            alerts.saveRule(rule("other", "event"), alerts.configuration().revision());
            CountDownLatch projected = projectionSignal(alerts, "call");
            var call = call("waiting:call");
            alerts.receiveCompletedCall(call);
            assertTrue(projected.await(2, TimeUnit.SECONDS));

            CountDownLatch indexed = new CountDownLatch(1);
            Path unrelatedAudio = directory.resolve("unrelated.mp3");
            Files.write(unrelatedAudio, new byte[]{'I', 'D', '3', 0});
            assertTrue(catalog.submit(unrelatedAudio, call("different:call"), Files.size(unrelatedAudio), indexed::countDown));
            assertTrue(indexed.await(2, TimeUnit.SECONDS));
            assertTrue(alerts.pendingDeliveries(10).isEmpty(), "A different exact source cannot release this call");

            alerts.observe(new AlertEvent("other-event", System.currentTimeMillis(), "event", Map.of(),
                AlertAction.Context.EMPTY, SourceCallReference.fromToken("other:event")));
            await(() -> eligible(alerts), 1000);
            AlertDelivery immediate = alerts.pendingDeliveries(10).getFirst();
            assertEquals("event", alerts.historyItem(immediate.alertId()).orElseThrow().get("event_type"));
            alerts.completeDelivery(immediate.id(), "push_accepted", "", "", 0);

            await(() -> eligible(alerts), 3500);
            AlertDelivery timedOut = alerts.pendingDeliveries(10).getFirst();
            assertEquals("call", alerts.historyItem(timedOut.alertId()).orElseThrow().get("event_type"));
            var related = relations(catalog, alerts).resolve(alerts.sourceCallUuid(timedOut.alertId()).orElseThrow(), false, true);
            assertTrue(rows(related, "recordings").isEmpty(), "Timeout sends the alert without borrowing another recording");
            assertTrue(Files.exists(unrelatedAudio));
        }
    }

    private static CountDownLatch projectionSignal(TopicAlertService alerts, String trigger)
    {
        var projected = new CountDownLatch(1);
        alerts.setCallProjector(call -> {
            var view = DecodeEventViewService.completedCallView(call, null);
            if(trigger.equals("call")) projected.countDown();
            return view;
        });
        alerts.setToneProjector(sequence -> {
            var view = DecodeEventViewService.completedToneView(sequence, null);
            if(trigger.equals("tone")) projected.countDown();
            return view;
        });
        return projected;
    }

    private static void configure(TopicAlertService alerts, String mode, String trigger) throws Exception
    {
        var config = alerts.configuration();
        alerts.updateConfiguration(new TopicAlertConfiguration(config.revision(),
            new TopicAlertConfiguration.PushSettings(true, mode, mode.equals("direct") ? "https://receiver.example.com" : "", "Receiver"),
            List.of(AlertTopic.administratorAlerts(), new AlertTopic("fire", "Fire", "", true, true, false)),
            List.of(rule("ready", trigger))), config.revision());
        alerts.saveDevice(new AlertDevice("phone", "Phone", mode, mode.equals("proxy") ? "remote-phone" : "",
            mode.equals("direct") ? "https://fcm.googleapis.com/synthetic" : "", "key", "auth", "a".repeat(64),
            List.of("fire"), false));
    }

    private static AlertRule rule(String id, String trigger)
    {
        return new AlertRule(id, "Dispatch " + trigger, "fire", true, trigger, "", "", "", "", "",
            null, null, null, 0, "rule", false, "Dispatch", "Dispatch activity", "details", true);
    }

    private static CompletedAudioCall call(String token)
    {
        long now = System.currentTimeMillis();
        var source = new CallLegSource(DecoderType.NBFM, CHANNEL, "Dispatch", null, 0, null, null,
            ChannelKind.CONVENTIONAL, false);
        var id = new AudioCallId(7, Math.abs((long)token.hashCode()) + 1, 0);
        var snapshot = new AudioCallSnapshot(id, null, null, new IdentifierCollection(), Set.of(), now - 1000, now,
            1, 0, now - 1000, now, false, true, CallEncryptionState.CLEAR, true, null, VoiceCallQuality.EMPTY,
            CallLegId.from(id), source, null, null, token);
        return new CompletedAudioCall(snapshot, List.of(new float[160]));
    }

    private static CompletedToneSequence tones(CompletedAudioCall call)
    {
        return new CompletedToneSequence(call.nativeAudioSnapshot(), call.snapshot().startTimestamp(),
            CompletedToneSequence.Source.DECODED_AUDIO, CompletedToneSequence.Method.ANALOG_PCM_TONE_V1,
            List.of(new CompletedToneSequence.Element(List.of(510500), null, 0L, 500, .99)));
    }

    private static CallRelationsService relations(ManagedRecordingCatalog catalog, TopicAlertService alerts)
    {
        return new CallRelationsService(new CallRelationsService.ActivityLookup()
        {
            public String source(long id) { fail("No Activity lookup is required"); return null; }
            public List<Map<String,Object>> find(List<String> sources) { fail("Activity is disabled"); return List.of(); }
        }, () -> catalog, alerts::historyBySourceCallReferences, rows -> rows);
    }

    private static boolean eligible(TopicAlertService alerts)
    {
        try { return !alerts.pendingDeliveries(10).isEmpty(); }
        catch(Exception failure) { throw new AssertionError(failure); }
    }

    private static void await(BooleanSupplier condition, long milliseconds) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(milliseconds);
        while(!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "The expected delivery became eligible within its bounded wait");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Map<String,Object> result, String key)
    { return (List<Map<String,Object>>)result.get(key); }
}
