/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.web.settings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/** Code-owned country catalog for tuner-spectrum frequency scopes and optional snap rules. */
public final class SpectrumSnapPresetCatalog
{
    private static final Country UNITED_STATES = new Country("US", "United States", List.of(
        raster("am-broadcast", "AM broadcast", 540_000, 1_700_000, 540_000, 10_000),
        display("amateur-160m", "Amateur 160 meter", 1_800_000, 2_000_000),
        display("amateur-80m", "Amateur 80 meter", 3_500_000, 4_000_000),
        display("amateur-40m", "Amateur 40 meter", 7_000_000, 7_300_000),
        display("amateur-30m", "Amateur 30 meter", 10_100_000, 10_150_000),
        display("amateur-20m", "Amateur 20 meter", 14_000_000, 14_350_000),
        display("amateur-17m", "Amateur 17 meter", 18_068_000, 18_168_000),
        display("amateur-15m", "Amateur 15 meter", 21_000_000, 21_450_000),
        display("amateur-12m", "Amateur 12 meter", 24_890_000, 24_990_000),
        channels("cb", "Citizens Band (CB)", 5_000,
            26_965_000, 26_975_000, 26_985_000, 27_005_000, 27_015_000, 27_025_000, 27_035_000,
            27_055_000, 27_065_000, 27_075_000, 27_085_000, 27_105_000, 27_115_000, 27_125_000,
            27_135_000, 27_155_000, 27_165_000, 27_175_000, 27_185_000, 27_205_000, 27_215_000,
            27_225_000, 27_255_000, 27_235_000, 27_245_000, 27_265_000, 27_275_000, 27_285_000,
            27_295_000, 27_305_000, 27_315_000, 27_325_000, 27_335_000, 27_345_000, 27_355_000,
            27_365_000, 27_375_000, 27_385_000, 27_395_000, 27_405_000),
        display("amateur-10m", "Amateur 10 meter", 28_000_000, 29_700_000),
        display("amateur-6m", "Amateur 6 meter", 50_000_000, 54_000_000),
        raster("fm-broadcast", "FM broadcast", 88_100_000, 107_900_000, 88_100_000, 200_000),
        raster("civil-air", "Civil air", 118_000_000, 136_975_000, 118_000_000, 25_000),
        display("amateur-2m", "Amateur 2 meter", 144_000_000, 148_000_000),
        raster("vhf-land-mobile", "VHF land mobile", 150_000_000, 162_000_000,
            150_000_000, 7_500),
        channels("murs", "Multi-Use Radio Service (MURS)", 15_000,
            151_820_000, 151_880_000, 151_940_000, 154_570_000, 154_600_000),
        channels("vhf-interoperability", "VHF public-safety interoperability", 3_750,
            151_137_500, 154_452_500, 155_752_500, 158_737_500, 159_472_500),
        channels("marine-vhf", "Marine VHF", 12_500,
            combinedRanges(156_025_000, 157_425_000, 25_000, 160_625_000, 162_025_000, 25_000)),
        raster("railroad", "Railroad", 159_810_000, 161_565_000, 159_810_000, 7_500),
        raster("federal-vhf", "Federal VHF land mobile", 162_000_000, 174_000_000,
            162_000_000, 12_500),
        raster("noaa-weather", "NOAA Weather Radio", 162_400_000, 162_550_000,
            162_400_000, 25_000),
        display("amateur-1-25m-lower", "Amateur 1.25 meter", 219_000_000, 220_000_000),
        raster("220-land-mobile", "220 MHz land mobile", 220_002_500, 221_997_500,
            220_002_500, 5_000),
        display("amateur-1-25m-upper", "Amateur 1.25 meter", 222_000_000, 225_000_000),
        raster("military-air", "Military air", 225_000_000, 399_975_000, 225_000_000, 25_000),
        raster("federal-uhf", "Federal UHF land mobile", 406_112_500, 419_987_500,
            406_112_500, 12_500),
        display("amateur-70cm", "Amateur 70 centimeter", 420_000_000, 450_000_000),
        raster("uhf-land-mobile-421", "UHF land mobile", 421_000_000, 429_987_500,
            421_000_000, 12_500),
        channels("frs-gmrs", "FRS / GMRS", 6_250, frsGmrsChannels()),
        raster("uhf-land-mobile-450", "UHF land mobile", 450_000_000, 511_987_500,
            450_000_000, 12_500),
        raster("700-base", "700 MHz base", 769_006_250, 774_993_750,
            769_006_250, 6_250),
        raster("700-mobile", "700 MHz mobile", 799_006_250, 804_993_750,
            799_006_250, 6_250),
        raster("800-mobile", "800 MHz mobile", 806_006_250, 823_993_750,
            806_006_250, 6_250),
        raster("800-base", "800 MHz base", 851_006_250, 868_993_750,
            851_006_250, 6_250),
        display("ism-900", "900 MHz ISM", 902_000_000, 928_000_000),
        display("amateur-33cm", "Amateur 33 centimeter", 902_000_000, 928_000_000),
        raster("900-mobile", "900 MHz mobile", 896_012_500, 900_987_500,
            896_012_500, 12_500),
        raster("900-base", "900 MHz base", 935_012_500, 939_987_500,
            935_012_500, 12_500),
        display("amateur-23cm", "Amateur 23 centimeter", 1_240_000_000, 1_300_000_000)
    ));
    private static final List<Country> COUNTRIES = List.of(UNITED_STATES);

