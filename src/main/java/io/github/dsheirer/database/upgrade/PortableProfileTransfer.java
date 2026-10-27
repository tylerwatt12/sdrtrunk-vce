/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.database.upgrade;

import io.github.dsheirer.portable.PortableDataRootLock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * Explicit ownership transfer of one stopped portable profile, without copying its recordings or history.
 * The caller has already obtained the user's backup acknowledgment and holds the source profile lock. This helper
 * borrows that lock; it never closes it. Its channel continues to lock the same file after a successful rename.
 * A filesystem that cannot rename the locked directory is refused without a copy-and-delete fallback.
 *
 * <p>The small receipt lives beside the destination, so a directory rename cannot strand it. Directory identity,
 * rather than mere path existence, proves which location owns the transferred profile after an interrupted move.
 * Call {@link Transfer#beginActivation()} durably before any operation that could commit database changes. Once
 * activation begins, neither this helper nor recovery automatically moves data back under the older application.
 */
public final class PortableProfileTransfer
{
    private static final String SUFFIX = ".sdrtrunk-vce-profile-transfer.properties";
    private static final int MAX_RECEIPT_BYTES = 32 * 1024;
    private static final Set<String> FIELDS = Set.of("version", "id", "source", "target", "identity", "phase");

    private PortableProfileTransfer() {}

    public enum Phase { PREPARED, MOVED, ACTIVATING, COMMITTED }

    /** A read-only recovery description. Acquire the lock at currentRoot before calling resume. */
    public record Inspection(Path sourceRoot, Path targetRoot, Path currentRoot, Phase phase) {}

    public static Path receiptPath(Path targetRoot)
    {
        Path target = normalize(targetRoot);
        if(target.getParent() == null) throw new IllegalArgumentException("Transfer destination needs a parent folder");
        return target.resolveSibling("." + target.getFileName() + SUFFIX);
    }

    /** Checks only root metadata and the destination's immediate entries, never walks recording/history files. */
    public static Transfer prepare(Path sourceRoot, Path targetRoot, PortableDataRootLock sourceLock) throws IOException
    {
        Path source = normalize(sourceRoot), target = normalize(targetRoot);
        requireRoots(source, target);
        requireLock(sourceLock, source, source);
        requireDirectory(source);
        requireBlankTarget(target);
        requireSameFilesystem(source, target.getParent());
        Path receipt = receiptPath(target);
        if(Files.exists(receipt, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("An earlier VCE profile transfer needs to be resumed before starting another.");
        Transfer transfer = new Transfer(source, target, identity(source), UUID.randomUUID().toString(), Phase.PREPARED,
            Map.of());
        transfer.borrowedLock = sourceLock;
        transfer.write(false);
        return transfer;
    }

    /** Returns null when no transfer is pending. This method does not acquire a profile lock or modify files. */
    public static Inspection inspect(Path targetRoot) throws IOException
    {
        if(!Files.exists(receiptPath(targetRoot), LinkOption.NOFOLLOW_LINKS)) return null;
        Transfer transfer = read(targetRoot);
        return transfer.inspectLocation();
    }

    public static Path lockRoot(Path targetRoot) throws IOException
    {
        Inspection inspection = inspect(targetRoot);
        if(inspection == null) throw new IOException("No VCE profile transfer is pending.");
        return inspection.currentRoot();
    }

    public static Transfer resume(Path targetRoot, PortableDataRootLock heldLock) throws IOException
    {
        Transfer transfer = read(targetRoot);
        Inspection inspection = transfer.inspectLocation();
        requireLock(heldLock, inspection.currentRoot(), transfer.sourceRoot);
        transfer.borrowedLock = heldLock;
        if(transfer.phase != inspection.phase()) transfer.updatePhase(inspection.phase());
        return transfer;
    }

    /** Child-process admission; the parent retains the portable-profile lock throughout activation. */
    public static void requireActivation(Path originalSource, Path targetRoot) throws IOException
    {
        Transfer transfer = read(targetRoot);
        Inspection inspection = transfer.inspectLocation();
        if(!transfer.sourceRoot.equals(normalize(originalSource)) || !inspection.currentRoot().equals(transfer.targetRoot) ||
            (inspection.phase() != Phase.ACTIVATING && inspection.phase() != Phase.COMMITTED))
            throw new IOException("The transferred profile does not match the pending VCE upgrade.");
    }

    private static Transfer read(Path targetRoot) throws IOException
    {
        Path target = normalize(targetRoot), receipt = receiptPath(target);
        if(Files.isSymbolicLink(receipt) || !Files.isRegularFile(receipt, LinkOption.NOFOLLOW_LINKS) ||
            Files.size(receipt) > MAX_RECEIPT_BYTES)
            throw new IOException("The VCE profile transfer receipt is unavailable or unsafe. Preserve both installations.");
        Properties properties = new Properties();
        try
        {
            try(var input = Files.newInputStream(receipt)) { properties.load(input); }
            if(!"1".equals(properties.getProperty("version")) || !properties.stringPropertyNames().containsAll(FIELDS))
                throw new IllegalArgumentException();
            Path source = normalize(Path.of(properties.getProperty("source")));
            if(!normalize(Path.of(properties.getProperty("target"))).equals(target)) throw new IllegalArgumentException();
            requireRoots(source, target);
            String id = properties.getProperty("id");
            UUID.fromString(id);
            String identity = properties.getProperty("identity");
            if(identity.isBlank()) throw new IllegalArgumentException();
            Map<String,String> metadata = new LinkedHashMap<>();
            for(String name: properties.stringPropertyNames())
            {
                if(name.startsWith("metadata."))
                {
                    String key = name.substring("metadata.".length());
                    requireMetadata(key, properties.getProperty(name));
                    metadata.put(key, properties.getProperty(name));
                }
                else if(!FIELDS.contains(name)) throw new IllegalArgumentException();
            }
            return new Transfer(source, target, identity, id, Phase.valueOf(properties.getProperty("phase")), metadata);
        }
        catch(IllegalArgumentException failure)
        {
            throw new IOException("The VCE profile transfer receipt is invalid. Preserve both installations.", failure);
        }
    }

    public static final class Transfer
    {
        private final Path sourceRoot;
        private final Path targetRoot;
        private final String identity;
        private final String id;
        private final Map<String,String> metadata;
        private PortableDataRootLock borrowedLock;
        private Phase phase;
        private boolean finished;

        private Transfer(Path source, Path target, String identity, String id, Phase phase, Map<String,String> metadata)
        {
            sourceRoot = source; targetRoot = target; this.identity = identity; this.id = id; this.phase = phase;
            this.metadata = new LinkedHashMap<>(metadata);
        }

        public Path sourceRoot() { return sourceRoot; }
        public Path targetRoot() { return targetRoot; }
        public Phase phase() { return phase; }
        public String metadata(String key) { return metadata.get(key); }
        public Map<String,String> metadata() { return Map.copyOf(metadata); }

        /** Merges values while retaining metadata owned by other upgrade phases. */
        public synchronized void metadata(Map<String,String> values) throws IOException
        {
            requirePending();
            Map<String,String> previous = new LinkedHashMap<>(metadata);
            values.forEach(PortableProfileTransfer::requireMetadata);
            metadata.putAll(values);
            try { write(true); }
            catch(IOException | RuntimeException failure)
            {
                metadata.clear(); metadata.putAll(previous); throw failure;
            }
        }

        public synchronized void move() throws IOException
        {
            requirePending();
            Inspection location = inspectLocation();
            if(location.currentRoot().equals(targetRoot))
            {
                if(phase == Phase.PREPARED) updatePhase(Phase.MOVED);
                return;
            }
            if(phase != Phase.PREPARED) throw new IOException("The profile cannot be moved in its current upgrade phase.");
            requireBlankTarget(targetRoot);
            requireSameFilesystem(sourceRoot, targetRoot.getParent());
            if(Files.exists(targetRoot, LinkOption.NOFOLLOW_LINKS)) Files.delete(targetRoot); // Only an empty directory.
            try
            {
                Files.move(sourceRoot, targetRoot, StandardCopyOption.ATOMIC_MOVE);
            }
            catch(AtomicMoveNotSupportedException failure)
            {
                throw new IOException("This filesystem cannot move the VCE data folder atomically. Keep both installations " +
                    "and transfer the closed data folder manually; no recording files were copied.", failure);
            }
            forceDirectory(sourceRoot.getParent());
            forceDirectory(targetRoot.getParent());
            // If publishing MOVED fails, resume detects the same directory identity at the destination.
            updatePhase(Phase.MOVED);
        }

        public synchronized void beginActivation() throws IOException
        {
            requirePending(); requireAtTarget();
            if(phase == Phase.COMMITTED) throw new IOException("The VCE profile upgrade is already committed.");
            if(phase != Phase.ACTIVATING) updatePhase(Phase.ACTIVATING);
        }

        public synchronized void markCommitted() throws IOException
        {
            requirePending(); requireAtTarget();
            if(phase != Phase.COMMITTED) updatePhase(Phase.COMMITTED);
        }

        /** Explicit cancellation only before activation; never guesses whether a database transaction committed. */
        public synchronized void restoreBeforeActivation() throws IOException
        {
            requirePending();
            if(phase == Phase.ACTIVATING || phase == Phase.COMMITTED)
                throw new IOException("Database activation has started. Do not move this profile back to the older VCE build; " +
                    "resume the upgrade or restore the user's complete backup.");
            Inspection location = inspectLocation();
            if(location.currentRoot().equals(targetRoot))
            {
                if(Files.exists(sourceRoot, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("The original data location is occupied. Both locations have been preserved.");
                Files.move(targetRoot, sourceRoot, StandardCopyOption.ATOMIC_MOVE);
                forceDirectory(sourceRoot.getParent());
                forceDirectory(targetRoot.getParent());
            }
            deleteReceipt();
        }

        /** Call only after the caller has finished its validated activation and cleanup. */
        public synchronized void complete() throws IOException
        {
            requirePending(); requireAtTarget();
            if(phase != Phase.COMMITTED) throw new IOException("The profile transfer has not been committed.");
            deleteReceipt();
        }

        private void deleteReceipt() throws IOException
        {
            requirePending();
            Files.delete(receiptPath(targetRoot));
            forceDirectory(targetRoot.getParent());
            finished = true;
        }

        private void requirePending() throws IOException
        {
            if(finished) throw new IOException("The VCE profile transfer has already finished.");
            if(borrowedLock == null || (!normalize(borrowedLock.getDataRoot()).equals(sourceRoot) &&
                !normalize(borrowedLock.getDataRoot()).equals(targetRoot)))
                throw new IOException("The VCE profile transfer no longer holds its data lock.");
            // This also proves the channel is still open and owns the same lock-file identity after a rename.
            borrowedLock.rebindMovedRoot(inspectLocation().currentRoot());
            Transfer disk = read(targetRoot);
            if(!id.equals(disk.id) || !identity.equals(disk.identity))
                throw new IOException("The pending VCE profile transfer changed. Preserve both installations.");
            // A second recovered handle must not forget a durable activation boundary written by another handle.
            if(disk.phase.ordinal() > phase.ordinal()) phase = disk.phase;
            else if(disk.phase != phase)
                throw new IOException("The pending VCE transfer phase changed. Resume the upgrade before continuing.");
            metadata.clear();
            metadata.putAll(disk.metadata);
        }

        private void requireAtTarget() throws IOException
        {
            if(!inspectLocation().currentRoot().equals(targetRoot))
                throw new IOException("The VCE data folder has not reached its new installation.");
        }

        private Inspection inspectLocation() throws IOException
        {
            requireRoots(sourceRoot, targetRoot);
            boolean atSource = matches(sourceRoot, identity), atTarget = matches(targetRoot, identity);
            if(atSource == atTarget)
                throw new IOException("The transferred data folder cannot be identified safely. Preserve both installations.");
            if(atSource && (phase == Phase.ACTIVATING || phase == Phase.COMMITTED))
                throw new IOException("An activated VCE profile is no longer at its expected destination. Preserve both installations.");
            Phase actual = phase == Phase.PREPARED && atTarget ? Phase.MOVED :
                phase == Phase.MOVED && atSource ? Phase.PREPARED : phase;
            return new Inspection(sourceRoot, targetRoot, atSource ? sourceRoot : targetRoot, actual);
        }

        private void updatePhase(Phase next) throws IOException
        {
            Phase previous = phase;
            phase = next;
            try { write(true); }
            catch(IOException | RuntimeException failure) { phase = previous; throw failure; }
        }

        private void write(boolean replace) throws IOException
        {
            Properties properties = new Properties();
            properties.setProperty("version", "1"); properties.setProperty("id", id);
            properties.setProperty("source", sourceRoot.toString()); properties.setProperty("target", targetRoot.toString());
            properties.setProperty("identity", identity); properties.setProperty("phase", phase.name());
            metadata.forEach((key, value) -> properties.setProperty("metadata." + key, value));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            properties.store(output, "VCE portable profile ownership transfer");
            byte[] bytes = output.toByteArray();
            if(bytes.length > MAX_RECEIPT_BYTES) throw new IOException("VCE profile transfer metadata exceeds its safe limit.");
            Path receipt = receiptPath(targetRoot);
            if(Files.isSymbolicLink(receipt)) throw new IOException("The VCE profile transfer receipt cannot be a symbolic link.");
            Path temporary = Files.createTempFile(receipt.getParent(), ".vce-transfer-", ".incomplete");
            try
            {
                FileAccessAttributeSnapshot.restrictSensitiveFile(temporary);
                try(FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE))
                {
                    ByteBuffer buffer = ByteBuffer.wrap(bytes);
                    while(buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                }
                if(!replace && Files.exists(receipt, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Another VCE profile transfer is already pending.");
                if(replace) Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                else Files.move(temporary, receipt, StandardCopyOption.ATOMIC_MOVE);
                forceDirectory(receipt.getParent());
            }
            finally { Files.deleteIfExists(temporary); }
        }
    }

    private static void requireMetadata(String key, String value)
    {
        if(key == null || !key.matches("[a-zA-Z][a-zA-Z0-9_.-]{0,63}") || value == null || value.length() > 4096)
            throw new IllegalArgumentException("Invalid VCE profile transfer metadata");
    }

    private static Path normalize(Path path) { return Objects.requireNonNull(path).toAbsolutePath().normalize(); }

    private static void requireRoots(Path source, Path target) throws IOException
    {
        if(source.getParent() == null || target.getParent() == null || Files.isSymbolicLink(source) || Files.isSymbolicLink(target))
            throw new IOException("Choose ordinary, separate VCE data folders without symbolic links.");
        if(!Files.isDirectory(source.getParent()) || !Files.isDirectory(target.getParent()))
            throw new IOException("Both VCE installation folders must exist before transferring data.");
        Path physicalSource = source.getParent().toRealPath().resolve(source.getFileName());
        Path physicalTarget = target.getParent().toRealPath().resolve(target.getFileName());
        if(physicalSource.startsWith(physicalTarget) || physicalTarget.startsWith(physicalSource))
            throw new IOException("The old and new VCE data folders must be separate and cannot contain each other.");
    }

    private static void requireDirectory(Path directory) throws IOException
    {
        if(Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("The VCE data folder must be an ordinary directory.");
    }

    private static void requireBlankTarget(Path target) throws IOException
    {
        if(!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        requireDirectory(target);
        try(var entries = Files.list(target))
        {
            if(entries.findAny().isPresent()) throw new IOException("The new installation already contains data. " +
                "Choose a blank installation; existing data will not be replaced.");
        }
    }

    private static void requireSameFilesystem(Path source, Path targetParent) throws IOException
    {
        if(!Files.getFileStore(source).equals(Files.getFileStore(targetParent)))
            throw new IOException("A fast VCE upgrade requires both installations on the same filesystem. " +
                "Put the new installation beside the old one, or copy the closed installation to the new drive manually. " +
                "No data has been moved.");
    }

    private static String identity(Path directory) throws IOException
    {
        requireDirectory(directory);
        Object key = Files.readAttributes(directory, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        if(key == null) throw new IOException("This filesystem cannot identify a moved VCE data folder reliably. " +
            "Transfer the closed installation manually instead.");
        return key.toString();
    }

    private static boolean matches(Path directory, String identity) throws IOException
    {
        return Files.exists(directory, LinkOption.NOFOLLOW_LINKS) && identity.equals(identity(directory));
    }

    private static void requireLock(PortableDataRootLock lock, Path current, Path original) throws IOException
    {
        if(lock == null || (!normalize(lock.getDataRoot()).equals(current) && !normalize(lock.getDataRoot()).equals(original)))
            throw new IOException("Hold the stopped VCE profile's data lock before transferring or resuming it.");
        lock.rebindMovedRoot(current);
    }

    /** Directory forcing is unavailable on Windows; the durable receipt file itself is always forced before rename. */
    private static void forceDirectory(Path directory) throws IOException
    {
        if(System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")) return;
        try(FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
}
