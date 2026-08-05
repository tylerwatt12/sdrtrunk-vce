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

package io.github.dsheirer.database.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.UnmatchedTalkgroupPolicy;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveConfiguration;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.MergeResult;
import io.github.dsheirer.module.decode.p25.phase1.DecodeConfigP25Phase1;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyXmlConfigurationMergerTest
{
    private static final String EXISTING_CONFIGURATION_ID = "11111111-1111-1111-1111-111111111111";
    private static final String DUPLICATE_RADIO_REFERENCE_GUID = "22222222-2222-2222-2222-222222222222";

    @Test
    void renamesAllCaseInsensitiveConflictsAndUpdatesImportedReferences()
    {
        ConfigurationState existing = state("County", "Control", "Calls", 100);
        existing.getChannels().getFirst().setConfigurationId(EXISTING_CONFIGURATION_ID);
        existing.getChannels().getFirst().setRadresGuid(DUPLICATE_RADIO_REFERENCE_GUID);
        existing.getBroadcastConfigurations().getFirst().setConfigurationId(EXISTING_CONFIGURATION_ID);

        ConfigurationState imported = state("county", "control", "calls", 200);
        imported.getAliasListDefinitions().getFirst().setUnmatchedTalkgroupPolicy(
            new UnmatchedTalkgroupPolicy(-1, true, List.of("calls")));
        Alias importedSourceAlias = imported.getAliases().getFirst();
        Channel importedSourceChannel = imported.getChannels().getFirst();
        RadioResolveConfiguration importedSourceStream =
            (RadioResolveConfiguration)imported.getBroadcastConfigurations().getFirst();
        importedSourceChannel.setConfigurationId(EXISTING_CONFIGURATION_ID);
        importedSourceChannel.setRadresGuid(DUPLICATE_RADIO_REFERENCE_GUID);
        importedSourceStream.setConfigurationId(EXISTING_CONFIGURATION_ID);

        var preview = LegacyXmlConfigurationMerger.preview(existing, imported);
        assertEquals(1, preview.aliasListCount());
        assertEquals(1, preview.aliasCount());
        assertEquals(1, preview.channelCount());
        assertEquals(1, preview.streamCount());
        assertEquals(1, preview.aliasListConflicts());
        assertEquals(1, preview.channelConflicts());
        assertEquals(1, preview.streamConflicts());
        assertEquals(3, preview.totalConflicts());

        MergeResult result = LegacyXmlConfigurationMerger.merge(existing, imported);
        ConfigurationState merged = result.configurationState();

        assertSame(existing.getAliasListDefinitions().getFirst(), merged.getAliasListDefinitions().getFirst());
        assertSame(existing.getAliases().getFirst(), merged.getAliases().getFirst());
        assertSame(existing.getChannels().getFirst(), merged.getChannels().getFirst());
        assertSame(existing.getBroadcastConfigurations().getFirst(),
            merged.getBroadcastConfigurations().getFirst());

        AliasListDefinition importedDefinition = merged.getAliasListDefinitions().get(1);
        assertNotSame(imported.getAliasListDefinitions().getFirst(), importedDefinition);
        assertEquals("county (Imported)", importedDefinition.getName());
        assertEquals(AliasListFamily.P25, importedDefinition.getFamily());
        assertEquals(AliasListDefinition.UNASSIGNED_ID, importedDefinition.getId());
        assertEquals(-1, importedDefinition.getUnmatchedTalkgroupPolicy().getPlaybackPriority());
        assertTrue(importedDefinition.getUnmatchedTalkgroupPolicy().isRecordEnabled());
        assertEquals(List.of("calls (Imported)"),
            importedDefinition.getUnmatchedTalkgroupPolicy().getStreamDestinationNames());

        Alias importedAlias = merged.getAliases().get(1);
        assertNotSame(importedSourceAlias, importedAlias);
        assertEquals("county (Imported)", importedAlias.getAliasListName());
        assertEquals(Alias.UNASSIGNED_ALIAS_LIST_ID, importedAlias.getAliasListId());
        assertTrue(importedAlias.hasBroadcastChannel("calls (Imported)"));
        assertEquals(200, ((Talkgroup)importedAlias.getMatchIdentifier()).getValue());

        Channel importedChannel = merged.getChannels().get(1);
        assertSame(importedSourceChannel, importedChannel);
        assertEquals("control (Imported)", importedChannel.getName());
        assertEquals("county (Imported)", importedChannel.getAliasListName());
        assertNotEquals(EXISTING_CONFIGURATION_ID, importedChannel.getConfigurationId());
        assertFalse(importedChannel.hasRadresGuid());

        RadioResolveConfiguration importedStream =
            (RadioResolveConfiguration)merged.getBroadcastConfigurations().get(1);
        assertSame(importedSourceStream, importedStream);
        assertEquals("calls (Imported)", importedStream.getName());
        assertNotEquals(EXISTING_CONFIGURATION_ID, importedStream.getConfigurationId());

        assertEquals("County", merged.getAliasListDefinitions().getFirst().getName());
        assertEquals("Control", merged.getChannels().getFirst().getName());
        assertEquals("Calls", merged.getBroadcastConfigurations().getFirst().getName());
        assertEquals(EXISTING_CONFIGURATION_ID, merged.getChannels().getFirst().getConfigurationId());

        assertEquals(1, result.summary().aliasListCount());
        assertEquals(1, result.summary().aliasCount());
        assertEquals(1, result.summary().channelCount());
        assertEquals(1, result.summary().streamCount());
        assertEquals(4, result.summary().totalImported());
        assertEquals(1, result.summary().renamedAliasLists());
        assertEquals(1, result.summary().renamedChannels());
        assertEquals(1, result.summary().renamedStreams());
        assertEquals(3, result.summary().totalRenamed());
    }

    @Test
    void preservesNonConflictingNamesContentAndUniqueRadioReferenceGuid()
    {
        ConfigurationState imported = state("Metro", "Primary", "Metro Calls", 1234);
        Alias sourceAlias = imported.getAliases().getFirst();
        sourceAlias.setDescription("Primary dispatch");
        sourceAlias.setGroup("Dispatch");
        sourceAlias.setRecordable(true);
        sourceAlias.setCallPriority(25);

        Channel sourceChannel = imported.getChannels().getFirst();
        sourceChannel.setConfigurationId(EXISTING_CONFIGURATION_ID);
        sourceChannel.setRadresGuid(DUPLICATE_RADIO_REFERENCE_GUID);
        RadioResolveConfiguration sourceStream =
            (RadioResolveConfiguration)imported.getBroadcastConfigurations().getFirst();
        sourceStream.setConfigurationId(EXISTING_CONFIGURATION_ID);

        MergeResult result = LegacyXmlConfigurationMerger.merge(new ConfigurationState(), imported);
        ConfigurationState merged = result.configurationState();

        assertEquals("Metro", merged.getAliasListDefinitions().getFirst().getName());
        assertEquals("Primary", merged.getChannels().getFirst().getName());
        assertEquals("Metro Calls", merged.getBroadcastConfigurations().getFirst().getName());
        assertEquals(DUPLICATE_RADIO_REFERENCE_GUID, merged.getChannels().getFirst().getRadresGuid());
        assertNotEquals(EXISTING_CONFIGURATION_ID, merged.getChannels().getFirst().getConfigurationId());
        assertNotEquals(EXISTING_CONFIGURATION_ID,
            merged.getBroadcastConfigurations().getFirst().getConfigurationId());

        Alias copiedAlias = merged.getAliases().getFirst();
        assertNotSame(sourceAlias, copiedAlias);
        assertEquals("Primary dispatch", copiedAlias.getDescription());
        assertEquals("Dispatch", copiedAlias.getGroup());
        assertTrue(copiedAlias.isRecordable());
        assertEquals(25, copiedAlias.getPlaybackPriority());
        assertTrue(copiedAlias.hasBroadcastChannel("Metro Calls"));
        assertEquals(1234, ((Talkgroup)copiedAlias.getMatchIdentifier()).getValue());

        assertEquals(4, result.summary().totalImported());
        assertEquals(0, result.summary().totalRenamed());
        assertEquals(0, LegacyXmlConfigurationMerger.preview(new ConfigurationState(), imported).totalConflicts());
    }

    @Test
    void usesNumberedSuffixWhenImportedNameIsAlreadyReserved()
    {
        ConfigurationState existing = state("County", "Control", "Calls", 100);
        append(existing, state("County (Imported)", "Control (Imported)", "Calls (Imported)", 101));
        ConfigurationState imported = state("COUNTY", "CONTROL", "CALLS", 200);

        MergeResult result = LegacyXmlConfigurationMerger.merge(existing, imported);
        ConfigurationState merged = result.configurationState();

        assertEquals("COUNTY (Imported 2)", merged.getAliasListDefinitions().get(2).getName());
        assertEquals("CONTROL (Imported 2)", merged.getChannels().get(2).getName());
        assertEquals("CALLS (Imported 2)", merged.getBroadcastConfigurations().get(2).getName());
        assertEquals("COUNTY (Imported 2)", merged.getAliases().get(2).getAliasListName());
        assertTrue(merged.getAliases().get(2).hasBroadcastChannel("CALLS (Imported 2)"));
        assertEquals(3, result.summary().totalRenamed());
    }

    private static ConfigurationState state(String aliasListName, String channelName, String streamName, int talkgroup)
    {
        ConfigurationState state = new ConfigurationState();
        AliasListDefinition definition =
            new AliasListDefinition(aliasListName, AliasListFamily.P25);
        Alias alias = new Alias("Talkgroup " + talkgroup);
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.APCO25, talkgroup));
        alias.addBroadcastChannel(streamName);

        Channel channel = new Channel(channelName);
        channel.setSystem("System");
        channel.setSite("Site");
        channel.setAliasListName(aliasListName);
        channel.setDecodeConfiguration(new DecodeConfigP25Phase1());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(851_000_000L + talkgroup);
        channel.setSourceConfiguration(source);

        RadioResolveConfiguration stream = new RadioResolveConfiguration();
        stream.setName(streamName);

        state.setAliasListDefinitions(List.of(definition));
        state.setAliases(List.of(alias));
        state.setChannels(List.of(channel));
        state.setBroadcastConfigurations(List.of(stream));
        return state;
    }

    private static void append(ConfigurationState target, ConfigurationState addition)
    {
        target.setAliasListDefinitions(concat(target.getAliasListDefinitions(),
            addition.getAliasListDefinitions()));
        target.setAliases(concat(target.getAliases(), addition.getAliases()));
        target.setChannels(concat(target.getChannels(), addition.getChannels()));
        target.setBroadcastConfigurations(concat(target.getBroadcastConfigurations(),
            addition.getBroadcastConfigurations()));
    }

    private static <T> List<T> concat(List<T> first, List<T> second)
    {
        java.util.ArrayList<T> combined = new java.util.ArrayList<>(first);
        combined.addAll(second);
        return combined;
    }
}
