/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.debug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.channel.quality.ControlChannelQualityRegistry;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueMetricsSnapshot.ChannelQueueMetrics;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueMetricsSnapshot.NativeBufferMetrics;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueMetricsSnapshot.QueueMetrics;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class DebugHarnessTelemetryTest
{
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void failedDeadlockProbeIsContainedAndRateLimited() throws Exception
    {
        AtomicLong now = new AtomicLong(100_000L);
        AtomicInteger probes = new AtomicInteger();
        DebugHarnessTelemetry telemetry = telemetry(List::of, null, now, () -> {
            probes.incrementAndGet();
            throw new UnsupportedOperationException("not supported");
        });

        telemetry.sample();
        JsonNode first = json(telemetry);
        assertEquals("ok", first.at("/telemetry/state").asText());
        assertEquals("unsupported", first.at("/process/deadlock_probe_state").asText());
        assertEquals(1, probes.get());

        now.addAndGet(1_000L);
        telemetry.sample();
        JsonNode second = json(telemetry);
        assertEquals("ok", second.at("/telemetry/state").asText());
        assertEquals("unsupported", second.at("/process/deadlock_probe_state").asText());
        assertEquals(1, probes.get(), "an unsupported global probe must not be retried every second");
    }

    @Test
    void samplingFailurePublishesVisibleStaleStateAndRecovers() throws Exception
    {
        AtomicLong now = new AtomicLong(200_000L);
        AtomicInteger tunerReads = new AtomicInteger();
        Supplier<List<DiscoveredTuner>> tuners = () -> {
            if(tunerReads.incrementAndGet() == 2)
            {
                throw new IllegalStateException("injected collection failure");
            }

            return List.of();
        };
        DebugHarnessTelemetry telemetry = telemetry(tuners, null, now, () -> null);

        telemetry.sample();
        JsonNode success = json(telemetry);
        assertEquals("ok", success.at("/telemetry/state").asText());
        long lastSuccess = success.path("observed_at_ms").asLong();

        now.addAndGet(1_000L);
        telemetry.sample();
        JsonNode failed = json(telemetry);
        assertEquals("sample_failed", failed.at("/telemetry/state").asText());
        assertTrue(failed.at("/telemetry/stale").asBoolean());
        assertEquals(lastSuccess, failed.at("/telemetry/last_success_ms").asLong());
        assertEquals(1_000L, failed.at("/telemetry/stale_age_ms").asLong());
        assertEquals(1L, failed.at("/telemetry/total_failures").asLong());
        assertEquals("IllegalStateException", failed.at("/telemetry/last_failure_type").asText());

        now.addAndGet(1_000L);
        telemetry.sample();
        JsonNode recovered = json(telemetry);
        assertEquals("ok", recovered.at("/telemetry/state").asText());
        assertFalse(recovered.at("/telemetry/stale").asBoolean());
        assertEquals(1L, recovered.at("/telemetry/total_failures").asLong());
        assertEquals(0L, recovered.at("/telemetry/consecutive_failures").asLong());
        assertEquals("IllegalStateException", recovered.at("/telemetry/last_failure_type").asText());
    }

    @Test
    void qualityAndChannelOutputRowsAreBounded() throws Exception
    {
        AtomicLong now = new AtomicLong(300_000L);
        ControlChannelQualityRegistry registry = new ControlChannelQualityRegistry();

        for(int x = 0; x < 30; x++)
        {
            registry.receive(new ControlChannelQualitySnapshot(null, String.format("site-%02d", x),
                851_000_000L + x * 12_500L, now.get() - 1_000L, true, -45.0d, -46.0d, -50.0d,
                -40.0d, 98.5d, 100L, 1L, 2L, 3L, 4L, now.get() - 2_000L));
        }

        DebugHarnessTelemetry telemetry = telemetry(List::of, registry, now, () -> null);
        telemetry.sample();
        JsonNode control = json(telemetry).path("control_channels");
        assertEquals(30, control.path("total_count").asInt());
        assertEquals(DebugHarnessTelemetry.CONTROL_CHANNEL_LIMIT, control.path("rows").size());
        assertEquals(6, control.path("hidden_count").asInt());
        assertEquals(2_000L, control.path("rows").get(0).path("last_valid_decode_age_ms").asLong());
        assertFalse(control.path("rows").get(0).has("channel"));

        Map<String,Object> outputs = DebugHarnessTelemetry.channelOutputs(channels(30));
        assertEquals(30, outputs.get("total_count"));
        assertEquals(DebugHarnessTelemetry.CHANNEL_OUTPUT_LIMIT, ((List<?>)outputs.get("rows")).size());
        assertEquals(6, outputs.get("hidden_count"));
        assertEquals(6, ((Map<?,?>)outputs.get("hidden_summary")).get("count"));
    }

    @Test
    void queueProjectionExportsRemoteMeaningfulAgesAndFullRawAccounting()
    {
        long captured = 10_000_000_000L;
        NativeBufferMetrics raw = new NativeBufferMetrics("raw", 10_000_000.0, 100, 1_000_000,
            2, 131_072, 13, 1, 65_536, 7, 4, 262_144, 27,
            100, 6_553_600, 655, 95, 6_225_920, 622, 3, 196_608, 20,
            2, 131_072, 13, captured - 2_000_000L, captured - 5_000_000L,
            captured - 7_000_000L, true, false);
        Map<String,Object> rawJson = DebugHarnessTelemetry.nativeMetrics(raw, captured);
        assertEquals(655L, rawJson.get("received_ms"));
        assertEquals(622L, rawJson.get("processed_ms"));
        assertEquals(131_072L, rawJson.get("cleanup_samples"));
        assertEquals(13L, rawJson.get("cleanup_ms"));
        assertEquals(2L, rawJson.get("last_ingress_age_ms"));
        assertEquals(5L, rawJson.get("last_completion_age_ms"));
        assertEquals(7L, rawJson.get("active_age_ms"));
        assertTrue(rawJson.keySet().stream().noneMatch(key -> key.endsWith("_nanos")));

        QueueMetrics queue = new QueueMetrics("output", 8, 2, 1, true, 100, 100, 97, 1, 2, 5,
            captured - 3_000_000L, captured - 6_000_000L, captured - 8_000_000L, captured, true);
        Map<String,Object> queueJson = DebugHarnessTelemetry.queueMetrics(queue);
        assertEquals(3L, queueJson.get("last_ingress_age_ms"));
        assertEquals(6L, queueJson.get("last_completion_age_ms"));
        assertEquals(8L, queueJson.get("callback_active_age_ms"));
        assertTrue(queueJson.keySet().stream().noneMatch(key -> key.endsWith("_nanos")));
        assertNull(DebugHarnessTelemetry.monotonicAgeMilliseconds(captured, 0));
    }

    private static DebugHarnessTelemetry telemetry(Supplier<List<DiscoveredTuner>> tuners,
                                                    ControlChannelQualityRegistry registry, AtomicLong now,
                                                    Supplier<long[]> deadlockProbe)
    {
        return new DebugHarnessTelemetry(tuners, registry, now::get, () -> 10_000_000_000L,
            ManagementFactory.getThreadMXBean(), deadlockProbe);
    }

    private static JsonNode json(DebugHarnessTelemetry telemetry) throws Exception
    {
        return OBJECT_MAPPER.readTree(telemetry.getCachedJson());
    }

    private static List<ChannelQueueMetrics> channels(int count)
    {
        List<ChannelQueueMetrics> channels = new ArrayList<>();
        long captured = 10_000_000_000L;

        for(int x = 0; x < count; x++)
        {
            QueueMetrics metrics = new QueueMetrics("output-" + x, 8, x % 3, x % 2,
                x % 2 == 1, 100 + x, 100 + x, 99 + x, x, x * 2L, 4,
                captured - 1_000_000L, captured - 2_000_000L, captured - 3_000_000L,
                captured, true);
            channels.add(new ChannelQueueMetrics(770_000_000L + x * 12_500L, 50_000.0, metrics));
        }

        return channels;
    }
}
