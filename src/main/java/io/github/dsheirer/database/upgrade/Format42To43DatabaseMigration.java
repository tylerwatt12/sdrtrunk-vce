/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.io.IOException;

/** Adds the Live row density choice without changing other personal settings. */
final class Format42To43DatabaseMigration extends WebUserPreferenceMigration
{
    Format42To43DatabaseMigration() { super(42, 11); }

    @Override public String description() { return "Add personal Live row density"; }
    @Override protected String subject() { return "personal Live row density"; }
    @Override protected String preservationDetail()
    {
        return "Preserve all usable version-10 personal preferences, add Normal Live row density, " +
                "and increment each preference revision";
    }
    @Override protected String layoutDetail()
    {
        return "Remove only enough cached table layouts to fit the added row density choice; preserve all other personal settings, " +
                "accounts, credentials and receiver configuration";
    }

    @Override protected void validatePreferences(String source) throws IOException
    {
        Format36WebUserPreferencesCodec.validate(source);
    }

    @Override protected Converted convertPreferences(String source) throws IOException
    {
        var migrated = Format43WebUserPreferencesCodec.migrateToBoundedFormat43(source);
        return new Converted(migrated.json(), migrated.resetLayouts());
    }

    @Override protected String defaultPreferences() throws IOException
    {
        return Format43WebUserPreferencesCodec.defaults();
    }
}
