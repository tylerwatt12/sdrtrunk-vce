/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.preference.call;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.dsheirer.audio.broadcast.PatchGroupStreamingOption;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class CallManagementPreferenceTest
{
    @Test
    void preservesHistoricalPatchGroupPreferenceNodeAndValue() throws Exception
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

            preference.setPatchGroupStreamingOption(PatchGroupStreamingOption.PATCH_GROUP);
            assertEquals(PatchGroupStreamingOption.PATCH_GROUP.name(),
                preferences.get("patchgroup.streaming", null));
        }
        finally
        {
            Preferences parent = preferences.parent();
            preferences.removeNode();
            parent.flush();
        }
    }
}
