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

package io.github.dsheirer.database.upgrade;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HexFormat;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;

/**
 * Creates consistent standalone SQLite snapshots for either an application-owned live database or an immutable
 * external migration source.
 */
public final class SqliteDatabaseSnapshot
{
    private static final int SQLITE_OK = 0;
    private static final int BUSY_TIMEOUT_MILLISECONDS = 10_000;

    private SqliteDatabaseSnapshot()
    {
    }

    /**
     * Creates an online snapshot, including committed records still in WAL. SQLite may update lock bookkeeping in
     * the live source's shared-memory sidecar; use {@link #createExternal(Path, Path)} for user-selected sources.
     */
    public static void create(Path source, Path destination) throws IOException, SQLException
    {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        requireSourceUsable(normalizedSource);
        requireUnusedDestination(normalizedDestination);
        Path destinationParent = normalizedDestination.getParent();
        if(destinationParent == null)
        {
            throw new IOException("SQLite snapshot has no parent directory: " + normalizedDestination);
        }
        Files.createDirectories(destinationParent);

        Path privateDirectory = Files.createTempDirectory(destinationParent, ".sqlite-snapshot-");
        Path privateSnapshot = privateDirectory.resolve("snapshot.sqlite");
        Throwable primaryFailure = null;
        try
        {
            FileAccessAttributeSnapshot.restrictPrivateDirectory(privateDirectory);
            createOnline(normalizedSource, privateSnapshot);
            FileAccessAttributeSnapshot.restrictSensitiveFile(privateSnapshot);
            requireUnusedDestination(normalizedDestination);
            Files.move(privateSnapshot, normalizedDestination);
        }
        catch(IOException | SQLException | RuntimeException | Error failure)
        {
            primaryFailure = failure;
            throw failure;
        }
        finally
        {
            try
            {
                deleteTree(privateDirectory);
            }
            catch(IOException cleanupFailure)
            {
                if(primaryFailure != null)
                {
                    primaryFailure.addSuppressed(cleanupFailure);
                }
                else
                {
                    throw cleanupFailure;
                }
            }
        }
    }

    /**
     * Creates a source-immutable snapshot through a stable private copy. Pending WAL or journal recovery occurs only
     * on that copy, and the source bundle is hashed before and after copying to detect concurrent changes.
     */
    public static void createExternal(Path source, Path destination) throws IOException, SQLException
    {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        requireSourceUsable(normalizedSource);
        requireUnusedDestination(normalizedDestination);
        Path destinationParent = normalizedDestination.getParent();
        if(destinationParent == null)
        {
            throw new IOException("SQLite snapshot has no parent directory: " + normalizedDestination);
        }
        Files.createDirectories(destinationParent);

        ExternalSourceState sourceState = captureExternalSourceState(normalizedSource);
        Path privateDirectory = Files.createTempDirectory(destinationParent, ".sqlite-source-");
        Path privateSource = privateDirectory.resolve("source.sqlite");
        Throwable primaryFailure = null;
        try
        {
            FileAccessAttributeSnapshot.restrictPrivateDirectory(privateDirectory);
            copyCapturedFile(normalizedSource, privateSource, sourceState.database());
            copyCapturedFile(Path.of(normalizedSource + "-journal"), Path.of(privateSource + "-journal"),
                sourceState.journal());
            copyCapturedFile(Path.of(normalizedSource + "-wal"), Path.of(privateSource + "-wal"),
                sourceState.wal());
            requireExternalCopyMatches(privateSource, sourceState);
            requireExternalSourceUnchanged(normalizedSource, sourceState);
            recoverPrivateSource(privateSource);
            create(privateSource, normalizedDestination);
        }
        catch(IOException | SQLException | RuntimeException | Error failure)
        {
            primaryFailure = failure;
            throw failure;
        }
        finally
        {
            try
            {
                deleteTree(privateDirectory);
            }
            catch(IOException cleanupFailure)
            {
                if(primaryFailure != null)
                {
                    primaryFailure.addSuppressed(cleanupFailure);
                }
                else
                {
                    throw cleanupFailure;
                }
            }
        }
    }

    private static void copyCapturedFile(Path source, Path destination, FileState expected) throws IOException
    {
        if(expected.exists())
        {
            Files.copy(source, destination);
        }
    }

    static void requireExternalCopyMatches(Path privateSource, ExternalSourceState expected) throws IOException
    {
        requireCopiedContent(privateSource, expected.database());
        requireCopiedContent(Path.of(privateSource + "-journal"), expected.journal());
        requireCopiedContent(Path.of(privateSource + "-wal"), expected.wal());
    }

    private static void requireCopiedContent(Path copied, FileState expected) throws IOException
    {
        FileState actual = fileState(copied);
        if(actual.exists() != expected.exists() || actual.size() != expected.size() ||
            !actual.sha256().equals(expected.sha256()))
        {
            throw new IOException("The selected SQLite source changed while it was being copied. Close the " +
                "previous application and try again; the incomplete snapshot was not accepted.");
        }
    }

