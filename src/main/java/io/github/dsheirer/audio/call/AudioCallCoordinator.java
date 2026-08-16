/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.audio.call;

import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.broadcast.AudioStreamingManager;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.duplicate.ICallManagementProvider;
import io.github.dsheirer.record.AudioRecordingManager;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.util.concurrent.ObserverThreadFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serialized owner of live audio call state. Normalizes immutable producer events, applies duplicate suppression,
 * and emits the elected completed immutable call for recording, configured streaming providers, and browser
 * playback. Completed-call consumers run on the coordinator worker and must perform only bounded, nonblocking
 * handoffs; the production recording, streaming, and browser consumers each own their downstream queue and limits.
 */
public class AudioCallCoordinator implements Listener<AudioCallEvent>
{
    private static final Logger mLog = LoggerFactory.getLogger(AudioCallCoordinator.class);
    static final long DEFAULT_DUPLICATE_COMPLETION_WATCHDOG_MILLISECONDS = 1_000L;
    static final long DEFAULT_DUPLICATE_COMPLETION_ORPHAN_CEILING_MILLISECONDS = 10_000L;
    static final int DEFAULT_INGRESS_CAPACITY = 4_096;
    static final int DEFAULT_LIFECYCLE_INGRESS_RESERVE = 256;
    static final int MAXIMUM_ACTIVE_CALLS = 128;
    static final int MAXIMUM_TRACKED_OVERFLOWED_CALLS = 256;
    static final long MAXIMUM_AUDIO_SAMPLES_PER_CALL = 512_000L;
    private static final long SHUTDOWN_DRAIN_MILLISECONDS = 2_000L;
    private static final int EVENT = 1;
    private static final int ABORT = 2;
    private static final int WATCHDOG = 3;

    private final AudioCallIngressQueue mIngress;
    private final ScheduledThreadPoolExecutor mWatchdogScheduler;
    private final Thread mWorker;
    private final Map<AudioCallId, ManagedAudioCall> mCalls = new HashMap<>();
    private final Map<Long, DuplicateGroup> mDuplicateGroups = new HashMap<>();
    private final List<CompletedAudioCall> mPendingFanouts = new ArrayList<>();
    private final Set<AudioCallId> mOverflowedCallIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger mOverflowedCallCount = new AtomicInteger();
    private final AtomicLong mIngressGeneration = new AtomicLong(1L);
    private final AtomicBoolean mAbortAllPending = new AtomicBoolean();
    private final AtomicLong mAcceptedIngressCount = new AtomicLong();
    private final AtomicLong mDroppedIngressCount = new AtomicLong();
    private final AtomicLong mDroppedLifecycleCount = new AtomicLong();
    private final AtomicLong mDroppedOperationCount = new AtomicLong();
    private final AtomicLong mAbortedCallCount = new AtomicLong();
    private final AtomicInteger mActiveReceives = new AtomicInteger();
    private final ICallManagementProvider mCallManagementProvider;
    private final DuplicateCallPriorityProvider mDuplicateCallPriorityProvider;
    private final Consumer<CompletedAudioCall> mRecordingConsumer;
    private final Consumer<CompletedAudioCall> mStreamingConsumer;
    private final Consumer<CompletedAudioCall> mWebConsumer;
    private final long mDuplicateCompletionWatchdogMilliseconds;
    private final long mDuplicateCompletionOrphanCeilingMilliseconds;
    private long mNextRegistrationOrdinal;
    private long mNextDuplicateGroupOrdinal;
    private volatile boolean mAccepting = true;
    private volatile boolean mDisposed;

    public AudioCallCoordinator(UserPreferences userPreferences, AudioRecordingManager audioRecordingManager,
                                AudioStreamingManager audioStreamingManager,
                                Consumer<CompletedAudioCall> webConsumer)
    {
        this(userPreferences, audioRecordingManager, audioStreamingManager, webConsumer,
            DuplicateCallPriorityProvider.NONE);
    }

    /**
     * Constructs an audio-call coordinator with an optional stable source-priority hook for deterministic duplicate
     * elections.
     */
    public AudioCallCoordinator(UserPreferences userPreferences, AudioRecordingManager audioRecordingManager,
                                AudioStreamingManager audioStreamingManager,
                                Consumer<CompletedAudioCall> webConsumer,
                                DuplicateCallPriorityProvider duplicateCallPriorityProvider)
    {
        this(userPreferences.getCallManagementPreference(),
            audioRecordingManager != null ? audioRecordingManager::receive : null,
            audioStreamingManager != null ? audioStreamingManager::receive : null, webConsumer,
            duplicateCallPriorityProvider, DEFAULT_DUPLICATE_COMPLETION_WATCHDOG_MILLISECONDS,
            DEFAULT_DUPLICATE_COMPLETION_ORPHAN_CEILING_MILLISECONDS);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer)
    {
        this(callManagementProvider, recordingConsumer, streamingConsumer, webConsumer,
            DuplicateCallPriorityProvider.NONE,
            DEFAULT_DUPLICATE_COMPLETION_WATCHDOG_MILLISECONDS,
            DEFAULT_DUPLICATE_COMPLETION_ORPHAN_CEILING_MILLISECONDS);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer,
                         DuplicateCallPriorityProvider duplicateCallPriorityProvider,
                         long duplicateCompletionWatchdogMilliseconds)
    {
        this(callManagementProvider, recordingConsumer, streamingConsumer, webConsumer,
            duplicateCallPriorityProvider,
            duplicateCompletionWatchdogMilliseconds,
            deriveOrphanCeilingMilliseconds(duplicateCompletionWatchdogMilliseconds),
            DEFAULT_INGRESS_CAPACITY, DEFAULT_LIFECYCLE_INGRESS_RESERVE);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer,
                         DuplicateCallPriorityProvider duplicateCallPriorityProvider,
                         long duplicateCompletionWatchdogMilliseconds,
                         long duplicateCompletionOrphanCeilingMilliseconds)
    {
        this(callManagementProvider, recordingConsumer, streamingConsumer, webConsumer,
            duplicateCallPriorityProvider, duplicateCompletionWatchdogMilliseconds,
            duplicateCompletionOrphanCeilingMilliseconds, DEFAULT_INGRESS_CAPACITY,
            DEFAULT_LIFECYCLE_INGRESS_RESERVE);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer,
                         DuplicateCallPriorityProvider duplicateCallPriorityProvider,
                         long duplicateCompletionWatchdogMilliseconds,
                         long duplicateCompletionOrphanCeilingMilliseconds,
                         int ingressCapacity, int lifecycleIngressReserve)
    {
        mCallManagementProvider = callManagementProvider;
        mRecordingConsumer = recordingConsumer;
        mStreamingConsumer = streamingConsumer;
        mWebConsumer = webConsumer;
        mDuplicateCallPriorityProvider = duplicateCallPriorityProvider != null ?
            duplicateCallPriorityProvider : DuplicateCallPriorityProvider.NONE;
        mDuplicateCompletionWatchdogMilliseconds = Math.max(0L, duplicateCompletionWatchdogMilliseconds);
        mDuplicateCompletionOrphanCeilingMilliseconds = Math.max(mDuplicateCompletionWatchdogMilliseconds,
            duplicateCompletionOrphanCeilingMilliseconds);
        mIngress = new AudioCallIngressQueue(ingressCapacity, lifecycleIngressReserve);
        mWatchdogScheduler = new ScheduledThreadPoolExecutor(1,
            new ObserverThreadFactory("audio-call watchdog"));
        mWatchdogScheduler.setRemoveOnCancelPolicy(true);
        mWatchdogScheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        mWorker = new ObserverThreadFactory("audio-call coordinator").newThread(this::runWorker);
        mWorker.start();
    }

