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

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.UnmatchedTalkgroupPolicy;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.sample.Listener;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Producer-side mutable call assembly object that preserves only the state needed to emit immutable
 * AudioCallSnapshot updates.
 */
public class MutableAudioCallBuilder implements Listener<IdentifierUpdateNotification>
{
    private final AliasList mAliasList;
    private final int mTimeslot;
    private final MutableIdentifierCollection mIdentifierCollection = new MutableIdentifierCollection();
    private final Set<BroadcastChannel> mBroadcastChannels = new HashSet<>();
    private AudioCallRecordingMetadata.DestinationDecision mRecordingDestination;
    private AudioCallRecordingMetadata.SourceDecision mRecordingSource;
    private AudioCallRecordingMetadata mRecordingMetadata;
    private CallEncryptionEvidence mCallEncryptionEvidence;
    private CallEncryptionState mEncryptionState = CallEncryptionState.UNKNOWN;
    private long mStartTimestamp = System.currentTimeMillis();
    private long mLastActivityTimestamp = mStartTimestamp;
    private long mLastBurstStartTimestamp;
    private long mLastBurstEndTimestamp;
    private long mSampleCount;
    private long mBurstGeneration;
    private boolean mStartTimestampPinned;
    private boolean mLastActivityTimestampPinned;
    private boolean mBurstActive;
    private boolean mComplete;
    private boolean mEncryptionStateConflicted;
    private boolean mRecordAudioOverride;
    private boolean mRecordAudio;
    private int mBurstCount;
    private int mAudioBufferCount;
    private long mDecodedVoiceFrameCount;
    private long mRepeatedVoiceFrameCount;
    private long mConcealedVoiceFrameCount;
    private long mFecErrorCount;
    private long mFecProtectedBitCount;

    public MutableAudioCallBuilder(AliasList aliasList, int timeslot)
    {
        mAliasList = aliasList;
        mTimeslot = timeslot;
        mIdentifierCollection.setTimeslot(timeslot);
    }

    public int getTimeslot()
    {
        return mTimeslot;
    }

    public long getStartTimestamp()
    {
        return mStartTimestamp;
    }

    public long getLastActivityTimestamp()
    {
        return mLastActivityTimestamp;
    }

    public long getLastBurstStartTimestamp()
    {
        return mLastBurstStartTimestamp;
    }

    public long getLastBurstEndTimestamp()
    {
        return mLastBurstEndTimestamp;
    }

    public int getBurstCount()
    {
        return mBurstCount;
    }

    public long getBurstGeneration()
    {
        return mBurstGeneration;
    }

    public boolean isBurstActive()
    {
        return mBurstActive;
    }

    public boolean isComplete()
    {
        return mComplete;
    }

    public CallEncryptionState getEncryptionState()
    {
        return mEncryptionState;
    }

    /**
     * Applies an authoritative encryption observation.  Opposing known observations make the state permanently
     * unknown for this physical leg so that downstream matching fails open instead of trusting damaged signaling.
     *
     * @return true when the published state changed
     */
    public boolean observeEncryptionState(CallEncryptionState encryptionState)
    {
        if(encryptionState == null || !encryptionState.isKnown() || mEncryptionStateConflicted)
        {
            return false;
        }

        if(!mEncryptionState.isKnown())
        {
            mEncryptionState = encryptionState;
            return true;
        }

        if(mEncryptionState == encryptionState)
        {
            return false;
        }

        mEncryptionState = CallEncryptionState.UNKNOWN;
        mEncryptionStateConflicted = true;
        return true;
    }

    public CallEncryptionEvidence getCallEncryptionEvidence()
    {
        return mCallEncryptionEvidence;
    }

    /**
     * Freezes the first usable message indicator for this physical call leg.  A later update can fill a previously
     * missing indicator, but it cannot replace an already captured one as encryption synchronization advances.
     *
     * @return true when the retained evidence or encrypted state changed
     */
    public boolean setCallEncryptionEvidence(CallEncryptionEvidence evidence)
    {
        CallEncryptionEvidence previousEvidence = mCallEncryptionEvidence;
        CallEncryptionState previousState = mEncryptionState;

        if(evidence != null && (mCallEncryptionEvidence == null ||
            !mCallEncryptionEvidence.hasMessageIndicator() && evidence.hasMessageIndicator()))
        {
            mCallEncryptionEvidence = evidence;
        }

        if(evidence != null)
        {
            observeEncryptionState(CallEncryptionState.ENCRYPTED);
        }

        return previousEvidence != mCallEncryptionEvidence || previousState != mEncryptionState;
    }

