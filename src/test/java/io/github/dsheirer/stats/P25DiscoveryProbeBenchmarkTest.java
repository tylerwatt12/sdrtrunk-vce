/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.bits.CorrectedBinaryMessage;
import io.github.dsheirer.bits.IntField;
import io.github.dsheirer.message.IMessage;
import io.github.dsheirer.module.decode.p25.phase1.Modulation;
import io.github.dsheirer.module.decode.p25.phase1.P25P1DataUnitID;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.Opcode;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.NetworkStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.RFSSStatusBroadcast;
import io.github.dsheirer.module.decode.p25.phase1.message.tsbk.standard.osp.UnknownOSPMessage;
import io.github.dsheirer.sample.Listener;
import io.github.dsheirer.sample.complex.ComplexSamples;
import io.github.dsheirer.source.SourceEvent;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Reproducible discovery-policy benchmark at the decoder-message seam. It measures worker wall time and logical
 * on-air dwell, not IQ demodulation CPU or RF accuracy. Run against the same source revisions with
 * SDRTRUNK_DISCOVERY_BENCHMARK=true ./gradlew test --tests '*P25DiscoveryProbeBenchmarkTest' --rerun-tasks.
 * Logical time advances 100 ms per batch paced at 5 ms; every accepted batch is acknowledged before the next.
 */
@EnabledIfEnvironmentVariable(named = "SDRTRUNK_DISCOVERY_BENCHMARK", matches = "true")
class P25DiscoveryProbeBenchmarkTest
{
    private static final int WACN = 0xABCDE;
    private static final int SYSTEM = 0x123;
    private static final int NAC = 0x345;
    private static final long FREQUENCY = 770_500_000;
    private static final int LOGICAL_BATCH_MS = 100;
    private static final int PACED_BATCH_MS = 5;

    enum Fixture
    {
        CLEAN_C4FM(true), CLEAN_CQPSK(true), DELAYED_C4FM(true), DELAYED_CQPSK(true),
        SILENCE(false), VOICE_ONLY(false), NETWORK_ONLY(false), NAC_CONFLICT(false),
        POOR_QUALITY(false), NEAR_TIE(false), SITE_CONFLICT(false), CHANGING_IDENTITY(false);

        final boolean expectedReady;
        Fixture(boolean ready) { expectedReady = ready; }
    }

    @Test
    void benchmarkConfirmationLatencyAndFalsePositiveFixtures() throws Exception
    {
        System.out.println("P25 discovery policy benchmark: parsed synthetic messages, 20x paced logical clock; no RF accuracy claim");
        System.out.println("fixture,expected_ready,ready,logical_dwell_ms,wall_ms,control_units,network_observations,site_observations,quality_pct");
        int ready = 0;
        for(Fixture fixture: Fixture.values())
        {
            if(run(fixture)) ready++;
        }
        assertEquals(4, ready, "The same four control-channel fixtures must pass in both revisions");
        //Repeat the common positive paths separately so their latency is not hidden by conservative negative timeouts.
        for(int repeat = 0; repeat < 2; repeat++)
        {
            run(Fixture.CLEAN_C4FM);
            run(Fixture.CLEAN_CQPSK);
        }
    }

    private static boolean run(Fixture fixture) throws Exception
    {
        AtomicLong clock = new AtomicLong(1000);
        AtomicInteger processed = new AtomicInteger();
        BenchmarkSource source = new BenchmarkSource();
        long started = System.nanoTime();
        try(P25DiscoveryProbe probe = new P25DiscoveryProbe((target, frequency) -> source,
            (modulation, rate, messages) -> new P25DiscoveryProbe.ProbeDecoder()
            {
                public void receive(ComplexSamples samples)
                {
                    emit(fixture, modulation, messages, samples.timestamp());
                    processed.incrementAndGet();
                }
                public void reset() { }
                public void close() { }
            }, clock::get))
        {
            P25DiscoveryProbe.Session session = probe.open("benchmark", FREQUENCY);
            for(int batch = 1; batch <= 310 && "running".equals(session.status().state()); batch++)
            {
                Thread.sleep(PACED_BATCH_MS);
                long timestamp = 1000L + batch * LOGICAL_BATCH_MS;
                clock.set(timestamp);
                source.emit(new ComplexSamples(new float[]{0.5f, 0.5f}, new float[]{0, 0}, timestamp));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                int expectedProcessed = batch * 2;
                while(processed.get() < expectedProcessed && "running".equals(session.status().state()) &&
                    System.nanoTime() < deadline) Thread.sleep(1);
                assertTrue(processed.get() >= expectedProcessed || !"running".equals(session.status().state()),
                    "Probe did not consume a benchmark batch");
            }
            var status = session.status();
            assertTrue(!"running".equals(status.state()), "Probe did not reach a bounded terminal state");
            boolean actualReady = "ready".equals(status.state());
            assertEquals(fixture.expectedReady, actualReady, fixture.name());
            assertEquals(0, status.droppedBuffers());
            var evidence = "CQPSK".equals(status.selectedModulation()) ? status.cqpsk() : status.c4fm();
            if(actualReady)
            {
                assertEquals(new P25DiscoveryProbe.Identity(WACN, SYSTEM, 2, 7, NAC), status.identity());
                assertTrue(evidence.confirmed());
            }
            else assertEquals(null, status.identity());
            System.out.printf(Locale.ROOT, "%s,%s,%s,%d,%.3f,%d,%d,%d,%.3f%n", fixture,
                fixture.expectedReady, actualReady, status.elapsedMs(),
                (System.nanoTime() - started) / 1_000_000.0, evidence.validControlMessages(),
                evidence.networkObservations(), evidence.siteObservations(), evidence.qualityPct());
            return actualReady;
        }
    }

