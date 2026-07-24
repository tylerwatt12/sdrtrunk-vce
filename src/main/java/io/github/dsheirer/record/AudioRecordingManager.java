/*
 * *****************************************************************************
 * Copyright (C) 2014-2024 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.record;

import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.util.ThreadPool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Records completed immutable audio calls that have been flagged as recordable.
 */
public class AudioRecordingManager
{
    private static final Logger mLog = LoggerFactory.getLogger(AudioRecordingManager.class);
    private static final int MAXIMUM_QUEUED_CALLS = 128;
    private static final long MAXIMUM_SOURCE_BYTES_PER_CALL = 64L * 1024L * 1024L;
    private static final long MAXIMUM_QUEUED_SOURCE_BYTES = 256L * 1024L * 1024L;
    private static final int RECONCILIATION_BATCH_ENTRIES = 128;
    private static final int MAXIMUM_PENDING_CATALOG_RECOVERIES =
        RECONCILIATION_BATCH_ENTRIES + MAXIMUM_QUEUED_CALLS;
    private static final long RECONCILIATION_INTERVAL_SECONDS = 30;
    private static final Duration STALE_WORK_AGE = Duration.ofHours(1);
    private final ArrayBlockingQueue<QueuedCall> mCompletedAudioCallQueue =
        new ArrayBlockingQueue<>(MAXIMUM_QUEUED_CALLS);
    private final ArrayBlockingQueue<Path> mPendingCatalogRecoveries =
        new ArrayBlockingQueue<>(MAXIMUM_PENDING_CATALOG_RECOVERIES);
    private final AtomicLong mQueuedSourceBytes = new AtomicLong();
    private final AtomicLong mDroppedRecordings = new AtomicLong();
    private final AtomicLong mCatalogPausedRecordings = new AtomicLong();
    private final AtomicBoolean mCatalogBackpressured = new AtomicBoolean();
    private final ReentrantLock mProcessingLock = new ReentrantLock();
    private final ReentrantLock mHandoffLock = new ReentrantLock();
    private final ReentrantLock mReconciliationLock = new ReentrantLock();
    private final Set<Path> mActiveRecordingPaths = ConcurrentHashMap.newKeySet();
    private volatile boolean mAcceptingCalls;
    private ScheduledFuture<?> mQueueProcessorHandle;
    private ScheduledFuture<?> mReconciliationHandle;
    private ManagedRecordingReconciler mReconciler;
    private Path mReconciliationRoot;
    private volatile Path mRecordingBasePath;
    private final UserPreferences mUserPreferences;
    private final ScheduledExecutorService mScheduler;
    private final Consumer<CompletedAudioCall> mRecordedCallConsumer;
    private final Consumer<RecordedCallArtifact> mRecordedArtifactConsumer;
    private final Consumer<Path> mRecoveredArtifactConsumer;
    private final RecordedCallCatalogHandoff mCatalogHandoff;

    /**
     * Constructs an instance
     * @param userPreferences to determine audio recording format
     */
    public AudioRecordingManager(UserPreferences userPreferences)
    {
        this(userPreferences, null, null, null, null, ThreadPool.SCHEDULED);
    }

    public AudioRecordingManager(UserPreferences userPreferences, Consumer<CompletedAudioCall> recordedCallConsumer)
    {
        this(userPreferences, recordedCallConsumer, null, null, null, ThreadPool.SCHEDULED);
    }

    public AudioRecordingManager(UserPreferences userPreferences, Consumer<CompletedAudioCall> recordedCallConsumer,
                                 Consumer<RecordedCallArtifact> recordedArtifactConsumer)
    {
        this(userPreferences, recordedCallConsumer, recordedArtifactConsumer, null, null, ThreadPool.SCHEDULED);
    }

