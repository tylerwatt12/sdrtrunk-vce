/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.module.decode.p25.telemetry;

import io.github.dsheirer.metadata.site.FactConfirmationPolicy;
import io.github.dsheirer.metadata.site.StableFactTracker;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/** Site-observer-worker-owned voice BSI confirmation. Evidence never crosses carriers or receiver generations. */
public final class P25VoiceBsiTracker
{
    private static final int MAXIMUM_CARRIERS = 256;
    private static final FactConfirmationPolicy POLICY =
        new FactConfirmationPolicy(2, 1, TimeUnit.HOURS.toMillis(2), false);
    private final Map<Long,StableFactTracker<String,String>> mCarriers = new LinkedHashMap<>();
    private long mProcessingIncarnation = Long.MIN_VALUE;
    private long mTuningGeneration = Long.MIN_VALUE;

    public void observe(long frequency, String callsign, long timestamp)
    {
        if(frequency <= 0 || timestamp <= 0 || callsign == null || callsign.isBlank()) return;
        if(!mCarriers.containsKey(frequency) && mCarriers.size() >= MAXIMUM_CARRIERS) return;
        mCarriers.computeIfAbsent(frequency, ignored -> new StableFactTracker<>(value -> value))
            .observe(callsign, timestamp, POLICY, ignored -> true);
    }

    public void generation(long processingIncarnation, long tuningGeneration)
    {
        if(mProcessingIncarnation != processingIncarnation || mTuningGeneration != tuningGeneration)
        {
            mCarriers.clear();
            mProcessingIncarnation = processingIncarnation;
            mTuningGeneration = tuningGeneration;
        }
    }

    public P25NetworkConfigurationSnapshot merge(P25NetworkConfigurationSnapshot control)
    {
        if(control == null) return null;
        var channels = new ArrayList<>(control.channels());
        for(var entry: mCarriers.entrySet())
        {
            var observation = entry.getValue().getStableObservation();
            if(observation.value() != null)
            {
                channels.add(new P25NetworkConfigurationSnapshot.Channel("base_station_voice", null,
                    entry.getKey(), null, false, 1, observation.value(), observation.lastSeenTimestamp()));
            }
        }
        return new P25NetworkConfigurationSnapshot(control.decoder(), control.network(), control.currentSite(),
            channels, control.neighborSites(), control.frequencyBands(), control.patchGroups(), control.talkerAliases(),
            control.siteStatus(), control.foreignSystemBands(), control.activePatchesObservedAtMs());
    }
}
