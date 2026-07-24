/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.record;

import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.database.SdrTrunkDatabase;
import java.io.IOException;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Savepoint;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Bounded, single-writer recorded-call catalog.
 *
 * <p>Recording and decoder threads only perform a non-blocking queue offer. File validation, SQLite writes, and
 * retention cleanup run on the catalog worker. Cleanup removes a bounded batch every pass, including audio files,
 * call rows, and directory buckets no longer referenced by a retained call.</p>
 */
public final class RecordedCallCatalogService implements AutoCloseable, RecordedCallCatalogHandoff
{
    public static final int MINIMUM_RETENTION_DAYS = 1;
    public static final int MAXIMUM_RETENTION_DAYS = 3_650;
    public static final int MAXIMUM_BATCH_SIZE = RecordedCallCatalogStore.MAXIMUM_BATCH_SIZE;
    public static final int DEFAULT_QUEUE_CAPACITY = 2_048;
    public static final int DEFAULT_RETENTION_BATCH_SIZE = 250;
    public static final long DEFAULT_MAXIMUM_RETAINED_BYTES = 2_000L * 1024 * 1024;
    public static final long MAXIMUM_RETAINED_BYTES = 16L * 1024 * 1024 * 1024 * 1024;
    private static final Logger mLog = LoggerFactory.getLogger(RecordedCallCatalogService.class);
    private static final int MAXIMUM_QUEUE_CAPACITY = 16_384;
    private static final int WRITE_BATCH_SIZE = 100;
    private static final long POLL_MILLISECONDS = 500;
    private static final long CLEANUP_INTERVAL_MILLISECONDS = TimeUnit.SECONDS.toMillis(30);

    private final Path mDatabasePath;
    private final Path mConfiguredRecordingRoot;
    private final Path mRecordingRoot;
    private final RecordedCallCatalogStore mStore;
    private final ArrayBlockingQueue<RecordedCallArtifact> mQueue;
    private final ArrayBlockingQueue<Path> mRecoveryQueue;
    private final LongSupplier mCurrentTime;
    private final long mCleanupIntervalMs;
    private final int mRetentionBatchSize;
    private final AtomicBoolean mRunning = new AtomicBoolean();
    private final AtomicBoolean mAccepting = new AtomicBoolean();
    private final AtomicBoolean mCleanupRequested = new AtomicBoolean();
    private final AtomicBoolean mCleanupRestartRequested = new AtomicBoolean();
    private final AtomicLong mAccepted = new AtomicLong();
    private final AtomicLong mRejected = new AtomicLong();
    private final AtomicLong mDropped = new AtomicLong();
    private final AtomicLong mInserted = new AtomicLong();
    private final AtomicLong mDuplicate = new AtomicLong();
    private final AtomicLong mInvalid = new AtomicLong();
    private final AtomicLong mWriteFailures = new AtomicLong();
    private final AtomicLong mRetentionRowsDeleted = new AtomicLong();
    private final AtomicLong mRetainedBytes = new AtomicLong();
    private volatile int mRetentionDays;
    private volatile long mMaximumRetainedBytes;
    private volatile State mState = State.STOPPED;
    private volatile String mLastError;
    private volatile long mLastSuccessfulWriteMs;
    private volatile long mLastCleanupMs;
    private RecordedCallCatalogStore.RetentionCursor mCleanupCursor;
    private ExecutorService mExecutor;

    public RecordedCallCatalogService(Path databasePath, Path recordingRoot, int retentionDays)
    {
        this(databasePath, recordingRoot, retentionDays, DEFAULT_MAXIMUM_RETAINED_BYTES);
    }

    public RecordedCallCatalogService(Path databasePath, Path recordingRoot, int retentionDays,
                                      long maximumRetainedBytes)
    {
        this(databasePath, recordingRoot, retentionDays, maximumRetainedBytes, DEFAULT_QUEUE_CAPACITY,
            System::currentTimeMillis, CLEANUP_INTERVAL_MILLISECONDS, DEFAULT_RETENTION_BATCH_SIZE);
    }

    RecordedCallCatalogService(Path databasePath, Path recordingRoot, int retentionDays, int queueCapacity,
                               LongSupplier currentTime, long cleanupIntervalMs, int retentionBatchSize)
    {
        this(databasePath, recordingRoot, retentionDays, DEFAULT_MAXIMUM_RETAINED_BYTES, queueCapacity,
            currentTime, cleanupIntervalMs, retentionBatchSize);
    }

