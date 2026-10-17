/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.configuration.ChannelConfigurationSnapshot;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.CreateSenderResult;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.AliasListOption;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.FeedState;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.ListenerState;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.ListenerUpdate;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.SenderConnectionUpdate;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.SenderState;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.UpdateFeedRequest;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.UpdateSenderRequest;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.DependencyState;
import io.github.dsheirer.remote.RemoteWireProtocol.Frame;
import io.github.dsheirer.remote.RemoteWireProtocol.Type;
import io.github.dsheirer.remote.RemoteLinkSettingsStore.TrustedSender;
import io.github.dsheirer.source.config.SourceConfigRemote;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RemoteConnectivityServiceTest
{
    @TempDir
    Path mTemp;

    @Test
    void failedPairingWriteKeepsTheRuntimeRevisionAndSenderSnapshotUnchanged() throws Exception
    {
        try(Installation host = new Installation(mTemp.resolve("failed-save-host")))
        {
            var original = host.remote.snapshot();
            try(Connection connection = SdrTrunkDatabase.open(host.configuration.getDatabasePath());
                Statement statement = connection.createStatement())
            {
                statement.execute("""
                    CREATE TRIGGER refuse_remote_settings BEFORE INSERT ON application_settings
                    WHEN NEW.key='remote.links'
                    BEGIN SELECT RAISE(ABORT, 'sensitive-sentinel'); END
                    """);
            }

            var error = assertThrows(RemoteLinkAdministrationService.UnavailableException.class,
                () -> host.remote.createSender(original.revision(),
                    new RemoteLinkAdministrationService.CreateSenderRequest("Unsaved receiver")));

            assertEquals(original, host.remote.snapshot());
            assertEquals(original.revision(), new RemoteLinkSettingsStore(host.configuration.getDatabasePath())
                .load().revision());
            assertTrue(new RemoteLinkSettingsStore(host.configuration.getDatabasePath()).load().trustedSenders().isEmpty());
            assertFalse(error.toString().contains("sensitive-sentinel"));
            assertNull(error.getCause());
        }
    }

    @Test
    void choosesPreferredThenFactoryThenLowestP25AliasListWithoutRequiringSenderOptIn()
    {
        TrustedSender oldSender = new TrustedSender(UUID.randomUUID().toString(), "Old sender", "test-only-key",
            false, 41L, 1L, false);
        List<AliasListOption> choices = List.of(new AliasListOption(7L, "Other P25"),
            new AliasListOption(13L, "Default P25"), new AliasListOption(41L, "Preferred P25"));
        assertEquals(41L, RemoteConnectivityService.preferredAliasList(oldSender, choices).aliasListId());
        TrustedSender noPreference = new TrustedSender(oldSender.senderId(), oldSender.displayName(),
            oldSender.secret(), false, null, oldSender.pairedAtMs(), false);
        assertEquals(13L, RemoteConnectivityService.preferredAliasList(noPreference, choices).aliasListId());
        TrustedSender stalePreference = new TrustedSender(oldSender.senderId(), oldSender.displayName(),
            oldSender.secret(), false, 999L, oldSender.pairedAtMs(), false);
        assertEquals(13L, RemoteConnectivityService.preferredAliasList(stalePreference, choices).aliasListId());
        assertEquals(7L, RemoteConnectivityService.preferredAliasList(noPreference,
            List.of(choices.get(2), choices.getFirst())).aliasListId());
        assertNull(RemoteConnectivityService.preferredAliasList(noPreference, List.of()));
    }

    @Test
    void removesAnUnusedSenderPermanentlyAndRejectsStaleOrMissingIdentities() throws Exception
    {
        try(Installation host = new Installation(mTemp.resolve("unused-sender-host")))
        {
            CreateSenderResult removed = host.remote.createSender(host.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Remove this sender"));
            CreateSenderResult retained = host.remote.createSender(host.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Keep this sender"));
            long revision = host.remote.snapshot().revision();
            assertThrows(RemoteLinkAdministrationService.StaleRevisionException.class,
                () -> host.remote.removeSender(revision - 1, removed.senderId()));
            assertEquals(2, host.remote.snapshot().senders().size());
            var result = host.remote.removeSender(revision, removed.senderId());
            assertEquals(revision + 1, result.revision());
            assertEquals(List.of(retained.senderId()), result.senders().stream().map(sender -> sender.senderId()).toList());
            assertEquals(List.of(retained.senderId()), new RemoteLinkSettingsStore(host.configuration.getDatabasePath())
                .load().trustedSenders().stream().map(sender -> sender.senderId()).toList());
            assertThrows(RemoteLinkAdministrationService.NotFoundException.class,
                () -> host.remote.removeSender(result.revision(), removed.senderId()));
            assertEquals(result.revision(), host.remote.snapshot().revision());
        }
    }

    @Test
    void failedRemovalRestoresRunningRemoteChannelAndRetriesTheSameRemoteRevision() throws Exception
    {
        try(Installation host = new Installation(mTemp.resolve("failed-removal-host")))
        {
            CreateSenderResult pair = host.remote.createSender(host.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Synthetic sender"));
            Channel channel = host.addRemote("Synthetic feed", pair.senderId(), UUID.randomUUID().toString());
            host.configuration.flushConfiguration();
            host.channels.setProcessing(List.of(channel.getConfigurationId()), true);
            await(() -> !host.configuration.getChannelProcessingManager()
                .getProcessingChainsByConfiguration(channel.getConfigurationId(), null).isEmpty());
            long revision = host.remote.snapshot().revision();
            try(Connection connection = SdrTrunkDatabase.open(host.configuration.getDatabasePath());
                Statement statement = connection.createStatement())
            {
                statement.execute("""
                    CREATE TRIGGER refuse_remote_removal BEFORE UPDATE ON application_settings
                    WHEN NEW.key='remote.links'
                    BEGIN SELECT RAISE(ABORT, 'synthetic-removal-refusal'); END
                    """);
            }
            assertThrows(RemoteLinkAdministrationService.UnavailableException.class,
                () -> host.remote.removeSender(revision, pair.senderId()));
            assertEquals(revision, host.remote.snapshot().revision());
            assertEquals(pair.senderId(), new RemoteLinkSettingsStore(host.configuration.getDatabasePath())
                .load().trustedSenders().getFirst().senderId());
            assertEquals(channel.getConfigurationId(), host.configuration.getChannelModel().getChannels()
                .getFirst().getConfigurationId());
            await(() -> !host.configuration.getChannelProcessingManager()
                .getProcessingChainsByConfiguration(channel.getConfigurationId(), null).isEmpty());
            try(Connection connection = SdrTrunkDatabase.open(host.configuration.getDatabasePath());
                Statement statement = connection.createStatement())
            {
                statement.execute("DROP TRIGGER refuse_remote_removal");
            }
            assertTrue(host.remote.removeSender(revision, pair.senderId()).senders().isEmpty());
            assertTrue(host.configuration.getChannelModel().getChannels().isEmpty());
            assertTrue(host.remote.originSnapshot().byChannelConfigurationId().isEmpty());
            assertTrue(host.configuration.getChannelProcessingManager()
                .getProcessingChainsByConfiguration(channel.getConfigurationId(), null).isEmpty());
        }
    }

    @Test
    void committedRemovalReturnsRestartRequiredWhenChannelPublicationFails() throws Exception
    {
        try(Installation host = new Installation(mTemp.resolve("publication-failed-removal-host"), true))
        {
            CreateSenderResult pair = host.remote.createSender(host.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Synthetic sender"));
            Channel channel = host.addRemote("Synthetic feed", pair.senderId(), UUID.randomUUID().toString());
            host.configuration.flushConfiguration();
            host.channels.setProcessing(List.of(channel.getConfigurationId()), true);
            var result = host.remote.removeSender(host.remote.snapshot().revision(), pair.senderId());
            assertTrue(result.restartRequired());
            assertTrue(result.senders().isEmpty());
            assertTrue(host.remote.snapshot().restartRequired(), "The restart warning persists on later polls");
            assertTrue(new RemoteLinkSettingsStore(host.configuration.getDatabasePath()).load().trustedSenders().isEmpty());
            assertTrue(host.remote.originSnapshot().byChannelConfigurationId().isEmpty());
            assertTrue(host.configuration.getChannelProcessingManager()
                .getProcessingChainsByConfiguration(channel.getConfigurationId(), null).isEmpty(),
                "Committed deletion must not restart the removed channel");
        }
    }

    @Test
    void committedRemovalIsSuccessfulAndReleasesAdmissionWhenASourceObserverThrows() throws Exception
    {
        try(Installation host = new Installation(mTemp.resolve("cleanup-failed-removal-host")))
        {
            CreateSenderResult pair = host.remote.createSender(host.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Synthetic sender"));
            Channel first = host.addRemote("First synthetic feed", pair.senderId(), UUID.randomUUID().toString());
            Channel second = host.addRemote("Second synthetic feed", pair.senderId(), UUID.randomUUID().toString());
            host.configuration.flushConfiguration();
            var firstSource = host.remote.acquireSource(first, "synthetic-first-source");
            var secondSource = host.remote.acquireSource(second, "synthetic-second-source");
            firstSource.setOverflowListener(overflow -> { throw new IllegalStateException("Synthetic observer failure"); });
            AtomicBoolean secondStopped = new AtomicBoolean();
            secondSource.setOverflowListener(overflow -> secondStopped.set(!overflow));
            var result = host.remote.removeSender(host.remote.snapshot().revision(), pair.senderId());
            assertTrue(result.senders().isEmpty());
            assertTrue(result.restartRequired());
            assertTrue(secondStopped.get(), "One faulty observer must not strand another removed source");
            assertTrue(host.configuration.getChannelModel().getChannels().isEmpty());
            assertTrue(host.remote.originSnapshot().byChannelConfigurationId().isEmpty());
            assertThrows(io.github.dsheirer.source.SourceException.class,
                () -> host.remote.acquireSource(first, "synthetic-late-source"));
            host.remote.createSender(result.revision(), new RemoteLinkAdministrationService.CreateSenderRequest("Next sender"));
            assertEquals(1, host.remote.snapshot().senders().size(), "Cleanup failure must release administration admission");
        }
    }

    @Test
    void removedSenderCannotFinishAnInFlightAuthentication() throws Exception
    {
        try(Installation host = new Installation(mTemp.resolve("revocation-host")))
        {
            CreateSenderResult pair = host.remote.createSender(host.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Removed receiver"));
            host.remote.start();
            host.remote.updateListener(host.remote.snapshot().revision(),
                new ListenerUpdate(true, "127.0.0.1", availablePort()));
            await(() -> host.remote.snapshot().listener().state() == ListenerState.LISTENING);
            int port = host.remote.snapshot().listener().port();

            try(Socket socket = new Socket(InetAddress.getLoopbackAddress(), port))
            {
                socket.setSoTimeout(2_000);
                DataInputStream input = new DataInputStream(socket.getInputStream());
                DataOutputStream output = new DataOutputStream(socket.getOutputStream());
                RemoteWireProtocol.write(output, new Frame(Type.HELLO, RemoteWireMessages.json(
                    new RemoteWireMessages.Hello(RemoteWireProtocol.VERSION, pair.senderId()))));
                output.flush();
                Frame challenge = RemoteWireProtocol.read(input);
                assertEquals(Type.CHALLENGE, challenge.type());

                host.remote.removeSender(host.remote.snapshot().revision(), pair.senderId());
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(pair.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                mac.update(pair.senderId().getBytes(StandardCharsets.US_ASCII));
                mac.update(challenge.payload());
                RemoteWireProtocol.write(output, new Frame(Type.AUTH, mac.doFinal()));
                output.flush();
                assertEquals(Type.ACCEPT, RemoteWireProtocol.read(input).type());
                assertEquals(-1, input.read(), "removal must close even a just-authenticated socket");
            }
            assertTrue(host.remote.snapshot().senders().isEmpty());
            assertTrue(new RemoteLinkSettingsStore(host.configuration.getDatabasePath()).load().trustedSenders().isEmpty());
        }
    }

    @Test
    void rejectsATrustedSendersExcessiveTrafficOpens() throws Exception
    {
        try(Installation host = new Installation(mTemp.resolve("quota-host")))
        {
            CreateSenderResult pair = host.remote.createSender(host.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Busy receiver"));
            host.remote.start();
            host.remote.updateListener(host.remote.snapshot().revision(),
                new ListenerUpdate(true, "127.0.0.1", availablePort()));
            await(() -> host.remote.snapshot().listener().state() == ListenerState.LISTENING);
            String feedId = UUID.randomUUID().toString();
            try(RemoteLinkTransport.Connection sender = RemoteLinkTransport.connect("127.0.0.1",
                host.remote.snapshot().listener().port(), pair.senderId(), pair.secret(), NO_OP_LISTENER))
            {
                assertTrue(sender.offer(new Frame(Type.CATALOG, RemoteWireMessages.json(
                    new RemoteWireMessages.Catalog(List.of(new RemoteWireMessages.Feed(feedId,
                        "System", "Site", "Feed", "P25_PHASE1", 851_012_500L, true)))))));
                await(() -> !host.remote.snapshot().senders().getFirst().feeds().isEmpty());
                for(int index = 0; index <= 16; index++)
                {
                    assertTrue(sender.offer(new Frame(Type.OPEN, RemoteWireMessages.json(
                        new RemoteWireMessages.Open(UUID.randomUUID().toString(), feedId, false,
                            "PHASE_1", 851_500_000L + index * 12_500L, 1L,
                            System.currentTimeMillis(), 0x123, null, null)))));
                }
                await(() -> !sender.isOpen());
            }
        }
    }

    @Test
    void missingP25AliasListKeepsFeedVisibleAndAliasListCreationStartsSetup() throws Exception
    {
        try(Installation host = new Installation(mTemp.resolve("missing-alias-host")))
        {
            List<AliasListDefinition> savedDefinitions =
                List.copyOf(host.configuration.getAliasModel().aliasListDefinitions());
            host.configuration.getAliasModel().replaceCommittedConfiguration(List.of(), List.of());
            CreateSenderResult pair = host.remote.createSender(host.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Receiver"));
            host.remote.start();
            host.remote.updateListener(host.remote.snapshot().revision(),
                new ListenerUpdate(true, "127.0.0.1", availablePort()));
            await(() -> host.remote.snapshot().listener().state() == ListenerState.LISTENING);

            String feedId = UUID.randomUUID().toString();
            Frame catalog = new Frame(Type.CATALOG, RemoteWireMessages.json(new RemoteWireMessages.Catalog(
                List.of(new RemoteWireMessages.Feed(feedId, "System", "Site", "Feed", "P25_PHASE1",
                    851_012_500L, true)))));
            try(RemoteLinkTransport.Connection sender = RemoteLinkTransport.connect("127.0.0.1",
                host.remote.snapshot().listener().port(), pair.senderId(), pair.secret(), NO_OP_LISTENER))
            {
                assertTrue(sender.offer(catalog));
                await(() -> host.remote.snapshot().senders().getFirst().feeds().stream().anyMatch(feed ->
                    feed.state() == FeedState.PENDING && feed.statusMessage() != null &&
                        feed.statusMessage().contains("Create a P25 Alias List")));
                assertTrue(host.configuration.getChannelModel().getChannels().isEmpty());
                assertTrue(sender.isOpen(), "missing Alias List must not disconnect an authenticated sender");

                host.configuration.getAliasModel().replaceCommittedConfiguration(savedDefinitions, List.of());
                await(() -> host.remote.snapshot().senders().getFirst().feeds().stream().anyMatch(feed ->
                    feedId.equals(feed.feedId()) && feed.channelConfigurationId() != null));
                assertTrue(sender.offer(catalog), "later catalogs must keep the configured feed intact");
                assertEquals(1, host.configuration.getChannelModel().getChannels().size());
            }
        }
    }

    private static int availablePort() throws Exception
    {
        try(ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
        {
            return reservation.getLocalPort();
        }
    }

    @Test
    void removalDrainsConcurrentCatalogAndOpenWorkAndPreservesUnrelatedChannels() throws Exception
    {
        try(Installation host = new Installation(mTemp.resolve("remove-live-sender-host")))
        {
            CreateSenderResult removed = host.remote.createSender(host.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Remove live sender"));
            CreateSenderResult retained = host.remote.createSender(host.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Keep remote sender"));
            Channel local = host.addControl("Keep local channel", 852_012_500L);
            Channel otherRemote = host.addRemote("Keep other remote feed", retained.senderId(), UUID.randomUUID().toString());
            host.configuration.flushConfiguration();
            host.remote.start();
            host.remote.updateListener(host.remote.snapshot().revision(),
                new ListenerUpdate(true, "127.0.0.1", availablePort()));
            await(() -> host.remote.snapshot().listener().state() == ListenerState.LISTENING);
            int port = host.remote.snapshot().listener().port();
            String feedId = UUID.randomUUID().toString();
            String streamId = UUID.randomUUID().toString();
            Frame catalog = new Frame(Type.CATALOG, RemoteWireMessages.json(new RemoteWireMessages.Catalog(
                List.of(new RemoteWireMessages.Feed(feedId, "Synthetic system", "Synthetic site", "Remove feed",
                    "P25_PHASE1", 851_012_500L, true)))));
            Frame open = new Frame(Type.OPEN, RemoteWireMessages.json(new RemoteWireMessages.Open(streamId,
                feedId, true, "PHASE_1", 851_012_500L, 1L, System.currentTimeMillis(), 0x123, null, null)));
            try(RemoteLinkTransport.Connection sender = RemoteLinkTransport.connect("127.0.0.1", port,
                removed.senderId(), removed.secret(), NO_OP_LISTENER))
            {
                assertTrue(sender.offer(catalog));
                await(() -> host.remote.snapshot().senders().stream().filter(peer -> removed.senderId().equals(peer.senderId()))
                    .flatMap(peer -> peer.feeds().stream()).anyMatch(feed -> feed.channelConfigurationId() != null));
                String removedChannelId = host.remote.snapshot().senders().stream()
                    .filter(peer -> removed.senderId().equals(peer.senderId())).findFirst().orElseThrow()
                    .feeds().getFirst().channelConfigurationId();
                assertTrue(sender.offer(open));
                assertTrue(sender.offer(data(streamId, 0L)));
                await(() -> host.remote.originSnapshot().find(removedChannelId) != null &&
                    host.remote.originSnapshot().find(removedChannelId).dependencyState() == DependencyState.READY);
                CountDownLatch burstStarted = new CountDownLatch(1);
                Thread burst = Thread.ofVirtual().start(() -> {
                    try
                    {
                        for(int sequence = 1; sequence < 200 && sender.isOpen(); sequence++)
                        {
                            sender.offer(catalog);
                            sender.offer(open);
                            sender.offer(data(streamId, sequence));
                            burstStarted.countDown();
                            Thread.sleep(1);
                        }
                    }
                    catch(Exception ignored) { }
                });
                assertTrue(burstStarted.await(2, TimeUnit.SECONDS));
                var result = host.remote.removeSender(host.remote.snapshot().revision(), removed.senderId());
                burst.join(2_000);
                assertFalse(burst.isAlive(), "A removed sender cannot continue catalog/open admission");
                await(() -> !sender.isOpen());
                assertEquals(List.of(retained.senderId()), result.senders().stream().map(peer -> peer.senderId()).toList());
                assertEquals(Set.of(local.getConfigurationId(), otherRemote.getConfigurationId()),
                    host.configuration.getChannelModel().getChannels().stream()
                        .map(Channel::getConfigurationId).collect(java.util.stream.Collectors.toSet()));
                assertNull(host.remote.originSnapshot().find(removedChannelId));
                assertTrue(host.configuration.getChannelProcessingManager()
                    .getProcessingChainsByConfiguration(removedChannelId, null).isEmpty());
                assertEquals(List.of(retained.senderId()), new RemoteLinkSettingsStore(host.configuration.getDatabasePath())
                    .load().trustedSenders().stream().map(peer -> peer.senderId()).toList());
                assertThrows(java.io.IOException.class, () -> RemoteLinkTransport.connect("127.0.0.1", port,
                    removed.senderId(), removed.secret(), NO_OP_LISTENER));
            }
        }
    }

    @Test
    void hostDiscoversTwoAuthenticatedSenderCatalogsWithoutAllocatingLocalTuners() throws Exception
    {
        int port = availablePort();
        try(Installation host = new Installation(mTemp.resolve("host"));
            Installation west = new Installation(mTemp.resolve("west"));
            Installation east = new Installation(mTemp.resolve("east")))
        {
            Channel westControl = west.addControl("West", 851_012_500L);
            Channel eastControl = east.addControl("East".repeat(50), 852_012_500L);
            CreateSenderResult westPair = host.remote.createSender(host.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("West receiver"));
            CreateSenderResult eastPair = host.remote.createSender(host.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("East receiver"));
            host.remote.start();
            host.remote.updateListener(host.remote.snapshot().revision(),
                new ListenerUpdate(true, "127.0.0.1", port));
            await(() -> host.remote.snapshot().listener().state() == ListenerState.LISTENING);

            west.remote.updateSenderConnection(west.remote.snapshot().revision(),
                new SenderConnectionUpdate(true, "127.0.0.1", port, westPair.senderId(),
                    westPair.secret(), List.of(westControl.getConfigurationId())));
            east.remote.updateSenderConnection(east.remote.snapshot().revision(),
                new SenderConnectionUpdate(true, "127.0.0.1", port, eastPair.senderId(),
                    eastPair.secret(), List.of(eastControl.getConfigurationId())));
            west.remote.start();
            east.remote.start();

            await(() -> host.remote.snapshot().senders().stream().filter(sender ->
                sender.state() == SenderState.CONNECTED && sender.feeds().size() == 1).count() == 2);
            var discovered = host.remote.snapshot().senders();
            assertEquals(2, discovered.size());
            assertTrue(discovered.stream().anyMatch(sender -> sender.feeds().getFirst().feedId().equals(
                westControl.getConfigurationId())));
            assertTrue(discovered.stream().anyMatch(sender -> sender.feeds().getFirst().feedId().equals(
                eastControl.getConfigurationId())));
            assertTrue(discovered.stream().filter(sender -> eastPair.senderId().equals(sender.senderId()))
                .findFirst().orElseThrow().feeds().getFirst().advertisedName().length() <= 120,
                "long saved names must not kill the catalog connection");
            assertTrue(new RemoteLinkSettingsStore(host.configuration.getDatabasePath()).load().trustedSenders().stream()
                .allMatch(sender -> !sender.autoAdopt() && sender.defaultAliasListId() == null),
                "old disabled sender preferences must not gate automatic setup");
            await(() -> host.remote.snapshot().senders().stream().allMatch(sender ->
                sender.feeds().size() == 1 && sender.feeds().getFirst().channelConfigurationId() != null));
            Channel westRemote = host.configuration.getChannelModel().getChannels().stream().filter(channel ->
                channel.getSourceConfiguration() instanceof SourceConfigRemote source &&
                    westPair.senderId().equals(source.getSenderId()) &&
                    westControl.getConfigurationId().equals(source.getFeedId())).findFirst().orElseThrow();
            Channel eastRemote = host.configuration.getChannelModel().getChannels().stream().filter(channel ->
                channel.getSourceConfiguration() instanceof SourceConfigRemote source &&
                    eastPair.senderId().equals(source.getSenderId()) &&
                    eastControl.getConfigurationId().equals(source.getFeedId())).findFirst().orElseThrow();
            long defaultP25 = host.remote.snapshot().aliasLists().stream()
                .filter(alias -> "Default P25".equals(alias.name())).findFirst().orElseThrow().aliasListId();
            assertEquals(defaultP25, westRemote.getAliasListId());
            assertEquals(defaultP25, eastRemote.getAliasListId());
            assertTrue(westRemote.isAutoStart());
            assertTrue(eastRemote.isAutoStart());
            long feedRevision = host.remote.snapshot().revision();
            host.remote.updateFeed(feedRevision, eastPair.senderId(),
                eastControl.getConfigurationId(), new UpdateFeedRequest(eastRemote.getName(), defaultP25, false));
            long savedFeedRevision = host.remote.snapshot().revision();
            assertThrows(RemoteLinkAdministrationService.StaleRevisionException.class,
                () -> host.remote.updateFeed(feedRevision, eastPair.senderId(),
                    eastControl.getConfigurationId(), new UpdateFeedRequest("Stale edit", defaultP25, true)));
            assertEquals(savedFeedRevision, host.remote.snapshot().revision(),
                "an old remote settings revision must not be retried or advance the revision");
            Channel savedEast = host.configuration.getChannelModel().getChannels().stream().filter(channel ->
                eastRemote.getConfigurationId().equals(channel.getConfigurationId())).findFirst().orElseThrow();
            assertEquals(eastRemote.getName(), savedEast.getName(),
                "an old remote settings revision must not rename the host channel");
            assertFalse(savedEast.isAutoStart(),
                "an old remote settings revision must not re-enable the host channel");
            host.remote.updateSender(host.remote.snapshot().revision(), eastPair.senderId(),
                new UpdateSenderRequest("East receiver", null));
            assertTrue(host.remote.snapshot().senders().stream().filter(sender ->
                eastPair.senderId().equals(sender.senderId())).findFirst().orElseThrow().feeds().stream()
                .anyMatch(feed -> "Host channel is disabled".equals(feed.statusMessage())));
            assertFalse(host.configuration.getChannelModel().getChannels().stream().filter(channel ->
                eastRemote.getConfigurationId().equals(channel.getConfigurationId())).findFirst().orElseThrow()
                .isAutoStart(),
                "catalog setup must not re-enable a host channel that an administrator disabled");
            await(() -> !host.configuration.getChannelProcessingManager()
                .getProcessingChainsByConfiguration(westRemote.getConfigurationId(), null).isEmpty());
            west.remote.close();
            await(() -> host.remote.originSnapshot().find(westRemote.getConfigurationId()) != null &&
                host.remote.originSnapshot().find(westRemote.getConfigurationId()).dependencyState() ==
                    DependencyState.MISSING);

            try(RemoteLinkTransport.Connection synthetic = RemoteLinkTransport.connect("127.0.0.1", port,
                westPair.senderId(), westPair.secret(), NO_OP_LISTENER))
            {
                String streamId = UUID.randomUUID().toString();
                Frame catalog = new Frame(Type.CATALOG, RemoteWireMessages.json(
                    new RemoteWireMessages.Catalog(List.of(new RemoteWireMessages.Feed(
                        westControl.getConfigurationId(), "Test system", "West", "West",
                        "P25_PHASE1", 851_012_500L, true)))));
                assertTrue(synthetic.offer(catalog));
                assertTrue(synthetic.offer(catalog), "repeated catalogs must be idempotent");
                assertTrue(synthetic.offer(new Frame(Type.OPEN, RemoteWireMessages.json(
                    new RemoteWireMessages.Open(streamId, westControl.getConfigurationId(), true,
                        "PHASE_1", 851_012_500L, 1L, System.currentTimeMillis(), null, null, null)))));
                assertTrue(synthetic.offer(new Frame(Type.DATA, RemoteWireMessages.encodeData(
                    new RemoteWireMessages.Data(streamId, 1L, 0L, System.currentTimeMillis(),
                        new byte[300])))));
                await(() -> host.remote.originSnapshot().find(westRemote.getConfigurationId()) != null &&
                    host.remote.originSnapshot().find(westRemote.getConfigurationId()).dependencyState() ==
                        DependencyState.READY);
            }
            await(() -> host.remote.originSnapshot().find(westRemote.getConfigurationId()) != null &&
                host.remote.originSnapshot().find(westRemote.getConfigurationId()).dependencyState() ==
                    DependencyState.MISSING);
            assertEquals(1, host.configuration.getChannelModel().getChannels().stream().filter(channel ->
                channel.getSourceConfiguration() instanceof SourceConfigRemote source &&
                    westPair.senderId().equals(source.getSenderId()) &&
                    westControl.getConfigurationId().equals(source.getFeedId())).count(),
                "reconnect and duplicate catalogs must reuse the saved remote channel");
        }
    }

    @Test
    void queuedRepairCannotRestartAStoppedRemoteChannel() throws Exception
    {
        try(Installation host = new Installation(mTemp.resolve("queued-stopped-host"));
            RemoteControl control = connectControl(host))
        {
            var processing = host.configuration.getChannelProcessingManager();
            synchronized(host.remote)
            {
                processing.stop(control.channel());
                processing.start(control.channel());
                assertTrue(catalogWork(host.remote, "mCatalogSetupRunning").contains(control.senderId()),
                    "Restart must queue repair while the catalog worker is gated");
                processing.stop(control.channel());
            }
            awaitCatalogIdle(host.remote);
            assertNull(processing.getProcessingChain(control.channel()),
                "Queued repair must not restart a channel stopped before repair runs");
            assertTrue(host.remote.snapshot().senders().stream().filter(peer ->
                control.senderId().equals(peer.senderId())).flatMap(peer -> peer.feeds().stream()).anyMatch(feed ->
                    control.feedId().equals(feed.feedId()) && "Host channel is not running".equals(feed.statusMessage())),
                "The stopped channel must remain absent from active service state");
        }
    }

    @Test
    void closeBeforeQueuedRepairCannotRestoreTheClosedGeneration() throws Exception
    {
        try(Installation host = new Installation(mTemp.resolve("queued-close-host"));
            RemoteControl control = connectControl(host))
        {
            var processing = host.configuration.getChannelProcessingManager();
            List<Long> received = new CopyOnWriteArrayList<>();
            CountDownLatch currentReceived = new CountDownLatch(1);
            synchronized(host.remote)
            {
                processing.stop(control.channel());
                processing.start(control.channel());
                RemoteP25BitstreamSource replacement = (RemoteP25BitstreamSource)
                    processing.getProcessingChain(control.channel()).getSource();
                replacement.setRemoteBitstreamListener(packet -> {
                    received.add(packet.sequence());
                    if(packet.sequence() == 1L) currentReceived.countDown();
                });
                assertTrue(control.sender().offer(new Frame(Type.CLOSE, RemoteWireMessages.json(
                    new RemoteWireMessages.Close(control.streamId(), 1L)))));
                assertTrue(control.sender().offer(control.catalog("After close")));
                await(() -> host.remote.snapshot().senders().stream().filter(peer ->
                    control.senderId().equals(peer.senderId())).flatMap(peer -> peer.feeds().stream()).anyMatch(feed ->
                        control.feedId().equals(feed.feedId()) && "After close".equals(feed.advertisedName())));
            }
            awaitCatalogIdle(host.remote);
            assertTrue(control.sender().offer(data(control.streamId(), 0L)));
            assertTrue(control.sender().offer(control.open(2L)));
            assertTrue(control.sender().offer(data(control.streamId(), 2L, 1L)));
            assertTrue(currentReceived.await(3, TimeUnit.SECONDS), "A newer OPEN must still bind the replacement source");
            assertEquals(List.of(1L), received, "The closed generation's packet must never reach the repaired source");
        }
    }

    @Test
    void repairCapturedBeforeCloseCannotRetuneTheNewGeneration() throws Exception
    {
        try(Installation host = new Installation(mTemp.resolve("stale-carrier-host"));
            RemoteControl control = connectControl(host))
        {
            Object peer = privateMap(host.remote, "mPeers").get(control.senderId());
            Object retained = ((Map<?,?>)privateField(peer, "activeOpens")).values().iterator().next();
            RemoteWireMessages.Open oldOpen = (RemoteWireMessages.Open)privateField(retained, "open");
            RemoteLinkTransport.Connection connection = (RemoteLinkTransport.Connection)privateField(peer, "connection");
            RemoteP25BitstreamSource source = (RemoteP25BitstreamSource)host.configuration.getChannelProcessingManager()
                .getProcessingChain(control.channel()).getSource();
            List<Long> received = new CopyOnWriteArrayList<>();
            CountDownLatch firstCurrent = new CountDownLatch(1);
            CountDownLatch nextCurrent = new CountDownLatch(1);
            source.setRemoteBitstreamListener(packet -> {
                received.add(packet.sequence());
                if(packet.sequence() == 1L) firstCurrent.countDown();
                if(packet.sequence() == 3L) nextCurrent.countDown();
            });
            assertTrue(control.sender().offer(new Frame(Type.CLOSE, RemoteWireMessages.json(
                new RemoteWireMessages.Close(control.streamId(), 1L)))));
            assertTrue(control.sender().offer(new Frame(Type.OPEN, RemoteWireMessages.json(new RemoteWireMessages.Open(
                control.streamId(), control.feedId(), true, "PHASE_1", 851_025_000L, 2L,
                System.currentTimeMillis(), null, null, null)))));
            assertTrue(control.sender().offer(data(control.streamId(), 2L, 1L)));
            assertTrue(firstCurrent.await(3, TimeUnit.SECONDS));
            assertEquals(851_025_000L, source.getFrequency());
            long dropped = source.getDroppedPacketCount();
            AtomicLong epoch = (AtomicLong)privateField(source, "mFrequencyEpoch");
            long currentEpoch = epoch.get();
            var repair = RemoteConnectivityService.class.getDeclaredMethod("acceptOpen", String.class,
                RemoteLinkTransport.Connection.class, RemoteWireMessages.Open.class, retained.getClass());
            repair.setAccessible(true);
            repair.invoke(host.remote, control.senderId(), connection, oldOpen, retained);

            assertEquals(851_025_000L, source.getFrequency(), "Stale repair must not retune the current source");
            assertEquals(dropped, source.getDroppedPacketCount(), "Stale repair must not discard current packets");
            assertEquals(currentEpoch, epoch.get(), "Stale repair must not reset the source's frequency epoch");
            assertTrue(control.sender().offer(data(control.streamId(), 1L, 2L)));
            assertTrue(control.sender().offer(data(control.streamId(), 2L, 3L)));
            assertTrue(nextCurrent.await(3, TimeUnit.SECONDS));
            assertEquals(List.of(1L, 3L), received, "Only the current generation may continue delivering packets");
        }
    }

    @Test
    void channelStartedDoesNotWaitForTrafficOwnerWhileHoldingTheActualProcessingMonitor() throws Exception
    {
        Installation host = new Installation(mTemp.resolve("startup-monitor-host"));
        AtomicBoolean safeToClose = new AtomicBoolean(true);
        try(RemoteControl control = connectControl(host))
        {
            var processing = host.configuration.getChannelProcessingManager();
            var parentChain = processing.getProcessingChain(control.channel());
            P25TrafficChannelManager owner = parentChain.getModules().stream()
                .filter(P25TrafficChannelManager.class::isInstance).map(P25TrafficChannelManager.class::cast)
                .findFirst().orElseThrow();
            var lockField = P25TrafficChannelManager.class.getDeclaredField("mLock");
            lockField.setAccessible(true);
            ReentrantLock ownerLock = (ReentrantLock)lockField.get(owner);
            CountDownLatch monitorHeld = new CountDownLatch(1);
            CountDownLatch notifyStarted = new CountDownLatch(1);
            CountDownLatch returned = new CountDownLatch(1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread startup = Thread.ofPlatform().daemon().name("test-remote-control-startup").start(() -> {
                try
                {
                    synchronized(processing)
                    {
                        monitorHeld.countDown();
                        if(!notifyStarted.await(5, TimeUnit.SECONDS)) throw new AssertionError("No START notification");
                        host.remote.channelStarted(control.channel(), new ChannelStartProcessingRequest(control.channel()),
                            parentChain);
                    }
                }
                catch(Throwable error) { failure.set(error); }
                finally { returned.countDown(); }
            });
            safeToClose.set(false);
            try
            {
                assertTrue(monitorHeld.await(3, TimeUnit.SECONDS));
                String trafficStream = UUID.randomUUID().toString();
                assertTrue(control.sender().offer(new Frame(Type.OPEN, RemoteWireMessages.json(
                    new RemoteWireMessages.Open(trafficStream, control.feedId(), false, "PHASE_1", 851_025_000L,
                        1L, System.currentTimeMillis(), 0x491, null, null)))));
                await(ownerLock::isLocked);
                notifyStarted.countDown();
                assertTrue(returned.await(3, TimeUnit.SECONDS),
                    "START must release CPM while the traffic reader waits for that same monitor");
                safeToClose.set(true);
                assertNull(failure.get());
                startup.join(1_000L);
                Map<?,?> bindings = privateMap(host.remote, "mOpenSources");
                await(() -> bindings.size() == 2);
                var trafficChain = processing.getProcessingChainsByConfiguration(control.channel().getConfigurationId(),
                    851_025_000L).getFirst();
                RemoteP25BitstreamSource source = (RemoteP25BitstreamSource)trafficChain.getSource();
                CountDownLatch received = new CountDownLatch(1);
                source.setRemoteBitstreamListener(packet -> received.countDown());
                assertTrue(control.sender().offer(data(trafficStream, 0L)));
                assertTrue(received.await(3, TimeUnit.SECONDS), "The contended traffic OPEN must finish and deliver data");
            }
            finally { notifyStarted.countDown(); }
        }
        finally
        {
            if(safeToClose.get()) host.close();
        }
    }

    @Test
    void renamedRemoteChannelRebindsTheExistingStreamAfterItsOldSourceHasBeenDetached() throws Exception
    {
        int port = availablePort();
        try(Installation host = new Installation(mTemp.resolve("rename-host")))
        {
            CreateSenderResult pair = host.remote.createSender(host.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Synthetic sender"));
            host.remote.start();
            host.remote.updateListener(host.remote.snapshot().revision(), new ListenerUpdate(true, "127.0.0.1", port));
            await(() -> host.remote.snapshot().listener().state() == ListenerState.LISTENING);
            try(RemoteLinkTransport.Connection sender = RemoteLinkTransport.connect("127.0.0.1", port,
                pair.senderId(), pair.secret(), NO_OP_LISTENER))
            {
                String feedId = UUID.randomUUID().toString();
                String streamId = UUID.randomUUID().toString();
                assertTrue(sender.offer(new Frame(Type.CATALOG, RemoteWireMessages.json(
                    new RemoteWireMessages.Catalog(List.of(new RemoteWireMessages.Feed(feedId, "System", "Site",
                        "Original", "P25_PHASE1", 851_012_500L, true)))))));
                await(() -> host.remote.snapshot().senders().stream().anyMatch(peer ->
                    pair.senderId().equals(peer.senderId()) && peer.feeds().stream().anyMatch(feed ->
                        feedId.equals(feed.feedId()) && feed.channelConfigurationId() != null)));
                Channel original = host.configuration.getChannelModel().getChannels().stream().filter(channel ->
                    channel.getSourceConfiguration() instanceof SourceConfigRemote source &&
                        pair.senderId().equals(source.getSenderId()) && feedId.equals(source.getFeedId()))
                    .findFirst().orElseThrow();
                var processing = host.configuration.getChannelProcessingManager();
                await(() -> processing.getProcessingChain(original) != null &&
                    processing.getProcessingChain(original).isProcessing());
                var oldChain = processing.getProcessingChain(original);
                RemoteP25BitstreamSource oldSource = (RemoteP25BitstreamSource)oldChain.getSource();
                CountDownLatch beforeRename = new CountDownLatch(1);
                oldSource.setRemoteBitstreamListener(packet -> beforeRename.countDown());
                assertTrue(sender.offer(new Frame(Type.OPEN, RemoteWireMessages.json(new RemoteWireMessages.Open(
                    streamId, feedId, true, "PHASE_1", 851_018_750L, 1L,
                    System.currentTimeMillis(), null, null, null)))));
                assertTrue(sender.offer(data(streamId, 0L)));
                assertTrue(beforeRename.await(3, TimeUnit.SECONDS), "The original live stream must deliver data");

                var entry = host.channels.get(original.getConfigurationId());
                ChannelDefinition current = entry.channel();
                ChannelDefinition renamed = new ChannelDefinition(current.configurationId(), current.protocolId(),
                    "Official system", "Official site", "Official name", current.radioResolveId(), current.aliasListId(),
                    current.source(), current.settings(), current.frequencyMap(), current.eventLogs(), current.recorders(),
                    current.auxiliaryDecoders(), current.observed());
                host.channels.update(current.configurationId(), renamed, entry.revision());
                assertNull(oldChain.getSource(), "Normal channel shutdown detaches its source before notification");
                Channel replacement = host.configuration.getChannelModel().getChannels().stream().filter(channel ->
                    original.getConfigurationId().equals(channel.getConfigurationId())).findFirst().orElseThrow();
                assertNotSame(original, replacement);
                var newChain = processing.getProcessingChain(replacement);
                RemoteP25BitstreamSource newSource = (RemoteP25BitstreamSource)newChain.getSource();
                assertNotSame(oldSource, newSource);
                assertEquals(851_018_750L, newSource.getFrequency(),
                    "A replacement must start on the retained OPEN carrier rather than the saved configuration");
                awaitCatalogIdle(host.remote);
                CountDownLatch afterRename = new CountDownLatch(2);
                newSource.setRemoteBitstreamListener(packet -> afterRename.countDown());
                assertTrue(sender.offer(data(streamId, 1L)));
                await(() -> afterRename.getCount() == 1);
                //A delayed old stop must not remove the replacement's binding, even for the same saved identity.
                host.remote.channelStopped(original, oldChain);
                host.remote.channelStopped(replacement, oldChain);
                assertTrue(sender.offer(data(streamId, 2L)));
                assertTrue(afterRename.await(3, TimeUnit.SECONDS), "The same stream must still reach the replacement source");
                await(() -> host.remote.originSnapshot().find(replacement.getConfigurationId()).dependencyState() ==
                    DependencyState.READY);
                assertEquals(1, host.configuration.getChannelModel().getChannels().size());
            }
        }
    }

    private RemoteControl connectControl(Installation host) throws Exception
    {
        int port = availablePort();
        CreateSenderResult pair = host.remote.createSender(host.remote.snapshot().revision(),
            new RemoteLinkAdministrationService.CreateSenderRequest("Synthetic sender"));
        host.remote.start();
        host.remote.updateListener(host.remote.snapshot().revision(), new ListenerUpdate(true, "127.0.0.1", port));
        await(() -> host.remote.snapshot().listener().state() == ListenerState.LISTENING);
        RemoteLinkTransport.Connection sender = RemoteLinkTransport.connect("127.0.0.1", port,
            pair.senderId(), pair.secret(), NO_OP_LISTENER);
        String feedId = UUID.randomUUID().toString();
        String streamId = UUID.randomUUID().toString();
        RemoteControl pending = new RemoteControl(sender, pair.senderId(), feedId, streamId, null);
        try
        {
            assertTrue(sender.offer(pending.catalog("Synthetic feed")));
            await(() -> host.remote.snapshot().senders().stream().anyMatch(peer ->
                pair.senderId().equals(peer.senderId()) && peer.feeds().stream().anyMatch(feed ->
                    feedId.equals(feed.feedId()) && feed.channelConfigurationId() != null)));
            Channel channel = host.configuration.getChannelModel().getChannels().stream().filter(saved ->
                saved.getSourceConfiguration() instanceof SourceConfigRemote source &&
                    pair.senderId().equals(source.getSenderId()) && feedId.equals(source.getFeedId()))
                .findFirst().orElseThrow();
            var processing = host.configuration.getChannelProcessingManager();
            await(() -> processing.getProcessingChain(channel) != null &&
                processing.getProcessingChain(channel).isProcessing());
            RemoteP25BitstreamSource source = (RemoteP25BitstreamSource)processing.getProcessingChain(channel).getSource();
            CountDownLatch received = new CountDownLatch(1);
            source.setRemoteBitstreamListener(packet -> received.countDown());
            assertTrue(sender.offer(pending.open(1L)));
            assertTrue(sender.offer(data(streamId, 0L)));
            assertTrue(received.await(3, TimeUnit.SECONDS), "The initial control OPEN must bind and deliver data");
            awaitCatalogIdle(host.remote);
            return new RemoteControl(sender, pair.senderId(), feedId, streamId, channel);
        }
        catch(Throwable failure)
        {
            sender.close();
            throw failure;
        }
    }

    private static Set<?> catalogWork(RemoteConnectivityService remote, String name) throws Exception
    {
        return (Set<?>)privateField(remote, name);
    }

    private static Map<?,?> privateMap(RemoteConnectivityService remote, String name) throws Exception
    {
        return (Map<?,?>)privateField(remote, name);
    }

    private static Object privateField(Object target, String name) throws Exception
    {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void awaitCatalogIdle(RemoteConnectivityService remote) throws Exception
    {
        Set<?> running = catalogWork(remote, "mCatalogSetupRunning");
        Set<?> dirty = catalogWork(remote, "mCatalogSetupDirty");
        await(() -> running.isEmpty() && dirty.isEmpty());
    }

    private record RemoteControl(RemoteLinkTransport.Connection sender, String senderId, String feedId,
                                 String streamId, Channel channel) implements AutoCloseable
    {
        private Frame catalog(String name) throws java.io.IOException
        {
            return new Frame(Type.CATALOG, RemoteWireMessages.json(new RemoteWireMessages.Catalog(List.of(
                new RemoteWireMessages.Feed(feedId, "System", "Site", name, "P25_PHASE1", 851_012_500L, true)))));
        }

        private Frame open(long generation) throws java.io.IOException
        {
            return new Frame(Type.OPEN, RemoteWireMessages.json(new RemoteWireMessages.Open(streamId, feedId,
                true, "PHASE_1", 851_012_500L, generation, System.currentTimeMillis(), null, null, null)));
        }

        @Override
        public void close()
        {
            sender.close();
        }
    }

    private static Frame data(String streamId, long sequence) throws java.io.IOException
    {
        return data(streamId, 1L, sequence);
    }

    private static Frame data(String streamId, long generation, long sequence) throws java.io.IOException
    {
        return new Frame(Type.DATA, RemoteWireMessages.encodeData(new RemoteWireMessages.Data(streamId, generation,
            sequence, System.currentTimeMillis(), new byte[300])));
    }

    private static void await(java.util.function.BooleanSupplier condition) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime() < deadline)
        {
            if(condition.getAsBoolean()) return;
            Thread.sleep(50);
        }
        fail("Remote-link state did not converge before the test deadline");
    }

    private static final class Installation implements AutoCloseable
    {
        private final ConfigurationManager configuration;
        private final RemoteConnectivityService remote;
        private final ChannelAdministrationService channels;
        private final boolean skipConfigurationFlush;

        private Installation(Path root) throws Exception
        {
            this(root, false);
        }

        private Installation(Path root, boolean failRemovalPublication) throws Exception
        {
            SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(root));
            TestPreferences preferences = new TestPreferences(root);
            AliasModel aliases = new AliasModel();
            skipConfigurationFlush = failRemovalPublication;
            configuration = new ConfigurationManager(preferences, null, aliases,
                new EventLogManager(aliases, preferences), null)
            {
                @Override
                public synchronized ChannelConfigurationSnapshot commitAndPublishRemoteSenderRemoval(
                    ChannelConfigurationSnapshot proposed, Set<String> changedIds, String senderId,
                    RemoteLinkSettingsStore.Settings nextSettings, Runnable afterCommit)
                {
                    return super.commitAndPublishRemoteSenderRemoval(proposed, changedIds, senderId, nextSettings, () ->
                    {
                        afterCommit.run();
                        if(failRemovalPublication) throw new IllegalStateException("Synthetic postcommit publication failure");
                    });
                }
            };
            configuration.init();
            channels = ChannelAdministrationServiceTestSupport.create(configuration);
            remote = new RemoteConnectivityService(configuration, channels);
            configuration.getChannelProcessingManager().setP25RemoteBitstreamService(remote);
        }

        private Channel addControl(String name, long frequency)
        {
            Channel channel = new Channel(name, Channel.ChannelType.STANDARD);
            channel.setSystem("Test system");
            channel.setSite(name);
            AliasListDefinition aliasList = configuration.getAliasModel().aliasListDefinitions().stream()
                .filter(alias -> alias.getFamily() == AliasListFamily.P25).findFirst().orElseThrow();
            channel.setAliasListId(aliasList.getId());
            channel.setAliasListName(aliasList.getName());
            channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(frequency);
            channel.setSourceConfiguration(source);
            configuration.getChannelModel().addChannel(channel);
            return channel;
        }

        private Channel addRemote(String name, String senderId, String feedId)
        {
            Channel channel = new Channel(name, Channel.ChannelType.STANDARD);
            AliasListDefinition aliasList = configuration.getAliasModel().aliasListDefinitions().stream()
                .filter(alias -> alias.getFamily() == AliasListFamily.P25).findFirst().orElseThrow();
            channel.setAliasListId(aliasList.getId());
            channel.setAliasListName(aliasList.getName());
            channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
            SourceConfigRemote source = new SourceConfigRemote();
            source.setSenderId(senderId);
            source.setFeedId(feedId);
            source.setFrequency(851_012_500L);
            channel.setSourceConfiguration(source);
            configuration.getChannelModel().addChannel(channel);
            return channel;
        }

        @Override
        public void close()
        {
            try
            {
                remote.close();
            }
            finally
            {
                try
                {
                    configuration.getChannelProcessingManager().shutdown();
                    if(!skipConfigurationFlush) configuration.flushConfiguration();
                }
                finally
                {
                    MyEventBus.getGlobalEventBus().unregister(configuration.getChannelProcessingManager());
                }
            }
        }
    }

    private static final class TestPreferences extends UserPreferences
    {
        private final DirectoryPreference mDirectory;

        private TestPreferences(Path root)
        {
            mDirectory = new DirectoryPreference(preferenceType -> {})
            {
                @Override
                public Path getDirectoryApplicationRoot()
                {
                    return root;
                }
            };
        }

        @Override
        public DirectoryPreference getDirectoryPreference()
        {
            return mDirectory;
        }
    }

    private static final RemoteLinkTransport.Listener NO_OP_LISTENER = new RemoteLinkTransport.Listener()
    {
        @Override
        public void connected(String senderId, RemoteLinkTransport.Connection connection)
        {
        }

        @Override
        public void frame(String senderId, RemoteLinkTransport.Connection connection, Frame frame)
        {
        }

        @Override
        public void disconnected(String senderId, RemoteLinkTransport.Connection connection)
        {
        }
    };
}
