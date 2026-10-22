/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.stats.health;

import io.github.dsheirer.dsp.filter.channelizer.PolyphaseChannelManager;
import io.github.dsheirer.source.tuner.usb.USBTunerController;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Observer-owned numeric evidence, independent of the presentation text budget. Accepts snapshots already obtained
 * by the health sampler; it never reads a tuner, decoder, file, or database. No names or device serials leave it.
 */
final class ReceiverHealthDiagnosticCore
{
    // Leaves room for bounded alert metadata within the writer's 64 KiB record ceiling.
    static final int MAXIMUM_TUNERS = 12;
    private static final int MAXIMUM_IDENTITIES = 64;
    private static final int MAXIMUM_ALERT_LINKS = 256;
    static final long EXPECTED_INTERVAL_MS = 1_000;
    private final Map<String,Device> mDevices = new LinkedHashMap<>(16, 0.75f, true);
    private long mNextIdentity;
    private long mCapture;
    private long mGeneration;
    private long mSequence;
    private long mPreviousStartNanos;
    private long mPreviousDurationNanos;
    private long mPreviousGcCount;
    private long mPreviousGcTime;
    private boolean mEnabled;
    private boolean mTimingAvailable;
    private boolean mGcAvailable;

    Sample begin(long generation, long startedAtMs, long nowMs, long nowNanos)
    {
        if(!mEnabled || generation != mGeneration)
        {
            mEnabled = true;
            mGeneration = generation;
            mCapture++;
            mTimingAvailable = false;
            mGcAvailable = false;
        }
        boolean intervalAvailable = mTimingAvailable && nowNanos >= mPreviousStartNanos;
        long interval = intervalAvailable ? nowNanos - mPreviousStartNanos : 0;
        long expected = TimeUnit.MILLISECONDS.toNanos(EXPECTED_INTERVAL_MS) + mPreviousDurationNanos;
        Sample sample = new Sample(startedAtMs, nowMs, nowNanos, ++mSequence, intervalAvailable,
            TimeUnit.NANOSECONDS.toMillis(interval), intervalAvailable ?
                TimeUnit.NANOSECONDS.toMillis(Math.max(0, interval - expected)) : 0);
        mPreviousStartNanos = nowNanos;
        mTimingAvailable = true;
        return sample;
    }

    void disabled()
    {
        if(!mEnabled) return;
        mEnabled = false;
        mTimingAvailable = false;
        mGcAvailable = false;
        // Release stopped tuner object graphs while keeping only the bounded process-local identities.
        mDevices.values().forEach(device -> { device.controller.clear(); device.sourceManager.clear(); });
    }

    final class Sample
    {
        private final long mStartedAtMs;
        private final long mTimeMs;
        private final long mStartNanos;
        private final long mSampleSequence;
        private final boolean mIntervalAvailable;
        private final long mIntervalMs;
        private final long mLatenessMs;
        private final List<Tuner> mTuners = new ArrayList<>();
        private Map<String,Object> mHost = Map.of("available", false);
        private Map<String,Object> mWeb = Map.of("available", false);
        private List<Map<String,Object>> mAlertTuners = List.of();
        private boolean mAlertLinksLimited;
        private int mAmbiguousAlertLinks;
        private int mTunerCount;
        private int mTunerUnavailable;

        private Sample(long startedAtMs, long timeMs, long startNanos, long sequence, boolean intervalAvailable,
                       long intervalMs, long latenessMs)
        {
            mStartedAtMs = startedAtMs;
            mTimeMs = timeMs;
            mStartNanos = startNanos;
            mSampleSequence = sequence;
            mIntervalAvailable = intervalAvailable;
            mIntervalMs = intervalMs;
            mLatenessMs = latenessMs;
        }

        Tuner tuner(String scope, String display, Object controller, Object sourceManager, boolean enabled,
                    boolean error, int channels)
        {
            if(mTuners.size() == MAXIMUM_TUNERS) return null;
            Device device = mDevices.get(scope);
            if(device == null)
            {
                if(mDevices.size() == MAXIMUM_IDENTITIES) mDevices.remove(mDevices.keySet().iterator().next());
                device = new Device(++mNextIdentity);
                mDevices.put(scope, device);
            }
            device.prepare(controller, sourceManager);
            Tuner tuner = new Tuner(device, display, enabled, error, channels);
            mTuners.add(tuner);
            return tuner;
        }

        void tunersObserved(int count) { mTunerCount = count; }
        void tunerUnavailable() { mTunerUnavailable++; }

