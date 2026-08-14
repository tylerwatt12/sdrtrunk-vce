/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.debug;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.debug.ReceiverIncidentController.IncidentCaptureResult;
import io.github.dsheirer.debug.ReceiverIncidentController.IncidentReportState;
import io.github.dsheirer.debug.ReceiverIncidentController.IncidentState;
import io.github.dsheirer.debug.ReceiverIncidentController.ReceiverIncidentStatus;
import io.github.dsheirer.debug.ReceiverIncidentController.ThreadDumpState;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded receiver flight recorder.  All file and thread-dump work is handed to one bounded low-priority worker with
 * AbortPolicy; saturation loses diagnostic work instead of ever running it on the caller.
 */
final class ReceiverIncidentRecorder implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(ReceiverIncidentRecorder.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    static final long STALE_CONTROL_MILLISECONDS = 5_000L;
    static final int SAMPLE_LIMIT = 15 * 60;
    static final int PRE_TRIGGER_SAMPLES = 60;
    static final long DEFAULT_POST_TRIGGER_MILLISECONDS = 120_000L;
    static final long MAXIMUM_INCIDENT_MILLISECONDS = 300_000L;
    static final int SAVED_INCIDENT_LIMIT = 5;
    static final int MAXIMUM_THREAD_DUMPS = 3;
    static final long THREAD_DUMP_SPACING_MILLISECONDS = 5_000L;
    static final long THREAD_DUMP_COOLDOWN_MILLISECONDS = 5 * 60_000L;
    static final long AUTOMATIC_TRIGGER_REARM_MILLISECONDS = 30_000L;
    static final int MAXIMUM_REPORT_BYTES = 1_024 * 1_024;
    static final int MAXIMUM_STORED_THREAD_DUMP_BYTES = 256 * 1_024;
    static final long NEVER_DECODED_CONTROL_GRACE_MILLISECONDS = 10_000L;
    private static final int DIAGNOSTIC_QUEUE_CAPACITY = 2;
    private static final int MAXIMUM_REASON_LENGTH = 160;
    private static final long CLOSE_TIMEOUT_MILLISECONDS = 2_000L;
    private static final DateTimeFormatter FILE_TIMESTAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private final Path mDirectory;
    private final LongSupplier mWallClock;
    private final LongSupplier mNanoClock;
    private final Supplier<byte[]> mThreadDumpSupplier;
    private final String mTunerPseudonymSalt = UUID.randomUUID().toString();
    private final ThreadPoolExecutor mDiagnosticWorker;
    private final ArrayDeque<ReceiverIncidentSample> mRing = new ArrayDeque<>(SAMPLE_LIMIT);
    private final ArrayList<SavedIncidentSummary> mSaved = new ArrayList<>();
    private volatile ReceiverIncidentStatus mStatus;
    private volatile byte[] mLatestIncidentJson = "{\"incident\":null}".getBytes(StandardCharsets.UTF_8);
    private volatile String mLatestIncidentText = "";
    private volatile byte[] mIncidentIndexJson = "{\"incidents\":[]}".getBytes(StandardCharsets.UTF_8);
    private ReceiverIncidentSample mPrevious;
    private IncidentDraft mActive;
    private boolean mClosed;
    private int mRawHighStreak;
    private final Map<String,Integer> mNoProgressStreaks = new LinkedHashMap<>();
    private final AutomaticConditionLatch mStaleControlTrigger = new AutomaticConditionLatch();
    private final AutomaticConditionLatch mNeverDecodedControlTrigger = new AutomaticConditionLatch();
    private int mTunerErrorStreak;
    private long mNeverDecodedControlFirstObservedAtMs;
    private long mNextThreadSeriesAllowedAtMs;
    private long mThreadSeriesBaseMs;
    private int mNextThreadDumpOrdinal;
    private boolean mThreadDumpOutstanding;
    private String mThreadSeriesReason;
    private ThreadDumpState mThreadDumpState = ThreadDumpState.NONE;
    private long mLastThreadDumpAtMs;
    private String mLastThreadDumpReason;
    private long mLastThreadDumpDurationMs;
    private String mLastRecorderError;
    private String mLastThreadDumpError;
    private IncidentReportState mLatestReportState = IncidentReportState.NONE;
    private long mLatestIncidentAtMs;
    private String mLatestIncidentReason;
    private String mLatestIncidentFileName;

    ReceiverIncidentRecorder(Path directory, LongSupplier wallClock, LongSupplier nanoClock,
                             Supplier<byte[]> threadDumpSupplier)
    {
        mDirectory = directory.toAbsolutePath().normalize();
        mWallClock = Objects.requireNonNullElse(wallClock, System::currentTimeMillis);
        mNanoClock = Objects.requireNonNullElse(nanoClock, System::nanoTime);
        mThreadDumpSupplier = Objects.requireNonNullElse(threadDumpSupplier, ReceiverThreadDumpCapture::capture);
        mDiagnosticWorker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(DIAGNOSTIC_QUEUE_CAPACITY), lowPriorityFactory(),
            new ThreadPoolExecutor.AbortPolicy());
        refreshStatusLocked();
        submitDiagnostic(this::loadExistingIndex, "load incident index");
    }

    ReceiverIncidentStatus getStatus()
    {
        return mStatus;
    }

    String getLatestIncidentText()
    {
        return mLatestIncidentText;
    }

    byte[] getLatestIncidentJson()
    {
        return mLatestIncidentJson.clone();
    }

    byte[] getIncidentIndexJson()
    {
        return mIncidentIndexJson.clone();
    }

    void acceptTelemetry(byte[] telemetryJson)
    {
        ReceiverIncidentSample sample;

        try
        {
            JsonNode root = OBJECT_MAPPER.readTree(telemetryJson);
            sample = ReceiverIncidentSample.from(root, mTunerPseudonymSalt);
        }
        catch(RuntimeException | IOException e)
        {
            recordRecorderError("Incident sampler could not read telemetry: " + e.getClass().getSimpleName());
            return;
        }

        synchronized(this)
        {
            if(mClosed)
            {
                return;
            }

            addToRing(sample);
            boolean alreadyRecording = mActive != null;

            if(alreadyRecording)
            {
                mActive.addSample(sample);
            }

            if(mPrevious != null && !Objects.equals(sample.bootId(), mPrevious.bootId()))
            {
                mStaleControlTrigger.reset();
                mNeverDecodedControlTrigger.reset();
                mNeverDecodedControlFirstObservedAtMs = 0L;
                mRawHighStreak = 0;
                mNoProgressStreaks.clear();
                mTunerErrorStreak = 0;
            }

            Trigger trigger = evaluate(sample, mPrevious);

            if(trigger != null)
            {
                if(mActive == null)
                {
                    startIncidentLocked(trigger.reason(), sample.observedAtMs(), false);
                }
                else
                {
                    mActive.addReason(trigger.reason());
                    extendIncidentLocked(sample.observedAtMs());
                }

                if(trigger.severe())
                {
                    startThreadSeriesLocked(trigger.reason(), sample.observedAtMs());
                }
            }

            if(mActive != null)
            {
                scheduleDueThreadDumpLocked(sample.observedAtMs());

                if(sample.observedAtMs() >= mActive.expectedCompletionAtMs)
                {
                    finalizeIncidentLocked(sample.observedAtMs());
                }
            }

            mPrevious = sample;
            refreshStatusLocked();
        }
    }

    synchronized IncidentCaptureResult captureIncident(String reason, boolean includeThreadDump)
    {
        if(mClosed)
        {
            return IncidentCaptureResult.REJECTED_CLOSED;
        }

        long now = safeWallClock();
        String safeReason = normalizeReason(reason, "Manual tester capture");
        IncidentCaptureResult result;

        if(includeThreadDump && !canStartThreadSeriesLocked(now))
        {
            mLastRecorderError = "Thread-dump request was not admitted because diagnostics are busy or cooling down";
            refreshStatusLocked();
            return IncidentCaptureResult.REJECTED_BUSY;
        }

        if(mActive == null)
        {
            startIncidentLocked(safeReason, now, true);
            result = IncidentCaptureResult.STARTED;
        }
        else
        {
            mActive.addReason(safeReason);
            extendIncidentLocked(now);
            result = IncidentCaptureResult.COALESCED;
        }

        if(includeThreadDump)
        {
            if(!startThreadSeriesLocked(safeReason, now) || !scheduleDueThreadDumpLocked(now))
            {
                mLastRecorderError = "Thread-dump request could not enter the bounded diagnostics worker";
                refreshStatusLocked();
                return IncidentCaptureResult.REJECTED_BUSY;
            }
        }

        refreshStatusLocked();
        return result;
    }

    private Trigger evaluate(ReceiverIncidentSample current, ReceiverIncidentSample previous)
    {
        List<String> reasons = new ArrayList<>();
        boolean severe = false;
        CounterChange change = counterChange(current, previous);
        boolean reliableTelemetry = "ok".equalsIgnoreCase(current.telemetryState());

        if(change.rawDroppedBuffers > 0L)
        {
            reasons.add("New raw IQ loss (" + change.rawDroppedBuffers + " buffers, " +
                change.rawDroppedMilliseconds + " ms)");
        }

        if(change.downstreamDropped > 0L)
        {
            reasons.add("New downstream queue overflow loss (" + change.downstreamDropped + ")");
        }

        mRawHighStreak = current.rawAboveThreshold() ? mRawHighStreak + 1 : 0;

        if(mRawHighStreak >= 3)
        {
            reasons.add("Raw IQ queue remained at least 75% full for three samples");
            severe = true;
        }

        String stalledTuner = stalledTuner(current, previous);

        if(stalledTuner != null)
        {
            reasons.add("Raw IQ continued arriving while tuner " + stalledTuner + " made no processing progress");
            severe = true;
        }

        boolean staleControlPresent = current.staleControlChannels() > 0;
        boolean staleControlSevere = change.anyRawIngress;

        if(mStaleControlTrigger.admit(staleControlPresent, staleControlPresent, staleControlSevere,
            current.observedAtMs(), reliableTelemetry))
        {
            reasons.add(current.staleControlChannels() + " active control channel(s) had no valid decode for at least " +
                STALE_CONTROL_MILLISECONDS / 1_000L + " seconds");
            severe |= staleControlSevere;
        }

        boolean neverDecodedPresent = current.activeNeverDecodedControlChannels() > 0;
        boolean neverDecodedEligible = false;

        if(reliableTelemetry && neverDecodedPresent)
        {
            if(mNeverDecodedControlFirstObservedAtMs == 0L)
            {
                mNeverDecodedControlFirstObservedAtMs = current.observedAtMs();
            }

            neverDecodedEligible = current.observedAtMs() - mNeverDecodedControlFirstObservedAtMs >=
                NEVER_DECODED_CONTROL_GRACE_MILLISECONDS;
        }
        else if(reliableTelemetry)
        {
            mNeverDecodedControlFirstObservedAtMs = 0L;
        }

        boolean neverDecodedSevere = change.anyRawIngress;

        if(mNeverDecodedControlTrigger.admit(neverDecodedPresent, neverDecodedEligible, neverDecodedSevere,
            current.observedAtMs(), reliableTelemetry))
        {
            reasons.add(current.activeNeverDecodedControlChannels() +
                " active control channel(s) produced no valid decode after the startup grace period");
            severe |= neverDecodedSevere;
        }

        mTunerErrorStreak = current.hasTunerError() ? mTunerErrorStreak + 1 : 0;

        if(mTunerErrorStreak > 0)
        {
            reasons.add("A tuner entered an error state");
            severe |= mTunerErrorStreak >= 3;
        }

        if(current.deadlockedThreadCount() > 0)
        {
            reasons.add("The JVM reported " + current.deadlockedThreadCount() + " deadlocked thread(s)");
            severe = true;
        }

        return reasons.isEmpty() ? null : new Trigger(String.join("; ", reasons), severe);
    }

    private CounterChange counterChange(ReceiverIncidentSample current, ReceiverIncidentSample previous)
    {
        if(previous == null || !Objects.equals(current.bootId(), previous.bootId()))
        {
            return CounterChange.NONE;
        }

        Map<String,ReceiverIncidentSample.TunerSample> prior = new LinkedHashMap<>();

        for(ReceiverIncidentSample.TunerSample tuner: previous.tuners())
        {
            prior.put(tuner.id(), tuner);
        }

        long rawDropped = 0L;
        long rawDroppedMs = 0L;
        long downstream = 0L;
        boolean anyRawIngress = false;

        for(ReceiverIncidentSample.TunerSample tuner: current.tuners())
        {
            ReceiverIncidentSample.TunerSample before = prior.get(tuner.id());

            if(before != null)
            {
                rawDropped += delta(tuner.rawDroppedBuffers(), before.rawDroppedBuffers());
                rawDroppedMs += delta(tuner.rawDroppedMs(), before.rawDroppedMs());
                downstream += delta(tuner.downstreamDropped(), before.downstreamDropped());
                anyRawIngress |= delta(tuner.rawReceivedBuffers(), before.rawReceivedBuffers()) > 0L;
            }
        }

        return new CounterChange(rawDropped, rawDroppedMs, downstream, anyRawIngress);
    }

    /** Evaluates progress independently so a healthy tuner cannot mask a stalled tuner. */
    private String stalledTuner(ReceiverIncidentSample current, ReceiverIncidentSample previous)
    {
        if(previous == null || !Objects.equals(current.bootId(), previous.bootId()))
        {
            mNoProgressStreaks.clear();
            return null;
        }

        Map<String,ReceiverIncidentSample.TunerSample> prior = new LinkedHashMap<>();
        previous.tuners().forEach(tuner -> prior.put(tuner.id(), tuner));
        LinkedHashSet<String> currentIds = new LinkedHashSet<>();
        String stalled = null;

        for(ReceiverIncidentSample.TunerSample tuner: current.tuners())
        {
            currentIds.add(tuner.id());
            ReceiverIncidentSample.TunerSample before = prior.get(tuner.id());
            boolean noProgress = before != null && tuner.rawWaitingMs() > 0L &&
                delta(tuner.rawReceivedBuffers(), before.rawReceivedBuffers()) > 0L &&
                delta(tuner.rawProcessedBuffers(), before.rawProcessedBuffers()) == 0L;
            int streak = noProgress ? mNoProgressStreaks.getOrDefault(tuner.id(), 0) + 1 : 0;

            if(streak > 0)
            {
                mNoProgressStreaks.put(tuner.id(), streak);
            }
            else
            {
                mNoProgressStreaks.remove(tuner.id());
            }

            if(streak >= 3 && stalled == null)
            {
                stalled = tuner.id() != null ? tuner.id() : "unknown";
            }
        }

        mNoProgressStreaks.keySet().retainAll(currentIds);
        return stalled;
    }

    private static long delta(long current, long previous)
    {
        return current >= previous ? current - previous : 0L;
    }

    private void addToRing(ReceiverIncidentSample sample)
    {
        if(mRing.size() == SAMPLE_LIMIT)
        {
            mRing.removeFirst();
        }

        mRing.addLast(sample);
    }

    private void startIncidentLocked(String reason, long triggerAtMs, boolean manual)
    {
        List<ReceiverIncidentSample> preTrigger = new ArrayList<>(Math.min(PRE_TRIGGER_SAMPLES, mRing.size()));
        int skip = Math.max(0, mRing.size() - PRE_TRIGGER_SAMPLES);
        int index = 0;

        for(ReceiverIncidentSample sample: mRing)
        {
            if(index++ >= skip)
            {
                preTrigger.add(sample);
            }
        }

        long startAt = !preTrigger.isEmpty() ? preTrigger.getFirst().observedAtMs() : triggerAtMs;
        mActive = new IncidentDraft(UUID.randomUUID().toString(), startAt, triggerAtMs,
            Math.min(triggerAtMs + DEFAULT_POST_TRIGGER_MILLISECONDS, startAt + MAXIMUM_INCIDENT_MILLISECONDS),
            startAt + MAXIMUM_INCIDENT_MILLISECONDS, manual, preTrigger);
        mActive.addReason(reason);
    }

    private void extendIncidentLocked(long now)
    {
        if(mActive != null)
        {
            mActive.expectedCompletionAtMs = Math.min(mActive.maximumCompletionAtMs,
                Math.max(mActive.expectedCompletionAtMs, now + DEFAULT_POST_TRIGGER_MILLISECONDS));
        }
    }

    private boolean canStartThreadSeriesLocked(long now)
    {
        return mNextThreadDumpOrdinal == 0 && !mThreadDumpOutstanding &&
            now >= mNextThreadSeriesAllowedAtMs && !mDiagnosticWorker.isShutdown() &&
            mDiagnosticWorker.getQueue().remainingCapacity() > 0;
    }

    private boolean startThreadSeriesLocked(String reason, long now)
    {
        if(mActive == null || mNextThreadDumpOrdinal > 0 || mThreadDumpOutstanding)
        {
            return false;
        }

        if(now < mNextThreadSeriesAllowedAtMs)
        {
            mLastRecorderError = "Thread dump cooldown is active until " +
                Instant.ofEpochMilli(mNextThreadSeriesAllowedAtMs);
            return false;
        }

        mThreadSeriesBaseMs = now;
        mNextThreadDumpOrdinal = 1;
        mThreadSeriesReason = normalizeReason(reason, "Sustained receiver incident");
        mNextThreadSeriesAllowedAtMs = now + THREAD_DUMP_COOLDOWN_MILLISECONDS;
        mThreadDumpState = ThreadDumpState.SCHEDULED;
        return true;
    }

    private boolean scheduleDueThreadDumpLocked(long now)
    {
        if(mActive == null || mThreadDumpOutstanding || mNextThreadDumpOrdinal < 1 ||
            mNextThreadDumpOrdinal > MAXIMUM_THREAD_DUMPS)
        {
            return false;
        }

        long due = mThreadSeriesBaseMs + (mNextThreadDumpOrdinal - 1L) * THREAD_DUMP_SPACING_MILLISECONDS;

        if(now < due)
        {
            return false;
        }

        int ordinal = mNextThreadDumpOrdinal;
        ThreadDumpRequest request = new ThreadDumpRequest(mActive, ordinal, mThreadSeriesReason,
            latestSample(), now);

        try
        {
            mDiagnosticWorker.execute(() -> performThreadDump(request));
            mThreadDumpOutstanding = true;
            mNextThreadDumpOrdinal++;
            mThreadDumpState = ThreadDumpState.SCHEDULED;
            return true;
        }
        catch(RejectedExecutionException e)
        {
            mThreadDumpState = ThreadDumpState.FAILED;
            mLastThreadDumpError = "Thread dump was skipped because the bounded diagnostics worker was busy";
            return false;
        }
    }

    private void performThreadDump(ThreadDumpRequest request)
    {
        synchronized(this)
        {
            mThreadDumpState = ThreadDumpState.CAPTURING;
            refreshStatusLocked();
        }

        long startedAt = safeWallClock();
        long startedNanos = safeNanoClock();
        byte[] captured;
        String error = null;

        try
        {
            captured = mThreadDumpSupplier.get();

            if(captured == null || captured.length > ReceiverThreadDumpCapture.MAXIMUM_BYTES)
            {
                error = "Thread dump exceeded its 512 KiB safety limit";
                captured = OBJECT_MAPPER.createObjectNode().put("error", error).toString()
                    .getBytes(StandardCharsets.UTF_8);
            }
            else
            {
                JsonNode result = OBJECT_MAPPER.readTree(captured);

                if(result == null || !result.isObject())
                {
                    error = "Thread dump returned invalid JSON";
                }
                else if(result.path("error").isTextual())
                {
                    error = result.path("error").asText();
                }

                captured = ReceiverThreadDumpCapture.boundForStorage(captured,
                    MAXIMUM_STORED_THREAD_DUMP_BYTES);
                JsonNode stored = OBJECT_MAPPER.readTree(captured);

                if(error == null && stored != null && stored.path("error").isTextual())
                {
                    error = stored.path("error").asText();
                }
            }
        }
        catch(RuntimeException | IOException e)
        {
            error = "Thread dump failed: " + e.getClass().getSimpleName();
            captured = OBJECT_MAPPER.createObjectNode().put("error", error).toString()
                .getBytes(StandardCharsets.UTF_8);
        }

        long completedAt = safeWallClock();
        long duration = Math.max(0L, safeNanoClock() - startedNanos) / 1_000_000L;

        synchronized(this)
        {
            ReceiverIncidentSample post = latestSample();

            //The queued request owns its draft.  Finalization can remove the draft from mActive while the capture is
            //running, but persistence is ordered behind this task on the same worker and sees the attached evidence.
            request.incident.threadDumps.add(new ThreadDumpEvidence(request.ordinal, request.reason, startedAt,
                completedAt, duration, request.preSample, post, captured, error));

            mThreadDumpOutstanding = false;
            mLastThreadDumpAtMs = completedAt;
            mLastThreadDumpReason = request.reason;
            mLastThreadDumpDurationMs = duration;
            mThreadDumpState = error == null ? ThreadDumpState.CAPTURED : ThreadDumpState.FAILED;
            mLastThreadDumpError = error;
            refreshStatusLocked();
        }
    }

    private ReceiverIncidentSample latestSample()
    {
        return mRing.peekLast();
    }

    private void finalizeIncidentLocked(long endedAtMs)
    {
        IncidentDraft completed = mActive;
        mActive = null;
        mNextThreadDumpOrdinal = 0;
        mThreadSeriesBaseMs = 0L;
        mThreadSeriesReason = null;

        if(completed != null)
        {
            completed.endedAtMs = endedAtMs;
            mLatestIncidentAtMs = completed.triggerAtMs;
            mLatestIncidentReason = completed.reasons.isEmpty() ? "Receiver incident" :
                completed.reasons.iterator().next();
            mLatestIncidentFileName = null;
            mLatestReportState = IncidentReportState.CAPTURED_PENDING_SAVE;

            if(!submitDiagnostic(() -> persist(completed), "persist receiver incident"))
            {
                mLatestReportState = IncidentReportState.SAVE_FAILED;
            }
        }
    }

    private void persist(IncidentDraft incident)
    {
        try
        {
            Files.createDirectories(mDirectory);
            byte[] json = serializeBounded(incident);
            String fileName = "receiver-incident-" + FILE_TIMESTAMP.format(Instant.ofEpochMilli(incident.triggerAtMs)) +
                "-" + incident.id.substring(0, 8) + ".json";
            Path target = mDirectory.resolve(fileName);
            Path temporary = mDirectory.resolve(fileName + ".tmp");
            try
            {
                Files.write(temporary, json, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);

                try
                {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                catch(IOException e)
                {
                    Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            finally
            {
                Files.deleteIfExists(temporary);
            }

            String reason = incident.reasons.isEmpty() ? "Receiver incident" : incident.reasons.iterator().next();
            SavedIncidentSummary summary = new SavedIncidentSummary(fileName, incident.triggerAtMs, incident.endedAtMs,
                reason, incident.threadDumps.size(), json.length);
            String text = renderText(incident, fileName);

            synchronized(this)
            {
                mSaved.removeIf(saved -> saved.fileName.equals(fileName));
                mSaved.add(summary);
                mSaved.sort(Comparator.comparingLong(SavedIncidentSummary::triggerAtMs));

                while(mSaved.size() > SAVED_INCIDENT_LIMIT)
                {
                    SavedIncidentSummary removed = mSaved.removeFirst();
                    Files.deleteIfExists(mDirectory.resolve(removed.fileName));
                }

                mLatestIncidentJson = json;
                mLatestIncidentText = text;
                mLatestIncidentAtMs = incident.triggerAtMs;
                mLatestIncidentReason = reason;
                mLatestIncidentFileName = fileName;
                mLatestReportState = IncidentReportState.SAVED;
                rebuildIndexLocked();
                mLastRecorderError = null;
                refreshStatusLocked();
            }
        }
        catch(IOException | RuntimeException e)
        {
            synchronized(this)
            {
                mLatestReportState = IncidentReportState.SAVE_FAILED;
            }
            recordRecorderError("Unable to save receiver incident: " + e.getClass().getSimpleName());
            mLog.warn("Unable to save receiver incident", e);
        }
    }

    /** Serializes with progressively coarser timeline projection, then omits stack payloads if necessary. */
    private static byte[] serializeBounded(IncidentDraft incident) throws IOException
    {
        for(boolean includeDumpPayload: new boolean[]{true, false})
        {
            for(int stride: new int[]{1, 2, 4, 8, 16, 32, 64})
            {
                byte[] candidate = OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(incident.toMap(stride, includeDumpPayload));

                if(candidate.length <= MAXIMUM_REPORT_BYTES)
                {
                    return candidate;
                }
            }
        }

        Map<String,Object> minimal = incident.toMap(Integer.MAX_VALUE, false);
        minimal.put("report_truncated", true);
        byte[] candidate = OBJECT_MAPPER.writeValueAsBytes(minimal);

        if(candidate.length > MAXIMUM_REPORT_BYTES)
        {
            throw new IOException("Bounded incident report could not fit the report byte limit");
        }

        return candidate;
    }

    private void loadExistingIndex()
    {
        try
        {
            Files.createDirectories(mDirectory);
            List<Path> files;

            try(var stream = Files.list(mDirectory))
            {
                List<Path> entries = stream.toList();

                for(Path entry: entries)
                {
                    String name = entry.getFileName().toString();

                    if(name.startsWith("receiver-incident-") && name.endsWith(".tmp"))
                    {
                        Files.deleteIfExists(entry);
                    }
                }

                files = new ArrayList<>(entries.stream().filter(path -> {
                    String name = path.getFileName().toString();
                    return name.startsWith("receiver-incident-") && name.endsWith(".json");
                }).sorted(Comparator.comparingLong(this::lastModified)).toList());
            }

            while(files.size() > SAVED_INCIDENT_LIMIT)
            {
                Files.deleteIfExists(files.removeFirst());
            }

            List<LoadedIncident> loaded = new ArrayList<>();
            String loadError = null;

            for(Path file: files)
            {
                try
                {
                    byte[] json = readBoundedReport(file);
                    JsonNode root = OBJECT_MAPPER.readTree(json);

                    if(root == null || !root.isObject() || !root.path("incident").isObject())
                    {
                        throw new IOException("Incident report root is malformed");
                    }

                    JsonNode details = root.path("incident");
                    SavedIncidentSummary summary = new SavedIncidentSummary(file.getFileName().toString(),
                        details.path("trigger_at_ms").asLong(lastModified(file)),
                        details.path("ended_at_ms").asLong(lastModified(file)),
                        details.path("reasons").path(0).asText("Receiver incident"),
                        root.path("thread_dumps").size(), json.length);
                    loaded.add(new LoadedIncident(summary, json));
                }
                catch(IOException | RuntimeException e)
                {
                    loadError = "Removed malformed or oversized receiver incident " + file.getFileName();
                    Files.deleteIfExists(file);
                }
            }

            synchronized(this)
            {
                for(LoadedIncident incident: loaded)
                {
                    mSaved.add(incident.summary);
                    mLatestIncidentJson = incident.json;
                    mLatestIncidentText = renderLoadedText(incident.summary);
                    mLatestIncidentAtMs = incident.summary.triggerAtMs;
                    mLatestIncidentReason = incident.summary.reason;
                    mLatestIncidentFileName = incident.summary.fileName;
                    mLatestReportState = IncidentReportState.SAVED;
                }

                if(loadError != null)
                {
                    mLastRecorderError = loadError;
                }

                rebuildIndexLocked();
                refreshStatusLocked();
            }
        }
        catch(IOException e)
        {
            recordRecorderError("Unable to initialize receiver incident directory: " + e.getClass().getSimpleName());
        }
    }

    private static byte[] readBoundedReport(Path file) throws IOException
    {
        long size = Files.size(file);

        if(size < 1L || size > MAXIMUM_REPORT_BYTES)
        {
            throw new IOException("Incident report is empty or exceeds its byte limit");
        }

        try(InputStream input = Files.newInputStream(file);
            ByteArrayOutputStream output = new ByteArrayOutputStream((int)Math.min(size, 64 * 1_024L)))
        {
            byte[] buffer = new byte[8_192];
            int total = 0;
            int read;

            while((read = input.read(buffer)) >= 0)
            {
                total += read;

                if(total > MAXIMUM_REPORT_BYTES)
                {
                    throw new IOException("Incident report grew beyond its byte limit while reading");
                }

                output.write(buffer, 0, read);
            }

            return output.toByteArray();
        }
    }

    private long lastModified(Path path)
    {
        try
        {
            return Files.getLastModifiedTime(path).toMillis();
        }
        catch(IOException e)
        {
            return 0L;
        }
    }

    private void rebuildIndexLocked()
    {
        Map<String,Object> index = new LinkedHashMap<>();
        index.put("schema_version", 1);
        index.put("saved_limit", SAVED_INCIDENT_LIMIT);
        index.put("incidents", mSaved.stream().map(SavedIncidentSummary::toMap).toList());

        try
        {
            mIncidentIndexJson = OBJECT_MAPPER.writeValueAsBytes(index);
        }
        catch(IOException e)
        {
            mIncidentIndexJson = "{\"incidents\":[],\"error\":\"serialization failed\"}"
                .getBytes(StandardCharsets.UTF_8);
        }
    }

    private static String renderText(IncidentDraft incident, String fileName)
    {
        StringBuilder text = new StringBuilder(1_024);
        text.append("RECEIVER INCIDENT\n")
            .append("File: ").append(fileName).append('\n')
            .append("Triggered: ").append(Instant.ofEpochMilli(incident.triggerAtMs)).append('\n')
            .append("Ended: ").append(Instant.ofEpochMilli(incident.endedAtMs)).append('\n')
            .append("Reasons: ").append(String.join("; ", incident.reasons)).append('\n')
            .append("Timeline samples: ").append(incident.samples.size()).append('\n')
            .append("Thread dumps: ").append(incident.threadDumps.size()).append('\n');

        for(ThreadDumpEvidence dump: incident.threadDumps)
        {
            text.append("  Dump ").append(dump.ordinal).append(" captured ")
                .append(Instant.ofEpochMilli(dump.completedAtMs)).append(" in ")
                .append(dump.durationMs).append(" ms");

            if(dump.error != null)
            {
                text.append(" (failed: ").append(dump.error).append(')');
            }

            text.append('\n');
        }

        text.append("The JSON file contains the second-by-second queue timeline and bounded thread stacks.\n");
        return text.toString();
    }

    private static String renderLoadedText(SavedIncidentSummary summary)
    {
        return "RECEIVER INCIDENT\nFile: " + summary.fileName + "\nTriggered: " +
            Instant.ofEpochMilli(summary.triggerAtMs) + "\nReason: " + summary.reason +
            "\nThread dumps: " + summary.threadDumpCount + "\n";
    }

    private boolean submitDiagnostic(Runnable task, String operation)
    {
        try
        {
            mDiagnosticWorker.execute(task);
            return true;
        }
        catch(RejectedExecutionException e)
        {
            recordRecorderError(operation + " skipped because the bounded diagnostics worker was busy");
            return false;
        }
    }

    private synchronized void recordRecorderError(String error)
    {
        mLastRecorderError = error;
        refreshStatusLocked();
    }

    private void refreshStatusLocked()
    {
        IncidentState state = mClosed ? IncidentState.CLOSED : mActive != null ?
            IncidentState.RECORDING : IncidentState.ARMED;
        String summary;

        if(mClosed)
        {
            summary = "Receiver incident recorder is closed";
        }
        else if(mActive != null)
        {
            summary = mThreadDumpState == ThreadDumpState.CAPTURING ?
                "Capturing a diagnostic thread dump" : "Recording receiver incident evidence";
        }
        else if(mLastThreadDumpAtMs > 0L)
        {
            summary = "Armed; last thread dump completed " + Instant.ofEpochMilli(mLastThreadDumpAtMs);
        }
        else
        {
            summary = "Armed; retaining the latest 15 minutes of receiver metrics";
        }

        String visibleError = mThreadDumpState == ThreadDumpState.FAILED && mLastThreadDumpError != null ?
            mLastThreadDumpError : mLastRecorderError;
        mStatus = new ReceiverIncidentStatus(state, summary, mRing.size(), SAMPLE_LIMIT,
            mActive != null ? String.join("; ", mActive.reasons) : null,
            mActive != null ? mActive.startedAtMs : 0L,
            mActive != null ? mActive.expectedCompletionAtMs : 0L,
            mActive != null ? mActive.threadDumps.size() : 0, mThreadDumpState, mLastThreadDumpAtMs,
            mLastThreadDumpReason, mLastThreadDumpDurationMs, mSaved.size(), mLatestIncidentAtMs,
            mLatestIncidentReason, mLatestIncidentFileName, visibleError, mLatestReportState);
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

    private long safeNanoClock()
    {
        try
        {
            return mNanoClock.getAsLong();
        }
        catch(RuntimeException e)
        {
            return System.nanoTime();
        }
    }

    private static String normalizeReason(String reason, String fallback)
    {
        String normalized = reason != null ? reason.strip().replaceAll("\\s+", " ") : "";

        if(normalized.isEmpty())
        {
            normalized = fallback;
        }

        return normalized.length() <= MAXIMUM_REASON_LENGTH ? normalized :
            normalized.substring(0, MAXIMUM_REASON_LENGTH);
    }

    private static ThreadFactory lowPriorityFactory()
    {
        return runnable -> {
            Thread thread = new Thread(runnable, "receiver incident diagnostics");
            thread.setDaemon(true);
            thread.setPriority(Thread.MIN_PRIORITY);
            return thread;
        };
    }

    @Override
    public void close()
    {
        synchronized(this)
        {
            if(mClosed)
            {
                return;
            }

            if(mActive != null)
            {
                finalizeIncidentLocked(safeWallClock());
            }

            mClosed = true;
            refreshStatusLocked();
        }

        mDiagnosticWorker.shutdown();

        try
        {
            if(!mDiagnosticWorker.awaitTermination(CLOSE_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS))
            {
                mDiagnosticWorker.shutdownNow();
            }
        }
        catch(InterruptedException e)
        {
            Thread.currentThread().interrupt();
            mDiagnosticWorker.shutdownNow();
        }
    }

    private record Trigger(String reason, boolean severe)
    {
    }

    /** Latches one level-based control-channel condition until reliable telemetry proves sustained recovery. */
    private static final class AutomaticConditionLatch
    {
        private boolean mLatched;
        private boolean mSevere;
        private long mAbsentSinceMs;

        private boolean admit(boolean present, boolean eligible, boolean severe, long now,
                              boolean reliableTelemetry)
        {
            if(!reliableTelemetry)
            {
                return false;
            }

            if(!present)
            {
                if(mLatched)
                {
                    if(mAbsentSinceMs == 0L)
                    {
                        mAbsentSinceMs = now;
                    }
                    else if(now - mAbsentSinceMs >= AUTOMATIC_TRIGGER_REARM_MILLISECONDS)
                    {
                        reset();
                    }
                }

                return false;
            }

            mAbsentSinceMs = 0L;

            if(eligible && (!mLatched || severe && !mSevere))
            {
                mLatched = true;
                mSevere |= severe;
                return true;
            }

            return false;
        }

        private void reset()
        {
            mLatched = false;
            mSevere = false;
            mAbsentSinceMs = 0L;
        }
    }

    private record CounterChange(long rawDroppedBuffers, long rawDroppedMilliseconds, long downstreamDropped,
                                 boolean anyRawIngress)
    {
        private static final CounterChange NONE = new CounterChange(0L, 0L, 0L, false);
    }

    private record ThreadDumpRequest(IncidentDraft incident, int ordinal, String reason,
                                     ReceiverIncidentSample preSample, long requestedAtMs)
    {
    }

    private record ThreadDumpEvidence(int ordinal, String reason, long startedAtMs, long completedAtMs,
                                      long durationMs, ReceiverIncidentSample preSample,
                                      ReceiverIncidentSample postSample, byte[] json, String error)
    {
        Map<String,Object> toMap(boolean includeDumpPayload)
        {
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("ordinal", ordinal);
            value.put("reason", reason);
            value.put("started_at_ms", startedAtMs);
            value.put("completed_at_ms", completedAtMs);
            value.put("duration_ms", durationMs);
            value.put("error", error);
            value.put("metrics_before", preSample != null ? preSample.toMap() : null);
            value.put("metrics_after", postSample != null ? postSample.toMap() : null);

            if(!includeDumpPayload)
            {
                value.put("dump_omitted_for_report_limit", true);
            }
            else try
            {
                value.put("dump", OBJECT_MAPPER.readTree(json));
            }
            catch(IOException e)
            {
                value.put("dump", Map.of("error", "Stored thread dump JSON was invalid"));
            }

            return value;
        }
    }

    private static final class IncidentDraft
    {
        private final String id;
        private final long startedAtMs;
        private final long triggerAtMs;
        private long expectedCompletionAtMs;
        private final long maximumCompletionAtMs;
        private final boolean manual;
        private long endedAtMs;
        private final LinkedHashSet<String> reasons = new LinkedHashSet<>();
        private final ArrayList<ReceiverIncidentSample> samples;
        private final ArrayList<ThreadDumpEvidence> threadDumps = new ArrayList<>(MAXIMUM_THREAD_DUMPS);

        private IncidentDraft(String id, long startedAtMs, long triggerAtMs, long expectedCompletionAtMs,
                              long maximumCompletionAtMs, boolean manual, List<ReceiverIncidentSample> samples)
        {
            this.id = id;
            this.startedAtMs = startedAtMs;
            this.triggerAtMs = triggerAtMs;
            this.expectedCompletionAtMs = expectedCompletionAtMs;
            this.maximumCompletionAtMs = maximumCompletionAtMs;
            this.manual = manual;
            this.samples = new ArrayList<>(samples);
        }

        private void addReason(String reason)
        {
            reasons.add(normalizeReason(reason, "Receiver incident"));
        }

        private void addSample(ReceiverIncidentSample sample)
        {
            if(samples.isEmpty() || samples.getLast().sequence() != sample.sequence())
            {
                samples.add(sample);
            }
        }

        private Map<String,Object> toMap(int timelineStride, boolean includeDumpPayload)
        {
            Map<String,Object> incident = new LinkedHashMap<>();
            incident.put("id", id);
            incident.put("started_at_ms", startedAtMs);
            incident.put("trigger_at_ms", triggerAtMs);
            incident.put("ended_at_ms", endedAtMs);
            incident.put("manual", manual);
            incident.put("reasons", List.copyOf(reasons));
            Map<String,Object> policy = new LinkedHashMap<>();
            policy.put("ring_sample_limit", SAMPLE_LIMIT);
            policy.put("pre_trigger_samples", PRE_TRIGGER_SAMPLES);
            policy.put("post_trigger_ms", DEFAULT_POST_TRIGGER_MILLISECONDS);
            policy.put("maximum_incident_ms", MAXIMUM_INCIDENT_MILLISECONDS);
            policy.put("thread_dump_limit", MAXIMUM_THREAD_DUMPS);
            policy.put("thread_dump_spacing_ms", THREAD_DUMP_SPACING_MILLISECONDS);
            policy.put("thread_stack_depth_limit", ReceiverThreadDumpCapture.MAXIMUM_STACK_DEPTH);
            policy.put("thread_dump_byte_limit", ReceiverThreadDumpCapture.MAXIMUM_BYTES);
            policy.put("stored_thread_dump_byte_limit", MAXIMUM_STORED_THREAD_DUMP_BYTES);
            policy.put("report_byte_limit", MAXIMUM_REPORT_BYTES);
            List<ReceiverIncidentSample> selectedSamples = new ArrayList<>();
            int stride = Math.max(1, timelineStride);

            for(int x = 0; x < samples.size(); x += stride)
            {
                selectedSamples.add(samples.get(x));
            }

            if(!samples.isEmpty() && (selectedSamples.isEmpty() ||
                selectedSamples.getLast().sequence() != samples.getLast().sequence()))
            {
                selectedSamples.add(samples.getLast());
            }

            Map<String,Object> root = new LinkedHashMap<>();
            root.put("schema_version", 1);
            root.put("incident", incident);
            root.put("capture_policy", policy);
            root.put("timeline_original_sample_count", samples.size());
            root.put("timeline_stride", stride);
            root.put("timeline_truncated", selectedSamples.size() < samples.size());
            root.put("timeline", selectedSamples.stream().map(ReceiverIncidentSample::toMap).toList());
            root.put("thread_dump_payloads_omitted", !includeDumpPayload);
            root.put("thread_dumps", threadDumps.stream().map(dump -> dump.toMap(includeDumpPayload)).toList());
            return root;
        }
    }

    private record SavedIncidentSummary(String fileName, long triggerAtMs, long endedAtMs, String reason,
                                        int threadDumpCount, long bytes)
    {
        Map<String,Object> toMap()
        {
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("file_name", fileName);
            value.put("trigger_at_ms", triggerAtMs);
            value.put("ended_at_ms", endedAtMs);
            value.put("reason", reason);
            value.put("thread_dump_count", threadDumpCount);
            value.put("bytes", bytes);
            return value;
        }
    }

    private record LoadedIncident(SavedIncidentSummary summary, byte[] json)
    {
    }
}
