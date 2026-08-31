/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.UnmatchedTalkgroupPolicy;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.database.scanlist.ScanListDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasAdministrationPersistenceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void customAliasListStartsWithoutPersistedUnmatchedScanListRouting() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("custom-list-default-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            long aliasListId = service.createAliasList("County P25", AliasListFamily.P25,
                service.currentRevision()).aliasListId();

            assertTrue(service.getAliasListDefaults(aliasListId).defaults().scanListIds().isEmpty());
            assertTrue(new ScanListDatabaseStore(database).loadConfiguration()
                .scanListIdsForUnmatchedTalkgroups(aliasListId).isEmpty());
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void createCommitsCanonicalIdentityBeforePublishingTheAlias() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("publish-after-commit-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25, service.currentRevision());
            AliasDatabaseStore store = new AliasDatabaseStore(database);
            AtomicInteger additions = new AtomicInteger();
            AtomicReference<Alias> published = new AtomicReference<>();
            AtomicReference<Boolean> rowWasCommitted = new AtomicReference<>(false);
            AtomicReference<Throwable> listenerFailure = new AtomicReference<>();

            manager.getAliasModel().aliasList().addListener((ListChangeListener<Alias>)change ->
            {
                while(change.next())
                {
                    for(Alias added: change.getAddedSubList())
                    {
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
                    }
                }
            });

            AliasAdministrationService.MutationResult created = service.createAlias(
                alias("Dispatch", list.aliasListId(), 101), list.revision());
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
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void aliasWithMembershipPublishesRoutingBeforeTheAlias() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("membership-before-alias-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25, service.currentRevision());
            AliasAdministrationService.ScanListMutationResult scanList = service.createScanList(
                new ScanList(ScanList.UNASSIGNED_ID, 1, "Dispatch", null, true, false), list.revision());
            AtomicBoolean membershipVisible = new AtomicBoolean();

            manager.getAliasModel().aliasList().addListener((ListChangeListener<Alias>)change ->
            {
                while(change.next())
                {
                    for(Alias added: change.getAddedSubList())
                    {
                        membershipVisible.set(manager.getScanListModel().scanListIdsForAlias(added.getId())
                            .contains(scanList.scanListId()));
                    }
                }
            });

            service.createAlias(alias("Dispatch", list.aliasListId(), 101), List.of(scanList.scanListId()),
                scanList.revision());

            assertTrue(membershipVisible.get(), "Alias became visible before its committed scan-list routing");
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void delayedChannelEditAfterCandidateCaptureIsNotDiscarded() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("late-channel-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        LateChannelConfigurationManager manager =
            new LateChannelConfigurationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            manager.addChannelDuringNextCommit();
            service.createAliasList("County P25", AliasListFamily.P25, service.currentRevision());

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
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void channelEditObservedDuringAliasPublicationIsStillSaved() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("publication-channel-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25, service.currentRevision());
            manager.getAliasModel().aliasList().addListener((ListChangeListener<Alias>)change ->
            {
                while(change.next())
                {
                    if(change.wasAdded())
                    {
                        manager.getChannelModel().addChannel(new Channel("Publication Channel"));
                    }
                }
            });

            service.createAlias(alias("Dispatch", list.aliasListId(), 101), list.revision());
            manager.flushConfiguration();

            assertEquals(List.of("Publication Channel"),
                new ConfigurationDatabaseStore(database).load().channels().stream()
                    .map(Channel::getName).toList());
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void publicationFailureReloadsTheCommittedCanonicalState() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("publication-recovery-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        RecoveringPublicationManager manager = new RecoveringPublicationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25, service.currentRevision());
            manager.failNextPublication();

            long aliasId = service.createAlias(alias("Dispatch", list.aliasListId(), 101), list.revision())
                .aliasIds().getFirst();

            assertEquals(aliasId, manager.getAliasModel().getAliases().getFirst().getId());
            List<AliasListDefinition> definitions = new AliasDatabaseStore(database).loadAliasListDefinitions();
            assertEquals(aliasId, new AliasDatabaseStore(database).loadAliases(definitions).getFirst().getId());
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void unrecoverablePublicationFailureRejectsLaterCommandsWithRestartError() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("fatal-publication-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        UnrecoverablePublicationManager manager =
            new UnrecoverablePublicationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            ConfigurationManager.ConfigurationPublicationException initial = assertThrows(
                ConfigurationManager.ConfigurationPublicationException.class,
                () -> service.createAliasList("County P25", AliasListFamily.P25, service.currentRevision()));
            assertTrue(initial.getMessage().contains("restart"));
            assertEquals(AliasListFamily.values().length + 1,
                new AliasDatabaseStore(database).loadAliasListDefinitions().size());

            ConfigurationManager.ConfigurationPublicationException later = assertThrows(
                ConfigurationManager.ConfigurationPublicationException.class,
                () -> service.createAliasList("Other P25", AliasListFamily.P25, service.currentRevision()));
            assertTrue(later.getMessage().contains("restart"));
            assertEquals(AliasListFamily.values().length + 1,
                new AliasDatabaseStore(database).loadAliasListDefinitions().size());
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void failedCommitNeverPublishesTheCandidateOrChangesStoredRows() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        FailingConfigurationManager manager = new FailingConfigurationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25, service.catalog().revision());
            AliasAdministrationService.MutationResult first = service.createAlias(
                alias("Dispatch", list.aliasListId(), 101), list.revision());
            AliasAdministrationService.MutationResult second = service.createAlias(
                alias("Operations", list.aliasListId(), 102), first.revision());
            List<Long> aliasIds = List.of(first.aliasIds().getFirst(), second.aliasIds().getFirst());
            AtomicInteger publications = new AtomicInteger();
            manager.getAliasModel().aliasList().addListener((ListChangeListener<Alias>)change ->
            {
                while(change.next())
                {
                    if(change.wasAdded() || change.wasRemoved() || change.wasUpdated())
                    {
                        publications.incrementAndGet();
                    }
                }
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
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void failedMixedCommandsNeverPublishAndSuccessfulRenamePersists() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("mixed-failure-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        FailingConfigurationManager manager = new FailingConfigurationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            BroadcastifyCallConfiguration oldStream = new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
            oldStream.setName("Old Stream");
            manager.getBroadcastModel().addBroadcastConfiguration(oldStream);
            long aliasListId = service.createAliasList("County P25", AliasListFamily.P25).aliasListId();
            Alias original = alias("Dispatch", aliasListId, 101);
            original.addBroadcastChannel("Old Stream");
            long aliasId = service.createAlias(original,
                Set.of(manager.getScanListModel().defaultScanList().getId()), service.currentRevision())
                .aliasIds().getFirst();
            long secondAliasId = service.createAlias(alias("Secondary", aliasListId, 101)).aliasIds().getFirst();
            AliasList cachedAliasList = manager.getAliasModel().getAliasList("County P25");
            assertEquals(secondAliasId,
                cachedAliasList.getAliases(APCO25Talkgroup.create(101)).getFirst().getId());
            service.updateUnmatchedTalkgroupPolicy(aliasListId,
                new UnmatchedTalkgroupPolicy(false, List.of("Old Stream")), service.currentRevision());

            Alias replacement = service.getAlias(aliasId).alias();
            replacement.setName("Changed");
            manager.failNextSave();
            assertThrows(AliasAdministrationService.PersistenceException.class,
                () -> service.saveAliases(List.of(replacement, alias("New Alias", aliasListId, 102))));

            assertEquals(List.of(aliasId, secondAliasId), manager.getAliasModel().getAliases().stream()
                .map(Alias::getId).toList());
            assertEquals("Dispatch", service.getAlias(aliasId).alias().getName());
            assertEquals(secondAliasId,
                cachedAliasList.getAliases(APCO25Talkgroup.create(101)).getFirst().getId());

            manager.failNextSave();
            assertThrows(AliasAdministrationService.PersistenceException.class,
                () -> service.deleteAliases(List.of(aliasId)));
            assertEquals(List.of(aliasId, secondAliasId), manager.getAliasModel().getAliases().stream()
                .map(Alias::getId).toList());
            assertEquals(secondAliasId,
                cachedAliasList.getAliases(APCO25Talkgroup.create(101)).getFirst().getId());

            manager.failNextSave();
            assertThrows(AliasAdministrationService.PersistenceException.class,
                () -> service.renameBroadcastChannelReferences("Old Stream", "New Stream"));

            assertEquals("Old Stream", oldStream.getName());
            Alias restored = service.getAlias(aliasId).alias();
            assertTrue(restored.hasBroadcastChannel("Old Stream"));
            assertFalse(restored.hasBroadcastChannel("New Stream"));
            assertEquals(List.of("Old Stream"), manager.getAliasModel().getAliasListDefinition(aliasListId)
                .getUnmatchedTalkgroupPolicy().getStreamDestinationNames());

            AliasDatabaseStore store = new AliasDatabaseStore(database);
            List<AliasListDefinition> definitions = store.loadAliasListDefinitions();
            Alias stored = store.loadAliases(definitions).getFirst();
            assertEquals(List.of("Old Stream"), stored.getBroadcastChannels().stream()
                .map(BroadcastChannel::getChannelName).toList());
            assertEquals(List.of("Old Stream"), aliasListDefinition(definitions, aliasListId)
                .getUnmatchedTalkgroupPolicy()
                .getStreamDestinationNames());

            service.renameBroadcastChannelReferences("Old Stream", "New Stream");
            assertEquals("New Stream", oldStream.getName());
            Alias renamed = service.getAlias(aliasId).alias();
            assertFalse(renamed.hasBroadcastChannel("Old Stream"));
            assertTrue(renamed.hasBroadcastChannel("New Stream"));
            assertEquals(List.of("New Stream"), manager.getAliasModel().getAliasListDefinition(aliasListId)
                .getUnmatchedTalkgroupPolicy().getStreamDestinationNames());

            definitions = store.loadAliasListDefinitions();
            stored = store.loadAliases(definitions).getFirst();
            assertEquals(List.of("New Stream"), stored.getBroadcastChannels().stream()
                .map(BroadcastChannel::getChannelName).toList());
            assertEquals(List.of("New Stream"), aliasListDefinition(definitions, aliasListId)
                .getUnmatchedTalkgroupPolicy()
                .getStreamDestinationNames());
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void broadcastRenameCommitsTheStreamAndAliasReferencesTogether() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("broadcast-rename-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            BroadcastifyCallConfiguration stream = new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
            stream.setName("Old Stream");
            manager.getBroadcastModel().addBroadcastConfiguration(stream);
            manager.flushConfiguration();

            long aliasListId = service.createAliasList("County P25", AliasListFamily.P25).aliasListId();
            Alias proposed = alias("Dispatch", aliasListId, 101);
            proposed.addBroadcastChannel("Old Stream");
            long aliasId = service.createAlias(proposed,
                Set.of(manager.getScanListModel().defaultScanList().getId()), service.currentRevision())
                .aliasIds().getFirst();
            service.updateUnmatchedTalkgroupPolicy(aliasListId,
                new UnmatchedTalkgroupPolicy(false, List.of("Old Stream")), service.currentRevision());

            assertEquals("Old Stream", stream.getName());
            service.renameBroadcastChannelReferences("Old Stream", "New Stream");

            assertEquals("New Stream", stream.getName());
            assertEquals(List.of("New Stream"), new ConfigurationDatabaseStore(database).load()
                .broadcastConfigurations().stream().map(configuration -> configuration.getName()).toList());
            List<AliasListDefinition> definitions = new AliasDatabaseStore(database).loadAliasListDefinitions();
            Alias stored = new AliasDatabaseStore(database).loadAliases(definitions).stream()
                .filter(alias -> alias.getId() == aliasId).findFirst().orElseThrow();
            assertEquals(List.of("New Stream"), stored.getBroadcastChannels().stream()
                .map(BroadcastChannel::getChannelName).toList());
            assertEquals(List.of("New Stream"), aliasListDefinition(definitions, aliasListId)
                .getUnmatchedTalkgroupPolicy()
                .getStreamDestinationNames());
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void failedScanListCommitNeverPublishesDefinitionsOrMemberships() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("scan-list-failure-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        FailingConfigurationManager manager = new FailingConfigurationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25, service.currentRevision());
            AliasAdministrationService.MutationResult alias = service.createAlias(
                alias("Dispatch", list.aliasListId(), 101), list.revision());
            long aliasId = alias.aliasIds().getFirst();
            AliasAdministrationService.ScanListMutationResult created = service.createScanList(
                new ScanList(ScanList.UNASSIGNED_ID, 10, "SouthWest", "Southwest calls", true, false),
                alias.revision());
            long scanListId = created.scanListId();
            AliasAdministrationService.ScanListMutationResult configured = service.updateScanListMemberships(
                scanListId, List.of(aliasId),
                AliasAdministrationService.MembershipOperation.ADD, created.revision());
            ScanListDatabaseStore store = new ScanListDatabaseStore(database);
            ScanListConfiguration baseline = store.loadConfiguration();

            manager.failNextSave();
            assertThrows(AliasAdministrationService.PersistenceException.class, () -> service.updateScanList(
                scanListId, new ScanList(scanListId, 2, "Renamed", "Changed", true, false),
                configured.revision()));
            assertScanListStateEquals(baseline, manager.getScanListModel().configuration());
            assertScanListStateEquals(baseline, store.loadConfiguration());

            manager.failNextSave();
            assertThrows(AliasAdministrationService.PersistenceException.class,
                () -> service.updateScanListMemberships(scanListId, List.of(aliasId),
                    AliasAdministrationService.MembershipOperation.REMOVE, service.currentRevision()));
            assertScanListStateEquals(baseline, manager.getScanListModel().configuration());
            assertScanListStateEquals(baseline, store.loadConfiguration());
            assertEquals(Set.of(aliasId), service.getScanList(scanListId).aliasIds());

            int aliasesBeforeAtomicCreate = manager.getAliasModel().getAliases().size();
            manager.failNextSave();
            assertThrows(AliasAdministrationService.PersistenceException.class,
                () -> service.createAlias(alias("Rejected Atomic", list.aliasListId(), 103), List.of(scanListId),
                    service.currentRevision()));
            assertEquals(aliasesBeforeAtomicCreate, manager.getAliasModel().getAliases().size());
            assertScanListStateEquals(baseline, manager.getScanListModel().configuration());
            assertScanListStateEquals(baseline, store.loadConfiguration());

            UnmatchedTalkgroupPolicy baselinePolicy = manager.getAliasModel()
                .getAliasListDefinition(list.aliasListId()).getUnmatchedTalkgroupPolicy();
            manager.failNextSave();
            assertThrows(AliasAdministrationService.PersistenceException.class,
                () -> service.updateUnmatchedTalkgroupPolicy(list.aliasListId(),
                    new UnmatchedTalkgroupPolicy(true, List.of()), List.of(scanListId),
                    service.currentRevision()));
            assertEquals(baselinePolicy, manager.getAliasModel().getAliasListDefinition(list.aliasListId())
                .getUnmatchedTalkgroupPolicy());
            assertScanListStateEquals(baseline, manager.getScanListModel().configuration());
            assertScanListStateEquals(baseline, store.loadConfiguration());
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
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
            super(preferences, null, new AliasModel(), null, null);
        }

        private void failNextSave()
        {
            mFailNextSave.set(true);
        }

        @Override
        public AliasConfigurationSnapshot commitAliasConfiguration(AliasConfigurationSnapshot proposed,
            AliasConfigurationPublication publication, BroadcastConfigurationRename broadcastRename)
        {
            if(mFailNextSave.compareAndSet(true, false))
            {
                throw new ConfigurationCommitException("Injected test failure", new IllegalStateException());
            }

            return super.commitAliasConfiguration(proposed, publication, broadcastRename);
        }
    }

    private static final class LateChannelConfigurationManager extends ConfigurationManager
    {
        private final AtomicBoolean mAddChannelDuringNextCommit = new AtomicBoolean();

        private LateChannelConfigurationManager(UserPreferences preferences)
        {
            super(preferences, null, new AliasModel(), null, null);
        }

        private void addChannelDuringNextCommit()
        {
            mAddChannelDuringNextCommit.set(true);
        }

        @Override
        protected AliasConfigurationSnapshot commitAliasConfiguration(AliasConfigurationSnapshot proposed,
            AliasConfigurationPublication publication, BroadcastConfigurationRename broadcastRename)
        {
            if(mAddChannelDuringNextCommit.compareAndSet(true, false))
            {
                getChannelModel().addChannel(new Channel("Late Channel"));
            }
            return super.commitAliasConfiguration(proposed, publication, broadcastRename);
        }
    }

    private static final class RecoveringPublicationManager extends ConfigurationManager
    {
        private final AtomicBoolean mFailNextPublication = new AtomicBoolean();

        private RecoveringPublicationManager(UserPreferences preferences)
        {
            super(preferences, null, new AliasModel(), null, null);
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
            super(preferences, null, new AliasModel(), null, null);
        }

        @Override
        protected void publishCommittedAliasConfiguration(AliasConfigurationSnapshot committed,
            AliasConfigurationPublication publication)
        {
            throw new IllegalStateException("Injected unrecoverable publication failure");
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
