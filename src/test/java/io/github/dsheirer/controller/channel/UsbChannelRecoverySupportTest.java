/* Copyright (C) 2026. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.controller.channel;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.controller.channel.ChannelProcessingManager.TunerChannelAssignment;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.channel.TunerChannelSource;
import io.github.dsheirer.source.tuner.manager.DiscoveredUSBTuner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class UsbChannelRecoverySupportTest
{
    @Test void reconnectRestartsOnlyPreviouslyRunningConfiguredChannelsOnTheirOriginalUsbOwner()
    {
        Harness h = new Harness();
        FakeUsb a = h.usb("a");
        FakeUsb b = h.usb("b");
        Channel control = h.running("Control", a, true);
        Channel other = h.running("Other", b, true);
        Channel neverRan = new Channel("Intentionally stopped");
        Channel traffic = h.running("Temporary call", a, false);

        h.support.tunerUnavailable(a);
        assertTrue(h.support.isPending(control.getConfigurationId()));
        assertFalse(h.active.containsKey(control));
        assertTrue(h.active.containsKey(other));
        assertFalse(h.support.isPending(neverRan.getConfigurationId()));
        assertFalse(h.support.isPending(traffic.getConfigurationId()));
        h.support.tunerAvailable(b);
        assertTrue(h.started.isEmpty(), "Another healthy tuner must not take over interrupted channels");
        h.support.tunerAvailable(a);
        assertEquals(List.of(control), h.started);
        assertSame(a, h.active.get(control));
        assertFalse(h.support.hasInterruptedChannels(a.getId()));
    }

    @Test void explicitStopEditDeleteAndSetupCancelWaitingRecovery()
    {
        Harness h = new Harness();
        FakeUsb usb = h.usb("a");
        Channel stopped = h.running("Stop", usb, true);
        Channel edited = h.running("Edit", usb, true);
        Channel deleted = h.running("Delete", usb, true);
        h.support.tunerUnavailable(usb);
        h.support.cancel(stopped.getConfigurationId());
        h.support.cancelPending(edited.getConfigurationId());
        h.support.cancel(deleted.getConfigurationId());
        h.support.tunerAvailable(usb);
        assertTrue(h.started.isEmpty());

        Channel setup = h.running("Setup supersedes recovery", usb, true);
        h.support.tunerUnavailable(usb);
        assertTrue(h.support.isPending(setup.getConfigurationId()));
        usb.holdForSetup();
        // The test tuner still presents LIVE/available, as if Setup had already finished between USB worker ticks.
        h.support.tunerAvailable(usb);
        assertFalse(h.support.isPending(setup.getConfigurationId()));
        assertTrue(h.started.isEmpty());
    }

    @Test void explicitDisableInvalidatesIntentEvenIfReenabledBeforeNextWorkerPass()
    {
        Harness h = new Harness();
        FakeUsb usb = h.usb("a");
        Channel channel = h.running("Control", usb, true);
        h.support.tunerUnavailable(usb);
        usb.setEnabled(false);
        // isEnabled/isAvailable deliberately return true to model an already-finished re-enable.
        h.support.tunerAvailable(usb);
        assertFalse(h.support.isPending(channel.getConfigurationId()));
        assertTrue(h.started.isEmpty());
    }

    @Test void rotatedChannelKeepsItsCurrentOwnersIntentWhenFormerOwnerIsDisabledOrEntersSetup()
    {
        Harness h = new Harness();
        FakeUsb former = h.usb("a");
        FakeUsb current = h.usb("b");
        Channel channel = h.running("Rotating control", former, true);
        h.active.put(channel, current);
        h.support.sourceAssigned(channel, current.getId(), channel.getProcessingIncarnation());
        former.holdForSetup();
        h.support.tunerDisabled(former);
        h.support.tunerUnavailable(current);
        h.support.tunerAvailable(current);
        assertEquals(List.of(channel), h.started);
        assertSame(current, h.active.get(channel));

        // Also cover the tiny publication window before the rotation callback runs.
        Channel second = h.running("Assignment publication window", former, true);
        h.active.put(second, current);
        h.support.tunerDisabled(former);
        h.support.tunerUnavailable(current);
        h.support.tunerAvailable(current);
        assertTrue(h.started.contains(second));
    }

    @Test void sourceErrorBeforeUsbPollPreservesIntentAndStopDoesNotReinstateIt()
    {
        Harness h = new Harness();
        FakeUsb usb = h.usb("a");
        Channel channel = h.running("Control", usb, true);
        long incarnation = channel.getProcessingIncarnation();
        FakeSource source = h.source(usb);
        h.support.sourceInterrupted(channel, source, incarnation);
        h.active.remove(channel);
        h.support.tunerUnavailable(usb);
        h.support.tunerAvailable(usb);
        assertEquals(List.of(channel), h.started);

        h.support.cancel(channel.getConfigurationId());
        h.support.sourceInterrupted(channel, source, channel.getProcessingIncarnation());
        h.active.remove(channel);
        h.support.tunerAvailable(usb);
        assertEquals(1, h.started.size(), "A later source error must not recreate canceled run intent");
    }

    @Test void staleFormerOwnerAssignmentCannotOverwriteANewerUsbRotation()
    {
        Harness h = new Harness();
        FakeUsb former = h.usb("a");
        FakeUsb current = h.usb("b");
        Channel channel = h.running("Rotating control", former, true);
        h.assignmentsSnapshotHook = () -> {
            h.active.put(channel, current);
            h.support.sourceAssigned(channel, current.getId(), channel.getProcessingIncarnation());
        };
        h.support.tunerUnavailable(former);
        assertSame(current, h.active.get(channel));
        assertFalse(h.support.isPending(channel.getConfigurationId()));
        h.support.tunerUnavailable(current);
        h.support.tunerAvailable(current);
        assertEquals(List.of(channel), h.started);
    }

    @Test void staleIncarnationAndReplacementConfigurationCannotBeRestarted()
    {
        Harness h = new Harness();
        FakeUsb usb = h.usb("a");
        Channel channel = h.running("Control", usb, true);
        long old = channel.getProcessingIncarnation();
        channel.activateProcessingIncarnation(++h.incarnation);
        h.support.channelStarted(channel, h.source(usb), h.incarnation);
        h.support.sourceInterrupted(channel, h.source(usb), old);
        assertFalse(h.support.isPending(channel.getConfigurationId()));
        h.support.tunerUnavailable(usb);
        Channel replacement = new Channel("Edited");
        replacement.setConfigurationId(channel.getConfigurationId());
        h.support.configurationChanged(replacement);
        h.support.tunerAvailable(usb);
        assertTrue(h.started.isEmpty());
    }

    @Test void failedChannelStartsUseBoundedBackoffAndKeepOneCancelableIntent()
    {
        Harness h = new Harness();
        FakeUsb usb = h.usb("a");
        Channel channel = h.running("Control", usb, true);
        h.support.tunerUnavailable(usb);
        h.failStart = true;
        for(int index = 0; index < 100; index++) h.support.tunerAvailable(usb);
        assertEquals(1, h.attempts);
        h.clock.addAndGet(TimeUnit.SECONDS.toNanos(2));
        h.support.tunerAvailable(usb);
        assertEquals(2, h.attempts);
        h.clock.addAndGet(TimeUnit.SECONDS.toNanos(3));
        h.support.tunerAvailable(usb);
        assertEquals(2, h.attempts);
        h.clock.addAndGet(TimeUnit.SECONDS.toNanos(1));
        h.failStart = false;
        h.support.tunerAvailable(usb);
        assertEquals(3, h.attempts);
        assertEquals(List.of(channel), h.started);
        assertFalse(h.support.isPending(channel.getConfigurationId()));
    }

    @Test void stopRacingRecoveryWaitsForStartThenCancelsItsNewIntent() throws Exception
    {
        Harness h = new Harness();
        FakeUsb usb = h.usb("a");
        Channel channel = h.running("Control", usb, true);
        h.support.tunerUnavailable(usb);
        h.startEntered = new CountDownLatch(1);
        h.releaseStart = new CountDownLatch(1);
        CountDownLatch canceled = new CountDownLatch(1);
        Thread recovery = new Thread(() -> h.support.tunerAvailable(usb));
        Thread stop = new Thread(() -> {
            h.support.cancel(channel.getConfigurationId());
            h.active.remove(channel);
            canceled.countDown();
        });
        try
        {
            recovery.start();
            assertTrue(h.startEntered.await(2, TimeUnit.SECONDS));
            stop.start();
            assertFalse(canceled.await(50, TimeUnit.MILLISECONDS));
            h.releaseStart.countDown();
            assertTrue(canceled.await(2, TimeUnit.SECONDS));
            recovery.join(2_000);
            stop.join(2_000);
            h.support.tunerUnavailable(usb);
            h.support.tunerAvailable(usb);
            assertEquals(1, h.started.size());
            assertFalse(h.active.containsKey(channel));
        }
        finally { h.releaseStart.countDown(); recovery.join(2_000); stop.join(2_000); }
    }

    private static class Harness implements UsbChannelRecoverySupport.Operations
    {
        final Map<String,FakeUsb> tuners = new HashMap<>();
        final Map<Channel,FakeUsb> active = new java.util.concurrent.ConcurrentHashMap<>();
        final List<Channel> started = new ArrayList<>();
        final AtomicLong clock = new AtomicLong(1);
        final UsbChannelRecoverySupport support = new UsbChannelRecoverySupport(new Object(), this, clock::get);
        long incarnation;
        int attempts;
        boolean failStart;
        CountDownLatch startEntered;
        CountDownLatch releaseStart;
        Runnable assignmentsSnapshotHook;

        FakeUsb usb(String id) { FakeUsb usb = new FakeUsb(id); tuners.put(id, usb); return usb; }
        FakeSource source(FakeUsb usb) { return new FakeSource(usb.getId()); }
        Channel running(String name, FakeUsb usb, boolean standard)
        {
            Channel channel = new Channel(name, standard ? Channel.ChannelType.STANDARD : Channel.ChannelType.TRAFFIC);
            channel.activateProcessingIncarnation(++incarnation);
            active.put(channel, usb);
            support.channelStarted(channel, source(usb), incarnation);
            return channel;
        }
        @Override public DiscoveredUSBTuner usbTuner(String identity) { return tuners.get(identity); }
        @Override public DiscoveredUSBTuner currentUsbTuner(Channel channel) { return active.get(channel); }
        @Override public List<TunerChannelAssignment> assignments(String identity)
        {
            List<TunerChannelAssignment> snapshot = active.entrySet().stream()
                .filter(entry -> entry.getValue().getId().equals(identity))
                .map(entry -> ChannelProcessingManager.tunerChannelAssignment(entry.getKey(), 155_100_000L,
                    entry.getKey().getProcessingIncarnation())).toList();
            Runnable hook = assignmentsSnapshotHook;
            assignmentsSnapshotHook = null;
            if(hook != null) hook.run();
            return snapshot;
        }
        @Override public boolean isProcessing(Channel channel) { return active.containsKey(channel); }
        @Override public boolean isStopping(Channel channel) { return false; }
        @Override public void stopInterrupted(TunerChannelAssignment assignment)
        {
            if(assignment.channel().getProcessingIncarnation() == assignment.processingIncarnation())
                active.remove(assignment.channel());
        }
        @Override public void startRecovered(Channel channel, DiscoveredUSBTuner tuner) throws ChannelException
        {
            attempts++;
            if(failStart) throw new ChannelException("injected startup failure");
            if(startEntered != null)
            {
                startEntered.countDown();
                try { if(!releaseStart.await(2, TimeUnit.SECONDS)) throw new AssertionError("start not released"); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); throw new AssertionError(exception); }
            }
            channel.activateProcessingIncarnation(++incarnation);
            active.put(channel, (FakeUsb)tuner);
            started.add(channel);
            support.channelStarted(channel, source((FakeUsb)tuner), incarnation);
        }
        @Override public boolean isClosed() { return false; }
    }

    private static class FakeUsb extends DiscoveredUSBTuner
    {
        private final String id;
        FakeUsb(String id) { super(TunerClass.TEST_TUNER, 1, id); this.id = id; }
        @Override public String getId() { return id; }
        @Override public boolean isAvailable() { return true; }
        @Override public boolean isEnabled() { return true; }
        @Override public OperatorState getOperatorState() { return OperatorState.LIVE; }
        @Override public void stop() { }
    }

    private static class FakeSource extends TunerChannelSource
    {
        FakeSource(String identity)
        {
            super(null, new TunerChannel(155_100_000L, 12_500), "recovery test", null);
            setTunerIdentity(identity);
        }
        @Override public void reset() { }
        @Override public void setFrequency(long frequency) { }
        @Override public void setFrequencyCorrection(long correction) { }
        @Override protected void setSampleRate(double sampleRate) { }
        @Override public void setListener(Listener<ComplexSamples> listener) { }
        @Override public double getSampleRate() { return 50_000; }
    }
}
