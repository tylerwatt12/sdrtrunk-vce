/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.debug;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.application.ApplicationInfo;
import io.github.dsheirer.channel.quality.ControlChannelQualityRegistry;
import io.github.dsheirer.channel.quality.ControlChannelQualityRegistry.DiagnosticQualityBatch;
import io.github.dsheirer.channel.quality.ControlChannelQualityRegistry.DiagnosticQualitySnapshot;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueMetricsSnapshot;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueMetricsSnapshot.ChannelQueueMetrics;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueMetricsSnapshot.NativeBufferMetrics;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueMetricsSnapshot.QueueMetrics;
import io.github.dsheirer.dsp.filter.channelizer.ReceiverQueueProfile;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.manager.ChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.PolyphaseChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.lang.management.OperatingSystemMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Produces one bounded, pre-serialized receiver snapshot per sampling interval. HTTP clients only copy the cached byte
 * array, so additional clients cannot add tuner/channelizer polling, JSON projection, or work to receiver threads.
 * Receiver queue values come from the existing lock-free counter snapshots; this class never attaches an IQ, FFT,
 * symbol, message, or audio listener.
 */
final class DebugHarnessTelemetry
{
    static final int TUNER_LIMIT = 16;
    static final int CHANNEL_OUTPUT_LIMIT = 24;
    static final int CONTROL_CHANNEL_LIMIT = 24;
    static final long EXPECTED_SAMPLE_INTERVAL_MILLISECONDS = 1_000L;
    private static final long DEADLOCK_SAMPLE_INTERVAL_MILLISECONDS = 30_000L;
    private static final String SOURCE_BASELINE = "v0.6.2-alpha-9 (d89a2da8f)";
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final Supplier<List<DiscoveredTuner>> mTunerSupplier;
    private final ControlChannelQualityRegistry mQualityRegistry;
    private final LongSupplier mWallClock;
    private final LongSupplier mNanoClock;
    private final ThreadMXBean mThreadBean;
    private final MemoryMXBean mMemoryBean = ManagementFactory.getMemoryMXBean();
    private final RuntimeMXBean mRuntimeBean = ManagementFactory.getRuntimeMXBean();
    private final OperatingSystemMXBean mOperatingSystemBean = ManagementFactory.getOperatingSystemMXBean();
    private final List<GarbageCollectorMXBean> mGarbageCollectors =
        List.copyOf(ManagementFactory.getGarbageCollectorMXBeans());
    private final Supplier<long[]> mDeadlockProbe;
    private final Map<String,GcCounters> mPreviousGarbageCollectorCounters = new LinkedHashMap<>();
    private final AtomicReference<byte[]> mCachedJson;
    private final AtomicLong mSequence = new AtomicLong();
    private final String mBootId = UUID.randomUUID().toString();

    private long mLastSuccessMilliseconds;
    private long mLastFailureMilliseconds;
    private long mTotalFailures;
    private long mConsecutiveFailures;
    private String mLastFailureType;
    private long mLastDeadlockProbeMilliseconds;
    private Integer mDeadlockedThreadCount;
    private String mDeadlockProbeState = "not_yet_sampled";
    private String mDeadlockProbeError;

    DebugHarnessTelemetry(TunerManager tunerManager, ControlChannelQualityRegistry qualityRegistry)
    {
        this(tunerManager != null ?
                () -> tunerManager.getDiscoveredTunerModel().getDiscoveredTuners() : List::of,
            qualityRegistry, System::currentTimeMillis, System::nanoTime, ManagementFactory.getThreadMXBean(), null);
    }

