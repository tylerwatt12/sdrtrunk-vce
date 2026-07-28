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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 * feeds live calls to receiver-local speaker playback, and emits the elected completed immutable call for recording,
 * configured streaming providers, and browser playback.
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
    private final Consumer<ManagedPlayableAudioCall> mPlaybackConsumer;
    private final Consumer<CompletedAudioCall> mRecordingConsumer;
    private final Consumer<CompletedAudioCall> mStreamingConsumer;
    private final Consumer<CompletedAudioCall> mWebConsumer;
    private final WebCallDeliveryListener mWebCallDeliveryListener;
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
     * Constructs an audio-call coordinator with receiver-local speaker playback.
     */
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
    public AudioCallCoordinator(UserPreferences userPreferences, AudioRecordingManager audioRecordingManager,
                                AudioStreamingManager audioStreamingManager,
                                Consumer<CompletedAudioCall> webConsumer,
                                DuplicateCallPriorityProvider duplicateCallPriorityProvider)
    {
        this(userPreferences.getCallManagementPreference(), null,
            audioRecordingManager != null ? audioRecordingManager::receive : null,
            audioStreamingManager != null ? audioStreamingManager::receive : null, webConsumer,
            duplicateCallPriorityProvider, DEFAULT_STREAMING_DUPLICATE_WATCHDOG_MILLISECONDS,
            DEFAULT_STREAMING_DUPLICATE_ORPHAN_CEILING_MILLISECONDS, null);
    }

    /**
     * Constructs an audio-call coordinator with receiver-local speaker playback and deterministic duplicate-election
     * hooks. Speaker playback follows the live winner while completed-call consumers share the final election.
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
            duplicateCallPriorityProvider,
            DEFAULT_STREAMING_DUPLICATE_WATCHDOG_MILLISECONDS,
            DEFAULT_STREAMING_DUPLICATE_ORPHAN_CEILING_MILLISECONDS, null);
    }

    /**
     * Constructs an audio-call coordinator with both the compatibility completed-call web consumer and the ordered
     * browser-delivery lifecycle. Runtime wiring should normally pass {@code null} for the compatibility consumer
     * when selecting the lifecycle listener so that one browser adapter does not receive each call twice.
     */
    public AudioCallCoordinator(UserPreferences userPreferences, AudioRecordingManager audioRecordingManager,
                                AudioStreamingManager audioStreamingManager,
                                Consumer<CompletedAudioCall> webConsumer,
                                DuplicateCallPriorityProvider duplicateCallPriorityProvider,
                                WebCallDeliveryListener webCallDeliveryListener)
    {
        this(userPreferences.getCallManagementPreference(),
            null,
            audioRecordingManager != null ? audioRecordingManager::receive : null,
            audioStreamingManager != null ? audioStreamingManager::receive : null, webConsumer,
            duplicateCallPriorityProvider,
            DEFAULT_STREAMING_DUPLICATE_WATCHDOG_MILLISECONDS,
            DEFAULT_STREAMING_DUPLICATE_ORPHAN_CEILING_MILLISECONDS, webCallDeliveryListener);
    }

    /**
     * Constructs an audio-call coordinator with receiver-local speaker playback and ordered browser delivery.
     */
    public AudioCallCoordinator(UserPreferences userPreferences, AudioPlaybackManager audioPlaybackManager,
                                AudioRecordingManager audioRecordingManager,
                                AudioStreamingManager audioStreamingManager,
                                Consumer<CompletedAudioCall> webConsumer,
                                DuplicateCallPriorityProvider duplicateCallPriorityProvider,
                                WebCallDeliveryListener webCallDeliveryListener)
    {
        this(userPreferences.getCallManagementPreference(),
            audioPlaybackManager != null ? audioPlaybackManager::receive : null,
            audioRecordingManager != null ? audioRecordingManager::receive : null,
            audioStreamingManager != null ? audioStreamingManager::receive : null, webConsumer,
            duplicateCallPriorityProvider,
            DEFAULT_STREAMING_DUPLICATE_WATCHDOG_MILLISECONDS,
            DEFAULT_STREAMING_DUPLICATE_ORPHAN_CEILING_MILLISECONDS, webCallDeliveryListener);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer)
    {
        this(callManagementProvider, null, recordingConsumer, streamingConsumer, webConsumer,
            DuplicateCallPriorityProvider.NONE,
            DEFAULT_STREAMING_DUPLICATE_WATCHDOG_MILLISECONDS,
            DEFAULT_STREAMING_DUPLICATE_ORPHAN_CEILING_MILLISECONDS, null);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<ManagedPlayableAudioCall> playbackConsumer,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer)
    {
        this(callManagementProvider, playbackConsumer, recordingConsumer, streamingConsumer, webConsumer,
            DuplicateCallPriorityProvider.NONE,
            DEFAULT_STREAMING_DUPLICATE_WATCHDOG_MILLISECONDS,
            DEFAULT_STREAMING_DUPLICATE_ORPHAN_CEILING_MILLISECONDS, null);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer,
                         DuplicateCallPriorityProvider duplicateCallPriorityProvider,
                         long streamingDuplicateWatchdogMilliseconds)
    {
        this(callManagementProvider, null, recordingConsumer, streamingConsumer, webConsumer,
            duplicateCallPriorityProvider,
            streamingDuplicateWatchdogMilliseconds,
            deriveOrphanCeilingMilliseconds(streamingDuplicateWatchdogMilliseconds), null);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer,
                         DuplicateCallPriorityProvider duplicateCallPriorityProvider,
                         long streamingDuplicateWatchdogMilliseconds,
                         long streamingDuplicateOrphanCeilingMilliseconds)
    {
        this(callManagementProvider, null, recordingConsumer, streamingConsumer, webConsumer,
            duplicateCallPriorityProvider,
            streamingDuplicateWatchdogMilliseconds, streamingDuplicateOrphanCeilingMilliseconds, null);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer,
                         DuplicateCallPriorityProvider duplicateCallPriorityProvider,
                         long streamingDuplicateWatchdogMilliseconds,
                         long streamingDuplicateOrphanCeilingMilliseconds,
                         WebCallDeliveryListener webCallDeliveryListener)
    {
        this(callManagementProvider, null, recordingConsumer, streamingConsumer, webConsumer,
            duplicateCallPriorityProvider,
            streamingDuplicateWatchdogMilliseconds, streamingDuplicateOrphanCeilingMilliseconds,
            webCallDeliveryListener);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<ManagedPlayableAudioCall> playbackConsumer,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer,
                         Consumer<CompletedAudioCall> webConsumer,
                         DuplicateCallPriorityProvider duplicateCallPriorityProvider,
                         long streamingDuplicateWatchdogMilliseconds,
                         long streamingDuplicateOrphanCeilingMilliseconds,
                         WebCallDeliveryListener webCallDeliveryListener)
    {
        mCallManagementProvider = callManagementProvider;
        mPlaybackConsumer = playbackConsumer;
        mRecordingConsumer = recordingConsumer;
        mStreamingConsumer = streamingConsumer;
        mWebConsumer = webConsumer;
        mWebCallDeliveryListener = webCallDeliveryListener;
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
            abandonAllWebReservations(WebCallDeliveryEvent.Abandoned.Reason.SHUTDOWN);
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
            openWebReservation(context, incomingSnapshot, event.eventTimestamp(), false);
        }
        else if(!context.webReservationOpen && !context.webDeliveryFinalized &&
            (isProgressEvent(event) || event.eventType() == AudioCallEventType.CALL_COMPLETED))
        {
            openWebReservation(context, incomingSnapshot, event.eventTimestamp(), true);
        }

        // Ownership boundary:
        // 1) producers emit immutable AudioCallEvent/AudioCallSnapshot objects
        // 2) the coordinator is the only writer of live call state and live playback-call buffers
        // 3) receiver-local playback reads the managed live call while recording, configured streaming providers,
        //    and browser playback consume the one elected completed immutable call
        context.snapshot = incomingSnapshot.withDuplicate(context.snapshot != null && context.snapshot.duplicate());

        if(isProgressEvent(event))
        {
            context.lastProgressNanos = System.nanoTime();
        }

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
            handleResolvedCompletion(context, completedAudioCall);

            cancelWebReservationWatchdog(context);
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
     * Reserves a chronological browser-delivery position without retaining audio. A call that previously lost its
     * reservation to the inactivity watchdog receives a new event-time position so it cannot later be inserted
     * behind a publication watermark the browser spool has already advanced.
     */
    private void openWebReservation(ManagedAudioCall context, AudioCallSnapshot snapshot, long eventTimestamp,
                                    boolean reopenedAfterInactivity)
    {
        if(mWebCallDeliveryListener == null || context == null || snapshot == null ||
            snapshot.callId() == null || context.webReservationOpen || context.webDeliveryFinalized)
        {
            return;
        }

        long orderTimestamp;

        if(reopenedAfterInactivity)
        {
            orderTimestamp = eventTimestamp > 0L ? eventTimestamp : System.currentTimeMillis();
        }
        else if(snapshot.startTimestamp() > 0L)
        {
            orderTimestamp = snapshot.startTimestamp();
        }
        else
        {
            orderTimestamp = eventTimestamp > 0L ? eventTimestamp : System.currentTimeMillis();
        }

        WebCallDeliveryEvent.OrderKey orderKey =
            new WebCallDeliveryEvent.OrderKey(orderTimestamp, context.registrationOrdinal, snapshot.callId());
        context.webOrderKey = orderKey;
        context.webReservationOpen = true;
        DuplicateGroup group = getDuplicateGroup(context);

        if(group != null)
        {
            group.memberWebOrderKeys.put(snapshot.callId(), orderKey);
        }

        notifyWebDelivery(new WebCallDeliveryEvent.Opened(orderKey));
        scheduleWebReservationWatchdog(context,
            TimeUnit.MILLISECONDS.toNanos(mStreamingDuplicateOrphanCeilingMilliseconds));
    }

    private void scheduleWebReservationWatchdog(ManagedAudioCall context, long delayNanos)
    {
        if(mDisposed || mWebCallDeliveryListener == null || context == null ||
            !context.webReservationOpen || context.webOrderKey == null)
        {
            return;
        }

        cancelWebReservationWatchdog(context);
        AudioCallId callId = context.snapshot.callId();
        WebCallDeliveryEvent.OrderKey orderKey = context.webOrderKey;

        try
        {
            context.webReservationWatchdog = mExecutor.schedule(() -> {
                synchronized(mStateLock)
                {
                    if(!mDisposed)
                    {
                        evaluateWebReservationWatchdog(callId, orderKey);
                    }
                }
            }, Math.max(0L, delayNanos), TimeUnit.NANOSECONDS);
        }
        catch(RejectedExecutionException _)
        {
            context.webReservationWatchdog = null;
        }
    }

    private void evaluateWebReservationWatchdog(AudioCallId callId,
                                                WebCallDeliveryEvent.OrderKey orderKey)
    {
        ManagedAudioCall context = mCalls.get(callId);

        if(context == null || !context.webReservationOpen || !orderKey.equals(context.webOrderKey))
        {
            return;
        }

        context.webReservationWatchdog = null;
        long now = System.nanoTime();
        long inactivityDeadline = safeAddNanos(context.lastProgressNanos,
            TimeUnit.MILLISECONDS.toNanos(mStreamingDuplicateOrphanCeilingMilliseconds));

        if(now < inactivityDeadline)
        {
            scheduleWebReservationWatchdog(context, inactivityDeadline - now);
        }
        else
        {
            abandonWebReservation(context, WebCallDeliveryEvent.Abandoned.Reason.INACTIVITY);
        }
    }

    private void abandonWebReservation(ManagedAudioCall context,
                                       WebCallDeliveryEvent.Abandoned.Reason reason)
    {
        if(context == null || !context.webReservationOpen || context.webOrderKey == null)
        {
            return;
        }

        WebCallDeliveryEvent.OrderKey orderKey = context.webOrderKey;
        cancelWebReservationWatchdog(context);
        context.webOrderKey = null;
        context.webReservationOpen = false;
        DuplicateGroup group = getDuplicateGroup(context);

        if(group != null)
        {
            group.memberWebOrderKeys.remove(context.snapshot.callId());
        }

        notifyWebDelivery(new WebCallDeliveryEvent.Abandoned(orderKey, reason));
    }

    private void cancelWebReservationWatchdog(ManagedAudioCall context)
    {
        if(context != null && context.webReservationWatchdog != null)
        {
            context.webReservationWatchdog.cancel(false);
            context.webReservationWatchdog = null;
        }
    }

    private void abandonAllWebReservations(WebCallDeliveryEvent.Abandoned.Reason reason)
    {
        if(mWebCallDeliveryListener == null)
        {
            return;
        }

        Set<WebCallDeliveryEvent.OrderKey> openOrderKeys = new HashSet<>();

        for(ManagedAudioCall context : mCalls.values())
        {
            cancelWebReservationWatchdog(context);

            if(context.webReservationOpen && context.webOrderKey != null)
            {
                openOrderKeys.add(context.webOrderKey);
                context.webReservationOpen = false;
                context.webDeliveryFinalized = true;
                context.webOrderKey = null;
            }
        }

        for(DuplicateGroup group : mDuplicateGroups.values())
        {
            openOrderKeys.addAll(group.memberWebOrderKeys.values());
            group.memberWebOrderKeys.clear();
        }

        for(WebCallDeliveryEvent.OrderKey orderKey : openOrderKeys)
        {
            notifyWebDelivery(new WebCallDeliveryEvent.Abandoned(orderKey, reason));
        }
    }

    /**
     * Closes every physical reservation represented by one logical call. Recording and configured streaming have
     * already received the call before this transient browser handoff; the coordinator retains no PCM afterward.
     */
    private void resolveWebDelivery(WebCallDeliveryEvent.OrderKey orderKey,
                                    Set<AudioCallId> sourceCallIds,
                                    CompletedAudioCall completedAudioCall)
    {
        if(mWebCallDeliveryListener == null || orderKey == null || completedAudioCall == null ||
            sourceCallIds == null || sourceCallIds.isEmpty())
        {
            return;
        }

        for(AudioCallId sourceCallId : sourceCallIds)
        {
            ManagedAudioCall context = mCalls.get(sourceCallId);

            if(context != null)
            {
                cancelWebReservationWatchdog(context);
                context.webReservationOpen = false;
                context.webDeliveryFinalized = true;
                context.webOrderKey = null;
            }
        }

        notifyWebDelivery(new WebCallDeliveryEvent.Resolved(orderKey, sourceCallIds, completedAudioCall));
    }

    /**
     * The lifecycle recipient is required to be non-blocking. A faulty browser adapter cannot be allowed to unwind
     * coordinator state or interrupt recording/upload fanout on the latency-sensitive audio path.
     */
    private void notifyWebDelivery(WebCallDeliveryEvent event)
    {
        if(mWebCallDeliveryListener != null && event != null)
        {
            try
            {
                mWebCallDeliveryListener.receive(event);
            }
            catch(RuntimeException _)
            {
                //Isolate an optional browser adapter from radio, recording, and configured streaming.
            }
        }
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

        if(call.webReservationOpen && call.webOrderKey != null)
        {
            group.memberWebOrderKeys.put(call.snapshot.callId(), call.webOrderKey);
        }

        setDuplicateState(call, !group.liveWinnerCallId.equals(call.snapshot.callId()));
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
     * Sends a single call through immediately. Once the first member of a duplicate cohort completes, the cohort is
     * sealed and one bounded election supplies the same resolved logical call to recording, configured streaming, and
     * browser playback. The watchdog follows coordinator-local member progress and only treats an inactive member as
     * orphaned; a separate ceiling bounds a producer that reports progress forever without completing.
     */
    private void handleResolvedCompletion(ManagedAudioCall context, CompletedAudioCall completedAudioCall)
    {
        DuplicateGroup group = context.duplicateGroupId != null ?
            mDuplicateGroups.get(context.duplicateGroupId) : null;

        if(group == null || !mCallManagementProvider.isDuplicateCallDetectionEnabled())
        {
            CompletedAudioCall resolvedCall =
                mergeOutputPolicy(completedAudioCall, List.of(completedAudioCall.snapshot()));
            fanout(resolvedCall);
            resolveWebDelivery(context.webOrderKey, Set.of(completedAudioCall.snapshot().callId()),
                resolvedCall);
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
                TimeUnit.MILLISECONDS.toNanos(mStreamingDuplicateOrphanCeilingMilliseconds));
            scheduleResolutionWatchdog(group,
                TimeUnit.MILLISECONDS.toNanos(mStreamingDuplicateWatchdogMilliseconds));
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
            group.resolutionWatchdog = mExecutor.schedule(() -> {
                synchronized(mStateLock)
                {
                    if(!mDisposed)
                    {
                        DuplicateGroup currentGroup = mDuplicateGroups.get(groupId);

                        if(currentGroup != null)
                        {
                            currentGroup.resolutionWatchdog = null;
                        }

                        evaluateResolutionWatchdog(groupId);
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
                TimeUnit.MILLISECONDS.toNanos(mStreamingDuplicateWatchdogMilliseconds)) : now;

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
                fanout(resolvedCall);
                resolveWebDelivery(minimumWebOrderKey(group), Set.copyOf(group.memberCallIds),
                    resolvedCall);
                group.memberWebOrderKeys.clear();
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
                DuplicateGroup group = mDuplicateGroups.get(call.duplicateGroupId);
                setDuplicateState(call, false);
                call.duplicateGroupId = null;

                /*
                 * A bounded duplicate election may already have closed this still-live physical call's browser
                 * reservation as part of the elected logical call. If duplicate detection is then disabled, the
                 * core recording and streaming paths release this call to complete independently. Reopen its web
                 * lifecycle at the current time as well; using the original start time could insert it behind a
                 * publication watermark that the browser spool has already advanced.
                 */
                if(group != null && group.resolutionDecisionMade && call.webDeliveryFinalized)
                {
                    call.webDeliveryFinalized = false;
                    openWebReservation(call, call.snapshot, System.currentTimeMillis(), true);
                }
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
            fanout(resolvedCall);
            AudioCallId callId = completedCall.snapshot().callId();
            resolveWebDelivery(group.memberWebOrderKeys.remove(callId), Set.of(callId), resolvedCall);
        }
    }

    private WebCallDeliveryEvent.OrderKey minimumWebOrderKey(DuplicateGroup group)
    {
        return group != null ? group.memberWebOrderKeys.values().stream().min(
            WebCallDeliveryEvent.OrderKey::compareTo).orElse(null) : null;
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
            resolvedPolicy.recordAudio(), winnerSnapshot.monitorPriority(), false, mergedMetadata,
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

    private void fanout(CompletedAudioCall completedAudioCall)
    {
        if(mRecordingConsumer != null)
        {
            mRecordingConsumer.accept(completedAudioCall);
        }

        if(mStreamingConsumer != null)
        {
            mStreamingConsumer.accept(completedAudioCall);
        }

        if(mWebConsumer != null)
        {
            mWebConsumer.accept(completedAudioCall);
        }
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
        private final ManagedPlayableAudioCall playbackCall;
        private final long registrationOrdinal;
        private long lastProgressNanos = System.nanoTime();
        private Long duplicateGroupId;
        private WebCallDeliveryEvent.OrderKey webOrderKey;
        private ScheduledFuture<?> webReservationWatchdog;
        private boolean webReservationOpen;
        private boolean webDeliveryFinalized;

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
        private final long anchorRegistrationOrdinal;
        private final Set<AudioCallId> memberCallIds = new HashSet<>();
        private final Set<AudioCallId> activeMemberCallIds = new HashSet<>();
        private final Set<AudioCallId> completedMemberCallIds = new HashSet<>();
        private final Map<AudioCallId, AudioCallSnapshot> memberSnapshots = new HashMap<>();
        private final Map<AudioCallId, CompletedCandidate> completedCandidates = new HashMap<>();
        private final Map<AudioCallId, WebCallDeliveryEvent.OrderKey> memberWebOrderKeys = new HashMap<>();
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
}
