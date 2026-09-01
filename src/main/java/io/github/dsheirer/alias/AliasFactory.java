/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.alias;

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.esn.Esn;
import io.github.dsheirer.alias.id.radio.P25FullyQualifiedRadio;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
import io.github.dsheirer.alias.id.talkgroup.P25FullyQualifiedTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;

/**
 * Deep-copy helpers for current alias matchers and behavior.
 */
public final class AliasFactory
{
    private AliasFactory()
    {
    }

    public static AliasID copyOf(AliasID id)
    {
        if(id == null)
        {
            return null;
        }

        return switch(id.getType())
        {
            case DCS -> {
                Dcs copy = new Dcs();
                copy.setDCSCode(((Dcs)id).getDCSCode());
                yield copy;
            }
            case ESN -> {
                Esn copy = new Esn();
                copy.setEsn(((Esn)id).getEsn());
                yield copy;
            }
            case P25_FULLY_QUALIFIED_RADIO_ID -> {
                P25FullyQualifiedRadio original = (P25FullyQualifiedRadio)id;
                P25FullyQualifiedRadio copy =
                    new P25FullyQualifiedRadio(original.getWacn(), original.getSystem(), original.getValue());
                copy.setOverlap(original.overlapProperty().get());
                yield copy;
            }
            case P25_FULLY_QUALIFIED_TALKGROUP -> {
                P25FullyQualifiedTalkgroup original = (P25FullyQualifiedTalkgroup)id;
                P25FullyQualifiedTalkgroup copy =
                    new P25FullyQualifiedTalkgroup(original.getWacn(), original.getSystem(), original.getValue());
                copy.setOverlap(original.overlapProperty().get());
                yield copy;
            }
            case RADIO_ID -> {
                Radio original = (Radio)id;
                Radio copy = new Radio(original.getProtocol(), original.getValue());
                copy.setOverlap(original.overlapProperty().get());
                yield copy;
            }
            case RADIO_ID_RANGE -> {
                RadioRange original = (RadioRange)id;
                RadioRange copy =
                    new RadioRange(original.getProtocol(), original.getMinRadio(), original.getMaxRadio());
                copy.setOverlap(original.overlapProperty().get());
                yield copy;
            }
            case STATUS -> {
                UserStatusID original = (UserStatusID)id;
                UserStatusID copy = new UserStatusID();
                copy.setStatus(original.getStatus());
                copy.setOverlap(original.overlapProperty().get());
                yield copy;
            }
            case TALKGROUP -> {
                Talkgroup original = (Talkgroup)id;
                Talkgroup copy = new Talkgroup(original.getProtocol(), original.getValue());
                copy.setOverlap(original.overlapProperty().get());
                yield copy;
            }
            case TALKGROUP_RANGE -> {
                TalkgroupRange original = (TalkgroupRange)id;
                TalkgroupRange copy = new TalkgroupRange(original.getProtocol(), original.getMinTalkgroup(),
                    original.getMaxTalkgroup());
                copy.setOverlap(original.overlapProperty().get());
                yield copy;
            }
            case TONES -> {
                TonesID original = (TonesID)id;
                TonesID copy = new TonesID();
                copy.setToneSequence(original.getToneSequence() != null ?
                    original.getToneSequence().copyOf() : null);
                yield copy;
            }
            case UNIT_STATUS -> {
                UnitStatusID original = (UnitStatusID)id;
                UnitStatusID copy = new UnitStatusID();
                copy.setStatus(original.getStatus());
                copy.setOverlap(original.overlapProperty().get());
                yield copy;
            }
            default -> null;
        };
    }

    private static Alias shallowCopyOf(Alias original)
    {
        Alias copy = new Alias(original.getName());
        copy.setAliasListId(original.getAliasListId());
        copy.setAliasListName(original.getAliasListName());
        copy.setDescription(original.getDescription());
        copy.setGroup(original.getGroup());
        copy.setColor(original.getColor());
        copy.setIconName(original.getIconName());
        copy.setRecordable(original.isRecordable());
        copy.setListen(original.isListen());

        if(original.getStreamTalkgroupAlias() != null)
        {
            copy.setStreamTalkgroupAlias(new StreamAsTalkgroup(original.getStreamTalkgroupAlias().getValue()));
        }

        for(BroadcastChannel broadcastChannel: original.getBroadcastChannels())
        {
            copy.addBroadcastChannel(new BroadcastChannel(broadcastChannel.getChannelName()));
        }

        return copy;
    }

    public static Alias copyOf(Alias original)
    {
        Alias copy = shallowCopyOf(original);
        copy.setMatchIdentifier(copyOf(original.getMatchIdentifier()));
        return copy;
    }
}
