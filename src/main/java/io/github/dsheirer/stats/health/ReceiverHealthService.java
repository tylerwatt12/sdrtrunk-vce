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
import io.github.dsheirer.stats.activity.ReceiverActivityService;
import io.github.dsheirer.stats.activity.ReceiverActivityStatus;
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
    private static final long USB_STALE_DELIVERY_MILLISECONDS = 1_000L;
    private static final double USB_LOW_DELIVERY_PERCENT = 90.0;
    private static final double USB_WARNING_DELIVERY_PERCENT = 98.0;
    private static final Set<String> CURRENT_CONTROL_TAGS = Set.of("CURRENT_CONTROL");
    private final UserPreferences mUserPreferences;
    private final TunerManager mTunerManager;
    private final ChannelActivityModel mChannelActivityModel;
    private final ReceiverActivityService mActivityLogService;
    private final LongSupplier mClock;
    private final long mStartedAtMs;
    private final ScheduledExecutorService mExecutor;
    private final ReceiverHealthSnapshotWriter mSnapshotWriter;
    private final ReceiverHealthIncidentTracker mIncidents = new ReceiverHealthIncidentTracker();
    private final Map<String,CounterBaseline> mCounterBaselines = new HashMap<>();
    private final Map<String,Long> mConditionStartTimes = new HashMap<>();
    private final Set<String> mConditionsEvaluatedThisSample = new HashSet<>();
    private final Map<String,UsbDeliveryClassifier> mUsbDeliveryClassifiers = new HashMap<>();
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
                                 ReceiverActivityService activityLogService)
    {
        this(userPreferences, tunerManager, channelProcessingManager, activityLogService,
            System::currentTimeMillis, snapshotWriter(userPreferences));
    }

    ReceiverHealthService(UserPreferences userPreferences, TunerManager tunerManager,
                          ChannelProcessingManager channelProcessingManager,
                          ReceiverActivityService activityLogService, LongSupplier clock)
    {
        this(userPreferences, tunerManager, channelProcessingManager, activityLogService, clock, null);
    }

    ReceiverHealthService(UserPreferences userPreferences, TunerManager tunerManager,
                          ChannelProcessingManager channelProcessingManager,
                          ReceiverActivityService activityLogService, LongSupplier clock,
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
        LinkedHashMap<String,Object> response = new LinkedHashMap<>();
        response.put("started_at_ms", mStartedAtMs);
        response.put("generated_at_ms", now);
        response.put("summary", summarize(active));
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
                mIncidents.observe("tuner-error", "critical", "Tuner stopped working", display, now, 1,
                    discovered.getErrorMessage(), "The USB connection, device access, driver, power, or tuner startup failed",
                    "Channels assigned to this tuner cannot receive radio data",
                    "Open the tuner details, then check its error message, USB cable, driver, and power");
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
                receiverRows.add(row(scope, display + " incoming-data backlog", nativeStatus.queuedMilliseconds(), "ms",
                    queueSeverity(nativeStatus.queuedMilliseconds(), nativeStatus.appliedDurationMilliseconds()),
                    "high_water=" + nativeStatus.highWaterMilliseconds() + " ms; capacity=" +
                        nativeStatus.appliedDurationMilliseconds() + " ms; requested=" +
                        nativeStatus.requestedDurationMilliseconds() + " ms; dropped=" +
                        nativeStatus.droppedBuffers() + " buffers / " + nativeStatus.droppedMilliseconds() + " ms"));
                long nativeDropDelta = delta(scope + ":native-drops", nativeStatus.droppedBuffers(), now);

                if(nativeDropDelta > 0)
                {
                    mIncidents.observe("receiver-iq-drop", "critical", "Radio data was lost before decoding", display,
                        now, nativeStatus.droppedBuffers(), nativeDropDelta + " new buffers; " +
                            nativeStatus.droppedMilliseconds() + " ms discarded since start",
                        "The tuner delivered data faster than sdrtrunk-vce could process it",
                        "Every channel using this tuner may lose sync or have missing audio",
                        "Check the USB connection, computer load, memory-cleanup activity, and incoming radio-data backlog");
                }

                if(sustained(scope + ":native-pressure",
                    nativeStatus.appliedDurationMilliseconds() > 0 && nativeStatus.queuedMilliseconds() * 4 >=
                        nativeStatus.appliedDurationMilliseconds() * 3, now))
                {
                    mIncidents.observe("receiver-queue-pressure", "warning", "Receiver processing is falling behind",
                        display, now, nativeStatus.highWaterMilliseconds(), "current=" +
                            nativeStatus.queuedMilliseconds() + " ms; capacity=" +
                            nativeStatus.appliedDurationMilliseconds() + " ms",
                        "Radio data is arriving faster than sdrtrunk-vce can process it",
                        "If this continues, every channel using this tuner may lose data",
                        "Check USB delivery, computer load, memory-cleanup activity, channel separation, and the number of active channels");
                }

                PolyphaseChannelManager.PipelineStatus pipeline = polyphase.getPipelineStatus();
                channelizerRows.add(row(scope, display + " channel-separation backlog", pipeline.ifftQueuedBatches(), "batches",
                    queueSeverity(pipeline.ifftQueuedBatches(), pipeline.ifftCapacityBatches()),
                    channelizerDetail(pipeline)));
                long ifftDropDelta = delta(scope + ":ifft-drops", pipeline.ifftDroppedBatches(), now);

                if(ifftDropDelta > 0)
                {
                    mIncidents.observe("channelizer-drop", "critical", "Channels lost radio data",
                        display, now, pipeline.ifftDroppedBatches(), ifftDropDelta + " new channelizer batches",
                        "sdrtrunk-vce ran out of room while separating this tuner's data into channels",
                        "Every channel using this tuner may have missing radio data",
                        "Check computer load, memory-cleanup activity, and per-channel backlogs; close unused diagnostic views or reduce active channels");
                }

                if(sustained(scope + ":ifft-pressure", pipeline.ifftCapacityBatches() > 0 &&
                    pipeline.ifftQueuedBatches() * 4 >= pipeline.ifftCapacityBatches() * 3, now))
                {
                    mIncidents.observe("channelizer-queue-pressure", "warning",
                        "Channel separation is falling behind", display, now, pipeline.ifftHighWaterBatches(),
                        "current=" + pipeline.ifftQueuedBatches() + "; capacity=" +
                            pipeline.ifftCapacityBatches(), "Channel separation is close to falling behind",
                        "Every channel using this tuner may lose data if this continues",
                        "Check computer load and memory-cleanup activity, then close unused diagnostic views or reduce active channels");
                }

                long aggregateChannelDrops = pipeline.channelDroppedBatches();

                for(PolyphaseChannelManager.ChannelQueueStatus channel: pipeline.channels())
                {
                    String channelScope = scope + ":" + channel.frequencyHz();

                    if(sustained(channelScope + ":pressure", channel.capacityBatches() > 0 &&
                        channel.queuedBatches() * 4 >= channel.capacityBatches() * 3, now))
                    {
                        mIncidents.observe("channel-queue-pressure", "warning",
                            "One channel is falling behind", display + " · " +
                                formatFrequency(channel.frequencyHz()), now,
                            channel.highWaterBatches(), "current=" + channel.queuedBatches() + "; capacity=" +
                                channel.capacityBatches() + "; tuner=" + display,
                            "Processing for this channel is not keeping up",
                            "The listed control or voice channel may lose decoding or audio",
                            "Check computer load and the work associated with this channel");
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
                    mIncidents.observe("channel-output-drop", "critical", "One or more channels lost radio data",
                        display, now, aggregateChannelDrops, channelDropDelta + " new channel batches",
                        "One or more channel decoders could not keep up",
                        "Affected control or voice channels may lose decoding or have broken audio",
                        "Check the per-channel measurements and computer load; reduce the number of active channels if needed");
                }
            }
            }
            catch(RuntimeException exception)
            {
                tunerRows.add(row(fallbackScope, "Tuner status is temporarily unavailable", "unavailable", "", "warning",
                    "The tuner changed state while its status was being checked (" +
                        exception.getClass().getSimpleName() + "); the next update will try again"));
            }
        }

        mUsbDeliveryClassifiers.keySet().retainAll(activeUsbScopes);

        if(mTunerManager != null)
        {
            TunerManager.TunerAllocationStatus allocation = mTunerManager.getTunerAllocationStatus();
            long failureDelta = delta("tuner:allocation-failures", allocation.failures(), now);
            tunerRows.add(row("allocations", "Channel start requests", allocation.successes(), "successful",
                failureDelta > 0 ? "warning" : allocation.failures() > 0 ? "info" : "healthy",
                "requests=" + allocation.requests() +
                    "; failures=" + allocation.failures()));

            if(failureDelta > 0)
            {
                mIncidents.observe("tuner-allocation-failure", "warning", "No tuner was available for a channel",
                    "Tuner allocation", now, allocation.failures(), failureDelta + " new failed allocation(s)",
                    "No enabled tuner could receive the requested frequency, an in-use tuner had no room, Lock Center Frequency prevented retuning, or the tuner changed state during the request",
                    "A conventional channel, control channel, or voice channel may not start",
                    "Check enabled tuner frequency ranges, Lock Center Frequency, Preferred Tuner settings, and the number of active channels");
            }
        }

        measurements.add(section("tuners", "Tuners", tunerRows));
        measurements.add(section("usb", "USB tuner connection", usbRows));
        measurements.add(section("receiver-queues", "Incoming radio data", receiverRows));
        measurements.add(section("channelizer", "Channel separation", channelizerRows));
        measurements.add(section("channels", "Per-channel processing", channelRows));
    }

    private void collectUsb(long now, String scope, String display, Tuner tuner,
                            USBTunerController.UsbTransferHealthSnapshot usb,
                            List<Map<String,Object>> rows)
    {
        TunerController controller = tuner.getTunerController();
        double requiredBytesPerSecond = usb.streaming() && controller != null ?
            controller.getSampleRate() * usb.sampleFrameSizeBytes() : 0;
        long transferStatusCount = usb.errorTransferCount() + usb.stalledTransferCount() +
            usb.timedOutTransferCount() + usb.cancelledTransferCount() + usb.unexpectedStatusTransferCount() +
            usb.submissionFailureCount();
        long bufferFailureCount = usb.nativeIngressCopyFailures() + usb.nativeIngressConversionFailures();
        long integrityCount = usb.shortTransferCount() + usb.zeroLengthTransferCount() +
            usb.malformedTransferCount() + transferStatusCount + bufferFailureCount;
        long lastDelivery = usb.lastTransferTimestampMilliseconds() > 0 ?
            usb.lastTransferTimestampMilliseconds() : usb.streamStartedTimestampMilliseconds();
        long ingressLossCount = usb.nativeIngressSaturationDroppedBuffers();
        long ingressLossDelta = delta(scope + ":receiver-ingress-loss", ingressLossCount, now);
        long listenerFailureDelta = delta(scope + ":receiver-listener-failure",
            usb.nativeIngressListenerFailures(), now);
        UsbDeliveryAssessment assessment = mUsbDeliveryClassifiers.computeIfAbsent(scope,
            ignored -> new UsbDeliveryClassifier()).evaluate(new UsbDeliveryObservation(now, usb.streaming(),
                usb.streamSequence(), usb.expectedBytes(), usb.usableBytes(), transferStatusCount, integrityCount,
                usb.longTransferGapCount(), lastDelivery, requiredBytesPerSecond));
        double seconds = assessment.windowMilliseconds() / 1_000.0;
        double callbackBytesPerSecond = assessment.rateAvailable() && seconds > 0 ?
            assessment.expectedBytesDelta() / seconds : 0;
        double usableBytesPerSecond = assessment.rateAvailable() && seconds > 0 ?
            assessment.usableBytesDelta() / seconds : 0;
        double displayDeliveryPercent = assessment.rateAvailable() ?
            Math.min(100.0, assessment.rawDeliveryPercent()) : 100.0;
        String rateSeverity = assessment.rateCritical() ? "critical" :
            assessment.rateMeasurementWarning() ? "warning" : "healthy";
        String aggregateDetail = Double.isFinite(assessment.twoWindowDeliveryPercent()) ?
            "; two_window_delivery=" + round(assessment.twoWindowDeliveryPercent()) + "%" : "";
        rows.add(row(scope, display + " radio data rate", round(usableBytesPerSecond / 1_000_000.0), "MB/s",
            rateSeverity, "required=" + round(requiredBytesPerSecond / 1_000_000.0) +
                " MB/s; callbacks=" + round(callbackBytesPerSecond / 1_000_000.0) + " MB/s; delivery=" +
                (assessment.rateAvailable() ? round(displayDeliveryPercent) + "%" : "warming up") +
                (assessment.rateAvailable() ? "; raw_window_delivery=" +
                    round(assessment.rawDeliveryPercent()) + "%" : "") + aggregateDetail + "; window_ms=" +
                assessment.windowMilliseconds() + "; nominal_bytes=" + round(assessment.nominalBytes()) +
                "; expected_bytes=" + assessment.expectedBytesDelta() + "; usable_bytes=" +
                assessment.usableBytesDelta() + "; streaming=" + usb.streaming() + "; tuner_peak_payload=" +
                round(tuner.getMaximumUSBBitsPerSecond() / 1_000_000.0) + " Mbit/s"));
        rows.add(row(scope, display + " USB transfer results", usb.transferCount(), "transfers",
            assessment.statusDelta() > 0 ? "warning" : transferStatusCount > 0 ? "info" : "healthy", "completed=" +
                usb.completedTransferCount() + "; stall=" +
                usb.stalledTransferCount() + "; timeout=" + usb.timedOutTransferCount() + "; error=" +
                usb.errorTransferCount() + "; cancelled=" + usb.cancelledTransferCount() + "; unexpected=" +
                usb.unexpectedStatusTransferCount()));
        rows.add(row(scope, display + " active USB transfers", usb.activeTransferCount(), "active transfers",
            usb.retryTransferCount() > 0 ? "warning" : "healthy", "pool=" + usb.transferPoolSize() +
                "; retrying=" + usb.retryTransferCount() + "; submission_failures=" +
                usb.submissionFailureCount()));
        rows.add(row(scope, display + " USB connection speed", usb.negotiatedDeviceSpeed(), "",
            "info", "device_speed_code=" + usb.negotiatedDeviceSpeedCode() +
                "; this is the tuner link speed, not the complete upstream hub/root-controller capacity"));
        rows.add(row(scope, display + " USB data errors", usb.shortTransferCount(), "short transfers",
            assessment.integrityDelta() > 0 ? "critical" : integrityCount > 0 ? "info" : "healthy",
            "zero=" + usb.zeroLengthTransferCount() +
                "; malformed=" + usb.malformedTransferCount() + "; missing=" + usb.estimatedMissingBytes() +
                " bytes; unreliable_payload=" + usb.unusableBytes() + " bytes; copy_failures=" +
                usb.nativeIngressCopyFailures() + "; conversion_failures=" +
                usb.nativeIngressConversionFailures()));
        rows.add(row(scope, display + " USB data pauses", usb.lastInterTransferGapMilliseconds(), "ms",
            assessment.gapDelta() > 0 ? "warning" : usb.longTransferGapCount() > 0 ? "info" : "healthy",
            "worst=" + usb.worstInterTransferGapMilliseconds() + " ms; expected_transfer=" +
                usb.expectedTransferLengthBytes() + " bytes; long_gaps=" + usb.longTransferGapCount()));
        boolean ingressPressure = usb.nativeIngressCapacity() > 0 &&
            usb.nativeIngressDepth() * 4 >= usb.nativeIngressCapacity() * 3;
        rows.add(row(scope, display + " radio data waiting after USB", usb.nativeIngressDepth(), "buffers",
            ingressLossDelta > 0 ? "critical" : listenerFailureDelta > 0 || ingressPressure ? "warning" :
                ingressLossCount > 0 || usb.nativeIngressListenerFailures() > 0 ? "info" : "healthy",
            "capacity=" + usb.nativeIngressCapacity() + "; high_water=" +
                usb.nativeIngressHighWaterDepth() + "; dropped=" +
                usb.nativeIngressSaturationDroppedBuffers() + "; dropped_samples=" +
                usb.nativeIngressSaturationDroppedSamples() + "; callback_to_resubmit_ms=" +
                round(usb.lastCallbackToResubmitDurationNanoseconds() / 1_000_000.0) + "; worst_ms=" +
                round(usb.worstCallbackToResubmitDurationNanoseconds() / 1_000_000.0) +
                "; queue_delay_ms=" + round(usb.nativeIngressLastQueueDelayNanoseconds() / 1_000_000.0) +
                "; worst_queue_ms=" +
                round(usb.nativeIngressWorstQueueDelayNanoseconds() / 1_000_000.0) +
                "; listener_failures=" + usb.nativeIngressListenerFailures()));

        if(ingressLossDelta > 0)
        {
            mIncidents.observe("receiver-ingress-drop", "critical",
                "Radio data was lost entering the receiver", display, now, ingressLossCount,
                ingressLossDelta + " new dropped native buffers; dropped_samples=" +
                    usb.nativeIngressSaturationDroppedSamples(),
                "Receiver processing could not accept data from the USB tuner quickly enough",
                "The USB connection stayed responsive, but live radio data was lost",
                "Check the incoming radio-data backlog and close unused diagnostic views before changing queue limits");
        }

        if(listenerFailureDelta > 0)
        {
            mIncidents.observe("receiver-listener-failure", "warning",
                "A receiver component missed radio data", display, now,
                usb.nativeIngressListenerFailures(), listenerFailureDelta + " new failed listener delivery attempt(s)",
                "A receiver or diagnostic component reported an error while accepting radio data",
                "That component may miss data; other receiver components continue",
                "Check the application log for the affected component and its error");
        }

        if(assessment.hardLoss())
        {
            mIncidents.observe("usb-sample-loss", "critical", "USB tuner data is incomplete", display,
                now, Math.max(1, integrityCount), assessment.integrityDelta() +
                    " new transfer integrity events; status_events=" + assessment.statusDelta() +
                    "; last_callback_age_ms=" + assessment.lastDeliveryAgeMilliseconds() + "; delivery=" +
                    (assessment.rateAvailable() ? round(displayDeliveryPercent) + "%" : "warming up") +
                    "; missing=" + usb.estimatedMissingBytes() + " bytes; copy_failures=" +
                    usb.nativeIngressCopyFailures() + "; conversion_failures=" +
                    usb.nativeIngressConversionFailures(),
                "The USB connection, cable, power, driver, computer load, or tuner may be interrupting radio data",
                "Signal strength may still look normal while channels using this tuner lose decoding or have gaps in audio",
                "If possible, connect high-rate tuners to separate USB controllers; then check the cable, power, and connection speed");
        }
        else if(assessment.rateCritical() || assessment.rateWarning())
        {
            String severity = assessment.rateCritical() ? "critical" : "warning";
            String title = assessment.gapCorrelated() ? "USB tuner data stopped arriving briefly" :
                assessment.rateCritical() ? "USB tuner data is arriving too slowly" :
                    "USB tuner data arrived too slowly for a short time";
            String aggregate = Double.isFinite(assessment.twoWindowDeliveryPercent()) ?
                "; two_window_delivery=" + round(assessment.twoWindowDeliveryPercent()) + "%" : "";
            mIncidents.observe("usb-delivery-rate-low", severity, title, display, now,
                Math.max(1, assessment.lowRateWindowCount()), "window_delivery=" +
                    round(assessment.rawDeliveryPercent()) + "%" + aggregate + "; window_ms=" +
                    assessment.windowMilliseconds() + "; usable=" + assessment.usableBytesDelta() +
                    " bytes; nominal=" + round(assessment.nominalBytes()) + " bytes; new_long_gaps=" +
                    assessment.gapDelta(),
                "A pause in USB data, high computer load, or the timing of this check can make the measured rate look low",
                assessment.rateCritical() ? "The slowdown lasted long enough to put live decoding at risk" :
                    "One brief slowdown does not necessarily mean that radio data was lost",
                "Compare this with USB data errors, USB pauses, incoming radio-data loss, and the next status update");
        }
        else if(assessment.gapDelta() > 0)
        {
            mIncidents.observe("usb-transfer-gap", "warning", "USB tuner data paused", display, now,
                usb.longTransferGapCount(), assessment.gapDelta() + " new gap(s) of at least 200 ms; latest=" +
                    usb.lastInterTransferGapMilliseconds() + " ms; worst=" +
                    usb.worstInterTransferGapMilliseconds() + " ms",
                "The USB connection, tuner, or computer briefly delayed radio data",
                "A long pause can interrupt the control channel or clip call audio",
                "Watch for repeated pauses together with incoming radio-data or decoder losses");
        }

        if(sustained(scope + ":usb-pool-degraded", usb.streaming() && usb.retryTransferCount() > 0, now))
        {
            mIncidents.observe("usb-transfer-pool-degraded", "warning",
                "USB tuner has reduced transfer capacity", display, now, usb.retryTransferCount(),
                "active=" + usb.activeTransferCount() + "/" + usb.transferPoolSize() + "; retrying=" +
                    usb.retryTransferCount() + "; submission_failures=" + usb.submissionFailureCount(),
                "One or more USB transfers could not restart",
                "With fewer transfers running, data pauses and control-channel loss are more likely",
                "Check USB capacity, hub or controller placement, cable, power, and driver stability");
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
                mIncidents.observe("control-channel-lock-lost", "critical", "Control channel stopped decoding",
                    stableScope, now, 1, "no valid control frame for " + lostForMs + " ms; last frequency=" +
                        formatFrequency(continuity.frequencyHz) + "; signal=" + continuity.signalDbfs +
                        " dBFS; decoder=" + continuity.decoder,
                    "The signal may be weak or interrupted, USB or tuner data may be missing, the frequency may be wrong, or the decoder may not have locked on",
                    "The receiver is not getting control messages or voice-channel assignments",
                    "Check the tuner and USB status, then the signal level, spectrum, decoder type, and alternate control frequencies");
            }
        }

        mControlContinuityByTable.keySet().retainAll(activeTables);

        measurements.add(section("decoders", "Control channel", rows));
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
        rows.add(row("host", "sdrtrunk-vce processor use", Double.isFinite(cpuPercent) ? round(cpuPercent) : "n/a", "%",
            Double.isFinite(cpuPercent) && cpuPercent >= 90 ? "warning" : "healthy",
            "share of the computer's total processor capacity"));
        rows.add(row("host", "sdrtrunk-vce memory use", round(heapPercent), "%", heapPercent >= 90 ? "critical" :
            heapPercent >= 80 ? "warning" : "healthy", "used=" + heapUsed + " bytes; max=" + heapMaximum +
                " bytes"));
        rows.add(row("host", "Time spent freeing memory", gcIntervalMs, "ms in last sample",
            gcIntervalMs >= 500 ? "warning" : "healthy", "collections=" + gcCount + "; total_pause=" +
                gcTimeMs + " ms"));

        if(sustained("host:cpu", Double.isFinite(cpuPercent) && cpuPercent >= 90, now))
        {
            mIncidents.observe("host-cpu-pressure", "warning", "Computer is overloaded", "Host", now, 1,
                round(cpuPercent) + "% total CPU", "Too much receiver, decoder, diagnostic, or other work is running at once",
                "Radio-data processing may fall behind even before any loss is recorded",
                "Close unused diagnostic views, review active channels, and check other programs using the processor");
        }

        if(sustained("host:heap", heapPercent >= 90, now))
        {
            mIncidents.observe("heap-pressure", "critical", "sdrtrunk-vce is low on memory", "Host", now, 1,
                round(heapPercent) + "% of maximum heap",
                "Waiting output work, too little memory assigned to the app, or an unexpected increase in memory use",
                "Long memory-cleanup pauses can interrupt USB data and channel decoding",
                "Check call-output and radio-data backlogs, then reduce load or increase the app's memory limit");
        }

        if(gcIntervalMs >= 500)
        {
            mIncidents.observe("gc-pause", "warning", "sdrtrunk-vce spent extra time freeing memory", "Host", now,
                gcTimeMs, gcIntervalMs + " ms in the last sample", "High memory use or a burst of activity",
                "This extra work can make incoming radio data and channel processing fall behind",
                "Compare this with memory use, incoming radio-data backlogs, and open diagnostic views");
        }

        if(now - mLastStorageSampleMs >= STORAGE_SAMPLE_INTERVAL_MILLISECONDS)
        {
            mLastStorageSampleMs = now;
            mStorageSnapshot = storageSnapshot();
        }

        rows.add(row("host", "Free storage space", mStorageSnapshot.freePercent >= 0 ?
            round(mStorageSnapshot.freePercent) : "n/a", "%", mStorageSnapshot.freePercent >= 0 &&
            mStorageSnapshot.freePercent < 5 ? "critical" : mStorageSnapshot.freePercent >= 0 &&
            mStorageSnapshot.freePercent < 10 ? "warning" : "healthy", mStorageSnapshot.detail));

        if(mStorageSnapshot.freePercent >= 0 && mStorageSnapshot.freePercent < 10)
        {
            String severity = mStorageSnapshot.freePercent < 5 ? "critical" : "warning";
            mIncidents.observe("disk-space", severity, "Storage space is low", "Application data", now,
                1, round(mStorageSnapshot.freePercent) + "% free",
                "Application data or other files are using most of the space on the drive",
                "Statistics and any recordings stored on this drive may not be saved",
                "Free up space on the drive that holds the application data");
        }

        measurements.add(section("host", "Computer resources", rows));
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
                "A call could not finish all output steps", status.droppedOperations(),
                "The app could not queue part of the work needed to finish a call for recording, streaming, or browser audio");
            long abortedDelta = observeOutputDrop(now, "audio-coordinator-aborted",
                "Output processing stopped for a call", status.abortedCalls(),
                "Output processing was overloaded and stopped handling the call");
            String coordinatorSeverity = operationDropDelta + abortedDelta > 0 ? "warning" :
                queueSeverity(status.ingressDepth(), status.totalIngressCapacity());
            rows.add(row("audio", "Call output work waiting", status.ingressDepth(), "items",
                coordinatorSeverity, "capacity=" +
                    status.totalIngressCapacity() + "; accepted=" + status.acceptedIngress() + "; dropped=" +
                    status.droppedIngress() + "; lifecycle_dropped=" + status.droppedLifecycle() +
                    "; unique_dropped=" + status.droppedOperations() + "; aborted=" + status.abortedCalls()));

            if(sustained("output:audio-pressure", status.totalIngressCapacity() > 0 &&
                status.ingressDepth() * 4 >= status.totalIngressCapacity() * 3, now))
            {
                mIncidents.observe("audio-output-pressure", "warning",
                    "Call outputs are falling behind", "Audio output", now, status.ingressDepth(),
                    "current=" + status.ingressDepth() + "; capacity=" + status.totalIngressCapacity(),
                    "Recording, streaming, or browser-audio work is falling behind",
                    "Completed calls may be missed by these outputs, but live receiving continues",
                    "Check recording, streaming, browser audio, processor use, and disk activity");
            }
        }

        if(recording != null)
        {
            AudioRecordingManager.RecordingQueueStatus status = recording.getQueueStatus();
            boolean pressure = status.queuedCalls() * 4 >= status.maximumQueuedCalls() * 3 ||
                status.queuedSourceBytes() * 4 >= status.maximumQueuedSourceBytes() * 3;
            long droppedDelta = observeOutputDrop(now, "recording", "A call recording was not saved",
                status.droppedRecordings(), "The recording queue was full or had too much audio data waiting");
            rows.add(row("recording", "Calls waiting to be recorded", status.queuedCalls(), "calls",
                droppedDelta > 0 || pressure ? "warning" : status.droppedRecordings() > 0 ? "info" : "healthy",
                "call_capacity=" +
                    status.maximumQueuedCalls() + "; source_bytes=" + status.queuedSourceBytes() + "/" +
                    status.maximumQueuedSourceBytes() + "; accepting=" + status.acceptingCalls() + "; dropped=" +
                    status.droppedRecordings() + "; active=" + status.writerActive() + "; waiting=" +
                    status.waitingDrains()));

            if(sustained("output:recording-pressure", pressure, now))
            {
                mIncidents.observe("recording-output-pressure", "warning", "Saving recordings is falling behind",
                    "Recording", now, status.queuedCalls(), "calls=" + status.queuedCalls() + "/" +
                        status.maximumQueuedCalls() + "; bytes=" + status.queuedSourceBytes() + "/" +
                        status.maximumQueuedSourceBytes(), "Saving recordings or writing to the drive is not keeping up",
                    "Some completed-call recordings may not be saved, but live receiving continues",
                    "Check drive performance, free space, and calls waiting to be recorded");
            }
        }

        if(streaming != null)
        {
            AudioStreamingManager.StreamingQueueStatus status = streaming.getQueueStatus();
            boolean pressure = status.retainedCalls() * 4 >= status.maximumRetainedCalls() * 3 ||
                status.retainedSourceBytes() * 4 >= status.maximumRetainedSourceBytes() * 3;
            long outputLosses = status.droppedCalls() + status.failedCalls();
            long outputLossDelta = observeOutputDrop(now, "streaming", "A call was not sent to the streaming service",
                outputLosses, "The streaming queue was full, or encoding or delivery failed");
            rows.add(row("streaming", "Calls waiting to be streamed", status.retainedCalls(), "calls",
                outputLossDelta > 0 || pressure ? "warning" : outputLosses > 0 ? "info" : "healthy",
                "call_capacity=" + status.maximumRetainedCalls() + "; source_bytes=" +
                    status.retainedSourceBytes() + "/" + status.maximumRetainedSourceBytes() + "; accepting=" +
                    status.acceptingCalls() + "; dropped=" + status.droppedCalls() + "; failed=" +
                    status.failedCalls() + "; active=" + status.writerActive() + "; waiting=" +
                    status.waitingDrains()));

            if(sustained("output:streaming-pressure", pressure, now))
            {
                mIncidents.observe("streaming-output-pressure", "warning", "Streaming is falling behind",
                    "Streaming", now, status.retainedCalls(), "calls=" + status.retainedCalls() + "/" +
                        status.maximumRetainedCalls() + "; bytes=" + status.retainedSourceBytes() + "/" +
                        status.maximumRetainedSourceBytes(),
                    "Encoding or sending calls to the streaming service is not keeping up",
                    "Some completed calls may not be streamed, but live receiving continues",
                    "Check the streaming service, network connection, processor use, and calls waiting to be streamed");
            }
        }

        measurements.add(section("outputs", "Recordings, streams, and browser audio", rows));
    }

    private void collectSupportingServices(long now, List<Map<String,Object>> measurements)
    {
        List<Map<String,Object>> rows = new ArrayList<>();

        if(mActivityLogService != null)
        {
            ReceiverActivityStatus status = mActivityLogService.getStatus();
            delta("observer:statistics", status.recordsDropped(), now);
            rows.add(row("statistics", "Statistics saving", status.state().name().toLowerCase(Locale.ROOT),
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
            rows.add(row("web", "Web status details", "unavailable", "", "warning",
                "The web service changed state or did not respond. The next status update will try again."));
            measurements.add(section("supporting", "Web and statistics services", rows));
            return;
        }

        Map<String,Object> server = map(webStatus.get("server"));
        Map<String,Object> transport = map(server.get("live_transport"));
        long rejected = number(transport.get("rejected_clients"));
        long slow = number(transport.get("slow_disconnects"));
        long dropped = number(transport.get("event_drops"));
        long observerTotal = rejected + slow + dropped;
        delta("observer:web", observerTotal, now);
        rows.add(row("web", "Live web connections", number(transport.get("active_clients")), "clients",
            observerTotal > 0 ? "info" : "healthy", "rejected=" + rejected +
                "; slow_disconnects=" + slow + "; observer_event_drops=" + dropped));

        Map<String,Object> webPlayer = map(webStatus.get("web_player"));
        long webCapacityDrops = number(webPlayer.get("dropped_encoder_capacity"));
        long webEncoderFailures = number(webPlayer.get("encoder_failures"));
        long webAudioLosses = webCapacityDrops + webEncoderFailures;
        long webObserverDrops = number(webPlayer.get("rejected_feeds"));
        long webAudioDelta = delta("output:web-audio", webAudioLosses, now);
        delta("observer:web-audio", webObserverDrops, now);
        rows.add(row("web-audio", "Browser audio", number(webPlayer.get("encoder_queue_depth")),
            "encoder queue", webAudioDelta > 0 ? "warning" : webAudioLosses + webObserverDrops > 0 ?
            "info" : "healthy",
            "published=" + number(webPlayer.get("published_calls")) + "; active_feeds=" +
                number(webPlayer.get("active_feeds")) + "; capacity_drops=" + webCapacityDrops +
                "; encoder_failures=" + webEncoderFailures + "; rejected_feeds=" + webObserverDrops +
                "; rejected_audio=" +
                number(webPlayer.get("rejected_audio_responses"))));

        if(webAudioDelta > 0)
        {
            mIncidents.observe("web-audio-drop", "warning", "Browser audio was not available for a call", "Web audio", now,
                webAudioLosses, webAudioDelta + " new dropped or failed browser calls",
                "The browser-audio queue was full or the call could not be encoded",
                "Browser listeners may miss the completed call, but live receiving continues",
                "Check processor and memory use and the application log; reduce browser-audio demand if calls keep being skipped");
        }

        Map<String,Object> diagnostics = map(webStatus.get("diagnostics"));
        long channelSessions = number(diagnostics.get("channel_sessions"));
        long tunerSessions = number(diagnostics.get("tuner_sessions"));
        rows.add(row("diagnostics", "Open diagnostic views", channelSessions + tunerSessions, "sessions",
            "info", "channel_sessions=" + channelSessions + "; channel_producers=" +
                number(diagnostics.get("channel_producers")) + "; tuner_sessions=" + tunerSessions +
                "; tuner_producers=" + number(diagnostics.get("tuner_producers")) +
                "; diagnostic updates may be skipped to protect live receiving"));

        measurements.add(section("supporting", "Web and statistics services", rows));
    }

    private long observeOutputDrop(long now, String code, String title, long count, String cause)
    {
        long dropDelta = delta("output:" + code, count, now);

        if(dropDelta > 0)
        {
            mIncidents.observe(code, "warning", title, "Audio output", now, count, dropDelta + " new losses",
                cause, "The call's intended output may be missing or incomplete",
                "Check the matching output status and its destination");
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
            pipeline.ifftCapacityBatches() + "; pipeline_dropped=" + pipeline.ifftDroppedBatches();
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

    static Map<String,Object> summarize(List<Map<String,Object>> active)
    {
        long critical = active.stream().filter(incident -> "critical".equals(incident.get("severity"))).count();
        long warning = active.size() - critical;
        String severity = critical > 0 ? "critical" : warning > 0 ? "warning" : "healthy";
        LinkedHashMap<String,Object> summary = new LinkedHashMap<>();
        summary.put("severity", severity);
        summary.put("active_count", active.size());
        summary.put("warning_count", warning);
        summary.put("critical_count", critical);
        return Map.copyOf(summary);
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
                "critical_count", 0), "active", List.of(), "resolved", List.of(),
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

    static final class UsbDeliveryClassifier
    {
        private UsbDeliveryBaseline mBaseline;
        private UsbRateWindow mPreviousRateWindow;
        private boolean mPreviousRateWindowLow;
        private boolean mPreviousWindowHadGap;
        private long mLowRateWindowCount;

        UsbDeliveryAssessment evaluate(UsbDeliveryObservation observation)
        {
            if(!observation.streaming())
            {
                reset();
                return UsbDeliveryAssessment.warming(false, 0);
            }

            long lastDeliveryAge = observation.lastDeliveryTimestampMs() > 0 &&
                observation.nowMs() >= observation.lastDeliveryTimestampMs() ?
                observation.nowMs() - observation.lastDeliveryTimestampMs() : 0;
            boolean stale = observation.lastDeliveryTimestampMs() > 0 &&
                lastDeliveryAge > USB_STALE_DELIVERY_MILLISECONDS;
            boolean baselineUsable = mBaseline != null && mBaseline.sequence() == observation.sequence() &&
                observation.nowMs() > mBaseline.timestampMs() && observation.expectedBytes() >=
                mBaseline.expectedBytes() && observation.usableBytes() >= mBaseline.usableBytes() &&
                observation.statusCount() >= mBaseline.statusCount() && observation.integrityCount() >=
                mBaseline.integrityCount() && observation.longGapCount() >= mBaseline.longGapCount();

            if(!baselineUsable)
            {
                setBaseline(observation);
                resetRateWindow();
                return UsbDeliveryAssessment.warming(stale, lastDeliveryAge);
            }

            long windowMilliseconds = observation.nowMs() - mBaseline.timestampMs();
            long expectedBytesDelta = observation.expectedBytes() - mBaseline.expectedBytes();
            long usableBytesDelta = observation.usableBytes() - mBaseline.usableBytes();
            long statusDelta = observation.statusCount() - mBaseline.statusCount();
            long integrityDelta = observation.integrityCount() - mBaseline.integrityCount();
            long gapDelta = observation.longGapCount() - mBaseline.longGapCount();
            setBaseline(observation);
            boolean rateAvailable = observation.requiredBytesPerSecond() > 0 && windowMilliseconds > 0;

            if(!rateAvailable)
            {
                resetRateWindow();
                return new UsbDeliveryAssessment(false, windowMilliseconds, expectedBytesDelta, usableBytesDelta,
                    0, Double.NaN, Double.NaN, statusDelta, integrityDelta, gapDelta, stale, false, false,
                    false, mLowRateWindowCount, lastDeliveryAge);
            }

            double nominalBytes = observation.requiredBytesPerSecond() * windowMilliseconds / 1_000.0;
            double rawDeliveryPercent = nominalBytes > 0 ? 100.0 * usableBytesDelta / nominalBytes : 0;
            boolean currentWindowLow = rawDeliveryPercent < USB_LOW_DELIVERY_PERCENT;
            boolean gapCorrelated = (currentWindowLow && (gapDelta > 0 || mPreviousWindowHadGap)) ||
                (gapDelta > 0 && mPreviousRateWindowLow);
            double twoWindowDeliveryPercent = Double.NaN;
            boolean aggregateLow = false;

            if(mPreviousRateWindowLow && mPreviousRateWindow != null)
            {
                double aggregateNominalBytes = mPreviousRateWindow.nominalBytes() + nominalBytes;

                if(aggregateNominalBytes > 0)
                {
                    twoWindowDeliveryPercent = 100.0 *
                        (mPreviousRateWindow.usableBytes() + usableBytesDelta) / aggregateNominalBytes;
                    aggregateLow = twoWindowDeliveryPercent < USB_LOW_DELIVERY_PERCENT;
                }
            }

            boolean rateCritical = aggregateLow || gapCorrelated;
            boolean rateWarning = currentWindowLow && !rateCritical;

            if(currentWindowLow)
            {
                mLowRateWindowCount++;
            }
            else
            {
                mLowRateWindowCount = 0;
            }

            mPreviousRateWindow = new UsbRateWindow(nominalBytes, usableBytesDelta);
            mPreviousRateWindowLow = currentWindowLow;
            mPreviousWindowHadGap = gapDelta > 0;
            return new UsbDeliveryAssessment(true, windowMilliseconds, expectedBytesDelta, usableBytesDelta,
                nominalBytes, rawDeliveryPercent, twoWindowDeliveryPercent, statusDelta, integrityDelta, gapDelta,
                stale, rateWarning, rateCritical, gapCorrelated, mLowRateWindowCount, lastDeliveryAge);
        }

        private void setBaseline(UsbDeliveryObservation observation)
        {
            mBaseline = new UsbDeliveryBaseline(observation.nowMs(), observation.sequence(),
                observation.expectedBytes(), observation.usableBytes(), observation.statusCount(),
                observation.integrityCount(), observation.longGapCount());
        }

        private void reset()
        {
            mBaseline = null;
            resetRateWindow();
        }

        private void resetRateWindow()
        {
            mPreviousRateWindow = null;
            mPreviousRateWindowLow = false;
            mPreviousWindowHadGap = false;
            mLowRateWindowCount = 0;
        }
    }

    record UsbDeliveryObservation(long nowMs, boolean streaming, long sequence, long expectedBytes,
                                  long usableBytes, long statusCount, long integrityCount, long longGapCount,
                                  long lastDeliveryTimestampMs, double requiredBytesPerSecond)
    {
    }

    record UsbDeliveryAssessment(boolean rateAvailable, long windowMilliseconds, long expectedBytesDelta,
                                 long usableBytesDelta, double nominalBytes, double rawDeliveryPercent,
                                 double twoWindowDeliveryPercent, long statusDelta, long integrityDelta,
                                 long gapDelta, boolean stale, boolean rateWarning, boolean rateCritical,
                                 boolean gapCorrelated, long lowRateWindowCount,
                                 long lastDeliveryAgeMilliseconds)
    {
        private static UsbDeliveryAssessment warming(boolean stale, long lastDeliveryAgeMilliseconds)
        {
            return new UsbDeliveryAssessment(false, 0, 0, 0, 0, Double.NaN, Double.NaN, 0, 0, 0, stale,
                false, false, false, 0, lastDeliveryAgeMilliseconds);
        }

        boolean hardLoss()
        {
            return integrityDelta > 0 || stale;
        }

        boolean rateMeasurementWarning()
        {
            return rateAvailable && rawDeliveryPercent < USB_WARNING_DELIVERY_PERCENT;
        }
    }

    private record UsbDeliveryBaseline(long timestampMs, long sequence, long expectedBytes, long usableBytes,
                                       long statusCount, long integrityCount, long longGapCount)
    {
    }

    private record UsbRateWindow(double nominalBytes, long usableBytes)
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
