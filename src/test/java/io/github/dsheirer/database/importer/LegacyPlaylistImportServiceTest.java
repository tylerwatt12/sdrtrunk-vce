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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationSnapshotDatabaseStore;
import io.github.dsheirer.database.scanlist.ScanListDatabaseStore;
import io.github.dsheirer.database.importer.LegacyPlaylistImportService.ImportResult;
import io.github.dsheirer.database.importer.LegacyPlaylistImportService.PreparedImport;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.scanlist.ScanListConfiguration;
import io.github.dsheirer.source.config.SourceConfigTuner;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LegacyPlaylistImportServiceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void mergesIntoExistingDatabaseWithBackupAndLeavesSourceUntouched() throws Exception
    {
        Path database = mTemporaryFolder.resolve("database").resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        new ConfigurationSnapshotDatabaseStore(database).replace(existingState());

        Path xml = mTemporaryFolder.resolve("legacy.xml");
        Files.writeString(xml, """
            <playlist version="4">
              <alias name="Imported Dispatch" list="County">
                <id type="talkgroup" protocol="DMR" value="200"/>
              </alias>
              <channel system="County" site="Site" name="Control">
                <alias_list_name>County</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="452000000"/>
                <aux_decode_configuration/>
                <decode_configuration type="decodeConfigDMR" ignore_data_calls="false"/>
                <event_log_configuration/>
                <record_configuration/>
              </channel>
            </playlist>
            """);
        byte[] sourceBefore = Files.readAllBytes(xml);

        LegacyPlaylistImportService service = new LegacyPlaylistImportService(database);
        PreparedImport prepared = service.prepare(xml);
        assertEquals(1, prepared.preview().aliasListCount());
        assertEquals(1, prepared.preview().aliasCount());
        assertEquals(1, prepared.preview().channelCount());
        assertEquals(1, prepared.preview().aliasListConflicts());
        assertEquals(1, prepared.preview().channelConflicts());

        ImportResult result = service.execute(prepared);

        assertArrayEquals(sourceBefore, Files.readAllBytes(xml));
        assertTrue(Files.isRegularFile(result.backupPath()));
        assertTrue(result.backupPath().startsWith(database.getParent().resolve("backups")));
        assertEquals(1, result.summary().renamedAliasLists());
        assertEquals(1, result.summary().renamedChannels());
        SdrTrunkDatabaseStartup.validateGlobalDatabase(database);
        SdrTrunkDatabaseStartup.validateGlobalDatabase(result.backupPath());

        AliasDatabaseStore aliasStore = new AliasDatabaseStore(database);
        List<AliasListDefinition> definitions = aliasStore.loadAliasListDefinitions();
        List<Alias> aliases = aliasStore.loadAliases(definitions);
        assertEquals(List.of("County", "County (Imported)"),
            definitions.stream().map(AliasListDefinition::getName).toList());
        assertEquals(2, aliases.size());
        assertTrue(aliases.stream().anyMatch(alias ->
            "Imported Dispatch".equals(alias.getName()) &&
                "County (Imported)".equals(alias.getAliasListName())));
        ScanListConfiguration scanLists = new ScanListDatabaseStore(database).loadConfiguration();
        long defaultScanListId = scanLists.defaultScanList().getId();
        Alias importedAlias = aliases.stream().filter(alias -> "Imported Dispatch".equals(alias.getName()))
            .findFirst().orElseThrow();
        AliasListDefinition importedDefinition = definitions.stream()
            .filter(definition -> "County (Imported)".equals(definition.getName())).findFirst().orElseThrow();
        assertEquals(java.util.Set.of(defaultScanListId), scanLists.scanListIdsForAlias(importedAlias.getId()));
        assertEquals(java.util.Set.of(defaultScanListId),
            scanLists.scanListIdsForUnmatchedTalkgroups(importedDefinition.getId()));

        ConfigurationState merged = new ConfigurationDatabaseStore(database).loadConfigurationState();
        assertEquals(List.of("Control", "Control (Imported)"),
            merged.getChannels().stream().map(Channel::getName).toList());
        assertEquals("County (Imported)", merged.getChannels().get(1).getAliasListName());

        AliasDatabaseStore backupAliasStore = new AliasDatabaseStore(result.backupPath());
        List<AliasListDefinition> backupDefinitions = backupAliasStore.loadAliasListDefinitions();
        assertEquals(List.of("County"),
            backupDefinitions.stream().map(AliasListDefinition::getName).toList());
        assertEquals(1, new ConfigurationDatabaseStore(result.backupPath())
            .loadConfigurationState().getChannels().size());
    }

    @Test
    void refusesSameShapePlaylistChangesAfterPreview() throws Exception
    {
        Path database = mTemporaryFolder.resolve("changed").resolve("database").resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        new ConfigurationSnapshotDatabaseStore(database).replace(existingState());
        Path xml = mTemporaryFolder.resolve("changed").resolve("legacy.xml");
        Files.createDirectories(xml.getParent());
        Files.writeString(xml, playlist("Dispatch A", 200, 452_000_000L));

        LegacyPlaylistImportService service = new LegacyPlaylistImportService(database);
        PreparedImport prepared = service.prepare(xml);
        Files.writeString(xml, playlist("Dispatch B", 201, 453_000_000L));

        assertThrows(IOException.class, () -> service.execute(prepared));
        assertFalse(Files.exists(database.getParent().resolve("backups")));
        assertEquals(1, new ConfigurationDatabaseStore(database).loadConfigurationState().getChannels().size());
    }

    @Test
    void refusesSameShapeConfigurationChangesAfterPreview() throws Exception
    {
        Path database = mTemporaryFolder.resolve("configuration-changed").resolve("database")
            .resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        new ConfigurationSnapshotDatabaseStore(database).replace(existingState());
        Path xml = mTemporaryFolder.resolve("configuration-changed").resolve("legacy.xml");
        Files.createDirectories(xml.getParent());
        Files.writeString(xml, playlist("Imported Dispatch", 200, 452_000_000L));

        LegacyPlaylistImportService service = new LegacyPlaylistImportService(database);
        PreparedImport prepared = service.prepare(xml);

        AliasDatabaseStore aliasStore = new AliasDatabaseStore(database);
        List<AliasListDefinition> definitions = aliasStore.loadAliasListDefinitions();
        List<Alias> aliases = aliasStore.loadAliases(definitions);
        aliases.getFirst().setName("Changed Existing Dispatch");
        ConfigurationState changed = new ConfigurationDatabaseStore(database).loadConfigurationState();
        changed.setAliasListDefinitions(definitions);
        changed.setAliases(aliases);
        new ConfigurationSnapshotDatabaseStore(database).replace(changed);

        assertThrows(IOException.class, () -> service.execute(prepared));
        assertFalse(Files.exists(database.getParent().resolve("backups")));
        List<AliasListDefinition> changedDefinitions = aliasStore.loadAliasListDefinitions();
        assertEquals("Changed Existing Dispatch",
            aliasStore.loadAliases(changedDefinitions).getFirst().getName());
    }

    private static String playlist(String aliasName, int talkgroup, long frequency)
    {
        return """
            <playlist version="4">
              <alias name="%s" list="County">
                <id type="talkgroup" protocol="DMR" value="%d"/>
              </alias>
              <channel system="County" site="Site" name="Control">
                <alias_list_name>County</alias_list_name>
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="%d"/>
                <aux_decode_configuration/>
                <decode_configuration type="decodeConfigDMR" ignore_data_calls="false"/>
                <event_log_configuration/>
                <record_configuration/>
              </channel>
            </playlist>
            """.formatted(aliasName, talkgroup, frequency);
    }

    private static ConfigurationState existingState()
    {
        ConfigurationState state = new ConfigurationState();
        AliasListDefinition definition =
            new AliasListDefinition("County", AliasListFamily.DMR);
        Alias alias = new Alias("Existing Dispatch");
        alias.setAliasListDefinition(definition);
        alias.setMatchIdentifier(new Talkgroup(Protocol.DMR, 100));
        state.setAliasListDefinitions(List.of(definition));
        state.setAliases(List.of(alias));
        state.setScanListConfiguration(ScanListConfiguration.defaultConfiguration());

        Channel channel = new Channel("Control");
        channel.setSystem("County");
        channel.setSite("Site");
        channel.setAliasListName("County");
        channel.setDecodeConfiguration(new DecodeConfigDMR());
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(451_000_000L);
        channel.setSourceConfiguration(source);
        state.setChannels(List.of(channel));
        return state;
    }
}
