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

class ConfigurationCommitIsolationTest
{
    @TempDir Path mTemporaryFolder;

    @Test
    void channelCommandLeavesOtherChannelsAndAllDestinationsUntouched() throws Exception
    {
        try(Fixture fixture = fixture("channel"))
        {
            Channel changed = fixture.manager().getChannelModel().getChannels().getFirst();
            forbid(fixture, "configuration_channel", "WHEN OLD.configuration_id<>'" + changed.getConfigurationId() + "'");
            forbid(fixture, "configuration_broadcast_stream", "");
            Channel candidate = changed.copyOfPreservingIdentity();
            candidate.setName("Committed channel");
            commitChannel(fixture, candidate);
            assertEquals("Committed channel", fixture.repository().load().channels().getFirst().getName());
            //A later no-op save must not attempt even one write to the previously edited channel.
            execute(fixture.database(), "DROP TRIGGER reject_configuration_channel");
            forbid(fixture, "configuration_channel", "");
            commitChannel(fixture, candidate);
        }
    }

    @Test
    void destinationCommandLeavesAllChannelsAndOtherDestinationsUntouched() throws Exception
    {
        try(Fixture fixture = fixture("stream"))
        {
            var changed = fixture.manager().getBroadcastModel().getBroadcastConfigurations().getFirst();
            forbid(fixture, "configuration_channel", "");
            forbid(fixture, "configuration_broadcast_stream", "WHEN OLD.configuration_id<>'" + changed.getConfigurationId() + "'");
            var candidate = changed.copyOf();
            candidate.setConfigurationId(changed.getConfigurationId());
            candidate.setName("Committed destination");
            commitStream(fixture, candidate);
            assertEquals("Committed destination", fixture.repository().load().broadcastConfigurations().getFirst().getName());
        }
    }

    @Test
    void aliasCatalogRevisionTracksChannelAssignmentsButNotUnrelatedEdits() throws Exception
    {
        try(Fixture fixture = fixture("assignments"))
        {
            var manager = fixture.manager();
            Channel original = manager.getChannelModel().getChannels().getFirst();
            long aliasListId = original.getAliasListId();
            long revision = manager.getAliasConfigurationRevision();
            Channel renamed = original.copyOfPreservingIdentity();
            renamed.setName("Renamed channel");
            commitChannel(fixture, renamed);
            assertEquals(revision, manager.getAliasConfigurationRevision());
            assertEquals(2, manager.getAliasAdministrationService().catalog()
                .assignedChannelCounts().get(aliasListId).intValue());

            var remaining = manager.getChannelModel().getChannels().stream()
                .filter(channel -> !channel.getConfigurationId().equals(original.getConfigurationId())).toList();
            manager.commitAndPublishChannelConfiguration(new ChannelConfigurationSnapshot(remaining),
                java.util.Set.of(original.getConfigurationId()), false);
            assertEquals(revision + 1, manager.getAliasConfigurationRevision());
            assertEquals(1, manager.getAliasAdministrationService().catalog()
                .assignedChannelCounts().get(aliasListId).intValue());
        }
    }

    @Test
    void failedCommandKeepsLiveStateAndCanBeRetriedWithoutPartialPersistence() throws Exception
    {
        try(Fixture fixture = fixture("retry"))
        {
            String before = fixture.repository().fingerprint();
            var live = fixture.manager().getBroadcastModel().getBroadcastConfigurations().getFirst();
            var candidate = live.copyOf();
            candidate.setConfigurationId(live.getConfigurationId());
            candidate.setName("Retry destination");
            forbid(fixture, "configuration_broadcast_stream", "");
            org.junit.jupiter.api.Assertions.assertThrows(ConfigurationManager.ConfigurationCommitException.class,
                () -> commitStream(fixture, candidate));
            assertEquals(before, fixture.repository().fingerprint());
            assertEquals("First stream", live.getName());
            execute(fixture.database(), "DROP TRIGGER reject_configuration_broadcast_stream");
            commitStream(fixture, candidate);
            var saved = fixture.repository().load();
            assertEquals("First", saved.channels().getFirst().getName());
            assertEquals("Retry destination", saved.broadcastConfigurations().getFirst().getName());
        }
    }

    private static void commitChannel(Fixture fixture, Channel candidate)
    {
        var channels = fixture.manager().getChannelModel().getChannels().stream()
            .map(channel -> channel.getConfigurationId().equals(candidate.getConfigurationId()) ? candidate : channel).toList();
        fixture.manager().commitAndPublishChannelConfiguration(new ChannelConfigurationSnapshot(channels),
            java.util.Set.of(candidate.getConfigurationId()), false);
    }

