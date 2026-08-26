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

package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.record.AudioCallRecorder;
import io.github.dsheirer.record.RecordFormat;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.ThreadPool;
import io.github.dsheirer.util.TimeStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Streams completed immutable audio calls by creating temporary recordings and enqueueing them for broadcast.
 */
public class AudioStreamingManager
{
    private static final Logger mLog = LoggerFactory.getLogger(AudioStreamingManager.class);
    static final int MAXIMUM_QUEUED_CALLS = 128;
    static final long MAXIMUM_SOURCE_BYTES_PER_CALL = 64L * 1024L * 1024L;
    static final long MAXIMUM_QUEUED_SOURCE_BYTES = 256L * 1024L * 1024L;
    private final ConcurrentLinkedQueue<QueuedCall> mNewAudioCalls = new ConcurrentLinkedQueue<>();
    private final AtomicInteger mRetainedCallCount = new AtomicInteger();
    private final AtomicLong mRetainedSourceBytes = new AtomicLong();
    private final AtomicLong mDroppedCalls = new AtomicLong();
    private final AtomicLong mFailedCalls = new AtomicLong();
    private final ReentrantLock mProcessingLock = new ReentrantLock();
    private final ReentrantLock mHandoffLock = new ReentrantLock();
    private final Listener<AudioRecording> mAudioRecordingListener;
    private final BroadcastFormat mBroadcastFormat;
    private final UserPreferences mUserPreferences;
    private final Consumer<CompletedAudioCall> mStreamedCallConsumer;
    private final ScheduledExecutorService mScheduler;
    private final StreamingRecordingWriter mRecordingWriter;
    private volatile boolean mAcceptingCalls;
    private ScheduledFuture<?> mAudioSegmentProcessorFuture;
    private int mNextRecordingNumber = 1;

    /**
     * Constructs an instance
     * @param listener to receive completed audio recordings
     * @param broadcastFormat for temporary recordings
     * @param userPreferences to manage recording directories
     */
    public AudioStreamingManager(Listener<AudioRecording> listener, BroadcastFormat broadcastFormat, UserPreferences userPreferences)
    {
        this(listener, broadcastFormat, userPreferences, null);
    }

    public AudioStreamingManager(Listener<AudioRecording> listener, BroadcastFormat broadcastFormat,
                                 UserPreferences userPreferences,
                                 Consumer<CompletedAudioCall> streamedCallConsumer)
    {
        this(listener, broadcastFormat, userPreferences, streamedCallConsumer, ThreadPool.SCHEDULED,
            (call, path, preferences, identifiers) ->
                AudioCallRecorder.write(call, path, RecordFormat.MP3, preferences, identifiers));
    }

    AudioStreamingManager(Listener<AudioRecording> listener, BroadcastFormat broadcastFormat,
                          UserPreferences userPreferences, Consumer<CompletedAudioCall> streamedCallConsumer,
                          ScheduledExecutorService scheduler, StreamingRecordingWriter recordingWriter)
    {
        mAudioRecordingListener = listener;
        mBroadcastFormat = Objects.requireNonNull(broadcastFormat, "Broadcast format cannot be null");
        mUserPreferences = Objects.requireNonNull(userPreferences, "User preferences cannot be null");
        mStreamedCallConsumer = streamedCallConsumer;
        mScheduler = Objects.requireNonNull(scheduler, "Streaming scheduler cannot be null");
        mRecordingWriter = Objects.requireNonNull(recordingWriter, "Streaming recording writer cannot be null");
    }

    /**
     * Starts the scheduled completed-call processor.
     */
    public synchronized void start()
    {
        if(mAudioSegmentProcessorFuture == null)
        {
            mHandoffLock.lock();

            try
            {
                mAudioSegmentProcessorFuture = mScheduler.scheduleAtFixedRate(new AudioSegmentProcessor(),
                    0, 250, TimeUnit.MILLISECONDS);
                mAcceptingCalls = true;
            }
            catch(RuntimeException exception)
            {
                mAcceptingCalls = false;
                releaseQueuedCalls("streaming processor could not start");
                throw exception;
            }
            finally
            {
                mHandoffLock.unlock();
            }
        }
    }

