/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.stats.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.metadata.activity.ChannelActivityModel;
import io.github.dsheirer.channel.metadata.activity.ChannelActivitySnapshot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReceiverHealthServiceTest
{
    @TempDir
    Path mTemporaryDirectory;

    @Test
    void publishesTheLocalIncidentSnapshotFromTheObserverSample() throws Exception
    {
        AtomicLong clock = new AtomicLong(1_000);
        Path target = mTemporaryDirectory.resolve(ReceiverHealthSnapshotWriter.FILE_NAME);

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get,
            new ReceiverHealthSnapshotWriter(target)))
        {
            service.sampleNow();
            assertTrue(Files.isRegularFile(target));
            String first = Files.readString(target);
            assertFalse(first.contains("\"measurements\""));

            clock.set(2_000);
            service.sampleNow();
            assertEquals(first, Files.readString(target));
        }
    }

    @Test
    void keepsObserverLossInformationalAndOutOfTheServiceImpactSummary()
    {
        AtomicLong clock = new AtomicLong(1_000);
        AtomicLong eventDrops = new AtomicLong(2);

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null,
            clock::get))
        {
            service.setWebStatusSupplier(() -> Map.of(
                "server", Map.of("liveTransport", Map.of(
                    "activeClients", 1,
                    "rejectedClients", 0,
                    "slowDisconnects", 0,
                    "eventDrops", eventDrops.get())),
                "webPlayer", Map.of(),
                "diagnostics", Map.of()));
            service.sampleNow();

            Map<String,Object> snapshot = service.snapshot();
            assertEquals("healthy", map(snapshot.get("summary")).get("severity"));
            List<Map<String,Object>> active = rows(snapshot.get("active"));
            assertTrue(active.isEmpty());
            assertEquals(9, rows(snapshot.get("measurements")).size());
            assertEquals("info", measurement(snapshot, "supporting", "web").get("severity"));

            clock.set(11_000);
            service.sampleNow();
            assertTrue(rows(service.snapshot().get("active")).isEmpty());
            assertTrue(rows(service.snapshot().get("resolved")).isEmpty());
        }
    }

    @Test
    void closeInterruptsAndJoinsAnInProgressObserverSample() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        ReceiverHealthService service = new ReceiverHealthService(null, null, null, null,
            System::currentTimeMillis);
        service.setWebStatusSupplier(() ->
        {
            entered.countDown();

            try
            {
                new CountDownLatch(1).await();
            }
            catch(InterruptedException interruptedException)
            {
                interrupted.countDown();
                Thread.currentThread().interrupt();
            }

            return Map.of();
        });
        service.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        service.close();
        assertTrue(interrupted.await(2, TimeUnit.SECONDS));
    }

    @Test
    void transientObserverFailureDoesNotReplayCumulativeLossOnRecovery()
    {
        AtomicLong clock = new AtomicLong(1_000);
        AtomicBoolean fail = new AtomicBoolean();

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null,
            clock::get))
        {
            service.setWebStatusSupplier(() ->
            {
                if(fail.get())
                {
                    throw new IllegalStateException("temporary observer failure");
                }

                return Map.of("server", Map.of("liveTransport", Map.of(
                    "activeClients", 0, "rejectedClients", 0, "slowDisconnects", 0, "eventDrops", 2)),
                    "webPlayer", Map.of(), "diagnostics", Map.of());
            });
            service.sampleNow();
            assertTrue(rows(service.snapshot().get("active")).isEmpty());

            clock.set(11_000);
            service.sampleNow();
            assertTrue(rows(service.snapshot().get("active")).isEmpty());
            assertTrue(rows(service.snapshot().get("resolved")).isEmpty());

            fail.set(true);
            clock.set(12_000);
            service.sampleNow();
            fail.set(false);
            clock.set(13_000);
            service.sampleNow();

            assertTrue(rows(service.snapshot().get("active")).isEmpty());
            assertTrue(rows(service.snapshot().get("resolved")).isEmpty());
        }
    }

    @Test
    void reportsSiteLevelControlLockLossAcrossAlternateFrequencySearch()
    {
        AtomicLong clock = new AtomicLong(1_000);
        AtomicReference<ChannelActivityModel.SnapshotSet> activity = new AtomicReference<>(
            activity(773_831_250L, 1_000L, 1_000L, 10));

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get))
        {
            service.setChannelActivitySnapshotSupplier(activity::get);
            service.sampleNow();
            assertEquals("healthy", map(service.snapshot().get("summary")).get("severity"));

            clock.set(10_999L);
            activity.set(activity(774_281_250L, 10_999L, 0, 0));
            service.sampleNow();
            assertTrue(rows(service.snapshot().get("active")).isEmpty());

            clock.set(11_000L);
            activity.set(activity(774_531_250L, 11_000L, 0, 0));
            service.sampleNow();
            List<Map<String,Object>> active = rows(service.snapshot().get("active"));
            assertEquals(1, active.size());
            assertEquals("control-channel-lock-lost", active.getFirst().get("code"));
            assertEquals("critical", map(service.snapshot().get("summary")).get("severity"));

            clock.set(11_001L);
            activity.set(activity(774_781_250L, 11_001L, 0, 0));
            service.sampleNow();
            assertEquals(1, rows(service.snapshot().get("active")).size());
        }
    }

    @Test
    void doesNotReportControlLockLossBeforeAValidControlFrame()
    {
        AtomicLong clock = new AtomicLong(30_000L);

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get))
        {
            service.setChannelActivitySnapshotSupplier(() -> activity(774_281_250L, clock.get(), 0, 0));
            service.sampleNow();
            assertTrue(rows(service.snapshot().get("active")).isEmpty());
            assertEquals("healthy", map(service.snapshot().get("summary")).get("severity"));
        }
    }

    @Test
    void keepsRawDecoderDropEvidenceAsAnInformationalMeasurement()
    {
        AtomicLong clock = new AtomicLong(1_000L);

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get))
        {
            service.setChannelActivitySnapshotSupplier(() -> activity(773_831_250L, clock.get(), 0, 0, 48));
            service.sampleNow();
            assertTrue(rows(service.snapshot().get("active")).isEmpty());
            assertTrue(rows(service.snapshot().get("resolved")).isEmpty());
            Map<String,Object> summary = map(service.snapshot().get("summary"));
            assertEquals("healthy", summary.get("severity"));
            assertEquals(0, summary.get("active_count"));
            assertEquals(0, summary.get("diagnostic_count"));
            assertTrue(String.valueOf(measurement(service.snapshot(), "decoders", "site-table").get("detail"))
                .contains("dropped_bits=48"));
        }
    }

    @Test
    void keepsSameNamedSitesAsSeparateControlLockIncidents()
    {
        AtomicLong clock = new AtomicLong(1_000L);
        AtomicReference<ChannelActivityModel.SnapshotSet> activity = new AtomicReference<>(
            new ChannelActivityModel.SnapshotSet(1_000L, List.of(
                controlTable("site-a", 773_831_250L, 1_000L, 1_000L, 10, 0),
                controlTable("site-b", 856_162_500L, 1_000L, 1_000L, 10, 0))));

        try(ReceiverHealthService service = new ReceiverHealthService(null, null, null, null, clock::get))
        {
            service.setChannelActivitySnapshotSupplier(activity::get);
            service.sampleNow();
            clock.set(11_000L);
            activity.set(new ChannelActivityModel.SnapshotSet(11_000L, List.of(
                controlTable("site-a", 774_281_250L, 11_000L, 0, 0, 0),
                controlTable("site-b", 855_987_500L, 11_000L, 0, 0, 0))));
            service.sampleNow();
            assertEquals(2, rows(service.snapshot().get("active")).size());
            assertEquals(2, map(service.snapshot().get("summary")).get("active_count"));
        }
    }

    private static ChannelActivityModel.SnapshotSet activity(long frequencyHz, long observedAtMs,
                                                              long lastValidDecodeMs, long validFrames)
    {
        return activity(frequencyHz, observedAtMs, lastValidDecodeMs, validFrames, 0);
    }

    private static ChannelActivityModel.SnapshotSet activity(long frequencyHz, long observedAtMs,
                                                              long lastValidDecodeMs, long validFrames,
                                                              long droppedBits)
    {
        return new ChannelActivityModel.SnapshotSet(observedAtMs, List.of(controlTable("site-table", frequencyHz,
            observedAtMs, lastValidDecodeMs, validFrames, droppedBits)));
    }

    private static ChannelActivitySnapshot controlTable(String tableId, long frequencyHz, long observedAtMs,
                                                         long lastValidDecodeMs, long validFrames,
                                                         long droppedBits)
    {
        ChannelActivitySnapshot.Row row = new ChannelActivitySnapshot.Row("control-" + frequencyHz, "Control",
            null, "ACTIVE", List.of("CURRENT_CONTROL"), null, frequencyHz, null, -45.0,
            validFrames > 0 ? 98.0 : null, observedAtMs, validFrames, 0, 0, 0, droppedBits, lastValidDecodeMs,
            null, null, null, null, null, null, null, null, null, null, "P25_PHASE1", null);
        return new ChannelActivitySnapshot(tableId, "County · Downtown", "County",
            "Downtown", "Control", null, null, true, List.of(), List.of(row));
    }

    private static Map<String,Object> measurement(Map<String,Object> snapshot, String sectionId, String scope)
    {
        return rows(snapshot.get("measurements")).stream()
            .filter(section -> sectionId.equals(section.get("id")))
            .flatMap(section -> rows(section.get("rows")).stream())
            .filter(row -> scope.equals(row.get("scope")))
            .findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object value)
    {
        return (Map<String,Object>)value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String,Object>> rows(Object value)
    {
        return (List<Map<String,Object>>)value;
    }
}