    RecordedCallCatalogService(Path databasePath, Path recordingRoot, int retentionDays,
                               long maximumRetainedBytes, int queueCapacity, LongSupplier currentTime,
                               long cleanupIntervalMs, int retentionBatchSize)
    {
        mDatabasePath = Objects.requireNonNull(databasePath, "Database path cannot be null")
            .toAbsolutePath().normalize();
        mConfiguredRecordingRoot = Objects.requireNonNull(recordingRoot, "Recording root cannot be null")
            .toAbsolutePath().normalize();
        mRecordingRoot = ManagedRecordingPath.prepareRoot(mConfiguredRecordingRoot);

        if(queueCapacity < 1 || queueCapacity > MAXIMUM_QUEUE_CAPACITY || cleanupIntervalMs < 1 ||
            retentionBatchSize < 1 || retentionBatchSize > 10_000)
        {
            throw new IllegalArgumentException("Recorded-call catalog worker bounds are invalid");
        }

        mQueue = new ArrayBlockingQueue<>(queueCapacity);
        mRecoveryQueue = new ArrayBlockingQueue<>(Math.min(queueCapacity, 1_024));
        mStore = new RecordedCallCatalogStore(mRecordingRoot);
        mCurrentTime = Objects.requireNonNull(currentTime, "Clock cannot be null");
        mCleanupIntervalMs = cleanupIntervalMs;
        mRetentionBatchSize = retentionBatchSize;
        setRetentionDays(retentionDays);
        setMaximumRetainedBytes(maximumRetainedBytes);
    }

    public void start()
    {
        if(mRunning.compareAndSet(false, true))
        {
            mState = State.STARTING;
            mLastError = null;
            mCleanupCursor = null;
            mCleanupRestartRequested.set(false);
            //Accept bounded handoffs before the worker validates SQLite so the recording startup scan cannot race
            //catalog initialization and silently lose discovered or newly completed recordings.
            mAccepting.set(true);

            try
            {
                mExecutor = Executors.newSingleThreadExecutor(new NamingThreadFactory("recorded call catalog"));
                mExecutor.execute(this::run);
            }
            catch(RuntimeException exception)
            {
                mAccepting.set(false);
                mRunning.set(false);
                mState = State.FAILED;
                mLastError = conciseError(exception);

                if(mExecutor != null)
                {
                    mExecutor.shutdownNow();
                    mExecutor = null;
                }
            }
        }
    }

    /**
     * Non-blocking handoff from the successful recording-publication path.
     *
     * @return true only when the artifact entered the bounded queue
     */
    @Override
    public boolean submit(RecordedCallArtifact artifact)
    {
        if(artifact == null)
        {
            mRejected.incrementAndGet();
            return false;
        }

        if(!mAccepting.get())
        {
            mDropped.incrementAndGet();
            return false;
        }

        if(mQueue.offer(artifact))
        {
            mAccepted.incrementAndGet();
            return true;
        }

        mDropped.incrementAndGet();
        return false;
    }

    /**
     * Non-blocking handoff for a bounded filesystem reconciler that discovers a completed but uncataloged audio file.
     */
    @Override
    public boolean submitRecovery(Path path)
    {
        if(path == null || !mAccepting.get())
        {
            return false;
        }

        Path normalized = path.toAbsolutePath().normalize();
        return (normalized.startsWith(mRecordingRoot) || normalized.startsWith(mConfiguredRecordingRoot)) &&
            mRecoveryQueue.offer(normalized);
    }

    @Override
    public boolean isAccepting()
    {
        return mAccepting.get();
    }

    public void setRetentionDays(int retentionDays)
    {
        if(retentionDays < MINIMUM_RETENTION_DAYS || retentionDays > MAXIMUM_RETENTION_DAYS)
        {
            throw new IllegalArgumentException("Recorded-call retention must be between " +
                MINIMUM_RETENTION_DAYS + " and " + MAXIMUM_RETENTION_DAYS + " days");
        }

        int previous = mRetentionDays;
        mRetentionDays = retentionDays;

        if(previous > 0 && retentionDays < previous)
        {
            mCleanupRestartRequested.set(true);
            mCleanupRequested.set(true);
        }
    }

