/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.traffic;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelConfigurationKey;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.radio.RadioIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;

/**
 * Immutable completed talker-alias facts. The completion time controls alias freshness, while the physical call's
 * earlier start time controls which radio-system generation owns the alias.
 */
public record TrunkedTalkerAliasEvent(String configurationId, DecoderType decoderType, Protocol protocol,
                                      RadioIdentifier radio, String talkerAlias, List<Identifier> identifiers,
                                      TrunkedIdentityDomain identityDomain,
                                      long observedAtEpochMilliseconds, long callStartEpochMilliseconds,
                                      String radioSystemKey)
{
    public TrunkedTalkerAliasEvent
    {
        configurationId = ChannelConfigurationKey.canonical(configurationId);
        talkerAlias = talkerAlias != null ? talkerAlias.strip() : null;
        identifiers = identifiers != null ? List.copyOf(identifiers) : List.of();
        identityDomain = identityDomain != null ? identityDomain : TrunkedIdentityDomain.STANDARD;
        radioSystemKey = RadioSystemKey.validateForReceiver(protocol, identityDomain, configurationId,
            radioSystemKey);
    }

    public TrunkedTalkerAliasEvent(Channel channel, Protocol protocol, RadioIdentifier radio,
                                   TalkerAliasIdentifier alias, IdentifierCollection identifiers,
                                   TrunkedIdentityDomain identityDomain, long observedAtEpochMilliseconds,
                                   CallSystemIdentity callIdentity)
    {
        this(ChannelConfigurationKey.configured(channel), decoderType(channel), protocol, radio,
            alias != null ? alias.getValue() : null,
            identifiers != null ? identifiers.getIdentifiers() : List.of(), identityDomain,
            observedAtEpochMilliseconds, callIdentity != null ? callIdentity.callStartEpochMilliseconds() : 0,
            callIdentity != null ? callIdentity.radioSystemKey() : null);
    }

    public IdentifierCollection identifierCollection()
    {
        return new IdentifierCollection(identifiers);
    }

    private static DecoderType decoderType(Channel channel)
    {
        return channel != null && channel.getDecodeConfiguration() != null ?
            channel.getDecodeConfiguration().getDecoderType() : null;
    }
}
