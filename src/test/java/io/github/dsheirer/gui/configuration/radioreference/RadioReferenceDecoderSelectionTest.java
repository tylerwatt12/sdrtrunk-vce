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

package io.github.dsheirer.gui.configuration.radioreference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.rrapi.type.Flavor;
import io.github.dsheirer.rrapi.type.Mode;
import io.github.dsheirer.rrapi.type.System;
import io.github.dsheirer.rrapi.type.Talkgroup;
import io.github.dsheirer.rrapi.type.Type;
import io.github.dsheirer.rrapi.type.Voice;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RadioReferenceDecoderSelectionTest
{
    @Test
    void recognizesAmButDoesNotOfferChannelCreation()
    {
        Mode mode = new Mode();
        mode.setName("am");
        ModeDecoderType modeDecoderType = ModeDecoderType.get(mode);

        assertEquals(ModeDecoderType.AM, modeDecoderType);
        assertFalse(modeDecoderType.hasDecoderType());
        assertNull(FrequencyEditor.createChannel(modeDecoderType, 118_500_000L,
            "Aviation", "Airport", "Tower"));
    }

    @Test
    void createsConventionalDecoderForP25AgencyFrequency()
    {
        Mode mode = new Mode();
        mode.setName("p25");
        ModeDecoderType modeDecoderType = ModeDecoderType.get(mode);

        Channel channel = FrequencyEditor.createChannel(modeDecoderType, 154_755_000L, "Public Safety",
            "Law Enforcement", "AMHST PDISP");

        assertEquals(DecoderType.P25_CONVENTIONAL, modeDecoderType.getDecoderType());
        assertInstanceOf(DecodeConfigP25Conventional.class, channel.getDecodeConfiguration());
        SourceConfigTuner source = assertInstanceOf(SourceConfigTuner.class, channel.getSourceConfiguration());
        assertEquals(154_755_000L, source.getFrequency());
        assertEquals("Public Safety", channel.getSystem());
        assertEquals("Law Enforcement", channel.getSite());
        assertEquals("AMHST PDISP", channel.getName());
    }

    @Test
    void keepsPhaseSpecificDecodersForTrunkedP25Systems()
    {
        assertEquals(DecoderType.P25_PHASE1, trunkedDecoderType("Phase I"));
        assertEquals(DecoderType.P25_PHASE2, trunkedDecoderType("Phase II"));
    }

    @Test
    void createsConventionalDmrForAgencyFrequency()
    {
        Mode mode = new Mode();
        mode.setName("dmr");

        Channel channel = FrequencyEditor.createChannel(ModeDecoderType.get(mode), 451_012_500L,
            "Public Safety", "Public Works", "Repeater");

        DecodeConfigDMR configuration =
            assertInstanceOf(DecodeConfigDMR.class, channel.getDecodeConfiguration());
        assertEquals(DMRChannelMode.CONVENTIONAL, configuration.getChannelMode());
    }

    @Test
    void treatsMptSystemsAsUnsupported()
    {
        Type type = new Type();
        type.setTypeId(1);
        type.setName("MPT-1327");
        Flavor flavor = new Flavor();
        flavor.setFlavorId(2);
        flavor.setName("Standard");
        Voice voice = new Voice();
        voice.setVoiceId(3);
        voice.setName("Analog");
        System system = new System();
        system.setTypeId(type.getTypeId());
        system.setFlavorId(flavor.getFlavorId());
        system.setVoiceId(voice.getVoiceId());

        RadioReferenceDecoder decoder = new RadioReferenceDecoder(null, Map.of(type.getTypeId(), type),
            Map.of(flavor.getFlavorId(), flavor), Map.of(voice.getVoiceId(), voice), Map.of());

        assertNull(decoder.getDecoderType(system));
        assertEquals(Protocol.UNKNOWN, decoder.getProtocol(system));
    }

    @Test
    void treatsAllLtrFamiliesAsUnsupported()
    {
        for(String flavorName: new String[]{"Standard", "Net", "Passport"})
        {
            Type type = new Type();
            type.setTypeId(1);
            type.setName("LTR");
            Flavor flavor = new Flavor();
            flavor.setFlavorId(2);
            flavor.setName(flavorName);
            Voice voice = new Voice();
            voice.setVoiceId(3);
            voice.setName("Analog");
            System system = new System();
            system.setTypeId(type.getTypeId());
            system.setFlavorId(flavor.getFlavorId());
            system.setVoiceId(voice.getVoiceId());

            RadioReferenceDecoder decoder = new RadioReferenceDecoder(null, Map.of(type.getTypeId(), type),
                Map.of(flavor.getFlavorId(), flavor), Map.of(voice.getVoiceId(), voice), Map.of());

            assertNull(decoder.getDecoderType(system), flavorName);
            assertEquals(Protocol.UNKNOWN, decoder.getProtocol(system), flavorName);
            assertFalse(decoder.hasSupportedProtocol(system), flavorName);
        }
    }

    @Test
    void copiesRadioReferenceDescriptionIntoAlias()
    {
        Type type = new Type();
        type.setTypeId(1);
        type.setName("Project 25");
        Flavor flavor = new Flavor();
        flavor.setFlavorId(2);
        flavor.setName("Phase II");
        Voice voice = new Voice();
        voice.setVoiceId(3);
        voice.setName("Digital");
        System system = new System();
        system.setTypeId(type.getTypeId());
        system.setFlavorId(flavor.getFlavorId());
        system.setVoiceId(voice.getVoiceId());
        Talkgroup talkgroup = new Talkgroup();
        talkgroup.setDecimalValue(13501);
        talkgroup.setAlphaTag("LORAIN DISP");
        talkgroup.setDescription("Lorain County dispatch");

        RadioReferenceDecoder decoder = new RadioReferenceDecoder(null, Map.of(type.getTypeId(), type),
            Map.of(flavor.getFlavorId(), flavor), Map.of(voice.getVoiceId(), voice), Map.of());
        AliasListDefinition definition =
            new AliasListDefinition("Ohio MARCS-IP", AliasListFamily.P25);
        definition.setId(42);
        Alias alias = decoder.createAlias(talkgroup, system, definition, "Law Dispatch");

        assertEquals("LORAIN DISP", alias.getName());
        assertEquals("Lorain County dispatch", alias.getDescription());
        assertEquals(42, alias.getAliasListId());
        assertEquals("Ohio MARCS-IP", alias.getAliasListName());
        assertInstanceOf(io.github.dsheirer.alias.id.talkgroup.Talkgroup.class,
            alias.getMatchIdentifier());
        assertEquals("Law Dispatch", alias.getGroup());
    }

    private static DecoderType trunkedDecoderType(String flavorName)
    {
        Type type = new Type();
        type.setTypeId(1);
        type.setName("Project 25");

        Flavor flavor = new Flavor();
        flavor.setFlavorId(2);
        flavor.setName(flavorName);

        Voice voice = new Voice();
        voice.setVoiceId(3);
        voice.setName("Digital");

        System system = new System();
        system.setTypeId(type.getTypeId());
        system.setFlavorId(flavor.getFlavorId());
        system.setVoiceId(voice.getVoiceId());

        RadioReferenceDecoder decoder = new RadioReferenceDecoder(null, Map.of(type.getTypeId(), type),
            Map.of(flavor.getFlavorId(), flavor), Map.of(voice.getVoiceId(), voice), Map.of());
        return decoder.getDecoderType(system);
    }
}
