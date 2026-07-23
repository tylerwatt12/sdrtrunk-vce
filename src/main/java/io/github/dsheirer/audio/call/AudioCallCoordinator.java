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

import io.github.dsheirer.audio.broadcast.AudioStreamingManager;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.duplicate.ICallManagementProvider;
import io.github.dsheirer.record.AudioRecordingManager;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Serialized owner of live audio call state. Normalizes immutable producer events, applies duplicate suppression,
 * and emits completed immutable calls for recording, configured streaming providers, and browser playback.
 */
public class AudioCallCoordinator implements Listener<AudioCallEvent>
{
    static final long DEFAULT_STREAMING_DUPLICATE_WATCHDOG_MILLISECONDS = 1_000L;
    static final long DEFAULT_STREAMING_DUPLICATE_ORPHAN_CEILING_MILLISECONDS = 10_000L;

    private final Object mStateLock = new Object();
    private final ScheduledThreadPoolExecutor mExecutor;
    private final Map<AudioCallId, ManagedAudioCall> mCalls = new HashMap<>();
    private final Map<Long, DuplicateGroup> mDuplicateGroups = new HashMap<>();
    private final ICallManagementProvider mCallManagementProvider;
    private final DuplicateCallPriorityProvider mDuplicateCallPriorityProvider;
    private final Consumer<CompletedAudioCall> mRecordingConsumer;
    private final Consumer<CompletedAudioCall> mStreamingConsumer;
    private final Consumer<CompletedAudioCall> mWebConsumer;
    private final long mStreamingDuplicateWatchdogMilliseconds;
    private final long mStreamingDuplicateOrphanCeilingMilliseconds;
    private long mNextRegistrationOrdinal;
    private long mNextDuplicateGroupOrdinal;
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
            duplicateCallPriorityProvider, DEFAULT_STREAMING_DUPLICATE_WATCHDOG_MILLISECONDS,
            DEFAULT_STREAMING_DUPLICATE_ORPHAN_CEILING_MILLISECONDS);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer)
    {
        this(callManagementProvider, recordingConsumer, streamingConsumer, webConsumer,
            DuplicateCallPriorityProvider.NONE, DEFAULT_STREAMING_DUPLICATE_WATCHDOG_MILLISECONDS,
            DEFAULT_STREAMING_DUPLICATE_ORPHAN_CEILING_MILLISECONDS);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer,
                         DuplicateCallPriorityProvider duplicateCallPriorityProvider,
                         long streamingDuplicateWatchdogMilliseconds)
    {
        this(callManagementProvider, recordingConsumer, streamingConsumer, webConsumer,
            duplicateCallPriorityProvider, streamingDuplicateWatchdogMilliseconds,
            deriveOrphanCeilingMilliseconds(streamingDuplicateWatchdogMilliseconds));
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer,
                         DuplicateCallPriorityProvider duplicateCallPriorityProvider,
                         long streamingDuplicateWatchdogMilliseconds,
                         long streamingDuplicateOrphanCeilingMilliseconds)
    {
        mCallManagementProvider = callManagementProvider;
        mRecordingConsumer = recordingConsumer;
        mStreamingConsumer = streamingConsumer;
        mWebConsumer = webConsumer;
        mDuplicateCallPriorityProvider = duplicateCallPriorityProvider != null ?
            duplicateCallPriorityProvider : DuplicateCallPriorityProvider.NONE;
        mStreamingDuplicateWatchdogMilliseconds = Math.max(0L, streamingDuplicateWatchdogMilliseconds);
        mStreamingDuplicateOrphanCeilingMilliseconds = Math.max(mStreamingDuplicateWatchdogMilliseconds,
            streamingDuplicateOrphanCeilingMilliseconds);
        mExecutor = new ScheduledThreadPoolExecutor(1,
            new NamingThreadFactory("sdrtrunk audio coordinator"));
        mExecutor.setRemoveOnCancelPolicy(true);
        mExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
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
        if(event != null && !mDisposed)
        {
            try
            {
                mExecutor.execute(() -> {
                    synchronized(mStateLock)
                    {
                        if(!mDisposed)
                        {
                            process(event);
                        }
                    }
                });
            }
            catch(RejectedExecutionException _)
            {
                //The coordinator was disposed between the state check and task submission.
            }
        }
    }

    public void dispose()
    {
        mDisposed = true;
        mExecutor.shutdownNow();

        synchronized(mStateLock)
        {
            mCalls.clear();
            mDuplicateGroups.clear();
        }
    }

    private void process(AudioCallEvent event)
    {
        AudioCallSnapshot incomingSnapshot = event.snapshot();

        if(incomingSnapshot == null || incomingSnapshot.callId() == null)
        {
            return;
        }

        ManagedAudioCall context = mCalls.get(incomingSnapshot.callId());

        if(context == null)
        {
            context = new ManagedAudioCall(incomingSnapshot, mNextRegistrationOrdinal++);
            mCalls.put(incomingSnapshot.callId(), context);
        }

        // Ownership boundary:
        // 1) producers emit immutable AudioCallEvent/AudioCallSnapshot objects
        // 2) the coordinator is the only writer of live call state and call audio buffers
        // 3) recording, configured streaming providers, and browser playback consume completed immutable calls
        context.snapshot = incomingSnapshot.withDuplicate(context.snapshot != null && context.snapshot.duplicate());

        if(isProgressEvent(event))
        {
            context.lastProgressNanos = System.nanoTime();
        }

        if(event.audioFrame() != null)
        {
            context.audioBuffers.add(event.audioFrame());
        }

        updateDuplicateState(context);

        if(event.eventType() == AudioCallEventType.CALL_COMPLETED)
        {
            CompletedAudioCall completedAudioCall =
                new CompletedAudioCall(context.snapshot, List.copyOf(context.audioBuffers));

            if(mRecordingConsumer != null)
            {
                mRecordingConsumer.accept(completedAudioCall);
            }

            handleStreamingCompletion(context, completedAudioCall);

            if(mWebConsumer != null)
            {
                mWebConsumer.accept(completedAudioCall);
            }

            mCalls.remove(context.snapshot.callId());
            finishDuplicateGroupMember(context);
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
            if(changedCall.snapshot.complete())
            {
                changedGroup.sealed = true;
            }
        }

        if(!mCallManagementProvider.isDuplicateStreamingSuppressionEnabled())
        {
            releasePendingStreamingCandidates();
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
                !call.audioBuffers.isEmpty())
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
        if(group == null || group.sealed || group.streamingDecisionMade ||
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
     * Sends single calls through immediately. Once the first duplicate member completes, its cohort is sealed and a
     * watchdog starts. Streaming normally waits until every known member completes and then scores the whole cohort;
     * the watchdog follows coordinator-local member progress and only treats an inactive member as orphaned. A
     * separate, longer ceiling bounds the delay if a producer keeps reporting progress but never completes.
     */
    private void handleStreamingCompletion(ManagedAudioCall context, CompletedAudioCall completedAudioCall)
    {
        if(mStreamingConsumer == null)
        {
            return;
        }

        DuplicateGroup group = context.duplicateGroupId != null ?
            mDuplicateGroups.get(context.duplicateGroupId) : null;

        if(group == null || !mCallManagementProvider.isDuplicateCallDetectionEnabled() ||
            !mCallManagementProvider.isDuplicateStreamingSuppressionEnabled())
        {
            mStreamingConsumer.accept(completedAudioCall);
            return;
        }

        if(group.streamingDecisionMade)
        {
            return;
        }

        group.sealed = true;
        group.completedStreamingCandidates.put(completedAudioCall.snapshot().callId(),
            new CompletedStreamingCandidate(completedAudioCall, context.registrationOrdinal));

        if(!group.streamingWatchdogStarted)
        {
            group.streamingWatchdogStarted = true;
            group.streamingOrphanDeadlineNanos = safeAddNanos(System.nanoTime(),
                TimeUnit.MILLISECONDS.toNanos(mStreamingDuplicateOrphanCeilingMilliseconds));
            scheduleStreamingWatchdog(group,
                TimeUnit.MILLISECONDS.toNanos(mStreamingDuplicateWatchdogMilliseconds));
        }
    }

    private void scheduleStreamingWatchdog(DuplicateGroup group, long delayNanos)
    {
        if(mDisposed)
        {
            return;
        }

        long groupId = group.groupId;

        try
        {
            group.streamingWatchdog = mExecutor.schedule(() -> {
                synchronized(mStateLock)
                {
                    if(!mDisposed)
                    {
                        DuplicateGroup currentGroup = mDuplicateGroups.get(groupId);

                        if(currentGroup != null)
                        {
                            currentGroup.streamingWatchdog = null;
                        }

                        evaluateStreamingWatchdog(groupId);
                    }
                }
            }, Math.max(0L, delayNanos), TimeUnit.NANOSECONDS);
        }
        catch(RejectedExecutionException _)
        {
            group.streamingWatchdog = null;
        }
    }

    private void evaluateStreamingWatchdog(long groupId)
    {
        if(!mCallManagementProvider.isDuplicateCallDetectionEnabled())
        {
            releaseAllDuplicateGroups();
            return;
        }

        if(!mCallManagementProvider.isDuplicateStreamingSuppressionEnabled())
        {
            releasePendingStreamingCandidates();
            return;
        }

        DuplicateGroup group = mDuplicateGroups.get(groupId);

        if(group == null || group.streamingDecisionMade)
        {
            return;
        }

        if(group.completedMemberCallIds.containsAll(group.memberCallIds))
        {
            flushStreamingGroup(groupId);
            return;
        }

        long now = System.nanoTime();

        if(now >= group.streamingOrphanDeadlineNanos)
        {
            flushStreamingGroup(groupId);
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
                TimeUnit.MILLISECONDS.toNanos(mStreamingDuplicateWatchdogMilliseconds)) : now;

        if(now < inactivityDeadline)
        {
            long nextDeadline = Math.min(inactivityDeadline, group.streamingOrphanDeadlineNanos);
            scheduleStreamingWatchdog(group, Math.max(0L, nextDeadline - now));
        }
        else
        {
            flushStreamingGroup(groupId);
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

    private void flushStreamingGroup(long groupId)
    {
        if(!mCallManagementProvider.isDuplicateCallDetectionEnabled())
        {
            releaseAllDuplicateGroups();
            return;
        }

        if(!mCallManagementProvider.isDuplicateStreamingSuppressionEnabled())
        {
            releasePendingStreamingCandidates();
            return;
        }

        DuplicateGroup group = mDuplicateGroups.get(groupId);

        if(group == null || group.streamingDecisionMade)
        {
            return;
        }

        group.streamingDecisionMade = true;
        cancelStreamingWatchdog(group);
        CompletedStreamingCandidate winner = group.completedStreamingCandidates.values().stream()
            .min(this::compareStreamingCandidates).orElse(null);
        group.completedStreamingCandidates.clear();

        try
        {
            if(winner != null && mStreamingConsumer != null)
            {
                CompletedAudioCall selectedCall = winner.completedAudioCall;

                //Streaming selection is independent from sticky live playback. A higher-quality live duplicate must
                //be cleared before entering the existing streaming suppression filter.
                if(selectedCall.snapshot().duplicate())
                {
                    selectedCall = new CompletedAudioCall(selectedCall.snapshot().withDuplicate(false),
                        selectedCall.audioBuffers());
                }

                mStreamingConsumer.accept(selectedCall);
            }
        }
        finally
        {
            cleanupDuplicateGroup(group);
        }
    }

    private void releasePendingStreamingCandidates()
    {
        for(DuplicateGroup group : new ArrayList<>(mDuplicateGroups.values()))
        {
            if(!group.streamingDecisionMade && !group.completedStreamingCandidates.isEmpty())
            {
                sendAllStreamingCandidates(group, false);
                cleanupDuplicateGroup(group);
            }
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
            if(!group.streamingDecisionMade && !group.completedStreamingCandidates.isEmpty())
            {
                sendAllStreamingCandidates(group, true);
            }
            else
            {
                cancelStreamingWatchdog(group);
            }
        }

        mDuplicateGroups.clear();
    }

    private void sendAllStreamingCandidates(DuplicateGroup group, boolean clearDuplicate)
    {
        group.streamingDecisionMade = true;
        cancelStreamingWatchdog(group);
        List<CompletedStreamingCandidate> candidates =
            new ArrayList<>(group.completedStreamingCandidates.values());
        candidates.sort((first, second) -> compareElectionOrder(first.completedAudioCall.snapshot(),
            first.registrationOrdinal, second.completedAudioCall.snapshot(), second.registrationOrdinal));
        group.completedStreamingCandidates.clear();

        if(mStreamingConsumer != null)
        {
            for(CompletedStreamingCandidate candidate : candidates)
            {
                CompletedAudioCall completedCall = candidate.completedAudioCall;

                if(clearDuplicate && completedCall.snapshot().duplicate())
                {
                    completedCall = new CompletedAudioCall(completedCall.snapshot().withDuplicate(false),
                        completedCall.audioBuffers());
                }

                mStreamingConsumer.accept(completedCall);
            }
        }
    }

    private void cancelStreamingWatchdog(DuplicateGroup group)
    {
        if(group.streamingWatchdog != null)
        {
            group.streamingWatchdog.cancel(false);
            group.streamingWatchdog = null;
        }
    }

    private int compareStreamingCandidates(CompletedStreamingCandidate first, CompletedStreamingCandidate second)
    {
        int comparison = Boolean.compare(second.completedAudioCall.snapshot().hasBroadcastChannels(),
            first.completedAudioCall.snapshot().hasBroadcastChannels());

        if(comparison == 0)
        {
            comparison = Double.compare(getPcmCoverage(second.completedAudioCall),
                getPcmCoverage(first.completedAudioCall));
        }

        if(comparison == 0)
        {
            comparison = Long.compare(getSampleCount(second.completedAudioCall),
                getSampleCount(first.completedAudioCall));
        }

        if(comparison == 0)
        {
            comparison = Integer.compare(getBufferCount(second.completedAudioCall),
                getBufferCount(first.completedAudioCall));
        }

        if(comparison == 0)
        {
            comparison = Double.compare(getSamplesPerBurst(second.completedAudioCall),
                getSamplesPerBurst(first.completedAudioCall));
        }

        if(comparison == 0)
        {
            comparison = compareElectionOrder(first.completedAudioCall.snapshot(), first.registrationOrdinal,
                second.completedAudioCall.snapshot(), second.registrationOrdinal);
        }

        return comparison;
    }

    private double getPcmCoverage(CompletedAudioCall call)
    {
        long sampleCount = getSampleCount(call);
        AudioCallSnapshot snapshot = call.snapshot();
        long callSpan = Math.max(1L, snapshot.lastActivityTimestamp() - snapshot.startTimestamp());
        long expectedSampleCount;

        try
        {
            expectedSampleCount = Math.multiplyExact(callSpan, 8L);
        }
        catch(ArithmeticException _)
        {
            expectedSampleCount = Long.MAX_VALUE;
        }

        return Math.min(1.0d, (double)sampleCount / Math.max(1L, expectedSampleCount));
    }

    private long getSampleCount(CompletedAudioCall call)
    {
        long sampleCount = 0L;

        if(call != null && call.audioBuffers() != null)
        {
            for(float[] buffer : call.audioBuffers())
            {
                if(buffer != null)
                {
                    sampleCount += buffer.length;
                }
            }
        }

        return sampleCount;
    }

    private int getBufferCount(CompletedAudioCall call)
    {
        return call != null && call.audioBuffers() != null ? call.audioBuffers().size() : 0;
    }

    private double getSamplesPerBurst(CompletedAudioCall call)
    {
        int burstCount = call != null && call.snapshot() != null ?
            Math.max(1, call.snapshot().burstCount()) : 1;
        return (double)getSampleCount(call) / burstCount;
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

            if(!group.streamingDecisionMade && !group.completedStreamingCandidates.isEmpty() &&
                group.completedMemberCallIds.containsAll(group.memberCallIds))
            {
                flushStreamingGroup(group.groupId);
            }

            cleanupDuplicateGroup(group);
        }
    }

    private void cleanupDuplicateGroup(DuplicateGroup group)
    {
        if(group.activeMemberCallIds.isEmpty() &&
            (mStreamingConsumer == null ||
                !mCallManagementProvider.isDuplicateStreamingSuppressionEnabled() ||
                group.streamingDecisionMade))
        {
            cancelStreamingWatchdog(group);
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
        private final Map<AudioCallId, CompletedStreamingCandidate> completedStreamingCandidates = new HashMap<>();
        private final AudioCallSnapshot anchorSnapshot;
        private ScheduledFuture<?> streamingWatchdog;
        private long streamingOrphanDeadlineNanos;
        private boolean streamingWatchdogStarted;
        private boolean sealed;
        private boolean streamingDecisionMade;

        private DuplicateGroup(long groupId, AudioCallSnapshot anchorSnapshot, long anchorRegistrationOrdinal)
        {
            this.groupId = groupId;
            this.anchorSnapshot = anchorSnapshot;
            this.anchorRegistrationOrdinal = anchorRegistrationOrdinal;
            liveWinnerCallId = anchorSnapshot.callId();
        }
    }

    private record CompletedStreamingCandidate(CompletedAudioCall completedAudioCall, long registrationOrdinal)
    {
    }
}
