/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.stats.activity.ReceiverActivityMaintenance;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.DeletionTarget;
import io.github.dsheirer.stats.activity.StatsDatabaseMaintenanceRequest.ScopedData;
import io.github.dsheirer.web.http.ApiHttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

/** Bounded receiver-lifetime cleanup jobs. Listener reload never resets admission or retry keys. */
final class RetainedStatisticsJobs implements AutoCloseable
{
    private static final int MAXIMUM_JOBS = 64;
    private static final long COMPLETED_JOB_RETENTION_MS = 3_600_000L;
    private final RetainedStatisticsCatalog mCatalog;
    private final Consumer<StatsDatabaseMaintenanceRequest> mDispatcher;
    private final Object mJobLock = new Object();
    private final Map<String,Job> mJobs = new LinkedHashMap<>();
    private final Semaphore mActiveDeletion = new Semaphore(1, true);
    private boolean mClosed;

    RetainedStatisticsJobs(RetainedStatisticsCatalog catalog, Consumer<StatsDatabaseMaintenanceRequest> dispatcher)
    {
        mCatalog = Objects.requireNonNull(catalog);
        mDispatcher = Objects.requireNonNull(dispatcher);
    }

    Map<String,Object> submit(String requestId, DeletionTarget target)
    {
        Job job;
        synchronized(mJobLock)
        {
            requireOpen();
            pruneJobs();
            job = mJobs.get(requestId);
            requireSameTarget(job, target);
        }
        if(job != null)
        {
            return snapshot(job);
        }
        String label = mCatalog.jobLabel(target);
        StatsDatabaseMaintenanceRequest request = null;
        synchronized(mJobLock)
        {
            requireOpen();
            pruneJobs();
            job = mJobs.get(requestId);
            if(job != null)
            {
                requireSameTarget(job, target);
            }
            else
            {
                while(mJobs.size() >= MAXIMUM_JOBS && evictOldestCompletedJob())
                {
                    // Keep active work and the newest retry keys; allow long cleanup sessions to continue.
                }
                if(mJobs.size() >= MAXIMUM_JOBS || !mActiveDeletion.tryAcquire())
                {
                    throw new StatsApiException(429, "deletion_busy", "A deletion is already in progress");
                }
                job = new Job(requestId, target, label);
                mJobs.put(requestId, job);
                request = StatsDatabaseMaintenanceRequest.delete(target);
                job.request = request;
            }
        }
        if(request != null)
        {
            dispatch(job, request);
        }
        return snapshot(job);
    }

    private static void requireSameTarget(Job job, DeletionTarget target)
    {
        if(job != null && !job.target.equals(target))
        {
            throw new StatsApiException(409, "request_id_conflict",
                "This request ID was already used for another target");
        }
    }

    private void dispatch(Job job, StatsDatabaseMaintenanceRequest request)
    {
        request.result().whenComplete((result, failure) -> finish(job, request, result, failure));
        try
        {
            mDispatcher.accept(request);
        }
        catch(RuntimeException exception)
        {
            request.result().completeExceptionally(exception);
        }
    }

    private void finish(Job job, StatsDatabaseMaintenanceRequest request,
                        ReceiverActivityMaintenance.Result result, Throwable failure)
    {
        synchronized(mJobLock)
        {
            if(job.request != request) return;
            if(failure != null || result == null)
            {
                job.state = "failed";
                job.error = request.progress().resumable() ?
                    "Cleanup stopped. Resume to delete the remaining records." :
                    "Cleanup failed. Refresh the results and try again.";
            }
            else
            {
                job.state = result.deletionOutcome() == ReceiverActivityMaintenance.DeletionOutcome.INTERRUPTED ?
                    "cancelled" : "succeeded";
                job.rowsDeleted = Math.max(request.progress().rowsDeleted(), result.rowsDeleted());
                job.outcome = switch(result.deletionOutcome())
                {
                    case NOT_FOUND -> "not_found";
                    case STALE_SITE -> "stale_site";
                    case TOO_LARGE -> "too_large";
                    case INTERRUPTED -> "interrupted";
                    default -> "deleted";
                };
            }
            job.completedAtMs = System.currentTimeMillis();
            mActiveDeletion.release();
        }
    }

    Map<String,Object> snapshot(String requestId)
    {
        Job job;
        synchronized(mJobLock)
        {
            pruneJobs();
            job = requireJob(requestId);
        }
        return snapshot(job);
    }

    List<Map<String,Object>> list()
    {
        List<Map<String,Object>> jobs;
        synchronized(mJobLock)
        {
            pruneJobs();
            jobs = new ArrayList<>(mJobs.values().stream().map(Job::snapshot).toList());
        }
        java.util.Collections.reverse(jobs);
        return jobs;
    }

    Map<String,Object> control(String requestId, boolean resume)
    {
        Job job;
        StatsDatabaseMaintenanceRequest request = null;
        synchronized(mJobLock)
        {
            requireOpen();
            pruneJobs();
            job = requireJob(requestId);
            if(resume)
            {
                if(Set.of("queued", "running", "cancelling").contains(job.state))
                {
                    // Retrying an accepted resume must not enqueue another maintenance request.
                }
                else if(job.canResume())
                {
                    if(!mActiveDeletion.tryAcquire())
                    {
                        throw new StatsApiException(429, "deletion_busy", "A deletion is already in progress");
                    }
                    try
                    {
                        request = job.request.resume();
                        job.request = request;
                        job.state = "running";
                        job.error = null;
                        job.outcome = null;
                        job.completedAtMs = 0;
                    }
                    catch(RuntimeException exception)
                    {
                        mActiveDeletion.release();
                        throw exception;
                    }
                }
                else
                {
                    throw new StatsApiException(409, "deletion_not_resumable",
                        "Cleanup cannot be resumed. Refresh the results and start another cleanup.");
                }
            }
            else if(Set.of("queued", "running", "cancelling").contains(job.state))
            {
                job.state = "cancelling";
                job.request.cancel();
            }
        }
        if(request != null) dispatch(job, request);
        return snapshot(job);
    }

