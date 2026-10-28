/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.web.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.preference.call.CallFilteringSettings;
import io.github.dsheirer.preference.call.CallManagementPreference;
import java.util.prefs.BackingStoreException;
import org.junit.jupiter.api.Test;

class CallFilteringSettingsServiceTest
{
    @Test
    void revisionTracksBothFieldsAndRejectsStaleChangesAcrossServiceInstances() throws Exception
    {
        Calls calls = new Calls();
        CallFilteringSettingsService service = new CallFilteringSettingsService(calls);
        CallFilteringSettingsService second = new CallFilteringSettingsService(calls);
        CallFilteringSettingsService.Snapshot initial = service.snapshot();
        assertFalse(initial.enabled());
        assertEquals(64, initial.revision().length());
        assertEquals(null, initial.minimumUsableVoicePercent());
        var saved = service.replace(initial.revision(), new CallFilteringSettings(true, 75));
        assertTrue(saved.updated());
        assertNotEquals(initial.revision(), saved.snapshot().revision());
        assertFalse(second.replace(initial.revision(), new CallFilteringSettings(true, 90)).updated());
        assertEquals(new CallFilteringSettings(true, 75), calls.settings);
        var disabled = second.replace(saved.snapshot().revision(), new CallFilteringSettings(false, 75));
        assertTrue(disabled.updated());
        assertFalse(disabled.snapshot().enabled());
        assertEquals(75, disabled.snapshot().minimumUsableVoicePercent());
        assertNotEquals(saved.snapshot().revision(), disabled.snapshot().revision());
        calls.setCallFilteringSettings(new CallFilteringSettings(false, 80));
        assertFalse(service.replace(disabled.snapshot().revision(), new CallFilteringSettings(true, 75)).updated());
    }

    @Test
    void saveFailureKeepsThePreviouslyPublishedPolicyAndRevision() throws Exception
    {
        Calls calls = new Calls();
        CallFilteringSettingsService service = new CallFilteringSettingsService(calls);
        var initial = service.snapshot();
        calls.failSave = true;
        assertThrows(BackingStoreException.class,
            () -> service.replace(initial.revision(), new CallFilteringSettings(true, 60)));
        assertEquals(initial, service.snapshot());
    }

    private static final class Calls extends CallManagementPreference
    {
        private CallFilteringSettings settings = CallFilteringSettings.DISABLED;
        private boolean failSave;
        private Calls() { super(ignored -> {}); }
        @Override public CallFilteringSettings getCallFilteringSettings() { return settings; }
        @Override public synchronized void setCallFilteringSettings(CallFilteringSettings value) throws BackingStoreException
        {
            if(failSave) throw new BackingStoreException("Simulated save failure");
            settings = value;
        }
    }
}