    public boolean isRecordAudio()
    {
        return mRecordAudio;
    }

    public void setRecordAudio(boolean recordAudio)
    {
        mRecordAudioOverride = recordAudio;
        recomputeAliasActions();
    }

    public Set<BroadcastChannel> getBroadcastChannels()
    {
        return Collections.unmodifiableSet(mBroadcastChannels);
    }

    public IdentifierCollection getIdentifierCollection()
    {
        return mIdentifierCollection;
    }

    /**
     * Immutable recording metadata. Destination and source Alias decisions follow identifier attribution updates,
     * then remain frozen unless another identifier update arrives; an administrator edit alone cannot rewrite the
     * historical call.
     */
    public AudioCallRecordingMetadata getRecordingMetadata()
    {
        if(mRecordingMetadata == null)
        {
            mRecordingMetadata =
                AudioCallRecordingMetadata.capture(mIdentifierCollection, mRecordingDestination, mRecordingSource);
        }

        return mRecordingMetadata;
    }

    public int getAudioBufferCount()
    {
        return mAudioBufferCount;
    }

    public boolean hasAudio()
    {
        return mAudioBufferCount > 0;
    }

    public VoiceCallQuality getVoiceCallQuality()
    {
        VoiceCallQuality quality = new VoiceCallQuality(mDecodedVoiceFrameCount, mRepeatedVoiceFrameCount,
            mConcealedVoiceFrameCount, 0, mFecErrorCount, mFecProtectedBitCount);
        return quality.withExpectedFrameCount(VoiceCallQuality.expectedFrameCount(mStartTimestamp,
            mLastActivityTimestamp));
    }

    public void touch()
    {
        touch(System.currentTimeMillis());
    }

    /**
     * Marks carrier activity at the supplied decoder-message timestamp.
     */
    public void touch(long timestamp)
    {
        updateLastActivity(resolveTimestamp(timestamp));
    }

    public void begin()
    {
        begin(System.currentTimeMillis());
    }

    /**
     * Begins this call at the supplied decoder-message timestamp.
     */
    public void begin(long timestamp)
    {
        long observedTimestamp = resolveTimestamp(timestamp);

        if(!mStartTimestampPinned)
        {
            mStartTimestamp = observedTimestamp;
            mStartTimestampPinned = true;
        }

        updateLastActivity(observedTimestamp);
    }

    public void beginBurst()
    {
        beginBurst(System.currentTimeMillis());
    }

    /**
     * Begins a talk burst at the supplied decoder-message timestamp.
     */
    public void beginBurst(long timestamp)
    {
        long observedTimestamp = resolveTimestamp(timestamp);

        if(!mBurstActive)
        {
            mBurstActive = true;
            mBurstCount++;
            mBurstGeneration++;
            mLastBurstStartTimestamp = observedTimestamp;
        }

        updateLastActivity(observedTimestamp);
    }

    public void endBurst()
    {
        endBurst(System.currentTimeMillis());
    }

    /**
     * Ends a talk burst at the supplied decoder-message timestamp.
     */
    public void endBurst(long timestamp)
    {
        if(mBurstActive)
        {
            long observedTimestamp = resolveTimestamp(timestamp);
            mBurstActive = false;
            mLastBurstEndTimestamp = observedTimestamp;
            updateLastActivity(observedTimestamp);
        }
    }

    public void complete()
    {
        complete(System.currentTimeMillis());
    }

    /**
     * Completes this call at the supplied decoder-message timestamp.
     */
    public void complete(long timestamp)
    {
        if(!mComplete)
        {
            endBurst(timestamp);
            mComplete = true;
        }
    }

    public void addIdentifiers(Collection<? extends Identifier<?>> identifiers)
    {
        for(Identifier<?> identifier : identifiers)
        {
            addIdentifier(identifier);
        }
    }

    public void addAudio(float[] audioBuffer)
    {
        addAudio(audioBuffer, System.currentTimeMillis());
    }

