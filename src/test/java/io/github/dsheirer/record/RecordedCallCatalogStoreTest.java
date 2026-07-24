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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.dsheirer.audio.AudioFormats;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallRecordingMetadata;
import io.github.dsheirer.database.SdrTrunkDatabase;
import io.github.dsheirer.database.SdrTrunkDatabaseStartup;
import io.github.dsheirer.record.wave.AudioMetadata;
import io.github.dsheirer.record.wave.AudioMetadataUtils;
import io.github.dsheirer.record.wave.WaveWriter;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordedCallCatalogStoreTest
{
    private static final long COMPLETED = Instant.parse("2026-07-01T12:34:56.789Z").toEpochMilli();
    private static final AudioCallId CALL_ID = new AudioCallId(0x10203040L, 77, 1);

    @TempDir
    Path mTemporaryFolder;
    private Path mDatabase;
    private Path mRecordingRoot;
    private RecordedCallCatalogStore mStore;

    @BeforeEach
    void setUp() throws Exception
    {
        mDatabase = mTemporaryFolder.resolve("database/sdrtrunk.sqlite");
        mRecordingRoot = mTemporaryFolder.resolve("recordings");
        Files.createDirectories(mRecordingRoot);
        SdrTrunkDatabaseStartup.createGlobalDatabase(mDatabase);
        mStore = new RecordedCallCatalogStore(mRecordingRoot);
    }

    @Test
    void durableIdentityIncludesCompletionTimeAndPagesWithOpaqueTokens() throws Exception
    {
        RecordedCallArtifact first = artifact(CALL_ID, COMPLETED, true);
        RecordedCallArtifact afterRestart = artifact(CALL_ID, COMPLETED + 1_000, true);

        try(Connection connection = SdrTrunkDatabase.open(mDatabase))
        {
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED, mStore.admit(connection, first));
            assertEquals(RecordedCallCatalogStore.AdmissionResult.DUPLICATE, mStore.admit(connection, first));
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED,
                mStore.admit(connection, afterRestart),
                "a restarted producer can reuse its runtime AudioCallId at a different completion time");

            RecordedCallCatalogSearch search =
                RecordedCallCatalogSearch.recent(COMPLETED - 1, COMPLETED + 2_000, 1);
            RecordedCallCatalogPage pageOne = mStore.search(connection, search);
            assertEquals(1, pageOne.calls().size());
            assertNotNull(pageOne.nextCursor());
            assertTrue(pageOne.calls().get(0).id().startsWith("c1_"));
            assertFalse(pageOne.calls().get(0).id().contains(CALL_ID.toString()));

            RecordedCallCatalogSearch pageTwoSearch = new RecordedCallCatalogSearch(
                null, null, null, null, null, COMPLETED - 1, COMPLETED + 2_000, 0,
                RecordedCallCatalogSearch.MAXIMUM_CALL_DURATION_MS, 1, pageOne.nextCursor());
            RecordedCallCatalogPage pageTwo = mStore.search(connection, pageTwoSearch);
            assertEquals(1, pageTwo.calls().size());
            assertNull(pageTwo.nextCursor());
            assertNotEquals(pageOne.calls().get(0).id(), pageTwo.calls().get(0).id());
            assertEquals(2, count(connection, "recorded_call"));
        }
    }

    @Test
    void forwardSearchPagesEqualCompletionTimesWithoutDuplicatesOrOmissions() throws Exception
    {
        List<RecordedCallArtifact> artifacts = List.of(
            artifact(new AudioCallId(2, 1, 0), COMPLETED, true),
            artifact(new AudioCallId(1, 2, 0), COMPLETED, true),
            artifact(new AudioCallId(1, 1, 1), COMPLETED, true),
            artifact(new AudioCallId(1, 1, 0), COMPLETED, true),
            artifact(new AudioCallId(1, 0, 0), COMPLETED - 1, true),
            artifact(new AudioCallId(3, 0, 0), COMPLETED + 1, true));

        try(Connection connection = SdrTrunkDatabase.open(mDatabase))
        {
            for(RecordedCallArtifact artifact: artifacts)
            {
                assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED,
                    mStore.admit(connection, artifact));
            }

            RecordedCallCatalogSearch filters = RecordedCallCatalogSearch.recent(
                COMPLETED - 2, COMPLETED + 2, 2);
            RecordedCallCatalogSearch.Cursor after = null;
            List<RecordedCallCatalogTokens.CursorValues> returned = new ArrayList<>();

            do
            {
                RecordedCallCatalogPage page = mStore.searchForward(connection, filters, after);
                page.calls().stream()
                    .map(call -> RecordedCallCatalogTokens.parseCallId(call.id()))
                    .forEach(returned::add);
                after = page.nextCursor();
            }
            while(after != null);

            assertEquals(6, returned.size());
            assertEquals(List.of(
                    new RecordedCallCatalogTokens.CursorValues(COMPLETED - 1, new AudioCallId(1, 0, 0)),
                    new RecordedCallCatalogTokens.CursorValues(COMPLETED, new AudioCallId(1, 1, 0)),
                    new RecordedCallCatalogTokens.CursorValues(COMPLETED, new AudioCallId(1, 1, 1)),
                    new RecordedCallCatalogTokens.CursorValues(COMPLETED, new AudioCallId(1, 2, 0)),
                    new RecordedCallCatalogTokens.CursorValues(COMPLETED, new AudioCallId(2, 1, 0)),
                    new RecordedCallCatalogTokens.CursorValues(COMPLETED + 1, new AudioCallId(3, 0, 0))),
                returned);
            assertEquals(6, returned.stream().distinct().count());

            List<RecordedCallCatalogTokens.CursorValues> newest = mStore.search(connection,
                    RecordedCallCatalogSearch.recent(COMPLETED - 2, COMPLETED + 2, 10)).calls().stream()
                .map(call -> RecordedCallCatalogTokens.parseCallId(call.id()))
                .toList();
            List<RecordedCallCatalogTokens.CursorValues> reversed = new ArrayList<>(returned);
            Collections.reverse(reversed);
            assertEquals(reversed, newest, "the existing newest-first search remains unchanged");

            RecordedCallCatalogSearch newestCursor = new RecordedCallCatalogSearch(null, null, null, null, null,
                COMPLETED - 2, COMPLETED + 2, 0, RecordedCallCatalogSearch.MAXIMUM_CALL_DURATION_MS, 2,
                RecordedCallCatalogSearch.Cursor.create(returned.get(1).completedAtMs(),
                    returned.get(1).callId()));
            assertThrows(IllegalArgumentException.class,
                () -> mStore.searchForward(connection, newestCursor, null));
        }
    }

    @Test
    void batchResolutionIsAlignedBoundedCanonicalAndPathFree() throws Exception
    {
        RecordedCallArtifact first = artifact(new AudioCallId(10, 1, 0), COMPLETED, true);
        RecordedCallArtifact second = artifact(new AudioCallId(10, 2, 0), COMPLETED + 1, true);
        RecordedCallArtifact ineligible = artifact(new AudioCallId(10, 3, 0), COMPLETED + 2, false);

        try(Connection connection = SdrTrunkDatabase.open(mDatabase))
        {
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED, mStore.admit(connection, first));
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED, mStore.admit(connection, second));
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED, mStore.admit(connection, ineligible));
            String firstId = RecordedCallCatalogTokens.callId(first.completedAtMs(), first.callId());
            String secondId = RecordedCallCatalogTokens.callId(second.completedAtMs(), second.callId());
            String missingId = RecordedCallCatalogTokens.callId(COMPLETED + 3, new AudioCallId(10, 4, 0));
            String ineligibleId =
                RecordedCallCatalogTokens.callId(ineligible.completedAtMs(), ineligible.callId());
            List<Optional<RecordedCallCatalogMetadata>> resolved =
                mStore.resolveCalls(connection, List.of(secondId, missingId, firstId, ineligibleId));

            assertEquals(4, resolved.size());
            assertEquals(secondId, resolved.get(0).orElseThrow().id());
            assertTrue(resolved.get(1).isEmpty());
            assertEquals(firstId, resolved.get(2).orElseThrow().id());
            assertTrue(resolved.get(3).isEmpty(),
                "non-record-eligible calls must be indistinguishable from unavailable calls");
            assertTrue(Arrays.stream(RecordedCallCatalogMetadata.class.getRecordComponents())
                .noneMatch(component -> Path.class.isAssignableFrom(component.getType())));

            try(PreparedStatement expired = connection.prepareStatement("""
                DELETE FROM recorded_call
                WHERE completed_at_ms = ? AND producer_id = ? AND call_sequence = ? AND timeslot = ?
                """))
            {
                expired.setLong(1, second.completedAtMs());
                expired.setLong(2, second.callId().producerId());
                expired.setLong(3, second.callId().sequence());
                expired.setInt(4, second.callId().timeslot());
                assertEquals(1, expired.executeUpdate());
            }

            assertTrue(mStore.resolveCalls(connection, List.of(secondId, missingId, ineligibleId)).stream()
                .allMatch(Optional::isEmpty),
                "expired, missing, and non-record-eligible IDs must have the same unavailable result");
            assertThrows(IllegalArgumentException.class,
                () -> mStore.resolveCalls(connection, List.of(firstId, firstId)));
            assertThrows(IllegalArgumentException.class,
                () -> mStore.resolveCalls(connection, List.of(firstId + "=")));
            assertThrows(IllegalArgumentException.class,
                () -> mStore.resolveCalls(connection, null));
            assertTrue(mStore.resolveCalls(connection, List.of()).isEmpty());

            List<String> maximum = new ArrayList<>(RecordedCallCatalogStore.MAXIMUM_BATCH_SIZE);

            for(int index = 0; index < RecordedCallCatalogStore.MAXIMUM_BATCH_SIZE; index++)
            {
                maximum.add(RecordedCallCatalogTokens.callId(COMPLETED + 10_000 + index,
                    new AudioCallId(99, index, 0)));
            }

            RecordedCallCatalogStore.SearchStatement statement =
                RecordedCallCatalogStore.buildBatchResolveStatement(maximum);
            assertEquals(1 + RecordedCallCatalogStore.MAXIMUM_BATCH_SIZE * 4,
                statement.parameters().size());
            assertTrue(statement.parameters().size() <= 999);
            List<Optional<RecordedCallCatalogMetadata>> maximumResult =
                mStore.resolveCalls(connection, maximum);
            assertEquals(RecordedCallCatalogStore.MAXIMUM_BATCH_SIZE, maximumResult.size());
            assertTrue(maximumResult.stream().allMatch(Optional::isEmpty));

            List<String> tooMany = new ArrayList<>(maximum);
            tooMany.add(RecordedCallCatalogTokens.callId(COMPLETED + 20_000,
                new AudioCallId(99, RecordedCallCatalogStore.MAXIMUM_BATCH_SIZE, 0)));
            assertThrows(IllegalArgumentException.class,
                () -> mStore.resolveCalls(connection, tooMany));
        }
    }

    @Test
    void supportsEveryBoundedFacetAndDurationFilter() throws Exception
    {
        try(Connection connection = SdrTrunkDatabase.open(mDatabase))
        {
            RecordedCallArtifact artifact = artifact(CALL_ID, COMPLETED, true);
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED,
                mStore.admit(connection, artifact));
            RecordedCallCatalogEntry call = mStore.search(connection,
                RecordedCallCatalogSearch.recent(COMPLETED - 1, COMPLETED + 1, 10)).calls().get(0);
            assertEquals(artifact.path().toRealPath(),
                mStore.resolveMedia(connection, call.id()).orElseThrow().toRealPath());

            assertEquals(1, filtered(connection, call.system().valueKey(), null, null, null, null));
            assertEquals(1, filtered(connection, null, call.site().valueKey(), null, null, null));
            assertEquals(1, filtered(connection, call.system().valueKey(), null,
                call.talkgroup().valueKey(), null, null));
            assertEquals(1, filtered(connection, null, null, null, call.channel().valueKey(), null));
            assertEquals(1, filtered(connection, call.system().valueKey(), null, null, null,
                call.sourceRadio().valueKey()));

            RecordedCallCatalogSearch duration = new RecordedCallCatalogSearch(null, null, null, null, null,
                COMPLETED - 1, COMPLETED + 1, 2_000, 2_000, 10, null);
            assertEquals(1, mStore.search(connection, duration).calls().size());
            assertTrue(mStore.search(connection, new RecordedCallCatalogSearch(null, null, null, null, null,
                COMPLETED - 1, COMPLETED + 1, 2_001, 3_000, 10, null)).calls().isEmpty());

            assertEquals(1, mStore.listIdentities(connection, RecordedCallIdentityKind.SYSTEM, "", "", 10).size());
            assertEquals(1, mStore.listIdentities(connection, RecordedCallIdentityKind.SITE,
                call.system().valueKey(), "", 10).size());
            assertEquals(1, mStore.listIdentities(connection, RecordedCallIdentityKind.CHANNEL,
                call.site().valueKey(), "", 10).size());
            assertEquals(1, mStore.listIdentities(connection, RecordedCallIdentityKind.TALKGROUP,
                call.system().valueKey(), "", 10).size());
            var radios = mStore.listIdentities(connection, RecordedCallIdentityKind.RADIO,
                call.system().valueKey(), "", 10);
            assertEquals(1, radios.size());
            assertEquals("APCO25:16777201", radios.getFirst().displayLabel());
        }
    }

    @Test
    void retainsButDoesNotExposeChannelLevelRecordingWithoutDestinationRecordFlag() throws Exception
    {
        RecordedCallArtifact artifact = artifact(CALL_ID, COMPLETED, false);

        try(Connection connection = SdrTrunkDatabase.open(mDatabase))
        {
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED,
                mStore.admit(connection, artifact));
            assertEquals(1, count(connection, "recorded_call"));
            assertEquals(1, count(connection, "recorded_call_bucket"));
            assertEquals(artifact.byteSize(), mStore.totalRetainedBytes(connection));
            assertTrue(mStore.search(connection,
                RecordedCallCatalogSearch.recent(COMPLETED - 1, COMPLETED + 1, 10)).calls().isEmpty());

            for(RecordedCallIdentityKind kind: RecordedCallIdentityKind.values())
            {
                assertTrue(mStore.listIdentities(connection, kind, "", "", 10).isEmpty());
            }

            String publicId = RecordedCallCatalogTokens.callId(COMPLETED, CALL_ID);
            assertTrue(mStore.resolveMedia(connection, publicId).isEmpty());

            RecordedCallCatalogStore.RetentionResult result =
                mStore.cleanupRetention(connection, COMPLETED + 1, 100);
            assertEquals(1, result.rowsDeleted());
            assertEquals(1, result.filesDeleted());
            assertFalse(Files.exists(artifact.path()));
            assertEquals(0, count(connection, "recorded_call"));
            assertEquals(0, count(connection, "recorded_call_bucket"));
        }
    }

    @Test
    void retentionDeletesAudioRowsAndOrphanedDirectoryBuckets() throws Exception
    {
        RecordedCallArtifact artifact = artifact(CALL_ID, COMPLETED, true);

        try(Connection connection = SdrTrunkDatabase.open(mDatabase))
        {
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED, mStore.admit(connection, artifact));
            RecordedCallCatalogStore.RetentionResult result =
                mStore.cleanupRetention(connection, COMPLETED + 1, 100);

            assertEquals(1, result.rowsDeleted());
            assertEquals(1, result.filesDeleted());
            assertFalse(Files.exists(artifact.path()));
            assertEquals(0, count(connection, "recorded_call"));
            assertEquals(0, count(connection, "recorded_call_bucket"));
        }
    }

    @Test
    void storageCapDeletesOnlyEnoughOldestCallsInOneBoundedPass() throws Exception
    {
        RecordedCallArtifact first = artifact(CALL_ID, COMPLETED, true);
        RecordedCallArtifact second = artifact(new AudioCallId(0x10203040L, 78, 1), COMPLETED + 1, true);
        RecordedCallArtifact third = artifact(new AudioCallId(0x10203040L, 79, 1), COMPLETED + 2, true);

        try(Connection connection = SdrTrunkDatabase.open(mDatabase))
        {
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED, mStore.admit(connection, first));
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED, mStore.admit(connection, second));
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED, mStore.admit(connection, third));
            assertEquals(192, mStore.totalRetainedBytes(connection));

            RecordedCallCatalogStore.RetentionResult result =
                mStore.cleanupRetention(connection, COMPLETED - 10_000, 192, 100, 100);

            assertEquals(2, result.rowsDeleted());
            assertEquals(128, result.bytesRemoved());
            assertEquals(64, mStore.totalRetainedBytes(connection));
            assertFalse(Files.exists(first.path()));
            assertFalse(Files.exists(second.path()));
            assertTrue(Files.exists(third.path()));
        }
    }

    @Test
    void recoveryRequiresManifestPathIdentityTimeFormatAndEligibilityAgreement() throws Exception
    {
        RecordedCallManifest validManifest = new RecordedCallManifest(CALL_ID, metadata(true),
            COMPLETED - 2_000, COMPLETED, 2_000, false, true);
        Path valid = writeManifestWave(CALL_ID, COMPLETED, validManifest);
        assertNull(mStore.prepareRecovered(valid).result());

        RecordedCallManifest wrongCall = new RecordedCallManifest(new AudioCallId(5, 6, 1), metadata(true),
            COMPLETED - 2_000, COMPLETED, 2_000, false, true);
        Path wrongIdentityPath = canonicalPath(CALL_ID, COMPLETED + 1_000, RecordFormat.WAVE);
        writeManifestWave(wrongIdentityPath, wrongCall);
        assertEquals(RecordedCallCatalogStore.AdmissionResult.INVALID_ARTIFACT,
            mStore.prepareRecovered(wrongIdentityPath).result());

        AudioCallId technicalCallId = new AudioCallId(0x10203040L, 78, 1);
        RecordedCallManifest technical = new RecordedCallManifest(technicalCallId, metadata(false),
            COMPLETED + 1_000, COMPLETED + 2_000, 1_000, false, false);
        Path technicalPath = canonicalPath(technicalCallId, COMPLETED + 2_000, RecordFormat.WAVE);
        writeManifestWave(technicalPath, technical);
        RecordedCallCatalogStore.PreparedAdmission recoveredTechnical = mStore.prepareRecovered(technicalPath);
        assertNull(recoveredTechnical.result());
        assertFalse(recoveredTechnical.artifact().destinationTalkgroupRecordEnabled());

        RecordedCallManifest eligibilityMismatch = new RecordedCallManifest(CALL_ID, metadata(true),
            COMPLETED + 2_000, COMPLETED + 3_000, 1_000, false, false);
        Path ineligiblePath = canonicalPath(CALL_ID, COMPLETED + 3_000, RecordFormat.WAVE);
        writeManifestWave(ineligiblePath, eligibilityMismatch);
        assertEquals(RecordedCallCatalogStore.AdmissionResult.INVALID_ARTIFACT,
            mStore.prepareRecovered(ineligiblePath).result());
    }

    @Test
    void configuredRootSymlinkUsesOneRealRootForAdmissionAndRetention() throws Exception
    {
        Path realRoot = mTemporaryFolder.resolve("real-recordings");
        Path aliasRoot = mTemporaryFolder.resolve("recordings-alias");
        Files.createDirectories(realRoot);

        try
        {
            Files.createSymbolicLink(aliasRoot, realRoot);
        }
        catch(UnsupportedOperationException | java.io.IOException exception)
        {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        RecordedCallCatalogStore aliasStore = new RecordedCallCatalogStore(aliasRoot);
        RecordedCallArtifact artifact = artifact(aliasRoot, CALL_ID, COMPLETED + 10_000, true);

        try(Connection connection = SdrTrunkDatabase.open(mDatabase))
        {
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED,
                aliasStore.admit(connection, artifact));
            RecordedCallCatalogStore.RetentionResult result =
                aliasStore.cleanupRetention(connection, COMPLETED + 10_001, 100);
            assertEquals(1, result.filesDeleted());
            assertEquals(1, result.rowsDeleted());
            assertFalse(Files.exists(realRoot.resolve(artifact.relativePath())));
        }
    }

    @Test
    void unsafeManagedTreeLinkKeepsCatalogOwnershipRow() throws Exception
    {
        RecordedCallArtifact artifact = artifact(CALL_ID, COMPLETED, true);

        try(Connection connection = SdrTrunkDatabase.open(mDatabase))
        {
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED,
                mStore.admit(connection, artifact));
            Path managedLeaf = artifact.path().getParent();
            Path heldLeaf = managedLeaf.resolveSibling(managedLeaf.getFileName() + "-held");
            Path outside = mTemporaryFolder.resolve("outside");
            Files.move(managedLeaf, heldLeaf);
            Files.createDirectories(outside);
            Path outsideFile = Files.write(outside.resolve(artifact.path().getFileName()), new byte[] {9, 8, 7});

            try
            {
                Files.createSymbolicLink(managedLeaf, outside);
            }
            catch(UnsupportedOperationException | java.io.IOException exception)
            {
                assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
            }

            RecordedCallCatalogStore.RetentionResult result =
                mStore.cleanupRetention(connection, COMPLETED + 1, 100);
            assertEquals(1, result.candidates());
            assertEquals(0, result.rowsDeleted());
            assertEquals(0, result.filesMissing());
            assertEquals(1, result.fileFailures());
            assertEquals(1, count(connection, "recorded_call"));
            assertTrue(Files.exists(outsideFile), "Retention must not follow a managed-tree link");
        }
    }

    @Test
    void retentionContinuesPastUnsafeOldestPrefixToLaterExpiredCall() throws Exception
    {
        RecordedCallArtifact unsafe = artifact(CALL_ID, COMPLETED, true);
        RecordedCallArtifact removable = artifact(new AudioCallId(0x10203040L, 78, 1),
            COMPLETED + TimeUnit.DAYS.toMillis(1), true);

        try(Connection connection = SdrTrunkDatabase.open(mDatabase))
        {
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED, mStore.admit(connection, unsafe));
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED, mStore.admit(connection, removable));
            Path outsideFile = replaceLeafWithOutsideLink(unsafe);
            long cutoff = removable.completedAtMs() + 1;

            RecordedCallCatalogStore.RetentionResult first =
                mStore.cleanupRetention(connection, cutoff, 128, Long.MAX_VALUE, 1, null);
            assertEquals(0, first.rowsDeleted());
            assertEquals(1, first.fileFailures());
            assertTrue(first.moreWork());
            assertNotNull(first.nextCursor());

            RecordedCallCatalogStore.RetentionResult second =
                mStore.cleanupRetention(connection, cutoff, 128, Long.MAX_VALUE, 1, first.nextCursor());
            assertEquals(1, second.rowsDeleted());
            assertEquals(1, second.filesDeleted());
            assertFalse(second.moreWork());
            assertTrue(Files.exists(outsideFile), "Retention must preserve the unsafe oldest ownership row");
            assertFalse(Files.exists(removable.path()));
            assertEquals(1, count(connection, "recorded_call"));
        }
    }

    @Test
    void storageCapContinuesPastUnsafeOldestPrefixToLaterSafeCall() throws Exception
    {
        RecordedCallArtifact unsafe = artifact(CALL_ID, COMPLETED, true);
        RecordedCallArtifact removable = artifact(new AudioCallId(0x10203040L, 78, 1),
            COMPLETED + TimeUnit.DAYS.toMillis(1), true);

        try(Connection connection = SdrTrunkDatabase.open(mDatabase))
        {
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED, mStore.admit(connection, unsafe));
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED, mStore.admit(connection, removable));
            Path outsideFile = replaceLeafWithOutsideLink(unsafe);

            RecordedCallCatalogStore.RetentionResult first =
                mStore.cleanupRetention(connection, COMPLETED - 1, 128, 64, 1, null);
            assertEquals(0, first.rowsDeleted());
            assertEquals(1, first.fileFailures());
            assertTrue(first.moreWork());

            RecordedCallCatalogStore.RetentionResult second =
                mStore.cleanupRetention(connection, COMPLETED - 1, 128, 64, 1, first.nextCursor());
            assertEquals(1, second.rowsDeleted());
            assertEquals(64, second.bytesRemoved());
            assertFalse(second.moreWork());
            assertTrue(Files.exists(outsideFile), "The byte cap must not delete through an unsafe path");
            assertFalse(Files.exists(removable.path()));
            assertEquals(1, count(connection, "recorded_call"));
        }
    }

    @Test
    void replacedCanonicalRootKeepsCatalogOwnershipRow() throws Exception
    {
        RecordedCallArtifact artifact = artifact(CALL_ID, COMPLETED, true);

        try(Connection connection = SdrTrunkDatabase.open(mDatabase))
        {
            assertEquals(RecordedCallCatalogStore.AdmissionResult.INSERTED,
                mStore.admit(connection, artifact));
            Path heldRoot = mTemporaryFolder.resolve("recordings-held");
            Path outsideRoot = mTemporaryFolder.resolve("outside-root");
            Files.move(mRecordingRoot, heldRoot);
            Path outsideFile = outsideRoot.resolve(artifact.relativePath());
            Files.createDirectories(outsideFile.getParent());
            Files.write(outsideFile, new byte[] {9, 8, 7});

            try
            {
                Files.createSymbolicLink(mRecordingRoot, outsideRoot);
            }
            catch(UnsupportedOperationException | java.io.IOException exception)
            {
                assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
            }

            RecordedCallCatalogStore.RetentionResult result =
                mStore.cleanupRetention(connection, COMPLETED + 1, 100);
            assertEquals(1, result.candidates());
            assertEquals(0, result.rowsDeleted());
            assertEquals(1, result.fileFailures());
            assertEquals(1, count(connection, "recorded_call"));
            assertTrue(Files.exists(outsideFile), "Retention must not follow a replaced recording root");
        }
    }

    @Test
    void writerAndCatalogUseTheSameCanonicalFilenameRoundTrip()
    {
        String fileName = ManagedRecordingPath.fileName(CALL_ID, COMPLETED, RecordFormat.MP3);
        Path relative = canonicalDirectory(COMPLETED).resolve(fileName);
        ManagedRecordingPath parsed = ManagedRecordingPath.parse(relative).orElseThrow();

        assertEquals(fileName, RecordedCallCatalogPaths.fileName(CALL_ID, COMPLETED, RecordFormat.MP3));
        assertEquals(CALL_ID.producerId(), Long.parseUnsignedLong(parsed.callIdentity().split("-")[0], 36));
        assertEquals(CALL_ID.sequence(), Long.parseUnsignedLong(parsed.callIdentity().split("-")[1], 36));
        assertEquals(CALL_ID.timeslot(), Integer.parseUnsignedInt(parsed.callIdentity().split("-")[2], 36));
        assertEquals(COMPLETED, parsed.completedAtMs());
        assertEquals(RecordFormat.MP3, parsed.format());
    }

    private int filtered(Connection connection, String system, String site, String talkgroup, String channel,
                         String radio)
        throws Exception
    {
        return mStore.search(connection, new RecordedCallCatalogSearch(system, site, talkgroup, channel, radio,
            COMPLETED - 1, COMPLETED + 1, 0, 10_000, 10, null)).calls().size();
    }

    private RecordedCallArtifact artifact(AudioCallId callId, long completed, boolean eligible) throws Exception
    {
        return artifact(mRecordingRoot, callId, completed, eligible);
    }

    private RecordedCallArtifact artifact(Path root, AudioCallId callId, long completed, boolean eligible)
        throws Exception
    {
        Path path = canonicalPath(root, callId, completed, RecordFormat.WAVE);
        Files.createDirectories(path.getParent());
        Files.write(path, new byte[64]);
        Path relative = root.toAbsolutePath().normalize().relativize(path);
        return new RecordedCallArtifact(path, relative, RecordFormat.WAVE, Files.size(path), callId,
            metadata(eligible), completed - 2_000, completed, 2_000, false, eligible);
    }

    private Path replaceLeafWithOutsideLink(RecordedCallArtifact artifact) throws Exception
    {
        Path managedLeaf = artifact.path().getParent();
        Path heldLeaf = managedLeaf.resolveSibling(managedLeaf.getFileName() + "-held");
        Path outside = mTemporaryFolder.resolve("outside-" + artifact.callId().sequence());
        Files.move(managedLeaf, heldLeaf);
        Files.createDirectories(outside);
        Path outsideFile = Files.write(outside.resolve(artifact.path().getFileName()), new byte[] {9, 8, 7});

        try
        {
            Files.createSymbolicLink(managedLeaf, outside);
        }
        catch(UnsupportedOperationException | java.io.IOException exception)
        {
            assumeTrue(false, "Symbolic links are unavailable: " + exception.getMessage());
        }

        return outsideFile;
    }

    private Path writeManifestWave(AudioCallId callId, long completed, RecordedCallManifest manifest)
        throws Exception
    {
        Path path = canonicalPath(callId, completed, RecordFormat.WAVE);
        writeManifestWave(path, manifest);
        return path;
    }

    private void writeManifestWave(Path path, RecordedCallManifest manifest) throws Exception
    {
        Files.createDirectories(path.getParent());

        try(WaveWriter writer = new WaveWriter(AudioFormats.PCM_SIGNED_8000_HZ_16_BIT_MONO, path))
        {
            writer.writeData(ByteBuffer.wrap(new byte[32]));
            Map<AudioMetadata,String> metadata =
                AudioMetadataUtils.getMetadataMap(null, manifest.metadata(), manifest);
            writer.writeMetadata(AudioMetadataUtils.getLISTChunk(metadata),
                AudioMetadataUtils.getID3Chunk(AudioMetadataUtils.getMP3ID3(metadata)));
        }
    }

    private Path canonicalPath(AudioCallId callId, long completed, RecordFormat format)
    {
        return canonicalPath(mRecordingRoot, callId, completed, format);
    }

    private static Path canonicalPath(Path root, AudioCallId callId, long completed, RecordFormat format)
    {
        return root.resolve(canonicalDirectory(completed))
            .resolve(ManagedRecordingPath.fileName(callId, completed, format)).toAbsolutePath().normalize();
    }

    private static Path canonicalDirectory(long completed)
    {
        var date = Instant.ofEpochMilli(completed).atZone(java.time.ZoneOffset.UTC).toLocalDate();
        return Path.of("calls", "v1", "%04d".formatted(date.getYear()), "%02d".formatted(date.getMonthValue()),
            "%02d".formatted(date.getDayOfMonth()), "county~aaaaaaaaaaaa", "downtown~bbbbbbbbbbbb",
            "control~cccccccccccc", "56138-fire~dddddddddddd");
    }

    private static AudioCallRecordingMetadata metadata(boolean eligible)
    {
        return new AudioCallRecordingMetadata("County Radio", "county-radio", "Downtown",
            "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee", "Control", "channel-guid", "Public Safety",
            "APCO25", "56138", "Fire Dispatch", "exact:APCO25:56138", eligible,
            "APCO25", "16777201", "Engine 4");
    }

    private static long count(Connection connection, String table) throws Exception
    {
        try(Statement statement = connection.createStatement();
            ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + table))
        {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
