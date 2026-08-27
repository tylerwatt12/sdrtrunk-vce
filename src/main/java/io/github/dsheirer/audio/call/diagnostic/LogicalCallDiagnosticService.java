/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import io.github.dsheirer.util.concurrent.BoundedMpscPairQueue;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

/**
 * Session-only logical-call diagnostic history and bounded rotating JSONL service.
 *
 * <p>The sink path performs only fixed atomic writes, a fixed-attempt bounded queue offer, and a worker wake-up.  The
 * single low-priority writer thread owns all serialization and filesystem work.  Diagnostics are dropped instead of
 * delaying the coordinator observer when the queue is saturated.</p>
 */
public final class LogicalCallDiagnosticService implements LogicalCallDiagnosticSink, AutoCloseable
{
    private static final long IDLE_PARK_NANOS = TimeUnit.MILLISECONDS.toNanos(25);
    private final LogicalCallDiagnosticConfiguration mConfiguration;
    private final String mSessionId = UUID.randomUUID().toString();
    private final long mSessionStartedAtEpochMillis = System.currentTimeMillis();
    private final LogicalCallDiagnosticHistory mHistory;
    private final BoundedMpscPairQueue<QueueRecordType,Object> mQueue;
    private final LogicalCallDiagnosticRecordEncoder mEncoder;
    private final AtomicBoolean mAccepting = new AtomicBoolean(true);
    private final AtomicBoolean mHandoffOpen = new AtomicBoolean(true);
    private final AtomicInteger mOffersInFlight = new AtomicInteger();
    private final AtomicLong mCloseDeadlineNanos = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong mDecisionsObserved = new AtomicLong();
    private final AtomicLong mOutputConfirmationsObserved = new AtomicLong();
    private final AtomicLong mRecordedConfirmationsObserved = new AtomicLong();
    private final AtomicLong mStreamSubmittedConfirmationsObserved = new AtomicLong();
    private final AtomicLong mRecordsEnqueued = new AtomicLong();
    private final AtomicLong mRecordsDroppedAtQueue = new AtomicLong();
    private final AtomicLong mRecordsRejectedAfterClose = new AtomicLong();
    private final AtomicLong mFileRecordsWritten = new AtomicLong();
    private final AtomicLong mFileRecordsDropped = new AtomicLong();
    private final AtomicLong mOversizedRecordsDropped = new AtomicLong();
    private final AtomicLong mFileWriteFailures = new AtomicLong();
    private final AtomicReference<LogicalCallDiagnosticFileState> mFileState =
        new AtomicReference<>(LogicalCallDiagnosticFileState.NOT_STARTED);
    private final AtomicBoolean mWriterTerminated = new AtomicBoolean();
    private final Thread mWriterThread;
    private volatile LogicalCallDiagnosticFileWriter mFileWriter;

    /**
     * Creates a service beneath the configured application log directory using production resource limits.
     */
    public LogicalCallDiagnosticService(Path applicationLogDirectory)
    {
        this(LogicalCallDiagnosticConfiguration.defaults(applicationLogDirectory));
    }

    /**
     * Creates a service with explicit fixed limits.  The configured directory is the service-owned diagnostic
     * directory, not its application-log parent.
     */
    public LogicalCallDiagnosticService(LogicalCallDiagnosticConfiguration configuration)
    {
        this(configuration, new LogicalCallDiagnosticJsonEncoder());
    }

    LogicalCallDiagnosticService(LogicalCallDiagnosticConfiguration configuration,
                                 LogicalCallDiagnosticRecordEncoder encoder)
    {
        mConfiguration = Objects.requireNonNull(configuration, "configuration cannot be null");
        mEncoder = Objects.requireNonNull(encoder, "encoder cannot be null");
        mHistory = new LogicalCallDiagnosticHistory(configuration.recentDecisionCapacity());
        mQueue = new BoundedMpscPairQueue<>(configuration.queueCapacity());
        mWriterThread = new ObserverThreadFactory("sdrtrunk logical-call diagnostic writer")
            .newThread(this::runWriter);
        mWriterThread.start();
    }

    /**
     * Creates a fallback service beneath the portable data log directory.  Normal application wiring should pass
     * its configured application log directory to {@link #LogicalCallDiagnosticService(Path)}.
     */
    public static LogicalCallDiagnosticService createPortableDefault()
    {
        return new LogicalCallDiagnosticService(LogicalCallDiagnosticConfiguration.portableDefaults());
    }