    /** Test seam for clocks, tuner discovery, and the relatively expensive deadlock probe. */
    DebugHarnessTelemetry(Supplier<List<DiscoveredTuner>> tunerSupplier,
                          ControlChannelQualityRegistry qualityRegistry, LongSupplier wallClock,
                          LongSupplier nanoClock, ThreadMXBean threadBean, Supplier<long[]> deadlockProbe)
    {
        mTunerSupplier = Objects.requireNonNullElse(tunerSupplier, List::of);
        mQualityRegistry = qualityRegistry;
        mWallClock = Objects.requireNonNullElse(wallClock, System::currentTimeMillis);
        mNanoClock = Objects.requireNonNullElse(nanoClock, System::nanoTime);
        mThreadBean = Objects.requireNonNullElseGet(threadBean, ManagementFactory::getThreadMXBean);
        mDeadlockProbe = deadlockProbe != null ? deadlockProbe : mThreadBean::findDeadlockedThreads;
        long createdAt = safeWallClock();
        mCachedJson = new AtomicReference<>(failurePayload(0, createdAt, "starting", null));
    }

    /** Returns the same immutable-by-convention cached byte array; this performs no collection or serialization. */
    byte[] getCachedJson()
    {
        return mCachedJson.get();
    }

    /**
     * Collects and serializes one snapshot. This is called by exactly one low-priority sampler, never by an HTTP
     * request thread. A failed collection publishes a small stale-state payload instead of silently freezing the last
     * successful timestamp.
     */
    synchronized void sample()
    {
        long sequence = mSequence.incrementAndGet();
        long startedNanos = mNanoClock.getAsLong();
        long now = safeWallClock();

        try
        {
            Map<String,Object> root = new LinkedHashMap<>();
            root.put("schema_version", 1);
            root.put("sequence", sequence);
            root.put("observed_at_ms", now);
            root.put("observed_at", Instant.ofEpochMilli(now).toString());
            root.put("telemetry", telemetryState("ok", false, now, 0));
            root.put("application", application());
            root.put("process", process(now));
            root.put("tuners", tuners());
            root.put("control_channels", controlChannels(now));
            //Projection/collection time intentionally stops before serialization; the mapper cannot modify receiver
            //state and the next sample is still isolated on this same single diagnostics worker.
            root.put("projection_duration_us", elapsedMicroseconds(startedNanos, mNanoClock.getAsLong()));
            byte[] json = OBJECT_MAPPER.writeValueAsBytes(root);
            mCachedJson.set(json);
            mLastSuccessMilliseconds = now;
            mConsecutiveFailures = 0;
        }
        catch(RuntimeException | IOException e)
        {
            mTotalFailures++;
            mConsecutiveFailures++;
            mLastFailureMilliseconds = now;
            mLastFailureType = e.getClass().getSimpleName();
            mCachedJson.set(failurePayload(sequence, now, "sample_failed", mLastFailureType));
        }
    }

    private long safeWallClock()
    {
        try
        {
            return mWallClock.getAsLong();
        }
        catch(RuntimeException e)
        {
            return System.currentTimeMillis();
        }
    }

    private Map<String,Object> telemetryState(String state, boolean stale, long now, long staleAgeMilliseconds)
    {
        Map<String,Object> telemetry = new LinkedHashMap<>();
        telemetry.put("boot_id", mBootId);
        telemetry.put("state", state);
        telemetry.put("stale", stale);
        telemetry.put("expected_sample_interval_ms", EXPECTED_SAMPLE_INTERVAL_MILLISECONDS);
        telemetry.put("last_success_ms", state.equals("ok") ? now : positiveOrNull(mLastSuccessMilliseconds));
        telemetry.put("stale_age_ms", stale ? staleAgeMilliseconds : 0L);
        telemetry.put("total_failures", mTotalFailures);
        telemetry.put("consecutive_failures", state.equals("ok") ? 0L : mConsecutiveFailures);
        telemetry.put("last_failure_ms", positiveOrNull(mLastFailureMilliseconds));
        telemetry.put("last_failure_type", mLastFailureType);
        return telemetry;
    }

