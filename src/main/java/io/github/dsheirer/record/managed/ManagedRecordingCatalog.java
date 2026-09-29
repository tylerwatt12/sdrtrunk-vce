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
package io.github.dsheirer.record.managed;

import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.protocol.Protocol;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Owner of the separate Managed Recordings database. The recording worker hands off only primitive metadata, while a
 * bounded daemon queue performs every SQLite insert. Browsing, maintenance, and audio serving never touch decoder
 * callbacks. Existing catalogs are validation-only at startup; this class never imports loose audio files.
 */
public final class ManagedRecordingCatalog implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(ManagedRecordingCatalog.class);
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 50;
    public static final int MAX_SUGGESTIONS = 25;
    public static final int CALL_CONVENTIONAL = 0;
    public static final int CALL_GROUP = 1;
    public static final int CALL_PATCH = 2;
    public static final int CALL_DIRECT = 3;
    public static final int VOICE_UNKNOWN = 0;
    public static final int VOICE_CLEAR = 1;
    public static final int VOICE_ENCRYPTED = 2;
    private static final int MAX_PENDING_WRITES = 512;
    private static final int MAX_PENDING_TRANSCRIPT_WRITES = 32;
    private final Path mRoot;
    private final ManagedRecordingStore mStore;
    private final ArrayBlockingQueue<PendingRecording> mPending = new ArrayBlockingQueue<>(MAX_PENDING_WRITES);
    private final ArrayBlockingQueue<PendingTranscription> mPendingTranscription =
        new ArrayBlockingQueue<>(MAX_PENDING_TRANSCRIPT_WRITES);
    private final Thread mWriter;
    private final AtomicLong mDropped = new AtomicLong();
    private final AtomicLong mWriteFailures = new AtomicLong();
    private final Object mQueueHandoff = new Object();
    private final Object mTranscriptionHandoff = new Object();
    private final ReentrantLock mMaintenanceLock = new ReentrantLock();
    private volatile boolean mAccepting = true;

    public ManagedRecordingCatalog(Path databaseFile, Path recordingsRoot) throws SQLException, IOException
    {
        Objects.requireNonNull(databaseFile, "Database file is required");
        mRoot = Objects.requireNonNull(recordingsRoot, "Managed recordings root is required")
            .toAbsolutePath().normalize();
        Files.createDirectories(mRoot);
        mStore = new ManagedRecordingStore(databaseFile.toAbsolutePath().normalize(), mRoot);
        mWriter = new Thread(this::writeLoop, "managed-recordings-catalog");
        mWriter.setDaemon(true);
        mWriter.start();
    }

    /**
     * Offers a completed file to the catalog without filesystem or SQLite access. On false, the caller still owns the
     * file and must remove it. On true, the catalog owns the file and removes it if indexing fails. The optional
     * observer runs only after a successful SQLite commit on the catalog thread.
     */
    public boolean submit(Path savedFile, CompletedAudioCall call, long sizeBytes, Runnable onIndexed)
    {
        if(savedFile == null || call == null || sizeBytes <= 0L)
        {
            mDropped.incrementAndGet();
            return false;
        }

        String relativePath;
        try
        {
            Path relative = mRoot.relativize(savedFile.toAbsolutePath().normalize());
            if(relative.isAbsolute() || relative.getNameCount() == 0 || relative.startsWith(".."))
            {
                mDropped.incrementAndGet();
                return false;
            }
            relativePath = relative.toString().replace('\\', '/');
            ManagedRecordingMetadata metadata = ManagedRecordingMetadata.from(call, relativePath, sizeBytes);
            synchronized(mQueueHandoff)
            {
                if(mAccepting && mPending.offer(new PendingRecording(metadata, onIndexed)))
                {
                    return true;
                }
            }
        }
        catch(RuntimeException exception)
        {
            mLog.warn("Unable to capture managed recording metadata", exception);
        }

        long dropped = mDropped.incrementAndGet();
        if(dropped == 1 || dropped % 100 == 0)
        {
            mLog.warn("Managed recording catalog queue rejected a file ({} dropped)", dropped);
        }
        return false;
    }

    private void writeLoop()
    {
        while(mAccepting || !mPending.isEmpty() || !mPendingTranscription.isEmpty())
        {
            PendingRecording pending;
            try
            {
                pending = mPending.poll(250, TimeUnit.MILLISECONDS);
            }
            catch(InterruptedException exception)
            {
                continue;
            }
            if(pending == null)
            {
                // A transcript result may wait behind recording inserts, but never delays accepting or indexing one.
                if(mPending.isEmpty())
                {
                    writeTranscriptResult();
                }
                continue;
            }
            ManagedRecordingMetadata metadata = pending.metadata();
            try
            {
                mStore.insert(metadata);
                if(pending.onIndexed() != null)
                {
                    try
                    {
                        pending.onIndexed().run();
                    }
                    catch(RuntimeException exception)
                    {
                        mLog.warn("Managed recording post-index observer failed", exception);
                    }
                }
            }
            catch(SQLException | RuntimeException exception)
            {
                long failures = mWriteFailures.incrementAndGet();
                if(failures == 1 || failures % 100 == 0)
                {
                    mLog.error("Unable to index managed recording ({} failures)", failures, exception);
                }
                try
                {
                    Files.deleteIfExists(mRoot.resolve(metadata.relativePath()));
                }
                catch(IOException cleanupError)
                {
                    mLog.warn("Unable to remove unindexed managed recording", cleanupError);
                }
            }
        }
    }

    private record PendingRecording(ManagedRecordingMetadata metadata, Runnable onIndexed) {}

    private record PendingTranscription(long id, String text, long storedAtMs, boolean failed,
                                        CompletableFuture<Boolean> result) {}

    private void writeTranscriptResult()
    {
        PendingTranscription pending = mPendingTranscription.poll();
        if(pending == null)
        {
            return;
        }
        try
        {
            boolean persisted = pending.failed() ? mStore.failTranscription(pending.id()) :
                mStore.storeTranscript(pending.id(), pending.text(), pending.storedAtMs());
            pending.result().complete(persisted);
        }
        catch(SQLException | RuntimeException exception)
        {
            mLog.warn("Unable to update managed recording transcription status", exception);
            pending.result().complete(false);
        }
    }

    /** The worker can await the returned acknowledgement without occupying the catalog or receiver thread. */
    public CompletableFuture<Boolean> storeTranscript(long id, String text, long storedAtMs)
    {
        return offerTranscription(new PendingTranscription(id, text, storedAtMs, false,
            new CompletableFuture<>()));
    }

    public CompletableFuture<Boolean> failTranscription(long id)
    {
        return offerTranscription(new PendingTranscription(id, null, 0L, true,
            new CompletableFuture<>()));
    }

    private CompletableFuture<Boolean> offerTranscription(PendingTranscription pending)
    {
        synchronized(mTranscriptionHandoff)
        {
            if(mAccepting && mPendingTranscription.offer(pending))
            {
                return pending.result();
            }
        }
        pending.result().complete(false);
        return pending.result();
    }

    /** Returns zero when no eligible pending call exists after the supplied catalog ID. */
    public long nextPendingTranscription(long afterId, long minimumDurationMs) throws SQLException
    {
        return mStore.nextPendingTranscription(afterId, minimumDurationMs);
    }

    public Transcript transcription(long id) throws SQLException
    {
        return mStore.transcription(id);
    }

    public TranscriptionCounts transcriptionCounts(long minimumDurationMs) throws SQLException
    {
        return mStore.transcriptionCounts(minimumDurationMs);
    }

    /** Cheap backlog signal for lower-priority workers; never reads SQLite or takes a recording lock. */
    public int pendingRecordingWrites()
    {
        return mPending.size();
    }

    /** Retries a failed indexed call. Admin request threads only; never invoke from a receiver callback. */
    public boolean retryTranscription(long id) throws SQLException
    {
        return mStore.retryTranscription(id);
    }

    public SearchPage search(SearchFilter filter) throws SQLException
    {
        return mStore.search(filter != null ? filter : SearchFilter.builder().build());
    }

    public RecordingCall find(long id) throws SQLException
    {
        return mStore.find(id);
    }

    /** Returns a validated path only for a currently indexed regular file within the managed root. */
    public Path audioPath(long id) throws SQLException, IOException
    {
        RecordingCall call = find(id);
        if(call == null)
        {
            return null;
        }
        Path file = safePath(call.relativePath());
        if(file == null || !Files.isRegularFile(file) || Files.size(file) <= 0L)
        {
            return null;
        }
        return file;
    }

    /** Deletes the indexed recording and its catalog row. This operation is reserved for the primary admin. */
    public boolean delete(long id) throws SQLException, IOException
    {
        beginMaintenance();
        try
        {
            RecordingCall call = find(id);
            if(call == null)
            {
                return false;
            }
            Path file = safePath(call.relativePath());
            if(file != null && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            {
                Files.deleteIfExists(file);
            }
            return mStore.delete(id);
        }
        finally
        {
            mMaintenanceLock.unlock();
        }
    }

    /** Walks indexed rows only, discarding invalid or missing files and correcting changed byte counts. */
    public MaintenanceResult recount() throws SQLException, IOException
    {
        beginMaintenance();
        try
        {
            return mStore.recount(false);
        }
        finally
        {
            mMaintenanceLock.unlock();
        }
    }

    /** Explicit manual recount plus SQLite index rebuild. Loose files are never imported. */
    public MaintenanceResult reindex() throws SQLException, IOException
    {
        beginMaintenance();
        try
        {
            return mStore.recount(true);
        }
        finally
        {
            mMaintenanceLock.unlock();
        }
    }

    /** Applies an age cutoff. The caller owns the age setting and schedule. */
    public MaintenanceResult pruneOlderThan(long cutoffEpochMs) throws SQLException, IOException
    {
        beginMaintenance();
        try
        {
            return mStore.pruneOlderThan(cutoffEpochMs);
        }
        finally
        {
            mMaintenanceLock.unlock();
        }
    }

    private void beginMaintenance()
    {
        if(!mMaintenanceLock.tryLock())
        {
            throw new IllegalStateException("Managed recordings maintenance is already running");
        }
    }

    public Stats stats() throws SQLException
    {
        ManagedRecordingStore.StoredStats stats = mStore.stats();
        return new Stats(stats.callCount(), stats.totalBytes(), mPending.size(), MAX_PENDING_WRITES,
            mDropped.get(), mWriteFailures.get());
    }

    public List<String> systemKeys(String query, int limit) throws SQLException
    {
        return mStore.systemKeys(query, boundedSuggestions(limit));
    }

    public List<String> channelIds(String query, int limit) throws SQLException
    {
        return mStore.channelIds(query, boundedSuggestions(limit));
    }

    public List<Site> sites(String systemKey, int limit) throws SQLException
    {
        return sites(systemKey, null, limit);
    }

    public List<Site> sites(String systemKey, String query, int limit) throws SQLException
    {
        return mStore.sites(systemKey, query, boundedSuggestions(limit));
    }

    public Path recordingsRoot()
    {
        return mRoot;
    }

    private static int boundedSuggestions(int limit)
    {
        return Math.max(1, Math.min(MAX_SUGGESTIONS, limit));
    }

    Path safePath(String relativePath) throws IOException
    {
        if(relativePath == null || relativePath.isBlank())
        {
            return null;
        }
        Path relative = Path.of(relativePath);
        if(relative.isAbsolute() || relative.startsWith(".."))
        {
            return null;
        }
        Path file = mRoot.resolve(relative).normalize();
        if(!file.startsWith(mRoot))
        {
            return null;
        }
        Path probe = Files.exists(file) ? file : file.getParent();
        while(probe != null && !Files.exists(probe))
        {
            probe = probe.getParent();
        }
        if(probe == null)
        {
            return null;
        }
        try
        {
            if(!probe.toRealPath().startsWith(mRoot.toRealPath()))
            {
                return null;
            }
        }
        catch(java.nio.file.NoSuchFileException ignored)
        {
            // The entry can be dropped normally if its file disappears during this check.
        }
        return file;
    }

    @Override
    public void close()
    {
        synchronized(mQueueHandoff)
        {
            synchronized(mTranscriptionHandoff)
            {
                mAccepting = false;
            }
        }
        // A user-started recount/reindex may still be finishing as the web server stops. Keep the SQLite
        // connection alive until that operation exits, then drain every already accepted catalog write.
        mMaintenanceLock.lock();
        try
        {
            boolean interrupted = false;
            while(mWriter.isAlive())
            {
                try
                {
                    mWriter.join();
                }
                catch(InterruptedException exception)
                {
                    interrupted = true;
                }
            }
            if(interrupted)
            {
                Thread.currentThread().interrupt();
            }
            try
            {
                mStore.close();
            }
            catch(SQLException exception)
            {
                mLog.warn("Unable to close managed recordings catalog", exception);
            }
        }
        finally
        {
            mMaintenanceLock.unlock();
        }
    }

    static int protocolCode(Protocol protocol)
    {
        if(protocol == null)
        {
            return 0;
        }
        return switch(protocol)
        {
            case APCO25 -> 1;
            case APCO25_PHASE2 -> 2;
            case DMR -> 3;
            case NXDN -> 4;
            case NBFM -> 5;
            case AM -> 6;
            case DCS -> 7;
            default -> 0;
        };
    }

    static String protocolName(int code)
    {
        return switch(code)
        {
            case 1 -> "APCO25";
            case 2 -> "APCO25_PHASE2";
            case 3 -> "DMR";
            case 4 -> "NXDN";
            case 5 -> "NBFM";
            case 6 -> "AM";
            case 7 -> "DCS";
            default -> "UNKNOWN";
        };
    }

    public record Site(int wacn, int systemId, int rfss, int siteId)
    {
        public Map<String,Object> toMap()
        {
            return Map.of("wacn", wacn, "system_id", systemId, "rfss_id", rfss, "site_id", siteId);
        }
    }

    public record Member(String kind, int id, Integer homeWacn, Integer homeSystemId, Integer homeIdentityId)
    {
        public Map<String,Object> toMap()
        {
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("kind", kind);
            value.put("id", id);
            put(value, "home_wacn", homeWacn);
            put(value, "home_system_id", homeSystemId);
            put(value, "home_identity_id", homeIdentityId);
            return value;
        }
    }

    public record RecordingCall(long id, long startMs, long endMs, long durationMs, String relativePath,
                                long sizeBytes, String systemKey, String channelId, long aliasListId,
                                String protocol, String callType, String voiceType, Integer sourceId,
                                Integer sourceHomeWacn, Integer sourceHomeSystem, Integer sourceHomeId,
                                Integer targetId, Integer targetHomeWacn, Integer targetHomeSystem,
                                Integer targetHomeId, Long frequencyHz, Integer timeslot, Integer nac,
                                Integer toneKind, String tone, Site winnerSite,
                                List<Site> alsoReceivedOn, List<Member> patchMembers)
    {
        public Map<String,Object> toMap()
        {
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("id", id);
            value.put("start_ms", startMs);
            value.put("end_ms", endMs);
            value.put("duration_ms", durationMs);
            value.put("size_bytes", sizeBytes);
            value.put("audio_url", "/api/v1/recordings/calls/" + id + "/audio");
            put(value, "system_key", systemKey);
            put(value, "channel_id", channelId);
            if(aliasListId > 0)
            {
                value.put("alias_list_id", aliasListId);
            }
            put(value, "protocol", protocol);
            put(value, "call_type", callType);
            put(value, "voice_type", voiceType);
            put(value, "source_id", sourceId);
            put(value, "source_home_wacn", sourceHomeWacn);
            put(value, "source_home_system_id", sourceHomeSystem);
            put(value, "source_home_id", sourceHomeId);
            if("GROUP".equals(callType) || "PATCH".equals(callType))
            {
                put(value, "talkgroup_id", targetId);
            }
            else if("DIRECT".equals(callType))
            {
                put(value, "destination_radio_id", targetId);
            }
            put(value, "target_id", targetId);
            put(value, "target_home_wacn", targetHomeWacn);
            put(value, "target_home_system_id", targetHomeSystem);
            put(value, "target_home_id", targetHomeId);
            put(value, "frequency_hz", frequencyHz);
            put(value, "timeslot", timeslot);
            put(value, "nac", nac);
            put(value, "tone", tone);
            if(toneKind != null && tone != null)
            {
                value.put("tone_kind", toneKind == 1 ? "DPL" : toneKind == 2 ? "PL" : "UNKNOWN");
                if(toneKind == 1)
                {
                    value.put("dpl", tone);
                }
                else if(toneKind == 2)
                {
                    value.put("pl", tone);
                }
            }
            if(winnerSite != null)
            {
                value.put("audio_from", winnerSite.toMap());
                value.put("wacn", winnerSite.wacn());
                value.put("system_id", winnerSite.systemId());
                value.put("rfss_id", winnerSite.rfss());
                value.put("site_id", winnerSite.siteId());
            }
            if(!alsoReceivedOn.isEmpty())
            {
                value.put("also_received_on", alsoReceivedOn.stream().map(Site::toMap).toList());
            }
            if(!patchMembers.isEmpty())
            {
                value.put("patch_members", patchMembers.stream().map(Member::toMap).toList());
            }
            return value;
        }
    }

    private static void put(Map<String,Object> map, String key, Object value)
    {
        if(value != null && !(value instanceof String text && text.isBlank()))
        {
            map.put(key, value);
        }
    }

    public record SearchPage(List<RecordingCall> calls, String nextCursor)
    {
    }

    public record Stats(long callCount, long totalBytes, int queuedWrites, int maximumQueuedWrites,
                        long droppedWrites, long failedWrites)
    {
    }

    public record Transcript(String status, String text, Long storedAtMs)
    {
        public Map<String,Object> toMap()
        {
            Map<String,Object> value = new LinkedHashMap<>();
            value.put("status", status);
            if(text != null)
            {
                value.put("text", text);
            }
            put(value, "stored_at_ms", storedAtMs);
            return value;
        }
    }

    public record TranscriptionCounts(long pending, long completed, long failed)
    {
        public Map<String,Object> toMap()
        {
            return Map.of("pending", pending, "completed", completed, "failed", failed);
        }
    }

    public record MaintenanceResult(long inspected, long removed, long corrected, long callCount, long totalBytes)
    {
    }

    /** Search parameters are immutable, and every query is constrained to a bounded time window and page size. */
    public static final class SearchFilter
    {
        private static final long DEFAULT_SPAN_MS = Duration.ofDays(1).toMillis();
        private static final long MAX_SPAN_MS = Duration.ofDays(3650).toMillis();
        public final long fromMs;
        public final long toMs;
        public final String systemKey;
        public final String channelId;
        public final Long aliasListId;
        public final Integer wacn;
        public final Integer systemId;
        public final Integer rfss;
        public final Integer siteId;
        public final Long minDurationMs;
        public final Long maxDurationMs;
        public final Integer talkgroupId;
        public final Integer talkgroupMin;
        public final Integer talkgroupMax;
        public final Integer sourceId;
        public final Integer sourceMin;
        public final Integer sourceMax;
        public final Integer anyIdentityId;
        public final List<Integer> anyIdentityIds;
        public final Long frequencyHz;
        public final String protocol;
        public final String callType;
        public final String voiceType;
        public final boolean sortAscending;
        public final String cursor;
        public final int limit;

        private SearchFilter(Builder builder)
        {
            long now = System.currentTimeMillis();
            toMs = builder.toMs != null ? builder.toMs : now;
            fromMs = builder.fromMs != null ? builder.fromMs : Math.max(0, toMs - DEFAULT_SPAN_MS);
            if(fromMs < 0 || toMs < fromMs || toMs - fromMs > MAX_SPAN_MS)
            {
                throw new IllegalArgumentException("Recording search time range must be within ten years");
            }
            systemKey = builder.systemKey;
            channelId = builder.channelId;
            aliasListId = builder.aliasListId;
            wacn = builder.wacn;
            systemId = builder.systemId;
            rfss = builder.rfss;
            siteId = builder.siteId;
            minDurationMs = builder.minDurationMs;
            maxDurationMs = builder.maxDurationMs;
            talkgroupId = builder.talkgroupId;
            talkgroupMin = builder.talkgroupMin;
            talkgroupMax = builder.talkgroupMax;
            sourceId = builder.sourceId;
            sourceMin = builder.sourceMin;
            sourceMax = builder.sourceMax;
            if(talkgroupMin != null && (talkgroupMax == null || talkgroupMin < 0 ||
                talkgroupMax < talkgroupMin) || talkgroupMax != null && talkgroupMin == null ||
                sourceMin != null && (sourceMax == null || sourceMin < 0 || sourceMax < sourceMin) ||
                sourceMax != null && sourceMin == null)
            {
                throw new IllegalArgumentException("Invalid recording identity range");
            }
            anyIdentityId = builder.anyIdentityId;
            anyIdentityIds = builder.anyIdentityIds != null ? List.copyOf(builder.anyIdentityIds) : List.of();
            if(anyIdentityIds.size() > 200 || anyIdentityIds.stream().anyMatch(value -> value == null || value < 0))
            {
                throw new IllegalArgumentException("Too many or invalid recording identity search IDs");
            }
            frequencyHz = builder.frequencyHz;
            protocol = builder.protocol;
            callType = builder.callType;
            voiceType = builder.voiceType;
            sortAscending = builder.sortAscending;
            cursor = builder.cursor;
            limit = Math.max(1, Math.min(MAX_PAGE_SIZE, builder.limit));
        }

        public static Builder builder()
        {
            return new Builder();
        }

        public static final class Builder
        {
            private Long fromMs, toMs, minDurationMs, maxDurationMs, frequencyHz, aliasListId;
            private String systemKey, channelId, protocol, callType, voiceType, cursor;
            private Integer wacn, systemId, rfss, siteId, talkgroupId, talkgroupMin, talkgroupMax,
                sourceId, sourceMin, sourceMax, anyIdentityId;
            private List<Integer> anyIdentityIds;
            private boolean sortAscending;
            private int limit = DEFAULT_PAGE_SIZE;

            public Builder fromMs(Long value) { fromMs = value; return this; }
            public Builder toMs(Long value) { toMs = value; return this; }
            public Builder systemKey(String value) { systemKey = value; return this; }
            public Builder channelId(String value) { channelId = value; return this; }
            public Builder aliasListId(Long value) { aliasListId = value; return this; }
            public Builder wacn(Integer value) { wacn = value; return this; }
            public Builder systemId(Integer value) { systemId = value; return this; }
            public Builder rfss(Integer value) { rfss = value; return this; }
            public Builder siteId(Integer value) { siteId = value; return this; }
            public Builder minDurationMs(Long value) { minDurationMs = value; return this; }
            public Builder maxDurationMs(Long value) { maxDurationMs = value; return this; }
            public Builder talkgroupId(Integer value) { talkgroupId = value; return this; }
            public Builder talkgroupMin(Integer value) { talkgroupMin = value; return this; }
            public Builder talkgroupMax(Integer value) { talkgroupMax = value; return this; }
            public Builder sourceId(Integer value) { sourceId = value; return this; }
            public Builder sourceMin(Integer value) { sourceMin = value; return this; }
            public Builder sourceMax(Integer value) { sourceMax = value; return this; }
            public Builder anyIdentityId(Integer value) { anyIdentityId = value; return this; }
            public Builder anyIdentityIds(List<Integer> value) { anyIdentityIds = value; return this; }
            public Builder frequencyHz(Long value) { frequencyHz = value; return this; }
            public Builder protocol(String value) { protocol = value; return this; }
            public Builder callType(String value) { callType = value; return this; }
            public Builder voiceType(String value) { voiceType = value; return this; }
            public Builder sortAscending(boolean value) { sortAscending = value; return this; }
            public Builder cursor(String value) { cursor = value; return this; }
            public Builder limit(int value) { limit = value; return this; }
            public SearchFilter build() { return new SearchFilter(this); }
        }
    }
}
