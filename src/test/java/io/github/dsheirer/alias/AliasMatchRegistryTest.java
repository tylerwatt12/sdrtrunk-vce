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

import io.github.dsheirer.alias.id.AliasIDType;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.alias.id.status.UnitStatusID;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.tone.TonesID;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.protocol.Protocol;
import java.util.Set;
import org.junit.jupiter.api.Test;

class AliasMatchRegistryTest
{
    @Test
    void protocolFamiliesRejectCrossSystemMatchers()
    {
        AliasListDefinition p25 = definition(AliasListFamily.P25);
        AliasListDefinition dmr = definition(AliasListFamily.DMR);
        AliasListDefinition unowned = new AliasListDefinition("Unowned", null, AliasListFamily.P25);

        assertTrue(AliasMatchRegistry.supports(p25, new Talkgroup(Protocol.APCO25, 1)));
        assertTrue(AliasMatchRegistry.supports(p25, new Talkgroup(Protocol.APCO25_PHASE2, 1)));
        assertFalse(AliasMatchRegistry.supports(p25, new Talkgroup(Protocol.DMR, 1)));
        assertTrue(AliasMatchRegistry.supports(dmr, new Radio(Protocol.DMR, 1)));
        assertFalse(AliasMatchRegistry.supports(dmr, new Radio(Protocol.NXDN, 1)));
        assertFalse(AliasMatchRegistry.supports(unowned, new Talkgroup(Protocol.APCO25, 1)));
    }

    @Test
    void protocolLessStatusUsesOwningFamily()
    {
        AliasListDefinition dmr = definition(AliasListFamily.DMR);
        AliasListDefinition nxdn = definition(AliasListFamily.NXDN);

        assertTrue(AliasMatchRegistry.supports(dmr, new UnitStatusID()));
        assertFalse(AliasMatchRegistry.supports(nxdn, new UnitStatusID()));
    }

    @Test
    void auxiliaryMatchersAreOwnedByPrimaryFamily()
    {
        AliasListDefinition nbfm = definition(AliasListFamily.NBFM);
        Talkgroup fleetsync = new Talkgroup(Protocol.FLEETSYNC, 1);

        assertTrue(AliasMatchRegistry.supports(nbfm, fleetsync));
        assertTrue(AliasMatchRegistry.isChannelCompatible(nbfm, DecoderType.NBFM));
    }

    @Test
    void eachFamilyOffersOneProtocolLessUserStatusChoice()
    {
        AliasListDefinition p25 = definition(AliasListFamily.P25);

        assertEquals(1, AliasMatchRegistry.allowed(p25).stream()
            .filter(descriptor -> descriptor.type() == AliasIDType.STATUS).count());
        assertEquals("User Status", AliasMatchRegistry.allowed(p25).stream()
            .filter(descriptor -> descriptor.type() == AliasIDType.STATUS)
            .findFirst().orElseThrow().label());

        AliasListDefinition nbfm = definition(AliasListFamily.NBFM);
        assertEquals("User Status", AliasMatchRegistry.allowed(nbfm).stream()
            .filter(descriptor -> descriptor.type() == AliasIDType.STATUS)
            .findFirst().orElseThrow().label());
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

    private static AliasListDefinition definition(AliasListFamily family)
    {
        return new AliasListDefinition("Test", "System", family);
    }
}
