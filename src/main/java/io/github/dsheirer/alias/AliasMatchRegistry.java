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

package io.github.dsheirer.alias;

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.esn.Esn;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioFormat;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
import io.github.dsheirer.alias.id.talkgroup.P25FullyQualifiedTalkgroup;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupFormat;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.protocol.Protocol;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Central registry for matcher creation and alias-list capability validation.
 */
public final class AliasMatchRegistry
{
    private static final List<AliasMatchDescriptor> DESCRIPTORS = createDescriptors();

    private AliasMatchRegistry()
    {
    }

    public static List<AliasMatchDescriptor> allowed(AliasListDefinition definition)
    {
        if(definition == null)
        {
            return List.of();
        }

        List<AliasMatchDescriptor> allowed = new ArrayList<>();

        for(AliasMatchDescriptor descriptor: DESCRIPTORS)
        {
            if(descriptor.supports(definition))
            {
                allowed.add(descriptor);
            }
        }

        return List.copyOf(allowed);
    }

    public static boolean supports(AliasListDefinition definition, AliasID identifier)
    {
        return definition != null && identifier != null &&
            DESCRIPTORS.stream().anyMatch(descriptor ->
                descriptor.supports(definition) && descriptor.matches(identifier));
    }

    /**
     * Indicates that a matcher can be activated in the specified list without conversion or repair.
     */
    public static boolean isOperational(AliasListDefinition definition, AliasID identifier)
    {
        return identifier != null && identifier.isValid() && supports(definition, identifier);
    }

    /**
     * Indicates that an identifier is the retired full-domain range convention for unmatched talkgroups.
     *
     * <p>These ranges remain readable so ambiguous legacy imports are never discarded. New aliases must use the
     * list-owned {@link UnmatchedTalkgroupPolicy} instead, which keeps each received talkgroup as its real identity
     * while supplying only playback, recording, and streaming behavior.</p>
     */
    public static boolean isUnmatchedTalkgroupCatchAll(AliasListDefinition definition, AliasID identifier)
    {
        if(definition == null || definition.getFamily() == null ||
            !(identifier instanceof TalkgroupRange range))
        {
            return false;
        }

        boolean startsAtBeginning = range.getMinTalkgroup() == 0 || range.getMinTalkgroup() == 1;

        return startsAtBeginning && switch(definition.getFamily())
        {
            case P25 -> protocolsMatch(Protocol.APCO25, range.getProtocol()) &&
                range.getMaxTalkgroup() == 0xFFFF;
            case DMR -> range.getProtocol() == Protocol.DMR && range.getMaxTalkgroup() == 0xFFFFFF;
            case NXDN -> range.getProtocol() == Protocol.NXDN && range.getMaxTalkgroup() == 0xFFFF;
            case NBFM -> false;
        };
    }

    public static AliasListFamily familyFor(DecoderType decoderType)
    {
        return AliasListFamily.from(decoderType);
    }

    /**
     * Tests whether a channel can consume every matcher capability declared by the list.
     */
    public static boolean isChannelCompatible(AliasListDefinition definition, DecoderType primaryDecoder)
    {
        return definition != null && familyFor(primaryDecoder) == definition.getFamily();
    }

    private static Protocol protocol(AliasID identifier)
    {
        return switch(identifier)
        {
            case Talkgroup talkgroup -> talkgroup.getProtocol();
            case TalkgroupRange range -> range.getProtocol();
            case Radio radio -> radio.getProtocol();
            case RadioRange range -> range.getProtocol();
            default -> null;
        };
    }

