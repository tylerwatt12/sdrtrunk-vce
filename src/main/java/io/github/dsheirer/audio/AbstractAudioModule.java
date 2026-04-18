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

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.call.AudioCallEvent;
import io.github.dsheirer.audio.call.AudioCallEventType;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.IAudioCallProvider;
import io.github.dsheirer.audio.call.MutableAudioCallBuilder;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.IdentifierUpdateListener;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.Module;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * Base audio module implementation.
 */
public abstract class AbstractAudioModule extends Module implements IAudioCallProvider,
    IdentifierUpdateListener
{
    public static final long DEFAULT_SEGMENT_AUDIO_SAMPLE_LENGTH = 60L * 8000; // 1 minute @ 8kHz
    public static final int DEFAULT_TIMESLOT = 0;
    private final int mMaxSegmentAudioSampleLength;
    private Listener<AudioCallEvent> mAudioCallEventListener;
    protected MutableIdentifierCollection mIdentifierCollection;
    private Broadcaster<IdentifierUpdateNotification> mIdentifierUpdateNotificationBroadcaster = new Broadcaster<>();
    private AliasList mAliasList;
    private int mAudioSampleCount = 0;
    private boolean mRecordAudioOverride;
    private int mTimeslot;
    private final long mProducerId = System.identityHashCode(this);
    private long mNextAudioCallSequence = 1;
    private AudioCallId mCurrentAudioCallId;
    private AudioCallId mCurrentLinkedAudioCallId;
    private AudioCallId mPreviousAudioCallId;
    private boolean mLinkNextAudioCallToPrevious;
    private MutableAudioCallBuilder mCurrentAudioCall;

    /**
     * Constructs an abstract audio module
     *
     * @param aliasList for aliasing identifiers
     * @param maxSegmentAudioSampleLength in milliseconds
     */
    protected AbstractAudioModule(AliasList aliasList, int timeslot, long maxSegmentAudioSampleLength)
    {
        mAliasList = aliasList;
        mMaxSegmentAudioSampleLength = (int)(maxSegmentAudioSampleLength * 8); //Convert milliseconds to samples
        mTimeslot = timeslot;
        mIdentifierCollection = new MutableIdentifierCollection(getTimeslot());
        mIdentifierUpdateNotificationBroadcaster.addListener(mIdentifierCollection);
        mIdentifierUpdateNotificationBroadcaster.addListener(notification -> {
            synchronized(AbstractAudioModule.this)
            {
                if(mCurrentAudioCall != null)
                {
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
        this(aliasList, DEFAULT_TIMESLOT, DEFAULT_SEGMENT_AUDIO_SAMPLE_LENGTH);
    }

    /**
     * Timeslot for this audio module
     */
    protected int getTimeslot()
    {
        return mTimeslot;
    }

    /**
     * Closes the current audio segment
     */
    protected void closeAudioSegment()
    {
        synchronized(this)
        {
            if(mCurrentAudioCall != null)
            {
                mCurrentAudioCall.complete();
                emitAudioCallEvent(AudioCallEventType.CALL_COMPLETED, null);
                mIdentifierUpdateNotificationBroadcaster.removeListener(mCurrentAudioCall);
                mCurrentAudioCall = null;
                mPreviousAudioCallId = mCurrentAudioCallId;
                mCurrentAudioCallId = null;
                mCurrentLinkedAudioCallId = null;
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
                mCurrentAudioCallId = new AudioCallId(mProducerId, mNextAudioCallSequence++, getTimeslot());
                mCurrentLinkedAudioCallId = mLinkNextAudioCallToPrevious ? mPreviousAudioCallId : null;
                mLinkNextAudioCallToPrevious = false;
                mCurrentAudioCall.addIdentifiers(asTypedIdentifiers(mIdentifierCollection.getIdentifiers()));
                mIdentifierUpdateNotificationBroadcaster.addListener(mCurrentAudioCall);

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
        synchronized(this)
        {
            if(mCurrentAudioCall != null)
            {
                mCurrentAudioCall.touch();
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
        synchronized(this)
        {
            MutableAudioCallBuilder audioCall = getAudioCall();
            audioCall.begin();
            emitAudioCallEvent(AudioCallEventType.ACTIVITY, null);
            return audioCall;
        }
    }

    /**
     * Marks the current audio segment as actively carrying a talk burst, creating the segment if necessary.
     */
    protected MutableAudioCallBuilder beginCurrentAudioBurst()
    {
        synchronized(this)
        {
            MutableAudioCallBuilder audioCall = getAudioCall();
            audioCall.beginBurst();
            emitAudioCallEvent(AudioCallEventType.BURST_STARTED, null);
            return audioCall;
        }
    }

    /**
     * Marks the current talk burst as ended while leaving the audio segment open.
     */
    protected void endCurrentAudioBurst()
    {
        synchronized(this)
        {
            if(mCurrentAudioCall != null)
            {
                mCurrentAudioCall.endBurst();
                emitAudioCallEvent(AudioCallEventType.BURST_ENDED, null);
            }
        }
    }

    public void addAudio(float[] audioBuffer)
    {
        MutableAudioCallBuilder audioCall = getAudioCall();

        //If the current segment exceeds the max samples length, close it so that a new segment gets generated
        //and then link the segments together
        if(mAudioSampleCount >= mMaxSegmentAudioSampleLength)
        {
            mLinkNextAudioCallToPrevious = true;
            closeAudioSegment();
            audioCall = getAudioCall();
        }

        try
        {
            audioCall.addAudio(audioBuffer);
            mAudioSampleCount += audioBuffer.length;
            emitAudioCallEvent(AudioCallEventType.AUDIO_FRAME, audioBuffer);
        }
        catch(Exception _)
        {
            closeAudioSegment();
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

    private AudioCallSnapshot createSnapshot(MutableAudioCallBuilder audioCall, AudioCallId callId, AudioCallId linkedCallId)
    {
        if(audioCall == null || callId == null)
        {
            return null;
        }

        IdentifierCollection identifierCollection =
            new IdentifierCollection(audioCall.getIdentifierCollection().getIdentifiers());
        identifierCollection.setTimeslot(callId.timeslot());
        Set<BroadcastChannel> broadcastChannels = new HashSet<>(audioCall.getBroadcastChannels());

        return new AudioCallSnapshot(callId, linkedCallId, mAliasList, identifierCollection, broadcastChannels,
            audioCall.getStartTimestamp(), audioCall.getLastActivityTimestamp(), audioCall.getBurstCount(),
            audioCall.getBurstGeneration(), audioCall.getLastBurstStartTimestamp(),
            audioCall.getLastBurstEndTimestamp(), audioCall.isBurstActive(), audioCall.isComplete(), audioCall.isEncrypted(),
            audioCall.isRecordAudio(), audioCall.getMonitorPriority(), audioCall.isDuplicate());
    }

    private void emitAudioCallEvent(AudioCallEventType eventType, float[] audioFrame)
    {
        if(mAudioCallEventListener == null)
        {
            return;
        }

        AudioCallSnapshot snapshot = getCurrentAudioCallSnapshot();

        if(snapshot != null)
        {
            mAudioCallEventListener.receive(new AudioCallEvent(eventType, snapshot, System.currentTimeMillis(),
                audioFrame));
        }
    }

    @SuppressWarnings("unchecked")
    private static Collection<? extends Identifier<?>> asTypedIdentifiers(Collection<Identifier> identifiers)
    {
        return (Collection<? extends Identifier<?>>)(Collection<?>)identifiers;
    }
}
