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
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IncompleteIdentifier;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityEligibility;
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
public record ResolvedCallPolicy(boolean recordAudio, boolean destinationRecordEnabled,
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
                    metadata != null && metadata.destinationRecordEnabled();
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
     * Captures stable broadcast-configuration UUIDs used by the runtime as routing keys.
     */
    private static Set<String> broadcastRoutingKeys(Collection<BroadcastChannel> broadcastChannels)
    {
        Set<String> destinations = new LinkedHashSet<>();

        if(broadcastChannels != null)
        {
            for(BroadcastChannel broadcastChannel : broadcastChannels)
            {
                String destination = normalize(broadcastChannel != null ?
                    broadcastChannel.getConfigurationId() : null);

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
    public record MatchContext(String channelConfigurationId, long aliasListId,
                               ChannelConfigurationPolicy.ChannelKind channelKind,
                               List<DestinationIdentity> destinationIdentities,
                               Set<Long> matchedAliasIds, AliasList.TalkgroupMatchStatus talkgroupMatchStatus,
                               boolean recordAudio,
                               boolean destinationRecordEnabled, Set<String> broadcastRoutingKeys)
    {
        public MatchContext
        {
            channelConfigurationId = normalize(channelConfigurationId);
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
                    if((identifier instanceof TalkgroupIdentifier || identifier instanceof RadioIdentifier) &&
                        AudioCallRecordingMetadata.isDestination(identifier))
                    {
                        addDestinationIdentity(destinations, identifier);
                    }
                    else if(identifier instanceof PatchGroupIdentifier patchIdentifier)
                    {
                        PatchGroup patchGroup = patchIdentifier.getValue();

                        if(patchGroup != null)
                        {
                            addDestinationIdentity(destinations, patchIdentifier);

                            for(TalkgroupIdentifier patchedTalkgroup : patchGroup.getPatchedTalkgroupIdentifiers())
                            {
                                addDestinationIdentity(destinations, patchedTalkgroup);
                            }
                        }
                    }
                }
            }

            AudioCallRecordingMetadata metadata = snapshot.recordingMetadata();
            String channelIdentity = configurationValue(snapshot, Form.UNIQUE_ID);
            long aliasListId = runtimeAliasList != null ? runtimeAliasList.getId() : 0L;
            ChannelConfigurationPolicy.ChannelKind channelKind = snapshot.callLegSource() != null ?
                snapshot.callLegSource().channelKind() : null;

            if(channelIdentity == null && metadata != null)
            {
                channelIdentity = metadata.channelIdentity();
            }

            return new MatchContext(channelIdentity, aliasListId, channelKind, List.copyOf(destinations),
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
            return recordAudio || destinationRecordEnabled || !broadcastRoutingKeys.isEmpty();
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

        private static void addDestinationIdentity(Set<DestinationIdentity> destinations,
                                                   Identifier<?> identifier)
        {
            DestinationIdentity destination = DestinationIdentity.from(identifier);

            if(destination != null)
            {
                destinations.add(destination);
            }
        }
    }

    /**
     * Immutable protocol-aware destination identity. The qualifier is null for an ordinary local identity.
     */
    public record DestinationIdentity(Protocol protocol, Form kind, int localAddress, int canonicalIdentity,
                                      DestinationQualifier qualifier)
    {
        /**
         * Creates the canonical identity represented by a runtime talkgroup identifier.
         */
        public static DestinationIdentity from(Identifier<?> destination)
        {
            Form kind = destination instanceof PatchGroupIdentifier ? Form.PATCH_GROUP :
                destination != null ? destination.getForm() : null;
            Identifier<?> primary = destination instanceof PatchGroupIdentifier patch && patch.getValue() != null ?
                patch.getValue().getPatchGroup() : destination;

            if(destination instanceof IncompleteIdentifier || primary instanceof IncompleteIdentifier)
            {
                return null;
            }

            if(primary instanceof FullyQualifiedTalkgroupIdentifier fullyQualified)
            {
                Protocol protocol = normalizeProtocol(primary.getProtocol());
                return eligible(protocol, kind, fullyQualified.getTalkgroup()) &&
                    eligibleDecoded(protocol, destination) ?
                    new DestinationIdentity(protocol, kind, fullyQualified.getValue(), fullyQualified.getTalkgroup(),
                        DestinationQualifier.networkAndSystem(fullyQualified.getWacn(), fullyQualified.getSystem())) :
                    null;
            }
            else if(primary instanceof FullyQualifiedRadioIdentifier fullyQualified)
            {
                Protocol protocol = normalizeProtocol(primary.getProtocol());
                return eligible(protocol, Form.RADIO, fullyQualified.getRadio()) &&
                    eligibleDecoded(protocol, destination) ?
                    new DestinationIdentity(protocol, Form.RADIO, fullyQualified.getValue(), fullyQualified.getRadio(),
                        DestinationQualifier.networkAndSystem(fullyQualified.getWacn(), fullyQualified.getSystem())) :
                    null;
            }
            else if(primary != null && primary.getValue() instanceof Number number &&
                (kind == Form.TALKGROUP || kind == Form.PATCH_GROUP || kind == Form.RADIO))
            {
                Protocol protocol = normalizeProtocol(primary.getProtocol());
                return eligible(protocol, kind, number.intValue()) ?
                    new DestinationIdentity(protocol, kind, number.intValue(), number.intValue(), null) : null;
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
            if(other == null || protocol != other.protocol || kind != other.kind)
            {
                return false;
            }

            if(qualifier != null && other.qualifier != null)
            {
                return canonicalIdentity == other.canonicalIdentity && qualifier.equals(other.qualifier);
            }

            return localAddress > 0 && localAddress == other.localAddress;
        }

        private static Protocol normalizeProtocol(Protocol protocol)
        {
            return protocol == Protocol.APCO25_PHASE2 ? Protocol.APCO25 : protocol;
        }

        private static boolean eligible(Protocol protocol, Form kind, int identifier)
        {
            return protocol != Protocol.APCO25 || TrunkedIdentityEligibility.isEligible(protocol,
                TrunkedIdentityDomain.STANDARD, kind, identifier);
        }

        private static boolean eligibleDecoded(Protocol protocol, Identifier<?> identifier)
        {
            return protocol != Protocol.APCO25 || TrunkedIdentityEligibility.isEligibleDecodedIdentifier(protocol,
                TrunkedIdentityDomain.STANDARD, identifier);
        }
    }

    /**
     * P25 home-system qualifier for a fully-qualified destination.
     */
    public record DestinationQualifier(Integer networkId, int systemId)
    {
        public static DestinationQualifier networkAndSystem(int networkId, int systemId)
        {
            return new DestinationQualifier(networkId, systemId);
        }

        public boolean hasNetwork()
        {
            return networkId != null;
        }
    }
}
