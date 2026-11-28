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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

public class NodeReporterTest
{
    private static final long SINCE = 1_794_590_000_437L; //Not a round time

    /**
     * A snapshot with one site whose good message count is the given number.
     */
    static HealthSnapshot snapshot(long timeMs, long good)
    {
        ChannelHealth.Totals site = new ChannelHealth.Totals(ChannelHealthTest.info(1, "p25"), "p25", 851_000_000,
            java.util.Map.of(), good, 0, null, timeMs - SINCE, true, CallTally.Totals.EMPTY, timeMs - SINCE, 0, 0, 0);
        return new HealthSnapshot(timeMs, SINCE, List.of(site), List.of());
    }

    @Test
    void nodes_report_at_their_own_random_point_in_each_interval()
    {
        List<Long> firsts = new ArrayList<>();

        for(int seed = 0; seed < 50; seed++)
        {
            NodeReporter reporter = new NodeReporter(seed);
            assertFalse(reporter.isDue(snapshot(SINCE, 0)));
            long first = reporter.getNextAt();
            assertTrue(first >= SINCE + NodeReporter.FIRST_AFTER_MS, "first at least 60 s after the start");
            assertTrue(first <= SINCE + NodeReporter.FIRST_AFTER_MS + NodeReporter.INTERVAL_MS +
                NodeReporter.JITTER_MS);
            firsts.add(first);

            HealthHistory history = new HealthHistory();
            history.add(snapshot(first, 10));
            assertTrue(reporter.isDue(history.latest()));
            assertNotNull(reporter.window(history));
            reporter.delivered(null);
            long second = reporter.getNextAt();
            assertTrue(Math.abs(second - first - NodeReporter.INTERVAL_MS) <= 2 * NodeReporter.JITTER_MS + 1000,
                "the next keeps to the node's point in the interval");
        }

        firsts.sort(Long::compare);
        assertTrue(firsts.get(49) - firsts.get(0) > NodeReporter.INTERVAL_MS * 0.7, "spread over the interval");
    }

    @Test
    void nodes_started_together_with_similar_ids_are_spread_over_the_interval()
    {
        List<Long> firsts = new ArrayList<>();

        for(int systemId = 1000; systemId < 1050; systemId++)
        {
            NodeReporter reporter = new NodeReporter(String.valueOf(systemId));
            reporter.isDue(snapshot(SINCE, 0));
            firsts.add(reporter.getNextAt());
        }

        firsts.sort(Long::compare);
        assertTrue(firsts.get(49) - firsts.get(0) > NodeReporter.INTERVAL_MS * 0.7, "spread over the interval");

        //50 times spread at random over 5 minutes have about 8 neighbours within a second of each other, by chance.
        //Seeding that leaves nearby seeds alike puts nearly all 50 within a second of one another.
        int withinASecond = 0;

        for(int i = 1; i < firsts.size(); i++)
        {
            if(firsts.get(i) - firsts.get(i - 1) < 1000)
            {
                withinASecond++;
            }
        }

        assertTrue(withinASecond < 25, "not bunched together: " + withinASecond);
    }

    @Test
    void delivered_windows_follow_on_exactly_at_times_that_are_not_round()
    {
        NodeReporter reporter = new NodeReporter(5);
        HealthHistory history = new HealthHistory();
        List<Long> seqs = new ArrayList<>();
        Long lastEnd = null;
        long counted = 0;
        long t = SINCE;

        while(t < SINCE + 2_000_000)
        {
            history.add(snapshot(t, (t - SINCE) / 10_003 * 37));

            if(reporter.isDue(history.latest()))
            {
                NodeReporter.Window window = reporter.window(history);

                if(lastEnd != null)
                {
                    assertEquals(lastEnd, window.startMs(), "each window starts where the last ended");
                }

                ChannelHealth.Totals a = window.now().channel(1);
                ChannelHealth.Totals b = window.base() != null ? window.base().channel(1) : null;
                counted += a.good() - (b != null ? b.good() : 0);
                seqs.add(window.seq());
                lastEnd = window.now().timeMs();
                reporter.delivered(null);
            }

            t += 10_003;
        }

        assertTrue(seqs.size() >= 5);

        for(int i = 0; i < seqs.size(); i++)
        {
            assertEquals(i, seqs.get(i), "numbered on, not started over");
        }

        assertEquals(history.atOrAfter(lastEnd).channel(1).good(), counted, "every message counted once");
    }