    private Map<String,Object> snapshot(Job job)
    {
        synchronized(mJobLock)
        {
            return job.snapshot();
        }
    }

    /** Caller holds mJobLock. */
    private Job requireJob(String requestId)
    {
        String canonical;
        try
        {
            canonical = UUID.fromString(requestId).toString();
        }
        catch(IllegalArgumentException exception)
        {
            throw missingJob();
        }
        if(!canonical.equals(requestId)) throw missingJob();
        Job job = mJobs.get(canonical);
        if(job == null) throw missingJob();
        return job;
    }

    private static StatsApiException missingJob()
    {
        return new StatsApiException(404, "job_not_found",
            "Cleanup status is no longer available. If the receiver restarted, refresh the results and " +
                "start another cleanup for the remaining records.");
    }

    /** Caller holds mJobLock. */
    private void pruneJobs()
    {
        long oldest = System.currentTimeMillis() - COMPLETED_JOB_RETENTION_MS;
        Iterator<Job> jobs = mJobs.values().iterator();
        while(jobs.hasNext())
        {
            Job job = jobs.next();
            if(job.completedAtMs > 0 && job.completedAtMs < oldest)
            {
                jobs.remove();
            }
        }
    }

    /** Caller holds mJobLock. */
    private boolean evictOldestCompletedJob()
    {
        Iterator<Job> jobs = mJobs.values().iterator();
        while(jobs.hasNext())
        {
            if(jobs.next().completedAtMs > 0)
            {
                jobs.remove();
                return true;
            }
        }
        return false;
    }

    private static final class Job
    {
        private final String id;
        private final DeletionTarget target;
        private final String label;
        private volatile String state = "queued";
        private volatile Long rowsDeleted;
        private volatile StatsDatabaseMaintenanceRequest request;
        private volatile String outcome;
        private volatile String error;
        private volatile long completedAtMs;

        private Job(String id, DeletionTarget target, String label)
        {
            this.id = id;
            this.target = target;
            this.label = label;
            state = "running";
        }

        private boolean canResume()
        {
            return Set.of("cancelled", "failed").contains(state) && request != null &&
                request.progress().resumable();
        }

        private Map<String,Object> snapshot()
        {
            Map<String,Object> result = new LinkedHashMap<>();
            result.put("job_id", id);
            result.put("state", state);
            result.put("label", label);
            String kind = switch(target)
            {
                case ScopedData ignored -> "scoped_data";
                case StatsDatabaseMaintenanceRequest.Frequency ignored -> "frequency";
                case StatsDatabaseMaintenanceRequest.Identity identity ->
                    identity.kind() == StatsDatabaseMaintenanceRequest.IdentityKind.RADIO ? "radio" : "talkgroup";
                case StatsDatabaseMaintenanceRequest.ConventionalIdentity identity ->
                    identity.kind() == StatsDatabaseMaintenanceRequest.IdentityKind.RADIO ?
                        "conventional_radio" : "conventional_talkgroup";
                case StatsDatabaseMaintenanceRequest.LearnedSite ignored -> "learned_site";
                case StatsDatabaseMaintenanceRequest.SavedSite ignored -> "saved_site";
                case StatsDatabaseMaintenanceRequest.Channel ignored -> "channel";
                case StatsDatabaseMaintenanceRequest.System ignored -> "system";
                case StatsDatabaseMaintenanceRequest.TalkerAlias ignored -> "talker_alias";
            };
            var targetFields = (com.fasterxml.jackson.databind.node.ObjectNode)ApiHttpResponse.normalizePayload(target);
            targetFields.put("kind", kind);
            if(target instanceof StatsDatabaseMaintenanceRequest.Frequency)
            {
                targetFields.set("site_configuration_id", targetFields.remove("configuration_id"));
            }
            else if(target instanceof StatsDatabaseMaintenanceRequest.ConventionalIdentity)
            {
                targetFields.set("native_id", targetFields.remove("identity_id"));
            }
            List<String> missing = new ArrayList<>();
            targetFields.fields().forEachRemaining(field -> {
                if(field.getValue().isNull()) missing.add(field.getKey());
            });
            targetFields.remove(missing);
            result.put("target", targetFields);
            if(request != null)
            {
                var progress = request.progress();
                result.put("rows_deleted", Math.max(progress.rowsDeleted(), rowsDeleted == null ? 0 : rowsDeleted));
                result.put("rows_total", progress.rowsTotal());
                result.put("batches_completed", progress.batchesCompleted());
                result.put("rows_retained", progress.rowsRetained());
                result.put("database_busy_retries", progress.databaseBusyRetries());
                result.put("maximum_batch_ms", progress.maximumBatchMs());
                result.put("observation_queue_high_water", progress.observationQueueHighWater());
                result.put("records_dropped", progress.recordsDropped());
                if(progress.cutoffMs() > 0) result.put("cutoff_ms", progress.cutoffMs());
            }
            result.put("can_resume", canResume());
            if(outcome != null) result.put("outcome", outcome);
            if(error != null) result.put("error", error);
            return result;
        }
    }
    private void requireOpen()
    {
        if(mClosed) throw new StatsApiException(503, "statistics_unavailable", "Cleanup is stopping");
    }

    @Override public void close()
    {
        synchronized(mJobLock)
        {
            mClosed = true;
            for(Job job: mJobs.values())
                if(job.request != null && Set.of("queued", "running", "cancelling").contains(job.state))
                    job.request.cancel();
        }
    }
}
