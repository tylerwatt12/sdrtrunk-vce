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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.EnumSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("platform-filesystem")
class SqliteDatabaseSnapshotTest
{
    @TempDir
    Path mTemporaryFolder;

    @Test
    void snapshotsAndApprovalsIncludeTheActivityCompanion() throws Exception
    {
        Path source = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        Path copy = mTemporaryFolder.resolve("copied.sqlite");
        Path externalCopy = mTemporaryFolder.resolve("external.sqlite");
        io.github.dsheirer.database.SdrTrunkDatabaseStartup.createGlobalDatabase(source);
        SqliteDatabaseSnapshot.create(source, copy);
        io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePair(copy);
        for(Path member: java.util.List.of(copy,io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(copy)))
        {
            for(String suffix: java.util.List.of("-wal","-shm","-journal"))
                assertFalse(Files.exists(Path.of(member + suffix)), "validated snapshots are standalone files");
        }
        String before = SqliteDatabaseSnapshot.sha256(source);
        try(var history = io.github.dsheirer.database.SdrTrunkDatabase.openHistory(source))
        {
            history.createStatement().execute("INSERT INTO receiver_channel(configuration_id,first_seen_ms,last_seen_ms) " +
                "VALUES('123e4567-e89b-42d3-a456-426614174008',1,1)");
        }
        assertFalse(before.equals(SqliteDatabaseSnapshot.sha256(source)), "approval binds retained history too");
        SqliteDatabaseSnapshot.createExternal(source, externalCopy);
        io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePair(externalCopy);
        assertEquals("1", scalar(io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(externalCopy),
            "SELECT count(*) FROM receiver_channel"));
        assertEquals("0", scalar(io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(copy),
            "SELECT count(*) FROM receiver_channel"));
    }

    @Test
    void cancellationBetweenPairMembersRemovesOnlyOwnedDestinations() throws Exception
    {
        Path source = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        Path copy = mTemporaryFolder.resolve("cancelled.sqlite");
        io.github.dsheirer.database.SdrTrunkDatabaseStartup.createGlobalDatabase(source);
        assertThrows(CancellationException.class,
            () -> SqliteDatabaseSnapshot.create(source, copy, () -> Files.exists(copy)));
        assertFalse(Files.exists(copy));
        assertFalse(Files.exists(io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(copy)));
        io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePair(source);
    }

    @Test
    void aPrivateIdentityAnchorRemovesAnOwnedSnapshotWhenFileKeysAreUnavailable() throws Exception
    {
        Path copy = Files.writeString(mTemporaryFolder.resolve("owned.sqlite"), "snapshot");
        Path privateDirectory = Files.createTempDirectory(mTemporaryFolder, ".sqlite-snapshot-");
        Path anchor = Files.createLink(privateDirectory.resolve("identity.sqlite"), copy);
        var published = new SqliteDatabaseSnapshot.PublishedSnapshot(copy, null, anchor);
        published.deleteIfUnchanged();
        assertFalse(Files.exists(copy));
        assertTrue(Files.exists(anchor));
        published.releaseIdentity();
        assertFalse(Files.exists(privateDirectory));
    }

    @Test
    void aPrivateIdentityAnchorPreservesAReplacementEvenWithIdenticalContents() throws Exception
    {
        Path copy = Files.writeString(mTemporaryFolder.resolve("replaced-with-identical.sqlite"), "same contents");
        Path privateDirectory = Files.createTempDirectory(mTemporaryFolder, ".sqlite-snapshot-");
        Path anchor = Files.createLink(privateDirectory.resolve("identity.sqlite"), copy);
        var published = new SqliteDatabaseSnapshot.PublishedSnapshot(copy, null, anchor);
        Path replacement = Files.writeString(mTemporaryFolder.resolve("replacement.sqlite"), "same contents");
        Files.move(replacement, copy, StandardCopyOption.REPLACE_EXISTING);
        assertThrows(IOException.class, published::deleteIfUnchanged);
        assertEquals("same contents", Files.readString(copy));
        published.releaseIdentity();
        assertFalse(Files.exists(privateDirectory));
        assertTrue(Files.exists(copy));
    }

