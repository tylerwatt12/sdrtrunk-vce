/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.alias;

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.esn.Esn;
import io.github.dsheirer.alias.id.radio.P25Subscriber;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
import io.github.dsheirer.alias.id.talkgroup.StreamAsTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.identifier.tone.Tone;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.ToLongFunction;

/** Compares saved configuration values without depending on mutable property or display-object identity. */
public final class AliasConfigurationEquality
{
    private AliasConfigurationEquality()
    {
    }

    public static boolean sameConfiguration(AliasConfigurationSnapshot first, AliasConfigurationSnapshot second)
    {
        return sameEntities(first.definitions(), second.definitions(), AliasListDefinition::getId,
            AliasConfigurationEquality::sameDefinition) &&
            sameEntities(first.aliases(), second.aliases(), Alias::getId, AliasConfigurationEquality::sameAlias) &&
            sameScanConfiguration(first.scanLists(), second.scanLists());
    }

    private static <T> boolean sameEntities(List<T> first, List<T> second, ToLongFunction<T> identity,
                                           BiPredicate<T,T> equal)
    {
        if(first.size() != second.size()) return false;
        Map<Long,T> existing = new HashMap<>();
        for(T value: first)
        {
            long id = identity.applyAsLong(value);
            if(id <= 0 || existing.put(id, value) != null) return false;
        }
        for(T value: second)
        {
            T previous = existing.remove(identity.applyAsLong(value));
            if(previous == null || !equal.test(previous, value)) return false;
        }
        return existing.isEmpty();
    }

    public static boolean sameDefinition(AliasListDefinition first, AliasListDefinition second)
    {
        return first != null && second != null && first.getId() == second.getId() &&
            Objects.equals(first.getName(), second.getName()) && first.getFamily() == second.getFamily() &&
            first.getUnmatchedTalkgroupPolicy().isRecordEnabled() ==
                second.getUnmatchedTalkgroupPolicy().isRecordEnabled() &&
            sameRoutes(first.getUnmatchedTalkgroupPolicy().getStreamDestinations(),
                second.getUnmatchedTalkgroupPolicy().getStreamDestinations()) &&
            first.getNewAliasBehavior().isRecordEnabled() == second.getNewAliasBehavior().isRecordEnabled() &&
            sameRoutes(first.getNewAliasBehavior().getStreamDestinations(),
                second.getNewAliasBehavior().getStreamDestinations());
    }

    public static boolean sameScanConfiguration(ScanListConfiguration first, ScanListConfiguration second)
    {
        return first.scanLists().equals(second.scanLists()) &&
            first.aliasMemberships().equals(second.aliasMemberships()) &&
            first.unmatchedAliasListMemberships().equals(second.unmatchedAliasListMemberships()) &&
            first.newAliasListMemberships().equals(second.newAliasListMemberships());
    }

    public static boolean sameAlias(Alias first, Alias second)
    {
        return first != null && second != null && first.getId() == second.getId() &&
            first.getAliasListId() == second.getAliasListId() &&
            Objects.equals(first.getName(), second.getName()) &&
            Objects.equals(first.getDescription(), second.getDescription()) &&
            Objects.equals(first.getGroup(), second.getGroup()) && first.getColor() == second.getColor() &&
            Objects.equals(first.getIconName(), second.getIconName()) &&
            first.isRecordable() == second.isRecordable() &&
            Objects.equals(streamAs(first), streamAs(second)) &&
            sameRoutes(first.getBroadcastChannels(), second.getBroadcastChannels()) &&
            sameMatcher(first.getMatchIdentifier(), second.getMatchIdentifier());
    }

    private static Integer streamAs(Alias alias)
    {
        StreamAsTalkgroup streamAs = alias.getStreamTalkgroupAlias();
        return streamAs != null ? streamAs.getValue() : null;
    }

    private static boolean sameRoutes(Collection<BroadcastChannel> first, Collection<BroadcastChannel> second)
    {
        // A route's saved identity is its UUID; displayed provider names and selection order are not saved edges.
        Set<BroadcastChannel> routes = new HashSet<>(first);
        return routes.size() == first.size() && first.size() == second.size() &&
            routes.equals(new HashSet<>(second));
    }

    private static boolean sameMatcher(AliasID first, AliasID second)
    {
        if(first == null || second == null || first.getType() != second.getType()) return false;
        return switch(first.getType())
        {
            case P25_SUBSCRIBER_IDENTITY -> first instanceof P25Subscriber a && second instanceof P25Subscriber b &&
                a.getHomeWacn() == b.getHomeWacn() && a.getHomeSystemId() == b.getHomeSystemId() &&
                a.getSubscriberId() == b.getSubscriberId();
            case TALKGROUP -> first instanceof Talkgroup a && second instanceof Talkgroup b &&
                a.getProtocol() == b.getProtocol() && a.getValue() == b.getValue();
            case TALKGROUP_RANGE -> first instanceof TalkgroupRange a && second instanceof TalkgroupRange b &&
                a.getProtocol() == b.getProtocol() && a.getMinTalkgroup() == b.getMinTalkgroup() &&
                a.getMaxTalkgroup() == b.getMaxTalkgroup();
            case RADIO_ID -> first instanceof Radio a && second instanceof Radio b &&
                a.getProtocol() == b.getProtocol() && a.getValue() == b.getValue();
            case RADIO_ID_RANGE -> first instanceof RadioRange a && second instanceof RadioRange b &&
                a.getProtocol() == b.getProtocol() && a.getMinRadio() == b.getMinRadio() &&
                a.getMaxRadio() == b.getMaxRadio();
            case STATUS -> first instanceof UserStatusID a && second instanceof UserStatusID b &&
                a.getStatus() == b.getStatus();
            case UNIT_STATUS -> first instanceof UnitStatusID a && second instanceof UnitStatusID b &&
                a.getStatus() == b.getStatus();
            case DCS -> first instanceof Dcs a && second instanceof Dcs b && a.getDCSCode() == b.getDCSCode();
            case ESN -> first instanceof Esn a && second instanceof Esn b && Objects.equals(a.getEsn(), b.getEsn());
            case TONES -> first instanceof TonesID a && second instanceof TonesID b && sameTones(a, b);
            default -> false;
        };
    }

    private static boolean sameTones(TonesID first, TonesID second)
    {
        if(first.getToneSequence() == null || second.getToneSequence() == null) return false;
        List<Tone> a = first.getToneSequence().getTones();
        List<Tone> b = second.getToneSequence().getTones();
        if(a.size() != b.size()) return false;
        for(int index = 0; index < a.size(); index++)
        {
            if(a.get(index).getAmbeTone() != b.get(index).getAmbeTone() ||
                a.get(index).getDuration() != b.get(index).getDuration()) return false;
        }
        return true;
    }
}