    private static long deriveOrphanCeilingMilliseconds(long watchdogMilliseconds)
    {
        long normalizedWatchdog = Math.max(0L, watchdogMilliseconds);

        try
        {
            return Math.max(TimeUnit.SECONDS.toMillis(1), Math.multiplyExact(normalizedWatchdog, 10L));
        }
        catch(ArithmeticException _)
        {
            return Long.MAX_VALUE;
        }
    }

    @Override
    public void receive(AudioCallEvent event)
    {
        if(event == null || event.snapshot() == null || event.callId() == null || !mAccepting)
        {
            return;
        }

        mActiveReceives.incrementAndGet();

        try
        {
            //Close the race where shutdown begins after the initial fast-path check but before this producer is
            //registered. The worker remains alive while a registered receive is in progress.
            if(!mAccepting)
            {
                return;
            }

            AudioCallId callId = event.callId();

            if(mAbortAllPending.get())
            {
                mDroppedIngressCount.incrementAndGet();
                mDroppedOperationCount.incrementAndGet();
                return;
            }

            if(mOverflowedCallIds.contains(callId))
            {
                mDroppedIngressCount.incrementAndGet();
                mDroppedOperationCount.incrementAndGet();

                if(event.eventType() == AudioCallEventType.CALL_COMPLETED)
                {
                    offerAbort(callId, mIngressGeneration.get());
                    clearOverflowMarker(callId);
                }

                return;
            }

            long generation = mIngressGeneration.get();
            boolean lifecycle = isLifecycleEvent(event);

            if(mIngress.offer(EVENT, lifecycle, event, 0L, generation))
            {
                mAcceptedIngressCount.incrementAndGet();
                signalWorker();
            }
            else if(lifecycle)
            {
                mDroppedIngressCount.incrementAndGet();
                mDroppedLifecycleCount.incrementAndGet();
                mDroppedOperationCount.incrementAndGet();
                requestAbortAll();
            }
            else
            {
                mDroppedIngressCount.incrementAndGet();
                mDroppedOperationCount.incrementAndGet();

                if(markOverflowed(callId))
                {
                    mAbortedCallCount.incrementAndGet();
                    offerAbort(callId, generation);
                }
            }
        }
        finally
        {
            mActiveReceives.decrementAndGet();

            if(!mAccepting)
            {
                signalWorker();
            }
        }
    }

    public synchronized void dispose()
    {
        if(!mAccepting)
        {
            return;
        }

        mAccepting = false;
        mWatchdogScheduler.shutdownNow();
        signalWorker();

        try
        {
            mWorker.join(SHUTDOWN_DRAIN_MILLISECONDS);
        }
        catch(InterruptedException _)
        {
            Thread.currentThread().interrupt();
        }

        if(mWorker.isAlive())
        {
            mDisposed = true;
            mWorker.interrupt();
            mLog.warn("Audio-call coordinator did not drain within {} milliseconds; remaining calls were discarded",
                SHUTDOWN_DRAIN_MILLISECONDS);
        }
    }

    private static boolean isLifecycleEvent(AudioCallEvent event)
    {
        return event.eventType() == AudioCallEventType.CALL_CREATED ||
            event.eventType() == AudioCallEventType.CALL_COMPLETED;
    }

    private void offerAbort(AudioCallId callId, long generation)
    {
        if(!mDisposed && callId != null)
        {
            if(mIngress.offer(ABORT, true, callId, 0L, generation))
            {
                mAcceptedIngressCount.incrementAndGet();
                signalWorker();
            }
            else
            {
                mDroppedLifecycleCount.incrementAndGet();
                mDroppedOperationCount.incrementAndGet();
                requestAbortAll();
            }
        }
    }

    private boolean markOverflowed(AudioCallId callId)
    {
        if(callId == null || mOverflowedCallIds.contains(callId))
        {
            return false;
        }

        int count = mOverflowedCallCount.get();

        while(count < MAXIMUM_TRACKED_OVERFLOWED_CALLS)
        {
            if(mOverflowedCallCount.compareAndSet(count, count + 1))
            {
                if(mOverflowedCallIds.add(callId))
                {
                    return true;
                }

                mOverflowedCallCount.decrementAndGet();
                return false;
            }

            count = mOverflowedCallCount.get();
        }

        requestAbortAll();
        return false;
    }

    private void clearOverflowMarker(AudioCallId callId)
    {
        if(callId != null && mOverflowedCallIds.remove(callId))
        {
            mOverflowedCallCount.decrementAndGet();
        }
    }

    private void clearOverflowMarkers()
    {
        mOverflowedCallIds.clear();
        mOverflowedCallCount.set(0);
    }

    private void requestAbortAll()
    {
        if(!mDisposed && mAbortAllPending.compareAndSet(false, true))
        {
            mIngressGeneration.incrementAndGet();
            signalWorker();
        }
    }

    private void signalWorker()
    {
        LockSupport.unpark(mWorker);
    }

    private void runWorker()
    {
        try
        {
            while(!mDisposed && (mAccepting || mActiveReceives.get() > 0 || mIngress.size() > 0 ||
                mAbortAllPending.get()))
            {
                if(mAbortAllPending.get())
                {
                    abortAllState();
                    mIngress.clear();
                    clearOverflowMarkers();
                    mAbortAllPending.set(false);
                }

                int drained = 0;
                AudioCallIngressQueue.Entry entry;

                while(!mDisposed && drained < mIngress.capacity() && (entry = mIngress.poll()) != null)
                {
                    try
                    {
                        if(entry.generation() == mIngressGeneration.get())
                        {
                            process(entry);
                            drainPendingFanouts();
                        }
                    }
                    catch(Throwable throwable)
                    {
                        rethrowFatal(throwable);
                        mLog.error("Error processing audio-call coordinator command", throwable);
                    }

                    drained++;

                    if(mAbortAllPending.get())
                    {
                        break;
                    }
                }

                if(drained == 0 && !mAbortAllPending.get())
                {
                    LockSupport.parkNanos(this, TimeUnit.MILLISECONDS.toNanos(50));
                }
            }
        }
        finally
        {
            abortAllState();
            mIngress.clear();
            clearOverflowMarkers();
            mDisposed = true;
        }
    }

