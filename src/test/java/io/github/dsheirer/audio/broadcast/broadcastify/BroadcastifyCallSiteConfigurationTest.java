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

package io.github.dsheirer.audio.broadcast.broadcastify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.BroadcastConfiguration;
import io.github.dsheirer.audio.broadcast.BroadcastFactory;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.BroadcastServerType;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.configuration.ChannelAndBroadcastConfiguration;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.nxdn.NXDNChannelMode;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BroadcastifyCallSiteConfigurationTest
{
    private static final long ALIAS_LIST_ID = 41L;
    private static final String CHANNEL_ID = "00000000-0000-0000-0000-000000000041";
    private final ObjectMapper mObjectMapper = new ObjectMapper();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void polymorphicJsonAndFactoryPreserveTheSiteSelection() throws Exception
    {
        BroadcastifyCallSiteConfiguration configuration = configuredSiteProvider();
        configuration.setName("West site calls");
        configuration.setEnabled(true);

        String json = mObjectMapper.writeValueAsString(configuration);
        JsonNode tree = mObjectMapper.readTree(json);
        BroadcastConfiguration restoredBase = mObjectMapper.readValue(json, BroadcastConfiguration.class);
        BroadcastifyCallSiteConfiguration restored =
            assertInstanceOf(BroadcastifyCallSiteConfiguration.class, restoredBase);

        assertEquals("broadcastifyCallSiteConfiguration", tree.path("type").asText());
        assertEquals(ALIAS_LIST_ID, restored.getAliasListId());
        assertEquals("Regional P25", restored.getAliasListName());
        assertEquals(CHANNEL_ID, restored.getChannelConfigurationId());
        assertEquals(BroadcastServerType.BROADCASTIFY_CALL_SITE, restored.getBroadcastServerType());
        assertTrue(restored.isValid());
        assertInstanceOf(BroadcastifyCallSiteConfiguration.class,
            BroadcastFactory.getConfiguration(BroadcastServerType.BROADCASTIFY_CALL_SITE, BroadcastFormat.MP3));
    }

    @Test
    void sqliteConfigurationStoreRoundTripsTheSiteSelection() throws Exception
    {
        Path database = mTemporaryFolder.resolve("site-provider.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationDatabaseStore store = new ConfigurationDatabaseStore(database);
        BroadcastifyCallSiteConfiguration configuration = configuredSiteProvider();
        configuration.setName("West site calls");
        configuration.setEnabled(true);

        try(Connection connection = SdrTrunkDatabase.open(database))
        {
            connection.setAutoCommit(false);
            store.replace(connection,
                new ChannelAndBroadcastConfiguration(List.of(), List.of(configuration)));
            connection.commit();
        }

        BroadcastifyCallSiteConfiguration restored = assertInstanceOf(BroadcastifyCallSiteConfiguration.class,
            store.load().broadcastConfigurations().getFirst());
        assertEquals(ALIAS_LIST_ID, restored.getAliasListId());
        assertEquals("Regional P25", restored.getAliasListName());
        assertEquals(CHANNEL_ID, restored.getChannelConfigurationId());
        assertEquals("West site calls", restored.getName());
        assertTrue(restored.isEnabled());
    }

    @Test
    void selectorOffersOnlySavedSupportedTrunkedChannelsForTheAliasList()
    {
        AliasListDefinition p25 = definition(ALIAS_LIST_ID, "Regional P25", AliasListFamily.P25);
        Channel phase1 = channel("Phase 1", Channel.ChannelType.STANDARD, "Regional P25",
            new DecodeConfigP25Phase1());
        Channel phase2 = channel("Phase 2", Channel.ChannelType.STANDARD, "regional p25",
            new DecodeConfigP25Phase2());
        Channel conventional = channel("Conventional", Channel.ChannelType.STANDARD, "Regional P25",
            new DecodeConfigP25Conventional());
        Channel traffic = channel("Traffic", Channel.ChannelType.TRAFFIC, "Regional P25",
            new DecodeConfigP25Phase1());
        Channel reassigned = channel("Other list", Channel.ChannelType.STANDARD, "Other P25",
            new DecodeConfigP25Phase1());

        assertEquals(List.of(phase1, phase2), BroadcastifyCallSiteConfiguration.eligibleChannels(
            List.of(phase1, phase2, conventional, traffic, reassigned), p25));

        AliasListDefinition dmr = definition(42L, "Regional DMR", AliasListFamily.DMR);
        DecodeConfigDMR trunkedDmr = new DecodeConfigDMR();
        trunkedDmr.setChannelMode(DMRChannelMode.TRUNKED);
        DecodeConfigDMR conventionalDmr = new DecodeConfigDMR();
        conventionalDmr.setChannelMode(DMRChannelMode.CONVENTIONAL);
        assertTrue(BroadcastifyCallSiteConfiguration.isEligibleChannel(
            channel("DMR trunked", Channel.ChannelType.STANDARD, "Regional DMR", trunkedDmr), dmr));
        assertFalse(BroadcastifyCallSiteConfiguration.isEligibleChannel(
            channel("DMR conventional", Channel.ChannelType.STANDARD, "Regional DMR", conventionalDmr), dmr));

        AliasListDefinition nxdn = definition(43L, "Regional NXDN", AliasListFamily.NXDN);
        DecodeConfigNXDN trunkedNxdn = new DecodeConfigNXDN();
        trunkedNxdn.setChannelMode(NXDNChannelMode.TRUNKED);
        DecodeConfigNXDN conventionalNxdn = new DecodeConfigNXDN();
        conventionalNxdn.setChannelMode(NXDNChannelMode.CONVENTIONAL);
        assertTrue(BroadcastifyCallSiteConfiguration.isEligibleChannel(
            channel("NXDN trunked", Channel.ChannelType.STANDARD, "Regional NXDN", trunkedNxdn), nxdn));
        assertFalse(BroadcastifyCallSiteConfiguration.isEligibleChannel(
            channel("NXDN conventional", Channel.ChannelType.STANDARD, "Regional NXDN", conventionalNxdn), nxdn));
    }

    @Test
    void durableAliasListRenameResolvesButMissingOrChangedSelectionsFailClosed()
    {
        AliasListDefinition renamed = definition(ALIAS_LIST_ID, "Renamed Regional P25", AliasListFamily.P25);
        AliasModel aliasModel = new AliasModel();
        aliasModel.replaceCommittedConfiguration(List.of(renamed), List.of());
        BroadcastifyCallSiteConfiguration configuration = configuredSiteProvider();
        Channel selected = channel("West", Channel.ChannelType.STANDARD, renamed.getName(),
            new DecodeConfigP25Phase1());
        selected.setConfigurationId(CHANNEL_ID);

        assertSame(renamed, configuration.resolveAliasList(aliasModel).orElseThrow(),
            "The durable Alias List ID, not its old display name, owns resolution");
        assertSame(selected, configuration.resolveChannel(aliasModel, List.of(selected)).orElseThrow());
        assertTrue(configuration.hasValidSiteSelection(aliasModel, List.of(selected)));

        assertFalse(configuration.hasValidSiteSelection(aliasModel, List.of()),
            "A deleted selected channel must not be rebound");

        Channel reassigned = channel("West", Channel.ChannelType.STANDARD, "A different Alias List",
            new DecodeConfigP25Phase1());
        reassigned.setConfigurationId(CHANNEL_ID);
        assertFalse(configuration.hasValidSiteSelection(aliasModel, List.of(reassigned)));

        Channel conventional = channel("West", Channel.ChannelType.STANDARD, renamed.getName(),
            new DecodeConfigP25Conventional());
        conventional.setConfigurationId(CHANNEL_ID);
        assertFalse(configuration.hasValidSiteSelection(aliasModel, List.of(conventional)));

        Channel traffic = channel("West", Channel.ChannelType.TRAFFIC, renamed.getName(),
            new DecodeConfigP25Phase1());
        traffic.setConfigurationId(CHANNEL_ID);
        assertFalse(configuration.hasValidSiteSelection(aliasModel, List.of(traffic)));

        selected.setConfigurationId("00000000-0000-0000-0000-000000000099");
        assertFalse(configuration.hasValidSiteSelection(aliasModel, List.of(selected)),
            "A different saved-channel UUID must not satisfy the selection");

        configuration.setChannelConfigurationId("not-a-uuid");
        assertFalse(configuration.hasSiteSelection());
        assertFalse(configuration.hasValidSiteSelection(aliasModel, List.of(selected)));
    }

    private static BroadcastifyCallSiteConfiguration configuredSiteProvider()
    {
        BroadcastifyCallSiteConfiguration configuration = new BroadcastifyCallSiteConfiguration();
        configuration.setSystemID(1);
        configuration.setApiKey("test-key");
        configuration.setAliasListId(ALIAS_LIST_ID);
        configuration.setAliasListName("Regional P25");
        configuration.setChannelConfigurationId(CHANNEL_ID);
        return configuration;
    }

    private static AliasListDefinition definition(long id, String name, AliasListFamily family)
    {
        AliasListDefinition definition = new AliasListDefinition(name, family);
        definition.setId(id);
        return definition;
    }

    private static Channel channel(String name, Channel.ChannelType type, String aliasListName,
                                   io.github.dsheirer.module.decode.config.DecodeConfiguration decoder)
    {
        Channel channel = new Channel(name, type);
        channel.setAliasListName(aliasListName);
        channel.setDecodeConfiguration(decoder);
        return channel;
    }
}
