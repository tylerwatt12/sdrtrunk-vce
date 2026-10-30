/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.web.tuner;

import io.github.dsheirer.database.SdrTrunkDatabaseSchema;
import io.github.dsheirer.database.settings.ReceiverNicknameSchema;
import io.github.dsheirer.database.settings.ReceiverNicknameStore;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.rtl.r8x.r820t.R820TTunerConfiguration;
import io.github.dsheirer.source.tuner.test.TestTuner;
import io.github.dsheirer.stats.SpectrumSearchService;
import io.github.dsheirer.web.http.ApiHttpResponse;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SpectrumSearchReceiverNamesTest
{
    @TempDir Path directory;

    @Test void actualSearchCatalogUsesNicknameChangesWithoutChangingSelectionOrAllocationPreference() throws Exception
    {
        Path database = directory.resolve("nicknames.sqlite");
        try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database))
        {
            SdrTrunkDatabaseSchema.create(connection);
            ReceiverNicknameSchema.initializeMissing(connection);
        }
        DiscoveredTuner receiver = new DiscoveredTuner()
        {
            {
                mTunerConfiguration = new R820TTunerConfiguration("native-receiver");
                mTuner = new TestTuner(this)
                {
                    @Override public String getPreferredName() { return "Canonical hardware receiver"; }
                };
            }
            @Override public TunerClass getTunerClass() { return TunerClass.UNKNOWN; }
            @Override public String getId() { return "native-receiver"; }
            @Override public void start() { }
        };
        TunerAdministrationService tuners = new TunerAdministrationService(() -> List.of(receiver),
            () -> tuner -> null, null, tuner -> List.of(), new ReceiverNicknameStore(database));
        var baseline = catalog(tuners);
        String selected = baseline.tuners().getFirst().id();
        assertEquals("Canonical hardware receiver", baseline.tuners().getFirst().name());
        assertTrue(baseline.tuners().getFirst().eligible());
        assertEquals(receiver.getOperatorGeneration(), baseline.tuners().getFirst().operatorGeneration());
        assertEquals(selected, baseline.suggestedTunerId());

        for(String nickname : List.of("Roof antenna", "Desk antenna", ""))
        {
            tuners.setNickname(receiver, nickname);
            var result = catalog(tuners);
            var item = result.tuners().getFirst();
            assertEquals(nickname.isEmpty() ? "Canonical hardware receiver" : nickname, item.name());
            assertEquals(selected, item.id());
            assertEquals(selected, result.suggestedTunerId());
            assertEquals(baseline.tuners().getFirst().eligible(), item.eligible());
            assertEquals(baseline.tuners().getFirst().minimumFrequencyHz(), item.minimumFrequencyHz());
            assertEquals(baseline.tuners().getFirst().maximumFrequencyHz(), item.maximumFrequencyHz());
            assertEquals("Canonical hardware receiver", receiver.getTuner().getPreferredName());
            assertEquals("native-receiver", receiver.getTunerConfiguration().getUniqueID());
        }
        var serialized = ApiHttpResponse.normalizePayload(tuners.item(receiver));
        assertFalse(serialized.toString().contains("display_name"), "Pure display helper adds no API field");
        long priorGeneration = baseline.tuners().getFirst().operatorGeneration();
        assertTrue(receiver.enterSetup());
        var updated = catalog(tuners);
        assertEquals("setup", updated.tuners().getFirst().operatorState());
        assertTrue(updated.tuners().getFirst().operatorGeneration() > priorGeneration,
            "A refreshed Find Trunked Systems choice must fence the mode shown by an older chooser");
        assertEquals(receiver.getOperatorGeneration(), ApiHttpResponse.normalizePayload(updated)
            .at("/tuners/0/operator_generation").asLong());
    }

    private static SpectrumSearchService.Catalog catalog(TunerAdministrationService tuners) throws Exception
    {
        var catalog = SpectrumSearchService.class.getDeclaredMethod("catalog", TunerAdministrationService.class);
        catalog.setAccessible(true);
        return (SpectrumSearchService.Catalog)catalog.invoke(null, tuners);
    }
}
