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

import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Prepared target for the immutable {@code calls/v1} retained-call directory convention.
 */
final class ManagedCallRecording implements AutoCloseable
{
    static final String MANAGED_DIRECTORY = "calls";
    static final String LAYOUT_VERSION = "v1";
    private static final int MAXIMUM_SEGMENT_LENGTH = 28;
    private static final int HASH_BYTES = 6;
    private static final int HASH_HEX_LENGTH = HASH_BYTES * 2;
    private static final int MAXIMUM_SLUG_LENGTH = MAXIMUM_SEGMENT_LENGTH - HASH_HEX_LENGTH - 1;
    private static final int MAXIMUM_RELATIVE_PATH_LENGTH = 208;
    private static final Set<String> WINDOWS_RESERVED_NAMES = Set.of("con", "prn", "aux", "nul",
        "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
        "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");
    private static final DateTimeFormatter YEAR = DateTimeFormatter.ofPattern("uuuu").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("MM").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter FILE_TIME =
        DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss.SSS'Z'").withZone(ZoneOffset.UTC);
    private final Path mRoot;
    private final Path mFinalPath;
    private final Path mStagingPath;
    private final Path mStagingDirectory;
    private final Path mReservationPath;
    private final Object mReservationKey;
    private final Set<Path> mActivePaths;
    private final boolean mDestinationTalkgroupRecordEnabled;
    private final long mCompletedAtMs;
    private boolean mCommitted;

    private ManagedCallRecording(Path root, Path finalPath, Path stagingPath, Path stagingDirectory,
                                 Path reservationPath, Object reservationKey, Set<Path> activePaths,
                                 boolean destinationTalkgroupRecordEnabled, long completedAtMs)
    {
        mRoot = root;
        mFinalPath = finalPath;
        mStagingPath = stagingPath;
        mStagingDirectory = stagingDirectory;
        mReservationPath = reservationPath;
        mReservationKey = reservationKey;
        mActivePaths = activePaths;
        mDestinationTalkgroupRecordEnabled = destinationTalkgroupRecordEnabled;
        mCompletedAtMs = completedAtMs;
    }

    static ManagedCallRecording prepare(Path recordingRoot, CompletedAudioCall call, RecordFormat format)
        throws IOException
    {
        return prepare(recordingRoot, call, format, CallPathMetadata.capture(call), new HashSet<>());
    }

    static ManagedCallRecording prepare(Path recordingRoot, CompletedAudioCall call, RecordFormat format,
                                        CallPathMetadata metadata) throws IOException
    {
        return prepare(recordingRoot, call, format, metadata, new HashSet<>());
    }

    static ManagedCallRecording prepare(Path recordingRoot, CompletedAudioCall call, RecordFormat format,
                                        CallPathMetadata metadata, Set<Path> activePaths) throws IOException
    {
        Objects.requireNonNull(recordingRoot, "Recording root cannot be null");
        Objects.requireNonNull(call, "Completed call cannot be null");
        Objects.requireNonNull(format, "Recording format cannot be null");
        Objects.requireNonNull(call.snapshot(), "Completed call snapshot cannot be null");
        Objects.requireNonNull(metadata, "Managed call path metadata cannot be null");
        Objects.requireNonNull(activePaths, "Active managed recording paths cannot be null");
        long completedAt = metadata.completedAtMs();
        Instant instant = Instant.ofEpochMilli(completedAt);
        Path base = ensureBaseDirectory(recordingRoot);
        Path managedRoot = ensureChildDirectory(base, MANAGED_DIRECTORY);
        Path versionRoot = ensureChildDirectory(managedRoot, LAYOUT_VERSION);
        Path leaf = versionRoot;
        leaf = ensureChildDirectory(leaf, YEAR.format(instant));
        leaf = ensureChildDirectory(leaf, MONTH.format(instant));
        leaf = ensureChildDirectory(leaf, DAY.format(instant));
        leaf = ensureChildDirectory(leaf, metadata.system() != null && !metadata.system().isBlank() ?
            namedSegment(metadata.system(), "system:" + metadata.systemIdentity(), "_unknown") : "_unknown");
        leaf = ensureChildDirectory(leaf, metadata.site() != null && !metadata.site().isBlank() ?
            namedSegment(metadata.site(), "site:" + metadata.siteIdentity(), "_unknown") : "_conventional");
        leaf = ensureChildDirectory(leaf, metadata.channel() != null && !metadata.channel().isBlank() ?
            namedSegment(metadata.channel(), "channel:" + metadata.channelIdentity(), "_unknown") : "_unknown");
        leaf = ensureChildDirectory(leaf, destinationSegment(metadata));
        String fileName = call.snapshot().callId() != null ?
            ManagedRecordingPath.fileName(call.snapshot().callId(), completedAt, format) :
            filenameStem(null, completedAt) + format.getExtension();
        ReservedPath finalReservation = reserveFinalPath(leaf, fileName, activePaths);
        Path stagingDirectory = null;

        try
        {
            stagingDirectory = stagingDirectory(leaf, activePaths);
            Path stagingPath = stagingDirectory.resolve("audio.tmp");
            verifyManagedPath(versionRoot, finalReservation.finalPath());
            verifyManagedPath(versionRoot, finalReservation.reservationPath());
            verifyManagedPath(versionRoot, stagingPath);
            Path relativePath = base.relativize(finalReservation.finalPath());

            if(relativePath.toString().length() > MAXIMUM_RELATIVE_PATH_LENGTH ||
                ManagedRecordingPath.parse(relativePath).isEmpty())
            {
                throw new IOException("Generated managed recording path does not match the released layout");
            }

            return new ManagedCallRecording(base, finalReservation.finalPath(), stagingPath, stagingDirectory,
                finalReservation.reservationPath(), finalReservation.fileKey(), activePaths,
                metadata.destinationTalkgroupRecordEnabled(), completedAt);
        }
        catch(IOException | RuntimeException exception)
        {
            try
            {
                deleteEmptyReservation(finalReservation.reservationPath(), finalReservation.fileKey());
            }
            catch(IOException cleanupException)
            {
                exception.addSuppressed(cleanupException);
            }
            finally
            {
                activePaths.remove(finalReservation.reservationPath());
            }

            if(stagingDirectory != null)
            {
                try
                {
                    Files.deleteIfExists(stagingDirectory);
                }
                catch(IOException cleanupException)
                {
                    exception.addSuppressed(cleanupException);
                }
                finally
                {
                    activePaths.remove(stagingDirectory);
                }
            }

            throw exception;
        }
    }

