/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.identifier.alias;

/** Existing OTA alias text policy shared by storage and actions against the displayed name. */
public final class TalkerAliasText
{
    public static final int MAX_CODE_POINTS = 160;
    public static final int MAX_UTF16_UNITS = MAX_CODE_POINTS * 2;

    private TalkerAliasText() {}

    public static String normalize(String alias)
    {
        if(alias == null || alias.isBlank())
        {
            return null;
        }

        String normalized = alias.strip();
        int codePointCount = normalized.codePointCount(0, normalized.length());
        if(codePointCount <= MAX_CODE_POINTS)
        {
            return normalized;
        }

        int end = normalized.offsetByCodePoints(0, MAX_CODE_POINTS);
        return normalized.substring(0, end);
    }
}