    /**
     * Constructs a recording manager with non-blocking handoffs for newly completed and startup-recovered artifacts.
     */
    public AudioRecordingManager(UserPreferences userPreferences, Consumer<CompletedAudioCall> recordedCallConsumer,
                                 Consumer<RecordedCallArtifact> recordedArtifactConsumer,
                                 Consumer<Path> recoveredArtifactConsumer)
    {
        this(userPreferences, recordedCallConsumer, recordedArtifactConsumer, recoveredArtifactConsumer, null,
            ThreadPool.SCHEDULED);
    }

    /**
     * Creates a manager whose new and recovered recordings must be accepted by the bounded retention catalog.
     */
    public static AudioRecordingManager withCatalogHandoff(UserPreferences userPreferences,
                                                            Consumer<CompletedAudioCall> recordedCallConsumer,
                                                            RecordedCallCatalogHandoff catalogHandoff)
    {
        return new AudioRecordingManager(userPreferences, recordedCallConsumer, null, null,
            Objects.requireNonNull(catalogHandoff, "Catalog handoff cannot be null"), ThreadPool.SCHEDULED);
    }

    AudioRecordingManager(UserPreferences userPreferences, Consumer<CompletedAudioCall> recordedCallConsumer,
                          Consumer<RecordedCallArtifact> recordedArtifactConsumer,
                          ScheduledExecutorService scheduler)
    {
        this(userPreferences, recordedCallConsumer, recordedArtifactConsumer, null, null, scheduler);
    }

    AudioRecordingManager(UserPreferences userPreferences, Consumer<CompletedAudioCall> recordedCallConsumer,
                          Consumer<RecordedCallArtifact> recordedArtifactConsumer,
                          Consumer<Path> recoveredArtifactConsumer, ScheduledExecutorService scheduler)
    {
        this(userPreferences, recordedCallConsumer, recordedArtifactConsumer, recoveredArtifactConsumer, null,
            scheduler);
    }

    static AudioRecordingManager withCatalogHandoff(UserPreferences userPreferences,
                                                     Consumer<CompletedAudioCall> recordedCallConsumer,
                                                     RecordedCallCatalogHandoff catalogHandoff,
                                                     ScheduledExecutorService scheduler)
    {
        return new AudioRecordingManager(userPreferences, recordedCallConsumer, null, null,
            Objects.requireNonNull(catalogHandoff, "Catalog handoff cannot be null"), scheduler);
    }

    private AudioRecordingManager(UserPreferences userPreferences,
                                  Consumer<CompletedAudioCall> recordedCallConsumer,
                                  Consumer<RecordedCallArtifact> recordedArtifactConsumer,
                                  Consumer<Path> recoveredArtifactConsumer,
                                  RecordedCallCatalogHandoff catalogHandoff,
                                  ScheduledExecutorService scheduler)
    {
        mUserPreferences = Objects.requireNonNull(userPreferences, "User preferences cannot be null");
        mRecordedCallConsumer = recordedCallConsumer;
        mRecordedArtifactConsumer = recordedArtifactConsumer;
        mRecoveredArtifactConsumer = recoveredArtifactConsumer;
        mCatalogHandoff = catalogHandoff;
        mScheduler = Objects.requireNonNull(scheduler, "Recording scheduler cannot be null");
    }

    /**
     * Starts the manager and begins completed-call recording.
     */
    public synchronized void start()
    {
        if(mQueueProcessorHandle == null)
        {
            mHandoffLock.lock();

            try
            {
                if(mRecordingBasePath == null)
                {
                    //The managed recording root is fixed for this manager's lifetime. Directory changes require an
                    //explicit offline migration so the writer and retention catalog can never diverge.
                    mRecordingBasePath = ManagedRecordingPath.prepareRoot(
                        mUserPreferences.getDirectoryPreference().getDirectoryRecording());
                }

                reconcileManagedRecordings();

                try
                {
                    mQueueProcessorHandle = mScheduler.scheduleAtFixedRate(new QueueProcessor(),
                        0, 1, TimeUnit.SECONDS);
                    mReconciliationHandle = mScheduler.scheduleWithFixedDelay(new ReconciliationProcessor(),
                        RECONCILIATION_INTERVAL_SECONDS, RECONCILIATION_INTERVAL_SECONDS, TimeUnit.SECONDS);
                    mAcceptingCalls = true;
                }
                catch(RuntimeException exception)
                {
                    mAcceptingCalls = false;
                    ScheduledFuture<?> processor = mQueueProcessorHandle;
                    mQueueProcessorHandle = null;

                    if(processor != null)
                    {
                        processor.cancel(false);
                    }

                    closeReconciler();
                    throw exception;
                }
            }
            finally
            {
                mHandoffLock.unlock();
            }
        }
    }

