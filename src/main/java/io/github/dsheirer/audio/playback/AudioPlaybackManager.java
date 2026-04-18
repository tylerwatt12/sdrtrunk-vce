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
package io.github.dsheirer.audio.playback;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.audio.AudioEvent;
import io.github.dsheirer.audio.AudioException;
import io.github.dsheirer.audio.IAudioController;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.controller.NamingThreadFactory;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.preference.PreferenceType;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.playback.PlayTestAudioRequest;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedTransferQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages scheduling and playback of audio segments to the local users audio system.
 */
public class AudioPlaybackManager implements IAudioController
{
    private static final Logger mLog = LoggerFactory.getLogger(AudioPlaybackManager.class);
    private static final String UNKNOWN = "unknown";
    private static final long NORMAL_PROCESS_INTERVAL_MS = 20;
    private static final long WATCHDOG_INTERVAL_MS = 1000;
    private static final long WATCHDOG_STARTUP_GRACE_PERIOD_MS = 5000;
    private final AudioSegmentPrioritySorter mAudioSegmentPrioritySorter = new AudioSegmentPrioritySorter();
    private final Broadcaster<AudioEvent> mControllerBroadcaster = new Broadcaster<>();
    private final LinkedTransferQueue<PlayableAudioCall> mIncomingSegments = new LinkedTransferQueue<>();
    private final Map<PlayableAudioCall, PlaybackCallContext> mCallContexts = new IdentityHashMap<>();
    private final ReentrantLock mAudioChannelsLock = new ReentrantLock();
    private final UserPreferences mUserPreferences;
    private final ScheduledExecutorService mProcessingExecutorService;
    private final AudioSegmentProcessor mAudioSegmentProcessor = new AudioSegmentProcessor();
    private static final Listener<AudioChannel> AUDIO_CHANNEL_IDLE_LISTENER = audioChannel -> { };
    private final AtomicLong mNormalRunsStarted = new AtomicLong();
    private final AtomicLong mNormalRunsCompleted = new AtomicLong();
    private final AtomicLong mLastNormalRunStartTimestamp = new AtomicLong();
    private final AtomicLong mLastNormalRunCompleteTimestamp = new AtomicLong();
    private final long mCreatedTimestamp = System.currentTimeMillis();
    private AudioPlaybackDeviceDescriptor mAudioPlaybackDevice;
    private AudioOutput mAudioOutput;
    private ScheduledFuture<?> mNormalProcessingTask;
    private ScheduledFuture<?> mWatchdogTask;

    private enum PlaybackCallState
    {
        WAITING_FOR_AUDIO,
        READY
    }

    private record PlaybackCallContext(PlayableAudioCall audioCall, PlaybackCallState state)
    {
        private PlaybackCallContext withState(PlaybackCallState newState)
        {
            return new PlaybackCallContext(audioCall, newState);
        }
    }

