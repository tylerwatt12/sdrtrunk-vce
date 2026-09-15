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

package io.github.dsheirer.channel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

/**
 * UI-neutral channel administration document. Protocol settings use stable manifest field IDs instead of persisted
 * Jackson class names, and every collection is detached from the live receiver model.
 */
public record ChannelDefinition(String configurationId, String protocolId, String system, String site, String name,
                                String radioResolveId, long aliasListId, Source source, Map<String,Object> settings,
                                List<FrequencyMapEntry> frequencyMap, List<String> eventLogs, List<String> recorders,
                                List<String> auxiliaryDecoders, Observed observed)
{
    public ChannelDefinition
    {
        protocolId = Objects.requireNonNull(protocolId, "Protocol ID cannot be null");
        source = Objects.requireNonNull(source, "Source cannot be null");
        settings = immutableScalars(settings);
        frequencyMap = List.copyOf(frequencyMap != null ? frequencyMap : List.of());
        eventLogs = List.copyOf(eventLogs != null ? eventLogs : List.of());
        recorders = List.copyOf(recorders != null ? recorders : List.of());
        auxiliaryDecoders = List.copyOf(auxiliaryDecoders != null ? auxiliaryDecoders : List.of());
        observed = observed != null ? observed : Observed.EMPTY;
    }

    private static Map<String,Object> immutableScalars(Map<String,Object> values)
    {
        Map<String,Object> copy = new LinkedHashMap<>();
        if(values != null)
        {
            values.forEach((key, value) ->
            {
                if(key == null || !(value == null || value instanceof String || value instanceof Boolean ||
                    value instanceof Integer || value instanceof Long || value instanceof Float ||
                    value instanceof Double))
                {
                    throw new IllegalArgumentException("Channel settings must contain scalar values");
                }
                copy.put(key, value);
            });
        }
        return Collections.unmodifiableMap(copy);
    }

    public record Source(List<Long> frequenciesHz, Long minimumFrequencyHz, Long maximumFrequencyHz,
                         Long preferredFrequencyHz, String preferredTuner, Integer rotationDelayMs)
    {
        public Source
        {
            frequenciesHz = List.copyOf(frequenciesHz != null ? frequenciesHz : List.of());
        }
    }

    /** DMR uses number as LCN; NXDN uses it as channel number. */
    public record FrequencyMapEntry(int number, long downlinkHz, long uplinkHz)
    {
    }

    public record Observed(List<Long> learnedControlFrequenciesHz, Map<String,Object> p25SiteIdentity)
    {
        public static final Observed EMPTY = new Observed(List.of(), Map.of());

        public Observed
        {
            learnedControlFrequenciesHz = List.copyOf(learnedControlFrequenciesHz != null ?
                learnedControlFrequenciesHz : List.of());
            p25SiteIdentity = immutableScalars(p25SiteIdentity);
        }
    }
}
