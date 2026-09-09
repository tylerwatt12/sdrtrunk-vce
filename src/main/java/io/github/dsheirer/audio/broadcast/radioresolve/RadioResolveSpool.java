/*
 * *****************************************************************************
 * Copyright (C) 2026 Dennis Sheirer
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 * *****************************************************************************
 */
package io.github.dsheirer.audio.broadcast.radioresolve;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Small file-backed FIFO that owns RadioResolve audio and its complete immutable v3 manifest before acknowledging the
 * temporary streaming file. It is deliberately independent from the application SQLite database.
 */
final class RadioResolveSpool
{
    static final int SPOOL_VERSION = 1;
    static final long MAXIMUM_AGE_MILLISECONDS = TimeUnit.HOURS.toMillis(24);
    static final long MAXIMUM_BYTES = 2L * 1024L * 1024L * 1024L;
    static final long PRUNE_INTERVAL_MILLISECONDS = TimeUnit.MINUTES.toMillis(1);
    private static final String MANIFEST_SUFFIX = ".json";
    private static final String AUDIO_SUFFIX = ".mp3";
    private static final Map<SpoolKey,SharedDirectoryState> SHARED_DIRECTORY_STATES = new ConcurrentHashMap<>();
    private final Path mDirectory;
    private final long mMaximumAgeMilliseconds;
    private final long mMaximumBytes;
    /**
     * Provider replacement can briefly leave the old async upload alive while its successor opens the same
     * directory. Both instances therefore share one in-process index, lock, and active-upload claim set.
     */
    private final SharedDirectoryState mState;

    RadioResolveSpool(Path directory)
    {
        this(directory, MAXIMUM_AGE_MILLISECONDS, MAXIMUM_BYTES);
    }

    RadioResolveSpool(Path directory, long maximumAgeMilliseconds, long maximumBytes)
    {
        mDirectory = directory.toAbsolutePath().normalize();
        mMaximumAgeMilliseconds = maximumAgeMilliseconds;
        mMaximumBytes = maximumBytes;
        mState = SHARED_DIRECTORY_STATES.computeIfAbsent(
            new SpoolKey(mDirectory, maximumAgeMilliseconds, maximumBytes), ignored -> new SharedDirectoryState());
    }

    RecoveryReport open() throws IOException
    {
        synchronized(mState)
        {
            return openLocked();
        }
    }

    private RecoveryReport openLocked() throws IOException
    {
        Files.createDirectories(mDirectory);
        removeMissingEntriesLocked();
        int temporaryFiles = 0;
        int corruptEntries = 0;

        try(DirectoryStream<Path> files = Files.newDirectoryStream(mDirectory))
        {
            for(Path path : files)
            {
                String name = path.getFileName().toString();

                if(name.endsWith(".tmp"))
                {
                    Files.deleteIfExists(path);
                    temporaryFiles++;
                }
                else if(name.endsWith(MANIFEST_SUFFIX))
                {
                    if(!loadManifest(path))
                    {
                        corruptEntries++;
                    }
                }
            }
        }

        removeMissingEntriesLocked();

        List<Entry> sorted = mState.entries.values().stream()
            .sorted(Comparator.comparingLong((Entry entry) -> entry.manifest().enqueuedAtMs())
                .thenComparing(entry -> entry.manifest().envelope().submissionId())).toList();
        mState.entries.clear();
        sorted.forEach(entry -> mState.entries.put(submissionId(entry), entry));
        rebuildReadinessIndexesLocked();
        int orphanFiles = removeOrphanedAudio();
        long now = System.currentTimeMillis();
        int expiredEntries = pruneLocked(now);
        mState.lastPruneAtMs = now;
        int capacityEvictions = evictFor(0L, null);

        return new RecoveryReport(corruptEntries, orphanFiles, temporaryFiles, expiredEntries,
            capacityEvictions);
    }

    EnqueueResult enqueue(Path sourceAudio, RadioResolveCallEnvelope envelope,
                          RadioResolveCallEnvelope.HoldContext holdContext, long now) throws IOException
    {
        synchronized(mState)
        {
            return enqueueLocked(sourceAudio, envelope, holdContext, now);
        }
    }

