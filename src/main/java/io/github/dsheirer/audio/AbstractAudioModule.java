/*
 * *****************************************************************************
 * Copyright (C) 2014-2023 Dennis Sheirer
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

package io.github.dsheirer.audio;

import com.google.common.eventbus.Subscribe;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionEvidence;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.IAudioCallProvider;
import io.github.dsheirer.audio.call.MutableAudioCallBuilder;
import io.github.dsheirer.audio.call.VoiceFrameQualityObservation;
import io.github.dsheirer.controller.channel.ChannelConfigurationChangeNotification;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.IdentifierUpdateListener;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Base audio module implementation.
 */
public abstract class AbstractAudioModule extends Module implements IAudioCallProvider,
    IdentifierUpdateListener
{
    public static final long DEFAULT_SEGMENT_AUDIO_LENGTH_MILLISECONDS = 60_000L;
    public static final int DEFAULT_TIMESLOT = 0;
    private static final int AUDIO_FRAME_SNAPSHOT_INTERVAL = 50;
    private static final AtomicLong NEXT_PRODUCER_ID =
        new AtomicLong(ThreadLocalRandom.current().nextLong());
    private final int mMaxSegmentAudioSampleLength;
    private volatile CallLegSource mCallLegSource;
    private volatile Listener<AudioCallEvent> mAudioCallEventListener;
    protected MutableIdentifierCollection mIdentifierCollection;
    private Broadcaster<IdentifierUpdateNotification> mIdentifierUpdateNotificationBroadcaster = new Broadcaster<>();
    private AliasList mAliasList;
    private int mAudioSampleCount = 0;
    private boolean mRecordAudioOverride;
    private int mTimeslot;
    private final long mProducerId = NEXT_PRODUCER_ID.getAndIncrement();
    private long mNextAudioCallSequence = 1;
    private AudioCallId mCurrentAudioCallId;
    private AudioCallId mCurrentLinkedAudioCallId;
    private AudioCallId mPreviousAudioCallId;
    private CallLegId mCurrentCallLegId;
    private CallLegId mPreviousCallLegId;
    private boolean mLinkNextAudioCallToPrevious;
    private MutableAudioCallBuilder mCurrentAudioCall;
    private AudioCallSnapshot mLastPublishedAudioCallSnapshot;

    /**
     * Constructs an abstract audio module
     *
     * @param aliasList for aliasing identifiers
     * @param maxSegmentAudioSampleLength in milliseconds
     */
    protected AbstractAudioModule(AliasList aliasList, int timeslot, long maxSegmentAudioSampleLength)
    {
        this(aliasList, timeslot, maxSegmentAudioSampleLength, CallLegSource.UNKNOWN);
    }

    /**
     * Constructs an abstract audio module with immutable source evidence for every call leg it emits.
     */
    protected AbstractAudioModule(AliasList aliasList, int timeslot, long maxSegmentAudioSampleLength,
                                  CallLegSource callLegSource)
    {
        mAliasList = aliasList;
        mMaxSegmentAudioSampleLength = (int)(maxSegmentAudioSampleLength * 8); //Convert milliseconds to samples
        mCallLegSource = callLegSource != null ? callLegSource : CallLegSource.UNKNOWN;
        mTimeslot = timeslot;
        mIdentifierCollection = new MutableIdentifierCollection(getTimeslot());
        mIdentifierUpdateNotificationBroadcaster.addListener(mIdentifierCollection);
        mIdentifierUpdateNotificationBroadcaster.addListener(notification -> {
            synchronized(AbstractAudioModule.this)
            {
                if(mCurrentAudioCall != null)
                {
                    //Apply this identifier and its alias policy before publishing the corresponding snapshot.
                    mCurrentAudioCall.receive(notification);
                    emitAudioCallEvent(AudioCallEventType.METADATA_UPDATED, null);
                }
            }
        });
    }

    /**
     * Constructs an abstract audio module with a default maximum audio segment length and a default timeslot 0.
     */
    protected AbstractAudioModule(AliasList aliasList)
    {
        this(aliasList, DEFAULT_TIMESLOT, DEFAULT_SEGMENT_AUDIO_LENGTH_MILLISECONDS);
    }

    /**
     * Timeslot for this audio module
     */
    protected int getTimeslot()
    {
        return mTimeslot;
    }

    /**
     * Applies a committed processing-chain role change.  Capacity Plus reuses the existing audio modules when its
     * rest channel becomes a traffic channel, so future snapshots must identify calls from that chain as trunked.
     * This notification is posted by the lifecycle worker after the traffic-channel map entry is authoritative.
     */
    @Subscribe
    public void channelConfigurationChanged(ChannelConfigurationChangeNotification notification)
    {
        if(notification != null && notification.getChannel() != null &&
            notification.getChannel().isTrafficChannel())
        {
            mCallLegSource = mCallLegSource.asTrafficChannel();
        }
    }

    /**
     * Closes the current audio segment
     */
    protected void closeAudioSegment()
    {
        closeAudioSegment(System.currentTimeMillis());
    }

    /**
     * Closes the current audio segment at the supplied decoder-message timestamp.
     */
    protected void closeAudioSegment(long timestamp)
    {
        synchronized(this)
        {
            if(mCurrentAudioCall != null)
            {
                mCurrentAudioCall.complete(timestamp);
                emitAudioCallEvent(AudioCallEventType.CALL_COMPLETED, null, mLinkNextAudioCallToPrevious);
                mCurrentAudioCall = null;
                mLastPublishedAudioCallSnapshot = null;
                mPreviousAudioCallId = mCurrentAudioCallId;
                mPreviousCallLegId = mCurrentCallLegId;
                mCurrentAudioCallId = null;
                mCurrentLinkedAudioCallId = null;
                mCurrentCallLegId = null;
            }
        }
    }

    @Override
    public void stop()
    {
        closeAudioSegment();
    }

    /**
     * Gets the current mutable producer-side audio call, creating it as necessary.
     */
    protected MutableAudioCallBuilder getAudioCall()
    {
        synchronized(this)
        {
            if(mCurrentAudioCall == null)
            {
                mCurrentAudioCall = new MutableAudioCallBuilder(mAliasList, getTimeslot());
                mLastPublishedAudioCallSnapshot = null;
                mCurrentAudioCallId = new AudioCallId(mProducerId, mNextAudioCallSequence++, getTimeslot());
                boolean linkedContinuation = mLinkNextAudioCallToPrevious;
                mCurrentLinkedAudioCallId = linkedContinuation ? mPreviousAudioCallId : null;
                mCurrentCallLegId = linkedContinuation && mPreviousCallLegId != null ? mPreviousCallLegId :
                    new CallLegId(mProducerId, mCurrentAudioCallId.sequence(), getTimeslot());
                mLinkNextAudioCallToPrevious = false;
                mCurrentAudioCall.addIdentifiers(asTypedIdentifiers(mIdentifierCollection.getIdentifiers()));
                if(mRecordAudioOverride)
                {
                    mCurrentAudioCall.setRecordAudio(true);
                }

                mAudioSampleCount = 0;
                emitAudioCallEvent(AudioCallEventType.CALL_CREATED, null);
            }

            return mCurrentAudioCall;
        }
    }

    /**
     * Gets the current mutable producer-side audio call without creating a new one.
     */
    protected MutableAudioCallBuilder getCurrentAudioCall()
    {
        synchronized(this)
        {
            return mCurrentAudioCall;
        }
    }

    /**
     * Marks the current segment as intentionally active without appending audio.
     */
    protected void touchCurrentAudioSegment()
    {
        touchCurrentAudioSegment(System.currentTimeMillis());
    }

    /**
     * Marks the current segment active at the supplied decoder-message timestamp.
     */
    protected void touchCurrentAudioSegment(long timestamp)
    {
        synchronized(this)
        {
            if(mCurrentAudioCall != null)
            {
                mCurrentAudioCall.touch(timestamp);
                emitAudioCallEvent(AudioCallEventType.ACTIVITY, null);
            }
        }
    }

    /**
     * Explicitly begins the current segment, creating it if necessary and pinning its start timestamp to the current
     * signaling event instead of the first audio append.
     */
    protected MutableAudioCallBuilder beginCurrentAudioSegment()
    {
        return beginCurrentAudioSegment(System.currentTimeMillis());
    }

    /**
     * Begins the current segment at the supplied decoder-message timestamp.
     */
    protected MutableAudioCallBuilder beginCurrentAudioSegment(long timestamp)
    {
        synchronized(this)
        {
            MutableAudioCallBuilder audioCall = getAudioCall();
            audioCall.begin(timestamp);
            mLastPublishedAudioCallSnapshot = null;
            emitAudioCallEvent(AudioCallEventType.ACTIVITY, null);
            return audioCall;
        }
    }

    /**
     * Marks the current audio segment as actively carrying a talk burst, creating the segment if necessary.
     */
    protected MutableAudioCallBuilder beginCurrentAudioBurst()
    {
        return beginCurrentAudioBurst(System.currentTimeMillis());
    }

    /**
     * Begins the current talk burst at the supplied decoder-message timestamp.
     */
    protected MutableAudioCallBuilder beginCurrentAudioBurst(long timestamp)
    {
        synchronized(this)
        {
            MutableAudioCallBuilder audioCall = getAudioCall();
            audioCall.beginBurst(timestamp);
            emitAudioCallEvent(AudioCallEventType.BURST_STARTED, null);
            return audioCall;
        }
    }

    /**
     * Marks the current talk burst as ended while leaving the audio segment open.
     */
    protected void endCurrentAudioBurst()
    {
        endCurrentAudioBurst(System.currentTimeMillis());
    }

    /**
     * Ends the current talk burst at the supplied decoder-message timestamp.
     */
    protected void endCurrentAudioBurst(long timestamp)
    {
        synchronized(this)
        {
            if(mCurrentAudioCall != null)
            {
                mCurrentAudioCall.endBurst(timestamp);
                emitAudioCallEvent(AudioCallEventType.BURST_ENDED, null);
            }
        }
    }

    /**
     * Adds bounded encrypted-call evidence to the current leg and publishes it as immutable metadata.  This method
     * performs no I/O and retains no raw message indicator.
     */
    protected void setCurrentCallEncryptionEvidence(CallEncryptionEvidence evidence,
                                                    EncryptionKeyIdentifier encryptionKey, long timestamp)
    {
        if(evidence == null)
        {
            return;
        }

        synchronized(this)
        {
            MutableAudioCallBuilder audioCall = getAudioCall();
            boolean changed = false;

            if(encryptionKey != null)
            {
                Identifier<?> existing = audioCall.getIdentifierCollection().getIdentifier(
                    encryptionKey.getIdentifierClass(), encryptionKey.getForm(), encryptionKey.getRole());

                if(!encryptionKey.equals(existing))
                {
                    audioCall.addIdentifier(encryptionKey);
                    changed = true;
                }
            }

            changed |= audioCall.setCallEncryptionEvidence(evidence);
            audioCall.touch(timestamp);

            if(changed)
            {
                mLastPublishedAudioCallSnapshot = null;
                emitAudioCallEvent(AudioCallEventType.METADATA_UPDATED, null);
            }
        }
    }

    /**
     * Applies an authoritative encryption state to the current call.  This is a fixed-cost in-memory update and
     * never performs decryption or observer I/O.
     */
    protected void setCurrentCallEncryptionState(CallEncryptionState encryptionState, long timestamp)
    {
        synchronized(this)
        {
            MutableAudioCallBuilder audioCall = getAudioCall();
            boolean changed = audioCall.observeEncryptionState(encryptionState);
            audioCall.touch(timestamp);

            if(changed)
            {
                mLastPublishedAudioCallSnapshot = null;
                emitAudioCallEvent(AudioCallEventType.METADATA_UPDATED, null);
            }
        }
    }

    /**
     * Marks the current call encrypted when non-P25 protocol signaling exposes only a privacy flag.
     */
    protected void markCurrentCallEncrypted(long timestamp)
    {
        setCurrentCallEncryptionState(CallEncryptionState.ENCRYPTED, timestamp);
    }

    public void addAudio(float[] audioBuffer)
    {
        addAudio(audioBuffer, null, System.currentTimeMillis(), 0);
    }

    public void addAudio(float[] audioBuffer, VoiceFrameQualityObservation qualityObservation)
    {
        addAudio(audioBuffer, qualityObservation, System.currentTimeMillis(), 0);
    }

    public void addAudio(float[] audioBuffer, long timestamp)
    {
        addAudio(audioBuffer, null, timestamp, 0);
    }

    /**
     * Adds decoded audio associated with the supplied decoder-message timestamp.
     */
    public void addAudio(float[] audioBuffer, VoiceFrameQualityObservation qualityObservation, long timestamp)
    {
        addAudio(audioBuffer, qualityObservation, timestamp, 0);
    }

    /**
     * Adds decoded audio with carrier time and a fingerprint of its received or successfully decrypted vocoder frame.
     */
    public void addAudio(float[] audioBuffer, VoiceFrameQualityObservation qualityObservation, long timestamp,
                         long voiceFrameFingerprint)
    {
        long carrierTimestamp = timestamp > 0L ? timestamp : System.currentTimeMillis();
        MutableAudioCallBuilder audioCall = getAudioCall();

        //If the current segment exceeds the max samples length, close it so that a new segment gets generated
        //and then link the segments together
        if(mAudioSampleCount >= mMaxSegmentAudioSampleLength)
        {
            mLinkNextAudioCallToPrevious = true;
            closeAudioSegment(carrierTimestamp);
            audioCall = getAudioCall();
        }

        try
        {
            audioCall.addAudio(audioBuffer, carrierTimestamp);
            audioCall.addVoiceFrameQuality(qualityObservation);
            mAudioSampleCount += audioBuffer.length;
            emitAudioCallEvent(AudioCallEventType.AUDIO_FRAME, audioBuffer, voiceFrameFingerprint, carrierTimestamp);
        }
        catch(Exception _)
        {
            closeAudioSegment(carrierTimestamp);
        }
    }

    /**
     * Sets all audio segments as recordable when the argument is true.  Otherwise, defers to the aliased identifiers
     * from the identifier collection to determine whether to record the audio or not.
     * @param recordAudio set to true to mark all audio as recordable.
     */
    public void setRecordAudio(boolean recordAudio)
    {
        mRecordAudioOverride = recordAudio;

        if(mRecordAudioOverride)
        {
            synchronized(this)
            {
                if(mCurrentAudioCall != null)
                {
                    mCurrentAudioCall.setRecordAudio(true);
                    emitAudioCallEvent(AudioCallEventType.METADATA_UPDATED, null);
                }
            }
        }
    }

    /**
     * Receive updated identifiers from decoder state(s).
     */
    @Override
    public Listener<IdentifierUpdateNotification> getIdentifierUpdateListener()
    {
        return mIdentifierUpdateNotificationBroadcaster;
    }

    /**
     * Identifier collection containing the current set of identifiers received from the decoder state(s).
     */
    public MutableIdentifierCollection getIdentifierCollection()
    {
        return mIdentifierCollection;
    }

    @Override
    public void setAudioCallEventListener(Listener<AudioCallEvent> listener)
    {
        mAudioCallEventListener = listener;
    }

    @Override
    public void removeAudioCallEventListener()
    {
        mAudioCallEventListener = null;
    }

    private AudioCallSnapshot getCurrentAudioCallSnapshot()
    {
        return createSnapshot(mCurrentAudioCall, mCurrentAudioCallId, mCurrentLinkedAudioCallId);
    }

    /**
     * A voice decoder can publish fifty audio frames per second.  The identifiers, Alias actions, and broadcast
     * destinations normally do not change between those frames, so rebuilding those collections on every decoder
     * callback adds avoidable work to the real-time path.  Structural changes always receive a fresh snapshot; audio
     * diagnostics refresh on the first frame and once per second.  Other frames reuse the latest immutable snapshot,
     * while the terminal event captures the complete final state.
     */
    private AudioCallSnapshot getSnapshotForEvent(AudioCallEventType eventType)
    {
        boolean refresh = switch(eventType)
        {
            case CALL_CREATED, METADATA_UPDATED, BURST_STARTED, BURST_ENDED, CALL_COMPLETED -> true;
            case AUDIO_FRAME -> mCurrentAudioCall != null &&
                (mCurrentAudioCall.getAudioBufferCount() == 1 ||
                    mCurrentAudioCall.getAudioBufferCount() % AUDIO_FRAME_SNAPSHOT_INTERVAL == 0);
            case ACTIVITY -> false;
        };

        if(refresh || mLastPublishedAudioCallSnapshot == null)
        {
            mLastPublishedAudioCallSnapshot = getCurrentAudioCallSnapshot();
        }

        return mLastPublishedAudioCallSnapshot;
    }

    private AudioCallSnapshot createSnapshot(MutableAudioCallBuilder audioCall, AudioCallId callId, AudioCallId linkedCallId)
    {
        if(audioCall == null || callId == null)
        {
            return null;
        }

        IdentifierCollection identifierCollection =
            new IdentifierCollection(audioCall.getIdentifierCollection().getIdentifiers());
        identifierCollection.setTimeslot(callId.timeslot());
        Set<BroadcastChannel> broadcastChannels = Set.copyOf(audioCall.getBroadcastChannels());

        return new AudioCallSnapshot(callId, linkedCallId, mAliasList, identifierCollection, broadcastChannels,
            audioCall.getStartTimestamp(), audioCall.getLastActivityTimestamp(), audioCall.getBurstCount(),
            audioCall.getBurstGeneration(), audioCall.getLastBurstStartTimestamp(),
            audioCall.getLastBurstEndTimestamp(), audioCall.isBurstActive(), audioCall.isComplete(),
            audioCall.getEncryptionState(),
            audioCall.isRecordAudio(), audioCall.getRecordingMetadata(), audioCall.getVoiceCallQuality(),
            mCurrentCallLegId, mCallLegSource, audioCall.getCallEncryptionEvidence());
    }

    private void emitAudioCallEvent(AudioCallEventType eventType, float[] audioFrame)
    {
        emitAudioCallEvent(eventType, audioFrame, false);
    }

    private void emitAudioCallEvent(AudioCallEventType eventType, float[] audioFrame, long voiceFrameFingerprint,
                                    long voiceFrameTimestamp)
    {
        emitAudioCallEvent(eventType, audioFrame, false, voiceFrameFingerprint, voiceFrameTimestamp);
    }

    private void emitAudioCallEvent(AudioCallEventType eventType, float[] audioFrame, boolean continuationExpected)
    {
        emitAudioCallEvent(eventType, audioFrame, continuationExpected, 0, 0);
    }

    private void emitAudioCallEvent(AudioCallEventType eventType, float[] audioFrame, boolean continuationExpected,
                                    long voiceFrameFingerprint, long voiceFrameTimestamp)
    {
        Listener<AudioCallEvent> listener = mAudioCallEventListener;

        if(listener == null)
        {
            return;
        }

        AudioCallSnapshot snapshot = getSnapshotForEvent(eventType);

        if(snapshot != null)
        {
            listener.receive(new AudioCallEvent(eventType, snapshot, audioFrame,
                continuationExpected, voiceFrameFingerprint, voiceFrameTimestamp));
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<? extends Identifier<?>> asTypedIdentifiers(Collection<Identifier> identifiers)
    {
        return (Collection<? extends Identifier<?>>)(Collection<?>)identifiers;
    }
}