    private void process(AudioCallIngressQueue.Entry entry)
    {
        switch(entry.operation())
        {
            case EVENT -> process((AudioCallEvent)entry.payload());
            case ABORT -> abortCall((AudioCallId)entry.payload());
            case WATCHDOG -> processWatchdog(entry.value());
            default -> mLog.warn("Ignoring unknown audio-call coordinator operation [{}]", entry.operation());
        }
    }

    private void processWatchdog(long groupId)
    {
        DuplicateGroup group = mDuplicateGroups.get(groupId);

        if(group != null)
        {
            group.resolutionWatchdog = null;
            evaluateResolutionWatchdog(groupId);
        }
    }

    private void abortAllState()
    {
        for(DuplicateGroup group: mDuplicateGroups.values())
        {
            cancelResolutionWatchdog(group);
        }

        mCalls.clear();
        mDuplicateGroups.clear();
        mPendingFanouts.clear();
    }

    private void abortCall(AudioCallId callId)
    {
        ManagedAudioCall context = mCalls.remove(callId);

        if(context == null)
        {
            return;
        }

        context.audioBuffers.clear();

        if(context.duplicateGroupId == null)
        {
            return;
        }

        DuplicateGroup group = mDuplicateGroups.remove(context.duplicateGroupId);

        if(group == null)
        {
            return;
        }

        cancelResolutionWatchdog(group);

        //Dissolve an overloaded cohort. Any healthy live members become independent calls and can be grouped again
        //by their next accepted update; already completed healthy candidates are released once as independent calls.
        for(AudioCallId activeCallId: group.activeMemberCallIds)
        {
            if(!activeCallId.equals(callId))
            {
                ManagedAudioCall active = mCalls.get(activeCallId);

                if(active != null)
                {
                    active.duplicateGroupId = null;
                    setDuplicateState(active, false);
                }
            }
        }

        List<CompletedCandidate> candidates = new ArrayList<>(group.completedCandidates.values());
        candidates.sort((first, second) -> compareElectionOrder(first.completedAudioCall.snapshot(),
            first.registrationOrdinal, second.completedAudioCall.snapshot(), second.registrationOrdinal));

        for(CompletedCandidate candidate: candidates)
        {
            if(!candidate.completedAudioCall.snapshot().callId().equals(callId))
            {
                CompletedAudioCall call = candidate.completedAudioCall;

                if(call.snapshot().duplicate())
                {
                    call = new CompletedAudioCall(call.snapshot().withDuplicate(false), call.audioBuffers(),
                        call.resolvedPolicy());
                }

                queueFanout(mergeOutputPolicy(call, List.of(call.snapshot())));
            }
        }
    }

    private void drainPendingFanouts()
    {
        for(CompletedAudioCall completedAudioCall: mPendingFanouts)
        {
            deliver("recording", mRecordingConsumer, completedAudioCall);
            deliver("streaming", mStreamingConsumer, completedAudioCall);
            deliver("browser", mWebConsumer, completedAudioCall);
        }

        mPendingFanouts.clear();
    }

    private void deliver(String name, Consumer<CompletedAudioCall> consumer, CompletedAudioCall completedAudioCall)
    {
        if(consumer == null || completedAudioCall == null)
        {
            return;
        }

        try
        {
            consumer.accept(completedAudioCall);
        }
        catch(Throwable throwable)
        {
            rethrowFatal(throwable);
            mLog.warn("Completed-call {} consumer failed", name, throwable);
        }
    }

    private void queueFanout(CompletedAudioCall completedAudioCall)
    {
        if(completedAudioCall != null)
        {
            mPendingFanouts.add(completedAudioCall);
        }
    }

    private static void rethrowFatal(Throwable throwable)
    {
        if(throwable instanceof VirtualMachineError error)
        {
            throw error;
        }
    }

    private void process(AudioCallEvent event)
    {
        AudioCallSnapshot incomingSnapshot = event.snapshot();

        if(incomingSnapshot == null || incomingSnapshot.callId() == null)
        {
            return;
        }

        if(mOverflowedCallIds.contains(incomingSnapshot.callId()))
        {
            abortCall(incomingSnapshot.callId());

            if(event.eventType() == AudioCallEventType.CALL_COMPLETED)
            {
                clearOverflowMarker(incomingSnapshot.callId());
            }

            return;
        }

        ManagedAudioCall context = mCalls.get(incomingSnapshot.callId());

        if(context == null)
        {
            if(mCalls.size() >= MAXIMUM_ACTIVE_CALLS)
            {
                if(markOverflowed(incomingSnapshot.callId()))
                {
                    mAbortedCallCount.incrementAndGet();
                }

                return;
            }

            context = new ManagedAudioCall(incomingSnapshot, mNextRegistrationOrdinal++);
            mCalls.put(incomingSnapshot.callId(), context);
        }

        // Ownership boundary:
        // 1) producers emit immutable AudioCallEvent/AudioCallSnapshot objects
        // 2) the coordinator is the only writer of live call state and completed-call audio buffers
        // 3) recording, configured streaming providers, and browser playback consume the one elected completed
        //    immutable call
        boolean duplicate = context.snapshot != null && context.snapshot.duplicate();
        context.snapshot = incomingSnapshot.duplicate() == duplicate ? incomingSnapshot :
            incomingSnapshot.withDuplicate(duplicate);

        if(isProgressEvent(event))
        {
            context.lastProgressNanos = System.nanoTime();
        }

        float[] audioFrame = event.audioFrameView();

        if(audioFrame != null)
        {
            if(context.audioSampleCount > MAXIMUM_AUDIO_SAMPLES_PER_CALL - audioFrame.length)
            {
                if(markOverflowed(incomingSnapshot.callId()))
                {
                    mAbortedCallCount.incrementAndGet();
                }

                abortCall(incomingSnapshot.callId());
                return;
            }

            context.audioBuffers.add(audioFrame);
            context.audioSampleCount += audioFrame.length;
        }

        updateDuplicateState(context);

        if(event.eventType() == AudioCallEventType.CALL_COMPLETED)
        {
            CompletedAudioCall completedAudioCall =
                new CompletedAudioCall(context.snapshot, List.copyOf(context.audioBuffers));

            try
            {
                handleResolvedCompletion(context, completedAudioCall);
            }
            finally
            {
                mCalls.remove(context.snapshot.callId());
                finishDuplicateGroupMember(context);
                clearOverflowMarker(context.snapshot.callId());
            }
        }
    }

