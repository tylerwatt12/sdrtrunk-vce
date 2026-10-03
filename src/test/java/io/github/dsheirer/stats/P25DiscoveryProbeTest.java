/*
 * Copyright (C) 2026 Dennis Sheirer
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.message.SyncLossMessage;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.P25FrequencyBand;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.TSBKMessage;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.AdjacentStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.NetworkStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.RFSSStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.UnknownOSPMessage;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;

class P25DiscoveryProbeTest
{
    private static final int WACN = 0xABCDE;
    private static final int SYSTEM = 0x123;
    private static final int NAC = 0x345;
    private static final long FREQUENCY = 770_500_000;

    @Test
    void requiresRepeatedFreshServingBroadcastsAndEnoughControlMessages()
    {
        P25DiscoveryProbe.ModeEvidence evidence = new P25DiscoveryProbe.ModeEvidence(Modulation.C4FM);
        NetworkStatusBroadcast network = network(WACN, SYSTEM, NAC, 1000);
        RFSSStatusBroadcast site = site(SYSTEM, NAC, 2, 7, 1000);
        for(int x = 0; x < 30; x++)
        {
            evidence.receive(network);
            evidence.receive(site);
        }
        assertEquals(1, evidence.metrics().networkObservations());
        assertEquals(1, evidence.metrics().siteObservations());
        assertFalse(evidence.metrics().confirmed());
        assertNotNull(evidence.metrics().identity());

        evidence.receive(network(WACN, SYSTEM, NAC, 2000));
        evidence.receive(site(SYSTEM, NAC, 2, 7, 2000));
        evidence.receive(network(WACN, SYSTEM, NAC, 3000));
        evidence.receive(site(SYSTEM, NAC, 2, 7, 3000));
        assertTrue(evidence.metrics().confirmed());
        assertEquals(new P25DiscoveryProbe.Identity(WACN, SYSTEM, 2, 7, NAC), evidence.metrics().identity());
    }

    @Test
    void exposesOnlyRepeatedResolvedServingCarrierAndClearsItWhenDescriptorChanges()
    {
        P25DiscoveryProbe.ModeEvidence evidence = new P25DiscoveryProbe.ModeEvidence(Modulation.C4FM);
        for(int x = 1; x <= 3; x++)
        {
            evidence.receive(network(WACN, SYSTEM, NAC, x * 1000));
            RFSSStatusBroadcast serving = site(SYSTEM, NAC, 2, 7, x * 1000);
            serving.getChannel().setFrequencyBand(new P25FrequencyBand(0, FREQUENCY, -30_000_000, 6250, 12500, 1));
            evidence.receive(serving);
        }
        for(int x = 0; x < 20; x++) evidence.receive(other(4000 + x));
        assertTrue(evidence.metrics().confirmed());
        assertEquals(FREQUENCY, evidence.metrics().servingControlFrequencyHz());

        RFSSStatusBroadcast changedCarrier = site(SYSTEM, NAC, 2, 7, 5000);
        changedCarrier.getChannel().setFrequencyBand(
            new P25FrequencyBand(0, FREQUENCY + 12500, -30_000_000, 6250, 12500, 1));
        evidence.receive(changedCarrier);
        assertTrue(evidence.metrics().confirmed());
        assertNull(evidence.metrics().servingControlFrequencyHz(), "A single new carrier is insufficient for an exact match");
        evidence.receive(site(SYSTEM, NAC, 2, 7, 6000));
        assertNull(evidence.metrics().servingControlFrequencyHz(), "Unresolved serving descriptors clear old carrier evidence");
        evidence.reset();
        assertNull(evidence.metrics().servingControlFrequencyHz());
    }

    @Test
    void unresolvedServingCarrierDoesNotPreventIdentityConfirmation()
    {
        P25DiscoveryProbe.ModeEvidence evidence = new P25DiscoveryProbe.ModeEvidence(Modulation.CQPSK);
        confirm(evidence, 1000);
        assertTrue(evidence.metrics().confirmed());
        assertNull(evidence.metrics().servingControlFrequencyHz());
    }

    @Test
    void rejectsInvalidProtectedNeighborAndVoiceEvidence()
    {
        P25DiscoveryProbe.ModeEvidence evidence = new P25DiscoveryProbe.ModeEvidence(Modulation.CQPSK);
        for(int x = 1; x <= 5; x++)
        {
            NetworkStatusBroadcast invalid = network(WACN, SYSTEM, NAC, x * 1000);
            invalid.setValid(false);
            evidence.receive(invalid);
            NetworkStatusBroadcast protectedMessage = network(WACN, SYSTEM, NAC, x * 1000);
            protectedMessage.getMessage().set(1);
            evidence.receive(protectedMessage);
            CorrectedBinaryMessage bits = fields(Opcode.OSP_ADJACENT_STATUS_BROADCAST);
            bits.setInt(SYSTEM, IntField.length12(28));
            bits.setInt(2, IntField.length8(40));
            bits.setInt(7, IntField.length8(48));
            AdjacentStatusBroadcast neighbor = new AdjacentStatusBroadcast(
                P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, NAC, x * 1000);
            neighbor.setValid(true);
            evidence.receive(neighbor);
        }
        evidence.receive(new IMessage()
        {
            public long getTimestamp() { return 1000; }
            public boolean isValid() { return true; }
            public Protocol getProtocol() { return Protocol.APCO25; }
            public int getTimeslot() { return 0; }
            public List<io.github.dsheirer.identifier.Identifier> getIdentifiers() { return List.of(); }
        });
        assertEquals(0, evidence.metrics().networkObservations());
        assertEquals(0, evidence.metrics().siteObservations());
        assertNull(evidence.metrics().identity());
        assertFalse(evidence.metrics().confirmed());
        assertEquals(5, evidence.metrics().invalidControlMessages());
    }

    @Test
    void rejectsInconsistentNacAndSystemAndResetsOnIdentityChange()
    {
        P25DiscoveryProbe.ModeEvidence evidence = new P25DiscoveryProbe.ModeEvidence(Modulation.C4FM);
        for(int x = 1; x <= 3; x++)
        {
            evidence.receive(network(WACN, SYSTEM, NAC, x * 1000));
            evidence.receive(site(SYSTEM, NAC + 1, 2, 7, x * 1000));
        }
        assertNull(evidence.metrics().identity());
        evidence.reset();
        for(int x = 1; x <= 3; x++)
        {
            evidence.receive(network(WACN, SYSTEM, NAC, x * 1000));
            evidence.receive(site(SYSTEM + 1, NAC, 2, 7, x * 1000));
        }
        assertNull(evidence.metrics().identity());
        evidence.reset();
        confirm(evidence, 1000);
        assertTrue(evidence.metrics().confirmed());
        evidence.receive(network(WACN + 1, SYSTEM, NAC, 5000));
        assertEquals(1, evidence.metrics().networkObservations());
        assertEquals(0, evidence.metrics().siteObservations());
        assertFalse(evidence.metrics().confirmed());
        assertNull(evidence.metrics().identity());
    }

    @Test
    void scoresFailedControlUnitsCorrectedBitsAndSyncLossRatherThanBareSuccess()
    {
        P25DiscoveryProbe.ModeEvidence good = new P25DiscoveryProbe.ModeEvidence(Modulation.C4FM);
        P25DiscoveryProbe.ModeEvidence poor = new P25DiscoveryProbe.ModeEvidence(Modulation.CQPSK);
        confirm(good, 1000);
        confirm(poor, 1000);
        for(int x = 0; x < 3; x++)
        {
            UnknownOSPMessage invalid = other(4000 + x);
            invalid.setValid(false);
            poor.receive(invalid);
        }
        poor.receive(new SyncLossMessage(5000, 196 * 10, Protocol.APCO25));
        assertTrue(good.metrics().confirmed());
        assertTrue(poor.metrics().qualityPct() < good.metrics().qualityPct());
        assertEquals(Modulation.C4FM, P25DiscoveryProbe.select(good.metrics(), poor.metrics()));
        UnknownOSPMessage corrected = other(6000);
        corrected.getMessage().setCorrectedBitCount(20);
        good.receive(corrected);
        assertEquals(20, good.metrics().correctedBits());
        assertTrue(good.metrics().qualityPct() < 100);
    }

    @Test
    void choosesTheHigherScoreWithoutAMinimumMarginButRefusesExactTiesAndConflictingSites()
    {
        P25DiscoveryProbe.ModeEvidence first = new P25DiscoveryProbe.ModeEvidence(Modulation.C4FM);
        P25DiscoveryProbe.ModeEvidence second = new P25DiscoveryProbe.ModeEvidence(Modulation.CQPSK);
        confirm(first, 1000);
        confirm(second, 1000);
        assertNull(P25DiscoveryProbe.select(first.metrics(), second.metrics()));
        second.receive(new SyncLossMessage(5000, 50, Protocol.APCO25));
        assertTrue(first.metrics().qualityPct() - second.metrics().qualityPct() < 5);
        assertEquals(Modulation.C4FM, P25DiscoveryProbe.select(first.metrics(), second.metrics()));
        second.reset();
        for(int x = 1; x <= 3; x++)
        {
            second.receive(network(WACN, SYSTEM, NAC, x * 1000));
            second.receive(site(SYSTEM, NAC, 2, 8, x * 1000));
        }
        for(int x = 0; x < 20; x++) second.receive(other(5000 + x));
        assertTrue(second.metrics().confirmed());
        assertNull(P25DiscoveryProbe.select(first.metrics(), second.metrics()));
    }

    @Test
    void weakSignalWithThirtyEightValidControlsWinsAndRemainsEligibleToSave() throws Exception
    {
        FakeSource source = new FakeSource();
        AtomicLong clock = new AtomicLong(1000);
        try(P25DiscoveryProbe probe = new P25DiscoveryProbe((target, frequency) -> source,
            (modulation, rate, messages) -> decoder(samples -> {
                if(modulation == Modulation.C4FM)
                {
                    for(int x = 1; x <= 3; x++)
                    {
                        messages.receive(network(WACN, SYSTEM, NAC, x * 1000));
                        messages.receive(site(SYSTEM, NAC, 2, 7, x * 1000));
                    }
                    for(int x = 0; x < 32; x++) messages.receive(other(4000 + x));
                    for(int x = 0; x < 169; x++)
                    {
                        UnknownOSPMessage rejected = other(5000 + x);
                        rejected.setValid(false);
                        messages.receive(rejected);
                    }
                    messages.receive(new SyncLossMessage(6000, 196 * 1050, Protocol.APCO25));
                }
            }), clock::get))
        {
            P25DiscoveryProbe.Session session = probe.open("target", FREQUENCY);
            clock.set(3000);
            source.emit(samples(3000));
            await(() -> session.status().state().equals("ready"));
            var status = session.status();
            assertEquals(38, status.c4fm().validControlMessages());
            assertEquals(169, status.c4fm().invalidControlMessages());
            assertEquals(3, Math.round(status.c4fm().qualityPct()));
            assertEquals(0, status.cqpsk().validControlMessages());
            assertEquals("C4FM", status.selectedModulation());
            assertEquals(new P25DiscoveryProbe.Identity(WACN, SYSTEM, 2, 7, NAC), status.identity());
            var proof = TrunkedDiscoveryEvidence.p25(status, 7000);
            assertNotNull(proof);
            assertTrue(proof.verified(), "The shared save path must not reapply a signal-quality threshold");
            assertEquals("C4FM", proof.settings().get("modulation"));
            assertEquals(0, source.closed.get(), "Review retains the tuner hold until save or cancellation");
        }
    }

    @Test
    void manyWeakValidControlsWithoutRepeatedServingIdentityCannotBecomeDiscoveryProof()
    {
        P25DiscoveryProbe.ModeEvidence evidence = new P25DiscoveryProbe.ModeEvidence(Modulation.C4FM);
        evidence.receive(network(WACN, SYSTEM, NAC, 1000));
        evidence.receive(site(SYSTEM, NAC, 2, 7, 1000));
        for(int x = 0; x < 36; x++) evidence.receive(other(2000 + x));
        evidence.receive(new SyncLossMessage(3000, 196 * 1200, Protocol.APCO25));
        assertEquals(38, evidence.metrics().validControlMessages());
        assertFalse(evidence.metrics().confirmed());
        assertNull(P25DiscoveryProbe.select(evidence.metrics(),
            new P25DiscoveryProbe.ModeEvidence(Modulation.CQPSK).metrics()));
    }

    @Test
    void measuresUntouchedIqWithoutCallingItModulationClassification()
    {
        P25DiscoveryProbe.SignalEvidence evidence = new P25DiscoveryProbe.SignalEvidence();
        assertNull(evidence.metrics().meanPowerDbfs());
        evidence.receive(new ComplexSamples(new float[]{1, 1, 1, Float.NaN}, new float[]{0, 0, 0, 0}, 1000));
        assertEquals(3, evidence.metrics().sampleCount());
        assertEquals(0, evidence.metrics().meanPowerDbfs());
        assertEquals(0, evidence.metrics().envelopeVariation());
        evidence.reset();
        evidence.receive(new ComplexSamples(new float[]{0.5f, 1.5f}, new float[]{0, 0}, 1000));
        assertEquals(0.5, evidence.metrics().envelopeVariation(), 1e-6);
    }

    @Test
    void runsBothModesOnSameIqOffProducerAndRetainsOnlyReviewHoldWhenReady() throws Exception
    {
        FakeSource source = new FakeSource();
        AtomicLong clock = new AtomicLong(1000);
        List<ComplexSamples> received = new CopyOnWriteArrayList<>();
        List<String> threads = new CopyOnWriteArrayList<>();
        try(P25DiscoveryProbe probe = new P25DiscoveryProbe((target, frequency) -> source,
            (modulation, rate, messages) -> decoder(samples -> {
                received.add(samples);
                threads.add(Thread.currentThread().getName());
                if(modulation == Modulation.C4FM)
                {
                    for(int x = 1; x <= 3; x++)
                    {
                        messages.receive(network(WACN, SYSTEM, NAC, x * 1000));
                        messages.receive(site(SYSTEM, NAC, 2, 7, x * 1000));
                    }
                    for(int x = 0; x < 20; x++) messages.receive(other(4000 + x));
                }
            }), clock::get))
        {
            P25DiscoveryProbe.Session session = probe.open("target", FREQUENCY);
            assertThrows(IllegalStateException.class, () -> probe.open("target", FREQUENCY));
            ComplexSamples iq = samples(1000);
            source.emit(iq);
            await(() -> received.size() == 2);
            assertSame(iq, received.get(0));
            assertSame(iq, received.get(1));
            assertTrue(threads.stream().allMatch(name -> name.equals("P25 discovery decoder")));
            clock.set(6500);
            await(() -> session.status().state().equals("ready"));
            assertEquals("C4FM", session.status().selectedModulation());
            assertEquals(new P25DiscoveryProbe.Identity(WACN, SYSTEM, 2, 7, NAC), session.status().identity());
            assertEquals(1, source.samplesStopped.get());
            assertEquals(0, source.closed.get());
            assertTrue(session.valid());
            source.valid = false;
            assertFalse(session.valid());
            assertEquals("failed", session.status().state());
            session.close();
            session.close();
            assertEquals(1, source.closed.get());
        }
    }

    @Test
    void completesStrongIdentityAtTwoSecondsBetweenPublicationTicks() throws Exception
    {
        FakeSource source = new FakeSource();
        AtomicLong clock = new AtomicLong(1000);
        AtomicInteger c4fmBatches = new AtomicInteger();
        try(P25DiscoveryProbe probe = new P25DiscoveryProbe((target, frequency) -> source,
            (modulation, rate, messages) -> decoder(samples -> {
                if(modulation == Modulation.C4FM && c4fmBatches.incrementAndGet() == 1)
                {
                    for(int x = 1; x <= 3; x++)
                    {
                        messages.receive(network(WACN, SYSTEM, NAC, x * 1000));
                        messages.receive(site(SYSTEM, NAC, 2, 7, x * 1000));
                    }
                    for(int x = 0; x < 20; x++) messages.receive(other(4000 + x));
                }
            }), clock::get))
        {
            P25DiscoveryProbe.Session session = probe.open("target", FREQUENCY);
            clock.set(2999);
            source.emit(samples(2999));
            await(() -> session.status().elapsedMs() == 1999 && session.status().c4fm().confirmed());
            assertEquals("running", session.status().state());
            clock.set(3000);
            source.emit(samples(3000));
            await(() -> session.status().state().equals("ready"));
            assertEquals(2000, session.status().elapsedMs());
            assertEquals("C4FM", session.status().selectedModulation());
            assertEquals(new P25DiscoveryProbe.Identity(WACN, SYSTEM, 2, 7, NAC), session.status().identity());
        }
    }

    @Test
    void fastCompletionStillComparesBothDecodersBeforePublishing() throws Exception
    {
        FakeSource source = new FakeSource();
        AtomicLong clock = new AtomicLong(1000);
        try(P25DiscoveryProbe probe = new P25DiscoveryProbe((target, frequency) -> source,
            (modulation, rate, messages) -> decoder(samples -> {
                for(int x = 1; x <= 3; x++)
                {
                    messages.receive(network(WACN, SYSTEM, NAC, x * 1000));
                    messages.receive(site(SYSTEM, NAC, 2, modulation == Modulation.C4FM ? 7 : 8, x * 1000));
                }
                for(int x = 0; x < 20; x++) messages.receive(other(4000 + x));
            }), clock::get))
        {
            P25DiscoveryProbe.Session session = probe.open("target", FREQUENCY);
            clock.set(3000);
            source.emit(samples(3000));
            await(() -> session.status().elapsedMs() == 2000 && session.status().cqpsk().confirmed());
            assertEquals("running", session.status().state());
            assertNull(session.status().selectedModulation());
            clock.set(1000 + P25DiscoveryProbe.TIMEOUT_MILLISECONDS);
            await(() -> session.status().state().equals("inconclusive"));
            assertNull(session.status().identity());
        }
    }

    @Test
    void cachesWorkerMetricsOnlyUntilNewEvidenceOrReset()
    {
        P25DiscoveryProbe.ModeEvidence evidence = new P25DiscoveryProbe.ModeEvidence(Modulation.C4FM);
        P25DiscoveryProbe.ModeMetrics initial = evidence.metrics();
        assertSame(initial, evidence.metrics());
        confirm(evidence, 1000);
        P25DiscoveryProbe.ModeMetrics confirmed = evidence.metrics();
        assertTrue(confirmed.confirmed());
        assertSame(confirmed, evidence.metrics());
        assertEquals(0, initial.validControlMessages());
        evidence.reset();
        assertFalse(evidence.metrics().confirmed());
        assertEquals(0, evidence.metrics().validControlMessages());
        assertTrue(confirmed.confirmed(), "Published evidence snapshots must remain immutable");
    }

    @Test
    void fullQueueNeverBlocksProducerOrRunsDecoderAndResetsProofAfterGap() throws Exception
    {
        FakeSource source = new FakeSource();
        AtomicLong clock = new AtomicLong(1000);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger resets = new AtomicInteger();
        try(P25DiscoveryProbe probe = new P25DiscoveryProbe((target, frequency) -> source,
            (modulation, rate, messages) -> new P25DiscoveryProbe.ProbeDecoder()
            {
                public void receive(ComplexSamples iq)
                {
                    int call = calls.incrementAndGet();
                    if(call == 1)
                    {
                        messages.receive(network(WACN, SYSTEM, NAC, 1000));
                        entered.countDown();
                        waitLatch(unblock);
                    }
                }
                public void reset() { resets.incrementAndGet(); }
                public void close() { }
            }, clock::get))
        {
            P25DiscoveryProbe.Session session = probe.open("target", FREQUENCY);
            source.emit(samples(1000));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                for(int x = 0; x < 4096; x++) source.emit(samples(2000 + x));
            });
            assertEquals(1, calls.get());
            unblock.countDown();
            await(() -> calls.get() >= 18);
            source.emit(samples(10000));
            await(() -> resets.get() == 2);
            clock.set(11000);
            await(() -> session.status().elapsedMs() == 10000);
            assertTrue(session.status().droppedBuffers() >= 4088);
            assertEquals(0, session.status().c4fm().networkObservations());
            assertEquals("running", session.status().state());
        }
        finally
        {
            unblock.countDown();
        }
    }

    @Test
    void cancellationDuringSlowDecodeReleasesSourceOnceAndDoesNotPublishReady() throws Exception
    {
        FakeSource source = new FakeSource();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        try(P25DiscoveryProbe probe = new P25DiscoveryProbe((target, frequency) -> source,
            (modulation, rate, messages) -> decoder(samples -> {
                entered.countDown();
                waitLatch(unblock);
            }), System::currentTimeMillis))
        {
            P25DiscoveryProbe.Session session = probe.open("target", FREQUENCY);
            source.emit(samples(1000));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            session.close();
            session.close();
            unblock.countDown();
            assertEquals("closed", session.status().state());
            assertFalse(session.valid());
            assertEquals(1, source.closed.get());
            assertEquals(1, source.samplesStopped.get());
        }
        finally
        {
            unblock.countDown();
        }
    }

    @Test
    void upstreamDropDuringDecodeRejectsOtherwiseCompleteProofAndReleasesHold() throws Exception
    {
        FakeSource source = new FakeSource();
        AtomicLong clock = new AtomicLong(1000);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch unblock = new CountDownLatch(1);
        AtomicInteger decoderCloses = new AtomicInteger();
        try(P25DiscoveryProbe probe = new P25DiscoveryProbe((target, frequency) -> source,
            (modulation, rate, messages) -> new P25DiscoveryProbe.ProbeDecoder()
            {
                public void receive(ComplexSamples samples)
                {
                    if(modulation == Modulation.C4FM)
                    {
                        for(int x = 1; x <= 3; x++)
                        {
                            messages.receive(network(WACN, SYSTEM, NAC, x * 1000));
                            messages.receive(site(SYSTEM, NAC, 2, 7, x * 1000));
                        }
                        for(int x = 0; x < 20; x++) messages.receive(other(4000 + x));
                        entered.countDown();
                        waitLatch(unblock);
                    }
                }
                public void reset() { }
                public void close() { decoderCloses.incrementAndGet(); }
            }, clock::get))
        {
            P25DiscoveryProbe.Session session = probe.open("target", FREQUENCY);
            clock.set(6500);
            source.emit(samples(1000));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            source.upstreamDrops.incrementAndGet();
            unblock.countDown();
            await(() -> session.status().state().equals("failed"));
            assertTrue(session.status().reason().contains("dropped sample batches"));
            assertNull(session.status().selectedModulation());
            assertNull(session.status().identity());
            assertEquals(0, session.status().droppedBuffers());
            await(() -> decoderCloses.get() == 2 && source.closed.get() == 1);
            assertEquals(1, source.samplesStopped.get());
        }
        finally
        {
            unblock.countDown();
        }
    }

    @Test
    void upstreamDropBeforeWorkerReceivesSamplesFailsWithoutCallingDecoder() throws Exception
    {
        FakeSource source = new FakeSource();
        source.upstreamDrops.set(1);
        AtomicInteger decoderCalls = new AtomicInteger();
        try(P25DiscoveryProbe probe = new P25DiscoveryProbe((target, frequency) -> source,
            (modulation, rate, messages) -> decoder(samples -> decoderCalls.incrementAndGet()),
            System::currentTimeMillis))
        {
            P25DiscoveryProbe.Session session = probe.open("target", FREQUENCY);
            await(() -> session.status().state().equals("failed"));
            assertEquals(0, decoderCalls.get());
            await(() -> source.closed.get() == 1);
        }
    }

    @Test
    void teardownFailureInvalidatesReadySelectionAndStillReleasesHold() throws Exception
    {
        FakeSource source = new FakeSource();
        source.failStop.set(true);
        AtomicLong clock = new AtomicLong(1000);
        try(P25DiscoveryProbe probe = new P25DiscoveryProbe((target, frequency) -> source,
            (modulation, rate, messages) -> decoder(samples -> {
                if(modulation == Modulation.C4FM)
                {
                    for(int x = 1; x <= 3; x++)
                    {
                        messages.receive(network(WACN, SYSTEM, NAC, x * 1000));
                        messages.receive(site(SYSTEM, NAC, 2, 7, x * 1000));
                    }
                    for(int x = 0; x < 20; x++) messages.receive(other(4000 + x));
                }
            }), clock::get))
        {
            P25DiscoveryProbe.Session session = probe.open("target", FREQUENCY);
            clock.set(6500);
            source.emit(samples(1000));
            await(() -> session.status().state().equals("failed") && source.closed.get() == 1);
            assertNull(session.status().selectedModulation());
            assertNull(session.status().identity());
            assertFalse(session.valid());
            assertEquals(1, source.samplesStopped.get());
        }
    }

    @Test
    void reentrantWorkerCancellationDoesNotDeadlockPublishReadyOrRetainActiveSession() throws Exception
    {
        FakeSource source = new FakeSource();
        FakeSource secondSource = new FakeSource();
        AtomicInteger sourceCalls = new AtomicInteger();
        AtomicReference<P25DiscoveryProbe.Session> sessionReference = new AtomicReference<>();
        AtomicLong clock = new AtomicLong(1000);
        AtomicInteger decoderCloses = new AtomicInteger();
        try(P25DiscoveryProbe probe = new P25DiscoveryProbe((target, frequency) ->
            sourceCalls.getAndIncrement() == 0 ? source : secondSource,
            (modulation, rate, messages) -> new P25DiscoveryProbe.ProbeDecoder()
            {
                public void receive(ComplexSamples samples)
                {
                    sessionReference.get().close();
                    for(int x = 1; x <= 3; x++)
                    {
                        messages.receive(network(WACN, SYSTEM, NAC, x * 1000));
                        messages.receive(site(SYSTEM, NAC, 2, 7, x * 1000));
                    }
                    for(int x = 0; x < 20; x++) messages.receive(other(4000 + x));
                }
                public void reset() { }
                public void close() { decoderCloses.incrementAndGet(); }
            }, clock::get))
        {
            P25DiscoveryProbe.Session session = probe.open("target", FREQUENCY);
            sessionReference.set(session);
            clock.set(6500);
            source.emit(samples(1000));
            await(() -> decoderCloses.get() == 2);
            assertEquals("closed", session.status().state());
            assertNull(session.status().selectedModulation());
            assertEquals(1, source.closed.get());
            assertEquals(1, source.samplesStopped.get());
            P25DiscoveryProbe.Session next = probe.open("target", FREQUENCY);
            next.close();
            assertEquals(1, secondSource.closed.get());
        }
    }

    @Test
    void timesOutAndReleasesHoldEvenWhenNoSamplesArrive() throws Exception
    {
        FakeSource source = new FakeSource();
        AtomicLong clock = new AtomicLong(1000);
        try(P25DiscoveryProbe probe = new P25DiscoveryProbe((target, frequency) -> source,
            (modulation, rate, messages) -> decoder(samples -> { }), clock::get))
        {
            P25DiscoveryProbe.Session session = probe.open("target", FREQUENCY);
            clock.set(1000 + P25DiscoveryProbe.TIMEOUT_MILLISECONDS);
            await(() -> session.status().state().equals("failed"));
            assertNull(session.status().selectedModulation());
            assertNull(session.status().identity());
            await(() -> source.closed.get() == 1);
        }
    }

    @Test
    void failsOnSourceChangesAndDecoderFailureWithoutReturningRawErrors() throws Exception
    {
        FakeSource source = new FakeSource();
        try(P25DiscoveryProbe probe = new P25DiscoveryProbe((target, frequency) -> source,
            (modulation, rate, messages) -> decoder(samples -> { }), System::currentTimeMillis))
        {
            P25DiscoveryProbe.Session session = probe.open("target", FREQUENCY);
            source.events.receive(SourceEvent.sampleRateChange(25000));
            await(() -> session.status().state().equals("failed"));
            assertTrue(session.status().reason().contains("sample rate changed"));
            await(() -> source.closed.get() == 1);
        }
        FakeSource failureSource = new FakeSource();
        try(P25DiscoveryProbe probe = new P25DiscoveryProbe((target, frequency) -> failureSource,
            (modulation, rate, messages) -> { throw new IllegalArgumentException("raw decoder details"); },
            System::currentTimeMillis))
        {
            P25DiscoveryProbe.Session session = probe.open("target", FREQUENCY);
            await(() -> session.status().state().equals("failed"));
            assertFalse(session.status().reason().contains("raw decoder details"));
            await(() -> failureSource.closed.get() == 1);
        }
    }

    private static void confirm(P25DiscoveryProbe.ModeEvidence evidence, long base)
    {
        for(int x = 0; x < 3; x++)
        {
            evidence.receive(network(WACN, SYSTEM, NAC, base + x * 1000));
            evidence.receive(site(SYSTEM, NAC, 2, 7, base + x * 1000));
        }
        for(int x = 0; x < 20; x++) evidence.receive(other(base + 3000 + x));
    }

    private static CorrectedBinaryMessage fields(Opcode opcode)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.setInt(opcode.getCode(), IntField.length6(2));
        return bits;
    }

    private static NetworkStatusBroadcast network(int wacn, int system, int nac, long timestamp)
    {
        //Fields interpreted by the existing TSBK parser; no copied standards text or protocol fixtures.
        CorrectedBinaryMessage bits = fields(Opcode.OSP_NETWORK_STATUS_BROADCAST);
        bits.setInt(wacn, IntField.length20(24));
        bits.setInt(system, IntField.length12(44));
        NetworkStatusBroadcast result = new NetworkStatusBroadcast(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, nac, timestamp);
        result.setValid(true);
        return result;
    }

    private static RFSSStatusBroadcast site(int system, int nac, int rfss, int site, long timestamp)
    {
        CorrectedBinaryMessage bits = fields(Opcode.OSP_RFSS_STATUS_BROADCAST);
        bits.setInt(system, IntField.length12(28));
        bits.setInt(rfss, IntField.length8(40));
        bits.setInt(site, IntField.length8(48));
        RFSSStatusBroadcast result = new RFSSStatusBroadcast(
            P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1, bits, nac, timestamp);
        result.setValid(true);
        return result;
    }

    private static UnknownOSPMessage other(long timestamp)
    {
        UnknownOSPMessage result = new UnknownOSPMessage(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
            new CorrectedBinaryMessage(96), NAC, timestamp);
        result.setValid(true);
        return result;
    }

    private static ComplexSamples samples(long timestamp)
    {
        return new ComplexSamples(new float[]{0.5f, 0.5f}, new float[]{0, 0}, timestamp);
    }

    private static P25DiscoveryProbe.ProbeDecoder decoder(Listener<ComplexSamples> consumer)
    {
        return new P25DiscoveryProbe.ProbeDecoder()
        {
            public void receive(ComplexSamples samples) { consumer.receive(samples); }
            public void reset() { }
            public void close() { }
        };
    }

    private static void await(BooleanSupplier condition) throws InterruptedException
    {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for probe worker");
    }

    private static void waitLatch(CountDownLatch latch)
    {
        try
        {
            latch.await(3, TimeUnit.SECONDS);
        }
        catch(InterruptedException exception)
        {
            Thread.currentThread().interrupt();
        }
    }

    private static final class FakeSource implements P25DiscoveryProbe.SourceLease
    {
        private final AtomicInteger samplesStopped = new AtomicInteger();
        private final AtomicInteger closed = new AtomicInteger();
        private final AtomicLong upstreamDrops = new AtomicLong();
        private final AtomicBoolean failStop = new AtomicBoolean();
        private volatile Listener<ComplexSamples> samples;
        private volatile Listener<SourceEvent> events;
        private volatile boolean valid = true;

        public double sampleRate() { return 50000; }
        public boolean valid() { return valid && closed.get() == 0; }
        public long droppedSampleBatches() { return upstreamDrops.get(); }
        public void start(Listener<ComplexSamples> samples, Listener<SourceEvent> events)
        {
            this.samples = samples;
            this.events = events;
        }
        public synchronized void stopSamples()
        {
            if(samples != null)
            {
                samplesStopped.incrementAndGet();
                samples = null;
                if(failStop.compareAndSet(true, false)) throw new IllegalStateException("raw teardown detail");
            }
        }
        public synchronized void close()
        {
            stopSamples();
            closed.compareAndSet(0, 1);
        }
        void emit(ComplexSamples iq)
        {
            Listener<ComplexSamples> listener = samples;
            if(listener != null) listener.receive(iq);
        }
    }
}
