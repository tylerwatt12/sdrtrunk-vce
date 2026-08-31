/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.module.decode.p25.bandplan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.database.settings.ApplicationSettingsStore;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.phase1.message.IFrequencyBand;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class P25BandplanOverrideRegistryTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void usesExactSiteProfileBeforeSystemProfile()
    {
        P25BandplanOverrideProfile system = profile(0xBEE00, 0x49F, null, null, 851_006_250L);
        P25BandplanOverrideProfile site = profile(0xBEE00, 0x49F, 1, 2, 762_006_250L);
        P25BandplanOverrideRegistry registry = P25BandplanOverrideRegistry.of(List.of(system, site));

        assertEquals(site, registry.find(new P25SiteIdentity(0xBEE00, 0x49F, 1, 2)).orElseThrow());
        assertEquals(system, registry.find(new P25SiteIdentity(0xBEE00, 0x49F, 1, 3)).orElseThrow());
        assertTrue(registry.find(new P25SiteIdentity(0xBEE00, 0x348, 1, 2)).isEmpty());
        assertEquals(762_006_250L,
            registry.getFrequencyBands(new P25SiteIdentity(0xBEE00, 0x49F, 1, 2)).get(0).getBaseFrequency());
    }

    @Test
    void savesAndReloadsOneApplicationSettingsDocument() throws Exception
    {
        Path database = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        ApplicationSettingsStore store = new ApplicationSettingsStore(database);
        P25BandplanOverrideProfile profile = profile(0xBEE00, 0x49F, null, null, 851_006_250L);
        P25BandplanOverrideRegistry writer = new P25BandplanOverrideRegistry(store);

        writer.setProfiles(List.of(profile));

        P25BandplanOverrideRegistry reader = new P25BandplanOverrideRegistry(store);
        assertEquals(List.of(profile), reader.getProfiles());
        assertTrue(store.contains(ApplicationSettingsStore.P25_BANDPLAN_OVERRIDES));
    }

    @Test
    void rejectsDuplicateProfilesAndExposesImmutableRuntimeState()
    {
        P25BandplanOverrideProfile profile = profile(0xBEE00, 0x49F, null, null, 851_006_250L);
        assertThrows(IllegalArgumentException.class,
            () -> P25BandplanOverrideRegistry.of(List.of(profile, profile)));

        P25BandplanOverrideRegistry registry = P25BandplanOverrideRegistry.of(List.of(profile));
        assertThrows(UnsupportedOperationException.class, () -> registry.getProfiles().add(profile));
        Map<Integer,IFrequencyBand> frequencyBands =
            registry.getFrequencyBands(new P25SiteIdentity(0xBEE00, 0x49F, 1, 2));
        assertThrows(UnsupportedOperationException.class,
            () -> frequencyBands.put(1, band(1, 762_006_250L).toFrequencyBand()));
    }

    @Test
    void notifiesRegisteredListenersOnlyAfterSuccessfulReplacement() throws Exception
    {
        Path database = mTemporaryFolder.resolve("listener.sqlite");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        P25BandplanOverrideRegistry registry = new P25BandplanOverrideRegistry(
            new ApplicationSettingsStore(database));
        P25BandplanOverrideProfile system = profile(0xBEE00, 0x49F, null, null, 851_006_250L);
        P25BandplanOverrideProfile site = profile(0xBEE00, 0x49F, 1, 2, 762_006_250L);
        AtomicInteger notifications = new AtomicInteger();
        Runnable listener = notifications::incrementAndGet;

        registry.addChangeListener(listener);
        registry.setProfiles(List.of(system));
        assertEquals(1, notifications.get());

        assertThrows(IllegalArgumentException.class,
            () -> registry.setProfiles(List.of(system, system)));
        assertEquals(1, notifications.get(), "a rejected replacement must not notify listeners");

        registry.removeChangeListener(listener);
        registry.setProfiles(List.of(site));
        assertEquals(1, notifications.get(), "a removed listener must not be notified");
    }

    private static P25BandplanOverrideProfile profile(int wacn, int system, Integer rfss, Integer site, long base)
    {
        return new P25BandplanOverrideProfile(wacn, system, rfss, site, List.of(band(0, base)));
    }

    private static P25BandplanOverrideBand band(int identifier, long base)
    {
        return new P25BandplanOverrideBand(identifier, P25BandplanChannelType.FDMA, base, 12_500, 6_250L,
            -45_000_000L);
    }
}