    private boolean isProgressEvent(AudioCallEvent event)
    {
        return switch(event.eventType())
        {
            case CALL_CREATED, ACTIVITY, METADATA_UPDATED, BURST_STARTED, BURST_ENDED, AUDIO_FRAME -> true;
            case DUPLICATE_UPDATED, CALL_COMPLETED -> false;
        };
    }

    /**
     * Reconciles explicit, non-transitive duplicate cohorts. Every member has to match every other member directly;
     * a talkgroup match followed by a radio match cannot bridge two otherwise unrelated calls. A cohort is sealed as
     * soon as one member completes, so a later transmission cannot be absorbed by a lingering old duplicate.
     */
    private void updateDuplicateState(ManagedAudioCall changedCall)
    {
        if(changedCall == null || changedCall.snapshot == null)
        {
            return;
        }

        if(!mCallManagementProvider.isDuplicateCallDetectionEnabled())
        {
            releaseAllDuplicateGroups();
            return;
        }

        DuplicateGroup changedGroup = getDuplicateGroup(changedCall);

        if(changedGroup != null)
        {
            changedGroup.memberSnapshots.put(changedCall.snapshot.callId(), changedCall.snapshot);

            if(changedCall.snapshot.complete())
            {
                changedGroup.sealed = true;
            }
        }

        String system = getSystem(changedCall.snapshot);

        if(system == null)
        {
            return;
        }

        List<ManagedAudioCall> ungroupedCalls = new ArrayList<>();

        for(ManagedAudioCall call : mCalls.values())
        {
            if(call.snapshot != null && !call.snapshot.complete() && !call.snapshot.encrypted() &&
                call.duplicateGroupId == null && system.equals(getSystem(call.snapshot)) &&
                call.snapshot.identifierCollection() != null)
            {
                ungroupedCalls.add(call);
            }
        }

        if(ungroupedCalls.isEmpty())
        {
            return;
        }

        //First attach ungrouped calls to an open cohort only when they directly match every existing member.
        ungroupedCalls.sort(this::compareLiveCandidates);
        List<ManagedAudioCall> stillUngrouped = new ArrayList<>();

        for(ManagedAudioCall call : ungroupedCalls)
        {
            DuplicateGroup matchingGroup = mDuplicateGroups.values().stream()
                .filter(group -> isOpenDuplicateGroup(group, system) &&
                    isPairwiseCompatible(group, call.snapshot))
                .min(this::compareDuplicateGroupAnchors).orElse(null);

            if(matchingGroup != null)
            {
                addToDuplicateGroup(matchingGroup, call);
            }
            else
            {
                stillUngrouped.add(call);
            }
        }

        //Create new cohorts greedily in explicit election order. Each cohort must remain a pairwise clique.
        while(stillUngrouped.size() > 1)
        {
            ManagedAudioCall anchor = stillUngrouped.removeFirst();
            List<ManagedAudioCall> matches = new ArrayList<>();
            List<AudioCallSnapshot> clique = new ArrayList<>();
            clique.add(anchor.snapshot);

            for(ManagedAudioCall candidate : stillUngrouped)
            {
                if(clique.stream().allMatch(member -> isDuplicate(member, candidate.snapshot)))
                {
                    matches.add(candidate);
                    clique.add(candidate.snapshot);
                }
            }

            if(!matches.isEmpty())
            {
                DuplicateGroup group = new DuplicateGroup(mNextDuplicateGroupOrdinal++, anchor.snapshot,
                    anchor.registrationOrdinal);
                mDuplicateGroups.put(group.groupId, group);
                addToDuplicateGroup(group, anchor);

                for(ManagedAudioCall match : matches)
                {
                    addToDuplicateGroup(group, match);
                }

                stillUngrouped.removeAll(matches);
            }
        }
    }

    private boolean isPairwiseCompatible(DuplicateGroup group, AudioCallSnapshot candidate)
    {
        return group.memberSnapshots.values().stream().allMatch(member -> isDuplicate(member, candidate));
    }

    private DuplicateGroup getDuplicateGroup(ManagedAudioCall call)
    {
        return call != null && call.duplicateGroupId != null ?
            mDuplicateGroups.get(call.duplicateGroupId) : null;
    }

    private boolean isOpenDuplicateGroup(DuplicateGroup group, String system)
    {
        if(group == null || group.sealed || group.resolutionDecisionMade ||
            !system.equals(getSystem(group.anchorSnapshot)))
        {
            return false;
        }

        ManagedAudioCall anchor = mCalls.get(group.liveWinnerCallId);
        return anchor != null && anchor.snapshot != null && !anchor.snapshot.complete() &&
            !anchor.snapshot.encrypted();
    }

    private int compareDuplicateGroupAnchors(DuplicateGroup first, DuplicateGroup second)
    {
        int comparison = compareElectionOrder(first.anchorSnapshot, first.anchorRegistrationOrdinal,
            second.anchorSnapshot, second.anchorRegistrationOrdinal);

        if(comparison == 0)
        {
            comparison = Long.compare(first.groupId, second.groupId);
        }

        return comparison;
    }

    private void addToDuplicateGroup(DuplicateGroup group, ManagedAudioCall call)
    {
        if(group == null || call == null || group.sealed || call.duplicateGroupId != null)
        {
            return;
        }

        call.duplicateGroupId = group.groupId;
        group.memberCallIds.add(call.snapshot.callId());
        group.activeMemberCallIds.add(call.snapshot.callId());
        group.memberSnapshots.put(call.snapshot.callId(), call.snapshot);

        setDuplicateState(call, !group.liveWinnerCallId.equals(call.snapshot.callId()));
    }

    private void setDuplicateState(ManagedAudioCall call, boolean duplicate)
    {
        if(call.snapshot.duplicate() != duplicate)
        {
            call.snapshot = call.snapshot.withDuplicate(duplicate);
        }
    }

    private int compareLiveCandidates(ManagedAudioCall first, ManagedAudioCall second)
    {
        return compareElectionOrder(first.snapshot, first.registrationOrdinal, second.snapshot,
            second.registrationOrdinal);
    }

    int compareElectionOrder(AudioCallSnapshot first, long firstRegistrationOrdinal,
                             AudioCallSnapshot second, long secondRegistrationOrdinal)
    {
        int comparison = Integer.compare(getConfiguredSourcePriority(first), getConfiguredSourcePriority(second));

        if(comparison == 0)
        {
            comparison = Long.compare(normalizeStartTimestamp(first), normalizeStartTimestamp(second));
        }

        if(comparison == 0)
        {
            comparison = Long.compare(firstRegistrationOrdinal, secondRegistrationOrdinal);
        }

        if(comparison == 0)
        {
            comparison = compareCallIds(first.callId(), second.callId());
        }

        return comparison;
    }