    private EnqueueResult enqueueLocked(Path sourceAudio, RadioResolveCallEnvelope envelope,
                                        RadioResolveCallEnvelope.HoldContext holdContext, long now) throws IOException
    {
        if(sourceAudio == null || !Files.isRegularFile(sourceAudio) || envelope == null)
        {
            throw new IOException("RadioResolve spool requires audio and a call manifest");
        }

        Files.createDirectories(mDirectory);
        pruneIfDue(now);

        if(mState.entries.containsKey(envelope.submissionId()))
        {
            //A submission UUID names one immutable transport attempt. Never replace its durable audio or manifest;
            //doing so would corrupt byte-stable retries and could make an in-flight acknowledgement delete new data.
            throw new IOException("RadioResolve submission is already present in the durable spool");
        }

        String base = String.format("%013d-%s", now, envelope.submissionId());
        Path audio = mDirectory.resolve(base + AUDIO_SUFFIX);
        Path manifestPath = mDirectory.resolve(base + MANIFEST_SUFFIX);
        Path audioTemporary = mDirectory.resolve(base + AUDIO_SUFFIX + ".tmp");
        Path manifestTemporary = mDirectory.resolve(base + MANIFEST_SUFFIX + ".tmp");
        Manifest manifest = new Manifest(SPOOL_VERSION, now, now, 0,
            now + RadioResolveBroadcaster.METADATA_HOLD_MILLISECONDS,
            holdContext != null ? holdContext : RadioResolveCallEnvelope.HoldContext.EMPTY, null, envelope);
        long audioBytes = Files.size(sourceAudio);
        long manifestBytes = RadioResolveJson.GSON.toJson(manifest).getBytes(StandardCharsets.UTF_8).length;
        long entryBytes = audioBytes + manifestBytes;

        if(audioBytes <= 0L || entryBytes > mMaximumBytes)
        {
            throw new IOException("RadioResolve call is larger than the spool capacity");
        }

        if(envelope.completedAtMs() <= 0L || now - envelope.completedAtMs() > mMaximumAgeMilliseconds)
        {
            throw new ExpiredCallException("RadioResolve call is older than the 24-hour spool window");
        }

        Entry entry;
        try
        {
            Files.copy(sourceAudio, audioTemporary, StandardCopyOption.REPLACE_EXISTING);
            force(audioTemporary);
            moveAtomically(audioTemporary, audio);
            writeManifest(manifestTemporary, manifestPath, manifest);
            forceDirectory();
            entry = new Entry(manifestPath, audio, manifest, Files.size(manifestPath) + Files.size(audio));
            mState.entries.put(envelope.submissionId(), entry);
            indexReadinessLocked(entry);
            mState.sizeBytes += entry.sizeBytes();
        }
        catch(IOException exception)
        {
            Files.deleteIfExists(audioTemporary);
            Files.deleteIfExists(manifestTemporary);
            Files.deleteIfExists(audio);
            Files.deleteIfExists(manifestPath);
            throw exception;
        }

        //The newest call must be completely durable and indexed before capacity eviction begins. This can exceed the
        //limit by one entry briefly, but a failed write can never sacrifice an older queued call. Keep the new entry
        //excluded so a trim failure or an in-flight older upload leaves the newly committed call recoverable.
        int evictedEntries = evictFor(0L, entry);
        return new EnqueueResult(entry, evictedEntries);
    }

    Entry first()
    {
        synchronized(mState)
        {
            return mState.entries.isEmpty() ? null : mState.entries.values().iterator().next();
        }
    }

    List<Entry> entries()
    {
        synchronized(mState)
        {
            return List.copyOf(mState.entries.values());
        }
    }

    /** Oldest upload-ready call without copying the complete outage backlog. */
    Entry firstReady()
    {
        synchronized(mState)
        {
            for(String submissionId : mState.readySubmissionIds)
            {
                Entry entry = mState.entries.get(submissionId);

                if(entry != null)
                {
                    return entry;
                }
            }

            return null;
        }
    }

    /**
     * Only unresolved calls are copied for placement checks. This set is naturally bounded by the separate
     * two-minute metadata deadline instead of growing with a 24-hour network outage.
     */
    List<Entry> heldEntries()
    {
        synchronized(mState)
        {
            return mState.heldSubmissionIds.stream().map(mState.entries::get)
                .filter(java.util.Objects::nonNull).toList();
        }
    }

    boolean protect(Entry entry)
    {
        synchronized(mState)
        {
            Entry current = find(entry);
            return current != null && Files.isRegularFile(current.audioPath()) &&
                Files.isRegularFile(current.manifestPath()) &&
                mState.protectedSubmissionIds.add(submissionId(current));
        }
    }

    int unprotect(Entry entry) throws IOException
    {
        synchronized(mState)
        {
            if(entry != null)
            {
                mState.protectedSubmissionIds.remove(submissionId(entry));
            }

            return evictFor(0L, null);
        }
    }

    boolean isProtected(Entry entry)
    {
        synchronized(mState)
        {
            return isProtectedLocked(entry);
        }
    }

