/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

/** Explicit contents selected for an older application database update or import. */
public enum MigrationContent
{
    FULL("Configuration, activity, and statistics"),
    CONFIGURATION_ONLY("Configuration only");

    private final String mDisplayName;

    MigrationContent(String displayName) { mDisplayName = displayName; }

    public String displayName() { return mDisplayName; }
}
