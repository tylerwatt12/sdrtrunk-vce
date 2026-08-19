/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.health;

import io.github.dsheirer.audio.broadcast.AudioStreamingManager;
import io.github.dsheirer.audio.call.AudioCallCoordinator;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.dsp.filter.channelizer.PolyphaseChannelManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.AudioRecordingManager;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerController;
import io.github.dsheirer.source.tuner.manager.ChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.DiscoveredUSBTuner;
import io.github.dsheirer.source.tuner.manager.PolyphaseChannelSourceManager;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import io.github.dsheirer.source.tuner.manager.TunerStatus;
import io.github.dsheirer.source.tuner.usb.USBTunerController;
import io.github.dsheirer.stats.activity.P25ActivityLogService;
import io.github.dsheirer.stats.activity.P25ActivityLogStatus;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Collects receiver troubleshooting measurements once per second on one low-priority observer thread.  Producer-side
 * instrumentation is limited to primitive counters; this service performs all snapshots, incident correlation,
 * formatting, filesystem queries and API projection away from receiver and decoder callbacks.
 */
public final class ReceiverHealthService implements AutoCloseable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ReceiverHealthService.class);
    private static final long SAMPLE_INTERVAL_MILLISECONDS = 1_000L;
    private static final long CONDITION_HOLD_MILLISECONDS = 10_000L;
    private static final long STORAGE_SAMPLE_INTERVAL_MILLISECONDS = 30_000L;
    private static final long FAILURE_LOG_INTERVAL_MILLISECONDS = 60_000L;
    private static final long COUNTER_BASELINE_RETENTION_MILLISECONDS = 60_000L;
    private static final Set<String> CURRENT_CONTROL_TAGS = Set.of("CURRENT_CONTROL");
    private static final Set<String> SERVICE_IMPACT_INCIDENT_CODES = Set.of("tuner-error",
        "tuner-allocation-failure", "control-channel-lock-lost", "audio-coordinator-ingress",
        "audio-coordinator-aborted", "recording", "streaming", "web-audio-drop");

    private final UserPreferences mUserPreferences;
    private final TunerManager mTunerManager;
    private final ChannelActivityModel mChannelActivityModel;
    private final P25ActivityLogService mActivityLogService;
    private final LongSupplier mClock;
    private final long mStartedAtMs;
    private final ScheduledExecutorService mExecutor;
    private final ReceiverHealthSnapshotWriter mSnapshotWriter;
    private final ReceiverHealthIncidentTracker mIncidents = new ReceiverHealthIncidentTracker();
    private final Map<String,CounterBaseline> mCounterBaselines = new HashMap<>();
    private final Map<String,Long> mConditionStartTimes = new HashMap<>();
    private final Set<String> mConditionsEvaluatedThisSample = new HashSet<>();
    private final Map<String,UsbRateBaseline> mUsbRateBaselines = new HashMap<>();
    private final Map<String,ControlContinuity> mControlContinuityByTable = new HashMap<>();
    private final AtomicBoolean mStarted = new AtomicBoolean();
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private volatile AudioCallCoordinator mAudioCallCoordinator;
    private volatile AudioRecordingManager mAudioRecordingManager;
    private volatile AudioStreamingManager mAudioStreamingManager;
    private volatile Supplier<Map<String,Object>> mWebStatusSupplier = Map::of;
    private volatile Supplier<ChannelActivityModel.SnapshotSet> mChannelActivitySnapshotSupplier;
    private volatile Map<String,Object> mSnapshot;
    private long mLastStorageSampleMs;
    private StorageSnapshot mStorageSnapshot = StorageSnapshot.unavailable();
    private long mLastGcCollectionTimeMs = -1;
    private long mLastFailureLogMs;
    private long mLastSnapshotWriteFailureLogMs;

    public ReceiverHealthService(UserPreferences userPreferences, TunerManager tunerManager,
                                 ChannelProcessingManager channelProcessingManager,
                                 P25ActivityLogService activityLogService)
    {
        this(userPreferences, tunerManager, channelProcessingManager, activityLogService,
            System::currentTimeMillis, snapshotWriter(userPreferences));
    }

    ReceiverHealthService(UserPreferences userPreferences, TunerManager tunerManager,
                          ChannelProcessingManager channelProcessingManager,
                          P25ActivityLogService activityLogService, LongSupplier clock)
    {
        this(userPreferences, tunerManager, channelProcessingManager, activityLogService, clock, null);
    }

    ReceiverHealthService(UserPreferences userPreferences, TunerManager tunerManager,
                          ChannelProcessingManager channelProcessingManager,
                          P25ActivityLogService activityLogService, LongSupplier clock,
                          ReceiverHealthSnapshotWriter snapshotWriter)
    {
        mUserPreferences = userPreferences;
        mTunerManager = tunerManager;
        mChannelActivityModel = channelProcessingManager != null ? channelProcessingManager.getChannelActivityModel() :
            null;
        mChannelActivitySnapshotSupplier = () -> mChannelActivityModel != null ?
            mChannelActivityModel.getSnapshotSet() : new ChannelActivityModel.SnapshotSet(0, List.of());
        mActivityLogService = activityLogService;
        mClock = clock != null ? clock : System::currentTimeMillis;
        mSnapshotWriter = snapshotWriter;
        mStartedAtMs = mClock.getAsLong();
        mSnapshot = emptySnapshot(mStartedAtMs);
        mExecutor = Executors.newSingleThreadScheduledExecutor(runnable ->
        {
            Thread thread = new Thread(runnable, "receiver health sampler");
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 2));
            return thread;
        });

    }

    /**
     * Starts the single observer sampler after its owning services have completed construction.
     */
    public void start()
    {
        if(!mClosed.get() && mStarted.compareAndSet(false, true))
        {
            mExecutor.scheduleWithFixedDelay(this::sampleSafely, 0, SAMPLE_INTERVAL_MILLISECONDS,
                TimeUnit.MILLISECONDS);
        }
    }

    public void setOutputSources(AudioCallCoordinator coordinator, AudioRecordingManager recordingManager,
                                 AudioStreamingManager streamingManager)
    {
        mAudioCallCoordinator = coordinator;
        mAudioRecordingManager = recordingManager;
        mAudioStreamingManager = streamingManager;
    }

    public void setWebStatusSupplier(Supplier<Map<String,Object>> webStatusSupplier)
    {
        mWebStatusSupplier = webStatusSupplier != null ? webStatusSupplier : Map::of;
    }

    void setChannelActivitySnapshotSupplier(Supplier<ChannelActivityModel.SnapshotSet> supplier)
    {
        mChannelActivitySnapshotSupplier = supplier != null ? supplier :
            () -> new ChannelActivityModel.SnapshotSet(0, List.of());
    }

    /**
     * Immutable API payload.  HTTP callers never sample live receiver objects themselves.
     */
    public Map<String,Object> snapshot()
    {
        return mSnapshot;
    }

    void sampleNow()
    {
        sample();
    }

    private void sampleSafely()
    {
        try
        {
            sample();
        }
        catch(Throwable throwable)
        {
            long now = System.currentTimeMillis();

            if(mLastFailureLogMs == 0 || now - mLastFailureLogMs >= FAILURE_LOG_INTERVAL_MILLISECONDS)
            {
                mLastFailureLogMs = now;
                LOGGER.warn("Receiver health sampling failed; the last complete snapshot remains available", throwable);
            }
        }
    }

    private void sample()
    {
        if(mClosed.get())
        {
            return;
        }

        long now = mClock.getAsLong();
        mIncidents.beginSample();
        mConditionsEvaluatedThisSample.clear();
        List<Map<String,Object>> measurements = new ArrayList<>();
        collectTuners(now, measurements);
        collectControlChannels(now, measurements);
        collectHost(now, measurements);
        collectOutputs(now, measurements);
        collectSupportingServices(now, measurements);
        mCounterBaselines.entrySet().removeIf(entry ->
            now - entry.getValue().lastSeenMs > COUNTER_BASELINE_RETENTION_MILLISECONDS);
        mConditionStartTimes.keySet().retainAll(mConditionsEvaluatedThisSample);
        mIncidents.endSample(now);
        List<Map<String,Object>> active = mIncidents.active();
        List<Map<String,Object>> serviceImpact = active.stream().filter(ReceiverHealthService::isServiceImpact)
            .toList();
        long critical = serviceImpact.stream().filter(incident -> "critical".equals(incident.get("severity")))
            .count();
        long warning = serviceImpact.size() - critical;
        String severity = critical > 0 ? "critical" : warning > 0 ? "warning" : "healthy";
        LinkedHashMap<String,Object> summary = new LinkedHashMap<>();
        summary.put("severity", severity);
        summary.put("active_count", serviceImpact.size());
        summary.put("warning_count", warning);
        summary.put("critical_count", critical);
        summary.put("diagnostic_count", active.size() - serviceImpact.size());
        LinkedHashMap<String,Object> response = new LinkedHashMap<>();
        response.put("started_at_ms", mStartedAtMs);
        response.put("generated_at_ms", now);
        response.put("summary", Map.copyOf(summary));
        response.put("active", active);
        response.put("resolved", mIncidents.resolved());
        response.put("measurements", List.copyOf(measurements));
        mSnapshot = Map.copyOf(response);
        publishSnapshot(response, now);
    }

    private void publishSnapshot(Map<String,Object> snapshot, long now)
    {
        if(mSnapshotWriter == null)
        {
            return;
        }

        try
        {
            mSnapshotWriter.publish(snapshot);
        }
        catch(Exception exception)
        {
            if(mLastSnapshotWriteFailureLogMs == 0 ||
                now - mLastSnapshotWriteFailureLogMs >= FAILURE_LOG_INTERVAL_MILLISECONDS)
            {
                mLastSnapshotWriteFailureLogMs = now;
                LOGGER.warn("Receiver health incident report could not be updated", exception);
            }
        }
    }

    private void collectTuners(long now, List<Map<String,Object>> measurements)
    {
        List<Map<String,Object>> tunerRows = new ArrayList<>();
        List<Map<String,Object>> usbRows = new ArrayList<>();
        List<Map<String,Object>> receiverRows = new ArrayList<>();
        List<Map<String,Object>> channelizerRows = new ArrayList<>();
        List<Map<String,Object>> channelRows = new ArrayList<>();
        Set<String> activeUsbScopes = new HashSet<>();
        List<DiscoveredTuner> tuners = mTunerManager != null ?
            mTunerManager.getDiscoveredTunerModel().getTunersSnapshot() : List.of();

        for(DiscoveredTuner discovered: tuners)
        {
            String fallbackScope = "tuner-" + Integer.toHexString(System.identityHashCode(discovered));

            try
            {
            String discoveredId = discovered.getId();
            String scope = discoveredId != null && !discoveredId.isBlank() ? discoveredId : fallbackScope;
            TunerStatus tunerStatus = discovered.getTunerStatus();
            Tuner tuner = discovered.getTuner();
            String preferredName = tuner != null ? tuner.getPreferredName() : null;
            String display = preferredName != null && !preferredName.isBlank() ? preferredName : scope;
            String uniqueId = tuner != null ? tuner.getUniqueID() : null;
            String serial = uniqueId != null ? uniqueId : "";
            TunerController controller = tuner != null ? tuner.getTunerController() : null;
            ChannelSourceManager sourceManager = tuner != null ? tuner.getChannelSourceManager() : null;
            int channels = sourceManager != null ? sourceManager.getTunerChannelCount() : 0;
            tunerRows.add(row(scope, display, tunerStatus.name().toLowerCase(Locale.ROOT), "",
                tunerStatus == TunerStatus.ERROR ? "critical" : "healthy",
                "serial=" + serial + "; enabled=" + discovered.isEnabled() + "; channels=" + channels +
                    (controller != null ? "; center=" + controller.getFrequency() + " Hz; sample_rate=" +
                        Math.round(controller.getSampleRate()) + " samples/s" : "")));

            if(discovered.isEnabled() && tunerStatus == TunerStatus.ERROR)
            {
                mIncidents.observe("tuner-error", "critical", "Tuner is in an error state", display, now, 1,
                    discovered.getErrorMessage(), "USB/device access failure or tuner initialization failure",
                    "Channels assigned to this tuner cannot receive samples", "Check the tuner error, cable, driver, and power");
            }

            if(discovered instanceof DiscoveredUSBTuner usb)
            {
                usbRows.add(row(scope, "USB location", "Bus " + usb.getBus() + " / " + usb.getPortAddress(), "",
                    "info", display + (serial.isBlank() ? "" : " · " + serial)));
            }

            if(controller instanceof USBTunerController usbController)
            {
                activeUsbScopes.add(scope);
                collectUsb(now, scope, display, tuner, usbController.getUsbTransferHealthSnapshot(), usbRows);
            }

            if(sourceManager instanceof PolyphaseChannelSourceManager polyphase)
            {
                PolyphaseChannelManager.NativeBufferQueueStatus nativeStatus =
                    polyphase.getNativeBufferQueueStatus();
                receiverRows.add(row(scope, display + " IQ queue", nativeStatus.queuedMilliseconds(), "ms",
                    queueSeverity(nativeStatus.queuedMilliseconds(), nativeStatus.appliedDurationMilliseconds()),
                    "high_water=" + nativeStatus.highWaterMilliseconds() + " ms; capacity=" +
                        nativeStatus.appliedDurationMilliseconds() + " ms; requested=" +
                        nativeStatus.requestedDurationMilliseconds() + " ms; dropped=" +
                        nativeStatus.droppedBuffers() + " buffers / " + nativeStatus.droppedMilliseconds() + " ms"));
                long nativeDropDelta = delta(scope + ":native-drops", nativeStatus.droppedBuffers(), now);

                if(nativeDropDelta > 0)
                {
                    mIncidents.observe("receiver-iq-drop", "critical", "Receiver IQ samples were discarded", display,
                        now, nativeStatus.droppedBuffers(), nativeDropDelta + " new buffers; " +
                            nativeStatus.droppedMilliseconds() + " ms discarded since start",
                        "The tuner sample producer outran the channelizer or its worker was delayed",
                        "Every channel on this tuner can lose sync or audio", "Check USB integrity, CPU/GC, and receiver queue pressure");
                }

                if(sustained(scope + ":native-pressure",
                    nativeStatus.appliedDurationMilliseconds() > 0 && nativeStatus.queuedMilliseconds() * 4 >=
                        nativeStatus.appliedDurationMilliseconds() * 3, now))
                {
                    mIncidents.observe("receiver-queue-pressure", "warning", "Receiver IQ queue is nearly full",
                        display, now, nativeStatus.highWaterMilliseconds(), "current=" +
                            nativeStatus.queuedMilliseconds() + " ms; capacity=" +
                            nativeStatus.appliedDurationMilliseconds() + " ms",
                        "The tuner sample producer is running ahead of receiver processing",
                        "Continued pressure will discard IQ for every channel on this tuner",
                        "Check USB delivery, CPU/GC, channelizer load, and active channels");
                }

                PolyphaseChannelManager.PipelineStatus pipeline = polyphase.getPipelineStatus();
                channelizerRows.add(row(scope, display + " IFFT queue", pipeline.ifftQueuedBatches(), "batches",
                    queueSeverity(pipeline.ifftQueuedBatches(), pipeline.ifftCapacityBatches()),
                    channelizerDetail(pipeline)));
                long ifftDropDelta = delta(scope + ":ifft-drops", pipeline.ifftDroppedBatches(), now);

                if(ifftDropDelta > 0)
                {
                    mIncidents.observe("channelizer-drop", "critical", "Channelizer output batches were discarded",
                        display, now, pipeline.ifftDroppedBatches(), ifftDropDelta + " new IFFT batches",
                        "The polyphase IFFT worker missed its bounded queue deadline",
                        "All extracted channels on this tuner can have missing samples",
                        "Check CPU/GC and per-channel backlog; reduce avoidable receiver work");
                }

                if(sustained(scope + ":ifft-pressure", pipeline.ifftCapacityBatches() > 0 &&
                    pipeline.ifftQueuedBatches() * 4 >= pipeline.ifftCapacityBatches() * 3, now))
                {
                    mIncidents.observe("channelizer-queue-pressure", "warning",
                        "Channelizer IFFT queue is nearly full", display, now, pipeline.ifftHighWaterBatches(),
                        "current=" + pipeline.ifftQueuedBatches() + "; capacity=" +
                            pipeline.ifftCapacityBatches(), "The IFFT worker is close to missing its deadline",
                        "All extracted channels on this tuner are at risk of sample loss",
                        "Inspect host CPU/GC and reduce receiver or diagnostic load");
                }

                long aggregateChannelDrops = pipeline.channelDroppedBatches();

                for(PolyphaseChannelManager.ChannelQueueStatus channel: pipeline.channels())
                {
                    String channelScope = scope + ":" + channel.frequencyHz();

                    if(sustained(channelScope + ":pressure", channel.capacityBatches() > 0 &&
                        channel.queuedBatches() * 4 >= channel.capacityBatches() * 3, now))
                    {
                        mIncidents.observe("channel-queue-pressure", "warning",
                            "A channel output queue is nearly full", display + " · " +
                                formatFrequency(channel.frequencyHz()), now,
                            channel.highWaterBatches(), "current=" + channel.queuedBatches() + "; capacity=" +
                                channel.capacityBatches() + "; tuner=" + display,
                            "This channel's extraction or decoder work is not keeping up",
                            "The affected control or traffic channel can lose sync or audio",
                            "Inspect host CPU/GC and the work associated with this channel");
                    }
                }

                List<PolyphaseChannelManager.ChannelQueueStatus> affected = pipeline.channels().stream()
                    .sorted(Comparator.comparingLong(PolyphaseChannelManager.ChannelQueueStatus::droppedBatches)
                        .reversed().thenComparing(Comparator.comparingInt(
                            PolyphaseChannelManager.ChannelQueueStatus::queuedBatches).reversed()))
                    .limit(20).toList();

                for(PolyphaseChannelManager.ChannelQueueStatus channel: affected)
                {
                    String channelScope = scope + ":" + channel.frequencyHz();
                    channelRows.add(row(channelScope, formatFrequency(channel.frequencyHz()), channel.queuedBatches(),
                        "batches", queueSeverity(channel.queuedBatches(), channel.capacityBatches()),
                        display + "; high_water=" + channel.highWaterBatches() + "; capacity=" +
                            channel.capacityBatches() + "; dropped=" + channel.droppedBatches()));
                }

                long channelDropDelta = delta(scope + ":channel-drops", aggregateChannelDrops, now);

                if(channelDropDelta > 0)
                {
                    mIncidents.observe("channel-output-drop", "critical", "A channel decoder missed sample batches",
                        display, now, aggregateChannelDrops, channelDropDelta + " new channel batches",
                        "One or more channel output workers could not keep up",
                        "Affected control or traffic channels can lose sync and produce broken audio",
                        "Inspect the per-channel rows and host load; reduce active channel pressure");
                }
            }
            }
            catch(RuntimeException exception)
            {
                tunerRows.add(row(fallbackScope, "Tuner measurements unavailable", "unavailable", "", "warning",
                    "A tuner changed lifecycle state while the observer sampled it (" +
                        exception.getClass().getSimpleName() + "); the next snapshot will retry"));
            }
        }

        mUsbRateBaselines.keySet().retainAll(activeUsbScopes);

        if(mTunerManager != null)
        {
            TunerManager.TunerAllocationStatus allocation = mTunerManager.getTunerAllocationStatus();
            long failureDelta = delta("tuner:allocation-failures", allocation.failures(), now);
            tunerRows.add(row("allocations", "Channel source allocation", allocation.successes(), "successful",
                failureDelta > 0 ? "warning" : allocation.failures() > 0 ? "info" : "healthy",
                "requests=" + allocation.requests() +
                    "; failures=" + allocation.failures()));

            if(failureDelta > 0)
            {
                mIncidents.observe("tuner-allocation-failure", "warning", "A channel could not obtain a tuner source",
                    "Tuner allocation", now, allocation.failures(), failureDelta + " new failed allocation(s)",
                    "No enabled tuner covered the requested frequency, a tuner was busy/locked, or allocation raced tuner lifecycle",
                    "A control or granted traffic channel may not start",
                    "Check enabled tuner frequency spans, center locks, preferred tuner settings, and active channel count");
            }
        }

        measurements.add(section("tuners", "Tuners", tunerRows));
        measurements.add(section("usb", "USB transfers", usbRows));
        measurements.add(section("receiver-queues", "Receiver IQ queues", receiverRows));
        measurements.add(section("channelizer", "Channelizers", channelizerRows));
        measurements.add(section("channels", "Channel output queues", channelRows));
    }

    private void collectUsb(long now, String scope, String display, Tuner tuner,
                            USBTunerController.UsbTransferHealthSnapshot usb,
                            List<Map<String,Object>> rows)
    {
        UsbRateBaseline baseline = mUsbRateBaselines.get(scope);
        boolean rateAvailable = baseline != null && baseline.sequence == usb.streamSequence() &&
            now > baseline.timestampMs;
        double callbackBytesPerSecond = 0;
        double usableBytesPerSecond = 0;

        if(rateAvailable)
        {
            double seconds = (now - baseline.timestampMs) / 1_000.0;
            callbackBytesPerSecond = Math.max(0, usb.expectedBytes() - baseline.expectedBytes) / seconds;
            usableBytesPerSecond = Math.max(0, usb.usableBytes() - baseline.usableBytes) / seconds;
        }

        mUsbRateBaselines.put(scope, new UsbRateBaseline(now, usb.streamSequence(), usb.expectedBytes(),
            usb.usableBytes()));
        TunerController controller = tuner.getTunerController();
        double requiredBytesPerSecond = usb.streaming() && controller != null ?
            controller.getSampleRate() * usb.sampleFrameSizeBytes() : 0;
        double deliveryPercent = rateAvailable && requiredBytesPerSecond > 0 ?
            Math.min(100.0, 100.0 * usableBytesPerSecond / requiredBytesPerSecond) : 100.0;
        long transferStatusCount = usb.errorTransferCount() + usb.stalledTransferCount() +
            usb.timedOutTransferCount() + usb.cancelledTransferCount() + usb.unexpectedStatusTransferCount() +
            usb.submissionFailureCount();
        long transferStatusDelta = delta(scope + ":usb-status", transferStatusCount, now);
        long integrityCount = usb.shortTransferCount() + usb.zeroLengthTransferCount() +
            usb.malformedTransferCount() + transferStatusCount;
        long integrityDelta = delta(scope + ":usb-integrity", integrityCount, now);
        long gapDelta = delta(scope + ":usb-long-gaps", usb.longTransferGapCount(), now);
        rows.add(row(scope, display + " delivered", round(usableBytesPerSecond / 1_000_000.0), "MB/s",
            rateAvailable && deliveryPercent < 90.0 ? "critical" : rateAvailable && deliveryPercent < 98.0 ?
                "warning" : "healthy", "required=" + round(requiredBytesPerSecond / 1_000_000.0) +
                " MB/s; callbacks=" + round(callbackBytesPerSecond / 1_000_000.0) + " MB/s; delivery=" +
                (rateAvailable ? round(deliveryPercent) + "%" : "warming up") + "; streaming=" +
                usb.streaming() + "; tuner_peak_payload=" +
                round(tuner.getMaximumUSBBitsPerSecond() / 1_000_000.0) + " Mbit/s"));
        rows.add(row(scope, display + " transfer status", usb.transferCount(), "transfers",
            transferStatusDelta > 0 ? "warning" : transferStatusCount > 0 ? "info" : "healthy", "completed=" +
                usb.completedTransferCount() + "; stall=" +
                usb.stalledTransferCount() + "; timeout=" + usb.timedOutTransferCount() + "; error=" +
                usb.errorTransferCount() + "; cancelled=" + usb.cancelledTransferCount() + "; unexpected=" +
                usb.unexpectedStatusTransferCount()));
        rows.add(row(scope, display + " transfer pool", usb.activeTransferCount(), "active transfers",
            usb.retryTransferCount() > 0 ? "warning" : "healthy", "pool=" + usb.transferPoolSize() +
                "; retrying=" + usb.retryTransferCount() + "; submission_failures=" +
                usb.submissionFailureCount()));
        rows.add(row(scope, display + " negotiated USB link", usb.negotiatedDeviceSpeed(), "",
            "info", "device_speed_code=" + usb.negotiatedDeviceSpeedCode() +
                "; this is the tuner link speed, not the complete upstream hub/root-controller capacity"));
        rows.add(row(scope, display + " transfer integrity", usb.shortTransferCount(), "short transfers",
            integrityDelta > 0 ? "critical" : integrityCount > 0 ? "info" : "healthy",
            "zero=" + usb.zeroLengthTransferCount() +
                "; malformed=" + usb.malformedTransferCount() + "; missing=" + usb.estimatedMissingBytes() +
                " bytes; unreliable_payload=" + usb.unusableBytes() + " bytes"));
        rows.add(row(scope, display + " transfer gap", usb.lastInterTransferGapMilliseconds(), "ms",
            gapDelta > 0 ? "warning" : usb.longTransferGapCount() > 0 ? "info" : "healthy",
            "worst=" + usb.worstInterTransferGapMilliseconds() + " ms; expected_transfer=" +
                usb.expectedTransferLengthBytes() + " bytes; long_gaps=" + usb.longTransferGapCount()));
        boolean rateFailure = usb.streaming() && rateAvailable && requiredBytesPerSecond > 0 &&
            deliveryPercent < 90.0;
        long lastDelivery = usb.lastTransferTimestampMilliseconds() > 0 ?
            usb.lastTransferTimestampMilliseconds() : usb.streamStartedTimestampMilliseconds();
        boolean stale = usb.streaming() && lastDelivery > 0 && now - lastDelivery > 1_000;

        if(integrityDelta > 0 || rateFailure || stale)
        {
            mIncidents.observe("usb-sample-loss", "critical", "USB tuner sample delivery is incomplete", display,
                now, integrityCount, integrityDelta + " new transfer integrity events; delivery=" +
                    round(deliveryPercent) + "%; missing=" + usb.estimatedMissingBytes() + " bytes",
                "USB bandwidth, hub/controller contention, cable/power trouble, or a device/driver transfer fault",
                "The tuner can keep showing signal power while every channel loses decode sync",
                "Separate high-rate tuners across USB root controllers; then check cable, power, and negotiated speed");
        }
        else if(gapDelta > 0)
        {
            mIncidents.observe("usb-transfer-gap", "warning", "USB tuner delivery paused", display, now,
                usb.longTransferGapCount(), gapDelta + " new gap(s) of at least 200 ms; latest=" +
                    usb.lastInterTransferGapMilliseconds() + " ms; worst=" +
                    usb.worstInterTransferGapMilliseconds() + " ms",
                "Temporary USB scheduling, host load, or device transfer delay",
                "A long enough gap can break control-channel sync or clip audio",
                "Watch for repeated gaps together with receiver or decoder drops");
        }

        if(sustained(scope + ":usb-pool-degraded", usb.streaming() && usb.retryTransferCount() > 0, now))
        {
            mIncidents.observe("usb-transfer-pool-degraded", "warning",
                "USB tuner transfer capacity is degraded", display, now, usb.retryTransferCount(),
                "active=" + usb.activeTransferCount() + "/" + usb.transferPoolSize() + "; retrying=" +
                    usb.retryTransferCount() + "; submission_failures=" + usb.submissionFailureCount(),
                "One or more USB transfer buffers cannot be resubmitted",
                "Reduced transfer concurrency makes sample gaps and control-channel loss more likely",
                "Check USB bandwidth, hub/root-controller placement, cable, power, and driver stability");
        }
    }

    private void collectControlChannels(long now, List<Map<String,Object>> measurements)
    {
        List<Map<String,Object>> rows = new ArrayList<>();
        ChannelActivityModel.SnapshotSet set = mChannelActivitySnapshotSupplier.get();
        Set<String> activeTables = new HashSet<>();

        for(ChannelActivitySnapshot table: set.tables())
        {
            if(table.controlActive())
            {
                activeTables.add(table.tableId());
            }

            for(ChannelActivitySnapshot.Row channel: table.rows())
            {
                if(channel.tags() == null || !channel.tags().containsAll(CURRENT_CONTROL_TAGS) ||
                    channel.qualityObservedAtMs() <= 0 || now - channel.qualityObservedAtMs() > 5_000)
                {
                    continue;
                }

                String scope = !table.siteName().isBlank() ? table.siteName() : table.title();
                String label = scope + " · " + formatFrequency(channel.frequencyHz());
                Double health = channel.decodeHealthPercent();
                ControlContinuity previous = mControlContinuityByTable.get(table.tableId());
                long lastValidDecodeMs = Math.max(channel.controlLastValidDecodeMs(),
                    previous != null ? previous.lastValidDecodeMs : 0);
                mControlContinuityByTable.put(table.tableId(), new ControlContinuity(lastValidDecodeMs,
                    channel.frequencyHz(), channel.decoder(), channel.signalDbfs()));
                rows.add(row(table.tableId(), label, health != null ? round(health) : "n/a", "%",
                    health != null && health < 20 ? "warning" : "healthy", "signal=" + channel.signalDbfs() +
                        " dBFS; valid=" + channel.controlValidFrames() + "; invalid=" +
                        channel.controlInvalidFrames() + "; corrected=" + channel.controlCorrectedBits() +
                        "; sync_loss_bits=" + channel.controlSyncLossBits() + "; dropped_bits=" +
                        channel.controlDroppedBits() + "; last_valid_decode_ms=" + lastValidDecodeMs +
                        "; decoder=" + channel.decoder()));
            }

            ControlContinuity continuity = mControlContinuityByTable.get(table.tableId());

            if(table.controlActive() && continuity != null && continuity.lastValidDecodeMs > 0 &&
                now >= continuity.lastValidDecodeMs && now - continuity.lastValidDecodeMs >=
                    CONDITION_HOLD_MILLISECONDS)
            {
                String scopeLabel = !table.title().isBlank() ? table.title() :
                    !table.siteName().isBlank() ? table.siteName() : "Control channel";
                String stableScope = scopeLabel + " (" + table.tableId() + ")";
                long lostForMs = now - continuity.lastValidDecodeMs;
                mIncidents.observe("control-channel-lock-lost", "critical", "Control-channel lock was lost",
                    stableScope, now, 1, "no valid control frame for " + lostForMs + " ms; last frequency=" +
                        formatFrequency(continuity.frequencyHz) + "; signal=" + continuity.signalDbfs +
                        " dBFS; decoder=" + continuity.decoder,
                    "RF loss or interference, USB/sample loss, tuner failure, frequency error, or decoder acquisition failure",
                    "Control messages and traffic grants are not being received",
                    "Check tuner and USB measurements, then signal level, spectrum, decoder mode, and alternate frequencies");
            }
        }

        mControlContinuityByTable.keySet().retainAll(activeTables);

        measurements.add(section("decoders", "Control-channel decode", rows));
    }

    private void collectHost(long now, List<Map<String,Object>> measurements)
    {
        List<Map<String,Object>> rows = new ArrayList<>();
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapMaximum = runtime.maxMemory();
        double heapPercent = heapMaximum > 0 ? 100.0 * heapUsed / heapMaximum : 0;
        double cpuPercent = processCpuPercent();
        long gcCount = 0;
        long gcTimeMs = 0;

        for(GarbageCollectorMXBean bean: ManagementFactory.getGarbageCollectorMXBeans())
        {
            gcCount += Math.max(0, bean.getCollectionCount());
            gcTimeMs += Math.max(0, bean.getCollectionTime());
        }

        long gcIntervalMs = mLastGcCollectionTimeMs >= 0 ? Math.max(0, gcTimeMs - mLastGcCollectionTimeMs) : 0;
        mLastGcCollectionTimeMs = gcTimeMs;
        rows.add(row("host", "Process CPU", Double.isFinite(cpuPercent) ? round(cpuPercent) : "n/a", "%",
            Double.isFinite(cpuPercent) && cpuPercent >= 90 ? "warning" : "healthy",
            "percentage of total host CPU capacity"));
        rows.add(row("host", "JVM heap", round(heapPercent), "%", heapPercent >= 90 ? "critical" :
            heapPercent >= 80 ? "warning" : "healthy", "used=" + heapUsed + " bytes; max=" + heapMaximum +
                " bytes"));
        rows.add(row("host", "Garbage collection", gcIntervalMs, "ms in last sample",
            gcIntervalMs >= 500 ? "warning" : "healthy", "collections=" + gcCount + "; total_pause=" +
                gcTimeMs + " ms"));

        if(sustained("host:cpu", Double.isFinite(cpuPercent) && cpuPercent >= 90, now))
        {
            mIncidents.observe("host-cpu-pressure", "warning", "Receiver host CPU is saturated", "Host", now, 1,
                round(cpuPercent) + "% total CPU", "Too much simultaneous receiver, decoder, diagnostic, or other host work",
                "Receiver queues may miss their deadlines even before a drop is recorded",
                "Close expensive diagnostics and inspect active channels and other host processes");
        }

        if(sustained("host:heap", heapPercent >= 90, now))
        {
            mIncidents.observe("heap-pressure", "critical", "JVM heap is nearly full", "Host", now, 1,
                round(heapPercent) + "% of maximum heap", "Retained backlog, undersized heap, or an unexpected allocation surge",
                "Long garbage-collection pauses can interrupt USB and decoder processing",
                "Inspect queue depths and GC; reduce load or adjust the packaged heap limit");
        }

        if(gcIntervalMs >= 500)
        {
            mIncidents.observe("gc-pause", "warning", "Long garbage-collection activity observed", "Host", now,
                gcTimeMs, gcIntervalMs + " ms in the last sample", "Allocation or heap pressure",
                "Stop-the-world pauses can cause receiver and channel queues to overflow",
                "Correlate with heap, receiver queues, and diagnostic usage");
        }

        if(now - mLastStorageSampleMs >= STORAGE_SAMPLE_INTERVAL_MILLISECONDS)
        {
            mLastStorageSampleMs = now;
            mStorageSnapshot = storageSnapshot();
        }

        rows.add(row("host", "Application disk free", mStorageSnapshot.freePercent >= 0 ?
            round(mStorageSnapshot.freePercent) : "n/a", "%", mStorageSnapshot.freePercent >= 0 &&
            mStorageSnapshot.freePercent < 5 ? "critical" : mStorageSnapshot.freePercent >= 0 &&
            mStorageSnapshot.freePercent < 10 ? "warning" : "healthy", mStorageSnapshot.detail));

        if(mStorageSnapshot.freePercent >= 0 && mStorageSnapshot.freePercent < 10)
        {
            String severity = mStorageSnapshot.freePercent < 5 ? "critical" : "warning";
            mIncidents.observe("disk-space", severity, "Receiver storage is running low", "Application data", now,
                1, round(mStorageSnapshot.freePercent) + "% free", "Recordings, logs, statistics, or other files filled the volume",
                "Recording and statistics writes can fail", "Free space on the application data volume");
        }

        measurements.add(section("host", "Host resources", rows));
    }

    private void collectOutputs(long now, List<Map<String,Object>> measurements)
    {
        List<Map<String,Object>> rows = new ArrayList<>();
        AudioCallCoordinator coordinator = mAudioCallCoordinator;
        AudioRecordingManager recording = mAudioRecordingManager;
        AudioStreamingManager streaming = mAudioStreamingManager;

        if(coordinator != null)
        {
            AudioCallCoordinator.CoordinatorQueueStatus status = coordinator.getQueueStatus();
            long operationDropDelta = observeOutputDrop(now, "audio-coordinator-ingress",
                "Completed-call handoff events were dropped", status.droppedOperations(),
                "Call or lifecycle operations could not enter the bounded coordinator queue");
            long abortedDelta = observeOutputDrop(now, "audio-coordinator-aborted", "Completed calls were aborted",
                status.abortedCalls(), "The coordinator rejected or abandoned calls after bounded-capacity pressure");
            String coordinatorSeverity = operationDropDelta + abortedDelta > 0 ? "warning" :
                queueSeverity(status.ingressDepth(), status.totalIngressCapacity());
            rows.add(row("audio", "Completed-call coordinator", status.ingressDepth(), "calls",
                coordinatorSeverity, "capacity=" +
                    status.totalIngressCapacity() + "; accepted=" + status.acceptedIngress() + "; dropped=" +
                    status.droppedIngress() + "; lifecycle_dropped=" + status.droppedLifecycle() +
                    "; unique_dropped=" + status.droppedOperations() + "; aborted=" + status.abortedCalls()));

            if(sustained("output:audio-pressure", status.totalIngressCapacity() > 0 &&
                status.ingressDepth() * 4 >= status.totalIngressCapacity() * 3, now))
            {
                mIncidents.observe("audio-output-pressure", "warning",
                    "Completed-call coordinator is nearly full", "Audio output", now, status.ingressDepth(),
                    "current=" + status.ingressDepth() + "; capacity=" + status.totalIngressCapacity(),
                    "Recording, streaming, or web completion work is falling behind",
                    "Completed calls may be dropped, but live decoder processing remains isolated",
                    "Inspect recording, streaming, web audio, CPU, and disk I/O");
            }
        }

        if(recording != null)
        {
            AudioRecordingManager.RecordingQueueStatus status = recording.getQueueStatus();
            boolean pressure = status.queuedCalls() * 4 >= status.maximumQueuedCalls() * 3 ||
                status.queuedSourceBytes() * 4 >= status.maximumQueuedSourceBytes() * 3;
            long droppedDelta = observeOutputDrop(now, "recording", "Call recordings were dropped",
                status.droppedRecordings(), "The recording queue or source-byte limit was exhausted");
            rows.add(row("recording", "Recording writer", status.queuedCalls(), "calls",
                droppedDelta > 0 || pressure ? "warning" : status.droppedRecordings() > 0 ? "info" : "healthy",
                "call_capacity=" +
                    status.maximumQueuedCalls() + "; source_bytes=" + status.queuedSourceBytes() + "/" +
                    status.maximumQueuedSourceBytes() + "; accepting=" + status.acceptingCalls() + "; dropped=" +
                    status.droppedRecordings() + "; active=" + status.writerActive() + "; waiting=" +
                    status.waitingDrains()));

            if(sustained("output:recording-pressure", pressure, now))
            {
                mIncidents.observe("recording-output-pressure", "warning", "Recording queue is nearly full",
                    "Recording", now, status.queuedCalls(), "calls=" + status.queuedCalls() + "/" +
                        status.maximumQueuedCalls() + "; bytes=" + status.queuedSourceBytes() + "/" +
                        status.maximumQueuedSourceBytes(), "The recording writer or storage is not keeping up",
                    "Completed-call recordings may be dropped; live decoder processing remains isolated",
                    "Check disk performance, free space, and recording backlog");
            }
        }

        if(streaming != null)
        {
            AudioStreamingManager.StreamingQueueStatus status = streaming.getQueueStatus();
            boolean pressure = status.retainedCalls() * 4 >= status.maximumRetainedCalls() * 3 ||
                status.retainedSourceBytes() * 4 >= status.maximumRetainedSourceBytes() * 3;
            long outputLosses = status.droppedCalls() + status.failedCalls();
            long outputLossDelta = observeOutputDrop(now, "streaming", "Call streaming output was lost",
                outputLosses, "The streaming queue filled or an encoder/writer failed");
            rows.add(row("streaming", "Streaming writer", status.retainedCalls(), "calls",
                outputLossDelta > 0 || pressure ? "warning" : outputLosses > 0 ? "info" : "healthy",
                "call_capacity=" + status.maximumRetainedCalls() + "; source_bytes=" +
                    status.retainedSourceBytes() + "/" + status.maximumRetainedSourceBytes() + "; accepting=" +
                    status.acceptingCalls() + "; dropped=" + status.droppedCalls() + "; failed=" +
                    status.failedCalls() + "; active=" + status.writerActive() + "; waiting=" +
                    status.waitingDrains()));

            if(sustained("output:streaming-pressure", pressure, now))
            {
                mIncidents.observe("streaming-output-pressure", "warning", "Streaming queue is nearly full",
                    "Streaming", now, status.retainedCalls(), "calls=" + status.retainedCalls() + "/" +
                        status.maximumRetainedCalls() + "; bytes=" + status.retainedSourceBytes() + "/" +
                        status.maximumRetainedSourceBytes(), "Streaming encoding or upload work is not keeping up",
                    "Completed-call streams may be dropped; live decoder processing remains isolated",
                    "Check streamer health, network performance, CPU, and backlog");
            }
        }

        measurements.add(section("outputs", "Audio and output queues", rows));
    }

    private void collectSupportingServices(long now, List<Map<String,Object>> measurements)
    {
        List<Map<String,Object>> rows = new ArrayList<>();

        if(mActivityLogService != null)
        {
            P25ActivityLogStatus status = mActivityLogService.getStatus();
            delta("observer:statistics", status.recordsDropped(), now);
            rows.add(row("statistics", "Statistics database writer", status.state().name().toLowerCase(Locale.ROOT),
                "", status.recordsDropped() > 0 ? "info" : "healthy",
                "written=" + status.recordsWritten() +
                    "; dropped=" + status.recordsDropped() + "; last_success=" + status.lastSuccessfulWriteMs()));
        }

        Map<String,Object> webStatus;

        try
        {
            webStatus = mWebStatusSupplier.get();
        }
        catch(RuntimeException exception)
        {
            rows.add(row("web", "Web observer measurements", "unavailable", "", "warning",
                "The web observer changed lifecycle state or did not respond; the next snapshot will retry"));
            measurements.add(section("supporting", "Supporting and observer services", rows));
            return;
        }

        Map<String,Object> server = map(webStatus.get("server"));
        Map<String,Object> transport = map(server.get("liveTransport"));
        long rejected = number(transport.get("rejectedClients"));
        long slow = number(transport.get("slowDisconnects"));
        long dropped = number(transport.get("eventDrops"));
        long observerTotal = rejected + slow + dropped;
        delta("observer:web", observerTotal, now);
        rows.add(row("web", "Web live transport", number(transport.get("activeClients")), "clients",
            observerTotal > 0 ? "info" : "healthy", "rejected=" + rejected +
                "; slow_disconnects=" + slow + "; observer_event_drops=" + dropped));

        Map<String,Object> webPlayer = map(webStatus.get("webPlayer"));
        long webCapacityDrops = number(webPlayer.get("dropped_pending_capacity")) +
            number(webPlayer.get("dropped_encoder_capacity"));
        long webObserverDrops = number(webPlayer.get("dropped_sse_events")) +
            number(webPlayer.get("rejected_listeners"));
        long webAudioDelta = delta("output:web-audio", webCapacityDrops, now);
        delta("observer:web-audio", webObserverDrops, now);
        rows.add(row("web-audio", "Web call audio", number(webPlayer.get("encoder_queue_depth")),
            "encoder queue", webAudioDelta > 0 ? "warning" : webCapacityDrops + webObserverDrops > 0 ?
                "info" : "healthy",
            "published=" + number(webPlayer.get("published_calls")) + "; pending_bytes=" +
                number(webPlayer.get("pending_audio_bytes")) + "; capacity_drops=" + webCapacityDrops +
                "; observer_drops=" + webObserverDrops + "; rejected_audio=" +
                number(webPlayer.get("rejected_audio_responses"))));

        if(webAudioDelta > 0)
        {
            mIncidents.observe("web-audio-drop", "warning", "Web call audio was dropped", "Web audio", now,
                webCapacityDrops, webAudioDelta + " new capacity drops",
                "The bounded web audio encoder or pending-audio queue was saturated",
                "Browser listeners may miss completed calls; receiver decoding remains protected",
                "Reduce listener/encoding pressure and inspect host CPU and heap");
        }

        Map<String,Object> diagnostics = map(webStatus.get("diagnostics"));
        long channelSessions = number(diagnostics.get("channel_sessions"));
        long tunerSessions = number(diagnostics.get("tuner_sessions"));
        rows.add(row("diagnostics", "Receiver diagnostics", channelSessions + tunerSessions, "sessions",
            "info", "channel_sessions=" + channelSessions + "; channel_producers=" +
                number(diagnostics.get("channel_producers")) + "; tuner_sessions=" + tunerSessions +
                "; tuner_producers=" + number(diagnostics.get("tuner_producers")) +
                "; diagnostic data is expendable and should shed before receiver samples"));

        measurements.add(section("supporting", "Supporting and observer services", rows));
    }

    private long observeOutputDrop(long now, String code, String title, long count, String cause)
    {
        long dropDelta = delta("output:" + code, count, now);

        if(dropDelta > 0)
        {
            mIncidents.observe(code, "warning", title, "Audio output", now, count, dropDelta + " new losses",
                cause, "A recording, stream, or completed-call artifact may be missing or incomplete",
                "Inspect the corresponding output queue and writer destination");
        }

        return dropDelta;
    }

    private long delta(String key, long current, long now)
    {
        CounterBaseline previous = mCounterBaselines.put(key, new CounterBaseline(current, now));

        if(previous == null)
        {
            return Math.max(0, current);
        }

        return current >= previous.value ? current - previous.value : 0;
    }

    private boolean sustained(String key, boolean condition, long now)
    {
        mConditionsEvaluatedThisSample.add(key);

        if(!condition)
        {
            mConditionStartTimes.remove(key);
            return false;
        }

        long started = mConditionStartTimes.computeIfAbsent(key, ignored -> now);
        return now - started >= CONDITION_HOLD_MILLISECONDS;
    }

    private StorageSnapshot storageSnapshot()
    {
        if(mUserPreferences == null)
        {
            return StorageSnapshot.unavailable();
        }

        try
        {
            Path root = mUserPreferences.getDirectoryPreference().getDirectoryApplicationRoot();
            FileStore store = Files.getFileStore(root);
            long total = store.getTotalSpace();
            long usable = store.getUsableSpace();
            double percent = total > 0 ? 100.0 * usable / total : -1;
            return new StorageSnapshot(percent, "usable=" + usable + " bytes; total=" + total + " bytes; path=" + root);
        }
        catch(Exception exception)
        {
            return StorageSnapshot.unavailable();
        }
    }

    private static double processCpuPercent()
    {
        java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();

        if(bean instanceof com.sun.management.OperatingSystemMXBean extended)
        {
            double load = extended.getProcessCpuLoad();
            return load >= 0 ? Math.min(100.0, load * 100.0) : Double.NaN;
        }

        return Double.NaN;
    }

    private static String queueSeverity(long depth, long capacity)
    {
        if(capacity <= 0)
        {
            return "healthy";
        }

        double ratio = (double)depth / capacity;
        return ratio >= 1.0 ? "critical" : ratio >= 0.75 ? "warning" : "healthy";
    }

    /** Formats primitive channelizer measurements on the health observer, never on a receiver processing thread. */
    static String channelizerDetail(PolyphaseChannelManager.PipelineStatus pipeline)
    {
        return "high_water=" + pipeline.ifftHighWaterBatches() + "; capacity=" +
            pipeline.ifftCapacityBatches() + "; dropped=" + pipeline.ifftDroppedBatches() +
            "; result_pool=" + pipeline.ifftResultPoolSize() + "/" + pipeline.ifftResultPoolCapacity() +
            " arrays; pool_misses=" + pipeline.ifftResultPoolMisses() + "; new_arrays=" +
            pipeline.ifftResultArrayAllocations() + "; owned_batches=" + pipeline.ifftOwnedBatches() +
            "; owned_high_water=" + pipeline.ifftHighWaterOwnedBatches();
    }

    private static Map<String,Object> section(String id, String title, List<Map<String,Object>> rows)
    {
        return Map.of("id", id, "title", title, "rows", List.copyOf(rows));
    }

    private static Map<String,Object> row(String scope, String label, Object value, String unit, String severity,
                                          String detail)
    {
        LinkedHashMap<String,Object> row = new LinkedHashMap<>();
        row.put("scope", scope != null ? scope : "");
        row.put("label", label != null ? label : "");
        row.put("value", value != null ? value : "");
        row.put("unit", unit != null ? unit : "");
        row.put("severity", severity != null ? severity : "healthy");
        row.put("detail", detail != null ? detail : "");
        return Map.copyOf(row);
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value)
    {
        return value instanceof Map<?,?> map ? (Map<String,Object>)map : Map.of();
    }

    private static long number(Object value)
    {
        return value instanceof Number number ? number.longValue() : 0;
    }

    private static boolean isServiceImpact(Map<String,Object> incident)
    {
        return SERVICE_IMPACT_INCIDENT_CODES.contains(String.valueOf(incident.get("code")));
    }

    private static double round(double value)
    {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String formatFrequency(long frequencyHz)
    {
        return frequencyHz > 0 ? String.format(Locale.US, "%.6f MHz", frequencyHz / 1_000_000.0) : "Unknown frequency";
    }

    private static Map<String,Object> emptySnapshot(long startedAtMs)
    {
        return Map.of("started_at_ms", startedAtMs, "generated_at_ms", startedAtMs,
            "summary", Map.of("severity", "healthy", "active_count", 0, "warning_count", 0,
                "critical_count", 0, "diagnostic_count", 0), "active", List.of(), "resolved", List.of(),
            "measurements", List.of());
    }

    private static ReceiverHealthSnapshotWriter snapshotWriter(UserPreferences userPreferences)
    {
        if(userPreferences == null)
        {
            return null;
        }

        try
        {
            Path path = userPreferences.getDirectoryPreference().getDirectoryApplicationLog()
                .resolve(ReceiverHealthSnapshotWriter.FILE_NAME);
            return new ReceiverHealthSnapshotWriter(path);
        }
        catch(Exception exception)
        {
            LOGGER.warn("Receiver health incident report path is unavailable", exception);
            return null;
        }
    }

    @Override
    public void close()
    {
        if(mClosed.compareAndSet(false, true))
        {
            mExecutor.shutdownNow();

            try
            {
                if(!mExecutor.awaitTermination(2, TimeUnit.SECONDS))
                {
                    LOGGER.warn("Receiver health sampler did not stop within two seconds");
                }
            }
            catch(InterruptedException interruptedException)
            {
                Thread.currentThread().interrupt();
            }
        }
    }

    private record UsbRateBaseline(long timestampMs, long sequence, long expectedBytes, long usableBytes)
    {
    }

    private record CounterBaseline(long value, long lastSeenMs)
    {
    }

    private record ControlContinuity(long lastValidDecodeMs, long frequencyHz, String decoder, Double signalDbfs)
    {
    }

    private record StorageSnapshot(double freePercent, String detail)
    {
        private static StorageSnapshot unavailable()
        {
            return new StorageSnapshot(-1, "unavailable");
        }
    }
}
