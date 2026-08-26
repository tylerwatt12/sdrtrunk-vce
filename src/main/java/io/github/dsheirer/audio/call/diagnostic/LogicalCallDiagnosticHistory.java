/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Fixed session-only decision ring.  Appends use only bounded atomic writes and snapshots never make an appender wait.
 */
final class LogicalCallDiagnosticHistory
{
    private final AtomicReferenceArray<LogicalCallDiagnosticDecision> mDecisions;
    private final AtomicLongArray mPublishedPositions;
    private final AtomicLong mNextPosition = new AtomicLong();
    private final int mMask;

    LogicalCallDiagnosticHistory(int capacity)
    {
        if(capacity < 2 || Integer.bitCount(capacity) != 1)
        {
            throw new IllegalArgumentException("capacity must be a power of two greater than one");
        }

        mDecisions = new AtomicReferenceArray<>(capacity);
        mPublishedPositions = new AtomicLongArray(capacity);
        mMask = capacity - 1;
    }

    void append(LogicalCallDiagnosticDecision decision)
    {
        long position = mNextPosition.getAndIncrement();
        int index = (int)position & mMask;
        mPublishedPositions.set(index, 0);
        mDecisions.set(index, decision);
        mPublishedPositions.set(index, position + 1);
    }

    Snapshot snapshot()
    {
        long nextPosition = mNextPosition.get();
        long oldestPosition = Math.max(0, nextPosition - mDecisions.length());
        List<LogicalCallDiagnosticDecision> decisions = new ArrayList<>((int)(nextPosition - oldestPosition));

        for(long position = oldestPosition; position < nextPosition; position++)
        {
            int index = (int)position & mMask;
            long expectedPublication = position + 1;
            long firstPublication = mPublishedPositions.get(index);

            if(firstPublication == expectedPublication)
            {
                LogicalCallDiagnosticDecision decision = mDecisions.get(index);

                if(decision != null && mPublishedPositions.get(index) == expectedPublication)
                {
                    decisions.add(decision);
                }
            }
        }

        return new Snapshot(List.copyOf(decisions), Math.max(0, nextPosition - mDecisions.length()));
    }

    int capacity()
    {
        return mDecisions.length();
    }

    record Snapshot(List<LogicalCallDiagnosticDecision> decisions, long evictedDecisions)
    {
    }
}