    /**
     * Adds decoded audio associated with the supplied carrier timestamp.
     */
    public void addAudio(float[] audioBuffer, long timestamp)
    {
        if(audioBuffer == null)
        {
            throw new IllegalArgumentException("Can't add null audio buffer");
        }

        long observedTimestamp = resolveTimestamp(timestamp);

        if(mAudioBufferCount == 0 && !mStartTimestampPinned)
        {
            mStartTimestamp = Math.max(0, observedTimestamp - 20);
            mStartTimestampPinned = true;
        }

        mAudioBufferCount++;
        mSampleCount += audioBuffer.length;

        //Committed audio is a known clear call unless authoritative signaling has already marked it encrypted.
        //Decrypted calls are marked encrypted before their first decoded frame reaches this builder.
        if(audioBuffer.length > 0 && !mEncryptionState.isKnown() && !mEncryptionStateConflicted)
        {
            mEncryptionState = CallEncryptionState.CLEAR;
        }

        updateLastActivity(observedTimestamp);
    }

    private void updateLastActivity(long timestamp)
    {
        if(!mLastActivityTimestampPinned || timestamp > mLastActivityTimestamp)
        {
            mLastActivityTimestamp = timestamp;
        }

        mLastActivityTimestampPinned = true;
    }

    private static long resolveTimestamp(long timestamp)
    {
        return timestamp > 0 ? timestamp : System.currentTimeMillis();
    }

    public void addVoiceFrameQuality(VoiceFrameQualityObservation observation)
    {
        if(observation == null)
        {
            return;
        }

        switch(observation.outcome())
        {
            case DECODED -> mDecodedVoiceFrameCount++;
            case REPEATED -> mRepeatedVoiceFrameCount++;
            case CONCEALED -> mConcealedVoiceFrameCount++;
        }

        mFecErrorCount += observation.fecErrorCount();
        mFecProtectedBitCount += observation.fecProtectedBitCount();
    }

    @Override
    public void receive(IdentifierUpdateNotification notification)
    {
        if(notification.getTimeslot() == getTimeslot() &&
            (notification.isAdd() || notification.isSilentAdd()))
        {
            addIdentifier(notification.getIdentifier());
        }
    }

    public void addIdentifier(Identifier<?> identifier)
    {
        if(AudioCallRecordingMetadata.isDestination(identifier))
        {
            //A destination can be promoted between a talkgroup and a patch group, and patch updates can keep the same
            //primary group while adding members. Retain only the newest destination object so withdrawn fallback
            //actions cannot remain latched onto the call.
            for(Identifier<?> previous: List.copyOf(mIdentifierCollection.getIdentifiers()))
            {
                if(previous != identifier && AudioCallRecordingMetadata.isDestination(previous))
                {
                    mIdentifierCollection.silentRemove(previous);
                }
            }
        }
        mIdentifierCollection.update(identifier);

        if(AudioCallRecordingMetadata.isDestination(identifier))
        {
            mRecordingDestination = AudioCallRecordingMetadata.captureDestination(mAliasList, identifier);
        }

        if(AudioCallRecordingMetadata.isSource(identifier))
        {
            mRecordingSource = AudioCallRecordingMetadata.captureSource(mAliasList, identifier);
        }

        mRecordingMetadata = null;

        recomputeAliasActions();
    }

    /**
     * Rebuilds action contributions from the current identifiers so late destination/patch attribution can replace
     * unmatched behavior instead of permanently latching it onto the call. The explicit channel recording override
     * remains independent and cannot be withdrawn by an alias update.
     */
    private void recomputeAliasActions()
    {
        boolean recordAudio = mRecordAudioOverride;
        Set<BroadcastChannel> broadcastChannels = new HashSet<>();

        for(Identifier<?> identifier: mIdentifierCollection.getIdentifiers())
        {
            List<Alias> aliases = mAliasList.getAliases(identifier);

            for(Alias alias : aliases)
            {
                if(alias.isRecordable())
                {
                    recordAudio = true;
                }

                broadcastChannels.addAll(alias.getBroadcastChannels());

            }

            UnmatchedTalkgroupPolicy unmatchedPolicy = mAliasList.getUnmatchedTalkgroupPolicy(identifier);

            if(unmatchedPolicy != null)
            {
                if(unmatchedPolicy.isRecordEnabled())
                {
                    recordAudio = true;
                }

                for(String destination: unmatchedPolicy.getStreamDestinationNames())
                {
                    broadcastChannels.add(new BroadcastChannel(destination));
                }

            }
        }

        mRecordAudio = recordAudio;
        mBroadcastChannels.clear();
        mBroadcastChannels.addAll(broadcastChannels);
    }
}
