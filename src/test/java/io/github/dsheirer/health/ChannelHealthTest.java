/*
 * *****************************************************************************
 * Copyright (C) 2014-2026 Dennis Sheirer
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

package io.github.dsheirer.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

public class ChannelHealthTest
{
    static ChannelHealth.ChannelInfo info(int id, String mode)
    {
        return new ChannelHealth.ChannelInfo(id, configurationId(id), 1, "Channel " + id, "System", "Site " + id,
            "List", mode, "p25".equals(mode) ? 4800 : null);
    }

    /**
     * A channel configuration's durable ID, made from its number.
     */
    static String configurationId(int id)
    {
        return new java.util.UUID(0, id).toString();
    }

    @Test
    void locked_seconds_count_a_good_message_within_the_last_two_seconds()
    {
        ChannelHealth health = new ChannelHealth(info(1, "p25"));
        long t = 1_000_000;

        //A good message every 100 ms for 10 s: locked 10 s.
        for(int i = 0; i <= 100; i++)
        {
            health.control("p25", true, t + i * 100L);
        }

        //A bad one, then nothing for 30 s: the lock holds 2 s past the last good message, no more.
        health.control("p25", false, t + 10_500);
        ChannelHealth.Totals totals = health.totals(t + 40_000);

        assertEquals(10_000 + ChannelHealth.LOCK_HOLD_MS, totals.lockedMs());
        assertEquals(101, totals.good());
        assertEquals(1, totals.bad());
        assertTrue(totals.isSite());
        assertEquals("p25", totals.protocol());

        //Totals only ever grow, whenever they're read.
        assertTrue(health.totals(t + 10_500).lockedMs() <= totals.lockedMs());
    }

    @Test
    void a_conventional_channel_counts_openings_and_seconds_open()
    {
        ChannelHealth health = new ChannelHealth(info(2, "fm"));
        health.started(0);
        health.opened(1_000);
        health.opened(1_500); //Already open: not another opening
        health.closed(4_000);
        health.opened(10_000);

        ChannelHealth.Totals totals = health.totals(12_000);
        assertFalse(totals.isSite());
        assertEquals(2, totals.opens());
        assertEquals(1, totals.closes());
        assertEquals(3_000 + 2_000, totals.openMs());
        assertEquals(12_000, totals.runningMs());

        health.stopped(13_000);
        totals = health.totals(20_000);
        assertEquals(2, totals.closes(), "stopping closes an open channel");
        assertEquals(13_000, totals.runningMs(), "not running after it stopped");
    }

    @Test
    void identity_is_what_the_site_says_of_itself()
    {
        ChannelHealth health = new ChannelHealth(info(3, "p25"));
        health.identity("wacn", 781824);
        health.identity("sysId", 939);
        health.identity("nac", 928);
        assertEquals(3, health.totals(0).identity().size());
        assertEquals(939, health.totals(0).identity().get("sysId"));
    }

    @Test
    void symbols_without_sync_add_up()
    {
        ChannelHealth health = new ChannelHealth(info(4, "p25"));
        assertNull(health.totals(0).syncLossSym(), "not measured until its decoder says it is");
        health.setSyncLossMeasured(true);
        assertEquals(0, health.totals(0).syncLossSym());
        health.syncLoss(4800);
        health.syncLoss(-5); //Nonsense from a decoder: ignored
        health.syncLoss(200);
        assertEquals(5000, health.totals(0).syncLossSym());
    }
    @Test
    void reporting_projection_cannot_hold_up_receiver_measurements() throws Exception
    {
        CountDownLatch snapshotEntered = new CountDownLatch(1);
        CountDownLatch releaseSnapshot = new CountDownLatch(1);
        CallTally blockedProjection = new CallTally()
        {
            @Override
            public Totals totals(long now)
            {
                snapshotEntered.countDown();
                try
                {
                    assertTrue(releaseSnapshot.await(5, TimeUnit.SECONDS));
                }
                catch(InterruptedException exception)
                {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                }
                return super.totals(now);
            }
        };
        ChannelHealth health = new ChannelHealth(info(5, "p25"), blockedProjection);
        health.setCallsCounted();
        Thread reader = new Thread(() -> health.totals(20_000), "blocked-health-snapshot");
        CountDownLatch producerFinished = new CountDownLatch(1);
        Thread producer = new Thread(() ->
        {
            try
            {
                for(int index = 0; index < 10_000; index++)
                {
                    health.control("p25", true, 1_000 + index);
                    health.identity("nac", index);
                    health.syncLoss(1);
                    health.deliveredSamples(480, 48_000);
                }
                health.started(1_000);
                health.opened(2_000);
                health.stopped(3_000);
                health.setFrequency(851_000_000);
            }
            finally
            {
                producerFinished.countDown();
            }
        }, "health-measurement-producer");
        try
        {
            reader.start();
            assertTrue(snapshotEntered.await(2, TimeUnit.SECONDS));
            producer.start();
            assertTrue(producerFinished.await(2, TimeUnit.SECONDS),
                "Receiver callback waited for the blocked reporting snapshot");
        }
        finally
        {
            releaseSnapshot.countDown();
            reader.join(5_000);
            producer.join(5_000);
        }
        assertFalse(reader.isAlive());
        assertFalse(producer.isAlive());
        assertEquals(10_000, health.totals(20_000).good());
        assertEquals(100_000, health.totals(20_000).heardMs());
    }

    @Test
    void several_producers_and_snapshot_readers_preserve_all_scalar_counts() throws Exception
    {
        ChannelHealth health = new ChannelHealth(info(6, "p25"));
        health.setSyncLossMeasured(true);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(6);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        List<Thread> threads = new ArrayList<>();
        for(int writer = 0; writer < 4; writer++)
        {
            threads.add(new Thread(() ->
            {
                try
                {
                    start.await();
                    for(int index = 0; index < 25_000; index++)
                    {
                        health.control("p25", (index & 1) == 0, 1_000 + index);
                        health.syncLoss(1);
                        health.deliveredSamples(480, 48_000);
                    }
                }
                catch(Throwable exception)
                {
                    failure.compareAndSet(null, exception);
                }
                finally
                {
                    done.countDown();
                }
            }, "health-writer-" + writer));
        }
        for(int reader = 0; reader < 2; reader++)
        {
            threads.add(new Thread(() ->
            {
                try
                {
                    start.await();
                    for(int index = 0; index < 2_000; index++)
                    {
                        ChannelHealth.Totals snapshot = health.totals(30_000);
                        assertTrue(snapshot.good() >= 0 && snapshot.bad() >= 0);
                    }
                }
                catch(Throwable exception)
                {
                    failure.compareAndSet(null, exception);
                }
                finally
                {
                    done.countDown();
                }
            }, "health-reader-" + reader));
        }
        threads.forEach(Thread::start);
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        for(Thread thread: threads) thread.join(1_000);
        if(failure.get() != null) throw new AssertionError(failure.get());
        ChannelHealth.Totals totals = health.totals(30_000);
        assertEquals(50_000, totals.good());
        assertEquals(50_000, totals.bad());
        assertEquals(100_000, totals.syncLossSym());
        assertEquals(1_000_000, totals.heardMs());
    }

    @Test
    void stopped_intervals_are_not_credited_after_restart_and_zero_time_opening_is_valid()
    {
        ChannelHealth health = new ChannelHealth(info(7, "p25"));
        health.started(1_000);
        health.started(1_100);
        health.control("p25", true, 1_100);
        health.control("p25", true, 1_200);
        health.stopped(1_300);
        assertEquals(200, health.totals(9_000).lockedMs());
        assertFalse(health.totals(9_000).locked());
        health.started(10_000);
        health.control("p25", true, 10_100);
        health.stopped(10_200);
        assertEquals(300, health.totals(20_000).lockedMs());
        assertEquals(500, health.totals(20_000).runningMs());

        ChannelHealth conventional = new ChannelHealth(info(8, "fm"));
        conventional.opened(0);
        conventional.closed(100);
        assertEquals(100, conventional.totals(100).openMs());
        assertEquals(1, conventional.totals(100).opens());
    }

    @Test
    void identity_snapshots_are_immutable_and_unknown_field_names_cannot_grow_producer_state()
    {
        ChannelHealth health = new ChannelHealth(info(9, "p25"));
        health.identity("site", 1);
        ChannelHealth.Totals before = health.totals(0);
        health.identity("site", 2);
        health.identity("unexpected", 3);
        assertEquals(1, before.identity().get("site"));
        assertEquals(2, health.totals(0).identity().get("site"));
        assertEquals(1, health.totals(0).identity().size());
        assertThrows(UnsupportedOperationException.class, () -> before.identity().put("site", 4));
    }

    @Test
    void delivered_sample_duration_excludes_missing_buffers_and_does_not_assume_running_means_heard()
    {
        ChannelHealth health = new ChannelHealth(info(10, "fm"));
        health.started(0);
        assertNull(health.totals(10_000).heardMs());
        health.sampleRate(48_000);
        assertEquals(0, health.totals(10_000).heardMs(), "Known source rate without delivery is measured silence");
        health.deliveredSamples(48_000, 48_000);
        //Nine seconds with no samples add nothing even though the processing chain remained started.
        assertEquals(1_000, health.totals(10_000).heardMs());
        assertEquals(10_000, health.totals(10_000).runningMs());
        health.deliveredSamples(24_000, 24_000);
        assertEquals(2_000, health.totals(20_000).heardMs(), "Sample-rate changes use their actual duration");

        ChannelHealth unknown = new ChannelHealth(info(11, "fm"));
        unknown.deliveredSamples(100, 0);
        unknown.sampleRate(48_000);
        unknown.deliveredSamples(48_000, 48_000);
        assertNull(unknown.totals(1_000).heardMs(), "Unknown delivery duration cannot later become measured uptime");
    }

    @Test
    void contended_timing_read_reuses_complete_tuple_and_marks_new_boundary_incomplete() throws Exception
    {
        ChannelHealth health = new ChannelHealth(info(12, "p25"));
        health.started(1_000);
        health.control("p25", true, 1_100);
        health.opened(1_100);
        ChannelHealth.Totals baseline = health.totals(1_200);
        assertTrue(baseline.timingComplete());
        java.lang.reflect.Field field = ChannelHealth.class.getDeclaredField("mTimingWriters");
        field.setAccessible(true);
        AtomicInteger activeWriters = (AtomicInteger)field.get(health);
        activeWriters.incrementAndGet();
        try
        {
            //Simulate a timing update paused between its compound scalar writes. The reporting worker must not
            //combine its old start timestamp with a new accumulated duration or extend an old cached lock tail.
            health.stopped(1_300);
            ChannelHealth.Totals contended = health.totals(20_000);
            assertFalse(contended.timingComplete());
            assertEquals(baseline.lockedMs(), contended.lockedMs());
            assertEquals(baseline.runningMs(), contended.runningMs());
            assertEquals(baseline.openMs(), contended.openMs());
            //Accounting still progresses while the observer cannot obtain a coherent timing boundary.
            health.control("p25", false, 1_400);
            assertEquals(1, health.totals(20_000).bad());
        }
        finally
        {
            activeWriters.decrementAndGet();
        }
        ChannelHealth.Totals recovered = health.totals(20_000);
        assertTrue(recovered.timingComplete());
        assertEquals(200, recovered.lockedMs());
        assertEquals(300, recovered.runningMs());
        assertEquals(200, recovered.openMs());
        assertEquals(1, recovered.closes());
    }

}
