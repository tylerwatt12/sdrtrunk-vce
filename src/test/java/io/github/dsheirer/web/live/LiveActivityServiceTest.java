/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.live;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.application.service.LiveContext;
import io.github.dsheirer.application.service.LiveContextResolver;
import io.github.dsheirer.channel.IChannelDescriptor;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionDescriptor;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySelectionScope;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.stats.StatsLiveEventHub;
import java.time.Duration;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LiveActivityServiceTest
{
    private static final long FREQUENCY = 851_012_500L;

    @Test
    void boundsReadersAndPreservesStableContextIdentity()
    {
        try(Fixture fixture = fixture(new TestChannelDescriptor(FREQUENCY)))
        {
            List<LiveActivityService.OpenStream> streams = new ArrayList<>();

            for(int x = 0; x < 8; x++)
            {
                streams.add(fixture.service().openStream(fixture.selectionId(),
                    LiveActivityService.FeedType.EVENTS, null).orElseThrow());
            }

            assertTrue(fixture.service().openStream(fixture.selectionId(),
                LiveActivityService.FeedType.EVENTS, null).isEmpty());
            LiveActivityService.FeedSnapshot snapshot = streams.getFirst().snapshot();
            assertEquals(fixture.selectionId(), snapshot.context().get("selectionId"));
            assertEquals("EXACT_FREQUENCY", snapshot.context().get("scope"));
            assertEquals(FREQUENCY, snapshot.context().get("frequencyHz"));
            assertEquals(1, snapshot.context().get("timeslot"));
            assertEquals("ACTIVE", snapshot.status());

            streams.removeFirst().close();
            streams.add(fixture.service().openStream(fixture.selectionId(),
                LiveActivityService.FeedType.EVENTS, null).orElseThrow());
            streams.forEach(LiveActivityService.OpenStream::close);
        }
    }

    @Test
    void recreatedFeedUsesANewStreamEpoch()
    {
        String first;

        try(Fixture fixture = fixture(new TestChannelDescriptor(FREQUENCY)))
        {
            first = fixture.service().snapshot(fixture.selectionId(), LiveActivityService.FeedType.EVENTS)
                .orElseThrow().streamId();
        }

        try(Fixture fixture = fixture(new TestChannelDescriptor(FREQUENCY)))
        {
            String second = fixture.service().snapshot(fixture.selectionId(), LiveActivityService.FeedType.EVENTS)
                .orElseThrow().streamId();
            assertNotEquals(first, second);
        }
    }

    @Test
    void messageCallbackDefersRenderingAndIdentifierTraversal() throws Exception
    {
        try(Fixture fixture = fixture(new TestChannelDescriptor(FREQUENCY));
            LiveActivityService.OpenStream stream = fixture.service().openStream(fixture.selectionId(),
                LiveActivityService.FeedType.MESSAGES, null).orElseThrow())
        {
            CountingIdentifierList identifiers = new CountingIdentifierList(2_000);
            BlockingMessage message = new BlockingMessage(identifiers);
            ExecutorService callbackExecutor = Executors.newSingleThreadExecutor(runnable ->
                new Thread(runnable, "synthetic decoder callback"));

            try
            {
                Future<?> callback = callbackExecutor.submit(() ->
                    fixture.chain().getMessageHistory().receive(message));
                callback.get(500, TimeUnit.MILLISECONDS);
                assertTrue(message.renderEntered().await(2, TimeUnit.SECONDS));
                assertNotEquals("synthetic decoder callback", message.renderThread().get().getName());
                assertTrue(message.renderThread().get().getName().contains("live activity worker"));
                assertEquals(0, identifiers.elementsRead(),
                    "decoder callback must not traverse the message identifier collection");
                message.releaseRender().countDown();

                StatsLiveEventHub.LiveEvent event = pollFor(stream.subscription(), "delta", Duration.ofSeconds(3));
                Map<?,?> delta = (Map<?,?>)event.data();
                List<?> upserts = (List<?>)delta.get("upserts");
                LiveMessageDto dto = (LiveMessageDto)upserts.getFirst();
                assertEquals(LiveActivityMapper.MAXIMUM_IDENTIFIERS, dto.identifiers().size());
                assertEquals("rendered away from decoder", dto.text());
                assertTrue(identifiers.readThread().get().getName().contains("live activity worker"));
            }
            finally
            {
                message.releaseRender().countDown();
                callbackExecutor.shutdownNow();
                assertTrue(callbackExecutor.awaitTermination(2, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void overflowRequestsResnapshotAndRetainedRowsStayBounded() throws Exception
    {
        BlockingChannelDescriptor channel = new BlockingChannelDescriptor(FREQUENCY);

        try(Fixture fixture = fixture(channel);
            LiveActivityService.OpenStream stream = fixture.service().openStream(fixture.selectionId(),
                LiveActivityService.FeedType.EVENTS, null).orElseThrow())
        {
            fixture.chain().getDecodeEventHistory().receive(event(channel, 0));
            assertTrue(channel.renderEntered().await(2, TimeUnit.SECONDS));

            //The worker is intentionally paused in display rendering while this synthetic decoder burst fills the
            //capture queue.  This makes overload behavior deterministic without slowing the callback itself.
            for(int x = 1; x <= 2_300; x++)
            {
                fixture.chain().getDecodeEventHistory().receive(event(channel, x));
            }

            channel.releaseRender().countDown();
            StatsLiveEventHub.LiveEvent resnapshot = pollForResnapshot(stream.subscription(), Duration.ofSeconds(5));
            assertTrue(resnapshot.requiresResnapshot());
            LiveActivityService.FeedSnapshot snapshot = awaitRowCount(fixture.service(), fixture.selectionId(),
                LiveActivityService.FeedType.EVENTS, 2_000, Duration.ofSeconds(8));
            assertEquals(2_000, snapshot.rows().size());

            Set<String> ids = new HashSet<>();
            snapshot.rows().forEach(row -> ids.add(((LiveDecodeEventDto)row).id()));
            assertEquals(2_000, ids.size());
            assertTrue(snapshot.sequence() > 0);
        }
        finally
        {
            channel.releaseRender().countDown();
        }
    }

    private static DecodeEvent event(IChannelDescriptor channel, int index)
    {
        return DecodeEvent.builder(DecodeEventType.CALL_GROUP, 10_000L + index)
            .duration(100L)
            .channel(channel)
            .details("event-" + index)
            .protocol(Protocol.APCO25)
            .timeslot(1)
            .build();
    }

    private static Fixture fixture(IChannelDescriptor descriptor)
    {
        Channel channel = new Channel("Dispatch");
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        ProcessingChain chain = new ProcessingChain(channel, new AliasModel());
        String selectionId = "exact-test-context";
        ChannelActivitySelectionDescriptor selection = new ChannelActivitySelectionDescriptor(selectionId,
            "table-1", "row-1", "Metro / Downtown", "Dispatch", ChannelActivitySelectionScope.EXACT_FREQUENCY,
            null, channel.getChannelID(), descriptor.getDownlinkFrequency(), 1, "P25");
        LiveContext context = new LiveContext(selection, null, channel, chain, chain);
        StubLiveContextResolver resolver = new StubLiveContextResolver(context);
        LiveActivityService service = new LiveActivityService(resolver);
        service.start();
        return new Fixture(selectionId, chain, resolver, service);
    }

    private static StatsLiveEventHub.LiveEvent pollFor(StatsLiveEventHub.Subscription subscription, String name,
                                                        Duration timeout) throws InterruptedException
    {
        long deadline = System.nanoTime() + timeout.toNanos();

        while(System.nanoTime() < deadline)
        {
            StatsLiveEventHub.LiveEvent event = subscription.poll(100, TimeUnit.MILLISECONDS);

            if(event != null && name.equals(event.name()))
            {
                return event;
            }
        }

        throw new AssertionError("Timed out waiting for " + name + " event");
    }

    private static StatsLiveEventHub.LiveEvent pollForResnapshot(StatsLiveEventHub.Subscription subscription,
                                                                  Duration timeout) throws InterruptedException
    {
        long deadline = System.nanoTime() + timeout.toNanos();

        while(System.nanoTime() < deadline)
        {
            StatsLiveEventHub.LiveEvent event = subscription.poll(100, TimeUnit.MILLISECONDS);

            if(event != null && event.requiresResnapshot())
            {
                return event;
            }
        }

        throw new AssertionError("Timed out waiting for resnapshot request");
    }

    private static LiveActivityService.FeedSnapshot awaitRowCount(LiveActivityService service, String selectionId,
                                                                   LiveActivityService.FeedType type, int rows,
                                                                   Duration timeout) throws InterruptedException
    {
        long deadline = System.nanoTime() + timeout.toNanos();
        LiveActivityService.FeedSnapshot latest = null;

        while(System.nanoTime() < deadline)
        {
            latest = service.snapshot(selectionId, type).orElseThrow();

            if(latest.rows().size() == rows)
            {
                return latest;
            }

            TimeUnit.MILLISECONDS.sleep(25);
        }

        throw new AssertionError("Timed out waiting for " + rows + " rows; latest count was " +
            (latest != null ? latest.rows().size() : 0));
    }

    private record Fixture(String selectionId, ProcessingChain chain, StubLiveContextResolver resolver,
                           LiveActivityService service) implements AutoCloseable
    {
        @Override
        public void close()
        {
            service.close();
            chain.dispose();
            assertEquals(1, resolver.starts());
            assertEquals(1, resolver.stops());
            assertEquals(0, service.contextCount());
        }
    }

    private static final class StubLiveContextResolver extends LiveContextResolver
    {
        private final LiveContext mContext;
        private final AtomicInteger mStarts = new AtomicInteger();
        private final AtomicInteger mStops = new AtomicInteger();

        private StubLiveContextResolver(LiveContext context)
        {
            super(new ChannelProcessingManager(null, null, null, null, new UserPreferences()));
            mContext = context;
        }

        @Override
        public void start()
        {
            mStarts.incrementAndGet();
        }

        @Override
        public Optional<LiveContext> resolve(String selectionId)
        {
            return mContext.selectionId().equals(selectionId) ? Optional.of(mContext) : Optional.empty();
        }

        @Override
        public void stop()
        {
            mStops.incrementAndGet();
        }

        private int starts()
        {
            return mStarts.get();
        }

        private int stops()
        {
            return mStops.get();
        }
    }

    private static class TestChannelDescriptor implements IChannelDescriptor
    {
        private final long mFrequency;

        private TestChannelDescriptor(long frequency)
        {
            mFrequency = frequency;
        }

        @Override
        public long getDownlinkFrequency()
        {
            return mFrequency;
        }

        @Override
        public long getUplinkFrequency()
        {
            return 0;
        }

        @Override
        public int[] getFrequencyBandIdentifiers()
        {
            return new int[0];
        }

        @Override
        public void setFrequencyBand(IFrequencyBand bandIdentifier)
        {
        }

        @Override
        public boolean isTDMAChannel()
        {
            return true;
        }

        @Override
        public int getTimeslotCount()
        {
            return 2;
        }

        @Override
        public Protocol getProtocol()
        {
            return Protocol.APCO25;
        }

        @Override
        public String toString()
        {
            return "Channel 1";
        }
    }

    private static final class BlockingChannelDescriptor extends TestChannelDescriptor
    {
        private final CountDownLatch mRenderEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseRender = new CountDownLatch(1);

        private BlockingChannelDescriptor(long frequency)
        {
            super(frequency);
        }

        @Override
        public String toString()
        {
            mRenderEntered.countDown();

            try
            {
                mReleaseRender.await(10, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }

            return "Channel 1";
        }

        private CountDownLatch renderEntered()
        {
            return mRenderEntered;
        }

        private CountDownLatch releaseRender()
        {
            return mReleaseRender;
        }
    }

    private static final class CountingIdentifierList extends AbstractList<Identifier>
    {
        private final int mSize;
        private final AtomicInteger mElementsRead = new AtomicInteger();
        private final AtomicReference<Thread> mReadThread = new AtomicReference<>();

        private CountingIdentifierList(int size)
        {
            mSize = size;
        }

        @Override
        public Identifier get(int index)
        {
            mReadThread.compareAndSet(null, Thread.currentThread());
            mElementsRead.incrementAndGet();
            return APCO25RadioIdentifier.createFrom(30_000 + index);
        }

        @Override
        public int size()
        {
            return mSize;
        }

        private int elementsRead()
        {
            return mElementsRead.get();
        }

        private AtomicReference<Thread> readThread()
        {
            return mReadThread;
        }
    }

    private static final class BlockingMessage implements IMessage
    {
        private final List<Identifier> mIdentifiers;
        private final CountDownLatch mRenderEntered = new CountDownLatch(1);
        private final CountDownLatch mReleaseRender = new CountDownLatch(1);
        private final AtomicReference<Thread> mRenderThread = new AtomicReference<>();

        private BlockingMessage(List<Identifier> identifiers)
        {
            mIdentifiers = identifiers;
        }

        @Override
        public long getTimestamp()
        {
            return 123L;
        }

        @Override
        public boolean isValid()
        {
            return true;
        }

        @Override
        public Protocol getProtocol()
        {
            return Protocol.APCO25;
        }

        @Override
        public int getTimeslot()
        {
            return 1;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return mIdentifiers;
        }

        @Override
        public String toString()
        {
            mRenderThread.set(Thread.currentThread());
            mRenderEntered.countDown();

            try
            {
                mReleaseRender.await(10, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }

            return "rendered away from decoder";
        }

        private CountDownLatch renderEntered()
        {
            return mRenderEntered;
        }

        private CountDownLatch releaseRender()
        {
            return mReleaseRender;
        }

        private AtomicReference<Thread> renderThread()
        {
            return mRenderThread;
        }
    }
}
