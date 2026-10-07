/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.filter.AllPassFilter;
import io.github.dsheirer.filter.Filter;
import io.github.dsheirer.filter.FilterElement;
import io.github.dsheirer.filter.FilterSet;
import io.github.dsheirer.channel.metadata.activity.ConventionalLiveSources;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.identifier.Identifier;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.ComplexSource;
import io.github.dsheirer.source.SourceEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
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
            assertFalse(view.filterKey().isBlank());
            assertEquals("Other/Unlisted", view.filterLabel());
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
    void rebindPublishesTheCompleteSameOrDifferentCatalogWithItsGeneration()
    {
        FakeMessageSource first = new FakeMessageSource(classifier("Known Messages", "Known Type"));
        FakeMessageSource sameCatalog = new FakeMessageSource(classifier("Known Messages", "Known Type"));
        FakeMessageSource differentCatalog = new FakeMessageSource(classifier("Different Messages", "Rare Type"));
        AtomicReference<FakeMessageSource> selected = new AtomicReference<>(first);

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> selected.get());
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            DecodeMessageViewService.SourceState firstState = session.sourceState();
            assertEquals(CONFIGURATION_ID, firstState.configurationId());
            assertNotNull(firstState.filterCatalog());

            selected.set(sameCatalog);
            session.refresh();
            await(() -> session.generation() > firstState.generation() && session.isBound());
            DecodeMessageViewService.SourceState sameState = session.sourceState();
            assertEquals(firstState.filterCatalog().signature(), sameState.filterCatalog().signature());

            selected.set(differentCatalog);
            session.refresh();
            await(() -> session.generation() > sameState.generation() && session.isBound());
            DecodeMessageViewService.SourceState differentState = session.sourceState();
            assertNotEquals(sameState.filterCatalog().signature(), differentState.filterCatalog().signature());
            assertEquals(List.of("Rare Type"), differentState.filterCatalog().groups().getFirst().children().stream()
                .map(io.github.dsheirer.filter.FilterCatalog.Node::label).toList());
        }
    }

    @Test
    void catalogConstructionFailureUsesFallbackWithoutBreakingLiveDelivery() throws InterruptedException
    {
        FakeMessageSource source = new FakeMessageSource();
        source.setThrowClassifier(true);

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> source);
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            assertEquals(MessageFilterCatalog.fallback().catalog(), session.filterCatalog());
            source.receive(new TestMessage(1_000L, Protocol.APCO25, 0, true, "delivered"));
            assertEquals("delivered", session.poll(2, TimeUnit.SECONDS).text());
        }
    }

    @Test
    void failedListenerBindPublishesAnUnboundGenerationWithoutAnOldCatalog()
    {
        FakeMessageSource first = new FakeMessageSource(classifier("First", "First Type"));
        FakeMessageSource failing = new FakeMessageSource(classifier("Second", "Second Type"));
        AtomicReference<FakeMessageSource> selected = new AtomicReference<>(first);

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> selected.get());
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            long generation = session.generation();
            failing.setThrowOnAdd(true);
            selected.set(failing);
            session.refresh();
            await(() -> session.generation() > generation && !session.isBound());
            DecodeMessageViewService.SourceState state = session.sourceState();
            assertFalse(state.bound());
            assertNull(state.filterCatalog());
            assertEquals(0, first.listenerCount());
            selected.set(null);
            session.refresh();
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
    void saturationAndBlockedClassificationNeverRunOrWaitOnTheDecoderCallback() throws Exception
    {
        CountDownLatch classificationEntered = new CountDownLatch(1);
        CountDownLatch releaseClassification = new CountDownLatch(1);
        AtomicReference<Thread> classificationThread = new AtomicReference<>();
        MessageFilterCatalog.Classifier classifier = blockingClassifier(classificationEntered,
            releaseClassification, classificationThread);
        FakeMessageSource source = new FakeMessageSource(classifier);
        Thread decoderThread = Thread.currentThread();

        try(DecodeMessageViewService service = new DecodeMessageViewService(scope -> source);
            DecodeMessageViewService.Session session = service.openSession(scope()))
        {
            await(session::isBound);
            source.receive(new TestMessage(1_000L, Protocol.APCO25, 0, true, "blocked classification"));
            assertTrue(classificationEntered.await(2, TimeUnit.SECONDS));
            assertFalse(decoderThread == classificationThread.get());
            long started = System.nanoTime();

            for(int index = 0; index < DecodeMessageViewService.INGRESS_QUEUE_SIZE + 16; index++)
            {
                source.receive(new TestMessage(index + 2_000L, Protocol.APCO25, 0, true, "queued"));
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 250, "bounded decoder callback offers took " + elapsedMs + " ms");
            assertTrue(service.getDroppedObservationCount(scope()) > 0);
            releaseClassification.countDown();
            DecodeMessageViewService.MessageView view = session.poll(2, TimeUnit.SECONDS);
            assertEquals("Observed Type", view.filterLabel());
        }
        finally
        {
            releaseClassification.countDown();
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
            assertFalse(view.filterKey().isBlank());
            assertEquals("Other/Unlisted", view.filterLabel());
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
            assertEquals(1, source.classifierBuilds());
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
        assertTrue(DecodeMessageViewService.Scope.conventional().aggregate());
        assertThrows(IllegalArgumentException.class,
            () -> new DecodeMessageViewService.Scope(CONFIGURATION_ID, FREQUENCY,
                DecodeMessageViewService.Mode.CONVENTIONAL));
    }

    @Test
    void aggregateSharesExactSourceTapsAndProjectsMixedProtocolsOnlyOnce() throws InterruptedException
    {
        FakeMessageSource first = new FakeMessageSource(classifier("DMR Messages", "Known"));
        FakeMessageSource second = new FakeMessageSource(classifier("NXDN Messages", "Known"));
        AtomicInteger projections = new AtomicInteger();

        try(DecodeMessageViewService service = new DecodeMessageViewService(selected ->
            selected.equals(scope()) ? first : second, DecodeMessageViewServiceTest::bothSources);
            DecodeMessageViewService.Session aggregate = service.openSession(DecodeMessageViewService.Scope.conventional());
            DecodeMessageViewService.Session otherViewer = service.openSession(DecodeMessageViewService.Scope.conventional());
            DecodeMessageViewService.Session exact = service.openSession(scope()))
        {
            await(() -> aggregate.isBound() && aggregate.filterCatalog().groups().size() == 2);
            assertTrue(aggregate.sourceState().aggregate());
            assertEquals(1, first.listenerCount());
            assertEquals(1, second.listenerCount());
            assertEquals(1, first.classifierBuilds());

            first.receive(countedMessage(1_000, Protocol.DMR, "DMR", projections));
            DecodeMessageViewService.MessageView firstView = aggregate.poll(2, TimeUnit.SECONDS);
            assertEquals(CONFIGURATION_ID, firstView.configurationId());
            assertEquals("First channel", firstView.channelName());
            assertEquals(FREQUENCY, firstView.frequencyHz());
            assertEquals(aggregate.generation(), firstView.sourceGeneration());
            assertEquals(firstView, otherViewer.poll(2, TimeUnit.SECONDS));
            assertEquals(firstView.messageId(), exact.poll(2, TimeUnit.SECONDS).messageId());

            second.receive(countedMessage(1_000, Protocol.NXDN, "NXDN", projections));
            DecodeMessageViewService.MessageView secondView = aggregate.poll(2, TimeUnit.SECONDS);
            assertEquals(SECOND_CONFIGURATION_ID, secondView.configurationId());
            assertEquals("Second channel", secondView.channelName());
            assertNotEquals(firstView.messageId(), secondView.messageId());
            assertNotEquals(firstView.filterKey(), secondView.filterKey(),
                "the two positional message/0/0 choices must remain separate");
            assertTrue(hasFilterKey(aggregate.filterCatalog().groups(), firstView.filterKey()));
            assertTrue(hasFilterKey(aggregate.filterCatalog().groups(), secondView.filterKey()));
            assertEquals(2, projections.get(), "projection is per source observation, not per viewer");
            assertNull(exact.poll(0, TimeUnit.MILLISECONDS));

            TestMessage sharedObject = new TestMessage(5_000, Protocol.DMR, 1, true, "same decoder object");
            first.receive(sharedObject);
            DecodeMessageViewService.MessageView sameObjectFirst = aggregate.poll(2, TimeUnit.SECONDS);
            second.receive(sharedObject);
            DecodeMessageViewService.MessageView sameObjectSecond = aggregate.poll(2, TimeUnit.SECONDS);
            assertNotEquals(sameObjectFirst.messageId(), sameObjectSecond.messageId(),
                "even one message reference observed on two channels needs two originating row identities");
        }
    }

    @Test
    void aggregateCallbacksDoNotWaitForContendedSourceLifecycleLock() throws Exception
    {
        CountDownLatch listenerAdded = new CountDownLatch(1);
        CountDownLatch releaseBind = new CountDownLatch(1);
        FakeMessageSource source = new FakeMessageSource()
        {
            @Override
            public void addListener(Listener<IMessage> listener)
            {
                super.addListener(listener);
                listenerAdded.countDown();

                try
                {
                    releaseBind.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }
            }
        };

        try(DecodeMessageViewService service = new DecodeMessageViewService(selected -> source,
            () -> List.of(bothSources().getFirst()));
            DecodeMessageViewService.Session aggregate = service.openSession(DecodeMessageViewService.Scope.conventional()))
        {
            assertTrue(listenerAdded.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();

            for(int index = 0; index < DecodeMessageViewService.INGRESS_QUEUE_SIZE + 16; index++)
            {
                source.receive(new TestMessage(index, Protocol.DMR, 1, true, "bounded offer"));
            }

            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 250,
                "the worker holds the producer's lifecycle lock throughout these callback offers");
            assertTrue(service.getDroppedObservationCount(scope()) > 0);
            releaseBind.countDown();
            await(aggregate::isBound);
        }
        finally
        {
            releaseBind.countDown();
        }
    }

    @Test
    void aggregateMembershipRemovesSourcesWithoutTakingAnExactViewerDownAndRebindsWithoutReplay()
        throws InterruptedException
    {
        FakeMessageSource first = new FakeMessageSource();
        FakeMessageSource second = new FakeMessageSource();
        FakeMessageSource replacement = new FakeMessageSource();
        AtomicReference<FakeMessageSource> selectedFirst = new AtomicReference<>(first);
        AtomicReference<List<ConventionalLiveSources.Source>> members = new AtomicReference<>(bothSources());

        try(DecodeMessageViewService service = new DecodeMessageViewService(selected ->
            selected.equals(scope()) ? selectedFirst.get() : second, members::get);
            DecodeMessageViewService.Session aggregate = service.openSession(DecodeMessageViewService.Scope.conventional());
            DecodeMessageViewService.Session exact = service.openSession(scope()))
        {
            await(() -> aggregate.isBound() && aggregate.filterCatalog().groups().size() == 2);
            first.receive(new TestMessage(1_000, Protocol.DMR, 1, true, "first binding"));
            String oldId = aggregate.poll(2, TimeUnit.SECONDS).messageId();
            assertNotNull(exact.poll(2, TimeUnit.SECONDS));
            members.set(List.of(bothSources().get(1)));
            aggregate.refresh();
            await(() -> aggregate.filterCatalog().groups().size() == 1);
            assertEquals(1, first.listenerCount(), "selected-channel demand must keep its shared source attached");
            first.receive(new TestMessage(2_000, Protocol.DMR, 1, true, "exact only"));
            assertEquals("exact only", exact.poll(2, TimeUnit.SECONDS).text());
            assertNull(aggregate.poll(100, TimeUnit.MILLISECONDS));
            exact.close();
            await(() -> first.listenerCount() == 0);

            selectedFirst.set(replacement);
            members.set(bothSources());
            aggregate.refresh();
            await(() -> replacement.listenerCount() == 1 && aggregate.filterCatalog().groups().size() == 2);
            assertNull(aggregate.poll(0, TimeUnit.MILLISECONDS));
            replacement.receive(new TestMessage(1_000, Protocol.DMR, 1, true, "replacement"));
            DecodeMessageViewService.MessageView current = aggregate.poll(2, TimeUnit.SECONDS);
            assertEquals("replacement", current.text());
            assertEquals(aggregate.generation(), current.sourceGeneration());
            assertNotEquals(oldId, current.messageId());

            members.set(List.of());
            aggregate.refresh();
            await(() -> !aggregate.isBound() && replacement.listenerCount() == 0 && second.listenerCount() == 0);
            assertNull(aggregate.filterCatalog());
        }
    }

    @Test
    void aggregateLateViewerStartsAtItsOwnLiveEdgeWhileProjectionIsBlocked() throws Exception
    {
        FakeMessageSource source = new FakeMessageSource();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try(DecodeMessageViewService service = new DecodeMessageViewService(selected -> source,
            () -> List.of(bothSources().getFirst()));
            DecodeMessageViewService.Session existing = service.openSession(DecodeMessageViewService.Scope.conventional()))
        {
            await(existing::isBound);
            source.receive(blockedMessage(entered, release));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            source.receive(new TestMessage(2_000, Protocol.DMR, 1, true, "queued before open"));

            try(DecodeMessageViewService.Session late = service.openSession(DecodeMessageViewService.Scope.conventional()))
            {
                release.countDown();
                assertEquals("blocked", existing.poll(2, TimeUnit.SECONDS).text());
                assertEquals("queued before open", existing.poll(2, TimeUnit.SECONDS).text());
                assertNull(late.poll(100, TimeUnit.MILLISECONDS));
                source.receive(new TestMessage(3_000, Protocol.DMR, 1, true, "after open"));
                assertEquals("after open", existing.poll(2, TimeUnit.SECONDS).text());
                assertEquals("after open", late.poll(2, TimeUnit.SECONDS).text());
            }
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    void aggregateLateViewerDoesNotReportIngressDropsFromBeforeItOpened() throws Exception
    {
        FakeMessageSource source = new FakeMessageSource();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try(DecodeMessageViewService service = new DecodeMessageViewService(selected -> source,
            () -> List.of(bothSources().getFirst()));
            DecodeMessageViewService.Session existing = service.openSession(DecodeMessageViewService.Scope.conventional()))
        {
            await(existing::isBound);
            source.receive(blockedMessage(entered, release));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            for(int index = 0; index < DecodeMessageViewService.INGRESS_QUEUE_SIZE + 16; index++)
            {
                source.receive(new TestMessage(index + 2_000, Protocol.DMR, 1, true, "before late viewer"));
            }

            assertTrue(service.getDroppedObservationCount(scope()) > 0);

            try(DecodeMessageViewService.Session late = service.openSession(DecodeMessageViewService.Scope.conventional()))
            {
                release.countDown();
                await(() -> service.getPendingObservationCount(scope()) == 0 && existing.droppedCount() > 0);
                assertEquals(0, late.droppedCount(), "pre-viewer loss must not produce a live-gap warning");
                assertNull(late.poll(0, TimeUnit.MILLISECONDS));
                source.receive(new TestMessage(5_000, Protocol.DMR, 1, true, "after late viewer"));
                assertEquals("after late viewer", late.poll(2, TimeUnit.SECONDS).text());
                assertEquals(0, late.droppedCount());
            }
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    void aggregatePollRejectsQueuedSourceRemovedWithoutAnExplicitRefresh() throws InterruptedException
    {
        FakeMessageSource source = new FakeMessageSource();
        AtomicReference<List<ConventionalLiveSources.Source>> members =
            new AtomicReference<>(List.of(bothSources().getFirst()));

        try(DecodeMessageViewService service = new DecodeMessageViewService(selected -> source, members::get);
            DecodeMessageViewService.Session aggregate = service.openSession(DecodeMessageViewService.Scope.conventional()))
        {
            await(aggregate::isBound);
            source.receive(new TestMessage(500, Protocol.DMR, 1, true, "polled before promotion"));
            DecodeMessageViewService.MessageView polled = aggregate.poll(2, TimeUnit.SECONDS);
            assertTrue(aggregate.isCurrent(polled));
            source.receive(new TestMessage(1_000, Protocol.DMR, 1, true, "queued before promotion"));
            await(() -> aggregate.queuedCount() == 1);
            members.set(List.of());
            assertFalse(aggregate.isCurrent(polled),
                "a previously polled row must also fail final admission after authoritative membership changes");
            assertNull(aggregate.poll(0, TimeUnit.MILLISECONDS),
                "a stopped or newly trunked source must disappear before the periodic chain refresh");
            await(() -> !aggregate.isBound() && source.listenerCount() == 0);
        }
    }

    @Test
    void actualChainOriginInvalidationRejectsPolledAndInFlightAggregateRowsAndRebindsEqualValuedOrigins()
        throws Exception
    {
        Channel channel = new Channel("Conventional DMR");
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setDecodeConfiguration(new DecodeConfigDMR());
        ProcessingChain chain = new ProcessingChain(channel, new AliasModel());
        TestChainSource source = new TestChainSource();
        chain.setSource(source);
        DecodeMessageViewService.ChainMessageSource original = new DecodeMessageViewService.ChainMessageSource(chain);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try(DecodeMessageViewService service = new DecodeMessageViewService(selected ->
            new DecodeMessageViewService.ChainMessageSource(chain), () -> List.of(bothSources().getFirst()));
            DecodeMessageViewService.Session aggregate = service.openSession(DecodeMessageViewService.Scope.conventional());
            DecodeMessageViewService.Session exact = service.openSession(scope()))
        {
            await(() -> aggregate.isBound() && exact.isBound());
            source.receive(new TestMessage(1_000, Protocol.DMR, 1, true, "before origin changed"));
            DecodeMessageViewService.MessageView polled = aggregate.poll(2, TimeUnit.SECONDS);
            assertNotNull(exact.poll(2, TimeUnit.SECONDS));
            assertTrue(aggregate.isCurrent(polled));
            source.receive(blockedMessage(entered, release));
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            // Same channel, chain, sample source, and final frequency: a rotation A→B→A has equal-valued origins.
            chain.removeModule(source);
            chain.setSource(source);
            DecodeMessageViewService.ChainMessageSource replacement =
                new DecodeMessageViewService.ChainMessageSource(chain);
            assertNotSame(original.mOrigin(), replacement.mOrigin());
            assertEquals(original.mOrigin(), replacement.mOrigin(), "the prepared record values intentionally match");
            assertFalse(original.matches(scope()));
            assertTrue(replacement.matches(scope()));
            assertNotEquals(original, replacement, "source equality must compare origin identity, not record values");
            assertFalse(aggregate.isCurrent(polled),
                "final wire admission must reject the old origin while its aggregate generation is still cached");

            long generation = aggregate.generation();
            aggregate.refresh();
            release.countDown();
            await(() -> aggregate.generation() > generation && aggregate.isBound() && exact.isBound());
            assertNull(aggregate.poll(0, TimeUnit.MILLISECONDS));
            assertNull(exact.poll(0, TimeUnit.MILLISECONDS));
            assertFalse(aggregate.isCurrent(polled), "a completed rebind must also reject the old binding sequence");

            source.receive(new TestMessage(2_000, Protocol.DMR, 1, true, "new origin"));
            DecodeMessageViewService.MessageView current = aggregate.poll(2, TimeUnit.SECONDS);
            assertEquals("new origin", current.text());
            assertTrue(aggregate.isCurrent(current));
            assertNotEquals(polled.sourceBindingSequence(), current.sourceBindingSequence());
        }
        finally
        {
            release.countDown();
            chain.dispose();
        }
    }

    @Test
    void aggregateSaturationReportsIngressLossAndPreservesQuietSourceInOneBoundedQueue() throws Exception
    {
        FakeMessageSource hot = new FakeMessageSource();
        FakeMessageSource quiet = new FakeMessageSource();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try(DecodeMessageViewService service = new DecodeMessageViewService(selected ->
            selected.equals(scope()) ? hot : quiet, DecodeMessageViewServiceTest::bothSources);
            DecodeMessageViewService.Session aggregate = service.openSession(DecodeMessageViewService.Scope.conventional()))
        {
            await(() -> aggregate.isBound() && aggregate.filterCatalog().groups().size() == 2);
            quiet.receive(new TestMessage(1_000, Protocol.NXDN, 0, true, "quiet"));
            await(() -> aggregate.queuedCount() == 1);
            hot.receive(blockedMessage(entered, release));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();

            for(int index = 0; index < DecodeMessageViewService.INGRESS_QUEUE_SIZE + 16; index++)
            {
                hot.receive(new TestMessage(index + 2_000, Protocol.DMR, 1, true, "hot"));
            }

            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 250);
            assertTrue(service.getDroppedObservationCount(scope()) > 0);
            release.countDown();
            await(() -> service.getPendingObservationCount(scope()) == 0 && aggregate.droppedCount() > 0);
            int count = 0;
            boolean foundQuiet = false;
            DecodeMessageViewService.MessageView view;

            while((view = aggregate.poll(0, TimeUnit.MILLISECONDS)) != null)
            {
                count++;
                foundQuiet |= "quiet".equals(view.text());
            }

            assertTrue(count <= DecodeMessageViewService.LIVE_QUEUE_SIZE);
            assertTrue(foundQuiet, "the hot source must replace its own stale rows before the quiet source");
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    void aggregateFinalDisconnectImmediatelyStopsOffersAndAbandonsQueuedProjection() throws Exception
    {
        FakeMessageSource source = new FakeMessageSource();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger abandoned = new AtomicInteger();

        try(DecodeMessageViewService service = new DecodeMessageViewService(selected -> source,
            () -> List.of(bothSources().getFirst())))
        {
            DecodeMessageViewService.Session aggregate = service.openSession(DecodeMessageViewService.Scope.conventional());
            await(aggregate::isBound);
            source.receive(blockedMessage(entered, release));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            source.receive(countedMessage(2_000, Protocol.DMR, "abandoned", abandoned));
            int pending = service.getPendingObservationCount(scope());
            aggregate.close();

            for(int index = 0; index < 32; index++)
            {
                source.receive(countedMessage(3_000 + index, Protocol.DMR, "after disconnect", abandoned));
            }

            assertEquals(pending, service.getPendingObservationCount(scope()),
                "no-demand sources must stop accepting observations before the blocked worker returns");
            release.countDown();
            await(() -> source.listenerCount() == 0 && service.getProducerCount() == 0);
            assertEquals(0, abandoned.get());
        }
        finally
        {
            release.countDown();
        }
    }

    @Test
    void aggregateRemovalDuringBlockedProjectionRejectsOldSourceMessage() throws Exception
    {
        FakeMessageSource source = new FakeMessageSource();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<List<ConventionalLiveSources.Source>> members =
            new AtomicReference<>(List.of(bothSources().getFirst()));

        try(DecodeMessageViewService service = new DecodeMessageViewService(selected -> source, members::get);
            DecodeMessageViewService.Session aggregate = service.openSession(DecodeMessageViewService.Scope.conventional()))
        {
            await(aggregate::isBound);
            source.receive(blockedMessage(entered, release));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            members.set(List.of());
            release.countDown();
            await(() -> !aggregate.isBound() && source.listenerCount() == 0);
            assertNull(aggregate.poll(0, TimeUnit.MILLISECONDS));
        }
        finally
        {
            release.countDown();
        }
    }

    private static List<ConventionalLiveSources.Source> bothSources()
    {
        return List.of(new ConventionalLiveSources.Source(CONFIGURATION_ID, FREQUENCY, "First channel"),
            new ConventionalLiveSources.Source(SECOND_CONFIGURATION_ID, SECOND_FREQUENCY, "Second channel"));
    }

    private static TestMessage countedMessage(long timestamp, Protocol protocol, String text, AtomicInteger counter)
    {
        return new TestMessage(timestamp, protocol, 0, true, text)
        {
            @Override
            public String toString()
            {
                counter.incrementAndGet();
                return super.toString();
            }
        };
    }

    private static TestMessage blockedMessage(CountDownLatch entered, CountDownLatch release)
    {
        return new TestMessage(1_000, Protocol.DMR, 1, true, "blocked")
        {
            @Override
            public String toString()
            {
                entered.countDown();

                try
                {
                    release.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return super.toString();
            }
        };
    }

    private static boolean hasFilterKey(List<io.github.dsheirer.filter.FilterCatalog.Node> nodes, String key)
    {
        return nodes.stream().anyMatch(node -> node.key().equals(key) || hasFilterKey(node.children(), key));
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

    private static MessageFilterCatalog.Classifier classifier(String group, String type)
    {
        FilterSet<IMessage> filters = new FilterSet<>("Messages");
        filters.addFilter(new StaticFilter(group, type));
        filters.addFilter(new AllPassFilter<>("All Other Messages"));
        return MessageFilterCatalog.fromFilterSet(filters, new int[0]);
    }

    private static MessageFilterCatalog.Classifier blockingClassifier(CountDownLatch entered,
        CountDownLatch release, AtomicReference<Thread> thread)
    {
        FilterSet<IMessage> filters = new FilterSet<>("Messages");
        filters.addFilter(new BlockingFilter(entered, release, thread));
        filters.addFilter(new AllPassFilter<>("All Other Messages"));
        return MessageFilterCatalog.fromFilterSet(filters, new int[0]);
    }

    private static class FakeMessageSource implements DecodeMessageViewService.MessageSource
    {
        private final CopyOnWriteArrayList<Listener<IMessage>> mListeners = new CopyOnWriteArrayList<>();
        private final AtomicBoolean mMatches = new AtomicBoolean(true);
        private final AtomicInteger mAdds = new AtomicInteger();
        private final AtomicInteger mRemoves = new AtomicInteger();
        private final AtomicInteger mClassifierBuilds = new AtomicInteger();
        private final MessageFilterCatalog.Classifier mClassifier;
        private final AtomicBoolean mThrowClassifier = new AtomicBoolean();
        private final AtomicBoolean mThrowOnAdd = new AtomicBoolean();

        private FakeMessageSource()
        {
            this(MessageFilterCatalog.fallback());
        }

        private FakeMessageSource(MessageFilterCatalog.Classifier classifier)
        {
            mClassifier = classifier;
        }

        @Override
        public void addListener(Listener<IMessage> listener)
        {
            mAdds.incrementAndGet();

            if(mThrowOnAdd.get())
            {
                throw new IllegalStateException("test listener bind failure");
            }

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

        @Override
        public MessageFilterCatalog.Classifier messageFilterClassifier()
        {
            mClassifierBuilds.incrementAndGet();

            if(mThrowClassifier.get())
            {
                throw new IllegalStateException("test catalog failure");
            }

            return mClassifier;
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

        void setThrowClassifier(boolean throwClassifier)
        {
            mThrowClassifier.set(throwClassifier);
        }

        void setThrowOnAdd(boolean throwOnAdd)
        {
            mThrowOnAdd.set(throwOnAdd);
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

        int classifierBuilds()
        {
            return mClassifierBuilds.get();
        }
    }

    private static class TestChainSource extends ComplexSource implements IMessageProvider
    {
        private Listener<IMessage> mMessages;

        @Override public void setMessageListener(Listener<IMessage> listener) { mMessages = listener; }
        @Override public void removeMessageListener() { mMessages = null; }
        @Override public void setListener(Listener<ComplexSamples> listener) {}
        @Override public Listener<SourceEvent> getSourceEventListener() { return event -> {}; }
        @Override public void setSourceEventListener(Listener<SourceEvent> listener) {}
        @Override public void removeSourceEventListener() {}
        @Override public double getSampleRate() { return 25_000; }
        @Override public long getFrequency() { return FREQUENCY; }
        @Override public void reset() {}
        @Override public void start() {}
        @Override public void stop() {}

        void receive(IMessage message)
        {
            mMessages.receive(message);
        }
    }

    private static class StaticFilter extends Filter<IMessage,String>
    {
        private final String mType;

        private StaticFilter(String group, String type)
        {
            super(group);
            mType = type;
            add(new FilterElement<>(type));
        }

        @Override
        public Function<IMessage,String> getKeyExtractor()
        {
            return message -> mType;
        }
    }

    private static class BlockingFilter extends Filter<IMessage,String>
    {
        private static final String TYPE = "Observed Type";
        private final CountDownLatch mEntered;
        private final CountDownLatch mRelease;
        private final AtomicReference<Thread> mThread;

        private BlockingFilter(CountDownLatch entered, CountDownLatch release, AtomicReference<Thread> thread)
        {
            super("Observed Messages");
            mEntered = entered;
            mRelease = release;
            mThread = thread;
            add(new FilterElement<>(TYPE));
        }

        @Override
        public Function<IMessage,String> getKeyExtractor()
        {
            return message -> {
                mThread.compareAndSet(null, Thread.currentThread());
                mEntered.countDown();

                try
                {
                    mRelease.await();
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return TYPE;
            };
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
