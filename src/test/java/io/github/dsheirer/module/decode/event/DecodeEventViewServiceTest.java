/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.module.decode.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.filter.FilterCatalog;
import io.github.dsheirer.module.decode.p25.identifier.channel.StandardChannel;
import io.github.dsheirer.protocol.Protocol;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
