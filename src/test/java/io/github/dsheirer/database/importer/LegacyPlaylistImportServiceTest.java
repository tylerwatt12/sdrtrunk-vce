/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */

package io.github.dsheirer.database.importer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.configuration.ConfigurationState;
import io.github.dsheirer.controller.channel.Channel;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.alias.AliasDatabaseStore;
import io.github.dsheirer.database.configuration.ConfigurationDatabaseStore;
import io.github.dsheirer.database.importer.LegacyPlaylistImportService.ImportResult;
import io.github.dsheirer.database.importer.LegacyPlaylistImportService.PreparedImport;
import io.github.dsheirer.database.importer.LegacyXmlConfigurationMerger.ConflictPolicy;
import io.github.dsheirer.module.decode.dmr.DecodeConfigDMR;
import io.github.dsheirer.source.config.SourceConfigTuner;
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
    void importsIntoExistingDatabaseAndCreatesBackup() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        new AliasDatabaseStore(database).replaceAliases(List.of());
        ConfigurationState existing = new ConfigurationState();
        Channel existingChannel = new Channel("Existing");
        existingChannel.setSystem("Local");
        existingChannel.setSite("Site");
        SourceConfigTuner source = new SourceConfigTuner();
        source.setFrequency(155_000_000L);
        existingChannel.setSourceConfiguration(source);
        existingChannel.setDecodeConfiguration(new DecodeConfigDMR());
        existing.setChannels(List.of(existingChannel));
        new ConfigurationDatabaseStore(database).replaceConfigurationState(existing);

        Path xml = mTemporaryFolder.resolve("second-playlist.xml");
        Files.writeString(xml, """
            <playlist version="4">
              <channel system="Imported" site="Site" name="Control">
                <source_configuration type="sourceConfigTuner" source_type="TUNER" frequency="452000000"/>
                <aux_decode_configuration/>
                <decode_configuration type="decodeConfigDMR">
                  <timeslot lsn="1" downlink="452012500" uplink="457012500"/>
                  <timeslot lsn="2" downlink="452025000" uplink="457025000"/>
                </decode_configuration>
                <event_log_configuration/>
                <record_configuration/>
              </channel>
            </playlist>
            """);

        LegacyPlaylistImportService service = new LegacyPlaylistImportService(database);
        PreparedImport prepared = service.prepare(xml);
        assertEquals(1, prepared.preview().channelCount());
        assertEquals(0, prepared.preview().totalConflicts());

        ImportResult result = service.execute(prepared, ConflictPolicy.SKIP);
        assertTrue(Files.isRegularFile(result.backupPath()));
        assertEquals(1, result.summary().added());
        ConfigurationState loaded = new ConfigurationDatabaseStore(database).loadConfigurationState();
        assertEquals(2, loaded.getChannels().size());
        DecodeConfigDMR dmr = assertInstanceOf(DecodeConfigDMR.class,
            loaded.getChannels().get(1).getDecodeConfiguration());
        assertEquals(2, dmr.getTimeslotMap().size());
        assertEquals(452_025_000L, dmr.getTimeslotMap().get(1).getDownlinkFrequency());
    }
}
