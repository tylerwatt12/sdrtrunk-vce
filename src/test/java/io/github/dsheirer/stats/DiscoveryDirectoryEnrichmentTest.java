/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.channel.ChannelDefinition.FrequencyMapEntry;
import io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Match;
import io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver.Result;
import io.github.dsheirer.service.radioreference.RadioReferenceGateway.TrunkedSiteChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DiscoveryDirectoryEnrichmentTest
{
    @Test
    void mergesVerifiedDmrLcnAndPreservesDecodedFrequenciesAndUplink()
    {
        TrunkedDiscoveryEvidence evidence = evidence("dmr", List.of(new FrequencyMapEntry(1, 450_000_000, 455_000_000)));
        TrunkedDiscoveryEvidence enriched = DiscoveryDirectoryEnrichment.merge(evidence, matched(List.of(
            channel(1, "", 451_000_000), channel(2, "", 452_000_000), channel(2, "", 452_000_000))));
        assertEquals(List.of(new FrequencyMapEntry(1, 450_000_000, 455_000_000),
            new FrequencyMapEntry(2, 452_000_000, 0)), enriched.frequencyMap());
        assertSame(evidence.identity(), enriched.identity());
        assertEquals(evidence.settings(), enriched.settings());
        assertEquals(evidence.validControlMessages(), enriched.validControlMessages());
        assertEquals(evidence.servingFrequencyHz(), enriched.servingFrequencyHz());
    }

    @Test
    void conflictingRrLcnIsOmittedEvenWhenOnlyOneFrequencyIsControl()
    {
        TrunkedDiscoveryEvidence result = DiscoveryDirectoryEnrichment.merge(evidence("dmr", List.of()),
            matched(List.of(channel(1, "", 450_000_000), channel(1, "", 451_000_000),
                channel(2, "", 452_000_000))));
        assertEquals(List.of(new FrequencyMapEntry(2, 452_000_000, 0)), result.frequencyMap());
    }

    @Test
    void usesExplicitNxdnChannelIdAndCatalogBoundsWithoutInventingNumbersOrUplink()
    {
        TrunkedDiscoveryEvidence evidence = evidence("nxdn", List.of(new FrequencyMapEntry(1, 450_000_000, 455_000_000)));
        TrunkedDiscoveryEvidence enriched = DiscoveryDirectoryEnrichment.merge(evidence, matched(List.of(
            channel(0, "2", 451_000_000), channel(2048, "", 452_000_000), channel(2049, "", 453_000_000),
            channel(0, "1-3", 454_000_000), channel(0, "", 454_000_000),
            channel(4, "", 0), channel(5, "", 10_000_000_000L))));
        assertEquals(List.of(new FrequencyMapEntry(1, 450_000_000, 0), new FrequencyMapEntry(2, 451_000_000, 0),
            new FrequencyMapEntry(2048, 452_000_000, 0)), enriched.frequencyMap());
    }

    @Test
    void unmatchedDirectoryAndP25LeaveEvidenceUnchanged()
    {
        TrunkedDiscoveryEvidence evidence = evidence("dmr", List.of());
        assertSame(evidence, DiscoveryDirectoryEnrichment.merge(evidence, Result.manual()));
        assertSame(evidence, DiscoveryDirectoryEnrichment.merge(evidence, Result.pending()));
        assertSame(evidence, DiscoveryDirectoryEnrichment.merge(evidence, null));
        TrunkedDiscoveryEvidence p25 = evidence("p25-phase1", List.of());
        assertSame(p25, DiscoveryDirectoryEnrichment.merge(p25, matched(List.of(channel(1, "", 450_000_000)))));
    }

    @Test
    void mapLimitPreservesNativeEntriesBeforeRrAndSkipsInvalidRows()
    {
        List<FrequencyMapEntry> nativeMap = new ArrayList<>();
        for(int number = 1; number <= 4096; number++) nativeMap.add(new FrequencyMapEntry(number, 450_000_000 + number, 0));
        TrunkedDiscoveryEvidence evidence = evidence("dmr", nativeMap);
        TrunkedDiscoveryEvidence enriched = DiscoveryDirectoryEnrichment.merge(evidence,
            matched(List.of(channel(4097, "", 452_000_000), channel(-1, "", 451_000_000))));
        assertEquals(4096, enriched.frequencyMap().size());
        assertEquals(nativeMap, enriched.frequencyMap());
        assertTrue(enriched.frequencyMap().stream().allMatch(entry -> entry.number() > 0));
    }

    @Test
    void invalidOrConflictingNativeNumbersCannotBeFilledFromDirectory()
    {
        TrunkedDiscoveryEvidence evidence = evidence("dmr", List.of(new FrequencyMapEntry(1, 450_000_000, 0),
            new FrequencyMapEntry(1, 451_000_000, 0), new FrequencyMapEntry(0, 450_000_000, 0),
            new FrequencyMapEntry(2, 450_000_000, -1)));
        assertEquals(List.of(new FrequencyMapEntry(3, 453_000_000, 0)), DiscoveryDirectoryEnrichment.merge(evidence,
            matched(List.of(channel(1, "", 452_000_000), channel(3, "", 453_000_000)))).frequencyMap());
    }

    private static TrunkedDiscoveryEvidence evidence(String protocol, List<FrequencyMapEntry> map)
    {
        return new TrunkedDiscoveryEvidence(protocol, "test", new TrunkedDiscoveryEvidence.Identity(null,
            "native", "native:site", null, 1, 1, null, null, null, null, null),
            Map.of("channel_mode", "TRUNKED"), map, 100, 20, 20, 0, 1, "verified", 450_000_000L);
    }

    private static TrunkedSiteChannel channel(int number, String id, long frequency)
    {
        return new TrunkedSiteChannel(frequency, number, id, "", "", false, false);
    }

    private static Result matched(List<TrunkedSiteChannel> channels)
    {
        return new Result("matched", "", new Match(1, 1, "System", "Site", channels, "", ""), "verified");
    }
}
