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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies broadcaster start cancellation without connecting to a remote streaming provider.
 */
public class BroadcastModelLifecycleTest
{
    @Test
    public void queuedStartDoesNotRunAfterImmediateDelete()
    {
        TestBroadcastModel model = new TestBroadcastModel();
        TestBroadcastConfiguration configuration = createConfiguration("Immediate delete");

        model.addBroadcastConfiguration(configuration);
        TestAudioBroadcaster broadcaster = model.getCreatedBroadcasters().getFirst();

        model.removeBroadcastConfiguration(configuration);
        model.runAllStarts();

        assertEquals(0, broadcaster.getStartCount());
        assertEquals(1, broadcaster.getStopCount());
        assertEquals(1, broadcaster.getDisposeCount());
        assertFalse(broadcaster.isActive());
        assertNull(model.getBroadcaster(configuration.getName()));
    }

    @Test
    public void startupThatRacesWithDeleteIsStoppedWhenStartupReturns() throws Exception
    {
        TestBroadcastModel model = new TestBroadcastModel();
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        model.blockNextBroadcasterStart(startEntered, releaseStart);
        TestBroadcastConfiguration configuration = createConfiguration("Racing delete");

        model.addBroadcastConfiguration(configuration);
        TestAudioBroadcaster broadcaster = model.getCreatedBroadcasters().getFirst();
        Thread startThread = new Thread(model.removeNextStart(), "broadcast-lifecycle-test-start");
        startThread.start();

        try
        {
            assertTrue(startEntered.await(2, TimeUnit.SECONDS));
            model.removeBroadcastConfiguration(configuration);
            assertNull(model.getBroadcaster(configuration.getName()));
        }
        finally
        {
            releaseStart.countDown();
            startThread.join(TimeUnit.SECONDS.toMillis(2));
        }

        assertFalse(startThread.isAlive());
        assertTrue(broadcaster.wasStartInterrupted());
        assertEquals(1, broadcaster.getStartCount());
        assertEquals(1, broadcaster.getStopCount());
        assertEquals(1, broadcaster.getDisposeCount());
        assertFalse(broadcaster.isActive());
    }

    @Test
    public void onlyNewestRapidReconfigurationCanCreateAndStartBroadcaster()
    {
        TestBroadcastModel model = new TestBroadcastModel();
        TestBroadcastConfiguration configuration = createConfiguration("Rapid reconfigure");

        model.addBroadcastConfiguration(configuration);
        model.runAllStarts();
        TestAudioBroadcaster originalBroadcaster = model.getCreatedBroadcasters().getFirst();
        assertTrue(originalBroadcaster.isActive());

        model.process(new BroadcastEvent(configuration, BroadcastEvent.Event.CONFIGURATION_CHANGE));
        model.process(new BroadcastEvent(configuration, BroadcastEvent.Event.CONFIGURATION_CHANGE));

        assertEquals(2, model.getPendingRestartCount());
        model.runAllRestarts();

        assertEquals(2, model.getCreatedBroadcasters().size(),
            "The older delayed restart must not create an intermediate broadcaster");
        TestAudioBroadcaster currentBroadcaster = model.getCreatedBroadcasters().getLast();
        model.runAllStarts();

        assertEquals(1, originalBroadcaster.getStartCount());
        assertEquals(1, originalBroadcaster.getStopCount());
        assertEquals(1, originalBroadcaster.getDisposeCount());
        assertFalse(originalBroadcaster.isActive());
        assertEquals(1, currentBroadcaster.getStartCount());
        assertTrue(currentBroadcaster.isActive());
        assertSame(currentBroadcaster, model.getBroadcaster(configuration.getName()));

        model.removeBroadcastConfiguration(configuration);
        assertFalse(currentBroadcaster.isActive());
    }