    /**
     * Stops the manager and records any remaining queued completed calls.
     */
    public synchronized void stop()
    {
        mHandoffLock.lock();

        try
        {
            mAcceptingCalls = false;
        }
        finally
        {
            mHandoffLock.unlock();
        }

        ScheduledFuture<?> processor = mQueueProcessorHandle;
        mQueueProcessorHandle = null;
        ScheduledFuture<?> reconciliation = mReconciliationHandle;
        mReconciliationHandle = null;

        if(processor != null)
        {
            //Do not interrupt a file write.  The processing lock waits for an in-flight run before the final drain.
            processor.cancel(false);
        }

        if(reconciliation != null)
        {
            reconciliation.cancel(false);
        }

        processAudioSegments();

        if(mRecordingBasePath != null && (mCatalogHandoff != null || mRecoveredArtifactConsumer != null))
        {
            //Give any final published file one bounded ownership retry before the catalog is closed by the caller.
            reconcileManagedRecordings();
        }

        closeReconciler();
    }

    /**
     * Processes any queued completed calls.
     */
    private void processAudioSegments()
    {
        mProcessingLock.lock();

        try
        {
            RecordFormat recordFormat = mUserPreferences.getRecordPreference().getAudioRecordFormat();
            QueuedCall queuedCall = mCompletedAudioCallQueue.poll();

            while(queuedCall != null)
            {
                CompletedAudioCall completedAudioCall = queuedCall.call();

                try
                {
                    if(!catalogCanOwnNewRecording())
                    {
                        mDroppedRecordings.incrementAndGet();
                        mCatalogPausedRecordings.incrementAndGet();
                    }
                    else if(!(completedAudioCall.snapshot().duplicate() &&
                        mUserPreferences.getCallManagementPreference().isDuplicateRecordingSuppressionEnabled()))
                    {
                        try(ManagedCallRecording recording = ManagedCallRecording.prepare(getRecordingBasePath(),
                            completedAudioCall, recordFormat, queuedCall.pathMetadata(), mActiveRecordingPaths))
                        {
                            RecordedCallManifest manifest = new RecordedCallManifest(
                                completedAudioCall.snapshot().callId(),
                                completedAudioCall.snapshot().recordingMetadata(),
                                completedAudioCall.snapshot().startTimestamp(),
                                recording.completedAtMs(),
                                completedAudioCall.getDuration(),
                                completedAudioCall.snapshot().encrypted(),
                                recording.destinationTalkgroupRecordEnabled());
                            AudioCallRecorder.write(completedAudioCall, recording.stagingPath(), recordFormat,
                                mUserPreferences, completedAudioCall.snapshot().identifierCollection(), manifest);
                            Path path = recording.commit();

                            if(Files.isRegularFile(path) && Files.size(path) > 0)
                            {
                                long byteSize = Files.size(path);
                                RecordedCallArtifact artifact = new RecordedCallArtifact(path,
                                    recording.relativePath(), recordFormat, byteSize, manifest.callId(),
                                    manifest.metadata(), manifest.startAtMs(), manifest.completedAtMs(),
                                    manifest.durationMs(), manifest.encrypted(), manifest.recordEligible());
                                notifyRecorded(completedAudioCall, artifact);
                            }
                        }
                        catch(IOException | RuntimeException exception)
                        {
                            mLog.error("Error recording completed audio call", exception);
                        }
                    }
                }
                finally
                {
                    mQueuedSourceBytes.addAndGet(-queuedCall.sourceBytes());
                }

                //Grab the next one to record
                queuedCall = mCompletedAudioCallQueue.poll();
            }
        }
        finally
        {
            mProcessingLock.unlock();
        }
    }

