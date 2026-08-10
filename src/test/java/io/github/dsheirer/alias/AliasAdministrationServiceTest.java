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

package io.github.dsheirer.alias;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AliasAdministrationServiceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void persistsValidatedChangesAndUpdatesTheLiveCatalog() throws Exception
    {
        exerciseService();
    }

    @Test
    void sharedServiceAtomicallySavesMixedCreatesAndReplacements() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("mixed-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        CountingConfigurationManager manager = new CountingConfigurationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            assertSame(manager.getAliasAdministrationService(), manager.getAliasAdministrationService());
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            assertEquals(service.catalog().revision(), service.currentRevision());
            long aliasListId = service.createAliasList("County P25", AliasListFamily.P25).aliasListId();
            AliasAdministrationService.MutationResult initial = service.saveAliases(List.of(
                alias("Dispatch", aliasListId, 101), alias("Operations", aliasListId, 102)));
            long dispatchId = initial.aliasIds().getFirst();
            Alias replacement = service.getAlias(dispatchId).alias();
            replacement.setName("Dispatch Updated");
            Alias created = alias("Tactical", aliasListId, 103);
            int flushesBeforeMixedSave = manager.flushCount();

            Alias duplicateReplacement = service.getAlias(dispatchId).alias();
            assertThrows(IllegalArgumentException.class,
                () -> service.saveAliases(List.of(replacement, duplicateReplacement)));
            assertEquals(flushesBeforeMixedSave, manager.flushCount());
            assertEquals("Dispatch", service.getAlias(dispatchId).alias().getName());

            Alias invalidCreate = alias("Invalid", aliasListId + 1000, 104);
            assertThrows(AliasAdministrationService.NotFoundException.class,
                () -> service.saveAliases(List.of(replacement, invalidCreate)));
            assertEquals(flushesBeforeMixedSave, manager.flushCount());
            assertEquals("Dispatch", service.getAlias(dispatchId).alias().getName());

            AliasAdministrationService.MutationResult mixed = service.saveAliases(List.of(replacement, created));

            assertEquals(flushesBeforeMixedSave + 1, manager.flushCount());
            assertEquals(2, mixed.affected());
            assertEquals(2, mixed.aliasIds().size());
            assertEquals(dispatchId, mixed.aliasIds().getFirst());
            assertEquals(2, new HashSet<>(mixed.aliasIds()).size());
            assertEquals("Dispatch Updated", service.getAlias(dispatchId).alias().getName());
            assertEquals(3, manager.getAliasModel().getAliases().size());
            assertEquals(3, new HashSet<>(manager.getAliasModel().getAliases().stream()
                .map(Alias::getId).toList()).size());

            service.deleteAliases(mixed.aliasIds());
            assertEquals(1, manager.getAliasModel().getAliases().size());

            AliasDatabaseStore store = new AliasDatabaseStore(database);
            List<AliasListDefinition> definitions = store.loadAliasListDefinitions();
            assertEquals(1, store.loadAliases(definitions).size());
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    private void exerciseService() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);

        UserPreferences preferences = new TestUserPreferences(dataRoot);
        ConfigurationManager manager = new ConfigurationManager(preferences, null, new AliasModel(), null, null);

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            long emptyRevision = service.catalog().revision();

            AliasAdministrationService.MutationResult createdList = service.createAliasList(
                "County P25", AliasListFamily.P25, emptyRevision);
            long aliasListId = createdList.aliasListId();
            assertNotEquals(AliasListDefinition.UNASSIGNED_ID, aliasListId);
            assertEquals(aliasListId,
                manager.getAliasModel().getAliasListDefinition("County P25").getId());

            AliasAdministrationService.StaleRevisionException stale = assertThrows(
                AliasAdministrationService.StaleRevisionException.class,
                () -> service.createAliasList("Stale List", AliasListFamily.DMR, emptyRevision));
            assertEquals(emptyRevision, stale.getExpected());
            assertEquals(createdList.revision(), stale.getCurrent());
            assertNull(manager.getAliasModel().getAliasListDefinition("Stale List"));

            AliasAdministrationService.MutationResult first = service.createAlias(
                alias("Dispatch", aliasListId, 101), createdList.revision());
            AliasAdministrationService.MutationResult second = service.createAlias(
                alias("Operations", aliasListId, 102), first.revision());
            long firstAliasId = first.aliasIds().getFirst();
            long secondAliasId = second.aliasIds().getFirst();
            assertNotEquals(Alias.UNASSIGNED_ID, firstAliasId);
            assertNotEquals(Alias.UNASSIGNED_ID, secondAliasId);

            Alias retiredCatchAll = alias("Unknown talkgroups", aliasListId, 1);
            retiredCatchAll.setMatchIdentifier(new TalkgroupRange(Protocol.APCO25, 1, 0xFFFF));
            IllegalArgumentException catchAllFailure = assertThrows(IllegalArgumentException.class,
                () -> service.createAlias(retiredCatchAll, second.revision()));
            assertTrue(catchAllFailure.getMessage().contains("Unmatched Talkgroups"));

            BroadcastifyCallConfiguration primary = new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
            primary.setName("Primary");
            manager.getBroadcastModel().addBroadcastConfiguration(primary);
            BroadcastifyCallConfiguration archive = new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
            archive.setName("Archive");
            manager.getBroadcastModel().addBroadcastConfiguration(archive);

            AliasAdministrationService.MutationResult policyChanged = service.updateUnmatchedTalkgroupPolicy(
                aliasListId, new UnmatchedTalkgroupPolicy(io.github.dsheirer.alias.id.priority.Priority.DO_NOT_MONITOR,
                    true, List.of("Primary")), service.catalog().revision());
            UnmatchedTalkgroupPolicy livePolicy = manager.getAliasModel()
                .getAliasListDefinition(aliasListId).getUnmatchedTalkgroupPolicy();
            assertEquals(io.github.dsheirer.alias.id.priority.Priority.DO_NOT_MONITOR,
                livePolicy.getPlaybackPriority());
            assertTrue(livePolicy.isRecordEnabled());
            assertEquals(List.of("Primary"), livePolicy.getStreamDestinationNames());
            assertEquals(livePolicy, service.catalog().aliasLists().getFirst().getUnmatchedTalkgroupPolicy());
            assertThrows(IllegalArgumentException.class, () -> service.updateUnmatchedTalkgroupPolicy(aliasListId,
                new UnmatchedTalkgroupPolicy(100, false, List.of("Missing")), policyChanged.revision()));

            long bulkRevision = service.catalog().revision();
            AliasAdministrationService.MutationResult configured = service.bulkEdit(
                new AliasAdministrationService.BulkEdit(List.of(firstAliasId, secondAliasId), null, null, null,
                    null, null, AliasAdministrationService.GroupOperation.SET, "Fire Dispatch",
                    AliasAdministrationService.StreamOperation.ADD, List.of("Primary"), false), bulkRevision);
            configured = service.bulkEdit(new AliasAdministrationService.BulkEdit(
                List.of(firstAliasId, secondAliasId), null, null, null, null, null, null, null,
                AliasAdministrationService.StreamOperation.ADD, List.of("Archive"), false), configured.revision());
            configured = service.bulkEdit(new AliasAdministrationService.BulkEdit(
                List.of(firstAliasId, secondAliasId), null, null, null, null, null, null, null,
                AliasAdministrationService.StreamOperation.REMOVE, List.of("Primary"), false), configured.revision());

            for(long aliasId: List.of(firstAliasId, secondAliasId))
            {
                Alias liveAlias = service.getAlias(aliasId).alias();
                assertEquals("Fire Dispatch", liveAlias.getGroup());
                assertEquals(List.of("Archive"), liveAlias.getBroadcastChannels().stream()
                    .map(BroadcastChannel::getChannelName).toList());
            }

            configured = service.bulkEdit(new AliasAdministrationService.BulkEdit(
                List.of(firstAliasId, secondAliasId), null, null, null, null, null,
                AliasAdministrationService.GroupOperation.CLEAR, null,
                AliasAdministrationService.StreamOperation.REPLACE, List.of("Primary"), false),
                configured.revision());
            configured = service.bulkEdit(new AliasAdministrationService.BulkEdit(
                List.of(firstAliasId, secondAliasId), null, null, null, null, null, null, null,
                AliasAdministrationService.StreamOperation.CLEAR, null, false), configured.revision());
            assertNull(service.getAlias(firstAliasId).alias().getGroup());
            assertTrue(service.getAlias(firstAliasId).alias().getBroadcastChannels().isEmpty());

            Alias staleReference = findAlias(manager, firstAliasId);
            staleReference.setIconName("Deleted icon");
            staleReference.addBroadcastChannel("Deleted stream");
            Alias unrelatedEdit = service.getAlias(firstAliasId).alias();
            unrelatedEdit.setDescription("Unrelated edit");
            AliasAdministrationService.MutationResult preserved = service.replaceAlias(firstAliasId, unrelatedEdit,
                service.catalog().revision());
            assertEquals("Deleted icon", service.getAlias(firstAliasId).alias().getIconName());
            assertTrue(service.getAlias(firstAliasId).alias().hasBroadcastChannel("Deleted stream"));

            Alias invalidReference = service.getAlias(firstAliasId).alias();
            invalidReference.addBroadcastChannel("Never configured");
            assertThrows(IllegalArgumentException.class, () ->
                service.replaceAlias(firstAliasId, invalidReference, preserved.revision()));

            Alias repaired = service.getAlias(firstAliasId).alias();
            repaired.setIconName(null);
            repaired.setBroadcastChannels(List.of());
            service.replaceAlias(firstAliasId, repaired, preserved.revision());

            Alias desktopEdited = findAlias(manager, firstAliasId);
            long beforeDesktopEdit = service.catalog().revision();
            desktopEdited.setDescription("Edited on desktop");
            assertNotEquals(beforeDesktopEdit, service.catalog().revision());
            assertThrows(AliasAdministrationService.StaleRevisionException.class,
                () -> service.replaceAlias(firstAliasId, alias("Stale overwrite", aliasListId, 101),
                    beforeDesktopEdit));
            assertEquals("Edited on desktop", desktopEdited.getDescription());

            AliasList liveList = manager.getAliasModel().getAliasList("County P25");
            assertEquals(firstAliasId,
                liveList.getAliases(APCO25Talkgroup.createAny(101)).getFirst().getId());

            AliasDatabaseStore aliasStore = new AliasDatabaseStore(database);
            List<AliasListDefinition> storedDefinitions = aliasStore.loadAliasListDefinitions();
            List<Alias> storedAliases = aliasStore.loadAliases(storedDefinitions);
            assertEquals(1, storedDefinitions.size());
            assertEquals(aliasListId, storedDefinitions.getFirst().getId());
            assertEquals(livePolicy, storedDefinitions.getFirst().getUnmatchedTalkgroupPolicy());
            assertEquals(2, storedAliases.size());
            assertTrue(storedAliases.stream().anyMatch(alias -> alias.getId() == firstAliasId));
            assertTrue(storedAliases.stream().anyMatch(alias -> alias.getId() == secondAliasId));

            Channel channel = new Channel("County Control");
            channel.setAliasListName("County P25");
            channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
            long beforeChannelAssignment = service.catalog().revision();
            manager.getChannelModel().addChannel(channel);
            assertNotEquals(beforeChannelAssignment, service.catalog().revision());

            long beforeBulk = service.catalog().revision();
            AliasAdministrationService.MutationResult bulk = service.bulkEdit(
                new AliasAdministrationService.BulkEdit(List.of(firstAliasId, secondAliasId), null,
                    0x123456, null, null, true, null, null, null, null, false), beforeBulk);
            assertNotEquals(beforeBulk, bulk.revision());

            for(long aliasId: List.of(firstAliasId, secondAliasId))
            {
                Alias liveAlias = service.getAlias(aliasId).alias();
                assertEquals(0x123456, liveAlias.getColor());
                assertTrue(liveAlias.isRecordable());
            }

            storedDefinitions = aliasStore.loadAliasListDefinitions();
            storedAliases = aliasStore.loadAliases(storedDefinitions);
            assertTrue(storedAliases.stream().allMatch(Alias::isRecordable));

            AliasAdministrationService.DeleteImpact impact = service.aliasListDeleteImpact(aliasListId);
            assertEquals(2, impact.aliasCount());
            assertEquals(1, impact.channelCount());
            AliasAdministrationService.ConfirmationRequiredException confirmation = assertThrows(
                AliasAdministrationService.ConfirmationRequiredException.class,
                () -> service.deleteAliasList(aliasListId, impact.revision(), false));
            assertEquals(impact, confirmation.getImpact());
            assertEquals(2, manager.getAliasModel().getAliases().size());

            AliasAdministrationService.MutationResult deleted = service.deleteAliasList(
                aliasListId, impact.revision(), true);
            assertEquals(2, deleted.affected());
            assertTrue(manager.getAliasModel().getAliases().isEmpty());
            assertNull(manager.getAliasModel().getAliasListDefinition(aliasListId));
            assertNull(channel.getAliasListName());
            assertTrue(liveList.getAliases(APCO25Talkgroup.createAny(101)).isEmpty());

            assertTrue(aliasStore.loadAliasListDefinitions().isEmpty());
            assertTrue(aliasStore.loadAliases(List.of()).isEmpty());
            List<Channel> storedChannels = new ConfigurationDatabaseStore(database)
                .loadConfigurationState().getChannels();
            assertEquals(1, storedChannels.size());
            assertNull(storedChannels.getFirst().getAliasListName());
            assertTrue(storedChannels.getFirst().hasRadresGuid());
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    private static Alias alias(String name, long aliasListId, int talkgroup)
    {
        Alias alias = new Alias(name);
        alias.setAliasListId(aliasListId);
        alias.setAliasListName("County P25");
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, talkgroup));
        return alias;
    }

    private static Alias findAlias(ConfigurationManager manager, long aliasId)
    {
        return manager.getAliasModel().getAliases().stream().filter(alias -> alias.getId() == aliasId).findFirst()
            .orElseThrow();
    }

    private static class TestUserPreferences extends UserPreferences
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

    private static final class CountingConfigurationManager extends ConfigurationManager
    {
        private final AtomicInteger mFlushCount = new AtomicInteger();

        private CountingConfigurationManager(UserPreferences preferences)
        {
            super(preferences, null, new AliasModel(), null, null);
        }

        @Override
        public void flushConfiguration()
        {
            mFlushCount.incrementAndGet();
            super.flushConfiguration();
        }

        private int flushCount()
        {
            return mFlushCount.get();
        }
    }
}
