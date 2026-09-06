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
        assertEquals(47, country.scopes().size());

        SpectrumSnapPresetCatalog.Scope cb = scope(country, "cb");
        assertEquals("CHANNELS", cb.snap().kind());
        assertEquals(40, cb.snap().frequenciesHz().size());
        assertTrue(cb.snap().frequenciesHz().contains(27_255_000L));

        for(long base: List.of(462_550_000L, 467_550_000L))
        {
            SpectrumSnapPresetCatalog.Scope frsGmrs = scope(country, "frs-gmrs-" + base / 1_000_000);
            assertEquals(15, frsGmrs.snap().frequenciesHz().size());
            assertEquals(base, frsGmrs.minHz());
            assertEquals(base + 175_000, frsGmrs.maxHz());
            assertEquals(6_250, frsGmrs.snap().matchToleranceHz());
            for(long frequency = base; frequency <= base + 175_000; frequency += 12_500)
            {
                assertTrue(frsGmrs.snap().frequenciesHz().contains(frequency));
            }
        }

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
    void separatesMarineGroupsAndIndividualInteroperabilityChannels()
    {
        SpectrumSnapPresetCatalog.Country country = SpectrumSnapPresetCatalog.requireCountry("US");
        for(String group: List.of("lower", "upper"))
        {
            long start = group.equals("lower") ? 156_025_000L : 160_625_000L;
            SpectrumSnapPresetCatalog.Scope marine = scope(country, "marine-vhf-" + group);
            assertEquals(start, marine.minHz());
            assertEquals(start + 1_400_000, marine.maxHz());
            assertEquals(57, marine.snap().frequenciesHz().size());
            assertEquals(12_500, marine.snap().matchToleranceHz());
            for(long frequency = start; frequency <= start + 1_400_000; frequency += 25_000)
            {
                assertTrue(marine.snap().frequenciesHz().contains(frequency));
            }
        }
        for(long frequency: List.of(151_137_500L, 154_452_500L, 155_752_500L, 158_737_500L, 159_472_500L))
        {
            SpectrumSnapPresetCatalog.Scope channel = scope(country, "vhf-interoperability-" + frequency / 1_000_000);
            assertEquals(List.of(frequency), channel.snap().frequenciesHz());
            assertEquals(frequency - 3_750, channel.minHz());
            assertEquals(frequency + 3_750, channel.maxHz());
            assertEquals(3_750, channel.snap().matchToleranceHz());
        }
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
