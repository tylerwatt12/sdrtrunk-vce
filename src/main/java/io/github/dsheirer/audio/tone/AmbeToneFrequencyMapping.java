/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.audio.tone;

import io.github.dsheirer.identifier.tone.AmbeTone;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Shared AMBE single-tone frequency table. Assigns decoded tone reports to the same nominal frequencies used
 * by encoded tone metadata; it does not change audio or identify an encoded tone frame.
 */
public final class AmbeToneFrequencyMapping
{
    /** Exact predefined nominal frequency in milliHz and its stable AmbeTone enum name. */
    public record Match(int frequencyMilliHertz, String code)
    {
        public double frequencyHz()
        {
            return frequencyMilliHertz / 1000.;
        }
    }

    //The enum has out-of-order entries; its numeric values, not ordinal arithmetic, define the frequency table.
    private static final List<Match> NOMINALS = Arrays.stream(AmbeTone.values())
        .filter(tone -> tone.name().startsWith("HZ_"))
        .map(tone -> new Match(new BigDecimal(tone.getValue()).movePointRight(3).intValueExact(), tone.name()))
        .sorted(Comparator.comparingInt(Match::frequencyMilliHertz))
        .toList();

    private AmbeToneFrequencyMapping() {}

    /**
     * Nearest nominal inside the predefined single-tone range, with exact ties choosing the lower frequency.
     * Values outside that range are unavailable, rather than clamped to an endpoint.
     */
    public static Optional<Match> nearest(int frequencyMilliHertz)
    {
        if(frequencyMilliHertz < NOMINALS.getFirst().frequencyMilliHertz() ||
            frequencyMilliHertz > NOMINALS.getLast().frequencyMilliHertz())
        {
            return Optional.empty();
        }

        Match nearest = NOMINALS.getFirst();
        int minimumDistance = Math.abs(frequencyMilliHertz - nearest.frequencyMilliHertz());
        for(Match candidate: NOMINALS)
        {
            int distance = Math.abs(frequencyMilliHertz - candidate.frequencyMilliHertz());
            if(distance < minimumDistance)
            {
                nearest = candidate;
                minimumDistance = distance;
            }
        }
        return Optional.of(nearest);
    }

}
