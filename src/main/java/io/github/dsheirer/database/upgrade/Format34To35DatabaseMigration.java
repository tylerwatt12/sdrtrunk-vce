/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.io.IOException;

/** Adds a personal source-name display choice while preserving usable preferences. */
final class Format34To35DatabaseMigration extends WebUserPreferenceMigration
{
    Format34To35DatabaseMigration() { super(34, 9); }

    @Override public String description() { return "Add a personal source-name display choice with Talker Alias preferred"; }
    @Override protected String subject() { return "personal source-name display"; }
    @Override protected String preservationDetail()
    {
        return "Preserve all usable version-8 personal preferences, prefer Talker Alias with Source Alias fallback, " +
                "and increment each preference revision";
    }
    @Override protected String layoutDetail()
    {
        return "Remove only enough cached table layouts to fit the source-name choice; preserve all other personal settings, " +
                "accounts, credentials and receiver configuration";
    }

    @Override protected void validatePreferences(String source) throws IOException
    {
        Format31WebUserPreferencesCodec.validate(source);
    }

    @Override protected Converted convertPreferences(String source) throws IOException
    {
        var migrated = Format35WebUserPreferencesCodec.migrateToBoundedFormat35(source);
        return new Converted(migrated.json(), migrated.resetLayouts());
    }

    @Override protected String defaultPreferences() throws IOException
    {
        return Format35WebUserPreferencesCodec.defaults();
    }
}
