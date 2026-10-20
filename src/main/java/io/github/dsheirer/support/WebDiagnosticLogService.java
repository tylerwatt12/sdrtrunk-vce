/* Copyright (C) 2026. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.preference.application.ApplicationPreference;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Optional, bounded diagnostic observer. HTTP, listener and transport workers offer immutable records without
 * waiting for queue capacity. Projection, redaction, correlation, rotation and disk work belong to the lower-priority
 * worker. Receiver health has a separate latest-sample slot; no queue producer runs on a receiver callback.
 */
public final class WebDiagnosticLogService implements AutoCloseable
{
    public static final String FILE_NAME = "sdrtrunk_web.log";
    public static final int RETENTION_DAYS = 7;
    public static final long MAXIMUM_BYTES = 100L * 1024 * 1024;
    static final int QUEUE_CAPACITY = 512;
    static final int CONTEXT_SAMPLES = 60;
    private static final int MAXIMUM_RECORD_BYTES = 64 * 1024;
    private static final int MAXIMUM_FILES = 128;
    private static final long AFTER_ALERT_MS = 30_000;
    private static final long SUMMARY_INTERVAL_MS = 5_000;
    private static final Pattern ARCHIVE = Pattern.compile("([0-9]{8})_[0-9]{6}_[0-9]{3}_[0-9]{1,10}_sdrtrunk_web\\.log");
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("uuuuMMdd HHmmss.SSS");
    private static final DateTimeFormatter ARCHIVE_TIME = DateTimeFormatter.ofPattern("uuuuMMdd_HHmmss_SSS");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> EVENT_FIELDS = Set.of("topic", "client_id", "active_clients", "reason", "count",
        "duration_ms", "enabled", "port", "tls", "https", "failure_count", "operation");
    private static final Set<String> EVENT_KINDS = Set.of("listener_started", "listener_stopped", "listener_failed",
        "request_queue_full", "live_connected", "live_disconnected", "live_slow_disconnect", "stream_topic_open",
        "stream_topic_close", "stream_topic_failed");
    private static final Set<String> HEALTH_FIELDS = Set.of("generated_at_ms", "started_at_ms", "summary", "active",
        "measurements", "severity", "active_count", "warning_count", "critical_count", "occurrence_id", "code",
        "title", "scope", "opened_at_ms", "last_seen_ms", "resolved_at_ms", "count", "observed", "likely_cause",
        "impact", "check_next", "id", "rows", "label", "value", "unit", "detail", "system_name", "channel_name",
        "configuration_id", "frequency_hz", "wacn", "system_id", "rfss", "site_id");
    private static final Set<String> COMPACT_HEALTH_FIELDS = Set.of("generated_at_ms", "started_at_ms", "summary", "active",
        "severity", "active_count", "warning_count", "critical_count", "occurrence_id", "code", "title", "scope",
        "opened_at_ms", "last_seen_ms", "count", "observed", "likely_cause", "impact", "check_next");
    private final Supplier<Path> mDirectory;
    private final ApplicationPreference mPreferences;
    private final LongSupplier mClock;
    private final long mMaximumBytes;
    private final long mRotationBytes;
    private final Runnable mBeforeProjection;
    private final ApplicationLogService mReader;
    private final Semaphore mDownloadReader = new Semaphore(1);
    private final Semaphore mActiveDownloads = new Semaphore(2);
    private final ArrayBlockingQueue<Pending> mQueue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicLong mGeneration = new AtomicLong(1);
    private final AtomicLong mRequestSequence = new AtomicLong();
    private final AtomicLong mDropped = new AtomicLong();
    private final AtomicLong mDroppedRequests = new AtomicLong();
    private final AtomicLong mDroppedEvents = new AtomicLong();
    private final AtomicLong mCoalescedHealth = new AtomicLong();
    private final AtomicLong mDroppedHealth = new AtomicLong();
    private long mDuplicateHealth;
    private final AtomicLong mProcessedHealth = new AtomicLong();
    private final AtomicReference<Pending> mLatestHealth = new AtomicReference<>();
    private final WebDiagnosticRequestMetrics mViewerMetrics = new WebDiagnosticRequestMetrics();
    private final AtomicLong mWritten = new AtomicLong();
    private final AtomicBoolean mClosed = new AtomicBoolean();
    private final ArrayDeque<HealthSample> mContext = new ArrayDeque<>(CONTEXT_SAMPLES);
    private final Thread mWorker;
    private volatile boolean mEnabled;
    private volatile boolean mActive;
    private volatile boolean mCanDownload;
    private volatile String mLastError;
    // The following fields are worker-owned.
    private FileChannel mOutput;
    private Path mOutputDirectory;
    private Object mOutputFileKey;
    private LocalDate mOutputDay;
    private long mWorkerGeneration;
    private long mReportedDropped;
    private long mAfterUntil;
    private long mLastContextWritten;
    private long mLastHealthSecond = Long.MIN_VALUE;
    private long mLastBaseline;
    private long mArchiveSequence;
    private Path mConfiguredDirectory;
    private long mRetainedBytes;
    private long mCurrentBytes;
    private long mSummaryAt;
    private long mSummaryWritten;
    private long mQueueDelayNanos;
    private long mMaximumQueueDelayNanos;
    private long mProjectedRecords;
    private long mProjectionNanos;
    private long mMaximumProjectionNanos;
    private long mWriteNanos;
    private long mMaximumWriteNanos;
    private long mBytesWritten;
    private long mMaintenanceNanos;
    private long mMaintenanceScans;
    private long mRotations;
    private long mRotationNanos;
    private long mContextFlushNanos;
    private long mMaximumContextFlushNanos;
    private Map<String,Map<String,Object>> mAlerts = Map.of();
    private long mLiveClients;
    private final Map<String,Long> mStreamTopics = new LinkedHashMap<>();

