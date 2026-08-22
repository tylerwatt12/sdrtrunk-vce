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
import io.github.dsheirer.sample.Listener;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class DecodeMessageViewServiceTest
{
    private static final String CONFIGURATION_ID = "00000000-0000-0000-0000-000000000001";
    private static final String SECOND_CONFIGURATION_ID = "00000000-0000-0000-0000-000000000002";
    private static final long FREQUENCY = 851_012_500L;
    private static final long SECOND_FREQUENCY = 852_012_500L;

    @Test
    void sessionStartsEmptyAndOnlyReceivesMessagesObservedAfterOpening() throws InterruptedException
    {
        FakeMessageSource source = new FakeMessageSource();
        source.receive(new TestMessage(1_000L, Protocol.APCO25, 0, true, "before"));

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> source);
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            assertNull(session.poll(0, TimeUnit.MILLISECONDS));

            source.receive(new StuffBitsMessage(1_500L, 12, Protocol.APCO25));
            source.receive(new TestMessage(2_000L, Protocol.DMR, 1, true, "after"));
            DecodeMessageViewService.MessageView view = session.poll(2, TimeUnit.SECONDS);
            assertEquals("after", view.text());
            assertNull(session.poll(0, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void decoderCallbackOnlyQueuesUntilWorkerProjectsAndClassifies() throws InterruptedException
    {
        FakeMessageSource source = new FakeMessageSource();
        AtomicReference<Thread> projectionThread = new AtomicReference<>();
        TestMessage message = new TestMessage(1_000L, Protocol.NXDN, 1, false, "x".repeat(3_000),
            projectionThread);
        Thread decoderThread = Thread.currentThread();

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> source);
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            source.receive(message);

            DecodeMessageViewService.MessageView view = session.poll(2, TimeUnit.SECONDS);
            assertEquals(2_048, view.text().length());
            assertTrue(view.text().endsWith("…"));
            assertEquals("NXDN", view.protocol());
            assertEquals("NXDN", view.filterGroup());
            assertEquals("TestMessage", view.filterType());
            assertEquals(1, view.timeslot());
            assertFalse(view.valid());
            assertFalse(decoderThread == projectionThread.get());
        }
    }

    @Test
    void detachesAndRebindsTheExactScopeWithoutDeliveringStaleMessages() throws InterruptedException
    {
        FakeMessageSource first = new FakeMessageSource();
        FakeMessageSource second = new FakeMessageSource();
        AtomicReference<FakeMessageSource> selected = new AtomicReference<>(first);

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> selected.get());
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            first.receive(new TestMessage(1_000L, Protocol.APCO25, 0, true, "first"));
            assertEquals("first", session.poll(2, TimeUnit.SECONDS).text());

            long generation = session.generation();
            selected.set(second);
            session.refresh();
            await(() -> session.generation() > generation && session.isBound() && first.listenerCount() == 0);

            first.receive(new TestMessage(2_000L, Protocol.APCO25, 0, true, "stale"));
            second.receive(new TestMessage(3_000L, Protocol.APCO25_PHASE2, 1, true, "replacement"));
            assertEquals("replacement", session.poll(2, TimeUnit.SECONDS).text());
            assertNull(session.poll(0, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    void lateSessionStartsAtItsOwnLiveEdgeWhileExistingSessionKeepsEarlierObservations() throws Exception
    {
        FakeMessageSource source = new FakeMessageSource();
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        TestMessage blocked = new TestMessage(1_000L, Protocol.APCO25, 0, true, "in progress",
            new AtomicReference<>())
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

        try(DecodeMessageViewService service = new DecodeMessageViewService(selected -> source);
            DecodeMessageViewService.Session existing = service.openSession(scope()))
        {
            await(() -> source.listenerCount() == 1);
            source.receive(blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            source.receive(new TestMessage(2_000L, Protocol.APCO25, 0, true, "queued before open"));
            assertEquals(1, service.getPendingObservationCount(scope()));

            try(DecodeMessageViewService.Session late = service.openSession(scope()))
            {
                releaseProjection.countDown();
                assertEquals("in progress", existing.poll(2, TimeUnit.SECONDS).text());
                assertEquals("queued before open", existing.poll(2, TimeUnit.SECONDS).text());
                assertNull(late.poll(100, TimeUnit.MILLISECONDS));

                source.receive(new TestMessage(3_000L, Protocol.APCO25, 0, true, "after open"));
                assertEquals("after open", existing.poll(2, TimeUnit.SECONDS).text());
                assertEquals("after open", late.poll(2, TimeUnit.SECONDS).text());
            }
        }
        finally
        {
            releaseProjection.countDown();
        }
    }

    @Test
    void validatesTheCurrentSourceBeforeAndAfterProjection() throws Exception
    {
        FakeMessageSource source = new FakeMessageSource();
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        TestMessage message = new TestMessage(1_000L, Protocol.DMR, 0, true, "transition",
            new AtomicReference<>())
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

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> source);
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            long generation = session.generation();
            source.receive(message);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            source.setMatches(false);
            releaseProjection.countDown();

            assertNull(session.poll(100, TimeUnit.MILLISECONDS));
            await(() -> session.generation() > generation && !session.isBound());
        }
        finally
        {
            releaseProjection.countDown();
        }
    }

    @Test
    void boundsEachSessionQueueByDroppingTheOldestMessages() throws InterruptedException
    {
        FakeMessageSource source = new FakeMessageSource();

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> source);
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            int count = DecodeMessageViewService.LIVE_QUEUE_SIZE + 5;

            for(int x = 0; x < count; x++)
            {
                source.receive(new TestMessage(x, Protocol.APCO25, 0, true, "message " + x));
            }

            await(() -> session.droppedCount() >= 5L);
            assertEquals(5L, session.poll(0, TimeUnit.MILLISECONDS).timestampMs());
        }
    }

    @Test
    void saturationAndBlockedProjectionNeverMakeTheDecoderCallbackWait() throws Exception
    {
        FakeMessageSource source = new FakeMessageSource();
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        TestMessage blocked = new TestMessage(1_000L, Protocol.APCO25, 0, true, "blocked",
            new AtomicReference<>())
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

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> source);
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            source.receive(blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();

            for(int x = 0; x < DecodeMessageViewService.INGRESS_QUEUE_SIZE + 16; x++)
            {
                source.receive(new TestMessage(x + 2_000L, Protocol.APCO25, 0, true, "queued"));
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 250, "bounded offers took " + elapsedMs + " ms");
            assertTrue(service.getDroppedObservationCount(scope()) > 0);
            releaseProjection.countDown();
        }
        finally
        {
            releaseProjection.countDown();
        }
    }

    @Test
    void safelyProjectsMalformedMessages() throws InterruptedException
    {
        FakeMessageSource source = new FakeMessageSource();

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> source);
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            source.receive(new BrokenMessage());
            DecodeMessageViewService.MessageView view = session.poll(2, TimeUnit.SECONDS);

            assertEquals(0L, view.timestampMs());
            assertEquals("Unknown", view.protocol());
            assertEquals("UNKNOWN", view.filterGroup());
            assertEquals("BrokenMessage", view.filterType());
            assertEquals(0, view.timeslot());
            assertFalse(view.valid());
            assertEquals("MESSAGE ITEM ENCOUNTERED PARSING ERROR", view.text());
        }
    }

    @Test
    void sharesOneSourceListenerAcrossMultipleSessionsAndRetiresAfterLastClose()
    {
        FakeMessageSource source = new FakeMessageSource();

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> source))
        {
            DecodeMessageViewService.Session first = service.openSession(scope());
            DecodeMessageViewService.Session second = service.openSession(scope());
            await(() -> first.isBound() && second.isBound());
            assertEquals(1, service.getProducerCount());
            assertEquals(1, source.adds());
            assertEquals(1, source.listenerCount());

            first.close();
            assertEquals(1, source.listenerCount());
            second.close();
            await(() -> service.getProducerCount() == 0 && source.listenerCount() == 0);
            assertEquals(1, source.removes());
        }
    }

    @Test
    void finalDisconnectAbandonsQueuedProjectionAndLetsAnotherScopeContinue() throws Exception
    {
        FakeMessageSource retiringSource = new FakeMessageSource();
        FakeMessageSource activeSource = new FakeMessageSource();
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicInteger abandonedProjectionCalls = new AtomicInteger();
        TestMessage blocked = new TestMessage(1_000L, Protocol.APCO25, 0, true, "in progress",
            new AtomicReference<>())
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
        TestMessage abandoned = new TestMessage(2_000L, Protocol.APCO25, 0, true, "abandoned",
            new AtomicReference<>())
        {
            @Override
            public String toString()
            {
                abandonedProjectionCalls.incrementAndGet();
                return super.toString();
            }
        };

        try(DecodeMessageViewService service = new DecodeMessageViewService(selected ->
            selected.equals(scope()) ? retiringSource : activeSource);
            DecodeMessageViewService.Session retiring = service.openSession(scope());
            DecodeMessageViewService.Session active = service.openSession(secondScope()))
        {
            await(() -> retiringSource.listenerCount() == 1 && activeSource.listenerCount() == 1);
            retiringSource.receive(blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            retiringSource.receive(abandoned);
            assertEquals(1, service.getPendingObservationCount(scope()));
            retiring.close();
            activeSource.receive(new TestMessage(3_000L, Protocol.DMR, 1, true, "other scope"));
            releaseProjection.countDown();

            assertEquals("other scope", active.poll(2, TimeUnit.SECONDS).text());
            await(() -> retiringSource.listenerCount() == 0);
            assertEquals(0, abandonedProjectionCalls.get(),
                "queued projection must be discarded after the final viewer disconnects");
        }
        finally
        {
            releaseProjection.countDown();
        }
    }

    @Test
    void serviceCloseDoesNotStartAnotherQueuedProjectionAfterAnInFlightProjectionReturns() throws Exception
    {
        FakeMessageSource source = new FakeMessageSource();
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicInteger abandonedProjectionCalls = new AtomicInteger();
        TestMessage blocked = new TestMessage(1_000L, Protocol.APCO25, 0, true, "in progress",
            new AtomicReference<>())
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
        TestMessage abandoned = new TestMessage(2_000L, Protocol.APCO25, 0, true, "abandoned",
            new AtomicReference<>())
        {
            @Override
            public String toString()
            {
                abandonedProjectionCalls.incrementAndGet();
                return super.toString();
            }
        };
        DecodeMessageViewService service = new DecodeMessageViewService(selected -> source,
            25, TimeUnit.MILLISECONDS);
        DecodeMessageViewService.Session session = service.openSession(scope());

        try
        {
            await(() -> source.listenerCount() == 1);
            source.receive(blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            source.receive(abandoned);
            assertEquals(1, service.getPendingObservationCount(scope()));
            service.close();
            assertFalse(service.isWorkerTerminated());
            releaseProjection.countDown();
            await(service::isWorkerTerminated);

            assertEquals(0, abandonedProjectionCalls.get(),
                "service cleanup must discard queued projection without invoking message toString");
            assertEquals(0, source.listenerCount());
        }
        finally
        {
            releaseProjection.countDown();
            session.close();
            service.close();
        }
    }

    @Test
    void blockedResolverCloseLeavesCleanupToTheWorker() throws Exception
    {
        FakeMessageSource source = new FakeMessageSource();
        CountDownLatch resolverEntered = new CountDownLatch(1);
        CountDownLatch releaseResolver = new CountDownLatch(1);
        DecodeMessageViewService service = new DecodeMessageViewService(scope -> {
            resolverEntered.countDown();

            try
            {
                releaseResolver.await();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }

            return source;
        }, 25, TimeUnit.MILLISECONDS);
        DecodeMessageViewService.Session session = service.openSession(scope());

        assertTrue(resolverEntered.await(2, TimeUnit.SECONDS));
        service.close();
        assertFalse(service.isWorkerTerminated());
        assertEquals(0, source.adds());

        releaseResolver.countDown();
        await(service::isWorkerTerminated);
        assertEquals(source.adds(), source.removes());
        assertFalse(session.isBound());
        source.receive(new TestMessage(3_000L, Protocol.APCO25, 0, true, "after close"));
        assertNull(session.poll(0, TimeUnit.MILLISECONDS));
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

    private static void await(BooleanSupplier condition)
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        while(System.nanoTime() < deadline && !condition.getAsBoolean())
        {
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

    private static DecodeMessageViewService.Scope secondScope()
    {
        return new DecodeMessageViewService.Scope(SECOND_CONFIGURATION_ID, SECOND_FREQUENCY);
    }

    private static class FakeMessageSource implements DecodeMessageViewService.MessageSource
    {
        private final CopyOnWriteArrayList<Listener<IMessage>> mListeners = new CopyOnWriteArrayList<>();
        private final AtomicBoolean mMatches = new AtomicBoolean(true);
        private final AtomicInteger mAdds = new AtomicInteger();
        private final AtomicInteger mRemoves = new AtomicInteger();

        @Override
        public void addListener(Listener<IMessage> listener)
        {
            mAdds.incrementAndGet();
            mListeners.addIfAbsent(listener);
        }

        @Override
        public void removeListener(Listener<IMessage> listener)
        {
            mRemoves.incrementAndGet();
            mListeners.remove(listener);
        }

        @Override
        public boolean matches(DecodeMessageViewService.Scope scope)
        {
            return mMatches.get();
        }

        void receive(IMessage message)
        {
            for(Listener<IMessage> listener: mListeners)
            {
                listener.receive(message);
            }
        }

        void setMatches(boolean matches)
        {
            mMatches.set(matches);
        }

        int listenerCount()
        {
            return mListeners.size();
        }

        int adds()
        {
            return mAdds.get();
        }

        int removes()
        {
            return mRemoves.get();
        }
    }

    private static class TestMessage implements IMessage
    {
        private final long mTimestamp;
        private final Protocol mProtocol;
        private final int mTimeslot;
        private final boolean mValid;
        private final String mText;
        private final AtomicReference<Thread> mProjectionThread;

        private TestMessage(long timestamp, Protocol protocol, int timeslot, boolean valid, String text)
        {
            this(timestamp, protocol, timeslot, valid, text, new AtomicReference<>());
        }

        protected TestMessage(long timestamp, Protocol protocol, int timeslot, boolean valid, String text,
                              AtomicReference<Thread> projectionThread)
        {
            mTimestamp = timestamp;
            mProtocol = protocol;
            mTimeslot = timeslot;
            mValid = valid;
            mText = text;
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
            mProjectionThread.compareAndSet(null, Thread.currentThread());
            return mText;
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