    @Test
    void aPrivateIdentityAnchorDoesNotGrantOwnershipOfANewSidecar() throws Exception
    {
        Path copy = Files.writeString(mTemporaryFolder.resolve("sidecar-owned.sqlite"), "snapshot");
        Path privateDirectory = Files.createTempDirectory(mTemporaryFolder, ".sqlite-snapshot-");
        Path anchor = Files.createLink(privateDirectory.resolve("identity.sqlite"), copy);
        var published = new SqliteDatabaseSnapshot.PublishedSnapshot(copy, null, anchor);
        Path sidecar = Files.writeString(Path.of(copy + "-wal"), "foreign sidecar");
        assertThrows(IOException.class, published::deleteIfUnchanged);
        published.releaseIdentity();
        assertEquals("snapshot", Files.readString(copy));
        assertEquals("foreign sidecar", Files.readString(sidecar));
    }

    @Test
    void repeatedCanonicalSnapshotsHaveTheSameApprovalDigest() throws Exception
    {
        Path source = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        Path first = mTemporaryFolder.resolve("first.sqlite");
        Path second = mTemporaryFolder.resolve("second.sqlite");
        Path independent = mTemporaryFolder.resolve("independent.sqlite");
        io.github.dsheirer.database.SdrTrunkDatabaseStartup.createGlobalDatabase(source);
        SqliteDatabaseSnapshot.create(source, first);
        SqliteDatabaseSnapshot.create(first, second);
        SqliteDatabaseSnapshot.createExternal(source, independent);
        assertEquals(SqliteDatabaseSnapshot.sha256(first), SqliteDatabaseSnapshot.sha256(second),
            () -> byteDifferences(first, second));
        assertEquals(SqliteDatabaseSnapshot.sha256(first), SqliteDatabaseSnapshot.sha256(independent),
            () -> byteDifferences(first, independent));
    }

    @Test
    void approvalDigestsStillDetectConfigurationHistoryAndOtherHeaderChanges() throws Exception
    {
        Path source = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        Path first = mTemporaryFolder.resolve("first.sqlite");
        Path renamed = mTemporaryFolder.resolve("renamed.sqlite");
        Path observed = mTemporaryFolder.resolve("observed.sqlite");
        io.github.dsheirer.database.SdrTrunkDatabaseStartup.createGlobalDatabase(source);
        SqliteDatabaseSnapshot.create(source, first);
        try(Connection configuration = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = configuration.createStatement())
        {
            statement.executeUpdate("UPDATE alias_list SET name='Changed configuration' " +
                "WHERE id=(SELECT min(id) FROM alias_list)");
        }
        SqliteDatabaseSnapshot.create(source, renamed);
        assertFalse(SqliteDatabaseSnapshot.sha256(first).equals(SqliteDatabaseSnapshot.sha256(renamed)));
        try(var history = io.github.dsheirer.database.SdrTrunkDatabase.openHistory(source))
        {
            history.createStatement().executeUpdate("INSERT INTO receiver_channel(configuration_id,first_seen_ms,last_seen_ms) " +
                "VALUES('123e4567-e89b-42d3-a456-426614174008',1,1)");
        }
        SqliteDatabaseSnapshot.create(source, observed);
        assertFalse(SqliteDatabaseSnapshot.sha256(renamed).equals(SqliteDatabaseSnapshot.sha256(observed)));
        String beforeHeaderChange = SqliteDatabaseSnapshot.sha256(observed);
        try(Connection configuration = DriverManager.getConnection("jdbc:sqlite:" + observed);
            Statement statement = configuration.createStatement())
        {
            statement.execute("PRAGMA user_version=17");
        }
        assertFalse(beforeHeaderChange.equals(SqliteDatabaseSnapshot.sha256(observed)),
            "Only the proven bookkeeping counters are normalized");
    }

    @Test
    void externalSourceMutationDetectionKeepsExactBookkeepingBytes() throws Exception
    {
        Path source = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        io.github.dsheirer.database.SdrTrunkDatabaseStartup.createGlobalDatabase(source);
        SqliteDatabaseSnapshot.ExternalSourceState before = SqliteDatabaseSnapshot.captureExternalSourceState(source);
        FileTime modified = Files.getLastModifiedTime(source);
        try(RandomAccessFile bytes = new RandomAccessFile(source.toFile(), "rw"))
        {
            bytes.seek(24); int counter = bytes.readInt();
            bytes.seek(24); bytes.writeInt(counter + 1);
            bytes.seek(92); bytes.writeInt(counter + 1);
        }
        Files.setLastModifiedTime(source, modified);
        assertThrows(IOException.class, () -> SqliteDatabaseSnapshot.requireExternalSourceUnchanged(source, before));
    }

