/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.CreateSenderResult;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.FeedState;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.ListenerState;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.ListenerUpdate;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.SenderConnectionUpdate;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.SenderState;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.UpdateSenderRequest;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.DependencyState;
import io.github.dsheirer.remote.RemoteWireProtocol.Frame;
import io.github.dsheirer.remote.RemoteWireProtocol.Type;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RemoteConnectivityServiceTest
{
    @TempDir
    Path mTemp;

    @Test
    void revokedSenderCannotFinishAnInFlightAuthentication() throws Exception
    {
        Installation host = new Installation(mTemp.resolve("revocation-host"));
        try
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
        finally
        {
            host.remote.close();
        }
    }

    @Test
    void rejectsATrustedSendersExcessiveTrafficOpens() throws Exception
    {
        Installation host = new Installation(mTemp.resolve("quota-host"));
        try
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
        finally
        {
            host.remote.close();
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
        Installation host = new Installation(mTemp.resolve("host"));
        Installation west = new Installation(mTemp.resolve("west"));
        Installation east = new Installation(mTemp.resolve("east"));
        int port;
        try(ServerSocket reservation = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()))
        {
            port = reservation.getLocalPort();
        }

        try
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
            assertTrue(discovered.stream().allMatch(sender -> sender.feeds().getFirst().state() ==
                FeedState.PENDING));
            assertTrue(discovered.stream().anyMatch(sender -> sender.feeds().getFirst().feedId().equals(
                westControl.getConfigurationId())));
            assertTrue(discovered.stream().anyMatch(sender -> sender.feeds().getFirst().feedId().equals(
                eastControl.getConfigurationId())));
            assertTrue(discovered.stream().filter(sender -> eastPair.senderId().equals(sender.senderId()))
                .findFirst().orElseThrow().feeds().getFirst().advertisedName().length() <= 120,
                "long saved names must not kill the catalog connection");

            long aliasListId = host.remote.snapshot().aliasLists().getFirst().aliasListId();
            host.remote.updateSender(host.remote.snapshot().revision(), westPair.senderId(),
                new UpdateSenderRequest("West receiver", true, aliasListId));
            await(() -> host.remote.snapshot().senders().stream().filter(sender ->
                westPair.senderId().equals(sender.senderId())).anyMatch(sender ->
                    sender.feeds().stream().anyMatch(feed -> feed.adopted() &&
                        westControl.getConfigurationId().equals(feed.feedId()))));
            Channel adopted = host.configuration.getChannelModel().getChannels().stream().filter(channel ->
                channel.getSourceConfiguration() instanceof SourceConfigRemote source &&
                    westPair.senderId().equals(source.getSenderId()) &&
                    westControl.getConfigurationId().equals(source.getFeedId())).findFirst().orElseThrow();
            assertTrue(adopted.isAutoStart());
            await(() -> !host.configuration.getChannelProcessingManager()
                .getProcessingChainsByConfiguration(adopted.getConfigurationId(), null).isEmpty());
            west.remote.close();
            await(() -> host.remote.originSnapshot().find(adopted.getConfigurationId()) != null &&
                host.remote.originSnapshot().find(adopted.getConfigurationId()).dependencyState() ==
                    DependencyState.MISSING);

            try(RemoteLinkTransport.Connection synthetic = RemoteLinkTransport.connect("127.0.0.1", port,
                westPair.senderId(), westPair.secret(), NO_OP_LISTENER))
            {
                String streamId = UUID.randomUUID().toString();
                assertTrue(synthetic.offer(new Frame(Type.CATALOG, RemoteWireMessages.json(
                    new RemoteWireMessages.Catalog(List.of(new RemoteWireMessages.Feed(
                        westControl.getConfigurationId(), "Test system", "West", "West",
                        "P25_PHASE1", 851_012_500L, true)))))));
                assertTrue(synthetic.offer(new Frame(Type.OPEN, RemoteWireMessages.json(
                    new RemoteWireMessages.Open(streamId, westControl.getConfigurationId(), true,
                        "PHASE_1", 851_012_500L, 1L, System.currentTimeMillis(), null, null, null)))));
                assertTrue(synthetic.offer(new Frame(Type.DATA, RemoteWireMessages.encodeData(
                    new RemoteWireMessages.Data(streamId, 1L, 0L, System.currentTimeMillis(),
                        new byte[300])))));
                await(() -> host.remote.originSnapshot().find(adopted.getConfigurationId()) != null &&
                    host.remote.originSnapshot().find(adopted.getConfigurationId()).dependencyState() ==
                        DependencyState.READY);
            }
            await(() -> host.remote.originSnapshot().find(adopted.getConfigurationId()) != null &&
                host.remote.originSnapshot().find(adopted.getConfigurationId()).dependencyState() ==
                    DependencyState.MISSING);
        }
        finally
        {
            east.remote.close();
            west.remote.close();
            host.remote.close();
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

    private static final class Installation
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
            channel.setAliasListId(configuration.getAliasModel().aliasListDefinitions().stream()
                .filter(alias -> alias.getFamily() == AliasListFamily.P25).findFirst().orElseThrow().getId());
            channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(frequency);
            channel.setSourceConfiguration(source);
            configuration.getChannelModel().addChannel(channel);
            return channel;
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