    private static List<AliasMatchDescriptor> createDescriptors()
    {
        List<AliasMatchDescriptor> descriptors = new ArrayList<>();

        addProtocolMatchers(descriptors, AliasListFamily.P25, Protocol.APCO25, "P25");
        addProtocolMatchers(descriptors, AliasListFamily.DMR, Protocol.DMR, "DMR");
        addProtocolMatchers(descriptors, AliasListFamily.NXDN, Protocol.NXDN, "NXDN");
        addTalkgroupMatchers(descriptors, AliasListFamily.NBFM, Protocol.NBFM, "NBFM");

        descriptors.add(descriptor("Tone Sequence", AliasIDType.TONES,
            EnumSet.of(AliasListFamily.P25, AliasListFamily.DMR),
            _ -> new TonesID(),
            TonesID.class::isInstance));
        descriptors.add(descriptor("Digital Coded Squelch (DCS)", AliasIDType.DCS,
            Set.of(AliasListFamily.NBFM), _ -> new Dcs(), Dcs.class::isInstance));
        addTalkgroupMatchers(descriptors, AliasListFamily.NBFM, Protocol.FLEETSYNC, "Fleetsync");
        addTalkgroupMatchers(descriptors, AliasListFamily.NBFM, Protocol.MDC1200, "MDC-1200");
        descriptors.add(descriptor("LoJack Transponder ESN", AliasIDType.ESN,
            Set.of(AliasListFamily.NBFM), _ -> new Esn(), Esn.class::isInstance));
        descriptors.add(descriptor("User Status", AliasIDType.STATUS,
            EnumSet.of(AliasListFamily.P25, AliasListFamily.NBFM), _ -> new UserStatusID(),
            UserStatusID.class::isInstance));
        descriptors.add(descriptor("Unit Status", AliasIDType.UNIT_STATUS,
            EnumSet.of(AliasListFamily.P25, AliasListFamily.DMR),
            _ -> new UnitStatusID(), UnitStatusID.class::isInstance));

        return List.copyOf(descriptors);
    }

    private static void addProtocolMatchers(List<AliasMatchDescriptor> descriptors, AliasListFamily family,
                                            Protocol protocol, String label)
    {
        addTalkgroupMatchers(descriptors, family, protocol, label);
        descriptors.add(protocolDescriptor(label + " Radio ID", AliasIDType.RADIO_ID, protocol,
            Set.of(family), _ -> new Radio(protocol, RadioFormat.get(protocol).getMinimumValidValue()),
            Radio.class::isInstance));
        descriptors.add(protocolDescriptor(label + " Radio ID Range",
            AliasIDType.RADIO_ID_RANGE, protocol, Set.of(family),
            _ -> new RadioRange(protocol, RadioFormat.get(protocol).getMinimumValidValue(),
                RadioFormat.get(protocol).getMinimumValidValue() + 1), RadioRange.class::isInstance));
    }

    private static void addTalkgroupMatchers(List<AliasMatchDescriptor> descriptors, AliasListFamily family,
                                             Protocol protocol, String label)
    {
        addTalkgroupMatchers(descriptors, Set.of(family), protocol, label);
    }

    private static void addTalkgroupMatchers(List<AliasMatchDescriptor> descriptors,
                                             Set<AliasListFamily> families, Protocol protocol, String label)
    {
        descriptors.add(protocolDescriptor(label + " Talkgroup", AliasIDType.TALKGROUP,
            protocol, families,
            _ -> new Talkgroup(protocol, TalkgroupFormat.get(protocol).getMinimumValidValue()),
            identifier -> identifier instanceof Talkgroup &&
                !(identifier instanceof P25FullyQualifiedTalkgroup)));
        descriptors.add(protocolDescriptor(label + " Talkgroup Range",
            AliasIDType.TALKGROUP_RANGE, protocol, families,
            _ -> new TalkgroupRange(protocol, TalkgroupFormat.get(protocol).getMinimumValidValue(),
                TalkgroupFormat.get(protocol).getMinimumValidValue() + 1), TalkgroupRange.class::isInstance));
    }

    private static AliasMatchDescriptor protocolDescriptor(String label, AliasIDType type,
                                                           Protocol protocol, Set<AliasListFamily> families,
                                                           Function<AliasListDefinition,AliasID> factory,
                                                           Predicate<AliasID> classMatcher)
    {
        return descriptor(label, type, families,
            factory, identifier -> classMatcher.test(identifier) && protocolsMatch(protocol, protocol(identifier)));
    }

    private static AliasMatchDescriptor descriptor(String label, AliasIDType type,
                                                   Set<AliasListFamily> families,
                                                   Function<AliasListDefinition,AliasID> factory,
                                                   Predicate<AliasID> matcher)
    {
        return new AliasMatchDescriptor(label, type, families, factory, matcher);
    }

    private static boolean protocolsMatch(Protocol expected, Protocol actual)
    {
        if(expected == Protocol.APCO25)
        {
            return actual == Protocol.APCO25 || actual == Protocol.APCO25_PHASE2;
        }

        return expected == actual;
    }
}
