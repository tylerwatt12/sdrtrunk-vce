/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.metadata.activity.ChannelActivityEvent;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import io.github.dsheirer.channel.metadata.activity.ChannelActivityTableState;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.module.decode.dmr.DMRChannelMode;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class StatsLiveServiceTest
{
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path mTemporaryDirectory;

    @Test
    void ownsChannelActivityOnlyWhileAWebSubscriberNeedsIt()
    {
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences());
        StatsLiveService service = new StatsLiveService(null, manager);
        assertFalse(manager.getChannelActivityModel().isEnabled());

        service.start();
        assertFalse(manager.getChannelActivityModel().isEnabled());
        assertFalse(service.isRawWorkerAlive());

        try(StatsLiveEventHub.Subscription first = service.subscribeSystems();
            StatsLiveEventHub.Subscription second = service.subscribeSystems())
        {
            assertNotNull(first);
            assertNotNull(second);
            assertEquals(2, service.getSystemsDemandCount());
            assertTrue(manager.getChannelActivityModel().isEnabled());
            assertTrue(service.isRawWorkerAlive());

            first.close();
            assertEquals(1, service.getSystemsDemandCount());
            assertTrue(manager.getChannelActivityModel().isEnabled());

            manager.setChannelActivityEnabled("java-ui", true);
            second.close();
            assertEquals(0, service.getSystemsDemandCount());
            assertTrue(manager.getChannelActivityModel().isEnabled(),
                "the independent Java renderer lease must remain active");
            assertFalse(service.isRawWorkerAlive());
            manager.setChannelActivityEnabled("java-ui", false);
            assertFalse(manager.getChannelActivityModel().isEnabled());
        }

        service.stop();
        assertFalse(manager.getChannelActivityModel().isEnabled());
        service.close();
    }

    @Test
    void projectionWorkerCanStopAndRestartWithoutDuplicatingConsumers() throws Exception
    {
        StatsLiveService service = new StatsLiveService(null, null);
        ChannelActivityEvent event = conventionalActivity("CALL");

        try
        {
            service.start();

            try(StatsLiveEventHub.Subscription ignored = service.subscribeSystems())
            {
                service.receiveChannelActivity(event);
                waitUntil(() -> !tables(service).isEmpty());
            }

            service.stop();
            assertTrue(tables(service).isEmpty());

            service.start();

            try(StatsLiveEventHub.Subscription ignored = service.subscribeSystems())
            {
                service.receiveChannelActivity(event);
                waitUntil(() -> !tables(service).isEmpty());
                assertEquals(1, tables(service).size());
            }
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void activityCommitSaturationPublishesAnAuthoritativeReset() throws Exception
    {
        CountDownLatch lookupEntered = new CountDownLatch(1);
        CountDownLatch releaseLookup = new CountDownLatch(1);
        StatsWebDatabase database = new StatsWebDatabase(new UserPreferences(),
            mTemporaryDirectory.resolve("activity-reset.sqlite"))
        {
            @Override
            List<Map<String,Object>> activityByIds(List<Long> rowIds)
            {
                lookupEntered.countDown();

                try
                {
                    releaseLookup.await(5, TimeUnit.SECONDS);
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                }

                return List.of(Map.of("id", rowIds.getFirst()));
            }
        };
        StatsLiveService service = new StatsLiveService(database, null);
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribeActivity(event -> true))
        {
            assertNotNull(subscription);
            service.activityCommitted(List.of(1L));
            assertTrue(lookupEntered.await(2, TimeUnit.SECONDS));

            for(long id = 2; id <= StatsLiveService.EVENT_QUEUE_CAPACITY + 2L; id++)
            {
                service.activityCommitted(List.of(id));
            }

            StatsLiveEventHub.LiveEvent reset = subscription.poll(2, TimeUnit.SECONDS);
            assertNotNull(reset);
            assertEquals("activity_reset", reset.name());
            assertEquals("source_overflow", ((Map<?,?>)reset.data()).get("reason"));
        }
        finally
        {
            releaseLookup.countDown();
            service.close();
        }
    }

    @Test
    void racingActivitySubscribeAndStopCannotLeakSubscriptions() throws Exception
    {
        StatsLiveService service = new StatsLiveService(null, null);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try
        {
            for(int iteration = 0; iteration < 100; iteration++)
            {
                service.start();
                CountDownLatch start = new CountDownLatch(1);
                AtomicReference<StatsLiveEventHub.Subscription> opened = new AtomicReference<>();
                var subscribe = executor.submit(() ->
                {
                    start.await();
                    opened.set(service.subscribeActivity(event -> true));
                    return null;
                });
                var stop = executor.submit(() ->
                {
                    start.await();
                    service.stop();
                    return null;
                });
                start.countDown();
                subscribe.get(2, TimeUnit.SECONDS);
                stop.get(2, TimeUnit.SECONDS);

                if(opened.get() != null)
                {
                    assertTrue(opened.get().isClosed());
                }

                service.start();

                try(StatsLiveEventHub.Subscription probe = service.subscribeActivity(event -> true))
                {
                    assertNotNull(probe, "a stop race must not consume reusable subscriber capacity");
                }

                service.stop();
            }
        }
        finally
        {
            executor.shutdownNow();
            service.close();
        }
    }

    @Test
    void publishesConventionalStatusChangesWithStableTableIdentity() throws Exception
    {
        StatsLiveService service = new StatsLiveService(null, null);
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribeSystems())
        {
            service.process(conventionalActivity("IDLE"));
            assertActivityStatus(subscription.poll(1, TimeUnit.SECONDS), "IDLE");

            service.process(conventionalActivity("CALL"));
            assertActivityStatus(subscription.poll(1, TimeUnit.SECONDS), "CALL");

            service.process(conventionalActivity("IDLE"));
            assertActivityStatus(subscription.poll(1, TimeUnit.SECONDS), "IDLE");
        }
        finally
        {
            service.close();
        }
    }

    @Test
    void activityProjectionIsWorkerSideAndRawIngressDropsInsteadOfBlocking() throws Exception
    {
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicReference<Thread> projectionThread = new AtomicReference<>();
        StatsLiveService service = new StatsLiveService(null, null, event -> {
            projectionThread.set(Thread.currentThread());
            projectionEntered.countDown();

            try
            {
                releaseProjection.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException interruptedException)
            {
                Thread.currentThread().interrupt();
            }
        });

        service.start();

        try(StatsLiveEventHub.Subscription ignored = service.subscribeSystems())
        {
            Thread producer = Thread.currentThread();
            ChannelActivityEvent event = conventionalActivity("CALL");
            service.receiveChannelActivity(event);
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));

            for(int index = 0; index < 256; index++)
            {
                service.receiveChannelActivity(event);
            }

            assertTrue(service.getDroppedRawEventCount() > 0);
            assertFalse(producer == projectionThread.get());
            assertTrue(projectionThread.get().getName().startsWith("stats live projection"));
        }
        finally
        {
            releaseProjection.countDown();
            service.close();
        }
    }

    @Test
    void droppedRemoveConvergesFromAuthoritativeCoreSnapshot() throws Exception
    {
        AliasModel aliasModel = new AliasModel();
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, aliasModel,
            new UserPreferences());
        CountDownLatch projectionBlocked = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        StatsLiveService service = new StatsLiveService(null, manager, event -> {
            projectionBlocked.countDown();

            try
            {
                releaseProjection.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException interruptedException)
            {
                Thread.currentThread().interrupt();
            }
        });
        Channel parent = trunkedDmrChannel();
        service.start();

        try(StatsLiveEventHub.Subscription subscription = service.subscribeSystems())
        {
            StatsLiveEventHub.LiveEvent initial = subscription.poll(2, TimeUnit.SECONDS);
            assertNotNull(initial);
            assertEquals("activity_resync", initial.name());
            service.receiveChannelActivity(conventionalActivity("CALL"));
            assertTrue(projectionBlocked.await(2, TimeUnit.SECONDS));
            manager.getChannelActivityModel().channelStarted(parent, List.of());
            waitUntil(() -> manager.getChannelActivityModel().getTables().size() == 2);
            ChannelActivityTableState removed = manager.getChannelActivityModel().getTables().stream()
                .filter(table -> table.getOwnerChannel() == parent).findFirst().orElseThrow();

            for(int index = 0; index < 256; index++)
            {
                manager.getChannelActivityModel().channelConfigurationChanged(parent);
            }

            waitUntil(() -> service.getDroppedRawEventCount() > 0);
            manager.getChannelActivityModel().close(removed);
            waitUntil(() -> manager.getChannelActivityModel().getTables().size() == 1);
            releaseProjection.countDown();

            boolean resyncObserved = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

            while(System.nanoTime() < deadline)
            {
                StatsLiveEventHub.LiveEvent event = subscription.poll(100, TimeUnit.MILLISECONDS);

                if(event != null && "activity_resync".equals(event.name()))
                {
                    resyncObserved = true;
                    break;
                }
            }

            assertTrue(resyncObserved);
            assertEquals(1, manager.getChannelActivityModel().getSnapshotSet().tables().size());
            assertTrue(tables(service).stream().noneMatch(
                table -> removed.getTableId().equals(table.get("table_id"))));
        }
        finally
        {
            releaseProjection.countDown();
            service.close();
            manager.shutdown();
        }
    }

    private static Channel trunkedDmrChannel()
    {
        Channel channel = new Channel("Bus", Channel.ChannelType.STANDARD);
        channel.setSystem("Metro");
        channel.setSite("Garage");
        DecodeConfigDMR configuration = new DecodeConfigDMR();
        configuration.setChannelMode(DMRChannelMode.TRUNKED);
        channel.setDecodeConfiguration(configuration);
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(451_000_000L);
        channel.setSourceConfiguration(source);
        return channel;
    }

    private static void waitUntil(BooleanSupplier condition) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);

        while(!condition.getAsBoolean() && System.nanoTime() < deadline)
        {
            Thread.sleep(5);
        }

        assertTrue(condition.getAsBoolean());
    }

    private static ChannelActivityEvent conventionalActivity(String status)
    {
        ChannelActivitySnapshot.Row row = new ChannelActivitySnapshot.Row("channel-17:155730000:0",
            "Dispatch", "configuration-17", status, List.of("CONVENTIONAL"), null, 155_730_000L, null,
            null, null, 0L, 0L, 0L, 0L, 0L, 0L, null, null, null, null, null, null, null, null,
            "NBFM", null);
        ChannelActivitySnapshot snapshot = new ChannelActivitySnapshot("conventional", "Conventional",
            "Conventional", null, null, false, false, List.of(row));
        return new ChannelActivityEvent(ChannelActivityEvent.Operation.UPSERT, snapshot);
    }

    @SuppressWarnings("unchecked")
    private static void assertActivityStatus(StatsLiveEventHub.LiveEvent event, String expectedStatus)
    {
        assertNotNull(event);
        assertEquals("activity_table", event.name());
        Map<String,Object> update = (Map<String,Object>)event.data();
        assertEquals("upsert", update.get("operation"));
        assertEquals("conventional", update.get("table_id"));
        Map<String,Object> table = (Map<String,Object>)update.get("table");
        assertEquals("conventional", table.get("table_id"));
        List<Map<String,Object>> rows = (List<Map<String,Object>>)table.get("rows");
        assertEquals("channel-17:155730000:0", rows.getFirst().get("key"));
        assertEquals(expectedStatus, rows.getFirst().get("status"));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> tables(StatsLiveService service)
    {
        return (List<Map<String,Object>>)service.snapshot().get("tables");
    }

}
