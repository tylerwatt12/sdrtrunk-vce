/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded, session-only history of confirmed duplicate-call decisions.
 *
 * <p>Offers perform only fixed atomic operations. Snapshots do not block producers, and this service never starts a
 * worker or writes diagnostic files.</p>
 */
public final class LogicalCallDiagnosticService implements LogicalCallDiagnosticSink, AutoCloseable
{
    private final String mSessionId = UUID.randomUUID().toString();
    private final long mSessionStartedAtEpochMillis = System.currentTimeMillis();
    private final LogicalCallDiagnosticHistory mHistory;
    private final AtomicBoolean mAccepting = new AtomicBoolean(true);
    private final AtomicLong mDecisionsObserved = new AtomicLong();
    private final AtomicLong mRecordsRejectedAfterClose = new AtomicLong();

    public LogicalCallDiagnosticService()
    {
        this(new LogicalCallDiagnosticConfiguration(
            LogicalCallDiagnosticConfiguration.DEFAULT_RECENT_DUPLICATE_CAPACITY));
    }

    /** Creates a service with an explicit fixed duplicate-history limit. */
    public LogicalCallDiagnosticService(LogicalCallDiagnosticConfiguration configuration)
    {
        Objects.requireNonNull(configuration, "configuration cannot be null");
        mHistory = new LogicalCallDiagnosticHistory(configuration.recentDuplicateCapacity());
    }

    /** Accepts one final resolver decision without waiting. Only confirmed merges occupy the bounded history. */
    @Override
    public boolean offer(LogicalCallDiagnosticDecision decision)
    {
        Objects.requireNonNull(decision, "decision cannot be null");

        if(!mAccepting.get())
        {
            mRecordsRejectedAfterClose.incrementAndGet();
            return false;
        }

        if(decision.outcome() == LogicalCallDecisionOutcome.MERGED)
        {
            mDecisionsObserved.incrementAndGet();
            mHistory.append(decision);
        }

        return true;
    }

    /** Immutable recent duplicate view for the administrator UI. */
    public LogicalCallDiagnosticServiceSnapshot snapshot()
    {
        LogicalCallDiagnosticHistory.Snapshot history = mHistory.snapshot();
        return new LogicalCallDiagnosticServiceSnapshot(mSessionId, mSessionStartedAtEpochMillis,
            history.evictedDuplicates(), history.duplicates(), status());
    }

    public LogicalCallDiagnosticStatus status()
    {
        return new LogicalCallDiagnosticStatus(mAccepting.get(), mDecisionsObserved.get(),
            mRecordsRejectedAfterClose.get());
    }

    /** Stops accepting new observations; there are no threads or files to drain. */
    @Override
    public void close()
    {
        mAccepting.set(false);
    }
}
