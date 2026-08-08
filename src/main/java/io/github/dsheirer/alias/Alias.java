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
import io.github.dsheirer.alias.id.priority.Priority;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import java.awt.Color;
import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import javafx.beans.Observable;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.util.Callback;

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
    private final BooleanProperty mRecordable = new SimpleBooleanProperty();
    private final BooleanProperty mStreamable = new SimpleBooleanProperty();
    private final BooleanProperty mOverlap = new SimpleBooleanProperty();
    private final IntegerProperty mColor = new SimpleIntegerProperty();
    private final IntegerProperty mPriority = new SimpleIntegerProperty(Priority.DEFAULT_PRIORITY);
    private final StringProperty mAliasListName = new SimpleStringProperty();
    private final StringProperty mDescription = new SimpleStringProperty();
    private final StringProperty mGroup = new SimpleStringProperty();
    private final StringProperty mIconName = new SimpleStringProperty();
    private final StringProperty mName = new SimpleStringProperty();
    private final ObjectProperty<AliasID> mMatchIdentifier = new SimpleObjectProperty<>();
    private final ObservableList<BroadcastChannel> mBroadcastChannels =
        FXCollections.observableArrayList(channel ->
            new Observable[] {channel.valueProperty(), channel.overlapProperty()});
    private final ObservableList<BroadcastChannel> mReadOnlyBroadcastChannels =
        FXCollections.unmodifiableObservableList(mBroadcastChannels);
    private final ObjectProperty<StreamAsTalkgroup> mStreamTalkgroupAlias = new SimpleObjectProperty<>();

    public Alias(String name)
    {
        mName.set(name);
        mMatchIdentifier.addListener((_, _, _) -> updateOverlapBinding());
        mBroadcastChannels.addListener((javafx.collections.ListChangeListener<BroadcastChannel>)_ ->
            mStreamable.set(!mBroadcastChannels.isEmpty()));
        updateOverlapBinding();
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

        mId = id;
    }

    /**
     * Stable alias-list foreign key. The name is retained as a display snapshot for existing UI bindings.
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

        mAliasListId = aliasListId;
    }

    public void setAliasListDefinition(AliasListDefinition definition)
    {
        if(definition == null)
        {
            setAliasListId(UNASSIGNED_ALIAS_LIST_ID);
            setAliasListName(null);
        }
        else
        {
            setAliasListId(definition.getId());
            setAliasListName(definition.getName());
        }
    }

    @JsonIgnore
    public ObjectProperty<AliasID> matchIdentifierProperty()
    {
        return mMatchIdentifier;
    }

    @JsonIgnore
    public AliasID getMatchIdentifier()
    {
        return mMatchIdentifier.get();
    }

    public void setMatchIdentifier(AliasID identifier)
    {
        if(identifier != null)
        {
            identifier.updateValueProperty();
        }

        mMatchIdentifier.set(identifier);
    }

    @JsonIgnore
    public IntegerProperty priorityProperty()
    {
        return mPriority;
    }

    @JsonIgnore
    public BooleanProperty overlapProperty()
    {
        return mOverlap;
    }

    @JsonIgnore
    public BooleanProperty recordableProperty()
    {
        return mRecordable;
    }

    @JsonIgnore
    public BooleanProperty streamableProperty()
    {
        return mStreamable;
    }

    @JsonIgnore
    public StringProperty aliasListNameProperty()
    {
        return mAliasListName;
    }

    @JsonIgnore
    public StringProperty descriptionProperty()
    {
        return mDescription;
    }

    @JsonIgnore
    public StringProperty groupProperty()
    {
        return mGroup;
    }

    @JsonIgnore
    public StringProperty nameProperty()
    {
        return mName;
    }

    @JsonIgnore
    public IntegerProperty colorProperty()
    {
        return mColor;
    }

    @JsonIgnore
    public ObjectProperty<StreamAsTalkgroup> streamTalkgroupAliasProperty()
    {
        return mStreamTalkgroupAlias;
    }

    @JsonIgnore
    public StringProperty iconNameProperty()
    {
        return mIconName;
    }

    @JsonIgnore
    public ObservableList<BroadcastChannel> broadcastChannels()
    {
        return mReadOnlyBroadcastChannels;
    }

    @Override
    public String toString()
    {
        return getName();
    }

    public String getName()
    {
        return mName.get();
    }

    public void setName(String name)
    {
        mName.set(name);
    }

    public StreamAsTalkgroup getStreamTalkgroupAlias()
    {
        return mStreamTalkgroupAlias.get();
    }

    public void setStreamTalkgroupAlias(StreamAsTalkgroup streamTalkgroupAlias)
    {
        mStreamTalkgroupAlias.set(streamTalkgroupAlias);
    }

    public String getAliasListName()
    {
        return mAliasListName.get();
    }

    public void setAliasListName(String aliasListName)
    {
        mAliasListName.set(aliasListName);
    }

    public String getDescription()
    {
        return mDescription.get();
    }

    public void setDescription(String description)
    {
        mDescription.set(description);
    }

    /**
     * Tests membership using the durable SQLite identity. Name matching is only for not-yet-persisted import and
     * editor objects that do not have database identities yet.
     */
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

        return getAliasListName() != null && definition.getName() != null &&
            getAliasListName().equalsIgnoreCase(definition.getName());
    }

    public String getGroup()
    {
        return mGroup.get();
    }

    public void setGroup(String group)
    {
        mGroup.set(group);
    }

    public boolean hasGroup()
    {
        return mGroup.get() != null;
    }

    public int getColor()
    {
        return mColor.get();
    }

    public void setColor(int color)
    {
        mColor.set(color);
    }

    @JsonIgnore
    public Color getDisplayColor()
    {
        return new Color(getColor());
    }

    public String getIconName()
    {
        return mIconName.get();
    }

    public void setIconName(String iconName)
    {
        mIconName.set(iconName);
    }

    @JsonIgnore
    public int getPlaybackPriority()
    {
        return mPriority.get();
    }

    public boolean hasCallPriority()
    {
        return getPlaybackPriority() != Priority.DEFAULT_PRIORITY;
    }

    public void setCallPriority(int priority)
    {
        if(priority == Priority.DO_NOT_MONITOR ||
            (Priority.MIN_PRIORITY <= priority && priority < Priority.MAX_PRIORITY))
        {
            mPriority.set(priority);
        }
        else
        {
            mPriority.set(Priority.DEFAULT_PRIORITY);
        }
    }

    @JsonIgnore
    public boolean isRecordable()
    {
        return mRecordable.get();
    }

    public void setRecordable(boolean recordable)
    {
        mRecordable.set(recordable);
    }

    @JsonIgnore
    public boolean isStreamable()
    {
        return mStreamable.get();
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

    public void addBroadcastChannel(String channel)
    {
        if(channel != null && !channel.isEmpty())
        {
            addBroadcastChannel(new BroadcastChannel(channel));
        }
    }

    public void addBroadcastChannel(BroadcastChannel broadcastChannel)
    {
        if(broadcastChannel != null && broadcastChannel.isValid() && !mBroadcastChannels.contains(broadcastChannel))
        {
            mBroadcastChannels.add(broadcastChannel);
        }
    }

    public boolean hasBroadcastChannel(String channel)
    {
        return channel != null && !channel.isEmpty() &&
            mBroadcastChannels.stream().anyMatch(broadcastChannel ->
                channel.equals(broadcastChannel.getChannelName()));
    }

    public void removeBroadcastChannel(String channel)
    {
        if(channel != null && !channel.isEmpty())
        {
            mBroadcastChannels.removeIf(broadcastChannel -> channel.equals(broadcastChannel.getChannelName()));
        }
    }

    private void updateOverlapBinding()
    {
        mOverlap.unbind();
        mOverlap.set(false);

        if(getMatchIdentifier() != null)
        {
            mOverlap.bind(getMatchIdentifier().overlapProperty());
        }
    }

    public static Callback<Alias,Observable[]> extractor()
    {
        return alias -> new Observable[] {alias.recordableProperty(), alias.streamableProperty(),
            alias.colorProperty(), alias.aliasListNameProperty(), alias.descriptionProperty(), alias.groupProperty(),
            alias.iconNameProperty(), alias.nameProperty(), alias.overlapProperty(), alias.priorityProperty(),
            alias.streamTalkgroupAliasProperty(), alias.matchIdentifierProperty(), alias.broadcastChannels()};
    }
}
