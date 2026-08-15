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
package io.github.dsheirer.source.tuner.usb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.buffer.INativeBufferFactory;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.SourceException;
import io.github.dsheirer.source.tuner.ITunerErrorListener;
import io.github.dsheirer.source.tuner.LoggingTunerErrorListener;
import io.github.dsheirer.source.tuner.TunerType;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;

class USBTunerControllerLifecycleTest
{
    @Test
    void prepareFailureRollsBackBeforeAsynchronousErrorNotification() throws Exception
    {
        MonitorCheckingErrorListener errorListener = new MonitorCheckingErrorListener();
        TestController controller = new TestController(errorListener);
        errorListener.mController.set(controller);
        controller.mFailPrepare = true;
        setRunning(controller, true);

        controller.addBufferListener(buffer -> {});

        assertTrue(errorListener.mNotified.await(2, TimeUnit.SECONDS));
        assertFalse(errorListener.mMonitorWaitTimedOut.get(),
                "error notification must run after the streaming lifecycle monitor is released");
        assertEquals(0, controller.mTransferBufferSizeRequests.get(),
                "a prepare failure must not allocate or submit transfers");
        assertEquals(1, controller.mStreamingCleanupCalls.get());
        assertFalse(streaming(controller).get());
    }

    @Test
    void eventProcessorDoesNotRunWhenStoppedBeforeThreadBegins() throws Exception
    {
        TestController controller = new TestController();
        USBTunerController.UsbEventProcessor processor = controller.new UsbEventProcessor();
        controller.mProcessor.set(processor);
        processor.start();
        assertTrue(controller.mCreatedEventThread.get().isDaemon());
        boolean eventThreadReady = controller.mEventThreadReady.await(1, TimeUnit.SECONDS);

        if(!eventThreadReady)
        {
            controller.mAllowEventLoop.countDown();
        }

        assertTrue(eventThreadReady);

        AtomicBoolean stopped = new AtomicBoolean();
        Thread stopper = Thread.ofPlatform().start(() -> stopped.set(processor.stop()));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);

        while(processor.isProcessing() && System.nanoTime() < deadline)
        {
            Thread.onSpinWait();
        }

        boolean stopPublishedBeforeRun = !processor.isProcessing();
        controller.mAllowEventLoop.countDown();
        stopper.join(2_000);

