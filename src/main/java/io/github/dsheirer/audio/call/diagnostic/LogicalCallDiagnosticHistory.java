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
 * Fixed session-only duplicate-decision ring. Appends use only bounded atomic writes and snapshots never make an
 * appender wait.
 */
final class LogicalCallDiagnosticHistory
{
    private final AtomicReferenceArray<LogicalCallDiagnosticDecision> mDuplicates;
    private final AtomicLongArray mPublishedPositions;
    private final AtomicLong mNextPosition = new AtomicLong();
    private final int mMask;

    LogicalCallDiagnosticHistory(int capacity)
    {
        if(capacity < 2 || Integer.bitCount(capacity) != 1)
        {
            throw new IllegalArgumentException("capacity must be a power of two greater than one");
        }

        mDuplicates = new AtomicReferenceArray<>(capacity);
        mPublishedPositions = new AtomicLongArray(capacity);
        mMask = capacity - 1;
    }

    void append(LogicalCallDiagnosticDecision decision)
    {
        long position = mNextPosition.getAndIncrement();
        int index = (int)position & mMask;
        mPublishedPositions.set(index, 0);
        mDuplicates.set(index, decision);
        mPublishedPositions.set(index, position + 1);
    }

    Snapshot snapshot()
    {
        long nextPosition = mNextPosition.get();
        long oldestPosition = Math.max(0, nextPosition - mDuplicates.length());
        List<LogicalCallDiagnosticDecision> duplicates = new ArrayList<>((int)(nextPosition - oldestPosition));

        for(long position = oldestPosition; position < nextPosition; position++)
        {
            int index = (int)position & mMask;
            long expectedPublication = position + 1;
            long firstPublication = mPublishedPositions.get(index);

            if(firstPublication == expectedPublication)
            {
                LogicalCallDiagnosticDecision decision = mDuplicates.get(index);

                if(decision != null && mPublishedPositions.get(index) == expectedPublication)
                {
                    duplicates.add(decision);
                }
            }
        }

        return new Snapshot(List.copyOf(duplicates), Math.max(0, nextPosition - mDuplicates.length()));
    }

    int capacity()
    {
        return mDuplicates.length();
    }

    record Snapshot(List<LogicalCallDiagnosticDecision> duplicates, long evictedDuplicates)
    {
    }
}
