/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;

/** Bounded completed discovery DTOs, independent of tuner reservations, probes and active wizard ownership. */
final class DiscoveryCompletionRegistry
{
    private final LongSupplier mClock;
    private final Map<String,SpectrumDiscoveryService.Snapshot> mSnapshots = new LinkedHashMap<>();

    DiscoveryCompletionRegistry() { this(System::currentTimeMillis); }

    DiscoveryCompletionRegistry(LongSupplier clock) { mClock = Objects.requireNonNull(clock); }

    synchronized SpectrumDiscoveryService.Snapshot retain(SpectrumDiscoveryService.Snapshot snapshot)
    {
        Objects.requireNonNull(snapshot);
        if(snapshot.saved() == null || snapshot.saved().configurationId() == null ||
            snapshot.saved().configurationId().isBlank())
            throw new IllegalArgumentException("Only saved discovery results can be retained");
        if(snapshot.sessionId() == null || snapshot.sessionId().isBlank())
            throw new IllegalArgumentException("A completed discovery session ID is required");
        SpectrumDiscoveryService.Snapshot previous = mSnapshots.get(snapshot.sessionId());
        cleanup();
        long expiresAt = previous != null ? previous.expiresAtMs() :
            mClock.getAsLong() + DiscoveryAliasImportService.RETENTION_MS;
        var review = snapshot.review();
        if(review != null) review = new io.github.dsheirer.channel.ChannelAdministrationService.DiscoveryReview(
            review.revision(), review.template(), List.copyOf(review.aliasLists()), review.suggestedAliasListId(),
            review.defaultNewAliasListName());
        var digital = snapshot.digitalProbe();
        if(digital != null) digital = new DigitalTrunkedDiscoveryProbe.Status(digital.state(), digital.reason(),
            digital.targetId(), digital.frequencyHz(), digital.elapsedMs(), digital.timeoutMs(), digital.droppedBuffers(),
            digital.evidence(), List.copyOf(digital.modes()));
        var retained = new SpectrumDiscoveryService.Snapshot(snapshot.sessionId(), snapshot.state(), snapshot.reason(),
            snapshot.protocolId(), snapshot.tunerId(), snapshot.targetId(), snapshot.frequencyHz(), expiresAt,
            snapshot.probe(), review, snapshot.saved(), digital, snapshot.trunkedEvidence(),
            snapshot.radioReference(), snapshot.aliasImport());
        // An admitted retry can finish after its original deadline; its response must not renew retention.
        if(previous != null && expiresAt <= mClock.getAsLong()) return retained;
        if(previous == null && mSnapshots.size() >= DiscoveryAliasImportService.MAXIMUM_BATCHES)
            mSnapshots.remove(mSnapshots.keySet().iterator().next());
        mSnapshots.put(retained.sessionId(), retained);
        return retained;
    }

    synchronized SpectrumDiscoveryService.Snapshot get(String id)
    {
        cleanup();
        return mSnapshots.get(id);
    }

    synchronized void clear() { mSnapshots.clear(); }

    private void cleanup()
    {
        long now = mClock.getAsLong();
        mSnapshots.values().removeIf(snapshot -> snapshot.expiresAtMs() <= now);
    }
}