    public void receive(CompletedAudioCall completedAudioCall)
    {
        if(completedAudioCall != null && completedAudioCall.snapshot() != null &&
            completedAudioCall.snapshot().recordAudio())
        {
            long sourceBytes = sourceBytes(completedAudioCall);

            if(sourceBytes <= 0 || sourceBytes > MAXIMUM_SOURCE_BYTES_PER_CALL)
            {
                mDroppedRecordings.incrementAndGet();
                return;
            }

            //A completed-call handoff must never wait behind disk or shutdown work.
            if(!mHandoffLock.tryLock())
            {
                mDroppedRecordings.incrementAndGet();
                return;
            }

            try
            {
                if(!mAcceptingCalls)
                {
                    mDroppedRecordings.incrementAndGet();
                    return;
                }

                if(!catalogCanOwnNewRecording())
                {
                    mDroppedRecordings.incrementAndGet();
                    mCatalogPausedRecordings.incrementAndGet();
                    return;
                }

                if(!reserveSourceBytes(sourceBytes))
                {
                    mDroppedRecordings.incrementAndGet();
                    return;
                }

                ManagedCallRecording.CallPathMetadata pathMetadata =
                    ManagedCallRecording.CallPathMetadata.capture(completedAudioCall);

                if(!mCompletedAudioCallQueue.offer(new QueuedCall(completedAudioCall, sourceBytes, pathMetadata)))
                {
                    mQueuedSourceBytes.addAndGet(-sourceBytes);
                    mDroppedRecordings.incrementAndGet();
                }
            }
            catch(RuntimeException _)
            {
                mQueuedSourceBytes.addAndGet(-sourceBytes);
                mDroppedRecordings.incrementAndGet();
            }
            finally
            {
                mHandoffLock.unlock();
            }
        }
    }

    public RecordingQueueStatus getQueueStatus()
    {
        boolean catalogPaused = isCatalogPaused();
        return new RecordingQueueStatus(mCompletedAudioCallQueue.size(), mQueuedSourceBytes.get(),
            mDroppedRecordings.get(), mAcceptingCalls && !catalogPaused,
            catalogPaused, mPendingCatalogRecoveries.size(), mCatalogPausedRecordings.get());
    }

    long getQueuedSourceBytes()
    {
        return mQueuedSourceBytes.get();
    }

    long getDroppedRecordingCount()
    {
        return mDroppedRecordings.get();
    }

    private boolean reserveSourceBytes(long sourceBytes)
    {
        long current = mQueuedSourceBytes.get();

        while(current <= MAXIMUM_QUEUED_SOURCE_BYTES - sourceBytes)
        {
            if(mQueuedSourceBytes.compareAndSet(current, current + sourceBytes))
            {
                return true;
            }

            current = mQueuedSourceBytes.get();
        }

        return false;
    }

    private static long sourceBytes(CompletedAudioCall call)
    {
        long samples = 0;

        if(call.audioBuffers() != null)
        {
            for(float[] buffer: call.audioBuffers())
            {
                if(buffer != null)
                {
                    if(samples > Long.MAX_VALUE - buffer.length)
                    {
                        return -1;
                    }

                    samples += buffer.length;
                }
            }
        }

        return samples > 0 && samples <= Long.MAX_VALUE / Float.BYTES ? samples * Float.BYTES : -1;
    }

