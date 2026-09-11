/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast.radioresolve;

import io.github.dsheirer.metadata.site.FactConfirmationPolicy;
import io.github.dsheirer.metadata.site.SiteMetadataEvent;
import io.github.dsheirer.metadata.site.SiteReceiverContext;
import io.github.dsheirer.metadata.site.StableFactTracker;
import io.github.dsheirer.module.decode.p25.P25GrantObservationEvent;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelConfirmationEvent;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Worker-owned confirmation and retention of grant-derived P25 Phase 2 frequencies. */
final class RadioResolveP25FrequencyEvidenceTracker
{
    private static final int MAXIMUM_TRACKED_CHANNELS = 2_048;
    private static final long CANDIDATE_TTL_MILLISECONDS = TimeUnit.MINUTES.toMillis(10);
    private static final long EVIDENCE_TTL_MILLISECONDS = TimeUnit.HOURS.toMillis(26);

    private final int mMaximumTrackedChannels;
    private final long mCandidateTtlMilliseconds;
    private final long mEvidenceTtlMilliseconds;
    private final FactConfirmationPolicy mPolicy;
    private final Map<Key,StableFactTracker<Candidate,Long>> mTrackers =
        new LinkedHashMap<>(16, 0.75f, true);

    RadioResolveP25FrequencyEvidenceTracker()
    {
        this(MAXIMUM_TRACKED_CHANNELS, CANDIDATE_TTL_MILLISECONDS, EVIDENCE_TTL_MILLISECONDS);
    }

    RadioResolveP25FrequencyEvidenceTracker(int maximumTrackedChannels, long candidateTtlMilliseconds,
                                            long evidenceTtlMilliseconds)
    {
        if(maximumTrackedChannels < 1 || candidateTtlMilliseconds < 0 || evidenceTtlMilliseconds < 0)
        {
            throw new IllegalArgumentException("Evidence capacity must be positive and retention cannot be negative");
        }

        mMaximumTrackedChannels = maximumTrackedChannels;
        mCandidateTtlMilliseconds = candidateTtlMilliseconds;
        mEvidenceTtlMilliseconds = evidenceTtlMilliseconds;
        mPolicy = new FactConfirmationPolicy(2, 1L, candidateTtlMilliseconds, false);
    }

    void observe(P25GrantObservationEvent event)
    {
        if(!valid(event))
        {
            return;
        }

        int channelNumber = event.channelNumber() - event.channelNumber() % 2;
        Key key = new Key(event.configurationId(), event.processingIncarnation(),
            event.siteEvidenceTuningGeneration(), event.radioSystemKey(), event.channelBand() + "-" + channelNumber);
        Candidate candidate = new Candidate(event.frequencyHertz(), event.timeslot());
        mTrackers.computeIfAbsent(key, ignored -> new StableFactTracker<>(Candidate::frequencyHertz))
            .observe(candidate, event.timestamp(), mPolicy, ignored -> true);
        trim();
    }

    void confirm(P25TrafficChannelConfirmationEvent event)
    {
        if(event == null || event.configurationId() == null || event.processingIncarnation() <= 0L ||
            event.siteEvidenceTuningGeneration() <= 0L || event.frequencyHertz() <= 0L ||
            event.timeslot() < 1 || event.timeslot() > 2 || event.timestamp() <= 0L)
        {
            return;
        }

        prune(event.timestamp());
        List<StableFactTracker<Candidate,Long>> matches = new ArrayList<>();
        String radioSystemKey = null;

        for(Map.Entry<Key,StableFactTracker<Candidate,Long>> entry: mTrackers.entrySet())
        {
            Candidate candidate = entry.getValue().getCandidateValue();

            if(entry.getKey().matches(event) && candidate != null &&
                candidate.frequencyHertz() == event.frequencyHertz() && candidate.timeslot() == event.timeslot())
            {
                if(radioSystemKey != null && !radioSystemKey.equals(entry.getKey().radioSystemKey()))
                {
                    return;
                }

                radioSystemKey = entry.getKey().radioSystemKey();
                matches.add(entry.getValue());
            }
        }

        matches.forEach(tracker -> tracker.confirmCandidate(event.timestamp(), ignored -> true));
    }

