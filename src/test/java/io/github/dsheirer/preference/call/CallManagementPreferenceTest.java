/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.preference.call;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.audio.broadcast.PatchGroupStreamingOption;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.AbstractPreferences;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class CallManagementPreferenceTest
{
    @Test
    void filteringStartsUnconfiguredAndRetainsExplicitThresholdAcrossDisableAndReload() throws Exception
    {
        MemoryPreferences store = new MemoryPreferences();
        AtomicInteger notifications = new AtomicInteger();
        CallManagementPreference preference = new CallManagementPreference(ignored -> notifications.incrementAndGet(), store);
        assertEquals(CallFilteringSettings.DISABLED, preference.getCallFilteringSettings());
        assertTrue(store.values.isEmpty());

        preference.setCallFilteringSettings(new CallFilteringSettings(true, 1));
        assertEquals(new CallFilteringSettings(true, 1), preference.getCallFilteringSettings());
        preference.setCallFilteringSettings(new CallFilteringSettings(false, 1));
        CallManagementPreference reloaded = new CallManagementPreference(ignored -> {}, store);
        assertEquals(new CallFilteringSettings(false, 1), reloaded.getCallFilteringSettings());
        assertEquals(2, notifications.get());
        reloaded.setCallFilteringSettings(new CallFilteringSettings(true, 100));
        assertEquals(new CallFilteringSettings(true, 100), reloaded.getCallFilteringSettings());
    }

    @Test
    void filteringValidatesBothValuesAndPublishesOnlyAfterSuccessfulSave() throws Exception
    {
        MemoryPreferences store = new MemoryPreferences();
        AtomicInteger notifications = new AtomicInteger();
        CallManagementPreference preference = new CallManagementPreference(ignored -> notifications.incrementAndGet(), store);
        assertThrows(IllegalArgumentException.class, () -> new CallFilteringSettings(true, null));
        assertThrows(IllegalArgumentException.class, () -> new CallFilteringSettings(false, 0));
        assertThrows(IllegalArgumentException.class, () -> new CallFilteringSettings(true, 101));
        assertTrue(store.values.isEmpty());

        CallFilteringSettings saved = new CallFilteringSettings(true, 70);
        preference.setCallFilteringSettings(saved);
        store.failFlush = true;
        assertThrows(BackingStoreException.class,
            () -> preference.setCallFilteringSettings(new CallFilteringSettings(false, 85)));
        assertSame(saved, preference.getCallFilteringSettings());
        assertEquals(saved, new CallManagementPreference(ignored -> {}, store).getCallFilteringSettings());
        assertEquals(1, notifications.get());
        store.values.clear(); // Workers read the cached snapshot, not the preference store.
        assertSame(saved, preference.getCallFilteringSettings());
    }

    @Test
    void invalidSavedFilteringDoesNotEnableAnUnconfiguredPolicy()
    {
        MemoryPreferences store = new MemoryPreferences();
        store.putBoolean("call.filtering.enabled", true);
        CallManagementPreference preference = new CallManagementPreference(ignored -> {}, store);
        assertFalse(preference.getCallFilteringSettings().enabled());
        assertNull(preference.getCallFilteringSettings().minimumUsableVoicePercent());
        store.put("call.filtering.minimum.usable.voice.percent", "bad");
        assertEquals(CallFilteringSettings.DISABLED,
            new CallManagementPreference(ignored -> {}, store).getCallFilteringSettings());
    }

    @Test
    void preservesHistoricalPreferenceNodeAndRoundTripsIndependentCallSettings() throws Exception
    {
        assertEquals("/io/github/dsheirer/preference/duplicate",
            CallManagementPreference.PREFERENCE_NODE_PATH);
        Preferences preferences = Preferences.userRoot().node(
            "/io/github/dsheirer/test/call-management-" + UUID.randomUUID());

        try
        {
            preferences.put("patchgroup.streaming", PatchGroupStreamingOption.TALKGROUPS.name());
            CallManagementPreference preference = new CallManagementPreference(ignored -> {}, preferences);

            assertEquals(PatchGroupStreamingOption.TALKGROUPS, preference.getPatchGroupStreamingOption());
            assertEquals(CallFilteringSettings.DISABLED, preference.getCallFilteringSettings());
            assertNull(preferences.get("call.filtering.enabled", null));
            assertNull(preferences.get("call.filtering.minimum.usable.voice.percent", null));

            preference.setPatchGroupStreamingOption(PatchGroupStreamingOption.PATCH_GROUP);
            assertEquals(PatchGroupStreamingOption.PATCH_GROUP.name(),
                preferences.get("patchgroup.streaming", null));
            preference.setCallFilteringSettings(new CallFilteringSettings(true, 75));
            preference.setCallFilteringSettings(new CallFilteringSettings(false, 75));
            CallManagementPreference reloaded = new CallManagementPreference(ignored -> {}, preferences);
            assertEquals(new CallFilteringSettings(false, 75), reloaded.getCallFilteringSettings());
            assertEquals("false", preferences.get("call.filtering.enabled", null));
            assertEquals("75", preferences.get("call.filtering.minimum.usable.voice.percent", null));
            assertEquals(PatchGroupStreamingOption.PATCH_GROUP, reloaded.getPatchGroupStreamingOption());
        }
        finally
        {
            Preferences parent = preferences.parent();
            preferences.removeNode();
            parent.flush();
        }
    }

    private static final class MemoryPreferences extends AbstractPreferences
    {
        private final Map<String,String> values = new HashMap<>();
        private boolean failFlush;

        private MemoryPreferences() { super(null, ""); }
        @Override protected void putSpi(String key, String value) { values.put(key, value); }
        @Override protected String getSpi(String key) { return values.get(key); }
        @Override protected void removeSpi(String key) { values.remove(key); }
        @Override protected void removeNodeSpi() { values.clear(); }
        @Override protected String[] keysSpi() { return values.keySet().toArray(String[]::new); }
        @Override protected String[] childrenNamesSpi() { return new String[0]; }
        @Override protected AbstractPreferences childSpi(String name) { throw new UnsupportedOperationException(); }
        @Override protected void syncSpi() { }
        @Override protected void flushSpi() throws BackingStoreException
        {
            if(failFlush)
            {
                failFlush = false;
                throw new BackingStoreException("Simulated settings save failure");
            }
        }
    }
}
