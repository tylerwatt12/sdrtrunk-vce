/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.protocol.Protocol;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class DecodeMessageViewServiceTest
{
    private static final String CONFIGURATION_ID = "00000000-0000-0000-0000-000000000001";
    private static final long FREQUENCY = 851_012_500L;

    @Test
    void snapshotsNewestFirstWithoutStuffBitsAndBoundsText()
    {
        MessageHistory history = new MessageHistory(200);
        TestMessage older = new TestMessage(1_000L, Protocol.APCO25, 0, true, "older");
        TestMessage newer = new TestMessage(2_000L, Protocol.DMR, 1, false, "x".repeat(3_000));
        history.receive(older);
        history.receive(new StuffBitsMessage(1_500L, 12, Protocol.APCO25));
        history.receive(newer);

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> history);
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            await(() -> session.snapshot().size() == 2);
            List<DecodeMessageViewService.MessageView> snapshot = session.snapshot();

            assertEquals(2, snapshot.size());
            assertEquals(2_000L, snapshot.get(0).timestampMs());
            assertEquals("DMR", snapshot.get(0).protocol());
            assertEquals(1, snapshot.get(0).timeslot());
            assertFalse(snapshot.get(0).valid());
            assertEquals(2_048, snapshot.get(0).text().length());
            assertTrue(snapshot.get(0).text().endsWith("…"));
            assertEquals(1_000L, snapshot.get(1).timestampMs());
        }
    }

    @Test
    void decoderCallbackOnlyQueuesRawMessageUntilConsumerPolls() throws InterruptedException
    {
        MessageHistory history = new MessageHistory(200);
        AtomicReference<Thread> projectionThread = new AtomicReference<>();
        TestMessage message = new TestMessage(1_000L, Protocol.NXDN, 0, true, "queued", new AtomicInteger(),
            projectionThread);
        Thread decoderThread = Thread.currentThread();

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> history);
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            history.receive(message);

            DecodeMessageViewService.MessageView view = session.poll(2, TimeUnit.SECONDS);
            assertEquals("queued", view.text());
            assertFalse(decoderThread == projectionThread.get());
        }
    }

    @Test
    void detachesAndRebindsWhenProcessingChainHistoryChanges() throws InterruptedException
    {
        MessageHistory first = new MessageHistory(200);
        MessageHistory second = new MessageHistory(200);
        AtomicReference<MessageHistory> selected = new AtomicReference<>(first);
        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> selected.get());
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            first.receive(new TestMessage(1_000L, Protocol.APCO25, 0, true, "first"));
            assertEquals("first", session.poll(2, TimeUnit.SECONDS).text());
            first.receive(new TestMessage(1_500L, Protocol.APCO25, 0, true, "unread old binding"));
            await(() -> !session.snapshot().isEmpty() &&
                "unread old binding".equals(session.snapshot().getFirst().text()));

            long generation = session.generation();
            selected.set(second);
            session.refresh();
            await(() -> session.generation() > generation && session.isBound());
            first.receive(new TestMessage(2_000L, Protocol.APCO25, 0, true, "stale"));
            second.receive(new TestMessage(3_000L, Protocol.APCO25_PHASE2, 1, true, "replacement"));
            assertEquals("replacement", session.poll(2, TimeUnit.SECONDS).text());
            assertNull(session.poll(0, TimeUnit.MILLISECONDS));
        }

        second.receive(new TestMessage(4_000L, Protocol.APCO25_PHASE2, 1, true, "after close"));
    }

    @Test
    void boundsTheLiveQueueByDroppingTheOldestMessages() throws InterruptedException
    {
        MessageHistory history = new MessageHistory(200);
        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> history);
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            int messageCount = DecodeMessageViewService.LIVE_QUEUE_SIZE + 5;

            for(int x = 0; x < messageCount; x++)
            {
                history.receive(new TestMessage(x, Protocol.APCO25, 0, true, "message " + x));
            }

            await(() -> !session.snapshot().isEmpty() &&
                session.snapshot().get(0).timestampMs() == messageCount - 1 && session.droppedCount() >= 5L);
            assertEquals(5L, session.poll(0, TimeUnit.MILLISECONDS).timestampMs());
            assertTrue(session.droppedCount() >= 5L);
        }
    }

    @Test
    void safelyProjectsMalformedMessages() throws InterruptedException
    {
        MessageHistory history = new MessageHistory(200);
        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> history);
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            history.receive(new BrokenMessage());
            DecodeMessageViewService.MessageView view = session.poll(2, TimeUnit.SECONDS);

            assertEquals(0L, view.timestampMs());
            assertEquals("Unknown", view.protocol());
            assertEquals(0, view.timeslot());
            assertFalse(view.valid());
            assertEquals("MESSAGE ITEM ENCOUNTERED PARSING ERROR", view.text());
        }
    }

    @Test
    void requiresAnExactConfigurationUuidAndPositiveFrequency()
    {
        assertEquals(CONFIGURATION_ID, scope().configurationId());
        assertThrows(IllegalArgumentException.class,
            () -> new DecodeMessageViewService.Scope("not-a-uuid", FREQUENCY));
        assertThrows(IllegalArgumentException.class,
            () -> new DecodeMessageViewService.Scope(CONFIGURATION_ID, 0));
    }

    @Test
    void sharesOneHistoryListenerAcrossMultipleSessions()
    {
        CountingMessageHistory history = new CountingMessageHistory();

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> history);
            DecodeMessageViewService.Session first = service.openSession(scope());
            DecodeMessageViewService.Session second = service.openSession(scope()))
        {
            await(() -> first.isBound() && second.isBound());
            assertEquals(1, service.getProducerCount());
            assertEquals(1, history.mAdds.get());
        }
    }

    @Test
    void blockedResolverCloseLeavesCleanupToTheWorkerAndBalancesListenerOwnership() throws Exception
    {
        CountingMessageHistory history = new CountingMessageHistory();
        CountDownLatch resolverEntered = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        AtomicInteger resolverCalls = new AtomicInteger();
        DecodeMessageViewService service = new DecodeMessageViewService(scope -> {
            if(resolverCalls.incrementAndGet() == 1)
            {
                resolverEntered.countDown();

                try
                {
                    releaseResolver.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }

            return history;
        }, 25, TimeUnit.MILLISECONDS);
        DecodeMessageViewService.Session session = service.openSession(scope());

        assertTrue(resolverEntered.await(2, TimeUnit.SECONDS));
        service.close();
        assertFalse(service.isWorkerTerminated());
        assertEquals(0, history.mAdds.get());
        assertEquals(0, history.mRemoves.get());

        releaseResolver.countDown();
        await(service::isWorkerTerminated);
        assertEquals(history.mAdds.get(), history.mRemoves.get());
        assertFalse(session.isBound());
        history.receive(new TestMessage(3_000L, Protocol.APCO25, 0, true, "after close"));
        assertNull(session.poll(0, TimeUnit.MILLISECONDS));
    }

    @Test
    void blockedProjectionCloseHasOneQueueConsumerAndNoPostClosePublication() throws Exception
    {
        CountingMessageHistory history = new CountingMessageHistory();
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        TestMessage blocked = new TestMessage(1_000L, Protocol.APCO25, 0, true, "blocked",
            new AtomicInteger(), new AtomicReference<>())
        {
            @Override
            public String toString()
            {
                projectionEntered.countDown();

                try
                {
                    releaseProjection.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return super.toString();
            }
        };
        DecodeMessageViewService service = new DecodeMessageViewService(scope -> history,
            25, TimeUnit.MILLISECONDS);
        DecodeMessageViewService.Session session = service.openSession(scope());
        await(session::isBound);
        history.receive(blocked);
        assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));

        service.close();
        assertFalse(service.isWorkerTerminated());
        assertEquals(0, service.getPendingObservationCount(scope()),
            "the worker owns and has already removed the blocked observation");
        releaseProjection.countDown();
        await(service::isWorkerTerminated);
        assertEquals(1, history.mAdds.get());
        assertEquals(1, history.mRemoves.get());
        assertNull(session.poll(0, TimeUnit.MILLISECONDS));
    }

    @Test
    void livePublicationAlwaysFollowsAuthoritativeCachePublication() throws Exception
    {
        MessageHistory history = new MessageHistory(200);

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> history);
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            history.receive(new TestMessage(4_000L, Protocol.APCO25, 0, true, "ordered"));
            DecodeMessageViewService.MessageView live = session.poll(2, TimeUnit.SECONDS);
            assertEquals(live.messageId(), session.snapshot().getFirst().messageId());
        }
    }

    private static void await(BooleanSupplier condition)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        while(System.nanoTime() < deadline)
        {
            if(condition.getAsBoolean())
            {
                return;
            }

            try
            {
                Thread.sleep(5);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }

        assertTrue(condition.getAsBoolean(), "condition was not met before timeout");
    }

    private static DecodeMessageViewService.Scope scope()
    {
        return new DecodeMessageViewService.Scope(CONFIGURATION_ID, FREQUENCY);
    }

    private static class TestMessage implements IMessage
    {
        private final long mTimestamp;
        private final Protocol mProtocol;
        private final int mTimeslot;
        private final boolean mValid;
        private final String mText;
        private final AtomicInteger mTextCalls;
        private final AtomicReference<Thread> mProjectionThread;

        private TestMessage(long timestamp, Protocol protocol, int timeslot, boolean valid, String text)
        {
            this(timestamp, protocol, timeslot, valid, text, new AtomicInteger());
        }

        private TestMessage(long timestamp, Protocol protocol, int timeslot, boolean valid, String text,
                            AtomicInteger textCalls)
        {
            this(timestamp, protocol, timeslot, valid, text, textCalls, new AtomicReference<>());
        }

        protected TestMessage(long timestamp, Protocol protocol, int timeslot, boolean valid, String text,
                            AtomicInteger textCalls, AtomicReference<Thread> projectionThread)
        {
            mTimestamp = timestamp;
            mProtocol = protocol;
            mTimeslot = timeslot;
            mValid = valid;
            mText = text;
            mTextCalls = textCalls;
            mProjectionThread = projectionThread;
        }

        @Override
        public long getTimestamp()
        {
            return mTimestamp;
        }

        @Override
        public boolean isValid()
        {
            return mValid;
        }

        @Override
        public Protocol getProtocol()
        {
            return mProtocol;
        }

        @Override
        public int getTimeslot()
        {
            return mTimeslot;
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of();
        }

        @Override
        public String toString()
        {
            mTextCalls.incrementAndGet();
            mProjectionThread.compareAndSet(null, Thread.currentThread());
            return mText;
        }
    }

    private static class CountingMessageHistory extends MessageHistory
    {
        private final AtomicInteger mAdds = new AtomicInteger();
        private final AtomicInteger mRemoves = new AtomicInteger();

        private CountingMessageHistory()
        {
            super(200);
        }

        @Override
        public void addListener(io.github.dsheirer.sample.Listener<IMessage> listener)
        {
            mAdds.incrementAndGet();
            super.addListener(listener);
        }

        @Override
        public void removeListener(io.github.dsheirer.sample.Listener<IMessage> listener)
        {
            mRemoves.incrementAndGet();
            super.removeListener(listener);
        }
    }

    private static class BrokenMessage implements IMessage
    {
        @Override
        public long getTimestamp()
        {
            throw new IllegalStateException("broken timestamp");
        }

        @Override
        public boolean isValid()
        {
            throw new IllegalStateException("broken validity");
        }

        @Override
        public Protocol getProtocol()
        {
            throw new IllegalStateException("broken protocol");
        }

        @Override
        public int getTimeslot()
        {
            throw new IllegalStateException("broken timeslot");
        }

        @Override
        public List<Identifier> getIdentifiers()
        {
            return List.of();
        }

        @Override
        public String toString()
        {
            throw new IllegalStateException("broken text");
        }
    }
}
