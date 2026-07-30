/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */
package io.github.dsheirer.audio.broadcast;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Verifies that worker-owned broadcaster state is handed off before it reaches JavaFX table-facing properties.
 */
public class ConfiguredBroadcastThreadingTest
{
    @Test
    public void workerStateRemainsSynchronousWhileTableStateWaitsForFxDispatcher() throws Exception
    {
        QueuedFxDispatcher fxDispatcher = new QueuedFxDispatcher();
        TestBroadcastConfiguration configuration = createConfiguration("Thread handoff");
        ConfiguredBroadcast configuredBroadcast = new ConfiguredBroadcast(configuration, fxDispatcher::dispatch);
        TestAudioBroadcaster audioBroadcaster = new TestAudioBroadcaster(configuration);
        configuredBroadcast.setAudioBroadcaster(audioBroadcaster);
        fxDispatcher.runAll();

        AtomicReference<Thread> tableUpdateThread = new AtomicReference<>();
        configuredBroadcast.broadcastStateProperty()
            .addListener((observable, oldValue, newValue) -> tableUpdateThread.set(Thread.currentThread()));

        Thread worker = new Thread(() -> audioBroadcaster.setBroadcastState(BroadcastState.MOUNT_POINT_IN_USE),
            "stream-state-worker");
        worker.start();
        worker.join();

        //Connection logic sees the new state immediately, while the table remains untouched until its dispatcher runs.
        assertEquals(BroadcastState.MOUNT_POINT_IN_USE, audioBroadcaster.getBroadcastState());
        assertEquals(BroadcastState.MOUNT_POINT_IN_USE, audioBroadcaster.getLastBadBroadcastState());
        assertEquals(BroadcastState.READY, configuredBroadcast.broadcastStateProperty().get());
        assertNull(configuredBroadcast.lastBadBroadcastStateProperty().get());
        assertEquals(1, fxDispatcher.size());

        Thread fxTestThread = Thread.currentThread();
        fxDispatcher.runAll();

        assertEquals(BroadcastState.MOUNT_POINT_IN_USE, configuredBroadcast.broadcastStateProperty().get());
        assertEquals(BroadcastState.MOUNT_POINT_IN_USE, configuredBroadcast.lastBadBroadcastStateProperty().get());
        assertSame(fxTestThread, tableUpdateThread.get());
    }

    @Test
    public void replacementIgnoresQueuedStateAndDetachesOldBroadcasterListener()
    {
        QueuedFxDispatcher fxDispatcher = new QueuedFxDispatcher();
        TestBroadcastConfiguration configuration = createConfiguration("Replacement");
        ConfiguredBroadcast configuredBroadcast = new ConfiguredBroadcast(configuration, fxDispatcher::dispatch);
        TestAudioBroadcaster oldBroadcaster = new TestAudioBroadcaster(configuration);
        TestAudioBroadcaster replacementBroadcaster = new TestAudioBroadcaster(configuration);
        configuredBroadcast.setAudioBroadcaster(oldBroadcaster);
        fxDispatcher.runAll();

        //Leave an old-source warning queued, then replace the source before the table can apply it.
        oldBroadcaster.setBroadcastState(BroadcastState.MOUNT_POINT_IN_USE);
        configuredBroadcast.setAudioBroadcaster(replacementBroadcaster);
        int pendingAfterReplacement = fxDispatcher.size();

        //A detached broadcaster can continue shutting down, but it must no longer enqueue table changes.
        oldBroadcaster.setBroadcastState(BroadcastState.ERROR);
        assertEquals(pendingAfterReplacement, fxDispatcher.size());

        replacementBroadcaster.setBroadcastState(BroadcastState.CONNECTED);
        fxDispatcher.runAll();

        assertSame(replacementBroadcaster, configuredBroadcast.getAudioBroadcaster());
        assertEquals(BroadcastState.CONNECTED, configuredBroadcast.broadcastStateProperty().get());
        assertNull(configuredBroadcast.lastBadBroadcastStateProperty().get());
    }

    @Test
    public void detachedBroadcasterCannotOverwriteConfigurationState()
    {
        QueuedFxDispatcher fxDispatcher = new QueuedFxDispatcher();
        TestBroadcastConfiguration configuration = createConfiguration("Detach");
        ConfiguredBroadcast configuredBroadcast = new ConfiguredBroadcast(configuration, fxDispatcher::dispatch);
        TestAudioBroadcaster audioBroadcaster = new TestAudioBroadcaster(configuration);
        configuredBroadcast.setAudioBroadcaster(audioBroadcaster);
        fxDispatcher.runAll();

        audioBroadcaster.setBroadcastState(BroadcastState.MOUNT_POINT_IN_USE);
        fxDispatcher.runAll();
        assertEquals(BroadcastState.MOUNT_POINT_IN_USE, configuredBroadcast.broadcastStateProperty().get());

        configuredBroadcast.setAudioBroadcaster(null);
        int pendingAfterDetach = fxDispatcher.size();
        audioBroadcaster.setBroadcastState(BroadcastState.ERROR);
        assertEquals(pendingAfterDetach, fxDispatcher.size());
        fxDispatcher.runAll();

        assertNull(configuredBroadcast.getAudioBroadcaster());
        assertEquals(BroadcastState.READY, configuredBroadcast.broadcastStateProperty().get());
        assertNull(configuredBroadcast.lastBadBroadcastStateProperty().get());
    }

    private static TestBroadcastConfiguration createConfiguration(String name)
    {
        TestBroadcastConfiguration configuration = new TestBroadcastConfiguration();
        configuration.setName(name);
        configuration.validProperty().set(true);
        return configuration;
    }

    private static class QueuedFxDispatcher
    {
        private final Queue<Runnable> mPending = new ConcurrentLinkedQueue<>();

        private void dispatch(Runnable runnable)
        {
            mPending.add(runnable);
        }

        private int size()
        {
            return mPending.size();
        }

        private void runAll()
        {
            Runnable runnable;

            while((runnable = mPending.poll()) != null)
            {
                runnable.run();
            }
        }
    }

    private static class TestBroadcastConfiguration extends BroadcastConfiguration
    {
        @Override
        public BroadcastConfiguration copyOf()
        {
            TestBroadcastConfiguration copy = new TestBroadcastConfiguration();
            copy.setName(getName());
            copy.setEnabled(isEnabled());
            copy.validProperty().set(isValid());
            return copy;
        }

        @Override
        public BroadcastServerType getBroadcastServerType()
        {
            return BroadcastServerType.UNKNOWN;
        }
    }

    private static class TestAudioBroadcaster extends AbstractAudioBroadcaster<TestBroadcastConfiguration>
    {
        private TestAudioBroadcaster(TestBroadcastConfiguration configuration)
        {
            super(configuration);
        }

        @Override
        public void start()
        {
        }

        @Override
        public void stop()
        {
        }

        @Override
        public void dispose()
        {
        }

        @Override
        public void receive(AudioRecording audioRecording)
        {
        }

        @Override
        public int getAudioQueueSize()
        {
            return 0;
        }
    }
}
