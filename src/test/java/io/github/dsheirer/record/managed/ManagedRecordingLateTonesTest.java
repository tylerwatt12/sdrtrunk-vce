/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.record.managed;

import static org.junit.jupiter.api.Assertions.*;

import io.github.dsheirer.audio.call.*;
import io.github.dsheirer.audio.tone.CompletedToneSequence;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import io.github.dsheirer.module.decode.traffic.TrunkedIdentityDomain;
import io.github.dsheirer.sample.Listener;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingLateTonesTest
{
    @TempDir Path directory;

    @Test void completionAfterCommittedRecordingAttachesOnceAndSurvivesRestart() throws Exception
    {
        FakeTones tones = new FakeTones();
        Path database = directory.resolve("late.sqlite"), audio = directory.resolve("late.mp3");
        Files.write(audio, new byte[]{1});
        AudioCallSnapshot nativeCall = snapshot(1, 1, source("one"), 1000, 2000);
        try(var catalog = new ManagedRecordingCatalog(database, directory, tones))
        {
            CountDownLatch indexed = new CountDownLatch(1);
            assertTrue(catalog.submit(audio, call(nativeCall), 1, indexed::countDown));
            assertTrue(indexed.await(2, TimeUnit.SECONDS));
            assertNull(catalog.find(1).pagingToneSummary());
            tones.publish(sequence(nativeCall));
            tones.publish(sequence(nativeCall));
        }
        assertEquals(0, tones.active.get());
        try(var catalog = new ManagedRecordingCatalog(database, directory, new FakeTones()))
        {
            assertEquals(List.of(510.5), firstTone(catalog.find(1).pagingToneSummary()).get("frequencies_hz"));
            assertEquals(1, catalog.stats().callCount());
            assertTrue(Files.exists(audio));
        }
        try(var c = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = c.createStatement())
        {
            try(var row = sql.executeQuery("SELECT count(*) FROM recording_tone"))
            { assertTrue(row.next()); assertEquals(1, row.getInt(1)); }
        }
    }

    @Test void completionDuringInitialLookupCannotFallBetweenSubscriptionAndCatalogInsert() throws Exception
    {
        FakeTones tones = new FakeTones();
        AudioCallSnapshot nativeCall = snapshot(2, 2, source("two"), 3000, 4000);
        tones.onLookup = () -> tones.publish(sequence(nativeCall));
        Path audio = directory.resolve("early.mp3"); Files.write(audio, new byte[]{1});
        Path database = directory.resolve("early.sqlite");
        try(var catalog = new ManagedRecordingCatalog(database, directory, tones))
        {
            assertTrue(catalog.submit(audio, call(nativeCall), 1, null));
        }
        try(var catalog = new ManagedRecordingCatalog(database, directory, new FakeTones()))
        { assertEquals("analog_pcm_tone_v1", catalog.find(1).pagingToneSummary().get("method")); }
    }

    @Test void fullQueueAndContendedHandoffNeverWaitForCatalogWorkAndCloseRemovesListener() throws Exception
    {
        FakeTones tones = new FakeTones();
        AudioCallSnapshot nativeCall = snapshot(3, 3, source("three"), 5000, 6000);
        CountDownLatch indexed = new CountDownLatch(1), releaseWriter = new CountDownLatch(1);
        Path audio = directory.resolve("pressure.mp3"); Files.write(audio, new byte[]{1});
        var catalog = new ManagedRecordingCatalog(directory.resolve("pressure.sqlite"), directory, tones);
        try
        {
            assertTrue(catalog.submit(audio, call(nativeCall), 1, () -> {
                indexed.countDown();
                try { releaseWriter.await(2, TimeUnit.SECONDS); }
                catch(InterruptedException failure) { Thread.currentThread().interrupt(); }
            }));
            assertTrue(indexed.await(2, TimeUnit.SECONDS));
            assertTimeoutPreemptively(Duration.ofMillis(500), () -> {
                for(int i = 0; i <= ManagedRecordingCatalog.MAX_PENDING_TONES; i++) tones.publish(sequence(nativeCall));
            });
            assertEquals(1, catalog.droppedToneObservations());
            var field = ManagedRecordingCatalog.class.getDeclaredField("mToneHandoff"); field.setAccessible(true);
            ReentrantLock handoff = (ReentrantLock)field.get(catalog);
            handoff.lock();
            try { assertTimeoutPreemptively(Duration.ofMillis(500), () -> tones.publish(sequence(nativeCall))); }
            finally { handoff.unlock(); }
            assertEquals(2, catalog.droppedToneObservations());
        }
        finally { releaseWriter.countDown(); catalog.close(); }
        assertEquals(0, tones.active.get());
        tones.publish(sequence(nativeCall));
        assertTrue(Files.exists(audio));
    }

    @Test void exactWinnerPartSourceAndNativeTimesMustAllMatchWithoutAWindowFallback()
    {
        var associations = new ManagedRecordingToneAssociations();
        var nativeCall = snapshot(4, 4, source("four"), 7000, 8000);
        associations.indexed(ManagedRecordingToneAssociations.Key.from(nativeCall), 41, false);
        for(var other: List.of(snapshot(5, 4, source("four"), 7000, 8000),
            snapshot(4, 5, source("four"), 7000, 8000), snapshot(4, 4, source("other"), 7000, 8000),
            snapshot(4, 4, source("four"), 7001, 8000), snapshot(4, 4, source("four"), 7000, 8001)))
            associations.completed(sequence(other));
        assertTrue(associations.ready(0).isEmpty());
        associations.completed(sequence(nativeCall));
        var attachment = associations.ready(0).getFirst();
        assertEquals(41, attachment.recordingId());
        associations.attached(attachment);
        associations.completed(sequence(nativeCall));
        assertTrue(associations.ready(0).isEmpty());
    }

    @Test void closeDrainsMoreThanOneAttachmentBatchForTheSameExactNativePart() throws Exception
    {
        FakeTones tones = new FakeTones();
        var nativeCall = snapshot(7, 7, source("seven"), 11000, 12000);
        Path database = directory.resolve("many.sqlite");
        CountDownLatch indexed = new CountDownLatch(65);
        var catalog = new ManagedRecordingCatalog(database, directory, tones);
        try
        {
            for(int part = 0; part < 65; part++)
            {
                Path audio = directory.resolve("many-" + part + ".mp3"); Files.write(audio, new byte[]{1});
                assertTrue(catalog.submit(audio, call(nativeCall), 1, indexed::countDown));
            }
            assertTrue(indexed.await(5, TimeUnit.SECONDS));
            tones.publish(sequence(nativeCall));
        }
        finally { assertTimeoutPreemptively(Duration.ofSeconds(3), catalog::close); }
        try(var c = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = c.createStatement();
            var row = sql.executeQuery("SELECT count(*) FROM recording_tone_sequence"))
        { assertTrue(row.next()); assertEquals(65, row.getInt(1)); }
    }

    @Test void optionalSubscriptionFailureKeepsRecordingAndCachedLookupAvailable() throws Exception
    {
        var nativeCall = snapshot(8, 8, source("eight"), 13000, 14000);
        Path audio = directory.resolve("no-observer.mp3"); Files.write(audio, new byte[]{1});
        var source = new ManagedRecordingCatalog.CompletedToneSource()
        {
            @Override public CompletedToneSequence lookup(AudioCallSnapshot snapshot) { return sequence(snapshot); }
            @Override public AutoCloseable subscribe(Listener<CompletedToneSequence> listener)
            { throw new IllegalStateException("injected listener capacity"); }
        };
        Path database = directory.resolve("no-observer.sqlite");
        try(var catalog = new ManagedRecordingCatalog(database, directory, source))
        { assertTrue(catalog.submit(audio, call(nativeCall), 1, null)); }
        try(var catalog = new ManagedRecordingCatalog(database, directory, new FakeTones()))
        { assertNotNull(catalog.find(1).pagingToneSummary()); assertTrue(Files.exists(audio)); }
    }

    @Test void cachedAndPreAttachedToneWriteFailuresKeepIndexedAudioAndRetryIndependently() throws Exception
    {
        for(boolean preAttached: List.of(false, true))
        {
            var nativeCall = snapshot(9, 9, source("nine"), 15000, 16000);
            FakeTones tones = new FakeTones();
            tones.cached = preAttached ? null : sequence(nativeCall);
            Path database = directory.resolve("optional-failure-" + preAttached + ".sqlite");
            Path audio = directory.resolve("optional-failure-" + preAttached + ".mp3"); Files.write(audio, new byte[]{1});
            try(var catalog = new ManagedRecordingCatalog(database, directory, tones);
                var c = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = c.createStatement())
            {
                sql.execute("CREATE TRIGGER injected_tone_failure BEFORE INSERT ON recording_tone BEGIN SELECT RAISE(ABORT,'injected'); END");
                CountDownLatch indexed = new CountDownLatch(1);
                var call = call(nativeCall);
                if(preAttached) call = call.withCompletedTones(sequence(nativeCall));
                assertTrue(catalog.submit(audio, call, 1, indexed::countDown));
                assertTrue(indexed.await(2, TimeUnit.SECONDS));
                assertNotNull(catalog.find(1)); assertNull(catalog.find(1).pagingToneSummary()); assertTrue(Files.exists(audio));
                sql.execute("DROP TRIGGER injected_tone_failure");
                assertTimeoutPreemptively(Duration.ofSeconds(3), () -> {
                    while(catalog.find(1).pagingToneSummary() == null) Thread.sleep(10);
                });
            }
        }
    }

    @Test void attachmentFailureRollsBackOnlyOptionalFactsAndCanRetryWithoutReplacingAudio() throws Exception
    {
        Path database = directory.resolve("retry.sqlite"), audio = directory.resolve("retry.mp3");
        Files.write(audio, new byte[]{1});
        var nativeCall = snapshot(6, 6, source("six"), 9000, 10000);
        try(var store = new ManagedRecordingStore(database, directory);
            var c = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = c.createStatement())
        {
            long id = store.insert(ManagedRecordingMetadata.from(call(nativeCall), "retry.mp3", 1));
            sql.execute("CREATE TRIGGER injected_tone_failure BEFORE INSERT ON recording_tone BEGIN SELECT RAISE(ABORT,'injected'); END");
            assertThrows(java.sql.SQLException.class, () -> store.attachCompletedTones(id, sequence(nativeCall)));
            assertNotNull(store.find(id)); assertTrue(Files.exists(audio));
            try(var row = sql.executeQuery("SELECT count(*) FROM recording_tone_sequence"))
            { assertTrue(row.next()); assertEquals(0, row.getInt(1)); }
            sql.execute("DROP TRIGGER injected_tone_failure");
            store.attachCompletedTones(id, sequence(nativeCall));
            store.attachCompletedTones(id, sequence(nativeCall));
            assertEquals(List.of(510.5), firstTone(store.find(id).pagingToneSummary()).get("frequencies_hz"));
        }
    }

    private static Map<?,?> firstTone(Map<String,Object> summary)
    { return (Map<?,?>)((List<?>)summary.get("tones")).getFirst(); }

    private static CompletedAudioCall call(AudioCallSnapshot snapshot)
    { return new CompletedAudioCall(snapshot, List.of(new float[160])); }

    private static CallLegSource source(String name)
    {
        return new CallLegSource(DecoderType.NBFM, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", name, null, 0, null,
            TrunkedIdentityDomain.STANDARD, ChannelConfigurationPolicy.ChannelKind.CONVENTIONAL, false);
    }

    private static AudioCallSnapshot snapshot(long call, long leg, CallLegSource source, long start, long end)
    {
        return new AudioCallSnapshot(new AudioCallId(77, call, 0), null, null, new IdentifierCollection(), Set.of(),
            start, end, 1, 1, start, end, false, true, CallEncryptionState.CLEAR, true, null, VoiceCallQuality.EMPTY,
            new CallLegId(77, leg, 0), source, null);
    }

    private static CompletedToneSequence sequence(AudioCallSnapshot snapshot)
    {
        return new CompletedToneSequence(snapshot, snapshot.startTimestamp(), CompletedToneSequence.Source.DECODED_AUDIO,
            CompletedToneSequence.Method.ANALOG_PCM_TONE_V1,
            List.of(new CompletedToneSequence.Element(List.of(510500), null, 0L, 920, .99)));
    }

    private static class FakeTones implements ManagedRecordingCatalog.CompletedToneSource
    {
        private volatile Listener<CompletedToneSequence> listener;
        private Runnable onLookup;
        private CompletedToneSequence cached;
        private final AtomicInteger active = new AtomicInteger();
        @Override public CompletedToneSequence lookup(AudioCallSnapshot snapshot)
        { if(onLookup != null) onLookup.run(); return cached; }
        @Override public AutoCloseable subscribe(Listener<CompletedToneSequence> consumer)
        {
            listener = consumer; active.incrementAndGet();
            return () -> { listener = null; active.decrementAndGet(); };
        }
        private void publish(CompletedToneSequence sequence)
        { Listener<CompletedToneSequence> current = listener; if(current != null) current.receive(sequence); }
    }
}