    int size()
    {
        synchronized(mState)
        {
            return mState.entries.size();
        }
    }

    long sizeBytes()
    {
        synchronized(mState)
        {
            return mState.sizeBytes;
        }
    }

    Entry retry(Entry entry, long nextAttemptAtMs) throws IOException
    {
        synchronized(mState)
        {
            return retryLocked(entry, nextAttemptAtMs);
        }
    }

    private Entry retryLocked(Entry entry, long nextAttemptAtMs) throws IOException
    {
        Entry current = find(entry);

        if(current == null)
        {
            return null;
        }

        Manifest updated = new Manifest(SPOOL_VERSION, current.manifest().enqueuedAtMs(),
            Math.max(System.currentTimeMillis(), nextAttemptAtMs), current.manifest().attemptCount() + 1,
            current.manifest().metadataDeadlineMs(), current.manifest().holdContext(),
            current.manifest().appliedServerClockOffsetMs(), current.manifest().envelope());
        return replaceManifest(current, updated);
    }

    Entry updateEnvelope(Entry entry, RadioResolveCallEnvelope envelope) throws IOException
    {
        synchronized(mState)
        {
            return updateEnvelopeLocked(entry, envelope);
        }
    }

    private Entry updateEnvelopeLocked(Entry entry, RadioResolveCallEnvelope envelope) throws IOException
    {
        Entry current = find(entry);

        if(current == null || envelope == null)
        {
            return null;
        }

        Manifest updated = new Manifest(SPOOL_VERSION, current.manifest().enqueuedAtMs(),
            current.manifest().nextAttemptAtMs(), current.manifest().attemptCount(),
            current.manifest().metadataDeadlineMs(), current.manifest().holdContext(),
            current.manifest().appliedServerClockOffsetMs(), envelope);
        return replaceManifest(current, updated);
    }

    /**
     * Freezes one server-clock adjustment into the durable envelope before its first upload.  The marker and adjusted
     * timestamps share one atomically replaced manifest, so a retry or process restart can never apply the offset
     * twice or change the payload for the same submission UUID.
     */
    Entry applyServerClockOffset(Entry entry, long offsetMilliseconds) throws IOException
    {
        synchronized(mState)
        {
            Entry current = find(entry);

            if(current == null || current.manifest().appliedServerClockOffsetMs() != null)
            {
                return current;
            }

            if(isProtectedLocked(current))
            {
                return null;
            }

            RadioResolveCallEnvelope adjusted = current.manifest().envelope()
                .withTimestampOffset(offsetMilliseconds);
            Manifest updated = new Manifest(SPOOL_VERSION, current.manifest().enqueuedAtMs(),
                current.manifest().nextAttemptAtMs(), current.manifest().attemptCount(),
                current.manifest().metadataDeadlineMs(), current.manifest().holdContext(), offsetMilliseconds,
                adjusted);
            return replaceManifest(current, updated);
        }
    }

    void remove(Entry entry) throws IOException
    {
        synchronized(mState)
        {
            removeLocked(entry);
        }
    }

    private void removeLocked(Entry entry) throws IOException
    {
        Entry current = find(entry);

        if(current == null)
        {
            return;
        }

        IOException deletionFailure = null;

        try
        {
            Files.deleteIfExists(current.manifestPath());
        }
        catch(IOException exception)
        {
            deletionFailure = exception;
        }

        try
        {
            Files.deleteIfExists(current.audioPath());
        }
        catch(IOException exception)
        {
            if(deletionFailure == null)
            {
                deletionFailure = exception;
            }
            else
            {
                deletionFailure.addSuppressed(exception);
            }
        }

        String submissionId = submissionId(current);
        mState.entries.remove(submissionId);
        mState.readySubmissionIds.remove(submissionId);
        mState.heldSubmissionIds.remove(submissionId);
        mState.protectedSubmissionIds.remove(submissionId);
        mState.sizeBytes = Math.max(0L, mState.sizeBytes - current.sizeBytes());
        forceDirectory();

        if(deletionFailure != null)
        {
            throw deletionFailure;
        }
    }

    int prune(long now) throws IOException
    {
        synchronized(mState)
        {
            int removed = pruneLocked(now);
            mState.lastPruneAtMs = now;
            return removed;
        }
    }

    int pruneIfDue(long now) throws IOException
    {
        synchronized(mState)
        {
            if(now >= mState.lastPruneAtMs && now - mState.lastPruneAtMs < PRUNE_INTERVAL_MILLISECONDS)
            {
                return 0;
            }

            int removed = pruneLocked(now);
            mState.lastPruneAtMs = now;
            return removed;
        }
    }

