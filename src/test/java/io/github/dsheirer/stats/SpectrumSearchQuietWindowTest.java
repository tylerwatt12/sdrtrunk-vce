/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.source.tuner.Tuner;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** The empty first-pass shortcut preserves the two-pass intersection, ownership checks and progress contract. */
class SpectrumSearchQuietWindowTest
{
    private static final long CENTER = 773_000_000;
    private static final List<SpectrumSearchService.Range> RANGES =
        List.of(new SpectrumSearchService.Range(769_000_000, 775_000_000));

    @Test
    void emptyMultiwindowFirstPassStillObservesEveryWindowBeforeSkippingSecondPass() throws Exception
    {
        try(Fixture fixture = new Fixture(false))
        {
            int windows = fixture.windows().size();
            CountDownLatch firstPassComplete = new CountDownLatch(1), release = new CountDownLatch(1);
            fixture.lease.onValidityCheck = check -> {
                if(check == windows + 1)
                {
                    firstPassComplete.countDown();
                    awaitRelease(release);
                }
            };
            var opened = fixture.open();
            assertTrue(firstPassComplete.await(2, TimeUnit.SECONDS));
            var betweenPasses = fixture.service.status(opened.jobId());
            assertEquals("scanning", betweenPasses.phase());
            assertEquals(windows, betweenPasses.progress().completed());
            assertEquals(windows * 2, betweenPasses.progress().total());
            assertEquals(fixture.windows(), fixture.lease.centers);
            assertEquals(windows, fixture.lease.observations.get());
            assertTrue(fixture.lease.dwells.stream().allMatch(dwell -> dwell == 1500));
            release.countDown();

            var completed = fixture.awaitPhase(opened.jobId(), "complete");
            assertEmptyCompleted(completed, windows);
            assertEquals(fixture.windows(), fixture.lease.centers);
            assertEquals(windows, fixture.lease.observations.get());
            assertEquals(0, fixture.checks.get());
            assertEquals(0, fixture.lease.closed.get());
            assertTrue(fixture.lease.valid);
        }
    }

    @Test
    void emptyFixedRecordingWindowCompletesProgressWithoutAcquiringLateIntermittentSignal() throws Exception
    {
        try(Fixture fixture = new Fixture(true))
        {
            // A peak that would first appear in the second observation cannot pass the two-pass intersection.
            fixture.lease.peaks = observation -> observation == 1 ? List.of() : List.of(peak(CENTER - 50000));
            var completed = fixture.awaitPhase(fixture.open().jobId(), "complete");
            assertEmptyCompleted(completed, 1);
            assertEquals(List.of(CENTER), fixture.lease.centers);
            assertEquals(1, fixture.lease.observations.get());
            assertEquals(List.of(1500L), fixture.lease.dwells);
            assertEquals(0, fixture.checks.get());
            assertTrue(fixture.lease.valid);
        }
    }

    @Test
    void peakInLastFirstPassWindowPreservesEverySecondPassObservationAndCandidateCheck() throws Exception
    {
        try(Fixture fixture = new Fixture(false))
        {
            int windows = fixture.windows().size();
            long frequency = 774_600_000;
            fixture.lease.peaks = observation -> observation == windows || observation == windows * 2 ?
                List.of(peak(frequency)) : List.of();
            var completed = fixture.awaitPhase(fixture.open().jobId(), "complete");
            List<Long> expectedCenters = new ArrayList<>(fixture.windows());
            expectedCenters.addAll(fixture.windows());
            expectedCenters.add(SpectrumSearchService.probeCenter(frequency, fixture.lease));
            assertEquals(expectedCenters, fixture.lease.centers);
            assertEquals(windows * 2, fixture.lease.observations.get());
            assertEquals(windows * 2, completed.progress().completed());
            assertEquals(windows * 2, completed.progress().total());
            assertEquals(1, completed.progress().totalSignals());
            assertEquals(1, completed.progress().checked());
            assertEquals(List.of(frequency), fixture.checkedFrequencies);
            assertTrue(completed.candidates().isEmpty());
            assertNull(completed.truncatedReason());
        }
    }

    @Test
    void nonemptyFirstPassDoesNotMakeNewSecondPassPeaksEligible() throws Exception
    {
        try(Fixture fixture = new Fixture(false))
        {
            int windows = fixture.windows().size();
            fixture.lease.peaks = observation -> observation == 1 ? List.of(peak(770_100_000)) :
                observation == windows + 1 ? List.of(peak(770_400_000)) : List.of();
            var completed = fixture.awaitPhase(fixture.open().jobId(), "complete");
            assertEmptyCompleted(completed, windows);
            assertEquals(windows * 2, fixture.lease.observations.get());
            assertEquals(windows * 2, fixture.lease.centers.size());
            assertEquals(0, fixture.checks.get());
        }
    }

