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

package io.github.dsheirer.audio.call;

import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.controller.channel.ChannelConfigurationKey;
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.IncompleteIdentifier;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemIdentityKey;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityEligibility;
import io.github.dsheirer.protocol.Protocol;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Stable server-owned target used by browser playback controls.
 *
 * <p>This is deliberately separate from display names and Alias Lists. Conventional calls are selected by their
 * saved channel identity, while trunked calls are selected by an identity inside a radio-system scope. This prevents
 * a synthetic conventional talkgroup or the same numeric talkgroup on another system from crossing a Hold or Avoid
 * boundary.</p>
 */
public record CallPlaybackTarget(String key, Kind kind, String radioSystemKey, Integer timeslot)
{
    public CallPlaybackTarget
    {
        if(key == null || key.isBlank() || kind == null)
        {
            throw new IllegalArgumentException("Playback target identity is incomplete");
        }

        key = key.strip();
        radioSystemKey = text(radioSystemKey);

        if(kind == Kind.CHANNEL_TIMESLOT && (timeslot == null || timeslot < 1 || timeslot > 2))
        {
            throw new IllegalArgumentException("DMR conventional playback target requires timeslot 1 or 2");
        }
        else if(kind != Kind.CHANNEL_TIMESLOT)
        {
            timeslot = null;
        }
    }

    /**
     * Creates the one playback target for a completed call. A missing saved channel identity is treated as invalid
     * rather than falling back to a mutable channel or system name.
     */
    public static CallPlaybackTarget from(AudioCallSnapshot snapshot, Identifier<?> target)
    {
        if(snapshot == null)
        {
            return null;
        }

        CallLegSource source = snapshot.callLegSource();
        IdentifierCollection identifiers = snapshot.identifierCollection();
        String configurationId = source != null ?
            ChannelConfigurationKey.canonical(source.channelConfigurationId()) : null;

        if(configurationId == null || source.decoderType() == null || source.channelKind() == null)
        {
            return null;
        }

        ChannelConfigurationPolicy.ChannelKind channelKind = source.channelKind();
        DecoderType decoderType = source.decoderType();
        Protocol configuredProtocol = canonicalProtocol(decoderType.getProtocol());
        if(configuredProtocol == Protocol.UNKNOWN)
        {
            return null;
        }

        Protocol protocol = configuredProtocol;
        if(channelKind == ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL)
        {
            if(protocol == Protocol.DMR)
            {
                int slot = snapshot.timeslot();

                if(slot < 1 || slot > 2)
                {
                    return null;
                }

                String key = "channel:" + configurationId + ":timeslot:" + slot;
                return new CallPlaybackTarget(key, Kind.CHANNEL_TIMESLOT, null, slot);
            }

            return new CallPlaybackTarget("channel:" + configurationId, Kind.CHANNEL, null, null);
        }

        if(channelKind != ChannelConfigurationPolicy.ChannelKind.TRUNKED)
        {
            return null;
        }

        if(target instanceof IncompleteIdentifier)
        {
            return null;
        }

        Protocol targetProtocol = canonicalProtocol(target != null ? target.getProtocol() : Protocol.UNKNOWN);
        if(targetProtocol != Protocol.UNKNOWN && protocol != targetProtocol)
        {
            return null;
        }

        TrunkedIdentityDomain identityDomain = source.identityDomain();
        if(protocol == Protocol.NXDN &&
            (!TrunkedIdentityEligibility.nxdnIdentifierMatchesDomain(target, identityDomain) ||
                !TrunkedIdentityEligibility.nxdnIdentifiersMatchDomain(identifiers, identityDomain)))
        {
            return null;
        }

        P25Home servingP25Home = protocol == Protocol.APCO25 ? servingP25Home(source, identifiers) : null;
        String radioSystemKey = radioSystemKey(protocol, identityDomain, configurationId, servingP25Home,
            source.radioSystemKey());

        if(radioSystemKey == null)
        {
            return null;
        }

        String prefix = "system:" + radioSystemKey + ':';

        if(target instanceof FullyQualifiedTalkgroupIdentifier fullyQualified)
        {
            return identityTarget(prefix, radioSystemKey, protocol, identityDomain, Kind.TALKGROUP,
                RadioSystemIdentityKey.KIND_TALKGROUP, fullyQualified.getWacn(), fullyQualified.getSystem(),
                fullyQualified.getTalkgroup());
        }
        else if(target instanceof PatchGroupIdentifier patchIdentifier)
        {
            PatchGroup patch = patchIdentifier.getValue();

            if(patch != null && patch.getPatchGroup() != null)
            {
                if(protocol == Protocol.APCO25 &&
                    patch.getPatchGroup() instanceof FullyQualifiedTalkgroupIdentifier fullyQualified)
                {
                    return identityTarget(prefix, radioSystemKey, protocol, identityDomain, Kind.PATCH_GROUP,
                        RadioSystemIdentityKey.KIND_PATCH_GROUP, fullyQualified.getWacn(),
                        fullyQualified.getSystem(), fullyQualified.getTalkgroup());
                }

                if(protocol == Protocol.APCO25 && servingP25Home != null)
                {
                    return identityTarget(prefix, radioSystemKey, protocol, identityDomain, Kind.PATCH_GROUP,
                        RadioSystemIdentityKey.KIND_PATCH_GROUP, servingP25Home.wacn(),
                        servingP25Home.system(), patch.getPatchGroup().getValue());
                }
            }
        }
        else if(target instanceof FullyQualifiedRadioIdentifier fullyQualified)
        {
            return identityTarget(prefix, radioSystemKey, protocol, identityDomain, Kind.RADIO,
                RadioSystemIdentityKey.KIND_RADIO,
                fullyQualified.getWacn(), fullyQualified.getSystem(), fullyQualified.getRadio());
        }
        else if(target != null && target.getValue() instanceof Number number)
        {
            Kind kind = switch(target.getForm())
            {
                case TALKGROUP -> Kind.TALKGROUP;
                case RADIO -> Kind.RADIO;
                default -> null;
            };

            if(kind != null)
            {
                if(protocol == Protocol.APCO25 && servingP25Home != null)
                {
                    return identityTarget(prefix, radioSystemKey, protocol, identityDomain, kind,
                        identityKind(kind),
                        servingP25Home.wacn(), servingP25Home.system(), number.intValue());
                }

                return identityTarget(prefix, radioSystemKey, protocol, identityDomain, kind, identityKind(kind),
                    RadioSystemIdentityKey.NO_HOME, RadioSystemIdentityKey.NO_HOME, number.intValue());
            }
        }

        return null;
    }

