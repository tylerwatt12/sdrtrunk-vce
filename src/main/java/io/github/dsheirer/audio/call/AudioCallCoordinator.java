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
import io.github.dsheirer.audio.playback.AudioPlaybackManager;
import io.github.dsheirer.audio.playback.ManagedPlayableAudioCall;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Serialized owner of live audio call state. Normalizes immutable producer events, applies duplicate suppression,
 * feeds playback directly via managed playable calls, and emits completed immutable calls for recording/streaming.
 */
public class AudioCallCoordinator implements Listener<AudioCallEvent>
{
    static final long DEFAULT_STREAMING_DUPLICATE_GRACE_MILLISECONDS = 1_000L;

    private final Object mStateLock = new Object();
    private final ScheduledThreadPoolExecutor mExecutor;
    private final Map<AudioCallId, ManagedAudioCall> mCalls = new HashMap<>();
    private final Map<Long, DuplicateGroup> mDuplicateGroups = new HashMap<>();
    private final ICallManagementProvider mCallManagementProvider;
    private final DuplicateCallPriorityProvider mDuplicateCallPriorityProvider;
    private final Consumer<ManagedPlayableAudioCall> mPlaybackConsumer;
    private final Consumer<CompletedAudioCall> mRecordingConsumer;
    private final Consumer<CompletedAudioCall> mStreamingConsumer;
    private final Consumer<CompletedAudioCall> mWebConsumer;
    private final long mStreamingDuplicateGraceMilliseconds;
    private long mNextRegistrationOrdinal;
    private long mNextDuplicateGroupOrdinal;
    private volatile boolean mDisposed;

    public AudioCallCoordinator(UserPreferences userPreferences, AudioPlaybackManager audioPlaybackManager,
                                AudioRecordingManager audioRecordingManager,
                                AudioStreamingManager audioStreamingManager,
                                Consumer<CompletedAudioCall> webConsumer)
    {
        this(userPreferences, audioPlaybackManager, audioRecordingManager, audioStreamingManager, webConsumer,
            DuplicateCallPriorityProvider.NONE);
    }

    /**
     * Constructs an audio-call coordinator with an optional stable source-priority hook for deterministic duplicate
     * elections.
     */
    public AudioCallCoordinator(UserPreferences userPreferences, AudioPlaybackManager audioPlaybackManager,
                                AudioRecordingManager audioRecordingManager,
                                AudioStreamingManager audioStreamingManager,
                                Consumer<CompletedAudioCall> webConsumer,
                                DuplicateCallPriorityProvider duplicateCallPriorityProvider)
    {
        this(userPreferences.getCallManagementPreference(),
            audioPlaybackManager != null ? audioPlaybackManager::receive : null,
            audioRecordingManager != null ? audioRecordingManager::receive : null,
            audioStreamingManager != null ? audioStreamingManager::receive : null, webConsumer,
            duplicateCallPriorityProvider, DEFAULT_STREAMING_DUPLICATE_GRACE_MILLISECONDS);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<ManagedPlayableAudioCall> playbackConsumer,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer)
    {
        this(callManagementProvider, playbackConsumer, recordingConsumer, streamingConsumer, webConsumer,
            DuplicateCallPriorityProvider.NONE, DEFAULT_STREAMING_DUPLICATE_GRACE_MILLISECONDS);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<ManagedPlayableAudioCall> playbackConsumer,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer,
                         DuplicateCallPriorityProvider duplicateCallPriorityProvider,
                         long streamingDuplicateGraceMilliseconds)
    {
        mCallManagementProvider = callManagementProvider;
        mPlaybackConsumer = playbackConsumer;
        mRecordingConsumer = recordingConsumer;
        mStreamingConsumer = streamingConsumer;
        mWebConsumer = webConsumer;
        mDuplicateCallPriorityProvider = duplicateCallPriorityProvider != null ?
            duplicateCallPriorityProvider : DuplicateCallPriorityProvider.NONE;
        mStreamingDuplicateGraceMilliseconds = Math.max(0L, streamingDuplicateGraceMilliseconds);
        mExecutor = new ScheduledThreadPoolExecutor(1,
            new NamingThreadFactory("sdrtrunk audio coordinator"));
        mExecutor.setRemoveOnCancelPolicy(true);
        mExecutor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
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
            context = new ManagedAudioCall(incomingSnapshot, createPlaybackCall(incomingSnapshot),
                mNextRegistrationOrdinal++);
            mCalls.put(incomingSnapshot.callId(), context);
        }

        // Ownership boundary:
        // 1) producers emit immutable AudioCallEvent/AudioCallSnapshot objects
        // 2) the coordinator is the only writer of live call state and playback-call buffers
        // 3) playback/recording/streaming consume snapshots or coordinator-owned playback calls and do not mutate
        //    the shared call context directly
        context.snapshot = incomingSnapshot.withDuplicate(context.snapshot != null && context.snapshot.duplicate());
        if(context.playbackCall != null)
        {
            context.playbackCall.updateSnapshot(context.snapshot);
        }

