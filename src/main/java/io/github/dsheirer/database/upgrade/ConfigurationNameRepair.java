/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.database.upgrade;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Deterministically canonicalizes persisted configuration names and separates post-trim collisions. */
final class ConfigurationNameRepair
{
    private ConfigurationNameRepair()
    {
    }

    static Map<Long,String> plan(List<Candidate> candidates, int maximumLength, boolean unicodeWhitespace)
    {
        List<Candidate> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingLong(Candidate::id));
        Map<String,List<Candidate>> groups = new LinkedHashMap<>();
        Map<Long,String> canonical = new LinkedHashMap<>();
        for(Candidate candidate: ordered)
        {
            String prepared = unicodeWhitespace ? candidate.name().strip() : candidate.name().trim();
            String recovered = "Recovered " + candidate.id();
            if(prepared.isBlank())
            {
                //Callers normally supply a component-specific recovered name. Keep this shared planner defensive so
                //an unusual Unicode blank cannot turn one recoverable row into a whole-migration failure.
                prepared = recovered;
            }
            prepared = truncateToCodePoints(prepared, maximumLength).stripTrailing();
            if(prepared.isBlank())
            {
                //Truncation can remove the first visible character after a prefix made entirely of Unicode blanks.
                prepared = truncateToCodePoints(recovered, maximumLength).stripTrailing();
            }
            canonical.put(candidate.id(), prepared);
            groups.computeIfAbsent(normalize(prepared), ignored -> new ArrayList<>()).add(candidate);
        }

        Set<String> reserved = new LinkedHashSet<>(groups.keySet());
        Map<Long,String> planned = new LinkedHashMap<>();
        for(List<Candidate> group: groups.values())
        {
            String base = canonical.get(group.get(0).id());
            planned.put(group.get(0).id(), base);
            int suffix = 2;
            for(int index = 1; index < group.size(); index++)
            {
                String unique;
                do
                {
                    unique = withSuffix(base, suffix++, maximumLength);
                }
                while(!reserved.add(normalize(unique)));
                planned.put(group.get(index).id(), unique);
            }
        }
        return Map.copyOf(planned);
    }

    static String normalize(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    static int codePointLength(String value)
    {
        return value.codePointCount(0, value.length());
    }

    static String truncateToCodePoints(String value, int maximumLength)
    {
        int retainedCodePoints = Math.min(codePointLength(value), maximumLength);
        return value.substring(0, value.offsetByCodePoints(0, retainedCodePoints));
    }

    private static String withSuffix(String base, int number, int maximumLength)
    {
        String suffix = " (" + number + ")";
        int suffixLength = codePointLength(suffix);
        if(suffixLength >= maximumLength)
        {
            throw new IllegalArgumentException("Configuration-name suffix exceeds the supported length");
        }
        return truncateToCodePoints(base, maximumLength - suffixLength).stripTrailing() + suffix;
    }

    record Candidate(long id, String name)
    {
    }
}
