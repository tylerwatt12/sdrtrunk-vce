/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * ****************************************************************************
 */
package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordedCallCatalogServiceTest
{
    private static final long COMPLETED = Instant.parse("2026-07-01T12:34:56.789Z").toEpochMilli();

    @TempDir
    Path mTemporaryFolder;

    @Test
    void boundedWorkerAdmitsOffThreadDrainsAndServesSearches() throws Exception
    {
        Path database = mTemporaryFolder.resolve("database/sdrtrunk.sqlite");
        Path root = mTemporaryFolder.resolve("recordings");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        RecordedCallCatalogService service = new RecordedCallCatalogService(database, root, 30);
        RecordedCallArtifact artifact = artifact(root, COMPLETED);

        assertFalse(service.submit(artifact), "a stopped service never performs caller-thread work");
        service.start();
        assertTrue(service.submit(artifact),
            "the bounded handoff must accept while SQLite validation is still starting");
        assertTrue(service.submitRecovery(artifact.path()),
            "the bounded recovery handoff must also accept while SQLite validation is still starting");
        waitFor(() -> service.status().state() == RecordedCallCatalogService.State.RUNNING);
        waitFor(() -> service.status().inserted() == 1);

        RecordedCallCatalogPage page = service.search(
            RecordedCallCatalogSearch.recent(COMPLETED - 1, COMPLETED + 1, 10));
        assertEquals(1, page.calls().size());
        assertTrue(page.calls().get(0).id().startsWith("c1_"));
        assertEquals(2_000, page.calls().get(0).durationMs());
        assertEquals(artifact.path().toRealPath(),
            service.resolveMedia(page.calls().get(0).id()).orElseThrow().toRealPath());

        try(RecordedCallCatalogService.OpenedMedia media =
                service.openMedia(page.calls().get(0).id()).orElseThrow())
        {
            assertEquals(64, media.length());
            assertEquals(RecordFormat.WAVE, media.format());
            assertEquals(64, media.channel().read(ByteBuffer.allocate(64)));
        }

        RecordedCallCatalogPage forward = service.searchForward(
            RecordedCallCatalogSearch.recent(COMPLETED - 1, COMPLETED + 1, 10), null);
        assertEquals(List.of(page.calls().get(0).id()),
            forward.calls().stream().map(RecordedCallCatalogEntry::id).toList());
        List<Optional<RecordedCallCatalogMetadata>> resolved = service.resolveCalls(List.of(
            page.calls().get(0).id(),
            RecordedCallCatalogTokens.callId(COMPLETED + 1, new AudioCallId(10, 21, 1))));
        assertEquals(page.calls().get(0).id(), resolved.get(0).orElseThrow().id());
        assertTrue(resolved.get(1).isEmpty());

        service.close();
        assertEquals(RecordedCallCatalogService.State.STOPPED, service.status().state());
        assertEquals(0, service.status().queued());
    }

    @Test
    void repeatedBoundedCleanupEventuallyRemovesExpiredFileAndRow() throws Exception
    {
        Path database = mTemporaryFolder.resolve("database/sdrtrunk.sqlite");
        Path root = mTemporaryFolder.resolve("recordings");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        AtomicLong clock = new AtomicLong(COMPLETED + TimeUnit.DAYS.toMillis(2));
        RecordedCallCatalogService service = new RecordedCallCatalogService(database, root, 1, 8,
            clock::get, 5, 1);
        RecordedCallArtifact first = artifact(root, COMPLETED, new AudioCallId(10, 20, 1));
        RecordedCallArtifact second = artifact(root, COMPLETED + 1, new AudioCallId(10, 21, 1));
        RecordedCallArtifact third = artifact(root, COMPLETED + 2, new AudioCallId(10, 22, 1));
        service.start();
        waitFor(() -> service.status().state() == RecordedCallCatalogService.State.RUNNING);
        assertTrue(service.submit(first));
        assertTrue(service.submit(second));
        assertTrue(service.submit(third));
        waitFor(() -> service.status().inserted() == 3);
        clock.addAndGet(10);
        waitFor(() -> service.status().retentionRowsDeleted() == 3);
        assertFalse(Files.exists(first.path()));
        assertFalse(Files.exists(second.path()));
        assertFalse(Files.exists(third.path()));
        assertTrue(service.search(RecordedCallCatalogSearch.recent(COMPLETED - 1, COMPLETED + 3, 10))
            .calls().isEmpty());
        service.close();
    }

    @Test
    void workerRetainsNoneligibleManagedRecordingButPublicReadsCannotResolveIt() throws Exception
    {
        Path database = mTemporaryFolder.resolve("database/sdrtrunk.sqlite");
        Path root = mTemporaryFolder.resolve("recordings");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        RecordedCallCatalogService service = new RecordedCallCatalogService(database, root, 30);
        AudioCallId callId = new AudioCallId(30, 40, 1);
        RecordedCallArtifact artifact = artifact(root, COMPLETED, callId, false);
        service.start();
        assertTrue(service.submit(artifact));
        waitFor(() -> service.status().inserted() == 1);
        assertEquals(artifact.byteSize(), service.status().retainedBytes());
        assertTrue(service.search(RecordedCallCatalogSearch.recent(COMPLETED - 1, COMPLETED + 1, 10))
            .calls().isEmpty());
        assertTrue(service.listIdentities(RecordedCallIdentityKind.SYSTEM, "", "", 10).isEmpty());
        assertTrue(service.resolveMedia(RecordedCallCatalogTokens.callId(COMPLETED, callId)).isEmpty());
        service.close();
    }

    @Test
    void runtimeReadsDoNotRepeatWholeSchemaValidation() throws Exception
    {
        Path database = mTemporaryFolder.resolve("database/sdrtrunk.sqlite");
        Path root = mTemporaryFolder.resolve("recordings");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        RecordedCallCatalogService service = new RecordedCallCatalogService(database, root, 30);

        try(Connection connection = SdrTrunkDatabase.open(database);
            Statement statement = connection.createStatement())
        {
            statement.execute("DROP INDEX " + RecordedCallCatalogSchema.DURATION_TIME_INDEX);
        }

        assertTrue(service.listIdentities(RecordedCallIdentityKind.SYSTEM, "", "", 10).isEmpty(),
            "bounded runtime reads must trust the single startup schema validation instead of rescanning sqlite_master");
        service.close();
    }

    @Test
    void workerAutomaticallyEnforcesConfiguredAudioByteCap() throws Exception
    {
        Path database = mTemporaryFolder.resolve("database/sdrtrunk.sqlite");
        Path root = mTemporaryFolder.resolve("recordings");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        AtomicLong clock = new AtomicLong(COMPLETED);
        RecordedCallCatalogService service = new RecordedCallCatalogService(database, root, 30, 100,
            8, clock::get, 5, 10);
        RecordedCallArtifact first = artifact(root, COMPLETED, new AudioCallId(20, 30, 1));
        RecordedCallArtifact second = artifact(root, COMPLETED + 1, new AudioCallId(20, 31, 1));
        RecordedCallArtifact third = artifact(root, COMPLETED + 2, new AudioCallId(20, 32, 1));
        service.start();
        waitFor(() -> service.status().state() == RecordedCallCatalogService.State.RUNNING);
        assertTrue(service.submit(first));
        assertTrue(service.submit(second));
        assertTrue(service.submit(third));
        waitFor(() -> service.status().inserted() == 3);
        clock.addAndGet(10);
        waitFor(() -> service.status().retentionRowsDeleted() >= 2);
        assertTrue(service.status().retainedBytes() <= 100);
        assertEquals(100, service.status().maximumRetainedBytes());
        assertFalse(Files.exists(first.path()));
        assertFalse(Files.exists(second.path()));
        assertTrue(Files.exists(third.path()));
        service.close();
    }

    @Test
    void invalidNewRecordingFailsClosedInsteadOfLeavingCatalogAccepting() throws Exception
    {
        Path database = mTemporaryFolder.resolve("database/sdrtrunk.sqlite");
        Path root = mTemporaryFolder.resolve("recordings");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        RecordedCallCatalogService service = new RecordedCallCatalogService(database, root, 30);
        RecordedCallArtifact artifact = artifact(root, COMPLETED);
        service.start();
        waitFor(() -> service.status().state() == RecordedCallCatalogService.State.RUNNING);
        Files.delete(artifact.path());
        assertTrue(service.submit(artifact));
        waitFor(() -> service.status().state() == RecordedCallCatalogService.State.FAILED);
        assertFalse(service.isAccepting());
        assertEquals(1, service.status().invalid());
        assertTrue(service.status().lastError().contains("retention ownership"));
        service.close();
    }

    @Test
    void unsafeOnlyCleanupPageYieldsThenContinuesFromItsCursor() throws Exception
    {
        Path database = mTemporaryFolder.resolve("database/sdrtrunk.sqlite");
        Path root = mTemporaryFolder.resolve("recordings");
        SdrTrunkDatabaseStartup.createGlobalDatabase(database);
        long secondCompletion = COMPLETED + TimeUnit.DAYS.toMillis(1);
        AtomicLong clock = new AtomicLong(secondCompletion + 1_000);
        RecordedCallCatalogService service = new RecordedCallCatalogService(database, root, 3_650, 1_024,
            8, clock::get, 1_000, 1);
        RecordedCallArtifact unsafe = artifact(root, COMPLETED, new AudioCallId(30, 40, 1));
        RecordedCallArtifact removable = artifact(root, secondCompletion, new AudioCallId(30, 41, 1));
        service.start();
        waitFor(() -> service.status().state() == RecordedCallCatalogService.State.RUNNING);
        assertTrue(service.submit(unsafe));
        assertTrue(service.submit(removable));
        waitFor(() -> service.status().inserted() == 2);

        Path managedLeaf = unsafe.path().getParent();
        Path heldLeaf = managedLeaf.resolveSibling(managedLeaf.getFileName() + "-held");
        Path outside = mTemporaryFolder.resolve("outside");
        Files.move(managedLeaf, heldLeaf);
        Files.createDirectories(outside);
        Path outsideFile = Files.write(outside.resolve(unsafe.path().getFileName()), new byte[] {9, 8, 7});

        try
        {
            Files.createSymbolicLink(managedLeaf, outside);
        }
        catch(UnsupportedOperationException | java.io.IOException exception)
        {
            service.close();
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        service.setMaximumRetainedBytes(64);
        waitFor(() -> service.status().lastError() != null &&
            service.status().lastError().contains("Unable to delete"));
        Thread.sleep(100);
        assertEquals(0, service.status().retentionRowsDeleted(),
            "an unsafe-only page must yield instead of immediately spinning");
        assertTrue(Files.exists(removable.path()));

        clock.addAndGet(1_001);
        waitFor(() -> service.status().retentionRowsDeleted() == 1);
        assertTrue(Files.exists(outsideFile));
        assertFalse(Files.exists(removable.path()));
        assertEquals(64, service.status().retainedBytes());
        service.close();
    }

    @Test
    void retentionIsExplicitlyBounded()
    {
        Path database = mTemporaryFolder.resolve("database.sqlite");
        Path root = mTemporaryFolder.resolve("recordings");
        assertThrows(IllegalArgumentException.class,
            () -> new RecordedCallCatalogService(database, root, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new RecordedCallCatalogService(database, root, 3_651));
        assertThrows(IllegalArgumentException.class,
            () -> new RecordedCallCatalogService(database, root, 30, 0));
        assertThrows(IllegalArgumentException.class,
            () -> new RecordedCallCatalogService(database, root, 30,
                RecordedCallCatalogService.MAXIMUM_RETAINED_BYTES + 1));
    }

    private static RecordedCallArtifact artifact(Path root, long completed) throws Exception
    {
        return artifact(root, completed, new AudioCallId(10, 20, 1));
    }

    private static RecordedCallArtifact artifact(Path root, long completed, AudioCallId callId) throws Exception
    {
        return artifact(root, completed, callId, true);
    }

    private static RecordedCallArtifact artifact(Path root, long completed, AudioCallId callId, boolean eligible)
        throws Exception
    {
        var date = Instant.ofEpochMilli(completed).atZone(java.time.ZoneOffset.UTC).toLocalDate();
        Path directory = Path.of("calls", "v1", "%04d".formatted(date.getYear()),
            "%02d".formatted(date.getMonthValue()), "%02d".formatted(date.getDayOfMonth()),
            "county~aaaaaaaaaaaa", "downtown~bbbbbbbbbbbb", "control~cccccccccccc",
            "56138-fire~dddddddddddd");
        Path path = root.toAbsolutePath().normalize().resolve(directory)
            .resolve(ManagedRecordingPath.fileName(callId, completed, RecordFormat.WAVE));
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[64]);
        AudioCallRecordingMetadata metadata = new AudioCallRecordingMetadata(
            "County", "county", "Downtown", "site-guid", "Control", "channel-guid", "Public Safety",
            "APCO25", "56138", "Fire", "exact:APCO25:56138", eligible, "APCO25", "1234", "Engine");
        return new RecordedCallArtifact(path, root.toAbsolutePath().normalize().relativize(path),
            RecordFormat.WAVE, 64, callId, metadata, completed - 2_000, completed, 2_000, false, eligible);
    }

    private static void waitFor(CheckedCondition condition) throws Exception
    {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

        while(System.nanoTime() < deadline)
        {
            if(condition.test())
            {
                return;
            }

            Thread.sleep(10);
        }

        assertTrue(condition.test(), "condition did not become true before timeout");
    }

    @FunctionalInterface
    private interface CheckedCondition
    {
        boolean test() throws Exception;
    }
}