    /**
     * Stops the scheduled completed-call processor.
     */
    public synchronized void stop()
    {
        ScheduledFuture<?> processor;
        mHandoffLock.lock();

        try
        {
            mAcceptingCalls = false;
            processor = mAudioSegmentProcessorFuture;
            mAudioSegmentProcessorFuture = null;
        }
        finally
        {
            mHandoffLock.unlock();
        }

        if(processor != null)
        {
            //Do not interrupt an encoder/file handoff. The processing lock waits for it before releasing the queue.
            processor.cancel(false);
        }

        mProcessingLock.lock();

        try
        {
            releaseQueuedCalls("streaming manager stopped");
        }
        finally
        {
            mProcessingLock.unlock();
        }
    }

    /**
     * Scheduled runnable to process completed calls.
     */
    public class AudioSegmentProcessor implements Runnable
    {
        /**
         * Creates a temporary streaming recording file path
         */
        private Path getTemporaryRecordingPath()
        {
            StringBuilder sb = new StringBuilder();
            sb.append(BroadcastModel.TEMPORARY_STREAM_FILE_SUFFIX);

            //Check for integer overflow and readjust negative value to 0
            if(mNextRecordingNumber < 0)
            {
                mNextRecordingNumber = 1;
            }

            int recordingNumber = mNextRecordingNumber++;

            sb.append(recordingNumber).append("_");
            sb.append(TimeStamp.getLongTimeStamp("_"));
            sb.append(mBroadcastFormat.getFileExtension());

            return mUserPreferences.getDirectoryPreference().getDirectoryStreaming().resolve(sb.toString());
        }

        /**
         * Processes a completed call for streaming by creating a temporary MP3 recording and submitting the recording
         * to the specific broadcast channel(s).
         * @param completedAudioCall to process for streaming
         * @param identifierCollection to use for the streamed audio recording
         * @param broadcastChannels to receive the audio recording
         */
        private boolean processAudioCall(CompletedAudioCall completedAudioCall,
                                         IdentifierCollection identifierCollection,
                                         Set<BroadcastChannel> broadcastChannels)
        {
            Path path = getTemporaryRecordingPath();
            long length = completedAudioCall.getDuration();

            try
            {
                mRecordingWriter.write(completedAudioCall, path, mUserPreferences, identifierCollection);

                if(!Files.isRegularFile(path) || Files.size(path) <= 0)
                {
                    return false;
                }

                BroadcastDeliveryEvidence deliveryEvidence =
                    BroadcastDeliveryEvidence.from(completedAudioCall.resolvedPolicy());
                AudioRecording audioRecording = new AudioRecording(path, broadcastChannels, identifierCollection,
                    completedAudioCall.snapshot().startTimestamp(), length, deliveryEvidence);
                mAudioRecordingListener.receive(audioRecording);
                return true;
            }
            catch(IOException ioe)
            {
                mLog.error("Error recording temporary stream MP3");
                return false;
            }
            catch(RuntimeException e)
            {
                mLog.warn("Error handing completed call to the streaming pipeline", e);
                return false;
            }
        }