        assertFalse(stopper.isAlive());
        assertTrue(stopPublishedBeforeRun, "stop must publish before the delayed event thread is released");
        assertTrue(stopped.get());
        assertFalse(processor.isProcessing());
        assertEquals(0, controller.mEventCalls.get());
    }

    @Test
    void listenerWaitingForControllerLockRechecksShutdownGate() throws Exception
    {
        TestController controller = new TestController();
        setRunning(controller, true);
        Listener<INativeBuffer> listener = buffer -> {};
        ReentrantLock lock = controller.getLock();
        lock.lock();

        try
        {
            Thread adder = Thread.ofPlatform().start(() -> controller.addBufferListener(listener));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);

            while(adder.getState() != Thread.State.WAITING && System.nanoTime() < deadline)
            {
                Thread.onSpinWait();
            }

            boolean waitingForLock = adder.getState() == Thread.State.WAITING;
            stopping(controller).set(true);
            lock.unlock();
            adder.join(2_000);

            assertTrue(waitingForLock, "listener must reach the controller lock before shutdown is published");
            assertFalse(adder.isAlive());
            assertFalse(controller.hasBufferListeners());
        }
        finally
        {
            if(lock.isHeldByCurrentThread())
            {
                lock.unlock();
            }
        }
    }

    @Test
    void handedOffStreamStopPreservesNewListener() throws Exception
    {
        TestController controller = new TestController();
        setRunning(controller, true);
        streaming(controller).set(true);
        Listener<INativeBuffer> listener = buffer -> {};
        ReentrantLock lock = controller.getLock();
        lock.lock();

        try
        {
            Thread stopWorker = Thread.ofPlatform().start(controller::stopStreamingIfNoListeners);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);

            while(stopWorker.getState() != Thread.State.WAITING && System.nanoTime() < deadline)
            {
                Thread.onSpinWait();
            }

            boolean waitingForLock = stopWorker.getState() == Thread.State.WAITING;
            controller.addBufferListener(listener);
            lock.unlock();
            stopWorker.join(2_000);

            assertTrue(waitingForLock, "stream-stop worker must be queued behind the listener lifecycle lock");
            assertFalse(stopWorker.isAlive());
            assertTrue(controller.hasBufferListeners());
            assertTrue(streaming(controller).get(), "handoff must not stop a stream needed by the new listener");
        }
        finally
        {
            if(lock.isHeldByCurrentThread())
            {
                lock.unlock();
            }
        }
    }

    private static void setRunning(TestController controller, boolean running) throws Exception
    {
        Field field = USBTunerController.class.getDeclaredField("mRunning");
        field.setAccessible(true);
        field.setBoolean(controller, running);
    }

    private static AtomicBoolean stopping(TestController controller) throws Exception
    {
        Field field = USBTunerController.class.getDeclaredField("mStopping");
        field.setAccessible(true);
        return (AtomicBoolean)field.get(controller);
    }

    private static AtomicBoolean streaming(TestController controller) throws Exception
    {
        Field field = USBTunerController.class.getDeclaredField("mStreaming");
        field.setAccessible(true);
        return (AtomicBoolean)field.get(controller);
    }

    private static class MonitorCheckingErrorListener implements ITunerErrorListener
    {
        private final AtomicReference<TestController> mController = new AtomicReference<>();
        private final AtomicBoolean mMonitorWaitTimedOut = new AtomicBoolean();
        private final CountDownLatch mNotified = new CountDownLatch(1);

        @Override
        public void setErrorMessage(String errorMessage)
        {
            Thread monitorChecker = Thread.ofPlatform().start(() -> {
                synchronized(mController.get())
                {
                    //Acquiring this monitor proves start rollback has exited its serialized section.
                }
            });

            try
            {
                monitorChecker.join(1_000);
                mMonitorWaitTimedOut.set(monitorChecker.isAlive());
            }
            catch(InterruptedException ie)
            {
                Thread.currentThread().interrupt();
                mMonitorWaitTimedOut.set(true);
            }
            finally
            {
                mNotified.countDown();
            }
        }

        @Override
        public void tunerRemoved()
        {
        }
    }

    private static class TestController extends USBTunerController
    {
        private final CountDownLatch mEventThreadReady = new CountDownLatch(1);
        private final CountDownLatch mAllowEventLoop = new CountDownLatch(1);
        private final AtomicInteger mEventCalls = new AtomicInteger();
        private final AtomicReference<UsbEventProcessor> mProcessor = new AtomicReference<>();
        private final AtomicReference<Thread> mCreatedEventThread = new AtomicReference<>();
        private final AtomicInteger mTransferBufferSizeRequests = new AtomicInteger();
        private final AtomicInteger mStreamingCleanupCalls = new AtomicInteger();
        private volatile boolean mFailPrepare;

        private TestController()
        {
            this(new LoggingTunerErrorListener());
        }

        private TestController(ITunerErrorListener tunerErrorListener)
        {
            super(0, "test", tunerErrorListener);
        }

        @Override
        public TunerType getTunerType()
        {
            return TunerType.TEST;
        }

        @Override
        protected INativeBufferFactory getNativeBufferFactory()
        {
            return null;
        }

        @Override
        protected int getTransferBufferSize()
        {
            mTransferBufferSizeRequests.incrementAndGet();
            return 1_024;
        }

        @Override
        protected void prepareStreaming() throws SourceException
        {
            if(mFailPrepare)
            {
                throw new SourceException("test prepare failure");
            }
        }

        @Override
        protected void streamingCleanup()
        {
            mStreamingCleanupCalls.incrementAndGet();
        }

        @Override
        protected void deviceStart() throws SourceException
        {
        }

        @Override
        protected void deviceStop()
        {
        }

        @Override
        public int getBufferSampleCount()
        {
            return 1;
        }

        @Override
        public double getCurrentSampleRate()
        {
            return 1.0;
        }

        @Override
        public long getTunedFrequency()
        {
            return 0;
        }

        @Override
        public void setTunedFrequency(long frequency)
        {
        }

        @Override
        protected Thread createUsbEventThread(Runnable runnable)
        {
            Thread thread = new Thread(() -> {
                mEventThreadReady.countDown();

                try
                {
                    mAllowEventLoop.await();
                }
                catch(InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    return;
                }

                runnable.run();
            });
            mCreatedEventThread.set(thread);
            return thread;
        }

        @Override
        protected void handleUsbEvents(long timeoutMilliseconds)
        {
            mEventCalls.incrementAndGet();
            UsbEventProcessor processor = mProcessor.get();

            if(processor != null)
            {
                processor.stop();
            }
        }

        @Override
        protected void interruptUsbEventHandler()
        {
            //No native context is used by this lifecycle test.
        }
    }
}
