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
import io.github.dsheirer.audio.AudioSegment;
import io.github.dsheirer.audio.AudioSegment.AudioSegmentLifecycleListener;
import io.github.dsheirer.audio.AudioSegment.AudioSegmentLifecycleEvent;
import io.github.dsheirer.audio.AudioSegment.AudioSegmentLifecycleEventType;
import io.github.dsheirer.audio.IAudioController;
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
public class AudioPlaybackManager implements Listener<AudioSegment>, AudioSegmentLifecycleListener, IAudioController
{
    private static final Logger mLog = LoggerFactory.getLogger(AudioPlaybackManager.class);
    private static final String UNKNOWN = "unknown";
    private static final long WATCHDOG_INTERVAL_MS = 1000;
    private static final long WATCHDOG_STARTUP_GRACE_PERIOD_MS = 5000;
    private record QueuedLifecycleEvent(AudioSegmentLifecycleEvent event, long audioAvailableSequence, long enqueuedAt)
    {
    }
    private final AudioSegmentPrioritySorter mAudioSegmentPrioritySorter = new AudioSegmentPrioritySorter();
    private final Broadcaster<AudioEvent> mControllerBroadcaster = new Broadcaster<>();
    private final List<AudioSegment> mAudioSegments = new ArrayList<>();
    private final List<AudioSegment> mPendingAudioSegments = new ArrayList<>();
    private final LinkedTransferQueue<AudioSegment> mNewAudioSegmentQueue = new LinkedTransferQueue<>();
    private final LinkedTransferQueue<QueuedLifecycleEvent> mLifecycleChangedSegments = new LinkedTransferQueue<>();
    private final ReentrantLock mAudioChannelsLock = new ReentrantLock();
    private final UserPreferences mUserPreferences;
    private final ScheduledExecutorService mProcessingExecutorService;
    private final AudioSegmentProcessor mAudioSegmentProcessor = new AudioSegmentProcessor();
    private final Listener<AudioChannel> mAudioChannelIdleListener = audioChannel -> triggerAudioSegmentProcessing();
    private final AtomicBoolean mNormalProcessingScheduled = new AtomicBoolean();
    private final AtomicBoolean mWorkRequested = new AtomicBoolean();
    private final AtomicLong mNormalTriggerRequests = new AtomicLong();
    private final AtomicLong mNormalTriggerSchedules = new AtomicLong();
    private final AtomicLong mNormalRunsStarted = new AtomicLong();
    private final AtomicLong mNormalRunsCompleted = new AtomicLong();
    private final AtomicLong mLastNormalRunStartTimestamp = new AtomicLong();
    private final AtomicLong mLastNormalRunCompleteTimestamp = new AtomicLong();
    private final AtomicLong mAudioAvailableSequence = new AtomicLong();
    private final AtomicLong mLastAudioAvailableEnqueueSequence = new AtomicLong();
    private final AtomicLong mLastAudioAvailableEnqueueTimestamp = new AtomicLong();
    private final AtomicLong mLastAudioAvailableProcessedSequence = new AtomicLong();
    private final AtomicLong mLastAudioAvailableProcessedTimestamp = new AtomicLong();
    private final long mCreatedTimestamp = System.currentTimeMillis();
    private AudioPlaybackDeviceDescriptor mAudioPlaybackDevice;
    private AudioOutput mAudioOutput;
    private ScheduledFuture<?> mProcessingTask;

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

