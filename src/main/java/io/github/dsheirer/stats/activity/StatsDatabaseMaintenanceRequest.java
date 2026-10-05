/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import java.util.Objects;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Requests database maintenance on the single statistics database writer.
 */
public final class StatsDatabaseMaintenanceRequest
{
    private final ReceiverActivityMaintenance.Operation mOperation;
    private final String mConfigurationId;
    private final DeletionTarget mDeletionTarget;
    private final CompletableFuture<ReceiverActivityMaintenance.Result> mResult = new CompletableFuture<>();
    private final AtomicBoolean mCancelled = new AtomicBoolean();
    private final AtomicBoolean mSubmitted = new AtomicBoolean();
    private final AtomicBoolean mResumeClaimed = new AtomicBoolean();
    private ProgressState mProgress = new ProgressState();
    private ReceiverActivityScopedDeletion.Batch mDeletionBatch;

    /** Transient job counters. Jobs and their continuation are retained only for this receiver process. */
    public record Progress(long rowsTotal, long rowsDeleted, long batchesCompleted, long cutoffMs,
                           boolean resumable, long rowsRetained, long databaseBusyRetries, long maximumBatchMs,
                           long observationQueueHighWater, long recordsDropped)
    {
    }

    public Progress progress()
    {
        return mProgress.snapshot;
    }

    /** Stops before the next committed batch; rows already removed remain removed. */
    public void cancel()
    {
        mCancelled.set(true);
    }

    /** Creates one continuation with the same cutoff, plan, and committed counters. */
    public StatsDatabaseMaintenanceRequest resume()
    {
        if(!mResult.isDone() || !progress().resumable() || !mResumeClaimed.compareAndSet(false, true))
            throw new IllegalStateException("Deletion is not available to resume");
        StatsDatabaseMaintenanceRequest continuation = delete(mDeletionTarget);
        continuation.mProgress = mProgress;
        continuation.mDeletionBatch = mDeletionBatch;
        return continuation;
    }

    boolean claimSubmission()
    {
        return mSubmitted.compareAndSet(false, true);
    }

    boolean cancelled()
    {
        return mCancelled.get();
    }

    ReceiverActivityScopedDeletion.Batch deletionBatch()
    {
        return mDeletionBatch;
    }

    void deletionBatch(ReceiverActivityScopedDeletion.Batch batch)
    {
        mDeletionBatch = batch;
    }

    void initializeProgress(long total, long cutoffMs, long droppedRecords)
    {
        mProgress.droppedAtStart = droppedRecords;
        mProgress.snapshot = new Progress(total, 0, 0, cutoffMs, false, 0, 0, 0, 0, 0);
    }

    void matchedRows(long total)
    {
        Progress previous = progress();
        mProgress.snapshot = new Progress(total, previous.rowsDeleted(), previous.batchesCompleted(),
            previous.cutoffMs(), previous.resumable(), previous.rowsRetained(), previous.databaseBusyRetries(),
            previous.maximumBatchMs(), previous.observationQueueHighWater(), previous.recordsDropped());
    }

    void preparationDuration(long elapsedMilliseconds)
    {
        Progress previous = progress();
        mProgress.snapshot = new Progress(previous.rowsTotal(), previous.rowsDeleted(), previous.batchesCompleted(),
            previous.cutoffMs(), previous.resumable(), previous.rowsRetained(), previous.databaseBusyRetries(),
            Math.max(previous.maximumBatchMs(), elapsedMilliseconds), previous.observationQueueHighWater(),
            previous.recordsDropped());
    }

    void committedBatch(long deleted, long elapsedMilliseconds)
    {
        Progress previous = progress();
        mProgress.snapshot = new Progress(previous.rowsTotal(), previous.rowsDeleted() + deleted,
            previous.batchesCompleted() + 1, previous.cutoffMs(), false, 0, previous.databaseBusyRetries(),
            Math.max(previous.maximumBatchMs(), elapsedMilliseconds), previous.observationQueueHighWater(),
            previous.recordsDropped());
    }

    void observeWriter(int queued, long droppedRecords)
    {
        Progress previous = progress();
        mProgress.snapshot = new Progress(previous.rowsTotal(), previous.rowsDeleted(), previous.batchesCompleted(),
            previous.cutoffMs(), previous.resumable(), previous.rowsRetained(), previous.databaseBusyRetries(),
            previous.maximumBatchMs(), Math.max(previous.observationQueueHighWater(), queued),
            Math.max(previous.recordsDropped(), Math.max(0, droppedRecords - mProgress.droppedAtStart)));
    }