        /**
         * Decomposes a patch group without allowing current alias state to expand the frozen routing decisions carried
         * by the completed call. Each routing key is claimed once in stable identifier order. A key contributed by a
         * losing receiver copy, or whose alias was removed after completion, is sent once with the original patch
         * identifiers instead of being dropped or sprayed across every member.
         */
        private boolean processPatchGroupTalkgroups(CompletedAudioCall completedAudioCall,
                                                    IdentifierCollection identifiers,
                                                    PatchGroup patchGroup)
        {
            Map<String, BroadcastChannel> frozenChannels =
                indexFrozenBroadcastChannels(completedAudioCall.snapshot().broadcastChannels());

            if(frozenChannels.isEmpty())
            {
                return false;
            }

            List<Identifier> patchIdentifiers = new ArrayList<>();
            patchIdentifiers.addAll(patchGroup.getPatchedTalkgroupIdentifiers());
            patchIdentifiers.addAll(patchGroup.getPatchedRadioIdentifiers());
            patchIdentifiers.sort(Comparator.comparingInt(this::stableIdentifierCategory)
                .thenComparing(this::stableIdentifierKey));
            AliasList aliasList = completedAudioCall.snapshot().aliasList();

            if(patchIdentifiers.isEmpty() || aliasList == null)
            {
                return processAudioCall(completedAudioCall, identifiers,
                    Set.copyOf(frozenChannels.values()));
            }

            Set<String> unclaimedRoutingKeys = new LinkedHashSet<>(frozenChannels.keySet());
            boolean sentToStreamer = false;

            for(Identifier identifier : patchIdentifiers)
            {
                Set<String> identifierRoutingKeys = new TreeSet<>();

                for(Alias alias : aliasList.getAliases(identifier))
                {
                    for(BroadcastChannel broadcastChannel : alias.getBroadcastChannels())
                    {
                        String routingKey = normalizeRoutingKey(broadcastChannel);

                        if(routingKey != null && unclaimedRoutingKeys.contains(routingKey))
                        {
                            identifierRoutingKeys.add(routingKey);
                        }
                    }
                }

                if(!identifierRoutingKeys.isEmpty())
                {
                    Set<BroadcastChannel> claimedChannels = new LinkedHashSet<>();

                    for(String routingKey : identifierRoutingKeys)
                    {
                        if(unclaimedRoutingKeys.remove(routingKey))
                        {
                            claimedChannels.add(frozenChannels.get(routingKey));
                        }
                    }

                    MutableIdentifierCollection decomposedIdentifiers =
                        new MutableIdentifierCollection(identifiers.getIdentifiers());
                    //Remove patch group TO identifier and replace it with the deterministic patched member.
                    decomposedIdentifiers.remove(Role.TO);
                    decomposedIdentifiers.update(identifier);
                    sentToStreamer |= processAudioCall(completedAudioCall, decomposedIdentifiers,
                        Set.copyOf(claimedChannels));
                }
            }

            if(!unclaimedRoutingKeys.isEmpty())
            {
                Set<BroadcastChannel> fallbackChannels = new LinkedHashSet<>();

                for(String routingKey : unclaimedRoutingKeys)
                {
                    fallbackChannels.add(frozenChannels.get(routingKey));
                }

                sentToStreamer |= processAudioCall(completedAudioCall, identifiers,
                    Set.copyOf(fallbackChannels));
            }

            return sentToStreamer;
        }

        private Map<String, BroadcastChannel> indexFrozenBroadcastChannels(
            Set<BroadcastChannel> broadcastChannels)
        {
            Map<String, BroadcastChannel> frozenChannels = new TreeMap<>();

            if(broadcastChannels != null)
            {
                for(BroadcastChannel broadcastChannel : broadcastChannels)
                {
                    String routingKey = normalizeRoutingKey(broadcastChannel);

                    if(routingKey != null)
                    {
                        frozenChannels.putIfAbsent(routingKey, broadcastChannel);
                    }
                }
            }

            return frozenChannels;
        }

        private String normalizeRoutingKey(BroadcastChannel broadcastChannel)
        {
            if(broadcastChannel == null || broadcastChannel.getChannelName() == null)
            {
                return null;
            }

            String routingKey = broadcastChannel.getChannelName().trim();
            return routingKey.isEmpty() ? null : routingKey;
        }

        private String stableIdentifierKey(Identifier identifier)
        {
            if(identifier == null)
            {
                return "";
            }

            String protocol = identifier.getProtocol() != null ? identifier.getProtocol().name() : "";
            String form = identifier.getForm() != null ? identifier.getForm().name() : "";
            String value = identifier.getValue() != null ? identifier.getValue().toString() : "";
            return protocol + '\u0000' + form + '\u0000' + value;
        }