    @Test
    public void delayedCreationThatOverlapsDeleteCannotPublishOrLeakBroadcaster() throws Exception
    {
        TestBroadcastModel model = new TestBroadcastModel();
        TestBroadcastConfiguration configuration = createConfiguration("Delayed creation delete");

        model.addBroadcastConfiguration(configuration);
        model.runAllStarts();
        TestAudioBroadcaster originalBroadcaster = model.getCreatedBroadcasters().getFirst();
        assertTrue(originalBroadcaster.isActive());

        model.process(new BroadcastEvent(configuration, BroadcastEvent.Event.CONFIGURATION_CHANGE));
        assertFalse(originalBroadcaster.isActive());

        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch configurationRemoved = new CountDownLatch(1);
        model.blockNextBroadcasterFactory(factoryEntered, releaseFactory);
        model.getConfiguredBroadcasts().addListener(
            (ListChangeListener<ConfiguredBroadcast>)change -> configurationRemoved.countDown());

        Thread restartThread = new Thread(model.removeNextRestart(), "broadcast-delayed-create-test");
        restartThread.start();
        assertTrue(factoryEntered.await(2, TimeUnit.SECONDS));

        Thread deleteThread = new Thread(() -> model.removeBroadcastConfiguration(configuration),
            "broadcast-delayed-delete-test");
        deleteThread.start();

        try
        {
            /*
             * The fixed implementation blocks delete on the lifecycle lock before list removal.  Waiting until the
             * delete thread is actually blocked avoids a scheduler-timing false pass.  The old implementation first
             * removed the list row and then blocked while invalidating the generation, so the listener would already
             * have fired at this point.
             */
            assertTrue(awaitThreadState(deleteThread, Thread.State.BLOCKED, 2, TimeUnit.SECONDS));
            assertEquals(1L, configurationRemoved.getCount());
        }
        finally
        {
            releaseFactory.countDown();
            restartThread.join(TimeUnit.SECONDS.toMillis(2));
            deleteThread.join(TimeUnit.SECONDS.toMillis(2));
        }

        assertFalse(restartThread.isAlive());
        assertFalse(deleteThread.isAlive());
        assertEquals(2, model.getCreatedBroadcasters().size());
        TestAudioBroadcaster racedBroadcaster = model.getCreatedBroadcasters().getLast();
        model.runAllStarts();

        assertEquals(0, racedBroadcaster.getStartCount());
        assertEquals(1, racedBroadcaster.getStopCount());
        assertEquals(1, racedBroadcaster.getDisposeCount());
        assertFalse(racedBroadcaster.isActive());
        assertNull(model.getBroadcaster(configuration.getName()));
    }

    private static boolean awaitThreadState(Thread thread, Thread.State state, long timeout, TimeUnit timeUnit)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + timeUnit.toNanos(timeout);

        while(System.nanoTime() < deadline)
        {
            if(thread.getState() == state)
            {
                return true;
            }

            Thread.sleep(1);
        }