    public void setMaximumRetainedBytes(long maximumRetainedBytes)
    {
        if(maximumRetainedBytes < 1 || maximumRetainedBytes > MAXIMUM_RETAINED_BYTES)
        {
            throw new IllegalArgumentException("Recorded-call maximum retained bytes must be between 1 and " +
                MAXIMUM_RETAINED_BYTES);
        }

        long previous = mMaximumRetainedBytes;
        mMaximumRetainedBytes = maximumRetainedBytes;

        if(previous > 0 && maximumRetainedBytes < previous)
        {
            mCleanupRestartRequested.set(true);
            mCleanupRequested.set(true);
        }
    }

    /**
     * Runs one bounded, read-only website query on its own SQLite connection.
     */
    public RecordedCallCatalogPage search(RecordedCallCatalogSearch search) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            RecordedCallCatalogSchema.validate(connection);
            return mStore.search(connection, search);
        }
    }

    /**
     * Runs one bounded oldest-first website query after an optional composite primary-key cursor.
     */
    public RecordedCallCatalogPage searchForward(RecordedCallCatalogSearch search,
                                                 RecordedCallCatalogSearch.Cursor after)
        throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            RecordedCallCatalogSchema.validate(connection);
            return mStore.searchForward(connection, search, after);
        }
    }

    /**
     * Resolves at most {@link #MAXIMUM_BATCH_SIZE} opaque public call IDs in one SQLite query. Each returned list
     * position corresponds to the same request position. Missing, expired, and non-record-eligible calls are all
     * represented by an empty optional, and managed filesystem paths are never returned.
     */
    public List<Optional<RecordedCallCatalogMetadata>> resolveCalls(List<String> publicCallIds)
        throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            RecordedCallCatalogSchema.validate(connection);
            return mStore.resolveCalls(connection, publicCallIds);
        }
    }

    /**
     * Lists one bounded page of browsing values. The caller uses the returned value key as both the next-page cursor
     * and a call-search filter.
     */
    public List<RecordedCallIdentity> listIdentities(RecordedCallIdentityKind kind, String scopeKey,
                                                     String afterValueKey, int pageSize)
        throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            RecordedCallCatalogSchema.validate(connection);
            return mStore.listIdentities(connection, kind, scopeKey, afterValueKey, pageSize);
        }
    }

    /**
     * Resolves one public recorded-call ID to a currently valid managed file. Non-record-eligible catalog rows are
     * deliberately indistinguishable from missing rows.
     */
    public Optional<Path> resolveMedia(String publicCallId) throws IOException, SQLException
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            RecordedCallCatalogSchema.validate(connection);
            return mStore.resolveMedia(connection, publicCallId);
        }
    }

    /**
     * Resolves and safely opens one eligible retained call without exposing its managed path to the caller.
     */
    public Optional<OpenedMedia> openMedia(String publicCallId) throws IOException, SQLException
    {
        RecordedCallCatalogStore.ResolvedMedia media;

        try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
        {
            RecordedCallCatalogSchema.validate(connection);
            Optional<RecordedCallCatalogStore.ResolvedMedia> resolved =
                mStore.resolveMediaDescriptor(connection, publicCallId);

            if(resolved.isEmpty())
            {
                return Optional.empty();
            }

            media = resolved.get();
        }

        Optional<SeekableByteChannel> channel = ManagedRecordingPath.openReadOnly(mRecordingRoot,
            media.path(), media.byteSize(), media.format());
        return channel.map(value -> new OpenedMedia(value, media.byteSize(), media.format()));
    }

    public Status status()
    {
        return new Status(mState, mRetentionDays, mMaximumRetainedBytes, mRetainedBytes.get(),
            mQueue.size(), mRecoveryQueue.size(),
            mQueue.remainingCapacity() + mQueue.size(),
            mAccepted.get(), mRejected.get(), mDropped.get(), mInserted.get(), mDuplicate.get(), mInvalid.get(),
            mWriteFailures.get(), mRetentionRowsDeleted.get(), mLastSuccessfulWriteMs, mLastCleanupMs, mLastError);
    }

    public static final class OpenedMedia implements AutoCloseable
    {
        private final SeekableByteChannel mChannel;
        private final long mLength;
        private final RecordFormat mFormat;

        private OpenedMedia(SeekableByteChannel channel, long length, RecordFormat format)
        {
            mChannel = Objects.requireNonNull(channel, "Recorded-call media channel cannot be null");
            mLength = length;
            mFormat = Objects.requireNonNull(format, "Recorded-call media format cannot be null");
        }

        public SeekableByteChannel channel()
        {
            return mChannel;
        }

        public long length()
        {
            return mLength;
        }

        public RecordFormat format()
        {
            return mFormat;
        }

        @Override
        public void close() throws IOException
        {
            mChannel.close();
        }
    }

    @Override
    public void close()
    {
        mAccepting.set(false);
        mRunning.set(false);

        if(mExecutor != null)
        {
            mExecutor.shutdown();

            try
            {
                if(!mExecutor.awaitTermination(10, TimeUnit.SECONDS))
                {
                    mExecutor.shutdownNow();
                }
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                mExecutor.shutdownNow();
            }
            finally
            {
                mExecutor = null;
            }
        }

        if(mState != State.FAILED)
        {
            mState = State.STOPPED;
        }
    }

    private void run()
    {
        try
        {
            Thread.currentThread().setPriority(Thread.MIN_PRIORITY);
            Files.createDirectories(mRecordingRoot);

            try(Connection connection = SdrTrunkDatabase.open(mDatabasePath))
            {
                RecordedCallCatalogSchema.validate(connection);
                mRetainedBytes.set(mStore.totalRetainedBytes(connection));
                cleanup(connection);
                mState = State.RUNNING;
                List<RecordedCallArtifact> batch = new ArrayList<>(WRITE_BATCH_SIZE);

                while(mRunning.get() || !mQueue.isEmpty() || !mRecoveryQueue.isEmpty())
                {
                    RecordedCallArtifact artifact = mQueue.poll(POLL_MILLISECONDS, TimeUnit.MILLISECONDS);

                    if(artifact != null)
                    {
                        batch.add(artifact);
                        mQueue.drainTo(batch, WRITE_BATCH_SIZE - 1);
                        writeBatch(connection, batch);
                        batch.clear();
                    }

                    if(!mRecoveryQueue.isEmpty())
                    {
                        List<Path> recovered = new ArrayList<>(WRITE_BATCH_SIZE);
                        mRecoveryQueue.drainTo(recovered, WRITE_BATCH_SIZE);
                        writeRecoveredBatch(connection, recovered);
                    }

                    long now = mCurrentTime.getAsLong();

                    if(mCleanupRequested.getAndSet(false) || now - mLastCleanupMs >= mCleanupIntervalMs)
                    {
                        cleanup(connection);
                    }
                }
            }
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();

            if(mRunning.get())
            {
                fail(exception);
            }
        }
        catch(IOException | SQLException | RuntimeException exception)
        {
            fail(exception);
            mLog.warn("Recorded-call catalog worker stopped", exception);
        }
        finally
        {
            mAccepting.set(false);
            mRunning.set(false);

            if(mState != State.FAILED)
            {
                mState = State.STOPPED;
            }
        }
    }

    private void writeBatch(Connection connection, List<RecordedCallArtifact> artifacts) throws SQLException
    {
        List<RecordedCallCatalogStore.PreparedAdmission> prepared = new ArrayList<>(artifacts.size());

        for(RecordedCallArtifact artifact: artifacts)
        {
            RecordedCallCatalogStore.PreparedAdmission admission;

            try
            {
                admission = mStore.prepare(artifact);
            }
            catch(IOException | RuntimeException exception)
            {
                mInvalid.incrementAndGet();
                mLastError = conciseError(exception);
                throw new IllegalStateException("New managed recording could not enter retention ownership",
                    exception);
            }

            if(admission.result() == RecordedCallCatalogStore.AdmissionResult.INVALID_ARTIFACT)
            {
                mInvalid.incrementAndGet();
                throw new IllegalStateException("New managed recording failed retention ownership validation");
            }

            prepared.add(admission);
        }

        writePrepared(connection, prepared);
    }

    private void writeRecoveredBatch(Connection connection, List<Path> paths) throws SQLException
    {
        List<RecordedCallCatalogStore.PreparedAdmission> prepared = new ArrayList<>(paths.size());

        for(Path path: paths)
        {
            try
            {
                RecordedCallCatalogStore.PreparedAdmission admission = mStore.prepareRecovered(path);

                if(admission.result() == null)
                {
                    prepared.add(admission);
                }
                else
                {
                    mInvalid.incrementAndGet();
                }
            }
            catch(IOException | RuntimeException exception)
            {
                mInvalid.incrementAndGet();
                mLastError = conciseError(exception);
            }
        }

        writePrepared(connection, prepared);
    }

    private void writePrepared(Connection connection,
                               List<RecordedCallCatalogStore.PreparedAdmission> prepared) throws SQLException
    {
        if(prepared.isEmpty())
        {
            return;
        }

        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        long inserted = 0;
        long duplicates = 0;
        long insertedBytes = 0;
        SQLException sqlFailure = null;
        RuntimeException runtimeFailure = null;

        try
        {
            for(RecordedCallCatalogStore.PreparedAdmission admission: prepared)
            {
                Savepoint savepoint = connection.setSavepoint();

                try
                {
                    RecordedCallCatalogStore.AdmissionResult result = mStore.admit(connection, admission);

                    if(result == RecordedCallCatalogStore.AdmissionResult.INSERTED)
                    {
                        inserted++;
                        insertedBytes = Math.addExact(insertedBytes, admission.artifact().byteSize());
                    }
                    else if(result == RecordedCallCatalogStore.AdmissionResult.DUPLICATE)
                    {
                        duplicates++;
                    }

                    connection.releaseSavepoint(savepoint);
                }
                catch(SQLException exception)
                {
                    connection.rollback(savepoint);
                    mWriteFailures.incrementAndGet();
                    mLastError = conciseError(exception);
                    sqlFailure = exception;
                    break;
                }
                catch(RuntimeException exception)
                {
                    connection.rollback(savepoint);
                    mWriteFailures.incrementAndGet();
                    mLastError = conciseError(exception);
                    runtimeFailure = exception;
                    break;
                }
            }

            connection.commit();
            mInserted.addAndGet(inserted);
            mDuplicate.addAndGet(duplicates);
            long retainedBytes = mRetainedBytes.addAndGet(insertedBytes);

            if(retainedBytes > mMaximumRetainedBytes)
            {
                mCleanupRequested.set(true);
            }

            if(inserted > 0 || duplicates > 0)
            {
                mLastSuccessfulWriteMs = mCurrentTime.getAsLong();
            }

            if(sqlFailure != null)
            {
                throw sqlFailure;
            }

            if(runtimeFailure != null)
            {
                throw runtimeFailure;
            }
        }
        catch(SQLException exception)
        {
            connection.rollback();
            throw exception;
        }
        catch(RuntimeException exception)
        {
            connection.rollback();
            throw exception;
        }
        finally
        {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private void cleanup(Connection connection) throws SQLException
    {
        long now = mCurrentTime.getAsLong();
        long retentionMs = TimeUnit.DAYS.toMillis(mRetentionDays);
        long cutoff = Math.max(1, now - retentionMs);

        if(mCleanupRestartRequested.getAndSet(false))
        {
            mCleanupCursor = null;
        }

        RecordedCallCatalogStore.RetentionResult result =
            mStore.cleanupRetention(connection, cutoff, mRetainedBytes.get(), mMaximumRetainedBytes,
                mRetentionBatchSize, mCleanupCursor);
        mRetentionRowsDeleted.addAndGet(result.rowsDeleted());
        mRetainedBytes.updateAndGet(current -> Math.max(0, current - result.bytesRemoved()));
        mLastCleanupMs = now;
        mCleanupCursor = result.nextCursor();

        if(result.moreWork() && result.rowsDeleted() > 0)
        {
            //Catch up through repeated bounded passes without turning one maintenance action into an unbounded sweep.
            //When a full page contains only unsafe ownership rows, retain the cursor but wait for the normal
            //maintenance interval before continuing past that page instead of spinning continuously.
            mCleanupRequested.set(true);
        }

        if(result.fileFailures() > 0)
        {
            mLastError = "Unable to delete " + result.fileFailures() + " expired recording file(s)";
        }
    }

    private void fail(Exception exception)
    {
        mState = State.FAILED;
        mLastError = conciseError(exception);
    }

    private static String conciseError(Exception exception)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? exception.getClass().getSimpleName() : message;
    }

    public enum State
    {
        STOPPED,
        STARTING,
        RUNNING,
        FAILED
    }

    public record Status(State state, int retentionDays, long maximumRetainedBytes, long retainedBytes,
                         int queued, int recoveryQueued, int queueCapacity,
                         long accepted,
                         long rejected, long dropped, long inserted, long duplicates, long invalid,
                         long writeFailures, long retentionRowsDeleted, long lastSuccessfulWriteMs,
                         long lastCleanupMs, String lastError)
    {
    }
}
