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
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.alias.TalkerAliasIdentifier;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.talkgroup.TalkgroupIdentifier;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;

/** Extracts immutable scalar facts before a trunked call crosses an asynchronous observer boundary. */
final class TrunkedCallSnapshot
{
    private TrunkedCallSnapshot()
    {
    }

    static TrunkedIdentityDomain identityDomain(Channel channel, Protocol protocol)
    {
        if(protocol != Protocol.NXDN)
        {
            return TrunkedIdentityDomain.STANDARD;
        }

        return channel != null && channel.getDecodeConfiguration() instanceof DecodeConfigNXDN config &&
            config.getTransmissionMode() != null && config.getTransmissionMode().isTypeD() ?
                TrunkedIdentityDomain.NXDN_TYPE_D : TrunkedIdentityDomain.NXDN_TYPE_C;
    }

    static String eligibleConfigurationId(Channel channel, Protocol protocol, IdentifierCollection identifiers)
    {
        TrunkedIdentityDomain domain = identityDomain(channel, protocol);
        return protocol != Protocol.NXDN || TrunkedIdentityEligibility.nxdnIdentifiersMatchDomain(identifiers, domain) ?
            ChannelConfigurationKey.configured(channel) : null;
    }

    static Integer sourceRadioId(IdentifierCollection identifiers)
    {
        Identifier source = identifiers != null ? identifiers.getFromIdentifier() : null;
        return source != null && source.getForm() == Form.RADIO ? identityId(source) : null;
    }

    static Integer sourceRadioIdentity(IdentifierCollection identifiers)
    {
        Identifier source = identifiers != null ? identifiers.getFromIdentifier() : null;
        return source != null && source.getForm() == Form.RADIO ? identityId(source) : null;
    }

    static Integer targetId(IdentifierCollection identifiers)
    {
        Identifier target = identifiers != null ? identifiers.getToIdentifier() : null;
        return identityId(target);
    }

    static Integer destinationId(IdentifierCollection identifiers)
    {
        return identityId(identifiers != null ? identifiers.getToIdentifier() : null);
    }

    static Form targetForm(IdentifierCollection identifiers)
    {
        Identifier target = identifiers != null ? identifiers.getToIdentifier() : null;
        return target != null ? target.getForm() : null;
    }

    static List<Integer> patchMemberTalkgroupIds(IdentifierCollection identifiers)
    {
        Identifier target = identifiers != null ? identifiers.getToIdentifier() : null;
        if(!(target instanceof PatchGroupIdentifier patch) || patch.getValue() == null)
        {
            return List.of();
        }

        Integer canonical = identityId(target);
        return patch.getValue().getPatchedTalkgroupIdentifiers().stream()
            .filter(member -> member != null && member.getValue() != null && member.getValue() > 0)
            .map(TalkgroupIdentifier::getValue)
            .filter(member -> !member.equals(canonical))
            .distinct()
            .sorted()
            .toList();
    }

    static Integer integerValue(IdentifierCollection identifiers, Form form)
    {
        if(identifiers == null)
        {
            return null;
        }

        List<Identifier> matches = identifiers.getIdentifiers(form);
        return matches.isEmpty() ? null : identityId(matches.get(0));
    }

    static String talkerAlias(IdentifierCollection identifiers)
    {
        if(identifiers == null)
        {
            return null;
        }

        List<Identifier> matches = identifiers.getIdentifiers(Form.TALKER_ALIAS);
        if(matches.isEmpty() || !(matches.getFirst() instanceof TalkerAliasIdentifier alias))
        {
            return null;
        }

        return alias.getValue();
    }

    private static Integer identityId(Identifier identifier)
    {
        if(identifier instanceof PatchGroupIdentifier patch && patch.getValue() != null &&
            patch.getValue().getPatchGroup() != null)
        {
            Identifier primary = patch.getValue().getPatchGroup();
            return primary.getValue() instanceof Number number ? number.intValue() : null;
        }

        return identifier != null && identifier.getValue() instanceof Number number ? number.intValue() : null;
    }
}
