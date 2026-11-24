package io.github.dsheirer.source.tuner.manager;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Small, process-local recovery bookkeeping, confined to the serialized USB lifecycle worker. */
final class UsbRecoveryMonitor
{
    private static final long SAMPLE_STALL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long SCHEDULER_PAUSE_NANOS = TimeUnit.SECONDS.toNanos(15);
    private final LongSupplier mClock;
    private final Map<String,Progress> mProgress = new HashMap<>();
    private final Map<String,Retry> mRetries = new HashMap<>();

    UsbRecoveryMonitor() { this(System::nanoTime); }

    UsbRecoveryMonitor(LongSupplier clock) { mClock = clock; }

    boolean samplesStalled(String identity, Object runtime, long stream, boolean receiving, long usableBytes)
    {
        if(!receiving)
        {
            mProgress.remove(identity);
            return false;
        }
        long now = mClock.getAsLong();
        Progress previous = mProgress.get(identity);
        // A new stream, useful data, or the first check after sleep gets its own grace period. The time the computer
        // spent asleep is not itself evidence that an otherwise healthy radio must be restarted.
        if(previous == null || previous.runtime != runtime || previous.stream != stream ||
            previous.bytes != usableBytes || now - previous.checkedAt > SCHEDULER_PAUSE_NANOS ||
            now - previous.checkedAt < 0)
        {
            mProgress.put(identity, new Progress(runtime, stream, usableBytes, now, now));
            return false;
        }
        mProgress.put(identity, new Progress(runtime, stream, usableBytes, previous.progressAt, now));
        return now - previous.progressAt >= SAMPLE_STALL_NANOS;
    }

    boolean mayRetry(String identity, long operatorGeneration, boolean explicit, boolean newAttachment)
    {
        Retry retry = mRetries.get(identity);
        return explicit || newAttachment || retry == null || retry.operatorGeneration != operatorGeneration ||
            mClock.getAsLong() - retry.at >= 0;
    }

    void failed(String identity, long operatorGeneration)
    {
        Retry previous = mRetries.get(identity);
        int failures = previous == null ? 1 : Math.min(previous.failures + 1, 5);
        long seconds = Math.min(30, 2L << (failures - 1));
        mRetries.put(identity, new Retry(operatorGeneration, failures,
            mClock.getAsLong() + TimeUnit.SECONDS.toNanos(seconds)));
        mProgress.remove(identity);
    }

    void recovered(String identity)
    {
        mRetries.remove(identity);
    }

    void forget(String identity)
    {
        mRetries.remove(identity);
        mProgress.remove(identity);
    }

    private record Progress(Object runtime, long stream, long bytes, long progressAt, long checkedAt) {}
    private record Retry(long operatorGeneration, int failures, long at) {}
}