    Path stagingPath()
    {
        return mStagingPath;
    }

    Path finalPath()
    {
        return mFinalPath;
    }

    boolean destinationTalkgroupRecordEnabled()
    {
        return mDestinationTalkgroupRecordEnabled;
    }

    long completedAtMs()
    {
        return mCompletedAtMs;
    }

    Path commit() throws IOException
    {
        if(mCommitted)
        {
            return mFinalPath;
        }

        if(!Files.isRegularFile(mStagingPath, LinkOption.NOFOLLOW_LINKS) || Files.size(mStagingPath) <= 0)
        {
            throw new IOException("Managed recording staging file is missing or empty");
        }

        verifyReservation();

        //The staging file is in a private directory beneath the final leaf, so this is always a same-filesystem move.
        //Do not request ATOMIC_MOVE: its target-exists behavior is implementation-specific and can overwrite.  A
        //normal move without REPLACE_EXISTING has the portable no-overwrite contract required for canonical calls.
        Files.move(mStagingPath, mFinalPath);

        mCommitted = true;

        try
        {
            Files.deleteIfExists(mStagingPath);
        }
        catch(IOException _)
        {
            //The move normally removes staging; reconciliation handles a provider-specific leftover.
        }

        try
        {
            deleteEmptyReservation(mReservationPath, mReservationKey);
        }
        catch(IOException _)
        {
            //Publication succeeded.  Reconciliation can remove the reservation later.
        }

        try
        {
            Files.deleteIfExists(mStagingDirectory);
        }
        catch(IOException _)
        {
            //Publication succeeded.  Reconciliation can remove the now-empty work directory later.
        }
        finally
        {
            mActivePaths.remove(mReservationPath);
            mActivePaths.remove(mStagingDirectory);
        }

        return mFinalPath;
    }

    Path relativePath()
    {
        return mRoot.relativize(mFinalPath);
    }

    @Override
    public void close()
    {
        if(!mCommitted)
        {
            try
            {
                Files.deleteIfExists(mStagingPath);
            }
            catch(IOException _)
            {
                //Reconciliation retries this staging path.
            }

            try
            {
                Files.deleteIfExists(mStagingDirectory);
            }
            catch(IOException _)
            {
                //Reconciliation retries this work directory after its contents are removable.
            }

            try
            {
                deleteEmptyReservation(mReservationPath, mReservationKey);
            }
            catch(IOException _)
            {
                //Reconciliation retries this zero-byte reservation.
            }

            pruneEmptyManagedDirectories();
            mActivePaths.remove(mReservationPath);
            mActivePaths.remove(mStagingDirectory);
        }
    }

