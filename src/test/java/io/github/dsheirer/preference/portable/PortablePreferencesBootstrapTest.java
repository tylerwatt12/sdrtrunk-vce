/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.preference.portable;

import java.util.prefs.Preferences;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("platform-filesystem")
class PortablePreferencesBootstrapTest
{
    private static final String INITIAL_BACKEND = Preferences.userRoot().getClass().getName();

    @Test
    void installsPortableBackendBeforeTestClassInitialization()
    {
        assertTrue(INITIAL_BACKEND.startsWith(SqlitePreferencesFactory.class.getName() + "$"), INITIAL_BACKEND);
    }
}