    /**
     * Offers one final resolver decision.  The recent UI ring is updated even when the optional file queue is full.
     *
     * @return true when the decision was accepted into the session diagnostic history; optional file-queue shedding
     * is reported separately by {@link #status()}
     */
    @Override
    public boolean offer(LogicalCallDiagnosticDecision decision)
    {
        Objects.requireNonNull(decision, "decision cannot be null");

        if(!beginOffer())
        {
            return false;
        }

        try
        {
            mDecisionsObserved.incrementAndGet();
            mHistory.append(decision);
            enqueue(QueueRecordType.DECISION, decision);
            return true;
        }
        finally
        {
            endOffer();
        }
    }

    /**
     * Offers a local downstream submission confirmation.  This does not claim provider acknowledgement.
     */
    @Override
    public boolean offerOutput(LogicalCallDiagnosticOutputEvent outputEvent)
    {
        Objects.requireNonNull(outputEvent, "outputEvent cannot be null");

        if(!beginOffer())
        {
            return false;
        }

        try
        {
            mOutputConfirmationsObserved.incrementAndGet();

            switch(outputEvent.outputType())
            {
                case RECORDED -> mRecordedConfirmationsObserved.incrementAndGet();
                case STREAM_SUBMITTED -> mStreamSubmittedConfirmationsObserved.incrementAndGet();
            }

            enqueue(QueueRecordType.OUTPUT_CONFIRMATION, outputEvent);
            return true;
        }
        finally
        {
            endOffer();
        }
    }

    /**
     * Immutable recent decision view for the UI.  File output events intentionally do not occupy this ring.
     */
    public LogicalCallDiagnosticServiceSnapshot snapshot()
    {
        LogicalCallDiagnosticHistory.Snapshot history = mHistory.snapshot();
        return new LogicalCallDiagnosticServiceSnapshot(mSessionId, mSessionStartedAtEpochMillis,
            history.evictedDecisions(), history.decisions(), status());
    }

    public LogicalCallDiagnosticStatus status()
    {
        LogicalCallDiagnosticFileWriter fileWriter = mFileWriter;
        long activeFileBytes = fileWriter != null ? fileWriter.activeFileBytes() : 0;
        int retainedFileCount = fileWriter != null ? fileWriter.retainedFileCount() : 0;
        return new LogicalCallDiagnosticStatus(mAccepting.get(), mWriterTerminated.get(), mQueue.size(),
            mQueue.capacity(), mDecisionsObserved.get(), mOutputConfirmationsObserved.get(),
            mRecordedConfirmationsObserved.get(), mStreamSubmittedConfirmationsObserved.get(),
            mRecordsEnqueued.get(), mRecordsDroppedAtQueue.get(), mRecordsRejectedAfterClose.get(),
            mFileRecordsWritten.get(), mFileRecordsDropped.get(), mOversizedRecordsDropped.get(),
            mFileWriteFailures.get(), mFileState.get(), activeFileBytes, retainedFileCount,
            mConfiguration.maximumFileBytes(), mConfiguration.maximumFiles());
    }

    /**
     * Returns the service-owned directory.  The path is never written into the diagnostic JSONL files.
     */
    public Path diagnosticDirectory()
    {
        return mConfiguration.directory();
    }

    private boolean beginOffer()
    {
        if(!mAccepting.get())
        {
            mRecordsRejectedAfterClose.incrementAndGet();
            return false;
        }

        mOffersInFlight.incrementAndGet();

        if(!mAccepting.get() || !mHandoffOpen.get())
        {
            mOffersInFlight.decrementAndGet();
            mRecordsRejectedAfterClose.incrementAndGet();
            return false;
        }

        return true;
    }

    private void endOffer()
    {
        mOffersInFlight.decrementAndGet();

        if(!mAccepting.get())
        {
            LockSupport.unpark(mWriterThread);
        }
    }

    private boolean enqueue(QueueRecordType type, Object record)
    {
        if(!mHandoffOpen.get())
        {
            mFileRecordsDropped.incrementAndGet();
            return false;
        }

        if(mQueue.offer(type, record))
        {
            mRecordsEnqueued.incrementAndGet();
            LockSupport.unpark(mWriterThread);
            return true;
        }

        mRecordsDroppedAtQueue.incrementAndGet();
        return false;
    }

