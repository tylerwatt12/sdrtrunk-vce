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
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteConnection;
import io.github.dsheirer.database.ActivityDatabasePath;
import io.github.dsheirer.database.ConfigurationHistoryDatabase;

/**
 * Creates consistent standalone SQLite snapshots for either an application-owned live database or an immutable
 * external migration source.
 */
public final class SqliteDatabaseSnapshot
{
    private static final int SQLITE_OK = 0;
    private static final int BUSY_TIMEOUT_MILLISECONDS = 10_000;
    private static final int COPY_BUFFER_BYTES = 64 * 1024;
    private static final BooleanSupplier NEVER_CANCELLED = () -> false;

    private SqliteDatabaseSnapshot()
    {
    }

    /**
     * Creates an online snapshot, including committed records still in WAL. SQLite may update lock bookkeeping in
     * the live source's shared-memory sidecar; use {@link #createExternal(Path, Path)} for user-selected sources.
     */
    public static void create(Path source, Path destination) throws IOException, SQLException
    {
        create(source, destination, NEVER_CANCELLED);
    }

    /** Creates an online snapshot while honoring cancellation at every application-controlled safe boundary. */
    static void create(Path source, Path destination, BooleanSupplier cancellation)
        throws IOException, SQLException
    {
        requireSourceUsable(source);
        requireUnusedDestination(destination);
        boolean paired = pairedConfiguration(source);
        Path companion = ActivityDatabasePath.forConfiguration(destination);
        if(paired)
        {
            ConfigurationHistoryDatabase.validatePairIdentity(source);
            requireUnusedDestination(companion);
        }
        List<PublishedSnapshot> published = new ArrayList<>();
        try
        {
            createSingle(source, destination, cancellation, published);
            if(paired)
            {
                createSingle(ActivityDatabasePath.forConfiguration(source), companion, cancellation, published);
                ConfigurationHistoryDatabase.validatePairIdentity(destination);
            }
        }
        catch(IOException | SQLException | RuntimeException | Error failure)
        {
            //Admission does not confer ownership: another actor may claim a destination before publication.
            for(PublishedSnapshot owned: published.reversed())
            {
                try { owned.deleteIfUnchanged(); }
                catch(IOException cleanup) { failure.addSuppressed(cleanup); }
            }
            throw failure;
        }
    }

