/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ChannelAdministrationPersistenceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void webCommandsCommitBeforePublishingAndUseNullableDenseAutoStartOrder() throws Exception
    {
        Path dataRoot = mTemporaryFolder.resolve("channel-administration-data");
        Path database = SdrTrunkDatabasePath.getDatabasePath(dataRoot);
        SdrTrunkTestDatabase.create(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(dataRoot), null,
            new AliasModel(), null, null);

        try
        {
            manager.init();
            AliasAdministrationService aliases = AliasAdministrationServiceTestSupport.create(manager);
            long aliasListId = aliases.createAliasList("County P25", AliasListFamily.P25,
                aliases.currentRevision()).aliasListId();
            ChannelAdministrationService channels = ChannelAdministrationServiceTestSupport.create(manager);
            ChannelDefinition template = channels.template("p25-conventional");

            ChannelAdministrationService.MutationResult first = channels.create(
                withNameAndFrequency(template, aliasListId, "Dispatch", 155_010_000L),
                channels.currentRevision());
            ChannelAdministrationService.MutationResult second = channels.create(
                withNameAndFrequency(template, aliasListId, "Operations", 155_020_000L), first.revision());

            String firstId = first.configurationIds().getFirst();
            String secondId = second.configurationIds().getFirst();
            assertEquals(2, new ConfigurationDatabaseStore(database).load().channels().size());
            assertEquals(2, manager.getChannelModel().getChannels().size());

            ChannelAdministrationService.MutationResult orderOne = channels.moveAutoStart(firstId,
                ChannelAdministrationService.Direction.EARLIER, second.revision());
            ChannelAdministrationService.MutationResult orderTwo = channels.moveAutoStart(secondId,
                ChannelAdministrationService.Direction.EARLIER, orderOne.revision());
            assertEquals(1, channels.get(firstId).autoStartOrder());
            assertEquals(2, channels.get(secondId).autoStartOrder());

            ChannelAdministrationService.MutationResult disabled = channels.moveAutoStart(secondId,
                ChannelAdministrationService.Direction.LATER, orderTwo.revision());
            assertNull(channels.get(secondId).autoStartOrder());
            assertFalse(new ConfigurationDatabaseStore(database).load().channels().stream()
                .filter(channel -> secondId.equals(channel.getConfigurationId())).findFirst().orElseThrow()
                .isAutoStart());

            ChannelDefinition current = channels.get(firstId).channel();
            ChannelAdministrationService.MutationResult updated = channels.update(firstId,
                withName(current, "Dispatch Updated"), disabled.revision());
            ChannelAdministrationService.MutationResult cloned = channels.cloneChannels(List.of(firstId),
                updated.revision());
            String cloneId = cloned.configurationIds().getFirst();
            assertEquals("Dispatch Updated", channels.get(cloneId).channel().name());
            assertEquals(2, channels.get(cloneId).autoStartOrder(),
                "Web clones preserve Java clone auto-start behavior and append safely to the queue");

            ChannelAdministrationService.MutationResult deleted = channels.deleteChannels(List.of(cloneId),
                cloned.revision());
            assertTrue(channels.catalog().channels().stream()
                .noneMatch(channel -> cloneId.equals(channel.configurationId())));
            assertEquals(deleted.revision(), channels.currentRevision());
            assertEquals(2, new ConfigurationDatabaseStore(database).load().channels().size());

            ChannelAdministrationService.MutationResult autoStartEnabled = channels.setAutoStart(List.of(secondId),
                true, deleted.revision());
            assertEquals(1, channels.get(firstId).autoStartOrder());
            assertEquals(2, channels.get(secondId).autoStartOrder(),
                "Bulk enable appends newly enabled channels to the queue");
            ChannelAdministrationService.MutationResult autoStartDisabled = channels.setAutoStart(List.of(firstId),
                false, autoStartEnabled.revision());
            assertNull(channels.get(firstId).autoStartOrder());
            assertEquals(1, channels.get(secondId).autoStartOrder(),
                "Bulk disable compacts the remaining queue");
            assertFalse(new ConfigurationDatabaseStore(database).load().channels().stream()
                .filter(channel -> firstId.equals(channel.getConfigurationId())).findFirst().orElseThrow()
                .isAutoStart());
            assertEquals(autoStartDisabled.revision(), channels.currentRevision());
        }
        finally
        {
            try
            {
                manager.flushConfiguration();
            }
            finally
            {
                MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
            }
        }
    }

    private static ChannelDefinition withNameAndFrequency(ChannelDefinition source, long aliasListId, String name,
                                                          long frequency)
    {
        return new ChannelDefinition(null, source.protocolId(), "County", "Central", name, null, aliasListId,
            new ChannelDefinition.Source(List.of(frequency), null, null, null, null, null), source.settings(),
            source.frequencyMap(), source.eventLogs(), source.recorders(), source.auxiliaryDecoders(),
            ChannelDefinition.Observed.EMPTY);
    }

    private static ChannelDefinition withName(ChannelDefinition source, String name)
    {
        return new ChannelDefinition(source.configurationId(), source.protocolId(), source.system(), source.site(),
            name, source.radioResolveId(), source.aliasListId(), source.source(), source.settings(),
            source.frequencyMap(), source.eventLogs(), source.recorders(), source.auxiliaryDecoders(),
            source.observed());
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
