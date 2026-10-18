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

package io.github.dsheirer.record;

import io.github.dsheirer.identifier.IdentityResolutionEvidence;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mpatric.mp3agic.ID3v24Tag;
import io.github.dsheirer.alias.Alias;
import io.github.dsheirer.alias.AliasList;
import io.github.dsheirer.alias.AliasListDefinition;
import io.github.dsheirer.alias.AliasListFamily;
import io.github.dsheirer.alias.id.radio.P25Subscriber;
import io.github.dsheirer.alias.id.radio.Radio;
import io.github.dsheirer.audio.call.AudioCallId;
import io.github.dsheirer.audio.call.AudioCallSnapshot;
import io.github.dsheirer.audio.call.CallEncryptionState;
import io.github.dsheirer.audio.call.CallLegId;
import io.github.dsheirer.audio.call.CallLegSource;
import io.github.dsheirer.audio.call.VoiceCallQuality;
import io.github.dsheirer.audio.call.CompletedAudioCall;
import io.github.dsheirer.identifier.MutableIdentifierCollection;
import io.github.dsheirer.identifier.Role;
import io.github.dsheirer.identifier.configuration.ChannelNameConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SiteConfigurationIdentifier;
import io.github.dsheirer.identifier.configuration.SystemConfigurationIdentifier;
import io.github.dsheirer.identifier.radio.ResolvedRadioIdentity;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.p25.P25SiteIdentity;
import io.github.dsheirer.module.decode.p25.identifier.APCO25System;
import io.github.dsheirer.module.decode.p25.identifier.APCO25Wacn;
import io.github.dsheirer.module.decode.p25.identifier.radio.APCO25FullyQualifiedRadioIdentifier;
import io.github.dsheirer.module.decode.p25.identifier.talkgroup.APCO25Talkgroup;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.preference.UserPreferences;
import io.github.dsheirer.portable.PortableApplicationPaths;
import io.github.dsheirer.preference.record.RecordingMode;
import io.github.dsheirer.protocol.Protocol;
import io.github.dsheirer.record.managed.ManagedRecordingCatalog;
import io.github.dsheirer.util.TimeStamp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioRecordingManagerTest
{
    @TempDir
    Path mTemporaryFolder;
    private RecordingMode mOriginalMode;


    private static void setPortableDataRoot(String value) throws ReflectiveOperationException
    {
        if(value == null) System.clearProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY);
        else System.setProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY, value);
        var reset = PortableApplicationPaths.class.getDeclaredMethod("resetForTest");
        reset.setAccessible(true);
        reset.invoke(null);
    }


    @Test
    void installedLibrarySupplies16kOnRecordingWorkerWhileObserversKeepNativeClock() throws Exception
    {
        String originalDataRoot = System.getProperty(PortableApplicationPaths.DATA_ROOT_PROPERTY);
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        Path originalJmbe = preferences.getJmbeLibraryPreference().getPathJmbeLibrary();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        var originalRate = preferences.getMP3Preference().getAudioSampleRate();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<Path> files = new ArrayList<>();
        List<CompletedAudioCall> observed = new ArrayList<>();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, observed::add, scheduler,
            (call, path, format, prefs, normalize) -> {
                AudioCallRecorder.write(call, path, format, prefs, normalize);
                files.add(path);
            }, null, null, call -> null);
        CompletedAudioCall source = withDecoder(completedCall(93, List.of(new float[8000])), DecoderType.P25_PHASE2);
        try
        {
            setPortableDataRoot(mTemporaryFolder.resolve("portable-output").toString());
            preferences.getJmbeLibraryPreference().setPathJmbeLibrary(
                io.github.dsheirer.audio.codec.mbe.JmbeOutputLibraryFixture.create(mTemporaryFolder.resolve("output-library")));
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getRecordPreference().setAudioRecordFormat(RecordFormat.MP3);
            preferences.getMP3Preference().setAudioSampleRate(io.github.dsheirer.audio.convert.InputAudioFormat.SR_16000);
            manager.start(); manager.receive(source);
            assertTrue(files.isEmpty(), "No library processing or encoding runs on completed-call ingress");
            manager.stop();
            assertEquals(1, files.size());
            assertEquals(16000, new com.mpatric.mp3agic.Mp3File(files.getFirst().toString()).getSampleRate());
            assertSame(source, observed.getFirst());
            assertEquals(1000, source.getDuration());
            assertEquals(8000, source.audioBuffers().getFirst().length);
        }
        finally
        {
            manager.stop(); scheduler.shutdownNow();
            io.github.dsheirer.audio.codec.mbe.JmbeLibraryLoader.getInstance().close();
            preferences.getRecordPreference().setAudioRecordFormat(originalFormat);
            preferences.getMP3Preference().setAudioSampleRate(originalRate);
            setPortableDataRoot(originalDataRoot);
            if(originalJmbe != null) preferences.getJmbeLibraryPreference().setPathJmbeLibrary(originalJmbe);
            else preferences.getJmbeLibraryPreference().resetPathJmbeLibrary();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
        }
    }

    @Test
    void recordingWorkerCapturesOneSharedResultForFileCatalogAndObserverWithoutWaitingInReceive() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<CompletedAudioCall> written = new ArrayList<>(), observed = new ArrayList<>();
        CompletedAudioCall source = withDecoder(completedCall(), DecoderType.P25_PHASE1);
        var sequence = new io.github.dsheirer.audio.tone.CompletedToneSequence(source.nativeAudioSnapshot(),
            source.snapshot().startTimestamp(), io.github.dsheirer.audio.tone.CompletedToneSequence.Source.DECODED_AUDIO,
            io.github.dsheirer.audio.tone.CompletedToneSequence.Method.P25_PCM_TONE_V1,
            List.of(new io.github.dsheirer.audio.tone.CompletedToneSequence.Element(List.of(1_000_000),null,0L,60,.9)));
        AtomicInteger lookups = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, observed::add, scheduler,
            (call, path, format, prefs, normalizeAudio) -> { written.add(call); Files.write(path,new byte[]{1},StandardOpenOption.CREATE_NEW); },
            null,null,call -> { assertSame(source,call); lookups.incrementAndGet(); return sequence; });
        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            manager.start(); manager.receive(source);
            assertEquals(0,lookups.get(), "Live handoff cannot wait for tone analysis");
            manager.stop();
            assertEquals(1,lookups.get());
            assertSame(sequence,written.getFirst().completedToneSequence());
            assertSame(sequence,observed.getFirst().completedToneSequence());
            assertSame(source.nativeAudioSnapshot(),written.getFirst().nativeAudioSnapshot());
            assertSame(source.audioBuffers().getFirst(),observed.getFirst().audioBuffers().getFirst());
            assertNull(source.completedToneSequence());
        }
        finally
        {
            manager.stop(); scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
        }
    }

    @Test
    void unavailableToneObserverStillSavesAndReportsRecording() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<CompletedAudioCall> written = new ArrayList<>(), observed = new ArrayList<>();
        CompletedAudioCall source = completedCall();
        AudioRecordingManager manager = new AudioRecordingManager(preferences,observed::add,scheduler,
            (call, path, format, prefs, normalizeAudio) -> { written.add(call); Files.write(path,new byte[]{1},StandardOpenOption.CREATE_NEW); },
            null,null,call -> { throw new IllegalStateException("Unavailable optional observer"); });
        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            manager.start(); manager.receive(source); manager.stop();
            assertSame(source,written.getFirst()); assertSame(source,observed.getFirst());
            assertEquals(0,manager.getQueueStatus().droppedRecordings());
        }
        finally
        {
            manager.stop(); scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
        }
    }

    @Test
    void managedEncodingDoesNotAwaitToneCompletion() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        RecordingMode previous = preferences.getRecordPreference().getRecordingMode();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AtomicInteger indexed = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, scheduler,
            (call, path, format, prefs, normalize) -> Files.write(path, new byte[]{1}, StandardOpenOption.CREATE_NEW),
            (path, call, size, observer) -> { indexed.incrementAndGet(); return true; },
            mTemporaryFolder.resolve("managed-no-tone-wait"),
            call -> { throw new AssertionError("Managed audio must not invoke the bounded tone wait"); });
        try
        {
            preferences.getRecordPreference().setRecordingMode(RecordingMode.MANAGED);
            manager.start(); manager.receive(completedCall()); manager.stop();
            assertEquals(1, indexed.get());
        }
        finally
        {
            manager.stop(); scheduler.shutdownNow();
            preferences.getRecordPreference().setRecordingMode(previous);
        }
    }

    @Test
    void recordingWriterReceivesNativePcmAndRetainsRawCallForObservers() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<CompletedAudioCall> written = new ArrayList<>();
        List<CompletedAudioCall> observed = new ArrayList<>();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, observed::add, scheduler,
            (call, path, format, userPreferences, normalizeAudio) -> {
                written.add(call);
                Files.write(path, new byte[]{1}, StandardOpenOption.CREATE_NEW);
            });
        float[] rawSamples = {0.1f, -0.2f, 0.4f, -0.8f};
        CompletedAudioCall rawCall = withDecoder(completedCall(81, List.of(rawSamples)), DecoderType.P25_PHASE1);
        CompletedAudioCall phase2 = withDecoder(completedCall(82, List.of(rawSamples.clone())), DecoderType.P25_PHASE2);

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            manager.start();
            manager.receive(rawCall);
            manager.receive(phase2);
            assertTrue(written.isEmpty());
            manager.stop();
            assertEquals(2, written.size());
            assertSame(rawCall, observed.getFirst());
            assertSame(rawCall.snapshot(), written.getFirst().snapshot());
            assertSame(rawCall.resolvedPolicy(), written.getFirst().resolvedPolicy());
            assertEquals(rawCall.getDuration(), written.getFirst().getDuration());
            assertSame(rawCall, written.getFirst(), "The recorder owns format rendering and presentation gain");
            assertEquals(2.0f, written.getFirst().audioBuffers().getFirst()[2] /
                written.getFirst().audioBuffers().getFirst()[1] * -1.0f, 0.000001f);
            assertArrayEquals(new float[]{0.1f, -0.2f, 0.4f, -0.8f}, rawSamples);
            assertSame(phase2, written.get(1));
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
        }
    }

    private static CompletedAudioCall withDecoder(CompletedAudioCall call, DecoderType decoderType)
    {
        AudioCallSnapshot old = call.snapshot();
        CallLegSource source = new CallLegSource(decoderType, null, null, null, 0, null, null, null, false);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(old.callId(), old.linkedCallId(), old.aliasList(),
            old.identifierCollection(), old.broadcastChannels(), old.startTimestamp(), old.lastActivityTimestamp(),
            old.burstCount(), old.burstGeneration(), old.lastBurstStartTimestamp(), old.lastBurstEndTimestamp(),
            old.burstActive(), old.complete(), old.encryptionState(), old.recordAudio(), old.recordingMetadata(),
            old.voiceCallQuality(), old.callLegId(), source, old.callEncryptionEvidence());
        return new CompletedAudioCall(snapshot, call.audioBuffers());
    }

    @BeforeEach
    void selectClassicByDefault()
    {
        var preference = new UserPreferences().getRecordPreference();
        mOriginalMode = preference.getRecordingMode();
        preference.setRecordingMode(RecordingMode.CLASSIC);
    }

    @AfterEach
    void restoreRecordingMode()
    {
        if(mOriginalMode != null)
        {
            new UserPreferences().getRecordPreference().setRecordingMode(mOriginalMode);
        }
    }

    @Test
    void reportsRecordedOnlyAfterPermanentFileExists() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        CountDownLatch recorded = new CountDownLatch(1);
        AtomicInteger metrics = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, call -> {
            metrics.incrementAndGet();
            recorded.countDown();
        });

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getRecordPreference().setAudioRecordFormat(RecordFormat.WAVE);
            manager.start();
            manager.receive(completedCall());

            assertTrue(recorded.await(5, TimeUnit.SECONDS));
            assertEquals(1, metrics.get());

            try(var files = Files.list(mTemporaryFolder))
            {
                List<Path> recordings = files.filter(Files::isRegularFile).toList();
                assertEquals(1, recordings.size());
                assertTrue(Files.size(recordings.getFirst()) > 0);
            }
        }
        finally
        {
            manager.stop();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
            preferences.getRecordPreference().setAudioRecordFormat(originalFormat);
        }
    }

    @Test
    void completedCallsUseWriterClockSecondsAndDistinctMp3Files() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<Path> writtenPaths = new ArrayList<>();
        AtomicInteger recorded = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences,
            ignored -> recorded.incrementAndGet(), scheduler, (call, path, format, userPreferences, normalizeAudio) -> {
                writtenPaths.add(path);
                Files.write(path, new byte[]{(byte)call.logicalCallId().sequence()}, StandardOpenOption.CREATE_NEW);
            });
        long completedAt = 1_777_777_777_123L;

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getRecordPreference().setAudioRecordFormat(RecordFormat.MP3);
            manager.start();
            manager.receive(completedCall(1, completedAt, List.of(new float[80])));
            manager.receive(completedCall(2, completedAt + 1, List.of(new float[80])));
            manager.stop();

            assertEquals(2, recorded.get());
            assertEquals(2, writtenPaths.size());
            assertNotEquals(writtenPaths.get(0), writtenPaths.get(1));
            for(Path path: writtenPaths)
            {
                assertTrue(path.getFileName().toString().matches("\\d{8}_\\d{6}__TO_56138(?:_V\\d+)?\\.mp3"));
                assertFalse(path.getFileName().toString().startsWith(TimeStamp.getTimeStamp(completedAt, "_")));
            }
            assertFalse(writtenPaths.get(0).getFileName().toString().contains("_CALL_"));
            assertFalse(writtenPaths.get(1).getFileName().toString().contains("_CALL_"));
            assertArrayEquals(new byte[]{1}, Files.readAllBytes(writtenPaths.get(0)));
            assertArrayEquals(new byte[]{2}, Files.readAllBytes(writtenPaths.get(1)));
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
            preferences.getRecordPreference().setAudioRecordFormat(originalFormat);
        }
    }

    @Test
    void basicWriterRetriesCreateNewCollisionsWithoutReplacingExistingFile() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<Path> attempted = new ArrayList<>();
        AtomicInteger recorded = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences,
            ignored -> recorded.incrementAndGet(), scheduler, (call, path, format, userPreferences, normalizeAudio) -> {
                attempted.add(path);
                if(attempted.size() == 1) Files.write(path, new byte[]{9}, StandardOpenOption.CREATE_NEW);
                AudioCallRecorder.write(call, path, format, userPreferences, normalizeAudio);
            });
        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getRecordPreference().setAudioRecordFormat(RecordFormat.WAVE);
            manager.start();
            manager.receive(completedCall());
            manager.stop();

            assertEquals(2, attempted.size());
            assertEquals(1, recorded.get());
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(attempted.getFirst()));
            assertTrue(attempted.get(1).getFileName().toString().endsWith("_V2.wav"));
            assertTrue(Files.size(attempted.get(1)) > 44);
            assertEquals(0, manager.getQueueStatus().droppedRecordings());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
            preferences.getRecordPreference().setAudioRecordFormat(originalFormat);
        }
    }

    @Test
    void classicFilenameRetryAndActualTagsUseCapturedWinnerSystemOverForeignNetworkIdentifiers() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<Path> attempted = new ArrayList<>();
        AtomicInteger recorded = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences,
            ignored -> recorded.incrementAndGet(), scheduler, (call, path, format, userPreferences, normalizeAudio) -> {
                attempted.add(path);
                //Simulate another writer claiming the chosen path after its existence check.
                if(attempted.size() == 1) Files.write(path, new byte[]{9}, StandardOpenOption.CREATE_NEW);
                AudioCallRecorder.write(call, path, format, userPreferences, normalizeAudio);
            });
        AliasList aliases = new AliasList(new AliasListDefinition("SyntheticAliases", AliasListFamily.P25));
        Alias canonical = new Alias("Canonical Unit");
        canonical.setMatchIdentifier(new P25Subscriber(0xBEE00, 0x687, 70001));
        aliases.addAlias(canonical);
        Alias working = new Alias("Working Unit");
        working.setMatchIdentifier(new Radio(Protocol.APCO25, 501));
        aliases.addAlias(working);
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Wacn.create(0xABCDE));
        identifiers.update(APCO25System.create(0x456));
        identifiers.update(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(501,
            0xBEE00, 0x687, 70001));
        identifiers.update(APCO25FullyQualifiedRadioIdentifier.createToWithWorkingAddress(70002,
            0xBEE00, 0x687, 70002));
        CallLegSource winner = new CallLegSource(DecoderType.P25_PHASE1, null, null, null, 0,
            new P25SiteIdentity(0xABCDE, 0x456, 1, 1), TrunkedIdentityDomain.STANDARD, null, false,
            "p25:bee00:687");
        AudioCallId callId = new AudioCallId(1L, 1L, 1);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, aliases, identifiers, Set.of(),
            1_000L, 2_000L, 1, 1, 1_000L, 2_000L, false, true,
            CallEncryptionState.CLEAR, true, null, VoiceCallQuality.EMPTY, CallLegId.from(callId), winner, null);
        List<float[]> audio = new ArrayList<>();
        for(int frame = 0; frame < 50; frame++) audio.add(new float[160]);
        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getRecordPreference().setAudioRecordFormat(RecordFormat.MP3);
            preferences.getRecordPreference().setRecordingMode(RecordingMode.CLASSIC);
            manager.start();
            manager.receive(new CompletedAudioCall(snapshot, audio));
            manager.stop();

            assertEquals(2, attempted.size());
            assertEquals(1, recorded.get());
            assertTrue(attempted.getFirst().getFileName().toString().matches(
                "\\d{8}_\\d{6}__TO_70002_FROM_501\\.mp3"));
            assertTrue(attempted.get(1).getFileName().toString().matches(
                "\\d{8}_\\d{6}__TO_70002_FROM_501_V2\\.mp3"));
            for(Path path: attempted)
            {
                String filename = path.getFileName().toString();
                assertTrue(filename.matches(
                    "[0-9]{8}_[0-9]{6}__TO_[0-9]+_FROM_[0-9]+(?:_V[0-9]+)?\\.mp3"));
                assertFalse(filename.substring(filename.indexOf("__TO_")).contains("70001"));
                assertFalse(filename.contains("Working_ID"));
            }
            assertArrayEquals(new byte[]{9}, Files.readAllBytes(attempted.getFirst()));
            ID3v24Tag tag = new ID3v24Tag(Files.readAllBytes(attempted.get(1)));
            assertEquals("501 Canonical Unit", tag.getArtist());
            assertEquals("70002", tag.getTitle());
            assertFalse(tag.getArtist().contains("70001"));
            assertFalse(tag.getArtist().contains("Working ID"));
            assertEquals(0, manager.getQueueStatus().droppedRecordings());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
            preferences.getRecordPreference().setAudioRecordFormat(originalFormat);
            preferences.getRecordPreference().setRecordingMode(originalMode);
        }
    }

    @Test
    void classicMp3FilenameAndArtistKeepObservedRadioWhenOnlyAssignmentCacheQualifiedIt() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalDirectory = preferences.getDirectoryPreference().getDirectoryRecording();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<Path> written = new ArrayList<>();
        AtomicInteger recorded = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences,
            ignored -> recorded.incrementAndGet(), scheduler, (call, path, format, userPreferences, normalizeAudio) -> {
                AudioCallRecorder.write(call, path, format, userPreferences, normalizeAudio);
                written.add(path);
            });
        AliasList aliases = new AliasList(new AliasListDefinition("SyntheticAliases", AliasListFamily.P25));
        Alias local = new Alias("Local Unit");
        local.setMatchIdentifier(new Radio(Protocol.APCO25, 85610));
        aliases.addAlias(local);
        Alias working = new Alias("Working Unit");
        working.setMatchIdentifier(new Radio(Protocol.APCO25, 501));
        aliases.addAlias(working);
        Alias canonical = new Alias("Canonical Unit");
        canonical.setMatchIdentifier(new P25Subscriber(0xBEE00, 0x687, 85610));
        aliases.addAlias(canonical);
        List<RadioRecordingFixture> fixtures = List.of(
            new RadioRecordingFixture(APCO25FullyQualifiedRadioIdentifier.createWithWorkingAddress(
                85610, 0xBEE00, 0x687, 85610, Role.FROM, IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT),
                "85610", "85610 Canonical Unit"),
            new RadioRecordingFixture(APCO25FullyQualifiedRadioIdentifier.createWithWorkingAddress(
                501, 0xBEE00, 0x687, 85610, Role.FROM, IdentityResolutionEvidence.CONFIRMED_ASSIGNMENT),
                "501", "501 Canonical Unit"),
            new RadioRecordingFixture(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                85610, 0xBEE00, 0x687, 85610),
                "85610", "85610 Canonical Unit"),
            new RadioRecordingFixture(APCO25FullyQualifiedRadioIdentifier.createFromWithWorkingAddress(
                501, 0xBEE00, 0x687, 85610),
                "501", "501 Canonical Unit"));
        List<CompletedAudioCall> calls = new ArrayList<>();

        try
        {
            preferences.getDirectoryPreference().setDirectoryRecording(mTemporaryFolder);
            preferences.getRecordPreference().setAudioRecordFormat(RecordFormat.MP3);
            preferences.getRecordPreference().setRecordingMode(RecordingMode.CLASSIC);
            manager.start();
            for(int index = 0; index < fixtures.size(); index++)
            {
                RadioRecordingFixture fixture = fixtures.get(index);
                MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
                identifiers.update(SystemConfigurationIdentifier.create("SyntheticSystem"));
                identifiers.update(SiteConfigurationIdentifier.create("SyntheticSite"));
                identifiers.update(ChannelNameConfigurationIdentifier.create("SyntheticChannel"));
                identifiers.update(APCO25Talkgroup.create(48530));
                identifiers.update(fixture.source());
                long completedAt = System.currentTimeMillis();
                long startedAt = completedAt - 1_000;
                AudioCallId callId = new AudioCallId(1L, index + 1L, 1);
                AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null, aliases, identifiers, Set.of(),
                    startedAt, completedAt, 1, 1, startedAt, completedAt, false, true,
                    CallEncryptionState.CLEAR, true, null, VoiceCallQuality.EMPTY, CallLegId.from(callId), null, null);
                List<float[]> audioBuffers = new ArrayList<>();
                for(int frame = 0; frame < 50; frame++) audioBuffers.add(new float[160]);
                CompletedAudioCall call = new CompletedAudioCall(snapshot, audioBuffers);
                calls.add(call);
                manager.receive(call);
                assertEquals(index + 1, manager.getQueueStatus().queuedCalls());
                assertEquals(0, manager.getQueueStatus().droppedRecordings());
            }
            manager.stop();

            assertEquals(fixtures.size(), recorded.get());
            assertEquals(fixtures.size(), written.size());
            for(int index = 0; index < fixtures.size(); index++)
            {
                RadioRecordingFixture fixture = fixtures.get(index);
                Path path = written.get(index);
                assertTrue(path.getFileName().toString().matches("\\d{8}_\\d{6}_SyntheticSystem_SyntheticSite_" +
                    "SyntheticChannel__TO_48530_FROM_" + fixture.filenameSource() + "(?:_V[0-9]+)?\\.mp3"));
                byte[] bytes = Files.readAllBytes(path);
                ID3v24Tag tag = new ID3v24Tag(bytes);
                assertEquals(fixture.artist(), tag.getArtist());
                assertEquals("48530", tag.getTitle());
                int tagSize = 10 + ((bytes[6] & 0x7F) << 21) + ((bytes[7] & 0x7F) << 14) +
                    ((bytes[8] & 0x7F) << 7) + (bytes[9] & 0x7F);
                assertTrue(bytes.length > tagSize, "recording must contain encoded audio after its ID3 tag");
                assertSame(fixture.source(), calls.get(index).snapshot().identifierCollection().getFromIdentifier(),
                    "recording compatibility must preserve canonical identity facts in the call snapshot");
            }
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getDirectoryPreference().setDirectoryRecording(originalDirectory);
            preferences.getRecordPreference().setAudioRecordFormat(originalFormat);
            preferences.getRecordPreference().setRecordingMode(originalMode);
        }
    }

    @Test
    void completedCallQueueIsBoundedByCount() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, scheduler,
            (call, path, format, userPreferences, normalizeAudio) -> {});

        try
        {
            manager.start();
            CompletedAudioCall call = completedCall(1, List.of(new float[80]));

            for(int index = 0; index < AudioRecordingManager.MAXIMUM_QUEUED_CALLS + 2; index++)
            {
                manager.receive(call);
            }

            AudioRecordingManager.RecordingQueueStatus status = manager.getQueueStatus();
            assertEquals(AudioRecordingManager.MAXIMUM_QUEUED_CALLS, status.queuedCalls());
            assertEquals(2, status.droppedRecordings());
            manager.stop();
            assertEquals(0, manager.getQueueStatus().queuedCalls());
            assertEquals(0, manager.getQueueStatus().queuedSourceBytes());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    void completedCallQueueIsBoundedBySourceBytes() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, scheduler,
            (call, path, format, userPreferences, normalizeAudio) -> {});
        float[] sharedEightMiBBuffer = new float[2 * 1024 * 1024];
        CompletedAudioCall call = completedCall(1, List.of(sharedEightMiBBuffer));

        try
        {
            manager.start();

            for(int index = 0; index < 40; index++)
            {
                manager.receive(call);
            }

            AudioRecordingManager.RecordingQueueStatus status = manager.getQueueStatus();
            assertEquals(32, status.queuedCalls());
            assertEquals(AudioRecordingManager.MAXIMUM_QUEUED_SOURCE_BYTES, status.queuedSourceBytes());
            assertEquals(8, status.droppedRecordings());
            manager.stop();
            assertEquals(0, manager.getQueueStatus().queuedSourceBytes());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    void emptyAudioCallsNeverEnterRecordingQueue()
    {
        UserPreferences preferences = new UserPreferences();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, scheduler,
            (call, path, format, userPreferences, normalizeAudio) -> {});

        try
        {
            manager.start();
            manager.receive(completedCall(1, List.of()));
            AudioRecordingManager.RecordingQueueStatus status = manager.getQueueStatus();
            assertEquals(0, status.queuedCalls());
            assertEquals(0, status.queuedSourceBytes());
            assertEquals(0, status.droppedRecordings());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
        }
    }

    @Test
    void modeIsCapturedWhenCallEntersQueueAndManagedFilesStaySeparate() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        Path originalClassic = preferences.getDirectoryPreference().getDirectoryRecording();
        RecordFormat originalFormat = preferences.getRecordPreference().getAudioRecordFormat();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();
        Path classic = mTemporaryFolder.resolve("recordings");
        Path managed = mTemporaryFolder.resolve("recordings-managed");
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<Path> paths = new ArrayList<>();
        List<RecordFormat> formats = new ArrayList<>();
        List<Path> indexed = new ArrayList<>();
        AtomicInteger recorded = new AtomicInteger();
        AtomicReference<Runnable> indexedObserver = new AtomicReference<>();
        AudioRecordingManager manager = new AudioRecordingManager(preferences,
            ignored -> recorded.incrementAndGet(), scheduler, (call, path, format, userPreferences, normalizeAudio) -> {
                paths.add(path);
                formats.add(format);
                Files.write(path, new byte[]{1}, StandardOpenOption.CREATE_NEW);
            }, (path, call, size, onIndexed) -> {
                indexed.add(path);
                indexedObserver.set(onIndexed);
                return true;
            }, managed);

        try
        {
            Files.createDirectories(classic);
            preferences.getDirectoryPreference().setDirectoryRecording(classic);
            preferences.getRecordPreference().setAudioRecordFormat(RecordFormat.WAVE);
            preferences.getRecordPreference().setRecordingMode(RecordingMode.MANAGED);
            manager.start();
            manager.receive(completedCall(1, 1_777_777_777_123L, List.of(new float[80])));
            preferences.getRecordPreference().setRecordingMode(RecordingMode.CLASSIC);
            manager.receive(completedCall(2, 1_777_777_777_124L, List.of(new float[80])));
            manager.stop();

            assertEquals(2, paths.size());
            assertEquals(List.of(RecordFormat.MP3, RecordFormat.WAVE), formats);
            assertEquals(1, indexed.size());
            assertEquals(paths.getFirst(), indexed.getFirst());
            assertTrue(paths.getFirst().startsWith(managed));
            assertTrue(paths.getFirst().getFileName().toString().endsWith(".mp3"));
            assertEquals("tg-56138", paths.getFirst().getParent().getFileName().toString());
            assertEquals("unknown-channel", paths.getFirst().getParent().getParent().getFileName().toString());
            assertTrue(paths.getFirst().getParent().getParent().getParent().getFileName().toString()
                .matches("\\d{4}-\\d{2}-\\d{2}"));
            assertEquals(classic, paths.get(1).getParent());
            assertTrue(paths.get(1).getFileName().toString().endsWith(".wav"));
            assertEquals(1, recorded.get(), "managed activity waits for the catalog commit");
            indexedObserver.get().run();
            assertEquals(2, recorded.get());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getRecordPreference().setRecordingMode(originalMode);
            preferences.getRecordPreference().setAudioRecordFormat(originalFormat);
            preferences.getDirectoryPreference().setDirectoryRecording(originalClassic);
        }
    }

    @Test
    void realCatalogCommitsBeforeRecordedActivityIsReported() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();
        Path managed = mTemporaryFolder.resolve("managed");
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        CountDownLatch recorded = new CountDownLatch(1);
        try(ManagedRecordingCatalog catalog = new ManagedRecordingCatalog(
            mTemporaryFolder.resolve("managed.sqlite"), managed))
        {
            AudioRecordingManager manager = new AudioRecordingManager(preferences,
                ignored -> recorded.countDown(), scheduler, (call, path, format, userPreferences, normalizeAudio) ->
                    Files.write(path, new byte[]{'I', 'D', '3', 'a', 'b'}, StandardOpenOption.CREATE_NEW),
                catalog::submit, managed);
            try
            {
                preferences.getRecordPreference().setRecordingMode(RecordingMode.MANAGED);
                manager.start();
                manager.receive(completedCall());
                manager.stop();
                assertTrue(recorded.await(5, TimeUnit.SECONDS));
                assertEquals(1L, catalog.stats().callCount());
                assertTrue(Files.isRegularFile(catalog.audioPath(1L)));
            }
            finally
            {
                manager.stop();
            }
        }
        finally
        {
            scheduler.shutdownNow();
            preferences.getRecordPreference().setRecordingMode(originalMode);
        }
    }

    @Test
    void rejectedManagedCatalogHandoffRemovesUnindexedAudio() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<Path> written = new ArrayList<>();
        AtomicInteger recorded = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences,
            ignored -> recorded.incrementAndGet(), scheduler, (call, path, format, userPreferences, normalizeAudio) -> {
                written.add(path);
                Files.write(path, new byte[]{1}, StandardOpenOption.CREATE_NEW);
            }, (path, call, size, onIndexed) -> false, mTemporaryFolder.resolve("managed"));

        try
        {
            preferences.getRecordPreference().setRecordingMode(RecordingMode.MANAGED);
            manager.start();
            manager.receive(completedCall());
            manager.stop();

            assertEquals(1, written.size());
            assertFalse(Files.exists(written.getFirst()));
            assertEquals(0, recorded.get());
            assertEquals(1, manager.getQueueStatus().droppedRecordings());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getRecordPreference().setRecordingMode(originalMode);
        }
    }

    @Test
    void managedModeWithoutCatalogDropsBeforeFileWrite()
    {
        UserPreferences preferences = new UserPreferences();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        AtomicInteger writes = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, scheduler,
            (call, path, format, userPreferences, normalizeAudio) -> writes.incrementAndGet());

        try
        {
            preferences.getRecordPreference().setRecordingMode(RecordingMode.MANAGED);
            manager.start();
            manager.receive(completedCall());
            assertEquals(0, manager.getQueueStatus().queuedCalls());
            assertEquals(0, manager.getQueueStatus().queuedSourceBytes());
            assertEquals(1, manager.getQueueStatus().droppedRecordings());
            assertEquals(RecordingMode.MANAGED, preferences.getRecordPreference().getRecordingMode());
            manager.stop();
            assertEquals(0, writes.get());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getRecordPreference().setRecordingMode(originalMode);
        }
    }

    @Test
    void failedManagedEncodingRemovesPartialFile() throws Exception
    {
        UserPreferences preferences = new UserPreferences();
        RecordingMode originalMode = preferences.getRecordPreference().getRecordingMode();
        ManualRecordingScheduler scheduler = new ManualRecordingScheduler();
        List<Path> written = new ArrayList<>();
        AtomicInteger indexed = new AtomicInteger();
        AudioRecordingManager manager = new AudioRecordingManager(preferences, null, scheduler,
            (call, path, format, userPreferences, normalizeAudio) -> {
                written.add(path);
                Files.write(path, new byte[]{1}, StandardOpenOption.CREATE_NEW);
                throw new IOException("simulated encoder failure");
            }, (path, call, size, onIndexed) -> {
                indexed.incrementAndGet();
                return true;
            }, mTemporaryFolder.resolve("managed"));

        try
        {
            preferences.getRecordPreference().setRecordingMode(RecordingMode.MANAGED);
            manager.start();
            manager.receive(completedCall());
            manager.stop();

            assertEquals(1, written.size());
            assertFalse(Files.exists(written.getFirst()));
            assertEquals(0, indexed.get());
            assertEquals(1, manager.getQueueStatus().droppedRecordings());
        }
        finally
        {
            manager.stop();
            scheduler.shutdownNow();
            preferences.getRecordPreference().setRecordingMode(originalMode);
        }
    }

    private static CompletedAudioCall completedCall()
    {
        return completedCall(1, List.of(new float[800]));
    }

    private static CompletedAudioCall completedCall(long sequence, List<float[]> audioBuffers)
    {
        long now = System.currentTimeMillis();
        return completedCall(sequence, now + 100, audioBuffers);
    }

    private static CompletedAudioCall completedCall(long sequence, long completedAt, List<float[]> audioBuffers)
    {
        MutableIdentifierCollection identifiers = new MutableIdentifierCollection();
        identifiers.update(APCO25Talkgroup.create(56138));
        long startedAt = completedAt - 100;
        AudioCallId callId = new AudioCallId(1L, sequence, 1);
        AudioCallSnapshot snapshot = new AudioCallSnapshot(callId, null,
            AliasList.empty("test"),
            identifiers, Set.of(), startedAt, completedAt, 1, 1, startedAt, completedAt, false, true,
            CallEncryptionState.CLEAR, true, null, VoiceCallQuality.EMPTY, CallLegId.from(callId), null, null);
        return new CompletedAudioCall(snapshot, audioBuffers);
    }

    private static class ManualRecordingScheduler extends ScheduledThreadPoolExecutor
    {
        ManualRecordingScheduler()
        {
            super(1);
        }

        @Override
        public ScheduledFuture<?> scheduleAtFixedRate(Runnable command, long initialDelay, long period, TimeUnit unit)
        {
            return super.scheduleAtFixedRate(command, 1, 1, TimeUnit.DAYS);
        }
    }

    private record RadioRecordingFixture(APCO25FullyQualifiedRadioIdentifier source, String filenameSource,
                                         String artist) {}
}
