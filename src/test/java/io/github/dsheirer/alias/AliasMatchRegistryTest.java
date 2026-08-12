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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.id.AliasID;
import io.github.dsheirer.alias.id.dcs.Dcs;
import io.github.dsheirer.alias.id.esn.Esn;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.radio.RadioRange;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.status.UserStatusID;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.protocol.Protocol;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class AliasMatchRegistryTest
{
    @Test
    void protocolFamiliesRejectCrossFamilyMatchers()
    {
        AliasListDefinition p25 = definition(AliasListFamily.P25);
        AliasListDefinition dmr = definition(AliasListFamily.DMR);

        assertTrue(AliasMatchRegistry.supports(p25, new Talkgroup(Protocol.APCO25, 1)));
        assertTrue(AliasMatchRegistry.supports(p25, new Talkgroup(Protocol.APCO25_PHASE2, 1)));
        assertFalse(AliasMatchRegistry.supports(p25, new Talkgroup(Protocol.DMR, 1)));
        assertTrue(AliasMatchRegistry.supports(dmr, new Radio(Protocol.DMR, 1)));
        assertFalse(AliasMatchRegistry.supports(dmr, new Radio(Protocol.NXDN, 1)));
        assertTrue(AliasMatchRegistry.isChannelCompatible(p25, DecoderType.P25_PHASE1));
        assertFalse(AliasMatchRegistry.isChannelCompatible(p25, DecoderType.DMR));
    }

    @Test
    void protocolLessStatusUsesOwningFamily()
    {
        AliasListDefinition p25 = definition(AliasListFamily.P25);
        AliasListDefinition dmr = definition(AliasListFamily.DMR);
        AliasListDefinition nxdn = definition(AliasListFamily.NXDN);
        AliasListDefinition nbfm = definition(AliasListFamily.NBFM);

        assertTrue(AliasMatchRegistry.supports(p25, new UnitStatusID()));
        assertTrue(AliasMatchRegistry.supports(dmr, new UnitStatusID()));
        assertFalse(AliasMatchRegistry.supports(nxdn, new UnitStatusID()));
        assertFalse(AliasMatchRegistry.supports(nbfm, new UnitStatusID()));

        assertTrue(AliasMatchRegistry.supports(p25, new UserStatusID()));
        assertFalse(AliasMatchRegistry.supports(dmr, new UserStatusID()));
        assertFalse(AliasMatchRegistry.supports(nxdn, new UserStatusID()));
        assertTrue(AliasMatchRegistry.supports(nbfm, new UserStatusID()));
    }

    @Test
    void auxiliaryMatchersAreOwnedByPrimaryFamily()
    {
        AliasListDefinition p25 = definition(AliasListFamily.P25);
        AliasListDefinition dmr = definition(AliasListFamily.DMR);
        AliasListDefinition nxdn = definition(AliasListFamily.NXDN);
        AliasListDefinition nbfm = definition(AliasListFamily.NBFM);
        Talkgroup fleetsync = new Talkgroup(Protocol.FLEETSYNC, 1);
        Talkgroup mdc1200 = new Talkgroup(Protocol.MDC1200, 1);

        assertTrue(AliasMatchRegistry.supports(nbfm, fleetsync));
        assertTrue(AliasMatchRegistry.supports(nbfm, mdc1200));
        assertTrue(AliasMatchRegistry.supports(nbfm, new Talkgroup(Protocol.AM, 1)));
        assertTrue(AliasMatchRegistry.supports(nbfm, new Dcs()));
        assertTrue(AliasMatchRegistry.supports(nbfm, new Esn()));

        for(AliasListDefinition trunked: Set.of(p25, dmr, nxdn))
        {
            assertFalse(AliasMatchRegistry.supports(trunked, fleetsync));
            assertFalse(AliasMatchRegistry.supports(trunked, mdc1200));
            assertFalse(AliasMatchRegistry.supports(trunked, new Dcs()));
            assertFalse(AliasMatchRegistry.supports(trunked, new Esn()));
        }

        assertTrue(AliasMatchRegistry.isChannelCompatible(nbfm, DecoderType.NBFM));
        assertTrue(AliasMatchRegistry.isChannelCompatible(nbfm, DecoderType.AM));
    }

    @Test
    void eachFamilyOffersOnlyItsSupportedMatchers()
    {
        assertEquals(Set.of("P25 Talkgroup", "P25 Talkgroup Range", "P25 Radio ID", "P25 Radio ID Range",
                "P25 Fully Qualified Talkgroup", "P25 Fully Qualified Radio ID", "Tone Sequence", "User Status",
                "Unit Status"),
            labels(AliasListFamily.P25));
        assertEquals(Set.of("DMR Talkgroup", "DMR Talkgroup Range", "DMR Radio ID", "DMR Radio ID Range",
                "Tone Sequence", "Unit Status"),
            labels(AliasListFamily.DMR));
        assertEquals(Set.of("NXDN Talkgroup", "NXDN Talkgroup Range", "NXDN Radio ID", "NXDN Radio ID Range"),
            labels(AliasListFamily.NXDN));
        assertEquals(Set.of("AM Talkgroup", "AM Talkgroup Range", "NBFM Talkgroup", "NBFM Talkgroup Range",
                "Digital Coded Squelch (DCS)",
                "Fleetsync Talkgroup", "Fleetsync Talkgroup Range", "MDC-1200 Talkgroup",
                "MDC-1200 Talkgroup Range", "LoJack Transponder ESN", "User Status"),
            labels(AliasListFamily.NBFM));
    }

    @Test
    void tonesAreOfferedOnlyByDecodersThatEmitToneIdentifiers()
    {
        TonesID tones = new TonesID();

        assertTrue(AliasMatchRegistry.supports(definition(AliasListFamily.P25), tones));
        assertTrue(AliasMatchRegistry.supports(definition(AliasListFamily.DMR), tones));
        assertFalse(AliasMatchRegistry.supports(definition(AliasListFamily.NBFM), tones));
    }

    @Test
    void eachActiveFamilyHasAValidDefaultForNewAndRepairedAliases()
    {
        for(AliasListFamily family: Set.of(AliasListFamily.P25, AliasListFamily.DMR, AliasListFamily.NXDN,
            AliasListFamily.NBFM))
        {
            AliasListDefinition definition = definition(family);
            var matcher = AliasMatchRegistry.allowed(definition).getFirst().create(definition);
            assertTrue(AliasMatchRegistry.isOperational(definition, matcher),
                () -> family + " default matcher must be immediately persistable");
        }
    }

    @Test
    void nxdnFactoriesStartAtTheFirstValidIdentifier()
    {
        AliasListDefinition definition = definition(AliasListFamily.NXDN);
        Talkgroup talkgroup = (Talkgroup)create(definition, "NXDN Talkgroup");
        TalkgroupRange talkgroupRange = (TalkgroupRange)create(definition, "NXDN Talkgroup Range");
        Radio radio = (Radio)create(definition, "NXDN Radio ID");
        RadioRange radioRange = (RadioRange)create(definition, "NXDN Radio ID Range");

        assertEquals(1, talkgroup.getValue());
        assertEquals(1, talkgroupRange.getMinTalkgroup());
        assertEquals(2, talkgroupRange.getMaxTalkgroup());
        assertEquals(1, radio.getValue());
        assertEquals(1, radioRange.getMinRadio());
        assertEquals(2, radioRange.getMaxRadio());
        assertTrue(talkgroup.isValid());
        assertTrue(talkgroupRange.isValid());
        assertTrue(radio.isValid());
        assertTrue(radioRange.isValid());
    }

    private static AliasListDefinition definition(AliasListFamily family)
    {
        return new AliasListDefinition("Test", family);
    }

    private static Set<String> labels(AliasListFamily family)
    {
        return AliasMatchRegistry.allowed(definition(family)).stream()
            .map(AliasMatchDescriptor::label)
            .collect(Collectors.toSet());
    }

    private static AliasID create(AliasListDefinition definition, String label)
    {
        return AliasMatchRegistry.allowed(definition).stream()
            .filter(descriptor -> label.equals(descriptor.label()))
            .findFirst()
            .orElseThrow()
            .create(definition);
    }
}