    private void pruneEmptyManagedDirectories()
    {
        Path boundary = mRoot.resolve(MANAGED_DIRECTORY).normalize();
        Path current = mStagingPath.getParent();

        while(current != null && current.normalize().startsWith(boundary) && !current.normalize().equals(boundary))
        {
            try
            {
                Files.deleteIfExists(current);
            }
            catch(IOException exception)
            {
                return;
            }

            current = current.getParent();
        }
    }

    private static Path ensureBaseDirectory(Path root) throws IOException
    {
        Files.createDirectories(root);
        return root.toRealPath();
    }

    private static Path ensureChildDirectory(Path parent, String component) throws IOException
    {
        if(component == null || component.isBlank() || component.contains("/") || component.contains("\\") ||
            ".".equals(component) || "..".equals(component))
        {
            throw new IOException("Invalid managed recording directory component");
        }

        Path child = parent.resolve(component);

        if(Files.exists(child, LinkOption.NOFOLLOW_LINKS))
        {
            if(Files.isSymbolicLink(child) || !Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS))
            {
                throw new IOException("Managed recording directory is not a real directory: " + component);
            }
        }
        else
        {
            Files.createDirectory(child);
        }

        Path realChild = child.toRealPath();

        if(!realChild.startsWith(parent.toRealPath()))
        {
            throw new IOException("Managed recording directory escaped its root");
        }

