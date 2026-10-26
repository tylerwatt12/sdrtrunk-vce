/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.configuration;

import static io.github.dsheirer.test.BroadcastRouteTestSupport.route;
import static io.github.dsheirer.test.BroadcastRouteTestSupport.routeNames;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasListDefaults;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.NewAliasBehavior;
import io.github.dsheirer.alias.UnmatchedTalkgroupPolicy;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.BroadcastEvent;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelConfigurationChangeNotification;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.database.scanlist.ScanListDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasAdministrationPersistenceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void renameRetainsDefaultListIdentityPoliciesAssignmentsAndActiveChainsAcrossReload() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("rename-default-list-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null);
        long listId;
        long aliasId;
        String channelId;
        AliasListDefaults defaults;
        Map<Channel,ProcessingChain> chains = null;
        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasListDefinition original = manager.getAliasModel().getDefaultAliasListDefinition(
                io.github.dsheirer.module.decode.DecoderType.P25_PHASE1);
            listId = original.getId();
            long scanListId = manager.getScanListModel().defaultScanList().getId();
            defaults = new AliasListDefaults(new UnmatchedTalkgroupPolicy(true, List.of()), Set.of(scanListId),
                new NewAliasBehavior(true, List.of()), Set.of(scanListId));
            service.updateAliasListDefaults(listId, defaults, service.getAliasListDefaults(listId).editRevision());
            aliasId = service.createAlias(alias("Dispatch", listId, 101), List.of(scanListId)).aliasIds().getFirst();
            AliasList cached = manager.getAliasModel().getAliasList(original);
            Channel saved = new Channel("Saved P25");
            saved.setDecodeConfiguration(new DecodeConfigP25Phase1());
            saved.setAliasListDefinition(original);
            manager.addChannel(saved);
            channelId = saved.getConfigurationId();
            Channel traffic = new Channel("Traffic P25", Channel.ChannelType.TRAFFIC);
            traffic.setDecodeConfiguration(new DecodeConfigP25Phase1());
            traffic.setConfigurationId(channelId);
            traffic.setAliasListDefinition(original);
            AtomicInteger savedRefreshes = new AtomicInteger();
            AtomicInteger trafficRefreshes = new AtomicInteger();
            ProcessingChain savedChain = observingChain(saved, manager.getAliasModel(), savedRefreshes,
                new AtomicBoolean(false));
            ProcessingChain trafficChain = observingChain(traffic, manager.getAliasModel(), trafficRefreshes,
                new AtomicBoolean(true));
            chains = processingChains(manager);
            chains.put(saved, savedChain);
            chains.put(traffic, trafficChain);

            service.renameAliasList(listId, "  Regional P25  ",
                AliasAdministrationService.aliasListNameEditRevision(original));

            assertEquals("Regional P25", saved.getAliasListName());
            assertEquals("Regional P25", traffic.getAliasListName());
            assertEquals(listId, saved.getAliasListId());
            assertSame(savedChain, chains.get(saved));
            assertSame(trafficChain, chains.get(traffic));
            assertTrue(savedRefreshes.get() >= 1);
            assertEquals(2, trafficRefreshes.get(), "Recovery retries metadata notification after an observer failure");
            assertEquals(aliasId, manager.getAliasModel().getAlias(aliasId).getId());
            assertEquals("Regional P25", manager.getAliasModel().getAlias(aliasId).getAliasListName());
            assertSame(cached, manager.getAliasModel().getAliasListForChannel(saved));
            assertEquals(defaults, service.getAliasListDefaults(listId).defaults());
            assertEquals(Set.of(scanListId), service.getAlias(aliasId).scanListIds());
            assertEquals("Regional P25", new ConfigurationDatabaseStore(database).load().channels().getFirst()
                .getAliasListName());
        }
        finally
        {
            if(chains != null) chains.clear();
            flushAndUnregister(manager);
        }

        ConfigurationManager reloaded = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null);
        try
        {
            reloaded.init();
            assertEquals("Regional P25", reloaded.getAliasModel().getAliasListDefinition(listId).getName());
            assertEquals(defaults, reloaded.getAliasAdministrationService().getAliasListDefaults(listId).defaults());
            assertEquals(listId, reloaded.getAliasModel().getAlias(aliasId).getAliasListId());
            assertTrue(reloaded.getAliasModel().aliasListDefinitions().stream()
                .noneMatch(definition -> definition.getName().equals("Default P25")));
            Channel saved = reloaded.getChannelModel().getChannels().stream()
                .filter(channel -> channel.getConfigurationId().equals(channelId)).findFirst().orElseThrow();
            assertEquals(listId, saved.getAliasListId());
            assertEquals("Regional P25", saved.getAliasListName());
            Channel newChannel = new Channel("New P25");
            newChannel.setDecodeConfiguration(new DecodeConfigP25Phase1());
            reloaded.getAliasModel().requireAliasListAssignment(newChannel);
            assertEquals(listId, newChannel.getAliasListId());
        }
        finally
        {
            flushAndUnregister(reloaded);
        }
    }

    @Test
    void renameRejectsStaleDuplicateAndInvalidNamesWithoutChangingConfiguration() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("rename-validation-data");
        SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null);
        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            long listId = service.createAliasList("County P25", AliasListFamily.P25).aliasListId();
            service.createAliasList("Other", AliasListFamily.P25);
            String initial = AliasAdministrationService.aliasListNameEditRevision(service.options(listId).aliasList());
            long revision = service.currentRevision();
            for(String invalid: List.of("   ", "x".repeat(129), "oThEr"))
            {
                assertThrows(IllegalArgumentException.class, () -> service.renameAliasList(listId, invalid, initial));
            }
            assertEquals(revision, service.currentRevision());
            service.updateUnmatchedTalkgroupPolicy(listId, new UnmatchedTalkgroupPolicy(true, List.of()),
                service.getAliasListDefaults(listId).editRevision());
            service.renameAliasList(listId, "county p25", initial);
            assertEquals("county p25", service.options(listId).aliasList().getName());
            assertThrows(AliasAdministrationService.StaleEditException.class,
                () -> service.renameAliasList(listId, "Stale rename", initial));
            String current = AliasAdministrationService.aliasListNameEditRevision(service.options(listId).aliasList());
            service.renameAliasList(listId, "x".repeat(128), current);
            assertEquals(128, service.options(listId).aliasList().getName().length());
        }
        finally
        {
            flushAndUnregister(manager);
        }
    }

    @Test
    void unchangedRenameAvoidsCommitAndFailedRenamePreservesRowsAndLiveNames() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("rename-rollback-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        FailingConfigurationManager manager = new FailingConfigurationManager(new TestUserPreferences(dataRoot));
        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            long listId = service.createAliasList("County P25", AliasListFamily.P25).aliasListId();
            long aliasId = service.createAlias(alias("Dispatch", listId, 101)).aliasIds().getFirst();
            AliasListDefinition definition = manager.getAliasModel().getAliasListDefinition(listId);
            String editRevision = AliasAdministrationService.aliasListNameEditRevision(definition);
            Channel channel = new Channel("Saved P25");
            channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
            channel.setAliasListDefinition(definition);
            manager.addChannel(channel);
            long revision = service.currentRevision();
            manager.failNextSave();

            assertEquals(0, service.renameAliasList(listId, " County P25 ", editRevision).affected());
            assertEquals(revision, service.currentRevision());
            assertThrows(AliasAdministrationService.PersistenceException.class,
                () -> service.renameAliasList(listId, "Regional P25", editRevision));

            assertEquals(revision, service.currentRevision());
            assertSame(definition, manager.getAliasModel().getAliasListDefinition(listId));
            assertEquals("County P25", channel.getAliasListName());
            assertEquals("County P25", manager.getAliasModel().getAlias(aliasId).getAliasListName());
            assertEquals("County P25", aliasListDefinition(new AliasDatabaseStore(database)
                .loadAliasListDefinitions(), listId).getName());
        }
        finally
        {
            flushAndUnregister(manager);
        }
    }

    private static ProcessingChain observingChain(Channel channel, AliasModel aliases, AtomicInteger notifications,
                                                   AtomicBoolean failFirstNotification)
    {
        return new ProcessingChain(channel, aliases)
        {
            @Override
            public void channelConfigurationChanged(ChannelConfigurationChangeNotification notification)
            {
                super.channelConfigurationChanged(notification);
                notifications.incrementAndGet();
                if(failFirstNotification.getAndSet(false))
                {
                    throw new IllegalStateException("Injected rename observer failure");
                }
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<Channel,ProcessingChain> processingChains(ConfigurationManager manager) throws Exception
    {
        Field field = ChannelProcessingManager.class.getDeclaredField("mProcessingChainsMap");
        field.setAccessible(true);
        return (Map<Channel,ProcessingChain>)field.get(manager.getChannelProcessingManager());
    }

    @Test
    void unchangedAliasDefaultsAndScanListAvoidCommitPublicationAndRevisionChange() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("unchanged-configuration-data");
        SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(dataRoot));
        FailingConfigurationManager manager = new FailingConfigurationManager(new TestUserPreferences(dataRoot));
        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            long listId = service.createAliasList("County P25", AliasListFamily.P25).aliasListId();
            long aliasId = service.createAlias(alias("Dispatch", listId, 101)).aliasIds().getFirst();
            long revision = service.currentRevision();
            Alias liveAlias = manager.getAliasModel().getAlias(aliasId);
            AliasListDefinition liveDefinition = liveAlias.getAliasListDefinition();
            AtomicInteger notifications = new AtomicInteger();
            manager.getAliasModel().addChangeListener(change -> {
                if(change.aliasesChanged()) notifications.incrementAndGet();
            });
            manager.failNextSave();

            assertEquals(revision, service.replaceAlias(aliasId, service.getAlias(aliasId).alias(), service.getAlias(aliasId).editRevision()).revision());
            assertEquals(revision, service.updateAliasListDefaults(listId,
                service.getAliasListDefaults(listId).defaults(), service.getAliasListDefaults(listId).editRevision()).revision());
            ScanList scanList = manager.getScanListModel().configuration().defaultScanList();
            assertEquals(revision, service.updateScanList(scanList.getId(), scanList, service.getScanList(scanList.getId()).editRevision()).revision());
            assertSame(liveAlias, manager.getAliasModel().getAlias(aliasId));
            assertSame(liveDefinition, liveAlias.getAliasListDefinition());
            assertEquals(0, notifications.get());

            Alias changed = service.getAlias(aliasId).alias();
            changed.setName("Changed Dispatch");
            assertThrows(AliasAdministrationService.PersistenceException.class,
                () -> service.replaceAlias(aliasId, changed, service.getAlias(aliasId).editRevision()),
                "Unchanged commands must leave the injected next-commit failure unused");
            assertEquals(revision, service.currentRevision());
            assertEquals("Dispatch", liveAlias.getName());
        }
        finally
        {
            flushAndUnregister(manager);
        }
    }

    @Test
    void customAliasListStartsWithoutPersistedUnmatchedScanListRouting() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("custom-list-default-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null);

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            long aliasListId = service.createAliasList("County P25", AliasListFamily.P25).aliasListId();

            assertTrue(service.getAliasListDefaults(aliasListId).defaults().scanListIds().isEmpty());
            assertTrue(new ScanListDatabaseStore(database).loadConfiguration()
                .scanListIdsForUnmatchedTalkgroups(aliasListId).isEmpty());
        }
        finally
        {
            flushAndUnregister(manager);
        }
    }

    @Test
    void createCommitsCanonicalIdentityBeforePublishingTheAlias() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("publish-after-commit-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null);

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25);
            AliasDatabaseStore store = new AliasDatabaseStore(database);
            AtomicInteger additions = new AtomicInteger();
            AtomicReference<Alias> published = new AtomicReference<>();
            AtomicReference<Boolean> rowWasCommitted = new AtomicReference<>(false);
            AtomicReference<Throwable> listenerFailure = new AtomicReference<>();

            io.github.dsheirer.alias.AliasModelTestAccess.observeAdditions(manager.getAliasModel(), added -> {
                additions.incrementAndGet();
                published.set(added);

                try
                {
                    List<AliasListDefinition> definitions = store.loadAliasListDefinitions();
                    rowWasCommitted.set(added.getId() > Alias.UNASSIGNED_ID &&
                        store.loadAliases(definitions).stream()
                            .anyMatch(alias -> alias.getId() == added.getId()));
                }
                catch(Throwable throwable)
                {
                    listenerFailure.set(throwable);
                }
            });

            AliasAdministrationService.MutationResult created = service.createAlias(
                alias("Dispatch", list.aliasListId(), 101));
            long aliasId = created.aliasIds().getFirst();

            assertEquals(1, additions.get());
            assertEquals(null, listenerFailure.get());
            assertTrue(rowWasCommitted.get(), "Alias was observable before its SQLite row and identity existed");
            assertTrue(aliasId > Alias.UNASSIGNED_ID);
            assertEquals(aliasId, published.get().getId());
            assertEquals(1, manager.getAliasModel().getAliases().stream()
                .filter(alias -> alias.getId() == aliasId).count());
        }
        finally
        {
            flushAndUnregister(manager);
        }
    }

    @Test
    void aliasWithMembershipPublishesRoutingBeforeTheAlias() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("membership-before-alias-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null);

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25);
            AliasAdministrationService.ScanListMutationResult scanList = service.createScanList(
                new ScanList(ScanList.UNASSIGNED_ID, 1, "Dispatch", null, true, false));
            AtomicBoolean membershipVisible = new AtomicBoolean();

            io.github.dsheirer.alias.AliasModelTestAccess.observeAdditions(manager.getAliasModel(), added -> {
                membershipVisible.set(manager.getScanListModel().scanListIdsForAlias(added.getId())
                    .contains(scanList.scanListId()));
            });

            service.createAlias(alias("Dispatch", list.aliasListId(), 101), List.of(scanList.scanListId()));

            assertTrue(membershipVisible.get(), "Alias became visible before its committed scan-list routing");
        }
        finally
        {
            flushAndUnregister(manager);
        }
    }

    @Test
    void delayedChannelEditAfterCandidateCaptureIsNotDiscarded() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("late-channel-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        LateChannelConfigurationManager manager =
            new LateChannelConfigurationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            manager.addChannelDuringNextCommit();
            service.createAliasList("County P25", AliasListFamily.P25);

            try(Connection connection = SdrTrunkDatabase.open(database);
                Statement statement = connection.createStatement())
            {
                statement.executeUpdate("""
                    CREATE TRIGGER reject_delayed_alias_rewrite
                    BEFORE DELETE ON alias_list
                    BEGIN
                        SELECT RAISE(ABORT, 'channel save must not rewrite Alias rows');
                    END
                    """);
            }

            manager.flushConfiguration();

            assertEquals(List.of("Late Channel"), new ConfigurationDatabaseStore(database).load()
                .channels().stream().map(Channel::getName).toList());
        }
        finally
        {
            flushAndUnregister(manager);
        }
    }

    @Test
    void channelEditObservedDuringAliasPublicationIsStillSaved() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("publication-channel-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null);

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25);
            io.github.dsheirer.alias.AliasModelTestAccess.observeAdditions(manager.getAliasModel(), added -> {
                Channel channel = new Channel("Publication Channel");
                channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
                manager.addChannel(channel);
            });

            service.createAlias(alias("Dispatch", list.aliasListId(), 101));
            manager.flushConfiguration();

            assertEquals(List.of("Publication Channel"),
                new ConfigurationDatabaseStore(database).load().channels().stream()
                    .map(Channel::getName).toList());
        }
        finally
        {
            flushAndUnregister(manager);
        }
    }

    @Test
    void publicationFailureReloadsTheCommittedCanonicalState() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("publication-recovery-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        RecoveringPublicationManager manager = new RecoveringPublicationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25);
            manager.failNextPublication();

            long aliasId = service.createAlias(alias("Dispatch", list.aliasListId(), 101))
                .aliasIds().getFirst();

            assertEquals(aliasId, manager.getAliasModel().getAliases().getFirst().getId());
            List<AliasListDefinition> definitions = new AliasDatabaseStore(database).loadAliasListDefinitions();
            assertEquals(aliasId, new AliasDatabaseStore(database).loadAliases(definitions).getFirst().getId());
        }
        finally
        {
            flushAndUnregister(manager);
        }
    }

    @Test
    void unrecoverablePublicationFailureRejectsLaterCommandsWithRestartError() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("fatal-publication-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        UnrecoverablePublicationManager manager =
            new UnrecoverablePublicationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            ConfigurationManager.ConfigurationPublicationException initial = assertThrows(
                ConfigurationManager.ConfigurationPublicationException.class,
                () -> service.createAliasList("County P25", AliasListFamily.P25));
            assertTrue(initial.getMessage().contains("restart"));
            assertEquals(AliasListFamily.values().length + 1,
                new AliasDatabaseStore(database).loadAliasListDefinitions().size());

            ConfigurationManager.ConfigurationPublicationException later = assertThrows(
                ConfigurationManager.ConfigurationPublicationException.class,
                () -> service.createAliasList("Other P25", AliasListFamily.P25));
            assertTrue(later.getMessage().contains("restart"));
            assertEquals(AliasListFamily.values().length + 1,
                new AliasDatabaseStore(database).loadAliasListDefinitions().size());
        }
        finally
        {
            manager.quiesceAdministration();
            manager.getChannelProcessingManager().close();
            manager.getBroadcastModel().close();
            manager.close();
            manager.getRadioReferenceDirectoryService().close();
        }
    }

    @Test
    void failedCommitNeverPublishesTheCandidateOrChangesStoredRows() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        FailingConfigurationManager manager = new FailingConfigurationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25);
            AliasAdministrationService.MutationResult first = service.createAlias(
                alias("Dispatch", list.aliasListId(), 101));
            AliasAdministrationService.MutationResult second = service.createAlias(
                alias("Operations", list.aliasListId(), 102));
            List<Long> aliasIds = List.of(first.aliasIds().getFirst(), second.aliasIds().getFirst());
            AtomicInteger publications = new AtomicInteger();
            manager.getAliasModel().addChangeListener(change -> {
                if(change.aliasesChanged()) publications.incrementAndGet();
            });

            manager.failNextSave();
            assertThrows(AliasAdministrationService.PersistenceException.class, () ->
                service.bulkEdit(new AliasAdministrationService.BulkEdit(aliasIds, null, 0x123456, null,
                    true, null, null, null, null, false), second.revision()));

            assertEquals(0, publications.get());
            for(long aliasId: aliasIds)
            {
                Alias live = service.getAlias(aliasId).alias();
                assertEquals(0, live.getColor());
                assertFalse(live.isRecordable());
            }
            assertStoredDefaults(database, aliasIds);
        }
        finally
        {
            flushAndUnregister(manager);
        }
    }

    @Test
    void failedMixedCommandsNeverPublishOrMutateLiveState() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("mixed-failure-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        FailingConfigurationManager manager = new FailingConfigurationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            long aliasListId = service.createAliasList("County P25", AliasListFamily.P25).aliasListId();
            Alias original = alias("Dispatch", aliasListId, 101);
            long aliasId = service.createAlias(original,
                Set.of(manager.getScanListModel().defaultScanList().getId()))
                .aliasIds().getFirst();
            long secondAliasId = service.createAlias(alias("Secondary", aliasListId, 101)).aliasIds().getFirst();
            AliasList cachedAliasList = manager.getAliasModel().getAliasList("County P25");
            assertEquals(secondAliasId,
                cachedAliasList.getAliases(APCO25Talkgroup.create(101)).getFirst().getId());
            Alias replacement = service.getAlias(aliasId).alias();
            replacement.setName("Changed");
            manager.failNextSave();
            long mixedRevision = service.currentRevision();
            assertThrows(AliasAdministrationService.PersistenceException.class,
                () -> service.applyTransfer(aliasListId, List.of(
                    new AliasAdministrationService.AliasEntry(mixedRevision, replacement,
                        service.getAlias(aliasId).scanListIds()),
                    new AliasAdministrationService.AliasEntry(mixedRevision,
                        alias("New Alias", aliasListId, 102), Set.of())), List.of(), mixedRevision));

            assertEquals(List.of(aliasId, secondAliasId), manager.getAliasModel().getAliases().stream()
                .map(Alias::getId).toList());
            assertEquals("Dispatch", service.getAlias(aliasId).alias().getName());
            assertEquals(secondAliasId,
                cachedAliasList.getAliases(APCO25Talkgroup.create(101)).getFirst().getId());

            manager.failNextSave();
            assertThrows(AliasAdministrationService.PersistenceException.class,
                () -> service.applyTransfer(aliasListId, List.of(), List.of(aliasId), service.currentRevision()));
            assertEquals(List.of(aliasId, secondAliasId), manager.getAliasModel().getAliases().stream()
                .map(Alias::getId).toList());
            assertEquals(secondAliasId,
                cachedAliasList.getAliases(APCO25Talkgroup.create(101)).getFirst().getId());

            AliasDatabaseStore store = new AliasDatabaseStore(database);
            List<AliasListDefinition> definitions = store.loadAliasListDefinitions();
            assertEquals(List.of("Dispatch", "Secondary"), store.loadAliases(definitions).stream()
                .map(Alias::getName).toList());
        }
        finally
        {
            flushAndUnregister(manager);
        }
    }

    @Test
    void broadcastRenameKeepsStableAliasReferencesUntouched() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("broadcast-rename-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null);

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            BroadcastifyCallConfiguration stream = new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
            stream.setName("Old Stream");
            manager.commitAndPublishStreamingConfiguration(List.of(stream), stream.getConfigurationId());
            manager.flushConfiguration();

            long aliasListId = service.createAliasList("County P25", AliasListFamily.P25).aliasListId();
            Alias proposed = alias("Dispatch", aliasListId, 101);
            proposed.addBroadcastChannel(route(stream));
            long aliasId = service.createAlias(proposed,
                Set.of(manager.getScanListModel().defaultScanList().getId()))
                .aliasIds().getFirst();
            service.updateUnmatchedTalkgroupPolicy(aliasListId,
                new UnmatchedTalkgroupPolicy(false, List.of(route(stream))), service.getAliasListDefaults(aliasListId).editRevision());

            assertEquals("Old Stream", stream.getName());
            stream.setName("New Stream");
            manager.commitAndPublishStreamingConfiguration(List.of(stream), stream.getConfigurationId());
            manager.flushConfiguration();

            assertEquals("New Stream", stream.getName());
            assertEquals(List.of("New Stream"), new ConfigurationDatabaseStore(database).load()
                .broadcastConfigurations().stream().map(configuration -> configuration.getName()).toList());
            List<AliasListDefinition> definitions = new AliasDatabaseStore(database).loadAliasListDefinitions();
            Alias stored = new AliasDatabaseStore(database).loadAliases(definitions).stream()
                .filter(alias -> alias.getId() == aliasId).findFirst().orElseThrow();
            assertEquals(List.of(stream.getConfigurationId()), stored.getBroadcastChannels().stream()
                .map(BroadcastChannel::getConfigurationId).toList());
            assertEquals(List.of("New Stream"), stored.getBroadcastChannels().stream()
                .map(BroadcastChannel::getChannelName).toList());
            UnmatchedTalkgroupPolicy storedPolicy = aliasListDefinition(definitions, aliasListId)
                .getUnmatchedTalkgroupPolicy();
            assertEquals(List.of(stream.getConfigurationId()), storedPolicy.getStreamDestinations().stream()
                .map(BroadcastChannel::getConfigurationId).toList());
            assertEquals(List.of("New Stream"), routeNames(storedPolicy));
        }
        finally
        {
            flushAndUnregister(manager);
        }
    }

    @Test
    void failedScanListCommitNeverPublishesDefinitionsOrMemberships() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("scan-list-failure-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        FailingConfigurationManager manager = new FailingConfigurationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25);
            AliasAdministrationService.MutationResult alias = service.createAlias(
                alias("Dispatch", list.aliasListId(), 101));
            long aliasId = alias.aliasIds().getFirst();
            AliasAdministrationService.ScanListMutationResult created = service.createScanList(
                new ScanList(ScanList.UNASSIGNED_ID, 10, "SouthWest", "Southwest calls", true, false));
            long scanListId = created.scanListId();
            AliasAdministrationService.ScanListMutationResult configured = service.updateScanListMemberships(
                scanListId, List.of(aliasId),
                AliasAdministrationService.MembershipOperation.ADD, service.getScanList(scanListId).editRevision());
            ScanListDatabaseStore store = new ScanListDatabaseStore(database);
            ScanListConfiguration baseline = store.loadConfiguration();

            manager.failNextSave();
            assertThrows(AliasAdministrationService.PersistenceException.class, () -> service.updateScanList(
                scanListId, new ScanList(scanListId, 2, "Renamed", "Changed", true, false), service.getScanList(scanListId).editRevision()));
            assertScanListStateEquals(baseline, manager.getScanListModel().configuration());
            assertScanListStateEquals(baseline, store.loadConfiguration());

            manager.failNextSave();
            assertThrows(AliasAdministrationService.PersistenceException.class,
                () -> service.updateScanListMemberships(scanListId, List.of(aliasId),
                    AliasAdministrationService.MembershipOperation.REMOVE, service.getScanList(scanListId).editRevision()));
            assertScanListStateEquals(baseline, manager.getScanListModel().configuration());
            assertScanListStateEquals(baseline, store.loadConfiguration());
            assertEquals(Set.of(aliasId), service.getScanList(scanListId).aliasIds());

            int aliasesBeforeAtomicCreate = manager.getAliasModel().getAliases().size();
            manager.failNextSave();
            assertThrows(AliasAdministrationService.PersistenceException.class,
                () -> service.createAlias(alias("Rejected Atomic", list.aliasListId(), 103), List.of(scanListId)));
            assertEquals(aliasesBeforeAtomicCreate, manager.getAliasModel().getAliases().size());
            assertScanListStateEquals(baseline, manager.getScanListModel().configuration());
            assertScanListStateEquals(baseline, store.loadConfiguration());

            UnmatchedTalkgroupPolicy baselinePolicy = manager.getAliasModel()
                .getAliasListDefinition(list.aliasListId()).getUnmatchedTalkgroupPolicy();
            manager.failNextSave();
            assertThrows(AliasAdministrationService.PersistenceException.class,
                () -> service.updateUnmatchedTalkgroupPolicy(list.aliasListId(),
                    new UnmatchedTalkgroupPolicy(true, List.of()), List.of(scanListId), service.getAliasListDefaults(list.aliasListId()).editRevision()));
            assertEquals(baselinePolicy, manager.getAliasModel().getAliasListDefinition(list.aliasListId())
                .getUnmatchedTalkgroupPolicy());
            assertScanListStateEquals(baseline, manager.getScanListModel().configuration());
            assertScanListStateEquals(baseline, store.loadConfiguration());
        }
        finally
        {
            flushAndUnregister(manager);
        }
    }

    private static void assertStoredDefaults(Path database, List<Long> aliasIds) throws Exception
    {
        AliasDatabaseStore store = new AliasDatabaseStore(database);
        List<AliasListDefinition> definitions = store.loadAliasListDefinitions();
        List<Alias> aliases = store.loadAliases(definitions).stream()
            .filter(alias -> aliasIds.contains(alias.getId())).toList();
        assertEquals(aliasIds.size(), aliases.size());
        assertEquals(List.of(0, 0), aliases.stream().map(Alias::getColor).toList());
        assertEquals(List.of(false, false), aliases.stream().map(Alias::isRecordable).toList());
    }

    private static AliasListDefinition aliasListDefinition(List<AliasListDefinition> definitions, long aliasListId)
    {
        return definitions.stream().filter(definition -> definition.getId() == aliasListId)
            .findFirst().orElseThrow();
    }

    private static void assertScanListStateEquals(ScanListConfiguration expected, ScanListConfiguration actual)
    {
        assertEquals(expected.scanLists(), actual.scanLists());
        assertEquals(expected.aliasMemberships(), actual.aliasMemberships());
        assertEquals(expected.unmatchedAliasListMemberships(), actual.unmatchedAliasListMemberships());
    }

    private static Alias alias(String name, long aliasListId, int talkgroup)
    {
        Alias alias = new Alias(name);
        alias.setAliasListId(aliasListId);
        alias.setAliasListName("County P25");
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, talkgroup));
        return alias;
    }

    private static final class FailingConfigurationManager extends ConfigurationManager
    {
        private final AtomicBoolean mFailNextSave = new AtomicBoolean();

        private FailingConfigurationManager(UserPreferences preferences)
        {
            super(preferences, null, new AliasModel(), null);
        }

        private void failNextSave()
        {
            mFailNextSave.set(true);
        }

        @Override
        public AliasConfigurationSnapshot commitAliasConfiguration(AliasConfigurationSnapshot proposed,
            AliasConfigurationPublication publication)
        {
            if(mFailNextSave.compareAndSet(true, false))
            {
                throw new ConfigurationCommitException("Injected test failure", new IllegalStateException());
            }

            return super.commitAliasConfiguration(proposed, publication);
        }
    }

    private static final class LateChannelConfigurationManager extends ConfigurationManager
    {
        private final AtomicBoolean mAddChannelDuringNextCommit = new AtomicBoolean();

        private LateChannelConfigurationManager(UserPreferences preferences)
        {
            super(preferences, null, new AliasModel(), null);
        }

        private void addChannelDuringNextCommit()
        {
            mAddChannelDuringNextCommit.set(true);
        }

        @Override
        protected AliasConfigurationSnapshot commitAliasConfiguration(AliasConfigurationSnapshot proposed,
            AliasConfigurationPublication publication)
        {
            if(mAddChannelDuringNextCommit.compareAndSet(true, false))
            {
                Channel channel = new Channel("Late Channel");
                channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
                addChannel(channel);
            }
            return super.commitAliasConfiguration(proposed, publication);
        }
    }

    private static final class RecoveringPublicationManager extends ConfigurationManager
    {
        private final AtomicBoolean mFailNextPublication = new AtomicBoolean();

        private RecoveringPublicationManager(UserPreferences preferences)
        {
            super(preferences, null, new AliasModel(), null);
        }

        private void failNextPublication()
        {
            mFailNextPublication.set(true);
        }

        @Override
        protected void publishCommittedAliasConfiguration(AliasConfigurationSnapshot committed,
            AliasConfigurationPublication publication)
        {
            if(mFailNextPublication.compareAndSet(true, false))
            {
                throw new IllegalStateException("Injected publication failure");
            }
            super.publishCommittedAliasConfiguration(committed, publication);
        }
    }

    private static final class UnrecoverablePublicationManager extends ConfigurationManager
    {
        private UnrecoverablePublicationManager(UserPreferences preferences)
        {
            super(preferences, null, new AliasModel(), null);
        }

        @Override
        protected void publishCommittedAliasConfiguration(AliasConfigurationSnapshot committed,
            AliasConfigurationPublication publication)
        {
            throw new IllegalStateException("Injected unrecoverable publication failure");
        }
    }

    private static void flushAndUnregister(ConfigurationManager manager)
    {
        try
        {
            manager.flushConfiguration();
        }
        finally
        {
            manager.quiesceAdministration();
            manager.getChannelProcessingManager().close();
            manager.getBroadcastModel().close();
            manager.close();
            manager.getRadioReferenceDirectoryService().close();
        }
    }

    private static final class TestUserPreferences extends UserPreferences
    {
        private final DirectoryPreference mDirectoryPreference;

        private TestUserPreferences(Path dataRoot)
        {
            mDirectoryPreference = new DirectoryPreference(preferenceType -> {})
            {
                @Override
                public Path getDirectoryApplicationRoot()
                {
                    return dataRoot;
                }
            };
        }

        @Override
        public DirectoryPreference getDirectoryPreference()
        {
            return mDirectoryPreference;
        }
    }
}
