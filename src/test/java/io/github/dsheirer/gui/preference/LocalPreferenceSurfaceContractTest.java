/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.gui.preference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Keeps the Java preferences surface limited to local setup and maintenance. */
class LocalPreferenceSurfaceContractTest
{
    private static final Path PREFERENCES = Path.of("src/main/java/io/github/dsheirer/gui/preference");

    @Test
    void operationalAudioAndStatisticsControlsAreWebOwned() throws Exception
    {
        String types = Files.readString(PREFERENCES.resolve("PreferenceEditorType.java"));
        String factory = Files.readString(PREFERENCES.resolve("PreferenceEditorFactory.java"));
        String tree = Files.readString(PREFERENCES.resolve("UserPreferencesEditor.java"));
        String stats = Files.readString(PREFERENCES.resolve("stats/StatsServerPreferenceEditor.java"));

        assertFalse(Files.exists(PREFERENCES.resolve("call/CallManagementPreferenceEditor.java")));
        assertFalse(Files.exists(PREFERENCES.resolve("mp3/MP3PreferenceEditor.java")));
        assertFalse(Files.exists(PREFERENCES.resolve("record/RecordPreferenceEditor.java")));
        assertFalse(types.contains("AUDIO_CALL_MANAGEMENT"));
        assertFalse(types.contains("AUDIO_MP3"));
        assertFalse(types.contains("AUDIO_RECORD"));
        assertFalse(factory.contains("CallManagementPreferenceEditor"));
        assertFalse(factory.contains("MP3PreferenceEditor"));
        assertFalse(factory.contains("RecordPreferenceEditor"));
        assertFalse(tree.contains("TreeItem<Object> audioItem"));
        assertTrue(types.contains("STATS_SERVER(\"Stats Database Maintenance\")"));
        assertFalse(stats.contains("setStatsLoggingEnabled"));
        assertFalse(stats.contains("setStatsDetailedHistoryEnabled"));
        assertFalse(stats.contains("setStatsLoggingRetentionDays"));
        assertTrue(stats.contains("ReceiverActivityPath.getDatabasePath(mUserPreferences)"));
        assertTrue(stats.contains("ReceiverActivityMaintenance.Operation.MAINTAIN"));
        assertTrue(stats.contains("ReceiverActivityMaintenance.Operation.SHRINK"));
        assertTrue(stats.contains("ReceiverActivityMaintenance.Operation.CHECK"));
        assertTrue(stats.contains("ReceiverActivityMaintenance.Operation.RESET_STATS"));
    }

    @Test
    void localWebBootstrapAndHardwareSetupRemainAvailable() throws Exception
    {
        String types = Files.readString(PREFERENCES.resolve("PreferenceEditorType.java"));
        String tree = Files.readString(PREFERENCES.resolve("UserPreferencesEditor.java"));

        for(String type: new String[]{"DIRECTORY", "JMBE_LIBRARY", "VOICE_DECRYPTION_MODULE", "SOURCE_TUNERS", "WEB_SERVER"})
        {
            assertTrue(types.contains(type));
            assertTrue(tree.contains("PreferenceEditorType." + type));
        }
        assertTrue(Files.exists(PREFERENCES.resolve("stats/WebServerPreferenceEditor.java")));
    }

    @Test
    void unconsumedLegacyPreferenceEditorsAreAbsentButStoredModelsRemain() throws Exception
    {
        String types = Files.readString(PREFERENCES.resolve("PreferenceEditorType.java"));
        String factory = Files.readString(PREFERENCES.resolve("PreferenceEditorFactory.java"));
        String tree = Files.readString(PREFERENCES.resolve("UserPreferencesEditor.java"));
        Path preferenceModels = Path.of("src/main/java/io/github/dsheirer/preference");

        assertFalse(Files.exists(PREFERENCES.resolve("application/ApplicationPreferenceEditor.java")));
        assertFalse(Files.exists(PREFERENCES.resolve("DecodeEventViewPreferenceEditor.java")));
        assertFalse(Files.exists(PREFERENCES.resolve("TalkgroupFormatPreferenceEditor.java")));
        for(String type: new String[]{"APPLICATION", "CHANNEL_EVENT", "TALKGROUP_FORMAT"})
        {
            assertFalse(types.contains(type + "("));
            assertFalse(tree.contains("PreferenceEditorType." + type));
        }
        assertFalse(factory.contains("ApplicationPreferenceEditor"));
        assertFalse(factory.contains("DecodeEventViewPreferenceEditor"));
        assertFalse(factory.contains("TalkgroupFormatPreferenceEditor"));
        assertFalse(tree.contains("TreeItem<Object> displayItem"));

        assertTrue(Files.exists(preferenceModels.resolve("application/ApplicationPreference.java")));
        assertTrue(Files.exists(preferenceModels.resolve("event/DecodeEventPreference.java")));
        assertTrue(Files.exists(preferenceModels.resolve("identifier/TalkgroupFormatPreference.java")));
        assertTrue(Files.exists(preferenceModels.resolve("TimestampFormat.java")));
    }
}
