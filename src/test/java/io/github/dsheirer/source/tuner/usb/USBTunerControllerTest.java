/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.usb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.buffer.INativeBufferFactory;
import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.source.SourceException;
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

class USBTunerControllerTest
{
    @Test
    void removeLastAndAddFirstKeepsTheExistingStreamAlive() throws Exception
    {
        TestController controller = new TestController();
        setRunning(controller, true);
        streaming(controller).set(true);
        streamingShutdownComplete(controller).set(false);
        Listener<INativeBuffer> first = _ -> {};
        Listener<INativeBuffer> replacement = _ -> {};
        controller.addBufferListener(first);
        ReentrantLock lifecycleLock = streamingLifecycleLock(controller);
        lifecycleLock.lock();

        try
        {
            Thread remover = Thread.ofPlatform().start(() -> controller.removeBufferListener(first));
            awaitListenerState(controller, false);
            Thread adder = Thread.ofPlatform().start(() -> controller.addBufferListener(replacement));
            awaitListenerState(controller, true);
            lifecycleLock.unlock();
            remover.join(2_000);
            adder.join(2_000);

            assertFalse(remover.isAlive());
            assertFalse(adder.isAlive());
            assertTrue(controller.hasBufferListeners());
            assertTrue(streaming(controller).get(), "the live transfer loop must not be drained between listeners");
            assertFalse(streamingShutdownComplete(controller).get());
        }
        finally
        {
            if(lifecycleLock.isHeldByCurrentThread())
            {
                lifecycleLock.unlock();
            }
        }
    }

    @Test
    void addFirstAndRemoveLastDoesNotStartAnUnwantedStream() throws Exception
    {
        TestController controller = new TestController();
        setRunning(controller, true);
        streaming(controller).set(false);
        streamingShutdownComplete(controller).set(true);
        Listener<INativeBuffer> listener = _ -> {};
        ReentrantLock lifecycleLock = streamingLifecycleLock(controller);
        lifecycleLock.lock();

        try
        {
            Thread adder = Thread.ofPlatform().start(() -> controller.addBufferListener(listener));
            awaitListenerState(controller, true);
            Thread remover = Thread.ofPlatform().start(() -> controller.removeBufferListener(listener));
            awaitListenerState(controller, false);
            lifecycleLock.unlock();
            adder.join(2_000);
            remover.join(2_000);

            assertFalse(adder.isAlive());
            assertFalse(remover.isAlive());
            assertFalse(controller.hasBufferListeners());
            assertFalse(streaming(controller).get());
            assertTrue(streamingShutdownComplete(controller).get());
        }
        finally
        {
            if(lifecycleLock.isHeldByCurrentThread())
            {
                lifecycleLock.unlock();
            }
        }
    }

    @Test
    void listenerCannotRegisterAfterFullShutdownIsPublished() throws Exception
    {
        TestController controller = new TestController();
        setRunning(controller, false);
        controller.addBufferListener(_ -> {});
        assertFalse(controller.hasBufferListeners());
        assertFalse(streaming(controller).get());
    }

    @Test
    void eventProcessorDoesNotRestartWhenStoppedBeforeRunBegins() throws Exception
    {
        TestController controller = new TestController();
        USBTunerController.UsbEventProcessor processor = controller.new UsbEventProcessor();
        controller.processor.set(processor);
        processor.start();
        assertTrue(controller.eventThreadReady.await(1, TimeUnit.SECONDS));

        AtomicBoolean stopped = new AtomicBoolean();
        Thread stopper = new Thread(() -> stopped.set(processor.stop()));
        stopper.start();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);

        while(processor.isProcessing() && System.nanoTime() < deadline)
        {
            Thread.onSpinWait();
        }

        boolean stopWasPublishedBeforeRun = !processor.isProcessing();

        //The thread is alive but has not entered UsbEventProcessor.run() yet.  Releasing it recreates the exact
        //start/stop scheduling race that previously allowed run() to turn processing back on.
        controller.allowEventLoop.countDown();
        stopper.join(2_000);

        assertFalse(stopper.isAlive());
        assertTrue(stopWasPublishedBeforeRun);
        assertTrue(stopped.get());
        assertFalse(processor.isProcessing());
        assertEquals(0, controller.eventCalls.get(),
            "a stop requested before run begins must not process any LibUsb events");
    }

    private static void awaitListenerState(TestController controller, boolean expected) throws InterruptedException
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);

        while(controller.hasBufferListeners() != expected && System.nanoTime() < deadline)
        {
            Thread.sleep(1);
        }

        assertEquals(expected, controller.hasBufferListeners());
    }

    private static void setRunning(TestController controller, boolean running) throws Exception
    {
        Field field = USBTunerController.class.getDeclaredField("mRunning");
        field.setAccessible(true);
        field.setBoolean(controller, running);
    }

    private static AtomicBoolean streaming(TestController controller) throws Exception
    {
        Field field = USBTunerController.class.getDeclaredField("mStreaming");
        field.setAccessible(true);
        return (AtomicBoolean)field.get(controller);
    }

    private static AtomicBoolean streamingShutdownComplete(TestController controller) throws Exception
    {
        Field field = USBTunerController.class.getDeclaredField("mStreamingShutdownComplete");
        field.setAccessible(true);
        return (AtomicBoolean)field.get(controller);
    }

    private static ReentrantLock streamingLifecycleLock(TestController controller) throws Exception
    {
        Field field = USBTunerController.class.getDeclaredField("mStreamingLifecycleLock");
        field.setAccessible(true);
        return (ReentrantLock)field.get(controller);
    }

    private static class TestController extends USBTunerController
    {
        private final CountDownLatch eventThreadReady = new CountDownLatch(1);
        private final CountDownLatch allowEventLoop = new CountDownLatch(1);
        private final AtomicInteger eventCalls = new AtomicInteger();
        private final AtomicReference<UsbEventProcessor> processor = new AtomicReference<>();

        private TestController()
        {
            super(0, "test", new LoggingTunerErrorListener());
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
            return 1_024;
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
            return new Thread(() ->
            {
                eventThreadReady.countDown();

                try
                {
                    allowEventLoop.await();
                }
                catch(InterruptedException ie)
                {
                    Thread.currentThread().interrupt();
                    return;
                }

                runnable.run();
            });
        }

        @Override
        protected void handleUsbEvents(long timeoutMilliseconds)
        {
            eventCalls.incrementAndGet();

            //Keep the test self-cleaning if the old race is reintroduced: the first unexpected event clears the loop.
            UsbEventProcessor eventProcessor = processor.get();

            if(eventProcessor != null)
            {
                eventProcessor.stop();
            }
        }
    }
}
