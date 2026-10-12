/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.source.tuner.manager;

import io.github.dsheirer.source.tuner.channel.TunerChannel;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationContext;
import io.github.dsheirer.source.tuner.diagnostic.TunerAllocationKnownSite;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Bounded placement hints. New or contended observations never gate source admission. */
final class TunerFrequencyKnowledge
{
    private static final int MAXIMUM_SITES = 128;
    private static final int MAXIMUM_FREQUENCIES = 256;
    private final AtomicReferenceArray<Site> mSites = new AtomicReferenceArray<>(MAXIMUM_SITES);

    boolean observe(TunerAllocationContext context, long frequencyHz, int bandwidthHz)
    {
        if(context == null || context.channelId() == null || frequencyHz <= 0 || bandwidthHz <= 0) return false;
        Site site = find(context.channelId());
        if(site == null)
        {
            Site fresh = new Site(context);
            for(int index = 0; index < MAXIMUM_SITES && site == null; index++)
            {
                Site existing = mSites.get(index);
                if(existing != null && context.channelId().equals(existing.context.channelId())) site = existing;
                else if(existing == null)
                {
                    if(mSites.compareAndSet(index, null, fresh)) site = fresh;
                    else
                    {
                        existing = mSites.get(index);
                        if(existing != null && context.channelId().equals(existing.context.channelId())) site = existing;
                    }
                }
            }
            if(site == null) return false;
        }
        State original = site.state.get();
        List<TunerAllocationKnownSite.Frequency> current = original.frequencies();
        int replace = -1;
        for(int index = 0; index < current.size(); index++)
        {
            TunerAllocationKnownSite.Frequency frequency = current.get(index);
            if(frequency.frequencyHz() == frequencyHz)
            {
                boolean controlUpgrade = context.role() == TunerAllocationContext.Role.CONTROL &&
                    frequency.role() != TunerAllocationContext.Role.CONTROL;
                if(bandwidthHz <= frequency.bandwidthHz() && !controlUpgrade) return false;
                bandwidthHz = Math.max(bandwidthHz, (int)frequency.bandwidthHz());
                replace = index;
                break;
            }
        }
        if(replace < 0 && current.size() >= MAXIMUM_FREQUENCIES) return false;
        List<TunerAllocationKnownSite.Frequency> updated = new ArrayList<>(current.size() + 1);
        updated.addAll(current);
        TunerAllocationContext.Role role = replace >= 0 && current.get(replace).role() == TunerAllocationContext.Role.CONTROL ?
            TunerAllocationContext.Role.CONTROL : context.role();
        TunerAllocationKnownSite.Frequency observed = new TunerAllocationKnownSite.Frequency(frequencyHz, bandwidthHz, role);
        if(replace < 0) updated.add(observed); else updated.set(replace, observed);
        List<TunerAllocationKnownSite.Frequency> frequencies = List.copyOf(updated);
        List<TunerChannel> channels = frequencies.stream().map(frequency ->
            new TunerChannel(frequency.frequencyHz(), (int)frequency.bandwidthHz())).toList();
        return site.state.compareAndSet(original, new State(frequencies, channels));
    }

    List<TunerChannel> channels(String channelId)
    {
        Site site = find(channelId);
        if(site == null) return List.of();
        return site.state.get().channels();
    }

    List<TunerAllocationKnownSite> snapshot()
    {
        List<TunerAllocationKnownSite> sites = new ArrayList<>();
        for(int index = 0; index < MAXIMUM_SITES; index++)
        {
            Site site = mSites.get(index);
            if(site != null) sites.add(new TunerAllocationKnownSite(site.context, site.state.get().frequencies()));
        }
        return List.copyOf(sites);
    }

    private Site find(String channelId)
    {
        if(channelId == null) return null;
        for(int index = 0; index < MAXIMUM_SITES; index++)
        {
            Site site = mSites.get(index);
            if(site != null && channelId.equals(site.context.channelId())) return site;
        }
        return null;
    }

    private static final class Site
    {
        private final TunerAllocationContext context;
        private final AtomicReference<State> state = new AtomicReference<>(new State(List.of(), List.of()));

        private Site(TunerAllocationContext context) { this.context = context; }
    }

    private record State(List<TunerAllocationKnownSite.Frequency> frequencies, List<TunerChannel> channels) {}
}