    public WebDiagnosticLogService(Supplier<Path> directory, ApplicationPreference preferences)
    {
        this(directory, preferences, System::currentTimeMillis, MAXIMUM_BYTES, 10L * 1024 * 1024, () -> {});
    }

    WebDiagnosticLogService(Supplier<Path> directory, ApplicationPreference preferences, LongSupplier clock,
                            long maximumBytes, long rotationBytes, Runnable beforeProjection)
    {
        mDirectory = Objects.requireNonNull(directory);
        mPreferences = Objects.requireNonNull(preferences);
        mClock = Objects.requireNonNull(clock);
        mMaximumBytes = maximumBytes;
        mRotationBytes = Math.min(rotationBytes, maximumBytes);
        mBeforeProjection = Objects.requireNonNull(beforeProjection);
        mReader = new ApplicationLogService(directory, FILE_NAME, ARCHIVE);
        mEnabled = preferences.isWebDiagnosticLoggingEnabled();
        mWorker = new Thread(this::run, "web diagnostic log writer");
        mWorker.setDaemon(true);
        mWorker.setPriority(Thread.MIN_PRIORITY);
        mWorker.start();
    }

    public Status status()
    {
        return new Status(mEnabled, mEnabled && mActive, FILE_NAME, RETENTION_DAYS, mMaximumBytes,
            mQueue.size() + (mLatestHealth.get() != null ? 1 : 0), mDropped.get(), mWritten.get(),
            mLastError, mCanDownload);
    }

    public boolean isEnabled() { return mEnabled && !mClosed.get(); }

    /** Capture identity for bounded aggregate observers; zero avoids any collection while logging is off. */
    public long captureGeneration() { return isEnabled() ? mGeneration.get() : 0; }
    public WebDiagnosticRequestMetrics viewerMetrics() { return mViewerMetrics; }
    long processedHealthSamples() { return mProcessedHealth.get(); }

    /** Only settings request threads use this short control lock; no producer acquires it. */
    public synchronized Status setEnabled(boolean enabled)
    {
        if(mClosed.get()) throw new IllegalStateException("Diagnostic logging is closed");
        mPreferences.setWebDiagnosticLoggingEnabled(enabled);
        if(enabled != mEnabled)
        {
            mGeneration.incrementAndGet();
            mEnabled = enabled;
            if(!enabled) mActive = false;
            LockSupport.unpark(mWorker);
        }
        return status();
    }

    public long requestStarted(String method, String normalizedRoute)
    {
        if(!mEnabled || mClosed.get()) return 0;
        long generation = mGeneration.get();
        long id = (generation << 32) | (mRequestSequence.incrementAndGet() & 0xffffffffL);
        submit(new Pending(generation, mClock.getAsLong(), "request_started", null, method, normalizedRoute,
            id, 0, 0, false));
        return id;
    }

    public void requestFinished(long requestId, String method, String normalizedRoute, long durationNanos,
                                int responseCode, boolean failed)
    {
        long generation = mGeneration.get();
        if(requestId == 0 || !mEnabled || mClosed.get() || (requestId >>> 32) != generation) return;
        submit(new Pending(generation, mClock.getAsLong(), "request_finished", null, method, normalizedRoute,
            requestId, durationNanos, responseCode, failed));
    }

    /** HTTP/listener/transport workers only; immutable bounded fields are projected on the diagnostic worker. */
    public void event(String kind, Map<String,Object> fields)
    {
        if(mEnabled && !mClosed.get())
            submit(new Pending(mGeneration.get(), mClock.getAsLong(), kind, fields, null, null, 0, 0, 0, false));
    }

    /** Called by the existing receiver-health observer, never by a hardware/decoder callback. */
    public void recordHealth(Map<String,Object> immutableSnapshot)
    {
        recordHealth(immutableSnapshot, null);
    }

