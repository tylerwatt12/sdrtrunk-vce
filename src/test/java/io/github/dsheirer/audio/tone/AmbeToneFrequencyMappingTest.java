/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.tone;

import io.github.dsheirer.identifier.tone.AmbeTone;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AmbeToneFrequencyMappingTest
{
    @Test
    void realMeasurementsUseNearestNominalIdentity()
    {
        assertMatch(343_183, 343_750, "HZ_343_75");
        assertMatch(387_660, 375_000, "HZ_375_00");
        assertMatch(435_251, 437_500, "HZ_437_50");
        assertMatch(566_379, 562_500, "HZ_562_50");
        assertMatch(598_065, 593_750, "HZ_593_75");
        assertMatch(914_305, 906_250, "HZ_906_25");
        assertEquals(AmbeToneFrequencyMapping.nearest(566_379), AmbeToneFrequencyMapping.nearest(560_000));
        assertNotEquals(AmbeToneFrequencyMapping.nearest(566_379), AmbeToneFrequencyMapping.nearest(598_065));
    }

    @Test
    void everyNominalHasExactIdentityRegardlessOfEnumOrder()
    {
        List<AmbeTone> singles = singles().stream().sorted(Comparator.comparingInt(
            AmbeToneFrequencyMappingTest::milliHertz)).toList();
        assertEquals(118, singles.size());
        for(int index = 0; index < singles.size(); index++)
        {
            AmbeTone tone = singles.get(index);
            int frequency = milliHertz(tone);
            assertEquals((index + 5) * 31_250, frequency);
            var nearest = AmbeToneFrequencyMapping.nearest(frequency).orElseThrow();
            assertEquals(frequency, nearest.frequencyMilliHertz());
            assertEquals(tone.name(), nearest.code());
            assertEquals(frequency / 1000., nearest.frequencyHz());
        }
        assertMatch(2_687_500, 2_687_500, "HZ_2687_50");
        assertMatch(2_718_750, 2_718_750, "HZ_2718_75");
    }

    @Test
    void everyMidpointChoosesLowerAndOneMilliHertzAboveChoosesUpper()
    {
        List<AmbeTone> sorted = singles().stream().sorted(Comparator.comparingInt(
            AmbeToneFrequencyMappingTest::milliHertz)).toList();
        for(int index = 1; index < sorted.size(); index++)
        {
            AmbeTone lower = sorted.get(index - 1), upper = sorted.get(index);
            int midpoint = (milliHertz(lower) + milliHertz(upper)) / 2;
            assertMatch(midpoint, milliHertz(lower), lower.name());
            assertMatch(midpoint + 1, milliHertz(upper), upper.name());
        }
    }

    @Test
    void boundariesAreInclusiveAndOutsideValuesAreNotClamped()
    {
        assertMatch(156_250, 156_250, "HZ_156_25");
        assertMatch(3_812_500, 3_812_500, "HZ_3812_50");
        for(int value: new int[]{Integer.MIN_VALUE, -1, 0, 156_249, 3_812_501, Integer.MAX_VALUE})
            assertTrue(AmbeToneFrequencyMapping.nearest(value).isEmpty());
    }

    private static List<AmbeTone> singles()
    {
        return Arrays.stream(AmbeTone.values()).filter(tone -> tone.name().startsWith("HZ_")).toList();
    }

    private static int milliHertz(AmbeTone tone)
    {
        return new BigDecimal(tone.getValue()).movePointRight(3).intValueExact();
    }

    private static void assertMatch(int measured, int expected, String code)
    {
        var match = AmbeToneFrequencyMapping.nearest(measured).orElseThrow();
        assertEquals(expected, match.frequencyMilliHertz());
        assertEquals(code, match.code());
    }
}
