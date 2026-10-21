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
package io.github.dsheirer.alias;

import com.fasterxml.jackson.annotation.JsonIgnore;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.Set;
import java.util.TreeSet;

/**
 * User-facing metadata and behavior for exactly one alias match identifier.
 *
 * <p>Legacy identifier lists are converted before an alias enters the runtime model. Runtime aliases do not carry
 * disabled, review, or compatibility state.</p>
 */
public class Alias
{
    public static final long UNASSIGNED_ID = 0L;
    public static final long UNASSIGNED_ALIAS_LIST_ID = AliasListDefinition.UNASSIGNED_ID;

    private volatile long mId = UNASSIGNED_ID;
    private volatile long mAliasListId = UNASSIGNED_ALIAS_LIST_ID;
    private transient volatile AliasListDefinition mAliasListDefinition;
    private boolean mRecordable;
    private int mColor;
    private String mAliasListName;
    private String mDescription;
    private String mGroup;
    private String mIconName;
    private String mName;
    private AliasID mMatchIdentifier;
    private StreamAsTalkgroup mStreamTalkgroupAlias;
    private final List<BroadcastChannel> mBroadcastChannels = new ArrayList<>();
    enum LookupChange { IDENTIFIER, MEMBERSHIP }
    private final List<Consumer<LookupChange>> mLookupListeners = new CopyOnWriteArrayList<>();
    private final Runnable mIdentifierChanged = () -> notifyLookupChanged(LookupChange.IDENTIFIER);

    public Alias(String name)
    {
        mName = name;
    }

    public Alias()
    {
        this(null);
    }

    /**
     * Stable internal database identity. Zero identifies a new, not-yet-persisted alias.
     */
    @JsonIgnore
    public long getId()
    {
        return mId;
    }

    public void setId(long id)
    {
        if(id < UNASSIGNED_ID)
        {
            throw new IllegalArgumentException("Alias ID cannot be negative");
        }
        if(mId != UNASSIGNED_ID && mId != id)
        {
            throw new IllegalStateException("Persisted Alias ID cannot be changed");
        }

        mId = id;
    }

    /**
     * Stable alias-list foreign key. The name is retained as a display snapshot.
     */
    @JsonIgnore
    public long getAliasListId()
    {
        return mAliasListId;
    }

    public void setAliasListId(long aliasListId)
    {
        if(aliasListId < UNASSIGNED_ALIAS_LIST_ID)
        {
            throw new IllegalArgumentException("Alias list ID cannot be negative");
        }

        if(mAliasListDefinition != null && mAliasListDefinition.getId() != aliasListId)
        {
            mAliasListDefinition = null;
        }

        if(mAliasListId != aliasListId)
        {
            mAliasListId = aliasListId;
            notifyLookupChanged(LookupChange.MEMBERSHIP);
        }
    }

    /**
     * Exact in-memory Alias List relationship for a new draft, or the definition bound to the durable ID after load.
     * The reference is runtime-only; SQLite stores {@link #getAliasListId()} as the authoritative relationship.
     */
    @JsonIgnore
    public AliasListDefinition getAliasListDefinition()
    {
        return mAliasListDefinition;
    }

    public void setAliasListDefinition(AliasListDefinition definition)
    {
        AliasListDefinition previous = mAliasListDefinition;
        if(definition == null)
        {
            mAliasListDefinition = null;
            setAliasListId(UNASSIGNED_ALIAS_LIST_ID);
            setAliasListName(null);
        }
        else
        {
            setAliasListId(definition.getId());
            mAliasListDefinition = definition;
            setAliasListName(definition.getName());
        }
        if(previous != definition && mAliasListId == UNASSIGNED_ALIAS_LIST_ID)
            notifyLookupChanged(LookupChange.MEMBERSHIP);
    }

    @JsonIgnore
    public AliasID getMatchIdentifier()
    {
        return mMatchIdentifier;
    }

    @JsonIgnore
    public boolean isOverlap()
    {
        return mMatchIdentifier != null && mMatchIdentifier.isOverlap();
    }

    public void setMatchIdentifier(AliasID identifier)
    {
        if(mMatchIdentifier != null) mMatchIdentifier.removeChangeListener(mIdentifierChanged);
        mMatchIdentifier = identifier;
        if(identifier != null) identifier.addChangeListener(mIdentifierChanged);
        notifyLookupChanged(LookupChange.IDENTIFIER);
    }

