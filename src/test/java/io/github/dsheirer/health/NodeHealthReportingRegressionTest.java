/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.health;

import com.google.gson.JsonObject;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NodeHealthReportingRegressionTest
{
    private static final long START = 1_794_590_000_000L;

    private static ChannelHealth site()
    {
        ChannelHealth health = new ChannelHealth(ChannelHealthTest.info(101, "p25"));
        health.setFrequency(851_000_000);
        health.setCallsCounted();
        health.started(START);
        return health;
    }

    private static HealthSnapshot snapshot(ChannelHealth health, long time)
    {
        return new HealthSnapshot(time, START, List.of(health.totals(time)), List.of());
    }

    private static JsonObject build(HealthSnapshot before, HealthSnapshot after)
    {
        return NodeReport.build(new NodeReporter.Window(before, after, false, 0), new JsonObject(), "test",
            info -> true);
    }

    @Test
    void deaf_from_start_and_stopped_receivers_remain_trunked_coverage()
    {
        ChannelHealth health = site();
        HealthSnapshot before = snapshot(health, START + 1000);
        health.stopped(START + 2000);
        HealthSnapshot stopped = snapshot(health, START + 3000);
        JsonObject report = build(before, stopped);
        assertFalse(report.has("channels"));
        JsonObject site = report.getAsJsonArray("sites").get(0).getAsJsonObject();
        assertEquals("p25", site.get("protocol").getAsString());
        assertEquals(0, site.getAsJsonObject("cc").get("good").getAsInt());
        assertFalse(site.getAsJsonObject("cc").has("hz"));
        assertNotNull(build(stopped, snapshot(health, START + 10_000)), "continued stopped coverage");
    }

    @Test
    void missing_observations_discard_only_the_incomplete_window_and_resume_with_a_gap()
    {
        ChannelHealth health = site();
        HealthSnapshot before = snapshot(health, START + 1000);
        health.getCalls().lost();
        HealthSnapshot lost = snapshot(health, START + 300_000);
        assertNull(build(before, lost), "no partial mandatory totals");

        NodeReporter reporter = new NodeReporter(42);
        HealthHistory history = new HealthHistory();
        history.add(before);
        history.add(lost);
        reporter.begin(before);
        reporter.isDue(lost);
        reporter.discardTo(lost);
        //Keep snapshot cadence while overflow's quiet tail drains, discarding every qualified interval.
        HealthSnapshot drained = snapshot(health, START + 320_000);
        history.add(drained);
        reporter.discardTo(drained);
        health.getCalls().count(CallOutcome.RECORDED);
        HealthSnapshot recovered = snapshot(health, START + 600_000);
        history.add(recovered);
        NodeReporter.Window window = reporter.window(history);
        assertEquals(drained.timeMs(), window.startMs());
        assertTrue(window.gap());
        JsonObject report = NodeReport.build(window, new JsonObject(), "test", info -> true);
        JsonObject calls = report.getAsJsonArray("sites").get(0).getAsJsonObject().getAsJsonObject("calls");
        assertEquals(1, calls.get("started").getAsInt());
        assertEquals(1, calls.get("recorded").getAsInt());
        reporter.delivered(null);
        HealthSnapshot next = snapshot(health, START + 900_000);
        history.add(next);
        assertFalse(reporter.window(history).gap(), "gap acknowledged only by the successful report");
    }

    @Test
    void conventional_coverage_requires_actual_samples_and_never_substitutes_processing_uptime()
    {
        ChannelHealth health = new ChannelHealth(ChannelHealthTest.info(102, "fm"));
        health.started(START);
        health.setFrequency(155_520_000);
        HealthSnapshot before = snapshot(health, START + 1000);
        assertNull(build(before, snapshot(health, START + 10_000)));
        health.deliveredSamples(2000, 1000);
        JsonObject channel = build(before, snapshot(health, START + 11_000)).getAsJsonArray("channels")
            .get(0).getAsJsonObject();
        assertEquals(2.0, channel.get("heardS").getAsDouble());
    }

    @Test
    void history_is_bounded_even_if_snapshot_clock_repeats()
    {
        HealthHistory history = new HealthHistory();
        HealthSnapshot first = new HealthSnapshot(START, START, List.of(), List.of());
        history.add(first);
        for(int index = 0; index < HealthHistory.MAX_SNAPSHOTS + 10; index++)
        {
            history.add(new HealthSnapshot(START + 1, START, List.of(), List.of()));
        }
        assertSame(first, history.first());
        assertNotSame(first, history.atOrAfter(START), "old duplicate-clock entries have been pruned");
    }
    @Test
    void accepted_response_interval_uses_receipt_time_without_moving_report_boundaries()
    {
        ChannelHealth health = site();
        HealthHistory history = new HealthHistory();
        history.add(snapshot(health, START + 1000));
        history.add(snapshot(health, START + 300_000));
        NodeReporter reporter = new NodeReporter(41);
        reporter.begin(history.first());
        reporter.isDue(history.latest());
        NodeReporter.Window window = reporter.window(history);
        long receipt = window.now().timeMs() + 30_000;
        reporter.delivered(60.0, receipt);
        assertTrue(reporter.getNextAt() >= receipt + 60_000);
        assertTrue(reporter.getNextAt() <= receipt + 60_000 + NodeReporter.JITTER_MS);
        history.add(snapshot(health, START + 600_000));
        assertEquals(window.now().timeMs(), reporter.window(history).startMs());
        reporter.failed(history.latest(), NodeReporter.Failure.BUSY, 7200.0, receipt + 500_000);
        assertEquals(receipt + 500_000 + 7_200_000, reporter.getNextAt(), "explicit long Retry-After is honored");
    }

}
