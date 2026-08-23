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

package io.github.dsheirer.gui.configuration.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.DecoderFactory;
import io.github.dsheirer.module.decode.DecoderType;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChannelConfigurationEditorTest
{
    @Test
    void p25ConventionalCanSelectAnyP25ListWithoutSystemOwnership()
    {
        AliasListDefinition p25 = new AliasListDefinition("Shared P25", AliasListFamily.P25);

        assertTrue(ChannelConfigurationEditor.isAliasListCompatible(p25, DecoderType.P25_CONVENTIONAL));
        assertTrue(ChannelConfigurationEditor.isAliasListCompatible(p25, DecoderType.P25_PHASE1));
        assertTrue(ChannelConfigurationEditor.isAliasListCompatible(p25, DecoderType.P25_PHASE2));
        assertFalse(ChannelConfigurationEditor.isAliasListCompatible(p25, DecoderType.DMR));
        assertFalse(ChannelConfigurationEditor.isAliasListCompatible(p25, DecoderType.NXDN));
    }

    @Test
    void manualNewChannelsSelectTheCompatibleFactoryAliasList()
    {
        AliasModel model = factoryAliasModel();

        assertDefault(model, DecoderType.P25_CONVENTIONAL, "Default P25");
        assertDefault(model, DecoderType.P25_PHASE1, "Default P25");
        assertDefault(model, DecoderType.P25_PHASE2, "Default P25");
        assertDefault(model, DecoderType.DMR, "Default DMR");
        assertDefault(model, DecoderType.NXDN, "Default NXDN");
        assertDefault(model, DecoderType.AM, "Default NBFM");
        assertDefault(model, DecoderType.NBFM, "Default NBFM");
    }

    @Test
    void defaultResolverRejectsANameOwnedByTheWrongFamily()
    {
        AliasListDefinition collision = new AliasListDefinition("Default P25", AliasListFamily.DMR);
        collision.setId(1);
        AliasModel model = new AliasModel();
        model.replaceCommittedConfiguration(List.of(collision), List.of());

        assertNull(model.getDefaultAliasListDefinition(DecoderType.P25_PHASE1));
        Channel channel = channel(DecoderType.P25_PHASE1);
        assertFalse(model.assignDefaultAliasList(channel));
        assertNull(channel.getAliasListName());
    }

    private static void assertDefault(AliasModel model, DecoderType decoderType, String expected)
    {
        Channel channel = channel(decoderType);
        assertTrue(model.assignDefaultAliasList(channel));
        assertEquals(expected, channel.getAliasListName());
        assertEquals(expected, model.getDefaultAliasListDefinition(decoderType).getName());
    }

    private static Channel channel(DecoderType decoderType)
    {
        Channel channel = new Channel();
        channel.setDecodeConfiguration(DecoderFactory.getDecodeConfiguration(decoderType));
        return channel;
    }

    private static AliasModel factoryAliasModel()
    {
        AliasModel model = new AliasModel();
        long id = 1;
        List<AliasListDefinition> definitions = new ArrayList<>();
        for(AliasListFamily family: AliasListFamily.values())
        {
            AliasListDefinition definition = new AliasListDefinition(family.getDefaultAliasListName(), family);
            definition.setId(id++);
            definitions.add(definition);
        }
        model.replaceCommittedConfiguration(definitions, List.of());
        return model;
    }
}