    List<Evidence> select(SiteMetadataEvent event, long nowMilliseconds)
    {
        prune(nowMilliseconds);
        SiteReceiverContext context = event != null ? event.receiverContext() : null;
        String radioSystemKey = RadioSystemKey.p25(event != null ? P25SiteIdentity.from(event.snapshot()) : null);

        if(context == null || context.configurationId() == null || context.processingIncarnation() <= 0L ||
            context.siteEvidenceTuningGeneration() <= 0L || radioSystemKey == null)
        {
            return List.of();
        }

        List<Evidence> evidence = new ArrayList<>();

        for(Map.Entry<Key,StableFactTracker<Candidate,Long>> entry: mTrackers.entrySet())
        {
            StableFactTracker.StableObservation<Candidate,Long> stable = entry.getValue().getStableObservation();

            if(entry.getKey().matches(context, radioSystemKey) && stable.value() != null)
            {
                evidence.add(new Evidence(entry.getKey().descriptor(), stable.value().frequencyHertz(),
                    stable.lastSeenTimestamp()));
            }
        }

        evidence.sort(Comparator.comparing(Evidence::channelDescriptor));
        return List.copyOf(evidence);
    }

    void clear()
    {
        mTrackers.clear();
    }

    private void prune(long timestamp)
    {
        Iterator<StableFactTracker<Candidate,Long>> iterator = mTrackers.values().iterator();

        while(iterator.hasNext())
        {
            StableFactTracker<Candidate,Long> tracker = iterator.next();
            tracker.expireCandidate(timestamp, mCandidateTtlMilliseconds);
            tracker.expireStable(timestamp, mEvidenceTtlMilliseconds);

            if(tracker.isEmpty())
            {
                iterator.remove();
            }
        }
    }

    private void trim()
    {
        Iterator<Key> iterator = mTrackers.keySet().iterator();

        while(mTrackers.size() > mMaximumTrackedChannels && iterator.hasNext())
        {
            iterator.next();
            iterator.remove();
        }
    }

    private static boolean valid(P25GrantObservationEvent event)
    {
        return event != null && event.tdma() && event.confirmedBand() && event.configurationId() != null &&
            event.processingIncarnation() > 0L && event.siteEvidenceTuningGeneration() > 0L &&
            event.timestamp() > 0L && event.frequencyHertz() != null && event.frequencyHertz() > 0L &&
            event.channelBand() != null && event.channelBand() >= 0 && event.channelBand() <= 15 &&
            event.channelNumber() != null && event.channelNumber() >= 0 && event.channelNumber() <= 4_095 &&
            event.timeslot() != null && event.timeslot() >= 1 && event.timeslot() <= 2 &&
            RadioSystemKey.isP25Native(event.radioSystemKey());
    }

    record Evidence(String channelDescriptor, long frequencyHertz, long observedAtMs)
    {
    }

    private record Candidate(long frequencyHertz, int timeslot) {}

    private record Key(String configurationId, long processingIncarnation, long tuningGeneration,
                       String radioSystemKey, String descriptor)
    {
        private boolean matches(P25TrafficChannelConfirmationEvent event)
        {
            return configurationId.equals(event.configurationId()) &&
                processingIncarnation == event.processingIncarnation() &&
                tuningGeneration == event.siteEvidenceTuningGeneration();
        }

        private boolean matches(SiteReceiverContext context, String selectedRadioSystemKey)
        {
            return configurationId.equals(context.configurationId()) &&
                processingIncarnation == context.processingIncarnation() &&
                tuningGeneration == context.siteEvidenceTuningGeneration() &&
                radioSystemKey.equals(selectedRadioSystemKey);
        }
    }
}
