/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.health;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonObject;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DecoderClockHealthTest
{
    private static final long START = 1_000_000;
    private static final long ARRIVED = START + 10_000;
    private static final long FREQUENCY = 854_787_500;
    private static final long OTHER_FREQUENCY = 853_250_000;

    @Test
    void healthy_three_or_five_second_delayed_and_future_decoder_clocks_keep_the_control_frequency()
    {
        for(long offset: new long[]{-3_000, -5_000, 60_000})
        {
            ChannelHealth health = site();
            health.control("p25", true, ARRIVED + offset, ARRIVED);
            health.control("p25", true, ARRIVED + offset + 100, ARRIVED + 100);

            JsonObject report = report(health, ARRIVED + 200);
            JsonObject cc = cc(report);
            assertEquals(FREQUENCY, cc.get("hz").getAsLong(), "decoder clock offset " + offset);
            assertEquals(2, cc.get("good").getAsLong());
            assertEquals(Set.of("v", "node", "recorder", "window", "sites"), report.keySet());
            assertEquals(Set.of("name", "protocol", "cc", "calls"),
                report.getAsJsonArray("sites").get(0).getAsJsonObject().keySet());
            assertEquals(Set.of("hz", "lockedS", "good", "bad", "symbolRate", "syncLossSym"), cc.keySet(),
                "clock tracking adds no outbound fields");
        }
    }

    @Test
    void advancing_bad_units_and_sync_loss_expire_a_good_unit_in_the_same_decoder_clock()
    {
        for(long offset: new long[]{-5_000, 60_000})
        {
            long decoded = ARRIVED + offset;
            ChannelHealth bad = site();
            bad.control("p25", true, decoded, ARRIVED);
            bad.control("p25", false, decoded + 2_000, ARRIVED + 100);
            assertTrue(cc(report(bad, ARRIVED + 100)).has("hz"));
            bad.control("p25", false, decoded + 2_001, ARRIVED + 101);
            JsonObject cc = cc(report(bad, ARRIVED + 101));
            assertFalse(cc.has("hz"), "recent arrival does not make a source-old good unit current");
            assertEquals(1, cc.get("good").getAsLong());
            assertEquals(2, cc.get("bad").getAsLong());

            ChannelHealth unsynchronized = site();
            unsynchronized.control("p25", true, decoded, ARRIVED);
            unsynchronized.syncLoss(4_800, decoded + 2_001, ARRIVED + 101);
            JsonObject loss = cc(report(unsynchronized, ARRIVED + 101));
            assertFalse(loss.has("hz"), "sync loss advances the same source clock without a control unit");
            assertEquals(4_800, loss.get("syncLossSym").getAsLong());
        }
    }

    @Test
    void silence_expires_at_the_existing_two_second_boundary_without_changing_locked_duration()
    {
        ChannelHealth health = site();
        health.control("p25", true, ARRIVED - 5_000, ARRIVED);
        assertTrue(cc(report(health, ARRIVED + 1_999)).has("hz"));
        assertTrue(cc(report(health, ARRIVED + 2_000)).has("hz"), "the existing boundary is inclusive");
        JsonObject stale = cc(report(health, ARRIVED + 2_001));
        assertFalse(stale.has("hz"));
        assertEquals(Set.of("lockedS", "good", "bad", "symbolRate", "syncLossSym"), stale.keySet(),
            "an expired frequency is omitted rather than null or zero");
        assertFalse(cc(report(health, ARRIVED + 9_999)).has("hz"));

        ChannelHealth legacy = site();
        legacy.control("p25", true, ARRIVED);
        legacy.control("p25", true, ARRIVED + 100);
        legacy.control("p25", false, ARRIVED + 500);
        assertEquals(2_100, legacy.totals(ARRIVED + 10_000).lockedMs(),
            "legacy duration keeps the 100 ms interval and capped two second tail");
    }

    @Test
    void duplicate_and_out_of_order_good_units_do_not_renew_or_restore_current_frequency()
    {
        long decoded = ARRIVED - 5_000;
        ChannelHealth silent = site();
        silent.control("p25", true, decoded, ARRIVED);
        silent.control("p25", true, decoded, ARRIVED + 1_500);
        silent.control("p25", true, decoded - 1, ARRIVED + 1_900);
        assertFalse(cc(report(silent, ARRIVED + 2_001)).has("hz"),
            "late copies cannot renew the last advancing good unit's arrival time");
        assertEquals(3, silent.totals(ARRIVED + 2_001).good(), "quality counts still include received units");

        ChannelHealth advanced = site();
        advanced.control("p25", true, decoded, ARRIVED);
        advanced.syncLoss(100, decoded + 2_001, ARRIVED + 100);
        advanced.control("p25", true, decoded, ARRIVED + 200);
        advanced.control("p25", true, decoded - 1, ARRIVED + 300);
        assertFalse(cc(report(advanced, ARRIVED + 300)).has("hz"),
            "a delayed old good unit cannot undo newer source-clock loss evidence");
    }

    @Test
    void stop_demand_epochs_and_retuning_clear_freshness_while_same_frequency_notifications_preserve_it()
    {
        ChannelHealth health = site();
        health.control("p25", true, ARRIVED - 5_000, ARRIVED);
        health.setFrequency(FREQUENCY);
        assertEquals(FREQUENCY, cc(report(health, ARRIVED + 100)).get("hz").getAsLong());
        health.setFrequency(OTHER_FREQUENCY);
        assertFalse(cc(report(health, ARRIVED + 100)).has("hz"), "a retune needs a new good unit");
        health.control("p25", true, ARRIVED - 4_900, ARRIVED + 200);
        assertEquals(OTHER_FREQUENCY, cc(report(health, ARRIVED + 200)).get("hz").getAsLong());

        health.stopped(ARRIVED + 300);
        assertFalse(cc(report(health, ARRIVED + 301)).has("hz"));
        health.started(ARRIVED + 400);
        assertFalse(cc(report(health, ARRIVED + 400)).has("hz"), "restart cannot reuse a previous lock");
        health.control("p25", true, ARRIVED - 4_800, ARRIVED + 500);
        assertTrue(cc(report(health, ARRIVED + 500)).has("hz"));

        long oldEpoch = health.collectionEpoch();
        health.setCollecting(false, ARRIVED + 600);
        health.control("p25", true, ARRIVED - 4_700, ARRIVED + 700);
        health.setCollecting(true, ARRIVED + 800);
        assertFalse(health.totals(ARRIVED + 800).locked());
        health.control(oldEpoch, "p25", true, ARRIVED - 4_600, ARRIVED + 900);
        health.syncLoss(oldEpoch, 4_800, ARRIVED - 4_500, ARRIVED + 1_000);
        assertFalse(health.totals(ARRIVED + 1_000).locked(), "an old epoch cannot restore freshness");
        assertEquals(3, health.totals(ARRIVED + 1_000).good());
        assertEquals(0, health.totals(ARRIVED + 1_000).syncLossSym());

        health.control("p25", true, ARRIVED - 4_400, ARRIVED + 1_100);
        health.setFrequency(OTHER_FREQUENCY);
        assertTrue(health.totals(ARRIVED + 1_100).locked());
        assertEquals(OTHER_FREQUENCY, health.totals(ARRIVED + 1_100).frequency());
        health.setFrequency(FREQUENCY);
        assertFalse(health.totals(ARRIVED + 1_100).locked());
    }

    private static ChannelHealth site()
    {
        ChannelHealth health = new ChannelHealth(ChannelHealthTest.info(201, "p25"));
        health.setProtocol("p25");
        health.setCallsCounted();
        health.setSyncLossMeasured(true);
        health.setFrequency(FREQUENCY);
        health.started(START);
        return health;
    }

    private static JsonObject report(ChannelHealth health, long now)
    {
        HealthSnapshot snapshot = new HealthSnapshot(now, START, List.of(health.totals(now)), List.of());
        JsonObject report = NodeReport.build(new NodeReporter.Window(null, snapshot, false, 0), new JsonObject(),
            "test", info -> true);
        assertNotNull(report, "the configured running or stopped trunked site stays reportable");
        return report;
    }

    private static JsonObject cc(JsonObject report)
    {
        return report.getAsJsonArray("sites").get(0).getAsJsonObject().getAsJsonObject("cc");
    }
}