    private void notifyRecorded(CompletedAudioCall completedAudioCall, RecordedCallArtifact artifact)
    {
        if(mRecordedCallConsumer != null)
        {
            try
            {
                mRecordedCallConsumer.accept(completedAudioCall);
            }
            catch(RuntimeException e)
            {
                mLog.warn("Recorded-call stats listener failed", e);
            }
        }

        if(mRecordedArtifactConsumer != null)
        {
            try
            {
                mRecordedArtifactConsumer.accept(artifact);
            }
            catch(RuntimeException e)
            {
                mLog.warn("Recorded-call artifact listener failed", e);
            }
        }

        if(mCatalogHandoff != null)
        {
            boolean accepted = false;

            try
            {
                accepted = mCatalogHandoff.submit(artifact);
            }
            catch(RuntimeException exception)
            {
                mLog.warn("Recorded-call catalog handoff failed", exception);
            }

            if(!accepted)
            {
                retainCatalogRecovery(artifact.path());
            }
        }
    }

    private boolean catalogCanOwnNewRecording()
    {
        if(mCatalogHandoff == null)
        {
            return true;
        }

        if(mCatalogBackpressured.get())
        {
            return false;
        }

        try
        {
            boolean accepting = mCatalogHandoff.isAccepting();

            if(!accepting)
            {
                mCatalogBackpressured.set(true);
            }

            return accepting;
        }
        catch(RuntimeException exception)
        {
            if(mCatalogBackpressured.compareAndSet(false, true))
            {
                mLog.warn("Recorded-call catalog availability check failed", exception);
            }

            return false;
        }
    }

    private void retainCatalogRecovery(Path path)
    {
        if(path == null)
        {
            mCatalogBackpressured.set(true);
            return;
        }

        Path normalized = path.toAbsolutePath().normalize();

        if(!mPendingCatalogRecoveries.offer(normalized))
        {
            //Never delete a rejected or unknown managed file. Pausing new recording bounds any uncataloged files,
            //and the normal filesystem reconciler can rediscover this exact path after catalog recovery.
            mLog.warn("Recorded-call catalog recovery queue is full; recording remains paused");
        }

        //Set this after publishing the retry path so a concurrent reconciliation pass cannot clear the pause between
        //the state change and the bounded queue offer.
        mCatalogBackpressured.set(true);
    }

    private boolean isCatalogPaused()
    {
        if(mCatalogBackpressured.get())
        {
            return true;
        }

        if(mCatalogHandoff == null)
        {
            return false;
        }

        try
        {
            return !mCatalogHandoff.isAccepting();
        }
        catch(RuntimeException _)
        {
            return true;
        }
    }

    /**
     * Base path to recordings folder
     * @return
     */
    public Path getRecordingBasePath()
    {
        Path recordingBasePath = mRecordingBasePath;
        return recordingBasePath != null ? recordingBasePath :
            mUserPreferences.getDirectoryPreference().getDirectoryRecording();
    }

    /**
     * Threaded queue processor to record each recordable completed call.
     */
    public class QueueProcessor implements Runnable
    {
        @Override
        public void run()
        {
            try
            {
                processAudioSegments();
            }
            catch(Exception e)
            {
                mLog.error("Error while processing queued audio segments to recordings", e);
            }
        }
    }

