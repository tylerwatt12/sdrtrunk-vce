/* Copyright (C) 2026 Dennis Sheirer. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.audio.broadcast.BroadcastFormat;
import io.github.dsheirer.audio.broadcast.broadcastify.BroadcastifyCallConfiguration;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigurationBackgroundSaveTest
{
    @TempDir Path mTemporaryFolder;

    @Test
    void backgroundChannelSaveLeavesOtherChannelAndAllDestinationsUntouched() throws Exception
    {
        try(Fixture fixture = fixture("channel"))
        {
            Channel changed = fixture.manager().getChannelModel().getChannels().getFirst();
            forbid(fixture, "configuration_channel", "WHEN OLD.configuration_id<>'" + changed.getConfigurationId() + "'");
            forbid(fixture, "configuration_broadcast_stream", "");
            changed.setName("Background saved channel");
            assertTrue(fixture.manager().saveChannelAndBroadcastConfigurationToDatabase());
            assertEquals("Background saved channel", fixture.repository().load().channels().getFirst().getName());
            //A later no-op save must not attempt even one write to the previously edited channel.
            execute(fixture.database(), "DROP TRIGGER reject_configuration_channel");
            forbid(fixture, "configuration_channel", "");
            assertTrue(fixture.manager().saveChannelAndBroadcastConfigurationToDatabase());
        }
    }

    @Test
    void backgroundDestinationSaveLeavesAllChannelsAndOtherDestinationsUntouched() throws Exception
    {
        try(Fixture fixture = fixture("stream"))
        {
            var changed = fixture.manager().getBroadcastModel().getBroadcastConfigurations().getFirst();
            forbid(fixture, "configuration_channel", "");
            forbid(fixture, "configuration_broadcast_stream", "WHEN OLD.configuration_id<>'" + changed.getConfigurationId() + "'");
            changed.setName("Background saved destination");
            assertTrue(fixture.manager().saveChannelAndBroadcastConfigurationToDatabase());
            assertEquals("Background saved destination", fixture.repository().load().broadcastConfigurations().getFirst().getName());
        }
    }

    @Test
    void failedBackgroundSaveKeepsDraftAndCanBeRetriedWithoutPartialPersistence() throws Exception
    {
        try(Fixture fixture = fixture("retry"))
        {
            String before = fixture.repository().fingerprint();
            Channel changed = fixture.manager().getChannelModel().getChannels().getFirst();
            changed.setName("Retry retained channel");
            fixture.manager().getBroadcastModel().getBroadcastConfigurations().getFirst().setName("Retry retained destination");
            forbid(fixture, "configuration_broadcast_stream", "");
            assertFalse(fixture.manager().saveChannelAndBroadcastConfigurationToDatabase());
            assertEquals(before, fixture.repository().fingerprint());
            assertEquals("Retry retained channel", changed.getName());
            execute(fixture.database(), "DROP TRIGGER reject_configuration_broadcast_stream");
            assertTrue(fixture.manager().saveChannelAndBroadcastConfigurationToDatabase());
            var saved = fixture.repository().load();
            assertEquals("Retry retained channel", saved.channels().getFirst().getName());
            assertEquals("Retry retained destination", saved.broadcastConfigurations().getFirst().getName());
        }
    }

    private Fixture fixture(String name) throws Exception
    {
        Path root = mTemporaryFolder.resolve(name);
        Path database = SdrTrunkDatabasePath.getDatabasePath(root);
        SdrTrunkTestDatabase.create(database);
        ConfigurationRepository repository = new ConfigurationRepository(database);
        var definition = repository.loadAliasConfiguration().definitions().stream()
            .filter(list -> list.getFamily() == AliasListFamily.P25).findFirst().orElseThrow();
        Channel first = new Channel("First");
        first.setAliasListDefinition(definition);
        first.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(851_012_500L);
        first.setSourceConfiguration(source);
        Channel second = first.copyOf();
        second.setName("Second");
        BroadcastifyCallConfiguration firstStream = new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
        firstStream.setName("First stream");
        BroadcastifyCallConfiguration secondStream = new BroadcastifyCallConfiguration(BroadcastFormat.MP3);
        secondStream.setName("Second stream");
        repository.synchronizeChannelAndBroadcastConfiguration(List.of(first, second), List.of(firstStream, secondStream));
        ConfigurationManager manager = new ConfigurationManager(new TestUserPreferences(root), null, new AliasModel(), null);
        manager.init();
        return new Fixture(database, repository, manager);
    }

    private static void forbid(Fixture fixture, String table, String condition) throws Exception
    {
        execute(fixture.database(), "CREATE TRIGGER reject_" + table + " BEFORE UPDATE ON " + table + " " + condition +
            " BEGIN SELECT RAISE(ABORT,'unexpected background write'); END");
    }

    private static void execute(Path database, String sql) throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(database); Statement statement = connection.createStatement())
        {
            statement.execute(sql);
        }
    }

    private record Fixture(Path database, ConfigurationRepository repository, ConfigurationManager manager) implements AutoCloseable
    {
        @Override public void close()
        {
            MyEventBus.getGlobalEventBus().unregister(manager.getChannelProcessingManager());
        }
    }

    private static final class TestUserPreferences extends UserPreferences
    {
        private final DirectoryPreference mDirectoryPreference;
        private TestUserPreferences(Path root)
        {
            mDirectoryPreference = new DirectoryPreference(type -> {})
            {
                @Override public Path getDirectoryApplicationRoot() { return root; }
            };
        }
        @Override public DirectoryPreference getDirectoryPreference() { return mDirectoryPreference; }
    }
}
