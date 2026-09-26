/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.filter.FilterCatalog;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.p25.P25AffiliationEvent;
import io.github.dsheirer.module.decode.p25.P25SignalingEvent;
import io.github.dsheirer.module.decode.p25.P25SignalingSemantics;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.protocol.Protocol;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class DecodeEventViewServiceTest
{
    private static final String CONFIGURATION_ID = "00000000-0000-0000-0000-000000000001";
    private static final long FREQUENCY = 851_012_500L;

    @Test
    void filterCatalogIsCompleteStableAndGloballyUniqueBeforeAnyEventsArrive()
    {
        FilterCatalog catalog = DecodeEventViewService.filterCatalog();
        FilterCatalog secondRead = DecodeEventViewService.filterCatalog();
        List<String> expectedGroups = List.of("Voice Calls", "Voice Calls - Encrypted", "Data Calls",
            "Commands", "Registrations", "Other");
        List<FilterCatalog.Node> leaves = catalog.groups().stream().flatMap(group -> group.children().stream())
            .toList();
        List<String> allKeys = catalog.groups().stream()
            .flatMap(group -> java.util.stream.Stream.concat(java.util.stream.Stream.of(group.key()),
                group.children().stream().map(FilterCatalog.Node::key)))
            .toList();

        assertEquals(expectedGroups, catalog.groups().stream().map(FilterCatalog.Node::label).toList());
        assertEquals(catalog, secondRead);
        assertEquals(Set.of(), new HashSet<>(catalog.timeslots()));
        assertEquals(allKeys.size(), new HashSet<>(allKeys).size());
        assertEquals(Arrays.stream(DecodeEventType.values()).map(Enum::name).collect(java.util.stream.Collectors.toSet()),
            leaves.stream().map(FilterCatalog.Node::key).collect(java.util.stream.Collectors.toSet()));
        assertEquals(DecodeEventType.values().length, leaves.size());
        assertTrue(leaves.stream().allMatch(leaf -> leaf.children().isEmpty()));
        assertTrue(leaves.stream().anyMatch(leaf -> leaf.key().equals("COMMAND") &&
            leaf.label().equals(DecodeEventType.COMMAND.getLabel())));
        assertTrue(catalog.groups().stream().allMatch(group -> group.key().startsWith("event-group/")));
    }

    @Test
    void keepsStableIdentityWhileProjectingEventUpdates()
    {
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP_ENCRYPTED, 1_000L)
            .duration(250L)
            .channel(new StandardChannel(FREQUENCY))
            .details("  " + "x".repeat(600) + "  ")
            .protocol(Protocol.APCO25_PHASE2)
            .timeslot(1)
            .build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            DecodeEventViewService.EventView initial = service.view(CONFIGURATION_ID, event);
            event.update(1_500L);
            DecodeEventViewService.EventView updated = service.view(CONFIGURATION_ID, event);

            assertEquals(initial.eventId(), updated.eventId());
            assertEquals(250L, initial.durationMs());
            assertEquals(500L, updated.durationMs());
            assertEquals("ENCRYPTED_VOICE", updated.category());
            assertEquals(FREQUENCY, updated.frequencyHz());
            assertEquals(1, updated.timeslot());
            assertEquals("APCO25_PHASE2", updated.protocol());
            assertEquals(512, updated.details().length());
            assertTrue(updated.details().endsWith("…"));
        }
    }

    @Test
    void scopeMatchesTheConfiguredReceiverAndOptionalFrequency()
    {
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L)
            .channel(new StandardChannel(FREQUENCY))
            .timeslot(1)
            .build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            DecodeEventViewService.EventView view = service.view(CONFIGURATION_ID, event);

            assertTrue(new DecodeEventViewService.Scope(CONFIGURATION_ID, null, null).matches(view));
            assertTrue(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY, null).matches(view));
            assertTrue(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY, 1).matches(view));
            assertFalse(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY, 2).matches(view));
            assertFalse(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY + 1, null).matches(view));
            assertFalse(new DecodeEventViewService.Scope("other", null, null).matches(view));
        }
    }

    @Test
    void usesTheProcessingSourceFrequencyWhenTheEventHasNoChannelDescriptor()
    {
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 1_000L).build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            DecodeEventViewService.EventView view = service.view(CONFIGURATION_ID, event, FREQUENCY);
            assertEquals(FREQUENCY, view.frequencyHz());
            assertTrue(new DecodeEventViewService.Scope(CONFIGURATION_ID, FREQUENCY, null).matches(view));
        }
    }

    @Test
    void liveEdgeStampIsInternalAndNotSerializedToTheBrowser() throws Exception
    {
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL, 1_000L).build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            String json = new ObjectMapper().writeValueAsString(service.view(CONFIGURATION_ID, event));
            assertFalse(json.contains("observationEpoch"));
        }
    }

    @Test
    void blockedProjectionNeverBlocksOrProjectsOnTheDecoderCallback() throws Exception
    {
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicReference<Thread> projectionThread = new AtomicReference<>();
        DecodeEvent blocked = new DecodeEvent(DecodeEventType.CALL_GROUP, 1_000L)
        {
            @Override
            public DecodeEventType getEventType()
            {
                projectionThread.compareAndSet(null, Thread.currentThread());
                projectionEntered.countDown();

                try
                {
                    releaseProjection.await(3, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return super.getEventType();
            }
        };
        DecodeEvent ordinary = DecodeEvent.builder(DecodeEventType.CALL_GROUP, 2_000L).build();
        Channel channel = new Channel("test", Channel.ChannelType.STANDARD);
        Thread decoderThread = Thread.currentThread();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            service.addListener(event -> { });
            service.receive(channel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();

            for(int x = 0; x < DecodeEventViewService.UPDATE_QUEUE_SIZE + 16; x++)
            {
                service.receive(channel, ordinary);
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 250, "bounded offers took " + elapsedMs + " ms");
            assertTrue(service.getDroppedObservationCount() > 0);
            assertFalse(decoderThread == projectionThread.get());
            releaseProjection.countDown();
        }
        finally
        {
            releaseProjection.countDown();
        }
    }

    @Test
    void zeroConsumersRejectIngressAndEachDemandGenerationStartsEmpty()
    {
        AtomicInteger callbacks = new AtomicInteger();
        List<Long> timestamps = new CopyOnWriteArrayList<>();
        io.github.dsheirer.sample.Listener<DecodeEventViewService.EventView> listener =
            event -> {
                timestamps.add(event.timeStartMs());
                callbacks.incrementAndGet();
            };
        Channel channel = new Channel("inactive", Channel.ChannelType.STANDARD);
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL, 1_000L).build();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            service.getDecodeEventListener().accept(channel, event);
            assertEquals(0, service.getPendingObservationCount());
            service.addListener(listener);
            service.getDecodeEventListener().accept(channel, event);
            await(() -> callbacks.get() == 1);
            service.removeListener(listener);
            service.getDecodeEventListener().accept(channel,
                DecodeEvent.builder(DecodeEventType.CALL, 2_000L).build());
            assertEquals(0, service.getPendingObservationCount());
            assertEquals(1, callbacks.get());

            service.addListener(listener);
            assertEquals(List.of(1_000L), timestamps);
            DecodeEvent replacement = DecodeEvent.builder(DecodeEventType.CALL, 3_000L).build();
            service.getDecodeEventListener().accept(channel, replacement);
            await(() -> callbacks.get() == 2);
            assertEquals(List.of(1_000L, 3_000L), timestamps);
        }
    }

    @Test
    void blockedProjectionCloseRemainsWorkerOwnedAndCannotPublishAfterClose() throws Exception
    {
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicInteger callbacks = new AtomicInteger();
        DecodeEvent blocked = new DecodeEvent(DecodeEventType.CALL_GROUP, 1_000L)
        {
            @Override
            public DecodeEventType getEventType()
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

                return super.getEventType();
            }
        };
        DecodeEventViewService service = new DecodeEventViewService(null, null, 25, TimeUnit.MILLISECONDS);
        service.addListener(event -> callbacks.incrementAndGet());
        service.getDecodeEventListener().accept(new Channel("test", Channel.ChannelType.STANDARD), blocked);
        assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));

        service.close();
        assertFalse(service.isWorkerTerminated());
        assertEquals(0, callbacks.get());
        assertEquals(0, service.getPendingObservationCount(),
            "the worker owns and has already removed the blocked observation");

        releaseProjection.countDown();
        await(service::isWorkerTerminated);
        service.getDecodeEventListener().accept(new Channel("closed", Channel.ChannelType.STANDARD),
            DecodeEvent.builder(DecodeEventType.CALL, 2_000L).build());
        assertEquals(0, callbacks.get());
    }

    @Test
    void liveCallbackReceivesEachProjectedItemWithoutAReplayCache() throws Exception
    {
        Channel channel = new Channel("ordered", Channel.ChannelType.STANDARD);
        DecodeEvent event = DecodeEvent.builder(DecodeEventType.CALL, 4_000L).build();
        AtomicInteger callbacks = new AtomicInteger();
        CountDownLatch callback = new CountDownLatch(1);

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            service.addListener(view -> {
                if(view.timeStartMs() == 4_000L)
                {
                    callbacks.incrementAndGet();
                }

                callback.countDown();
            });
            service.getDecodeEventListener().accept(channel, event);
            assertTrue(callback.await(2, TimeUnit.SECONDS));
            assertEquals(1, callbacks.get());
        }
    }

    @Test
    void networkProjectionHasALiveEdgeAndPublishesOnlyAuthoritativeP25Semantics() throws Exception
    {
        Channel channel = networkChannel();
        List<DecodeEventViewService.NetworkEventView> views = new CopyOnWriteArrayList<>();
        CountDownLatch callbacks = new CountDownLatch(4);
        io.github.dsheirer.sample.Listener<DecodeEventViewService.NetworkEventView> listener = event -> {
            views.add(event);
            callbacks.countDown();
        };

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            service.getDecodeEventListener().accept(channel, affiliation(900L,
                P25AffiliationEvent.Outcome.ACCEPTED, true, DecodeEventType.RESPONSE));
            assertEquals(0, service.getPendingObservationCount());

            service.addNetworkListener(listener);
            long liveEdge = service.advanceLiveEdge();
            service.getDecodeEventListener().accept(channel, affiliation(1_000L,
                P25AffiliationEvent.Outcome.REQUESTED, true, DecodeEventType.REQUEST));
            service.getDecodeEventListener().accept(channel, affiliation(1_100L,
                P25AffiliationEvent.Outcome.REJECTED, true, DecodeEventType.RESPONSE));
            service.getDecodeEventListener().accept(channel, affiliation(1_200L,
                P25AffiliationEvent.Outcome.ACCEPTED, true, DecodeEventType.RESPONSE));
            service.getDecodeEventListener().accept(channel, affiliation(1_300L,
                P25AffiliationEvent.Outcome.ACCEPTED, false, DecodeEventType.REGISTER));
            service.getDecodeEventListener().accept(channel, affiliation(1_400L,
                P25AffiliationEvent.Outcome.CLEARED, false, DecodeEventType.DEREGISTER));

            assertTrue(callbacks.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("signaling_observed", "affiliation_observed", "presence_observed",
                    "presence_cleared"),
                views.stream().map(DecodeEventViewService.NetworkEventView::kind).toList());
            assertEquals("denial", views.getFirst().action());
            assertEquals("response", views.getFirst().eventType());
            assertTrue(views.stream().allMatch(view -> view.observationEpoch() >= liveEdge));
            assertTrue(views.stream().allMatch(view -> CONFIGURATION_ID.equals(view.configurationId())));
            assertEquals(FREQUENCY, views.getFirst().frequencyHz());
            assertEquals(0xABCDE, views.getFirst().site().wacn());
            assertEquals(0x123, views.getFirst().site().systemId());
            assertEquals(4, views.getFirst().site().rfss());
            assertEquals(9, views.getFirst().site().site());
            assertEquals(1_201, views.getFirst().radio().nativeId());
            assertEquals(101, views.getFirst().group().nativeId());
            assertNotNull(views.getFirst().eventId());

            service.removeNetworkListener(listener);
            service.getDecodeEventListener().accept(channel, affiliation(1_500L,
                P25AffiliationEvent.Outcome.ACCEPTED, true, DecodeEventType.RESPONSE));
            assertEquals(0, service.getPendingObservationCount());
            assertEquals(4, views.size());
        }
    }

    @Test
    void networkProjectionPublishesOnlyTheRequestedTypedP25Signals() throws Exception
    {
        Channel channel = networkChannel();
        List<DecodeEventViewService.NetworkEventView> views = new CopyOnWriteArrayList<>();
        CountDownLatch callbacks = new CountDownLatch(5);

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            service.addNetworkListener(view -> {
                views.add(view);
                callbacks.countDown();
            });
            service.advanceLiveEdge();
            service.getDecodeEventListener().accept(channel,
                p25Signal(DecodeEventType.DENIAL, 2_000L, "denied " + "x".repeat(600)));
            service.getDecodeEventListener().accept(channel,
                p25Signal(DecodeEventType.RADIO_CHECK, 2_100L, "radio check"));
            service.getDecodeEventListener().accept(channel,
                p25Signal(DecodeEventType.EMERGENCY, 2_200L, "emergency alarm"));
            service.getDecodeEventListener().accept(channel,
                p25Signal(DecodeEventType.PAGE, 2_300L, "call alert"));
            service.getDecodeEventListener().accept(channel,
                p25BusySignal(2_400L, "target busy"));

            // Generic request/response/command observations do not become signaling events, even when P25.
            service.getDecodeEventListener().accept(channel,
                p25Signal(DecodeEventType.REQUEST, 2_500L, "group affiliation"));
            service.getDecodeEventListener().accept(channel,
                p25Signal(DecodeEventType.RESPONSE, 2_600L, "deny-looking display text"));
            service.getDecodeEventListener().accept(channel,
                p25Signal(DecodeEventType.COMMAND, 2_700L, "radio check-looking display text"));

            assertTrue(callbacks.await(2, TimeUnit.SECONDS));
            assertEquals(List.of("denial", "check", "emergency", "page", "busy"),
                views.stream().map(DecodeEventViewService.NetworkEventView::action).toList());
            assertTrue(views.stream().allMatch(view -> "signaling_observed".equals(view.kind())));
            assertEquals(List.of("denial", "radio_check", "emergency", "page", "response"),
                views.stream().map(DecodeEventViewService.NetworkEventView::eventType).toList());
            assertEquals(512, views.getFirst().detail().length());
            assertTrue(views.getFirst().detail().endsWith("…"));
            assertEquals(1_201, views.getFirst().radio().nativeId());
            assertEquals(101, views.getFirst().group().nativeId());
            assertEquals(5, views.size());
        }
    }

    @Test
    void rejectedAffiliationRetainsDenialButOmitsInvalidZeroIdentities() throws Exception
    {
        Channel channel = networkChannel();
        AtomicReference<DecodeEventViewService.NetworkEventView> observed = new AtomicReference<>();
        CountDownLatch callback = new CountDownLatch(1);
        P25AffiliationEvent rejected = new P25AffiliationEvent(DecodeEventType.RESPONSE, 2_900L,
            P25AffiliationEvent.Outcome.REJECTED, APCO25RadioIdentifier.createFrom(0),
            APCO25Talkgroup.create(0));
        rejected.setChannelDescriptor(new StandardChannel(FREQUENCY));

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            service.addNetworkListener(view -> {
                observed.set(view);
                callback.countDown();
            });
            service.advanceLiveEdge();
            service.getDecodeEventListener().accept(channel, rejected);

            assertTrue(callback.await(2, TimeUnit.SECONDS));
            assertNotNull(observed.get());
            assertEquals("signaling_observed", observed.get().kind());
            assertEquals("denial", observed.get().action());
            assertNull(observed.get().radio());
            assertNull(observed.get().group());
        }
    }

    @Test
    void signalingProjectionAndOverflowRemainOffTheProducerThread() throws Exception
    {
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicReference<Thread> projectionThread = new AtomicReference<>();
        DecodeEvent blocked = new DecodeEvent(DecodeEventType.EMERGENCY, 1_000L)
        {
            @Override
            public IdentifierCollection getIdentifierCollection()
            {
                projectionThread.compareAndSet(null, Thread.currentThread());
                projectionEntered.countDown();

                try
                {
                    releaseProjection.await(3, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return super.getIdentifierCollection();
            }
        };
        blocked.setProtocol(Protocol.APCO25);
        DecodeEvent ordinary = p25Signal(DecodeEventType.PAGE, 2_000L, "page");
        Channel channel = networkChannel();
        Thread producerThread = Thread.currentThread();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            service.addNetworkListener(event -> { });
            service.getDecodeEventListener().accept(channel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();

            for(int index = 0; index < DecodeEventViewService.UPDATE_QUEUE_SIZE + 16; index++)
            {
                service.getDecodeEventListener().accept(channel, ordinary);
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 250, "bounded offers took " + elapsedMs + " ms");
            assertTrue(service.getDroppedNetworkObservationCount() > 0);
            assertFalse(producerThread == projectionThread.get());
            releaseProjection.countDown();
        }
        finally
        {
            releaseProjection.countDown();
        }
    }

    @Test
    void networkOnlyDemandKeepsTheSharedTapBoundedAndNeverBlocksTheProducer() throws Exception
    {
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        P25AffiliationEvent blocked = new P25AffiliationEvent(DecodeEventType.RESPONSE, 1_000L,
            P25AffiliationEvent.Outcome.ACCEPTED, APCO25RadioIdentifier.createFrom(1_201),
            APCO25Talkgroup.create(101))
        {
            @Override
            public io.github.dsheirer.identifier.Identifier<?> getRadioIdentifier()
            {
                projectionEntered.countDown();

                try
                {
                    releaseProjection.await(3, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return super.getRadioIdentifier();
            }
        };
        Channel channel = networkChannel();

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            service.addNetworkListener(event -> { });
            service.getDecodeEventListener().accept(channel, blocked);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));

            for(int index = 0; index < DecodeEventViewService.UPDATE_QUEUE_SIZE * 2; index++)
            {
                P25AffiliationEvent.Outcome outcome = index % 2 == 0 ?
                    P25AffiliationEvent.Outcome.REQUESTED : P25AffiliationEvent.Outcome.UNRESOLVED;
                DecodeEventType eventType = outcome == P25AffiliationEvent.Outcome.REQUESTED ?
                    DecodeEventType.REQUEST : DecodeEventType.RESPONSE;
                service.getDecodeEventListener().accept(channel,
                    affiliation(1_100L + index, outcome, true, eventType));
            }

            assertEquals(0, service.getPendingObservationCount(),
                "generic requests and unresolved responses must not consume the bounded network queue");
            assertEquals(0, service.getDroppedNetworkObservationCount(),
                "ignored requests and unresolved responses are not lost semantic observations");
            long started = System.nanoTime();

            for(int index = 0; index < DecodeEventViewService.UPDATE_QUEUE_SIZE + 16; index++)
            {
                service.getDecodeEventListener().accept(channel, affiliation(2_000L + index,
                    P25AffiliationEvent.Outcome.ACCEPTED, true, DecodeEventType.RESPONSE));
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue(elapsedMs < 250, "bounded offers took " + elapsedMs + " ms");
            assertTrue(service.getDroppedNetworkObservationCount() > 0);
            releaseProjection.countDown();
        }
        finally
        {
            releaseProjection.countDown();
        }
    }

    @Test
    void networkSubscribersKeepIndependentLiveEdgesWithoutRestartingSharedDemand() throws Exception
    {
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        P25AffiliationEvent preBoundary = new P25AffiliationEvent(DecodeEventType.RESPONSE, 1_000L,
            P25AffiliationEvent.Outcome.ACCEPTED, APCO25RadioIdentifier.createFrom(1_201),
            APCO25Talkgroup.create(101))
        {
            @Override
            public io.github.dsheirer.identifier.Identifier<?> getRadioIdentifier()
            {
                projectionEntered.countDown();

                try
                {
                    releaseProjection.await(3, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return super.getRadioIdentifier();
            }
        };
        preBoundary.setChannelDescriptor(new StandardChannel(FREQUENCY));
        Channel channel = networkChannel();
        AtomicLong firstBoundary = new AtomicLong(Long.MAX_VALUE);
        AtomicLong lateBoundary = new AtomicLong(Long.MAX_VALUE);
        List<Long> firstSubscriber = new CopyOnWriteArrayList<>();
        List<Long> lateSubscriber = new CopyOnWriteArrayList<>();
        io.github.dsheirer.sample.Listener<DecodeEventViewService.NetworkEventView> first = view -> {
            if(view.observationEpoch() >= firstBoundary.get())
            {
                firstSubscriber.add(view.observedAtMs());
            }
        };
        io.github.dsheirer.sample.Listener<DecodeEventViewService.NetworkEventView> late = view -> {
            if(view.observationEpoch() >= lateBoundary.get())
            {
                lateSubscriber.add(view.observedAtMs());
            }
        };

        try(DecodeEventViewService service = new DecodeEventViewService(null, null))
        {
            service.addNetworkListener(first);
            service.advanceLiveEdge(firstBoundary);
            long sourceGeneration = service.getSourceGeneration();
            service.getDecodeEventListener().accept(channel, preBoundary);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));

            service.addNetworkListener(late);
            long lateEdge = service.advanceLiveEdge(lateBoundary);
            assertEquals(sourceGeneration, service.getSourceGeneration(),
                "another subscriber must not restart the shared receiver demand generation");
            releaseProjection.countDown();
            await(() -> firstSubscriber.size() == 1);
            assertEquals(List.of(1_000L), firstSubscriber);
            assertTrue(lateSubscriber.isEmpty(), "a late subscriber must reject the in-flight older observation");

            service.getDecodeEventListener().accept(channel, affiliation(2_000L,
                P25AffiliationEvent.Outcome.ACCEPTED, true, DecodeEventType.RESPONSE));
            await(() -> firstSubscriber.size() == 2 && lateSubscriber.size() == 1);
            assertEquals(List.of(1_000L, 2_000L), firstSubscriber);
            assertEquals(List.of(2_000L), lateSubscriber);
            assertEquals(lateEdge, lateBoundary.get());

            service.removeNetworkListener(first);
            service.removeNetworkListener(late);
        }
        finally
        {
            releaseProjection.countDown();
        }
    }

    private static Channel networkChannel()
    {
        Channel channel = new Channel("Network", Channel.ChannelType.STANDARD);
        channel.setConfigurationId(CONFIGURATION_ID);
        channel.setSystem("Metro");
        channel.setSite("North");
        channel.setP25SiteIdentity(new P25SiteIdentity(0xABCDE, 0x123, 4, 9));
        return channel;
    }

    private static P25AffiliationEvent affiliation(long timestamp, P25AffiliationEvent.Outcome outcome,
                                                    boolean withGroup, DecodeEventType eventType)
    {
        P25AffiliationEvent event = new P25AffiliationEvent(eventType, timestamp, outcome,
            APCO25RadioIdentifier.createFrom(1_201), withGroup ? APCO25Talkgroup.create(101) : null);
        event.setChannelDescriptor(new StandardChannel(FREQUENCY));
        return event;
    }

    private static DecodeEvent p25Signal(DecodeEventType type, long timestamp, String detail)
    {
        return DecodeEvent.builder(type, timestamp)
            .channel(new StandardChannel(FREQUENCY))
            .details(detail)
            .identifiers(new IdentifierCollection(List.of(APCO25RadioIdentifier.createFrom(1_201),
                APCO25Talkgroup.create(101))))
            .protocol(Protocol.APCO25)
            .build();
    }

    private static P25SignalingEvent p25BusySignal(long timestamp, String detail)
    {
        P25SignalingEvent event = new P25SignalingEvent(DecodeEventType.RESPONSE, timestamp,
            P25SignalingSemantics.Action.BUSY);
        event.setChannelDescriptor(new StandardChannel(FREQUENCY));
        event.setDetails(detail);
        event.setIdentifierCollection(new IdentifierCollection(List.of(APCO25RadioIdentifier.createFrom(1_201),
            APCO25Talkgroup.create(101))));
        return event;
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
}