        private int stableIdentifierCategory(Identifier identifier)
        {
            if(identifier == null || identifier.getForm() == null)
            {
                return 2;
            }

            return identifier.getForm() == Form.TALKGROUP ? 0 :
                identifier.getForm() == Form.RADIO ? 1 : 2;
        }

        /**
         * Main processing method to process completed calls.
         */
        private void processAudioSegments()
        {
            mProcessingLock.lock();

            try
            {
                QueuedCall queuedCall = mNewAudioCalls.poll();

                while(queuedCall != null)
                {
                    CompletedAudioCall completedAudioCall = queuedCall.call();

                    try
                    {
                        boolean attemptedStreaming = false;
                        boolean sentToStreamer = false;

                        try
                        {
                            if(isEligibleForStreaming(completedAudioCall))
                            {
                                attemptedStreaming = true;
                                IdentifierCollection identifiers = new IdentifierCollection(
                                    completedAudioCall.snapshot().identifierCollection().getIdentifiers());

                                if(identifiers.getToIdentifier() instanceof PatchGroupIdentifier patchGroupIdentifier)
                                {
                                    if(mUserPreferences.getCallManagementPreference()
                                        .getPatchGroupStreamingOption() == PatchGroupStreamingOption.TALKGROUPS)
                                    {
                                        sentToStreamer = processPatchGroupTalkgroups(completedAudioCall, identifiers,
                                            patchGroupIdentifier.getValue());
                                    }
                                    else
                                    {
                                        sentToStreamer = processAudioCall(completedAudioCall, identifiers,
                                            completedAudioCall.snapshot().broadcastChannels());
                                    }
                                }
                                else
                                {
                                    sentToStreamer = processAudioCall(completedAudioCall, identifiers,
                                        completedAudioCall.snapshot().broadcastChannels());
                                }
                            }
                        }
                        catch(RuntimeException exception)
                        {
                            attemptedStreaming = true;
                            mLog.warn("Error processing completed call for streaming", exception);
                        }

                        if(sentToStreamer)
                        {
                            notifyStreamed(completedAudioCall);
                        }
                        else if(attemptedStreaming)
                        {
                            failStreaming();
                        }
                    }
                    finally
                    {
                        releaseReservation(queuedCall.sourceBytes());
                    }

                    queuedCall = mNewAudioCalls.poll();
                }
            }
            finally
            {
                mProcessingLock.unlock();
            }
        }

        @Override
        public void run()
        {
            try
            {
                processAudioSegments();
            }
            catch(Exception e)
            {
                mLog.error("Error processing completed audio calls for streaming", e);
            }
        }
    }

    public void receive(CompletedAudioCall completedAudioCall)
    {
        if(completedAudioCall == null)
        {
            return;
        }

        if(!isEligibleForStreaming(completedAudioCall))
        {
            return;
        }

        long sourceBytes = sourceBytes(completedAudioCall);

        if(sourceBytes <= 0 || sourceBytes > MAXIMUM_SOURCE_BYTES_PER_CALL)
        {
            dropStreaming("invalid or oversized source audio");
            return;
        }

        //A completed-call producer must never wait behind an encoder, scheduled drain, or shutdown operation.
        if(!mHandoffLock.tryLock())
        {
            dropStreaming("streaming manager is changing state");
            return;
        }

        boolean callReserved = false;
        boolean sourceBytesReserved = false;

        try
        {
            if(!mAcceptingCalls)
            {
                dropStreaming("streaming manager is not accepting calls");
                return;
            }

            if(!reserveCall())
            {
                dropStreaming("streaming queue call limit reached");
                return;
            }

            callReserved = true;

            if(!reserveSourceBytes(sourceBytes))
            {
                mRetainedCallCount.decrementAndGet();
                callReserved = false;
                dropStreaming("streaming queue source-audio limit reached");
                return;
            }

            sourceBytesReserved = true;

            //ConcurrentLinkedQueue is lock-free and this handoff never waits for the encoder or file system.
            mNewAudioCalls.offer(new QueuedCall(completedAudioCall, sourceBytes));
            callReserved = false;
            sourceBytesReserved = false;
        }
        catch(RuntimeException exception)
        {
            if(sourceBytesReserved)
            {
                mRetainedSourceBytes.addAndGet(-sourceBytes);
            }

            if(callReserved)
            {
                mRetainedCallCount.decrementAndGet();
            }

            dropStreaming("unexpected queue handoff failure");
            mLog.warn("Unable to queue completed call for streaming", exception);
        }
        finally
        {
            mHandoffLock.unlock();
        }
    }

