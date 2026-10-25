/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.stats;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.alias.AliasConfigurationSnapshot;
import io.github.dsheirer.alias.AliasModel;
import io.github.dsheirer.channel.ChannelAdministrationService;
import io.github.dsheirer.channel.ChannelAdministrationServiceTestSupport;
import io.github.dsheirer.configuration.ConfigurationManager;
import io.github.dsheirer.database.SdrTrunkDatabasePath;
import io.github.dsheirer.database.SdrTrunkTestDatabase;
import io.github.dsheirer.database.configuration.ConfigurationRepository;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.directory.DirectoryPreference;
import io.github.dsheirer.source.tuner.Tuner;
import io.github.dsheirer.source.tuner.TunerClass;
import io.github.dsheirer.source.tuner.manager.DiscoveredTuner;
import io.github.dsheirer.source.tuner.manager.TunerSettingsService;
import io.github.dsheirer.source.tuner.test.TestTunerController;
import io.github.dsheirer.web.tuner.TunerAdministrationService;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/** A database commit followed by failed model publication must finish the temporary RF check. */
@Timeout(10)
class SpectrumDiscoveryPublicationCleanupTest
{
    @TempDir Path root;

    @Test
    void publicationFailureReleasesRfHoldAndDetachesWizardWithoutLosingCommittedChannel() throws Exception
    {
        Path database = SdrTrunkDatabasePath.getDatabasePath(root);
        SdrTrunkTestDatabase.create(database);
        UserPreferences preferences = new UserPreferences()
        {
            final DirectoryPreference directory = new DirectoryPreference(type -> {})
            { @Override public Path getDirectoryApplicationRoot() { return root; } };
            @Override public DirectoryPreference getDirectoryPreference() { return directory; }
        };
        AtomicInteger publications = new AtomicInteger();
        ConfigurationManager manager = new ConfigurationManager(preferences, null, new AliasModel(), null)
        {
            @Override protected void publishCommittedAliasConfiguration(AliasConfigurationSnapshot committed,
                AliasConfigurationPublication publication)
            {
                publications.incrementAndGet();
                throw new IllegalStateException("Injected publication failure");
            }
        };
        manager.init();
        FakeTuner tuner = new FakeTuner();
        var settings = settings(tuner);
        var diagnostics = new TunerDiagnosticService(List::of, (target, viewport, profile, consumer) ->
        { throw new AssertionError("An AM readiness check does not attach an FFT processor"); });
        var channels = ChannelAdministrationServiceTestSupport.create(manager);
        var discovery = new SpectrumDiscoveryService(channels, inventory(tuner), diagnostics, settings,
            new StatsWebDatabase(preferences, database));
        try
        {
            var ready = discovery.open(TunerAdministrationService.opaqueId(tuner),
                tuner.getTuner().getTunerController().getFrequency() + 100_000, "am", null);
            assertEquals("ready", ready.state());
            Object wizard = field(discovery, "mWizard");
            var hold = (TunerSettingsService.ProbeHold)field(wizard, "hold");
            assertTrue(hold.valid());
            var template = ready.review().template();
            var request = new SpectrumDiscoveryService.SaveRequest("Weather", "Local", "Weather AM", 0,
                "Weather AM", template.settings(), ready.review().revision());

            var stale = new SpectrumDiscoveryService.SaveRequest("Weather", "Local", "Weather AM", 0,
                "Weather AM", template.settings(), ready.review().revision() - 1);
            assertThrows(ChannelAdministrationService.StaleRevisionException.class,
                () -> discovery.save(ready.sessionId(), stale));
            assertSame(wizard, field(discovery, "mWizard"), "An uncommitted draft remains retryable");
            assertTrue(hold.valid(), "A stale revision must not discard the RF check");
            assertTrue(new ConfigurationRepository(database).load().channels().isEmpty());

            var failure = assertThrows(ConfigurationManager.ConfigurationPublicationException.class,
                () -> discovery.save(ready.sessionId(), request));
            int failedPublicationAttempts = publications.get();
            assertTrue(failedPublicationAttempts > 0);
            assertNotNull(failure.committedConfigurationId(), "The exception must retain the actual committed ID");
            assertNull(field(discovery, "mWizard"));
            assertFalse(hold.valid(), "Publication failure must not retain the RF reservation");
            try(var nextHold = settings.holdForProbe(tuner)) { assertTrue(nextHold.valid()); }
            var committed = new ConfigurationRepository(database).load();
            assertEquals(1, committed.channels().size());
            assertEquals(failure.committedConfigurationId(), committed.channels().getFirst().getConfigurationId());
            assertEquals(failure.committedAliasListId(), committed.channels().getFirst().getAliasListId());
            assertTrue(manager.getChannelModel().getChannels().isEmpty(), "Publication was deliberately rejected");

            assertThrows(IllegalStateException.class, () -> discovery.save(ready.sessionId(), request));
            assertThrows(ConfigurationManager.ConfigurationPublicationException.class,
                () -> channels.createDiscovered(template, null, "Another AM list", ready.review().revision()));
            assertEquals(failedPublicationAttempts, publications.get(),
                "A user retry must not publish or commit a second configuration");
            var afterRetry = new ConfigurationRepository(database).load();
            assertEquals(committed.channels().size(), afterRetry.channels().size());
            assertEquals(committed.aliasListDefinitions().size(), afterRetry.aliasListDefinitions().size());
        }
        finally
        {
            discovery.close();
            settings.close();
            diagnostics.close();
            manager.getChannelProcessingManager().close();
            manager.close();
        }
    }

    private static TunerSettingsService settings(FakeTuner tuner) throws Exception
    {
        Constructor<TunerSettingsService> constructor = TunerSettingsService.class.getDeclaredConstructor(
            Runnable.class, Predicate.class, Function.class,
            io.github.dsheirer.controller.channel.ChannelProcessingManager.class, Supplier.class);
        constructor.setAccessible(true);
        return constructor.newInstance((Runnable)() -> {}, (Predicate<DiscoveredTuner>)candidate -> candidate == tuner,
            (Function<String,DiscoveredTuner>)id -> tuner, null,
            (Supplier<List<DiscoveredTuner>>)() -> List.of(tuner));
    }

    private static TunerAdministrationService inventory(FakeTuner tuner) throws Exception
    {
        Constructor<TunerAdministrationService> constructor = TunerAdministrationService.class.getDeclaredConstructor(
            Supplier.class, Function.class);
        constructor.setAccessible(true);
        return constructor.newInstance((Supplier<List<DiscoveredTuner>>)() -> List.of(tuner),
            (Function<Tuner,String>)ignored -> null);
    }

    private static Object field(Object object, String name) throws Exception
    {
        Field field = object.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static final class FakeTuner extends DiscoveredTuner
    {
        FakeTuner()
        {
            mTuner = new Tuner(new TestTunerController(), this)
            {
                @Override public int getMaximumUSBBitsPerSecond() { return 0; }
                @Override public String getUniqueID() { return "publication-cleanup"; }
                @Override public String getPreferredName() { return getUniqueID(); }
                @Override public TunerClass getTunerClass() { return TunerClass.TEST_TUNER; }
                @Override public double getSampleSize() { return 16; }
            };
        }
        @Override public TunerClass getTunerClass() { return TunerClass.TEST_TUNER; }
        @Override public String getId() { return "publication-cleanup"; }
        @Override public void start() { }
    }
}
