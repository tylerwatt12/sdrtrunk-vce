/* Copyright (C) 2026 Dennis Sheirer */
package io.github.dsheirer.preference;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;

class LocalWindowPreferenceScopeTest
{
    @Test
    void receiverPreferencesDoNotCreateLocalWindowOwnersUntilRequested() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Field swing = UserPreferences.class.getDeclaredField("mSwingPreference");
        Field javaFx = UserPreferences.class.getDeclaredField("mJavaFxPreferences");
        swing.setAccessible(true);
        javaFx.setAccessible(true);
        assertNull(swing.get(preferences));
        assertNull(javaFx.get(preferences));
        var localSwing = preferences.getSwingPreference();
        assertSame(localSwing, preferences.getSwingPreference());
        assertNull(javaFx.get(preferences));
        var localJavaFx = preferences.getJavaFxPreferences();
        assertSame(localJavaFx, preferences.getJavaFxPreferences());
    }
}