    private int pruneLocked(long now) throws IOException
    {
        int removed = 0;
        List<Entry> snapshot = List.copyOf(mState.entries.values());

        for(Entry entry : snapshot)
        {
            //The envelope may use a server-normalized clock while enqueue time remains on this receiver's clock.
            //Retention is therefore measured from durable ownership, which begins within seconds of call completion.
            long durableOwnershipStartedAt = entry.manifest().enqueuedAtMs();

            if((durableOwnershipStartedAt <= 0L || now - durableOwnershipStartedAt > mMaximumAgeMilliseconds) &&
                !isProtectedLocked(entry))
            {
                remove(entry);
                removed++;
            }
        }

        return removed;
    }

    private int evictFor(long requiredBytes, Entry excluded) throws IOException
    {
        int evicted = 0;

        while(mState.sizeBytes > mMaximumBytes - requiredBytes)
        {
            Entry oldest = oldestUnprotectedLocked(excluded);

            if(oldest == null)
            {
                //The active upload cannot be deleted safely on every supported file system. Accept the newest call
                //with a bounded one-entry transient overage; unprotect() immediately evicts oldest-first afterward.
                break;
            }

            remove(oldest);
            evicted++;
        }

        return evicted;
    }

    private Entry oldestUnprotectedLocked(Entry excluded)
    {
        for(Entry entry : mState.entries.values())
        {
            if(entry != excluded && !isProtectedLocked(entry))
            {
                return entry;
            }
        }

        return null;
    }

    private boolean isProtectedLocked(Entry entry)
    {
        return entry != null && mState.protectedSubmissionIds.contains(submissionId(entry));
    }

    private boolean loadManifest(Path manifestPath) throws IOException
    {
        Manifest manifest;
        String fileName = manifestPath.getFileName().toString();
        Path audioPath = manifestPath.resolveSibling(fileName.substring(0,
            fileName.length() - MANIFEST_SUFFIX.length()) + AUDIO_SUFFIX);

        try
        {
            manifest = RadioResolveJson.GSON.fromJson(Files.readString(manifestPath, StandardCharsets.UTF_8),
                Manifest.class);
        }
        catch(IOException | RuntimeException exception)
        {
            Files.deleteIfExists(manifestPath);
            Files.deleteIfExists(audioPath);
            return false;
        }

        if(manifest == null || manifest.spoolVersion() != SPOOL_VERSION || manifest.envelope() == null ||
            !Files.isRegularFile(audioPath) || Files.size(audioPath) <= 0L)
        {
            Files.deleteIfExists(manifestPath);
            Files.deleteIfExists(audioPath);
            return false;
        }

        long size = Files.size(manifestPath) + Files.size(audioPath);
        Entry entry = new Entry(manifestPath, audioPath, manifest, size);
        String submissionId = submissionId(entry);

        Entry existing = mState.entries.get(submissionId);

        if(existing != null && !existing.manifestPath().equals(manifestPath))
        {
            Files.deleteIfExists(manifestPath);
            Files.deleteIfExists(audioPath);
            return false;
        }

        mState.entries.put(submissionId, entry);
        indexReadinessLocked(entry);
        mState.sizeBytes = Math.max(0L, mState.sizeBytes - (existing != null ? existing.sizeBytes() : 0L)) + size;
        return true;
    }

    private Entry replaceManifest(Entry current, Manifest updated) throws IOException
    {
        Path temporary = current.manifestPath().resolveSibling(current.manifestPath().getFileName() + ".tmp");
        //Placement and retry updates change only the bounded manifest. Do not evict a newer call to grow an older
        //manifest, especially while that older entry owns an async upload. The next enqueue/open/release performs
        //oldest-first trimming; this temporary overhead is bounded by one manifest.
        writeManifest(temporary, current.manifestPath(), updated);
        long updatedSize = Files.size(current.manifestPath()) + Files.size(current.audioPath());
        Entry replacement = new Entry(current.manifestPath(), current.audioPath(), updated, updatedSize);
        mState.entries.put(submissionId(current), replacement);
        indexReadinessLocked(replacement);
        mState.sizeBytes = Math.max(0L, mState.sizeBytes - current.sizeBytes()) + updatedSize;
        return replacement;
    }

    private Entry find(Entry entry)
    {
        if(entry == null)
        {
            return null;
        }

        return mState.entries.get(submissionId(entry));
    }