    /** Numeric core is built with fixed field/device limits by the observer and never shares the text budget. */
    public void recordHealth(Map<String,Object> immutableSnapshot, Map<String,Object> immutableCore)
    {
        if(mEnabled && !mClosed.get())
        {
            long generation = mGeneration.get();
            if(immutableCore != null && immutableCore.get("capture_generation") instanceof Number capture &&
                capture.longValue() != generation) return;
            Pending pending = new Pending(generation, mClock.getAsLong(), "receiver_health", immutableSnapshot,
                null, null, 0, 0, 0, false, immutableCore, System.nanoTime());
            Pending previous = mLatestHealth.get();
            if(generation != mGeneration.get() || !mEnabled || previous != null && previous.generation() > generation) return;
            // A paused producer from an earlier capture cannot overwrite a newly admitted sample. One attempt
            // also bounds contention with the consumer: lose the observation rather than retrying or waiting.
            if(!mLatestHealth.compareAndSet(previous, pending))
            {
                dropped(pending);
                return;
            }
            if(previous != null)
            {
                mCoalescedHealth.incrementAndGet();
                mDropped.incrementAndGet();
            }
            LockSupport.unpark(mWorker);
        }
    }

    private void dropped(Pending pending)
    {
        mDropped.incrementAndGet();
        if(pending.requestId() != 0) mDroppedRequests.incrementAndGet();
        else if("receiver_health".equals(pending.kind())) mDroppedHealth.incrementAndGet();
        else mDroppedEvents.incrementAndGet();
    }

    private void submit(Pending pending)
    {
        if(!mQueue.offer(pending))
        {
            dropped(pending);
            return;
        }
        LockSupport.unpark(mWorker);
    }

    private void run()
    {
        long maintenanceAt = 0;
        long retryAt = 0;
        Pending carried = null;
        try
        {
            while(!mClosed.get())
            {
                long now = mClock.getAsLong();
                try
                {
                    long generation = mGeneration.get();
                    if(generation != mWorkerGeneration)
                    {
                        if(mOutput != null) write(now, "logging_stopped", Map.of(), true);
                        closeOutput();
                        mContext.clear();
                        mAlerts = Map.of();
                        mAfterUntil = 0;
                        mLastHealthSecond = Long.MIN_VALUE;
                        mLastContextWritten = 0;
                        mLastBaseline = 0;
                        mLiveClients = 0;
                        mStreamTopics.clear();
                        mWorkerGeneration = generation;
                        mSummaryAt = now + SUMMARY_INTERVAL_MS;
                        mSummaryWritten = mWritten.get();
                        retryAt = 0;
                    }
                    if(now >= maintenanceAt)
                    {
                        maintain(now, 0, false);
                        maintenanceAt = now + 60_000;
                    }
                    Pending pending = carried != null ? carried : mLatestHealth.getAndSet(null);
                    if(pending == null) pending = mQueue.poll();
                    carried = null;
                    long currentGeneration = mGeneration.get();
                    if(currentGeneration != generation)
                    {
                        // A new capture can publish while maintenance is running. Preserve its already-admitted
                        // first record while the next loop adopts that epoch instead of discarding it as stale.
                        if(pending != null && pending.generation() == currentGeneration) carried = pending;
                        continue;
                    }
                    if(!mEnabled)
                    {
                        if(pending == null) LockSupport.parkNanos(100_000_000L);
                        continue;
                    }
                    if(pending != null && pending.generation() != generation) continue;
                    if(now < retryAt)
                    {
                        if(pending != null) dropped(pending);
                        LockSupport.parkNanos(100_000_000L);
                        continue;
                    }
                    if(mOutput == null)
                    {
                        String version = WebDiagnosticLogService.class.getPackage().getImplementationVersion();
                        write(now, "logging_started", Map.of("retention_days", RETENTION_DAYS,
                            "maximum_bytes", mMaximumBytes, "process_id", ProcessHandle.current().pid(),
                            "process_started_at", ProcessHandle.current().info().startInstant().orElse(Instant.EPOCH).toString(),
                            "java_version", System.getProperty("java.version", "unknown"), "time_zone", ZoneId.systemDefault().getId(),
                            "version", ApplicationLogService.redact(version != null ? version : "development")));
                    }
                    long dropped = mDropped.get();
                    if(dropped != mReportedDropped)
                    {
                        write(now, "diagnostic_gap", Map.of("dropped_records", dropped,
                            "newly_dropped_records", dropped - mReportedDropped));
                        mReportedDropped = dropped;
                    }
                    summaries(now, generation);
                    if(pending == null)
                    {
                        LockSupport.parkNanos(100_000_000L);
                        continue;
                    }
                    long queued = Math.max(0, System.nanoTime() - pending.queuedNanos());
                    mQueueDelayNanos += queued;
                    mMaximumQueueDelayNanos = Math.max(mMaximumQueueDelayNanos, queued);
                    mBeforeProjection.run();
                    if(!mEnabled || pending.generation() != mGeneration.get()) continue;
                    long projectionStarted = System.nanoTime();
                    if("receiver_health".equals(pending.kind()))
                    {
                        health(pending);
                        mProcessedHealth.incrementAndGet();
                    }
                    else
                    {
                        Map<String,Object> fields = project(pending);
                        activity(pending.kind(), fields);
                        projected(System.nanoTime() - projectionStarted);
                        write(pending.time(), safeKind(pending.kind()), fields);
                    }
                }
                catch(Exception exception)
                {
                    mLastError = "Diagnostic logs could not be written. Check available disk space and the application log folder.";
                    mActive = false;
                    mDropped.incrementAndGet();
                    closeOutput();
                    retryAt = now + 5_000;
                    maintenanceAt = now + 5_000;
                    LockSupport.parkNanos(100_000_000L);
                }
            }
        }
        finally
        {
            if(mOutput != null)
            {
                try { write(mClock.getAsLong(), "logging_stopped", Map.of(), true); }
                catch(Exception ignored) { }
            }
            closeOutput();
        }
    }

