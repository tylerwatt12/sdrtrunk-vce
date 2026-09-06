package io.github.dsheirer.web.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class SpectrumSnapPresetCatalogTest
{
    @Test
    void exposesBoundedUnitedStatesCatalogWithExactPersonalRadioChannels()
    {
        SpectrumSnapPresetCatalog.Country country = SpectrumSnapPresetCatalog.requireCountry("us");
        assertEquals("US", country.code());
        assertEquals("United States", country.label());
        assertEquals(41, country.scopes().size());

        SpectrumSnapPresetCatalog.Scope cb = scope(country, "cb");
        assertEquals("CHANNELS", cb.snap().kind());
        assertEquals(40, cb.snap().frequenciesHz().size());
        assertTrue(cb.snap().frequenciesHz().contains(27_255_000L));

        SpectrumSnapPresetCatalog.Scope frsGmrs = scope(country, "frs-gmrs");
        assertEquals(30, frsGmrs.snap().frequenciesHz().size());
        assertTrue(frsGmrs.snap().frequenciesHz().contains(462_562_500L));
        assertTrue(frsGmrs.snap().frequenciesHz().contains(467_725_000L));

        SpectrumSnapPresetCatalog.Scope mursLower = scope(country, "murs-lower");
        SpectrumSnapPresetCatalog.Scope mursUpper = scope(country, "murs-upper");
        assertEquals(List.of(151_820_000L, 151_880_000L, 151_940_000L), mursLower.snap().frequenciesHz());
        assertEquals(List.of(154_570_000L, 154_600_000L), mursUpper.snap().frequenciesHz());
        assertEquals(151_820_000L, mursLower.minHz());
        assertEquals(151_940_000L, mursLower.maxHz());
        assertEquals(154_570_000L, mursUpper.minHz());
        assertEquals(154_600_000L, mursUpper.maxHz());
        assertEquals(15_000L, mursLower.snap().matchToleranceHz());
        assertEquals(15_000L, mursUpper.snap().matchToleranceHz());

        SpectrumSnapPresetCatalog.Scope noaa = scope(country, "noaa-weather");
        assertEquals(25_000, noaa.snap().stepHz());
        assertEquals(162_400_000, noaa.snap().originHz());
        assertEquals(162_550_000, noaa.maxHz());
    }

    @Test
    void separatesDisplayOnlyScopesFromCoarseServiceSnapRules()
    {
        SpectrumSnapPresetCatalog.Country country = SpectrumSnapPresetCatalog.requireCountry("US");
        assertEquals(7_500, scope(country, "vhf-land-mobile").snap().stepHz());
        assertEquals(12_500, scope(country, "federal-vhf").snap().stepHz());
        assertEquals(25_000, scope(country, "civil-air").snap().stepHz());
        assertNull(scope(country, "amateur-2m").snap());
        assertNull(scope(country, "amateur-70cm").snap());
        assertNull(scope(country, "ism-900").snap());
        assertEquals(902_000_000, scope(country, "ism-900").minHz());
        assertEquals(928_000_000, scope(country, "ism-900").maxHz());
        assertEquals("700 MHz public safety (base)", scope(country, "700-base").label());
        assertEquals("700 MHz public safety (mobile)", scope(country, "700-mobile").label());
        assertEquals("800 MHz public safety (base)", scope(country, "800-base").label());
        assertEquals("800 MHz public safety (mobile)", scope(country, "800-mobile").label());
    }

    @Test
    void refusesUnknownCountries()
    {
        assertThrows(IllegalArgumentException.class, () -> SpectrumSnapPresetCatalog.requireCountry("CA"));
        assertThrows(IllegalArgumentException.class, () -> SpectrumSnapPresetCatalog.requireCountry(""));
    }

    private static SpectrumSnapPresetCatalog.Scope scope(SpectrumSnapPresetCatalog.Country country, String id)
    {
        Predicate<SpectrumSnapPresetCatalog.Scope> matches = scope -> id.equals(scope.id());
        return country.scopes().stream().filter(matches).findFirst().orElseThrow();
    }
}
