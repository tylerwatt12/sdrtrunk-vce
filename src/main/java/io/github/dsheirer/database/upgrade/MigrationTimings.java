/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Monotonic, value-free phase timings. Reporting cannot change the outcome of a database operation. */
final class MigrationTimings
{
    static final MigrationTimings NONE = new MigrationTimings(ignored -> { });
    private final Consumer<String> mSink;
    private final LongSupplier mClock;

    MigrationTimings(Consumer<String> sink)
    {
        this(sink, System::nanoTime);
    }

    MigrationTimings(Consumer<String> sink, LongSupplier clock)
    {
        mSink = Objects.requireNonNull(sink);
        mClock = Objects.requireNonNull(clock);
    }

    Phase phase(String label)
    {
        return new Phase(Objects.requireNonNull(label));
    }

    final class Phase implements AutoCloseable
    {
        private final String mLabel;
        private final long mStarted = mClock.getAsLong();
        private boolean mComplete;
        private boolean mClosed;

        private Phase(String label)
        {
            mLabel = label;
        }

        void complete()
        {
            mComplete = true;
        }

        @Override
        public void close()
        {
            if(mClosed) return;
            mClosed = true;
            long milliseconds = Math.max(0, (mClock.getAsLong() - mStarted) / 1_000_000);
            try
            {
                mSink.accept("TIMING: " + mLabel + " — " + milliseconds + " ms (" +
                    (mComplete ? "completed" : "failed") + ")");
            }
            catch(RuntimeException | Error ignored)
            {
                //A report observer cannot undo a committed transaction or mask the original failure.
            }
        }
    }
}
