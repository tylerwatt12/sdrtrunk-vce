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
import io.github.dsheirer.identifier.Form;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.identifier.IdentifierClass;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.patch.PatchGroup;
import io.github.dsheirer.identifier.patch.PatchGroupIdentifier;
import io.github.dsheirer.identifier.radio.FullyQualifiedRadioIdentifier;
import io.github.dsheirer.identifier.talkgroup.FullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.nxdn.identifier.NXDNFullyQualifiedTalkgroupIdentifier;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
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
public record CallPlaybackTarget(String key, Kind kind, String systemKey, Integer timeslot)
{
    public CallPlaybackTarget
    {
        if(key == null || key.isBlank() || kind == null)
        {
            throw new IllegalArgumentException("Playback target identity is incomplete");
        }

        key = key.strip();
        systemKey = text(systemKey);

        if(kind == Kind.CHANNEL_TIMESLOT && (timeslot == null || timeslot < 0 || timeslot > 1))
        {
            throw new IllegalArgumentException("DMR conventional playback target requires timeslot 0 or 1");
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
        String configurationId = source != null ? text(source.channelConfigurationId()) : null;

        if(configurationId == null)
        {
            configurationId = identifierText(identifiers, IdentifierClass.CONFIGURATION, Form.UNIQUE_ID, Role.ANY);
        }

        if(configurationId == null)
        {
            return null;
        }

        ChannelConfigurationPolicy.ChannelKind channelKind = source != null ? source.channelKind() : null;
        DecoderType decoderType = source != null ? source.decoderType() : null;
        Protocol protocol = canonicalProtocol(target != null ? target.getProtocol() :
            decoderType != null ? decoderType.getProtocol() : Protocol.UNKNOWN);

        if(channelKind == null)
        {
            channelKind = inferredChannelKind(identifiers, decoderType);
        }

        if(channelKind == ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL)
        {
            if(protocol == Protocol.DMR)
            {
                int slot = snapshot.timeslot();

                if(slot < 0 || slot > 1)
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

        String systemKey = systemKey(source, identifiers, protocol, configurationId);

        if(systemKey == null)
        {
            return null;
        }

        String prefix = "system:" + systemKey + ':';

        if(target instanceof FullyQualifiedTalkgroupIdentifier fullyQualified)
        {
            return new CallPlaybackTarget(prefix + "talkgroup:home:" + fullyQualified.getWacn() + ':' +
                fullyQualified.getSystem() + ':' + fullyQualified.getTalkgroup(), Kind.TALKGROUP, systemKey, null);
        }
        else if(target instanceof NXDNFullyQualifiedTalkgroupIdentifier fullyQualified)
        {
            return new CallPlaybackTarget(prefix + "talkgroup:home:" + fullyQualified.getSystem() + ':' +
                fullyQualified.getValue(), Kind.TALKGROUP, systemKey, null);
        }
        else if(target instanceof PatchGroupIdentifier patchIdentifier)
        {
            PatchGroup patch = patchIdentifier.getValue();

            if(patch != null && patch.getPatchGroup() != null)
            {
                return new CallPlaybackTarget(prefix + "patch-group:" + patch.getPatchGroup().getValue(),
                    Kind.PATCH_GROUP, systemKey, null);
            }
        }
        else if(target instanceof FullyQualifiedRadioIdentifier fullyQualified)
        {
            return new CallPlaybackTarget(prefix + "radio:home:" + fullyQualified.getWacn() + ':' +
                fullyQualified.getSystem() + ':' + fullyQualified.getRadio(), Kind.RADIO, systemKey, null);
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
                return new CallPlaybackTarget(prefix + kind.keyPart() + ':' + number.intValue(), kind, systemKey,
                    null);
            }
        }

        return new CallPlaybackTarget(prefix + "channel:" + configurationId, Kind.CHANNEL, systemKey, null);
    }

    public Map<String,Object> toMap(String label)
    {
        Map<String,Object> value = new LinkedHashMap<>();
        value.put("key", key);
        value.put("kind", kind.wireName());

        if(systemKey != null)
        {
            value.put("system_key", systemKey);
        }
        if(timeslot != null)
        {
            value.put("timeslot", timeslot + 1);
        }
        if(label != null && !label.isBlank())
        {
            value.put("label", label.strip());
        }

        return Map.copyOf(value);
    }

    private static String systemKey(CallLegSource source, IdentifierCollection identifiers, Protocol protocol,
                                    String configurationId)
    {
        if(protocol == Protocol.APCO25)
        {
            P25SiteIdentity learned = source != null ? source.p25SiteIdentity() : null;
            Integer wacn = learned != null ? learned.wacn() :
                integerIdentifier(identifiers, Form.WACN);
            Integer system = learned != null ? learned.system() :
                integerIdentifier(identifiers, Form.SYSTEM);

            if(wacn != null && system != null)
            {
                return RadioSystemKey.p25(wacn, system);
            }
        }

        return RadioSystemKey.configured(protocol, configurationId);
    }

    private static Integer integerIdentifier(IdentifierCollection identifiers, Form form)
    {
        Identifier<?> identifier = identifiers != null ?
            identifiers.getIdentifier(IdentifierClass.NETWORK, form, Role.BROADCAST) : null;
        return identifier != null && identifier.getValue() instanceof Number number ? number.intValue() : null;
    }

    private static ChannelConfigurationPolicy.ChannelKind inferredChannelKind(IdentifierCollection identifiers,
                                                                               DecoderType decoderType)
    {
        if(identifiers != null && !identifiers.getIdentifiers(Form.TRAFFIC_CHANNEL).isEmpty())
        {
            return ChannelConfigurationPolicy.ChannelKind.TRUNKED;
        }

        return switch(decoderType != null ? decoderType : DecoderType.P25_CONVENTIONAL)
        {
            case P25_PHASE1, P25_PHASE2 -> ChannelConfigurationPolicy.ChannelKind.TRUNKED;
            case AM, NBFM, P25_CONVENTIONAL -> ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL;
            default -> null;
        };
    }

    private static String identifierText(IdentifierCollection identifiers, IdentifierClass identifierClass,
                                         Form form, Role role)
    {
        Identifier<?> identifier = identifiers != null ? identifiers.getIdentifier(identifierClass, form, role) : null;
        return identifier != null && identifier.getValue() != null ? text(identifier.getValue().toString()) : null;
    }

    private static Protocol canonicalProtocol(Protocol protocol)
    {
        return protocol == Protocol.APCO25_PHASE2 ? Protocol.APCO25 :
            protocol != null ? protocol : Protocol.UNKNOWN;
    }

    private static String protocolName(Protocol protocol)
    {
        return switch(canonicalProtocol(protocol))
        {
            case APCO25 -> "p25";
            case DMR -> "dmr";
            case NXDN -> "nxdn";
            case AM -> "am";
            case NBFM -> "nbfm";
            default -> "unknown";
        };
    }

    private static String text(String value)
    {
        return value != null && !value.isBlank() ? value.strip() : null;
    }

    public enum Kind
    {
        CHANNEL("channel", "channel"),
        CHANNEL_TIMESLOT("channel_timeslot", "channel-timeslot"),
        TALKGROUP("talkgroup", "talkgroup"),
        PATCH_GROUP("patch_group", "patch-group"),
        RADIO("radio", "radio");

        private final String mWireName;
        private final String mKeyPart;

        Kind(String wireName, String keyPart)
        {
            mWireName = wireName;
            mKeyPart = keyPart;
        }

        String wireName()
        {
            return mWireName;
        }

        String keyPart()
        {
            return mKeyPart;
        }
    }
}