    private boolean isEligibleForStreaming(CompletedAudioCall completedAudioCall)
    {
        return mAudioRecordingListener != null && completedAudioCall.snapshot().hasBroadcastChannels() &&
            !(completedAudioCall.snapshot().duplicate() && mUserPreferences.getCallManagementPreference()
                .isDuplicateStreamingSuppressionEnabled());
    }

    public StreamingQueueStatus getQueueStatus()
    {
        return new StreamingQueueStatus(mRetainedCallCount.get(), mRetainedSourceBytes.get(),
            MAXIMUM_QUEUED_CALLS, MAXIMUM_QUEUED_SOURCE_BYTES, mDroppedCalls.get(), mFailedCalls.get(),
            mAcceptingCalls, mProcessingLock.isLocked(), mProcessingLock.getQueueLength());
    }

    private boolean reserveCall()
    {
        int current = mRetainedCallCount.get();

        while(current < MAXIMUM_QUEUED_CALLS)
        {
            if(mRetainedCallCount.compareAndSet(current, current + 1))
            {
                return true;
            }

            current = mRetainedCallCount.get();
        }

        return false;
    }

    private boolean reserveSourceBytes(long sourceBytes)
    {
        long current = mRetainedSourceBytes.get();

        while(current <= MAXIMUM_QUEUED_SOURCE_BYTES - sourceBytes)
        {
            if(mRetainedSourceBytes.compareAndSet(current, current + sourceBytes))
            {
                return true;
            }

            current = mRetainedSourceBytes.get();
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

    private void releaseQueuedCalls(String reason)
    {
        QueuedCall queuedCall = mNewAudioCalls.poll();

        while(queuedCall != null)
        {
            releaseReservation(queuedCall.sourceBytes());
            dropStreaming(reason);
            queuedCall = mNewAudioCalls.poll();
        }
    }

    private void releaseReservation(long sourceBytes)
    {
        mRetainedSourceBytes.addAndGet(-sourceBytes);
        mRetainedCallCount.decrementAndGet();
    }

    private void dropStreaming(String reason)
    {
        long dropped = mDroppedCalls.incrementAndGet();

        if(dropped == 1 || dropped % 100 == 0)
        {
            mLog.warn("Dropped completed call streaming handoff because {} ({} dropped since startup)",
                reason, dropped);
        }
    }

    private void failStreaming()
    {
        long failed = mFailedCalls.incrementAndGet();

        if(failed == 1 || failed % 100 == 0)
        {
            mLog.warn("Failed to create a completed call streaming recording ({} failed since startup)", failed);
        }
    }

    private void notifyStreamed(CompletedAudioCall completedAudioCall)
    {
        if(mStreamedCallConsumer != null)
        {
            try
            {
                mStreamedCallConsumer.accept(completedAudioCall);
            }
            catch(RuntimeException e)
            {
                mLog.warn("Streamed-call stats listener failed", e);
            }
        }
    }

    public record StreamingQueueStatus(int retainedCalls, long retainedSourceBytes, int maximumRetainedCalls,
                                       long maximumRetainedSourceBytes, long droppedCalls, long failedCalls,
                                       boolean acceptingCalls, boolean writerActive, int waitingDrains)
    {
    }

    private record QueuedCall(CompletedAudioCall call, long sourceBytes) {}

    @FunctionalInterface
    interface StreamingRecordingWriter
    {
        void write(CompletedAudioCall completedAudioCall, Path path, UserPreferences userPreferences,
                   IdentifierCollection identifierCollection) throws IOException;
    }
}
