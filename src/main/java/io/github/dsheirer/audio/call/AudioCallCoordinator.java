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

import io.github.dsheirer.audio.DuplicateCallDetector;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Serialized owner of live audio call state. Normalizes immutable producer events, applies duplicate suppression,
 * feeds playback directly via managed playable calls, and emits completed immutable calls for recording/streaming.
 */
public class AudioCallCoordinator implements Listener<AudioCallEvent>
{
    private final ExecutorService mExecutor =
        Executors.newSingleThreadExecutor(new NamingThreadFactory("sdrtrunk audio coordinator"));
    private final Map<AudioCallId, ManagedAudioCall> mCalls = new HashMap<>();
    private final ICallManagementProvider mCallManagementProvider;
    private final Consumer<ManagedPlayableAudioCall> mPlaybackConsumer;
    private final Consumer<CompletedAudioCall> mRecordingConsumer;
    private final Consumer<CompletedAudioCall> mStreamingConsumer;

    public AudioCallCoordinator(UserPreferences userPreferences, AudioPlaybackManager audioPlaybackManager,
                                AudioRecordingManager audioRecordingManager,
                                AudioStreamingManager audioStreamingManager)
    {
        this(userPreferences.getCallManagementPreference(),
            audioPlaybackManager != null ? audioPlaybackManager::receive : null,
            audioRecordingManager != null ? audioRecordingManager::receive : null,
            audioStreamingManager != null ? audioStreamingManager::receive : null);
    }

    AudioCallCoordinator(ICallManagementProvider callManagementProvider,
                         Consumer<ManagedPlayableAudioCall> playbackConsumer,
                         Consumer<CompletedAudioCall> recordingConsumer,
                         Consumer<CompletedAudioCall> streamingConsumer)
    {
        mCallManagementProvider = callManagementProvider;
        mPlaybackConsumer = playbackConsumer;
        mRecordingConsumer = recordingConsumer;
        mStreamingConsumer = streamingConsumer;
    }

    @Override
    public void receive(AudioCallEvent event)
    {
        if(event != null)
        {
            mExecutor.execute(() -> process(event));
        }
    }

    public void dispose()
    {
        mExecutor.shutdownNow();
        mCalls.clear();
    }

    private void process(AudioCallEvent event)
    {
        AudioCallSnapshot incomingSnapshot = event.snapshot();

        if(incomingSnapshot == null || incomingSnapshot.callId() == null)
        {
            return;
        }

        ManagedAudioCall context = mCalls.computeIfAbsent(incomingSnapshot.callId(),
            key -> new ManagedAudioCall(incomingSnapshot, createPlaybackCall(incomingSnapshot)));

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

        updateDuplicateState(context.snapshot);

        if(event.eventType() == AudioCallEventType.CALL_COMPLETED)
        {
            CompletedAudioCall completedAudioCall =
                new CompletedAudioCall(context.snapshot, List.copyOf(context.audioBuffers));

            if(mRecordingConsumer != null)
            {
                mRecordingConsumer.accept(completedAudioCall);
            }

            if(mStreamingConsumer != null)
            {
                mStreamingConsumer.accept(completedAudioCall);
            }

            mCalls.remove(context.snapshot.callId());
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

    private void updateDuplicateState(AudioCallSnapshot changedSnapshot)
    {
        if(changedSnapshot == null || !mCallManagementProvider.isDuplicateCallDetectionEnabled())
        {
            return;
        }

        String system = getSystem(changedSnapshot);

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

        for(int currentIndex = 0; currentIndex < systemCalls.size() - 1; currentIndex++)
        {
            ManagedAudioCall current = systemCalls.get(currentIndex);

            if(current.snapshot.duplicate())
            {
                continue;
            }

            for(int checkIndex = currentIndex + 1; checkIndex < systemCalls.size(); checkIndex++)
            {
                ManagedAudioCall toCheck = systemCalls.get(checkIndex);

                if(!toCheck.snapshot.duplicate() && isDuplicate(current.snapshot, toCheck.snapshot))
                {
                    toCheck.snapshot = toCheck.snapshot.withDuplicate(true);
                    if(toCheck.playbackCall != null)
                    {
                        toCheck.playbackCall.updateSnapshot(toCheck.snapshot);
                    }
                }
            }
        }
    }

    private boolean isDuplicate(AudioCallSnapshot snapshot1, AudioCallSnapshot snapshot2)
    {
        if(mCallManagementProvider.isDuplicateCallDetectionByTalkgroupEnabled() &&
            DuplicateCallDetector.SystemDuplicateCallDetector.isDuplicate(getIdentifiers(snapshot1, Role.TO),
                getIdentifiers(snapshot2, Role.TO)))
        {
            return true;
        }

        if(mCallManagementProvider.isDuplicateCallDetectionByRadioEnabled())
        {
            return DuplicateCallDetector.SystemDuplicateCallDetector.isDuplicate(getIdentifiers(snapshot1, Role.FROM),
                getIdentifiers(snapshot2, Role.FROM));
        }

        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Identifier<?>> getIdentifiers(AudioCallSnapshot snapshot, Role role)
    {
        return snapshot != null ? (List<Identifier<?>>)(List<?>)snapshot.identifierCollection().getIdentifiers(role) :
            List.of();
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

        private ManagedAudioCall(AudioCallSnapshot snapshot, ManagedPlayableAudioCall playbackCall)
        {
            this.snapshot = snapshot;
            this.playbackCall = playbackCall;
        }
    }
}