    private static String byteDifferences(Path first, Path second)
    {
        StringBuilder differences = new StringBuilder();
        for(boolean companion: new boolean[]{false, true})
        {
            Path left = companion ? io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(first) : first;
            Path right = companion ? io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(second) : second;
            try
            {
                byte[] original = Files.readAllBytes(left);
                byte[] copy = Files.readAllBytes(right);
                differences.append(companion ? " history" : " configuration").append(" sizes=")
                    .append(original.length).append('/').append(copy.length);
                for(int offset = 0, count = 0; offset < Math.min(original.length, copy.length); offset++)
                {
                    if(original[offset] != copy[offset] && count++ < 20)
                        differences.append(" offset=").append(offset).append(':')
                            .append(Byte.toUnsignedInt(original[offset])).append('/')
                            .append(Byte.toUnsignedInt(copy[offset]));
                }
            }
            catch(IOException failure) { throw new UncheckedIOException(failure); }
        }
        return differences.toString();
    }

    @Test
    void aCompanionClaimedBetweenMembersIsNeverRemoved() throws Exception
    {
        Path source = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        Path copy = mTemporaryFolder.resolve("claimed.sqlite");
        Path companion = io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(copy);
        io.github.dsheirer.database.SdrTrunkDatabaseStartup.createGlobalDatabase(source);
        AtomicBoolean claimed = new AtomicBoolean();
        BooleanSupplier cancellation = () -> {
            if(Files.exists(copy) && claimed.compareAndSet(false, true))
            {
                try { Files.writeString(companion, "foreign companion"); }
                catch(IOException failure) { throw new UncheckedIOException(failure); }
            }
            return false;
        };
        assertThrows(IOException.class, () -> SqliteDatabaseSnapshot.create(source, copy, cancellation));
        assertTrue(claimed.get());
        assertFalse(Files.exists(copy), "The first member is owned by this failed snapshot");
        assertEquals("foreign companion", Files.readString(companion));
        io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePair(source);
    }

    @Test
    void aPublishedMemberReplacedBeforeCancellationIsPreserved() throws Exception
    {
        Path source = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        Path copy = mTemporaryFolder.resolve("replaced.sqlite");
        Path replacement = mTemporaryFolder.resolve("foreign.sqlite");
        Files.writeString(replacement, "foreign replacement");
        io.github.dsheirer.database.SdrTrunkDatabaseStartup.createGlobalDatabase(source);
        AtomicBoolean replaced = new AtomicBoolean();
        BooleanSupplier cancellation = () -> {
            if(Files.exists(copy) && replaced.compareAndSet(false, true))
            {
                try { Files.move(replacement, copy, StandardCopyOption.REPLACE_EXISTING); }
                catch(IOException failure) { throw new UncheckedIOException(failure); }
                return true;
            }
            return false;
        };
        CancellationException failure = assertThrows(CancellationException.class,
            () -> SqliteDatabaseSnapshot.create(source, copy, cancellation));
        assertTrue(replaced.get());
        assertEquals("foreign replacement", Files.readString(copy));
        assertEquals(1, failure.getSuppressed().length, "Changed ownership makes cleanup fail closed");
        assertFalse(Files.exists(io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(copy)));
        io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePair(source);
    }

    @Test
    void destinationSidecarsOfEitherPairMemberAreRejectedAndPreserved() throws Exception
    {
        Path source = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        io.github.dsheirer.database.SdrTrunkDatabaseStartup.createGlobalDatabase(source);
        for(boolean companion: new boolean[]{false, true})
        {
            for(String suffix: java.util.List.of("-wal", "-shm", "-journal"))
            {
                Path copy = mTemporaryFolder.resolve("occupied-" + companion + suffix + ".sqlite");
                Path activity = io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(copy);
                Path sidecar = Path.of((companion ? activity : copy) + suffix);
                Files.writeString(sidecar, "foreign sidecar");
                assertThrows(IOException.class, () -> SqliteDatabaseSnapshot.create(source, copy));
                assertEquals("foreign sidecar", Files.readString(sidecar));
                assertFalse(Files.exists(copy));
                assertFalse(Files.exists(activity));
            }
        }
        io.github.dsheirer.database.ConfigurationHistoryDatabase.validatePair(source);
    }