    private int getConfiguredSourcePriority(AudioCallSnapshot snapshot)
    {
        String sourceGuid = getStableSourceGuid(snapshot);
        return sourceGuid != null ? mDuplicateCallPriorityProvider.getPriority(sourceGuid) : Integer.MAX_VALUE;
    }

    private long normalizeStartTimestamp(AudioCallSnapshot snapshot)
    {
        return snapshot != null && snapshot.startTimestamp() > 0 ? snapshot.startTimestamp() : Long.MAX_VALUE;
    }

    private int compareCallIds(AudioCallId first, AudioCallId second)
    {
        if(first == second)
        {
            return 0;
        }
        else if(first == null)
        {
            return 1;
        }
        else if(second == null)
        {
            return -1;
        }

        int comparison = Long.compare(first.producerId(), second.producerId());

        if(comparison == 0)
        {
            comparison = Long.compare(first.sequence(), second.sequence());
        }

        if(comparison == 0)
        {
            comparison = Integer.compare(first.timeslot(), second.timeslot());
        }

        return comparison;
    }

    private String getStableSourceGuid(AudioCallSnapshot snapshot)
    {
        if(snapshot == null || snapshot.identifierCollection() == null)
        {
            return null;
        }

        Identifier<?> identifier = snapshot.identifierCollection()
            .getIdentifier(IdentifierClass.CONFIGURATION, Form.RADRES_GUID, Role.ANY);

        return identifier != null && identifier.getValue() != null ? identifier.getValue().toString() : null;
    }

    /**
     * Sends a single call through immediately. Once the first member of a duplicate cohort completes, the cohort is
     * sealed and one bounded election supplies the same resolved logical call to recording, configured streaming, and
     * browser playback. The completion watchdog follows coordinator-local member progress and only treats an inactive
     * member as orphaned; a separate ceiling bounds a producer that reports progress forever without completing.
     */
    private void handleResolvedCompletion(ManagedAudioCall context, CompletedAudioCall completedAudioCall)
    {
        DuplicateGroup group = context.duplicateGroupId != null ?
            mDuplicateGroups.get(context.duplicateGroupId) : null;

        if(group == null || !mCallManagementProvider.isDuplicateCallDetectionEnabled())
        {
            CompletedAudioCall resolvedCall =
                mergeOutputPolicy(completedAudioCall, List.of(completedAudioCall.snapshot()));
            queueFanout(resolvedCall);
            return;
        }

        if(group.resolutionDecisionMade)
        {
            return;
        }

        group.sealed = true;
        group.completedCandidates.put(completedAudioCall.snapshot().callId(),
            new CompletedCandidate(completedAudioCall, context.registrationOrdinal));

        if(!group.resolutionWatchdogStarted)
        {
            group.resolutionWatchdogStarted = true;
            group.resolutionOrphanDeadlineNanos = safeAddNanos(System.nanoTime(),
                TimeUnit.MILLISECONDS.toNanos(mDuplicateCompletionOrphanCeilingMilliseconds));
            scheduleResolutionWatchdog(group,
                TimeUnit.MILLISECONDS.toNanos(mDuplicateCompletionWatchdogMilliseconds));
        }
    }

    private void scheduleResolutionWatchdog(DuplicateGroup group, long delayNanos)
    {
        if(mDisposed)
        {
            return;
        }

        long groupId = group.groupId;

        try
        {
            long generation = mIngressGeneration.get();
            group.resolutionWatchdog = mWatchdogScheduler.schedule(() -> {
                if(!mDisposed)
                {
                    if(mIngress.offer(WATCHDOG, true, null, groupId, generation))
                    {
                        mAcceptedIngressCount.incrementAndGet();
                        signalWorker();
                    }
                    else
                    {
                        mDroppedLifecycleCount.incrementAndGet();
                        mDroppedOperationCount.incrementAndGet();
                        requestAbortAll();
                    }
                }
            }, Math.max(0L, delayNanos), TimeUnit.NANOSECONDS);
        }
        catch(RejectedExecutionException _)
        {
            group.resolutionWatchdog = null;
        }
    }

    private void evaluateResolutionWatchdog(long groupId)
    {
        if(!mCallManagementProvider.isDuplicateCallDetectionEnabled())
        {
            releaseAllDuplicateGroups();
            return;
        }

        DuplicateGroup group = mDuplicateGroups.get(groupId);

        if(group == null || group.resolutionDecisionMade)
        {
            return;
        }

        if(group.completedMemberCallIds.containsAll(group.memberCallIds))
        {
            flushResolvedGroup(groupId);
            return;
        }

        long now = System.nanoTime();

        if(now >= group.resolutionOrphanDeadlineNanos)
        {
            flushResolvedGroup(groupId);
            return;
        }

        long latestProgress = Long.MIN_VALUE;

        for(AudioCallId activeMemberCallId : group.activeMemberCallIds)
        {
            ManagedAudioCall activeMember = mCalls.get(activeMemberCallId);

            if(activeMember != null)
            {
                latestProgress = Math.max(latestProgress, activeMember.lastProgressNanos);
            }
        }

        long inactivityDeadline = latestProgress != Long.MIN_VALUE ?
            safeAddNanos(latestProgress,
                TimeUnit.MILLISECONDS.toNanos(mDuplicateCompletionWatchdogMilliseconds)) : now;

        if(now < inactivityDeadline)
        {
            long nextDeadline = Math.min(inactivityDeadline, group.resolutionOrphanDeadlineNanos);
            scheduleResolutionWatchdog(group, Math.max(0L, nextDeadline - now));
        }
        else
        {
            flushResolvedGroup(groupId);
        }
    }

    private long safeAddNanos(long timestamp, long duration)
    {
        if(duration > 0L && timestamp > Long.MAX_VALUE - duration)
        {
            return Long.MAX_VALUE;
        }

        return timestamp + duration;
    }