    @Test
    void firstPassTruncationStillRequiresSecondPassAndRetainsItsReason() throws Exception
    {
        try(Fixture fixture = new Fixture(false))
        {
            List<SpectrumPeakDetector.Peak> peaks = new ArrayList<>();
            for(int index = 0; index < SpectrumSearchService.MAX_CANDIDATES * 4 + 2; index++)
                peaks.add(peak(769_100_000 + index * 12500L));
            fixture.lease.peaks = observation -> observation == 1 ? peaks : List.of();
            var completed = fixture.awaitPhase(fixture.open().jobId(), "complete");
            assertEquals(fixture.windows().size() * 2, fixture.lease.observations.get());
            assertEquals("The search found more peaks than its limit. Choose fewer bands to check additional signals.",
                completed.truncatedReason());
            assertEquals(0, completed.progress().totalSignals());
            assertEquals(0, fixture.checks.get());
            assertTrue(completed.candidates().isEmpty());
        }
    }

    @Test
    void receiverLossBetweenPassesFailsAndReleasesBothRecordingAndMultiwindowLeases() throws Exception
    {
        for(boolean recording: List.of(false, true))
        {
            try(Fixture fixture = new Fixture(recording))
            {
                int windows = fixture.windows().size();
                fixture.lease.onValidityCheck = check -> {
                    if(check == windows + 1) fixture.lease.valid = false;
                };
                var failed = fixture.awaitPhase(fixture.open().jobId(), "failed");
                assertEquals(windows, failed.progress().completed());
                assertEquals(windows * 2, failed.progress().total());
                assertEquals(windows, fixture.lease.observations.get());
                assertEquals(fixture.windows(), fixture.lease.centers);
                assertTrue(failed.candidates().isEmpty());
                assertEquals(0, fixture.checks.get());
                assertEquals("The receiver could not finish searching. Check its availability and try again.", failed.reason());
                assertTrue(fixture.lease.closedLatch.await(2, TimeUnit.SECONDS));
                assertEquals(1, fixture.lease.closed.get());
            }
        }
    }

    @Test
    void receiverValidityIsCheckedBeforeEverySkippedWindow() throws Exception
    {
        try(Fixture fixture = new Fixture(false))
        {
            int windows = fixture.windows().size();
            assertTrue(windows > 2);
            fixture.lease.onValidityCheck = check -> {
                if(check == windows + 3) fixture.lease.valid = false;
            };
            var failed = fixture.awaitPhase(fixture.open().jobId(), "failed");
            assertEquals(windows + 2, failed.progress().completed());
            assertEquals(windows * 2, failed.progress().total());
            assertEquals(windows, fixture.lease.observations.get());
            assertEquals(fixture.windows(), fixture.lease.centers);
            assertTrue(failed.candidates().isEmpty());
            assertTrue(fixture.lease.closedLatch.await(2, TimeUnit.SECONDS));
            assertEquals(1, fixture.lease.closed.get());
        }
    }

    @Test
    void cancellationBetweenPassesReleasesOwnershipWithoutSecondPassAcquisition() throws Exception
    {
        for(boolean recording: List.of(false, true))
        {
            try(Fixture fixture = new Fixture(recording))
            {
                int windows = fixture.windows().size();
                CountDownLatch firstPassComplete = new CountDownLatch(1), release = new CountDownLatch(1);
                fixture.lease.onValidityCheck = check -> {
                    if(check == windows + 1)
                    {
                        firstPassComplete.countDown();
                        awaitRelease(release);
                    }
                };
                var opened = fixture.open();
                assertTrue(firstPassComplete.await(2, TimeUnit.SECONDS));
                assertEquals(windows, fixture.service.status(opened.jobId()).progress().completed());
                fixture.service.cancel(opened.jobId());
                assertEquals(windows, fixture.lease.observations.get());
                assertEquals(fixture.windows(), fixture.lease.centers);
                assertEquals(0, fixture.checks.get());
                assertFalse(fixture.lease.valid);
                assertEquals(1, fixture.lease.closed.get());
                assertThrows(SpectrumSearchService.SearchExpiredException.class,
                    () -> fixture.service.status(opened.jobId()));
            }
        }
    }

    private static void assertEmptyCompleted(SpectrumSearchService.Snapshot snapshot, int windows)
    {
        assertEquals("complete", snapshot.phase());
        assertEquals(windows * 2, snapshot.progress().completed());
        assertEquals(windows * 2, snapshot.progress().total());
        assertEquals(0, snapshot.progress().totalSignals());
        assertEquals(0, snapshot.progress().checked());
        assertNull(snapshot.progress().currentFrequencyHz());
        assertTrue(snapshot.candidates().isEmpty());
        assertTrue(snapshot.aliasGroups().isEmpty());
        assertNull(snapshot.reason());
        assertNull(snapshot.truncatedReason());
    }

