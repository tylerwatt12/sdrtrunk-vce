/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.concurrent.TimeUnit;
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
        }

        hub.close();
    }
}
