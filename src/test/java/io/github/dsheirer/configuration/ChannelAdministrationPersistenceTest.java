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
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.dsheirer.alias.AliasAdministrationService;
import io.github.dsheirer.alias.AliasAdministrationServiceTestSupport;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.remote.RemoteLinkSettingsStore;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.sql.Connection;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
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

    @Test
    void senderRemovalPublishesOnlyAfterTheSharedCommitAndRetriesAPrecommitFailure() throws Exception
    {
        try(RemovalFixture fixture = removalFixture("sender-removal"))
        {
            try(Connection connection = SdrTrunkDatabase.open(fixture.manager().getDatabasePath());
                Statement sql = connection.createStatement())
            {
                sql.execute("CREATE TRIGGER refuse_sender_update BEFORE UPDATE ON application_settings " +
                    "WHEN NEW.key='remote.links' BEGIN SELECT RAISE(ABORT,'test-only'); END");
            }
            AtomicInteger committed = new AtomicInteger();
            AtomicInteger rollback = new AtomicInteger();
            assertThrows(ChannelAdministrationService.PersistenceException.class, () ->
                fixture.channels().removeRemoteSender(fixture.senderId(), fixture.next(),
                    committed::incrementAndGet, rollback::incrementAndGet));
            assertEquals(0, committed.get()); assertEquals(1, rollback.get());
            assertEquals(1, fixture.store().load().trustedSenders().size());
            assertEquals(1, fixture.channels().catalog().channels().size());
            try(Connection connection = SdrTrunkDatabase.open(fixture.manager().getDatabasePath());
                Statement sql = connection.createStatement())
            {
                sql.execute("DROP TRIGGER refuse_sender_update");
            }
            fixture.channels().removeRemoteSender(fixture.senderId(), fixture.next(), () ->
            {
                committed.incrementAndGet();
                assertEquals(1, fixture.manager().getChannelModel().getChannels().size(),
                    "Credential notification precedes model publication");
            }, rollback::incrementAndGet);
            assertEquals(1, committed.get()); assertEquals(1, rollback.get());
            assertEquals(fixture.next(), fixture.store().load());
            assertTrue(fixture.channels().catalog().channels().isEmpty());
        }
    }

    @Test
    void postcommitFailureNeverInvokesTheRollbackRecoveryCallback() throws Exception
    {
        try(RemovalFixture fixture = removalFixture("sender-publication", false))
        {
            AtomicInteger rollback = new AtomicInteger();
            assertThrows(ConfigurationManager.ConfigurationPublicationException.class, () ->
                fixture.channels().removeRemoteSender(fixture.senderId(), fixture.next(), () ->
                {
                    throw new IllegalStateException("Injected postcommit failure");
                }, rollback::incrementAndGet));
            assertEquals(0, rollback.get(), "Committed deletions must never run restart recovery");
            assertEquals(fixture.next(), fixture.store().load());
            assertTrue(new ConfigurationDatabaseStore(fixture.manager().getDatabasePath()).load().channels().isEmpty());
        }
    }

    private RemovalFixture removalFixture(String name) throws Exception
    {
        return removalFixture(name, true);
    }

    private RemovalFixture removalFixture(String name, boolean flushOnClose) throws Exception
    {
        Path root = mTemporaryFolder.resolve(name);
        Path database = SdrTrunkDatabasePath.getDatabasePath(root);
        SdrTrunkTestDatabase.create(database);
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(root), null,
            new AliasModel(), null, null);
        manager.init();
        ChannelAdministrationService channels = ChannelAdministrationServiceTestSupport.create(manager);
        long aliasListId = manager.getAliasModel().aliasListDefinitions().stream()
            .filter(list -> list.getFamily() == AliasListFamily.P25).findFirst().orElseThrow().getId();
        String senderId = UUID.randomUUID().toString();
        ChannelDefinition template = channels.template("p25-phase1");
        ChannelDefinition channel = new ChannelDefinition(null, template.protocolId(), "County", "Central",
            "Remote control", null, aliasListId, new ChannelDefinition.Source(List.of(851_012_500L),
                null, null, null, null, null, "remote", senderId, UUID.randomUUID().toString()),
            template.settings(), template.frequencyMap(), template.eventLogs(), template.recorders(),
            template.auxiliaryDecoders(), ChannelDefinition.Observed.EMPTY);
        channels.create(channel, channels.currentRevision());
        RemoteLinkSettingsStore store = new RemoteLinkSettingsStore(database);
        RemoteLinkSettingsStore.Settings original = new RemoteLinkSettingsStore.Settings(2, false,
            RemoteLinkSettingsStore.DEFAULT_BIND_ADDRESS, 53_800, List.of(new RemoteLinkSettingsStore.TrustedSender(
                senderId, "Remote control", "synthetic-key", false, null, 1, false)),
            RemoteLinkSettingsStore.Outbound.disabled());
        store.save(original);
        RemoteLinkSettingsStore.Settings next = new RemoteLinkSettingsStore.Settings(3, false,
            original.bindAddress(), original.listenPort(), List.of(), original.outbound());
        return new RemovalFixture(manager, channels, store, senderId, next, flushOnClose);
    }

    private record RemovalFixture(ConfigurationManager manager, ChannelAdministrationService channels,
        RemoteLinkSettingsStore store, String senderId, RemoteLinkSettingsStore.Settings next,
        boolean flushOnClose) implements AutoCloseable
    {
        @Override public void close()
        {
            try { if(flushOnClose) manager.flushConfiguration(); }
            finally { MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager()); }
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
