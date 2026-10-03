/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.http;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.dsheirer.channel.ChannelDefinition;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Shared bounds for explicitly reviewed discovery channel maps. Receiver identity never comes from these edits. */
final class DiscoveryRequestSupport
{
    private DiscoveryRequestSupport() { }

    static List<ChannelDefinition.FrequencyMapEntry> frequencyMap(JsonNode body)
    {
        JsonNode map = body.get("frequency_map");
        if(map == null || map.isNull()) return List.of();
        if(!map.isArray() || map.size() > 4096) throw new IllegalArgumentException("frequency_map is invalid");
        List<ChannelDefinition.FrequencyMapEntry> entries = new ArrayList<>();
        Set<Integer> numbers = new java.util.HashSet<>();
        for(JsonNode entry: map)
        {
            if(!entry.isObject()) throw new IllegalArgumentException("frequency_map entries must be objects");
            entry.fieldNames().forEachRemaining(field -> {
                if(!Set.of("number", "downlink_hz", "uplink_hz").contains(field))
                    throw new IllegalArgumentException("Unknown frequency_map field: " + field);
            });
            long number = integer(entry, "number", 1, Integer.MAX_VALUE);
            if(!numbers.add((int)number)) throw new IllegalArgumentException("frequency_map numbers must be unique");
            entries.add(new ChannelDefinition.FrequencyMapEntry((int)number,
                integer(entry, "downlink_hz", 1, 100_000_000_000L),
                entry.has("uplink_hz") ? integer(entry, "uplink_hz", 0, 100_000_000_000L) : 0));
        }
        return List.copyOf(entries);
    }

    private static long integer(JsonNode body, String field, long minimum, long maximum)
    {
        JsonNode value = body.get(field);
        if(value == null || !value.isIntegralNumber() || !value.canConvertToLong() ||
            value.longValue() < minimum || value.longValue() > maximum)
            throw new IllegalArgumentException("frequency_map " + field + " is invalid");
        return value.longValue();
    }
}
