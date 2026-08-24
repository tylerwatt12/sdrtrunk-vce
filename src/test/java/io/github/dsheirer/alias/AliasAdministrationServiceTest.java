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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.alias.id.talkgroup.TalkgroupRange;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.database.scanlist.ScanListDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanList;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
            int commitsBeforeMixedSave = manager.commitCount();

            Alias duplicateReplacement = service.getAlias(dispatchId).alias();
            assertThrows(IllegalArgumentException.class,
                () -> service.saveAliases(List.of(replacement, duplicateReplacement)));
            assertEquals(commitsBeforeMixedSave, manager.commitCount());
            assertEquals("Dispatch", service.getAlias(dispatchId).alias().getName());

            Alias invalidCreate = alias("Invalid", aliasListId + 1000, 104);
            assertThrows(AliasAdministrationService.NotFoundException.class,
                () -> service.saveAliases(List.of(replacement, invalidCreate)));
            assertEquals(commitsBeforeMixedSave, manager.commitCount());
            assertEquals("Dispatch", service.getAlias(dispatchId).alias().getName());

            AliasAdministrationService.MutationResult mixed = service.saveAliases(List.of(replacement, created));

            assertEquals(commitsBeforeMixedSave + 1, manager.commitCount());
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

    @Test
    void rejectsBroadcastRouteStateBeyondTheSharedServiceBound() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("broadcast-bound-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        CountingConfigurationManager manager = new CountingConfigurationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            long aliasListId = service.createAliasList("County P25", AliasListFamily.P25).aliasListId();
            long aliasId = service.createAlias(alias("Dispatch", aliasListId, 101)).aliasIds().getFirst();
            List<String> routes = new ArrayList<>();

            for(int index = 0; index <= AliasAdministrationService.MAX_BROADCAST_CHANNELS; index++)
            {
                String name = "Route " + index;
                BroadcastifyCallConfiguration route = new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
                route.setName(name);
                manager.getBroadcastModel().addBroadcastConfiguration(route);
                routes.add(name);
            }

            Alias bounded = service.getAlias(aliasId).alias();
            bounded.setBroadcastChannels(routes.subList(0, AliasAdministrationService.MAX_BROADCAST_CHANNELS)
                .stream().map(BroadcastChannel::new).toList());
            AliasAdministrationService.MutationResult configured = service.replaceAlias(aliasId, bounded);
            int commitsBeforeOverflow = manager.commitCount();

            IllegalArgumentException overflow = assertThrows(IllegalArgumentException.class,
                () -> service.bulkEdit(new AliasAdministrationService.BulkEdit(List.of(aliasId), null, null,
                    null, null, null, null, AliasAdministrationService.StreamOperation.ADD,
                    List.of(routes.getLast()), false), configured.revision()));
            assertTrue(overflow.getMessage().contains("more than " +
                AliasAdministrationService.MAX_BROADCAST_CHANNELS));
            assertEquals(commitsBeforeOverflow, manager.commitCount());
            assertEquals(AliasAdministrationService.MAX_BROADCAST_CHANNELS,
                service.getAlias(aliasId).alias().getBroadcastChannels().size());

            Alias longName = service.getAlias(aliasId).alias();
            longName.setBroadcastChannels(List.of(new BroadcastChannel(
                "x".repeat(AliasAdministrationService.MAX_BROADCAST_CHANNEL_NAME_LENGTH + 1))));
            assertThrows(IllegalArgumentException.class, () -> service.replaceAlias(aliasId, longName));
            assertEquals(commitsBeforeOverflow, manager.commitCount());
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void managesScanListLifecycleMembershipsAndOwnerCleanup() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("scan-list-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            ScanList initialDefault = manager.getScanListModel().defaultScanList();
            assertNotEquals(ScanList.UNASSIGNED_ID, initialDefault.getId());

            AliasAdministrationService.MutationResult countyList = service.createAliasList(
                "County P25", AliasListFamily.P25, service.currentRevision());
            AliasAdministrationService.MutationResult metroList = service.createAliasList(
                "Metro P25", AliasListFamily.P25, countyList.revision());
            long countyListId = countyList.aliasListId();
            long metroListId = metroList.aliasListId();
            AliasAdministrationService.MutationResult dispatch = service.createAlias(
                alias("Dispatch", countyListId, "County P25", 101), metroList.revision());
            AliasAdministrationService.MutationResult operations = service.createAlias(
                alias("Operations", countyListId, "County P25", 102), dispatch.revision());
            AliasAdministrationService.MutationResult tactical = service.createAlias(
                alias("Tactical", metroListId, "Metro P25", 201), operations.revision());
            long dispatchId = dispatch.aliasIds().getFirst();
            long operationsId = operations.aliasIds().getFirst();
            long tacticalId = tactical.aliasIds().getFirst();

            ScanList southwest = new ScanList(ScanList.UNASSIGNED_ID, 10, "SouthWest",
                "Southwest calls", true, false);
            AliasAdministrationService.ScanListMutationResult southwestCreated = service.createScanList(southwest,
                tactical.revision());
            long southwestId = southwestCreated.scanListId();
            AliasAdministrationService.ScanListMutationResult clevelandCreated = service.createScanList(
                new ScanList(ScanList.UNASSIGNED_ID, 20, "Cleveland", null, true, false),
                southwestCreated.revision());
            long clevelandId = clevelandCreated.scanListId();

            AliasAdministrationService.ScanListMutationResult clevelandUpdated = service.updateScanList(clevelandId,
                new ScanList(clevelandId, 5, "Cleveland", "Citywide calls", true, true),
                clevelandCreated.revision());
            assertEquals("Cleveland", manager.getScanListModel().defaultScanList().getName());
            assertFalse(manager.getScanListModel().scanList(initialDefault.getId()).isDefault());
            assertThrows(IllegalArgumentException.class,
                () -> service.deleteScanList(clevelandId, clevelandUpdated.revision()));
            AliasAdministrationService.ScanListMutationResult oldDefaultDeleted = service.deleteScanList(
                initialDefault.getId(), service.currentRevision());
            assertNull(manager.getScanListModel().scanList(initialDefault.getId()));

            AliasAdministrationService.ScanListMutationResult southwestAdded = service.updateScanListMemberships(
                southwestId, List.of(dispatchId, operationsId),
                List.of(countyListId),
                AliasAdministrationService.MembershipOperation.ADD, oldDefaultDeleted.revision());
            assertEquals(Set.of(dispatchId, operationsId), service.getScanList(southwestId).aliasIds());
            assertEquals(Set.of(countyListId), service.getScanList(southwestId).unmatchedAliasListIds());

            AliasAdministrationService.ScanListMutationResult clevelandAdded = service.updateScanListMemberships(
                clevelandId, List.of(dispatchId, tacticalId),
                List.of(metroListId),
                AliasAdministrationService.MembershipOperation.ADD, southwestAdded.revision());
            AliasAdministrationService.ScanListMutationResult southwestRemoved = service.updateScanListMemberships(
                southwestId, List.of(operationsId),
                List.of(countyListId),
                AliasAdministrationService.MembershipOperation.REMOVE, clevelandAdded.revision());
            assertEquals(Set.of(dispatchId), service.getScanList(southwestId).aliasIds());
            assertTrue(service.getScanList(southwestId).unmatchedAliasListIds().isEmpty());

            AliasAdministrationService.ScanListMutationResult southwestReplaced = service.updateScanListMemberships(
                southwestId, List.of(operationsId, tacticalId),
                List.of(countyListId),
                AliasAdministrationService.MembershipOperation.REPLACE, southwestRemoved.revision());
            AliasAdministrationService.ScanListEntry replaced = service.getScanList(southwestId);
            assertEquals(Set.of(operationsId, tacticalId), replaced.aliasIds());
            assertEquals(Set.of(countyListId), replaced.unmatchedAliasListIds());
            AliasAdministrationService.ScanListSummary southwestSummary = service.scanListCatalog().scanLists()
                .stream().filter(summary -> summary.scanList().getId() == southwestId).findFirst().orElseThrow();
            assertEquals(2, southwestSummary.aliasCount());
            assertEquals(1, southwestSummary.unmatchedAliasListCount());

            AliasAdministrationService.ScanListCoverage southwestCoverage =
                service.scanListCoverage(southwestId);
            assertEquals(southwestId, southwestCoverage.scanList().getId());
            assertEquals(2, southwestCoverage.aliasCount());
            assertFalse(southwestCoverage.truncated());
            assertEquals(List.of(operationsId, tacticalId), southwestCoverage.aliases().stream()
                .map(AliasAdministrationService.ScanListCoverageAlias::aliasId).toList());
            assertTrue(southwestCoverage.aliases().stream().allMatch(alias -> alias.matcherType() != null &&
                alias.matcher() != null));
            assertEquals(List.of(countyListId), southwestCoverage.unmatchedAliasLists().stream()
                .map(AliasAdministrationService.ScanListCoverageAliasList::aliasListId).toList());

            ScanListDatabaseStore scanListStore = new ScanListDatabaseStore(database);
            ScanListConfiguration workflowStored = scanListStore.loadConfiguration();
            assertEquals(List.of("Cleveland", "SouthWest"), workflowStored.scanLists().stream()
                .map(ScanList::getName).toList());
            assertEquals(Set.of(clevelandId), workflowStored.scanListIdsForAlias(dispatchId));
            assertEquals(Set.of(southwestId), workflowStored.scanListIdsForAlias(operationsId));
            assertEquals(Set.of(southwestId, clevelandId), workflowStored.scanListIdsForAlias(tacticalId));
            assertEquals(Set.of(southwestId), workflowStored.scanListIdsForUnmatchedTalkgroups(countyListId));
            assertEquals(Set.of(clevelandId), workflowStored.scanListIdsForUnmatchedTalkgroups(metroListId));

            Alias unrelatedEdit = service.getAlias(tacticalId).alias();
            unrelatedEdit.setDescription("Unrelated Alias edit");
            AliasAdministrationService.MutationResult aliasUpdated = service.replaceAlias(tacticalId, unrelatedEdit,
                southwestReplaced.revision());
            assertEquals(Set.of(southwestId, clevelandId), service.getAlias(tacticalId).scanListIds());

            AliasAdministrationService.MutationResult aliasDeleted = service.deleteAlias(operationsId,
                aliasUpdated.revision());
            assertEquals(Set.of(tacticalId), service.getScanList(southwestId).aliasIds());

            AliasAdministrationService.MutationResult aliasListDeleted = service.deleteAliasList(metroListId,
                aliasDeleted.revision(), true);
            assertTrue(service.getScanList(southwestId).aliasIds().isEmpty());
            assertEquals(Set.of(dispatchId), service.getScanList(clevelandId).aliasIds());
            assertTrue(service.getScanList(clevelandId).unmatchedAliasListIds().isEmpty());

            AliasAdministrationService.ScanListMutationResult southwestRefilled = service.updateScanListMemberships(
                southwestId, List.of(dispatchId),
                AliasAdministrationService.MembershipOperation.ADD, aliasListDeleted.revision());
            service.deleteScanList(southwestId, southwestRefilled.revision());
            assertEquals(Set.of(clevelandId), service.getAlias(dispatchId).scanListIds());

            ScanListConfiguration stored = scanListStore.loadConfiguration();
            assertEquals(List.of(clevelandId), stored.scanLists().stream().map(ScanList::getId).toList());
            assertTrue(stored.defaultScanList().isDefault());
            assertEquals(Set.of(clevelandId), stored.scanListIdsForAlias(dispatchId));
            assertTrue(stored.scanListIdsForUnmatchedTalkgroups(countyListId).isEmpty());
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void createsAliasAndInitialScanListMembershipsInOneCommit() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("atomic-alias-membership-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        CountingConfigurationManager manager = new CountingConfigurationManager(new TestUserPreferences(dataRoot));

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25, service.currentRevision());
            long defaultScanListId = manager.getScanListModel().defaultScanList().getId();
            AliasAdministrationService.MutationResult retired = service.createAlias(
                alias("Retired", list.aliasListId(), "County P25", 100), list.revision());
            long retiredAliasId = retired.aliasIds().getFirst();
            AliasAdministrationService.MutationResult retiredDeleted = service.deleteAlias(retiredAliasId,
                retired.revision());
            int commitsBeforeCreate = manager.commitCount();

            AliasAdministrationService.MutationResult created = service.createAlias(
                alias("Dispatch", list.aliasListId(), "County P25", 101), List.of(defaultScanListId),
                retiredDeleted.revision());
            long aliasId = created.aliasIds().getFirst();

            assertEquals(commitsBeforeCreate + 1, manager.commitCount());
            assertTrue(aliasId > retiredAliasId);
            assertEquals(Set.of(defaultScanListId), service.getAlias(aliasId).scanListIds());
            assertEquals(Set.of(defaultScanListId),
                new ScanListDatabaseStore(database).loadConfiguration().scanListIdsForAlias(aliasId));
        }
        finally
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    @Test
    void centralDefaultsInitializeOnlyNewTalkgroupMatchersAndExplicitRoutingWins() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("central-defaults-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        Files.createDirectories(database.getParent());
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);

        try
        {
            manager.init();
            AliasAdministrationService service = AliasAdministrationServiceTestSupport.create(manager);
            long defaultScanListId = manager.getScanListModel().defaultScanList().getId();
            AliasAdministrationService.MutationResult list = service.createAliasList(
                "County P25", AliasListFamily.P25, service.currentRevision());
            assertEquals(Set.of(defaultScanListId),
                service.getAliasListDefaults(list.aliasListId()).defaults().scanListIds());

            BroadcastifyCallConfiguration primary = new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
            primary.setName("Primary");
            manager.getBroadcastModel().addBroadcastConfiguration(primary);
            AliasAdministrationService.MutationResult configured = service.updateAliasListDefaults(
                list.aliasListId(), new AliasListDefaults(
                    new UnmatchedTalkgroupPolicy(true, List.of("Primary")), Set.of(defaultScanListId)),
                list.revision());

            Alias talkgroup = alias("Dispatch", list.aliasListId(), "County P25", 101);
            long talkgroupId = service.createAlias(talkgroup, configured.revision()).aliasIds().getFirst();
            Alias inherited = service.getAlias(talkgroupId).alias();
            assertTrue(inherited.isRecordable());
            assertEquals(Set.of("Primary"), inherited.getBroadcastChannels().stream()
                .map(BroadcastChannel::getChannelName).collect(java.util.stream.Collectors.toSet()));
            assertEquals(Set.of(defaultScanListId), service.getAlias(talkgroupId).scanListIds());

            Alias range = alias("Range", list.aliasListId(), "County P25", 200);
            range.setMatchIdentifier(new TalkgroupRange(Protocol.APCO25, 200, 210));
            long rangeId = service.createAlias(range, service.currentRevision()).aliasIds().getFirst();
            assertTrue(service.getAlias(rangeId).alias().isRecordable());
            assertEquals(Set.of(defaultScanListId), service.getAlias(rangeId).scanListIds());

            Alias radio = new Alias("Radio");
            radio.setAliasListId(list.aliasListId());
            radio.setAliasListName("County P25");
            radio.setMatchIdentifier(new Radio(Protocol.APCO25, 5001));
            long radioId = service.createAlias(radio, service.currentRevision()).aliasIds().getFirst();
            assertFalse(service.getAlias(radioId).alias().isRecordable());
            assertTrue(service.getAlias(radioId).alias().getBroadcastChannels().isEmpty());
            assertTrue(service.getAlias(radioId).scanListIds().isEmpty());

            Alias explicit = alias("Encrypted", list.aliasListId(), "County P25", 102);
            long explicitId = service.createAlias(explicit, Set.of(), service.currentRevision()).aliasIds().getFirst();
            assertFalse(service.getAlias(explicitId).alias().isRecordable());
            assertTrue(service.getAlias(explicitId).alias().getBroadcastChannels().isEmpty());
            assertTrue(service.getAlias(explicitId).scanListIds().isEmpty());

            service.updateAliasListDefaults(list.aliasListId(),
                new AliasListDefaults(UnmatchedTalkgroupPolicy.DEFAULT, Set.of()), service.currentRevision());
            assertTrue(service.getAlias(talkgroupId).alias().isRecordable());
            assertEquals(Set.of(defaultScanListId), service.getAlias(talkgroupId).scanListIds());
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
                aliasListId, new UnmatchedTalkgroupPolicy(true, List.of("Primary")),
                service.catalog().revision());
            UnmatchedTalkgroupPolicy livePolicy = manager.getAliasModel()
                .getAliasListDefinition(aliasListId).getUnmatchedTalkgroupPolicy();
            assertTrue(livePolicy.isRecordEnabled());
            assertEquals(List.of("Primary"), livePolicy.getStreamDestinationNames());
            assertEquals(livePolicy, service.catalog().aliasLists().stream()
                .filter(definition -> definition.getId() == aliasListId)
                .findFirst().orElseThrow().getUnmatchedTalkgroupPolicy());
            assertThrows(IllegalArgumentException.class, () -> service.updateUnmatchedTalkgroupPolicy(aliasListId,
                new UnmatchedTalkgroupPolicy(false, List.of("Missing")), policyChanged.revision()));

            long bulkRevision = service.catalog().revision();
            AliasAdministrationService.MutationResult configured = service.bulkEdit(
                new AliasAdministrationService.BulkEdit(List.of(firstAliasId, secondAliasId), null, null, null,
                    null, AliasAdministrationService.GroupOperation.SET, "Fire Dispatch",
                    AliasAdministrationService.StreamOperation.ADD, List.of("Primary"), false), bulkRevision);
            configured = service.bulkEdit(new AliasAdministrationService.BulkEdit(
                List.of(firstAliasId, secondAliasId), null, null, null, null, null, null,
                AliasAdministrationService.StreamOperation.ADD, List.of("Archive"), false), configured.revision());
            configured = service.bulkEdit(new AliasAdministrationService.BulkEdit(
                List.of(firstAliasId, secondAliasId), null, null, null, null, null, null,
                AliasAdministrationService.StreamOperation.REMOVE, List.of("Primary"), false), configured.revision());

            for(long aliasId: List.of(firstAliasId, secondAliasId))
            {
                Alias liveAlias = service.getAlias(aliasId).alias();
                assertEquals("Fire Dispatch", liveAlias.getGroup());
                assertEquals(List.of("Archive"), liveAlias.getBroadcastChannels().stream()
                    .map(BroadcastChannel::getChannelName).toList());
            }

            configured = service.bulkEdit(new AliasAdministrationService.BulkEdit(
                List.of(firstAliasId, secondAliasId), null, null, null, null,
                AliasAdministrationService.GroupOperation.CLEAR, null,
                AliasAdministrationService.StreamOperation.REPLACE, List.of("Primary"), false),
                configured.revision());
            configured = service.bulkEdit(new AliasAdministrationService.BulkEdit(
                List.of(firstAliasId, secondAliasId), null, null, null, null, null, null,
                AliasAdministrationService.StreamOperation.CLEAR, null, false), configured.revision());
            assertNull(service.getAlias(firstAliasId).alias().getGroup());
            assertTrue(service.getAlias(firstAliasId).alias().getBroadcastChannels().isEmpty());

            Alias unrelatedEdit = service.getAlias(firstAliasId).alias();
            unrelatedEdit.setDescription("Unrelated edit");
            AliasAdministrationService.MutationResult preserved = service.replaceAlias(firstAliasId, unrelatedEdit,
                service.catalog().revision());
            assertEquals("Unrelated edit", service.getAlias(firstAliasId).alias().getDescription());

            Alias invalidReference = service.getAlias(firstAliasId).alias();
            invalidReference.addBroadcastChannel("Never configured");
            assertThrows(IllegalArgumentException.class, () ->
                service.replaceAlias(firstAliasId, invalidReference, preserved.revision()));

            long beforeDesktopEdit = service.catalog().revision();
            Alias desktopEdited = service.getAlias(firstAliasId).alias();
            desktopEdited.setDescription("Edited on desktop");
            AliasAdministrationService.MutationResult desktopSaved = service.replaceAlias(firstAliasId,
                desktopEdited, beforeDesktopEdit);
            assertNotEquals(beforeDesktopEdit, desktopSaved.revision());
            assertThrows(AliasAdministrationService.StaleRevisionException.class,
                () -> service.replaceAlias(firstAliasId, alias("Stale overwrite", aliasListId, 101),
                    beforeDesktopEdit));
            assertEquals("Edited on desktop", service.getAlias(firstAliasId).alias().getDescription());

            AliasList liveList = manager.getAliasModel().getAliasList("County P25");
            assertEquals(firstAliasId,
                liveList.getAliases(APCO25Talkgroup.createAny(101)).getFirst().getId());

            AliasDatabaseStore aliasStore = new AliasDatabaseStore(database);
            List<AliasListDefinition> storedDefinitions = aliasStore.loadAliasListDefinitions();
            List<Alias> storedAliases = aliasStore.loadAliases(storedDefinitions);
            assertEquals(AliasListFamily.values().length + 1, storedDefinitions.size());
            AliasListDefinition storedDefinition = storedDefinitions.stream()
                .filter(definition -> definition.getId() == aliasListId)
                .findFirst().orElseThrow();
            assertEquals(aliasListId, storedDefinition.getId());
            assertEquals(livePolicy, storedDefinition.getUnmatchedTalkgroupPolicy());
            assertEquals(2, storedAliases.size());
            assertTrue(storedAliases.stream().anyMatch(alias -> alias.getId() == firstAliasId));
            assertTrue(storedAliases.stream().anyMatch(alias -> alias.getId() == secondAliasId));

            Channel channel = new Channel("County Control");
            channel.setAliasListName("County P25");
            channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
            long beforeChannelAssignment = service.catalog().revision();
            manager.getChannelModel().addChannel(channel);
            assertNotEquals(beforeChannelAssignment, service.catalog().revision());
            manager.flushConfiguration();

            long beforeBulk = service.catalog().revision();
            AliasAdministrationService.MutationResult bulk = service.bulkEdit(
                new AliasAdministrationService.BulkEdit(List.of(firstAliasId, secondAliasId), null,
                    0x123456, null, true, null, null, null, null, false), beforeBulk);
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
            AliasAdministrationService.DeleteImpact boundedImpact =
                service.aliasListDeleteImpact(aliasListId, 1);
            assertEquals(2, boundedImpact.aliasCount());
            assertEquals(1, boundedImpact.channelCount());
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

            List<AliasListDefinition> remainingDefinitions = aliasStore.loadAliasListDefinitions();
            assertEquals(AliasListFamily.values().length, remainingDefinitions.size());
            assertTrue(remainingDefinitions.stream().noneMatch(definition -> definition.getId() == aliasListId));
            assertTrue(aliasStore.loadAliases(remainingDefinitions).isEmpty());
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
        return alias(name, aliasListId, "County P25", talkgroup);
    }

    private static Alias alias(String name, long aliasListId, String aliasListName, int talkgroup)
    {
        Alias alias = new Alias(name);
        alias.setAliasListId(aliasListId);
        alias.setAliasListName(aliasListName);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, talkgroup));
        return alias;
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
        private final AtomicInteger mCommitCount = new AtomicInteger();

        private CountingConfigurationManager(UserPreferences preferences)
        {
            super(preferences, null, new AliasModel(), null, null);
        }

        @Override
        protected AliasConfigurationSnapshot commitAliasConfiguration(AliasConfigurationSnapshot proposed,
            AliasConfigurationPublication publication, BroadcastConfigurationRename broadcastRename)
        {
            mCommitCount.incrementAndGet();
            return super.commitAliasConfiguration(proposed, publication, broadcastRename);
        }

        private int commitCount()
        {
            return mCommitCount.get();
        }
    }
}