    /**
     * Constructs an instance.
     *
     * @param userPreferences for audio playback preferences
     */
    public AudioPlaybackManager(UserPreferences userPreferences)
    {
        mUserPreferences = userPreferences;
        MyEventBus.getGlobalEventBus().register(this);
        AudioPlaybackDeviceDescriptor device = mUserPreferences.getPlaybackPreference().getAudioPlaybackDevice();

        if(device != null)
        {
            try
            {
                setAudioPlaybackDevice(device);
            }
            catch(AudioException ae)
            {
                mLog.error("Error during setup of audio playback configuration.  Attempted to use device [" +
                        device + "]", ae);
            }
        }
        else
        {
            mLog.warn("No audio output devices available");
        }

        //Run normal processing on a fixed cadence so playback correctness does not depend on lifecycle wakeups.
        //The watchdog remains as a diagnostic backstop, not the primary progress mechanism.
        mProcessingExecutorService =
                Executors.newSingleThreadScheduledExecutor(new NamingThreadFactory("sdrtrunk audio manager"));
        mNormalProcessingTask = mProcessingExecutorService.scheduleAtFixedRate(() -> mAudioSegmentProcessor.run(false),
            0, NORMAL_PROCESS_INTERVAL_MS, TimeUnit.MILLISECONDS);
        mWatchdogTask = mProcessingExecutorService.scheduleAtFixedRate(() -> mAudioSegmentProcessor.run(true),
            0, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Receives audio segments from channel audio modules.
     * @param audioSegment to receive and process
     */
    public void receive(PlayableAudioCall audioCall)
    {
        mIncomingSegments.add(audioCall);
    }

    public void dispose()
    {
        MyEventBus.getGlobalEventBus().unregister(this);
        if(mNormalProcessingTask != null)
        {
            mNormalProcessingTask.cancel(true);
            mNormalProcessingTask = null;
        }

        if(mWatchdogTask != null)
        {
            mWatchdogTask.cancel(true);
            mWatchdogTask = null;
        }

        mProcessingExecutorService.shutdownNow();

        releaseAudioSegments(mIncomingSegments);
        releaseCallContexts();
    }

    private void releaseAudioSegments(Iterable<PlayableAudioCall> audioSegments)
    {
        if(audioSegments == null)
        {
            return;
        }

        if(audioSegments instanceof LinkedTransferQueue<PlayableAudioCall> queue)
        {
            queue.clear();
        }
    }

    private void releaseCallContexts()
    {
        if(mCallContexts.isEmpty())
        {
            return;
        }

        mCallContexts.clear();
    }

    /**
     * Receives a request from the global event bus to playback a test audio sequence via the specified audio channel
     * @param request with test audio and channel number
     */
    @Subscribe
    public void playTestAudio(PlayTestAudioRequest request)
    {
        if(mAudioOutput != null)
        {
            mAudioOutput.playTestAudio(request);
        }
    }

    /**
     * Receive user preference update notifications so that we can detect when the user changes the audio output
     * device in the user preferences editor.
     */
    @Subscribe
    public void preferenceUpdated(PreferenceType preferenceType)
    {
        if(preferenceType == PreferenceType.PLAYBACK)
        {
            AudioPlaybackDeviceDescriptor device = mUserPreferences.getPlaybackPreference().getAudioPlaybackDevice();

            if(device != null && !device.equals(getAudioPlaybackDevice()))
            {
                try
                {
                    setAudioPlaybackDevice(device);
                }
                catch(AudioException ae)
                {
                    mLog.error("Error changing audio output to [" + device + "]", ae);
                }
            }
        }
    }

    /**
     * Configures audio playback to use the audio device argument.
     *
     * @param audioDevice to use in configuring the audio playback setup.
     * @throws AudioException if there is an error
     */
    @Override
    public void setAudioPlaybackDevice(AudioPlaybackDeviceDescriptor audioDevice) throws AudioException
    {
        if(audioDevice != null)
        {
            mControllerBroadcaster.broadcast(new AudioEvent(AudioEvent.Type.AUDIO_CONFIGURATION_CHANGE_STARTED,
                    audioDevice.getMixerInfo().getName()));

            mAudioChannelsLock.lock();

            try
            {
                if(mAudioOutput != null)
                {
                    mAudioOutput.dispose();
                }

                int channelCount = audioDevice.getAudioFormat().getChannels();

                switch(channelCount)
                {
                    case 1:
                        mAudioOutput = new AudioOutput(audioDevice, new AudioProviderMono(mUserPreferences));
                        break;
                    case 2:
                        mAudioOutput = new AudioOutput(audioDevice, new AudioProviderStereo(mUserPreferences));
                        break;
                    default:
                        throw new AudioException("Unsupported mixer channel configuration channel count: " + channelCount);
                }

                //Note: audio output can use an alternate device if the requested device can't be used, so we assign
                //the descriptor that was actually used by the audio output
                mAudioPlaybackDevice = mAudioOutput.getAudioPlaybackDeviceDescriptor();

                for(AudioChannel audioChannel : mAudioOutput.getAudioProvider().getAudioChannels())
                {
                    audioChannel.setIdleStateListener(AUDIO_CHANNEL_IDLE_LISTENER);
                }
            }
            finally
            {
                mAudioChannelsLock.unlock();
            }

            mControllerBroadcaster.broadcast(new AudioEvent(AudioEvent.Type.AUDIO_CONFIGURATION_CHANGE_COMPLETE,
                    mAudioPlaybackDevice.getMixerInfo().getName()));
        }
    }

    /**
     * Audio output device.  Note: this can be null depending on when it is accessed.
     * @return audio output or null.
     */
    public AudioOutput getAudioOutput()
    {
        return mAudioOutput;
    }

    /**
     * Current audio playback mixer channel configuration setting.
     */
    @Override
    public AudioPlaybackDeviceDescriptor getAudioPlaybackDevice()
    {
        return mAudioPlaybackDevice;
    }

    /**
     * List of sorted audio outputs available for the current mixer channel configuration
     */
    @Override
    public List<AudioChannel> getAudioChannels()
    {
        if(mAudioOutput != null)
        {
            return mAudioOutput.getAudioProvider().getAudioChannels();
        }

        return Collections.emptyList();
    }

    /**
     * Adds an audio event listener to receive audio event notifications.
     */
    @Override
    public void addAudioEventListener(Listener<AudioEvent> listener)
    {
        mControllerBroadcaster.addListener(listener);
    }

    /**
     * Removes an audio event listener from receiving audio event notifications.
     */
    @Override
    public void removeAudioEventListener(Listener<AudioEvent> listener)
    {
        mControllerBroadcaster.removeListener(listener);
    }

    /**
     * Scheduled runnable to process incoming audio segments
     */
    public class AudioSegmentProcessor implements Runnable
    {
        private final AtomicBoolean mProcessing = new AtomicBoolean();

        private record QueueSnapshot(int incoming, int contexts, int waiting, int ready)
        {
        }

        private record WatchdogRescueSummary(boolean acceptedIncomingSegment, boolean promotedWaitingSegment,
                                             boolean assignedReadySegment, String rescuedSegments)
        {
            boolean rescuedWork()
            {
                return acceptedIncomingSegment || promotedWaitingSegment || assignedReadySegment;
            }

            String rescuedTypes()
            {
                StringBuilder sb = new StringBuilder();

                if(acceptedIncomingSegment)
                {
                    sb.append("incoming-accept");
                }

                if(promotedWaitingSegment)
                {
                    if(!sb.isEmpty())
                    {
                        sb.append(",");
                    }
                    sb.append("waiting-promotion");
                }

                if(assignedReadySegment)
                {
                    if(!sb.isEmpty())
                    {
                        sb.append(",");
                    }
                    sb.append("ready-assignment");
                }

                return sb.toString();
            }
        }

        private class RescueAccumulator
        {
            private final Set<PlayableAudioCall> mRescuedSegments = new HashSet<>();
            private boolean mAcceptedIncomingSegment;
            private boolean mPromotedWaitingSegment;
            private boolean mAssignedReadySegment;

            private void addRescued(PlayableAudioCall audioSegment)
            {
                if(audioSegment != null)
                {
                    mRescuedSegments.add(audioSegment);
                }
            }

            private WatchdogRescueSummary toSummary()
            {
                return new WatchdogRescueSummary(mAcceptedIncomingSegment, mPromotedWaitingSegment, mAssignedReadySegment,
                    formatRescuedSegments());
            }

            private String formatRescuedSegments()
            {
                if(mRescuedSegments.isEmpty())
                {
                    return "";
                }

                List<String> formatted = new ArrayList<>();

                for(PlayableAudioCall audioSegment : mRescuedSegments)
                {
                    if(audioSegment != null)
                    {
                        formatted.add(formatSegmentSummary(audioSegment));
                    }
                }

                Collections.sort(formatted);
                return String.join(",", formatted);
            }

            private String formatSegmentSummary(PlayableAudioCall audioSegment)
            {
                if(audioSegment == null)
                {
                    return "null";
                }

                StringBuilder sb = new StringBuilder();
                sb.append(audioSegment.callId());
                sb.append("[decoder=").append(getDecoderType(audioSegment));
                sb.append(",buffers=").append(audioSegment.getAudioBufferCount());
                sb.append(",complete=").append(audioSegment.isComplete());
                sb.append(",encrypted=").append(audioSegment.isEncrypted());
                sb.append("]");
                return sb.toString();
            }

            private String getDecoderType(PlayableAudioCall audioSegment)
            {
                if(audioSegment == null || audioSegment.getIdentifierCollection() == null)
                {
                    return UNKNOWN;
                }

                List<Identifier> decoderTypeIdentifiers =
                    audioSegment.getIdentifierCollection().getIdentifiers(Form.DECODER_TYPE);

                if(!decoderTypeIdentifiers.isEmpty())
                {
                    Identifier identifier = decoderTypeIdentifiers.getFirst();
                    return identifier != null ? identifier.toString() : UNKNOWN;
                }

                return UNKNOWN;
            }
        }

        /**
         * Processes new audio segments and automatically assigns them to audio outputs.
         *
         * Note: this method is intended to be repeatedly invoked by a scheduled processing thread.
         */
        private WatchdogRescueSummary processAudioSegments(boolean watchdog)
        {
            RescueAccumulator rescueAccumulator = new RescueAccumulator();
            drainIncomingSegments(watchdog, rescueAccumulator);
            refreshCallContexts(watchdog, rescueAccumulator);
            assignLinkedReadySegments(watchdog, rescueAccumulator);
            assignReadySegments(watchdog, rescueAccumulator);
            return rescueAccumulator.toSummary();
        }

        private void drainIncomingSegments(boolean watchdog, RescueAccumulator rescueAccumulator)
        {
            PlayableAudioCall incomingSegment = mIncomingSegments.poll();

            while(incomingSegment != null)
            {
                if(!isSuppressedDuplicate(incomingSegment) && !incomingSegment.isDoNotMonitor() &&
                    !(incomingSegment.isComplete() && !incomingSegment.hasAudio()))
                {
                    PlaybackCallState initialState = incomingSegment.hasAudio() ? PlaybackCallState.READY :
                        PlaybackCallState.WAITING_FOR_AUDIO;
                    mCallContexts.put(incomingSegment, new PlaybackCallContext(incomingSegment, initialState));

                    if(watchdog)
                    {
                        rescueAccumulator.mAcceptedIncomingSegment = true;
                    }

                    rescueAccumulator.addRescued(incomingSegment);
                }

                incomingSegment = mIncomingSegments.poll();
            }
        }

        private void refreshCallContexts(boolean watchdog, RescueAccumulator rescueAccumulator)
        {
            if(mCallContexts.isEmpty())
            {
                return;
            }

            Iterator<Map.Entry<PlayableAudioCall, PlaybackCallContext>> iterator = mCallContexts.entrySet().iterator();

            while(iterator.hasNext())
            {
                Map.Entry<PlayableAudioCall, PlaybackCallContext> entry = iterator.next();
                PlaybackCallContext context = entry.getValue();
                PlayableAudioCall audioSegment = context.audioCall();

                if(audioSegment == null)
                {
                    iterator.remove();
                    continue;
                }

                if(audioSegment.isDoNotMonitor() || isSuppressedDuplicate(audioSegment))
                {
                    iterator.remove();
                }
                else if(audioSegment.isComplete() && !audioSegment.hasAudio())
                {
                    iterator.remove();
                }
                else if(context.state() == PlaybackCallState.WAITING_FOR_AUDIO && audioSegment.hasAudio())
                {
                    entry.setValue(context.withState(PlaybackCallState.READY));

                    if(watchdog)
                    {
                        rescueAccumulator.mPromotedWaitingSegment = true;
                    }

                    rescueAccumulator.addRescued(audioSegment);
                }
            }
        }

        private void assignLinkedReadySegments(boolean watchdog, RescueAccumulator rescueAccumulator)
        {
            if(mCallContexts.isEmpty() || mAudioOutput == null)
            {
                return;
            }

            mAudioChannelsLock.lock();

            try
            {
                for(AudioChannel audioChannel : mAudioOutput.getAudioProvider().getAudioChannels())
                {
                    if(audioChannel == null || !audioChannel.isIdle())
                    {
                        continue;
                    }

                    PlayableAudioCall linkedSegment = findLinkedReadySegment(audioChannel);

                    if(linkedSegment != null)
                    {
                        mCallContexts.remove(linkedSegment);
                        transferToAudioChannel(linkedSegment, audioChannel);

                        if(watchdog)
                        {
                            rescueAccumulator.mAssignedReadySegment = true;
                        }

                        rescueAccumulator.addRescued(linkedSegment);
                    }
                }
            }
            finally
            {
                mAudioChannelsLock.unlock();
            }
        }

        private PlayableAudioCall findLinkedReadySegment(AudioChannel audioChannel)
        {
            for(PlaybackCallContext context : mCallContexts.values())
            {
                if(context != null && context.state() == PlaybackCallState.READY)
                {
                    PlayableAudioCall audioSegment = context.audioCall();
                    if(audioSegment != null && audioSegment.isLinked() && audioChannel.isLinkedTo(audioSegment))
                    {
                        return audioSegment;
                    }
                }
            }

            return null;
        }

        private boolean isSuppressedDuplicate(PlayableAudioCall audioSegment)
        {
            return audioSegment.isDuplicate() &&
                mUserPreferences.getCallManagementPreference().isDuplicatePlaybackSuppressionEnabled();
        }

        private void assignReadySegments(boolean watchdog, RescueAccumulator rescueAccumulator)
        {
            if(mCallContexts.isEmpty() || mAudioOutput == null)
            {
                return;
            }

            List<PlayableAudioCall> readySegments = new ArrayList<>();

            for(PlaybackCallContext context : mCallContexts.values())
            {
                if(context != null && context.state() == PlaybackCallState.READY)
                {
                    PlayableAudioCall audioSegment = context.audioCall();
                    if(audioSegment != null && !audioSegment.isLinked())
                    {
                        readySegments.add(audioSegment);
                    }
                }
            }

            if(readySegments.isEmpty())
            {
                return;
            }

            readySegments.sort(mAudioSegmentPrioritySorter);
            mAudioChannelsLock.lock();

            try
            {
                for(AudioChannel audioChannel : mAudioOutput.getAudioProvider().getAudioChannels())
                {
                    if(audioChannel == null || !audioChannel.isIdle() || readySegments.isEmpty())
                    {
                        continue;
                    }

                    PlayableAudioCall assignedSegment = readySegments.removeFirst();
                    mCallContexts.remove(assignedSegment);
                    transferToAudioChannel(assignedSegment, audioChannel);

                    if(watchdog)
                    {
                        rescueAccumulator.mAssignedReadySegment = true;
                    }

                    rescueAccumulator.addRescued(assignedSegment);
                }
            }
            finally
            {
                mAudioChannelsLock.unlock();
            }
        }

        @Override
        public void run()
        {
            run(false);
        }

        public void run(boolean watchdog)
        {
            if(mProcessing.compareAndSet(false, true))
            {
                try
                {
                    if(!watchdog)
                    {
                        mNormalRunsStarted.incrementAndGet();
                        mLastNormalRunStartTimestamp.set(System.currentTimeMillis());
                    }

                    QueueSnapshot preSnapshot = snapshotQueues();

                    WatchdogRescueSummary summary = processAudioSegments(watchdog);

                    if(watchdog && summary.rescuedWork() &&
                        (System.currentTimeMillis() - mCreatedTimestamp) >= WATCHDOG_STARTUP_GRACE_PERIOD_MS)
                    {
                        QueueSnapshot postSnapshot = snapshotQueues();
                        mLog.warn("Playback watchdog rescued work types:{} segments:{} pre[incoming:{} contexts:{} waiting:{} ready:{}] post[incoming:{} contexts:{} waiting:{} ready:{}] channels:{} normal:{}",
                            summary.rescuedTypes(), summary.rescuedSegments(),
                            preSnapshot.incoming(), preSnapshot.contexts(), preSnapshot.waiting(), preSnapshot.ready(),
                            postSnapshot.incoming(), postSnapshot.contexts(), postSnapshot.waiting(), postSnapshot.ready(),
                            formatChannels(getAudioChannels()), formatNormalProcessingState());
                    }
                }
                catch(Exception e)
                {
                    mLog.error("Encountered error while processing audio segments", e);
                }
                finally
                {
                    if(!watchdog)
                    {
                        mNormalRunsCompleted.incrementAndGet();
                        mLastNormalRunCompleteTimestamp.set(System.currentTimeMillis());
                    }

                    mProcessing.set(false);
                }
            }
        }

        private QueueSnapshot snapshotQueues()
        {
            int waiting = 0;
            int ready = 0;

            for(PlaybackCallContext context : mCallContexts.values())
            {
                if(context == null)
                {
                    continue;
                }

                if(context.state() == PlaybackCallState.READY)
                {
                    ready++;
                }
                else
                {
                    waiting++;
                }
            }

            return new QueueSnapshot(mIncomingSegments.size(), mCallContexts.size(), waiting, ready);
        }

        private void transferToAudioChannel(PlayableAudioCall audioSegment, AudioChannel audioChannel)
        {
            if(audioSegment != null && audioChannel != null)
            {
                audioChannel.play(audioSegment);
            }
        }

        private String formatChannels(List<AudioChannel> audioChannels)
        {
            StringBuilder sb = new StringBuilder();

            for(AudioChannel audioChannel: audioChannels)
            {
                if(!sb.isEmpty())
                {
                    sb.append(",");
                }

                sb.append(audioChannel.getChannelName());
                sb.append("[idle=").append(audioChannel.isIdle());
                sb.append(",hasSegment=").append(audioChannel.hasAudioSegment()).append("]");
            }

            return sb.toString();
        }

        private String formatNormalProcessingState()
        {
            long now = System.currentTimeMillis();
            long lastStart = mLastNormalRunStartTimestamp.get();
            long lastComplete = mLastNormalRunCompleteTimestamp.get();

            return "start=" + mNormalRunsStarted.get() +
                " done=" + mNormalRunsCompleted.get() +
                " lastStartAgoMs=" + (lastStart > 0 ? now - lastStart : -1) +
                " lastDoneAgoMs=" + (lastComplete > 0 ? now - lastComplete : -1);
        }

    }

    /**
     * Audio segment comparator for sorting audio segments by: 1)Playback priority and 2)Segment start time
     */
    public static class AudioSegmentPrioritySorter implements Comparator<PlayableAudioCall>
    {
        @Override
        public int compare(PlayableAudioCall segment1, PlayableAudioCall segment2)
        {
            if(segment1 == null || segment2 == null)
            {
                return -1;
            }

            //If priority is the same, sort by start time
            if(segment1.getMonitorPriority() == segment2.getMonitorPriority())
            {
                return Long.compare(segment1.getStartTimestamp(), segment2.getStartTimestamp());
            }
            //Otherwise, sort by priority
            else
            {
                return Integer.compare(segment1.getMonitorPriority(), segment2.getMonitorPriority());
            }
        }
    }
}
