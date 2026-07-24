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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.id.record.Record;
import io.github.dsheirer.alias.id.talkgroup.Talkgroup;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.identifier.configuration.ChannelConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.protocol.Protocol;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioRecordingManagerTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void reportsWaveOnlyAfterPermanentFileExists() throws Exception
    {
        assertRecordedOnlyAfterPermanentFileExists(RecordFormat.WAVE);
    }

    @Test
    void reportsMp3OnlyAfterPermanentFileExists() throws Exception
    {
        assertRecordedOnlyAfterPermanentFileExists(RecordFormat.MP3);
    }

    private void assertRecordedOnlyAfterPermanentFileExists(RecordFormat recordFormat) throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        AtomicInteger metrics = new AtomicInteger();
        AtomicReference<RecordedCallArtifact> artifact = new AtomicReference<>();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, call -> metrics.incrementAndGet(),
            artifact::set, scheduler);

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getRecordPreference().setAudioRecordFormat(recordFormat);
            manager.start();
            manager.receive(completedCall());
            manager.stop();

            assertEquals(1, metrics.get());
            assertNotNull(artifact.get());
            assertTrue(artifact.get().destinationTalkgroupRecordEnabled());
            assertTrue(artifact.get().relativePath().toString().replace('\\', '/').startsWith("calls/v1/"));

            try(var files = Files.walk(mTemporaryFolder))
            {
                List<Path> recordings = files.filter(Files::isRegularFile).toList();
                assertEquals(1, recordings.size());
                assertTrue(Files.size(recordings.getFirst()) > 0);
                assertEquals(artifact.get().path().toRealPath(), recordings.getFirst().toRealPath());
                assertFalse(recordings.getFirst().getFileName().toString().endsWith(".part"));
                RecordedCallManifest manifest =
                    RecordedCallManifest.readFromAudioFile(recordings.getFirst(), recordFormat).orElseThrow();
                assertEquals(artifact.get().callId(), manifest.callId());
                assertEquals(artifact.get().metadata(), manifest.metadata());
                assertEquals("Fire Dispatch", manifest.metadata().destinationAlias());
                assertTrue(manifest.recordEligible());
            }
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
    void recordingHandoffIsBoundedAndNeverWaitsForDiskWork()
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        boolean originalDuplicateSuppression =
            preferences.getCallManagementPreference().isDuplicateRecordingSuppressionEnabled();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, null, scheduler);

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getCallManagementPreference().setDuplicateRecordingSuppressionEnabled(true);
            manager.start();
            CompletedAudioCall call = completedCall(1, true, true, List.of(new float[800]));
            long started = System.nanoTime();

            for(int index = 0; index < 1_000; index++)
            {
                manager.receive(call);
            }

            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            AudioRecordingManager.RecordingQueueStatus status = manager.getQueueStatus();
            assertTrue(elapsedMs < 1_000, "Completed-call recording handoff blocked on storage work");
            assertEquals(128, status.queuedCalls());
            assertTrue(status.queuedSourceBytes() <= 256L * 1024L * 1024L);
            assertTrue(status.droppedRecordings() > 0);
            manager.stop();
            assertEquals(0, manager.getQueueStatus().queuedCalls());
            assertEquals(0, manager.getQueueStatus().queuedSourceBytes());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
            preferences.getCallManagementPreference()
                .setDuplicateRecordingSuppressionEnabled(originalDuplicateSuppression);
        }
    }

    @Test
    void stopDrainsAcceptedCallsRejectsLateCallsAndSupportsRestart()
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AtomicInteger artifacts = new AtomicInteger();
        AudioRecordingManager manager =
            new AudioRecordingManager(preferences, null, artifact -> artifacts.incrementAndGet(), scheduler);

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getRecordPreference().setAudioRecordFormat(RecordFormat.WAVE);
            manager.start();
            manager.receive(completedCall(1, true, false, List.of(new float[80])));
            manager.stop();
            assertEquals(1, artifacts.get());
            assertEquals(0, manager.getQueueStatus().queuedSourceBytes());
            long drops = manager.getQueueStatus().droppedRecordings();
            manager.receive(completedCall(2, true, false, List.of(new float[80])));
            assertEquals(drops + 1, manager.getQueueStatus().droppedRecordings());
            assertEquals(0, manager.getQueueStatus().queuedCalls());

            manager.start();
            manager.receive(completedCall(3, true, false, List.of(new float[80])));
            manager.stop();
            assertEquals(2, artifacts.get());
            assertFalse(manager.getQueueStatus().acceptingCalls());
            assertEquals(0, manager.getQueueStatus().queuedSourceBytes());
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
    void recordingHandoffEnforcesTheAggregateByteCapIndependentlyOfCallCount()
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        boolean originalDuplicateSuppression =
            preferences.getCallManagementPreference().isDuplicateRecordingSuppressionEnabled();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, null, scheduler);
        float[] sharedEightMiBBuffer = new float[2 * 1024 * 1024];
        CompletedAudioCall call = completedCall(1, true, true, List.of(sharedEightMiBBuffer));

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getCallManagementPreference().setDuplicateRecordingSuppressionEnabled(true);
            manager.start();

            for(int index = 0; index < 40; index++)
            {
                manager.receive(call);
            }

            assertEquals(32, manager.getQueueStatus().queuedCalls());
            assertEquals(256L * 1024L * 1024L, manager.getQueueStatus().queuedSourceBytes());
            assertEquals(8, manager.getQueueStatus().droppedRecordings());
            manager.stop();
            assertEquals(0, manager.getQueueStatus().queuedSourceBytes());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
            preferences.getCallManagementPreference()
                .setDuplicateRecordingSuppressionEnabled(originalDuplicateSuppression);
        }
    }

    @Test
    void channelLevelRecordingWithoutDestinationRecordStillEntersRetentionCatalog()
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AtomicInteger recorded = new AtomicInteger();
        AtomicReference<RecordedCallArtifact> cataloged = new AtomicReference<>();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, call -> recorded.incrementAndGet(),
            cataloged::set, scheduler);

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            manager.start();
            manager.receive(completedCall(1, false, false, List.of(new float[80])));
            manager.stop();
            assertEquals(1, recorded.get());
            assertNotNull(cataloged.get());
            assertFalse(cataloged.get().destinationTalkgroupRecordEnabled());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
        }
    }

    @Test
    void activeManagerKeepsWritingToItsStartupRecordingRoot() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        Path startupRoot = mTemporaryFolder.resolve("startup-recordings");
        Path changedRoot = mTemporaryFolder.resolve("changed-recordings");
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<RecordedCallArtifact> artifacts = new java.util.concurrent.CopyOnWriteArrayList<>();
        AudioRecordingManager manager =
            new AudioRecordingManager(preferences, null, artifacts::add, scheduler);

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(startupRoot);
            manager.start();
            manager.receive(completedCall(1, true, false, List.of(new float[80])));
            preferences.getDirectoryPreference().setDirectoryRecording(changedRoot);
            manager.receive(completedCall(2, true, false, List.of(new float[80])));
            manager.stop();

            assertEquals(2, artifacts.size());
            Path realStartupRoot = startupRoot.toRealPath();

            for(RecordedCallArtifact artifact: artifacts)
            {
                assertTrue(artifact.path().toRealPath().startsWith(realStartupRoot));
            }

            if(Files.exists(changedRoot))
            {
                try(var paths = Files.walk(changedRoot))
                {
                    assertEquals(0, paths.filter(Files::isRegularFile).count());
                }
            }
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
        }
    }

    @Test
    void malformedCallDoesNotBlockTheFollowingCallOrLeaveStagingWork() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AtomicInteger recorded = new AtomicInteger();
        AudioRecordingManager manager =
            new AudioRecordingManager(preferences, call -> recorded.incrementAndGet(), null, scheduler);

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            manager.start();
            manager.receive(completedCall(1, true, false, Arrays.asList(new float[80], null)));
            manager.receive(completedCall(2, true, false, List.of(new float[80])));
            manager.stop();
            assertEquals(1, recorded.get());
            assertEquals(0, manager.getQueueStatus().queuedSourceBytes());

            try(var paths = Files.walk(mTemporaryFolder))
            {
                assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp") ||
                    path.getFileName().toString().endsWith(".work") ||
                    Files.isRegularFile(path) && path.toFile().length() == 0));
            }
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
        }
    }

    @Test
    void startupPerformsOneBoundedStaleWorkCleanupBeforeAcceptingCalls() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, null, scheduler);
        Path leaf = mTemporaryFolder.resolve(Path.of("calls", "v1", "2026", "07", "23",
            "metro~0123456789ab", "_conventional", "control~abcdef012345",
            "56138-dispatch~111111111111"));
        Files.createDirectories(leaf);
        String fileName = "20260723T183000.123Z-a-k-1.wav";
        Path reservation = Files.createFile(leaf.resolve('.' + fileName + ".reserve"));
        Path work = Files.createDirectory(
            leaf.resolve(".recording-12345678-1234-4abc-8def-123456789abc.work"));
        Path staging = Files.write(work.resolve(ManagedRecordingPath.STAGING_FILE_NAME), new byte[] {1});
        FileTime stale = FileTime.from(Instant.now().minusSeconds(7_200));
        Files.setLastModifiedTime(reservation, stale);
        Files.setLastModifiedTime(staging, stale);
        Files.setLastModifiedTime(work, stale);

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            manager.start();

            assertFalse(Files.exists(reservation));
            assertFalse(Files.exists(work));
            assertTrue(manager.getQueueStatus().acceptingCalls());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
        }
    }

    @Test
    void rejectedCatalogHandoffPausesNewFilesUntilBoundedRecoverySucceeds() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        Path recordingRoot = mTemporaryFolder.resolve("recordings");
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        ControllableCatalogHandoff catalog = new ControllableCatalogHandoff();
        catalog.mAcceptNew.set(false);
        catalog.mAcceptRecovery.set(false);
        AudioRecordingManager manager =
            AudioRecordingManager.withCatalogHandoff(preferences, null, catalog, scheduler);

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(recordingRoot);
            manager.start();
            manager.receive(completedCall(1, true, false, List.of(new float[80])));
            manager.new QueueProcessor().run();

            assertTrue(manager.getQueueStatus().catalogPaused());
            assertEquals(1, manager.getQueueStatus().pendingCatalogRecoveries());
            assertEquals(1, countManagedAudioFiles(recordingRoot));

            manager.receive(completedCall(2, true, false, List.of(new float[80])));
            assertEquals(1, manager.getQueueStatus().catalogPausedRecordings());
            assertEquals(1, manager.getQueueStatus().droppedRecordings());
            assertEquals(0, manager.getQueueStatus().queuedCalls());
            assertEquals(1, countManagedAudioFiles(recordingRoot),
                "catalog backpressure must bound uncataloged audio instead of continuing to publish files");

            catalog.mAcceptRecovery.set(true);
            catalog.mAcceptNew.set(true);
            scheduler.runReconciliation();
            assertFalse(manager.getQueueStatus().catalogPaused());
            assertEquals(0, manager.getQueueStatus().pendingCatalogRecoveries());
            assertFalse(catalog.mRecovered.isEmpty());

            manager.receive(completedCall(3, true, false, List.of(new float[80])));
            manager.new QueueProcessor().run();
            assertEquals(1, catalog.mAccepted.size());
            assertEquals(2, countManagedAudioFiles(recordingRoot));
            assertTimeoutPreemptively(Duration.ofSeconds(2), manager::stop);
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
        }
    }

    @Test
    void failedCatalogDropsCompletedCallsBeforeCreatingUnownedAudio() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        Path recordingRoot = mTemporaryFolder.resolve("recordings");
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        ControllableCatalogHandoff catalog = new ControllableCatalogHandoff();
        catalog.mAccepting.set(false);
        AudioRecordingManager manager =
            AudioRecordingManager.withCatalogHandoff(preferences, null, catalog, scheduler);

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(recordingRoot);
            manager.start();
            manager.receive(completedCall(1, true, false, List.of(new float[80])));
            AudioRecordingManager.RecordingQueueStatus status = manager.getQueueStatus();
            assertTrue(status.catalogPaused());
            assertFalse(status.acceptingCalls());
            assertEquals(1, status.catalogPausedRecordings());
            assertEquals(1, status.droppedRecordings());
            assertEquals(0, status.pendingCatalogRecoveries());
            assertEquals(0, countManagedAudioFiles(recordingRoot));
            assertTimeoutPreemptively(Duration.ofSeconds(2), manager::stop);
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
        }
    }

    @Test
    void runtimeCatalogReceivesStartupRecoveryAndFinalRecordingDrainBeforeShutdown() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        ManualRecordingScheduler writerScheduler = new ManualRecordingScheduler();
        ManualRecordingScheduler runtimeScheduler = new ManualRecordingScheduler();
        AtomicReference<RecordedCallArtifact> written = new AtomicReference<>();
        AudioRecordingManager writer =
            new AudioRecordingManager(preferences, null, written::set, writerScheduler);
        RecordedCallCatalogService catalog = null;
        AudioRecordingManager runtime = null;

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getRecordPreference().setAudioRecordFormat(RecordFormat.WAVE);
            writer.start();
            writer.receive(completedCall(91, true, false, List.of(new float[80])));
            writer.stop();
            assertNotNull(written.get());
            assertTrue(Files.isRegularFile(written.get().path()));
            ManagedRecordingPath inspected =
                ManagedRecordingPath.inspect(mTemporaryFolder, written.get().path()).orElseThrow();
            RecordedCallManifest recoveredManifest =
                RecordedCallManifest.readFromAudioFile(written.get().path(), written.get().format()).orElseThrow();
            assertTrue(recoveredManifest.recordEligible());
            assertTrue(recoveredManifest.metadata().destinationTalkgroupRecordEnabled());
            assertEquals(recoveredManifest.completedAtMs(), inspected.completedAtMs());
            assertEquals(written.get().callId(), recoveredManifest.callId());
            assertNull(new RecordedCallCatalogStore(mTemporaryFolder)
                .prepareRecovered(written.get().path()).result());

            Path database = mTemporaryFolder.resolve("database/sdrtrunk.sqlite");
            SdrTrunkDatabaseStartup.createGlobalDatabase(database);
            catalog = new RecordedCallCatalogService(database, mTemporaryFolder, 30);
            catalog.start();
            runtime = AudioRecordingManager.withCatalogHandoff(preferences, null, catalog, runtimeScheduler);
            runtime.start();
            runtime.receive(completedCall(92, true, false, List.of(new float[80])));

            //This order is the application contract: finish the recording writer first, then let the catalog drain.
            runtime.stop();
            catalog.close();
            RecordedCallCatalogPage page = catalog.search(RecordedCallCatalogSearch.recent(
                written.get().startAtMs() - 1_000, System.currentTimeMillis() + 10_000, 10));
            assertEquals(2, page.calls().size(), catalog.status().toString());
            assertEquals(2, catalog.status().inserted());
        }
        finally
        {
            if(runtime != null)
            {
                runtime.stop();
            }

            if(catalog != null)
            {
                catalog.close();
            }

            writer.stop();
            runtimeScheduler.shutdownNow();
            writerScheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
            preferences.getRecordPreference().setAudioRecordFormat(originalFormat);
        }
    }

    private static CompletedAudioCall completedCall()
    {
        return completedCall(1, true, false, List.of(new float[800]));
    }

    private static CompletedAudioCall completedCall(long sequence, boolean destinationRecord, boolean duplicate,
                                                     List<float[]> audioBuffers)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(SystemConfigurationIdentifier.create("Metro / North"));
        identifiers.update(SiteConfigurationIdentifier.create("Downtown"));
        identifiers.update(ChannelNameConfigurationIdentifier.create("Control: One"));
        identifiers.update(ChannelConfigurationIdentifier.create("11111111-2222-4333-8444-555555555555"));
        identifiers.update(APCO25Talkgroup.create(56138));
        Alias alias = new Alias("Fire Dispatch");
        alias.addAliasID(new Talkgroup(Protocol.APCO25, 56138));

        if(destinationRecord)
        {
            alias.addAliasID(new Record());
        }

        AliasList aliasList = new AliasList("test");
        aliasList.addAlias(alias);
        long now = System.currentTimeMillis() + sequence;
        AudioCallSnapshot snapshot = new AudioCallSnapshot(new AudioCallId(1L, sequence, 1), null,
            aliasList,
            identifiers, Set.of(), now, now + 100, 1, 1, now, now + 100, false, true, false, true,
            100, duplicate);
        return new CompletedAudioCall(snapshot, audioBuffers);
    }

    private static long countManagedAudioFiles(Path root) throws Exception
    {
        if(!Files.exists(root))
        {
            return 0;
        }

        try(var paths = Files.walk(root))
        {
            return paths.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".wav") ||
                    path.getFileName().toString().endsWith(".mp3"))
                .count();
        }
    }

    private static class ManualRecordingScheduler extends ScheduledThreadPoolExecutor
    {
        private Runnable mReconciliation;

        ManualRecordingScheduler()
        {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)
        {
            return super.scheduleAtFixedRate(command, 1, 1, TimeUnit.DAYS);
        }

        @Override
        public ScheduledFuture<?> scheduleWithFixedDelay(Runnable command, long initialDelay, long delay,
                                                         TimeUnit unit)
        {
            mReconciliation = command;
            return super.scheduleWithFixedDelay(command, 1, 1, TimeUnit.DAYS);
        }

        void runReconciliation()
        {
            assertNotNull(mReconciliation);
            mReconciliation.run();
        }
    }

    private static class ControllableCatalogHandoff implements RecordedCallCatalogHandoff
    {
        private final AtomicBoolean mAccepting = new AtomicBoolean(true);
        private final AtomicBoolean mAcceptNew = new AtomicBoolean(true);
        private final AtomicBoolean mAcceptRecovery = new AtomicBoolean(true);
        private final List<RecordedCallArtifact> mAccepted = new CopyOnWriteArrayList<>();
        private final List<Path> mRecovered = new CopyOnWriteArrayList<>();

        @Override
        public boolean isAccepting()
        {
            return mAccepting.get();
        }

        @Override
        public boolean submit(RecordedCallArtifact artifact)
        {
            if(mAccepting.get() && mAcceptNew.get())
            {
                mAccepted.add(artifact);
                return true;
            }

            return false;
        }

        @Override
        public boolean submitRecovery(Path path)
        {
            if(mAccepting.get() && mAcceptRecovery.get())
            {
                mRecovered.add(path);
                return true;
            }

            return false;
        }
    }
}
