/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs only administrator-requested catalog verification jobs, one at a time. */
final class ManagedRecordingMaintenance implements AutoCloseable
{
    private static final Logger mLog = LoggerFactory.getLogger(ManagedRecordingMaintenance.class);
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "managed-recordings-maintenance");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<Status> mStatus = new AtomicReference<>(
        new Status(null, "IDLE", 0L, null, null, null));
    private boolean mClosed;

    synchronized Status start(String kind, ManagedRecordingCatalog catalog)
    {
        Objects.requireNonNull(catalog);
        if(!"recount".equals(kind) && !"reindex".equals(kind))
        {
            throw new IllegalArgumentException("Unknown recording maintenance operation");
        }
        if(mClosed)
        {
            throw new IllegalStateException("Recording maintenance is unavailable");
        }
        Status previous = mStatus.get();
        if("RUNNING".equals(previous.state()))
        {
            return null;
        }
        Status running = new Status(kind, "RUNNING", System.currentTimeMillis(), null, null, null);
        mStatus.set(running);
        mExecutor.execute(() -> {
            try
            {
                ManagedRecordingCatalog.MaintenanceResult result = "recount".equals(kind) ?
                    catalog.recount() : catalog.reindex();
                mStatus.set(new Status(kind, "COMPLETED", running.startedAtMs(),
                    System.currentTimeMillis(), result, null));
            }
            catch(Exception exception)
            {
                mLog.warn("Manual managed recording maintenance failed", exception);
                mStatus.set(new Status(kind, "FAILED", running.startedAtMs(),
                    System.currentTimeMillis(), null, "The maintenance operation failed"));
            }
        });
        return running;
    }

    Status status()
    {
        return mStatus.get();
    }

    @Override
    public synchronized void close()
    {
        mClosed = true;
        mExecutor.shutdownNow();
    }

    record Status(String kind, String state, long startedAtMs, Long finishedAtMs,
                  ManagedRecordingCatalog.MaintenanceResult result, String error)
    {
    }
}
