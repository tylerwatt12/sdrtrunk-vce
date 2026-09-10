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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dsheirer.module.decode.DecoderType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RadioResolveSpoolTest
{
    private static final String EVIDENCE_SESSION = "99999999-8888-4777-8666-555555555555";

    @Test
    void productionBoundsAreFixedAtTwentyFourHoursAndTwoGiB()
    {
        assertEquals(TimeUnit.HOURS.toMillis(24), RadioResolveSpool.MAXIMUM_AGE_MILLISECONDS);
        assertEquals(2L * 1024L * 1024L * 1024L, RadioResolveSpool.MAXIMUM_BYTES);
    }

    @Test
    void conventionalCallEntersReadyIndexWithoutMetadataHold(@TempDir Path directory) throws Exception
    {
        Path source = audio(directory.resolve("conventional.mp3"), 64);
        RadioResolveCallEnvelope.BuildResult build = RadioResolveTestFixtures.conventionalBuild(source,
            DecoderType.NBFM);
        RadioResolveSpool spool = new RadioResolveSpool(directory.resolve("spool"));
        spool.open();

        RadioResolveSpool.Entry queued = spool.enqueue(source, build.envelope(), build.holdContext(),
            RadioResolveTestFixtures.END).entry();

        assertTrue(queued.manifest().envelope().isReady());
        assertEquals(RadioResolveCallEnvelope.HoldContext.EMPTY, queued.manifest().holdContext());
        assertEquals(queued, spool.firstReady());
        assertTrue(spool.heldEntries().isEmpty());
    }

    @Test
    void manifestOwnsEnvelopeEvidenceAndRetryIdentityAcrossRestart(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path audio = audio(directory.resolve("source.mp3"), 64);
        RadioResolveCallEnvelope.BuildResult build = RadioResolveTestFixtures.build(audio, now - 3_000L,
            uuid(1));
        RadioResolveCallEnvelope envelope = build.envelope().withVerifiedPlacements(
            RadioResolveTestFixtures.placement(), java.util.List.of());
        RadioResolveCallEnvelope.HoldContext hold = build.holdContext().withEvidenceSession(EVIDENCE_SESSION);
        RadioResolveSpool first = new RadioResolveSpool(directory.resolve("spool"));
        first.open();
        RadioResolveSpool.Entry queued = first.enqueue(audio, envelope, hold, now).entry();
        first.retry(queued, now + 5_000L);

        RadioResolveSpool reopened = new RadioResolveSpool(directory.resolve("spool"));
        reopened.open();
        RadioResolveSpool.Entry restored = reopened.first();
        assertEquals(envelope.submissionId(), restored.manifest().envelope().submissionId());
        assertEquals(envelope.reception().fingerprints(),
            restored.manifest().envelope().reception().fingerprints());
        assertEquals(EVIDENCE_SESSION,
            restored.manifest().holdContext().selected().evidenceSessionId());
        assertEquals(1, restored.manifest().attemptCount());
        assertTrue(Files.isRegularFile(restored.audioPath()));
    }

    @Test
    void duplicateSubmissionUuidCannotReplaceDurableAudioOrManifest(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path firstAudio = audio(directory.resolve("first.mp3"), 32);
        Path replacementAudio = audio(directory.resolve("replacement.mp3"), 96);
        String submissionId = uuid(1);
        RadioResolveSpool spool = new RadioResolveSpool(directory.resolve("spool"));
        spool.open();
        RadioResolveSpool.Entry original = spool.enqueue(firstAudio,
            RadioResolveTestFixtures.readyEnvelope(firstAudio, now - 3_000L, submissionId),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry();

        assertThrows(java.io.IOException.class, () -> spool.enqueue(replacementAudio,
            RadioResolveTestFixtures.readyEnvelope(replacementAudio, now - 2_000L, submissionId),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now + 1L));

        assertEquals(1, spool.size());
        assertEquals(original, spool.first());
        assertEquals(-1L, Files.mismatch(firstAudio, original.audioPath()));
        try(var files = Files.list(directory.resolve("spool")))
        {
            assertEquals(2L, files.count(),
                "one immutable manifest/audio pair must remain owned by the submission UUID");
        }
    }

    @Test
    void serverClockAdjustmentIsDurableAndCannotBeAppliedTwice(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path audio = audio(directory.resolve("source.mp3"), 64);
        RadioResolveCallEnvelope original = RadioResolveTestFixtures.readyEnvelope(audio, now - 3_000L, uuid(1));
        RadioResolveSpool spool = new RadioResolveSpool(directory.resolve("spool"));
        spool.open();
        RadioResolveSpool.Entry queued = spool.enqueue(audio, original,
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry();

        RadioResolveSpool.Entry adjusted = spool.applyServerClockOffset(queued, 275L);
        assertEquals(275L, adjusted.manifest().appliedServerClockOffsetMs());
        assertEquals(original.completedAtMs() + 275L, adjusted.manifest().envelope().completedAtMs());
        spool.retry(adjusted, now + 5_000L);

        RadioResolveSpool reopened = new RadioResolveSpool(directory.resolve("spool"));
        reopened.open();
        RadioResolveSpool.Entry restored = reopened.first();
        RadioResolveSpool.Entry secondAdjustment = reopened.applyServerClockOffset(restored, -900L);

        assertEquals(275L, secondAdjustment.manifest().appliedServerClockOffsetMs());
        assertEquals(original.completedAtMs() + 275L,
            secondAdjustment.manifest().envelope().completedAtMs(),
            "retry and restart must preserve the first payload for this submission UUID");
    }

    @Test
    void batchClockAdjustmentIsDurableOrderedAndCannotBeAppliedTwice(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path audio = audio(directory.resolve("source.mp3"), 64);
        Path spoolDirectory = directory.resolve("spool");
        RadioResolveSpool spool = new RadioResolveSpool(spoolDirectory);
        spool.open();
        List<RadioResolveSpool.Entry> queued = new ArrayList<>();

        for(int index = 1; index <= 4; index++)
        {
            queued.add(spool.enqueue(audio, RadioResolveTestFixtures.readyEnvelope(audio,
                now - 5_000L + index, uuid(index)), RadioResolveCallEnvelope.HoldContext.EMPTY,
                now + index).entry());
        }

        List<RadioResolveSpool.Entry> adjusted = spool.applyServerClockOffsetAll(queued, 275L);

        assertEquals(queued.stream().map(entry -> entry.manifest().envelope().submissionId()).toList(),
            adjusted.stream().map(entry -> entry.manifest().envelope().submissionId()).toList());
        assertTrue(adjusted.stream().allMatch(entry -> entry.manifest().appliedServerClockOffsetMs() == 275L));

        RadioResolveSpool reopened = new RadioResolveSpool(spoolDirectory);
        reopened.open();
        List<RadioResolveSpool.Entry> restored = reopened.entries();
        List<RadioResolveSpool.Entry> secondAdjustment = reopened.applyServerClockOffsetAll(restored, -900L);

        assertEquals(adjusted.stream().map(entry -> entry.manifest().envelope().completedAtMs()).toList(),
            secondAdjustment.stream().map(entry -> entry.manifest().envelope().completedAtMs()).toList(),
            "a replayed batch must keep the first byte-stable clock proof and call order");
        assertTrue(secondAdjustment.stream()
            .allMatch(entry -> entry.manifest().appliedServerClockOffsetMs() == 275L));
    }

    @Test
    void batchClockAdjustmentDoesNotPartiallyChangeAClaimedBatch(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path audio = audio(directory.resolve("source.mp3"), 64);
        RadioResolveSpool spool = new RadioResolveSpool(directory.resolve("spool"));
        spool.open();
        RadioResolveSpool.Entry first = spool.enqueue(audio,
            RadioResolveTestFixtures.readyEnvelope(audio, now - 4_000L, uuid(1)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry();
        RadioResolveSpool.Entry claimed = spool.enqueue(audio,
            RadioResolveTestFixtures.readyEnvelope(audio, now - 3_000L, uuid(2)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now + 1L).entry();
        assertTrue(spool.protect(claimed));

        assertTrue(spool.applyServerClockOffsetAll(List.of(first, claimed), 275L).isEmpty());
        assertNull(spool.entries().getFirst().manifest().appliedServerClockOffsetMs(),
            "preflight must reject the complete batch before changing an earlier entry");
        spool.unprotect(claimed);
    }

    @Test
    void serverClockAdjustmentDoesNotChangeFreshLaneEligibility(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path audio = audio(directory.resolve("source.mp3"), 64);
        RadioResolveCallEnvelope envelope = RadioResolveTestFixtures.readyEnvelope(audio, now - 3_000L, uuid(1));
        RadioResolveSpool spool = new RadioResolveSpool(directory.resolve("spool"));
        spool.open();
        RadioResolveSpool.Entry queued = spool.enqueue(audio, envelope,
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry();
        RadioResolveSpool.Entry adjusted = spool.applyServerClockOffset(queued, -TimeUnit.MINUTES.toMillis(4L));

        assertEquals(adjusted, spool.firstFresh(now,
            RadioResolveBroadcaster.LIVE_UPLOAD_PRIORITY_WINDOW_MILLISECONDS));
    }

    @Test
    void clockAdjustmentDoesNotShortenTheLocalTwentyFourHourOwnershipWindow(@TempDir Path directory)
        throws Exception
    {
        long now = 1_700_000_000_000L;
        Path audio = audio(directory.resolve("source.mp3"), 64);
        RadioResolveCallEnvelope envelope = RadioResolveTestFixtures.readyEnvelope(audio, now - 100L, uuid(1));
        RadioResolveSpool spool = new RadioResolveSpool(directory.resolve("spool"), 1_000L, 1_000_000L);
        spool.open();
        RadioResolveSpool.Entry queued = spool.enqueue(audio, envelope,
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry();
        spool.applyServerClockOffset(queued, -TimeUnit.HOURS.toMillis(1L));

        assertEquals(0, spool.prune(now + 1_000L));
        assertEquals(1, spool.prune(now + 1_001L));
    }

    @Test
    void enqueueEvictsOldestCompleteEntriesAndAcceptsNewest(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path source = audio(directory.resolve("source.mp3"), 128);
        Path sizingDirectory = directory.resolve("sizing");
        RadioResolveSpool sizing = new RadioResolveSpool(sizingDirectory, RadioResolveSpool.MAXIMUM_AGE_MILLISECONDS,
            1_000_000L);
        sizing.open();
        long oneEntryBytes = sizing.enqueue(source,
            RadioResolveTestFixtures.readyEnvelope(source, now - 3_000L, uuid(1)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry().sizeBytes();

        RadioResolveSpool spool = new RadioResolveSpool(directory.resolve("bounded"),
            RadioResolveSpool.MAXIMUM_AGE_MILLISECONDS, oneEntryBytes * 2 + 32L);
        spool.open();
        spool.enqueue(source, RadioResolveTestFixtures.readyEnvelope(source, now - 3_000L, uuid(1)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now);
        spool.enqueue(source, RadioResolveTestFixtures.readyEnvelope(source, now - 2_900L, uuid(2)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now + 1L);
        RadioResolveSpool.EnqueueResult newest = spool.enqueue(source,
            RadioResolveTestFixtures.readyEnvelope(source, now - 2_800L, uuid(3)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now + 2L);

        assertEquals(1, newest.evictedEntries());
        assertEquals(2, spool.size());
        assertEquals(uuid(2), spool.first().manifest().envelope().submissionId());
        assertTrue(spool.sizeBytes() <= oneEntryBytes * 2 + 32L);
    }

    @Test
    void failedNewWriteCannotEvictOlderDurableCall(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path source = audio(directory.resolve("source.mp3"), 128);
        RadioResolveSpool sizing = new RadioResolveSpool(directory.resolve("sizing"),
            RadioResolveSpool.MAXIMUM_AGE_MILLISECONDS, 1_000_000L);
        sizing.open();
        long oneEntryBytes = sizing.enqueue(source,
            RadioResolveTestFixtures.readyEnvelope(source, now - 3_000L, uuid(1)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry().sizeBytes();

        Path spoolDirectory = directory.resolve("bounded");
        RadioResolveSpool spool = new RadioResolveSpool(spoolDirectory,
            RadioResolveSpool.MAXIMUM_AGE_MILLISECONDS, oneEntryBytes + 8L);
        spool.open();
        RadioResolveSpool.Entry oldest = spool.enqueue(source,
            RadioResolveTestFixtures.readyEnvelope(source, now - 3_000L, uuid(1)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry();
        RadioResolveCallEnvelope newestEnvelope = RadioResolveTestFixtures.readyEnvelope(source,
            now - 2_000L, uuid(2));
        long newestEnqueuedAt = now + 1L;
        String newestBase = String.format("%013d-%s", newestEnqueuedAt, newestEnvelope.submissionId());
        Path blockedTemporaryAudio = spoolDirectory.resolve(newestBase + ".mp3.tmp");
        Files.createDirectory(blockedTemporaryAudio);
        Path blocker = blockedTemporaryAudio.resolve("blocker");
        Files.writeString(blocker, "force copy failure", StandardCharsets.UTF_8);

        assertThrows(java.io.IOException.class, () -> spool.enqueue(source, newestEnvelope,
            RadioResolveCallEnvelope.HoldContext.EMPTY, newestEnqueuedAt));
        assertEquals(1, spool.size());
        assertEquals(uuid(1), spool.first().manifest().envelope().submissionId());
        assertTrue(Files.isRegularFile(oldest.audioPath()));
        assertTrue(Files.isRegularFile(oldest.manifestPath()));

        Files.deleteIfExists(blocker);
        Files.deleteIfExists(blockedTemporaryAudio);
        RadioResolveSpool.EnqueueResult newest = spool.enqueue(source, newestEnvelope,
            RadioResolveCallEnvelope.HoldContext.EMPTY, newestEnqueuedAt);

        assertEquals(1, newest.evictedEntries());
        assertEquals(1, spool.size());
        assertEquals(uuid(2), spool.first().manifest().envelope().submissionId());
        assertFalse(Files.exists(oldest.audioPath()));
        assertFalse(Files.exists(oldest.manifestPath()));
        assertTrue(Files.isRegularFile(newest.entry().audioPath()));
        assertTrue(Files.isRegularFile(newest.entry().manifestPath()));
        assertTrue(spool.sizeBytes() <= oneEntryBytes + 8L);
    }

    @Test
    void rejectsOnlyAnIndividuallyOversizedNewestCall(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path small = audio(directory.resolve("small.mp3"), 32);
        RadioResolveCallEnvelope envelope = RadioResolveTestFixtures.readyEnvelope(small, now - 3_000L,
            uuid(1));
        long manifestBytes = RadioResolveJson.GSON.toJson(new RadioResolveSpool.Manifest(1, now, now, 0,
            now + RadioResolveBroadcaster.METADATA_HOLD_MILLISECONDS,
            RadioResolveCallEnvelope.HoldContext.EMPTY, null, envelope)).getBytes(StandardCharsets.UTF_8).length;
        RadioResolveSpool spool = new RadioResolveSpool(directory.resolve("tiny"),
            RadioResolveSpool.MAXIMUM_AGE_MILLISECONDS, manifestBytes + 16L);
        spool.open();

        assertThrows(java.io.IOException.class, () -> spool.enqueue(small, envelope,
            RadioResolveCallEnvelope.HoldContext.EMPTY, now));
        assertEquals(0, spool.size());
    }

    @Test
    void rejectsCallsOutsideTwentyFourHourWindow(@TempDir Path directory) throws Exception
    {
        Path audio = audio(directory.resolve("old.mp3"), 32);
        RadioResolveSpool spool = new RadioResolveSpool(directory.resolve("spool"));
        spool.open();

        assertThrows(RadioResolveSpool.ExpiredCallException.class, () -> spool.enqueue(audio,
            RadioResolveTestFixtures.readyEnvelope(audio), RadioResolveCallEnvelope.HoldContext.EMPTY,
            RadioResolveTestFixtures.END + RadioResolveSpool.MAXIMUM_AGE_MILLISECONDS + 1L));
        assertEquals(0, spool.size());
    }

    @Test
    void recoveryRemovesCorruptOrphanTemporaryAndExpiredFiles(@TempDir Path directory) throws Exception
    {
        Path spoolDirectory = directory.resolve("spool");
        Path source = audio(directory.resolve("old.mp3"), 32);
        RadioResolveSpool original = new RadioResolveSpool(spoolDirectory);
        original.open();
        original.enqueue(source, RadioResolveTestFixtures.readyEnvelope(source),
            RadioResolveCallEnvelope.HoldContext.EMPTY, RadioResolveTestFixtures.END);
        Files.writeString(spoolDirectory.resolve("broken.json"), "{not-json", StandardCharsets.UTF_8);
        Files.write(spoolDirectory.resolve("broken-utf8.json"), new byte[] {(byte)0xC3, 0x28});
        audio(spoolDirectory.resolve("orphan.mp3"), 8);
        Files.writeString(spoolDirectory.resolve("unfinished.tmp"), "partial", StandardCharsets.UTF_8);

        RadioResolveSpool recovered = new RadioResolveSpool(spoolDirectory);
        RadioResolveSpool.RecoveryReport report = recovered.open();

        assertEquals(2, report.corruptEntries());
        assertEquals(1, report.orphanFiles());
        assertEquals(1, report.temporaryFiles());
        assertEquals(1, report.expiredEntries());
        assertEquals(0, recovered.size());
    }

    @Test
    void largeRecoveryAndDrainUseIndexedEntries(@TempDir Path directory) throws Exception
    {
        int count = 250;
        long now = System.currentTimeMillis();
        Path spoolDirectory = directory.resolve("spool");
        Files.createDirectories(spoolDirectory);

        for(int index = 0; index < count; index++)
        {
            String id = uuid(index + 1L);
            RadioResolveCallEnvelope envelope = RadioResolveTestFixtures.readyEnvelope(
                directory.resolve("unused.mp3"), now - 3_000L, id);
            RadioResolveSpool.Manifest manifest = new RadioResolveSpool.Manifest(1, now + index, now + index,
                0, now + 120_000L, RadioResolveCallEnvelope.HoldContext.EMPTY, null, envelope);
            String base = String.format("%013d-%s", now + index, id);
            Files.write(spoolDirectory.resolve(base + ".mp3"), new byte[] {1});
            Files.writeString(spoolDirectory.resolve(base + ".json"), RadioResolveJson.GSON.toJson(manifest),
                StandardCharsets.UTF_8);
        }

        audio(spoolDirectory.resolve("orphan.mp3"), 1);
        RadioResolveSpool spool = new RadioResolveSpool(spoolDirectory);
        RadioResolveSpool.RecoveryReport report = spool.open();
        assertEquals(1, report.orphanFiles());
        assertEquals(count, spool.size());

        for(int index = 0; index < count; index++)
        {
            RadioResolveSpool.Entry head = spool.first();
            assertEquals(uuid(index + 1L), head.manifest().envelope().submissionId());
            spool.remove(head);
        }

        assertEquals(0, spool.size());
    }

    @Test
    void acknowledgedBatchRemovalIsSelectiveIdempotentAndSafeIfFilesReappear(@TempDir Path directory)
        throws Exception
    {
        long now = System.currentTimeMillis();
        Path source = audio(directory.resolve("source.mp3"), 32);
        Path spoolDirectory = directory.resolve("spool");
        Path crashImage = directory.resolve("crash-image");
        Files.createDirectories(crashImage);
        RadioResolveSpool spool = new RadioResolveSpool(spoolDirectory);
        spool.open();
        List<RadioResolveSpool.Entry> queued = new ArrayList<>();

        for(int index = 1; index <= 5; index++)
        {
            queued.add(spool.enqueue(source, RadioResolveTestFixtures.readyEnvelope(source,
                now - 5_000L + index, uuid(index)), RadioResolveCallEnvelope.HoldContext.EMPTY,
                now + index).entry());
        }

        List<RadioResolveSpool.Entry> acknowledged = List.of(queued.get(0), queued.get(2), queued.get(4));

        for(RadioResolveSpool.Entry entry : acknowledged)
        {
            Files.copy(entry.manifestPath(), crashImage.resolve(entry.manifestPath().getFileName()));
            Files.copy(entry.audioPath(), crashImage.resolve(entry.audioPath().getFileName()));
        }

        spool.removeAll(acknowledged);
        spool.removeAll(acknowledged);
        assertEquals(List.of(uuid(2), uuid(4)), spool.entries().stream()
            .map(entry -> entry.manifest().envelope().submissionId()).toList());

        //Model each crash outcome: one complete pair reappears, one has only its manifest, and one has only audio.
        //Only the complete immutable pair can be retried, with the same UUID for RadioResolve's server-side dedupe.
        RadioResolveSpool.Entry completePair = acknowledged.get(0);
        RadioResolveSpool.Entry manifestOnly = acknowledged.get(1);
        RadioResolveSpool.Entry audioOnly = acknowledged.get(2);
        Files.copy(crashImage.resolve(completePair.manifestPath().getFileName()), completePair.manifestPath());
        Files.copy(crashImage.resolve(completePair.audioPath().getFileName()), completePair.audioPath());
        Files.copy(crashImage.resolve(manifestOnly.manifestPath().getFileName()), manifestOnly.manifestPath());
        Files.copy(crashImage.resolve(audioOnly.audioPath().getFileName()), audioOnly.audioPath());

        RadioResolveSpool reopened = new RadioResolveSpool(spoolDirectory);
        RadioResolveSpool.RecoveryReport report = reopened.open();
        List<RadioResolveSpool.Entry> reappeared = reopened.entries().stream()
            .filter(entry -> entry.manifest().envelope().submissionId().equals(uuid(1))).toList();
        assertEquals(1, report.corruptEntries(), "a manifest without audio is discarded");
        assertEquals(1, report.orphanFiles(), "audio without a manifest is discarded");
        assertEquals(List.of(uuid(1)), reappeared.stream()
            .map(entry -> entry.manifest().envelope().submissionId()).toList());

        reopened.removeAll(reappeared);
        assertEquals(List.of(uuid(2), uuid(4)), reopened.entries().stream()
            .map(entry -> entry.manifest().envelope().submissionId()).toList());
    }

    @Test
    void replacementSharesOneAtomicUploadClaimAndIndex(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path source = audio(directory.resolve("source.mp3"), 32);
        Path spoolDirectory = directory.resolve("spool");
        RadioResolveSpool oldProvider = new RadioResolveSpool(spoolDirectory);
        oldProvider.open();
        RadioResolveSpool.Entry queued = oldProvider.enqueue(source,
            RadioResolveTestFixtures.readyEnvelope(source, now - 3_000L, uuid(1)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry();
        assertTrue(oldProvider.protect(queued));

        RadioResolveSpool replacement = new RadioResolveSpool(spoolDirectory);
        replacement.open();
        RadioResolveSpool.Entry sameEntry = replacement.first();
        assertEquals(queued.manifest().envelope().submissionId(),
            sameEntry.manifest().envelope().submissionId());
        assertFalse(replacement.protect(sameEntry),
            "provider replacement must not start a second upload for an in-flight entry");

        oldProvider.remove(queued);
        oldProvider.unprotect(queued);
        assertNull(replacement.first(), "the replacement shares removal state with the completing provider");
    }

    @Test
    void replacementCannotClaimAnyPartOfAnActiveBatch(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path source = audio(directory.resolve("source.mp3"), 32);
        Path spoolDirectory = directory.resolve("batch-claim");
        RadioResolveSpool firstProvider = new RadioResolveSpool(spoolDirectory);
        firstProvider.open();
        RadioResolveSpool.Entry first = firstProvider.enqueue(source,
            RadioResolveTestFixtures.readyEnvelope(source, now - 3_000L, uuid(1)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry();
        RadioResolveSpool.Entry second = firstProvider.enqueue(source,
            RadioResolveTestFixtures.readyEnvelope(source, now - 2_000L, uuid(2)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now + 1L).entry();
        assertTrue(firstProvider.protectAll(List.of(first, second)));

        RadioResolveSpool replacement = new RadioResolveSpool(spoolDirectory);
        replacement.open();
        assertFalse(replacement.protectAll(replacement.entries()));
        assertTrue(replacement.entries().stream().allMatch(replacement::isProtected));

        firstProvider.unprotectAll(List.of(first, second));
        assertTrue(replacement.protectAll(replacement.entries()));
        replacement.unprotectAll(replacement.entries());
    }

    @Test
    void newestCallIsAcceptedWhileOldestUploadTemporarilyOwnsCapacity(@TempDir Path directory) throws Exception
    {
        long now = System.currentTimeMillis();
        Path source = audio(directory.resolve("source.mp3"), 64);
        Path sizingDirectory = directory.resolve("sizing");
        RadioResolveSpool sizing = new RadioResolveSpool(sizingDirectory,
            RadioResolveSpool.MAXIMUM_AGE_MILLISECONDS, 1_000_000L);
        sizing.open();
        long entryBytes = sizing.enqueue(source,
            RadioResolveTestFixtures.readyEnvelope(source, now - 3_000L, uuid(1)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry().sizeBytes();
        RadioResolveSpool spool = new RadioResolveSpool(directory.resolve("bounded"),
            RadioResolveSpool.MAXIMUM_AGE_MILLISECONDS, entryBytes + 8L);
        spool.open();
        RadioResolveSpool.Entry inFlight = spool.enqueue(source,
            RadioResolveTestFixtures.readyEnvelope(source, now - 3_000L, uuid(1)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now).entry();
        assertTrue(spool.protect(inFlight));

        RadioResolveSpool.EnqueueResult newest = spool.enqueue(source,
            RadioResolveTestFixtures.readyEnvelope(source, now - 2_000L, uuid(2)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now + 1L);

        assertEquals(uuid(2), newest.entry().manifest().envelope().submissionId());
        assertEquals(1, spool.unprotect(inFlight));
        assertEquals(uuid(2), spool.first().manifest().envelope().submissionId());
        assertTrue(spool.sizeBytes() <= entryBytes + 8L);
    }

    @Test
    void hotPollAndEnqueuePruningIsThrottledButExplicitPruneRemainsAvailable(@TempDir Path directory)
        throws Exception
    {
        long now = System.currentTimeMillis();
        Path source = audio(directory.resolve("source.mp3"), 32);
        RadioResolveSpool spool = new RadioResolveSpool(directory.resolve("spool"), 100L, 1_000_000L);
        spool.open();
        spool.enqueue(source, RadioResolveTestFixtures.readyEnvelope(source, now - 2_050L, uuid(1)),
            RadioResolveCallEnvelope.HoldContext.EMPTY, now);

        assertEquals(0, spool.pruneIfDue(now + 101L),
            "the 500 ms processor poll must not scan the complete outage backlog");
        assertEquals(1, spool.size());
        assertEquals(1, spool.prune(now + 101L), "explicit recovery/test pruning remains immediate");
        assertEquals(0, spool.size());
    }

    @Test
    void readyAndHeldIndexesPreserveReadyFifoWithoutCopyingTheOutageBacklog(@TempDir Path directory)
        throws Exception
    {
        long now = System.currentTimeMillis();
        Path source = audio(directory.resolve("source.mp3"), 32);
        RadioResolveSpool spool = new RadioResolveSpool(directory.resolve("spool"));
        spool.open();
        RadioResolveCallEnvelope.BuildResult held = RadioResolveTestFixtures.build(source, now - 4_000L,
            uuid(1));
        spool.enqueue(source, held.envelope(), held.holdContext().withEvidenceSession(EVIDENCE_SESSION), now);
        RadioResolveCallEnvelope firstReady = RadioResolveTestFixtures.readyEnvelope(source, now - 3_000L,
            uuid(2));
        RadioResolveCallEnvelope secondReady = RadioResolveTestFixtures.readyEnvelope(source, now - 2_000L,
            uuid(3));
        spool.enqueue(source, firstReady, RadioResolveCallEnvelope.HoldContext.EMPTY, now + 1L);
        spool.enqueue(source, secondReady, RadioResolveCallEnvelope.HoldContext.EMPTY, now + 2L);

        assertEquals(1, spool.heldEntries().size());
        assertEquals(uuid(1), spool.heldEntries().getFirst().manifest().envelope().submissionId());
        assertEquals(uuid(2), spool.firstReady().manifest().envelope().submissionId());

        spool.remove(spool.firstReady());
        assertEquals(uuid(3), spool.firstReady().manifest().envelope().submissionId());

        RadioResolveCallEnvelope resolved = held.envelope().withVerifiedPlacements(
            RadioResolveTestFixtures.placement(), java.util.List.of(
                new RadioResolveCallEnvelope.IndexedPlacement(0, RadioResolveTestFixtures.placement())));
        spool.updateEnvelope(spool.heldEntries().getFirst(), resolved);
        assertTrue(spool.heldEntries().isEmpty());
        assertEquals(uuid(3), spool.firstReady().manifest().envelope().submissionId(),
            "a newly resolved older hold joins behind calls that were already upload-ready");
    }

    private static Path audio(Path path, int bytes) throws Exception
    {
        Files.write(path, new byte[Math.max(1, bytes)]);
        return path;
    }

    private static String uuid(long value)
    {
        return new UUID(0L, value).toString();
    }
}