        //Even if we don't have an audio device, setup the queue processor to always process the audio segment queue
        mProcessingExecutorService =
                Executors.newSingleThreadScheduledExecutor(new NamingThreadFactory("sdrtrunk audio manager"));
        mProcessingTask = mProcessingExecutorService.scheduleAtFixedRate(() -> mAudioSegmentProcessor.run(true),
                0, WATCHDOG_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * Receives audio segments from channel audio modules.
     * @param audioSegment to receive and process
     */
    @Override
    public void receive(AudioSegment audioSegment)
    {
        //Enqueue before registering the lifecycle listener.  AUDIO_AVAILABLE fires synchronously on the
        //producer thread the first time addAudio() is called — if the listener were registered first,
        //the lifecycle event could arrive and be processed before the segment is visible in
        //mNewAudioSegmentQueue, leaving it stranded in mPendingAudioSegments with no further signal.
        mNewAudioSegmentQueue.add(audioSegment);
        audioSegment.addLifecycleListener(this);
        triggerAudioSegmentProcessing();
    }

    @Override
    public void receive(AudioSegmentLifecycleEvent event)
    {
        if(event != null && event.audioSegment() != null)
        {
            long audioAvailableSequence = 0;
            long enqueuedAt = 0;

            if(event.eventType() == AudioSegmentLifecycleEventType.AUDIO_AVAILABLE)
            {
                audioAvailableSequence = mAudioAvailableSequence.incrementAndGet();
                enqueuedAt = System.currentTimeMillis();
                mLastAudioAvailableEnqueueSequence.set(audioAvailableSequence);
                mLastAudioAvailableEnqueueTimestamp.set(enqueuedAt);
            }

            mLifecycleChangedSegments.add(new QueuedLifecycleEvent(event, audioAvailableSequence, enqueuedAt));
        }

        triggerAudioSegmentProcessing();
    }

    public void dispose()
    {
        MyEventBus.getGlobalEventBus().unregister(this);
        if(mProcessingTask != null)
        {
            mProcessingTask.cancel(true);
            mProcessingTask = null;
        }

        mProcessingExecutorService.shutdownNow();

        releaseAudioSegments(mNewAudioSegmentQueue);
        mLifecycleChangedSegments.clear();
        releaseAudioSegments(mPendingAudioSegments);
        releaseAudioSegments(mAudioSegments);
    }

    private void triggerAudioSegmentProcessing()
    {
        mNormalTriggerRequests.incrementAndGet();
        mWorkRequested.set(true);

        if(mNormalProcessingScheduled.compareAndSet(false, true))
        {
            mNormalTriggerSchedules.incrementAndGet();
            mProcessingExecutorService.execute(() -> {
                try
                {
                    do
                    {
                        mWorkRequested.set(false);
                        mAudioSegmentProcessor.run(false);
                    }
                    while(mWorkRequested.get());
                }
                finally
                {
                    mNormalProcessingScheduled.set(false);

                    if(mWorkRequested.get())
                    {
                        triggerAudioSegmentProcessing();
                    }
                }
            });
        }
    }

    private void releaseAudioSegments(Iterable<AudioSegment> audioSegments)
    {
        if(audioSegments == null)
        {
            return;
        }

        for(AudioSegment audioSegment : audioSegments)
        {
            if(audioSegment != null)
            {
                releaseOwnedSegment(audioSegment);
            }
        }

        if(audioSegments instanceof LinkedTransferQueue<AudioSegment> queue)
        {
            queue.clear();
        }
        else if(audioSegments instanceof List<AudioSegment> list)
        {
            list.clear();
        }
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
                    audioChannel.setIdleStateListener(mAudioChannelIdleListener);
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

        private record WatchdogRescueSummary(boolean drainedNewQueue, boolean promotedPendingSegment,
                                             boolean assignedReadySegment, String rescuedSegments)
        {
            boolean rescuedWork()
            {
                return drainedNewQueue || promotedPendingSegment || assignedReadySegment;
            }

            String rescuedTypes()
            {
                StringBuilder sb = new StringBuilder();

                if(drainedNewQueue)
                {
                    sb.append("new-queue");
                }

                if(promotedPendingSegment)
                {
                    if(!sb.isEmpty())
                    {
                        sb.append(",");
                    }
                    sb.append("pending-promotion");
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

        private record PendingChangeSummary(boolean changedWorkProcessed, Set<AudioSegment> rescuedSegments,
                                           Map<AudioSegment, AudioSegmentLifecycleEventType> rescuedEventTypes)
        {
        }

        private class RescueAccumulator
        {
            private final Set<AudioSegment> mRescuedSegments = new HashSet<>();
            private final Map<AudioSegment, AudioSegmentLifecycleEventType> mRescuedEventTypes = new HashMap<>();
            private boolean mDrainedNewQueue;
            private boolean mPromotedPendingSegment;
            private boolean mAssignedReadySegment;

            private void addRescued(AudioSegment audioSegment)
            {
                if(audioSegment != null)
                {
                    mRescuedSegments.add(audioSegment);
                }
            }

            private void addRescuedAll(Set<AudioSegment> audioSegments)
            {
                if(audioSegments != null)
                {
                    mRescuedSegments.addAll(audioSegments);
                }
            }

            private void addRescuedEventTypes(Map<AudioSegment, AudioSegmentLifecycleEventType> eventTypes)
            {
                if(eventTypes != null)
                {
                    mRescuedEventTypes.putAll(eventTypes);
                }
            }

            private WatchdogRescueSummary toSummary()
            {
                return new WatchdogRescueSummary(mDrainedNewQueue, mPromotedPendingSegment, mAssignedReadySegment,
                    formatSegments(mRescuedSegments, mRescuedEventTypes));
            }

            private String formatSegments(Set<AudioSegment> audioSegments,
                                          Map<AudioSegment, AudioSegmentLifecycleEventType> eventTypes)
            {
                if(audioSegments == null || audioSegments.isEmpty())
                {
                    return "";
                }

                List<String> formatted = new ArrayList<>();

                for(AudioSegment audioSegment : audioSegments)
                {
                    if(audioSegment != null)
                    {
                        formatted.add(formatSegmentSummary(audioSegment,
                            eventTypes != null ? eventTypes.get(audioSegment) : null));
                    }
                }

                Collections.sort(formatted);
                return String.join(",", formatted);
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
            processNewAudioSegments(watchdog, rescueAccumulator);
            processPendingLifecycleChanges(watchdog, rescueAccumulator);
            processPendingAudioSegments(watchdog, rescueAccumulator);
            logPendingInvariantViolationIfPresent(watchdog);
            pruneAndTransferReadyAudioSegments();
            assignReadyAudioSegments(watchdog, rescueAccumulator);
            removeDuplicateReadyAudioSegments();
            return rescueAccumulator.toSummary();
        }

        private void processNewAudioSegments(boolean watchdog, RescueAccumulator rescueAccumulator)
        {
            AudioSegment newSegment = mNewAudioSegmentQueue.poll();

            while(newSegment != null)
            {
                if(isSuppressedDuplicate(newSegment))
                {
                    releaseOwnedSegment(newSegment);
                }
                else if(newSegment.hasAudio())
                {
                    mAudioSegments.add(newSegment);

                    if(watchdog)
                    {
                        rescueAccumulator.mDrainedNewQueue = true;
                    }

                    rescueAccumulator.addRescued(newSegment);
                }
                else
                {
                    mPendingAudioSegments.add(newSegment);
                }

                newSegment = mNewAudioSegmentQueue.poll();
            }
        }

        private void processPendingLifecycleChanges(boolean watchdog, RescueAccumulator rescueAccumulator)
        {
            PendingChangeSummary pendingChangeSummary = processChangedPendingSegments();

            if(pendingChangeSummary.changedWorkProcessed() && watchdog)
            {
                rescueAccumulator.mPromotedPendingSegment = true;
                rescueAccumulator.addRescuedAll(pendingChangeSummary.rescuedSegments());
                rescueAccumulator.addRescuedEventTypes(pendingChangeSummary.rescuedEventTypes());
            }
        }

        private void processPendingAudioSegments(boolean watchdog, RescueAccumulator rescueAccumulator)
        {
            if(mPendingAudioSegments.isEmpty())
            {
                return;
            }

            Iterator<AudioSegment> it = mPendingAudioSegments.iterator();

            while(it.hasNext())
            {
                AudioSegment audioSegment = it.next();

                if(isSuppressedDuplicate(audioSegment))
                {
                    it.remove();
                    releaseOwnedSegment(audioSegment);
                }
                else if(audioSegment.hasAudio())
                {
                    it.remove();
                    mAudioSegments.add(audioSegment);

                    if(watchdog)
                    {
                        rescueAccumulator.mPromotedPendingSegment = true;
                    }

                    rescueAccumulator.addRescued(audioSegment);
                }
                else if(audioSegment.completeProperty().get())
                {
                    it.remove();
                    releaseOwnedSegment(audioSegment);
                }
            }
        }

        private void logPendingInvariantViolationIfPresent(boolean watchdog)
        {
            if(watchdog)
            {
                return;
            }

            for(AudioSegment audioSegment : mPendingAudioSegments)
            {
                if(audioSegment != null && audioSegment.hasAudio())
                {
                    mLog.warn("Playback pending invariant violated segment:{} queuedLifecycleEvent:{} pending:{} ready:{} new:{} lifecycle:{}",
                        formatSegmentSummary(audioSegment, null), UNKNOWN, mPendingAudioSegments.size(),
                        mAudioSegments.size(), mNewAudioSegmentQueue.size(), mLifecycleChangedSegments.size());
                    break;
                }
            }
        }

        private void pruneAndTransferReadyAudioSegments()
        {
            if(mAudioSegments.isEmpty())
            {
                return;
            }

            Iterator<AudioSegment> it = mAudioSegments.iterator();

            while(it.hasNext())
            {
                AudioSegment audioSegment = it.next();

                if(audioSegment.isDoNotMonitor() || isSuppressedDuplicate(audioSegment))
                {
                    it.remove();
                    releaseOwnedSegment(audioSegment);
                }
                else if(audioSegment.isLinked())
                {
                    transferLinkedAudioSegment(it, audioSegment);
                }
            }
        }

        private void transferLinkedAudioSegment(Iterator<AudioSegment> it, AudioSegment audioSegment)
        {
            mAudioChannelsLock.lock();

            try
            {
                for(AudioChannel audioOutput : mAudioOutput.getAudioProvider().getAudioChannels())
                {
                    if(audioOutput.isLinkedTo(audioSegment))
                    {
                        it.remove();
                        transferToAudioChannel(audioSegment, audioOutput);
                    }
                }
            }
            finally
            {
                mAudioChannelsLock.unlock();
            }
        }

        private void assignReadyAudioSegments(boolean watchdog, RescueAccumulator rescueAccumulator)
        {
            if(mAudioSegments.isEmpty())
            {
                return;
            }

            mAudioSegments.sort(mAudioSegmentPrioritySorter);
            mAudioChannelsLock.lock();

            try
            {
                for(AudioChannel audioChannel : mAudioOutput.getAudioProvider().getAudioChannels())
                {
                    if(audioChannel.isIdle() && !mAudioSegments.isEmpty())
                    {
                        AudioSegment assignedSegment = mAudioSegments.removeFirst();
                        transferToAudioChannel(assignedSegment, audioChannel);

                        if(watchdog)
                        {
                            rescueAccumulator.mAssignedReadySegment = true;
                        }

                        rescueAccumulator.addRescued(assignedSegment);
                    }
                }
            }
            finally
            {
                mAudioChannelsLock.unlock();
            }
        }

        private void removeDuplicateReadyAudioSegments()
        {
            if(mAudioSegments.isEmpty())
            {
                return;
            }

            Iterator<AudioSegment> it = mAudioSegments.iterator();

            while(it.hasNext())
            {
                AudioSegment audioSegment = it.next();

                if(isSuppressedDuplicate(audioSegment))
                {
                    it.remove();
                    releaseOwnedSegment(audioSegment);
                }
            }
        }

        private boolean isSuppressedDuplicate(AudioSegment audioSegment)
        {
            return audioSegment.isDuplicate() &&
                mUserPreferences.getCallManagementPreference().isDuplicatePlaybackSuppressionEnabled();
        }

        private PendingChangeSummary processChangedPendingSegments()
        {
            if(mLifecycleChangedSegments.isEmpty())
            {
                return new PendingChangeSummary(false, Collections.emptySet(), Collections.emptyMap());
            }

            //Drain the lifecycle queue unconditionally.  A segment may not yet be in mPendingAudioSegments
            //when AUDIO_AVAILABLE fires (it may still be in transit from the new-segment queue), so
            //short-circuiting on mPendingAudioSegments.isEmpty() would silently drop the event and strand
            //the segment until the watchdog rescues it.
            Map<AudioSegment, AudioSegmentLifecycleEventType> changedSegments = new HashMap<>();
            QueuedLifecycleEvent changed = mLifecycleChangedSegments.poll();
            long maxProcessedAudioAvailableSequence = 0;
            long maxProcessedAudioAvailableTimestamp = 0;

            while(changed != null)
            {
                changedSegments.put(changed.event().audioSegment(), changed.event().eventType());

                if(changed.audioAvailableSequence() > 0)
                {
                    maxProcessedAudioAvailableSequence =
                        Math.max(maxProcessedAudioAvailableSequence, changed.audioAvailableSequence());
                    maxProcessedAudioAvailableTimestamp =
                        Math.max(maxProcessedAudioAvailableTimestamp, changed.enqueuedAt());
                }

                changed = mLifecycleChangedSegments.poll();
            }

            if(maxProcessedAudioAvailableSequence > 0)
            {
                mLastAudioAvailableProcessedSequence.set(maxProcessedAudioAvailableSequence);
                mLastAudioAvailableProcessedTimestamp.set(maxProcessedAudioAvailableTimestamp);
            }

            if(changedSegments.isEmpty() || mPendingAudioSegments.isEmpty())
            {
                return new PendingChangeSummary(false, Collections.emptySet(), Collections.emptyMap());
            }

            Iterator<AudioSegment> it = mPendingAudioSegments.iterator();
            boolean changedWorkProcessed = false;
            Set<AudioSegment> rescuedSegments = new HashSet<>();
            Map<AudioSegment, AudioSegmentLifecycleEventType> rescuedEventTypes = new HashMap<>();
            List<AudioSegment> pendingSnapshot = new ArrayList<>(mPendingAudioSegments);

            while(it.hasNext())
            {
                AudioSegment audioSegment = it.next();

                AudioSegmentLifecycleEventType eventType = changedSegments.get(audioSegment);

                if(eventType == null)
                {
                    continue;
                }

                if(audioSegment.isDuplicate() &&
                    mUserPreferences.getCallManagementPreference().isDuplicatePlaybackSuppressionEnabled())
                {
                    it.remove();
                    releaseOwnedSegment(audioSegment);
                }
                else if(audioSegment.hasAudio())
                {
                    it.remove();
                    mAudioSegments.add(audioSegment);
                    changedWorkProcessed = true;
                    rescuedSegments.add(audioSegment);
                    rescuedEventTypes.put(audioSegment, eventType);
                }
                else if(audioSegment.completeProperty().get())
                {
                    it.remove();
                    releaseOwnedSegment(audioSegment);
                    changedWorkProcessed = true;
                }
            }

            if(!changedWorkProcessed)
            {
                logPendingChangeMiss(changedSegments, pendingSnapshot);
            }

            return new PendingChangeSummary(changedWorkProcessed, rescuedSegments, rescuedEventTypes);
        }

        private void logPendingChangeMiss(Map<AudioSegment, AudioSegmentLifecycleEventType> changedSegments,
                                          List<AudioSegment> pendingSnapshot)
        {
            if(changedSegments.isEmpty() || pendingSnapshot.isEmpty())
            {
                return;
            }

            boolean hasAudioAvailableEvent = changedSegments.containsValue(AudioSegmentLifecycleEventType.AUDIO_AVAILABLE);

            if(!hasAudioAvailableEvent)
            {
                return;
            }

            Set<Integer> changedIdentities = new HashSet<>();
            List<String> changed = new ArrayList<>();

            for(Map.Entry<AudioSegment, AudioSegmentLifecycleEventType> entry : changedSegments.entrySet())
            {
                AudioSegment audioSegment = entry.getKey();
                if(audioSegment != null)
                {
                    int identity = System.identityHashCode(audioSegment);
                    changedIdentities.add(identity);
                    changed.add(identity + ":" + entry.getValue() + ":audio=" + audioSegment.hasAudio() +
                        ":complete=" + audioSegment.completeProperty().get());
                }
            }

            List<String> pending = new ArrayList<>();
            boolean matched = false;

            for(AudioSegment audioSegment : pendingSnapshot)
            {
                if(audioSegment != null)
                {
                    int identity = System.identityHashCode(audioSegment);
                    matched |= changedIdentities.contains(identity);
                    pending.add(identity + ":audio=" + audioSegment.hasAudio() + ":complete=" +
                        audioSegment.completeProperty().get());
                }
            }

            mLog.warn("Playback pending change miss changed:{} pending:{} matched:{}",
                changed, pending, matched);
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

                    int preNewQueue = mNewAudioSegmentQueue.size();
                    int preLifecycleQueue = mLifecycleChangedSegments.size();
                    int prePending = mPendingAudioSegments.size();
                    int preReady = mAudioSegments.size();

                    WatchdogRescueSummary summary = processAudioSegments(watchdog);

                    if(watchdog && summary.rescuedWork() &&
                        (System.currentTimeMillis() - mCreatedTimestamp) >= WATCHDOG_STARTUP_GRACE_PERIOD_MS)
                    {
                        mLog.warn("Playback watchdog rescued work types:{} segments:{} pre[new:{} lifecycle:{} pending:{} ready:{}] post[new:{} lifecycle:{} pending:{} ready:{}] channels:{} normal:{}",
                            summary.rescuedTypes(), summary.rescuedSegments(),
                            preNewQueue, preLifecycleQueue, prePending, preReady,
                            mNewAudioSegmentQueue.size(), mLifecycleChangedSegments.size(), mPendingAudioSegments.size(),
                            mAudioSegments.size(), formatChannels(getAudioChannels()), formatNormalProcessingState());
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

        private void transferToAudioChannel(AudioSegment audioSegment, AudioChannel audioChannel)
        {
            if(audioSegment != null && audioChannel != null)
            {
                audioSegment.removeLifecycleListener(AudioPlaybackManager.this);
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
            long lastAudioAvailableEnqueued = mLastAudioAvailableEnqueueTimestamp.get();
            long lastAudioAvailableProcessed = mLastAudioAvailableProcessedTimestamp.get();

            return "req=" + mNormalTriggerRequests.get() +
                " sched=" + mNormalTriggerSchedules.get() +
                " start=" + mNormalRunsStarted.get() +
                " done=" + mNormalRunsCompleted.get() +
                " audioAvailEnqSeq=" + mLastAudioAvailableEnqueueSequence.get() +
                " audioAvailProcSeq=" + mLastAudioAvailableProcessedSequence.get() +
                " audioAvailEnqAgoMs=" + (lastAudioAvailableEnqueued > 0 ? now - lastAudioAvailableEnqueued : -1) +
                " audioAvailProcAgoMs=" + (lastAudioAvailableProcessed > 0 ? now - lastAudioAvailableProcessed : -1) +
                " lastStartAgoMs=" + (lastStart > 0 ? now - lastStart : -1) +
                " lastDoneAgoMs=" + (lastComplete > 0 ? now - lastComplete : -1);
        }

        private String formatSegmentSummary(AudioSegment audioSegment, AudioSegmentLifecycleEventType queuedLifecycleEvent)
        {
            if(audioSegment == null)
            {
                return "null";
            }

            StringBuilder sb = new StringBuilder();
            sb.append(formatSegment(audioSegment));
            sb.append("[decoder=").append(getDecoderType(audioSegment));
            sb.append(",buffers=").append(audioSegment.getAudioBufferCount());
            sb.append(",complete=").append(audioSegment.isComplete());
            sb.append(",encrypted=").append(audioSegment.isEncrypted());
            sb.append(",queuedLifecycleEvent=").append(queuedLifecycleEvent != null ? queuedLifecycleEvent.name() : UNKNOWN);
            sb.append("]");
            return sb.toString();
        }

        private String getDecoderType(AudioSegment audioSegment)
        {
            if(audioSegment == null || audioSegment.getIdentifierCollection() == null)
            {
                return UNKNOWN;
            }

            List<Identifier> decoderTypeIdentifiers = audioSegment.getIdentifierCollection().getIdentifiers(Form.DECODER_TYPE);

            if(!decoderTypeIdentifiers.isEmpty())
            {
                Identifier identifier = decoderTypeIdentifiers.getFirst();
                return identifier != null ? identifier.toString() : UNKNOWN;
            }

            return UNKNOWN;
        }
    }

    private String formatSegment(AudioSegment audioSegment)
    {
        if(audioSegment == null)
        {
            return "null";
        }

        return audioSegment.getTimeslot() + ":" + audioSegment.getStartTimestamp() + ":" +
            System.identityHashCode(audioSegment);
    }

    private String formatSegments(List<AudioSegment> audioSegments)
    {
        StringBuilder sb = new StringBuilder();

        for(AudioSegment audioSegment: audioSegments)
        {
            if(!sb.isEmpty())
            {
                sb.append(",");
            }

            sb.append(formatSegment(audioSegment));
            sb.append("[buffers=").append(audioSegment.getAudioBufferCount());
            sb.append(",complete=").append(audioSegment.isComplete()).append("]");
        }

        return sb.toString();
    }

    private void releaseOwnedSegment(AudioSegment audioSegment)
    {
        if(audioSegment != null)
        {
            audioSegment.removeLifecycleListener(this);
            audioSegment.decrementConsumerCount();
        }
    }

    /**
     * Audio segment comparator for sorting audio segments by: 1)Playback priority and 2)Segment start time
     */
    public static class AudioSegmentPrioritySorter implements Comparator<AudioSegment>
    {
        @Override
        public int compare(AudioSegment segment1, AudioSegment segment2)
        {
            if(segment1 == null || segment2 == null)
            {
                return -1;
            }

            //If priority is the same, sort by start time
            if(segment1.monitorPriorityProperty().get() == segment2.monitorPriorityProperty().get())
            {
                return Long.compare(segment1.getStartTimestamp(), segment2.getStartTimestamp());
            }
            //Otherwise, sort by priority
            else
            {
                return Integer.compare(segment1.monitorPriorityProperty().get(), segment2.monitorPriorityProperty().get());
            }
        }
    }
}
