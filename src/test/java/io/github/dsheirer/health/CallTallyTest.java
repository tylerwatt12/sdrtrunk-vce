package io.github.dsheirer.health;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class CallTallyTest
{
    @Test
    void tentative_failure_then_success_crossing_snapshot_settles_only_once()
    {
        CallTally tally = new CallTally();
        CallTally.Pending call = tally.pending(CallOutcome.NO_SOURCE, 299_500, 3_000);
        assertEquals(0, tally.totals(300_000).started());
        call.observe(CallOutcome.RECORDED, 300_500);
        CallTally.Totals end = tally.totals(303_501);
        assertEquals(1, end.started());
        assertEquals(1, end.get(CallOutcome.RECORDED));
        assertEquals(0, end.get(CallOutcome.NO_SOURCE));
        call.finish();
        call.finish();
        assertEquals(end, tally.totals(600_000));
    }

    @Test
    void current_traffic_activity_keeps_an_outcome_tentative_and_end_is_idempotent()
    {
        CallTally tally = new CallTally();
        CallTally.Pending call = tally.pending(CallOutcome.RECORDED, 1_000, 3_000);
        for(long time = 2_000; time <= 600_000; time += 1_000)
        {
            call.observe(null, time);
            assertEquals(0, tally.totals(time).started());
        }
        call.finish();
        assertEquals(1, tally.totals(700_000).started());
        assertEquals(1, tally.get(CallOutcome.RECORDED));
    }

    @Test
    void full_pending_ring_loses_observer_data_without_corrupting_completed_counts()
    {
        CallTally tally = new CallTally();
        List<CallTally.Pending> calls = new ArrayList<>();
        for(int index = 0; index <= CallTally.MAX_PENDING; index++)
        {
            calls.add(tally.pending(CallOutcome.RECORDED, 1_000, 3_000));
        }
        assertEquals(1, tally.totals().losses());
        calls.forEach(CallTally.Pending::finish);
        assertEquals(CallTally.MAX_PENDING, tally.getStarted());
        CallTally.Totals settled = tally.totals(9_000);
        assertEquals(settled.started(), settled.get(CallOutcome.RECORDED));
        assertTrue(settled.losses() >= 2, "the lost call's finalization window is incomplete too");
        tally.pending(CallOutcome.NO_RECORDER, 10_000, 3_000).finish();
        assertEquals(CallTally.MAX_PENDING + 1, tally.getStarted());
    }

    @Test
    void holding_a_snapshot_objects_monitor_cannot_block_producers() throws Exception
    {
        CallTally tally = new CallTally();
        CountDownLatch done = new CountDownLatch(1);
        Thread producer = new Thread(() -> {
            tally.pending(CallOutcome.RECORDED, 1_000, 3_000).finish();
            done.countDown();
        });
        var field = CallTally.class.getDeclaredField("mSnapshotLock");
        field.setAccessible(true);
        Object snapshotLock = field.get(tally);
        synchronized(snapshotLock)
        {
            producer.start();
            assertTrue(done.await(1, TimeUnit.SECONDS));
        }
        producer.join(1_000);
        assertEquals(1, tally.getStarted());
    }

    @Test
    void concurrent_expiry_and_explicit_end_cannot_settle_a_call_twice() throws Exception
    {
        CallTally tally = new CallTally();
        List<CallTally.Pending> calls = new ArrayList<>();
        for(int index = 0; index < 1_000; index++)
        {
            calls.add(tally.pending(CallOutcome.RECORDED, 1_000, 3_000));
        }
        CountDownLatch start = new CountDownLatch(1);
        Thread worker = new Thread(() -> {
            try { start.await(); } catch(InterruptedException e) { throw new AssertionError(e); }
            tally.totals(9_000);
        });
        worker.start();
        start.countDown();
        calls.forEach(CallTally.Pending::finish);
        worker.join(2_000);
        assertFalse(worker.isAlive());
        assertEquals(1_000, tally.getStarted());
        assertEquals(1_000, tally.get(CallOutcome.RECORDED));
        assertEquals(0, tally.totals().losses());
    }

    @Test
    void unresolved_last_grant_settles_after_quiet_without_another_grant()
    {
        CallTally tally = new CallTally();
        UnresolvedGrants grants = new UnresolvedGrants();
        grants.resolved("initial", 0);
        grants.unresolved("early", 1_000, tally);
        grants.unresolved("last", 11_000, tally);
        grants.unresolved("last", 12_000, tally);
        assertEquals(0, tally.totals(17_000).started());
        assertEquals(1, tally.totals(17_001).get(CallOutcome.NO_FREQUENCY));
        grants.reset();
        assertEquals(1, tally.getStarted());
    }

    @Test
    void a_band_plan_before_quiet_cancels_unresolved_failure_and_reset_flushes_remaining()
    {
        CallTally tally = new CallTally();
        UnresolvedGrants grants = new UnresolvedGrants();
        grants.resolved("initial", 0);
        grants.unresolved("resolved-later", 11_000, tally);
        grants.resolved("resolved-later", 12_000);
        assertEquals(0, tally.totals(20_000).started());
        grants.unresolved("ends-on-reset", 21_000, tally);
        grants.reset();
        grants.reset();
        assertEquals(1, tally.getStarted());
        assertEquals(1, tally.get(CallOutcome.NO_FREQUENCY));
    }

    @Test
    void same_talkgroup_after_quiet_is_two_unresolved_calls_even_without_a_snapshot_between()
    {
        CallTally tally = new CallTally();
        UnresolvedGrants grants = new UnresolvedGrants();
        grants.resolved("initial", 0);
        grants.unresolved("same", 11_000, tally);
        grants.unresolved("same", 17_000, tally);
        assertEquals(1, tally.getStarted());
        assertEquals(2, tally.totals(22_001).get(CallOutcome.NO_FREQUENCY));
    }
    @Test
    void material_reclassification_after_quiet_expiry_marks_collection_incomplete()
    {
        CallTally tally = new CallTally();
        CallTally.Pending call = tally.pending(CallOutcome.NO_SOURCE, 1_000, 3_000);
        CallTally.Totals expired = tally.totals(4_001);
        assertEquals(1, expired.get(CallOutcome.NO_SOURCE));
        assertEquals(0, expired.losses());
        call.observe(CallOutcome.RECORDED, 4_002);
        assertEquals(1, tally.totals().losses(), "late evidence cannot silently preserve the provisional rejection");
        assertEquals(1, tally.getStarted());
        call.finish();
        assertEquals(1, tally.getStarted(), "a retired call cannot be counted again");
    }

    @Test
    void duplicate_activity_after_explicit_end_does_not_invalidate_a_final_outcome()
    {
        CallTally tally = new CallTally();
        CallTally.Pending call = tally.pending(CallOutcome.RECORDED, 1_000, 3_000);
        call.finish();
        call.observe(null, 5_000);
        call.observe(CallOutcome.RECORDED, 5_000);
        assertEquals(0, tally.totals().losses());
        assertEquals(1, tally.get(CallOutcome.RECORDED));
    }
    @Test
    void overflow_loss_covers_its_quiet_finalization_window_then_recovers()
    {
        CallTally tally = new CallTally();
        for(int index = 0; index <= CallTally.MAX_PENDING; index++)
        {
            tally.pending(CallOutcome.RECORDED, 299_500, 3_000);
        }
        long firstLoss = tally.totals(300_000).losses();
        assertTrue(firstLoss > 0);
        long tailLoss = tally.totals(310_000).losses();
        assertTrue(tailLoss > firstLoss, "overflow call's quiet end belongs to this incomplete interval");
        long firstPastHorizon = tally.totals(320_000).losses();
        assertTrue(firstPastHorizon > tailLoss);
        assertEquals(firstPastHorizon, tally.totals(330_000).losses(), "complete intervals can resume");
    }
}
