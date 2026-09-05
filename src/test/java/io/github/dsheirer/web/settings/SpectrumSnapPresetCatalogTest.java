package io.github.dsheirer.web.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(40, country.scopes().size());

        SpectrumSnapPresetCatalog.Scope cb = scope(country, "cb");
        assertEquals("CHANNELS", cb.snap().kind());
        assertEquals(40, cb.snap().frequenciesHz().size());
        assertTrue(cb.snap().frequenciesHz().contains(27_255_000L));

        SpectrumSnapPresetCatalog.Scope frsGmrs = scope(country, "frs-gmrs");
        assertEquals(30, frsGmrs.snap().frequenciesHz().size());
        assertTrue(frsGmrs.snap().frequenciesHz().contains(462_562_500L));
        assertTrue(frsGmrs.snap().frequenciesHz().contains(467_725_000L));

        SpectrumSnapPresetCatalog.Scope murs = scope(country, "murs");
        assertEquals(5, murs.snap().frequenciesHz().size());
        assertTrue(murs.snap().frequenciesHz().contains(151_820_000L));
        assertTrue(murs.snap().frequenciesHz().contains(154_600_000L));

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
