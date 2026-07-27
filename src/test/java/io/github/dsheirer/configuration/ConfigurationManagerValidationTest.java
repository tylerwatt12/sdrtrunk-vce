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

package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.openmhz.OpenMHzConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigurationManagerValidationTest
{
    private static final String DUPLICATE_ID = "11111111-2222-4333-8444-555555555555";

    @Test
    void startupValidationRejectsDuplicateChannelIdentitiesWithoutRewritingThem()
    {
        Channel first = new Channel("First");
        Channel second = new Channel("Second");
        first.setConfigurationId(DUPLICATE_ID);
        second.setConfigurationId(DUPLICATE_ID);
        ConfigurationState state = new ConfigurationState();
        state.setChannels(List.of(first, second));

        assertThrows(RuntimeException.class, () -> ConfigurationManager.validateConfigurationIdentities(state));
        assertEquals(DUPLICATE_ID, first.getConfigurationId());
        assertEquals(DUPLICATE_ID, second.getConfigurationId());
    }

    @Test
    void startupValidationRejectsDuplicateProviderIdentitiesWithoutRewritingThem()
    {
        BroadcastConfiguration first = new OpenMHzConfiguration(BroadcastFormat.MP3);
        BroadcastConfiguration second = new OpenMHzConfiguration(BroadcastFormat.MP3);
        first.setConfigurationId(DUPLICATE_ID);
        second.setConfigurationId(DUPLICATE_ID);
        ConfigurationState state = new ConfigurationState();
        state.setBroadcastConfigurations(List.of(first, second));

        assertThrows(RuntimeException.class, () -> ConfigurationManager.validateConfigurationIdentities(state));
        assertEquals(DUPLICATE_ID, first.getConfigurationId());
        assertEquals(DUPLICATE_ID, second.getConfigurationId());
    }

    @Test
    void startupValidationRejectsCrossSystemAndCrossFamilyAliasListAssignments()
    {
        AliasListDefinition definition =
            new AliasListDefinition("County", "System A", AliasListFamily.P25);
        Channel wrongSystem = new Channel("Wrong System");
        wrongSystem.setSystem("System B");
        wrongSystem.setAliasListName("County");
        wrongSystem.setDecodeConfiguration(new DecodeConfigP25Phase1());
        ConfigurationState wrongSystemState = new ConfigurationState();
        wrongSystemState.setChannels(List.of(wrongSystem));

        assertThrows(RuntimeException.class, () ->
            ConfigurationManager.validateAliasListAssignments(wrongSystemState, List.of(definition)));

        Channel wrongFamily = new Channel("Wrong Family");
        wrongFamily.setSystem("System A");
        wrongFamily.setAliasListName("County");
        wrongFamily.setDecodeConfiguration(new DecodeConfigDMR());
        ConfigurationState wrongFamilyState = new ConfigurationState();
        wrongFamilyState.setChannels(List.of(wrongFamily));

        assertThrows(RuntimeException.class, () ->
            ConfigurationManager.validateAliasListAssignments(wrongFamilyState, List.of(definition)));
    }

    @Test
    void startupValidationAcceptsExactSystemAndFamilyAliasListAssignment()
    {
        AliasListDefinition definition =
            new AliasListDefinition("County", "System A", AliasListFamily.P25);
        Channel channel = new Channel("Control");
        channel.setSystem("System A");
        channel.setAliasListName("County");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        ConfigurationState state = new ConfigurationState();
        state.setChannels(List.of(channel));

        assertDoesNotThrow(() ->
            ConfigurationManager.validateAliasListAssignments(state, List.of(definition)));
    }

    @Test
    void startupValidationRejectsNoncanonicalAliasListName()
    {
        AliasListDefinition definition =
            new AliasListDefinition("County", "System A", AliasListFamily.P25);
        Channel channel = new Channel("Control");
        channel.setSystem("System A");
        channel.setAliasListName("county");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        ConfigurationState state = new ConfigurationState();
        state.setChannels(List.of(channel));

        assertThrows(RuntimeException.class, () ->
            ConfigurationManager.validateAliasListAssignments(state, List.of(definition)));
    }
}
