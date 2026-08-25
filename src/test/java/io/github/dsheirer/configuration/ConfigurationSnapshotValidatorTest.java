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
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;

class ConfigurationSnapshotValidatorTest
{
    private static final String DUPLICATE_ID = "11111111-2222-4333-8444-555555555555";

    @Test
    void startupRejectsDuplicateChannelIdentitiesWithoutRewritingThem()
    {
        Channel first = new Channel("First");
        Channel second = new Channel("Second");
        first.setConfigurationId(DUPLICATE_ID);
        second.setConfigurationId(DUPLICATE_ID);
        ConfigurationSnapshot state = snapshot(List.of(), List.of(first, second), List.of());

        assertThrows(RuntimeException.class, () -> ConfigurationSnapshotValidator.validateForStartup(state));
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
        ConfigurationSnapshot state = snapshot(List.of(), List.of(), List.of(first, second));

        assertThrows(RuntimeException.class, () -> ConfigurationSnapshotValidator.validateForStartup(state));
        assertEquals(DUPLICATE_ID, first.getConfigurationId());
        assertEquals(DUPLICATE_ID, second.getConfigurationId());
    }

    @Test
    void startupValidationAcceptsSameFamilyAcrossSystemsAndRejectsCrossFamilyAssignment()
    {
        AliasListDefinition definition =
            new AliasListDefinition("County", AliasListFamily.P25);
        Channel differentSystem = new Channel("Different System");
        differentSystem.setSystem("System B");
        differentSystem.setAliasListName("County");
        differentSystem.setDecodeConfiguration(new DecodeConfigP25Phase1());
        ConfigurationSnapshot differentSystemState =
            snapshot(List.of(definition), List.of(differentSystem), List.of());

        assertDoesNotThrow(() ->
            ConfigurationSnapshotValidator.validateForWrite(differentSystemState));

        Channel wrongFamily = new Channel("Wrong Family");
        wrongFamily.setSystem("System A");
        wrongFamily.setAliasListName("County");
        wrongFamily.setDecodeConfiguration(new DecodeConfigDMR());
        ConfigurationSnapshot wrongFamilyState = snapshot(List.of(definition), List.of(wrongFamily), List.of());

        assertThrows(RuntimeException.class, () ->
            ConfigurationSnapshotValidator.validateForWrite(wrongFamilyState));
    }

    @Test
    void startupValidationAcceptsMatchingFamilyAliasListAssignment()
    {
        AliasListDefinition definition =
            new AliasListDefinition("County", AliasListFamily.P25);
        Channel channel = new Channel("Control");
        channel.setSystem("System A");
        channel.setAliasListName("County");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        ConfigurationSnapshot state = snapshot(List.of(definition), List.of(channel), List.of());

        assertDoesNotThrow(() -> ConfigurationSnapshotValidator.validateForWrite(state));
    }

    @Test
    void startupValidationRejectsNoncanonicalAliasListName()
    {
        AliasListDefinition definition =
            new AliasListDefinition("County", AliasListFamily.P25);
        Channel channel = new Channel("Control");
        channel.setSystem("System A");
        channel.setAliasListName("county");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        ConfigurationSnapshot state = snapshot(List.of(definition), List.of(channel), List.of());

        assertThrows(RuntimeException.class, () -> ConfigurationSnapshotValidator.validateForWrite(state));
    }

    private static ConfigurationSnapshot snapshot(List<AliasListDefinition> definitions, List<Channel> channels,
                                                  List<BroadcastConfiguration> streams)
    {
        return new ConfigurationSnapshot(definitions, List.of(), ScanListConfiguration.defaultConfiguration(),
            channels, streams);
    }
}
