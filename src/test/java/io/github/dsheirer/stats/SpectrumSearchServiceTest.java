/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.source.tuner.Tuner;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Exercises receiver ownership and retry ledgers independently of native hardware or the realtime decoder. */
class SpectrumSearchServiceTest
{
    private static final long A = 770_100_000, B = 770_400_000, FAR = 774_100_000;
    private static final List<SpectrumSearchService.Range> RANGES = List.of(new SpectrumSearchService.Range(769_000_000, 775_000_000));

    @Test
    void completesBothSweepsAndAllChecksBeforePublishingConfirmedRows() throws Exception
    {
        try(Fixture fixture = new Fixture(A, B, FAR))
        {
            fixture.lease.transientFrequency = B;
            CountDownLatch checking = new CountDownLatch(1), release = new CountDownLatch(1);
            fixture.check = (lease, frequency, cancelled) -> {
                checking.countDown();
                release.await();
                return evidence(frequency, 1, "C4FM", true);
            };
            var opened = fixture.open();
            assertTrue(checking.await(2, TimeUnit.SECONDS));
            var duringCheck = fixture.service.status(opened.jobId());
            assertEquals("checking", duringCheck.phase());
            assertEquals(duringCheck.progress().total(), duringCheck.progress().completed());
            assertTrue(duringCheck.candidates().isEmpty());
            assertTrue(duringCheck.aliasGroups().isEmpty());
            release.countDown();
            var completed = fixture.complete(opened.jobId());
            assertEquals(2, completed.progress().checked());
            // Both steady frequencies report the same full serving identity, so only one site is listed.
            assertEquals(1, completed.candidates().size());
            assertEquals(-31.0, completed.candidates().getFirst().strengthDbfs());
            assertEquals("Friendly network", completed.candidates().getFirst().systemName());
            assertTrue(fixture.lease.valid());
            assertEquals(0, fixture.channels.definitions.size());
        }
    }

    @Test
    void rejectsUnconfirmedOrInconsistentModeIdentityWithoutGuessing() throws Exception
    {
        try(Fixture fixture = new Fixture(A, B, FAR))
        {
            fixture.check = (lease, frequency, cancelled) -> frequency == A ? evidence(frequency, 1, "C4FM", false) :
                frequency == B ? mismatchedEvidence(frequency) : evidence(frequency, 3, "CQPSK", true);
            var completed = fixture.complete(fixture.open().jobId());
            assertEquals(3, completed.progress().checked());
            assertEquals(1, completed.candidates().size());
            assertEquals(FAR, completed.candidates().getFirst().frequencyHz());
            assertEquals("CQPSK", completed.candidates().getFirst().modulation());
        }
    }

    @Test
    void partialSaveRetryReusesOneExactSystemAliasAndNeverDuplicatesSuccessfulRows() throws Exception
    {
        try(Fixture fixture = new Fixture(A, B))
        {
            var completed = fixture.complete(fixture.open().jobId());
            fixture.channels.failOnce = B;
            var request = saveRequest(completed, Map.of(A, false, B, true));
            var partial = fixture.service.save(completed.jobId(), request);
            assertTrue(row(partial, A).saved());
            assertEquals(Boolean.FALSE, row(partial, A).autoStart());
            assertFalse(row(partial, B).saved());
            assertNotNull(row(partial, B).saveError());
            assertEquals(1, fixture.channels.createdAliases);
            assertTrue(fixture.channels.starts.isEmpty());
            // The browser repeats the original "new list" choice with a fresh configuration revision.
            var retry = fixture.service.save(completed.jobId(), saveRequest(partial, Map.of(A, false, B, true)));
            assertTrue(retry.candidates().stream().allMatch(SpectrumSearchService.Candidate::saved));
            assertEquals(2, fixture.channels.definitions.size());
            assertEquals(1, fixture.channels.createdAliases);
            assertEquals(row(retry, A).aliasListId(), row(retry, B).aliasListId());
            assertEquals(Boolean.TRUE, row(retry, B).autoStart());
            assertEquals(2, fixture.channels.identities.size());
            for(var definition: fixture.channels.definitions)
            {
                assertEquals("C4FM", definition.settings().get("modulation"));
                assertEquals(Boolean.TRUE, definition.settings().get("learn_announced_control_channels"));
                assertEquals("p25-phase1", definition.protocolId());
            }
            fixture.service.save(completed.jobId(), saveRequest(retry, Map.of(A, false, B, true)));
            assertEquals(2, fixture.channels.definitions.size());
        }
    }