        /** Link the existing alert's numeric occurrence identity to a numeric tuner identity without emitting names. */
        void activeAlerts(List<Map<String,Object>> alerts)
        {
            List<Map<String,Object>> links = new ArrayList<>();
            int inspected = 0;
            for(Map<String,Object> alert: alerts)
            {
                if(inspected++ == MAXIMUM_ALERT_LINKS) break;
                if(!(alert.get("scope") instanceof String scope) ||
                    !(alert.get("occurrence_id") instanceof Number occurrence)) continue;
                Tuner match = null;
                boolean ambiguous = false;
                for(Tuner tuner: mTuners)
                {
                    if(scope.equals(tuner.mDisplay) || (scope.startsWith(tuner.mDisplay) &&
                        scope.startsWith(" · ", tuner.mDisplay.length())))
                    {
                        if(match == null || tuner.mDisplay.length() > match.mDisplay.length())
                        {
                            match = tuner;
                            ambiguous = false;
                        }
                        else if(tuner.mDisplay.length() == match.mDisplay.length()) ambiguous = true;
                    }
                }
                if(ambiguous) mAmbiguousAlertLinks++;
                else if(match != null) links.add(Map.of("occurrence_id", occurrence.longValue(), "tuner_id", match.mDevice.id));
            }
            mAlertTuners = List.copyOf(links);
            mAlertLinksLimited = alerts.size() > MAXIMUM_ALERT_LINKS;
        }

        void host(double cpuPercent, long heapUsed, long heapMaximum, double heapPercent, long gcCount, long gcTime)
        {
            boolean countersAvailable = gcCount >= 0 && gcTime >= 0;
            boolean gcAvailable = countersAvailable && mGcAvailable && gcCount >= mPreviousGcCount &&
                gcTime >= mPreviousGcTime;
            boolean cpuAvailable = Double.isFinite(cpuPercent) && cpuPercent >= 0;
            mHost = Map.ofEntries(Map.entry("available", true), Map.entry("cpu_available", cpuAvailable),
                Map.entry("cpu_percent", cpuAvailable ? cpuPercent : 0.0),
                Map.entry("heap_used_bytes", heapUsed), Map.entry("heap_max_bytes", heapMaximum),
                Map.entry("heap_percent", heapPercent), Map.entry("gc_available", countersAvailable),
                Map.entry("gc_delta_available", gcAvailable),
                Map.entry("gc_collection_count", gcAvailable ? gcCount - mPreviousGcCount : 0),
                Map.entry("gc_collection_total_count", Math.max(0, gcCount)),
                Map.entry("gc_collection_time_ms", gcAvailable ? gcTime - mPreviousGcTime : 0),
                Map.entry("gc_collection_total_time_ms", Math.max(0, gcTime)));
            mPreviousGcCount = gcCount;
            mPreviousGcTime = gcTime;
            mGcAvailable = countersAvailable;
        }

        void web(Map<String,Object> transport, Map<String,Object> audio, Map<String,Object> diagnostics)
        {
            mWeb = Map.ofEntries(Map.entry("available", true),
                Map.entry("live_available", !transport.isEmpty()), Map.entry("audio_available", !audio.isEmpty()),
                Map.entry("diagnostics_available", !diagnostics.isEmpty()),
                Map.entry("live_clients", number(transport, "active_clients")),
                Map.entry("live_rejected", number(transport, "rejected_clients")),
                Map.entry("live_slow_disconnects", number(transport, "slow_disconnects")),
                Map.entry("live_event_drops", number(transport, "event_drops")),
                Map.entry("audio_listeners", number(audio, "active_feeds")),
                Map.entry("audio_queued_calls", number(audio, "encoder_queue_depth")),
                Map.entry("audio_capacity_drops", number(audio, "dropped_encoder_capacity")),
                Map.entry("audio_encoder_failures", number(audio, "encoder_failures")),
                Map.entry("audio_rejected_feeds", number(audio, "rejected_feeds")),
                Map.entry("channel_views", number(diagnostics, "channel_sessions")),
                Map.entry("tuner_views", number(diagnostics, "tuner_sessions")),
                Map.entry("channel_producers", number(diagnostics, "channel_producers")),
                Map.entry("tuner_producers", number(diagnostics, "tuner_producers")));
        }

