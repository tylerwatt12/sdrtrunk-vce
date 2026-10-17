/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.tone;

import io.github.dsheirer.identifier.tone.AmbeTone;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Analog single-tone nominal frequencies: existing AMBE values plus Motorola Quick Call II and Plectron values.
 * A frequency match does not identify a paging format, sequence, or capcode. Encoded AMBE identities are unchanged.
 */
public final class PagingToneFrequencyMapping
{
    public record Match(int frequencyMilliHertz, String code)
    {
        public double frequencyHz() { return frequencyMilliHertz / 1000.; }
    }

    //Midian Electronics Tone Signaling Charts, pages 1 (QCII groups 1-6,10,11) and 4 (Plectron).
    //https://www.sigidwiki.com/images/f/f3/Tone-signaling-charts.pdf
    private static final double[] QUICK_CALL_II_HZ = {
        330.5,349.0,368.5,389.0,410.8,433.7,457.9,483.5,510.5,539.0,
        569.1,600.9,634.5,669.9,707.3,746.8,788.5,832.5,879.0,928.1,
        1092.4,288.5,296.5,304.7,313.0,953.7,979.9,1006.9,1034.7,1063.2,
        321.7,339.6,358.6,378.6,399.8,422.1,445.7,470.5,496.8,524.6,
        553.9,584.8,617.4,651.9,688.3,726.8,767.4,810.2,855.5,903.2,
        1122.5,1153.4,1185.2,1217.8,1251.4,1285.8,1321.2,1357.6,1395.0,1433.4,
        1472.9,1513.5,1555.2,1598.0,1642.0,1687.2,1733.7,1781.5,1830.5,1881.0,
        1930.2,1989.0,2043.8,2094.5,2155.6,2212.2,2271.7,2334.6,2401.0,2468.2
    };
    private static final double[] PLECTRON_HZ = {
        282.2,294.7,307.8,321.4,335.6,350.5,366.0,382.3,399.2,416.9,
        435.3,454.6,474.8,495.8,517.8,540.7,564.7,589.7,615.8,643.0,
        672.0,701.0,732.0,765.0,799.0,834.0,871.0,910.0,950.0,992.0,
        1036.0,1082.0,1130.0,1180.0,1232.0,1287.0,1344.0,1403.0,1465.0,1530.0,
        1598.0,1669.0,1743.0,1820.0,1901.0,1985.0,2073.0,2164.0,2260.0,2361.0,
        2465.0,2575.0,2688.0,2807.0,2932.0,3062.0,3197.0,3339.0,3487.0
    };
    private static final List<Match> NOMINALS = createNominals();

    private PagingToneFrequencyMapping() {}

    /** Sorted immutable union; shared frequencies occur once and retain their existing AMBE code when applicable. */
    public static List<Match> nominals() { return NOMINALS; }

    /** Nearest predefined nominal; exact ties select the lower frequency and out-of-range values are unavailable. */
    public static Optional<Match> nearest(int frequencyMilliHertz)
    {
        if(frequencyMilliHertz < NOMINALS.getFirst().frequencyMilliHertz() ||
            frequencyMilliHertz > NOMINALS.getLast().frequencyMilliHertz()) return Optional.empty();
        Match nearest = NOMINALS.getFirst();
        int distance = Math.abs(frequencyMilliHertz - nearest.frequencyMilliHertz());
        for(Match candidate: NOMINALS)
        {
            int candidateDistance = Math.abs(frequencyMilliHertz - candidate.frequencyMilliHertz());
            if(candidateDistance < distance)
            {
                nearest = candidate;
                distance = candidateDistance;
            }
        }
        return Optional.of(nearest);
    }

    private static List<Match> createNominals()
    {
        TreeMap<Integer,Match> values = new TreeMap<>();
        for(AmbeTone tone: AmbeTone.values())
        {
            if(tone.name().startsWith("HZ_"))
            {
                int frequency = new BigDecimal(tone.getValue()).movePointRight(3).intValueExact();
                values.put(frequency,new Match(frequency,tone.name()));
            }
        }
        for(double[] table: List.of(QUICK_CALL_II_HZ,PLECTRON_HZ))
        {
            for(double frequency: table)
            {
                BigDecimal hertz = BigDecimal.valueOf(frequency);
                int milliHertz = hertz.movePointRight(3).intValueExact();
                values.putIfAbsent(milliHertz,new Match(milliHertz,"HZ_" + hertz.setScale(2).toPlainString().replace('.', '_')));
            }
        }
        return List.copyOf(values.values());
    }
}
