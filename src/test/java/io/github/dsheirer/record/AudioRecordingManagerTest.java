/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */

package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioRecordingManagerTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void reportsRecordedOnlyAfterPermanentFileExists() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        CountDownLatch recorded = new CountDownLatch(1);
        AtomicInteger metrics = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, call -> {
            metrics.incrementAndGet();
            recorded.countDown();
        });

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getRecordPreference().setAudioRecordFormat(RecordFormat.WAVE);
            manager.start();
            manager.receive(completedCall());

            assertTrue(recorded.await(5, TimeUnit.SECONDS));
            assertEquals(1, metrics.get());

            try(var files = Files.list(mTemporaryFolder))
            {
                List<Path> recordings = files.filter(Files::isRegularFile).toList();
                assertEquals(1, recordings.size());
                assertTrue(Files.size(recordings.getFirst()) > 0);
            }
        }
        finally
        {
            manager.stop();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
            preferences.getRecordPreference().setAudioRecordFormat(originalFormat);
        }
    }

    private static CompletedAudioCall completedCall()
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(56138));
        long now = System.currentTimeMillis();
        AudioCallSnapshot snapshot = new AudioCallSnapshot(new AudioCallId(1L, 1L, 1), null,
            new AliasList("test"),
            identifiers, Set.of(), now, now + 100, 1, 1, now, now + 100, false, true, false, true,
            100, false);
        return new CompletedAudioCall(snapshot, List.of(new float[800]));
    }
}
