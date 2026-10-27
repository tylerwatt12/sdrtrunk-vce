/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.channel;

import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.source.tuner.manager.TunerManager;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertEquals;

class ChannelOptionsNicknameTest
{
    @TempDir Path directory;

    @Test
    void renamingAndClearingOnlyChangeChoiceLabelsAndFailuresPreserveCanonicalChoices() throws Exception
    {
        DirectoryPreference paths = new DirectoryPreference(ignored -> {})
        {
            @Override public Path getDirectoryApplicationRoot() { return directory; }
        };
        UserPreferences preferences = new UserPreferences()
        {
            @Override public DirectoryPreference getDirectoryPreference() { return paths; }
        };
        SdrTrunkTestDatabase.create(SdrTrunkDatabasePath.getDatabasePath(preferences));
        List<String> values = List.of("Airspy canonical identifier", "RTL canonical identifier");
        TunerManager tuners = new TunerManager(preferences)
        {
            @Override public List<String> getPreferredTunerNames() { return values; }
        };
        ConfigurationManager manager = new ConfigurationManager(preferences, tuners, new AliasModel(), null);
        try
        {
            manager.init();
            ChannelAdministrationService service = manager.getChannelAdministrationService();
            AtomicReference<Map<String,String>> labels = new AtomicReference<>(Map.of(values.getFirst(), "Roof"));
            service.setTunerDisplayNames(labels::get);
            long revision = service.options().revision();
            assertEquals(values, service.options().tuners());
            assertEquals(List.of(new ChannelAdministrationService.TunerOption(values.getFirst(), "Roof"),
                new ChannelAdministrationService.TunerOption(values.getLast(), values.getLast())),
                service.options().tunerOptions());
            var payload = io.github.dsheirer.web.http.ApiHttpResponse.normalizePayload(service.options());
            assertEquals(values.getFirst(), payload.at("/tuner_options/0/value").asText());
            assertEquals("Roof", payload.at("/tuner_options/0/label").asText());
            labels.set(Map.of(values.getFirst(), "North antenna"));
            assertEquals("North antenna", service.options().tunerOptions().getFirst().label());
            assertEquals(values, service.options().tunerOptions().stream().map(ChannelAdministrationService.TunerOption::value).toList());
            labels.set(Map.of());
            assertEquals(values, service.options().tunerOptions().stream().map(ChannelAdministrationService.TunerOption::label).toList());
            service.setTunerDisplayNames(() -> { throw new IllegalStateException("Label projection unavailable"); });
            assertEquals(values, service.options().tuners());
            assertEquals(values.getFirst(), service.options().tunerOptions().getFirst().label());
            assertEquals(revision, service.options().revision(), "a display rename cannot change saved channel revisions");
        }
        finally
        {
            manager.quiesceAdministration();
            manager.getChannelProcessingManager().close();
            manager.getBroadcastModel().close();
            manager.close();
            manager.getRadioReferenceDirectoryService().close();
            tuners.stop();
        }
    }
}
