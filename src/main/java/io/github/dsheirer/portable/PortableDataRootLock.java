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
import java.util.Objects;

/**
 * Exclusive process lock for a portable application data root.
 *
 * <p>The lock file remains in place after this lock is closed. Ownership is represented by the operating system file
 * lock, not by the presence of the file.</p>
 */
public final class PortableDataRootLock implements AutoCloseable
{
    public static final String LOCK_FILE_NAME = ".sdrtrunk-vce.lock";

    private final Path mDataRoot;
    private final Path mLockFile;
    private FileChannel mChannel;
    private FileLock mLock;
    private boolean mClosed;

    private PortableDataRootLock(Path dataRoot, Path lockFile, FileChannel channel, FileLock lock)
    {
        mDataRoot = dataRoot;
        mLockFile = lockFile;
        mChannel = channel;
        mLock = lock;
    }

    /**
     * Acquires an exclusive lock for the supplied portable data root.
     *
     * @param dataRoot explicit portable data root
     * @return acquired lock
     * @throws IOException if the root cannot be created, its lock file cannot be opened, or another process or thread
     * already owns the data root
     */
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
            catch(IOException closeException)
            {
                e.addSuppressed(closeException);
            }

            throw e;
        }
    }

    /**
     * Portable data root protected by this lock.
     */
    public Path getDataRoot()
    {
        return mDataRoot;
    }

    /**
     * File used for the operating system lock.
     */
    public Path getLockFile()
    {
        return mLockFile;
    }

    /**
     * Releases the file lock and closes its channel. Repeated calls have no effect.
     */
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
        String message = "Portable data root is already in use: " + dataRoot +
            ". Stop the running sdrtrunk-vce instance before continuing.";
        return cause == null ? new IOException(message) : new IOException(message, cause);
    }
}
