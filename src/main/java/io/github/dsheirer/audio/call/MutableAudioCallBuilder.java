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
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.IdentifierUpdateNotification;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.encryption.EncryptionKeyIdentifier;
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
    private long mStartTimestamp = System.currentTimeMillis();
    private long mLastActivityTimestamp = mStartTimestamp;
    private long mLastBurstStartTimestamp;
    private long mLastBurstEndTimestamp;
    private long mSampleCount;
    private long mBurstGeneration;
    private boolean mStartTimestampPinned;
    private boolean mBurstActive;
    private boolean mComplete;
    private boolean mDuplicate;
    private boolean mEncrypted;
    private boolean mRecordAudio;
    private int mMonitorPriority = Priority.DEFAULT_PRIORITY;
    private int mBurstCount;
    private int mAudioBufferCount;

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

    public boolean isEncrypted()
    {
        return mEncrypted;
    }

    public boolean isDuplicate()
    {
        return mDuplicate;
    }

    public void setDuplicate(boolean duplicate)
    {
        mDuplicate = duplicate;
    }

    public boolean isRecordAudio()
    {
        return mRecordAudio;
    }

    public void setRecordAudio(boolean recordAudio)
    {
        mRecordAudio = recordAudio;
    }

    public int getMonitorPriority()
    {
        return mMonitorPriority;
    }

    public Set<BroadcastChannel> getBroadcastChannels()
    {
        return Collections.unmodifiableSet(mBroadcastChannels);
    }

    public IdentifierCollection getIdentifierCollection()
    {
        return mIdentifierCollection;
    }

    public int getAudioBufferCount()
    {
        return mAudioBufferCount;
    }

    public boolean hasAudio()
    {
        return mAudioBufferCount > 0;
    }

    public void touch()
    {
        mLastActivityTimestamp = System.currentTimeMillis();
    }

    public void begin()
    {
        long now = System.currentTimeMillis();

        if(!mStartTimestampPinned)
        {
            mStartTimestamp = now;
            mStartTimestampPinned = true;
        }

        mLastActivityTimestamp = now;
    }

    public void beginBurst()
    {
        long now = System.currentTimeMillis();

        if(!mBurstActive)
        {
            mBurstActive = true;
            mBurstCount++;
            mBurstGeneration++;
            mLastBurstStartTimestamp = now;
        }

        mLastActivityTimestamp = now;
    }

    public void endBurst()
    {
        if(mBurstActive)
        {
            long now = System.currentTimeMillis();
            mBurstActive = false;
            mLastBurstEndTimestamp = now;
            mLastActivityTimestamp = now;
        }
    }

    public void complete()
    {
        if(!mComplete)
        {
            endBurst();
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
        if(audioBuffer == null)
        {
            throw new IllegalArgumentException("Can't add null audio buffer");
        }

        if(mAudioBufferCount == 0 && !mStartTimestampPinned)
        {
            mStartTimestamp = System.currentTimeMillis() - 20;
        }

        mAudioBufferCount++;
        mSampleCount += audioBuffer.length;
        mLastActivityTimestamp = System.currentTimeMillis();
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

    private void addIdentifier(Identifier<?> identifier)
    {
        mIdentifierCollection.update(identifier);

        if(identifier instanceof EncryptionKeyIdentifier encryptionKeyIdentifier)
        {
            mEncrypted = encryptionKeyIdentifier.isEncrypted();
        }

        List<Alias> aliases = mAliasList.getAliases(identifier);

        for(Alias alias : aliases)
        {
            if(alias.isRecordable())
            {
                mRecordAudio = true;
            }

            mBroadcastChannels.addAll(alias.getBroadcastChannels());

            int playbackPriority = alias.getPlaybackPriority();

            if(playbackPriority < mMonitorPriority)
            {
                mMonitorPriority = playbackPriority;
            }
        }
    }
}
