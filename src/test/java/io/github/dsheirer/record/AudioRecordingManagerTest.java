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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
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

    @Test
    void completedCallQueueIsBoundedByCount() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        boolean originalDuplicateSuppression =
            preferences.getCallManagementPreference().isDuplicateRecordingSuppressionEnabled();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, scheduler,
            AudioCallRecorder::write);

        try
        {
            preferences.getCallManagementPreference().setDuplicateRecordingSuppressionEnabled(true);
            manager.start();
            CompletedAudioCall duplicate = completedCall(1, true, List.of(new float[80]));

            for(int index = 0; index < AudioRecordingManager.MAXIMUM_QUEUED_CALLS + 2; index++)
            {
                manager.receive(duplicate);
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
            preferences.getCallManagementPreference()
                .setDuplicateRecordingSuppressionEnabled(originalDuplicateSuppression);
        }
    }

    @Test
    void completedCallQueueIsBoundedBySourceBytes() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        boolean originalDuplicateSuppression =
            preferences.getCallManagementPreference().isDuplicateRecordingSuppressionEnabled();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, scheduler,
            AudioCallRecorder::write);
        float[] sharedEightMiBBuffer = new float[2 * 1024 * 1024];
        CompletedAudioCall duplicate = completedCall(1, true, List.of(sharedEightMiBBuffer));

        try
        {
            preferences.getCallManagementPreference().setDuplicateRecordingSuppressionEnabled(true);
            manager.start();

            for(int index = 0; index < 40; index++)
            {
                manager.receive(duplicate);
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
            preferences.getCallManagementPreference()
                .setDuplicateRecordingSuppressionEnabled(originalDuplicateSuppression);
        }
    }

    @Test
    void stopWaitsForInFlightWriterBeforeFinalDrain() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        CountDownLatch firstWriterEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstWriter = new CountDownLatch(1);
        CountDownLatch stopTaskStarted = new CountDownLatch(1);
        AtomicInteger activeWriters = new AtomicInteger();
        AtomicInteger maximumActiveWriters = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        AudioRecordingManager.RecordingWriter writer = (call, path, format, userPreferences) -> {
            int active = activeWriters.incrementAndGet();
            maximumActiveWriters.accumulateAndGet(active, Math::max);
            int writeIndex = writes.getAndIncrement();

            try
            {
                if(writeIndex == 0)
                {
                    firstWriterEntered.countDown();

                    try
                    {
                        if(!releaseFirstWriter.await(2, TimeUnit.SECONDS))
                        {
                            throw new java.io.IOException("Timed out waiting to release test writer");
                        }
                    }
                    catch(InterruptedException exception)
                    {
                        Thread.currentThread().interrupt();
                        throw new java.io.IOException("Interrupted test writer", exception);
                    }
                }

                Files.write(path, new byte[]{1}, StandardOpenOption.CREATE_NEW);
            }
            finally
            {
                activeWriters.decrementAndGet();
            }
        };
        AtomicInteger recorded = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences,
            ignored -> recorded.incrementAndGet(), scheduler, writer);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getRecordPreference().setAudioRecordFormat(RecordFormat.WAVE);
            manager.start();
            manager.receive(completedCall(1, false, List.of(new float[80])));
            manager.receive(completedCall(2, false, List.of(new float[80])));
            Future<?> processor = executor.submit(manager.new QueueProcessor());
            assertTrue(firstWriterEntered.await(1, TimeUnit.SECONDS));
            Future<?> stopping = executor.submit(() -> {
                stopTaskStarted.countDown();
                manager.stop();
            });
            assertTrue(stopTaskStarted.await(1, TimeUnit.SECONDS));
            assertTrue(awaitWaitingDrain(manager, 1, TimeUnit.SECONDS),
                "Stop did not reach the single-writer drain lock");
            assertFalse(stopping.isDone(), "Stop must wait for the in-flight recording writer");
            releaseFirstWriter.countDown();
            processor.get(2, TimeUnit.SECONDS);
            stopping.get(2, TimeUnit.SECONDS);

            assertEquals(1, maximumActiveWriters.get());
            assertEquals(2, writes.get());
            assertEquals(2, recorded.get());
            assertEquals(0, manager.getQueueStatus().queuedCalls());

            try(var files = Files.list(mTemporaryFolder))
            {
                assertEquals(2, files.filter(Files::isRegularFile).count());
            }
        }
        finally
        {
            releaseFirstWriter.countDown();
            manager.stop();
            executor.shutdownNow();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
            preferences.getRecordPreference().setAudioRecordFormat(originalFormat);
        }
    }

    private static CompletedAudioCall completedCall()
    {
        return completedCall(1, false, List.of(new float[800]));
    }

    private static CompletedAudioCall completedCall(long sequence, boolean duplicate, List<float[]> audioBuffers)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(56138));
        long now = System.currentTimeMillis();
        AudioCallSnapshot snapshot = new AudioCallSnapshot(new AudioCallId(1L, sequence, 1), null,
            AliasList.empty("test"),
            identifiers, Set.of(), now, now + 100, 1, 1, now, now + 100, false, true, false, true,
            100, duplicate);
        return new CompletedAudioCall(snapshot, audioBuffers);
    }

    private static boolean awaitWaitingDrain(AudioRecordingManager manager, long timeout, TimeUnit unit)
        throws InterruptedException
    {
        long deadline = System.nanoTime() + unit.toNanos(timeout);

        while(System.nanoTime() < deadline)
        {
            if(manager.getQueueStatus().waitingDrains() > 0)
            {
                return true;
            }

            Thread.sleep(10);
        }

        return manager.getQueueStatus().waitingDrains() > 0;
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