    @Test
    void a_failed_report_is_carried_by_the_next_up_to_the_longest_window()
    {
        NodeReporter reporter = new NodeReporter(7);
        HealthHistory history = new HealthHistory();
        Long firstEnd = null;
        boolean reachedLongest = false;

        for(long t = SINCE; t <= SINCE + 3_000_000; t += 10_000)
        {
            HealthSnapshot now = snapshot(t, (t - SINCE) / 1000);
            history.add(now);

            if(!reporter.isDue(now))
            {
                continue;
            }

            NodeReporter.Window window = reporter.window(history);

            if(firstEnd == null)
            {
                firstEnd = window.now().timeMs();
                reporter.delivered(null);
            }
            else if(t - firstEnd > NodeReporter.MAX_WINDOW_MS)
            {
                assertTrue(window.gap(), "says time is missing");
                assertTrue(Math.abs(window.now().timeMs() - window.startMs() - NodeReporter.MAX_WINDOW_MS) <= 10_000);
                reachedLongest = true;
                break;
            }
            else
            {
                assertEquals(firstEnd, window.startMs(), "carried from the last delivered");
                assertFalse(window.gap());
                reporter.failed(now, NodeReporter.Failure.UNREACHABLE, null);
            }
        }

        assertTrue(reachedLongest);
    }

    @Test
    void a_busy_service_is_waited_for_and_refused_credentials_stop_the_node()
    {
        NodeReporter reporter = new NodeReporter(3);
        HealthSnapshot now = snapshot(SINCE + 5_000_000, 0);
        reporter.isDue(now);

        reporter.failed(now, NodeReporter.Failure.BUSY, 120.0);
        assertEquals(now.timeMs() + 120_000, reporter.getNextAt(), "Retry-After");

        reporter.failed(now, NodeReporter.Failure.BUSY, null);
        long first = reporter.getNextAt() - now.timeMs();
        reporter.failed(now, NodeReporter.Failure.BUSY, null);
        long second = reporter.getNextAt() - now.timeMs();
        assertTrue(second > first, "backs off further");
        assertTrue(second <= NodeReporter.MAX_INTERVAL_MS + NodeReporter.JITTER_MS + 1000);

        reporter.failed(now, NodeReporter.Failure.REFUSED, null);
        assertTrue(reporter.isStopped());
        assertFalse(reporter.isDue(snapshot(SINCE + 9_000_000, 0)));
    }

    @Test
    void the_service_can_set_the_interval_within_limits()
    {
        NodeReporter reporter = new NodeReporter(9);
        HealthHistory history = new HealthHistory();
        history.add(snapshot(SINCE + 990_000, 0));
        reporter.begin(history.first());
        HealthSnapshot now = snapshot(SINCE + 1_000_000, 0);
        history.add(now);
        reporter.isDue(now);
        long aimed = reporter.getNextAt();
        reporter.window(history);
        reporter.delivered(1200.0);
        assertEquals(aimed + 1_200_000, reporter.getNextAt(), 2 * NodeReporter.JITTER_MS + 1,
            "1200 s on from the node's point in the interval");

        aimed = reporter.getNextAt();
        now = snapshot(now.timeMs() + 10_000, 0);
        history.add(now);
        reporter.window(history);
        reporter.delivered(5.0);
        assertEquals(aimed + NodeReporter.MIN_INTERVAL_MS, reporter.getNextAt(), 2 * NodeReporter.JITTER_MS + 1,
            "no less than 60 s");
    }

    /**
     * Reports go every 300 s on average, though each waits for a snapshot (every 10 s) to end its window at and the
     * service gives the interval with every answer.
     */
    @Test
    void reports_are_300_seconds_apart_on_average_whatever_they_wait_for()
    {
        NodeReporter reporter = new NodeReporter(17);
        HealthHistory history = new HealthHistory();
        List<Long> ends = new ArrayList<>();

        for(long t = SINCE; ends.size() < 200; t += 10_000)
        {
            HealthSnapshot now = snapshot(t, 0);
            history.add(now);

            if(reporter.isDue(now) && reporter.window(history) != null)
            {
                reporter.delivered(300.0, t + 1_500);
                ends.add(t);
            }
        }

        double mean = (ends.getLast() - ends.getFirst()) / (double)(ends.size() - 1);
        assertEquals(NodeReporter.INTERVAL_MS, mean, 1000, "300 s apart on average");
    }

