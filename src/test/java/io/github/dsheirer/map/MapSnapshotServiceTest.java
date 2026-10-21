/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.configuration.AliasListConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.event.PlottableDecodeEvent;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25RadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.protocol.Protocol;
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
    void aliasesUseBundledIconsAndLegacyOrUnsetNamesFallBackWithoutLosingLabels() throws Exception
    {
        AliasListDefinition definition = new AliasListDefinition("test", AliasListFamily.P25);
        definition.setId(1);
        List<Alias> aliases = new ArrayList<>();
        String[] icons = {"Police", "Old custom icon", null};
        for(int index = 0; index < icons.length; index++)
        {
            Alias alias = new Alias("Unit " + (index + 1));
            alias.setId(index + 1);
            alias.setAliasListDefinition(definition);
            alias.setMatchIdentifier(new Radio(Protocol.APCO25, index + 1));
            alias.setIconName(icons[index]);
            alias.setColor(0x123456);
            aliases.add(alias);
        }
        AliasModel model = new AliasModel();
        model.replaceCommittedConfiguration(List.of(definition), aliases);
        try(MapSnapshotService service = new MapSnapshotService(model))
        {
            for(int radio = 1; radio <= icons.length; radio++)
            {
                service.receive(event(radio, 40, -83, radio * 1_000L));
            }
            await(() -> service.snapshot().entities().size() == 3);
            for(var entity: service.snapshot().entities())
            {
                assertEquals("Unit " + entity.identifier(), entity.label());
                assertEquals("#123456", entity.color());
                assertEquals("1".equals(entity.identifier()) ? "police" : "no-icon", entity.icon());
            }
            assertEquals("Old custom icon", aliases.get(1).getIconName());
            assertEquals(0, service.projectionFailures());
        }
    }

    @Test
    void capturesReceivingSystemAndWorkingFactsBeforeObserverProjection() throws Exception
    {
        CountDownLatch projectionEntered = new CountDownLatch(1);
        CountDownLatch releaseProjection = new CountDownLatch(1);
        try(MapSnapshotService service = new MapSnapshotService((aliasList, identifier) ->
        {
            projectionEntered.countDown();
            try
            {
                releaseProjection.await(2, TimeUnit.SECONDS);
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
            return null;
        }, 8, 8, 2))
        {
            MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
            identifiers.update(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                501, 0xABCDE, 0x123, 9001));
            identifiers.update(APCO25Wacn.create(0xBEE00));
            identifiers.update(APCO25System.create(0x348));
            service.receive(PlottableDecodeEvent.plottableBuilder(DecodeEventType.GPS, 1_000)
                .identifiers(identifiers).location(new GeoPosition(40, -83)).build());
            assertTrue(projectionEntered.await(2, TimeUnit.SECONDS));
            identifiers.update(APCO25Wacn.create(0xABCDE));
            identifiers.update(APCO25System.create(0x123));
            identifiers.update(APCO25RadioIdentifier.createFrom(7001));
            releaseProjection.countDown();
            await(() -> service.snapshot().entities().size() == 1);
            var entity = service.snapshot().entities().getFirst();
            assertEquals("p25:bee00:348", entity.servingSystemKey());
            assertEquals(501, entity.observedWorkingId());
            assertEquals(0xABCDE, entity.homeWacn());
            assertEquals(0x123, entity.homeSystemId());
            assertEquals(9001, entity.radioId());
        }
        finally
        {
            releaseProjection.countDown();
        }
    }

    @Test
    void missingOrPartialReceivingFactsAndHomeOnlyValuesRemainUnknown() throws Exception
    {
        try(MapSnapshotService service = new MapSnapshotService((aliasList, identifier) -> null, 8, 8, 2))
        {
            for(int index = 0; index < 3; index++)
            {
                MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
                identifiers.update(SystemConfigurationIdentifier.create("Navigation system name"));
                identifiers.update(index == 1 ? APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                    0, 0xABCDE, 0x123, 9001 + index) :
                    APCO25FullyQualifiedRadioIdentifier.createFrom(9001 + index, 0xABCDE, 0x123, 9001 + index));
                if(index == 1) identifiers.update(APCO25Wacn.create(0xBEE00));
                if(index == 2) identifiers.update(APCO25System.create(0x348));
                service.receive(PlottableDecodeEvent.plottableBuilder(DecodeEventType.GPS, 1_000 + index)
                    .identifiers(identifiers).location(new GeoPosition(40, -83)).build());
            }
            await(() -> service.snapshot().entities().size() == 3);
            for(var entity : service.snapshot().entities())
            {
                assertNull(entity.servingSystemKey());
                assertNull(entity.observedWorkingId());
                assertEquals("Navigation system name", entity.system());
                assertEquals(0xABCDE, entity.homeWacn());
            }
        }
    }

    @Test
    void keepsTypedHomeIdentityWhenHexDisplayContainsOnlyDigits() throws Exception
    {
        try(MapSnapshotService service = new MapSnapshotService((aliasList, identifier) -> null, 8, 8, 2))
        {
            MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
            identifiers.update(APCO25FullyQualifiedRadioIdentifier.createFrom(1103, 1, 0x018, 1103));
            service.receive(PlottableDecodeEvent.plottableBuilder(DecodeEventType.GPS, 1_000)
                .identifiers(identifiers).location(new GeoPosition(40, -83)).build());
            await(() -> service.snapshot().entities().size() == 1);
            MapSnapshotService.Entity entity = service.snapshot().entities().getFirst();
            assertEquals("00001.018.1103", entity.identifier());
            assertEquals(1, entity.homeWacn());
            assertEquals(24, entity.homeSystemId());
            assertEquals(1103, entity.radioId());
        }
    }

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
            assertEquals("Test System", snapshot.entities().getFirst().system());
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
        identifiers.update(SystemConfigurationIdentifier.create("Test System"));
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
