/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */

package io.github.dsheirer.source.tuner.usb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.buffer.INativeBuffer;
import io.github.dsheirer.buffer.INativeBufferFactory;
import io.github.dsheirer.sample.Broadcaster;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.sample.complex.InterleavedComplexSamples;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class UsbNativeBufferIngressTest
{
    @Test
    void saturatedQueueNeverBlocksProducerAndAccountsDroppedSamples() throws Exception
    {
        CountDownLatch listenerStarted = new CountDownLatch(1);
        CountDownLatch releaseListener = new CountDownLatch(1);
        CountDownLatch allAcceptedProcessed = new CountDownLatch(3);
        AtomicReference<Thread> listenerThread = new AtomicReference<>();
        Thread producerThread = Thread.currentThread();
        UsbNativeBufferIngress ingress = ingress("USB ingress saturation test", 2, buffer ->
        {
            listenerThread.compareAndSet(null, Thread.currentThread());
            listenerStarted.countDown();

            if(((TestNativeBuffer)buffer).id() == 1)
            {
                await(releaseListener);
            }

            allAcceptedProcessed.countDown();
        });

        try
        {
            long generation = ingress.start(4, 100, factory());
            assertTrue(offer(ingress, 1, generation));
            assertTrue(listenerStarted.await(1, TimeUnit.SECONDS));

            org.junit.jupiter.api.Assertions.assertTimeout(Duration.ofMillis(250), () ->
            {
                assertTrue(offer(ingress, 2, generation));
                assertTrue(offer(ingress, 3, generation));
                assertFalse(offer(ingress, 4, generation));
            });

            UsbNativeBufferIngress.Snapshot saturated = ingress.snapshot();
            assertEquals(1, saturated.saturationDroppedBuffers());
            assertEquals(100, saturated.saturationDroppedSamples());
            assertEquals(2, saturated.depth());
            assertEquals(2, saturated.highWaterDepth());
            assertNotEquals(producerThread, listenerThread.get());

            releaseListener.countDown();
            assertTrue(allAcceptedProcessed.await(1, TimeUnit.SECONDS));
            assertEquals(0, ingress.snapshot().depth());
        }
        finally
        {
            releaseListener.countDown();
            ingress.close();
            assertTrue(ingress.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void restartDiscardsQueuedAndLateOffersFromOlderGeneration() throws Exception
    {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch replacementProcessed = new CountDownLatch(1);
        List<Integer> received = new CopyOnWriteArrayList<>();
        UsbNativeBufferIngress ingress = ingress("USB ingress lifecycle test", 4, buffer ->
        {
            int id = ((TestNativeBuffer)buffer).id();
            received.add(id);

            if(id == 1)
            {
                firstStarted.countDown();
                await(releaseFirst);
            }
            else if(id == 3)
            {
                replacementProcessed.countDown();
            }
        });

        try
        {
            long firstGeneration = ingress.start(4, 100, factory());
            assertTrue(offer(ingress, 1, firstGeneration));
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            assertTrue(offer(ingress, 2, firstGeneration));

            ingress.stop();
            long secondGeneration = ingress.start(4, 100, factory());
            assertTrue(secondGeneration > firstGeneration);
            assertFalse(offer(ingress, 99, firstGeneration));
            assertTrue(offer(ingress, 3, secondGeneration));
            releaseFirst.countDown();

            assertTrue(replacementProcessed.await(1, TimeUnit.SECONDS));
            assertEquals(List.of(1, 3), received);
            assertEquals(secondGeneration, ingress.activeGeneration());
        }
        finally
        {
            releaseFirst.countDown();
            ingress.close();
            assertTrue(ingress.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void conversionOrListenerFailureDoesNotKillWorkerOrBlockHealthyListeners() throws Exception
    {
        CountDownLatch healthyReceivedFirst = new CountDownLatch(1);
        CountDownLatch healthyReceivedSecond = new CountDownLatch(1);
        Broadcaster<INativeBuffer> broadcaster = new Broadcaster<>();
        broadcaster.addListener(buffer ->
        {
            if(((TestNativeBuffer)buffer).id() == 1)
            {
                throw new AssertionError("injected listener failure");
            }
        });
        broadcaster.addListener(buffer ->
        {
            if(((TestNativeBuffer)buffer).id() == 1)
            {
                healthyReceivedFirst.countDown();
            }
            else if(((TestNativeBuffer)buffer).id() == 2)
            {
                healthyReceivedSecond.countDown();
            }
        });
        UsbNativeBufferIngress ingress = new UsbNativeBufferIngress("USB ingress failure containment test", 4,
            broadcaster);
        INativeBufferFactory factory = new INativeBufferFactory()
        {
            @Override
            public INativeBuffer getBuffer(ByteBuffer samples, long timestamp)
            {
                int id = Byte.toUnsignedInt(samples.get(0));

                if(id == 3)
                {
                    throw new AssertionError("injected conversion failure");
                }

                return new TestNativeBuffer(id, 100);
            }

            @Override
            public void setSamplesPerMillisecond(float samplesPerMillisecond)
            {
            }
        };

        try
        {
            long generation = ingress.start(4, 100, factory);
            assertTrue(offer(ingress, 1, generation));
            assertTrue(healthyReceivedFirst.await(1, TimeUnit.SECONDS));
            assertTrue(offer(ingress, 3, generation));
            assertTrue(offer(ingress, 2, generation));
            assertTrue(healthyReceivedSecond.await(1, TimeUnit.SECONDS));
            UsbNativeBufferIngress.Snapshot snapshot = ingress.snapshot();
            assertEquals(1, snapshot.listenerFailures());
            assertEquals(1, snapshot.conversionFailures());
            assertEquals(0, snapshot.saturationDroppedBuffers());
        }
        finally
        {
            ingress.close();
            assertTrue(ingress.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static UsbNativeBufferIngress ingress(String name, int capacity, Listener<INativeBuffer> listener)
    {
        Broadcaster<INativeBuffer> broadcaster = new Broadcaster<>();
        broadcaster.addListener(listener);
        return new UsbNativeBufferIngress(name, capacity, broadcaster);
    }

    private static boolean offer(UsbNativeBufferIngress ingress, int id, long generation)
    {
        ByteBuffer samples = ByteBuffer.allocate(4);
        samples.put(0, (byte)id);
        UsbNativeBufferIngress.TransferSamples copy = ingress.copy(samples, samples.capacity(), 0, generation);
        return copy != null && ingress.offer(copy);
    }

    private static INativeBufferFactory factory()
    {
        return new INativeBufferFactory()
        {
            @Override
            public INativeBuffer getBuffer(ByteBuffer samples, long timestamp)
            {
                return new TestNativeBuffer(Byte.toUnsignedInt(samples.get(0)), 100);
            }

            @Override
            public void setSamplesPerMillisecond(float samplesPerMillisecond)
            {
            }
        };
    }

    private static void await(CountDownLatch latch)
    {
        try
        {
            latch.await();
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Test interrupted", exception);
        }
    }

    private record TestNativeBuffer(int id, int sampleCount) implements INativeBuffer
    {
        @Override
        public Iterator<ComplexSamples> iterator()
        {
            return Collections.emptyIterator();
        }

        @Override
        public Iterator<InterleavedComplexSamples> iteratorInterleaved()
        {
            return Collections.emptyIterator();
        }

        @Override
        public long getTimestamp()
        {
            return 0;
        }
    }
}
