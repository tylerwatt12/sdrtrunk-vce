/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Shared protocol selection and observation budget for single-frequency and band-search discovery workers. */
final class TrunkedSignalDetector
{
    static final long NO_CONTROL_EVIDENCE_MS = 8_000;

    private TrunkedSignalDetector() {}

    record Result(TrunkedDiscoveryEvidence evidence, Double strengthDbfs,
                  P25DiscoveryProbe.Status p25, DigitalTrunkedDiscoveryProbe.Status digital) {}

    /** Decoder callbacks retain their bounded handoff; only a dedicated discovery worker polls their snapshots. */
    static Result check(Supplier<P25DiscoveryProbe.Status> p25,
                        Supplier<DigitalTrunkedDiscoveryProbe.Status> digital,
                        BooleanSupplier cancelled) throws InterruptedException
    {
        boolean needsFullCheck = false;
        while(!cancelled.getAsBoolean())
        {
            var p25Status = p25.get();
            var p25Evidence = TrunkedDiscoveryEvidence.p25(p25Status, System.currentTimeMillis());
            var digitalStatus = digital.get();
            var digitalEvidence = digitalStatus != null && "ready".equals(digitalStatus.state()) ? digitalStatus.evidence() : null;
            if(p25Evidence != null && digitalEvidence != null) return new Result(null, null, p25Status, digitalStatus);
            if(p25Evidence != null) return new Result(p25Evidence,
                p25Status.signal() != null ? p25Status.signal().meanPowerDbfs() : null, p25Status, digitalStatus);
            if(digitalEvidence != null) return new Result(digitalEvidence, null, p25Status, digitalStatus);
            if(!"running".equals(p25Status.state()) && (digitalStatus == null || !"running".equals(digitalStatus.state())))
                return new Result(null, null, p25Status, digitalStatus);

            // An interrupted stream can reset counters between publications. Any control evidence or sample loss
            // retains the full protocol-probe allowance instead of silently shortening a promising check.
            needsFullCheck |= p25Status.droppedBuffers() > 0 ||
                p25Status.c4fm() != null && p25Status.c4fm().validControlMessages() > 0 ||
                p25Status.cqpsk() != null && p25Status.cqpsk().validControlMessages() > 0 ||
                digitalStatus != null && (digitalStatus.droppedBuffers() > 0 ||
                    digitalStatus.modes().stream().anyMatch(mode -> mode.validControlMessages() > 0));
            if(!needsFullCheck &&
                (!"running".equals(p25Status.state()) || p25Status.elapsedMs() >= NO_CONTROL_EVIDENCE_MS &&
                    p25Status.c4fm() != null && p25Status.cqpsk() != null) &&
                (digitalStatus == null || !"running".equals(digitalStatus.state()) ||
                    digitalStatus.elapsedMs() >= NO_CONTROL_EVIDENCE_MS && !digitalStatus.modes().isEmpty()))
                return new Result(null, null, p25Status, digitalStatus);
            Thread.sleep(25);
        }
        throw new InterruptedException();
    }
}
