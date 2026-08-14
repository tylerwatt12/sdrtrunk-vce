/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
}
