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
package io.github.dsheirer.channel.metadata.activity;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.identifier.Identifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Session-only row for the Now Playing activity tables.
 */
public class ChannelActivityRow
{
    public enum Role
    {
        CONVENTIONAL,
        CONFIGURED_CONTROL,
        CURRENT_CONTROL,
        ALTERNATE_CONTROL,
        TRAFFIC
    }

    public enum Origin
    {
        CONVENTIONAL_METADATA,
        CONFIGURED_CONTROL,
        DECODED_CURRENT_CONTROL,
        DECODED_ALTERNATE_CONTROL,
        DECODED_DATA_ANNOUNCEMENT,
        TRAFFIC_GRANT
    }

    private final String mKey;
    private Channel mChannel;
    private Role mRole;
    private final EnumSet<ChannelTag> mTags = EnumSet.noneOf(ChannelTag.class);
    private Origin mOrigin;
    private State mState = State.IDLE;
    private String mLcn;
    private long mFrequency;
    private String mCallsign;
    private Integer mTimeslot;
    private Identifier<?> mSource;
    private List<Alias> mSourceAliases = Collections.emptyList();
    private Identifier<?> mTalkerAlias;
    private Identifier<?> mTarget;
    private List<Alias> mTargetAliases = Collections.emptyList();
    private String mDecoder;
    private String mEncryptionDetails;
    private Double mSignalDbfs;
    private Double mDecodeHealthPercent;
    private long mQualityObservedAt;
    private long mTrafficGrantExpiresAt;

    public ChannelActivityRow(String key, Channel channel, Role role, long frequency, Integer timeslot)
    {
        mKey = key;
        mChannel = channel;
        mFrequency = frequency;
        mTimeslot = timeslot;
        setRole(role);
    }

    public String getKey()
    {
        return mKey;
    }

    public Channel getChannel()
    {
        return mChannel;
    }

    public String getChannelName()
    {
        return mChannel != null ? mChannel.getName() : null;
    }

    public void setChannel(Channel channel)
    {
        mChannel = channel;
    }

    public Role getRole()
    {
        return mRole;
    }

    public void setRole(Role role)
    {
        mRole = role != null ? role : Role.CONVENTIONAL;
        addTag(switch(mRole)
        {
            case CONVENTIONAL -> ChannelTag.CONVENTIONAL;
            case CONFIGURED_CONTROL -> ChannelTag.CONFIGURED;
            case CURRENT_CONTROL -> ChannelTag.CURRENT_CONTROL;
            case ALTERNATE_CONTROL -> ChannelTag.ALTERNATE_CONTROL;
            case TRAFFIC -> null;
        });
    }

    public Set<ChannelTag> getTags()
    {
        return Collections.unmodifiableSet(mTags);
    }

    public void addTag(ChannelTag tag)
    {
        if(tag != null)
        {
            mTags.add(tag);
        }
    }

    public void addTags(Set<ChannelTag> tags)
    {
        if(tags != null)
        {
            mTags.addAll(tags);
        }
    }

    public void removeTag(ChannelTag tag)
    {
        if(tag != null)
        {
            mTags.remove(tag);
        }
    }

    public boolean hasTag(ChannelTag tag)
    {
        return tag != null && mTags.contains(tag);
    }

    public String getTagsDisplay()
    {
        return displayedTags()
            .map(ChannelTag::getLabel).collect(Collectors.joining(" + "));
    }

    public String getTagsDescription()
    {
        return displayedTags()
            .map(ChannelTag::getDescription).collect(Collectors.joining(" + "));
    }

    private java.util.stream.Stream<ChannelTag> displayedTags()
    {
        return mTags.stream().filter(tag -> tag != ChannelTag.CONFIGURED || mTags.size() == 1)
            .filter(tag -> tag != ChannelTag.DATA_ANNOUNCED || !mTags.contains(ChannelTag.DATA));
    }

    public boolean isControlRow()
    {
        return mRole == Role.CONFIGURED_CONTROL || mRole == Role.CURRENT_CONTROL || mRole == Role.ALTERNATE_CONTROL;
    }

    public Origin getOrigin()
    {
        return mOrigin;
    }

    public void setOrigin(Origin origin)
    {
        mOrigin = origin;
    }

    public State getState()
    {
        return mState;
    }

    public void setState(State state)
    {
        mState = state != null ? state : State.IDLE;
    }

    public String getLcn()
    {
        return mLcn;
    }

    public void setLcn(String lcn)
    {
        mLcn = lcn;
    }