    private void reconcileManagedRecordings()
    {
        mReconciliationLock.lock();

        try
        {
            if(mCatalogHandoff != null && !retryPendingCatalogRecoveries())
            {
                return;
            }

            Path root = getRecordingBasePath().toAbsolutePath().normalize();

            if(mReconciler == null || !root.equals(mReconciliationRoot))
            {
                if(mReconciler != null)
                {
                    mReconciler.close();
                }

                mReconciliationRoot = root;
                mReconciler = new ManagedRecordingReconciler(root, STALE_WORK_AGE, mActiveRecordingPaths);
            }

            ManagedRecordingReconciler.Batch batch = mReconciler.reconcile(RECONCILIATION_BATCH_ENTRIES);

            if(mCatalogHandoff != null)
            {
                for(int index = 0; index < batch.recordings().size(); index++)
                {
                    Path path = mReconciliationRoot.resolve(batch.recordings().get(index).relativePath());
                    boolean accepted = false;

                    try
                    {
                        accepted = mCatalogHandoff.submitRecovery(path);
                    }
                    catch(RuntimeException exception)
                    {
                        mLog.debug("Recorded-call recovery handoff failed", exception);
                    }

                    if(!accepted)
                    {
                        retainCatalogRecovery(path);

                        //The reconciler cursor has already advanced over this bounded batch. Preserve each remaining
                        //known managed path for direct retry instead of waiting for a full filesystem scan to wrap.
                        for(int remaining = index + 1; remaining < batch.recordings().size(); remaining++)
                        {
                            retainCatalogRecovery(mReconciliationRoot.resolve(
                                batch.recordings().get(remaining).relativePath()));
                        }

                        break;
                    }
                }
            }
            else if(mRecoveredArtifactConsumer != null)
            {
                for(ManagedRecordingPath recording: batch.recordings())
                {
                    try
                    {
                        mRecoveredArtifactConsumer.accept(mReconciliationRoot.resolve(recording.relativePath()));
                    }
                    catch(RuntimeException exception)
                    {
                        mLog.debug("Recorded-call recovery handoff failed", exception);
                    }
                }
            }

            if(batch.errors() > 0)
            {
                mLog.debug("Managed recording reconciliation encountered {} bounded filesystem error(s)",
                    batch.errors());
            }
        }
        catch(RuntimeException exception)
        {
            mLog.debug("Unable to reconcile managed recording work files", exception);
        }
        finally
        {
            mReconciliationLock.unlock();
        }
    }

    private boolean retryPendingCatalogRecoveries()
    {
        if(mCatalogHandoff == null)
        {
            return true;
        }

        try
        {
            if(!mCatalogHandoff.isAccepting())
            {
                mCatalogBackpressured.set(true);
                return false;
            }
        }
        catch(RuntimeException exception)
        {
            mCatalogBackpressured.set(true);
            mLog.debug("Recorded-call catalog availability check failed during recovery", exception);
            return false;
        }

        int attempted = 0;
        Path pending = mPendingCatalogRecoveries.peek();

        while(pending != null && attempted < RECONCILIATION_BATCH_ENTRIES)
        {
            boolean accepted = false;

            try
            {
                accepted = mCatalogHandoff.submitRecovery(pending);
            }
            catch(RuntimeException exception)
            {
                mLog.debug("Recorded-call recovery retry failed", exception);
            }

            if(!accepted)
            {
                mCatalogBackpressured.set(true);
                return false;
            }

            mPendingCatalogRecoveries.poll();
            attempted++;
            pending = mPendingCatalogRecoveries.peek();
        }

        if(mPendingCatalogRecoveries.isEmpty())
        {
            mCatalogBackpressured.set(false);
            return true;
        }

        //A bounded maintenance pass never drains more than one reconciliation batch.
        mCatalogBackpressured.set(true);
        return false;
    }

    private void closeReconciler()
    {
        mReconciliationLock.lock();

        try
        {
            if(mReconciler != null)
            {
                mReconciler.close();
                mReconciler = null;
                mReconciliationRoot = null;
            }
        }
        finally
        {
            mReconciliationLock.unlock();
        }
    }

    private class ReconciliationProcessor implements Runnable
    {
        @Override
        public void run()
        {
            reconcileManagedRecordings();
        }
    }

    public record RecordingQueueStatus(int queuedCalls, long queuedSourceBytes, long droppedRecordings,
                                       boolean acceptingCalls, boolean catalogPaused,
                                       int pendingCatalogRecoveries, long catalogPausedRecordings)
    {
    }

    private record QueuedCall(CompletedAudioCall call, long sourceBytes,
                              ManagedCallRecording.CallPathMetadata pathMetadata)
    {
    }
}
