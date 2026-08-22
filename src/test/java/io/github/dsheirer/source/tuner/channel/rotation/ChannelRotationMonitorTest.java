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

import io.github.dsheirer.channel.state.DecoderStateEvent;
import io.github.dsheirer.channel.state.State;
import io.github.dsheirer.module.decode.p25.P25ControlChannelRotationPolicy;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.config.SourceConfigTunerMultipleFrequency;
import java.util.List;
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
