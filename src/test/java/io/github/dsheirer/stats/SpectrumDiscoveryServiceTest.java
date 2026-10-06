/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelDefinition;
import io.github.dsheirer.service.radioreference.RadioReferenceDiscoveryResolver;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SpectrumDiscoveryServiceTest
{
    @Test
    void nullNewListNameRegistersTheActualSystemFallbackAndExplicitNamesRemainAuthoritative()
    {
        var definition = new ChannelDefinition(null, "p25-phase1", "County Public Safety", "North", "Control",
            null, 0, new ChannelDefinition.Source(List.of(853_162_500L), null, null, 853_162_500L,
                "Tuner", null), Map.of(), List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
        var batch = new DiscoveryAliasImportService.Batch();
        batch.register(7, SpectrumDiscoveryService.savedAliasListName(null, definition), definition.system(),
            RadioReferenceDiscoveryResolver.Result.manual());
        assertEquals("County Public Safety", batch.snapshot().targets().getFirst().aliasListName());
        assertEquals("Custom imported names", SpectrumDiscoveryService.savedAliasListName(" Custom imported names ", definition));
    }

    @Test
    void lateDirectoryNamesUpdateTheNewListDefaultForEveryTrunkedProtocolWithoutChangingExistingListChoices()
    {
        for(String protocol: List.of("p25-phase1", "dmr", "nxdn"))
        {
            var template = new ChannelDefinition(null, protocol, "P25 BEE00-49F", "Site 02-0C", "Control",
                null, 23, new ChannelDefinition.Source(List.of(853_162_500L), null, null, 853_162_500L,
                "Tuner", null), Map.of(), List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
            var aliases = List.of(new ChannelAdministrationService.DiscoveryAliasList(23, "Existing List", true));
            var original = new ChannelAdministrationService.DiscoveryReview(7, template, aliases, 23L, "P25 BEE00-49F");
            assertSame(original, SpectrumDiscoveryService.directoryReview(original,
                RadioReferenceDiscoveryResolver.Result.pending()));
            var match = new RadioReferenceDiscoveryResolver.Match(100, 200,
                "Metropolitan Emergency Communications Network", "North", List.of(),
                "https://www.radioreference.com/db/sid/100", "https://www.radioreference.com/db/site/200");
            var resolved = SpectrumDiscoveryService.directoryReview(original,
                new RadioReferenceDiscoveryResolver.Result("matched", "Matched", match, "radioreference"));
            assertEquals("Metropolitan Emergency Communications Network", resolved.template().system());
            assertEquals("North", resolved.template().site());
            assertEquals("Metropolitan Emergency Communications Network", resolved.defaultNewAliasListName());
            assertEquals(23, resolved.template().aliasListId());
            assertEquals(23L, resolved.suggestedAliasListId());
            assertEquals(aliases, resolved.aliasLists());
            assertEquals(template.source(), resolved.template().source());
            assertEquals(7, resolved.revision());
        }
    }

    @Test
    void emptyDirectoryNamesKeepTheKnownFriendlySystemAndItsGeneratedListName()
    {
        var template = new ChannelDefinition(null, "p25-phase1", "County Public Safety", "North", "Control",
            null, 0, new ChannelDefinition.Source(List.of(853_162_500L), null, null, 853_162_500L,
            "Tuner", null), Map.of(), List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
        var original = new ChannelAdministrationService.DiscoveryReview(7, template, List.of(), null, "P25 BEE00-49F");
        var match = new RadioReferenceDiscoveryResolver.Match(100, 200, "", "", List.of(), null, null);
        var resolved = SpectrumDiscoveryService.directoryReview(original,
            new RadioReferenceDiscoveryResolver.Result("matched", "Matched", match, "radioreference"));
        assertEquals("County Public Safety", resolved.template().system());
        assertEquals("North", resolved.template().site());
        assertEquals("County Public Safety", resolved.defaultNewAliasListName());
    }

    @Test
    void eligibilityReasonNamesTheOwningChannel()
    {
        var configured = new ChannelAdministrationService.DiscoveryFrequencyMatch(
            "one", "County Control", "County P25", "North", "configured");
        assertEquals("This frequency belongs to County Control.",
            SpectrumDiscoveryService.eligibilityReason(List.of(configured)));
        assertEquals("This frequency belongs to County Control and 1 other channel.",
            SpectrumDiscoveryService.eligibilityReason(List.of(configured,
                Map.of("configuration_id", "two", "name", "City Control"))));
    }

    @Test
    void eligibilityReasonDeduplicatesOwnersAndKeepsAGenericFallback()
    {
        assertEquals("This frequency belongs to County Control.",
            SpectrumDiscoveryService.eligibilityReason(List.of(
                Map.of("configuration_id", "one", "name", "County Control"),
                Map.of("configuration_id", "one", "name", "County Control"))));
        assertEquals("This frequency belongs to a known channel.",
            SpectrumDiscoveryService.eligibilityReason(List.of(Map.of("configuration_id", "one"))));
    }
}
