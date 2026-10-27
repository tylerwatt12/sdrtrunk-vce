/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.health;

import io.github.dsheirer.application.ComputerInformation;
import io.github.dsheirer.audio.broadcast.AudioStreamingManager;
import io.github.dsheirer.audio.call.AudioCallCoordinator;
import io.github.dsheirer.channel.quality.ControlChannelQualityMonitor;
import io.github.dsheirer.channel.quality.ControlChannelQualitySnapshot;
import io.github.dsheirer.metadata.site.SiteReceiverContext;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
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
import io.github.dsheirer.stats.activity.ReceiverHealthIncidentRecord;
import io.github.dsheirer.support.WebDiagnosticLogService;
import io.github.dsheirer.web.tuner.TunerAdministrationService;
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
    static final String LEGACY_SNAPSHOT_FILE_NAME = "receiver-health.json";
    private static final long SAMPLE_INTERVAL_MILLISECONDS = 1_000L;
    private static final long CONDITION_HOLD_MILLISECONDS = 10_000L;
    private static final long STORAGE_SAMPLE_INTERVAL_MILLISECONDS = 30_000L;
    private static final long FAILURE_LOG_INTERVAL_MILLISECONDS = 60_000L;
    private static final long COUNTER_BASELINE_RETENTION_MILLISECONDS = 60_000L;
    private static final long USB_STALE_DELIVERY_MILLISECONDS = 10_000L;
    private static final double USB_LOW_DELIVERY_PERCENT = 90.0;
    private final UserPreferences mUserPreferences;
    private final TunerManager mTunerManager;
    private final ReceiverActivityService mActivityLogService;
    private final LongSupplier mClock;
    private final LongSupplier mMonotonicClock;
    private final long mStartedAtMs;
    private final ScheduledExecutorService mExecutor;
    private final Path mLegacySnapshotPath;
    private final ReceiverHealthIncidentTracker mIncidents = new ReceiverHealthIncidentTracker();
    private final ReceiverHealthDiagnosticCore mDiagnosticCore = new ReceiverHealthDiagnosticCore();
    private final Map<String,CounterBaseline> mCounterBaselines = new HashMap<>();
    private final Map<String,CounterOwner> mCounterOwners = new HashMap<>();
    private final Map<String,UsbDeliveryClassifier> mUsbDeliveryClassifiers = new HashMap<>();
    private final Map<String,ControlContinuity> mControlContinuityByReceiver = new HashMap<>();
    private final AtomicBoolean mStarted = new AtomicBoolean();
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private volatile AudioCallCoordinator mAudioCallCoordinator;
    private volatile AudioRecordingManager mAudioRecordingManager;
    private volatile AudioStreamingManager mAudioStreamingManager;
    private volatile LongSupplier mRadioResolveAcceptedCalls = () -> 0L;
    private volatile Supplier<Map<String,Object>> mWebStatusSupplier = Map::of;
    private volatile Supplier<Map<String,TunerAdministrationService.ReceiverReference>> mReceiverReferences = Map::of;
    private volatile Supplier<List<ControlChannelQualityMonitor>> mControlQualitySupplier;
    private volatile Map<String,Object> mSnapshot;
    private volatile WebDiagnosticLogService mWebDiagnosticLogService;
    private long mLastStorageSampleMs;
    private StorageSnapshot mStorageSnapshot = StorageSnapshot.unavailable();
    private long mLastGcPauseTimeMs = -1;
    private long mLastFailureLogMs;
    private long mLastIncidentPersistenceFailureLogMs;
    private boolean mLegacySnapshotCleanupAttempted;

    public ReceiverHealthService(UserPreferences userPreferences, TunerManager tunerManager,
                                 ChannelProcessingManager channelProcessingManager,
                                 ReceiverActivityService activityLogService)
    {
        this(userPreferences, tunerManager, channelProcessingManager, activityLogService,
            System::currentTimeMillis, legacySnapshotPath(userPreferences));
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
                          Path legacySnapshotPath)
    {
        this(userPreferences, tunerManager, channelProcessingManager, activityLogService, clock,
            legacySnapshotPath, System::nanoTime);
    }

    ReceiverHealthService(UserPreferences userPreferences, TunerManager tunerManager,
                          ChannelProcessingManager channelProcessingManager,
                          ReceiverActivityService activityLogService, LongSupplier clock,
                          Path legacySnapshotPath, LongSupplier monotonicClock)
    {
        mUserPreferences = userPreferences;
        mTunerManager = tunerManager;
        mControlQualitySupplier = channelProcessingManager != null ?
            channelProcessingManager::getControlChannelQualityMonitors : List::of;
        mActivityLogService = activityLogService;
        mClock = clock != null ? clock : System::currentTimeMillis;
        mMonotonicClock = monotonicClock != null ? monotonicClock : System::nanoTime;
        mLegacySnapshotPath = legacySnapshotPath;
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

    public void setRadioResolveAcceptedCalls(LongSupplier acceptedCalls)
    {
        mRadioResolveAcceptedCalls = acceptedCalls != null ? acceptedCalls : () -> 0L;
    }

    public void setWebStatusSupplier(Supplier<Map<String,Object>> webStatusSupplier)
    {
        mWebStatusSupplier = webStatusSupplier != null ? webStatusSupplier : Map::of;
    }

    /** Labels are projected from one cached nickname snapshot on the health observer, never from a receiver callback. */
    public void setReceiverReferences(Supplier<Map<String,TunerAdministrationService.ReceiverReference>> supplier)
    {
        mReceiverReferences = supplier != null ? supplier : Map::of;
    }

    void setControlQualitySupplier(Supplier<List<ControlChannelQualityMonitor>> supplier)
    {
        mControlQualitySupplier = supplier != null ? supplier : List::of;
    }

    /**
     * Immutable API payload.  HTTP callers never sample live receiver objects themselves.
     */
    public Map<String,Object> snapshot()
    {
        return mSnapshot;
    }

    /** A single bounded handoff owned by the web service; sampling remains independent of web clients. */
    public void setWebDiagnosticLogService(WebDiagnosticLogService service)
    {
        mWebDiagnosticLogService = service;
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
            mIncidents.discardPending();
            mDiagnosticCore.disabled();
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
        removeLegacySnapshotOnce();

        if(mClosed.get())
        {
            return;
        }

        long now = mClock.getAsLong();
        long nowNanos = mMonotonicClock.getAsLong();
        WebDiagnosticLogService diagnostics = mWebDiagnosticLogService;
        long capture = diagnostics != null ? diagnostics.captureGeneration() : 0;
        ReceiverHealthDiagnosticCore.Sample core = capture != 0 ?
            mDiagnosticCore.begin(capture, mStartedAtMs, now, nowNanos) : null;
        if(core == null) mDiagnosticCore.disabled();
        mIncidents.beginSample(nowNanos);
        List<Map<String,Object>> measurements = new ArrayList<>();
        collectTuners(now, measurements, core);
        collectControlChannels(now, nowNanos, measurements);
        collectHost(now, measurements, core);
        collectOutputs(now, measurements);
        collectSupportingServices(now, measurements, core);
        mCounterBaselines.entrySet().removeIf(entry ->
            now - entry.getValue().lastSeenMs > COUNTER_BASELINE_RETENTION_MILLISECONDS);
        mIncidents.endSample(now);
        publishIncidentChanges(mIncidents.lifecycleChanges(), now);
        List<Map<String,Object>> active = mIncidents.active();
        if(core != null) core.activeAlerts(active);
        LinkedHashMap<String,Object> response = new LinkedHashMap<>();
        response.put("started_at_ms", mStartedAtMs);
        response.put("generated_at_ms", now);
        response.put("summary", summarize(active));
        response.put("active", active);
        response.put("resolved", mIncidents.resolved());
        response.put("measurements", List.copyOf(measurements));
        response.put("computer", ComputerInformation.current().snapshot());
        response.put("radioresolve_accepted_calls", mRadioResolveAcceptedCalls.getAsLong());
        mSnapshot = Map.copyOf(response);
        if(core != null) diagnostics.recordHealth(mSnapshot, core.finish(System.nanoTime()));
    }

    private void publishIncidentChanges(List<Map<String,Object>> incidents, long now)
    {
        if(mActivityLogService == null || incidents.isEmpty())
        {
            return;
        }

        for(Map<String,Object> incident: incidents)
        {
            try
            {
                ReceiverHealthIncidentRecord record = new ReceiverHealthIncidentRecord(mStartedAtMs,
                    number(incident.get("occurrence_id")), text(incident.get("code")),
                    text(incident.get("severity")), text(incident.get("title")), text(incident.get("scope")),
                    number(incident.get("opened_at_ms")), number(incident.get("last_seen_ms")),
                    number(incident.get("resolved_at_ms")), number(incident.get("count")),
                    text(incident.get("observed")), text(incident.get("likely_cause")),
                    text(incident.get("impact")), text(incident.get("check_next")));

                if(!mActivityLogService.receiveReceiverHealthIncident(record))
                {
                    logIncidentPersistenceFailure(now, null);
                }
            }
            catch(RuntimeException exception)
            {
                logIncidentPersistenceFailure(now, exception);
            }
        }
    }

    private void logIncidentPersistenceFailure(long now, RuntimeException exception)
    {
        if(mLastIncidentPersistenceFailureLogMs == 0 ||
            now - mLastIncidentPersistenceFailureLogMs >= FAILURE_LOG_INTERVAL_MILLISECONDS)
        {
            mLastIncidentPersistenceFailureLogMs = now;
            if(exception != null)
            {
                LOGGER.warn("Receiver status alert history could not be queued for SQLite", exception);
            }
            else
            {
                LOGGER.warn("Receiver status alert history could not be queued for SQLite");
            }
        }
    }

    private void collectTuners(long now, List<Map<String,Object>> measurements,
                               ReceiverHealthDiagnosticCore.Sample core)
    {
        collectTuners(now, measurements, core, mTunerManager != null ?
            mTunerManager.getDiscoveredTunerRegistry().snapshot() : List.of());
    }

    /** Projects one inventory snapshot without opening settings stores or changing native counter ownership. */
    void collectTuners(long now, List<Map<String,Object>> measurements,
                       ReceiverHealthDiagnosticCore.Sample core, List<DiscoveredTuner> tuners)
    {
        List<Map<String,Object>> tunerRows = new ArrayList<>();
        List<Map<String,Object>> usbRows = new ArrayList<>();
        List<Map<String,Object>> receiverRows = new ArrayList<>();
        List<Map<String,Object>> channelizerRows = new ArrayList<>();
        List<Map<String,Object>> channelRows = new ArrayList<>();
        Set<String> activeUsbScopes = new HashSet<>();
        Set<String> activeTunerScopes = new HashSet<>();
        Map<String,TunerAdministrationService.ReceiverReference> references = receiverReferences();
        if(core != null) core.tunersObserved(tuners.size());

        for(DiscoveredTuner discovered: tuners)
        {
            String fallbackScope = "tuner-" + Integer.toHexString(System.identityHashCode(discovered));

            try
            {
            String discoveredId = discovered.getId();
            String scope = discoveredId != null && !discoveredId.isBlank() ? discoveredId : fallbackScope;
            activeTunerScopes.add(scope);
            TunerStatus tunerStatus = discovered.getTunerStatus();
            Tuner tuner = discovered.getTuner();
            var reference = discoveredId != null ? references.get(discoveredId) : null;
            String display = reference != null ? reference.nickname() : null;
            if(display == null || display.isBlank())
            {
                String preferredName = tuner != null ? tuner.getPreferredName() : null;
                display = preferredName != null && !preferredName.isBlank() ? preferredName : scope;
            }
            String uniqueId = tuner != null ? tuner.getUniqueID() : null;
            String serial = uniqueId != null ? uniqueId : "";
            TunerController controller = tuner != null ? tuner.getTunerController() : null;
            ChannelSourceManager sourceManager = tuner != null ? tuner.getChannelSourceManager() : null;
            int channels = sourceManager != null ? sourceManager.getTunerChannelCount() : 0;
            USBTunerController.UsbTransferHealthSnapshot usbHealth = controller instanceof USBTunerController usb ?
                usb.getUsbTransferHealthSnapshot() : null;
            boolean tunerRequired = prepareCounterOwner(scope, controller, sourceManager,
                usbHealth != null ? usbHealth.streamSequence() : 0);
            ReceiverHealthDiagnosticCore.Tuner compact = core != null ?
                core.tuner(scope, display, controller, sourceManager, discovered.isEnabled(), tunerStatus == TunerStatus.ERROR,
                    channels) : null;
            tunerRows.add(row(scope, display, tunerStatus.toString(), "",
                tunerStatus == TunerStatus.ERROR ? "critical" : "healthy",
                (serial.isBlank() ? "" : "Serial " + serial + " · ") + channels + " channel" +
                    (channels == 1 ? "" : "s") + (controller != null ? " · Center " +
                        controller.getFrequency() + " Hz · Sample rate " +
                        Math.round(controller.getSampleRate()) + " samples/s" : "") +
                    (tunerStatus == TunerStatus.ERROR ? " · " + discovered.getErrorMessage() : "")));

            if(discovered.isEnabled() && tunerRequired && tunerStatus == TunerStatus.ERROR)
            {
                mIncidents.observe("tuner-error", "critical", "Tuner is not working", scope, display, now, 1,
                    "The tuner stopped providing radio data to its assigned channels",
                    "The USB connection, driver or tuner may have stopped working",
                    "Channels assigned to this tuner cannot receive radio data",
                    "Open the tuner details, then check its error message, USB cable, driver, and power");
            }

            if(discovered instanceof DiscoveredUSBTuner usb)
            {
                usbRows.add(row(scope, "USB location", "Bus " + usb.getBus() + " / " + usb.getPortAddress(), "",
                    "info", display + (serial.isBlank() ? "" : " · " + serial)));
            }

            if(controller instanceof USBTunerController)
            {
                activeUsbScopes.add(scope);
                collectUsb(now, scope, display, tuner, tunerRequired, usbHealth, usbRows, compact);
            }

            if(sourceManager instanceof PolyphaseChannelSourceManager polyphase)
            {
                PolyphaseChannelManager.NativeBufferQueueStatus nativeStatus =
                    polyphase.getNativeBufferQueueStatus();
                if(compact != null) compact.nativeQueue(nativeStatus);
                receiverRows.add(row(scope, display + " incoming radio data waiting", nativeStatus.queuedMilliseconds(), "ms",
                    queueSeverity(nativeStatus.queuedMilliseconds(), nativeStatus.appliedDurationMilliseconds()),
                    "Peak " + nativeStatus.highWaterMilliseconds() + " ms · Limit " +
                        nativeStatus.appliedDurationMilliseconds() + " ms · Dropped " +
                        nativeStatus.droppedBuffers() + " losses (" + nativeStatus.droppedMilliseconds() + " ms)"));
                long nativeDropDelta = delta(scope + ":native-drops", nativeStatus.droppedBuffers(), now);

                if(nativeDropDelta > 0)
                {
                    mIncidents.observeRecurring("receiver-iq-drop", "warning", "Radio data keeps being lost", scope,
                        display, now, nativeStatus.droppedBuffers(), "VCE has repeatedly lost incoming radio data",
                        "The computer or a receiving function may not be keeping up",
                        "Calls on channels using this tuner may be missed or interrupted",
                        "Check the USB connection and computer load, then open the detailed measurements below");
                }

                PolyphaseChannelManager.PipelineStatus pipeline = polyphase.getPipelineStatus();
                channelizerRows.add(row(scope, display + " radio data waiting for channel separation",
                    pipeline.ifftQueuedBatches(), "chunks",
                    queueSeverity(pipeline.ifftQueuedBatches(), pipeline.ifftCapacityBatches()),
                    channelizerDetail(pipeline)));
                long ifftDropDelta = delta(scope + ":ifft-drops", pipeline.ifftDroppedBatches(), now);

                if(ifftDropDelta > 0)
                {
                    mIncidents.observeRecurring("channelizer-drop", "warning", "Radio data keeps being lost while separating channels",
                        scope, display, now, pipeline.ifftDroppedBatches(), "VCE keeps losing radio data while separating this tuner's channels",
                        "VCE ran out of room while separating this tuner's data into channels",
                        "Calls on channels using this tuner may be missed or interrupted",
                        "Check computer load and memory use, then close unused signal views");
                }

                long aggregateChannelDrops = pipeline.channelDroppedBatches();
                long queued = 0;
                long peak = 0;
                long capacity = 0;
                long maximumCapacity = 0;

                for(PolyphaseChannelManager.ChannelQueueStatus channel: pipeline.channels())
                {
                    if(compact != null)
                    {
                        queued += channel.queuedBatches();
                        peak += channel.highWaterBatches();
                        capacity += channel.capacityBatches();
                        maximumCapacity = Math.max(maximumCapacity, channel.capacityBatches());
                    }
                }
                if(compact != null) compact.pipeline(pipeline, queued, peak, capacity, maximumCapacity);

                List<PolyphaseChannelManager.ChannelQueueStatus> affected = pipeline.channels().stream()
                    .sorted(Comparator.comparingLong(PolyphaseChannelManager.ChannelQueueStatus::droppedBatches)
                        .reversed().thenComparing(Comparator.comparingInt(
                            PolyphaseChannelManager.ChannelQueueStatus::queuedBatches).reversed()))
                    .limit(20).toList();

                for(PolyphaseChannelManager.ChannelQueueStatus channel: affected)
                {
                    String channelScope = scope + ":" + channel.frequencyHz();
                    channelRows.add(row(channelScope, formatFrequency(channel.frequencyHz()), channel.queuedBatches(),
                        "chunks", queueSeverity(channel.queuedBatches(), channel.capacityBatches()),
                        display + " · Peak " + channel.highWaterBatches() + " chunks · Limit " +
                            channel.capacityBatches() + " · Lost " + channel.droppedBatches()));
                }

                long channelDropDelta = delta(scope + ":channel-drops", aggregateChannelDrops, now);

                if(channelDropDelta > 0)
                {
                    mIncidents.observeRecurring("channel-output-drop", "warning", "A channel keeps losing radio data",
                        scope, display, now, aggregateChannelDrops, "A channel keeps missing some of its incoming radio data",
                        "The computer or a receiving function may not be keeping up",
                        "Calls on affected channels may be missed or interrupted",
                        "Check the affected channels and computer load; stop unused channels if needed");
                }
            }
            }
            catch(RuntimeException exception)
            {
                if(core != null) core.tunerUnavailable();
                String failedScope = discovered.getId();
                if(failedScope != null) mIncidents.preserve(failedScope);
                tunerRows.add(row(fallbackScope, "Tuner status is temporarily unavailable", "Unavailable", "", "warning",
                    "The tuner changed state while its status was checked."));
            }
        }

        mCounterOwners.keySet().retainAll(activeTunerScopes);
        mUsbDeliveryClassifiers.keySet().retainAll(activeUsbScopes);

        if(mTunerManager != null)
        {
            TunerManager.TunerAllocationStatus allocation = mTunerManager.getTunerAllocationStatus();
            long failureDelta = delta("tuner:allocation-failures", allocation.failures(), now);
            tunerRows.add(row("Tuner", "Channel start requests", allocation.successes(), "successful",
                failureDelta > 0 ? "warning" : allocation.failures() > 0 ? "info" : "healthy",
                allocation.requests() + " requested · " + allocation.failures() + " failed"));

        }

        measurements.add(section("tuners", "Tuners", tunerRows));
        measurements.add(section("usb", "USB tuner connection", usbRows));
        measurements.add(section("receiver-queues", "Incoming radio data", receiverRows));
        measurements.add(section("channelizer", "Channel separation", channelizerRows));
        measurements.add(section("channels", "Per-channel processing", channelRows));
    }

    private Map<String,TunerAdministrationService.ReceiverReference> receiverReferences()
    {
        try
        {
            Map<String,TunerAdministrationService.ReceiverReference> references = mReceiverReferences.get();
            return references != null ? references : Map.of();
        }
        catch(RuntimeException ignored)
        {
            // Missing labels must not hide tuner health measurements or disturb their native identities.
            return Map.of();
        }
    }

    private void collectUsb(long now, String scope, String display, Tuner tuner, boolean receiving,
                            USBTunerController.UsbTransferHealthSnapshot usb,
                            List<Map<String,Object>> rows, ReceiverHealthDiagnosticCore.Tuner compact)
    {
        TunerController controller = tuner.getTunerController();
        double requiredBytesPerSecond = usb.streaming() && controller != null ?
            controller.getSampleRate() * usb.sampleFrameSizeBytes() : 0;
        long transferStatusCount = usb.errorTransferCount() + usb.stalledTransferCount() +
            usb.timedOutTransferCount() + usb.cancelledTransferCount() + usb.unexpectedStatusTransferCount() +
            usb.submissionFailureCount();
        long bufferFailureCount = usb.nativeIngressCopyFailures() + usb.nativeIngressConversionFailures();
        long integrityCount = usb.shortTransferCount() + usb.zeroLengthTransferCount() +
            usb.malformedTransferCount() + bufferFailureCount;
        long lastDeliveryNanos = usb.hasTransfer() ?
            usb.lastTransferTimestampNanoseconds() : usb.streamStartedTimestampNanoseconds();
        long ingressLossCount = usb.nativeIngressSaturationDroppedBuffers();
        long ingressLossDelta = delta(scope + ":receiver-ingress-loss", ingressLossCount, now);
        long listenerFailureDelta = delta(scope + ":receiver-listener-failure",
            usb.nativeIngressListenerFailures(), now);
        UsbDeliveryAssessment assessment = mUsbDeliveryClassifiers.computeIfAbsent(scope,
            ignored -> new UsbDeliveryClassifier()).evaluate(new UsbDeliveryObservation(usb.observedAtNanoseconds(), usb.streaming(),
                usb.streamSequence(), usb.expectedBytes(), usb.usableBytes(), transferStatusCount, integrityCount,
                usb.longTransferGapCount(), lastDeliveryNanos, requiredBytesPerSecond));
        double seconds = assessment.windowMilliseconds() / 1_000.0;
        double usableBytesPerSecond = assessment.rateAvailable() && seconds > 0 ?
            assessment.usableBytesDelta() / seconds : 0;
        if(compact != null) compact.usb(usb, assessment.lastDeliveryAgeMilliseconds(), assessment.rateAvailable(), assessment.rawDeliveryPercent(),
            usableBytesPerSecond, requiredBytesPerSecond);
        double displayDeliveryPercent = assessment.rateAvailable() ?
            Math.min(100.0, assessment.rawDeliveryPercent()) : 100.0;
        String rateSeverity = assessment.sustainedShortage() ? "warning" : "info";
        rows.add(row(scope, display + " radio data rate", round(usableBytesPerSecond / 1_000_000.0), "MB/s",
            rateSeverity, "Required " + round(requiredBytesPerSecond / 1_000_000.0) +
                " MB/s · Delivery " + (assessment.rateAvailable() ? round(displayDeliveryPercent) + "%" :
                "warming up") + " · Sample window " + assessment.windowMilliseconds() +
                " ms · Tuner USB limit " + round(tuner.getMaximumUSBBitsPerSecond() / 1_000_000.0) + " Mbit/s"));
        rows.add(row(scope, display + " USB transfer results", usb.transferCount(), "transfers",
            assessment.statusDelta() > 0 ? "warning" : transferStatusCount > 0 ? "info" : "healthy",
            "Completed " + usb.completedTransferCount() + " · Stalled " + usb.stalledTransferCount() +
                " · Timed out " + usb.timedOutTransferCount() + " · Errors " + usb.errorTransferCount() +
                " · Cancelled " + usb.cancelledTransferCount() + " · Unexpected " +
                usb.unexpectedStatusTransferCount()));
        rows.add(row(scope, display + " active USB transfers", usb.activeTransferCount(), "active transfers",
            usb.retryTransferCount() > 0 ? "warning" : "healthy", "Total " + usb.transferPoolSize() +
                " · Retrying " + usb.retryTransferCount() + " · Start failures " +
                usb.submissionFailureCount()));
        rows.add(row(scope, display + " USB connection speed", usb.negotiatedDeviceSpeed(), "",
            "info", ""));
        rows.add(row(scope, display + " USB data errors", usb.shortTransferCount(), "short transfers",
            assessment.integrityDelta() > 0 ? "critical" : integrityCount > 0 ? "info" : "healthy",
            "Zero-length " + usb.zeroLengthTransferCount() + " · Malformed " + usb.malformedTransferCount() +
                " · Estimated missing " + usb.estimatedMissingBytes() + " bytes · Unusable " +
                usb.unusableBytes() + " bytes · Processing errors " + bufferFailureCount));
        rows.add(row(scope, display + " USB data pauses", usb.lastInterTransferGapMilliseconds(), "ms",
            assessment.gapDelta() > 0 ? "warning" : usb.longTransferGapCount() > 0 ? "info" : "healthy",
            "Longest " + usb.worstInterTransferGapMilliseconds() + " ms · Expected transfer size " +
                usb.expectedTransferLengthBytes() + " bytes · Long pauses " + usb.longTransferGapCount()));
        rows.add(row(scope, display + " USB data handling time",
            round(usb.lastCallbackToResubmitDurationNanoseconds() / 1_000_000.0), "ms", "info",
            "Longest since tuner opened " +
                round(usb.worstCallbackToResubmitDurationNanoseconds() / 1_000_000.0) +
                " ms · Last 25 ms+ this stream " +
                (usb.lastAtLeast25MsDurationNanoseconds() > 0 ?
                    round(usb.lastAtLeast25MsDurationNanoseconds() / 1_000_000.0) + " ms" : "none") +
                " · Transfers measured since tuner opened " +
                usb.callbackCount() + " · Took at least 25 ms " + usb.callbacksAtLeast25MsCount()));
        boolean ingressPressure = usb.nativeIngressCapacity() > 0 &&
            usb.nativeIngressDepth() * 4 >= usb.nativeIngressCapacity() * 3;
        rows.add(row(scope, display + " radio data waiting after USB", usb.nativeIngressDepth(), "chunks",
            ingressLossDelta > 0 ? "critical" : listenerFailureDelta > 0 || ingressPressure ? "warning" :
                ingressLossCount > 0 || usb.nativeIngressListenerFailures() > 0 ? "info" : "healthy",
            "Limit " + usb.nativeIngressCapacity() + " chunks · Peak " +
                usb.nativeIngressHighWaterDepth() + " · Dropped " +
                usb.nativeIngressSaturationDroppedBuffers() + " chunks (" +
                usb.nativeIngressSaturationDroppedSamples() + " samples) · Latest delay " +
                round(usb.nativeIngressLastQueueDelayNanoseconds() / 1_000_000.0) +
                " ms · Longest delay " + round(usb.nativeIngressWorstQueueDelayNanoseconds() / 1_000_000.0) +
                " ms · Delivery failures " + usb.nativeIngressListenerFailures()));

        if(!receiving) return;

        if(ingressLossDelta > 0)
        {
            mIncidents.observeRecurring("receiver-ingress-drop", "warning",
                "Radio data from the tuner keeps being lost", scope, display, now, ingressLossCount,
                "VCE keeps losing incoming radio data after it reaches the computer",
                "The computer is not processing the tuner's radio data quickly enough",
                "Calls may be missed or interrupted",
                "Check the tuner connection and computer load, then close unused signal views");
        }

        if(assessment.stopped())
        {
            mIncidents.observe("usb-sample-loss", "critical", "Radio data from the USB tuner has stopped",
                scope, display, now, 1, "No radio data has arrived for " +
                    assessment.lastDeliveryAgeMilliseconds() / 1_000 + " seconds",
                "The tuner, cable, USB connection or driver may have stopped working",
                "Channels using this tuner cannot receive calls",
                "Open the tuner details and check its connection, power and error message");
        }
        else if(assessment.integrityDelta() > 0)
        {
            mIncidents.observeRecurring("usb-sample-loss", "warning", "Radio data from the USB tuner is incomplete",
                scope, display, now, integrityCount, "Incoming radio data keeps arriving incomplete or cannot be read",
                "The tuner connection or VCE's handling of incoming radio data is failing",
                "Calls may be missed or interrupted",
                "Check the USB cable, connection and computer load; details are listed below");
        }
        else if(assessment.sustainedShortage())
        {
            mIncidents.observe("usb-delivery-rate-low", "warning", "Too little radio data is arriving from the USB tuner",
                scope, display, now, 1, "The tuner delivered " + round(assessment.sustainedDeliveryPercent()) +
                    "% of the expected radio data over " + assessment.sustainedWindowMilliseconds() / 1_000 + " seconds",
                "The tuner connection or computer is not keeping up",
                "Calls may be missed or interrupted",
                "Check the USB connection, cable, power and computer load");
        }
    }

    private void collectControlChannels(long now, long nowNanos, List<Map<String,Object>> measurements)
    {
        List<Map<String,Object>> rows = new ArrayList<>();
        Set<String> activeReceivers = new HashSet<>();
        for(ControlChannelQualityMonitor monitor: mControlQualitySupplier.get())
        {
            ControlChannelQualityMonitor.ReceiverObservation observation = monitor.getReceiverObservation();
            if(observation == null)
            {
                mControlContinuityByReceiver.forEach((scope, continuity) ->
                {
                    if(continuity.owner() == monitor)
                    {
                        activeReceivers.add(scope);
                        mIncidents.preserve(scope);
                    }
                });
                continue;
            }
            ControlChannelQualitySnapshot quality = observation.snapshot();
            SiteReceiverContext receiver = quality.receiverContext();
            if(receiver == null || !receiver.isStandardChannel() ||
                receiver.receiverMode() != SiteReceiverContext.ReceiverMode.TRUNKED ||
                !receiver.matchesCurrentQualityReceiver(quality.channel(), true)) continue;

            String configuration = receiver.configurationId();
            String scope = configuration != null ? "channel:" + configuration :
                "transient-channel:" + receiver.runtimeChannelId();
            activeReceivers.add(scope);
            ControlContinuity previous = mControlContinuityByReceiver.get(scope);
            if(previous != null && (previous.owner() != monitor ||
                previous.lifecycleGeneration() != observation.lifecycleGeneration() ||
                previous.processingIncarnation() != receiver.processingIncarnation())) previous = null;
            long sequence = monitor.getValidFrameSequence();
            long lastProgressNanos = previous == null || previous.validFrameSequence() != sequence ?
                nowNanos : previous.lastProgressNanos();
            mControlContinuityByReceiver.put(scope, new ControlContinuity(monitor, observation.lifecycleGeneration(),
                receiver.processingIncarnation(), sequence, lastProgressNanos));

            String systemName = receiver.systemName() != null ? receiver.systemName() : "";
            String siteName = receiver.siteName() != null ? receiver.siteName() : "";
            String channelName = receiver.channelName() != null ? receiver.channelName() : "";
            String title = !systemName.isBlank() && !siteName.isBlank() ? systemName + " · " + siteName :
                !siteName.isBlank() ? siteName : !systemName.isBlank() ? systemName :
                !channelName.isBlank() ? channelName : "Control channel";
            boolean fresh = quality.active() && now >= quality.observedAtMs() &&
                now - quality.observedAtMs() <= 5_000;
            Double health = fresh ? quality.decodeHealthPercent() : null;
            Map<String,Object> measurement = new LinkedHashMap<>(row(scope,
                title + " · " + formatFrequency(quality.frequencyHz()),
                health != null ? round(health) : "Unavailable", "%",
                !fresh ? "info" : health != null && health < 20 ? "warning" : "healthy",
                (fresh ? "" : "Last control measurement is unavailable or out of date. ") +
                    "Signal " + quality.signalDbfs() + " dBFS · Valid frames " + quality.validFrames() +
                    " · Invalid frames " + quality.invalidFrames() + " · Corrected bits " + quality.correctedBits() +
                    " · Sync loss " + quality.syncLossBits() + " bits · Dropped " + quality.droppedBits() +
                    " bits · Decoder " + receiver.decoderType()));
            measurement.put("system_name", systemName);
            measurement.put("channel_name", channelName);
            measurement.put("configuration_id", configuration != null ? configuration : "");
            measurement.put("frequency_hz", quality.frequencyHz());
            P25SiteIdentity site = quality.channel().getP25SiteIdentity();
            if(site != null)
            {
                measurement.put("wacn", site.wacn());
                measurement.put("system_id", site.system());
                measurement.put("rfss", site.rfss());
                measurement.put("site_id", site.site());
            }
            rows.add(measurement);

            long lostForMs = TimeUnit.NANOSECONDS.toMillis(Math.max(0, nowNanos - lastProgressNanos));
            if(lostForMs >= CONDITION_HOLD_MILLISECONDS)
            {
                mIncidents.observe("control-channel-lock-lost", "critical", "Control channel is not receiving messages",
                    scope, title, now, 1, "No readable control messages for " + lostForMs / 1_000 + " seconds",
                    "The signal may be weak, the frequency may be wrong, or the tuner may not be providing radio data",
                    "VCE cannot follow this site's new calls",
                    "Check the site's signal and selected control frequency, then the tuner connection");
            }
        }
        mControlContinuityByReceiver.keySet().retainAll(activeReceivers);
        measurements.add(section("decoders", "Control channel", rows));
    }

    /** ZGC cycle counters include concurrent work; its separate pause counters measure stopped application time. */
    static long gcPauseTime(String collectorName, long collectionTimeMs)
    {
        return collectorName != null && collectorName.endsWith(" Cycles") ? 0 : Math.max(0, collectionTimeMs);
    }

    private void collectHost(long now, List<Map<String,Object>> measurements,
                             ReceiverHealthDiagnosticCore.Sample core)
    {
        List<Map<String,Object>> rows = new ArrayList<>();
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        long heapMaximum = runtime.maxMemory();
        double heapPercent = heapMaximum > 0 ? 100.0 * heapUsed / heapMaximum : 0;
        double cpuPercent = processCpuPercent();
        long gcTimeMs = 0;
        long gcPauseTimeMs = 0;
        long gcCount = 0;
        boolean gcAvailable = true;

        for(GarbageCollectorMXBean bean: ManagementFactory.getGarbageCollectorMXBeans())
        {
            long time = bean.getCollectionTime();
            long count = core != null ? bean.getCollectionCount() : 0;
            gcTimeMs += Math.max(0, time);
            gcPauseTimeMs += gcPauseTime(bean.getName(), time);
            gcCount += Math.max(0, count);
            gcAvailable &= time >= 0 && count >= 0;
        }
        if(core != null) core.host(cpuPercent, heapUsed, heapMaximum, heapPercent,
            gcAvailable ? gcCount : -1, gcAvailable ? gcTimeMs : -1);

        long gcPauseIntervalMs = mLastGcPauseTimeMs >= 0 ? Math.max(0, gcPauseTimeMs - mLastGcPauseTimeMs) : 0;
        mLastGcPauseTimeMs = gcPauseTimeMs;
        rows.add(row("Computer", "VCE processor use", Double.isFinite(cpuPercent) ? round(cpuPercent) : "Unavailable", "%",
            Double.isFinite(cpuPercent) && cpuPercent >= 90 ? "warning" : "healthy", ""));
        rows.add(row("Computer", "VCE memory use", round(heapPercent), "%", heapPercent >= 90 ? "critical" :
            heapPercent >= 80 ? "warning" : "healthy",
            "Used " + formatBytes(heapUsed) + " of " + formatBytes(heapMaximum)));
        rows.add(row("Computer", "Time spent freeing memory", gcPauseIntervalMs, "ms in last sample",
            gcPauseIntervalMs >= 500 ? "warning" : "healthy", "Total pauses since startup " + gcPauseTimeMs + " ms"));

        if(now - mLastStorageSampleMs >= STORAGE_SAMPLE_INTERVAL_MILLISECONDS)
        {
            mLastStorageSampleMs = now;
            mStorageSnapshot = storageSnapshot();
        }

        rows.add(row("Computer", "Free storage space", mStorageSnapshot.freePercent >= 0 ?
            round(mStorageSnapshot.freePercent) : "Unavailable", "%", mStorageSnapshot.freePercent >= 0 &&
            mStorageSnapshot.freePercent < 5 ? "critical" : mStorageSnapshot.freePercent >= 0 &&
            mStorageSnapshot.freePercent < 10 ? "warning" : "healthy", mStorageSnapshot.detail));

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
            long operationDropDelta = delta("output:audio-coordinator-ingress", status.droppedOperations(), now);
            long abortedDelta = delta("output:audio-coordinator-aborted", status.abortedCalls(), now);
            String coordinatorSeverity = operationDropDelta + abortedDelta > 0 ? "warning" :
                queueSeverity(status.ingressDepth(), status.totalIngressCapacity());
            rows.add(row("Call outputs", "Call outputs waiting", status.ingressDepth(), "outputs",
                coordinatorSeverity, "Limit " + status.totalIngressCapacity() + " · Dropped " +
                    status.droppedOperations() + " · Stopped " + status.abortedCalls()));

        }

        if(recording != null)
        {
            AudioRecordingManager.RecordingQueueStatus status = recording.getQueueStatus();
            boolean pressure = status.queuedCalls() * 4 >= status.maximumQueuedCalls() * 3 ||
                status.queuedSourceBytes() * 4 >= status.maximumQueuedSourceBytes() * 3;
            long droppedDelta = delta("output:recording", status.droppedRecordings(), now);
            rows.add(row("Recordings", "Calls waiting to be recorded", status.queuedCalls(), "calls",
                droppedDelta > 0 || pressure ? "warning" : status.droppedRecordings() > 0 ? "info" : "healthy",
                "Limit " + status.maximumQueuedCalls() + " calls · Audio waiting " +
                    formatBytes(status.queuedSourceBytes()) + " of " +
                    formatBytes(status.maximumQueuedSourceBytes()) + " · Dropped " + status.droppedRecordings()));

        }

        if(streaming != null)
        {
            AudioStreamingManager.StreamingQueueStatus status = streaming.getQueueStatus();
            boolean pressure = status.retainedCalls() * 4 >= status.maximumRetainedCalls() * 3 ||
                status.retainedSourceBytes() * 4 >= status.maximumRetainedSourceBytes() * 3;
            long outputLosses = status.droppedCalls() + status.failedCalls();
            long outputLossDelta = delta("output:streaming", outputLosses, now);
            rows.add(row("Streaming", "Calls waiting to be streamed", status.retainedCalls(), "calls",
                outputLossDelta > 0 || pressure ? "warning" : outputLosses > 0 ? "info" : "healthy",
                "Limit " + status.maximumRetainedCalls() + " calls · Audio waiting " +
                    formatBytes(status.retainedSourceBytes()) + " of " +
                    formatBytes(status.maximumRetainedSourceBytes()) + " · Dropped " + status.droppedCalls() +
                    " · Failed " + status.failedCalls()));

        }

        measurements.add(section("outputs", "Recordings, streams, and browser audio", rows));
    }

    private void collectSupportingServices(long now, List<Map<String,Object>> measurements,
                                           ReceiverHealthDiagnosticCore.Sample core)
    {
        List<Map<String,Object>> rows = new ArrayList<>();

        if(mActivityLogService != null)
        {
            ReceiverActivityStatus status = mActivityLogService.getStatus();
            delta("observer:statistics", status.recordsDropped(), now);
            String activityState = switch(status.state())
            {
                case DISABLED -> "Off";
                case STARTING -> "Starting";
                case RUNNING -> "On";
                case STOPPED -> "Not running";
                case FAILED -> "Error";
            };
            rows.add(row("Activity", "Activity history", activityState,
                "", status.recordsDropped() > 0 ? "info" : "healthy",
                "Saved " + status.recordsWritten() + " · Dropped " + status.recordsDropped()));
        }

        Map<String,Object> webStatus;

        try
        {
            webStatus = mWebStatusSupplier.get();
        }
        catch(RuntimeException exception)
        {
            rows.add(row("Web", "Web status", "Unavailable", "", "warning",
                "Web status is temporarily unavailable."));
            measurements.add(section("supporting", "Web and browser audio", rows));
            return;
        }

        Map<String,Object> server = map(webStatus.get("server"));
        Map<String,Object> transport = map(server.get("live_transport"));
        long rejected = number(transport.get("rejected_clients"));
        long slow = number(transport.get("slow_disconnects"));
        long dropped = number(transport.get("event_drops"));
        long observerTotal = rejected + slow + dropped;
        delta("observer:web", observerTotal, now);
        rows.add(row("Web", "Live web connections", number(transport.get("active_clients")), "connections",
            observerTotal > 0 ? "info" : "healthy", "Rejected " + rejected +
                " · Slow connections closed " + slow + " · Updates dropped " + dropped));

        Map<String,Object> webPlayer = map(webStatus.get("web_player"));
        long webCapacityDrops = number(webPlayer.get("dropped_encoder_capacity"));
        long webEncoderFailures = number(webPlayer.get("encoder_failures"));
        long webAudioLosses = webCapacityDrops + webEncoderFailures;
        long webObserverDrops = number(webPlayer.get("rejected_feeds"));
        long webAudioDelta = delta("output:web-audio", webAudioLosses, now);
        delta("observer:web-audio", webObserverDrops, now);
        rows.add(row("Browser audio", "Browser audio waiting", number(webPlayer.get("encoder_queue_depth")),
            "calls", webAudioDelta > 0 ? "warning" : webAudioLosses + webObserverDrops > 0 ?
            "info" : "healthy",
            "Published " + number(webPlayer.get("published_calls")) + " · Active listeners " +
                number(webPlayer.get("active_feeds")) + " · Dropped " + webCapacityDrops +
                " · Audio preparation failures " + webEncoderFailures));

        Map<String,Object> diagnostics = map(webStatus.get("diagnostics"));
        if(core != null) core.web(transport, webPlayer, diagnostics);
        long channelSessions = number(diagnostics.get("channel_sessions"));
        long tunerSessions = number(diagnostics.get("tuner_sessions"));
        rows.add(row("Diagnostics", "Open diagnostic views", channelSessions + tunerSessions, "views",
            "info", "Channel views " + channelSessions + " · Tuner views " + tunerSessions));

        measurements.add(section("supporting", "Web and browser audio", rows));
    }

    /** Keeps loss counters tied to their producer, and remembers an assignment after a failed tuner is stopped. */
    private boolean prepareCounterOwner(String scope, TunerController controller, Object sourceManager, long stream)
    {
        CounterOwner previous = mCounterOwners.get(scope);
        boolean receiving = sourceManager instanceof ChannelSourceManager manager && manager.getTunerChannelCount() > 0 ||
            controller == null && previous != null && previous.receiving();
        mCounterOwners.put(scope, new CounterOwner(controller, sourceManager, stream, receiving));
        if(previous == null || previous.controller() != controller || previous.sourceManager() != sourceManager ||
            previous.streamSequence() != stream || previous.receiving() != receiving)
        {
            mCounterBaselines.keySet().removeIf(key -> key.startsWith(scope + ":"));
            mUsbDeliveryClassifiers.remove(scope);
            mIncidents.preserve(scope);
        }
        return receiving;
    }

    private long delta(String key, long current, long now)
    {
        CounterBaseline previous = mCounterBaselines.put(key, new CounterBaseline(current, now));

        if(previous == null)
        {
            return 0;
        }

        return current >= previous.value ? current - previous.value : 0;
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
            return new StorageSnapshot(percent, "Free " + formatBytes(usable) + " of " + formatBytes(total));
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

    private static String formatBytes(long bytes)
    {
        if(bytes < 1_024)
        {
            return Math.max(0, bytes) + " B";
        }

        String[] units = {"KB", "MB", "GB", "TB"};
        double value = bytes;
        int unit = -1;

        do
        {
            value /= 1_024.0;
            unit++;
        }
        while(value >= 1_024.0 && unit < units.length - 1);

        return String.format(Locale.US, value >= 10.0 ? "%.0f %s" : "%.1f %s", value, units[unit]);
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
        return "Peak " + pipeline.ifftHighWaterBatches() + " chunks · Limit " +
            pipeline.ifftCapacityBatches() + " · Lost " + pipeline.ifftDroppedBatches();
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

    private static String text(Object value)
    {
        return value != null ? String.valueOf(value) : "";
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
            "measurements", List.of(), "radioresolve_accepted_calls", 0L);
    }

    private static Path legacySnapshotPath(UserPreferences userPreferences)
    {
        if(userPreferences == null)
        {
            return null;
        }

        try
        {
            Path path = userPreferences.getDirectoryPreference().getDirectoryApplicationLog()
                .resolve(LEGACY_SNAPSHOT_FILE_NAME);
            return path;
        }
        catch(Exception exception)
        {
            LOGGER.warn("Legacy receiver health incident report path is unavailable", exception);
            return null;
        }
    }

    private void removeLegacySnapshotOnce()
    {
        if(mLegacySnapshotCleanupAttempted)
        {
            return;
        }

        mLegacySnapshotCleanupAttempted = true;
        if(mLegacySnapshotPath == null)
        {
            return;
        }

        try
        {
            removeLegacySnapshotFiles(mLegacySnapshotPath);
        }
        catch(Exception exception)
        {
            LOGGER.warn("Legacy receiver health incident report could not be removed", exception);
        }
    }

    static void removeLegacySnapshotFiles(Path target) throws java.io.IOException
    {
        if(target == null)
        {
            return;
        }

        Files.deleteIfExists(target);
        Files.deleteIfExists(target.resolveSibling("." + target.getFileName() + ".tmp"));
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

    /** Measures short diagnostic windows and separately confirms a shortage over at least ten seconds. */
    static final class UsbDeliveryClassifier
    {
        private UsbDeliveryObservation mBaseline;
        private UsbDeliveryObservation mRateBaseline;

        UsbDeliveryAssessment evaluate(UsbDeliveryObservation observation)
        {
            if(!observation.streaming())
            {
                mBaseline = null;
                mRateBaseline = null;
                return UsbDeliveryAssessment.warming(false, 0);
            }

            long lastDeliveryAge = TimeUnit.NANOSECONDS.toMillis(Math.max(0,
                observation.nowNanos() - observation.lastDeliveryTimestampNanos()));
            boolean stopped = lastDeliveryAge >= USB_STALE_DELIVERY_MILLISECONDS;
            UsbDeliveryObservation previous = mBaseline;
            mBaseline = observation;
            boolean usable = previous != null && previous.sequence() == observation.sequence() &&
                observation.nowNanos() - previous.nowNanos() > 0 &&
                observation.requiredBytesPerSecond() == previous.requiredBytesPerSecond() &&
                observation.expectedBytes() >= previous.expectedBytes() &&
                observation.usableBytes() >= previous.usableBytes() &&
                observation.statusCount() >= previous.statusCount() &&
                observation.integrityCount() >= previous.integrityCount() &&
                observation.longGapCount() >= previous.longGapCount();

            if(!usable)
            {
                mRateBaseline = observation;
                return UsbDeliveryAssessment.warming(stopped, lastDeliveryAge);
            }

            long elapsed = observation.nowNanos() - previous.nowNanos();
            long windowMs = TimeUnit.NANOSECONDS.toMillis(elapsed);
            long delivered = observation.usableBytes() - previous.usableBytes();
            boolean rateAvailable = observation.requiredBytesPerSecond() > 0 && windowMs > 0;
            double percent = rateAvailable ? 100.0 * delivered /
                (observation.requiredBytesPerSecond() * elapsed / 1_000_000_000.0) : Double.NaN;
            long sustainedElapsed = observation.nowNanos() - mRateBaseline.nowNanos();
            long sustainedMs = TimeUnit.NANOSECONDS.toMillis(sustainedElapsed);
            double sustainedPercent = Double.NaN;

            if(rateAvailable && sustainedMs >= CONDITION_HOLD_MILLISECONDS)
            {
                sustainedPercent = 100.0 * (observation.usableBytes() - mRateBaseline.usableBytes()) /
                    (observation.requiredBytesPerSecond() * sustainedElapsed / 1_000_000_000.0);
                mRateBaseline = observation;
            }

            return new UsbDeliveryAssessment(rateAvailable, windowMs, delivered, percent,
                observation.statusCount() - previous.statusCount(),
                observation.integrityCount() - previous.integrityCount(),
                observation.longGapCount() - previous.longGapCount(), stopped, lastDeliveryAge,
                sustainedMs, sustainedPercent);
        }
    }

    record UsbDeliveryObservation(long nowNanos, boolean streaming, long sequence, long expectedBytes,
                                  long usableBytes, long statusCount, long integrityCount, long longGapCount,
                                  long lastDeliveryTimestampNanos, double requiredBytesPerSecond)
    {
    }

    record UsbDeliveryAssessment(boolean rateAvailable, long windowMilliseconds, long usableBytesDelta,
                                 double rawDeliveryPercent, long statusDelta, long integrityDelta,
                                 long gapDelta, boolean stopped, long lastDeliveryAgeMilliseconds,
                                 long sustainedWindowMilliseconds, double sustainedDeliveryPercent)
    {
        private static UsbDeliveryAssessment warming(boolean stopped, long lastDeliveryAgeMilliseconds)
        {
            return new UsbDeliveryAssessment(false, 0, 0, Double.NaN, 0, 0, 0, stopped,
                lastDeliveryAgeMilliseconds, 0, Double.NaN);
        }

        boolean sustainedShortage()
        {
            return Double.isFinite(sustainedDeliveryPercent) && sustainedDeliveryPercent < USB_LOW_DELIVERY_PERCENT;
        }
    }

    private record CounterOwner(TunerController controller, Object sourceManager, long streamSequence, boolean receiving)
    {
    }

    private record CounterBaseline(long value, long lastSeenMs)
    {
    }

    private record ControlContinuity(ControlChannelQualityMonitor owner, long lifecycleGeneration,
                                     long processingIncarnation, long validFrameSequence, long lastProgressNanos)
    {
    }

    private record StorageSnapshot(double freePercent, String detail)
    {
        private static StorageSnapshot unavailable()
        {
            return new StorageSnapshot(-1, "Storage information is unavailable.");
        }
    }
}
