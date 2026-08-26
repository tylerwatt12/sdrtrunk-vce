/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
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

package io.github.dsheirer.audio.broadcast;

import io.github.dsheirer.audio.call.ResolvedCallPolicy;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Immutable evidence describing the individual channel observations that contributed to a completed call's
 * broadcast routing.  This broadcast-layer projection prevents downstream dispatch code from depending on the
 * duplicate-call coordinator's policy model.
 */
public record BroadcastDeliveryEvidence(List<Observation> observations)
{
    public static final BroadcastDeliveryEvidence EMPTY = new BroadcastDeliveryEvidence(List.of());

    public BroadcastDeliveryEvidence
    {
        observations = observations != null ? List.copyOf(new LinkedHashSet<>(observations)) : List.of();
    }

    /**
     * Projects only the fields needed to make a broadcast delivery decision from a resolved completed-call policy.
     *
     * @param resolvedCallPolicy completed-call policy, or null
     * @return immutable broadcast delivery evidence
     */
    public static BroadcastDeliveryEvidence from(ResolvedCallPolicy resolvedCallPolicy)
    {
        if(resolvedCallPolicy == null || resolvedCallPolicy.matchContexts().isEmpty())
        {
            return EMPTY;
        }

        Set<Observation> observations = new LinkedHashSet<>();

        for(ResolvedCallPolicy.MatchContext context : resolvedCallPolicy.matchContexts())
        {
            if(context != null)
            {
                Observation observation = new Observation(context.channelConfigurationId(), context.aliasListId(),
                    context.aliasListName(), context.broadcastRoutingKeys());

                if(observation.hasDeliveryEvidence())
                {
                    observations.add(observation);
                }
            }
        }

        return observations.isEmpty() ? EMPTY : new BroadcastDeliveryEvidence(List.copyOf(observations));
    }

    /**
     * Indicates if one individual channel observation simultaneously contains the requested provider route, alias
     * list, and saved channel identity.  Evidence from separate duplicate copies is deliberately never combined.
     */
    public boolean matches(String broadcastRoutingKey, long aliasListId, String channelConfigurationId)
    {
        String normalizedRoute = normalize(broadcastRoutingKey);
        String normalizedChannel = normalizeChannelConfigurationId(channelConfigurationId);

        if(normalizedRoute == null || aliasListId <= 0 || normalizedChannel == null)
        {
            return false;
        }

        for(Observation observation : observations)
        {
            if(observation.matches(normalizedRoute, aliasListId, normalizedChannel))
            {
                return true;
            }
        }

        return false;
    }

    /**
     * One receiver/channel copy's site-bound broadcast routing evidence.
     */
    public record Observation(String channelConfigurationId, long aliasListId, String aliasListName,
                              Set<String> broadcastRoutingKeys)
    {
        public Observation
        {
            channelConfigurationId = normalizeChannelConfigurationId(channelConfigurationId);
            aliasListName = normalize(aliasListName);
            broadcastRoutingKeys = immutableStrings(broadcastRoutingKeys);
        }

        private boolean hasDeliveryEvidence()
        {
            return channelConfigurationId != null || aliasListName != null || aliasListId > 0 ||
                !broadcastRoutingKeys.isEmpty();
        }

        private boolean matches(String broadcastRoutingKey, long requiredAliasListId,
                                String requiredChannelConfigurationId)
        {
            return requiredChannelConfigurationId.equals(channelConfigurationId) &&
                requiredAliasListId == aliasListId && broadcastRoutingKeys.contains(broadcastRoutingKey);
        }
    }

    private static Set<String> immutableStrings(Collection<String> values)
    {
        Set<String> normalized = new LinkedHashSet<>();

        if(values != null)
        {
            for(String value : values)
            {
                String item = normalize(value);

                if(item != null)
                {
                    normalized.add(item);
                }
            }
        }

        return Set.copyOf(normalized);
    }

    private static String normalize(String value)
    {
        if(value == null)
        {
            return null;
        }

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    private static String normalizeChannelConfigurationId(String value)
    {
        String normalized = normalize(value);

        if(normalized != null)
        {
            try
            {
                return UUID.fromString(normalized).toString();
            }
            catch(IllegalArgumentException _)
            {
                //Invalid saved channel identity is not delivery evidence.
            }
        }

        return null;
    }
}
