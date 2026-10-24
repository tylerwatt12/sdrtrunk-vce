/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/** Exercises the search coordinator's observation budget without hardware or wall-clock waits. */
class SpectrumSearchCheckTest
{
    private static final long FREQUENCY = 770_100_000;
    private static final P25DiscoveryProbe.Identity P25_ID =
        new P25DiscoveryProbe.Identity(0xBEE00, 0x348, 2, 7, 0x348);
    private static final P25DiscoveryProbe.ModeMetrics EMPTY = p25Mode(0, 0, 0);

    @Test
    void voiceAndInvalidControlMessagesDoNotExtendEightSecondTrial() throws Exception
    {
        var voice = p25Mode(500, 0, 200);
        var digitalVoice = digitalMode("DMR", 500, 0, 200);
        var p25 = new Script<>(p25("running", 7_999, voice, voice, 0),
            p25("running", 8_000, voice, voice, 0));
        var digital = new Script<>(digital("running", 7_999, 0, null, digitalVoice),
            digital("running", 8_000, 0, null, digitalVoice));

        assertEmpty(SpectrumSearchService.checkTrunked(p25, digital, () -> false));
        p25.assertConsumed();
        digital.assertConsumed();
    }

    @Test
    void eachRunningProbeGetsItsOwnFullInitialTrial() throws Exception
    {
        var p25 = new Script<>(runningP25(8_000), runningP25(30_000), runningP25(30_000));
        var digital = new Script<>(runningDigital(3_000), runningDigital(7_999), runningDigital(8_000));

        assertEmpty(SpectrumSearchService.checkTrunked(p25, digital, () -> false));
        p25.assertConsumed();
        digital.assertConsumed();
    }

    @Test
    void terminalProbeDoesNotPreventOtherProbeFromFinishingItsTrial() throws Exception
    {
        var p25 = new Script<>(p25("failed", 100, EMPTY, EMPTY, 0),
            p25("failed", 100, EMPTY, EMPTY, 0));
        var digital = new Script<>(runningDigital(7_999), runningDigital(8_000));
        assertEmpty(SpectrumSearchService.checkTrunked(p25, digital, () -> false));
        p25.assertConsumed();
        digital.assertConsumed();

        var runningP25 = new Script<>(runningP25(7_999), runningP25(8_000));
        var failedDigital = new Script<>(digital("failed", 100, 0, null),
            digital("failed", 100, 0, null));
        assertEmpty(SpectrumSearchService.checkTrunked(runningP25, failedDigital, () -> false));
        runningP25.assertConsumed();
        failedDigital.assertConsumed();
    }

    @Test
    void absentOptionalDigitalProbeDoesNotBlockCutoff() throws Exception
    {
        var p25 = new Script<>(runningP25(7_999), runningP25(8_000));
        assertEmpty(SpectrumSearchService.checkTrunked(p25, () -> null, () -> false));
        p25.assertConsumed();
    }

    @Test
    void validControlInEitherP25ModulationKeepsFullOpportunityAfterCountersReset() throws Exception
    {
        for(boolean cqpsk : List.of(false, true))
        {
            var control = p25Mode(1, 1, 0);
            var p25 = new Script<>(p25("running", 8_000, cqpsk ? EMPTY : control,
                cqpsk ? control : EMPTY, 0), runningP25(8_000), runningP25(29_999),
                p25("inconclusive", 30_000, EMPTY, EMPTY, 0));

            assertEmpty(SpectrumSearchService.checkTrunked(p25, () -> null, () -> false));
            p25.assertConsumed();
        }
    }

    @Test
    void validControlInEveryDigitalModeKeepsFullOpportunityAfterCountersReset() throws Exception
    {
        for(String mode : List.of("DMR", "M4800", "M9600", "TYPE_D"))
        {
            var p25 = new Script<>(runningP25(8_000), runningP25(8_000), runningP25(29_999),
                p25("inconclusive", 30_000, EMPTY, EMPTY, 0));
            var digital = new Script<>(digital("running", 8_000, 0, null, digitalMode(mode, 1, 1, 0)),
                runningDigital(8_000), runningDigital(29_999), digital("inconclusive", 30_000, 0, null));

            assertEmpty(SpectrumSearchService.checkTrunked(p25, digital, () -> false));
            p25.assertConsumed();
            digital.assertConsumed();
        }
    }

    @Test
    void droppedSamplesInEitherProbeConservativelyKeepFullOpportunity() throws Exception
    {
        for(boolean digitalDropped : List.of(false, true))
        {
            var p25 = new Script<>(p25("running", 8_000, EMPTY, EMPTY, digitalDropped ? 0 : 1),
                runningP25(8_000), p25("inconclusive", 30_000, EMPTY, EMPTY, 0));
            var digital = new Script<>(digital("running", 8_000, digitalDropped ? 1 : 0, null,
                digitalMode("DMR", 0, 0, 0)), runningDigital(8_000), digital("inconclusive", 30_000, 0, null));

            assertEmpty(SpectrumSearchService.checkTrunked(p25, digital, () -> false));
            p25.assertConsumed();
            digital.assertConsumed();
        }
    }