    void databaseBusy()
    {
        Progress previous = progress();
        mProgress.snapshot = new Progress(previous.rowsTotal(), previous.rowsDeleted(), previous.batchesCompleted(),
            previous.cutoffMs(), previous.resumable(), previous.rowsRetained(), previous.databaseBusyRetries() + 1,
            previous.maximumBatchMs(), previous.observationQueueHighWater(), previous.recordsDropped());
    }

    void setResumable(boolean resumable)
    {
        Progress previous = progress();
        mProgress.snapshot = new Progress(previous.rowsTotal(), previous.rowsDeleted(), previous.batchesCompleted(),
            previous.cutoffMs(), resumable, resumable ? 0 : Math.max(0, previous.rowsTotal() - previous.rowsDeleted()),
            previous.databaseBusyRetries(), previous.maximumBatchMs(), previous.observationQueueHighWater(),
            previous.recordsDropped());
    }

    private static final class ProgressState
    {
        private volatile Progress snapshot = new Progress(0, 0, 0, 0, false, 0, 0, 0, 0, 0);
        private long droppedAtStart;
    }

    private StatsDatabaseMaintenanceRequest(ReceiverActivityMaintenance.Operation operation, String configurationId,
                                            DeletionTarget deletionTarget)
    {
        mOperation = Objects.requireNonNull(operation, "Maintenance operation is required");
        mConfigurationId = configurationId;
        mDeletionTarget = deletionTarget;

        if(operation == ReceiverActivityMaintenance.Operation.CLEAR_CHANNEL_STATS &&
            (configurationId == null || configurationId.isBlank()))
        {
            throw new IllegalArgumentException("Channel configuration ID is required");
        }
        else if(operation != ReceiverActivityMaintenance.Operation.CLEAR_CHANNEL_STATS && configurationId != null)
        {
            throw new IllegalArgumentException("Channel configuration ID is only valid for CLEAR_CHANNEL_STATS");
        }

        if((operation == ReceiverActivityMaintenance.Operation.DELETE_RETAINED_STATS) != (deletionTarget != null))
        {
            throw new IllegalArgumentException("Deletion target is required only for DELETE_RETAINED_STATS");
        }
    }

    public static StatsDatabaseMaintenanceRequest forOperation(ReceiverActivityMaintenance.Operation operation)
    {
        return new StatsDatabaseMaintenanceRequest(operation, null, null);
    }

    public static StatsDatabaseMaintenanceRequest clearChannel(String configurationId)
    {
        return new StatsDatabaseMaintenanceRequest(ReceiverActivityMaintenance.Operation.CLEAR_CHANNEL_STATS,
            configurationId, null);
    }

    public static StatsDatabaseMaintenanceRequest delete(DeletionTarget target)
    {
        return new StatsDatabaseMaintenanceRequest(ReceiverActivityMaintenance.Operation.DELETE_RETAINED_STATS,
            null, Objects.requireNonNull(target, "Deletion target is required"));
    }

    public ReceiverActivityMaintenance.Operation operation()
    {
        return mOperation;
    }

    public String configurationId()
    {
        return mConfigurationId;
    }

    public DeletionTarget deletionTarget()
    {
        return mDeletionTarget;
    }

    public sealed interface DeletionTarget permits Frequency, Identity, ConventionalIdentity, LearnedSite,
        SavedSite, Channel, System, ScopedData
    {
    }

    public record Frequency(String configurationId, String expectedSiteKey, long frequencyHz)
        implements DeletionTarget
    {
        public Frequency
        {
            requireText(configurationId, "Channel configuration ID");
            requireText(expectedSiteKey, "Expected site key");
            if(frequencyHz <= 0) throw new IllegalArgumentException("Frequency must be positive");
        }
    }

    public enum IdentityKind
    {
        RADIO, TALKGROUP
    }

    public record Identity(String radioSystemKey, String identityKey, IdentityKind kind) implements DeletionTarget
    {
        public Identity
        {
            requireText(radioSystemKey, "Radio system key");
            requireText(identityKey, "Identity key");
            Objects.requireNonNull(kind, "Identity kind is required");
        }
    }

    /** One row in a DMR conventional saved channel's lifetime identity summary. */
    public record ConventionalIdentity(String configurationId, long frequencyHz, int timeslot, int identityId,
                                       IdentityKind kind) implements DeletionTarget
    {
        public ConventionalIdentity
        {
            requireText(configurationId, "Channel configuration ID");
            if(frequencyHz <= 0) throw new IllegalArgumentException("Frequency must be positive");
            if(timeslot != 1 && timeslot != 2) throw new IllegalArgumentException("Timeslot must be 1 or 2");
            if(identityId <= 0 || identityId > 16_777_215)
                throw new IllegalArgumentException("Identity ID is outside its supported range");
            Objects.requireNonNull(kind, "Identity kind is required");
        }
    }

