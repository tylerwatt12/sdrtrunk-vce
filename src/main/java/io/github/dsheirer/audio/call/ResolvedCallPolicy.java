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

package io.github.dsheirer.audio.call;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNFullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.protocol.Protocol;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Immutable aggregate output policy and matching context for one resolved logical call.
 *
 * <p>The elected snapshot continues to own audio, RF/site metadata, and the mutable runtime alias-list reference used
 * by existing streaming code. This compact value separately preserves every cohort member's channel/talkgroup match
 * context and frozen output decisions, so a losing receiver copy cannot silently remove a recording destination,
 * configured stream, or scan-list match.</p>
 */
public record ResolvedCallPolicy(boolean recordAudio, boolean destinationTalkgroupRecordEnabled,
                                 Set<String> broadcastRoutingKeys, List<MatchContext> matchContexts)
{
    public ResolvedCallPolicy
    {
        broadcastRoutingKeys = immutableStrings(broadcastRoutingKeys);
        matchContexts = matchContexts != null ? List.copyOf(new LinkedHashSet<>(matchContexts)) : List.of();
    }

    /**
     * Captures and unions the frozen policy for the supplied duplicate-cohort snapshots.
     */
    public static ResolvedCallPolicy capture(Collection<AudioCallSnapshot> snapshots)
    {
        boolean recordAudio = false;
        boolean destinationRecordEnabled = false;
        Set<String> destinations = new LinkedHashSet<>();
        Set<MatchContext> contexts = new LinkedHashSet<>();

        if(snapshots != null)
        {
            for(AudioCallSnapshot snapshot : snapshots)
            {
                if(snapshot == null)
                {
                    continue;
                }

                AudioCallRecordingMetadata metadata = snapshot.recordingMetadata();
                boolean destinationRecord =
                    metadata != null && metadata.destinationTalkgroupRecordEnabled();
                Set<String> memberDestinations = broadcastRoutingKeys(snapshot.broadcastChannels());
                MatchContext context = MatchContext.capture(snapshot, snapshot.recordAudio(), destinationRecord,
                    memberDestinations);
                recordAudio |= snapshot.recordAudio() || destinationRecord;
                destinationRecordEnabled |= destinationRecord;
                destinations.addAll(memberDestinations);

                if(context.hasMatchingIdentity() || context.hasOutputPolicy())
                {
                    contexts.add(context);
                }
            }
        }

        return new ResolvedCallPolicy(recordAudio, destinationRecordEnabled, destinations,
            List.copyOf(contexts));
    }

    public static ResolvedCallPolicy capture(AudioCallSnapshot snapshot)
    {
        return capture(snapshot != null ? List.of(snapshot) : List.of());
    }

    /**
     * Captures the current {@link BroadcastChannel#getChannelName()} values used by the runtime as routing keys.
     * These names are not stable provider/configuration UUIDs and must not be treated as such by persistence code.
     */
    private static Set<String> broadcastRoutingKeys(Collection<BroadcastChannel> broadcastChannels)
    {
        Set<String> destinations = new LinkedHashSet<>();

        if(broadcastChannels != null)
        {
            for(BroadcastChannel broadcastChannel : broadcastChannels)
            {
                String destination = normalize(broadcastChannel != null ? broadcastChannel.getChannelName() : null);

                if(destination != null)
                {
                    destinations.add(destination);
                }
            }
        }

        return Set.copyOf(destinations);
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

    /**
     * One cohort member's immutable scan-list and output-policy evidence. Site is deliberately absent: talkgroup
     * membership is site-independent, while exact channel membership uses the stable channel configuration identity.
     */
    public record MatchContext(String channelConfigurationId, long aliasListId, String aliasListName,
                               String systemName, List<DestinationIdentity> destinationIdentities,
                               Set<Long> matchedAliasIds, AliasList.TalkgroupMatchStatus talkgroupMatchStatus,
                               boolean recordAudio,
                               boolean destinationTalkgroupRecordEnabled, Set<String> broadcastRoutingKeys)
    {
        public MatchContext
        {
            channelConfigurationId = normalize(channelConfigurationId);
            aliasListName = normalize(aliasListName);
            systemName = normalize(systemName);
            destinationIdentities = destinationIdentities != null ?
                List.copyOf(new LinkedHashSet<>(destinationIdentities)) : List.of();
            matchedAliasIds = matchedAliasIds != null ? Set.copyOf(matchedAliasIds) : Set.of();
            talkgroupMatchStatus = talkgroupMatchStatus != null ? talkgroupMatchStatus :
                AliasList.TalkgroupMatchStatus.NOT_APPLICABLE;
            broadcastRoutingKeys = immutableStrings(broadcastRoutingKeys);
        }

        private static MatchContext capture(AudioCallSnapshot snapshot, boolean recordAudio,
                                            boolean destinationRecordEnabled, Set<String> broadcastRoutingKeys)
        {
            Set<DestinationIdentity> destinations = new LinkedHashSet<>();
            Set<Long> matchedAliasIds = Set.of();
            AliasList runtimeAliasList = snapshot.aliasList();
            AliasList.TalkgroupMatchStatus talkgroupMatchStatus =
                AliasList.TalkgroupMatchStatus.NOT_APPLICABLE;

            if(runtimeAliasList != null)
            {
                AliasList.CallMatchResult callMatchResult =
                    runtimeAliasList.getCallMatchResult(snapshot.identifierCollection());
                matchedAliasIds = callMatchResult.matchedAliasIds();
                talkgroupMatchStatus = callMatchResult.talkgroupMatchStatus();
            }

            if(snapshot.identifierCollection() != null)
            {
                for(Identifier<?> identifier : snapshot.identifierCollection().getIdentifiers())
                {
                    if(identifier instanceof TalkgroupIdentifier talkgroup &&
                        AudioCallRecordingMetadata.isDestination(identifier))
                    {
                        addTalkgroupIdentity(destinations, talkgroup);
                    }
                    else if(identifier instanceof PatchGroupIdentifier patchIdentifier)
                    {
                        PatchGroup patchGroup = patchIdentifier.getValue();

                        if(patchGroup != null)
                        {
                            addTalkgroupIdentity(destinations, patchGroup.getPatchGroup());

                            for(TalkgroupIdentifier patchedTalkgroup : patchGroup.getPatchedTalkgroupIdentifiers())
                            {
                                addTalkgroupIdentity(destinations, patchedTalkgroup);
                            }
                        }
                    }
                }
            }

            AudioCallRecordingMetadata metadata = snapshot.recordingMetadata();
            String channelIdentity = configurationValue(snapshot, Form.UNIQUE_ID);
            String aliasList = configurationValue(snapshot, Form.ALIAS_LIST);
            String system = configurationValue(snapshot, Form.SYSTEM);
            long aliasListId = runtimeAliasList != null ? runtimeAliasList.getId() : 0L;

            if(channelIdentity == null && metadata != null)
            {
                channelIdentity = metadata.channelIdentity();
            }

            if(aliasList == null && metadata != null)
            {
                aliasList = metadata.aliasListName();
            }

            if(aliasList == null && runtimeAliasList != null)
            {
                aliasList = runtimeAliasList.getName();
            }

            if(system == null && metadata != null)
            {
                system = metadata.systemName();
            }

            return new MatchContext(channelIdentity, aliasListId, aliasList, system, List.copyOf(destinations),
                matchedAliasIds, talkgroupMatchStatus, recordAudio, destinationRecordEnabled,
                broadcastRoutingKeys);
        }

        public boolean hasMatchingIdentity()
        {
            return channelConfigurationId != null || !destinationIdentities.isEmpty() || !matchedAliasIds.isEmpty() ||
                talkgroupMatchStatus != AliasList.TalkgroupMatchStatus.NOT_APPLICABLE;
        }

        public boolean hasOutputPolicy()
        {
            return recordAudio || destinationTalkgroupRecordEnabled || !broadcastRoutingKeys.isEmpty();
        }

        private static String configurationValue(AudioCallSnapshot snapshot, Form form)
        {
            if(snapshot == null || snapshot.identifierCollection() == null)
            {
                return null;
            }

            Identifier<?> identifier = snapshot.identifierCollection()
                .getIdentifier(IdentifierClass.CONFIGURATION, form, Role.ANY);
            return identifier != null && identifier.getValue() != null ?
                normalize(identifier.getValue().toString()) : null;
        }

        private static void addTalkgroupIdentity(Set<DestinationIdentity> destinations,
                                                 TalkgroupIdentifier talkgroup)
        {
            DestinationIdentity destination = DestinationIdentity.from(talkgroup);

            if(destination != null)
            {
                destinations.add(destination);
            }
        }
    }

    /**
     * Immutable protocol-aware talkgroup identity. The qualifier is null for an ordinary talkgroup.
     */
    public record DestinationIdentity(Protocol protocol, int talkgroup, DestinationQualifier qualifier)
    {
        /**
         * Creates the canonical identity represented by a runtime talkgroup identifier.
         */
        public static DestinationIdentity from(TalkgroupIdentifier talkgroup)
        {
            if(talkgroup instanceof FullyQualifiedTalkgroupIdentifier fullyQualified)
            {
                return new DestinationIdentity(normalizeProtocol(talkgroup.getProtocol()),
                    fullyQualified.getTalkgroup(),
                    DestinationQualifier.networkAndSystem(fullyQualified.getWacn(), fullyQualified.getSystem()));
            }
            else if(talkgroup instanceof NXDNFullyQualifiedTalkgroupIdentifier fullyQualified)
            {
                return new DestinationIdentity(normalizeProtocol(talkgroup.getProtocol()), fullyQualified.getValue(),
                    DestinationQualifier.system(fullyQualified.getSystem()));
            }
            else if(talkgroup != null && talkgroup.getValue() != null)
            {
                return new DestinationIdentity(normalizeProtocol(talkgroup.getProtocol()), talkgroup.getValue(), null);
            }

            return null;
        }

        public boolean fullyQualified()
        {
            return qualifier != null;
        }

        /**
         * Matches an exact qualified destination or an unqualified destination that deliberately leaves its home
         * system as a wildcard.
         */
        public boolean matches(DestinationIdentity other)
        {
            return other != null && protocol == other.protocol && talkgroup == other.talkgroup &&
                (qualifier == null || other.qualifier == null || qualifier.equals(other.qualifier));
        }

        private static Protocol normalizeProtocol(Protocol protocol)
        {
            return protocol == Protocol.APCO25_PHASE2 ? Protocol.APCO25 : protocol;
        }
    }

    /**
     * Protocol-neutral home-system qualifier for a destination.
     *
     * <p>The optional network ID is a protocol-native network namespace when one exists. P25 therefore supplies its
     * WACN as the network ID plus its system ID, while NXDN supplies only its system ID. Keeping these values in a
     * separate qualifier avoids treating an NXDN system as a P25 WACN.</p>
     */
    public record DestinationQualifier(Integer networkId, int systemId)
    {
        public static DestinationQualifier networkAndSystem(int networkId, int systemId)
        {
            return new DestinationQualifier(networkId, systemId);
        }

        public static DestinationQualifier system(int systemId)
        {
            return new DestinationQualifier(null, systemId);
        }

        public boolean hasNetwork()
        {
            return networkId != null;
        }
    }
}