    @Test
    void missingP25MetricsWaitForDecoderMetricsBeforeEarlyRejection() throws Exception
    {
        for(int missing = 0; missing < 3; missing++)
        {
            var p25 = new Script<>(p25("running", 8_000, missing == 1 ? EMPTY : null,
                missing == 0 ? EMPTY : null, 0), runningP25(8_000));

            assertEmpty(SpectrumSearchService.checkTrunked(p25, () -> null, () -> false));
            p25.assertConsumed();
        }
    }

    @Test
    void emptyDigitalModesWaitForDecoderMetricsBeforeEarlyRejection() throws Exception
    {
        var p25 = new Script<>(runningP25(8_000), runningP25(8_000));
        var digital = new Script<>(digital("running", 8_000, 0, null), runningDigital(8_000));

        assertEmpty(SpectrumSearchService.checkTrunked(p25, digital, () -> false));
        p25.assertConsumed();
        digital.assertConsumed();
    }

    @Test
    void readyP25AtCutoffWinsAndPreservesQualityAndStrength() throws Exception
    {
        var p25 = new Script<>(readyP25(8_000, "C4FM", 3));
        var digital = new Script<>(runningDigital(8_000));

        var result = SpectrumSearchService.checkTrunked(p25, digital, () -> false);
        assertEquals("p25-phase1", result.evidence().protocolId());
        assertEquals("C4FM", result.evidence().settings().get("modulation"));
        assertEquals(3, result.evidence().qualityPct());
        assertTrue(result.evidence().verified(), "P25 retains its existing acceptance of a low finite score");
        assertEquals(-65.5, result.strengthDbfs());
        p25.assertConsumed();
        digital.assertConsumed();
    }

    @Test
    void lateP25ConfirmationSurvivesInitialControlEvidenceThenMetricReset() throws Exception
    {
        var p25 = new Script<>(p25("running", 100, EMPTY, p25Mode(1, 1, 0), 0),
            runningP25(8_000), readyP25(29_999, "CQPSK", 82));

        var result = SpectrumSearchService.checkTrunked(p25, () -> null, () -> false);
        assertEquals("CQPSK", result.evidence().settings().get("modulation"));
        assertEquals(82, result.evidence().qualityPct());
        assertTrue(result.evidence().verified());
        p25.assertConsumed();
    }

    @Test
    void lateDmrAndNxdnConfirmationKeepEvidenceAndQualityUnchanged() throws Exception
    {
        for(String protocol : List.of("dmr", "nxdn"))
        {
            String mode = "dmr".equals(protocol) ? "DMR" : "M4800";
            var evidence = digitalEvidence(protocol, 60);
            assertTrue(evidence.verified());
            var p25 = new Script<>(runningP25(100), runningP25(8_000), runningP25(29_999));
            var digital = new Script<>(digital("running", 100, 0, null, digitalMode(mode, 1, 1, 0)),
                runningDigital(8_000), digital("ready", 29_999, 0, evidence));

            var result = SpectrumSearchService.checkTrunked(p25, digital, () -> false);
            assertSame(evidence, result.evidence());
            assertEquals(60, result.evidence().qualityPct());
            assertNull(result.strengthDbfs());
            p25.assertConsumed();
            digital.assertConsumed();
        }
    }

    @Test
    void simultaneousReadyProtocolsRemainAmbiguousAtCutoff() throws Exception
    {
        var p25 = new Script<>(readyP25(8_000, "C4FM", 95));
        var digital = new Script<>(digital("ready", 8_000, 0, digitalEvidence("dmr", 95)));

        assertEmpty(SpectrumSearchService.checkTrunked(p25, digital, () -> false));
        p25.assertConsumed();
        digital.assertConsumed();
    }

    @Test
    void bothTerminalProbesFinishImmediatelyEvenWithEarlierControlOrDropEvidence() throws Exception
    {
        var p25 = new Script<>(p25("failed", 100, p25Mode(1, 1, 0), EMPTY, 1));
        var digital = new Script<>(digital("inconclusive", 100, 0, null));

        assertEmpty(SpectrumSearchService.checkTrunked(p25, digital, () -> false));
        p25.assertConsumed();
        digital.assertConsumed();
    }

    @Test
    void cancellationBeforeAndDuringObservationIsPreserved()
    {
        assertThrows(InterruptedException.class, () -> SpectrumSearchService.checkTrunked(
            () -> { throw new AssertionError("A cancelled check must not poll"); }, () -> null, () -> true));

        var p25 = new Script<>(runningP25(100));
        AtomicInteger cancellationPolls = new AtomicInteger();
        assertThrows(InterruptedException.class, () -> SpectrumSearchService.checkTrunked(p25, () -> null,
            () -> cancellationPolls.incrementAndGet() > 1));
        p25.assertConsumed();
    }

