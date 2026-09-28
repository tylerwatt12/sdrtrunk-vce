/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.remote;

import io.github.dsheirer.source.SourceEvent;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteP25BitstreamSourceTest
{
    @Test
    void offerIsNonBlockingBoundedAndDefensivelyCopies() throws Exception
    {
        RemoteP25BitstreamSource source = new RemoteP25BitstreamSource(851_012_500L, 4_800.0, "test remote", 1);
        byte[] dibits = new byte[]{0x12, 0x34};
        assertTrue(source.offer(dibits, 1_000L, 10L));
        dibits[0] = 0x55;
        assertFalse(source.offer(new byte[]{0x01}, 1_001L, 11L));
        assertEquals(1L, source.getDroppedPacketCount());
        CountDownLatch received = new CountDownLatch(1);
        List<P25RemoteBitstreamPacket> packets = new CopyOnWriteArrayList<>();
        source.setRemoteBitstreamListener(packet ->
        {
            packets.add(packet);
            received.countDown();
        });

        try
        {
            source.start();
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertArrayEquals(new byte[]{0x12, 0x34}, packets.getFirst().dibits());
            assertTrue(packets.getFirst().discontinuity());
        }
        finally
        {
            source.stop();
        }
    }

    @Test
    void sequenceGapMarksOnlyTheNextPacketDiscontinuous() throws Exception
    {
        RemoteP25BitstreamSource source =
            new RemoteP25BitstreamSource(851_012_500L, 4_800.0, "test remote", 8);
        CountDownLatch received = new CountDownLatch(3);
        List<P25RemoteBitstreamPacket> packets = new CopyOnWriteArrayList<>();
        source.setRemoteBitstreamListener(packet ->
        {
            packets.add(packet);
            received.countDown();
        });

        try
        {
            assertTrue(source.offer(new byte[]{0x01}, 1_000L, 10L));
            assertTrue(source.offer(new byte[]{0x02}, 1_001L, 12L));
            assertTrue(source.offer(new byte[]{0x03}, 1_002L, 13L));
            source.start();
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertTrue(packets.get(0).discontinuity());
            assertTrue(packets.get(1).discontinuity());
            assertFalse(packets.get(2).discontinuity());
        }
        finally
        {
            source.stop();
        }
    }

    @Test
    void closeDrainsAcceptedPacketsAndRejectsNewInput() throws Exception
    {
        RemoteP25BitstreamSource source =
            new RemoteP25BitstreamSource(851_012_500L, 4_800.0, "test remote", 2);
        CountDownLatch received = new CountDownLatch(1);
        source.setRemoteBitstreamListener(packet -> received.countDown());
        assertTrue(source.offer(new byte[]{0x01}, 1_000L, 1L));
        var drained = source.close();
        assertFalse(source.offer(new byte[]{0x02}, 1_001L, 2L));

        try
        {
            source.start();
            assertTrue(received.await(2, TimeUnit.SECONDS));
            drained.get(2, TimeUnit.SECONDS);
        }
        finally
        {
            source.stop();
        }
    }

    @Test
    void frequencyUpdateDropsOldCarrierAndNotifiesDownstream() throws Exception
    {
        RemoteP25BitstreamSource source =
            new RemoteP25BitstreamSource(851_012_500L, 4_800.0, "test remote", 2);
        List<SourceEvent> sourceEvents = new CopyOnWriteArrayList<>();
        source.setSourceEventListener(sourceEvents::add);
        assertTrue(source.offer(new byte[]{0x01}, 1_000L, 1L));

        source.updateFrequency(852_012_500L);

        CountDownLatch received = new CountDownLatch(1);
        List<P25RemoteBitstreamPacket> packets = new CopyOnWriteArrayList<>();
        source.setRemoteBitstreamListener(packet ->
        {
            packets.add(packet);
            received.countDown();
        });
        assertTrue(source.offer(new byte[]{0x02}, 2_000L, 2L));

        try
        {
            source.start();
            assertTrue(received.await(2, TimeUnit.SECONDS));
            assertEquals(852_012_500L, source.getFrequency());
            assertEquals(1L, source.getDroppedPacketCount());
            assertEquals(1, packets.size());
            assertTrue(packets.getFirst().discontinuity());
            assertEquals(1, sourceEvents.size());
            assertEquals(SourceEvent.Event.NOTIFICATION_FREQUENCY_CHANGE, sourceEvents.getFirst().getEvent());
            assertEquals(852_012_500L, sourceEvents.getFirst().getValue().longValue());
        }
        finally
        {
            source.stop();
        }

        assertThrows(IllegalArgumentException.class, () -> source.updateFrequency(0L));
    }
}
