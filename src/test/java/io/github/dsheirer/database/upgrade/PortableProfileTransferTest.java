/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.portable.PortableDataRootLock;
import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PortableProfileTransferTest
{
    @TempDir Path temporary;

    @Test
    void movesCompleteProfileWithoutCopyingItsDatabaseOrAudio() throws Exception
    {
        Path source = source(), target = target();
        Path audio = source.resolve("recordings-managed/call.wav");
        Object audioIdentity = Files.readAttributes(audio, BasicFileAttributes.class).fileKey();
        try(PortableDataRootLock lock = PortableDataRootLock.acquire(source))
        {
            var transfer = PortableProfileTransfer.prepare(source, target, lock);
            transfer.metadata(Map.of("content", "CONFIGURATION_ONLY", "preparedRoot", temporary.resolve("prepared").toString()));
            transfer.move();
            assertFalse(Files.exists(source));
            assertEquals("catalog", Files.readString(target.resolve("database/managed-recordings.sqlite")));
            assertEquals("vault", Files.readString(target.resolve("vault/keys.sqlite")));
            assertEquals("voice-library", Files.readString(target.resolve("jmbe/library.jar")));
            assertEquals("classic", Files.readString(target.resolve("recordings/call.wav")));
            assertEquals(audioIdentity, Files.readAttributes(target.resolve("recordings-managed/call.wav"),
                BasicFileAttributes.class).fileKey());
            assertThrows(IOException.class, () -> PortableProfileTransfer.requireActivation(source, target));
            assertThrows(IOException.class, () -> PortableDataRootLock.acquire(target));
            transfer.beginActivation();
            PortableProfileTransfer.requireActivation(source, target);
            transfer.markCommitted();
            transfer.complete();
            assertNull(PortableProfileTransfer.inspect(target));
            assertEquals("audio", Files.readString(target.resolve("recordings-managed/call.wav")));
        }
        try(PortableDataRootLock ignored = PortableDataRootLock.acquire(target)) {}
    }

    @Test
    void resumesPreparedTransferWithoutChangingSource() throws Exception
    {
        Path source = source(), target = target();
        try(PortableDataRootLock lock = PortableDataRootLock.acquire(source))
        {
            var transfer = PortableProfileTransfer.prepare(source, target, lock);
            transfer.metadata(Map.of("content", "FULL", "futureMetadata", "kept"));
        }
        assertEquals(source, PortableProfileTransfer.lockRoot(target));
        assertEquals(PortableProfileTransfer.Phase.PREPARED, PortableProfileTransfer.inspect(target).phase());
        try(PortableDataRootLock lock = PortableDataRootLock.acquire(source))
        {
            var resumed = PortableProfileTransfer.resume(target, lock);
            resumed.metadata(Map.of("activationPhase", "SOURCE_FILES_SAVED"));
            assertEquals("kept", resumed.metadata("futureMetadata"));
            assertEquals("FULL", resumed.metadata("content"));
            resumed.move();
            resumed.restoreBeforeActivation();
        }
        assertTrue(Files.exists(source.resolve("recordings-managed/call.wav")));
        assertFalse(Files.exists(target));
        assertNull(PortableProfileTransfer.inspect(target));
    }

    @Test
    void identifiesRenameCompletedBeforeMovedReceiptWasPublished() throws Exception
    {
        Path source = source(), target = target();
        try(PortableDataRootLock lock = PortableDataRootLock.acquire(source))
        {
            PortableProfileTransfer.prepare(source, target, lock);
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        }
        assertEquals(target, PortableProfileTransfer.lockRoot(target));
        assertEquals(PortableProfileTransfer.Phase.MOVED, PortableProfileTransfer.inspect(target).phase());
        try(PortableDataRootLock lock = PortableDataRootLock.acquire(target))
        {
            var resumed = PortableProfileTransfer.resume(target, lock);
            assertEquals(PortableProfileTransfer.Phase.MOVED, resumed.phase());
            resumed.move(); // Idempotent: never attempts another transfer or replaces an installation.
            resumed.restoreBeforeActivation();
        }
        assertTrue(Files.exists(source.resolve("database/sdrtrunk.sqlite")));
    }

    @Test
    void neverRestoresAfterActivationBoundaryEvenThroughAStaleHandle() throws Exception
    {
        Path source = source(), target = target();
        try(PortableDataRootLock lock = PortableDataRootLock.acquire(source))
        {
            var first = PortableProfileTransfer.prepare(source, target, lock);
            first.move();
            var second = PortableProfileTransfer.resume(target, lock);
            second.beginActivation();
            IOException failure = assertThrows(IOException.class, first::restoreBeforeActivation);
            assertTrue(failure.getMessage().contains("activation has started"));
            assertEquals(PortableProfileTransfer.Phase.ACTIVATING, PortableProfileTransfer.inspect(target).phase());
            assertTrue(Files.exists(target.resolve("database/sdrtrunk.sqlite")));
            assertFalse(Files.exists(source));
        }
        try(PortableDataRootLock lock = PortableDataRootLock.acquire(target))
        {
            var resumed = PortableProfileTransfer.resume(target, lock);
            assertEquals(PortableProfileTransfer.Phase.ACTIVATING, resumed.phase());
            assertThrows(IOException.class, resumed::restoreBeforeActivation);
            resumed.markCommitted();
            assertThrows(IOException.class, resumed::restoreBeforeActivation);
            resumed.complete();
        }
    }

    @Test
    void doesNotReplaceDestinationDataOrRestoreOverARecreatedSource() throws Exception
    {
        Path source = source(), target = target();
        Files.createDirectories(target);
        Files.writeString(target.resolve("keep.txt"), "destination");
        try(PortableDataRootLock lock = PortableDataRootLock.acquire(source))
        {
            assertThrows(IOException.class, () -> PortableProfileTransfer.prepare(source, target, lock));
            assertEquals("destination", Files.readString(target.resolve("keep.txt")));
            Files.delete(target.resolve("keep.txt"));
            var transfer = PortableProfileTransfer.prepare(source, target, lock);
            transfer.move();
            Files.createDirectory(source);
            Files.writeString(source.resolve("keep.txt"), "new source");
            assertThrows(IOException.class, transfer::restoreBeforeActivation);
            assertEquals("new source", Files.readString(source.resolve("keep.txt")));
            assertTrue(Files.exists(target.resolve("recordings-managed/call.wav")));
        }
    }

    @Test
    void refusesCrossFilesystemBeforeCreatingReceiptOrMovingAnything() throws Exception
    {
        Path source = source();
        URI zip = URI.create("jar:" + temporary.resolve("other-filesystem.zip").toUri());
        try(var filesystem = FileSystems.newFileSystem(zip, Map.of("create", "true"));
            PortableDataRootLock lock = PortableDataRootLock.acquire(source))
        {
            Path destinationParent = Files.createDirectory(filesystem.getPath("/new-install"));
            Path target = destinationParent.resolve("data");
            IOException failure = assertThrows(IOException.class, () -> PortableProfileTransfer.prepare(source, target, lock));
            assertTrue(failure.getMessage().contains("same filesystem"));
            assertTrue(Files.exists(source.resolve("database/sdrtrunk.sqlite")));
            assertFalse(Files.exists(target));
            assertFalse(Files.exists(PortableProfileTransfer.receiptPath(target)));
        }
    }

    @Test
    void refusesOverlappingRootsMissingLockAndSymbolicRoot() throws Exception
    {
        Path source = source(), target = target();
        try(PortableDataRootLock lock = PortableDataRootLock.acquire(source))
        {
            assertThrows(IOException.class, () -> PortableProfileTransfer.prepare(source, source, lock));
            assertThrows(IOException.class, () -> PortableProfileTransfer.prepare(source, source.resolve("nested"), lock));
            assertThrows(IOException.class, () -> PortableProfileTransfer.prepare(source, target, null));
            Path link = temporary.resolve("linked-source");
            try { Files.createSymbolicLink(link, source); }
            catch(UnsupportedOperationException | IOException unsupported) { return; }
            assertThrows(IOException.class, () -> PortableProfileTransfer.prepare(link, target, lock));
            assertTrue(Files.exists(source.resolve("database/sdrtrunk.sqlite")));
        }
    }

    @Test
    void recoveryRefusesSubstitutedDataRootAndMalformedMetadata() throws Exception
    {
        Path source = source(), target = target();
        try(PortableDataRootLock lock = PortableDataRootLock.acquire(source))
        {
            var transfer = PortableProfileTransfer.prepare(source, target, lock);
            assertThrows(IllegalArgumentException.class, () -> transfer.metadata(Map.of("bad key", "rejected")));
            transfer.move();
        }
        Files.move(target, target.resolveSibling("preserved-real-data"));
        Files.createDirectory(target);
        Files.writeString(target.resolve("unrelated.txt"), "preserve");
        assertThrows(IOException.class, () -> PortableProfileTransfer.inspect(target));
        assertThrows(IOException.class, () -> PortableProfileTransfer.requireActivation(source, target));
        assertEquals("preserve", Files.readString(target.resolve("unrelated.txt")));
    }

    @Test
    void refusesMutationAfterBorrowedLockWasClosed() throws Exception
    {
        Path source = source(), target = target();
        PortableDataRootLock lock = PortableDataRootLock.acquire(source);
        var transfer = PortableProfileTransfer.prepare(source, target, lock);
        lock.close();
        assertThrows(IOException.class, transfer::move);
        assertThrows(IOException.class, () -> transfer.metadata(Map.of("content", "FULL")));
        assertTrue(Files.exists(source.resolve("recordings-managed/call.wav")));
        assertFalse(Files.exists(target));
        try(PortableDataRootLock recoveredLock = PortableDataRootLock.acquire(source))
        {
            PortableProfileTransfer.resume(target, recoveredLock).restoreBeforeActivation();
        }
    }

    private Path source() throws IOException
    {
        Path root = Files.createDirectories(temporary.resolve("old-install/data"));
        for(var file: Map.of("database/sdrtrunk.sqlite", "configuration", "database/activity.sqlite", "history",
            "database/managed-recordings.sqlite", "catalog", "recordings-managed/call.wav", "audio",
            "recordings/call.wav", "classic", "vault/keys.sqlite", "vault", "jmbe/library.jar", "voice-library").entrySet())
        {
            Path path = root.resolve(file.getKey());
            Files.createDirectories(path.getParent());
            Files.writeString(path, file.getValue());
        }
        return root.toAbsolutePath().normalize();
    }

    private Path target() throws IOException
    {
        return Files.createDirectories(temporary.resolve("new-install")).resolve("data").toAbsolutePath().normalize();
    }
}