    @Test
    void aSidecarCreatedAfterPublicationIsPreservedWithItsDatabase() throws Exception
    {
        Path source = mTemporaryFolder.resolve("sdrtrunk.sqlite");
        Path copy = mTemporaryFolder.resolve("opened.sqlite");
        Path sidecar = Path.of(copy + "-wal");
        io.github.dsheirer.database.SdrTrunkDatabaseStartup.createGlobalDatabase(source);
        BooleanSupplier cancellation = () -> {
            if(!Files.exists(copy)) return false;
            try { Files.writeString(sidecar, "foreign sidecar"); }
            catch(IOException failure) { throw new UncheckedIOException(failure); }
            return true;
        };
        CancellationException failure = assertThrows(CancellationException.class,
            () -> SqliteDatabaseSnapshot.create(source, copy, cancellation));
        assertTrue(Files.exists(copy));
        assertEquals("foreign sidecar", Files.readString(sidecar));
        assertEquals(1, failure.getSuppressed().length, "An externally opened snapshot cannot be cleaned up");
        assertFalse(Files.exists(io.github.dsheirer.database.ActivityDatabasePath.forConfiguration(copy)));
    }

    @Test
    void onlineSnapshotCapturesCommittedRowsThatHaveNotBeenCheckpointedFromWal() throws Exception
    {
        Path source = mTemporaryFolder.resolve("source.sqlite");
        Path snapshot = mTemporaryFolder.resolve("snapshots").resolve("snapshot.sqlite");

        try(Connection writer = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = writer.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.executeUpdate("CREATE TABLE event(id INTEGER PRIMARY KEY, name TEXT NOT NULL)");
            statement.executeUpdate("INSERT INTO event(name) VALUES ('committed-in-wal')");

            Path wal = Path.of(source + "-wal");
            assertTrue(Files.isRegularFile(wal));
            assertTrue(Files.size(wal) > 0);

            SqliteDatabaseSnapshot.create(source, snapshot);
            assertTrue(Files.size(wal) > 0);
        }

        assertEquals("committed-in-wal", scalar(snapshot, "SELECT name FROM event WHERE id=1"));
        assertEquals("ok", scalar(snapshot, "PRAGMA quick_check"));
    }

    @Test
    void refusesToOverwriteAnExistingSnapshot() throws Exception
    {
        Path source = mTemporaryFolder.resolve("source.sqlite");
        Path destination = mTemporaryFolder.resolve("existing.sqlite");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE sample(value TEXT)");
        }

        Files.writeString(destination, "do-not-replace");
        IOException exception = assertThrows(IOException.class,
            () -> SqliteDatabaseSnapshot.createExternal(source, destination));