    @JsonIgnore
    public List<BroadcastChannel> broadcastChannels()
    {
        return List.copyOf(mBroadcastChannels);
    }

    @Override
    public String toString()
    {
        return getName();
    }

    public String getName()
    {
        return mName;
    }

    public void setName(String name)
    {
        mName = name;
    }

    public StreamAsTalkgroup getStreamTalkgroupAlias()
    {
        return mStreamTalkgroupAlias;
    }

    public void setStreamTalkgroupAlias(StreamAsTalkgroup streamTalkgroupAlias)
    {
        mStreamTalkgroupAlias = streamTalkgroupAlias;
    }

    public String getAliasListName()
    {
        return mAliasListName;
    }

    public void setAliasListName(String aliasListName)
    {
        if(!Objects.equals(mAliasListName, aliasListName))
        {
            mAliasListName = aliasListName;
        }
    }

    public String getDescription()
    {
        return mDescription;
    }

    public void setDescription(String description)
    {
        mDescription = description;
    }

    /** Tests membership by durable SQLite identity, or exact definition object for an unpersisted draft. */
    boolean belongsTo(AliasListDefinition definition)
    {
        if(definition == null)
        {
            return false;
        }

        if(getAliasListId() > UNASSIGNED_ALIAS_LIST_ID ||
            definition.getId() > AliasListDefinition.UNASSIGNED_ID)
        {
            return getAliasListId() > UNASSIGNED_ALIAS_LIST_ID &&
                definition.getId() > AliasListDefinition.UNASSIGNED_ID &&
                getAliasListId() == definition.getId();
        }

        return mAliasListDefinition == definition;
    }

    public String getGroup()
    {
        return mGroup;
    }

    public void setGroup(String group)
    {
        mGroup = group;
    }

    public boolean hasGroup()
    {
        return mGroup != null;
    }

    public int getColor()
    {
        return mColor;
    }

    public void setColor(int color)
    {
        mColor = color;
    }

    public String getIconName()
    {
        return mIconName;
    }

    public void setIconName(String iconName)
    {
        mIconName = iconName;
    }

    @JsonIgnore
    public boolean isRecordable()
    {
        return mRecordable;
    }

    public void setRecordable(boolean recordable)
    {
        mRecordable = recordable;
    }

    @JsonIgnore
    public boolean isStreamable()
    {
        return !mBroadcastChannels.isEmpty();
    }

    @JsonIgnore
    public Set<BroadcastChannel> getBroadcastChannels()
    {
        return new TreeSet<>(mBroadcastChannels);
    }

    public void setBroadcastChannels(Collection<BroadcastChannel> broadcastChannels)
    {
        mBroadcastChannels.clear();

        if(broadcastChannels != null)
        {
            broadcastChannels.forEach(this::addBroadcastChannel);
        }
    }

    public void addBroadcastChannel(String configurationId, String channelName)
    {
        if(configurationId != null && !configurationId.isBlank())
        {
            addBroadcastChannel(new BroadcastChannel(configurationId, channelName));
        }
    }

    public void addBroadcastChannel(BroadcastChannel broadcastChannel)
    {
        if(broadcastChannel != null && (broadcastChannel.isValid() || broadcastChannel.hasDisplayName()) &&
            !mBroadcastChannels.contains(broadcastChannel))
        {
            mBroadcastChannels.add(broadcastChannel);
        }
    }

    public boolean hasBroadcastConfiguration(String configurationId)
    {
        return configurationId != null && !configurationId.isBlank() &&
            mBroadcastChannels.stream().anyMatch(broadcastChannel ->
                configurationId.equals(broadcastChannel.getConfigurationId()));
    }

    public void removeBroadcastConfiguration(String configurationId)
    {
        if(configurationId != null && !configurationId.isBlank())
        {
            mBroadcastChannels.removeIf(broadcastChannel ->
                configurationId.equals(broadcastChannel.getConfigurationId()));
        }
    }

    /** Runtime indexes observe matcher/list changes directly, not UI properties. */
    void addLookupChangeListener(Consumer<LookupChange> listener) { mLookupListeners.add(listener); }
    void removeLookupChangeListener(Consumer<LookupChange> listener) { mLookupListeners.remove(listener); }
    private void notifyLookupChanged(LookupChange change) { mLookupListeners.forEach(listener -> listener.accept(change)); }
}