    @Test
    void a_restart_starts_the_reports_over()
    {
        NodeReporter reporter = new NodeReporter(11);
        HealthHistory history = new HealthHistory();
        history.add(snapshot(SINCE + 990_000, 0));
        reporter.begin(history.first());
        HealthSnapshot now = snapshot(SINCE + 1_000_000, 0);
        history.add(now);
        reporter.isDue(now);
        reporter.window(history);
        reporter.delivered(null);
        history.add(snapshot(SINCE + 1_010_000, 0));
        assertEquals(1, reporter.window(history).seq());

        //A snapshot from totals that started again later.
        HealthSnapshot restarted = new HealthSnapshot(SINCE + 2_000_000, SINCE + 1_900_000, List.of(), List.of());
        HealthHistory after = new HealthHistory();
        after.add(restarted);
        reporter.isDue(restarted);
        NodeReporter.Window window = reporter.window(after);
        assertEquals(0, window.seq());
        assertNull(window.base(), "from the start of the new totals");
    }

    @Test
    void a_window_always_has_time_in_it()
    {
        NodeReporter reporter = new NodeReporter(17);
        HealthHistory history = new HealthHistory();
        history.add(snapshot(SINCE, 0));
        reporter.isDue(history.latest());
        assertNull(reporter.window(history), "the totals' first snapshot: nothing to report yet");

        history.add(snapshot(SINCE + 300_000, 10));
        NodeReporter.Window window = reporter.window(history);
        assertNotNull(window);
        reporter.delivered(null);
        assertNull(reporter.window(history), "no snapshot since the last delivered window");

        history.add(snapshot(SINCE + 310_000, 11));
        window = reporter.window(history);
        assertNotNull(window);
        assertEquals(SINCE + 300_000, window.startMs());
        assertTrue(window.now().timeMs() > window.startMs());
    }

    @Test
    void reports_started_with_the_recording_start_where_it_did()
    {
        HealthHistory history = new HealthHistory();
        history.add(snapshot(SINCE, 0));
        NodeReporter reporter = new NodeReporter(18);
        reporter.begin(history.latest());

        for(long t = SINCE + 10_000; !reporter.isDue(history.latest()); t += 10_000)
        {
            history.add(snapshot(t, t - SINCE));
        }

        NodeReporter.Window window = reporter.window(history);
        assertEquals(SINCE, window.startMs());
        assertFalse(window.gap());
        assertEquals(0, window.seq());
    }

    @Test
    void reports_started_while_recording_goes_on_start_at_their_own_first_snapshot_with_a_gap()
    {
        //One node's reports, delivered for 20 minutes, then started again (its destination restarted, say).
        HealthHistory history = new HealthHistory();
        NodeReporter before = new NodeReporter(19);
        history.add(snapshot(SINCE, 0));
        before.begin(history.latest());
        long lastDelivered = 0;
        long t = SINCE;

        while(t < SINCE + 1_200_000)
        {
            t += 10_000;
            history.add(snapshot(t, t - SINCE));

            if(before.isDue(history.latest()) && before.window(history) != null)
            {
                lastDelivered = t;
                before.delivered(null);
            }
        }

        assertTrue(lastDelivered > SINCE);
        NodeReporter after = new NodeReporter(19);
        HealthSnapshot first = history.latest();
        after.begin(first);
        assertFalse(after.isDue(first));
        assertTrue(after.getNextAt() >= first.timeMs() + NodeReporter.FIRST_AFTER_MS,
            "at least 60 s after its own start");

        while(!after.isDue(history.latest()))
        {
            t += 10_000;
            history.add(snapshot(t, t - SINCE));
        }

        NodeReporter.Window window = after.window(history);
        assertEquals(first.timeMs(), window.startMs(), "from its own first snapshot");
        assertTrue(window.startMs() >= lastDelivered, "never over time already reported");
        assertTrue(window.gap(), "it can't say what came before");
        assertEquals(0, window.seq(), "numbered from 0 again");

        after.delivered(null);

        while(!after.isDue(history.latest()))
        {
            t += 10_000;
            history.add(snapshot(t, t - SINCE));
        }

        assertFalse(after.window(history).gap(), "only the first");
    }

    @Test
    void the_first_snapshot_of_the_totals_is_kept_after_older_ones_go()
    {
        HealthHistory history = new HealthHistory();

        for(long t = SINCE; t <= SINCE + HealthHistory.KEEP_MS * 2; t += 60_000)
        {
            history.add(snapshot(t, 0));
        }

        assertEquals(SINCE, history.first().timeMs());
        assertTrue(history.atOrAfter(SINCE).timeMs() > SINCE, "the rest are kept for half an hour");
    }
}