    @Test
    void sharedDetectorReturnsTheConfirmedProtocolAndBothFinalSnapshotsForSingleFrequencyReview() throws Exception
    {
        for(String protocol : List.of("dmr", "nxdn"))
        {
            var runningP25 = runningP25(100);
            var confirmed = digitalEvidence(protocol, 87);
            var readyDigital = digital("ready", 2_500, 0, confirmed);
            var detected = TrunkedSignalDetector.check(() -> runningP25, () -> readyDigital, () -> false);

            assertSame(confirmed, detected.evidence());
            assertEquals(protocol, detected.evidence().protocolId());
            assertSame(runningP25, detected.p25());
            assertSame(readyDigital, detected.digital());
            assertNull(detected.strengthDbfs());
        }
    }

    @Test
    void sharedDetectorPreservesFinalAmbiguousSnapshotsWithoutChoosingAProtocol() throws Exception
    {
        var p25 = readyP25(2_500, "C4FM", 90);
        var digital = digital("ready", 2_500, 0, digitalEvidence("dmr", 90));
        var result = TrunkedSignalDetector.check(() -> p25, () -> digital, () -> false);

        assertNull(result.evidence());
        assertSame(p25, result.p25());
        assertSame(digital, result.digital());
    }

    private static void assertEmpty(SpectrumSearchService.ProbeResult result)
    {
        assertNull(result.evidence());
        assertNull(result.strengthDbfs());
    }

    private static P25DiscoveryProbe.Status runningP25(long elapsed)
    {
        return p25("running", elapsed, EMPTY, EMPTY, 0);
    }

    private static P25DiscoveryProbe.Status p25(String state, long elapsed,
        P25DiscoveryProbe.ModeMetrics c4fm, P25DiscoveryProbe.ModeMetrics cqpsk, long dropped)
    {
        return new P25DiscoveryProbe.Status(state, null, "test-target", FREQUENCY, 0, elapsed, 30_000,
            dropped, c4fm, cqpsk, null, null, null);
    }

    private static P25DiscoveryProbe.ModeMetrics p25Mode(long valid, long controls, long invalid)
    {
        return new P25DiscoveryProbe.ModeMetrics(valid, controls, invalid, 0, 0, 0, 0, 0,
            false, null, null);
    }

    private static P25DiscoveryProbe.Status readyP25(long elapsed, String modulation, double quality)
    {
        var confirmed = new P25DiscoveryProbe.ModeMetrics(80, 40, 2, 0, 0, 4, 4, quality,
            true, P25_ID, null, FREQUENCY);
        return new P25DiscoveryProbe.Status("ready", null, "test-target", FREQUENCY, 0, elapsed,
            30_000, 0, "C4FM".equals(modulation) ? confirmed : EMPTY,
            "CQPSK".equals(modulation) ? confirmed : EMPTY,
            new P25DiscoveryProbe.SignalMetrics(10_000, -65.5, 0.1), modulation, P25_ID);
    }

    private static DigitalTrunkedDiscoveryProbe.Status runningDigital(long elapsed)
    {
        return digital("running", elapsed, 0, null, digitalMode("DMR", 0, 0, 0));
    }

    private static DigitalTrunkedDiscoveryProbe.Status digital(String state, long elapsed, long dropped,
        TrunkedDiscoveryEvidence evidence, DigitalTrunkedDiscoveryProbe.ModeMetrics... modes)
    {
        return new DigitalTrunkedDiscoveryProbe.Status(state, null, "test-target", FREQUENCY,
            elapsed, 30_000, dropped, evidence, List.of(modes));
    }

    private static DigitalTrunkedDiscoveryProbe.ModeMetrics digitalMode(String mode, long valid,
        long controls, long invalid)
    {
        return new DigitalTrunkedDiscoveryProbe.ModeMetrics(mode, valid, controls, invalid, 0, 0,
            0, false, null);
    }

    private static TrunkedDiscoveryEvidence digitalEvidence(String protocol, double quality)
    {
        boolean dmr = "dmr".equals(protocol);
        String key = dmr ? RadioSystemKey.dmrTier3("SMALL", 0) : RadioSystemKey.nxdnTypeC("LOCAL", 68);
        var identity = new TrunkedDiscoveryEvidence.Identity(null, key, key + ":5", dmr ? 0 : null,
            dmr ? null : 68, 5, dmr ? "SMALL" : null, dmr ? null : "LOCAL", null, dmr ? null : 5, null);
        return new TrunkedDiscoveryEvidence(protocol, dmr ? "TIER_III" : "TYPE_C", identity,
            Map.of(), List.of(), quality, 80, 40, 2, 29_999, "Test evidence");
    }

    /** Failing on extra polls prevents a broken cutoff from hanging or silently using a terminal fallback. */
    private static final class Script<T> implements Supplier<T>
    {
        private final List<T> statuses;
        private int calls;

        @SafeVarargs
        Script(T... statuses)
        {
            this.statuses = List.of(statuses);
        }

        @Override
        public T get()
        {
            assertTrue(calls < statuses.size(), "The check exceeded its expected observation budget");
            return statuses.get(calls++);
        }

        void assertConsumed()
        {
            assertEquals(statuses.size(), calls, "The check stopped before the expected observation");
        }
    }
}