    private static SpectrumPeakDetector.Peak peak(long frequency)
    { return new SpectrumPeakDetector.Peak(frequency, -35, 15, 10); }

    private static void awaitRelease(CountDownLatch release)
    {
        try
        {
            if(!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Test did not release first-pass barrier");
        }
        catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    private static final class Fixture implements AutoCloseable
    {
        final FakeLease lease;
        final AtomicInteger checks = new AtomicInteger();
        final List<Long> checkedFrequencies = new ArrayList<>();
        final SpectrumSearchService service;
        Fixture(boolean recording)
        {
            lease = new FakeLease(recording);
            service = new SpectrumSearchService(new EmptyChannels(), (tuner, browse) -> lease,
                (receiver, frequency, cancelled) -> {
                    checks.incrementAndGet();
                    checkedFrequencies.add(frequency);
                    return null;
                }, () -> 10000, () -> new SpectrumSearchService.Catalog(List.of(), null, List.of(), null));
        }
        List<Long> windows()
        {
            return lease.recording ? List.of(CENTER) : SpectrumSearchService.windows(RANGES,
                lease.usableBandwidthHz(), lease.minimumFrequencyHz(), lease.maximumFrequencyHz());
        }
        SpectrumSearchService.Snapshot open()
        {
            var ranges = lease.recording ?
                List.of(new SpectrumSearchService.Range(CENTER - 56250, CENTER - 43750)) : RANGES;
            return service.open("test-receiver", "test-browse", ranges, 1500);
        }
        SpectrumSearchService.Snapshot awaitPhase(String id, String phase) throws Exception
        {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            SpectrumSearchService.Snapshot snapshot;
            do
            {
                snapshot = service.status(id);
                if(phase.equals(snapshot.phase())) return snapshot;
                if("failed".equals(snapshot.phase())) fail(snapshot.reason());
                Thread.sleep(5);
            } while(System.nanoTime() < deadline);
            throw new AssertionError("Search did not reach " + phase + ": " + snapshot.phase());
        }
        public void close() { service.close(); }
    }

    private static final class FakeLease implements SpectrumSearchHardware.Lease
    {
        final boolean recording;
        final List<Long> centers = new ArrayList<>(), dwells = new ArrayList<>();
        final AtomicInteger observations = new AtomicInteger(), validityChecks = new AtomicInteger(), closed = new AtomicInteger();
        final CountDownLatch closedLatch = new CountDownLatch(1);
        IntFunction<List<SpectrumPeakDetector.Peak>> peaks = observation -> List.of();
        IntConsumer onValidityCheck = check -> {};
        volatile boolean valid = true;
        long center = CENTER;
        FakeLease(boolean recording) { this.recording = recording; }
        public Tuner tuner() { return null; }
        public String targetId() { return "test-target"; }
        public boolean fixedWindow() { return recording; }
        public long usableBandwidthHz() { return 4_000_000; }
        public long sampleRateHz() { return 5_000_000; }
        public long middleUnusableHalfBandwidthHz() { return 12500; }
        public long minimumFrequencyHz() { return 20_000_000; }
        public long maximumFrequencyHz() { return 1_000_000_000; }
        public long centerFrequencyHz() { return center; }
        public void tune(long frequency)
        {
            assertTrue(valid);
            if(recording) assertEquals(CENTER, frequency);
            center = frequency;
            centers.add(frequency);
        }
        public List<SpectrumPeakDetector.Peak> observe(long dwell, BooleanSupplier cancelled)
        {
            dwells.add(dwell);
            return peaks.apply(observations.incrementAndGet());
        }
        public P25DiscoveryProbe.Session probe(long frequency) { throw new UnsupportedOperationException(); }
        public boolean valid()
        {
            onValidityCheck.accept(validityChecks.incrementAndGet());
            return valid;
        }
        public <T> T handoff(long frequency, Supplier<T> start) { throw new UnsupportedOperationException(); }
        public void close()
        {
            closed.incrementAndGet();
            valid = false;
            closedLatch.countDown();
        }
    }

    private static final class EmptyChannels implements SpectrumSearchService.Channels
    {
        public long revision() { return 1; }
        public SpectrumSearchService.KnownChannel known(long frequency) { throw new AssertionError("Unexpected candidate"); }
        public ChannelAdministrationService.DiscoveryReview review(long frequency, String preferred,
            P25SiteIdentity identity, String modulation) { throw new AssertionError("Unexpected candidate"); }
        public ChannelAdministrationService.DiscoveryCreated create(ChannelDefinition definition, P25SiteIdentity identity,
            String aliasName, long revision, boolean autoStart) { throw new AssertionError("Unexpected channel save"); }
        public ChannelAdministrationService.LifecycleResult start(String id, String tunerId, Tuner runtime, boolean handoff)
        { throw new AssertionError("Unexpected channel start"); }
    }
}
