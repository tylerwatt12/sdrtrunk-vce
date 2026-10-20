/* Copyright (C) 2026 Dennis Sheirer. GNU General Public License, version 3 or later. */
package io.github.dsheirer.stats.activity;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReferenceArray;
import org.sqlite.SQLiteUpdateListener;

/**
 * Writer-owned, process-local receipts for committed Activity changes. Publication is a bounded atomic ring
 * write: readers cannot acquire a lock, run work, or backpressure the database writer.
 */
public final class ActivityCommitJournal
{
    public static final int MAXIMUM_RANGE_IDS = 65_536;
    private static final int MAXIMUM_TRANSACTION_IDS = 8_192;
    private static final int RETAINED_COMMITS = 256;
    public record Boundary(String sourceEpoch, long sequence) { }
    public record Range(boolean available, Boundary boundary, List<Long> ids) { }
    private record Commit(String epoch, long sequence, long[] ids, boolean gap) { }
    private final AtomicReferenceArray<Commit> mCommits;
    private final Set<Long> mTransactionIds = new LinkedHashSet<>();
    private final int mMaximumTransactionIds;
    private volatile Boundary mBoundary = new Boundary(UUID.randomUUID().toString(), 0);
    private boolean mCollecting;
    private boolean mOverflow;

    public ActivityCommitJournal()
    {
        this(RETAINED_COMMITS, MAXIMUM_TRANSACTION_IDS);
    }

    ActivityCommitJournal(int commits, int transactionIds)
    {
        mCommits = new AtomicReferenceArray<>(Math.max(1, commits));
        mMaximumTransactionIds = Math.max(1, transactionIds);
    }

    void begin()
    {
        mTransactionIds.clear();
        mOverflow = false;
        mCollecting = true;
    }

    void updated(SQLiteUpdateListener.Type type, String database, String table, long id)
    {
        if(!mCollecting || mOverflow || id <= 0 || !"main".equals(database) ||
            !"receiver_activity_event".equals(table)) return;
        if(mTransactionIds.contains(id)) return;
        if(mTransactionIds.size() == mMaximumTransactionIds)
        {
            mOverflow = true;
            mTransactionIds.clear();
        }
        else mTransactionIds.add(id);
    }

    /** Call only after the owning SQL transaction completed successfully. */
    void committed()
    {
        if(mCollecting && (mOverflow || !mTransactionIds.isEmpty()))
        {
            Boundary previous = mBoundary;
            long sequence = previous.sequence() + 1;
            long[] ids = mTransactionIds.stream().mapToLong(Long::longValue).toArray();
            mCommits.set((int)(sequence % mCommits.length()),
                new Commit(previous.sourceEpoch(), sequence, ids, mOverflow));
            mBoundary = new Boundary(previous.sourceEpoch(), sequence);
        }
        rolledBack();
    }

    void rolledBack()
    {
        mCollecting = false;
        mTransactionIds.clear();
        mOverflow = false;
    }

    /** Whole-source changes can bypass SQLite row callbacks (including truncate optimization). */
    void invalidate()
    {
        rolledBack();
        mBoundary = new Boundary(UUID.randomUUID().toString(), 0);
    }

    public Boundary boundary()
    {
        return mBoundary;
    }

    /** Bounded reader-side copy. A concurrent overwrite/reset is an explicit unavailable range. */
    public Range range(String epoch, long afterSequence, Long requestedThrough)
    {
        Boundary boundary = mBoundary;
        long through = requestedThrough != null ? requestedThrough : boundary.sequence();
        if(!boundary.sourceEpoch().equals(epoch) || afterSequence < 0 || through < afterSequence ||
            through > boundary.sequence() || afterSequence < boundary.sequence() - mCommits.length())
            return new Range(false, boundary, List.of());
        TreeSet<Long> ids = new TreeSet<>();
        for(long sequence = afterSequence + 1; sequence <= through; sequence++)
        {
            Commit commit = mCommits.get((int)(sequence % mCommits.length()));
            if(commit == null || commit.sequence() != sequence || !commit.epoch().equals(epoch) || commit.gap())
                return new Range(false, mBoundary, List.of());
            for(long id: commit.ids())
            {
                ids.add(id);
                if(ids.size() > MAXIMUM_RANGE_IDS) return new Range(false, mBoundary, List.of());
            }
        }
        if(!mBoundary.sourceEpoch().equals(epoch)) return new Range(false, mBoundary, List.of());
        return new Range(true, new Boundary(epoch, through), List.copyOf(ids));
    }
}