    public record LearnedSite(String radioSystemKey, int rfss, int site, boolean includeChannelHistory)
        implements DeletionTarget
    {
        public LearnedSite
        {
            requireText(radioSystemKey, "Radio system key");
            if(rfss < 0 || rfss > 255 || site < 0 || site > 255)
                throw new IllegalArgumentException("RFSS and site must be between 0 and 255");
        }
    }

    public record SavedSite(String configurationId, String expectedSiteKey, boolean includeChannelHistory)
        implements DeletionTarget
    {
        public SavedSite
        {
            requireText(configurationId, "Channel configuration ID");
            requireText(expectedSiteKey, "Expected site key");
        }
    }

    public record Channel(String configurationId) implements DeletionTarget
    {
        public Channel
        {
            requireText(configurationId, "Channel configuration ID");
        }
    }

    public record System(String radioSystemKey, boolean includeChannelHistory) implements DeletionTarget
    {
        public System
        {
            requireText(radioSystemKey, "Radio system key");
        }
    }

    /** A bounded, source-owned family of retained observations. No configuration rows are targets. */
    public record ScopedData(String sourceKind, String sourceKey, String siteConfigurationId,
                             String expectedSiteKey, String dataType, String recordKey, List<String> parts,
                             Long fromMs, Long toMs, Long frequencyHz)
        implements DeletionTarget
    {
        private static final Set<String> SOURCE_KINDS = Set.of("radio_system", "saved_channel", "alias_activity");
        private static final Set<String> DATA_TYPES = Set.of("all", "site_state", "frequencies", "band_plans",
            "foreign_band_plans", "neighbors", "patches", "control_quality", "radios", "talkgroups",
            "relationships", "affiliations", "call_activity", "signaling_activity", "detailed_events",
            "hourly_history", "alias_activity", "issi_assignment_history");
        private static final Set<String> PARTS = Set.of("current", "summary", "buckets", "events");

        public ScopedData(String sourceKind, String sourceKey, String siteConfigurationId, String expectedSiteKey,
                          String dataType, String recordKey, List<String> parts)
        {
            this(sourceKind, sourceKey, siteConfigurationId, expectedSiteKey, dataType, recordKey, parts,
                null, null, null);
        }

        public ScopedData
        {
            if(fromMs != null && fromMs < 0 || toMs != null && toMs <= 0 ||
                fromMs != null && toMs != null && toMs <= fromMs)
                throw new IllegalArgumentException("History range must have an inclusive start and later exclusive end");
            if(frequencyHz != null && frequencyHz <= 0)
                throw new IllegalArgumentException("History frequency must be positive");
            if((fromMs != null || toMs != null || frequencyHz != null) &&
                !Set.of("control_quality", "hourly_history", "detailed_events").contains(dataType))
                throw new IllegalArgumentException("Filters are available for history data only");
            if(!SOURCE_KINDS.contains(sourceKind)) throw new IllegalArgumentException("Source kind is invalid");
            if(!DATA_TYPES.contains(dataType)) throw new IllegalArgumentException("Data type is invalid");
            if((siteConfigurationId == null) != (expectedSiteKey == null))
                throw new IllegalArgumentException("Site configuration and site key must be supplied together");
            if(siteConfigurationId != null)
            {
                requireText(siteConfigurationId, "Site configuration ID");
                requireText(expectedSiteKey, "Expected site key");
            }
            if("alias_activity".equals(sourceKind))
            {
                if(sourceKey != null || siteConfigurationId != null || !"alias_activity".equals(dataType))
                    throw new IllegalArgumentException("Alias Activity is receiver-wide");
            }
            else
            {
                requireText(sourceKey, "Source key");
                if("alias_activity".equals(dataType))
                    throw new IllegalArgumentException("Alias Activity has no per-source attribution");
            }
            if(recordKey != null && (recordKey.isBlank() || recordKey.length() > 128))
                throw new IllegalArgumentException("Record key is invalid");
            if(parts == null || parts.isEmpty() || parts.size() > 4 || !PARTS.containsAll(parts))
                throw new IllegalArgumentException("Select supported retained-data parts");
            parts = parts.stream().distinct().sorted().toList();
            if("alias_activity".equals(dataType) && !parts.equals(List.of("summary")))
                throw new IllegalArgumentException("Alias Activity has only a summary part");
            if("issi_assignment_history".equals(dataType) &&
                (!"radio_system".equals(sourceKind) || siteConfigurationId != null || recordKey != null ||
                    !parts.equals(List.of("summary"))))
                throw new IllegalArgumentException("ISSI assignment history requires a whole radio system and saved mappings & counts");
        }
    }

    private static void requireText(String value, String name)
    {
        if(value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    public CompletableFuture<ReceiverActivityMaintenance.Result> result()
    {
        return mResult;
    }
}
