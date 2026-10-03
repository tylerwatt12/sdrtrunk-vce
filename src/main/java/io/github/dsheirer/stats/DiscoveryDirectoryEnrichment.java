/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.channel.ChannelDefinition.FrequencyMapEntry;
import io.github.dsheirer.channel.ChannelProtocolRegistry;
import io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Result;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteChannel;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Pure, conservative merger of verified RR site channels into a decoded DMR/NXDN channel map. */
public final class DiscoveryDirectoryEnrichment
{
    private static final long MAXIMUM_FREQUENCY_HZ = 9_999_999_999L;
    private static final int MAXIMUM_MAP_ENTRIES = 4096;
    private static final ChannelProtocolRegistry PROTOCOLS = new ChannelProtocolRegistry();

    private DiscoveryDirectoryEnrichment() { }

    public static TrunkedDiscoveryEvidence merge(TrunkedDiscoveryEvidence evidence, Result result)
    {
        if(evidence == null || result == null || !result.matched() ||
            !Set.of("dmr", "nxdn").contains(evidence.protocolId())) return evidence;
        var rule = PROTOCOLS.require(evidence.protocolId()).fields().get("frequency_map");
        int minimum = rule.numberMinimum() != null ? rule.numberMinimum() : 1;
        int maximum = rule.numberMaximum() != null ? rule.numberMaximum() : Integer.MAX_VALUE;
        Map<Integer,FrequencyMapEntry> nativeMap = new TreeMap<>();
        Set<Integer> nativeConflicts = new HashSet<>();
        for(FrequencyMapEntry entry: evidence.frequencyMap())
        {
            if(entry == null || !valid(entry.number(), entry.downlinkHz(), minimum, maximum) ||
                entry.uplinkHz() < 0 || entry.uplinkHz() > MAXIMUM_FREQUENCY_HZ) continue;
            FrequencyMapEntry normalized = rule.showUplink() ? entry :
                new FrequencyMapEntry(entry.number(), entry.downlinkHz(), 0);
            FrequencyMapEntry previous = nativeMap.putIfAbsent(entry.number(), normalized);
            if(previous != null && !previous.equals(normalized)) nativeConflicts.add(entry.number());
        }
        nativeConflicts.forEach(nativeMap::remove);

        Map<Integer,FrequencyMapEntry> remoteMap = new TreeMap<>();
        Set<Integer> remoteConflicts = new HashSet<>();
        for(TrunkedSiteChannel channel: result.match().channels())
        {
            int number = number(channel, "nxdn".equals(evidence.protocolId()));
            if(!valid(number, channel.frequencyHz(), minimum, maximum) || nativeMap.containsKey(number) ||
                nativeConflicts.contains(number)) continue;
            // RR site snapshots contain downlink only. An uplink is never inferred from an offset or bandplan.
            FrequencyMapEntry entry = new FrequencyMapEntry(number, channel.frequencyHz(), 0);
            FrequencyMapEntry previous = remoteMap.putIfAbsent(number, entry);
            if(previous != null && previous.downlinkHz() != entry.downlinkHz()) remoteConflicts.add(number);
        }
        remoteConflicts.forEach(remoteMap::remove);
        Map<Integer,FrequencyMapEntry> merged = new TreeMap<>();
        nativeMap.values().stream().limit(MAXIMUM_MAP_ENTRIES).forEach(entry -> merged.put(entry.number(), entry));
        int remaining = MAXIMUM_MAP_ENTRIES - merged.size();
        remoteMap.values().stream().limit(remaining).forEach(entry -> merged.put(entry.number(), entry));
        List<FrequencyMapEntry> map = List.copyOf(merged.values());
        if(map.equals(evidence.frequencyMap())) return evidence;
        return new TrunkedDiscoveryEvidence(evidence.protocolId(), evidence.variant(), evidence.identity(),
            evidence.settings(), map, evidence.qualityPct(), evidence.validMessages(), evidence.validControlMessages(),
            evidence.invalidControlMessages(), evidence.checkedAtMs(), evidence.reason(), evidence.servingFrequencyHz());
    }

    private static boolean valid(int number, long frequencyHz, int minimum, int maximum)
    {
        return number >= minimum && number <= maximum && frequencyHz > 0 && frequencyHz <= MAXIMUM_FREQUENCY_HZ;
    }

    private static int number(TrunkedSiteChannel channel, boolean nxdn)
    {
        if(nxdn && channel.channelId() != null && channel.channelId().strip().matches("[0-9]{1,9}"))
        {
            try { return Integer.parseInt(channel.channelId().strip()); }
            catch(NumberFormatException exception) { return 0; }
        }
        return channel.logicalChannelNumber();
    }
}