        assertTrue(exception.getMessage().contains("Refusing to overwrite"));
        assertEquals("do-not-replace", Files.readString(destination));
    }

    @Test
    void refusesToReplaceADanglingDestinationSymbolicLink() throws Exception
    {
        Path source = mTemporaryFolder.resolve("dangling-source.sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE sample(value TEXT)");
        }

        Path destination = mTemporaryFolder.resolve("dangling-destination.sqlite");
        try
        {
            Files.createSymbolicLink(destination, Path.of("missing-target.sqlite"));
        }
        catch(UnsupportedOperationException | IOException | SecurityException e)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }

        IOException exception = assertThrows(IOException.class,
            () -> SqliteDatabaseSnapshot.createExternal(source, destination));
        assertTrue(exception.getMessage().contains("Refusing to overwrite"));
        assertTrue(Files.isSymbolicLink(destination));
    }

    @Test
    void snapshotsReadOnlyDatabaseFromReadOnlyFolderWithoutChangingSource() throws Exception
    {
        Path sourceFolder = mTemporaryFolder.resolve("read-only-source");
        Files.createDirectory(sourceFolder);
        Path source = sourceFolder.resolve("source.sqlite");
        Path destination = mTemporaryFolder.resolve("read-only-source-snapshot.sqlite");

        Assumptions.assumeTrue(Files.getFileStore(sourceFolder)
            .supportsFileAttributeView(PosixFileAttributeView.class), "POSIX permissions are unavailable");

        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = connection.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.executeUpdate("CREATE TABLE sample(value TEXT NOT NULL)");
            statement.executeUpdate("INSERT INTO sample(value) VALUES ('preserved')");
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }
        Files.deleteIfExists(Path.of(source + "-wal"));
        Files.deleteIfExists(Path.of(source + "-shm"));

        byte[] originalBytes = Files.readAllBytes(source);
        FileTime originalModifiedTime = Files.getLastModifiedTime(source);

        try
        {
            Files.setPosixFilePermissions(source, EnumSet.of(PosixFilePermission.OWNER_READ));
            Files.setPosixFilePermissions(sourceFolder, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_EXECUTE));

            SqliteDatabaseSnapshot.createExternal(source, destination);

            assertEquals("preserved", scalar(destination, "SELECT value FROM sample"));
            assertArrayEquals(originalBytes, Files.readAllBytes(source));
            assertEquals(originalModifiedTime, Files.getLastModifiedTime(source));
            assertFalse(Files.exists(Path.of(source + "-journal")));
            assertFalse(Files.exists(Path.of(source + "-wal")));
            assertFalse(Files.exists(Path.of(source + "-shm")));
        }
        finally
        {
            Files.setPosixFilePermissions(sourceFolder, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE));
            Files.setPosixFilePermissions(source, EnumSet.of(PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE));
        }
    }

    @Test
    void externalSnapshotIncludesWalWithoutChangingSourceOrSidecars() throws Exception
    {
        Path source = mTemporaryFolder.resolve("external-wal.sqlite");
        Path destination = mTemporaryFolder.resolve("external-wal-snapshot.sqlite");
        try(Connection writer = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = writer.createStatement())
        {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA wal_autocheckpoint=0");
            statement.executeUpdate("CREATE TABLE sample(value TEXT)");
            statement.executeUpdate("INSERT INTO sample(value) VALUES ('from-wal')");

            Path wal = Path.of(source + "-wal");
            Path sharedMemory = Path.of(source + "-shm");
            byte[] databaseBytes = Files.readAllBytes(source);
            byte[] walBytes = Files.readAllBytes(wal);
            byte[] sharedMemoryBytes = Files.readAllBytes(sharedMemory);
            FileTime databaseModified = Files.getLastModifiedTime(source);
            FileTime walModified = Files.getLastModifiedTime(wal);
            FileTime sharedMemoryModified = Files.getLastModifiedTime(sharedMemory);

            SqliteDatabaseSnapshot.createExternal(source, destination);

            assertEquals("from-wal", scalar(destination, "SELECT value FROM sample"));
            assertArrayEquals(databaseBytes, Files.readAllBytes(source));
            assertArrayEquals(walBytes, Files.readAllBytes(wal));
            assertArrayEquals(sharedMemoryBytes, Files.readAllBytes(sharedMemory));
            assertEquals(databaseModified, Files.getLastModifiedTime(source));
            assertEquals(walModified, Files.getLastModifiedTime(wal));
            assertEquals(sharedMemoryModified, Files.getLastModifiedTime(sharedMemory));
        }
    }

    @Test
    void externalSnapshotRecoversPrivateRollbackJournalWithoutChangingSource() throws Exception
    {
        Path source = mTemporaryFolder.resolve("active-journal.sqlite");
        Path destination = mTemporaryFolder.resolve("journal-destination.sqlite");
        try(Connection writer = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = writer.createStatement())
        {
            statement.executeUpdate("CREATE TABLE sample(value TEXT)");
            writer.setAutoCommit(false);
            statement.executeUpdate("INSERT INTO sample(value) VALUES ('uncommitted')");
            Path journal = Path.of(source + "-journal");
            assertTrue(Files.size(journal) > 0);
            byte[] databaseBytes = Files.readAllBytes(source);
            byte[] journalBytes = Files.readAllBytes(journal);
            FileTime databaseModified = Files.getLastModifiedTime(source);
            FileTime journalModified = Files.getLastModifiedTime(journal);

            SqliteDatabaseSnapshot.createExternal(source, destination);

            assertEquals("0", scalar(destination, "SELECT COUNT(*) FROM sample"));
            assertArrayEquals(databaseBytes, Files.readAllBytes(source));
            assertArrayEquals(journalBytes, Files.readAllBytes(journal));
            assertEquals(databaseModified, Files.getLastModifiedTime(source));
            assertEquals(journalModified, Files.getLastModifiedTime(journal));
            writer.rollback();
        }
    }

    @Test
    void failedSnapshotRemovesTheDatabaseAndEverySidecar() throws Exception
    {
        Path source = mTemporaryFolder.resolve("invalid.sqlite");
        Path destination = mTemporaryFolder.resolve("failed-snapshot.sqlite");
        Files.writeString(source, "not a SQLite database");

        assertThrows(SQLException.class, () -> SqliteDatabaseSnapshot.createExternal(source, destination));

        for(String suffix: java.util.List.of("", "-journal", "-wal", "-shm"))
        {
            assertFalse(Files.exists(suffix.isEmpty() ? destination : Path.of(destination + suffix)));
        }
    }

    @Test
    void cancellableDigestStopsBetweenBoundedReads() throws Exception
    {
        Path source = mTemporaryFolder.resolve("digest-source.bin");
        Files.write(source, new byte[512 * 1024]);
        AtomicInteger checks = new AtomicInteger();

        assertThrows(CancellationException.class,
            () -> SqliteDatabaseSnapshot.sha256(source, () -> checks.incrementAndGet() > 4));

        assertTrue(checks.get() > 4);
        assertEquals(512 * 1024, Files.size(source));
    }

    @Test
    void cancellationDuringChunkedExternalCopyRemovesPrivateWorkAndLeavesSourceUnchanged() throws Exception
    {
        Path source = mTemporaryFolder.resolve("cancellable-source.sqlite");
        Path destination = mTemporaryFolder.resolve("cancellable-destination.sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE sample(payload BLOB NOT NULL)");
            statement.executeUpdate("INSERT INTO sample(payload) VALUES (zeroblob(2097152))");
        }
        byte[] sourceBytes = Files.readAllBytes(source);
        BooleanSupplier cancellation = () -> hasPartialPrivateCopy(mTemporaryFolder);

        assertThrows(CancellationException.class,
            () -> SqliteDatabaseSnapshot.createExternal(source, destination, cancellation));

        assertArrayEquals(sourceBytes, Files.readAllBytes(source));
        assertFalse(Files.exists(destination));
        try(var children = Files.list(mTemporaryFolder))
        {
            assertTrue(children.noneMatch(path -> path.getFileName().toString().startsWith(".sqlite-source-")));
        }
    }

    @Test
    void refusesSymbolicLinkDatabaseAndSidecars() throws Exception
    {
        Path source = mTemporaryFolder.resolve("source.sqlite");
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + source);
            Statement statement = connection.createStatement())
        {
            statement.executeUpdate("CREATE TABLE sample(value TEXT)");
        }

        Path sourceLink = mTemporaryFolder.resolve("source-link.sqlite");
        try
        {
            Files.createSymbolicLink(sourceLink, source.getFileName());
        }
        catch(UnsupportedOperationException | IOException | SecurityException e)
        {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }

        Path linkedDestination = mTemporaryFolder.resolve("linked-snapshot.sqlite");
        IOException sourceFailure = assertThrows(IOException.class,
            () -> SqliteDatabaseSnapshot.create(sourceLink, linkedDestination));
        assertTrue(sourceFailure.getMessage().contains("symbolic-link SQLite database"));
        assertFalse(Files.exists(linkedDestination));

        Path external = mTemporaryFolder.resolve("external-sidecar-content");
        Files.writeString(external, "must remain unchanged");

        for(String suffix: java.util.List.of("-journal", "-wal", "-shm"))
        {
            Path sidecar = Path.of(source + suffix);
            Files.createSymbolicLink(sidecar, external.getFileName());
            Path destination = mTemporaryFolder.resolve("sidecar" + suffix + ".sqlite");
            IOException sidecarFailure = assertThrows(IOException.class,
                () -> SqliteDatabaseSnapshot.create(source, destination));
            assertTrue(sidecarFailure.getMessage().contains("symbolic-link SQLite sidecar"));
            assertFalse(Files.exists(destination));
            assertEquals("must remain unchanged", Files.readString(external));
            Files.delete(sidecar);
        }
    }

    private static String scalar(Path database, String sql) throws Exception
    {
        try(Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
            Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery(sql))
        {
            assertTrue(resultSet.next());
            return resultSet.getString(1);
        }
    }

    private static boolean hasPartialPrivateCopy(Path parent)
    {
        try(var children = Files.list(parent))
        {
            return children.filter(path -> path.getFileName().toString().startsWith(".sqlite-source-"))
                .map(path -> path.resolve("source.sqlite"))
                .anyMatch(path ->
                {
                    try
                    {
                        return Files.isRegularFile(path) && Files.size(path) > 0;
                    }
                    catch(IOException exception)
                    {
                        throw new UncheckedIOException(exception);
                    }
                });
        }
        catch(IOException exception)
        {
            throw new UncheckedIOException(exception);
        }
    }
}