    public Map<String,Object> toMap(String label)
    {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("key", key);
        value.put("kind", kind.wireName());

        if(radioSystemKey != null)
        {
            value.put("radio_system_key", radioSystemKey);
        }
        if(timeslot != null)
        {
            value.put("timeslot", timeslot);
        }
        if(label != null && !label.isBlank())
        {
            value.put("label", label.strip());
        }

        return Map.copyOf(value);
    }

    private static String radioSystemKey(Protocol protocol, TrunkedIdentityDomain identityDomain,
                                         String configurationId, P25Home servingP25Home,
                                         String learnedNativeSystemKey)
    {
        if(protocol == Protocol.APCO25 && servingP25Home != null)
        {
            return RadioSystemKey.p25(servingP25Home.wacn(), servingP25Home.system());
        }

        boolean nativeDmrTierThree = protocol == Protocol.DMR && learnedNativeSystemKey != null &&
            learnedNativeSystemKey.startsWith("dmr:tier3:");
        boolean nativeNxdnTypeC = protocol == Protocol.NXDN && identityDomain == TrunkedIdentityDomain.NXDN_TYPE_C &&
            learnedNativeSystemKey != null && learnedNativeSystemKey.startsWith("nxdn-c:") &&
            !learnedNativeSystemKey.startsWith("nxdn-c:channel:");
        if((nativeDmrTierThree || nativeNxdnTypeC) && RadioSystemKey.isCanonical(learnedNativeSystemKey))
        {
            return learnedNativeSystemKey;
        }

        return protocol == Protocol.APCO25 ? null :
            RadioSystemKey.channelScoped(protocol, identityDomain, configurationId);
    }

    private static P25Home servingP25Home(CallLegSource source, IdentifierCollection identifiers)
    {
        P25SiteIdentity learned = source != null ? source.p25SiteIdentity() : null;
        Integer wacn = learned != null ? learned.wacn() : integerIdentifier(identifiers, Form.WACN);
        Integer system = learned != null ? learned.system() : integerIdentifier(identifiers, Form.SYSTEM);
        return wacn != null && system != null ? new P25Home(wacn, system) : null;
    }

    private static CallPlaybackTarget identityTarget(String prefix, String radioSystemKey, Protocol protocol,
                                                     TrunkedIdentityDomain identityDomain, Kind kind,
                                                     int identityKind, int homeWacn, int homeSystemId,
                                                     int identityId)
    {
        Form form = switch(kind)
        {
            case TALKGROUP -> Form.TALKGROUP;
            case PATCH_GROUP -> Form.PATCH_GROUP;
            case RADIO -> Form.RADIO;
            default -> null;
        };

        if(form == null || !TrunkedIdentityEligibility.isEligible(protocol, identityDomain,
            form, identityId))
        {
            return null;
        }

        try
        {
            return new CallPlaybackTarget(prefix + RadioSystemIdentityKey.format(identityKind, homeWacn,
                homeSystemId, identityId), kind, radioSystemKey, null);
        }
        catch(IllegalArgumentException exception)
        {
            return null;
        }
    }

    private static int identityKind(Kind kind)
    {
        return switch(kind)
        {
            case TALKGROUP -> RadioSystemIdentityKey.KIND_TALKGROUP;
            case RADIO -> RadioSystemIdentityKey.KIND_RADIO;
            case PATCH_GROUP -> RadioSystemIdentityKey.KIND_PATCH_GROUP;
            default -> throw new IllegalArgumentException("Not a radio-system identity target");
        };
    }

    private static Integer integerIdentifier(IdentifierCollection identifiers, Form form)
    {
        Identifier<?> identifier = identifiers != null ?
            identifiers.getIdentifier(IdentifierClass.NETWORK, form, Role.BROADCAST) : null;
        return identifier != null && identifier.getValue() instanceof Number number ? number.intValue() : null;
    }

    private static Protocol canonicalProtocol(Protocol protocol)
    {
        return protocol == Protocol.APCO25_PHASE2 ? Protocol.APCO25 :
            protocol != null ? protocol : Protocol.UNKNOWN;
    }

    private static String text(String value)
    {
        return value != null && !value.isBlank() ? value.strip() : null;
    }

    private record P25Home(int wacn, int system)
    {
    }

    public enum Kind
    {
        CHANNEL("channel"),
        CHANNEL_TIMESLOT("channel_timeslot"),
        TALKGROUP("talkgroup"),
        PATCH_GROUP("patch_group"),
        RADIO("radio");

        private final String mWireName;

        Kind(String wireName)
        {
            mWireName = wireName;
        }

        String wireName()
        {
            return mWireName;
        }

    }
}