    private void projected(long nanos)
    {
        mProjectedRecords++;
        mProjectionNanos += nanos;
        mMaximumProjectionNanos = Math.max(mMaximumProjectionNanos, nanos);
    }

    private void summaries(long now, long generation) throws IOException
    {
        if(now < mSummaryAt) return;
        mSummaryAt = now + SUMMARY_INTERVAL_MS;
        Map<String,Object> viewer = mViewerMetrics.snapshotAndReset(generation, now);
        if(!viewer.isEmpty()) write(now, "viewer_activity", viewer);
        if(mWritten.get() == mSummaryWritten) return;
        Map<String,Object> fields = new LinkedHashMap<>();
        fields.put("capture_generation", generation);
        fields.put("counter_scope", "process");
        fields.put("projected_records", mProjectedRecords);
        fields.put("health_samples_processed", mProcessedHealth.get());
        fields.put("queue_delay_ms", mQueueDelayNanos / 1_000_000.0);
        fields.put("maximum_queue_delay_ms", mMaximumQueueDelayNanos / 1_000_000.0);
        fields.put("projection_ms", mProjectionNanos / 1_000_000.0);
        fields.put("maximum_projection_ms", mMaximumProjectionNanos / 1_000_000.0);
        fields.put("writer_ms", mWriteNanos / 1_000_000.0);
        fields.put("maximum_writer_ms", mMaximumWriteNanos / 1_000_000.0);
        fields.put("bytes_written", mBytesWritten);
        fields.put("maintenance_ms", mMaintenanceNanos / 1_000_000.0);
        fields.put("maintenance_scans", mMaintenanceScans);
        fields.put("rotations", mRotations);
        fields.put("rotation_ms", mRotationNanos / 1_000_000.0);
        fields.put("context_flush_ms", mContextFlushNanos / 1_000_000.0);
        fields.put("maximum_context_flush_ms", mMaximumContextFlushNanos / 1_000_000.0);
        fields.put("dropped_requests", mDroppedRequests.get());
        fields.put("dropped_events", mDroppedEvents.get());
        fields.put("coalesced_health_samples", mCoalescedHealth.get());
        fields.put("dropped_health_samples", mDroppedHealth.get());
        fields.put("duplicate_health_samples", mDuplicateHealth);
        write(now, "logging_overhead", fields);
        mSummaryWritten = mWritten.get();
    }