    private int removeOrphanedAudio() throws IOException
    {
        int removed = 0;

        Set<Path> ownedPaths = new HashSet<>();
        mState.entries.values().forEach(entry -> ownedPaths.add(entry.audioPath()));

        try(DirectoryStream<Path> files = Files.newDirectoryStream(mDirectory, "*" + AUDIO_SUFFIX))
        {
            for(Path audio : files)
            {
                if(!ownedPaths.contains(audio))
                {
                    Files.deleteIfExists(audio);
                    removed++;
                }
            }
        }

        return removed;
    }

    private void removeMissingEntriesLocked()
    {
        var iterator = mState.entries.entrySet().iterator();

        while(iterator.hasNext())
        {
            Map.Entry<String,Entry> indexed = iterator.next();
            Entry entry = indexed.getValue();

            if(!Files.isRegularFile(entry.manifestPath()) || !Files.isRegularFile(entry.audioPath()))
            {
                iterator.remove();
                mState.readySubmissionIds.remove(indexed.getKey());
                mState.heldSubmissionIds.remove(indexed.getKey());
                mState.protectedSubmissionIds.remove(indexed.getKey());
                mState.sizeBytes = Math.max(0L, mState.sizeBytes - entry.sizeBytes());
            }
        }
    }

    private static String submissionId(Entry entry)
    {
        return entry.manifest().envelope().submissionId();
    }

    private void rebuildReadinessIndexesLocked()
    {
        mState.readySubmissionIds.clear();
        mState.heldSubmissionIds.clear();
        mState.entries.values().forEach(this::indexReadinessLocked);
        mState.protectedSubmissionIds.removeIf(submissionId -> !mState.entries.containsKey(submissionId));
    }

    private void indexReadinessLocked(Entry entry)
    {
        if(entry == null)
        {
            return;
        }

        String submissionId = submissionId(entry);
        if(entry.manifest().envelope().isReady())
        {
            mState.heldSubmissionIds.remove(submissionId);
            mState.readySubmissionIds.add(submissionId);
        }
        else
        {
            mState.readySubmissionIds.remove(submissionId);
            mState.heldSubmissionIds.add(submissionId);
        }
    }

    private void writeManifest(Path temporary, Path target, Manifest manifest) throws IOException
    {
        Files.writeString(temporary, RadioResolveJson.GSON.toJson(manifest), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        force(temporary);
        moveAtomically(temporary, target);
        forceDirectory();
    }

    private static void moveAtomically(Path source, Path target) throws IOException
    {
        try
        {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        catch(AtomicMoveNotSupportedException _)
        {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void force(Path path) throws IOException
    {
        try(FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE))
        {
            channel.force(true);
        }
    }

    private void forceDirectory()
    {
        try(FileChannel channel = FileChannel.open(mDirectory, StandardOpenOption.READ))
        {
            channel.force(true);
        }
        catch(IOException | UnsupportedOperationException _)
        {
            //Some file systems do not expose directory fsync. Each owned file was forced before its atomic rename.
        }
    }

    private record SpoolKey(Path directory, long maximumAgeMilliseconds, long maximumBytes)
    {
    }

    /** Insertion-ordered FIFO with constant-time submission lookup/removal and one shared upload claim set. */
    private static final class SharedDirectoryState
    {
        private final LinkedHashMap<String,Entry> entries = new LinkedHashMap<>();
        private final LinkedHashSet<String> readySubmissionIds = new LinkedHashSet<>();
        private final LinkedHashSet<String> heldSubmissionIds = new LinkedHashSet<>();
        private final Set<String> protectedSubmissionIds = new HashSet<>();
        private long sizeBytes;
        private long lastPruneAtMs;
    }

    record Manifest(int spoolVersion, long enqueuedAtMs, long nextAttemptAtMs, int attemptCount,
                    long metadataDeadlineMs, RadioResolveCallEnvelope.HoldContext holdContext,
                    Long appliedServerClockOffsetMs, RadioResolveCallEnvelope envelope)
    {
    }

    record Entry(Path manifestPath, Path audioPath, Manifest manifest, long sizeBytes)
    {
    }

    record EnqueueResult(Entry entry, int evictedEntries)
    {
    }

    record RecoveryReport(int corruptEntries, int orphanFiles, int temporaryFiles, int expiredEntries,
                          int capacityEvictions)
    {
        int discardedCallEntries()
        {
            return corruptEntries + orphanFiles + expiredEntries + capacityEvictions;
        }

        boolean hasCleanup()
        {
            return discardedCallEntries() > 0 || temporaryFiles > 0;
        }
    }

    static class ExpiredCallException extends IOException
    {
        ExpiredCallException(String message)
        {
            super(message);
        }
    }
}
