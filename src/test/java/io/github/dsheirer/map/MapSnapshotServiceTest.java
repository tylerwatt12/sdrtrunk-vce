/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.PlottableDecodeEvent;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jdesktop.swingx.mapviewer.GeoPosition;
import org.junit.jupiter.api.Test;

class MapSnapshotServiceTest
{
    @Test
    void retainsOnlyRecentEntitiesAndLocations() throws Exception
    {
        try(MapSnapshotService service = new MapSnapshotService(
            (aliasList, identifier) -> new MapSnapshotService.Display("Unit " + identifier, "police", "#123456"),
            32, 3, 2))
        {
            for(int radio = 1; radio <= 4; radio++)
            {
                for(int position = 1; position <= 3; position++)
                {
                    service.receive(event(radio, 40 + radio, -83 - position, position * 3_000L));
                }
            }

            await(() -> service.snapshot().evictedEntities() == 1 &&
                service.snapshot().entities().size() == 3 &&
                service.snapshot().entities().getFirst().positions().size() == 2);
            MapSnapshotService.Snapshot snapshot = service.snapshot();
            assertEquals(3, snapshot.entities().size());
            assertEquals(1, snapshot.evictedEntities());
            assertTrue(snapshot.entities().getFirst().identifier().contains("4"));
            assertEquals("police", snapshot.entities().getFirst().icon());
            assertEquals("#123456", snapshot.entities().getFirst().color());
            for(MapSnapshotService.Entity entity: snapshot.entities())
            {
                assertEquals(2, entity.positions().size());
                assertTrue(entity.positions().getFirst().timestampMs() >
                    entity.positions().getLast().timestampMs());
            }
        }
    }

    @Test
    void blockedWorkerAndFullQueueCannotBlockDecoderCallback() throws Exception
    {
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        AtomicReference<Thread> projectionThread = new AtomicReference<>();
        try(MapSnapshotService service = new MapSnapshotService((aliasList, identifier) ->
        {
            projectionThread.set(Thread.currentThread());
            projectionEntered.countDown();
            try
            {
                releaseProjection.await(5, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            return null;
        }, 4, 4, 2))
        {
            service.receive(event(1, 40, -83, 1_000));
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            assertFalse(Thread.currentThread() == projectionThread.get());

            assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
            {
                for(int radio = 2; radio < 1_002; radio++)
                {
                    service.receive(event(radio, 40, -83, radio * 3_000L));
                }
            });
            assertTrue(service.droppedObservations() > 0);
        }
        finally
        {
            releaseProjection.countDown();
        }
    }

    @Test
    void manySnapshotReadersDoNotAddReceiverSubscriptionsOrMutableState() throws Exception
    {
        try(MapSnapshotService service = new MapSnapshotService((aliasList, identifier) -> null,
            64, 16, 3))
        {
            for(int radio = 1; radio <= 10; radio++)
            {
                service.receive(event(radio, 40 + radio, -83, radio * 3_000L));
            }
            await(() -> service.snapshot().entities().size() == 10);

            try(ExecutorService readers = Executors.newFixedThreadPool(8))
            {
                List<Future<?>> futures = new ArrayList<>();
                for(int reader = 0; reader < 8; reader++)
                {
                    futures.add(readers.submit(() ->
                    {
                        for(int read = 0; read < 1_000; read++)
                        {
                            MapSnapshotService.Snapshot snapshot = service.snapshot();
                            assertTrue(snapshot.entities().size() <= 16);
                            snapshot.entities().forEach(entity -> assertTrue(entity.positions().size() <= 3));
                        }
                    }));
                }
                for(Future<?> future: futures)
                {
                    future.get(2, TimeUnit.SECONDS);
                }
            }
        }
    }

    @Test
    void invalidCoordinatesNeverReachSnapshot() throws Exception
    {
        try(MapSnapshotService service = new MapSnapshotService((aliasList, identifier) -> null,
            8, 8, 2))
        {
            service.receive(event(1, 91, -83, 1_000));
            service.receive(event(2, 0, 0, 2_000));
            service.receive(event(3, 40, -83, 3_000));
            await(() -> service.snapshot().entities().size() == 1);
            assertEquals(2, service.invalidObservations());
        }
    }

    private static PlottableDecodeEvent event(int radio, double latitude, double longitude, long timestampMs)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(AliasListConfigurationIdentifier.create("test"));
        identifiers.update(APCO25RadioIdentifier.createFrom(radio));
        return PlottableDecodeEvent.plottableBuilder(DecodeEventType.GPS, timestampMs)
            .identifiers(identifiers).location(new GeoPosition(latitude, longitude)).heading(90).speed(12).build();
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean() && System.nanoTime() < deadline)
        {
            Thread.sleep(10);
        }
        assertTrue(condition.getAsBoolean(), "Timed out waiting for map snapshot");
    }
}
