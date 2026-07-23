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

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Incrementally discovers committed recordings and removes stale writer artifacts from the exact managed-recording
 * tree.
 *
 * <p>A traversal retains only its small directory stack between batches.  Every directory entry consumes one unit
 * from the caller's budget, so a large archive cannot turn reconciliation into an unbounded scheduler task.  Unknown
 * files, unknown directories, symbolic links, legacy layouts, and paths reported active by a writer are never
 * removed.</p>
 */
final class ManagedRecordingReconciler implements AutoCloseable
{
    static final int MAXIMUM_BATCH_ENTRIES = 4096;
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};
    private final Path mRecordingRoot;
    private final Duration mStaleAfter;
    private final Clock mClock;
    private final Set<Path> mActivePaths;
    private final Deque<DirectoryFrame> mFrames = new ArrayDeque<>();
    private Path mBaseRoot;
    private Path mVersionRoot;
    private boolean mCycleActive;
    private boolean mClosed;

    ManagedRecordingReconciler(Path recordingRoot, Duration staleAfter, Set<Path> activePaths)
    {
        this(recordingRoot, staleAfter, Clock.systemUTC(), activePaths);
    }

    ManagedRecordingReconciler(Path recordingRoot, Duration staleAfter, Set<Path> activePaths, Clock clock)
    {
        this(recordingRoot, staleAfter, clock, activePaths);
    }

    ManagedRecordingReconciler(Path recordingRoot, Duration staleAfter, Clock clock, Set<Path> activePaths)
    {
        mRecordingRoot = Objects.requireNonNull(recordingRoot, "Recording root cannot be null")
            .toAbsolutePath().normalize();
        mStaleAfter = Objects.requireNonNull(staleAfter, "Stale age cannot be null");
        mClock = Objects.requireNonNull(clock, "Clock cannot be null");
        mActivePaths = Objects.requireNonNull(activePaths, "Active managed recording paths cannot be null");

        if(staleAfter.isNegative())
        {
            throw new IllegalArgumentException("Stale age cannot be negative");
        }
    }

    /**
     * Visits at most {@code maximumEntries} filesystem entries and preserves the depth-first cursor for the next call.
     */
    synchronized Batch reconcile(int maximumEntries)
    {
        ensureOpen();

        if(maximumEntries < 1 || maximumEntries > MAXIMUM_BATCH_ENTRIES)
        {
            throw new IllegalArgumentException("Batch entry limit must be between 1 and " +
                MAXIMUM_BATCH_ENTRIES);
        }

        MutableBatch batch = new MutableBatch();

        if(!mCycleActive && !startCycle(batch))
        {
            return batch.toBatch(true);
        }

        while(batch.mVisited < maximumEntries && !mFrames.isEmpty())
        {
            DirectoryFrame frame = mFrames.peek();
            Path child;

            try
            {
                if(!frame.mIterator.hasNext())
                {
                    finishFrame(batch, frame);
                    continue;
                }

                child = frame.mIterator.next();
                batch.mVisited++;
            }
            catch(DirectoryIteratorException exception)
            {
                batch.mErrors++;
                frame.mRemovable = false;
                finishFrame(batch, frame);
                continue;
            }
            catch(RuntimeException exception)
            {
                batch.mErrors++;
                frame.mRemovable = false;
                finishFrame(batch, frame);
                continue;
            }

            if(frame.mWorkDirectory)
            {
                inspectWorkEntry(batch, frame, child);
            }
            else
            {
                inspectManagedEntry(batch, frame, child);
            }
        }

        boolean complete = mFrames.isEmpty();

        if(complete)
        {
            endCycle();
        }

        return batch.toBatch(complete);
    }

    @Override
    public synchronized void close()
    {
        if(!mClosed)
        {
            closeFrames();
            endCycle();
            mClosed = true;
        }
    }

    private boolean startCycle(MutableBatch batch)
    {
        closeFrames();
        mBaseRoot = null;
        mVersionRoot = null;

        try
        {
            if(!Files.exists(mRecordingRoot, LinkOption.NOFOLLOW_LINKS))
            {
                endCycle();
                return false;
            }

            mBaseRoot = mRecordingRoot.toRealPath();
            Path managedRoot = mBaseRoot.resolve(ManagedCallRecording.MANAGED_DIRECTORY);

            if(!isRealDirectory(managedRoot))
            {
                endCycle();
                return false;
            }

            Path versionRoot = managedRoot.resolve(ManagedCallRecording.LAYOUT_VERSION);

            if(!isRealDirectory(versionRoot))
            {
                endCycle();
                return false;
            }

            mVersionRoot = versionRoot.normalize();
            mFrames.push(openFrame(mVersionRoot, 0, false));
            mCycleActive = true;
            return true;
        }
        catch(IOException | RuntimeException exception)
        {
            batch.mErrors++;
            closeFrames();
            endCycle();
            return false;
        }
    }

    private void inspectManagedEntry(MutableBatch batch, DirectoryFrame frame, Path child)
    {
        BasicFileAttributes attributes = attributes(child, batch);

        if(attributes == null || attributes.isSymbolicLink())
        {
            return;
        }

        if(frame.mDepth < 7)
        {
            if(!attributes.isDirectory())
            {
                return;
            }

            Path prefix = mVersionRoot.relativize(child.normalize());

            if(!ManagedRecordingPath.isValidDirectoryPrefix(prefix))
            {
                return;
            }

            try
            {
                mFrames.push(openFrame(child, frame.mDepth + 1, false));
            }
            catch(IOException | RuntimeException exception)
            {
                batch.mErrors++;
            }

            return;
        }

        if(attributes.isRegularFile())
        {
            inspectLeafFile(batch, child, attributes);
        }
        else if(attributes.isDirectory() && ManagedRecordingPath.isWorkDirectoryName(fileName(child)) &&
            isStale(attributes))
        {
            if(isActive(child))
            {
                batch.mActiveSkipped++;
                return;
            }

            try
            {
                mFrames.push(openFrame(child, frame.mDepth + 1, true));
            }
            catch(IOException | RuntimeException exception)
            {
                batch.mErrors++;
            }
        }
    }

    private void inspectLeafFile(MutableBatch batch, Path child, BasicFileAttributes attributes)
    {
        Path relative = mBaseRoot.relativize(child.normalize());
        Optional<ManagedRecordingPath> recording = ManagedRecordingPath.parse(relative);

        if(recording.isPresent())
        {
            if(attributes.size() > 0)
            {
                if(isActive(child))
                {
                    batch.mActiveSkipped++;
                }
                else
                {
                    batch.mRecordings.add(recording.get());
                }
            }

            return;
        }

        if(attributes.size() != 0 || ManagedRecordingPath.parseReservation(relative).isEmpty() ||
            !isStale(attributes))
        {
            return;
        }

        if(isActive(child))
        {
            batch.mActiveSkipped++;
            return;
        }

        try
        {
            if(deleteUnchangedFile(child, attributes, true))
            {
                batch.mReservationsDeleted++;
            }
        }
        catch(IOException | RuntimeException exception)
        {
            batch.mErrors++;
        }
    }

    private void inspectWorkEntry(MutableBatch batch, DirectoryFrame frame, Path child)
    {
        if(isActive(child))
        {
            batch.mActiveSkipped++;
            frame.mRemovable = false;
            return;
        }

        BasicFileAttributes attributes = attributes(child, batch);

        if(attributes == null || attributes.isSymbolicLink() || !attributes.isRegularFile() ||
            !ManagedRecordingPath.isStagingFileName(fileName(child)) || !isStale(attributes))
        {
            frame.mRemovable = false;
            return;
        }

        try
        {
            if(deleteUnchangedFile(child, attributes, false))
            {
                batch.mStagingFilesDeleted++;
            }
            else
            {
                frame.mRemovable = false;
            }
        }
        catch(IOException | RuntimeException exception)
        {
            batch.mErrors++;
            frame.mRemovable = false;
        }
    }

    private void finishFrame(MutableBatch batch, DirectoryFrame frame)
    {
        mFrames.pop();

        try
        {
            frame.close();
        }
        catch(IOException | RuntimeException exception)
        {
            batch.mErrors++;
            frame.mRemovable = false;
        }

        if(!frame.mWorkDirectory || !frame.mRemovable)
        {
            return;
        }

        if(isActive(frame.mDirectory))
        {
            batch.mActiveSkipped++;
            return;
        }

        try
        {
            BasicFileAttributes attributes =
                Files.readAttributes(frame.mDirectory, BasicFileAttributes.class, NO_FOLLOW);

            if(!attributes.isSymbolicLink() && attributes.isDirectory() &&
                Files.deleteIfExists(frame.mDirectory))
            {
                batch.mWorkDirectoriesDeleted++;
            }
        }
        catch(IOException | RuntimeException exception)
        {
            batch.mErrors++;
        }
    }

    private DirectoryFrame openFrame(Path directory, int depth, boolean workDirectory) throws IOException
    {
        BasicFileAttributes attributes =
            Files.readAttributes(directory, BasicFileAttributes.class, NO_FOLLOW);

        if(attributes.isSymbolicLink() || !attributes.isDirectory())
        {
            throw new IOException("Managed recording traversal encountered a non-directory");
        }

        DirectoryStream<Path> stream = Files.newDirectoryStream(directory);

        try
        {
            return new DirectoryFrame(directory.normalize(), depth, workDirectory, stream);
        }
        catch(RuntimeException exception)
        {
            try
            {
                stream.close();
            }
            catch(IOException closeException)
            {
                exception.addSuppressed(closeException);
            }

            throw exception;
        }
    }

    private BasicFileAttributes attributes(Path path, MutableBatch batch)
    {
        try
        {
            return Files.readAttributes(path, BasicFileAttributes.class, NO_FOLLOW);
        }
        catch(IOException | RuntimeException exception)
        {
            batch.mErrors++;
            return null;
        }
    }

    private boolean deleteUnchangedFile(Path path, BasicFileAttributes observed, boolean requireEmpty)
        throws IOException
    {
        BasicFileAttributes current =
            Files.readAttributes(path, BasicFileAttributes.class, NO_FOLLOW);

        if(current.isSymbolicLink() || !current.isRegularFile() || (requireEmpty && current.size() != 0) ||
            !Objects.equals(observed.fileKey(), current.fileKey()) ||
            !observed.lastModifiedTime().equals(current.lastModifiedTime()) ||
            observed.size() != current.size())
        {
            return false;
        }

        return Files.deleteIfExists(path);
    }

    private boolean isActive(Path candidate)
    {
        Path normalizedCandidate = candidate.toAbsolutePath().normalize();

        for(Path active : mActivePaths)
        {
            if(active == null)
            {
                continue;
            }

            Path normalizedActive = active.isAbsolute() ? active.normalize() :
                (mBaseRoot != null ? mBaseRoot : mRecordingRoot).resolve(active).normalize();

            if(active.isAbsolute() && mBaseRoot != null && normalizedActive.startsWith(mRecordingRoot))
            {
                normalizedActive = mBaseRoot.resolve(mRecordingRoot.relativize(normalizedActive)).normalize();
            }

            if(normalizedCandidate.equals(normalizedActive) ||
                normalizedCandidate.startsWith(normalizedActive) ||
                normalizedActive.startsWith(normalizedCandidate))
            {
                return true;
            }
        }

        return false;
    }

    private boolean isStale(BasicFileAttributes attributes)
    {
        Instant threshold = mClock.instant().minus(mStaleAfter);
        return !attributes.lastModifiedTime().toInstant().isAfter(threshold);
    }

    private static boolean isRealDirectory(Path path) throws IOException
    {
        if(!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        {
            return false;
        }

        BasicFileAttributes attributes =
            Files.readAttributes(path, BasicFileAttributes.class, NO_FOLLOW);
        return !attributes.isSymbolicLink() && attributes.isDirectory();
    }

    private static String fileName(Path path)
    {
        Path name = path.getFileName();
        return name != null ? name.toString() : "";
    }

    private void ensureOpen()
    {
        if(mClosed)
        {
            throw new IllegalStateException("Managed recording reconciler is closed");
        }
    }

    private void closeFrames()
    {
        while(!mFrames.isEmpty())
        {
            try
            {
                mFrames.pop().close();
            }
            catch(IOException | RuntimeException _)
            {
                //Closing is best effort; no filesystem content is changed.
            }
        }
    }

    private void endCycle()
    {
        mCycleActive = false;
        mBaseRoot = null;
        mVersionRoot = null;
    }

    static record Batch(List<ManagedRecordingPath> recordings, int visited, int reservationsDeleted,
                        int stagingFilesDeleted, int workDirectoriesDeleted, int activeSkipped, int errors,
                        boolean cycleComplete)
    {
        Batch
        {
            recordings = List.copyOf(recordings);
        }
    }

    private static final class MutableBatch
    {
        private final List<ManagedRecordingPath> mRecordings = new ArrayList<>();
        private int mVisited;
        private int mReservationsDeleted;
        private int mStagingFilesDeleted;
        private int mWorkDirectoriesDeleted;
        private int mActiveSkipped;
        private int mErrors;

        private Batch toBatch(boolean complete)
        {
            return new Batch(mRecordings, mVisited, mReservationsDeleted, mStagingFilesDeleted,
                mWorkDirectoriesDeleted, mActiveSkipped, mErrors, complete);
        }
    }

    private static final class DirectoryFrame implements AutoCloseable
    {
        private final Path mDirectory;
        private final int mDepth;
        private final boolean mWorkDirectory;
        private final DirectoryStream<Path> mStream;
        private final Iterator<Path> mIterator;
        private boolean mRemovable = true;

        private DirectoryFrame(Path directory, int depth, boolean workDirectory, DirectoryStream<Path> stream)
        {
            mDirectory = directory;
            mDepth = depth;
            mWorkDirectory = workDirectory;
            mStream = stream;
            mIterator = stream.iterator();
        }

        @Override
        public void close() throws IOException
        {
            mStream.close();
        }
    }
}
