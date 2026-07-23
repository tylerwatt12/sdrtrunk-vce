/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * ****************************************************************************
 */
package io.github.dsheirer.record;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingReconcilerTest
{
    private static final Instant NOW = Instant.parse("2026-07-24T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final Duration STALE_AFTER = Duration.ofHours(1);
    private static final FileTime STALE = FileTime.from(NOW.minus(Duration.ofHours(2)));
    private static final FileTime FRESH = FileTime.from(NOW.minus(Duration.ofMinutes(10)));

    @TempDir
    Path mTemporaryFolder;

    @Test
    void persistsADepthFirstCursorAndNeverExceedsTheEntryBudget() throws Exception
    {
        createRecording(1);
        createRecording(2);
        createRecording(3);

        try(ManagedRecordingReconciler reconciler =
                new ManagedRecordingReconciler(mTemporaryFolder, STALE_AFTER, CLOCK, Set.of()))
        {
            Totals totals = completeCycle(reconciler, 1);

            assertEquals(Set.of(relativeRecording(1), relativeRecording(2), relativeRecording(3)),
                totals.mRecordings);
            assertEquals(0, totals.mReservationsDeleted);
            assertEquals(0, totals.mStagingFilesDeleted);
            assertEquals(0, totals.mWorkDirectoriesDeleted);
            assertEquals(0, totals.mErrors);
            assertTrue(totals.mCalls > 1);
        }
    }

    @Test
    void removesOnlyStaleExactArtifactsAndSkipsActivePaths() throws Exception
    {
        Path committed = createRecording(1);
        Path staleReservation = createReservation(2, 0, STALE);
        Path activeReservation = createReservation(3, 0, STALE);
        Path nonEmptyReservation = createReservation(4, 1, STALE);
        Path invalidReservation = leaf().resolve(".not-a-managed-recording.wav.reserve");
        Files.write(invalidReservation, new byte[0]);
        Files.setLastModifiedTime(invalidReservation, STALE);

        Path staleWork = createWork(".recording-11111111-1111-4111-8111-111111111111.work",
            ManagedRecordingPath.STAGING_FILE_NAME, STALE, STALE);
        Path freshWork = createWork(".recording-22222222-2222-4222-8222-222222222222.work",
            ManagedRecordingPath.STAGING_FILE_NAME, FRESH, FRESH);
        Path unknownWork = createWork(".recording-33333333-3333-4333-8333-333333333333.work",
            "do-not-delete.txt", STALE, STALE);
        Path activeWork = createWork(".recording-44444444-4444-4444-8444-444444444444.work",
            ManagedRecordingPath.STAGING_FILE_NAME, STALE, STALE);
        Path activeStagingWork = createWork(".recording-55555555-5555-4555-8555-555555555555.work",
            ManagedRecordingPath.STAGING_FILE_NAME, STALE, STALE);
        Path activeStaging = activeStagingWork.resolve(ManagedRecordingPath.STAGING_FILE_NAME);
        Path unknownLeafFile = Files.writeString(leaf().resolve("notes.txt"), "keep");
        Path legacy = Files.writeString(mTemporaryFolder.resolve("legacy-recording.wav"), "keep");
        Path invalidTree = Files.createDirectories(
            mTemporaryFolder.resolve("calls").resolve("v1").resolve("not-a-year"));
        Path invalidTreeFile = Files.writeString(invalidTree.resolve("audio.tmp"), "keep");
        Set<Path> active = new HashSet<>();
        active.add(activeReservation.toAbsolutePath().normalize());
        active.add(activeWork.toAbsolutePath().normalize());
        active.add(activeStaging.toAbsolutePath().normalize());

        Path linkTarget = Files.createDirectories(mTemporaryFolder.resolve("outside-work"));
        Path linkedWork = leaf().resolve(".recording-66666666-6666-4666-8666-666666666666.work");
        boolean linksAvailable = createSymbolicLink(linkedWork, linkTarget);

        Totals firstPass;

        try(ManagedRecordingReconciler reconciler =
                new ManagedRecordingReconciler(mTemporaryFolder, STALE_AFTER, active, CLOCK))
        {
            firstPass = completeCycle(reconciler, 2);
            assertEquals(Set.of(relativeRecording(1)), firstPass.mRecordings);
            assertEquals(1, firstPass.mReservationsDeleted);
            assertEquals(1, firstPass.mStagingFilesDeleted);
            assertEquals(1, firstPass.mWorkDirectoriesDeleted);
            assertTrue(firstPass.mActiveSkipped >= 3);
            assertEquals(0, firstPass.mErrors);

            assertFalse(Files.exists(staleReservation));
            assertFalse(Files.exists(staleWork));
            assertTrue(Files.exists(activeReservation));
            assertTrue(Files.exists(nonEmptyReservation));
            assertTrue(Files.exists(invalidReservation));
            assertTrue(Files.exists(freshWork));
            assertTrue(Files.exists(unknownWork));
            assertTrue(Files.exists(activeWork));
            assertTrue(Files.exists(activeStagingWork));
            assertTrue(Files.exists(unknownLeafFile));
            assertTrue(Files.exists(legacy));
            assertTrue(Files.exists(invalidTreeFile));
            assertTrue(Files.exists(committed));

            if(linksAvailable)
            {
                assertTrue(Files.isSymbolicLink(linkedWork));
                assertTrue(Files.exists(linkTarget));
            }

            active.clear();
            Totals secondPass = completeCycle(reconciler, 3);
            assertEquals(1, secondPass.mReservationsDeleted);
            assertEquals(2, secondPass.mStagingFilesDeleted);
            assertEquals(2, secondPass.mWorkDirectoriesDeleted);
            assertEquals(0, secondPass.mErrors);
            assertFalse(Files.exists(activeReservation));
            assertFalse(Files.exists(activeWork));
            assertFalse(Files.exists(activeStagingWork));
            assertTrue(Files.exists(unknownWork));
        }
    }

    @Test
    void neverFollowsASymbolicLinkIntoTheManagedRoot() throws Exception
    {
        Path outside = Files.createDirectories(mTemporaryFolder.resolve("outside").resolve("v1"));
        Path marker = Files.writeString(outside.resolve("marker"), "keep");
        Path calls = mTemporaryFolder.resolve("calls");

        if(!createSymbolicLink(calls, outside.getParent()))
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable for this filesystem");
            return;
        }

        try(ManagedRecordingReconciler reconciler =
                new ManagedRecordingReconciler(mTemporaryFolder, Duration.ZERO, CLOCK, Set.of()))
        {
            ManagedRecordingReconciler.Batch batch = reconciler.reconcile(10);
            assertTrue(batch.cycleComplete());
            assertTrue(batch.recordings().isEmpty());
            assertEquals(0, batch.visited());
            assertEquals(0, batch.errors());
        }

        assertTrue(Files.exists(marker));
    }

    @Test
    void validatesBatchBoundsAndCannotRunAfterClose()
    {
        ManagedRecordingReconciler reconciler =
            new ManagedRecordingReconciler(mTemporaryFolder, STALE_AFTER, CLOCK, Set.of());

        assertThrows(IllegalArgumentException.class, () -> reconciler.reconcile(0));
        assertThrows(IllegalArgumentException.class,
            () -> reconciler.reconcile(ManagedRecordingReconciler.MAXIMUM_BATCH_ENTRIES + 1));
        reconciler.close();
        assertThrows(IllegalStateException.class, () -> reconciler.reconcile(1));
        reconciler.close();
    }

    private Path createRecording(int identity) throws IOException
    {
        Path path = mTemporaryFolder.resolve(relativeRecording(identity));
        Files.createDirectories(path.getParent());
        return Files.write(path, new byte[] {(byte)identity});
    }

    private Path createReservation(int identity, int size, FileTime modified) throws IOException
    {
        Path canonical = mTemporaryFolder.resolve(relativeRecording(identity));
        Files.createDirectories(canonical.getParent());
        Path reservation = canonical.resolveSibling('.' + canonical.getFileName().toString() + ".reserve");
        Files.write(reservation, new byte[size]);
        Files.setLastModifiedTime(reservation, modified);
        return reservation;
    }

    private Path createWork(String name, String childName, FileTime childModified, FileTime directoryModified)
        throws IOException
    {
        Path work = Files.createDirectories(leaf().resolve(name));
        Path child = Files.write(work.resolve(childName), new byte[] {1});
        Files.setLastModifiedTime(child, childModified);
        Files.setLastModifiedTime(work, directoryModified);
        return work;
    }

    private Path leaf() throws IOException
    {
        Path leaf = mTemporaryFolder.resolve(relativeRecording(1)).getParent();
        Files.createDirectories(leaf);
        return leaf;
    }

    private static Path relativeRecording(int identity)
    {
        return Path.of("calls", "v1", "2026", "07", "23", "metro~0123456789ab",
            "_conventional", "control~abcdef012345", "56138-dispatch~111111111111",
            "20260723T183000.123Z-a-" + Integer.toString(identity, 36) + "-1.wav");
    }

    private static boolean createSymbolicLink(Path link, Path target)
    {
        try
        {
            Files.createSymbolicLink(link, target);
            return true;
        }
        catch(UnsupportedOperationException | IOException | SecurityException exception)
        {
            return false;
        }
    }

    private static Totals completeCycle(ManagedRecordingReconciler reconciler, int budget)
    {
        Totals totals = new Totals();

        for(int call = 0; call < 200; call++)
        {
            ManagedRecordingReconciler.Batch batch = reconciler.reconcile(budget);
            assertTrue(batch.visited() <= budget);
            totals.add(batch);

            if(batch.cycleComplete())
            {
                return totals;
            }
        }

        throw new AssertionError("Reconciliation did not complete within the bounded test tree");
    }

    private static final class Totals
    {
        private final Set<Path> mRecordings = new LinkedHashSet<>();
        private int mReservationsDeleted;
        private int mStagingFilesDeleted;
        private int mWorkDirectoriesDeleted;
        private int mActiveSkipped;
        private int mErrors;
        private int mCalls;

        private void add(ManagedRecordingReconciler.Batch batch)
        {
            for(ManagedRecordingPath recording : batch.recordings())
            {
                mRecordings.add(recording.relativePath());
            }

            mReservationsDeleted += batch.reservationsDeleted();
            mStagingFilesDeleted += batch.stagingFilesDeleted();
            mWorkDirectoriesDeleted += batch.workDirectoriesDeleted();
            mActiveSkipped += batch.activeSkipped();
            mErrors += batch.errors();
            mCalls++;
        }
    }
}
