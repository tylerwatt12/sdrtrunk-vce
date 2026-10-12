package io.github.dsheirer.source.tuner.diagnostic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class TunerAllocationDiagnosticServiceTest
{
    private static final TunerAllocationContext CONTEXT = new TunerAllocationContext("channel", "Control", "System",
        "Site", null, TunerAllocationContext.Role.CALL);

    @Test void repeatedFailuresGroupButChangedGeometryAndExpiredEpisodesDoNot()
    {
        try(TunerAllocationDiagnosticService service = new TunerAllocationDiagnosticService(8))
        {
            assertTrue(service.offer(decision(100, 851_000_000L, 2_000_000L, 851_050_000L)));
            assertTrue(service.offer(decision(101, 851_000_000L, 2_000_000L, 851_050_000L)));
            var grouped = service.snapshot().decisions();
            assertEquals(1, grouped.size());
            assertEquals(2, grouped.getFirst().repeatCount());
            assertEquals(100, grouped.getFirst().firstTimestamp());
            assertEquals(101, grouped.getFirst().lastTimestamp());
            assertTrue(service.offer(decision(102, 852_000_000L, 2_000_000L, 851_050_000L)));
            assertTrue(service.offer(decision(103, 852_000_000L, 1_500_000L, 851_050_000L)));
            assertTrue(service.offer(decision(104, 852_000_000L, 1_500_000L, 851_075_000L)));
            assertEquals(4, service.snapshot().decisions().size());
            assertTrue(service.offer(decision(40_000, 852_000_000L, 1_500_000L, 851_075_000L)));
            assertEquals(5, service.snapshot().decisions().size());
        }
    }

    @Test void fullHistoryAndBlockedObserversNeverBackpressureOffers() throws Exception
    {
        try(TunerAllocationDiagnosticService service = new TunerAllocationDiagnosticService(4))
        {
            service.offer(decision(100, 851_000_000L, 2_000_000L, null));
            var held = service.snapshot();
            CountDownLatch blockedObserver = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            CompletableFuture<Void> observer = CompletableFuture.runAsync(() -> {
                blockedObserver.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                assertEquals(1, held.decisions().size());
            });
            assertTrue(blockedObserver.await(1, TimeUnit.SECONDS));
            try
            {
                CompletableFuture<Void> producer = CompletableFuture.runAsync(() -> {
                    for(int index = 0; index < 10_000; index++)
                        service.offer(decision(index + 101, 851_000_000L + index, 2_000_000L, null));
                });
                producer.get(2, TimeUnit.SECONDS);
                assertEquals(4, service.snapshot().decisions().size());
            }
            finally { release.countDown(); observer.get(2, TimeUnit.SECONDS); }
        }
    }

    @Test void successfulRequestClosesFailureEpisodeWithoutAddingSuccessfulCallRows()
    {
        try(TunerAllocationDiagnosticService service = new TunerAllocationDiagnosticService())
        {
            service.offer(decision(100, 851_000_000L, 2_000_000L, null));
            service.resolved(CONTEXT, 854_000_000L);
            service.offer(decision(101, 851_000_000L, 2_000_000L, null));
            assertEquals(1, service.snapshot().decisions().size());
            assertEquals(2, service.snapshot().decisions().getFirst().repeatCount());
            service.resolved(CONTEXT, 853_000_000L);
            assertEquals(1, service.snapshot().decisions().size());
            service.offer(decision(102, 851_000_000L, 2_000_000L, null));
            assertEquals(2, service.snapshot().decisions().size());
            assertEquals(1, service.snapshot().decisions().getFirst().repeatCount());
        }
    }

    @Test void sourceStartupFailureUpdatesMoveInsteadOfDuplicatingItsHistory()
    {
        try(TunerAllocationDiagnosticService service = new TunerAllocationDiagnosticService())
        {
            service.offer(new TunerAllocationDecision(System.currentTimeMillis(), CONTEXT, "native",
                TunerAllocationDecision.Outcome.MOVED, TunerAllocationDecision.Reason.SPARE_TUNER,
                853_000_000L, 12_500L, 851_000_000L, 852_000_000L, 852_000_000L, 2_400_000L, 5_000L,
                null, null, null, List.of()));
            long id = service.snapshot().decisions().getFirst().decisionId();
            service.sourceStartFailed(CONTEXT, "native", 853_000_000L, 12_500);
            var history = service.snapshot().decisions();
            assertEquals(1, history.size());
            assertEquals(id, history.getFirst().decisionId());
            assertEquals(TunerAllocationDecision.Outcome.MOVED_CALL_FAILED, history.getFirst().decision().outcome());
            assertEquals(851_000_000L, history.getFirst().decision().beforeFrequencyHz());
            assertEquals(852_000_000L, history.getFirst().decision().afterFrequencyHz());
            service.sourceStartFailed(CONTEXT, "native", 853_000_000L, 12_500);
            assertEquals(1, service.snapshot().decisions().size(), "Duplicate startup notifications do not add another row");
            service.sourceStartFailed(CONTEXT, "other", 854_000_000L, 12_500);
            assertEquals(2, service.snapshot().decisions().size());
            assertEquals(TunerAllocationDecision.Outcome.ALLOCATION_FAILED,
                service.snapshot().decisions().getFirst().decision().outcome());
        }
    }

    @Test void laterRequestAndOldHistoryCannotRewriteAnEarlierSuccessfulMove()
    {
        try(TunerAllocationDiagnosticService service = new TunerAllocationDiagnosticService())
        {
            service.offer(new TunerAllocationDecision(System.currentTimeMillis() - 31_000, CONTEXT, "native",
                TunerAllocationDecision.Outcome.MOVED, TunerAllocationDecision.Reason.SPARE_TUNER,
                853_000_000L, 12_500L, 851_000_000L, 852_000_000L, 852_000_000L, 2_400_000L, 0L,
                null, null, null, List.of()));
            service.sourceStartFailed(CONTEXT, "native", 853_000_000L, 12_500);
            assertEquals(2, service.snapshot().decisions().size());
            assertEquals(TunerAllocationDecision.Outcome.MOVED,
                service.snapshot().decisions().getLast().decision().outcome());
            service.offer(new TunerAllocationDecision(System.currentTimeMillis(), CONTEXT, "native",
                TunerAllocationDecision.Outcome.MOVED, TunerAllocationDecision.Reason.SPARE_TUNER,
                854_000_000L, 12_500L, 851_000_000L, 852_000_000L, 852_000_000L, 2_400_000L, 0L,
                null, null, null, List.of()));
            service.resolved(CONTEXT, 854_000_000L);
            service.sourceStartFailed(CONTEXT, "native", 854_000_000L, 12_500);
            assertEquals(4, service.snapshot().decisions().size());
            assertEquals(TunerAllocationDecision.Outcome.ALLOCATION_FAILED,
                service.snapshot().decisions().getFirst().decision().outcome());
            assertEquals(TunerAllocationDecision.Outcome.MOVED,
                service.snapshot().decisions().get(1).decision().outcome());
        }
    }

    @Test void repeatedStartupFailuresGroupUntilTheSourceActuallyStarts()
    {
        try(TunerAllocationDiagnosticService service = new TunerAllocationDiagnosticService())
        {
            service.resolved(CONTEXT, 853_000_000L);
            service.sourceStartFailed(CONTEXT, "native", 853_000_000L, 12_500);
            long id = service.snapshot().decisions().getFirst().decisionId();
            service.resolved(CONTEXT, 853_000_000L);
            service.sourceStartFailed(CONTEXT, "native", 853_000_000L, 12_500);
            assertEquals(1, service.snapshot().decisions().size());
            assertEquals(id, service.snapshot().decisions().getFirst().decisionId());
            assertEquals(2, service.snapshot().decisions().getFirst().repeatCount());

            service.sourceStarted(CONTEXT, 853_000_000L);
            assertEquals(1, service.snapshot().decisions().size(), "Successful starts do not add routine call rows");
            service.resolved(CONTEXT, 853_000_000L);
            service.sourceStartFailed(CONTEXT, "native", 853_000_000L, 12_500);
            assertEquals(2, service.snapshot().decisions().size());
            assertEquals(1, service.snapshot().decisions().getFirst().repeatCount());
            assertNotEquals(id, service.snapshot().decisions().getFirst().decisionId());
        }
    }

    @Test void concurrentProducersAndSnapshotsRemainBounded() throws Exception
    {
        try(TunerAllocationDiagnosticService service = new TunerAllocationDiagnosticService(4))
        {
            CompletableFuture<?>[] workers = new CompletableFuture[4];
            for(int worker = 0; worker < workers.length; worker++)
            {
                int offset = worker * 10_000;
                workers[worker] = CompletableFuture.runAsync(() -> {
                    for(int index = 0; index < 2_000; index++)
                    {
                        service.offer(decision(index + 100, 851_000_000L + offset + index, 2_000_000L, null));
                        assertTrue(service.snapshot().decisions().size() <= 4);
                    }
                });
            }
            CompletableFuture.allOf(workers).get(3, TimeUnit.SECONDS);
            assertTrue(service.snapshot().decisions().size() <= 4);
            service.close();
            assertFalse(service.offer(decision(20_000, 851_000_000L, 2_000_000L, null)));
        }
    }

    private static TunerAllocationDecision decision(long timestamp, Long center, Long width, Long control)
    {
        return new TunerAllocationDecision(timestamp, CONTEXT, "native-private-identity",
            TunerAllocationDecision.Outcome.ALLOCATION_FAILED, TunerAllocationDecision.Reason.NO_ROOM,
            853_000_000L, 12_500L, center, center, center, width, 5_000L, control, null, null, List.of());
    }
}
