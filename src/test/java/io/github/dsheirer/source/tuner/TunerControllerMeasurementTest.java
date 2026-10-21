/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.source.tuner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.source.ISourceEventProcessor;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.tuner.test.TestTunerController;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class TunerControllerMeasurementTest
{
    @Test
    void measuredErrorUpdatesRetainSignedAndZeroValuesWithoutCallingSourceObservers() throws Exception
    {
        TestTunerController controller = new TestTunerController();
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger observerCalls = new AtomicInteger();
        ISourceEventProcessor blockedObserver = event ->
        {
            observerCalls.incrementAndGet();
            try
            {
                release.await();
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        };
        controller.addListener(blockedObserver);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try
        {
            worker.submit(() ->
            {
                for(int measured : new int[]{125, 0, -75})
                {
                    controller.setMeasuredFrequencyError(measured);
                    assertEquals(measured, controller.getMeasuredFrequencyError());
                }
            }).get(1, TimeUnit.SECONDS);
            assertEquals(-75, controller.getMeasuredFrequencyError());
            assertEquals(0, observerCalls.get());
        }
        finally
        {
            release.countDown();
            controller.removeListener(blockedObserver);
            worker.shutdownNow();
            controller.getTunerFrequencyErrorManager().dispose();
        }
    }

    @Test
    void tuningAndPpmChangesStillPublishTheirControlNotifications() throws Exception
    {
        TestTunerController controller = new TestTunerController();
        List<SourceEvent.Event> events = new ArrayList<>();
        ISourceEventProcessor observer = event -> events.add(event.getEvent());
        controller.addListener(observer);
        try
        {
            controller.setFrequency(851_000_000);
            controller.setFrequencyCorrection(-0.25);
            assertTrue(events.contains(SourceEvent.Event.NOTIFICATION_FREQUENCY_CHANGE));
            assertTrue(events.contains(SourceEvent.Event.NOTIFICATION_FREQUENCY_CORRECTION_CHANGE));
            assertEquals(851_000_000, controller.getFrequency());
            assertEquals(-0.25, controller.getFrequencyCorrection());
        }
        finally
        {
            controller.removeListener(observer);
            controller.getTunerFrequencyErrorManager().dispose();
        }
    }
}