    private static void createOnline(Path source, Path destination)
        throws IOException, SQLException
    {
        Path normalizedSource = source.toAbsolutePath().normalize();
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        requireReadableSource(normalizedSource);
        requireUnusedDestination(normalizedDestination);

        if(normalizedDestination.getParent() == null)
        {
            throw new IOException("SQLite snapshot has no parent directory: " + normalizedDestination);
        }

        Files.createDirectories(normalizedDestination.getParent());
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLISECONDS);

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + normalizedSource,
            config.toProperties()))
        {
            if(!(connection instanceof SQLiteConnection sqliteConnection))
            {
                throw new SQLException("The configured JDBC driver is not the SQLite driver.");
            }

            int result = sqliteConnection.getDatabase().backup("main", normalizedDestination.toString(), null);

            if(result != SQLITE_OK)
            {
                throw new SQLException("SQLite backup returned status " + result + ".");
            }
        }
        catch(SQLException | RuntimeException e)
        {
            try
            {
                deleteDatabaseAndSidecars(normalizedDestination);
            }
            catch(IOException cleanupFailure)
            {
                e.addSuppressed(cleanupFailure);
            }

            throw e;
        }
    }

    /**
     * Lets SQLite recover a copied hot rollback journal and initialize any copied WAL bookkeeping. This connection is
     * deliberately writable because rollback-journal recovery cannot run through a read-only connection. The path is
     * an application-owned private copy; the selected source is never opened here.
     */
    private static void recoverPrivateSource(Path privateSource) throws SQLException
    {
        SQLiteConfig config = new SQLiteConfig();
        config.setBusyTimeout(BUSY_TIMEOUT_MILLISECONDS);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + privateSource,
            config.toProperties()); Statement statement = connection.createStatement();
            java.sql.ResultSet resultSet = statement.executeQuery("PRAGMA schema_version"))
        {
            if(!resultSet.next())
            {
                throw new SQLException("The private SQLite source copy could not be recovered safely.");
            }
        }
    }

    static void requireSourceUsable(Path source) throws IOException
    {
        Path normalized = source.toAbsolutePath().normalize();
        requireReadableSource(normalized);
    }

    private static void requireUnusedDestination(Path destination) throws IOException
    {
        if(Files.isSymbolicLink(destination) || Files.exists(destination, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("Refusing to overwrite an existing SQLite snapshot: " + destination);
        }
    }

    private static void requireReadableSource(Path normalized) throws IOException
    {
        if(Files.isSymbolicLink(normalized))
        {
            throw new IOException("Refusing to snapshot a symbolic-link SQLite database: " + normalized);
        }
        if(!Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS))
        {
            throw new IOException("SQLite database does not exist: " + normalized);
        }
        if(!Files.isReadable(normalized))
        {
            throw new IOException("SQLite database is not readable: " + normalized);
        }
        for(String suffix: java.util.List.of("-journal", "-wal", "-shm"))
        {
            Path sidecar = Path.of(normalized + suffix);
            if(Files.isSymbolicLink(sidecar))
            {
                throw new IOException("Refusing to snapshot a symbolic-link SQLite sidecar: " + sidecar);
            }
            if(Files.exists(sidecar, LinkOption.NOFOLLOW_LINKS) &&
                (!Files.isRegularFile(sidecar, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(sidecar)))
            {
                throw new IOException("SQLite sidecar is not a readable regular file: " + sidecar);
            }
        }

    }

    static ExternalSourceState captureExternalSourceState(Path source) throws IOException
    {
        Path normalized = source.toAbsolutePath().normalize();
        return new ExternalSourceState(fileState(normalized), fileState(Path.of(normalized + "-journal")),
            fileState(Path.of(normalized + "-wal")), fileState(Path.of(normalized + "-shm")));
    }

    static void requireExternalSourceUnchanged(Path source, ExternalSourceState expected) throws IOException
    {
        requireSourceUsable(source);
        ExternalSourceState actual = captureExternalSourceState(source);
        if(!actual.equals(expected))
        {
            throw new IOException("The selected SQLite source changed while it was being read. Close the previous " +
                "application and try again; the incomplete snapshot was not accepted.");
        }
    }

    private static FileState fileState(Path path) throws IOException
    {
        if(!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        {
            return new FileState(false, 0, "", "", "");
        }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        return new FileState(true, attributes.size(), attributes.lastModifiedTime().toString(),
            String.valueOf(attributes.fileKey()), sha256(path));
    }

    /** Content digest for an application-owned standalone snapshot. */
    static String sha256(Path path) throws IOException
    {
        final MessageDigest digest;
        try
        {
            digest = MessageDigest.getInstance("SHA-256");
        }
        catch(NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
        try(InputStream input = Files.newInputStream(path))
        {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while((read = input.read(buffer)) >= 0)
            {
                if(read > 0)
                {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Opens a standalone source without creating or changing SQLite sidecars beside it. */
    static Connection openImmutable(Path source) throws SQLException
    {
        SQLiteConfig config = new SQLiteConfig();
        config.setReadOnly(true);
        config.setBusyTimeout(BUSY_TIMEOUT_MILLISECONDS);
        config.enforceForeignKeys(true);
        return openImmutable(source.toAbsolutePath().normalize(), config);
    }

    private static Connection openImmutable(Path source, SQLiteConfig config) throws SQLException
    {
        String fileUri = source.toUri().toASCIIString();
        return DriverManager.getConnection("jdbc:sqlite:" + fileUri + "?immutable=1", config.toProperties());
    }

    private static void deleteDatabaseAndSidecars(Path database) throws IOException
    {
        IOException failure = null;
        for(String suffix: java.util.List.of("-journal", "-wal", "-shm", ""))
        {
            Path path = suffix.isEmpty() ? database : Path.of(database + suffix);
            try
            {
                Files.deleteIfExists(path);
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

    private static void deleteTree(Path root) throws IOException
    {
        if(!Files.exists(root, LinkOption.NOFOLLOW_LINKS))
        {
            return;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>()
        {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException
            {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException failure) throws IOException
            {
                if(failure != null)
                {
                    throw failure;
                }
                Files.delete(directory);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    record ExternalSourceState(FileState database, FileState journal, FileState wal, FileState sharedMemory)
    {
    }

    private record FileState(boolean exists, long size, String modifiedAt, String fileKey, String sha256)
    {
    }
}
