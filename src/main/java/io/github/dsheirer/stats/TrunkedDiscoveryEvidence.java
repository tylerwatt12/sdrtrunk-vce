/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.traffic.RadioSystemKey;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Immutable, receiver-confirmed evidence. Browser documents cannot manufacture this proof. */
public record TrunkedDiscoveryEvidence(String protocolId, String variant, Identity identity,
    Map<String,Object> settings, List<ChannelDefinition.FrequencyMapEntry> frequencyMap,
    double qualityPct, long validMessages, long validControlMessages, long invalidControlMessages,
    long checkedAtMs, String reason, Long servingFrequencyHz)
{
    public TrunkedDiscoveryEvidence
    {
        settings = Map.copyOf(settings != null ? settings : Map.of());
        frequencyMap = List.copyOf(frequencyMap != null ? frequencyMap : List.of());
    }

    public TrunkedDiscoveryEvidence(String protocolId,String variant,Identity identity,Map<String,Object> settings,
        List<ChannelDefinition.FrequencyMapEntry> frequencyMap,double qualityPct,long validMessages,long validControlMessages,
        long invalidControlMessages,long checkedAtMs,String reason)
    { this(protocolId,variant,identity,settings,frequencyMap,qualityPct,validMessages,validControlMessages,invalidControlMessages,checkedAtMs,reason,null); }

    public boolean verified()
    {
        return protocolId != null && variant != null && identity != null && validIdentity() && identity.siteKey() != null && !identity.siteKey().isBlank() &&
            List.of("p25-phase1", "dmr", "nxdn").contains(protocolId) &&
            validControlMessages >= 20 && Double.isFinite(qualityPct) && qualityPct <= 100 &&
            qualityPct >= ("p25-phase1".equals(protocolId) ? 0 : 60);
    }

    private boolean validIdentity()
    {
        if("p25-phase1".equals(protocolId)) return identity.p25() != null && Objects.equals(identity.radioSystemKey(),RadioSystemKey.p25(identity.p25()));
        if("dmr".equals(protocolId) && identity.radioSystemKey() != null)
            return Objects.equals(identity.radioSystemKey(),RadioSystemKey.dmrTier3(identity.model(),identity.network()));
        if("nxdn".equals(protocolId) && identity.radioSystemKey() != null)
            return Objects.equals(identity.radioSystemKey(),RadioSystemKey.nxdnTypeC(identity.category(),identity.system()));
        return identity.p25() == null && identity.radioSystemKey() == null;
    }

    /** Explicit administrator map edits may fill unresolved numbers; trusted mappings remain authoritative. */
    public TrunkedDiscoveryEvidence withManualFrequencyMap(List<ChannelDefinition.FrequencyMapEntry> additions)
    {
        if(additions == null || additions.isEmpty()) return this;
        if(!List.of("dmr","nxdn").contains(protocolId)) throw new IllegalArgumentException("This protocol does not use a channel map");
        if(additions.size() > 4096) throw new IllegalArgumentException("Frequency map cannot exceed 4,096 entries");
        Map<Integer,ChannelDefinition.FrequencyMapEntry> combined = new java.util.LinkedHashMap<>();
        frequencyMap.forEach(entry -> combined.put(entry.number(),entry));
        java.util.Set<Integer> submitted = new java.util.HashSet<>();
        for(var entry: additions)
        {
            if(entry == null || entry.number() <= 0 || !submitted.add(entry.number()) || entry.downlinkHz() <= 0 || entry.downlinkHz() > 100_000_000_000L || entry.uplinkHz() < 0 || entry.uplinkHz() > 100_000_000_000L)
                throw new IllegalArgumentException("Enter unique positive channel numbers and valid frequencies");
            var known = combined.get(entry.number());
            if(known != null && (known.downlinkHz() != entry.downlinkHz() || known.uplinkHz() > 0 && entry.uplinkHz() > 0 && known.uplinkHz() != entry.uplinkHz()))
                throw new IllegalArgumentException("The entered map conflicts with a confirmed channel frequency");
            if(known == null) combined.put(entry.number(),entry);
        }
        if(combined.size() > 4096) throw new IllegalArgumentException("Frequency map cannot exceed 4,096 entries");
        return new TrunkedDiscoveryEvidence(protocolId,variant,identity,settings,List.copyOf(combined.values()),qualityPct,validMessages,validControlMessages,invalidControlMessages,checkedAtMs,reason,servingFrequencyHz);
    }

    public String systemName()
    {
        if(identity == null) return protocolId;
        if(identity.p25() != null) return String.format(Locale.ROOT, "P25 %05X-%03X",
            identity.p25().wacn(), identity.p25().system());
        String family = "dmr".equals(protocolId) ? "DMR" : "NXDN";
        return family + " " + variant + (identity.system() != null ? " " + identity.system() : "");
    }

    public String siteName() { return identity != null && identity.site() != null ? "Site " + identity.site() : "Observed site"; }
    public String groupId() { return identity.radioSystemKey() != null ? identity.radioSystemKey() : identity.siteKey(); }

    public static TrunkedDiscoveryEvidence p25(P25DiscoveryProbe.Status status, long now)
    {
        if(status == null || !"ready".equals(status.state()) || status.identity() == null) return null;
        var mode = "C4FM".equals(status.selectedModulation()) ? status.c4fm() :
            "CQPSK".equals(status.selectedModulation()) ? status.cqpsk() : null;
        if(mode == null || !mode.confirmed() || !status.identity().equals(mode.identity())) return null;
        var observed = status.identity();
        var p25 = new P25SiteIdentity(observed.wacn(), observed.system(), observed.rfss(), observed.site());
        String key = RadioSystemKey.p25(p25);
        return new TrunkedDiscoveryEvidence("p25-phase1", "P25_PHASE_1",
            new Identity(p25, key, key + ':' + p25.rfss() + ':' + p25.site(), null, p25.system(), p25.site(),
                null, null, null, null, null),
            Map.of("modulation", status.selectedModulation(), "learn_announced_control_channels", true), List.of(),
            mode.qualityPct(), mode.validMessages(), mode.validControlMessages(), mode.invalidControlMessages(),
            now, status.reason(), mode.servingControlFrequencyHz() != null && Math.abs(mode.servingControlFrequencyHz()-status.frequencyHz()) <= 6250 ? mode.servingControlFrequencyHz() : null);
    }

    /** Null native key means the on-air variant supplies only a local identity, scoped to its observed frequency. */
    public record Identity(P25SiteIdentity p25, String radioSystemKey, String siteKey, Integer network,
        Integer system, Integer site, String model, String category, Integer integrator, Integer ran, Integer colorCode) { }
}
