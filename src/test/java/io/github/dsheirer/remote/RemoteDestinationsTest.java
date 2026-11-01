/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.remote;

import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.event.ChannelStartProcessingRequest;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.eventbus.MyEventBus;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.P25P1NACPreloadDataContent;
import io.github.dsheirer.module.log.EventLogManager;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.remote.RemoteLinkAdministrationService.SenderConnectionUpdate;
import io.github.dsheirer.remote.RemoteWireProtocol.Frame;
import io.github.dsheirer.remote.RemoteWireProtocol.Type;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import io.github.dsheirer.util.concurrent.BoundedMpscReferenceQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class RemoteDestinationsTest
{
    @TempDir Path mTemp;

    @Test
    void copiesFourSitesAndTrafficToALateDestinationWithoutRestartingTheExistingConnection() throws Exception
    {
        try(Installation sender = new Installation(mTemp.resolve("four-sites"));
            Probe demo = new Probe(); Probe debug = new Probe())
        {
            List<FeedChain> sites = new ArrayList<>();
            for(int site = 0; site < 4; site++) sites.add(sender.addControl("Site " + site, 851_012_500L + site * 25_000L));
            List<String> feeds = sites.stream().map(chain -> chain.channel.getConfigurationId()).toList();
            sender.remote.updateSenderConnection(sender.remote.snapshot().revision(), demo.request(feeds));
            sender.remote.start();
            sites.forEach(sender::start);
            FeedChain traffic = sender.addTraffic(sites.getFirst().channel, 852_012_500L);
            sender.start(traffic);
            await(() -> demo.opens.size() == 5);
            var originalDemo = demo.connection.get();
            var preserved = new RemoteLinkSettingsStore(sender.configuration.getDatabasePath()).load().destinations().getFirst();

            sender.remote.createSenderConnection(sender.remote.snapshot().revision(), debug.request(feeds));
            String debugId = sender.remote.snapshot().senderConnections().getLast().destinationId();
            await(() -> debug.opens.size() == 5 && debug.catalogHas(feeds));
            assertTrue(demo.catalogHas(feeds));
            sites.forEach(chain -> assertEquals(1, chain.listeners.size()));
            assertEquals(1, traffic.listeners.size());
            for(int index = 0; index < sites.size(); index++) sites.get(index).emit((byte)(index + 1));
            traffic.emit((byte)99);
            await(() -> demo.data.size() == 5 && debug.data.size() == 5);
            for(var first: demo.data)
            {
                var second = debug.data.stream().filter(data -> data.streamId().equals(first.streamId())).findFirst().orElseThrow();
                assertEquals(first.generation(), second.generation());
                assertEquals(first.sequence(), second.sequence());
                assertEquals(first.captureTimestamp(), second.captureTimestamp());
                assertArrayEquals(first.dibits(), second.dibits());
            }
            sender.remote.updateSenderConnection(sender.remote.snapshot().revision(), debugId,
                new SenderConnectionUpdate(false, "127.0.0.1", debug.server.port(), debug.senderId, "", feeds));
            await(() -> !debug.connection.get().isOpen());
            sites.getFirst().emit((byte)100);
            await(() -> demo.data.size() == 6);
            sender.remote.removeSenderConnection(sender.remote.snapshot().revision(), debugId);
            sites.getFirst().emit((byte)101);
            await(() -> demo.data.size() == 7);
            assertSame(originalDemo, demo.connection.get());
            assertTrue(originalDemo.isOpen());
            assertEquals(1, demo.connections.get());
            assertEquals(preserved, new RemoteLinkSettingsStore(sender.configuration.getDatabasePath()).load().destinations().getFirst());
            assertEquals(1, sites.getFirst().listeners.size());
        }
    }

    @Test
    void reconnectsOneDestinationWithoutRebindingTheHealthyDestination() throws Exception
    {
        try(Installation sender = new Installation(mTemp.resolve("reconnect")); Probe demo = new Probe(); Probe debug = new Probe())
        {
            FeedChain chain = sender.addControl("Site", 851_012_500L);
            var feeds = List.of(chain.channel.getConfigurationId());
            sender.remote.updateSenderConnection(sender.remote.snapshot().revision(), demo.request(feeds));
            sender.remote.createSenderConnection(sender.remote.snapshot().revision(), debug.request(feeds));
            sender.remote.start(); sender.start(chain);
            await(() -> demo.opens.size() == 1 && debug.opens.size() == 1);
            var originalDemo = demo.connection.get();
            var originalDebug = debug.connection.get();
            originalDebug.close();
            chain.emit((byte)1);
            await(() -> demo.data.size() == 1);
            await(() -> debug.connections.get() == 2 && debug.opens.size() == 2);
            chain.emit((byte)2);
            await(() -> demo.data.size() == 2 && debug.data.stream().anyMatch(data -> data.dibits()[0] == 2));
            assertSame(originalDemo, demo.connection.get());
            assertEquals(1, demo.connections.get());
            assertTrue(originalDemo.isOpen());
            assertFalse(originalDebug.isOpen());
            assertEquals(demo.opens.getFirst().streamId(), debug.opens.getLast().streamId());
            assertEquals(1, chain.listeners.size());
        }
    }

    @Test
    void aStalledAuthenticationDoesNotDelayAnotherDestination() throws Exception
    {
        try(Installation sender = new Installation(mTemp.resolve("stalled-auth"));
            ServerSocket stalled = new ServerSocket(0, 1, InetAddress.getLoopbackAddress()); Probe healthy = new Probe())
        {
            stalled.setSoTimeout(3_000);
            sender.remote.updateSenderConnection(sender.remote.snapshot().revision(), new SenderConnectionUpdate(true,
                "127.0.0.1", stalled.getLocalPort(), UUID.randomUUID().toString(), "synthetic-stalled-key", List.of()));
            sender.remote.createSenderConnection(sender.remote.snapshot().revision(), healthy.request(List.of()));
            sender.remote.start();
            try(Socket waiting = stalled.accept())
            {
                waiting.setSoTimeout(3_000);
                assertEquals(Type.HELLO, RemoteWireProtocol.read(new DataInputStream(waiting.getInputStream())).type());
                // The first destination still awaits CHALLENGE; the healthy connection must beat its five-second timeout.
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> await(() -> healthy.connections.get() == 1 &&
                    sender.remote.snapshot().senderConnections().getLast().state() ==
                        RemoteLinkAdministrationService.SenderConnectionState.CONNECTED));
            }
        }
    }

    @Test
    void aBlockedWriterSaturatesOnlyItsOwnQueueAndNeverWritesOnTheProducer() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Thread> writer = new AtomicReference<>();
        try(Installation sender = new Installation(mTemp.resolve("blocked")); Probe healthy = new Probe(); Probe blocked = new Probe())
        {
            FeedChain chain = sender.addControl("Site", 851_012_500L);
            var feeds = List.of(chain.channel.getConfigurationId());
            sender.remote.updateSenderConnection(sender.remote.snapshot().revision(), healthy.request(feeds));
            sender.remote.createSenderConnection(sender.remote.snapshot().revision(), blocked.request(feeds));
            String blockedId = sender.remote.snapshot().senderConnections().getLast().destinationId();
            sender.remote.start(); sender.start(chain);
            await(() -> healthy.opens.size() == 1 && blocked.opens.size() == 1);
            var healthySession = healthy.connection.get();
            var blockedSession = outboundConnection(sender.remote, blockedId);
            var original = (DataOutputStream)field(blockedSession, "mOutput");
            var output = blockedSession.getClass().getDeclaredField("mOutput");
            output.setAccessible(true);
            output.set(blockedSession, new DataOutputStream(new OutputStream()
            {
                @Override public void write(int value) throws IOException { waitForRelease(); original.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException
                { waitForRelease(); original.write(bytes, offset, length); }
                @Override public void flush() throws IOException { original.flush(); }
                private void waitForRelease() throws IOException
                {
                    writer.compareAndSet(null, Thread.currentThread()); entered.countDown();
                    try
                    {
                        if(!release.await(10, TimeUnit.SECONDS)) throw new IOException("Test writer timed out");
                    }
                    catch(InterruptedException exception)
                    { Thread.currentThread().interrupt(); throw new IOException("Test writer interrupted"); }
                }
            }));
            chain.emit((byte)7);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            await(() -> healthy.data.size() == 1);
            // Batches stay below both queue capacities; only the blocked host accumulates 1,024 queued frames.
            for(int batch = 0; batch < 12; batch++)
            {
                assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                { for(int packet = 0; packet < 100; packet++) chain.emit((byte)8); });
                int expected = 1 + (batch + 1) * 100;
                await(() -> healthy.data.size() >= expected);
            }
            await(() -> !blockedSession.isOpen());
            assertSame(healthySession, healthy.connection.get());
            assertTrue(healthySession.isOpen());
            assertEquals(1, healthy.connections.get());
            assertEquals("remote-p25-write", writer.get().getName());
            assertEquals(1, chain.listeners.size());
        }
        finally { release.countDown(); }
    }

    @Test
    void fullCaptureHandoffAndContendedLifecycleLockNeverBlockTheBitstreamCallback() throws Exception
    {
        try(Installation sender = new Installation(mTemp.resolve("full-handoff")); Probe destination = new Probe())
        {
            FeedChain chain = sender.addControl("Site", 851_012_500L);
            sender.remote.updateSenderConnection(sender.remote.snapshot().revision(),
                destination.request(List.of(chain.channel.getConfigurationId())));
            sender.remote.start(); sender.start(chain);
            await(() -> destination.opens.size() == 1);
            Object exports = field(sender.remote, "mExports");
            Thread publisher = (Thread)field(sender.remote, "mPublisher");
            BoundedMpscReferenceQueue<?> handoff = (BoundedMpscReferenceQueue<?>)field(sender.remote, "mOutboundPackets");
            synchronized(exports)
            {
                chain.emit((byte)1);
                await(() -> publisher.getState() == Thread.State.BLOCKED);
                assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                { for(int packet = 0; packet < 4_096; packet++) chain.emit((byte)2); });
                assertEquals(0, handoff.capacity() - handoff.size());
                assertEquals(2_048, handoff.size());
                assertTrue(destination.connection.get().isOpen());
            }
        }
    }

    @Test
    void lifecycleOverflowDiscardsTheOldControlOpenBeforeReconnectAndLateJoin() throws Exception
    {
        try(Installation sender = new Installation(mTemp.resolve("lost-lifecycle"));
            Probe demo = new Probe(); Probe debug = new Probe())
        {
            FeedChain anchor = sender.addControl("Anchor site", 851_012_500L);
            FeedChain old = sender.addControl("Retuned site", 852_012_500L);
            List<String> feeds = List.of(anchor.channel.getConfigurationId(), old.channel.getConfigurationId());
            sender.remote.updateSenderConnection(sender.remote.snapshot().revision(), demo.request(feeds));
            sender.remote.start(); sender.start(anchor);
            await(() -> demo.opens.size() == 1);
            var originalSession = demo.connection.get();
            Object exports = field(sender.remote, "mExports");
            Thread publisher = (Thread)field(sender.remote, "mPublisher");
            BoundedMpscReferenceQueue<?> handoff = (BoundedMpscReferenceQueue<?>)field(sender.remote, "mOutboundPackets");
            FeedChain replacement = sender.chain(old.channel, 853_012_500L);
            synchronized(exports)
            {
                anchor.emit((byte)1);
                await(() -> publisher.getState() == Thread.State.BLOCKED);
                // Keep the old OPEN queued, then force its CLOSE and the replacement OPEN to overflow.
                sender.start(old);
                for(int packet = 0; packet < 4_096; packet++) old.emit((byte)2);
                assertEquals(0, handoff.capacity() - handoff.size());
                sender.start(replacement);
                assertTrue(((AtomicBoolean)field(sender.remote, "mOutboundLifecycleLost")).get());
                assertEquals(0, old.listeners.size());
                assertEquals(0, replacement.listeners.size(), "A full queue refuses the replacement until recovery");
            }
            await(() -> demo.connections.get() == 2 && demo.opens.stream().anyMatch(open ->
                open.feedId().equals(old.channel.getConfigurationId()) && open.frequency() == 853_012_500L));
            assertEquals(1, replacement.listeners.size());
            assertFalse(originalSession.isOpen());
            sender.remote.createSenderConnection(sender.remote.snapshot().revision(), debug.request(feeds));
            await(() -> debug.opens.size() >= 2);
            replacement.emit((byte)9);
            await(() -> demo.data.stream().anyMatch(data -> data.dibits()[0] == 9) &&
                debug.data.stream().anyMatch(data -> data.dibits()[0] == 9));
            for(Probe destination: List.of(demo, debug))
            {
                var retuned = destination.opens.stream().filter(open ->
                    open.feedId().equals(old.channel.getConfigurationId())).toList();
                assertEquals(1, retuned.size(), "A lost CLOSE must not leave a second control stream in the registry");
                assertEquals(853_012_500L, retuned.getFirst().frequency());
            }
            assertEquals(2, debug.opens.size());
        }
    }

    private static RemoteLinkTransport.Connection outboundConnection(RemoteConnectivityService service, String id) throws Exception
    {
        Object runtime = ((Map<?,?>)field(service, "mDestinations")).get(id);
        return (RemoteLinkTransport.Connection)field(field(runtime, "session"), "connection");
    }
    private static Object field(Object target, String name) throws Exception
    {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void await(BooleanSupplier condition) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while(System.nanoTime() < deadline) { if(condition.getAsBoolean()) return; Thread.sleep(2); }
        fail("Remote destinations did not converge before the test deadline");
    }

    private static final class Probe implements RemoteLinkTransport.Listener, AutoCloseable
    {
        private final String senderId = UUID.randomUUID().toString(), secret = "synthetic-" + UUID.randomUUID();
        private final RemoteLinkTransport.Server server;
        private final AtomicReference<RemoteLinkTransport.Connection> connection = new AtomicReference<>();
        private final AtomicInteger connections = new AtomicInteger();
        private final List<RemoteWireMessages.Catalog> catalogs = new CopyOnWriteArrayList<>();
        private final List<RemoteWireMessages.Open> opens = new CopyOnWriteArrayList<>();
        private final List<RemoteWireMessages.Data> data = new CopyOnWriteArrayList<>();
        private Probe() throws IOException
        { server = RemoteLinkTransport.listen("127.0.0.1", 0, id -> senderId.equals(id) ? secret : null, this); }
        private SenderConnectionUpdate request(List<String> feeds)
        { return new SenderConnectionUpdate(true, "127.0.0.1", server.port(), senderId, secret, feeds); }
        private boolean catalogHas(List<String> feeds)
        { return catalogs.stream().anyMatch(catalog -> catalog.feeds().stream().map(RemoteWireMessages.Feed::feedId).toList().containsAll(feeds)); }
        @Override public void connected(String id, RemoteLinkTransport.Connection current)
        { connection.set(current); connections.incrementAndGet(); }
        @Override public void frame(String id, RemoteLinkTransport.Connection current, Frame frame)
        {
            try
            {
                switch(frame.type())
                {
                    case CATALOG -> catalogs.add(RemoteWireMessages.parse(frame.payload(), RemoteWireMessages.Catalog.class));
                    case OPEN -> opens.add(RemoteWireMessages.parse(frame.payload(), RemoteWireMessages.Open.class));
                    case DATA -> data.add(RemoteWireMessages.decodeData(frame.payload()));
                    default -> { }
                }
            }
            catch(IOException exception) { throw new AssertionError("Invalid outbound frame", exception); }
        }
        @Override public void disconnected(String id, RemoteLinkTransport.Connection current) {}
        @Override public void close() { server.close(); }
    }

    private static final class FeedChain extends ProcessingChain
    {
        private final Channel channel;
        private final List<Listener<ByteBuffer>> listeners = new CopyOnWriteArrayList<>();
        private FeedChain(Channel channel, AliasModel aliases, long frequency)
        {
            super(channel, aliases); this.channel = channel;
            setSource(new RemoteP25BitstreamSource(frequency, P25RemotePhase.PHASE_1, "test-source"));
        }
        @Override public void addDemodulatedBitstreamListener(Listener<ByteBuffer> listener) { listeners.add(listener); }
        @Override public void removeDemodulatedBitstreamListener(Listener<ByteBuffer> listener) { listeners.remove(listener); }
        private void emit(byte marker)
        {
            byte[] packet = new byte[300]; Arrays.fill(packet, marker);
            for(var listener: listeners) listener.receive(ByteBuffer.wrap(packet));
        }
    }

    private static final class Installation implements AutoCloseable
    {
        private final ConfigurationManager configuration;
        private final RemoteConnectivityService remote;
        private final List<FeedChain> chains = new ArrayList<>();
        private Installation(Path root) throws Exception
        {
            SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(root));
            UserPreferences preferences = new UserPreferences()
            {
                private final DirectoryPreference directory = new DirectoryPreference(type -> {})
                { @Override public Path getDirectoryApplicationRoot() { return root; } };
                @Override public DirectoryPreference getDirectoryPreference() { return directory; }
            };
            AliasModel aliases = new AliasModel();
            configuration = new ConfigurationManager(preferences, null, aliases, new EventLogManager(aliases, preferences));
            configuration.init();
            remote = new RemoteConnectivityService(configuration, ChannelAdministrationServiceTestSupport.create(configuration));
        }
        private FeedChain addControl(String name, long frequency)
        {
            Channel channel = channel(name, Channel.ChannelType.STANDARD, frequency);
            configuration.getChannelModel().addChannel(channel); return chain(channel, frequency);
        }
        private FeedChain addTraffic(Channel parent, long frequency)
        {
            Channel channel = channel("Traffic", Channel.ChannelType.TRAFFIC, frequency);
            channel.setConfigurationId(parent.getConfigurationId()); return chain(channel, frequency);
        }
        private Channel channel(String name, Channel.ChannelType type, long frequency)
        {
            Channel channel = new Channel(name, type); channel.setSystem("Test system"); channel.setSite(name);
            var alias = configuration.getAliasModel().aliasListDefinitions().stream()
                .filter(list -> list.getFamily() == AliasListFamily.P25).findFirst().orElseThrow();
            channel.setAliasListId(alias.getId()); channel.setAliasListName(alias.getName());
            channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
            SourceConfigTuner source = new SourceConfigTuner(); source.setFrequency(frequency); channel.setSourceConfiguration(source);
            return channel;
        }
        private FeedChain chain(Channel channel, long frequency)
        { FeedChain chain = new FeedChain(channel, configuration.getAliasModel(), frequency); chains.add(chain); return chain; }
        private void start(FeedChain chain)
        {
            var request = new ChannelStartProcessingRequest(chain.channel);
            if(chain.channel.isTrafficChannel()) request.addPreloadDataContent(new P25P1NACPreloadDataContent(0x123));
            remote.channelStarted(chain.channel, request, chain);
        }
        @Override public void close()
        {
            remote.close(); chains.forEach(FeedChain::dispose);
            try { configuration.getChannelProcessingManager().shutdown(); configuration.flushConfiguration(); }
            finally { MyEventBus.getGlobalEventBus().unregister(configuration.getChannelProcessingManager()); }
        }
    }
}
