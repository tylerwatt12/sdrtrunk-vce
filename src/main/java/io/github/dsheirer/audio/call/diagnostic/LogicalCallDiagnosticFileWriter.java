/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 * *****************************************************************************
 */
package io.github.dsheirer.audio.call.diagnostic;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.function.LongFunction;

/**
 * Single-thread-owned bounded JSONL segment writer.  Serialization is supplied by the worker so this class never
 * sees unsanitized call objects.
 */
final class LogicalCallDiagnosticFileWriter implements AutoCloseable
{
    static final String ACTIVE_FILE_NAME = "logical-call-diagnostics.jsonl";
    private static final String ARCHIVE_FILE_PREFIX = "logical-call-diagnostics.";
    private static final String ARCHIVE_FILE_SUFFIX = ".jsonl";
    private static final byte NEWLINE = (byte)'\n';

    private final LogicalCallDiagnosticConfiguration mConfiguration;
    private final LongFunction<byte[]> mHeaderFactory;
    private OutputStream mOutputStream;
    private long mSegmentNumber;
    private volatile long mActiveFileBytes;
    private volatile int mRetainedFileCount;

    LogicalCallDiagnosticFileWriter(LogicalCallDiagnosticConfiguration configuration,
                                    LongFunction<byte[]> headerFactory)
    {
        mConfiguration = Objects.requireNonNull(configuration, "configuration cannot be null");
        mHeaderFactory = Objects.requireNonNull(headerFactory, "headerFactory cannot be null");
    }

    void start() throws IOException
    {
        Files.createDirectories(mConfiguration.directory());
        removeUnsafeOversizedAndExcessOwnedFiles();
        rotateOwnedFiles();
        openNextSegment();
    }

    WriteResult write(byte[] json) throws IOException
    {
        Objects.requireNonNull(json, "json cannot be null");

        if(mOutputStream == null)
        {
            throw new IOException("Diagnostic file writer has not been started");
        }

        if(json.length > mConfiguration.maximumRecordBytes())
        {
            return WriteResult.OVERSIZED;
        }

        long requiredBytes = (long)json.length + 1;

        if(mActiveFileBytes + requiredBytes > mConfiguration.maximumFileBytes())
        {
            closeCurrentSegment();
            rotateOwnedFiles();
            openNextSegment();
        }

        if(mActiveFileBytes + requiredBytes > mConfiguration.maximumFileBytes())
        {
            return WriteResult.OVERSIZED;
        }

        mOutputStream.write(json);
        mOutputStream.write(NEWLINE);
        mOutputStream.flush();
        mActiveFileBytes += requiredBytes;
        return WriteResult.WRITTEN;
    }

    long activeFileBytes()
    {
        return mActiveFileBytes;
    }

    int retainedFileCount()
    {
        return mRetainedFileCount;
    }

    private void openNextSegment() throws IOException
    {
        mSegmentNumber++;
        byte[] header = Objects.requireNonNull(mHeaderFactory.apply(mSegmentNumber),
            "session header cannot be null");

        if(header.length > mConfiguration.maximumRecordBytes() ||
            (long)header.length + 1 > mConfiguration.maximumFileBytes())
        {
            throw new IOException("Diagnostic session header exceeds configured bounds");
        }

        Path activeFile = ownedFile(0);
        mOutputStream = new BufferedOutputStream(Files.newOutputStream(activeFile, StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
        mOutputStream.write(header);
        mOutputStream.write(NEWLINE);
        mOutputStream.flush();
        mActiveFileBytes = (long)header.length + 1;
        refreshRetainedFileCount();
    }

    private void closeCurrentSegment() throws IOException
    {
        if(mOutputStream != null)
        {
            try
            {
                mOutputStream.close();
            }
            finally
            {
                mOutputStream = null;
            }
        }
    }

    private void removeUnsafeOversizedAndExcessOwnedFiles() throws IOException
    {
        for(int index = 0; index < 32; index++)
        {
            Path file = ownedFile(index);

            if(Files.isSymbolicLink(file) || (index >= mConfiguration.maximumFiles() &&
                Files.exists(file, LinkOption.NOFOLLOW_LINKS)))
            {
                Files.deleteIfExists(file);
            }
            else if(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) &&
                Files.size(file) > mConfiguration.maximumFileBytes())
            {
                Files.deleteIfExists(file);
            }
        }
    }

    private void rotateOwnedFiles() throws IOException
    {
        int lastIndex = mConfiguration.maximumFiles() - 1;
        Files.deleteIfExists(ownedFile(lastIndex));

        for(int destinationIndex = lastIndex; destinationIndex > 0; destinationIndex--)
        {
            Path source = ownedFile(destinationIndex - 1);

            if(Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS))
            {
                moveReplacing(source, ownedFile(destinationIndex));
            }
        }

        refreshRetainedFileCount();
    }

    private void refreshRetainedFileCount()
    {
        int count = 0;

        for(int index = 0; index < mConfiguration.maximumFiles(); index++)
        {
            if(Files.isRegularFile(ownedFile(index), LinkOption.NOFOLLOW_LINKS))
            {
                count++;
            }
        }

        mRetainedFileCount = count;
    }

    private Path ownedFile(int index)
    {
        return index == 0 ? mConfiguration.directory().resolve(ACTIVE_FILE_NAME) :
            mConfiguration.directory().resolve(ARCHIVE_FILE_PREFIX + index + ARCHIVE_FILE_SUFFIX);
    }

    private static void moveReplacing(Path source, Path destination) throws IOException
    {
        try
        {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch(AtomicMoveNotSupportedException exception)
        {
            Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @Override
    public void close() throws IOException
    {
        closeCurrentSegment();
    }

    enum WriteResult
    {
        WRITTEN,
        OVERSIZED
    }
}
