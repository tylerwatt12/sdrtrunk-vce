/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.service.radioreference;

import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Shared naming suggestions; directory labels never change decoder or local handling settings. */
public record RadioReferenceChannelNames(String system, String site, String name)
{
    public static RadioReferenceChannelNames trunked(String system, String site, long frequencyHz)
    {
        String systemName = text(system);
        String siteName = text(site);
        String channelName = !systemName.isEmpty() && !siteName.isEmpty() ? systemName + " · " + siteName :
            first(siteName, systemName, frequencyName(frequencyHz));
        return new RadioReferenceChannelNames(systemName, siteName, channelName);
    }

    public static RadioReferenceChannelNames conventional(String county, String agency, String state,
        String category, String subCategory, String description, String alphaTag, long frequencyHz)
    {
        String context = Stream.of(county, agency).map(RadioReferenceChannelNames::text)
            .filter(value -> !value.isEmpty()).distinct().collect(Collectors.joining(" · "));
        return new RadioReferenceChannelNames(first(context, state), first(subCategory, category),
            conventionalChannelName(description, alphaTag, frequencyHz));
    }

    public static String conventionalChannelName(String description, String alphaTag, long frequencyHz)
    {
        return first(description, alphaTag, frequencyName(frequencyHz));
    }

    private static String frequencyName(long frequencyHz)
    {
        return String.format(Locale.ROOT, "%.6f MHz", frequencyHz / 1_000_000.0);
    }

    private static String first(String... values)
    {
        return Stream.of(values).map(RadioReferenceChannelNames::text).filter(value -> !value.isEmpty())
            .findFirst().orElse("");
    }

    private static String text(String value) { return value == null ? "" : value.strip(); }
}
