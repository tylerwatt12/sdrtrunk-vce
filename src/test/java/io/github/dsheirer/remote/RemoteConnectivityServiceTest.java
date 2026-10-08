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
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.eventbus.MyEventBus;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RemoteConnectivityServiceTest
{
    @TempDir
    Path mTemp;

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
    void revokedSenderCannotFinishAnInFlightAuthentication() throws Exception
    {
        try(Installation host = new Installation(mTemp.resolve("revocation-host")))
        {
            CreateSenderResult pair = host.remote.createSender(host.remote.snapshot().revision(),
                new RemoteLinkAdministrationService.CreateSenderRequest("Revoked receiver"));
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

                host.remote.revokeSender(host.remote.snapshot().revision(), pair.senderId());
                Mac mac = Mac.getInstance("HmacSHA256");
                mac.init(new SecretKeySpec(pair.secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
                mac.update(pair.senderId().getBytes(StandardCharsets.US_ASCII));
                mac.update(challenge.payload());
                RemoteWireProtocol.write(output, new Frame(Type.AUTH, mac.doFinal()));
                output.flush();
                assertEquals(Type.ACCEPT, RemoteWireProtocol.read(input).type());
                assertEquals(-1, input.read(), "revocation must close even a just-authenticated socket");
            }
            assertEquals(SenderState.REVOKED, host.remote.snapshot().senders().getFirst().state());
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
            assertTrue(new RemoteLinkSettingsStore(mTemp.resolve("host")).load().trustedSenders().stream()
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

        private Installation(Path root) throws Exception
        {
            SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(root));
            TestPreferences preferences = new TestPreferences(root);
            AliasModel aliases = new AliasModel();
            configuration = new ConfigurationManager(preferences, null, aliases,
                new EventLogManager(aliases, preferences), null);
            configuration.init();
            remote = new RemoteConnectivityService(configuration, preferences,
                ChannelAdministrationServiceTestSupport.create(configuration));
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
                    configuration.flushConfiguration();
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
