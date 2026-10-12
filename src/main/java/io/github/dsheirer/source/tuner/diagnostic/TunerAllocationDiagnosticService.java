/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.diagnostic;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Fixed, session-only decision history. Producers never wait, project names, write files, or start observer workers.
 * Repeated identical failures update one recent entry; successful calls received in an existing window are not offered.
 */
public final class TunerAllocationDiagnosticService implements AutoCloseable
{
    public static final int CAPACITY = 256;
    private static final int REPEAT_SEARCH_LIMIT = 16;
    private static final long REPEAT_WINDOW_MS = 30_000;
    private final String mSessionId = UUID.randomUUID().toString();
    private final AtomicReferenceArray<Entry> mHistory;
    private final AtomicLong mNextPosition = new AtomicLong();
    private final AtomicLong mRevision = new AtomicLong();
    private final AtomicBoolean mAccepting = new AtomicBoolean(true);
    private final int mMask;

    public TunerAllocationDiagnosticService() { this(CAPACITY); }

    /** Fixed smaller capacity is useful for saturation tests. */
    public TunerAllocationDiagnosticService(int capacity)
    {
        if(capacity < 2 || capacity > CAPACITY || Integer.bitCount(capacity) != 1)
            throw new IllegalArgumentException("Capacity must be a power of two from 2 through 256");
        mHistory = new AtomicReferenceArray<>(capacity);
        mMask = capacity - 1;
    }

    public boolean offer(TunerAllocationDecision decision)
    {
        Objects.requireNonNull(decision);
        if(!mAccepting.get()) return false;
        long revision = mRevision.incrementAndGet();
        long next = mNextPosition.get();
        if(decision.outcome() == TunerAllocationDecision.Outcome.ALLOCATION_FAILED)
        {
            int count = (int)Math.min(Math.min(next, mHistory.length()), REPEAT_SEARCH_LIMIT);
            for(int offset = 1; offset <= count; offset++)
            {
                int index = (int)(next - offset) & mMask;
                Entry previous = mHistory.get(index);
                if(previous != null && !previous.resolved() && sameFailure(previous.decision(), decision) &&
                    decision.occurredAtMs() >= previous.lastTimestamp() &&
                    decision.occurredAtMs() - previous.lastTimestamp() <= REPEAT_WINDOW_MS)
                {
                    Entry updated = new Entry(previous.position(), previous.decisionId(), revision, decision,
                        previous.firstTimestamp(), decision.occurredAtMs(), previous.repeatCount() + 1, false);
                    if(mHistory.compareAndSet(index, previous, updated)) return true;
                    // A contended repeat is expendable. Never retry or delay receiving for diagnostic completeness.
                    return false;
                }
            }
        }
        long position = mNextPosition.getAndIncrement();
        Entry entry = new Entry(position, revision, revision, decision, decision.occurredAtMs(),
            decision.occurredAtMs(), 1, false);
        int index = (int)position & mMask;
        Entry previous = mHistory.get(index);
        // One bounded CAS prevents a delayed producer from replacing a more recent wraparound entry.
        return (previous == null || previous.position() < position) && mHistory.compareAndSet(index, previous, entry);
    }

    private static boolean sameFailure(TunerAllocationDecision left, TunerAllocationDecision right)
    {
        return left.outcome() == right.outcome() && left.reason() == right.reason() &&
            Objects.equals(left.context(), right.context()) && Objects.equals(left.tunerIdentity(), right.tunerIdentity()) &&
            Objects.equals(left.requestedFrequencyHz(), right.requestedFrequencyHz()) &&
            Objects.equals(left.requestedBandwidthHz(), right.requestedBandwidthHz()) &&
            Objects.equals(left.beforeFrequencyHz(), right.beforeFrequencyHz()) &&
            Objects.equals(left.requestedCenterFrequencyHz(), right.requestedCenterFrequencyHz()) &&
            Objects.equals(left.afterFrequencyHz(), right.afterFrequencyHz()) &&
            Objects.equals(left.usableBandwidthHz(), right.usableBandwidthHz()) &&
            Objects.equals(left.middleUnusableHalfBandwidthHz(), right.middleUnusableHalfBandwidthHz()) &&
            Objects.equals(left.controlFrequencyHz(), right.controlFrequencyHz()) &&
            Objects.equals(left.coveredBefore(), right.coveredBefore()) &&
            Objects.equals(left.coveredAfter(), right.coveredAfter()) &&
            Objects.equals(left.candidates(), right.candidates());
    }

    /** Successful allocation closes prior allocation episodes; startup failures wait for a successful source start. */
    public void resolved(TunerAllocationContext context, long frequencyHz)
    {
        closeEpisodes(context, frequencyHz, false);
    }

    /** Completed source startup closes failure episodes without adding a routine successful-call history row. */
    public void sourceStarted(TunerAllocationContext context, long frequencyHz)
    {
        closeEpisodes(context, frequencyHz, true);
    }

