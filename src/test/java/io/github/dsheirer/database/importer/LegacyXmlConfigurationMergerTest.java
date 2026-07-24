/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.database.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.id.broadcast.BroadcastChannel;
import io.github.dsheirer.audio.broadcast.radioresolve.RadioResolveConfiguration;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.ConflictPolicy;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.MergeResult;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyXmlConfigurationMergerTest
{
    @Test
    void renamesConflictsAndUpdatesImportedReferences()
    {
        ConfigurationState existing = state("County", "Control", "Calls");
        ConfigurationState imported = state("County", "Control", "Calls");

        MergeResult result = LegacyXmlConfigurationMerger.merge(existing, imported, ConflictPolicy.RENAME);
        ConfigurationState merged = result.configurationState();

        assertEquals(2, merged.getAliases().size());
        assertEquals("County (Imported)", merged.getAliases().get(1).getAliasListName());
        BroadcastChannel importedBroadcast = (BroadcastChannel)merged.getAliases().get(1).getAliasIdentifiers().get(0);
        assertEquals("Calls (Imported)", importedBroadcast.getChannelName());
        assertEquals("County (Imported)", merged.getChannels().get(1).getAliasListName());
        assertEquals("Control (Imported)", merged.getChannels().get(1).getName());
        assertEquals("Calls (Imported)", merged.getBroadcastConfigurations().get(1).getName());
        assertEquals(3, result.summary().renamed());
        assertEquals(0, result.summary().skipped());
    }

    @Test
    void skipAndReplacePoliciesDoNotCreateDuplicateNames()
    {
        MergeResult skipped = LegacyXmlConfigurationMerger.merge(
            state("County", "Control", "Calls"),
            state("County", "Control", "Calls"), ConflictPolicy.SKIP);
        assertEquals(1, skipped.configurationState().getAliases().size());
        assertEquals(1, skipped.configurationState().getChannels().size());
        assertEquals(1, skipped.configurationState().getBroadcastConfigurations().size());
        assertEquals(3, skipped.summary().skipped());

        ConfigurationState existing = state("County", "Control", "Calls");
        Alias formerAlias = existing.getAliases().get(0);
        Channel formerChannel = existing.getChannels().get(0);
        MergeResult replaced = LegacyXmlConfigurationMerger.merge(existing,
            state("County", "Control", "Calls"), ConflictPolicy.REPLACE);
        assertEquals(1, replaced.configurationState().getAliases().size());
        assertEquals(1, replaced.configurationState().getChannels().size());
        assertFalse(replaced.configurationState().getAliases().contains(formerAlias));
        assertFalse(replaced.configurationState().getChannels().contains(formerChannel));
        assertEquals(3, replaced.summary().replaced());
    }

    @Test
    void previewCountsEachConflictCategory()
    {
        ConfigurationState existing = state("County", "Control", "Calls");
        ConfigurationState imported = state("county", "control", "calls");
        var preview = LegacyXmlConfigurationMerger.preview(existing, imported);

        assertEquals(3, preview.totalConflicts());
        assertEquals(1, preview.aliasListConflicts());
        assertEquals(1, preview.channelConflicts());
        assertEquals(1, preview.streamConflicts());
    }

    private static ConfigurationState state(String aliasListName, String channelName, String streamName)
    {
        ConfigurationState state = new ConfigurationState();
        Alias alias = new Alias("Dispatch");
        alias.setAliasListName(aliasListName);
        alias.addAliasID(new BroadcastChannel(streamName));
        state.setAliases(List.of(alias));

        Channel channel = new Channel(channelName);
        channel.setSystem("System");
        channel.setSite("Site");
        channel.setAliasListName(aliasListName);
        channel.setDecodeConfiguration(new DecodeConfigDMR());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(460_000_000L);
        channel.setSourceConfiguration(source);
        state.setChannels(List.of(channel));

        RadioResolveConfiguration stream = new RadioResolveConfiguration();
        stream.setName(streamName);
        state.setBroadcastConfigurations(List.of(stream));
        return state;
    }
}