        return thread.getState() == state;
    }

    private static TestBroadcastConfiguration createConfiguration(String name)
    {
        TestBroadcastConfiguration configuration = new TestBroadcastConfiguration();
        configuration.setName(name);
        configuration.setEnabled(true);
        return configuration;
    }

    private static class TestBroadcastModel extends BroadcastModel
    {
        private final Deque<Runnable> mPendingStarts = new ArrayDeque<>();
        private final Deque<Runnable> mPendingRestarts = new ArrayDeque<>();
        private final List<TestAudioBroadcaster> mCreatedBroadcasters = new ArrayList<>();
        private CountDownLatch mNextStartEntered;
        private CountDownLatch mNextStartRelease;
        private CountDownLatch mNextFactoryEntered;
        private CountDownLatch mNextFactoryRelease;

        private TestBroadcastModel()
        {
            super(null, null, null, false);
        }

        @Override
        protected AbstractAudioBroadcaster<?> createAudioBroadcaster(BroadcastConfiguration broadcastConfiguration)
        {
            CountDownLatch factoryEntered = mNextFactoryEntered;
            CountDownLatch factoryRelease = mNextFactoryRelease;
            mNextFactoryEntered = null;
            mNextFactoryRelease = null;

            if(factoryEntered != null && factoryRelease != null)
            {
                factoryEntered.countDown();
                try
                {
                    assertTrue(factoryRelease.await(2, TimeUnit.SECONDS));
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("Broadcaster factory was interrupted", exception);
                }
            }

            TestAudioBroadcaster broadcaster = new TestAudioBroadcaster(
                (TestBroadcastConfiguration)broadcastConfiguration, mNextStartEntered, mNextStartRelease);
            mNextStartEntered = null;
            mNextStartRelease = null;
            mCreatedBroadcasters.add(broadcaster);
            return broadcaster;
        }

        @Override
        protected void executeBroadcasterStart(Runnable startTask)
        {
            mPendingStarts.addLast(startTask);
        }

        @Override
        protected void scheduleBroadcasterRestart(Runnable restartTask)
        {
            mPendingRestarts.addLast(restartTask);
        }

        private void blockNextBroadcasterStart(CountDownLatch startEntered, CountDownLatch releaseStart)
        {
            mNextStartEntered = startEntered;
            mNextStartRelease = releaseStart;
        }

        private void blockNextBroadcasterFactory(CountDownLatch factoryEntered, CountDownLatch releaseFactory)
        {
            mNextFactoryEntered = factoryEntered;
            mNextFactoryRelease = releaseFactory;
        }

        private List<TestAudioBroadcaster> getCreatedBroadcasters()
        {
            return mCreatedBroadcasters;
        }

        private Runnable removeNextStart()
        {
            return mPendingStarts.removeFirst();
        }

        private Runnable removeNextRestart()
        {
            return mPendingRestarts.removeFirst();
        }

        private int getPendingRestartCount()
        {
            return mPendingRestarts.size();
        }

        private void runAllStarts()
        {
            while(!mPendingStarts.isEmpty())
            {
                mPendingStarts.removeFirst().run();
            }
        }

        private void runAllRestarts()
        {
            while(!mPendingRestarts.isEmpty())
            {
                mPendingRestarts.removeFirst().run();
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
        private final CountDownLatch mStartEntered;
        private final CountDownLatch mReleaseStart;
        private final AtomicInteger mStartCount = new AtomicInteger();
        private final AtomicInteger mStopCount = new AtomicInteger();
        private final AtomicInteger mDisposeCount = new AtomicInteger();
        private final AtomicBoolean mActive = new AtomicBoolean();
        private final AtomicBoolean mStartInterrupted = new AtomicBoolean();

        private TestAudioBroadcaster(TestBroadcastConfiguration configuration, CountDownLatch startEntered,
                                     CountDownLatch releaseStart)
        {
            super(configuration);
            mStartEntered = startEntered;
            mReleaseStart = releaseStart;
        }

        @Override
        public void start()
        {
            mStartCount.incrementAndGet();

            if(mStartEntered != null && mReleaseStart != null)
            {
                mStartEntered.countDown();
                boolean released = false;

                while(!released)
                {
                    try
                    {
                        released = mReleaseStart.await(2, TimeUnit.SECONDS);
                    }
                    catch(InterruptedException e)
                    {
                        mStartInterrupted.set(true);
                    }
                }
            }

            mActive.set(true);
        }

        @Override
        public void stop()
        {
            mStopCount.incrementAndGet();
            mActive.set(false);
        }

        @Override
        public void dispose()
        {
            mDisposeCount.incrementAndGet();
            mActive.set(false);
        }

        @Override
        public void receive(AudioRecording audioRecording)
        {
            //No network or recording work is needed for lifecycle tests.
        }

        @Override
        public int getAudioQueueSize()
        {
            return 0;
        }

        private int getStartCount()
        {
            return mStartCount.get();
        }

        private int getStopCount()
        {
            return mStopCount.get();
        }

        private int getDisposeCount()
        {
            return mDisposeCount.get();
        }

        private boolean isActive()
        {
            return mActive.get();
        }

        private boolean wasStartInterrupted()
        {
            return mStartInterrupted.get();
        }
    }
}
