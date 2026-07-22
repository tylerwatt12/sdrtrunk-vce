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

import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class StatsLiveEventHubTest
{
    @Test
    void boundsSubscribersAndReleasesSlotOnClose()
    {
        StatsLiveEventHub hub = new StatsLiveEventHub(1, 2);
        StatsLiveEventHub.Subscription first = hub.subscribe();

        assertTrue(hub.hasSubscribers());
        assertNull(hub.subscribe());

        first.close();
        assertFalse(hub.hasSubscribers());

        try(StatsLiveEventHub.Subscription replacement = hub.subscribe())
        {
            assertEquals(0, replacement.registrationHighWaterEventId());
        }

        hub.close();
        assertNull(hub.subscribe());
    }

    @Test
    void assignsMonotonicIdsAndAtomicallyReplaysAfterCursor() throws Exception
    {
        StatsLiveEventHub hub = new StatsLiveEventHub(1, 4, 4);
        assertEquals(1, hub.publish("change", 1));
        assertEquals(2, hub.publish("change", 2));

        try(StatsLiveEventHub.Subscription subscription = hub.subscribe(1))
        {
            assertEquals(2, subscription.registrationHighWaterEventId());
            assertEquals(3, hub.publish("change", 3));

            StatsLiveEventHub.LiveEvent replayed = subscription.poll(1, TimeUnit.SECONDS);
            StatsLiveEventHub.LiveEvent live = subscription.poll(1, TimeUnit.SECONDS);
            assertEquals(2, replayed.id());
            assertEquals(2, replayed.data());
            assertEquals(3, live.id());
            assertEquals(3, live.data());
        }

        hub.close();
    }

    @Test
    void expiredReplayWindowRequiresFreshSnapshot() throws Exception
    {
        StatsLiveEventHub hub = new StatsLiveEventHub(1, 4, 2);
        hub.publish("change", 1);
        hub.publish("change", 2);
        hub.publish("change", 3);

        try(StatsLiveEventHub.Subscription subscription = hub.subscribe(0))
        {
            StatsLiveEventHub.LiveEvent event = subscription.poll(1, TimeUnit.SECONDS);
            assertTrue(event.requiresResnapshot());
            assertEquals(3, event.id());
            StatsLiveEventHub.ReplayGap gap = (StatsLiveEventHub.ReplayGap)event.data();
            assertEquals("replay_window_expired", gap.reason());
            assertEquals(0, gap.requestedAfterEventId());
            assertEquals(2, gap.earliestAvailableEventId());
            assertEquals(3, gap.highWaterEventId());
            assertNull(subscription.poll(0, TimeUnit.MILLISECONDS));
        }

        hub.close();
    }

    @Test
    void replayLargerThanSubscriberQueueRequiresFreshSnapshot() throws Exception
    {
        StatsLiveEventHub hub = new StatsLiveEventHub(1, 1, 4);
        hub.publish("change", 1);
        hub.publish("change", 2);

        try(StatsLiveEventHub.Subscription subscription = hub.subscribe(0))
        {
            StatsLiveEventHub.LiveEvent event = subscription.poll(1, TimeUnit.SECONDS);
            assertTrue(event.requiresResnapshot());
            assertEquals("replay_exceeds_subscriber_queue",
                ((StatsLiveEventHub.ReplayGap)event.data()).reason());
        }

        hub.close();
    }

    @Test
    void slowSubscriberGetsExplicitGapThenNewerEvents() throws Exception
    {
        StatsLiveEventHub hub = new StatsLiveEventHub(1, 1, 4);

        try(StatsLiveEventHub.Subscription subscription = hub.subscribe())
        {
            hub.publish("change", 1);
            hub.publish("change", 2);
            hub.publish("change", 3);

            StatsLiveEventHub.LiveEvent gapEvent = subscription.poll(1, TimeUnit.SECONDS);
            assertTrue(gapEvent.requiresResnapshot());
            assertEquals(2, gapEvent.id());
            assertEquals("subscriber_queue_overflow", ((StatsLiveEventHub.ReplayGap)gapEvent.data()).reason());

            StatsLiveEventHub.LiveEvent next = subscription.poll(1, TimeUnit.SECONDS);
            assertEquals(3, next.id());
            assertEquals(3, next.data());
        }

        hub.close();
    }

    @Test
    void authoritativeSnapshotDiscardsAlreadyRepresentedDeltas() throws Exception
    {
        StatsLiveEventHub hub = new StatsLiveEventHub(1, 4, 8);

        try(StatsLiveEventHub.Subscription subscription = hub.subscribe())
        {
            hub.publish("change", 1);
            hub.publish("change", 2);
            subscription.acknowledgeSnapshot(2);
            assertNull(subscription.poll(0, TimeUnit.MILLISECONDS));

            hub.publish("change", 3);
            StatsLiveEventHub.LiveEvent next = subscription.poll(1, TimeUnit.SECONDS);
            assertEquals(3, next.id());
            assertEquals(3, next.data());
        }

        hub.close();
    }

    @Test
    void closeUnblocksPollAndReleasesRetainedEvents() throws Exception
    {
        StatsLiveEventHub hub = new StatsLiveEventHub(1, 2, 2);
        StatsLiveEventHub.Subscription subscription = hub.subscribe();
        FutureTask<StatsLiveEventHub.LiveEvent> poll = new FutureTask<>(
            () -> subscription.poll(30, TimeUnit.SECONDS));
        Thread thread = new Thread(poll, "stats-live-hub-close-test");
        thread.start();

        hub.close();

        assertNull(poll.get(1, TimeUnit.SECONDS));
        assertTrue(subscription.isClosed());
        assertFalse(hub.hasSubscribers());
        assertEquals(-1, hub.publish("ignored", 1));
        thread.join(1000);
    }
}
