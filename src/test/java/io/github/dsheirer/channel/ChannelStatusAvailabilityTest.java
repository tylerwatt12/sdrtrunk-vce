/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.channel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.configuration.ChannelConfigurationSnapshot;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelEvent;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
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
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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
        try(Fixture fixture = fixture())
        {
            ConfigurationManager manager = fixture.manager();
            ChannelAdministrationService service = fixture.service();
            AliasModel aliases = fixture.aliases();
            Map<Channel,ProcessingChain> running = fixture.running();
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var blockedOwner = manager.submitConfigurationTask(() ->
            {
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); }
            });
            try
            {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                {
                    assertFalse(service.hasRunningP25TrunkedChannel());
                    assertTrue(service.currentCatalog().channels().isEmpty());
                }, "navigation/status must not wait for a busy configuration owner");
            }
            finally { release.countDown(); }
            blockedOwner.get(2, TimeUnit.SECONDS);
            assertFalse(service.hasRunningP25TrunkedChannel());
            for(DecodeConfiguration decoder: List.of(new DecodeConfigP25Phase1(), new DecodeConfigP25Phase2(),
                new DecodeConfigP25Conventional(), new DecodeConfigDMR(), new DecodeConfigNXDN(), new DecodeConfigNBFM()))
            {
                Channel channel = new Channel("Synthetic status channel");
                channel.setDecodeConfiguration(decoder);
                SourceConfigTuner source = new SourceConfigTuner();
                source.setFrequency(851_012_500L);
                channel.setSourceConfiguration(source);
                publish(fixture, () -> manager.getChannelModel().addChannel(channel));
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
                        publish(fixture, () -> channel.setSourceConfiguration(remote));
                        assertTrue(service.hasRunningP25TrunkedChannel());
                        assertEquals(catalogAvailable(service), service.hasRunningP25TrunkedChannel());
                        publish(fixture, () -> channel.setSourceConfiguration(new SourceConfigRemote()));
                        assertFalse(service.hasRunningP25TrunkedChannel(), "Unsupported source cannot be classified");
                        assertFalse(catalogAvailable(service));
                    }
                }
                finally
                {
                    running.clear();
                    publish(fixture, () -> manager.getChannelModel().removeChannel(channel));
                    chain.dispose();
                }
            }
        }
    }

    @Test
    void populatedRunningStatusAndCatalogDoNotWaitForTheConfigurationOwner() throws Exception
    {
        try(Fixture fixture = fixture())
        {
            Channel channel = p25Channel("Published running channel");
            publish(fixture, () -> fixture.manager().getChannelModel().addChannel(channel));
            ProcessingChain chain = new ProcessingChain(channel, fixture.aliases());
            fixture.running().put(channel, chain);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            var blocked = fixture.manager().submitConfigurationTask(() -> {
                entered.countDown(); await(release);
            });
            try
            {
                assertTrue(entered.await(2, TimeUnit.SECONDS));
                long revision = fixture.service().currentRevision();
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                    assertTrue(fixture.service().hasRunningP25TrunkedChannel());
                    var catalog = fixture.service().currentCatalog();
                    assertEquals(revision, catalog.revision());
                    assertEquals(List.of("Published running channel"),
                        catalog.channels().stream().map(ChannelAdministrationService.ChannelSummary::name).toList());
                    assertEquals(ChannelAdministrationService.ProcessingState.RUNNING,
                        catalog.channels().getFirst().processingState());
                }, "Populated navigation and catalog reads must not queue behind a blocked owner");
            }
            finally
            {
                release.countDown();
                blocked.get(2, TimeUnit.SECONDS);
                fixture.running().clear(); chain.dispose();
            }
        }
    }

    @Test
    void aPartialModelPublicationCannotExposeMixedConfigurationOrItsNewRevision() throws Exception
    {
        try(Fixture fixture = fixture())
        {
            ConfigurationManager manager = fixture.manager();
            Channel first = p25Channel("Before first");
            Channel second = p25Channel("Before second");
            first.setAliasListDefinition(fixture.aliases().getAliasListDefinition(AliasListFamily.P25.getDefaultAliasListName()));
            second.setAliasListDefinition(fixture.aliases().getAliasListDefinition(AliasListFamily.P25.getDefaultAliasListName()));
            Set<String> ids = Set.of(first.getConfigurationId(), second.getConfigurationId());
            manager.commitAndPublishChannelConfiguration(new ChannelConfigurationSnapshot(List.of(first, second)), ids, false);
            var before = fixture.service().currentCatalog();
            List<Channel> changed = manager.getChannelModel().getChannels().stream()
                .map(Channel::copyOfPreservingIdentity).toList();
            changed.get(0).setName("After first");
            changed.get(1).setName("After second");
            CountDownLatch partial = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicInteger publications = new AtomicInteger();
            Listener<ChannelEvent> pause = event -> {
                if(event.getEvent() == ChannelEvent.Event.NOTIFICATION_CONFIGURATION_CHANGE &&
                    publications.incrementAndGet() == 1)
                {
                    partial.countDown(); await(release);
                }
            };
            manager.getChannelModel().addListener(pause);
            var update = manager.submitConfigurationTask(() -> manager.commitAndPublishChannelConfiguration(
                new ChannelConfigurationSnapshot(changed), ids, false));
            try
            {
                assertTrue(partial.await(2, TimeUnit.SECONDS));
                assertEquals(1, manager.getChannelModel().getChannels().stream()
                    .filter(channel -> channel.getName().startsWith("After")).count(), "Fixture pauses halfway through model replacement");
                assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                    assertEquals(before, fixture.service().currentCatalog());
                    assertEquals(before.revision(), fixture.service().currentRevision());
                }, "Readers must see the previous complete publication while the model is partially replaced");
            }
            finally
            {
                release.countDown();
                update.get(2, TimeUnit.SECONDS);
                manager.getChannelModel().removeListener(pause);
            }
            var after = fixture.service().currentCatalog();
            assertTrue(after.revision() > before.revision());
            assertEquals(List.of("After first", "After second"),
                after.channels().stream().map(ChannelAdministrationService.ChannelSummary::name).toList());
        }
    }

    private static Channel p25Channel(String name)
    {
        Channel channel = new Channel(name);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(851_012_500L);
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static void publish(Fixture fixture, Runnable mutation)
    {
        fixture.manager().executeConfigurationTask(() -> {
            mutation.run();
            fixture.manager().getChannelAdministrationService().publishCommittedCatalog();
            return null;
        });
    }

    private static void await(CountDownLatch release)
    {
        try
        {
            if(!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture publication was not released");
        }
        catch(InterruptedException failure)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private Fixture fixture() throws Exception
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
        TunerManager tuners = new TunerManager(preferences);
        ConfigurationManager manager = new ConfigurationManager(preferences, tuners, aliases, null);
        manager.init();
        Field field = ChannelProcessingManager.class.getDeclaredField("mProcessingChainsMap");
        field.setAccessible(true);
        Map<Channel,ProcessingChain> running = (Map<Channel,ProcessingChain>)field.get(manager.getChannelProcessingManager());
        return new Fixture(manager, ChannelAdministrationServiceTestSupport.create(manager), aliases, tuners, running);
    }

    private record Fixture(ConfigurationManager manager, ChannelAdministrationService service, AliasModel aliases,
                           TunerManager tuners, Map<Channel,ProcessingChain> running) implements AutoCloseable
    {
        @Override public void close()
        {
            manager.quiesceAdministration();
            manager.getChannelProcessingManager().close();
            manager.getBroadcastModel().close();
            manager.close();
            manager.getRadioReferenceDirectoryService().close();
            tuners.stop();
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
