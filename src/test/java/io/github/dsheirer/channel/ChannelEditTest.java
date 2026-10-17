/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.channel;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.module.decode.DecoderType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChannelEditTest
{
    private static final String ID = "00000000-0000-4000-8000-000000000001";

    @Test
    void unchangedFieldsKeepLearnedFrequenciesPreferredControlAndIdentity()
    {
        ChannelDefinition original = definition("Control", List.of(851_012_500L), 851_012_500L, Map.of("skip", false));
        ChannelDefinition current = definition("Control", List.of(851_012_500L, 852_012_500L), 852_012_500L,
            Map.of("skip", false));
        current = observed(current, new ChannelDefinition.Observed(List.of(852_012_500L),
            Map.of("wacn", 0xBEE00, "system", 0x123, "rfss", 1, "site", 2)));
        ChannelDefinition draft = definition("Renamed", original.source().frequenciesHz(), 851_012_500L,
            original.settings());

        ChannelDefinition merged = ChannelEdit.merge(ID, ChannelEdit.version(original), original, draft, current);
        assertEquals("Renamed", merged.name());
        assertEquals(current.source(), merged.source());
        assertEquals(current.observed(), merged.observed());
    }

    @Test
    void independentDecoderSettingsMergeAndJsonNumberTypesDoNotCreateConflicts()
    {
        ChannelDefinition original = definition("Control", List.of(851_012_500L), null,
            Map.of("threshold", 0.25f, "skip", false));
        ChannelDefinition current = definition("Control", original.source().frequenciesHz(), null,
            Map.of("threshold", 0.5f, "skip", false));
        ChannelDefinition draft = definition("Control", original.source().frequenciesHz(), null,
            Map.of("threshold", 0.25d, "skip", true));
        ChannelDefinition merged = ChannelEdit.merge(ID, ChannelEdit.version(original), original, draft, current);
        assertEquals(0.5f, merged.settings().get("threshold"));
        assertEquals(true, merged.settings().get("skip"));
    }

    @Test
    void conflictingNamesFrequenciesAndDecoderSettingsAreRejected()
    {
        ChannelDefinition original = definition("Control", List.of(851_012_500L), null, Map.of("pool", 10));
        assertThrows(ChannelAdministrationService.StaleChannelException.class, () -> ChannelEdit.merge(ID,
            ChannelEdit.version(original), original,
            definition("My name", original.source().frequenciesHz(), null, original.settings()),
            definition("Their name", original.source().frequenciesHz(), null, original.settings())));
        assertThrows(ChannelAdministrationService.StaleChannelException.class, () -> ChannelEdit.merge(ID,
            ChannelEdit.version(original), original,
            definition("Control", List.of(852_012_500L), null, original.settings()),
            definition("Control", List.of(853_012_500L), null, original.settings())));
        assertThrows(ChannelAdministrationService.StaleChannelException.class, () -> ChannelEdit.merge(ID,
            ChannelEdit.version(original), original,
            definition("Control", original.source().frequenciesHz(), null, Map.of("pool", 20)),
            definition("Control", original.source().frequenciesHz(), null, Map.of("pool", 30))));
    }

    @Test
    void identicalChangesAndEditsWithACurrentTokenAreAccepted()
    {
        ChannelDefinition original = definition("Control", List.of(851_012_500L), null, Map.of());
        ChannelDefinition draft = definition("Renamed", original.source().frequenciesHz(), null, Map.of());
        assertEquals(draft, ChannelEdit.merge(ID, ChannelEdit.version(original), original, draft, draft));
        assertEquals(draft, ChannelEdit.merge(ID, ChannelEdit.version(original), null, draft, original));
        assertThrows(ChannelAdministrationService.StaleChannelException.class, () -> ChannelEdit.merge(ID,
            ChannelEdit.version(original), null, draft, draft));
    }

    @Test
    void versionsIgnoreObservedMetadataAndMapInsertionOrder()
    {
        var first = new LinkedHashMap<String,Object>();
        first.put("a", 1); first.put("b", true);
        var second = new LinkedHashMap<String,Object>();
        second.put("b", true); second.put("a", 1);
        ChannelDefinition original = definition("Control", List.of(851_012_500L), null, first);
        ChannelDefinition learned = observed(definition("Control", original.source().frequenciesHz(), null, second),
            new ChannelDefinition.Observed(List.of(), Map.of("site", 2)));
        assertEquals(ChannelEdit.version(original), ChannelEdit.version(learned));
        assertThrows(IllegalArgumentException.class, () -> ChannelEdit.merge(ID, "wrong", original, original, learned));
    }

    @Test
    void everyProtocolOriginalSurvivesTheBrowserJsonRoundTrip() throws Exception
    {
        var registry = new ChannelProtocolRegistry();
        var codec = new ChannelDefinitionCodec(registry);
        var json = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        for(DecoderType decoder: DecoderType.PRIMARY_DECODERS)
        {
            var profile = registry.require(decoder);
            var list = new AliasListDefinition("Protocol list", profile.aliasFamily());
            list.setId(1);
            var source = new ChannelDefinition.Source(List.of(851_012_500L), null, null, null, null, null);
            var template = new ChannelDefinition(null, profile.id(), "System", "Site", "Name", null, 1,
                source, profile.defaultSettings(), List.of(), List.of(), List.of(), List.of(),
                ChannelDefinition.Observed.EMPTY);
            ChannelDefinition original = codec.fromChannel(codec.toChannel(template, list, null));
            var browserDocument = json.valueToTree(original);
            var browserSettings = (com.fasterxml.jackson.databind.node.ObjectNode)browserDocument.path("settings");
            browserSettings.properties().stream().toList().forEach(field -> {
                var value = field.getValue();
                if(value.isNumber() && value.doubleValue() == Math.rint(value.doubleValue()))
                    browserSettings.put(field.getKey(), value.longValue());
            });
            ChannelDefinition roundTrip = json.treeToValue(browserDocument, ChannelDefinition.class);
            assertEquals(ChannelEdit.version(original), ChannelEdit.version(roundTrip), profile.id());
            assertDoesNotThrow(() -> ChannelEdit.merge(original.configurationId(), ChannelEdit.version(original),
                roundTrip, roundTrip, original), profile.id());
        }
    }

    private static ChannelDefinition definition(String name, List<Long> frequencies, Long preferred,
                                                Map<String,Object> settings)
    {
        return new ChannelDefinition(ID, "p25-phase1", "System", "Site", name, null, 1,
            new ChannelDefinition.Source(frequencies, null, null, preferred, null, 500), settings,
            List.of(), List.of(), List.of(), List.of(), ChannelDefinition.Observed.EMPTY);
    }

    private static ChannelDefinition observed(ChannelDefinition channel, ChannelDefinition.Observed observed)
    {
        return new ChannelDefinition(channel.configurationId(), channel.protocolId(), channel.system(), channel.site(),
            channel.name(), channel.radioResolveId(), channel.aliasListId(), channel.source(), channel.settings(),
            channel.frequencyMap(), channel.eventLogs(), channel.recorders(), channel.auxiliaryDecoders(), observed);
    }
}