    @Test
    void staleRevisionAfterPartialSuccessMarksEveryUnattemptedRowForRetry() throws Exception
    {
        try(Fixture fixture = new Fixture(A, B, FAR))
        {
            var completed = fixture.complete(fixture.open().jobId());
            fixture.channels.staleAt = B;
            var result = fixture.service.save(completed.jobId(), saveRequest(completed, Map.of()));
            assertTrue(row(result, A).saved());
            assertNotNull(row(result, B).saveError());
            assertNotNull(row(result, FAR).saveError());
            assertEquals(1, fixture.channels.definitions.size());
        }
    }

    @Test
    void exactExistingSiteAndNewFrequencyOwnerBlockAddsAndForeignIdsCannotMutate() throws Exception
    {
        try(Fixture fixture = new Fixture(A, B))
        {
            fixture.channels.knownSites.put(new P25SiteIdentity(0xBEE00, 0x348, 2, 1),
                new SpectrumSearchService.KnownChannel("existing-site", "Downtown"));
            var completed = fixture.complete(fixture.open().jobId());
            assertFalse(row(completed, A).selectable());
            assertEquals("Downtown", row(completed, A).knownChannel().name());
            fixture.channels.knownFrequencies.put(B, new SpectrumSearchService.KnownChannel("new-owner", "New channel"));
            var result = fixture.service.save(completed.jobId(), saveRequest(completed, Map.of()));
            assertTrue(result.candidates().stream().noneMatch(SpectrumSearchService.Candidate::saved));
            assertEquals(0, fixture.channels.definitions.size());
            var foreign = new SpectrumSearchService.SaveRequest(result.revision(),
                List.of(new SpectrumSearchService.SaveCandidate("foreign", "Other", true)), List.of());
            assertThrows(IllegalArgumentException.class, () -> fixture.service.save(result.jobId(), foreign));
            assertEquals(0, fixture.channels.definitions.size());
        }
    }

    @Test
    void selectedFirstStartsFirstAndDistantSavedRowsNeverRetuneOrFallback() throws Exception
    {
        try(Fixture fixture = new Fixture(A, B, FAR))
        {
            var completed = fixture.complete(fixture.open().jobId());
            var saved = fixture.service.save(completed.jobId(), saveRequest(completed, Map.of()));
            int scanTunes = fixture.lease.centers.size();
            var started = fixture.service.start(saved.jobId(), saved.candidates().stream()
                .map(SpectrumSearchService.Candidate::candidateId).toList(), row(saved, B).candidateId());
            assertEquals(List.of(row(saved, B).configurationId(), row(saved, A).configurationId()), fixture.channels.starts);
            assertTrue(row(started, B).running());
            assertTrue(row(started, A).running());
            assertFalse(row(started, FAR).running());
            assertTrue(row(started, FAR).startError().contains("outside"));
            assertEquals(1, fixture.lease.handoffs.get());
            assertEquals(scanTunes, fixture.lease.centers.size());
            assertEquals(B + 100000, fixture.lease.center);
            // Terminal handoff permits configuration retry and strictly bounded start retry, but cannot retune.
            fixture.service.start(saved.jobId(), List.of(row(saved, B).candidateId(), row(saved, A).candidateId()), null);
            assertEquals(2, fixture.channels.starts.size());
            assertEquals(1, fixture.lease.handoffs.get());
            var repeated = fixture.service.save(saved.jobId(), saveRequest(started, Map.of()));
            assertEquals(3, fixture.channels.definitions.size());
            assertEquals(3, repeated.candidates().size());
        }
    }

    @Test
    void saveMustFinishBeforeStartingAndOldEvidenceCannotCreateAChannel() throws Exception
    {
        try(Fixture fixture = new Fixture(A))
        {
            var completed = fixture.complete(fixture.open().jobId());
            assertThrows(IllegalStateException.class, () -> fixture.service.start(completed.jobId(),
                List.of(completed.candidates().getFirst().candidateId()), null));
            // Keep the review session alive while scan evidence itself becomes too old.
            for(int step = 0; step < 16; step++)
            {
                fixture.clock.addAndGet(59000);
                fixture.service.status(completed.jobId());
            }
            var result = fixture.service.save(completed.jobId(), saveRequest(completed, Map.of()));
            assertFalse(result.candidates().getFirst().saved());
            assertTrue(result.candidates().getFirst().saveError().contains("too old"));
            assertEquals(0, fixture.channels.definitions.size());
        }
    }

