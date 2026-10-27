package io.github.dsheirer.web.tuner;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.diagnostic.*;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.test.TestTuner;
import io.github.dsheirer.web.http.ApiHttpResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TunerAllocationMonitorServiceTest
{
    private static final TunerAllocationContext CONTEXT = new TunerAllocationContext("saved-channel", "Named control",
        "Named system", "Named site", null, TunerAllocationContext.Role.CONTROL);

    @Test void currentNicknameLabelsTunersDecisionsAndCandidatesAndClearingInvalidatesTheCache() throws Exception
    {
        FakeTuner tuner = new FakeTuner("private-native-receiver", 851_000_000L);
        AtomicReference<Map<String,TunerAdministrationService.ReceiverReference>> receivers =
            new AtomicReference<>(Map.of());
        try(TunerAllocationDiagnosticService history = new TunerAllocationDiagnosticService())
        {
            history.offer(new TunerAllocationDecision(900, CONTEXT, tuner.getId(), TunerAllocationDecision.Outcome.MOVED,
                TunerAllocationDecision.Reason.SPARE_TUNER, 851_100_000L, 12_500L, 850_000_000L,
                851_000_000L, 851_000_000L, 2_400_000L, 0L, null, null, null,
                List.of(new TunerAllocationDecision.Candidate(tuner.getId(),
                    TunerAllocationDecision.Reason.SPARE_TUNER, 100_000L, 2_000_000_000L))));
            TunerAllocationMonitorService service = new TunerAllocationMonitorService(() -> List.of(tuner),
                List::of, List::of, history, () -> 1_000, receivers::get);
            var original = service.snapshot();
            String fallback = original.tuners().getFirst().name();
            receivers.set(Map.of(tuner.getId(), new TunerAdministrationService.ReceiverReference(
                "tuner-" + "a".repeat(32), "Roof antenna")));
            var named = service.snapshot();
            assertNotSame(original, named, "nickname changes invalidate the view even within the cache interval");
            assertEquals("Roof antenna", named.tuners().getFirst().name());
            assertEquals("Roof antenna", named.decisions().getFirst().tunerName());
            assertEquals("Roof antenna", named.decisions().getFirst().candidates().getFirst().tunerName());
            assertEquals(original.tuners().getFirst().id(), named.tuners().getFirst().id());
            assertEquals(original.decisions().getFirst().tunerId(), named.decisions().getFirst().tunerId());
            assertEquals(original.tuners().getFirst().frequencyHz(), named.tuners().getFirst().frequencyHz());
            assertSame(named, service.snapshot(), "unchanged labels still reuse the shared projection");
            assertEquals(fallback, original.tuners().getFirst().name(), "previous snapshots remain immutable");

            receivers.set(Map.of());
            var cleared = service.snapshot();
            assertEquals(fallback, cleared.tuners().getFirst().name());
            assertEquals(fallback, cleared.decisions().getFirst().tunerName());
            assertEquals(fallback, cleared.decisions().getFirst().candidates().getFirst().tunerName());
            assertFalse(ApiHttpResponse.normalizePayload(cleared).toString().contains("private-native-receiver"));
            assertEquals("Roof antenna", named.tuners().getFirst().name());
        }
    }

    @Test void unionCoverageUsesRuntimeCentersFullWidthsDcAndLimitsAndKeepsHistoryFrozen() throws Exception
    {
        FakeTuner first = new FakeTuner("PRIVATE-SERIAL-1", 851_000_000L);
        FakeTuner second = new FakeTuner("PRIVATE-SERIAL-2", 854_000_000L);
        first.getTuner().getTunerController().setMiddleUnusableHalfBandwidth(10_000);
        second.getTuner().getTunerController().setMaximumFrequency(854_100_000);
        List<TunerAllocationKnownSite.Frequency> frequencies = List.of(
            new TunerAllocationKnownSite.Frequency(851_000_000, 12_500, TunerAllocationContext.Role.CONTROL),
            new TunerAllocationKnownSite.Frequency(851_100_000, 12_500, TunerAllocationContext.Role.CALL),
            new TunerAllocationKnownSite.Frequency(853_900_000, 12_500, TunerAllocationContext.Role.CALL),
            new TunerAllocationKnownSite.Frequency(854_100_000, 12_500, TunerAllocationContext.Role.CALL));
        AtomicLong clock = new AtomicLong(1_000);
        try(TunerAllocationDiagnosticService history = new TunerAllocationDiagnosticService())
        {
            history.offer(new TunerAllocationDecision(900, CONTEXT, first.getId(), TunerAllocationDecision.Outcome.MOVED,
                TunerAllocationDecision.Reason.SPARE_TUNER, 851_100_000L, 12_500L, 850_000_000L,
                851_000_000L, 851_000_000L, 2_400_000L, 10_000L, 851_100_000L, null, null, List.of()));
            TunerAllocationMonitorService service = new TunerAllocationMonitorService(() -> List.of(first, second),
                () -> List.of(new TunerAllocationKnownSite(CONTEXT, frequencies)),
                () -> List.of(new TunerAllocationRuntimeChannel(first.getId(), CONTEXT, 851_100_000, 12_500)), history, clock::get);
            var snapshot = service.snapshot();
            assertEquals(2, snapshot.sites().getFirst().coveredFrequencyCount());
            assertEquals(2, snapshot.sites().getFirst().tuners().size(), "An unused/other-site window also contributes coverage");
            assertEquals(851_000_000L, snapshot.tuners().getFirst().frequencyHz());
            assertEquals(20_000L, snapshot.tuners().getFirst().dcGapHz());
            String json = ApiHttpResponse.normalizePayload(snapshot).toString();
            assertFalse(json.contains("PRIVATE-SERIAL"));
            assertTrue(json.contains("Named site"));
            first.getTuner().getTunerController().setFrequency(852_000_000L);
            clock.addAndGet(300);
            assertEquals(852_000_000L, service.snapshot().tuners().getFirst().frequencyHz());
            assertEquals(851_000_000L, service.snapshot().decisions().getFirst().afterFrequencyHz());
            assertEquals(850_000_000L, service.snapshot().decisions().getFirst().beforeFrequencyHz());
        }
    }

    @Test void slowProjectionAndManyClientsCannotDelayTheProducerOrDuplicateProjection() throws Exception
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<Thread> projectionThread = new AtomicReference<>();
        try(TunerAllocationDiagnosticService history = new TunerAllocationDiagnosticService())
        {
            TunerAllocationMonitorService service = new TunerAllocationMonitorService(List::of, () -> {
                reads.incrementAndGet(); projectionThread.set(Thread.currentThread()); entered.countDown();
                try { release.await(3, TimeUnit.SECONDS); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
                return List.of();
            }, List::of, history, () -> 1_000);
            CompletableFuture<?> blocked = CompletableFuture.runAsync(service::snapshot);
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            try
            {
                for(int index = 0; index < 20; index++) assertNull(service.snapshot());
                assertEquals(1, reads.get());
                assertNotSame(Thread.currentThread(), projectionThread.get());
                assertTrue(history.offer(new TunerAllocationDecision(900, CONTEXT, null,
                    TunerAllocationDecision.Outcome.ALLOCATION_FAILED, TunerAllocationDecision.Reason.NO_TUNER_AVAILABLE,
                    851_100_000L, 12_500L, null, null, null, null, null, null, null, null, List.of())));
                assertEquals(1, history.snapshot().decisions().size());
            }
            finally { release.countDown(); blocked.get(2, TimeUnit.SECONDS); }
            assertNotNull(service.snapshot());
            assertEquals(1, reads.get());
        }
    }

    @Test void disappearingTunerDoesNotEraseCapturedDecisionAndVisibleHistoryIsBounded()
    {
        AtomicReference<List<DiscoveredTuner>> inventory = new AtomicReference<>(List.of());
        AtomicLong clock = new AtomicLong(1_000);
        try(TunerAllocationDiagnosticService history = new TunerAllocationDiagnosticService())
        {
            for(int index = 0; index < 300; index++) history.offer(new TunerAllocationDecision(index, CONTEXT,
                "gone-native-serial", TunerAllocationDecision.Outcome.MOVED, TunerAllocationDecision.Reason.SPARE_TUNER,
                851_100_000L, 12_500L, 850_000_000L + index, 851_000_000L, 851_000_000L,
                2_400_000L, 0L, null, null, null, List.of()));
            var service = new TunerAllocationMonitorService(inventory::get, List::of, List::of, history, clock::get);
            assertEquals(100, service.snapshot().decisions().size());
            assertEquals(0, service.snapshot().tuners().size());
            assertNotNull(service.snapshot().decisions().getFirst().tunerName());
            assertFalse(ApiHttpResponse.normalizePayload(service.snapshot()).toString().contains("gone-native-serial"));
        }
    }

    @Test void observerNeverWaitsForControllerLockOrFetchesUsbPreferredName() throws Exception
    {
        FakeTuner tuner = new FakeTuner("private-lock-tuner", 851_000_000, true);
        try(TunerAllocationDiagnosticService history = new TunerAllocationDiagnosticService())
        {
            var known = new TunerAllocationKnownSite(CONTEXT, List.of(
                new TunerAllocationKnownSite.Frequency(851_000_000, 12_500, TunerAllocationContext.Role.CONTROL),
                new TunerAllocationKnownSite.Frequency(851_100_000, 12_500, TunerAllocationContext.Role.CALL)));
            var service = new TunerAllocationMonitorService(() -> List.of(tuner), () -> List.of(known), List::of,
                history, () -> 1_000);
            tuner.getTuner().getTunerController().getLock().lock();
            try
            {
                var result = CompletableFuture.supplyAsync(service::snapshot).get(1, TimeUnit.SECONDS);
                assertEquals(1, result.sites().getFirst().coveredFrequencyCount());
                assertFalse(result.tuners().getFirst().supportsSharedWindow());
                assertEquals(851_000_000L, result.tuners().getFirst().frequencyHz());
            }
            finally { tuner.getTuner().getTunerController().getLock().unlock(); }
        }
    }

    private static final class FakeTuner extends DiscoveredTuner
    {
        private final String identity;
        private FakeTuner(String identity, long frequency) throws Exception
        {
            this(identity, frequency, false);
        }
        private FakeTuner(String identity, long frequency, boolean passThrough) throws Exception
        {
            this.identity = identity;
            mTuner = new TestTuner(this)
            {
                {
                    if(passThrough) setChannelSourceManager(new io.github.dsheirer.source.tuner.manager.PassThroughSourceManager(
                        getTunerController()));
                }
                @Override public String getPreferredName() { throw new AssertionError("Observer must not fetch device metadata"); }
            };
            mTuner.getTunerController().setFrequency(frequency);
        }
        @Override public TunerClass getTunerClass() { return TunerClass.RTL2832; }
        @Override public String getId() { return identity; }
        @Override public void start() { }
    }
}
