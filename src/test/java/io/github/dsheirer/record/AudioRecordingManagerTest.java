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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.preference.record.RecordingMode;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.util.TimeStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioRecordingManagerTest
{
    @TempDir
    Path mTemporaryFolder;
    private RecordingMode mOriginalMode;

    @BeforeEach
    void selectClassicByDefault()
    {
        var preference = new UserPreferences().getRecordPreference();
        mOriginalMode = preference.getRecordingMode();
        preference.setRecordingMode(RecordingMode.CLASSIC);
    }

    @AfterEach
    void restoreRecordingMode()
    {
        if(mOriginalMode != null)
        {
            new UserPreferences().getRecordPreference().setRecordingMode(mOriginalMode);
        }
    }

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

    @Test
    void completedCallsUseWriterClockSecondsAndDistinctMp3Files() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<Path> writtenPaths = new ArrayList<>();
        AtomicInteger recorded = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences,
            ignored -> recorded.incrementAndGet(), scheduler, (call, path, format, userPreferences) -> {
                writtenPaths.add(path);
                Files.write(path, new byte[]{(byte)call.logicalCallId().sequence()}, StandardOpenOption.CREATE_NEW);
            });
        long completedAt = 1_777_777_777_123L;

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getRecordPreference().setAudioRecordFormat(RecordFormat.MP3);
            manager.start();
            manager.receive(completedCall(1, completedAt, List.of(new float[80])));
            manager.receive(completedCall(2, completedAt + 1, List.of(new float[80])));
            manager.stop();

            assertEquals(2, recorded.get());
            assertEquals(2, writtenPaths.size());
            assertNotEquals(writtenPaths.get(0), writtenPaths.get(1));
            for(Path path: writtenPaths)
            {
                assertTrue(path.getFileName().toString().matches("\\d{8}_\\d{6}__TO_56138(?:_V\\d+)?\\.mp3"));
                assertFalse(path.getFileName().toString().startsWith(TimeStamp.getTimeStamp(completedAt, "_")));
            }
            assertFalse(writtenPaths.get(0).getFileName().toString().contains("_CALL_"));
            assertFalse(writtenPaths.get(1).getFileName().toString().contains("_CALL_"));
            assertArrayEquals(new byte[]{1}, Files.readAllBytes(writtenPaths.get(0)));
            assertArrayEquals(new byte[]{2}, Files.readAllBytes(writtenPaths.get(1)));
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
            preferences.getRecordPreference().setAudioRecordFormat(originalFormat);
        }
    }

    @Test
    void basicWriterRetriesCreateNewCollisionsWithoutReplacingExistingFile() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<Path> attempted = new ArrayList<>();
        AtomicInteger recorded = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences,
            ignored -> recorded.incrementAndGet(), scheduler, (call, path, format, userPreferences) -> {
                attempted.add(path);
                if(attempted.size() == 1) Files.write(path, new byte[]{9}, StandardOpenOption.CREATE_NEW);
                AudioCallRecorder.write(call, path, format, userPreferences);
            });
        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getRecordPreference().setAudioRecordFormat(RecordFormat.WAVE);
            manager.start();
            manager.receive(completedCall());
            manager.stop();

            assertEquals(2, attempted.size());
            assertEquals(1, recorded.get());
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(attempted.getFirst()));
            assertTrue(attempted.get(1).getFileName().toString().endsWith("_V2.wav"));
            assertTrue(Files.size(attempted.get(1)) > 44);
            assertEquals(0, manager.getQueueStatus().droppedRecordings());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
            preferences.getRecordPreference().setAudioRecordFormat(originalFormat);
        }
    }

    @Test
    void completedCallQueueIsBoundedByCount() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, scheduler,
            (call, path, format, userPreferences) -> {});

        try
        {
            manager.start();
            CompletedAudioCall call = completedCall(1, List.of(new float[80]));

            for(int index = 0; index < AudioRecordingManager.MAXIMUM_QUEUED_CALLS + 2; index++)
            {
                manager.receive(call);
            }

            AudioRecordingManager.RecordingQueueStatus status = manager.getQueueStatus();
            assertEquals(AudioRecordingManager.MAXIMUM_QUEUED_CALLS, status.queuedCalls());
            assertEquals(2, status.droppedRecordings());
            manager.stop();
            assertEquals(0, manager.getQueueStatus().queuedCalls());
            assertEquals(0, manager.getQueueStatus().queuedSourceBytes());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    void completedCallQueueIsBoundedBySourceBytes() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, scheduler,
            (call, path, format, userPreferences) -> {});
        float[] sharedEightMiBBuffer = new float[2 * 1024 * 1024];
        CompletedAudioCall call = completedCall(1, List.of(sharedEightMiBBuffer));

        try
        {
            manager.start();

            for(int index = 0; index < 40; index++)
            {
                manager.receive(call);
            }

            AudioRecordingManager.RecordingQueueStatus status = manager.getQueueStatus();
            assertEquals(32, status.queuedCalls());
            assertEquals(AudioRecordingManager.MAXIMUM_QUEUED_SOURCE_BYTES, status.queuedSourceBytes());
            assertEquals(8, status.droppedRecordings());
            manager.stop();
            assertEquals(0, manager.getQueueStatus().queuedSourceBytes());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    void emptyAudioCallsNeverEnterRecordingQueue()
    {
        UserPreferences preferences = new UserPreferences();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, scheduler,
            (call, path, format, userPreferences) -> {});

        try
        {
            manager.start();
            manager.receive(completedCall(1, List.of()));
            AudioRecordingManager.RecordingQueueStatus status = manager.getQueueStatus();
            assertEquals(0, status.queuedCalls());
            assertEquals(0, status.queuedSourceBytes());
            assertEquals(0, status.droppedRecordings());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    void modeIsCapturedWhenCallEntersQueueAndManagedFilesStaySeparate() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalClassic = preferences.getDirectoryPreference().getDirectoryRecording();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();
        Path classic = mTemporaryFolder.resolve("recordings");
        Path managed = mTemporaryFolder.resolve("recordings-managed");
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<Path> paths = new ArrayList<>();
        List<RecordFormat> formats = new ArrayList<>();
        List<Path> indexed = new ArrayList<>();
        AtomicInteger recorded = new AtomicInteger();
        AtomicReference<Runnable> indexedObserver = new AtomicReference<>();
        AudioRecordingManager manager = new AudioRecordingManager(preferences,
            ignored -> recorded.incrementAndGet(), scheduler, (call, path, format, userPreferences) -> {
                paths.add(path);
                formats.add(format);
                Files.write(path, new byte[]{1}, StandardOpenOption.CREATE_NEW);
            }, (path, call, size, onIndexed) -> {
                indexed.add(path);
                indexedObserver.set(onIndexed);
                return true;
            }, managed);

        try
        {
            Files.createDirectories(classic);
            preferences.getDirectoryPreference().setDirectoryRecording(classic);
            preferences.getRecordPreference().setAudioRecordFormat(RecordFormat.WAVE);
            preferences.getRecordPreference().setRecordingMode(RecordingMode.MANAGED);
            manager.start();
            manager.receive(completedCall(1, 1_777_777_777_123L, List.of(new float[80])));
            preferences.getRecordPreference().setRecordingMode(RecordingMode.CLASSIC);
            manager.receive(completedCall(2, 1_777_777_777_124L, List.of(new float[80])));
            manager.stop();

            assertEquals(2, paths.size());
            assertEquals(List.of(RecordFormat.MP3, RecordFormat.WAVE), formats);
            assertEquals(1, indexed.size());
            assertEquals(paths.getFirst(), indexed.getFirst());
            assertTrue(paths.getFirst().startsWith(managed));
            assertTrue(paths.getFirst().getFileName().toString().endsWith(".mp3"));
            assertEquals("tg-56138", paths.getFirst().getParent().getFileName().toString());
            assertEquals("unknown-channel", paths.getFirst().getParent().getParent().getFileName().toString());
            assertTrue(paths.getFirst().getParent().getParent().getParent().getFileName().toString()
                .matches("\\d{4}-\\d{2}-\\d{2}"));
            assertEquals(classic, paths.get(1).getParent());
            assertTrue(paths.get(1).getFileName().toString().endsWith(".wav"));
            assertEquals(1, recorded.get(), "managed activity waits for the catalog commit");
            indexedObserver.get().run();
            assertEquals(2, recorded.get());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getRecordPreference().setRecordingMode(originalMode);
            preferences.getRecordPreference().setAudioRecordFormat(originalFormat);
            preferences.getDirectoryPreference().setDirectoryRecording(originalClassic);
        }
    }

    @Test
    void realCatalogCommitsBeforeRecordedActivityIsReported() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();
        Path managed = mTemporaryFolder.resolve("managed");
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        CountDownLatch recorded = new CountDownLatch(1);
        try(ManagedRecordingCatalog catalog = new ManagedRecordingCatalog(
            mTemporaryFolder.resolve("managed.sqlite"), managed))
        {
            AudioRecordingManager manager = new AudioRecordingManager(preferences,
                ignored -> recorded.countDown(), scheduler, (call, path, format, userPreferences) ->
                    Files.write(path, new byte[]{'I', 'D', '3', 'a', 'b'}, StandardOpenOption.CREATE_NEW),
                catalog::submit, managed);
            try
            {
                preferences.getRecordPreference().setRecordingMode(RecordingMode.MANAGED);
                manager.start();
                manager.receive(completedCall());
                manager.stop();
                assertTrue(recorded.await(5, TimeUnit.SECONDS));
                assertEquals(1L, catalog.stats().callCount());
                assertTrue(Files.isRegularFile(catalog.audioPath(1L)));
            }
            finally
            {
                manager.stop();
            }
        }
        finally
        {
            scheduler.shutdownNow();
            preferences.getRecordPreference().setRecordingMode(originalMode);
        }
    }

    @Test
    void rejectedManagedCatalogHandoffRemovesUnindexedAudio() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<Path> written = new ArrayList<>();
        AtomicInteger recorded = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences,
            ignored -> recorded.incrementAndGet(), scheduler, (call, path, format, userPreferences) -> {
                written.add(path);
                Files.write(path, new byte[]{1}, StandardOpenOption.CREATE_NEW);
            }, (path, call, size, onIndexed) -> false, mTemporaryFolder.resolve("managed"));

        try
        {
            preferences.getRecordPreference().setRecordingMode(RecordingMode.MANAGED);
            manager.start();
            manager.receive(completedCall());
            manager.stop();

            assertEquals(1, written.size());
            assertFalse(Files.exists(written.getFirst()));
            assertEquals(0, recorded.get());
            assertEquals(1, manager.getQueueStatus().droppedRecordings());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getRecordPreference().setRecordingMode(originalMode);
        }
    }

    @Test
    void managedModeWithoutCatalogDropsBeforeFileWrite()
    {
        UserPreferences preferences = new UserPreferences();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AtomicInteger writes = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, scheduler,
            (call, path, format, userPreferences) -> writes.incrementAndGet());

        try
        {
            preferences.getRecordPreference().setRecordingMode(RecordingMode.MANAGED);
            manager.start();
            manager.receive(completedCall());
            assertEquals(0, manager.getQueueStatus().queuedCalls());
            assertEquals(0, manager.getQueueStatus().queuedSourceBytes());
            assertEquals(1, manager.getQueueStatus().droppedRecordings());
            assertEquals(RecordingMode.MANAGED, preferences.getRecordPreference().getRecordingMode());
            manager.stop();
            assertEquals(0, writes.get());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getRecordPreference().setRecordingMode(originalMode);
        }
    }

    @Test
    void failedManagedEncodingRemovesPartialFile() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<Path> written = new ArrayList<>();
        AtomicInteger indexed = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, scheduler,
            (call, path, format, userPreferences) -> {
                written.add(path);
                Files.write(path, new byte[]{1}, StandardOpenOption.CREATE_NEW);
                throw new IOException("simulated encoder failure");
            }, (path, call, size, onIndexed) -> {
                indexed.incrementAndGet();
                return true;
            }, mTemporaryFolder.resolve("managed"));

        try
        {
            preferences.getRecordPreference().setRecordingMode(RecordingMode.MANAGED);
            manager.start();
            manager.receive(completedCall());
            manager.stop();

            assertEquals(1, written.size());
            assertFalse(Files.exists(written.getFirst()));
            assertEquals(0, indexed.get());
            assertEquals(1, manager.getQueueStatus().droppedRecordings());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getRecordPreference().setRecordingMode(originalMode);
        }
    }

    private static CompletedAudioCall completedCall()
    {
        return completedCall(1, List.of(new float[800]));
    }

    private static CompletedAudioCall completedCall(long sequence, List<float[]> audioBuffers)
    {
        long now = System.currentTimeMillis();
        return completedCall(sequence, now + 100, audioBuffers);
    }

    private static CompletedAudioCall completedCall(long sequence, long completedAt, List<float[]> audioBuffers)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(56138));
        long startedAt = completedAt - 100;
        AudioCallId callId = new AudioCallId(1L, sequence, 1);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null,
            AliasList.empty("test"),
            identifiers, Set.of(), startedAt, completedAt, 1, 1, startedAt, completedAt, false, true,
            CallEncryptionState.CLEAR, true, null, VoiceCallQuality.EMPTY, CallLegId.from(callId), null, null);
        return new CompletedAudioCall(snapshot, audioBuffers);
    }

    private static class ManualRecordingScheduler extends ScheduledThreadPoolExecutor
    {
        ManualRecordingScheduler()
        {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)
        {
            return super.scheduleAtFixedRate(command, 1, 1, TimeUnit.DAYS);
        }
    }
}