    public long getFrequency()
    {
        return mFrequency;
    }

    public void setFrequency(long frequency)
    {
        mFrequency = frequency;
    }

    public String getCallsign()
    {
        return mCallsign;
    }

    public void setCallsign(String callsign)
    {
        mCallsign = callsign != null && !callsign.isBlank() ? callsign.trim() : null;
    }

    public Integer getTimeslot()
    {
        return mTimeslot;
    }

    public void setTimeslot(Integer timeslot)
    {
        mTimeslot = timeslot;
    }

    public Identifier<?> getSource()
    {
        return mSource;
    }

    public void setSource(Identifier<?> source)
    {
        mSource = source;
    }

    public List<Alias> getSourceAliases()
    {
        return mSourceAliases;
    }

    public void setSourceAliases(List<Alias> sourceAliases)
    {
        mSourceAliases = sourceAliases != null ? sourceAliases : Collections.emptyList();
    }

    public Identifier<?> getTalkerAlias()
    {
        return mTalkerAlias;
    }

    public void setTalkerAlias(Identifier<?> talkerAlias)
    {
        mTalkerAlias = talkerAlias;
    }

    /**
     * Source alias text for activity renderers.  Configured aliases remain primary and an over-the-air talker alias
     * is appended when it provides a distinct value.
     */
    public String getSourceAliasDisplay()
    {
        List<String> configuredAliases = new ArrayList<>();

        for(Alias alias: mSourceAliases)
        {
            String name = clean(alias != null ? alias.getName() : null);

            if(name != null)
            {
                configuredAliases.add(name);
            }
        }

        String configured = configuredAliases.isEmpty() ? null : String.join(", ", configuredAliases);
        String talker = clean(mTalkerAlias != null && mTalkerAlias.getValue() != null ?
            mTalkerAlias.getValue().toString() : null);

        if(talker == null)
        {
            return configured;
        }

        String normalizedTalker = normalize(talker);

        if(configuredAliases.stream().anyMatch(alias -> normalize(alias).equals(normalizedTalker)))
        {
            return configured;
        }

        return configured != null ? configured + " · TA: " + talker : "TA: " + talker;
    }

    private static String clean(String value)
    {
        if(value == null)
        {
            return null;
        }

        String cleaned = value.trim();
        return cleaned.isEmpty() ? null : cleaned;
    }

    private static String normalize(String value)
    {
        return value.trim().toLowerCase(Locale.ROOT);
    }

    public Identifier<?> getTarget()
    {
        return mTarget;
    }

    public void setTarget(Identifier<?> target)
    {
        mTarget = target;
    }

    public List<Alias> getTargetAliases()
    {
        return mTargetAliases;
    }

    public void setTargetAliases(List<Alias> targetAliases)
    {
        mTargetAliases = targetAliases != null ? targetAliases : Collections.emptyList();
    }

    public String getDecoder()
    {
        return mDecoder;
    }

    public void setDecoder(String decoder)
    {
        mDecoder = decoder;
    }

    public String getEncryptionDetails()
    {
        return mEncryptionDetails;
    }

    public void setEncryptionDetails(String encryptionDetails)
    {
        mEncryptionDetails = encryptionDetails;
    }

    public Double getSignalDbfs()
    {
        return mSignalDbfs;
    }

    public Double getDecodeHealthPercent()
    {
        return mDecodeHealthPercent;
    }

    public long getQualityObservedAt()
    {
        return mQualityObservedAt;
    }

    public void setQuality(Double signalDbfs, Double decodeHealthPercent, long observedAt)
    {
        mSignalDbfs = signalDbfs;
        mDecodeHealthPercent = decodeHealthPercent;
        mQualityObservedAt = observedAt;
    }

    public void clearQuality()
    {
        mSignalDbfs = null;
        mDecodeHealthPercent = null;
        mQualityObservedAt = 0;
    }

    public long getTrafficGrantExpiresAt()
    {
        return mTrafficGrantExpiresAt;
    }

    public void setTrafficGrantExpiresAt(long expiresAt)
    {
        mTrafficGrantExpiresAt = expiresAt;
    }

    public void clearTrafficGrantExpiresAt()
    {
        mTrafficGrantExpiresAt = 0;
    }

    public void clearCallDetails()
    {
        mSource = null;
        mSourceAliases = Collections.emptyList();
        mTalkerAlias = null;
        mTarget = null;
        mTargetAliases = Collections.emptyList();
        mEncryptionDetails = null;
    }
}