    private void closeEpisodes(TunerAllocationContext context, long frequencyHz, boolean sourceStarted)
    {
        long next = mNextPosition.get();
        int count = (int)Math.min(Math.min(next, mHistory.length()), REPEAT_SEARCH_LIMIT);
        for(int offset = 1; offset <= count; offset++)
        {
            int index = (int)(next - offset) & mMask;
            Entry previous = mHistory.get(index);
            if(previous != null && !previous.resolved() &&
                ((previous.decision().outcome() == TunerAllocationDecision.Outcome.ALLOCATION_FAILED &&
                    (sourceStarted || previous.decision().reason() != TunerAllocationDecision.Reason.SOURCE_START_FAILED)) ||
                    previous.decision().outcome() == TunerAllocationDecision.Outcome.MOVED ||
                    previous.decision().outcome() == TunerAllocationDecision.Outcome.MOVED_CALL_FAILED) &&
                Objects.equals(previous.decision().context(), context) &&
                Objects.equals(previous.decision().requestedFrequencyHz(), frequencyHz))
                mHistory.compareAndSet(index, previous, new Entry(previous.position(), previous.decisionId(),
                    previous.sequence(), previous.decision(), previous.firstTimestamp(), previous.lastTimestamp(),
                    previous.repeatCount(), true));
        }
    }

    /** Final startup failure updates its recent move, preserving the recorded windows instead of adding a second row. */
    public void sourceStartFailed(TunerAllocationContext context, String tunerIdentity, long frequencyHz, int bandwidthHz)
    {
        if(!mAccepting.get()) return;
        long now = System.currentTimeMillis();
        long next = mNextPosition.get();
        int count = (int)Math.min(Math.min(next, mHistory.length()), REPEAT_SEARCH_LIMIT);
        for(int offset = 1; offset <= count; offset++)
        {
            int index = (int)(next - offset) & mMask;
            Entry previous = mHistory.get(index);
            if(previous != null && !previous.resolved() && now >= previous.lastTimestamp() &&
                now - previous.lastTimestamp() <= REPEAT_WINDOW_MS &&
                Objects.equals(previous.decision().context(), context) &&
                Objects.equals(previous.decision().tunerIdentity(), tunerIdentity) &&
                Objects.equals(previous.decision().requestedFrequencyHz(), frequencyHz))
            {
                TunerAllocationDecision movement = previous.decision();
                if(movement.outcome() == TunerAllocationDecision.Outcome.MOVED_CALL_FAILED) return;
                if(movement.outcome() != TunerAllocationDecision.Outcome.MOVED) continue;
                TunerAllocationDecision failed = new TunerAllocationDecision(movement.occurredAtMs(), movement.context(),
                    movement.tunerIdentity(), TunerAllocationDecision.Outcome.MOVED_CALL_FAILED,
                    TunerAllocationDecision.Reason.SOURCE_START_FAILED, movement.requestedFrequencyHz(),
                    movement.requestedBandwidthHz(), movement.beforeFrequencyHz(), movement.requestedCenterFrequencyHz(),
                    movement.afterFrequencyHz(), movement.usableBandwidthHz(), movement.middleUnusableHalfBandwidthHz(),
                    movement.controlFrequencyHz(), movement.coveredBefore(), movement.coveredAfter(), movement.candidates());
                mHistory.compareAndSet(index, previous, new Entry(previous.position(), previous.decisionId(),
                    mRevision.incrementAndGet(), failed, previous.firstTimestamp(), now, previous.repeatCount(), false));
                return;
            }
        }
        offer(new TunerAllocationDecision(now, context, tunerIdentity, TunerAllocationDecision.Outcome.ALLOCATION_FAILED,
            TunerAllocationDecision.Reason.SOURCE_START_FAILED, frequencyHz, (long)bandwidthHz,
            null, null, null, null, null, null, null, null, List.of()));
    }

    /** Copies at most the fixed ring size, exclusively on the requesting observer's thread. */
    public Snapshot snapshot()
    {
        long oldest = Math.max(0, mNextPosition.get() - mHistory.length());
        List<Entry> entries = new ArrayList<>(mHistory.length());
        for(int index = 0; index < mHistory.length(); index++)
        {
            Entry entry = mHistory.get(index);
            if(entry != null && entry.position() >= oldest) entries.add(entry);
        }
        entries.sort(Comparator.comparingLong(Entry::sequence).reversed());
        return new Snapshot(mSessionId, mRevision.get(), List.copyOf(entries));
    }

    @Override public void close() { mAccepting.set(false); }

    public String sessionId() { return mSessionId; }

    public record Entry(long position, long decisionId, long sequence, TunerAllocationDecision decision,
                        long firstTimestamp, long lastTimestamp, long repeatCount, boolean resolved) { }
    public record Snapshot(String sessionId, long cursor, List<Entry> decisions) { }
}
