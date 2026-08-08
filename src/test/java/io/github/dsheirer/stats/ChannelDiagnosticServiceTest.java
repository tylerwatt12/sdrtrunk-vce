/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.controller.channel.ChannelProcessingManager;
import io.github.dsheirer.module.ProcessingChain;
import io.github.dsheirer.preference.UserPreferences;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
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
    void ownsOneDemandLeaseAndReleasesItOnClose()
    {
        ChannelProcessingManager manager = new ChannelProcessingManager(null, null, null, new UserPreferences());
        ChannelDiagnosticService service = new ChannelDiagnosticService(manager);
        ChannelDiagnosticService.Scope scope = new ChannelDiagnosticService.Scope(CONFIGURATION_ID, 851_012_500L,
            null);
        UUID firstClient = UUID.randomUUID();
        UUID otherClient = UUID.randomUUID();

        ChannelDiagnosticService.OpenResult first = service.tryOpen(scope, firstClient);
        assertEquals(ChannelDiagnosticService.OpenStatus.OPEN, first.status());
        assertEquals("waiting", first.session().state().state());
        assertEquals("waiting", first.session().state().signalState());
        assertEquals("waiting", first.session().state().symbolsState());
        assertEquals(ChannelDiagnosticService.OpenStatus.BUSY, service.tryOpen(scope, otherClient).status());

        ChannelDiagnosticService.OpenResult replacement = service.tryOpen(scope, firstClient);
        assertEquals(ChannelDiagnosticService.OpenStatus.OPEN, replacement.status());
        assertTrue(first.session().isClosed());
        replacement.session().close();

        service.close();
        assertEquals(ChannelDiagnosticService.OpenStatus.CLOSED, service.tryOpen(scope, firstClient).status());
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
        UUID clientId = UUID.randomUUID();

        assertThrows(IllegalStateException.class, () -> service.tryOpen(scope, clientId));
        ChannelDiagnosticService.OpenResult retry = service.tryOpen(scope, UUID.randomUUID());
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
        ChannelDiagnosticService.LatestFrameQueue queue = new ChannelDiagnosticService.LatestFrameQueue();
        AtomicBoolean sourceClosed = new AtomicBoolean();
        queue.publish(new ChannelDiagnosticService.Frame("signal", 1, 1, 10, new float[]{1.0f}), sourceClosed);
        queue.publish(new ChannelDiagnosticService.Frame("symbols", 1, 1, 11, new float[]{2.0f}), sourceClosed);

        ChannelDiagnosticService.Frame signal = queue.poll(Duration.ZERO);
        queue.publish(new ChannelDiagnosticService.Frame("signal", 1, 2, 12, new float[]{3.0f}), sourceClosed);
        ChannelDiagnosticService.Frame symbols = queue.poll(Duration.ZERO);
        ChannelDiagnosticService.Frame nextSignal = queue.poll(Duration.ZERO);
        assertEquals("signal", signal.type());
        assertEquals(1, signal.sequence());
        assertEquals(1.0f, signal.values()[0]);
        assertEquals("symbols", symbols.type());
        assertEquals(1, symbols.sequence());
        assertEquals(2.0f, symbols.values()[0]);
        assertEquals("signal", nextSignal.type());
        assertEquals(2, nextSignal.sequence());
        assertEquals(3.0f, nextSignal.values()[0]);
        assertNull(queue.poll(Duration.ZERO));
        queue.close();
    }

    @Test
    void waitsForFramesWhenBothDiagnosticsAreIdle() throws Exception
    {
        ChannelDiagnosticService.LatestFrameQueue queue = new ChannelDiagnosticService.LatestFrameQueue();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch started = new CountDownLatch(1);

        try
        {
            Future<ChannelDiagnosticService.Frame> waiting = executor.submit(() ->
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
