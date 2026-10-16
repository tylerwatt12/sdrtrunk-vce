/* Copyright (C) 2026 Dennis Sheirer. Licensed under GPL-3.0-or-later. */
package io.github.dsheirer.database.configuration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveConfiguration;
import io.github.dsheirer.configuration.ChannelConfigurationSnapshot;
import io.github.dsheirer.configuration.ConfigurationSnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.remote.RemoteLinkSettingsStore;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.Settings;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.TrustedSender;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.source.config.SourceConfigRemote;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RemoteSenderRemovalPersistenceTest
{
    @TempDir Path mTemporaryFolder;

    @Test
    void removesLegacySenderAndOnlyItsExactRemoteChannelsInOneCommit() throws Exception
    {
        Fixture fixture = fixture(true);
        AtomicInteger callbacks = new AtomicInteger();
        ChannelConfigurationSnapshot result = fixture.repository().commitRemoteSenderRemoval(fixture.remaining(),
            fixture.senderId(), fixture.next(), () ->
            {
                callbacks.incrementAndGet();
                assertEquals(fixture.next(), assertDoesNotThrow(() -> fixture.store().load()),
                    "The callback sees the committed credential state");
            });
        assertEquals(1, callbacks.get());
        assertEquals(fixture.remaining().channels().stream().map(Channel::getConfigurationId).toList(),
            result.channels().stream().map(Channel::getConfigurationId).toList());
        assertEquals(fixture.next(), fixture.store().load());
        assertUnrelatedState(fixture);
    }

    @Test
    void rollsBackChannelDeletionWhenTheSettingsWriteFailsAndCanRetry() throws Exception
    {
        Fixture fixture = fixture(true);
        try(Connection connection = SdrTrunkDatabase.open(fixture.database()); Statement sql = connection.createStatement())
        {
            sql.execute("CREATE TRIGGER refuse_sender_removal BEFORE UPDATE ON application_settings " +
                "WHEN NEW.key='remote.links' BEGIN SELECT RAISE(ABORT,'sensitive-test-only'); END");
        }
        AtomicInteger callbacks = new AtomicInteger();
        IOException failure = assertThrows(IOException.class, () -> fixture.repository().commitRemoteSenderRemoval(
            fixture.remaining(), fixture.senderId(), fixture.next(), callbacks::incrementAndGet));
        assertTrue(!failure.toString().contains("sensitive-test-only"));
        assertEquals(0, callbacks.get());
        assertEquals(fixture.original(), fixture.store().load());
        assertEquals(4, fixture.repository().loadChannelConfiguration().channels().size());
        try(Connection connection = SdrTrunkDatabase.open(fixture.database()); Statement sql = connection.createStatement())
        {
            sql.execute("DROP TRIGGER refuse_sender_removal");
        }
        fixture.repository().commitRemoteSenderRemoval(fixture.remaining(), fixture.senderId(), fixture.next(),
            callbacks::incrementAndGet);
        assertEquals(1, callbacks.get());
        assertUnrelatedState(fixture);
    }

    @Test
    void rollsBackSenderSettingsWhenChannelDeletionFails() throws Exception
    {
        Fixture fixture = fixture(true);
        try(Connection connection = SdrTrunkDatabase.open(fixture.database()); Statement sql = connection.createStatement())
        {
            sql.execute("CREATE TRIGGER refuse_channel_removal BEFORE DELETE ON configuration_channel " +
                "BEGIN SELECT RAISE(ABORT,'channel-test-only'); END");
        }
        AtomicInteger callbacks = new AtomicInteger();
        assertThrows(Exception.class, () -> fixture.repository().commitRemoteSenderRemoval(fixture.remaining(),
            fixture.senderId(), fixture.next(), callbacks::incrementAndGet));
        assertEquals(0, callbacks.get());
        assertEquals(fixture.original(), fixture.store().load());
        assertEquals(4, fixture.repository().loadChannelConfiguration().channels().size());
        assertUnrelatedState(fixture);
    }

    @Test
    void zeroChannelRemovalStillCommitsTheSenderAndChecksTheCurrentRevision() throws Exception
    {
        Fixture fixture = fixture(false);
        Settings stale = new Settings(fixture.next().revision() + 1, fixture.next().listenerEnabled(),
            fixture.next().bindAddress(), fixture.next().listenPort(), fixture.next().trustedSenders(),
            fixture.next().outbound());
        assertThrows(IOException.class, () -> fixture.repository().commitRemoteSenderRemoval(fixture.remaining(),
            fixture.senderId(), stale, () -> {}));
        assertEquals(fixture.original(), fixture.store().load());
        AtomicInteger callbacks = new AtomicInteger();
        fixture.repository().commitRemoteSenderRemoval(fixture.remaining(), fixture.senderId(), fixture.next(),
            callbacks::incrementAndGet);
        assertEquals(1, callbacks.get());
        assertEquals(2, fixture.repository().loadChannelConfiguration().channels().size());
        assertEquals(fixture.next(), fixture.store().load());
        assertUnrelatedState(fixture);
    }

    private Fixture fixture(boolean targetChannels) throws Exception
    {
        Path database = SdrTrunkTestDatabase.create(mTemporaryFolder.resolve(UUID.randomUUID() + ".sqlite"));
        ConfigurationRepository repository = new ConfigurationRepository(database);
        String senderId = UUID.randomUUID().toString();
        String otherSender = UUID.randomUUID().toString();
        String sharedFeed = UUID.randomUUID().toString();
        AliasListDefinition definition = new AliasListDefinition("Unrelated P25", AliasListFamily.P25);
        definition.setId(100);
        RadioResolveConfiguration stream = new RadioResolveConfiguration();
        stream.setName("Unrelated stream");
        Alias alias = new Alias("Unrelated dispatch");
        alias.setId(100);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, 1001));
        alias.addBroadcastChannel(new BroadcastChannel(stream.getConfigurationId(), stream.getName()));
        Channel local = channel(definition, null, null);
        Channel other = channel(definition, otherSender, sharedFeed);
        List<Channel> channels = new ArrayList<>(List.of(local, other));
        if(targetChannels)
        {
            channels.add(channel(definition, senderId, sharedFeed));
            channels.add(channel(definition, senderId, UUID.randomUUID().toString()));
        }
        ScanListConfiguration scanLists = new ScanListConfiguration(
            repository.load().scanListConfiguration().scanLists(), Map.of(), Map.of());
        ConfigurationSnapshot saved = repository.replace(new ConfigurationSnapshot(List.of(definition), List.of(alias),
            scanLists, channels, List.of(stream)));
        RemoteLinkSettingsStore store = new RemoteLinkSettingsStore(database);
        Settings original = new Settings(7, false, RemoteLinkSettingsStore.DEFAULT_BIND_ADDRESS, 53_800,
            List.of(new TrustedSender(senderId, "Old sender", null, false, null, 1, true),
                new TrustedSender(otherSender, "Other sender", "synthetic-key", false, null, 2, false)),
            RemoteLinkSettingsStore.Outbound.disabled());
        store.save(original);
        Settings next = new Settings(8, original.listenerEnabled(), original.bindAddress(), original.listenPort(),
            List.of(original.trustedSenders().get(1)), original.outbound());
        try(Connection connection = SdrTrunkDatabase.open(database);
            PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO receiver_channel(configuration_id,first_seen_ms,last_seen_ms) VALUES (?,123,456)"))
        {
            insert.setString(1, local.getConfigurationId());
            insert.executeUpdate();
            try(Statement sql = connection.createStatement())
            {
                sql.execute("INSERT INTO receiver_activity_event(channel_id,observed_at_ms,action_code) " +
                    "SELECT id,456,1 FROM receiver_channel");
            }
        }
        ChannelConfigurationSnapshot remaining = new ChannelConfigurationSnapshot(saved.channels().stream()
            .filter(item -> !(item.getSourceConfiguration() instanceof SourceConfigRemote remote) ||
                !senderId.equals(remote.getSenderId())).toList());
        return new Fixture(database, repository, store, senderId, original, next, remaining);
    }

    private static Channel channel(AliasListDefinition definition, String senderId, String feedId)
    {
        Channel channel = new Channel("Same display name");
        channel.setAliasListDefinition(definition);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        if(senderId == null)
        {
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(851_012_500L);
            channel.setSourceConfiguration(source);
        }
        else
        {
            SourceConfigRemote source = new SourceConfigRemote();
            source.setSenderId(senderId); source.setFeedId(feedId); source.setFrequency(851_012_500L);
            channel.setSourceConfiguration(source);
        }
        return channel;
    }

    private static void assertUnrelatedState(Fixture fixture) throws Exception
    {
        ConfigurationSnapshot snapshot = fixture.repository().load();
        assertEquals("Unrelated dispatch", snapshot.aliases().getFirst().getName());
        assertEquals("Unrelated P25", snapshot.aliasListDefinitions().getFirst().getName());
        assertEquals("Unrelated stream", snapshot.broadcastConfigurations().getFirst().getName());
        try(Connection connection = SdrTrunkDatabase.open(fixture.database()); Statement sql = connection.createStatement();
            var rows = sql.executeQuery("SELECT count(*) FROM receiver_activity_event WHERE observed_at_ms=456"))
        {
            assertTrue(rows.next()); assertEquals(1, rows.getInt(1));
        }
    }

    private record Fixture(Path database, ConfigurationRepository repository, RemoteLinkSettingsStore store,
                           String senderId, Settings original, Settings next, ChannelConfigurationSnapshot remaining) {}
}