    /**
     * Minimal hand-built fallback so even an ObjectMapper failure cannot leave clients with an unexplained frozen
     * success payload. Values interpolated here are either numbers, a UUID, or a Java class simple name.
     */
    private byte[] failurePayload(long sequence, long now, String state, String failureType)
    {
        long staleAge = mLastSuccessMilliseconds > 0 ?
            Math.max(0L, now - mLastSuccessMilliseconds) : 0L;
        StringBuilder sb = new StringBuilder(512);
        sb.append('{')
            .append("\"schema_version\":1,")
            .append("\"sequence\":").append(sequence).append(',')
            .append("\"observed_at_ms\":").append(now).append(',')
            .append("\"telemetry\":{")
            .append("\"boot_id\":\"").append(jsonEscape(mBootId)).append("\",")
            .append("\"state\":\"").append(jsonEscape(state)).append("\",")
            .append("\"stale\":true,")
            .append("\"expected_sample_interval_ms\":").append(EXPECTED_SAMPLE_INTERVAL_MILLISECONDS).append(',')
            .append("\"last_success_ms\":");
        appendNullableNumber(sb, positiveOrNull(mLastSuccessMilliseconds));
        sb.append(',').append("\"stale_age_ms\":").append(staleAge).append(',')
            .append("\"total_failures\":").append(mTotalFailures).append(',')
            .append("\"consecutive_failures\":").append(mConsecutiveFailures).append(',')
            .append("\"last_failure_ms\":");
        appendNullableNumber(sb, positiveOrNull(mLastFailureMilliseconds));
        sb.append(',').append("\"last_failure_type\":");

        if(failureType == null)
        {
            sb.append("null");
        }
        else
        {
            sb.append('\"').append(jsonEscape(failureType)).append('\"');
        }

        sb.append("}}");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static void appendNullableNumber(StringBuilder sb, Long value)
    {
        sb.append(value != null ? value : "null");
    }

    private static String jsonEscape(String value)
    {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static Map<String,Object> application()
    {
        Map<String,Object> application = new LinkedHashMap<>();
        application.put("name", ApplicationInfo.getDisplayName());
        application.put("build_time", ApplicationInfo.getBuildTimestamp());
        application.put("source_baseline", SOURCE_BASELINE);
        application.put("queue_profile", ReceiverQueueProfile.getActive().getPropertyValue());
        return application;
    }

    private Map<String,Object> process(long now)
    {
        MemoryUsage heap = mMemoryBean.getHeapMemoryUsage();
        MemoryUsage nonHeap = mMemoryBean.getNonHeapMemoryUsage();
        Map<String,Object> process = new LinkedHashMap<>();
        process.put("uptime_ms", mRuntimeBean.getUptime());
        process.put("heap_used_bytes", heap.getUsed());
        process.put("heap_committed_bytes", heap.getCommitted());
        process.put("heap_max_bytes", heap.getMax());
        process.put("nonheap_used_bytes", nonHeap.getUsed());
        process.put("nonheap_committed_bytes", nonHeap.getCommitted());
        process.put("thread_count", mThreadBean.getThreadCount());
        process.put("daemon_thread_count", mThreadBean.getDaemonThreadCount());
        process.put("peak_thread_count", mThreadBean.getPeakThreadCount());
        process.put("total_started_thread_count", mThreadBean.getTotalStartedThreadCount());
        updateDeadlockState(now);
        process.put("deadlock_probe_state", mDeadlockProbeState);
        process.put("deadlock_probe_error", mDeadlockProbeError);
        process.put("deadlock_probe_observed_at_ms", positiveOrNull(mLastDeadlockProbeMilliseconds));
        process.put("deadlock_probe_age_ms", wallAgeMilliseconds(now, mLastDeadlockProbeMilliseconds));
        process.put("deadlocked_thread_count", mDeadlockedThreadCount);
        addCpu(process);
        process.put("garbage_collectors", garbageCollectors());
        return process;
    }

    private void updateDeadlockState(long now)
    {
        if(mLastDeadlockProbeMilliseconds > 0 && now >= mLastDeadlockProbeMilliseconds &&
            now - mLastDeadlockProbeMilliseconds < DEADLOCK_SAMPLE_INTERVAL_MILLISECONDS)
        {
            return;
        }

        //Record the attempt before calling the bean so an unsupported/failing probe is not retried every second.
        mLastDeadlockProbeMilliseconds = now;

        try
        {
            long[] deadlocked = mDeadlockProbe.get();
            mDeadlockedThreadCount = deadlocked != null ? deadlocked.length : 0;
            mDeadlockProbeState = "ok";
            mDeadlockProbeError = null;
        }
        catch(UnsupportedOperationException e)
        {
            mDeadlockedThreadCount = null;
            mDeadlockProbeState = "unsupported";
            mDeadlockProbeError = e.getClass().getSimpleName();
        }
        catch(SecurityException e)
        {
            mDeadlockedThreadCount = null;
            mDeadlockProbeState = "denied";
            mDeadlockProbeError = e.getClass().getSimpleName();
        }
        catch(RuntimeException e)
        {
            mDeadlockedThreadCount = null;
            mDeadlockProbeState = "error";
            mDeadlockProbeError = e.getClass().getSimpleName();
        }
    }

    private void addCpu(Map<String,Object> process)
    {
        process.put("available_processors", mOperatingSystemBean.getAvailableProcessors());

        if(mOperatingSystemBean instanceof com.sun.management.OperatingSystemMXBean os)
        {
            try
            {
                process.put("process_cpu_load", finiteNonNegativeOrNull(os.getProcessCpuLoad()));
                long cpuTime = os.getProcessCpuTime();
                process.put("process_cpu_time_ns", cpuTime >= 0 ? cpuTime : null);
                process.put("cpu_probe_error", null);
            }
            catch(RuntimeException e)
            {
                process.put("process_cpu_load", null);
                process.put("process_cpu_time_ns", null);
                process.put("cpu_probe_error", e.getClass().getSimpleName());
            }
        }
        else
        {
            process.put("process_cpu_load", null);
            process.put("process_cpu_time_ns", null);
            process.put("cpu_probe_error", "unsupported");
        }
    }

    private List<Map<String,Object>> garbageCollectors()
    {
        List<Map<String,Object>> collectors = new ArrayList<>(mGarbageCollectors.size());

        for(int x = 0; x < mGarbageCollectors.size(); x++)
        {
            GarbageCollectorMXBean collector = mGarbageCollectors.get(x);
            Map<String,Object> value = new LinkedHashMap<>();
            String name = collector.getName();
            String key = x + "\u0000" + name;
            value.put("name", name);

            try
            {
                long collections = collector.getCollectionCount();
                long collectionTime = collector.getCollectionTime();
                GcCounters previous = mPreviousGarbageCollectorCounters.get(key);
                value.put("collections", supportedCounter(collections));
                value.put("collection_time_ms", supportedCounter(collectionTime));
                value.put("collections_since_previous_sample",
                    delta(collections, previous != null ? previous.collections() : -1L));
                value.put("collection_time_since_previous_sample_ms",
                    delta(collectionTime, previous != null ? previous.collectionTimeMilliseconds() : -1L));
                value.put("probe_error", null);
                mPreviousGarbageCollectorCounters.put(key, new GcCounters(collections, collectionTime));
            }
            catch(RuntimeException e)
            {
                value.put("collections", null);
                value.put("collection_time_ms", null);
                value.put("collections_since_previous_sample", null);
                value.put("collection_time_since_previous_sample_ms", null);
                value.put("probe_error", e.getClass().getSimpleName());
            }

            collectors.add(value);
        }

        return collectors;
    }

    private Map<String,Object> tuners()
    {
        List<DiscoveredTuner> discoveredTuners = mTunerSupplier.get();

        if(discoveredTuners == null)
        {
            discoveredTuners = List.of();
        }

        int totalCount = discoveredTuners.size();
        int rowCount = Math.min(TUNER_LIMIT, totalCount);
        List<Map<String,Object>> rows = new ArrayList<>(rowCount);

        for(int x = 0; x < rowCount; x++)
        {
            rows.add(tuner(discoveredTuners.get(x)));
        }

        Map<String,Object> result = new LinkedHashMap<>();
        result.put("total_count", totalCount);
        result.put("limit", TUNER_LIMIT);
        result.put("hidden_count", Math.max(0, totalCount - rows.size()));
        result.put("rows", rows);
        return result;
    }

    private static Map<String,Object> tuner(DiscoveredTuner discovered)
    {
        Map<String,Object> tuner = new LinkedHashMap<>();
        ReceiverQueueMetricsSnapshot snapshot = null;

        try
        {
            tuner.put("id", discovered.getId());
            tuner.put("status", discovered.getTunerStatus() != null ?
                discovered.getTunerStatus().name().toLowerCase(Locale.ROOT) : null);
            Tuner liveTuner = discovered.getTuner();

            if(liveTuner != null)
            {
                tuner.put("center_frequency_hz", liveTuner.getTunerController().getFrequency());
                tuner.put("sample_rate_hz", liveTuner.getTunerController().getSampleRate());
                ChannelSourceManager sourceManager = liveTuner.getChannelSourceManager();

                if(sourceManager instanceof PolyphaseChannelSourceManager polyphase)
                {
                    snapshot = polyphase.getQueueMetricsSnapshot();
                }
            }
        }
        catch(RuntimeException e)
        {
            tuner.put("lifecycle_race", e.getClass().getSimpleName());
        }

        if(snapshot == null)
        {
            tuner.put("metrics_available", false);
            tuner.put("raw_input", null);
            tuner.put("ifft", null);
            tuner.put("channel_outputs", channelOutputs(List.of()));
        }
        else
        {
            tuner.put("metrics_available", snapshot.rawInput() != null || snapshot.ifft() != null);
            tuner.put("raw_input", nativeMetrics(snapshot.rawInput(), snapshot.capturedNanos()));
            tuner.put("ifft", queueMetrics(snapshot.ifft()));
            tuner.put("channel_outputs", channelOutputs(snapshot.channels()));
        }

        return tuner;
    }

    static Map<String,Object> nativeMetrics(NativeBufferMetrics metrics, long capturedNanos)
    {
        if(metrics == null)
        {
            return null;
        }

        Map<String,Object> value = new LinkedHashMap<>();
        value.put("running", metrics.running());
        value.put("disposed", metrics.disposed());
        value.put("unbounded", metrics.unbounded());
        value.put("sample_rate_hz", metrics.sampleRate());
        value.put("limit_ms", metrics.configuredLimitMilliseconds());
        value.put("limit_samples", metrics.configuredLimitSamples());
        value.put("waiting_buffers", metrics.waitingBuffers());
        value.put("waiting_samples", metrics.waitingSamples());
        value.put("waiting_ms", metrics.waitingMilliseconds());
        value.put("in_flight_buffers", metrics.inFlightBuffers());
        value.put("in_flight_samples", metrics.inFlightSamples());
        value.put("in_flight_ms", metrics.inFlightMilliseconds());
        value.put("retained_buffers", metrics.waitingBuffers() + metrics.inFlightBuffers());
        value.put("retained_samples", metrics.waitingSamples() + metrics.inFlightSamples());
        value.put("retained_ms", metrics.waitingMilliseconds() + metrics.inFlightMilliseconds());
        value.put("high_water_waiting_buffers", metrics.highWaterWaitingBuffers());
        value.put("high_water_waiting_samples", metrics.highWaterWaitingSamples());
        value.put("high_water_waiting_ms", metrics.highWaterWaitingMilliseconds());
        value.put("received_buffers", metrics.receivedBuffers());
        value.put("received_samples", metrics.receivedSamples());
        value.put("received_ms", metrics.receivedMilliseconds());
        value.put("processed_buffers", metrics.processedBuffers());
        value.put("processed_samples", metrics.processedSamples());
        value.put("processed_ms", metrics.processedMilliseconds());
        value.put("dropped_buffers", metrics.droppedBuffers());
        value.put("dropped_samples", metrics.droppedSamples());
        value.put("dropped_ms", metrics.droppedMilliseconds());
        value.put("cleanup_buffers", metrics.cleanupBuffers());
        value.put("cleanup_samples", metrics.cleanupSamples());
        value.put("cleanup_ms", metrics.cleanupMilliseconds());
        value.put("last_ingress_age_ms", monotonicAgeMilliseconds(capturedNanos, metrics.lastIngressNanos()));
        value.put("last_completion_age_ms", monotonicAgeMilliseconds(capturedNanos, metrics.lastCompletionNanos()));
        value.put("active_age_ms", metrics.active() ?
            monotonicAgeMilliseconds(capturedNanos, metrics.activeSinceNanos()) : null);
        return value;
    }

    static Map<String,Object> queueMetrics(QueueMetrics metrics)
    {
        if(metrics == null)
        {
            return null;
        }

        Map<String,Object> value = new LinkedHashMap<>();
        value.put("running", metrics.running());
        value.put("unbounded", metrics.unbounded());
        value.put("limit", metrics.configuredLimit());
        value.put("waiting", metrics.waiting());
        value.put("in_flight", metrics.inFlight());
        value.put("outstanding", metrics.outstanding());
        value.put("callback_active", metrics.callbackActive());
        value.put("received", metrics.received());
        value.put("accepted", metrics.accepted());
        value.put("processed", metrics.processed());
        value.put("dropped", metrics.dropped());
        value.put("discarded", metrics.discarded());
        value.put("high_water_outstanding", metrics.highWaterOutstanding());
        value.put("last_ingress_age_ms",
            monotonicAgeMilliseconds(metrics.capturedNanos(), metrics.lastIngressNanos()));
        value.put("last_completion_age_ms",
            monotonicAgeMilliseconds(metrics.capturedNanos(), metrics.lastCompletionNanos()));
        value.put("callback_active_age_ms", metrics.callbackActive() ?
            monotonicAgeMilliseconds(metrics.capturedNanos(), metrics.activeSinceNanos()) : null);
        return value;
    }

    private static Map<String,Object> channelMetrics(ChannelQueueMetrics channel)
    {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("frequency_hz", channel.requestedFrequency());
        value.put("sample_rate_hz", channel.sampleRate());
        value.put("output", queueMetrics(channel.output()));
        return value;
    }

    static Map<String,Object> channelOutputs(List<ChannelQueueMetrics> channels)
    {
        List<ChannelQueueMetrics> safeChannels = channels != null ? channels : List.of();
        int visibleCount = Math.min(CHANNEL_OUTPUT_LIMIT, safeChannels.size());
        List<ChannelQueueMetrics> visible = new ArrayList<>(safeChannels.subList(0, visibleCount));
        visible.sort(Comparator.comparingLong(ChannelQueueMetrics::requestedFrequency));
        List<Map<String,Object>> rows = visible.stream().map(DebugHarnessTelemetry::channelMetrics).toList();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("total_count", safeChannels.size());
        result.put("limit", CHANNEL_OUTPUT_LIMIT);
        result.put("hidden_count", Math.max(0, safeChannels.size() - rows.size()));
        result.put("rows", rows);
        result.put("hidden_summary", hiddenSummary(safeChannels.subList(visibleCount, safeChannels.size())));
        return result;
    }

    static Map<String,Object> hiddenSummary(List<ChannelQueueMetrics> channels)
    {
        long dropped = 0;
        long discarded = 0;
        int unavailable = 0;
        int maximumOutstanding = 0;
        int maximumHighWater = 0;
        int activeCallbacks = 0;
        Long oldestActiveAgeMilliseconds = null;

        for(ChannelQueueMetrics channel: channels)
        {
            QueueMetrics metrics = channel.output();

            if(metrics == null)
            {
                unavailable++;
                continue;
            }

            dropped += metrics.dropped();
            discarded += metrics.discarded();
            maximumOutstanding = Math.max(maximumOutstanding, metrics.outstanding());
            maximumHighWater = Math.max(maximumHighWater, metrics.highWaterOutstanding());

            if(metrics.callbackActive())
            {
                activeCallbacks++;
                Long activeAge = monotonicAgeMilliseconds(metrics.capturedNanos(), metrics.activeSinceNanos());

                if(activeAge != null)
                {
                    oldestActiveAgeMilliseconds = oldestActiveAgeMilliseconds == null ? activeAge :
                        Math.max(oldestActiveAgeMilliseconds, activeAge);
                }
            }
        }

        Map<String,Object> summary = new LinkedHashMap<>();
        summary.put("count", channels.size());
        summary.put("unavailable", unavailable);
        summary.put("maximum_outstanding", maximumOutstanding);
        summary.put("maximum_high_water", maximumHighWater);
        summary.put("dropped", dropped);
        summary.put("discarded", discarded);
        summary.put("active_callbacks", activeCallbacks);
        summary.put("oldest_active_age_ms", oldestActiveAgeMilliseconds);
        return summary;
    }

    private Map<String,Object> controlChannels(long now)
    {
        DiagnosticQualityBatch batch = mQualityRegistry != null ?
            mQualityRegistry.getDiagnosticSnapshots(CONTROL_CHANNEL_LIMIT) :
            new DiagnosticQualityBatch(0, List.of());
        List<Map<String,Object>> rows = batch.snapshots().stream()
            .map(snapshot -> controlChannel(snapshot, now)).toList();
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("total_count", batch.totalCount());
        result.put("limit", CONTROL_CHANNEL_LIMIT);
        result.put("hidden_count", Math.max(0L, batch.totalCount() - rows.size()));
        result.put("rows", rows);
        return result;
    }

    private static Map<String,Object> controlChannel(DiagnosticQualitySnapshot snapshot, long now)
    {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("site_identity", snapshot.siteIdentity());
        value.put("frequency_hz", snapshot.frequencyHz());
        value.put("active", snapshot.active());
        value.put("signal_dbfs", snapshot.signalDbfs());
        value.put("decode_health_percent", snapshot.decodeHealthPercent());
        value.put("valid_frames", snapshot.validFrames());
        value.put("invalid_frames", snapshot.invalidFrames());
        value.put("corrected_bits", snapshot.correctedBits());
        value.put("sync_loss_bits", snapshot.syncLossBits());
        value.put("dropped_bits", snapshot.droppedBits());
        value.put("observed_at_ms", snapshot.observedAtMs());
        value.put("observed_age_ms", wallAgeMilliseconds(now, snapshot.observedAtMs()));
        value.put("last_valid_decode_ms", positiveOrNull(snapshot.lastValidDecodeMs()));
        value.put("last_valid_decode_age_ms", wallAgeMilliseconds(now, snapshot.lastValidDecodeMs()));
        return value;
    }

    static Long monotonicAgeMilliseconds(long capturedNanos, long eventNanos)
    {
        if(capturedNanos <= 0 || eventNanos <= 0)
        {
            return null;
        }

        return Math.max(0L, capturedNanos - eventNanos) / 1_000_000L;
    }

    private static Long wallAgeMilliseconds(long now, long eventMilliseconds)
    {
        return eventMilliseconds > 0 ? Math.max(0L, now - eventMilliseconds) : null;
    }

    private static long elapsedMicroseconds(long startedNanos, long completedNanos)
    {
        return Math.max(0L, completedNanos - startedNanos) / 1_000L;
    }

    private static Double finiteNonNegativeOrNull(double value)
    {
        return Double.isFinite(value) && value >= 0.0 ? value : null;
    }

    private static Long supportedCounter(long value)
    {
        return value >= 0 ? value : null;
    }

    private static Long delta(long current, long previous)
    {
        return current >= 0 && previous >= 0 && current >= previous ? current - previous : null;
    }

    private static Long positiveOrNull(long value)
    {
        return value > 0 ? value : null;
    }

    private record GcCounters(long collections, long collectionTimeMilliseconds)
    {
    }
}