    private static void emit(Fixture fixture, Modulation mode, Listener<IMessage> messages, long timestamp)
    {
        if(fixture == Fixture.SILENCE) return;
        if(fixture == Fixture.VOICE_ONLY)
        {
            messages.receive(new IMessage()
            {
                public long getTimestamp() { return timestamp; }
                public boolean isValid() { return true; }
                public io.github.dsheirer.protocol.Protocol getProtocol() { return io.github.dsheirer.protocol.Protocol.APCO25; }
                public int getTimeslot() { return 0; }
                public java.util.List<io.github.dsheirer.identifier.Identifier> getIdentifiers() { return java.util.List.of(); }
            });
            return;
        }
        if((fixture == Fixture.CLEAN_C4FM || fixture == Fixture.DELAYED_C4FM) && mode != Modulation.C4FM ||
            (fixture == Fixture.CLEAN_CQPSK || fixture == Fixture.DELAYED_CQPSK) && mode != Modulation.CQPSK) return;
        int batch = (int)((timestamp - 1000) / LOGICAL_BATCH_MS);
        int identityInterval = fixture == Fixture.DELAYED_C4FM || fixture == Fixture.DELAYED_CQPSK ? 40 : 10;
        if((batch - 1) % identityInterval == 0)
        {
            int wacn = fixture == Fixture.CHANGING_IDENTITY ? WACN + (batch / 10) % 2 : WACN;
            messages.receive(network(wacn, timestamp));
            if(fixture != Fixture.NETWORK_ONLY)
                messages.receive(site(fixture == Fixture.NAC_CONFLICT ? NAC + 1 : NAC,
                    fixture == Fixture.SITE_CONFLICT && mode == Modulation.CQPSK ? 8 : 7, timestamp));
        }
        UnknownOSPMessage control = other(timestamp);
        if(fixture == Fixture.POOR_QUALITY) control.setValid(false);
        messages.receive(control);
    }

    private static CorrectedBinaryMessage fields(Opcode opcode)
    {
        CorrectedBinaryMessage bits = new CorrectedBinaryMessage(96);
        bits.setInt(opcode.getCode(), IntField.length6(2));
        return bits;
    }

    private static NetworkStatusBroadcast network(int wacn, long timestamp)
    {
        CorrectedBinaryMessage bits = fields(Opcode.OSP_NETWORK_STATUS_BROADCAST);
        bits.setInt(wacn, IntField.length20(24));
        bits.setInt(SYSTEM, IntField.length12(44));
        NetworkStatusBroadcast result = new NetworkStatusBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
            bits, NAC, timestamp);
        result.setValid(true);
        return result;
    }

    private static RFSSStatusBroadcast site(int nac, int site, long timestamp)
    {
        CorrectedBinaryMessage bits = fields(Opcode.OSP_RFSS_STATUS_BROADCAST);
        bits.setInt(SYSTEM, IntField.length12(28));
        bits.setInt(2, IntField.length8(40));
        bits.setInt(site, IntField.length8(48));
        RFSSStatusBroadcast result = new RFSSStatusBroadcast(P25P1DataUnitID.TRUNKING_SIGNALING_BLOCK_1,
            bits, nac, timestamp);
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

    private static final class BenchmarkSource implements P25DiscoveryProbe.SourceLease
    {
        private volatile Listener<ComplexSamples> listener;
        private volatile boolean closed;
        public double sampleRate() { return 50000; }
        public boolean valid() { return !closed; }
        public void start(Listener<ComplexSamples> samples, Listener<SourceEvent> events) { listener = samples; }
        public void stopSamples() { listener = null; }
        public void close() { closed = true; listener = null; }
        void emit(ComplexSamples samples)
        {
            var consumer = listener;
            if(consumer != null) consumer.receive(samples);
        }
    }
}