    private static boolean pairedConfiguration(Path source) throws SQLException
    {
        SQLiteConfig options = new SQLiteConfig();
        options.setReadOnly(true);
        options.setBusyTimeout(BUSY_TIMEOUT_MILLISECONDS);
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source, options.toProperties());
            Statement statement = connection.createStatement(); var tables = statement.executeQuery(
                "SELECT 1 FROM sqlite_schema WHERE type='table' AND name='database_metadata'"))
        {
            return tables.next() && ConfigurationHistoryDatabase.isPairedConfiguration(connection);
        }
    }

    private static void createSingle(Path source, Path destination, BooleanSupplier cancellation,
                                     List<PublishedSnapshot> published)
        throws IOException, SQLException
    {
        Objects.requireNonNull(cancellation, "SQLite snapshot cancellation cannot be null");
        requireNotCancelled(cancellation);
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
            requireNotCancelled(cancellation);
            createOnline(normalizedSource, privateSnapshot, cancellation);
            makeStandalone(privateSnapshot);
            requireNotCancelled(cancellation);
            FileAccessAttributeSnapshot.restrictSensitiveFile(privateSnapshot);
            Object fileKey = Files.readAttributes(privateSnapshot, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
            requireUnusedDestination(normalizedDestination);
            requireNotCancelled(cancellation);
            Files.move(privateSnapshot, normalizedDestination);
            published.add(new PublishedSnapshot(normalizedDestination, fileKey));
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
                if(primaryFailure instanceof CancellationException)
                {
                    cleanupFailure.addSuppressed(primaryFailure);
                    throw cleanupFailure;
                }
                else if(primaryFailure != null)
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

    /** Native backup copies the WAL-mode header too; publish each owned snapshot as one standalone file. */
    private static void makeStandalone(Path snapshot) throws IOException, SQLException
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + snapshot);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA busy_timeout=" + BUSY_TIMEOUT_MILLISECONDS);
            try(var rows = statement.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)"))
            {
                if(!rows.next() || rows.getInt(1) != 0)
                    throw new IOException("The private SQLite snapshot could not be checkpointed");
            }
            try(var rows = statement.executeQuery("PRAGMA journal_mode=DELETE"))
            {
                if(!rows.next() || !"delete".equalsIgnoreCase(rows.getString(1)))
                    throw new IOException("The private SQLite snapshot could not be made standalone");
            }
        }
        for(String suffix: java.util.List.of("-wal", "-shm", "-journal"))
            if(Files.exists(Path.of(snapshot + suffix)))
                throw new IOException("The private SQLite snapshot still has an active sidecar");
    }

    /**
     * Creates a source-immutable snapshot through a stable private copy. Pending WAL or journal recovery occurs only
     * on that copy, and the source bundle is hashed before and after copying to detect concurrent changes.
     */
    public static void createExternal(Path source, Path destination) throws IOException, SQLException
    {
        createExternal(source, destination, NEVER_CANCELLED);
    }

    /**
     * Creates a source-immutable snapshot while honoring cancellation at application-controlled copy, hash, and
     * recovery boundaries. Any partial private work is removed before cancellation is returned to the caller.
     */
    public static void createExternal(Path source, Path destination, BooleanSupplier cancellation)
        throws IOException, SQLException
    {
        Objects.requireNonNull(cancellation, "SQLite snapshot cancellation cannot be null");
        requireNotCancelled(cancellation);
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

        ExternalSourceState sourceState = captureExternalSourceState(normalizedSource, cancellation);
        requireNotCancelled(cancellation);
        Path privateDirectory = Files.createTempDirectory(destinationParent, ".sqlite-source-");
        Path privateSource = privateDirectory.resolve("source.sqlite");
        Throwable primaryFailure = null;
        try
        {
            FileAccessAttributeSnapshot.restrictPrivateDirectory(privateDirectory);
            requireNotCancelled(cancellation);
            copyCapturedFile(normalizedSource, privateSource, sourceState.database(), cancellation);
            copyCapturedFile(Path.of(normalizedSource + "-journal"), Path.of(privateSource + "-journal"),
                sourceState.journal(), cancellation);
            copyCapturedFile(Path.of(normalizedSource + "-wal"), Path.of(privateSource + "-wal"),
                sourceState.wal(), cancellation);
            if(sourceState.companion() != null)
            {
                Path originalCompanion = ActivityDatabasePath.forConfiguration(normalizedSource);
                Path privateCompanion = ActivityDatabasePath.forConfiguration(privateSource);
                copyCapturedFile(originalCompanion, privateCompanion, sourceState.companion().database(), cancellation);
                copyCapturedFile(Path.of(originalCompanion + "-journal"), Path.of(privateCompanion + "-journal"),
                    sourceState.companion().journal(), cancellation);
                copyCapturedFile(Path.of(originalCompanion + "-wal"), Path.of(privateCompanion + "-wal"),
                    sourceState.companion().wal(), cancellation);
            }
            requireExternalCopyMatches(privateSource, sourceState, cancellation);
            requireExternalSourceUnchanged(normalizedSource, sourceState, cancellation);
            recoverPrivateSource(privateSource, cancellation);
            if(sourceState.companion() != null)
            {
                recoverPrivateSource(ActivityDatabasePath.forConfiguration(privateSource), cancellation);
                if(!pairedConfiguration(privateSource))
                    throw new IOException("The selected source has an unexpected activity companion");
            }
            requireNotCancelled(cancellation);
            create(privateSource, normalizedDestination, cancellation);
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
                if(primaryFailure instanceof CancellationException)
                {
                    cleanupFailure.addSuppressed(primaryFailure);
                    throw cleanupFailure;
                }
                else if(primaryFailure != null)
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

    private static void copyCapturedFile(Path source, Path destination, FileState expected,
                                         BooleanSupplier cancellation) throws IOException
    {
        if(expected.exists())
        {
            requireNotCancelled(cancellation);
            try(InputStream input = Files.newInputStream(source);
                OutputStream output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE))
            {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while((read = input.read(buffer)) >= 0)
                {
                    requireNotCancelled(cancellation);
                    if(read > 0)
                    {
                        output.write(buffer, 0, read);
                    }
                }
            }
            requireNotCancelled(cancellation);
        }
    }

    static void requireExternalCopyMatches(Path privateSource, ExternalSourceState expected) throws IOException
    {
        requireExternalCopyMatches(privateSource, expected, NEVER_CANCELLED);
    }

    private static void requireExternalCopyMatches(Path privateSource, ExternalSourceState expected,
                                                   BooleanSupplier cancellation) throws IOException
    {
        requireCopiedContent(privateSource, expected.database(), cancellation);
        requireCopiedContent(Path.of(privateSource + "-journal"), expected.journal(), cancellation);
        requireCopiedContent(Path.of(privateSource + "-wal"), expected.wal(), cancellation);
        if(expected.companion() != null)
            requireExternalCopyMatches(ActivityDatabasePath.forConfiguration(privateSource), expected.companion(),
                cancellation);
    }

    private static void requireCopiedContent(Path copied, FileState expected, BooleanSupplier cancellation)
        throws IOException
    {
        requireNotCancelled(cancellation);
        FileState actual = fileState(copied, cancellation);
        if(actual.exists() != expected.exists() || actual.size() != expected.size() ||
            !actual.sha256().equals(expected.sha256()))
        {
            throw new IOException("The selected SQLite source changed while it was being copied. Close the " +
                "previous application and try again; the incomplete snapshot was not accepted.");
        }
    }

    private static void createOnline(Path source, Path destination, BooleanSupplier cancellation)
        throws IOException, SQLException
    {
        requireNotCancelled(cancellation);
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

            requireNotCancelled(cancellation);
            connection.setAutoCommit(false);
            //Pin one WAL-aware read view. Without this, continuous reception can restart every backup step.
            try(Statement pin = connection.createStatement(); var ignored = pin.executeQuery(
                "SELECT rootpage FROM sqlite_schema LIMIT 1")) { ignored.next(); }
            int result = sqliteConnection.getDatabase().backup("main", normalizedDestination.toString(), null);

            if(result != SQLITE_OK)
            {
                throw new SQLException("SQLite backup returned status " + result + ".");
            }
            //The sqlite-jdbc backup loop does not expose a cancellable progress callback. Treat it as one safe
            //boundary and honor a request immediately after the native call returns.
            requireNotCancelled(cancellation);
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
    private static void recoverPrivateSource(Path privateSource, BooleanSupplier cancellation) throws SQLException
    {
        requireNotCancelled(cancellation);
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
        requireNotCancelled(cancellation);
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
        requireNoDestinationSidecars(destination);
    }

    private static void requireNoDestinationSidecars(Path destination) throws IOException
    {
        for(String suffix: List.of("-journal", "-wal", "-shm"))
        {
            if(Files.exists(Path.of(destination + suffix), LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Refusing to overwrite an existing SQLite snapshot sidecar: " + destination);
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
        return captureExternalSourceState(source, NEVER_CANCELLED);
    }

    private static ExternalSourceState captureExternalSourceState(Path source, BooleanSupplier cancellation)
        throws IOException
    {
        requireNotCancelled(cancellation);
        Path normalized = source.toAbsolutePath().normalize();
        Path companion = ActivityDatabasePath.forConfiguration(normalized);
        ExternalSourceState companionState = Files.exists(companion, LinkOption.NOFOLLOW_LINKS) ?
            captureSingleExternalSourceState(companion, cancellation) : null;
        ExternalSourceState single = captureSingleExternalSourceState(normalized, cancellation);
        return new ExternalSourceState(single.database(), single.journal(), single.wal(), single.sharedMemory(),
            companionState);
    }

    private static ExternalSourceState captureSingleExternalSourceState(Path normalized,
        BooleanSupplier cancellation) throws IOException
    {
        requireReadableSource(normalized);
        return new ExternalSourceState(fileState(normalized, cancellation),
            fileState(Path.of(normalized + "-journal"), cancellation),
            fileState(Path.of(normalized + "-wal"), cancellation),
            fileState(Path.of(normalized + "-shm"), cancellation), null);
    }

    static void requireExternalSourceUnchanged(Path source, ExternalSourceState expected) throws IOException
    {
        requireExternalSourceUnchanged(source, expected, NEVER_CANCELLED);
    }

    private static void requireExternalSourceUnchanged(Path source, ExternalSourceState expected,
                                                       BooleanSupplier cancellation) throws IOException
    {
        requireNotCancelled(cancellation);
        requireSourceUsable(source);
        ExternalSourceState actual = captureExternalSourceState(source, cancellation);
        if(!actual.equals(expected))
        {
            throw new IOException("The selected SQLite source changed while it was being read. Close the previous " +
                "application and try again; the incomplete snapshot was not accepted.");
        }
    }

    private static FileState fileState(Path path, BooleanSupplier cancellation) throws IOException
    {
        requireNotCancelled(cancellation);
        if(!Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        {
            return new FileState(false, 0, "", "", "");
        }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);
        return new FileState(true, attributes.size(), attributes.lastModifiedTime().toString(),
            String.valueOf(attributes.fileKey()), sha256File(path, cancellation));
    }

    /** Content digest for a standalone snapshot, independent of SQLite's matching backup bookkeeping counters. */
    static String sha256(Path path) throws IOException
    {
        return sha256(path, NEVER_CANCELLED);
    }

    /** Content digest that can stop between bounded file reads. */
    static String sha256(Path path, BooleanSupplier cancellation) throws IOException
    {
        String configurationDigest = sha256File(path, cancellation, true);
        Path companion = ActivityDatabasePath.forConfiguration(path);
        if(!Files.exists(companion, LinkOption.NOFOLLOW_LINKS)) return configurationDigest;
        String activityDigest = sha256File(companion, cancellation, true);
        try
        {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(("configuration/activity-v63\n" + configurationDigest +
                "\n" + activityDigest).getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        }
        catch(NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static String sha256File(Path path, BooleanSupplier cancellation) throws IOException
    {
        return sha256File(path, cancellation, false);
    }

    private static String sha256File(Path path, BooleanSupplier cancellation, boolean canonicalSnapshot)
        throws IOException
    {
        Objects.requireNonNull(cancellation, "SQLite digest cancellation cannot be null");
        requireNotCancelled(cancellation);
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
            if(canonicalSnapshot)
            {
                byte[] header = input.readNBytes(100);
                //Native backup and WAL-to-DELETE conversion change these two matching cache/version counters.
                //They do not describe row content. Preserve every other header byte and every database page;
                //external-source capture uses the raw digest above and never applies this normalization.
                if(header.length == 100 &&
                    "SQLite format 3\0".equals(new String(header, 0, 16,
                        java.nio.charset.StandardCharsets.US_ASCII)) &&
                    Arrays.equals(header, 24, 28, header, 92, 96))
                {
                    Arrays.fill(header, 24, 28, (byte)0);
                    Arrays.fill(header, 92, 96, (byte)0);
                }
                digest.update(header);
            }
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            int read;
            while((read = input.read(buffer)) >= 0)
            {
                requireNotCancelled(cancellation);
                if(read > 0)
                {
                    digest.update(buffer, 0, read);
                }
            }
        }
        requireNotCancelled(cancellation);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void requireNotCancelled(BooleanSupplier cancellation)
    {
        if(cancellation.getAsBoolean())
        {
            throw new CancellationException("SQLite safety review was skipped.");
        }
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

    private record PublishedSnapshot(Path path, Object fileKey)
    {
        private void deleteIfUnchanged() throws IOException
        {
            if(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return;
            Object currentKey = Files.readAttributes(path, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS).fileKey();
            if(fileKey == null || !fileKey.equals(currentKey))
                throw new IOException("Snapshot destination ownership changed; preserving " + path);
            //Standalone publication creates no sidecars. A new sidecar belongs to a different actor.
            requireNoDestinationSidecars(path);
            Files.delete(path);
        }
    }

    record ExternalSourceState(FileState database, FileState journal, FileState wal, FileState sharedMemory,
                               ExternalSourceState companion)
    {
    }

    private record FileState(boolean exists, long size, String modifiedAt, String fileKey, String sha256)
    {
    }
}
