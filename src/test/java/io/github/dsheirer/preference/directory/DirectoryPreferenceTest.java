/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.preference.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryPreferenceTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void runtimeLockPreventsWriterCatalogAndRestartRootsFromDiverging() throws Exception
    {
        Preferences preferences = Preferences.userRoot().node(
            "/io/github/dsheirer/test/" + UUID.randomUUID());
        AtomicInteger updates = new AtomicInteger();
        Path startupRoot = mTemporaryFolder.resolve("startup-recordings");
        Path attemptedRoot = mTemporaryFolder.resolve("attempted-recordings");

        try
        {
            DirectoryPreference preference =
                new DirectoryPreference(ignored -> updates.incrementAndGet(), preferences);
            preference.setDirectoryRecording(startupRoot);
            Path locked = preference.lockRecordingDirectoryForRuntime();
            assertEquals(startupRoot.toRealPath(), locked);
            Files.writeString(locked.resolve("retained-before-restart.txt"), "owned");

            preference.setDirectoryRecording(attemptedRoot);
            preference.resetDirectoryRecording();

            assertTrue(preference.isRecordingDirectoryRuntimeLocked());
            assertEquals(locked, preference.getDirectoryRecording().toRealPath());
            assertEquals(1, updates.get(), "Rejected runtime changes must not emit preference updates");

            DirectoryPreference restarted = new DirectoryPreference(ignored -> {}, preferences);
            assertEquals(locked, restarted.getDirectoryRecording().toRealPath(),
                "A restart must continue owning files cataloged beneath the previous runtime root");
            assertTrue(Files.exists(restarted.getDirectoryRecording().resolve("retained-before-restart.txt")));
        }
        finally
        {
            preferences.removeNode();
        }
    }
}