    @Test
    void learnedSettingsAreDetachedCommittedAndPublishedOnTheConfigurationOwner() throws Exception
    {
        try(Fixture fixture = fixture("learned"))
        {
            Channel candidate = fixture.manager().getChannelModel().getChannels().getFirst().copyOfPreservingIdentity();
            var source = new io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency();
            source.setFrequencies(List.of(851_012_500L));
            candidate.setSourceConfiguration(source);
            ((DecodeConfigP25Phase1)candidate.getDecodeConfiguration()).setLearnAnnouncedControlChannels(true);
            commitChannel(fixture, candidate);
            Channel live = fixture.manager().getChannelModel().getChannels().getFirst();
            var liveSource = live.getSourceConfiguration();
            var liveDecoder = live.getDecodeConfiguration();
            var snapshot = new io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot(
                "P25_PHASE_1", new io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot.Network(
                    0xBEE00, 0x123, 0x456, null),
                new io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot.CurrentSite(
                    0x123, 0x456, 1, 2, null, null),
                List.of(new io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot.Channel(
                    "primary_control", null, 851_012_500L, null, false, 1),
                    new io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot.Channel(
                        "secondary_control", null, 851_262_500L, null, false, 1)),
                List.of(), List.of(), List.of(), List.of());
            var event = new io.github.dsheirer.metadata.site.SiteMetadataEvent(live, snapshot, 1_000, 851_012_500L);
            var learner = new io.github.dsheirer.metadata.site.SiteControlChannelLearner(fixture.manager());
            var active = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var blocker = fixture.manager().submitConfigurationTask(() ->
            {
                active.countDown();
                try { assertTrue(release.await(3, java.util.concurrent.TimeUnit.SECONDS)); }
                catch(InterruptedException failure) { throw new AssertionError(failure); }
            });
            assertTrue(active.await(3, java.util.concurrent.TimeUnit.SECONDS));
            try
            {
                learner.receiveSiteMetadata(event);
                org.junit.jupiter.api.Assertions.assertNull(live.getP25SiteIdentity());
                assertEquals(List.of(851_012_500L), live.getFrequencyList());
            }
            finally { release.countDown(); }
            blocker.get(3, java.util.concurrent.TimeUnit.SECONDS);
            fixture.manager().flushConfiguration();
            org.junit.jupiter.api.Assertions.assertSame(live, fixture.manager().getChannelModel().getChannels().getFirst());
            org.junit.jupiter.api.Assertions.assertSame(liveSource, live.getSourceConfiguration());
            org.junit.jupiter.api.Assertions.assertSame(liveDecoder, live.getDecodeConfiguration());
            assertEquals(List.of(851_012_500L, 851_262_500L), live.getFrequencyList());
            var disk = fixture.repository().load().channels().getFirst();
            assertEquals(live.getP25SiteIdentity(), disk.getP25SiteIdentity());
            assertEquals(live.getFrequencyList(), disk.getFrequencyList());
            assertEquals(List.of(851_262_500L), ((DecodeConfigP25Phase1)disk.getDecodeConfiguration()).getLearnedControlFrequencies());
        }
    }

    private static void commitStream(Fixture fixture, io.github.dsheirer.audio.broadcast.BroadcastConfiguration candidate)
    {
        var streams = fixture.manager().getBroadcastModel().getBroadcastConfigurations().stream()
            .map(stream -> stream.getConfigurationId().equals(candidate.getConfigurationId()) ? candidate : stream).toList();
        fixture.manager().commitAndPublishStreamingConfiguration(streams, candidate.getConfigurationId());
    }

    @Test
    void failedLearnedCommitDoesNotChangeTheActiveChannel() throws Exception
    {
        try(Fixture fixture = fixture("learned-failure"))
        {
            Channel live = fixture.manager().getChannelModel().getChannels().getFirst();
            String before = fixture.repository().fingerprint();
            var snapshot = new io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot(
                "P25_PHASE_1", new io.github.dsheirer.module.decode.p25.telemetry.P25NetworkConfigurationSnapshot.Network(
                    0xBEE00, 0x123, 0x456, null), null, List.of(), List.of(), List.of(), List.of(), List.of());
            var event = new io.github.dsheirer.metadata.site.SiteMetadataEvent(live, snapshot, 1_000, 851_012_500L);
            var identity = new io.github.dsheirer.module.decode.p25.P25SiteIdentity(0xBEE00, 0x123, 1, 2);
            forbid(fixture, "configuration_channel", "");
            fixture.manager().submitLearnedChannelUpdate(event, candidate ->
            {
                candidate.setP25SiteIdentity(identity);
                return true;
            });
            fixture.manager().flushConfiguration();
            org.junit.jupiter.api.Assertions.assertNull(live.getP25SiteIdentity());
            assertEquals(before, fixture.repository().fingerprint());
            execute(fixture.database(), "DROP TRIGGER reject_configuration_channel");
            fixture.manager().submitLearnedChannelUpdate(event, candidate ->
            {
                candidate.setP25SiteIdentity(identity);
                return true;
            });
            fixture.manager().flushConfiguration();
            assertEquals(identity, live.getP25SiteIdentity());
            assertEquals(identity, fixture.repository().load().channels().getFirst().getP25SiteIdentity());
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
            manager.getChannelProcessingManager().close();
            manager.getBroadcastModel().close();
            manager.getRadioReferenceDirectoryService().close();
            manager.close();
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
