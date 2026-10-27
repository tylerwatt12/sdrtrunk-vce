/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats.health;

import io.github.dsheirer.dsp.filter.channelizer.PolyphaseChannelManager;
import io.github.dsheirer.source.tuner.LoggingTunerErrorListener;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredUSBTuner;
import io.github.dsheirer.source.tuner.manager.TestPolyphaseChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.TunerStatus;
import io.github.dsheirer.source.tuner.test.TestTuner;
import io.github.dsheirer.web.tuner.TunerAdministrationService.ReceiverReference;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReceiverHealthNicknameTest
{
    @Test void renameAndClearUpdateCurrentLabelsWithoutResettingNativeScopesCountersOrIncidents() throws Exception
    {
        HealthReceiver first = new HealthReceiver("1");
        HealthReceiver second = new HealthReceiver("2");
        AtomicReference<Map<String,ReceiverReference>> names = new AtomicReference<>(Map.of(
            first.getId(), new ReceiverReference("first", "Desk antenna"),
            second.getId(), new ReceiverReference("second", "Spare antenna")));
        AtomicInteger reads = new AtomicInteger();
        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, () -> 1_000L))
        {
            service.setReceiverReferences(() -> { reads.incrementAndGet(); return names.get(); });
            ReceiverHealthIncidentTracker incidents = field(service, "mIncidents");
            List<Map<String,Object>> original = sample(service, incidents, 1_000, first, second);
            assertEquals(1, reads.get(), "A whole health sample uses one cached label snapshot");
            assertEquals(0, first.hardware.preferredReads);
            assertEquals(0, second.hardware.preferredReads);
            assertEquals("Desk antenna", measurement(original, "tuners", first.getId()).get("label"));
            assertTrue(String.valueOf(measurement(original, "tuners", first.getId()).get("detail"))
                .contains("Serial hardware-serial"));
            assertTrue(String.valueOf(measurement(original, "usb", first.getId()).get("detail"))
                .startsWith("Desk antenna"));
            assertTrue(String.valueOf(measurement(original, "receiver-queues", first.getId()).get("label"))
                .startsWith("Desk antenna"));
            Map<String,Object> ownerMap = field(service, "mCounterOwners");
            Object firstOwner = ownerMap.get(first.getId());
            Set<String> baselineKeys = Set.copyOf(ReceiverHealthNicknameTest.<Map<String,Object>>field(
                service, "mCounterBaselines").keySet());
            Object occurrence = incidents.active().getFirst().get("occurrence_id");

            names.set(Map.of(first.getId(), new ReceiverReference("first", "Roof antenna"),
                second.getId(), new ReceiverReference("second", "Spare antenna")));
            var renamed = sample(service, incidents, 2_000, first, second);
            assertEquals("Roof antenna", measurement(renamed, "tuners", first.getId()).get("label"));
            assertEquals(occurrence, incidents.active().getFirst().get("occurrence_id"));
            assertEquals("Roof antenna", incidents.active().getFirst().get("scope"));
            assertEquals(firstOwner, ownerMap.get(first.getId()));
            assertEquals(baselineKeys, ReceiverHealthNicknameTest.<Map<String,Object>>field(
                service, "mCounterBaselines").keySet());

            names.set(Map.of(second.getId(), new ReceiverReference("second", "Spare antenna")));
            var cleared = sample(service, incidents, 3_000, first, second);
            assertEquals("Hardware receiver", measurement(cleared, "tuners", first.getId()).get("label"));
            assertEquals(1, first.hardware.preferredReads);
            assertEquals("Spare antenna", measurement(cleared, "tuners", second.getId()).get("label"));
            assertEquals(3, reads.get());
            assertEquals(occurrence, incidents.active().getFirst().get("occurrence_id"));
            assertEquals("Hardware receiver", incidents.active().getFirst().get("scope"));
            assertEquals("Desk antenna", measurement(original, "tuners", first.getId()).get("label"),
                "Previously captured diagnostic evidence keeps its original label");
        }
    }

    @Test void unavailableNicknameSnapshotLeavesHardwareHealthVisible() throws Exception
    {
        HealthReceiver receiver = new HealthReceiver("3");
        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, () -> 1_000L))
        {
            service.setReceiverReferences(() -> { throw new IllegalStateException("labels unavailable"); });
            ReceiverHealthIncidentTracker incidents = field(service, "mIncidents");
            var measurements = sample(service, incidents, 1_000, receiver);
            assertEquals("Hardware receiver", measurement(measurements, "tuners", receiver.getId()).get("label"));
            assertEquals("critical", measurement(measurements, "tuners", receiver.getId()).get("severity"));
            assertFalse(incidents.active().isEmpty());
            service.setReceiverReferences(() -> null);
            assertEquals("Hardware receiver", measurement(sample(service, incidents, 2_000, receiver),
                "tuners", receiver.getId()).get("label"));
        }
    }

    private static List<Map<String,Object>> sample(ReceiverHealthService service,
        ReceiverHealthIncidentTracker incidents, long now, DiscoveredTuner... receivers)
    {
        List<Map<String,Object>> measurements = new ArrayList<>();
        incidents.beginSample(TimeUnit.MILLISECONDS.toNanos(now));
        service.collectTuners(now, measurements, null, List.of(receivers));
        incidents.endSample(now);
        return measurements;
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> measurement(List<Map<String,Object>> measurements, String section, String scope)
    {
        return measurements.stream().filter(value -> section.equals(value.get("id")))
            .flatMap(value -> ((List<Map<String,Object>>)value.get("rows")).stream())
            .filter(value -> scope.equals(value.get("scope"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object owner, String name) throws Exception
    {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T)field.get(owner);
    }

    private static final class HealthReceiver extends DiscoveredUSBTuner
    {
        private final HealthTuner hardware = new HealthTuner();

        private HealthReceiver(String port)
        {
            super(TunerClass.TEST_TUNER, 1, port);
            mTuner = hardware;
        }

        @Override public TunerStatus getTunerStatus() { return TunerStatus.ERROR; }
    }

    private static final class HealthTuner extends TestTuner
    {
        private int preferredReads;

        private HealthTuner()
        {
            super(new LoggingTunerErrorListener());
            setChannelSourceManager(new TestPolyphaseChannelSourceManager(getTunerController())
            {
                @Override public int getTunerChannelCount() { return 1; }
                @Override public PolyphaseChannelManager.NativeBufferQueueStatus getNativeBufferQueueStatus()
                {
                    return new PolyphaseChannelManager.NativeBufferQueueStatus(1_000, 1_000,
                        0, 0, 0, 0, 0, 0, 0);
                }
                @Override public PolyphaseChannelManager.PipelineStatus getPipelineStatus()
                {
                    return new PolyphaseChannelManager.PipelineStatus(0, 0, 8, 0, 0, List.of());
                }
            });
        }

        @Override public String getPreferredName() { preferredReads++; return "Hardware receiver"; }
        @Override public String getUniqueID() { return "hardware-serial"; }
    }
}