        Map<String,Object> finish(long endNanos)
        {
            mPreviousDurationNanos = Math.max(0, endNanos - mStartNanos);
            List<Map<String,Object>> tuners = mTuners.stream().map(Tuner::snapshot).toList();
            return Map.ofEntries(Map.entry("schema_version", 1), Map.entry("started_at_ms", mStartedAtMs),
                Map.entry("capture_generation", mGeneration),
                Map.entry("generated_at_ms", mTimeMs), Map.entry("sample_sequence", mSampleSequence),
                Map.entry("sample_interval_available", mIntervalAvailable), Map.entry("sample_interval_ms", mIntervalMs),
                Map.entry("sample_expected_interval_ms", EXPECTED_INTERVAL_MS), Map.entry("sample_lateness_ms", mLatenessMs),
                Map.entry("sample_duration_ms", TimeUnit.NANOSECONDS.toMillis(mPreviousDurationNanos)),
                Map.entry("host", mHost), Map.entry("web", mWeb), Map.entry("tuners", tuners),
                Map.entry("alert_tuners", mAlertTuners), Map.entry("alert_links_limited", mAlertLinksLimited),
                Map.entry("alert_tuners_ambiguous", mAmbiguousAlertLinks),
                Map.entry("tuner_count", mTunerCount),
                Map.entry("tuners_omitted", Math.max(0, mTunerCount - tuners.size())),
                Map.entry("tuners_unavailable", mTunerUnavailable), Map.entry("tuner_limit", MAXIMUM_TUNERS));
        }
    }

    static final class Tuner
    {
        private final Device mDevice;
        private final String mDisplay;
        private final boolean mEnabled;
        private final boolean mError;
        private final int mChannels;
        private Map<String,Object> mUsb = Map.of("available", false);
        private Map<String,Object> mIngress = Map.of("available", false);
        private Map<String,Object> mNative = Map.of("available", false);
        private Map<String,Object> mChannelizer = Map.of("available", false);
        private Map<String,Object> mChannelAggregate = Map.of("available", false);

        private Tuner(Device device, String display, boolean enabled, boolean error, int channels)
        { mDevice = device; mDisplay = display; mEnabled = enabled; mError = error; mChannels = channels; }

        void usb(USBTunerController.UsbTransferHealthSnapshot usb, long lastDeliveryAgeMilliseconds, boolean deliveryAvailable,
                 double deliveryPercent, double usableBytesPerSecond, double requiredBytesPerSecond)
        {
            mDevice.stream(usb.streamSequence());
            long statuses = usb.errorTransferCount() + usb.stalledTransferCount() + usb.timedOutTransferCount() +
                usb.cancelledTransferCount() + usb.unexpectedStatusTransferCount() + usb.submissionFailureCount();
            Delta completed = mDevice.delta("usb_completed", usb.completedTransferCount());
            Delta errors = mDevice.delta("usb_status_errors", statuses);
            Delta gaps = mDevice.delta("usb_long_gaps", usb.longTransferGapCount());
            mUsb = Map.ofEntries(Map.entry("available", true), Map.entry("streaming", usb.streaming()),
                Map.entry("stream_sequence", usb.streamSequence()), Map.entry("completed", usb.completedTransferCount()),
                Map.entry("new_completed", completed.value()), Map.entry("errors", usb.errorTransferCount()),
                Map.entry("status_errors", statuses), Map.entry("new_status_errors", errors.value()),
                Map.entry("delta_available", completed.available() && errors.available() && gaps.available()),
                Map.entry("active_transfers", usb.activeTransferCount()), Map.entry("retry_transfers", usb.retryTransferCount()),
                Map.entry("delivery_available", deliveryAvailable),
                Map.entry("delivery_percent", deliveryAvailable && Double.isFinite(deliveryPercent) ? deliveryPercent : 0.0),
                Map.entry("usable_bytes_s", usableBytesPerSecond), Map.entry("required_bytes_s", requiredBytesPerSecond),
                Map.entry("last_delivery_available", usb.hasTransfer()),
                Map.entry("last_delivery_age_ms", usb.hasTransfer() ? Math.max(0, lastDeliveryAgeMilliseconds) : 0),
                Map.entry("last_gap_ms", usb.lastInterTransferGapMilliseconds()),
                Map.entry("max_gap_ms", usb.worstInterTransferGapMilliseconds()),
                Map.entry("long_gaps", usb.longTransferGapCount()), Map.entry("new_long_gaps", gaps.value()),
                Map.entry("last_callback_ms", milliseconds(usb.lastCallbackToResubmitDurationNanoseconds())),
                Map.entry("max_callback_ms", milliseconds(usb.worstCallbackToResubmitDurationNanoseconds())),
                Map.entry("callbacks", usb.callbackCount()), Map.entry("slow_callbacks", usb.callbacksAtLeast25MsCount()));
            Delta dropped = mDevice.delta("ingress_dropped", usb.nativeIngressSaturationDroppedBuffers());
            Delta failures = mDevice.delta("ingress_failures", usb.nativeIngressCopyFailures() +
                usb.nativeIngressConversionFailures() + usb.nativeIngressListenerFailures());
            mIngress = Map.ofEntries(Map.entry("available", true), Map.entry("depth", usb.nativeIngressDepth()),
                Map.entry("capacity", usb.nativeIngressCapacity()), Map.entry("high_water", usb.nativeIngressHighWaterDepth()),
                Map.entry("dropped_buffers", usb.nativeIngressSaturationDroppedBuffers()),
                Map.entry("new_dropped_buffers", dropped.value()), Map.entry("dropped_samples", usb.nativeIngressSaturationDroppedSamples()),
                Map.entry("copy_failures", usb.nativeIngressCopyFailures()),
                Map.entry("conversion_failures", usb.nativeIngressConversionFailures()),
                Map.entry("listener_failures", usb.nativeIngressListenerFailures()), Map.entry("new_failures", failures.value()),
                Map.entry("delta_available", dropped.available() && failures.available()),
                Map.entry("queue_delay_ms", milliseconds(usb.nativeIngressLastQueueDelayNanoseconds())),
                Map.entry("max_queue_delay_ms", milliseconds(usb.nativeIngressWorstQueueDelayNanoseconds())));
        }

