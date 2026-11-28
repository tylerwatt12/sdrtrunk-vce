/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.health;

import com.google.gson.JsonObject;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelManager;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Conventional;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DecoderState;
import io.github.dsheirer.module.decode.p25.phase2.DecodeConfigP25Phase2;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import io.github.dsheirer.source.config.SourceConfigTuner;
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
    void actual_conventional_p25_decoder_reports_delivered_samples_without_trunked_calls()
    {
        Channel configured = new Channel("Conventional P25");
        configured.setDecodeConfiguration(new DecodeConfigP25Conventional());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(155_520_000);
        configured.setSourceConfiguration(source);
        try
        {
            //DecoderFactory supplies no shared manager for conventional P25; the state creates its private one.
            new P25P1DecoderState(configured, null);
            HealthMonitor monitor = new HealthMonitor(configured);
            ChannelHealth health = monitor.getHealth();
            health.setCollecting(true, START);
            health.started(START);
            monitor.getSourceEventListener().receive(SourceEvent.sampleRateChange(1_000));
            HealthSnapshot before = snapshot(health, START + 1000);
            monitor.getComplexSamplesListener().receive(new ComplexSamples(new float[2000], new float[2000], START + 1000));

            JsonObject report = build(before, snapshot(health, START + 3000));
            assertNotNull(report, "The actual conventional P25 decoder must be included in health coverage");
            assertFalse(report.has("sites"), "A conventional decoder must not invent trunked-site call totals");
            JsonObject channel = report.getAsJsonArray("channels").get(0).getAsJsonObject();
            assertEquals("p25", channel.get("mode").getAsString());
            assertEquals(155_520_000, channel.get("hz").getAsLong());
            assertEquals(2.0, channel.get("heardS").getAsDouble());
            assertFalse(health.countsCalls());
            assertNull(health.totals(START + 3000).calls());
        }
        finally
        {
            HealthRegistry.getInstance().retire(configured);
        }
    }

    @Test
    void actual_trunked_p25_managers_report_deaf_sites_before_the_first_grant()
    {
        for(boolean phase2: List.of(false, true))
        {
            Channel configured = new Channel("Deaf P25 " + (phase2 ? "Phase 2" : "Phase 1"));
            if(phase2)
            {
                DecodeConfigP25Phase2 configuration = new DecodeConfigP25Phase2();
                configuration.setTrafficChannelPoolSize(0);
                configured.setDecodeConfiguration(configuration);
            }
            else
            {
                DecodeConfigP25Phase1 configuration = new DecodeConfigP25Phase1();
                configuration.setTrafficChannelPoolSize(0);
                configured.setDecodeConfiguration(configuration);
            }
            SourceConfigTuner source = new SourceConfigTuner();
            source.setFrequency(851_000_000);
            configured.setSourceConfiguration(source);
            P25TrafficChannelManager manager = new P25TrafficChannelManager(configured);
            try
            {
                ChannelHealth health = HealthRegistry.getInstance().existingChannel(configured);
                assertNotNull(health, "A trunked manager establishes coverage during construction");
                HealthMonitor monitor = new HealthMonitor(configured);
                assertSame(health, monitor.getHealth(), "The normal monitor shares the manager's startup coverage");
                health.setCollecting(true, START);
                health.started(START);
                HealthSnapshot before = snapshot(health, START + 1000);
                JsonObject report = build(before, snapshot(health, START + 10_000));
                assertNotNull(report, "A site with no decoded grants must still report its deaf control channel");
                assertFalse(report.has("channels"));
                JsonObject site = report.getAsJsonArray("sites").get(0).getAsJsonObject();
                assertEquals("p25", site.get("protocol").getAsString());
                assertEquals(0, site.getAsJsonObject("cc").get("good").getAsLong());
                assertEquals(0, site.getAsJsonObject("cc").get("bad").getAsLong());
                assertEquals(0, site.getAsJsonObject("calls").get("started").getAsLong());
                assertEquals(0, site.getAsJsonObject("calls").get("recorded").getAsLong());
                assertTrue(health.countsCalls());
            }
            finally
            {
                manager.stop();
                HealthRegistry.getInstance().retire(configured);
            }
        }
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

    @Test
    void demand_collection_starts_at_its_first_real_baseline_even_after_an_empty_startup_snapshot()
    {
        ChannelHealth health = site();
        HealthSnapshot empty = new HealthSnapshot(START, START, List.of(), List.of());
        HealthSnapshot activated = snapshot(health, START + 10_000);
        HealthSnapshot later = snapshot(health, START + 300_000);
        HealthHistory history = new HealthHistory();
        history.add(empty);
        history.add(activated);
        history.add(later);
        NodeReporter reporter = new NodeReporter(73);
        reporter.beginCollected(empty);
        reporter.isDue(activated);
        reporter.beginCollected(activated);
        reporter.isDue(later);
        NodeReporter.Window window = reporter.window(history);
        assertNotNull(window);
        assertEquals(activated.timeMs(), window.startMs());
        assertTrue(window.gap());
        reporter.delivered(null);
        HealthSnapshot newlyEnabledSource = snapshot(health, START + 310_000);
        history.add(newlyEnabledSource);
        reporter.restarted(history, newlyEnabledSource, false);
        history.add(snapshot(health, START + 600_000));
        assertEquals(newlyEnabledSource.timeMs(), reporter.window(history).startMs());
        assertTrue(reporter.window(history).gap());
    }

    /**
     * A channel re-created when it restarts (a new channel ID for the same saved channel) starts being measured afresh
     * partway through a window.  What was measured before it is sent at once, in a report ending at the last snapshot
     * before the restart, and the next window follows on from there without a gap, the new channel counted from its
     * own start.  Nothing measured is dropped.
     */
    @Test
    void a_channel_recreated_mid_window_sends_what_came_before_and_carries_on_without_a_gap()
    {
        ChannelHealth old = site();
        HealthHistory history = new HealthHistory();
        HealthSnapshot first = snapshot(old, START + 1000);
        HealthSnapshot delivered = snapshot(old, START + 300_000);
        history.add(first);
        history.add(delivered);
        NodeReporter reporter = new NodeReporter(5);
        reporter.begin(first);
        reporter.isDue(delivered);
        assertNotNull(reporter.window(history));
        reporter.delivered(null);

        old.getCalls().count(CallOutcome.RECORDED);
        HealthSnapshot beforeRestart = snapshot(old, START + 390_000);
        history.add(beforeRestart);

        ChannelHealth.ChannelInfo again = new ChannelHealth.ChannelInfo(202, ChannelHealthTest.configurationId(101), 1,
            "Channel 101", "System", "Site 101", "List", "p25", 4800);
        ChannelHealth recreated = new ChannelHealth(again, false);
        recreated.setFrequency(851_000_000);
        recreated.setCallsCounted();
        recreated.setCollecting(true, START + 400_000);
        recreated.started(START + 400_000);
        HealthSnapshot restart = snapshot(recreated, START + 400_000);
        history.add(restart);

        reporter.restarted(history, restart, true);
        assertTrue(reporter.isDue(restart), "what came before the restart goes now");
        NodeReporter.Window flush = reporter.window(history);
        assertEquals(delivered.timeMs(), flush.startMs());
        assertEquals(beforeRestart.timeMs(), flush.now().timeMs());
        assertFalse(flush.gap());
        assertFalse(NodeReport.incomplete(flush, info -> true));
        JsonObject flushed = NodeReport.build(flush, new JsonObject(), "test", info -> true);
        assertEquals(1, flushed.getAsJsonArray("sites").get(0).getAsJsonObject().getAsJsonObject("calls")
            .get("recorded").getAsInt());
        reporter.delivered(null);
        assertTrue(reporter.getNextAt() >= beforeRestart.timeMs() + NodeReporter.MIN_INTERVAL_MS);

        reporter.restarted(history, restart, true); //Seen again on the next check: already handled
        recreated.getCalls().count(CallOutcome.RECORDED);
        HealthSnapshot later = snapshot(recreated, START + 600_000);
        history.add(later);
        NodeReporter.Window next = reporter.window(history);
        assertEquals(beforeRestart.timeMs(), next.startMs(), "follows on from the report before");
        assertFalse(next.gap());
        assertFalse(NodeReport.incomplete(next, info -> true), "the new channel counts from its own start");
        JsonObject report = NodeReport.build(next, new JsonObject(), "test", info -> true);
        assertEquals(1, report.getAsJsonArray("sites").get(0).getAsJsonObject().getAsJsonObject("calls")
            .get("recorded").getAsInt());
    }
}
