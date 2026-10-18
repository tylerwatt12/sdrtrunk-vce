/* Copyright (C) 2026 Dennis Sheirer. SPDX-License-Identifier: GPL-3.0-or-later */
package io.github.dsheirer.record.managed;

import static org.junit.jupiter.api.Assertions.*;
import io.github.dsheirer.audio.call.*;
import io.github.dsheirer.configuration.ChannelConfigurationPolicy.ChannelKind;
import io.github.dsheirer.identifier.IdentifierCollection;
import io.github.dsheirer.module.decode.DecoderType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagedRecordingReadyTest
{
    @TempDir Path directory;

    @Test void readyContainsEveryExactReceiverReferenceAndOnlyFiresAfterReadableCommit() throws Exception
    {
        var call = call(1);
        var loser = new CallLegSummary(new CallLegId(33, 2, 0), call.snapshot().callLegSource(), 1000, 2000,
            VoiceCallQuality.EMPTY, 160, false, false, false, null).withActivityCallToken("other-receiver");
        call = new CompletedAudioCall(call.logicalCallId(), call.snapshot(), call.audioBuffers(), call.resolvedPolicy(),
            List.of(call.callLegSummaries().getFirst(), loser));
        var expected = call.sourceCallReferences();
        Path audio = directory.resolve("ready.mp3"); Files.write(audio, new byte[]{1});
        CountDownLatch ready = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try(var catalog = new ManagedRecordingCatalog(directory.resolve("ready.sqlite"), directory))
        {
            catalog.addRecordingReadyListener(event -> {
                try
                {
                    assertEquals(expected, event.sourceCallReferences());
                    assertEquals(expected, catalog.sourceCallReferences(event.recordingId()));
                    assertTrue(Files.isRegularFile(catalog.audioPath(event.recordingId())));
                    assertNotNull(catalog.find(event.recordingId()));
                }
                catch(Throwable problem) { failure.set(problem); }
                finally { ready.countDown(); }
            });
            assertTrue(catalog.submit(audio, call, 1, null));
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            if(failure.get() != null) throw new AssertionError(failure.get());
        }
    }

    @Test void observerFailureAndUnsubscribeNeverRemoveIndexedAudioOrSuppressOtherObservers() throws Exception
    {
        Path audio = directory.resolve("observer.mp3"); Files.write(audio, new byte[]{1});
        CountDownLatch ready = new CountDownLatch(1);
        var seen = new ArrayList<Long>();
        try(var catalog = new ManagedRecordingCatalog(directory.resolve("observer.sqlite"), directory))
        {
            catalog.addRecordingReadyListener(event -> { throw new IllegalStateException("injected observer failure"); });
            var cancelled = catalog.addRecordingReadyListener(event -> fail("Unsubscribed observer invoked"));
            cancelled.close(); cancelled.close();
            catalog.addRecordingReadyListener(event -> { seen.add(event.recordingId()); ready.countDown(); });
            assertTrue(catalog.submit(audio, call(3), 1, null));
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            assertEquals(List.of(1L), seen);
            assertNotNull(catalog.find(1)); assertTrue(Files.exists(audio));
        }
    }

    @Test void failedCommitNeverAnnouncesReadyAndListenerRegistrationIsBounded() throws Exception
    {
        Path database = directory.resolve("failed.sqlite"), audio = directory.resolve("failed.mp3");
        Files.write(audio, new byte[]{1});
        var seen = new ArrayList<Long>();
        var catalog = new ManagedRecordingCatalog(database, directory);
        try
        {
            for(int index = 0; index < 8; index++) catalog.addRecordingReadyListener(event -> seen.add(event.recordingId()));
            assertThrows(IllegalStateException.class, () -> catalog.addRecordingReadyListener(event -> {}));
            try(var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement())
            {
                sql.execute("CREATE TRIGGER fail_recording BEFORE INSERT ON recording_call BEGIN SELECT RAISE(ABORT,'injected'); END");
                assertTrue(catalog.submit(audio, call(4), 1, null));
                assertTimeoutPreemptively(Duration.ofSeconds(3), catalog::close);
                assertTrue(seen.isEmpty());
                assertFalse(Files.exists(audio));
                sql.execute("DROP TRIGGER fail_recording");
            }
            assertThrows(IllegalStateException.class, () -> catalog.addRecordingReadyListener(event -> {}));
        }
        finally { catalog.close(); }
    }

    private static CompletedAudioCall call(long sequence)
    {
        var id = new AudioCallId(33, sequence, 0);
        var source = new CallLegSource(DecoderType.NBFM, "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", "Dispatch", null,
            0, null, null, ChannelKind.CONVENTIONAL, false);
        var snapshot = new AudioCallSnapshot(id, null, null, new IdentifierCollection(), Set.of(), 1000, 2000,
            1, 0, 1000, 2000, false, true, CallEncryptionState.CLEAR, true, null, VoiceCallQuality.EMPTY,
            CallLegId.from(id), source, null, null, "ready-call:" + sequence);
        return new CompletedAudioCall(snapshot, List.of(new float[160]));
    }
}
