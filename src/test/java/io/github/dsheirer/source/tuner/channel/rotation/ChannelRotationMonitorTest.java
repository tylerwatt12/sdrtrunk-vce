/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.channel.rotation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.module.decode.p25.P25ControlChannelRotationPolicy;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ChannelRotationMonitorTest
{
    @Test
    void usesTwoSecondDefaultAndTenSecondMaximum()
    {
        SourceConfigTunerMultipleFrequency source = new SourceConfigTunerMultipleFrequency();
        assertEquals(2_000, ChannelRotationMonitor.CHANNEL_ROTATION_DELAY_DEFAULT);
        assertEquals(10_000, ChannelRotationMonitor.CHANNEL_ROTATION_DELAY_MAXIMUM);
        assertEquals(2_000, source.getFrequencyRotationDelay());

        source.setFrequencyRotationDelay(20_000);
        assertEquals(10_000, source.getFrequencyRotationDelay());
    }

    @Test
    void searchesUsingConfiguredRotationDelay()
    {
        AtomicInteger rotations = new AtomicInteger();
        ChannelRotationMonitor monitor = monitor(rotations);
        long now = System.currentTimeMillis();

        monitor.checkState(now + 600);

        assertEquals(1, rotations.get());
    }

    @Test
    void toleratesBriefLossAfterActiveStateThenReturnsToFastSearch()
    {
        AtomicInteger rotations = new AtomicInteger();
        ChannelRotationMonitor monitor = monitor(rotations);
        long activeAt = System.currentTimeMillis();

        monitor.receive(DecoderStateEvent.stateNotification(State.CONTROL, 0));
        monitor.checkState(activeAt + 1500);
        assertEquals(0, rotations.get());

        monitor.checkState(activeAt + 2500);
        assertEquals(1, rotations.get());

        monitor.checkState(activeAt + 3100);
        assertEquals(2, rotations.get());
    }

    @Test
    void p25PolicyToleratesFourSecondLossThenReturnsToFastSearch()
    {
        AtomicInteger rotations = new AtomicInteger();
        ChannelRotationMonitor monitor = new ChannelRotationMonitor(List.of(State.CONTROL),
            P25ControlChannelRotationPolicy.SEARCH_DWELL_MILLISECONDS,
            P25ControlChannelRotationPolicy.ACTIVE_STATE_LOSS_GRACE_MILLISECONDS);
        monitor.setSourceEventListener(event -> {
            assertEquals(SourceEvent.Event.REQUEST_FREQUENCY_ROTATION, event.getEvent());
            rotations.incrementAndGet();
        });
        long activeAt = System.currentTimeMillis();

        monitor.receive(DecoderStateEvent.stateNotification(State.CONTROL, 0));
        monitor.checkState(activeAt + 3_999);
        assertEquals(0, rotations.get());

        monitor.checkState(activeAt + 4_200);
        assertEquals(1, rotations.get());

        monitor.checkState(activeAt + 4_800);
        assertEquals(2, rotations.get());
    }

    @Test
    void pauseWaitsForAnInFlightRotationCallbackAndPreventsAnotherRotation() throws Exception
    {
        AtomicInteger rotations = new AtomicInteger();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch pauseReturned = new CountDownLatch(1);
        ChannelRotationMonitor monitor = new ChannelRotationMonitor(List.of(State.CONTROL), 500,
            ChannelRotationMonitor.ACTIVE_STATE_LOSS_DELAY_DEFAULT);
        monitor.setSourceEventListener(event -> {
            rotations.incrementAndGet();
            callbackEntered.countDown();

            try
            {
                assertTrue(releaseCallback.await(2, TimeUnit.SECONDS));
            }
            catch(InterruptedException exception)
            {
                Thread.currentThread().interrupt();
            }
        });
        long now = System.currentTimeMillis();
        Thread checkThread = new Thread(() -> monitor.checkState(now + 600), "rotation-check-test");
        checkThread.start();
        assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
        ChannelRotationMonitorPauseRequest pauseRequest = new ChannelRotationMonitorPauseRequest();
        Thread pauseThread = new Thread(() -> {
            monitor.pause(pauseRequest);
            pauseReturned.countDown();
        }, "rotation-pause-test");
        pauseThread.start();

        assertFalse(pauseReturned.await(100, TimeUnit.MILLISECONDS),
            "pause returned while the rotation callback was still in flight");
        releaseCallback.countDown();
        assertTrue(pauseReturned.await(2, TimeUnit.SECONDS));
        checkThread.join(2_000);
        pauseThread.join(2_000);
        assertTrue(pauseRequest.isMonitorPaused());

        monitor.checkState(now + 1_200);
        assertEquals(1, rotations.get());
        monitor.resume(new ChannelRotationMonitorResumeRequest());
        monitor.checkState(System.currentTimeMillis() + 600);
        assertEquals(2, rotations.get());
    }

    private static ChannelRotationMonitor monitor(AtomicInteger rotations)
    {
        ChannelRotationMonitor monitor = new ChannelRotationMonitor(List.of(State.CONTROL), 500,
            ChannelRotationMonitor.ACTIVE_STATE_LOSS_DELAY_DEFAULT);
        monitor.setSourceEventListener(event -> {
            assertEquals(SourceEvent.Event.REQUEST_FREQUENCY_ROTATION, event.getEvent());
            rotations.incrementAndGet();
        });
        return monitor;
    }
}
