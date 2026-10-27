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

package io.github.dsheirer.portable;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.nio.file.LinkOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

/**
 * Exclusive process lock for a portable application data root.
 *
 * <p>The lock file remains in place after this lock is closed. Ownership is represented by the operating-system file
 * lock, not by the presence of the file.</p>
 */
public final class PortableDataRootLock implements AutoCloseable
{
    public static final String LOCK_FILE_NAME = ".sdrtrunk-vce.lock";

    private Path mDataRoot;
    private Path mLockFile;
    private String mFileKey;
    private FileChannel mChannel;
    private FileLock mLock;
    private boolean mClosed;

    private PortableDataRootLock(Path dataRoot, Path lockFile, FileChannel channel, FileLock lock) throws IOException
    {
        mDataRoot = dataRoot;
        mLockFile = lockFile;
        mChannel = channel;
        mLock = lock;
        Object key = Files.readAttributes(lockFile, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        mFileKey = key == null ? null : key.toString();
    }

    public static PortableDataRootLock acquire(Path dataRoot) throws IOException
    {
        Objects.requireNonNull(dataRoot, "Portable data root cannot be null");
        Path normalizedDataRoot = dataRoot.toAbsolutePath().normalize();
        Files.createDirectories(normalizedDataRoot);
        Path lockFile = normalizedDataRoot.resolve(LOCK_FILE_NAME);
        FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);

        try
        {
            FileLock lock;

            try
            {
                lock = channel.tryLock();
            }
            catch(OverlappingFileLockException e)
            {
                throw alreadyInUse(normalizedDataRoot, e);
            }

            if(lock == null)
            {
                throw alreadyInUse(normalizedDataRoot, null);
            }

            return new PortableDataRootLock(normalizedDataRoot, lockFile, channel, lock);
        }
        catch(IOException | RuntimeException e)
        {
            try
            {
                channel.close();
            }
            catch(IOException closeFailure)
            {
                e.addSuppressed(closeFailure);
            }

            throw e;
        }
    }

    public Path getDataRoot()
    {
        return mDataRoot;
    }

    public Path getLockFile()
    {
        return mLockFile;
    }

    /** Validates an already-held lock against the exact lock file at its current profile location. */
    public synchronized void rebindMovedRoot(Path dataRoot) throws IOException
    {
        Path root = dataRoot.toAbsolutePath().normalize();
        Path file = root.resolve(LOCK_FILE_NAME);
        // Ordinary startup needs only the OS lock; require a stable identity when transfer is actually requested.
        if(mFileKey == null && !mClosed && mLock != null && mLock.isValid())
            mFileKey = PortableFileIdentity.read(mLockFile);
        if(mClosed || mLock == null || !mLock.isValid() || Files.isSymbolicLink(file) || mFileKey == null ||
            !mFileKey.equals(PortableFileIdentity.read(file)))
            throw new IOException("The transferred data folder no longer holds the original VCE lock.");
        mDataRoot = root;
        mLockFile = file;
    }

    /**
     * Renames a stopped profile without copying its contents. Windows forbids renaming a directory containing an
     * open file, so its inner lock must close for the atomic rename and then be reacquired on the exact same file.
     * Any competing Windows process opening the old lock prevents the rename; a competing destination owner
     * prevents reacquisition. Either failure stops activation and leaves the caller's durable transfer receipt.
     */
    public synchronized void moveRootAtomically(Path destination) throws IOException
    {
        rebindMovedRoot(mDataRoot);
        Path source = mDataRoot, target = destination.toAbsolutePath().normalize();
        if(!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win"))
        {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            rebindMovedRoot(target);
            return;
        }
        String directoryIdentity = PortableFileIdentity.read(source);
        if(directoryIdentity == null) throw new IOException("The VCE data folder has no reliable filesystem identity.");
        // Close before rename, including the channel: releasing only FileLock still leaves an open child handle.
        mLock.release();
        mLock = null;
        mChannel.close();
        mChannel = null;
        IOException failure = null;
        try
        {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        catch(IOException moveFailure) { failure = moveFailure; }
        try
        {
            boolean atTarget = Files.exists(target, LinkOption.NOFOLLOW_LINKS) &&
                directoryIdentity.equals(PortableFileIdentity.read(target));
            Path current = atTarget ? target : source;
            if(!directoryIdentity.equals(PortableFileIdentity.read(current)))
                throw new IOException("The VCE data folder changed during the Windows lock handoff.");
            mDataRoot = current;
            mLockFile = current.resolve(LOCK_FILE_NAME);
            if(!mFileKey.equals(PortableFileIdentity.read(mLockFile)))
                throw new IOException("The VCE lock file changed during the Windows lock handoff.");
            // Never create a replacement lock file when recovering ownership after the rename.
            FileChannel channel = FileChannel.open(mLockFile, StandardOpenOption.WRITE);
            try
            {
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch(OverlappingFileLockException competing) { throw alreadyInUse(current, competing); }
                if(lock == null) throw alreadyInUse(current, null);
                if(!mFileKey.equals(PortableFileIdentity.read(mLockFile)))
                {
                    lock.release();
                    throw new IOException("The transferred VCE lock was replaced during the Windows handoff.");
                }
                mChannel = channel;
                mLock = lock;
            }
            finally { if(mChannel != channel) channel.close(); }
        }
        catch(IOException ownershipFailure)
        {
            if(failure == null) failure = ownershipFailure;
            else failure.addSuppressed(ownershipFailure);
        }
        if(failure != null) throw failure;
    }

    @Override
    public synchronized void close() throws IOException
    {
        if(mClosed)
        {
            return;
        }

        mClosed = true;
        IOException failure = null;
        FileLock lock = mLock;
        mLock = null;

        if(lock != null && lock.isValid())
        {
            try
            {
                lock.release();
            }
            catch(IOException e)
            {
                failure = e;
            }
        }

        FileChannel channel = mChannel;
        mChannel = null;

        if(channel != null)
        {
            try
            {
                channel.close();
            }
            catch(IOException e)
            {
                if(failure == null)
                {
                    failure = e;
                }
                else
                {
                    failure.addSuppressed(e);
                }
            }
        }

        if(failure != null)
        {
            throw failure;
        }
    }

    private static IOException alreadyInUse(Path dataRoot, Exception cause)
    {
        String message = "Portable data is already in use: " + dataRoot +
            ". Close the running VCE app before continuing.";
        return cause == null ? new IOException(message) : new IOException(message, cause);
    }
}