        void nativeQueue(PolyphaseChannelManager.NativeBufferQueueStatus queue)
        {
            Delta dropped = mDevice.delta("native_dropped", queue.droppedBuffers());
            mNative = Map.ofEntries(Map.entry("available", true), Map.entry("queued_ms", queue.queuedMilliseconds()),
                Map.entry("capacity_ms", queue.appliedDurationMilliseconds()), Map.entry("high_water_ms", queue.highWaterMilliseconds()),
                Map.entry("dropped_buffers", queue.droppedBuffers()), Map.entry("new_dropped_buffers", dropped.value()),
                Map.entry("dropped_samples", queue.droppedSamples()), Map.entry("dropped_ms", queue.droppedMilliseconds()),
                Map.entry("delta_available", dropped.available()));
        }

        void pipeline(PolyphaseChannelManager.PipelineStatus pipeline, long queued, long peak, long capacity,
                      long maximumCapacity)
        {
            Delta separated = mDevice.delta("ifft_dropped", pipeline.ifftDroppedBatches());
            Delta channels = mDevice.delta("channel_dropped", pipeline.channelDroppedBatches());
            mChannelizer = Map.ofEntries(Map.entry("available", true), Map.entry("queued", pipeline.ifftQueuedBatches()),
                Map.entry("capacity", pipeline.ifftCapacityBatches()), Map.entry("high_water", pipeline.ifftHighWaterBatches()),
                Map.entry("dropped", pipeline.ifftDroppedBatches()), Map.entry("new_dropped", separated.value()),
                Map.entry("delta_available", separated.available()));
            mChannelAggregate = Map.ofEntries(Map.entry("available", true), Map.entry("count", pipeline.channels().size()),
                Map.entry("queued", queued), Map.entry("high_water", peak), Map.entry("maximum_capacity", maximumCapacity),
                Map.entry("capacity", capacity),
                Map.entry("dropped", pipeline.channelDroppedBatches()), Map.entry("new_dropped", channels.value()),
                Map.entry("delta_available", channels.available()));
        }

        private Map<String,Object> snapshot()
        {
            return Map.ofEntries(Map.entry("id", mDevice.id), Map.entry("epoch", mDevice.epoch),
                Map.entry("enabled", mEnabled), Map.entry("error", mError), Map.entry("channels", mChannels),
                Map.entry("usb", mUsb), Map.entry("ingress", mIngress), Map.entry("native", mNative),
                Map.entry("channelizer", mChannelizer), Map.entry("channel_aggregate", mChannelAggregate));
        }
    }

    private final class Device
    {
        private final long id;
        private long epoch;
        private long capture;
        private WeakReference<Object> controller = new WeakReference<>(null);
        private WeakReference<Object> sourceManager = new WeakReference<>(null);
        private long streamSequence;
        private boolean streamAvailable;
        private final Map<String,Long> counters = new LinkedHashMap<>();
        private Device(long identity) { id = identity; }
        private void prepare(Object nextController, Object nextManager)
        {
            if(capture != mCapture || controller.get() != nextController || sourceManager.get() != nextManager)
            {
                epoch++;
                capture = mCapture;
                controller = new WeakReference<>(nextController);
                sourceManager = new WeakReference<>(nextManager);
                counters.clear();
                streamAvailable = false;
            }
        }
        private void stream(long sequence)
        {
            if(streamAvailable && sequence != streamSequence)
            {
                epoch++;
                counters.clear();
            }
            streamSequence = sequence;
            streamAvailable = true;
        }
        private Delta delta(String key, long value)
        {
            Long previous = counters.put(key, value);
            boolean available = previous != null && value >= previous;
            return new Delta(available ? value - previous : 0, available);
        }
    }

    private record Delta(long value, boolean available) { }
    private static double milliseconds(long nanos) { return Math.max(0, nanos) / 1_000_000.0; }
    private static long number(Map<String,Object> values, String key)
    { return values.get(key) instanceof Number number ? Math.max(0, number.longValue()) : 0; }
}
