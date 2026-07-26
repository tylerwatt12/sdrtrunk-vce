/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */

package io.github.dsheirer.stats.activity;

import io.github.dsheirer.channel.metadata.activity.ChannelTag;
import io.github.dsheirer.metadata.site.FactConfirmationPolicy;
import io.github.dsheirer.metadata.site.StableFactTracker;
import io.github.dsheirer.module.decode.event.DecodeEventType;
import io.github.dsheirer.module.decode.p25.P25GrantObservationEvent;
import io.github.dsheirer.module.decode.p25.P25TrafficChannelConfirmationEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Keeps one-shot or unresolved grant decodes out of the durable channel inventory.  A fact is accepted after two
 * matching grants on a confirmed band, or after an actual traffic decoder independently confirms its frequency and
 * timeslot.
 */
class P25GrantFactConfirmationTracker
{
    private static final long CANDIDATE_TTL_MILLISECONDS = 600_000L;
    private static final FactConfirmationPolicy POLICY =
        new FactConfirmationPolicy(2, 1L, CANDIDATE_TTL_MILLISECONDS, false);
    private final Map<FactIdentity,StableFactTracker<Candidate,FactValue>> mTrackers = new HashMap<>();

    synchronized P25ActivityLogRecords.ChannelFact observe(P25GrantObservationEvent event,
                                                           P25ActivityLogRecords.ActivityEvent activity)
    {
        Candidate candidate = candidate(event, activity);

        if(candidate == null || !event.confirmedBand())
        {
            return null;
        }

        prune(activity.observedAtEpochMilliseconds());
        FactIdentity identity = new FactIdentity(candidate.fact().guid(), candidate.fact().lcn(),
            candidate.fact().serviceTag());
        StableFactTracker<Candidate,FactValue> tracker = mTrackers.computeIfAbsent(identity,
            ignored -> new StableFactTracker<>(Candidate::value));
        StableFactTracker.Result result = tracker.observe(candidate, activity.observedAtEpochMilliseconds(), POLICY,
            ignored -> true);

        if(result == StableFactTracker.Result.PROMOTED ||
            (!event.continuation() && tracker.hasStableValue() &&
                Objects.equals(tracker.getStableValue().value(), candidate.value())))
        {
            return candidate.fact();
        }

        return null;
    }

    synchronized List<P25ActivityLogRecords.ChannelFact> confirm(P25TrafficChannelConfirmationEvent event)
    {
        List<P25ActivityLogRecords.ChannelFact> confirmed = new ArrayList<>();

        if(event == null || event.channel() == null || event.frequencyHertz() <= 0)
        {
            return confirmed;
        }

        prune(event.timestamp());
        String guid = event.channel().getRadresGuid();

        for(StableFactTracker<Candidate,FactValue> tracker: mTrackers.values())
        {
            Candidate candidate = tracker.getCandidateValue();

            if(candidate != null && Objects.equals(guid, candidate.fact().guid()) &&
                candidate.fact().frequencyHertz() == event.frequencyHertz() &&
                candidate.timeslot() == event.timeslot() &&
                tracker.confirmCandidate(event.timestamp(), ignored -> true) == StableFactTracker.Result.PROMOTED)
            {
                confirmed.add(new P25ActivityLogRecords.ChannelFact(event.timestamp(), candidate.fact().guid(),
                    candidate.fact().lcn(), candidate.fact().frequencyHertz(), candidate.fact().serviceTag(),
                    candidate.fact().tdma(), candidate.fact().timeslots()));
            }
        }

        return confirmed;
    }

    synchronized void reset()
    {
        mTrackers.clear();
    }

    private void prune(long timestamp)
    {
        Iterator<StableFactTracker<Candidate,FactValue>> iterator = mTrackers.values().iterator();

        while(iterator.hasNext())
        {
            StableFactTracker<Candidate,FactValue> tracker = iterator.next();
            tracker.expireCandidate(timestamp, CANDIDATE_TTL_MILLISECONDS);

            if(tracker.isEmpty())
            {
                iterator.remove();
            }
        }
    }

    private static Candidate candidate(P25GrantObservationEvent event,
                                       P25ActivityLogRecords.ActivityEvent activity)
    {
        ChannelTag serviceTag = serviceTag(activity);

        if(event == null || activity == null || activity.guid() == null || activity.guid().isBlank() ||
            activity.lcn() == null || activity.lcn().isBlank() || activity.frequencyHertz() == null ||
            activity.frequencyHertz() <= 0 || serviceTag == null)
        {
            return null;
        }

        boolean tdma = "APCO25_PHASE2".equals(activity.protocol()) ||
            (activity.decoder() != null && activity.decoder().contains("PHASE2")) ||
            activity.lcn().contains("TS");
        int timeslots = tdma ? 2 : 1;
        int timeslot = activity.timeslot() != null ? activity.timeslot() : 0;
        P25ActivityLogRecords.ChannelFact fact = new P25ActivityLogRecords.ChannelFact(
            activity.observedAtEpochMilliseconds(), activity.guid(), activity.lcn(), activity.frequencyHertz(),
            serviceTag, tdma, timeslots);
        return new Candidate(fact, timeslot);
    }

    private static ChannelTag serviceTag(P25ActivityLogRecords.ActivityEvent activity)
    {
        if(activity == null || activity.eventType() == null)
        {
            return null;
        }

        try
        {
            return ChannelTag.fromService(DecodeEventType.valueOf(activity.eventType()));
        }
        catch(IllegalArgumentException e)
        {
            return null;
        }
    }

    private record FactIdentity(String guid, String lcn, ChannelTag serviceTag)
    {
    }

    private record FactValue(long frequencyHertz, int timeslot, boolean tdma, int timeslots)
    {
    }

    private record Candidate(P25ActivityLogRecords.ChannelFact fact, int timeslot)
    {
        private FactValue value()
        {
            return new FactValue(fact.frequencyHertz(), timeslot, fact.tdma(), fact.timeslots());
        }
    }
}