        return realChild;
    }

    private static void verifyManagedPath(Path root, Path path) throws IOException
    {
        Path normalizedRoot = root.toRealPath();
        Path normalizedParent = path.getParent().toRealPath();

        if(!normalizedParent.startsWith(normalizedRoot) || Files.isSymbolicLink(normalizedParent))
        {
            throw new IOException("Managed recording path escaped its versioned root");
        }
    }

    private void verifyReservation() throws IOException
    {
        BasicFileAttributes attributes = Files.readAttributes(mReservationPath, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);

        if(!attributes.isRegularFile() || attributes.size() != 0 ||
            mReservationKey != null && !mReservationKey.equals(attributes.fileKey()))
        {
            throw new IOException("Managed recording reservation changed before publication");
        }
    }

    private static ReservedPath reserveFinalPath(Path directory, String fileName, Set<Path> activePaths)
        throws IOException
    {
        Path finalPath = directory.resolve(fileName);
        Path reservationPath = directory.resolve('.' + fileName + ".reserve");

        if(!activePaths.add(reservationPath))
        {
            throw new IOException("Canonical managed recording is already active");
        }

        boolean reservationCreated = false;

        try
        {
            if(Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS))
            {
                throw new FileAlreadyExistsException(finalPath.toString());
            }

            Files.createFile(reservationPath);
            reservationCreated = true;
            BasicFileAttributes attributes = Files.readAttributes(reservationPath, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);

            if(Files.exists(finalPath, LinkOption.NOFOLLOW_LINKS))
            {
                Files.deleteIfExists(reservationPath);
                throw new FileAlreadyExistsException(finalPath.toString());
            }

            return new ReservedPath(finalPath, reservationPath, attributes.fileKey());
        }
        catch(FileAlreadyExistsException exception)
        {
            activePaths.remove(reservationPath);
            throw new IOException("Canonical managed recording already exists", exception);
        }
        catch(IOException | RuntimeException exception)
        {
            if(reservationCreated)
            {
                try
                {
                    Files.deleteIfExists(reservationPath);
                }
                catch(IOException cleanupException)
                {
                    exception.addSuppressed(cleanupException);
                }
            }

            activePaths.remove(reservationPath);
            throw exception;
        }
    }

    private static Path stagingDirectory(Path directory, Set<Path> activePaths) throws IOException
    {
        for(int attempt = 0; attempt < 10; attempt++)
        {
            Path candidate = directory.resolve(".recording-" + UUID.randomUUID() + ".work");

            if(!activePaths.add(candidate))
            {
                continue;
            }

            try
            {
                Path created = Files.createDirectory(candidate);

                try
                {
                    return created.toRealPath();
                }
                catch(IOException | RuntimeException exception)
                {
                    try
                    {
                        Files.deleteIfExists(created);
                    }
                    catch(IOException cleanupException)
                    {
                        exception.addSuppressed(cleanupException);
                    }

                    throw exception;
                }
            }
            catch(FileAlreadyExistsException _)
            {
                activePaths.remove(candidate);
                //Try another unpredictable private work directory.
            }
            catch(IOException | RuntimeException exception)
            {
                activePaths.remove(candidate);
                throw exception;
            }
        }

        throw new IOException("Unable to reserve a managed recording work directory");
    }

    private static String filenameStem(AudioCallId callId, long completedAt)
    {
        String identity;

        if(callId != null)
        {
            identity = Long.toUnsignedString(callId.producerId(), 36) + '-' +
                Long.toUnsignedString(callId.sequence(), 36) + '-' +
                Integer.toUnsignedString(Math.max(0, callId.timeslot()), 36);
        }
        else
        {
            identity = "unknown-" + shortHash(Long.toString(completedAt));
        }

        return FILE_TIME.format(Instant.ofEpochMilli(completedAt)) + '-' + identity;
    }

    private static String destinationSegment(CallPathMetadata metadata)
    {
        if(metadata.destinationValue() == null || metadata.destinationValue().isBlank())
        {
            return "_unknown";
        }

        String value = slug(metadata.destinationValue());
        String name = slug(metadata.destinationAlias());
        String label = value;

        if(!"_unknown".equals(name))
        {
            label += '-' + name;
        }

        return truncate(label, MAXIMUM_SLUG_LENGTH) + '~' + shortHash(metadata.destinationIdentity());
    }

    private static String namedSegment(String label, String identity, String fallback)
    {
        String safe = slug(label);
        return ("_unknown".equals(safe) ? fallback : safe) + '~' + shortHash(identity);
    }

    private static String slug(String value)
    {
        if(value == null || value.isBlank())
        {
            return "_unknown";
        }

        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKD);
        StringBuilder slug = new StringBuilder();
        boolean separator = false;

        for(int index = 0; index < normalized.length(); index++)
        {
            char character = normalized.charAt(index);

            if(character <= 0x7f && Character.isLetterOrDigit(character))
            {
                if(separator && !slug.isEmpty())
                {
                    slug.append('-');
                }

                slug.append(Character.toLowerCase(character));
                separator = false;
            }
            else if(character == '-' || character == '_' || character == '.' || Character.isWhitespace(character))
            {
                separator = true;
            }
        }

        String result = truncate(slug.toString(), MAXIMUM_SLUG_LENGTH);

        while(result.endsWith(".") || result.endsWith(" "))
        {
            result = result.substring(0, result.length() - 1);
        }

        if(result.isBlank() || ".".equals(result) || "..".equals(result))
        {
            return "_unknown";
        }

        String baseName = result.contains(".") ? result.substring(0, result.indexOf('.')) : result;

        if(WINDOWS_RESERVED_NAMES.contains(baseName.toLowerCase(Locale.ROOT)))
        {
            result = '_' + result;
        }

        return result;
    }

    private static String truncate(String value, int maximumLength)
    {
        return value.length() <= maximumLength ? value : value.substring(0, maximumLength);
    }

    private static String shortHash(String value)
    {
        try
        {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                nullSafe(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, HASH_BYTES);
        }
        catch(NoSuchAlgorithmException exception)
        {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static long completedAt(AudioCallSnapshot snapshot)
    {
        long completedAt = Math.max(snapshot.lastActivityTimestamp(), snapshot.lastBurstEndTimestamp());
        return completedAt > 0 ? completedAt : Math.max(1L, System.currentTimeMillis());
    }

    private static String nullSafe(String value)
    {
        return value != null ? value : "";
    }

    private static void deleteEmptyReservation(Path path, Object expectedFileKey) throws IOException
    {
        if(path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS))
        {
            return;
        }

        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class,
            LinkOption.NOFOLLOW_LINKS);

        if(attributes.isRegularFile() && attributes.size() == 0 &&
            (expectedFileKey == null || expectedFileKey.equals(attributes.fileKey())))
        {
            Files.deleteIfExists(path);
        }
    }

    static record CallPathMetadata(long completedAtMs, String system, String systemIdentity, String site,
                                   String siteIdentity, String channel, String channelIdentity,
                                   String destinationValue, String destinationAlias, String destinationIdentity,
                                   boolean destinationTalkgroupRecordEnabled)
    {
        static CallPathMetadata capture(CompletedAudioCall call)
        {
            AudioCallSnapshot snapshot =
                Objects.requireNonNull(call.snapshot(), "Completed call snapshot cannot be null");
            AudioCallRecordingMetadata metadata = snapshot.recordingMetadata();
            return new CallPathMetadata(completedAt(snapshot), metadata.systemName(), metadata.systemIdentity(),
                metadata.siteName(), metadata.siteIdentity(), metadata.channelName(), metadata.channelIdentity(),
                metadata.destinationValue(), metadata.destinationAlias(), metadata.destinationMatcherIdentity(),
                metadata.destinationTalkgroupRecordEnabled());
        }
    }

    private record ReservedPath(Path finalPath, Path reservationPath, Object fileKey)
    {
    }
}
