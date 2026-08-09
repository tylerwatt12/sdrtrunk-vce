/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.preference.UserPreferences;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class ChannelDiagnosticServiceTest
{
    private static final String CONFIGURATION_ID = "00000000-0000-0000-0000-000000000001";

    @Test
    void supportsBoundedDemandSessionsAndReleasesThemOnClose()
    {
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences());
        DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
        ChannelDiagnosticService service = new ChannelDiagnosticService(manager, scheduler);
        ChannelDiagnosticService.Scope scope = new ChannelDiagnosticService.Scope(CONFIGURATION_ID, 851_012_500L,
            null);
        ChannelDiagnosticService.OpenResult first = service.tryOpen(scope);
        assertEquals(ChannelDiagnosticService.OpenStatus.OPEN, first.status());
        assertEquals("waiting", first.session().state().state());
        assertEquals("waiting", first.session().state().signalState());
        assertEquals("waiting", first.session().state().symbolsState());
        ChannelDiagnosticService.OpenResult second = service.tryOpen(scope);
        assertEquals(ChannelDiagnosticService.OpenStatus.OPEN, second.status());
        assertFalse(first.session().isClosed());
        assertEquals(2, service.activeSessionCount());
        assertEquals(0, service.activeProducerCount());
        assertFalse(scheduler.hasWorker());
        first.session().close();
        assertEquals(1, service.activeSessionCount());
        second.session().close();
        assertEquals(0, service.activeSessionCount());

        service.close();
        scheduler.close();
        assertEquals(ChannelDiagnosticService.OpenStatus.CLOSED, service.tryOpen(scope).status());
    }

    @Test
    void releasesLeaseWhenInitialRefreshFails()
    {
        AtomicBoolean fail = new AtomicBoolean(true);
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences())
        {
            @Override
            public List<ProcessingChain> getProcessingChainsByConfiguration(String configurationId, Long frequency)
            {
                if(fail.getAndSet(false))
                {
                    throw new IllegalStateException("test failure");
                }

                return List.of();
            }
        };
        ChannelDiagnosticService service = new ChannelDiagnosticService(manager);
        ChannelDiagnosticService.Scope scope = new ChannelDiagnosticService.Scope(CONFIGURATION_ID, 851_012_500L,
            null);
        assertThrows(IllegalStateException.class, () -> service.tryOpen(scope));
        ChannelDiagnosticService.OpenResult retry = service.tryOpen(scope);
        assertEquals(ChannelDiagnosticService.OpenStatus.OPEN, retry.status());
        retry.session().close();
        service.close();
    }

    @Test
    void validatesExactFrequencyAndBoundedSymbolBatch()
    {
        assertThrows(IllegalArgumentException.class, () -> new ChannelDiagnosticService.Scope(
            CONFIGURATION_ID, 0, null));
        assertFalse(ChannelDiagnosticService.MAXIMUM_VISIBLE_SYMBOLS < ChannelDiagnosticService.SYMBOL_BATCH_SIZE);
    }

    @Test
    void retainsLatestSignalAndSymbolsIndependently() throws Exception
    {
        DiagnosticFrameQueue queue = new DiagnosticFrameQueue();
        DiagnosticStreamFrame signal = DiagnosticStreamFrame.float32(
            DiagnosticStreamFrame.TYPE_CHANNEL_SIGNAL, 1, 1, 10, 851_012_500L, 25_000, 1_024,
            new float[]{1.0f});
        DiagnosticStreamFrame symbols = DiagnosticStreamFrame.float32(
            DiagnosticStreamFrame.TYPE_CHANNEL_SYMBOLS, 1, 1, 11, 851_012_500L, 4_800, 0,
            new float[]{2.0f});
        DiagnosticStreamFrame nextSignal = DiagnosticStreamFrame.float32(
            DiagnosticStreamFrame.TYPE_CHANNEL_SIGNAL, 1, 2, 12, 851_012_500L, 25_000, 1_024,
            new float[]{3.0f});
        queue.offer(signal);
        queue.offer(symbols);

        assertSame(signal, queue.poll(Duration.ZERO));
        queue.offer(nextSignal);
        assertSame(symbols, queue.poll(Duration.ZERO));
        assertSame(nextSignal, queue.poll(Duration.ZERO));
        assertNull(queue.poll(Duration.ZERO));
        queue.close();
    }

    @Test
    void waitsForFramesWhenBothDiagnosticsAreIdle() throws Exception
    {
        DiagnosticFrameQueue queue = new DiagnosticFrameQueue();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);

        try
        {
            Future<DiagnosticStreamFrame> waiting = executor.submit(() ->
            {
                started.countDown();
                return queue.poll(Duration.ofSeconds(5));
            });
            assertTrue(started.await(1, TimeUnit.SECONDS));
            Thread.sleep(25);
            assertFalse(waiting.isDone());
            queue.close();
            assertNull(waiting.get(1, TimeUnit.SECONDS));
        }
        finally
        {
            queue.close();
            executor.shutdownNow();
        }
    }
}