    private void flushResolvedGroup(long groupId)
    {
        if(!mCallManagementProvider.isDuplicateCallDetectionEnabled())
        {
            releaseAllDuplicateGroups();
            return;
        }

        DuplicateGroup group = mDuplicateGroups.get(groupId);

        if(group == null || group.resolutionDecisionMade)
        {
            return;
        }

        group.resolutionDecisionMade = true;
        cancelResolutionWatchdog(group);
        long expectedFrameCount = getCohortExpectedFrameCount(group);
        CompletedCandidate winner = group.completedCandidates.values().stream()
            .min((first, second) -> compareResolvedCandidates(first, second, expectedFrameCount)).orElse(null);
        group.completedCandidates.clear();

        try
        {
            if(winner != null)
            {
                AudioCallSnapshot winnerSnapshot = winner.completedAudioCall.snapshot();
                CompletedAudioCall measuredWinner = new CompletedAudioCall(
                    winnerSnapshot.withVoiceCallQuality(
                        winnerSnapshot.voiceCallQuality().withExpectedFrameCount(expectedFrameCount)),
                    winner.completedAudioCall.audioBuffers(), winner.completedAudioCall.resolvedPolicy());
                CompletedAudioCall resolvedCall =
                    mergeOutputPolicy(measuredWinner, group.memberSnapshots.values());
                queueFanout(resolvedCall);
            }
        }
        finally
        {
            cleanupDuplicateGroup(group);
        }
    }

    /**
     * Releases every live and completed call when detection is disabled. Pending calls are cleared of their old
     * duplicate flag so that the downstream streaming-suppression preference cannot discard them.
     */
    private void releaseAllDuplicateGroups()
    {
        for(ManagedAudioCall call : mCalls.values())
        {
            if(call.duplicateGroupId != null)
            {
                setDuplicateState(call, false);
                call.duplicateGroupId = null;
            }
        }

        for(DuplicateGroup group : new ArrayList<>(mDuplicateGroups.values()))
        {
            if(!group.resolutionDecisionMade && !group.completedCandidates.isEmpty())
            {
                sendAllResolvedCandidates(group, true);
            }
            else
            {
                cancelResolutionWatchdog(group);
            }
        }

        mDuplicateGroups.clear();
    }

    private void sendAllResolvedCandidates(DuplicateGroup group, boolean clearDuplicate)
    {
        group.resolutionDecisionMade = true;
        cancelResolutionWatchdog(group);
        List<CompletedCandidate> candidates = new ArrayList<>(group.completedCandidates.values());
        candidates.sort((first, second) -> compareElectionOrder(first.completedAudioCall.snapshot(),
            first.registrationOrdinal, second.completedAudioCall.snapshot(), second.registrationOrdinal));
        group.completedCandidates.clear();

        for(CompletedCandidate candidate : candidates)
        {
            CompletedAudioCall completedCall = candidate.completedAudioCall;

            if(clearDuplicate && completedCall.snapshot().duplicate())
            {
                completedCall = new CompletedAudioCall(completedCall.snapshot().withDuplicate(false),
                    completedCall.audioBuffers());
            }

            CompletedAudioCall resolvedCall =
                mergeOutputPolicy(completedCall, List.of(completedCall.snapshot()));
            queueFanout(resolvedCall);
        }
    }

    private void cancelResolutionWatchdog(DuplicateGroup group)
    {
        if(group.resolutionWatchdog != null)
        {
            group.resolutionWatchdog.cancel(false);
            group.resolutionWatchdog = null;
        }
    }

    /**
     * Final duplicate election uses only voice completeness, repeated audio, and normalized FEC corrections, in that
     * order.  The remaining fallback is mechanical so exact ties are reproducible.
     */
    private int compareResolvedCandidates(CompletedCandidate first, CompletedCandidate second,
                                          long expectedFrameCount)
    {
        VoiceQualityScore firstScore = getVoiceQualityScore(first.completedAudioCall, expectedFrameCount);
        VoiceQualityScore secondScore = getVoiceQualityScore(second.completedAudioCall, expectedFrameCount);
        int comparison = Double.compare(firstScore.missingAndConcealedRate(),
            secondScore.missingAndConcealedRate());

        if(comparison == 0)
        {
            comparison = Double.compare(firstScore.repeatedRate(), secondScore.repeatedRate());
        }

        if(comparison == 0)
        {
            comparison = Double.compare(firstScore.normalizedFecCorrectionRate(),
                secondScore.normalizedFecCorrectionRate());
        }

        if(comparison == 0)
        {
            comparison = compareResolvedFallback(first, second);
        }

        return comparison;
    }

    private long getCohortExpectedFrameCount(DuplicateGroup group)
    {
        long earliestStart = Long.MAX_VALUE;
        long latestActivity = Long.MIN_VALUE;
        long observedMaximum = 0L;

        for(AudioCallSnapshot snapshot : group.memberSnapshots.values())
        {
            if(snapshot != null)
            {
                if(snapshot.startTimestamp() > 0)
                {
                    earliestStart = Math.min(earliestStart, snapshot.startTimestamp());
                }

                latestActivity = Math.max(latestActivity, snapshot.lastActivityTimestamp());
                observedMaximum = Math.max(observedMaximum, snapshot.voiceCallQuality().observedFrameCount());
            }
        }

        long elapsedFrames = earliestStart != Long.MAX_VALUE && latestActivity >= earliestStart ?
            VoiceCallQuality.expectedFrameCount(earliestStart, latestActivity) : 1L;
        return Math.max(elapsedFrames, observedMaximum);
    }

    private VoiceQualityScore getVoiceQualityScore(CompletedAudioCall call, long expectedFrameCount)
    {
        AudioCallSnapshot snapshot = call != null ? call.snapshot() : null;
        VoiceCallQuality quality = snapshot != null ? snapshot.voiceCallQuality() : VoiceCallQuality.EMPTY;
        long observedFrames = quality.observedFrameCount();
        long expectedFrames = Math.max(Math.max(1L, expectedFrameCount), observedFrames);
        long missingFrames = expectedFrames - observedFrames;
        double missingAndConcealedRate =
            (double)(missingFrames + quality.concealedFrameCount()) / expectedFrames;
        double repeatedRate = (double)quality.repeatedFrameCount() / expectedFrames;
        double normalizedFecCorrectionRate = quality.fecProtectedBitCount() > 0 ?
            (double)quality.fecErrorCount() / quality.fecProtectedBitCount() : 1.0d;
        return new VoiceQualityScore(missingAndConcealedRate, repeatedRate, normalizedFecCorrectionRate);
    }

    private int compareResolvedFallback(CompletedCandidate first, CompletedCandidate second)
    {
        AudioCallSnapshot firstSnapshot = first.completedAudioCall.snapshot();
        AudioCallSnapshot secondSnapshot = second.completedAudioCall.snapshot();
        int comparison =
            Long.compare(normalizeStartTimestamp(firstSnapshot), normalizeStartTimestamp(secondSnapshot));

        if(comparison == 0)
        {
            comparison = Long.compare(first.registrationOrdinal, second.registrationOrdinal);
        }

        if(comparison == 0)
        {
            comparison = compareCallIds(firstSnapshot.callId(), secondSnapshot.callId());
        }

        return comparison;
    }

