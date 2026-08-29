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
import io.github.dsheirer.identifier.string.StringIdentifier;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.identifier.tone.ToneIdentifier;
import io.github.dsheirer.identifier.tone.ToneSequence;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.util.StringUtils;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.util.TimeStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
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
    private final ArrayBlockingQueue<QueuedCall> mCompletedAudioCallQueue =
        new ArrayBlockingQueue<>(MAXIMUM_QUEUED_CALLS);
    private final AtomicLong mQueuedSourceBytes = new AtomicLong();
    private final AtomicLong mDroppedRecordings = new AtomicLong();
    private final ReentrantLock mProcessingLock = new ReentrantLock();
    private final ReentrantLock mHandoffLock = new ReentrantLock();
    private volatile boolean mAcceptingCalls;
    private ScheduledFuture<?> mQueueProcessorHandle;
    private final UserPreferences mUserPreferences;
    private final Consumer<CompletedAudioCall> mRecordedCallConsumer;
    private final ScheduledExecutorService mScheduler;
    private final RecordingWriter mRecordingWriter;
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
        this(userPreferences, recordedCallConsumer, ThreadPool.SCHEDULED, AudioCallRecorder::write);
    }

    AudioRecordingManager(UserPreferences userPreferences, Consumer<CompletedAudioCall> recordedCallConsumer,
                          ScheduledExecutorService scheduler, RecordingWriter recordingWriter)
    {
        mUserPreferences = Objects.requireNonNull(userPreferences, "User preferences cannot be null");
        mRecordedCallConsumer = recordedCallConsumer;
        mScheduler = Objects.requireNonNull(scheduler, "Recording scheduler cannot be null");
        mRecordingWriter = Objects.requireNonNull(recordingWriter, "Recording writer cannot be null");
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
            RecordFormat recordFormat = mUserPreferences.getRecordPreference().getAudioRecordFormat();
            QueuedCall queuedCall = mCompletedAudioCallQueue.poll();

            while(queuedCall != null)
            {
                CompletedAudioCall completedAudioCall = queuedCall.call();
                Path path = null;

                try
                {
                    if(!(completedAudioCall.snapshot().duplicate() &&
                        mUserPreferences.getCallManagementPreference().isDuplicateRecordingSuppressionEnabled()))
                    {
                        path = getAudioRecordingPath(completedAudioCall, recordFormat);
                        mRecordingWriter.write(completedAudioCall, path, recordFormat, mUserPreferences);

                        if(Files.isRegularFile(path) && Files.size(path) > 0)
                        {
                            notifyRecorded(completedAudioCall);
                        }
                    }
                }
                catch(IOException | RuntimeException exception)
                {
                    mLog.error("Error recording completed audio call" +
                        (path != null ? " to [" + path + "]" : ""), exception);
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

                if(!mCompletedAudioCallQueue.offer(new QueuedCall(completedAudioCall, sourceBytes)))
                {
                    mQueuedSourceBytes.addAndGet(-sourceBytes);
                    sourceBytesReserved = false;
                    dropRecording("recording queue is full");
                    return;
                }

                //The queue now owns this reservation until the single recording drain releases it.
                sourceBytesReserved = false;
            }
            catch(RuntimeException exception)
            {
                if(sourceBytesReserved)
                {
                    mQueuedSourceBytes.addAndGet(-sourceBytes);
                }

                dropRecording("unexpected queue handoff failure");
                mLog.warn("Unable to queue completed call recording", exception);
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
            mDroppedRecordings.get(), mAcceptingCalls, mProcessingLock.isLocked(),
            mProcessingLock.getQueueLength());
    }

    private void dropRecording(String reason)
    {
        long dropped = mDroppedRecordings.incrementAndGet();

        if(dropped == 1 || dropped % 100 == 0)
        {
            mLog.warn("Dropped completed call recording because {} ({} dropped since startup)", reason, dropped);
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

    /**
     * Provides a formatted audio recording filename to use as the final audio filename.
     */
    private Path getAudioRecordingPath(CompletedAudioCall completedAudioCall, RecordFormat recordFormat)
    {
        IdentifierCollection identifierCollection = completedAudioCall.snapshot().identifierCollection();
        StringBuilder sb = new StringBuilder();

        if(identifierCollection != null)
        {
            Identifier system = identifierCollection.getIdentifier(IdentifierClass.CONFIGURATION, Form.SYSTEM, Role.ANY);

            if(system != null)
            {
                sb.append(((StringIdentifier)system).getValue()).append("_");
            }

            Identifier site = identifierCollection.getIdentifier(IdentifierClass.CONFIGURATION, Form.SITE, Role.ANY);

            if(site != null)
            {
                sb.append(((StringIdentifier)site).getValue()).append("_");
            }

            Identifier channel = identifierCollection.getIdentifier(IdentifierClass.CONFIGURATION, Form.CHANNEL, Role.ANY);

            if(channel != null)
            {
                sb.append(((StringIdentifier)channel).getValue()).append("_");
            }

            Identifier to = identifierCollection.getIdentifier(IdentifierClass.USER, Form.TALKGROUP, Role.TO);

            if(to != null)
            {
                sb.append("_TO_").append(clean(to.toString()));
            }
            else
            {
                List<Identifier> toIdentifiers = identifierCollection.getIdentifiers(Role.TO);

                if(!toIdentifiers.isEmpty())
                {
                    sb.append("_TO_").append(clean(toIdentifiers.get(0).toString()));
                }
            }

            Identifier from = identifierCollection.getIdentifier(IdentifierClass.USER, Form.RADIO, Role.FROM);

            if(from != null)
            {
                sb.append("_FROM_").append(clean(from.toString()));
            }
            else
            {
                List<Identifier> fromIdentifiers = identifierCollection.getIdentifiers(Role.FROM);

                if(!fromIdentifiers.isEmpty())
                {
                    for(Identifier identifier: fromIdentifiers)
                    {
                        if(identifier.getForm() != Form.TONE)
                        {
                            sb.append("_FROM_").append(clean(identifier.toString()));
                            break;
                        }
                    }
                }
            }

            List<Identifier> toneIdentifiers = identifierCollection.getIdentifiers(IdentifierClass.USER, Form.TONE);

            if(!toneIdentifiers.isEmpty())
            {
                try
                {
                    Identifier identifier = toneIdentifiers.get(0);

                    if(identifier instanceof ToneIdentifier)
                    {
                        ToneIdentifier toneIdentifier = (ToneIdentifier)identifier;
                        ToneSequence toneSequence = toneIdentifier.getValue();

                        if(toneSequence.hasTones())
                        {
                            sb.append("_TONES");

                            for(Tone tone: toneIdentifier.getValue().getTones())
                            {
                                String label = tone.getAmbeTone().toString();
                                label = label.replace("TONE", "").trim();
                                label = label.replace(" ", "_");
                                sb.append("_").append(label);
                            }
                        }
                    }
                }
                catch(Exception e)
                {
                    mLog.error("Error appending tones to audio recording filename");
                }
            }
        }
        else
        {
            sb.append("audio_recording_no_metadata_").append(mUnknownAudioRecordingIndex++);

            if(mUnknownAudioRecordingIndex < 0)
            {
                mUnknownAudioRecordingIndex = 1;
            }
        }

        StringBuilder sbFinal = new StringBuilder();
        sbFinal.append(TimeStamp.getLongTimeStamp(completedAudioCall.snapshot().lastActivityTimestamp(), "_"))
            .append("_");

        //Remove any illegal filename characters
        String cleaned = StringUtils.replaceIllegalCharacters(sb.toString());

        //Ensure total length doesn't exceed 255 characters.  Allow room for timestamp and extension.
        int maxLength = 255 - sbFinal.length() - recordFormat.getExtension().length();

        if(cleaned.length() > maxLength)
        {
            cleaned = cleaned.substring(0, maxLength);
        }

        sbFinal.append(cleaned).append(recordFormat.getExtension());

        return getRecordingBasePath().resolve(sbFinal.toString());
    }

    public static String clean(String value)
    {
        if(value != null)
        {
            return value.replace(":", "")
                    .replace(".", "_")
                    .replace("(", "_")
                    .replace(")", "")
                    .replace("ROAM ", "")
                    .replace("ISSI ", "");
        }

        return null;
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

    public record RecordingQueueStatus(int queuedCalls, long queuedSourceBytes, long droppedRecordings,
                                       boolean acceptingCalls, boolean writerActive, int waitingDrains)
    {
    }

    private record QueuedCall(CompletedAudioCall call, long sourceBytes)
    {
    }

    @FunctionalInterface
    interface RecordingWriter
    {
        void write(CompletedAudioCall completedAudioCall, Path path, RecordFormat recordFormat,
                   UserPreferences userPreferences) throws IOException;
    }
}
