/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.preference.record;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.record.ClassicRecordingPathTemplate;
import java.util.UUID;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;

class RecordPreferenceTest
{
    @Test
    void defaultsToStandardAndRetainsTheCustomTemplateAcrossPreferenceReloads() throws Exception
    {
        Preferences node = Preferences.userRoot().node("/sdrtrunk-vce-tests/classic-naming-" + UUID.randomUUID());
        Preferences parent = node.parent();
        try
        {
            RecordPreference fresh = new RecordPreference(ignored -> {}, node);
            assertFalse(fresh.getClassicNaming().custom());
            assertEquals(ClassicRecordingPathTemplate.STARTER, fresh.getClassicNaming().template().text());
            assertEquals(0, node.keys().length);

            String customPath = "{date}/{standardName}";
            fresh.setClassicNaming(true, ClassicRecordingPathTemplate.compile(customPath));
            RecordPreference reloaded = new RecordPreference(ignored -> {}, node);
            assertTrue(reloaded.getClassicNaming().custom());
            assertEquals(customPath, reloaded.getClassicNaming().template().text());

            reloaded.setClassicNaming(false, null);
            RecordPreference standard = new RecordPreference(ignored -> {}, node);
            assertFalse(standard.getClassicNaming().custom());
            assertEquals(customPath, standard.getClassicNaming().template().text());
        }
        finally
        {
            node.removeNode();
            parent.flush();
        }
    }

    @Test
    void distinguishesAnExplicitSavedChoiceFromTheClassicDefaultWithoutRewritingIt() throws Exception
    {
        Preferences node = Preferences.userRoot().node("/sdrtrunk-vce-tests/record-mode-" + UUID.randomUUID());
        Preferences parent = node.parent();
        try
        {
            RecordPreference fresh = new RecordPreference(ignored -> {}, node);
            assertEquals(RecordingMode.CLASSIC, fresh.getRecordingMode());
            assertNull(fresh.getConfiguredRecordingMode());
            assertNull(node.get("audio.record.mode", null));

            for(RecordingMode mode: RecordingMode.values())
            {
                fresh.setRecordingMode(mode);
                RecordPreference copied = new RecordPreference(ignored -> {}, node);
                assertEquals(mode, copied.getConfiguredRecordingMode());
                assertEquals(mode, copied.getRecordingMode());
            }

            node.put("audio.record.mode", "invalid");
            RecordPreference invalid = new RecordPreference(ignored -> {}, node);
            assertNull(invalid.getConfiguredRecordingMode());
            assertEquals("invalid", node.get("audio.record.mode", null));
        }
        finally
        {
            node.removeNode();
            parent.flush();
        }
    }
}