    private void runWriter()
    {
        LogicalCallDiagnosticFileWriter fileWriter = new LogicalCallDiagnosticFileWriter(mConfiguration,
            segmentNumber -> mEncoder.encodeSessionHeader(mSessionId, mSessionStartedAtEpochMillis, segmentNumber,
                mConfiguration));
        mFileWriter = fileWriter;

        try
        {
            try
            {
                fileWriter.start();
                mFileState.set(LogicalCallDiagnosticFileState.ACTIVE);
            }
            catch(IOException | RuntimeException exception)
            {
                disableFileWriter(fileWriter);
            }

            while(true)
            {
                if(closeDeadlineReached())
                {
                    mHandoffOpen.set(false);
                    dropQueuedRecords();

                    if(mOffersInFlight.get() == 0)
                    {
                        break;
                    }

                    LockSupport.parkNanos(this, TimeUnit.MICROSECONDS.toNanos(100));
                    continue;
                }

                BoundedMpscPairQueue.Entry<QueueRecordType,Object> entry = mQueue.poll();

                if(entry != null)
                {
                    write(entry, fileWriter);
                    continue;
                }

                if(!mAccepting.get() && mOffersInFlight.get() == 0)
                {
                    break;
                }

                Thread.interrupted();
                LockSupport.parkNanos(this, IDLE_PARK_NANOS);
            }
        }
        finally
        {
            mHandoffOpen.set(false);
            dropQueuedRecords();

            try
            {
                fileWriter.close();
            }
            catch(IOException exception)
            {
                mFileWriteFailures.incrementAndGet();
            }

            mFileState.set(LogicalCallDiagnosticFileState.CLOSED);
            mWriterTerminated.set(true);
        }
    }

    private void write(BoundedMpscPairQueue.Entry<QueueRecordType,Object> entry,
                       LogicalCallDiagnosticFileWriter fileWriter)
    {
        if(mFileState.get() != LogicalCallDiagnosticFileState.ACTIVE)
        {
            mFileRecordsDropped.incrementAndGet();
            return;
        }

        try
        {
            byte[] json = switch(entry.first())
            {
                case DECISION -> mEncoder.encodeDecision((LogicalCallDiagnosticDecision)entry.second());
                case OUTPUT_CONFIRMATION -> mEncoder.encodeOutput((LogicalCallDiagnosticOutputEvent)entry.second());
            };

            if(closeDeadlineReached())
            {
                mFileRecordsDropped.incrementAndGet();
                return;
            }

            LogicalCallDiagnosticFileWriter.WriteResult result = fileWriter.write(json);

            if(result == LogicalCallDiagnosticFileWriter.WriteResult.WRITTEN)
            {
                mFileRecordsWritten.incrementAndGet();
            }
            else
            {
                mOversizedRecordsDropped.incrementAndGet();
                mFileRecordsDropped.incrementAndGet();
            }
        }
        catch(IOException | RuntimeException exception)
        {
            mFileRecordsDropped.incrementAndGet();
            disableFileWriter(fileWriter);
        }
    }

    private void disableFileWriter(LogicalCallDiagnosticFileWriter fileWriter)
    {
        mFileWriteFailures.incrementAndGet();
        mFileState.set(LogicalCallDiagnosticFileState.DISABLED);

        try
        {
            fileWriter.close();
        }
        catch(IOException exception)
        {
            // The original bounded failure counter already represents this disabled writer session.
        }
    }

    private boolean closeDeadlineReached()
    {
        long deadline = mCloseDeadlineNanos.get();
        return deadline != Long.MAX_VALUE && System.nanoTime() - deadline >= 0;
    }

    private void dropQueuedRecords()
    {
        long dropped = 0;

        while(mQueue.poll() != null)
        {
            dropped++;
        }

        if(dropped > 0)
        {
            mFileRecordsDropped.addAndGet(dropped);
        }
    }

    /**
     * Stops intake and drains accepted records only until the configured close deadline.  The method returns by that
     * deadline even if the local filesystem is slow or stuck; the writer is a daemon thread.
     */
    @Override
    public void close()
    {
        long now = System.nanoTime();
        long requestedDeadline = now + mConfiguration.closeTimeout().toNanos();

        if(mAccepting.compareAndSet(true, false))
        {
            mCloseDeadlineNanos.compareAndSet(Long.MAX_VALUE, requestedDeadline);
        }

        LockSupport.unpark(mWriterThread);

        if(Thread.currentThread() == mWriterThread || mWriterTerminated.get())
        {
            return;
        }

        long deadline = mCloseDeadlineNanos.get();
        long remainingNanos = deadline - System.nanoTime();

        if(remainingNanos <= 0)
        {
            mWriterThread.interrupt();
            return;
        }

        try
        {
            TimeUnit.NANOSECONDS.timedJoin(mWriterThread, remainingNanos);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            return;
        }

        if(!mWriterTerminated.get())
        {
            mHandoffOpen.set(false);
            mWriterThread.interrupt();
        }
    }

    private enum QueueRecordType
    {
        DECISION,
        OUTPUT_CONFIRMATION
    }
}
