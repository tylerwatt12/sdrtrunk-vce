/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.audio.broadcast;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.broadcast.shoutcast.v1.ShoutcastV1Configuration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Sender state is receiver-owned; it does not wait for a desktop event queue. */
class ConfiguredBroadcastThreadingTest
{
    @Test
    void workerPublishesOneCoherentStateAndErrorPairWithoutDesktopDispatch() throws Exception
    {
        var configuration = configuration();
        var configured = new ConfiguredBroadcast(configuration);
        var broadcaster = new TestAudioBroadcaster(configuration);
        configured.setAudioBroadcaster(broadcaster);
        AtomicReference<Thread> callbackThread = new AtomicReference<>();
        AtomicReference<AbstractAudioBroadcaster.StateSnapshot> observed = new AtomicReference<>();
        configured.addChangeListener(() -> {
            callbackThread.set(Thread.currentThread());
            observed.set(configured.getStateSnapshot());
        });
        Thread worker = new Thread(() -> broadcaster.setBroadcastState(BroadcastState.MOUNT_POINT_IN_USE));
        worker.start();
        worker.join(2_000);
        assertTrue(!worker.isAlive());
        assertSame(worker, callbackThread.get());
        assertEquals(new AbstractAudioBroadcaster.StateSnapshot(BroadcastState.MOUNT_POINT_IN_USE,
            BroadcastState.MOUNT_POINT_IN_USE), observed.get());
        broadcaster.setBroadcastState(BroadcastState.CONNECTED);
        assertEquals(BroadcastState.CONNECTED, configured.getBroadcastState());
        assertNull(configured.getLastBadBroadcastState());
    }

    @Test
    void sourceReplacementRejectsAnAlreadyInFlightOldSourceCallback() throws Exception
    {
        var configuration = configuration();
        var configured = new ConfiguredBroadcast(configuration);
        var old = new TestAudioBroadcaster(configuration);
        var replacement = new TestAudioBroadcaster(configuration);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        old.addStateChangeListener(() -> {
            callbackEntered.countDown();
            try { assertTrue(releaseCallback.await(2, TimeUnit.SECONDS)); }
            catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
        });
        configured.setAudioBroadcaster(old);
        AtomicInteger changes = new AtomicInteger();
        configured.addChangeListener(changes::incrementAndGet);
        Thread worker = new Thread(() -> old.setBroadcastState(BroadcastState.ERROR));
        worker.start();
        try
        {
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
            configured.setAudioBroadcaster(replacement);
        }
        finally
        {
            releaseCallback.countDown();
            worker.join(2_000);
        }
        assertTrue(!worker.isAlive());
        assertEquals(1, changes.get());
        assertSame(replacement, configured.getAudioBroadcaster());
        assertEquals(BroadcastState.READY, configured.getBroadcastState());
        assertNull(configured.getLastBadBroadcastState());
    }

    @Test
    void detachedSourceCannotChangeConfigurationStateAndValidityRemainsLive()
    {
        var configuration = configuration();
        var configured = new ConfiguredBroadcast(configuration);
        var broadcaster = new TestAudioBroadcaster(configuration);
        configured.setAudioBroadcaster(broadcaster);
        broadcaster.setBroadcastState(BroadcastState.MOUNT_POINT_IN_USE);
        configured.setAudioBroadcaster(null);
        AtomicInteger changes = new AtomicInteger();
        configured.addChangeListener(changes::incrementAndGet);
        broadcaster.setBroadcastState(BroadcastState.ERROR);
        assertEquals(0, changes.get());
        assertEquals(BroadcastState.READY, configured.getBroadcastState());
        assertNull(configured.getLastBadBroadcastState());
        configuration.setPort(0);
        assertEquals(1, changes.get());
        assertEquals(BroadcastState.CONFIGURATION_ERROR, configured.getBroadcastState());
        configuration.setPort(80);
        assertEquals(BroadcastState.READY, configured.getBroadcastState());
    }

    private static ShoutcastV1Configuration configuration()
    {
        var configuration = new ShoutcastV1Configuration();
        configuration.setName("Fixture");
        configuration.setHost("example.invalid");
        return configuration;
    }

    private static class TestAudioBroadcaster extends AbstractAudioBroadcaster<BroadcastConfiguration>
    {
        TestAudioBroadcaster(BroadcastConfiguration configuration) { super(configuration); }
        @Override public void start() {}
        @Override public void stop() {}
        @Override public void dispose() {}
        @Override public void receive(AudioRecording recording) {}
        @Override public int getAudioQueueSize() { return 0; }
    }
}
