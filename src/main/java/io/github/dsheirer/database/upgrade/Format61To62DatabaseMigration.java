/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import java.io.IOException;

/** Adds an explicit account-owned automatic page-guide choice without changing receiver configuration. */
final class Format61To62DatabaseMigration extends WebUserPreferenceMigration
{
    Format61To62DatabaseMigration() { super(61, 12); }

    @Override public String description() { return "Add personal automatic page-guide preference"; }
    @Override protected String subject() { return "personal automatic page-guide preference"; }
    @Override protected String preservationDetail()
    {
        return "Preserve all usable version-11 personal settings, enable automatic page guides, and increment each " +
            "preference revision once; preserve valid account timestamps, authentication and tour dismissal history";
    }
    @Override protected String layoutDetail()
    {
        return "Remove only enough cached table layouts to fit the added guide choice; preserve all non-layout personal " +
            "settings, accounts, credentials, tour dismissal history and receiver configuration";
    }
    @Override protected boolean preserveAccountTimestamps() { return true; }
    @Override protected void validatePreferences(String source) throws IOException
    {
        Format43WebUserPreferencesCodec.validate(source);
    }
    @Override protected Converted convertPreferences(String source) throws IOException
    {
        var migrated = Format62WebUserPreferencesCodec.migrateFromFormat61(source);
        return new Converted(migrated.json(), migrated.resetLayouts());
    }
    @Override protected String defaultPreferences() throws IOException
    {
        return Format62WebUserPreferencesCodec.defaults();
    }
}