    private SpectrumSnapPresetCatalog()
    {
    }

    public static List<CountrySummary> countries()
    {
        return COUNTRIES.stream().map(country -> new CountrySummary(country.code(), country.label())).toList();
    }

    public static Optional<Country> country(String code)
    {
        if(code == null)
        {
            return Optional.empty();
        }

        String normalized = code.strip().toUpperCase(Locale.ROOT);
        return COUNTRIES.stream().filter(country -> country.code().equals(normalized)).findFirst();
    }

    public static Country requireCountry(String code)
    {
        return country(code).orElseThrow(() -> new IllegalArgumentException("Unsupported spectrum-snap country"));
    }

    private static Scope display(String id, String label, long minHz, long maxHz)
    {
        return new Scope(id, label, minHz, maxHz, null);
    }

    private static Scope raster(String id, String label, long minHz, long maxHz, long originHz, long stepHz)
    {
        return new Scope(id, label, minHz, maxHz, new Snap("RASTER", originHz, stepHz, 0, List.of()));
    }

    private static Scope channels(String id, String label, long toleranceHz, long... frequenciesHz)
    {
        LinkedHashSet<Long> unique = new LinkedHashSet<>();
        for(long frequency: frequenciesHz)
        {
            unique.add(frequency);
        }
        List<Long> frequencies = unique.stream().sorted().toList();
        return new Scope(id, label, frequencies.getFirst(), frequencies.getLast(),
            new Snap("CHANNELS", 0, 0, toleranceHz, frequencies));
    }

    private static long[] combinedRanges(long firstStart, long firstEnd, long firstStep,
                                         long secondStart, long secondEnd, long secondStep)
    {
        List<Long> values = new ArrayList<>();
        appendRange(values, firstStart, firstEnd, firstStep);
        appendRange(values, secondStart, secondEnd, secondStep);
        return values.stream().mapToLong(Long::longValue).toArray();
    }

    private static long[] frsGmrsChannels()
    {
        List<Long> values = new ArrayList<>();
        appendRange(values, 462_562_500, 462_712_500, 25_000);
        appendRange(values, 467_562_500, 467_712_500, 25_000);
        appendRange(values, 462_550_000, 462_725_000, 25_000);
        appendRange(values, 467_550_000, 467_725_000, 25_000);
        return values.stream().mapToLong(Long::longValue).toArray();
    }

    private static void appendRange(List<Long> values, long start, long end, long step)
    {
        for(long frequency = start; frequency <= end; frequency = Math.addExact(frequency, step))
        {
            values.add(frequency);
        }
    }

    public record CountrySummary(String code, String label)
    {
    }

    public record Country(String code, String label, List<Scope> scopes)
    {
        public Country
        {
            Objects.requireNonNull(code);
            Objects.requireNonNull(label);
            scopes = List.copyOf(scopes);
        }
    }

    public record Scope(String id, String label, long minHz, long maxHz, Snap snap)
    {
        public Scope
        {
            if(id == null || id.isBlank() || label == null || label.isBlank() || minHz <= 0 || maxHz < minHz)
            {
                throw new IllegalArgumentException("Invalid spectrum frequency scope");
            }
        }
    }

    public record Snap(String kind, long originHz, long stepHz, long matchToleranceHz, List<Long> frequenciesHz)
    {
        public Snap
        {
            frequenciesHz = List.copyOf(frequenciesHz);
            if(!("RASTER".equals(kind) || "CHANNELS".equals(kind)) ||
                "RASTER".equals(kind) && (originHz <= 0 || stepHz <= 0 || matchToleranceHz != 0 ||
                    !frequenciesHz.isEmpty()) ||
                "CHANNELS".equals(kind) && (originHz != 0 || stepHz != 0 || matchToleranceHz <= 0 ||
                    frequenciesHz.isEmpty()))
            {
                throw new IllegalArgumentException("Invalid spectrum frequency snap rule");
            }
        }
    }
}