    @Test
    void cancellationAndShutdownWaitForTheWorkerToReleaseReceiverOwnership() throws Exception
    {
        try(Fixture fixture = new Fixture(A))
        {
            fixture.lease.blockObserve = new CountDownLatch(1);
            var opened = fixture.open();
            assertTrue(fixture.lease.observing.await(2, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, fixture::open);
            fixture.service.cancel(opened.jobId());
            assertFalse(fixture.lease.valid());
            assertTrue(fixture.lease.closed.get() > 0);
            assertEquals(0, fixture.channels.definitions.size());
            assertThrows(IllegalStateException.class, () -> fixture.service.status(opened.jobId()));
        }
        try(Fixture fixture = new Fixture(A))
        {
            fixture.lease.blockObserve = new CountDownLatch(1);
            fixture.open();
            assertTrue(fixture.lease.observing.await(2, TimeUnit.SECONDS));
            fixture.service.closeActiveSession();
            assertFalse(fixture.lease.valid());
        }
    }

    @Test
    void shutdownWaitsForAnInFlightSaveBeforeReleasingTheLease() throws Exception
    {
        try(Fixture fixture = new Fixture(A))
        {
            var completed = fixture.complete(fixture.open().jobId());
            fixture.channels.createEntered = new CountDownLatch(1);
            fixture.channels.createRelease = new CountDownLatch(1);
            Thread save = new Thread(() -> fixture.service.save(completed.jobId(), saveRequest(completed, Map.of())));
            save.start();
            assertTrue(fixture.channels.createEntered.await(2, TimeUnit.SECONDS));
            Thread shutdown = new Thread(fixture.service::closeActiveSession);
            shutdown.start();
            shutdown.join(100);
            assertTrue(shutdown.isAlive());
            assertTrue(fixture.lease.valid());
            fixture.channels.createRelease.countDown();
            save.join(2000); shutdown.join(2000);
            assertFalse(save.isAlive()); assertFalse(shutdown.isAlive());
            assertFalse(fixture.lease.valid());
            assertEquals(1, fixture.channels.definitions.size());
        }
    }

    @Test
    void receiverLossAfterSaveRetainsThePublishedCommitLedger() throws Exception
    {
        try(Fixture fixture = new Fixture(A, B))
        {
            var completed = fixture.complete(fixture.open().jobId());
            var saved = fixture.service.save(completed.jobId(), saveRequest(completed, Map.of()));
            fixture.lease.valid = false;
            var failed = fixture.service.status(saved.jobId());
            assertEquals("failed", failed.phase());
            assertEquals(2, failed.candidates().size());
            assertTrue(failed.candidates().stream().allMatch(SpectrumSearchService.Candidate::saved));
            assertEquals(saved.candidates().stream().map(SpectrumSearchService.Candidate::configurationId).toList(),
                failed.candidates().stream().map(SpectrumSearchService.Candidate::configurationId).toList());
            assertEquals(1, failed.aliasGroups().size());
            assertFalse(failed.candidates().stream().anyMatch(SpectrumSearchService.Candidate::running));
        }
    }

    @Test
    void committedPublicationFailureRetainsTheRealSavedIdAndBlocksEveryRetryAndStart() throws Exception
    {
        try(Fixture fixture = new Fixture(A, B))
        {
            var completed = fixture.complete(fixture.open().jobId());
            fixture.channels.publicationFailureAt = A;
            var failure = assertThrows(ConfigurationManager.ConfigurationPublicationException.class,
                () -> fixture.service.save(completed.jobId(), saveRequest(completed, Map.of(A, false))));
            var ledger = fixture.service.status(completed.jobId());
            assertTrue(ledger.restartRequired());
            assertTrue(row(ledger, A).saved());
            assertEquals(failure.committedConfigurationId(), row(ledger, A).configurationId());
            assertEquals(failure.committedAliasListId(), row(ledger, A).aliasListId());
            assertEquals(Boolean.FALSE, row(ledger, A).autoStart());
            assertEquals("Site 1", row(ledger, A).name());
            assertFalse(row(ledger, B).saved());
            assertTrue(row(ledger, B).saveError().startsWith("Not added"));
            assertThrows(ConfigurationManager.ConfigurationPublicationException.class,
                () -> fixture.service.save(completed.jobId(), saveRequest(ledger, Map.of())));
            assertThrows(ConfigurationManager.ConfigurationPublicationException.class,
                () -> fixture.service.start(completed.jobId(), List.of(row(ledger, A).candidateId()), null));
            assertEquals(1, fixture.channels.definitions.size());
            assertEquals(1, fixture.channels.createdAliases);
            assertTrue(fixture.channels.starts.isEmpty());
        }
    }

    @Test
    void suspendedPreCommitFailureDoesNotInventASavedChannel() throws Exception
    {
        try(Fixture fixture = new Fixture(A))
        {
            var completed = fixture.complete(fixture.open().jobId());
            fixture.channels.suspendedAt = A;
            var failure = assertThrows(ConfigurationManager.ConfigurationPublicationException.class,
                () -> fixture.service.save(completed.jobId(), saveRequest(completed, Map.of())));
            assertNull(failure.committedConfigurationId());
            var ledger = fixture.service.status(completed.jobId());
            assertTrue(ledger.restartRequired());
            assertFalse(row(ledger, A).saved());
            assertNull(row(ledger, A).configurationId());
            assertTrue(ledger.reason().startsWith("Nothing was added"));
            assertTrue(fixture.channels.definitions.isEmpty());
        }
    }

    @Test
    void concurrentCancellationAndListenerShutdownCloseTheReceiverLeaseOnlyOnce() throws Exception
    {
        try(Fixture fixture = new Fixture(A))
        {
            var completed = fixture.complete(fixture.open().jobId());
            fixture.lease.closeEntered = new CountDownLatch(1);
            fixture.lease.closeRelease = new CountDownLatch(1);
            Thread cancel = new Thread(() -> fixture.service.cancel(completed.jobId()));
            cancel.start();
            assertTrue(fixture.lease.closeEntered.await(2, TimeUnit.SECONDS));
            Thread shutdown = new Thread(fixture.service::closeActiveSession);
            shutdown.start();
            shutdown.join(100);
            assertTrue(shutdown.isAlive());
            assertEquals(1, fixture.lease.closed.get());
            fixture.lease.closeRelease.countDown();
            cancel.join(2000); shutdown.join(2000);
            assertFalse(cancel.isAlive()); assertFalse(shutdown.isAlive());
            assertEquals(1, fixture.lease.closed.get());
        }
    }

    @Test
    void failedCancellationCleanupCanRetryTheSameJobWithoutLosingSavedChannels() throws Exception
    {
        try(Fixture fixture = new Fixture(A))
        {
            var completed = fixture.complete(fixture.open().jobId());
            fixture.lease.scanWorker.join(2000);
            assertFalse(fixture.lease.scanWorker.isAlive());
            var request = saveRequest(completed, Map.of());
            var saved = fixture.service.save(completed.jobId(), request);
            fixture.lease.closeFailures.set(1);
            try
            {
                assertThrows(IllegalStateException.class, () -> fixture.service.cancel(saved.jobId()));
                assertEquals(1, fixture.lease.closed.get());
                assertTrue(fixture.lease.valid());
                assertThrows(SpectrumSearchService.SearchExpiredException.class,
                    () -> fixture.service.cancel("another-search"));
                assertEquals(1, fixture.lease.closed.get(), "A foreign ID must not retry another job's cleanup");
                assertThrows(SpectrumSearchService.SearchExpiredException.class,
                    () -> fixture.service.status(saved.jobId()));
                assertThrows(SpectrumSearchService.SearchExpiredException.class,
                    () -> fixture.service.save(saved.jobId(), request));
                assertThrows(SpectrumSearchService.SearchExpiredException.class,
                    () -> fixture.service.start(saved.jobId(),
                        List.of(saved.candidates().getFirst().candidateId()), null));

                assertDoesNotThrow(() -> fixture.service.cancel(saved.jobId()));
                assertEquals(2, fixture.lease.closed.get());
                assertFalse(fixture.lease.valid());
                assertEquals(1, fixture.channels.definitions.size());
                assertEquals(1, fixture.channels.createdAliases);
                assertTrue(fixture.channels.starts.isEmpty());
            }
            finally
            {
                fixture.lease.closeFailures.set(0);
                if(fixture.lease.valid()) fixture.lease.close();
            }
        }
    }

    @Test
    void failedListenerShutdownCleanupRetriesTheReceiverLease() throws Exception
    {
        try(Fixture fixture = new Fixture(A))
        {
            fixture.complete(fixture.open().jobId());
            fixture.lease.scanWorker.join(2000);
            assertFalse(fixture.lease.scanWorker.isAlive());
            fixture.lease.closeFailures.set(1);
            try
            {
                assertThrows(IllegalStateException.class, fixture.service::closeActiveSession);
                assertEquals(1, fixture.lease.closed.get());
                assertTrue(fixture.lease.valid());

                assertDoesNotThrow(fixture.service::closeActiveSession);
                assertEquals(2, fixture.lease.closed.get());
                assertFalse(fixture.lease.valid());
                assertTrue(fixture.channels.definitions.isEmpty());
                assertTrue(fixture.channels.starts.isEmpty());
            }
            finally
            {
                fixture.lease.closeFailures.set(0);
                if(fixture.lease.valid()) fixture.lease.close();
            }
        }
    }

    @Test
    void boundedWindowsCoverCenterNotchAndDoNotSilentlyTruncateRequestedBands()
    {
        var ranges = List.of(new SpectrumSearchService.Range(138_000_000, 174_000_000));
        var centers = SpectrumSearchService.windows(ranges, 2_000_000, 20_000_000, 1_000_000_000);
        for(long frequency = 138_000_000; frequency <= 174_000_000; frequency += 12500)
        {
            final long signal = frequency;
            assertTrue(centers.stream().anyMatch(center -> FrequencyListenService.withinCurrentWindow(signal,
                center, 12500, 1_000_000, 25000)), "Uncovered signal: " + signal);
        }
        var tiny = List.of(new SpectrumSearchService.Range(770_000_000, 770_010_000));
        assertTrue(SpectrumSearchService.windows(tiny, 2_000_000, 20_000_000, 1_000_000_000).size() >= 2);
        assertThrows(IllegalArgumentException.class, () -> SpectrumSearchService.windows(ranges, 100_000,
            20_000_000, 1_000_000_000));
        assertThrows(IllegalArgumentException.class, () -> SpectrumSearchService.validateRanges(
            List.of(new SpectrumSearchService.Range(1, 200_000_000))));
        assertThrows(IllegalArgumentException.class, () -> SpectrumSearchService.windows(ranges, 2_000_000,
            150_000_000, 1_000_000_000));
        var ceiling = List.of(new SpectrumSearchService.Range(999_990_000, 1_000_000_000));
        var ceilingCenters = SpectrumSearchService.windows(ceiling, 2_000_000, 20_000_000, 1_000_000_000);
        for(long frequency = 999_990_000; frequency <= 1_000_000_000; frequency += 1250)
        {
            final long signal = frequency;
            assertTrue(ceilingCenters.stream().anyMatch(center -> FrequencyListenService.withinCurrentWindow(signal,
                center, 12500, 1_000_000, 25000)), "Uncovered hardware-ceiling signal: " + signal);
        }
    }

    @Test
    void selected700And800BandsHaveOrderedCompleteWindowsAndRespectConfiguredLimits()
    {
        var band700 = new SpectrumSearchService.Range(769_000_000, 775_000_000);
        var band800 = new SpectrumSearchService.Range(851_000_000, 869_000_000);
        var ranges = List.of(band700, band800);
        SpectrumSearchService.validateRanges(ranges);
        var expected = List.of(771_250_000L, 774_850_000L, 778_450_000L,
            853_250_000L, 856_850_000L, 860_450_000L, 864_050_000L, 867_650_000L,
            871_250_000L, 874_850_000L);
        var centers = SpectrumSearchService.windows(ranges, 9_000_000, 24_000_000, 1_800_000_000);
        assertEquals(expected, centers);
        assertTrue(centers.stream().allMatch(center -> center >= 24_000_000 && center <= 1_800_000_000));
        for(var range: ranges)
        {
            for(long frequency = range.minimumHz(); frequency <= range.maximumHz(); frequency += 12500)
            {
                final long signal = frequency;
                assertTrue(centers.stream().anyMatch(center -> FrequencyListenService.withinCurrentWindow(signal,
                    center, 12500, 4_500_000, 25000)), "Uncovered selected-band signal: " + signal);
            }
        }
        var reversed = new ArrayList<>(expected.subList(3, expected.size()));
        reversed.addAll(expected.subList(0, 3));
        assertEquals(reversed, SpectrumSearchService.windows(List.of(band800, band700), 9_000_000,
            24_000_000, 1_800_000_000));

        // The 9 MHz passband does not expand the receiver's configured tuning limits.
        var limited700 = SpectrumSearchService.windows(List.of(band700), 9_000_000, 767_600_000, 777_600_000);
        assertEquals(List.of(771_250_000L, 774_850_000L, 775_350_000L), limited700);
        assertThrows(IllegalArgumentException.class, () -> SpectrumSearchService.windows(List.of(band800),
            9_000_000, 767_600_000, 777_600_000));
        assertThrows(IllegalArgumentException.class, () -> SpectrumSearchService.windows(ranges,
            9_000_000, 767_600_000, 777_600_000));
    }

    @Test
    void selected700And800BandsTuneEveryWindowInBothPassesBeforeCheckingSignals() throws Exception
    {
        long signal800 = 852_400_000;
        var ranges = List.of(new SpectrumSearchService.Range(769_000_000, 775_000_000),
            new SpectrumSearchService.Range(851_000_000, 869_000_000));
        var onePass = List.of(771_250_000L, 774_850_000L, 778_450_000L,
            853_250_000L, 856_850_000L, 860_450_000L, 864_050_000L, 867_650_000L,
            871_250_000L, 874_850_000L);
        var twoPasses = new ArrayList<>(onePass);
        twoPasses.addAll(onePass);
        try(Fixture fixture = new Fixture(A, signal800))
        {
            fixture.lease.usableBandwidth = 9_000_000;
            fixture.lease.sampleRate = 10_000_000;
            fixture.lease.minimum = 24_000_000;
            fixture.lease.maximum = 1_800_000_000;
            CountDownLatch checking = new CountDownLatch(1), release = new CountDownLatch(1);
            fixture.check = (lease, frequency, cancelled) -> {
                checking.countDown();
                release.await();
                return evidence(frequency, frequency == A ? 1 : 2, "C4FM", true);
            };
            var opened = fixture.open(ranges);
            try
            {
                assertTrue(checking.await(2, TimeUnit.SECONDS));
                var checkingSnapshot = fixture.service.status(opened.jobId());
                assertEquals("checking", checkingSnapshot.phase());
                assertEquals(20, checkingSnapshot.progress().total());
                assertEquals(20, checkingSnapshot.progress().completed());
                assertEquals(20, fixture.lease.observations);
                assertEquals(twoPasses, fixture.lease.centers.subList(0, 20));
                assertTrue(checkingSnapshot.candidates().isEmpty());
            }
            finally { release.countDown(); }
            var completed = fixture.complete(opened.jobId());
            assertEquals(2, completed.progress().checked());
            assertEquals(List.of(A, signal800), completed.candidates().stream()
                .map(SpectrumSearchService.Candidate::frequencyHz).sorted().toList());
            assertTrue(fixture.channels.definitions.isEmpty());
        }
    }

    @Test
    void shippedUhfPresetFitsACommonRtlReceiverUsableBandwidth()
    {
        var uhf = List.of(new SpectrumSearchService.Range(406_000_000, 470_000_000));
        // A 2.4 MHz receiver with 80% usable bandwidth must support the shipped UHF preset.
        var centers = SpectrumSearchService.windows(uhf, 1_920_000, 20_000_000, 1_000_000_000);
        assertTrue(centers.size() <= SpectrumSearchService.MAX_WINDOWS);
        assertTrue(centers.size() > 64);
        for(long frequency = 406_000_000; frequency <= 470_000_000; frequency += 12500)
        {
            final long signal = frequency;
            assertTrue(centers.stream().anyMatch(center -> FrequencyListenService.withinCurrentWindow(signal,
                center, 12500, 960_000, 25000)), "Uncovered UHF signal: " + signal);
        }
    }

    @Test
    void expectedObservationFailurePreservesActionableCopyAndReleasesTheReceiver() throws Exception
    {
        try(Fixture fixture = new Fixture(A))
        {
            String reason = "The receiver did not provide enough new spectrum data. Retry the search or choose another receiver.";
            fixture.lease.observationFailure = new SpectrumSearchHardware.ObservationException(reason);
            var opened = fixture.open();
            SpectrumSearchService.Snapshot failed = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while(System.nanoTime() < deadline)
            {
                failed = fixture.service.status(opened.jobId());
                if("failed".equals(failed.phase())) break;
                Thread.sleep(5);
            }
            assertNotNull(failed);
            assertEquals("failed", failed.phase());
            assertEquals(reason, failed.reason());
            assertEquals(0, failed.progress().completed());
            assertTrue(failed.candidates().isEmpty());
            fixture.service.closeActiveSession();
            assertEquals(1, fixture.lease.closed.get());
            assertFalse(fixture.lease.valid());
            assertTrue(fixture.channels.definitions.isEmpty());
        }
    }

    private static SpectrumSearchService.Candidate row(SpectrumSearchService.Snapshot snapshot, long frequency)
    { return snapshot.candidates().stream().filter(row -> row.frequencyHz() == frequency).findFirst().orElseThrow(); }

    private static SpectrumSearchService.SaveRequest saveRequest(SpectrumSearchService.Snapshot snapshot, Map<Long,Boolean> auto)
    {
        return new SpectrumSearchService.SaveRequest(snapshot.revision(), snapshot.candidates().stream().map(row ->
            new SpectrumSearchService.SaveCandidate(row.candidateId(), "Site " + row.identity().site(), auto.getOrDefault(row.frequencyHz(), true))).toList(),
            snapshot.aliasGroups().stream().map(group -> new SpectrumSearchService.AliasChoice(group.groupId(), 0, "Test network")).toList());
    }

    private static P25DiscoveryProbe.Status evidence(long frequency, int site, String modulation, boolean confirmed)
    {
        var identity = new P25DiscoveryProbe.Identity(0xBEE00, 0x348, 2, site, 0x348);
        var mode = new P25DiscoveryProbe.ModeMetrics(80, 40, 2, 0, 0, 4, 4, 95, confirmed, identity, null);
        var absent = new P25DiscoveryProbe.ModeMetrics(0, 0, 0, 0, 0, 0, 0, 0, false, null, null);
        return new P25DiscoveryProbe.Status("ready", null, "test-target", frequency, 10000, 1000, 30000, 0,
            "C4FM".equals(modulation) ? mode : absent, "CQPSK".equals(modulation) ? mode : absent,
            new P25DiscoveryProbe.SignalMetrics(10000, -31.0, 0.2), modulation, identity);
    }

    private static P25DiscoveryProbe.Status mismatchedEvidence(long frequency)
    {
        var valid = evidence(frequency, 2, "C4FM", true);
        return new P25DiscoveryProbe.Status(valid.state(), null, valid.targetId(), frequency, valid.startedAtMs(),
            valid.elapsedMs(), valid.timeoutMs(), 0, valid.c4fm(), valid.cqpsk(), valid.signal(), "C4FM",
            new P25DiscoveryProbe.Identity(0xBEE00, 0x349, 2, 2, 0x348));
    }

    private static final class Fixture implements AutoCloseable
    {
        final AtomicLong clock = new AtomicLong(10000);
        final FakeLease lease;
        final FakeChannels channels = new FakeChannels();
        SpectrumSearchService.ProbeCheck check = (lease, frequency, cancelled) -> evidence(frequency,
            frequency == A ? 1 : frequency == B ? 2 : 3, "C4FM", true);
        SpectrumSearchService service;
        Fixture(long... frequencies) { lease = new FakeLease(frequencies); }
        SpectrumSearchService.Snapshot open()
        { return open(RANGES); }
        SpectrumSearchService.Snapshot open(List<SpectrumSearchService.Range> ranges)
        {
            if(service == null) service = new SpectrumSearchService(channels, (tuner, browse) -> lease,
                (lease, frequency, cancelled) -> check.check(lease, frequency, cancelled), clock::get,
                () -> new SpectrumSearchService.Catalog(List.of(), null, List.of(), null));
            return service.open("test-receiver", "test-browse", ranges, 750);
        }
        SpectrumSearchService.Snapshot complete(String id) throws Exception
        {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            SpectrumSearchService.Snapshot snapshot;
            do
            {
                snapshot = service.status(id);
                if("complete".equals(snapshot.phase())) return snapshot;
                if("failed".equals(snapshot.phase())) fail(snapshot.reason());
                Thread.sleep(5);
            } while(System.nanoTime() < deadline);
            throw new AssertionError("Search did not finish");
        }
        public void close() { if(service != null) service.close(); }
    }

    private static final class FakeLease implements SpectrumSearchHardware.Lease
    {
        final long[] frequencies;
        final List<Long> centers = new ArrayList<>();
        final AtomicInteger closed = new AtomicInteger(), handoffs = new AtomicInteger();
        final AtomicInteger closeFailures = new AtomicInteger();
        final CountDownLatch observing = new CountDownLatch(1);
        volatile Thread scanWorker;
        volatile CountDownLatch blockObserve;
        RuntimeException observationFailure;
        CountDownLatch closeEntered, closeRelease;
        volatile boolean valid = true;
        long center = 773_000_000, transientFrequency;
        long usableBandwidth = 4_000_000, sampleRate = 5_000_000, minimum = 20_000_000, maximum = 1_000_000_000;
        int observations;
        FakeLease(long... frequencies) { this.frequencies = frequencies; }
        public Tuner tuner() { return null; }
        public String targetId() { return "test-target"; }
        public long usableBandwidthHz() { return usableBandwidth; }
        public long sampleRateHz() { return sampleRate; }
        public long middleUnusableHalfBandwidthHz() { return 12500; }
        public long minimumFrequencyHz() { return minimum; }
        public long maximumFrequencyHz() { return maximum; }
        public long centerFrequencyHz() { return center; }
        public void tune(long frequency) { assertTrue(valid); center = frequency; centers.add(frequency); }
        public List<SpectrumPeakDetector.Peak> observe(long dwell, BooleanSupplier cancelled) throws InterruptedException
        {
            scanWorker = Thread.currentThread();
            observing.countDown();
            if(blockObserve != null) blockObserve.await();
            if(observationFailure != null) throw observationFailure;
            observations++;
            // Each pass has five windows for this fixture range/bandwidth.
            List<SpectrumPeakDetector.Peak> result = new ArrayList<>();
            for(long frequency: frequencies)
                if(FrequencyListenService.withinCurrentWindow(frequency, center, 12500, usableBandwidthHz()/2, 12500) &&
                    (frequency != transientFrequency || observations <= 5))
                    result.add(new SpectrumPeakDetector.Peak(frequency, -35 - (frequency - A) / 1000000.0, 15, 10));
            return result;
        }
        public P25DiscoveryProbe.Session probe(long frequency) { throw new UnsupportedOperationException(); }
        public boolean valid() { return valid; }
        public <T> T handoff(long frequency, Supplier<T> start)
        { handoffs.incrementAndGet(); center = frequency; try { return start.get(); } finally { valid = false; } }
        public void close()
        {
            closed.incrementAndGet();
            if(closeFailures.getAndUpdate(remaining -> Math.max(0, remaining - 1)) > 0)
                throw new IllegalStateException("Temporary receiver cleanup failure");
            if(closeEntered != null)
            {
                closeEntered.countDown();
                try { closeRelease.await(); }
                catch(InterruptedException exception) { Thread.currentThread().interrupt(); }
            }
            valid = false;
        }
    }

    private static final class FakeChannels implements SpectrumSearchService.Channels
    {
        long revision = 1, failOnce, staleAt, publicationFailureAt, suspendedAt;
        int createdAliases;
        final Map<String,Long> aliases = new LinkedHashMap<>();
        final Map<Long,SpectrumSearchService.KnownChannel> knownFrequencies = new LinkedHashMap<>();
        final Map<P25SiteIdentity,SpectrumSearchService.KnownChannel> knownSites = new LinkedHashMap<>();
        final List<ChannelDefinition> definitions = new ArrayList<>();
        final List<P25SiteIdentity> identities = new ArrayList<>();
        final List<String> starts = new ArrayList<>();
        CountDownLatch createEntered, createRelease;
        public long revision() { return revision; }
        public SpectrumSearchService.KnownChannel known(long frequency) { return knownFrequencies.get(frequency); }
        public SpectrumSearchService.KnownChannel knownSite(P25SiteIdentity identity) { return knownSites.get(identity); }
        private String key(P25SiteIdentity identity) { return identity.wacn() + ":" + identity.system(); }
        public ChannelAdministrationService.DiscoveryReview review(long frequency, String preferred,
            P25SiteIdentity identity, String modulation)
        {
            Long alias = aliases.get(key(identity));
            var template = new ChannelDefinition(null, "p25-phase1", "Friendly network", "Site " + identity.site(), "Probe",
                null, alias != null ? alias : 0, new ChannelDefinition.Source(List.of(frequency), null, null, frequency, preferred, null),
                Map.of("modulation", modulation, "learn_announced_control_channels", true), List.of(), List.of(), List.of(), List.of(), null);
            return new ChannelAdministrationService.DiscoveryReview(revision, template,
                alias == null ? List.of() : List.of(new ChannelAdministrationService.DiscoveryAliasList(alias, "Test network", true)),
                alias, "Test network");
        }
        public ChannelAdministrationService.DiscoveryCreated create(ChannelDefinition definition, P25SiteIdentity identity,
            String aliasName, long expected, boolean autoStart)
        {
            if(createEntered != null)
            {
                createEntered.countDown();
                try { createRelease.await(); } catch(InterruptedException exception) { throw new IllegalStateException(exception); }
            }
            long frequency = definition.source().frequenciesHz().getFirst();
            if(frequency == suspendedAt) throw new ConfigurationManager.ConfigurationPublicationException("Saving is suspended");
            if(frequency == failOnce) { failOnce = 0; throw new IllegalStateException("Temporary save failure"); }
            if(frequency == staleAt) { revision++; throw new ChannelAdministrationService.StaleRevisionException(expected, revision); }
            assertEquals(revision, expected);
            long aliasId = definition.aliasListId();
            if(aliasId == 0) { aliasId = 100 + ++createdAliases; aliases.put(key(identity), aliasId); }
            definitions.add(definition); identities.add(identity); revision++;
            if(frequency == publicationFailureAt) throw new ConfigurationManager.ConfigurationPublicationException(
                "Committed but not published", new IllegalStateException("Test publication failure"),
                "saved-" + definitions.size(), aliasId);
            return new ChannelAdministrationService.DiscoveryCreated("saved-" + definitions.size(), aliasId);
        }
        public ChannelAdministrationService.LifecycleResult start(String id, String tunerId, Tuner runtime, boolean handoff)
        {
            assertEquals("test-receiver", tunerId);
            starts.add(id);
            return new ChannelAdministrationService.LifecycleResult(id, true, ChannelAdministrationService.ProcessingState.RUNNING, null);
        }
    }
}