    private Map<String,Object> project(Pending pending)
    {
        if(pending.requestId() != 0)
        {
            Map<String,Object> fields = new LinkedHashMap<>();
            fields.put("request_id", pending.requestId());
            fields.put("method", Set.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "HEAD")
                .contains(pending.method() != null ? pending.method() : "") ? pending.method() : "OTHER");
            fields.put("route", safeRoute(pending.route()));
            if("request_finished".equals(pending.kind()))
            {
                fields.put("duration_ms", Math.max(0, pending.durationNanos()) / 1_000_000.0);
                fields.put("status", Math.max(0, Math.min(599, pending.responseCode())));
                fields.put("failed", pending.failed());
            }
            return fields;
        }
        return projection(pending.fields(), EVENT_FIELDS);
    }

    private void activity(String kind, Map<String,Object> fields)
    {
        if("live_connected".equals(kind)) mLiveClients++;
        else if("live_disconnected".equals(kind)) mLiveClients = Math.max(0, mLiveClients - 1);
        if(("stream_topic_open".equals(kind) || "stream_topic_close".equals(kind)) && fields.get("topic") instanceof String topic)
        {
            if(mStreamTopics.size() < 32 || mStreamTopics.containsKey(topic))
                mStreamTopics.put(topic, Math.max(0, mStreamTopics.getOrDefault(topic, 0L) +
                    ("stream_topic_open".equals(kind) ? 1 : -1)));
        }
    }

    private void health(Pending pending) throws IOException
    {
        long projectionStarted = System.nanoTime();
        Map<String,Object> safe = pending.core() == null ? projection(pending.fields(), HEALTH_FIELDS) :
            projection(pending.fields(), COMPACT_HEALTH_FIELDS, new ProjectionBudget(256, 2_048));
        if(pending.core() != null) safe.put("core", pending.core());
        safe.put("writer_queue_delay_ms", Math.max(0, System.nanoTime() - pending.queuedNanos()) / 1_000_000.0);
        safe.put("web_activity", Map.of("recorded_live_clients", mLiveClients, "recorded_stream_topics", Map.copyOf(mStreamTopics)));
        long time = safe.get("generated_at_ms") instanceof Number number ? number.longValue() : pending.time();
        if(time / 1_000 == mLastHealthSecond) { mDuplicateHealth++; return; }
        mLastHealthSecond = time / 1_000;
        Map<String,Map<String,Object>> alerts = new LinkedHashMap<>();
        Object originalActive = pending.fields() != null ? pending.fields().get("active") : null;
        boolean activeComplete = originalActive instanceof List<?> original && safe.get("active") instanceof List<?> projected &&
            original.size() == projected.size();
        if(safe.get("active") instanceof List<?> active)
        {
            for(Object value: active)
            {
                if(value instanceof Map<?,?> map)
                {
                    @SuppressWarnings("unchecked") Map<String,Object> alert = (Map<String,Object>)map;
                    if(alert.get("occurrence_id") instanceof Number identity && identity.longValue() > 0 &&
                        alert.get("severity") instanceof String severity && Set.of("info", "warning", "critical").contains(severity))
                        alerts.put(Long.toString(identity.longValue()), alert);
                    else activeComplete = false;
                }
            }
        }
        List<Map<String,Object>> changes = new ArrayList<>();
        alerts.forEach((key, alert) -> {
            Map<String,Object> previous = mAlerts.get(key);
            if(previous == null || !Objects.equals(previous.get("severity"), alert.get("severity")))
                changes.add(Map.of("change", previous == null ? "opened" : "severity_changed", "alert", alert));
        });
        if(activeComplete)
        {
            mAlerts.forEach((key, alert) -> {
                if(!alerts.containsKey(key)) changes.add(Map.of("change", "resolved", "alert", alert));
            });
            mAlerts = alerts;
        }
        else
        {
            safe.put("active_projection_incomplete", true);
            Map<String,Map<String,Object>> merged = new LinkedHashMap<>(mAlerts);
            alerts.forEach((key, alert) -> { if(merged.size() < 256 || merged.containsKey(key)) merged.put(key, alert); });
            mAlerts = merged;
        }
        projected(System.nanoTime() - projectionStarted);
        if(mContext.size() == CONTEXT_SAMPLES) mContext.removeFirst();
        mContext.addLast(new HealthSample(time, safe));
        if(!changes.isEmpty())
        {
            long flushStarted = System.nanoTime();
            for(HealthSample sample: mContext)
            {
                if(sample.time() > mLastContextWritten)
                {
                    write(sample.time(), "receiver_context", sample.fields());
                    mLastContextWritten = sample.time();
                }
            }
            for(int index = 0; index < changes.size(); index++)
                write(time, "receiver_alert", Map.of("changes", List.of(changes.get(index)),
                    "change_index", index + 1, "change_count", changes.size()));
            long flushNanos = System.nanoTime() - flushStarted;
            mContextFlushNanos += flushNanos;
            mMaximumContextFlushNanos = Math.max(mMaximumContextFlushNanos, flushNanos);
            mAfterUntil = time + AFTER_ALERT_MS;
        }
        else
        {
            if(time <= mAfterUntil)
            {
                write(time, "receiver_context", safe);
                mLastContextWritten = time;
            }
            else if(time - mLastBaseline >= 10_000)
            {
                write(time, "receiver_health", safe);
                mLastBaseline = time;
            }
        }
    }

    private static Map<String,Object> projection(Map<?,?> input, Set<String> allowed)
    {
        return projection(input, allowed, new ProjectionBudget(1_000, 12_000));
    }

    private static Map<String,Object> projection(Map<?,?> input, Set<String> allowed, ProjectionBudget budget)
    {
        Map<String,Object> result = projectMap(input, allowed, 0, budget);
        if(budget.truncated) result.put("projection_truncated", true);
        return result;
    }

    private static Map<String,Object> projectMap(Map<?,?> input, Set<String> allowed, int depth, ProjectionBudget budget)
    {
        Map<String,Object> result = new LinkedHashMap<>();
        if(input == null || depth > 6) return result;
        List<String> ordered = new ArrayList<>(allowed);
        // Preserve alert identity before using the remaining budget for large measurement tables.
        ordered.sort(Comparator.comparingInt(key -> switch(key) {
            case "generated_at_ms", "active", "occurrence_id", "code", "scope", "severity" -> 0;
            case "measurements", "rows", "detail" -> 2;
            default -> 1;
        }));
        for(String key: ordered)
        {
            if(budget.nodes <= 0) { budget.truncated = true; break; }
            Object value = input.get(key);
            if(value == null) continue;
            Object safe = projectValue(value, allowed, depth + 1, budget);
            if(safe != null) result.put(key, safe);
        }
        return result;
    }

    private static Object projectValue(Object value, Set<String> allowed, int depth, ProjectionBudget budget)
    {
        if(depth > 7 || --budget.nodes < 0) { budget.truncated = true; return null; }
        if(value instanceof String text)
        {
            int length = Math.min(text.length(), Math.min(1_024, budget.characters));
            if(length < text.length()) budget.truncated = true;
            budget.characters -= length;
            return ApplicationLogService.redact(text.substring(0, length));
        }
        if(value instanceof Boolean || value instanceof Byte || value instanceof Short || value instanceof Integer ||
            value instanceof Long) return value;
        if(value instanceof Double number && Double.isFinite(number)) return value;
        if(value instanceof Float number && Float.isFinite(number)) return value;
        if(value instanceof Map<?,?> map) return projectMap(map, allowed, depth, budget);
        if(value instanceof List<?> list)
        {
            List<Object> result = new ArrayList<>();
            if(list.size() > 128) budget.truncated = true;
            for(int index = 0; index < Math.min(128, list.size()); index++)
            {
                if(budget.nodes <= 0) { budget.truncated = true; break; }
                Object safe = projectValue(list.get(index), allowed, depth + 1, budget);
                if(safe != null) result.add(safe);
            }
            return result;
        }
        return null;
    }

    private static String safeKind(String kind)
    {
        return "request_started".equals(kind) || "request_finished".equals(kind) ||
            EVENT_KINDS.contains(kind != null ? kind : "") ? kind : "web_event";
    }

    private static String safeRoute(String route)
    {
        // Only route templates supplied by the server; query values, URL escapes and punctuation never survive.
        return route != null && route.length() <= 160 && route.matches("/[a-zA-Z0-9_/:.*{}-]*") &&
            !route.contains("..") && !route.contains("@") ? ApplicationLogService.redact(route) : "other";
    }

    private void write(long time, String kind, Map<String,Object> fields) throws IOException
    {
        write(time, kind, fields, false);
    }

    private void write(long time, String kind, Map<String,Object> fields, boolean finalRecord) throws IOException
    {
        if(!finalRecord && (!mEnabled || mWorkerGeneration != mGeneration.get() || mClosed.get())) return;
        long writeStarted = System.nanoTime();
        Map<String,Object> timed = new LinkedHashMap<>(fields);
        timed.put("observed_at_ms", time);
        timed.put("recorded_at_ms", mClock.getAsLong());
        String line = LOG_TIME.format(Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault())) +
            " [web diagnostic writer] " + level(kind, fields) + " web.diagnostic - " + kind + " " + MAPPER.writeValueAsString(timed) + "\n";
        byte[] bytes = ApplicationLogService.redact(line).getBytes(StandardCharsets.UTF_8);
        if(bytes.length > MAXIMUM_RECORD_BYTES || bytes.length > mMaximumBytes)
        {
            mDropped.incrementAndGet();
            return;
        }
        maintain(mClock.getAsLong(), bytes.length, true);
        if(!finalRecord && (!mEnabled || mWorkerGeneration != mGeneration.get() || mClosed.get())) return;
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        while(buffer.hasRemaining()) mOutput.write(buffer);
        mCurrentBytes += bytes.length;
        mRetainedBytes += bytes.length;
        mBytesWritten += bytes.length;
        long writeNanos = System.nanoTime() - writeStarted;
        mWriteNanos += writeNanos;
        mMaximumWriteNanos = Math.max(mMaximumWriteNanos, writeNanos);
        mWritten.incrementAndGet();
        mCanDownload = true;
        mActive = mEnabled;
        mLastError = null;
    }

    private static String level(String kind, Map<String,Object> fields)
    {
        if("diagnostic_gap".equals(kind) || "request_queue_full".equals(kind) || "live_slow_disconnect".equals(kind)) return "WARN";
        if(kind.endsWith("_failed")) return "ERROR";
        if("request_finished".equals(kind))
        {
            int status = fields.get("status") instanceof Number number ? number.intValue() : 0;
            if(Boolean.TRUE.equals(fields.get("failed")) || status >= 500) return "ERROR";
            if(status >= 400) return "WARN";
        }
        if("receiver_alert".equals(kind) && fields.get("changes") instanceof List<?> changes)
        {
            String result = "INFO";
            for(Object value: changes)
            {
                if(value instanceof Map<?,?> change && !"resolved".equals(change.get("change")) &&
                    change.get("alert") instanceof Map<?,?> alert)
                {
                    if("critical".equals(alert.get("severity"))) return "ERROR";
                    if("warning".equals(alert.get("severity"))) result = "WARN";
                }
            }
            return result;
        }
        return "INFO";
    }

    /** Reserve space before writing; the opened writer alone owns the cached byte counts between scans. */
    private void maintain(long now, int incomingBytes, boolean writing) throws IOException
    {
        Path configured = Objects.requireNonNull(mDirectory.get()).toAbsolutePath().normalize();
        LocalDate day = Instant.ofEpochMilli(now).atZone(ZoneId.systemDefault()).toLocalDate();
        // The common write path makes no directory scan or metadata query. A periodic scan reconciles external
        // changes; the existing NOFOLLOW channel cannot follow a replacement symlink between those scans.
        if(writing && mOutput != null && configured.equals(mConfiguredDirectory) && day.equals(mOutputDay) &&
            mCurrentBytes + incomingBytes <= mRotationBytes && mRetainedBytes + incomingBytes <= mMaximumBytes) return;
        long maintenanceStarted = System.nanoTime();
        try
        {
            if(writing) Files.createDirectories(configured);
            if(!Files.isDirectory(configured)) { closeOutput(); mCanDownload = false; return; }
            Path directory = configured.toRealPath();
            if(mOutputDirectory != null && !mOutputDirectory.equals(directory)) closeOutput();
            Path current = directory.resolve(FILE_NAME);
            if(!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) closeOutput();
            else
            {
                BasicFileAttributes currentAttributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if(!currentAttributes.isRegularFile()) throw new IOException("Unsafe log file");
                if(mOutput != null && !Objects.equals(mOutputFileKey, currentAttributes.fileKey())) closeOutput();
                LocalDate fileDay = mOutputDay != null ? mOutputDay : Files.getLastModifiedTime(current,
                    LinkOption.NOFOLLOW_LINKS).toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                if(!day.equals(fileDay) || incomingBytes > 0 && Files.size(current) + incomingBytes > mRotationBytes)
                {
                    long rotationStarted = System.nanoTime();
                    closeOutput();
                    Path archive;
                    do
                    {
                        archive = directory.resolve(ARCHIVE_TIME.format(Instant.ofEpochMilli(now)
                            .atZone(ZoneId.systemDefault())) + "_" + String.format(java.util.Locale.ROOT, "%010d", ++mArchiveSequence) + "_" + FILE_NAME);
                    }
                    while(Files.exists(archive, LinkOption.NOFOLLOW_LINKS));
                    Files.move(current, archive);
                    mRotations++;
                    mRotationNanos += System.nanoTime() - rotationStarted;
                }
            }
            List<RetainedFile> files = retained(directory);
            mMaintenanceScans++;
            long total = files.stream().mapToLong(RetainedFile::size).sum();
            long currentBytes = 0;
            long cutoff = now - RETENTION_DAYS * 86_400_000L;
            for(RetainedFile file: files)
            {
                if(file.retainedSince() < cutoff || total + incomingBytes > mMaximumBytes)
                {
                    if(file.path().equals(current)) closeOutput();
                    Files.delete(file.path());
                    total -= file.size();
                }
                else if(file.path().equals(current)) currentBytes = file.size();
            }
            mCanDownload = total > 0;
            mRetainedBytes = total;
            mCurrentBytes = currentBytes;
            mConfiguredDirectory = configured;
            if(writing && mOutput == null)
            {
                mOutput = FileChannel.open(current, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND, LinkOption.NOFOLLOW_LINKS);
                mOutputDirectory = directory;
                mOutputFileKey = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
                mOutputDay = day;
            }
        }
        finally { mMaintenanceNanos += System.nanoTime() - maintenanceStarted; }
    }

    private static List<RetainedFile> retained(Path directory) throws IOException
    {
        List<RetainedFile> files = new ArrayList<>();
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*sdrtrunk_web.log"))
        {
            for(Path file: stream)
            {
                String name = file.getFileName().toString();
                if(!FILE_NAME.equals(name) && !ARCHIVE.matcher(name).matches()) continue;
                if(files.size() >= MAXIMUM_FILES) throw new IOException("Diagnostic archive limit exceeded");
                BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if(!attributes.isRegularFile()) continue;
                long modifiedAt = attributes.lastModifiedTime().toMillis();
                long createdAt = attributes.creationTime().toMillis();
                // Age daily files from their beginning, rather than their final append. Some filesystems report
                // modification time as creation time; conservatively allow a preceding day in that case.
                long retainedSince = createdAt > 0 && createdAt < modifiedAt ? createdAt : modifiedAt - 86_400_000L;
                files.add(new RetainedFile(file, attributes.size(), modifiedAt, retainedSince, attributes.fileKey()));
            }
        }
        files.sort(Comparator.comparingLong(RetainedFile::modifiedAt).thenComparing(value -> value.path().getFileName().toString()));
        return files;
    }

    public ApplicationLogService.Snapshot snapshot(String log, String after, String revision) throws IOException
    {
        return mReader.snapshot(log, after, revision);
    }

    /** Builds a bounded, redacted private temporary ZIP; browser writes hold no writer/reader admission. */
    public Download download() throws IOException
    {
        if(mClosed.get() || !mActiveDownloads.tryAcquire()) throw new ApplicationLogService.BusyException();
        if(!mDownloadReader.tryAcquire())
        {
            mActiveDownloads.release();
            throw new ApplicationLogService.BusyException();
        }
        Path temporary = null;
        try
        {
            Path directory = Objects.requireNonNull(mDirectory.get()).toRealPath();
            List<RetainedFile> files = retained(directory);
            if(files.isEmpty()) throw new IOException("No diagnostic logs available");
            if(java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix"))
                temporary = Files.createTempFile("sdrtrunk-web-diagnostics-", ".zip",
                    java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")));
            else temporary = Files.createTempFile("sdrtrunk-web-diagnostics-", ".zip");
            long remaining = mMaximumBytes;
            long outputRemaining = mMaximumBytes;
            try(ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(temporary)))
            {
                for(RetainedFile file: files)
                {
                    if(file.size() > remaining) throw new IOException("Diagnostic download exceeds limit");
                    remaining -= file.size();
                    try(FileChannel channel = FileChannel.open(file.path(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))
                    {
                        BasicFileAttributes opened = Files.readAttributes(file.path(), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                        if(!opened.isRegularFile() || !Objects.equals(file.fileKey(), opened.fileKey()))
                            throw new IOException("Diagnostic log changed while opening");
                        zip.putNextEntry(new ZipEntry(file.path().getFileName().toString()));
                        try(var input = new BoundedChannelReader(channel, file.size()))
                        {
                            StringBuilder line = new StringBuilder();
                            int value;
                            while((value = input.read()) >= 0)
                            {
                                if(value == '\n')
                                {
                                    outputRemaining = writeSanitized(zip, line.toString() + "\n", outputRemaining);
                                    line.setLength(0);
                                }
                                else if(line.length() >= MAXIMUM_RECORD_BYTES) throw new IOException("Diagnostic entry exceeds limit");
                                else line.append((char)value);
                            }
                            if(!line.isEmpty()) outputRemaining = writeSanitized(zip, line.toString(), outputRemaining);
                        }
                        zip.closeEntry();
                    }
                }
            }
            return new Download(temporary, mActiveDownloads::release);
        }
        catch(IOException | RuntimeException exception)
        {
            try { if(temporary != null) Files.deleteIfExists(temporary); }
            catch(IOException cleanup) { exception.addSuppressed(cleanup); }
            finally { mActiveDownloads.release(); }
            throw exception;
        }
        finally
        {
            mDownloadReader.release();
        }
    }

    private static long writeSanitized(ZipOutputStream zip, String text, long remaining) throws IOException
    {
        byte[] bytes = ApplicationLogService.redact(text).getBytes(StandardCharsets.UTF_8);
        if(bytes.length > remaining) throw new IOException("Sanitized diagnostic download exceeds limit");
        zip.write(bytes);
        return remaining - bytes.length;
    }

    private void closeOutput()
    {
        if(mOutput != null)
        {
            try { mOutput.close(); } catch(IOException ignored) { }
            mOutput = null;
        }
        mOutputDirectory = null;
        mOutputFileKey = null;
        mOutputDay = null;
        mActive = false;
    }

    @Override public void close()
    {
        if(mClosed.compareAndSet(false, true))
        {
            mEnabled = false;
            mActive = false;
            mReader.close();
            LockSupport.unpark(mWorker);
            try { mWorker.join(1_000); }
            catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
        }
    }

    public record Status(boolean enabled, boolean active, String fileName, int retentionDays, long maximumBytes,
                         int queueDepth, long droppedRecords, long writtenRecords, String lastError, boolean canDownload) { }
    public static final class Download implements AutoCloseable
    {
        private final Path mPath;
        private final Runnable mRelease;
        private final AtomicBoolean mClosed = new AtomicBoolean();
        private Download(Path path, Runnable release) { mPath = path; mRelease = release; }
        public Path path() { return mPath; }
        @Override public void close() throws IOException
        {
            if(mClosed.compareAndSet(false, true))
            {
                try { Files.deleteIfExists(mPath); }
                finally { mRelease.run(); }
            }
        }
    }
    private record Pending(long generation, long time, String kind, Map<String,Object> fields, String method,
                           String route, long requestId, long durationNanos, int responseCode, boolean failed,
                           Map<String,Object> core, long queuedNanos)
    {
        Pending(long generation, long time, String kind, Map<String,Object> fields, String method, String route,
                long requestId, long durationNanos, int responseCode, boolean failed)
        {
            this(generation, time, kind, fields, method, route, requestId, durationNanos, responseCode, failed,
                null, System.nanoTime());
        }
    }
    private record HealthSample(long time, Map<String,Object> fields) { }
    private record RetainedFile(Path path, long size, long modifiedAt, long retainedSince, Object fileKey) { }
    private static final class ProjectionBudget
    {
        private int nodes;
        private int characters;
        private ProjectionBudget(int nodeLimit, int characterLimit) { nodes = nodeLimit; characters = characterLimit; }
        private boolean truncated;
    }

    /** Limit a changing current file to its inspected size, and decode UTF-8 with bounded buffering. */
    private static final class BoundedChannelReader extends BufferedReader
    {
        BoundedChannelReader(FileChannel channel, long length)
        {
            super(new InputStreamReader(new java.io.FilterInputStream(Channels.newInputStream(channel))
            {
                private long mRemaining = length;
                @Override public int read(byte[] bytes, int offset, int count) throws IOException
                {
                    if(mRemaining <= 0) return -1;
                    int read = super.read(bytes, offset, (int)Math.min(count, mRemaining));
                    if(read > 0) mRemaining -= read;
                    return read;
                }
                @Override public int read() throws IOException
                {
                    if(mRemaining <= 0) return -1;
                    int value = super.read();
                    if(value >= 0) mRemaining--;
                    return value;
                }
            }, StandardCharsets.UTF_8));
        }
    }
}
