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
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.record.RecordingMode;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.util.ThreadPool;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
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
    static final int MAXIMUM_QUEUED_CALLS = 128;
    static final long MAXIMUM_SOURCE_BYTES_PER_CALL = 64L * 1024L * 1024L;
    static final long MAXIMUM_QUEUED_SOURCE_BYTES = 256L * 1024L * 1024L;
    private static final DateTimeFormatter MANAGED_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd")
        .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MANAGED_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmssSSS'Z'")
        .withZone(ZoneOffset.UTC);
    private final ArrayBlockingQueue<QueuedCall> mCompletedAudioCallQueue =
        new ArrayBlockingQueue<>(MAXIMUM_QUEUED_CALLS);
    private final AtomicLong mQueuedSourceBytes = new AtomicLong();
    private final AtomicLong mDroppedRecordings = new AtomicLong();
    private volatile String mLastDropReason;
    private long mLastReportedDropped;
    private final ReentrantLock mProcessingLock = new ReentrantLock();
    private final ReentrantLock mHandoffLock = new ReentrantLock();
    private volatile boolean mAcceptingCalls;
    private ScheduledFuture<?> mQueueProcessorHandle;
    private final UserPreferences mUserPreferences;
    private final Consumer<CompletedAudioCall> mRecordedCallConsumer;
    private final ScheduledExecutorService mScheduler;
    private final RecordingWriter mRecordingWriter;
    private final ManagedRecordingSink mManagedRecordingSink;
    private final Path mManagedRecordingRoot;
    private int mUnknownAudioRecordingIndex = 1;

    /**
     * Constructs an instance
     * @param userPreferences to determine audio recording format
     */
    public AudioRecordingManager(UserPreferences userPreferences)
    {
        this(userPreferences, null);
    }

    public AudioRecordingManager(UserPreferences userPreferences, Consumer<CompletedAudioCall> recordedCallConsumer)
    {
        this(userPreferences, recordedCallConsumer, ThreadPool.SCHEDULED, AudioCallRecorder::write, null, null);
    }

    /** The catalog is separately owned and must outlive this manager's final queue drain. */
    public AudioRecordingManager(UserPreferences userPreferences, Consumer<CompletedAudioCall> recordedCallConsumer,
                                 ManagedRecordingCatalog managedRecordingCatalog)
    {
        this(userPreferences, recordedCallConsumer, ThreadPool.SCHEDULED, AudioCallRecorder::write,
            Objects.requireNonNull(managedRecordingCatalog, "Managed recording catalog is required")::submit,
            managedRecordingCatalog.recordingsRoot());
    }

    AudioRecordingManager(UserPreferences userPreferences, Consumer<CompletedAudioCall> recordedCallConsumer,
                          ScheduledExecutorService scheduler, RecordingWriter recordingWriter)
    {
        this(userPreferences, recordedCallConsumer, scheduler, recordingWriter, null, null);
    }

    AudioRecordingManager(UserPreferences userPreferences, Consumer<CompletedAudioCall> recordedCallConsumer,
                          ScheduledExecutorService scheduler, RecordingWriter recordingWriter,
                          ManagedRecordingSink managedRecordingSink, Path managedRecordingRoot)
    {
        mUserPreferences = Objects.requireNonNull(userPreferences, "User preferences cannot be null");
        mRecordedCallConsumer = recordedCallConsumer;
        mScheduler = Objects.requireNonNull(scheduler, "Recording scheduler cannot be null");
        mRecordingWriter = Objects.requireNonNull(recordingWriter, "Recording writer cannot be null");
        mManagedRecordingSink = managedRecordingSink;
        mManagedRecordingRoot = managedRecordingRoot != null ? managedRecordingRoot.toAbsolutePath().normalize() : null;
    }

    /**
     * Starts the manager and begins completed-call recording.
     */
    public synchronized void start()
    {
        if(mQueueProcessorHandle == null)
        {
            // Prime the preference before receive() can run on a completed-call callback.
            mUserPreferences.getRecordPreference().getRecordingMode();
            mHandoffLock.lock();

            try
            {
                mQueueProcessorHandle = mScheduler.scheduleAtFixedRate(new QueueProcessor(),
                    0, 1, TimeUnit.SECONDS);
                mAcceptingCalls = true;
            }
            catch(RuntimeException exception)
            {
                mAcceptingCalls = false;
                mQueueProcessorHandle = null;
                throw exception;
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
        ScheduledFuture<?> processor;
        mHandoffLock.lock();

        try
        {
            mAcceptingCalls = false;
            processor = mQueueProcessorHandle;
            mQueueProcessorHandle = null;
        }
        finally
        {
            mHandoffLock.unlock();
        }

        if(processor != null)
        {
            //Do not interrupt a file write.  The processing lock waits for an in-flight run before the final drain.
            processor.cancel(false);
        }

        processAudioSegments();
    }

    /**
     * Processes any queued completed calls.
     */
    private void processAudioSegments()
    {
        mProcessingLock.lock();

        try
        {
            QueuedCall queuedCall = mCompletedAudioCallQueue.poll();

            while(queuedCall != null)
            {
                CompletedAudioCall completedAudioCall = queuedCall.call();
                boolean managed = queuedCall.mode() == RecordingMode.MANAGED;
                RecordFormat recordFormat = managed ? RecordFormat.MP3 :
                    mUserPreferences.getRecordPreference().getAudioRecordFormat();
                Path path = null;
                boolean writeStarted = false;
                long writerTimestamp = System.currentTimeMillis();
                int unknownIndex = mUnknownAudioRecordingIndex;
                if(!managed && completedAudioCall.snapshot().identifierCollection() == null)
                {
                    if(++mUnknownAudioRecordingIndex <= 0) mUnknownAudioRecordingIndex = 1;
                }

                try
                {
                    path = managed ? getManagedRecordingPath(completedAudioCall) :
                        BasicRecordingContract.nextPath(getRecordingBasePath(),
                            completedAudioCall.snapshot().identifierCollection(), recordFormat, writerTimestamp,
                            unknownIndex);

                    if(managed)
                    {
                        Files.createDirectories(path.getParent());
                    }

                    writeStarted = true;
                    //CREATE_NEW owns the final collision check. Never overwrite an existing Basic recording,
                    //including a file another writer creates after nextPath() checks the directory.
                    for(int attempt = 0; ; attempt++)
                    {
                        try
                        {
                            mRecordingWriter.write(completedAudioCall, path, recordFormat, mUserPreferences);
                            break;
                        }
                        catch(FileAlreadyExistsException collision)
                        {
                            if(managed || attempt >= 31) throw collision;
                            path = BasicRecordingContract.nextPath(getRecordingBasePath(),
                                completedAudioCall.snapshot().identifierCollection(), recordFormat, writerTimestamp,
                                unknownIndex);
                        }
                    }

                    long fileSize = Files.isRegularFile(path) ? Files.size(path) : 0L;

                    if(fileSize <= 0L)
                    {
                        if(managed)
                        {
                            deleteUnindexedManagedFile(path);
                            dropRecording("managed audio file was empty or missing");
                        }
                    }
                    else if(managed)
                    {
                        // Keep only the small call snapshot for the observer. The catalog invokes it after the
                        // SQLite insert commits, and never retains the source audio buffers in its queue.
                        CompletedAudioCall observedCall = mRecordedCallConsumer != null ?
                            new CompletedAudioCall(completedAudioCall.logicalCallId(),
                                completedAudioCall.snapshot(), List.of(), completedAudioCall.resolvedPolicy(),
                                completedAudioCall.callLegSummaries()) : null;
                        if(mManagedRecordingSink != null &&
                            mManagedRecordingSink.submit(path, completedAudioCall, fileSize,
                                observedCall != null ? () -> notifyRecorded(observedCall) : null))
                        {
                            //The catalog now owns this file and its committed-record observer.
                        }
                        else
                        {
                            deleteUnindexedManagedFile(path);
                            dropRecording("managed catalog handoff was unavailable or full");
                        }
                    }
                    else
                    {
                        notifyRecorded(completedAudioCall);
                    }
                }
                catch(IOException | RuntimeException exception)
                {
                    if(managed && path != null && writeStarted && !(exception instanceof FileAlreadyExistsException))
                    {
                        deleteUnindexedManagedFile(path);
                        dropRecording("managed recording or catalog handoff failed");
                    }

                    mLog.error("Error recording completed audio call" +
                        (path != null ? " to [" + path.getFileName() + "]" : ""), exception);
                }
                finally
                {
                    mQueuedSourceBytes.addAndGet(-queuedCall.sourceBytes());
                }

                queuedCall = mCompletedAudioCallQueue.poll();
            }
        }
        finally
        {
            try
            {
                reportDroppedRecordings();
            }
            finally
            {
                mProcessingLock.unlock();
            }
        }
    }

    public void receive(CompletedAudioCall completedAudioCall)
    {
        if(completedAudioCall != null && completedAudioCall.hasAudio() && completedAudioCall.snapshot() != null &&
            completedAudioCall.snapshot().recordAudio())
        {
            long sourceBytes = sourceBytes(completedAudioCall);

            if(sourceBytes <= 0 || sourceBytes > MAXIMUM_SOURCE_BYTES_PER_CALL)
            {
                dropRecording("invalid or oversized source audio");
                return;
            }

            //A completed-call handoff must never wait behind disk or shutdown work.
            if(!mHandoffLock.tryLock())
            {
                dropRecording("recording manager is stopping");
                return;
            }

            boolean sourceBytesReserved = false;

            try
            {
                if(!mAcceptingCalls)
                {
                    dropRecording("recording manager is not accepting calls");
                    return;
                }

                if(!reserveSourceBytes(sourceBytes))
                {
                    dropRecording("queued source-audio limit reached");
                    return;
                }

                sourceBytesReserved = true;

                RecordingMode mode = mUserPreferences.getRecordPreference().getRecordingMode();

                if(mode == RecordingMode.MANAGED && mManagedRecordingSink == null)
                {
                    mQueuedSourceBytes.addAndGet(-sourceBytes);
                    sourceBytesReserved = false;
                    dropRecording("managed catalog is unavailable");
                    return;
                }

                if(!mCompletedAudioCallQueue.offer(new QueuedCall(completedAudioCall, sourceBytes, mode)))
                {
                    mQueuedSourceBytes.addAndGet(-sourceBytes);
                    sourceBytesReserved = false;
                    dropRecording("recording queue is full");
                    return;
                }

                //The queue now owns this reservation until the single recording drain releases it.
                sourceBytesReserved = false;
            }
            catch(RuntimeException ignored)
            {
                if(sourceBytesReserved)
                {
                    mQueuedSourceBytes.addAndGet(-sourceBytes);
                }

                dropRecording("unexpected queue handoff failure");
            }
            finally
            {
                mHandoffLock.unlock();
            }
        }
    }

    public RecordingQueueStatus getQueueStatus()
    {
        return new RecordingQueueStatus(mCompletedAudioCallQueue.size(), mQueuedSourceBytes.get(),
            MAXIMUM_QUEUED_CALLS, MAXIMUM_QUEUED_SOURCE_BYTES, mDroppedRecordings.get(), mAcceptingCalls,
            mProcessingLock.isLocked(), mProcessingLock.getQueueLength());
    }

    private void dropRecording(String reason)
    {
        mLastDropReason = reason;
        mDroppedRecordings.incrementAndGet();
    }

    /** Reports on the recording worker so a completed-call callback never waits for logging. */
    private void reportDroppedRecordings()
    {
        long dropped = mDroppedRecordings.get();
        if(dropped > mLastReportedDropped)
        {
            mLog.warn("Dropped {} completed call recordings since previous report ({} since startup); latest reason: {}",
                dropped - mLastReportedDropped, dropped, mLastDropReason);
            mLastReportedDropped = dropped;
        }
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

    private void notifyRecorded(CompletedAudioCall completedAudioCall)
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
    }

    /**
     * Base path to recordings folder
     * @return
     */
    public Path getRecordingBasePath()
    {
        return mUserPreferences.getDirectoryPreference().getDirectoryRecording();
    }

    /** Uses only stable machine identifiers in folders; current display labels remain in the catalog lookup. */
    private Path getManagedRecordingPath(CompletedAudioCall call)
    {
        Instant completedAt = Instant.ofEpochMilli(call.snapshot().lastActivityTimestamp());
        String channelId = safeManagedPart(call.snapshot().callLegSource().channelConfigurationId(), "unknown-channel");
        IdentifierCollection identifiers = call.snapshot().identifierCollection();
        Identifier<?> destination = identifiers != null ? identifiers.getToIdentifier() : null;
        Identifier<?> source = identifiers != null ? identifiers.getFromIdentifier() : null;
        String destinationId = numericIdentifier(destination, "unknown");
        String sourceId = numericIdentifier(source, "unknown");
        String destinationFolder = destination != null && destination.getForm() == Form.PATCH_GROUP ?
            "patch-" + destinationId : destination != null && destination.getForm() == Form.RADIO ?
                "radio-" + destinationId : destination != null && destination.getForm() == Form.TALKGROUP ?
                    "tg-" + destinationId : "channel";
        String fileName = MANAGED_TIMESTAMP.format(completedAt) + "_" + destinationId + "_" + sourceId +
            "_" + UUID.randomUUID() + RecordFormat.MP3.getExtension();

        Path root = mManagedRecordingRoot != null ? mManagedRecordingRoot :
            mUserPreferences.getDirectoryPreference().getDirectoryManagedRecording();
        return root
            .resolve(MANAGED_DATE.format(completedAt)).resolve(channelId).resolve(destinationFolder)
            .resolve(fileName);
    }

    private static String numericIdentifier(Identifier<?> identifier, String fallback)
    {
        if(identifier instanceof PatchGroupIdentifier patch && patch.getValue() != null)
        {
            identifier = patch.getValue().getPatchGroup();
        }

        Object value = identifier != null ? identifier.getValue() : null;
        return value instanceof Number number ? Long.toString(number.longValue()) : fallback;
    }

    private static String safeManagedPart(String value, String fallback)
    {
        if(value == null || value.isBlank())
        {
            return fallback;
        }

        String cleaned = value.replaceAll("[^A-Za-z0-9_-]", "-");
        return cleaned.length() > 64 ? cleaned.substring(0, 64) : cleaned;
    }

    private void deleteUnindexedManagedFile(Path path)
    {
        try
        {
            Files.deleteIfExists(path);
        }
        catch(IOException exception)
        {
            mLog.warn("Unable to remove an unindexed managed recording [{}]", path, exception);
        }
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

    public record RecordingQueueStatus(int queuedCalls, long queuedSourceBytes, int maximumQueuedCalls,
                                       long maximumQueuedSourceBytes, long droppedRecordings,
                                       boolean acceptingCalls, boolean writerActive, int waitingDrains)
    {
    }

    private record QueuedCall(CompletedAudioCall call, long sourceBytes, RecordingMode mode)
    {
    }

    @FunctionalInterface
    interface ManagedRecordingSink
    {
        boolean submit(Path savedFile, CompletedAudioCall call, long sizeBytes, Runnable onIndexed);
    }

    @FunctionalInterface
    interface RecordingWriter
    {
        void write(CompletedAudioCall completedAudioCall, Path path, RecordFormat recordFormat,
                   UserPreferences userPreferences) throws IOException;
    }
}
