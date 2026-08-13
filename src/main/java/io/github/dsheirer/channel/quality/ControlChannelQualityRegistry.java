/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>
 * ****************************************************************************
 */

package io.github.dsheirer.channel.quality;

import io.github.dsheirer.sample.Listener;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.LongSupplier;

/**
 * Small in-memory registry of the latest control-channel quality observation for each configured site.
 *
 * <p>The quality monitor publishes approximately once per second. The default five-second freshness window permits a
 * few delayed heartbeats without allowing a stopped or retuned control channel to influence a later call election.
 * One immutable value is retained per observed configured site; no history is accumulated.</p>
 */
public final class ControlChannelQualityRegistry
    implements ControlChannelQualityProvider, Listener<ControlChannelQualitySnapshot>
{
    public static final long DEFAULT_FRESHNESS_MILLISECONDS = 5_000L;

    private final ConcurrentMap<String, QualityObservation> mObservations = new ConcurrentHashMap<>();
    private final long mFreshnessMilliseconds;
    private final LongSupplier mClock;

    public ControlChannelQualityRegistry()
    {
        this(DEFAULT_FRESHNESS_MILLISECONDS, System::currentTimeMillis);
    }

    ControlChannelQualityRegistry(long freshnessMilliseconds, LongSupplier clock)
    {
        if(freshnessMilliseconds < 0)
        {
            throw new IllegalArgumentException("Freshness must not be negative");
        }

        mFreshnessMilliseconds = freshnessMilliseconds;
        mClock = clock != null ? clock : System::currentTimeMillis;
    }

    /**
     * Stores the latest immutable observation for a stable configured site. Older observations cannot replace newer
     * observations when multiple receiver processing threads publish concurrently.
     */
    @Override
    public void receive(ControlChannelQualitySnapshot snapshot)
    {
        String siteIdentity = normalize(snapshot != null ? snapshot.guid() : null);

        if(siteIdentity == null || snapshot.observedAtMs() <= 0)
        {
            return;
        }

        Double health = snapshot.decodeHealthPercent();
        double normalizedHealth = health != null && Double.isFinite(health) && health >= 0.0d && health <= 100.0d ?
            health : Double.NaN;
        QualityObservation incoming = new QualityObservation(snapshot.observedAtMs(), snapshot.active(),
            normalizedHealth, snapshot.frequencyHz(), finiteOrNull(snapshot.signalDbfs()), snapshot.validFrames(),
            snapshot.invalidFrames(), snapshot.correctedBits(), snapshot.syncLossBits(), snapshot.droppedBits(),
            snapshot.lastValidDecodeMs());
        mObservations.compute(siteIdentity,
            (_, current) -> current == null || incoming.observedAtMilliseconds() >=
                current.observedAtMilliseconds() ? incoming : current);
    }

    @Override
    public OptionalDouble getDecodeHealthPercent(String stableSiteIdentity)
    {
        String siteIdentity = normalize(stableSiteIdentity);

        if(siteIdentity == null)
        {
            return OptionalDouble.empty();
        }

        QualityObservation observation = mObservations.get(siteIdentity);

        if(observation == null || !observation.active() || !Double.isFinite(observation.decodeHealthPercent()))
        {
            return OptionalDouble.empty();
        }

        long now = mClock.getAsLong();
        long observedAt = observation.observedAtMilliseconds();

        if(now < observedAt || now - observedAt > mFreshnessMilliseconds)
        {
            return OptionalDouble.empty();
        }

        return OptionalDouble.of(observation.decodeHealthPercent());
    }

    /**
     * Clears all current observations during application shutdown or a full receiver reset.
     */
    public void clear()
    {
        mObservations.clear();
    }

    int size()
    {
        return mObservations.size();
    }

    private static String normalize(String value)
    {
        if(value == null)
        {
            return null;
        }

        String normalized = value.trim();
        return normalized.isEmpty() ? null : normalized;
    }

    /**
     * Bounded, weakly consistent diagnostic snapshot of already-observed control-channel quality values.  This does
     * not register another receiver listener or retain the mutable Channel reference from the source event.  The
     * iterator stops as soon as the requested limit is reached, so a diagnostic sampler never projects or sorts more
     * than {@code maximumSnapshots} observations.
     */
    public DiagnosticQualityBatch getDiagnosticSnapshots(int maximumSnapshots)
    {
        if(maximumSnapshots < 0)
        {
            throw new IllegalArgumentException("Maximum diagnostic quality snapshot count cannot be negative");
        }

        long totalCount = mObservations.size();
        List<DiagnosticQualitySnapshot> snapshots = new ArrayList<>(
            (int)Math.min(maximumSnapshots, Math.min(totalCount, Integer.MAX_VALUE)));

        if(maximumSnapshots > 0)
        {
            for(var entry: mObservations.entrySet())
            {
                snapshots.add(entry.getValue().toDiagnosticSnapshot(entry.getKey()));

                if(snapshots.size() >= maximumSnapshots)
                {
                    break;
                }
            }

            snapshots.sort(Comparator.comparing(DiagnosticQualitySnapshot::siteIdentity));
        }

        return new DiagnosticQualityBatch(totalCount, snapshots);
    }

    private static Double finiteOrNull(Double value)
    {
        return value != null && Double.isFinite(value) ? value : null;
    }

    private record QualityObservation(long observedAtMilliseconds, boolean active, double decodeHealthPercent,
                                      long frequencyHz, Double signalDbfs, long validFrames, long invalidFrames,
                                      long correctedBits, long syncLossBits, long droppedBits, long lastValidDecodeMs)
    {
        private DiagnosticQualitySnapshot toDiagnosticSnapshot(String siteIdentity)
        {
            return new DiagnosticQualitySnapshot(siteIdentity, observedAtMilliseconds, active,
                Double.isFinite(decodeHealthPercent) ? decodeHealthPercent : null, frequencyHz, signalDbfs,
                validFrames, invalidFrames, correctedBits, syncLossBits, droppedBits, lastValidDecodeMs);
        }
    }

    /** Primitive-only diagnostic view; it intentionally excludes the source Channel reference. */
    public record DiagnosticQualitySnapshot(String siteIdentity, long observedAtMs, boolean active,
                                            Double decodeHealthPercent, long frequencyHz, Double signalDbfs,
                                            long validFrames, long invalidFrames, long correctedBits,
                                            long syncLossBits, long droppedBits, long lastValidDecodeMs)
    {
    }

    /** Bounded observations plus the weakly consistent total retained site count. */
    public record DiagnosticQualityBatch(long totalCount, List<DiagnosticQualitySnapshot> snapshots)
    {
        public DiagnosticQualityBatch
        {
            snapshots = snapshots == null ? List.of() : List.copyOf(snapshots);
        }
    }
}
