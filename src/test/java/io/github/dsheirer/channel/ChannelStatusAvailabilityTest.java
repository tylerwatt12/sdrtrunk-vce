/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.config.DecodeConfiguration;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.nbfm.DecodeConfigNBFM;
import io.github.dsheirer.module.decode.nxdn.DecodeConfigNXDN;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.source.config.SourceConfigRemote;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelStatusAvailabilityTest
{
    @TempDir Path mTemporaryDirectory;

    @Test
    @SuppressWarnings("unchecked")
    void availabilityMatchesCatalogWithoutBuildingDirectoryDetails() throws Exception
    {
        DirectoryPreference directory = new DirectoryPreference(ignored -> {})
        {
            @Override public Path getDirectoryApplicationRoot() { return mTemporaryDirectory; }
        };
        UserPreferences preferences = new UserPreferences()
        {
            @Override public DirectoryPreference getDirectoryPreference() { return directory; }
        };
        SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(preferences));
        AliasModel aliases = new AliasModel();
        ConfigurationManager manager = new ConfigurationManager(preferences, null, aliases, null, null);
        manager.init();
        ChannelAdministrationService service = ChannelAdministrationServiceTestSupport.create(manager);
        Field field = ChannelProcessingManager.class.getDeclaredField("mProcessingChainsMap");
        field.setAccessible(true);
        Map<Channel,ProcessingChain> running = (Map<Channel,ProcessingChain>)field.get(manager.getChannelProcessingManager());
        try
        {
            assertFalse(service.hasRunningP25TrunkedChannel());
            for(DecodeConfiguration decoder: List.of(new DecodeConfigP25Phase1(), new DecodeConfigP25Phase2(),
                new DecodeConfigP25Conventional(), new DecodeConfigDMR(), new DecodeConfigNXDN(), new DecodeConfigNBFM()))
            {
                Channel channel = new Channel("Synthetic status channel");
                channel.setDecodeConfiguration(decoder);
                SourceConfigTuner source = new SourceConfigTuner();
                source.setFrequency(851_012_500L);
                channel.setSourceConfiguration(source);
                manager.getChannelModel().addChannel(channel);
                ProcessingChain chain = new ProcessingChain(channel, aliases);
                try
                {
                    assertFalse(service.hasRunningP25TrunkedChannel(), "A stopped P25 channel is unavailable");
                    running.put(channel, chain);
                    boolean expected = decoder instanceof DecodeConfigP25Phase1 || decoder instanceof DecodeConfigP25Phase2;
                    assertEquals(expected, service.hasRunningP25TrunkedChannel(), decoder.getDecoderType().name());
                    assertEquals(catalogAvailable(service), service.hasRunningP25TrunkedChannel());
                    if(expected)
                    {
                        SourceConfigRemote remote = new SourceConfigRemote();
                        remote.setSenderId("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee");
                        remote.setFeedId("11111111-2222-4333-8444-555555555555");
                        remote.setFrequency(851_012_500L);
                        channel.setSourceConfiguration(remote);
                        assertTrue(service.hasRunningP25TrunkedChannel());
                        assertEquals(catalogAvailable(service), service.hasRunningP25TrunkedChannel());
                        channel.setSourceConfiguration(new SourceConfigRemote());
                        assertFalse(service.hasRunningP25TrunkedChannel(), "Unsupported source cannot be classified");
                        assertFalse(catalogAvailable(service));
                    }
                }
                finally
                {
                    running.clear();
                    manager.getChannelModel().removeChannel(channel);
                    chain.dispose();
                }
            }
        }
        finally
        {
            manager.flushConfiguration();
            manager.getChannelProcessingManager().shutdown();
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    private static boolean catalogAvailable(ChannelAdministrationService service)
    {
        return service.currentCatalog().channels().stream().anyMatch(channel ->
            "TRUNKED".equals(channel.channelKind()) &&
                channel.processingState() == ChannelAdministrationService.ProcessingState.RUNNING &&
                Set.of("p25-phase1", "p25-phase2").contains(channel.protocolId()));
    }
}