    /**
     * Applies the union of every duplicate member's output policy to the elected call while retaining only the
     * winner's identifiers, RF/site metadata, and audio.
     */
    private CompletedAudioCall mergeOutputPolicy(CompletedAudioCall winner,
                                                 Iterable<AudioCallSnapshot> memberSnapshots)
    {
        AudioCallSnapshot winnerSnapshot = winner.snapshot();
        List<AudioCallSnapshot> cohortSnapshots = new ArrayList<>();
        Map<String, BroadcastChannel> broadcastChannels = new LinkedHashMap<>();

        if(memberSnapshots != null)
        {
            for(AudioCallSnapshot snapshot : memberSnapshots)
            {
                if(snapshot == null)
                {
                    continue;
                }

                cohortSnapshots.add(snapshot);

                if(snapshot.broadcastChannels() != null)
                {
                    for(BroadcastChannel broadcastChannel : snapshot.broadcastChannels())
                    {
                        String destinationId = broadcastChannel != null ?
                            normalizeText(broadcastChannel.getChannelName()) : null;

                        if(destinationId != null)
                        {
                            broadcastChannels.putIfAbsent(destinationId, broadcastChannel);
                        }
                    }
                }
            }
        }

        if(cohortSnapshots.isEmpty())
        {
            cohortSnapshots.add(winnerSnapshot);
        }

        ResolvedCallPolicy resolvedPolicy = ResolvedCallPolicy.capture(cohortSnapshots);
        AudioCallRecordingMetadata mergedMetadata =
            mergeRecordingMetadata(winnerSnapshot, cohortSnapshots,
                resolvedPolicy.destinationTalkgroupRecordEnabled());
        AudioCallSnapshot mergedSnapshot = new AudioCallSnapshot(winnerSnapshot.callId(),
            winnerSnapshot.linkedCallId(), winnerSnapshot.aliasList(), winnerSnapshot.identifierCollection(),
            Set.copyOf(broadcastChannels.values()), winnerSnapshot.startTimestamp(),
            winnerSnapshot.lastActivityTimestamp(), winnerSnapshot.burstCount(), winnerSnapshot.burstGeneration(),
            winnerSnapshot.lastBurstStartTimestamp(), winnerSnapshot.lastBurstEndTimestamp(),
            winnerSnapshot.burstActive(), winnerSnapshot.complete(), winnerSnapshot.encrypted(),
            resolvedPolicy.recordAudio(), false, mergedMetadata,
            winnerSnapshot.voiceCallQuality());
        return new CompletedAudioCall(mergedSnapshot, winner.audioBuffers(), resolvedPolicy);
    }

    /**
     * Retains the elected receiver copy's system/site/channel/source metadata, while ensuring that a recording
     * decision contributed by another cohort member carries that member's matching destination into the recording.
     * Otherwise setting only the winner's record flag could mislabel a recording with an unrelated talkgroup.
     */
    private AudioCallRecordingMetadata mergeRecordingMetadata(AudioCallSnapshot winnerSnapshot,
                                                              List<AudioCallSnapshot> cohortSnapshots,
                                                              boolean destinationRecordEnabled)
    {
        AudioCallRecordingMetadata winnerMetadata =
            winnerSnapshot != null ? winnerSnapshot.recordingMetadata() : null;

        if(!destinationRecordEnabled)
        {
            return winnerMetadata;
        }

        AudioCallSnapshot selectedSnapshot =
            selectDestinationRecordingSnapshot(winnerSnapshot, cohortSnapshots);
        AudioCallRecordingMetadata selectedMetadata =
            selectedSnapshot != null ? selectedSnapshot.recordingMetadata() : null;

        if(selectedMetadata == null)
        {
            return winnerMetadata;
        }

        if(winnerMetadata == null)
        {
            return selectedMetadata;
        }

        return new AudioCallRecordingMetadata(winnerMetadata.systemName(), winnerMetadata.systemIdentity(),
            winnerMetadata.siteName(), winnerMetadata.siteIdentity(), winnerMetadata.channelName(),
            winnerMetadata.channelIdentity(), selectedMetadata.aliasListName(),
            selectedMetadata.destinationProtocol(), selectedMetadata.destinationValue(),
            selectedMetadata.destinationIdentity(), selectedMetadata.destinationAlias(),
            selectedMetadata.destinationMatcherIdentity(), true, winnerMetadata.sourceProtocol(),
            winnerMetadata.sourceValue(), winnerMetadata.sourceAlias());
    }

    /**
     * Prefers a record-enabled copy for the winner's logical destination. A stable destination tuple and call ID
     * provide a deterministic fallback when a radio-ID duplicate cohort contains different talkgroups.
     */
    private AudioCallSnapshot selectDestinationRecordingSnapshot(AudioCallSnapshot winnerSnapshot,
                                                                 List<AudioCallSnapshot> cohortSnapshots)
    {
        AudioCallRecordingMetadata winnerMetadata =
            winnerSnapshot != null ? winnerSnapshot.recordingMetadata() : null;
        AudioCallSnapshot selected = null;

        if(cohortSnapshots != null)
        {
            for(AudioCallSnapshot candidate : cohortSnapshots)
            {
                AudioCallRecordingMetadata candidateMetadata =
                    candidate != null ? candidate.recordingMetadata() : null;

                if(candidateMetadata == null || !candidateMetadata.destinationTalkgroupRecordEnabled())
                {
                    continue;
                }

                if(selected == null ||
                    compareDestinationRecordingSnapshots(candidate, selected, winnerMetadata) < 0)
                {
                    selected = candidate;
                }
            }
        }

        return selected;
    }

    private int compareDestinationRecordingSnapshots(AudioCallSnapshot first, AudioCallSnapshot second,
                                                     AudioCallRecordingMetadata winnerMetadata)
    {
        AudioCallRecordingMetadata firstMetadata = first.recordingMetadata();
        AudioCallRecordingMetadata secondMetadata = second.recordingMetadata();
        int comparison = Boolean.compare(!isSameLogicalDestination(firstMetadata, winnerMetadata),
            !isSameLogicalDestination(secondMetadata, winnerMetadata));

        if(comparison == 0)
        {
            comparison = compareNullableText(firstMetadata.destinationProtocol(),
                secondMetadata.destinationProtocol(), true);
        }

        if(comparison == 0)
        {
            comparison = compareNullableText(firstMetadata.destinationValue(),
                secondMetadata.destinationValue(), false);
        }

        if(comparison == 0)
        {
            comparison = compareNullableText(firstMetadata.destinationIdentity(),
                secondMetadata.destinationIdentity(), false);
        }

        if(comparison == 0)
        {
            comparison = compareNullableText(firstMetadata.destinationMatcherIdentity(),
                secondMetadata.destinationMatcherIdentity(), false);
        }

        if(comparison == 0)
        {
            comparison = compareNullableText(firstMetadata.destinationAlias(),
                secondMetadata.destinationAlias(), false);
        }

        if(comparison == 0)
        {
            comparison = compareNullableText(firstMetadata.aliasListName(),
                secondMetadata.aliasListName(), false);
        }

        if(comparison == 0)
        {
            comparison = compareCallIds(first.callId(), second.callId());
        }

        return comparison;
    }

