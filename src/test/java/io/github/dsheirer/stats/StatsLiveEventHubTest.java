/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.event.DecodeEvent;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.DecodeEventViewService;
import io.github.dsheirer.sample.Listener;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class StatsLiveEventHubTest
{
    @Test
    void boundsSubscribersAndDropsOldestPendingUpdate() throws Exception
    {
        StatsLiveEventHub hub = new StatsLiveEventHub(1, 1);

        try(StatsLiveEventHub.Subscription subscription = hub.subscribe())
        {
            assertNull(hub.subscribe());
            hub.publish("first", 1);
            hub.publish("second", 2);
            StatsLiveEventHub.LiveEvent event = subscription.poll(1, TimeUnit.SECONDS);
            assertEquals("second", event.name());
            assertEquals(2, event.data());
            assertEquals(1, subscription.droppedCount());
        }

        hub.close();
    }

    @Test
    void filtersBeforeEventsEnterTheBoundedSubscriptionQueue() throws Exception
    {
        StatsLiveEventHub hub = new StatsLiveEventHub(1, 1);

        try(StatsLiveEventHub.Subscription subscription = hub.subscribe(event -> "selected".equals(event.name())))
        {
            hub.publish("selected", 1);
            hub.publish("unrelated", 2);
            StatsLiveEventHub.LiveEvent event = subscription.poll(1, TimeUnit.SECONDS);
            assertEquals("selected", event.name());
            assertEquals(1, event.data());
            assertNull(subscription.poll(10, TimeUnit.MILLISECONDS));
            assertEquals(0, subscription.droppedCount());
        }

        hub.close();
    }

    @Test
    void invokesTheSubscriptionCloseActionExactlyOnce()
    {
        StatsLiveEventHub hub = new StatsLiveEventHub(2, 2);
        AtomicInteger closes = new AtomicInteger();
        StatsLiveEventHub.Subscription subscription = hub.subscribe(event -> true, closes::incrementAndGet);

        subscription.close();
        subscription.close();
        hub.close();

        assertEquals(1, closes.get());
    }

    @Test
    void reportsExactOverflowSeparatelyForEachSubscriber() throws Exception
    {
        StatsLiveEventHub hub = new StatsLiveEventHub(2, 1);

        try(StatsLiveEventHub.Subscription all = hub.subscribe();
            StatsLiveEventHub.Subscription selected = hub.subscribe(event -> "selected".equals(event.name())))
        {
            hub.publish("selected", 1);
            hub.publish("unrelated", 2);
            hub.publish("selected", 3);

            assertEquals(2, all.drainDroppedEvents());
            assertEquals(1, selected.drainDroppedEvents());
            assertEquals(3, hub.droppedEvents());
            assertEquals(0, all.drainDroppedEvents());
            assertEquals(0, selected.drainDroppedEvents());
            assertEquals(3, all.poll(1, TimeUnit.SECONDS).data());
            assertEquals(3, selected.poll(1, TimeUnit.SECONDS).data());
        }

        hub.close();
    }

    @Test
    void detectsWhetherAnyCurrentSubscriberAcceptsAnEvent()
    {
        StatsLiveEventHub hub = new StatsLiveEventHub(2, 2);
        assertFalse(hub.hasMatchingSubscriber("selected", 1));

        StatsLiveEventHub.Subscription subscription =
            hub.subscribe(event -> "selected".equals(event.name()));
        assertFalse(hub.hasMatchingSubscriber("unrelated", 1));
        assertTrue(hub.hasMatchingSubscriber("selected", 1));

        subscription.close();
        assertFalse(hub.hasMatchingSubscriber("selected", 1));
        hub.close();
    }

    @Test
    void decodeEventSubscriberStartsAtReceiverAcceptanceLiveEdge() throws Exception
    {
        StatsLiveEventHub hub = new StatsLiveEventHub(2, 8);
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        CountDownLatch preOpenPublished = new CountDownLatch(1);
        DecodeEvent preOpen = new DecodeEvent(DecodeEventType.CALL_GROUP, 1_000L)
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
        Channel channel = new Channel("live-edge", Channel.ChannelType.STANDARD);

        try(DecodeEventViewService service = new DecodeEventViewService(null, null);
            StatsLiveEventHub.Subscription existing = hub.subscribe())
        {
            assertNotNull(existing);
            Listener<DecodeEventViewService.EventView> relay = view -> {
                hub.publish("decode_event", view);

                if(view.timeStartMs() == 1_000L)
                {
                    preOpenPublished.countDown();
                }
            };
            service.addListener(relay);
            service.getDecodeEventListener().accept(channel, preOpen);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));

            AtomicLong liveEdge = new AtomicLong(Long.MAX_VALUE);

            try(StatsLiveEventHub.Subscription late = hub.subscribe(event ->
                event.data() instanceof DecodeEventViewService.EventView view &&
                    view.observationEpoch() >= liveEdge.get()))
            {
                assertNotNull(late);
                liveEdge.set(service.advanceLiveEdge());
                releaseProjection.countDown();
                assertTrue(preOpenPublished.await(2, TimeUnit.SECONDS));

                StatsLiveEventHub.LiveEvent existingPreOpen = existing.poll(1, TimeUnit.SECONDS);
                assertNotNull(existingPreOpen);
                assertEquals(1_000L,
                    ((DecodeEventViewService.EventView)existingPreOpen.data()).timeStartMs());
                assertNull(late.poll(100, TimeUnit.MILLISECONDS));

                service.getDecodeEventListener().accept(channel,
                    DecodeEvent.builder(DecodeEventType.CALL, 2_000L).build());
                StatsLiveEventHub.LiveEvent existingFuture = existing.poll(2, TimeUnit.SECONDS);
                StatsLiveEventHub.LiveEvent lateFuture = late.poll(2, TimeUnit.SECONDS);
                assertNotNull(existingFuture);
                assertNotNull(lateFuture);
                assertEquals(2_000L,
                    ((DecodeEventViewService.EventView)existingFuture.data()).timeStartMs());
                assertEquals(2_000L,
                    ((DecodeEventViewService.EventView)lateFuture.data()).timeStartMs());
            }
        }
        finally
        {
            releaseProjection.countDown();
            hub.close();
        }
    }
}
