/*
 * Copyright (C) 2026 Dennis Sheirer
 */
package io.github.dsheirer.alias;

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.controller.channel.ChannelConfigurationKey;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;

/** The configured number and winning aliases observed when one analog squelch call starts. */
public record AnalogCallAttribution(String channelConfigurationId, long aliasListId, Protocol protocol,
                                    int assignedTalkgroup, long startedAt, List<Match> matches)
{
    public AnalogCallAttribution
    {
        channelConfigurationId = ChannelConfigurationKey.canonical(channelConfigurationId);
        matches = matches != null ? List.copyOf(matches) : List.of();
    }

    public boolean hasSavedScope()
    {
        return channelConfigurationId != null && !channelConfigurationId.isBlank() && aliasListId > 0 &&
            (protocol == Protocol.AM || protocol == Protocol.NBFM) && assignedTalkgroup > 0 &&
            assignedTalkgroup <= 65535 && startedAt > 0;
    }

    /** Uses the same protocol-aware exact-before-range lookup as audio policy. No configuration is retained. */
    static AnalogCallAttribution capture(String channelId, AliasList list, TalkgroupIdentifier destination,
                                         long startedAt)
    {
        List<Match> matches = new ArrayList<>(1);
        if(list != null && destination != null)
        {
            for(Alias alias: list.getAliases(destination))
            {
                if(alias.getId() <= Alias.UNASSIGNED_ID)
                {
                    continue;
                }
                AliasID identifier = alias.getMatchIdentifier();
                if(identifier instanceof Talkgroup exact && exact.getProtocol() == destination.getProtocol())
                {
                    matches.add(new Match(alias.getId(), "TALKGROUP", exact.getValue(), exact.getValue()));
                }
                else if(identifier instanceof TalkgroupRange range && range.getProtocol() == destination.getProtocol())
                {
                    matches.add(new Match(alias.getId(), "TALKGROUP_RANGE", range.getMinTalkgroup(),
                        range.getMaxTalkgroup()));
                }
            }
        }
        return new AnalogCallAttribution(channelId, list != null ? list.getId() : 0,
            destination != null ? destination.getProtocol() : Protocol.UNKNOWN,
            destination != null ? destination.getValue() : 0, startedAt, matches);
    }

    /** Matcher identity permits queued observations to be rejected after a matcher is changed or reset. */
    public record Match(long aliasId, String matcherType, int minimum, int maximum) {}
}
