/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.io.IOException;

/** Adds the original-palette/custom-hue choice without changing other personal settings. */
final class Format30To31DatabaseMigration extends WebUserPreferenceMigration
{
    Format30To31DatabaseMigration() { super(30, 8); }

    @Override public String description() { return "Add a personal theme hue with the original palette as default"; }
    @Override protected String subject() { return "personal theme hue"; }
    @Override protected String preservationDetail()
    {
        return "Preserve all usable version-7 personal preferences, add a null hue for the original palette, " +
                "and increment each preference revision";
    }
    @Override protected String layoutDetail()
    {
        return "Remove only enough cached table layouts to fit the added hue; preserve all other personal settings, " +
                "accounts, credentials and receiver configuration";
    }

    @Override protected void validatePreferences(String source) throws IOException
    {
        Format23WebUserPreferencesCodec.validate(source);
    }

    @Override protected Converted convertPreferences(String source) throws IOException
    {
        var migrated = Format23WebUserPreferencesCodec.migrateToBoundedFormat31(source);
        return new Converted(migrated.json(), migrated.resetLayouts());
    }

    @Override protected String defaultPreferences() throws IOException
    {
        return Format31WebUserPreferencesCodec.defaults();
    }
}