    private boolean isSameLogicalDestination(AudioCallRecordingMetadata first,
                                             AudioCallRecordingMetadata second)
    {
        if(first == null || second == null)
        {
            return false;
        }

        String firstProtocol = normalizeText(first.destinationProtocol());
        String secondProtocol = normalizeText(second.destinationProtocol());
        String firstValue = normalizeText(first.destinationValue());
        String secondValue = normalizeText(second.destinationValue());
        return firstProtocol != null && secondProtocol != null &&
            firstProtocol.equalsIgnoreCase(secondProtocol) && firstValue != null &&
            firstValue.equals(secondValue);
    }

    private int compareNullableText(String first, String second, boolean ignoreCase)
    {
        String normalizedFirst = normalizeText(first);
        String normalizedSecond = normalizeText(second);

        if(normalizedFirst == null)
        {
            return normalizedSecond == null ? 0 : 1;
        }
        else if(normalizedSecond == null)
        {
            return -1;
        }

        return ignoreCase ? normalizedFirst.compareToIgnoreCase(normalizedSecond) :
            normalizedFirst.compareTo(normalizedSecond);
    }

    public CoordinatorQueueStatus getQueueStatus()
    {
        return new CoordinatorQueueStatus(mIngress.size(), mIngress.regularCapacity(), mIngress.capacity(),
            mAcceptedIngressCount.get(), mDroppedIngressCount.get(), mDroppedLifecycleCount.get(),
            mDroppedOperationCount.get(), mAbortedCallCount.get());
    }

    private boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    private String normalizeText(String value)
    {
        if(value == null)
        {
            return null;
        }

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private void finishDuplicateGroupMember(ManagedAudioCall context)
    {
        if(context.duplicateGroupId == null)
        {
            return;
        }

        DuplicateGroup group = mDuplicateGroups.get(context.duplicateGroupId);

        if(group != null)
        {
            group.activeMemberCallIds.remove(context.snapshot.callId());
            group.completedMemberCallIds.add(context.snapshot.callId());

            if(!group.resolutionDecisionMade && !group.completedCandidates.isEmpty() &&
                group.completedMemberCallIds.containsAll(group.memberCallIds))
            {
                flushResolvedGroup(group.groupId);
            }

            cleanupDuplicateGroup(group);
        }
    }

    private void cleanupDuplicateGroup(DuplicateGroup group)
    {
        if(group.activeMemberCallIds.isEmpty() && group.resolutionDecisionMade)
        {
            cancelResolutionWatchdog(group);
            mDuplicateGroups.remove(group.groupId);
        }
    }

    private boolean isDuplicate(AudioCallSnapshot snapshot1, AudioCallSnapshot snapshot2)
    {
        if(mCallManagementProvider.isDuplicateCallDetectionByTalkgroupEnabled() &&
            AudioCallDuplicateDetector.isDuplicate(getIdentifiers(snapshot1, Role.TO),
                getIdentifiers(snapshot2, Role.TO)))
        {
            return true;
        }

        if(mCallManagementProvider.isDuplicateCallDetectionByRadioEnabled())
        {
            return AudioCallDuplicateDetector.isDuplicate(getIdentifiers(snapshot1, Role.FROM),
                getIdentifiers(snapshot2, Role.FROM));
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Identifier<?>> getIdentifiers(AudioCallSnapshot snapshot, Role role)
    {
        return snapshot != null && snapshot.identifierCollection() != null ?
            (List<Identifier<?>>)(List<?>)snapshot.identifierCollection().getIdentifiers(role) : List.of();
    }

    private String getSystem(AudioCallSnapshot snapshot)
    {
        if(snapshot == null || snapshot.identifierCollection() == null)
        {
            return null;
        }

        Identifier<?> identifier = snapshot.identifierCollection()
            .getIdentifier(IdentifierClass.CONFIGURATION, Form.SYSTEM, Role.ANY);

        return identifier instanceof SystemConfigurationIdentifier system ? system.getValue() : null;
    }

    private static class ManagedAudioCall
    {
        private AudioCallSnapshot snapshot;
        private final List<float[]> audioBuffers = new ArrayList<>();
        private final long registrationOrdinal;
        private long audioSampleCount;
        private long lastProgressNanos = System.nanoTime();
        private Long duplicateGroupId;

        private ManagedAudioCall(AudioCallSnapshot snapshot, long registrationOrdinal)
        {
            this.snapshot = snapshot;
            this.registrationOrdinal = registrationOrdinal;
        }
    }

    private static class DuplicateGroup
    {
        private final long groupId;
        private final AudioCallId liveWinnerCallId;
        private final long anchorRegistrationOrdinal;
        private final Set<AudioCallId> memberCallIds = new HashSet<>();
        private final Set<AudioCallId> activeMemberCallIds = new HashSet<>();
        private final Set<AudioCallId> completedMemberCallIds = new HashSet<>();
        private final Map<AudioCallId, AudioCallSnapshot> memberSnapshots = new HashMap<>();
        private final Map<AudioCallId, CompletedCandidate> completedCandidates = new HashMap<>();
        private final AudioCallSnapshot anchorSnapshot;
        private ScheduledFuture<?> resolutionWatchdog;
        private long resolutionOrphanDeadlineNanos;
        private boolean resolutionWatchdogStarted;
        private boolean sealed;
        private boolean resolutionDecisionMade;

        private DuplicateGroup(long groupId, AudioCallSnapshot anchorSnapshot, long anchorRegistrationOrdinal)
        {
            this.groupId = groupId;
            this.anchorSnapshot = anchorSnapshot;
            this.anchorRegistrationOrdinal = anchorRegistrationOrdinal;
            liveWinnerCallId = anchorSnapshot.callId();
        }
    }

    private record CompletedCandidate(CompletedAudioCall completedAudioCall, long registrationOrdinal)
    {
    }

    private record VoiceQualityScore(double missingAndConcealedRate, double repeatedRate,
                                     double normalizedFecCorrectionRate)
    {
    }

    public record CoordinatorQueueStatus(int ingressDepth, int regularIngressCapacity, int totalIngressCapacity,
                                         long acceptedIngress, long droppedIngress, long droppedLifecycle,
                                         long droppedOperations, long abortedCalls)
    {
    }
}
