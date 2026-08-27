/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.audio.call;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.UnmatchedTalkgroupPolicy;
import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNFullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.List;

/**
 * Compact immutable call metadata captured while aliases are matched.
 *
 * <p>This object deliberately contains no Alias, AliasList, IdentifierCollection, or other mutable runtime graph.
 * The standard recording writer can therefore use the same historical decision even if an administrator edits
 * aliases while a call is active or queued for disk.</p>
 */
public record AudioCallRecordingMetadata(String systemName, String systemIdentity, String siteName,
                                         String siteIdentity, String channelName, String channelIdentity,
                                         String aliasListName, String destinationProtocol, String destinationValue,
                                         String destinationIdentity, String destinationAlias,
                                         String destinationDescription, String destinationGroup,
                                         String destinationMatcherIdentity,
                                         boolean destinationTalkgroupRecordEnabled, String sourceProtocol,
                                         String sourceValue, String sourceAlias, String sourceDescription,
                                         String sourceGroup)
{
    private static final int MAXIMUM_LABEL_LENGTH = 160;

    public static AudioCallRecordingMetadata captureAtSnapshot(AliasList aliasList,
                                                               IdentifierCollection identifiers)
    {
        return capture(identifiers, captureDestination(aliasList, destinationIdentifier(identifiers)),
            captureSource(aliasList, sourceIdentifier(identifiers)));
    }

    public static AudioCallRecordingMetadata capture(IdentifierCollection identifiers,
                                                     DestinationDecision destination,
                                                     SourceDecision source)
    {
        String system = identifierText(identifiers, IdentifierClass.CONFIGURATION, Form.SYSTEM, Role.ANY);
        String site = identifierText(identifiers, IdentifierClass.CONFIGURATION, Form.SITE, Role.ANY);
        String siteGuid = identifierText(identifiers, IdentifierClass.CONFIGURATION, Form.RADRES_GUID, Role.ANY);
        String channel = identifierText(identifiers, IdentifierClass.CONFIGURATION, Form.CHANNEL, Role.ANY);
        String channelIdentity =
            identifierText(identifiers, IdentifierClass.CONFIGURATION, Form.UNIQUE_ID, Role.ANY);
        String aliasList = identifierText(identifiers, IdentifierClass.CONFIGURATION, Form.ALIAS_LIST, Role.ANY);
        String stableSiteIdentity = hasText(siteGuid) ? siteGuid : nullSafe(system) + ':' + nullSafe(site);
        String stableChannelIdentity = hasText(channelIdentity) ? channelIdentity :
            nullSafe(system) + ':' + nullSafe(site) + ':' + nullSafe(channel);
        DestinationDecision safeDestination = destination != null ? destination : DestinationDecision.empty();
        SourceDecision safeSource = source != null ? source : SourceDecision.empty();
        return new AudioCallRecordingMetadata(label(system), nullSafe(system), label(site), stableSiteIdentity,
            label(channel), stableChannelIdentity, label(aliasList), safeDestination.protocol(),
            safeDestination.value(), safeDestination.receivedIdentity(), safeDestination.aliasName(),
            safeDestination.aliasDescription(), safeDestination.aliasGroup(), safeDestination.matcherIdentity(),
            safeDestination.recordEnabled(), safeSource.protocol(), safeSource.value(), safeSource.aliasName(),
            safeSource.aliasDescription(), safeSource.aliasGroup());
    }

    /**
     * Applies the coordinator's final, most-exact user identities while retaining the alias and output decisions
     * frozen by the physical legs.  Resolution runs after audio-quality election, so these identities need not come
     * from the receiver that supplied the selected audio.
     */
    AudioCallRecordingMetadata withResolvedUserIdentifiers(Identifier<?> destination, Identifier<?> source)
    {
        String resolvedDestinationProtocol = destination != null ? protocol(destination) : destinationProtocol;
        String resolvedDestinationValue = destination != null ? destinationValue(destination) : destinationValue;
        String resolvedDestinationIdentity = destination != null ?
            receivedDestinationIdentity(destination) : destinationIdentity;
        String resolvedSourceProtocol = source != null ? protocol(source) : sourceProtocol;
        String resolvedSourceValue = source != null ? receivedSourceIdentity(source) : sourceValue;
        return new AudioCallRecordingMetadata(systemName, systemIdentity, siteName, siteIdentity, channelName,
            channelIdentity, aliasListName, resolvedDestinationProtocol, resolvedDestinationValue,
            resolvedDestinationIdentity, destinationAlias, destinationDescription, destinationGroup,
            destinationMatcherIdentity, destinationTalkgroupRecordEnabled, resolvedSourceProtocol,
            resolvedSourceValue, sourceAlias, sourceDescription, sourceGroup);
    }

    public static boolean isDestination(Identifier<?> identifier)
    {
        return identifier != null && identifier.getIdentifierClass() == IdentifierClass.USER &&
            identifier.getRole() == Role.TO &&
            (identifier.getForm() == Form.TALKGROUP || identifier.getForm() == Form.PATCH_GROUP);
    }

    public static boolean isSource(Identifier<?> identifier)
    {
        return identifier != null && identifier.getIdentifierClass() == IdentifierClass.USER &&
            identifier.getRole() == Role.FROM && identifier.getForm() == Form.RADIO;
    }

    public static DestinationDecision captureDestination(AliasList aliasList, Identifier<?> destination)
    {
        String value = destinationValue(destination);
        String fallbackIdentity = receivedDestinationIdentity(destination);

        if(aliasList == null || destination == null)
        {
            return new DestinationDecision(protocol(destination), value, fallbackIdentity, null, null, null,
                fallbackIdentity, false);
        }

        List<TalkgroupIdentifier> candidates = new ArrayList<>();

        if(destination instanceof PatchGroupIdentifier patchGroupIdentifier)
        {
            PatchGroup patchGroup = patchGroupIdentifier.getValue();
            candidates.add(patchGroup.getPatchGroup());
            candidates.addAll(patchGroup.getPatchedTalkgroupIdentifiers());
        }
        else if(destination instanceof TalkgroupIdentifier talkgroupIdentifier)
        {
            candidates.add(talkgroupIdentifier);
        }
        else
        {
            return new DestinationDecision(protocol(destination), value, fallbackIdentity, null, null, null,
                fallbackIdentity, false);
        }

        DestinationDecision firstMatch = null;

        for(TalkgroupIdentifier candidate: candidates)
        {
            if(candidate == null)
            {
                continue;
            }

            for(Alias alias: aliasList.getAliases(candidate))
            {
                AliasID matcher = matchingTalkgroupAliasId(alias, candidate);
                String matcherIdentity = matcher != null ? matcherIdentity(matcher) :
                    receivedDestinationIdentity(candidate);
                DestinationDecision match = new DestinationDecision(protocol(destination), value, fallbackIdentity,
                    label(alias.getName()), label(alias.getDescription()), label(alias.getGroup()), matcherIdentity,
                    alias.isRecordable());

                if(match.recordEnabled())
                {
                    return match;
                }

                if(firstMatch == null)
                {
                    firstMatch = match;
                }
            }
        }

        if(firstMatch != null)
        {
            return firstMatch;
        }

        UnmatchedTalkgroupPolicy unmatchedPolicy = aliasList.getUnmatchedTalkgroupPolicy(destination);
        return new DestinationDecision(protocol(destination), value, fallbackIdentity, null, null, null,
            fallbackIdentity, unmatchedPolicy != null && unmatchedPolicy.isRecordEnabled());
    }

    public static SourceDecision captureSource(AliasList aliasList, Identifier<?> source)
    {
        if(source == null)
        {
            return SourceDecision.empty();
        }

        String aliasName = null;
        String aliasDescription = null;
        String aliasGroup = null;

        if(aliasList != null)
        {
            List<Alias> aliases = aliasList.getAliases(source);

            if(!aliases.isEmpty())
            {
                Alias alias = aliases.getFirst();
                aliasName = label(alias.getName());
                aliasDescription = label(alias.getDescription());
                aliasGroup = label(alias.getGroup());
            }
        }

        return new SourceDecision(protocol(source),
            source.getValue() != null ? source.getValue().toString() : null, aliasName, aliasDescription, aliasGroup);
    }

    private static AliasID matchingTalkgroupAliasId(Alias alias, TalkgroupIdentifier destination)
    {
        AliasID aliasID = alias != null ? alias.getMatchIdentifier() : null;

        if(aliasID instanceof Talkgroup matcher &&
            protocolsMatch(matcher.getProtocol(), destination.getProtocol()) &&
            matcher.getValue() == destination.getValue())
        {
            return matcher;
        }

        if(aliasID instanceof TalkgroupRange matcher &&
            protocolsMatch(matcher.getProtocol(), destination.getProtocol()) &&
            matcher.contains(destination.getValue()))
        {
            return matcher;
        }

        return null;
    }

    private static boolean protocolsMatch(Protocol first, Protocol second)
    {
        return first != null && second != null && canonicalProtocol(first) == canonicalProtocol(second);
    }

    private static Protocol canonicalProtocol(Protocol protocol)
    {
        return protocol == Protocol.APCO25_PHASE2 ? Protocol.APCO25 : protocol;
    }

    private static String matcherIdentity(AliasID matcher)
    {
        if(matcher instanceof TalkgroupRange range)
        {
            return "range:" + range.getProtocol() + ':' + range.getMinTalkgroup() + ':' + range.getMaxTalkgroup();
        }
        else if(matcher instanceof Talkgroup talkgroup)
        {
            return "exact:" + talkgroup.getProtocol() + ':' + talkgroup.getValue();
        }

        return matcher.getType() + ":" + matcher;
    }

    private static Identifier<?> destinationIdentifier(IdentifierCollection identifiers)
    {
        if(identifiers == null)
        {
            return null;
        }

        Identifier<?> destination =
            identifiers.getIdentifier(IdentifierClass.USER, Form.PATCH_GROUP, Role.TO);
        return destination != null ? destination :
            identifiers.getIdentifier(IdentifierClass.USER, Form.TALKGROUP, Role.TO);
    }

    private static Identifier<?> sourceIdentifier(IdentifierCollection identifiers)
    {
        return identifiers != null ?
            identifiers.getIdentifier(IdentifierClass.USER, Form.RADIO, Role.FROM) : null;
    }

    private static String destinationValue(Identifier<?> destination)
    {
        if(destination instanceof PatchGroupIdentifier patchGroupIdentifier)
        {
            return destinationValue(patchGroupIdentifier.getValue().getPatchGroup());
        }

        return destination != null && destination.getValue() != null ? destination.getValue().toString() : null;
    }

    private static String receivedDestinationIdentity(Identifier<?> destination)
    {
        if(destination instanceof FullyQualifiedTalkgroupIdentifier fullyQualified)
        {
            return protocol(destination) + ":fq:" + fullyQualified.getWacn() + ':' +
                fullyQualified.getSystem() + ':' + fullyQualified.getTalkgroup();
        }
        else if(destination instanceof NXDNFullyQualifiedTalkgroupIdentifier fullyQualified)
        {
            return protocol(destination) + ":fq:" + fullyQualified.getSystem() + ':' +
                fullyQualified.getValue();
        }
        else if(destination instanceof PatchGroupIdentifier patchGroupIdentifier)
        {
            return receivedDestinationIdentity(patchGroupIdentifier.getValue().getPatchGroup());
        }

        return destination != null ?
            protocol(destination) + ":" + destination.getForm() + ':' + destination.getValue() : null;
    }

    private static String receivedSourceIdentity(Identifier<?> source)
    {
        if(source instanceof FullyQualifiedRadioIdentifier fullyQualified)
        {
            return fullyQualified.getFullyQualifiedRadioAddress();
        }

        return source != null && source.getValue() != null ? source.getValue().toString() : null;
    }

    private static String identifierText(IdentifierCollection identifiers, IdentifierClass identifierClass,
                                         Form form, Role role)
    {
        Identifier<?> identifier = identifiers != null ? identifiers.getIdentifier(identifierClass, form, role) : null;
        return identifier != null && identifier.getValue() != null ? identifier.getValue().toString() : null;
    }

    private static String protocol(Identifier<?> identifier)
    {
        return identifier != null && identifier.getProtocol() != null ? identifier.getProtocol().name() : null;
    }

    private static boolean hasText(String value)
    {
        return value != null && !value.isBlank();
    }

    private static String label(String value)
    {
        if(value == null)
        {
            return null;
        }

        String stripped = value.strip();
        return stripped.length() <= MAXIMUM_LABEL_LENGTH ? stripped : stripped.substring(0, MAXIMUM_LABEL_LENGTH);
    }

    private static String nullSafe(String value)
    {
        return value != null ? value : "";
    }

    public record DestinationDecision(String protocol, String value, String receivedIdentity, String aliasName,
                                      String aliasDescription, String aliasGroup, String matcherIdentity,
                                      boolean recordEnabled)
    {
        static DestinationDecision empty()
        {
            return new DestinationDecision(null, null, null, null, null, null, null, false);
        }
    }

    public record SourceDecision(String protocol, String value, String aliasName, String aliasDescription,
                                 String aliasGroup)
    {
        static SourceDecision empty()
        {
            return new SourceDecision(null, null, null, null, null);
        }
    }
}