        if(event.audioFrame() != null)
        {
            context.audioBuffers.add(event.audioFrame());
            if(context.playbackCall != null)
            {
                context.playbackCall.appendAudio(event.audioFrame());
            }
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
            removeActiveDuplicateGroupMember(context);
        }
    }

    private ManagedPlayableAudioCall createPlaybackCall(AudioCallSnapshot snapshot)
    {
        if(mPlaybackConsumer == null || snapshot == null)
        {
            return null;
        }

        ManagedPlayableAudioCall playbackCall = new ManagedPlayableAudioCall(snapshot);
        mPlaybackConsumer.accept(playbackCall);
        return playbackCall;
    }

    /**
     * Reconciles explicit duplicate groups for the changed call. Calls are connected when any enabled detector
     * matches them, so mixed talkgroup/radio chains form one group with one elected survivor. Once a group elects a
     * live survivor, that survivor remains sticky for the life of the group; completing it does not promote another
     * call mid-call.
     */
    private void updateDuplicateState(ManagedAudioCall changedCall)
    {
        if(changedCall == null || changedCall.snapshot == null ||
            !mCallManagementProvider.isDuplicateCallDetectionEnabled())
        {
            return;
        }

        String system = getSystem(changedCall.snapshot);

        if(system == null)
        {
            return;
        }

        List<ManagedAudioCall> systemCalls = new ArrayList<>();

        for(ManagedAudioCall call : mCalls.values())
        {
            if(call.snapshot != null && !call.snapshot.complete() && !call.snapshot.encrypted() &&
                system.equals(getSystem(call.snapshot)) && !call.audioBuffers.isEmpty())
            {
                systemCalls.add(call);
            }
        }

        if(systemCalls.size() < 2)
        {
            return;
        }

        //Hash-map iteration order never participates in either grouping or election.
        systemCalls.sort(Comparator.comparingLong(call -> call.registrationOrdinal));
        Set<ManagedAudioCall> remaining = new LinkedHashSet<>(systemCalls);

        while(!remaining.isEmpty())
        {
            ManagedAudioCall seed = remaining.iterator().next();
            List<ManagedAudioCall> component = new ArrayList<>();
            List<ManagedAudioCall> pending = new ArrayList<>();
            pending.add(seed);
            remaining.remove(seed);

            for(int index = 0; index < pending.size(); index++)
            {
                ManagedAudioCall current = pending.get(index);
                component.add(current);

                List<ManagedAudioCall> matches = new ArrayList<>();
                for(ManagedAudioCall candidate : remaining)
                {
                    if(areInSameDuplicateGroup(current, candidate) ||
                        isDuplicate(current.snapshot, candidate.snapshot))
                    {
                        matches.add(candidate);
                    }
                }

                remaining.removeAll(matches);
                pending.addAll(matches);
            }

            if(component.size() > 1 && hasActualDuplicatePair(component))
            {
                reconcileDuplicateComponent(component);
            }
        }
    }

    private boolean hasActualDuplicatePair(List<ManagedAudioCall> component)
    {
        for(int first = 0; first < component.size() - 1; first++)
        {
            for(int second = first + 1; second < component.size(); second++)
            {
                if(isDuplicate(component.get(first).snapshot, component.get(second).snapshot))
                {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean areInSameDuplicateGroup(ManagedAudioCall first, ManagedAudioCall second)
    {
        return first.duplicateGroupId != null && first.duplicateGroupId.equals(second.duplicateGroupId);
    }

    private void reconcileDuplicateComponent(List<ManagedAudioCall> component)
    {
        Set<Long> existingGroupIds = new HashSet<>();

        for(ManagedAudioCall call : component)
        {
            if(call.duplicateGroupId != null && mDuplicateGroups.containsKey(call.duplicateGroupId))
            {
                existingGroupIds.add(call.duplicateGroupId);
            }
        }

        DuplicateGroup group;

        if(existingGroupIds.isEmpty())
        {
            ManagedAudioCall winner = component.stream().min(this::compareLiveCandidates).orElseThrow();
            group = new DuplicateGroup(mNextDuplicateGroupOrdinal++, winner.snapshot.callId());
            mDuplicateGroups.put(group.groupId, group);
        }
        else
        {
            //The oldest established group owns the sticky survivor if two previously independent groups merge.
            long primaryGroupId = existingGroupIds.stream().min(Long::compareTo).orElseThrow();
            group = mDuplicateGroups.get(primaryGroupId);

            for(Long groupId : existingGroupIds)
            {
                if(groupId != primaryGroupId)
                {
                    mergeDuplicateGroup(group, mDuplicateGroups.remove(groupId));
                }
            }
        }

        for(ManagedAudioCall call : component)
        {
            call.duplicateGroupId = group.groupId;
            group.memberCallIds.add(call.snapshot.callId());
            group.activeMemberCallIds.add(call.snapshot.callId());
        }

        applyStickyDuplicateState(group);
    }

    private void mergeDuplicateGroup(DuplicateGroup primary, DuplicateGroup secondary)
    {
        if(primary == null || secondary == null)
        {
            return;
        }

        primary.memberCallIds.addAll(secondary.memberCallIds);
        primary.activeMemberCallIds.addAll(secondary.activeMemberCallIds);

        if(!primary.streamingDecisionMade)
        {
            primary.completedStreamingCandidates.putAll(secondary.completedStreamingCandidates);
        }

        for(AudioCallId memberCallId : secondary.memberCallIds)
        {
            ManagedAudioCall member = mCalls.get(memberCallId);

            if(member != null)
            {
                member.duplicateGroupId = primary.groupId;
            }
        }

        if(!primary.streamingFlushScheduled && secondary.streamingFlushScheduled &&
            !primary.completedStreamingCandidates.isEmpty())
        {
            scheduleStreamingFlush(primary);
        }
    }

    private void applyStickyDuplicateState(DuplicateGroup group)
    {
        for(AudioCallId memberCallId : group.activeMemberCallIds)
        {
            ManagedAudioCall member = mCalls.get(memberCallId);

            if(member != null)
            {
                setDuplicateState(member, !group.liveWinnerCallId.equals(memberCallId));
            }
        }
    }

    private void setDuplicateState(ManagedAudioCall call, boolean duplicate)
    {
        if(call.snapshot.duplicate() != duplicate)
        {
            call.snapshot = call.snapshot.withDuplicate(duplicate);

            if(call.playbackCall != null)
            {
                call.playbackCall.updateSnapshot(call.snapshot);
            }
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
     * Sends single calls through immediately. Actual duplicate groups are retained briefly so that streaming can
     * choose the most complete finished copy independently of the sticky live-playback survivor.
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

        group.completedStreamingCandidates.put(completedAudioCall.snapshot().callId(),
            new CompletedStreamingCandidate(completedAudioCall, context.registrationOrdinal));

        if(!group.streamingFlushScheduled)
        {
            scheduleStreamingFlush(group);
        }
    }

    private void scheduleStreamingFlush(DuplicateGroup group)
    {
        if(mDisposed)
        {
            return;
        }

        group.streamingFlushScheduled = true;
        long groupId = group.groupId;

        try
        {
            mExecutor.schedule(() -> {
                synchronized(mStateLock)
                {
                    if(!mDisposed)
                    {
                        flushStreamingGroup(groupId);
                    }
                }
            }, mStreamingDuplicateGraceMilliseconds, TimeUnit.MILLISECONDS);
        }
        catch(RejectedExecutionException _)
        {
            group.streamingFlushScheduled = false;
        }
    }

    private void flushStreamingGroup(long groupId)
    {
        DuplicateGroup group = mDuplicateGroups.get(groupId);

        if(group == null || group.streamingDecisionMade)
        {
            return;
        }

        group.streamingDecisionMade = true;
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

    private void removeActiveDuplicateGroupMember(ManagedAudioCall context)
    {
        if(context.duplicateGroupId == null)
        {
            return;
        }

        DuplicateGroup group = mDuplicateGroups.get(context.duplicateGroupId);

        if(group != null)
        {
            group.activeMemberCallIds.remove(context.snapshot.callId());
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
        private final ManagedPlayableAudioCall playbackCall;
        private final long registrationOrdinal;
        private Long duplicateGroupId;

        private ManagedAudioCall(AudioCallSnapshot snapshot, ManagedPlayableAudioCall playbackCall,
                                 long registrationOrdinal)
        {
            this.snapshot = snapshot;
            this.playbackCall = playbackCall;
            this.registrationOrdinal = registrationOrdinal;
        }
    }

    private static class DuplicateGroup
    {
        private final long groupId;
        private final AudioCallId liveWinnerCallId;
        private final Set<AudioCallId> memberCallIds = new HashSet<>();
        private final Set<AudioCallId> activeMemberCallIds = new HashSet<>();
        private final Map<AudioCallId, CompletedStreamingCandidate> completedStreamingCandidates = new HashMap<>();
        private boolean streamingFlushScheduled;
        private boolean streamingDecisionMade;

        private DuplicateGroup(long groupId, AudioCallId liveWinnerCallId)
        {
            this.groupId = groupId;
            this.liveWinnerCallId = liveWinnerCallId;
        }
    }

    private record CompletedStreamingCandidate(CompletedAudioCall completedAudioCall, long registrationOrdinal)
    {
    }
}
