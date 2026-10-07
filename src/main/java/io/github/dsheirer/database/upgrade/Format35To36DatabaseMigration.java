/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.io.IOException;

/** Adds the Live calls sorting choice without changing other personal settings. */
final class Format35To36DatabaseMigration extends WebUserPreferenceMigration
{
    Format35To36DatabaseMigration() { super(35, 10); }

    @Override public String description() { return "Add personal Live calls sorting"; }
    @Override protected String subject() { return "personal Live calls sorting"; }
    @Override protected String preservationDetail()
    {
        return "Preserve all usable version-9 personal preferences, add order-appeared sorting for active-only views or LCN for views with idle rows, " +
                "and increment each preference revision";
    }
    @Override protected String layoutDetail()
    {
        return "Remove only enough cached table layouts to fit the added sorting choice; preserve all other personal settings, " +
                "accounts, credentials and receiver configuration";
    }

    @Override protected void validatePreferences(String source) throws IOException
    {
        Format35WebUserPreferencesCodec.validate(source);
    }

    @Override protected Converted convertPreferences(String source) throws IOException
    {
        var migrated = Format36WebUserPreferencesCodec.migrateToBoundedFormat36(source);
        return new Converted(migrated.json(), migrated.resetLayouts());
    }

    @Override protected String defaultPreferences() throws IOException
    {
        return Format36WebUserPreferencesCodec.defaults();
    }
}
