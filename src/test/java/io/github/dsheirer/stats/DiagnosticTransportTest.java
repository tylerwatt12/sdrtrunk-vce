/*
 * ****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.buffer.FloatNativeBuffer;
import io.github.dsheirer.spectrum.DFTSize;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class DiagnosticTransportTest
{
    @Test
    void createsAndRemovesTheOnlyWorkerWithDemand() throws Exception
    {
        DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
        CountDownLatch ran = new CountDownLatch(1);

        assertFalse(scheduler.hasWorker());
        assertEquals(0, scheduler.activeTaskCount());

        DiagnosticFftScheduler.Task task = scheduler.scheduleWithFixedDelay(ran::countDown, 20);
        assertTrue(scheduler.hasWorker());
        assertEquals(1, scheduler.activeTaskCount());
        assertTrue(ran.await(1, TimeUnit.SECONDS));

        task.close();
        assertFalse(scheduler.hasWorker());
        assertEquals(0, scheduler.activeTaskCount());
        scheduler.close();
    }

    @Test
    void publishesFreshSamplesWithoutTheOldOneFrameDelay() throws Exception
    {
        DiagnosticFftScheduler scheduler = new DiagnosticFftScheduler();
        CountDownLatch published = new CountDownLatch(1);
        AtomicLong observedAt = new AtomicLong();
        AtomicLong completedAt = new AtomicLong();
        DemandDftProcessor processor = new DemandDftProcessor(scheduler, DFTSize.FFT00512, 20,
            (timestamp, values) ->
            {
                observedAt.set(timestamp);
                completedAt.set(System.currentTimeMillis());
                assertEquals(DFTSize.FFT00512.getSize(), values.length);
                published.countDown();
            });

        try
        {
            float[] samples = new float[4_096];
            long before = System.currentTimeMillis();
            processor.receive(new FloatNativeBuffer(samples, 1, 1));
            processor.receive(new FloatNativeBuffer(samples, 1, 1));

            assertTrue(published.await(2, TimeUnit.SECONDS));
            assertTrue(observedAt.get() >= before);
            assertTrue(completedAt.get() - observedAt.get() < 500,
                "A fresh diagnostic FFT should stay within the browser latency budget before transport");
        }
        finally
        {
            processor.close();
            scheduler.close();
        }

        assertFalse(scheduler.hasWorker());
    }

    @Test
    void encodesTheFixedLittleEndianWireHeaderAndSanitizesValues()
    {
        DiagnosticStreamFrame frame = DiagnosticStreamFrame.float32(
            DiagnosticStreamFrame.TYPE_TUNER_FFT, 7, 9, 100, 773_106_250L, 10_000_000L, 4_096,
            new float[]{Float.NaN, -250.0f, 30.0f, -42.5f});
        ByteBuffer encoded = ByteBuffer.wrap(frame.encoded()).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(DiagnosticStreamFrame.MAGIC, encoded.getInt());
        assertEquals(DiagnosticStreamFrame.VERSION, Byte.toUnsignedInt(encoded.get()));
        assertEquals(DiagnosticStreamFrame.TYPE_TUNER_FFT, Byte.toUnsignedInt(encoded.get()));
        assertEquals(DiagnosticStreamFrame.HEADER_BYTES, Short.toUnsignedInt(encoded.getShort()));
        assertEquals(4 * Float.BYTES, encoded.getInt());
        assertEquals(4, encoded.getInt());
        assertEquals(7, encoded.getLong());
        assertEquals(9, encoded.getLong());
        assertEquals(100, encoded.getLong());
        assertTrue(encoded.getLong() >= 100);
        assertEquals(773_106_250L, encoded.getLong());
        assertEquals(10_000_000, encoded.getInt());
        assertEquals(4_096, encoded.getInt());
        assertEquals(0, encoded.getInt());
        assertEquals(4_096, encoded.getInt());
        assertEquals(-196.0f, encoded.getFloat());
        assertEquals(-196.0f, encoded.getFloat());
        assertEquals(20.0f, encoded.getFloat());
        assertEquals(-42.5f, encoded.getFloat());
    }

    @Test
    void encodesViewportMetadataAtTheExtendedHeaderOffsets()
    {
        DiagnosticStreamFrame frame = DiagnosticStreamFrame.float32(
            DiagnosticStreamFrame.TYPE_TUNER_FFT, 3, 4, 100, 100_000_000L, 8_192_000L, 32_768,
            14_336, 4_096, new float[4_096]);
        ByteBuffer encoded = ByteBuffer.wrap(frame.encoded()).order(ByteOrder.LITTLE_ENDIAN);

        assertEquals(72, Short.toUnsignedInt(encoded.getShort(6)));
        assertEquals(32_768, encoded.getInt(60));
        assertEquals(14_336, encoded.getInt(64));
        assertEquals(4_096, encoded.getInt(68));
        assertEquals(4_096 * Float.BYTES, encoded.getInt(8));
        assertEquals(4_096, encoded.getInt(12));
    }

    @Test
    void retainsOnlyTheLatestFramePerTypeAndWakesPollersOnClose() throws Exception
    {
        DiagnosticFrameQueue queue = new DiagnosticFrameQueue();
        DiagnosticStreamFrame first = signal(1);
        DiagnosticStreamFrame latest = signal(2);

        queue.offer(first);
        queue.offer(latest);

        assertSame(latest, queue.poll(Duration.ZERO));
        assertNull(queue.poll(Duration.ZERO));
        queue.close();
        assertNull(queue.poll(Duration.ofSeconds(1)));
    }

    private static DiagnosticStreamFrame signal(long sequence)
    {
        return DiagnosticStreamFrame.float32(DiagnosticStreamFrame.TYPE_CHANNEL_SIGNAL, 1, sequence,
            System.currentTimeMillis(), 851_012_500L, 25_000L, 512, new float[]{-50.0f});
    }
}
