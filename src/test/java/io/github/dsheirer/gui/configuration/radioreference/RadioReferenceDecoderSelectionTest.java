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
import io.github.dsheirer.module.decode.am.DecodeConfigAM;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNChannelMode;
import io.github.dsheirer.module.decode.nxdn.layer3.type.TransmissionMode;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.rrapi.type.CountyInfo;
import io.github.dsheirer.rrapi.type.Flavor;
import io.github.dsheirer.rrapi.type.Mode;
import io.github.dsheirer.rrapi.type.Site;
import io.github.dsheirer.rrapi.type.System;
import io.github.dsheirer.rrapi.type.Talkgroup;
import io.github.dsheirer.rrapi.type.Type;
import io.github.dsheirer.rrapi.type.Voice;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RadioReferenceDecoderSelectionTest
{
    @Test
    void createsAmAgencyFrequencyChannels()
    {
        Mode mode = new Mode();
        mode.setName("am");
        ModeDecoderType modeDecoderType = ModeDecoderType.get(mode);

        assertEquals(ModeDecoderType.AM, modeDecoderType);
        Channel channel = FrequencyEditor.createChannel(modeDecoderType, 118_500_000L,
            "Aviation", "Airport", "Tower");
        DecodeConfigAM config = assertInstanceOf(DecodeConfigAM.class, channel.getDecodeConfiguration());
        assertEquals(DecoderType.AM, modeDecoderType.getDecoderType());
        assertEquals(DecodeConfigAM.Bandwidth.BW_15_0, config.getBandwidth());
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
    void mapsRadioReferenceCqpskSiteModulationToLsm()
    {
        Site site = new Site();
        site.setModulation("CQPSK Phase 1");

        assertEquals(Modulation.CQPSK, SiteEditor.getP25Phase1Modulation(site));
    }

    @Test
    void simulcastSiteNameTakesPrecedenceOverStructuredC4fmModulation()
    {
        Site site = new Site();
        site.setDescription("Lorain County Simulcast");
        site.setModulation("C4FM, H-DQPSK");

        assertEquals(Modulation.CQPSK, SiteEditor.getP25Phase1Modulation(site));
    }

    @Test
    void simulcastSiteNameMatchIsCaseInsensitive()
    {
        Site site = new Site();
        site.setDescription("county SIMULCAST layer");
        site.setModulation("C4FM");

        assertEquals(Modulation.CQPSK, SiteEditor.getP25Phase1Modulation(site));
    }

    @Test
    void fallsBackToSimulcastSiteDescriptionWhenModulationIsMissing()
    {
        Site site = new Site();
        site.setDescription("Cuyahoga Co Simulcast");

        assertEquals(Modulation.CQPSK, SiteEditor.getP25Phase1Modulation(site));
    }

    @Test
    void defaultsP25Phase1ImportToC4fmWhenRadioReferenceHasNoIndicator()
    {
        assertEquals(Modulation.C4FM, SiteEditor.getP25Phase1Modulation(new Site()));
        assertEquals(Modulation.C4FM, SiteEditor.getP25Phase1Modulation(null));
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
    void createsConventionalNxdnAgencyFrequenciesWithTheRadioReferenceMode()
    {
        assertConventionalNxdn("nxdn", ModeDecoderType.NXDN, TransmissionMode.M4800);
        assertConventionalNxdn("nxdn48", ModeDecoderType.NXDN48, TransmissionMode.M4800);
        assertConventionalNxdn("nxdn96", ModeDecoderType.NXDN96, TransmissionMode.M9600);
    }

    @Test
    void mapsTrunkedNxdnFlavorsAndKeepsTrunkedChannelMode()
    {
        assertTrunkedNxdn("NEXEDGE 9600", TransmissionMode.M9600);
        assertTrunkedNxdn("Conventional Networked", TransmissionMode.M9600);
        assertTrunkedNxdn("Icom IDAS Type D", TransmissionMode.TYPE_D);
        assertTrunkedNxdn("Kenwood Type D", TransmissionMode.TYPE_D);
        assertTrunkedNxdn("Narrowband Networked", TransmissionMode.M4800);
        assertTrunkedNxdn("NEXEDGE 4800", TransmissionMode.M4800);
        assertTrunkedNxdn("Icom IDAS Type C", TransmissionMode.M4800);
        assertTrunkedNxdn(null, TransmissionMode.M4800);
    }

    @Test
    void usesRadioReferenceSiteDescriptionInsteadOfCountyName()
    {
        Site site = new Site();
        site.setDescription("Medina Simulcast");
        CountyInfo countyInfo = new CountyInfo();
        countyInfo.setName("Medina County");

        EnrichedSite enrichedSite = new EnrichedSite(site, countyInfo);

        assertEquals("Medina Simulcast", SiteEditor.getSiteLabel(enrichedSite));
    }

    @Test
    void fallsBackToCountyWhenRadioReferenceSiteDescriptionIsMissing()
    {
        Site site = new Site();
        CountyInfo countyInfo = new CountyInfo();
        countyInfo.setName("Medina County");

        EnrichedSite enrichedSite = new EnrichedSite(site, countyInfo);

        assertEquals("Medina County", SiteEditor.getSiteLabel(enrichedSite));
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

    private static void assertConventionalNxdn(String modeName, ModeDecoderType expectedDecoderType,
                                                TransmissionMode expectedTransmissionMode)
    {
        Mode mode = new Mode();
        mode.setName(modeName);
        ModeDecoderType modeDecoderType = ModeDecoderType.get(mode);

        Channel channel = FrequencyEditor.createChannel(modeDecoderType, 451_012_500L,
            "Public Safety", "Public Works", "Repeater");

        DecodeConfigNXDN configuration = assertInstanceOf(DecodeConfigNXDN.class,
            channel.getDecodeConfiguration());
        assertEquals(expectedDecoderType, modeDecoderType);
        assertEquals(NXDNChannelMode.CONVENTIONAL, configuration.getChannelMode());
        assertEquals(expectedTransmissionMode, configuration.getTransmissionMode());
    }

    private static void assertTrunkedNxdn(String flavorName, TransmissionMode expectedTransmissionMode)
    {
        Flavor flavor = null;

        if(flavorName != null)
        {
            flavor = new Flavor();
            flavor.setName(flavorName);
        }

        DecodeConfigNXDN configuration = SiteEditor.createNXDNDecodeConfiguration(flavor, List.of());

        assertEquals(NXDNChannelMode.TRUNKED, configuration.getChannelMode());
        assertEquals(expectedTransmissionMode, configuration.getTransmissionMode());
        assertEquals(List.of(), configuration.getChannelMap());
    }
}
